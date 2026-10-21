# Getting started

[Wiki home](Home.md) · [Context reference](Contexts-and-message-flow.md)

## Before writing a channel

Install the Python Runtime extension using the engine's extension manager and restart when prompted. Open the Web Administrator. **Settings → Script Environments** should show **Vanilla Python (standard library)** reaching **Ready** on the node that will execute your channel. The built-in environment contains the SDK and standard library; it prepares offline using the approved installed interpreter.

New Python rules, steps, Readers, and Writers select that environment. For third-party libraries, create a separate [Script Environment](Environments-and-deployment.md). An environment shown as ready on one node is not necessarily ready on another.

The examples below use JSON object messages and no outbound template. Configure the channel's data types accordingly. Do not paste JSON dictionary operations into a RAW or XML context without the conversion described in [Filters and transformers](Filters-and-transformers.md).

## 1. Produce one JSON message

Select **Python Reader** as the source connector. This fixture emits one message **on every poll**, so use a controlled test channel and stop it when finished.

```python
import json
from oie_python import Message

result = Message(
    payload=json.dumps({"patient_id": "P123", "name": " Ada ", "active": True}),
    source_map={"origin": "getting-started"},
)
```

`Message` is the supported Python counterpart to a JavaScript Reader's `RawMessage`. `source_map` becomes the downstream read-only `sourceMap`. Leaving `result = None` would emit nothing. A plain string is not a valid Reader result.

You can instead use an existing source connector and submit the equivalent JSON to it.

## 2. Add a native Python source filter

Add a **Python** rule in the source filter list:

```python
result = msg.get("active") is True
```

The result must be a real boolean, not `1`, a string, or a nonempty container. AND/OR ordering and short circuit behavior are the engine's existing filter behavior. Native Python filters do not need a JavaScript wrapper authored by you.

## 3. Add a Python source transformer step

Add a **Python** step in the source transformer list:

```python
msg["name"] = msg["name"].strip()
channelMap.put("patient_id", msg["patient_id"])
logger.info("Normalized patient " + msg["patient_id"])
```

The next step sees the updated document. `result = msg` is unnecessary in a transformer; document changes come from `msg`/`tmp`. Maps are available by name. `$c('patient_id')` becomes `channelMap.get("patient_id")` in Python.

If the transformer has an outbound template, write its outgoing fields to `tmp` as appropriate. `has_tmp` tells you whether `tmp` exists; updating only `msg` does not populate an unrelated outbound template.

## 4. Add a Python Writer destination

This Writer is a demonstration that acknowledges the serialized payload; it does not deliver to an external system:

```python
import json
from oie_python import Response, Status

document = json.loads(payload)
result = Response(
    status=Status.SENT,
    data=json.dumps({"accepted": document["patient_id"]}),
    status_message="Demonstration completed",
)
```

A Writer receives `payload` as a string, not a parsed `msg`. Its `Response.data` becomes the native response body. A production Writer must perform its intended delivery before returning success. See [Connectors](Connectors.md) for ERROR/QUEUED, retries, and idempotency.

## 5. Validate, test, and deploy

1. Use **Validate Python** in each editor. It compiles the script and checks the selected environment; it does not execute imports or predict runtime results.
2. Use **Test sample** for each rule/step/connector. Supply the appropriate synthetic JSON described in the [testing guide](Editor-testing-and-troubleshooting.md#sample-contexts). Inspect both output and diagnostics. These separate samples do not run the whole channel pipeline.
3. Save and deploy the channel. Inspect a real message's source/destination status, transformed data, and response. Confirm that a message with `active: false` is filtered as intended.
4. Stop the fixture Reader when finished. A sample test does not dispatch Reader output, while a deployed Reader does.

For this fixture the expected result is a normalized name of `Ada`, a channel-map `patient_id` of `P123`, and a SENT destination response containing `{"accepted": "P123"}`. JSON whitespace is not significant.

## 6. Reuse logic

If JavaScript needs to call Python, create a **Python Function** code template, name it `normalize_name`, choose a supported context, and enable its library for the channel:

```python
result = arguments[0].strip()
```

Call it from a JavaScript transformer after deploying the consuming channel:

```javascript
msg.name = normalize_name(msg.name);
```

The native Python step does not import or call this registered JavaScript function. To share a class or helper among native Python scripts, install a package in their environments and use ordinary Python imports. The [reuse guide](Functions-classes-and-modules.md) covers both approaches in detail.
