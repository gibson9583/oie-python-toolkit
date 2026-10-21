# SPDX-License-Identifier: MPL-2.0
"""Persistent worker process. Started by the engine with:

    <venv>/bin/python -I -S -c <verified bundled SDK bootstrap>

The engine bootstraps this module from its verified SDK source under -I -S.
The worker establishes containment before loading dependency site/.pth code,
handles one request at a time and exits when its protocol input closes.
"""

import os
import sys


def _reserve_streams():
    """Keep the protocol pipes for the worker and divert the OS-level stdio.

    This runs before any user code is imported, so native libraries that write
    to file descriptor 1 cannot corrupt the protocol stream.
    """
    proto_in = os.dup(0)
    proto_out = os.dup(1)
    devnull = os.open(os.devnull, os.O_RDONLY)
    os.dup2(devnull, 0)
    os.close(devnull)
    os.dup2(2, 1)
    return os.fdopen(proto_in, "rb", buffering=0), os.fdopen(proto_out, "wb", buffering=0)


def _own_process_group():
    if hasattr(os, "setsid"):
        try:
            os.setsid()
        except OSError:
            pass


def _end_process_group(code):
    """Exit and take any processes the script started with us (POSIX).

    The worker leads its own process group, so the group signal also ends the
    worker. It can run from any thread.
    """
    sys.stderr.flush()
    if hasattr(os, "killpg"):
        try:
            import signal

            if os.getpgid(0) == os.getpid():
                os.killpg(os.getpid(), signal.SIGKILL)
        except Exception:  # noqa: BLE001 - fall through to a plain exit
            pass
    os._exit(code)


def _supervise_parent(proto_in, proto_out):
    """Forward the engine pipe from an independent POSIX watchdog process.

    A Python thread cannot notice engine EOF while a native extension holds
    the worker's GIL. This child never executes or imports user code and owns
    the original input pipe, so it can kill the process group independently.
    """
    if not hasattr(os, "fork") or not hasattr(os, "killpg"):
        raise RuntimeError("owned Python process containment requires POSIX; Windows support needs qualified Job Objects")
    import select
    import signal

    worker_pid = os.getpid()
    if os.getpgid(0) != worker_pid:
        raise RuntimeError("the worker could not establish its owned process group")
    forward_read, forward_write = os.pipe()
    watchdog_pid = os.fork()
    if watchdog_pid == 0:
        os.close(forward_read)
        proto_out.close()
        os.set_blocking(proto_in.fileno(), False)
        os.set_blocking(forward_write, False)
        pending = bytearray()
        input_events = select.poll()
        input_events.register(proto_in.fileno(), select.POLLIN | select.POLLHUP | select.POLLERR)
        try:
            while os.getppid() == worker_pid:
                # POLLHUP observes a closed engine pipe even when unread bytes
                # remain and backpressure prevents forwarding another byte.
                if any(flags & (select.POLLHUP | select.POLLERR) for _, flags in input_events.poll(0)):
                    break
                room = 65536 - len(pending)
                readable, writable, _ = select.select([proto_in.fileno()] if room else [], [forward_write] if pending else [], [], 0.2)
                if writable:
                    sent = os.write(forward_write, pending)
                    del pending[:sent]
                if readable:
                    data = os.read(proto_in.fileno(), min(room, 65536))
                    if not data:
                        break
                    pending.extend(data)
        except (OSError, ValueError):
            pass
        finally:
            try:
                os.killpg(worker_pid, signal.SIGKILL)
            finally:
                os._exit(0)
    os.close(forward_write)
    proto_in.close()
    return os.fdopen(forward_read, "rb", buffering=0), watchdog_pid


