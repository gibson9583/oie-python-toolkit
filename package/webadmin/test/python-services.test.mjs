/* SPDX-License-Identifier: MPL-2.0 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { bindPythonEditor, installPythonServices } from '../web/python-services.mjs';

function fixture(source = 'globalMap.get(') {
    const providers = {};
    const monaco = { Range: class { constructor(...values) { this.values = values; } }, languages: { CompletionItemKind: {} } };
    for (const [method, key] of [['CompletionItem','completion'], ['Hover','hover'], ['SignatureHelp','signature'], ['DocumentFormattingEdit','format'], ['DocumentRangeFormattingEdit','range']]) {
        monaco.languages['register' + method + 'Provider'] = (language, provider) => { assert.equal(language, 'python'); providers[key] = provider; };
    }
    installPythonServices(monaco);
    installPythonServices(monaco);
    let version = 1;
    const model = { isDisposed: () => false, getVersionId: () => version, getValue: () => source,
        getLineContent: () => source, getFullModelRange: () => 'full', getLineCount: () => 3, getLineMaxColumn: () => 15 };
    let next = { ok: true, items: [{ name: 'get', type: 'function', prefixLength: 2 }] };
    const requests = [];
    const state = { profile: { id: 'profile', revision: 2 }, epoch: 'registry', nodeId: 'node', role: 'writer', allowed: true,
        send: async body => { requests.push(body); return typeof next === 'function' ? next() : next; } };
    const unbind = bindPythonEditor({ monaco: { getModel: () => model } }, () => state, { capture: () => () => true });
    return { providers, model, requests, state, unbind, next(value) { next = value; }, edit() { version++; } };
}

test('all six Python roles use the same real service contract and Monaco providers', async () => {
    const f = fixture();
    try {
        assert.equal(Object.keys(f.providers).length, 5);
        for (const role of ['reader', 'writer', 'source-transformer', 'destination-transformer', 'response-transformer', 'code-template']) {
            f.state.role = role;
            const value = await f.providers.completion.provideCompletionItems(f.model, { lineNumber: 1, column: 3 }, {}, {});
            assert.equal(value.suggestions[0].label, 'get');
            assert.equal(f.requests.at(-1).role, role);
            assert.equal(f.requests.at(-1).expectedEnvironmentRevision, 2);
            assert.equal(f.requests.at(-1).expectedRegistryEpoch, 'registry');
            assert.equal(f.requests.at(-1).expectedNodeId, 'node');
        }
        f.next({ ok: true, contents: ['get(key, default=None)'] });
        assert.ok(await f.providers.hover.provideHover(f.model, { lineNumber: 1, column: 3 }, {}));
        f.next({ ok: true, signatures: [{ label: 'get(key, default=None)', parameters: ['key', 'default=None'], activeParameter: 1 }] });
        assert.equal((await f.providers.signature.provideSignatureHelp(f.model, { lineNumber: 1, column: 3 }, {})).value.activeParameter, 1);
    } finally { f.unbind(); }
});

test('stale source, environment, cancellation and disposed ownership suppress edits', async () => {
    for (const invalidate of [f => f.edit(), f => { f.state.profile.revision++; }, f => f.unbind()]) {
        const f = fixture();
        try {
            f.next(() => { invalidate(f); return { ok: true, text: 'formatted' }; });
            assert.deepEqual(await f.providers.format.provideDocumentFormattingEdits(f.model, {}, {}), []);
        } finally { f.unbind(); }
    }
    const f = fixture();
    try {
        assert.deepEqual(await f.providers.format.provideDocumentFormattingEdits(f.model, {}, { isCancellationRequested: true }), []);
        assert.equal(f.requests.length, 0);
        f.state.readOnly = true;
        assert.deepEqual(await f.providers.format.provideDocumentFormattingEdits(f.model, {}, {}), []);
    } finally { f.unbind(); }
});

test('format selection expands to touched full lines and failed formatting never returns edits', async () => {
    const f = fixture();
    try {
        f.next({ ok: true, text: 'result = 1\n' });
        const edits = await f.providers.range.provideDocumentRangeFormattingEdits(f.model,
            { startLineNumber: 1, startColumn: 4, endLineNumber: 3, endColumn: 1 }, {}, {});
        assert.deepEqual(f.requests[0].selection, { startLineNumber: 1, endLineNumber: 2 });
        assert.deepEqual(edits[0].range.values, [1, 1, 3, 1]);
        f.next({ ok: false, message: 'Invalid Python source' });
        await assert.rejects(f.providers.format.provideDocumentFormattingEdits(f.model, {}, {}), /Invalid Python/);
    } finally { f.unbind(); }
});

test('Python positions convert UTF16 to code points and unowned models issue no requests', async () => {
    const f = fixture('"😀"; lo');
    try {
        await f.providers.completion.provideCompletionItems(f.model, { lineNumber: 1, column: 8 }, {}, {});
        assert.equal(f.requests[0].column, 6);
        f.unbind();
        assert.deepEqual((await f.providers.completion.provideCompletionItems(f.model, { lineNumber: 1, column: 8 }, {}, {})).suggestions, []);
    } finally { f.unbind(); }
});

test('completion replaces the complete UTF16 prefix of an astral Python identifier', async () => {
    const f = fixture('𐐀');
    try {
        f.next({ ok: true, items: [{ name: '𐐀name', type: 'statement', prefixLength: 1 }] });
        const value = await f.providers.completion.provideCompletionItems(f.model, { lineNumber: 1, column: 3 }, {}, {});
        assert.equal(f.requests[0].column, 1);
        assert.deepEqual(value.suggestions[0].range.values, [1, 1, 1, 3]);
    } finally { f.unbind(); }
});
