/* SPDX-License-Identifier: MPL-2.0 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { parsePythonLanguage, registerPythonEditorLanguage } from '../web/python-language.mjs';

const asset = await readFile(new URL('../web/python-language.json', import.meta.url), 'utf8');

test('registration fetches no Python grammar until a Python editor asks for it', async () => {
    let provider, requests = 0;
    registerPythonEditorLanguage({ registerEditorLanguage(id, value) { assert.equal(id, 'python'); provider = value; } }, {
        async getAssetText(path) { requests++; assert.equal(path, 'web/python-language.json'); return asset; }
    });
    assert.equal(requests, 0);
    assert.deepEqual(provider.extensions, ['.py']);
    const value = await provider.load();
    assert.equal(requests, 1);
    assert.equal(value.configuration.comments.lineComment, '#');
    assert.ok(value.configuration.onEnterRules[0].beforeText instanceof RegExp);
    assert.ok(value.configuration.onEnterRules[0].beforeText.test('def helper():'));
    assert.ok(value.tokensProvider.tokenizer.root.some(rule => Array.isArray(rule) && rule[0] instanceof RegExp));
});

test('plugin language reads can retry after an asset failure', async () => {
    let provider, requests = 0;
    registerPythonEditorLanguage({ registerEditorLanguage(_id, value) { provider = value; } }, {
        async getAssetText() { if (++requests === 1) throw new Error('Asset unavailable'); return asset; }
    });
    await assert.rejects(provider.load(), /Asset unavailable/);
    assert.ok((await provider.load()).tokensProvider.keywords.includes('def'));
    assert.equal(requests, 2);
    assert.throws(() => registerPythonEditorLanguage({}, {}), /plugin language and asset contracts/);
});

test('malformed language data fails before Monaco receives a definition', () => {
    for (const text of ['invalid JSON', '{}', '{"schemaVersion":2}', '{"$regexp":"x"}', '{"$regexp":"[","$flags":""}']) {
        assert.throws(() => parsePythonLanguage(text));
    }
});

test('packaged grammar data identifies the pinned source and carries its license', async () => {
    const require = createRequire(import.meta.url);
    const source = await readFile(require.resolve('monaco-editor/languages/definitions/python/python.js'));
    assert.equal(JSON.parse(asset).sourceSha256, createHash('sha256').update(source).digest('hex'));
    assert.match(await readFile(new URL('../web/python-language.LICENSE.txt', import.meta.url), 'utf8'), /Microsoft Corporation/);
    const entry = await readFile(new URL('../web/plugin.js', import.meta.url), 'utf8');
    assert.ok(!entry.includes('__subclasses__'), 'grammar keyword tables must stay out of the eagerly loaded plugin entry');
    assert.ok(!entry.includes('/vendor/monaco/python.js'), 'the plugin must not rely on a host Python asset');
});
