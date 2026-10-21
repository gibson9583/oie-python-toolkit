# SPDX-License-Identifier: MPL-2.0
import io
import json
import os
import struct
import subprocess
import sys
import textwrap
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SDK = os.path.dirname(HERE)
sys.path.insert(0, SDK)

from oie_python import ContractError, Message, Response, UnsupportedValue, UnsupportedValueError  # noqa: E402
from oie_python import _codec, validate  # noqa: E402
from oie_python._context import ReaderContext, ScriptLogger, TransformContext, WriterContext  # noqa: E402


def transform_wire(msg_kind="json", msg_text='{"a":1}', tmp=None, maps=None, response=None):
    wire = {"role": "transform", "phase": "source-transformer", "msg": {"kind": msg_kind, "text": msg_text}}
    if tmp is not None:
        wire["tmp"] = tmp
    if response is not None:
        wire["response"] = response
    wire["maps"] = maps or {
        "channelMap": {"writable": True, "values": {"n": {"$t": "int", "v": "5"}, "obj": {"$t": "unsupported", "map": "channelMap", "key": "obj", "type": "java.util.Date"}}},
        "connectorMap": {"writable": True, "values": {}},
        "sourceMap": {"writable": False, "values": {"origin": "x"}},
        "responseMap": {"writable": False, "values": {}},
    }
    return wire


class FunctionContextTest(unittest.TestCase):
    def test_function_arguments_and_return_values(self):
        from oie_python._context import FunctionContext
        context = FunctionContext({"role": "call", "arguments": [{"x": 1}, None]}, ScriptLogger(1024))
        self.assertEqual(context.arguments, [{"x": 1}, None])
        for value in (None, 3, "hello", True, [1, None], {"v": 5}):
            self.assertEqual(context.result_for(value), {"value": value})
        for value in (float("nan"), 2**60, object(), {1: "x"}):
            with self.assertRaises(ContractError):
                context.result_for(value)
        self.assertEqual(validate.check("return arguments\n", "call"), [])
        self.assertEqual(validate.check("def helper(value):\n    return value\n", "call"), [])


class CodecTest(unittest.TestCase):
    def test_round_trip_tags(self):
        value = {"i": 2 ** 40, "b": b"\x00\xff", "f": 1.5, "s": "x", "n": None, "l": [True, {"$t": "odd"}]}
        encoded = _codec.encode(value)
        self.assertEqual(encoded["i"], {"$t": "int", "v": str(2 ** 40)})
        self.assertEqual(encoded["l"][1], {"$t": "map", "v": {"$t": "odd"}})
        self.assertEqual(_codec.decode(json.loads(json.dumps(encoded))), value)

    def test_rejects_unsafe_values(self):
        for bad in (2 ** 63, float("nan"), float("inf"), object(), {1: "x"}):
            with self.assertRaises(ContractError):
                _codec.encode(bad)
        cyc = []
        cyc.append(cyc)
        with self.assertRaises(ContractError):
            _codec.encode(cyc)
        deep = []
        cur = deep
        for _ in range(70):
            nxt = []
            cur.append(nxt)
            cur = nxt
        with self.assertRaises(ContractError):
            _codec.encode(deep)

    def test_frames(self):
        buf = io.BytesIO()
        _codec.write_frame(buf, {"type": "X", "v": "é"})
        buf.seek(0)
        self.assertEqual(_codec.read_frame(buf), {"type": "X", "v": "é"})
        self.assertIsNone(_codec.read_frame(buf))
        big = io.BytesIO(struct.pack(">I", 100) + b"{}")
        with self.assertRaises(_codec.ProtocolError):
            _codec.read_frame(big, max_bytes=10)
        trunc = io.BytesIO(struct.pack(">I", 10) + b"{}")
        with self.assertRaises(_codec.ProtocolError):
            _codec.read_frame(trunc)
        nan = io.BytesIO(struct.pack(">I", 11) + b'{"a": NaN}')
        with self.assertRaises(_codec.ProtocolError):
            _codec.read_frame(nan)

    def test_duplicate_fields_and_malformed_tags(self):
        body = b'{"id":"a","id":"b"}'
        with self.assertRaises(_codec.ProtocolError):
            _codec.read_frame(io.BytesIO(struct.pack(">I", len(body)) + body))
        for malformed in ({"$t": "int", "v": "9223372036854775808"}, {"$t": "int", "v": 5},
                          {"$t": "int", "v": "01"}, {"$t": "bytes"}, {"$t": "bytes", "v": "%%%"},
                          {"$t": "map", "v": []}):
            with self.assertRaises(_codec.ProtocolError):
                _codec.decode(malformed)

    def test_json_document_rules(self):
        self.assertEqual(_codec.check_json_document({"a": 1}, "msg"), '{"a":1}')
        for bad in (5, "s", None, {"a": 2 ** 53}, {"a": float("nan")}, {"a": b"x"}):
            with self.assertRaises(ContractError):
                _codec.check_json_document(bad, "msg")


