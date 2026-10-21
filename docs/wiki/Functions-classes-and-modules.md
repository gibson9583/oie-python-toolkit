# Functions, classes, and modules

[Wiki home](Home.md) · [JavaScript migration](JavaScript-to-Python.md) · [Environments](Environments-and-deployment.md)

## Can a code template contain Python classes?

**Yes.** A Python Function code template can contain imports, classes, dataclasses, helper functions, and normal top-level Python statements. Its externally callable interface is still a JavaScript function. The caller passes positional JSON-compatible arguments; the script assigns its JSON-compatible return value to `result`.

The class itself is not exported to JavaScript or registered as a Python module. You cannot return a class instance across the boundary, use `new MyPythonClass()` in JavaScript, or import that code template from another Python script. Convert instances to dictionaries/lists/scalars for return values. Use an installed Python package when several Python scripts need the same class.

| Need | Mechanism |
| --- | --- |
| A helper used once within a script | Define a local function or class and call it in that script |
| JavaScript calling Python | Python Function code template |
| Shared classes/functions used by native Python contexts | Python module/package installed in their Script Environments |
| Shared instance or durable state across messages | Supported map values or external storage, with appropriate concurrency control |
| A Java or Rhino utility | Keep that operation in a supported JavaScript context, or implement it using Python libraries; no direct Java bridge exists |

## Create and call a Python Function

1. Open **Code Templates**, create a template, and choose **Python Function**.
2. Set **Function name** to a unique, non-reserved JavaScript identifier, such as `normalize_patient`. This is the name JavaScript calls; the template's display name is separate. Avoid collisions with existing templates or script variables.
3. Choose a ready Script Environment. Set the invocation timeout if necessary.
4. Select the applicable source filter/transformer, destination filter/transformer, or destination response transformer contexts.
5. Enable the containing library for the consuming channel. Save, validate, and deploy that channel.

For `normalize_patient`, paste this complete template body:

```python
from dataclasses import asdict, dataclass


@dataclass
class Patient:
    patient_id: str
    name: str

    def normalized(self):
        return Patient(self.patient_id.strip(), self.name.strip())


if len(arguments) != 1 or not isinstance(arguments[0], dict):
    raise ValueError("normalize_patient expects one JSON object")

raw = arguments[0]
if not isinstance(raw.get("patient_id"), str) or not isinstance(raw.get("name"), str):
    raise ValueError("patient_id and name must be strings")

patient = Patient(raw["patient_id"], raw["name"]).normalized()
result = asdict(patient)
```

Call from a JavaScript transformer with JSON input:

```javascript
msg = normalize_patient(msg);
```

For this example, the returned object intentionally contains only `patient_id` and `name`. If other fields must survive, merge those fields into the original document instead of replacing the whole root.

The function does not receive a `msg` or `tmp` global. Editing `arguments[0]` alone does not edit the calling JavaScript document; return the value and explicitly apply it. Writable map changes are returned independently and applied after the function result and all map changes validate.

The worker registers the temporary script module while it executes, so ordinary dataclasses and module-aware class definitions can resolve their module. Script globals are fresh on each invocation. The definitions in this example are created again on each call.

## Arguments and return values

The generated JavaScript wrapper accepts positional arguments without a fixed arity. The Python body enforces its own required count and types. `arguments` is a Python list, not JavaScript's special `arguments` object; keyword argument calls are not exposed through the wrapper. Use one JSON object for named options.

```python
# Function name: format_label
if len(arguments) != 1 or not isinstance(arguments[0], dict):
    raise ValueError("format_label expects an options object")
options = arguments[0]
prefix = str(options.get("prefix", "Patient"))
result = prefix + ": " + str(options["patient_id"])
```

```javascript
var label = format_label({patient_id: 'P123', prefix: 'Record'});
```

Supported argument/result data consists of null/`None`, booleans, strings, finite numbers, arrays/lists, and objects/dictionaries with string keys. Integers must be within JavaScript's safe integer range, ±(2^53−1). There is no automatic conversion of datetimes, Decimal values, bytes, class instances, functions, Java objects, or E4X objects. Cycles, undefined values, and non-finite numbers are rejected.

The engine's boxed RAW `msg` string is supported directly, including within ordinary JSON containers. Serialize E4X explicitly:

```javascript
// The Python function accepts and returns XML text.
msg = new XML(normalize_xml(msg.toXMLString()));
```

This is a caller-side conversion recipe, not an automatic XML-aware template. The `normalize_xml` template must exist and return valid XML text. For direct XML transformations and namespace handling, use the [native transformer guide](Filters-and-transformers.md).

