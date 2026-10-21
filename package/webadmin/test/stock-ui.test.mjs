/* SPDX-License-Identifier: MPL-2.0 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { routeValidationContext, normalizeConnectorPluginProperties, runtimeLabel } from '../web/ui-model.mjs';
import { createSessionFence } from '../web/ui-session.mjs';
test('plugin session fence rejects engine away/back, cookie changes and logout event on stock API',()=>{
    let serverId='A',listener;const platform={store:{getState:()=>serverId,subscribe:(_,fn)=>{listener=fn;return()=>listener=null;}}};const target=new EventTarget();target.document={cookie:'oie-engine=A; oie-login=one'};const fence=createSessionFence(platform,target);
    const first=fence.capture();assert.equal(first(),true);serverId='B';listener();serverId='A';listener();assert.equal(first(),false);
    const second=fence.capture();target.document.cookie='oie-engine=A; oie-login=two';assert.equal(second(),false);
    const third=fence.capture();target.dispatchEvent(new Event('oie-session-changed'));assert.equal(third(),false);
    const fourth=fence.capture();fence.close();assert.equal(fourth(),false);assert.equal(listener,null);
});

test('stock route context identifies standalone editors and leaves embedded role explicit',()=>{
    assert.deepEqual(routeValidationContext('/channels/id/transformer/0'),{role:'source-transformer',connectorMetadataId:0});
    assert.deepEqual(routeValidationContext('/channels/id/transformer/3'),{role:'destination-transformer',connectorMetadataId:3});
    assert.deepEqual(routeValidationContext('/channels/id/response/3'),{role:'response-transformer',connectorMetadataId:3});
    assert.deepEqual(routeValidationContext('/channels/id/edit'),{role:'',connectorMetadataId:null});
});

test('Python connector empty plugin-property sets remain typed without discarding configured entries',()=>{
    for(const pluginProperties of [undefined,null]){
        const common={queueEnabled:true},properties={pluginProperties,destinationConnectorProperties:common,pythonScript:'pass'};
        assert.equal(normalizeConnectorPluginProperties(properties),properties);
        assert.deepEqual(properties.pluginProperties,{'@class':'set'});assert.equal(properties.destinationConnectorProperties,common);assert.equal(properties.pythonScript,'pass');
        const empty=properties.pluginProperties;normalizeConnectorPluginProperties(properties);assert.equal(properties.pluginProperties,empty);
    }
    const entries={'@class':'set','example.CertificateProperties':{'@version':'4.6.0',alias:'kept',enabled:true}},before=structuredClone(entries),properties={pluginProperties:entries};
    normalizeConnectorPluginProperties(properties);assert.equal(properties.pluginProperties,entries);assert.deepEqual(entries,before);
});

test('runtime labels show the exact probed CPython version and honest availability',()=>{
    assert.equal(runtimeLabel({id:'system-python3',label:'System python3',available:true,version:'3.14.6'}),'System python3 · CPython 3.14.6');
    assert.equal(runtimeLabel({id:'system-python3',label:'System python3',available:false}),'System python3 (unavailable)');
    assert.equal(runtimeLabel(null),'Python runtime unavailable');
});
