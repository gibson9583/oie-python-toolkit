/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import com.mirth.connect.plugins.python.PythonContract;

class PythonWrapperTest {
    @Test void engineBoxedRawMessagesAndTemplatesStayTypedIncludingEmptyStrings() throws Exception {
        for (String initial : List.of("new String('message')", "new String('')", "'primitive'")) {
            AtomicReference<List<Object>> observed = new AtomicReference<>();
            try (MockedStatic<PythonBridge> bridge = mockStatic(PythonBridge.class)) {
                bridge.when(() -> PythonBridge.executeStep(anyString(), anyString(), any(), anyString(), anyString(), anyString(), anyString(),
                        any(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
                    observed.set(List.of(call.getArgument(3), call.getArgument(4), call.getArgument(5), call.getArgument(6)));
                    return new StepResult("raw", "replacement", "raw", "template result", false, null, null, null, List.of());
                });
                Context context = Context.enter();
                try {
                    Scriptable scope = context.initStandardObjects();
                    context.evaluateString(scope, "var connectorMessage={getMetaDataId:function(){return 0;}}; var msg=" + initial
                            + "; var tmp=new String('');", "engine raw input", 1, null);
                    context.evaluateString(scope, PythonContract.stepWrapper("a".repeat(64)), "Python wrapper", 1, null);
                    assertEquals("raw", observed.get().get(0));
                    assertEquals("raw", observed.get().get(2));
                    assertEquals("", observed.get().get(3));
                    assertEquals("replacement", Context.toString(scope.get("msg", scope)));
                    assertEquals("template result", Context.toString(scope.get("tmp", scope)));
                } finally { Context.exit(); }
            }
        }
    }

    @Test void responseStatusUsesTheEngineUserutilEnum() throws Exception {
        try (MockedStatic<PythonBridge> bridge = mockStatic(PythonBridge.class)) {
            bridge.when(() -> PythonBridge.executeStep(anyString(), anyString(), any(), anyString(), anyString(), anyString(), any(),
                    any(), any(), any(), any(), any(), any(), any())).thenReturn(
                    new StepResult(null, null, null, null, true, com.mirth.connect.donkey.model.message.Status.ERROR,
                            "changed", "diagnostic", List.of()));
            Context context = Context.enter();
            try {
                Scriptable scope = context.initStandardObjects();
                context.evaluateString(scope, "var connectorMessage={getMetaDataId:function(){return 1;}}; var msg=new String('raw');"
                        + "var responseStatus=Packages.com.mirth.connect.userutil.Status.SENT; var responseStatusMessage=''; var responseErrorMessage='';",
                        "engine response input", 1, null);
                context.evaluateString(scope, PythonContract.stepWrapper("a".repeat(64)), "Python response wrapper", 1, null);
                Object status = Context.jsToJava(scope.get("responseStatus", scope), com.mirth.connect.userutil.Status.class);
                assertSame(com.mirth.connect.userutil.Status.ERROR, status);
                assertEquals("changed", Context.toString(scope.get("responseStatusMessage", scope)));
                assertEquals("diagnostic", Context.toString(scope.get("responseErrorMessage", scope)));
            } finally { Context.exit(); }
        }
    }
}
