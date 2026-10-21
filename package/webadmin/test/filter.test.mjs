/* SPDX-License-Identifier: MPL-2.0 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { RULE, defaults, routeValidationContext, generateScript } from '../web/ui-model.mjs';
import { placementError } from '../web/step-placement.mjs';

test('source and destination filter routes use dedicated roles', () => {
    assert.deepEqual(routeValidationContext('/channels/id/filter/0'), {role:'source-filter',connectorMetadataId:0});
    assert.deepEqual(routeValidationContext('/channels/id/filter/3'), {role:'destination-filter',connectorMetadataId:3});
    assert.equal(routeValidationContext('/channels/id/response/3').role,'response-transformer');
});
test('native filter preview returns exact predicate; iterator rules rejected', () => {
    const script = generateScript({__type:RULE,...defaults(),pythonScript:'return True'});
    assert.match(script,/^return \(function/);
    assert.match(script,/executeFilter\(/);
    assert.match(script,/return __r.isAccepted\(\);/);
    assert.equal(placementError({role:'source-filter',elementPath:[0]}),'');
    assert.match(placementError({role:'source-filter',elementPath:[0,0]}),/Iterator rules/);
    assert.match(placementError({role:'destination-filter',elementPath:[]}),/Select this Python rule/);
});

test('new Reader and Writer defaults use native returns; transformer defaults remain statements', () => {
    assert.equal(defaults('reader').pythonScript, 'return None\n');
    assert.equal(defaults('writer').pythonScript, 'return None\n');
    assert.equal(defaults().pythonScript, '');
});
