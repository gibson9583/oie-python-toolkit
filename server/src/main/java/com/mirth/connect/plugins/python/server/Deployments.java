/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mirth.connect.donkey.server.channel.Channel;
import com.mirth.connect.plugins.python.PythonApi.Diagnostic;
import com.mirth.connect.plugins.python.PythonApi.Profile;
import com.mirth.connect.plugins.python.PythonApi.Registry;
import com.mirth.connect.plugins.python.PythonApi.ResolvedLock;
import com.mirth.connect.plugins.python.PythonContract;

/**
 * Deployment bindings. A channel deployment captures one registry snapshot,
 * resolves every enabled Python surface to a prepared environment on this node,
 * leases those environments and publishes one immutable binding. Execution
 * always uses the binding of the runtime channel the call belongs to; it never
 * reads saved channels or the current profile.
 */
public final class Deployments {

    private static final Logger logger = LogManager.getLogger(Deployments.class);

    /** A call cannot be admitted. The message names the cause and the remedy. */
    public static final class NotAdmitted extends Exception {
        private static final long serialVersionUID = 1L;

        NotAdmitted(String message) {
            super(message);
        }
    }

    public enum State {
        PREPARED, ACTIVE, RETIRING, RETIRED
    }

    public static final class Binding {
        public final String attemptId;
        public final String channelId;
        public final String channelName;
        public final int channelRevision;
        public final long createdAtMillis = System.currentTimeMillis();
        public final String createdAt = Instant.now().toString();
        public final Map<String, BoundSpec> specs;
        public final Map<String, String> unavailable;
        private volatile Channel runtime;
        private volatile State state = State.PREPARED;
        private int inflight;

        Binding(String channelId, String channelName, int channelRevision, Map<String, BoundSpec> specs) {
            this(channelId, channelName, channelRevision, specs, Collections.emptyMap());
        }

        Binding(String channelId, String channelName, int channelRevision, Map<String, BoundSpec> specs, Map<String, String> unavailable) {
            this.attemptId = UUID.randomUUID().toString();
            this.channelId = channelId;
            this.channelName = channelName;
            this.channelRevision = channelRevision;
            this.specs = Collections.unmodifiableMap(new LinkedHashMap<>(specs));
            this.unavailable = Collections.unmodifiableMap(new LinkedHashMap<>(unavailable));
        }

        public State state() {
            return state;
        }

        public Channel runtime() {
            return runtime;
        }

        synchronized void leave() {
            inflight--;
            notifyAll();
        }

        synchronized boolean awaitDrained(long millis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (inflight > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            return true;
        }
    }

    /** An admitted call. Close it when the call is complete. */
    public static final class Admission implements AutoCloseable {
        public final Binding binding;
        public final BoundSpec spec;
        private boolean closed;

        Admission(Binding binding, BoundSpec spec) {
            this.binding = binding;
            this.spec = spec;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                binding.leave();
            }
        }
    }

    private final PythonRuntime runtime;
    private final Map<String, Binding> byChannel = new ConcurrentHashMap<>();
    private final Map<String, Binding> byAttempt = new ConcurrentHashMap<>();
    private final Map<Channel, Binding> byRuntime = new ConcurrentHashMap<>();
    private final Map<String, Binding> pendingByChannel = new ConcurrentHashMap<>();

