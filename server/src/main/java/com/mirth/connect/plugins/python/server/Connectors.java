/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.MessageContent;
import com.mirth.connect.donkey.model.message.RawMessage;
import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.channel.Channel;
import com.mirth.connect.donkey.server.channel.DestinationChainProvider;

/** Shared authoritative conversion for live connector calls and sample previews. */
public final class Connectors {
    private Connectors() {}

    public static void checkSourceMap(Map<String, Object> map) { SourceMapPolicy.validate(map); }

    public static Set<Integer> destinationIds(Channel channel) {
        Set<Integer> ids = new java.util.LinkedHashSet<>();
        for (DestinationChainProvider chain : channel.getDestinationChainProviders()) {
            // Stock OIE constructs runtime chains from enabled model
            // destinations only. Its runtime DestinationConnector.enabled
            // flag is not populated by the controller and defaults to false.
            // Membership in this exact deployed runtime is authoritative.
            ids.addAll(chain.getDestinationConnectors().keySet());
        }
        return ids;
    }

    /** Validates every result before a Reader admits anything. */
    @SuppressWarnings("unchecked")
    public static List<RawMessage> readerMessages(Map<String, ?> result, Set<Integer> enabled, int limit, boolean batch) {
        Object list = result == null ? null : result.get("messages");
        if (!(list instanceof List<?>)) throw new Wire.ValueException("Reader result.messages must be a list");
        if (((List<?>) list).size() > limit) throw new Wire.ValueException("Reader exceeded the " + limit + " messages per poll limit");
        List<RawMessage> messages = new ArrayList<>();
        for (Object item : (List<?>) list) {
            if (!(item instanceof Map<?, ?>)) throw new Wire.ValueException("Reader result contains a malformed Message");
            Map<String, Object> m = (Map<String, Object>) item;
            Object payload = Wire.decode(m.get("payload"), "payload", null, 0);
            if (!(payload instanceof String) && !(payload instanceof byte[])) throw new Wire.ValueException("Message.payload must be str or bytes");
            if (batch && payload instanceof byte[]) throw new Wire.ValueException("Batch processing does not support binary Message payloads");
            Object rawMap = Wire.decode(m.get("sourceMap"), "sourceMap", null, 0);
            if (!(rawMap instanceof Map<?, ?>)) throw new Wire.ValueException("Message.source_map must be an object");
            Map<String, Object> sourceMap = (Map<String, Object>) rawMap;
            checkSourceMap(sourceMap);
            List<Integer> destinations = null;
            Object rawIds = m.get("destinationIds");
            if (rawIds != null) {
                if (!(rawIds instanceof List<?>)) throw new Wire.ValueException("Message.destination_ids must be a list or None");
                destinations = new ArrayList<>();
                for (Object id : (List<?>) rawIds) {
                    int value;
                    try { value = new BigDecimal(String.valueOf(id)).intValueExact(); }
                    catch (RuntimeException e) { throw new Wire.ValueException("Message.destination_ids must contain integer metadata IDs"); }
                    if (!(id instanceof Number) || !enabled.contains(value)) throw new Wire.ValueException("Destination " + id + " is not enabled in this deployment");
                    if (!destinations.contains(value)) destinations.add(value);
                }
            }
            messages.add(payload instanceof byte[] ? new RawMessage((byte[]) payload, destinations, sourceMap)
                    : new RawMessage((String) payload, destinations, sourceMap));
        }
        return messages;
    }

    public static Map<String, Object> checkWriterResponse(Map<String, Object> response) {
        if (response == null) throw new Wire.ValueException("Writer result.response is required");
        Object status = response.get("status");
        if (!(status instanceof String) || !Set.of("SENT", "ERROR", "QUEUED").contains(status)) {
            throw new Wire.ValueException("Response.status must be SENT, ERROR or QUEUED");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        copy.put("status", status);
        for (String field : List.of("data", "statusMessage", "error")) {
            Object value = response.get(field);
            if (value != null && !(value instanceof String)) throw new Wire.ValueException("Response." + field + " must be a string or None");
            copy.put(field, value == null ? "" : value);
        }
        return copy;
    }

    public static Response writerResponse(Map<String, Object> value, boolean queueEnabled, boolean validate) {
        Map<String, Object> response = checkWriterResponse(value);
        Status status = Status.valueOf((String) response.get("status"));
        String statusMessage = (String) response.get("statusMessage");
        String error = (String) response.get("error");
        if (status == Status.QUEUED && !queueEnabled) {
            status = Status.ERROR;
            statusMessage = "Python returned QUEUED, but this destination's queue is disabled.";
            error = error.isEmpty() ? statusMessage : error;
        }
        return new Response(status, (String) response.get("data"), statusMessage, error, validate);
    }

    /** Empty encoded/transformed content is present and must win over raw. */
    public static String payload(ConnectorMessage message) {
        MessageContent content = message.getEncoded();
        if (content == null) content = message.getTransformed();
        if (content == null) content = message.getRaw();
        return content == null ? null : content.getContent();
    }

    static Map<String, Object> metadata(ConnectorMessage message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channel_id", message.getChannelId());
        out.put("channel_name", message.getChannelName());
        out.put("connector_name", message.getConnectorName());
        out.put("metadata_id", Wire.encodeTopLevel(message.getMetaDataId(), "message", "metadata_id"));
        out.put("message_id", Wire.encodeTopLevel(message.getMessageId(), "message", "message_id"));
        out.put("server_id", message.getServerId());
        out.put("send_attempts", Wire.encodeTopLevel(message.getSendAttempts(), "message", "send_attempts"));
        if (message.getReceivedDate() != null) out.put("received_date", message.getReceivedDate().toInstant().toString());
        return out;
    }
}
