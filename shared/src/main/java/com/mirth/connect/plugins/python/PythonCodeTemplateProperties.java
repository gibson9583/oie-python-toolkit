/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python;

import java.util.LinkedHashMap;
import java.util.Map;
import com.mirth.connect.model.codetemplates.CodeTemplateProperties;

/** A callable Python function, transported through the stock JavaScript template compiler. */
public class PythonCodeTemplateProperties extends CodeTemplateProperties implements PythonScriptConfiguration {
    public static final String PLUGIN_POINT = "Python Function";
    private String functionName = "python_function";
    private String pythonScript = "return arguments[0] if arguments else None\n";
    private String environmentProfileId = PythonContract.DEFAULT_PROFILE_ID;
    private String environmentNameHint = PythonContract.DEFAULT_PROFILE_NAME;
    private int timeoutMillis = PythonContract.DEFAULT_TIMEOUT_MILLIS;
    private int contractVersion = PythonContract.CONTRACT_VERSION;

    public PythonCodeTemplateProperties() { super(CodeTemplateType.FUNCTION); }
    public PythonCodeTemplateProperties(PythonCodeTemplateProperties other) {
        this(); functionName = other.functionName; pythonScript = other.pythonScript;
        environmentProfileId = other.environmentProfileId; environmentNameHint = other.environmentNameHint;
        timeoutMillis = other.timeoutMillis; contractVersion = other.contractVersion;
    }
    @Override public String getPluginPointName() { return PLUGIN_POINT; }
    @Override public String getCode() { return PythonContract.templateWrapper(functionName, PythonContract.specDigest(this)); }
    @Override public PythonCodeTemplateProperties clone() { return new PythonCodeTemplateProperties(this); }
    @Override public Map<String, Object> getPurgedProperties() {
        Map<String, Object> out = new LinkedHashMap<>(); out.put("type", getType());
        out.put("pythonScriptLines", com.mirth.connect.donkey.util.purge.PurgeUtil.countLines(pythonScript));
        out.put("timeoutMillis", timeoutMillis); return out;
    }
    public String getFunctionName() { return functionName; }
    public void setFunctionName(String value) { functionName = value; }
    @Override public String getCodeTemplateFunctionName() { return functionName; }
    @Override public String getPythonScript() { return pythonScript; }
    public void setPythonScript(String value) { pythonScript = value; }
    @Override public String getEnvironmentProfileId() { return environmentProfileId; }
    public void setEnvironmentProfileId(String value) { environmentProfileId = value; }
    @Override public String getEnvironmentNameHint() { return environmentNameHint; }
    public void setEnvironmentNameHint(String value) { environmentNameHint = value; }
    @Override public int getTimeoutMillis() { return timeoutMillis; }
    public void setTimeoutMillis(int value) { timeoutMillis = value; }
    @Override public int getContractVersion() { return contractVersion; }
    public void setContractVersion(int value) { contractVersion = value; }
    @Override public com.mirth.connect.model.codetemplates.CodeTemplateFunctionDefinition getFunctionDefinition() {
        updateDocumentation(); return super.getFunctionDefinition();
    }
}
