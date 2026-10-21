# JavaScript to Python

[Wiki home](Home.md) · [Contexts and message flow](Contexts-and-message-flow.md) · [Maps and values](Maps-and-values.md)

Python scripts run in CPython workers outside the engine JVM. The toolkit carries supported documents, metadata, map snapshots, and results between the two processes. It preserves familiar map names and ordinary map methods; it does not run JavaScript, E4X, or Java classes inside Python.

## Start with the script's job

Write the Python script body in the editor. Filters, Readers, Writers, and Python Functions support ordinary `return`; the runtime handles function execution internally. No `ctx` or named entry point is required. Imports, functions, and classes are ordinary Python; call helpers yourself. Transformer steps edit `msg`/`tmp` and do not support top-level `return`.

| JavaScript surface or task | Python surface and output | Small working example |
| --- | --- | --- |
| Source filter rule: `return msg.active === true;` | Python filter; return an exact boolean | `return msg.get("active") is True` for a JSON object |
| Destination filter rule | Same filter contract; rejection skips this destination | `return sourceMap.get("origin") == "trusted"` |
| Source transformer step | Change `msg`, `tmp` when available, or writable maps | `msg["checked"] = True` for a JSON object |
| Destination transformer step | Same document contract; an outbound template makes `tmp` the outgoing document | `if has_tmp: tmp["checked"] = True` for a JSON template |
| Response transformer | Change response document and/or the supplied response fields | `response.status_message = "Validated"` |
| JavaScript Reader returning raw message data | Python Reader; return `Message`, a list of `Message`, or `None` | `from oie_python import Message` then `return Message("hello")` |
| JavaScript Writer returning a response | Python Writer; return `None`, `str`, `Status`, or `Response` | `from oie_python import Status` then `return Status.SENT` |
| Callable JavaScript code template | Python Function template; read JSON `arguments`, return a JSON-compatible value | `return {"name": arguments[0]["name"].strip()}` |

An empty filter or fallthrough accepts as `True`. An explicit filter return must be boolean; `1`, `"true"`, `None`, and a bare `return` are invalid. A Writer rejects a dict result unless you serialize it to text. A Reader rejects a bare string unless you wrap it in `Message`. See [Filters and transformers](Filters-and-transformers.md), [Connectors](Connectors.md), and [Functions, classes, and modules](Functions-classes-and-modules.md) for complete examples and failure behavior.

## Map shortcuts

The engine generates JavaScript helper functions such as `$c`; `$` is not valid in a Python identifier. Use the injected maps directly, without imports or selection calls. The following examples assume a message context; Readers only receive the shared maps.

| Familiar JavaScript | Python read | Python write or alternative |
| --- | --- | --- |
| `$g('site')`, `$g('site', 'east')` | `globalMap.get("site")` | `globalMap.put("site", "east")` |
| `$gc('lastId')`, `$gc('lastId', 'P123')` | `globalChannelMap.get("lastId")` | `globalChannelMap.put("lastId", "P123")` |
| `$c('patientId')`, `$c('patientId', 'P123')` | `channelMap.get("patientId")` | `channelMap.put("patientId", "P123")` |
| `$co('requestId')`, `$co('requestId', 'R1')` | `connectorMap.get("requestId")` | `connectorMap.put("requestId", "R1")` |
| `$s('origin')` | `sourceMap.get("origin")` | Read-only downstream. In a Reader, set `sourceMap.put("origin", "poller")` for all emitted messages, or `Message("hello", source_map={"origin": "poller"})` for a per-message override. |
| `$r('Destination name')` | `responseMap.get("Destination name")` | `responseMap.put("applicationReceipt", "accepted")` writes that literal key; a read of an engine response can yield an unsupported-value sentinel. |
| `$cfg('site')` | `configurationMap.get("site")` | Read-only. Change engine configuration through its administrative facilities, then read it here. |
| `$('patientId')` | Prefer `channelMap.get("patientId")` when the owning map is known | No automatic generic lookup helper; the explicit compatibility helper below implements the available-map search order. |

