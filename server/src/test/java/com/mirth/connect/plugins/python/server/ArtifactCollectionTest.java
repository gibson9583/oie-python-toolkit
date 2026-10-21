/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirth.connect.plugins.python.PythonApi;
import com.mirth.connect.plugins.python.PythonContract;

class ArtifactCollectionTest {
    @TempDir Path temp;

    @Test void repeatedPublicationRejectsOldSelectionsWhileFreshCollectionHonorsLeases() throws Exception {
        ArtifactStore store = new ArtifactStore(temp);
        for (int i = 0; i < 20; i++) {
            String digest = String.format("%064x", i);
            var buildLock = store.buildLock(digest);
            long generation = store.collectionGeneration();
            buildLock.lock();
            Thread collector = null;
            try {
                var manifest = manifest(store, digest);
                collector = new Thread(() -> store.collect(Set.of(), generation));
                collector.start();
                store.publish(manifest);
                store.invalidateCollection();
            } finally { buildLock.unlock(); }
            join(collector);
            assertNotNull(store.ready(digest));
            assertTrue(store.collect(Set.of(), generation).isEmpty(), "An older selection cannot delete a new publication");
            var lease = store.acquire(digest);
            assertNotNull(lease);
            assertTrue(store.collect(Set.of(), store.collectionGeneration()).isEmpty());
            lease.release();
            assertTrue(store.collect(Set.of(digest), store.collectionGeneration()).isEmpty());
            assertEquals(List.of(digest), store.collect(Set.of(), store.collectionGeneration()));
        }
    }

    @Test void preparationSurvivesACollectorQueuedBeforeItsLockCommit() throws Exception {
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            settle(fixture);
            var previous = fixture.api.export(Set.of(fixture.profileId)).profiles.get(0);
            archive(fixture);
            var request = new PythonApi.ProfileRequest();
            request.requestId = UUID.randomUUID().toString(); request.expectedRegistryEpoch = fixture.registry.epoch();
            request.name = "Prepared during collection"; request.runtimeId = previous.runtimeId;
            request.packageSourceId = previous.packageSourceId; request.requirementsText = previous.requirementsText;
            String id = fixture.api.create("test", request).profile.id;
            ReentrantLock gate = gate(fixture.runtime.artifacts);
            Thread collector = new Thread(fixture.runtime::collectGarbage);
            PythonApi.JobView job;
            gate.lock();
            try {
                collector.start();
                await(() -> gate.hasQueuedThread(collector));
                job = fixture.runtime.preparation.prepare(id, fixture.registry.epoch(), 1L, () -> true);
                // The old implementation reached READY and queued its own GC;
                // the fixed one waits before committing the new lock selection.
                await(() -> gate.getQueueLength() >= 2);
            } finally { gate.unlock(); }
            join(collector);
            String jobId = job.id;
            await(() -> PreparationManager.TERMINAL.contains(fixture.runtime.preparation.job(jobId).state));
            await(() -> !fixture.runtime.preparation.hasActiveJob(id));
            job = fixture.runtime.preparation.job(jobId);
            assertEquals("READY", job.state, job.message);
            assertNotNull(fixture.runtime.artifacts.ready(job.artifactDigest));
            assertEquals("ready", fixture.api.get(id).readiness.state);
            Files.writeString(fixture.evidence.resolve("gc-race-job.json"), PythonApi.mapper().writeValueAsString(job));
            Files.writeString(fixture.evidence.resolve("gc-race.log"), String.join("\n", fixture.runtime.preparation.log(jobId, 0).lines));
        }
    }

    @Test void importAndAdoptedRestoreInvalidateSelectionsEvenWhenTheyReuseExistingFiles() throws Exception {
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            settle(fixture);
            var store = fixture.runtime.artifacts;
            var bundle = fixture.api.export(Set.of(fixture.profileId));
            String digest = fixture.api.get(fixture.profileId).readiness.artifactDigest;
            archive(fixture);
            long generation = store.collectionGeneration();
            var request = new PythonApi.ImportRequest();
            request.requestId = "reuse-existing-artifact"; request.expectedRegistryEpoch = fixture.registry.epoch();
            request.bundle = bundle; request.mappings.put(fixture.profileId, fixture.profileId);
            request.expectedRevisions.put(fixture.profileId, 2L);
            fixture.api.importBundle("test", request);
            assertTrue(store.collect(Set.of(), generation).isEmpty());
            assertEquals("ready", fixture.api.get(fixture.profileId).readiness.state);

            generation = store.collectionGeneration();
            String replacement = fixture.registry.prepareReplacement(fixture.registry.storedJson());
            assertEquals(RegistryStore.WriteOutcome.COMMITTED, fixture.persistence.write(fixture.registry.storedJson(), replacement, "restore"));
            fixture.registry.adoptCommitted(replacement);
            assertTrue(store.collect(Set.of(), generation).isEmpty());
            assertNotNull(store.ready(digest));

            generation = store.collectionGeneration();
            assertEquals(RegistryStore.WriteOutcome.COMMITTED,
                    fixture.persistence.write(replacement, fixture.registry.prepareReplacement(replacement), "external-write"));
            fixture.registry.snapshot(); // Reconcile another node's observed write.
            assertTrue(store.collect(Set.of(), generation).isEmpty());
            assertEquals("ready", fixture.api.get(fixture.profileId).readiness.state);

            String obsolete = "f".repeat(64);
            store.publish(manifest(store, obsolete));
            fixture.runtime.collectGarbage();
            assertNull(store.ready(obsolete), "Current collection still removes unused files");
            assertNotNull(store.ready(digest));
        }
    }

    private static void archive(PythonRuntimeIntegrationTest.Fixture fixture) {
        var request = new PythonApi.RevisionRequest();
        request.requestId = UUID.randomUUID().toString(); request.expectedRegistryEpoch = fixture.registry.epoch(); request.expectedRevision = 1L;
        fixture.api.archive("test", fixture.profileId, request);
    }

    private static void settle(PythonRuntimeIntegrationTest.Fixture fixture) throws InterruptedException {
        await(() -> "ready".equals(fixture.api.get(PythonContract.DEFAULT_PROFILE_ID).readiness.state)
                && !fixture.runtime.preparation.hasActiveJob(PythonContract.DEFAULT_PROFILE_ID)
                && !fixture.runtime.preparation.hasActiveJob(fixture.profileId));
    }

    private static ArtifactStore.Manifest manifest(ArtifactStore store, String digest) throws Exception {
        Path build = store.newBuildDir(digest);
        Path python = ProcessControl.venvPython(build.resolve("venv"));
        Files.createDirectories(python.getParent()); Files.writeString(python, "fixture");
        var manifest = new ArtifactStore.Manifest(); manifest.artifactDigest = digest; manifest.buildId = build.getFileName().toString();
        return manifest;
    }

    private static ReentrantLock gate(ArtifactStore store) throws Exception {
        var field = ArtifactStore.class.getDeclaredField("gate"); field.setAccessible(true);
        return (ReentrantLock) field.get(store);
    }

    private static void await(BooleanSupplier ready) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(ready.getAsBoolean(), "Timed out waiting for the controlled schedule");
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(10000);
        assertFalse(thread.isAlive(), "Artifact collection did not finish");
    }
}
