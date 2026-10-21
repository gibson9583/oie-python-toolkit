/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.mirth.connect.donkey.model.message.RawMessage;
import com.mirth.connect.donkey.server.channel.ChannelException;
import com.mirth.connect.donkey.server.channel.DispatchResult;

class ReaderRawMessageTest {
    @Test void pythonEnvelopeMapsToTheSameFieldsAsTheStockScriptRawMessage() {
        Map<String, Object> metadata = Map.of("origin", "reader", "nested", Map.of("id", 1L << 40), "bytes", new byte[]{0, -1});
        for (Object payload : List.of("", "text", new byte[0], new byte[]{0, -1})) {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("payload", Wire.encodeTopLevel(payload, "message", "payload"));
            envelope.put("sourceMap", Wire.encodeMap(metadata, "sourceMap"));
            envelope.put("destinationIds", List.of(2, 2));
            RawMessage converted = Connectors.readerMessages(Map.of("messages", List.of(envelope)), Set.of(1, 2), 10, false).get(0);
            com.mirth.connect.server.userutil.RawMessage stock = payload instanceof byte[]
                    ? new com.mirth.connect.server.userutil.RawMessage((byte[]) payload, List.of(2, 2), metadata)
                    : new com.mirth.connect.server.userutil.RawMessage((String) payload, List.of(2, 2), metadata);
            assertEquals(stock.isBinary(), converted.isBinary());
            assertEquals(stock.getRawData(), converted.getRawData());
            assertArrayEquals(stock.getRawBytes(), converted.getRawBytes());
            assertEquals(new ArrayList<>(stock.getDestinationMetaDataIds()), new ArrayList<>(converted.getDestinationMetaDataIds()));
            assertEquals(stock.getSourceMap().get("nested"), converted.getSourceMap().get("nested"));
            assertArrayEquals((byte[]) stock.getSourceMap().get("bytes"), (byte[]) converted.getSourceMap().get("bytes"));
        }
    }

    @Test void returnedEngineFailureFinalizesOnceAndDoesNotRetryOrAdmitLaterEnvelopes() throws Exception {
        DispatchResult failed = mock(DispatchResult.class);
        when(failed.getChannelException()).thenReturn(new ChannelException(false, null, "engine admission failed"));
        ConnectorAdapterTest.Reader reader = new ConnectorAdapterTest.Reader() {
            @Override public DispatchResult dispatchRawMessage(RawMessage message) {
                calls.add("dispatch:" + message.getRawData());
                return failed;
            }
        };
        reader.messages = List.of(new RawMessage("first"), new RawMessage("second"));
        reader.poll();
        assertEquals(List.of("python-released", "dispatch:first", "finish"), reader.calls);
        verify(reader.eventController, times(1)).dispatchEvent(any(com.mirth.connect.donkey.server.event.ErrorEvent.class));
    }
}
