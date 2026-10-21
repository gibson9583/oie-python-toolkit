/* SPDX-License-Identifier: MPL-2.0 */
import {createServer} from 'node:http';
import {mkdir, readFile, readdir, writeFile} from 'node:fs/promises';
import {resolve, dirname} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
import {canonicalSpec, sha256} from '../web/ui-model.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '../../..');
const host = process.env.OIE_WEB_HOST || resolve(repo, '../oie-web-client');
const runtime = process.env.OIE_WEB_RUNTIME;
const evidence = process.env.OIE_WEB_ART || resolve(repo, 'validation/stock-admin');
if (!runtime) throw new Error('OIE_WEB_RUNTIME must identify an owned stock WAR extraction.');
await mkdir(evidence, {recursive: true});
const {chromium} = await import(pathToFileURL(resolve(host, 'node_modules/playwright/index.mjs')));
const assets = await readdir(resolve(runtime, 'assets'));
const mountAsset = assets.find(name => /^mount-.*\.js$/.test(name));
const editorAsset = assets.find(name => /^filter-transformer-.*\.js$/.test(name));

const harness = `
import {gt, n as mount} from '/assets/${mountAsset}';
import {platform} from '/core/pkg-shell.js';
import {CodeEditor} from '/core/codeeditor.js';
import * as views from '/assets/${editorAsset}';
import * as api from '/core/pkg-api.js';
platform.React = gt();
platform.setCodeEditorFactory(options => new CodeEditor(options));
const builtin = await import('/plugins/transformer-steps/web/plugin.js');
builtin.register(platform);
const plugin = await import('/toolkit.js');
plugin.register(platform, {getAssetText: async relativePath => {
    if (relativePath !== 'web/python-language.json') throw new Error('Unexpected plugin asset: ' + relativePath);
    const response = await fetch('/plugins/python-runtime/' + relativePath);
    if (!response.ok) throw new Error('Plugin asset unavailable: ' + response.status);
    return response.text();
}});
platform.store.setState('serverVersion', '4.6.0');
platform.store.setState('serverId', '11111111-1111-1111-1111-111111111111');
window.mountFixture = (role, standalone = false) => {
    window.teardown?.();
    const app = document.querySelector('#app');
    app.replaceChildren();
    const step = platform.stepType('com.mirth.connect.plugins.python.PythonStep').create();
    step.environmentProfileId = 'profile';
    const iterator = platform.stepType('com.mirth.connect.model.IteratorStep').create();
    iterator.name = 'Outer iterator';
    iterator.properties.target = 'msg.items';
    iterator.properties.children = api.arrayToElements([step]);
    const transformer = api.emptyTransformer('4.6.0');
    transformer.inboundDataType = 'JSON';
    transformer.outboundDataType = 'JSON';
    transformer.elements = api.arrayToElements([iterator]);
    const source = {metaDataId: 0, mode: 'SOURCE', name: 'Source', transformer};
    const destination = {metaDataId: 7, mode: 'DESTINATION', name: 'Destination', transformer, responseTransformer: transformer};
    const channel = {id: '22222222-2222-2222-2222-222222222222', name: 'Placement fixture', sourceConnector: source,
        destinationConnectors: {connector: [destination]}};
    platform.store.setState('editingChannel', channel);
    const kind = role === 'response-transformer' ? 'response' : 'transformer';
    const params = {channelId: channel.id, metaDataId: role === 'source-transformer' ? 0 : 7};
    history.replaceState(null, '', '/channels/' + channel.id + (standalone ? '/' + kind + '/' + params.metaDataId : '/edit'));
    if (standalone) {
        const View = kind === 'response' ? views.ResponseTransformerView : views.TransformerView;
        window.teardown = mount(app, platform.React.createElement(View, {params}));
    } else {
        const editor = views.createEmbeddedEditor(params, kind, () => {});
        app.appendChild(editor.el);
        window.teardown = editor.teardown;
    }
};
window.ready = true;
`;
const html = `<!doctype html><html><head><script type="importmap">{"imports":{"@oie/web-shell":"/core/pkg-shell.js","@oie/web-api":"/core/pkg-api.js","@oie/web-ui":"/core/pkg-ui.js","js-beautify":"/vendor/js-beautify.js"}}</script></head><body><main id="app"></main><script type="module" src="/harness.js"></script></body></html>`;
const server = createServer(async (request, response) => {
    try {
        const path = new URL(request.url, 'http://fixture').pathname;
        response.setHeader('content-type', path === '/' ? 'text/html' : path.endsWith('.json') ? 'application/json' : 'text/javascript');
        response.end(path === '/' ? html : path === '/harness.js' ? harness
            : await readFile(path === '/toolkit.js' ? resolve(here, '../web/plugin.js') : path === '/plugins/python-runtime/web/python-language.json' ? resolve(here, '../web/python-language.json') : resolve(runtime, path.slice(1))));
    } catch (error) { response.statusCode = 404; response.end(String(error)); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = await chromium.launch({headless: true, executablePath: process.env.OIE_BROWSER_EXECUTABLE});
const page = await browser.newPage();
page.setDefaultTimeout(10000);
const errors = [], checks = [], requests = [];
let blockedStage, release, runtimeReads = 0;
page.on('pageerror', error => errors.push(error.message));
const profile = {id: 'profile', revision: 1, registryEpoch: 'epoch', name: 'Profile', readiness: {state: 'ready'}};
await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname;
    let value = {};
    if (path.endsWith('/profiles')) value = {registryEpoch: 'epoch', profiles: [profile]};
    else if (path.endsWith('/runtime')) {
        runtimeReads++;
        if (blockedStage === 'runtime') await new Promise(resolve => release = resolve);
        value = {nodeId: 'node'};
    } else if (path.endsWith('/profiles/profile')) value = profile;

    else if (/\/scripts\/(validate|test)$/.test(path)) {
        const body = JSON.parse(request.postData());
        requests.push({path, body});
        if (blockedStage === 'validate' && path.endsWith('/validate')) await new Promise(resolve => release = resolve);
        value = {nodeId: 'node', profileId: 'profile', requestId: body.requestId, registryEpoch: 'epoch', environmentRevision: 1,
            sourceDigest: sha256(canonicalSpec(body)), status: path.endsWith('/test') ? 'success' : 'valid', diagnostics: [], output: {marker: 'sample complete'}};
    }
    await route.fulfill({contentType: 'application/json', body: JSON.stringify({string: JSON.stringify(value)})});
});

async function waitFor(predicate) {
    for (let attempt = 0; attempt < 200; attempt++) {
        if (predicate()) return;
        await page.waitForTimeout(10);
    }
    assert.ok(predicate(), 'Expected request did not arrive');
}
async function move(action) {
    await page.locator('tr.selected[data-path] td').nth(1).click({button: 'right'});
    await page.getByText(action, {exact: true}).click();
}
try {
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.waitForFunction(() => window.ready);
    for (const standalone of [false, true]) {
        for (const role of ['source-transformer', 'destination-transformer', 'response-transformer']) {
            await page.evaluate(({role, standalone}) => window.mountFixture(role, standalone), {role, standalone});
            await page.locator('tr[data-path="0.0"] td').nth(1).click();
            if (!standalone) {
                await page.getByLabel('Validation role', {exact: true}).selectOption(role);
                if (role !== 'source-transformer') await page.getByLabel('Validation connector', {exact: true}).selectOption('7');
            }
            const before = requests.length, reads = runtimeReads;
            await page.getByRole('button', {name: 'Validate Python', exact: true}).click();
            await page.getByText(/Python steps cannot be placed inside Iterator/).waitFor();
            await page.getByRole('button', {name: 'Test sample', exact: true}).click();
            await page.getByRole('button', {name: 'Run sample once', exact: true}).click();
            await page.getByText(/Python steps cannot be placed inside Iterator/).waitFor();
            assert.equal(requests.length, before); assert.equal(runtimeReads, reads);
            await move('Remove From Iterator');
            await page.getByRole('button', {name: 'Validate Python', exact: true}).click();
            await page.getByText('Python script validated', {exact: true}).waitFor();
            assert.equal(requests.at(-1).body.role, role);
            assert.equal(requests.at(-1).body.connectorMetadataId, role === 'source-transformer' ? 0 : 7);
            await page.getByRole('button', {name: 'Run sample once', exact: true}).click();
            await page.getByText('Sample completed', {exact: true}).waitFor();
            checks.push(`${standalone ? 'Standalone' : 'Embedded'} ${role}: nested Validate/Test reject locally; moving out allows both`);
        }
    }
    blockedStage = 'validate';
    await page.getByRole('button', {name: 'Validate Python', exact: true}).click();
    await waitFor(() => !!release);
    await move('Assign To Iterator');
    await move('Remove From Iterator');
    blockedStage = null; release(); release = null;
    await page.waitForTimeout(120);
    assert.equal(await page.getByText('Python script validated', {exact: true}).count(), 0);
    checks.push('Moving into and back out of Iterator discards an in-flight validation result');
    blockedStage = 'runtime';
    const beforeTest = requests.filter(request => request.path.endsWith('/test')).length;
    await page.getByRole('button', {name: 'Run sample once', exact: true}).click();
    await waitFor(() => !!release);
    await move('Assign To Iterator');
    blockedStage = null; release(); release = null;
    await page.waitForTimeout(120);
    assert.equal(requests.filter(request => request.path.endsWith('/test')).length, beforeTest);
    checks.push('Moving into Iterator during Test prerequisite read prevents execution');
    assert.deepEqual(errors, []);
    await writeFile(resolve(evidence, 'browser-step-placement-result.json'), JSON.stringify({status: 'passed', checks, requests,
        host: 'Actual unchanged stock WAR transformer/Iterator UI; fixture runtime APIs', browser: await browser.version()}, null, 2));
    console.log(`Stock editor placement checks passed: ${checks.length}`);
} catch (error) {
    await writeFile(resolve(evidence, 'browser-step-placement-failure.html'), await page.content());
    console.log(errors, await page.locator('body').innerText());
    throw error;
} finally {
    release?.();
    await browser.close();
    await new Promise(resolve => server.close(resolve));
}
