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
const checks = [], errors = [], observations = {}, hostPythonRequests = [], knownLimits = [];
const requestsByPage = new WeakMap();
const isPluginGrammar = url => new URL(url).pathname === '/plugins/python-runtime/web/python-language.json';
let browser, releaseGrammar;
async function openPage() {
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    page.on('pageerror', error => errors.push(error.message));
    const requests = []; requestsByPage.set(page, requests);
    page.on('request', request => {
        requests.push(request.url());
        if (new URL(request.url()).pathname === '/vendor/monaco/python.js') hostPythonRequests.push(request.url());
    });
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
async function show(page, kind, options = {}, monaco = true) {
    await page.evaluate(({ kind, options }) => window.showPanel(kind, options), { kind, options });
    if (monaco) await page.waitForFunction(() => window.lastEditor?.monaco?.getModel()?.getLanguageId() === 'python');
    else await page.locator('.ce-area').waitFor();
}
async function setSource(page, value) {
    await page.evaluate(value => {
        const editor = window.lastEditor.monaco;
        editor.setValue(value);
        editor.setPosition(editor.getModel().getPositionAt(value.length));
        editor.focus();
    }, value);
}
async function source(page) { return page.evaluate(() => window.lastEditor.getValue()); }

try {
    await new Promise(done => server.listen(0, '127.0.0.1', done));
    browser = await chromium.launch({ headless: true, executablePath: process.env.OIE_BROWSER_EXECUTABLE });
    const page = await openPage();
    const grammarRequests = () => requestsByPage.get(page).filter(isPluginGrammar);
    assert.equal(grammarRequests().length, 0, 'Settings must not fetch the plugin grammar');
    assert.equal(await page.evaluate(() => window.platform.apiVersion), '4.10.0');
    await page.evaluate(async () => {
        const { createCodeEditor } = await import('/core/codeeditor.js');
        window.javascriptEditor = createCodeEditor({ value: 'var unchanged = true;', ariaLabel: 'JavaScript control' });
        document.body.appendChild(window.javascriptEditor.el);
    });
    await page.waitForFunction(() => window.javascriptEditor.monaco);
    assert.equal(grammarRequests().length, 0);
    assert.equal(await page.evaluate(() => window.monaco.languages.getLanguages().some(value => value.id === 'python')), false);
    const initialScope = await page.evaluate(async () => (await import('/core/script-completions.js')).activeScope());
    checks.push('settings and JavaScript do not load Python');

    const surfaces = [
        ['source transformer', 'step', 'source-transformer'],
        ['destination transformer', 'step', 'destination-transformer'],
        ['response transformer', 'step', 'response-transformer'],
        ['source filter', 'rule', 'source-filter'], ['destination filter', 'rule', 'destination-filter'],
        ['reader', 'reader'], ['writer', 'writer'], ['code template', 'template']
    ];
    for (const [label, kind, role] of surfaces) {
        await show(page, kind, kind === 'rule' ? {role} : {});
        if (role && kind !== 'rule') await page.getByLabel('Validation role', { exact: true }).selectOption(role);
        assert.deepEqual(await page.evaluate(() => {
            const editor = window.lastEditor.monaco;
            const options = editor.getModel().getOptions();
            return { language: editor.getModel().getLanguageId(), spaces: options.insertSpaces, tabSize: options.tabSize, detectIndentation: window.lastEditor.opts.detectIndentation,
                folding: editor.getOption(window.monaco.editor.EditorOption.folding),
                keyword: window.monaco.editor.tokenize('def helper():', 'python')[0].some(token => token.type === 'keyword.python') };
        }), { language: 'python', spaces: true, tabSize: 4, detectIndentation: false, folding: true, keyword: true });
        await setSource(page, 'def helper():');
        observations[`${label} before Enter`] = await page.evaluate(() => ({
            focused: window.lastEditor.monaco.hasTextFocus(),
            activeElement: document.activeElement?.className,
            position: window.lastEditor.monaco.getPosition(),
            source: window.lastEditor.getValue(), subject: window.subject.pythonScript
        }));
        await page.keyboard.press('Enter');
        observations[`${label} after Enter`] = await page.evaluate(() => ({
            focused: window.lastEditor.monaco.hasTextFocus(),
            activeElement: document.activeElement?.className,
            position: window.lastEditor.monaco.getPosition(),
            source: window.lastEditor.getValue(), subject: window.subject.pythonScript
        }));
        assert.equal(await source(page), 'def helper():\n    ', `${label} Enter auto-indents`);
        await page.keyboard.press('Tab');
        assert.equal(await source(page), 'def helper():\n        ', `${label} Tab uses spaces`);
        await page.keyboard.press('Shift+Tab');
        await page.keyboard.insertText('return 1');
        const expected = 'def helper():\n    return 1';
        assert.equal(await source(page), expected);
        assert.equal(await page.evaluate(() => window.subject.pythonScript), expected);
        await page.evaluate(() => window.updatePanelSubject({ ...window.subject, pythonScript: '# external draft\n' }));
        await page.waitForFunction(() => window.lastEditor.getValue() === '# external draft\n');
        await page.evaluate(() => { window.previousModel = window.lastEditor.monaco.getModel(); window.showPanel('settings'); });
        await page.waitForFunction(() => window.previousModel.isDisposed());
        checks.push(`${label}: Python grammar, Enter/Tab/Shift+Tab, draft sync and unmount disposal`);
    }
    assert.equal(grammarRequests().length, 1);
    assert.deepEqual(await page.evaluate(async () => (await import('/core/script-completions.js')).activeScope()), initialScope);
    assert.deepEqual(await page.evaluate(() => ({
        language: window.javascriptEditor.monaco.getModel().getLanguageId(), value: window.javascriptEditor.getValue()
    })), { language: 'javascript', value: 'var unchanged = true;' });
    checks.push('one plugin grammar fetch preserves JavaScript language, source and completion scope; host has no Python asset');

    await show(page, 'template', { readOnly: true });
    const readOnlySource = await source(page);
    await page.evaluate(() => window.lastEditor.focus());
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');
    await page.keyboard.insertText('forbidden');
    assert.equal(await source(page), readOnlySource);
    assert.equal(await page.evaluate(() => window.lastEditor.monaco.getOption(window.monaco.editor.EditorOption.readOnly)), true);
    await show(page, 'template');
    await setSource(page, 'channelM');
    await page.evaluate(() => window.lastEditor.monaco.trigger('test', 'editor.action.triggerSuggest', {}));
    await page.waitForTimeout(150);
    assert.equal(await page.locator('.suggest-widget.visible .monaco-list-row').filter({ hasText: 'channelMap' }).count(), 0);
    await page.keyboard.press('Escape');
    await setSource(page, 'return arguments[0]\n');
    await page.screenshot({ path: resolve(art, 'python-editors-desktop.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    await page.screenshot({ path: resolve(art, 'python-editors-mobile.png'), fullPage: true });
    checks.push('read-only template blocks editing and remount restores editable Monaco; desktop/mobile layout');

    const fallback = await openPage();
    await fallback.route('**/plugins/python-runtime/web/python-language.json*', route => route.abort());
    await show(fallback, 'reader', {}, false);
    await fallback.locator('.ce-area').fill('def helper():\n');
    await fallback.locator('.ce-area').press('End');
    await fallback.locator('.ce-area').press('Tab');
    assert.equal(await fallback.locator('.ce-area').inputValue(), 'def helper():\n    ');
    await fallback.locator('.ce-area').press('Shift+Tab');
    assert.equal(await fallback.locator('.ce-area').inputValue(), 'def helper():\n');
    await fallback.evaluate(async () => { await (await import('/core/monaco.js')).ensureMonaco('python'); });
    assert.equal(await fallback.evaluate(() => !!window.lastEditor.monaco), false);
    await fallback.unroute('**/plugins/python-runtime/web/python-language.json*');
    await show(fallback, 'writer');
    assert.equal(await fallback.evaluate(() => window.lastEditor.monaco.getModel().getLanguageId()), 'python');
    checks.push('failed Python grammar keeps four-space fallback usable; next editor retries successfully');

    const delayed = await openPage();
    const gate = new Promise(done => { releaseGrammar = done; });
    await delayed.route('**/plugins/python-runtime/web/python-language.json*', async route => { await gate; await route.continue(); });
    await show(delayed, 'step', {}, false);
    await delayed.locator('.ce-area').fill('msg = "draft"');
    await delayed.locator('.ce-area').evaluate(area => { area.focus(); area.setSelectionRange(4, 13, 'backward'); });
    observations.beforeDelayedUpgrade = await delayed.locator('.ce-area').evaluate(area => ({
        focused: area === document.activeElement, selectionStart: area.selectionStart, selectionEnd: area.selectionEnd
    }));
    releaseGrammar(); releaseGrammar = null;
    await delayed.waitForFunction(() => window.lastEditor?.monaco?.getModel()?.getLanguageId() === 'python');
    assert.equal(await source(delayed), 'msg = "draft"');
    assert.equal(await delayed.evaluate(() => window.subject.pythonScript), 'msg = "draft"');
    observations.afterDelayedUpgrade = await delayed.evaluate(() => ({
        focused: window.lastEditor.monaco.hasTextFocus(), selection: window.lastEditor.monaco.getSelection()
    }));
    const restoredSelection = {
        anchorLine: observations.afterDelayedUpgrade.selection.selectionStartLineNumber,
        anchorColumn: observations.afterDelayedUpgrade.selection.selectionStartColumn,
        activeLine: observations.afterDelayedUpgrade.selection.positionLineNumber,
        activeColumn: observations.afterDelayedUpgrade.selection.positionColumn
    };
    if (!observations.afterDelayedUpgrade.focused) {
        // Shipped Web Administrator 1.2.0 only preserves focus for literalInput
        // editors. The plugin's normal Python editors retain their draft but
        // lose focus/cursor on delayed upgrade. Record that precise host limit;
        // do not silently qualify the unpublished local host fix instead.
        assert.deepEqual(restoredSelection, { anchorLine: 1, anchorColumn: 1, activeLine: 1, activeColumn: 1 });
        knownLimits.push('Web Administrator 1.2.0: delayed Python editor upgrade preserves the draft but resets focus and selection; click the editor to continue.');
        await delayed.evaluate(() => {
            window.lastEditor.monaco.setSelection({ selectionStartLineNumber: 1, selectionStartColumn: 14,
                positionLineNumber: 1, positionColumn: 5 });
            window.lastEditor.monaco.focus();
        });
    } else {
        assert.deepEqual(restoredSelection, { anchorLine: 1, anchorColumn: 14, activeLine: 1, activeColumn: 5 });
    }
    await delayed.keyboard.insertText('continued');
    assert.equal(await source(delayed), 'msg continued');
    assert.equal(await delayed.evaluate(() => window.subject.pythonScript), 'msg continued');
    await delayed.keyboard.press('ControlOrMeta+z');
    assert.equal(await source(delayed), 'msg = "draft"');
    checks.push('delayed Python grammar preserves draft, continued typing and undo; host focus/selection behavior is recorded');

    const disposing = await openPage();
    const disposeGate = new Promise(done => { releaseGrammar = done; });
    await disposing.route('**/plugins/python-runtime/web/python-language.json*', async route => { await disposeGate; await route.continue(); });
    await show(disposing, 'template', {}, false);
    await disposing.evaluate(() => { window.removedEditor = window.lastEditor; window.showPanel('settings'); });
    releaseGrammar(); releaseGrammar = null;
    await disposing.evaluate(async () => { await (await import('/core/monaco.js')).ensureMonaco('python'); });
    assert.equal(await disposing.evaluate(() => !!window.removedEditor.monaco), false);
    assert.equal(await disposing.locator('.ce-monaco').count(), 0);
    checks.push('unmount during grammar load does not create an orphan Monaco editor');

    const permission = await openPage();
    const permissionGate = new Promise(done => { releaseGrammar = done; });
    await permission.route('**/plugins/python-runtime/web/python-language.json*', async route => { await permissionGate; await route.continue(); });
    await show(permission, 'template', {}, false);
    await permission.locator('.ce-area').fill('return "preserved"');
    await permission.evaluate(() => {
        window.removedEditor = window.lastEditor;
        window.showPanel('template', { readOnly: true, subject: { ...window.subject } });
    });
    releaseGrammar(); releaseGrammar = null;
    await permission.waitForFunction(() => window.lastEditor?.monaco?.getModel()?.getLanguageId() === 'python');
    assert.equal(await permission.evaluate(() => !!window.removedEditor.monaco), false);
    assert.equal(await source(permission), 'return "preserved"');
    assert.equal(await permission.evaluate(() => window.lastEditor.monaco.getOption(window.monaco.editor.EditorOption.readOnly)), true);
    assert.equal(await permission.locator('.ce-monaco').count(), 1);
    checks.push('read-only transition during grammar load preserves the draft and disposes the editable instance');

    assert.deepEqual(hostPythonRequests, [], 'No browser may request Python grammar from the host');
    assert.deepEqual(errors, []);
    const after = await hashes();
    assert.deepEqual(after, before, 'Host and plugin inputs must remain unchanged during verification');
    await writeFile(resolve(art, 'browser-result.json'), JSON.stringify({
        status: 'passed', browser: await browser.version(), checks, errors, observations, knownLimits, inputHashes: before, pluginGrammarRequests: grammarRequests(), hostPythonRequests,
        monaco: JSON.parse(await readFile(resolve(runtime, 'vendor/monaco/provenance.json'), 'utf8'))
    }, null, 2) + '\n');
    console.log(`Python editor browser checks passed (${checks.length})`);
} catch (error) {
    const failure = { status: 'failed', message: error.message, stack: error.stack, checks, errors, observations, inputHashes: before };
    await writeFile(resolve(art, 'failure.json'), JSON.stringify(failure, null, 2) + '\n');
    await writeFile(resolve(art, 'browser-result.json'), JSON.stringify(failure, null, 2) + '\n');
    throw error;
} finally {
    releaseGrammar?.();
    if (browser) await browser.close();
    await new Promise(done => server.close(done));
    await writeFile(resolve(art, 'cleanup.json'), JSON.stringify({
        browserStarted: !!browser, browserClosed: true, serverClosed: true,
        isolatedEngineCreated: false, isolatedDatabaseCreated: false,
        hostFilesModified: false
    }, null, 2) + '\n');
}
