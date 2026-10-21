/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import com.mirth.connect.donkey.server.channel.Channel;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.userutil.ImmutableConnectorMessage;
import com.mirth.connect.server.util.javascript.JavaScriptTask;
import com.mirth.connect.server.util.javascript.JavaScriptUtil;

class RuntimeInvocationContextTest {
    @Test void nestedScopesRestoreIdentityWithoutChangingRhinoOwnership() {
        Context rhino = Context.enter();
        try {
            Channel outerChannel = new Channel(), innerChannel = new Channel();
            try (var outer = RuntimeInvocationContext.open(outerChannel, 0, "source-transformer")) {
                assertSame(outer, RuntimeInvocationContext.current());
                try (var inner = RuntimeInvocationContext.open(innerChannel, 3, "response-transformer")) {
                    assertSame(inner, RuntimeInvocationContext.current());
                    assertSame(innerChannel, inner.getChannel());
                    assertEquals(3, inner.getConnectorMetadataId());
                    assertEquals("response-transformer", inner.getPhase());
                    assertSame(rhino, Context.getCurrentContext());
                }
                assertSame(outer, RuntimeInvocationContext.current());
                assertSame(rhino, Context.getCurrentContext());
            }
            assertNull(RuntimeInvocationContext.current());
            assertSame(rhino, Context.getCurrentContext());
        } finally { Context.exit(); }
    }

    @Test void scopeIsNotInheritedAndCannotCloseFromAnotherThread() throws Exception {
        try (var scope = RuntimeInvocationContext.open(new Channel(), 1, "destination-transformer")) {
            AtomicReference<Throwable> result = new AtomicReference<>();
            Thread child = new Thread(() -> {
                try { assertNull(RuntimeInvocationContext.current()); scope.close(); }
                catch (Throwable error) { result.set(error); }
            });
            child.start(); child.join(5000);
            assertFalse(child.isAlive());
            assertInstanceOf(IllegalStateException.class, result.get());
            assertSame(scope, RuntimeInvocationContext.current());
        }
        assertNull(RuntimeInvocationContext.current());
    }

    @Test void incorrectCloseOrderDoesNotDestroyTheStackAndCloseIsIdempotent() {
        var outer = RuntimeInvocationContext.open(new Channel(), 0, "source-transformer");
        var inner = RuntimeInvocationContext.open(new Channel(), 1, "response-transformer");
        try {
            assertThrows(IllegalStateException.class, outer::close);
            assertSame(inner, RuntimeInvocationContext.current());
            inner.close(); inner.close();
            assertSame(outer, RuntimeInvocationContext.current());
        } finally { inner.close(); outer.close(); outer.close(); }
        assertNull(RuntimeInvocationContext.current());
    }

    @Test void invalidOpenLeavesTheCurrentScopeUntouched() {
        try (var outer = RuntimeInvocationContext.open(new Channel(), 0, "source-transformer")) {
            assertThrows(NullPointerException.class, () -> RuntimeInvocationContext.open(null, 1, "destination-transformer"));
            assertThrows(IllegalArgumentException.class, () -> RuntimeInvocationContext.open(new Channel(), -1, "destination-transformer"));
            assertThrows(NullPointerException.class, () -> RuntimeInvocationContext.open(new Channel(), 1, null));
            assertSame(outer, RuntimeInvocationContext.current());
        }
    }

    static Channel channel() { Channel channel = new Channel(); channel.setChannelId("same-channel-id"); return channel; }
    static ConnectorMessage message(Channel channel, int metadata) { return ScopedTransformersTest.message(channel, metadata); }

    @Test void actualStockJavaScriptExecutorResolvesOnlyThePublicExactMapIdentities() throws Exception {
        Channel channel = channel(); ConnectorMessage raw = message(channel, 3);
        ImmutableConnectorMessage immutable = new ImmutableConnectorMessage(raw, true);
        assertSame(raw.getConnectorMap(), immutable.getConnectorMap()); assertSame(raw.getChannelMap(), immutable.getChannelMap());
        assertSame(raw.getSourceMap(), immutable.getSourceMap());
        Thread owner = Thread.currentThread(); Context rhino = Context.enter();
        try (var harness = new StockJavaScriptTestHarness(); var scope = RuntimeInvocationContext.open(channel, 3, "response-transformer", raw)) {
            RuntimeInvocationContext resolved = JavaScriptUtil.execute(new JavaScriptTask<RuntimeInvocationContext>(null, "owned transfer proof") {
                @Override public RuntimeInvocationContext doCall() {
                    assertNotSame(owner, Thread.currentThread()); assertNull(RuntimeInvocationContext.current());
                    return RuntimeInvocationContext.resolve(immutable, "response-transformer");
                }
            });
            assertSame(scope, resolved); assertSame(rhino, Context.getCurrentContext());
            assertNull(RuntimeInvocationContext.resolve(new ImmutableConnectorMessage(raw, false), "response-transformer"));
            assertNull(RuntimeInvocationContext.resolve(immutable, "destination-transformer"));
        } finally { Context.exit(); }
        assertNull(RuntimeInvocationContext.resolve(immutable, "response-transformer"));
    }