class TransformContextTest(unittest.TestCase):
    def test_rhino_map_methods_and_native_return_values(self):
        maps = {name: {"writable": True, "values": {"existing": "old"}}
                for name in ("channelMap", "connectorMap", "responseMap", "globalMap", "globalChannelMap")}
        context = TransformContext(transform_wire(maps=maps), ScriptLogger(1000))
        for name, value in context.script_globals().items():
            if name not in maps:
                continue
            self.assertIsNone(value.get("missing"))
            self.assertFalse(value.containsKey("missing"))
            self.assertEqual(value.size(), 1)
            self.assertFalse(value.isEmpty())
            self.assertEqual(set(value.keySet()), {"existing"})
            self.assertEqual(value.put("existing", "new"), None if name.startswith("global") else "old")
            self.assertTrue(value.containsValue("new"))
            self.assertIsNone(value.putAll({"added": "yes"}))
            entry = next(entry for entry in value.entrySet() if entry.getKey() == "added")
            self.assertEqual(entry.getValue(), "yes")
            self.assertEqual(entry.setValue("changed"), "yes")
            self.assertEqual(value["added"], "changed")
            self.assertEqual(value.remove("existing"), None if name.startswith("global") else "new")
            self.assertIsNone(value.remove("missing"))
            value.clear()
            self.assertTrue(value.isEmpty())
        for change in context.result()["maps"].values():
            self.assertEqual(change, {"set": {}, "delete": ["existing"]})

    def test_global_get_variables_is_an_unmodifiable_view_of_this_call(self):
        context = TransformContext(transform_wire(maps={"globalMap": {"writable": True, "values": {"x": "old"}}}), ScriptLogger(100))
        value = context.script_globals()["globalMap"]
        view = value.getVariables()
        value.put("x", "new")
        value.put("added", "visible")
        self.assertEqual(view.get("x"), "new")
        self.assertTrue(view.containsKey("added"))
        self.assertEqual(view.size(), 2)
        self.assertEqual(set(view.keySet()), {"x", "added"})
        self.assertEqual(_codec.decode(_codec.encode(view)), {"x": "new", "added": "visible"})
        for action in (lambda: view.put("x", "bad"), lambda: view.putAll({}),
                       lambda: view.remove("missing"), view.clear,
                       lambda: next(iter(view.entrySet())).setValue("bad")):
            with self.assertRaises(TypeError):
                action()
        self.assertEqual(value.get("x"), "new")

    def test_readonly_maps_reject_java_and_python_mutation_methods(self):
        context = TransformContext(transform_wire(maps={"configurationMap": {"writable": False, "values": {"x": "old"}},
                                                        "sourceMap": {"writable": False, "values": {"x": "old"}}}), ScriptLogger(100))
        for value in (context.script_globals()["configurationMap"], context.sourceMap):
            for action in (lambda: value.put("x", "bad"), lambda: value.remove("missing"), value.clear,
                           lambda: value.putAll({}), lambda: value.update({}), lambda: value.setdefault("new", 1),
                           lambda: value.pop("missing", None), value.popitem,
                           lambda: next(iter(value.entrySet())).setValue("bad"), lambda: value.__ior__({"x": "bad"})):
                with self.assertRaises(TypeError):
                    action()
            self.assertEqual(value.get("x"), "old")
        self.assertEqual(context.result(), {"maps": {}})

    def test_stock_channel_source_fallback_does_not_change_contains_key_or_keys(self):
        context = TransformContext(transform_wire(maps={"channelMap": {"writable": True, "values": {"present": None}},
                                                        "sourceMap": {"writable": False, "values": {"sourceOnly": "source", "present": "other"}}}), ScriptLogger(1000))
        value = context.channelMap
        self.assertEqual(value.get("sourceOnly"), "source")
        self.assertFalse(value.containsKey("sourceOnly"))
        self.assertEqual(set(value.keySet()), {"present"})
        self.assertIsNone(value.get("present"))
        self.assertIsNone(value.put("sourceOnly", "channel"))
        self.assertEqual(value.get("sourceOnly"), "channel")
        self.assertEqual(context.logger.records[0]["level"], "error")

    def test_response_names_resolve_for_reads_and_presence_but_not_writes_or_removal(self):
        context = TransformContext(transform_wire(maps={"responseMap": {"writable": True,
            "values": {"d1": "actual", "Destination": None}, "aliases": {"Destination": "d1", "missing": "d2"}}}), ScriptLogger(100))
        value = context.responseMap
        self.assertEqual(value.get("Destination"), "actual")
        self.assertTrue(value.containsKey("Destination"))
        self.assertFalse(value.containsKey("missing"))
        self.assertEqual({entry.getKey(): entry.getValue() for entry in value.entrySet()}, {"d1": "actual", "Destination": None})
        self.assertIsNone(value.put("Destination", "override"))
        self.assertEqual(value.get("Destination"), "override")
        self.assertEqual(value.remove("Destination"), "override")
        self.assertIsNone(value.remove("Destination"))
        self.assertEqual(value.get("Destination"), "actual")
        self.assertEqual(value.size(), 1)
        self.assertEqual(set(value.keySet()), {"d1"})
        self.assertEqual(context.result()["maps"], {"responseMap": {"set": {}, "delete": ["Destination"]}})

    def test_global_put_rejects_null_and_put_all_rejects_nonmaps(self):
        context = TransformContext(transform_wire(maps={"globalMap": {"writable": True, "values": {}}}), ScriptLogger(100))
        value = context.script_globals()["globalMap"]
        for action in (lambda: value.put("null", None), lambda: value.put(1, "bad")):
            with self.assertRaises(ContractError):
                action()
        with self.assertRaises(TypeError):
            value.putAll([("x", 1)])
        self.assertEqual(context.result(), {"maps": {}})

    def test_only_canonical_map_variables_are_injected(self):
        names = ("channelMap", "connectorMap", "sourceMap", "responseMap", "globalMap", "globalChannelMap", "configurationMap")
        maps = {name: {"writable": name not in ("sourceMap", "configurationMap"), "values": {"existing": "engine"}}
                for name in names}
        context = TransformContext(transform_wire(maps=maps), ScriptLogger(100))
        values = context.script_globals()
        self.assertEqual(set(values), set(names) | {"logger", "phase", "message", "msg", "has_tmp", "UnsupportedValue"})
        for name in names:
            self.assertEqual(values[name]["existing"], "engine")
            if maps[name]["writable"]:
                values[name]["added"] = "python"
                del values[name]["existing"]
            else:
                with self.assertRaises(TypeError):
                    values[name]["added"] = "python"
        changes = context.result_from_globals(values)["maps"]
        self.assertEqual(set(changes), {name for name in maps if maps[name]["writable"]})
        for change in changes.values():
            self.assertEqual(change, {"set": {"added": "python"}, "delete": ["existing"]})

    def test_reader_and_function_return_shared_map_changes_only_after_valid_results(self):
        from oie_python._context import FunctionContext
        maps = {"globalMap": {"writable": True, "values": {"nested": {"items": []}}},
                "globalChannelMap": {"writable": True, "values": {}},
                "configurationMap": {"writable": False, "values": {"site": "local"}}}
        for context in (ReaderContext({"role": "read", "maps": maps}, ScriptLogger(100)),
                        FunctionContext({"role": "call", "arguments": [], "maps": maps}, ScriptLogger(100))):
            values = context.script_globals()
            values["globalMap"]["nested"]["items"].append("python")
            values["globalChannelMap"]["new"] = values["configurationMap"]["site"]
            changes = context.result_from_globals(values)["maps"]
            self.assertEqual(changes["globalMap"]["set"]["nested"], {"items": ["python"]})
            self.assertEqual(changes["globalChannelMap"]["set"], {"new": "local"})
            with self.assertRaises(ContractError):
                context.result_from_globals(values, object())

    def test_log_limits_count_utf8_bytes_and_bound_empty_records(self):
        logger = ScriptLogger(3)
        logger.info("éé")
        self.assertEqual(logger.records, [{"level": "info", "message": "é"}])
        self.assertTrue(logger.truncated)
        logger = ScriptLogger(100000)
        for _ in range(2000):
            logger.info("")
        self.assertEqual(len(logger.records), 1024)
        self.assertTrue(logger.truncated)
    def test_unchanged_produces_empty_result(self):
        ctx = TransformContext(transform_wire(), ScriptLogger(100))
        self.assertEqual(ctx.result(), {"maps": {}})

    def test_json_edit_and_map_delta(self):
        ctx = TransformContext(transform_wire(), ScriptLogger(100))
        ctx.msg["b"] = [1, 2]
        ctx.channelMap["new"] = {"x": 1}
        ctx.channelMap["n"] = 5  # unchanged value
        result = ctx.result()
        self.assertEqual(json.loads(result["msg"]["text"]), {"a": 1, "b": [1, 2]})
        self.assertEqual(result["maps"], {"channelMap": {"set": {"new": {"x": {"$t": "int", "v": "1"}}}, "delete": []}})

    def test_bool_versus_int_is_a_change(self):
        ctx = TransformContext(transform_wire(), ScriptLogger(100))
        ctx.channelMap["n"] = True
        self.assertIn("n", ctx.result()["maps"]["channelMap"]["set"])

    def test_nested_and_delete(self):
        wire = transform_wire(maps={"channelMap": {"writable": True, "values": {"d": {"k": [1]}, "gone": "x"}}})
        ctx = TransformContext(wire, ScriptLogger(100))
        ctx.channelMap["d"]["k"].append(2)
        del ctx.channelMap["gone"]
        delta = ctx.result()["maps"]["channelMap"]
        self.assertEqual(delta["delete"], ["gone"])
        self.assertEqual(delta["set"]["d"], {"k": [{"$t": "int", "v": "1"}, {"$t": "int", "v": "2"}]})

    def test_read_only_maps(self):
        ctx = TransformContext(transform_wire(), ScriptLogger(100))
        with self.assertRaises(TypeError):
            ctx.sourceMap["x"] = 1
        self.assertEqual(ctx.sourceMap["origin"], "x")

    def test_complete_maps_preserve_reserved_keys_and_nested_values(self):
        wire = transform_wire()
        nested = {"$t": "bytes", "v": [b"\x00\xff", {"$t": "int", "v": 7}]}
        wire["maps"]["configurationMap"] = {"writable": False, "values": {"$t": "int", "v": "42"}}
        for name in ("globalMap", "globalChannelMap"):
            wire["maps"][name] = {"writable": True, "values": {"$t": "ordinary-user-data", "nested": _codec.encode(nested)}}
        context = TransformContext(wire, ScriptLogger(100))
        values = context.script_globals()
        self.assertEqual(dict(values["configurationMap"]), {"$t": "int", "v": "42"})
        for name in ("globalMap", "globalChannelMap"):
            self.assertEqual(values[name]["$t"], "ordinary-user-data")
            self.assertEqual(values[name]["nested"], nested)
        self.assertEqual(context.result(), {"maps": {}})

    def test_unsupported_sentinel(self):
        ctx = TransformContext(transform_wire(), ScriptLogger(100))
        value = ctx.channelMap["obj"]
        self.assertIsInstance(value, UnsupportedValue)
        with self.assertRaises(UnsupportedValueError):
            str(value)
        self.assertEqual(ctx.result(), {"maps": {}})
        ctx.channelMap["copy"] = value
        moved = ctx.result()["maps"]["channelMap"]["set"]["copy"]
        self.assertEqual(moved, {"$t": "unsupported", "map": "channelMap", "key": "obj", "type": "java.util.Date"})

    def test_root_rules(self):
        ctx = TransformContext(transform_wire(), ScriptLogger(100))
        ctx.msg = "now a string"
        with self.assertRaises(ContractError):
            ctx.result()
        ctx = TransformContext(transform_wire("xml", "<a/>"), ScriptLogger(100))
        ctx.msg = {"a": 1}
        with self.assertRaises(ContractError):
            ctx.result()
        ctx = TransformContext(transform_wire("raw", "abc"), ScriptLogger(100))
        ctx.msg = b"abc"
        with self.assertRaises(ContractError):
            ctx.result()

    def test_xml_element_and_template_globals(self):
        import xml.etree.ElementTree as ET
        ctx = TransformContext(transform_wire("xml", "<a/>", tmp={"kind": "xml", "text": "<t/>"}), ScriptLogger(100))
        self.assertTrue(ctx.has_tmp)
        values = ctx.script_globals()
        values["tmp"] = ET.fromstring("<t><x>1</x></t>")
        result = ctx.result_from_globals(values)
        self.assertNotIn("msg", result)
        self.assertEqual(result["tmp"], {"kind": "xml", "text": "<t><x>1</x></t>"})
        ctx = TransformContext(transform_wire(), ScriptLogger(100))
        self.assertFalse(ctx.has_tmp)
        with self.assertRaises(AttributeError):
            ctx.tmp
        values = ctx.script_globals()
        self.assertNotIn("tmp", values)
        values["msg"] = {"z": 1}
        self.assertIn("msg", ctx.result_from_globals(values))

    def test_response_fields(self):
        ctx = TransformContext(transform_wire(response={"status": "SENT"}), ScriptLogger(100))
        with self.assertRaises(ContractError):
            ctx.response.status = "BOGUS"
        ctx.response.status = "ERROR"
        ctx.response.error = "bad"
        self.assertEqual(ctx.result()["response"], {"status": "ERROR", "statusMessage": None, "error": "bad"})
        plain = TransformContext(transform_wire(), ScriptLogger(100))
        with self.assertRaises(AttributeError):
            plain.response


