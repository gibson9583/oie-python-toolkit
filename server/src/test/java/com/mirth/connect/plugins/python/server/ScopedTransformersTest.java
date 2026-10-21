/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Calendar;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mozilla.javascript.Context;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.server.channel.Channel;
import com.mirth.connect.donkey.server.channel.DestinationChainProvider;
import com.mirth.connect.donkey.server.channel.DestinationConnector;
import com.mirth.connect.donkey.server.channel.FilterTransformerExecutor;
import com.mirth.connect.donkey.server.channel.FilterTransformerResult;
import com.mirth.connect.donkey.server.channel.ResponseTransformerExecutor;
import com.mirth.connect.donkey.server.channel.SourceConnector;
import com.mirth.connect.donkey.server.channel.components.FilterTransformer;
import com.mirth.connect.donkey.server.channel.components.ResponseTransformer;
import com.mirth.connect.userutil.ImmutableConnectorMessage;
import com.mirth.connect.server.util.CompiledScriptCache;
import com.mirth.connect.server.util.javascript.JavaScriptTask;
import com.mirth.connect.server.util.javascript.JavaScriptUtil;

class ScopedTransformersTest {
    @TempDir Path owned;
    final List<PythonRuntime> runtimes = new ArrayList<>();
    PythonRuntime runtime() throws Exception {
        RegistryStore registry = new RegistryStore(new RegistryStoreTest.Memory()); registry.load(null);
        PythonRuntime runtime = new PythonRuntime(new OperatorConfig(owned.toString(), "scoped-test"), registry, mock(EngineAccess.class));
        var field = PythonRuntime.class.getDeclaredField("deployments"); field.setAccessible(true); field.set(runtime, mock(Deployments.class));
        runtimes.add(runtime); return runtime;
    }
    @AfterEach void stop() { for (PythonRuntime runtime : runtimes) runtime.stop(); assertNull(RuntimeInvocationContext.current()); }

    record Fixture(Channel channel, FilterTransformerExecutor source, FilterTransformerExecutor destination, ResponseTransformerExecutor response,
            FilterTransformer sourceDelegate, FilterTransformer destinationDelegate, ResponseTransformer responseDelegate) {}
    Fixture channel() {
        Channel channel = new Channel(); channel.setChannelId("equal-id");
        FilterTransformer sourceDelegate = mock(FilterTransformer.class), destinationDelegate = mock(FilterTransformer.class);
        ResponseTransformer responseDelegate = mock(ResponseTransformer.class);
        FilterTransformerExecutor source = new FilterTransformerExecutor(null, null), destination = new FilterTransformerExecutor(null, null);
        ResponseTransformerExecutor response = new ResponseTransformerExecutor(null, null);
        source.setFilterTransformer(sourceDelegate); destination.setFilterTransformer(destinationDelegate); response.setResponseTransformer(responseDelegate);
        SourceConnector receiver = mock(SourceConnector.class);
        when(receiver.getChannel()).thenReturn(channel); when(receiver.getMetaDataId()).thenReturn(0); when(receiver.getFilterTransformerExecutor()).thenReturn(source);
        channel.setSourceConnector(receiver);
        DestinationConnector dispatcher = mock(DestinationConnector.class);
        when(dispatcher.getChannel()).thenReturn(channel); when(dispatcher.getMetaDataId()).thenReturn(3);
        when(dispatcher.getFilterTransformerExecutor()).thenReturn(destination); when(dispatcher.getResponseTransformerExecutor()).thenReturn(response);
        DestinationChainProvider chain = mock(DestinationChainProvider.class); when(chain.getDestinationConnectors()).thenReturn(Map.of(3, dispatcher));
        channel.getDestinationChainProviders().add(chain);
        return new Fixture(channel, source, destination, response, sourceDelegate, destinationDelegate, responseDelegate);
    }

