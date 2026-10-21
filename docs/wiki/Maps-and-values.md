# Maps and values

[Wiki home](Home.md) · [JavaScript to Python](JavaScript-to-Python.md) · [Contexts and message flow](Contexts-and-message-flow.md)

Use `channelMap`, `connectorMap`, `sourceMap`, `responseMap`, `globalMap`, `globalChannelMap`, and `configurationMap` directly in Python. They retain their familiar engine names and ordinary Rhino-style methods. The CPython worker receives a supported-value snapshot for each invocation; it does not receive live Java map objects.

## Availability in all eight contexts

**RW** means readable and writable. **R** means readable only. **—** means the variable is not supplied.

| Map | Source filter | Destination filter | Source transformer | Destination transformer | Response transformer | Reader | Writer | Python Function |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `globalMap` | RW | RW | RW | RW | RW | RW | RW | RW |
| `globalChannelMap` | RW | RW | RW | RW | RW | RW | RW | RW |
| `configurationMap` | R | R | R | R | R | R | R | R |
| `channelMap` | RW | RW | RW | RW | RW | — | RW | RW |
| `connectorMap` | RW | RW | RW | RW | RW | — | RW | RW |
| `sourceMap` | R | R | R | R | R | RW | R | R |
| `responseMap` | RW | RW | RW | RW | RW | — | RW | RW |

Python Functions receive the calling message's maps in their supported filter/transformer contexts. Readers run before a message exists and receive shared maps plus a fresh writable `sourceMap`, initially containing a live invocation UUID at `pollId` (`"sample"` in synthetic Test). Mutate this supplied map to set common source values on all emitted messages. Each `Message(..., source_map={...})` overrides inherited entries for that message, including `pollId`. Downstream Python receives the resulting source map read-only; JavaScript can read it with `sourceMap.get("key")`. The Reader map is local to one invocation and does not carry state into later polls. There are no response filter rules or Python lifecycle-script surfaces.

| Map | What it represents | Typical use |
| --- | --- | --- |
| `globalMap` | Engine-wide shared variables on the current node | Read/write a node-local shared value such as `globalMap.put("site", "east")`. |
| `globalChannelMap` | Shared variables for the current channel on the current node | Read/write channel state such as `globalChannelMap.put("lastSource", "poller")`. |
| `configurationMap` | Engine configuration entries | Read `configurationMap.get("site")`; manage values through the engine's administrative configuration. |
| `channelMap` | Current message's channel-level variables | Pass supported data between steps/destinations, for example `channelMap.put("patientId", "P123")`. |
| `connectorMap` | Current connector's message variables | Store local request data, for example `connectorMap.put("requestId", "R1")`. |
| `sourceMap` | Source metadata; writable defaults in a Reader, read-only on a current message | Set `sourceMap.put("origin", "reader")` in a Reader; read `sourceMap.get("origin")` downstream. Use `Message.source_map` for per-message overrides. |
| `responseMap` | Current message's responses and response-related entries | Read by destination name or literal key; store application entries with literal-key writes. Native Java response objects remain unsupported sentinels. |

All string-keyed entries are snapshotted; there is no map-selection step or key allowlist in script code. Non-string engine map keys are omitted. Shared map lifetimes follow the engine's existing configuration and redeployment behavior. They are node-local, not a cluster-wide or durable transaction store. Avoid assuming that a global survives a restart or redeploy.

## Familiar reads and writes

```python
# Filter / transformer / response transformer / Writer / Python Function.
site = configurationMap.get("site", "unknown")
patient_id = channelMap.get("patientId")
channelMap.put("site", site)
connectorMap["requestId"] = "request-123"
globalChannelMap.put("lastSite", site)

if channelMap.containsKey("temporary"):
    channelMap.remove("temporary")
```

