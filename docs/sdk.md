# Python SDK contract

For complete context-specific examples and JavaScript equivalents, see the [developer wiki](wiki/Home.md), especially [Functions, classes, and modules](wiki/Functions-classes-and-modules.md) and [Maps and values](wiki/Maps-and-values.md).

The bundled `oie_python` SDK has no third-party dependencies. Write the script body directly using the supplied context variables. Filters, Readers, Writers, and Python Functions support ordinary `return`; the runtime handles function execution internally, so no named entry point is required. A `return` exits immediately, with normal Python `finally` behavior. Transformer steps continue to mutate or replace `msg`/`tmp` and do not support a top-level `return`. There is no `ctx` object. Imports, classes, and helper functions are allowed; call your own helpers explicitly. Validation compiles the script without evaluating imports or top-level code. Execution uses fresh script globals; imported modules can remain cached until the worker is recycled. Persist durable state outside the process.

All script types receive `logger`, `phase`, read-only `message` metadata, and the complete `globalMap`, `globalChannelMap`, and `configurationMap`. Filter, Reader, Writer, and Python Function scripts return their output.

`phase` is optional context metadata; scripts do not need to reference it. Python supplies a string identifying the execution surface, such as `source-filter` or `destination-transformer` (see the [context matrix](wiki/Contexts-and-message-flow.md)). Stock OIE 4.6/4.7 JavaScript also exposes `phase` in source/destination filters and transformers, but as a Java array: `phase[0]` is `"filter"` or `"transformer"`. JavaScript response transformers, Readers, and Writers do not receive that binding. Python exposes its surface string in those contexts too. Rebinding the Python name only changes the script binding; it does not change engine routing or error classification.

| Context | Other available variables |
| --- | --- |
| Source / destination filter | `msg`, `has_tmp`, `tmp` when available |
| Source / destination transformer | `msg`, `has_tmp`, and `tmp` when a template exists |
| Response transformer | Transformer variables plus `response` |
| Reader | Writable invocation-local `sourceMap` |
| Writer | `payload` |
| Python Function | `arguments` |

`Message` is available automatically in Readers; `Response` and `Status` in Writers; and `Status` in response transformers. Every script receives `UnsupportedValue` for inspecting unsupported engine map values. Explicit imports from `oie_python` remain supported and are required in separately imported modules. Other SDK types, including exception classes, require explicit imports.

