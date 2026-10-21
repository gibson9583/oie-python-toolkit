/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.mozilla.javascript.ConsString;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;
import org.mozilla.javascript.xml.XMLObject;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirth.connect.plugins.python.PythonApi;

/**
 * Engine side of the worker protocol: length-prefixed UTF-8 JSON frames and
 * the tagged value encoding shared with {@code oie_python._codec}.
 */
public final class Wire {

    public static final int MAX_DEPTH = 64;
    static final ObjectMapper MAPPER = PythonApi.mapper();

    static {
        MAPPER.configure(JsonParser.Feature.ALLOW_NON_NUMERIC_NUMBERS, false);
        MAPPER.configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);
        MAPPER.configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
        MAPPER.configure(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS, false);
    }

    private Wire() {}

    /** The peer violated the protocol; the worker must be discarded. */
    public static final class ProtocolException extends IOException {
        private static final long serialVersionUID = 1L;

        public ProtocolException(String message) {
            super(message);
        }
    }

    /** A value cannot cross the boundary. */
    public static final class ValueException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ValueException(String message) {
            super(message);
        }
    }

    public static byte[] encodeFrame(Map<String, Object> frame, int maxBytes) throws IOException {
        byte[] body = MAPPER.writeValueAsBytes(frame);
        if (body.length > maxBytes) {
            throw new ValueException("The request is " + body.length + " bytes; the frame limit is " + maxBytes + " bytes");
        }
        return ByteBuffer.allocate(4 + body.length).putInt(body.length).put(body).array();
    }

    public static void writeFrame(OutputStream out, byte[] frame) throws IOException {
        out.write(frame);
        out.flush();
    }

    /** Reads one frame, or returns null on a clean end of stream. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readFrame(InputStream in, int maxBytes) throws IOException {
        DataInputStream data = new DataInputStream(in);
        int first = data.read();
        if (first < 0) {
            return null;
        }
        byte[] rest = new byte[3];
        try {
            data.readFully(rest);
        } catch (EOFException e) {
            throw new ProtocolException("Truncated frame header");
        }
        long length = ((long) first << 24) | ((rest[0] & 0xffL) << 16) | ((rest[1] & 0xffL) << 8) | (rest[2] & 0xffL);
        if (length > maxBytes) {
            throw new ProtocolException("Frame of " + length + " bytes exceeds the " + maxBytes + " byte limit");
        }
        byte[] body = new byte[(int) length];
        try {
            data.readFully(body);
        } catch (EOFException e) {
            throw new ProtocolException("Truncated frame body");
        }
        Object value;
        try {
            value = MAPPER.readValue(body, Object.class);
        } catch (IOException e) {
            throw new ProtocolException("Malformed frame: " + e.getMessage());
        }
        if (!(value instanceof Map)) {
            throw new ProtocolException("Frame is not a JSON object");
        }
        return (Map<String, Object>) value;
    }

    // ---- Java to wire -----------------------------------------------------------

    private static final Object UNSUPPORTED = new Object();

    /**
     * Encodes a top-level map value. A value that contains anything Python
     * cannot represent becomes a single unsupported sentinel; the original stays
     * in the engine.
     */
    public static Object encodeTopLevel(Object value, String mapName, String key) {
        Object encoded = encode(value, 0, new IdentityHashMap<>());
        if (encoded == UNSUPPORTED) {
            Map<String, Object> tag = new LinkedHashMap<>();
            tag.put("$t", "unsupported");
            tag.put("map", mapName);
            tag.put("key", key);
            tag.put("type", typeName(value));
            return tag;
        }
        return encoded;
    }

    /** Encodes a whole Java map snapshot. Non-string keys are skipped. */
    public static Map<String, Object> encodeMap(Map<?, ?> map, String mapName) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (map == null) {
            return out;
        }
        for (Map.Entry<?, ?> entry : new ArrayList<>(map.entrySet())) {
            if (entry.getKey() instanceof String) {
                String key = (String) entry.getKey();
                out.put(key, encodeTopLevel(entry.getValue(), mapName, key));
            }
        }
        return out;
    }

    private static Object encode(Object value, int depth, IdentityHashMap<Object, Boolean> active) {
        if (depth > MAX_DEPTH) {
            return UNSUPPORTED;
        }
        if (value instanceof Wrapper) {
            value = ((Wrapper) value).unwrap();
        }
        if (value == null) {
            return null;
        }
        if (value instanceof String || value instanceof ConsString || value instanceof Character) {
            return value.toString();
        }
        if (value instanceof Boolean) {
            return value;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return intTag(((Number) value).longValue());
        }
        if (value instanceof BigInteger) {
            BigInteger big = (BigInteger) value;
            return big.bitLength() < 64 ? intTag(big.longValue()) : UNSUPPORTED;
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            return Double.isFinite(d) ? (Object) d : UNSUPPORTED;
        }
        if (value instanceof byte[]) {
            Map<String, Object> tag = new LinkedHashMap<>();
            tag.put("$t", "bytes");
            tag.put("v", Base64.getEncoder().encodeToString((byte[]) value));
            return tag;
        }
        if (value instanceof XMLObject || value instanceof Function || value instanceof Undefined) {
            return UNSUPPORTED;
        }
        if (active.containsKey(value)) {
            return UNSUPPORTED;
        }
        if (value instanceof Map) {
            active.put(value, Boolean.TRUE);
            try {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : new ArrayList<>(((Map<?, ?>) value).entrySet())) {
                    if (!(entry.getKey() instanceof String)) {
                        return UNSUPPORTED;
                    }
                    Object encoded = encode(entry.getValue(), depth + 1, active);
                    if (encoded == UNSUPPORTED) {
                        return UNSUPPORTED;
                    }
                    out.put((String) entry.getKey(), encoded);
                }
                if (out.containsKey("$t")) {
                    Map<String, Object> tag = new LinkedHashMap<>();
                    tag.put("$t", "map");
                    tag.put("v", out);
                    return tag;
                }
                return out;
            } finally {
                active.remove(value);
            }
        }
        if (value instanceof Collection || value instanceof Object[]) {
            active.put(value, Boolean.TRUE);
            try {
                Iterable<?> items = value instanceof Collection ? new ArrayList<>((Collection<?>) value) : java.util.Arrays.asList((Object[]) value);
                List<Object> out = new ArrayList<>();
                for (Object item : items) {
                    Object encoded = encode(item, depth + 1, active);
                    if (encoded == UNSUPPORTED) {
                        return UNSUPPORTED;
                    }
                    out.add(encoded);
                }
                return out;
            } finally {
                active.remove(value);
            }
        }
        return UNSUPPORTED;
    }

    private static Map<String, Object> intTag(long v) {
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("$t", "int");
        tag.put("v", Long.toString(v));
        return tag;
    }

    static String typeName(Object value) {
        if (value instanceof Wrapper) {
            value = ((Wrapper) value).unwrap();
        }
        if (value == null) {
            return "null";
        }
        if (value instanceof XMLObject) {
            return "E4X XML";
        }
        if (value instanceof Function) {
            return "JavaScript function";
        }
        return value.getClass().getName();
    }

    // ---- Wire to Java -------------------------------------------------------------

    /** Looks up the original engine value behind an unsupported sentinel. */
    public interface OriginalValues {
        /** Returns the original value, or throws ValueException when it is not available. */
        Object original(String mapName, String key);
    }

    @SuppressWarnings("unchecked")
    public static Object decode(Object wire, String where, OriginalValues originals, int depth) {
        if (depth > MAX_DEPTH) {
            throw new ValueException(where + " is nested more than " + MAX_DEPTH + " levels deep");
        }
        if (wire == null || wire instanceof String || wire instanceof Boolean) {
            return wire;
        }
        if (wire instanceof Double || wire instanceof Float) {
            double d = ((Number) wire).doubleValue();
            if (!Double.isFinite(d)) {
                throw new ValueException(where + " is not a finite number");
            }
            return d;
        }
        if (wire instanceof Number) {
            // The SDK tags exact integers. Reject untagged integers that
            // would become a different value at the JavaScript boundary.
            BigInteger value = new BigInteger(wire.toString());
            if (value.abs().compareTo(BigInteger.valueOf(9007199254740991L)) > 0) {
                throw new ValueException(where + " is an untagged integer outside the JavaScript safe range");
            }
            return value.doubleValue();
        }
        if (wire instanceof List) {
            List<Object> out = new ArrayList<>();
            int i = 0;
            for (Object item : (List<Object>) wire) {
                out.add(decode(item, where + "[" + i++ + "]", originals, depth + 1));
            }
            return out;
        }
        if (wire instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) wire;
            Object tag = map.get("$t");
            if (tag == null) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    out.put(entry.getKey(), decode(entry.getValue(), where + "['" + entry.getKey() + "']", originals, depth + 1));
                }
                return out;
            }
            switch (String.valueOf(tag)) {
                case "int": {
                    long v;
                    try {
                        Object raw = map.get("v");
                        if (!(raw instanceof String) || !((String) raw).matches("-?(0|[1-9][0-9]*)")) throw new NumberFormatException();
                        v = Long.parseLong((String) raw);
                    } catch (NumberFormatException e) {
                        throw new ValueException(where + " is not a signed 64-bit integer");
                    }
                    return v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE ? (Object) (int) v : (Object) v;
                }
                case "bytes":
                    try {
                        if (!(map.get("v") instanceof String)) throw new IllegalArgumentException();
                        return Base64.getDecoder().decode((String) map.get("v"));
                    } catch (IllegalArgumentException e) {
                        throw new ValueException(where + " has malformed base64 bytes");
                    }
                case "map":
                    Object inner = map.get("v");
                    if (!(inner instanceof Map)) {
                        throw new ValueException(where + " has a malformed map tag");
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> entry : ((Map<String, Object>) inner).entrySet()) {
                        out.put(entry.getKey(), decode(entry.getValue(), where + "['" + entry.getKey() + "']", originals, depth + 1));
                    }
                    return out;
                case "unsupported":
                    if (originals == null) {
                        throw new ValueException(where + " refers to an engine value that is not available here");
                    }
                    return originals.original(String.valueOf(map.get("map")), String.valueOf(map.get("key")));
                default:
                    throw new ValueException(where + " has unknown value tag " + tag);
            }
        }
        throw new ValueException(where + " has an unsupported wire type " + wire.getClass().getSimpleName());
    }
}
