/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded pools of persistent workers. Pool identity includes the deployment
 * attempt, connector, role, spec digest and artifact, so a live interpreter is
 * never shared across deployments or scripts. Total and per-channel process
 * counts and the number of waiting callers are capped; idle workers of other
 * pools are evicted when capacity is needed.
 */
public final class WorkerPools {

    /** No worker became available before the deadline, or too many callers wait. */
    public static final class Saturated extends Exception {
        private static final long serialVersionUID = 1L;

        Saturated(String message) {
            super(message);
        }
    }

    public static final class Key {
        public final String attemptId;
        public final String channelId;
        public final int metaDataId;
        public final String role;
        public final String spec;
        public final String artifact;

        public Key(String attemptId, String channelId, int metaDataId, String role, String spec, String artifact) {
            this.attemptId = attemptId;
            this.channelId = channelId;
            this.metaDataId = metaDataId;
            this.role = role;
            this.spec = spec;
            this.artifact = artifact;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) {
                return false;
            }
            Key k = (Key) o;
            return metaDataId == k.metaDataId && attemptId.equals(k.attemptId) && channelId.equals(k.channelId) && role.equals(k.role)
                    && spec.equals(k.spec) && artifact.equals(k.artifact);
        }

        @Override
        public int hashCode() {
            return Objects.hash(attemptId, channelId, metaDataId, role, spec, artifact);
        }

