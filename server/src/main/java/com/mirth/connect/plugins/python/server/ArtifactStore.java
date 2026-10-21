/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirth.connect.plugins.python.CanonicalJson;
import com.mirth.connect.plugins.python.PythonApi;

/**
 * Immutable prepared environments on this node. An environment is ready only
 * when its manifest has been published atomically after verification. Lease
 * acquisition and garbage collection share one gate, so collection never
 * removes an environment between selection and use.
 */
public final class ArtifactStore {

    private static final Logger logger = LogManager.getLogger(ArtifactStore.class);
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    public static final String INSTALLER_POLICY = "pip-install/no-index/require-hashes/only-binary/no-deps/v1";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Manifest {
        public String artifactDigest;
        public String buildId;
        public String lockDigest;
        public String targetFingerprint;
        public String interpreter;
        public Map<String, Object> targetDescription = new LinkedHashMap<>();
        public String packageSourcePolicy;
        public String sdkVersion;
        public String sdkSha256;
        public List<Map<String, String>> packages = new ArrayList<>();
        public String createdAt;
    }

    /** A hold on one ready build. Release exactly once. */
    public final class Lease {
        public final String digest;
        public final String buildId;
        public final Path venv;
        public final Path python;
        public final Manifest manifest;
        private boolean released;

        Lease(Manifest manifest) {
            this.digest = manifest.artifactDigest;
            this.buildId = manifest.buildId;
            this.venv = buildDir(digest, buildId).resolve("venv");
            this.python = ProcessControl.venvPython(venv);
            this.manifest = manifest;
        }

        public void release() {
            synchronized (this) {
                if (released) {
                    return;
                }
                released = true;
            }
            gate.lock();
            try {
                leases.merge(digest + "/" + buildId, -1, Integer::sum);
                leases.remove(digest + "/" + buildId, 0);
            } finally {
                gate.unlock();
            }
        }
    }

    private final Path root;
    private final ObjectMapper mapper = PythonApi.mapper();
    private final ReentrantLock gate = new ReentrantLock();
    private final Map<String, Integer> leases = new HashMap<>();
    private final Map<String, ReentrantLock> buildLocks = new HashMap<>();
    private volatile long collectionGeneration;

    public ArtifactStore(Path cacheDir) throws IOException {
        this.root = cacheDir.resolve("artifacts");
        Files.createDirectories(root);
    }

    public Path root() {
        return root;
    }

    public static String artifactDigest(String lockDigest, Interpreters.Target target, Sdk sdk) {
        return artifactDigest(lockDigest, target, sdk, "");
    }

    public static String artifactDigest(PythonApi.ResolvedLock lock, Interpreters.Target target, Sdk sdk) {
        return artifactDigest(lock.lockDigest, target, sdk, lock.packageSourcePolicy);
    }