    @Test void everyPhaseCapturesItsExactRuntimeAndRestoresOuterScopeAndRhino() throws Exception {
        PythonRuntime runtime = runtime(); Fixture f = channel();
        FilterTransformerResult expected = new FilterTransformerResult(false, "filtered");
        when(f.sourceDelegate.doFilterTransform(any())).thenAnswer(call -> { check(f.channel, 0, "source-transformer"); return expected; });
        when(f.destinationDelegate.doFilterTransform(any())).thenAnswer(call -> { check(f.channel, 3, "destination-transformer"); return expected; });
        when(f.responseDelegate.doTransform(any(), any())).thenAnswer(call -> { check(f.channel, 3, "response-transformer"); return "reply"; });
        ScopedTransformers.install(runtime, f.channel);
        Context rhino = Context.enter();
        try (var outer = RuntimeInvocationContext.open(new Channel(), 9, "outer")) {
            ConnectorMessage message = message(f.channel, 0);
            assertSame(expected, f.source.getFilterTransformer().doFilterTransform(message));
            assertSame(expected, f.destination.getFilterTransformer().doFilterTransform(message(f.channel, 3)));
            assertEquals("reply", f.response.getResponseTransformer().doTransform(new Response(), message(f.channel, 3)));
            assertSame(outer, RuntimeInvocationContext.current());
            assertSame(rhino, Context.getCurrentContext());
        } finally { Context.exit(); }
        verify(runtime.deployments, times(3)).requireActive(f.channel);
    }

    static void check(Channel channel, int metadata, String phase) {
        var current = RuntimeInvocationContext.current(); assertNotNull(current);
        assertSame(channel, current.getChannel()); assertEquals(metadata, current.getConnectorMetadataId()); assertEquals(phase, current.getPhase());
    }
    static ConnectorMessage message(Channel channel, int metadata) {
        ConnectorMessage message = new ConnectorMessage(channel.getChannelId(), "scope-test", 17, metadata, "owned-test-node", Calendar.getInstance(), null);
        message.setConnectorMap(new HashMap<>()); message.setChannelMap(new HashMap<>()); message.setSourceMap(new HashMap<>());
        return message;
    }

    @Test void oldRuntimeWithEqualIdNeverReachesTheGlobalScriptCacheAfterReplacement() throws Exception {
        PythonRuntime runtime = runtime(); Fixture old = channel(), current = channel();
        AtomicReference<Channel> active = new AtomicReference<>(old.channel);
        AtomicReference<String> cache = new AtomicReference<>("old script"); List<String> executions = new ArrayList<>();
        doAnswer(call -> { if (call.getArgument(0) != active.get()) throw new IllegalStateException("retired actual runtime"); return null; })
                .when(runtime.deployments).requireActive(any());
        when(old.sourceDelegate.doFilterTransform(any())).thenAnswer(call -> { executions.add(cache.get()); return null; });
        when(current.sourceDelegate.doFilterTransform(any())).thenAnswer(call -> { executions.add(cache.get()); return null; });
        ScopedTransformers.install(runtime, old.channel); ScopedTransformers.install(runtime, current.channel);
        active.set(current.channel); cache.set("replacement script");
        assertThrows(IllegalStateException.class, () -> old.source.getFilterTransformer().doFilterTransform(message(old.channel, 0)));
        assertTrue(executions.isEmpty()); verify(old.sourceDelegate, never()).doFilterTransform(any());
        current.source.getFilterTransformer().doFilterTransform(message(current.channel, 0));
        assertEquals(List.of("replacement script"), executions);
    }

    @Test void pendingBindingRejectsBeforeDelegationAndInstallationIsIdempotent() throws Exception {
        PythonRuntime runtime = runtime(); Fixture f = channel();
        ScopedTransformers.install(runtime, f.channel); Object source = f.source.getFilterTransformer(), response = f.response.getResponseTransformer();
        ScopedTransformers.install(runtime, f.channel); assertSame(source, f.source.getFilterTransformer()); assertSame(response, f.response.getResponseTransformer());
        doThrow(new IllegalStateException("prepared, not active")).when(runtime.deployments).requireActive(f.channel);
        assertThrows(IllegalStateException.class, () -> f.source.getFilterTransformer().doFilterTransform(message(f.channel, 0)));
        assertThrows(IllegalStateException.class, () -> f.response.getResponseTransformer().doTransform(new Response(), message(f.channel, 3)));
        verifyNoInteractions(f.sourceDelegate, f.destinationDelegate, f.responseDelegate);
    }

