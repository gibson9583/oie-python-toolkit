/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Starts and stops owned processes. Commands are argument arrays; no shell is
 * involved. Environments start empty and receive only approved variables.
 */
public final class ProcessControl {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final Map<Long, List<ProcessHandle>> UNCERTAIN = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<Long> UNCERTAIN_GROUPS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private ProcessControl() {}

    /** Thrown when a process outlives its deadline or its owner cancels it. */
    public static final class Stopped extends Exception {
        private static final long serialVersionUID = 1L;
        public final boolean cancelled;
        public final boolean ownershipConfirmed;

        Stopped(String message, boolean cancelled) {
            this(message, cancelled, true);
        }

        Stopped(String message, boolean cancelled, boolean ownershipConfirmed) {
            super(message);
            this.cancelled = cancelled;
            this.ownershipConfirmed = ownershipConfirmed;
        }
    }

    public static final class OwnershipUncertain extends IOException {
        private static final long serialVersionUID = 1L;
        OwnershipUncertain(String message) { super(message); }
    }

    public static final class OwnedInterrupted extends InterruptedException {
        private static final long serialVersionUID = 1L;
        public final boolean ownershipConfirmed;
        OwnedInterrupted(boolean confirmed) { ownershipConfirmed = confirmed; }
    }

    /** A cancellation flag the caller can set from another thread. */
    public interface Cancellation {
        boolean isCancelled();
    }

    public static final class Result {
        public final int exitCode;
        public final List<String> output;
        public final Long pid;

        Result(int exitCode, List<String> output) {
            this(exitCode, output, null);
        }

        Result(int exitCode, List<String> output, Long pid) {
            this.exitCode = exitCode;
            this.output = output;
            this.pid = pid;
        }

        public String tail(int lines) {
            return String.join("\n", output.subList(Math.max(0, output.size() - lines), output.size()));
        }
    }

    /**
     * The base environment for every owned Python process. Python and pip
     * configuration from the engine's environment is never inherited.
     */
    public static Map<String, String> baseEnvironment(Path home) {
        Map<String, String> env = new TreeMap<>();
        for (String name : new String[] { "PATH", "SYSTEMROOT", "SystemRoot", "COMSPEC", "WINDIR", "TMPDIR", "TEMP", "TMP", "LANG", "LC_ALL" }) {
            String value = System.getenv(name);
            if (value != null) {
                env.put(name, value);
            }
        }
        env.put("HOME", home.toString());
        env.put("USERPROFILE", home.toString());
        env.put("PYTHONUTF8", "1");
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("PYTHONNOUSERSITE", "1");
        env.put("PYTHONDONTWRITEBYTECODE", "1");
        env.put("PIP_CONFIG_FILE", WINDOWS ? "NUL" : "/dev/null");
        env.put("PIP_DISABLE_PIP_VERSION_CHECK", "1");
        env.put("PIP_NO_INPUT", "1");
        env.put("PIP_NO_COLOR", "1");
        return env;
    }