    private static String artifactDigest(String lockDigest, Interpreters.Target target, Sdk sdk, String sourcePolicy) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("lockDigest", lockDigest);
        identity.put("targetFingerprint", target.fingerprint);
        identity.put("interpreter", String.valueOf(target.description.get("executable")));
        identity.put("sdkVersion", Sdk.VERSION);
        identity.put("sdkSha256", sdk.wheelSha256());
        identity.put("protocol", Sdk.PROTOCOL_VERSION);
        identity.put("installerPolicy", INSTALLER_POLICY);
        identity.put("packageSourcePolicy", sourcePolicy);
        return CanonicalJson.sha256(identity);
    }

    Path artifactDir(String digest) {
        if (!DIGEST.matcher(digest).matches()) {
            throw new IllegalArgumentException("Invalid artifact digest");
        }
        return root.resolve(digest);
    }

    Path buildDir(String digest, String buildId) {
        if (buildId == null || !buildId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("Invalid artifact build identity");
        }
        return artifactDir(digest).resolve("builds").resolve(buildId);
    }

    public Path newBuildDir(String digest) throws IOException {
        Path dir = buildDir(digest, UUID.randomUUID().toString());
        Files.createDirectories(dir);
        return dir;
    }

    /** Serializes builds of one artifact within this JVM. */
    public ReentrantLock buildLock(String digest) {
        synchronized (buildLocks) {
            return buildLocks.computeIfAbsent(digest, d -> new ReentrantLock());
        }
    }

    /** The published manifest, or null when the artifact is not ready here. */
    public Manifest ready(String digest) {
        Path file = artifactDir(digest).resolve("ready.json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            Manifest manifest = mapper.readValue(file.toFile(), Manifest.class);
            if (!digest.equals(manifest.artifactDigest) || manifest.buildId == null) {
                return null;
            }
            Path python = ProcessControl.venvPython(buildDir(digest, manifest.buildId).resolve("venv"));
            return Files.exists(python) ? manifest : null;
        } catch (IOException | IllegalArgumentException e) {
            logger.warn("Ignoring an unreadable Python environment manifest " + file + ": " + e.getMessage());
            return null;
        }
    }

    /** Atomically publishes a verified build as the artifact's ready environment. */
    public void publish(Manifest manifest) throws IOException {
        manifest.createdAt = Instant.now().toString();
        Path dir = artifactDir(manifest.artifactDigest);
        Path tmp = dir.resolve("ready.json." + UUID.randomUUID() + ".tmp");
        Files.write(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
        Files.move(tmp, dir.resolve("ready.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Leases the ready build of an artifact, or returns null when it is not ready. */
    public Lease acquire(String digest) {
        gate.lock();
        try {
            Manifest manifest = ready(digest);
            if (manifest == null) {
                return null;
            }
            leases.merge(digest + "/" + manifest.buildId, 1, Integer::sum);
            return new Lease(manifest);
        } finally {
            gate.unlock();
        }
    }

    public int activeLeases() {
        gate.lock();
        try {
            return leases.values().stream().mapToInt(Integer::intValue).sum();
        } finally {
            gate.unlock();
        }
    }

    /** Capture before reading the registry's selected locks. */
    long collectionGeneration() { return collectionGeneration; }

    /** Invalidate selections before a registry change or before releasing a prepared build. */
    void invalidateCollection() {
        gate.lock();
        try { collectionGeneration++; }
        finally { gate.unlock(); }
    }

    /**
     * Removes plugin-owned artifacts that no profile selects and no deployment
     * leases, and unpublished or superseded builds. Builds in progress hold the
     * artifact's build lock and are skipped. A selection invalidated since its
     * generation was captured is discarded without deleting anything.
     */
    public List<String> collect(Set<String> keep, long expectedGeneration) {
        List<String> removed = new ArrayList<>();
        if (ProcessControl.hasUncertainOwnership()) {
            logger.warn("Retaining Python artifacts because owned process exit is uncertain: " + ProcessControl.uncertainPids());
            return removed;
        }
        gate.lock();
        try (DirectoryStream<Path> artifacts = Files.newDirectoryStream(root)) {
            if (expectedGeneration != collectionGeneration) return removed;
            for (Path dir : artifacts) {
                String digest = dir.getFileName().toString();
                if (!DIGEST.matcher(digest).matches() || !Files.isDirectory(dir)) {
                    continue;
                }
                if (Files.exists(dir.resolve("ownership-uncertain"))) {
                    logger.warn("Retaining Python artifact with uncertain process ownership: " + dir);
                    continue;
                }
                ReentrantLock building = buildLock(digest);
                if (!building.tryLock()) {
                    continue;
                }
                try {
                    Manifest manifest = ready(digest);
                    boolean leased = leases.keySet().stream().anyMatch(k -> k.startsWith(digest + "/"));
                    if (!keep.contains(digest) && !leased) {
                        ProcessControl.deleteOwnedTree(root, dir);
                        removed.add(digest);
                        continue;
                    }
                    Path builds = dir.resolve("builds");
                    if (Files.isDirectory(builds)) {
                        try (DirectoryStream<Path> stream = Files.newDirectoryStream(builds)) {
                            for (Path build : stream) {
                                String buildId = build.getFileName().toString();
                                boolean current = manifest != null && buildId.equals(manifest.buildId);
                                if (!current && !leases.containsKey(digest + "/" + buildId)) {
                                    ProcessControl.deleteOwnedTree(root, build);
                                    removed.add(digest + "/" + buildId);
                                }
                            }
                        }
                    }
                } finally {
                    building.unlock();
                }
            }
        } catch (IOException e) {
            logger.warn("Python environment cleanup stopped: " + e.getMessage());
        } finally {
            gate.unlock();
        }
        return removed;
    }

    /** Removes builds of one artifact that were never published and are not leased. */
    public void removeUnpublishedBuilds(String digest) {
        if (ProcessControl.hasUncertainOwnership()) return;
        gate.lock();
        ReentrantLock building = buildLock(digest);
        try {
            if (Files.exists(artifactDir(digest).resolve("ownership-uncertain"))) return;
            if (!building.tryLock()) {
                return;
            }
            try {
                Manifest manifest = ready(digest);
                Path builds = artifactDir(digest).resolve("builds");
                if (!Files.isDirectory(builds)) {
                    return;
                }
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(builds)) {
                    for (Path build : stream) {
                        String buildId = build.getFileName().toString();
                        if ((manifest == null || !buildId.equals(manifest.buildId)) && !leases.containsKey(digest + "/" + buildId)) {
                            ProcessControl.deleteOwnedTree(root, build);
                        }
                    }
                }
            } finally {
                building.unlock();
            }
        } catch (IOException e) {
            logger.warn("Could not remove an unfinished Python environment build: " + e.getMessage());
        } finally {
            gate.unlock();
        }
    }

    public long sizeBytes() {
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return -1;
        }
    }
}
