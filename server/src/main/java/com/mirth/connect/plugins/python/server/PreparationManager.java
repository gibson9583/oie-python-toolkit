/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirth.connect.plugins.python.CanonicalJson;
import com.mirth.connect.plugins.python.PythonApi;
import com.mirth.connect.plugins.python.PythonApi.Diagnostic;
import com.mirth.connect.plugins.python.PythonApi.JobView;
import com.mirth.connect.plugins.python.PythonApi.LockedRequirement;
import com.mirth.connect.plugins.python.PythonApi.Profile;
import com.mirth.connect.plugins.python.PythonApi.Registry;
import com.mirth.connect.plugins.python.PythonApi.ResolvedLock;

/**
 * Asynchronous environment preparation on this node.
 *
 * <ol>
 * <li>Capture the epoch, revision, definition and the local target.</li>
 * <li>Reuse the committed lock for this definition and target, or resolve a
 * complete binary wheel set with {@code pip download} in a plugin-owned
 * resolver environment and commit the resulting lock.</li>
 * <li>Build a virtual environment at its final path from only the locked,
 * hash-checked wheels plus the SDK wheel; no index is used.</li>
 * <li>Run {@code pip check}, compare the installed set with the lock,
 * complete a worker handshake, and publish the ready manifest atomically if
 * the definition is still current.</li>
 * </ol>
 */
public final class PreparationManager {

    private static final Logger logger = LogManager.getLogger(PreparationManager.class);
    private static final Pattern WHEEL = Pattern.compile("^([A-Za-z0-9_.]+)-([^-]+)(?:-(\\d[^-]*))?-([^-]+)-([^-]+)-([^-]+)\\.whl$");
    private static final int MAX_LOG_LINES = 4000;
    private static final int MAX_JOBS_RETAINED = 200;
    public static final Set<String> TERMINAL = Collections.unmodifiableSet(new java.util.HashSet<>(
            Arrays.asList("READY", "FAILED", "CANCELLED", "SUPERSEDED", "INTERRUPTED")));

    /** Re-checks the requesting user's permission when the job starts. */
    public interface Authorizer {
        boolean stillAuthorized();
    }

    final class Job {
        final JobView view = new JobView();
        final Authorizer authorizer;
        final String dedupeKey;
        final List<String> log = new ArrayList<>();
        long firstLine;
        volatile boolean cancel;
        volatile boolean ownershipUncertain;
        final long startedNanos = System.nanoTime();
        final Map<String, Long> stageStart = new LinkedHashMap<>();

        Job(String dedupeKey, Authorizer authorizer) {
            this.dedupeKey = dedupeKey;
            this.authorizer = authorizer;
        }

        synchronized void line(String text) {
            String redacted = redact(text);
            log.add(redacted.length() > 4000 ? redacted.substring(0, 4000) + "..." : redacted);
            while (log.size() > MAX_LOG_LINES) {
                log.remove(0);
                firstLine++;
            }
        }

        synchronized void stage(String state, String message) {
            long now = System.nanoTime();
            if (view.state != null && stageStart.containsKey(view.state)) {
                view.durationsMillis.put(view.state.toLowerCase(Locale.ROOT), TimeUnit.NANOSECONDS.toMillis(now - stageStart.get(view.state)));
            }
            view.state = state;
            view.message = message;
            view.updatedAt = Instant.now().toString();
            stageStart.put(state, now);
            if (TERMINAL.contains(state)) {
                view.finishedAt = view.updatedAt;
                view.durationsMillis.put("total", TimeUnit.NANOSECONDS.toMillis(now - startedNanos));
            }
            line("[" + state + "] " + (message == null ? "" : message));
            persist(this);
        }

        synchronized JobView snapshot() {
            try {
                return mapper.readValue(mapper.writeValueAsBytes(view), JobView.class);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        long deadlineRemaining() {
            return config.prepareTimeoutMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        }
    }

    /** A step failed with a diagnostic the user can act on. */
    static final class StepFailure extends Exception {
        private static final long serialVersionUID = 1L;
        final Diagnostic diagnostic;

        StepFailure(String code, String message, String action) {
            super(message);
            diagnostic = Diagnostic.of(code, message, null, action);
        }
    }

    static final class Superseded extends Exception {
        private static final long serialVersionUID = 1L;

        Superseded(String message) {
            super(message);
        }
    }

    private final OperatorConfig config;
    private final RegistryStore registry;
    private final Interpreters interpreters;
    private final ArtifactStore artifacts;
    private final Sdk sdk;
    private final Map<String, String> baseEnv;
    private final Path cacheDir;
    private final Path jobsDir;
    private final Path wheelsDir;
    private final ObjectMapper mapper = PythonApi.mapper();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, Job> active = new ConcurrentHashMap<>();
    private final Map<Path, ReentrantLock> resolverLocks = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;
    private final Runnable afterReady;

    public PreparationManager(OperatorConfig config, RegistryStore registry, Interpreters interpreters, ArtifactStore artifacts, Sdk sdk,
            Map<String, String> baseEnv, Runnable afterReady) throws IOException {
        this.config = config;
        this.registry = registry;
        this.interpreters = interpreters;
        this.artifacts = artifacts;
        this.sdk = sdk;
        this.baseEnv = baseEnv;
        this.afterReady = afterReady;
        this.cacheDir = config.cacheDir;
        this.jobsDir = cacheDir.resolve("jobs");
        this.wheelsDir = cacheDir.resolve("wheels");
        Files.createDirectories(jobsDir);
        Files.createDirectories(wheelsDir);
        Files.createDirectories(cacheDir.resolve("staging"));
        this.executor = new ThreadPoolExecutor(config.maxConcurrentPreparations, config.maxConcurrentPreparations, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(32), r -> {
                    Thread t = new Thread(r, "python-runtime-prepare");
                    t.setDaemon(true);
                    return t;
                });
        recoverInterruptedJobs();
        // A restarted engine cannot infer that the previous owner's processes
        // exited. Preserve old stages until an ownership-aware cleanup verifies them.
    }

    // ---- Identities --------------------------------------------------------------------

    public static String definitionFingerprint(Profile p) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("canonicalization", 1);
        definition.put("language", "python");
        definition.put("runtimeId", p.runtimeId);
        definition.put("packageSourceId", p.packageSourceId);
        definition.put("requirements", Requirements.normalize(p.requirementsText));
        definition.put("refreshGeneration", p.refreshGeneration);
        return CanonicalJson.sha256(definition);
    }

