/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirth.connect.plugins.python.PythonApi;
import com.mirth.connect.plugins.python.PythonApi.Diagnostic;
import com.mirth.connect.plugins.python.PythonApi.LocalReadiness;
import com.mirth.connect.plugins.python.PythonApi.LockedRequirement;
import com.mirth.connect.plugins.python.PythonApi.Profile;
import com.mirth.connect.plugins.python.PythonApi.ProfileView;
import com.mirth.connect.plugins.python.PythonApi.Registry;
import com.mirth.connect.plugins.python.PythonApi.ResolvedLock;

/** The node-local Python runtime: registry, preparation, bindings and workers. */
public final class PythonRuntime {

    private static final Logger logger = LogManager.getLogger(PythonRuntime.class);
    private static volatile PythonRuntime instance;

    final OperatorConfig config;
    final Sdk sdk;
    final Map<String, String> baseEnv;
    final Interpreters interpreters;
    final RegistryStore registry;
    final ArtifactStore artifacts;
    final PreparationManager preparation;
    final WorkerPools pools;
    final Deployments deployments;
    final Executor executor;
    final EngineAccess engine;
    final ScheduledExecutorService housekeeping;
    final BuiltInEnvironment builtIn;
    private final ObjectMapper mapper = PythonApi.mapper();
    private final Path workDir;