    Deployments(PythonRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Prepares a closed binding for a channel model being deployed.
     * Throws with every problem found; nothing is held after a failure.
     */
    public Binding prepare(com.mirth.connect.model.Channel model) throws DeployFailure {
        List<String> problems = new ArrayList<>();
        List<Surfaces.Surface> surfaces = new ArrayList<>();
        for (Surfaces.Surface surface : Surfaces.of(model)) {
            if (!surface.enabled) {
                continue;
            }
            if (surface.insideIterator) {
                problems.add(surface.label(model.getName()) + (PythonContract.isFilterRole(surface.role)
                        ? ": Python rules inside Iterator rules are not supported. Move the rule out of the iterator and loop in Python."
                        : ": Python steps inside Iterator steps are not supported. Move the step out of the iterator and loop in Python."));
                continue;
            }
            surfaces.add(surface);
        }
        List<Surfaces.Surface> templates = Collections.emptyList();
        try { templates = Surfaces.templates(model, runtime.engine.codeTemplateLibraries()); }
        catch (RuntimeException failure) {
            logger.warn("Python functions are unavailable in channel \"" + model.getName() + "\" because its code templates could not be read: " + failure.getMessage());
        }
        // Stock compiles each phase before running the deploy script and this hook.
        // A template save can overlap either stage. Compare against those compiled
        // sources, including removed templates, before acquiring any environment leases.
        TemplateSnapshots.check(model, templates, runtime.engine, problems);
        if (!problems.isEmpty()) throw new DeployFailure(model, problems);
        surfaces.addAll(templates);
        if (surfaces.isEmpty()) {
            pendingByChannel.remove(model.getId());
            return null;
        }

        Registry registry = runtime.registry.snapshot();
        Map<String, BoundSpec> specs = new LinkedHashMap<>();
        Map<String, String> unavailable = new LinkedHashMap<>();
        List<ArtifactStore.Lease> leases = new ArrayList<>();
        Map<String, List<Map<String, Object>>> validation = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> templateValidation = new LinkedHashMap<>();
        Map<String, Surfaces.Surface> byId = new LinkedHashMap<>();
        java.util.function.BiConsumer<Surfaces.Surface, String> unbind = (surface, reason) -> {
            String key = BoundSpec.key(surface.metaDataId, surface.role, PythonContract.specDigest(surface.config));
            unavailable.putIfAbsent(key, surface.label(model.getName()) + ": " + reason);
            BoundSpec bound = specs.remove(key);
            if (bound != null) {
                leases.remove(bound.artifact);
                bound.artifact.release();
            }
        };
        boolean transferred = false;
        try {
        try {
            for (Surfaces.Surface surface : surfaces) {
                String label = surface.label(model.getName());
                java.util.function.Consumer<String> reject = surface.template()
                        ? message -> unavailable.putIfAbsent(BoundSpec.key(surface.metaDataId, surface.role, PythonContract.specDigest(surface.config)), message)
                        : problems::add;
                String profileId = BuiltInEnvironment.selectedId(surface.config.getEnvironmentProfileId());
                Profile profile = registry.profiles.get(profileId);
                if (PythonContract.DEFAULT_PROFILE_ID.equals(profileId) && !BuiltInEnvironment.owned(profile)) {
                    reject.accept(label + ": the built-in standard-library environment is unavailable or its reserved ID conflicts with another definition. Existing data was preserved.");
                    continue;
                }
                String hint = surface.config.getEnvironmentNameHint();
                if (profile == null) {
                    reject.accept(label + ": script environment " + (hint == null || hint.isEmpty() ? "" : "\"" + hint + "\" ") + "(" + profileId
                            + ") does not exist on this server. Import or create it, prepare it, then deploy.");
                    continue;
                }
                if (profile.archived) {
                    reject.accept(label + ": script environment \"" + profile.name + "\" is archived.");
                    continue;
                }
                if (surface.config.getContractVersion() != PythonContract.CONTRACT_VERSION) {
                    reject.accept(label + ": unsupported Python contract version " + surface.config.getContractVersion());
                    continue;
                }
                int timeout = PythonContract.effectiveTimeout(surface.config.getTimeoutMillis());
                if (timeout < PythonContract.MIN_TIMEOUT_MILLIS || timeout > runtime.config.maxTimeoutMillis) {
                    reject.accept(label + ": timeout must be between " + PythonContract.MIN_TIMEOUT_MILLIS + " and " + runtime.config.maxTimeoutMillis
                            + " ms.");
                    continue;
                }
                Interpreters.Target target;
                try {
                    target = runtime.interpreters.probe(profile.runtimeId);
                } catch (Interpreters.Unavailable e) {
                    reject.accept(label + ": " + e.getMessage());
                    continue;
                }
                ResolvedLock lock = profile.resolvedLocks.get(target.fingerprint);
                if (lock == null || !profile.definitionFingerprint.equals(lock.definitionFingerprint)) {
                    reject.accept(label + ": script environment \"" + profile.name + "\" is not prepared for revision " + profile.revision
                            + ". " + BuiltInEnvironment.preparationHint(profile));
                    continue;
                }
                OperatorConfig.PackageSource source = runtime.config.sources.get(profile.packageSourceId);
                if (source == null || !java.util.Objects.equals(lock.packageSourcePolicy, source.policyIdentity())) {
                    reject.accept(label + ": the approved package-source policy changed. Refresh dependencies, prepare, then redeploy.");
                    continue;
                }
                String digest = ArtifactStore.artifactDigest(lock, target, runtime.sdk);
                ArtifactStore.Lease lease = runtime.artifacts.acquire(digest);
                if (lease == null) {
                    reject.accept(label + ": script environment \"" + profile.name + "\" is not prepared on this node (" + runtime.config.nodeId
                            + "). " + BuiltInEnvironment.preparationHint(profile));
                    continue;
                }
                leases.add(lease);
                String specDigest = PythonContract.specDigest(surface.config);
                BoundSpec spec = new BoundSpec(specDigest, surface.role, surface.metaDataId, surface.connectorName, label,
                        PythonContract.normalizeSource(surface.config.getPythonScript()), profile.id, profile.name, profile.revision, lock.lockDigest,
                        lease, timeout);
                if (specs.putIfAbsent(spec.key(), spec) != null) {
                    // An identical script on the same connector and role shares the first registration.
                    leases.remove(lease);
                    lease.release();
                }
                String id = Integer.toString(byId.size());
                byId.put(id, surface);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", id);
                item.put("role", PythonContract.scriptRole(surface.role));
                item.put("label", label);
                item.put("source", spec.source);
                (surface.template() ? templateValidation : validation).computeIfAbsent(lease.python.toString(), k -> new ArrayList<>()).add(item);
            }
            if (problems.isEmpty()) {
                for (Map.Entry<String, List<Map<String, Object>>> entry : validation.entrySet()) {
                    for (Map<String, Object> result : runtime.compileOnly(java.nio.file.Paths.get(entry.getKey()), entry.getValue())) {
                        if (!Boolean.TRUE.equals(result.get("ok"))) {
                            Surfaces.Surface surface = byId.get(String.valueOf(result.get("id")));
                            for (Diagnostic d : PythonRuntime.diagnostics(result)) {
                                problems.add(surface.label(model.getName()) + ": " + d.message + (d.line != null ? " (line " + d.line + ")" : ""));
                            }
                        }
                    }
                }
                for (Map.Entry<String, List<Map<String, Object>>> entry : templateValidation.entrySet()) {
                    List<Map<String, Object>> results;
                    try {
                        results = runtime.compileOnly(java.nio.file.Paths.get(entry.getKey()), entry.getValue());
                    } catch (InterruptedException e) {
                        throw e;
                    } catch (Exception e) {
                        for (Map<String, Object> item : entry.getValue()) unbind.accept(byId.get(String.valueOf(item.get("id"))), "the Python source could not be checked: " + e.getMessage());
                        continue;
                    }
                    for (Map<String, Object> result : results) {
                        if (!Boolean.TRUE.equals(result.get("ok"))) {
                            List<Diagnostic> diagnostics = PythonRuntime.diagnostics(result);
                            Diagnostic d = diagnostics.isEmpty() ? null : diagnostics.get(0);
                            unbind.accept(byId.get(String.valueOf(result.get("id"))),
                                    d == null ? "the Python source is invalid" : d.message + (d.line != null ? " (line " + d.line + ")" : ""));
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            problems.add("Deployment was interrupted while checking Python scripts.");
        } catch (Exception e) {
            logger.warn("Python deployment check failed", e);
            problems.add("Python scripts could not be checked: " + e.getMessage());
        }
        if (!problems.isEmpty()) throw new DeployFailure(model, problems);
        if (!unavailable.isEmpty()) logger.debug("Python functions unavailable in channel \"" + model.getName() + "\": " + unavailable.values());
        Binding binding = new Binding(model.getId(), model.getName(), model.getRevision(), specs, unavailable);
        if (byAttempt.putIfAbsent(binding.attemptId, binding) != null) {
            throw new DeployFailure(model, Collections.singletonList("Deployment attempt was prepared twice"));
        }
        pendingByChannel.put(binding.channelId, binding);
        transferred = true;
        return binding;
        } finally {
            if (!transferred) for (ArtifactStore.Lease lease : leases) lease.release();
        }
    }

    /** Called inline by the stock channel-level DEPLOYED event, before start/recovery. */
    public void activate(Channel caller) throws NotAdmitted {
        Binding binding = attach(caller.getChannelId(), caller);
        synchronized (binding) {
            if (binding.state == State.ACTIVE) return;
            if (binding.state != State.PREPARED || binding.runtime != caller
                    || runtime.engine.deployedChannel(binding.channelId) != caller) {
                throw new NotAdmitted("The prepared Python deployment is no longer the published runtime.");
            }
            // Public stock executor composition. All wrappers remain gated until
            // the complete installation succeeds, including partial failures.
            ScopedTransformers.install(runtime, caller);
            binding.state = State.ACTIVE;
            pendingByChannel.remove(binding.channelId, binding);
            Binding previous = byChannel.put(binding.channelId, binding);
            if (previous != null && previous != binding) retire(previous, "replaced by a new deployment");
        }
    }

    /** Captures the connector's exact published runtime during onDeploy. */
    public Binding attach(String channelId, Channel caller) throws NotAdmitted {
        if (caller == null || !channelId.equals(caller.getChannelId())
                || runtime.engine.deployedChannel(channelId) != caller) {
            throw new NotAdmitted("This connector does not belong to the published deployment runtime.");
        }
        Binding existing = byRuntime.get(caller);
        if (existing != null && (existing.state == State.PREPARED || existing.state == State.ACTIVE)) return existing;
        Binding binding = pendingByChannel.get(channelId);
        if (binding == null) throw new NotAdmitted("This runtime has no prepared Python snapshot. Redeploy the channel.");
        synchronized (binding) {
            if (binding.state != State.PREPARED || binding.channelRevision != caller.getRevision()
                    || (binding.runtime != null && binding.runtime != caller)) {
                throw new NotAdmitted("The runtime does not match the prepared Python deployment.");
            }
            binding.runtime = caller;
            byRuntime.put(caller, binding);
            return binding;
        }
    }

    /** Checked before any delegated JavaScript executes, even when the script cache was replaced. */
    public void requireActive(Channel caller) throws NotAdmitted {
        Binding binding = caller == null ? null : byRuntime.get(caller);
        if (binding == null || binding.state != State.ACTIVE
                || runtime.engine.deployedChannel(binding.channelId) != caller) {
            throw new NotAdmitted("This Python runtime is pending, retired, or no longer deployed.");
        }
    }

    /** Admits only the exact connector/decorator runtime; never resolves a caller by channel ID. */
    public Admission admit(String channelId, int metaDataId, String role, String digest, Channel caller) throws NotAdmitted {
        Binding binding = caller == null ? null : byRuntime.get(caller);
        if (binding == null) throw new NotAdmitted("This runtime channel has no active Python deployment. Redeploy the channel.");
        synchronized (binding) {
            if (binding.runtime != caller || !binding.channelId.equals(channelId)
                    || binding.state != State.ACTIVE || runtime.engine.deployedChannel(channelId) != caller) {
                throw new NotAdmitted("The Python deployment is pending or being retired.");
            }
            String key = BoundSpec.key(metaDataId, role, digest);
            BoundSpec spec = binding.specs.get(key);
            if (spec == null) {
                String reason = binding.unavailable.get(key);
                throw new NotAdmitted(reason != null ? "This Python function is unavailable in this deployment. " + reason
                        : "This Python script is not registered in this deployed connector and phase. Redeploy the channel.");
            }
            binding.inflight++;
            return new Admission(binding, spec);
        }
    }

    public void retire(Channel caller, String reason) {
        Binding binding = caller == null ? null : byRuntime.get(caller);
        if (binding != null && binding.runtime == caller) retire(binding, reason);
    }

    /** Legacy undeploy has only an ID: retire only snapshots proven unpublished. */
    public void retireUnpublished(String channelId) {
        for (Binding binding : new ArrayList<>(byAttempt.values())) {
            if (channelId.equals(binding.channelId) && binding.runtime != null
                    && runtime.engine.deployedChannel(channelId) != binding.runtime) retire(binding, "runtime unpublished");
        }
    }

    /** A stock FIFO channel task proves this deployment attempt has returned. */
    public void completedAttempt(Binding binding) {
        if (binding != null && binding.state == State.PREPARED) {
            retire(binding, "deployment completed without Python activation");
        }
    }

    public boolean hasPending(String channelId) { return pendingByChannel.containsKey(channelId); }

    public Binding binding(String channelId) {
        return byChannel.get(channelId);
    }

    public Collection<Binding> bindings() {
        return new ArrayList<>(byAttempt.values());
    }

    private void retire(Binding binding, String reason) {
        synchronized (binding) {
            if (binding.state == State.RETIRING || binding.state == State.RETIRED) return;
            binding.state = State.RETIRING;
            byChannel.remove(binding.channelId, binding);
            if (binding.runtime != null) byRuntime.remove(binding.runtime, binding);
            pendingByChannel.remove(binding.channelId, binding);
        }
        runtime.pools.retire(binding.attemptId);
        if (binding.inflight == 0) finishRetirement(binding, reason);
        else runtime.housekeeping.execute(() -> finishRetirement(binding, reason));
    }

    private void finishRetirement(Binding binding, String reason) {
        try {
            if (!binding.awaitDrained(runtime.config.terminationGraceMillis + 1000)) {
                logger.warn("Python deployment " + binding.attemptId + " still has admitted work; retaining environment leases");
                runtime.housekeeping.schedule(() -> finishRetirement(binding, reason), 1, TimeUnit.SECONDS);
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Python retirement interrupted; retaining environment leases for " + binding.attemptId);
            return;
        }
        runtime.pools.retire(binding.attemptId);
        if (!runtime.pools.retirementComplete(binding.attemptId)) {
            logger.warn("Python deployment " + binding.attemptId + " has unconfirmed worker termination; retaining environment leases");
            runtime.housekeeping.schedule(() -> finishRetirement(binding, reason), 1, TimeUnit.SECONDS);
            return;
        }
        for (BoundSpec spec : binding.specs.values()) spec.artifact.release();
        binding.state = State.RETIRED;
        byAttempt.remove(binding.attemptId, binding);
        logger.debug("Python binding " + binding.attemptId + " retired: " + reason);
    }

    /** Exact known runtimes may be retired after removal; unidentified pending attempts stay leased. */
    public void reap() {
        for (Binding binding : new ArrayList<>(byAttempt.values())) {
            if (binding.state == State.ACTIVE && binding.runtime != null
                    && runtime.engine.deployedChannel(binding.channelId) != binding.runtime) retire(binding, "runtime unpublished");
        }
    }

    public void shutdown() {
        Throwable failure = null;
        for (Binding binding : new ArrayList<>(byAttempt.values())) {
            try { retire(binding, "server shutdown"); }
            catch (Throwable caught) {
                if (failure == null) failure = caught;
                else if (failure != caught) failure.addSuppressed(caught);
            }
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException error) throw error;
        if (failure != null) throw new IllegalStateException("Python deployment cleanup did not complete", failure);
    }

    /** A deployment problem, raised through the engine's deploy error path. */
    public static final class DeployFailure extends Exception {
        private static final long serialVersionUID = 1L;

        DeployFailure(com.mirth.connect.model.Channel model, List<String> problems) {
            super("Python Runtime cannot deploy channel \"" + model.getName() + "\":\n - " + String.join("\n - ", problems));
        }
    }
}
