# Filters and transformers

Use a **Python rule** to decide whether processing continues, and a **Python step** to edit a document. Both receive `msg`, `has_tmp`, optional `tmp`, and the available maps. Their document/map transfer and validation rules are shared; their outputs differ. Start with [Contexts and message flow](Contexts-and-message-flow.md) for the full eight-editor availability table.

## Native Python filters

Add a Python rule to a source or destination filter. A new rule starts with `result = True`. Your script must finish with `result` holding exactly a Python `bool`:

```python
# Assumption: JSON object msg; active is a JSON boolean, cancelled is optional.
result = msg.get("active") is True and not msg.get("cancelled", False)
```

`True` accepts this rule; `False` rejects this rule. The surrounding stock AND/OR expression decides overall acceptance. `1`, `0`, `"true"`, `[]`, `None`, or an omitted result are contract errors, not alternative booleans. `return True` at top level is invalid Python. If a helper returns the decision, assign it explicitly:

```python
# Assumption: JSON object msg; patient_id is a string when present.
def has_patient(document):
    value = document.get("patient_id")
    return isinstance(value, str) and bool(value.strip())

result = has_patient(msg)
```

A false overall source filter prevents destination processing. A false destination filter skips that destination. An exception or invalid result is a filter error rather than a normal rejection. There is no Python response-filter rule.

### AND, OR, short circuit, and ordering

The engine compiles the configured mixed rule list; Python does not reimplement rule precedence. Existing AND/OR precedence and short circuit apply to Python and JavaScript rules together. In particular, a false left side of an AND and a true left side of an OR can skip the next rule. Use Python `not`, `and`, and `or` **inside** a rule; configure cross-rule operators in the filter editor. For a nontrivial expression, verify its behavior in the actual channel rather than assuming visual grouping adds parentheses.

Never place required audit writes or cleanup only in a rule that may be skipped. A later rule sees earlier committed map/document changes only if the earlier rule actually executed successfully. Python rules nested inside a stock Iterator rule are unsupported; put iteration inside a flat Python rule:

```python
# Assumption: JSON object msg with observations: list of objects.
# Accept only when at least one observation explicitly marks itself critical.
result = any(item.get("critical") is True for item in msg["observations"])
```

### False results still commit valid edits

The boolean decides filtering, not whether edits commit. This complete source/destination filter assumes an object with an optional string `patient_id`:

```python
patient_id = msg.get("patient_id", "").strip()
msg["patient_id"] = patient_id
channelMap.put("patient_id_present", bool(patient_id))
result = bool(patient_id)
```

When the ID is empty, this rule returns `False` and retains its normalized document and map change. An invalid document or invalid boolean rejects the whole invocation instead. Do not rely on rejected messages proceeding to a later transformer to perform required work.

## Native Python transformer steps

Transformer output comes from `msg` and optional `tmp`. An empty step is a no-op. There is no automatic `output` alias, and `result` is not a transformer output variable.

For JSON, mutate in place or assign a supported replacement:

```python
# Assumption: JSON object msg = {"patient_id": " P123 ", "name": " Ada "}.
# No outbound template is configured for this phase.
msg["patient_id"] = msg["patient_id"].strip()
msg["name"] = msg["name"].strip()
msg["validated"] = True
channelMap.put("patient_id", msg["patient_id"])
```

A replacement also works, provided the original kind is JSON and the root remains an object/array:

```python
# Assumption: JSON object msg with patient_id and name strings; no template.
msg = {"id": msg["patient_id"].strip(), "display_name": msg["name"].strip()}
```

A list root supports ordinary list operations. Object keys must be strings; JSON values must be JSON-compatible. Large integers outside ±(2^53−1), nonfinite floats, cycles, and excessive nesting are rejected. Byte values and unsupported engine-object sentinels supported in some map scenarios are not JSON document values. See [Maps and values](Maps-and-values.md) for the separate map value contract.

