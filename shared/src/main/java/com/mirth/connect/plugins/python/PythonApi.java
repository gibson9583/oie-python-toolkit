/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Versioned JSON documents for the registry and the REST API. The server,
 * the runtime endpoints and web administrator share these shapes.
 */
public final class PythonApi {

    public static final int REGISTRY_SCHEMA_VERSION = 1;
    public static final int EXPORT_FORMAT_VERSION = 1;

    private PythonApi() {}

    public static ObjectMapper mapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        mapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
        return mapper;
    }

    // ---- Registry (persisted) ----------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Registry {
        public int schemaVersion = REGISTRY_SCHEMA_VERSION;
        public String registryEpoch;
        public long registryRevision;
        public Map<String, Profile> profiles = new TreeMap<>();
        public List<MutationReceipt> recentMutationReceipts = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Profile {
        public boolean builtInDefault;
        public String id;
        public long revision;
        public String name;
        public String description;
        public boolean archived;
        public String language = "python";
        public String runtimeId;
        public String packageSourceId;
        public String requirementsText;
        public String definitionFingerprint;
        public long refreshGeneration;
        public Map<String, ResolvedLock> resolvedLocks = new TreeMap<>();
        public String createdAt;
        public String updatedAt;
        public String updatedBy;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResolvedLock {
        public String definitionFingerprint;
        public String targetFingerprint;
        public String lockDigest;
        public String packageSourcePolicy;
        public List<LockedRequirement> lockedRequirements = new ArrayList<>();
        public String resolvedAt;
        public String resolvedOnNode;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LockedRequirement {
        public String name;
        public String version;
        public String filename;
        public String sha256;
        public List<String> tags = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MutationReceipt {
        public String actor;
        public String requestId;
        public String operation;
        public String target;
        public String contentDigest;
        public long resultRevision;
        /** Durable bounded mutation response, used to replay import mappings. */
        public Object result;
        public String at;
    }

    // ---- Diagnostics and errors ----------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Diagnostic {
        public String severity = "error";
        public String code;
        public String message;
        public String field;
        public Integer line;
        public Integer column;
        public String suggestedAction;

        public Diagnostic() {}

        public Diagnostic(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public static Diagnostic of(String code, String message, String field, String action) {
            Diagnostic d = new Diagnostic(code, message);
            d.field = field;
            d.suggestedAction = action;
            return d;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ApiError {
        public int status;
        public String code;
        public String message;
        public String operationId;
        public String ownerNodeId;
        public String registryEpoch;
        public Long currentRevision;
        public List<Diagnostic> diagnostics = new ArrayList<>();
    }

    // ---- Profiles ---------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProfileRequest {
        public String requestId;
        public String expectedRegistryEpoch;
        public Long expectedRevision;
        public String name;
        public String description;
        public String runtimeId;
        public String packageSourceId;
        public String requirementsText;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RevisionRequest {
        public String requestId;
        public String expectedRegistryEpoch;
        public Long expectedRevision;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProfileView {
        public boolean builtInDefault;
        public String id;
        public long revision;
        public String registryEpoch;
        public String name;
        public String description;
        public boolean archived;
        public String language;
        public String runtimeId;
        public String packageSourceId;
        public String requirementsText;
        public String definitionFingerprint;
        public long refreshGeneration;
        public String createdAt;
        public String updatedAt;
        public String updatedBy;
        public LocalReadiness readiness;
        public int savedReferenceCount;
        public int deployedReferenceCount;
        public boolean redeployNeeded;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LocalReadiness {
        public String pythonVersion;
        /** ready, not-prepared, preparing, failed, runtime-unavailable */
        public String state;
        public String nodeId;
        public String targetFingerprint;
        public String lockDigest;
        public String artifactDigest;
        public String jobId;
        public String message;
        public List<LockedRequirement> packages = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProfileList {
        /** Null when the reserved built-in identity is occupied by another profile. */
        public String defaultProfileId;
        public String defaultProfileMessage;
        public String registryEpoch;
        public long registryRevision;
        public String nodeId;
        public List<ProfileView> profiles = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MutationResult {
        public String requestId;
        public String registryEpoch;
        public long registryRevision;
        public boolean replayed;
        public ProfileView profile;
        public JobView job;
    }

    // ---- References ----------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class References {
        public String profileId;
        public String nodeId;
        public List<SurfaceReference> saved = new ArrayList<>();
        public List<DeploymentReference> deployed = new ArrayList<>();
        public int hiddenCount;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SurfaceReference {
        public String channelId;
        public String channelName;
        public Integer connectorMetadataId;
        public String connectorName;
        public String role;
        public String stepName;
        public boolean enabled;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DeploymentReference {
        public String channelId;
        public String channelName;
        public String attemptId;
        public String artifactDigest;
        public String lockDigest;
        public String deployedAt;
        public boolean redeployNeeded;
    }

    // ---- Preparation jobs ----------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JobView {
        public String id;
        public String nodeId;
        public String profileId;
        public String registryEpoch;
        public long profileRevision;
        public String definitionFingerprint;
        public String targetFingerprint;
        /** QUEUED, RESOLVING, BUILDING, VERIFYING, READY, FAILED, CANCELLED, SUPERSEDED, INTERRUPTED */
        public String state;
        public String message;
        public String createdAt;
        public String updatedAt;
        public String finishedAt;
        public String lockDigest;
        public String artifactDigest;
        public boolean cancelRequested;
        public List<Diagnostic> diagnostics = new ArrayList<>();
        public Map<String, Long> durationsMillis = new LinkedHashMap<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JobLog {
        public String jobId;
        public String nodeId;
        public long cursor;
        public long nextCursor;
        public boolean truncatedBefore;
        public boolean complete;
        public List<String> lines = new ArrayList<>();
    }

    // ---- Validation and test --------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScriptRequest {
        public String requestId;
        public String expectedNodeId;
        public String channelId;
        public Integer connectorMetadataId;
        public String role;
        public String functionName;
        public List<String> contextTypes;
        public String environmentProfileId;
        public String expectedRegistryEpoch;
        public Long expectedEnvironmentRevision;
        public String pythonScript;
        public Integer contractVersion;
        public Integer timeoutMillis;
        /** Test only: role-specific sample context. */
        public Map<String, Object> sampleContext;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ValidationResult {
        public String requestId;
        public String registryEpoch;
        public String nodeId;
        public String targetFingerprint;
        public Long environmentRevision;
        public String definitionFingerprint;
        public String sourceDigest;
        /** valid, invalid, unavailable */
        public String status;
        public Map<String, String> checks = new LinkedHashMap<>();
        public List<Diagnostic> diagnostics = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TestResult {
        public String requestId;
        public String sourceDigest;
        public String profileId;
        public String registryEpoch;
        public Long environmentRevision;
        public String nodeId;
        public String targetFingerprint;
        /** success, error, cancelled, timeout, unavailable */
        public String status;
        public boolean outcomeUncertain;
        public Map<String, Long> durationsMillis = new LinkedHashMap<>();
        public List<Map<String, Object>> logs = new ArrayList<>();
        public boolean logsTruncated;
        public List<Diagnostic> diagnostics = new ArrayList<>();
        public Map<String, Object> output;
    }

    // ---- Runtime information ---------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RuntimeInfo {
        public String defaultProfileId;
        public String defaultProfileMessage;
        public String nodeId;
        public String sdkVersion;
        public int protocolVersion;
        public int contractVersion = PythonContract.CONTRACT_VERSION;
        public String registryEpoch;
        public List<RuntimeOption> runtimes = new ArrayList<>();
        public List<SourceOption> packageSources = new ArrayList<>();
        public Map<String, Long> limits = new LinkedHashMap<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RuntimeOption {
        public String id;
        public String label;
        public boolean available;
        public String version;
        public String targetFingerprint;
        public String message;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SourceOption {
        public String id;
        public String label;
        /** index or wheelhouse */
        public String kind;
    }

    // ---- Import / export ---------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExportBundle {
        public int formatVersion = EXPORT_FORMAT_VERSION;
        public String exportedAt;
        public List<ExportedProfile> profiles = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExportedProfile {
        public String id;
        public String name;
        public String description;
        public String runtimeId;
        public String packageSourceId;
        public String requirementsText;
        public long refreshGeneration;
        public List<ResolvedLock> locks = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ImportRequest {
        public String requestId;
        public String expectedRegistryEpoch;
        public ExportBundle bundle;
        /** Imported ID to target: "new" creates a fresh ID, an existing ID updates that profile. */
        public Map<String, String> mappings = new LinkedHashMap<>();
        /** For updates: imported ID to the expected current revision of its target. */
        public Map<String, Long> expectedRevisions = new LinkedHashMap<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ImportResult {
        public String requestId;
        public String registryEpoch;
        public long registryRevision;
        public boolean replayed;
        public Map<String, String> mappings = new LinkedHashMap<>();
        public List<String> created = new ArrayList<>();
        public List<String> updated = new ArrayList<>();
        public List<String> unchanged = new ArrayList<>();
        public List<Diagnostic> conflicts = new ArrayList<>();
    }
}
