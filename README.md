# OIE Python Toolkit

Python filter rules and transformer steps, reusable **Python Function** code templates, a polling **Python Reader**, a **Python Writer**, and shared dependency profiles for Open Integration Engine. The extension is named **Python Toolkit** in the engine's extension manager; the administrator settings page is **Script Environments**.

Write the script body directly using the supplied context variables. Filters, Readers, Writers, and Python Functions support ordinary `return`; the runtime handles function execution internally, so no named entry point is required. A `return` exits immediately, with normal Python `finally` behavior. Transformer steps continue to mutate or replace `msg`/`tmp` and do not support a top-level `return`. There is no `ctx` object. Scripts run in persistent CPython processes. Each deployed script binds to one immutable prepared environment. Saving or preparing a profile does not change a running channel; redeploy to adopt a new script or dependency lock. Queued sends use the current deployed Writer, with the engine's existing transformer and retry options.

This is a community plugin, independently maintained from the OIE engine. CPython **3.11 or later** is required on each executing node; **3.14 is recommended**. The extension prepares its built-in **Python** environment automatically using the installed interpreter and bundled SDK, without package downloads.

## Developer wiki

Start with the [Python Toolkit developer wiki](docs/wiki/Home.md) for a complete guide to all eight editor contexts and JavaScript-to-Python migration. It includes [map/API equivalents](docs/wiki/JavaScript-to-Python.md), [filters and transformers](docs/wiki/Filters-and-transformers.md), [Reader RawMessage and Writer Response/Status handling](docs/wiki/Connectors.md), [classes and reusable packages](docs/wiki/Functions-classes-and-modules.md), and [IntelliSense, formatting, and sample testing](docs/wiki/Editor-testing-and-troubleshooting.md). A [complete shared-class example package](docs/examples/channel-helpers/README.md) accompanies the guide.

## Build and prerequisites

This is a self-contained plugin for stock OIE **4.6.0** and **4.7.0**, and Web Administrator extension API **4.10** or newer. Published releases have all three plugin JARs signed with SSL.com eSigner; local development builds are unsigned. It uses the web code-template provider contract and supplies its own Python language data, fetched only when a Python editor opens. The Web Administrator supplies a generic editor-language hook and ships no Python grammar asset. It requires no engine patches, replacement engine libraries, or changes to engine configuration. Installed engine libraries are staged under the local Maven coordinate `4.6.0-plugin-api`.

Build with JDK 17, 21, or 25, Maven, Python 3.11 or later for the build helper, and an existing OIE installation:

```sh
python3 tools/build_with_engine.py --engine-install /path/to/oie
```

The helper does not build or modify OIE. It records installed library hashes, runs SDK tests, and verifies the plugin modules. The distributable is `package/target/oie-python-toolkit-1.0.0.zip`; its plugin jars are unsigned. Engine libraries, MyBatis, and Rhino are provided dependencies excluded from the ZIP.

For a fresh build without an existing installation, use the checksum-pinned stock OIE download in the [release guide](docs/releasing.md). GitHub Actions runs the Java and web suites on JDK 17, 21 and 25, SDK tests on CPython 3.11–3.14, and isolated engine qualification on OIE 4.6/4.7 with Python 3.11 and 3.14. Tag releases sign the exact CI artifact, verify it on both engines, and publish the signed ZIP with checksums. Test evidence and installed API hashes are retained.

Use the Web Administrator to edit Python scripts and environments. The package contains shared, server and client libraries. The client library has one placeholder panel each for the Python Reader and Python Writer in the Swing Administrator. Opening and saving an existing connector keeps its Python script, selected environment, and configured polling/queue settings. The Swing Administrator has no Python filter or step editor and no Script Environments settings.

All eight Python source editors use the Web Administrator's shared Monaco editor in Python mode: source and destination filters, source, destination, and response transformers, Python Reader, Python Writer, and Python Function code templates. The plugin registers Python highlighting through the generic language contract and sets four-space indentation, folding, and normal code-editing assistance. Its separate `web/python-language.json` asset and MIT license are packaged inside this plugin; registration and the settings page do not fetch the grammar. Authenticated plugin asset reads support standalone and WAR administrators without executing downloaded JavaScript. If Monaco or the grammar cannot load, an editable text fallback preserves the script. **Validate Python** checks syntax and the selected environment. In a prepared environment, all eight editors provide Python completion, hover, signatures and document/selection formatting through plugin-owned Monaco providers. A bounded engine endpoint uses offline bundled Jedi/parso to analyze standard-library, SDK and selected-environment Python source/stubs without executing the authored source, importing its packages or processing environment startup files. Context globals follow the editor role. Dynamic/native package APIs without source or stubs may have limited suggestions. Selection formatting covers the touched full lines; autopep8 edits are accepted only when the complete document's AST and comment text remain unchanged. Invalid/incomplete source is left intact, and results from changed documents, sessions or environments are discarded. Tooling requires the existing Validate Python permission (plus template editing permission for Python Functions) and a ready environment; it never downloads or installs dependencies.

Install the ZIP through the standard extension manager and restart OIE when prompted. The plugin creates and prepares **Python** automatically, with no requirements or package downloads. New Python rules, steps and connectors select it by default. It uses the approved CPython interpreter already on the engine node; the default policy resolves `python3` on the engine's PATH. CPython 3.11 or later with `venv` and `ensurepip` is required. The UI reports the actual patch version, rather than assuming `python3` means 3.12. The plugin does not bundle an operating-system Python interpreter.

