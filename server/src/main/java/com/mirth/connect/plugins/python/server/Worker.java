/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * One persistent CPython worker. A worker serves one call at a time. Pipe
 * reads and writes run on dedicated threads so every wait is bounded by a
 * deadline; any protocol fault, timeout or interruption terminates the process
 * tree and the worker is never reused.
 */
public final class Worker {

    private static final Logger logger = LogManager.getLogger(Worker.class);
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** The call ended without a result frame; the worker is gone. */
    public static final class WorkerException extends Exception {
        private static final long serialVersionUID = 1L;
        public final boolean timeout;
        /** Dependency site startup may run external code once the process starts. */
        public final boolean processStarted;

        WorkerException(String message, boolean timeout, Throwable cause) {
            this(message, timeout, cause, true);
        }

        WorkerException(String message, boolean timeout, Throwable cause, boolean processStarted) {
            super(message, cause);
            this.timeout = timeout;
            this.processStarted = processStarted;
        }
    }

    private static final Object EOF = new Object();

    private final String label;
    private final Process process;
    private final OutputStream input;
    private final int maxFrameBytes;
    private final long graceMillis;
    private final ExecutorService writer;
    private final LinkedBlockingQueue<Object> frames = new LinkedBlockingQueue<>(2);
    private final Deque<String> stderrTail = new ArrayDeque<>();
    private final AtomicBoolean terminated = new AtomicBoolean();
    private final Set<String> loaded = new HashSet<>();
    private volatile String terminationReason;
    private volatile boolean terminationConfirmed;
    private final List<ProcessHandle> ownedDescendants = new ArrayList<>();
    private Map<String, Object> hello;
    private int invocations;
    private long lastUsedNanos = System.nanoTime();

