/* SPDX-License-Identifier: MPL-2.0 */
// The former picker is no longer a prerequisite for any Python editor.
import { build } from 'esbuild';
import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { createServer } from 'node:http';
import { resolve, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

const here = dirname(fileURLToPath(import.meta.url)), repo = resolve(here, '../../..');
const host = resolve(process.env.OIE_WEB_HOST || resolve(repo, '../oie-web-client'));
const runtime = resolve(process.env.OIE_WEB_RUNTIME || resolve(host, 'web-administrator/client'));
const art = resolve(process.env.OIE_WEB_ART || resolve(repo, 'validation/global-map-access-20261019/browser'));
await mkdir(art, { recursive: true });
const { chromium } = await import(pathToFileURL(resolve(host, 'node_modules/playwright/index.mjs')).href);
await build({ entryPoints: [resolve(here, 'browser-harness.jsx')], outfile: resolve(art, 'browser-harness.js'), bundle: true,
    format: 'esm', target: 'es2022', external: ['@oie/web-shell', '/toolkit.js', '/core/codeeditor.js'],
    alias: { react: resolve(host, 'node_modules/react'), 'react-dom': resolve(host, 'node_modules/react-dom') } });
const cssBase = process.env.OIE_WEB_RUNTIME ? '/assets' : '/dist/assets';
const css = (await readdir(resolve(runtime, cssBase.slice(1)))).find(value => value.endsWith('.css'));
const html = `<!doctype html><html data-theme="light"><head><meta name="viewport" content="width=device-width,initial-scale=1">
<script type="importmap">{"imports":{"@oie/web-shell":"/core/pkg-shell.js","@oie/web-api":"/core/pkg-api.js","@oie/web-ui":"/core/pkg-ui.js","js-beautify":"/vendor/js-beautify.js"}}</script>
<link rel="stylesheet" href="${cssBase}/${css}"></head><body><main id="app" style="padding:24px"></main><script type="module" src="/harness.js"></script></body></html>`;
const server = createServer(async (request, response) => {
    try {
        const path = new URL(request.url, 'http://local').pathname;
        if (path === '/') { response.setHeader('content-type', 'text/html'); response.end(html); return; }
        const file = path === '/toolkit.js' ? resolve(here, '../web/plugin.js')
            : path === '/plugins/oie-python-toolkit/web/python-language.json' ? resolve(here, '../web/python-language.json')
            : path === '/harness.js' ? resolve(art, 'browser-harness.js') : resolve(runtime, path.slice(1));
        response.setHeader('content-type', file.endsWith('.css') ? 'text/css' : file.endsWith('.json') ? 'application/json' : 'text/javascript');
        response.end(await readFile(file));
    } catch (error) { response.statusCode = 404; response.end(error.message); }
});
await new Promise(done => server.listen(0, '127.0.0.1', done));
const browser = await chromium.launch({ headless: true, executablePath: process.env.OIE_BROWSER_EXECUTABLE });
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
const errors = [], checks = [], requests = [];
page.on('pageerror', error => errors.push(error.message));
page.on('request', request => requests.push(request.url()));
await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    const profile = { id: 'profile', revision: 1, registryEpoch: 'epoch', name: 'Standard library', readiness: { state: 'ready', nodeId: 'node' } };
    const value = path.endsWith('/profiles') ? { registryEpoch: 'epoch', profiles: [profile] }
        : path.endsWith('/runtime') ? { nodeId: 'node', runtimes: [], packageSources: [] } : {};
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ string: JSON.stringify(value) }) });
});
try {
    await page.goto(`http://127.0.0.1:${server.address().port}`);
    await page.waitForFunction(() => window.ready);
    const surfaces = [['step', 'source'], ['step', 'destination'], ['step', 'response'], ['reader', 'reader'], ['writer', 'writer'], ['template', 'function']];
    for (const [kind, phase] of surfaces) {
        await page.evaluate(({ kind, phase }) => {
            window.changes = 0;
            window.platform.router.navigate(`/channels/channel/${phase}/1`);
            window.showPanel(kind, { subject: { pythonScript: "globalMap['key'] = 'value'" } });
        }, { kind, phase });
        await page.getByRole('button', { name: 'Validate Python', exact: true }).waitFor();
        await page.getByText('Advanced', { exact: true }).click();
        assert.equal(await page.getByRole('combobox', { name: 'Available shared input', exact: true }).count(), 0);
        assert.equal(await page.getByRole('button', { name: 'Refresh inputs', exact: true }).count(), 0);
        assert.equal(await page.getByText('Selected shared inputs', { exact: true }).count(), 0);
        assert.equal(await page.getByLabel('Invocation timeout (milliseconds)', { exact: true }).count(), 1);
        assert.equal(await page.evaluate(() => Object.hasOwn(window.subject, 'contextInputs')), false);
        assert.equal(await page.locator('.ce-area').inputValue(), "globalMap['key'] = 'value'");
        assert.equal(await page.evaluate(() => window.changes), 0);
        assert.match(await page.locator('main').innerText(), /globalMap.*globalChannelMap.*configurationMap/s);
        for (const width of [1280, 390]) {
            await page.setViewportSize({ width, height: 900 });
            assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
        }
        checks.push(`${phase}: direct map names only, direct map help and responsive editor`);
    }
    await page.evaluate(() => window.showPanel('template', { readOnly: true }));
    await page.getByText('Advanced', { exact: true }).click();
    assert.equal(await page.getByLabel('Invocation timeout (milliseconds)', { exact: true }).isDisabled(), true);
    assert.equal(await page.getByText('Selected shared inputs', { exact: true }).count(), 0);
    for (const kind of ['reader', 'writer']) {
        const defaults = await page.evaluate(kind => {
            const definition = window.platform.connectorPanel(kind === 'reader' ? 'Python Reader' : 'Python Writer', kind === 'reader' ? 'SOURCE' : 'DESTINATION');
            const first = definition.defaults('4.6.0'), second = definition.defaults('4.6.0');
            return { value: first.pluginProperties, independent: first.pluginProperties !== second.pluginProperties };
        }, kind);
        assert.deepEqual(defaults, { value: { '@class': 'set' }, independent: true });
    }
    assert.equal(requests.some(url => url.includes('/context-inputs')), false, 'Editors must not depend on catalog discovery');
    assert.deepEqual(errors, []);
    checks.push('read-only function, independent connector defaults, and zero shared-input discovery requests');
    await writeFile(resolve(art, 'browser-result.json'), JSON.stringify({ status: 'passed', checks, browser: await browser.version(), backend: 'Fixture API; runtime behavior qualified separately on stock engine' }, null, 2) + '\n');
    console.log('Python direct map editor checks passed');
} finally {
    await browser.close();
    await new Promise(done => server.close(done));
}
