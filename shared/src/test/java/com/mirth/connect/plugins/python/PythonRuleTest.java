/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.mirth.connect.model.Rule;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.Xpp3Driver;

class PythonRuleTest {
    @Test void nativeRuleRoundTripsOperatorSourceAndEnvironment() throws Exception {
        PythonRule rule = new PythonRule();
        rule.setOperator(Rule.Operator.OR);
        rule.setEnabled(true);
        rule.setPythonScript("result = not msg['reject']");
        rule.setEnvironmentProfileId("profile");
        XStream stream = new XStream(new Xpp3Driver());
        stream.allowTypesByWildcard(new String[] {"com.mirth.connect.**"});
        PythonRule restored = (PythonRule) stream.fromXML(stream.toXML(rule));
        assertEquals(Rule.Operator.OR, restored.getOperator());
        assertEquals(rule.getPythonScript(), restored.getPythonScript());
        assertEquals("profile", restored.getEnvironmentProfileId());
        assertEquals(PythonContract.specDigest(rule), PythonContract.specDigest(restored));
        assertEquals(rule.getScript(true), restored.clone().getScript(true));
    }
    @Test void wrapperReturnsPredicateAndDoesNotEmbedAuthoredSource() {
        PythonRule rule = new PythonRule();
        rule.setPythonScript("result = __import__('anything')");
        String wrapper = rule.getScript(true);
        assertTrue(wrapper.startsWith("return (function"));
        assertTrue(wrapper.contains("executeFilter("));
        assertTrue(wrapper.contains("return __r.isAccepted();"));
        assertTrue(wrapper.contains("'source-filter'"));
        assertFalse(wrapper.contains("__import__"));
        assertEquals("filter", PythonContract.scriptRole(PythonContract.ROLE_SOURCE_FILTER));
        assertTrue(PythonContract.isRole(PythonContract.ROLE_DESTINATION_FILTER));
        assertFalse(PythonContract.isRole("response-filter"));
    }
}
