# Editor, testing, and troubleshooting

[Wiki home](Home.md) · [Contexts](Contexts-and-message-flow.md) · [Environment readiness](Environments-and-deployment.md)

## IntelliSense and formatting in every Python context

The Web Administrator uses Monaco in Python mode for all eight source editors:

1. Source Python filter rule.
2. Destination Python filter rule.
3. Source Python transformer step.
4. Destination Python transformer step.
5. Destination response Python transformer step.
6. Python Reader.
7. Python Writer.
8. Python Function code template.

Python highlighting, four-space indentation, folding, and editor behavior come from the plugin's language registration. Completion, hover documentation, signature help, document formatting, and selection formatting come from plugin-owned providers backed by a bounded engine service. Use Monaco's suggestion/format actions from its command palette or context menu; key bindings depend on the host/browser platform.

The selected environment must be ready on the contacted node. Python tooling requires **Validate Python Scripts** permission; Python Functions additionally require native code-template editing permission. Ordinary channel/template access restrictions still apply. Read-only editors do not become editable through tooling.

The service uses offline bundled Jedi/parso and formatting tools. It analyzes standard-library definitions, SDK definitions, and selected-environment Python source/stubs without executing the authored script, importing its dependencies, or processing environment startup files. It does not install dependencies. Packages implemented dynamically or in native extensions can have limited IntelliSense unless source/type stubs describe their API.

Context globals match the role: Writer suggestions include `payload`, Function suggestions include `arguments`, and filters/transformers include `msg`/`has_tmp`. Suggestions cannot know every runtime message schema, custom map key, or external response shape. `tmp` still requires an actual outbound template; a hint is not evidence that it exists for this invocation.

Formatting is conservative. Selection formatting covers the full touched lines. Returned edits are accepted only when the whole document's AST and comment text remain unchanged. Invalid/incomplete source is left intact. Results are discarded if the document version, editor session, or selected environment changes before completion; an old request must not overwrite a newer draft.

If Monaco or its grammar cannot load, the editable text fallback preserves the script but cannot provide the full Monaco experience. A functioning text area is not evidence that completion or formatting is available. Check extension assets, runtime readiness, permissions, and diagnostics before assuming an editor defect.

## Save, Validate, Test, and deploy

| Action | What it establishes | What it does not establish |
| --- | --- | --- |
| Save draft | The authored configuration is retained | Environment readiness or executable behavior |
| Validate Python | Source compiles; request/context/function-name rules and selected environment checks pass | Imports succeed; runtime values satisfy result contracts; external systems are reachable |
| Test sample | Executes this script using a synthetic context and reports output/maps/logs | Real channel routing, downstream transforms, response validation, queue retries, or end-to-end delivery |
| Deploy | Captures the channel's script/environment bindings | Every optional Python Function is available; all future inputs succeed |
| Controlled live-channel exercise | Observes actual engine behavior for the tested inputs/settings | Untested data shapes, platforms, failures, and concurrency cases |

Use the plugin's **Validate Python** button for Python checks. The host's generic validation does not substitute for it. Python Function validation is also integrated with the code-template provider. There is no standalone sample-test action for Python Function templates; test their behavior by invoking them in a consuming channel.

Sample execution never dispatches Reader messages or writes live engine maps. It **does execute the code**, including imports and any external HTTP, database, or filesystem I/O. Use safe fixtures, test systems, and controlled credentials. If execution times out or the browser loses the response, external side effects may already have happened; inspect the target before manually retrying.

### Permissions

| Operation | Toolkit permission |
| --- | --- |
| View environments/runtime options and export profiles | **View Python Environments** |
| Create/update/archive/import profiles | **Manage Python Environments** |
| Prepare, inspect/cancel preparation jobs | **Prepare Python Environments** |
| Refresh dependencies | **Prepare Python Environments** and **Manage Python Environments** |
| Validate and Python editor services | **Validate Python Scripts** |
| Execute synthetic sample tests | **Execute Python Scripts** |

