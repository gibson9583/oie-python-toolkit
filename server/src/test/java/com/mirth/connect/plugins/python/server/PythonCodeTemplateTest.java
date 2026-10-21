/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ScriptableObject;
import com.mirth.connect.model.codetemplates.*;
import com.mirth.connect.plugins.python.*;
import com.mirth.connect.userutil.ImmutableConnectorMessage;

class PythonCodeTemplateTest {
    static CodeTemplateLibrary library(CodeTemplate template, String channelId) {
        var library = new CodeTemplateLibrary(); library.setId(UUID.randomUUID().toString()); library.setName("Python functions");
        library.getEnabledChannelIds().add(channelId); library.getCodeTemplates().add(template); return library;
    }
    static CodeTemplate template(PythonCodeTemplateProperties properties) {
        return new CodeTemplate("Python function", properties, new CodeTemplateContextSet(Surfaces.templateContexts()));
    }
    static String invoke(PythonRuntimeIntegrationTest.Deployment deployment, String wrapper, int metadataId, String phase, String expression) {
        var raw = PythonRuntimeIntegrationTest.message(deployment.model().getId(), metadataId);
        try (var span = RuntimeInvocationContext.open(deployment.channel(), metadataId, phase, raw)) {
            var cx = Context.enter();
            try {
                cx.setLanguageVersion(Context.VERSION_ES6);
                var scope = cx.initStandardObjects();
                ScriptableObject.putProperty(scope, "connectorMessage", Context.javaToJS(new ImmutableConnectorMessage(raw, true), scope));
                if (PythonContract.ROLE_RESPONSE_TRANSFORMER.equals(phase)) ScriptableObject.putProperty(scope, "responseStatus", "SENT");
                cx.evaluateString(scope, wrapper, "template", 1, null);
                return Context.toString(cx.evaluateString(scope, "JSON.stringify(" + expression + ")", "caller", 1, null));
            } finally { Context.exit(); }
        }
    }
    @Test void compiledRhinoTemplateCallsRealPythonInEachPhaseAndPinsSourceUntilRedeploy() throws Exception {
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            var deployment = PythonRuntimeIntegrationTest.deployment(UUID.randomUUID().toString(), fixture.profileId, "step");
            var properties = new PythonCodeTemplateProperties(); properties.setEnvironmentProfileId(fixture.profileId);
            properties.setFunctionName("py_echo");
            properties.setPythonScript("result = float('nan') if arguments and arguments[0] == 'bad' else "
                    + "{'arguments': arguments, 'phase': phase, 'site': configurationMap['site'], 'version': 'A'}\n");
            var template = template(properties); var library = library(template, deployment.model().getId());
            when(fixture.runtime.engine.codeTemplateLibraries()).thenReturn(List.of(library));
            when(fixture.runtime.engine.savedCodeTemplates()).thenReturn(List.of(template));
            doReturn(Map.of("site", "local")).when(fixture.runtime.engine).configurationMap();
            var binding = fixture.runtime.deployments.prepare(deployment.model());
            when(fixture.runtime.engine.deployedChannel(deployment.model().getId())).thenReturn(deployment.channel());
            fixture.runtime.deployments.activate(deployment.channel());
            String wrapper = properties.getCode();
            for (String phase : List.of(PythonContract.ROLE_SOURCE_TRANSFORMER, PythonContract.ROLE_DESTINATION_TRANSFORMER, PythonContract.ROLE_RESPONSE_TRANSFORMER)) {
                int metadataId = PythonContract.ROLE_SOURCE_TRANSFORMER.equals(phase) ? 0 : 1;
                var result = PythonApi.mapper().readTree(invoke(deployment, wrapper, metadataId, phase, "py_echo({x: 7}, null, 'hello')"));
                assertEquals(phase, result.get("phase").asText()); assertEquals(7, result.get("arguments").get(0).get("x").asInt());
                assertTrue(result.get("arguments").get(1).isNull()); assertEquals("local", result.get("site").asText());
                Files.writeString(fixture.evidence.resolve("template-" + phase + ".json"), result.toPrettyString());
                // Stock RAW transformers construct msg with new String in all three phases.
                for (String argument : List.of("'hello'", "new String('hello')")) {
                    var rawResult = PythonApi.mapper().readTree(invoke(deployment, wrapper, metadataId, phase,
                            "py_echo(" + argument + ", {nested: [new String('nested')]})"));
                    assertEquals("hello", rawResult.get("arguments").get(0).asText());
                    assertEquals("nested", rawResult.get("arguments").get(1).get("nested").get(0).asText());
                }
            }
            for (String expression : List.of("py_echo(undefined)", "py_echo(Infinity)", "py_echo(function(){})", "py_echo('bad')")) {
                assertThrows(RuntimeException.class, () -> invoke(deployment, wrapper, 0, PythonContract.ROLE_SOURCE_TRANSFORMER, expression));
            }
            for (String argument : List.of("NaN", "-Infinity", "[, 'sparse']", "{nested: undefined}",
                    "(function(){var a = {}; a.self = a; return a;})()",
                    "new java.util.Date()", "new java.lang.StringBuilder('raw')",
                    "{toString: function(){return 'raw';}}",
                    // Only native boxed strings are normalized; other boxed scalars stay unsupported.
                    "new Number(7)", "new Number(Infinity)", "new Boolean(false)")) {
                var failure = assertThrows(RuntimeException.class, () -> invoke(deployment, wrapper, 0,
                        PythonContract.ROLE_SOURCE_TRANSFORMER, "py_echo(" + argument + ")"), argument);
                assertTrue(failure.getMessage().contains("Python function arguments"), failure.getMessage());
            }
            properties.setPythonScript("result = 'B'\n");
            assertEquals("A", PythonApi.mapper().readTree(invoke(deployment, wrapper, 0, PythonContract.ROLE_SOURCE_TRANSFORMER, "py_echo()")).get("version").asText());
            assertThrows(RuntimeException.class, () -> invoke(deployment, properties.getCode(), 0, PythonContract.ROLE_SOURCE_TRANSFORMER, "py_echo()"));
            var newer = PythonRuntimeIntegrationTest.deployment(deployment.model().getId(), fixture.profileId, "step");
            fixture.runtime.deployments.prepare(newer.model());
            when(fixture.runtime.engine.deployedChannel(newer.model().getId())).thenReturn(newer.channel());
            fixture.runtime.deployments.activate(newer.channel());
            assertEquals("\"B\"", invoke(newer, properties.getCode(), 0, PythonContract.ROLE_SOURCE_TRANSFORMER, "py_echo()"));
            assertThrows(RuntimeException.class, () -> invoke(deployment, wrapper, 0, PythonContract.ROLE_SOURCE_TRANSFORMER, "py_echo()"));
            assertEquals(Deployments.State.RETIRED, binding.state());
            var request = new PythonApi.ScriptRequest(); request.role = PythonContract.ROLE_CODE_TEMPLATE;
            request.functionName = properties.getFunctionName(); request.pythonScript = properties.getPythonScript();
            request.environmentProfileId = fixture.profileId;
            request.contextTypes = List.of(ContextType.SOURCE_FILTER_TRANSFORMER.name());
            request.expectedRegistryEpoch = fixture.registry.epoch();
            request.expectedEnvironmentRevision = fixture.registry.snapshot().profiles.get(fixture.profileId).revision;
            var validated = fixture.api.validate(request); assertEquals("valid", validated.status);
            assertEquals(PythonContract.specDigest(properties), validated.sourceDigest);
            request.contextTypes = List.of(ContextType.GLOBAL_DEPLOY.name());
            assertEquals("INVALID_TEMPLATE_CONTEXT", assertThrows(ApiFailure.class, () -> fixture.api.validate(request)).code);
            var archive = new PythonApi.RevisionRequest(); archive.requestId = UUID.randomUUID().toString(); archive.expectedRegistryEpoch = fixture.registry.epoch();
            archive.expectedRevision = fixture.registry.snapshot().profiles.get(fixture.profileId).revision;
            assertThrows(ApiFailure.class, () -> fixture.api.archive("test", fixture.profileId, archive));
        }
    }
    @Test void libraryInclusionAndContextsMatchStock() {
        var model = PythonRuntimeIntegrationTest.deployment("channel", "profile", "test").model();
        var properties = new PythonCodeTemplateProperties(); var template = template(properties); var library = library(template, "other");
        assertTrue(Surfaces.templates(model, List.of(library)).isEmpty());
        library.setIncludeNewChannels(true); assertEquals(3, Surfaces.templates(model, List.of(library)).size());
        library.getDisabledChannelIds().add("channel"); assertTrue(Surfaces.templates(model, List.of(library)).isEmpty());
        library.getEnabledChannelIds().add("channel"); assertEquals(3, Surfaces.templates(model, List.of(library)).size());
        template.getContextSet().add(ContextType.GLOBAL_DEPLOY); assertEquals(3, Surfaces.templates(model, List.of(library)).size());
        template.setContextSet(new CodeTemplateContextSet()); assertTrue(Surfaces.templates(model, List.of(library)).isEmpty());
    }
    @Test void unavailableFunctionsDoNotBlockDeploymentAndReportTheirReasonWhenCalled() throws Exception {
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            var runtimeChannel = PythonRuntimeIntegrationTest.deployment(UUID.randomUUID().toString(), fixture.profileId, "unused").channel();
            var model = new com.mirth.connect.model.Channel(); model.setId(runtimeChannel.getChannelId()); model.setName("JavaScript only"); model.setRevision(1);
            var source = new com.mirth.connect.model.Connector("Source"); source.setEnabled(true); source.setMetaDataId(0); source.setTransportName("Channel Reader");
            source.setTransformer(new com.mirth.connect.model.Transformer());
            var destination = new com.mirth.connect.model.Connector("Destination"); destination.setEnabled(true); destination.setMetaDataId(1); destination.setTransportName("Channel Writer");
            destination.setTransformer(new com.mirth.connect.model.Transformer()); destination.setResponseTransformer(new com.mirth.connect.model.Transformer());
            model.setSourceConnector(source); model.getDestinationConnectors().add(destination);
            var deployment = new PythonRuntimeIntegrationTest.Deployment(model, runtimeChannel, null, null, null, null);
            assertTrue(Surfaces.of(model).isEmpty());
            var request = new PythonApi.ProfileRequest(); request.requestId = UUID.randomUUID().toString(); request.expectedRegistryEpoch = fixture.registry.epoch();
            request.name = "Saved but not prepared"; request.runtimeId = "python3"; request.packageSourceId = "offline"; request.requirementsText = "";
            String unpreparedProfile = fixture.api.create("test", request).profile.id;
            var ok = new PythonCodeTemplateProperties(); ok.setEnvironmentProfileId(fixture.profileId); ok.setFunctionName("py_ok"); ok.setPythonScript("result = 'ok'\n");
            var broken = new PythonCodeTemplateProperties(); broken.setEnvironmentProfileId(fixture.profileId); broken.setFunctionName("py_broken"); broken.setPythonScript("result = (\n");
            var unprepared = new PythonCodeTemplateProperties(); unprepared.setEnvironmentProfileId(unpreparedProfile); unprepared.setFunctionName("py_unprepared");
            var library = library(template(ok), model.getId()); library.getCodeTemplates().add(template(broken)); library.getCodeTemplates().add(template(unprepared));
            when(fixture.runtime.engine.codeTemplateLibraries()).thenReturn(List.of(library));
            var binding = fixture.runtime.deployments.prepare(model);
            assertEquals(3, binding.specs.size()); assertEquals(6, binding.unavailable.size()); assertEquals(3, fixture.runtime.artifacts.activeLeases());
            when(fixture.runtime.engine.deployedChannel(model.getId())).thenReturn(runtimeChannel);
            fixture.runtime.deployments.activate(runtimeChannel);
            String wrappers = ok.getCode() + broken.getCode() + unprepared.getCode();
            assertEquals("\"ok\"", invoke(deployment, wrappers, 0, PythonContract.ROLE_SOURCE_TRANSFORMER, "py_ok()"));
            var syntax = assertThrows(RuntimeException.class, () -> invoke(deployment, wrappers, 1, PythonContract.ROLE_DESTINATION_TRANSFORMER, "py_broken()"));
            assertTrue(syntax.getMessage().contains("unavailable in this deployment") && syntax.getMessage().contains("line 1"), syntax.getMessage());
            var missing = assertThrows(RuntimeException.class, () -> invoke(deployment, wrappers, 1, PythonContract.ROLE_RESPONSE_TRANSFORMER, "py_unprepared()"));
            assertTrue(missing.getMessage().contains("Response Transformer / Python function") && missing.getMessage().contains("is not prepared"), missing.getMessage());
        }
    }
    @Test void wrapperNamesCannotShadowTheBuiltinsOrMessageGlobalsTheyNeed() {
        // These names are legal JavaScript identifiers, but function declarations
        // replace same-named globals before any wrapper body executes. In stock
        // Rhino this made JSON/Array/Object fail and isFinite recurse indefinitely.
        var cx = Context.enter();
        try {
            for (String name : List.of("JSON", "Array", "Object", "Packages", "isFinite", "String", "Error", "connectorMessage", "responseStatus")) {
                var properties = new PythonCodeTemplateProperties(); properties.setFunctionName(name);
                assertThrows(IllegalArgumentException.class, properties::getCode, name);
                assertThrows(IllegalArgumentException.class, () -> PythonContract.validateFunctionName(name), name);
                properties.setFunctionName("py_" + name);
                assertDoesNotThrow(() -> cx.compileString(properties.getCode(), "safe template name", 1, null), name);
            }
        } finally { Context.exit(); }
    }
    @Test void customPropertiesRoundTripAndCloneWithoutSwingOrSourceInWrapper() {
        var properties = new PythonCodeTemplateProperties(); properties.setFunctionName("py_count");
        var template = template(properties); var serializer = com.mirth.connect.model.converters.ObjectXMLSerializer.getInstance();
        try { serializer.init("4.6.0"); } catch (Exception alreadyInitialized) { if (!alreadyInitialized.getMessage().contains("already")) throw new RuntimeException(alreadyInitialized); }
        var restored = serializer.deserialize(serializer.serialize(template), CodeTemplate.class);
        assertInstanceOf(PythonCodeTemplateProperties.class, restored.getProperties());
        assertEquals(properties.getPythonScript(), ((PythonCodeTemplateProperties) restored.getProperties()).getPythonScript());
        assertEquals(properties.getCode(), restored.getCode()); assertFalse(restored.getCode().contains("def call"));
        var copy = properties.clone(); copy.setPythonScript("result = 42\n"); assertNotEquals(properties.getPythonScript(), copy.getPythonScript());
        assertThrows(IllegalArgumentException.class, () -> PythonContract.templateWrapper("x); dangerous()", "a".repeat(64)));
    }
}