Global `put`/`remove` return `None`, matching the engine's void methods. Message-map `put`/`remove` return the prior literal entry or `None`. Do not replace `.get` mechanically with brackets when the key might be absent: `map["missing"]` raises `KeyError`, whereas `map.get("missing")` returns `None`.

If a migrated script intentionally depends on generic JavaScript `$` lookup, make the dependency explicit. This helper searches the seven maps supplied in a message context, honors `containsKey` behavior, preserves a present `None` value, and returns the engine helper's empty-string default:

```python
# Filter / transformer / response transformer / Writer / Python Function.
def lookup(key):
    for mapping in (responseMap, connectorMap, channelMap, sourceMap,
                    globalChannelMap, globalMap, configurationMap):
        if mapping.containsKey(key):
            return mapping.get(key)
    return ""

patient_id = lookup("patientId")
```

In a Reader use `(sourceMap, globalChannelMap, globalMap, configurationMap)` in that loop. The stock JavaScript helper also checks a legacy `resultMap` after configuration; Python does not inject `resultMap`, so that part has no automatic equivalent. There is no need to catch errors from every map: availability is defined by context. [Maps and values](Maps-and-values.md) explains response-name aliases, deprecated source fallback, snapshots, and concurrency.

## `msg`: JSON, E4X, and RAW

The datatype determines `msg` and, independently, `tmp`. Check the channel's datatype and template rather than guessing from the message contents.

### JSON

A JSON object becomes a Python dict; an array becomes a list. Use string keys instead of JavaScript object-property syntax.

```javascript
// JavaScript JSON transformer.
msg.patient.name = msg.patient.name.trim();
msg.validated = true;
$c('patientId', msg.patient.id);
```

```python
# Python JSON transformer. The expected patient fields are required.
msg["patient"]["name"] = msg["patient"]["name"].strip()
msg["validated"] = True
channelMap.put("patientId", msg["patient"]["id"])
```

Nested JSON edits are detected. Assigning `msg = {"accepted": True}` replaces the root; a JSON root must remain an object or array. To return JSON response data from a Writer, explicitly serialize it. This example does not deliver anything to an external target:

```python
import json

return json.dumps({"accepted": True}, ensure_ascii=False, allow_nan=False)
```

JSON transformer documents and Python Function arguments/results require finite numbers and integers within ±9,007,199,254,740,991. Preserve larger identifiers as strings before the JavaScript boundary. Map transport has a separate signed-64-bit integer contract; see [Maps and values](Maps-and-values.md).

### E4X XML and HL7 internal XML

JavaScript E4X becomes XML text. There is no Python E4X field syntax, XMLList object, or engine serializer automatically injected. An HL7 v2 transformer's `msg` contains the engine's internal XML, rather than the original pipe-delimited HL7 text.

```javascript
// JavaScript E4X transformer, where the expected fields exist.
msg['PID']['PID.5']['PID.5.1'] = 'SMITH';
```

```python
# Python transformer for a single, unnamespaced internal HL7 XML document.
import xml.etree.ElementTree as ET

root = ET.fromstring(msg)
family_name = root.find("./PID/PID.5/PID.5.1")
if family_name is None:
    raise ValueError("Expected PID.5.1 in the internal HL7 XML")
family_name.text = "SMITH"
msg = ET.tostring(root, encoding="unicode")
```

Parsing into a separate tree does not update `msg`; assign the element or serialized string back. Namespaced XML needs namespace-aware paths, for example `root.find("./p:patient/p:id", {"p": "urn:example:patient"})`. An E4X XMLList may have multiple roots or no roots; a single `ET.fromstring(msg)` is unsuitable for that input. Use fragment-aware handling appropriate to your format.

Changed XML is validated and rebuilt for later JavaScript steps. Earlier JavaScript references to the old tree remain references to the old tree; read the current `msg`/`tmp` in later steps. Parsing/serialization can change prefixes, whitespace, comments, or processing instructions, so do not treat it as byte-preserving editing.

### RAW text and templates

RAW documents are Python strings. Use string operations and assign the result:

```python
# RAW transformer.
msg = msg.replace("\r\n", "\n")
if has_tmp:
    tmp = msg
```