These are supplied variables, not imports. No `ctx`, `maps.select`, `$c`, `$g`, or generic `$` helper is created. [JavaScript to Python](JavaScript-to-Python.md#map-shortcuts) maps every JavaScript shortcut to the corresponding object.

Rebinding a local name does not replace the engine map:

```python
# Correct: mutate entries in the supplied object.
channelMap.update({"patientId": "P123", "checked": True})
```

This also applies to the Reader's `sourceMap`: mutate the supplied object instead of assigning a replacement dictionary. Source values can include `None`; reserved engine source keys and unsupported values fail validation even if the Reader returns no messages. See [Reader source values](Connectors.md#initial-source-values-and-reserved-keys).

`channelMap = {"patientId": "P123"}` only changes the local binding. The toolkit extracts changes from the original map object, so this assignment is not an engine-map replacement.

### Missing keys, `None`, and truthiness

| Operation | Present value `None` | Absent key |
| --- | --- | --- |
| `channelMap.get("key")` | `None` | `None` |
| `channelMap.get("key", "fallback")` | `None` | `"fallback"` |
| `channelMap["key"]` | `None` | Raises `KeyError`, unless the deprecated source fallback finds the key |
| `channelMap.containsKey("key")` / `"key" in channelMap` | `True` | `False`, even if the source map contains the key |

Use membership to distinguish absent from explicitly null. A `.get` default only applies when the key is missing, not when the value is `None`, `False`, `0`, or `""`.

```python
# Intentionally distinguish null from absence in a message map.
if channelMap.containsKey("middleName"):
    middle_name = channelMap.get("middleName")
    if middle_name is None:
        logger.info("Middle name was explicitly null")
else:
    logger.info("No middle-name entry")
```

`if value:` is a Python truthiness test. It conflates empty text, zero, false, and null, and fails for an unsupported-value sentinel. Use `is None`, membership, or explicit type checks when those cases matter. Do not use `value or fallback` to preserve valid false/zero values.

### Methods and return values

The same objects support Python dict operations and these familiar methods:

| Method | Behavior / result | Example |
| --- | --- | --- |
| `get(key, default=None)` | Read value; return default if missing; special channel/response lookup rules below apply | `site = configurationMap.get("site", "unknown")` |
| `put(key, value)` | Set literal key; message maps return prior literal value or `None`; global stores return `None` | `previous = channelMap.put("patientId", "P123")` |
| `remove(key)` | Remove literal key if present; message maps return prior value or `None`; global stores return `None` | `previous = channelMap.remove("temporary")` |
| `containsKey(key)` | Test actual membership, with response destination-name alias lookup | `exists = responseMap.containsKey("Destination name")` |
| `containsValue(value)` | Test actual values using Python equality | `has_true = channelMap.containsValue(True)` |
| `putAll(mapping)` | Update from a mapping; return `None` | `channelMap.putAll({"ready": True, "count": 2})` |
| `clear()` | Remove every actual entry in this snapshot; return `None` | `connectorMap.clear()` only when clearing all connector entries is intended |
| `size()` | Number of actual entries | `count = channelMap.size()` |
| `isEmpty()` | Whether there are no actual entries | `empty = connectorMap.isEmpty()` |
| `keySet()` | Python iterable keys view | `keys = list(channelMap.keySet())` |
| `entrySet()` | Iterable of entry objects with `getKey()`, `getValue()`, `setValue(value)` | See loop below. |
| `getVariables()` | Only on `globalMap` / `globalChannelMap`; read-only view of this invocation's global snapshot | `variables = globalChannelMap.getVariables()` |

`putAll`, `update`, and `clear` follow the map's write permission. Python `mapping["key"] = value`, `.update(...)`, `.setdefault(...)`, `.pop(...)`, `.popitem()`, `del`, and `|=` also enforce it. All writes require string keys. In `globalMap` and `globalChannelMap`, values cannot be `None`; remove the key instead:

```python
globalChannelMap.put("lastPatientId", "P123")
globalChannelMap.remove("lastPatientId")
```

`del channelMap["missing"]` and `.pop("missing")` raise `KeyError`; `.remove("missing")` and `.pop("missing", None)` return `None`. Do not port code that expects a global `put` to return the previous value; explicitly read before writing if you need it, while observing the non-atomic snapshot semantics below.

`keySet()` and `.values()` expose Python views, and `.items()` supplies Python key/value iteration. There is no Java `.iterator().hasNext()` collection API:

```python
# Inspect only supported scalar values; do not stringify unknown sentinels.
for key, value in channelMap.items():
    if isinstance(value, str):
        logger.debug("Text entry %s has %d characters", key, len(value))

# Entry.setValue returns the previous value, like the message-map put method.
for entry in channelMap.entrySet():
    if entry.getKey() == "patientId":
        previous = entry.setValue("P456")
```

Avoid adding/removing keys while iterating a live Python view. To delete selected entries use a key snapshot:

```python
for key in list(connectorMap.keySet()):
    if key.startswith("temporary:"):
        connectorMap.remove(key)
```

`getVariables()` reflects local changes made during this invocation; it does not refresh from another worker. Its mutation methods and entry `setValue` reject writes. For example, read `globalMap.getVariables().get("site")`, but use `globalMap.put("site", "east")` to change the original writable map. Full Java collection types, concurrent-store locking methods, and Java equality semantics are not exposed.

## Special lookup behavior

### Channel-map fallback to the source map

For engine compatibility, a missing `channelMap.get("origin")` or `channelMap["origin"]` falls back to `sourceMap` when that source entry exists. This logs the engine's deprecation message at ERROR. An actual channel entry with value `None` does not fall back. `containsKey`, `in`, enumeration, `size`, and writes/removals operate on actual channel entries, without source fallback.

```python
# Preferred: state the ownership explicitly.
origin = sourceMap.get("origin")
```

It is possible for `channelMap.get("origin")` to return source data while `"origin" in channelMap` is false. Do not use this deprecated behavior to decide where to write a value. `channelMap.put("origin", "new")` creates a channel entry; it does not update the read-only source map.

### Response-map destination names

The engine associates a destination name with a literal response key such as `d2` for metadata ID 2. The supplied response-map alias metadata supports reads and `containsKey` by name:

```python
# If a destination named "Send to EHR" has metadata ID 2:
by_name = responseMap.get("Send to EHR")
by_key = responseMap.get("d2")
exists = responseMap.containsKey("Send to EHR")
```

A native response is usually an unsupported-value sentinel; neither read creates a Python Response with Java getters. A non-null literal entry named `Send to EHR` takes precedence over alias lookup; a missing or null literal entry allows the `d2` fallback. `containsKey` reports the literal key or its mapped destination key.

Writes and removals use the literal argument, rather than resolving the alias. Therefore `responseMap.put("Send to EHR", "application note")` creates a literal named entry, and `.remove("Send to EHR")` removes that entry without removing `d2`. To intentionally change an actual `d2` entry you must address `d2` explicitly and understand its engine use. `put` returns the previous literal entry, which can differ from a preceding alias-aware `get`. Keys, `size`, `.items()`, and `entrySet()` enumerate actual entries and do not synthesize aliases.

Synthetic Test can accept `destinationIdMap` in the sample JSON, such as `{"Send to EHR": 2}`, to reproduce this name lookup. It is lookup metadata, not an eighth script map.

## Supported values and serialization limits

Map values travel through a checked serialization boundary. Plain Python values make the supported contract explicit:

| Value | Map transport | JSON document / Python Function arguments and results |
| --- | --- | --- |
| `None` | Supported except as a global-store value | JSON null |
| `bool` | Supported | JSON boolean |
| `str` | Supported | JSON string |
| `int` | Exact signed 64-bit range: −9,223,372,036,854,775,808 through 9,223,372,036,854,775,807 | Only ±9,007,199,254,740,991; larger integers must become strings |
| Finite `float` | Supported; non-finite values rejected | Supported; NaN and infinities rejected |
| `bytes`, `bytearray`, `memoryview` | Tagged bytes; received as `bytes` | Not JSON values; explicitly encode to text if needed |
| `dict` / supported mappings with string keys | Recursive supported values | JSON objects; document/function output should use ordinary dicts |
| `list`, `tuple` | Recursive supported values; tuples cross as arrays/lists | JSON arrays; no tuple identity is preserved |
| `datetime`, decimal/custom objects, sets, open files, connections, Java objects | No automatic Python encoding | Convert to deliberate supported representations |

The exact tagged integer route applies to maps and metadata, not the JavaScript JSON document/function route. Python's arbitrary-precision integer arithmetic does not remove those transport limits. If a map number is later inserted into `msg` or returned by a Function, the narrower JSON limit applies there. Store opaque identifiers as strings before JavaScript numeric precision is lost.

```python
# A supported map record with explicit conversion choices.
from datetime import datetime, timezone

channelMap.put("audit", {
    "recordId": "9223372036854775808",
    "checkedAt": datetime.now(timezone.utc).isoformat(),
    "accepted": True,
    "attempts": 1,
    "labels": ["validated", "outbound"],
})
```

Reference cycles are rejected. Nesting is limited to 64 levels by the codec, and serialized invocation/result frames are bounded (16 MiB by default; operator configuration controls the effective limit). Base64 byte transport and envelope overhead count toward the frame size. A large shared map can exceed a request limit even when the script only needs one entry. Do not store unbounded histories or full binary archives in shared maps.

Dictionary keys at every encoded level must be strings. A dictionary containing a `$t` key is escaped by the SDK's codec; do not manually construct private wire tags. Container identity and shared references are not a persistent cross-process object graph.

### Nested dict/list changes and copies

Nested supported dict/list edits are detected when results are extracted:

```python
# JSON-compatible map value; use this only when the entry has this schema.
channelMap.put("patient", {"id": "P123", "labels": []})
patient = channelMap["patient"]
patient["labels"].append("checked")
# No second put is required to detect the nested change.
```

A shallow copy retains local nested references. Use `copy.deepcopy` to isolate a derived record:

```python
from copy import deepcopy

original = channelMap["patient"]
copy_for_destination = deepcopy(original)
copy_for_destination["labels"].append("destination-specific")
connectorMap.put("patientForDestination", copy_for_destination)
```

Read-only maps may contain decoded dicts/lists. Do not mutate those nested values: the final delta check detects the change and raises `ContractError`. Copy supported data first, then store the changed copy in a writable map. `deepcopy` of an unsupported sentinel leaves it as the same sentinel; it does not make the Java object readable.

## Unsupported engine objects

An engine value with no supported representation becomes `oie_python.UnsupportedValue`. If a top-level map entry contains an unsupported nested value, the entire top-level entry becomes a sentinel; you do not receive a partially readable tree. Typical examples include native Java responses, E4X stored in a map, Java connections, or arbitrary application objects. The original remains in the engine.

Distinguish retrieving the sentinel from inspecting its underlying value:

```python
candidate = channelMap.get("legacyObject")  # Retrieving a sentinel is allowed.
if isinstance(candidate, UnsupportedValue):
    logger.warning("legacyObject is an unreadable engine type: %s", candidate.java_type)
else:
    # Application schema checks belong here before using candidate.
    logger.debug("legacyObject has a supported Python representation")
```

`candidate.java_type`, `candidate.origin`, and its diagnostic `repr` are available. Converting a sentinel with `str`, `bool`, `int`, `bytes`, iterating/indexing it, accessing arbitrary Java attributes, or calling Java getters raises `UnsupportedValueError`. An f-string with its ordinary string conversion also fails. `str(candidate)` is not a conversion strategy. Identity/equality of sentinels is not equality of the underlying Java objects.

You can preserve an existing original value by assigning its sentinel to another writable map entry from the same invocation:

```python
# Only when your application intentionally needs to preserve/copy this opaque value.
if channelMap.containsKey("legacyObject"):
    connectorMap.put("legacyObjectCopy", channelMap.get("legacyObject"))
```

This asks the Java side to reuse the original engine value identified by that invocation's provenance. It does not deserialize the object, clone its internals, or make the new entry readable. Do not fabricate sentinels or retain them in imported modules for later invocations; their origin is resolved against the current invocation's supplied originals. Leaving an unsupported entry untouched preserves it. Copying or deep-copying it cannot expose its data.

When Python needs fields, convert them explicitly in a preceding JavaScript step into supported scalars/containers or serialized text. For example, where the response exists:

```javascript
// JavaScript transformer: extract a supported field before the Python step.
var priorResponse = responseMap.get('Send to EHR');
if (priorResponse != null) {
    channelMap.put('ehrResponseText', String(priorResponse.getMessage()));
}
```

```python
# Following Python transformer.
ehr_text = channelMap.get("ehrResponseText")
if ehr_text is not None:
    logger.info("Prior EHR response text length: %d", len(ehr_text))
```

Use the actual Java object's API in its JavaScript surface. Serializing arbitrary Java objects through generic JSON is not guaranteed to yield a useful schema.

## Snapshots, commit, concurrency, and retries

The Reader's `sourceMap` supplies local defaults to returned messages; it produces no shared-map update and has no lifetime beyond the invocation. The rules below describe commits to writable message/shared maps.

For each invocation, the Java side snapshots the available maps and serializes their supported values. Python reads and changes those local objects. Result extraction compares the final values with the initial snapshot and produces per-key sets/deletes, including nested changes. The Java side validates the script's complete result before applying those map changes.

| Event | Map behavior |
| --- | --- |
| Successful transformer/Writer/Function invocation with valid output | Changed writable keys apply to the engine stores. |
| Filter returns valid `False` | Valid map/document changes still apply; false is rejection, not rollback. |
| Unhandled Python exception or invalid result/document/map value | No map changes from that invocation apply. External I/O already performed by user code is unaffected. |
| Direct mutation method on read-only map | Raises `TypeError`; nested edits are caught at extraction as `ContractError`. |
| Another invocation changes an unrelated key | This invocation sends no replacement for unchanged keys, preserving unrelated updates. |
| Another invocation changes the same key | Changes are not merged or compared against a live version; the last applied change wins. |
| Another invocation changes a map during a long Python call | This call's reads do not refresh. |
| Python changes a nested part of a value | The changed top-level entry is replaced; independent concurrent edits inside that entry are not merged. |
| A script writes a key and restores its initial serialized value before completion | No net change is sent for that key. Map methods are not an operation log. |
| Synthetic Test succeeds | Returns a preview of sample-map changes; live shared stores are neither read nor updated. |
| A later channel step fails | Previously committed steps' map changes remain; there is no channel-wide rollback. |
| Reader admission fails after its valid result | Shared-map changes committed before dispatch remain; earlier admissions may also remain. |

A read followed by a write is not atomic:

```python
# Not a reliable shared counter under concurrent invocations.
count = globalChannelMap.get("count", 0)
globalChannelMap.put("count", count + 1)
```

Two calls can read the same count and both write the same increment. `.setdefault`, `getVariables`, or Rhino-style methods do not add a distributed lock or compare-and-set operation. A Python thread lock only protects cooperating code inside one worker and cannot coordinate the engine, other workers, or nodes. Use an external transactional/atomic service when correctness requires a counter, lock, or deduplication claim.

Global maps are also unsuitable as an exactly-once delivery ledger. A Writer may perform external I/O and then time out or fail validation, leaving the external effect present without the corresponding map marker. Engine retries/manual reprocessing may repeat that operation. Use target-supported idempotency keys and durable source/target state according to your application's delivery contract; see [Connectors](Connectors.md).

## Read failures without hiding them

Handle anticipated data problems at the relevant boundary. Missing optional entries can use `.get`; required entries can use brackets and a schema check. Do not catch every exception around an entire script and return success: an unsupported value, missing key, invalid document, or network failure should not silently become a successful delivery.

```python
# Filter: missing consent is normal rejection, while malformed stored data is an error.
consent = sourceMap.get("consent")
if consent is not None and not isinstance(consent, bool):
    raise TypeError("sourceMap consent must be bool or None")
return consent is True
```

Review the context's result contract when testing failure paths. A filter that falls through accepts and commits valid map updates. An explicit null return fails validation; a valid false return commits changes and rejects. Synthetic Test supports sample shared maps and destination lookup metadata, while live deployment determines real lifetimes and concurrency. See [Editor testing and troubleshooting](Editor-testing-and-troubleshooting.md).

## Implementation references

- [Python map methods, literal writes, and compatibility lookups](../../python-sdk/oie_python/_maps.py)
- [Map snapshot comparison, read-only validation, and result extraction](../../python-sdk/oie_python/_context.py)
- [Python value codec and JSON safe-integer checks](../../python-sdk/oie_python/_codec.py)
- [UnsupportedValue and public result types](../../python-sdk/oie_python/_result_types.py)
- [Java snapshots, destination aliases, provenance, and map operation validation](../../server/src/main/java/com/mirth/connect/plugins/python/server/ScriptMaps.java)
- [Java value encoding and original-value restoration](../../server/src/main/java/com/mirth/connect/plugins/python/server/Wire.java)
- [Map integration checks](../../server/src/test/java/com/mirth/connect/plugins/python/server/PythonMapsIntegrationTest.java) and [stock JavaScript harness](../../server/src/test/java/com/mirth/connect/plugins/python/server/StockJavaScriptTestHarness.java)
