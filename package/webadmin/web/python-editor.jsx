/* SPDX-License-Identifier: MPL-2.0 */
import { platform } from '@oie/web-shell';
import * as api from '@oie/web-api';
import { request } from './ui-model.mjs';
import { createSessionFence } from './ui-session.mjs';
import { bindPythonEditor } from './python-services.mjs';

const React = platform.React;
const { useRef, useEffect, useLayoutEffect } = React;

export function PythonEditor({ value, onChange, readOnly = false, role, channelId, profile, catalog }) {
    const host = useRef();
    const editor = useRef();
    const callback = useRef(onChange);
    callback.current = onChange;
    const metadata = useRef();
    metadata.current = { role, channelId, readOnly, profile: profile ? { id: profile.id, revision: profile.revision } : null,
        nodeId: catalog?.nodeId, epoch: catalog?.registryEpoch,
        allowed: platform.checkTask('extension', 'validatePythonScript') &&
            (role !== 'code-template' || platform.checkTask('codeTemplate', 'doSaveCodeTemplates')),
        send: body => request(api, 'post', '/editor', body) };
    useLayoutEffect(() => {
        const instance = platform.createCodeEditor({
            value,
            ariaLabel: 'Python source',
            language: 'python',
            tabSize: 4,
            insertSpaces: true,
            detectIndentation: false,
            readOnly,
            minHeight: '260px',
            onChange: next => callback.current(next)
        });
        editor.current = instance;
        const session = createSessionFence(platform);
        const unbind = bindPythonEditor(instance, () => ({ ...metadata.current, allowed: platform.checkTask('extension', 'validatePythonScript') &&
            (metadata.current.role !== 'code-template' || platform.checkTask('codeTemplate', 'doSaveCodeTemplates')) }), session);
        host.current.appendChild(instance.el);
        return () => { unbind(); session.close(); instance.dispose?.(); instance.el.remove(); editor.current = null; };
    }, [readOnly]);
    useEffect(() => {
        if (editor.current?.getValue() !== value) editor.current?.setValue(value);
    }, [value]);
    return <div className="min-w-0"><div className="field-label mb-2">Python source</div><div ref={host}/></div>;
}
