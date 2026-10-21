# SPDX-License-Identifier: MPL-2.0
"""Writer return contract, including compatibility with existing string results."""
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from oie_python import ContractError, Response, Status
from oie_python._context import ScriptLogger, WriterContext


class WriterResponseTest(unittest.TestCase):
    def setUp(self):
        self.context = WriterContext({"role": "write", "payload": "outgoing", "maps": {}}, ScriptLogger(1024))

    def response(self, value):
        return self.context.result_for(value)["response"]

    def test_status_only_is_empty_response_and_plain_strings_remain_data(self):
        for status in Status:
            with self.subTest(status=status):
                self.assertEqual(str(status), status.value)
                self.assertEqual(status, status.value)
                result = self.response(status)
                self.assertEqual(result, {"status": status.value, "data": "", "statusMessage": None, "error": None})
                self.assertIs(type(result["status"]), str)
                self.assertEqual(self.response(status.value)["status"], "SENT")
                self.assertEqual(self.response(status.value)["data"], status.value)
                self.assertEqual(json.loads(json.dumps(result)), result)
        self.assertEqual(self.response(None)["data"], "")
        self.assertEqual(self.response("")["data"], "")

    def test_response_preserves_all_fields_and_accepts_string_or_enum_status(self):
        from oie_python import _types
        self.assertIs(_types.Response, Response)
        self.assertIs(_types.Status, Status)
        for status in Status:
            for value in (status, status.value):
                result = self.response(Response(value, "ack\n\u2603", "brief", "details"))
                self.assertEqual(result, {"status": status.value, "data": "ack\n\u2603", "statusMessage": "brief", "error": "details"})
                self.assertIs(type(result["status"]), str)
        self.assertEqual(self.response(Response()), {"status": "SENT", "data": None, "statusMessage": None, "error": None})

    def test_other_engine_statuses_and_nontext_fields_are_rejected(self):
        for status in ("FILTERED", "RECEIVED", "TRANSFORMED", "PENDING", "sent", None, 1):
            with self.subTest(status=status), self.assertRaises(ContractError):
                self.response(Response(status))
        for field in ("data", "status_message", "error"):
            for invalid in (False, 5, {}, b"bytes"):
                with self.subTest(field=field, invalid=invalid), self.assertRaises(ContractError):
                    self.response(Response(**{field: invalid}))
        for value in ({"status": "ERROR"}, 1, False, b"response"):
            with self.subTest(value=value), self.assertRaises(ContractError):
                self.response(value)


if __name__ == "__main__":
    unittest.main()
