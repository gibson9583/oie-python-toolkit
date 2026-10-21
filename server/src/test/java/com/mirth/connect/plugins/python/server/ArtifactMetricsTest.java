/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactMetricsTest {
    @TempDir Path temp;

    @Test void cacheSizeRemainsAvailableWhileBuildDirectoriesMove() throws Exception {
        var store = new ArtifactStore(temp);
        Path moving = Files.createDirectory(store.root().resolve("moving-build"));
        Path parked = temp.resolve("parked-build");
        for (int i = 0; i < 32; i++)
            Files.writeString(Files.createDirectory(moving.resolve("package-" + i)).resolve("module.py"), "x=1\n");
        var stop = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        Thread publisher = new Thread(() -> {
            try {
                while (!stop.get()) {
                    Files.move(moving, parked);
                    Files.move(parked, moving);
                }
            } catch (Throwable caught) { failure.set(caught); }
        }, "owned-artifact-metric-publisher");
        publisher.start();
        try {
            for (int i = 0; i < 1000; i++) {
                long bytes = assertDoesNotThrow(store::sizeBytes,
                    "Best-effort runtime metrics must tolerate concurrent environment publication/cleanup");
                assertTrue(bytes >= -1);
            }
        } finally {
            stop.set(true);
            publisher.join(10000);
            assertFalse(publisher.isAlive(), "Owned filesystem publisher must stop before fixture cleanup");
        }
        assertNull(failure.get());
    }
}
