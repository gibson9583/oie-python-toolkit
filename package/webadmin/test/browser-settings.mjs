/* SPDX-License-Identifier: MPL-2.0 */
import {build} from 'esbuild';
import {readFile,writeFile,mkdir,readdir} from 'node:fs/promises';
import {createServer} from 'node:http';
import {resolve,dirname} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';

const here=dirname(fileURLToPath(import.meta.url)),repo=resolve(here,'../../..'),host=resolve(process.env.OIE_WEB_HOST||resolve(repo,'../oie-web-client')),art=resolve(process.env.OIE_WEB_ART||resolve(repo,'validation/stock-admin')),runtime=resolve(process.env.OIE_WEB_RUNTIME||resolve(host,'web-administrator/client'));
await mkdir(art,{recursive:true});const {chromium}=await import(pathToFileURL(resolve(host,'node_modules/playwright/index.mjs')).href);
await build({entryPoints:[resolve(here,'browser-harness.jsx')],outfile:resolve(art,'browser-harness.js'),bundle:true,format:'esm',target:'es2022',external:['@oie/web-shell','/toolkit.js','/core/codeeditor.js'],alias:{react:resolve(host,'node_modules/react'),'react-dom':resolve(host,'node_modules/react-dom')}});
const assets=process.env.OIE_WEB_RUNTIME?'assets':'dist/assets';
const css=(await readdir(resolve(runtime,assets))).find(v=>v.endsWith('.css'));
const html=`<!doctype html><html><head><script type="importmap">{"imports":{"@oie/web-shell":"/core/pkg-shell.js","@oie/web-api":"/core/pkg-api.js","@oie/web-ui":"/core/pkg-ui.js","js-beautify":"/vendor/js-beautify.js"}}</script><link rel="stylesheet" href="/${assets}/${css}"></head><body><main id="app"></main><script type="module" src="/harness.js"></script></body></html>`;
const server=createServer(async(req,res)=>{try{const p=new URL(req.url,'http://local').pathname;if(p==='/'){res.setHeader('content-type','text/html');res.end(html);return;}const file=p==='/toolkit.js'?resolve(here,'../web/plugin.js'):p==='/plugins/python-runtime/web/python-language.json'?resolve(here,'../web/python-language.json'):p==='/harness.js'?resolve(art,'browser-harness.js'):resolve(runtime,p.slice(1));res.setHeader('content-type',file.endsWith('.css')?'text/css':file.endsWith('.json')?'application/json':'text/javascript');res.end(await readFile(file));}catch(error){res.statusCode=404;res.end(error.message);}});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const browser=await chromium.launch({headless:true,executablePath:process.env.OIE_BROWSER_EXECUTABLE}),page=await browser.newPage(),errors=[];page.on('pageerror',error=>errors.push(error.message));
const base={id:'profile',revision:1,registryEpoch:'epoch',name:'Original',description:'',runtimeId:'python',packageSourceId:'approved',requirementsText:'',readiness:{state:'ready',nodeId:'node'}},second={...base,id:'second',name:'Second'};
let profile={...base},blockLoads=false,loads=[],prepareBodies=[],refreshBodies=[],importBodies=[],exportRelease,downloads=0,refreshUnknown=false,refreshReceipts=new Map(),saveReplay=false;
const fulfill=(route,value,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(status>=400?value:{string:JSON.stringify(value)})});
async function wait(predicate){for(let i=0;i<150;i++){if(predicate())return;await page.waitForTimeout(20);}assert.ok(predicate(),'Fixture request did not reach expected stage.');}
await page.route('**/api/**',async route=>{
    const request=route.request(),p=new URL(request.url()).pathname.replace('/api/extensions/python-runtime',''),body=request.postData()?JSON.parse(request.postData()):null;
    if(p==='/profiles'&&request.method()==='GET'){const snapshot={...profile};if(blockLoads)await new Promise(resolve=>loads.push(resolve));return fulfill(route,{registryEpoch:'epoch',nodeId:'node',profiles:[snapshot,second]});}
    if(p==='/runtime')return fulfill(route,{nodeId:'node',runtimes:[{id:'python',label:'Python',available:true}],packageSources:[{id:'approved',label:'Approved',kind:'index'}]});
    if(p.endsWith('/references'))return fulfill(route,{profileId:p.split('/')[2],nodeId:'node',saved:[],deployed:[],hiddenCount:0});
    if(p==='/profiles/profile'&&request.method()==='PUT'){profile={...profile,...body,revision:profile.revision+1};if(saveReplay)profile={...profile,name:'Changed by another author',revision:profile.revision+1};blockLoads=true;return fulfill(route,{requestId:body.requestId,registryEpoch:'epoch',profile});}
    if(p==='/profiles/profile/prepare'){prepareBodies.push(body);return fulfill(route,{message:'Preparation offline'},503);}
    if(p==='/profiles/profile/refresh'){refreshBodies.push(body);if(!refreshReceipts.has(body.requestId)){profile={...profile,revision:profile.revision+1,refreshGeneration:(profile.refreshGeneration||0)+1};refreshReceipts.set(body.requestId,{...profile});}if(refreshUnknown){refreshUnknown=false;return fulfill(route,{message:'Refresh reply lost'},503);}return fulfill(route,{requestId:body.requestId,registryEpoch:'epoch',profile:refreshReceipts.get(body.requestId)});}
    if(p==='/profiles/import'){importBodies.push(body);if(importBodies.length===1)return fulfill(route,{});if(importBodies.length===2)return route.abort('connectionreset');return fulfill(route,{requestId:body.requestId,registryEpoch:'epoch',registryRevision:2,mappings:{imported:'created'},created:['created'],updated:[],unchanged:[],conflicts:[]});}
    if(p==='/profiles/export'){await new Promise(resolve=>exportRelease=resolve);return fulfill(route,{formatVersion:1,profiles:[]});}
    if(p==='/profiles/profile')return fulfill(route,profile);
    return fulfill(route,{});
});
async function mount(){blockLoads=false;loads.splice(0).forEach(resolve=>resolve());await page.evaluate(()=>{window.platform.store.setState('serverId','node');window.showPanel('settings');});await page.getByRole('button',{name:profile.name,exact:true}).click();await page.getByLabel('Name',{exact:true}).waitFor();}
function releaseLoads(){blockLoads=false;loads.splice(0).forEach(resolve=>resolve());}
async function switchAwayBack(){await page.evaluate(()=>{window.platform.store.setState('serverId','other');window.platform.store.setState('serverId','node');});}
const checks=[];
try{
    await page.goto(`http://127.0.0.1:${server.address().port}`);await page.waitForFunction(()=>window.ready);
    await mount();blockLoads=true;profile={...profile,name:'Older list read'};await page.getByRole('button',{name:'Refresh list',exact:true}).click();await wait(()=>loads.length===1);profile={...profile,name:'Newer list read'};await page.getByRole('button',{name:'Refresh list',exact:true}).click();await wait(()=>loads.length===2);blockLoads=false;const older=loads.shift(),newer=loads.shift();newer();await page.getByRole('button',{name:'Newer list read',exact:true}).waitFor();older();await page.waitForTimeout(100);assert.equal(await page.getByRole('button',{name:'Older list read',exact:true}).count(),0);checks.push('older late list read cannot overwrite newest refresh');
    for(const action of ['prepare','choose']){
        await mount();await page.getByLabel('Name',{exact:true}).fill('Saved '+action);
        await page.getByRole('button',{name:action==='prepare'?'Save and Prepare':'Second',exact:true}).click();await wait(()=>loads.length>0);await switchAwayBack();releaseLoads();await page.waitForTimeout(120);
        assert.equal(prepareBodies.length,0);await page.getByRole('button',{name:'Refresh list',exact:true}).click();await page.getByLabel('Name',{exact:true}).waitFor();assert.equal(await page.getByLabel('Name',{exact:true}).inputValue(),'Saved '+action);checks.push('late Save reload does not '+action);
    }
    await mount();await page.getByRole('button',{name:'Refresh dependencies',exact:true}).click();await page.getByText(/Refreshed revision .* preparation is unavailable/).waitFor();const refreshedRevision=profile.revision;assert.equal(refreshBodies.length,1);assert.equal(prepareBodies.at(-1).expectedRevision,refreshedRevision);
    await page.getByRole('button',{name:'Prepare saved revision',exact:true}).click();await wait(()=>prepareBodies.length===2);assert.equal(refreshBodies.length,1);assert.equal(prepareBodies.at(-1).expectedRevision,refreshedRevision);checks.push('confirmed Refresh revision survives failed Prepare and retry');
    refreshUnknown=true;await page.getByRole('button',{name:'Refresh dependencies',exact:true}).click();await page.getByText('Refresh reply lost',{exact:false}).waitFor();const unknownBody=JSON.stringify(refreshBodies.at(-1));await page.getByRole('button',{name:'Refresh dependencies',exact:true}).click();await wait(()=>refreshBodies.length===3);assert.equal(JSON.stringify(refreshBodies.at(-1)),unknownBody);await page.getByText(/Refreshed revision .* preparation is unavailable/).waitFor();checks.push('unknown Refresh replays exact body and request identity');
    const [picker]=await Promise.all([page.waitForEvent('filechooser'),page.getByRole('button',{name:'Import',exact:true}).click()]);await picker.setFiles({name:'owned-import.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify({formatVersion:1,profiles:[{id:'imported',name:'Imported'}]}))});await page.getByLabel(/Imported/).selectOption('new');
    for(let attempt=1;attempt<=3;attempt++){await page.getByRole('button',{name:'Apply import',exact:true}).click();await wait(()=>importBodies.length===attempt);if(attempt<3)await page.getByText(/Import choices are preserved/).waitFor();else await page.getByRole('button',{name:'Apply import',exact:true}).waitFor({state:'detached'});}
    assert.equal(JSON.stringify(importBodies[0]),JSON.stringify(importBodies[1]));assert.equal(JSON.stringify(importBodies[1]),JSON.stringify(importBodies[2]));checks.push('malformed and lost Import receipts preserve mapping choices and exact retry body');
    page.on('download',()=>downloads++);await page.getByRole('button',{name:'Export',exact:true}).click();await wait(()=>!!exportRelease);await switchAwayBack();exportRelease();await page.waitForTimeout(120);assert.equal(downloads,0);checks.push('late Export does not download across session change');
    await mount();saveReplay=true;await page.getByLabel('Name',{exact:true}).fill('Old draft');await page.getByRole('button',{name:'Save and Prepare',exact:true}).click();await wait(()=>loads.length>0);releaseLoads();await page.getByText(/Save was confirmed, but this environment changed again/).waitFor();assert.equal(await page.getByLabel('Name',{exact:true}).inputValue(),'Changed by another author');assert.equal(prepareBodies.length,3);checks.push('replayed Save rehydrates newer profile and requires review before Prepare');
    assert.deepEqual(await page.evaluate(()=>window.pluginAssetPaths),[], 'Settings-only actions never fetch Python grammar');checks.push('settings-only actions never fetch plugin-owned Python grammar');
    assert.deepEqual(errors,[]);await writeFile(resolve(art,'browser-settings-result.json'),JSON.stringify({status:'passed',checks,browser:await browser.version(),backend:'Fixture API, actual built web administrator'},null,2)+'\n');console.log('Settings browser action-boundary regression passed');
}catch(error){await writeFile(resolve(art,'browser-settings-failure.html'),await page.content());console.log(await page.locator('main').innerText());throw error;}
finally{releaseLoads();exportRelease?.();await browser.close();await new Promise(resolve=>server.close(resolve));}
