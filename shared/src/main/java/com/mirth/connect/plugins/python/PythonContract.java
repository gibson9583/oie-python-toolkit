/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Versioned contract constants, execution-spec digests and the step wrapper. */
public final class PythonContract {

    public static final int CONTRACT_VERSION = 1;
    public static final int CANONICALIZATION_VERSION = 1;

    public static final String EXTENSION_NAME = "Python Toolkit";
    public static final String STEP_TYPE = "Python";
    public static final String READER_NAME = "Python Reader";
    public static final String WRITER_NAME = "Python Writer";
    public static final String PROTOCOL = "Python";
    /** Stable installation-independent standard-library environment identity. */
    public static final String DEFAULT_PROFILE_ID = "232030e4-aa0d-39fc-a889-fed1318ef412";
    public static final String DEFAULT_PROFILE_NAME = "Python";
    public static final String BUILTIN_SOURCE_ID = "builtin-stdlib";

    public static final String ROLE_SOURCE_TRANSFORMER = "source-transformer";
    public static final String ROLE_DESTINATION_TRANSFORMER = "destination-transformer";
    public static final String ROLE_RESPONSE_TRANSFORMER = "response-transformer";
    public static final String ROLE_CODE_TEMPLATE = "code-template";
    public static String templateRole(String phase) { return ROLE_CODE_TEMPLATE + ":" + phase; }

    public static final String ROLE_SOURCE_FILTER = "source-filter";
    public static final String ROLE_DESTINATION_FILTER = "destination-filter";
    public static boolean isFilterRole(String role) { return ROLE_SOURCE_FILTER.equals(role) || ROLE_DESTINATION_FILTER.equals(role); }

    public static final String ROLE_READER = "reader";
    public static final String ROLE_WRITER = "writer";

    public static final int DEFAULT_TIMEOUT_MILLIS = 30000;
    public static final int MIN_TIMEOUT_MILLIS = 100;

    private static final Pattern SPEC = Pattern.compile("[0-9a-f]{64}");

    private PythonContract() {}

    /** The worker context kind for a script surface. */
    public static String scriptRole(String role) {
        if (ROLE_CODE_TEMPLATE.equals(role) || role.startsWith(ROLE_CODE_TEMPLATE + ":")) return "call";
        if (isFilterRole(role)) return "filter";
        if (ROLE_READER.equals(role)) {
            return "read";
        }
        if (ROLE_WRITER.equals(role)) {
            return "write";
        }
        return "transform";
    }

    public static boolean isRole(String role) {
        return isFilterRole(role) || ROLE_SOURCE_TRANSFORMER.equals(role) || ROLE_DESTINATION_TRANSFORMER.equals(role)
                || ROLE_RESPONSE_TRANSFORMER.equals(role) || ROLE_CODE_TEMPLATE.equals(role) || ROLE_READER.equals(role) || ROLE_WRITER.equals(role);
    }

    /** Python source with CRLF and CR line endings converted to LF; nothing else changes. */
    public static String normalizeSource(String source) {
        return source == null ? "" : source.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** Effective timeout: the configured value, or the default when it is not positive. */
    public static int effectiveTimeout(int timeoutMillis) {
        return timeoutMillis > 0 ? timeoutMillis : DEFAULT_TIMEOUT_MILLIS;
    }

    /** Canonical execution-spec document; its SHA-256 is the spec digest. */
    public static Map<String, Object> executionSpec(PythonScriptConfiguration c) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("canonicalization", CANONICALIZATION_VERSION);
        spec.put("contractVersion", c.getContractVersion() <= 0 ? CONTRACT_VERSION : c.getContractVersion());
        spec.put("profileId", c.getEnvironmentProfileId() == null ? "" : c.getEnvironmentProfileId());
        spec.put("timeoutMillis", effectiveTimeout(c.getTimeoutMillis()));
        spec.put("source", normalizeSource(c.getPythonScript()));
        if (c.getCodeTemplateFunctionName() != null) {
            spec.put("kind", ROLE_CODE_TEMPLATE);
            spec.put("functionName", c.getCodeTemplateFunctionName());
        }
        return spec;
    }

