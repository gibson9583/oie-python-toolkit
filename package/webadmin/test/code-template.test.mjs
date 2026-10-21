/* SPDX-License-Identifier: MPL-2.0 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { TEMPLATE_CLASS, TEMPLATE_CONTEXTS, templateDefaults, templateContexts, templateMessage, templateCompletionCode, canonicalTemplateSpec, validateTemplateRuntime } from '../web/code-template-model.mjs';
import { sha256 } from '../web/ui-model.mjs';
import { guardApi } from '../web/ui-session.mjs';

const subject = () => ({ ...templateDefaults({ version: '4.6.0', template: { name: 'Normalize patient' } }), environmentProfileId: 'profile' });
const profile = { id: 'profile', registryEpoch: 'epoch', revision: 2 };
function fixture(overrides = {}) {
    const calls = [];
    return { calls, api: {
        async get(path) { calls.push(path); return { ...path.endsWith('/runtime') ? { nodeId: 'node' } : profile, ...overrides.profile }; },
        async post(path, raw) {
            const body = JSON.parse(raw); calls.push({ path, body });
            return JSON.stringify({ nodeId: 'node', requestId: body.requestId, registryEpoch: 'epoch', environmentRevision: 2,
                sourceDigest: sha256(canonicalTemplateSpec(body)), status: 'valid', diagnostics: [], ...overrides.result });
        }
    } };
}

test('Python templates serialize a plugin class and function behavior without storing generated JavaScript', () => {
    const value = subject();
    assert.equal(value['@class'], TEMPLATE_CLASS); assert.equal(value['@version'], '4.6.0');
    assert.equal(value.type, 'FUNCTION'); assert.equal(value.functionName, 'Normalize_patient');
    assert.equal(value.pythonScript, 'return arguments[0] if arguments else None\n');
    assert.equal(Object.hasOwn(value, 'code'), false);
    assert.deepEqual(templateContexts({ contextSet: { delegate: { contextType: TEMPLATE_CONTEXTS[0] } } }), [TEMPLATE_CONTEXTS[0]]);
    assert.deepEqual(templateContexts({ contextSet: { delegate: { contextType: TEMPLATE_CONTEXTS } } }), TEMPLATE_CONTEXTS);
});

test('template guards reject invalid names and unsupported imported contexts without rewriting source', () => {
    const value = subject(), original = structuredClone(value);
    for (const functionName of ['', 'if', 'arguments', 'eval', 'has spaces', 'a(); throw 1;', 'JSON', 'Array', 'Object', 'Packages', 'isFinite', 'String', 'Error', 'connectorMessage', 'responseStatus']) assert.match(templateMessage({ ...value, functionName }, TEMPLATE_CONTEXTS), /Function name/);
    assert.equal(templateDefaults({ template: { name: 'JSON' } }).functionName, 'python_function');
    assert.equal(templateMessage({ ...value, functionName: '$normalize_1' }, TEMPLATE_CONTEXTS), '');
    assert.match(templateMessage(value, ['GLOBAL_DEPLOY']), /transformer contexts only/);
    assert.match(templateMessage(value, []), /at least one/);
    assert.match(templateMessage({ ...value, type: 'COMPILED_CODE' }, TEMPLATE_CONTEXTS), /Function behavior/);
    assert.deepEqual(value, original);
});

test('template spec includes the function name and role and normalizes source without selectors', () => {
    const value = { ...subject(), pythonScript: '# Result\r\nreturn None\r' };
    const spec = JSON.parse(canonicalTemplateSpec(value));
    assert.deepEqual(Object.keys(spec), ['canonicalization', 'contractVersion', 'functionName', 'kind', 'profileId', 'source', 'timeoutMillis']);
    assert.equal(spec.kind, 'code-template'); assert.equal(spec.source, '# Result\nreturn None\n');
    assert.equal(Object.hasOwn(spec, 'contextInputs'), false);
    assert.notEqual(sha256(canonicalTemplateSpec(value)), sha256(canonicalTemplateSpec({ ...value, functionName: 'other' })));
});

test('JavaScript completions describe only the callable wrapper and never include Python source', () => {
    const value = subject();
    const code = templateCompletionCode(value);
    assert.match(code, /function Normalize_patient\(\.\.\.args\) \{\}/);
    assert.match(code, /@param \{\.\.\.\(null\|boolean\|number\|string\|Array<\*>\|Object<string, \*>\)\} args/);
    assert.match(code, /@returns \{null\|boolean\|number\|string\|Array<\*>\|Object<string, \*>\}/);
    assert.equal(code.includes('@typedef'), false, 'template libraries must not introduce duplicate global type aliases');
    assert.equal(templateCompletionCode(value).includes(value.pythonScript), false);
    assert.equal(templateCompletionCode({ ...value, functionName: 'a(); throw 1' }), null);
    assert.equal(templateCompletionCode({ ...value, functionName: 'JSON' }), null);
});

test('validation sends a template-specific request to the selected node and verifies its receipt', async () => {
    const { api, calls } = fixture(), value = subject();
    const result = await validateTemplateRuntime(api, value, { contextTypes: TEMPLATE_CONTEXTS });
    assert.equal(result.status, 'valid');
    assert.equal(calls[2].path, '/extensions/oie-python-toolkit/templates/validate');
    assert.equal(calls[2].body.role, 'code-template'); assert.equal(calls[2].body.functionName, value.functionName);
    assert.equal(calls[2].body.expectedNodeId, 'node'); assert.equal(calls[2].body.expectedEnvironmentRevision, 2);
    assert.deepEqual(calls[2].body.contextTypes, TEMPLATE_CONTEXTS); assert.equal(Object.hasOwn(calls[2].body, 'channelId'), false);
});

test('local errors and stale prerequisites never reach Python validation', async () => {
    const local = fixture();
    assert.equal((await validateTemplateRuntime(local.api, subject(), { contextTypes: ['GLOBAL_DEPLOY'] })).status, 'invalid');
    assert.deepEqual(local.calls, []);
    const { api, calls } = fixture(); let current = true;
    const get = api.get; api.get = async path => { const value = await get(path); current = false; return value; };
    const result = await validateTemplateRuntime(guardApi(api, () => current), subject(), { contextTypes: TEMPLATE_CONTEXTS });
    assert.equal(result.status, 'unavailable'); assert.equal(calls.length, 1);
});

test('validation rejects wrong-node, wrong-source, stale revision, and wrong-profile responses', async () => {
    for (const result of [{ nodeId: 'other' }, { sourceDigest: 'other' }, { environmentRevision: 3 }, { registryEpoch: 'other' }]) {
        assert.equal((await validateTemplateRuntime(fixture({ result }).api, subject(), { contextTypes: TEMPLATE_CONTEXTS })).status, 'unavailable');
    }
    const { api, calls } = fixture({ profile: { id: 'other' } });
    assert.equal((await validateTemplateRuntime(api, subject(), { contextTypes: TEMPLATE_CONTEXTS })).status, 'unavailable');
    assert.equal(calls.length, 2);
});
