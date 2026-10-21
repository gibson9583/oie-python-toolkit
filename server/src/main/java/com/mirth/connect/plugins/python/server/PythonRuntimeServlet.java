/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.Supplier;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import com.mirth.connect.client.core.ExtensionOperation;
import com.mirth.connect.client.core.Operation;
import com.mirth.connect.client.core.Operation.ExecuteType;
import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.plugins.python.PythonApi;
import com.mirth.connect.plugins.python.PythonRuntimeServletInterface;
import com.mirth.connect.server.api.MirthServlet;

/** All paths authorize before reading runtime credentials or starting subprocesses. */
public class PythonRuntimeServlet extends MirthServlet implements PythonRuntimeServletInterface {
    public PythonRuntimeServlet(@Context HttpServletRequest request, @Context SecurityContext security) {
        super(request, security, PLUGIN_POINT);
    }
    protected PythonRuntime runtime() { return PythonRuntime.require(); }
    private RuntimeApi api() { return PythonServicePlugin.api(); }
    private String actor() { return Integer.toString(getCurrentUserId()); }
    private String result(Supplier<?> work) {
        try { return PythonApi.mapper().writeValueAsString(PythonServicePlugin.asActor(getCurrentUserId(), work)); }
        catch (ApiFailure failure) { throw response(failure); }
        catch (IllegalArgumentException failure) { throw response(ApiFailure.badRequest("INVALID_REQUEST", failure.getMessage())); }
        catch (IOException failure) { throw response(ApiFailure.unavailable("RESPONSE_UNAVAILABLE", "Cannot encode the Python Toolkit response")); }
        catch (IllegalStateException failure) { throw response(ApiFailure.unavailable("RUNTIME_UNAVAILABLE", failure.getMessage())); }
    }
    private static MirthApiException response(ApiFailure failure) {
        try {
            return new MirthApiException(Response.status(failure.status).type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(PythonApi.mapper().writeValueAsString(failure.toError())).build());
        } catch (IOException encoding) { return new MirthApiException(encoding); }
    }
    private <T> T body(String json, Class<T> type) {
        if (json == null || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16 * 1024 * 1024) {
            throw response(ApiFailure.badRequest("INVALID_REQUEST", "A bounded JSON request is required"));
        }
        try {
            T value = PythonApi.mapper().readValue(json, type);
            if (value == null) throw new IOException("request is null");
            return value;
        } catch (IOException failure) { throw response(ApiFailure.badRequest("INVALID_REQUEST", "Malformed Python Toolkit request: " + failure.getMessage())); }
    }
    private void extraPermission(String operationName) {
        if (!authorized(getCurrentUserId(), operationName)) throw new MirthApiException(Response.Status.FORBIDDEN);
    }
    private boolean authorized(int userId, String operationName) {
        try {
            return controllerFactory.createAuthorizationController().isUserAuthorized(userId,
                    new ExtensionOperation(PLUGIN_POINT, operationName, operationName, ExecuteType.ASYNC, false),
                    Map.of(), getRequestIpAddress(), false);
        } catch (Exception failure) { return false; }
    }

    private void channelAccess(PythonApi.ScriptRequest request) {
        if (request.channelId == null || request.channelId.isBlank()) {
            if (doesUserHaveChannelRestrictions()) throw new MirthApiException(Response.Status.FORBIDDEN);
        } else if (isChannelRedacted(request.channelId)) throw new MirthApiException(Response.Status.FORBIDDEN);
    }