    @Test void ownerValidationCompletesBeforeAnySetterMutation() throws Exception {
        PythonRuntime first = runtime(), second = runtime(); Fixture f = channel();
        ScopedTransformers.install(first, f.channel);
        f.source.setFilterTransformer(f.sourceDelegate); f.destination.setFilterTransformer(f.destinationDelegate);
        assertThrows(IllegalStateException.class, () -> ScopedTransformers.install(second, f.channel));
        assertSame(f.sourceDelegate, f.source.getFilterTransformer()); assertSame(f.destinationDelegate, f.destination.getFilterTransformer());
    }

    @Test void sharedExecutorAcrossScopesIsRejectedBeforeMutation() throws Exception {
        PythonRuntime runtime = runtime(); Fixture f = channel();
        DestinationConnector destination = f.channel.getDestinationChainProviders().get(0).getDestinationConnectors().get(3);
        when(destination.getFilterTransformerExecutor()).thenReturn(f.source);
        assertThrows(IllegalStateException.class, () -> ScopedTransformers.install(runtime, f.channel));
        assertSame(f.sourceDelegate, f.source.getFilterTransformer());
    }

    @Test void delegateErrorRestoresContextAndNeverExitsTheCallersRhinoContext() throws Exception {
        PythonRuntime runtime = runtime(); Fixture f = channel(); AssertionError original = new AssertionError("delegate failed");
        when(f.sourceDelegate.doFilterTransform(any())).thenThrow(original); ScopedTransformers.install(runtime, f.channel);
        Context rhino = Context.enter();
        try {
            assertSame(original, assertThrows(AssertionError.class, () -> f.source.getFilterTransformer().doFilterTransform(message(f.channel, 0))));
            assertNull(RuntimeInvocationContext.current()); assertSame(rhino, Context.getCurrentContext());
        } finally { Context.exit(); }
    }

    @Test void disposeAttemptsRetirementAfterAllThrowableFailuresAndPreservesOriginal() throws Exception {
        PythonRuntime runtime = runtime(); Fixture f = channel(); AssertionError original = new AssertionError("delegate disposal");
        LinkageError cleanup = new LinkageError("retirement failure");
        doThrow(original).when(f.sourceDelegate).dispose(); doThrow(cleanup).when(runtime.deployments).retire(f.channel, "transformer disposed");
        ScopedTransformers.install(runtime, f.channel);
        assertSame(original, assertThrows(AssertionError.class, () -> f.source.getFilterTransformer().dispose()));
        assertArrayEquals(new Throwable[]{cleanup}, original.getSuppressed());
        f.source.getFilterTransformer().dispose();
        verify(f.sourceDelegate, times(1)).dispose(); verify(runtime.deployments, times(1)).retire(f.channel, "transformer disposed");
        assertThrows(IllegalStateException.class, () -> f.source.getFilterTransformer().doFilterTransform(message(f.channel, 0)));
    }

    @Test void successfulResponseDisposeStillPropagatesRetirementFailure() throws Exception {
        PythonRuntime runtime = runtime(); Fixture f = channel(); LinkageError cleanup = new LinkageError("retirement failed");
        doThrow(cleanup).when(runtime.deployments).retire(f.channel, "transformer disposed"); ScopedTransformers.install(runtime, f.channel);
        assertSame(cleanup, assertThrows(LinkageError.class, () -> f.response.getResponseTransformer().dispose()));
        verify(f.responseDelegate).dispose(); verify(runtime.deployments).retire(f.channel, "transformer disposed");
    }

