# SPDX-License-Identifier: MPL-2.0
"""Script context objects and result extraction."""

import copy
import json
import types
import xml.etree.ElementTree as ET
from collections.abc import Mapping

from . import _codec
from ._codec import ABSENT, ContractError, check_json_document, decode, encode
from ._result_types import RESERVED_SOURCE_MAP_KEYS, RESPONSE_STATUSES, Message, Response, Status, UnsupportedValue
from ._maps import GlobalMap, ScriptMap

MAP_NAMES = ("channelMap", "connectorMap", "sourceMap", "responseMap",
             "globalMap", "globalChannelMap", "configurationMap")
KINDS = ("json", "xml", "raw")


class ScriptLogger:
    """Collects bounded per-invocation log records."""

    LEVELS = ("debug", "info", "warning", "error")

    def __init__(self, limit_chars):
        self.records = []
        self.truncated = False
        self._remaining = limit_chars

    def _log(self, level, message, *args):
        text = str(message)
        if args:
            try:
                text = text % args
            except (TypeError, ValueError):
                text = " ".join([text] + [str(a) for a in args])
        if self.truncated:
            return
        encoded = text.encode("utf-8")
        if len(self.records) >= 1024:
            self.truncated = True
            return
        if len(encoded) > self._remaining:
            encoded = encoded[: max(self._remaining, 0)]
            text = encoded.decode("utf-8", errors="ignore")
            self.truncated = True
        self._remaining -= max(1, len(encoded))
        self.records.append({"level": level, "message": text})

    def debug(self, message, *args):
        self._log("debug", message, *args)

    def info(self, message, *args):
        self._log("info", message, *args)

    def warning(self, message, *args):
        self._log("warning", message, *args)

    warn = warning

    def error(self, message, *args):
        self._log("error", message, *args)

    def write(self, text):
        """File-like sink used for print() and sys.stderr inside a script."""
        if text and text != "\n":
            self._log("stdout", text.rstrip("\n"))
        return len(text)

    def flush(self):
        pass


def _readonly(mapping):
    return types.MappingProxyType(mapping)


class _Maps:
    def __init__(self, wire_maps, logger):
        self.before = {}
        self.values = {}
        self.writable = {}
        for name in MAP_NAMES:
            spec = wire_maps.get(name)
            if spec is None:
                continue
            values = {k: decode(v, name, k) for k, v in spec.get("values", {}).items()}
            self.writable[name] = bool(spec.get("writable"))
            self.before[name] = {k: _canonical(encode(v, "%s[%r]" % (name, k))) for k, v in values.items()}
            map_type = GlobalMap if name in ("globalMap", "globalChannelMap") else ScriptMap
            self.values[name] = map_type(name, values, self.writable[name], self.values, logger, spec.get("aliases"))

    def view(self, name):
        if name not in self.values:
            raise AttributeError("%s is not available in this context" % name)
        return self.values[name]

    def delta(self):
        """Explicit set/delete operations. Raises ContractError for read-only edits."""
        out = {}
        for name, values in self.values.items():
            before = self.before[name]
            sets = {}
            for key, value in values.items():
                if not isinstance(key, str):
                    raise ContractError("%s has a non-string key %r" % (name, key), name)
                encoded = encode(value, "%s[%r]" % (name, key))
                if key not in before or before[key] != _canonical(encoded):
                    sets[key] = encoded
            # Response-map name lookup does not create an actual map entry.
            deletes = [key for key in before if not dict.__contains__(values, key)]
            if not sets and not deletes:
                continue
            if not self.writable[name]:
                changed = sorted(list(sets) + deletes)
                raise ContractError("%s is read-only; changed key(s): %s" % (name, ", ".join(map(repr, changed))), name)
            out[name] = {"set": sets, "delete": deletes}
        return out


def _canonical(encoded):
    return json.dumps(encoded, sort_keys=True, ensure_ascii=False, separators=(",", ":"))