`message` contains metadata, not a live Java `connectorMessage`. The [map availability table](#maps) below covers all seven map variables.

## Filters

Select a **Python** rule in a source or destination filter. An empty rule or execution that reaches the end accepts as `True`. An explicit return must be exactly `True` or `False`; truthy strings, numbers, `None`, and other values are rejected, including a bare `return`. New rules start with `return True`.

```python
return msg.get("active") is True and not msg.get("cancelled", False)
```

The stock engine controls AND/OR precedence and short-circuit evaluation across Python and other rule types. A false overall source filter prevents destination processing; a false destination filter skips that destination. Exceptions and invalid results produce a filter error, rather than a normal rejection. There are no response filter rules. Iterator-nested Python rules are unsupported.

Filters receive the same document and map objects as transformers below, with the same validation and mutation rules. Valid changes apply even when the rule returns `False`; an exception or invalid result applies no changes from that invocation. Filters run before transformer steps, so an outbound template may not yet exist. Check `has_tmp` before using `tmp`. **Test** returns the rule's `accepted` boolean and previews document/map changes without updating live shared maps.

## Transformers

`msg` is the current JSON dict/list, internal XML string, or raw string. `tmp` holds the outbound template in the same form when one exists; check `has_tmp` before accessing it. Mutate JSON in place or assign a supported replacement to `msg` or `tmp`.

```python
msg["validated"] = True
channelMap.put("checked", True)
```

The step also receives available `channelMap`, `connectorMap`, `sourceMap`, and `responseMap` objects. Response transformers additionally receive `response`. There is no automatic `output` alias; transformer output comes from `msg` and `tmp`. An empty step is a no-op.

Assigning `msg` or `tmp` replaces that document. Deleting either document, or assigning `tmp` when no template exists, is rejected. Change map keys or response fields through the supplied objects; rebinding a map, `response`, or another helper name only changes your script's local binding.

JavaScript steps and Python steps run in transformer order. The Python step receives the current `msg` and `tmp`, including edits from earlier JavaScript steps. E4X XML/XMLList objects are serialized with `toXMLString()`; Python receives strings, not live JavaScript objects. Changed XML is validated and rebuilt as the original E4X type before the next JavaScript step. Both documents are checked before either document or any map delta is applied.

For XML, parse the provided internal XML text explicitly. HL7 v2 channels expose the engine's internal XML here, not the original pipe-delimited message. The standard library is sufficient:

```python
import xml.etree.ElementTree as ET

document = ET.fromstring(msg)
document.set("validated", "true")
msg = ET.tostring(document, encoding="unicode")

if has_tmp:
    template = ET.fromstring(tmp)
    template.set("validated", "true")
    tmp = ET.tostring(template, encoding="unicode")
```

Assigning an `ElementTree.Element` directly is also supported. Editing a separately parsed tree alone does not update the context; assign it or its serialized text back. With an outbound template, the engine sends `tmp`, so changing only `msg` does not automatically populate the outgoing template.

Unchanged XML documents retain their original JavaScript objects. Changed documents replace the `msg`/`tmp` bindings; a JavaScript variable holding an earlier root or child still references the old tree. Read the current `msg`/`tmp` in following steps. XML libraries may change prefixes, formatting, comments, or processing instructions when parsing and serializing; this is not a byte-for-byte XML editor. An XMLList can contain multiple roots or be empty, so it requires fragment-aware processing rather than a single `ET.fromstring(msg)` call.

Response transformers expose `response.status`, `response.status_message`, and `response.error`. Only the supported engine response statuses may be assigned. All result documents, response fields, and map deltas are validated before any map update. Invalid XML, unsupported roots, unsafe numbers, cycles, and excessive nesting/frames fail with a field diagnostic.

## Maps

Maps use the existing JavaScript names directly; there is no selection step or extra context object. All script types receive every string-keyed entry in `globalMap`, `globalChannelMap`, and `configurationMap`. Filters, transformers, Writers, and Python Functions additionally receive `channelMap`, `connectorMap`, `sourceMap`, and `responseMap` from their current message. Readers also receive a fresh writable `sourceMap` for source values inherited by their returned `Message` results; they have no other per-message maps.

| Variable | Filter / transformer / response transformer | Reader | Writer | Python Function | Access |
| --- | --- | --- | --- | --- | --- |
| `globalMap` | Yes | Yes | Yes | Yes | Read/write |
| `globalChannelMap` | Yes | Yes | Yes | Yes | Read/write |
| `configurationMap` | Yes | Yes | Yes | Yes | Read-only |
| `channelMap` | Yes | — | Yes | Yes | Read/write |
| `connectorMap` | Yes | — | Yes | Yes | Read/write |
| `sourceMap` | Yes | Yes | Yes | Yes | Read/write in Reader; read-only downstream |
| `responseMap` | Yes | — | Yes | Yes | Read/write |

`globalMap`, `globalChannelMap`, `channelMap`, `connectorMap`, and `responseMap` are writable. `configurationMap` is read-only. `sourceMap` is writable only in a Reader; downstream scripts receive it read-only. For example:

```python
site = configurationMap.get('site')
previous = globalChannelMap.get('last_patient_id')
globalMap.put('last_site', site)
globalChannelMap.put('last_patient_id', channelMap.get('patient_id'))
if channelMap.containsKey('temporary'):
    channelMap.remove('temporary')
```

The same objects support the ordinary Rhino map methods: `get`, `put`, `containsKey`, `containsValue`, `remove`, `putAll`, `clear`, `size`, `isEmpty`, `keySet`, and `entrySet`. A missing `get(key)` returns `None`, corresponding to JavaScript's `null`. On message maps, `put` and `remove` return the previous value, or `None` when absent. On `globalMap` and `globalChannelMap`, they return `None`, matching the engine's void methods. These global stores also provide `getVariables()`, a read-only view that reflects changes made within the current invocation. Mutators on read-only maps reject writes, including through an entry's `setValue`.

`keySet()` and `values()` return Python iterable views. Iterate `entrySet()` with a Python `for` loop and call `getKey()`, `getValue()`, or `setValue(value)` on its entries. Full Java collection/iterator APIs are not provided. Python dictionary access remains available on the same map objects: `channelMap['key']`, `.items()`, `.update(...)`, `del`, and `.pop(...)`. Unlike `.get`, a missing bracket lookup raises `KeyError`.

`responseMap.get('Destination name')` and `containsKey` resolve the engine's destination name to its `d<metadataId>` key. Its keys and size still describe the actual entries, and writes/removals use the literal key, as in Rhino. `channelMap.get` retains the engine's source-map fallback for missing channel entries and logs the engine's deprecation error; `containsKey` and `keySet` only report actual channel entries. Use `sourceMap.get` for source entries.

CPython runs in a separate process, so each invocation reads snapshots. Changes to writable message/shared map entries are validated together with the script result, then written to the actual engine stores before the next step runs. A Reader's invocation-local `sourceMap` instead supplies defaults to each emitted message; it does not update shared storage. A script exception or invalid result applies no map changes. Only changed keys are written; unrelated concurrent updates are preserved. Reads do not refresh during an invocation, and concurrent writes to the same key use the last committed value; read-modify-write counters are not atomic. These are meaningful differences from Rhino's live map access. Global stores are node-local and follow the engine's existing lifetime and redeployment behavior. They reject `None` values; use `.remove(key)` to remove a key. These maps do not expose Java objects or the engine's synchronized-store methods.

Synthetic Test accepts `globalMap`, `globalChannelMap`, and `configurationMap` in its sample JSON and previews changes without reading or modifying live shared stores. For message contexts, optional sample `destinationIdMap` maps destination names to integer metadata IDs so response-map name lookup can be tested; it is lookup metadata, not another script variable.

Map values support null, booleans, strings, signed 64-bit integers, finite floating-point values, tagged bytes, dictionaries with string keys, and lists. Tagged map/metadata transport preserves signed 64-bit integers exactly; JSON documents and Python Function arguments/results instead reject integers outside ±(2^53−1). Unsupported engine values appear as `UnsupportedValue`; unrelated original values remain in the engine. Reading/converting a sentinel raises `UnsupportedValueError`, while assigning it to another supported map key can preserve the original engine value. Nested edits in supported dict/list values are detected. See the [detailed map and value guide](wiki/Maps-and-values.md) for method semantics, provenance, concurrency, and conversion examples.

Familiar map access does not make Python a Rhino interpreter. JavaScript shortcuts such as `$g`, `$gc`, `$c`, `$co`, `$s`, `$r`, `$cfg`, and `$` are not valid Python identifiers; use the existing map's `.get` or `.put` methods. E4X and arbitrary Java object methods also remain unavailable. For example, native engine response objects in `responseMap` remain unsupported values, not Python objects with Java response methods. Equality and collection behavior follow Python for supported values.

All scripts can use `logger.debug/info/warning/error`. `print()` and writes to standard output/error are captured as `stdout` records and forwarded to the engine's existing script logs at INFO; explicit logger calls retain their level. Logs follow the script's surface, including the calling phase for Python Functions. Sample Test includes captured records in its result. Output is bounded and can be truncated, and DEBUG visibility in the engine log follows its logging configuration. Script logs may contain sensitive application data; operator retention policy applies. Message bodies are not logged automatically.

## Reader

Return `None`, a string, one `Message`, or a bounded list mixing strings and `Message` objects. `None`, an empty list, or an empty bare string emits no messages; empty bare strings in a list are skipped; an empty string/bytes payload in a `Message` emits a real message. A Reader receives a fresh writable `sourceMap` with `pollId` set to a new UUID in live execution, or `"sample"` in synthetic Test. There is no `poll` script global. Set entries through `sourceMap.put(...)` or `sourceMap["key"] = value`; rebinding `sourceMap` only changes the local name. Every returned `Message` inherits the final map values, and its own `source_map` overrides matching keys, including `pollId`. This map is not storage for later polls. It accepts supported values including `None`; invalid values or reserved keys fail validation even when returning `None` or `[]`.

```python
return Message(
    '{"patient_id":"P123"}',
    source_map={"origin": "python-reader"},
    destination_ids=None,
)
```

`destination_ids=None` selects all enabled destinations; `[]` selects none; a list selects known numeric metadata IDs. Reserved keys in the Reader `sourceMap` and each `Message.source_map` (`destinationSet`, `batchId`, `batchComplete`, `batchSequenceId`, `reprocessed`, and `replaced`) are rejected. Binary payloads use the engine's base64 representation and are forbidden with Process Batch enabled.

`Message` is the Python counterpart of the JavaScript Reader's `RawMessage`. Its `payload` becomes the engine's raw text or raw bytes, `destination_ids` becomes the destination metadata ID list, and `source_map` overrides the invocation defaults to form the source map. `Message` is supplied automatically in Reader scripts; no Java object or additional RawMessage wrapper is needed. Use `return "hello"` for plain text, or `return ["one", "two"]` for multiple text messages. Strings inherit `sourceMap` and select all enabled destinations. Use `Message(...)` for binary data, per-message source values, destination selection, or an explicitly empty payload. The complete supplied list counts toward the poll limit, including skipped empty strings.

The complete list and map changes are validated before the first dispatch. Shared map changes commit before dispatch; an admission failure does not roll them back. The worker is released before engine admission, and each dispatch is finalized. If a later admission fails, earlier messages remain admitted and the poll reports partial completion. The toolkit does not replay the list or acknowledge/checkpoint an external source automatically.

## Writer

`payload` is the outgoing engine string, or `None` when no payload is available. Return `None` for SENT with empty data, a string for SENT with that data, an explicit `Response`, or a bare `Status` for that status with empty data. Dictionaries and bytes are rejected; serialize JSON yourself.

```python
# Perform your operation using payload.
return Response(status=Status.SENT, data="accepted", status_message="Completed")
```

`Response` maps `status`, `data`, `status_message`, and `error` to the engine response's status, message, status message, and error fields. Its text fields accept strings or `None` (`None` becomes empty text in the engine). Statuses are SENT, ERROR, or QUEUED, supplied as strings or `Status` members. Other engine lifecycle statuses are not valid Writer results.

For a status-only result, use `return Status.ERROR` (or `Status.SENT` / `Status.QUEUED`). A plain string such as `return "ERROR"` remains a SENT response with that string as its data. QUEUED becomes ERROR when queueing is disabled. The Java adapter applies effective engine response-validation settings and releases its worker before response transformation.

The engine can immediately retry ERROR or QUEUED according to the destination's retry count. Its asynchronous queue retries QUEUED responses; ERROR alone does not request queueing. A timeout after external I/O has an uncertain outcome. Use the available message/destination identity with target-supported idempotency, including manual reprocessing. The toolkit never silently re-executes an invocation on a replacement worker.

## Callable Python code templates

The web Administrator's **Python Function** template reads `arguments` and returns a JSON-compatible value:

```python
patient = arguments[0]
return {"id": patient["id"], "name": patient["name"].strip()}
```

Set its JavaScript function name to `normalize_patient`, enable its library for the consuming channel, and deploy that channel. A JavaScript filter or transformer can then call `normalize_patient({id: '123', name: ' Example '})`. The return value is a native JavaScript JSON value. The engine's RAW string is accepted directly. Convert E4X XML explicitly, for example `normalize_xml(msg.toXMLString())`.

`arguments` contains the positional JSON arguments. `message` metadata is read-only; `phase`, `logger`, the calling message's maps, and the shared maps are also available. A function can change writable map entries but does not receive `msg` or `tmp`. Return a value and explicitly apply the returned document value in the calling JavaScript. Map changes are committed only after a valid JSON result. Returning `None` or falling through returns JSON null. Arguments and results support null, booleans, strings, finite numbers, arrays and objects with string keys. Cycles, undefined, callables, non-JSON objects, non-finite numbers, and integers beyond JavaScript's safe range are rejected. Python exceptions and invalid results fail the calling script; external I/O performed by user code cannot be rolled back.

Supported contexts are source filter/transformer, destination filter/transformer, and destination response transformer. The runtime ignores other selected contexts. A template with no selected context is not bound. Global/channel lifecycle scripts, Readers and Writers cannot call Python templates. Each enabled template is captured at channel deployment with its source, execution digest and prepared environment lease. A template that cannot bind does not stop the channel deployment. For example, its environment is not ready on the node, or its source does not compile. A call to that function fails and reports the reason. Correct the cause, then redeploy. Saving a template does not change an already deployed channel. Redeploy to adopt saved changes. If a concurrent template save changes a function snapshot while the stock engine compiles the channel, deployment fails before the channel starts. Retry deployment after template saves finish.

The template **Validate** action only compiles Python and checks contexts, function name and environment readiness; it does not import or execute the script. It requires both Python script validation permission and the engine's native code-template management permission. Validate/test a function's actual behavior through its consuming channel. Environment archive checks include saved templates, even when they are not currently assigned to a library, and deployed template environment leases remain protected until retirement.
