/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import com.mirth.connect.donkey.model.DonkeyException;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.server.channel.Channel;
import com.mirth.connect.donkey.server.channel.Connector;
import com.mirth.connect.donkey.server.channel.FilterTransformerExecutor;
import com.mirth.connect.donkey.server.channel.FilterTransformerResult;
import com.mirth.connect.donkey.server.channel.ResponseTransformerExecutor;
import com.mirth.connect.donkey.server.channel.components.FilterTransformer;
import com.mirth.connect.donkey.server.channel.components.FilterTransformerException;
import com.mirth.connect.donkey.server.channel.components.ResponseTransformer;
import com.mirth.connect.plugins.python.PythonContract;

/** Uses stock public executor setters; retains decorators on retired runtimes. */
public final class ScopedTransformers {
    private ScopedTransformers() {}

    public static void install(PythonRuntime runtime, Channel channel) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(channel, "channel");
        synchronized (channel) {
            List<Runnable> changes = new ArrayList<>();
            IdentityHashMap<Object, Boolean> executors = new IdentityHashMap<>();
            var source = Objects.requireNonNull(channel.getSourceConnector(), "source connector");
            checkConnector(channel, source, 0);
            filter(runtime, channel, source.getFilterTransformerExecutor(), 0, PythonContract.ROLE_SOURCE_TRANSFORMER, executors, changes);
            for (var chain : channel.getDestinationChainProviders()) {
                for (var entry : chain.getDestinationConnectors().entrySet()) {
                    int metadataId = entry.getKey();
                    if (metadataId <= 0) throw new IllegalStateException("Destination metadata IDs must be positive");
                    var destination = Objects.requireNonNull(entry.getValue(), "destination connector");
                    checkConnector(channel, destination, metadataId);
                    filter(runtime, channel, destination.getFilterTransformerExecutor(), metadataId,
                            PythonContract.ROLE_DESTINATION_TRANSFORMER, executors, changes);
                    response(runtime, channel, destination.getResponseTransformerExecutor(), metadataId, executors, changes);
                }
            }
            // Validate all owners/aliases before the first setter. An unexpected
            // setter failure leaves the observer's binding PREPARED and closed.
            for (Runnable change : changes) change.run();
        }
    }

    private static void checkConnector(Channel channel, Connector connector, int metadataId) {
        if (connector.getChannel() != channel || connector.getMetaDataId() != metadataId) {
            throw new IllegalStateException("Transformer connector does not belong to this runtime and metadata ID");
        }
    }

    private static void claim(IdentityHashMap<Object, Boolean> executors, Object executor) {
        if (executors.put(executor, Boolean.TRUE) != null) {
            throw new IllegalStateException("A transformer executor is shared across connector scopes");
        }
    }

    private static void filter(PythonRuntime runtime, Channel channel, FilterTransformerExecutor executor, int metadataId, String phase,
            IdentityHashMap<Object, Boolean> executors, List<Runnable> changes) {
        if (executor == null) return;
        claim(executors, executor);
        FilterTransformer delegate = executor.getFilterTransformer();
        if (delegate == null) return;
        if (delegate instanceof Filter scoped) {
            scoped.requireOwner(runtime, channel, metadataId, phase);
            return;
        }
        Filter wrapped = new Filter(runtime, channel, metadataId, phase, delegate);
        changes.add(() -> executor.setFilterTransformer(wrapped));
    }

    private static void response(PythonRuntime runtime, Channel channel, ResponseTransformerExecutor executor, int metadataId,
            IdentityHashMap<Object, Boolean> executors, List<Runnable> changes) {
        if (executor == null) return;
        claim(executors, executor);
        ResponseTransformer delegate = executor.getResponseTransformer();
        if (delegate == null) return;
        if (delegate instanceof Reply scoped) {
            scoped.requireOwner(runtime, channel, metadataId, PythonContract.ROLE_RESPONSE_TRANSFORMER);
            return;
        }
        Reply wrapped = new Reply(runtime, channel, metadataId, delegate);
        changes.add(() -> executor.setResponseTransformer(wrapped));
    }

    private abstract static class Scoped {
        final PythonRuntime runtime;
        final Channel channel;
        final int metadataId;
        final String phase;
        final AtomicBoolean disposed = new AtomicBoolean();
        Scoped(PythonRuntime runtime, Channel channel, int metadataId, String phase) {
            this.runtime = runtime; this.channel = channel; this.metadataId = metadataId; this.phase = phase;
        }
        final void requireOwner(PythonRuntime runtime, Channel channel, int metadataId, String phase) {
            if (this.runtime != runtime || this.channel != channel || this.metadataId != metadataId || !this.phase.equals(phase)) {
                throw new IllegalStateException("A transformer is already scoped to a different Python runtime owner");
            }
            if (disposed.get()) throw new IllegalStateException("A disposed transformer cannot be installed again");
        }
        final void requireActive() {
            try { runtime.deployments.requireActive(channel); }
            catch (Deployments.NotAdmitted failure) { throw new IllegalStateException(failure.getMessage(), failure); }
            if (disposed.get()) throw new IllegalStateException("The scoped transformer was disposed");
        }
        final void dispose(Runnable delegate) {
            if (!disposed.compareAndSet(false, true)) return;
            Throwable failure = null;
            try { delegate.run(); } catch (Throwable caught) { failure = caught; }
            finally {
                try { runtime.deployments.retire(channel, "transformer disposed"); }
                catch (Throwable cleanup) { if (failure == null) failure = cleanup; else if (cleanup != failure) failure.addSuppressed(cleanup); }
            }
            if (failure instanceof Error error) throw error;
            if (failure instanceof RuntimeException error) throw error;
            if (failure != null) throw new IllegalStateException("Transformer disposal failed", failure);
        }
    }

    private static final class Filter extends Scoped implements FilterTransformer {
        final FilterTransformer delegate;
        Filter(PythonRuntime runtime, Channel channel, int metadataId, String phase, FilterTransformer delegate) {
            super(runtime, channel, metadataId, phase); this.delegate = delegate;
        }
        @Override public FilterTransformerResult doFilterTransform(ConnectorMessage message) throws FilterTransformerException, InterruptedException {
            requireActive();
            try (var scope = RuntimeInvocationContext.open(channel, metadataId, phase, message)) {
                try { return delegate.doFilterTransform(message); }
                catch (InterruptedException interrupted) { scope.interrupted(); throw interrupted; }
            }
        }
        @Override public void dispose() { dispose(delegate::dispose); }
    }

    private static final class Reply extends Scoped implements ResponseTransformer {
        final ResponseTransformer delegate;
        Reply(PythonRuntime runtime, Channel channel, int metadataId, ResponseTransformer delegate) {
            super(runtime, channel, metadataId, PythonContract.ROLE_RESPONSE_TRANSFORMER); this.delegate = delegate;
        }
        @Override public String doTransform(Response response, ConnectorMessage message) throws DonkeyException, InterruptedException {
            requireActive();
            try (var scope = RuntimeInvocationContext.open(channel, metadataId, phase, message)) {
                try { return delegate.doTransform(response, message); }
                catch (InterruptedException interrupted) { scope.interrupted(); throw interrupted; }
            }
        }
        @Override public void dispose() { dispose(delegate::dispose); }
    }
}
