/* SPDX-License-Identifier: MPL-2.0 */
import { installPythonServices } from './python-services.mjs';

export function parsePythonLanguage(text) {
    const value = JSON.parse(text, (_key, item) => {
        if (item && typeof item === 'object' && Object.hasOwn(item, '$regexp')) {
            if (Object.keys(item).length !== 2 || typeof item.$regexp !== 'string' || typeof item.$flags !== 'string') {
                throw new Error('Invalid Python language regular expression.');
            }
            return new RegExp(item.$regexp, item.$flags);
        }
        return item;
    });
    if (value?.schemaVersion !== 1 || !value.configuration || !value.tokensProvider?.tokenizer) {
        throw new Error('Invalid Python editor language asset.');
    }
    return { configuration: value.configuration, tokensProvider: value.tokensProvider };
}

export function registerPythonEditorLanguage(platform, context) {
    if (typeof platform.registerEditorLanguage !== 'function' || typeof context?.getAssetText !== 'function') {
        throw new Error('Python Runtime requires the plugin language and asset contracts in Web Administrator API 4.10.');
    }
    platform.registerEditorLanguage('python', {
        aliases: ['Python'], extensions: ['.py'],
        load: async () => {
            const language = parsePythonLanguage(await context.getAssetText('web/python-language.json'));
            installPythonServices(globalThis.window?.monaco);
            return language;
        }
    });
}
