/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mirth.connect.plugins.python.PythonContract;

/**
 * Builds a sample-test context from synthetic input and turns the script result
 * into a preview. Samples never touch live maps or messages, and the preview is
 * never applied.
 */
final class SampleContext {

    final Map<String, Object> context;
    private final String role;
    private final ScriptMaps maps;
    private final String msgKind;
    private final String tmpKind;

    private SampleContext(String role, Map<String, Object> context, ScriptMaps maps, String msgKind, String tmpKind) {
        this.role = role;
        this.context = context;
        this.maps = maps;
        this.msgKind = msgKind;
        this.tmpKind = tmpKind;
    }

    @SuppressWarnings("unchecked")
    static SampleContext build(String role, Map<String, Object> sample, int maxReaderMessages) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("channel_id", "sample");
        message.put("channel_name", "Sample test");
        message.put("connector_name", PythonContract.ROLE_READER.equals(role) ? "Source" : "Sample connector");
        message.put("message_id", Wire.encodeTopLevel(0L, "message", "message_id"));
        // Sample maps are always synthetic, including the shared stores.
        ScriptMaps maps = new ScriptMaps()
                .add(ScriptMaps.GLOBAL, plainMap(sample.get("globalMap")), true)
                .add(ScriptMaps.GLOBAL_CHANNEL, plainMap(sample.get("globalChannelMap")), true)
                .add(ScriptMaps.CONFIGURATION, plainMap(sample.get("configurationMap")), false);
        if (PythonContract.ROLE_READER.equals(role)) {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("role", "read");
            context.put("phase", role);
            context.put("message", message);
            context.put("maps", maps.wire());
            context.put("maxMessages", maxReaderMessages);
            Object ids = sample.get("destinationIds");
            context.put("destinationIds", ids instanceof List ? ids : new ArrayList<>());
            Map<String, Object> poll = new LinkedHashMap<>();
            poll.put("sample", true);
            context.put("poll", poll);
            return new SampleContext(role, context, maps, null, null);
        }
        maps.add(ScriptMaps.CHANNEL, plainMap(sample.get("channelMap")), true)
                .add(ScriptMaps.CONNECTOR, plainMap(sample.get("connectorMap")), true)
                .add(ScriptMaps.SOURCE, plainMap(sample.get("sourceMap")), false)
                .add(ScriptMaps.RESPONSE, plainMap(sample.get("responseMap")), true)
                .responseDestinations(plainMap(sample.get("destinationIdMap")));
        if (PythonContract.ROLE_WRITER.equals(role)) {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("role", "write");
            context.put("phase", role);
            Object payload = sample.get("payload");
            if (payload != null && !(payload instanceof String)) {
                throw new IllegalArgumentException("sampleContext.payload must be a string or null");
            }
            context.put("payload", payload);
            context.put("maps", maps.wire());
            context.put("message", message);
            return new SampleContext(role, context, maps, null, null);
        }
        Map<String, Object> msg = (Map<String, Object>) sample.get("msg");
        if (msg == null) {
            throw new IllegalArgumentException("sampleContext.msg is required for a transformer test");
        }
        String msgKind = String.valueOf(msg.get("kind"));
        Map<String, Object> tmp = (Map<String, Object>) sample.get("tmp");
        String tmpKind = tmp == null ? null : String.valueOf(tmp.get("kind"));
        Map<String, Object> response = null;
        if (PythonContract.ROLE_RESPONSE_TRANSFORMER.equals(role)) {
            Map<String, Object> r = sample.get("response") instanceof Map ? (Map<String, Object>) sample.get("response") : new LinkedHashMap<>();
            response = TransformCall.responseFields(r.getOrDefault("status", "SENT"), r.get("statusMessage"), r.get("error"));
        }
        Map<String, Object> context = TransformCall.context(role, msgKind, string(msg.get("text")), tmpKind, tmp == null ? null : string(tmp.get("text")),
                maps, message, response);
        if ("json".equals(msgKind)) {
            checkJsonRoot(string(msg.get("text")), "msg");
        }
        if ("json".equals(tmpKind)) {
            checkJsonRoot(string(tmp.get("text")), "tmp");
        }
        return new SampleContext(role, context, maps, msgKind, tmpKind);
    }

    private static void checkJsonRoot(String text, String name) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = Wire.MAPPER.readTree(text);
            if (node == null || !(node.isObject() || node.isArray())) {
                throw new IllegalArgumentException("Sample " + name + " must be a JSON object or array");
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Sample " + name + " is not valid JSON: " + e.getMessage());
        }
    }

    private static String string(Object value) {
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException("Sample text values must be strings");
        }
        return (String) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> plainMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Sample maps must be JSON objects");
        }
        return new LinkedHashMap<>((Map<String, Object>) value);
    }

    /** The preview of what the call would change. */
    @SuppressWarnings("unchecked")
    Map<String, Object> output(Map<String, Object> result) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (PythonContract.ROLE_READER.equals(role)) {
            List<Map<String, Object>> messages = new ArrayList<>();
            for (Object item : (List<Object>) result.getOrDefault("messages", new ArrayList<>())) {
                Map<String, Object> m = (Map<String, Object>) item;
                Object payload = Wire.decode(m.get("payload"), "payload", null, 0);
                Map<String, Object> preview = new LinkedHashMap<>();
                if (payload instanceof byte[]) {
                    preview.put("payloadKind", "bytes");
                    preview.put("payload", Base64.getEncoder().encodeToString((byte[]) payload));
                } else {
                    preview.put("payloadKind", "text");
                    preview.put("payload", payload);
                }
                Object sourceMap = Wire.decode(m.get("sourceMap"), "sourceMap", null, 0);
                Connectors.checkSourceMap((Map<String, Object>) sourceMap);
                preview.put("sourceMap", printable(sourceMap));
                preview.put("destinationIds", m.get("destinationIds"));
                messages.add(preview);
            }
            out.put("messages", messages);
            out.put("mapChanges", changes(maps.parse(result.get("maps"))));
            return out;
        }
        if (PythonContract.ROLE_WRITER.equals(role)) {
            Map<String, Object> response = (Map<String, Object>) result.get("response");
            out.put("response", Connectors.checkWriterResponse(response));
            out.put("mapChanges", changes(maps.parse(result.get("maps"))));
            return out;
        }
        StepResult step = PythonContract.isFilterRole(role) ? TransformCall.validateFilter(result, msgKind, tmpKind, maps)
                : TransformCall.validate(result, msgKind, tmpKind, PythonContract.ROLE_RESPONSE_TRANSFORMER.equals(role), maps);
        if (PythonContract.isFilterRole(role)) out.put("accepted", step.isAccepted());
        if (step.hasMsg()) {
            out.put("msg", doc(step.getMsgKind(), step.getMsgText()));
        }
        if (step.hasTmp()) {
            out.put("tmp", doc(step.getTmpKind(), step.getTmpText()));
        }
        if (step.hasResponse()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", step.getResponseStatus().name());
            response.put("statusMessage", step.getResponseStatusMessage());
            response.put("error", step.getResponseError());
            out.put("response", response);
        }
        out.put("mapChanges", changes(maps.parse(result.get("maps"))));
        return out;
    }

    private static Map<String, Object> doc(String kind, String text) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("kind", "xmllist".equals(kind) ? "xml" : kind);
        doc.put("text", text);
        return doc;
    }

    private static Map<String, Object> changes(List<ScriptMaps.Operation> operations) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ScriptMaps.Operation op : operations) {
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) out.computeIfAbsent(op.map, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("set", new LinkedHashMap<String, Object>());
                m.put("delete", new ArrayList<String>());
                return m;
            });
            if (op.delete) {
                @SuppressWarnings("unchecked")
                List<String> deletes = (List<String>) entry.get("delete");
                deletes.add(op.key);
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> sets = (Map<String, Object>) entry.get("set");
                sets.put(op.key, printable(op.value));
            }
        }
        return out;
    }

    /** Converts decoded values to JSON-friendly ones; bytes become {"base64": ...}. */
    @SuppressWarnings("unchecked")
    static Object printable(Object value) {
        if (value instanceof byte[]) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("base64", Base64.getEncoder().encodeToString((byte[]) value));
            return b;
        }
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
                out.put(e.getKey(), printable(e.getValue()));
            }
            return out;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<Object>) value) {
                out.add(printable(item));
            }
            return out;
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return String.valueOf(value);
    }
}
