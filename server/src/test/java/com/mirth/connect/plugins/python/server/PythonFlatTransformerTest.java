/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.Test;
import com.mirth.connect.plugins.python.PythonApi;
import com.mirth.connect.plugins.python.PythonContract;

class PythonFlatTransformerTest {
    private static final List<String> PHASES = List.of(PythonContract.ROLE_SOURCE_TRANSFORMER,
            PythonContract.ROLE_DESTINATION_TRANSFORMER, PythonContract.ROLE_RESPONSE_TRANSFORMER);

    @Test void directJsonAndRawGlobalsUseFreshHelpersAndStockLogCategoriesOnReusedWorkers() throws Exception {
        String source = """
                assert 'ctx' not in globals() and 'output' not in globals()
                assert 'helper_state' not in globals()
                helper_state = []
                def transform(unused, another):
                    raise AssertionError('Helpers must not run implicitly')
                def edit(value):
                    helper_state.append('called')
                    if isinstance(value, dict):
                        value['trace'].append('python')
                        return value
                    return value + '|python'
                msg = edit(msg)
                tmp = edit(tmp)
                assert helper_state == ['called', 'called']
                channelMap['phase'] = phase
                logger.info('flat-info')
                logger.warning('flat-warning')
                logger.error('flat-error')
                logger.debug('flat-debug')
                print('flat-print')
                """;
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            var deployment = PythonXmlRoundTripTest.deploy(fixture, source);
            for (String phase : PHASES) {
                String category = phase.equals(PythonContract.ROLE_RESPONSE_TRANSFORMER) ? "response" : "transformer";
                var logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(category);
                Level originalLevel = logger.getLevel();
                var logs = new CapturedLogs(); logs.start(); logger.addAppender(logs); logger.setLevel(Level.DEBUG);
                try {
                    for (boolean json : List.of(true, false)) {
                        String setup = json ? "var msg={trace:['before']}; var tmp={trace:['template']};"
                                : "var msg=new String('before'); var tmp=new String('template');";
                        PythonXmlRoundTripTest.run(fixture, deployment, phase, setup, "flat-" + phase + "-" + json, session -> {
                            session.executePython();
                            long starts = fixture.runtime.executor.workerStarts.get();
                            session.executePython();
                            assertEquals(starts, fixture.runtime.executor.workerStarts.get(), "The second call must reuse its worker");
                            assertEquals(json ? "before,python,python" : "before|python|python",
                                    session.text(json ? "msg.trace.join(',')" : "String(msg)"));
                            assertEquals(json ? "template,python,python" : "template|python|python",
                                    session.text(json ? "tmp.trace.join(',')" : "String(tmp)"));
                            assertEquals(phase, session.channelMap().get("phase"));
                        });
                    }
                    for (var expected : Map.of("info", Level.INFO, "warning", Level.WARN, "error", Level.ERROR,
                            "debug", Level.DEBUG, "print", Level.INFO).entrySet()) {
                        var events = logs.events.stream().filter(event -> event.getMessage().getFormattedMessage().endsWith("flat-" + expected.getKey())).toList();
                        assertEquals(4, events.size(), expected.getKey());
                        for (LogEvent event : events) {
                            assertEquals(category, event.getLoggerName()); assertEquals(expected.getValue(), event.getLevel());
                            assertTrue(event.getMessage().getFormattedMessage().startsWith("[Runtime integration / "));
                        }
                    }
                } finally {
                    logger.removeAppender(logs); logger.setLevel(originalLevel); logs.stop();
                }
            }
        }
    }

    @Test void validationAndDeploymentCompileTopLevelCodeWithoutExecutingIt() throws Exception {
        try (var fixture = new PythonRuntimeIntegrationTest.Fixture()) {
            var marker = fixture.cache.resolve("flat-executed");
            String source = "open(" + PythonApi.mapper().writeValueAsString(marker.toString())
                    + ", 'w').write('ran')\nmsg = msg + '|executed'\n";
            var profile = fixture.registry.snapshot().profiles.get(fixture.profileId);
            var request = new PythonApi.ScriptRequest(); request.requestId = UUID.randomUUID().toString();
            request.role = PythonContract.ROLE_SOURCE_TRANSFORMER; request.pythonScript = source;
            request.environmentProfileId = fixture.profileId; request.expectedRegistryEpoch = fixture.registry.epoch();
            request.expectedEnvironmentRevision = profile.revision;
            assertEquals("valid", fixture.api.validate(request).status);
            assertFalse(Files.exists(marker));
            var deployment = PythonXmlRoundTripTest.deploy(fixture, source);
            assertFalse(Files.exists(marker), "Deployment must only compile the script");
            PythonXmlRoundTripTest.run(fixture, deployment, PHASES.get(0), "var msg='before';", "flat-compile-only", session -> {
                session.executePython(); assertEquals("before|executed", session.text("msg"));
            });
            assertEquals("ran", Files.readString(marker));
            request.pythonScript = "msg = (";
            var invalid = fixture.api.validate(request);
            assertEquals("invalid", invalid.status);
            assertEquals("PYTHON_SYNTAX", invalid.diagnostics.get(0).code);
            assertEquals(1, invalid.diagnostics.get(0).line);
        }
    }

    private static final class CapturedLogs extends AbstractAppender {
        final List<LogEvent> events = new CopyOnWriteArrayList<>();
        CapturedLogs() { super("flat-test", null, PatternLayout.createDefaultLayout(), false, Property.EMPTY_ARRAY); }
        @Override public void append(LogEvent event) { events.add(event.toImmutable()); }
    }
}
