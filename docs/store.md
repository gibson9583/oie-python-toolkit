# Python Toolkit

Python filters and transformer steps, reusable Python Functions, polling Python Reader and Python Writer connectors, and shared script environments for OIE 4.6.0 and 4.7.0.

## Requirements

- Web Administrator extension API 4.10 or newer, supplied by Web Administrator 1.2.0 or newer.
- Operator-installed CPython 3.11 or newer with `venv` and `ensurepip` on a POSIX host. Python 3.14 is recommended; the plugin does not bundle or install Python.
- Java 17 or newer for the engine.

## Install

Download the signed ZIP and `SHA256SUMS` from the GitHub release. Verify the ZIP checksum, install it through the engine extension manager, restart the engine, and reload Web Administrator. The extension is listed as **Python Runtime**; its settings page is **Script Environments**. All three release JARs are signed; local development builds remain unsigned.

The built-in **Python** environment prepares automatically from the approved interpreter without package downloads. Add a Python rule, step, Reader, Writer or Function and select that environment. Use **Script Environments** for profiles with additional dependencies. Prepare changed profiles and redeploy consuming channels to adopt their changes.

Python scripts execute with the engine account's filesystem and network access. Install and execute trusted scripts and dependencies. Windows execution is unavailable. The Swing Administrator provides Reader/Writer placeholders that preserve connector properties; Python authoring uses Web Administrator.

See the [developer wiki](https://github.com/gibson9583/oie-python-toolkit/wiki) for usage and the [operations guide](https://github.com/gibson9583/oie-python-toolkit/blob/main/docs/operations.md) for deployment limits.
