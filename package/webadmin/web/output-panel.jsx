/* SPDX-License-Identifier: MPL-2.0 */
import { platform } from '@oie/web-shell';

const React = platform.React;

export function OutputPanel({ label, children, preformatted = true }) {
    return <div role="region" aria-label={label} tabIndex={0} style={{
        maxHeight: '280px', overflow: 'auto', minWidth: 0, maxWidth: '100%',
        boxSizing: 'border-box', overflowWrap: 'anywhere', marginTop: '8px',
        padding: '12px', border: '1px solid var(--line)',
        borderRadius: 'var(--radius)', background: 'var(--bg1)'
    }}>
        {preformatted ? <pre style={{ margin: 0, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
            {children}
        </pre> : children}
    </div>;
}