class _Document:
    """msg or tmp: a typed transformer value."""

    def __init__(self, name, wire):
        self.name = name
        self.kind = wire["kind"]
        if self.kind not in KINDS:
            raise _codec.ProtocolError("%s has unknown kind %r" % (name, self.kind))
        self.text = wire["text"]
        self.value = json.loads(self.text) if self.kind == "json" else self.text
        self.snapshot = copy.deepcopy(self.value) if self.kind == "json" else None
        self.assigned = False

    def set(self, value):
        self.value = value
        self.assigned = True

    def result(self):
        """The changed wire value, or None when unchanged."""
        if self.kind == "json":
            text = check_json_document(self.value, self.name)
            if not self.assigned and text == json.dumps(
                self.snapshot, ensure_ascii=False, allow_nan=False, separators=(",", ":")
            ):
                return None
            return {"kind": "json", "text": text}
        value = self.value
        if self.kind == "xml" and isinstance(value, ET.Element):
            value = ET.tostring(value, encoding="unicode")
        if not isinstance(value, str):
            raise ContractError(
                "%s holds %s text and must stay a str; got %s"
                % (self.name, "XML" if self.kind == "xml" else "raw", type(value).__name__),
                self.name,
            )
        if value == self.text:
            return None
        return {"kind": self.kind, "text": value}


class _ResponseFields:
    __slots__ = ("_status", "_status_message", "_error", "_changed")

    def __init__(self, wire):
        object.__setattr__(self, "_status", wire.get("status"))
        object.__setattr__(self, "_status_message", wire.get("statusMessage"))
        object.__setattr__(self, "_error", wire.get("error"))
        object.__setattr__(self, "_changed", False)

    @property
    def status(self):
        return self._status

    @status.setter
    def status(self, value):
        if value not in RESPONSE_STATUSES:
            raise ContractError("response.status must be one of %s" % ", ".join(RESPONSE_STATUSES), "response.status")
        object.__setattr__(self, "_status", value)
        object.__setattr__(self, "_changed", True)

    @property
    def status_message(self):
        return self._status_message

    @status_message.setter
    def status_message(self, value):
        _optional_str(value, "response.status_message")
        object.__setattr__(self, "_status_message", value)
        object.__setattr__(self, "_changed", True)

    @property
    def error(self):
        return self._error

    @error.setter
    def error(self, value):
        _optional_str(value, "response.error")
        object.__setattr__(self, "_error", value)
        object.__setattr__(self, "_changed", True)

    def __setattr__(self, name, value):
        if name in ("status", "status_message", "error"):
            object.__setattr__(self, name, value)
        else:
            raise AttributeError("response has no field %r" % name)

    def result(self):
        if not self._changed:
            return None
        return {"status": self._status, "statusMessage": self._status_message, "error": self._error}


def _optional_str(value, where):
    if value is not None and not isinstance(value, str):
        raise ContractError("%s must be a str or None" % where, where)


def _frozen(value):
    if isinstance(value, dict):
        return _readonly({k: _frozen(v) for k, v in value.items()})
    if isinstance(value, list):
        return tuple(_frozen(v) for v in value)
    return value


class _BaseContext:
    def __init__(self, wire, logger):
        self._wire = wire
        self.role = wire["role"]
        self.phase = wire.get("phase")
        self.logger = logger
        self.message = _frozen(decode(wire.get("message", {})))
        self._maps = _Maps(wire.get("maps", {}), logger)

    @property
    def channelMap(self):
        return self._maps.view("channelMap")

    @property
    def connectorMap(self):
        return self._maps.view("connectorMap")

    @property
    def sourceMap(self):
        return self._maps.view("sourceMap")

    @property
    def responseMap(self):
        return self._maps.view("responseMap")

    def script_globals(self):
        values = {name: getattr(self, name) for name in ("logger", "phase", "message")}
        values.update({name: self._maps.view(name) for name in self._maps.values})
        return values

    def _with_map_changes(self, result):
        if self._maps.values:
            result["maps"] = self._maps.delta()
        return result

    def result_from_globals(self, values, returned=None):
        return self.result_for(returned)


