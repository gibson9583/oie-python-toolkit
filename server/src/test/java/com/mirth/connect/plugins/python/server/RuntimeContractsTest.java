/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeContractsTest {
    @TempDir Path temp;

    private static ByteArrayInputStream frame(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return new ByteArrayInputStream(ByteBuffer.allocate(4 + bytes.length).putInt(bytes.length).put(bytes).array());
    }

    @Test void protocolRejectsAmbiguousOrTruncatedFrames() {
        for (String bad : List.of("{} {}", "{\"id\":\"a\",\"id\":\"b\"}", "[]", "{\"n\":NaN}")) {
            assertThrows(Wire.ProtocolException.class, () -> Wire.readFrame(frame(bad), 1024));
        }
        assertThrows(Wire.ProtocolException.class, () -> Wire.readFrame(new ByteArrayInputStream(new byte[]{0, 0}), 1024));
        assertThrows(Wire.ProtocolException.class, () -> Wire.readFrame(frame("{}"), 1));
    }

    @Test void exactIntegersBytesAndReservedTagMapsRoundTrip() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("$t", "user-value");
        value.put("integer", Long.MAX_VALUE);
        value.put("bytes", new byte[]{0, -1});
        Object encoded = Wire.encodeTopLevel(value, "channelMap", "x");
        @SuppressWarnings("unchecked") Map<String, Object> decoded = (Map<String, Object>) Wire.decode(encoded, "x", null, 0);
        assertEquals(Long.MAX_VALUE, decoded.get("integer"));
        assertArrayEquals(new byte[]{0, -1}, (byte[]) decoded.get("bytes"));
        assertEquals("user-value", decoded.get("$t"));
        for (Object bad : List.of(Map.of("$t", "int", "v", "9223372036854775808"), Map.of("$t", "int", "v", 1),
                Map.of("$t", "bytes"), Map.of("$t", "bytes", "v", "%%%"), 9007199254740992L)) {
            assertThrows(Wire.ValueException.class, () -> Wire.decode(bad, "x", null, 0));
        }
    }

    @Test void unsupportedValuesRemainAvailableAndMalformedDeltaNeverMutatesMaps() {
        Object original = new Object();
        Map<String, Object> live = new LinkedHashMap<>(Map.of("object", original, "safe", "before"));
        ScriptMaps maps = new ScriptMaps().add(ScriptMaps.CHANNEL, live, true).add(ScriptMaps.SOURCE, Map.of("r", 1), false);
        Object sentinel = Wire.encodeTopLevel(original, ScriptMaps.CHANNEL, "object");
        List<ScriptMaps.Operation> changes = maps.parse(Map.of(ScriptMaps.CHANNEL,
                Map.of("set", Map.of("copy", sentinel), "delete", List.of("safe"))));
        assertEquals("before", live.get("safe"));
        changes.forEach(ScriptMaps.Operation::apply);
        assertSame(original, live.get("copy"));
        assertFalse(live.containsKey("safe"));
        for (Object delta : List.of(Map.of(ScriptMaps.CHANNEL, Map.of("set", "bad", "delete", List.of())),
                Map.of(ScriptMaps.CHANNEL, Map.of("set", Map.of(), "delete", List.of(5))),
                Map.of(ScriptMaps.CHANNEL, Map.of("set", Map.of("copy", "x"), "delete", List.of("copy"))),
                Map.of(ScriptMaps.SOURCE, Map.of("set", Map.of("r", "x"), "delete", List.of())))) {
            assertThrows(Wire.ValueException.class, () -> maps.parse(delta));
            assertSame(original, live.get("copy"));
        }
    }

    @Test void transformerValidatesEntireDocumentBeforeCommittingMapChanges() {
        Map<String, Object> live = new LinkedHashMap<>(Map.of("n", "before"));
        ScriptMaps maps = new ScriptMaps().add(ScriptMaps.CHANNEL, live, true);
        Map<String, Object> delta = Map.of(ScriptMaps.CHANNEL, Map.of("set", Map.of("n", "after"), "delete", List.of()));
        for (Map<String, String> bad : List.of(Map.of("kind", "json", "text", "5"),
                Map.of("kind", "json", "text", "{} {}"), Map.of("kind", "json", "text", "{\"unsafe\":9007199254740993}"), Map.of("kind", "raw", "text", "x"))) {
            assertThrows(Wire.ValueException.class, () -> TransformCall.validate(Map.of("msg", bad, "maps", delta), "json", null, false, maps));
            assertEquals("before", live.get("n"));
        }
        assertThrows(Wire.ValueException.class, () -> TransformCall.validate(Map.of("msg", Map.of("kind", "xml", "text", "<!DOCTYPE a SYSTEM 'file:///etc/passwd'><a/>"), "maps", delta), "xml", null, false, maps));
        StepResult valid = TransformCall.validate(Map.of("msg", Map.of("kind", "json", "text", "{\"a\":1}"), "maps", delta), "json", null, false, maps);
        assertEquals("before", live.get("n"));
        valid.commit();
        valid.commit();
        assertEquals("after", live.get("n"));
    }

    @Test void validationCannotClaimSuccessWithMissingStaleOrMalformedResults() throws Exception {
        List<Map<String, Object>> request = List.of(Map.of("id", "step", "role", "transform"));
        Map<String, Object> valid = Map.of("id", "step", "role", "transform", "ok", true, "diagnostics", List.of());
        assertEquals(List.of(valid), PythonRuntime.checkValidationReply(request, Map.of("items", List.of(valid))));
        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("items", List.of()),
                Map.of("items", List.of(Map.of("id", "old", "role", "transform", "ok", true, "diagnostics", List.of()))),
                Map.of("items", List.of(Map.of("id", "step", "role", "write", "ok", true, "diagnostics", List.of()))),
                Map.of("items", List.of(Map.of("id", "step", "role", "transform", "ok", "true", "diagnostics", List.of()))),
                Map.of("items", List.of(Map.of("id", "step", "role", "transform", "ok", false, "diagnostics", List.of()))),
                Map.of("items", List.of(Map.of("id", "step", "role", "transform", "ok", true, "diagnostics", List.of(Map.of("code", "BAD", "message", "bad"))))))) {
            assertThrows(IOException.class, () -> PythonRuntime.checkValidationReply(request, bad));
        }
    }

    @Test void artifactManifestCannotSelectPathsOutsideOwnedBuildsAndGcHonorsLeases() throws Exception {
        ArtifactStore store = new ArtifactStore(temp);
        String digest = "a".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> store.buildDir(digest, "../../outside"));
        Path build = store.newBuildDir(digest);
        Path python = ProcessControl.venvPython(build.resolve("venv"));
        Files.createDirectories(python.getParent());
        Files.writeString(python, "fixture");
        ArtifactStore.Manifest manifest = new ArtifactStore.Manifest();
        manifest.artifactDigest = digest;
        manifest.buildId = build.getFileName().toString();
        store.publish(manifest);
        ArtifactStore.Lease lease = store.acquire(digest);
        assertNotNull(lease);
        assertTrue(store.collect(java.util.Set.of(), store.collectionGeneration()).isEmpty());
        lease.release();
        lease.release();
        assertEquals(0, store.activeLeases());
        assertEquals(List.of(digest), store.collect(java.util.Set.of(), store.collectionGeneration()));
        assertFalse(Files.exists(build));
    }
}
