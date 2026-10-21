# SPDX-License-Identifier: MPL-2.0
"""Script compiler regressions. No engine or database resources."""
import os
import sys
import traceback
import types
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from oie_python import validate
from oie_python._script import compile_script, execute_script
from oie_python._context import CONTEXTS, ScriptLogger
from oie_python._codec import ContractError


class ScriptReturnTest(unittest.TestCase):
    def run_source(self, source, role='call', **wire):
        wire = dict(role=role, **wire)
        if role == 'call':
            wire.setdefault('arguments', [])
        ctx = CONTEXTS[role](wire, ScriptLogger(4096))
        values = dict(ctx.script_globals(), __name__='__oie_return_test__')
        returned = execute_script(compile_script(source, role, '<authored>'), values)
        return ctx.result_from_globals(values, returned), values

    def test_compile_parity_and_transformer_rejection(self):
        for role in ('filter', 'read', 'write', 'call'):
            self.assertEqual(validate.check('return None', role), [])
            for source in ('return 1\nyield 2', 'return 1\nawait work()',
                           'return 1\nfrom __future__ import annotations',
                           'return 1\nclass C:\n    return 2', 'return 1\nnonlocal x'):
                self.assertTrue(validate.check(source, role), (role, source))
        diagnostics = validate.check('x = 1\nreturn x', 'transform')
        self.assertEqual(diagnostics[0]['line'], 2)
        self.assertIn('outside function', diagnostics[0]['message'])

    def test_every_result_bearing_body_uses_return_semantics(self):
        for source in ('def helper():\n    return 1\nresult = 7',
                       'class C:\n    def helper(self):\n        return 1\nresult = 7'):
            self.assertIsNotNone(compile_script(source, 'call')[1])
            self.assertIsNone(self.run_source(source)[0]['value'])
        self.assertEqual(self.run_source('result = 7\nreturn None')[0]['value'], None)
        self.assertEqual(self.run_source('result = 7\nif False:\n    return 2')[0]['value'], None)

    def test_early_return_and_finally_use_python_semantics(self):
        source = "events = []\ntry:\n    try:\n        return events\n    except BaseException:\n        events.append('caught')\nfinally:\n    events.append('finally')\nraise AssertionError('unreachable')"
        self.assertEqual(self.run_source(source)[0]['value'], ['finally'])
        self.assertEqual(self.run_source('try:\n    return 1\nfinally:\n    return 2')[0]['value'], 2)
        with self.assertRaisesRegex(ValueError, 'finally'):
            self.run_source("try:\n    return 1\nfinally:\n    raise ValueError('finally')")

    def test_globals_helpers_imports_comprehensions_and_fresh_calls(self):
        source = "import math\nx = 3\ndef helper():\n    return x + math.floor(1.5)\nitems = [helper() for _ in range(2)]\nreturn items"
        for _ in range(2):
            result, values = self.run_source(source)
            self.assertEqual(result['value'], [4, 4])
            self.assertNotIn('_', values)
        self.assertEqual(self.run_source('values = [(n := i) for i in range(3)]\nreturn n')[0]['value'], 2)
        self.assertEqual(self.run_source('exec = 1\nglobals = 2\nfrom math import *\nreturn floor(1.9)')[0]['value'], 1)
        self.assertEqual(self.run_source('def __oie_script_body__():\n    return 9\nreturn __oie_script_body__()')[0]['value'], 9)

    def test_future_annotations_dataclass_and_module_helpers(self):
        source = ('from __future__ import annotations\nfrom dataclasses import dataclass, asdict\n'
                  'Alias = int\nvalue: Alias = 4\n@dataclass\nclass Row:\n    value: Alias\n'
                  'def helper():\n    return asdict(Row(value))\nreturn helper()')
        module = types.ModuleType('__oie_return_test__')
        sys.modules[module.__name__] = module
        try:
            module.__dict__.update(CONTEXTS['call']({'role': 'call', 'arguments': []}, ScriptLogger(4096)).script_globals())
            returned = execute_script(compile_script(source, 'call'), module.__dict__)
            self.assertEqual(returned, {'value': 4})
            self.assertNotIn('result', module.__dict__)
            self.assertEqual(module.__annotations__, {'value': 'Alias'})
        finally:
            sys.modules.pop(module.__name__)

    def test_error_lines_are_authored_lines(self):
        source = 'x = 1\ntry:\n    return 1 / 0\nfinally:\n    x = 2'
        try:
            self.run_source(source)
        except ZeroDivisionError as error:
            lines = [frame.lineno for frame in traceback.extract_tb(error.__traceback__) if frame.filename == '<authored>']
            self.assertEqual(lines, [3])
        else:
            self.fail('expected runtime error')

    def test_reader_strings_mixed_envelopes_and_atomic_validation(self):
        wire = {'sourceMap': {'pollId': 'sample', 'origin': 'default'}, 'destinationIds': [2],
                'maps': {'globalMap': {'writable': True, 'values': {}}}}
        result, _ = self.run_source("sourceMap['origin'] = 'reader'\nreturn ['one', '', Message('two', source_map={'origin':'override'}, destination_ids=[2])]", 'read', **wire)
        self.assertEqual([entry['payload'] for entry in result['messages']], ['one', 'two'])
        self.assertEqual([entry['sourceMap']['origin'] for entry in result['messages']], ['reader', 'override'])
        self.assertEqual([entry['destinationIds'] for entry in result['messages']], [None, [2]])
        for source in ("return ''", "return None", "pass"):
            self.assertEqual(self.run_source(source, 'read', **wire)[0]['messages'], [])
        self.assertEqual(self.run_source("return 'one'", 'read', **wire)[0]['messages'][0]['payload'], 'one')
        for bad in ('None', 'b"bytes"', 'Message(None)', 'Message("bad", destination_ids=[3])'):
            with self.subTest(value=bad), self.assertRaises(ContractError):
                self.run_source("globalMap['changed'] = True\nreturn ['one', " + bad + "]", 'read', **wire)
        self.assertEqual(wire['maps']['globalMap']['values'], {})

    def test_filter_replacement_and_invalid_results(self):
        wire = {'msg': {'kind': 'raw', 'text': 'before'}, 'maps': {
            'channelMap': {'writable': True, 'values': {}}}}
        result, _ = self.run_source("msg += ' after'\nchannelMap['seen'] = True\nreturn False", 'filter', **wire)
        self.assertFalse(result['accepted'])
        self.assertEqual(result['msg']['text'], 'before after')
        self.assertIs(result['maps']['channelMap']['set']['seen'], True)
        for source in ('return 1', 'result = True\nreturn None', 'msg = 7\nreturn True',
                       "channelMap['bad'] = object()\nreturn True"):
            with self.subTest(source=source), self.assertRaises(ContractError):
                self.run_source(source, 'filter', **wire)

    def test_role_contracts(self):
        cases = [('read', 'from oie_python import Message\nreturn Message("body")', 'messages'),
                 ('write', 'from oie_python import Response\nreturn Response(data="ack")', 'response'),
                 ('write', 'from oie_python import Status\nreturn Status.QUEUED', 'response'),
                 ('call', 'return {"value": arguments[0]}', 'value')]
        for role, source, field in cases:
            with self.subTest(role=role, source=source):
                wire = {'arguments': [7]} if role == 'call' else {}
                returned = self.run_source(source, role, **wire)[0]
                self.assertIn(field, returned)
        self.assertEqual(self.run_source('return', 'read')[0]['messages'], [])
        self.assertEqual(self.run_source('return', 'write')[0]['response']['status'], 'SENT')
        for role, source in [('read', 'return 7'), ('write', 'return {}'), ('call', 'return object()')]:
            with self.subTest(role=role), self.assertRaises(ContractError):
                self.run_source(source, role)

    def test_automatic_wrappers_are_context_specific_and_fresh(self):
        from oie_python import Message, Response, Status, UnsupportedValue
        for role in ('read', 'write', 'call', 'filter', 'transform'):
            for response_transformer in ((False, True) if role == 'transform' else (False,)):
                wire = {'role': role}
                if role == 'call':
                    wire['arguments'] = []
                if role in ('filter', 'transform'):
                    wire['msg'] = {'kind': 'raw', 'text': 'body'}
                if response_transformer:
                    wire['response'] = {'status': 'SENT'}
                ctx = CONTEXTS[role](wire, ScriptLogger(4096))
                values = ctx.script_globals()
                self.assertIs(values['UnsupportedValue'], UnsupportedValue)
                for name, expected in [('Message', Message if role == 'read' else None),
                                       ('Response', Response if role == 'write' else None),
                                       ('Status', Status if role == 'write' or response_transformer else None)]:
                    self.assertIs(values.get(name), expected)
                values['UnsupportedValue'] = None
                self.assertIs(ctx.script_globals()['UnsupportedValue'], UnsupportedValue)
        self.assertEqual(self.run_source('def emit():\n    return Message("body")\nreturn emit()', 'read')[0]['messages'][0]['payload'], 'body')
        self.assertEqual(self.run_source('return Response(Status.ERROR, "ack")', 'write')[0]['response']['status'], 'ERROR')
        self.assertEqual(self.run_source('Message = lambda value: value\nreturn Message(None)', 'read')[0]['messages'], [])
        result, _ = self.run_source('response.status = Status.ERROR', 'transform',
                                    msg={'kind': 'raw', 'text': 'body'}, response={'status': 'SENT'})
        self.assertEqual(result['response']['status'], 'ERROR')

    def test_result_name_is_never_injected_or_used_as_output(self):
        for role in ('read', 'write', 'call', 'filter'):
            wire = {'msg': {'kind': 'raw', 'text': 'body'}} if role == 'filter' else {}
            valid = {'read': 'Message("body")', 'write': '"body"', 'call': '7', 'filter': 'False'}[role]
            source = 'from oie_python import Message\nassert "result" not in globals()\nresult = ' + valid
            if role == 'filter':
                self.assertIs(self.run_source(source, role, **wire)[0]['accepted'], True)
                self.assertIs(self.run_source('', role, **wire)[0]['accepted'], True)
            else:
                self.assertEqual(self.run_source(source, role, **wire)[0], self.run_source('', role, **wire)[0])
            actual, values = self.run_source(source + '\nreturn result', role, **wire)
            expected, _ = self.run_source('from oie_python import Message\nreturn ' + valid, role, **wire)
            self.assertEqual(actual, expected)

    def test_filter_fallthrough_accepts_but_explicit_none_is_invalid(self):
        wire = {'msg': {'kind': 'raw', 'text': 'body'}, 'maps': {
            'channelMap': {'writable': True, 'values': {}}}}
        for source in ('', '# comment', 'def helper():\n    return False',
                       'if False:\n    return False', 'result = False',
                       "try:\n    msg += '|changed'\nfinally:\n    channelMap['final'] = True"):
            with self.subTest(source=source):
                result, _ = self.run_source(source, 'filter', **wire)
                self.assertIs(result['accepted'], True)
                if source.startswith('try:'):
                    self.assertEqual(result['msg']['text'], 'body|changed')
                    self.assertIs(result['maps']['channelMap']['set']['final'], True)
        for source in ('return', 'return None', 'return 1', 'return "true"'):
            with self.subTest(source=source), self.assertRaises(ContractError):
                self.run_source(source, 'filter', **wire)
        with self.assertRaisesRegex(ValueError, 'before end'):
            self.run_source("raise ValueError('before end')", 'filter', **wire)


if __name__ == '__main__':
    unittest.main()
