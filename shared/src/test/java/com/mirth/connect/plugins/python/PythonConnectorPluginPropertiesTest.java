/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import com.mirth.connect.connectors.python.PythonDispatcherProperties;
import com.mirth.connect.connectors.python.PythonReceiverProperties;
import com.mirth.connect.donkey.model.channel.ConnectorPluginProperties;
import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.Xpp3Driver;

class PythonConnectorPluginPropertiesTest {
    /** An unrelated extension entry, preserved rather than discarded by normalization. */
    public static final class ForeignProperties extends ConnectorPluginProperties {
        Map<String, String> settings = new HashMap<>();
        ForeignProperties(String value) { settings.put("opaque:key", value); }
        @Override public String getName() { return "Foreign extension"; }
        @Override public Map<String, Object> getPurgedProperties() { return Map.of("count", settings.size()); }
        @Override public ConnectorPluginProperties clone() {
            var copy = new ForeignProperties(""); copy.settings = new HashMap<>(settings); return copy;
        }
        @Override public boolean equals(Object value) { return value instanceof ForeignProperties other && settings.equals(other.settings); }
        @Override public int hashCode() { return Objects.hash(settings); }
    }
    private static List<ConnectorProperties> models() { return List.of(new PythonReceiverProperties(), new PythonDispatcherProperties()); }
    private static XStream serializer() {
        var serializer = new XStream(new Xpp3Driver());
        serializer.allowTypesByWildcard(new String[] {"com.mirth.connect.**"});
        serializer.allowTypes(new Class<?>[] {com.thoughtworks.xstream.mapper.Mapper.Null.class});
        return serializer;
    }
    private static String withoutPluginSet(XStream serializer, ConnectorProperties model) {
        String xml = serializer.toXML(model);
        assertTrue(xml.contains("<pluginProperties"), "new models must serialize an initialized empty set");
        String absent = xml.replaceFirst("<pluginProperties(?:\\s[^>]*)?/>", "");
        assertFalse(absent.contains("<pluginProperties"), "fixture must omit the inherited field");
        return absent;
    }

    @Test void newAndExplicitNullModelsExposeMutableEmptySetsToStockPluginScans() {
        for (ConnectorProperties model : models()) {
            assertEquals(0, model.getPluginProperties().stream().count());
            var original = model.getPluginProperties();
            assertTrue(original.add(new ForeignProperties("preserve")));
            assertSame(original, model.getPluginProperties());
            model.setPluginProperties(null);
            assertNotSame(original, model.getPluginProperties()); assertTrue(model.getPluginProperties().isEmpty());
            assertTrue(model.getPluginProperties().add(new ForeignProperties("still mutable")));
        }
    }

    @Test void absentXStreamFieldsAreNormalizedWithoutLosingOtherConnectorSettings() {
        var serializer = serializer();
        for (ConnectorProperties model : models()) {
            var imported = (ConnectorProperties) serializer.fromXML(withoutPluginSet(serializer, model));
            assertEquals(0, imported.getPluginProperties().stream().count());
            assertTrue(imported.getPluginProperties().add(new ForeignProperties("after import")));
            assertEquals(model.getName(), imported.getName());
            assertEquals(model.getProtocol(), imported.getProtocol());
            if (imported instanceof PythonDispatcherProperties writer) assertNotNull(writer.getDestinationConnectorProperties());
            if (imported instanceof PythonReceiverProperties reader) {
                assertNotNull(reader.getSourceConnectorProperties()); assertNotNull(reader.getPollConnectorProperties());
            }
        }
    }

    @Test void explicitNullXStreamFieldsAndXmlRoundTripsRemainSafeAndPreserveForeignEntries() {
        var serializer = serializer();
        for (ConnectorProperties model : models()) {
            String absent = withoutPluginSet(serializer, model);
            int rootEnd = absent.indexOf('>') + 1;
            String explicitNull = absent.substring(0, rootEnd) + "<pluginProperties class=\"null\"/>" + absent.substring(rootEnd);
            var imported = (ConnectorProperties) serializer.fromXML(explicitNull);
            assertEquals(0, imported.getPluginProperties().stream().count());
            var foreign = new ForeignProperties("東京:preserved"); var supplied = new HashSet<ConnectorPluginProperties>(); supplied.add(foreign);
            imported.setPluginProperties(supplied); assertSame(supplied, imported.getPluginProperties());
            var roundTrip = (ConnectorProperties) serializer.fromXML(serializer.toXML(imported));
            assertEquals(supplied, roundTrip.getPluginProperties());
            assertNotSame(foreign, roundTrip.getPluginProperties().iterator().next());
        }
    }

    @Test void writerCopiesNormalizeImportedAbsenceAndCloneForeignEntriesIndependently() {
        var serializer = serializer(); var original = new PythonDispatcherProperties();
        var imported = (PythonDispatcherProperties) serializer.fromXML(withoutPluginSet(serializer, original));
        var emptyCopy = new PythonDispatcherProperties(imported);
        assertTrue(emptyCopy.getPluginProperties().isEmpty()); assertNotSame(imported.getPluginProperties(), emptyCopy.getPluginProperties());
        var foreign = new ForeignProperties("initial"); imported.getPluginProperties().add(foreign);
        var copy = (PythonDispatcherProperties) imported.clone();
        assertEquals(imported.getPluginProperties(), copy.getPluginProperties());
        var copiedEntry = (ForeignProperties) copy.getPluginProperties().iterator().next(); assertNotSame(foreign, copiedEntry);
        copiedEntry.settings.put("opaque:key", "copy mutation");
        assertEquals("initial", foreign.settings.get("opaque:key"));
        assertEquals("copy mutation", copiedEntry.settings.get("opaque:key"));
        copy.getPluginProperties().clear(); assertEquals(1, imported.getPluginProperties().size());
    }

    @Test void jsonBeanRequestsWithMissingOrExplicitNullSetsExposeSafeMutableCollections() throws Exception {
        for (ConnectorProperties model : models()) {
            for (String body : List.of("{}", "{\"pluginProperties\":null}", "{\"pluginProperties\":[]}")) {
                ConnectorProperties imported = PythonApi.mapper().readValue(body, model.getClass());
                assertEquals(0, imported.getPluginProperties().stream().count());
                assertTrue(imported.getPluginProperties().add(new ForeignProperties("JSON import")));
            }
        }
    }
}