    private Worker(String label, Process process, int maxFrameBytes, long graceMillis) {
        this.label = label;
        this.process = process;
        this.input = process.getOutputStream();
        this.maxFrameBytes = maxFrameBytes;
        this.graceMillis = graceMillis;
        int id = SEQUENCE.incrementAndGet();
        this.writer = Executors.newSingleThreadExecutor(r -> daemon(r, "python-worker-" + id + "-write"));
        InputStream out = process.getInputStream();
        daemon(() -> {
            try {
                while (true) {
                    Map<String, Object> frame = Wire.readFrame(out, maxFrameBytes);
                    if (frame == null) {
                        break;
                    }
                    if (!frames.offer(frame)) {
                        frames.clear();
                        frames.offer(new Wire.ProtocolException("Unsolicited worker frames exceed the pending-reply limit"));
                        terminate("unsolicited protocol output");
                        return;
                    }
                }
                frames.offer(EOF);
            } catch (IOException e) {
                frames.offer(e);
            }
        }, "python-worker-" + id + "-read").start();
        InputStream err = process.getErrorStream();
        daemon(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
                String line;
                while ((line = ProcessControl.readBoundedLine(in, 2000)) != null) {
                    String bounded = line.length() > 2000 ? line.substring(0, 2000) + "..." : line;
                    synchronized (stderrTail) {
                        stderrTail.addLast(bounded);
                        if (stderrTail.size() > 50) {
                            stderrTail.removeFirst();
                        }
                    }
                    logger.debug("[" + this.label + "] " + bounded);
                }
            } catch (IOException e) {
                // Process ended.
            }
        }, "python-worker-" + id + "-stderr").start();
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /**
     * Starts a worker and completes the HELLO handshake within the deadline.
     * The interpreter must report the expected protocol and SDK version.
     */
    public static Worker start(Path python, Map<String, String> env, Path workDir, String label, int maxFrameBytes, long graceMillis,
            long deadlineNanos) throws WorkerException, InterruptedException {
        if (deadlineNanos - System.nanoTime() <= 0) throw new WorkerException("The worker startup deadline passed before process creation", true, null, false);
        if (ProcessControl.hasUncertainOwnership()) throw new WorkerException("Owned Python process exit is uncertain: " + ProcessControl.uncertainPids(), false, null, false);
        String sdkSource = env.get("OIE_PYTHON_SDK_SOURCE");
        if (sdkSource == null || sdkSource.isEmpty()) throw new WorkerException("The trusted SDK bootstrap path is required before interpreter startup", false, null, false);
        String bootstrap = "import sys; sys.path.insert(0, " + Interpreters.quote(sdkSource)
                + "); from oie_python.worker import main; main()";
        List<String> command = Arrays.asList(python.toString(), "-I", "-S", "-c", bootstrap);
        Process process;
        try {
            process = ProcessControl.builder(command, env, workDir).start();
        } catch (IOException e) {
            throw new WorkerException("Cannot start Python (" + python + "): " + e.getMessage(), false, e, false);
        }
        Worker worker = new Worker(label, process, maxFrameBytes, graceMillis);
        try {
            Map<String, Object> hello = worker.call(frame("HELLO"), deadlineNanos);
            if (!"HELLO".equals(hello.get("type"))) {
                throw new WorkerException("Python worker handshake failed: " + hello, false, null);
            }
            Object protocol = hello.get("protocol");
            if (!(protocol instanceof Number) || ((Number) protocol).intValue() != Sdk.PROTOCOL_VERSION) {
                throw new WorkerException("Python worker speaks protocol " + protocol + "; expected " + Sdk.PROTOCOL_VERSION, false, null);
            }
            if (!Sdk.VERSION.equals(hello.get("sdk"))) {
                throw new WorkerException("Python environment has SDK " + hello.get("sdk") + "; expected " + Sdk.VERSION
                        + ". Prepare the environment again.", false, null);
            }
            Object contract = hello.get("contract");
            if (!(contract instanceof Number) || ((Number) contract).intValue() != com.mirth.connect.plugins.python.PythonContract.CONTRACT_VERSION) {
                throw new WorkerException("Python worker has contract " + contract + "; expected " + com.mirth.connect.plugins.python.PythonContract.CONTRACT_VERSION, false, null);
            }
            if (!(hello.get("pid") instanceof Number) || ((Number) hello.get("pid")).longValue() != process.pid()) {
                throw new WorkerException("Python worker did not confirm its process identity", false, null);
            }
            if (!ProcessControl.isWindows() && !Boolean.TRUE.equals(hello.get("processGroupLeader"))) {
                throw new WorkerException("Python worker did not establish its owned process group", false, null);
            }
            worker.hello = hello;
            return worker;
        } catch (WorkerException | InterruptedException | RuntimeException e) {
            worker.terminate("startup failed");
            throw e;
        }
    }

    static Map<String, Object> frame(String type) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type);
        frame.put("id", UUID.randomUUID().toString());
        return frame;
    }

    /**
     * Sends one request and waits for its reply. Returns RESULT, HELLO or ERROR
     * frames. Any other outcome terminates the worker and throws.
     */
    public synchronized Map<String, Object> call(Map<String, Object> request, long deadlineNanos) throws WorkerException, InterruptedException {
        if (terminated.get()) {
            throw new WorkerException("Python worker was already stopped: " + terminationReason, false, null);
        }
        String id = String.valueOf(request.get("id"));
        byte[] bytes;
        try {
            bytes = Wire.encodeFrame(request, maxFrameBytes);
        } catch (IOException e) {
            throw new WorkerException("Cannot encode the request: " + e.getMessage(), false, e);
        }
        Future<?> write;
        try {
            write = writer.submit(() -> { Wire.writeFrame(input, bytes); return null; });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            throw fail("Python worker stopped before its request was written", stopped);
        }
        try {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException();
            }
            write.get(remaining, TimeUnit.NANOSECONDS);
            while (true) {
                remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    throw new TimeoutException();
                }
                Object next = frames.poll(remaining, TimeUnit.NANOSECONDS);
                if (next == null) {
                    throw new TimeoutException();
                }
                if (next == EOF) {
                    throw fail("Python worker exited" + exitDescription(), null);
                }
                if (next instanceof Throwable) {
                    throw fail("Python worker protocol failure: " + ((Throwable) next).getMessage(), (Throwable) next);
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> reply = (Map<String, Object>) next;
                if (!id.equals(String.valueOf(reply.get("id")))) {
                    throw fail("Python worker answered request " + reply.get("id") + " while " + id + " was pending", null);
                }
                String type = String.valueOf(reply.get("type"));
                boolean valid = "HELLO".equals(request.get("type")) ? "HELLO".equals(type) : "RESULT".equals(type) || "ERROR".equals(type);
                if (!valid || ("RESULT".equals(type) && !(reply.get("result") instanceof Map))
                        || ("ERROR".equals(type) && !(reply.get("error") instanceof Map))) {
                    throw fail("Python worker returned a malformed " + type + " response", null);
                }
                Object logs = reply.get("logs");
                if (logs != null) {
                    if (!(logs instanceof List) || ((List<?>) logs).size() > 1024) throw fail("Python worker returned malformed or excessive logs", null);
                    for (Object record : (List<?>) logs) {
                        if (!(record instanceof Map) || !(((Map<?, ?>) record).get("message") instanceof String)
                                || !(((Map<?, ?>) record).get("level") instanceof String)) throw fail("Python worker returned malformed log records", null);
                    }
                }
                if ("ERROR".equals(type)) {
                    Map<?, ?> error = (Map<?, ?>) reply.get("error");
                    if (!(error.get("kind") instanceof String) || !(error.get("message") instanceof String)) throw fail("Python worker returned malformed error details", null);
                }
                // A deadline/cancellation wins even when a result was queued
                // immediately before the waiting thread woke up.
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (terminated.get()) throw fail("Python worker stopped before its result was accepted", null);
                if (deadlineNanos - System.nanoTime() <= 0) throw new TimeoutException();
                invocations++;
                lastUsedNanos = System.nanoTime();
                return reply;
            }
        } catch (TimeoutException e) {
            write.cancel(true);
            terminate("deadline exceeded");
            throw new WorkerException("Python call exceeded its deadline", true, null);
        } catch (InterruptedException e) {
            write.cancel(true);
            terminate("interrupted");
            throw e;
        } catch (java.util.concurrent.ExecutionException e) {
            throw fail("Cannot write to the Python worker: " + e.getCause().getMessage(), e.getCause());
        }
    }

    private WorkerException fail(String message, Throwable cause) {
        terminate(message);
        return new WorkerException(message + stderrSummary(), false, cause);
    }

    private String exitDescription() {
        try {
            if (process.waitFor(500, TimeUnit.MILLISECONDS)) {
                return " with status " + process.exitValue();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "";
    }

    private String stderrSummary() {
        synchronized (stderrTail) {
            if (stderrTail.isEmpty()) {
                return "";
            }
            List<String> tail = new ArrayList<>(stderrTail);
            return "\nPython output:\n" + String.join("\n", tail.subList(Math.max(0, tail.size() - 10), tail.size()));
        }
    }

    /** Stops the process tree. Safe to call repeatedly and from any thread. */
    public void terminate(String reason) {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }
        terminationReason = reason;
        // Killing before closing the pipe unblocks a writer holding the
        // OutputStream monitor; close-first can itself block forever.
        synchronized (ownedDescendants) {
            process.descendants().forEach(ownedDescendants::add);
        }
        terminationConfirmed = ProcessControl.killTree(process, graceMillis);
        try {
            input.close();
        } catch (IOException e) {
            // Already closed.
        }
        writer.shutdownNow();
        logger.debug("Stopped Python worker [" + label + "] pid " + process.pid() + ": " + reason);
    }

    public boolean isAlive() {
        return !terminated.get() && process.isAlive();
    }

    /** True only when the owned leader and every observed child have exited. */
    public boolean terminationConfirmed() {
        if (!terminated.get() || process.isAlive()) return false;
        synchronized (ownedDescendants) {
            terminationConfirmed = ownedDescendants.stream().noneMatch(ProcessHandle::isAlive) && ProcessControl.ownershipExitConfirmed(process.pid());
        }
        return terminationConfirmed;
    }

    public boolean isLoaded(String spec) {
        return loaded.contains(spec);
    }

    public void markLoaded(String spec) {
        loaded.add(spec);
    }

    public int invocations() {
        return invocations;
    }

    public long lastUsedNanos() {
        return lastUsedNanos;
    }

    public long pid() {
        return process.pid();
    }

    public Map<String, Object> hello() {
        return hello;
    }

    public void confirmTarget(String expectedFingerprint) throws WorkerException {
        if (expectedFingerprint == null) return;
        if (!(hello.get("python") instanceof Map)) throw fail("Python worker omitted its actual target identity", null);
        Map<?, ?> python = (Map<?, ?>) hello.get("python");
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("policyVersion", Interpreters.TARGET_POLICY_VERSION);
        for (String field : new String[]{"implementation", "version", "soabi", "platform", "machine", "system", "installerBootstrap"}) {
            identity.put(field, python.get(field));
        }
        if (!expectedFingerprint.equals(com.mirth.connect.plugins.python.CanonicalJson.sha256(identity))) {
            throw fail("The Python worker's actual interpreter/installer target differs from the prepared artifact. Prepare again.", null);
        }
    }

    public String label() {
        return label;
    }
}
