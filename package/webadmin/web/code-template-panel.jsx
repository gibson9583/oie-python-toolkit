/* SPDX-License-Identifier: MPL-2.0 */
import { platform } from '@oie/web-shell';
import * as api from '@oie/web-api';
import { PythonEditor } from './python-editor.jsx';
import { OutputPanel } from './output-panel.jsx';
import { createGeneration, request, DEFAULT_PROFILE_ID } from './ui-model.mjs';
import { selectedEnvironment, environmentState } from './default-environment.mjs';
import { createSessionFence, guardApi } from './ui-session.mjs';
import { TEMPLATE_TYPE, TEMPLATE_CLASS, TEMPLATE_CONTEXTS, templateDefaults, templateContexts, functionNameMessage, templateCompletionCode, validateTemplateRuntime } from './code-template-model.mjs';

const React = platform.React;
const { useState, useRef, useLayoutEffect, useEffect, useId } = React;
const canEdit = () => platform.checkTask('codeTemplate', 'doSaveCodeTemplates');
const canValidate = () => canEdit() && platform.checkTask('extension', 'validatePythonScript');
const errorText = error => error?.message || String(error);

function TemplatePanel({ template, properties, onChange, readOnly }) {
    const [profiles, setProfiles] = useState(null);
    const [error, setError] = useState('');
    const [result, setResult] = useState(null);
    const [busy, setBusy] = useState(false);
    const [, render] = useState(0);
    const [refresh, setRefresh] = useState(0);
    const nameErrorId = useId();
    const fence = useRef();
    const live = useRef(false);
    const read = useRef();
    read.current = () => ({ properties, contexts: templateContexts(template), readOnly });
    const actions = useRef();
    if (!actions.current) actions.current = createGeneration(() => read.current());
    const fingerprint = JSON.stringify(read.current());

    useLayoutEffect(() => {
        live.current = true;
        fence.current = createSessionFence(platform);
        const invalidate = () => { actions.current.invalidate(); setResult(null); setBusy(false); setProfiles(null); setRefresh(value => value + 1); };
        const unsubscribe = platform.store.subscribe('serverId', invalidate);
        window.addEventListener('oie-session-changed', invalidate);
        return () => { live.current = false; actions.current.invalidate(); fence.current.close(); unsubscribe(); window.removeEventListener('oie-session-changed', invalidate); };
    }, []);
    useEffect(() => { actions.current.invalidate(); setResult(null); setBusy(false); }, [fingerprint]);
    useEffect(() => {
        let mounted = true, timer;
        const currentSession = fence.current.capture();
        const current = () => mounted && live.current && currentSession();
        const load = async () => {
            try {
                const value = await request(guardApi(api, current), 'get', '/profiles');
                if (!Array.isArray(value.profiles) || value.profiles.some(profile => !profile || typeof profile.id !== 'string')) throw new Error('The environment list is unavailable. Refresh environments to try again.');
                if (!current()) return;
                setProfiles(value); setError('');
                const selected = selectedEnvironment(properties, value);
                if (selected?.builtInDefault && ['preparing', 'not-prepared'].includes(selected.readiness?.state)) timer = setTimeout(load, 1500);
            } catch (failure) { if (current()) setError(errorText(failure)); }
        };
        setProfiles(null); setError(''); load();
        return () => { mounted = false; clearTimeout(timer); };
    }, [properties, properties.environmentProfileId, refresh]);

    function change(key, value) {
        if (read.current().readOnly || !canEdit()) return;
        actions.current.invalidate(); setResult(null); setBusy(false);
        properties[key] = value;
        onChange(); render(value => value + 1);
    }
    async function validate() {
        if (read.current().readOnly || !canValidate()) return;
        const action = actions.current.begin(), session = fence.current.capture();
        const current = () => live.current && session() && action.isCurrent() && canValidate();
        setBusy(true); setResult(null);
        const value = await validateTemplateRuntime(guardApi(api, current), action.snapshot.properties, { contextTypes: action.snapshot.contexts, signal: action.signal });
        if (current()) { setResult(value); setBusy(false); }
    }
    const selected = selectedEnvironment(properties, profiles);
    const nameError = functionNameMessage(properties.functionName);
    return <section className="flex flex-col gap-4 min-w-0" aria-label="Python function">
        <fieldset disabled={readOnly} className="flex flex-col gap-4 min-w-0" style={{ border: 0, margin: 0, padding: 0 }}>
            <div className="flex flex-wrap items-end gap-3">
                <label className="field" style={{ flex: '1 1 220px', minWidth: 0 }}>Function name
                    <input aria-label="Function name" value={properties.functionName || ''} spellCheck={false} autoComplete="off" aria-invalid={!!nameError} aria-describedby={nameError ? nameErrorId : undefined} onChange={event => change('functionName', event.target.value)}/>
                    {nameError && <span id={nameErrorId} role="alert">{nameError}</span>}
                </label>
                <label className="field" style={{ flex: '1 1 240px', minWidth: 0 }}>Script environment
                    <select aria-label="Script environment" value={properties.environmentProfileId || ''} disabled={!profiles} onChange={event => {
                        if (read.current().readOnly || !canEdit()) return;
                        properties.environmentNameHint = profiles.profiles.find(profile => profile.id === event.target.value)?.name || '';
                        change('environmentProfileId', event.target.value);
                    }}>
                        <option value="">{profiles ? 'Select a script environment' : 'Loading environments…'}</option>
                        {properties.environmentProfileId && !selected && <option value={properties.environmentProfileId}>Unresolved: {properties.environmentNameHint || properties.environmentProfileId}</option>}
                        {profiles?.profiles.filter(profile => !profile.archived && (profile.id !== DEFAULT_PROFILE_ID || profile === selectedEnvironment({ environmentProfileId: DEFAULT_PROFILE_ID }, profiles))).map(profile => <option key={profile.id} value={profile.id}>{profile.name}</option>)}
                    </select>
                </label>
            </div>
            <div className="flex flex-wrap items-center gap-2">
                <button type="button" className="btn" onClick={() => setRefresh(value => value + 1)}>Refresh environments</button>
            </div>
        </fieldset>
        {error && <p role="alert">{error} Your function draft is preserved.</p>}
        {selected && <p className="text-text-dim">{selected.name} · {environmentState(selected)}{selected.readiness?.message ? `: ${selected.readiness.message}` : ''}</p>}
        {profiles && properties.environmentProfileId && !selected && <p role="alert">This script environment is unavailable. Select an available environment before deploying.</p>}
        <PythonEditor value={properties.pythonScript || ''} onChange={value => change('pythonScript', value)} readOnly={readOnly} role="code-template" profile={selected} catalog={profiles}/>
        <details>
            <summary>Advanced</summary>
            <fieldset disabled={readOnly} className="min-w-0" style={{ border: 0, margin: 0, padding: 0 }}>
                <label className="field mt-3">Invocation timeout (milliseconds)<input type="number" min="100" step="1" value={properties.timeoutMillis} onChange={event => change('timeoutMillis', Number(event.target.value))}/></label>
            </fieldset>
        </details>
        {!readOnly && <div><button type="button" className="btn" disabled={busy || !canValidate()} onClick={validate}>{busy ? 'Validating Python…' : 'Validate Python'}</button></div>}
        {result && <div role="status" aria-live="polite">
            <strong>{result.status === 'valid' ? 'Python function validated' : result.status === 'invalid' ? 'Python function needs attention' : 'Python validation unavailable'}</strong>
            {result.diagnostics.length > 0 && <OutputPanel label="Python diagnostics" preformatted={false}>
                {result.diagnostics.map((diagnostic, index) => <p key={index}>{diagnostic.line ? `Line ${diagnostic.line}: ` : ''}{diagnostic.message}{diagnostic.suggestedAction ? ` ${diagnostic.suggestedAction}` : ''}</p>)}
            </OutputPanel>}
        </div>}
    </section>;
}

export function registerPythonCodeTemplate() {
    platform.registerCodeTemplateType(TEMPLATE_TYPE, {
        label: 'Python Function', propertiesClass: TEMPLATE_CLASS, contexts: TEMPLATE_CONTEXTS,
        create: templateDefaults, component: TemplatePanel,
        validateSave: ({ properties }) => functionNameMessage(properties.functionName) || null,
        getCompletionCode: ({ properties }) => templateCompletionCode(properties),
        validate: async ({ template, properties }) => {
            if (!canEdit()) return 'Permission to manage code templates is required to validate Python functions.';
            if (!canValidate()) return 'Python validation permission is required.';
            const fence = createSessionFence(platform), session = fence.capture();
            try {
                const result = await validateTemplateRuntime(guardApi(api, () => session() && canValidate()), properties, { contextTypes: templateContexts(template) });
                return result.status === 'valid' ? null : result.diagnostics.map(diagnostic => diagnostic.message).join('\n') || 'Python validation is unavailable.';
            } finally { fence.close(); }
        }
    });
}