    public static String lockDigest(String targetFingerprint, List<LockedRequirement> requirements) {
        List<Object> entries = new ArrayList<>();
        for (LockedRequirement r : requirements) {
            entries.add(RegistryStore.mapOf("name", r.name, "version", r.version, "filename", r.filename, "sha256", r.sha256));
        }
        return CanonicalJson.sha256(RegistryStore.mapOf("targetFingerprint", targetFingerprint, "requirements", entries));
    }

    // ---- Requests ------------------------------------------------------------------------

    /** Starts (or joins) preparation of the profile's saved revision. */
    public JobView prepare(String profileId, String expectedEpoch, Long expectedRevision, Authorizer authorizer) {
        if (authorizer != null && !authorizer.stillAuthorized()) {
            throw new ApiFailure(403, "NOT_AUTHORIZED", "The requesting user can no longer prepare environments");
        }
        Registry snapshot = registry.snapshot();
        if (expectedEpoch == null || !expectedEpoch.equals(snapshot.registryEpoch)) {
            ApiFailure failure = ApiFailure.conflict("REGISTRY_EPOCH_CONFLICT", "The environment registry was restored or replaced. Reload.");
            failure.registryEpoch = snapshot.registryEpoch;
            throw failure;
        }
        Profile profile = RegistryStore.requireProfile(snapshot, profileId);
        RegistryStore.checkRevision(profile, expectedRevision);
        if (profile.archived) {
            throw ApiFailure.conflict("PROFILE_ARCHIVED", "Environment \"" + profile.name + "\" is archived");
        }
        // No process launch on the request thread. The immutable approved
        // runtime policy identifies the queued work; its actual target is
        // captured by the asynchronous job before resolution/building.
        OperatorConfig.Runtime approved = config.runtimes.get(profile.runtimeId);
        if (approved == null) throw ApiFailure.unavailable("RUNTIME_UNAVAILABLE", "The profile runtime is not approved on this node");
        String dedupe = String.join("|", snapshot.registryEpoch, profile.id, Long.toString(profile.revision), profile.definitionFingerprint,
                approved.executable, sdk.wheelSha256());
        synchronized (active) {
            Job existing = active.get(dedupe);
            if (existing != null) {
                return existing.snapshot();
            }
            Job job = new Job(dedupe, authorizer);
            job.view.id = UUID.randomUUID() + "~" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(config.nodeId.getBytes(StandardCharsets.UTF_8));
            job.view.nodeId = config.nodeId;
            job.view.profileId = profile.id;
            job.view.registryEpoch = snapshot.registryEpoch;
            job.view.profileRevision = profile.revision;
            job.view.definitionFingerprint = profile.definitionFingerprint;
            job.view.createdAt = Instant.now().toString();
            job.stage("QUEUED", "Waiting for a preparation slot");
            active.put(dedupe, job);
            remember(job);
            try {
                executor.execute(new PreparationTask(job));
            } catch (RejectedExecutionException e) {
                active.remove(dedupe, job);
                job.stage("FAILED", "Too many preparations are queued on this node");
                throw new ApiFailure(429, "TOO_MANY_PREPARATIONS", "Too many preparations are queued on this node. Try again later.");
            }
            return job.snapshot();
        }
    }

    public JobView job(String jobId) {
        checkJobOwner(jobId);
        Job job = jobs.get(jobId);
        if (job != null) {
            return job.snapshot();
        }
        JobView stored = readStoredJob(jobId);
        if (stored == null) {
            throw ApiFailure.notFound("Preparation job " + jobId + " does not exist on node " + config.nodeId);
        }
        if (stored.nodeId != null && !stored.nodeId.equals(config.nodeId)) {
            ApiFailure failure = ApiFailure.conflict("JOB_ON_DIFFERENT_NODE", "Preparation job " + jobId + " belongs to node " + stored.nodeId);
            failure.ownerNodeId = stored.nodeId;
            throw failure;
        }
        return stored;
    }

    public PythonApi.JobLog log(String jobId, long cursor) {
        checkJobOwner(jobId);
        if (cursor < 0) throw ApiFailure.badRequest("INVALID_CURSOR", "Log cursor must not be negative");
        Job job = jobs.get(jobId);
        PythonApi.JobLog out = new PythonApi.JobLog();
        out.jobId = jobId;
        out.nodeId = config.nodeId;
        out.cursor = cursor;
        if (job == null) {
            JobView view = job(jobId);
            out.complete = TERMINAL.contains(view.state);
            out.nextCursor = cursor;
            Path file = jobsDir.resolve(jobId + ".log");
            if (Files.isRegularFile(file)) {
                try {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    long start = Math.min(cursor, lines.size());
                    for (int i = (int) start; i < lines.size() && out.lines.size() < 1000; i++) {
                        out.lines.add(lines.get(i));
                    }
                    out.nextCursor = start + out.lines.size();
                } catch (IOException e) {
                    out.lines.add("(log unavailable: " + e.getMessage() + ")");
                }
            }
            return out;
        }
        synchronized (job) {
            long start = Math.min(Math.max(cursor, job.firstLine), job.firstLine + job.log.size());
            out.truncatedBefore = cursor < job.firstLine;
            int from = (int) (start - job.firstLine);
            for (int i = from; i < job.log.size() && out.lines.size() < 1000; i++) {
                out.lines.add(job.log.get(i));
            }
            out.nextCursor = start + out.lines.size();
            out.complete = TERMINAL.contains(job.view.state) && out.nextCursor >= job.firstLine + job.log.size();
        }
        return out;
    }