That last assignment is valid when the outbound template is also RAW. XML requires valid XML text; JSON requires a dict/list. An outbound template is not populated automatically by edits to `msg`; update `tmp` according to its datatype when one exists. A Writer receives the selected outgoing text as `payload`, with encoded content preferred over transformed content and raw content; an empty string is present content and is preserved.

## `connectorMessage` becomes metadata

Python's `message` is a read-only mapping of metadata, not a Java `connectorMessage`. In filters, transformers, Writers, and Python Functions it supplies these keys:

| JavaScript access | Python access | Meaning |
| --- | --- | --- |
| `connectorMessage.getChannelId()` | `message["channel_id"]` | Channel identifier |
| `connectorMessage.getChannelName()` | `message["channel_name"]` | Channel name |
| `connectorMessage.getConnectorName()` | `message["connector_name"]` | Current connector name |
| `connectorMessage.getMetaDataId()` | `message["metadata_id"]` | Numeric connector metadata ID |
| `connectorMessage.getMessageId()` | `message["message_id"]` | Engine message ID |
| `connectorMessage.getServerId()` | `message["server_id"]` | Server identifier |
| `connectorMessage.getSendAttempts()` | `message["send_attempts"]` | Send attempt count supplied for this invocation |
| `connectorMessage.getReceivedDate()` | `message.get("received_date")` | Optional ISO instant string, rather than a Java date |

Readers have channel/connector identity only: `channel_id`, `channel_name`, `connector_name`, and `metadata_id`. They do not have a per-message ID before emitting messages. Reader `sourceMap.get("pollId")` starts as a live invocation UUID, not a durable source checkpoint. Synthetic Test starts with `pollId: "sample"`. The Reader map is writable and invocation-local, and its final values are inherited by each returned `Message`. `Message.source_map` overrides matching entries, including `pollId`. There is no `poll` script global. Synthetic `message` metadata is fixed and cannot be overridden in sample JSON. Use `.get` defaults where appropriate, and test metadata-dependent behavior in an isolated deployed channel.

```python
# Writer: an identity suitable for a target-supported idempotency key.
request_key = "%s:%s:%s:%s" % (
    message["server_id"], message["channel_id"],
    message["message_id"], message["metadata_id"],
)
logger.info("Sending request %s", request_key)
```

Choose the idempotency scope deliberately: retries, manual reprocessing, and externally assigned source identities can need different treatment. Do not include `send_attempts` in a key intended to deduplicate retries. Metadata does not expose raw/transformed content getters, attachment methods, routing methods, or mutable Java response objects. Use the context's `msg`, `tmp`, `payload`, and maps where available.

## Logging

Use `logger.debug`, `logger.info`, `logger.warning`, or `logger.error`. The compatibility spelling `logger.warn` also exists. Arguments use Python `%` interpolation, rather than Java overloads or `{}` formatting:

```python
logger.info("Processed patient %s", channelMap.get("patientId"))
logger.warning("Missing optional field: %s", "middleName")
logger.error("Delivery failed for destination %s", message["metadata_id"])
print("A diagnostic line")
```

An f-string is also valid, for example `logger.info(f"Connector: {message['connector_name']}")`. There is no Java throwable overload or `logger.exception()` method. If a specific operation needs an error log, catch its specific exception, log text, and re-raise when execution must fail:

```python
import json

# Writer expecting a JSON payload.
try:
    document = json.loads(payload)
except json.JSONDecodeError as exc:
    logger.error("Outgoing JSON is malformed: %s", exc)
    raise
return json.dumps(document, ensure_ascii=False, allow_nan=False)
```

`print()` and stdout/stderr writes become captured stdout records, forwarded at INFO. Logs are bounded, can be truncated, and follow the engine's logging configuration. Avoid logging full clinical payloads or credentials.

## `RawMessage`, `Response`, and `Status`

A JavaScript Reader's `new RawMessage(...)` concept becomes the SDK's `Message`; do not import a nonexistent Python `RawMessage` or `newRawMessage` helper:

```python
from oie_python import Message

return Message(
    '{"patientId":"P123"}',
    source_map={"origin": "python-reader", "sourceId": "external-123"},
    destination_ids=None,
)
```

