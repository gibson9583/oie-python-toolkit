# SPDX-License-Identifier: MPL-2.0
"""Run with any supported approved CPython; no engine/database is started."""
import ast
import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[4]
RESOURCES = ROOT / 'server/src/main/resources/python-editor'


class EditorServiceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='oie-editor-unit-')
        cls.vendor = pathlib.Path(cls.temp.name) / 'vendor'
        cls.vendor.mkdir()
        for item in json.loads((RESOURCES / 'manifest.json').read_text()):
            wheel = RESOURCES / item['file']
            assert hashlib.sha256(wheel.read_bytes()).hexdigest() == item['sha256']
            with zipfile.ZipFile(wheel) as archive:
                archive.extractall(cls.vendor)
        cls.packages = pathlib.Path(cls.temp.name) / 'site-packages'
        cls.packages.mkdir()
        cls.marker = pathlib.Path(cls.temp.name) / 'startup-executed'
        (cls.packages / 'evil.pth').write_text('import pathlib; pathlib.Path(' + repr(str(cls.marker)) + ').touch()\n')
        # Importing this fixture would fail: completion must parse it statically.
        (cls.packages / 'safe_static_fixture.py').write_text(
            'raise RuntimeError("Editor imported user package")\n'
            'def transform_patient(patient: str, enabled: bool = True) -> str:\n'
            '    """Normalize a patient string."""\n    return patient\n')

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def call(self, source, operation, role='writer', **kw):
        bootstrap = 'import sys,runpy; sys.path.insert(0,sys.argv.pop(1)); runpy.run_path(sys.argv.pop(1),run_name="__main__")'
        result = subprocess.run([sys.executable, '-I', '-S', '-B', '-c', bootstrap, str(self.vendor),
                                 str(RESOURCES / 'service.py'), str(ROOT / 'python-sdk'),
                                 json.dumps([str(self.packages)])],
                                input=json.dumps(dict(pythonScript=source, operation=operation, role=role, **kw)),
                                text=True, capture_output=True, timeout=15, check=True)
        self.assertFalse(self.marker.exists(), 'Environment .pth startup must never execute')
        return json.loads(result.stdout)

    def test_return_format_analysis_and_signatures(self):
        for role in ('reader', 'writer', 'code-template', 'source-filter', 'destination-filter'):
            with self.subTest(role=role):
                source = 'return  {"ok":True}\n'
                formatted = self.call(source, 'format', role)
                self.assertTrue(formatted['ok'], formatted)
                self.assertEqual(ast.dump(ast.parse(source)), ast.dump(ast.parse(formatted['text'])))
        for role in ('source-transformer', 'destination-transformer', 'response-transformer'):
            self.assertFalse(self.call('return True\n', 'format', role)['ok'])
        source = 'from oie_python import Response\nreturn Response('
        value = self.call(source, 'signature', 'writer', line=2, column=len(source.splitlines()[1]))
        self.assertTrue(value['ok'], value)
        self.assertIn('status', value['signatures'][0]['label'])
        value = self.call('msg += "suffix"\nreturn logger.', 'complete', 'source-filter', line=2, column=14)
        self.assertTrue({'info', 'error'} <= {item['name'] for item in value['items']})

    def test_completion_on_empty_and_trailing_blank_lines(self):
        for role in ('reader', 'writer', 'code-template', 'source-filter', 'destination-filter'):
            for source, line in [('', 1), ('\n', 2), ('return None\n', 2),
                                 ('return None\r\n', 2), ('return None\r', 2),
                                 ('return None\n\n', 3)]:
                with self.subTest(role=role, source=source):
                    value = self.call(source, 'complete', role, line=line, column=0)
                    self.assertTrue(value['ok'], value)
                    self.assertIn('logger', {item['name'] for item in value['items']})

    def test_all_roles_have_correct_globals_and_sdk_members(self):
        for role, expected, absent in [('reader', 'sourceMap', 'payload'), ('writer', 'payload', 'poll'),
                ('source-filter', 'msg', 'payload'), ('destination-filter', 'msg', 'arguments'),
                ('source-transformer', 'has_tmp', 'arguments'), ('destination-transformer', 'has_tmp', 'poll'),
                ('response-transformer', 'has_tmp', 'payload'), ('code-template', 'arguments', 'has_tmp')]:
            with self.subTest(role=role):
                items = self.call('', 'complete', role, line=1, column=0)['items']
                names = {item['name'] for item in items}
                self.assertIn(expected, names)
                self.assertNotIn(absent, names)
                self.assertNotIn('poll', names)
                self.assertNotIn('result', names)
                members = self.call('globalMap.', 'complete', role, line=1, column=10)['items']
                self.assertTrue({'get', 'put'} <= {item['name'] for item in members})
                logger = self.call('logger.', 'complete', role, line=1, column=7)['items']
                self.assertTrue({'info', 'error'} <= {item['name'] for item in logger})
                readonly = self.call('configurationMap.', 'complete', role, line=1, column=17)['items']
                self.assertNotIn('put', {item['name'] for item in readonly})
                source_map = self.call('sourceMap.', 'complete', role, line=1, column=10)['items']
                source_names = {item['name'] for item in source_map}
                self.assertIn('get', source_names)
                if role == 'reader':
                    self.assertTrue({'put', 'putAll', 'remove', 'update'} <= source_names)
                else:
                    self.assertTrue({'put', 'putAll', 'remove', 'update'}.isdisjoint(source_names))
        response = self.call('response.', 'complete', 'response-transformer', line=1, column=9)['items']
        self.assertTrue({'status', 'status_message', 'error'} <= {item['name'] for item in response})

    def test_reader_source_map_write_signature_and_removed_poll_global(self):
        source = 'sourceMap.put('
        value = self.call(source, 'signature', 'reader', line=1, column=len(source))
        self.assertTrue(value['ok'], value)
        self.assertIn('key', value['signatures'][0]['label'])
        self.assertIn('value', value['signatures'][0]['label'])
        for source in ('pol', 'return pol', 'return None\npol'):
            with self.subTest(source=source):
                value = self.call(source, 'complete', 'reader', line=len(source.splitlines()),
                                  column=len(source.splitlines()[-1]))
                self.assertTrue(value['ok'], value)
                self.assertNotIn('poll', {item['name'] for item in value['items']})

    def test_stdlib_and_imported_packages_completion_hover_signature(self):
        value = self.call('import json\njson.', 'complete', line=2, column=5)
        self.assertTrue({'loads', 'dumps'} <= {item['name'] for item in value['items']})
        source = 'import safe_static_fixture\nsafe_static_fixture.'
        self.assertIn('transform_patient', {item['name'] for item in self.call(source, 'complete', line=2, column=20)['items']})
        source += 'transform_patient('
        result = self.call(source, 'signature', line=2, column=len(source.splitlines()[1]))
        self.assertIn('patient', result['signatures'][0]['label'])
        result = self.call(source, 'hover', line=2, column=23)
        self.assertIn('Normalize a patient string', '\n'.join(result['contents']))

    def test_connector_result_types_are_available_without_import_execution(self):
        for role, name, parameters in [('reader', 'Message', ('payload', 'source_map', 'destination_ids')),
                                      ('writer', 'Response', ('status', 'data', 'status_message', 'error'))]:
            source = 'from oie_python import ' + name + '\nreturn ' + name + '('
            result = self.call(source, 'signature', role, line=2, column=len(source.splitlines()[1]))
            self.assertTrue(result['signatures'], result)
            label = result['signatures'][0]['label']
            for parameter in parameters:
                self.assertIn(parameter, label)
        source = 'from oie_python import Status\nreturn Status.'
        result = self.call(source, 'complete', 'writer', line=2, column=len(source.splitlines()[1]))
        self.assertTrue({'SENT', 'ERROR', 'QUEUED'} <= {item['name'] for item in result['items']})

    def test_document_and_selection_formatting_preserve_ast_strings_comments(self):
        source = '# keep me\ntext="a=b,  c"\nreturn {"value":1,"list":[1,2]}\n'
        result = self.call(source, 'format')
        self.assertTrue(result['ok'], result)
        self.assertNotEqual(source, result['text'])
        self.assertEqual(ast.dump(ast.parse(source)), ast.dump(ast.parse(result['text'])))
        source = 'left=1\nreturn {"value":1}\nright=2\n'
        result = self.call(source, 'format', selection={'startLineNumber': 2, 'endLineNumber': 2})
        self.assertTrue(result['ok'], result)
        self.assertIn('return ', result['text'])
        self.assertNotIn('left', result['text'])
        self.assertNotIn('right', result['text'])

    def test_incomplete_and_invalid_source_is_never_reformatted(self):
        for source in ['return {', 'if True:\nreturn 1', 'text = "unterminated']:
            self.assertFalse(self.call(source, 'format')['ok'])


if __name__ == '__main__':
    unittest.main()
