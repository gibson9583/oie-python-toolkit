/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python;

/** Fields every Python surface persists: the step, the Reader and the Writer. */
public interface PythonScriptConfiguration {

    /** Non-null only for callable code templates. */
    default String getCodeTemplateFunctionName() { return null; }

    String getPythonScript();

    String getEnvironmentProfileId();

    String getEnvironmentNameHint();

    int getTimeoutMillis();

    int getContractVersion();
}