`destination_ids=None` selects all enabled destinations; `[]` selects none. A list selects enabled numeric metadata IDs, not destination names. `source_map` overrides the supplied Reader `sourceMap` defaults for this new message; it cannot set the reserved engine keys `destinationSet`, `batchId`, `batchComplete`, `batchSequenceId`, `reprocessed`, or `replaced`. `Message(b"binary")` is allowed with Process Batch disabled. Attachments, original-message IDs, and import/overwrite flags are not Message fields.

A Writer's JavaScript `new Response(...)` becomes an imported Python `Response` with named Python fields:

```python
from oie_python import Response, Status

return Response(
    status=Status.SENT,
    data="accepted",
    status_message="Delivery completed",
    error=None,
)
```

`data` maps to the engine response message. `status_message` maps to its short status message; `error` maps to error details. Text fields accept `str` or `None`. Use `Status.SENT`, `Status.ERROR`, or `Status.QUEUED`; no other lifecycle statuses are accepted. `return Status.ERROR` is an error status with empty data. `return "ERROR"` is a successful SENT response whose data is the text `ERROR`. QUEUED becomes ERROR if destination queueing is disabled. Engine retry settings still apply, so external writes need a deliberate retry/idempotency strategy.

A response transformer uses its existing `response` object instead of returning a Writer result:

```python
from oie_python import Status

response.status = Status.SENT
response.status_message = "Response accepted"
response.error = None
```

There is no `response.data` field on that object; change the response document through `msg`/`tmp` as appropriate. See [Connectors](Connectors.md) for dispatch, retry, and partial Reader admission semantics.

## Ordinary Python replacements

These examples show Python operations, rather than automatic engine-service bindings.

| JavaScript / Java habit | Python pattern | Boundary to keep explicit |
| --- | --- | --- |
| `JSON.parse(text)`, `JSON.stringify(value)` | `import json`; `value = json.loads('{"active":true}')`; `text = json.dumps(value, allow_nan=False)` | A JSON transformer's `msg` is already parsed. Writers require serialized text. |
| E4X or `new XML(text)` | `import xml.etree.ElementTree as ET`; `root = ET.fromstring("<patient/>")`; `text = ET.tostring(root, encoding="unicode")` | Namespace, XMLList, and formatting differences apply. This does not convert HL7 pipe text to internal XML. |
| `new Date()`, `java.util.Date` | `from datetime import datetime, timezone`; `stamp = datetime.now(timezone.utc).isoformat()` | Store the resulting string in a map or JSON result; datetime objects do not cross the boundary. |
| Base64 utilities | `import base64`; `text = base64.b64encode(b"hello").decode("ascii")`; `raw = base64.b64decode(text, validate=True)` | Base64 text and binary bytes are different contracts. |
| JavaScript regular expression | `import re`; `valid = re.fullmatch(r"[A-Z][0-9]+", "P123") is not None` | Review syntax and Unicode behavior; patterns are not universally interchangeable. |
| `null`, `true`, `false`, `undefined` | `value = None`; `active = True`; `cancelled = False`; `missing = "x" not in {}` | No `undefined` value. Test key membership to distinguish a missing entry from an explicit `None`. |
| `java.util.HashMap`, `ArrayList` for plain data | `patient = {"id": "P123"}`; `patients = [patient]` | Use dict/list data, not Java collection methods; serialized values do not retain Java class identity. |
| Java byte array / string conversion | `raw = "hello".encode("utf-8")`; `text = raw.decode("utf-8")` | Choose the encoding. Bytes work in maps and Reader payloads, not JSON documents or Writer results. |
| Numeric patient identifiers | `patient_id = "9007199254740992"` | Python integers are arbitrary precision, but boundary limits still apply; identifiers are usually better as strings. |

Python object identity and process lifetime differ from the JavaScript engine's live objects. Script globals are fresh on each invocation; imported modules may stay cached in a reused worker. A module-level list is neither a reliable channel-wide cache nor durable state. Use invocation-local data, supported engine maps with their snapshot rules, or an external durable store. See [Functions, classes, and modules](Functions-classes-and-modules.md).

## Java and engine helpers without automatic Python equivalents

