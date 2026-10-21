# Preparing a release

## Build from public inputs

Use JDK 17, 21, or 25, Maven, and Python 3.11 or later. A running engine is not needed. Download the published stock OIE 4.6.0 archive and verify its SHA-256 before staging its API:

```sh
engine_root=$(mktemp -d "${TMPDIR:-/tmp}/python-toolkit-api.XXXXXX")
curl --fail --location \
  https://github.com/OpenIntegrationEngine/engine/releases/download/v4.6.0/oie_unix_4_6_0.tar.gz \
  --output "${engine_root}/engine.tar.gz"
echo "7c82e79027e671277e1d78d0f7bbb1c53ddf1be476f1e80c2ddbfcf7855900ea  ${engine_root}/engine.tar.gz" | shasum -a 256 --check
tar -xzf "${engine_root}/engine.tar.gz" -C "$engine_root"
python3 tools/build_with_engine.py --engine-install "${engine_root}/oie"
python3 tools/inspect_plugin.py
shasum -a 256 package/target/oie-python-toolkit-1.0.0.zip
```

Choose a fresh private directory for the download and extraction. The helper reads the engine libraries and stages them into the local Maven repository; it never starts or modifies the engine. Preserve `target/installed-api-inputs.json`, the ZIP, its checksum and test evidence, then remove the private extracted installation. Do not use or remove an active or shared engine for this procedure.

The Build and test workflow stages only six API JARs from the checksum-pinned OIE 4.6.0 archive, then removes that private staging directory. It checks JDK 17/21/25, SDK compatibility on Python 3.11–3.14, live engine behavior on OIE 4.6/4.7 with Python 3.11 and 3.14, and browser behavior against the pinned Web Administrator 1.2.0 commit. Runtime and browser jobs consume the canonical ZIP built on JDK 17. Evidence and deduplicated runtime inputs are uploaded; owned engine installations and databases are removed after evidence capture and process exit. A local pass does not establish that GitHub Actions passed; inspect the workflow results for the release commit.

## Release gates

- Confirm the engine versions claimed in the parent POM, packaged descriptors, package inspector, README and support documentation agree and refer to published stock releases. OIE 4.7.0 was published on 2026-10-21. Its published Unix archive SHA-256 is `ab08558873dc6be1e474afaacc46efe94d29c945cd3381c10d0ae548ecb429de`, matching the previously qualified draft input. For future releases, compare the published archive SHA-256 with the qualified input and rerun isolated qualification if it differs.
- Run `mvn verify`, SDK tests and package inspection on the release source. Preserve the input hashes and test results for the exact ZIP to be published.
- Run the isolated return and editor-service checks against each claimed stock version. Their receipts must show successful assertions, terminated owned processes, captured final database observations and completed installation/database cleanup. Shared pinned inputs remain available after cleanup; removed installation paths and databases do not.
- Load the Python Reader and Writer placeholders in a real Swing Administrator. Open existing connectors and save without losing their Python scripts, environment selections or connector properties. Headless panel tests alone do not close this check.
- Confirm the minimum CPython runtime can prepare the built-in environment and execute live Reader, Writer, filter, transformer and Function scripts. SDK tests alone do not establish the full engine path.
- Check project attribution, repository URL, version numbers, signed-release versus unsigned-local-build wording and installation instructions. The GitHub repository description should explain that this is a community plugin.

### Real Swing save/reopen qualification

On a machine with a graphical desktop, use the stock release archive and a JavaFX-enabled JDK for the Administrator:

```sh
python3 tools/run_swing_isolated.py --version 4.6.0 \
  --engine /path/to/oie_unix_4_6_0.tar.gz \
  --java-home /path/to/server-jdk \
  --swing-java-home /path/to/javafx-enabled-jdk \
  --python /path/to/python3.14 --evidence target/swing-4.6.0
```

Repeat with the 4.7.0 archive and a new evidence directory. The runner launches the shipped, visible Administrator against an isolated engine. It checks two channels with distinct scripts and environments, switches between two Writer destinations, invokes the native save action, leaves and reopens the editor, then starts a fresh Administrator process and reopens again. It compares all connector properties through the live API and final stored database rows. Screenshots, input hashes, logs and process/cleanup receipts are retained; owned installations and databases are removed. Administrator preferences are isolated from the operator's preferences. This requires a desktop; a headless panel test does not replace it.

## Behavior and failure checks

| Surface | Happy path | Failure, ordering and retry checks |
| --- | --- | --- |
| Build inputs | Published archive matches the pinned SHA-256; installed API hashes are retained | Download failure or checksum mismatch stops before staging; partial staging cannot publish a package; rerun the helper to complete staging |
| Java versions | The full reactor passes on JDK 17, 21 and 25 | Run adapter and scoped-transformer tests against actual stock API classes; no experimental bytecode flags |
| Python versions | SDK tests and live engine qualification cover the minimum runtime | Missing `venv`/`ensurepip`, invalid returns and unavailable interpreter must produce actionable failures |
| Editor settings | Invocation timeout is visible in script and Function editors | Editing invalidates pending results; read-only Functions retain disabled controls; stale session/environment results are discarded |
| Registry persistence | Compare-and-set updates preserve unrelated rows | Concurrent initial insert and update, stale predecessor, invalid document, lost commit acknowledgement, and restored connection state; retain final rows before cleanup |
| Validation cleanup | Private database shutdown is confirmed; hashes and final rows are preserved | Failed test bodies still close resources; evidence/shutdown failure prevents removal; shared engines and uncertain historical ownership are excluded |
| Publishing | Release ZIP matches the inspected, qualified input hash | A rebuilt ZIP is a new input requiring inspection and qualification; do not silently replace qualified bytes |

