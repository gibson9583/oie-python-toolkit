/* SPDX-License-Identifier: MPL-2.0 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { canonicalSpec,sha256,generateScript,createGeneration,validateRuntime,testRuntime,structuralMessage,defaults,decode,request,assertProfileView,assertJobView,assertImportResult,createMutationReceipt } from '../web/ui-model.mjs';
import { createSessionFence, guardApi } from '../web/ui-session.mjs';
import { stepWrapper } from '../web/step-wrapper.mjs';
const pythonScript='pass\n';
test('empty scripts are valid no-ops while missing source remains invalid',()=>{
    for(const source of ['', ' \n\t', 'print("hi")\n']) assert.equal(structuralMessage({...defaults(),pythonScript:source}), '');
    for(const source of [null,undefined,42]) assert.match(structuralMessage({...defaults(),pythonScript:source}), /must be text/);
});
const fixtures=JSON.parse(readFileSync(new URL('../../../shared/src/test/resources/golden/spec-digests.json',import.meta.url)));
for(const fixture of fixtures)test(`golden spec ${fixture.name}`,()=>{
    assert.equal(canonicalSpec(fixture),fixture.canonical);assert.equal(sha256(fixture.canonical),fixture.digest);
    assert.match(generateScript(fixture),new RegExp(`executeStep\\('${fixture.digest}'`));assert.ok(!generateScript(fixture).includes(fixture.pythonScript));
});
test('hash matches cryptographic reference at multibyte and padding boundaries',()=>{
    for(const n of [0,1,55,56,63,64,65,127,1000]){const value='😀é'.repeat(n);assert.equal(sha256(value),createHash('sha256').update(value).digest('hex'));}
});
test('late actions cannot overwrite source, environment, engine, route, sample or epoch',()=>{
    for(const key of ['source','profile','engine','route','sample','epoch']){const state={source:'a',profile:'p',engine:'e',route:'/r',sample:'{}',epoch:'x'};const gen=createGeneration(()=>state);const action=gen.begin();state[key]+='changed';assert.equal(action.isCurrent(),false);}
    const gen=createGeneration(()=>({source:'a'}));const first=gen.begin();assert.ok(first.isCurrent());const second=gen.begin();assert.equal(first.signal.aborted,true);assert.equal(first.isCurrent(),false);assert.ok(second.isCurrent());gen.invalidate();assert.equal(second.isCurrent(),false);
});
test('standard library draft can save unresolved reference while validation rejects it',async()=>{
    const subject={...defaults(),pythonScript,environmentProfileId:''};assert.equal(structuralMessage(subject),'');const result=await validateRuntime({},subject,{role:'source-transformer',elementPath:[0]});assert.equal(result.status,'invalid');assert.match(result.diagnostics[0].message,/Select/);
});
test('unsupported iterator fails before API activity',async()=>{
    const result=await validateRuntime({}, {...defaults(),pythonScript,environmentProfileId:'p'},{role:'response-transformer',elementPath:[1,0]});assert.equal(result.status,'invalid');assert.equal(result.diagnostics[0].code,'UNSUPPORTED_ITERATOR');
});
test('network errors and malformed responses never validate',async()=>{
    const subject={...defaults(),pythonScript,environmentProfileId:'p'};const ctx={role:'source-transformer',elementPath:[0]};
    const offline={get:async()=>{throw new Error('offline');}};assert.equal((await validateRuntime(offline,subject,ctx)).status,'unavailable');
    for(const invalid of [null,'bad','{}','{"status":"valid","diagnostics":[]}']){const client={get:async path=>JSON.stringify(path.endsWith('/runtime')?{nodeId:'node'}:{id:'p',revision:1,registryEpoch:'epoch'}),post:async()=>invalid};assert.equal((await validateRuntime(client,subject,ctx)).status,'unavailable');}
});
test('runtime action pins current epoch and revision and rejects mismatching replies',async()=>{
    const subject={...defaults(),pythonScript,environmentProfileId:'p'};const ctx={role:'source-transformer',elementPath:[0],signal:new AbortController().signal};let posted;
    const client={get:async path=>JSON.stringify(path.endsWith('/runtime')?{nodeId:'node'}:{id:'p',revision:1,registryEpoch:'epoch'}),post:async(_path,wrapped,opts)=>{posted=JSON.parse(wrapped);assert.equal(opts.signal,ctx.signal);return JSON.stringify({nodeId:'node',requestId:posted.requestId,sourceDigest:sha256(canonicalSpec(subject)),registryEpoch:'epoch',environmentRevision:1,status:'valid',diagnostics:[]});}};
    assert.equal((await validateRuntime(client,subject,ctx)).status,'valid');assert.equal(posted.expectedEnvironmentRevision,1);assert.equal(posted.expectedRegistryEpoch,'epoch');
    client.post=async()=>JSON.stringify({requestId:posted.requestId,registryEpoch:'old',environmentRevision:1,status:'valid',diagnostics:[]});assert.equal((await validateRuntime(client,subject,ctx)).status,'unavailable');
});
test('String REST documents are sent as raw JSON text and decoded once',async()=>{const client={post:async(path,body)=>{assert.equal(path,'/extensions/python-runtime/profiles');assert.equal(body,'{"name":"profile"}');return '{"requestId":"x"}';}};assert.deepEqual(await request(client,'post','/profiles',{name:'profile'}),{requestId:'x'});assert.throws(()=>decode('[]'));});

test('malformed mutation profiles and preparation jobs fail before draft or readiness publication',()=>{for(const value of [null,{}, {id:'profile',revision:0,registryEpoch:'epoch'},{id:'profile',revision:1}])assert.throws(()=>assertProfileView(value));for(const value of [null,{}, {id:'j',nodeId:'node',registryEpoch:'epoch',profileId:'profile',profileRevision:1,state:'unknown'}])assert.throws(()=>assertJobView(value));});

test('web generated bridge matches the entire shared Java wrapper including RAW and response status conversion',()=>{
    const source=readFileSync(new URL('../../../shared/src/main/java/com/mirth/connect/plugins/python/PythonContract.java',import.meta.url),'utf8');
    const expression=source.match(/public static String stepWrapper[\s\S]*?return ([\s\S]*?);\n\s*}/)?.[1];assert.ok(expression,'Shared Java wrapper expression is present');
    const tokens=[...expression.matchAll(/"(?:\\.|[^"\\])*"|specDigest/g)].map(match=>match[0]);
    assert.match(expression.replace(/"(?:\\.|[^"\\])*"|specDigest/g,''),/^[\s+]*$/,'Parity extractor accepts only literal string concatenation');
    const digest='0123456789abcdef'.repeat(4),expected=tokens.map(token=>token==='specDigest'?digest:JSON.parse(token)).join('');assert.equal(stepWrapper(digest),expected);
});

test('cancel, source edit or session change between Test reads prevents unsent execution on stock API',async()=>{
    for(const stage of ['runtime','profile'])for(const change of ['cancel','source','session']){
        let release,readStarted;const started=new Promise(resolve=>readStarted=resolve),blocked=new Promise(resolve=>release=resolve);
        let serverId='node',listener;const platform={store:{getState:()=>serverId,subscribe:(_,fn)=>{listener=fn;return()=>{};}}};const target=new EventTarget();target.document={cookie:'oie-engine=node; oie-login=one'};const session=createSessionFence(platform,target),currentSession=session.capture();
        const state={subject:{...defaults(),pythonScript,environmentProfileId:'profile'}};const generation=createGeneration(()=>state),action=generation.begin();let posts=0;
        const api={get:async path=>{const runtime=path.endsWith('/runtime');if((runtime?'runtime':'profile')===stage){readStarted();await blocked;}return JSON.stringify(runtime?{nodeId:'node'}:{id:'profile',revision:1,registryEpoch:'epoch'});},post:async()=>{posts++;throw new Error('Execution must remain unsent.');}};
        const result=testRuntime(guardApi(api,()=>action.isCurrent()&&currentSession()),action.snapshot.subject,{role:'source-transformer',elementPath:[0]},{});
        await started;if(change==='cancel')generation.invalidate();else if(change==='source')state.subject.pythonScript+='\n';else{serverId='other';listener();serverId='node';listener();}release();
        await assert.rejects(result,/inputs or browser session changed/);assert.equal(posts,0,stage+' '+change);session.close();
    }
});

test('a Test already sent is not retried or published after cancellation',async()=>{
    let release,executionStarted;const started=new Promise(resolve=>executionStarted=resolve),blocked=new Promise(resolve=>release=resolve);let current=true,posts=0;
    const subject={...defaults(),pythonScript,environmentProfileId:'profile'},api={get:async path=>JSON.stringify(path.endsWith('/runtime')?{nodeId:'node'}:{id:'profile',revision:1,registryEpoch:'epoch'}),post:async(_path,wrapped)=>{posts++;executionStarted();await blocked;const body=JSON.parse(wrapped);return JSON.stringify({nodeId:'node',requestId:body.requestId,sourceDigest:sha256(canonicalSpec(subject)),registryEpoch:'epoch',environmentRevision:1,status:'success',diagnostics:[]});}};
    const result=testRuntime(guardApi(api,()=>current),subject,{role:'source-transformer',elementPath:[0]},{});await started;current=false;release();await assert.rejects(result,/inputs or browser session changed/);assert.equal(posts,1);
});
test('lost or malformed Test execution reply is uncertain while malformed prerequisites never execute',async()=>{
    const subject={...defaults(),pythonScript,environmentProfileId:'profile'};let posts=0;
    const get=async path=>JSON.stringify(path.endsWith('/runtime')?{nodeId:'node'}:{id:'profile',revision:1,registryEpoch:'epoch'});
    for(const post of [async()=>{throw new Error('reply lost');},async()=>'{"status":"success"}'])await assert.rejects(testRuntime({get,post:async(...args)=>{posts++;return post(...args);}},subject,{role:'source-transformer',elementPath:[0]},{}),error=>error.outcomeUncertain===true&&/Review the target before retrying/.test(error.message));
    assert.equal(posts,2);
    for(const profile of [{},{id:'foreign',revision:1,registryEpoch:'epoch'}]){let count=0;await assert.rejects(testRuntime({get:async path=>JSON.stringify(path.endsWith('/runtime')?{nodeId:'node'}:profile),post:async()=>{count++;}},subject,{role:'source-transformer',elementPath:[0]},{}));assert.equal(count,0);}
});
test('uncertain Import retains exact receipt and validates mapped target identities',()=>{
    const receipt=createMutationReceipt(),choices={expectedRegistryEpoch:'epoch',bundle:{profiles:[{id:'imported'}]},mappings:{imported:'new'},expectedRevisions:{}};
    const body=receipt.capture(choices),serialized=JSON.stringify(body);assert.throws(()=>assertImportResult({},body));assert.equal(JSON.stringify(receipt.capture(choices)),serialized);
    const response={requestId:body.requestId,registryEpoch:'epoch',registryRevision:2,mappings:{imported:'created'},created:['created'],updated:[],unchanged:[],conflicts:[]};assert.equal(assertImportResult(response,body),response);
    for(const key of ['requestId','registryEpoch'])assert.throws(()=>assertImportResult({...response,[key]:'foreign'},body));
    assert.throws(()=>assertImportResult({...response,mappings:{}},body));const explicit=receipt.capture({...choices,mappings:{imported:'target'}});assert.throws(()=>assertImportResult({...response,requestId:explicit.requestId},explicit));
    receipt.clear();assert.notEqual(receipt.capture(choices).requestId,body.requestId);
});
test('Refresh receipts distinguish equal revision profiles and preserve unknown first mutation',()=>{
    const receipt=createMutationReceipt(),value={expectedRegistryEpoch:'epoch',expectedRevision:1};const first=receipt.capture(value,'profileA');assert.equal(receipt.capture(value,'profileA'),first);assert.notEqual(receipt.capture(value,'profileB').requestId,first.requestId);
});
test('verified Test success requires selected profile identity and an output document',async()=>{
    const subject={...defaults(),pythonScript,environmentProfileId:'profile'};let patch={},posts=0;
    const api={get:async path=>JSON.stringify(path.endsWith('/runtime')?{nodeId:'node'}:{id:'profile',revision:1,registryEpoch:'epoch'}),post:async(_path,wrapped)=>{posts++;const body=JSON.parse(wrapped);return JSON.stringify({requestId:body.requestId,profileId:'profile',nodeId:'node',registryEpoch:'epoch',environmentRevision:1,sourceDigest:sha256(canonicalSpec(subject)),status:'success',diagnostics:[],output:{marker:'sample'},...patch});}};
    assert.equal((await testRuntime(api,subject,{role:'source-transformer',elementPath:[0]},{})).output.marker,'sample');
    for(patch of [{profileId:'foreign'},{output:null},{output:[]}])await assert.rejects(testRuntime(api,subject,{role:'source-transformer',elementPath:[0]},{}),error=>error.outcomeUncertain===true);
    assert.equal(posts,4);
});
test('all String DTO write endpoints send raw JSON text through stock API, not a string-root object',async()=>{
    const operations=[['post','/profiles'],['put','/profiles/profile'],['post','/profiles/profile/prepare'],['post','/profiles/profile/refresh'],['post','/profiles/import'],['post','/scripts/validate'],['post','/scripts/test']];
    const body={requestId:'receipt',name:'API-fed environment',expectedRegistryEpoch:'epoch',expectedRevision:1,requirementsText:''};let calls=0;
    const write=async(path,text)=>{calls++;assert.ok(path.startsWith('/extensions/python-runtime/'));assert.equal(typeof text,'string');assert.equal(text,JSON.stringify(body));assert.deepEqual(JSON.parse(text),body);assert.equal(JSON.parse(text).string,undefined);return JSON.stringify({requestId:'receipt',status:'accepted'});};
    for(const [method,path]of operations)assert.deepEqual(await request({post:write,put:write},method,path,body),{requestId:'receipt',status:'accepted'});assert.equal(calls,operations.length);
    await request({post:async(_path,value)=>{assert.equal(value,undefined);return {status:'accepted'};}},'post','/jobs/id/cancel');
});
