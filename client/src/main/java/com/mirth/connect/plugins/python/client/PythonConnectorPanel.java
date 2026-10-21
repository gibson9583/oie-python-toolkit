/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.client;

import java.awt.BorderLayout;

import javax.swing.JLabel;

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

    @Override public ConnectorProperties getProperties() { return properties == null ? getDefaults() : properties; }
    @Override public void setProperties(ConnectorProperties properties) { this.properties = properties; }
    @Override public boolean checkProperties(ConnectorProperties properties, boolean highlight) { return true; }
    @Override public void resetInvalidProperties() {}
}
