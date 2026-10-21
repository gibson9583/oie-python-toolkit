/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirth.connect.plugins.python.PythonApi;

class ProcessSupervisorTest {
    @TempDir Path owned;
    @Test void normalCommandCompletionEndsChildrenWithClosedPipes() throws Exception {
        assumeFalse(ProcessControl.isWindows());
        Path python = Interpreters.resolve("python3");
        String source = "import subprocess,sys\n"
                + "child=subprocess.Popen([sys.executable,'-I','-c','import time;time.sleep(60)'],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)\n"
                + "print(child.pid,flush=True)\n";
        ProcessControl.Result result = ProcessControl.runPython(List.of(python.toString(), "-I", "-c", source),
                ProcessControl.baseEnvironment(owned), owned, null, 10000, null, null, 50, 4000);
        long pid = Long.parseLong(result.output.get(0));
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        try {
            Path evidence = Path.of("target", "runtime-evidence", "normal-command-descendant-after-fix.json");
            Files.createDirectories(evidence.getParent());
            Files.writeString(evidence, PythonApi.mapper().writeValueAsString(Map.of("supervisor", result.pid, "child", pid,
                    "exitCode", result.exitCode, "childExited", handle == null || !handle.isAlive(),
                    "ownershipConfirmed", ProcessControl.ownershipExitConfirmed(result.pid))));
            assertEquals(0, result.exitCode);
            assertTrue(handle == null || !handle.isAlive(), "Owned child survived successful command completion");
            assertFalse(ProcessControl.hasUncertainOwnership());
            try (var files = Files.list(owned)) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith("oie-python-command-")), "Completed exit receipt was not cleaned");
            }
        } finally {
            if (handle != null && handle.isAlive()) handle.destroyForcibly();
        }
    }
    @Test void completedCommandDoesNotPoisonTheNextProcessWithAnUncertainGroup() throws Exception {
        assumeFalse(ProcessControl.isWindows());
        Path python = Interpreters.resolve("python3");
        for (int attempt = 0; attempt < 2; attempt++) {
            var result = ProcessControl.runPython(List.of(python.toString(), "-I", "-c", "print('complete')"),
                    ProcessControl.baseEnvironment(owned), owned, null, 10000, null, null, 50, 4000);
            assertEquals(0, result.exitCode);
            assertEquals(List.of("complete"), result.output);
            assertFalse(ProcessControl.hasUncertainOwnership(), "Exited group must not block a subsequent command");
        }
    }
    @Test void supervisorRetainsTheMainCommandsNonzeroExitStatus() throws Exception {
        assumeFalse(ProcessControl.isWindows());
        Path python = Interpreters.resolve("python3");
        ProcessControl.Result result = ProcessControl.runPython(List.of(python.toString(), "-I", "-c", "import sys;sys.exit(23)"),
                ProcessControl.baseEnvironment(owned), owned, null, 10000, null, null, 50, 4000);
        assertEquals(23, result.exitCode);
        assertFalse(ProcessControl.hasUncertainOwnership());
    }
    /** A private test JVM; this is never the shared engine JVM. */
    public static final class Harness {
        public static void main(String[] arguments) throws Exception {
            Path dir = Path.of(arguments[0]);
            Path python = Interpreters.resolve("python3");
            String source = "import os,json,subprocess,sys,ctypes\n"
                    + "child=subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)'])\n"
                    + "open(" + Interpreters.quote(dir.resolve("started.json").toString()) + ",'w').write(json.dumps({'supervisor':os.getppid(),'command':os.getpid(),'child':child.pid}))\n"
                    + "ctypes.PyDLL(None).sleep(60)\n";
            ProcessControl.runPython(List.of(python.toString(), "-I", "-c", source), ProcessControl.baseEnvironment(dir), dir, null,
                    60000, null, null, 50, 4000);
        }
    }
    @Test void privateParentJvmDeathEndsPreparationEvenWhenNativeCommandHoldsGil() throws Exception {
        assumeFalse(ProcessControl.isWindows());
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process parent = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"), Harness.class.getName(), owned.toString())
                .redirectErrorStream(true).redirectOutput(owned.resolve("harness.log").toFile()).start();
        List<ProcessHandle> handles = new ArrayList<>();
        try {
            Path marker = owned.resolve("started.json");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(marker) && parent.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(marker), Files.readString(owned.resolve("harness.log")));
            @SuppressWarnings("unchecked") Map<String, Number> pids = PythonApi.mapper().readValue(Files.readString(marker), Map.class);
            for (Number pid : pids.values()) handles.add(ProcessHandle.of(pid.longValue()).orElseThrow());
            parent.destroyForcibly();
            assertTrue(parent.waitFor(5, TimeUnit.SECONDS));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (handles.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) Thread.sleep(20);
            List<Map<String, Object>> receipts = new ArrayList<>();
            for (ProcessHandle handle : handles) receipts.add(Map.of("pid", handle.pid(), "exited", !handle.isAlive()));
            Path evidence = Path.of("target", "runtime-evidence", "preparation-parent-death.json");
            Files.createDirectories(evidence.getParent());
            Files.writeString(evidence, PythonApi.mapper().writeValueAsString(Map.of("privateTestJvm", parent.pid(), "processes", receipts)));
            assertTrue(handles.stream().noneMatch(ProcessHandle::isAlive), "Preparation process tree survived its private parent JVM: " + receipts);
        } finally {
            parent.destroyForcibly(); parent.waitFor(5, TimeUnit.SECONDS);
            for (ProcessHandle handle : handles) if (handle.isAlive()) handle.destroyForcibly();
        }
    }
}
