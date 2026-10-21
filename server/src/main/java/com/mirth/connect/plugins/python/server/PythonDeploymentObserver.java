/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import com.mirth.connect.donkey.model.event.DeployedStateEventType;
import com.mirth.connect.donkey.model.event.ErrorEventType;
import com.mirth.connect.donkey.model.event.Event;
import com.mirth.connect.donkey.server.channel.Channel;
import com.mirth.connect.donkey.server.event.DeployedStateEvent;
import com.mirth.connect.donkey.server.event.ErrorEvent;
import com.mirth.connect.donkey.server.event.EventType;
import com.mirth.connect.server.controllers.EventController;
import com.mirth.connect.server.event.EventListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Stock 4.6 dispatch calls each registered queue.put inline. Its channel-level
 * DEPLOYED event occurs after connector deployment and before start/recovery.
 * This queue observes that exact boundary without enqueuing or running Python.
 */
final class PythonDeploymentObserver extends EventListener implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger(PythonDeploymentObserver.class);
    private final Object lifecycle = new Object();
    private final PythonRuntime runtime;
    private final EventController events;
    private boolean closed;
    private boolean detached;
    private final BlockingQueue<Event> inline = new LinkedBlockingQueue<Event>() {
        private static final long serialVersionUID = 1L;
        @Override public void put(Event event) throws InterruptedException {
            if (Thread.interrupted()) throw new InterruptedException("Deployment event dispatch was interrupted");
            synchronized (lifecycle) {
                if (!closed) observe(event);
            }
        }
    };

    PythonDeploymentObserver(PythonRuntime runtime, EventController events) {
        this.runtime = runtime;
        this.events = events;
    }

    @Override public BlockingQueue<Event> getQueue() { return inline; }
    @Override public Set<EventType> getEventTypes() { return Set.of(EventType.DEPLOY_STATE); }
    @Override protected void processEvent(Event event) { /* Inline queue owns observation. */ }
    @Override protected void onShutdown() {
        synchronized (lifecycle) { closed = true; }
    }

    private void observe(Event event) {
        if (!(event instanceof DeployedStateEvent)) return;
        DeployedStateEvent state = (DeployedStateEvent) event;
        String channelId = null;
        try {
            channelId = state.getChannelId();
            if (state.getMetaDataId() != null || state.getState() != DeployedStateEventType.DEPLOYED
                    || !runtime.deployments.hasPending(channelId)) return;
            Channel actual = runtime.engine.deployedChannel(channelId);
            if (actual == null) return;
            runtime.deployments.activate(actual);
        } catch (Throwable failure) {
            // A listener failure must not escape stock dispatch. Installation
            // failures stay PREPARED; a previous binding's cleanup may fail
            // after the fully installed new binding has become ACTIVE.
            try {
                logger.error("Python activation or previous-deployment cleanup failed for channel " + channelId
                        + "; inspect the channel and runtime logs", failure);
                events.dispatchEvent(new ErrorEvent(channelId, null, null, ErrorEventType.DEPLOY_SCRIPT,
                        null, null, "Python Runtime activation or previous-deployment cleanup failed; inspect the channel and runtime logs", failure));
            } catch (Throwable reporting) {
                if (reporting != failure) failure.addSuppressed(reporting);
                try { logger.warn("Python activation error reporting failed", reporting); }
                catch (Throwable unavailableLogger) { /* Preserve the binding's actual admission state. */ }
            }
        }
    }

    @Override public void close() {
        synchronized (lifecycle) {
            closed = true;
            if (detached) return;
            detached = true;
        }
        try { events.removeListener(this); }
        finally { shutdown(); }
    }
}
