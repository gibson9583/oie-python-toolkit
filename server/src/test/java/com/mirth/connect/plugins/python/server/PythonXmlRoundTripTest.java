/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ScriptableObject;
import com.mirth.connect.model.Transformer;
import com.mirth.connect.plugins.python.PythonApi;
import com.mirth.connect.plugins.python.PythonContract;
import com.mirth.connect.plugins.python.PythonStep;
import com.mirth.connect.userutil.ImmutableConnectorMessage;

/** The generated engine wrapper, stock Rhino E4X, and a real CPython worker. */
class PythonXmlRoundTripTest {
    private static final List<String> PHASES = List.of(PythonContract.ROLE_SOURCE_TRANSFORMER,
            PythonContract.ROLE_DESTINATION_TRANSFORMER, PythonContract.ROLE_RESPONSE_TRANSFORMER);
    private static final String INPUT = """
            var m = new Namespace('urn:message');
            var t = new Namespace('urn:template');
            var msg = new XML('<m:root xmlns:m="urn:message"><m:value>A &amp; B — 雪</m:value></m:root>');
            var tmp = new XML('<t:root xmlns:t="urn:template"><t:value>before</t:value></t:root>');
            """;

    @Test void simultaneousMessageAndTemplateChangesRemainE4xInEveryTransformerPhase() throws Exception {
        String source = """
                import xml.etree.ElementTree as ET
                msg = ET.fromstring(msg)
                tmp = ET.fromstring(tmp)
                assert msg.find('{urn:message}value').text == 'A & B — 雪'
                msg.find('{urn:message}value').text += ' <Python> 🐍'
                msg.set('quote', '" & <')
                tmp.find('{urn:template}value').text = 'résumé & <done>'
                tmp = ET.tostring(tmp, encoding='unicode')
                channelMap['python'] = phase
                connectorMap['unicode'] = '雪 🐍'
                """;
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            var deployment = deploy(fixture, source);
            for (String phase : PHASES) {
                run(fixture, deployment, phase, INPUT, phase, session -> {
                    session.executePython();
                    assertEquals("xml", session.text("typeof msg"));
                    assertEquals("xml", session.text("typeof tmp"));
                    assertEquals("urn:message", session.text("msg.namespace().uri"));
                    assertEquals("urn:template", session.text("tmp.namespace().uri"));
                    assertEquals("A & B — 雪 <Python> 🐍", session.text("msg.m::value"));
                    assertEquals("\" & <", session.text("msg.@quote"));
                    assertEquals("résumé & <done>", session.text("tmp.t::value"));
                    assertEquals(phase, session.channelMap.get("python"));
                    assertEquals("雪 🐍", session.connectorMap.get("unicode"));

                    session.eval("""
                            msg.m::value = String(msg.m::value) + ' | JavaScript';
                            tmp.t::value = String(tmp.t::value) + ' | JavaScript';
                            msg.@after = 'yes';
                            tmp.@after = 'yes';
                            """);
                    assertEquals("A & B — 雪 <Python> 🐍 | JavaScript", session.text("msg.m::value"));
                    assertEquals("résumé & <done> | JavaScript", session.text("tmp.t::value"));
                    assertEquals("yes", session.text("msg.@after"));
                    assertEquals("yes", session.text("tmp.@after"));
                });
            }
        }
    }

    @Test void unchangedXmlAndXmlListsKeepTheirOriginalRhinoObjects() throws Exception {
        String source = """
                assert isinstance(msg, str) and isinstance(tmp, str)
                channelMap['executed'] = True
                """;
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            var deployment = deploy(fixture, source);
            for (String kind : List.of("XML", "XMLList")) {
                String setup = "var msg = new " + kind + "('<a>unchanged</a>');"
                        + "var tmp = new " + kind + "('<b>template</b>');";
                run(fixture, deployment, PHASES.get(0), setup, "unchanged-" + kind, session -> {
                    Object msg = session.scope.get("msg", session.scope);
                    Object tmp = session.scope.get("tmp", session.scope);
                    String beforeMsg = session.text("msg.toXMLString()");
                    String beforeTmp = session.text("tmp.toXMLString()");
                    session.executePython();
                    assertSame(msg, session.scope.get("msg", session.scope));
                    assertSame(tmp, session.scope.get("tmp", session.scope));
                    assertEquals(beforeMsg, session.text("msg.toXMLString()"));
                    assertEquals(beforeTmp, session.text("tmp.toXMLString()"));
                    assertEquals(Boolean.TRUE, session.channelMap.get("executed"));
                });
            }
        }
    }

    @Test void changedXmlListsKeepTheirKindAndSupportSubsequentJavascriptEdits() throws Exception {
        String source = """
                msg = msg.replace('before', 'Python') + '<a>second</a>'
                tmp = ''
                """;
        String setup = """
                var msg = new XMLList('<a>before</a>');
                var tmp = new XMLList('<b>before</b>');
                """;
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            var deployment = deploy(fixture, source);
            run(fixture, deployment, PHASES.get(0), setup, "changed-XMLList", session -> {
                session.executePython();
                assertEquals("[object XMLList]", session.text("Object.prototype.toString.call(msg)"));
                assertEquals("[object XMLList]", session.text("Object.prototype.toString.call(tmp)"));
                assertEquals("Python", session.text("msg[0]"));
                assertEquals("0", session.text("tmp.length()"));
                session.eval("msg[1].setChildren('JavaScript'); tmp += new XML('<b>JavaScript</b>');");
                assertEquals("JavaScript", session.text("msg[1]"));
                assertEquals("JavaScript", session.text("tmp[0]"));
                assertEquals("2", session.text("msg.length()"));
                assertEquals("1", session.text("tmp.length()"));
            });
        }
    }

    @Test void malformedMessageOrTemplateCannotPartiallyApplyDocumentsOrMaps() throws Exception {
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            for (String invalid : List.of("msg", "tmp")) {
                String source = """
                msg = '<changed>valid</changed>'
                tmp = '<changed>valid</changed>'
                channelMap['partial'] = 'must not apply'
                del connectorMap['keep']
                if phase == 'response-transformer':
                    response.status = 'ERROR'
                    response.status_message = 'must not apply'
                    response.error = 'must not apply'
                """ + invalid + " = '<broken>'\n";
                var deployment = deploy(fixture, source);
                for (String phase : PHASES) {
                    run(fixture, deployment, phase, INPUT, "invalid-" + invalid + "-" + phase, session -> {
                        session.channelMap.put("keep", "channel");
                        session.connectorMap.put("keep", "connector");
                        Object msg = session.scope.get("msg", session.scope);
                        Object tmp = session.scope.get("tmp", session.scope);
                        String beforeMsg = session.text("msg.toXMLString()");
                        String beforeTmp = session.text("tmp.toXMLString()");
                        RuntimeException error = assertThrows(RuntimeException.class, session::executePython);
                        assertTrue(error.getMessage().contains(invalid + " is not well-formed XML"), error.toString());
                        assertSame(msg, session.scope.get("msg", session.scope));
                        assertSame(tmp, session.scope.get("tmp", session.scope));
                        assertEquals(beforeMsg, session.text("msg.toXMLString()"));
                        assertEquals(beforeTmp, session.text("tmp.toXMLString()"));
                        assertEquals(Map.of("keep", "channel"), session.channelMap);
                        assertEquals(Map.of("keep", "connector"), session.connectorMap);
                        if (PythonContract.ROLE_RESPONSE_TRANSFORMER.equals(phase)) {
                            assertEquals("SENT", session.text("responseStatus"));
                            assertEquals("original status", session.text("responseStatusMessage"));
                            assertEquals("original error", session.text("responseErrorMessage"));
                        }
                    });
                }
                fixture.recordWorkers();
                fixture.runtime.deployments.retire(deployment.channel(), "XML case completed");
            }
        }
    }

    @Test void elementTreeEditsPreserveUnnamespacedChildrenUnderTheEngineDefaultNamespace() throws Exception {
        String source = """
                import xml.etree.ElementTree as ET
                msg = ET.fromstring(msg)
                tmp = ET.fromstring(tmp)
                msg.set('changed', 'Python')
                tmp.set('changed', 'Python')
                """;
        String setup = """
                var msg = new XML('<root xmlns="urn:message"><plain xmlns=""/></root>');
                var tmp = new XML('<template xmlns="urn:template"><plain xmlns=""/></template>');
                """;
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            var deployment = deploy(fixture, source);
            for (String phase : PHASES) {
                run(fixture, deployment, phase, setup, "namespace-" + phase, session -> {
                    assertEquals("urn:message", session.text("new XML('<after/>').namespace().uri"));
                    assertEquals("", session.text("msg.*[0].namespace().uri"));
                    assertEquals("", session.text("tmp.*[0].namespace().uri"));
                    session.executePython();
                    assertEquals("urn:message", session.text("msg.namespace().uri"));
                    assertEquals("urn:template", session.text("tmp.namespace().uri"));
                    assertEquals("Python", session.text("msg.@changed"));
                    assertEquals("Python", session.text("tmp.@changed"));
                    assertAll("Unprefixed children must retain their original empty namespace",
                            () -> assertEquals("", session.text("msg.*[0].namespace().uri"), "msg child"),
                            () -> assertEquals("", session.text("tmp.*[0].namespace().uri"), "tmp child"));
                });
            }
        }
    }

    @Test void outputWithoutAnOutboundTemplateChangesMessageAndRemainsE4x() throws Exception {
        String source = """
                import xml.etree.ElementTree as ET
                assert not has_tmp
                msg = ET.fromstring(msg)
                msg.set('changed', 'Python')
                """;
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            var deployment = deploy(fixture, source);
            for (String phase : PHASES) {
                run(fixture, deployment, phase, "var msg = new XML('<root/>');", "no-template-" + phase, session -> {
                    session.executePython();
                    assertEquals("xml", session.text("typeof msg"));
                    assertEquals("undefined", session.text("typeof tmp"));
                    assertEquals("Python", session.text("msg.@changed"));
                    session.eval("msg.@after = 'JavaScript';");
                    assertEquals("JavaScript", session.text("msg.@after"));
                });
            }
        }
    }

    @Test void shortAndWhitespacePaddedXmlReplacementsRemainElements() throws Exception {
        Class<?> processor = Class.forName("org.mozilla.javascript.xmlimpl.XmlProcessor");
        var engineLocation = com.mirth.connect.server.util.javascript.MirthContextFactory.class
                .getProtectionDomain().getCodeSource().getLocation();
        byte[] processorBytes;
        try (var engine = new JarFile(Path.of(engineLocation.toURI()).toFile());
                var loaded = processor.getResourceAsStream("XmlProcessor.class");
                var expected = engine.getInputStream(engine.getJarEntry("org/mozilla/javascript/xmlimpl/XmlProcessor.class"))) {
            assertNotNull(loaded);
            processorBytes = loaded.readAllBytes();
            assertArrayEquals(expected.readAllBytes(), processorBytes,
                    "Exercise the same XmlProcessor class bytes shipped in the OIE server");
        }
        String[][] variants = {
                {"<a/>", "<b/>", "a", "b"},
                {"\n<a/>\n", " <b/> ", "a", "b"},
                {"\n<root><value>changed</value></root>\n",
                        "\n<template><value>changed</value></template>\n", "root", "template"}
        };
        String setup = """
                var msg = new XML('<message xmlns="urn:message"/>');
                var tmp = new XML('<template xmlns="urn:template"/>');
                """;
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            Files.writeString(fixture.evidence.resolve("engine-xml-processor.json"),
                    PythonApi.mapper().writeValueAsString(Map.of(
                            "codeSource", processor.getProtectionDomain().getCodeSource().getLocation().toString(),
                            "referenceServer", engineLocation.toString(),
                            "classSha256", com.mirth.connect.plugins.python.CanonicalJson.sha256Hex(processorBytes))));
            for (int index = 0; index < variants.length; index++) {
                String[] value = variants[index];
                String source = "msg = "
                        + PythonApi.mapper().writeValueAsString(value[0]) + "\ntmp = "
                        + PythonApi.mapper().writeValueAsString(value[1]) + "\n";
                var deployment = deploy(fixture, source);
                for (String phase : PHASES) {
                    run(fixture, deployment, phase, setup, "element-" + index + "-" + phase, session -> {
                        session.executePython();
                        assertEquals("element", session.text("msg.nodeKind()"));
                        assertEquals("element", session.text("tmp.nodeKind()"));
                        assertEquals(value[2], session.text("msg.localName()"));
                        assertEquals(value[3], session.text("tmp.localName()"));
                        assertEquals("", session.text("msg.namespace().uri"));
                        assertEquals("", session.text("tmp.namespace().uri"));
                        session.eval("msg.@after = 'JavaScript'; tmp.@after = 'JavaScript';");
                        assertEquals("JavaScript", session.text("msg.@after"));
                        assertEquals("JavaScript", session.text("tmp.@after"));
                    });
                }
                fixture.recordWorkers();
                fixture.runtime.deployments.retire(deployment.channel(), "XML replacement case completed");
            }
        }
    }

    static PythonRuntimeIntegrationTest.Deployment deploy(
            PythonRuntimeIntegrationTest.Fixture fixture, String source) throws Exception {
        var deployment = PythonRuntimeIntegrationTest.deployment(UUID.randomUUID().toString(), fixture.profileId, "XML");
        deployment.source().setPythonScript(source);
        deployment.response().setPythonScript(source);
        PythonStep destination = new PythonStep();
        destination.setEnvironmentProfileId(fixture.profileId);
        destination.setPythonScript(source);
        destination.setEnabled(true);
        Transformer transformer = new Transformer();
        transformer.getElements().add(destination);
        deployment.model().getDestinationConnectors().get(0).setTransformer(transformer);
        fixture.runtime.deployments.prepare(deployment.model());
        when(fixture.runtime.engine.deployedChannel(deployment.model().getId())).thenReturn(deployment.channel());
        fixture.runtime.deployments.activate(deployment.channel());
        return deployment;
    }

    record Session(Context context, ScriptableObject scope, String wrapper,
            Map<String, Object> channelMap, Map<String, Object> connectorMap) {
        Object eval(String script) { return context.evaluateString(scope, script, "XML round trip", 1, null); }
        String text(String script) { return Context.toString(eval(script)); }
        void executePython() { eval(wrapper); }
    }

    static void run(PythonRuntimeIntegrationTest.Fixture fixture,
            PythonRuntimeIntegrationTest.Deployment deployment, String phase, String setup,
            String evidenceName, Consumer<Session> assertions) throws Exception {
        int metadataId = PythonContract.ROLE_SOURCE_TRANSFORMER.equals(phase) ? 0 : 1;
        var message = PythonRuntimeIntegrationTest.message(deployment.model().getId(), metadataId);
        try (var invocation = RuntimeInvocationContext.open(deployment.channel(), metadataId, phase, message)) {
            Context context = Context.enter();
            try {
                ScriptableObject scope = context.initStandardObjects();
                ScriptableObject.putProperty(scope, "connectorMessage",
                        Context.javaToJS(new ImmutableConnectorMessage(message, true), scope));
                ScriptableObject.putProperty(scope, "channelMap", Context.javaToJS(message.getChannelMap(), scope));
                ScriptableObject.putProperty(scope, "connectorMap", Context.javaToJS(message.getConnectorMap(), scope));
                if (PythonContract.ROLE_RESPONSE_TRANSFORMER.equals(phase)) {
                    ScriptableObject.putProperty(scope, "responseStatus",
                            Context.javaToJS(com.mirth.connect.userutil.Status.SENT, scope));
                    ScriptableObject.putProperty(scope, "responseStatusMessage", "original status");
                    ScriptableObject.putProperty(scope, "responseErrorMessage", "original error");
                }
                Session session = new Session(context, scope, PythonContract.stepWrapper(PythonContract.specDigest(deployment.source())),
                        message.getChannelMap(), message.getConnectorMap());
                session.eval(setup);
                // Match the stock XML transformer initialization. An earlier JS
                // step can also replace msg with an XMLList, covered separately.
                session.eval("if (Object.prototype.toString.call(msg) == '[object XML]') {"
                        + " if (msg.namespace('') != undefined) { default xml namespace = msg.namespace(''); }"
                        + " else { default xml namespace = ''; } }");
                String callerNamespace = session.text("new XML('<after/>').namespace().uri");
                assertions.accept(session);
                assertEquals(callerNamespace, session.text("new XML('<after/>').namespace().uri"),
                        "The wrapper must not change the caller's default XML namespace");
                Files.writeString(fixture.evidence.resolve("xml-" + evidenceName + ".json"),
                        PythonApi.mapper().writeValueAsString(Map.of("phase", phase,
                                "msg", session.text("typeof msg === 'xml' ? msg.toXMLString() : JSON.stringify(msg)"),
                                "tmp", scope.has("tmp", scope) ? session.text("typeof tmp === 'xml' ? tmp.toXMLString() : JSON.stringify(tmp)") : "absent",
                                "channelMap", session.channelMap, "connectorMap", session.connectorMap)));
            } finally {
                Context.exit();
            }
        }
    }
}
