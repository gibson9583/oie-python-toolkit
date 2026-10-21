/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.mirth.connect.plugins.python.PythonApi;
import com.mirth.connect.plugins.python.PythonApi.Profile;
import com.mirth.connect.plugins.python.PythonApi.Registry;
import com.mirth.connect.server.controllers.EventController;

/** Regression for the stock loader's read/defaults/legacy-write/init ordering. */
class PythonStartupDefaultsTest {
    private static final class Fixture {
        final EngineRegistryPersistence persistence = mock(EngineRegistryPersistence.class);
        final RegistryStoreTest.Memory memory = new RegistryStoreTest.Memory();
        final RegistryStore registry = new RegistryStore(memory);
        final EventController events = mock(EventController.class);
        final AtomicInteger creations = new AtomicInteger();
        final PythonServicePlugin service = new PythonServicePlugin(persistence, registry, () -> {
            creations.incrementAndGet();
            throw new AssertionError("Getting defaults must not create a runtime");
        }, events);

        void existing(String json) throws Exception {
            memory.json = json;
            when(persistence.read()).thenReturn(json);
        }

        void assertReadOnly() throws Exception {
            verify(persistence).read();
            verify(persistence, never()).write(any(), any(), any());
            verify(persistence, never()).update(any());
            verifyNoInteractions(events);
            assertEquals(0, creations.get());
            assertNull(registry.storedJson(), "Defaults must not publish an uninitialized registry");
        }
    }

    private static Registry registryWithProfile() {
        Registry registry = new Registry();
        registry.registryEpoch = "existing-startup-epoch";
        registry.registryRevision = 17;
        Profile profile = new Profile();
        profile.id = "7b768201-7c16-49e6-bb48-efaf7564c444";
        profile.name = "Existing environment";
        profile.runtimeId = "approved-python";
        profile.packageSourceId = "approved-offline";
        profile.requirementsText = "";
        profile.revision = 3;
        profile.definitionFingerprint = PreparationManager.definitionFingerprint(profile);
        registry.profiles.put(profile.id, profile);
        return registry;
    }

    private static String json(Registry registry) throws IOException {
        // Noncanonical whitespace proves the loader does not reserialize,
        // rotate the epoch, clear receipts or otherwise rewrite existing bytes.
        return PythonApi.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(registry);
    }

    @Test void existingRegistryIsValidatedAndReturnedByteForByteWithoutInitialization() throws Exception {
        Fixture fixture = new Fixture();
        String existing = json(registryWithProfile());
        fixture.existing(existing);
        Properties defaults = fixture.service.getDefaultProperties();
        assertEquals(1, defaults.size());
        assertEquals(existing, defaults.getProperty(RegistryStore.PROPERTY_KEY));
        assertEquals(existing, fixture.memory.json);
        fixture.assertReadOnly();
    }

    @Test void legacyReadFailureCannotReplaceExistingProfilesWithEmptyDefaults() throws Exception {
        Fixture fixture = new Fixture();
        String existing = json(registryWithProfile());
        fixture.existing(existing);
        // Reproduce DefaultExtensionController's public stock loader ordering:
        // a failed legacy read returns empty Properties, missing defaults are
        // merged, then the merged document is written before service.init().
        Properties legacyReadAfterFailure = new Properties();
        Properties defaults = fixture.service.getDefaultProperties();
        for (Object key : defaults.keySet()) {
            if (!legacyReadAfterFailure.containsKey(key)) {
                legacyReadAfterFailure.put(key, defaults.get(key));
            }
        }
        fixture.memory.json = legacyReadAfterFailure.getProperty(RegistryStore.PROPERTY_KEY);
        Registry saved = fixture.registry.parse(fixture.memory.json);
        assertEquals(existing, fixture.memory.json);
        assertEquals("existing-startup-epoch", saved.registryEpoch);
        assertEquals(17, saved.registryRevision);
        assertEquals(1, saved.profiles.size());
        fixture.assertReadOnly();
    }

    @Test void readFailureStopsStartupBeforeFreshDefaultsOrRuntimeSideEffects() throws Exception {
        Fixture fixture = new Fixture();
        IOException unavailable = new IOException("checked database read unavailable");
        when(fixture.persistence.read()).thenThrow(unavailable);
        IllegalStateException failure = assertThrows(IllegalStateException.class, fixture.service::getDefaultProperties);
        assertSame(unavailable, failure.getCause());
        assertTrue(failure.getMessage().contains("startup is stopped to preserve existing profiles"));
        fixture.assertReadOnly();
    }

    private void assertInvalid(String existing) throws Exception {
        Fixture fixture = new Fixture();
        fixture.existing(existing);
        IllegalStateException failure = assertThrows(IllegalStateException.class, fixture.service::getDefaultProperties);
        assertNotNull(failure.getCause());
        assertEquals(existing, fixture.memory.json, "Invalid data must remain available for diagnosis");
        fixture.assertReadOnly();
    }

    @Test void malformedOrBlankPresentRegistryCannotBecomeFreshDefaults() throws Exception {
        for (String existing : new String[] {"not JSON", "", "   ", "null", "[]"}) assertInvalid(existing);
    }

    @Test void unsupportedExistingSchemaStopsStartup() throws Exception {
        Registry registry = registryWithProfile();
        registry.schemaVersion = PythonApi.REGISTRY_SCHEMA_VERSION + 1;
        assertInvalid(json(registry));
    }

    @Test void forgedExistingDefinitionStopsStartup() throws Exception {
        Registry registry = registryWithProfile();
        registry.profiles.values().iterator().next().definitionFingerprint = "0".repeat(64);
        assertInvalid(json(registry));
    }

    @Test void checkedAbsenceAloneCreatesValidEmptyDefaultsWithoutWriting() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.persistence.read()).thenReturn(null);
        Properties defaults = fixture.service.getDefaultProperties();
        Registry created = fixture.registry.parse(defaults.getProperty(RegistryStore.PROPERTY_KEY));
        assertDoesNotThrow(() -> java.util.UUID.fromString(created.registryEpoch));
        assertEquals(0, created.registryRevision);
        assertTrue(created.profiles.isEmpty());
        assertTrue(created.recentMutationReceipts.isEmpty());
        assertNull(fixture.memory.json);
        fixture.assertReadOnly();
    }
}