Native channel restrictions and code-template management permissions are additional checks, not replaced by these grants. In particular, Validate permission alone does not authorize Test. Engine save/deploy actions retain their existing permissions.

## Sample contexts

The sample input is a JSON object, not Python source. Omitted maps default to empty synthetic maps. Use the field names below exactly. Sample metadata is synthetic; it is not proof that the same key exists in every live context. In particular, a Reader has no live message ID, and its sample `poll` contains `sample: true` instead of the live poll's ID. See the [live metadata matrix](Contexts-and-message-flow.md).

### JSON filter or transformer

Use this for a source/destination JSON filter or transformer with no outbound template:

```json
{
  "msg": {
    "kind": "json",
    "text": "{\"patient_id\":\"P123\",\"name\":\" Ada \",\"active\":true}"
  },
  "sourceMap": {"origin": "sample"},
  "channelMap": {},
  "connectorMap": {},
  "responseMap": {},
  "globalMap": {},
  "globalChannelMap": {},
  "configurationMap": {}
}
```

`msg.text` is serialized JSON **inside a JSON string**, not an embedded object. Root kind `json` requires an object or array. Add `"tmp": {"kind": "json", "text": "{}"}` to model an outbound template. Omitting `tmp` means `has_tmp` is false. A filter preview includes `accepted`; document and map changes are shown separately.

### XML or RAW filter/transformer

```json
{
  "msg": {"kind": "xml", "text": "<Patient><id>P123</id></Patient>"},
  "channelMap": {}
}
```

For RAW, use `"kind": "raw"` and put the raw text in `text`. For HL7/XML-backed datatypes, supply the engine's internal XML, not an ER7 wire message labeled as XML. Match namespaces and structure to the real serializer. A sample invented from a different schema cannot validate a production field path.

### Response transformer

```json
{
  "msg": {"kind": "raw", "text": "accepted"},
  "response": {"status": "SENT", "statusMessage": "Delivered", "error": ""},
  "channelMap": {"patient_id": "P123"}
}
```

The sample wire field `statusMessage` becomes Python `response.status_message`. The response body remains `msg` (or `tmp` with an outbound template); there is no `response.data` in this transformer context. This is separate from the Writer's `Response` class.

### Reader

```json
{
  "destinationIds": [1, 2],
  "globalMap": {},
  "globalChannelMap": {},
  "configurationMap": {"source_name": "sample"}
}
```

`destinationIds` supplies the known destination metadata IDs for validating a Reader result in this sample. It is test metadata, not a new script global. Choose IDs that correspond to your intended live configuration. The output previews messages and map changes; bytes are rendered as base64. It does not exercise live admission, downstream filtering, batch handling, or acknowledgments.

### Writer

```json
{
  "payload": "{\"patient_id\":\"P123\",\"name\":\"Ada\"}",
  "channelMap": {"patient_id": "P123"},
  "sourceMap": {},
  "connectorMap": {},
  "responseMap": {}
}
```

`payload` must be a string or null. The output previews the normalized response and staged map changes. It does not apply native queue/response-validation settings or run a response transformer. A sample returning QUEUED is not evidence that a live destination with queueing disabled will stay QUEUED.

Any message-context sample can also supply `destinationIdMap`, for example `{"Archive": 2}`, to exercise `responseMap` lookups by destination name. This is lookup metadata only. Native Java response objects cannot be represented by ordinary sample JSON; see [Maps and values](Maps-and-values.md).

## Focused developer validation

Cover each behavior your channel depends on, including failures. Useful cases are:

| Behavior | Cases to exercise |
| --- | --- |
| Filters | True, false, missing fields, exceptions, non-boolean result, AND/OR short circuit, mutations on false |
| Documents | Actual JSON/RAW/XML datatype; with/without `tmp`; missing/repeated/namespaced XML fields; a later JavaScript step reading the updated root |
| Maps | Read-only mutation rejected; unsupported native value; nested change; absent versus null; concurrent updates to the same key |
| Reader | None/empty list, empty payload, text/bytes, destination subset/none, malformed later list entry, partial live admission, duplicate source records |
| Writer | Success, permanent error, retriable outcome, response validation, queue enabled/disabled, queue-first, timeout after external side effect, manual reprocessing |
| Functions | Each selected calling context, missing/invalid arguments, class-to-dict conversion, invalid return type, unprepared environment, save before/after redeploy |
| Deployment | Updated requirements, node-local readiness, concurrent saves, restart, stop/start, archive references, saved versus deployed source |

