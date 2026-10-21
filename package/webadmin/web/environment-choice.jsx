/* SPDX-License-Identifier: MPL-2.0 */
import { platform } from '@oie/web-shell';

const React = platform.React;
const { useId } = React;

export function EnvironmentChoice({ label, value, options, format, disabled, onChange, help }) {
    const helpId = useId();
    const selected = options.find(option => option.id === value);
    const single = options.length === 1 && selected;
    return <div style={{ minWidth: 0 }}>
        <label className="field">{label}
            {single ? <output aria-label={label} aria-describedby={helpId} style={{ overflowWrap: 'anywhere' }}>
                {format(selected)}
            </output> : <select aria-label={label} value={value || ''} disabled={disabled || !options.length}
                aria-describedby={helpId} style={{ width: '100%', minWidth: 0 }}
                onChange={event => onChange(event.target.value)}>
                <option value="">{options.length ? 'Select an option' : 'No approved options available'}</option>
                {value && !selected && <option value={value}>No longer approved: {value}</option>}
                {options.map(option => <option key={option.id} value={option.id}>{format(option)}</option>)}
            </select>}
        </label>
        <p id={helpId} className="text-text-dim" style={{ maxWidth: '72ch' }}>{help}</p>
    </div>;
}
