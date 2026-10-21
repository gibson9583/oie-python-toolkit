/* SPDX-License-Identifier: MPL-2.0 */
// Plugin-owned operation fencing using the stock platform store and browser session.
export function createSessionFence(platform, target = globalThis.window) {
    let generation = 0;
    const invalidate = () => { generation++; };
    const unsubscribe = platform.store.subscribe('serverId', invalidate);
    target?.addEventListener('oie-session-changed', invalidate);
    function identity() {
        const names = new Set(['oie-engine', 'oie-engine-url', 'oie-login']);
        const cookies = String(target?.document?.cookie || '').split(';').map(v => v.trim()).filter(v => names.has(v.split('=')[0])).sort();
        return JSON.stringify([platform.store.getState('serverId'), cookies]);
    }
    return {
        capture() { const own = generation, key = identity(); return () => own === generation && key === identity(); },
        close() { unsubscribe?.(); target?.removeEventListener('oie-session-changed', invalidate); generation++; }
    };
}

// Stock API 4.8 does not consume AbortSignal options. Check the frozen action
// around every stage so a stale read cannot start a later execution request.
export function guardApi(api, current) {
    const check = sent => { if (!current()) { const error=new Error('Script inputs or browser session changed. Run the action again.');error.operationSent=sent;throw error; } };
    return Object.fromEntries(['get', 'post', 'put', 'del'].map(method => [method, async (...args) => {
        check(false);
        const value = await api[method](...args);
        check(true);
        return value;
    }]));
}
