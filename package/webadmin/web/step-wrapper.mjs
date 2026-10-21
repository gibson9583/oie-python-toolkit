/* SPDX-License-Identifier: MPL-2.0 */
// Mirrors PythonContract.stepWrapper; parity is checked against the shared Java contract.
export const stepWrapper = digest => `(function () {
    var __py = Packages.com.mirth.connect.plugins.python.server.PythonBridge;
    var __phase = typeof responseStatus !== 'undefined' ? 'response-transformer' : (connectorMessage.getMetaDataId() == 0 ? 'source-transformer' : 'destination-transformer');
    function __doc(v) {
        if (typeof v === 'undefined') { return ['absent', null]; }
        if (v === null) { return ['null', null]; }
        if (typeof v === 'xml') { return [Object.prototype.toString.call(v) == '[object XMLList]' ? 'xmllist' : 'xml', v.toXMLString()]; }
        var t = Object.prototype.toString.call(v);
        if (t == '[object Object]' || t == '[object Array]') { return ['json', JSON.stringify(v)]; }
        if (typeof v === 'string' || t == '[object String]' || v instanceof java.lang.String) { return ['raw', String(v)]; }
        return ['unsupported', typeof v];
    }
    function __restore(kind, text) {
        default xml namespace = '';
        text = String(text);
        if (kind == 'json') { return JSON.parse(text); }
        if (kind == 'xml') { return new XML('<?xml version="1.0"?>' + text); }
        if (kind == 'xmllist') { return new XMLList(text); }
        return text;
    }
    var __m = __doc(typeof msg === 'undefined' ? undefined : msg);
    var __t = __doc(typeof tmp === 'undefined' ? undefined : tmp);
    var __isResponse = __phase == 'response-transformer';
    var __r = __py.executeStep('__SPEC__', __phase, connectorMessage, __m[0], __m[1], __t[0], __t[1],
        typeof channelMap === 'undefined' ? null : channelMap,
        typeof connectorMap === 'undefined' ? null : connectorMap,
        typeof sourceMap === 'undefined' ? null : sourceMap,
        typeof responseMap === 'undefined' ? null : responseMap,
        __isResponse ? responseStatus : null,
        __isResponse && typeof responseStatusMessage !== 'undefined' ? responseStatusMessage : null,
        __isResponse && typeof responseErrorMessage !== 'undefined' ? responseErrorMessage : null);
    var __newMsg = __r.hasMsg() ? __restore(__r.getMsgKind(), __r.getMsgText()) : undefined;
    var __newTmp = __r.hasTmp() ? __restore(__r.getTmpKind(), __r.getTmpText()) : undefined;
    __r.commit();
    if (__r.hasMsg()) { msg = __newMsg; }
    if (__r.hasTmp()) { tmp = __newTmp; }
    if (__r.hasResponse()) {
        responseStatus = Packages.com.mirth.connect.userutil.Status.valueOf(String(__r.getResponseStatus().name()));
        responseStatusMessage = __r.getResponseStatusMessage();
        responseErrorMessage = __r.getResponseError();
    }
})();
`.replace("__SPEC__", digest);

export const filterWrapper = digest => 'return ' + stepWrapper(digest)
    .replace('executeStep(', 'executeFilter(')
    .replaceAll('source-transformer', 'source-filter').replaceAll('destination-transformer', 'destination-filter')
    .replace('})();', '    return __r.isAccepted();\n})();');