    public static String specDigest(PythonScriptConfiguration c) {
        return CanonicalJson.sha256(executionSpec(c));
    }

    public static void validateFunctionName(String name) {
        if (name == null || !name.matches("[A-Za-z_$][A-Za-z0-9_$]*") || java.util.Set.of(
                "break", "case", "catch", "continue", "debugger", "default", "delete", "do", "else", "finally", "for", "function",
                "if", "in", "instanceof", "new", "return", "switch", "this", "throw", "try", "typeof", "var", "void", "while", "with",
                "class", "const", "enum", "export", "extends", "import", "super", "implements", "interface", "let", "package", "private",
                "protected", "public", "static", "yield", "null", "true", "false", "arguments", "eval",
                "JSON", "Array", "Object", "Packages", "isFinite", "String", "Error", "connectorMessage", "responseStatus").contains(name)) {
            throw new IllegalArgumentException("Function name must be a non-reserved JavaScript identifier");
        }
    }

    /** Pure wrapper: no source execution or environment lookup during template save/validation. */
    public static String templateWrapper(String functionName, String specDigest) {
        validateFunctionName(functionName);
        if (specDigest == null || !SPEC.matcher(specDigest).matches()) throw new IllegalArgumentException("Invalid execution-spec digest");
        return "function " + functionName + "() {\n"
                + "    if (typeof connectorMessage === 'undefined') throw new Error('Python functions require a source, destination or response transformer');\n"
                + "    var __args = Array.prototype.slice.call(arguments);\n"
                + "    function __check(v, seen) {\n"
                + "        if (v === null || typeof v === 'string' || typeof v === 'boolean') return;\n"
                + "        if (typeof v === 'number' && isFinite(v)) return;\n"
                // Stock RAW msg is a native boxed String, including when nested in arguments.
                // The intrinsic rejects objects merely claiming the String tag and Java objects.
                + "        if (Object.prototype.toString.call(v) === '[object String]') {\n"
                + "            try { String.prototype.valueOf.call(v); return; } catch (e) { throw new Error('Python function arguments must contain only JSON values'); }\n"
                + "        }\n"
                + "        if (typeof v !== 'object' || (Object.prototype.toString.call(v) !== '[object Object]' && Object.prototype.toString.call(v) !== '[object Array]')) throw new Error('Python function arguments must contain only JSON values');\n"
                + "        if (seen.indexOf(v) >= 0) throw new Error('Python function arguments must not contain cycles');\n"
                + "        seen.push(v);\n"
                + "        if (Array.isArray(v)) { for (var i = 0; i < v.length; i++) __check(v[i], seen); }\n"
                + "        else { for (var key in v) if (Object.prototype.hasOwnProperty.call(v, key)) __check(v[key], seen); }\n"
                + "        seen.pop();\n"
                + "    }\n"
                + "    __check(__args, []);\n"
                + "    var __phase = typeof responseStatus !== 'undefined' ? 'response-transformer' : (connectorMessage.getMetaDataId() == 0 ? 'source-transformer' : 'destination-transformer');\n"
                + "    return JSON.parse(String(Packages.com.mirth.connect.plugins.python.server.PythonBridge.executeTemplate('" + specDigest
                + "', __phase, connectorMessage, JSON.stringify(__args))));\n"
                + "}\n";
    }

    /**
     * The JavaScript the engine compiles for a Python step. It carries only the
     * spec digest, never Python source, so user text cannot change the wrapper.
     * XML restoration isolates the caller's default namespace and supplies a
     * declaration so Rhino parses short or whitespace-prefixed XML as a document.
     */
    public static String filterWrapper(String specDigest) {
        return "return " + stepWrapper(specDigest)
                .replace("executeStep(", "executeFilter(")
                .replace("source-transformer", "source-filter").replace("destination-transformer", "destination-filter")
                .replace("})();", "    return __r.isAccepted();\n})();");
    }

