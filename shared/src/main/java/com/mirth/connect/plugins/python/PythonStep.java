/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python;

import java.util.Map;

import com.mirth.connect.donkey.util.purge.PurgeUtil;
import com.mirth.connect.model.Step;

/**
 * A transformer step that runs Python in a managed environment.
 *
 * <p>Serialized with its fully qualified class name; no XStream alias, so CLI
 * tools that do not load extension annotations can still read it.</p>
 */
public class PythonStep extends Step implements PythonScriptConfiguration {

    public static final String PLUGIN_POINT = PythonContract.STEP_TYPE;

    private String pythonScript;
    private String environmentProfileId;
    private String environmentNameHint;
    private int timeoutMillis;
    private int contractVersion;

    public PythonStep() {
        pythonScript = "";
        environmentProfileId = PythonContract.DEFAULT_PROFILE_ID;
        environmentNameHint = PythonContract.DEFAULT_PROFILE_NAME;
        timeoutMillis = PythonContract.DEFAULT_TIMEOUT_MILLIS;
        contractVersion = PythonContract.CONTRACT_VERSION;
    }

    public PythonStep(PythonStep props) {
        super(props);
        pythonScript = props.getPythonScript();
        environmentProfileId = props.getEnvironmentProfileId();
        environmentNameHint = props.getEnvironmentNameHint();
        timeoutMillis = props.getTimeoutMillis();
        contractVersion = props.getContractVersion();
    }

    /** Pure: returns the bridge wrapper. Never resolves profiles or starts Python. */
    @Override
    public String getScript(boolean loadFiles) {
        return PythonContract.stepWrapper(PythonContract.specDigest(this));
    }

    @Override
    public String getType() {
        return PLUGIN_POINT;
    }

    @Override
    public Step clone() {
        return new PythonStep(this);
    }

    @Override
    public Map<String, Object> getPurgedProperties() {
        Map<String, Object> purged = super.getPurgedProperties();
        purged.put("pythonScriptLines", PurgeUtil.countLines(pythonScript));
        purged.put("timeoutMillis", timeoutMillis);
        return purged;
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
