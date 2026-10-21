/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.ContentType;
import com.mirth.connect.donkey.model.message.MessageContent;
import com.mirth.connect.donkey.model.message.RawMessage;
import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.model.message.Status;

class ConnectorContractTest {
    static Map<String, Object> message(Object payload, Map<String, Object> map, Object ids) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("payload", Wire.encodeTopLevel(payload, "message", "payload"));
        result.put("sourceMap", Wire.encodeMap(map, "sourceMap"));
        result.put("destinationIds", ids);
        return result;
    }
    @Test void readerPreservesAbsentEmptyAndSubsetDestinationsAndEmptyPayloads() {
        var wire = List.of(message("", Map.of(), null), message(new byte[0], Map.of("v", 42L), List.of()), message("data", Map.of(), List.of(2)));
        List<RawMessage> messages = Connectors.readerMessages(Map.of("messages", wire), Set.of(1, 2), 1000, false);
        assertEquals(3, messages.size());
        assertNull(messages.get(0).getDestinationMetaDataIds());
        assertEquals("", messages.get(0).getRawData());
        assertTrue(messages.get(1).isBinary());
        assertEquals(0, messages.get(1).getRawBytes().length);
        assertEquals(List.of(), new ArrayList<>(messages.get(1).getDestinationMetaDataIds()));
        assertEquals(List.of(2), new ArrayList<>(messages.get(2).getDestinationMetaDataIds()));
        assertEquals(42L, ((Number) messages.get(1).getSourceMap().get("v")).longValue());
    }
    @Test void readerValidatesLaterEnvelopesBeforeReturningAnyAdmittableList() {
        for (String key : SourceMapPolicy.RESERVED) {
            Map<String, Object> result = Map.of("messages", List.of(message("first", Map.of(), null), message("bad", Map.of(key, "owned"), null)));
            assertThrows(Wire.ValueException.class, () -> Connectors.readerMessages(result, Set.of(1), 1000, false), key);
        }
        assertThrows(Wire.ValueException.class, () -> Connectors.readerMessages(Map.of("messages", List.of(message("first", Map.of(), null), message(new byte[]{1}, Map.of(), null))), Set.of(), 1000, true));
        assertThrows(Wire.ValueException.class, () -> Connectors.readerMessages(Map.of("messages", List.of(message("bad", Map.of(), List.of(5)))), Set.of(1), 1000, false));
        assertThrows(Wire.ValueException.class, () -> Connectors.readerMessages(Map.of("messages", List.of(message("bad", Map.of(), List.of(1.5)))), Set.of(1), 1000, false));
        assertThrows(Wire.ValueException.class, () -> Connectors.readerMessages(Map.of("messages", List.of(message("bad", Map.of(), List.of("1")))), Set.of(1), 1000, false));
    }
    @Test void readerRejectsUnboundedOrMalformedResults() {
        assertEquals(List.of(), Connectors.readerMessages(Map.of("messages", List.of()), Set.of(), 1, false));
        assertThrows(Wire.ValueException.class, () -> Connectors.readerMessages(Map.of("messages", List.of(message("a", Map.of(), null), message("b", Map.of(), null))), Set.of(), 1, false));
        assertThrows(Wire.ValueException.class, () -> Connectors.readerMessages(Map.of("messages", List.of("bare string")), Set.of(), 1, false));
        assertThrows(Wire.ValueException.class, () -> Connectors.readerMessages(Map.of("messages", List.of(message(1, Map.of(), null))), Set.of(), 1, false));
    }
    @Test void writerPreservesStatusesAndNormalizesQueuedOnlyWhenDisabled() {
        for (String status : List.of("SENT", "ERROR", "QUEUED")) {
            Map<String, Object> wire = Map.of("status", status, "data", "data", "statusMessage", "status", "error", "error");
            Response response = Connectors.writerResponse(wire, true, true);
            assertEquals(Status.valueOf(status), response.getStatus());
            assertEquals("data", response.getMessage());
            assertEquals("status", response.getStatusMessage());
            assertEquals("error", response.getError());
            assertTrue(response.isValidate());
        }
        Response normalized = Connectors.writerResponse(Map.of("status", "QUEUED", "data", "accepted"), false, false);
        assertEquals(Status.ERROR, normalized.getStatus());
        assertTrue(normalized.getStatusMessage().contains("queue is disabled"));
        assertEquals("accepted", normalized.getMessage());
        assertFalse(normalized.isValidate());
    }
    @Test void writerRejectsNonStringResultsAndUnsupportedStatuses() {
        assertThrows(Wire.ValueException.class, () -> Connectors.writerResponse(Map.of("status", "SENT", "data", Map.of()), true, false));
        assertThrows(Wire.ValueException.class, () -> Connectors.writerResponse(Map.of("status", "SENT", "data", new byte[0]), true, false));
        assertThrows(Wire.ValueException.class, () -> Connectors.writerResponse(Map.of("status", "PENDING"), true, false));
        assertThrows(Wire.ValueException.class, () -> Connectors.writerResponse(null, true, false));
    }
    @Test void writerPayloadPreservesEmptyVersusAbsent() {
        ConnectorMessage message = new ConnectorMessage();
        assertNull(Connectors.payload(message));
        message.setRaw(content(ContentType.RAW, "raw"));
        assertEquals("raw", Connectors.payload(message));
        message.setTransformed(content(ContentType.TRANSFORMED, ""));
        assertEquals("", Connectors.payload(message));
        message.setEncoded(content(ContentType.ENCODED, "encoded"));
        assertEquals("encoded", Connectors.payload(message));
        message.setEncoded(content(ContentType.ENCODED, ""));
        assertEquals("", Connectors.payload(message));
    }
    static MessageContent content(ContentType type, String value) { return new MessageContent("channel", 1L, 1, type, value, "Raw", false); }
}