    @Test void mapAliasesWithDifferentTupleOrOtherMapIdentitiesCannotResolve() {
        Channel channel = channel(); ConnectorMessage raw = message(channel, 3), alias = message(channel, 3);
        alias.setConnectorMap(raw.getConnectorMap());
        try (var scope = RuntimeInvocationContext.open(channel, 3, "destination-transformer", raw)) {
            assertNull(RuntimeInvocationContext.resolve(new ImmutableConnectorMessage(alias, true), "destination-transformer"));
            alias.setChannelMap(raw.getChannelMap()); alias.setSourceMap(raw.getSourceMap());
            ImmutableConnectorMessage immutable = new ImmutableConnectorMessage(alias, true);
            assertSame(scope, RuntimeInvocationContext.resolve(immutable, "destination-transformer"));
            alias.setMessageId(18); assertNull(RuntimeInvocationContext.resolve(immutable, "destination-transformer")); alias.setMessageId(17);
            alias.setServerId("different-server"); assertNull(RuntimeInvocationContext.resolve(immutable, "destination-transformer")); alias.setServerId(raw.getServerId());
            alias.setMetaDataId(4); assertNull(RuntimeInvocationContext.resolve(immutable, "destination-transformer")); alias.setMetaDataId(3);
            alias.setChannelId("different-channel"); assertNull(RuntimeInvocationContext.resolve(immutable, "destination-transformer"));
        }
    }

    @Test void duplicateActiveMapPhaseRejectsAcrossEqualIdRuntimesWhileNestedPhasesRemainIndependent() {
        Channel first = channel(), second = channel(); ConnectorMessage raw = message(first, 3);
        try (var outer = RuntimeInvocationContext.open(first, 3, "destination-transformer", raw)) {
            assertThrows(IllegalStateException.class, () -> RuntimeInvocationContext.open(second, 3, "destination-transformer", raw));
            assertSame(outer, RuntimeInvocationContext.current());
            try (var response = RuntimeInvocationContext.open(first, 3, "response-transformer", raw)) {
                var immutable = new ImmutableConnectorMessage(raw, true);
                assertSame(outer, RuntimeInvocationContext.resolve(immutable, "destination-transformer"));
                assertSame(response, RuntimeInvocationContext.resolve(immutable, "response-transformer"));
            }
        }
        try (var retry = RuntimeInvocationContext.open(second, 3, "destination-transformer", raw)) {
            assertSame(second, retry.getChannel());
        }
    }

    @Test void closeAfterResolvePreventsAdmissionAndSyntheticThreadLocalCannotAdmit() throws Exception {
        PythonRuntime runtime = mock(PythonRuntime.class); Deployments deployments = mock(Deployments.class);
        var field = PythonRuntime.class.getDeclaredField("deployments"); field.setAccessible(true); field.set(runtime, deployments);
        Channel channel = channel(); ConnectorMessage raw = message(channel, 0);
        RuntimeInvocationContext scope = RuntimeInvocationContext.open(channel, 0, "source-transformer", raw);
        RuntimeInvocationContext resolved = RuntimeInvocationContext.resolve(new ImmutableConnectorMessage(raw, true), "source-transformer");
        assertSame(scope, resolved); resolved.admit(runtime, "digest");
        verify(deployments).admit("same-channel-id", 0, "source-transformer", "digest", channel);
        clearInvocations(deployments); scope.close();
        assertThrows(Deployments.NotAdmitted.class, () -> resolved.admit(runtime, "digest")); verifyNoInteractions(deployments);
        try (var synthetic = RuntimeInvocationContext.open(channel, 0, "source-transformer")) {
            assertThrows(Deployments.NotAdmitted.class, () -> synthetic.admit(runtime, "digest"));
            assertNull(RuntimeInvocationContext.resolve(new ImmutableConnectorMessage(raw, true), "source-transformer"));
        }
    }

