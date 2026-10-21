/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Arrays;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.mirth.connect.client.core.Operation;
import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.plugins.python.PythonRuntimeServletInterface;
import com.mirth.connect.server.controllers.AuthorizationController;
import com.mirth.connect.server.controllers.ControllerFactory;

class PythonTemplateServletTest {
    static final class Harness extends PythonRuntimeServlet {
        boolean pluginAllowed = true;
        Harness(ControllerFactory factory) { super(null, null); controllerFactory = factory; }
        @Override protected void initializeControllers() {}
        @Override protected void initLogin() {}
        @Override protected int getCurrentUserId() { return 7; }
        @Override protected String getRequestIpAddress() { return "127.0.0.1"; }
        @Override public void checkUserAuthorized() { if (!pluginAllowed) throw new MirthApiException(Response.Status.FORBIDDEN); }
        @Override protected boolean doesUserHaveChannelRestrictions() { throw new AssertionError("Templates must not use channel editor restrictions"); }
        void checkTemplate() { templateAccess(); }
    }
    @Test void nativeTemplatePermissionIsRequiredIndependentlyOfChannelPermissions() throws Exception {
        var factory = mock(ControllerFactory.class); var authorization = mock(AuthorizationController.class);
        when(factory.createAuthorizationController()).thenReturn(authorization); var servlet = new Harness(factory);
        assertEquals(403, assertThrows(MirthApiException.class, () -> servlet.validateTemplate("{}" )).getResponse().getStatus());
        var operation = ArgumentCaptor.forClass(Operation.class);
        verify(authorization).isUserAuthorized(eq(7), operation.capture(), anyMap(), eq("127.0.0.1"), eq(false));
        assertEquals("updateCodeTemplate", operation.getValue().getName());
        assertFalse(operation.getValue() instanceof com.mirth.connect.client.core.ExtensionOperation);
        when(authorization.isUserAuthorized(eq(7), any(), anyMap(), anyString(), eq(false))).thenReturn(true);
        assertDoesNotThrow(servlet::checkTemplate);
        assertEquals(400, assertThrows(MirthApiException.class, () -> servlet.validateTemplate("{\"role\":\"source-transformer\"}")).getResponse().getStatus());
        servlet.pluginAllowed = false; clearInvocations(authorization);
        assertEquals(403, assertThrows(MirthApiException.class, () -> servlet.validateTemplate("{}")).getResponse().getStatus());
        verifyNoInteractions(authorization);
    }
    @Test void endpointUsesRegisteredPythonValidatePermission() throws Exception {
        var method = PythonRuntimeServletInterface.class.getMethod("validateTemplate", String.class);
        assertEquals("/templates/validate", method.getAnnotation(Path.class).value());
        var operation = method.getAnnotation(MirthOperation.class);
        assertEquals(PythonRuntimeServletInterface.PERMISSION_VALIDATE, operation.permission());
        var service = new PythonServicePlugin(null, null, null, null);
        assertTrue(Arrays.stream(service.getExtensionPermissions()).anyMatch(permission ->
                Arrays.asList(permission.getOperationNames()).contains(operation.name())
                && PythonRuntimeServletInterface.PERMISSION_VALIDATE.equals(permission.getDisplayName())));
    }
}
