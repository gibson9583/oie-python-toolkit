/* SPDX-License-Identifier: MPL-2.0 */
import { defaults, structuralMessage, canonicalSpec, sha256, request, scriptRequest, assertProfileView, DEFAULT_PROFILE_ID } from './ui-model.mjs';

export const TEMPLATE_TYPE = 'python-function';
export const TEMPLATE_CLASS = 'com.mirth.connect.plugins.python.PythonCodeTemplateProperties';
export const TEMPLATE_CONTEXTS = ['SOURCE_FILTER_TRANSFORMER', 'DESTINATION_FILTER_TRANSFORMER', 'DESTINATION_RESPONSE_TRANSFORMER'];
const reserved = new Set(('break case catch continue debugger default delete do else finally for function if in instanceof new return switch this throw try typeof var void while with class const enum export extends import super implements interface let package private protected public static yield null true false arguments eval JSON Array Object Packages isFinite String Error connectorMessage responseStatus').split(' '));

export function templateDefaults({ version, template } = {}) {
    const candidate = String(template?.name || '').replace(/[^A-Za-z0-9_$]+/g, '_');
    const functionName = /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(candidate) && !reserved.has(candidate) ? candidate : 'python_function';
    return {
        '@class': TEMPLATE_CLASS,
        ...(version ? { '@version': version } : {}),
        type: 'FUNCTION',
        ...defaults(),
        functionName,
        pythonScript: 'return arguments[0] if arguments else None\n'
    };
}

export function templateContexts(template) {
    const value = template?.contextSet?.delegate?.contextType;
    return value == null ? [] : [].concat(value).map(String);
}

export function functionNameMessage(name) {
    return !/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(name || '') || reserved.has(name)
        ? 'Function name must be a non-reserved JavaScript identifier.' : '';
}

export function templateCompletionCode(subject) {
    if (functionNameMessage(subject?.functionName)) return null;
    // Inline types avoid shared typedefs colliding across template extra-libs.
    // Container contents depend on the Python function; they must also be JSON-compatible.
    const jsonValue = 'null|boolean|number|string|Array<*>|Object<string, *>';
    return `/** Python function: accepts JSON-compatible arguments and returns a JSON-compatible value.
 * Array elements and object properties must also be JSON-compatible.
 * @param {...(${jsonValue})} args JSON-compatible positional arguments.
 * @returns {${jsonValue}} The JSON-decoded Python result.
 */
function ${subject.functionName}(...args) {}`;
}

export function templateMessage(subject, contexts) {
    if (subject?.type !== 'FUNCTION') return 'Python functions must use the Function behavior.';
    const nameError = functionNameMessage(subject?.functionName);
    if (nameError) return nameError;
    const invalid = structuralMessage(subject);
    if (invalid) return invalid;
    if (!subject.environmentProfileId) return 'Select a script environment.';
    if (!contexts?.length) return 'Select at least one transformer context for this Python function.';
    if (contexts.some(value => !TEMPLATE_CONTEXTS.includes(value))) return 'Python functions support source, destination, and response transformer contexts only.';
    return '';
}

export function canonicalTemplateSpec(subject) {
    const spec = { ...JSON.parse(canonicalSpec(subject)), kind: 'code-template', functionName: subject.functionName };
    return JSON.stringify(Object.fromEntries(Object.keys(spec).sort().map(key => [key, spec[key]])));
}

export async function validateTemplateRuntime(api, subject, { contextTypes, signal } = {}) {
    const invalid = templateMessage(subject, contextTypes);
    if (invalid) return { status: 'invalid', diagnostics: [{ severity: 'error', message: invalid }] };
    try {
        const runtime = await request(api, 'get', '/runtime', null, signal);
        if (typeof runtime.nodeId !== 'string' || !runtime.nodeId) throw new Error('Python runtime node identity is unavailable.');
        const profile = assertProfileView(await request(api, 'get', `/profiles/${encodeURIComponent(subject.environmentProfileId)}`, null, signal));
        if (profile.id !== subject.environmentProfileId) throw new Error('Profile response does not match the requested environment.');
        if (subject.environmentProfileId === DEFAULT_PROFILE_ID && (runtime.defaultProfileId !== DEFAULT_PROFILE_ID || profile.builtInDefault !== true)) throw new Error('The built-in Python environment is unavailable. Open Script Environments for details.');
        const body = {
            ...scriptRequest(subject, profile, { role: 'code-template' }),
            functionName: subject.functionName,
            contextTypes: [...contextTypes],
            expectedNodeId: runtime.nodeId
        };
        const result = await request(api, 'post', '/templates/validate', body, signal);
        if (result.nodeId !== runtime.nodeId || result.requestId !== body.requestId || result.sourceDigest !== sha256(canonicalTemplateSpec(subject)) || result.registryEpoch !== profile.registryEpoch || Number(result.environmentRevision) !== profile.revision || !['valid', 'invalid', 'unavailable'].includes(result.status) || !Array.isArray(result.diagnostics)) throw new Error('Validation response does not match this Python function. Retry validation.');
        return { status: result.status, diagnostics: result.diagnostics };
    } catch (error) {
        return { status: 'unavailable', diagnostics: [{ severity: 'error', message: error.message || 'Python validation is unavailable.' }] };
    }
}
