/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirth.connect.plugins.python.PythonApi;
import com.mirth.connect.plugins.python.PythonContract;

/** Registry replacement must not borrow preparation state from another epoch/revision. */
class BuiltInRestoreJobsTest {
    @TempDir Path owned;

    @Test void sameDefinitionRestoreIgnoresOldActiveJobAndQueuesNewEpoch() throws Exception {
        verifyObsoleteJob(true, false);
    }

    @Test void sameDefinitionRevisionChangeIgnoresOldActiveJobAndQueuesNewRevision() throws Exception {
        verifyObsoleteJob(false, false);
    }

    @Test void sameDefinitionRestoreIgnoresOldFailedJobAndQueuesNewEpoch() throws Exception {
        verifyObsoleteJob(true, true);
    }

    @Test void sameDefinitionRevisionChangeIgnoresOldFailedJobAndQueuesNewRevision() throws Exception {
        verifyObsoleteJob(false, true);
    }

    private void verifyObsoleteJob(boolean restore, boolean oldFailed) throws Exception {
        try (Fixture fixture = new Fixture(oldFailed)) {
            PythonApi.Registry before = fixture.runtime.registry.snapshot();
            PythonApi.Profile original = before.profiles.get(PythonContract.DEFAULT_PROFILE_ID);
            PythonApi.JobView old = fixture.manager.prepare(original.id, before.registryEpoch, original.revision, () -> true);
            assertTrue(fixture.oldProbeEntered.await(5, TimeUnit.SECONDS), "Owned old preparation did not enter its mock probe");
            if (oldFailed) {
                old = awaitTerminal(fixture.manager, old.id);
                assertEquals("FAILED", old.state);
                assertEquals("failed", fixture.runtime.readiness(original, before.registryEpoch).state);
            } else {
                assertEquals("preparing", fixture.runtime.readiness(original, before.registryEpoch).state);
                assertTrue(fixture.manager.hasActiveJob(original.id, before.registryEpoch, original.revision));
            }

            if (restore) {
                String replacement = fixture.runtime.registry.prepareReplacement(fixture.runtime.registry.storedJson());
                synchronized (fixture.persistence) { fixture.persistence.json = replacement; }
            } else {
                fixture.runtime.registry.change(next -> {
                    next.profiles.get(original.id).revision++;
                    return Boolean.TRUE;
                });
            }
            PythonApi.Registry after = fixture.runtime.registry.snapshot();
            PythonApi.Profile current = after.profiles.get(original.id);
            assertEquals(original.definitionFingerprint, current.definitionFingerprint);
            if (restore) assertNotEquals(before.registryEpoch, after.registryEpoch);
            else assertEquals(original.revision + 1, current.revision);

            assertNotNull(fixture.manager.latestFor(original.id, original.definitionFingerprint), "Old job history remains inspectable");
            assertNull(fixture.manager.latestFor(current.id, current.definitionFingerprint, after.registryEpoch, current.revision));
            assertFalse(fixture.manager.hasActiveJob(current.id, after.registryEpoch, current.revision));
            PythonApi.LocalReadiness unprepared = fixture.runtime.readiness(current, after.registryEpoch);
            assertEquals("not-prepared", unprepared.state);
            assertNull(unprepared.jobId, "A replaced definition must not expose the obsolete job handle");

            fixture.runtime.builtIn.tick();
            PythonApi.JobView accepted = fixture.manager.latestFor(current.id, current.definitionFingerprint,
                    after.registryEpoch, current.revision);
            assertNotNull(accepted, "Current default must receive its own automatic preparation");
            assertNotEquals(old.id, accepted.id);
            assertEquals(after.registryEpoch, accepted.registryEpoch);
            assertEquals(current.revision, accepted.profileRevision);
            assertTrue(fixture.manager.hasActiveJob(current.id, after.registryEpoch, current.revision));
            PythonApi.LocalReadiness preparing = fixture.runtime.readiness(current, after.registryEpoch);
            assertEquals("preparing", preparing.state);
            assertEquals(accepted.id, preparing.jobId);
            fixture.runtime.builtIn.tick();
            assertEquals(accepted.id, fixture.manager.latestFor(current.id, current.definitionFingerprint,
                    after.registryEpoch, current.revision).id, "A repeated tick must join current work");
            verifyNoInteractions(fixture.engine);
        }
    }

    private static PythonApi.JobView awaitTerminal(PreparationManager manager, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        PythonApi.JobView job;
        do {
            job = manager.job(id);
            if (PreparationManager.TERMINAL.contains(job.state)) return job;
            TimeUnit.MILLISECONDS.sleep(5);
        } while (System.nanoTime() < deadline);
        fail("Owned mock preparation did not reach a terminal state");
        return null;
    }

    private static Object replace(PythonRuntime runtime, String name, Object replacement) throws Exception {
        var field = PythonRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        Object original = field.get(runtime);
        field.set(runtime, replacement);
        return original;
    }

    private final class Fixture implements AutoCloseable {
        final RegistryStoreTest.Memory persistence = new RegistryStoreTest.Memory();
        final EngineAccess engine = mock(EngineAccess.class);
        final CountDownLatch oldProbeEntered = new CountDownLatch(1);
        final CountDownLatch releaseOld = new CountDownLatch(1);
        final CountDownLatch releaseCurrent = new CountDownLatch(1);
        final PythonRuntime runtime;
        final PreparationManager manager;
        final ThreadPoolExecutor preparationExecutor;

        Fixture(boolean oldFailed) throws Exception {
            RegistryStore registry = new RegistryStore(persistence);
            registry.load(null);
            OperatorConfig config = new OperatorConfig(key -> null, key -> null, owned.toString(), "owned-restore-jobs");
            runtime = new PythonRuntime(config, registry, engine);
            try {
                BuiltInEnvironment.seed(registry, config);
                Interpreters interpreters = mock(Interpreters.class);
                Interpreters.Target target = new Interpreters.Target("python3", owned.resolve("never-executed-python"),
                        "3.14.6", "a".repeat(64), Map.of("executable", owned.resolve("never-executed-python").toString()));
                when(interpreters.probe("python3")).thenReturn(target);
                AtomicInteger probes = new AtomicInteger();
                when(interpreters.probeForPreparation(eq("python3"), anyLong(), any())).thenAnswer(invocation -> {
                    if (probes.getAndIncrement() == 0) {
                        oldProbeEntered.countDown();
                        if (!oldFailed && !releaseOld.await(5, TimeUnit.SECONDS)) throw new AssertionError("Old mock probe was not released");
                    } else if (!releaseCurrent.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Current mock probe was not released");
                    }
                    throw new Interpreters.Unavailable("Owned fixture: interpreter unavailable; no process was started");
                });
                runtime.preparation.shutdown();
                manager = new PreparationManager(config, registry, interpreters, runtime.artifacts,
                        runtime.sdk, runtime.baseEnv, () -> {});
                replace(runtime, "interpreters", interpreters);
                replace(runtime, "preparation", manager);
                var field = PreparationManager.class.getDeclaredField("executor");
                field.setAccessible(true);
                preparationExecutor = (ThreadPoolExecutor) field.get(manager);
            } catch (Exception | Error failure) {
                releaseOld.countDown(); releaseCurrent.countDown();
                try { runtime.stop(); } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        @Override public void close() throws Exception {
            releaseOld.countDown(); releaseCurrent.countDown();
            try { runtime.stop(); }
            finally {
                preparationExecutor.shutdownNow();
                assertTrue(preparationExecutor.awaitTermination(5, TimeUnit.SECONDS), "Owned preparation threads must exit before fixture cleanup");
            }
        }
    }
}
