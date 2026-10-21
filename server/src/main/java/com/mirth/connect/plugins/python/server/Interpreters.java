/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirth.connect.plugins.python.CanonicalJson;
import com.mirth.connect.plugins.python.PythonApi;

/** Locates operator-approved interpreters and computes their target fingerprints. */
public final class Interpreters {

    public static final int TARGET_POLICY_VERSION = 2;
    private static final long CACHE_NANOS = TimeUnit.SECONDS.toNanos(60);

    public static final class Target {
        public final String runtimeId;
        public final Path executable;
        public final String version;
        public final String fingerprint;
        public final Map<String, Object> description;

        Target(String runtimeId, Path executable, String version, String fingerprint, Map<String, Object> description) {
            this.runtimeId = runtimeId;
            this.executable = executable;
            this.version = version;
            this.fingerprint = fingerprint;
            this.description = description;
        }
    }

    /** The runtime cannot be used on this node. */
    public static final class Unavailable extends Exception {
        private static final long serialVersionUID = 1L;
        public final boolean ownershipConfirmed;

        Unavailable(String message) {
            this(message, true);
        }

        Unavailable(String message, boolean ownershipConfirmed) {
            super(message);
            this.ownershipConfirmed = ownershipConfirmed;
        }
    }

    private static final class Cached {
        final Target target;
        final String error;
        final long at = System.nanoTime();

        Cached(Target target, String error) {
            this.target = target;
            this.error = error;
        }
    }

    private final OperatorConfig config;
    private final Sdk sdk;
    private final Map<String, String> env;
    private final ObjectMapper mapper = PythonApi.mapper();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public Interpreters(OperatorConfig config, Sdk sdk, Map<String, String> env) {
        this.config = config;
        this.sdk = sdk;
        this.env = env;
    }

    public Target probe(String runtimeId) throws Unavailable, InterruptedException {
        Cached cached = cache.get(runtimeId);
        if (cached != null && System.nanoTime() - cached.at < CACHE_NANOS) {
            if (cached.target == null) {
                throw new Unavailable(cached.error);
            }
            return cached.target;
        }
        try {
            Target target = doProbe(runtimeId);
            cache.put(runtimeId, new Cached(target, null));
            return target;
        } catch (Unavailable e) {
            cache.put(runtimeId, new Cached(null, e.getMessage()));
            throw e;
        }
    }

    public void invalidate() {
        cache.clear();
    }

    public Target probeForPreparation(String runtimeId, long timeoutMillis, ProcessControl.Cancellation cancellation) throws Unavailable, InterruptedException {
        Target target = doProbe(runtimeId, timeoutMillis, cancellation);
        cache.put(runtimeId, new Cached(target, null));
        return target;
    }

    @SuppressWarnings("unchecked")
    private Target doProbe(String runtimeId) throws Unavailable, InterruptedException {
        return doProbe(runtimeId, Math.min(30000, config.startupTimeoutMillis), null);
    }

    @SuppressWarnings("unchecked")
    private Target doProbe(String runtimeId, long timeoutMillis, ProcessControl.Cancellation cancellation) throws Unavailable, InterruptedException {
        if (ProcessControl.isWindows()) throw new Unavailable("Python runtime process containment is not qualified on Windows; Job Objects support is required");
        OperatorConfig.Runtime runtime = config.runtimes.get(runtimeId);
        if (runtime == null) {
            throw new Unavailable("Runtime \"" + runtimeId + "\" is not approved on this node");
        }
        Path executable = resolve(runtime.executable);
        if (executable == null) {
            throw new Unavailable("Runtime \"" + runtimeId + "\" executable " + runtime.executable + " was not found on this node");
        }
        String script = "import sys, json; sys.path.insert(0, " + quote(sdk.sourceDir().toString())
                + "); from oie_python import validate; print(json.dumps(validate.describe_python()))";
        List<String> command = Arrays.asList(executable.toString(), "-I", "-S", "-c", script);
        ProcessControl.Result result;
        try {
            result = ProcessControl.runPython(command, env, null, null, timeoutMillis, cancellation, null, 200, 4000);
        } catch (ProcessControl.Stopped | java.io.IOException e) {
            boolean confirmed = !(e instanceof ProcessControl.OwnershipUncertain) && (!(e instanceof ProcessControl.Stopped) || ((ProcessControl.Stopped) e).ownershipConfirmed);
            throw new Unavailable("Runtime \"" + runtimeId + "\" did not start: " + e.getMessage(), confirmed);
        }
        if (result.exitCode != 0) {
            throw new Unavailable("Runtime \"" + runtimeId + "\" failed its probe: " + result.tail(5));
        }
        Map<String, Object> described;
        try {
            described = mapper.readValue(result.output.get(result.output.size() - 1), Map.class);
        } catch (Exception e) {
            throw new Unavailable("Runtime \"" + runtimeId + "\" returned an unreadable probe: " + result.tail(3));
        }
        if (!"CPython".equals(described.get("implementation"))) {
            throw new Unavailable("Runtime \"" + runtimeId + "\" is " + described.get("implementation") + "; only CPython is supported");
        }
        String version = String.valueOf(described.get("version"));
        if (!supportedVersion(version)) {
            throw new Unavailable("Runtime \"" + runtimeId + "\" is Python " + version + "; Python 3.11 or later is required");
        }
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("policyVersion", TARGET_POLICY_VERSION);
        target.put("implementation", described.get("implementation"));
        target.put("version", version);
        target.put("soabi", described.get("soabi"));
        target.put("platform", described.get("platform"));
        target.put("machine", described.get("machine"));
        target.put("system", described.get("system"));
        target.put("installerBootstrap", described.get("installerBootstrap"));
        String fingerprint = CanonicalJson.sha256(target);
        Path real;
        try {
            real = Paths.get(String.valueOf(described.get("executable"))).toRealPath();
        } catch (java.io.IOException e) {
            real = executable;
        }
        target.put("executable", real.toString());
        return new Target(runtimeId, executable, version, fingerprint, target);
    }

    static boolean supportedVersion(String version) {
        String[] parts = version.split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            return major == 3 && minor >= 11;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Resolves a bare command name on PATH; absolute and relative paths are used as given. */
    static Path resolve(String executable) {
        if (executable.contains("/") || executable.contains(File.separator)) {
            Path path = Paths.get(executable);
            return Files.isExecutable(path) ? path.toAbsolutePath() : null;
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            for (String name : ProcessControl.isWindows() ? new String[] { executable + ".exe", executable } : new String[] { executable }) {
                Path candidate = Paths.get(dir, name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toAbsolutePath();
                }
            }
        }
        return null;
    }

    static String quote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
