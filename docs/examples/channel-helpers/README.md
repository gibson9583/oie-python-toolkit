# Shared class example

This small package demonstrates importing the same Python class in filters, transformers, Readers, Writers, and Python Function code templates. It normalizes two strings; it is not a patient schema or clinical validator. Missing fields raise `KeyError`; non-string fields raise `ValueError`. Empty strings are allowed so the consuming script can choose its policy.

See [Functions, classes, and modules](../../wiki/Functions-classes-and-modules.md) for context-specific invocation examples.

## Files

- [pyproject.toml](pyproject.toml): package metadata and wheel build configuration.
- [oie_channel_helpers](src/oie_channel_helpers/__init__.py): the `Patient` dataclass, `from_mapping`, and `as_dict`.

## Build and install for a channel

On a development/build machine with the `build` frontend available, run from this example directory:

```sh
python -m build --wheel
```

The build frontend may obtain its declared build tools according to the build machine's package policy. This is separate from the engine's wheel-only environment preparation. Place the resulting wheel in an approved wheelhouse or publish it through your normal internal package process. The example is not assumed to exist on a public package index.

Create a custom Script Environment using that approved source and the requirement `oie-channel-helpers-example==0.1.0`. Save, prepare, select the profile in each script, then deploy its channel. Do not add a local filesystem path, editable requirement, or `pip` command to the environment requirements.

The package distribution name contains hyphens; the Python import name uses underscores:

```python
from oie_channel_helpers import Patient

patient = Patient.from_mapping({"patient_id": " P123 ", "name": " Ada "})
assert patient.as_dict() == {"patient_id": "P123", "name": "Ada"}
```

Return `patient.as_dict()` from a Python Function; do not return the `Patient` instance. For changes, increment the package version, supply the new wheel, update the profile requirement, prepare, and redeploy. No package build or publication is performed merely by opening this example.