## Configure signing and catalog publication

This follows the debugger's SSL.com eSigner pipeline. Add these GitHub Actions secrets to `gibson9583/oie-python-toolkit`, using the same signing account configuration as the signed plugins:

- `SSL_COM_USERNAME`
- `SSL_COM_PASSWORD`
- `SSL_COM_CREDENTIAL_ID`
- `SSL_COM_TOTP_SECRET`

Add repository variable `SSL_COM_CERT_SHA256`: the SHA-256 fingerprint of the intended signing certificate, with or without colon separators. It is a public certificate identity, not a secret. The verifier requires that signer, a trusted chain and a trusted timestamp on every payload entry in all three JARs.

For catalog automation, add optional secret `CATALOG_TOKEN`, a fine-grained token with Contents and Pull requests read/write on `gibson9583/oie-community-catalog`. Its absence skips catalog submission with a warning; it does not discard a successful release. The Catalog PR workflow can be run later for the published tag. Identity, compatibility, ZIP filename and store documentation come from the tagged `oie.json`.

GitHub does not allow reading encrypted secret values back from another repository. Configure them from the same credential source used for the other signed plugins; do not put values in source files, command arguments, logs or release notes.

The credential-free Signing checks workflow exercises the pinned CodeSignTool 1.3.2 on JDK 11, then tests signing, timestamps, failure atomicity and publication behavior with local test certificates. Verification runs on JDK 17. No paid SSL.com signature is requested by CI or these tests.

## Publish version 1.0.0

1. Complete the manual Swing placeholder check and review the documented deployment limits. Python 3.11 is the supported minimum; Python 3.14 is recommended, and operators install the interpreter separately.
   Synchronize the developer wiki from `docs/wiki` before publication. Web Administrator 1.2.0's delayed editor upgrade can reset focus/cursor while preserving the draft; this shipped-host limitation is recorded in browser results and troubleshooting documentation. A host fix must be published before raising the pinned host version; local uncommitted host changes are not release evidence.
2. Commit the reviewed source and run Build and test plus Signing checks. Source versions, SDK handshake, browser metadata, catalog manifest and tag must agree. `python3 tools/release_contract.py` checks this without publishing anything.
3. Create and push `v1.0.0` on the approved commit. This is the publication trigger; preparing or committing workflow files does not publish a release.
4. Release invokes the full CI and Signing checks workflows. It refuses any existing published or draft release for that tag before requesting signatures. It downloads the exact canonical CI ZIP, signs all three JARs, verifies payload preservation, signer, coverage, trust and timestamps, then runs the signed ZIP against both supported engines before creating `SHA256SUMS`.
5. Publication verifies that the tag still names the tested commit. It creates a new draft release, uploads the signed ZIP and checksums to that returned release ID, confirms both uploads, and only then publishes that ID. Existing releases/assets are never adopted or replaced. A partial failure leaves its draft unpublished; inspect it before retrying. A retry does not silently resume or overwrite a draft.
6. The catalog job reads the published signed ZIP, computes its checksum and opens a catalog PR using the tagged manifest. The release job calls it explicitly because a release created with `GITHUB_TOKEN` does not trigger another release-event workflow.

The release assets are `oie-python-toolkit-1.0.0.zip` and `SHA256SUMS`. Local Maven builds are unsigned. To inspect the published signed ZIP, check out the matching source version and run:

```sh
python3 tools/inspect_plugin.py --plugin /path/to/oie-python-toolkit-1.0.0.zip \
  --certificate-sha256 "$SSL_COM_CERT_SHA256"
```

## Release failure matrix

| Condition | Required behavior | Validation |
| --- | --- | --- |
| Source/tag/version mismatch | Stop before signing or publication | Release contract tests and CI preflight |
| Missing or ambiguous ZIP/JAR; extra signing output | Reject; preserve the original ZIP | Signing regression tests |
| Wrong signer, missing timestamp, unsigned entry or changed payload | Reject the entire signed package | Real Java signature/coverage checks and local certificate tests |
| Signing failure or interruption before replacement | Original ZIP remains unchanged | Atomic replacement regressions |
| Existing release or draft | Stop before paid signing; never replace assets | Preflight tests include later API pages and API failure |
| Tag moved/deleted after validation | Stop before creating a draft | Publication regression tests |
| Create conflict or ambiguous creation response | Never adopt an existing release ID | Publication regression tests |
| Partial/unconfirmed upload | Keep the newly owned draft unpublished | Publication regression tests |
| Publication response uncertain | Fail; report owned release ID for inspection; do not retry by tag | Publication regression tests |
| Missing catalog token | Preserve the release; warn and allow later catalog submission | Explicit catalog configuration branch |
