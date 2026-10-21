/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class ScriptMapsTest {
    @Test void sampleReaderSourceMapIsSeededWithoutBecomingASharedMap() {
        Map<String, Object> source = new HashMap<>(Map.of("origin", "sample-origin"));
        SampleContext context = SampleContext.build("reader", Map.of("sourceMap", source), 10);
        assertFalse(context.context.containsKey("poll"));
        assertEquals(Map.of("pollId", "sample", "origin", "sample-origin"), context.context.get("sourceMap"));
        assertFalse(((Map<?, ?>) context.context.get("maps")).containsKey(ScriptMaps.SOURCE));
        assertEquals(Map.of("origin", "sample-origin"), source);
        assertThrows(Wire.ValueException.class,
                () -> SampleContext.build("reader", Map.of("sourceMap", Map.of("batchId", "reserved")), 10));
    }

    @Test void responseDestinationsUseTheStockMetadataIdKeyFormat() {
        ScriptMaps maps = new ScriptMaps().add(ScriptMaps.RESPONSE, Map.of("d1", "response"), true)
                .responseDestinations(Map.of("Destination:雪", 1));
        Map<?, ?> spec = (Map<?, ?>) maps.wire().get(ScriptMaps.RESPONSE);
        assertEquals(Map.of("Destination:雪", "d1"), spec.get("aliases"));
        assertEquals(Map.of("d1", "response"), spec.get("values"));
        for (Object invalid : List.of("1", 1.5, Double.NaN)) {
            assertThrows(Wire.ValueException.class, () -> maps.responseDestinations(Map.of("invalid", invalid)));
        }
    }

    @Test void fullSharedStoresDoNotDependOnSelectorsAndApplyOnlyChangedKeys() {
        EngineAccess engine = mock(EngineAccess.class);
        Map<String, Object> global = new ConcurrentHashMap<>(Map.of("existing", "before", "remove", "old"));
        Map<String, Object> channel = new ConcurrentHashMap<>(Map.of("unselected", "channel"));
        when(engine.globalMap()).thenReturn(global);
        when(engine.globalChannelMap("channel")).thenReturn(channel);
        doReturn(Map.of("$t", "user-key", "site", "local")).when(engine).configurationMap();
        ScriptMaps maps = new ScriptMaps().shared(engine, "channel");
        assertEquals(Map.of("existing", "before", "remove", "old"), ((Map<?, ?>) maps.wire().get(ScriptMaps.GLOBAL)).get("values"));
        assertEquals(Map.of("unselected", "channel"), ((Map<?, ?>) maps.wire().get(ScriptMaps.GLOBAL_CHANNEL)).get("values"));
        assertEquals(Map.of("$t", "user-key", "site", "local"), ((Map<?, ?>) maps.wire().get(ScriptMaps.CONFIGURATION)).get("values"));
        var changes = maps.parse(Map.of(ScriptMaps.GLOBAL, Map.of("set", Map.of("existing", "python"), "delete", List.of("remove")),
                ScriptMaps.GLOBAL_CHANNEL, Map.of("set", Map.of("created", "new"), "delete", List.of())));
        global.put("concurrent", "keep"); channel.put("unselected", "updated");
        changes.forEach(ScriptMaps.Operation::apply);
        assertEquals(Map.of("existing", "python", "concurrent", "keep"), global);
        assertEquals(Map.of("unselected", "updated", "created", "new"), channel);
    }

    @Test void invalidSharedChangesRejectTheWholeResultBeforeAnyWrite() {
        Map<String, Object> message = new HashMap<>(Map.of("keep", "before"));
        Map<String, Object> global = new ConcurrentHashMap<>(Map.of("keep", "global"));
        ScriptMaps maps = new ScriptMaps().add(ScriptMaps.CHANNEL, message, true)
                .add(ScriptMaps.GLOBAL, global, true).add(ScriptMaps.GLOBAL_CHANNEL, new ConcurrentHashMap<>(), true)
                .add(ScriptMaps.CONFIGURATION, Map.of("site", "local"), false);
        for (String name : List.of(ScriptMaps.GLOBAL, ScriptMaps.GLOBAL_CHANNEL, ScriptMaps.CONFIGURATION)) {
            Map<String, Object> sets = new HashMap<>(); sets.put("new", null);
            Map<String, Object> delta = new java.util.LinkedHashMap<>();
            delta.put(ScriptMaps.CHANNEL, Map.of("set", Map.of("keep", "partial"), "delete", List.of()));
            delta.put(name, Map.of("set", sets, "delete", List.of()));
            assertThrows(Wire.ValueException.class, () -> TransformCall.validate(Map.of("maps", delta), "raw", null, false, maps));
            assertEquals(Map.of("keep", "before"), message);
            assertEquals(Map.of("keep", "global"), global);
        }
    }

    @Test void unsupportedSharedValuesCanBePreservedWithoutSerializingJavaObjects() {
        Object opaque = new Object();
        Map<String, Object> global = new ConcurrentHashMap<>(Map.of("opaque", opaque));
        Map<String, Object> local = new HashMap<>();
        ScriptMaps maps = new ScriptMaps().add(ScriptMaps.GLOBAL, global, true).add(ScriptMaps.CHANNEL, local, true);
        Object value = ((Map<?, ?>) ((Map<?, ?>) maps.wire().get(ScriptMaps.GLOBAL)).get("values")).get("opaque");
        maps.parse(Map.of(ScriptMaps.CHANNEL, Map.of("set", Map.of("copy", value), "delete", List.of())))
                .forEach(ScriptMaps.Operation::apply);
        assertSame(opaque, local.get("copy")); assertSame(opaque, global.get("opaque"));
    }

    @Test void sampleSharedMapsAreCompleteAndRemainSynthetic() {
        Map<String, Object> global = new HashMap<>(Map.of("unselected", "sample"));
        for (String role : List.of("reader", "writer", "source-transformer")) {
            Map<String, Object> sample = new HashMap<>(); sample.put("globalMap", global);
            sample.put("configurationMap", Map.of("site", "sample-site"));
            sample.put("msg", Map.of("kind", "raw", "text", "sample"));
            SampleContext context = SampleContext.build(role, sample, 10);
            Map<?, ?> maps = (Map<?, ?>) context.context.get("maps");
            assertEquals(global, ((Map<?, ?>) maps.get(ScriptMaps.GLOBAL)).get("values"));
            Map<String, Object> result = new HashMap<>();
            result.put("maps", Map.of(ScriptMaps.GLOBAL, Map.of("set", Map.of("changed", "preview"), "delete", List.of())));
            result.put("messages", List.of());
            result.put("response", Map.of("status", "SENT"));
            if (role.equals("source-transformer")) { result.remove("response"); result.remove("messages"); }
            assertTrue(((Map<?, ?>) context.output(result).get("mapChanges")).containsKey(ScriptMaps.GLOBAL));
            assertEquals(Map.of("unselected", "sample"), global);
        }
    }
}