Exercise engine-dependent behavior with the same datatypes, routing, queue, and retry settings as the intended deployment. The [validation guide](../validation.md) contains repository-level suites and supported test configurations.

## Logs and errors

All scripts can use `logger.debug`, `logger.info`, `logger.warning`, and `logger.error`; `warn` is a compatibility alias. Use Python `%` arguments, such as `logger.info("Processed %s", patient_id)`, or an f-string. The helper does not expose the full standard-library `logging.Logger` API or `logger.exception()`. `print()` and writes to stdout/stderr are captured at INFO. Captured output is bounded and can be truncated; DEBUG visibility also depends on engine logging configuration.

| Context | Live engine log category |
| --- | --- |
| Native source/destination filter | `filter` |
| Source/destination transformer | `transformer` |
| Response transformer | `response` |
| Reader/Writer | `js-connector` |
| Python Function | Calling transformer phase, including functions invoked by JavaScript filters |

Test results show diagnostics, captured logs, and a preview of valid changes. Source line information and traceback diagnostics help distinguish syntax, import, script, and contract errors. Avoid logging complete payloads or secrets; log a useful correlation identifier and the operation instead.

## Troubleshooting recipes

| Symptom | Check and action |
| --- | --- |
| No completion or formatting | Environment readiness on this node; Validate Python permission; template-edit permission; plugin service/asset availability. Native package without stubs may have limited hints. |
| Formatting makes no change | Source may already match formatting, be incomplete, or require a change rejected by the AST/comment guard. Resolve syntax first. |
| `NameError: msg` in a Writer/Function | Writer uses `payload`; Function uses `arguments`. Use the context matrix rather than copying a transformer body unchanged. |
| `NameError: tmp` | Check `has_tmp`; configure an outbound template if needed. |
| Top-level `return` syntax error | Assign `result` for result-bearing contexts; transform `msg`/`tmp` directly. `return` is valid inside your own function. |
| Import works locally but fails on engine | Correct profile selected on this script; package included and prepared on that node; Python/ABI/system-library compatibility; redeployment completed. |
| Filter result rejected | Assign exactly `True`/`False`. `1`, `None`, and truthy objects are not booleans. |
| Writer reports SENT after returning `"ERROR"` | A string is response data. Use `Status.ERROR` or `Response(status=Status.ERROR, ...)`. |
| Reader result rejected | Wrap payloads in `Message`; validate source-map keys, routing IDs, list size, and binary/batch combination. |
| `UnsupportedValueError` on responseMap or another map | A native Java value is not a Python proxy. Extract supported data in an earlier JavaScript step or store plain values. |
| Source changes appear ignored | Save the correct channel/template, then redeploy its consumers. Stop/start alone keeps the old binding. |
| Function name absent or call unavailable | Library enabled for channel; matching context selected; valid unique function name; ready environment; source compiles; channel redeployed. |
| Output changed but an earlier JavaScript alias did not | Python replaces returned document roots. Re-read `msg`/`tmp` in later JavaScript code. |
| Timeout or disconnected Test | External outcome can be uncertain. Inspect the external system before retrying; use idempotency and explicit client timeouts. |
| Environment cannot archive | Inspect saved references, including disabled rules/steps and unassigned templates, active preparation, and deployed leases. |

Implementation references: [editor component](../../package/webadmin/web/python-editor.jsx), [Monaco providers](../../package/webadmin/web/python-services.mjs), [analysis/format service](../../server/src/main/resources/python-editor/service.py), [sample-context builder](../../server/src/main/java/com/mirth/connect/plugins/python/server/SampleContext.java), and [validation/test API](../../server/src/main/java/com/mirth/connect/plugins/python/server/RuntimeApi.java).