Do not delete `msg` or `tmp`. You may replace an existing document, but cannot create `tmp` when the invocation has no template. Rebinding `channelMap`, `response`, `logger`, or another helper name only replaces your local name; mutate the supplied object instead. Rebinding `has_tmp` cannot create or remove a template.

## Outbound templates must be populated explicitly

`msg` is the incoming/current document; `tmp` is the outbound template when present. The two do not automatically synchronize. The engine sends the template when it exists.

This complete source or destination transformer assumes JSON object input with string `patient_id` and `name`, JSON output, and either no template or a JSON object template:

```python
normalized = {
    "id": msg["patient_id"].strip(),
    "name": msg["name"].strip(),
}

if has_tmp:
    tmp["patient"] = normalized
else:
    msg = {"patient": normalized}
```

With a template, unrelated template fields remain in `tmp`. Without one, the outgoing document is replaced with the explicit patient wrapper. If your intended output must contain only that wrapper even when a template exists, replace the existing template with `tmp = {"patient": normalized}` instead.

Filters also expose `has_tmp` and may receive the initialized outbound template, but they run before transformer steps populate it. Prefer acceptance logic based on `msg`; check `has_tmp` before reading a template and populate outgoing fields in a transformer.

## RAW strings

RAW `msg` is Python `str`, not bytes or a JSON object, even if its characters happen to form JSON. Strings are immutable, so produce a replacement:

```python
# Assumption: RAW input and RAW output, no outbound template.
msg = msg.rstrip("\r\n") + "\n"
```

To edit JSON carried in RAW, parse and serialize it while retaining a string document:

```python
# Assumption: RAW msg contains a JSON object, no outbound template.
import json

document = json.loads(msg)
document["validated"] = True
msg = json.dumps(document, ensure_ascii=False, allow_nan=False)
```

Native JSON datatypes instead already provide a dict/list; do not call `json.loads` on those. A filter for RAW content still must assign a boolean: `result = "required-token" in msg` is valid.

## XML is text across the boundary

The engine's E4X XML is serialized with `toXMLString()`. Python receives a `str`, not a live XML/JavaScript object. E4X expressions such as `msg['PID']['PID.3']`, `msg..PID`, `new XML(...)`, and `default xml namespace` do not translate directly into Python. Parse the string with an XML library and assign the edited document back.

### Ordinary single-root XML

This complete transformer assumes XML `msg` containing one unnamespaced `<patient>` root with an existing `<name>` child, and no outbound template:

```python
import xml.etree.ElementTree as ET

document = ET.fromstring(msg)
name = document.find("name")
if name is None:
    raise ValueError("Expected patient/name")
name.text = (name.text or "").strip()
document.set("validated", "true")
msg = ET.tostring(document, encoding="unicode")
```

Assigning `msg = document` also works for an XML document because the SDK serializes `ElementTree.Element`. Merely editing `document` without assigning it or its text back leaves `msg` unchanged. JSON and RAW documents cannot be replaced with an XML element.

### Namespace-aware editing

Namespaces are part of the element name, including when the source uses an unprefixed default namespace. ElementTree uses `{namespace-uri}local-name` or explicit namespace mappings; an unqualified `find("name")` will not find a namespaced child.

This complete step assumes one XML root like `<patient xmlns="urn:example:patient"><name> Ada </name></patient>`, and no template:

```python
import xml.etree.ElementTree as ET

namespace = "urn:example:patient"
document = ET.fromstring(msg)
name = document.find("p:name", {"p": namespace})
if name is None:
    raise ValueError("Expected namespaced patient/name")
name.text = (name.text or "").strip()
msg = ET.tostring(document, encoding="unicode")
```

The serializer may choose a different prefix while preserving namespace identity. Do not strip namespaces to make selectors work. If a downstream consumer needs specific prefixes, comments, processing instructions, formatting, or byte identity, choose and test an XML strategy that preserves those requirements. Standard ElementTree parsing/serialization is not a byte-preserving editor.

