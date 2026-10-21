/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.mirth.connect.connectors.python.PythonDispatcherProperties;
import com.mirth.connect.connectors.python.PythonReceiverProperties;
import com.mirth.connect.donkey.model.message.BatchRawMessage;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.RawMessage;
import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.channel.DispatchResult;
import com.mirth.connect.donkey.server.message.batch.ResponseHandler;
import com.mirth.connect.server.controllers.EventController;

class ConnectorAdapterTest {
    static class Reader extends PythonReceiver {
        final EventController eventController = mock(EventController.class);
        List<RawMessage> messages = List.of();
        final List<String> calls = new ArrayList<>();
        final List<Collection<Integer>> destinationSets = new ArrayList<>();
        boolean batch;
        boolean terminated;
        int failAt = -1;
        @Override protected EventController events() { return eventController; }
        @Override protected List<RawMessage> readMessages() throws InterruptedException { calls.add("python-released"); return messages; }
        @Override public boolean isTerminated() { return terminated; }
        @Override public boolean isProcessBatch() { return batch; }
        @Override public DispatchResult dispatchRawMessage(RawMessage message) {
            calls.add("dispatch");
            if (calls.stream().filter("dispatch"::equals).count() == failAt) throw new IllegalStateException("admission failed");
            return mock(DispatchResult.class);
        }
        @Override public void finishDispatch(DispatchResult result) { calls.add("finish"); }
        @Override public Boolean dispatchBatchMessage(BatchRawMessage message, ResponseHandler handler, Collection<Integer> ids) {
            calls.add("batch"); destinationSets.add(ids); return false;
        }
    }
    @Test void readerFinalizesFailedDispatchAndStopsBeforeLaterEnvelopes() throws Exception {
        Reader reader = new Reader();
        reader.messages = List.of(new RawMessage("first"), new RawMessage("second"), new RawMessage("third"));
        reader.failAt = 2;
        reader.poll();
        assertEquals(List.of("python-released", "dispatch", "finish", "dispatch", "finish"), reader.calls);
        verify(reader.eventController, atLeastOnce()).dispatchEvent(any(com.mirth.connect.donkey.server.event.ErrorEvent.class));
    }
    @Test void batchOverloadPreservesAllNoneAndSubsetSelectionAndAcceptsEmptyBatch() throws Exception {
        Reader reader = new Reader(); reader.batch = true;
        reader.messages = List.of(new RawMessage("all", null, java.util.Map.of()), new RawMessage("none", List.of(), java.util.Map.of()),
                new RawMessage("subset", List.of(2), java.util.Map.of()));
        reader.poll();
        assertEquals(3, reader.destinationSets.size());
        assertNull(reader.destinationSets.get(0));
        assertTrue(reader.destinationSets.get(1).isEmpty());
        assertEquals(List.of(2), new ArrayList<>(reader.destinationSets.get(2)));
        verify(reader.eventController, never()).dispatchEvent(any(com.mirth.connect.donkey.server.event.ErrorEvent.class));
    }
    @Test void terminationBetweenEnvelopesPreventsAdmission() throws Exception {
        Reader reader = new Reader(); reader.terminated = true; reader.messages = List.of(new RawMessage("first"));
        reader.poll(); assertEquals(List.of("python-released"), reader.calls);
    }
    @Test void writerUsesEffectiveValidationAndPropagatesInterruption() throws Exception {
        EventController events = mock(EventController.class);
        PythonDispatcher writer = new PythonDispatcher() {
            @Override protected EventController events() { return events; }
            @Override protected Response write(ConnectorMessage message, boolean validate) throws InterruptedException {
                if (message.getMessageId() == 2) throw new InterruptedException("stopped");
                return new Response(Status.SENT, "current deployed script", "", "", validate);
            }
        };
        PythonDispatcherProperties deployed = new PythonDispatcherProperties(); deployed.setPythonScript("current source");
        writer.setConnectorProperties(deployed);
        PythonDispatcherProperties oldSent = new PythonDispatcherProperties(); oldSent.setPythonScript("old source");
        oldSent.getDestinationConnectorProperties().setValidateResponse(true);
        ConnectorMessage message = new ConnectorMessage(); message.setMessageId(1);
        Response response = writer.send(oldSent, message);
        assertTrue(response.isValidate());
        assertEquals("current source", ((PythonDispatcherProperties) writer.getConnectorProperties()).getPythonScript());
        assertEquals("current deployed script", response.getMessage());
        message.setMessageId(2);
        assertThrows(InterruptedException.class, () -> writer.send(oldSent, message));
    }
}
