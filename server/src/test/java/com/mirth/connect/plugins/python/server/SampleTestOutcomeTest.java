/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import com.mirth.connect.plugins.python.PythonApi;
import com.mirth.connect.plugins.python.PythonContract;

class SampleTestOutcomeTest {
    private static PythonApi.ScriptRequest request(PythonRuntimeIntegrationTest.Fixture fixture, String kind) {
        PythonApi.ScriptRequest request = new PythonApi.ScriptRequest();
        request.requestId = "sample-outcome"; request.role = PythonContract.ROLE_SOURCE_TRANSFORMER;
        request.environmentProfileId = fixture.profileId; request.expectedRegistryEpoch = fixture.registry.epoch();
        request.expectedEnvironmentRevision = fixture.registry.snapshot().profiles.get(fixture.profileId).revision;
        request.sampleContext = Map.of("msg", Map.of("kind", kind, "text", kind.equals("xml") ? "<a/>" : "{}"));
        return request;
    }

    @Test void dependencyStartupEffectsMakeLoadFailureUncertainButOversizeRejectsBeforeStartup() throws Exception {
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            PythonApi.Profile profile = fixture.registry.snapshot().profiles.get(fixture.profileId);
            var readiness = fixture.runtime.readiness(profile);
            var manifest = fixture.runtime.artifacts.ready(readiness.artifactDigest);
            Path python = ProcessControl.venvPython(fixture.runtime.artifacts.buildDir(readiness.artifactDigest, manifest.buildId).resolve("venv"));
            var described = ProcessControl.runPython(java.util.List.of(python.toString(), "-I", "-c", "import sysconfig;print(sysconfig.get_path('purelib'))"),
                    fixture.runtime.baseEnv, fixture.cache, null, 10000, null, null, 10, 4000);
            Path packages = Path.of(described.output.get(0));
            assertTrue(packages.toRealPath().startsWith(fixture.cache.toRealPath()), "Refuse writing a site file outside the owned fixture");
            Path marker = fixture.cache.resolve("dependency-startup-effect"), pth = packages.resolve("owned-test-startup.pth");
            Files.writeString(pth, "import builtins; builtins.open(" + Interpreters.quote(marker.toString()) + ",'w').write('site-effect')\n");
            try {
                PythonApi.ScriptRequest request = request(fixture, "raw");
                request.pythonScript = "pass\n";
                request.sampleContext = Map.of("msg", Map.of("kind", "raw", "text", "x".repeat(fixture.runtime.config.maxFrameBytes + 1)));
                PythonApi.TestResult oversized = fixture.api.test(request, null);
                assertEquals("error", oversized.status);
                assertEquals("REQUEST_TOO_LARGE", oversized.diagnostics.get(0).code);
                assertFalse(oversized.outcomeUncertain);
                assertFalse(Files.exists(marker), "Oversize preflight enabled dependency startup");
                Map<String, String> limits = Map.of("maxFrameBytes", "65536", "sources", "offline", "source.offline.wheelhouse",
                        fixture.cache.resolve("offline").toString(), "terminationGraceMillis", "1000");
                PythonRuntime narrow = new PythonRuntime(new OperatorConfig(name -> null,
                        name -> limits.get(name.substring(OperatorConfig.PROPERTY_PREFIX.length())), fixture.cache.toString(), fixture.runtime.config.nodeId),
                        fixture.registry, org.mockito.Mockito.mock(EngineAccess.class));
                try {
                    request = request(fixture, "json"); request.pythonScript = "pass\n#" + "x".repeat(65536);
                    PythonApi.TestResult largeSource = new RuntimeApi(narrow).test(request, null);
                    assertEquals("error", largeSource.status);
                    assertEquals("REQUEST_TOO_LARGE", largeSource.diagnostics.get(0).code);
                    assertFalse(largeSource.outcomeUncertain);
                    assertFalse(Files.exists(marker), "LOAD preflight enabled dependency startup");
                    Files.writeString(fixture.evidence.resolve("large-source-before-startup-outcome.json"), PythonApi.mapper().writeValueAsString(largeSource));
                } finally { narrow.stop(); }
                request = request(fixture, "json"); request.pythonScript = "def transform(:\n";
                PythonApi.TestResult failed = fixture.api.test(request, null);
                assertEquals("error", failed.status);
                assertEquals("LOAD_FAILED", failed.diagnostics.get(0).code);
                assertTrue(failed.outcomeUncertain);
                assertNull(failed.output);
                assertEquals("site-effect", Files.readString(marker));
                Files.writeString(fixture.evidence.resolve("dependency-startup-load-outcome.json"), PythonApi.mapper().writeValueAsString(failed));
                Files.writeString(fixture.evidence.resolve("oversize-before-startup-outcome.json"), PythonApi.mapper().writeValueAsString(oversized));
            } finally { Files.deleteIfExists(pth); }
        }
    }

    @Test void resultRejectedAfterExternalEffectReportsUncertainOutcomeAndNoPreview() throws Exception {
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            Path marker = fixture.cache.resolve("external-effect");
            PythonApi.ScriptRequest request = request(fixture, "xml");
            request.pythonScript = "open(" + Interpreters.quote(marker.toString()) + ",'w').write('completed')\nmsg = '<a>'\n";
            PythonApi.TestResult result = fixture.api.test(request, null);
            assertEquals("error", result.status);
            assertTrue(result.outcomeUncertain);
            assertNull(result.output);
            assertEquals("RESULT_REJECTED", result.diagnostics.get(0).code);
            assertEquals("completed", Files.readString(marker));
            Files.writeString(fixture.evidence.resolve("result-rejected-outcome.json"), PythonApi.mapper().writeValueAsString(result));
        }
    }

    @Test void profileOrEpochChangingAfterScriptStartsReturnsTypedUncertainCancellation() throws Exception {
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            for (String change : new String[]{"revision", "epoch"}) {
                Path marker = fixture.cache.resolve(change + "-started"), release = fixture.cache.resolve(change + "-release");
                PythonApi.ScriptRequest request = request(fixture, "json");
                request.pythonScript = "import os,time\nopen(" + Interpreters.quote(marker.toString()) + ",'a').write('once')\n"
                        + "while not os.path.exists(" + Interpreters.quote(release.toString()) + "): time.sleep(0.01)\n"
                        + "msg['completed'] = True\n";
                var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
                try {
                    var future = executor.submit(() -> fixture.api.test(request, null));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(10);
                    assertTrue(Files.exists(marker), "The sample script did not start");
                    if (change.equals("revision")) {
                        fixture.registry.change(registry -> { registry.profiles.get(fixture.profileId).revision++; return true; });
                    } else {
                        String previous = fixture.registry.storedJson();
                        String replacement = fixture.registry.prepareReplacement(previous);
                        assertEquals(RegistryStore.WriteOutcome.COMMITTED, fixture.persistence.write(previous, replacement, "restore-test"));
                        fixture.registry.adoptCommitted(replacement);
                    }
                    Files.writeString(release, "continue");
                    PythonApi.TestResult result = future.get(10, TimeUnit.SECONDS);
                    assertEquals("cancelled", result.status);
                    assertTrue(result.outcomeUncertain);
                    assertNull(result.output);
                    assertEquals("TEST_CONTEXT_CHANGED", result.diagnostics.get(0).code);
                    assertEquals("once", Files.readString(marker));
                    Files.writeString(fixture.evidence.resolve(change + "-changed-test-outcome.json"), PythonApi.mapper().writeValueAsString(result));
                } finally { Files.writeString(release, "continue"); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
            }
        }
    }
}
