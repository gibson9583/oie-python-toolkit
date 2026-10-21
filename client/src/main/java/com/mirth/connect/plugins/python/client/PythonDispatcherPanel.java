/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.client;

import com.mirth.connect.connectors.python.PythonDispatcherProperties;
import com.mirth.connect.donkey.model.channel.ConnectorProperties;

public final class PythonDispatcherPanel extends PythonConnectorPanel {
    @Override public String getConnectorName() { return PythonDispatcherProperties.NAME; }
    @Override public ConnectorProperties getDefaults() { return new PythonDispatcherProperties(); }
}
