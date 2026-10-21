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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerProcessTest {
    @TempDir static Path owned;
    static Path evidence;
    static Path python;
    static Map<String, String> env;
    static final List<Worker> workers = new ArrayList<>();

    @BeforeAll static void prepare() throws Exception {
        evidence = Path.of("target", "runtime-evidence").toAbsolutePath();
        Files.createDirectories(evidence);
        Path hostPython = Interpreters.resolve("python3");
        assertNotNull(hostPython);
        Files.createDirectories(owned.resolve("home"));
        env = ProcessControl.baseEnvironment(owned.resolve("home"));
        Sdk sdk = new Sdk(owned);
        env.put("OIE_PYTHON_SDK_SOURCE", sdk.sourceDir().toString());
        Path auditInput = evidence.resolve("content-addressed").resolve(sdk.wheelSha256());
        Files.createDirectories(auditInput.getParent());
        if (!Files.exists(auditInput)) Files.copy(sdk.wheelFile(), auditInput);
        Path venv = owned.resolve("venv");
        var created = ProcessControl.runPython(List.of(hostPython.toString(), "-I", "-m", "venv", venv.toString()), env, owned, null,
                30000, null, null, 50, 4000);
        assertEquals(0, created.exitCode, created.tail(10));
        python = ProcessControl.venvPython(venv);
        var installed = ProcessControl.runPython(List.of(python.toString(), "-I", "-m", "pip", "install", "--no-index", "--no-deps", sdk.wheelFile().toString()), env,
                owned, null, 30000, null, null, 50, 4000);
        Files.writeString(evidence.resolve("sdk-install.log"), installed.tail(50) + "\nSDK SHA-256: " + sdk.wheelSha256() + "\n");
        assertEquals(0, installed.exitCode, installed.tail(10));
    }

    @AfterAll static void cleanup() throws Exception {
        StringBuilder receipt = new StringBuilder();
        boolean confirmed = true;
        for (Worker worker : workers) {
            worker.terminate("test finished");
            confirmed &= worker.terminationConfirmed();
            receipt.append("pid=").append(worker.pid()).append(" terminated=").append(worker.terminationConfirmed()).append('\n');
        }
        Files.writeString(evidence.resolve("worker-process-receipts.log"), receipt.toString());
        assertTrue(confirmed, "Owned resources retained because process termination was not confirmed: " + owned);
        ProcessControl.deleteOwnedTree(owned, owned.resolve("venv"));
        Files.writeString(evidence.resolve("cleanup.log"), "All owned workers exited; isolated virtual environment removed. No engine or database was created.\n");
    }

    static Worker start(Path executable) throws Exception {
        Worker worker = Worker.start(executable, env, owned, "process-test", 16 * 1024 * 1024, 1000,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
        workers.add(worker);
        return worker;
    }

    static void load(Worker worker, String source) throws Exception {
        Map<String, Object> load = Worker.frame("LOAD");
        load.put("spec", "test"); load.put("role", "transform"); load.put("label", "<python:test>"); load.put("source", source);
        assertEquals("RESULT", worker.call(load, System.nanoTime() + TimeUnit.SECONDS.toNanos(5)).get("type"));
    }

    static Map<String, Object> execute() {
        Map<String, Object> call = Worker.frame("EXECUTE");
        call.put("spec", "test");
        call.put("context", Map.of("role", "transform", "phase", "source-transformer", "msg", Map.of("kind", "json", "text", "{}"), "maps", Map.of()));
        return call;
    }

    @Test void handshakeAndNativeOutputCannotCorruptSubsequentFrames() throws Exception {
        Worker worker = start(python);
        try {
            assertEquals(worker.pid(), ((Number) worker.hello().get("pid")).longValue());
            load(worker, "import os\nos.write(1, b'native noise\\n')\nmsg['seen'] = True\n");
            for (int i = 0; i < 2; i++) {
                Map<String, Object> reply = worker.call(execute(), System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
                assertEquals("RESULT", reply.get("type"));
                assertTrue(reply.toString().contains("seen"));
            }
        } finally { worker.terminate("test finished"); }
        assertTrue(worker.terminationConfirmed());
    }

    @Test void executionTimeoutHasOneOutcomeAndNeverReusesWorker() throws Exception {
        Worker worker = start(python);
        load(worker, "import time\ntime.sleep(60)\n");
        long started = System.nanoTime();
        Worker.WorkerException failure = assertThrows(Worker.WorkerException.class,
                () -> worker.call(execute(), started + TimeUnit.MILLISECONDS.toNanos(100)));
        assertTrue(failure.timeout);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3000);
        assertFalse(worker.isAlive());
        assertTrue(worker.terminationConfirmed());
        assertThrows(Worker.WorkerException.class, () -> worker.call(execute(), System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
    }

    @Test void expiredStartupDeadlineRejectsBeforeCreatingAProcess() {
        Worker.WorkerException failure = assertThrows(Worker.WorkerException.class, () -> Worker.start(python, env, owned,
                "expired-startup", 16 * 1024 * 1024, 1000, System.nanoTime() - 1));
        assertTrue(failure.timeout);
        assertFalse(failure.processStarted);
    }

    @Test void compileOnlySkipsDependencySiteCodeAndExecutionReservesProtocolBeforeItRuns() throws Exception {
        var described = ProcessControl.runPython(List.of(python.toString(), "-I", "-c", "import sysconfig;print(sysconfig.get_path('purelib'))"),
                env, owned, null, 10000, null, null, 10, 4000);
        assertEquals(0, described.exitCode);
        Path packages = Path.of(described.output.get(0));
        Path marker = owned.resolve("site-external-effect"), pth = packages.resolve("owned-audit.pth");
        Files.writeString(pth, "import os; os.write(1,b'site native noise\\n'); open(" + Interpreters.quote(marker.toString()) + ",'w').write('executed')\n");
        RegistryStore registry = new RegistryStore(new RegistryStoreTest.Memory()); registry.load(null);
        PythonRuntime runtime = new PythonRuntime(new OperatorConfig(owned.toString(), "test"), registry, org.mockito.Mockito.mock(EngineAccess.class));
        try {
            var result = runtime.compileOnly(python, List.of(Map.of("id", "audit", "role", "transform", "label", "<site-audit>",
                    "source", "pass\n")));
            assertEquals(Boolean.TRUE, result.get(0).get("ok"));
            assertFalse(Files.exists(marker), "Compile-only validation executed dependency .pth code");
            Worker worker = start(python);
            try {
                assertEquals("executed", Files.readString(marker));
                load(worker, "msg['site'] = True\n");
                assertEquals("RESULT", worker.call(execute(), System.nanoTime() + TimeUnit.SECONDS.toNanos(5)).get("type"));
            } finally { worker.terminate("site test complete"); }
        } finally { Files.deleteIfExists(pth); runtime.stop(); }
    }

    private static Path fake(String body) throws Exception {
        Path script = Files.createTempFile(owned, "fake-worker", ".py");
        String header = "#!" + Interpreters.resolve("python3") + "\nimport os,sys,json,struct,time\nos.setsid()\n"
                + "def read():\n    h=sys.stdin.buffer.read(4)\n    n=struct.unpack('>I',h)[0]\n    return json.loads(sys.stdin.buffer.read(n))\n"
                + "def send(o):\n    b=json.dumps(o).encode()\n    sys.stdout.buffer.write(struct.pack('>I',len(b))+b)\n    sys.stdout.buffer.flush()\n"
                + "r=read()\nsend({'type':'HELLO','id':r['id'],'protocol':1,'contract':1,'sdk':'0.1.0','pid':os.getpid(),'processGroupLeader':True})\n";
        Files.writeString(script, header + body);
        assertTrue(script.toFile().setExecutable(true));
        return script;
    }

    @Test void blockedPipeWriterIsKilledBeforeClosingItsStream() throws Exception {
        assumeFalse(ProcessControl.isWindows());
        Worker worker = start(fake("time.sleep(60)\n"));
        Map<String, Object> request = Worker.frame("EXECUTE");
        request.put("padding", "x".repeat(8 * 1024 * 1024));
        long started = System.nanoTime();
        Worker.WorkerException failure = assertThrows(Worker.WorkerException.class,
                () -> worker.call(request, started + TimeUnit.MILLISECONDS.toNanos(200)));
        assertTrue(failure.timeout);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3000);
        assertTrue(worker.terminationConfirmed());
    }

    @Test void malformedReplyAndWrongRequestIdDiscardWorkerWithoutReplay() throws Exception {
        assumeFalse(ProcessControl.isWindows());
        for (String response : List.of("{'type':'RESULT','id':'wrong','result':{}}", "{'type':'RESULT','id':r['id'],'result':[]} ", "{'type':'OTHER','id':r['id']}")) {
            Worker worker = start(fake("r=read()\nsend(" + response + ")\ntime.sleep(60)\n"));
            Worker.WorkerException failure = assertThrows(Worker.WorkerException.class,
                    () -> worker.call(execute(), System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
            assertFalse(failure.timeout);
            assertTrue(worker.terminationConfirmed());
        }
    }
}
