# SPDX-License-Identifier: MPL-2.0
"""Compile-only validation. Never imports or runs the script.

Input on stdin: {"items": [{"id", "role", "label", "source"}]}
Output on stdout: {"items": [{"id", "ok", "diagnostics": [...]}], "python": {...}}
"""

import json
import platform
import sys
import sysconfig

from ._script import compile_script

ROLES = ("filter", "transform", "read", "write", "call")


def _diagnostic(code, message, line=None, column=None):
    return {
        "severity": "error", "code": code, "message": message,
        "field": "pythonScript", "line": line, "column": column,
    }


def check(source, role, label="<script>"):
    if role not in ROLES:
        return [_diagnostic("UNKNOWN_ROLE", "unknown script role %r" % role)]
    try:
        compile_script(source, role, label)
    except SyntaxError as e:
        return [_diagnostic("PYTHON_SYNTAX", e.msg, e.lineno, e.offset)]
    except (ValueError, RecursionError, MemoryError) as e:
        return [_diagnostic("PYTHON_SYNTAX", "%s: %s" % (type(e).__name__, e))]
    return []


def describe_python():
    import ensurepip
    import hashlib
    from pathlib import Path
    bundled = Path(ensurepip.__file__).parent / "_bundled"
    wheels = sorted(bundled.glob("pip-*.whl"))
    if not wheels:
        external = sysconfig.get_config_var("WHEEL_PKG_DIR")
        wheels = sorted(Path(external).glob("pip-*.whl")) if external else []
    if not wheels:
        raise RuntimeError("the approved interpreter has no hashable ensurepip installer wheel")
    bootstrap = {"version": ensurepip.version(), "wheels": [
        {"filename": wheel.name, "sha256": hashlib.sha256(wheel.read_bytes()).hexdigest()} for wheel in wheels
    ]}
    return {
        "implementation": platform.python_implementation(),
        "version": platform.python_version(),
        "soabi": sysconfig.get_config_var("SOABI") or "",
        "platform": sysconfig.get_platform(),
        "machine": platform.machine(),
        "system": platform.system(),
        "executable": sys.executable,
        "installerBootstrap": bootstrap,
    }


def main():
    request = json.load(sys.stdin)
    items = []
    for item in request.get("items", []):
        diagnostics = check(item.get("source", ""), item.get("role"), item.get("label", "<script>"))
        items.append({"id": item.get("id"), "role": item.get("role"), "ok": not diagnostics, "diagnostics": diagnostics})
    json.dump({"items": items, "python": describe_python()}, sys.stdout)


if __name__ == "__main__":
    main()
