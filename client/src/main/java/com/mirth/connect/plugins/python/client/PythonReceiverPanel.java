/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.client;

import com.mirth.connect.connectors.python.PythonReceiverProperties;
import com.mirth.connect.donkey.model.channel.ConnectorProperties;

public final class PythonReceiverPanel extends PythonConnectorPanel {
    @Override public String getConnectorName() { return PythonReceiverProperties.NAME; }
    @Override public ConnectorProperties getDefaults() { return new PythonReceiverProperties(); }
}
