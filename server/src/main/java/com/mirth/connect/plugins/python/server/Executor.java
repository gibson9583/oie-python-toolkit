/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Runs one Python call on a pooled worker. One total deadline covers
 * acquisition, startup, loading and execution. A call that does not end in a
 * RESULT or ERROR frame terminates its worker and is never repeated.
 */
public final class Executor {

    private static final Logger filterLogger = LogManager.getLogger("filter");
    private static final Logger transformerLogger = LogManager.getLogger("transformer");
    private static final Logger responseLogger = LogManager.getLogger("response");
    private static final Logger connectorLogger = LogManager.getLogger("js-connector");

    /** The call did not produce a script outcome. */
    public static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        public final String code;
        /** True when the script may have run partly, so external effects are uncertain. */
        public final boolean outcomeUncertain;
        public final Map<String, Long> durations;

        Failure(String code, String message, boolean outcomeUncertain, Map<String, Long> durations) {
            super(message);
            this.code = code;
            this.outcomeUncertain = outcomeUncertain;
            this.durations = durations;
        }
    }

    /** A RESULT or a script-level ERROR from the worker. */
    public static final class Outcome {
        public final Map<String, Object> reply;
        public final Map<String, Long> durations;

        Outcome(Map<String, Object> reply, Map<String, Long> durations) {
            this.reply = reply;
            this.durations = durations;
        }

        public boolean ok() {
            return "RESULT".equals(reply.get("type"));
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> result() {
            return (Map<String, Object>) reply.get("result");
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> error() {
            return (Map<String, Object>) reply.get("error");
        }

        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> logs() {
            Object logs = reply.get("logs");
            return logs instanceof List ? (List<Map<String, Object>>) logs : new ArrayList<>();
        }

        public boolean logsTruncated() {
            return Boolean.TRUE.equals(reply.get("logsTruncated"));
        }
    }

    private final PythonRuntime runtime;
    final AtomicLong calls = new AtomicLong();
    final AtomicLong failures = new AtomicLong();
    final AtomicLong timeouts = new AtomicLong();
    final AtomicLong workerStarts = new AtomicLong();

    Executor(PythonRuntime runtime) {
        this.runtime = runtime;
    }

    /** Runs an admitted deployment call. */
    public Outcome invoke(Deployments.Admission admission, Map<String, Object> context) throws Failure, InterruptedException {
        BoundSpec spec = admission.spec;
        long start = System.nanoTime();
        long deadline = start + TimeUnit.MILLISECONDS.toNanos(spec.timeoutMillis);
        Map<String, Long> durations = new LinkedHashMap<>();
        calls.incrementAndGet();
        WorkerPools.Key key = new WorkerPools.Key(admission.binding.attemptId, admission.binding.channelId, spec.metaDataId, spec.role, spec.digest,
                spec.artifact.digest);
        WorkerPools.Lease lease;
        try {
            lease = runtime.pools.acquire(key, (k, d) -> startLoaded(spec.artifact.python, spec.digest, spec.role, spec.label, spec.source, d, spec.artifact.manifest.targetFingerprint),
                    deadline);
        } catch (WorkerPools.Saturated e) {
            failures.incrementAndGet();
            durations.put("acquireMillis", millisSince(start));
            throw new Failure("POOL_SATURATED", e.getMessage(), false, durations);
        } catch (Failure e) {
            failures.incrementAndGet();
            throw e;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            failures.incrementAndGet();
            durations.put("acquireMillis", millisSince(start));
            throw new Failure("WORKER_UNAVAILABLE", e.getMessage(), false, durations);
        }
        durations.put("acquireMillis", millisSince(start));
        return execute(lease, spec.digest, spec.role, spec.label, context, deadline, start, durations);
    }

    /** Runs a one-off call on a private worker that is stopped afterwards (sample tests). */
    public Outcome invokeEphemeral(Path python, String digest, String role, String label, String source, Map<String, Object> context,
            long timeoutMillis) throws Failure, InterruptedException {
        return invokeEphemeral(python, digest, role, label, source, context, timeoutMillis, null);
    }

    public Outcome invokeEphemeral(Path python, String digest, String role, String label, String source, Map<String, Object> context,
            long timeoutMillis, String expectedTarget) throws Failure, InterruptedException {
        long start = System.nanoTime();
        long deadline = start + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        Map<String, Long> durations = new LinkedHashMap<>();
        Map<String, Object> request = executeRequest(digest, context, deadline);
        try {
            // Reject before a private worker enables dependency site files;
            // those startup files can themselves perform external operations.
            Wire.encodeFrame(loadRequest(digest, role, label, source), runtime.config.maxFrameBytes);
            Wire.encodeFrame(request, runtime.config.maxFrameBytes);
        } catch (Wire.ValueException | java.io.IOException rejected) {
            throw new Failure("REQUEST_TOO_LARGE", rejected.getMessage(), false, durations);
        }
        Worker worker = startLoaded(python, digest, role, label, source, deadline, expectedTarget);
        durations.put("startMillis", millisSince(start));
        try {
            long executeStart = System.nanoTime();
            Map<String, Object> reply = worker.call(request, deadline);
            durations.put("executeMillis", millisSince(executeStart));
            durations.put("totalMillis", millisSince(start));
            Outcome outcome = new Outcome(reply, durations);
            log(role, label, outcome);
            return outcome;
        } catch (Worker.WorkerException e) {
            durations.put("totalMillis", millisSince(start));
            throw new Failure(e.timeout ? "TIMEOUT" : "WORKER_FAILED", e.getMessage(), true, durations);
        } catch (Wire.ValueException rejectedBeforeWrite) {
            durations.put("totalMillis", millisSince(start));
            throw new Failure("REQUEST_TOO_LARGE", rejectedBeforeWrite.getMessage(), true, durations);
        } finally {
            worker.terminate("sample test complete");
        }
    }

    private Outcome execute(WorkerPools.Lease lease, String digest, String role, String label, Map<String, Object> context, long deadline, long start,
            Map<String, Long> durations) throws Failure, InterruptedException {
        long executeStart = System.nanoTime();
        boolean healthy = false;
        try {
            Map<String, Object> reply = lease.worker.call(executeRequest(digest, context, deadline), deadline);
            durations.put("executeMillis", millisSince(executeStart));
            durations.put("totalMillis", millisSince(start));
            Outcome outcome = new Outcome(reply, durations);
            healthy = outcome.ok() || !"protocol".equals(String.valueOf(outcome.error() == null ? null : outcome.error().get("kind")));
            if (!outcome.ok()) {
                failures.incrementAndGet();
            }
            log(role, label, outcome);
            return outcome;
        } catch (Worker.WorkerException e) {
            failures.incrementAndGet();
            if (e.timeout) {
                timeouts.incrementAndGet();
            }
            durations.put("totalMillis", millisSince(start));
            throw new Failure(e.timeout ? "TIMEOUT" : "WORKER_FAILED", (e.timeout ? "The Python call exceeded its deadline. " : "")
                    + e.getMessage() + " The outcome of any external operation the script started is unknown.", true, durations);
        } catch (Wire.ValueException e) {
            failures.incrementAndGet();
            healthy = true;
            throw new Failure("REQUEST_TOO_LARGE", e.getMessage(), false, durations);
        } finally {
            lease.release(healthy);
        }
    }

    private static Map<String, Object> executeRequest(String digest, Map<String, Object> context, long deadline) {
        Map<String, Object> request = Worker.frame("EXECUTE");
        request.put("spec", digest);
        request.put("deadlineMillis", Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
        request.put("context", context);
        return request;
    }

    private static Map<String, Object> loadRequest(String digest, String role, String label, String source) {
        Map<String, Object> load = Worker.frame("LOAD");
        load.put("spec", digest);
        load.put("role", com.mirth.connect.plugins.python.PythonContract.scriptRole(role));
        load.put("label", label);
        load.put("source", source);
        return load;
    }

    /** Starts a worker and loads one script into it. */
    Worker startLoaded(Path python, String digest, String role, String label, String source, long deadline) throws Failure, InterruptedException {
        return startLoaded(python, digest, role, label, source, deadline, null);
    }

    private Worker startLoaded(Path python, String digest, String role, String label, String source, long deadline, String expectedTarget) throws Failure, InterruptedException {
        long startupDeadline = Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(runtime.config.startupTimeoutMillis));
        Map<String, Object> load = loadRequest(digest, role, label, source);
        Worker worker;
        try {
            worker = Worker.start(python, runtime.workerEnvironment(), runtime.workDir(), label, runtime.config.maxFrameBytes,
                    runtime.config.terminationGraceMillis, startupDeadline);
            workerStarts.incrementAndGet();
        } catch (Worker.WorkerException e) {
            throw new Failure(e.timeout ? "STARTUP_TIMEOUT" : "WORKER_START_FAILED", e.getMessage(), e.processStarted, new LinkedHashMap<>());
        }
        try {
            worker.confirmTarget(expectedTarget);
            Map<String, Object> reply = worker.call(load, startupDeadline);
            if (!"RESULT".equals(reply.get("type"))) {
                worker.terminate("load failed");
                Object error = reply.get("error");
                throw new Failure("LOAD_FAILED", "The Python script could not be loaded: " + error, true, new LinkedHashMap<>());
            }
            worker.markLoaded(digest);
            return worker;
        } catch (Worker.WorkerException e) {
            worker.terminate("load/target confirmation failed");
            throw new Failure(e.timeout ? "STARTUP_TIMEOUT" : "WORKER_START_FAILED", e.getMessage(), true, new LinkedHashMap<>());
        } catch (InterruptedException e) {
            worker.terminate("load interrupted or rejected");
            throw e;
        } catch (RuntimeException e) {
            worker.terminate("load rejected");
            throw new Failure("LOAD_FAILED", "The Python script could not be loaded: " + e.getMessage(), true, new LinkedHashMap<>());
        }
    }

    private static void log(String role, String label, Outcome outcome) {
        Logger scriptLogger = com.mirth.connect.plugins.python.PythonContract.isFilterRole(role) ? filterLogger : role.endsWith("response-transformer") ? responseLogger
                : "reader".equals(role) || "writer".equals(role) ? connectorLogger : transformerLogger;
        for (Map<String, Object> record : outcome.logs()) {
            String level = String.valueOf(record.get("level"));
            Level l = "error".equals(level) ? Level.ERROR : "warning".equals(level) ? Level.WARN : "debug".equals(level) ? Level.DEBUG : Level.INFO;
            scriptLogger.log(l, "[" + label + "] " + record.get("message"));
        }
        if (outcome.logsTruncated()) {
            scriptLogger.warn("[" + label + "] script log output was truncated at the per-call limit");
        }
    }

    private static long millisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    public Map<String, Long> metrics() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("calls", calls.get());
        m.put("failedCalls", failures.get());
        m.put("timedOutCalls", timeouts.get());
        m.put("workerStarts", workerStarts.get());
        return m;
    }
}