    @Test void realJavaScriptTaskCannotBorrowNewRuntimeAfterGlobalCompiledScriptReplacement() throws Exception {
        PythonRuntime runtime = runtime(); Fixture old = channel(), replacement = channel();
        AtomicReference<Channel> active = new AtomicReference<>(old.channel);
        doAnswer(call -> { if (call.getArgument(0) != active.get()) throw new IllegalStateException("retired actual runtime"); return null; })
                .when(runtime.deployments).requireActive(any());
        doAnswer(call -> { if (call.getArgument(4) != active.get()) throw new Deployments.NotAdmitted("retired actual runtime"); return null; })
                .when(runtime.deployments).admit(anyString(), anyInt(), anyString(), anyString(), any());
        String scriptId = "owned-python-scope-script";
        CountDownLatch taskStarted = new CountDownLatch(1), replacementReady = new CountDownLatch(1);
        AtomicReference<Throwable> taskFailure = new AtomicReference<>(), ownerFailure = new AtomicReference<>();
        when(old.sourceDelegate.doFilterTransform(any())).thenAnswer(call -> {
            ConnectorMessage raw = call.getArgument(0);
            JavaScriptUtil.execute(new JavaScriptTask<Void>(null, "owned compiled cache race") {
                @Override public Void doCall() throws Exception {
                    taskStarted.countDown(); assertTrue(replacementReady.await(5, TimeUnit.SECONDS));
                    Context context = Context.enter();
                    try {
                        assertEquals("replacement script", CompiledScriptCache.getInstance().getCompiledScript(scriptId).exec(context, context.initStandardObjects()));
                        RuntimeInvocationContext scope = RuntimeInvocationContext.resolve(new ImmutableConnectorMessage(raw, true), "source-transformer");
                        assertNotNull(scope); assertSame(old.channel, scope.getChannel());
                        assertThrows(Deployments.NotAdmitted.class, () -> scope.admit(runtime, "new-cache-digest"));
                    } catch (Throwable failure) { taskFailure.set(failure); } finally { Context.exit(); }
                    return null;
                }
            });
            return null;
        });
        ScopedTransformers.install(runtime, old.channel); ScopedTransformers.install(runtime, replacement.channel);
        try (var harness = new StockJavaScriptTestHarness()) {
            Context context = Context.enter();
            try { CompiledScriptCache.getInstance().putCompiledScript(scriptId, context.compileString("'old script'", scriptId, 1, null), "old script"); }
            finally { Context.exit(); }
            Thread owner = new Thread(() -> { try { old.source.getFilterTransformer().doFilterTransform(message(old.channel, 0)); }
                catch (Throwable failure) { ownerFailure.set(failure); } }, "owned old transformer");
            owner.start();
            try {
                assertTrue(taskStarted.await(5, TimeUnit.SECONDS)); active.set(replacement.channel);
                context = Context.enter();
                try { CompiledScriptCache.getInstance().putCompiledScript(scriptId, context.compileString("'replacement script'", scriptId, 1, null), "replacement script"); }
                finally { Context.exit(); }
                replacementReady.countDown(); owner.join(5000); assertFalse(owner.isAlive());
                assertNull(ownerFailure.get()); assertNull(taskFailure.get());
            } finally { replacementReady.countDown(); owner.join(5000); CompiledScriptCache.getInstance().removeCompiledScript(scriptId); }
        }
    }

    @Test void bothWrapperPhasesQuarantineInterruptedCallsEvenWhenDelegateClearsTheFlag() throws Exception {
        PythonRuntime runtime = runtime(); Fixture fixture = channel();
        when(fixture.destinationDelegate.doFilterTransform(any())).thenThrow(new InterruptedException("caller stopped waiting"));
        when(fixture.responseDelegate.doTransform(any(), any())).thenThrow(new InterruptedException("caller stopped waiting"));
        ScopedTransformers.install(runtime, fixture.channel);
        ConnectorMessage destination = message(fixture.channel, 3), response = message(fixture.channel, 3);
        assertThrows(InterruptedException.class, () -> fixture.destination.getFilterTransformer().doFilterTransform(destination));
        assertFalse(Thread.currentThread().isInterrupted()); assertNull(RuntimeInvocationContext.current());
        assertThrows(IllegalStateException.class, () -> fixture.destination.getFilterTransformer().doFilterTransform(destination));
        assertThrows(InterruptedException.class, () -> fixture.response.getResponseTransformer().doTransform(new Response(), response));
        assertFalse(Thread.currentThread().isInterrupted()); assertNull(RuntimeInvocationContext.current());
        assertThrows(IllegalStateException.class, () -> fixture.response.getResponseTransformer().doTransform(new Response(), response));
        verify(fixture.destinationDelegate, times(1)).doFilterTransform(any()); verify(fixture.responseDelegate, times(1)).doTransform(any(), any());
    }
}
