/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirth.connect.plugins.python.CanonicalJson;
import com.mirth.connect.plugins.python.PythonApi;
import com.mirth.connect.plugins.python.PythonApi.MutationReceipt;
import com.mirth.connect.plugins.python.PythonApi.Profile;
import com.mirth.connect.plugins.python.PythonApi.Registry;

/**
 * The profile registry: one canonical JSON document in the extension's
 * property group. Every mutation checks the registry epoch and the profile
 * revision, records a bounded receipt in the same write, and is published in
 * memory only after the write is confirmed.
 *
 * <p>The persistence adapter locks this plugin's configuration row and checks
 * its exact predecessor in one database transaction. A preceding read alone
 * cannot protect against another node writing before this node's commit.</p>
 */
public final class RegistryStore {

    private static final Logger logger = LogManager.getLogger(RegistryStore.class);
    public static final String PROPERTY_KEY = "configuration";
    public static final int MAX_RECEIPTS = 500;

    public enum WriteOutcome { COMMITTED, CONFLICT, OUTCOME_UNKNOWN }

    /** Reads and writes the stored document. */
    public interface Persistence {
        String read() throws Exception;

        WriteOutcome write(String expectedJson, String json, String operationId) throws Exception;
    }

    /** The outcome of one mutation. */
    public static final class Outcome<T> {
        public final T value;
        public final boolean replayed;
        public final Registry registry;

        Outcome(T value, boolean replayed, Registry registry) {
            this.value = value;
            this.replayed = replayed;
            this.registry = registry;
        }
    }

    private final ObjectMapper mapper = PythonApi.mapper();
    private final Persistence persistence;
    private String stored;
    private Registry current;
    private Runnable beforeChange = () -> {};

    public RegistryStore(Persistence persistence) {
        this.persistence = persistence;
    }

    /** Called under this store's monitor, before committing or adopting a new selection. */
    synchronized void beforeChange(Runnable action) { beforeChange = Objects.requireNonNull(action); }

    /** Loads the stored registry, creating an empty one with a fresh epoch when none exists. */
    public synchronized void load(String initial) throws Exception {
        String json = initial != null ? initial : persistence.read();
        if (json == null || json.trim().isEmpty()) {
            stored = json;
            Registry registry = new Registry();
            registry.registryEpoch = UUID.randomUUID().toString();
            write(registry, null);
            return;
        }
        adoptCommitted(json);
    }

    /** A private copy of the published registry. */
    public synchronized Registry snapshot() {
        reconcileExternalChanges();
        return copy(current);
    }

    public synchronized String epoch() {
        reconcileExternalChanges();
        return current.registryEpoch;
    }

    public synchronized String storedJson() {
        return stored;
    }

