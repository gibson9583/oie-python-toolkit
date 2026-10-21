# Operator configuration and recovery

For channel authors, see the [developer wiki](wiki/Home.md) and [environment/deployment guide](wiki/Environments-and-deployment.md). This page covers operator policy and recovery.

Settings resolve from `OIE_PYTHON_RUNTIME_<KEY>` environment variables, then existing `org.openintegrationengine.python-runtime.<key>` JVM properties, then the plugin-owned `operator.properties` file, then defaults. Environment keys are uppercased, replacing dots/dashes with underscores. The API exposes approved runtime/source IDs, not local executables or credentials.

The built-in **Vanilla Python (standard library)** profile needs no administrator configuration. The plugin seeds it without replacing existing profiles and queues its preparation automatically on each starting node. It has an empty requirements list and uses the plugin's fixed, offline standard-library source policy. Preparation creates a private environment and installs only the bundled OIE SDK from a local hash-pinned wheel. It performs no package download. The profile's definition is protected; use a separate profile for additional dependencies.

With the default operator policy, `python3` resolves on the engine process's PATH. The exact CPython patch version is probed and displayed. A custom operator policy keeps interpreter approval authoritative. Execution requires CPython 3.9 or later with its local `venv` and `ensurepip` modules.

Configure this plugin without editing engine files: create `operator.properties` under the engine's application-data directory, in `python-runtime/`. The ZIP includes `operator.properties.example`. Use absolute interpreter paths; for example:

```text
runtimes=cpython314=/opt/python/bin/python3.14
runtime.cpython314.label=CPython 3.14
sources=internal
source.internal.indexUrl=https://packages.example.org/simple
cacheDir=/var/lib/oie/python-runtime
```

Keep the policy file readable only by the service account and restart the plugin service after policy changes. Back it up separately: registry exports exclude local paths and credentials. It must be a regular file no larger than 64 KiB.

For offline preparation, set the source's `wheelhouse` to an approved local directory. Include all compatible transitive wheels for each target. Credentials use source `username` and `password` properties or corresponding protected environment values; never place credentials or pip options in profile requirements. Prepare uses binary wheels only and installs the committed hash lock without network access into a private final-path venv. Virtual environments are not portable across installations.

Approved source policy and actual interpreter version/ABI/OS/architecture affect definition/target/artifact identities. Updating them requires preparation and redeployment. Preparation of an unchanged definition reuses its lock; **Refresh dependencies** explicitly requests a new resolution. A profile rename may reuse files but does not change an active binding.

## Initial limits

The default node limits are 8 execution workers, 64 waiting acquisitions, 5-second acquisition, 10-second startup, 30-second channel invocation, and a 5-minute operator invocation maximum. Frames are capped at 16 MiB, Reader polls at 1,000 messages, and captured logs at 64 KiB. Idle workers expire after 60 seconds and recycle after 1,000 calls. Preparation has one concurrent job and a 10-minute deadline; sample testing allows two concurrent operations. These are initial resource limits, not throughput guarantees.

Operator keys include `maxWorkers`, `maxWorkersPerChannel`, `maxQueuedAcquisitions`, `acquireTimeoutMillis`, `startupTimeoutMillis`, `maxTimeoutMillis`, `maxFrameBytes`, `maxReaderMessages`, `logLimitBytes`, `idleEvictMillis`, `recycleAfterInvocations`, `prepareTimeoutMillis`, `terminationGraceMillis`, `maxConcurrentPreparations`, `maxConcurrentTests`, and `maxRequirementsBytes`. Select values from measurements for your messages, native packages, and concurrency.

Each Python rule, step, Reader, Writer and function binding has its own worker pool. By default, `maxWorkersPerChannel` equals `maxWorkers`, so one channel can use every worker on the node. Set `maxWorkersPerChannel` lower to stop one busy channel from using all workers. Keep it at least as high as the number of Python scripts in each channel. A lower value stops and starts workers for every message.

## Nodes, backups, and conflicts

During plugin-service startup, quiesce toolkit profile writes on other nodes. Stock OIE loads and rewrites generic plugin properties before the service callback, without compare-and-set. The plugin checks existing registry data before supplying defaults and fails startup on an unavailable or invalid read; it cannot make that host rewrite transactional with concurrent node edits. Normal single-node startup has no active plugin API writers.

Profiles/locks are persisted; readiness, jobs, processes, paths, and credentials are local. The built-in vanilla profile prepares automatically on each executing node; custom profiles need explicit preparation there. Automatic vanilla preparation retries failures up to three times for one generation. Explicit cancellation leaves that generation stopped; **Prepare saved revision** can retry it. A new plugin instance, registry epoch/revision, interpreter target or SDK identity resumes automatic preparation. Use node-affine connections for prepare/job/log/cancel/validate/test operations. A load balancer without affinity cannot manage node-local jobs reliably.