class WriterReaderTest(unittest.TestCase):
    def test_writer_results(self):
        ctx = WriterContext({"role": "write", "payload": "", "maps": {}}, ScriptLogger(10))
        self.assertEqual(ctx.payload, "")
        self.assertEqual(ctx.result_for(None)["response"]["status"], "SENT")
        self.assertEqual(ctx.result_for("ok")["response"]["data"], "ok")
        self.assertEqual(ctx.result_for(Response("QUEUED", data="d"))["response"]["status"], "QUEUED")
        for bad in (b"x", {"a": 1}, 3, Response("NOPE"), Response("SENT", data=1)):
            with self.assertRaises(ContractError):
                ctx.result_for(bad)
        absent = WriterContext({"role": "write", "maps": {}}, ScriptLogger(10))
        self.assertIsNone(absent.payload)

    def test_reader_results(self):
        ctx = ReaderContext({"role": "read", "maxMessages": 2, "destinationIds": [1, 2]}, ScriptLogger(10))
        self.assertEqual(ctx.result_for(None), {"messages": []})
        self.assertEqual(ctx.result_for([]), {"messages": []})
        one = ctx.result_for(Message(b"", destination_ids=[]))
        self.assertEqual(one["messages"][0]["payload"], {"$t": "bytes", "v": ""})
        self.assertEqual(one["messages"][0]["destinationIds"], [])
        self.assertIsNone(ctx.result_for(Message("x"))["messages"][0]["destinationIds"])
        bad = [Message("x"), Message("y"), Message("z")]
        cases = [bad, b"text", Message(5), Message("x", destination_ids=[3]), Message("x", destination_ids=[True]),
                 Message("x", source_map={"batchId": 1}), (m for m in [Message("x")]), ["good", None], ["good", 3]]
        for case in cases:
            with self.assertRaises(ContractError):
                ctx.result_for(case)

    def test_reader_string_and_mixed_results(self):
        ctx = ReaderContext({"role": "read", "maxMessages": 3, "destinationIds": [1, 2],
                             "sourceMap": {"pollId": "sample", "origin": "reader"}}, ScriptLogger(10))
        self.assertEqual(ctx.result_for("")["messages"], [])
        text = ctx.result_for("hello")["messages"]
        self.assertEqual(text, ctx.result_for(Message("hello"))["messages"])
        self.assertIsNone(text[0]["destinationIds"])
        self.assertEqual(_codec.decode(text[0]["sourceMap"]), {"pollId": "sample", "origin": "reader"})
        mixed = ctx.result_for(["first", "", Message("", source_map={"origin": "override"},
                                                   destination_ids=[2])])["messages"]
        self.assertEqual([item["payload"] for item in mixed], ["first", ""])
        self.assertEqual(mixed[1]["destinationIds"], [2])
        self.assertEqual(_codec.decode(mixed[1]["sourceMap"])["origin"], "override")
        # Count the supplied list before skipping blanks, keeping the poll bound.
        with self.assertRaises(ContractError):
            ctx.result_for(["", "", "", ""])
        for bad in (["first", None], ["first", b"bytes"], ["first", Message(None)]):
            with self.subTest(value=bad), self.assertRaises(ContractError):
                ctx.result_for(bad)

    def test_reader_source_map_defaults_overrides_and_independent_outputs(self):
        wire = {"role": "read", "sourceMap": {"pollId": "first", "nested": {"items": []}}}
        ctx = ReaderContext(wire, ScriptLogger(10))
        self.assertNotIn("poll", ctx.script_globals())
        self.assertIsNone(ctx.sourceMap.put("origin", "reader"))
        self.assertEqual(ctx.sourceMap.get("pollId"), "first")
        message = Message("x", source_map={"origin": "message", "pollId": "override"})
        result = ctx.result_for([message, Message("y")])["messages"]
        first = _codec.decode(result[0]["sourceMap"])
        second = _codec.decode(result[1]["sourceMap"])
        self.assertEqual(first, {"pollId": "override", "origin": "message", "nested": {"items": []}})
        self.assertEqual(second["origin"], "reader")
        first["nested"]["items"].append("changed")
        self.assertEqual(second["nested"]["items"], [])
        self.assertEqual(ctx.sourceMap["nested"]["items"], [])
        self.assertEqual(message.source_map, {"origin": "message", "pollId": "override"})
        next_ctx = ReaderContext(wire, ScriptLogger(10))
        self.assertNotIn("origin", next_ctx.sourceMap)
        self.assertNotIn("sourceMap", ctx.result_for(message).get("maps", {}))

    def test_reader_source_map_validation_includes_empty_results(self):
        invalid = [{"batchId": "reserved"}, {1: "not a string"}, {"nested": {1: "bad"}},
                   {"nested": [object()]}, {"nested": [float("nan")]},
                   {"nested": [UnsupportedValue("globalMap", "opaque", "java.lang.Object")]}]
        cycle = {}; cycle["cycle"] = cycle
        invalid.append(cycle)
        for values in invalid:
            for result in (None, [], "", "x", Message("x")):
                with self.subTest(values=repr(values), result=result):
                    ctx = ReaderContext({"role": "read"}, ScriptLogger(10))
                    # Bypass the convenience setter to exercise result-time validation.
                    dict.update(ctx.sourceMap, values)
                    with self.assertRaises(ContractError):
                        ctx.result_for(result)
        ctx = ReaderContext({"role": "read"}, ScriptLogger(10))
        with self.assertRaises(ContractError):
            ctx.result_for([Message("good"), Message("bad", source_map={"nested": object()})])

    def test_reader_source_map_preserves_literal_tag_keys(self):
        ctx = ReaderContext({"role": "read", "sourceMap": {"$t": "user-key", "nested": {
            "$t": "map", "v": {"$t": "nested-user-key"}}}}, ScriptLogger(10))
        self.assertEqual(_codec.decode(ctx.result_for(Message("x"))["messages"][0]["sourceMap"]),
                         {"$t": "user-key", "nested": {"$t": "nested-user-key"}})


