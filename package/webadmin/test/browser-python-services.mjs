/* SPDX-License-Identifier: MPL-2.0 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { createServer } from 'node:http';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { build } from 'esbuild';

const here = dirname(fileURLToPath(import.meta.url));
const host = resolve(process.env.OIE_WEB_HOST || resolve(here, '../../../../oie-web-client'));
const runtime = resolve(host, 'web-administrator/client');
const art = resolve(process.env.OIE_WEB_ART || resolve(here, '../../../validation/monaco-contracts-20261019'));
await mkdir(art, { recursive: true });
const { chromium } = await import(pathToFileURL(resolve(host, 'node_modules/playwright/index.mjs')).href);
await build({
    entryPoints: [resolve(here, 'browser-harness.jsx')],
    outfile: resolve(art, 'browser-harness.js'),
    bundle: true, format: 'esm', target: 'es2022',
    external: ['@oie/web-shell', '/toolkit.js', '/core/codeeditor.js'],
    alias: { react: resolve(host, 'node_modules/react'), 'react-dom': resolve(host, 'node_modules/react-dom') }
});
const inputs = [
    ...['core/platform.js', 'core/codeeditor.js', 'core/monaco.js', 'core/editor-languages.js',
        'vendor/monaco/editor.main.js', 'vendor/monaco/provenance.json']
        .map(path => resolve(runtime, path)),
    resolve(here, '../web/plugin.js'), resolve(here, '../web/python-language.json'), resolve(here, 'browser-harness.jsx')
];
async function hashes() {
    return Object.fromEntries(await Promise.all(inputs.map(async path => [
        path, createHash('sha256').update(await readFile(path)).digest('hex')
    ])));
}
const before = await hashes();
const proxy = process.env.OIE_EDITOR_PROXY;
assert.ok(proxy, 'Use tools/run_editor_services_isolated.py to supply an owned authenticated engine proxy');
const transportRetries = [];
async function proxyFetch(path, options) {
    for (let attempt = 0; ; attempt++) {
        try { return await fetch(proxy + path, options); }
        catch (error) {
            // Shared workstation port pressure can temporarily prevent a local
            // connection. Retry only allocation failures, never HTTP failures.
            if (error.cause?.code !== 'EADDRNOTAVAIL' || attempt >= 4) throw error;
            transportRetries.push({ path, code: error.cause.code, attempt });
            await new Promise(done => setTimeout(done, 500));
        }
    }
}
const catalog = await (await proxyFetch('/catalog')).json();
assert.equal((await readdir(resolve(runtime, 'vendor/monaco'))).includes('python.js'), false, 'Python grammar must not be a host asset');
const css = (await readdir(resolve(runtime, 'dist/assets'))).find(path => path.endsWith('.css'));
const html = `<!doctype html><html data-theme="light"><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<script type="importmap">{"imports":{"@oie/web-shell":"/core/pkg-shell.js","@oie/web-api":"/core/pkg-api.js","@oie/web-ui":"/core/pkg-ui.js","js-beautify":"/vendor/js-beautify.js"}}</script>
<link rel="stylesheet" href="/dist/assets/${css}"></head>
<body><main id="app" style="padding:24px"></main><script type="module" src="/harness.js"></script></body></html>`;
const server = createServer(async (request, response) => {
    try {
        const path = new URL(request.url, 'http://local').pathname;
        if (path === '/') {
            response.setHeader('content-type', 'text/html'); response.end(html); return;
        }
        const file = path === '/toolkit.js' ? resolve(here, '../web/plugin.js')
            : path === '/plugins/python-runtime/web/python-language.json' ? resolve(here, '../web/python-language.json')
            : path === '/harness.js' ? resolve(art, 'browser-harness.js') : resolve(runtime, path.slice(1));
        response.setHeader('content-type', file.endsWith('.css') ? 'text/css'
            : file.endsWith('.woff2') ? 'font/woff2' : file.endsWith('.json') ? 'application/json' : 'text/javascript');
        response.end(await readFile(file));
    } catch (error) { response.statusCode = 404; response.end(error.message); }
});
const observations = [], errors = [];
let browser;
try {
    await new Promise(done => server.listen(0, '127.0.0.1', done));
    browser = await chromium.launch({ headless: true, executablePath: process.env.OIE_BROWSER_EXECUTABLE });
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/api/**', async route => {
        const path = new URL(route.request().url()).pathname;
        if (path === '/api/server/id') return route.fulfill({ contentType: 'text/plain', body: catalog.runtime.nodeId });
        if (path.endsWith('/editor')) {
            try {
                const response = await proxyFetch('/editor', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: route.request().postData() });
                await route.fulfill({ status: response.status, contentType: 'application/json', body: await response.text() });
            } catch (error) {
                errors.push('Editor proxy transport: ' + error.message);
                // A route-handler rejection otherwise exits Node before finally
                // can close the owned browser and server.
                await route.abort('failed').catch(() => {});
            }
            return;
        }
        const value = path.endsWith('/profiles') ? catalog.profiles : path.endsWith('/runtime') ? catalog.runtime : {};
        return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ string: JSON.stringify(value) }) });
    });
    await page.goto(`http://127.0.0.1:${server.address().port}/?monaco`);
    await page.waitForFunction(() => window.ready);
    // Load the real host runtime first, then record real plugin providers while
    // preserving registration in Monaco. No replacement provider is installed.
    await page.evaluate(async () => {
        const { createCodeEditor } = await import('/core/codeeditor.js');
        window.controlEditor = createCodeEditor({ value: '', language: 'javascript' });
        document.body.appendChild(window.controlEditor.el);
    });
    await page.waitForFunction(() => window.monaco && window.controlEditor.monaco);
    await page.evaluate(() => {
        window.pythonProviders = {};
        for (const [method, name] of [['CompletionItem', 'completion'], ['Hover', 'hover'], ['SignatureHelp', 'signature'],
            ['DocumentFormattingEdit', 'format'], ['DocumentRangeFormattingEdit', 'range']]) {
            const key = 'register' + method + 'Provider', original = window.monaco.languages[key];
            window.monaco.languages[key] = function(language, provider) {
                if (language === 'python') window.pythonProviders[name] = provider;
                return original.call(this, language, provider);
            };
        }
    });
    for (const [kind, role, global] of [['reader', 'reader', 'poll'], ['writer', 'writer', 'payload'],
        ['step', 'source-transformer', 'has_tmp'], ['step', 'destination-transformer', 'has_tmp'],
        ['step', 'response-transformer', 'response'], ['rule', 'source-filter', 'result'], ['rule', 'destination-filter', 'result'], ['template', 'code-template', 'arguments']]) {
        await page.evaluate(({ kind, role, catalog }) => window.showPanel(kind, {
            role,
            subject: { environmentProfileId: catalog.profileId }, channel: { id: catalog.channelId }
        }), { kind, role, catalog });
        await page.waitForFunction(() => window.lastEditor?.monaco?.getModel()?.getLanguageId() === 'python');
        await page.waitForFunction(id => [...document.querySelectorAll('select')].some(select => select.value === id && !select.disabled), catalog.profileId);
        if (kind === 'step') await page.getByLabel('Validation role', { exact: true }).selectOption(role);
        const result = await page.evaluate(async ({ role, global }) => {
            const editor = window.lastEditor.monaco, model = editor.getModel(), providers = window.pythonProviders;
            editor.updateOptions({ quickSuggestions: false, suggestOnTriggerCharacters: false, hover: { enabled: false }, parameterHints: { enabled: false } });
            const set = value => { editor.setValue(value); return model.getPositionAt(value.length); };
            const complete = async source => (await providers.completion.provideCompletionItems(model, set(source), {}, {})).suggestions.map(item => item.label);
            const output = { role, maps: await complete('globalMap.'), logger: await complete('logger.'),
                stdlib: await complete('import json\njson.'), globals: await complete(global.slice(0, -1)) };
            const position = set('globalMap.get(');
            output.signature = (await providers.signature.provideSignatureHelp(model, position, {}))?.value;
            set('globalMap.get');
            output.hover = await providers.hover.provideHover(model, { lineNumber: 1, column: 12 }, {});
            if (role === 'response-transformer') output.response = await complete('response.');
            if (role === 'reader' || role === 'writer') {
                const name = role === 'reader' ? 'Message' : 'Response';
                const at = set(`from oie_python import ${name}\nresult = ${name}(`);
                output.resultSignature = (await providers.signature.provideSignatureHelp(model, at, {}))?.value;
                if (role === 'writer') output.statuses = await complete('from oie_python import Status\nresult = Status.');
            }
            if (role === 'writer') {
                const at = set('𐐀name = 1\n𐐀');
                const values = await providers.completion.provideCompletionItems(model, at, {}, {});
                const item = values.suggestions.find(item => item.label === '𐐀name');
                output.unicodeRange = item && [item.range.startColumn, item.range.endColumn];
                if (item) model.applyEdits([{ range: item.range, text: item.insertText }]);
                output.unicodeCompleted = model.getValue();
            }
            set('result={"value":1,"list":[1,2]}');
            await editor.getAction('editor.action.formatDocument').run();
            output.document = model.getValue();
            set('left=1\nresult={"value":1}\nright=2\n');
            editor.setSelection(new window.monaco.Range(2, 1, 3, 1));
            await editor.getAction('editor.action.formatSelection').run();
            output.selection = model.getValue();
            // A real request is issued before changing source: its eventual
            // formatting result must never overwrite the newer model version.
            set('result={"value":1}');
            const pending = providers.format.provideDocumentFormattingEdits(model, {}, {});
            set('result = "newer edit"');
            output.stale = await pending;
            output.afterStale = model.getValue();
            return output;
        }, { role, global });
        observations.push(result);
        await writeFile(resolve(art, 'browser-result.json'), JSON.stringify({ observations, errors }, null, 2));
        assert.ok(result.maps.includes('get') && result.maps.includes('put'), role + ': SDK map members');
        assert.ok(result.logger.includes('info') && result.logger.includes('error'), role + ': logger members');
        assert.ok(result.stdlib.includes('loads') && result.stdlib.includes('dumps'), role + ': standard library');
        assert.ok(result.globals.includes(global), role + ': role global');
        assert.ok(result.signature?.signatures?.length && result.hover?.contents?.length, role + ': signatures and hover');
        if (result.response) assert.ok(['status', 'status_message', 'error'].every(name => result.response.includes(name)));
        if (role === 'reader' || role === 'writer') {
            const parameters = role === 'reader' ? ['payload', 'source_map', 'destination_ids'] : ['status', 'data', 'status_message', 'error'];
            const label = result.resultSignature?.signatures?.[0]?.label || '';
            assert.ok(parameters.every(name => label.includes(name)), role + ': result constructor signature');
        }
        if (role === 'writer') {
            assert.ok(['SENT', 'ERROR', 'QUEUED'].every(name => result.statuses.includes(name)), 'Writer status enum completion');
            assert.deepEqual(result.unicodeRange, [1, 3]);
            assert.equal(result.unicodeCompleted, '𐐀name = 1\n𐐀name');
        }
        assert.match(result.document, /result = /);
        assert.match(result.selection, /^left=1\nresult = .*\nright=2\n$/);
        assert.deepEqual(result.stale, []);
        assert.equal(result.afterStale, 'result = "newer edit"');
    }
    assert.deepEqual(errors, []);
    assert.deepEqual(await hashes(), before, 'Runtime assets must remain unchanged');
    await writeFile(resolve(art, 'browser-result.json'), JSON.stringify({ status: 'passed', browser: await browser.version(),
        observations, errors, transportRetries, inputHashes: before, backend: 'Actual isolated stock engine through authenticated loopback proxy' }, null, 2) + '\n');
    console.log('Eight real Monaco surfaces passed against owned engine editor service');
} finally {
    await browser?.close();
    await new Promise(done => server.close(done));
    await writeFile(resolve(art, 'transport.json'), JSON.stringify({ transportRetries, errors }, null, 2));
    await writeFile(resolve(art, 'cleanup.json'), JSON.stringify({ browserClosed: true, serverClosed: !server.listening }, null, 2));
}