    Registry parse(String json) throws IOException {
        if (json == null || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16 * 1024 * 1024) {
            throw new IOException("The Python Toolkit registry exceeds the 16 MiB limit");
        }
        Registry registry = mapper.readValue(json, Registry.class);
        if (registry.schemaVersion != PythonApi.REGISTRY_SCHEMA_VERSION) {
            throw new IOException("Unsupported Python Toolkit registry schema " + registry.schemaVersion);
        }
        if (registry.registryEpoch == null || registry.registryEpoch.isEmpty() || registry.registryEpoch.length() > 200 || registry.registryRevision < 0) {
            throw new IOException("The Python Toolkit registry has no epoch");
        }
        if (registry.profiles == null) {
            registry.profiles = new java.util.TreeMap<>();
        }
        if (registry.recentMutationReceipts == null) {
            registry.recentMutationReceipts = new ArrayList<>();
        }
        if (registry.profiles.size() > 1001 || registry.recentMutationReceipts.size() > MAX_RECEIPTS) {
            throw new IOException("The Python Toolkit registry has too many profiles or receipts");
        }
        for (Map.Entry<String, Profile> entry : registry.profiles.entrySet()) {
            Profile p = entry.getValue();
            if (p == null || !entry.getKey().equals(p.id) || p.id == null || !p.id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                    || p.name == null || p.name.trim().isEmpty() || p.name.length() > RuntimeApi.MAX_NAME || p.revision < 1 || p.refreshGeneration < 0
                    || !"python".equals(p.language) || p.runtimeId == null || p.runtimeId.isBlank()
                    || p.packageSourceId == null || p.packageSourceId.isBlank()
                    || p.description != null && p.description.length() > RuntimeApi.MAX_DESCRIPTION) {
                throw new IOException("The Python Toolkit registry has a malformed profile " + entry.getKey());
            }
            List<PythonApi.Diagnostic> problems = Requirements.precheck(p.requirementsText, 1024 * 1024);
            if (!problems.isEmpty()) throw new IOException("Profile " + p.name + ": " + problems.get(0).message);
            if (!Objects.equals(p.definitionFingerprint, PreparationManager.definitionFingerprint(p))) {
                throw new IOException("Profile " + p.name + " has an invalid definition fingerprint");
            }
            if (p.builtInDefault && !BuiltInEnvironment.owned(p)) throw new IOException("The built-in standard-library profile has an invalid definition");
            if (com.mirth.connect.plugins.python.PythonContract.BUILTIN_SOURCE_ID.equals(p.packageSourceId)
                    && !BuiltInEnvironment.stdlibOnly(p.requirementsText)) throw new IOException("The standard-library source cannot have package requirements");
            if (p.resolvedLocks == null) {
                p.resolvedLocks = new java.util.TreeMap<>();
            }
            for (Map.Entry<String, PythonApi.ResolvedLock> lockEntry : p.resolvedLocks.entrySet()) {
                PythonApi.ResolvedLock lock = lockEntry.getValue();
                if (com.mirth.connect.plugins.python.PythonContract.BUILTIN_SOURCE_ID.equals(p.packageSourceId)
                        && lock != null && lock.lockedRequirements != null && !lock.lockedRequirements.isEmpty()) throw new IOException("The standard-library source cannot have locked packages");
                if (lock == null || !lockEntry.getKey().equals(lock.targetFingerprint) || !digest(lock.targetFingerprint)
                        || !digest(lock.definitionFingerprint) || !digest(lock.lockDigest) || !digest(lock.packageSourcePolicy) || lock.lockedRequirements == null
                        || lock.lockedRequirements.size() > 1000) throw new IOException("Profile " + p.name + " has a malformed lock");
                java.util.Set<String> names = new java.util.HashSet<>();
                for (PythonApi.LockedRequirement requirement : lock.lockedRequirements) {
                    if (requirement == null || requirement.name == null || !requirement.name.matches("[a-z0-9][a-z0-9-]*")
                            || Sdk.DISTRIBUTION.equals(requirement.name) || !names.add(requirement.name)
                            || requirement.version == null || !requirement.version.matches("[A-Za-z0-9_.+!-]+")
                            || requirement.filename == null || !requirement.filename.matches("[A-Za-z0-9_.+!-]+\\.whl")
                            || !digest(requirement.sha256)) throw new IOException("Profile " + p.name + " has a malformed locked wheel");
                }
                if (!lock.lockDigest.equals(PreparationManager.lockDigest(lock.targetFingerprint, lock.lockedRequirements))) {
                    throw new IOException("Profile " + p.name + " has an invalid lock digest");
                }
            }
        }
        long customProfiles = registry.profiles.values().stream().filter(profile -> !BuiltInEnvironment.owned(profile)).count();
        if (customProfiles > 1000) throw new IOException("The Python Toolkit registry has too many custom profiles");
        for (MutationReceipt receipt : registry.recentMutationReceipts) {
            if (receipt == null || receipt.requestId == null || receipt.requestId.length() > 200 || receipt.operation == null
                    || !digest(receipt.contentDigest) || receipt.resultRevision < 0) throw new IOException("The registry has a malformed mutation receipt");
        }
        return registry;
    }

