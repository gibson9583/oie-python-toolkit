/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.nio.file.Paths;
import java.util.Properties;
import java.util.UUID;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ExtensionPermission;
import com.mirth.connect.model.ServerEventContext;
import com.mirth.connect.plugins.ChannelPlugin;
import com.mirth.connect.plugins.ServicePlugin;
import com.mirth.connect.plugins.python.PythonApi;
import com.mirth.connect.plugins.python.PythonContract;
import com.mirth.connect.plugins.python.PythonRuntimeServletInterface;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;

/** Stock 4.6 service with idempotent lifecycle: its loader registers mixed plugins twice. */
public final class PythonServicePlugin implements ServicePlugin, ChannelPlugin {
    private static volatile PythonServicePlugin active;
    private final EngineRegistryPersistence persistence;
    private final RegistryStore registry;
    private final java.util.function.Supplier<PythonRuntime> creator;
    private final com.mirth.connect.server.controllers.EventController events;
    private PythonDeploymentObserver observer;
    private PythonRuntime runtime;
    private RuntimeApi api;
    private boolean started;

    public PythonServicePlugin() {
        ConfigurationController configuration = ConfigurationController.getInstance();
        RegistryStore[] store = new RegistryStore[1];
        persistence = new EngineRegistryPersistence(configuration, ControllerFactory.getFactory().createExtensionController(), () -> store[0]);
        registry = new RegistryStore(persistence);
        store[0] = registry;
        events = ControllerFactory.getFactory().createEventController();
        creator = () -> {
            try {
                OperatorConfig config = new OperatorConfig(Paths.get(configuration.getApplicationDataDir(), "oie-python-toolkit").toString(), configuration.getServerId());
                return new PythonRuntime(config, registry, new EngineAccess.Live());
            } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot initialize Python Toolkit storage", failure); }
        };
    }

    PythonServicePlugin(EngineRegistryPersistence persistence, RegistryStore registry,
            java.util.function.Supplier<PythonRuntime> creator, com.mirth.connect.server.controllers.EventController events) {
        this.persistence = persistence; this.registry = registry; this.creator = creator; this.events = events;
    }

    @Override public String getPluginPointName() { return PythonContract.EXTENSION_NAME; }
    @Override public Properties getDefaultProperties() {
        try {
            // The stock loader writes defaults before init() and its legacy
            // read can return an empty Properties after a database failure.
            // Read independently and preserve the exact authoritative bytes.
            String existing = persistence.read();
            if (existing != null) {
                registry.parse(existing);
                return EngineRegistryPersistence.properties(existing);
            }
            PythonApi.Registry defaults = new PythonApi.Registry();
            defaults.registryEpoch = UUID.randomUUID().toString();
            return EngineRegistryPersistence.properties(PythonApi.mapper().writeValueAsString(defaults));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot safely load Python Toolkit registry defaults: storage is unavailable or invalid; startup is stopped to preserve existing profiles", failure);
        }
    }

    private void initializeRuntime() throws Exception {
        registry.load(null);
        runtime = creator.get();
        api = new RuntimeApi(runtime);
    }

    @Override public synchronized void init(Properties properties) {
        if (started) return;
        try { if (runtime == null) initializeRuntime(); }
        catch (Exception | Error failure) {
            cleanup(failure);
            throw new IllegalStateException("Python Toolkit initialization failed; inspect the runtime configuration and registry", failure);
        }
    }

    @Override public synchronized void start() {
        if (started) return;
        try {
            if (runtime == null) initializeRuntime();
            runtime.start();
            observer = new PythonDeploymentObserver(runtime, events);
            events.addListener(observer);
            active = this;
            started = true;
        } catch (Exception | Error failure) {
            cleanup(failure);
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new IllegalStateException("Cannot start Python Toolkit", failure);
        }
    }

    private void cleanup(Throwable primary) {
        started = false;
        if (active == this) active = null;
        PythonDeploymentObserver previousObserver = observer; observer = null;
        PythonRuntime previousRuntime = runtime; runtime = null; api = null;
        if (previousObserver != null) {
            try { previousObserver.close(); }
            catch (Throwable failure) { if (failure != primary) primary.addSuppressed(failure); }
        }
        if (previousRuntime != null) {
            try { previousRuntime.stop(); }
            catch (Throwable failure) { if (failure != primary) primary.addSuppressed(failure); }
        }
    }