The generated wrapper resets its own default XML namespace while rebuilding changed E4X documents, so a prior JavaScript default namespace does not silently add that namespace to an unnamespaced Python replacement. It preserves the original E4X **type**, XML versus XMLList. Unchanged XML retains its original object. For changed XML, following JavaScript must read the current `msg`/`tmp`; a variable saved earlier pointing at an old root or child remains attached to the old tree.

### XMLList fragments

An XMLList can hold multiple roots or no roots. `ET.fromstring(msg)` assumes a single root and is unsuitable for arbitrary XMLList values. This complete fragment example assumes `msg` is an XMLList of zero or more unnamespaced `<item>` elements, with no XML declaration, and no template:

```python
import xml.etree.ElementTree as ET

container = ET.fromstring("<fragment>" + msg + "</fragment>")
for item in container.findall("item"):
    item.set("validated", "true")
msg = "".join(ET.tostring(child, encoding="unicode") for child in container)
```

The artificial container is a Python parsing aid and must not be returned as part of the fragment. The example is for element fragments; mixed top-level text and exact whitespace preservation require a parser/serializer strategy designed for that content.

### HL7 v2 uses internal XML here

For HL7 v2 datatypes, Python sees the engine's internal XML, not the original pipe-delimited ER7 text. The stock serializer converts internal documents according to datatype settings after transformation. Do not split native transformer `msg` on `|` or assign pipe text to an XML-kind `msg`.

This complete transformer assumes internal XML with an unnamespaced `<HL7Message>` root and an existing first `PID/PID.5/PID.5.1` name component; no outbound template is configured:

```python
import xml.etree.ElementTree as ET

document = ET.fromstring(msg)
family_name = document.find("PID/PID.5/PID.5.1")
if family_name is None:
    raise ValueError("Expected first PID.5.1 in the internal HL7 XML")
family_name.text = (family_name.text or "").strip().upper()
msg = document
```

This deliberately targets only the first matching segment/repetition. Check your actual internal XML and serialization settings before adopting a field path; repeated segments, groups, repetitions, escaping, and namespaces change what a correct edit requires. If a template exists, edit/populate its `tmp` tree explicitly instead of assuming the changed `msg` becomes the output.

Changed XML is checked for well-formedness, with DTD declarations rejected. XMLList is validated as a fragment. A malformed replacement fails before the invocation's map edits are applied.

## Destination and response examples

A destination transformer can build the final destination request and save supported metadata for its Writer. This complete step assumes JSON object input with a patient_id string, JSON output, and no template:

```python
patient_id = msg["patient_id"].strip()
connectorMap.put("request_patient_id", patient_id)
msg = {"patientId": patient_id, "operation": "upsert"}
```

The Python Writer later receives serialized outgoing `payload`, not that dict or a `msg` global. See [Connectors](Connectors.md) for sending and response results.

A response transformer receives the response body as `msg`/optional `tmp` plus the `response` field helper. This complete step assumes the destination response uses JSON input/output, its `msg` is an object with a boolean `accepted`, and there is no response outbound template:

```python
accepted = msg.get("accepted") is True
if accepted:
    response.status = "SENT"
    response.status_message = "Remote application accepted request"
    response.error = None
else:
    response.status = "ERROR"
    response.status_message = "Remote application rejected request"
    response.error = "accepted was not true"

msg["checked_by"] = "python-response-transformer"
```

Only `SENT`, `ERROR`, and `QUEUED` are supported assigned response statuses. `status_message` and `error` take text or `None`. The helper has no `data` field; change body content through documents. Assigning `result = Response(...)` here does not create a response. An engine error without a JSON body does not satisfy this example's assumptions; use response datatype/settings and guards appropriate to real error responses.

## Calling a Python Function from JavaScript

