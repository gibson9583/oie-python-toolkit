/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.server.channel.Channel;
import com.mirth.connect.userutil.ImmutableConnectorMessage;

/** Plugin-owned exact runtime identity around stock transformer delegates. */
public final class RuntimeInvocationContext implements AutoCloseable {
    private static final ThreadLocal<RuntimeInvocationContext> CURRENT = new ThreadLocal<>();
    private static final Object REGISTRY = new Object();
    private static final int MAX_ACTIVE_SPANS = 4096;
    private static final IdentityHashMap<Map<String, Object>, Map<String, RuntimeInvocationContext>> ACTIVE = new IdentityHashMap<>();
    private static final ReferenceQueue<Map<String, Object>> COLLECTED = new ReferenceQueue<>();
    private static final Map<WeakIdentity, Set<String>> QUARANTINED = new HashMap<>();
    private static int activeCount;
    private final Thread owner = Thread.currentThread();
    private final RuntimeInvocationContext previous;
    private final Channel channel;
    private final int connectorMetadataId;
    private final String phase;
    private final Map<String, Object> connectorMap, channelMap, sourceMap;
    private final String channelId, serverId;
    private final long messageId;
    private boolean closed;
    private boolean interrupted;

    private RuntimeInvocationContext(Channel channel, int connectorMetadataId, String phase, ConnectorMessage message) {
        this.channel = Objects.requireNonNull(channel, "channel");
        if (connectorMetadataId < 0) throw new IllegalArgumentException("connector metadata ID must not be negative");
        this.connectorMetadataId = connectorMetadataId;
        this.phase = Objects.requireNonNull(phase, "phase");
        channelId = channel.getChannelId();
        if (message != null) {
            if (channelId == null || !channelId.equals(message.getChannelId()) || connectorMetadataId != message.getMetaDataId()) {
                throw new IllegalArgumentException("The message does not belong to this runtime connector");
            }
            connectorMap = Objects.requireNonNull(message.getConnectorMap(), "connector map");
            channelMap = Objects.requireNonNull(message.getChannelMap(), "channel map");
            sourceMap = Objects.requireNonNull(message.getSourceMap(), "source map");
            messageId = message.getMessageId(); serverId = message.getServerId();
            synchronized (REGISTRY) {
                reapQuarantine();
                Map<String, RuntimeInvocationContext> phases = ACTIVE.get(connectorMap);
                if (isQuarantined(connectorMap, phase) || phases != null && phases.containsKey(phase)) {
                    throw new IllegalStateException("This message-map identity is already active or has interrupted work for this phase");
                }
                if (activeCount >= MAX_ACTIVE_SPANS) throw new IllegalStateException("Too many active transformer invocation scopes");
                if (phases == null) { phases = new HashMap<>(); ACTIVE.put(connectorMap, phases); }
                phases.put(phase, this); activeCount++;
            }
        } else {
            connectorMap = channelMap = sourceMap = null; messageId = 0; serverId = null;
        }
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    public static RuntimeInvocationContext open(Channel channel, int connectorMetadataId, String phase) {
        return new RuntimeInvocationContext(channel, connectorMetadataId, phase, null);
    }
    /** Stock JavaScript tasks run on another executor thread. Their public
     * immutable message exposes these exact raw map objects with modifiableMaps=true.
     * Register only for the owning delegate's lifetime; never write a marker into a map. */
    public static RuntimeInvocationContext open(Channel channel, int connectorMetadataId, String phase, ConnectorMessage message) {
        return new RuntimeInvocationContext(channel, connectorMetadataId, phase, Objects.requireNonNull(message, "message"));
    }
    public static RuntimeInvocationContext resolve(ImmutableConnectorMessage message, String phase) {
        if (message == null || phase == null) return null;
        RuntimeInvocationContext span;
        synchronized (REGISTRY) {
            Map<String, RuntimeInvocationContext> phases = ACTIVE.get(message.getConnectorMap());
            span = phases == null ? null : phases.get(phase);
        }
        if (span == null) return null;
        synchronized (span) {
            return !span.closed && !span.interrupted && span.matches(message, phase) ? span : null;
        }
    }
    private boolean matches(ImmutableConnectorMessage message, String phase) {
        return this.phase.equals(phase) && connectorMap == message.getConnectorMap() && channelMap == message.getChannelMap()
                && sourceMap == message.getSourceMap() && connectorMetadataId == message.getMetaDataId()
                && messageId == message.getMessageId() && Objects.equals(channelId, message.getChannelId())
                && Objects.equals(serverId, message.getServerId());
    }
    /** Synchronizes admission with close/cancellation. A previously resolved span
     * cannot admit work after its owner stopped waiting for the JavaScript task. */
    public synchronized Deployments.Admission admit(PythonRuntime runtime, String digest) throws Deployments.NotAdmitted {
        if (closed || interrupted || connectorMap == null) throw new Deployments.NotAdmitted("The scoped transformer invocation is no longer active");
        return runtime.deployments.admit(channelId, connectorMetadataId, phase, digest, channel);
    }
    public synchronized Deployments.Admission admitFilter(PythonRuntime runtime, String filterRole, String digest) throws Deployments.NotAdmitted {
        String expected = com.mirth.connect.plugins.python.PythonContract.ROLE_SOURCE_TRANSFORMER.equals(phase)
                ? com.mirth.connect.plugins.python.PythonContract.ROLE_SOURCE_FILTER
                : com.mirth.connect.plugins.python.PythonContract.ROLE_DESTINATION_TRANSFORMER.equals(phase)
                ? com.mirth.connect.plugins.python.PythonContract.ROLE_DESTINATION_FILTER : null;
        if (closed || interrupted || connectorMap == null || !filterRole.equals(expected))
            throw new Deployments.NotAdmitted("The matching scoped filter invocation is not active");
        return runtime.deployments.admit(channelId, connectorMetadataId, filterRole, digest, channel);
    }
    public synchronized Deployments.Admission admitTemplate(PythonRuntime runtime, String digest) throws Deployments.NotAdmitted {
        if (closed || interrupted || connectorMap == null) throw new Deployments.NotAdmitted("The scoped transformer invocation is no longer active");
        return runtime.deployments.admit(channelId, connectorMetadataId, com.mirth.connect.plugins.python.PythonContract.templateRole(phase), digest, channel);
    }
    /** An interrupted Future.get does not prove the JavaScript task exited.
     * Keep a weak identity tombstone so a late task cannot borrow a retry's span. */
    public synchronized void interrupted() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Only the invocation owner can interrupt its scope");
        interrupted = true;
        if (connectorMap != null) synchronized (REGISTRY) {
            reapQuarantine();
            QUARANTINED.computeIfAbsent(new WeakIdentity(connectorMap, COLLECTED), key -> new HashSet<>()).add(phase);
        }
    }
    private static boolean isQuarantined(Map<String, Object> map, String phase) {
        Set<String> phases = QUARANTINED.get(new WeakIdentity(map, null));
        return phases != null && phases.contains(phase);
    }
    private static void reapQuarantine() {
        WeakIdentity identity;
        while ((identity = (WeakIdentity) COLLECTED.poll()) != null) QUARANTINED.remove(identity);
    }
    private static final class WeakIdentity extends WeakReference<Map<String, Object>> {
        private final int hash;
        WeakIdentity(Map<String, Object> value, ReferenceQueue<Map<String, Object>> queue) { super(value, queue); hash = System.identityHashCode(value); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof WeakIdentity identity && get() != null && get() == identity.get();
        }
    }
    public static RuntimeInvocationContext current() { return CURRENT.get(); }
    public Channel getChannel() { return channel; }
    public int getConnectorMetadataId() { return connectorMetadataId; }
    public String getPhase() { return phase; }

    @Override public synchronized void close() {
        if (closed) return;
        if (Thread.currentThread() != owner || CURRENT.get() != this) {
            throw new IllegalStateException("Runtime invocation contexts must close on their owner thread in reverse order");
        }
        if (Thread.currentThread().isInterrupted()) interrupted();
        closed = true;
        if (connectorMap != null) synchronized (REGISTRY) {
            Map<String, RuntimeInvocationContext> phases = ACTIVE.get(connectorMap);
            if (phases != null && phases.remove(phase, this)) { activeCount--; if (phases.isEmpty()) ACTIVE.remove(connectorMap); }
        }
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