CPython does not supply `Packages`, `java`, or Rhino's Java import machinery. Renaming a Java import to Python syntax does not bridge it into the JVM.

| Existing JavaScript usage | Supported migration direction |
| --- | --- |
| `Packages...`, `importPackage(...)`, `java.util...` | Replace plain-data/date/encoding operations with Python built-ins or standard-library modules above. Keep engine-specific Java operations in an appropriate JavaScript step or connector. No arbitrary Java invocation bridge is exposed. |
| `java.sql...` or `DatabaseConnectionFactory.createDatabaseConnection(...)` | Use an existing engine database connector, or install an approved Python database driver in the selected prepared environment and use its documented API. JDBC connections, connection pools, engine database credentials, and SQL transaction objects are not injected into Python. |
| `FileUtil.read(...)` / `FileUtil.write(...)` | For deliberate worker-host file access use `from pathlib import Path`, `text = Path("/approved/input.txt").read_text(encoding="utf-8")`, or `Path("/approved/output.txt").write_text(text, encoding="utf-8")`. Paths and permissions belong to the worker's host/process. This does not provide engine attachment, file-connector, or remote-file semantics. |
| `SerializerFactory.getSerializer(...)` | Keep the engine datatype/serializer conversion in JavaScript or the configured engine processing stages. `json` and XML libraries parse their own formats; they are not automatic replacements for the engine's HL7/EDI/datatype serializers. |
| `router.routeMessage(...)` | No Python router binding. Use the existing engine routing connector or retain an appropriate JavaScript routing step. A Python Reader emits messages into its own configured channel; that is not an arbitrary channel router API. |
| `destinationSet` mutation | No injected Python `destinationSet`. A Reader can select enabled destination metadata IDs with `Message(..., destination_ids=[...])`; filters can accept/reject their own processing scope. Neither is a general destinationSet object for a transformer. |
| `getAttachment(...)`, `addAttachment(...)`, attachment IDs or engine attachment objects | No Python attachment-service binding or `Message` attachment field. Keep attachment handling in a supported engine/JavaScript surface; explicitly pass supported text/bytes through a writable map if Python needs to process content. |
| Engine Java HTTP utilities or third-party Java HTTP classes | Use the configured engine HTTP connector, Python standard-library HTTP code, or an approved Python HTTP package in the selected environment. Configure timeouts, authentication, TLS, and target idempotency explicitly; Java clients and their connection state are not injected. |

External packages must be installed through the toolkit's prepared environment workflow before use. A successful compile/Validate does not prove an import is installed, the remote service is reachable, or a credential/path is available. See [Environments and deployment](Environments-and-deployment.md) and [Editor testing and troubleshooting](Editor-testing-and-troubleshooting.md).

## Migration checks

1. Identify the surface and produce its correct output: document, boolean, message envelope, response, or JSON function result.
2. Replace `$` shortcuts with explicit maps and review missing-key/null handling.
3. Identify each Java/E4X/helper dependency and select a supported replacement; retain engine-specific work where its API exists.
4. Convert custom objects, datetimes, sets, bytes-in-JSON, and large JSON integers explicitly before returning.
5. Exercise missing data, rejected filters, exceptions, timeouts, retry, and duplicate delivery using the same datatypes and templates as production.
6. Verify deployment and environment behavior; synthetic Test uses sample maps and does not mutate live shared stores.

## Implementation references

- [SDK globals, documents, logging, metadata freezing, and result extraction](../../python-sdk/oie_python/_context.py)
- [SDK map methods](../../python-sdk/oie_python/_maps.py), [result types](../../python-sdk/oie_python/_result_types.py), and [value codec](../../python-sdk/oie_python/_codec.py)
- [Engine bridge and message metadata](../../server/src/main/java/com/mirth/connect/plugins/python/server/PythonBridge.java), [connector payload/metadata handling](../../server/src/main/java/com/mirth/connect/plugins/python/server/Connectors.java), and [Reader polling](../../server/src/main/java/com/mirth/connect/plugins/python/server/PythonReceiver.java)
- [Stock JavaScript integration harness](../../server/src/test/java/com/mirth/connect/plugins/python/server/StockJavaScriptTestHarness.java)
