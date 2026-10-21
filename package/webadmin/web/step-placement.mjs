/* SPDX-License-Identifier: MPL-2.0 */
// Stock API 4.8 passes a copied step without its path. Read the owning editor's
// live tree selection; the channel's saved elements can lag behind a move.
export function stepEditor(host) {
    return host?.closest('.view-body.flush') || null;
}

export function selectedStepPath(host) {
    const selected = stepEditor(host)?.querySelector('tr.selected[data-path]');
    const path = selected?.getAttribute('data-path');
    return /^\d+(\.\d+)*$/.test(path || '') ? path.split('.').map(Number) : [];
}

export function placementError(context) {
    if (!context.role?.endsWith('transformer') && !context.role?.endsWith('-filter')) return '';
    if (context.role?.endsWith('-filter')) {
        if (!context.elementPath?.length) return 'Select this Python rule in the filter editor before validating or testing.';
        return context.elementPath.length > 1 ? 'Python rules cannot be placed inside Iterator rules. Move the Python rule to the top level.' : '';
    }
    if (!context.elementPath?.length) return 'Select this Python step in the transformer editor before validating or testing.';
    return context.elementPath.length > 1
        ? 'Python steps cannot be placed inside Iterator steps. Move the Python step to the top level.' : '';
}