`result` initially equals `None`; leaving it unchanged returns JavaScript `null`. A top-level `return` is invalid Python. If you define a helper, call it explicitly:

```python
def normalize(value):
    return value.strip().upper()


result = normalize(arguments[0])
```

## Calling contexts and maps

Python Functions are available from JavaScript in these selected engine contexts:

| Engine template context | Calls allowed |
| --- | --- |
| `SOURCE_FILTER_TRANSFORMER` | Source JavaScript filter rules and transformer steps |
| `DESTINATION_FILTER_TRANSFORMER` | Destination JavaScript filter rules and transformer steps |
| `DESTINATION_RESPONSE_TRANSFORMER` | Destination JavaScript response transformer steps |

They are not available from Python Reader/Writer scripts, JavaScript connector bodies, global/channel lifecycle scripts, or as direct callable globals in native Python scripts. Other selected template contexts do not create additional Python bindings; no selected contexts means no binding.

A call receives `arguments`, `result`, `message`, `phase`, `logger`, all three shared maps, and the four calling-message maps. See the [context matrix](Contexts-and-message-flow.md). It does not receive the response-transformer's `response` object; pass data explicitly if needed. A call from a JavaScript filter reports the combined source/destination **transformer** phase, whereas a native Python filter reports a filter phase.

An exception or invalid return value fails the calling JavaScript operation. The failed invocation's staged map changes are not applied. External I/O already performed remains external; a JavaScript catch cannot undo it. Earlier successful calls or steps are not rolled back as a group.

## Native Python reuse through a package

The included [channel helper example](../examples/channel-helpers/README.md) contains a complete package with a `Patient` class. Its [module](../examples/channel-helpers/src/oie_channel_helpers/__init__.py) has no runtime dependency on the toolkit, and its [packaging definition](../examples/channel-helpers/pyproject.toml) builds a wheel.

Build and publish the wheel to an operator-approved package source or wheelhouse. Add this requirement to a custom environment, then save and prepare it:

```text
oie-channel-helpers-example==0.1.0
```

Select that prepared environment in every consuming script. Imports are available in that environment only; selecting it for one step does not install the package for other steps.

Native JSON transformer:

```python
from oie_channel_helpers import Patient

patient = Patient.from_mapping(msg)
msg.update(patient.as_dict())
channelMap.put("patient_id", patient.patient_id)
```

Python Function body using the same class:

```python
from oie_channel_helpers import Patient

result = Patient.from_mapping(arguments[0]).as_dict()
```

Native JSON filter body using the same class:

```python
from oie_channel_helpers import Patient

patient = Patient.from_mapping(msg)
result = bool(patient.name)
```

All three snippets assume string `patient_id` and `name` fields; missing or incorrectly typed fields raise an error, rather than being treated as a false predicate. You can use the same import in Reader and Writer code, but those contexts construct their own input data from a polled source or serialized `payload`.

Do not put `pip install` in a channel script. Do not modify an active prepared environment or rely on an engine working-directory import. Use a versioned wheel, update the environment requirement, prepare, and redeploy consuming channels. The built-in vanilla environment is protected; create a custom environment for the example package.

## Lifetime, caching, and deployment

Workers are persistent but replaceable. Imported module code can remain cached within one worker; another worker may have its own cache. Invocation-local classes and globals are recreated. Worker eviction, timeouts, process failures, redeployment, and restarts can all discard process-local state. Avoid global connection singletons unless your package explicitly handles per-process lifetime, stale connections, and cleanup; do not treat a cached object as a transaction or cross-worker lock.

Deployment captures each enabled template's source, function name, execution identity, and prepared environment. Saving a template or preparing newer dependencies does not update an already deployed caller. Redeploy each consuming channel.

A template with invalid Python syntax or an unavailable environment can be recorded as unavailable without blocking channel deployment. Calling it fails with the recorded reason; correct the cause and redeploy. This differs from an enabled native Python rule/step/connector, which must bind successfully at deployment.

If a concurrent template save changes the snapshot while the engine compiles the channel, deployment is rejected before activation rather than mixing incompatible JavaScript wrappers and Python bindings. Finish the save and retry deployment. A stable deployment still needs behavioral testing: compile-only validation does not check imports, class constructors, argument count, or business logic.

Implementation references: [template model](../../shared/src/main/java/com/mirth/connect/plugins/python/PythonCodeTemplateProperties.java), [wrapper and function-name rules](../../shared/src/main/java/com/mirth/connect/plugins/python/PythonContract.java), [function context](../../python-sdk/oie_python/_context.py), [worker execution](../../python-sdk/oie_python/worker.py), and [template deployment snapshots](../../server/src/main/java/com/mirth/connect/plugins/python/server/TemplateSnapshots.java).