Registry edits use epoch/revision tokens and bounded mutation receipts. A 409 preserves the draft and requires reloading/reconciling the conflict. A 503 persistence outcome is uncertain: reconcile the same request ID before retrying rather than generating another mutation. Receipt retention is bounded; it is not indefinite exactly-once storage.

Export includes versioned custom profile definitions and matching saved locks when present, excluding local readiness, credentials and the automatically supplied built-in profile. The registry allows 1,000 custom profiles plus one validated built-in profile; portable exports/imports retain the 1,000-custom-profile limit. Channel XML keeps profile UUID references without embedding definitions. Import is idempotent for matching definitions; conflicts require explicit mappings/updates. Names never resolve missing UUIDs implicitly. Only a null or empty profile reference uses the built-in default; nonempty unknown references remain unresolved. Archive uses tombstones and rejects known references or active preparations.

An existing custom environment with the vanilla display name is preserved; the plugin chooses a unique suffix for its own default. A preexisting unowned entry at the reserved default UUID is preserved and reported as a collision. The plugin will not execute that entry as the vanilla default. Optional default-initialization failures are reported without disabling otherwise valid custom profiles; invalid registry reads still fail closed.

Use plugin import/export for ordinary profile migration. Before a whole-server configuration restore, quiesce plugin API activity, then **restore → prepare each target node → deploy**. The stock engine commits generic configuration before the plugin callback; the plugin cannot add pre-write validation to that host operation. A successfully canonicalized restore creates a fresh registry epoch. A malformed or conflicting replacement requires operator reconciliation. It never installs packages. Existing admitted deployment calls retain their bindings and leases until retirement. A deploy attempted before local preparation returns an actionable unavailable-environment diagnostic.

## Deployment boundaries

The engine can execute JavaScript deploy scripts before Python deployment checks complete. A rejected Python deployment cannot undo those scripts' external effects. Keep deploy-script side effects idempotent and inspect deployment diagnostics before sending production traffic.

Python calls remain unavailable until their captured deployment binding activates. If activation fails, the stock engine may still display the channel as deployed; inspect the Python diagnostic, correct its cause, and redeploy. Saving or preparing an environment does not replace a running channel's binding.

## Failures and cleanup

Compile validation checks syntax, request/context rules and environment readiness; it does not execute code or validate runtime result values. Use explicit Test for imports and script behavior. Every script runs its top-level statements once per invocation with fresh globals. Transformers use `msg`/`tmp` directly; filters, Reader, Writer, and Python Function scripts assign `result`. Test uses synthetic context and never dispatches a message or writes live maps, but external script I/O can still occur. Do not automatically retry a Test after a transport failure.

Every script can use `logger`; its calls are captured and forwarded at their requested level. Native source and destination filters use `filter`, source and destination transformers use `transformer`, response transformers use `response`, and Readers/Writers use `js-connector`. Python Function logs follow the calling transformer phase, including calls from JavaScript filters. `print()` and writes to standard output/error are captured and forwarded at INFO. Sample Test returns captured records in its output panel. The per-call log limit can truncate output; engine logging levels and retention settings still apply, including whether DEBUG records are written.

Preparation failures report their stage without changing active deployments. Cancel stops owned process work and prevents publishing partial readiness. Wrong-node job requests report the owning node explicitly. A pool limit or deadline yields a bounded diagnostic. Malformed worker traffic invalidates that worker; no execution replay occurs.

Environment cleanup respects build/deployment/worker leases. Uncertain termination retains ownership records and environments. Operators should investigate retained ownership before removing files.

Preparation/probe/validation commands use an independent POSIX supervisor. It ends its process group when the JVM disappears and on normal completion, preserving the main command's exit status in a temporary owned receipt. Java verifies group disappearance before returning; an unconfirmed group retains resources and blocks new process creation/collection. Each worker also owns an independent watchdog, so operating-system process counts exceed the configured worker count.

Supervisors and the compile-only validator start with site processing disabled. Workers bootstrap the verified bundled SDK before dependency `.pth` processing; their independent watchdog and protocol stream reservation already exist when dependency startup code runs. Execution then enables the prepared venv's normal site/dependency imports.

Platform support is limited to the [supported configuration](validation.md#supported-configuration). The runtime reports Windows interpreters as unavailable. Process separation does not restrict a trusted script's network/filesystem permissions.

A soft plugin-service stop retires deployment workers and preparation jobs. Already captured API requests own their sample-Test workers and Validate/probe commands separately: they can finish or reach their bounded deadlines after that stop, retaining their artifact leases and final cleanup. Soft stop is not a universal cancellation of those requests. JVM exit still activates their independent parent-death containment. Avoid starting new administrative work during a shutdown window.