def main():
    _own_process_group()
    proto_in, proto_out = _reserve_streams()
    proto_in, watchdog_pid = _supervise_parent(proto_in, proto_out)

    # The trusted SDK is bootstrapped under -S. Site processing may execute
    # dependency .pth files, including native code, so it belongs after the
    # independent watchdog and protocol stream reservation.
    import site
    site.main()

    import builtins
    import io
    import platform
    import queue
    import sysconfig
    import threading
    import time
    import traceback
    import types

    from . import CONTRACT_VERSION, PROTOCOL_VERSION, __version__
    from ._codec import MAX_FRAME_BYTES, ContractError, ProtocolError, read_frame, write_frame
    from ._context import CONTEXTS, ScriptLogger
    from ._result_types import UnsupportedValueError
    from ._script import compile_script, execute_script
    from .validate import describe_python

    max_frame = int(os.environ.get("OIE_PYTHON_MAX_FRAME", MAX_FRAME_BYTES))
    log_limit = int(os.environ.get("OIE_PYTHON_LOG_LIMIT", 64 * 1024))
    prefix = sys.prefix
    sdk_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    requests = queue.Queue(maxsize=2)

    def reader():
        # A dedicated reader notices the engine going away even while a script
        # runs, and ends the process group instead of leaving an orphan.
        try:
            while True:
                frame = read_frame(proto_in, max_frame)
                if frame is None:
                    break
                requests.put_nowait(frame)
        except Exception as e:  # noqa: BLE001 - any protocol failure ends the worker
            sys.stderr.write("oie_python worker: protocol input failed: %s\n" % e)
        _end_process_group(0)

    threading.Thread(target=reader, name="oie-protocol-reader", daemon=True).start()

    compiled = {}

    def send(frame):
        write_frame(proto_out, frame, max_frame)

    def redact(text):
        out = text.replace(prefix, "<env>") if prefix else text
        return out.replace(sdk_root, "<sdk>")

    def error(request_id, kind, message, **extra):
        payload = {"kind": kind, "message": redact(message)}
        payload.update(extra)
        return {"type": "ERROR", "id": request_id, "error": payload}

    def script_error(request_id, exc, label, logs):
        tb = exc.__traceback__
        line = None
        for frame, lineno in traceback.walk_tb(tb):
            if frame.f_code.co_filename == label:
                line = lineno
        text = "".join(traceback.format_exception(type(exc), exc, tb))
        kind = "contract" if isinstance(exc, (ContractError, UnsupportedValueError)) else "script"
        out = error(
            request_id,
            kind,
            "%s: %s" % (type(exc).__name__, exc),
            type=type(exc).__name__,
            traceback=redact(text)[-16000:],
            line=line,
            field=getattr(exc, "field", None),
        )
        out.update(logs)
        return out

    def hello(request):
        return {
            "type": "HELLO",
            "id": request.get("id"),
            "protocol": PROTOCOL_VERSION,
            "contract": CONTRACT_VERSION,
            "sdk": __version__,
            "pid": os.getpid(),
            "processGroupLeader": hasattr(os, "getpgid") and os.getpgid(0) == os.getpid(),
            "watchdogPid": watchdog_pid,
            "python": describe_python(),
        }

    def load(request):
        spec = request["spec"]
        role = request["role"]
        label = request["label"]
        if role not in CONTEXTS:
            return error(request.get("id"), "protocol", "unknown role %r" % role)
        try:
            code = compile_script(request["source"], role, label)
        except SyntaxError as e:
            return error(request.get("id"), "syntax", "%s" % e.msg, line=e.lineno, column=e.offset)
        compiled[spec] = (code, role, label)
        return {"type": "RESULT", "id": request.get("id"), "result": {"loaded": spec}}

    def execute(request):
        request_id = request.get("id")
        spec = request["spec"]
        if spec not in compiled:
            return error(request_id, "protocol", "script %s is not loaded" % spec)
        code, role, label = compiled[spec]
        logger = ScriptLogger(log_limit)
        logs = lambda: {"logs": logger.records, "logsTruncated": logger.truncated}  # noqa: E731
        saved = sys.stdout, sys.stderr
        sys.stdout = sys.stderr = logger
        started = time.monotonic()
        # Dataclasses and other module-aware code resolve classes via sys.modules.
        module = types.ModuleType("__oie_script__")
        module_globals = module.__dict__
        module_globals.update(__builtins__=builtins, __file__=label)
        missing = object()
        previous_module = sys.modules.get("__oie_script__", missing)
        sys.modules["__oie_script__"] = module
        try:
            ctx = CONTEXTS[role](request["context"], logger)
            module_globals.update(ctx.script_globals())
            returned = execute_script(code, module_globals)
            result = ctx.result_from_globals(module_globals, returned)
        except ProtocolError as e:
            return error(request_id, "protocol", str(e))
        except BaseException as e:  # noqa: BLE001 - every script failure is reported
            if isinstance(e, (SystemExit, KeyboardInterrupt)):
                e = RuntimeError("the script raised %s" % type(e).__name__)
            return script_error(request_id, e, label, logs())
        finally:
            if previous_module is missing:
                sys.modules.pop("__oie_script__", None)
            else:
                sys.modules["__oie_script__"] = previous_module
            sys.stdout, sys.stderr = saved
        out = {"type": "RESULT", "id": request_id, "result": result,
               "elapsedMicros": int((time.monotonic() - started) * 1e6)}
        out.update(logs())
        return out

    handlers = {"HELLO": hello, "LOAD": load, "EXECUTE": execute}
    while True:
        request = requests.get()
        handler = handlers.get(request.get("type"))
        if handler is None:
            reply = error(request.get("id"), "protocol", "unknown request type %r" % request.get("type"))
        else:
            try:
                reply = handler(request)
            except Exception as e:  # noqa: BLE001
                reply = error(request.get("id"), "protocol", "%s: %s" % (type(e).__name__, e))
        try:
            send(reply)
        except ContractError as e:
            send(error(request.get("id"), "contract", str(e)))
        except (BrokenPipeError, OSError):
            _end_process_group(0)


if __name__ == "__main__":
    main()
