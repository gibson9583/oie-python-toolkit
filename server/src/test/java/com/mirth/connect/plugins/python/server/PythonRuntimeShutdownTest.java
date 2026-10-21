/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PythonRuntimeShutdownTest {
    @TempDir Path owned;
    private static Object replace(PythonRuntime runtime, String name, Object replacement) throws Exception {
        var field = PythonRuntime.class.getDeclaredField(name); field.setAccessible(true);
        Object original = field.get(runtime); field.set(runtime, replacement); return original;
    }
    private PythonRuntime runtime() throws Exception {
        RegistryStore registry = new RegistryStore(new RegistryStoreTest.Memory()); registry.load(null);
        return new PythonRuntime(new OperatorConfig(key -> null, key -> null, owned.toString(), "owned-shutdown"), registry, mock(EngineAccess.class));
    }

    @Test void allOwnerCleanupRunsAfterAssertionAndLinkageErrorsAndOriginalFailureSurvives() throws Exception {
        PythonRuntime runtime = runtime(); Deployments deployments = mock(Deployments.class);
        PreparationManager preparation = mock(PreparationManager.class); WorkerPools pools = mock(WorkerPools.class);
        ScheduledExecutorService housekeeping = mock(ScheduledExecutorService.class);
        Object realDeployments = replace(runtime, "deployments", deployments), realPreparation = replace(runtime, "preparation", preparation);
        Object realPools = replace(runtime, "pools", pools), realHousekeeping = replace(runtime, "housekeeping", housekeeping);
        AssertionError original = new AssertionError("deployment cleanup"); LinkageError preparationFailure = new LinkageError("preparation cleanup");
        IllegalStateException poolsFailure = new IllegalStateException("pool cleanup"), executorFailure = new IllegalStateException("scheduler cleanup");
        doThrow(original).when(deployments).shutdown(); doThrow(preparationFailure).when(preparation).shutdown();
        doThrow(poolsFailure).when(pools).shutdown(); doThrow(executorFailure).when(housekeeping).shutdown();
        try {
            runtime.start(); assertSame(runtime, PythonRuntime.current());
            assertSame(original, assertThrows(AssertionError.class, runtime::stop));
            assertArrayEquals(new Throwable[]{preparationFailure, poolsFailure, executorFailure}, original.getSuppressed());
            assertNull(PythonRuntime.current());
            var order = inOrder(deployments, preparation, pools, housekeeping);
            order.verify(deployments).shutdown(); order.verify(preparation).shutdown(); order.verify(pools).shutdown();
            order.verify(housekeeping).shutdown(); order.verify(housekeeping).awaitTermination(anyLong(), eq(TimeUnit.MILLISECONDS));
            order.verify(housekeeping).shutdownNow();
        } finally {
            replace(runtime, "deployments", realDeployments); replace(runtime, "preparation", realPreparation);
            replace(runtime, "pools", realPools); replace(runtime, "housekeeping", realHousekeeping); runtime.stop();
        }
    }

    @Test void interruptedWaitPreservesFlagAndStillAttemptsForcedSchedulerShutdown() throws Exception {
        PythonRuntime runtime = runtime(); ScheduledExecutorService housekeeping = mock(ScheduledExecutorService.class);
        Object real = replace(runtime, "housekeeping", housekeeping);
        InterruptedException interruption = new InterruptedException("owned stop interrupted");
        doThrow(interruption).when(housekeeping).awaitTermination(anyLong(), eq(TimeUnit.MILLISECONDS));
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, runtime::stop);
            assertSame(interruption, failure.getCause()); assertTrue(Thread.currentThread().isInterrupted());
            verify(housekeeping).shutdown(); verify(housekeeping).shutdownNow();
        } finally { Thread.interrupted(); replace(runtime, "housekeeping", real); runtime.stop(); }
    }
}