    public static ProcessBuilder builder(List<String> command, Map<String, String> env, Path workingDir) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().clear();
        pb.environment().putAll(env);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        return pb;
    }

    /**
     * Runs a command to completion, streaming merged output lines to the sink.
     * The process tree is killed when the deadline passes or the cancellation
     * flag is set.
     */
    public static Result run(List<String> command, Map<String, String> env, Path workingDir, byte[] stdin, long timeoutMillis,
            Cancellation cancellation, Consumer<String> sink, int maxRetainedLines) throws IOException, Stopped, InterruptedException {
        return run(command, env, workingDir, stdin, timeoutMillis, cancellation, sink, maxRetainedLines, 4000);
    }

    /** Runs an approved Python command in its own POSIX process group. */
    public static Result runPython(List<String> command, Map<String, String> env, Path workingDir, byte[] stdin, long timeoutMillis,
            Cancellation cancellation, Consumer<String> sink, int maxRetainedLines, int maxLineChars) throws IOException, Stopped, InterruptedException {
        if (WINDOWS) return run(command, env, workingDir, stdin, timeoutMillis, cancellation, sink, maxRetainedLines, maxLineChars);
        Path receipt = workingDir == null ? Files.createTempFile("oie-python-command-", ".exit")
                : Files.createTempFile(workingDir, "oie-python-command-", ".exit");
        try {
            List<String> isolated = new ArrayList<>();
            isolated.add(command.get(0));
            isolated.add("-I");
            isolated.add("-S"); // Establish containment before any dependency .pth startup code.
            isolated.add("-c");
            isolated.add("import os,sys,time,subprocess,signal\n"
                    + "owner=" + ProcessHandle.current().pid() + "\n"
                    + "if os.getppid()!=owner: sys.exit(1)\n"
                    + "os.setsid()\nreceipt=os.open(sys.argv[1],os.O_WRONLY|os.O_TRUNC)\n"
                    + "try:\n"
                    + " child=subprocess.Popen(sys.argv[2:])\n"
                    + " while child.poll() is None:\n"
                    + "  if os.getppid()!=owner: os.killpg(os.getpid(),signal.SIGKILL)\n"
                    + "  time.sleep(0.1)\n"
                    + " os.write(receipt,str(child.returncode).encode('ascii'))\n"
                    + "finally:\n"
                    + " os.close(receipt)\n"
                    + " os.killpg(os.getpid(),signal.SIGKILL)\n");
            isolated.add(receipt.toString());
            isolated.addAll(command);
            Result result = run(isolated, env, workingDir, stdin, timeoutMillis, cancellation, sink, maxRetainedLines, maxLineChars);
            String recorded = Files.readString(receipt, StandardCharsets.US_ASCII);
            if (!recorded.matches("-?[0-9]{1,10}")) return result; // Supervisor failed before completing the command.
            try { return new Result(Integer.parseInt(recorded), result.output, result.pid); }
            catch (NumberFormatException invalid) { throw new IOException("Owned Python command returned a malformed exit receipt", invalid); }
        } finally {
            if (!hasUncertainOwnership()) Files.deleteIfExists(receipt);
        }
    }

    static Result run(List<String> command, Map<String, String> env, Path workingDir, byte[] stdin, long timeoutMillis,
            Cancellation cancellation, Consumer<String> sink, int maxRetainedLines, int maxLineChars) throws IOException, Stopped, InterruptedException {
        if (hasUncertainOwnership()) throw new OwnershipUncertain("A previously owned Python process has not confirmed exit: " + uncertainPids());
        if (cancellation != null && cancellation.isCancelled()) throw new Stopped("Cancelled", true);
        if (timeoutMillis <= 0) throw new Stopped("Timed out", false);
        ProcessBuilder pb = builder(command, env, workingDir);
        pb.redirectErrorStream(true);
        if (stdin == null) {
            pb.redirectInput(ProcessBuilder.Redirect.from(new File(WINDOWS ? "NUL" : "/dev/null")));
        }
        Process process = pb.start();
        List<String> retained = new ArrayList<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = readBoundedLine(in, maxLineChars)) != null) {
                    if (sink != null) {
                        sink.accept(line);
                    }
                    synchronized (retained) {
                        retained.add(line);
                        if (retained.size() > maxRetainedLines) {
                            retained.remove(0);
                        }
                    }
                }
            } catch (IOException e) {
                // The process ended or was killed.
            }
        }, "python-runtime-output");
        reader.setDaemon(true);
        reader.start();
        Thread writer = null;
        if (stdin != null) {
            writer = new Thread(() -> {
                try (OutputStream out = process.getOutputStream()) {
                    out.write(stdin);
                } catch (IOException e) {
                    // The process closed its input early; its exit status reports the problem.
                }
            }, "python-runtime-input");
            writer.setDaemon(true);
            writer.start();
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (cancellation != null && cancellation.isCancelled()) {
                    boolean confirmed = killTree(process, 0);
                    throw new Stopped("Cancelled", true, confirmed);
                }
                if (System.nanoTime() - deadline > 0) {
                    boolean confirmed = killTree(process, 0);
                    throw new Stopped("Timed out after " + timeoutMillis + " ms", false, confirmed);
                }
            }
        } catch (InterruptedException e) {
            throw new OwnedInterrupted(killTree(process, 0));
        }
        if (cancellation != null && cancellation.isCancelled()) throw new Stopped("Cancelled", true, killTree(process, 0));
        // A successful command may leave children behind with their stdio
        // closed. Stop and verify the owned group before releasing its files,
        // retaining the main command's exit status for the caller.
        if (!killTree(process, 0)) throw new OwnershipUncertain("Owned Python command pid " + process.pid() + " left processes alive after completion; retain its working resources");
        reader.join(TimeUnit.SECONDS.toMillis(1));
        if (writer != null) writer.join(TimeUnit.SECONDS.toMillis(1));
        if (reader.isAlive() || writer != null && writer.isAlive()) {
            boolean confirmed = killTree(process, 0);
            reader.join(TimeUnit.SECONDS.toMillis(1));
            if (!confirmed || reader.isAlive() || writer != null && writer.isAlive()) {
                throw new OwnershipUncertain("Owned Python command pid " + process.pid() + " did not close its process pipes; retain its working resources");
            }
        }
        synchronized (retained) {
            return new Result(process.exitValue(), new ArrayList<>(retained), process.pid());
        }
    }

    /**
     * Ends a process and everything it started. A worker leads its own process
     * group, so the group signal also reaches descendants that have already
     * been reparented. Waits up to the grace period for the exit.
     */
    public static boolean killTree(Process process, long graceMillis) {
        if (process == null) {
            return true;
        }
        long pid = process.pid();
        List<ProcessHandle> descendants = new ArrayList<>();
        process.descendants().forEach(descendants::add);
        if (!WINDOWS) {
            try {
                Process kill = new ProcessBuilder("kill", "-KILL", "-" + pid).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                kill.waitFor(2, TimeUnit.SECONDS);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        for (ProcessHandle handle : descendants) {
            handle.destroyForcibly();
        }
        process.destroyForcibly();
        try {
            process.waitFor(Math.max(graceMillis, 1000), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long descendantsDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(graceMillis, 1000));
        boolean groupAlive = !WINDOWS && groupExists(pid);
        while ((descendants.stream().anyMatch(ProcessHandle::isAlive) || groupAlive) && System.nanoTime() < descendantsDeadline) {
            try { Thread.sleep(10); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            groupAlive = !WINDOWS && groupExists(pid);
        }
        boolean confirmed = !process.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive) && !groupAlive;
        if (groupAlive) UNCERTAIN_GROUPS.add(pid);
        if (!confirmed) {
            List<ProcessHandle> receipt = new ArrayList<>(descendants);
            receipt.add(process.toHandle());
            UNCERTAIN.put(pid, receipt);
        }
        return confirmed;
    }

    /** Refuse new process creation/collection until every recorded owner has exited. */
    public static boolean hasUncertainOwnership() {
        UNCERTAIN.entrySet().removeIf(entry -> entry.getValue().stream().noneMatch(ProcessHandle::isAlive));
        UNCERTAIN_GROUPS.removeIf(pid -> !groupExists(pid));
        return !UNCERTAIN.isEmpty() || !UNCERTAIN_GROUPS.isEmpty();
    }

    public static List<Long> uncertainPids() {
        hasUncertainOwnership();
        java.util.Set<Long> pids = new java.util.TreeSet<>(UNCERTAIN_GROUPS);
        for (List<ProcessHandle> receipt : UNCERTAIN.values()) for (ProcessHandle process : receipt) if (process.isAlive()) pids.add(process.pid());
        return new ArrayList<>(pids);
    }

    static boolean ownershipExitConfirmed(long pid) {
        UNCERTAIN.computeIfPresent(pid, (key, receipt) -> receipt.stream().anyMatch(ProcessHandle::isAlive) ? receipt : null);
        if (UNCERTAIN_GROUPS.contains(pid) && !groupExists(pid)) UNCERTAIN_GROUPS.remove(pid);
        return !UNCERTAIN.containsKey(pid) && !UNCERTAIN_GROUPS.contains(pid);
    }

    /** A group can outlive its leader and no longer appear in descendants(). */
    private static boolean groupExists(long pid) {
        Process check = null;
        try {
            check = new ProcessBuilder("kill", "-0", "-" + pid).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!check.waitFor(2, TimeUnit.SECONDS)) return true;
            return check.exitValue() == 0;
        } catch (IOException | InterruptedException unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            return true;
        } finally {
            if (check != null && check.isAlive()) check.destroyForcibly();
        }
    }

    /** Drains a whole line while retaining at most the configured characters. */
    static String readBoundedLine(BufferedReader in, int limit) throws IOException {
        StringBuilder value = new StringBuilder(Math.min(Math.max(limit, 0), 4096));
        boolean seen = false;
        int next;
        while ((next = in.read()) != -1) {
            seen = true;
            if (next == '\n') break;
            if (value.length() < limit) value.append((char) next);
        }
        if (!seen) return null;
        if (!value.isEmpty() && value.charAt(value.length() - 1) == '\r') value.setLength(value.length() - 1);
        return value.toString();
    }

    /** The interpreter inside a virtual environment. */
    public static Path venvPython(Path venv) {
        return WINDOWS ? venv.resolve("Scripts").resolve("python.exe") : venv.resolve("bin").resolve("python");
    }

    public static boolean isWindows() {
        return WINDOWS;
    }

    /** Deletes a plugin-owned directory tree. Refuses paths outside the owned root. */
    public static void deleteOwnedTree(Path root, Path target) throws IOException {
        Path owned = root.toAbsolutePath().normalize();
        Path path = target.toAbsolutePath().normalize();
        if (!path.startsWith(owned) || path.equals(owned)) {
            throw new IOException("Refusing to delete " + path + " outside " + owned);
        }
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(path)) {
            List<Path> all = new ArrayList<>();
            walk.forEach(all::add);
            for (int i = all.size() - 1; i >= 0; i--) {
                Files.deleteIfExists(all.get(i));
            }
        }
    }
}
