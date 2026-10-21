/* SPDX-License-Identifier: MPL-2.0 */
import { build } from 'esbuild';
import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { createServer } from 'node:http';
import { resolve, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';
import { sha256 } from '../web/ui-model.mjs';
import { canonicalTemplateSpec } from '../web/code-template-model.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const host = resolve(process.env.OIE_WEB_HOST || resolve(here, '../../../../oie-web-client'));
const art = resolve(process.env.OIE_WEB_ART || resolve(here, '../../../validation/code-templates/web'));
const runtime = resolve(host, 'web-administrator/client');
await mkdir(art, { recursive: true });
const { chromium } = await import(pathToFileURL(resolve(host, 'node_modules/playwright/index.mjs')).href);
await build({ entryPoints: [resolve(here, 'browser-harness.jsx')], outfile: resolve(art, 'browser-harness.js'), bundle: true, format: 'esm', target: 'es2022', external: ['@oie/web-shell', '/toolkit.js', '/core/codeeditor.js'], alias: { react: resolve(host, 'node_modules/react'), 'react-dom': resolve(host, 'node_modules/react-dom') } });
const css = (await readdir(resolve(runtime, 'dist/assets'))).find(value => value.endsWith('.css'));
const html = `<!doctype html><html data-theme="light"><head><meta name="viewport" content="width=device-width,initial-scale=1"><script type="importmap">{"imports":{"@oie/web-shell":"/core/pkg-shell.js","@oie/web-api":"/core/pkg-api.js","@oie/web-ui":"/core/pkg-ui.js","js-beautify":"/vendor/js-beautify.js"}}</script><link rel="stylesheet" href="/dist/assets/${css}"></head><body><main id="app" style="padding:24px"></main><script type="module" src="/harness.js"></script></body></html>`;
const server = createServer(async (req, res) => {
    try {
        const path = new URL(req.url, 'http://local').pathname;
        if (path === '/') { res.setHeader('content-type', 'text/html'); res.end(html); return; }
        const file = path === '/toolkit.js' ? resolve(here, '../web/plugin.js') : path === '/plugins/python-runtime/web/python-language.json' ? resolve(here, '../web/python-language.json') : path === '/harness.js' ? resolve(art, 'browser-harness.js') : resolve(runtime, path.slice(1));
        res.setHeader('content-type', file.endsWith('.css') ? 'text/css' : file.endsWith('.woff2') ? 'font/woff2' : file.endsWith('.json') ? 'application/json' : 'text/javascript');
        res.end(await readFile(file));
    } catch (error) { res.statusCode = 404; res.end(error.message); }
});
await new Promise(done => server.listen(0, '127.0.0.1', done));
const browser = await chromium.launch({ headless: true, executablePath: process.env.OIE_BROWSER_EXECUTABLE });
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
const errors = [], requests = [], receipts = [];
page.on('pageerror', error => errors.push(error.message));
page.on('request', request => requests.push(request.url()));
let releaseValidation, holdValidation = false, failProfiles = false;
const profile = { id: 'profile', revision: 2, registryEpoch: 'epoch', name: 'Standard library', runtimeId: 'python', packageSourceId: 'approved', readiness: { state: 'ready', nodeId: 'node', pythonVersion: '3.14.6' } };
await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname.replace('/api/extensions/python-runtime', '');
    let value;
    if (url.pathname === '/api/server/id') { await route.fulfill({ contentType: 'text/plain', body: 'native-server' }); return; }
    if (path === '/profiles' && failProfiles) { await route.fulfill({ status: 503, body: 'Environment service unavailable' }); return; }
    if (path === '/profiles') value = { registryEpoch: 'epoch', profiles: [profile] };
    else if (path === '/profiles/profile') value = profile;
    else if (path === '/runtime') value = { nodeId: 'node', runtimes: [], packageSources: [] };
    else if (path === '/templates/validate') {
        const body = JSON.parse(req.postData()); receipts.push(body);
        if (holdValidation) await new Promise(done => { releaseValidation = done; });
        value = { nodeId: 'node', requestId: body.requestId, sourceDigest: sha256(canonicalTemplateSpec(body)), registryEpoch: 'epoch', environmentRevision: 2, status: 'valid', diagnostics: [] };
    } else value = {};
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ string: JSON.stringify(value) }) });
});
async function show(options = {}) {
    await page.evaluate(options => window.showPanel('template', options), options);
    await page.getByRole('region', { name: 'Python function' }).waitFor();
    await page.waitForFunction(() => window.lastEditor?.monaco?.getModel().getLanguageId() === 'python');
}
try {
    await page.goto(`http://127.0.0.1:${server.address().port}/?monaco`);
    await page.waitForFunction(() => window.ready);
    assert.equal(requests.some(url => url.includes('/plugins/python-runtime/web/python-language.json')), false, 'Settings must not load Python');
    await show();
    assert.equal(await page.getByLabel('Function name', { exact: true }).inputValue(), 'Normalize_patient');
    await page.getByLabel('Function name', { exact: true }).fill('normalize_patient');
    await page.getByLabel('Script environment', { exact: true }).selectOption('profile');
    await page.evaluate(() => window.lastEditor.setValue('return str(arguments[0]).strip().upper()\n'));
    // setValue is intentionally silent; user edits fire the model change listener.
    await page.evaluate(() => window.lastEditor.monaco.executeEdits('test', [{ range: { startLineNumber: 2, startColumn: 5, endLineNumber: 2, endColumn: 5 }, text: ' ' }]));
    await page.getByRole('button', { name: 'Validate Python', exact: true }).click();
    await page.getByText('Python function validated', { exact: true }).waitFor();
    assert.equal(receipts[0].functionName, 'normalize_patient');
    assert.deepEqual(receipts[0].contextTypes, ['SOURCE_FILTER_TRANSFORMER']);
    assert.equal(receipts[0].role, 'code-template');
    assert.equal(requests.filter(url => url.includes('/plugins/python-runtime/web/python-language.json')).length, 1);
    assert.equal(requests.some(url => url.includes('/vendor/monaco/python.js')), false, 'The host must not serve Python grammar');
    const grammar = await page.evaluate(() => ({ language: window.lastEditor.monaco.getModel().getLanguageId(), tabSize: window.lastEditor.monaco.getModel().getOptions().tabSize, spaces: window.lastEditor.monaco.getModel().getOptions().insertSpaces, detectIndentation: window.lastEditor.opts.detectIndentation, keyword: window.monaco.editor.tokenize('def helper():', 'python')[0].some(token => token.type.includes('keyword')) }));
    assert.deepEqual(grammar, { language: 'python', tabSize: 4, spaces: true, detectIndentation: false, keyword: true });
    await page.screenshot({ path: resolve(art, 'python-template-desktop.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    await page.screenshot({ path: resolve(art, 'python-template-mobile.png'), fullPage: true });
    await page.setViewportSize({ width: 1280, height: 900 });

    await page.getByLabel('Function name', { exact: true }).fill('function');
    assert.equal(await page.getByLabel('Function name', { exact: true }).getAttribute('aria-invalid'), 'true');
    const count = receipts.length;
    await page.getByRole('button', { name: 'Validate Python', exact: true }).click();
    await page.getByText('Python function needs attention', { exact: true }).waitFor();
    assert.equal(receipts.length, count);
    await page.getByLabel('Function name', { exact: true }).fill('normalize_patient');

    holdValidation = true;
    await page.getByRole('button', { name: 'Validate Python', exact: true }).click();
    for (let i = 0; i < 100 && !releaseValidation; i++) await page.waitForTimeout(20);
    assert.ok(releaseValidation);
    await page.getByLabel('Function name', { exact: true }).fill('changed_function');
    releaseValidation(); releaseValidation = null; await page.waitForTimeout(100);
    assert.equal(await page.getByText('Python function validated', { exact: true }).count(), 0);
    await page.getByRole('button', { name: 'Validate Python', exact: true }).click();
    for (let i = 0; i < 100 && !releaseValidation; i++) await page.waitForTimeout(20);
    assert.ok(releaseValidation);
    await page.evaluate(() => { window.platform.store.setState('serverId', 'other'); window.platform.store.setState('serverId', 'node'); });
    releaseValidation(); releaseValidation = null; holdValidation = false; await page.waitForTimeout(100);
    assert.equal(await page.getByText('Python function validated', { exact: true }).count(), 0);

    failProfiles = true;
    const draft = await page.evaluate(() => structuredClone(window.subject));
    await page.getByRole('button', { name: 'Refresh environments', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: 'Your function draft is preserved.' }).waitFor();
    assert.deepEqual(await page.evaluate(() => structuredClone(window.subject)), draft);
    failProfiles = false;
    await show({ readOnly: true });
    assert.equal(await page.getByLabel('Function name', { exact: true }).isDisabled(), true);
    assert.equal(await page.getByRole('button', { name: 'Validate Python', exact: true }).count(), 0);
    assert.equal(await page.evaluate(() => window.lastEditor.opts.readOnly), true);
    assert.equal(requests.some(url => url.includes('/vendor/monaco/python.js')), false);
    assert.deepEqual(errors, []);
    await writeFile(resolve(art, 'browser-result.json'), JSON.stringify({ status: 'passed', browser: await browser.version(), checks: ['plugin registered via public contract', 'plugin-owned Python grammar loads only when its editor opens; no host Python asset', 'Python grammar and indentation', 'template-specific validation receipt', 'invalid function name prevents request', 'stale edit and engine-away/back validation discarded', 'environment failure preserves draft', 'read-only controls and editor', 'desktop/mobile overflow'], requests: receipts }, null, 2) + '\n');
    console.log('Python code-template browser checks passed');
} catch (error) {
    await page.screenshot({ path: resolve(art, 'failure.png'), fullPage: true });
    await writeFile(resolve(art, 'failure.html'), await page.content());
    await writeFile(resolve(art, 'failure.json'), JSON.stringify({ error: error.stack, errors, requests }, null, 2));
    throw error;
} finally {
    releaseValidation?.();
    await browser.close();
    await new Promise(done => server.close(done));
}
