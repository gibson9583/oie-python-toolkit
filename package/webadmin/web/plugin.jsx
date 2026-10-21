/* SPDX-License-Identifier: MPL-2.0 */
import { platform } from '@oie/web-shell';
import * as api from '@oie/web-api';
import { defaultPollProperties, defaultSourceProperties, defaultDestinationProperties, PollSection } from '@oie/web-ui';
import { RULE, STEP, DEFAULT_PROFILE_ID, defaults, decode, structuralMessage, generateScript, validateRuntime, testRuntime, request, profileToken, createGeneration, terminalJobs, assertProfileView, assertJobView, assertImportResult, createMutationReceipt, routeValidationContext, normalizeConnectorPluginProperties, runtimeLabel } from './ui-model.mjs';
import { createSessionFence, guardApi } from './ui-session.mjs';
import { PythonEditor } from './python-editor.jsx';
import { EnvironmentChoice } from './environment-choice.jsx';
import { OutputPanel } from './output-panel.jsx';
import { newEnvironment } from './ui-model.mjs';
import { registerPythonEditorLanguage } from './python-language.mjs';
import { registerPythonCodeTemplate } from './code-template-panel.jsx';
import { defaultProfile, automaticEnvironment, selectedEnvironment, environmentState } from './default-environment.mjs';
import { stepEditor, selectedStepPath } from './step-placement.mjs';
const React = platform.React;
const { useState, useRef, useEffect, useLayoutEffect } = React;
const can = name => platform.checkTask('extension',name);
function useSessionFence(){const value=useRef();useLayoutEffect(()=>{value.current=createSessionFence(platform);return()=>{value.current.close();value.current=null;};},[]);return value;}
const permissions = { view:'getPythonEnvironments', create:'createPythonEnvironment', manage:'updatePythonEnvironment', archive:'archivePythonEnvironment', prepare:'preparePythonEnvironment', refresh:'refreshPythonEnvironment', cancel:'cancelPythonPreparationJob', import:'importPythonEnvironments', export:'exportPythonEnvironments', validate:'validatePythonScript', test:'testPythonScript' };
const stateLabel = environmentState;
const errorText = e => { try { const value=decode(e.body),details=(value.diagnostics||[]).map(d=>[d.message,d.suggestedAction].filter(Boolean).join(' '));return [value.message||e.message,...details].filter(Boolean).join('\n'); } catch { return e.message || String(e); } };
function Result({ value }) {
    if(!value)return null;
    return <div role="status" aria-live="polite" className="mt-3"><strong>{value.status==='valid'?'Python script validated':value.status==='success'?'Sample completed':value.status==='running'?'Checking Python script…':'Python diagnostics'}</strong>
        {value.diagnostics?.length>0&&<OutputPanel label="Python diagnostics" preformatted={false}>
            {value.diagnostics.map((d,i)=><p key={i}>{d.line?`Line ${d.line}: `:''}{d.message}{d.suggestedAction?` ${d.suggestedAction}`:''}</p>)}
        </OutputPanel>}
        {value.output&&<OutputPanel label="Sample output">{JSON.stringify(value.output,null,2)}</OutputPanel>}
        {value.outcomeUncertain&&<p>The script may have completed external work. Review the target before retrying.</p>}
        {value.logs?.length>0&&<details><summary>Script output{value.logsTruncated?' (truncated)':''}</summary><OutputPanel label="Script output">{value.logs.map(v=>v.message||JSON.stringify(v)).join('\n')}</OutputPanel></details>}
    </div>;
}
function ScriptPanel({ element, properties, onChange, role: explicitRole, channel, connector }) {
    const subject=element||properties;const path=platform.router.currentPath();
    const panel=useRef();
    useLayoutEffect(()=>{if(properties)normalizeConnectorPluginProperties(properties);},[properties]);
    const inferred=routeValidationContext(path),isStep=!!element,isRule=subject.__type===RULE;const [chosenRole,setChosenRole]=useState(explicitRole||inferred.role);const [chosenConnector,setChosenConnector]=useState(inferred.connectorMetadataId??'');const role=explicitRole||chosenRole;
    const activeChannel=channel||platform.store.getState('editingChannel');const destinations=platform.oie.destinationsOf(activeChannel||{});
    const [profiles,setProfiles]=useState(null);const [error,setError]=useState('');const [,bump]=useState(0);const [result,setResult]=useState(null);const [showTest,setShowTest]=useState(false);const [testBusy,setTestBusy]=useState(false);const pendingTest=useRef(false);
    const [sample,setSample]=useState('');
    const reader=useRef();reader.current=()=>({subject,role,sample,context:context(),engineId:platform.store.getState('serverId'),route:platform.router.currentPath(),epoch:profiles?.registryEpoch});
    const fence=useSessionFence();
    const generation=useRef();if(!generation.current)generation.current=createGeneration(()=>reader.current());
    useLayoutEffect(() => {
        if (!isStep) return;
        const editor = stepEditor(panel.current);
        if (!editor) return;
        let previous = JSON.stringify(selectedStepPath(panel.current));
        const observer = new MutationObserver(() => {
            const current = JSON.stringify(selectedStepPath(panel.current));
            if (current !== previous) {
                previous = current;
                generation.current.invalidate();
                setResult(null);
            }
        });
        observer.observe(editor, {subtree:true, childList:true, attributes:true, attributeFilter:['class','data-path']});
        return () => observer.disconnect();
    }, [subject]);
    useEffect(()=>{let mounted=true,ticket=0,timer;function loadProfiles(refresh=false){clearTimeout(timer);const own=++ticket,currentSession=fence.current.capture(),current=()=>mounted&&own===ticket&&currentSession()&&reader.current().subject===subject;if(!refresh){setProfiles(null);setError('');}request(guardApi(api,current),'get','/profiles').then(value=>{if(!Array.isArray(value.profiles)||value.profiles.some(p=>!p||typeof p.id!=='string'||!p.id))throw new Error('Malformed environment list.');if(current()){setProfiles(value);setError('');const choice=automaticEnvironment(subject,value);if(choice){subject.environmentProfileId=choice.id;subject.environmentNameHint=choice.name;generation.current.invalidate();setResult(null);bump(v=>v+1);onChange();}const active=selectedEnvironment(subject,value);if(active?.builtInDefault&&['preparing','not-prepared'].includes(active.readiness?.state))timer=setTimeout(()=>{if(current())loadProfiles(true);},1500);}}).catch(error=>{if(current())setError(errorText(error));});}loadProfiles();const unsubscribe=platform.store.subscribe('serverId',()=>{generation.current.invalidate();setResult(null);loadProfiles();});return()=>{mounted=false;ticket++;clearTimeout(timer);unsubscribe();generation.current.invalidate();};},[subject]);
    const selected=selectedEnvironment(subject,profiles);
    function context() {
        const currentRoute = routeValidationContext(platform.router.currentPath());
        const actionRole = isStep && currentRoute.role ? currentRoute.role : role;
        return {
            role: actionRole,
            channelId: activeChannel?.id,
            connectorMetadataId: connector?.metaDataId ?? (isStep && currentRoute.role ? currentRoute.connectorMetadataId : actionRole === 'reader' || actionRole === 'source-transformer' || actionRole === 'source-filter'
                ? 0 : chosenConnector === '' ? null : Number(chosenConnector)),
            elementPath: isStep ? selectedStepPath(panel.current) : [],
            engineId: String(platform.store.getState('serverId') || '')
        };
    }
    function change(key,value){generation.current.invalidate();subject[key]=value;setResult(null);bump(v=>v+1);onChange();}
    async function run(test){
        if(test&&pendingTest.current)return;
        if(!role||(isStep&&role!=='source-transformer'&&role!=='source-filter'&&context().connectorMetadataId==null)){setResult({status:'invalid',diagnostics:[{message:'Select the validation role and connector for this embedded Python step.'}]});return;}
        if(!can(permissions[test?'test':'validate'])){setResult({status:'unavailable',diagnostics:[{message:'Python script permission is required.'}]});return;}
        const action=generation.current.begin(),currentSession=fence.current.capture(),target=guardApi(api,()=>action.isCurrent()&&currentSession());if(test){pendingTest.current=true;setTestBusy(true);}setResult({status:'running'});let value;
        if(!test)value=await validateRuntime(target,action.snapshot.subject,{...action.snapshot.context,signal:action.signal});
        else try{value=await testRuntime(target,action.snapshot.subject,{...action.snapshot.context,signal:action.signal},JSON.parse(action.snapshot.sample.trim()||'{}'));}catch(e){value={status:'unavailable',outcomeUncertain:!!e.outcomeUncertain,diagnostics:[{message:errorText(e)}]};}
        if(test){pendingTest.current=false;setTestBusy(false);}if(action.isCurrent()&&currentSession())setResult(value);
    }
    return <div ref={panel} className="flex flex-col gap-4">
        <div className="flex flex-wrap items-end gap-3"><label className="field" style={{flex:"1 1 220px",minWidth:0}}>Script environment<select style={{width:"100%",minWidth:0}} value={subject.environmentProfileId||''} disabled={!profiles} onChange={e=>{subject.environmentNameHint=profiles.profiles.find(p=>p.id===e.target.value)?.name||'';change('environmentProfileId',e.target.value);}}><option value="">{profiles?'Select a script environment':'Loading environments…'}</option>{subject.environmentProfileId&&!selected&&<option value={subject.environmentProfileId}>Unresolved: {subject.environmentNameHint||subject.environmentProfileId}</option>}{profiles?.profiles.filter(p=>!p.archived&&(p.id!==DEFAULT_PROFILE_ID||p===selectedEnvironment({environmentProfileId:DEFAULT_PROFILE_ID},profiles))).map(p=><option key={p.id} value={p.id}>{p.name}</option>)}</select></label><button type="button" className="btn" onClick={()=>{platform.store.setState('pythonReturnPath',path);platform.router.navigate('/settings?tab=Script%20Environments');}}>Manage environments</button></div>
        {error&&<p role="alert">{error} Your script draft is preserved.</p>}{selected&&<div><strong>{selected.name}</strong><p>{stateLabel(selected)}{selected.readiness?.message?`: ${selected.readiness.message}`:''}</p>{selected.builtInDefault?<p>Built-in standard library. Prepares automatically with the installed approved Python; no requirements to configure.</p>:<p>{selected.description}</p>}{selected.redeployNeeded&&<p>Redeploy consuming channels to activate this environment revision.</p>}</div>}
        {subject.environmentProfileId&&!selected&&profiles&&<p role="alert">{subject.environmentProfileId===DEFAULT_PROFILE_ID?(profiles.defaultProfileMessage||'The built-in Python environment is unavailable on this engine. Open Script Environments for details.'):'This environment is unresolved. Restore its profile or select another before deploying.'}</p>}
        {isStep&&!inferred.role&&!isRule&&<div className="flex flex-wrap gap-3"><label className="field">Validation role<select aria-label="Validation role" value={role} onChange={e=>{generation.current.invalidate();setChosenRole(e.target.value);setResult(null);}}><option value="">Select where this step runs</option><option value="source-transformer">Source transformer</option><option value="destination-transformer">Destination transformer</option><option value="response-transformer">Response transformer</option></select></label>{role&&role!=='source-transformer'&&role!=='source-filter'&&<label className="field">Validation connector<select aria-label="Validation connector" value={chosenConnector} onChange={e=>{generation.current.invalidate();setChosenConnector(e.target.value);setResult(null);}}><option value="">Select a destination</option>{destinations.map(v=><option key={v.metaDataId} value={v.metaDataId}>{v.name||`Destination ${v.metaDataId}`}</option>)}</select></label>}</div>}
        <PythonEditor value={subject.pythonScript||''} onChange={v=>change('pythonScript',v)} role={role} channelId={activeChannel?.id} profile={selected} catalog={profiles}/>
        <p className="text-text-dim" style={{maxWidth:"72ch"}}>
            {isRule ? <>Assign <code>result</code> to exactly <code>True</code> or <code>False</code>. Use <code>not</code> to negate a predicate. Stock AND/OR order and short circuit apply. Documents and maps match transformer steps. Python rules must be outside Iterator rules.</> : isStep ? <>
                Write Python statements using <code>msg</code> and, when a template exists, <code>tmp</code> directly.
                {' '}XML is engine internal XML; replaced roots can invalidate earlier JavaScript aliases.
                {' '}Python steps must be outside Iterator steps.
            </> : role==='writer' ? <>
                Use <code>payload</code> directly and assign <code>result</code> to None, a string, a Status, or a Response.
                {' '}Engine retries apply. Use target-supported idempotency for external writes.
            </> : <>
                Use <code>poll</code> for polling metadata and assign <code>result</code> to None, a Message, or a list of Message objects.
                {' '}External acknowledgments are the script’s responsibility.
            </>}
            {' '}<code>logger</code> and <code>print()</code> write captured script output.
            {' '}Use <code>globalMap</code>, <code>globalChannelMap</code>, and <code>configurationMap</code> directly.
        </p>
        <details><summary>Advanced</summary><label className="field mt-3">Invocation timeout (milliseconds)<input type="number" min="100" step="1" value={subject.timeoutMillis} onChange={e=>change('timeoutMillis',Number(e.target.value))}/></label></details>
        <p className="text-text-dim">Use Validate Python for Python syntax and environment checks. Standard host validation does not run these checks.</p>
        <div className="flex flex-wrap gap-2"><button type="button" className="btn" disabled={!can(permissions.validate)} onClick={()=>run(false)}>Validate Python</button><button type="button" className="btn" disabled={!can(permissions.test)} onClick={()=>setShowTest(!showTest)}>Test sample</button><button type="button" className="btn" onClick={()=>{generation.current.invalidate();setResult({status:'cancelled',diagnostics:[{message:'Result cancelled. External work may still complete.'}]});}}>Cancel result</button></div>
        {showTest&&<div><label className="field">Synthetic sample context (JSON)<textarea className="font-mono" rows="8" value={sample} onChange={e=>{generation.current.invalidate();setSample(e.target.value);setResult(null);}}/></label><p>{isStep?'Transformer tests require message data in this JSON context.':'Leave empty to send an empty context.'} Test can perform external I/O. It does not admit engine messages or modify live maps.</p><button type="button" className="btn" disabled={!can(permissions.test)||testBusy} onClick={()=>run(true)}>Run sample once</button></div>}
        {isStep&&<details><summary>Generated bridge script</summary><OutputPanel label="Generated bridge script">{generateScript(subject)}</OutputPanel></details>}
        <Result value={result}/>
    </div>;
}
function EnvironmentSettings({setSave,markDirty,markClean,setTasks}) {
    const [list,setList]=useState(null);const [runtime,setRuntime]=useState(null);const [draft,setDraft]=useState(null);const [error,setError]=useState('');const [busy,setBusy]=useState(false);const [ready,setReady]=useState(false);const [refs,setRefs]=useState(null);const [job,setJob]=useState(null);const [logs,setLogs]=useState([]);const [bundle,setBundle]=useState(null);const [mappings,setMappings]=useState({});
    const fence=useSessionFence();
    const live=useRef(true);const latest=useRef();latest.current={list,draft,busy};const saver=useRef();const pending=useRef();const dirty=useRef(false);const generation=useRef(0);const saving=useRef(false);const loadTicket=useRef(0),importReceipt=useRef(),refreshReceipt=useRef();if(!importReceipt.current)importReceipt.current=createMutationReceipt();if(!refreshReceipt.current)refreshReceipt.current=createMutationReceipt();
    useLayoutEffect(()=>{live.current=true;setSave(()=>saver.current());setReady(true);return()=>{live.current=false;generation.current++;setSave(null);};},[]);
    useEffect(()=>{setTasks('Script Environments',[]);load();const unsubscribe=platform.store.subscribe('serverId',()=>{generation.current++;setList(null);setRuntime(null);setJob(null);setLogs([]);setRefs(null);setError('Engine changed. Refresh environments before editing.');});return unsubscribe;},[]);
    function capture(){const own=generation.current,engine=platform.store.getState("serverId"),session=fence.current.capture();return()=>{try{return session()&&live.current&&own===generation.current&&engine===platform.store.getState("serverId");}catch{return false;}};}
    async function load(){const ticket=++loadTicket.current,session=capture(),current=()=>ticket===loadTicket.current&&session();try{const [a,b]=await Promise.all([request(api,'get','/profiles'),request(api,'get','/runtime')]);if(!Array.isArray(a.profiles)||!Array.isArray(b.runtimes)||!Array.isArray(b.packageSources))throw new Error('Malformed Python runtime configuration.');if(current()){setList(a);latest.current.list=a;setRuntime(b);if(!dirty.current&&latest.current.draft?.builtInDefault){const updated=defaultProfile(a);const next=updated||{...latest.current.draft,readiness:{state:'runtime-unavailable',message:a.defaultProfileMessage||'The built-in Python environment is unavailable. Refresh after resolving the engine configuration.'}};if(!updated)generation.current++;setDraft(next);latest.current.draft=next;}setError('');}}catch(e){if(current())setError(errorText(e));}}
    useEffect(()=>{if(!draft?.builtInDefault||!['preparing','not-prepared'].includes(draft.readiness?.state)||busy)return;const current=capture(),timer=setTimeout(()=>{if(current())load();},1500);return()=>clearTimeout(timer);},[draft,busy]);
    function change(key,value){if(draft?.builtInDefault)return;generation.current++;setDraft({...draft,[key]:value});pending.current=null;dirty.current=true;markDirty();setError('');}
    async function choose(p){if(saving.current)return;const before=capture();if(dirty.current&&!await save())return;if(!before())return;generation.current++;setDraft({...p});setRefs(null);setJob(null);setLogs([]);pending.current=null;const current=capture();if(p.id)try{const value=await request(guardApi(api,current),'get',`/profiles/${encodeURIComponent(p.id)}/references`);if(value.profileId!==p.id||value.nodeId!==runtime?.nodeId||!Array.isArray(value.saved)||!Array.isArray(value.deployed))throw new Error('Usage response does not match the selected environment.');if(current())setRefs(value);}catch(e){if(current())setError(errorText(e));}}
    async function save(){const state=latest.current;const p=state.draft;if(!p||p.builtInDefault)return true;if(state.busy||saving.current)return false;if(!state.list){setError("Refresh environments before saving this draft.");return false;}if(!can(p.id?permissions.manage:permissions.create)){setError('Permission to manage Python environments is required.');return false;}
        if(!p.name?.trim()||!p.runtimeId||!p.packageSourceId){setError('Enter a name and select an approved runtime and package source.');return false;}
        const key=JSON.stringify(p);const body=pending.current?.key===key?pending.current.body:{...profileToken(p),expectedRegistryEpoch:p.registryEpoch||state.list.registryEpoch,name:p.name,description:p.description||'',runtimeId:p.runtimeId,packageSourceId:p.packageSourceId,requirementsText:p.requirementsText||''};pending.current={key,body};const current=capture();saving.current=true;setBusy(true);
        try{const result=await request(guardApi(api,current),p.id?'put':'post',p.id?`/profiles/${encodeURIComponent(p.id)}`:'/profiles',body);if(result.requestId!==body.requestId||!assertProfileView(result.profile)||result.registryEpoch!==body.expectedRegistryEpoch||result.profile.registryEpoch!==body.expectedRegistryEpoch||p.id&&result.profile.id!==p.id)throw new Error('Malformed save response. Retry Save to reconcile the same operation.');if(!current())return false;if(JSON.stringify(latest.current.draft)!==key){setError('The environment was saved, but this draft changed. Save this draft again.');return false;}setDraft(result.profile);latest.current.draft=result.profile;dirty.current=false;pending.current=null;markClean();const exact=result.profile.revision===(p.id?Number(body.expectedRevision)+1:1);await load();if(current()&&!exact)setError("Save was confirmed, but this environment changed again. Review its saved revision before continuing.");return current()&&exact;}catch(e){if(current())setError(errorText(e)+' Your draft is preserved. Retry Save to reconcile this operation.');return false;}finally{saving.current=false;if(live.current)setBusy(false);}}
    saver.current=save;
    async function prepare(refresh=false,saveFirst=false){
        const current=capture(),permission=refresh?permissions.refresh:permissions.prepare;if(refresh&&latest.current.draft?.builtInDefault){setError('The built-in standard-library environment is managed automatically.');return;}
        if(!can(permission)||refresh&&!can(permissions.manage)){setError('Permission to prepare and update Python environments is required.');return;}
        if(saveFirst&&!await save())return;if(!current())return;
        if(!can(permission)||refresh&&!can(permissions.manage)){setError('Permission to prepare and update Python environments is required.');return;}
        let p=latest.current.draft;if(p?.builtInDefault&&!defaultProfile(latest.current.list)){setError('The built-in Python environment is unavailable. Refresh after resolving the engine configuration.');return;}if(!p?.id||dirty.current){setError('Save this environment before preparing it.');return;}if(saving.current)return;
        saving.current=true;setBusy(true);let refreshed=false;
        try{
            const target=guardApi(api,current);
            if(refresh){
                const body=refreshReceipt.current.capture({expectedRegistryEpoch:p.registryEpoch,expectedRevision:p.revision},p.id),value=await request(target,'post',`/profiles/${encodeURIComponent(p.id)}/refresh`,body),updated=assertProfileView(value.profile);
                if(value.requestId!==body.requestId||value.registryEpoch!==p.registryEpoch||updated.registryEpoch!==p.registryEpoch||updated.id!==p.id||updated.revision<p.revision+1)throw new Error('Malformed refresh response. Retry Refresh dependencies to reconcile the same operation.');
                if(!current())return;refreshReceipt.current.clear();setDraft(updated);latest.current.draft=updated;setJob(null);setLogs([]);refreshed=true;
                if(updated.revision!==p.revision+1){setError('Refresh was confirmed, but this environment changed again. Review its saved revision before preparing.');return;}p=updated;
            }
            if(!can(permissions.prepare)){throw new Error('Permission to prepare Python environments is required.');}
            const body=profileToken(p),value=await request(target,'post',`/profiles/${encodeURIComponent(p.id)}/prepare`,body),updated=assertProfileView(value.profile);assertJobView(value.job);
            if(value.requestId!==body.requestId||value.registryEpoch!==p.registryEpoch||updated.id!==p.id||updated.registryEpoch!==p.registryEpoch||updated.revision!==p.revision||value.job.profileId!==p.id||value.job.registryEpoch!==p.registryEpoch||value.job.profileRevision!==p.revision)throw new Error('Preparation response does not match this saved environment.');
            if(current()){setDraft(updated);latest.current.draft=updated;setJob(value.job);setLogs([]);setError('');}
        }catch(e){if(current())setError((refreshed?`Refreshed revision ${p.revision} is saved; preparation is unavailable. `:'')+errorText(e));}finally{saving.current=false;if(live.current)setBusy(false);}
    }
    useEffect(()=>{if(!job)return;let stopped=false;let timer;const own=job.id,epoch=job.registryEpoch,node=job.nodeId,session=fence.current.capture();
        const active=()=>!stopped&&session();async function poll(){try{const target=guardApi(api,active);const [j,out]=await Promise.all([request(target,'get',`/jobs/${encodeURIComponent(own)}`),request(target,'get',`/jobs/${encodeURIComponent(own)}/logs?cursor=0`)]);if(!active())return;assertJobView(j);if(j.id!==own||j.nodeId!==node||j.registryEpoch!==epoch||out.jobId!==own||out.nodeId!==node||!Array.isArray(out.lines))throw new Error('Preparation or output belongs to another node or registry. Refresh this environment.');setJob(j);setLogs(out.lines);if(terminalJobs.has(j.state)){await load();if(!active())return;const p=latest.current.draft;if(!dirty.current&&p?.id===j.profileId&&p.registryEpoch===epoch&&Number(p.revision)===Number(j.profileRevision)){const current=capture(),updated=assertProfileView(await request(guardApi(api,()=>active()&&current()),'get',`/profiles/${encodeURIComponent(p.id)}`));if(updated.id!==p.id)throw new Error('Profile response does not match the requested environment.');if(active()&&!dirty.current&&current())setDraft(updated);}}else timer=setTimeout(poll,1500);}catch(e){if(active())setError(errorText(e));}}timer=setTimeout(poll,1000);return()=>{stopped=true;clearTimeout(timer);};},[job?.id]);
    async function cancel(){if(!job||!can(permissions.cancel))return;const expected=job,current=capture();try{const next=assertJobView(await request(api,'post',`/jobs/${encodeURIComponent(expected.id)}/cancel`));if(next.id!==expected.id||next.nodeId!==expected.nodeId||next.registryEpoch!==expected.registryEpoch)throw new Error('Cancellation response does not match this preparation.');if(current())setJob(next);}catch(e){if(current())setError(errorText(e));}}
    async function reloadProfile(){const p=latest.current.draft;if(!p||saving.current)return;const before=capture();
        if(dirty.current&&!await platform.ui.confirmDialog('Discard profile changes','Reloading the saved environment will discard this draft.',{danger:true,okLabel:'Discard changes'}))return;if(!before())return;
        generation.current++;const current=capture();saving.current=true;setBusy(true);try{const next=p.id?assertProfileView(await request(guardApi(api,current),'get',`/profiles/${encodeURIComponent(p.id)}`)):null;if(next&&next.id!==p.id)throw new Error('Profile response does not match the requested environment.');if(!current())return;setDraft(next);latest.current.draft=next;pending.current=null;dirty.current=false;markClean();setJob(null);setLogs([]);await load();}catch(e){if(current())setError(errorText(e));}finally{saving.current=false;if(live.current)setBusy(false);}}
    async function archive(){const p=latest.current.draft;if(!p?.id||p.builtInDefault||dirty.current||saving.current||!can(permissions.archive))return;const body=profileToken(p),current=capture();saving.current=true;setBusy(true);try{const v=decode(await api.del('/extensions/python-runtime/profiles/'+encodeURIComponent(p.id),body));assertProfileView(v.profile);if(v.requestId!==body.requestId||v.registryEpoch!==p.registryEpoch||v.profile.id!==p.id||v.profile.registryEpoch!==p.registryEpoch||v.profile.archived!==true)throw new Error('Malformed archive response. Refresh the saved environment before retrying.');if(!current())return;setDraft(v.profile);latest.current.draft=v.profile;setJob(null);setLogs([]);await load();}catch(e){if(current())setError(errorText(e));}finally{saving.current=false;if(live.current)setBusy(false);}}
    async function importFile(){if(!can(permissions.import))return;const current=capture(),file=await platform.ui.pickFile('.json');if(!file||!current())return;try{const value=JSON.parse(file.content);if(value.formatVersion!==1||!Array.isArray(value.profiles))throw new Error('Select a version 1 Python profile export.');setBundle(value);setMappings({});}catch(e){if(current())setError(errorText(e));}}
    async function applyImport(){if(!bundle||!list||saving.current||!can(permissions.import))return;const current=capture();saving.current=true;setBusy(true);try{const expectedRevisions={};for(const [old,id]of Object.entries(mappings)){const p=list.profiles.find(p=>p.id===id);if(p)expectedRevisions[old]=p.revision;}const body=importReceipt.current.capture({expectedRegistryEpoch:list.registryEpoch,bundle,mappings,expectedRevisions});const v=assertImportResult(await request(guardApi(api,current),'post','/profiles/import',body),body);if(!current())return;if(v.conflicts.length){setError(v.conflicts.map(d=>d.message).join('\n'));return;}importReceipt.current.clear();setBundle(null);await load();}catch(e){if(current())setError(errorText(e)+' Import choices are preserved. Retry Apply import to reconcile this operation.');}finally{saving.current=false;if(live.current)setBusy(false);}}
    async function exportFile(){if(!can(permissions.export))return;const current=capture();try{const v=await request(guardApi(api,current),'get','/profiles/export');if(current())platform.ui.downloadFile('python-environments.json',JSON.stringify(v,null,2),'application/json');}catch(e){if(current())setError(errorText(e));}}
    const editable=ready&&!!runtime&&!busy&&!draft?.builtInDefault&&can(draft?.id?permissions.manage:permissions.create);const p=draft;
    return <div className="flex flex-col gap-4"><div><h2 className="text-lg font-semibold">Script Environments</h2><p className="text-text-dim" style={{maxWidth:"72ch"}}>The built-in standard library prepares automatically. Add an environment when a script needs extra packages. Saving or preparing a profile leaves active deployments on their current environment until redeployment. Exports contain custom environments; the built-in default is supplied by each installation.</p></div>{error&&<p role="alert" className="whitespace-pre-wrap">{error}</p>}{list?.defaultProfileMessage&&<p role="alert">{list.defaultProfileMessage}</p>}
        <div className="flex flex-wrap gap-2"><button type="button" className="btn" disabled={!ready||!runtime||busy||!can(permissions.create)} onClick={()=>choose(newEnvironment(runtime,list.registryEpoch))}>Add environment</button><button type="button" className="btn" disabled={busy||dirty.current} onClick={load}>Refresh list</button><button type="button" className="btn" disabled={busy||!list||!can(permissions.import)} onClick={importFile}>Import</button><button type="button" className="btn" disabled={!list||!can(permissions.export)} onClick={exportFile}>Export</button>{platform.store.getState('pythonReturnPath')&&<button type="button" className="btn" onClick={()=>platform.router.navigate(platform.store.getState('pythonReturnPath'))}>Back to channel editor</button>}</div>
        {!list?<p role="status">Loading script environments…</p>:<div className="flex flex-wrap gap-6 items-start"><div style={{flex:"1 1 280px",minWidth:0,width:"100%"}}><div className="dt-wrap"><table className="dt"><thead><tr><th>Name</th><th>Runtime</th><th>Preparation</th><th>Usage</th></tr></thead><tbody>{list.profiles.filter(v=>!v.archived).map(v=><tr key={v.id}><td><button type="button" className="btn" onClick={()=>choose(v)}>{v.name}{v.builtInDefault?' · Built-in':''}</button></td><td>{runtime?.runtimes.find(r=>r.id===v.runtimeId)?runtimeLabel(runtime.runtimes.find(r=>r.id===v.runtimeId)):v.runtimeId}</td><td>{stateLabel(v)}</td><td>{v.savedReferenceCount} saved · {v.deployedReferenceCount} deployed</td></tr>)}</tbody></table></div>{!list.profiles.length&&<p>Add an environment and select an approved interpreter. Leave requirements empty to use Python’s standard library.</p>}</div>
            {p&&<div className="flex flex-col gap-3" style={{flex:"1 1 300px",minWidth:0,width:"100%"}}>
                <label className="field">Name<input value={p.name||''} disabled={!editable} onChange={e=>change('name',e.target.value)}/></label>
                <label className="field">Description<textarea rows="2" value={p.description||''} disabled={!editable} onChange={e=>change('description',e.target.value)}/></label>
                <EnvironmentChoice label="Approved Python runtime" value={p.runtimeId}
                    options={runtime?.runtimes||[]} format={runtimeLabel} disabled={!editable}
                    onChange={value=>change('runtimeId',value)}
                    help="The installed Python interpreter used to run this environment. Available interpreters are configured on the engine."/>
                <EnvironmentChoice label="Package source" value={p.packageSourceId}
                    options={runtime?.packageSources.filter(v=>v.kind!=='builtin'||p.builtInDefault||v.id===p.packageSourceId)||[]}
                    format={value=>`${value.label} (${value.kind})`} disabled={!editable}
                    onChange={value=>change('packageSourceId',value)}
                    help="Where preparation obtains dependency packages. Sources are configured on the engine."/>
                <label className="field">Requirements<textarea rows="10" className="font-mono" value={p.requirementsText||''} disabled={!editable} onChange={e=>change('requirementsText',e.target.value)}/></label>
                {p.builtInDefault&&<p>Built-in standard library: no requirements or manual setup. This profile is read-only; preparation runs automatically.</p>}
                <p>One constraint per line. Preparation accepts compatible wheels from approved sources. No editable, VCS, direct URL, or local-path dependencies.</p>
                <div className="flex flex-wrap gap-2"><button type="button" className="btn" disabled={!editable} onClick={save}>Save</button><button type="button" className="btn" disabled={!editable||!can(permissions.prepare)} onClick={()=>prepare(false,true)}>Save and Prepare</button><button type="button" className="btn" disabled={busy||!p.id||p.builtInDefault&&!defaultProfile(list)||dirty.current||!can(permissions.prepare)} onClick={()=>prepare()}>Prepare saved revision</button><button type="button" className="btn" disabled={busy||p.builtInDefault||!p.id||dirty.current||!can(permissions.refresh)||!can(permissions.manage)} onClick={()=>prepare(true)}>Refresh dependencies</button><button type="button" className="btn" disabled={busy} onClick={reloadProfile}>Reload saved profile</button><button type="button" className="btn" disabled={busy||p.builtInDefault||!p.id||dirty.current||!can(permissions.archive)} onClick={archive}>Archive</button></div><p>Saved revision {p.revision??'—'} · {p.builtInDefault&&!defaultProfile(list)?'built-in environment unavailable':stateLabel(p)}{p.readiness?.message?`: ${p.readiness.message}`:''}</p>
                {refs&&<div><h3 className="font-semibold">Usage</h3>{refs.saved?.map((v,i)=><p key={'s'+i}>{v.channelName} · {v.connectorName} · {v.role}</p>)}<h3 className="font-semibold mt-3">Active deployments</h3>{refs.deployed?.map((v,i)=><p key={'d'+i}>{v.channelName}{v.redeployNeeded?' · Redeploy needed':' · Current deployed environment'}</p>)}{refs.hiddenCount>0&&<p>{refs.hiddenCount} additional references are hidden by permissions.</p>}</div>}
                {job&&<div style={{minWidth:0}}><strong>Preparation of saved revision {job.profileRevision}: {job.state}</strong><p>{job.message}</p><Result value={{status:job.state,diagnostics:job.diagnostics}}/>{!terminalJobs.has(job.state)&&<button type="button" className="btn" disabled={!can(permissions.cancel)} onClick={cancel}>Cancel preparation</button>}<details><summary>View preparation output</summary><OutputPanel label="Preparation output">{logs.join('\n')||'No output yet.'}</OutputPanel></details></div>}
            </div>}</div>}
        {bundle&&<div className="flex flex-col gap-3"><h3 className="font-semibold">Import profile mapping</h3><p>Keep IDs when definitions agree. Choose a new ID or explicitly update a local profile to resolve conflicts.</p>{bundle.profiles.map(v=>v.id===DEFAULT_PROFILE_ID?<p key={v.id}>{v.name} · Built-in profile supplied by this installation. Only an unchanged definition can be preserved.</p>:<label className="field" key={v.id}>{v.name}<select disabled={busy} value={mappings[v.id]||''} onChange={e=>setMappings({...mappings,[v.id]:e.target.value})}><option value="">Preserve ID when safe</option><option value="new">Create with new ID</option>{list.profiles.filter(p=>!p.archived&&!p.builtInDefault&&p.id!==DEFAULT_PROFILE_ID).map(p=><option key={p.id} value={p.id}>Update {p.name} (revision {p.revision})</option>)}</select></label>)}<div className="flex gap-2"><button type="button" className="btn" disabled={busy} onClick={applyImport}>Apply import</button><button type="button" className="btn" disabled={busy} onClick={()=>setBundle(null)}>Cancel import</button></div></div>}
    </div>;
}
export function register(_host, context){
    const [major,minor]=platform.apiVersion.split('.').map(Number);if(major!==4||minor<10||typeof platform.registerCodeTemplateType!=='function')throw new Error('Python Runtime requires OIE Web Administrator extension API 4.10 or newer. Upgrade the administrator.');
    registerPythonEditorLanguage(platform, context);
    platform.registerSettingsPanel({id:'python-runtime',label:'Script Environments',order:65,task:permissions.view,component:EnvironmentSettings});
    registerPythonCodeTemplate();
    platform.registerRuleType(RULE,{label:'Python',create:()=>({__type:RULE,name:'Python',enabled:true,operator:'AND',...defaults(),pythonScript:'result = True'}),component:ScriptPanel});
    platform.registerStepType(STEP,{label:'Python',create:()=>({__type:STEP,name:'Python',enabled:true,...defaults()}),component:ScriptPanel});
    for(const [name,mode,role,cls]of [['Python Reader','SOURCE','reader','PythonReceiverProperties'],['Python Writer','DESTINATION','writer','PythonDispatcherProperties']])platform.registerConnectorPanel(name,mode,{
        defaults:version=>({'@class':`com.mirth.connect.connectors.python.${cls}`,'@version':version,pluginProperties:{'@class':'set'},...defaults(role),...(role==='reader'?{pollConnectorProperties:defaultPollProperties(version),sourceConnectorProperties:defaultSourceProperties(version)}:{destinationConnectorProperties:defaultDestinationProperties(version)})}),
        validate:subject=>{const message=structuralMessage(subject);return message?[{key:'pythonScript',label:message}]:[];},component:props=><div className="flex flex-col gap-4">{role==='reader'&&<PollSection properties={props.properties} onChange={props.onChange}/>}<ScriptPanel {...props} role={role}/></div>});
}
