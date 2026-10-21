/* SPDX-License-Identifier: MPL-2.0 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {DEFAULT_PROFILE_ID,DEFAULT_PROFILE_NAME,defaults,validateRuntime,testRuntime} from '../web/ui-model.mjs';
import {defaultProfile,automaticEnvironment,selectedEnvironment,environmentState} from '../web/default-environment.mjs';
const profile=()=>({id:DEFAULT_PROFILE_ID,name:DEFAULT_PROFILE_NAME,registryEpoch:'epoch',revision:1,builtInDefault:true,archived:false,readiness:{state:'ready',pythonVersion:'3.14.6'}});
const listing=()=>({defaultProfileId:DEFAULT_PROFILE_ID,profiles:[{id:'other',name:'Other'},profile()]});
test('new scripts and connectors share the stable vanilla binding and role starter',()=>{
    const java=readFileSync(new URL('../../../shared/src/main/java/com/mirth/connect/plugins/python/PythonContract.java',import.meta.url),'utf8');
    assert.ok(java.includes(`DEFAULT_PROFILE_ID = "${DEFAULT_PROFILE_ID}"`));assert.ok(java.includes(`DEFAULT_PROFILE_NAME = "${DEFAULT_PROFILE_NAME}"`));
    for(const role of ['source-transformer','destination-transformer','response-transformer','reader','writer']){const value=defaults(role);assert.equal(value.pythonScript,role==='reader'||role==='writer'?'return None\n':'');assert.deepEqual([value.environmentProfileId,value.environmentNameHint],[DEFAULT_PROFILE_ID,DEFAULT_PROFILE_NAME]);}
});
test('only an authoritative owned default fills a currently unconfigured binding',()=>{
    for(const id of [null,undefined,''])assert.deepEqual(automaticEnvironment({environmentProfileId:id},listing()),{id:DEFAULT_PROFILE_ID,name:DEFAULT_PROFILE_NAME});
    for(const id of ['other','missing',' ',DEFAULT_PROFILE_ID])assert.equal(automaticEnvironment({environmentProfileId:id},listing()),null);
    for(const list of [null,{...listing(),defaultProfileId:null},{...listing(),defaultProfileId:'other'},{...listing(),profiles:[{...profile(),builtInDefault:false}]},{...listing(),profiles:[{...profile(),archived:true}]},{...listing(),profiles:[profile(),profile()]},{...listing(),profiles:[{...profile(),revision:0}]}])assert.equal(defaultProfile(list),null);
});
test('reserved UUID collisions remain unavailable without losing the saved reference',()=>{
    const subject=defaults(),list={defaultProfileId:null,profiles:[{...profile(),name:'Existing custom definition',builtInDefault:false}]};
    assert.equal(selectedEnvironment(subject,list),null);assert.equal(subject.environmentProfileId,DEFAULT_PROFILE_ID);assert.equal(subject.environmentNameHint,DEFAULT_PROFILE_NAME);
    assert.equal(selectedEnvironment({environmentProfileId:'other'},listing()).name,'Other');assert.equal(selectedEnvironment({environmentProfileId:'missing'},listing()),null);
});
test('default readiness shows the exact reported patch version without claiming readiness',()=>{
    assert.equal(environmentState(profile()),'CPython 3.14.6 · ready');assert.equal(environmentState({...profile(),readiness:{state:'preparing',pythonVersion:'3.14.6'}}),'CPython 3.14.6 · preparing');assert.equal(environmentState({...profile(),readiness:{state:'runtime-unavailable'}}),'runtime unavailable');
});
test('missing authoritative default or unowned reserved UUID prevents Validate and Test POSTs',async()=>{
    for(const [defaultProfileId,builtInDefault]of [[null,true],[DEFAULT_PROFILE_ID,false]]){
        const subject={...defaults(),pythonScript:'pass\n'};let posts=0;const api={get:async path=>JSON.stringify(path.endsWith('/runtime')?{nodeId:'node',defaultProfileId}:{...profile(),builtInDefault}),post:async()=>{posts++;throw new Error('Must remain unsent');}};
        assert.equal((await validateRuntime(api,subject,{role:'source-transformer',elementPath:[0]})).status,'unavailable');await assert.rejects(testRuntime(api,subject,{role:'source-transformer',elementPath:[0]},{}),/built-in Python environment/);assert.equal(posts,0);
    }
});