class ValidateTest(unittest.TestCase):
    def codes(self, source, role="transform"):
        return [d["code"] for d in validate.check(source, role)]

    def test_known_roles_and_compile_errors(self):
        self.assertEqual(self.codes("pass", "unknown"), ["UNKNOWN_ROLE"])
        for role in ("filter", "transform", "read", "write", "call"):
            self.assertEqual(self.codes("return 1", role), ["PYTHON_SYNTAX"] if role == "transform" else [])
            for source in ("def helper(:", "yield 1", "await work()"):
                with self.subTest(role=role, source=source):
                    self.assertEqual(self.codes(source, role), ["PYTHON_SYNTAX"])

    def test_validation_never_executes(self):
        marker = os.path.join(os.path.dirname(__file__), "executed.marker")
        source = "open(%r, 'w').write('x')\nimport definitely_missing_module\n" % marker
        for role in ("transform", "read", "write", "call"):
            self.assertEqual(self.codes(source, role), [])
        self.assertFalse(os.path.exists(marker))

    def test_every_role_accepts_helpers_and_ordinary_statements(self):
        sources = ["", "msg['changed'] = True", "transform = read = write = call = 42",
                   "def transform():\n    pass\n", "def read(a, b):\n    yield a\n",
                   "async def write():\n    pass\n", "class call:\n    pass\n",
                   "import missing_module\nraise RuntimeError('only at execution')"]
        for role in ("transform", "read", "write", "call"):
            for source in sources:
                with self.subTest(role=role, source=source):
                    self.assertEqual(self.codes(source, role), [])


