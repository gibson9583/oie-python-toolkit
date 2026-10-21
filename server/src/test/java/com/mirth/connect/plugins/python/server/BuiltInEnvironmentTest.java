/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirth.connect.plugins.python.PythonApi;
import com.mirth.connect.plugins.python.PythonContract;
import com.mirth.connect.plugins.python.PythonStep;

class BuiltInEnvironmentTest {
    @TempDir Path temp;

    static final class Memory implements RegistryStore.Persistence {
        String json;
        int writes;
        boolean conflict;
        boolean unknown;
        boolean failRead;
        Runnable beforeWrite;
        @Override public synchronized String read() throws Exception {
            if (failRead) throw new IOException("Owned fixture database unavailable");
            return json;
        }
        @Override public synchronized RegistryStore.WriteOutcome write(String expected, String next, String operation) {
            writes++;
            if (beforeWrite != null) { Runnable once = beforeWrite; beforeWrite = null; once.run(); }
            if (conflict || !Objects.equals(expected, json)) return RegistryStore.WriteOutcome.CONFLICT;
            json = next;
            return unknown ? RegistryStore.WriteOutcome.OUTCOME_UNKNOWN : RegistryStore.WriteOutcome.COMMITTED;
        }
    }

    private OperatorConfig config(Map<String, String> properties) {
        return new OperatorConfig(key -> null, key -> properties.get(key.substring(OperatorConfig.PROPERTY_PREFIX.length())),
                temp.toString(), "builtin-test");
    }
    private RegistryStore store(Memory memory) throws Exception {
        RegistryStore registry = new RegistryStore(memory); registry.load(null); return registry;
    }
    private static PythonApi.Profile custom(String id, String name) {
        PythonApi.Profile profile = new PythonApi.Profile(); profile.id = id; profile.name = name; profile.description = "";
        profile.revision = 1; profile.runtimeId = "python3"; profile.packageSourceId = "pypi"; profile.requirementsText = "";
        profile.definitionFingerprint = PreparationManager.definitionFingerprint(profile); return profile;
    }
    private PythonRuntime runtime(RegistryStore registry) throws Exception {
        return new PythonRuntime(config(Map.of("sources", "")), registry, mock(EngineAccess.class));
    }
    private static void replace(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName); field.setAccessible(true); field.set(target, value);
    }
    private static PythonApi.ScriptRequest script(RegistryStore registry, String selection) {
        var request = new PythonApi.ScriptRequest(); request.requestId = "builtin-script"; request.environmentProfileId = selection;
        request.role = PythonContract.ROLE_SOURCE_TRANSFORMER; request.pythonScript = "pass\n";
        request.expectedRegistryEpoch = registry.epoch(); request.expectedEnvironmentRevision = 1L; return request;
    }

    @Test void seedPreservesProfilesEpochAndReceiptsAndRepeatedCallbacksDoNotWrite() throws Exception {
        Memory memory = new Memory(); RegistryStore registry = store(memory); String epoch = registry.epoch();
        var custom = custom(UUID.randomUUID().toString(), "Existing");
        registry.mutate("owner", "create-existing", "create", custom.id, Map.of("id", custom.id), epoch,
                next -> { next.profiles.put(custom.id, custom); return custom.id; }, next -> custom.id);
        var before = registry.snapshot(); String customBytes = registry.toJson(custom);
        assertTrue(BuiltInEnvironment.seed(registry, config(Map.of())));
        var after = registry.snapshot(); int writes = memory.writes;
        assertEquals(epoch, after.registryEpoch); assertEquals(before.registryRevision + 1, after.registryRevision);
        assertEquals(customBytes, registry.toJson(after.profiles.get(custom.id)));
        assertEquals(registry.toJson(before.recentMutationReceipts), registry.toJson(after.recentMutationReceipts));
        assertEquals(PythonContract.DEFAULT_PROFILE_ID, BuiltInEnvironment.defaultId(after));
        assertFalse(BuiltInEnvironment.seed(registry, config(Map.of("runtimes", "other=/unapproved-now"))));
        assertEquals(writes, memory.writes); assertEquals("python3", after.profiles.get(PythonContract.DEFAULT_PROFILE_ID).runtimeId);
    }

    @Test void concurrentSeedKeepsWinnerAndUncertainCommittedSeedDoesNotRepeat() throws Exception {
        Memory memory = new Memory(); RegistryStore first = store(memory); RegistryStore second = store(memory);
        memory.beforeWrite = () -> BuiltInEnvironment.seed(second, config(Map.of("runtimes", "approved=/owned/python")));
        assertFalse(BuiltInEnvironment.seed(first, config(Map.of())));
        var winner = first.snapshot().profiles.get(PythonContract.DEFAULT_PROFILE_ID);
        assertEquals("approved", winner.runtimeId); assertTrue(BuiltInEnvironment.owned(winner));
        Memory uncertain = new Memory(); RegistryStore other = store(uncertain); uncertain.unknown = true;
        assertTrue(BuiltInEnvironment.seed(other, config(Map.of())));
        int committedWrites = uncertain.writes;
        assertFalse(BuiltInEnvironment.seed(other, config(Map.of()))); assertEquals(committedWrites, uncertain.writes);
    }

    @Test void defaultRuntimeUsesOnlyApprovedPolicyAndInternalSourceIsIndependentOfExternalIndexes() {
        var noExternal = config(Map.of("sources", "", "runtimes", "custom=/owned/python"));
        assertEquals(java.util.Set.of(PythonContract.BUILTIN_SOURCE_ID), noExternal.sources.keySet());
        assertEquals("custom", BuiltInEnvironment.definition(noExternal).runtimeId);
        assertEquals("python3", BuiltInEnvironment.definition(config(Map.of("runtimes", "custom=/owned/first,python3=/owned/second"))).runtimeId);
        String fixed = noExternal.sources.get(PythonContract.BUILTIN_SOURCE_ID).policyIdentity();
        assertEquals(fixed, config(Map.of("source.pypi.indexUrl", "https://example.invalid/simple")).sources.get(PythonContract.BUILTIN_SOURCE_ID).policyIdentity());
        assertThrows(IllegalArgumentException.class, () -> config(Map.of("sources", PythonContract.BUILTIN_SOURCE_ID)));
        assertThrows(IllegalArgumentException.class, () -> config(Map.of("runtimes", "")));
    }

    @Test void reservedUuidCollisionIsPreservedAndCannotBecomeDefaultOrExecute() throws Exception {
        Memory memory = new Memory(); RegistryStore registry = store(memory);
        var collision = custom(PythonContract.DEFAULT_PROFILE_ID, "Custom collision");
        registry.change(next -> { next.profiles.put(collision.id, collision); return true; });
        String before = memory.json; int writes = memory.writes;
        assertFalse(BuiltInEnvironment.seed(registry, config(Map.of())));
        assertEquals(before, memory.json); assertEquals(writes, memory.writes); assertNull(BuiltInEnvironment.defaultId(registry.snapshot()));
        try (RuntimeFixture fixture = new RuntimeFixture(runtime(registry))) {
            Interpreters interpreters = mock(Interpreters.class); replace(fixture.runtime, "interpreters", interpreters);
            fixture.runtime.builtIn.initialize(registry.snapshot());
            RuntimeApi api = new RuntimeApi(fixture.runtime);
            assertNull(api.list(false).defaultProfileId); assertNotNull(api.list(false).defaultProfileMessage);
            for (String id : new String[] { null, "", PythonContract.DEFAULT_PROFILE_ID }) {
                var request = script(registry, id);
                assertEquals("DEFAULT_ENVIRONMENT_UNAVAILABLE", api.validate(request).diagnostics.get(0).code);
                assertEquals("DEFAULT_ENVIRONMENT_UNAVAILABLE", api.test(request, null).diagnostics.get(0).code);
                assertEquals(id, request.environmentProfileId);
            }
            verifyNoInteractions(interpreters);
            var deployment = PythonRuntimeIntegrationTest.deployment(UUID.randomUUID().toString(), "", "collision");
            assertThrows(Deployments.DeployFailure.class, () -> fixture.runtime.deployments.prepare(deployment.model()));
            verifyNoInteractions(interpreters); assertEquals(before, memory.json);
        }
    }

    @Test void nameCollisionUsesDeterministicUniqueLabelAndCustomRemainsEditable() throws Exception {
        Memory memory = new Memory(); RegistryStore registry = store(memory);
        var first = custom(UUID.randomUUID().toString(), "  " + PythonContract.DEFAULT_PROFILE_NAME.toUpperCase() + "  ");
        var second = custom(UUID.randomUUID().toString(), PythonContract.DEFAULT_PROFILE_NAME + " (BUILT IN)");
        var third = custom(UUID.randomUUID().toString(), PythonContract.DEFAULT_PROFILE_NAME + " (built in) 2");
        registry.change(next -> { for (var p : List.of(first, second, third)) next.profiles.put(p.id, p); return true; });
        BuiltInEnvironment.seed(registry, config(Map.of()));
        var builtin = registry.snapshot().profiles.get(PythonContract.DEFAULT_PROFILE_ID);
        assertEquals(PythonContract.DEFAULT_PROFILE_NAME + " (built in) 3", builtin.name); assertTrue(BuiltInEnvironment.owned(builtin));
        try (RuntimeFixture fixture = new RuntimeFixture(runtime(registry))) {
            var request = new PythonApi.ProfileRequest(); request.requestId = "edit-existing-name"; request.expectedRegistryEpoch = registry.epoch();
            request.expectedRevision = 1L; request.name = first.name.trim(); request.runtimeId = "python3";
            request.packageSourceId = PythonContract.BUILTIN_SOURCE_ID; request.requirementsText = "";
            assertEquals(first.id, new RuntimeApi(fixture.runtime).update("owner", first.id, request).profile.id);
        }
    }

    @Test void legacyOwnedLabelsArePresentationOnlyAndCustomNamesRemainUntouched() throws Exception {
        Memory memory = new Memory(); RegistryStore registry = store(memory);
        var builtin = BuiltInEnvironment.definition(config(Map.of()));
        builtin.name = "Vanilla Python (standard library) (built in) 2";
        var custom = custom(UUID.randomUUID().toString(), "Vanilla Python (standard library)");
        registry.change(next -> { next.profiles.put(builtin.id, builtin); next.profiles.put(custom.id, custom); return true; });
        String stored = memory.json; int writes = memory.writes;
        try (RuntimeFixture fixture = new RuntimeFixture(runtime(registry))) {
            var view = fixture.runtime.view(builtin, registry.epoch(), new PythonApi.LocalReadiness());
            assertEquals("Python (built in) 2", view.name);
            assertEquals(builtin.id, view.id); assertEquals(builtin.revision, view.revision);
            assertEquals(builtin.definitionFingerprint, view.definitionFingerprint); assertTrue(view.builtInDefault);
            assertEquals(custom.name, fixture.runtime.view(custom, registry.epoch(), new PythonApi.LocalReadiness()).name);
            assertEquals(stored, memory.json); assertEquals(writes, memory.writes);
            builtin.name = "Vanilla Python (standard library)";
            assertEquals("Python", BuiltInEnvironment.displayName(builtin));
            builtin.builtInDefault = false;
            assertEquals(builtin.name, BuiltInEnvironment.displayName(builtin));
        }
    }

    @Test void builtInIsQuotaNeutralButAnExtraCustomProfileStillFailsBeforeStorage() throws Exception {
        Memory memory = new Memory(); RegistryStore registry = store(memory);
        registry.change(next -> { for (int i = 0; i < 1000; i++) {
            var p = custom(UUID.nameUUIDFromBytes(("capacity-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(), "Custom " + i);
            next.profiles.put(p.id, p);
        } return true; });
        assertTrue(BuiltInEnvironment.seed(registry, config(Map.of()))); assertEquals(1001, registry.snapshot().profiles.size());
        String before = memory.json; int writes = memory.writes;
        assertThrows(IllegalStateException.class, () -> registry.change(next -> {
            var extra = custom(UUID.randomUUID().toString(), "Too many"); next.profiles.put(extra.id, extra); return true;
        }));
        assertEquals(before, memory.json); assertEquals(writes, memory.writes);
        assertNotNull(registry.parse(before).profiles.get(PythonContract.DEFAULT_PROFILE_ID));
    }

    @Test void optionalSeedConflictsAreNonfatalAndBoundedButInvalidExistingStorageFailsClosed() throws Exception {
        Memory memory = new Memory(); RegistryStore registry = store(memory);
        var custom = custom(UUID.randomUUID().toString(), "Surviving"); registry.change(next -> { next.profiles.put(custom.id, custom); return true; });
        try (RuntimeFixture fixture = new RuntimeFixture(runtime(registry))) {
            ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class); fixture.runtime.housekeeping.shutdownNow();
            replace(fixture.runtime, "housekeeping", scheduler); memory.conflict = true; int before = memory.writes;
            assertDoesNotThrow(fixture.runtime::start); assertSame(fixture.runtime, PythonRuntime.require());
            assertNotNull(fixture.runtime.builtIn.failure()); assertTrue(registry.snapshot().profiles.containsKey(custom.id));
            for (int i = 0; i < 10; i++) fixture.runtime.builtIn.tick();
            assertEquals(9, memory.writes - before, "Three finite seed rounds, each with three CAS attempts");
            assertTrue(fixture.runtime.builtIn.failure().contains("Restart"));
            assertNull(BuiltInEnvironment.defaultId(registry.snapshot()));
        }
        memory.conflict = false;
        try (RuntimeFixture fixture = new RuntimeFixture(runtime(registry))) {
            memory.json = "not a registry";
            assertEquals("REGISTRY_INVALID", assertThrows(ApiFailure.class, fixture.runtime::start).code);
        }
    }

    @Test void builtInUpdatesArchiveAndRefreshAreProtectedAndExplicitPrepareIsPermitted() throws Exception {
        Memory memory = new Memory(); RegistryStore registry = store(memory); BuiltInEnvironment.seed(registry, config(Map.of()));
        try (RuntimeFixture fixture = new RuntimeFixture(runtime(registry))) {
            RuntimeApi api = new RuntimeApi(fixture.runtime); String before = memory.json;
            var revision = new PythonApi.RevisionRequest(); revision.expectedRegistryEpoch = registry.epoch(); revision.expectedRevision = 1L; revision.requestId = "retry";
            assertEquals("BUILTIN_PROFILE_READ_ONLY", assertThrows(ApiFailure.class, () -> api.update("owner", PythonContract.DEFAULT_PROFILE_ID, new PythonApi.ProfileRequest())).code);
            assertEquals("BUILTIN_PROFILE_READ_ONLY", assertThrows(ApiFailure.class, () -> api.archive("owner", PythonContract.DEFAULT_PROFILE_ID, revision)).code);
            assertEquals("BUILTIN_PROFILE_READ_ONLY", assertThrows(ApiFailure.class, () -> api.refresh("owner", PythonContract.DEFAULT_PROFILE_ID, revision)).code);
            assertEquals(before, memory.json);
            fixture.runtime.preparation.shutdown(); var manager = mock(PreparationManager.class); replace(fixture.runtime, "preparation", manager);
            var accepted = new PythonApi.JobView(); accepted.id = "accepted"; accepted.state = "QUEUED";
            when(manager.prepare(eq(PythonContract.DEFAULT_PROFILE_ID), eq(registry.epoch()), eq(1L), any())).thenReturn(accepted);
            PreparationManager.Authorizer authorization = () -> true;
            assertEquals("accepted", api.prepare(PythonContract.DEFAULT_PROFILE_ID, revision, authorization).job.id);
            verify(manager).prepare(PythonContract.DEFAULT_PROFILE_ID, registry.epoch(), 1L, authorization);
        }
    }

    @Test void restoredMarkerRequirementsAndInjectedPackageLocksAreRejectedBeforeWrite() throws Exception {
        Memory memory = new Memory(); RegistryStore registry = store(memory); BuiltInEnvironment.seed(registry, config(Map.of()));
        String before = memory.json; int writes = memory.writes;
        for (String attack : new String[] { "requirements", "lock", "policy", "marker-id" }) {
            var restored = registry.snapshot(); var builtin = restored.profiles.get(PythonContract.DEFAULT_PROFILE_ID);
            if (attack.equals("requirements")) builtin.requirementsText = "foreign==1";
            if (attack.equals("marker-id")) { restored.profiles.remove(builtin.id); builtin.id = UUID.randomUUID().toString(); restored.profiles.put(builtin.id, builtin); }
            if (attack.equals("lock") || attack.equals("policy")) {
                var lock = emptyLock(builtin); if (attack.equals("lock")) lock.lockedRequirements.add(wheel());
                if (attack.equals("policy")) lock.packageSourcePolicy = "c".repeat(64);
                lock.lockDigest = PreparationManager.lockDigest(lock.targetFingerprint, lock.lockedRequirements);
                builtin.resolvedLocks.put(lock.targetFingerprint, lock);
            }
            builtin.definitionFingerprint = PreparationManager.definitionFingerprint(builtin);
            assertThrows(IOException.class, () -> registry.prepareReplacement(registry.toJson(restored)), attack);
            assertEquals(before, memory.json); assertEquals(writes, memory.writes);
        }
        assertTrue(BuiltInEnvironment.stdlibOnly(" \n# no packages\r\n"));
        assertFalse(BuiltInEnvironment.stdlibOnly("# comment\npackage==1"));
    }

    @Test void importsCannotSpoofReservedOwnershipOrAdoptForeignBuiltinLocks() throws Exception {
        Memory memory = new Memory(); RegistryStore registry = store(memory); BuiltInEnvironment.seed(registry, config(Map.of()));
        try (RuntimeFixture fixture = new RuntimeFixture(runtime(registry))) {
            RuntimeApi api = new RuntimeApi(fixture.runtime); var exported = new PythonApi.ExportBundle();
            exported.profiles.add(exported(registry.snapshot().profiles.get(PythonContract.DEFAULT_PROFILE_ID)));
            var request = new PythonApi.ImportRequest(); request.bundle = exported; request.requestId = "same-default"; request.expectedRegistryEpoch = registry.epoch();
            var result = api.importBundle("owner", request); assertEquals(List.of(PythonContract.DEFAULT_PROFILE_ID), result.unchanged);
            assertTrue(registry.snapshot().profiles.get(PythonContract.DEFAULT_PROFILE_ID).resolvedLocks.isEmpty());
            exported.profiles.get(0).description = "Overwrite"; request.requestId = "overwrite-default";
            String before = memory.json; assertEquals("IMPORT_CONFLICT", assertThrows(ApiFailure.class, () -> api.importBundle("owner", request)).code);
            assertEquals(before, memory.json);
            var builtin = registry.snapshot().profiles.get(PythonContract.DEFAULT_PROFILE_ID); var lock = emptyLock(builtin); lock.lockedRequirements.add(wheel());
            lock.lockDigest = PreparationManager.lockDigest(lock.targetFingerprint, lock.lockedRequirements);
            exported.profiles.get(0).locks.add(lock); request.requestId = "foreign-packages";
            assertEquals("STDLIB_ONLY", assertThrows(ApiFailure.class, () -> api.importBundle("owner", request)).code); assertEquals(before, memory.json);
            exported.profiles.get(0).locks.clear(); exported.profiles.get(0).id = UUID.randomUUID().toString();
            request.mappings.put(exported.profiles.get(0).id, PythonContract.DEFAULT_PROFILE_ID); request.requestId = "target-default";
            assertEquals("IMPORT_CONFLICT", assertThrows(ApiFailure.class, () -> api.importBundle("owner", request)).code); assertEquals(before, memory.json);
        }
    }

    @Test void automaticPreparationIsFiniteDeduplicatedByReadinessAndClosedAtStop() throws Exception {
        Memory memory = new Memory(); RegistryStore registry = store(memory); BuiltInEnvironment.seed(registry, config(Map.of()));
        try (RuntimeFixture fixture = new RuntimeFixture(runtime(registry))) {
            fixture.runtime.preparation.shutdown(); var manager = mock(PreparationManager.class); replace(fixture.runtime, "preparation", manager);
            var interpreters = mock(Interpreters.class); replace(fixture.runtime, "interpreters", interpreters);
            var target = new Interpreters.Target("python3", temp.resolve("never-run"), "3.14.6", "a".repeat(64), Map.of());
            when(interpreters.probe("python3")).thenReturn(target);
            var job = new PythonApi.JobView(); job.state = "FAILED"; job.profileId = PythonContract.DEFAULT_PROFILE_ID;
            when(manager.prepare(anyString(), anyString(), anyLong(), any())).thenReturn(job);
            for (int i = 0; i < 10; i++) fixture.runtime.builtIn.tick();
            verify(manager, times(3)).prepare(eq(PythonContract.DEFAULT_PROFILE_ID), eq(registry.epoch()), eq(1L), any());
            assertTrue(fixture.runtime.builtIn.failure().contains("after 3 attempts"));
            fixture.runtime.builtIn.close(); fixture.runtime.builtIn.tick(); verifyNoMoreInteractionsOnPrepare(manager);
        }
    }

    private static void verifyNoMoreInteractionsOnPrepare(PreparationManager manager) {
        verify(manager, times(3)).prepare(anyString(), anyString(), anyLong(), any());
    }

    @Test void nullAndEmptySelectDefaultButWhitespaceRemainsAnUnresolvedExactId() {
        assertEquals(PythonContract.DEFAULT_PROFILE_ID, BuiltInEnvironment.selectedId(null));
        assertEquals(PythonContract.DEFAULT_PROFILE_ID, BuiltInEnvironment.selectedId(""));
        for (String id : new String[] { " ", "\n", "unknown" }) assertEquals(id, BuiltInEnvironment.selectedId(id));
        PythonStep legacy = new PythonStep(); legacy.setEnvironmentProfileId(""); String before = PythonContract.specDigest(legacy);
        assertEquals(PythonContract.DEFAULT_PROFILE_ID, BuiltInEnvironment.selectedId(legacy.getEnvironmentProfileId()));
        assertEquals("", legacy.getEnvironmentProfileId()); assertEquals(before, PythonContract.specDigest(legacy));
    }

    @Test void preparationErrorBecomesTerminalAndReleasesTheActiveSlot() throws Exception {
        Memory memory = new Memory(); RegistryStore registry = store(memory); BuiltInEnvironment.seed(registry, config(Map.of()));
        try (RuntimeFixture fixture = new RuntimeFixture(runtime(registry))) {
            fixture.runtime.preparation.shutdown(); var interpreters = mock(Interpreters.class);
            when(interpreters.probeForPreparation(anyString(), anyLong(), any())).thenThrow(new LinkageError("Owned failing probe"));
            var manager = new PreparationManager(fixture.runtime.config, registry, interpreters, fixture.runtime.artifacts,
                    fixture.runtime.sdk, fixture.runtime.baseEnv, () -> {}); replace(fixture.runtime, "preparation", manager);
            var accepted = manager.prepare(PythonContract.DEFAULT_PROFILE_ID, registry.epoch(), 1L, () -> true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); PythonApi.JobView terminal;
            do { terminal = manager.job(accepted.id); if (PreparationManager.TERMINAL.contains(terminal.state)) break; Thread.sleep(10); } while (System.nanoTime() < deadline);
            assertEquals("FAILED", terminal.state); assertEquals("PREPARATION_FAILED", terminal.diagnostics.get(0).code);
            var executorField = PreparationManager.class.getDeclaredField("executor"); executorField.setAccessible(true);
            ThreadPoolExecutor executor = (ThreadPoolExecutor) executorField.get(manager);
            while (manager.hasActiveJob(PythonContract.DEFAULT_PROFILE_ID, registry.epoch(), 1L) && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(manager.hasActiveJob(PythonContract.DEFAULT_PROFILE_ID, registry.epoch(), 1L));
            manager.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void cancelledDefaultStaysStoppedUntilExplicitRetryOrTargetChanges() throws Exception {
        Memory memory = new Memory(); RegistryStore registry = store(memory); BuiltInEnvironment.seed(registry, config(Map.of()));
        try (RuntimeFixture fixture = new RuntimeFixture(runtime(registry))) {
            fixture.runtime.preparation.shutdown(); var manager = mock(PreparationManager.class); replace(fixture.runtime, "preparation", manager);
            var interpreters = mock(Interpreters.class); replace(fixture.runtime, "interpreters", interpreters);
            var target = new Interpreters.Target("python3", temp.resolve("never-run"), "3.14.6", "a".repeat(64), Map.of());
            when(interpreters.probe("python3")).thenReturn(target);
            var latest = new java.util.concurrent.atomic.AtomicReference<PythonApi.JobView>();
            when(manager.latestFor(anyString(), anyString(), anyString(), anyLong())).thenAnswer(call -> latest.get());
            var ids = new java.util.concurrent.atomic.AtomicInteger();
            when(manager.prepare(anyString(), anyString(), anyLong(), any())).thenAnswer(call -> {
                var job = new PythonApi.JobView(); job.id = "job-" + ids.incrementAndGet(); job.state = "QUEUED";
                job.targetFingerprint = target.fingerprint; latest.set(job); return job;
            });
            fixture.runtime.builtIn.tick(); assertEquals(1, ids.get());
            latest.get().state = "CANCELLED";
            for (int i = 0; i < 10; i++) fixture.runtime.builtIn.tick();
            assertEquals(1, ids.get()); assertTrue(fixture.runtime.builtIn.failure().contains("cancelled"));
            manager.prepare(PythonContract.DEFAULT_PROFILE_ID, registry.epoch(), 1L, () -> true);
            fixture.runtime.builtIn.tick(); assertEquals(2, ids.get(), "Explicit Prepare resumes without another automatic job");
            latest.get().state = "CANCELLED"; fixture.runtime.builtIn.tick(); assertEquals(2, ids.get());
            var changed = new Interpreters.Target("python3", temp.resolve("never-run-upgraded"), "3.15.0", "b".repeat(64), Map.of());
            when(interpreters.probe("python3")).thenReturn(changed);
            fixture.runtime.builtIn.tick(); assertEquals(3, ids.get(), "A changed target receives a fresh automatic attempt");
        }
    }

    @Test void fullCapacityExportOmitsBuiltinAndRoundTripsAllCustomProfiles() throws Exception {
        Memory memory = new Memory(); RegistryStore registry = store(memory);
        registry.change(next -> { for (int i = 0; i < 1000; i++) {
            var p = custom(UUID.nameUUIDFromBytes(("export-capacity-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(), "Custom " + i);
            next.profiles.put(p.id, p);
        } return true; });
        BuiltInEnvironment.seed(registry, config(Map.of()));
        try (RuntimeFixture source = new RuntimeFixture(new PythonRuntime(config(Map.of()), registry, mock(EngineAccess.class)))) {
            RuntimeApi api = new RuntimeApi(source.runtime); var export = api.export(null);
            assertEquals(1000, export.profiles.size()); assertTrue(export.profiles.stream().noneMatch(p -> PythonContract.DEFAULT_PROFILE_ID.equals(p.id)));
            assertTrue(api.export(java.util.Set.of(PythonContract.DEFAULT_PROFILE_ID)).profiles.isEmpty());
            Memory targetMemory = new Memory(); RegistryStore targetRegistry = store(targetMemory); BuiltInEnvironment.seed(targetRegistry, config(Map.of("runtimes", "custom=/owned/custom")));
            Path targetCache = temp.resolve("target-node");
            try (RuntimeFixture destination = new RuntimeFixture(new PythonRuntime(new OperatorConfig(key -> null, key -> null, targetCache.toString(), "target-node"), targetRegistry, mock(EngineAccess.class)))) {
                var request = new PythonApi.ImportRequest(); request.requestId = "full-capacity"; request.expectedRegistryEpoch = targetRegistry.epoch(); request.bundle = export;
                var imported = new RuntimeApi(destination.runtime).importBundle("owner", request);
                assertEquals(1000, imported.created.size()); assertEquals(1001, targetRegistry.snapshot().profiles.size());
                assertEquals("custom", targetRegistry.snapshot().profiles.get(PythonContract.DEFAULT_PROFILE_ID).runtimeId);
                var replay = new RuntimeApi(destination.runtime).importBundle("owner", request);
                assertTrue(replay.replayed); assertEquals(imported.mappings, replay.mappings);
            }
        }
    }

    static PythonApi.ResolvedLock emptyLock(PythonApi.Profile profile) {
        var lock = new PythonApi.ResolvedLock(); lock.targetFingerprint = "a".repeat(64); lock.definitionFingerprint = profile.definitionFingerprint;
        lock.packageSourcePolicy = OperatorConfig.builtInSourcePolicy(); lock.lockDigest = PreparationManager.lockDigest(lock.targetFingerprint, lock.lockedRequirements); return lock;
    }
    static PythonApi.LockedRequirement wheel() {
        var wheel = new PythonApi.LockedRequirement(); wheel.name = "foreign"; wheel.version = "1";
        wheel.filename = "foreign-1-py3-none-any.whl"; wheel.sha256 = "b".repeat(64); return wheel;
    }
    static PythonApi.ExportedProfile exported(PythonApi.Profile profile) {
        var exported = new PythonApi.ExportedProfile(); exported.id = profile.id; exported.name = profile.name;
        exported.description = profile.description; exported.runtimeId = profile.runtimeId; exported.packageSourceId = profile.packageSourceId;
        exported.requirementsText = profile.requirementsText; exported.refreshGeneration = profile.refreshGeneration;
        exported.locks.addAll(profile.resolvedLocks.values()); return exported;
    }
    static final class RuntimeFixture implements AutoCloseable {
        final PythonRuntime runtime;
        RuntimeFixture(PythonRuntime runtime) { this.runtime = runtime; }
        @Override public void close() { runtime.stop(); }
    }
}