    public PythonRuntime(OperatorConfig config, RegistryStore registry, EngineAccess engine) throws IOException {
        this.config = config;
        this.registry = registry;
        this.engine = engine;
        Files.createDirectories(config.cacheDir);
        Path home = config.cacheDir.resolve("home");
        Files.createDirectories(home);
        this.workDir = config.cacheDir.resolve("work");
        Files.createDirectories(workDir);
        this.sdk = new Sdk(config.cacheDir);
        this.baseEnv = ProcessControl.baseEnvironment(home);
        this.interpreters = new Interpreters(config, sdk, baseEnv);
        this.artifacts = new ArtifactStore(config.cacheDir);
        registry.beforeChange(artifacts::invalidateCollection);
        this.pools = new WorkerPools(config);
        this.deployments = new Deployments(this);
        this.executor = new Executor(this);
        this.housekeeping = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "python-runtime-housekeeping");
            t.setDaemon(true);
            return t;
        });
        this.preparation = new PreparationManager(config, registry, interpreters, artifacts, sdk, baseEnv, this::collectGarbage);
        this.builtIn = new BuiltInEnvironment(this);
    }

    public void start() {
        // Invalid/unavailable existing storage still fails closed. Failure to
        // add the optional default must not disable valid custom environments.
        builtIn.initialize(registry.snapshot());
        instance = this;
        housekeeping.scheduleWithFixedDelay(builtIn::tick, 0, 30, TimeUnit.SECONDS);
        housekeeping.scheduleWithFixedDelay(pools::evictExpired, 10, 10, TimeUnit.SECONDS);
        housekeeping.scheduleWithFixedDelay(this::reapQuietly, 30, 30, TimeUnit.SECONDS);
        housekeeping.schedule(this::collectGarbage, 60, TimeUnit.SECONDS);
    }

    public void stop() {
        if (builtIn != null) builtIn.close();
        if (instance == this) {
            instance = null;
        }
        Throwable failure = null;
        try { deployments.shutdown(); } catch (Throwable caught) { failure = accumulate(failure, caught); }
        try { preparation.shutdown(); } catch (Throwable caught) { failure = accumulate(failure, caught); }
        try { pools.shutdown(); } catch (Throwable caught) { failure = accumulate(failure, caught); }
        try { housekeeping.shutdown(); } catch (Throwable caught) { failure = accumulate(failure, caught); }
        try { housekeeping.awaitTermination(config.terminationGraceMillis + 1000, TimeUnit.MILLISECONDS); }
        catch (InterruptedException caught) { Thread.currentThread().interrupt(); failure = accumulate(failure, caught); }
        catch (Throwable caught) { failure = accumulate(failure, caught); }
        try { housekeeping.shutdownNow(); } catch (Throwable caught) { failure = accumulate(failure, caught); }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException error) throw error;
        if (failure != null) throw new IllegalStateException("Python Runtime cleanup did not complete", failure);
    }

    private static Throwable accumulate(Throwable first, Throwable next) {
        if (first == null) return next;
        if (next != first) first.addSuppressed(next);
        return first;
    }

    public static PythonRuntime require() {
        PythonRuntime runtime = instance;
        if (runtime == null) {
            throw new IllegalStateException("The Python Runtime extension is not running on this server");
        }
        return runtime;
    }

    static PythonRuntime current() {
        return instance;
    }

    public RegistryStore registry() {
        return registry;
    }

    public Deployments deployments() {
        return deployments;
    }

    public Executor executor() {
        return executor;
    }

    public PreparationManager preparation() {
        return preparation;
    }

    public OperatorConfig config() {
        return config;
    }

    public EngineAccess engine() {
        return engine;
    }

    Map<String, String> workerEnvironment() {
        Map<String, String> env = new TreeMap<>(baseEnv);
        env.put("OIE_PYTHON_SDK_SOURCE", sdk.sourceDir().toString());
        env.put("OIE_PYTHON_MAX_FRAME", Integer.toString(config.maxFrameBytes));
        env.put("OIE_PYTHON_LOG_LIMIT", Integer.toString(config.logLimitBytes));
        return env;
    }

    Path workDir() {
        return workDir;
    }

    private void reapQuietly() {
        try {
            deployments.reap();
        } catch (RuntimeException e) {
            logger.debug("Python binding check failed: " + e.getMessage());
        }
    }

    /** Removes environments no profile selects and no deployment leases. */
    void collectGarbage() {
        try {
            long generation = artifacts.collectionGeneration();
            Set<String> keep = new HashSet<>();
            Registry snapshot = registry.snapshot();
            for (Profile profile : snapshot.profiles.values()) {
                if (profile.archived) {
                    continue;
                }
                try {
                    Interpreters.Target target = interpreters.probe(profile.runtimeId);
                    ResolvedLock lock = profile.resolvedLocks.get(target.fingerprint);
                    if (lock != null) {
                        keep.add(ArtifactStore.artifactDigest(lock, target, sdk));
                    }
                } catch (Interpreters.Unavailable e) {
                    logger.warn("Deferring Python artifact collection because runtime ownership/selection is uncertain: " + e.getMessage());
                    return;
                }
            }
            for (Deployments.Binding binding : deployments.bindings()) {
                for (BoundSpec spec : binding.specs.values()) {
                    keep.add(spec.artifact.digest);
                }
            }
            List<String> removed = artifacts.collect(keep, generation);
            if (!removed.isEmpty()) {
                logger.debug("Removed unused Python environments: " + removed);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            logger.warn("Python environment cleanup failed: " + e.getMessage());
        }
    }

    // ---- Compile-only validation --------------------------------------------------------

    /**
     * Compiles scripts with the given interpreter without importing or running
     * them. Each item has id, role (transform/read/write), label and source.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> compileOnly(Path python, List<Map<String, Object>> items) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("items", items);
        byte[] input = mapper.writeValueAsBytes(request);
        if (input.length > config.maxFrameBytes) throw new IOException("Python validation input exceeds the frame limit");
        List<String> command = Arrays.asList(python.toString(), "-I", "-S", "-c", sdk.bootstrap("oie_python.validate"));
        ProcessControl.Result result = ProcessControl.runPython(command, baseEnv, workDir, input, 30000, null, null, 10, config.maxFrameBytes);
        if (result.exitCode != 0 || result.output.isEmpty()) {
            throw new IOException("Python validation did not complete: " + result.tail(5));
        }
        Map<String, Object> parsed = Wire.MAPPER.readValue(result.output.get(result.output.size() - 1), Map.class);
        return checkValidationReply(items, parsed);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> checkValidationReply(List<Map<String, Object>> expected, Map<String, Object> reply) throws IOException {
        Object rawItems = reply.get("items");
        if (!(rawItems instanceof List) || ((List<?>) rawItems).size() != expected.size()) {
            throw new IOException("Python validation returned a different number of scripts");
        }
        List<Map<String, Object>> checked = new ArrayList<>();
        Set<Object> ids = new HashSet<>();
        for (int index = 0; index < expected.size(); index++) {
            Object raw = ((List<?>) rawItems).get(index);
            if (!(raw instanceof Map)) throw new IOException("Python validation returned a malformed script result");
            Map<String, Object> item = (Map<String, Object>) raw;
            Map<String, Object> wanted = expected.get(index);
            if (!java.util.Objects.equals(wanted.get("id"), item.get("id")) || !ids.add(item.get("id"))
                    || !java.util.Objects.equals(wanted.get("role"), item.get("role")) || !(item.get("ok") instanceof Boolean)
                    || !(item.get("diagnostics") instanceof List)) throw new IOException("Python validation returned mismatched script identity or status");
            boolean hasErrors = false;
            for (Object rawDiagnostic : (List<?>) item.get("diagnostics")) {
                if (!(rawDiagnostic instanceof Map)) throw new IOException("Python validation returned malformed diagnostics");
                Map<?, ?> diagnostic = (Map<?, ?>) rawDiagnostic;
                if (!(diagnostic.get("code") instanceof String) || !(diagnostic.get("message") instanceof String)) {
                    throw new IOException("Python validation returned malformed diagnostics");
                }
                hasErrors |= !"warning".equals(diagnostic.get("severity")) && !"info".equals(diagnostic.get("severity"));
            }
            if (Boolean.TRUE.equals(item.get("ok")) == hasErrors) throw new IOException("Python validation status contradicts its diagnostics");
            checked.add(item);
        }
        return checked;
    }

    @SuppressWarnings("unchecked")
    static List<Diagnostic> diagnostics(Map<String, Object> item) {
        List<Diagnostic> out = new ArrayList<>();
        Object list = item.get("diagnostics");
        if (list instanceof List) {
            for (Object d : (List<Object>) list) {
                out.add(PythonApi.mapper().convertValue(d, Diagnostic.class));
            }
        }
        return out;
    }

    /** A readable message for a script-level ERROR frame. */
    static String describeError(String label, Map<String, Object> error) {
        if (error == null) {
            return label + ": the Python call failed";
        }
        StringBuilder out = new StringBuilder(label).append(": ");
        String kind = String.valueOf(error.get("kind"));
        if ("contract".equals(kind)) {
            out.append("the script broke the Python contract: ");
        } else if ("syntax".equals(kind)) {
            out.append("syntax error: ");
        }
        out.append(error.get("message"));
        if (error.get("line") != null) {
            out.append(" (line ").append(error.get("line")).append(')');
        }
        if (error.get("traceback") != null) {
            out.append('\n').append(error.get("traceback"));
        }
        return out.toString();
    }

    // ---- Readiness and views -----------------------------------------------------------

    public LocalReadiness readiness(Profile profile) {
        return readiness(profile, registry.epoch());
    }

    LocalReadiness readiness(Profile profile, String epoch) {
        LocalReadiness readiness = new LocalReadiness();
        readiness.nodeId = config.nodeId;
        if (com.mirth.connect.plugins.python.PythonContract.DEFAULT_PROFILE_ID.equals(profile.id) && !BuiltInEnvironment.owned(profile)) {
            readiness.state = "unavailable"; readiness.message = "The reserved default profile ID is occupied by another definition. Existing data was preserved."; return readiness;
        }
        Interpreters.Target target;
        try {
            target = interpreters.probe(profile.runtimeId);
        } catch (Interpreters.Unavailable e) {
            readiness.state = "runtime-unavailable";
            readiness.message = e.getMessage();
            return readiness;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            readiness.state = "runtime-unavailable";
            readiness.message = "Interrupted";
            return readiness;
        }
        readiness.targetFingerprint = target.fingerprint;
        readiness.pythonVersion = target.version;
        ResolvedLock lock = profile.resolvedLocks.get(target.fingerprint);
        if (lock != null && profile.definitionFingerprint.equals(lock.definitionFingerprint)) {
            OperatorConfig.PackageSource source = config.sources.get(profile.packageSourceId);
            if (source == null || !java.util.Objects.equals(source.policyIdentity(), lock.packageSourcePolicy)) {
                readiness.state = "source-policy-changed";
                readiness.message = "Refresh dependencies and prepare after the approved package source policy changes";
                return readiness;
            }
            readiness.lockDigest = lock.lockDigest;
            for (LockedRequirement r : lock.lockedRequirements) {
                LockedRequirement copy = new LockedRequirement();
                copy.name = r.name;
                copy.version = r.version;
                copy.filename = r.filename;
                copy.sha256 = r.sha256;
                readiness.packages.add(copy);
            }
            String digest = ArtifactStore.artifactDigest(lock, target, sdk);
            if (artifacts.ready(digest) != null) {
                readiness.state = "ready";
                readiness.artifactDigest = digest;
                return readiness;
            }
        }
        PythonApi.JobView job = preparation.latestFor(profile.id, profile.definitionFingerprint, epoch, profile.revision);
        if (job != null && !PreparationManager.TERMINAL.contains(job.state)) {
            readiness.state = "preparing";
            readiness.jobId = job.id;
            readiness.message = job.message;
        } else if (job != null && ("FAILED".equals(job.state) || "INTERRUPTED".equals(job.state))) {
            readiness.state = "failed";
            readiness.jobId = job.id;
            readiness.message = job.message;
        } else {
            readiness.state = "not-prepared";
            readiness.message = BuiltInEnvironment.owned(profile) ? BuiltInEnvironment.preparationHint(profile)
                    : "Prepare this environment on node " + config.nodeId;
        }
        return readiness;
    }

    public ProfileView view(Profile p, String epoch) {
        return view(p, epoch, readiness(p, epoch));
    }

    ProfileView view(Profile p, String epoch, LocalReadiness readiness) {
        ProfileView v = new ProfileView();
        v.id = p.id;
        v.builtInDefault = BuiltInEnvironment.owned(p);
        v.revision = p.revision;
        v.registryEpoch = epoch;
        v.name = p.name;
        v.description = p.description;
        v.archived = p.archived;
        v.language = p.language;
        v.runtimeId = p.runtimeId;
        v.packageSourceId = p.packageSourceId;
        v.requirementsText = p.requirementsText;
        v.definitionFingerprint = p.definitionFingerprint;
        v.refreshGeneration = p.refreshGeneration;
        v.createdAt = p.createdAt;
        v.updatedAt = p.updatedAt;
        v.updatedBy = p.updatedBy;
        v.readiness = readiness;
        for (Deployments.Binding binding : deployments.bindings()) {
            boolean uses = false;
            boolean stale = false;
            for (BoundSpec spec : binding.specs.values()) {
                if (p.id.equals(spec.profileId)) {
                    uses = true;
                    stale |= !spec.lockDigest.equals(v.readiness.lockDigest) || spec.profileRevision != p.revision;
                }
            }
            if (uses) {
                v.deployedReferenceCount++;
                v.redeployNeeded |= stale;
            }
        }
        return v;
    }

    public Map<String, Long> metrics() {
        Map<String, Long> m = new LinkedHashMap<>(executor.metrics());
        m.putAll(pools.metrics());
        m.put("activeEnvironmentLeases", (long) artifacts.activeLeases());
        m.put("deployments", (long) deployments.bindings().size());
        return m;
    }

    static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