    @Test void interruptedOwnerQuarantinesSameMapRetryUntilLateStockTaskEnds() throws Exception {
        Channel old = channel(), replacement = channel(); ConnectorMessage raw = message(old, 3);
        ImmutableConnectorMessage immutable = new ImmutableConnectorMessage(raw, true);
        CountDownLatch taskStarted = new CountDownLatch(1), allowLateResolve = new CountDownLatch(1), taskDone = new CountDownLatch(1);
        AtomicReference<Throwable> ownerFailure = new AtomicReference<>(), taskFailure = new AtomicReference<>();
        AtomicReference<RuntimeInvocationContext> late = new AtomicReference<>();
        try (var harness = new StockJavaScriptTestHarness()) {
            Thread owner = new Thread(() -> {
                try (var scope = RuntimeInvocationContext.open(old, 3, "destination-transformer", raw)) {
                    try {
                        JavaScriptUtil.execute(new JavaScriptTask<Void>(null, "owned interrupted transfer proof") {
                            @Override public Void doCall() {
                                try {
                                    taskStarted.countDown();
                                    while (true) { try { if (allowLateResolve.await(5, TimeUnit.SECONDS)) break; throw new AssertionError("late task timed out"); }
                                        catch (InterruptedException ignored) { /* Model native/uninterruptible user work. */ } }
                                    late.set(RuntimeInvocationContext.resolve(immutable, "destination-transformer"));
                                } catch (Throwable failure) { taskFailure.set(failure); } finally { taskDone.countDown(); }
                                return null;
                            }
                        });
                        throw new AssertionError("Expected caller interruption");
                    } catch (InterruptedException expected) {
                        Thread.interrupted(); // Prove explicit exception handling, independent of the flag.
                        scope.interrupted();
                    }
                } catch (Throwable failure) { ownerFailure.set(failure); }
            }, "owned transformer caller");
            owner.start();
            try {
                assertTrue(taskStarted.await(5, TimeUnit.SECONDS)); owner.interrupt(); owner.join(5000);
                assertFalse(owner.isAlive()); assertNull(ownerFailure.get());
                assertThrows(IllegalStateException.class, () -> RuntimeInvocationContext.open(replacement, 3, "destination-transformer", raw));
                try (var fresh = RuntimeInvocationContext.open(replacement, 3, "destination-transformer", message(replacement, 3))) {
                    allowLateResolve.countDown(); assertTrue(taskDone.await(5, TimeUnit.SECONDS));
                    assertNull(late.get()); assertNull(taskFailure.get());
                }
                assertThrows(IllegalStateException.class, () -> RuntimeInvocationContext.open(replacement, 3, "destination-transformer", raw));
                try (var differentPhase = RuntimeInvocationContext.open(replacement, 3, "response-transformer", raw)) {
                    assertSame(differentPhase, RuntimeInvocationContext.resolve(immutable, "response-transformer"));
                }
            } finally {
                allowLateResolve.countDown(); owner.interrupt(); owner.join(5000); assertTrue(taskDone.await(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test void differentPhasesResolveConcurrentlyWithoutInheritingAnotherOwnersStack() throws Exception {
        Channel channel = channel(); ConnectorMessage raw = message(channel, 3);
        ImmutableConnectorMessage immutable = new ImmutableConnectorMessage(raw, true);
        CountDownLatch bothOpen = new CountDownLatch(2), release = new CountDownLatch(1);
        AtomicReference<RuntimeInvocationContext> destination = new AtomicReference<>(), response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>(); List<Thread> owners = new ArrayList<>();
        for (String phase : List.of("destination-transformer", "response-transformer")) {
            Thread owner = new Thread(() -> {
                try (var scope = RuntimeInvocationContext.open(channel, 3, phase, raw)) {
                    (phase.equals("destination-transformer") ? destination : response).set(scope);
                    bothOpen.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
                    assertSame(scope, RuntimeInvocationContext.current());
                } catch (Throwable caught) { failure.compareAndSet(null, caught); }
            }, "owned " + phase);
            owners.add(owner); owner.start();
        }
        try (var harness = new StockJavaScriptTestHarness()) {
            assertTrue(bothOpen.await(5, TimeUnit.SECONDS));
            JavaScriptUtil.execute(new JavaScriptTask<Void>(null, "owned concurrent phase proof") {
                @Override public Void doCall() {
                    assertNull(RuntimeInvocationContext.current());
                    assertSame(destination.get(), RuntimeInvocationContext.resolve(immutable, "destination-transformer"));
                    assertSame(response.get(), RuntimeInvocationContext.resolve(immutable, "response-transformer"));
                    return null;
                }
            });
        } finally { release.countDown(); for (Thread owner : owners) { owner.join(5000); assertFalse(owner.isAlive()); } }
        assertNull(failure.get()); assertNull(RuntimeInvocationContext.resolve(immutable, "destination-transformer"));
        assertNull(RuntimeInvocationContext.resolve(immutable, "response-transformer"));
    }

    @Test void globalSpanBoundRejectsBeforeRegistrationAndAllClosedScopesReleaseCapacity() {
        Channel channel = channel(); List<RuntimeInvocationContext> scopes = new ArrayList<>();
        try {
            for (int i = 0; i < 4096; i++) scopes.add(RuntimeInvocationContext.open(channel, 0, "source-transformer", message(channel, 0)));
            assertThrows(IllegalStateException.class, () -> RuntimeInvocationContext.open(channel, 0, "source-transformer", message(channel, 0)));
            assertSame(scopes.get(scopes.size() - 1), RuntimeInvocationContext.current());
        } finally { for (int i = scopes.size() - 1; i >= 0; i--) scopes.get(i).close(); }
        assertNull(RuntimeInvocationContext.current());
        try (var available = RuntimeInvocationContext.open(channel, 0, "source-transformer", message(channel, 0))) {
            assertSame(available, RuntimeInvocationContext.current());
        }
    }
}