class TransformContext(_BaseContext):
    """Per-invocation documents and maps behind a transformer script's globals."""

    def __init__(self, wire, logger):
        super().__init__(wire, logger)
        self._msg = _Document("msg", wire["msg"])
        tmp = wire.get("tmp")
        self._tmp = _Document("tmp", tmp) if tmp is not None else None
        response = wire.get("response")
        self._response = _ResponseFields(response) if response is not None else None

    @property
    def msg(self):
        return self._msg.value

    @msg.setter
    def msg(self, value):
        self._msg.set(value)

    @property
    def has_tmp(self):
        return self._tmp is not None

    @property
    def tmp(self):
        if self._tmp is None:
            raise AttributeError("this transformer has no outbound template; check has_tmp")
        return self._tmp.value

    @tmp.setter
    def tmp(self, value):
        if self._tmp is None:
            raise AttributeError("this transformer has no outbound template; assign msg instead")
        self._tmp.set(value)

    @property
    def response(self):
        if self._response is None:
            raise AttributeError("response fields exist only in a response transformer")
        return self._response

    def result(self):
        out = {"maps": self._maps.delta()}
        msg = self._msg.result()
        if msg is not None:
            out["msg"] = msg
        if self._tmp is not None:
            tmp = self._tmp.result()
            if tmp is not None:
                out["tmp"] = tmp
        if self._response is not None:
            response = self._response.result()
            if response is not None:
                out["response"] = response
        return out

    def script_globals(self):
        values = super().script_globals()
        values.update(msg=self.msg, has_tmp=self.has_tmp)
        if self.has_tmp:
            values["tmp"] = self.tmp
        if self._response is not None:
            values["response"] = self.response
        return values

    def result_from_globals(self, values, returned=None):
        for name, document in (("msg", self._msg), ("tmp", self._tmp)):
            if document is None:
                if name in values:
                    raise ContractError("this transformer has no outbound template; assign msg instead", name)
            elif name not in values:
                raise ContractError("%s must not be deleted; assign a supported replacement instead" % name, name)
            elif values[name] is not document.value:
                document.set(values[name])
        return self.result()


class FilterContext(TransformContext):
    """A native rule must return exactly True or False; documents/maps match transformers."""

    def result_from_globals(self, values, returned=None):
        accepted = returned
        if type(accepted) is not bool:
            raise ContractError("Filter must return exactly True or False", "result")
        out = super().result_from_globals(values)
        out["accepted"] = accepted
        return out


class WriterContext(_BaseContext):
    """Writer payload, maps and result extraction."""

    def __init__(self, wire, logger):
        super().__init__(wire, logger)
        payload = wire.get("payload")
        self._payload = None if payload is None else payload

    @property
    def payload(self):
        """The outgoing content: encoded, else transformed, else raw. None when absent."""
        return self._payload

    def script_globals(self):
        values = super().script_globals()
        values.update(payload=self.payload)
        return values

    def result_for(self, value):
        if value is None:
            response = {"status": "SENT", "data": "", "statusMessage": None, "error": None}
        elif isinstance(value, Status):
            response = {"status": value.value, "data": "", "statusMessage": None, "error": None}
        elif isinstance(value, str):
            response = {"status": "SENT", "data": value, "statusMessage": None, "error": None}
        elif isinstance(value, Response):
            if value.status not in RESPONSE_STATUSES:
                raise ContractError("Response.status must be one of %s" % ", ".join(RESPONSE_STATUSES), "status")
            for field in ("data", "status_message", "error"):
                _optional_str(getattr(value, field), "Response." + field)
            response = {
                "status": value.status.value if isinstance(value.status, Status) else value.status,
                "data": value.data,
                "statusMessage": value.status_message,
                "error": value.error,
            }
        else:
            raise ContractError(
                "Writer must return None, a str, Status, or Response; got %s. Serialize JSON explicitly."
                % type(value).__name__,
                "result",
            )
        return {"response": response, "maps": self._maps.delta()}


