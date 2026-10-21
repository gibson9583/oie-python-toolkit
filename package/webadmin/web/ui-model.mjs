/* SPDX-License-Identifier: MPL-2.0 */
import { stepWrapper, filterWrapper } from './step-wrapper.mjs';
import { placementError } from './step-placement.mjs';
export const BASE = '/extensions/python-runtime';
export const RULE = 'com.mirth.connect.plugins.python.PythonRule';
export const STEP = 'com.mirth.connect.plugins.python.PythonStep';
export const DEFAULT_PROFILE_ID = '232030e4-aa0d-39fc-a889-fed1318ef412';
export const DEFAULT_PROFILE_NAME = 'Python';
export const terminalJobs = new Set(['READY', 'FAILED', 'CANCELLED', 'SUPERSEDED', 'INTERRUPTED']);
export const newRequestId = () => crypto.randomUUID();
export function decode(value) {
    if (typeof value === 'string') value = JSON.parse(value);
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('The Python runtime returned a malformed response.');
    return value;
}
export function defaults(role) {
    return { environmentProfileId: DEFAULT_PROFILE_ID, environmentNameHint: DEFAULT_PROFILE_NAME, pythonScript: role === 'reader' || role === 'writer' ? 'return None\n' : '', timeoutMillis: 30000, contractVersion: 1 };
}
export function newEnvironment(runtime, registryEpoch) {
    const runtimes = runtime.runtimes.filter(option => option.available);
    const sources = runtime.packageSources.filter(option => option.kind !== 'builtin');
    return {
        name: '', description: '', requirementsText: '', registryEpoch,
        runtimeId: runtimes.length === 1 ? runtimes[0].id : '',
        packageSourceId: sources.length === 1 ? sources[0].id : ''
    };
}
export function normalizeConnectorPluginProperties(properties) {
    if (properties.pluginProperties == null) properties.pluginProperties = { '@class': 'set' };
    return properties;
}
export function runtimeLabel(runtime) {
    if (!runtime) return 'Python runtime unavailable';
    return (runtime.label || runtime.id || 'Python runtime') + (runtime.version ? ` · CPython ${runtime.version}` : '') + (runtime.available ? '' : ' (unavailable)');
}
function checkDefaultBinding(subject,runtime,profile){
    if(subject.environmentProfileId===DEFAULT_PROFILE_ID&&(runtime.defaultProfileId!==DEFAULT_PROFILE_ID||profile.builtInDefault!==true))throw new Error('The built-in Python environment is unavailable or conflicts with an existing profile. Open Script Environments for details.');
}
export function structuralMessage(subject) {
    if (typeof subject?.pythonScript !== 'string') return 'Python source must be text.';
    if (!Number.isInteger(Number(subject.timeoutMillis)) || Number(subject.timeoutMillis) < 100) return 'Timeout must be a whole number of at least 100 milliseconds.';
    return '';
}
export function canonicalSpec(subject) {
    // Sorted property order is part of the versioned Java/Python contract.
    return JSON.stringify({ canonicalization: 1, contractVersion: Number(subject.contractVersion) > 0 ? Number(subject.contractVersion) : 1,
        profileId: subject.environmentProfileId || '', source: (subject.pythonScript || '').replace(/\r\n?/g, '\n'), timeoutMillis: Number(subject.timeoutMillis) > 0 ? Number(subject.timeoutMillis) : 30000 });
}
// Synchronous SHA-256 is needed by the plugin's generated bridge preview. The
// host's CSP allows ordinary arithmetic, and WebCrypto remains async.
export function sha256(text) {
    const primes = []; for (let n = 2; primes.length < 64; n++) { if (primes.every(p => p > Math.sqrt(n) || n % p !== 0)) primes.push(n); }
    const k = primes.map(p => (Math.cbrt(p) % 1 * 0x100000000) >>> 0);
    let h = primes.slice(0, 8).map(p => (Math.sqrt(p) % 1 * 0x100000000) >>> 0);
    const bytes = new TextEncoder().encode(text); const length = bytes.length;
    const data = new Uint8Array(Math.ceil((length + 9) / 64) * 64); data.set(bytes); data[length] = 128;
    const view = new DataView(data.buffer); view.setUint32(data.length - 8, Math.floor(length / 0x20000000)); view.setUint32(data.length - 4, (length * 8) >>> 0);
    const rotr = (n, x) => (x >>> n) | (x << (32 - n));
    for (let offset = 0; offset < data.length; offset += 64) {
        const w = new Uint32Array(64);
        for (let i = 0; i < 64; i++) w[i] = i < 16 ? view.getUint32(offset + i * 4) : ((rotr(7,w[i-15]) ^ rotr(18,w[i-15]) ^ (w[i-15] >>> 3)) + w[i-16] + (rotr(17,w[i-2]) ^ rotr(19,w[i-2]) ^ (w[i-2] >>> 10)) + w[i-7]) >>> 0;
        let [a,b,c,d,e,f,g,j] = h;
        for (let i=0;i<64;i++) { const t1=(j+(rotr(6,e)^rotr(11,e)^rotr(25,e))+((e&f)^(~e&g))+k[i]+w[i])>>>0;
            const t2=((rotr(2,a)^rotr(13,a)^rotr(22,a))+((a&b)^(a&c)^(b&c)))>>>0;
            [a,b,c,d,e,f,g,j]=[(t1+t2)>>>0,a,b,c,(d+t1)>>>0,e,f,g]; }
        h = h.map((v,i) => (v+[a,b,c,d,e,f,g,j][i])>>>0);
    }
    return h.map(v => v.toString(16).padStart(8,'0')).join('');
}
export const generateScript = subject => (subject.__type === RULE ? filterWrapper : stepWrapper)(sha256(canonicalSpec(subject)));
export function createGeneration(read) {
    let generation = 0; let abort;
    return { invalidate() { generation++; abort?.abort(); }, begin() { abort?.abort(); abort = new AbortController();
        const own = ++generation; const key = JSON.stringify(read()); const snapshot = JSON.parse(key);
        return { snapshot, signal: abort.signal, isCurrent: () => own === generation && key === JSON.stringify(read()) }; } };
}
export async function request(api, method, path, body, signal) {
    const value = method === 'get' ? await api.get(BASE + path, undefined, { signal })
        : await api[method](BASE + path, body == null ? undefined : JSON.stringify(body), { signal });
    return decode(value);
}
export function profileToken(profile) {
    return { requestId: newRequestId(), expectedRegistryEpoch: profile.registryEpoch, expectedRevision: profile.revision };
}
export function scriptRequest(subject, profile, context, sampleContext) {
    return { requestId: newRequestId(), channelId: context.channelId, connectorMetadataId: context.connectorMetadataId,
        role: context.role, environmentProfileId: subject.environmentProfileId, expectedRegistryEpoch: profile?.registryEpoch,
        expectedEnvironmentRevision: profile?.revision, pythonScript: subject.pythonScript, timeoutMillis: Number(subject.timeoutMillis),
        contractVersion: Number(subject.contractVersion) || 1, sampleContext };
}
export async function validateRuntime(api, subject, context) {
    const invalid = structuralMessage(subject);
    if (invalid) return { status: 'invalid', diagnostics: [{ severity: 'error', message: invalid }] };
    const placement = placementError(context);
    if (placement) return { status: 'invalid', diagnostics: [{ severity: 'error', code: context.elementPath?.length ? 'UNSUPPORTED_ITERATOR' : 'STEP_CONTEXT_UNAVAILABLE', message: placement }] };
    if (!subject.environmentProfileId) return { status: 'invalid', diagnostics: [{ severity: 'error', field: 'environmentProfileId', message: 'Select a script environment before validating or deploying.' }] };
    try {
        const runtime = await request(api, 'get', '/runtime', null, context.signal);
        if (typeof runtime.nodeId !== 'string' || !runtime.nodeId) throw new Error('Python runtime node identity is unavailable.');
        const profile = assertProfileView(await request(api, 'get', `/profiles/${encodeURIComponent(subject.environmentProfileId)}`, null, context.signal));
        if(profile.id!==subject.environmentProfileId)throw new Error('Profile response does not match the requested environment.');
        checkDefaultBinding(subject,runtime,profile);
        const body = scriptRequest(subject, profile, context);
        body.expectedNodeId = runtime.nodeId;
        const result = await request(api, 'post', '/scripts/validate', body, context.signal);
        if (result.nodeId !== runtime.nodeId || result.sourceDigest !== sha256(canonicalSpec(subject)) || result.requestId !== body.requestId || result.registryEpoch !== profile.registryEpoch || Number(result.environmentRevision) !== Number(profile.revision)
            || !['valid', 'invalid', 'unavailable'].includes(result.status) || !Array.isArray(result.diagnostics)) throw new Error('Validation response does not match the current script environment. Retry validation.');
        return { status: result.status, diagnostics: result.diagnostics };
    } catch (error) { return { status: 'unavailable', diagnostics: [{ severity: 'error', message: error.message || 'Python runtime validation is unavailable.' }] }; }
}
export async function testRuntime(api, subject, context, sampleContext) {
    const placement = placementError(context);
    if (placement) throw new Error(placement);
    const runtime = await request(api, 'get', '/runtime', null, context.signal);
    if (!runtime.nodeId) throw new Error('Python runtime node identity is unavailable.');
    const profile = assertProfileView(await request(api, 'get', `/profiles/${encodeURIComponent(subject.environmentProfileId)}`, null, context.signal));
    if(profile.id!==subject.environmentProfileId)throw new Error('Profile response does not match the requested environment.');
    checkDefaultBinding(subject,runtime,profile);
    const body = scriptRequest(subject, profile, context, sampleContext);
    body.expectedNodeId = runtime.nodeId;
    try {
        const result = await request(api, 'post', '/scripts/test', body, context.signal);
        if (result.profileId!==subject.environmentProfileId || result.status==='success'&&(!result.output || typeof result.output!=='object' || Array.isArray(result.output)) || result.nodeId !== runtime.nodeId || result.requestId !== body.requestId || result.sourceDigest !== sha256(canonicalSpec(subject)) || result.registryEpoch !== profile.registryEpoch || Number(result.environmentRevision) !== Number(profile.revision) || !Array.isArray(result.diagnostics) || !['success', 'error', 'timeout', 'cancelled', 'unavailable'].includes(result.status)) throw new Error('Test response could not be verified.');
        return result;
    } catch(error) {
        if(error.operationSent===false)throw error;
        const uncertain=new Error('External outcome uncertain. Review the target before retrying. '+error.message);uncertain.outcomeUncertain=true;throw uncertain;
    }
}
export function assertProfileView(profile) {
    if (!profile || typeof profile.id !== 'string' || !profile.id || typeof profile.registryEpoch !== 'string' || !profile.registryEpoch || !Number.isInteger(profile.revision) || profile.revision < 1) throw new Error('The Python runtime returned a malformed profile. Your draft is preserved.');
    return profile;
}
export function assertJobView(job) {
    if (!job || typeof job.id !== 'string' || !job.id || typeof job.nodeId !== 'string' || !job.nodeId || typeof job.registryEpoch !== 'string' || !job.registryEpoch || typeof job.profileId !== 'string' || !job.profileId || !Number.isInteger(job.profileRevision) || job.profileRevision < 1 || !['QUEUED','RESOLVING','BUILDING','VERIFYING','READY','FAILED','CANCELLED','SUPERSEDED','INTERRUPTED'].includes(job.state)) throw new Error('The Python runtime returned a malformed preparation job. Refresh the saved environment.');
    return job;
}
export function createMutationReceipt() {
    let saved;
    return { capture(value,context='') { const body=JSON.stringify(value),key=JSON.stringify([context,body]);if(saved?.key!==key)saved={key,body:{...JSON.parse(body),requestId:newRequestId()}};return saved.body; }, clear() { saved=null; } };
}
export function assertImportResult(result, request) {
    const arrays=['created','updated','unchanged'];
    if (!result || result.requestId!==request.requestId || result.registryEpoch!==request.expectedRegistryEpoch || !Number.isInteger(result.registryRevision) || result.registryRevision<0 || !result.mappings || Array.isArray(result.mappings) || typeof result.mappings!=='object' || !Array.isArray(result.conflicts) || result.conflicts.some(d=>!d || typeof d.message!=='string') || arrays.some(key=>!Array.isArray(result[key]) || result[key].some(id=>typeof id!=='string' || !id))) throw new Error('Malformed import response. Retry Apply import to reconcile the same operation.');
    const targets=[...result.created,...result.updated,...result.unchanged];
    if (!result.conflicts.length && request.bundle.profiles.some(profile=>{const actual=result.mappings[profile.id],expected=request.mappings[profile.id]??profile.id;return typeof actual!=='string' || !targets.includes(actual) || expected!=='new'&&expected!==actual;})) throw new Error('Import response is missing or changes requested profile mappings. Retry Apply import to reconcile the same operation.');
    return result;
}

// Stock embedded transformer panels omit connector context; do not invent one.
export function routeValidationContext(path) {
    const match=String(path).match(/\/(transformer|response|filter)\/(\d+)\/?(?:[?#].*)?$/);
    if(!match)return {role:'',connectorMetadataId:null};
    const id=Number(match[2]);return {role:match[1]==='filter'?(id===0?'source-filter':'destination-filter'):match[1]==='response'?'response-transformer':id===0?'source-transformer':'destination-transformer',connectorMetadataId:id};
}
