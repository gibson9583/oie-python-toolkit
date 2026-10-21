/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import com.mirth.connect.donkey.model.event.DeployedStateEventType;
import com.mirth.connect.donkey.server.event.DeployedStateEvent;
import com.mirth.connect.server.channel.ChannelTask;
import com.mirth.connect.server.controllers.*;

/** Runs unchanged installed-stock dispatch/task methods, rather than mirroring their logic. */
class StockLifecycleOrderingTest {
    private DefaultEventController eventController() {
        DefaultEventController events = mock(DefaultEventController.class);
        doCallRealMethod().when(events).addListener(any());
        doCallRealMethod().when(events).removeListener(any());
        doCallRealMethod().when(events).dispatchEvent(any());
        return events;
    }
    private DeployedStateEvent event(String channel, Integer metadataId) {
        return new DeployedStateEvent(channel, "Runtime integration", metadataId, null, DeployedStateEventType.DEPLOYED);
    }

    @Test void stockDispatchActivatesInlineOnlyForChannelEventAndDuplicateIsIdempotent() throws Exception {
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            var attempt = PythonRuntimeIntegrationTest.deployment(UUID.randomUUID().toString(), fixture.profileId, "inline");
            var binding = fixture.runtime.deployments.prepare(attempt.model());
            when(fixture.runtime.engine.deployedChannel(attempt.model().getId())).thenReturn(attempt.channel());
            DefaultEventController events = eventController();
            var observer = new PythonDeploymentObserver(fixture.runtime, events);
            try {
                events.addListener(observer);
                events.dispatchEvent(event(attempt.model().getId(), 0));
                assertEquals(Deployments.State.PREPARED, binding.state());
                assertThrows(Deployments.NotAdmitted.class, () -> fixture.runtime.deployments.requireActive(attempt.channel()));
                events.dispatchEvent(event(attempt.model().getId(), null));
                // Assertion immediately after stock dispatch returns proves the
                // binding did not wait for EventListener's asynchronous consumer.
                assertEquals(Deployments.State.ACTIVE, binding.state());
                events.dispatchEvent(event(attempt.model().getId(), null));
                assertEquals(1, fixture.runtime.deployments.bindings().size());
                assertEquals(0L, fixture.runtime.pools.metrics().get("busyWorkers"));
            } finally { observer.close(); }
        }
    }

    @Test void lookupAndInstallationFailuresCannotEscapeStockDispatcherOrAdmitPython() throws Exception {
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            var attempt = PythonRuntimeIntegrationTest.deployment(UUID.randomUUID().toString(), fixture.profileId, "failure");
            var binding = fixture.runtime.deployments.prepare(attempt.model());
            DefaultEventController events = eventController();
            var observer = new PythonDeploymentObserver(fixture.runtime, events);
            try {
                events.addListener(observer);
                when(fixture.runtime.engine.deployedChannel(attempt.model().getId())).thenThrow(new AssertionError("lookup failed"));
                assertDoesNotThrow(() -> events.dispatchEvent(event(attempt.model().getId(), null)));
                assertEquals(Deployments.State.PREPARED, binding.state());
                doReturn(attempt.channel()).when(fixture.runtime.engine).deployedChannel(attempt.model().getId());
                var executor = attempt.channel().getSourceConnector().getFilterTransformerExecutor();
                when(executor.getFilterTransformer()).thenReturn(mock(com.mirth.connect.donkey.server.channel.components.FilterTransformer.class));
                doThrow(new AssertionError("setter failed")).when(executor).setFilterTransformer(any());
                assertDoesNotThrow(() -> events.dispatchEvent(event(attempt.model().getId(), null)));
                assertEquals(Deployments.State.PREPARED, binding.state());
                assertThrows(Deployments.NotAdmitted.class, () -> fixture.runtime.deployments.requireActive(attempt.channel()));
                fixture.runtime.deployments.completedAttempt(binding);
                assertEquals(Deployments.State.RETIRED, binding.state());
            } finally { observer.close(); }
        }
    }

    @Test void separatelyShutdownListenerIsStillDetachedAndClosedPutCannotActivate() throws Exception {
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            var attempt = PythonRuntimeIntegrationTest.deployment(UUID.randomUUID().toString(), fixture.profileId, "closed");
            var binding = fixture.runtime.deployments.prepare(attempt.model());
            when(fixture.runtime.engine.deployedChannel(attempt.model().getId())).thenReturn(attempt.channel());
            DefaultEventController events = eventController();
            var observer = new PythonDeploymentObserver(fixture.runtime, events);
            var queue = observer.getQueue();
            try {
                events.addListener(observer); observer.shutdown(); observer.close(); observer.close();
                verify(events).removeListener(observer);
                queue.put(event(attempt.model().getId(), null));
                assertEquals(Deployments.State.PREPARED, binding.state());
                fixture.runtime.deployments.completedAttempt(binding);
            } finally { observer.close(); }
        }
    }

    @Test void priorCleanupFailurePreservesNewActiveAdmissionAndRetainsOldOwnership() throws Exception {
        String id = UUID.randomUUID().toString();
        var oldAttempt = PythonRuntimeIntegrationTest.deployment(id, "unused-profile", "old");
        var newAttempt = PythonRuntimeIntegrationTest.deployment(id, "unused-profile", "new");
        PythonRuntime runtime = mock(PythonRuntime.class);
        EngineAccess engine = mock(EngineAccess.class); WorkerPools pools = mock(WorkerPools.class);
        Deployments deployments = new Deployments(runtime);
        for (var entry : java.util.Map.of("engine", engine, "pools", pools, "deployments", deployments).entrySet()) {
            var field = PythonRuntime.class.getDeclaredField(entry.getKey()); field.setAccessible(true); field.set(runtime, entry.getValue());
        }
        ArtifactStore.Lease oldLease = mock(ArtifactStore.Lease.class), newLease = mock(ArtifactStore.Lease.class);
        var oldSpec = new BoundSpec("old-digest", "writer", 1, "Destination", "Old", "", "profile", "Old", 1, "lock", oldLease, 1000);
        var newSpec = new BoundSpec("new-digest", "writer", 1, "Destination", "New", "", "profile", "New", 2, "lock", newLease, 1000);
        var oldBinding = new Deployments.Binding(id, "Old", 1, java.util.Map.of(oldSpec.key(), oldSpec));
        var newBinding = new Deployments.Binding(id, "New", 1, java.util.Map.of(newSpec.key(), newSpec));
        var runtimeField = Deployments.Binding.class.getDeclaredField("runtime"); runtimeField.setAccessible(true); runtimeField.set(oldBinding, oldAttempt.channel());
        var stateField = Deployments.Binding.class.getDeclaredField("state"); stateField.setAccessible(true); stateField.set(oldBinding, Deployments.State.ACTIVE);
        put(deployments, "byAttempt", oldBinding.attemptId, oldBinding); put(deployments, "byAttempt", newBinding.attemptId, newBinding);
        put(deployments, "byChannel", id, oldBinding); put(deployments, "byRuntime", oldAttempt.channel(), oldBinding);
        put(deployments, "pendingByChannel", id, newBinding);
        when(engine.deployedChannel(id)).thenReturn(newAttempt.channel());
        doThrow(new AssertionError("old worker ownership unknown")).when(pools).retire(oldBinding.attemptId);
        DefaultEventController events = eventController();
        var observer = new PythonDeploymentObserver(runtime, events);
        try {
            events.addListener(observer);
            assertDoesNotThrow(() -> events.dispatchEvent(event(id, null)));
            assertEquals(Deployments.State.ACTIVE, newBinding.state());
            assertEquals(Deployments.State.RETIRING, oldBinding.state());
            assertSame(newBinding, deployments.binding(id));
            deployments.requireActive(newAttempt.channel());
            try (var admission = deployments.admit(id, 1, "writer", newSpec.digest, newAttempt.channel())) {
                assertSame(newSpec, admission.spec); assertSame(newBinding, admission.binding);
            }
            assertThrows(Deployments.NotAdmitted.class, () -> deployments.admit(id, 1, "writer", oldSpec.digest, oldAttempt.channel()));
            assertDoesNotThrow(() -> events.dispatchEvent(event(id, null)));
            verify(pools).retire(oldBinding.attemptId);
            verify(oldLease, never()).release(); verify(newLease, never()).release();
            assertEquals(2, deployments.bindings().size());
        } finally { observer.close(); }
    }

    private static void put(Deployments deployments, String name, Object key, Deployments.Binding binding) throws Exception {
        var field = Deployments.class.getDeclaredField(name); field.setAccessible(true);
        @SuppressWarnings("unchecked") var map = (java.util.Map<Object, Deployments.Binding>) field.get(deployments);
        map.put(key, binding);
    }

    @Test void stockPerChannelFifoCompletionRunsAfterFailedDeploymentAndReleasesOnlyThatAttempt() throws Exception {
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            String id = UUID.randomUUID().toString();
            var attempt = PythonRuntimeIntegrationTest.deployment(id, fixture.profileId, "task");
            FixtureController controller = new FixtureController();
            CountDownLatch completion = new CountDownLatch(1);
            List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
            try {
                ChannelTask deployment = new ChannelTask(id) {
                    @Override public Void execute() throws Exception {
                        var binding = fixture.runtime.deployments.prepare(attempt.model());
                        order.add("prepared");
                        controller.submitTasks(List.of(new ChannelTask(id) {
                            @Override public Void execute() {
                                order.add("completion");
                                fixture.runtime.deployments.completedAttempt(binding);
                                completion.countDown(); return null;
                            }
                        }), null);
                        assertEquals(Deployments.State.PREPARED, binding.state());
                        order.add("failed"); throw new IllegalStateException("owned deployment failed");
                    }
                };
                controller.submitTasks(List.of(deployment), null).get(0).get(10, TimeUnit.SECONDS);
                assertTrue(completion.await(10, TimeUnit.SECONDS));
                assertEquals(List.of("prepared", "failed", "completion"), order);
                assertTrue(fixture.runtime.deployments.bindings().isEmpty());
                assertEquals(0, fixture.runtime.artifacts.activeLeases());
            } finally { controller.close(id); }
        }
    }

    private static class FixtureController extends DonkeyEngineController {
        @Override protected ConfigurationController getConfigurationController() { return mock(ConfigurationController.class); }
        @Override protected ScriptController getScriptController() { return mock(ScriptController.class); }
        @Override protected ChannelController getChannelController() { return mock(ChannelController.class); }
        @Override protected ExtensionController getExtensionController() { return mock(ExtensionController.class); }
        @Override protected ContextFactoryController getContextFactoryController() { return mock(ContextFactoryController.class); }
        @Override protected EventController getEventController() { return mock(EventController.class); }
        @Override protected CodeTemplateController getCodeTemplateController() { return mock(CodeTemplateController.class); }
        void close(String id) { removeExecutor(id); }
    }
}
