/* SPDX-License-Identifier: MPL-2.0 */
// Providers use the host's Monaco and only models owned by this plugin.
const bindings = new Set();
const installed = new WeakSet();

export function bindPythonEditor(instance, read, session) {
    const binding = { instance, read, session };
    bindings.add(binding);
    return () => bindings.delete(binding);
}

function owner(model) {
    return [...bindings].find(value => value.instance.monaco?.getModel() === model);
}

export function installPythonServices(monaco) {
    if (!monaco?.languages || installed.has(monaco)) return;
    installed.add(monaco);
    async function call(model, operation, position, token, selection) {
        const binding = owner(model);
        if (!binding || model.isDisposed()) return null;
        const state = binding.read();
        if (!state?.profile || !state.nodeId || !state.epoch || !state.role || !state.allowed) return null;
        const identity = JSON.stringify(state);
        const version = model.getVersionId();
        const currentSession = binding.session.capture();
        const current = () => !model.isDisposed() && bindings.has(binding) && model.getVersionId() === version
            && JSON.stringify(binding.read()) === identity && currentSession() && !token?.isCancellationRequested;
        const body = { operation, pythonScript: model.getValue(), role: state.role, channelId: state.channelId,
            environmentProfileId: state.profile.id, expectedEnvironmentRevision: state.profile.revision,
            expectedRegistryEpoch: state.epoch, expectedNodeId: state.nodeId, selection,
            line: position?.lineNumber,
            column: position ? [...model.getLineContent(position.lineNumber).slice(0, position.column - 1)].length : undefined };
        try {
            if (!current()) return null;
            const value = await state.send(body);
            if (!current()) return null;
            if (!value?.ok) {
                if (operation === 'format') throw new Error(value?.message || 'Python formatting could not complete.');
                return null;
            }
            return value;
        } catch (error) {
            if (operation === 'format' && current()) throw error;
            return null;
        }
    }
    monaco.languages.registerCompletionItemProvider('python', {
        triggerCharacters: ['.'],
        async provideCompletionItems(model, position, _context, token) {
            const value = await call(model, 'complete', position, token);
            const kinds = monaco.languages.CompletionItemKind;
            return { suggestions: (value?.items || []).map(item => ({ label: item.name, insertText: item.name,
                detail: item.description, kind: ({ function: kinds.Function, class: kinds.Class, module: kinds.Module,
                    keyword: kinds.Keyword, param: kinds.Variable, instance: kinds.Variable })[item.type] ?? kinds.Property,
                range: new monaco.Range(position.lineNumber, Math.max(1, position.column - [...model.getLineContent(position.lineNumber).slice(0, position.column - 1)].slice(-item.prefixLength || Infinity).join('').length), position.lineNumber, position.column) })) };
        }
    });
    monaco.languages.registerHoverProvider('python', {
        async provideHover(model, position, token) {
            const value = await call(model, 'hover', position, token);
            return value?.contents?.length ? { contents: value.contents.map(value => ({ value: '```python\n' + value.replace(/```/g, '') + '\n```' })) } : null;
        }
    });
    monaco.languages.registerSignatureHelpProvider('python', {
        signatureHelpTriggerCharacters: ['(', ','],
        async provideSignatureHelp(model, position, token) {
            const value = await call(model, 'signature', position, token);
            if (!value?.signatures?.length) return null;
            return { value: { signatures: value.signatures.map(item => ({ label: item.label,
                documentation: item.documentation, parameters: item.parameters.map(label => ({ label })) })),
                activeSignature: 0, activeParameter: value.signatures[0].activeParameter }, dispose() {} };
        }
    });
    async function format(model, range, token) {
        if (owner(model)?.read().readOnly) return [];
        let selection, editRange = model.getFullModelRange();
        if (range) {
            // Python statements and indentation belong to full lines. Include
            // only lines touched by the selection, excluding an end at column1.
            const end = range.endColumn === 1 && range.endLineNumber > range.startLineNumber ? range.endLineNumber - 1 : range.endLineNumber;
            selection = { startLineNumber: range.startLineNumber, endLineNumber: end };
            editRange = end < model.getLineCount() ? new monaco.Range(range.startLineNumber, 1, end + 1, 1)
                : new monaco.Range(range.startLineNumber, 1, end, model.getLineMaxColumn(end));
        }
        const value = await call(model, 'format', null, token, selection);
        return value && typeof value.text === 'string' ? [{ range: editRange, text: value.text }] : [];
    }
    monaco.languages.registerDocumentFormattingEditProvider('python', {
        displayName: 'Python (autopep8)', provideDocumentFormattingEdits: (model, _options, token) => format(model, null, token)
    });
    monaco.languages.registerDocumentRangeFormattingEditProvider('python', {
        displayName: 'Python (autopep8)', provideDocumentRangeFormattingEdits: (model, range, _options, token) => format(model, range, token)
    });
}
