# SPDX-License-Identifier: MPL-2.0
"""Native filter contract regression cases; no engine mocks."""
import os
import sys
import unittest
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from oie_python._context import FilterContext, ScriptLogger
from oie_python._codec import ContractError
from oie_python import validate


def context(kind='json', text='{"accepted":true}'):
    return FilterContext({'role': 'filter', 'phase': 'source-filter',
        'msg': {'kind': kind, 'text': text}, 'maps': {
            'channelMap': {'writable': True, 'values': {}},
            'sourceMap': {'writable': False, 'values': {'origin': 'fixture'}}}}, ScriptLogger(4096))


class NativeFilterTest(unittest.TestCase):
    def test_exact_boolean_and_missing_result(self):
        for value in (None, 0, 1, '', 'true', [], {}, object()):
            ctx = context()
            values = ctx.script_globals()
            values['result'] = value
            with self.assertRaisesRegex(ContractError, 'exactly True or False'):
                ctx.result_from_globals(values)
        for value in (False, True):
            ctx = context()
            values = ctx.script_globals()
            values['result'] = value
            self.assertIs(ctx.result_from_globals(values)['accepted'], value)

    def test_false_preserves_valid_document_and_map_changes(self):
        ctx = context()
        values = ctx.script_globals()
        exec("msg['reason'] = 'rejected'\nchannelMap['seen'] = True\nresult = not msg['accepted']", values)
        result = ctx.result_from_globals(values)
        self.assertIs(result['accepted'], False)
        self.assertIn('rejected', result['msg']['text'])
        self.assertIs(result['maps']['channelMap']['set']['seen'], True)

    def test_invalid_document_or_readonly_map_rejects_entire_result(self):
        for source, error in (("msg = 7\nresult = True", ContractError),
                              ("sourceMap['origin'] = 'changed'\nresult = False", TypeError)):
            ctx = context()
            values = ctx.script_globals()
            with self.assertRaises(error):
                exec(source, values)
                ctx.result_from_globals(values)

    def test_raw_and_xml_replacements(self):
        for kind, before, after in [('raw', 'before', 'after'), ('xml', '<before/>', '<after/>')]:
            ctx = context(kind, before)
            values = ctx.script_globals()
            values.update(msg=after, result=True)
            self.assertEqual({'kind': kind, 'text': after}, ctx.result_from_globals(values)['msg'])

    def test_filter_is_a_compile_only_validation_role(self):
        self.assertIn('filter', validate.ROLES)


if __name__ == '__main__':
    unittest.main()
