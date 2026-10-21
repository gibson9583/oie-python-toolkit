/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.concurrent.TimeUnit;

import com.mirth.connect.plugins.python.CanonicalJson;

/**
 * Operator-owned settings. Each key resolves from the environment variable
 * {@code OIE_PYTHON_RUNTIME_<KEY>} (upper case, dots and dashes as
 * underscores), then the system property
 * {@code org.openintegrationengine.python-runtime.<key>}, then the plugin's
 * {@code operator.properties} file, then the default.
 * Administrators cannot change these through the API.
 */
public final class OperatorConfig {

    public static final String PROPERTY_PREFIX = "org.openintegrationengine.python-runtime.";
    public static final String ENV_PREFIX = "OIE_PYTHON_RUNTIME_";
    static String builtInSourcePolicy() { return CanonicalJson.sha256(Map.of("policyVersion", 1, "source", "bundled-sdk-and-stdlib-only")); }

    public static final class Runtime {
        public final String id;
        public final String label;
        public final String executable;

        Runtime(String id, String label, String executable) {
            this.id = id;
            this.label = label;
            this.executable = executable;
        }
    }

    public static final class PackageSource {
        public final String id;
        public final String label;
        public final String indexUrl;
        public final List<String> extraIndexUrls;
        public final String wheelhouse;
        public final String username;
        public final String password;

        PackageSource(String id, String label, String indexUrl, List<String> extraIndexUrls, String wheelhouse, String username, String password) {
            this.id = id;
            this.label = label;
            this.indexUrl = indexUrl;
            this.extraIndexUrls = extraIndexUrls;
            this.wheelhouse = wheelhouse;
            this.username = username;
            this.password = password;
        }

        public boolean isWheelhouse() {
            return wheelhouse != null && !wheelhouse.isEmpty();
        }

        public boolean isBuiltIn() { return com.mirth.connect.plugins.python.PythonContract.BUILTIN_SOURCE_ID.equals(id); }

        /** Identity of the source policy; credentials are excluded. */
        public String policyIdentity() {
            if (isBuiltIn()) return builtInSourcePolicy();
            Map<String, Object> policy = new LinkedHashMap<>();
            policy.put("policyVersion", 1);
            policy.put("id", id);
            policy.put("indexUrl", isWheelhouse() ? "" : indexUrl);
            policy.put("extraIndexUrls", isWheelhouse() ? Collections.emptyList() : extraIndexUrls);
            policy.put("wheelhouse", isWheelhouse() ? wheelhouse : "");
            // Exported locks retain an opaque trust identity, never approved
            // local paths, repository URLs or credentials embedded in them.
            return CanonicalJson.sha256(policy);
        }
    }

    private final Function<String, String> env;
    private final Function<String, String> props;

    public final Map<String, Runtime> runtimes = new LinkedHashMap<>();
    public final Map<String, PackageSource> sources = new LinkedHashMap<>();
    public final Path cacheDir;
    public final String nodeId;

    public final int maxWorkers;
    public final int maxWorkersPerChannel;
    public final int maxQueuedAcquisitions;
    public final long acquireTimeoutMillis;
    public final long startupTimeoutMillis;
    public final long maxTimeoutMillis;
    public final int maxFrameBytes;
    public final int maxReaderMessages;
    public final int logLimitBytes;
    public final long idleEvictMillis;
    public final int recycleAfterInvocations;
    public final long prepareTimeoutMillis;
    public final long terminationGraceMillis;
    public final int maxConcurrentPreparations;
    public final int maxConcurrentTests;
    public final int maxRequirementsBytes;

    public OperatorConfig(String defaultCacheDir, String defaultNodeId) {
        this(System::getenv, nodeProperties(Paths.get(defaultCacheDir), System::getProperty), defaultCacheDir, defaultNodeId);
    }

    /** Local operator policy lives with plugin data, without editing engine configuration. */
    static Function<String,String> nodeProperties(Path pluginData, Function<String,String> system) {
        java.util.Properties file = new java.util.Properties();
        Path path = pluginData.resolve("operator.properties");
        try {
            if (java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (!java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        || java.nio.file.Files.size(path) > 64 * 1024) throw new java.io.IOException("Operator policy must be a regular file no larger than 64 KiB");
                try (var reader = java.nio.file.Files.newBufferedReader(path, java.nio.charset.StandardCharsets.UTF_8)) { file.load(reader); }
            }
        } catch (java.io.IOException failure) { throw new IllegalArgumentException("Cannot read Python Runtime operator.properties", failure); }
        return name -> {
            String value = system.apply(name);
            if (value != null) return value;
            return file.getProperty(name, file.getProperty(name.startsWith(PROPERTY_PREFIX) ? name.substring(PROPERTY_PREFIX.length()) : name));
        };
    }

