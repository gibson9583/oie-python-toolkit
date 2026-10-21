/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.model.message.Status;

class WriterResponseContractTest {
    private Map<String, Object> result(String status) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("data", "ack\n\u2603");
        result.put("statusMessage", "brief");
        result.put("error", "details");
        return result;
    }

    @Test void responseFieldsAndValidationReachNativeResponse() {
        for (String status : new String[]{"SENT", "ERROR", "QUEUED"}) {
            Response response = Connectors.writerResponse(result(status), true, true);
            assertEquals(Status.valueOf(status), response.getStatus());
            assertEquals("ack\n\u2603", response.getMessage());
            assertEquals("brief", response.getStatusMessage());
            assertEquals("details", response.getError());
            assertTrue(response.isValidate());
            assertNull(response.fixStatus(true));
        }
    }

    @Test void disabledQueueRejectsQueuedWithoutDiscardingResponseOrError() {
        Response response = Connectors.writerResponse(result("QUEUED"), false, false);
        assertEquals(Status.ERROR, response.getStatus());
        assertEquals("ack\n\u2603", response.getMessage());
        assertEquals("details", response.getError());
        assertTrue(response.getStatusMessage().contains("queue is disabled"));
        assertFalse(response.isValidate());
        Map<String, Object> empty = result("QUEUED");
        empty.put("error", null);
        response = Connectors.writerResponse(empty, false, false);
        assertEquals(response.getStatusMessage(), response.getError());
    }

    @Test void absentTextFieldsNormalizeToEmptyAndInvalidStatusesAreRejected() {
        Response response = Connectors.writerResponse(Map.of("status", "SENT"), false, false);
        assertEquals("", response.getMessage());
        assertEquals("", response.getStatusMessage());
        assertEquals("", response.getError());
        for (String status : new String[]{"FILTERED", "RECEIVED", "TRANSFORMED", "PENDING", "sent"}) {
            assertThrows(Wire.ValueException.class, () -> Connectors.writerResponse(result(status), true, false));
        }
        for (String field : new String[]{"data", "statusMessage", "error"}) {
            Map<String, Object> malformed = result("SENT");
            malformed.put(field, Map.of("invalid", true));
            assertThrows(Wire.ValueException.class, () -> Connectors.writerResponse(malformed, true, false));
        }
    }
}