    private static boolean digest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }

    private Registry copy(Registry registry) {
        try {
            return mapper.readValue(mapper.writeValueAsBytes(registry), Registry.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Applies a user mutation. The receipt key is (epoch, actor, requestId);
     * repeating a request with the same content replays its result.
     */
    public synchronized <T> Outcome<T> mutate(String actor, String requestId, String operation, String target, Object content,
            String expectedEpoch, Function<Registry, T> change, Function<Registry, T> replay) {
        if (requestId == null || requestId.trim().isEmpty() || requestId.length() > 200) {
            throw ApiFailure.badRequest("REQUEST_ID_REQUIRED", "Every change needs a requestId of at most 200 characters");
        }
        reconcileExternalChanges();
        checkEpoch(expectedEpoch);
        String digest = CanonicalJson.sha256Hex(toJson(content).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (MutationReceipt receipt : current.recentMutationReceipts) {
            if (Objects.equals(receipt.actor, actor) && requestId.equals(receipt.requestId)) {
                if (operation.equals(receipt.operation) && Objects.equals(target, receipt.target) && digest.equals(receipt.contentDigest)) {
                    return new Outcome<>(replay.apply(copy(current)), true, copy(current));
                }
                throw ApiFailure.conflict("REQUEST_ID_REUSED", "This requestId was already used for a different change");
            }
        }
        Registry next = copy(current);
        T value = change.apply(next);
        MutationReceipt receipt = new MutationReceipt();
        receipt.actor = actor;
        receipt.requestId = requestId;
        receipt.operation = operation;
        receipt.target = target;
        receipt.contentDigest = digest;
        receipt.resultRevision = next.registryRevision + 1;
        receipt.result = mapper.convertValue(value, Object.class);
        receipt.at = Instant.now().toString();
        next.recentMutationReceipts.add(receipt);
        while (next.recentMutationReceipts.size() > MAX_RECEIPTS) {
            next.recentMutationReceipts.remove(0);
        }
        while (next.recentMutationReceipts.size() > 1 && toJson(next).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16 * 1024 * 1024) {
            next.recentMutationReceipts.remove(0);
        }
        commit(next, requestId);
        return new Outcome<>(value, false, copy(current));
    }

    /** Applies an internal change (lock commits) without a receipt. */
    public synchronized <T> T change(Function<Registry, T> change) {
        reconcileExternalChanges();
        Registry next = copy(current);
        T value = change.apply(next);
        if (value == null) {
            return null;
        }
        commit(next, "internal-" + UUID.randomUUID());
        return value;
    }

    public void checkEpochOf(String expectedEpoch) {
        synchronized (this) {
            checkEpoch(expectedEpoch);
        }
    }

    private void checkEpoch(String expectedEpoch) {
        if (expectedEpoch == null || !expectedEpoch.equals(current.registryEpoch)) {
            ApiFailure failure = ApiFailure.conflict("REGISTRY_EPOCH_CONFLICT",
                    "The environment registry was restored or replaced since this was loaded. Reload and reapply your changes.");
            failure.registryEpoch = current.registryEpoch;
            throw failure;
        }
    }

    private void commit(Registry next, String operationId) {
        next.registryRevision = current.registryRevision + 1;
        try {
            write(next, operationId);
        } catch (ApiFailure e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void write(Registry next, String operationId) throws Exception {
        String json = toJson(next);
        // Validate before transferring ownership to persistence. Rejecting an
        // invalid import only after the database commit would corrupt storage.
        parse(json);
        beforeChange.run();
        WriteOutcome outcome;
        try {
            outcome = persistence.write(stored, json, operationId);
        } catch (Exception e) {
            logger.warn("Python Toolkit registry write did not confirm; reconciling", e);
            outcome = WriteOutcome.OUTCOME_UNKNOWN;
        }
        if (outcome != WriteOutcome.COMMITTED) {
            String after;
            try {
                after = persistence.read();
            } catch (Exception readFailure) {
                ApiFailure failure = ApiFailure.unavailable("OUTCOME_UNKNOWN",
                        "The database did not confirm the change. Reload before retrying with the same requestId.");
                failure.operationId = operationId;
                throw failure;
            }
            // A checked conflict is authoritative. Never reinterpret it as a
            // commit merely because another writer stored identical content.
            if (outcome == WriteOutcome.CONFLICT) {
                if (after != null && !Objects.equals(after, stored)) adopt(after);
                ApiFailure failure = ApiFailure.conflict("REGISTRY_WRITE_CONFLICT", "The environment registry changed during this request. Reload and retry.");
                failure.operationId = operationId;
                throw failure;
            }
            if (!json.equals(after)) {
                // Another successful write may already follow ours. A receipt
                // in the same epoch is durable evidence of our committed user
                // operation even when the whole document has since changed.
                if (after != null && confirmsReceipt(next, after, operationId)) {
                    adoptCommitted(after);
                    return;
                }
                ApiFailure failure = ApiFailure.unavailable(Objects.equals(after, stored) ? "NOT_COMMITTED" : "OUTCOME_UNKNOWN",
                        "The database did not confirm the change. Reload before retrying with the same requestId.");
                failure.operationId = operationId;
                if (after != null && !Objects.equals(after, stored)) adopt(after);
                throw failure;
            }
        }
        stored = json;
        current = parse(json);
    }

    private boolean confirmsReceipt(Registry next, String after, String operationId) {
        try {
            Registry observed = parse(after);
            if (!Objects.equals(next.registryEpoch, observed.registryEpoch)) return false;
            for (MutationReceipt intended : next.recentMutationReceipts) {
                if (!Objects.equals(operationId, intended.requestId)) continue;
                for (MutationReceipt actual : observed.recentMutationReceipts) {
                    if (Objects.equals(intended.actor, actual.actor) && Objects.equals(intended.requestId, actual.requestId)
                            && Objects.equals(intended.operation, actual.operation) && Objects.equals(intended.target, actual.target)
                            && Objects.equals(intended.contentDigest, actual.contentDigest)) return true;
                }
            }
        } catch (IOException ignored) { }
        return false;
    }

    /** Adopts a document another node or a restore wrote since our last read. */
    private void reconcileExternalChanges() {
        String latest;
        try {
            latest = persistence.read();
        } catch (Exception e) {
            throw ApiFailure.unavailable("REGISTRY_UNAVAILABLE", "Cannot read the environment registry: " + e.getMessage());
        }
        if (!Objects.equals(latest, stored)) {
            if (latest == null) throw ApiFailure.unavailable("REGISTRY_UNAVAILABLE", "The stored environment registry was removed. Reload after the registry is restored.");
            adopt(latest);
        }
    }

    private void adopt(String json) {
        try {
            adoptCommitted(json);
        } catch (IOException e) {
            throw ApiFailure.unavailable("REGISTRY_INVALID", "The stored environment registry is unreadable: " + e.getMessage());
        }
    }

    /** Canonicalizes a restore without publishing it before database commit. */
    public synchronized String prepareReplacement(String json) throws IOException {
        Registry incoming = parse(json);
        incoming.registryEpoch = UUID.randomUUID().toString();
        incoming.recentMutationReceipts = new ArrayList<>();
        incoming.registryRevision = Math.max(incoming.registryRevision, current == null ? 0 : current.registryRevision) + 1;
        return toJson(incoming);
    }

    /** Publishes only a document whose checked transaction has committed. */
    public synchronized void adoptCommitted(String json) throws IOException {
        Registry committed = parse(json);
        beforeChange.run();
        current = committed;
        stored = json;
    }

    String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- Helpers shared by callers ----------------------------------------------------

    public static Profile requireProfile(Registry registry, String id) {
        Profile profile = registry.profiles.get(id);
        if (profile == null) {
            throw ApiFailure.notFound("Environment profile " + id + " does not exist");
        }
        return profile;
    }

    public static void checkRevision(Profile profile, Long expectedRevision) {
        if (expectedRevision == null || expectedRevision != profile.revision) {
            ApiFailure failure = ApiFailure.conflict("REVISION_CONFLICT",
                    "Environment \"" + profile.name + "\" changed since it was loaded (revision " + profile.revision + ").");
            failure.currentRevision = profile.revision;
            throw failure;
        }
    }

    public static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    public static List<String> names(Registry registry) {
        List<String> names = new ArrayList<>();
        for (Profile p : registry.profiles.values()) {
            names.add(p.name);
        }
        return names;
    }
}
