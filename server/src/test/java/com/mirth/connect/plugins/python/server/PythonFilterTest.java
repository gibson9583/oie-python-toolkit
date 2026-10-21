/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.mirth.connect.plugins.python.PythonRule;
import com.mirth.connect.plugins.python.PythonContract;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.Connector;
import com.mirth.connect.model.Filter;

class PythonFilterTest {
    @Test void validatesBooleanBeforeAnyDocumentOrMapCommit() {
        Map<String, Object> channelMap = new LinkedHashMap<>();
        ScriptMaps maps = new ScriptMaps().add(ScriptMaps.CHANNEL, channelMap, true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("maps", Map.of("channelMap", Map.of("set", Map.of("seen", true), "delete", List.of())));
        for (Object invalid : List.of(0, 1, "true", List.of(), Map.of())) {
            result.put("accepted", invalid);
            assertThrows(Wire.ValueException.class, () -> TransformCall.validateFilter(result, "raw", null, maps));
            assertTrue(channelMap.isEmpty());
        }
        result.put("accepted", false);
        result.put("msg", Map.of("kind", "raw", "text", "updated"));
        StepResult validated = TransformCall.validateFilter(result, "raw", null, maps);
        assertFalse(validated.isAccepted());
        assertEquals("updated", validated.getMsgText());
        assertTrue(channelMap.isEmpty());
        validated.commit(); validated.commit();
        assertEquals(Map.of("seen", true), channelMap);
        result.put("response", Map.of("status", "SENT"));
        assertThrows(Wire.ValueException.class, () -> TransformCall.validateFilter(result, "raw", null, maps));
    }
    @Test void scansSourceDestinationDisabledAndDistinctFilterRoles() {
        Channel channel = new Channel();
        Connector source = new Connector(); source.setEnabled(true); source.setFilter(new Filter());
        Connector destination = new Connector(); destination.setEnabled(false); destination.setMetaDataId(7); destination.setFilter(new Filter());
        PythonRule a = new PythonRule(); a.setEnabled(true); source.getFilter().getElements().add(a);
        PythonRule b = new PythonRule(); b.setEnabled(true); destination.getFilter().getElements().add(b);
        channel.setSourceConnector(source); channel.getDestinationConnectors().add(destination);
        var surfaces = Surfaces.of(channel);
        assertEquals(2, surfaces.size());
        assertEquals(PythonContract.ROLE_SOURCE_FILTER, surfaces.get(0).role);
        assertTrue(surfaces.get(0).enabled);
        assertEquals(PythonContract.ROLE_DESTINATION_FILTER, surfaces.get(1).role);
        assertFalse(surfaces.get(1).enabled);
        assertEquals(7, surfaces.get(1).metaDataId);
    }
    @Test void sampleReturnsPredicateAndValidatesBooleanWithoutApplyingLiveMaps() {
        SampleContext sample = SampleContext.build(PythonContract.ROLE_SOURCE_FILTER,
                Map.of("msg", Map.of("kind", "raw", "text", "sample")), 100);
        assertEquals("filter", sample.context.get("role"));
        assertEquals(false, sample.output(Map.of("accepted", false, "maps", Map.of())).get("accepted"));
        assertThrows(Wire.ValueException.class, () -> sample.output(Map.of("accepted", 1)));
    }
    @Test void compiledSnapshotRecognizesActualFilterCallAndIgnoresComments() {
        PythonRule rule = new PythonRule();
        assertEquals(Set.of(PythonContract.specDigest(rule)), TemplateSnapshots.filterDigests("function filterRule0() {" + rule.getScript(true) + "}"));
        String digest = PythonContract.specDigest(rule);
        String combined = "function filterRule0() {" + rule.getScript(true) + "}\n" + PythonContract.stepWrapper(digest)
                + "\nPackages.com.mirth.connect.plugins.python.server.PythonBridge.executeTemplate('" + digest + "');";
        assertEquals(Set.of(digest), TemplateSnapshots.digests(combined));
        assertEquals(Set.of(digest), TemplateSnapshots.filterDigests(combined));
        assertTrue(TemplateSnapshots.filterDigests("// __py.executeFilter('" + PythonContract.specDigest(rule) + "');").isEmpty());
    }
}
