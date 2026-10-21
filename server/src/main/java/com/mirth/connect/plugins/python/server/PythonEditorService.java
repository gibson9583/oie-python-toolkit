/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipInputStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.mirth.connect.plugins.python.CanonicalJson;
import com.mirth.connect.plugins.python.PythonApi;
import com.mirth.connect.plugins.python.PythonContract;

/** Offline plugin-owned tooling; user packages are read statically, never bootstrapped. */
public final class PythonEditorService {
    public static class Request extends PythonApi.ScriptRequest {
        public String operation;
        public Integer line;
        /** Python code point offset, zero based (Monaco uses UTF-16). */
        public Integer column;
        public Selection selection;
    }
    public static class Selection {
        public int startLineNumber;
        public int endLineNumber;
    }
    private static final Semaphore SLOTS = new Semaphore(2);
    private PythonEditorService() {}

    public static JsonNode request(PythonRuntime runtime, Request request) {
        validate(request);
        if (!SLOTS.tryAcquire()) throw new ApiFailure(429, "EDITOR_BUSY", "Python editor tooling is busy. Try again shortly.");
        ArtifactStore.Lease lease = null;
        boolean confirmed = true;
        try {
            PythonApi.Registry registry = runtime.registry.snapshot();
            String id = BuiltInEnvironment.selectedId(request.environmentProfileId);
            PythonApi.Profile profile = RegistryStore.requireProfile(registry, id);
            if (profile.archived || !registry.registryEpoch.equals(request.expectedRegistryEpoch)
                    || request.expectedEnvironmentRevision == null || profile.revision != request.expectedEnvironmentRevision) {
                throw ApiFailure.conflict("EDITOR_ENVIRONMENT_CHANGED", "Reload the selected script environment.");
            }
            if (!runtime.config.nodeId.equals(request.expectedNodeId)) {
                throw ApiFailure.conflict("NODE_CHANGED", "The Python editor request belongs to another engine node.");
            }
            PythonApi.LocalReadiness readiness = runtime.readiness(profile, registry.registryEpoch);
            if (!"ready".equals(readiness.state)) throw ApiFailure.unavailable("ENVIRONMENT_NOT_READY", "Prepare the selected Python environment to use editor tooling.");
            lease = runtime.artifacts.acquire(readiness.artifactDigest);
            if (lease == null) throw ApiFailure.unavailable("ENVIRONMENT_NOT_READY", "The selected environment is no longer ready.");
            Interpreters.Target target = runtime.interpreters.probe(profile.runtimeId);
            Path home = install(runtime.config.cacheDir);
            List<String> paths = new ArrayList<>();
            // Discover directories without running the environment or processing .pth files.
            Path lib = lease.venv.resolve("lib");
            if (Files.isDirectory(lib)) try (var entries = Files.list(lib)) {
                entries.filter(p -> p.getFileName().toString().matches("python[0-9]+\\.[0-9]+"))
                        .map(p -> p.resolve("site-packages")).filter(Files::isDirectory).forEach(p -> paths.add(p.toString()));
            }
            Path windowsSite = lease.venv.resolve("Lib/site-packages");
            if (Files.isDirectory(windowsSite)) paths.add(windowsSite.toString());
            String bootstrap = "import sys,runpy; sys.path.insert(0,sys.argv.pop(1)); runpy.run_path(sys.argv.pop(1),run_name='__main__')";
            List<String> command = List.of(target.executable.toString(), "-I", "-S", "-B", "-c", bootstrap,
                    home.resolve("vendor").toString(), home.resolve("service.py").toString(), runtime.sdk.sourceDir().toString(),
                    PythonApi.mapper().writeValueAsString(paths));
            ProcessControl.Result output = ProcessControl.runPython(command, runtime.baseEnv, home,
                    PythonApi.mapper().writeValueAsBytes(request), 8000, null, null, 4, 2 * 1024 * 1024);
            if (output.exitCode != 0 || output.output.size() != 1) throw new IOException("Python editor tooling did not return a result");
            JsonNode result = PythonApi.mapper().readTree(output.output.get(0));
            PythonApi.Registry current = runtime.registry.snapshot();
            PythonApi.Profile now = current.profiles.get(id);
            if (!registry.registryEpoch.equals(current.registryEpoch) || now == null || now.archived || now.revision != profile.revision) {
                throw ApiFailure.conflict("EDITOR_ENVIRONMENT_CHANGED", "The script environment changed during the editor request.");
            }
            return result;
        } catch (ProcessControl.Stopped e) {
            confirmed = e.ownershipConfirmed;
            throw ApiFailure.unavailable("EDITOR_TIMEOUT", "Python editor tooling exceeded its deadline.");
        } catch (InterruptedException e) {
            if (e instanceof ProcessControl.OwnedInterrupted) confirmed = ((ProcessControl.OwnedInterrupted) e).ownershipConfirmed;
            Thread.currentThread().interrupt();
            throw ApiFailure.unavailable("EDITOR_INTERRUPTED", "Python editor tooling was interrupted.");
        } catch (Interpreters.Unavailable | IOException e) {
            if (e instanceof ProcessControl.OwnershipUncertain) confirmed = false;
            if (e instanceof Interpreters.Unavailable) confirmed = ((Interpreters.Unavailable) e).ownershipConfirmed;
            throw ApiFailure.unavailable("EDITOR_UNAVAILABLE", "Python editor tooling is unavailable: " + e.getMessage());
        } finally {
            if (lease != null && confirmed && !ProcessControl.hasUncertainOwnership()) lease.release();
            SLOTS.release();
        }
    }