Python 3.14 is recommended for new installations. For additional dependencies or a specific interpreter, configure optional policy in the plugin-owned `appdata/oie-python-toolkit/operator.properties` file. See [operations and deployment limits](docs/operations.md), [testing and support](docs/validation.md), and the [signed release procedure](docs/releasing.md).

## First channel

1. Add a **Python** filter rule or transformer step, or select a **Python Reader/Writer** connector. The built-in Python environment is selected automatically; wait for its automatic preparation to reach **Ready**.
2. Write your script and validate it. **Test** executes the script against synthetic samples; it can still perform the external I/O your script contains. New filter rules start with `return True`; Readers and Writers start with `return None`. Python Function code templates also contain starter code. Transformer editors start empty.
3. Deploy. For additional packages, create a separate profile in **Settings → Script Environments**, select its approved runtime and source, save and prepare it, then select it in the editor. Later dependency changes require preparation and redeployment of consuming channels.

The runtime identifies the installed Python interpreter; the package source identifies where preparation obtains dependency packages. New environments select the sole available runtime and sole configured package source automatically. A single matching option is shown directly; multiple options remain selectable. Saved selections are preserved when engine configuration changes. Preparation and script output appear in bounded, scrollable panels.

Python receives the complete maps directly under the same names used in JavaScript: `globalMap`, `globalChannelMap`, and `configurationMap`, plus `sourceMap`, `channelMap`, `connectorMap`, and `responseMap` when a message is available. Use familiar methods such as `.get`, `.put`, `.containsKey`, and `.remove` on those objects. No shared-input selection is required. Global and global-channel maps are writable; configuration maps and downstream source maps are read-only. Readers receive the shared maps and a fresh writable `sourceMap` before they create messages. Set entries on it to supply source values to every returned `Message`; each `Message.source_map` overrides those defaults for that message. A live Reader starts with `sourceMap.get('pollId')` set to an invocation UUID. The Reader source map is local to that invocation, not storage for later polls. See the [context table and Rhino map semantics](docs/sdk.md#maps), including the remaining differences from live Rhino objects.

Transformer steps use `msg`, `tmp` when an outbound template exists, and the available maps directly:

```python
msg["validated"] = True
channelMap.put("patient_id", msg["patient_id"])
globalChannelMap.put("last_patient_id", msg["patient_id"])
```

Use `has_tmp` before accessing `tmp`. All script types can use `logger.debug/info/warning/error` and `print()` for captured output; live `print()` output is forwarded to the engine log at INFO. Readers return a string, `Message`, a list mixing strings and `Message` objects, or `None`. Empty bare strings emit nothing; `Message("")` emits an empty message. Writers use `payload` and return a string, `Response`, `Status`, or `None`. Returning `None` emits no Reader messages and gives an empty SENT Writer response.

## Python filters

Add a **Python** rule to a source or destination filter. It receives `msg` and the available maps, like a transformer, and accepts the rule when execution reaches the end. Return a boolean to choose explicitly:

```python
return msg.get("active") is True
```

The engine combines rules using the filter's existing AND/OR operators and short-circuit order. Use Python `not` to negate a condition. A false filter rejects the message at that connector; a script error or a non-boolean result fails the filter. New rules start with `return True`; an empty body or fallthrough also accepts. Explicit `return None` or a bare `return` is invalid. Filters run before transformer steps, so check `has_tmp` before using any outbound template.

See the [SDK contract and examples](docs/sdk.md) for templates, XML, Reader messages, Writer responses, and map values.

## Reusable Python functions

In **Code Templates**, create a template and select **Python Function**. Choose its JavaScript function name and script environment, then use `arguments` and return a JSON-compatible value:

```python
return str(arguments[0]).strip().upper()
```

For a function named `normalize_patient`, call `normalize_patient(value)` from JavaScript in a source, destination, or response transformer. Arguments and the return value must be JSON-compatible. The function receives its calling message's maps and the complete shared maps using the same names as JavaScript. It can update writable map entries; assign its returned document value explicitly.

Enable the template library for consuming channels and select the supported transformer contexts. **Validate Python** checks Python syntax and the selected environment. Saving preserves drafts even before an environment is ready. A function whose environment is not ready, or whose source does not compile, does not stop a channel from deploying. A call to that function fails with the reason. Redeploy consuming channels to use changes to Python source or dependencies; active deployments retain their existing snapshot.

If a concurrent template save changes the function snapshot while a channel is deploying, deployment fails before the channel starts. Retry deployment after template saves finish. This prevents a successful deployment from retaining compiled wrappers that disagree with its Python function bindings.

## Scope

Python rules work in source and destination filters; Python steps work in source, destination, and response transformer lists. JSON object/array roots, internal XML, and raw strings are supported. Reader byte payloads and map bytes are supported. Deploy/global scripts, Iterator-nested Python rules or steps, JSON scalar roots, transformer byte-array replacements, and direct Java/Rhino access are rejected.

Dependencies use package constraints, extras, markers, blank lines, and comments. Source distributions, editable installs, VCS/direct-URL/local-path requirements, and pip directives are rejected. Native wheels still require compatible system libraries. CPython workers execute trusted channel-author code with the engine service account's filesystem and network access; they are not a security sandbox.
