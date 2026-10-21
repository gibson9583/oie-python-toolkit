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
const art = resolve(process.env.OIE_WEB_ART || resolve(here, '../../../validation/code-templates/javascript-completions'));
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
            : path === '/plugins/oie-python-toolkit/web/python-language.json' ? resolve(here, '../web/python-language.json')
            : path === '/harness.js' ? resolve(art, 'browser-harness.js') : resolve(runtime, path.slice(1));
        response.setHeader('content-type', file.endsWith('.css') ? 'text/css'
            : file.endsWith('.woff2') ? 'font/woff2' : file.endsWith('.json') ? 'application/json' : 'text/javascript');
        response.end(await readFile(file));
    } catch (error) { response.statusCode = 404; response.end(error.message); }
});
const errors = [], observations = {};
let browser;
async function openPage() {
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/api/**', async route => {
        const path = new URL(route.request().url()).pathname;
        if (path === '/api/server/id') {
            await route.fulfill({ contentType: 'text/plain', body: 'native-server' }); return;
        }
        const profile = { id: 'profile', revision: 1, name: 'Standard library',
            readiness: { state: 'ready', nodeId: 'node' } };
        const value = path.endsWith('/profiles') ? { registryEpoch: 'epoch', profiles: [profile] }
            : path.endsWith('/runtime') ? { nodeId: 'node', runtimes: [], packageSources: [] } : {};
        await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ string: JSON.stringify(value) }) });
    });
    await page.goto(`http://127.0.0.1:${server.address().port}/?monaco`);
    await page.waitForFunction(() => window.ready);
    return page;
}

try {
    await new Promise(done => server.listen(0, '127.0.0.1', done));
    browser = await chromium.launch({ headless: true, executablePath: process.env.OIE_BROWSER_EXECUTABLE });
    const page = await openPage();
    const contexts = ['SOURCE_FILTER_TRANSFORMER', 'DESTINATION_FILTER_TRANSFORMER', 'DESTINATION_RESPONSE_TRANSFORMER'];
    await page.route('**/api/codeTemplateLibraries*', async route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ list: { codeTemplateLibrary: [{
        id: 'python-library', name: 'Python Helpers', revision: 1, includeNewChannels: false,
        enabledChannelIds: { string: ['channel'] }, disabledChannelIds: '',
        codeTemplates: { codeTemplate: [{ id: 'python-template', name: 'Normalize Patient', revision: 1,
            contextSet: { delegate: { contextType: contexts } },
            properties: { '@class': 'com.mirth.connect.plugins.python.PythonCodeTemplateProperties', type: 'FUNCTION', functionName: 'normalize_patient', pythonScript: 'return arguments[0]', environmentProfileId: 'profile' }
        }, { id: 'second-python-template', name: 'Other Helper', revision: 1, contextSet: { delegate: { contextType: contexts } }, properties: { '@class': 'com.mirth.connect.plugins.python.PythonCodeTemplateProperties', type: 'FUNCTION', functionName: 'other_helper', pythonScript: 'return None', environmentProfileId: 'profile' } }] }
    }] } }) }));
    await page.evaluate(async () => {
        const { createCodeEditor } = await import('/core/codeeditor.js');
        window.javascriptEditor = createCodeEditor({ value: 'normalize_p', ariaLabel: 'JavaScript calling Python function' });
        document.body.appendChild(window.javascriptEditor.el);
    });
    await page.waitForFunction(() => window.javascriptEditor.monaco);
    for (const context of contexts) {
        observations[context] = await page.evaluate(async context => {
            const completion = await import('/core/script-completions.js');
            await completion.setActiveScope('channel', [context]);
            return { entries: completion.getActiveCompletions(), libraries: completion.getActiveLibs(), format: window.javascriptEditor.monaco.getAction('editor.action.formatDocument').isSupported() };
        }, context);
        assert.ok(observations[context].entries.some(entry => entry.name === 'normalize_patient'));
        assert.ok(observations[context].entries.some(entry => entry.name === 'other_helper'));
        assert.equal(observations[context].format, true);
    }
    await page.evaluate(() => {
        const editor = window.javascriptEditor.monaco;
        editor.focus(); editor.setPosition({ lineNumber: 1, column: 12 });
        editor.trigger('probe', 'editor.action.triggerSuggest', {});
    });
    await page.waitForTimeout(600);
    observations.javascriptSuggestions = await page.locator('.suggest-widget.visible .monaco-list-row').allTextContents();
    assert.ok(observations.javascriptSuggestions.some(value => value.includes('normalize_patient')));
    observations.functionQuickInfo = await page.evaluate(async () => {
        const model = window.javascriptEditor.monaco.getModel();
        model.setValue('var normalized = normalize_patient({id: 1});');
        const ts = window.monaco.languages.typescript || window.monaco.typescript;
        const accessor = await ts.getJavaScriptWorker();
        const worker = await accessor(model.uri);
        const info = await worker.getQuickInfoAtPosition(model.uri.toString(), 23);
        return info?.displayParts?.map(value => value.text).join('');
    });
    assert.match(observations.functionQuickInfo, /normalize_patient\(\.\.\.args:/);
    assert.doesNotMatch(observations.functionQuickInfo, /\bvoid\b/);
    for (const type of ['string', 'number', 'boolean']) assert.ok(observations.functionQuickInfo.includes(type));
    assert.ok(observations.functionQuickInfo.includes('[]'), 'return contract includes JSON arrays');
    observations.workerContract = await page.evaluate(async () => {
        const model = window.javascriptEditor.monaco.getModel();
        const source = 'var normalized = normalize_patient({id: 1}, [true, null], "value", 3);';
        model.setValue(source);
        const ts = window.monaco.languages.typescript || window.monaco.typescript;
        const worker = await (await ts.getJavaScriptWorker())(model.uri);
        const signature = await worker.getSignatureHelpItems(model.uri.toString(), source.indexOf('{id'));
        const diagnostics = await worker.getSemanticDiagnostics(model.uri.toString());
        return { signature, diagnostics };
    });
    const signature = observations.workerContract.signature?.items?.[0];
    assert.equal(signature?.isVariadic, true, 'real worker offers arbitrary positional arguments');
    assert.equal(signature.parameters.length, 1);
    assert.deepEqual(observations.workerContract.diagnostics, [], 'JSON calls accept multiple arguments without type errors');
    observations.otherContexts = await page.evaluate(async () => {
        const completion = await import('/core/script-completions.js');
        await completion.setActiveScope('channel', ['SOURCE_CONNECTOR']);
        const sourceConnector = completion.getActiveCompletions();
        await completion.setActiveScope('other-channel', ['SOURCE_FILTER_TRANSFORMER']);
        return { sourceConnector, otherChannel: completion.getActiveCompletions() };
    });
    assert.deepEqual(observations.otherContexts, { sourceConnector: [], otherChannel: [] });
    assert.deepEqual(errors, []);
    assert.deepEqual(await hashes(), before);
    await writeFile(resolve(art, 'browser-result.json'), JSON.stringify({ status: 'passed', browser: await browser.version(), observations, errors, inputHashes: before,
        backend: 'Stock library JSON fixture following actual CodeTemplateServlet List<CodeTemplateLibrary> contract; real host scope resolution and TypeScript worker; engine execution reviewed separately.' }, null, 2) + '\n');
    console.log('Python function JavaScript-call signatures are variadic, JSON-valued, and scoped correctly.');
} finally {
    if (browser) await browser.close();
    await new Promise(done => server.close(done));
    await writeFile(resolve(art, 'cleanup.json'), JSON.stringify({ browserClosed: true, serverClosed: true, isolatedEngineCreated: false, isolatedDatabaseCreated: false, hostFilesModified: false }, null, 2) + '\n');
}