Use a Python Function when JavaScript should own the document assignment and Python should compute a reusable JSON value. The following complete template assumes one JSON object argument with a string name:

```python
patient = arguments[0]
result = {"name": patient["name"].strip()}
```

Configure the template's JavaScript name as `normalize_patient`, enable its library and source/destination transformer context for the channel, and deploy. A complete JavaScript JSON transformer step can then use:

```javascript
// Assumption: JSON msg object with a string name; no outbound template.
msg = normalize_patient(msg);
```

The function receives JSON arguments, a JSON result contract, and calling maps. Argument mutations alone do not update JavaScript `msg`; use the return value. With XML, explicitly pass `msg.toXMLString()` and explicitly reconstruct/assign the returned text in JavaScript. With RAW, passing the stock string directly is supported. See [Functions, classes, and modules](Functions-classes-and-modules.md) for binding, validation, and deployment details.

## Failure and mutation matrix

| Event | Processing result | Mutation consequences |
| --- | --- | --- |
| Rule returns valid `True` | Rule accepts; overall expression still governs | This invocation's valid changes commit |
| Rule returns valid `False` | Rule rejects; expression may still accept via OR | This invocation's valid changes commit |
| Rule is short-circuited/disabled | No invocation | No edits or logs from that rule |
| Rule returns `1`, string, `None`, or no result | Filter error | No staged changes from this invocation |
| Script raises an exception | Current script fails | No staged document/map/response changes from this invocation; logs/external I/O persist |
| Invalid `msg`, `tmp`, response field, or map delta | Contract error with diagnostic | All this invocation's output validated before any staged edits apply |
| `msg` valid, `tmp` invalid | Contract error | Neither document nor staged maps apply |
| Earlier Python invocation succeeded; later step fails | Later failure follows engine handling | Earlier commits are not rolled back |
| Function succeeded; subsequent JavaScript throws | Calling script fails | Function's committed map changes are not a larger JavaScript transaction |
| Concurrent writers update unrelated map keys | Normal validation/commit | Only changed keys written; unrelated updates preserved |
| Concurrent writers update the same map key | Normal validation/commit | Last commit wins; read-modify-write is not atomic |
| Timeout/failure after external I/O | Failure can have uncertain external outcome | External effects cannot be rolled back; engine retry/reprocess policy matters |

The validation boundary protects against script/result errors; it is not a database transaction across engine stores or external systems. Do not describe it as rollback of the entire channel.

## Testing and troubleshooting

Use Validate for compilation and environment checks; it does not execute imports or top-level behavior. Use Test with the correct datatype-shaped sample for document/map previews. Filter Test exposes an `accepted` boolean, and map previews do not update live shared stores. A synthetic sample does not prove actual engine queue behavior, metadata availability, HL7 serialization, or external delivery. Follow [Editor testing and troubleshooting](Editor-testing-and-troubleshooting.md) for test input and diagnostics.

When an edit seems lost, check the phase datatype, whether `has_tmp` was true, whether you assigned a parsed XML tree back, whether the invocation passed validation, and whether a later step replaced the document. If a following JavaScript step sees stale XML, reacquire nodes from the current document rather than using a reference captured before Python.

The public [SDK contract](../sdk.md), [SDK contexts](../../python-sdk/oie_python/_context.py), [document/result validation](../../server/src/main/java/com/mirth/connect/plugins/python/server/TransformCall.java), and [generated wrappers](../../shared/src/main/java/com/mirth/connect/plugins/python/PythonContract.java) are the implementation references. The [native filter qualification runner](../../tools/run_filter_isolated.py) covers actual stock-engine filter behavior; see [testing and support](../validation.md) for procedures and qualification limits. A runner's source does not itself prove that a particular installed release passed.

Continue with [Maps and values](Maps-and-values.md), [Functions, classes, and modules](Functions-classes-and-modules.md), or [Environments and deployment](Environments-and-deployment.md).
