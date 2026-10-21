/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerPoolsTest {
    @TempDir Path temp;
    private WorkerPools pools(int queued) {
        Map<String, String> settings = Map.of(OperatorConfig.PROPERTY_PREFIX + "maxWorkers", "1", OperatorConfig.PROPERTY_PREFIX + "maxWorkersPerChannel", "1",
                OperatorConfig.PROPERTY_PREFIX + "maxQueuedAcquisitions", Integer.toString(queued), OperatorConfig.PROPERTY_PREFIX + "acquireTimeoutMillis", "100");
        return new WorkerPools(new OperatorConfig(name -> null, settings::get, temp.toString(), "test"));
    }
    private static WorkerPools.Key key(String attempt, String channel) {
        return new WorkerPools.Key(attempt, channel, 1, "writer", "abc", "artifact");
    }
    private static Worker healthy() {
        Worker worker = mock(Worker.class);
        when(worker.isAlive()).thenReturn(true);
        when(worker.terminationConfirmed()).thenReturn(true);
        return worker;
    }
    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(5); }

    @Test void startupCannotPublishAWorkerAfterItsAttemptRetires() throws Exception {
        WorkerPools pools = pools(1);
        Worker worker = healthy();
        CountDownLatch starting = new CountDownLatch(1), finish = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> pools.acquire(key("old", "channel"), (key, deadline) -> {
                starting.countDown();
                assertTrue(finish.await(5, TimeUnit.SECONDS));
                return worker;
            }, deadline()));
            assertTrue(starting.await(5, TimeUnit.SECONDS));
            pools.retire("old");
            finish.countDown();
            assertInstanceOf(IllegalStateException.class, assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS)).getCause());
            verify(worker).terminate("deployment retired during worker startup");
            assertEquals(0L, pools.metrics().get("workers"));
        } finally { finish.countDown(); executor.shutdownNow(); pools.shutdown(); }
    }

    @Test void shutdownDuringStartupStopsCreatedWorkerAndRejectsFutureAcquisitions() throws Exception {
        WorkerPools pools = pools(1);
        Worker worker = healthy();
        CountDownLatch starting = new CountDownLatch(1), finish = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> pools.acquire(key("a", "channel"), (key, deadline) -> {
                starting.countDown(); finish.await(); return worker;
            }, deadline()));
            assertTrue(starting.await(5, TimeUnit.SECONDS));
            pools.shutdown(); finish.countDown();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> pools.acquire(key("b", "channel"), (key, deadline) -> healthy(), deadline()));
            assertEquals(0L, pools.metrics().get("workers"));
        } finally { finish.countDown(); executor.shutdownNow(); pools.shutdown(); }
    }

    @Test void failedAndNullStartupReleaseReservedCapacity() throws Exception {
        WorkerPools pools = pools(0);
        assertThrows(java.io.IOException.class, () -> pools.acquire(key("a", "channel"), (key, deadline) -> { throw new java.io.IOException("failed"); }, deadline()));
        assertThrows(IllegalStateException.class, () -> pools.acquire(key("a", "channel"), (key, deadline) -> null, deadline()));
        assertEquals(0L, pools.metrics().get("workers"));
        var lease = pools.acquire(key("a", "channel"), (key, deadline) -> healthy(), deadline());
        lease.release(false);
        assertEquals(0L, pools.metrics().get("workers"));
    }

    @Test void saturationHasBoundedWaitAndReleaseIsIdempotent() throws Exception {
        WorkerPools pools = pools(1);
        Worker worker = healthy();
        var first = pools.acquire(key("a", "channel"), (key, deadline) -> worker, deadline());
        long started = System.nanoTime();
        assertThrows(WorkerPools.Saturated.class, () -> pools.acquire(key("b", "other"), (key, deadline) -> { fail("full pool must not start"); return null; }, deadline()));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000);
        assertEquals(0L, pools.metrics().get("waitingCalls"));
        first.release(true); first.release(true);
        var reused = pools.acquire(key("a", "channel"), (key, deadline) -> { fail("reuse healthy idle"); return null; }, deadline());
        assertSame(worker, reused.worker);
        reused.release(true); pools.shutdown();
        assertEquals(0L, pools.metrics().get("workers"));
    }

    @Test void uncertainWorkerExitKeepsCapacityAndArtifactRetirementBlocked() throws Exception {
        WorkerPools pools = pools(0);
        Worker worker = healthy();
        when(worker.terminationConfirmed()).thenReturn(false);
        var lease = pools.acquire(key("a", "channel"), (key, deadline) -> worker, deadline());
        lease.release(false);
        pools.retire("a");
        assertFalse(pools.retirementComplete("a"));
        assertEquals(1L, pools.metrics().get("uncertainWorkerOwnership"));
        assertThrows(WorkerPools.Saturated.class, () -> pools.acquire(key("b", "other"), (key, deadline) -> healthy(), deadline()));
        when(worker.terminationConfirmed()).thenReturn(true);
        assertTrue(pools.retirementComplete("a"));
        assertEquals(0L, pools.metrics().get("workers"));
    }

    @Test void defaultChannelLimitKeepsOneWorkerForEachPythonScript() throws Exception {
        OperatorConfig config = new OperatorConfig(name -> null, name -> null, temp.toString(), "defaults");
        assertEquals(config.maxWorkers, config.maxWorkersPerChannel);
        WorkerPools pools = new WorkerPools(config);
        java.util.concurrent.atomic.AtomicInteger started = new java.util.concurrent.atomic.AtomicInteger();
        for (int message = 0; message < 5; message++) {
            for (String step : java.util.List.of("first", "second", "third")) {
                pools.acquire(new WorkerPools.Key("attempt", "channel", 0, "source-transformer", step, "artifact"),
                        (key, deadline) -> { started.incrementAndGet(); return healthy(); }, deadline()).release(true);
            }
        }
        assertEquals(3, started.get());
        pools.shutdown();
    }

    @Test void expiredDeadlineNeverReusesIdleWorker() throws Exception {
        WorkerPools pools = pools(0);
        var lease = pools.acquire(key("a", "channel"), (key, deadline) -> healthy(), deadline());
        lease.release(true);
        assertThrows(WorkerPools.Saturated.class, () -> pools.acquire(key("a", "channel"), (key, deadline) -> healthy(), System.nanoTime() - 1));
        pools.shutdown();
    }

    private WorkerPools threeWorkers() {
        Map<String, String> settings = Map.of(OperatorConfig.PROPERTY_PREFIX + "maxWorkers", "3", OperatorConfig.PROPERTY_PREFIX + "maxWorkersPerChannel", "3");
        return new WorkerPools(new OperatorConfig(name -> null, settings::get, temp.toString(), "owned-cleanup"));
    }
    private static void checkFailures(Throwable failure, Throwable a, Throwable b) {
        assertTrue(failure == a || failure == b);
        assertArrayEquals(new Throwable[]{failure == a ? b : a}, failure.getSuppressed());
    }

    @Test void shutdownAttemptsEveryIdleAndBusyWorkerAfterErrorsAndRetainsUnconfirmedOwners() throws Exception {
        WorkerPools pools = threeWorkers(); Worker a = healthy(), b = healthy(), c = healthy();
        var first = pools.acquire(key("attempt", "channel"), (key, deadline) -> a, deadline());
        var second = pools.acquire(key("attempt", "channel"), (key, deadline) -> b, deadline());
        var busy = pools.acquire(key("attempt", "channel"), (key, deadline) -> c, deadline());
        first.release(true); second.release(true);
        AssertionError firstFailure = new AssertionError("owned worker failed"); LinkageError secondFailure = new LinkageError("owned worker failed");
        doThrow(firstFailure).when(a).terminate("shutdown"); doThrow(secondFailure).when(b).terminate("shutdown");
        checkFailures(assertThrows(Error.class, pools::shutdown), firstFailure, secondFailure);
        verify(a).terminate("shutdown"); verify(b).terminate("shutdown"); verify(c).terminate("shutdown");
        assertFalse(pools.retirementComplete("attempt")); assertEquals(3L, pools.metrics().get("workers"));
        assertThrows(IllegalStateException.class, () -> pools.acquire(key("new", "channel"), (key, deadline) -> healthy(), deadline()));
        busy.release(false); assertEquals(2L, pools.metrics().get("workers"));
    }

    @Test void retirementAttemptsEveryWorkerAfterErrorsAndCannotReleaseFailedOwnersArtifacts() throws Exception {
        WorkerPools pools = threeWorkers(); Worker a = healthy(), b = healthy(), c = healthy();
        var first = pools.acquire(key("attempt", "channel"), (key, deadline) -> a, deadline());
        var second = pools.acquire(key("attempt", "channel"), (key, deadline) -> b, deadline());
        var third = pools.acquire(key("attempt", "channel"), (key, deadline) -> c, deadline());
        first.release(true); second.release(true); third.release(true);
        AssertionError firstFailure = new AssertionError("owned worker failed"); LinkageError secondFailure = new LinkageError("owned worker failed");
        doThrow(firstFailure).when(a).terminate("deployment retired"); doThrow(secondFailure).when(b).terminate("deployment retired");
        checkFailures(assertThrows(Error.class, () -> pools.retire("attempt")), firstFailure, secondFailure);
        verify(a).terminate("deployment retired"); verify(b).terminate("deployment retired"); verify(c).terminate("deployment retired");
        assertFalse(pools.retirementComplete("attempt")); assertEquals(2L, pools.metrics().get("workers"));
        assertThrows(IllegalStateException.class, () -> pools.acquire(key("attempt", "channel"), (key, deadline) -> healthy(), deadline()));
        pools.retire("attempt"); assertFalse(pools.retirementComplete("attempt"));
    }
}
