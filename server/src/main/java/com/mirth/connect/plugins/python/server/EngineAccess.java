/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.mirth.connect.donkey.server.channel.Channel;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.util.GlobalChannelVariableStoreFactory;
import com.mirth.connect.server.util.GlobalVariableStore;

/** The engine services the runtime reads. Tests substitute their own. */
public interface EngineAccess {

    /** The runtime channel the engine currently has deployed under this ID, or null. */
    Channel deployedChannel(String channelId);

    /** Queue a completion proof on the stock per-channel FIFO task executor. */
    default void afterChannelTasks(String channelId, Runnable work) {
        throw new UnsupportedOperationException("A stock channel-task completion scheduler is required");
    }

    /** Saved channel models, for reference scans. */
    List<com.mirth.connect.model.Channel> savedChannels();

    /** Template/library snapshots are used only during deployment and reference scans. */
    default List<com.mirth.connect.model.codetemplates.CodeTemplateLibrary> codeTemplateLibraries() { return Collections.emptyList(); }
    default List<com.mirth.connect.model.codetemplates.CodeTemplate> savedCodeTemplates() { return Collections.emptyList(); }

    /** Generated source paired with the stock compiled transformer, or null when no script was compiled. */
    default String compiledTransformerSource(String channelId, int metaDataId, boolean response) { return null; }

    /** The same node-local stores exposed to channel JavaScript. */
    default Map<String, ?> configurationMap() { return Collections.emptyMap(); }
    default Map<String, Object> globalMap() { return null; }
    default Map<String, Object> globalChannelMap(String channelId) { return null; }

    final class Live implements EngineAccess {
        @Override public Map<String, ?> configurationMap() {
            return ConfigurationController.getInstance().getConfigurationMap();
        }

        @Override public Map<String, Object> globalMap() {
            return GlobalVariableStore.getInstance().globalVariableMap;
        }

        @Override public Map<String, Object> globalChannelMap(String channelId) {
            return channelId == null ? null : GlobalChannelVariableStoreFactory.getInstance().get(channelId).globalChannelVariableMap;
        }

        @Override
        public Channel deployedChannel(String channelId) {
            return ControllerFactory.getFactory().createEngineController().getDeployedChannel(channelId);
        }

        @Override public void afterChannelTasks(String channelId, Runnable work) {
            var task = new com.mirth.connect.server.channel.ChannelTask(channelId) {
                @Override public Void execute() { work.run(); return null; }
            };
            if (ControllerFactory.getFactory().createEngineController().submitTasks(Collections.singletonList(task), null).size() != 1) {
                throw new IllegalStateException("Stock channel completion task was not queued");
            }
        }

        @Override
        public List<com.mirth.connect.model.Channel> savedChannels() {
            List<com.mirth.connect.model.Channel> channels = ControllerFactory.getFactory().createChannelController().getChannels(null);
            return channels == null ? Collections.emptyList() : channels;
        }

        @Override public List<com.mirth.connect.model.codetemplates.CodeTemplateLibrary> codeTemplateLibraries() {
            try { return ControllerFactory.getFactory().createCodeTemplateController().getLibraries(null, true); }
            catch (com.mirth.connect.client.core.ControllerException e) { throw new IllegalStateException("Cannot snapshot code template libraries", e); }
        }
        @Override public List<com.mirth.connect.model.codetemplates.CodeTemplate> savedCodeTemplates() {
            try { return ControllerFactory.getFactory().createCodeTemplateController().getCodeTemplates(null); }
            catch (com.mirth.connect.client.core.ControllerException e) { throw new IllegalStateException("Cannot inspect saved code templates", e); }
        }

        @Override public String compiledTransformerSource(String channelId, int metaDataId, boolean response) {
            String key = response ? com.mirth.connect.server.controllers.ScriptController.RESPONSE_TRANSFORMER + "_" + metaDataId
                    : "JavaScript_Filter_Transformer_" + metaDataId;
            String id = com.mirth.connect.server.controllers.ScriptController.getScriptId(key, channelId);
            var cache = com.mirth.connect.server.util.CompiledScriptCache.getInstance();
            String source = cache.getSourceScript(id);
            if (source == null && cache.getCompiledScript(id) != null) {
                throw new IllegalStateException("The compiled transformer has no source snapshot");
            }
            return source;
        }

    }
}
