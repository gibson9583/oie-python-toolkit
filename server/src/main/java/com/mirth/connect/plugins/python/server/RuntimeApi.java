/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;

import com.mirth.connect.plugins.python.CanonicalJson;
import com.mirth.connect.plugins.python.PythonApi;
import com.mirth.connect.plugins.python.PythonApi.Diagnostic;
import com.mirth.connect.plugins.python.PythonApi.ExportBundle;
import com.mirth.connect.plugins.python.PythonApi.ExportedProfile;
import com.mirth.connect.plugins.python.PythonApi.ImportRequest;
import com.mirth.connect.plugins.python.PythonApi.ImportResult;
import com.mirth.connect.plugins.python.PythonApi.MutationResult;
import com.mirth.connect.plugins.python.PythonApi.Profile;
import com.mirth.connect.plugins.python.PythonApi.ProfileList;
import com.mirth.connect.plugins.python.PythonApi.ProfileRequest;
import com.mirth.connect.plugins.python.PythonApi.Registry;
import com.mirth.connect.plugins.python.PythonApi.ResolvedLock;
import com.mirth.connect.plugins.python.PythonApi.RevisionRequest;
import com.mirth.connect.plugins.python.PythonApi.ScriptRequest;
import com.mirth.connect.plugins.python.PythonApi.TestResult;
import com.mirth.connect.plugins.python.PythonApi.ValidationResult;
import com.mirth.connect.plugins.python.PythonContract;
import com.mirth.connect.plugins.python.PythonScriptConfiguration;

/** The behavior behind the REST API. Authorization happens in the servlet first. */
public final class RuntimeApi {

    public static final int MAX_NAME = 200;
    public static final int MAX_DESCRIPTION = 2000;
    public static final int MAX_SOURCE_BYTES = 1024 * 1024;

    private final PythonRuntime runtime;
    private final Semaphore tests;

    public RuntimeApi(PythonRuntime runtime) {
        this.runtime = runtime;
        this.tests = new Semaphore(runtime.config.maxConcurrentTests);
    }

    // ---- Runtime --------------------------------------------------------------------------

    public PythonApi.RuntimeInfo runtimeInfo() {
        PythonApi.RuntimeInfo info = new PythonApi.RuntimeInfo();
        info.nodeId = runtime.config.nodeId;
        info.sdkVersion = Sdk.VERSION;
        info.protocolVersion = Sdk.PROTOCOL_VERSION;
        Registry registry = runtime.registry.snapshot();
        info.registryEpoch = registry.registryEpoch;
        info.defaultProfileId = BuiltInEnvironment.defaultId(registry);
        info.defaultProfileMessage = runtime.builtIn.failure();
        for (OperatorConfig.Runtime r : runtime.config.runtimes.values()) {
            PythonApi.RuntimeOption option = new PythonApi.RuntimeOption();
            option.id = r.id;
            option.label = r.label;
            try {
                Interpreters.Target target = runtime.interpreters.probe(r.id);
                option.available = true;
                option.version = target.version;
                option.targetFingerprint = target.fingerprint;
            } catch (Interpreters.Unavailable e) {
                option.message = e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                option.message = "Interrupted";
            }
            info.runtimes.add(option);
        }
        for (OperatorConfig.PackageSource s : runtime.config.sources.values()) {
            PythonApi.SourceOption option = new PythonApi.SourceOption();
            option.id = s.id;
            option.label = s.label;
            option.kind = s.isBuiltIn() ? "builtin" : s.isWheelhouse() ? "wheelhouse" : "index";
            info.packageSources.add(option);
        }
        info.limits.putAll(runtime.config.limits());
        info.limits.putAll(runtime.metrics());
        info.limits.put("environmentCacheBytes", runtime.artifacts.sizeBytes());
        return info;
    }

    // ---- Profiles ---------------------------------------------------------------------------

    public ProfileList list(boolean includeArchived) {
        Registry registry = runtime.registry.snapshot();
        ProfileList list = new ProfileList();
        list.registryEpoch = registry.registryEpoch;
        list.registryRevision = registry.registryRevision;
        list.nodeId = runtime.config.nodeId;
        list.defaultProfileId = BuiltInEnvironment.defaultId(registry);
        list.defaultProfileMessage = runtime.builtIn.failure();
        Map<String, Integer> saved = savedReferenceCounts();
        for (Profile p : registry.profiles.values()) {
            if (p.archived && !includeArchived) {
                continue;
            }
            PythonApi.ProfileView view = runtime.view(p, registry.registryEpoch);
            view.savedReferenceCount = saved.getOrDefault(p.id, 0);
            list.profiles.add(view);
        }
        list.profiles.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return list;
    }

    public PythonApi.ProfileView get(String id) {
        Registry registry = runtime.registry.snapshot();
        PythonApi.ProfileView view = runtime.view(RegistryStore.requireProfile(registry, id), registry.registryEpoch);
        view.savedReferenceCount = savedReferenceCounts().getOrDefault(id, 0);
        return view;
    }

