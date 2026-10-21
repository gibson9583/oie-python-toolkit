/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import com.mirth.connect.plugins.python.PythonCodeTemplateProperties;
import com.mirth.connect.plugins.python.PythonContract;
import com.mirth.connect.server.controllers.ScriptController;
import com.mirth.connect.server.util.CompiledScriptCache;

class TemplateSnapshotsTest {
    @Test void checksActualStockCacheKeysAndIgnoresCommentsAndQuotedWrapperText() {
        String channelId = UUID.randomUUID().toString();
        var properties = new PythonCodeTemplateProperties();
        String wrapper = properties.getCode();
        var cache = CompiledScriptCache.getInstance();
        var engine = new EngineAccess.Live();
        var cx = Context.enter();
        try {
            for (boolean response : List.of(false, true)) {
                int metadataId = response ? 1 : 0;
                String key = response ? ScriptController.RESPONSE_TRANSFORMER + "_1" : "JavaScript_Filter_Transformer_0";
                String id = ScriptController.getScriptId(key, channelId);
                try {
                    cache.putCompiledScript(id, cx.compileString(wrapper, id, 1, null), wrapper);
                    assertEquals(Set.of(PythonContract.specDigest(properties)),
                            TemplateSnapshots.digests(engine.compiledTransformerSource(channelId, metadataId, response)));
                } finally { cache.removeCompiledScript(id); }
            }
            assertNull(engine.compiledTransformerSource(channelId, 1, false));
            String fake = "// " + wrapper.replace("\n", " ") + "\nvar text = "
                    + com.mirth.connect.plugins.python.PythonApi.mapper().valueToTree(wrapper).toString() + ";";
            assertTrue(TemplateSnapshots.digests(fake).isEmpty());
        } finally { Context.exit(); }
    }

    @Test void templateChangesInEveryPhaseAndRemovedTemplatesRejectBeforeAnyLeaseOrPendingBinding() throws Exception {
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            var deployment = PythonRuntimeIntegrationTest.deployment(UUID.randomUUID().toString(), fixture.profileId, "step");
            var a = new PythonCodeTemplateProperties(); a.setEnvironmentProfileId(fixture.profileId);
            var b = a.clone(); b.setPythonScript("result = 'B'\n");
            var library = PythonCodeTemplateTest.library(PythonCodeTemplateTest.template(b), deployment.model().getId());
            when(fixture.runtime.engine.codeTemplateLibraries()).thenReturn(List.of(library));
            int leases = fixture.runtime.artifacts.activeLeases();
            for (String phase : List.of(PythonContract.ROLE_SOURCE_TRANSFORMER,
                    PythonContract.ROLE_DESTINATION_TRANSFORMER, PythonContract.ROLE_RESPONSE_TRANSFORMER)) {
                int metadataId = PythonContract.ROLE_SOURCE_TRANSFORMER.equals(phase) ? 0 : 1;
                boolean response = PythonContract.ROLE_RESPONSE_TRANSFORMER.equals(phase);
                when(fixture.runtime.engine.compiledTransformerSource(deployment.model().getId(), metadataId, response)).thenReturn(a.getCode());
                var failure = assertThrows(Deployments.DeployFailure.class, () -> fixture.runtime.deployments.prepare(deployment.model()));
                assertTrue(failure.getMessage().contains("Python functions changed"), failure.getMessage());
                assertFalse(fixture.runtime.deployments.hasPending(deployment.model().getId()));
                assertEquals(leases, fixture.runtime.artifacts.activeLeases());
                when(fixture.runtime.engine.compiledTransformerSource(deployment.model().getId(), metadataId, response)).thenReturn(null);
            }
            // Removing the library entirely must still inspect the compiled wrappers.
            when(fixture.runtime.engine.codeTemplateLibraries()).thenReturn(List.of());
            when(fixture.runtime.engine.compiledTransformerSource(deployment.model().getId(), 0, false)).thenReturn(a.getCode());
            assertThrows(Deployments.DeployFailure.class, () -> fixture.runtime.deployments.prepare(deployment.model()));
            assertEquals(leases, fixture.runtime.artifacts.activeLeases());
            // A stable retry binds the same immutable digest the compiler used.
            when(fixture.runtime.engine.codeTemplateLibraries()).thenReturn(List.of(library));
            when(fixture.runtime.engine.compiledTransformerSource(deployment.model().getId(), 0, false)).thenReturn(b.getCode());
            var binding = fixture.runtime.deployments.prepare(deployment.model());
            assertTrue(binding.specs.values().stream().filter(spec -> spec.role.startsWith(PythonContract.ROLE_CODE_TEMPLATE))
                    .allMatch(spec -> spec.digest.equals(PythonContract.specDigest(b))));
        }
    }

    @Test void phaseAdditionContextRemovalAndDuplicateLibrariesUseTheSameSnapshotRulesAsBinding() {
        var model = PythonRuntimeIntegrationTest.deployment("channel", "profile", "test").model();
        var properties = new PythonCodeTemplateProperties();
        var template = PythonCodeTemplateTest.template(properties);
        var library = PythonCodeTemplateTest.library(template, model.getId());
        var engine = mock(EngineAccess.class);
        when(engine.compiledTransformerSource("channel", 1, true)).thenReturn(properties.getCode());
        var problems = new ArrayList<String>();
        TemplateSnapshots.check(model, Surfaces.templates(model, List.of(library, library)), engine, problems);
        assertTrue(problems.isEmpty(), problems.toString());
        template.getContextSet().remove(com.mirth.connect.model.codetemplates.ContextType.DESTINATION_RESPONSE_TRANSFORMER);
        TemplateSnapshots.check(model, Surfaces.templates(model, List.of(library)), engine, problems);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("response-transformer"));
        problems.clear();
        when(engine.compiledTransformerSource("channel", 1, true)).thenReturn(null);
        when(engine.compiledTransformerSource("channel", 0, false)).thenReturn("var message = 'no wrappers yet';");
        TemplateSnapshots.check(model, Surfaces.templates(model, List.of(library)), engine, problems);
        assertEquals(1, problems.size());
        model.getSourceConnector().setEnabled(false);
        problems.clear();
        TemplateSnapshots.check(model, Surfaces.templates(model, List.of(library)), engine, problems);
        assertTrue(problems.isEmpty(), problems.toString());
    }
}
