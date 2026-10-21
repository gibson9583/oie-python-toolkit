/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.util.LinkedHashMap;
import java.util.Map;

import com.mirth.connect.plugins.python.PythonContract;
import com.mirth.connect.userutil.ImmutableConnectorMessage;

/**
 * Entry point for the JavaScript wrapper the engine compiles for each Python
 * step. Everything the call needs comes from the deployment binding; nothing
 * is read from saved channels or the current profile.
 */
public final class PythonBridge {

    private PythonBridge() {}

    /** A Python step failed. The message carries the Python diagnostic. */
    public static final class PythonStepException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public PythonStepException(String message) {
            super(message);
        }
    }

    public static StepResult executeStep(String spec, String phase, Object connectorMessage, String msgKind, String msgText, String tmpKind,
            String tmpText, Object channelMap, Object connectorMap, Object sourceMap, Object responseMap, Object responseStatus,
            Object responseStatusMessage, Object responseError) throws InterruptedException {
        return execute(spec, phase, connectorMessage, msgKind, msgText, tmpKind, tmpText, channelMap, connectorMap, sourceMap,
                responseMap, responseStatus, responseStatusMessage, responseError, false);
    }

    public static StepResult executeFilter(String spec, String phase, Object connectorMessage, String msgKind, String msgText, String tmpKind,
            String tmpText, Object channelMap, Object connectorMap, Object sourceMap, Object responseMap, Object responseStatus,
            Object responseStatusMessage, Object responseError) throws InterruptedException {
        if (!PythonContract.isFilterRole(phase)) throw new PythonStepException("Unknown filter phase " + phase);
        return execute(spec, phase, connectorMessage, msgKind, msgText, tmpKind, tmpText, channelMap, connectorMap, sourceMap,
                responseMap, null, null, null, true);
    }

    private static StepResult execute(String spec, String phase, Object connectorMessage, String msgKind, String msgText, String tmpKind,
            String tmpText, Object channelMap, Object connectorMap, Object sourceMap, Object responseMap, Object responseStatus,
            Object responseStatusMessage, Object responseError, boolean filter) throws InterruptedException {
        if (!(connectorMessage instanceof ImmutableConnectorMessage)) {
            throw new PythonStepException("Python steps run only inside a source, destination or response transformer");
        }
        ImmutableConnectorMessage message = (ImmutableConnectorMessage) connectorMessage;
        if (!filter && !PythonContract.ROLE_SOURCE_TRANSFORMER.equals(phase) && !PythonContract.ROLE_DESTINATION_TRANSFORMER.equals(phase)
                && !PythonContract.ROLE_RESPONSE_TRANSFORMER.equals(phase)) {
            throw new PythonStepException("Unknown transformer phase " + phase);
        }
        PythonRuntime runtime = PythonRuntime.require();
        String channelId = message.getChannelId();
        boolean response = TransformCall.isResponsePhase(phase);
        try (Deployments.Admission admission = admit(runtime, message, phase, spec)) {
            ScriptMaps maps = new ScriptMaps().add(ScriptMaps.CHANNEL, channelMap, true).add(ScriptMaps.CONNECTOR, connectorMap, true)
                    .add(ScriptMaps.SOURCE, sourceMap, false).add(ScriptMaps.RESPONSE, responseMap, true)
                    .responseDestinations(message.getDestinationIdMap())
                    .shared(runtime.engine, channelId);
            Map<String, Object> context;
            try {
                context = TransformCall.context(phase, msgKind, msgText, tmpKind, tmpText, maps, metadata(message),
                        response ? TransformCall.responseFields(responseStatus, responseStatusMessage, responseError) : null);
            } catch (Wire.ValueException e) {
                throw new PythonStepException(admission.spec.label + ": " + e.getMessage());
            }
            Executor.Outcome outcome;
            try {
                outcome = runtime.executor.invoke(admission, context);
            } catch (Executor.Failure e) {
                throw new PythonStepException(admission.spec.label + ": " + e.getMessage());
            }
            if (!outcome.ok()) {
                throw new PythonStepException(PythonRuntime.describeError(admission.spec.label, outcome.error()));
            }
            try {
                return filter ? TransformCall.validateFilter(outcome.result(), msgKind, tmpKind, maps)
                        : TransformCall.validate(outcome.result(), msgKind, tmpKind, response, maps);
            } catch (Wire.ValueException e) {
                throw new PythonStepException(admission.spec.label + ": the script result was rejected before any change was applied: "
                        + e.getMessage());
            }
        }
    }

    /** A JSON-result callable function with the calling transformer's maps. */
    public static String executeTemplate(String spec, String phase, Object connectorMessage, String argumentsJson) throws InterruptedException {
        if (!(connectorMessage instanceof ImmutableConnectorMessage)) throw new PythonStepException("Python functions require a transformer message");
        ImmutableConnectorMessage message = (ImmutableConnectorMessage) connectorMessage;
        PythonRuntime runtime = PythonRuntime.require();
        RuntimeInvocationContext context = RuntimeInvocationContext.resolve(message, phase);
        if (context == null) throw new PythonStepException("Python functions require the matching scoped runtime transformer context");
        try (Deployments.Admission admission = context.admitTemplate(runtime, spec)) {
            ScriptMaps maps = new ScriptMaps().message(message).shared(runtime.engine, message.getChannelId());
            Map<String, Object> request = new LinkedHashMap<>();
            Object arguments;
            try { arguments = com.mirth.connect.plugins.python.PythonApi.mapper().readValue(argumentsJson, Object.class); }
            catch (java.io.IOException e) { throw new PythonStepException("Python function arguments must be JSON"); }
            if (!(arguments instanceof java.util.List)) throw new PythonStepException("Python function arguments must be a JSON array");
            request.put("role", "call"); request.put("phase", phase); request.put("arguments", arguments);
            request.put("message", metadata(message));
            request.put("maps", maps.wire());
            Executor.Outcome outcome = runtime.executor.invoke(admission, request);
            if (!outcome.ok()) throw new PythonStepException(PythonRuntime.describeError(admission.spec.label, outcome.error()));
            if (!outcome.result().containsKey("value")) throw new PythonStepException("Python function returned no result value");
            try {
                String value = com.mirth.connect.plugins.python.PythonApi.mapper().writeValueAsString(outcome.result().get("value"));
                var changes = maps.parse(outcome.result().get("maps"));
                for (ScriptMaps.Operation change : changes) change.apply();
                return value;
            }
            catch (java.io.IOException e) { throw new PythonStepException("Python function result cannot be encoded as JSON"); }
        } catch (Deployments.NotAdmitted | Executor.Failure | Wire.ValueException e) { throw new PythonStepException(e.getMessage()); }
    }

    private static Deployments.Admission admit(PythonRuntime runtime, ImmutableConnectorMessage message, String phase, String spec) {
        String channelId = message.getChannelId();
        int metaDataId = message.getMetaDataId();
        try {
            String scopePhase = PythonContract.ROLE_SOURCE_FILTER.equals(phase) ? PythonContract.ROLE_SOURCE_TRANSFORMER
                    : PythonContract.ROLE_DESTINATION_FILTER.equals(phase) ? PythonContract.ROLE_DESTINATION_TRANSFORMER : phase;
            RuntimeInvocationContext context = RuntimeInvocationContext.resolve(message, scopePhase);
            if (context == null || !channelId.equals(context.getChannel().getChannelId())
                    || metaDataId != context.getConnectorMetadataId() || !scopePhase.equals(context.getPhase())) {
                throw new PythonStepException("Python steps require the matching scoped runtime transformer context");
            }
            return PythonContract.isFilterRole(phase) ? context.admitFilter(runtime, phase, spec) : context.admit(runtime, spec);
        } catch (Deployments.NotAdmitted e) {
            throw new PythonStepException(e.getMessage());
        }
    }

    static Map<String, Object> metadata(ImmutableConnectorMessage message) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("channel_id", message.getChannelId());
        meta.put("channel_name", message.getChannelName());
        meta.put("connector_name", message.getConnectorName());
        meta.put("metadata_id", Wire.encodeTopLevel(message.getMetaDataId(), "message", "metadata_id"));
        meta.put("message_id", Wire.encodeTopLevel(message.getMessageId(), "message", "message_id"));
        meta.put("server_id", message.getServerId());
        meta.put("send_attempts", Wire.encodeTopLevel(message.getSendAttempts(), "message", "send_attempts"));
        if (message.getReceivedDate() != null) {
            meta.put("received_date", message.getReceivedDate().toInstant().toString());
        }
        return meta;
    }
}
