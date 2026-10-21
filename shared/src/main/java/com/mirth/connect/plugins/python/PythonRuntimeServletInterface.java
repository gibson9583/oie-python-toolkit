/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.client.core.Operation.ExecuteType;
import com.mirth.connect.client.core.api.BaseServletInterface;
import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.client.core.api.Param;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Python Toolkit administration API.
 *
 * <p>String parameters and results carry JSON documents defined by
 * {@link PythonApi}. Request bodies contain the raw JSON document; stock
 * String results are enveloped as {@code {"string": "<json>"}}. Errors use HTTP status codes with an
 * {@link PythonApi.ApiError} body.</p>
 *
 * <p>Profiles, jobs and readiness are local to the engine node that serves the
 * request. Send job requests to the node that returned the job.</p>
 */
@Path("/extensions/oie-python-toolkit")
@Tag(name = "Extension Services")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface PythonRuntimeServletInterface extends BaseServletInterface {

    String PLUGIN_POINT = PythonContract.EXTENSION_NAME;

    String PERMISSION_VIEW = "View Python Environments";
    String PERMISSION_MANAGE = "Manage Python Environments";
    String PERMISSION_PREPARE = "Prepare Python Environments";
    String PERMISSION_VALIDATE = "Validate Python Scripts";
    String PERMISSION_EXECUTE = "Execute Python Scripts";

    String OP_RUNTIME = "getPythonRuntime";
    String OP_LIST = "getPythonEnvironments";
    String OP_GET = "getPythonEnvironment";
    String OP_CREATE = "createPythonEnvironment";
    String OP_UPDATE = "updatePythonEnvironment";
    String OP_ARCHIVE = "archivePythonEnvironment";
    String OP_REFERENCES = "getPythonEnvironmentReferences";
    String OP_PREPARE = "preparePythonEnvironment";
    String OP_REFRESH = "refreshPythonEnvironment";
    String OP_JOB = "getPythonPreparationJob";
    String OP_JOB_LOG = "getPythonPreparationLog";
    String OP_JOB_CANCEL = "cancelPythonPreparationJob";
    String OP_TEMPLATE_VALIDATE = "validatePythonTemplate";
    String OP_VALIDATE = "validatePythonScript";
    String OP_EDITOR = "pythonEditorRequest";
    String OP_TEST = "testPythonScript";
    String OP_EXPORT = "exportPythonEnvironments";
    String OP_IMPORT = "importPythonEnvironments";

    @GET
    @Path("/runtime")
    @Operation(summary = "Returns this node's approved runtimes, package sources and limits.")
    @MirthOperation(name = OP_RUNTIME, display = "View Python runtime", permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = false)
    String getRuntime() throws ClientException;

    @GET
    @Path("/profiles")
    @Operation(summary = "Lists environment profiles with their readiness on this node.")
    @MirthOperation(name = OP_LIST, display = "View Python environments", permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = false)
    String getProfiles(@Param("includeArchived") @QueryParam("includeArchived") boolean includeArchived) throws ClientException;

    @GET
    @Path("/profiles/export")
    @Operation(summary = "Exports profile definitions and locks. Excludes credentials, local paths and readiness.")
    @MirthOperation(name = OP_EXPORT, display = "Export Python environments", permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = false)
    String exportProfiles(@Param("ids") @QueryParam("ids") String ids) throws ClientException;

    @POST
    @Path("/profiles/import")
    @Operation(summary = "Imports exported profiles with explicit ID mappings.")
    @MirthOperation(name = OP_IMPORT, display = "Import Python environments", permission = PERMISSION_MANAGE)
    String importProfiles(@Param(value = "request", excludeFromAudit = true) String json) throws ClientException;

    @GET
    @Path("/profiles/{profileId}")
    @Operation(summary = "Returns one environment profile with its readiness on this node.")
    @MirthOperation(name = OP_GET, display = "View Python environment", permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = false)
    String getProfile(@Param("profileId") @PathParam("profileId") String profileId) throws ClientException;

    @POST
    @Path("/profiles")
    @Operation(summary = "Creates an environment profile. Saving does not install anything.")
    @MirthOperation(name = OP_CREATE, display = "Create Python environment", permission = PERMISSION_MANAGE)
    String createProfile(@Param(value = "profile", excludeFromAudit = true) String json) throws ClientException;

    @PUT
    @Path("/profiles/{profileId}")
    @Operation(summary = "Updates an environment profile at the expected revision.")
    @MirthOperation(name = OP_UPDATE, display = "Update Python environment", permission = PERMISSION_MANAGE)
    String updateProfile(@Param("profileId") @PathParam("profileId") String profileId,
            @Param(value = "profile", excludeFromAudit = true) String json) throws ClientException;

    @DELETE
    @Path("/profiles/{profileId}")
    @Operation(summary = "Archives an unreferenced environment profile at the expected revision.")
    @MirthOperation(name = OP_ARCHIVE, display = "Archive Python environment", permission = PERMISSION_MANAGE)
    String archiveProfile(@Param("profileId") @PathParam("profileId") String profileId,
            @Param("requestId") @QueryParam("requestId") String requestId,
            @Param("expectedRegistryEpoch") @QueryParam("expectedRegistryEpoch") String expectedRegistryEpoch,
            @Param("expectedRevision") @QueryParam("expectedRevision") Long expectedRevision) throws ClientException;

    @GET
    @Path("/profiles/{profileId}/references")
    @Operation(summary = "Lists saved and deployed channels that use the profile, filtered by channel visibility.")
    @MirthOperation(name = OP_REFERENCES, display = "View Python environment usage", permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = false)
    String getReferences(@Param("profileId") @PathParam("profileId") String profileId) throws ClientException;

    @POST
    @Path("/profiles/{profileId}/prepare")
    @Operation(summary = "Prepares the saved revision on this node. Returns a job.")
    @MirthOperation(name = OP_PREPARE, display = "Prepare Python environment", permission = PERMISSION_PREPARE)
    String prepareProfile(@Param("profileId") @PathParam("profileId") String profileId, @Param("request") String json) throws ClientException;

    @POST
    @Path("/profiles/{profileId}/refresh")
    @Operation(summary = "Requests a new dependency resolution without changing the requirements.")
    @MirthOperation(name = OP_REFRESH, display = "Refresh Python environment dependencies", permission = PERMISSION_PREPARE)
    String refreshProfile(@Param("profileId") @PathParam("profileId") String profileId, @Param("request") String json) throws ClientException;

    @GET
    @Path("/jobs/{jobId}")
    @Operation(summary = "Returns a preparation job owned by this node.")
    @MirthOperation(name = OP_JOB, display = "View Python preparation job", permission = PERMISSION_PREPARE, type = ExecuteType.ASYNC, auditable = false)
    String getJob(@Param("jobId") @PathParam("jobId") String jobId) throws ClientException;

    @GET
    @Path("/jobs/{jobId}/logs")
    @Operation(summary = "Returns bounded, redacted preparation output after the cursor.")
    @MirthOperation(name = OP_JOB_LOG, display = "View Python preparation output", permission = PERMISSION_PREPARE, type = ExecuteType.ASYNC, auditable = false)
    String getJobLog(@Param("jobId") @PathParam("jobId") String jobId,
            @Param("cursor") @QueryParam("cursor") Long cursor) throws ClientException;

    @POST
    @Path("/jobs/{jobId}/cancel")
    @Operation(summary = "Requests cancellation. Repeating the request is harmless.")
    @MirthOperation(name = OP_JOB_CANCEL, display = "Cancel Python preparation job", permission = PERMISSION_PREPARE)
    String cancelJob(@Param("jobId") @PathParam("jobId") String jobId) throws ClientException;

    @POST
    @Path("/scripts/validate")
    @Operation(summary = "Compiles Python syntax. Never imports or runs the script.")
    @MirthOperation(name = OP_VALIDATE, display = "Validate Python script", permission = PERMISSION_VALIDATE, type = ExecuteType.ASYNC, auditable = false)
    String validateScript(@Param(value = "request", excludeFromAudit = true) String json) throws ClientException;

    @POST
    @Path("/templates/validate")
    @Operation(summary = "Validates a callable Python code template without importing or executing it.")
    @MirthOperation(name = OP_TEMPLATE_VALIDATE, display = "Validate Python code template", permission = PERMISSION_VALIDATE, type = ExecuteType.ASYNC, auditable = false)
    String validateTemplate(@Param(value = "request", excludeFromAudit = true) String json) throws ClientException;

    @POST
    @Path("/editor")
    @Operation(summary = "Statically analyzes or safely formats Python source using bundled offline tooling.")
    @MirthOperation(name = OP_EDITOR, display = "Python editor tooling", permission = PERMISSION_VALIDATE, type = ExecuteType.ASYNC, auditable = false)
    String editorRequest(@Param(value = "request", excludeFromAudit = true) String json) throws ClientException;

    @POST
    @Path("/scripts/test")
    @Operation(summary = "Runs a script once against synthetic sample input. The script can perform external I/O.")
    @MirthOperation(name = OP_TEST, display = "Test Python script", permission = PERMISSION_EXECUTE, type = ExecuteType.ASYNC, abortable = true)
    String testScript(@Param(value = "request", excludeFromAudit = true) String json) throws ClientException;
}