    public JobView cancel(String jobId) {
        checkJobOwner(jobId);
        Job job = jobs.get(jobId);
        if (job == null) {
            return job(jobId);
        }
        job.cancel = true;
        synchronized (job) {
            job.view.cancelRequested = true;
        }
        return job.snapshot();
    }

    /** Active or most recent job for a profile definition on this node. */
    public JobView latestFor(String profileId, String definitionFingerprint) {
        return latestFor(profileId, definitionFingerprint, null, null);
    }

    public JobView latestFor(String profileId, String definitionFingerprint, String epoch, Long revision) {
        JobView best = null;
        for (Job job : jobs.values()) {
            JobView v = job.snapshot();
            if (profileId.equals(v.profileId) && definitionFingerprint.equals(v.definitionFingerprint)
                    && (epoch == null || epoch.equals(v.registryEpoch)) && (revision == null || revision == v.profileRevision)) {
                if (best == null || Instant.parse(v.createdAt).isAfter(Instant.parse(best.createdAt))) {
                    best = v;
                }
            }
        }
        return best;
    }

    public boolean hasActiveJob(String profileId) {
        return hasActiveJob(profileId, null, null);
    }

    public boolean hasActiveJob(String profileId, String epoch, Long revision) {
        for (Job job : active.values()) {
            if (profileId.equals(job.view.profileId) && (epoch == null || epoch.equals(job.view.registryEpoch))
                    && (revision == null || revision == job.view.profileRevision)) {
                return true;
            }
        }
        return false;
    }