    @Override public synchronized void stop() {
        Throwable failure = new IllegalStateException("Python Toolkit cleanup failed");
        cleanup(failure);
        if (failure.getSuppressed().length > 0) throw (IllegalStateException) failure;
    }

    @Override public synchronized void update(Properties properties) {
        try { persistence.update(properties); }
        catch (com.mirth.connect.client.core.ControllerException failure) {
            throw new IllegalStateException("Python Toolkit property replacement was rejected; restore a valid registry and retry", failure);
        }
    }
    static RuntimeApi api() {
        PythonServicePlugin service = active;
        if (service == null || service.api == null) throw new IllegalStateException("Python Toolkit is not running");
        return service.api;
    }
    static <T> T asActor(int actor, java.util.function.Supplier<T> work) {
        PythonServicePlugin service = active;
        if (service == null) throw new IllegalStateException("Python Toolkit is not running");
        return service.persistence.asActor(actor, work);
    }

    @Override public void save(Channel channel, ServerEventContext context) {}
    @Override public void remove(Channel channel, ServerEventContext context) {
        PythonRuntime current = PythonRuntime.current();
        if (current != null) current.deployments.retireUnpublished(channel.getId());
    }
    @Override public void deploy(Channel channel, ServerEventContext context) {
        PythonRuntime current = PythonRuntime.require();
        final Deployments.Binding binding;
        try { binding = current.deployments.prepare(channel); }
        catch (Deployments.DeployFailure failure) { throw new IllegalStateException(failure.getMessage(), failure); }
        if (binding != null) {
            try { current.engine.afterChannelTasks(channel.getId(), () -> current.deployments.completedAttempt(binding)); }
            catch (RuntimeException scheduling) {
                org.apache.logging.log4j.LogManager.getLogger(PythonServicePlugin.class).warn(
                        "Python completion task could not be queued; pending environment leases will be retained", scheduling);
            }
        }
    }
    @Override public void deploy(ServerEventContext context) {}
    @Override public void undeploy(String id, ServerEventContext context) {
        PythonRuntime current = PythonRuntime.current();
        if (current != null) current.deployments.retireUnpublished(id);
    }
    @Override public void undeploy(ServerEventContext context) {}
    @Override public ExtensionPermission[] getExtensionPermissions() {
        return new ExtensionPermission[] {
            permission(PythonRuntimeServletInterface.PERMISSION_VIEW, "View Python definitions, runtime options and visible usage", PythonRuntimeServletInterface.OP_RUNTIME,
                    PythonRuntimeServletInterface.OP_LIST, PythonRuntimeServletInterface.OP_GET, PythonRuntimeServletInterface.OP_REFERENCES, PythonRuntimeServletInterface.OP_EXPORT),
            permission(PythonRuntimeServletInterface.PERMISSION_MANAGE, "Manage and import Python environment profiles", PythonRuntimeServletInterface.OP_CREATE,
                    PythonRuntimeServletInterface.OP_UPDATE, PythonRuntimeServletInterface.OP_ARCHIVE, PythonRuntimeServletInterface.OP_IMPORT),
            permission(PythonRuntimeServletInterface.PERMISSION_PREPARE, "Prepare environments and inspect or cancel preparation jobs", PythonRuntimeServletInterface.OP_PREPARE,
                    PythonRuntimeServletInterface.OP_REFRESH, PythonRuntimeServletInterface.OP_JOB, PythonRuntimeServletInterface.OP_JOB_LOG, PythonRuntimeServletInterface.OP_JOB_CANCEL),
            permission(PythonRuntimeServletInterface.PERMISSION_VALIDATE, "Compile Python scripts without importing or executing them", PythonRuntimeServletInterface.OP_VALIDATE, PythonRuntimeServletInterface.OP_TEMPLATE_VALIDATE, PythonRuntimeServletInterface.OP_EDITOR),
            permission(PythonRuntimeServletInterface.PERMISSION_EXECUTE, "Execute a Python script against synthetic samples", PythonRuntimeServletInterface.OP_TEST)
        };
    }
    private ExtensionPermission permission(String name, String description, String... operations) {
        return new ExtensionPermission(getPluginPointName(), name, description, operations, new String[0]);
    }
}
