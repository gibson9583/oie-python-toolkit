/* SPDX-License-Identifier: MPL-2.0 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {defaults, validateRuntime, testRuntime, createGeneration} from '../web/ui-model.mjs';
import {selectedStepPath} from '../web/step-placement.mjs';

test('Validate and Test reject missing or nested placement for every transformer role', async () => {
    const api = {get() { throw new Error('No API read is allowed.'); }};
    for (const role of ['source-transformer', 'destination-transformer', 'response-transformer']) {
        for (const elementPath of [[], [0, 1], [2, 0, 3]]) {
            const context = {role, elementPath}, subject = {...defaults(), pythonScript: 'pass\n'};
            const result = await validateRuntime(api, subject, context);
            assert.equal(result.status, 'invalid');
            assert.equal(result.diagnostics[0].code, elementPath.length ? 'UNSUPPORTED_ITERATOR' : 'STEP_CONTEXT_UNAVAILABLE');
            await assert.rejects(testRuntime(api, subject, context, {}), /Select this Python step|Iterator/);
        }
    }
});

test('live selection stays within its owning editor and changes action identity', () => {
    let firstPath = '0.1';
    const first = {closest: () => ({querySelector: () => ({getAttribute: () => firstPath})})};
    const second = {closest: () => ({querySelector: () => ({getAttribute: () => '2'})})};
    assert.deepEqual(selectedStepPath(first), [0, 1]);
    assert.deepEqual(selectedStepPath(second), [2]);
    const generation = createGeneration(() => ({elementPath: selectedStepPath(first)}));
    const action = generation.begin();
    firstPath = '1';
    assert.equal(action.isCurrent(), false);
    assert.deepEqual(selectedStepPath(first), [1]);
    for (const invalid of [null, '', '0.bad', '-1', '1.']) {
        firstPath = invalid;
        assert.deepEqual(selectedStepPath(first), []);
    }
    assert.deepEqual(selectedStepPath({closest: () => null}), []);
});
