/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.client;

import java.awt.BorderLayout;

import javax.swing.JLabel;
import org.apache.commons.lang3.SerializationUtils;

import com.mirth.connect.client.ui.panels.connectors.ConnectorSettingsPanel;
import com.mirth.connect.donkey.model.channel.ConnectorProperties;

/** Swing placeholder: keeps the Python connector properties unchanged and points to the Web Administrator. */
abstract class PythonConnectorPanel extends ConnectorSettingsPanel {
    private ConnectorProperties properties;

    PythonConnectorPanel() {
        setOpaque(false);
        setLayout(new BorderLayout());
        add(new JLabel("Edit the Python script and environment in the Web Administrator."), BorderLayout.NORTH);
    }

    // The Administrator fills shared queue/polling settings into returned
    // properties while changing connectors. A live reference lets those writes
    // corrupt the previously selected connector before the next panel loads.
    @Override public ConnectorProperties getProperties() {
        return properties == null ? getDefaults() : SerializationUtils.clone(properties);
    }
    @Override public void setProperties(ConnectorProperties properties) {
        this.properties = SerializationUtils.clone(properties);
    }
    @Override public boolean checkProperties(ConnectorProperties properties, boolean highlight) { return true; }
    @Override public void resetInvalidProperties() {}
}
