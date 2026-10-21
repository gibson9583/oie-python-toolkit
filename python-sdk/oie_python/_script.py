# SPDX-License-Identifier: MPL-2.0
"""Compile authored scripts, preserving module bindings and native return semantics."""

import ast
import copy
import symtable


class _ScriptScope(ast.NodeTransformer):
    """Visit statements in the script, never a helper's separate scope."""

    def visit_FunctionDef(self, node):
        return node

    visit_AsyncFunctionDef = visit_FunctionDef
    visit_ClassDef = visit_FunctionDef
    visit_Lambda = visit_FunctionDef


class _WithoutReturns(_ScriptScope):
    def visit_Return(self, node):
        return ast.copy_location(ast.Expr(value=node.value or ast.Constant(None)), node)


class _ModuleStatements(_ScriptScope):
    def __init__(self, future_annotations, builtins_name):
        self.future_annotations = future_annotations
        self.builtins_name = builtins_name
        self.has_annotations = False

    def visit_AnnAssign(self, node):
        self.has_annotations = True
        if not isinstance(node.target, ast.Name) or not node.simple:
            # Non-simple module annotations evaluate their expression but do
            # not add an entry to __annotations__. Preserve target evaluation.
            statements = []
            if node.value is not None:
                statements.append(ast.Assign(targets=[node.target], value=node.value))
            elif isinstance(node.target, ast.Attribute):
                statements.append(ast.Expr(value=node.target.value))
            elif isinstance(node.target, ast.Subscript):
                statements.append(ast.Expr(value=node.target.value))
                if not isinstance(node.target.slice, ast.Slice):
                    statements.append(ast.Expr(value=node.target.slice))
                else:
                    statements.extend(ast.Expr(value=value) for value in (
                        node.target.slice.lower, node.target.slice.upper, node.target.slice.step) if value is not None)
            if not self.future_annotations:
                statements.append(ast.Expr(value=node.annotation))
            return [ast.copy_location(statement, node) for statement in statements] or ast.copy_location(ast.Pass(), node)
        # Function-local annotations cannot coexist with global bindings.
        # Reproduce the module's annotation assignment at its original point.
        statements = []
        if node.value is not None:
            statements.append(ast.Assign(targets=[node.target], value=node.value))
        annotation = (ast.Constant(ast.unparse(node.annotation)) if self.future_annotations
                      else node.annotation)
        statements.append(ast.Assign(targets=[ast.Subscript(
            value=ast.Name(id='__annotations__', ctx=ast.Load()),
            slice=ast.Constant(node.target.id), ctx=ast.Store())], value=annotation))
        return [ast.copy_location(statement, node) for statement in statements]

    def visit_ImportFrom(self, node):
        if any(alias.name == '*' for alias in node.names):
            # Import-star is module-only syntax; execute just that import in
            # the same globals, at the authored point (including conditionals).
            return ast.copy_location(ast.Expr(value=ast.Call(
                func=ast.Attribute(value=ast.Name(id=self.builtins_name, ctx=ast.Load()), attr='exec', ctx=ast.Load()),
                args=[ast.Constant(ast.unparse(node)), ast.Call(
                    func=ast.Attribute(value=ast.Name(id=self.builtins_name, ctx=ast.Load()), attr='globals', ctx=ast.Load()), args=[], keywords=[])],
                keywords=[])), node)
        return node


def compile_script(source, role, label='<script>'):
    """Compile result-bearing bodies as functions and transformer bodies as modules."""
    if role == 'transform':
        return compile(source, label, 'exec', dont_inherit=True), None
    tree = ast.parse(source, filename=label)
    without = _WithoutReturns()
    module_check = without.visit(copy.deepcopy(tree))
    # Check module-only constraints first: invalid future imports, nonlocal,
    # yield/await, and returns inside class bodies must not become valid merely
    # because the script receives a function wrapper.
    compile(ast.fix_missing_locations(module_check), label, 'exec', dont_inherit=True)
    symbols = symtable.symtable(ast.unparse(module_check), label, 'exec')
    names = sorted(symbol.get_name() for symbol in symbols.get_symbols()
                   if symbol.is_assigned() or symbol.is_imported())
    identifiers = set(symbols.get_identifiers())
    identifiers.update(node.id for node in ast.walk(tree) if isinstance(node, ast.Name))
    entry = '__oie_script_body__'
    while entry in identifiers:
        entry += '_'
    futures, body = [], list(tree.body)
    if body and isinstance(body[0], ast.Expr) and isinstance(body[0].value, ast.Constant) and isinstance(body[0].value.value, str):
        futures.append(body.pop(0))
    while body and isinstance(body[0], ast.ImportFrom) and body[0].module == '__future__':
        futures.append(body.pop(0))
    future_annotations = any(isinstance(node, ast.ImportFrom) and any(
        alias.name == 'annotations' for alias in node.names) for node in futures)
    builtins_name = entry + '_builtins'
    while builtins_name in identifiers:
        builtins_name += '_'
    rewrite = _ModuleStatements(future_annotations, builtins_name)
    body = rewrite.visit(ast.Module(body=body, type_ignores=[])).body
    if rewrite.has_annotations:
        # Module annotations exist before the first statement executes.
        futures.append(ast.Assign(targets=[ast.Name(id='__annotations__', ctx=ast.Store())], value=ast.Dict(keys=[], values=[])))
    body.insert(0, ast.Import(names=[ast.alias(name='builtins', asname=builtins_name)]))
    if names:
        body.insert(0, ast.Global(names=names))
    if role == 'filter':
        body.append(ast.Return(value=ast.Constant(True)))
    function = ast.FunctionDef(name=entry, args=ast.arguments(posonlyargs=[], args=[],
        kwonlyargs=[], kw_defaults=[], defaults=[]), body=body, decorator_list=[])
    wrapped = ast.fix_missing_locations(ast.Module(body=futures + [function], type_ignores=[]))
    return compile(wrapped, label, 'exec', dont_inherit=True), entry


def execute_script(compiled, values):
    """Execute in fresh caller-owned globals and return the authored value."""
    code, entry = compiled
    exec(code, values)
    if entry is not None:
        function = values.pop(entry)
        return function()
