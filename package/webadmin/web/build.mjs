/* SPDX-License-Identifier: MPL-2.0 */
import { build } from 'esbuild';
import { copyFileSync, readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
const pom = readFileSync(new URL('../../../pom.xml', import.meta.url), 'utf8');
const version = pom.match(/<artifactId>oie-python-toolkit<\/artifactId>\s*<version>([^<]+)<\/version>/)?.[1];
if (!version) throw new Error('Cannot read Python toolkit version');
const manifestUrl = new URL('../plugin.json', import.meta.url);
const manifest = JSON.parse(readFileSync(manifestUrl, 'utf8'));
manifest.version = version;
writeFileSync(manifestUrl, JSON.stringify(manifest, null, 2) + '\n');
// Ship only the Python grammar as plugin-owned data. The host provides the
// editor runtime and a generic lazy language contract; no Python asset belongs
// in its distribution. JSON also works through authenticated WAR asset reads,
// without relative imports from the WAR's temporary plugin module or eval.
const require = createRequire(import.meta.url);
const grammarPath = require.resolve('monaco-editor/languages/definitions/python/python.js');
const configurationPath = require.resolve('monaco-editor/editor/common/languages/languageConfiguration.js');
const editorApiPath = require.resolve('monaco-editor/editor/editor.api.js');
const monacoPackagePath = resolve(dirname(editorApiPath), '../../../package.json');
const monacoPackage = JSON.parse(readFileSync(monacoPackagePath, 'utf8'));
const grammar = await build({ entryPoints: [grammarPath], bundle: true, write: false, format: 'esm', platform: 'node', target: 'es2022', metafile: true,
    plugins: [{ name: 'language-configuration-only', setup(builder) {
        builder.onResolve({ filter: /editor\.api\.js$/ }, args => {
            if (resolve(args.resolveDir, args.path) === editorApiPath) return { path: 'language-configuration', namespace: 'python-grammar' };
        });
        builder.onLoad({ filter: /.*/, namespace: 'python-grammar' }, () => ({
            contents: `import { IndentAction } from ${JSON.stringify(configurationPath)}; export const languages = { IndentAction };`, loader: 'js', resolveDir: dirname(configurationPath)
        }));
    } }]
});
if (Object.keys(grammar.metafile.inputs).some(path => resolve(path) === editorApiPath)) throw new Error('Python language data must not bundle the Monaco editor runtime.');
const { conf, language } = await import('data:text/javascript;base64,' + Buffer.from(grammar.outputFiles[0].text).toString('base64'));
const asset = { schemaVersion: 1, monacoVersion: monacoPackage.version,
    sourceSha256: createHash('sha256').update(readFileSync(grammarPath)).digest('hex'), configuration: conf, tokensProvider: language };
writeFileSync(new URL('python-language.json', import.meta.url), JSON.stringify(asset, (_key, value) => {
    if (value instanceof RegExp) return { $regexp: value.source, $flags: value.flags };
    if (typeof value === 'function') throw new Error('Python language data cannot contain executable functions.');
    return value;
}) + '\n');
copyFileSync(resolve(dirname(monacoPackagePath), 'LICENSE'), new URL('python-language.LICENSE.txt', import.meta.url));
await build({ entryPoints: [new URL('plugin.jsx', import.meta.url).pathname], outfile: new URL('plugin.js', import.meta.url).pathname,
 bundle: true, format: 'esm', target: 'es2022', jsxFactory: 'React.createElement', jsxFragment: 'React.Fragment',
 external: ['@oie/web-api', '@oie/web-ui', '@oie/web-shell'], banner: { js: '/* SPDX-License-Identifier: MPL-2.0 */' } });
