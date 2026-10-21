# Python Toolkit developer wiki

This guide explains how to write, reuse, test, and deploy Python in Open Integration Engine, especially when moving code from JavaScript. It describes the toolkit implementation in this repository. The extension manager calls the plugin **Python Toolkit**; its dependency settings are under **Script Environments**.

Screenshots are taken in the OIE Web Administrator, using an isolated OIE 4.7 engine and synthetic example data.

## Start here

| You want to… | Read |
| --- | --- |
| Build a first channel with working examples | [Getting started](Getting-started.md) |
| Know exactly which variables and results exist in each editor | [Contexts and message flow](Contexts-and-message-flow.md) |
| Translate `$c`, E4X, logging, JSON, dates, and engine utilities | [JavaScript to Python](JavaScript-to-Python.md) |
| Understand map scope, mutation, serialization, and concurrency | [Maps and values](Maps-and-values.md) |
| Filter messages or transform JSON, XML, HL7, and raw text | [Filters and transformers](Filters-and-transformers.md) |
| Produce RawMessage-style inputs or return Writer responses/statuses | [Connectors](Connectors.md) |
| Create classes, call Python from JavaScript, or share Python modules | [Functions, classes, and modules](Functions-classes-and-modules.md) |
| Install dependencies, promote profiles, and update deployed scripts | [Environments and deployment](Environments-and-deployment.md) |
| Use IntelliSense, formatting, validation, samples, and diagnostics | [Editor, testing, and troubleshooting](Editor-testing-and-troubleshooting.md) |

## The execution model

Python scripts run in CPython worker processes. The engine supplies ordinary Python variables for the selected context. Write the script body directly, import modules, define classes or helpers, and call those helpers yourself. Filters, Readers, Writers, and Python Functions return their output; the runtime handles function execution internally. No named entry point or `ctx` variable is required. Transformers continue to edit `msg`/`tmp` and do not support top-level `return`.

Each invocation gets fresh script globals. A worker can survive many invocations, and imported modules can remain cached in that worker. Do not use module globals, class instances, or process identity as durable channel state. Maps and external storage have their own scope and concurrency rules.

| Surface | How the engine receives the result |
| --- | --- |
| Source or destination Python filter | Return exactly `True` or `False`; an empty body or fallthrough accepts as `True` |
| Source, destination, or response Python transformer | Edit or replace `msg` and, when present, `tmp`; response transformers can also edit `response` fields |
| Python Reader | Return `None`, a string, `Message`, or a list mixing strings and `Message` objects |
| Python Writer | Return `None`, a string, a `Response`, or a `Status` |
| Python Function code template | Read positional `arguments`; return a JSON-compatible value for the calling JavaScript |

The toolkit copies supported documents and maps across the Java/Python boundary. It validates the returned values before applying that invocation's changes. Python does not receive live Java/Rhino objects. External network, database, and filesystem operations are ordinary Python operations and cannot be rolled back by the toolkit.

## Choosing a reuse mechanism

- Use a **Python filter or step** for Python logic directly in the channel's filter/transformer lists.
- Use a **Python Function code template** when JavaScript needs to call Python and receive JSON-compatible data. The template body can contain classes and helper functions; its public interface remains a JavaScript function.
- Use a **Python package in a Script Environment** to share classes/functions across native Python filters, transformers, Readers, Writers, and Python Functions. A code template is not an importable Python module.

All eight Python editors use Python IntelliSense and formatting when their environment and permissions are available. See the [editor guide](Editor-testing-and-troubleshooting.md) for prerequisites and limitations.

## Compatibility and deeper references

The plugin targets stock OIE 4.6.0 and 4.7.0 with Web Administrator extension API 4.10 or newer. Runtime execution requires an approved CPython 3.11+ interpreter with `venv` and `ensurepip`; the plugin does not install an operating-system interpreter. The Web Administrator is the supported authoring interface. Swing connector placeholders preserve existing scripts but do not provide Python authoring.

Python lifecycle/global/deploy scripts, Iterator-nested Python rules/steps, Java/Rhino interop, JSON scalar transformer roots, and binary transformer replacements are outside the supported contract. For exact qualifications and operational limits, use the maintained references below; availability of a feature is not a release-readiness certification.

- [Repository README and build instructions](../../README.md)
- [Concise SDK contract](../sdk.md)
- [Operator configuration and recovery](../operations.md)
- [Testing and support](../validation.md)

Examples identify their context and data type. Sample IDs, paths, package names, and endpoints must be adapted to the channel. The [shared-class example](../examples/channel-helpers/README.md) is included as a small, complete package.
