/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.mirth.connect.connectors.python.PythonDispatcherProperties;
import com.mirth.connect.connectors.python.PythonReceiverProperties;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.Xpp3Driver;

class PythonContractTest {

    @Test
    void specDigestsMatchTheIndependentGoldenEncoder() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/golden/spec-digests.json")) {
            JsonNode cases = PythonApi.mapper().readTree(in);
            assertTrue(cases.size() >= 3);
            for (JsonNode c : cases) {
                PythonStep step = new PythonStep();
                step.setPythonScript(c.get("pythonScript").asText());
                step.setEnvironmentProfileId(c.get("environmentProfileId").asText());
                step.setTimeoutMillis(c.get("timeoutMillis").asInt());
                step.setContractVersion(c.get("contractVersion").asInt());
                String name = c.get("name").asText();
                assertEquals(c.get("canonical").asText(), CanonicalJson.encode(PythonContract.executionSpec(step)), name);
                assertEquals(c.get("digest").asText(), PythonContract.specDigest(step), name);
                assertTrue(step.getScript(true).contains("'" + c.get("digest").asText() + "'"), name);
            }
        }
    }

    @Test
    void allSurfacesShareOneDigestDefinition() {
        PythonStep step = new PythonStep();
        PythonReceiverProperties reader = new PythonReceiverProperties();
        PythonDispatcherProperties writer = new PythonDispatcherProperties();
        for (PythonScriptConfiguration c : Arrays.asList(step, reader, writer)) {
            assertEquals(64, PythonContract.specDigest(c).length());
        }
        reader.setPythonScript(step.getPythonScript());
        assertEquals(PythonContract.specDigest(step), PythonContract.specDigest(reader));
        reader.setTimeoutMillis(10);
        assertNotEquals(PythonContract.specDigest(step), PythonContract.specDigest(reader));
    }

    @Test
    void meaningfulChangesChangeTheDigest() {
        PythonStep a = new PythonStep();
        a.setPythonScript("pass\n");
        String base = PythonContract.specDigest(a);
        PythonStep b = new PythonStep(a);
        b.setPythonScript(a.getPythonScript() + " ");
        assertNotEquals(base, PythonContract.specDigest(b));
        b = new PythonStep(a);
        b.setEnvironmentProfileId("x");
        assertNotEquals(base, PythonContract.specDigest(b));
        b = new PythonStep(a);
        b.setEnvironmentNameHint("display only");
        b.setName("renamed step");
        assertEquals(base, PythonContract.specDigest(b));
        b = new PythonStep(a);
        b.setPythonScript(a.getPythonScript().replace("\n", "\r\n"));
        assertEquals(base, PythonContract.specDigest(b));
    }

    @Test
    void canonicalJsonEscapesLikeJsonStringify() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("b", "\ud800x\udc00");
        m.put("a", "\u0007\u2028\"\\");
        assertEquals("{\"a\":\"\\u0007\u2028\\\"\\\\\",\"b\":\"\\ud800x\\udc00\"}", CanonicalJson.encode(m));
        assertEquals("\"\ud83d\ude00\"", CanonicalJson.encode("\ud83d\ude00"));
    }

    @Test
    void wrapperCarriesOnlyTheDigest() {
        PythonStep step = new PythonStep();
        step.setPythonScript("pass\n'); java.lang.System.exit(0); ('\n");
        String script = step.getScript(false);
        assertFalse(script.contains("System.exit"));
        assertThrows(IllegalArgumentException.class, () -> PythonContract.stepWrapper("'); evil(); ('"));
        assertTrue(script.contains("__py.executeStep('" + PythonContract.specDigest(step) + "'"));
    }

    @Test
    void serializedScriptContractsHaveNoSelectors() throws Exception {
        XStream xstream = new XStream(new Xpp3Driver());
        for (PythonScriptConfiguration config : List.of(new PythonStep(), new PythonReceiverProperties(),
                new PythonDispatcherProperties(), new PythonCodeTemplateProperties())) {
            assertFalse(xstream.toXML(config).contains("contextInputs"));
            assertTrue(Arrays.stream(config.getClass().getMethods()).noneMatch(method -> method.getName().equals("getContextInputs")));
            assertFalse(PythonContract.executionSpec(config).containsKey("contextInputs"));
        }
        assertFalse(PythonApi.mapper().writeValueAsString(new PythonApi.ScriptRequest()).contains("contextInputs"));
    }

    @Test
    void modelsRoundTripWithFullyQualifiedNames() {
        XStream xstream = new XStream(new Xpp3Driver());
        xstream.allowTypesByWildcard(new String[] { "com.mirth.connect.**" });
        PythonStep step = new PythonStep();
        step.setName("Validate");
        step.setPythonScript("msg = 'saved step'\r\n");
        step.setEnvironmentProfileId("id-1");
        step.setEnvironmentNameHint("Patient Validation");
        String xml = xstream.toXML(step);
        assertTrue(xml.startsWith("<com.mirth.connect.plugins.python.PythonStep>"), xml);
        assertTrue(xml.contains("<pythonScript>"), xml);
        assertFalse(xml.contains("<script>"), xml);
        assertEquals(step, xstream.fromXML(xml));
        assertEquals(step.getPythonScript(), ((PythonStep) xstream.fromXML(xml)).getPythonScript());
        assertEquals(step.getPythonScript(), ((PythonStep) step.clone()).getPythonScript());

        PythonDispatcherProperties writer = new PythonDispatcherProperties();
        writer.setPythonScript("raise RuntimeError('saved writer')\n");
        writer.setEnvironmentProfileId("id-2");
        assertEquals(writer, xstream.fromXML(xstream.toXML(writer)));
        assertEquals(writer, writer.clone());
        PythonReceiverProperties reader = new PythonReceiverProperties();
        reader.setPythonScript("return None  # saved reader\n");
        assertEquals(reader, xstream.fromXML(xstream.toXML(reader)));
    }
}
