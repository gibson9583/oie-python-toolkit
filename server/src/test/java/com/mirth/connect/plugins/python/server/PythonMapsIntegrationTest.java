/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ScriptableObject;
import com.mirth.connect.plugins.python.PythonCodeTemplateProperties;
import com.mirth.connect.plugins.python.PythonContract;
import com.mirth.connect.userutil.ImmutableConnectorMessage;

class PythonMapsIntegrationTest {
    private static final List<String> PHASES = List.of(PythonContract.ROLE_SOURCE_TRANSFORMER,
            PythonContract.ROLE_DESTINATION_TRANSFORMER, PythonContract.ROLE_RESPONSE_TRANSFORMER);

    static Map<String, Object> shared(PythonRuntimeIntegrationTest.Fixture fixture) {
        Map<String, Object> global = new ConcurrentHashMap<>(Map.of("unselected", "global", "remove", "old"));
        when(fixture.runtime.engine.globalMap()).thenReturn(global);
        when(fixture.runtime.engine.globalChannelMap(anyString())).thenReturn(new ConcurrentHashMap<>(Map.of("unselected", "channel")));
        doReturn(Map.of("site", "local", "$t", "user-data")).when(fixture.runtime.engine).configurationMap();
        return global;
    }

    @Test void transformerGlobalsReadWriteAndRollbackAcrossEveryPhase() throws Exception {
        String script = """
                assert globalMap.get('unselected') == 'global'
                assert globalMap.get('missing') is None
                assert globalMap.getVariables().get('unselected') == 'global'
                assert globalChannelMap['unselected'] == 'channel'
                assert configurationMap['site'] == 'local' and configurationMap['$t'] == 'user-data'
                assert sourceMap['origin'] == 'engine'
                assert 'inputs' not in globals() and 'channel_map' not in globals() and 'global_map' not in globals()
                channelMap.put('written', phase)
                connectorMap.put('written', phase)
                responseMap.put('written', phase)
                globalMap.put('written', phase)
                globalChannelMap.put('written', phase)
                globalMap.pop('remove', None)
                if msg == 'fail':
                    raise RuntimeError('rejected before commit')
                if msg == 'null':
                    globalMap['invalid'] = None
                if msg == 'configuration':
                    configurationMap['site'] = 'changed'
                if msg == 'source':
                    sourceMap['origin'] = 'changed'
                msg = msg + '|python'
                """;
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            Map<String, Object> global = shared(fixture);
            var deployment = PythonXmlRoundTripTest.deploy(fixture, script);
            for (String phase : PHASES) {
                for (String input : List.of("ok", "fail", "null", "configuration", "source", "ok")) {
                    global.put("written", "before");
                    Map<String, Object> response = new HashMap<>();
                    PythonXmlRoundTripTest.run(fixture, deployment, phase, "var msg='" + input + "';", "maps-" + phase + "-" + input, session -> {
                        ScriptableObject.putProperty(session.scope(), "sourceMap", Context.javaToJS(Map.of("origin", "engine"), session.scope()));
                        ScriptableObject.putProperty(session.scope(), "responseMap", Context.javaToJS(response, session.scope()));
                        if (input.equals("ok")) {
                            session.executePython();
                            assertEquals("ok|python", session.text("msg"));
                            assertEquals(phase, global.get("written"));
                            assertEquals(phase, response.get("written"));
                            assertEquals(phase, session.channelMap().get("written"));
                            assertEquals(phase, session.connectorMap().get("written"));
                            assertFalse(global.containsKey("remove"));
                        } else {
                            assertThrows(RuntimeException.class, session::executePython);
                            assertEquals(input, session.text("msg"));
                            assertEquals("before", global.get("written"));
                            assertTrue(response.isEmpty());
                            assertTrue(session.channelMap().isEmpty());
                            assertTrue(session.connectorMap().isEmpty());
                        }
                    });
                }
            }
        }
    }

    @Test void readerAndWriterShareStoresWithoutSelectorsAndValidateBeforeCommitting() throws Exception {
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            Map<String, Object> global = shared(fixture);
            var deployment = PythonRuntimeIntegrationTest.deployment(UUID.randomUUID().toString(), fixture.profileId, "maps");
            deployment.reader().setPythonScript("""
                    from oie_python import Message
                    assert 'channelMap' not in globals() and 'sourceMap' not in globals()
                    assert configurationMap['site'] == 'local'
                    globalMap.put('reader', globalMap.get('reader', 0) + 1)
                    globalChannelMap.put('reader', True)
                    result = Message('read') if globalMap['reader'] == 1 else object()
                    """);
            deployment.writer().setPythonScript("""
                    assert globalMap['reader'] == 1
                    assert globalChannelMap['reader'] is True
                    assert sourceMap['origin'] == 'reader'
                    globalMap.put('writer', 'written')
                    channelMap.put('written', True)
                    connectorMap.put('written', True)
                    responseMap.put('written', True)
                    result = 'accepted' if payload == '' else object()
                    """);
            fixture.runtime.deployments.prepare(deployment.model());
            when(fixture.runtime.engine.deployedChannel(deployment.model().getId())).thenReturn(deployment.channel());
            var reader = PythonRuntimeIntegrationTest.reader(deployment);
            var writer = PythonRuntimeIntegrationTest.writer(deployment);
            fixture.runtime.deployments.activate(deployment.channel());
            assertEquals("read", reader.executeRead().get(0).getRawData());
            assertEquals(1, global.get("reader"));
            assertThrows(Wire.ValueException.class, reader::executeRead);
            assertEquals(1, global.get("reader"), "An invalid Reader result must not advance shared state");
            var message = PythonRuntimeIntegrationTest.message(deployment.model().getId(), 1);
            message.setSourceMap(Map.of("origin", "reader"));
            assertEquals("accepted", writer.write(message, false).getMessage());
            assertEquals("written", global.get("writer"));
            assertEquals(Boolean.TRUE, message.getChannelMap().get("written"));
            assertEquals(Boolean.TRUE, message.getResponseMap().get("written"));
            global.put("writer", "before"); message.getChannelMap().clear();
            message.setEncoded(ConnectorContractTest.content(com.mirth.connect.donkey.model.message.ContentType.ENCODED, "invalid"));
            assertThrows(Wire.ValueException.class, () -> writer.write(message, false));
            assertEquals("before", global.get("writer")); assertTrue(message.getChannelMap().isEmpty());
        }
    }

    @Test void functionsReceiveCallingMessageMapsAndSharedStoresInEachPhase() throws Exception {
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            Map<String, Object> global = shared(fixture);
            var deployment = PythonRuntimeIntegrationTest.deployment(UUID.randomUUID().toString(), fixture.profileId, "maps");
            var properties = new PythonCodeTemplateProperties(); properties.setEnvironmentProfileId(fixture.profileId);
            properties.setFunctionName("py_maps");
            properties.setPythonScript("""
                    assert sourceMap['origin'] == 'engine'
                    assert globalMap.get('unselected') == 'global'
                    assert globalMap.get('missing') is None
                    assert globalMap.getVariables().get('unselected') == 'global'
                    assert globalChannelMap['unselected'] == 'channel'
                    globalMap.put('function', phase)
                    globalChannelMap.put('function', phase)
                    channelMap.put('function', phase)
                    connectorMap.put('function', phase)
                    responseMap.put('function', phase)
                    result = {'site': configurationMap['site']} if arguments[0] else float('nan')
                    """);
            var template = PythonCodeTemplateTest.template(properties);
            when(fixture.runtime.engine.codeTemplateLibraries()).thenReturn(List.of(PythonCodeTemplateTest.library(template, deployment.model().getId())));
            fixture.runtime.deployments.prepare(deployment.model());
            when(fixture.runtime.engine.deployedChannel(deployment.model().getId())).thenReturn(deployment.channel());
            fixture.runtime.deployments.activate(deployment.channel());
            for (String phase : PHASES) for (boolean valid : List.of(true, false)) {
                global.put("function", "before");
                int metadataId = PHASES.indexOf(phase) == 0 ? 0 : 1;
                var raw = PythonRuntimeIntegrationTest.message(deployment.model().getId(), metadataId);
                raw.setSourceMap(Map.of("origin", "engine"));
                try (var invocation = RuntimeInvocationContext.open(deployment.channel(), metadataId, phase, raw)) {
                    var cx = Context.enter();
                    try {
                        var scope = cx.initStandardObjects();
                        ScriptableObject.putProperty(scope, "connectorMessage", Context.javaToJS(new ImmutableConnectorMessage(raw, true), scope));
                        if (phase.equals(PythonContract.ROLE_RESPONSE_TRANSFORMER)) ScriptableObject.putProperty(scope, "responseStatus", "SENT");
                        cx.evaluateString(scope, properties.getCode(), "maps wrapper", 1, null);
                        if (valid) {
                            Object result = cx.evaluateString(scope, "py_maps(true).site", "maps caller", 1, null);
                            assertEquals("local", Context.toString(result));
                            assertEquals(phase, raw.getChannelMap().get("function"));
                            assertEquals(phase, raw.getConnectorMap().get("function"));
                            assertEquals(phase, raw.getResponseMap().get("function"));
                            assertEquals(phase, global.get("function"));
                        } else {
                            assertThrows(RuntimeException.class, () -> cx.evaluateString(scope, "py_maps(false)", "maps caller", 1, null));
                            assertTrue(raw.getChannelMap().isEmpty()); assertTrue(raw.getResponseMap().isEmpty());
                            assertEquals("before", global.get("function"));
                        }
                    } finally { Context.exit(); }
                }
            }
        }
    }
}
