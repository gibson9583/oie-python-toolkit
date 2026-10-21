/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ContextFactoryController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.util.javascript.JavaScriptUtil;

/** Runs the actual installed stock JavaScriptUtil.execute/JavaScriptTask path.
 * Only the private executor is swapped to allow deterministic owned-thread
 * cleanup. Mocked factory initialization prevents any shared database access. */
final class StockJavaScriptTestHarness implements AutoCloseable {
    private final ExecutorService owned = Executors.newCachedThreadPool();
    private final ExecutorService previous;
    private final Field executor;
    StockJavaScriptTestHarness() throws Exception {
        ControllerFactory factory = mock(ControllerFactory.class);
        ConfigurationController configuration = mock(ConfigurationController.class);
        when(configuration.getServerId()).thenReturn("owned-test-node");
        when(factory.createConfigurationController()).thenReturn(configuration);
        when(factory.createContextFactoryController()).thenReturn(mock(ContextFactoryController.class));
        try (var controllers = mockStatic(ControllerFactory.class)) {
            controllers.when(ControllerFactory::getFactory).thenReturn(factory);
            Class.forName(JavaScriptUtil.class.getName(), true, JavaScriptUtil.class.getClassLoader());
        }
        executor = JavaScriptUtil.class.getDeclaredField("executor"); executor.setAccessible(true);
        previous = (ExecutorService) executor.get(null); executor.set(null, owned);
    }
    @Override public void close() throws Exception {
        executor.set(null, previous);
        owned.shutdownNow();
        if (!owned.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("Owned JavaScript task executor did not terminate");
    }
}
