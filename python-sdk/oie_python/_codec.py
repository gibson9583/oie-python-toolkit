# SPDX-License-Identifier: MPL-2.0
"""Frame and value codec for the engine/worker protocol.

A frame is a 4-byte big-endian length followed by that many bytes of UTF-8
JSON. Values that JSON cannot carry exactly use a tagged object:

    {"$t": "int", "v": "123"}            signed 64-bit integer
    {"$t": "bytes", "v": "<base64>"}     bytes
    {"$t": "map", "v": {...}}            object that itself has a "$t" key
    {"$t": "unsupported", "map": m, "key": k, "type": java_type}
    {"$t": "absent"}                     explicitly absent value

Plain JSON objects, arrays, strings, booleans, null and finite floats carry
themselves.
"""

import base64
import json
import math
import re
import struct
from collections.abc import Mapping

from ._result_types import ContractError, UnsupportedValue

MAX_FRAME_BYTES = 16 * 1024 * 1024
MAX_DEPTH = 64
INT64_MIN = -(2 ** 63)
INT64_MAX = 2 ** 63 - 1
JS_SAFE_INT = 2 ** 53 - 1

ABSENT = object()


class ProtocolError(Exception):
    """The peer sent a frame that violates the protocol."""


def _reject_constant(name):
    raise ProtocolError("non-finite number %s in frame" % name)


def _unique_object(pairs):
    out = {}
    for key, value in pairs:
        if key in out:
            raise ProtocolError("duplicate field %r in frame" % key)
        out[key] = value
    return out


def read_frame(stream, max_bytes=MAX_FRAME_BYTES):
    """Read one frame. Returns None on a clean EOF before the length."""
    header = _read_exact(stream, 4, allow_eof=True)
    if header is None:
        return None
    (length,) = struct.unpack(">I", header)
    if length > max_bytes:
        raise ProtocolError("frame of %d bytes exceeds the %d byte limit" % (length, max_bytes))
    body = _read_exact(stream, length, allow_eof=False)
    try:
        value = json.loads(body.decode("utf-8"), parse_constant=_reject_constant, object_pairs_hook=_unique_object)
    except (UnicodeDecodeError, ValueError) as e:
        raise ProtocolError("malformed frame: %s" % e)
    if not isinstance(value, dict):
        raise ProtocolError("frame is not a JSON object")
    return value


def write_frame(stream, value, max_bytes=MAX_FRAME_BYTES):
    body = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")
    if len(body) > max_bytes:
        raise ContractError("result of %d bytes exceeds the %d byte frame limit" % (len(body), max_bytes))
    stream.write(struct.pack(">I", len(body)) + body)
    stream.flush()


def _read_exact(stream, n, allow_eof):
    chunks = []
    remaining = n
    while remaining:
        chunk = stream.read(remaining)
        if not chunk:
            if allow_eof and remaining == n:
                return None
            raise ProtocolError("unexpected end of stream")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def encode(value, where="value", depth=0, _active=None):
    """Encode a Python value into its wire form or raise ContractError."""
    if depth > MAX_DEPTH:
        raise ContractError("%s is nested more than %d levels deep" % (where, MAX_DEPTH), where)
    if value is None or isinstance(value, str):
        return value
    if isinstance(value, bool):
        return value
    if isinstance(value, int):
        if value < INT64_MIN or value > INT64_MAX:
            raise ContractError(
                "%s is an integer outside the signed 64-bit range; convert it to a string" % where, where
            )
        return {"$t": "int", "v": str(value)}
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ContractError("%s is not a finite number" % where, where)
        return value
    if isinstance(value, (bytes, bytearray, memoryview)):
        return {"$t": "bytes", "v": base64.b64encode(bytes(value)).decode("ascii")}
    if isinstance(value, UnsupportedValue):
        map_name, key = value.origin
        return {"$t": "unsupported", "map": map_name, "key": key, "type": value.java_type}
    if _active is None:
        _active = set()
    if isinstance(value, (list, tuple)):
        _enter(value, where, _active)
        try:
            return [encode(v, "%s[%d]" % (where, i), depth + 1, _active) for i, v in enumerate(value)]
        finally:
            _active.discard(id(value))
    if isinstance(value, Mapping):
        _enter(value, where, _active)
        try:
            out = {}
            for k, v in value.items():
                if not isinstance(k, str):
                    raise ContractError("%s has a non-string key %r" % (where, k), where)
                out[k] = encode(v, "%s[%r]" % (where, k), depth + 1, _active)
            return {"$t": "map", "v": out} if "$t" in out else out
        finally:
            _active.discard(id(value))
    raise ContractError(
        "%s has unsupported type %s; use str, bytes, int, float, bool, None, list or dict"
        % (where, type(value).__name__),
        where,
    )


