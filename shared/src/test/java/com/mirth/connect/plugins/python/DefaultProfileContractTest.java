/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.mirth.connect.connectors.python.PythonDispatcherProperties;
import com.mirth.connect.connectors.python.PythonReceiverProperties;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.Xpp3Driver;

class DefaultProfileContractTest {
    @Test void newlyCreatedModelsSelectOneStableStandardLibraryEnvironment() {
        XStream stream = new XStream(new Xpp3Driver());
        stream.allowTypesByWildcard(new String[] { "com.mirth.connect.**" });
        for (PythonScriptConfiguration model : List.of(
                new PythonStep(), new PythonReceiverProperties(), new PythonDispatcherProperties())) {
            assertEquals("232030e4-aa0d-39fc-a889-fed1318ef412", model.getEnvironmentProfileId());
            assertEquals(PythonContract.DEFAULT_PROFILE_NAME, model.getEnvironmentNameHint());
            assertEquals(model instanceof PythonStep ? "" : "return None\n", model.getPythonScript());
            PythonScriptConfiguration restored = (PythonScriptConfiguration) stream.fromXML(stream.toXML(model));
            assertEquals(model.getPythonScript(), restored.getPythonScript());
        }
        assertEquals("return arguments[0] if arguments else None\n",
                new PythonCodeTemplateProperties().getPythonScript());
    }

    @Test void legacyNullEmptyAndWhitespaceReferencesRemainUnmodifiedThroughCopyAndSerialization() {
        XStream stream = new XStream(new Xpp3Driver()); stream.allowTypesByWildcard(new String[] { "com.mirth.connect.**" });
        for (String original : new String[] { null, "", " ", "unknown" }) {
            PythonStep step = new PythonStep(); step.setEnvironmentProfileId(original);
            String digest = PythonContract.specDigest(step);
            assertEquals(original, new PythonStep(step).getEnvironmentProfileId());
            PythonStep restored = (PythonStep) stream.fromXML(stream.toXML(step));
            assertEquals(original, restored.getEnvironmentProfileId()); assertEquals(digest, PythonContract.specDigest(restored));
            assertEquals(original == null ? "" : original, PythonContract.executionSpec(restored).get("profileId"));
            PythonDispatcherProperties writer = new PythonDispatcherProperties(); writer.setEnvironmentProfileId(original);
            assertEquals(original, ((PythonDispatcherProperties) writer.clone()).getEnvironmentProfileId());
            PythonReceiverProperties reader = new PythonReceiverProperties(); reader.setEnvironmentProfileId(original);
            assertEquals(original, ((PythonReceiverProperties) stream.fromXML(stream.toXML(reader))).getEnvironmentProfileId());
        }
    }

    @Test void defaultAndLegacyBlankDigestsAreDistinctWithoutImplicitMutation() {
        PythonStep fresh = new PythonStep(); PythonStep legacy = new PythonStep(); legacy.setEnvironmentProfileId("");
        fresh.setPythonScript("pass\n");
        legacy.setPythonScript(fresh.getPythonScript());
        assertEquals("1c6bbd5632c67f08c1ed628e1783de904ac83fc66510ae8821465b143566341e", PythonContract.specDigest(legacy));
        assertNotEquals(PythonContract.specDigest(legacy), PythonContract.specDigest(fresh));
        legacy.setEnvironmentProfileId(null);
        assertEquals("1c6bbd5632c67f08c1ed628e1783de904ac83fc66510ae8821465b143566341e", PythonContract.specDigest(legacy));
        legacy.setEnvironmentProfileId(" "); assertNotEquals(PythonContract.specDigest(fresh), PythonContract.specDigest(legacy));
    }
}
