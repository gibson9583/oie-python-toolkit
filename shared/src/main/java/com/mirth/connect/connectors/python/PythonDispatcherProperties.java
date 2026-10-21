/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.connectors.python;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.model.channel.ConnectorPluginProperties;
import com.mirth.connect.donkey.model.channel.DestinationConnectorProperties;
import com.mirth.connect.donkey.model.channel.DestinationConnectorPropertiesInterface;
import com.mirth.connect.donkey.util.DonkeyElement;
import com.mirth.connect.plugins.python.PythonContract;
import com.mirth.connect.plugins.python.PythonScriptConfiguration;

/** Python Writer: a destination script whose result supplies the response. */
public class PythonDispatcherProperties extends ConnectorProperties
        implements DestinationConnectorPropertiesInterface, PythonScriptConfiguration {

    public static final String NAME = PythonContract.WRITER_NAME;

    private DestinationConnectorProperties destinationConnectorProperties;
    private String pythonScript;
    private String environmentProfileId;
    private String environmentNameHint;
    private int timeoutMillis;
    private int contractVersion;

    public PythonDispatcherProperties() {
        setPluginProperties(new HashSet<>());
        destinationConnectorProperties = new DestinationConnectorProperties(false);
        pythonScript = "";
        environmentProfileId = PythonContract.DEFAULT_PROFILE_ID;
        environmentNameHint = PythonContract.DEFAULT_PROFILE_NAME;
        timeoutMillis = PythonContract.DEFAULT_TIMEOUT_MILLIS;
        contractVersion = PythonContract.CONTRACT_VERSION;
    }

    /** Stock XStream can bypass constructors and leave an absent set null. */
    @Override public synchronized Set<ConnectorPluginProperties> getPluginProperties() {
        if (super.getPluginProperties() == null) super.setPluginProperties(new HashSet<>());
        return super.getPluginProperties();
    }

    @Override public synchronized void setPluginProperties(Set<ConnectorPluginProperties> properties) {
        super.setPluginProperties(properties == null ? new HashSet<>() : properties);
    }

    public PythonDispatcherProperties(PythonDispatcherProperties props) {
        super(props);
        destinationConnectorProperties = new DestinationConnectorProperties(props.getDestinationConnectorProperties());
        pythonScript = props.getPythonScript();
        environmentProfileId = props.getEnvironmentProfileId();
        environmentNameHint = props.getEnvironmentNameHint();
        timeoutMillis = props.getTimeoutMillis();
        contractVersion = props.getContractVersion();
    }

    @Override
    public String getProtocol() {
        return PythonContract.PROTOCOL;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String toFormattedString() {
        return "ENVIRONMENT: " + environmentNameHint + " (" + environmentProfileId + ")\n";
    }

    @Override
    public DestinationConnectorProperties getDestinationConnectorProperties() {
        return destinationConnectorProperties;
    }

    @Override
    public boolean canValidateResponse() {
        return true;
    }

    @Override
    public ConnectorProperties clone() {
        return new PythonDispatcherProperties(this);
    }

    @Override
    public Map<String, Object> getPurgedProperties() {
        Map<String, Object> purged = new HashMap<>();
        purged.put("destinationConnectorProperties", destinationConnectorProperties.getPurgedProperties());
        purged.put("pythonScriptLines", pythonScript == null ? 0 : pythonScript.split("\r\n|\r|\n").length);
        purged.put("timeoutMillis", timeoutMillis);
        return purged;
    }

    @Override
    public void migrate3_0_1(DonkeyElement element) {}

    @Override
    public void migrate3_0_2(DonkeyElement element) {}

    @Override
    public boolean equals(Object obj) {
        return EqualsBuilder.reflectionEquals(this, obj);
    }

    @Override
    public int hashCode() {
        return HashCodeBuilder.reflectionHashCode(this);
    }

    @Override
    public String getPythonScript() {
        return pythonScript;
    }

    public void setPythonScript(String pythonScript) {
        this.pythonScript = pythonScript;
    }

    @Override
    public String getEnvironmentProfileId() {
        return environmentProfileId;
    }

    public void setEnvironmentProfileId(String environmentProfileId) {
        this.environmentProfileId = environmentProfileId;
    }

    @Override
    public String getEnvironmentNameHint() {
        return environmentNameHint;
    }

    public void setEnvironmentNameHint(String environmentNameHint) {
        this.environmentNameHint = environmentNameHint;
    }

    @Override
    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public int getContractVersion() {
        return contractVersion;
    }

    public void setContractVersion(int contractVersion) {
        this.contractVersion = contractVersion;
    }
}