def _enter(value, where, active):
    if id(value) in active:
        raise ContractError("%s contains a reference cycle" % where, where)
    active.add(id(value))


def decode(value, map_name=None, key=None, depth=0):
    """Decode a wire value. Unsupported values become UnsupportedValue sentinels."""
    if depth > MAX_DEPTH:
        raise ProtocolError("value nested more than %d levels deep" % MAX_DEPTH)
    if isinstance(value, list):
        return [decode(v, map_name, key, depth + 1) for v in value]
    if isinstance(value, dict):
        tag = value.get("$t")
        if tag is None:
            return {k: decode(v, map_name, key, depth + 1) for k, v in value.items()}
        if tag == "int":
            raw = value.get("v")
            if not isinstance(raw, str) or re.fullmatch(r"-?(0|[1-9][0-9]*)", raw) is None:
                raise ProtocolError("malformed integer tag")
            number = int(raw)
            if number < INT64_MIN or number > INT64_MAX:
                raise ProtocolError("integer outside the signed 64-bit range")
            return number
        if tag == "bytes":
            try:
                if not isinstance(value.get("v"), str):
                    raise ValueError("bytes tag must contain a string")
                return base64.b64decode(value["v"], validate=True)
            except (ValueError, TypeError) as e:
                raise ProtocolError("malformed bytes tag: %s" % e) from e
        if tag == "map":
            if not isinstance(value.get("v"), dict):
                raise ProtocolError("malformed map tag")
            return {k: decode(v, map_name, key, depth + 1) for k, v in value["v"].items()}
        if tag == "unsupported":
            return UnsupportedValue(value.get("map", map_name), value.get("key", key), value.get("type", "unknown"))
        if tag == "absent":
            return ABSENT
        raise ProtocolError("unknown value tag %r" % tag)
    return value


def check_json_document(value, where):
    """Check a transformer JSON root before it returns to the engine."""
    if not isinstance(value, (dict, list)):
        raise ContractError(
            "%s must stay a JSON object or array; got %s" % (where, type(value).__name__), where
        )
    _check_json(value, where, 0, set())
    try:
        return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
    except ValueError as e:
        raise ContractError("%s: %s" % (where, e), where)


def _check_json(value, where, depth, active):
    if depth > MAX_DEPTH:
        raise ContractError("%s is nested more than %d levels deep" % (where, MAX_DEPTH), where)
    if value is None or isinstance(value, (str, bool)):
        return
    if isinstance(value, int):
        if abs(value) > JS_SAFE_INT:
            raise ContractError(
                "%s is an integer outside the JavaScript safe range (+/-%d); convert it to a string"
                % (where, JS_SAFE_INT),
                where,
            )
        return
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ContractError("%s is not a finite number" % where, where)
        return
    if isinstance(value, (list, tuple, dict)):
        if id(value) in active:
            raise ContractError("%s contains a reference cycle" % where, where)
        active.add(id(value))
        try:
            if isinstance(value, dict):
                for k, v in value.items():
                    if not isinstance(k, str):
                        raise ContractError("%s has a non-string key %r" % (where, k), where)
                    _check_json(v, "%s[%r]" % (where, k), depth + 1, active)
            else:
                for i, v in enumerate(value):
                    _check_json(v, "%s[%d]" % (where, i), depth + 1, active)
        finally:
            active.discard(id(value))
        return
    raise ContractError(
        "%s has type %s, which JSON cannot represent; convert it explicitly" % (where, type(value).__name__),
        where,
    )