    public void shutdown() {
        for (Job job : active.values()) {
            job.cancel = true;
        }
        List<Runnable> abandoned = executor.shutdownNow();
        for (Runnable task : abandoned) ((PreparationTask) task).run();
        try {
            executor.awaitTermination(config.terminationGraceMillis + 2000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- The job ---------------------------------------------------------------------------

    private final class PreparationTask implements Runnable {
        final Job job;
        PreparationTask(Job job) { this.job = job; }
        @Override public void run() { PreparationManager.this.run(job); }
    }

    private void run(Job job) {
        Path staging = cacheDir.resolve("staging").resolve(job.view.id);
        try {
            if (job.cancel) {
                job.stage("CANCELLED", "Cancelled before it started");
                return;
            }
            if (job.authorizer != null && !job.authorizer.stillAuthorized()) {
                fail(job, Diagnostic.of("NOT_AUTHORIZED", "The requesting user can no longer prepare environments", null, null));
                return;
            }
            Files.createDirectories(staging);
            job.stage("RESOLVING", "Resolving dependencies");
            Profile profile = current(job);
            Interpreters.Target target = interpreters.probeForPreparation(profile.runtimeId,
                    Math.min(config.startupTimeoutMillis, job.deadlineRemaining()), () -> job.cancel);
            job.view.targetFingerprint = target.fingerprint;
            OperatorConfig.PackageSource source = config.sources.get(profile.packageSourceId);
            if (source == null) {
                throw new StepFailure("SOURCE_NOT_APPROVED", "Package source \"" + profile.packageSourceId + "\" is not approved on this node",
                        "Choose an approved package source.");
            }
            if (source.isBuiltIn() && !BuiltInEnvironment.stdlibOnly(profile.requirementsText)) {
                throw new StepFailure("STDLIB_ONLY", "The standard-library source cannot install package requirements", "Create a separate environment with an approved package source.");
            }
            Map<String, String> env = pipEnvironment(source);
            ResolvedLock lock = profile.resolvedLocks.get(target.fingerprint);
            if (source.isBuiltIn() && lock != null && !lock.lockedRequirements.isEmpty()) {
                throw new StepFailure("STDLIB_ONLY", "The standard-library lock contains external packages", "Restore a valid empty standard-library definition.");
            }
            if (lock != null && profile.definitionFingerprint.equals(lock.definitionFingerprint)) {
                if (!source.policyIdentity().equals(lock.packageSourcePolicy)) {
                    throw new StepFailure("SOURCE_POLICY_CHANGED", "The approved package source policy changed since this lock was prepared", "Refresh dependencies and prepare again.");
                }
                job.line("Using the committed lock " + lock.lockDigest);
                ensureWheels(job, target, source, env, lock, staging);
            } else {
                lock = resolve(job, profile, target, source, env, staging);
                lock = commitLock(job, lock);
                // A different node may have resolved a different wheel set.
                // Its committed lock wins, so fetch that set before building.
                ensureWheels(job, target, source, env, lock, staging);
            }
            job.view.lockDigest = lock.lockDigest;

            String digest = ArtifactStore.artifactDigest(lock, target, sdk);
            job.view.artifactDigest = digest;
            ReentrantLock buildLock = artifacts.buildLock(digest);
            if (!buildLock.tryLock(job.deadlineRemaining(), TimeUnit.MILLISECONDS)) {
                throw new StepFailure("BUILD_LOCK_TIMEOUT", "Another preparation of the same environment did not finish in time", "Prepare again.");
            }
            try {
                ArtifactStore.Manifest existing = artifacts.ready(digest);
                if (existing != null) {
                    job.stage("VERIFYING", "Verifying the existing environment");
                    verify(job, ProcessControl.venvPython(artifacts.buildDir(digest, existing.buildId).resolve("venv")), lock, staging);
                    current(job);
                } else {
                    job.stage("BUILDING", "Building the environment");
                    Path build = artifacts.newBuildDir(digest);
                    Path venv = build.resolve("venv");
                    Path python = build(job, target, lock, build, venv, staging);
                    job.stage("VERIFYING", "Verifying the environment");
                    List<Map<String, String>> packages = verify(job, python, lock, staging);
                    current(job);
                    ArtifactStore.Manifest manifest = new ArtifactStore.Manifest();
                    manifest.artifactDigest = digest;
                    manifest.buildId = build.getFileName().toString();
                    manifest.lockDigest = lock.lockDigest;
                    manifest.targetFingerprint = target.fingerprint;
                    manifest.interpreter = String.valueOf(target.description.get("executable"));
                    manifest.targetDescription = new LinkedHashMap<>(target.description);
                    manifest.packageSourcePolicy = lock.packageSourcePolicy;
                    manifest.sdkVersion = Sdk.VERSION;
                    manifest.sdkSha256 = sdk.wheelSha256();
                    manifest.packages = packages;
                    artifacts.publish(manifest);
                }
                // Keep the build lock until an older collector has finished or
                // its selection is invalidated. Collection only tryLocks builds.
                artifacts.invalidateCollection();
                job.stage("READY", existing == null ? "Environment ready" : "Environment ready (reused)");
            } finally {
                buildLock.unlock();
            }
            afterReady.run();
        } catch (Superseded e) {
            job.stage("SUPERSEDED", e.getMessage());
        } catch (ProcessControl.Stopped e) {
            job.ownershipUncertain = !e.ownershipConfirmed;
            if (e.cancelled) {
                job.stage("CANCELLED", "Cancelled; the owned process was stopped");
            } else {
                fail(job, Diagnostic.of("PREPARATION_TIMEOUT", "Preparation exceeded " + config.prepareTimeoutMillis + " ms", null,
                        "Check network access to the package source, or raise prepareTimeoutMillis."));
            }
        } catch (StepFailure e) {
            fail(job, e.diagnostic);
        } catch (Interpreters.Unavailable e) {
            job.ownershipUncertain = !e.ownershipConfirmed;
            fail(job, Diagnostic.of("RUNTIME_UNAVAILABLE", e.getMessage(), null, "Ask the operator to install or approve the runtime."));
        } catch (InterruptedException e) {
            job.ownershipUncertain = e instanceof ProcessControl.OwnedInterrupted && !((ProcessControl.OwnedInterrupted) e).ownershipConfirmed;
            job.stage("INTERRUPTED", "The preparation service stopped");
            Thread.currentThread().interrupt();
        } catch (ApiFailure e) {
            fail(job, Diagnostic.of(e.code, e.getMessage(), null, null));
        } catch (Exception e) {
            job.ownershipUncertain |= e instanceof ProcessControl.OwnershipUncertain;
            logger.warn("Python environment preparation failed", e);
            fail(job, Diagnostic.of("PREPARATION_FAILED", e.getClass().getSimpleName() + ": " + e.getMessage(), null, null));
        } catch (Error e) {
            // A failed worker must never remain "preparing" after it has left
            // the active queue. Preserve the Error for the executor as well.
            fail(job, Diagnostic.of("PREPARATION_FAILED", e.getClass().getSimpleName() + ": " + e.getMessage(), null, null));
            throw e;
        } finally {
            job.ownershipUncertain |= ProcessControl.hasUncertainOwnership();
            active.remove(job.dedupeKey, job);
            if (job.ownershipUncertain) {
                job.line("Owned process termination is uncertain; pids=" + ProcessControl.uncertainPids() + "; resources retained at " + staging);
                if (job.view.artifactDigest != null) {
                    try { Files.writeString(artifacts.artifactDir(job.view.artifactDigest).resolve("ownership-uncertain"), job.view.id); }
                    catch (IOException e) { logger.warn("Could not mark retained preparation artifact", e); }
                }
                logger.warn("Preparation process ownership is uncertain; retaining " + staging + " and artifact " + job.view.artifactDigest);
            } else {
                try {
                    ProcessControl.deleteOwnedTree(cacheDir, staging);
                } catch (IOException e) {
                    logger.warn("Could not remove preparation staging " + staging + ": " + e.getMessage());
                }
            }
            if (!job.ownershipUncertain && !"READY".equals(job.view.state) && job.view.artifactDigest != null) {
                artifacts.removeUnpublishedBuilds(job.view.artifactDigest);
            }
            persistLog(job);
        }
    }

    private void fail(Job job, Diagnostic diagnostic) {
        synchronized (job) {
            job.view.diagnostics.add(diagnostic);
        }
        if (job.cancel) {
            job.stage("CANCELLED", "Cancelled");
        } else {
            job.stage("FAILED", diagnostic.message);
        }
    }

    /** The profile if the captured definition is still current; otherwise the job is superseded. */
    private Profile current(Job job) throws Superseded, ProcessControl.Stopped {
        if (job.cancel) {
            throw new ProcessControl.Stopped("Cancelled", true);
        }
        if (job.deadlineRemaining() <= 0) throw new ProcessControl.Stopped("Preparation deadline passed", false);
        Registry snapshot = registry.snapshot();
        if (!job.view.registryEpoch.equals(snapshot.registryEpoch)) {
            throw new Superseded("The environment registry was restored or replaced during preparation");
        }
        Profile profile = snapshot.profiles.get(job.view.profileId);
        if (profile == null || profile.archived) {
            throw new Superseded("The environment was archived during preparation");
        }
        if (!job.view.definitionFingerprint.equals(profile.definitionFingerprint)) {
            throw new Superseded("The environment definition changed during preparation; prepare the new revision");
        }
        return profile;
    }

    private ResolvedLock resolve(Job job, Profile profile, Interpreters.Target target, OperatorConfig.PackageSource source,
            Map<String, String> env, Path staging) throws Exception {
        String requirements = Requirements.normalize(profile.requirementsText);
        List<Diagnostic> problems = Requirements.precheck(requirements, config.maxRequirementsBytes);
        if (!problems.isEmpty()) {
            throw new StepFailure(problems.get(0).code, problems.get(0).message, problems.get(0).suggestedAction);
        }
        List<LockedRequirement> locked = new ArrayList<>();
        if (!requirements.trim().isEmpty() && hasRequirementLines(requirements)) {
            Path resolver = resolverPython(job, target);
            Path reqFile = staging.resolve("requirements.txt");
            Files.write(reqFile, requirements.getBytes(StandardCharsets.UTF_8));
            Path downloads = staging.resolve("downloads");
            Files.createDirectories(downloads);
            List<String> command = new ArrayList<>(Arrays.asList(resolver.toString(), "-I", "-m", "pip", "download", "--only-binary=:all:",
                    "--dest", downloads.toString(), "--progress-bar", "off", "-r", reqFile.toString()));
            command.addAll(sourceArguments(source));
            ProcessControl.Result result = run(job, command, env, staging);
            if (result.exitCode != 0) {
                throw resolutionFailure(result);
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(downloads)) {
                for (Path file : files) {
                    locked.add(lockEntry(file));
                }
            }
            for (LockedRequirement entry : locked) {
                storeWheel(downloads.resolve(entry.filename), entry);
            }
        }
        locked.sort((a, b) -> a.name.compareTo(b.name));
        ResolvedLock lock = new ResolvedLock();
        lock.definitionFingerprint = profile.definitionFingerprint;
        lock.targetFingerprint = target.fingerprint;
        lock.packageSourcePolicy = source.policyIdentity();
        lock.lockedRequirements = locked;
        lock.lockDigest = lockDigest(target.fingerprint, locked);
        lock.resolvedAt = Instant.now().toString();
        lock.resolvedOnNode = config.nodeId;
        job.line("Resolved " + locked.size() + " package(s): " + describe(locked));
        return lock;
    }

    private static boolean hasRequirementLines(String requirements) {
        for (String line : requirements.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                return true;
            }
        }
        return false;
    }

    private static String describe(List<LockedRequirement> locked) {
        List<String> parts = new ArrayList<>();
        for (LockedRequirement r : locked) {
            parts.add(r.name + "==" + r.version);
        }
        return parts.isEmpty() ? "(standard library only)" : String.join(", ", parts);
    }

    private StepFailure resolutionFailure(ProcessControl.Result result) {
        String tail = redact(result.tail(15));
        String lower = tail.toLowerCase(Locale.ROOT);
        if (lower.contains("invalid requirement") || lower.contains("expected ") && lower.contains("requirement")) {
            return new StepFailure("REQUIREMENT_SYNTAX", "pip could not parse the requirements:\n" + tail, "Fix the reported requirement line.");
        }
        if (lower.contains("no matching distribution") || lower.contains("could not find a version")) {
            return new StepFailure("NO_MATCHING_WHEEL", "No compatible wheel was found:\n" + tail,
                    "Pin a version that publishes a wheel for this Python version and platform, or add it to the wheelhouse.");
        }
        if (lower.contains("resolutionimpossible") || lower.contains("conflict")) {
            return new StepFailure("DEPENDENCY_CONFLICT", "The requirements conflict:\n" + tail, "Relax the conflicting version constraints.");
        }
        if (lower.contains("connection") || lower.contains("network") || lower.contains("timed out") || lower.contains("name resolution")) {
            return new StepFailure("SOURCE_UNAVAILABLE", "The package source could not be reached:\n" + tail,
                    "Check network access, or use an offline wheelhouse source.");
        }
        return new StepFailure("RESOLUTION_FAILED", "Dependency resolution failed:\n" + tail, null);
    }

    LockedRequirement lockEntry(Path file) throws StepFailure, IOException {
        String filename = file.getFileName().toString();
        Matcher m = WHEEL.matcher(filename);
        if (!m.matches()) {
            throw new StepFailure("NOT_A_WHEEL", "The package source returned " + filename + ", which is not a wheel",
                    "Source builds are not supported; use packages that publish wheels.");
        }
        LockedRequirement entry = new LockedRequirement();
        entry.name = normalizeName(m.group(1));
        entry.version = m.group(2);
        entry.filename = filename;
        entry.sha256 = sha256(file);
        for (String py : m.group(4).split("\\.")) {
            for (String abi : m.group(5).split("\\.")) {
                for (String plat : m.group(6).split("\\.")) {
                    entry.tags.add(py + "-" + abi + "-" + plat);
                }
            }
        }
        if (entry.name.equals(Sdk.DISTRIBUTION)) {
            throw new StepFailure("SDK_PROTECTED", "A dependency tried to install oie-python, which the extension manages", null);
        }
        return entry;
    }

    static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }

    private void storeWheel(Path file, LockedRequirement entry) throws IOException {
        Path dir = wheelsDir.resolve(entry.sha256);
        Path target = dir.resolve(entry.filename);
        if (Files.isRegularFile(target) && entry.sha256.equals(sha256(target))) {
            return;
        }
        Files.createDirectories(dir);
        Path tmp = dir.resolve(entry.filename + "." + UUID.randomUUID() + ".tmp");
        Files.copy(file, tmp);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Fetches any locked wheels missing from this node's cache, checking their hashes. */
    private void ensureWheels(Job job, Interpreters.Target target, OperatorConfig.PackageSource source, Map<String, String> env,
            ResolvedLock lock, Path staging) throws Exception {
        List<LockedRequirement> missing = new ArrayList<>();
        for (LockedRequirement r : lock.lockedRequirements) {
            Path cached = wheelsDir.resolve(r.sha256).resolve(r.filename);
            if (!Files.isRegularFile(cached) || !r.sha256.equals(sha256(cached))) {
                missing.add(r);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        job.line("Downloading " + missing.size() + " locked wheel(s) missing from this node");
        Path reqFile = staging.resolve("locked-missing.txt");
        StringBuilder text = new StringBuilder();
        for (LockedRequirement r : missing) {
            text.append(r.name).append("==").append(r.version).append(" --hash=sha256:").append(r.sha256).append('\n');
        }
        Files.write(reqFile, text.toString().getBytes(StandardCharsets.UTF_8));
        Path downloads = staging.resolve("downloads");
        Files.createDirectories(downloads);
        List<String> command = new ArrayList<>(Arrays.asList(resolverPython(job, target).toString(), "-I", "-m", "pip", "download",
                "--only-binary=:all:", "--no-deps", "--require-hashes", "--dest", downloads.toString(), "--progress-bar", "off", "-r",
                reqFile.toString()));
        command.addAll(sourceArguments(source));
        ProcessControl.Result result = run(job, command, env, staging);
        if (result.exitCode != 0) {
            throw new StepFailure("LOCKED_WHEEL_UNAVAILABLE", "Locked wheels could not be downloaded:\n" + redact(result.tail(10)),
                    "Check the package source, or refresh dependencies to resolve again.");
        }
        for (LockedRequirement r : missing) {
            Path file = downloads.resolve(r.filename);
            if (!Files.isRegularFile(file) || !r.sha256.equals(sha256(file))) {
                throw new StepFailure("HASH_MISMATCH", "Downloaded " + r.filename + " does not match the locked SHA-256", null);
            }
            storeWheel(file, r);
        }
    }

    private ResolvedLock commitLock(Job job, ResolvedLock candidate) throws Superseded, ProcessControl.Stopped {
        while (true) {
        if (job.cancel) throw new ProcessControl.Stopped("Cancelled", true);
        if (job.deadlineRemaining() <= 0) throw new ProcessControl.Stopped("Timed out while committing the lock", false);
        final ResolvedLock[] winner = new ResolvedLock[1];
        final String[] superseded = new String[1];
        try {
        registry.change(next -> {
            if (!job.view.registryEpoch.equals(next.registryEpoch)) {
                superseded[0] = "The environment registry was restored or replaced during preparation";
                return null;
            }
            Profile p = next.profiles.get(job.view.profileId);
            if (p == null || p.archived || !job.view.definitionFingerprint.equals(p.definitionFingerprint)) {
                superseded[0] = "The environment definition changed during preparation; prepare the new revision";
                return null;
            }
            ResolvedLock existing = p.resolvedLocks.get(candidate.targetFingerprint);
            if (existing != null && candidate.definitionFingerprint.equals(existing.definitionFingerprint)) {
                winner[0] = existing;
                return null;
            }
            p.resolvedLocks.put(candidate.targetFingerprint, candidate);
            winner[0] = candidate;
            return Boolean.TRUE;
        });
        } catch (ApiFailure conflict) {
            if (!"REGISTRY_WRITE_CONFLICT".equals(conflict.code)) throw conflict;
            job.line("Registry changed during lock commit; checking the winning committed lock");
            continue;
        }
        if (superseded[0] != null) {
            throw new Superseded(superseded[0]);
        }
        if (winner[0] != candidate) {
            job.line("Another preparation committed lock " + winner[0].lockDigest + " first; using it");
        } else {
            job.line("Committed lock " + candidate.lockDigest);
        }
        return winner[0];
        }
    }

    private Path build(Job job, Interpreters.Target target, ResolvedLock lock, Path build, Path venv, Path staging) throws Exception {
        Path wheelhouse = build.resolve("wheelhouse");
        Files.createDirectories(wheelhouse);
        StringBuilder pins = new StringBuilder();
        for (LockedRequirement r : lock.lockedRequirements) {
            Path cached = wheelsDir.resolve(r.sha256).resolve(r.filename);
            if (!r.sha256.equals(sha256(cached))) {
                throw new StepFailure("HASH_MISMATCH", "Cached wheel " + r.filename + " no longer matches its locked hash", "Prepare again.");
            }
            Files.copy(cached, wheelhouse.resolve(r.filename), StandardCopyOption.REPLACE_EXISTING);
            pins.append(r.name).append("==").append(r.version).append(" --hash=sha256:").append(r.sha256).append('\n');
        }
        Files.copy(sdk.wheelFile(), wheelhouse.resolve(sdk.wheelName()), StandardCopyOption.REPLACE_EXISTING);
        pins.append(Sdk.DISTRIBUTION).append("==").append(Sdk.VERSION).append(" --hash=sha256:").append(sdk.wheelSha256()).append('\n');
        Path lockFile = build.resolve("requirements.lock");
        Files.write(lockFile, pins.toString().getBytes(StandardCharsets.UTF_8));

        Map<String, String> env = new TreeMap<>(baseEnv);
        ProcessControl.Result created = run(job, Arrays.asList(target.executable.toString(), "-I", "-m", "venv", venv.toString()), env, staging);
        if (created.exitCode != 0) {
            throw new StepFailure("VENV_FAILED", "Creating the virtual environment failed:\n" + redact(created.tail(10)),
                    "Install the venv and ensurepip modules for this interpreter.");
        }
        Path python = ProcessControl.venvPython(venv);
        verifyInstaller(job, python, target, staging);
        ProcessControl.Result installed = run(job, Arrays.asList(python.toString(), "-I", "-m", "pip", "install", "--no-index", "--find-links",
                wheelhouse.toString(), "--require-hashes", "--only-binary=:all:", "--no-deps", "--progress-bar", "off", "-r",
                lockFile.toString()), env, staging);
        if (installed.exitCode != 0) {
            throw new StepFailure("INSTALL_FAILED", "Installing the locked wheels failed:\n" + redact(installed.tail(15)), null);
        }
        ProcessControl.deleteOwnedTree(cacheDir, wheelhouse);
        return python;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> verify(Job job, Path python, ResolvedLock lock, Path staging) throws Exception {
        Map<String, String> env = new TreeMap<>(baseEnv);
        ProcessControl.Result check = run(job, Arrays.asList(python.toString(), "-I", "-m", "pip", "check"), env, staging);
        if (check.exitCode != 0) {
            throw new StepFailure("DEPENDENCY_CHECK_FAILED", "pip check reported problems:\n" + redact(check.tail(15)),
                    "Add the missing dependency or relax constraints, then refresh dependencies.");
        }
        ProcessControl.Result list = ProcessControl.runPython(Arrays.asList(python.toString(), "-I", "-m", "pip", "list", "--format=json"), env,
                staging, null, job.deadlineRemaining(), () -> job.cancel, null, 50, config.maxFrameBytes);
        if (list.exitCode != 0) {
            throw new StepFailure("VERIFY_FAILED", "Could not list installed packages:\n" + redact(list.tail(5)), null);
        }
        List<Map<String, Object>> installed = mapper.readValue(String.join("\n", list.output), List.class);
        Map<String, String> versions = new HashMap<>();
        for (Map<String, Object> pkg : installed) {
            versions.put(normalizeName(String.valueOf(pkg.get("name"))), String.valueOf(pkg.get("version")));
        }
        List<Map<String, String>> packages = new ArrayList<>();
        for (LockedRequirement r : lock.lockedRequirements) {
            String version = versions.get(r.name);
            if (version == null || !normalizeVersion(version).equals(normalizeVersion(r.version))) {
                throw new StepFailure("VERIFY_FAILED", "Installed " + r.name + " " + version + " does not match the locked " + r.version, null);
            }
            Map<String, String> pkg = new LinkedHashMap<>();
            pkg.put("name", r.name);
            pkg.put("version", r.version);
            pkg.put("sha256", r.sha256);
            packages.add(pkg);
        }
        if (!Sdk.VERSION.equals(versions.get(Sdk.DISTRIBUTION))) {
            throw new StepFailure("SDK_MISMATCH", "The environment has oie-python " + versions.get(Sdk.DISTRIBUTION) + "; expected " + Sdk.VERSION,
                    null);
        }
        verifyHandshake(job, python, staging, lock.targetFingerprint);
        return packages;
    }

    private static String normalizeVersion(String version) {
        return version.replace('_', '-').toLowerCase(Locale.ROOT);
    }

    private void verifyHandshake(Job job, Path python, Path staging, String expectedTarget) throws Exception {
        Map<String, String> env = new TreeMap<>(baseEnv);
        env.put("OIE_PYTHON_SDK_SOURCE", sdk.sourceDir().toString());
        long remaining = job.deadlineRemaining();
        if (remaining <= 0) throw new ProcessControl.Stopped("Preparation deadline passed", false);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(config.startupTimeoutMillis, remaining));
        Worker worker;
        try {
            worker = Worker.start(python, env, staging, "prepare " + job.view.profileId, config.maxFrameBytes, config.terminationGraceMillis,
                    deadline);
        } catch (Worker.WorkerException e) {
            throw new StepFailure("HANDSHAKE_FAILED", "The worker handshake failed: " + e.getMessage(), "Prepare again.");
        }
        try {
            worker.confirmTarget(expectedTarget);
            job.line("Worker handshake: Python " + ((Map<?, ?>) worker.hello().get("python")).get("version") + ", SDK " + worker.hello().get("sdk"));
        } finally {
            worker.terminate("verification complete");
            if (!worker.terminationConfirmed()) throw new ProcessControl.OwnershipUncertain("Verification worker pid " + worker.pid() + " did not confirm termination");
        }
    }

    private Path resolverPython(Job job, Interpreters.Target target) throws Exception {
        Path dir = cacheDir.resolve("resolver").resolve(target.fingerprint.substring(0, 24) + "-"
                + CanonicalJson.sha256Hex(String.valueOf(target.description.get("executable")).getBytes(StandardCharsets.UTF_8)).substring(0, 8));
        Path python = ProcessControl.venvPython(dir.resolve("venv"));
        Path marker = dir.resolve("ready");
        ReentrantLock resolverLock = resolverLocks.computeIfAbsent(dir, ignored -> new ReentrantLock());
        if (!resolverLock.tryLock(Math.max(0, job.deadlineRemaining()), TimeUnit.MILLISECONDS)) {
            throw new StepFailure("RESOLVER_LOCK_TIMEOUT", "The interpreter resolver is still being prepared", "Prepare again.");
        }
        try {
        if (Files.isRegularFile(marker) && Files.exists(python)) {
            verifyInstaller(job, python, target, dir);
            return python;
        }
        job.line("Creating the isolated resolver environment");
        ProcessControl.deleteOwnedTree(cacheDir, dir);
        Files.createDirectories(dir);
        ProcessControl.Result created = run(job, Arrays.asList(target.executable.toString(), "-I", "-m", "venv", dir.resolve("venv").toString()),
                new TreeMap<>(baseEnv), dir);
        if (created.exitCode != 0) {
            throw new StepFailure("VENV_FAILED", "Creating the resolver environment failed:\n" + redact(created.tail(10)),
                    "Install the venv and ensurepip modules for this interpreter.");
        }
        verifyInstaller(job, python, target, dir);
        Files.write(marker, Instant.now().toString().getBytes(StandardCharsets.UTF_8));
        return python;
        } finally {
            resolverLock.unlock();
        }
    }

    private void verifyInstaller(Job job, Path python, Interpreters.Target target, Path cwd) throws Exception {
        Object bootstrap = target.description.get("installerBootstrap");
        if (!(bootstrap instanceof Map) || ((Map<?, ?>) bootstrap).get("version") == null) {
            throw new StepFailure("INSTALLER_UNPINNED", "The interpreter did not report its installer bootstrap identity", null);
        }
        String version = String.valueOf(((Map<?, ?>) bootstrap).get("version"));
        ProcessControl.Result result = run(job, Arrays.asList(python.toString(), "-I", "-m", "pip", "--version"), new TreeMap<>(baseEnv), cwd);
        if (result.exitCode != 0 || result.output.isEmpty() || !result.output.get(0).startsWith("pip " + version + " from ")) {
            throw new StepFailure("INSTALLER_MISMATCH", "The virtual environment installer does not match the pinned interpreter bootstrap " + version, "Prepare again.");
        }
    }

    private List<String> sourceArguments(OperatorConfig.PackageSource source) {
        if (source.isBuiltIn()) return Collections.singletonList("--no-index");
        if (source.isWheelhouse()) {
            return Arrays.asList("--no-index", "--find-links", source.wheelhouse);
        }
        return Collections.emptyList();
    }

    /** pip settings for a source. Index URLs travel in the environment so credentials stay off the command line. */
    private Map<String, String> pipEnvironment(OperatorConfig.PackageSource source) {
        Map<String, String> env = new TreeMap<>(baseEnv);
        env.put("PIP_CACHE_DIR", cacheDir.resolve("pip-cache").toString());
        if (source.isBuiltIn()) { env.put("PIP_NO_INDEX", "1"); return env; }
        if (!source.isWheelhouse()) {
            env.put("PIP_INDEX_URL", withCredentials(source.indexUrl, source));
            if (!source.extraIndexUrls.isEmpty()) {
                List<String> extras = new ArrayList<>();
                for (String url : source.extraIndexUrls) {
                    extras.add(withCredentials(url, source));
                }
                env.put("PIP_EXTRA_INDEX_URL", String.join(" ", extras));
            }
        }
        return env;
    }

    private static String withCredentials(String url, OperatorConfig.PackageSource source) {
        if (source.username.isEmpty() || url.contains("@")) {
            return url;
        }
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return url;
        }
        String user = URLEncoder.encode(source.username, StandardCharsets.UTF_8);
        String password = URLEncoder.encode(source.password, StandardCharsets.UTF_8);
        return url.substring(0, scheme + 3) + user + (password.isEmpty() ? "" : ":" + password) + "@" + url.substring(scheme + 3);
    }

    String redact(String text) {
        if (text == null) {
            return "";
        }
        String out = text.replaceAll("://[^/\\s@]+@", "://***@");
        for (OperatorConfig.PackageSource source : config.sources.values()) {
            if (!source.password.isEmpty()) {
                out = out.replace(source.password, "***");
                out = out.replace(URLEncoder.encode(source.password, StandardCharsets.UTF_8), "***");
            }
        }
        return out.replace(cacheDir.toString(), "<cache>");
    }

    private ProcessControl.Result run(Job job, List<String> command, Map<String, String> env, Path cwd) throws Exception {
        long remaining = job.deadlineRemaining();
        if (remaining <= 0) {
            throw new ProcessControl.Stopped("Timed out", false);
        }
        job.line("$ " + String.join(" ", command));
        ProcessControl.Result result = ProcessControl.runPython(command, env, cwd, null, remaining, () -> job.cancel, job::line, 400, 4000);
        job.line("Owned command pid=" + result.pid + " exited with status=" + result.exitCode);
        return result;
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- Job records ----------------------------------------------------------------------

    private void remember(Job job) {
        jobs.put(job.view.id, job);
        if (jobs.size() > MAX_JOBS_RETAINED) {
            jobs.values().stream().filter(j -> TERMINAL.contains(j.view.state)).sorted((a, b) -> a.view.createdAt.compareTo(b.view.createdAt))
                    .limit(jobs.size() - MAX_JOBS_RETAINED).map(j -> j.view.id).forEach(jobs::remove);
        }
    }

    private void persist(Job job) {
        try {
            Path tmp = jobsDir.resolve(job.view.id + ".json." + UUID.randomUUID() + ".tmp");
            Files.write(tmp, mapper.writeValueAsBytes(job.view));
            Files.move(tmp, jobsDir.resolve(job.view.id + ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.debug("Could not record preparation job " + job.view.id + ": " + e.getMessage());
        }
    }

    private void persistLog(Job job) {
        try {
            synchronized (job) {
                Files.write(jobsDir.resolve(job.view.id + ".log"), job.log, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            logger.debug("Could not record preparation log " + job.view.id + ": " + e.getMessage());
        }
    }

    private JobView readStoredJob(String jobId) {
        if (jobId == null || !jobId.matches("[0-9a-fA-F-]{36}(?:~[A-Za-z0-9_-]+)?")) {
            return null;
        }
        Path file = jobsDir.resolve(jobId + ".json");
        try {
            return Files.isRegularFile(file) ? mapper.readValue(file.toFile(), JobView.class) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void checkJobOwner(String jobId) {
        if (jobId == null || !jobId.matches("[0-9a-fA-F-]{36}(?:~[A-Za-z0-9_-]+)?")) throw ApiFailure.notFound("Preparation job does not exist");
        int split = jobId.indexOf('~');
        if (split < 0) return; // Existing local records from the earlier format.
        String node;
        try { node = new String(java.util.Base64.getUrlDecoder().decode(jobId.substring(split + 1)), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException failure) { throw ApiFailure.notFound("Preparation job does not exist"); }
        if (!config.nodeId.equals(node)) {
            ApiFailure failure = ApiFailure.conflict("JOB_ON_DIFFERENT_NODE", "Preparation job belongs to node " + node);
            failure.ownerNodeId = node;
            throw failure;
        }
    }

    /** Marks jobs left unfinished by a previous run as INTERRUPTED. */
    private void recoverInterruptedJobs() {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(jobsDir, "*.json")) {
            for (Path file : files) {
                try {
                    JobView view = mapper.readValue(file.toFile(), JobView.class);
                    if (!TERMINAL.contains(view.state)) {
                        view.state = "INTERRUPTED";
                        view.message = "The engine stopped before preparation finished";
                        view.finishedAt = Instant.now().toString();
                        Files.write(file, mapper.writeValueAsBytes(view));
                    }
                } catch (IOException e) {
                    logger.debug("Skipping unreadable job record " + file + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.debug("Could not scan preparation jobs: " + e.getMessage());
        }
    }

    private void cleanStaging() {
        Path staging = cacheDir.resolve("staging");
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(staging)) {
            for (Path dir : dirs) {
                ProcessControl.deleteOwnedTree(cacheDir, dir);
            }
        } catch (IOException e) {
            logger.debug("Could not clean preparation staging: " + e.getMessage());
        }
    }
}