    public static String stepWrapper(String specDigest) {
        if (specDigest == null || !SPEC.matcher(specDigest).matches()) {
            throw new IllegalArgumentException("Invalid execution-spec digest");
        }
        return "(function () {\n"
                + "    var __py = Packages.com.mirth.connect.plugins.python.server.PythonBridge;\n"
                + "    var __phase = typeof responseStatus !== 'undefined' ? 'response-transformer' : (connectorMessage.getMetaDataId() == 0 ? 'source-transformer' : 'destination-transformer');\n"
                + "    function __doc(v) {\n"
                + "        if (typeof v === 'undefined') { return ['absent', null]; }\n"
                + "        if (v === null) { return ['null', null]; }\n"
                + "        if (typeof v === 'xml') { return [Object.prototype.toString.call(v) == '[object XMLList]' ? 'xmllist' : 'xml', v.toXMLString()]; }\n"
                + "        var t = Object.prototype.toString.call(v);\n"
                + "        if (t == '[object Object]' || t == '[object Array]') { return ['json', JSON.stringify(v)]; }\n"
                + "        if (typeof v === 'string' || t == '[object String]' || v instanceof java.lang.String) { return ['raw', String(v)]; }\n"
                + "        return ['unsupported', typeof v];\n"
                + "    }\n"
                + "    function __restore(kind, text) {\n"
                + "        default xml namespace = '';\n"
                + "        text = String(text);\n"
                + "        if (kind == 'json') { return JSON.parse(text); }\n"
                + "        if (kind == 'xml') { return new XML('<?xml version=\"1.0\"?>' + text); }\n"
                + "        if (kind == 'xmllist') { return new XMLList(text); }\n"
                + "        return text;\n"
                + "    }\n"
                + "    var __m = __doc(typeof msg === 'undefined' ? undefined : msg);\n"
                + "    var __t = __doc(typeof tmp === 'undefined' ? undefined : tmp);\n"
                + "    var __isResponse = __phase == 'response-transformer';\n"
                + "    var __r = __py.executeStep('" + specDigest + "', __phase, connectorMessage, __m[0], __m[1], __t[0], __t[1],\n"
                + "        typeof channelMap === 'undefined' ? null : channelMap,\n"
                + "        typeof connectorMap === 'undefined' ? null : connectorMap,\n"
                + "        typeof sourceMap === 'undefined' ? null : sourceMap,\n"
                + "        typeof responseMap === 'undefined' ? null : responseMap,\n"
                + "        __isResponse ? responseStatus : null,\n"
                + "        __isResponse && typeof responseStatusMessage !== 'undefined' ? responseStatusMessage : null,\n"
                + "        __isResponse && typeof responseErrorMessage !== 'undefined' ? responseErrorMessage : null);\n"
                + "    var __newMsg = __r.hasMsg() ? __restore(__r.getMsgKind(), __r.getMsgText()) : undefined;\n"
                + "    var __newTmp = __r.hasTmp() ? __restore(__r.getTmpKind(), __r.getTmpText()) : undefined;\n"
                + "    __r.commit();\n"
                + "    if (__r.hasMsg()) { msg = __newMsg; }\n"
                + "    if (__r.hasTmp()) { tmp = __newTmp; }\n"
                + "    if (__r.hasResponse()) {\n"
                + "        responseStatus = Packages.com.mirth.connect.userutil.Status.valueOf(String(__r.getResponseStatus().name()));\n"
                + "        responseStatusMessage = __r.getResponseStatusMessage();\n"
                + "        responseErrorMessage = __r.getResponseError();\n"
                + "    }\n"
                + "})();\n";
    }
}
