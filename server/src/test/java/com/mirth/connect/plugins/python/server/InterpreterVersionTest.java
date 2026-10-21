/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class InterpreterVersionTest {
    @Test void versionPolicyRejectsRetiredVersionsAndAcceptsTheSupportedMinimum() {
        for (String version : new String[]{"3.9.25", "3.10.19", "2.7.18", "invalid", "3"}) {
            assertFalse(Interpreters.supportedVersion(version), version);
        }
        for (String version : new String[]{"3.11.14", "3.12.12", "3.13.9", "3.14.0"}) {
            assertTrue(Interpreters.supportedVersion(version), version);
        }
    }
}
