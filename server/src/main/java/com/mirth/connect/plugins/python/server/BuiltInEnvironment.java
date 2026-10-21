/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.time.Instant;
import java.util.Objects;
import com.mirth.connect.plugins.python.PythonApi;
import com.mirth.connect.plugins.python.PythonContract;

/** One immutable, plugin-owned stdlib definition; preparation remains node local. */
final class BuiltInEnvironment {
    private static final int MAX_AUTOMATIC_ATTEMPTS = 3;
    private final PythonRuntime runtime;
    private volatile boolean closed;
    private String attemptKey;
    private int attempts;
    private String seedEpoch;
    private int seedAttempts;
    private String cancelledJobId;
    private String cancelledKey;
    private volatile String failure;

    BuiltInEnvironment(PythonRuntime runtime) { this.runtime = runtime; }

    static boolean stdlibOnly(String requirements) {
        for (String line : Requirements.normalize(requirements).split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) return false;
        }
        return true;
    }

    static boolean owned(PythonApi.Profile profile) {
        return profile != null && profile.builtInDefault && PythonContract.DEFAULT_PROFILE_ID.equals(profile.id)
                && PythonContract.BUILTIN_SOURCE_ID.equals(profile.packageSourceId) && stdlibOnly(profile.requirementsText)
                && !profile.archived && profile.refreshGeneration == 0 && "python".equals(profile.language)
                && profile.name != null && !profile.name.trim().isEmpty()
                && Objects.equals(profile.definitionFingerprint, PreparationManager.definitionFingerprint(profile))
                && profile.resolvedLocks != null && profile.resolvedLocks.values().stream().allMatch(lock -> lock != null
                        && lock.lockedRequirements != null && lock.lockedRequirements.isEmpty()
                        && Objects.equals(lock.definitionFingerprint, profile.definitionFingerprint)
                        && Objects.equals(lock.packageSourcePolicy, OperatorConfig.builtInSourcePolicy()));
    }

    static String selectedId(String raw) {
        return raw == null || raw.isEmpty() ? PythonContract.DEFAULT_PROFILE_ID : raw;
    }

    static void requireWritable(String id) {
        if (PythonContract.DEFAULT_PROFILE_ID.equals(id)) throw ApiFailure.conflict("BUILTIN_PROFILE_READ_ONLY",
                "The built-in standard-library environment is managed automatically. Create a separate environment for packages.");
    }

    static String defaultId(PythonApi.Registry registry) {
        return owned(registry.profiles.get(PythonContract.DEFAULT_PROFILE_ID)) ? PythonContract.DEFAULT_PROFILE_ID : null;
    }

    static String preparationHint(PythonApi.Profile profile) {
        return owned(profile) ? "This standard-library environment prepares automatically. Wait for it to finish, or use Prepare to retry, then deploy."
                : "Prepare this environment on this node, then deploy.";
    }

    static PythonApi.Profile definition(OperatorConfig config) {
        var profile = new PythonApi.Profile(); profile.id = PythonContract.DEFAULT_PROFILE_ID;
        profile.builtInDefault = true; profile.name = PythonContract.DEFAULT_PROFILE_NAME;
        profile.description = "Host-approved Python with the standard library and bundled OIE SDK; no third-party packages.";
        profile.runtimeId = config.runtimes.containsKey("python3") ? "python3" : config.runtimes.keySet().iterator().next();
        profile.packageSourceId = PythonContract.BUILTIN_SOURCE_ID; profile.requirementsText = "";
        profile.revision = 1; profile.createdAt = profile.updatedAt = Instant.now().toString();
        profile.updatedBy = "python-runtime:builtin"; profile.definitionFingerprint = PreparationManager.definitionFingerprint(profile);
        return profile;
    }

    static boolean seed(RegistryStore registry, OperatorConfig config) {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (registry.snapshot().profiles.containsKey(PythonContract.DEFAULT_PROFILE_ID)) return false;
            try { return registry.change(next -> {
                if (next.profiles.containsKey(PythonContract.DEFAULT_PROFILE_ID)) return null;
                var profile = definition(config); String base = PythonContract.DEFAULT_PROFILE_NAME; int suffix = 0;
                while (nameTaken(next, profile.name)) profile.name = base + " (built in)" + (++suffix == 1 ? "" : " " + suffix);
                next.profiles.put(PythonContract.DEFAULT_PROFILE_ID, profile); return Boolean.TRUE;
            }) != null; }
            catch (ApiFailure failure) {
                if (!"REGISTRY_WRITE_CONFLICT".equals(failure.code) && !"OUTCOME_UNKNOWN".equals(failure.code)) throw failure;
                if (registry.snapshot().profiles.containsKey(PythonContract.DEFAULT_PROFILE_ID)) return false;
                if (attempt == 2) throw failure;
            }
        }
        return false;
    }

    private static boolean nameTaken(PythonApi.Registry registry, String name) {
        return registry.profiles.values().stream().anyMatch(profile -> !profile.archived && profile.name.trim().equalsIgnoreCase(name.trim()));
    }

    synchronized void initialize(PythonApi.Registry checked) { ensureSeed(checked); }

    private boolean ensureSeed(PythonApi.Registry checked) {
        if (closed) return false;
        if (checked.profiles.containsKey(PythonContract.DEFAULT_PROFILE_ID)) {
            if (!owned(checked.profiles.get(PythonContract.DEFAULT_PROFILE_ID))) {
                failure = "The reserved default profile ID is occupied by another definition; existing data was preserved.";
                return false;
            }
            return true;
        }
        if (!Objects.equals(seedEpoch, checked.registryEpoch)) { seedEpoch = checked.registryEpoch; seedAttempts = 0; }
        if (seedAttempts >= MAX_AUTOMATIC_ATTEMPTS) return false;
        seedAttempts++;
        try { seed(runtime.registry, runtime.config); return true; }
        catch (RuntimeException caught) {
            failure = "The default standard-library environment could not be added; existing environments remain available. "
                    + caught.getMessage() + (seedAttempts >= MAX_AUTOMATIC_ATTEMPTS ? " Restart or restore the registry to retry automatic initialization." : "");
            return false;
        }
    }

    /** Called only by owned housekeeping; never prepares arbitrary restored definitions. */
    synchronized void tick() {
        if (closed) return;
        try {
            if (!ensureSeed(runtime.registry.snapshot())) return;
            var registry = runtime.registry.snapshot(); var profile = registry.profiles.get(PythonContract.DEFAULT_PROFILE_ID);
            if (!owned(profile)) { failure = "The reserved default profile ID is occupied by another definition; existing data was preserved."; return; }
            var readiness = runtime.readiness(profile, registry.registryEpoch);
            String key = registry.registryEpoch + '|' + profile.revision + '|' + profile.definitionFingerprint + '|' + readiness.targetFingerprint + '|' + runtime.sdk.wheelSha256();
            if (!Objects.equals(key, attemptKey)) { attemptKey = key; attempts = 0; }
            if ("ready".equals(readiness.state) || "preparing".equals(readiness.state)) { failure = null; return; }
            // latestFor reads only jobs created by this manager instance. A
            // new plugin start therefore permits automatic preparation again.
            var latest = runtime.preparation.latestFor(profile.id, profile.definitionFingerprint, registry.registryEpoch, profile.revision);
            if (latest != null && "CANCELLED".equals(latest.state)) {
                if (!Objects.equals(cancelledJobId, latest.id) && (latest.targetFingerprint == null
                        || Objects.equals(latest.targetFingerprint, readiness.targetFingerprint))) {
                    cancelledJobId = latest.id; cancelledKey = key;
                }
                if (Objects.equals(cancelledJobId, latest.id) && Objects.equals(cancelledKey, key)) {
                    failure = "Automatic standard-library preparation was cancelled. Use Prepare to retry."; return;
                }
            }
            if (attempts >= MAX_AUTOMATIC_ATTEMPTS) {
                failure = "Automatic standard-library preparation failed after " + MAX_AUTOMATIC_ATTEMPTS
                        + " attempts. Inspect the preparation job and use Prepare to retry.";
                return;
            }
            if (closed) return;
            attempts++;
            runtime.preparation.prepare(profile.id, registry.registryEpoch, profile.revision, () -> !closed
                    && owned(runtime.registry.snapshot().profiles.get(PythonContract.DEFAULT_PROFILE_ID)));
            failure = null;
        } catch (RuntimeException failure) { this.failure = "Automatic standard-library preparation is unavailable: " + failure.getMessage(); }
    }

    String failure() { return failure; }
    void close() { closed = true; }
}
