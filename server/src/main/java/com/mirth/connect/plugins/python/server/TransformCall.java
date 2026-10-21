/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.plugins.python.PythonContract;

/** Builds transformer contexts and validates transformer results before anything is applied. */
public final class TransformCall {

    private static final Pattern XML_DECLARATION = Pattern.compile("^\\s*<\\?xml[^>]*\\?>\\s*");

    private TransformCall() {}

    /** Python's view of a JavaScript document kind. */
    static String pythonKind(String jsKind, String name) {
        if (jsKind == null) {
            throw new Wire.ValueException(name + " is missing");
        }
        switch (jsKind) {
            case "json":
                return "json";
            case "xml":
            case "xmllist":
                return "xml";
            case "raw":
                return "raw";
            case "null":
                throw new Wire.ValueException(name + " is null; Python steps need a JSON object or array, XML, or a string");
            default:
                throw new Wire.ValueException(name + " holds a JavaScript " + jsKind
                        + " value; Python steps support JSON objects and arrays, XML and strings. A JSON scalar root is not supported.");
        }
    }

    public static Map<String, Object> context(String phase, String msgKind, String msgText, String tmpKind, String tmpText, ScriptMaps maps,
            Map<String, Object> message, Map<String, Object> response) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("role", PythonContract.isFilterRole(phase) ? "filter" : "transform");
        context.put("phase", phase);
        context.put("msg", doc(pythonKind(msgKind, "msg"), msgText));
        if (tmpKind != null && !"absent".equals(tmpKind)) {
            context.put("tmp", doc(pythonKind(tmpKind, "tmp"), tmpText));
        }
        if (response != null) {
            context.put("response", response);
        }
        context.put("maps", maps.wire());
        context.put("message", message);
        return context;
    }

    private static Map<String, Object> doc(String kind, String text) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("kind", kind);
        doc.put("text", text == null ? "" : text);
        return doc;
    }

    /** Validates the whole result. Throws Wire.ValueException naming the first problem. */
    @SuppressWarnings("unchecked")
    public static StepResult validate(Map<String, Object> result, String msgKind, String tmpKind, boolean responsePhase, ScriptMaps maps) {
        if (result == null || !java.util.Set.of("msg", "tmp", "response", "maps").containsAll(result.keySet())) {
            throw new Wire.ValueException("The transformer returned unknown or malformed result fields");
        }
        String newMsgKind = null;
        String newMsgText = null;
        String newTmpKind = null;
        String newTmpText = null;
        if (result.get("msg") != null) {
            if (!(result.get("msg") instanceof Map)) throw new Wire.ValueException("msg has a malformed document envelope");
            newMsgText = checkDocument((Map<String, Object>) result.get("msg"), msgKind, "msg");
            newMsgKind = msgKind;
        }
        if (result.get("tmp") != null) {
            if (!(result.get("tmp") instanceof Map)) throw new Wire.ValueException("tmp has a malformed document envelope");
            if (tmpKind == null || "absent".equals(tmpKind)) {
                throw new Wire.ValueException("The script returned tmp, but this transformer has no outbound template");
            }
            newTmpText = checkDocument((Map<String, Object>) result.get("tmp"), tmpKind, "tmp");
            newTmpKind = tmpKind;
        }
        boolean hasResponse = false;
        Status status = null;
        String statusMessage = null;
        String error = null;
        if (result.get("response") != null) {
            if (!(result.get("response") instanceof Map)) throw new Wire.ValueException("response has a malformed envelope");
            if (!responsePhase) {
                throw new Wire.ValueException("Response fields exist only in a response transformer");
            }
            Map<String, Object> response = (Map<String, Object>) result.get("response");
            String s = String.valueOf(response.get("status"));
            if (!s.equals("SENT") && !s.equals("ERROR") && !s.equals("QUEUED")) {
                throw new Wire.ValueException("response.status must be SENT, ERROR or QUEUED");
            }
            status = Status.valueOf(s);
            statusMessage = optionalString(response.get("statusMessage"), "response.status_message");
            error = optionalString(response.get("error"), "response.error");
            hasResponse = true;
        }
        List<ScriptMaps.Operation> operations = maps.parse(result.get("maps"));
        return new StepResult(newMsgKind, newMsgText, newTmpKind, newTmpText, hasResponse, status, statusMessage, error, operations);
    }

    public static StepResult validateFilter(Map<String, Object> result, String msgKind, String tmpKind, ScriptMaps maps) {
        if (result == null || !(result.get("accepted") instanceof Boolean)) {
            throw new Wire.ValueException("Filter result must be exactly True or False");
        }
        Map<String, Object> changes = new LinkedHashMap<>(result);
        boolean accepted = (Boolean) changes.remove("accepted");
        return validate(changes, msgKind, tmpKind, false, maps).accepted(accepted);
    }

    private static String optionalString(Object value, String field) {
        if (value != null && !(value instanceof String)) {
            throw new Wire.ValueException(field + " must be a string");
        }
        return (String) value;
    }

    private static String checkDocument(Map<String, Object> doc, String jsKind, String name) {
        String expected = pythonKind(jsKind, name);
        if (!expected.equals(doc.get("kind"))) {
            throw new Wire.ValueException(name + " changed from " + expected + " to " + doc.get("kind") + ", which is not supported");
        }
        Object text = doc.get("text");
        if (!(text instanceof String)) {
            throw new Wire.ValueException(name + " has no text");
        }
        String value = (String) text;
        switch (expected) {
            case "json":
                JsonNode node;
                try {
                    node = Wire.MAPPER.readTree(value);
                } catch (Exception e) {
                    throw new Wire.ValueException(name + " is not valid JSON: " + e.getMessage());
                }
                if (node == null || !(node.isObject() || node.isArray())) {
                    throw new Wire.ValueException(name + " must be a JSON object or array");
                }
                checkJsonNumbers(node, name, 0);
                return value;
            case "xml":
                String body = XML_DECLARATION.matcher(value).replaceFirst("");
                checkWellFormed("xmllist".equals(jsKind) ? "<oie-xml-list>" + body + "</oie-xml-list>" : body, name);
                return body;
            default:
                return value;
        }
    }

    private static void checkJsonNumbers(JsonNode node, String where, int depth) {
        if (depth > Wire.MAX_DEPTH) throw new Wire.ValueException(where + " exceeds the JSON nesting limit");
        if (node.isIntegralNumber() && node.bigIntegerValue().abs().compareTo(java.math.BigInteger.valueOf(9007199254740991L)) > 0) {
            throw new Wire.ValueException(where + " contains an integer outside the JavaScript safe range");
        }
        if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue())) throw new Wire.ValueException(where + " contains a non-finite number");
        if (node.isContainerNode()) for (JsonNode child : node) checkJsonNumbers(child, where, depth + 1);
    }

    private static void checkWellFormed(String xml, String name) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new Wire.ValueException(name + " is not well-formed XML: " + e.getMessage());
        }
    }

    public static Map<String, Object> responseFields(Object status, Object statusMessage, Object error) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status == null ? null : String.valueOf(unwrap(status)));
        response.put("statusMessage", statusMessage == null ? null : String.valueOf(unwrap(statusMessage)));
        response.put("error", error == null ? null : String.valueOf(unwrap(error)));
        return response;
    }

    private static Object unwrap(Object value) {
        return value instanceof org.mozilla.javascript.Wrapper ? ((org.mozilla.javascript.Wrapper) value).unwrap() : value;
    }

    static boolean isResponsePhase(String phase) {
        return PythonContract.ROLE_RESPONSE_TRANSFORMER.equals(phase);
    }
}