        @Override
        public String toString() {
            return channelId + "/" + metaDataId + "/" + role + "/" + spec.substring(0, Math.min(12, spec.length()));
        }
    }

    /** Creates a started, loaded worker for a key. Runs without the pool lock. */
    public interface Factory {
        Worker create(Key key, long deadlineNanos) throws Exception;
    }

    /** A worker checked out of its pool. Release exactly once. */
    public final class Lease {
        public final Key key;
        public final Worker worker;
        private boolean released;

        Lease(Key key, Worker worker) {
            this.key = key;
            this.worker = worker;
        }

        /** Returns a healthy worker to its pool; otherwise stops it. */
        public void release(boolean healthy) {
            synchronized (this) {
                if (released) {
                    return;
                }
                released = true;
            }
            WorkerPools.this.release(this, healthy);
        }
    }

    private final OperatorConfig config;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition changed = lock.newCondition();
    private final Map<Key, Deque<Worker>> idle = new HashMap<>();
    private final Map<String, Integer> perChannel = new HashMap<>();
    private final Set<String> retiredAttempts = new HashSet<>();
    private final Set<Worker> busy = new HashSet<>();
    private final Map<Worker, Key> uncertain = new HashMap<>();
    private final Map<Worker, Key> stopping = new HashMap<>();
    private int total;
    private int waiting;
    private boolean shutdown;

    public WorkerPools(OperatorConfig config) {
        this.config = config;
    }

    /**
     * Checks out a worker for the key, starting one if capacity allows. Waits no
     * longer than the acquisition limit or the caller's deadline.
     */
    public Lease acquire(Key key, Factory factory, long deadlineNanos) throws Saturated, Exception {
        long acquireDeadline = Math.min(deadlineNanos, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.acquireTimeoutMillis));
        boolean reserved = false;
        lock.lock();
        try {
            boolean queued = false;
            try {
                while (true) {
                    if (ProcessControl.hasUncertainOwnership()) throw new Saturated("A previously owned Python process has not confirmed exit: " + ProcessControl.uncertainPids());
                    if (acquireDeadline - System.nanoTime() <= 0) throw new Saturated("The Python worker acquisition deadline passed");
                    if (shutdown) throw new IllegalStateException("The Python worker pools are stopped");
                    if (retiredAttempts.contains(key.attemptId)) {
                        throw new IllegalStateException("The deployment that owns this script is being undeployed");
                    }
                    Deque<Worker> pool = idle.get(key);
                    while (pool != null && !pool.isEmpty()) {
                        Worker worker = pool.pollLast();
                        if (worker.isAlive()) {
                            busy.add(worker);
                            return new Lease(key, worker);
                        }
                        forget(key, worker);
                        stopping.put(worker, key);
                        lock.unlock();
                        try { stopOwned(key, worker, "exited while idle"); }
                        finally { lock.lock(); }
                    }
                    if (total + stopping.size() < config.maxWorkers && channelCount(key.channelId) + stopping.values().stream()
                            .filter(k -> k.channelId.equals(key.channelId)).count() < config.maxWorkersPerChannel) {
                        total++;
                        perChannel.merge(key.channelId, 1, Integer::sum);
                        reserved = true;
                        break;
                    }
                    Worker victim = evictIdle(total >= config.maxWorkers ? null : key.channelId);
                    if (victim != null) {
                        Key victimKey = stopping.get(victim);
                        lock.unlock();
                        try { stopOwned(victimKey, victim, "evicted to free capacity"); }
                        finally { lock.lock(); }
                        continue;
                    }
                    if (!queued) {
                        if (waiting >= config.maxQueuedAcquisitions) {
                            throw new Saturated("All " + config.maxWorkers + " Python workers are busy and " + waiting
                                    + " calls are already waiting");
                        }
                        waiting++;
                        queued = true;
                    }
                    long remaining = acquireDeadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new Saturated("No Python worker became available within " + config.acquireTimeoutMillis + " ms");
                    }
                    changed.awaitNanos(remaining);
                }
            } finally {
                if (queued) {
                    waiting--;
                }
            }
        } finally {
            lock.unlock();
        }
        Worker worker = null;
        try {
            worker = factory.create(key, deadlineNanos);
            if (worker == null) throw new IllegalStateException("The Python worker factory returned no worker");
        } finally {
            if (worker == null && reserved) {
                lock.lock();
                try {
                    total--;
                    perChannel.merge(key.channelId, -1, Integer::sum);
                    changed.signalAll();
                } finally {
                    lock.unlock();
                }
            }
        }
        lock.lock();
        boolean rejected;
        try {
            rejected = shutdown || retiredAttempts.contains(key.attemptId);
            if (rejected) {
                forget(key, worker);
                stopping.put(worker, key);
                changed.signalAll();
            } else {
                busy.add(worker);
            }
        } finally {
            lock.unlock();
        }
        if (rejected) {
            stopOwned(key, worker, "deployment retired during worker startup");
            throw new IllegalStateException("The deployment stopped while its Python worker was starting");
        }
        return new Lease(key, worker);
    }

    private void release(Lease lease, boolean healthy) {
        Worker stop = null;
        lock.lock();
        try {
            busy.remove(lease.worker);
            boolean keep = !shutdown && healthy && lease.worker.isAlive() && !retiredAttempts.contains(lease.key.attemptId)
                    && lease.worker.invocations() < config.recycleAfterInvocations;
            if (keep) {
                idle.computeIfAbsent(lease.key, k -> new ArrayDeque<>()).addLast(lease.worker);
            } else {
                forget(lease.key, lease.worker);
                stopping.put(lease.worker, lease.key);
                stop = lease.worker;
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        if (stop != null) {
            stopOwned(lease.key, stop, healthy ? "recycled" : "released after a failed call");
        }
    }

    private void stopOwned(Key key, Worker worker, String reason) {
        worker.terminate(reason);
        lock.lock();
        try {
            stopping.remove(worker);
            if (!worker.terminationConfirmed()) {
                if (uncertain.putIfAbsent(worker, key) == null) {
                    total++;
                    perChannel.merge(key.channelId, 1, Integer::sum);
                }
            }
            changed.signalAll();
        } finally { lock.unlock(); }
    }

    /** Retired artifacts remain leased while any owned worker has uncertain exit. */
    public boolean retirementComplete(String attemptId) {
        lock.lock();
        try {
            for (Iterator<Map.Entry<Worker, Key>> it = uncertain.entrySet().iterator(); it.hasNext();) {
                Map.Entry<Worker, Key> entry = it.next();
                if (entry.getKey().terminationConfirmed()) {
                    total--;
                    perChannel.merge(entry.getValue().channelId, -1, Integer::sum);
                    it.remove();
                    changed.signalAll();
                }
            }
            return !ProcessControl.hasUncertainOwnership() && uncertain.values().stream().noneMatch(k -> attemptId.equals(k.attemptId))
                    && stopping.values().stream().noneMatch(k -> attemptId.equals(k.attemptId));
        } finally { lock.unlock(); }
    }

    private void forget(Key key, Worker worker) {
        total--;
        perChannel.merge(key.channelId, -1, Integer::sum);
        Deque<Worker> pool = idle.get(key);
        if (pool != null) {
            pool.remove(worker);
            if (pool.isEmpty()) {
                idle.remove(key);
            }
        }
    }

    private int channelCount(String channelId) {
        return perChannel.getOrDefault(channelId, 0);
    }

    /** Removes the least recently used idle worker, optionally limited to one channel. */
    private Worker evictIdle(String channelId) {
        Key bestKey = null;
        Worker best = null;
        for (Map.Entry<Key, Deque<Worker>> entry : idle.entrySet()) {
            if (channelId != null && !channelId.equals(entry.getKey().channelId)) {
                continue;
            }
            for (Worker worker : entry.getValue()) {
                if (best == null || worker.lastUsedNanos() < best.lastUsedNanos()) {
                    best = worker;
                    bestKey = entry.getKey();
                }
            }
        }
        if (best != null) {
            forget(bestKey, best);
            stopping.put(best, bestKey);
        }
        return best;
    }

    /** Stops workers idle longer than the eviction limit. */
    public void evictExpired() {
        Map<Worker, Key> stop = new HashMap<>();
        long limit = TimeUnit.MILLISECONDS.toNanos(config.idleEvictMillis);
        lock.lock();
        try {
            long now = System.nanoTime();
            for (Iterator<Map.Entry<Key, Deque<Worker>>> it = idle.entrySet().iterator(); it.hasNext();) {
                Map.Entry<Key, Deque<Worker>> entry = it.next();
                for (Iterator<Worker> w = entry.getValue().iterator(); w.hasNext();) {
                    Worker worker = w.next();
                    if (now - worker.lastUsedNanos() > limit || !worker.isAlive()) {
                        w.remove();
                        total--;
                        perChannel.merge(entry.getKey().channelId, -1, Integer::sum);
                        stop.put(worker, entry.getKey());
                        stopping.put(worker, entry.getKey());
                    }
                }
                if (entry.getValue().isEmpty()) {
                    it.remove();
                }
            }
            if (!stop.isEmpty()) {
                changed.signalAll();
            }
        } finally {
            lock.unlock();
        }
        stopEvery(stop.keySet(), stop, "idle");
    }

    /**
     * Rejects new acquisitions for a deployment attempt and stops its idle
     * workers. Busy workers stop when released.
     */
    public void retire(String attemptId) {
        Map<Worker, Key> stop = new HashMap<>();
        lock.lock();
        try {
            retiredAttempts.add(attemptId);
            for (Iterator<Map.Entry<Key, Deque<Worker>>> it = idle.entrySet().iterator(); it.hasNext();) {
                Map.Entry<Key, Deque<Worker>> entry = it.next();
                if (entry.getKey().attemptId.equals(attemptId)) {
                    for (Worker worker : entry.getValue()) {
                        total--;
                        perChannel.merge(entry.getKey().channelId, -1, Integer::sum);
                        stop.put(worker, entry.getKey());
                        stopping.put(worker, entry.getKey());
                    }
                    it.remove();
                }
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        stopEvery(stop.keySet(), stop, "deployment retired");
    }

    /** Stops every worker, busy or idle. Used at shutdown. */
    public void shutdown() {
        List<Worker> stop = new ArrayList<>();
        Map<Worker, Key> idleStop = new HashMap<>();
        lock.lock();
        try {
            shutdown = true;
            for (Map.Entry<Key, Deque<Worker>> entry : idle.entrySet()) {
                stop.addAll(entry.getValue());
                for (Worker worker : entry.getValue()) {
                    idleStop.put(worker, entry.getKey());
                    stopping.put(worker, entry.getKey());
                }
                total -= entry.getValue().size();
                perChannel.merge(entry.getKey().channelId, -entry.getValue().size(), Integer::sum);
            }
            stop.addAll(busy);
            idle.clear();
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        stopEvery(stop, idleStop, "shutdown");
    }

    /** An unexpected worker failure cannot skip cleanup of other owned workers.
     * Failed idle ownership remains in stopping until exit can be established. */
    private void stopEvery(Iterable<Worker> workers, Map<Worker, Key> owned, String reason) {
        Throwable failure = null;
        for (Worker worker : workers) {
            try {
                Key key = owned.get(worker);
                if (key == null) worker.terminate(reason); else stopOwned(key, worker, reason);
            } catch (Throwable caught) {
                if (failure == null) failure = caught;
                else if (caught != failure) failure.addSuppressed(caught);
            }
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException error) throw error;
        if (failure != null) throw new IllegalStateException("Python worker cleanup did not complete", failure);
    }

    public Map<String, Long> metrics() {
        lock.lock();
        try {
            Map<String, Long> m = new java.util.LinkedHashMap<>();
            m.put("workers", (long) total + stopping.size());
            m.put("busyWorkers", (long) busy.size());
            m.put("waitingCalls", (long) waiting);
            m.put("uncertainWorkerOwnership", (long) uncertain.size());
            return m;
        } finally {
            lock.unlock();
        }
    }
}
