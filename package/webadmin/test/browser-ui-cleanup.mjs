/* SPDX-License-Identifier: MPL-2.0 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { createServer } from 'node:http';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { build } from 'esbuild';
import { canonicalSpec, sha256 } from '../web/ui-model.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const host = resolve(process.env.OIE_WEB_HOST || resolve(here, '../../../../oie-web-client'));
const runtime = resolve(host, 'web-administrator/client');
const art = resolve(process.env.OIE_WEB_ART || resolve(here, '../../../validation/ui-cleanup-20261019'));
await mkdir(art, { recursive: true });
const { chromium } = await import(pathToFileURL(resolve(host, 'node_modules/playwright/index.mjs')).href);
await build({
    entryPoints: [resolve(here, 'browser-harness.jsx')], outfile: resolve(art, 'browser-harness.js'),
    bundle: true, format: 'esm', target: 'es2022',
    external: ['@oie/web-shell', '/toolkit.js', '/core/codeeditor.js'],
    alias: { react: resolve(host, 'node_modules/react'), 'react-dom': resolve(host, 'node_modules/react-dom') }
});
const css = (await readdir(resolve(runtime, 'dist/assets'))).find(path => path.endsWith('.css'));
const inputFiles = ['core/platform.js', 'core/codeeditor.js', 'core/monaco.js', `dist/assets/${css}`]
    .map(path => resolve(runtime, path));
inputFiles.push(resolve(here, '../web/plugin.js'), resolve(here, '../web/python-language.json'));
async function hashes() {
    return Object.fromEntries(await Promise.all(inputFiles.map(async path => [
        path, createHash('sha256').update(await readFile(path)).digest('hex')
    ])));
}
const before = await hashes();
const html = `<!doctype html><html data-theme="light"><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<script type="importmap">{"imports":{"@oie/web-shell":"/core/pkg-shell.js","@oie/web-api":"/core/pkg-api.js","@oie/web-ui":"/core/pkg-ui.js","js-beautify":"/vendor/js-beautify.js"}}</script>
<link rel="stylesheet" href="/dist/assets/${css}"></head>
<body><main id="app" style="padding:24px;min-width:0"></main><script type="module" src="/harness.js"></script></body></html>`;
const server = createServer(async (request, response) => {
    try {
        const path = new URL(request.url, 'http://local').pathname;
        if (path === '/') { response.setHeader('content-type', 'text/html'); response.end(html); return; }
        const file = path === '/toolkit.js' ? resolve(here, '../web/plugin.js')
            : path === '/plugins/oie-python-toolkit/web/python-language.json' ? resolve(here, '../web/python-language.json')
            : path === '/harness.js' ? resolve(art, 'browser-harness.js') : resolve(runtime, path.slice(1));
        response.setHeader('content-type', file.endsWith('.css') ? 'text/css'
            : file.endsWith('.json') ? 'application/json' : file.endsWith('.woff2') ? 'font/woff2' : 'text/javascript');
        response.end(await readFile(file));
    } catch (error) { response.statusCode = 404; response.end(error.message); }
});

const baseProfile = { id: 'profile', revision: 1, registryEpoch: 'epoch', name: 'Saved environment',
    description: '', runtimeId: 'python', packageSourceId: 'approved', requirementsText: '',
    readiness: { state: 'ready', nodeId: 'node' }, savedReferenceCount: 0, deployedReferenceCount: 0 };
const python = { id: 'python', label: 'Primary interpreter', version: '3.14.6', available: true };
const approved = { id: 'approved', label: 'Approved package index', kind: 'index' };
const builtin = { id: 'builtin-stdlib', label: 'Standard library', kind: 'builtin' };
const soleChoices = { nodeId: 'node', runtimes: [python], packageSources: [approved, builtin] };
let runtimeConfig = structuredClone(soleChoices), profiles = [{ ...baseProfile }], sampleFailure = false;
const saves = [], samples = [], checks = [], errors = [], measurements = [];
const longLine = 'Long output: ' + 'x'.repeat(700);
const logLines = Array.from({ length: 70 }, (_, index) => `${index + 1}: ${longLine}`);
const diagnostics = [
    { severity: 'error', code: 'SCRIPT_RUNTIME', field: 'pythonScript', line: 2, message: `ValueError: ${longLine.repeat(4)}` },
    { severity: 'info', code: 'TRACEBACK', message: `Traceback (most recent call last):\n${logLines.slice(0, 10).join('\n')}` }
];
const job = { id: 'job', nodeId: 'node', registryEpoch: 'epoch', profileId: 'profile', profileRevision: 1,
    state: 'FAILED', message: 'Preparation failed',
    diagnostics: [{ severity: 'error', code: 'PREPARATION_FAILED', message: 'Dependency preparation failed. See preparation output.' }] };
let browser, page;
async function mount(kind, options = {}) {
    await page.evaluate(({ kind, options }) => window.showPanel(kind, options), { kind, options });
    if (kind === 'settings') await page.getByRole('button', { name: 'Add environment', exact: true }).waitFor();
    else await page.waitForFunction(() => window.lastEditor?.monaco?.getModel()?.getLanguageId() === 'python');
}
async function settings(config, saved = [{ ...baseProfile }]) {
    runtimeConfig = structuredClone(config); profiles = structuredClone(saved);
    await mount('settings');
    await page.getByRole('button', { name: 'Add environment', exact: true }).waitFor();
    await page.waitForFunction(() => ![...document.querySelectorAll('button')].find(button => button.textContent === 'Add environment')?.disabled);
}
async function saveName(name) {
    const count = saves.length;
    await page.getByLabel('Name', { exact: true }).fill(name);
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await page.waitForFunction(() => window.dirty === false);
    assert.equal(saves.length, count + 1);
    return saves.at(-1);
}
async function bounded(label, viewport) {
    const element = page.getByLabel(label, { exact: true });
    await element.waitFor();
    const metrics = await element.evaluate(node => {
        node.scrollTop = 100;
        const style = getComputedStyle(node);
        return { clientHeight: node.clientHeight, scrollHeight: node.scrollHeight,
            scrollTop: node.scrollTop, overflowY: style.overflowY, width: node.getBoundingClientRect().width };
    });
    assert.ok(metrics.clientHeight > 0 && metrics.clientHeight <= 400, `${label} must have a bounded visible height`);
    assert.ok(metrics.scrollHeight > metrics.clientHeight, `${label} needs an internal scroll area`);
    assert.ok(metrics.scrollTop > 0, `${label} must scroll`);
    assert.match(metrics.overflowY, /auto|scroll/);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, `${label} must not widen the page`);
    measurements.push({ label, viewport, ...metrics });
}

try {
    await new Promise(done => server.listen(0, '127.0.0.1', done));
    browser = await chromium.launch({ headless: true, executablePath: process.env.OIE_BROWSER_EXECUTABLE });
    page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/api/**', async route => {
        const request = route.request(), url = new URL(request.url());
        const path = url.pathname.replace('/api/extensions/oie-python-toolkit', '');
        const body = request.postData() ? JSON.parse(request.postData()) : null;
        let value;
        if (url.pathname === '/api/server/id') {
            await route.fulfill({ contentType: 'text/plain', body: 'native-server' }); return;
        }
        if (path === '/profiles' && request.method() === 'GET') value = { registryEpoch: 'epoch', profiles };
        else if (path === '/runtime') value = runtimeConfig;
        else if (path.endsWith('/references')) value = { profileId: path.split('/')[2], nodeId: 'node', saved: [], deployed: [], hiddenCount: 0 };
        else if ((path === '/profiles' && request.method() === 'POST')
            || (/^\/profiles\/[^/]+$/.test(path) && request.method() === 'PUT')) {
            saves.push(body);
            if (body.runtimeId === 'missing-runtime') {
                await route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({
                    status: 400, code: 'INVALID_PROFILE', message: 'Choose an approved Python runtime',
                    diagnostics: [
                        { code: 'RUNTIME_NOT_APPROVED', message: 'Choose an approved Python runtime', field: 'runtimeId' },
                        { code: 'SOURCE_NOT_APPROVED', message: 'Choose an approved package source', field: 'packageSourceId' }
                    ]
                }) });
                return;
            }
            const previous = request.method() === 'PUT' ? profiles.find(profile => profile.id === path.split('/')[2]) : null;
            const profile = { ...baseProfile, ...body, id: previous?.id || `created-${saves.length}`,
                registryEpoch: 'epoch', revision: previous ? previous.revision + 1 : 1 };
            profiles = [...profiles.filter(value => value.id !== profile.id), profile];
            value = { requestId: body.requestId, registryEpoch: 'epoch', profile };
        } else if (path === '/profiles/profile/prepare') {
            profiles[0] = { ...profiles[0], readiness: { state: 'failed', nodeId: 'node' } };
            value = { requestId: body.requestId, registryEpoch: 'epoch', profile: profiles[0], job };
        }
        else if (path === '/jobs/job/logs') value = { jobId: 'job', nodeId: 'node', lines: logLines };
        else if (path === '/jobs/job') value = job;
        else if (path === '/scripts/test') {
            samples.push(body.sampleContext);
            value = {
                nodeId: 'node', profileId: 'profile', requestId: body.requestId,
                sourceDigest: sha256(canonicalSpec(body)), registryEpoch: 'epoch', environmentRevision: 1,
                status: sampleFailure ? 'error' : 'success',
                output: sampleFailure ? null : { messages: [{ payloadKind: 'text', payload: logLines.join('\n'),
                    sourceMap: {}, destinationIds: [] }] },
                diagnostics: sampleFailure ? diagnostics : [], outcomeUncertain: sampleFailure,
                logs: logLines.map(message => ({ level: 'info', message }))
            };
        }
        else if (/^\/profiles\/[^/]+$/.test(path)) value = profiles.find(profile => profile.id === path.split('/')[2]);
        else value = {};
        await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ string: JSON.stringify(value) }) });
    });
    await page.goto(`http://127.0.0.1:${server.address().port}/?monaco`);
    await page.waitForFunction(() => window.ready);

    for (const kind of ['step', 'reader', 'writer']) {
        await mount(kind);
        assert.equal(await page.getByLabel('Invocation timeout (milliseconds)', { exact: true }).isVisible(), true);
        const starter = kind === 'step' ? '' : 'return None\n';
        assert.equal(await page.evaluate(() => window.lastEditor.getValue()), starter);
        assert.equal(await page.evaluate(() => window.subject.pythonScript), starter);
        await page.getByRole('button', { name: 'Test sample', exact: true }).click();
        assert.equal(await page.getByLabel('Synthetic sample context (JSON)', { exact: true }).inputValue(), '');
        const saved = `# Saved ${kind}\nprint("preserved")\n`;
        await mount(kind, { subject: { pythonScript: saved } });
        assert.equal(await page.evaluate(() => window.lastEditor.getValue()), saved);
        checks.push(`${kind}: new source uses its starter, sample is blank, saved source is preserved`);
    }
    await mount('template');
    assert.equal(await page.evaluate(() => window.lastEditor.getValue()), 'return arguments[0] if arguments else None\n');
    checks.push('Python Function keeps its starter');

    await settings(soleChoices);
    await page.getByRole('button', { name: 'Add environment', exact: true }).click();
    assert.equal(await page.getByRole('combobox', { name: 'Approved Python runtime', exact: true }).count(), 0);
    assert.equal(await page.getByRole('combobox', { name: 'Package source', exact: true }).count(), 0);
    assert.equal(await page.getByLabel('Requirements', { exact: true }).getAttribute('placeholder'), null);
    const soleSave = await saveName('Sole choices');
    assert.equal(soleSave.runtimeId, 'python'); assert.equal(soleSave.packageSourceId, 'approved');
    checks.push('new profile selects sole available runtime and package source without redundant selectors');

    await settings({ ...soleChoices, runtimes: [python, { id: 'offline', label: 'Offline', available: false }] });
    await page.getByRole('button', { name: 'Add environment', exact: true }).click();
    assert.equal(await page.getByLabel('Approved Python runtime', { exact: true }).inputValue(), 'python');
    await page.getByLabel('Approved Python runtime', { exact: true }).selectOption('offline');
    assert.equal((await saveName('Approved offline interpreter')).runtimeId, 'offline');
    checks.push('sole available runtime is preselected while other approved offline runtimes remain selectable');

    await settings({ nodeId: 'node', runtimes: [python, { ...python, id: 'other', label: 'Other interpreter' }],
        packageSources: [approved, { id: 'mirror', label: 'Internal mirror', kind: 'index' }, builtin] });
    await page.getByRole('button', { name: 'Add environment', exact: true }).click();
    assert.equal(await page.getByLabel('Approved Python runtime', { exact: true }).inputValue(), '');
    assert.equal(await page.getByLabel('Package source', { exact: true }).inputValue(), '');
    await page.getByLabel('Approved Python runtime', { exact: true }).selectOption('other');
    await page.getByLabel('Package source', { exact: true }).selectOption('mirror');
    const multiSave = await saveName('Chosen alternatives');
    assert.equal(multiSave.runtimeId, 'other'); assert.equal(multiSave.packageSourceId, 'mirror');
    checks.push('multiple choices require and preserve the selected IDs');

    await settings(soleChoices, [{ ...baseProfile, runtimeId: 'missing-runtime', packageSourceId: 'missing-source' }]);
    await page.getByRole('button', { name: baseProfile.name, exact: true }).click();
    assert.equal(await page.getByLabel('Approved Python runtime', { exact: true }).inputValue(), 'missing-runtime');
    assert.equal(await page.getByLabel('Package source', { exact: true }).inputValue(), 'missing-source');
    await page.getByLabel('Name', { exact: true }).fill('Keep unavailable choices');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: 'Your draft is preserved' }).waitFor();
    assert.equal(saves.at(-1).runtimeId, 'missing-runtime'); assert.equal(saves.at(-1).packageSourceId, 'missing-source');
    assert.equal(await page.getByLabel('Approved Python runtime', { exact: true }).inputValue(), 'missing-runtime');
    assert.equal(await page.getByLabel('Package source', { exact: true }).inputValue(), 'missing-source');
    assert.equal(await page.getByLabel('Name', { exact: true }).inputValue(), 'Keep unavailable choices');
    checks.push('saved unapproved IDs remain visible and the draft survives the engine-shaped rejection');

    for (const runtimes of [[], [{ id: 'offline', label: 'Offline', available: false }]]) {
        await settings({ nodeId: 'node', runtimes, packageSources: [] });
        await page.getByRole('button', { name: 'Add environment', exact: true }).click();
        await page.getByLabel('Name', { exact: true }).fill('Incomplete choices');
        const count = saves.length;
        await page.getByRole('button', { name: 'Save', exact: true }).click();
        await page.getByRole('alert').filter({ hasText: 'select an approved runtime and package source' }).waitFor();
        assert.equal(saves.length, count);
    }
    checks.push('absent or unavailable runtime choices cannot fabricate a new profile binding');

    runtimeConfig = structuredClone(soleChoices); profiles = [{ ...baseProfile }];
    const logScript = 'from oie_python import Message\n'
        + 'lines = [str(i) + ": " + "x" * 700 for i in range(70)]\n'
        + 'for line in lines:\n    logger.info(line)\n'
        + 'return Message("\\n".join(lines))\n';
    await mount('reader', { subject: { pythonScript: logScript } });
    await page.getByRole('button', { name: 'Test sample', exact: true }).click();
    await page.getByRole('button', { name: 'Run sample once', exact: true }).click();
    await page.getByText('Sample completed', { exact: true }).waitFor();
    assert.deepEqual(samples, [{}]);
    checks.push('blank sample context still dispatches an empty object for sample testing');
    await page.getByText('Script output', { exact: true }).click();
    for (const width of [1280, 390]) {
        await page.setViewportSize({ width, height: 900 });
        for (const label of ['Sample output', 'Script output']) await bounded(label, width);
        await page.screenshot({ path: resolve(art, `script-output-${width}.png`), fullPage: true });
    }
    checks.push('sample output and script logs scroll within the panel on desktop and mobile');

    sampleFailure = true;
    await mount('reader', { subject: { pythonScript: logScript.replace('return Message("\\n".join(lines))', 'raise ValueError("x" * 2800)') } });
    await page.getByRole('button', { name: 'Test sample', exact: true }).click();
    await page.getByRole('button', { name: 'Run sample once', exact: true }).click();
    await page.getByLabel('Python diagnostics', { exact: true }).waitFor();
    for (const width of [1280, 390]) {
        await page.setViewportSize({ width, height: 900 });
        await bounded('Python diagnostics', width);
        await page.screenshot({ path: resolve(art, `diagnostics-${width}.png`), fullPage: true });
    }
    checks.push('script error and traceback diagnostics scroll within the panel on desktop and mobile');

    await settings(soleChoices);
    await page.getByRole('button', { name: baseProfile.name, exact: true }).click();
    await page.getByRole('button', { name: 'Prepare saved revision', exact: true }).click();
    await page.getByText('View preparation output', { exact: true }).click();
    await page.getByLabel('Preparation output', { exact: true }).getByText(/70: Long output/).waitFor();
    for (const width of [1280, 390]) {
        await page.setViewportSize({ width, height: 900 });
        await bounded('Preparation output', width);
        await page.screenshot({ path: resolve(art, `preparation-output-${width}.png`), fullPage: true });
    }
    checks.push('failed preparation output remains bounded and scrollable on desktop and mobile');
    assert.deepEqual(errors, []);
    assert.deepEqual(await hashes(), before);
    await writeFile(resolve(art, 'browser-result.json'), JSON.stringify({
        status: 'passed', browser: await browser.version(), checks, measurements, saves, inputHashes: before
    }, null, 2) + '\n');
    console.log(`Python UI cleanup browser checks passed (${checks.length})`);
} catch (error) {
    if (page) {
        await page.screenshot({ path: resolve(art, 'failure.png'), fullPage: true });
        await writeFile(resolve(art, 'failure.html'), await page.content());
    }
    await writeFile(resolve(art, 'browser-result.json'), JSON.stringify({
        status: 'failed', message: error.message, stack: error.stack, checks, measurements, errors, inputHashes: before
    }, null, 2) + '\n');
    throw error;
} finally {
    if (browser) await browser.close();
    await new Promise(done => server.close(done));
    await writeFile(resolve(art, 'cleanup.json'), JSON.stringify({
        browserStarted: !!browser, browserClosed: true, serverClosed: true,
        engineCreated: false, databaseCreated: false, hostFilesModified: false
    }, null, 2) + '\n');
}
