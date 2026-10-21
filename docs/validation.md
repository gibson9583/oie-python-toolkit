# Testing and support

When changing script execution, cover native source/destination filters, Reader, Writer, and Python Function calls, with early exit, explicit `None`, fallthrough, `finally`, filter acceptance on empty/fallthrough paths and rejection of explicit null returns, and transformer rejection of top-level `return`. Exercise validation, editor tooling, deployment, and live runtime behavior together.

For editor validation, synthetic sample JSON, and channel-author checks, see [Editor, testing, and troubleshooting](wiki/Editor-testing-and-troubleshooting.md). The [developer wiki](wiki/Home.md) covers all Python execution contexts.

## Run the tests

The build requires JDK 17 or later, Maven, and CPython on the `PATH` as `python3`. Maven installs Node.js for the web bundle.

1. Stage the installed engine libraries and run every suite:

   ```sh
   python3 tools/build_with_engine.py --engine-install /path/to/oie
   ```

   The helper requires Python 3.11 or later. It reads the engine JARs and does not change the installation.
2. To run the suites again after staging, run `mvn verify` in the repository root. The build writes the extension to `package/target/python-runtime-0.1.0.zip`.
3. To run only the SDK tests, run:

   ```sh
   PYTHONPATH=python-sdk python3 -m unittest discover -s python-sdk/tests -v
   ```

4. To run only the web unit tests from a fresh checkout, run `npm ci`, `npm run build`, then `npm test` in `package/webadmin`. The build generates the plugin bundle, Python language data, and its license; these outputs are not versioned. After dependencies and generated assets are current, use `npm test` directly.
5. To check the extension package, run `python3 tools/inspect_plugin.py`. It confirms that the ZIP contains only the three unsigned toolkit JARs and plugin-owned classes.

The server tests start real CPython processes. They create private Derby databases and environment caches. Environment caches are removed by their owning tests. After the test JVM exits, remove any private Derby database paths listed in its ownership receipts only after preserving final row observations and confirming shutdown. Do not remove shared or active resources.

## Browser and engine checks

The browser checks require a sibling Web Administrator build. Run them in `package/webadmin`:

- `node test/browser-code-template.mjs` checks the Python Function template editor.
- `npm run test:browser:editors` checks all eight Python editors.
- `npm run test:browser:ui` checks blank sources, environment choices and output panels.

`OIE_WEB_HOST` sets the Web Administrator repository. `OIE_BROWSER_EXECUTABLE` sets the Chromium executable. `OIE_WEB_ART` sets the output directory. The default output directory is `validation/`, which Git ignores.

`tools/run_live_engine_integration.py` checks an installed engine. It creates its own profiles and channels. It does not install, start, stop or delete the engine. Use `--inventory-only` for a read-only check.

## Supported configuration

- Stock OIE 4.6.0 or 4.7.0 with Web Administrator extension API 4.10 or newer. The Swing Administrator shows only placeholder panels for the Python Reader and Python Writer.
- JDK 17 or later.
- CPython 3.9 or later with `venv` and `ensurepip` on each engine node.
- POSIX hosts. The test suites run on macOS ARM64. The runtime reports Windows interpreters as unavailable.
- The Derby, MySQL, PostgreSQL, Oracle and SQL Server engine databases. The test suites use Derby.

## Known limits

- These items have no measurements: multi-node database races, native package compatibility, load, memory use, uninterruptible I/O, and child processes that leave their session.
- The service keeps the IDs of retired deployments and finished preparation jobs in memory until the plugin stops.
- A package source with credentials in its index URL gets a new policy identity when the credentials change. Prepare the environments again, then redeploy.

The maintained isolated runners `tools/run_filter_isolated.py`, `tools/run_reader_message_isolated.py`, and `tools/run_writer_response_isolated.py` exercise native filters and connector result contracts against a supplied stock engine archive. Use `tools/run_return_isolated.py` for script return behavior, and `tools/run_editor_services_isolated.py` for editor services and Monaco checks. A runner's presence does not establish that a release passed; retain results for the exact plugin and engine inputs tested. Each uses the common isolated lifecycle, preserves evidence and immutable input hashes, and removes its owned installation and database after process exit. Run against both supported stock versions with `--engine`, `--java-home`, `--python`, and a fresh `--evidence` directory.
