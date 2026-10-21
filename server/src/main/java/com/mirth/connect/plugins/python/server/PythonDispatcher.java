/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.mirth.connect.connectors.python.PythonDispatcherProperties;
import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.model.event.ConnectionStatusEventType;
import com.mirth.connect.donkey.model.event.ErrorEventType;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.ConnectorTaskException;
import com.mirth.connect.donkey.server.channel.DestinationConnector;
import com.mirth.connect.donkey.server.event.ConnectionStatusEvent;
import com.mirth.connect.donkey.server.event.ErrorEvent;
import com.mirth.connect.plugins.python.PythonContract;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EventController;

/** Uses current deployed executable identity even when effective Sent properties are older. */
public class PythonDispatcher extends DestinationConnector {
    private static final Logger logger = LogManager.getLogger(PythonDispatcher.class);
    private String digest;
    protected EventController events() { return ControllerFactory.getFactory().createEventController(); }
    protected PythonRuntime runtime() { return PythonRuntime.require(); }

    @Override public void onDeploy() throws ConnectorTaskException {
        if (!(getConnectorProperties() instanceof PythonDispatcherProperties)) throw new ConnectorTaskException("Python Writer properties are missing");
        digest = PythonContract.specDigest((PythonDispatcherProperties) getConnectorProperties());
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
    @Override public void replaceConnectorProperties(ConnectorProperties properties, ConnectorMessage message) {}

    @SuppressWarnings("unchecked")
    protected Response write(ConnectorMessage message, boolean validate) throws Exception {
        PythonRuntime runtime = runtime();
        try (Deployments.Admission admission = runtime.deployments.admit(getChannelId(), getMetaDataId(), PythonContract.ROLE_WRITER, digest, getChannel())) {
            ScriptMaps maps = new ScriptMaps().add(ScriptMaps.CHANNEL, message.getChannelMap(), true)
                    .add(ScriptMaps.CONNECTOR, message.getConnectorMap(), true).add(ScriptMaps.SOURCE, message.getSourceMap(), false)
                    .add(ScriptMaps.RESPONSE, message.getResponseMap(), true)
                    .responseDestinations(getDestinationIdMap()).shared(runtime.engine, getChannelId());
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("role", "write");
            context.put("phase", PythonContract.ROLE_WRITER);
            context.put("payload", Connectors.payload(message));
            context.put("maps", maps.wire());
            context.put("message", Connectors.metadata(message));
            Executor.Outcome outcome = runtime.executor.invoke(admission, context);
            if (!outcome.ok()) throw new Wire.ValueException(PythonRuntime.describeError(admission.spec.label, outcome.error()));
            // Validate the response and every map operation before applying the first change.
            Map<String, Object> result = outcome.result();
            if (result == null || !(result.get("response") instanceof Map)) throw new Wire.ValueException("Writer response is malformed");
            Response response = Connectors.writerResponse((Map<String, Object>) result.get("response"), isQueueEnabled(), validate);
            List<ScriptMaps.Operation> changes = maps.parse(result.get("maps"));
            for (ScriptMaps.Operation change : changes) change.apply();
            return response;
        }
    }

    @Override public Response send(ConnectorProperties effectiveProperties, ConnectorMessage message) throws InterruptedException {
        status(ConnectionStatusEventType.SENDING);
        boolean validate = false;
        try {
            if (!(effectiveProperties instanceof PythonDispatcherProperties)) throw new Wire.ValueException("Stored Sent properties are incompatible with Python Writer");
            validate = ((PythonDispatcherProperties) effectiveProperties).getDestinationConnectorProperties().isValidateResponse();
            return write(message, validate);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            String diagnostic = PythonReceiver.bounded(e.getMessage());
            events().dispatchEvent(new ErrorEvent(getChannelId(), getMetaDataId(), message.getMessageId(), ErrorEventType.DESTINATION_CONNECTOR,
                    getDestinationName(), PythonDispatcherProperties.NAME, diagnostic, e));
            logger.error("Python Writer failed for channel " + getChannelId() + " destination " + getMetaDataId() + ": " + diagnostic, e);
            return new Response(Status.ERROR, "", diagnostic, diagnostic, validate);
        } finally {
            status(ConnectionStatusEventType.IDLE);
        }
    }
    private void status(ConnectionStatusEventType status) {
        events().dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(), getDestinationName(), status));
    }
}
