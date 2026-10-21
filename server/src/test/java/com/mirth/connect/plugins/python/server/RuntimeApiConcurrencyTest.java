/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirth.connect.plugins.python.PythonApi;
import com.mirth.connect.plugins.python.PythonContract;

class RuntimeApiConcurrencyTest {
    @TempDir Path temp;
    private PythonRuntime runtime(RegistryStoreTest.Memory persistence, boolean missingInterpreter) throws Exception {
        RegistryStore store = new RegistryStore(persistence);
        store.load(null);
        OperatorConfig config = new OperatorConfig(name -> null,
                name -> missingInterpreter && name.equals(OperatorConfig.PROPERTY_PREFIX + "runtimes") ? "python3=/does/not/exist" : null,
                temp.toString(), "local");
        return new PythonRuntime(config, store, mock(EngineAccess.class));
    }
    private static PythonApi.Profile profile(String id, String name) {
        PythonApi.Profile profile = new PythonApi.Profile();
        profile.id = id; profile.name = name; profile.description = ""; profile.revision = 1;
        profile.runtimeId = "python3"; profile.packageSourceId = "pypi"; profile.requirementsText = "";
        profile.definitionFingerprint = PreparationManager.definitionFingerprint(profile);
        return profile;
    }
    @Test void importRetryReturnsOriginalNewIdMappingAndDoesNotCreateAgain() throws Exception {
        PythonRuntime runtime = runtime(new RegistryStoreTest.Memory(), false);
        try {
            String originalId = UUID.randomUUID().toString();
            runtime.registry.change(registry -> { registry.profiles.put(originalId, profile(originalId, "Existing")); return true; });
            PythonApi.ExportedProfile exported = new PythonApi.ExportedProfile();
            exported.id = originalId; exported.name = "Imported"; exported.description = ""; exported.runtimeId = "python3";
            exported.packageSourceId = "pypi"; exported.requirementsText = "";
            PythonApi.ImportRequest request = new PythonApi.ImportRequest();
            request.expectedRegistryEpoch = runtime.registry.epoch(); request.requestId = "import-once";
            request.bundle = new PythonApi.ExportBundle(); request.bundle.profiles.add(exported); request.mappings.put(originalId, "new");
            RuntimeApi api = new RuntimeApi(runtime);
            PythonApi.ImportResult first = api.importBundle("actor", request);
            PythonApi.ImportResult retry = api.importBundle("actor", request);
            assertTrue(retry.replayed);
            assertEquals(first.mappings, retry.mappings);
            assertEquals(first.created, retry.created);
            assertEquals(2, runtime.registry.snapshot().profiles.size());
        } finally { runtime.stop(); }
    }
    @Test void validateAndTestRejectStaleEpochOrRevisionBeforeInterpreterActivity() throws Exception {
        PythonRuntime runtime = runtime(new RegistryStoreTest.Memory(), true);
        try {
            String id = UUID.randomUUID().toString();
            runtime.registry.change(registry -> { registry.profiles.put(id, profile(id, "Test")); return true; });
            RuntimeApi api = new RuntimeApi(runtime);
            PythonApi.ScriptRequest request = new PythonApi.ScriptRequest();
            request.requestId = "validate"; request.environmentProfileId = id; request.role = PythonContract.ROLE_SOURCE_TRANSFORMER;
            request.pythonScript = "pass\n";
            request.expectedRegistryEpoch = "stale"; request.expectedEnvironmentRevision = 1L;
            assertEquals("REGISTRY_EPOCH_CONFLICT", assertThrows(ApiFailure.class, () -> api.validate(request)).code);
            assertEquals("REGISTRY_EPOCH_CONFLICT", assertThrows(ApiFailure.class, () -> api.test(request, null)).code);
            request.expectedRegistryEpoch = runtime.registry.epoch(); request.expectedEnvironmentRevision = 0L;
            assertEquals("REVISION_CONFLICT", assertThrows(ApiFailure.class, () -> api.validate(request)).code);
            assertEquals("REVISION_CONFLICT", assertThrows(ApiFailure.class, () -> api.test(request, null)).code);
        } finally { runtime.stop(); }
    }
    @Test void importNeverRelabelsAnUnrelatedDependencyLock() throws Exception {
        PythonRuntime runtime = runtime(new RegistryStoreTest.Memory(), true);
        try {
            PythonApi.ExportedProfile exported = new PythonApi.ExportedProfile();
            exported.id = UUID.randomUUID().toString(); exported.name = "Imported locks"; exported.description = "";
            exported.runtimeId = "python3"; exported.packageSourceId = "pypi"; exported.requirementsText = "package==1";
            PythonApi.Profile material = profile(exported.id, exported.name);
            material.requirementsText = exported.requirementsText;
            material.definitionFingerprint = PreparationManager.definitionFingerprint(material);
            PythonApi.ResolvedLock matching = new PythonApi.ResolvedLock();
            matching.targetFingerprint = "a".repeat(64); matching.definitionFingerprint = material.definitionFingerprint;
            matching.packageSourcePolicy = runtime.config.sources.get("pypi").policyIdentity();
            PythonApi.LockedRequirement wheel = new PythonApi.LockedRequirement();
            wheel.name = "package"; wheel.version = "1"; wheel.filename = "package-1-py3-none-any.whl"; wheel.sha256 = "b".repeat(64);
            matching.lockedRequirements.add(wheel);
            matching.lockDigest = PreparationManager.lockDigest(matching.targetFingerprint, matching.lockedRequirements);
            PythonApi.ResolvedLock unrelated = PythonApi.mapper().convertValue(matching, PythonApi.ResolvedLock.class);
            unrelated.targetFingerprint = "c".repeat(64);
            material.requirementsText = "package==2";
            unrelated.definitionFingerprint = PreparationManager.definitionFingerprint(material);
            unrelated.lockDigest = PreparationManager.lockDigest(unrelated.targetFingerprint, unrelated.lockedRequirements);
            exported.locks.add(null); exported.locks.add(unrelated); exported.locks.add(matching);
            PythonApi.ImportRequest request = new PythonApi.ImportRequest();
            request.expectedRegistryEpoch = runtime.registry.epoch(); request.requestId = "import-lock-identity";
            request.bundle = new PythonApi.ExportBundle(); request.bundle.profiles.add(exported);
            new RuntimeApi(runtime).importBundle("actor", request);
            PythonApi.Profile imported = runtime.registry.snapshot().profiles.get(exported.id);
            assertEquals(java.util.Set.of(matching.targetFingerprint), imported.resolvedLocks.keySet());
            assertEquals(matching.definitionFingerprint, imported.resolvedLocks.get(matching.targetFingerprint).definitionFingerprint);
            assertEquals(Requirements.normalize("package==1"), imported.requirementsText);
        } finally { runtime.stop(); }
    }
    @Test void validationAndTestRejectChangedNodeBeforeAnyRuntimeActivity() throws Exception {
        PythonRuntime runtime = runtime(new RegistryStoreTest.Memory(), true);
        try {
            RuntimeApi api = new RuntimeApi(runtime);
            PythonApi.ScriptRequest request = new PythonApi.ScriptRequest();
            request.expectedNodeId = "old-node";
            for (Runnable call : List.<Runnable>of(() -> api.validate(request), () -> api.test(request, null))) {
                ApiFailure failure = assertThrows(ApiFailure.class, call::run);
                assertEquals("NODE_CHANGED", failure.code);
                assertEquals("local", failure.ownerNodeId);
            }
        } finally { runtime.stop(); }
    }
    @Test void foreignNodeJobHandlesConflictForEveryOperationWithoutLocalRecord() throws Exception {
        PythonRuntime runtime = runtime(new RegistryStoreTest.Memory(), false);
        try {
            String handle = UUID.randomUUID() + "~" + Base64.getUrlEncoder().withoutPadding().encodeToString("foreign".getBytes(StandardCharsets.UTF_8));
            for (Runnable call : List.<Runnable>of(() -> runtime.preparation.job(handle), () -> runtime.preparation.log(handle, 0), () -> runtime.preparation.cancel(handle))) {
                ApiFailure failure = assertThrows(ApiFailure.class, call::run);
                assertEquals("JOB_ON_DIFFERENT_NODE", failure.code);
                assertEquals("foreign", failure.ownerNodeId);
            }
            String local = UUID.randomUUID() + "~" + Base64.getUrlEncoder().withoutPadding().encodeToString("local".getBytes(StandardCharsets.UTF_8));
            assertEquals("INVALID_CURSOR", assertThrows(ApiFailure.class, () -> runtime.preparation.log(local, -1)).code);
        } finally { runtime.stop(); }
    }
    @Test void revokedPreparationPermissionIsCheckedBeforeAnyRuntimeProbe() throws Exception {
        PythonRuntime runtime = runtime(new RegistryStoreTest.Memory(), true);
        try {
            assertEquals("NOT_AUTHORIZED", assertThrows(ApiFailure.class,
                    () -> runtime.preparation.prepare(UUID.randomUUID().toString(), runtime.registry.epoch(), 1L, () -> false)).code);
        } finally { runtime.stop(); }
    }
    @Test void unavailableInterpreterFailsTheAsynchronousJobAfterRequestReturns() throws Exception {
        PythonRuntime runtime = runtime(new RegistryStoreTest.Memory(), true);
        try {
            String id = UUID.randomUUID().toString();
            runtime.registry.change(registry -> { registry.profiles.put(id, profile(id, "Missing runtime")); return true; });
            PythonApi.JobView job = runtime.preparation.prepare(id, runtime.registry.epoch(), 1L, () -> true);
            assertNotNull(job.id);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!PreparationManager.TERMINAL.contains(job.state) && System.nanoTime() < deadline) {
                Thread.sleep(10); job = runtime.preparation.job(job.id);
            }
            assertEquals("FAILED", job.state);
            assertEquals("RUNTIME_UNAVAILABLE", job.diagnostics.get(0).code);
        } finally { runtime.stop(); }
    }
    @Test void prepareApiResponseNeverProbesReadinessOnTheRequestThread() throws Exception {
        PythonRuntime runtime = runtime(new RegistryStoreTest.Memory(), true);
        try {
            String id = UUID.randomUUID().toString();
            runtime.registry.change(registry -> { registry.profiles.put(id, profile(id, "Async API")); return true; });
            PythonRuntime observed = org.mockito.Mockito.spy(runtime);
            org.mockito.Mockito.doThrow(new AssertionError("Prepare must not launch a readiness probe on its request thread"))
                    .when(observed).readiness(org.mockito.ArgumentMatchers.any());
            PythonApi.RevisionRequest request = new PythonApi.RevisionRequest();
            request.requestId = "prepare-async"; request.expectedRegistryEpoch = runtime.registry.epoch(); request.expectedRevision = 1L;
            PythonApi.MutationResult result = new RuntimeApi(observed).prepare(id, request, () -> true);
            assertEquals(id, result.profile.id);
            assertEquals("local", result.profile.readiness.nodeId);
            assertEquals(result.job.id, result.profile.readiness.jobId);
            assertNotEquals("ready", result.profile.readiness.state);
            org.mockito.Mockito.verify(observed, org.mockito.Mockito.never()).readiness(org.mockito.ArgumentMatchers.any());
        } finally { runtime.stop(); }
    }
    @Test void prepareReturnsTheAcceptedHandleWithoutAnotherRegistryRead() throws Exception {
        RegistryStoreTest.Memory persistence = new RegistryStoreTest.Memory();
        PythonRuntime runtime = runtime(persistence, true);
        try {
            String id = UUID.randomUUID().toString();
            runtime.registry.change(registry -> { registry.profiles.put(id, profile(id, "Accepted handle")); return true; });
            PythonRuntime observed = org.mockito.Mockito.spy(runtime);
            PreparationManager preparation = org.mockito.Mockito.spy(runtime.preparation);
            org.mockito.Mockito.doAnswer(call -> {
                Object accepted = call.callRealMethod();
                persistence.failRead = true; // Database becomes unavailable immediately after acceptance.
                return accepted;
            }).when(preparation).prepare(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any());
            var field = PythonRuntime.class.getDeclaredField("preparation");
            field.setAccessible(true); field.set(observed, preparation);
            PythonApi.RevisionRequest request = new PythonApi.RevisionRequest();
            request.requestId = "accepted-handle"; request.expectedRegistryEpoch = runtime.registry.epoch(); request.expectedRevision = 1L;
            PythonApi.MutationResult result = new RuntimeApi(observed).prepare(id, request, () -> true);
            assertNotNull(result.job.id);
            assertEquals(id, result.profile.id);
            assertEquals(request.expectedRegistryEpoch, result.registryEpoch);
            assertEquals(result.job.id, result.profile.readiness.jobId);
        } finally { persistence.failRead = false; runtime.stop(); }
    }
    @Test void losingLockCasBuildsAgainstTheCommittedWinner() throws Exception {
        RegistryStoreTest.Memory persistence = new RegistryStoreTest.Memory();
        PythonRuntime runtime = runtime(persistence, false);
        try {
            String id = UUID.randomUUID().toString();
            PythonApi.Profile profile = profile(id, "Competing nodes");
            runtime.registry.change(registry -> { registry.profiles.put(id, profile); return true; });
            RegistryStore other = new RegistryStore(persistence); other.load(null);
            PythonApi.ResolvedLock candidate = new PythonApi.ResolvedLock();
            candidate.targetFingerprint = "a".repeat(64); candidate.definitionFingerprint = profile.definitionFingerprint;
            candidate.packageSourcePolicy = runtime.config.sources.get(profile.packageSourceId).policyIdentity();
            candidate.lockDigest = PreparationManager.lockDigest(candidate.targetFingerprint, candidate.lockedRequirements);
            PythonApi.ResolvedLock winner = new PythonApi.ResolvedLock();
            winner.targetFingerprint = candidate.targetFingerprint; winner.definitionFingerprint = candidate.definitionFingerprint;
            winner.packageSourcePolicy = candidate.packageSourcePolicy;
            PythonApi.LockedRequirement wheel = new PythonApi.LockedRequirement();
            wheel.name = "package"; wheel.version = "1"; wheel.filename = "package-1-py3-none-any.whl"; wheel.sha256 = "b".repeat(64);
            winner.lockedRequirements.add(wheel); winner.lockDigest = PreparationManager.lockDigest(winner.targetFingerprint, winner.lockedRequirements);
            PreparationManager.Job job = runtime.preparation.new Job("race", () -> true);
            job.view.profileId = id; job.view.registryEpoch = runtime.registry.epoch(); job.view.definitionFingerprint = profile.definitionFingerprint;
            persistence.beforeWrite = () -> other.change(registry -> { registry.profiles.get(id).resolvedLocks.put(winner.targetFingerprint, winner); return true; });
            var method = PreparationManager.class.getDeclaredMethod("commitLock", PreparationManager.Job.class, PythonApi.ResolvedLock.class);
            method.setAccessible(true);
            PythonApi.ResolvedLock selected = (PythonApi.ResolvedLock) method.invoke(runtime.preparation, job, candidate);
            assertEquals(winner.lockDigest, selected.lockDigest);
            assertNotEquals(candidate.lockDigest, selected.lockDigest);
        } finally { runtime.stop(); }
    }
}
