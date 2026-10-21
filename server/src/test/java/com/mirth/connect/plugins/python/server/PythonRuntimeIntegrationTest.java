/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import com.mirth.connect.connectors.python.PythonDispatcherProperties;
import com.mirth.connect.connectors.python.PythonReceiverProperties;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.RawMessage;
import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.channel.Channel;
import com.mirth.connect.donkey.server.channel.DestinationChainProvider;
import com.mirth.connect.plugins.python.CanonicalJson;
import com.mirth.connect.plugins.python.PythonApi;
import com.mirth.connect.plugins.python.PythonContract;
import com.mirth.connect.plugins.python.PythonStep;
import com.mirth.connect.model.Connector;
import com.mirth.connect.model.Transformer;
import com.mirth.connect.server.controllers.EventController;
import com.mirth.connect.userutil.ImmutableConnectorMessage;

/** Real offline venv preparation and persistent CPython execution through all adapters. */
class PythonRuntimeIntegrationTest {
    static class Memory implements RegistryStore.Persistence {
        String json;
        @Override public synchronized String read() { return json; }
        @Override public synchronized RegistryStore.WriteOutcome write(String expected, String next, String operation) {
            if (!java.util.Objects.equals(expected, json)) return RegistryStore.WriteOutcome.CONFLICT;
            json = next; return RegistryStore.WriteOutcome.COMMITTED;
        }
    }
    static class Fixture implements AutoCloseable {
        final Path cache = Files.createTempDirectory("oie-python-owned-test-");
        final Path evidence = Path.of("target", "integration-evidence", cache.getFileName().toString()).toAbsolutePath();
        final Memory persistence = new Memory();
        final RegistryStore registry = new RegistryStore(persistence);
        final PythonRuntime runtime;
        final RuntimeApi api;
        final String profileId;
        final String jobId;
        final List<Map<String, Object>> receipts = new ArrayList<>();
        Fixture() throws Exception { this(false); }
        Fixture(boolean builtInOnly) throws Exception {
            registry.load(null);
            Files.createDirectories(cache.resolve("offline"));
            Map<String, String> props = Map.of("sources", builtInOnly ? "" : "offline", "source.offline.wheelhouse", cache.resolve("offline").toString(),
                    "terminationGraceMillis", "1000", "maxWorkers", "2", "maxWorkersPerChannel", "1");
            OperatorConfig config = new OperatorConfig(name -> null,
                    name -> props.get(name.substring(OperatorConfig.PROPERTY_PREFIX.length())), cache.toString(), "integration-node");
            runtime = new PythonRuntime(config, registry, mock(EngineAccess.class));
            runtime.start();
            api = new RuntimeApi(runtime);
            PythonApi.JobView job = null;
            if (builtInOnly) {
                profileId = PythonContract.DEFAULT_PROFILE_ID;
                PythonApi.Profile profile = registry.snapshot().profiles.get(profileId);
                long queuedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (job == null && System.nanoTime() < queuedDeadline) {
                    job = runtime.preparation.latestFor(profileId, profile.definitionFingerprint, registry.epoch(), profile.revision);
                    if (job == null) Thread.sleep(10);
                }
                if (job == null) { close(); fail("Automatic default preparation was not queued"); }
            } else {
                PythonApi.ProfileRequest request = new PythonApi.ProfileRequest();
                request.requestId = UUID.randomUUID().toString(); request.expectedRegistryEpoch = registry.epoch();
                request.name = "Offline standard library"; request.runtimeId = "python3"; request.packageSourceId = "offline"; request.requirementsText = "";
                profileId = api.create("test", request).profile.id;
                PythonApi.Profile profile = registry.snapshot().profiles.get(profileId);
                job = runtime.preparation.prepare(profileId, registry.epoch(), profile.revision, () -> true);
            }
            jobId = job.id;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!PreparationManager.TERMINAL.contains(job.state) && System.nanoTime() < deadline) {
                Thread.sleep(50); job = runtime.preparation.job(jobId);
            }
            Files.createDirectories(evidence);
            Files.writeString(evidence.resolve("preparation.json"), PythonApi.mapper().writeValueAsString(job));
            Files.writeString(evidence.resolve("preparation.log"), String.join("\n", runtime.preparation.log(jobId, 0).lines));
            if (!"READY".equals(job.state)) { close(); fail(job.message + ": " + job.diagnostics); }
        }
        void recordWorkers() throws Exception {
            var field = WorkerPools.class.getDeclaredField("idle"); field.setAccessible(true);
            @SuppressWarnings("unchecked") Map<WorkerPools.Key, java.util.Deque<Worker>> idle = (Map<WorkerPools.Key, java.util.Deque<Worker>>) field.get(runtime.pools);
            for (java.util.Deque<Worker> workers : idle.values()) for (Worker worker : workers) {
                if (receipts.stream().noneMatch(r -> r.get("pid").equals(worker.pid()))) {
                    Map<String, Object> receipt = new LinkedHashMap<>(); receipt.put("pid", worker.pid());
                    receipt.put("startedAt", ProcessHandle.of(worker.pid()).flatMap(p -> p.info().startInstant()).map(Object::toString).orElse("unknown"));
                    receipts.add(receipt);
                }
            }
        }
        @Override public void close() throws Exception {
            recordWorkers();
            runtime.stop();
            Files.createDirectories(evidence);
            Files.writeString(evidence.resolve("registry-final.json"), registry.storedJson());
            for (Map<String, Object> receipt : receipts) {
                long pid = ((Number) receipt.get("pid")).longValue();
                receipt.put("exited", ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true));
            }
            Files.writeString(evidence.resolve("process-receipts.json"), PythonApi.mapper().writeValueAsString(receipts));
            boolean ownedExited = receipts.stream().allMatch(r -> Boolean.TRUE.equals(r.get("exited")))
                    && runtime.pools.metrics().get("uncertainWorkerOwnership") == 0 && !ProcessControl.hasUncertainOwnership();
            List<Map<String, Object>> hashes = new ArrayList<>();
            try (var files = Files.walk(cache)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String digest = CanonicalJson.sha256Hex(Files.readAllBytes(file));
                    hashes.add(Map.of("path", cache.relativize(file).toString(), "sha256", digest, "bytes", Files.size(file)));
                    if (file.getFileName().toString().equals("ready.json")) Files.copy(file, evidence.resolve("artifact-" + file.getParent().getFileName() + ".json"), StandardCopyOption.REPLACE_EXISTING);
                    if (file.getFileName().toString().endsWith(".whl")) {
                        Path artifact = evidence.getParent().resolve("content-addressed").resolve(digest);
                        Files.createDirectories(artifact.getParent());
                        if (!Files.exists(artifact)) Files.copy(file, artifact);
                    }
                }
            }
            Files.writeString(evidence.resolve("provenance.json"), PythonApi.mapper().writeValueAsString(hashes));
            Files.writeString(evidence.resolve("cleanup.json"), PythonApi.mapper().writeValueAsString(Map.of("ownedCache", cache.toString(), "processesExited", ownedExited,
                    "database", "in-memory registry only", "removed", ownedExited)));
            if (ownedExited) try (var files = Files.walk(cache)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
            assertTrue(ownedExited, "Owned runtime resources retained because process termination is uncertain: " + cache);
        }
    }
    static class Reader extends PythonReceiver {
        final EventController events = mock(EventController.class);
        @Override protected EventController events() { return events; }
        List<RawMessage> executeRead() throws Exception { return readMessages(); }
    }
    static class Writer extends PythonDispatcher {
        final EventController events = mock(EventController.class);
        @Override protected EventController events() { return events; }
    }
    record Deployment(com.mirth.connect.model.Channel model, Channel channel,
            PythonReceiverProperties reader, PythonDispatcherProperties writer, PythonStep source, PythonStep response) {}
    static Deployment deployment(String id, String profile, String marker) {
        PythonReceiverProperties reader = new PythonReceiverProperties(); reader.setEnvironmentProfileId(profile);
        reader.setPythonScript("from oie_python import Message\nreturn [Message('" + marker + "', source_map={'origin': 'python'}, destination_ids=[]), Message(b'\\x00\\xff')]\n");
        PythonDispatcherProperties writer = new PythonDispatcherProperties(); writer.setEnvironmentProfileId(profile);
        writer.setPythonScript("from oie_python import Response\nconnectorMap['marker'] = '" + marker + "'\nreturn Response(data='" + marker + "|' + payload)\n");
        PythonStep source = new PythonStep(); source.setEnvironmentProfileId(profile); source.setEnabled(true); source.setName("JSON output");
        source.setPythonScript("(tmp if has_tmp else msg)['marker'] = '" + marker + "'\nchannelMap['marker'] = '" + marker + "'\n");
        PythonStep response = new PythonStep(); response.setEnvironmentProfileId(profile); response.setEnabled(true); response.setName("Response status");
        response.setPythonScript("msg = msg + '|response'\nresponse.status_message = '" + marker + " response'\n");
        Connector src = new Connector("Source"); src.setEnabled(true); src.setMetaDataId(0); src.setProperties(reader); src.setTransportName(PythonContract.READER_NAME);
        Transformer transform = new Transformer(); transform.getElements().add(source); src.setTransformer(transform);
        Connector dst = new Connector("Destination"); dst.setEnabled(true); dst.setMetaDataId(1); dst.setProperties(writer); dst.setTransportName(PythonContract.WRITER_NAME);
        Transformer responseTransform = new Transformer(); responseTransform.getElements().add(response); dst.setResponseTransformer(responseTransform);
        com.mirth.connect.model.Channel model = new com.mirth.connect.model.Channel(); model.setId(id); model.setName("Runtime integration"); model.setRevision(1);
        model.setSourceConnector(src); model.getDestinationConnectors().add(dst);
        Channel channel = new Channel(); channel.setChannelId(id); channel.setName(model.getName());
        channel.setRevision(model.getRevision());
        var sourceRuntime = mock(com.mirth.connect.donkey.server.channel.SourceConnector.class);
        when(sourceRuntime.getChannel()).thenReturn(channel); when(sourceRuntime.getMetaDataId()).thenReturn(0);
        when(sourceRuntime.getFilterTransformerExecutor()).thenReturn(mock(com.mirth.connect.donkey.server.channel.FilterTransformerExecutor.class));
        channel.setSourceConnector(sourceRuntime);
        DestinationChainProvider chain = mock(DestinationChainProvider.class);
        var destination = mock(com.mirth.connect.donkey.server.channel.DestinationConnector.class); when(destination.isEnabled()).thenReturn(true);
        when(destination.getChannel()).thenReturn(channel); when(destination.getMetaDataId()).thenReturn(1);
        when(destination.getFilterTransformerExecutor()).thenReturn(mock(com.mirth.connect.donkey.server.channel.FilterTransformerExecutor.class));
        when(destination.getResponseTransformerExecutor()).thenReturn(mock(com.mirth.connect.donkey.server.channel.ResponseTransformerExecutor.class));
        when(chain.getDestinationConnectors()).thenReturn(Map.of(1, destination)); channel.addDestinationChainProvider(chain);
        return new Deployment(model, channel, reader, writer, source, response);
    }
    static ConnectorMessage message(String channel, int metadataId) {
        ConnectorMessage message = new ConnectorMessage(channel, "Runtime integration", 123L, metadataId, "integration-node", java.util.Calendar.getInstance(), Status.SENT);
        message.setEncoded(ConnectorContractTest.content(com.mirth.connect.donkey.model.message.ContentType.ENCODED, ""));
        return message;
    }
    static Reader reader(Deployment deployment) throws Exception {
        Reader reader = new Reader(); reader.setChannel(deployment.channel); reader.setChannelId(deployment.model.getId()); reader.setMetaDataId(0);
        reader.setSourceName("Source"); reader.setConnectorProperties(deployment.reader); reader.onDeploy(); return reader;
    }
    static Writer writer(Deployment deployment) throws Exception {
        Writer writer = new Writer(); writer.setChannel(deployment.channel); writer.setChannelId(deployment.model.getId()); writer.setMetaDataId(1);
        writer.setDestinationName("Destination"); writer.setConnectorProperties(deployment.writer); writer.onDeploy(); return writer;
    }
    @Test void builtinAutomaticallyPreparesOfflineAndBlankProfileReferencesUseTheApprovedPythonVersion() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            var profile = fixture.registry.snapshot().profiles.get(PythonContract.DEFAULT_PROFILE_ID);
            assertEquals(1, fixture.registry.snapshot().profiles.size()); assertTrue(BuiltInEnvironment.owned(profile));
            assertEquals("", profile.requirementsText); assertEquals(PythonContract.BUILTIN_SOURCE_ID, profile.packageSourceId);
            assertEquals(Set.of(PythonContract.BUILTIN_SOURCE_ID), fixture.runtime.config.sources.keySet());
            assertTrue(profile.resolvedLocks.values().stream().allMatch(lock -> lock.lockedRequirements.isEmpty()));
            var readiness = fixture.runtime.readiness(profile, fixture.registry.epoch()); assertEquals("ready", readiness.state);
            assertTrue(readiness.packages.isEmpty()); assertTrue(readiness.pythonVersion.matches("3\\.\\d+\\.\\d+.*"));
            assertEquals(readiness.pythonVersion, fixture.api.runtimeInfo().runtimes.get(0).version);
            assertEquals(PythonContract.DEFAULT_PROFILE_ID, fixture.api.list(false).defaultProfileId);
            assertTrue(fixture.api.list(false).profiles.get(0).builtInDefault);
            var mutation = new PythonApi.RevisionRequest(); mutation.requestId = "cannot-archive-default";
            mutation.expectedRegistryEpoch = fixture.registry.epoch(); mutation.expectedRevision = profile.revision;
            String protectedRegistry = fixture.registry.storedJson();
            assertEquals("BUILTIN_PROFILE_READ_ONLY", assertThrows(ApiFailure.class,
                    () -> fixture.api.archive("test", profile.id, mutation)).code);
            assertEquals(protectedRegistry, fixture.registry.storedJson());
            var log = fixture.runtime.preparation.log(fixture.jobId, 0);
            assertTrue(log.lines.stream().noneMatch(line -> line.contains("pip download")), "The empty built-in must never resolve external packages");
            for (String rawId : new String[] { null, "" }) {
                var request = new PythonApi.ScriptRequest(); request.requestId = "default-version";
                request.environmentProfileId = rawId; request.expectedRegistryEpoch = fixture.registry.epoch(); request.expectedEnvironmentRevision = profile.revision;
                request.role = PythonContract.ROLE_SOURCE_TRANSFORMER;
                request.pythonScript = "import sys,json\nmsg = json.dumps({'version': sys.version.split()[0], 'stdlib': True})\n";
                request.sampleContext = Map.of("msg", Map.of("kind", "raw", "text", "input"));
                var legacy = new PythonStep(); legacy.setEnvironmentProfileId(rawId); legacy.setPythonScript(request.pythonScript);
                String rawDigest = PythonContract.specDigest(legacy);
                var validated = fixture.api.validate(request); assertEquals("valid", validated.status); assertEquals(rawDigest, validated.sourceDigest);
                var tested = fixture.api.test(request, null); assertEquals("success", tested.status); assertFalse(tested.outcomeUncertain);
                assertEquals(PythonContract.DEFAULT_PROFILE_ID, tested.profileId); assertEquals(rawDigest, tested.sourceDigest);
                var output = PythonApi.mapper().readTree(PythonApi.mapper().valueToTree(tested.output).get("msg").get("text").asText());
                assertEquals(readiness.pythonVersion, output.get("version").asText()); assertTrue(output.get("stdlib").asBoolean());
                assertEquals(rawId, request.environmentProfileId); assertEquals(rawId, legacy.getEnvironmentProfileId());
                Files.writeString(fixture.evidence.resolve("default-version-" + (rawId == null ? "null" : "empty") + ".json"), PythonApi.mapper().writeValueAsString(tested));
            }
            var deployment = deployment(UUID.randomUUID().toString(), "", "python-default");
            String modelBefore = new com.thoughtworks.xstream.XStream().toXML(deployment.model());
            var binding = fixture.runtime.deployments.prepare(deployment.model());
            assertTrue(binding.specs.values().stream().allMatch(spec -> PythonContract.DEFAULT_PROFILE_ID.equals(spec.profileId)));
            assertEquals(modelBefore, new com.thoughtworks.xstream.XStream().toXML(deployment.model()), "Effective fallback must not rewrite saved channel XML");
            when(fixture.runtime.engine.deployedChannel(deployment.model().getId())).thenReturn(deployment.channel());
            fixture.runtime.deployments.activate(deployment.channel());
            assertEquals("python-default", reader(deployment).executeRead().get(0).getRawData());
            fixture.recordWorkers();
            Files.writeString(fixture.evidence.resolve("default-profile-list.json"), PythonApi.mapper().writeValueAsString(fixture.api.list(false)));
        }
    }
    @Test void offlinePreparedEnvironmentExecutesAllSurfacesAndUsesCurrentWriterAfterRedeploy() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String channelId = UUID.randomUUID().toString();
            Deployment a = deployment(channelId, fixture.profileId, "A");
            fixture.runtime.deployments.prepare(a.model);
            when(fixture.runtime.engine.deployedChannel(channelId)).thenReturn(a.channel);
            Reader readerA = reader(a); Writer writerA = writer(a);
            assertThrows(Deployments.NotAdmitted.class, () -> fixture.runtime.deployments.admit(channelId, 1, PythonContract.ROLE_WRITER, PythonContract.specDigest(a.writer), a.channel));
            fixture.runtime.deployments.activate(a.channel);
            List<RawMessage> messages = readerA.executeRead();
            assertEquals("A", messages.get(0).getRawData()); assertTrue(messages.get(0).getDestinationMetaDataIds().isEmpty());
            assertArrayEquals(new byte[]{0, (byte) 255}, messages.get(1).getRawBytes());
            assertEquals(0L, fixture.runtime.pools.metrics().get("busyWorkers"));
            ConnectorMessage sourceMessage = message(channelId, 0);
            Map<String, Object> channelMap = new HashMap<>();
            try (var scope = RuntimeInvocationContext.open(a.channel, 0, PythonContract.ROLE_SOURCE_TRANSFORMER, sourceMessage)) {
                StepResult result = PythonBridge.executeStep(PythonContract.specDigest(a.source), PythonContract.ROLE_SOURCE_TRANSFORMER,
                        new ImmutableConnectorMessage(sourceMessage, true), "json", "{\"value\":1}", "json", "{}", channelMap, new HashMap<>(), new HashMap<>(), new HashMap<>(), null, null, null);
                assertFalse(result.hasMsg()); assertTrue(result.hasTmp()); assertEquals("A", PythonApi.mapper().readTree(result.getTmpText()).get("marker").asText());
                assertTrue(channelMap.isEmpty()); result.commit(); assertEquals("A", channelMap.get("marker"));
            }
            ConnectorMessage outgoing = message(channelId, 1);
            assertEquals("A|", writerA.send(a.writer, outgoing).getMessage());
            try (var scope = RuntimeInvocationContext.open(a.channel, 1, PythonContract.ROLE_RESPONSE_TRANSFORMER, outgoing)) {
                StepResult result = PythonBridge.executeStep(PythonContract.specDigest(a.response), PythonContract.ROLE_RESPONSE_TRANSFORMER,
                        new ImmutableConnectorMessage(outgoing, true), "raw", "accepted", null, null, new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), Status.SENT, "", "");
                assertEquals("accepted|response", result.getMsgText()); assertEquals("A response", result.getResponseStatusMessage());
            }
            fixture.recordWorkers();
            fixture.runtime.deployments.retire(a.channel, "test undeploy");
            Deployment b = deployment(channelId, fixture.profileId, "B"); fixture.runtime.deployments.prepare(b.model);
            when(fixture.runtime.engine.deployedChannel(channelId)).thenReturn(b.channel);
            Writer writerB = writer(b); fixture.runtime.deployments.activate(b.channel);
            a.writer.getDestinationConnectorProperties().setValidateResponse(true);
            Response current = writerB.send(a.writer, outgoing);
            assertEquals("B|", current.getMessage()); assertTrue(current.isValidate()); assertEquals("B", outgoing.getConnectorMap().get("marker"));
            assertThrows(Deployments.NotAdmitted.class, () -> fixture.runtime.deployments.admit(channelId, 1, PythonContract.ROLE_WRITER, PythonContract.specDigest(a.writer), a.channel));
            fixture.runtime.deployments.retire(a.channel, "late old callback");
            assertEquals("B|", writerB.send(a.writer, outgoing).getMessage());
            fixture.recordWorkers();
            Deployment malformed = deployment(channelId, fixture.profileId, "C");
            malformed.writer.setPythonScript("connectorMap['partial'] = 'must-not-apply'\nreturn {'invalid': 'response'}\n");
            fixture.runtime.deployments.prepare(malformed.model);
            when(fixture.runtime.engine.deployedChannel(channelId)).thenReturn(malformed.channel);
            Writer malformedWriter = writer(malformed);
            fixture.runtime.deployments.activate(malformed.channel);
            Response rejected = malformedWriter.send(a.writer, outgoing);
            assertEquals(Status.ERROR, rejected.getStatus());
            assertFalse(outgoing.getConnectorMap().containsKey("partial"));
            assertEquals("B", outgoing.getConnectorMap().get("marker"));
            fixture.recordWorkers();
            Deployment scalar = deployment(channelId, fixture.profileId, "D");
            scalar.source.setPythonScript("msg = 1\nchannelMap['partial'] = 'must-not-apply'\n");
            fixture.runtime.deployments.prepare(scalar.model);
            when(fixture.runtime.engine.deployedChannel(channelId)).thenReturn(scalar.channel);
            fixture.runtime.deployments.activate(scalar.channel);
            try (var scope = RuntimeInvocationContext.open(scalar.channel, 0, PythonContract.ROLE_SOURCE_TRANSFORMER, sourceMessage)) {
                assertThrows(PythonBridge.PythonStepException.class, () -> PythonBridge.executeStep(PythonContract.specDigest(scalar.source), PythonContract.ROLE_SOURCE_TRANSFORMER,
                        new ImmutableConnectorMessage(sourceMessage, true), "json", "{}", null, null, channelMap, new HashMap<>(), new HashMap<>(), new HashMap<>(), null, null, null));
                assertFalse(channelMap.containsKey("partial"));
            }
            fixture.recordWorkers();
        }
    }
}
