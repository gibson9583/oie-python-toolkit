/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.mirth.connect.connectors.python.PythonDispatcherProperties;
import com.mirth.connect.connectors.python.PythonReceiverProperties;

class PythonConnectorPanelTest {
    @Test void panelsKeepTheLoadedPropertiesUnchanged() {
        var reader = new PythonReceiverPanel(); var readerProperties = new PythonReceiverProperties();
        readerProperties.setPythonScript("return None\n"); readerProperties.setEnvironmentProfileId("profile");
        reader.setProperties(readerProperties);
        assertEquals(readerProperties, reader.getProperties());
        assertNotSame(readerProperties, reader.getProperties());
        assertTrue(reader.checkProperties(readerProperties, true));
        assertEquals("Python Reader", reader.getConnectorName());
        assertInstanceOf(PythonReceiverProperties.class, reader.getDefaults());

        var writer = new PythonDispatcherPanel(); var writerProperties = new PythonDispatcherProperties();
        writerProperties.setPythonScript("return payload\n");
        writer.setProperties(writerProperties);
        assertEquals(writerProperties, writer.getProperties());
        assertNotSame(writerProperties, writer.getProperties());
        assertEquals("Python Writer", writer.getConnectorName());
        assertInstanceOf(PythonDispatcherProperties.class, writer.getDefaults());
    }

    @Test void sharedAdministratorSettingsCannotMutateTheLoadedConnector() {
        var writer = new PythonDispatcherPanel();
        var first = new PythonDispatcherProperties();
        first.setPythonScript("return 'first'\n");
        first.getDestinationConnectorProperties().setRetryCount(2);
        writer.setProperties(first);
        var duringSelection = (PythonDispatcherProperties) writer.getProperties();
        duringSelection.getDestinationConnectorProperties().setRetryCount(7);
        duringSelection.setPythonScript("return 'other'\n");
        assertEquals(2, first.getDestinationConnectorProperties().getRetryCount());
        assertEquals(first, writer.getProperties());
        first.getDestinationConnectorProperties().setRetryCount(9);
        assertEquals(2, ((PythonDispatcherProperties) writer.getProperties()).getDestinationConnectorProperties().getRetryCount());

        var reader = new PythonReceiverPanel();
        var source = new PythonReceiverProperties();
        source.getPollConnectorProperties().setPollingFrequency(90123);
        reader.setProperties(source);
        var returnedSource = (PythonReceiverProperties) reader.getProperties();
        returnedSource.getPollConnectorProperties().setPollingFrequency(60000);
        assertEquals(source, reader.getProperties());
        source.setPythonScript("return 'changed elsewhere'\n");
        assertEquals("return None\n", ((PythonReceiverProperties) reader.getProperties()).getPythonScript());
    }
}
