/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperatorConfigTest {
    @TempDir Path temp;
    private OperatorConfig config(String key, String value) {
        Map<String, String> settings = Map.of(OperatorConfig.PROPERTY_PREFIX + key, value);
        return new OperatorConfig(name -> null, settings::get, temp.toString(), "test");
    }
    @Test void limitsRejectOverflowNegativeAndNonNumericValues() {
        for (String value : new String[]{"2147483648", "4294967296", "-1", "9223372036854775807", "many"}) {
            assertThrows(IllegalArgumentException.class, () -> config("maxWorkers", value));
        }
        for (String value : new String[]{"-1", "9223372036854775807", "many"}) {
            assertThrows(IllegalArgumentException.class, () -> config("prepareTimeoutMillis", value));
        }
        assertEquals(0, config("maxQueuedAcquisitions", "0").maxQueuedAcquisitions);
    }
    @Test void runtimeAndSourcePolicyMustBeExplicitAndUnambiguous() {
        for (String value : new String[]{"", "x=python3,x=other", "bad id=python3", "missing"}) {
            assertThrows(IllegalArgumentException.class, () -> config("runtimes", value));
        }
        assertEquals(java.util.Set.of(com.mirth.connect.plugins.python.PythonContract.BUILTIN_SOURCE_ID), config("sources", "").sources.keySet());
        assertThrows(IllegalArgumentException.class, () -> config("sources", com.mirth.connect.plugins.python.PythonContract.BUILTIN_SOURCE_ID));
        assertThrows(IllegalArgumentException.class, () -> config("sources", "pypi,pypi"));
    }
    @Test void environmentPolicyOverridesSystemProperty() {
        OperatorConfig config = new OperatorConfig(name -> name.equals(OperatorConfig.ENV_PREFIX + "MAXWORKERS") ? "3" : null,
                name -> name.equals(OperatorConfig.PROPERTY_PREFIX + "maxWorkers") ? "5" : null, temp.toString(), "test");
        assertEquals(3, config.maxWorkers);
    }
    @Test void exportedSourcePolicyIdentityCannotExposePathsOrCredentials() throws Exception {
        Map<String,String> settings = Map.of("sources", "private", "source.private.indexUrl", "https://user:secret@repo.example/simple",
                "source.private.wheelhouse", "/private/local/wheelhouse", "source.private.username", "user", "source.private.password", "secret");
        OperatorConfig config = new OperatorConfig(name -> null,
                name -> settings.get(name.substring(OperatorConfig.PROPERTY_PREFIX.length())), temp.toString(), "test");
        String identity = config.sources.get("private").policyIdentity();
        assertTrue(identity.matches("[0-9a-f]{64}"));
        assertFalse(identity.contains("secret"));
        assertFalse(identity.contains("/private"));
        com.mirth.connect.plugins.python.PythonApi.ResolvedLock lock = new com.mirth.connect.plugins.python.PythonApi.ResolvedLock();
        lock.packageSourcePolicy = identity;
        String exported = com.mirth.connect.plugins.python.PythonApi.mapper().writeValueAsString(lock);
        assertFalse(exported.contains("wheelhouse"));
        assertFalse(exported.contains("repo.example"));
        assertFalse(exported.contains("secret"));
    }
    @Test void pluginOwnedPolicyWorksWithoutEngineConfigurationAndKeepsOverrideOrder() throws Exception {
        java.nio.file.Files.writeString(temp.resolve("operator.properties"),"runtimes=approved=/local/python3.14\nmaxWorkers=4\nsource.pypi.password=private\n");
        var policy=OperatorConfig.nodeProperties(temp,name->name.equals(OperatorConfig.PROPERTY_PREFIX+"maxWorkers")?"5":null);
        OperatorConfig config=new OperatorConfig(name->null,policy,temp.toString(),"test");
        assertEquals("/local/python3.14",config.runtimes.get("approved").executable);assertEquals(5,config.maxWorkers);
        assertEquals("private",config.sources.get("pypi").password);
        OperatorConfig env=new OperatorConfig(name->name.equals(OperatorConfig.ENV_PREFIX+"MAXWORKERS")?"6":null,policy,temp.toString(),"test");
        assertEquals(6,env.maxWorkers);
        assertTrue(config.sources.get("pypi").policyIdentity().matches("[0-9a-f]{64}"));
    }
    @Test void malformedOperatorFileFailsBeforeRuntimeWork() throws Exception {
        java.nio.file.Files.createDirectory(temp.resolve("operator.properties"));
        assertThrows(IllegalArgumentException.class,()->OperatorConfig.nodeProperties(temp,name->null));
    }
}
