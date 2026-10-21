/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirth.connect.server.controllers.EventController;

class PythonServiceLifecycleTest {
    @TempDir Path cache;

    private RegistryStore store() throws Exception {
        RegistryStore store = new RegistryStore(new PythonRuntimeIntegrationTest.Memory());
        store.load(null); return store;
    }
    private PythonRuntime runtime(RegistryStore store) throws Exception {
        OperatorConfig config = new OperatorConfig(key -> null, key -> null, cache.toString(), "service-fixture");
        return spy(new PythonRuntime(config, store, mock(EngineAccess.class)));
    }
    private Set<Thread> observers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().equals("PythonDeploymentObserver Consumer Thread"))
                .collect(java.util.stream.Collectors.toSet());
    }
    private void assertStopped(Set<Thread> before) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (observers().stream().filter(thread -> !before.contains(thread)).noneMatch(Thread::isAlive)) return;
            Thread.sleep(10);
        }
        fail("A plugin-owned observer thread survived service cleanup");
    }

    @Test void stockMixedInterfaceDuplicateStartAndStopRunOnlyOnce() throws Exception {
        RegistryStore store = store(); PythonRuntime runtime = runtime(store);
        EventController events = mock(EventController.class);
        var service = new PythonServicePlugin(null, store, () -> runtime, events);
        Set<Thread> before = observers();
        try {
            service.init(new Properties()); service.start(); service.start();
            verify(runtime).start(); verify(events).addListener(any());
            assertNotNull(PythonServicePlugin.api());
            service.stop(); service.stop();
            verify(runtime).stop(); verify(events).removeListener(any());
            assertThrows(IllegalStateException.class, PythonServicePlugin::api);
        } finally { service.stop(); runtime.stop(); }
        assertStopped(before);
    }

    @Test void registrationFailureClosesListenerRuntimeAndAllowsFreshRetry() throws Exception {
        RegistryStore store = store(); PythonRuntime first = runtime(store), next = runtime(store);
        EventController events = mock(EventController.class);
        IllegalStateException failure = new IllegalStateException("listener registration failed");
        doThrow(failure).doNothing().when(events).addListener(any());
        List<PythonRuntime> candidates = new ArrayList<>(List.of(first, next));
        var service = new PythonServicePlugin(null, store, () -> candidates.remove(0), events);
        Set<Thread> before = observers();
        try {
            assertSame(failure, assertThrows(IllegalStateException.class, service::start));
            verify(first).stop(); verify(events).removeListener(any());
            assertThrows(IllegalStateException.class, PythonServicePlugin::api);
            service.start(); service.start();
            verify(next).start(); verify(events, times(2)).addListener(any());
            service.stop(); verify(next).stop();
        } finally { service.stop(); first.stop(); next.stop(); }
        assertStopped(before);
    }

    @Test void stopBeforeStartIsIdempotentAndInitializationCanRestartCleanly() throws Exception {
        RegistryStore store = store(); PythonRuntime first = runtime(store), next = runtime(store);
        EventController events = mock(EventController.class);
        List<PythonRuntime> candidates = new ArrayList<>(List.of(first, next));
        var service = new PythonServicePlugin(null, store, () -> candidates.remove(0), events);
        Set<Thread> before = observers();
        try {
            service.init(new Properties()); service.stop(); service.stop();
            verify(first).stop(); verify(first, never()).start(); verifyNoInteractions(events);
            service.start(); verify(next).start(); service.stop();
        } finally { service.stop(); first.stop(); next.stop(); }
        assertStopped(before);
    }
}
