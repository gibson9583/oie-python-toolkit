/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.mirth.connect.connectors.python.PythonReceiverProperties;
import com.mirth.connect.donkey.model.event.ConnectionStatusEventType;
import com.mirth.connect.donkey.model.event.ErrorEventType;
import com.mirth.connect.donkey.model.message.BatchRawMessage;
import com.mirth.connect.donkey.model.message.RawMessage;
import com.mirth.connect.donkey.server.ConnectorTaskException;
import com.mirth.connect.donkey.server.channel.DispatchResult;
import com.mirth.connect.donkey.server.channel.PollConnector;
import com.mirth.connect.donkey.server.event.ConnectionStatusEvent;
import com.mirth.connect.donkey.server.event.ErrorEvent;
import com.mirth.connect.donkey.server.message.batch.BatchMessageReader;
import com.mirth.connect.donkey.server.message.batch.SimpleResponseHandler;
import com.mirth.connect.plugins.python.PythonContract;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EventController;

/** Polling adapter. The worker is released before any synchronous engine admission. */
public class PythonReceiver extends PollConnector {
    private static final Logger logger = LogManager.getLogger(PythonReceiver.class);
    private String digest;
    private static final class Progress { int admitted; int finalized; int envelopes; int total; }
    private final ThreadLocal<Progress> progress = new ThreadLocal<>();
    protected EventController events() { return ControllerFactory.getFactory().createEventController(); }
    protected PythonRuntime runtime() { return PythonRuntime.require(); }

    @Override public void onDeploy() throws ConnectorTaskException {
        if (!(getConnectorProperties() instanceof PythonReceiverProperties)) throw new ConnectorTaskException("Python Reader properties are missing");
        digest = PythonContract.specDigest((PythonReceiverProperties) getConnectorProperties());
        try { runtime().deployments.attach(getChannelId(), getChannel()); }
        catch (Deployments.NotAdmitted failure) { throw new ConnectorTaskException(failure.getMessage(), failure); }
        status(ConnectionStatusEventType.IDLE);
    }
    @Override public void onUndeploy() {
        PythonRuntime current = PythonRuntime.current();
        if (current != null) current.deployments.retire(getChannel(), "connector undeployed");
    }
    @Override public void onStart() throws ConnectorTaskException {
        try { runtime().deployments.requireActive(getChannel()); }
        catch (Deployments.NotAdmitted failure) { throw new ConnectorTaskException(failure.getMessage(), failure); }
    }
    @Override public void onStop() {}
    @Override public void onHalt() {}
    @Override public void handleRecoveredResponse(DispatchResult result) { finishDispatch(result); }

    protected List<RawMessage> readMessages() throws Exception {
        PythonRuntime runtime = runtime();
        Set<Integer> destinations = Connectors.destinationIds(getChannel());
        try (Deployments.Admission admission = runtime.deployments.admit(getChannelId(), getMetaDataId(), PythonContract.ROLE_READER, digest, getChannel())) {
            ScriptMaps maps = new ScriptMaps().shared(runtime.engine, getChannelId());
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("role", "read");
            context.put("phase", PythonContract.ROLE_READER);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("channel_id", getChannelId());
            metadata.put("channel_name", getChannel().getName());
            metadata.put("connector_name", getSourceName());
            metadata.put("metadata_id", Wire.encodeTopLevel(getMetaDataId(), "message", "metadata_id"));
            context.put("message", metadata);
            context.put("poll", Map.of("id", UUID.randomUUID().toString()));
            context.put("maps", maps.wire());
            context.put("maxMessages", runtime.config.maxReaderMessages);
            context.put("destinationIds", destinations);
            Executor.Outcome outcome = runtime.executor.invoke(admission, context);
            if (!outcome.ok()) throw new Wire.ValueException(PythonRuntime.describeError(admission.spec.label, outcome.error()));
            List<RawMessage> messages = Connectors.readerMessages(outcome.result(), destinations, runtime.config.maxReaderMessages, isProcessBatch());
            List<ScriptMaps.Operation> changes = maps.parse(outcome.result().get("maps"));
            for (ScriptMaps.Operation change : changes) change.apply();
            return messages;
        }
    }

    @Override public void poll() throws InterruptedException {
        status(ConnectionStatusEventType.READING);
        Progress counts = new Progress();
        progress.set(counts);
        try {
            List<RawMessage> messages = readMessages();
            counts.total = messages.size();
            for (RawMessage raw : messages) {
                if (isTerminated() || Thread.currentThread().isInterrupted()) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Python Reader poll interrupted");
                    return;
                }
                if (isProcessBatch()) {
                    BatchRawMessage batch = new BatchRawMessage(new BatchMessageReader(raw.getRawData()), raw.getSourceMap());
                    SimpleResponseHandler handler = new SimpleResponseHandler() {
                        @Override public void setDispatchResult(DispatchResult result) {
                            super.setDispatchResult(result);
                            if (result != null) counts.admitted++;
                        }
                        @Override public void responseProcess(int sequence, boolean complete) throws Exception {
                            checkDispatch(getDispatchResult());
                        }
                    };
                    Boolean processed = dispatchBatchMessage(batch, handler, raw.getDestinationMetaDataIds());
                    if (processed == null) return;
                    // false means a valid empty batch, rather than a failed batch.
                    counts.envelopes++;
                } else {
                    DispatchResult result = null;
                    try {
                        result = dispatchRawMessage(raw);
                        if (result == null) throw new IllegalStateException("The engine returned no dispatch result");
                        counts.admitted++;
                        checkDispatch(result);
                    } finally {
                        finishDispatch(result);
                    }
                    counts.envelopes++;
                }
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            String diagnostic = "Python Reader poll failed after " + counts.admitted + " messages admitted and " + counts.finalized
                    + " finalized (" + counts.envelopes + " of " + counts.total + " returned envelopes completed). Earlier admissions remain; this poll is not retried. " + bounded(e.getMessage());
            events().dispatchEvent(new ErrorEvent(getChannelId(), getMetaDataId(), null, ErrorEventType.SOURCE_CONNECTOR,
                    getSourceName(), PythonReceiverProperties.NAME, diagnostic, e));
            logger.error(diagnostic, e);
        } finally {
            progress.remove();
            status(ConnectionStatusEventType.IDLE);
        }
    }
    @Override public void finishDispatch(DispatchResult result) {
        super.finishDispatch(result);
        Progress counts = progress.get();
        if (counts != null && result != null) counts.finalized++;
    }
    private static void checkDispatch(DispatchResult result) throws Exception {
        if (result == null) throw new IllegalStateException("The engine returned no dispatch result");
        if (result.getChannelException() != null) throw result.getChannelException();
    }
    private void status(ConnectionStatusEventType status) {
        events().dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(), getSourceName(), status));
    }
    static String bounded(String text) { return text == null ? "Unknown error" : text.substring(0, Math.min(8192, text.length())); }
}