    OperatorConfig(Function<String, String> env, Function<String, String> props, String defaultCacheDir, String defaultNodeId) {
        this.env = env;
        this.props = props;
        for (String entry : list(get("runtimes", "python3=python3"))) {
            int eq = entry.indexOf('=');
            if (eq <= 0 || eq == entry.length() - 1) {
                throw new IllegalArgumentException("Python runtime entry \"" + entry + "\" must be id=executable");
            }
            String id = entry.substring(0, eq).trim();
            String executable = entry.substring(eq + 1).trim();
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}") || runtimes.containsKey(id)) throw new IllegalArgumentException("Invalid or duplicate Python runtime ID " + id);
            runtimes.put(id, new Runtime(id, get("runtime." + id + ".label", "python3".equals(id) ? "System python3" : id), executable));
        }
        for (String id : list(get("sources", "pypi"))) {
            if (com.mirth.connect.plugins.python.PythonContract.BUILTIN_SOURCE_ID.equals(id)) throw new IllegalArgumentException("builtin-stdlib is reserved for the bundled SDK and standard library");
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}") || sources.containsKey(id)) throw new IllegalArgumentException("Invalid or duplicate package source ID " + id);
            String defaultIndex = "pypi".equals(id) ? "https://pypi.org/simple" : "";
            sources.put(id, new PackageSource(id, get("source." + id + ".label", id), get("source." + id + ".indexUrl", defaultIndex),
                    list(get("source." + id + ".extraIndexUrls", "")), get("source." + id + ".wheelhouse", ""),
                    get("source." + id + ".username", ""), get("source." + id + ".password", "")));
        }
        sources.put(com.mirth.connect.plugins.python.PythonContract.BUILTIN_SOURCE_ID,
                new PackageSource(com.mirth.connect.plugins.python.PythonContract.BUILTIN_SOURCE_ID,
                        "Standard library (no packages)", "", Collections.emptyList(), "", "", ""));
        if (runtimes.isEmpty()) throw new IllegalArgumentException("At least one Python runtime must be approved");
        for (PackageSource source : sources.values()) {
            if (!source.isBuiltIn() && !source.isWheelhouse() && source.indexUrl.isEmpty()) {
                throw new IllegalArgumentException("Package source \"" + source.id + "\" needs an indexUrl or a wheelhouse");
            }
        }
        cacheDir = Paths.get(get("cacheDir", defaultCacheDir)).toAbsolutePath().normalize();
        nodeId = get("nodeId", defaultNodeId);
        if (nodeId.isEmpty() || nodeId.length() > 100) throw new IllegalArgumentException("nodeId must contain 1 to 100 characters");
        maxWorkers = intValue("maxWorkers", 8, 1);
        maxWorkersPerChannel = intValue("maxWorkersPerChannel", maxWorkers, 1);
        maxQueuedAcquisitions = intValue("maxQueuedAcquisitions", 64, 0);
        acquireTimeoutMillis = longValue("acquireTimeoutMillis", 5000, 1);
        startupTimeoutMillis = longValue("startupTimeoutMillis", 10000, 100);
        maxTimeoutMillis = longValue("maxTimeoutMillis", 300000, 100);
        maxFrameBytes = intValue("maxFrameBytes", 16 * 1024 * 1024, 64 * 1024);
        maxReaderMessages = intValue("maxReaderMessages", 1000, 1);
        logLimitBytes = intValue("logLimitBytes", 64 * 1024, 0);
        idleEvictMillis = longValue("idleEvictMillis", 60000, 1000);
        recycleAfterInvocations = intValue("recycleAfterInvocations", 1000, 1);
        prepareTimeoutMillis = longValue("prepareTimeoutMillis", 600000, 1000);
        terminationGraceMillis = longValue("terminationGraceMillis", 5000, 0);
        maxConcurrentPreparations = intValue("maxConcurrentPreparations", 1, 1);
        maxConcurrentTests = intValue("maxConcurrentTests", 2, 1);
        maxRequirementsBytes = intValue("maxRequirementsBytes", 64 * 1024, 1024);
    }

    public String get(String key, String defaultValue) {
        String envName = ENV_PREFIX + key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        String value = env.apply(envName);
        if (value == null) {
            value = props.apply(PROPERTY_PREFIX + key);
        }
        return value == null ? defaultValue : value.trim();
    }

    private int intValue(String key, int defaultValue, int min) {
        long value = Long.parseLong(get(key, Integer.toString(defaultValue)));
        if (value < min || value > Integer.MAX_VALUE) throw new IllegalArgumentException(key + " must be between " + min + " and " + Integer.MAX_VALUE);
        return (int) value;
    }

    private long longValue(String key, long defaultValue, long min) {
        long value = Long.parseLong(get(key, Long.toString(defaultValue)));
        if (value < min || value > TimeUnit.NANOSECONDS.toMillis(Long.MAX_VALUE) / 2) throw new IllegalArgumentException(key + " exceeds the supported deadline range");
        return value;
    }

    private static List<String> list(String value) {
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.trim().isEmpty()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    public Map<String, Long> limits() {
        Map<String, Long> limits = new LinkedHashMap<>();
        limits.put("maxWorkers", (long) maxWorkers);
        limits.put("maxWorkersPerChannel", (long) maxWorkersPerChannel);
        limits.put("maxQueuedAcquisitions", (long) maxQueuedAcquisitions);
        limits.put("acquireTimeoutMillis", acquireTimeoutMillis);
        limits.put("startupTimeoutMillis", startupTimeoutMillis);
        limits.put("defaultTimeoutMillis", (long) com.mirth.connect.plugins.python.PythonContract.DEFAULT_TIMEOUT_MILLIS);
        limits.put("maxTimeoutMillis", maxTimeoutMillis);
        limits.put("maxFrameBytes", (long) maxFrameBytes);
        limits.put("maxReaderMessages", (long) maxReaderMessages);
        limits.put("logLimitBytes", (long) logLimitBytes);
        limits.put("idleEvictMillis", idleEvictMillis);
        limits.put("recycleAfterInvocations", (long) recycleAfterInvocations);
        limits.put("prepareTimeoutMillis", prepareTimeoutMillis);
        limits.put("maxRequirementsBytes", (long) maxRequirementsBytes);
        return limits;
    }
}