@unittest.skipUnless(hasattr(os, "fork"), "qualified POSIX process containment")
class WorkerProcessTest(unittest.TestCase):
    """Drive a real worker process over the wire protocol."""

    def setUp(self):
        env = {"PATH": os.environ.get("PATH", ""), "OIE_PYTHON_LOG_LIMIT": "64"}
        bootstrap = "import sys; sys.path.insert(0, %r); from oie_python.worker import main; main()" % SDK
        self.proc = subprocess.Popen([sys.executable, "-I", "-S", "-c", bootstrap], stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)

    def tearDown(self):
        self.proc.kill()
        self.proc.wait()
        for stream in (self.proc.stdin, self.proc.stdout, self.proc.stderr):
            stream.close()

    def call(self, frame):
        _codec.write_frame(self.proc.stdin, frame)
        return _codec.read_frame(self.proc.stdout)

    def execute_source(self, source, wire=None):
        source = textwrap.dedent(source)
        wire = transform_wire() if wire is None else wire
        self.assertEqual(validate.check(source, wire["role"]), [])
        loaded = self.call({
            "type": "LOAD", "id": "load", "spec": "flat", "role": wire["role"],
            "label": "<flat-script>", "source": source,
        })
        self.assertEqual(loaded["type"], "RESULT", loaded)
        return self.call({
            "type": "EXECUTE", "id": "execute", "spec": "flat",
            "context": wire,
        })

    def test_native_script_returns_execute_over_worker_protocol(self):
        cases = [
            ({"role": "read"}, "from oie_python import Message\nreturn Message('body')", "messages"),
            ({"role": "write", "payload": "body"}, "return payload", "response"),
            ({"role": "call", "arguments": [7]}, "return {'argument': arguments[0]}", "value"),
        ]
        for wire, source, field in cases:
            with self.subTest(role=wire['role']):
                reply = self.execute_source(source, wire)
                self.assertEqual(reply['type'], 'RESULT', reply)
                self.assertIn(field, reply['result'])
        wire = transform_wire(msg_kind='raw', msg_text='before')
        wire['role'] = 'filter'
        reply = self.execute_source("msg += ' after'\nreturn False\nraise AssertionError('unreachable')", wire)
        self.assertEqual(reply['type'], 'RESULT', reply)
        self.assertIs(reply['result']['accepted'], False)
        self.assertEqual(reply['result']['msg']['text'], 'before after')

    def test_return_none_and_finally_override_local_result_on_worker(self):
        for role in ('read', 'write', 'call'):
            wire = {'role': role}
            if role == 'call':
                wire['arguments'] = []
            reply = self.execute_source('result = object()\ntry:\n    return 7\nfinally:\n    return None', wire)
            self.assertEqual(reply['type'], 'RESULT', reply)
        reply = self.call({'type': 'LOAD', 'id': 'return-transform', 'spec': 'return-transform',
                           'role': 'transform', 'label': '<authored>', 'source': 'x = 1\nreturn 1\n'})
        self.assertEqual(reply['type'], 'ERROR', reply)
        self.assertEqual(reply['error']['line'], 2)

    def test_result_variables_never_supply_worker_outputs(self):
        cases = [({"role": "read"}, "Message('body')", {"messages": []}),
                 ({"role": "write"}, "'body'", {"response": {"status": "SENT", "data": "", "statusMessage": None, "error": None}, "maps": {}}),
                 ({"role": "call", "arguments": []}, "{'body': True}", {"value": None})]
        for wire, value, empty in cases:
            source = "from oie_python import Message\nassert 'result' not in globals()\nresult = " + value
            with self.subTest(role=wire["role"]):
                reply = self.execute_source(source, wire)
                self.assertEqual(reply["type"], "RESULT", reply)
                self.assertEqual(reply["result"], empty)
                reply = self.execute_source(source + "\nreturn result", wire)
                self.assertEqual(reply["type"], "RESULT", reply)
                self.assertNotEqual(reply["result"], empty)
        wire = transform_wire()
        wire["role"] = "filter"
        for source in ("return", "return None"):
            reply = self.execute_source(source, wire)
            self.assertEqual(reply["type"], "ERROR", reply)
            self.assertEqual(reply["error"]["kind"], "contract")
            self.assertIn("exactly True or False", reply["error"]["message"])
        for source in ("", "# comment", "assert 'result' not in globals()\nresult = False",
                       "if False:\n    return False"):
            reply = self.execute_source(source, wire)
            self.assertEqual(reply["type"], "RESULT", reply)
            self.assertIs(reply["result"]["accepted"], True)
        reply = self.execute_source("assert 'result' not in globals()\nresult = False\nreturn result", wire)
        self.assertEqual(reply["type"], "RESULT", reply)
        self.assertIs(reply["result"]["accepted"], False)

    def test_filter_fallthrough_finally_mutations_and_exception_atomicity(self):
        wire = transform_wire(msg_kind='raw', msg_text='before')
        wire['role'] = 'filter'
        source = "try:\n    msg += '|changed'\nfinally:\n    channelMap['final'] = True"
        reply = self.execute_source(source, wire)
        self.assertEqual(reply['type'], 'RESULT', reply)
        self.assertIs(reply['result']['accepted'], True)
        self.assertEqual(reply['result']['msg']['text'], 'before|changed')
        self.assertIs(reply['result']['maps']['channelMap']['set']['final'], True)
        reply = self.execute_source(source + "\nraise ValueError('before end')", wire)
        self.assertEqual(reply['type'], 'ERROR', reply)
        self.assertNotIn('result', reply)

    def test_all_roles_execute_top_level_without_calling_named_helpers(self):
        source = """
            assert 'ctx' not in globals() and 'output' not in globals()
            assert 'previous_call' not in globals()
            previous_call = True
            assert message['id'] == '12' and configurationMap['site'] == 'clinic'
            assert phase == 'test-phase'
            def transform(a, b):
                raise AssertionError('not an entry point')
            def read():
                raise AssertionError('not an entry point')
            def write(ctx):
                raise AssertionError('not an entry point')
            def call(*args):
                raise AssertionError('not an entry point')
            logger.warning('logged')
            print('printed')
            """
        wires = [transform_wire(), {"role": "read"}, {"role": "write", "payload": "original"},
                 {"role": "call", "arguments": []}]
        expected = [{"maps": {}}, {"messages": []},
                    {"response": {"status": "SENT", "data": "", "statusMessage": None, "error": None}, "maps": {}},
                    {"value": None}]
        for wire, result in zip(wires, expected):
            wire.update(phase="test-phase", message={"id": "12"})
            wire.setdefault("maps", {})["configurationMap"] = {"writable": False, "values": {"site": "clinic"}}
            result.setdefault("maps", {})
            with self.subTest(role=wire["role"]):
                for _ in range(2):
                    reply = self.execute_source(source, wire)
                    self.assertEqual(reply["result"], result, reply)
                    self.assertEqual([record["level"] for record in reply["logs"]], ["warning", "stdout"])

    def test_reader_writer_and_function_collect_returned_values(self):
        reader = self.execute_source("""
            from oie_python import Message
            assert 'result' not in globals() and sourceMap['count'] == 2
            assert 'poll' not in globals()
            assert 'msg' not in globals() and 'channelMap' not in globals()
            return [Message(b'bytes', source_map={'origin': 'test'}, destination_ids=[1])]
            """, {"role": "read", "sourceMap": {"count": {"$t": "int", "v": "2"}},
                   "maxMessages": 2, "destinationIds": [1]})
        self.assertEqual(reader["type"], "RESULT", reader)
        self.assertEqual(reader["result"]["messages"], [{
            "payload": {"$t": "bytes", "v": "Ynl0ZXM="},
            "sourceMap": {"count": {"$t": "int", "v": "2"}, "origin": "test"}, "destinationIds": [1],
        }])
        writer = self.execute_source("""
            from oie_python import Response
            assert 'result' not in globals() and payload == 'original'
            channelMap['sent'] = True
            return Response('QUEUED', data=payload + ' sent', status_message='waiting')
            """, {"role": "write", "payload": "original",
                   "maps": {"channelMap": {"writable": True, "values": {}}}})
        self.assertEqual(writer["type"], "RESULT", writer)
        self.assertEqual(writer["result"]["response"], {
            "status": "QUEUED", "data": "original sent", "statusMessage": "waiting", "error": None,
        })
        self.assertEqual(writer["result"]["maps"], {"channelMap": {"set": {"sent": True}, "delete": []}})
        function = self.execute_source("""
            assert 'result' not in globals() and 'msg' not in globals() and 'channelMap' not in globals()
            def normalize(value):
                return value.strip().upper()
            return {'name': normalize(arguments[0]), 'rest': arguments[1:]}
            """, {"role": "call", "arguments": [" alice ", 3, None]})
        self.assertEqual(function["result"], {"value": {"name": "ALICE", "rest": [3, None]}}, function)

    def test_return_defaults_reset_and_invalid_returns_keep_contract_checks(self):
        cases = [({"role": "read"}, "return 7"),
                 ({"role": "write"}, "return {}"),
                 ({"role": "call", "arguments": []}, "return 2**60")]
        for wire, source in cases:
            with self.subTest(role=wire["role"]):
                reply = self.execute_source(source, wire)
                self.assertEqual(reply["type"], "ERROR", reply)
                self.assertEqual(reply["error"]["kind"], "contract")
                self.assertIn("result", reply["error"]["field"])
                self.assertNotIn("result", reply)
                reply = self.execute_source("assert 'result' not in globals()\nresult = 123\ndel result", wire)
                self.assertEqual(reply["type"], "RESULT", reply)
        reply = self.execute_source("assert 'result' not in globals()\nresult = object()")
        self.assertEqual(reply["result"], {"maps": {}}, reply)

    def test_return_value_is_fresh_when_reusing_a_loaded_script(self):
        cases = [
            ({"role": "read"}, "Message('produced')", {"messages": []}),
            ({"role": "write"}, "'produced'",
             {"response": {"status": "SENT", "data": "", "statusMessage": None, "error": None}, "maps": {}}),
            ({"role": "call", "arguments": []}, "'produced'", {"value": None}),
        ]
        for wire, value, empty in cases:
            with self.subTest(role=wire["role"]):
                source = "from oie_python import Message\nassert 'result' not in globals()\n"
                source += "if globalMap['produce']:\n    return " + value + "\n"
                wire["maps"] = {"globalMap": {"writable": True, "values": {"produce": True}}}
                empty.setdefault("maps", {})
                reply = self.execute_source(source, wire)
                self.assertEqual(reply["type"], "RESULT", reply)
                self.assertNotEqual(reply["result"], empty)
                wire["maps"]["globalMap"]["values"]["produce"] = False
                reply = self.call({"type": "EXECUTE", "id": "again", "spec": "flat", "context": wire})
                self.assertEqual(reply["result"], empty, reply)

    def test_direct_globals_follow_phase_and_template_availability(self):
        source = """
            assert 'ctx' not in globals() and 'output' not in globals()
            assert sourceMap['origin'] == 'x'
            msg['site'] = configurationMap['site']
            msg['message_id'] = message['message_id']
            channelMap['phase'] = phase
            if has_tmp:
                tmp['from_msg'] = msg['a']
            else:
                assert 'tmp' not in globals()
            if phase == 'response-transformer':
                response.status = 'ERROR'
                response.error = 'changed'
            else:
                assert 'response' not in globals()
            logger.info('direct')
            print('printed')
            """
        for phase in ("source-transformer", "destination-transformer", "response-transformer"):
            for has_tmp in (False, True):
                with self.subTest(phase=phase, has_tmp=has_tmp):
                    wire = transform_wire(tmp={"kind": "json", "text": "{}"} if has_tmp else None)
                    wire["phase"] = phase
                    wire["message"] = {"message_id": {"$t": "int", "v": "12"}}
                    wire["maps"]["configurationMap"] = {"writable": False, "values": {"site": "clinic"}}
                    if phase == "response-transformer":
                        wire["response"] = {"status": "SENT"}
                    reply = self.execute_source(source, wire)
                    self.assertEqual(reply["type"], "RESULT", reply)
                    result = reply["result"]
                    self.assertEqual(json.loads(result["msg"]["text"]),
                                     {"a": 1, "site": "clinic", "message_id": 12})
                    self.assertEqual(result["maps"]["channelMap"]["set"]["phase"], phase)
                    self.assertEqual("tmp" in result, has_tmp)
                    if has_tmp:
                        self.assertEqual(json.loads(result["tmp"]["text"]), {"from_msg": 1})
                    self.assertEqual("response" in result, phase == "response-transformer")
                    if "response" in result:
                        self.assertEqual(result["response"]["status"], "ERROR")
                    self.assertEqual([record["level"] for record in reply["logs"]], ["info", "stdout"])

    def test_transform_named_helpers_are_never_called_automatically(self):
        reply = self.execute_source("""
            def transform(ctx):
                raise AssertionError('must not be called')
            def increment(value):
                return value + 1
            msg['a'] = increment(msg['a'])
            """)
        self.assertEqual(reply["type"], "RESULT", reply)
        self.assertEqual(json.loads(reply["result"]["msg"]["text"]), {"a": 2})
        for source in ("def transform():\n    raise AssertionError('not called')",
                       "async def transform(ctx):\n    pass", "transform = 42", ""):
            with self.subTest(source=source):
                reply = self.execute_source(source)
                self.assertEqual(reply["result"], {"maps": {}}, reply)

    def test_direct_document_noops_and_replacements(self):
        for kind, text in (("json", '{"a":1}'), ("xml", "<root/>"), ("raw", "value")):
            with self.subTest(kind=kind):
                wire = transform_wire(kind, text, tmp={"kind": kind, "text": text})
                reply = self.execute_source("msg = msg\ntmp = tmp", wire)
                self.assertEqual(reply["result"], {"maps": {}}, reply)
        reply = self.execute_source("msg = {'a': 1}")
        self.assertIn("msg", reply["result"])
        reply = self.execute_source("msg['a'] = {'nested': []}\nmsg['a']['nested'].append(3)")
        self.assertEqual(json.loads(reply["result"]["msg"]["text"]), {"a": {"nested": [3]}})
        reply = self.execute_source("""
            import xml.etree.ElementTree as ET
            msg = ET.fromstring(msg)
            msg.set('changed', 'yes')
            """, transform_wire("xml", "<root/>"))
        self.assertEqual(reply["result"]["msg"], {"kind": "xml", "text": '<root changed="yes" />'})

    def test_invalid_document_bindings_cannot_return_partial_changes(self):
        cases = [
            ("del msg", transform_wire(), "msg"),
            ("del tmp", transform_wire(tmp={"kind": "raw", "text": "before"}), "tmp"),
            ("tmp = 'created'", transform_wire(), "tmp"),
            ("msg = 42", transform_wire(), "msg"),
        ]
        for source, wire, field in cases:
            with self.subTest(source=source):
                reply = self.execute_source("channelMap['partial'] = True\n" + source, wire)
                self.assertEqual(reply["type"], "ERROR", reply)
                self.assertEqual(reply["error"]["kind"], "contract")
                self.assertEqual(reply["error"]["field"], field)
                self.assertNotIn("result", reply)

    def test_reader_source_map_mutations_survive_rebinding_and_reset_next_invocation(self):
        wire = {"role": "read", "sourceMap": {"pollId": "sample"}}
        source = """
            from oie_python import Message
            assert sourceMap.get('origin') is None
            original = sourceMap
            sourceMap = {'discarded': True}
            original.put('origin', 'reader')
            return [Message('one'), Message('two', source_map={'origin': 'message'})]
            """
        for _ in range(2):
            reply = self.execute_source(source, wire)
            self.assertEqual(reply["type"], "RESULT", reply)
            self.assertEqual([m["sourceMap"] for m in reply["result"]["messages"]],
                             [{"pollId": "sample", "origin": "reader"}, {"pollId": "sample", "origin": "message"}])

    def test_invalid_reader_defaults_cannot_return_shared_deltas(self):
        wire = {"role": "read", "sourceMap": {"pollId": "sample"},
                "maps": {"globalMap": {"writable": True, "values": {}}}}
        for source in ("sourceMap['batchId'] = 'reserved'", "sourceMap['nested'] = [object()]",
                       "dict.__setitem__(sourceMap, 1, 'invalid')"):
            reply = self.execute_source("globalMap['partial'] = True\n" + source + "\nreturn None", wire)
            self.assertEqual(reply["type"], "ERROR", reply)
            self.assertEqual(reply["error"]["kind"], "contract")
            self.assertNotIn("result", reply)

    def test_direct_readonly_values_and_missing_maps(self):
        wire = transform_wire()
        wire["message"] = {"id": "original"}
        wire["maps"]["configurationMap"] = {"writable": False, "values": {"site": "original"}}
        for source in ("message['id'] = 'changed'", "configurationMap['site'] = 'changed'",
                       "sourceMap['origin'] = 'changed'"):
            with self.subTest(source=source):
                reply = self.execute_source(source, wire)
                self.assertEqual(reply["type"], "ERROR", reply)
                self.assertEqual(reply["error"]["type"], "TypeError")
                self.assertNotIn("result", reply)
        wire["maps"] = {}
        reply = self.execute_source("""
            assert 'channelMap' not in globals()
            assert 'connectorMap' not in globals()
            assert 'sourceMap' not in globals()
            assert 'responseMap' not in globals()
            """, wire)
        self.assertEqual(reply["result"], {"maps": {}}, reply)

    def test_rebinding_helper_names_does_not_replace_engine_objects(self):
        wire = transform_wire(response={"status": "SENT"})
        wire["phase"] = "response-transformer"
        reply = self.execute_source("""
            original_map = channelMap
            channelMap = {'discarded': True}
            original_map['kept'] = True
            original_response = response
            response = {'status': 'BOGUS'}
            original_response.status = 'ERROR'
            """, wire)
        self.assertEqual(reply["type"], "RESULT", reply)
        self.assertEqual(reply["result"]["maps"],
                         {"channelMap": {"set": {"kept": True}, "delete": []}})
        self.assertEqual(reply["result"]["response"]["status"], "ERROR")

    def test_hello_load_execute(self):
        hello = self.call({"type": "HELLO", "id": "1"})
        self.assertEqual(hello["type"], "HELLO")
        self.assertEqual(hello["protocol"], 1)
        source = ("import os\n"
                  "print('noise')\n"
                  "os.write(1, b'native noise\\n')\n"
                  "logger.info('n=%s', channelMap['n'])\n"
                  "msg['seen'] = True\n")
        loaded = self.call({"type": "LOAD", "id": "2", "spec": "s1", "role": "transform", "label": "<python:test>", "source": source})
        self.assertEqual(loaded["type"], "RESULT")
        result = self.call({"type": "EXECUTE", "id": "3", "spec": "s1", "context": transform_wire()})
        self.assertEqual(result["type"], "RESULT", result)
        self.assertEqual(json.loads(result["result"]["msg"]["text"]), {"a": 1, "seen": True})
        self.assertEqual([r["level"] for r in result["logs"]], ["stdout", "info"])
        # The native write went to stderr, not the protocol stream; the next frame still parses.
        failing = self.call({"type": "LOAD", "id": "4", "spec": "s2", "role": "transform", "label": "<python:fail>",
                             "source": "x = 1\nraise ValueError('bad patient')\n"})
        self.assertEqual(failing["type"], "RESULT")
        error = self.call({"type": "EXECUTE", "id": "5", "spec": "s2", "context": transform_wire()})
        self.assertNotIn(SDK, error["error"]["traceback"])
        self.assertEqual(error["type"], "ERROR")
        self.assertEqual(error["error"]["kind"], "script")
        self.assertEqual(error["error"]["line"], 2)
        self.assertIn("bad patient", error["error"]["message"])

    def test_log_limit_and_syntax_error(self):
        self.call({"type": "LOAD", "id": "1", "spec": "s", "role": "transform", "label": "<l>",
                   "source": "logger.info('x' * 500)\n"})
        result = self.call({"type": "EXECUTE", "id": "2", "spec": "s", "context": transform_wire()})
        self.assertTrue(result["logsTruncated"])
        bad = self.call({"type": "LOAD", "id": "3", "spec": "t", "role": "transform", "label": "<l>", "source": "def ("})
        self.assertEqual(bad["error"]["kind"], "syntax")

    def test_fresh_globals_per_call(self):
        source = "assert 'counter' not in globals()\ncounter = [1]\nchannelMap['c'] = len(counter)\n"
        self.call({"type": "LOAD", "id": "1", "spec": "s", "role": "transform", "label": "<l>", "source": source})
        for i in range(2):
            result = self.call({"type": "EXECUTE", "id": str(i), "spec": "s", "context": transform_wire()})
            self.assertEqual(result["result"]["maps"]["channelMap"]["set"]["c"], {"$t": "int", "v": "1"})

    def test_dataclasses_with_string_annotations_work_on_repeated_calls(self):
        source = ("from __future__ import annotations\n"
                  "from dataclasses import dataclass\n"
                  "from typing import ClassVar\n"
                  "@dataclass\n"
                  "class Patient:\n"
                  "    kind: ClassVar[str] = 'patient'\n"
                  "    id: str\n"
                  "msg['patient_id'] = Patient('P123').id\n")
        self.assertEqual(validate.check(source, "transform"), [])
        self.call({"type": "LOAD", "id": "load", "spec": "dataclass", "role": "transform",
                   "label": "<dataclass>", "source": source})
        for i in range(2):
            result = self.call({"type": "EXECUTE", "id": str(i), "spec": "dataclass", "context": transform_wire()})
            self.assertEqual(result["type"], "RESULT", result)
            self.assertEqual(json.loads(result["result"]["msg"]["text"])["patient_id"], "P123")

    def test_script_module_registration_is_restored_after_success_and_failure(self):
        # Observe replies outside the invocation, including a preexisting module
        # or None entry; script globals must still be fresh for every call.
        observer = ("import sys, types\n"
                    "sys.path.insert(0, %r)\n" % SDK
                    + "from oie_python import _codec\n"
                    "original_write = _codec.write_frame\n"
                    "original_streams = sys.stdout, sys.stderr\n"
                    "missing = object()\n"
                    "%s\n"
                    "previous = sys.modules.get('__oie_script__', missing)\n"
                    "def observe(stream, frame, limit):\n"
                    "    frame['moduleRestored'] = sys.modules.get('__oie_script__', missing) is previous\n"
                    "    frame['streamsRestored'] = (sys.stdout, sys.stderr) == original_streams\n"
                    "    original_write(stream, frame, limit)\n"
                    "_codec.write_frame = observe\n"
                    "from oie_python.worker import main\n"
                    "main()\n")
        source = ("import sys\n"
                  "assert sys.modules[__name__].__dict__ is globals()\n"
                  "assert 'previous_call' not in globals()\n"
                  "globals()['previous_call'] = True\n"
                  "if msg.get('fail'):\n"
                  "    sys.modules[__name__] = None\n"
                  "    raise ValueError('intentional failure')\n")
        for initial in ("pass", "sys.modules['__oie_script__'] = None",
                        "sys.modules['__oie_script__'] = types.ModuleType('__oie_script__')"):
            with self.subTest(initial=initial):
                proc = subprocess.Popen([sys.executable, "-I", "-S", "-c", observer % initial],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                try:
                    _codec.write_frame(proc.stdin, {"type": "LOAD", "id": "load", "spec": "scope", "role": "transform",
                                                   "label": "<scope>", "source": source})
                    self.assertEqual(_codec.read_frame(proc.stdout)["type"], "RESULT")
                    for index, fail in enumerate((False, True, False)):
                        wire = transform_wire(msg_text=json.dumps({"fail": fail}))
                        _codec.write_frame(proc.stdin, {"type": "EXECUTE", "id": str(index),
                                                       "spec": "scope", "context": wire})
                        reply = _codec.read_frame(proc.stdout)
                        self.assertEqual(reply["type"], "ERROR" if fail else "RESULT", reply)
                        self.assertTrue(reply["moduleRestored"], reply)
                        self.assertTrue(reply["streamsRestored"], reply)
                        if fail:
                            self.assertIn("intentional failure", reply["error"]["message"])
                    wire["maps"]["globalMap"] = {"writable": True, "values": {"invalid": {"$t": "invalid"}}}
                    _codec.write_frame(proc.stdin, {"type": "EXECUTE", "id": "bad-context",
                                                   "spec": "scope", "context": wire})
                    reply = _codec.read_frame(proc.stdout)
                    self.assertEqual(reply["error"]["kind"], "protocol", reply)
                    self.assertTrue(reply["moduleRestored"], reply)
                    self.assertTrue(reply["streamsRestored"], reply)
                    _codec.write_frame(proc.stdin, {
                        "type": "LOAD", "id": "top-level-load", "spec": "top-level", "role": "transform",
                        "label": "<top-level>", "source": "raise ValueError('top-level failure')",
                    })
                    self.assertEqual(_codec.read_frame(proc.stdout)["type"], "RESULT")
                    _codec.write_frame(proc.stdin, {"type": "EXECUTE", "id": "top-level-execute",
                                                   "spec": "top-level", "context": transform_wire()})
                    reply = _codec.read_frame(proc.stdout)
                    self.assertIn("top-level failure", reply["error"]["message"])
                    self.assertTrue(reply["moduleRestored"], reply)
                    self.assertTrue(reply["streamsRestored"], reply)
                finally:
                    proc.stdin.close()
                    try:
                        proc.wait(timeout=5)
                    finally:
                        if proc.poll() is None:
                            proc.kill()
                            proc.wait(timeout=5)
                        proc.stdout.close()
                        proc.stderr.close()

    def test_large_request_passes_bounded_watchdog_forwarding(self):
        source = "#" + "x" * (1024 * 1024) + "\npass\n"
        reply = self.call({"type": "LOAD", "id": "large", "spec": "s", "role": "transform", "label": "<large>", "source": source})
        self.assertEqual(reply["type"], "RESULT")

    def test_exits_on_input_eof(self):
        self.call({"type": "HELLO", "id": "1"})
        self.proc.stdin.close()
        self.proc.wait(timeout=10)

    @unittest.skipUnless(hasattr(os, "killpg"), "POSIX process groups")
    def test_input_eof_ends_script_children(self):
        source = ("import subprocess, sys\n"
                  "p = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])\n"
                  "channelMap['child'] = p.pid\n")
        self.call({"type": "LOAD", "id": "1", "spec": "s", "role": "transform", "label": "<l>", "source": source})
        result = self.call({"type": "EXECUTE", "id": "2", "spec": "s", "context": transform_wire()})
        child = int(result["result"]["maps"]["channelMap"]["set"]["child"]["v"])
        self.proc.stdin.close()
        self.proc.wait(timeout=10)
        import time
        for _ in range(50):
            try:
                os.kill(child, 0)
            except ProcessLookupError:
                break
            try:
                os.waitpid(child, os.WNOHANG)
            except ChildProcessError:
                pass
            time.sleep(0.1)
        else:
            os.kill(child, 9)
            self.fail("script child %d outlived its worker" % child)

    @unittest.skipUnless(hasattr(os, "fork"), "POSIX independent watchdog")
    def test_site_stdout_cannot_corrupt_hello_and_venv_dependencies_remain_importable(self):
        import tempfile
        with tempfile.TemporaryDirectory(prefix="oie-site-import-owned-") as owned:
            venv = os.path.join(owned, "venv")
            subprocess.run([sys.executable, "-I", "-S", "-m", "venv", "--without-pip", venv], check=True)
            python = os.path.join(venv, "bin", "python")
            packages = subprocess.check_output([python, "-I", "-c", "import sysconfig; print(sysconfig.get_path('purelib'))"], text=True).strip()
            with open(os.path.join(packages, "owned_dependency.py"), "w") as file:
                file.write("value = 'venv-loaded'\n")
            with open(os.path.join(packages, "owned-startup.pth"), "w") as file:
                file.write("import os; os.write(1,b'native site noise\\n')\n")
            bootstrap = "import sys; sys.path.insert(0, %r); from oie_python.worker import main; main()" % SDK
            proc = subprocess.Popen([python, "-I", "-S", "-c", bootstrap], stdin=subprocess.PIPE,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                def call(frame):
                    _codec.write_frame(proc.stdin, frame)
                    return _codec.read_frame(proc.stdout)
                self.assertEqual(call({"type": "HELLO", "id": "site-hello"})["type"], "HELLO")
                source = "import owned_dependency\nmsg['dependency'] = owned_dependency.value\n"
                self.assertEqual(call({"type": "LOAD", "id": "site-load", "spec": "site", "role": "transform", "label": "<site>", "source": source})["type"], "RESULT")
                result = call({"type": "EXECUTE", "id": "site-execute", "spec": "site", "context": transform_wire()})
                self.assertEqual(result["type"], "RESULT", result)
                self.assertEqual(json.loads(result["result"]["msg"]["text"])["dependency"], "venv-loaded")
                self.assertEqual(proc.stderr.readline(), b"native site noise\n")
                proc.stdin.close()
                proc.wait(timeout=5)
            finally:
                if proc.poll() is None:
                    os.killpg(proc.pid, 9)
                    proc.wait(timeout=5)
                for stream in (proc.stdin, proc.stdout, proc.stderr):
                    if stream and not stream.closed:
                        stream.close()

    @unittest.skipUnless(hasattr(os, "fork"), "POSIX independent watchdog")
    def test_input_eof_ends_dependency_site_code_holding_the_gil(self):
        import tempfile
        import time
        with tempfile.TemporaryDirectory(prefix="oie-site-owned-") as owned:
            venv = os.path.join(owned, "venv")
            subprocess.run([sys.executable, "-I", "-S", "-m", "venv", "--without-pip", venv], check=True)
            python = os.path.join(venv, "bin", "python")
            packages = subprocess.check_output([python, "-I", "-c", "import sysconfig; print(sysconfig.get_path('purelib'))"], text=True).strip()
            marker = os.path.join(owned, "site-started")
            with open(os.path.join(packages, "owned-startup.pth"), "w") as file:
                file.write("import ctypes,subprocess,sys; child=subprocess.Popen([sys.executable,'-I','-S','-c','import time;time.sleep(60)']); open(%r,'w').write(str(child.pid)); ctypes.PyDLL(None).sleep(60)\n" % marker)
            bootstrap = "import sys; sys.path.insert(0, %r); from oie_python.worker import main; main()" % SDK
            proc = subprocess.Popen([python, "-I", "-S", "-c", bootstrap], stdin=subprocess.PIPE,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            child = None
            try:
                deadline = time.monotonic() + 5
                while not os.path.exists(marker) and proc.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.01)
                self.assertTrue(os.path.exists(marker), "Dependency site code never started")
                with open(marker) as file:
                    child = int(file.read())
                proc.stdin.close()
                proc.wait(timeout=5)
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline:
                    try:
                        os.kill(child, 0)
                    except ProcessLookupError:
                        child = None
                        break
                    time.sleep(0.02)
                self.assertIsNone(child, "Dependency site child survived parent EOF")
            finally:
                if proc.poll() is None:
                    os.killpg(proc.pid, 9)
                    proc.wait(timeout=5)
                if child is not None:
                    try:
                        os.kill(child, 9)
                    except ProcessLookupError:
                        pass
                for stream in (proc.stdin, proc.stdout, proc.stderr):
                    if stream and not stream.closed:
                        stream.close()

    @unittest.skipUnless(hasattr(os, "fork"), "POSIX independent watchdog")
    def test_input_eof_ends_native_call_holding_the_gil(self):
        import tempfile
        import time
        with tempfile.TemporaryDirectory(prefix="oie-watchdog-owned-") as owned:
            marker = os.path.join(owned, "started")
            source = ("import ctypes, subprocess, sys\n"
                      "p=subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])\n"
                      "open(%r,'w').write(str(p.pid))\n"
                      "ctypes.PyDLL(None).sleep(60)\n") % marker
            self.call({"type": "LOAD", "id": "1", "spec": "s", "role": "transform", "label": "<gil>", "source": source})
            _codec.write_frame(self.proc.stdin, {"type": "EXECUTE", "id": "2", "spec": "s", "context": transform_wire()})
            deadline = time.monotonic() + 5
            while not os.path.exists(marker) and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertTrue(os.path.exists(marker), "native call did not start")
            with open(marker) as file:
                child = int(file.read())
            self.proc.stdin.close()
            self.proc.wait(timeout=5)
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                try:
                    os.kill(child, 0)
                except ProcessLookupError:
                    break
                time.sleep(0.05)
            else:
                os.kill(child, 9)
                self.fail("child survived the native GIL-held parent EOF")


if __name__ == "__main__":
    unittest.main()
