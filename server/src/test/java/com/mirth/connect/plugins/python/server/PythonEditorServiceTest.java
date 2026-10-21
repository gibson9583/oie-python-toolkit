/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import javax.ws.rs.core.Response;
import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.AuthorizationController;
import java.util.Arrays;
import javax.ws.rs.Path;
import org.junit.jupiter.api.Test;
import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.plugins.python.PythonRuntimeServletInterface;

class PythonEditorServiceTest {
    @Test void editorEndpointUsesExistingValidationPermissionAndRegisteredOperation() throws Exception {
        var method = PythonRuntimeServletInterface.class.getMethod("editorRequest", String.class);
        assertEquals("/editor", method.getAnnotation(Path.class).value());
        var operation = method.getAnnotation(MirthOperation.class);
        assertEquals(PythonRuntimeServletInterface.PERMISSION_VALIDATE, operation.permission());
        var service = new PythonServicePlugin(null, null, null, null);
        assertTrue(Arrays.stream(service.getExtensionPermissions()).anyMatch(permission ->
                Arrays.asList(permission.getOperationNames()).contains(operation.name())
                && PythonRuntimeServletInterface.PERMISSION_VALIDATE.equals(permission.getDisplayName())));
    }

    static class Harness extends PythonRuntimeServlet {
        boolean pluginAllowed = true;
        Harness(ControllerFactory factory) { super(null, null); controllerFactory = factory; }
        @Override protected void initializeControllers() {}
        @Override protected void initLogin() {}
        @Override protected int getCurrentUserId() { return 7; }
        @Override protected String getRequestIpAddress() { return "127.0.0.1"; }
        @Override public void checkUserAuthorized() { if (!pluginAllowed) throw new MirthApiException(Response.Status.FORBIDDEN); }
        @Override protected boolean doesUserHaveChannelRestrictions() { return true; }
        @Override protected PythonRuntime runtime() { throw new AssertionError("Unauthorized editor request reached runtime"); }
    }
    @Test void editorRequestsRequireNativeTemplateOrChannelAccessBeforeStartingTooling() {
        var factory = mock(ControllerFactory.class);
        var authorization = mock(AuthorizationController.class);
        when(factory.createAuthorizationController()).thenReturn(authorization);
        var servlet = new Harness(factory);
        assertEquals(403, assertThrows(MirthApiException.class,
                () -> servlet.editorRequest("{\"role\":\"code-template\"}")).getResponse().getStatus());
        assertEquals(403, assertThrows(MirthApiException.class,
                () -> servlet.editorRequest("{\"role\":\"writer\"}")).getResponse().getStatus());
        servlet.pluginAllowed = false;
        clearInvocations(authorization);
        assertEquals(403, assertThrows(MirthApiException.class,
                () -> servlet.editorRequest("{}")).getResponse().getStatus());
        verifyNoInteractions(authorization);
    }

    @Test void rejectsUnboundedSourceInvalidRolesAndPositionsBeforeStartingAnyProcess() {
        var request = new PythonEditorService.Request();
        request.role = "writer"; request.operation = "complete"; request.pythonScript = "logger.";
        request.line = 1; request.column = 7;
        assertDoesNotThrow(() -> PythonEditorService.validate(request));
        request.column = 8;
        assertThrows(ApiFailure.class, () -> PythonEditorService.validate(request));
        request.column = 7; request.role = "filter";
        assertThrows(ApiFailure.class, () -> PythonEditorService.validate(request));
        request.role = "writer"; request.operation = "execute";
        assertThrows(ApiFailure.class, () -> PythonEditorService.validate(request));
        request.operation = "format"; request.pythonScript = "x".repeat(256 * 1024 + 1);
        assertThrows(ApiFailure.class, () -> PythonEditorService.validate(request));
        request.pythonScript = "result = 1";
        request.selection = new PythonEditorService.Selection();
        request.selection.startLineNumber = 1; request.selection.endLineNumber = 2;
        assertThrows(ApiFailure.class, () -> PythonEditorService.validate(request));
    }
}