    @Override public String getRuntime() { checkUserAuthorized(); return result(() -> api().runtimeInfo()); }
    @Override public String getProfiles(boolean archived) { checkUserAuthorized(); return result(() -> api().list(archived)); }
    @Override public String getProfile(String id) { checkUserAuthorized(); return result(() -> api().get(id)); }
    @Override public String createProfile(String json) {
        checkUserAuthorized(); PythonApi.ProfileRequest request = body(json, PythonApi.ProfileRequest.class);
        return result(() -> api().create(actor(), request));
    }
    @Override public String updateProfile(String id, String json) {
        checkUserAuthorized(); PythonApi.ProfileRequest request = body(json, PythonApi.ProfileRequest.class);
        return result(() -> api().update(actor(), id, request));
    }
    @Override public String archiveProfile(String id, String requestId, String epoch, Long revision) {
        checkUserAuthorized(); PythonApi.RevisionRequest request = new PythonApi.RevisionRequest();
        request.requestId = requestId; request.expectedRegistryEpoch = epoch; request.expectedRevision = revision;
        return result(() -> api().archive(actor(), id, request));
    }
    @Override public String getReferences(String id) {
        checkUserAuthorized(); return result(() -> api().references(id, channel -> !isChannelRedacted(channel)));
    }
    @Override public String prepareProfile(String id, String json) {
        checkUserAuthorized(); PythonApi.RevisionRequest request = body(json, PythonApi.RevisionRequest.class);
        int user = getCurrentUserId();
        return result(() -> api().prepare(id, request, () -> authorized(user, OP_PREPARE)));
    }
    @Override public String refreshProfile(String id, String json) {
        checkUserAuthorized(); extraPermission(OP_UPDATE);
        PythonApi.RevisionRequest request = body(json, PythonApi.RevisionRequest.class);
        return result(() -> api().refresh(actor(), id, request));
    }
    @Override public String getJob(String id) { checkUserAuthorized(); return result(() -> runtime().preparation.job(id)); }
    @Override public String getJobLog(String id, Long cursor) {
        checkUserAuthorized(); return result(() -> runtime().preparation.log(id, cursor == null ? 0 : cursor));
    }
    @Override public String cancelJob(String id) { checkUserAuthorized(); return result(() -> runtime().preparation.cancel(id)); }
    @Override public String validateScript(String json) {
        checkUserAuthorized(); PythonApi.ScriptRequest request = body(json, PythonApi.ScriptRequest.class); channelAccess(request);
        if (com.mirth.connect.plugins.python.PythonContract.ROLE_CODE_TEMPLATE.equals(request.role)) {
            throw response(ApiFailure.badRequest("INVALID_ROLE", "Use /templates/validate for code templates"));
        }
        return result(() -> api().validate(request));
    }
    protected void templateAccess() {
        try {
            Operation operation = com.mirth.connect.client.core.api.util.OperationUtil.getOperation(
                    com.mirth.connect.client.core.api.servlets.CodeTemplateServletInterface.class, "updateCodeTemplate",
                    String.class, com.mirth.connect.model.codetemplates.CodeTemplate.class, boolean.class);
            if (operation == null) throw new MirthApiException(Response.Status.FORBIDDEN);
            if (!controllerFactory.createAuthorizationController().isUserAuthorized(getCurrentUserId(), operation, Map.of(), getRequestIpAddress(), false)) {
                throw new MirthApiException(Response.Status.FORBIDDEN);
            }
        } catch (MirthApiException failure) { throw failure; }
        catch (Exception failure) { throw new MirthApiException(Response.Status.FORBIDDEN); }
    }
    @Override public String validateTemplate(String json) {
        checkUserAuthorized(); templateAccess();
        PythonApi.ScriptRequest request = body(json, PythonApi.ScriptRequest.class);
        if (!com.mirth.connect.plugins.python.PythonContract.ROLE_CODE_TEMPLATE.equals(request.role)) {
            throw response(ApiFailure.badRequest("INVALID_ROLE", "Template validation requires role code-template"));
        }
        return result(() -> api().validate(request));
    }
    @Override public String editorRequest(String json) {
        checkUserAuthorized();
        PythonEditorService.Request request = body(json, PythonEditorService.Request.class);
        if (com.mirth.connect.plugins.python.PythonContract.ROLE_CODE_TEMPLATE.equals(request.role)) templateAccess();
        else channelAccess(request);
        return result(() -> PythonEditorService.request(runtime(), request));
    }
    @Override public String testScript(String json) {
        checkUserAuthorized(); PythonApi.ScriptRequest request = body(json, PythonApi.ScriptRequest.class); channelAccess(request);
        return result(() -> api().test(request, request.channelId));
    }
    @Override public String exportProfiles(String ids) {
        checkUserAuthorized();
        return result(() -> api().export(ids == null || ids.isBlank() ? java.util.Collections.emptySet()
                : new LinkedHashSet<>(Arrays.asList(ids.split(",")))));
    }
    @Override public String importProfiles(String json) {
        checkUserAuthorized(); PythonApi.ImportRequest request = body(json, PythonApi.ImportRequest.class);
        return result(() -> api().importBundle(actor(), request));
    }
}
