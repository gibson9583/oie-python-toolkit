# SPDX-License-Identifier: MPL-2.0
"""One bounded editor request. Never execute authored source or package startup."""
import ast
import io
import json
import pathlib
import sys
import tokenize


def context_source(role):
    lines = [
        'from typing import Any as _Any, Mapping as _Mapping, Optional as _Optional',
        'from oie_python._context import ScriptLogger as _Logger',
        'from oie_python import UnsupportedValue',
        'from oie_python._maps import GlobalMap as _GlobalMap, ScriptMap as _ScriptMap',
        'logger: _Logger', 'phase: str', 'message: _Mapping[str, _Any]',
        'globalMap: _GlobalMap', 'globalChannelMap: _GlobalMap',
        'configurationMap: _Mapping[str, _Any]',
    ]
    if role != 'reader':
        lines += ['channelMap: _ScriptMap', 'connectorMap: _ScriptMap',
                  'sourceMap: _Mapping[str, _Any]', 'responseMap: _ScriptMap']
    if role.endswith('transformer') or role in ('source-filter', 'destination-filter'):
        lines += ['msg: _Any', 'tmp: _Any', 'has_tmp: bool']
        if role == 'response-transformer':
            lines += ['from oie_python._context import _ResponseFields as _ResponseFields',
                      'response: _ResponseFields', 'from oie_python import Status']
    elif role == 'writer':
        lines += ['payload: _Optional[str]', 'from oie_python import Response, Status']
    elif role == 'reader':
        lines += ['sourceMap: _ScriptMap', 'from oie_python import Message']
    elif role == 'code-template':
        lines += ['arguments: list']
    return '\n'.join(lines) + '\n'


def tree(source):
    return ast.dump(ast.parse(source, type_comments=True), include_attributes=False)


def comments(source):
    return [t.string for t in tokenize.generate_tokens(io.StringIO(source).readline)
            if t.type == tokenize.COMMENT]


def format_source(request):
    from oie_python._script import compile_script
    import autopep8
    source = request['pythonScript']
    role = script_role(request['role'])
    compile_script(source, role)
    before = tree(source)  # Invalid/incomplete programs are never rewritten.
    options = {'aggressive': 0, 'experimental': False, 'ignore_local_config': True,
               'ignore': ['E501'], 'max_line_length': 100, 'indent_size': 4}
    selection = request.get('selection')
    if selection:
        options['line_range'] = [selection['startLineNumber'], selection['endLineNumber']]
    candidate = autopep8.fix_code(source, options=options)
    compile_script(candidate, role)
    if tree(candidate) != before or comments(candidate) != comments(source):
        raise ValueError('Formatting would change Python semantics or comments')
    if selection:
        # autopep8 operates on full lines. Return those full lines only and
        # reject any formatter edit outside the selected lines.
        a, b = selection['startLineNumber'] - 1, selection['endLineNumber']
        old, new = source.splitlines(keepends=True), candidate.splitlines(keepends=True)
        if not source or source.endswith(('\n', '\r')):
            old.append('')
        if not candidate or candidate.endswith(('\n', '\r')):
            new.append('')
        tail = len(old) - b
        if old[:a] != new[:a] or (tail and old[b:] != new[-tail:]):
            raise ValueError('Formatting would change lines outside the selection')
        return {'text': ''.join(new[a:len(new) - tail if tail else len(new)])}
    return {'text': candidate}


def analyze(request, sdk_path, package_paths):
    import jedi
    from jedi.api.environment import InterpreterEnvironment
    # Static Script, never Interpreter. Only the -I -S base interpreter's
    # stdlib paths are eligible for compiled imports; environment paths below
    # are added only to the static project resolver, never to sys.path.
    jedi.settings.auto_import_modules = []
    jedi.settings.allow_unsafe_interpreter_executions = False
    jedi.settings.use_filesystem_cache = False
    project = jedi.Project(str(pathlib.Path(__file__).parent),
                           added_sys_path=[sdk_path] + package_paths,
                           smart_sys_path=False, load_unsafe_extensions=False)
    prefix = context_source(request['role'])
    source = request['pythonScript']
    indentation = 0
    if script_role(request['role']) != 'transform':
        context_names = [node.target.id for node in ast.parse(prefix).body
                         if isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name)]
        prefix += 'def __oie_editor_body__():\n    global ' + ', '.join(context_names) + '\n'
        # Python normalizes CRLF/bare CR to LF. Keep the last empty line so
        # cursor columns on a newline-terminated draft include its indentation.
        source = source.replace('\r\n', '\n').replace('\r', '\n')
        source = '    ' + source.replace('\n', '\n    ')
        indentation = 4
    script = jedi.Script(prefix + source, project=project,
                         environment=InterpreterEnvironment())
    line = request['line'] + prefix.count('\n')
    column = request['column'] + indentation
    operation = request['operation']
    if operation == 'complete':
        items = script.complete(line, column)
        items.sort(key=lambda item: item.name not in {'logger', 'phase', 'message', 'globalMap', 'globalChannelMap',
                   'configurationMap', 'channelMap', 'connectorMap', 'sourceMap', 'responseMap', 'msg', 'tmp',
                   'has_tmp', 'response', 'payload', 'arguments', 'Message', 'Response', 'Status', 'UnsupportedValue'})
        return {'items': [{'name': item.name, 'type': item.type,
                           'description': item.description[:512],
                           'prefixLength': item.get_completion_prefix_length()}
                          for item in items[:200]]}
    if operation == 'hover':
        names = script.infer(line, column)
        return {'contents': [item.docstring()[:12000] or item.description
                             for item in names[:4]]}
    if operation == 'signature':
        return {'signatures': [{'label': item.to_string(), 'activeParameter': item.index or 0,
                                'documentation': item.docstring()[:12000],
                                'parameters': [p.to_string() for p in item.params]}
                               for item in script.get_signatures(line, column)[:8]]}
    raise ValueError('Unknown editor operation')


def script_role(role):
    if role in ('source-filter', 'destination-filter'):
        return 'filter'
    return {'reader': 'read', 'writer': 'write', 'code-template': 'call'}.get(role, 'transform')


def main():
    # Only the pinned SDK is importable; dependency paths remain static-only.
    sys.path.insert(0, sys.argv[1])
    request = json.load(sys.stdin)
    try:
        result = (format_source(request) if request['operation'] == 'format' else
                  analyze(request, sys.argv[1], json.loads(sys.argv[2])))
        print(json.dumps({'ok': True, **result}, ensure_ascii=True))
    except (SyntaxError, ValueError, tokenize.TokenError, IndentationError) as error:
        print(json.dumps({'ok': False, 'message': str(error)[:1000]}))


if __name__ == '__main__':
    main()