    public MutationResult create(String actor, ProfileRequest request) {
        checkDefinition(request);
        // Derived from the request identity so a replayed request reports the profile it created.
        String id = UUID.nameUUIDFromBytes(("create|" + request.expectedRegistryEpoch + "|" + actor + "|" + request.requestId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        RegistryStore.Outcome<String> outcome = runtime.registry.mutate(actor, request.requestId, "create", id, request,
                request.expectedRegistryEpoch, registry -> {
                    if (registry.profiles.containsKey(id)) {
                        throw ApiFailure.conflict("REQUEST_ID_REUSED", "This requestId already created an environment");
                    }
                    checkUniqueName(registry, request.name, null);
                    Profile p = new Profile();
                    p.id = id;
                    p.revision = 1;
                    p.createdAt = Instant.now().toString();
                    apply(p, request, actor);
                    registry.profiles.put(p.id, p);
                    return p.id;
                }, registry -> id);
        return result(request.requestId, outcome, outcome.value);
    }

    public MutationResult update(String actor, String id, ProfileRequest request) {
        BuiltInEnvironment.requireWritable(id);
        checkDefinition(request);
        RegistryStore.Outcome<String> outcome = runtime.registry.mutate(actor, request.requestId, "update", id, request,
                request.expectedRegistryEpoch, registry -> {
                    Profile p = RegistryStore.requireProfile(registry, id);
                    RegistryStore.checkRevision(p, request.expectedRevision);
                    if (p.archived) {
                        throw ApiFailure.conflict("PROFILE_ARCHIVED", "Environment \"" + BuiltInEnvironment.displayName(p) + "\" is archived");
                    }
                    checkUniqueName(registry, request.name, id);
                    apply(p, request, actor);
                    p.revision++;
                    return id;
                }, registry -> id);
        return result(request.requestId, outcome, id);
    }

    public MutationResult archive(String actor, String id, RevisionRequest request) {
        BuiltInEnvironment.requireWritable(id);
        RegistryStore.Outcome<String> outcome = runtime.registry.mutate(actor, request.requestId, "archive", id, request,
                request.expectedRegistryEpoch, registry -> {
                    Profile p = RegistryStore.requireProfile(registry, id);
                    RegistryStore.checkRevision(p, request.expectedRevision);
                    if (p.archived) {
                        return id;
                    }
                    int saved = savedReferenceCounts().getOrDefault(id, 0);
                    int deployed = deployedReferenceCount(id);
                    if (saved > 0 || deployed > 0) {
                        ApiFailure failure = ApiFailure.conflict("PROFILE_IN_USE", "Environment \"" + BuiltInEnvironment.displayName(p) + "\" is used by " + saved
                                + " saved script(s) and " + deployed + " deployed channel(s). Remove those references first.");
                        failure.currentRevision = p.revision;
                        throw failure;
                    }
                    if (runtime.preparation.hasActiveJob(id, registry.registryEpoch, p.revision)) {
                        throw ApiFailure.conflict("PREPARATION_ACTIVE", "Environment \"" + BuiltInEnvironment.displayName(p) + "\" is being prepared. Cancel it first.");
                    }
                    p.archived = true;
                    p.revision++;
                    p.updatedAt = Instant.now().toString();
                    p.updatedBy = actor;
                    return id;
                }, registry -> id);
        return result(request.requestId, outcome, id);
    }

    public MutationResult refresh(String actor, String id, RevisionRequest request) {
        BuiltInEnvironment.requireWritable(id);
        RegistryStore.Outcome<String> outcome = runtime.registry.mutate(actor, request.requestId, "refresh", id, request,
                request.expectedRegistryEpoch, registry -> {
                    Profile p = RegistryStore.requireProfile(registry, id);
                    RegistryStore.checkRevision(p, request.expectedRevision);
                    if (p.archived) {
                        throw ApiFailure.conflict("PROFILE_ARCHIVED", "Environment \"" + BuiltInEnvironment.displayName(p) + "\" is archived");
                    }
                    p.refreshGeneration++;
                    p.definitionFingerprint = PreparationManager.definitionFingerprint(p);
                    p.revision++;
                    p.updatedAt = Instant.now().toString();
                    p.updatedBy = actor;
                    return id;
                }, registry -> id);
        return result(request.requestId, outcome, id);
    }

    public MutationResult prepare(String id, RevisionRequest request, PreparationManager.Authorizer authorizer) {
        Registry registry = runtime.registry.snapshot();
        Profile profile = RegistryStore.requireProfile(registry, id);
        if (PythonContract.DEFAULT_PROFILE_ID.equals(id) && !BuiltInEnvironment.owned(profile)) throw ApiFailure.conflict("DEFAULT_ENVIRONMENT_UNAVAILABLE", "The reserved default environment identity conflicts with another definition; existing data was preserved.");
        PythonApi.JobView job = runtime.preparation.prepare(id, request.expectedRegistryEpoch, request.expectedRevision, authorizer);
        MutationResult result = new MutationResult();
        result.requestId = request.requestId;
        result.registryEpoch = registry.registryEpoch;
        result.registryRevision = registry.registryRevision;
        PythonApi.LocalReadiness readiness = new PythonApi.LocalReadiness();
        readiness.nodeId = runtime.config.nodeId;
        readiness.state = PreparationManager.TERMINAL.contains(job.state) ? "not-prepared" : "preparing";
        readiness.jobId = job.id;
        readiness.message = job.message;
        // Actual interpreter/readiness verification belongs to the queued job,
        // including when no earlier profile GET warmed the interpreter cache.
        result.profile = runtime.view(profile, registry.registryEpoch, readiness);
        result.job = job;
        return result;
    }

    private MutationResult result(String requestId, RegistryStore.Outcome<String> outcome, String id) {
        MutationResult result = new MutationResult();
        result.requestId = requestId;
        result.registryEpoch = outcome.registry.registryEpoch;
        result.registryRevision = outcome.registry.registryRevision;
        result.replayed = outcome.replayed;
        Profile p = id == null ? null : outcome.registry.profiles.get(id);
        result.profile = p == null ? null : runtime.view(p, outcome.registry.registryEpoch);
        return result;
    }

    private void apply(Profile p, ProfileRequest request, String actor) {
        p.name = request.name.trim();
        p.description = request.description == null ? "" : request.description;
        p.runtimeId = request.runtimeId;
        p.packageSourceId = request.packageSourceId;
        p.requirementsText = Requirements.normalize(request.requirementsText);
        p.definitionFingerprint = PreparationManager.definitionFingerprint(p);
        p.updatedAt = Instant.now().toString();
        p.updatedBy = actor;
    }

    private void checkDefinition(ProfileRequest request) {
        List<Diagnostic> problems = new ArrayList<>();
        if (request.name == null || request.name.trim().isEmpty()) {
            problems.add(Diagnostic.of("NAME_REQUIRED", "Enter a name", "name", null));
        } else if (request.name.length() > MAX_NAME) {
            problems.add(Diagnostic.of("NAME_TOO_LONG", "Use at most " + MAX_NAME + " characters", "name", null));
        }
        if (request.description != null && request.description.length() > MAX_DESCRIPTION) {
            problems.add(Diagnostic.of("DESCRIPTION_TOO_LONG", "Use at most " + MAX_DESCRIPTION + " characters", "description", null));
        }
        if (request.runtimeId == null || !runtime.config.runtimes.containsKey(request.runtimeId)) {
            problems.add(Diagnostic.of("RUNTIME_NOT_APPROVED", "Choose an approved Python runtime", "runtimeId", null));
        }
        if (request.packageSourceId == null || !runtime.config.sources.containsKey(request.packageSourceId)) {
            problems.add(Diagnostic.of("SOURCE_NOT_APPROVED", "Choose an approved package source", "packageSourceId", null));
        }
        problems.addAll(Requirements.precheck(request.requirementsText, runtime.config.maxRequirementsBytes));
        if (PythonContract.BUILTIN_SOURCE_ID.equals(request.packageSourceId) && !BuiltInEnvironment.stdlibOnly(request.requirementsText)) {
            problems.add(Diagnostic.of("STDLIB_ONLY", "The standard-library source accepts no package requirements", "requirementsText", "Choose an approved package source for third-party dependencies."));
        }
        if (!problems.isEmpty()) {
            throw ApiFailure.badRequest("INVALID_PROFILE", problems.get(0).message).with(problems);
        }
    }

    private static void checkUniqueName(Registry registry, String name, String exceptId) {
        for (Profile p : registry.profiles.values()) {
            if (!p.archived && !p.id.equals(exceptId) && p.name.trim().equalsIgnoreCase(name.trim())) {
                throw ApiFailure.conflict("NAME_IN_USE", "Another environment is already named \"" + BuiltInEnvironment.displayName(p) + "\"");
            }
        }
    }

    // ---- References -------------------------------------------------------------------------

    private Map<String, Integer> savedReferenceCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (com.mirth.connect.model.Channel channel : runtime.engine.savedChannels()) {
            for (Surfaces.Surface surface : Surfaces.of(channel)) {
                String id = BuiltInEnvironment.selectedId(surface.config.getEnvironmentProfileId());
                if (id != null && !id.isEmpty()) {
                    counts.merge(id, 1, Integer::sum);
                }
            }
        }
        for (var template : runtime.engine.savedCodeTemplates()) {
            if (template.getProperties() instanceof com.mirth.connect.plugins.python.PythonCodeTemplateProperties config) {
                counts.merge(BuiltInEnvironment.selectedId(config.getEnvironmentProfileId()), 1, Integer::sum);
            }
        }
        return counts;
    }

    private int deployedReferenceCount(String profileId) {
        int count = 0;
        for (Deployments.Binding binding : runtime.deployments.bindings()) {
            if (binding.specs.values().stream().anyMatch(s -> profileId.equals(s.profileId))) {
                count++;
            }
        }
        return count;
    }

    public PythonApi.References references(String profileId, Predicate<String> visible) {
        Registry registry = runtime.registry.snapshot();
        Profile profile = RegistryStore.requireProfile(registry, profileId);
        PythonApi.References refs = new PythonApi.References();
        refs.profileId = profileId;
        refs.nodeId = runtime.config.nodeId;
        Set<String> hidden = new LinkedHashSet<>();
        for (com.mirth.connect.model.Channel channel : runtime.engine.savedChannels()) {
            for (Surfaces.Surface surface : Surfaces.of(channel)) {
                if (!profileId.equals(BuiltInEnvironment.selectedId(surface.config.getEnvironmentProfileId()))) {
                    continue;
                }
                if (!visible.test(channel.getId())) {
                    hidden.add(channel.getId());
                    continue;
                }
                PythonApi.SurfaceReference ref = new PythonApi.SurfaceReference();
                ref.channelId = channel.getId();
                ref.channelName = channel.getName();
                ref.connectorMetadataId = surface.metaDataId;
                ref.connectorName = surface.connectorName;
                ref.role = surface.role;
                ref.stepName = surface.stepName;
                ref.enabled = surface.enabled;
                refs.saved.add(ref);
            }
        }
        PythonApi.LocalReadiness readiness = runtime.readiness(profile, registry.registryEpoch);
        for (Deployments.Binding binding : runtime.deployments.bindings()) {
            for (BoundSpec spec : binding.specs.values()) {
                if (!profileId.equals(spec.profileId)) {
                    continue;
                }
                if (!visible.test(binding.channelId)) {
                    hidden.add(binding.channelId);
                    break;
                }
                PythonApi.DeploymentReference ref = new PythonApi.DeploymentReference();
                ref.channelId = binding.channelId;
                ref.channelName = binding.channelName;
                ref.attemptId = binding.attemptId;
                ref.artifactDigest = spec.artifact.digest;
                ref.lockDigest = spec.lockDigest;
                ref.deployedAt = binding.createdAt;
                ref.redeployNeeded = !spec.lockDigest.equals(readiness.lockDigest) || spec.profileRevision != profile.revision;
                refs.deployed.add(ref);
                break;
            }
        }
        // This endpoint is authorized for environment/channel usage, not native template viewing.
        // Count protected template references without disclosing template names or IDs.
        int templates = 0;
        for (var template : runtime.engine.savedCodeTemplates()) {
            if (template.getProperties() instanceof com.mirth.connect.plugins.python.PythonCodeTemplateProperties config
                    && profileId.equals(BuiltInEnvironment.selectedId(config.getEnvironmentProfileId()))) templates++;
        }
        refs.hiddenCount = hidden.size() + templates;
        return refs;
    }

    // ---- Import and export -------------------------------------------------------------------

    public ExportBundle export(Set<String> ids) {
        Registry registry = runtime.registry.snapshot();
        ExportBundle bundle = new ExportBundle();
        bundle.exportedAt = Instant.now().toString();
        for (Profile p : registry.profiles.values()) {
            // The installation owns this profile and chooses its approved
            // runtime locally. It is never an editable migration record.
            if (BuiltInEnvironment.owned(p) || p.archived || (ids != null && !ids.isEmpty() && !ids.contains(p.id))) {
                continue;
            }
            ExportedProfile e = new ExportedProfile();
            e.id = p.id;
            e.name = p.name;
            e.description = p.description;
            e.runtimeId = p.runtimeId;
            e.packageSourceId = p.packageSourceId;
            e.requirementsText = p.requirementsText;
            e.refreshGeneration = p.refreshGeneration;
            for (ResolvedLock lock : p.resolvedLocks.values()) {
                if (p.definitionFingerprint.equals(lock.definitionFingerprint)) {
                    ResolvedLock copy = PythonApi.mapper().convertValue(lock, ResolvedLock.class);
                    copy.resolvedOnNode = null;
                    e.locks.add(copy);
                }
            }
            bundle.profiles.add(e);
        }
        return bundle;
    }

    /**
     * Imports profiles. An imported ID that does not exist locally keeps its ID
     * unless mapped. An ID that exists with the same definition is unchanged.
     * Any other collision needs an explicit mapping: "new" for a fresh ID, or the
     * existing ID plus its expected revision to update it.
     */
    public ImportResult importBundle(String actor, ImportRequest request) {
        if (request.bundle == null || request.bundle.formatVersion != PythonApi.EXPORT_FORMAT_VERSION || request.bundle.profiles == null
                || request.bundle.profiles.size() > 1000 || request.mappings == null || request.expectedRevisions == null) {
            throw ApiFailure.badRequest("UNSUPPORTED_EXPORT", "This export format is not supported");
        }
        Set<String> importedIds = new HashSet<>();
        for (ExportedProfile e : request.bundle.profiles) {
            if (e == null || e.id == null || !e.id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                    || !importedIds.add(e.id) || e.refreshGeneration < 0 || e.locks == null) throw ApiFailure.badRequest("INVALID_EXPORT", "Imported profiles require distinct UUIDs and valid definitions");
            ProfileRequest check = new ProfileRequest();
            check.name = e.name;
            check.description = e.description;
            check.runtimeId = e.runtimeId;
            check.packageSourceId = e.packageSourceId;
            check.requirementsText = e.requirementsText;
            try {
                checkDefinition(check);
            } catch (ApiFailure failure) {
                throw ApiFailure.badRequest(failure.code, "Imported environment \"" + e.name + "\": " + failure.getMessage()).with(failure.diagnostics);
            }
            if (PythonContract.BUILTIN_SOURCE_ID.equals(e.packageSourceId) && e.locks.stream().anyMatch(lock -> lock == null
                    || lock.lockedRequirements == null || !lock.lockedRequirements.isEmpty()
                    || !Objects.equals(lock.packageSourcePolicy, OperatorConfig.builtInSourcePolicy()))) {
                throw ApiFailure.badRequest("STDLIB_ONLY", "Standard-library imports cannot contain external package locks or another source policy");
            }
        }
        RegistryStore.Outcome<ImportResult> outcome = runtime.registry.mutate(actor, request.requestId, "import", null, request,
                request.expectedRegistryEpoch, registry -> applyImport(registry, request, actor), registry -> {
                    for (PythonApi.MutationReceipt receipt : registry.recentMutationReceipts) {
                        if (java.util.Objects.equals(actor, receipt.actor) && request.requestId.equals(receipt.requestId) && receipt.result != null) {
                            return PythonApi.mapper().convertValue(receipt.result, ImportResult.class);
                        }
                    }
                    throw ApiFailure.conflict("IMPORT_REPLAY_UNAVAILABLE", "This earlier import has no saved result mappings. Export the current profiles before reconciling references.");
                });
        ImportResult result = outcome.value;
        result.requestId = request.requestId;
        result.registryEpoch = outcome.registry.registryEpoch;
        result.registryRevision = outcome.registry.registryRevision;
        result.replayed = outcome.replayed;
        return result;
    }

    private ImportResult applyImport(Registry registry, ImportRequest request, String actor) {
        ImportResult result = new ImportResult();
        List<Diagnostic> conflicts = new ArrayList<>();
        for (ExportedProfile e : request.bundle.profiles) {
            String mapping = request.mappings.get(e.id);
            if (PythonContract.DEFAULT_PROFILE_ID.equals(mapping) || PythonContract.DEFAULT_PROFILE_ID.equals(e.id) && !"new".equals(mapping)) {
                Profile owned = registry.profiles.get(PythonContract.DEFAULT_PROFILE_ID);
                if (mapping == null && BuiltInEnvironment.owned(owned) && Objects.equals(owned.name, e.name)
                        && Objects.equals(owned.description, e.description) && Objects.equals(owned.runtimeId, e.runtimeId)
                        && PythonContract.BUILTIN_SOURCE_ID.equals(e.packageSourceId)
                        && Objects.equals(Requirements.normalize(owned.requirementsText), Requirements.normalize(e.requirementsText))
                        && e.refreshGeneration == 0 && e.locks.stream().allMatch(lock -> lock != null && lock.lockedRequirements != null && lock.lockedRequirements.isEmpty())) {
                    result.unchanged.add(owned.id); result.mappings.put(e.id, owned.id); continue;
                }
                conflicts.add(Diagnostic.of("BUILTIN_PROFILE_READ_ONLY", "The reserved built-in environment cannot be imported or overwritten", e.id, "Map to a new profile ID instead.")); continue;
            }
            Profile incoming = new Profile();
            incoming.name = e.name.trim();
            incoming.description = e.description == null ? "" : e.description;
            incoming.runtimeId = e.runtimeId;
            incoming.packageSourceId = e.packageSourceId;
            incoming.requirementsText = Requirements.normalize(e.requirementsText);
            incoming.refreshGeneration = Math.max(0, e.refreshGeneration);
            incoming.definitionFingerprint = PreparationManager.definitionFingerprint(incoming);
            Profile existing = mapping == null || "new".equals(mapping) ? (mapping == null ? registry.profiles.get(e.id) : null)
                    : registry.profiles.get(mapping);
            if (mapping != null && !"new".equals(mapping) && existing == null) {
                conflicts.add(Diagnostic.of("MAPPING_TARGET_MISSING", "Mapping for \"" + e.name + "\" names a profile that does not exist", e.id, null));
                continue;
            }
            if (existing == null) {
                incoming.id = "new".equals(mapping) || mapping == null && registry.profiles.containsKey(e.id) ? UUID.randomUUID().toString() : e.id;
                if (nameTaken(registry, incoming.name, null)) {
                    conflicts.add(Diagnostic.of("NAME_IN_USE", "An environment named \"" + incoming.name + "\" already exists; map it explicitly",
                            e.id, "Map it to the existing profile, or rename one of them."));
                    continue;
                }
                incoming.revision = 1;
                incoming.createdAt = Instant.now().toString();
                incoming.updatedAt = incoming.createdAt;
                incoming.updatedBy = actor;
                copyLocks(e, incoming);
                registry.profiles.put(incoming.id, incoming);
                result.created.add(incoming.id);
                result.mappings.put(e.id, incoming.id);
                continue;
            }
            if (existing.definitionFingerprint.equals(incoming.definitionFingerprint) && java.util.Objects.equals(existing.name, incoming.name)
                    && java.util.Objects.equals(existing.description, incoming.description) && !existing.archived) {
                result.unchanged.add(existing.id);
                result.mappings.put(e.id, existing.id);
                continue;
            }
            Long expected = request.expectedRevisions.get(e.id);
            if (mapping == null || expected == null) {
                conflicts.add(Diagnostic.of("IMPORT_CONFLICT", "Environment " + existing.id + " (\"" + existing.name
                        + "\") exists with a different definition", e.id, "Map it to \"new\", or update it with its expected revision."));
                continue;
            }
            if (expected != existing.revision) {
                conflicts.add(Diagnostic.of("REVISION_CONFLICT", "Environment \"" + existing.name + "\" is at revision " + existing.revision, e.id,
                        "Reload and confirm the update again."));
                continue;
            }
            if (nameTaken(registry, incoming.name, existing.id)) {
                conflicts.add(Diagnostic.of("NAME_IN_USE", "An environment named \"" + incoming.name + "\" already exists", e.id, null));
                continue;
            }
            existing.name = incoming.name;
            existing.description = incoming.description;
            existing.runtimeId = incoming.runtimeId;
            existing.packageSourceId = incoming.packageSourceId;
            existing.requirementsText = incoming.requirementsText;
            existing.refreshGeneration = incoming.refreshGeneration;
            existing.definitionFingerprint = incoming.definitionFingerprint;
            existing.archived = false;
            existing.revision++;
            existing.updatedAt = Instant.now().toString();
            existing.updatedBy = actor;
            copyLocks(e, existing);
            result.updated.add(existing.id);
            result.mappings.put(e.id, existing.id);
        }
        if (!conflicts.isEmpty()) {
            throw ApiFailure.conflict("IMPORT_CONFLICT", conflicts.size() + " environment(s) need an explicit mapping; nothing was imported")
                    .with(conflicts);
        }
        return result;
    }

    private static void copyLocks(ExportedProfile from, Profile to) {
        for (ResolvedLock lock : from.locks) {
            if (lock == null || !to.definitionFingerprint.equals(lock.definitionFingerprint)
                    || lock.targetFingerprint == null || lock.lockedRequirements == null || lock.packageSourcePolicy == null
                    || !lock.packageSourcePolicy.matches("[0-9a-f]{64}")) {
                continue;
            }
            String digest = PreparationManager.lockDigest(lock.targetFingerprint, lock.lockedRequirements);
            if (!digest.equals(lock.lockDigest)) {
                continue;
            }
            ResolvedLock copy = PythonApi.mapper().convertValue(lock, ResolvedLock.class);
            to.resolvedLocks.put(copy.targetFingerprint, copy);
        }
    }

    private static boolean nameTaken(Registry registry, String name, String exceptId) {
        for (Profile p : registry.profiles.values()) {
            if (!p.archived && !p.id.equals(exceptId) && p.name.trim().equalsIgnoreCase(name.trim())) {
                return true;
            }
        }
        return false;
    }

    // ---- Validation and test ------------------------------------------------------------------

    private static final class Request implements PythonScriptConfiguration {
        final ScriptRequest r;

        Request(ScriptRequest r) {
            this.r = r;
        }

        @Override
        public String getCodeTemplateFunctionName() { return PythonContract.ROLE_CODE_TEMPLATE.equals(r.role) ? r.functionName : null; }

        @Override
        public String getPythonScript() {
            return r.pythonScript;
        }

        @Override
        public String getEnvironmentProfileId() {
            return r.environmentProfileId;
        }

        @Override
        public String getEnvironmentNameHint() {
            return "";
        }

        @Override
        public int getTimeoutMillis() {
            return r.timeoutMillis == null ? PythonContract.DEFAULT_TIMEOUT_MILLIS : r.timeoutMillis;
        }

        @Override
        public int getContractVersion() {
            return r.contractVersion == null ? PythonContract.CONTRACT_VERSION : r.contractVersion;
        }
    }

    private static void checkScriptRequest(ScriptRequest request) {
        if (request.role == null || !PythonContract.isRole(request.role)) {
            throw ApiFailure.badRequest("INVALID_ROLE", "role must be source-filter, destination-filter, source-transformer, destination-transformer, response-transformer, reader, writer or code-template");
        }
        if (PythonContract.ROLE_CODE_TEMPLATE.equals(request.role)) {
            PythonContract.validateFunctionName(request.functionName);
            if (request.contextTypes == null || request.contextTypes.isEmpty() || request.contextTypes.stream().anyMatch(context ->
                    Surfaces.templateContexts().stream().noneMatch(allowed -> allowed.name().equals(context)))) {
                throw ApiFailure.badRequest("INVALID_TEMPLATE_CONTEXT", "Choose source, destination or response filter/transformer contexts for Python functions");
            }
        }
        if (request.pythonScript == null) {
            throw ApiFailure.badRequest("SCRIPT_REQUIRED", "pythonScript is required");
        }
        if (request.pythonScript.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw ApiFailure.badRequest("SCRIPT_TOO_LARGE", "pythonScript exceeds " + MAX_SOURCE_BYTES + " UTF-8 bytes");
        }
        if (request.contractVersion != null && request.contractVersion != PythonContract.CONTRACT_VERSION) {
            throw ApiFailure.badRequest("UNSUPPORTED_CONTRACT", "Contract version " + request.contractVersion + " is not supported");
        }
    }

    /** Compile-only validation. Never prepares dependencies, imports modules or runs the script. */
    @SuppressWarnings("unchecked")
    public ValidationResult validate(ScriptRequest request) {
        checkExpectedNode(request);
        checkScriptRequest(request);
        ValidationResult result = new ValidationResult();
        result.requestId = request.requestId;
        result.nodeId = runtime.config.nodeId;
        Request config = new Request(request);
        result.sourceDigest = PythonContract.specDigest(config);
        Registry registry = runtime.registry.snapshot();
        result.registryEpoch = registry.registryEpoch;
        checkActionEpoch(request, registry);
        String effectiveProfileId = BuiltInEnvironment.selectedId(request.environmentProfileId);
        Profile profile = registry.profiles.get(effectiveProfileId);
        if (PythonContract.DEFAULT_PROFILE_ID.equals(effectiveProfileId) && !BuiltInEnvironment.owned(profile)) {
            result.status = "unavailable"; result.diagnostics.add(Diagnostic.of("DEFAULT_ENVIRONMENT_UNAVAILABLE", "The built-in environment is not available; existing profiles were preserved", "environmentProfileId", null)); return result;
        }
        String runtimeId;
        if (profile != null) {
            checkActionRevision(request, profile);
            result.environmentRevision = profile.revision;
            result.definitionFingerprint = profile.definitionFingerprint;
            runtimeId = profile.runtimeId;
        } else {
            runtimeId = runtime.config.runtimes.keySet().iterator().next();
        }
        int timeout = PythonContract.effectiveTimeout(config.getTimeoutMillis());
        if (timeout < PythonContract.MIN_TIMEOUT_MILLIS || timeout > runtime.config.maxTimeoutMillis) {
            result.diagnostics.add(Diagnostic.of("INVALID_TIMEOUT", "Timeout must be between " + PythonContract.MIN_TIMEOUT_MILLIS + " and "
                    + runtime.config.maxTimeoutMillis + " ms", "timeoutMillis", null));
        }
        Interpreters.Target target;
        try {
            target = runtime.interpreters.probe(runtimeId);
            result.targetFingerprint = target.fingerprint;
        } catch (Interpreters.Unavailable e) {
            result.status = "unavailable";
            result.checks.put("syntax", "not-run");
            result.checks.put("environment", "runtime-unavailable");
            result.diagnostics.add(Diagnostic.of("RUNTIME_UNAVAILABLE", e.getMessage(), "environmentProfileId", null));
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiFailure.unavailable("INTERRUPTED", "Interrupted");
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "0");
        item.put("role", PythonContract.scriptRole(request.role));
        item.put("label", "<python:validate>");
        item.put("source", PythonContract.normalizeSource(request.pythonScript));
        boolean syntaxOk;
        try {
            Map<String, Object> compiled = runtime.compileOnly(target.executable, Collections.singletonList(item)).get(0);
            syntaxOk = Boolean.TRUE.equals(compiled.get("ok"));
            result.diagnostics.addAll(PythonRuntime.diagnostics(compiled));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiFailure.unavailable("INTERRUPTED", "Interrupted");
        } catch (Exception e) {
            result.status = "unavailable";
            result.checks.put("syntax", "unavailable");
            result.diagnostics.add(Diagnostic.of("VALIDATOR_UNAVAILABLE", "The Python validator did not run: " + e.getMessage(), null, null));
            return result;
        }
        result.checks.put("syntax", syntaxOk ? "passed" : "failed");
        String environment;
        if (profile == null) {
            environment = "missing";
            result.diagnostics.add(Diagnostic.of("ENVIRONMENT_MISSING", "The selected script environment does not exist on this server",
                    "environmentProfileId", "Import or create the environment, or select another."));
        } else if (profile.archived) {
            environment = "archived";
            result.diagnostics.add(Diagnostic.of("ENVIRONMENT_ARCHIVED", "The selected script environment is archived", "environmentProfileId", null));
        } else {
            PythonApi.LocalReadiness readiness = runtime.readiness(profile, registry.registryEpoch);
            environment = readiness.state;
            if (!"ready".equals(environment)) {
                result.diagnostics.add(Diagnostic.of("ENVIRONMENT_NOT_READY", "Environment \"" + BuiltInEnvironment.displayName(profile) + "\" is " + environment + " on node "
                        + runtime.config.nodeId, "environmentProfileId", BuiltInEnvironment.preparationHint(profile)));
            }
        }
        result.checks.put("environment", environment);
        boolean structural = result.diagnostics.stream().anyMatch(d -> "error".equals(d.severity) && d.code != null
                && (d.code.startsWith("PYTHON_") || d.code.startsWith("INVALID_")));
        if (!syntaxOk || structural) {
            result.status = "invalid";
        } else if (!"ready".equals(environment)) {
            result.status = "unavailable";
        } else {
            result.status = "valid";
        }
        checkActionStillCurrent(registry, profile, effectiveProfileId);
        return result;
    }

    /** Runs a script once against synthetic input on a private worker. Nothing is applied to engine state. */
    @SuppressWarnings("unchecked")
    public TestResult test(ScriptRequest request, String channelId) {
        if (PythonContract.ROLE_CODE_TEMPLATE.equals(request.role)) throw ApiFailure.badRequest("TEMPLATE_TEST_UNSUPPORTED", "Python functions are tested by invoking their consuming transformer");
        checkExpectedNode(request);
        checkScriptRequest(request);
        TestResult result = new TestResult();
        result.requestId = request.requestId;
        result.nodeId = runtime.config.nodeId;
        result.profileId = BuiltInEnvironment.selectedId(request.environmentProfileId);
        Request config = new Request(request);
        result.sourceDigest = PythonContract.specDigest(config);
        Registry registry = runtime.registry.snapshot();
        result.registryEpoch = registry.registryEpoch;
        checkActionEpoch(request, registry);
        Profile profile = registry.profiles.get(result.profileId);
        if (PythonContract.DEFAULT_PROFILE_ID.equals(result.profileId) && !BuiltInEnvironment.owned(profile)) {
            result.status = "unavailable"; result.diagnostics.add(Diagnostic.of("DEFAULT_ENVIRONMENT_UNAVAILABLE", "The built-in environment is not available; existing profiles were preserved", "environmentProfileId", null)); return result;
        }
        if (profile == null || profile.archived) {
            result.status = "unavailable";
            result.diagnostics.add(Diagnostic.of("ENVIRONMENT_MISSING", "Select an existing script environment", "environmentProfileId", null));
            return result;
        }
        result.environmentRevision = profile.revision;
        checkActionRevision(request, profile);
        int timeout = PythonContract.effectiveTimeout(config.getTimeoutMillis());
        if (timeout > runtime.config.maxTimeoutMillis || timeout < PythonContract.MIN_TIMEOUT_MILLIS) {
            throw ApiFailure.badRequest("INVALID_TIMEOUT", "Timeout must be between " + PythonContract.MIN_TIMEOUT_MILLIS + " and "
                    + runtime.config.maxTimeoutMillis + " ms");
        }
        PythonApi.LocalReadiness readiness = runtime.readiness(profile, registry.registryEpoch);
        result.targetFingerprint = readiness.targetFingerprint;
        if (!"ready".equals(readiness.state)) {
            result.status = "unavailable";
            result.diagnostics.add(Diagnostic.of("ENVIRONMENT_NOT_READY", "Environment \"" + BuiltInEnvironment.displayName(profile) + "\" is " + readiness.state + " on node "
                    + runtime.config.nodeId, "environmentProfileId", BuiltInEnvironment.preparationHint(profile)));
            return result;
        }
        SampleContext sample;
        try {
            sample = SampleContext.build(request.role, request.sampleContext == null ? new LinkedHashMap<>() : request.sampleContext,
                    runtime.config.maxReaderMessages);
        } catch (Wire.ValueException | IllegalArgumentException e) {
            throw ApiFailure.badRequest("INVALID_SAMPLE", e.getMessage());
        }
        if (!tests.tryAcquire()) {
            throw new ApiFailure(429, "TOO_MANY_TESTS", "Too many script tests are running on this node. Try again shortly.");
        }
        ArtifactStore.Lease lease = runtime.artifacts.acquire(readiness.artifactDigest);
        if (lease == null) {
            tests.release();
            result.status = "unavailable";
            result.diagnostics.add(Diagnostic.of("ENVIRONMENT_NOT_READY", "The environment was removed before the test started", null, null));
            return result;
        }
        try {
            checkActionStillCurrent(registry, profile, result.profileId);
            Executor.Outcome outcome = runtime.executor.invokeEphemeral(lease.python, result.sourceDigest, request.role,
                    "<python:test " + (channelId == null ? "" : channelId + " ") + request.role + ">", PythonContract.normalizeSource(request.pythonScript),
                    sample.context, timeout, lease.manifest.targetFingerprint);
            result.durationsMillis.putAll(outcome.durations);
            result.logs.addAll(outcome.logs());
            result.logsTruncated = outcome.logsTruncated();
            if (!checkCompletedTestContext(result, registry, profile, result.profileId)) return result;
            if (!outcome.ok()) {
                result.status = "error";
                Map<String, Object> error = outcome.error();
                Diagnostic d = Diagnostic.of("SCRIPT_" + String.valueOf(error.get("kind")).toUpperCase(Locale.ROOT), String.valueOf(error.get("message")),
                        error.get("field") == null ? "pythonScript" : String.valueOf(error.get("field")), null);
                d.line = error.get("line") instanceof Number ? ((Number) error.get("line")).intValue() : null;
                result.diagnostics.add(d);
                if (error.get("traceback") != null) {
                    Diagnostic tb = Diagnostic.of("TRACEBACK", String.valueOf(error.get("traceback")), null, null);
                    tb.severity = "info";
                    result.diagnostics.add(tb);
                }
                result.outcomeUncertain = true;
                return result;
            }
            try {
                result.output = sample.output(outcome.result());
                result.status = "success";
            } catch (Wire.ValueException | IllegalArgumentException | ClassCastException e) {
                result.status = "error";
                result.output = null;
                result.outcomeUncertain = true;
                result.diagnostics.add(Diagnostic.of("RESULT_REJECTED", e.getMessage(), null, null));
            }
            checkCompletedTestContext(result, registry, profile, result.profileId);
            return result;
        } catch (Executor.Failure e) {
            result.status = "TIMEOUT".equals(e.code) ? "timeout" : "error";
            result.outcomeUncertain = e.outcomeUncertain;
            result.durationsMillis.putAll(e.durations);
            result.diagnostics.add(Diagnostic.of(e.code, e.getMessage(), null, null));
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.status = "cancelled";
            result.outcomeUncertain = true;
            return result;
        } finally {
            lease.release();
            tests.release();
        }
    }

    private static void checkActionEpoch(ScriptRequest request, Registry registry) {
        if (!java.util.Objects.equals(request.expectedRegistryEpoch, registry.registryEpoch)) {
            ApiFailure failure = ApiFailure.conflict("REGISTRY_EPOCH_CONFLICT", "The environment registry was restored or replaced. Reload.");
            failure.registryEpoch = registry.registryEpoch;
            throw failure;
        }
    }

    private void checkExpectedNode(ScriptRequest request) {
        if (request == null) throw ApiFailure.badRequest("SCRIPT_REQUEST_REQUIRED", "A script request object is required");
        if (request.expectedNodeId != null && !runtime.config.nodeId.equals(request.expectedNodeId)) {
            ApiFailure failure = ApiFailure.conflict("NODE_CHANGED", "The selected engine node changed. Reload its runtime and validate or test again.");
            failure.ownerNodeId = runtime.config.nodeId;
            throw failure;
        }
    }

    private boolean checkCompletedTestContext(TestResult result, Registry registry, Profile profile, String profileId) {
        try {
            checkActionStillCurrent(registry, profile, profileId);
            return true;
        } catch (ApiFailure changed) {
            // The script already ran. A plain conflict/transport failure
            // response would hide that external work may have completed.
            result.status = changed.status == 409 ? "cancelled" : "error";
            result.output = null;
            result.outcomeUncertain = true;
            result.diagnostics.add(Diagnostic.of(changed.status == 409 ? "TEST_CONTEXT_CHANGED" : "TEST_CONTEXT_UNAVAILABLE",
                    changed.getMessage() + " The script already ran; any external operations may have completed. Sample output was omitted.",
                    "environmentProfileId", "Reload the environment and review any external effects before deciding to run Test again."));
            return false;
        }
    }

    private static void checkActionRevision(ScriptRequest request, Profile profile) {
        RegistryStore.checkRevision(profile, request.expectedEnvironmentRevision);
    }

    private void checkActionStillCurrent(Registry captured, Profile profile, String profileId) {
        Registry current = runtime.registry.snapshot();
        if (!captured.registryEpoch.equals(current.registryEpoch)) {
            ApiFailure failure = ApiFailure.conflict("REGISTRY_EPOCH_CONFLICT", "The environment registry changed while this action was running. Reload.");
            failure.registryEpoch = current.registryEpoch;
            throw failure;
        }
        Profile now = profileId == null ? null : current.profiles.get(profileId);
        if (profile == null ? now != null : now == null || profile.revision != now.revision || profile.archived != now.archived) {
            ApiFailure failure = ApiFailure.conflict("REVISION_CONFLICT", "The selected environment changed while this action was running. Validate or test again.");
            failure.currentRevision = now == null ? null : now.revision;
            throw failure;
        }
    }

    static String digestOf(Object value) {
        return CanonicalJson.sha256Hex(PythonApi.mapper().valueToTree(value).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
