# SPDX-License-Identifier: MPL-2.0
"""The Reader's existing Message wrapper is its RawMessage boundary."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from oie_python import ContractError, Message
from oie_python._codec import decode
from oie_python._context import ReaderContext, ScriptLogger
from oie_python._types import RESERVED_SOURCE_MAP_KEYS


class ReaderMessageTest(unittest.TestCase):
    def context(self, limit=10):
        return ReaderContext({"role": "read", "destinationIds": [1, 2], "maxMessages": limit}, ScriptLogger(100))

    def test_message_preserves_payload_types_metadata_and_destination_selection(self):
        context = self.context()
        metadata = {"origin": "reader", "nested": {"id": 2**40, "flags": [True, None]}, "bytes": b"\x00\xff"}
        messages = [Message("", metadata), Message(b"", destination_ids=[]),
                    Message("text", destination_ids=[2]), Message(b"\x00\xff", destination_ids=[1, 2])]
        result = context.result_for(messages)["messages"]
        self.assertEqual([decode(item["payload"]) for item in result], ["", b"", "text", b"\x00\xff"])
        self.assertEqual([item["destinationIds"] for item in result], [None, [], [2], [1, 2]])
        self.assertEqual(decode(result[0]["sourceMap"]), metadata)
        self.assertEqual(len(context.result_for(Message("single"))["messages"]), 1)
        self.assertEqual(context.result_for(None)["messages"], [])
        self.assertEqual(context.result_for([])["messages"], [])

    def test_validation_uses_current_fields_and_snapshots_the_returned_value(self):
        context = self.context()
        message = Message("old", {"nested": ["old"]}, [1])
        message.payload = bytearray(b"new")
        message.source_map["nested"].append("new")
        message.destination_ids[:] = [2]
        wire = context.result_for(message)["messages"][0]
        message.payload[:] = b"bad"
        message.source_map["nested"].append("later")
        message.destination_ids.append(1)
        self.assertEqual(decode(wire["payload"]), b"new")
        self.assertEqual(decode(wire["sourceMap"]), {"nested": ["old", "new"]})
        self.assertEqual(wire["destinationIds"], [2])
        message.destination_ids = [False]
        with self.assertRaises(ContractError):
            context.result_for(message)

    def test_invalid_later_message_prevents_a_result(self):
        context = self.context()
        invalid = [Message(None), Message(3), Message("bad", source_map=[]),
                   Message("bad", source_map={"java": object()}), Message("bad", source_map={1: "value"}),
                   Message("bad", destination_ids=[3]), Message("bad", destination_ids=[True]),
                   Message("bad", destination_ids=[1.0]), "unwrapped"]
        invalid += [Message("bad", source_map={key: "owned"}) for key in RESERVED_SOURCE_MAP_KEYS]
        for value in invalid:
            with self.subTest(value=value), self.assertRaises(ContractError):
                context.result_for([Message("first"), value])
        with self.assertRaises(ContractError):
            self.context(limit=1).result_for([Message("first"), Message("second")])
        for value in ("", b"", (Message("tuple"),), iter([Message("iterator")])):
            with self.subTest(type=type(value)), self.assertRaises(ContractError):
                context.result_for(value)


if __name__ == "__main__":
    unittest.main()