    static void validate(Request request) {
        if (request.pythonScript == null || request.pythonScript.getBytes(StandardCharsets.UTF_8).length > 256 * 1024
                || !PythonContract.isRole(request.role) || !List.of("complete", "hover", "signature", "format").contains(request.operation == null ? "" : request.operation)) {
            throw ApiFailure.badRequest("INVALID_EDITOR_REQUEST", "A supported editor operation, role and source of at most 256 KiB are required.");
        }
        String[] lines = request.pythonScript.split("\\n", -1);
        if (!"format".equals(request.operation) && (request.line == null || request.column == null || request.line < 1
                || request.line > lines.length || request.column < 0 || request.column > lines[request.line - 1].codePointCount(0, lines[request.line - 1].length()))) {
            throw ApiFailure.badRequest("INVALID_EDITOR_POSITION", "The editor position is outside the document.");
        }
        if (request.selection != null && (!"format".equals(request.operation) || request.selection.startLineNumber < 1
                || request.selection.endLineNumber < request.selection.startLineNumber || request.selection.endLineNumber > lines.length)) {
            throw ApiFailure.badRequest("INVALID_EDITOR_SELECTION", "The formatting selection is outside the document.");
        }
    }

    private static synchronized Path install(Path cache) throws IOException {
        byte[] manifest = resource("manifest.json");
        byte[] service = resource("service.py");
        String digest = CanonicalJson.sha256Hex((CanonicalJson.sha256Hex(manifest) + CanonicalJson.sha256Hex(service)).getBytes(StandardCharsets.UTF_8));
        Path home = cache.resolve("editor").resolve(digest);
        Path vendor = home.resolve("vendor");
        if (Files.isRegularFile(home.resolve("ready"))) return home;
        Files.createDirectories(vendor);
        for (JsonNode entry : PythonApi.mapper().readTree(manifest)) {
            byte[] wheel = resource(entry.get("file").asText());
            if (!CanonicalJson.sha256Hex(wheel).equals(entry.get("sha256").asText())) throw new IOException("Bundled editor dependency hash mismatch");
            try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(wheel))) {
                java.util.zip.ZipEntry item;
                while ((item = zip.getNextEntry()) != null) {
                    Path dest = vendor.resolve(item.getName()).normalize();
                    if (!dest.startsWith(vendor)) throw new IOException("Invalid bundled editor path");
                    if (item.isDirectory()) Files.createDirectories(dest);
                    else { Files.createDirectories(dest.getParent()); Files.copy(zip, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                }
            }
        }
        Files.write(home.resolve("service.py"), service);
        Files.writeString(home.resolve("ready"), digest);
        return home;
    }
    private static byte[] resource(String name) throws IOException {
        try (InputStream in = PythonEditorService.class.getResourceAsStream("/python-editor/" + name)) {
            if (in == null) throw new IOException("Missing bundled Python editor resource " + name);
            return in.readAllBytes();
        }
    }
}
