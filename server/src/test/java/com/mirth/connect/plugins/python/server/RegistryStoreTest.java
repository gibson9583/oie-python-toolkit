/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mirth.connect.plugins.python.PythonApi;
import com.mirth.connect.plugins.python.PythonApi.Profile;
import com.mirth.connect.plugins.python.PythonApi.Registry;

class RegistryStoreTest {
    static final class Memory implements RegistryStore.Persistence {
        String json;
        boolean failRead;
        boolean unknown;
        Runnable beforeWrite;
        @Override public synchronized String read() throws Exception {
            if (failRead) throw new IOException("unavailable");
            return json;
        }
        @Override public synchronized RegistryStore.WriteOutcome write(String expected, String next, String operation) throws Exception {
            if (beforeWrite != null) { Runnable hook = beforeWrite; beforeWrite = null; hook.run(); }
            if (!Objects.equals(expected, json)) return RegistryStore.WriteOutcome.CONFLICT;
            json = next;
            return unknown ? RegistryStore.WriteOutcome.OUTCOME_UNKNOWN : RegistryStore.WriteOutcome.COMMITTED;
        }
    }

    private static RegistryStore store(Memory persistence) throws Exception {
        RegistryStore store = new RegistryStore(persistence);
        store.load(null);
        return store;
    }

    private static Profile profile() {
        Profile profile = new Profile();
        profile.id = UUID.randomUUID().toString();
        profile.name = "standard library";
        profile.runtimeId = "python3";
        profile.packageSourceId = "offline";
        profile.requirementsText = "";
        profile.revision = 1;
        profile.definitionFingerprint = PreparationManager.definitionFingerprint(profile);
        return profile;
    }

    @Test void checkedCasPreventsCrossNodeLostUpdateAndSnapshotRefreshes() throws Exception {
        Memory persistence = new Memory();
        RegistryStore first = store(persistence);
        RegistryStore second = store(persistence);
        String epoch = first.epoch();
        Profile winner = profile();
        Profile loser = profile();
        persistence.beforeWrite = () -> second.mutate("two", "winner", "create", winner.id, Map.of("name", winner.name), epoch,
                registry -> registry.profiles.put(winner.id, winner), registry -> registry.profiles.get(winner.id));
        ApiFailure conflict = assertThrows(ApiFailure.class, () -> first.mutate("one", "loser", "create", loser.id, Map.of("name", loser.name), epoch,
                registry -> { registry.profiles.put(loser.id, loser); return loser; }, registry -> registry.profiles.get(loser.id)));
        assertEquals("REGISTRY_WRITE_CONFLICT", conflict.code);
        assertTrue(first.snapshot().profiles.containsKey(winner.id));
        assertFalse(first.snapshot().profiles.containsKey(loser.id));
    }

    @Test void retryReplaysAndRejectsChangedContentAndOldEpoch() throws Exception {
        Memory persistence = new Memory();
        RegistryStore store = store(persistence);
        String epoch = store.epoch();
        Profile profile = profile();
        java.util.function.Function<Registry, Profile> create = registry -> { registry.profiles.put(profile.id, profile); return profile; };
        var first = store.mutate("actor", "id", "create", profile.id, Map.of("name", profile.name), epoch, create, registry -> registry.profiles.get(profile.id));
        var replay = store.mutate("actor", "id", "create", profile.id, Map.of("name", profile.name), epoch,
                registry -> { fail("retry must not repeat mutation"); return null; }, registry -> registry.profiles.get(profile.id));
        assertTrue(replay.replayed);
        assertEquals(first.registry.registryRevision, replay.registry.registryRevision);
        assertEquals("REQUEST_ID_REUSED", assertThrows(ApiFailure.class, () -> store.mutate("actor", "id", "create", profile.id,
                Map.of("name", "different"), epoch, create, registry -> null)).code);
        String replacement = store.prepareReplacement(store.storedJson());
        persistence.json = replacement;
        assertEquals("REGISTRY_EPOCH_CONFLICT", assertThrows(ApiFailure.class, () -> store.mutate("actor", "id", "create", profile.id,
                Map.of("name", profile.name), epoch, create, registry -> null)).code);
        assertTrue(store.snapshot().recentMutationReceipts.isEmpty());
    }

    @Test void unknownWriteIsReconciledWithoutSpeculativePublication() throws Exception {
        Memory persistence = new Memory();
        RegistryStore store = store(persistence);
        String epoch = store.epoch();
        String before = store.storedJson();
        Profile profile = profile();
        persistence.unknown = true;
        persistence.beforeWrite = () -> persistence.failRead = true;
        ApiFailure failure = assertThrows(ApiFailure.class, () -> store.mutate("actor", "request", "create", profile.id, Map.of(), epoch,
                registry -> { registry.profiles.put(profile.id, profile); return profile; }, registry -> registry.profiles.get(profile.id)));
        assertEquals("OUTCOME_UNKNOWN", failure.code);
        assertEquals(before, store.storedJson());
        persistence.failRead = false;
        var replay = store.mutate("actor", "request", "create", profile.id, Map.of(), epoch,
                registry -> { fail("committed unknown operation must replay"); return null; }, registry -> registry.profiles.get(profile.id));
        assertTrue(replay.replayed);
        assertEquals(profile.id, replay.value.id);
    }

    @Test void malformedExternalRegistryFailsClosedAndRestoreDoesNotPublishEarly() throws Exception {
        Memory persistence = new Memory();
        RegistryStore store = store(persistence);
        String before = store.storedJson();
        String restore = store.prepareReplacement(before);
        assertEquals(before, store.storedJson());
        Registry next = PythonApi.mapper().readValue(restore, Registry.class);
        assertNotEquals(store.epoch(), next.registryEpoch);
        persistence.json = "broken";
        assertEquals("REGISTRY_INVALID", assertThrows(ApiFailure.class, store::snapshot).code);
        assertEquals(before, store.storedJson());
        persistence.json = null;
        assertEquals("REGISTRY_UNAVAILABLE", assertThrows(ApiFailure.class, store::snapshot).code);
    }

    @Test void lockedWheelPathTraversalAndForgedDefinitionCannotBeRestored() throws Exception {
        Memory persistence = new Memory();
        RegistryStore store = store(persistence);
        Registry registry = store.snapshot();
        Profile profile = profile();
        registry.profiles.put(profile.id, profile);
        profile.definitionFingerprint = "0".repeat(64);
        assertThrows(IOException.class, () -> store.prepareReplacement(PythonApi.mapper().writeValueAsString(registry)));
        profile.definitionFingerprint = PreparationManager.definitionFingerprint(profile);
        PythonApi.LockedRequirement wheel = new PythonApi.LockedRequirement();
        wheel.name = "safe";
        wheel.version = "1";
        wheel.filename = "../../outside.whl";
        wheel.sha256 = "a".repeat(64);
        PythonApi.ResolvedLock lock = new PythonApi.ResolvedLock();
        lock.definitionFingerprint = profile.definitionFingerprint;
        lock.targetFingerprint = "b".repeat(64);
        lock.packageSourcePolicy = "c".repeat(64);
        lock.lockedRequirements.add(wheel);
        lock.lockDigest = PreparationManager.lockDigest(lock.targetFingerprint, lock.lockedRequirements);
        profile.resolvedLocks.put(lock.targetFingerprint, lock);
        assertThrows(IOException.class, () -> store.prepareReplacement(PythonApi.mapper().writeValueAsString(registry)));
    }
}
