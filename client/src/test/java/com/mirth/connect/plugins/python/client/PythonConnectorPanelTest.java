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
        assertSame(readerProperties, reader.getProperties());
        assertTrue(reader.checkProperties(readerProperties, true));
        assertEquals("Python Reader", reader.getConnectorName());
        assertInstanceOf(PythonReceiverProperties.class, reader.getDefaults());

        var writer = new PythonDispatcherPanel(); var writerProperties = new PythonDispatcherProperties();
        writerProperties.setPythonScript("return payload\n");
        writer.setProperties(writerProperties);
        assertSame(writerProperties, writer.getProperties());
        assertEquals("Python Writer", writer.getConnectorName());
        assertInstanceOf(PythonDispatcherProperties.class, writer.getDefaults());
    }
}
