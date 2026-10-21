/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import com.mirth.connect.plugins.python.PythonContract;

class DeploymentFailureTest {
    @Test void shutdownAttemptsEveryBindingAfterRetirementThrowsAndRetainsUnconfirmedOwnership() throws Exception {
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            PythonRuntime controlled = mock(PythonRuntime.class);
            WorkerPools pools = mock(WorkerPools.class);
            for (var entry : java.util.Map.of("config", fixture.runtime.config, "pools", pools).entrySet()) {
                var field = PythonRuntime.class.getDeclaredField(entry.getKey());
                field.setAccessible(true); field.set(controlled, entry.getValue());
            }
            Deployments deployments = new Deployments(controlled);
            var first = new Deployments.Binding("first", "First", 1, java.util.Map.of());
            var second = new Deployments.Binding("second", "Second", 1, java.util.Map.of());
            var successful = new Deployments.Binding("successful", "Successful", 1, java.util.Map.of());
            var field = Deployments.class.getDeclaredField("byAttempt"); field.setAccessible(true);
            @SuppressWarnings("unchecked") var attempts = (java.util.Map<String, Deployments.Binding>) field.get(deployments);
            for (var binding : List.of(first, second, successful)) attempts.put(binding.attemptId, binding);
            AssertionError firstFailure = new AssertionError("first worker ownership unknown");
            IllegalStateException secondFailure = new IllegalStateException("second worker ownership unknown");
            doThrow(firstFailure).when(pools).retire(first.attemptId);
            doThrow(secondFailure).when(pools).retire(second.attemptId);
            when(pools.retirementComplete(successful.attemptId)).thenReturn(true);
            Throwable thrown = assertThrows(Throwable.class, deployments::shutdown);
            assertTrue(thrown == firstFailure || thrown == secondFailure);
            assertEquals(1, thrown.getSuppressed().length);
            assertTrue(thrown.getSuppressed()[0] == (thrown == firstFailure ? secondFailure : firstFailure));
            verify(pools).retire(first.attemptId); verify(pools).retire(second.attemptId);
            verify(pools, times(2)).retire(successful.attemptId);
            assertEquals(Deployments.State.RETIRING, first.state());
            assertEquals(Deployments.State.RETIRING, second.state());
            assertEquals(Deployments.State.RETIRED, successful.state());
            assertEquals(java.util.Set.of(first.attemptId, second.attemptId), attempts.keySet());
        }
    }
    @Test void everyUntransferredLeaseIsReleasedOnAssertionAndLinkageFailures() throws Exception {
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            PythonRuntime controlled = spy(fixture.runtime);
            Deployments deployments = new Deployments(controlled);
            for (Error failure : List.of(new AssertionError("validator failed"), new LinkageError("runtime linkage failed"))) {
                doThrow(failure).when(controlled).compileOnly(any(Path.class), anyList());
                var attempt = PythonRuntimeIntegrationTest.deployment(UUID.randomUUID().toString(), fixture.profileId, "fault");
                assertSame(failure, assertThrows(Error.class, () -> deployments.prepare(attempt.model())));
                assertEquals(0, fixture.runtime.artifacts.activeLeases());
                assertTrue(deployments.bindings().isEmpty());
                deployments.retire(attempt.channel(), "failed");
                assertEquals(0, fixture.runtime.artifacts.activeLeases());
            }
        }
    }
    @Test void concurrentAdmissionCloseCannotDrainAnotherActiveCall() throws Exception {
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            var attempt = PythonRuntimeIntegrationTest.deployment(UUID.randomUUID().toString(), fixture.profileId, "close");
            fixture.runtime.deployments.prepare(attempt.model());
            when(fixture.runtime.engine.deployedChannel(attempt.model().getId())).thenReturn(attempt.channel());
            fixture.runtime.deployments.activate(attempt.channel());
            var first = fixture.runtime.deployments.admit(attempt.model().getId(), 1, PythonContract.ROLE_WRITER,
                    PythonContract.specDigest(attempt.writer()), attempt.channel());
            var second = fixture.runtime.deployments.admit(attempt.model().getId(), 1, PythonContract.ROLE_WRITER,
                    PythonContract.specDigest(attempt.writer()), attempt.channel());
            CountDownLatch start = new CountDownLatch(1);
            var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
            try {
                var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> { start.await(); first.close(); return null; }));
                start.countDown();
                for (var future : futures) future.get(5, TimeUnit.SECONDS);
                assertFalse(first.binding.awaitDrained(1));
                second.close();
                assertTrue(first.binding.awaitDrained(100));
            } finally { start.countDown(); first.close(); second.close(); executor.shutdownNow(); }
        }
    }
}