class ReaderContext(_BaseContext):
    """Invocation-local source metadata and message result extraction."""

    def __init__(self, wire, logger):
        super().__init__(wire, logger)
        source_map = {key: decode(value, "sourceMap", key) for key, value in wire.get("sourceMap", {}).items()}
        self._source_map = ScriptMap("sourceMap", source_map, True, {}, logger)
        self._max_messages = int(wire.get("maxMessages", 1000))
        self._destination_ids = frozenset(wire.get("destinationIds", []))

    @property
    def sourceMap(self):
        return self._source_map

    def script_globals(self):
        values = super().script_globals()
        values.update(sourceMap=self.sourceMap)
        return values

    def result_for(self, value):
        self._encode_source_map(self.sourceMap, "sourceMap")
        if value is None:
            items = []
        elif isinstance(value, Message):
            items = [value]
        elif isinstance(value, list):
            items = value
        else:
            raise ContractError(
                "Reader must return None, a Message, or a list of Message objects; got %s"
                % type(value).__name__,
                "result",
            )
        if len(items) > self._max_messages:
            raise ContractError(
                "result contains %d messages; the limit is %d per poll" % (len(items), self._max_messages),
                "result",
            )
        out = []
        for index, item in enumerate(items):
            where = "message[%d]" % index
            if not isinstance(item, Message):
                raise ContractError("%s is %s, not a Message" % (where, type(item).__name__), where)
            out.append(self._message(item, where))
        return self._with_map_changes({"messages": out})

    def _message(self, item, where):
        payload = item.payload
        if isinstance(payload, (bytearray, memoryview)):
            payload = bytes(payload)
        if not isinstance(payload, (str, bytes)):
            raise ContractError("%s.payload must be str or bytes" % where, where + ".payload")
        if not isinstance(item.source_map, dict):
            raise ContractError("%s.source_map must be a dict" % where, where + ".source_map")
        source_map = dict(self.sourceMap)
        source_map.update(item.source_map)
        encoded_source_map = self._encode_source_map(source_map, where + ".source_map")
        ids = item.destination_ids
        if ids is not None:
            if not isinstance(ids, (list, tuple)) or any(isinstance(i, bool) or not isinstance(i, int) for i in ids):
                raise ContractError("%s.destination_ids must be None or a list of int" % where, where + ".destination_ids")
            unknown = sorted(set(i for i in ids if i not in self._destination_ids))
            if unknown:
                raise ContractError(
                    "%s.destination_ids has unknown or disabled destination(s): %s"
                    % (where, ", ".join(map(str, unknown))),
                    where + ".destination_ids",
                )
            ids = list(ids)
        return {
            "payload": encode(payload, where + ".payload"),
            "sourceMap": encoded_source_map,
            "destinationIds": ids,
        }

    @staticmethod
    def _encode_source_map(values, where):
        reserved = sorted(k for k in values if isinstance(k, str) and k in RESERVED_SOURCE_MAP_KEYS)
        if reserved:
            raise ContractError("%s uses reserved engine key(s): %s" % (where, ", ".join(reserved)), where)
        encoded = encode(values, where)
        ReaderContext._check_source_values(values, where)
        return encoded

    @staticmethod
    def _check_source_values(value, where):
        # Unlike shared-map deltas, Reader metadata has no engine object store
        # against which an opaque value can be resolved.
        if isinstance(value, UnsupportedValue):
            raise ContractError("%s cannot contain an unsupported engine value" % where, where)
        if isinstance(value, Mapping):
            for key, child in value.items():
                ReaderContext._check_source_values(child, "%s[%r]" % (where, key))
        elif isinstance(value, (list, tuple)):
            for index, child in enumerate(value):
                ReaderContext._check_source_values(child, "%s[%d]" % (where, index))


class FunctionContext(_BaseContext):
    """Explicit JSON arguments, calling maps and function result extraction."""
    def __init__(self, wire, logger):
        super().__init__(wire, logger)
        arguments = wire.get("arguments")
        if not isinstance(arguments, list):
            raise ContractError("Python function arguments must be a JSON array", "arguments")
        check_json_document(arguments, "arguments")
        self.arguments = arguments

    def script_globals(self):
        values = super().script_globals()
        values.update(arguments=self.arguments)
        return values

    def result_for(self, value):
        check_json_document([value], "result")
        return self._with_map_changes({"value": value})


CONTEXTS = {"filter": FilterContext, "transform": TransformContext, "write": WriterContext, "read": ReaderContext, "call": FunctionContext}
