/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.util.ArrayList;
import java.util.List;

import com.mirth.connect.connectors.python.PythonDispatcherProperties;
import com.mirth.connect.connectors.python.PythonReceiverProperties;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.Connector;
import com.mirth.connect.model.IteratorStep;
import com.mirth.connect.model.Step;
import com.mirth.connect.model.Rule;
import com.mirth.connect.model.IteratorRule;
import com.mirth.connect.plugins.python.PythonRule;
import com.mirth.connect.model.Transformer;
import com.mirth.connect.plugins.python.PythonContract;
import com.mirth.connect.plugins.python.PythonScriptConfiguration;
import com.mirth.connect.plugins.python.PythonStep;

/** Finds every Python surface in a channel model. */
public final class Surfaces {

    public static final class Surface {
        public final PythonScriptConfiguration config;
        public final int metaDataId;
        public final String connectorName;
        public final String role;
        public final String stepName;
        /** False when the step, its connector or an enclosing iterator is disabled. */
        public final boolean enabled;
        /** True when the step sits inside an Iterator step, which v1 does not support. */
        public final boolean insideIterator;

        Surface(PythonScriptConfiguration config, int metaDataId, String connectorName, String role, String stepName, boolean enabled,
                boolean insideIterator) {
            this.config = config;
            this.metaDataId = metaDataId;
            this.connectorName = connectorName;
            this.role = role;
            this.stepName = stepName;
            this.enabled = enabled;
            this.insideIterator = insideIterator;
        }

        public boolean template() {
            return role.startsWith(PythonContract.ROLE_CODE_TEMPLATE + ":");
        }

        public String label(String channelName) {
            String where = channelName + " / " + connectorName + " / ";
            if (template()) {
                return where + (role.endsWith(PythonContract.ROLE_RESPONSE_TRANSFORMER) ? "Response Transformer" : "Transformer") + " / Python function " + stepName;
            }
            switch (role) {
                case PythonContract.ROLE_SOURCE_FILTER:
                case PythonContract.ROLE_DESTINATION_FILTER:
                    return where + "Filter / " + stepName;
                case PythonContract.ROLE_READER:
                    return where + "Python Reader";
                case PythonContract.ROLE_WRITER:
                    return where + "Python Writer";
                case PythonContract.ROLE_RESPONSE_TRANSFORMER:
                    return where + "Response Transformer / " + stepName;
                default:
                    return where + "Transformer / " + stepName;
            }
        }
    }

    private Surfaces() {}

    public static List<Surface> of(Channel channel) {
        List<Surface> out = new ArrayList<>();
        Connector source = channel.getSourceConnector();
        if (source != null) {
            boolean enabled = source.isEnabled();
            if (source.getProperties() instanceof PythonReceiverProperties) {
                out.add(new Surface((PythonScriptConfiguration) source.getProperties(), 0, name(source, "Source"), PythonContract.ROLE_READER,
                        null, enabled, false));
            }
            rules(out, source.getFilter() == null ? null : source.getFilter().getElements(), 0, name(source, "Source"), PythonContract.ROLE_SOURCE_FILTER, enabled, false);
            steps(out, source.getTransformer(), 0, name(source, "Source"), PythonContract.ROLE_SOURCE_TRANSFORMER, enabled);
        }
        if (channel.getDestinationConnectors() != null) {
            for (Connector destination : channel.getDestinationConnectors()) {
                boolean enabled = destination.isEnabled();
                String name = name(destination, "Destination " + destination.getMetaDataId());
                if (destination.getProperties() instanceof PythonDispatcherProperties) {
                    out.add(new Surface((PythonScriptConfiguration) destination.getProperties(), destination.getMetaDataId(), name,
                            PythonContract.ROLE_WRITER, null, enabled, false));
                }
                rules(out, destination.getFilter() == null ? null : destination.getFilter().getElements(), destination.getMetaDataId(), name, PythonContract.ROLE_DESTINATION_FILTER, enabled, false);
                steps(out, destination.getTransformer(), destination.getMetaDataId(), name, PythonContract.ROLE_DESTINATION_TRANSFORMER, enabled);
                steps(out, destination.getResponseTransformer(), destination.getMetaDataId(), name, PythonContract.ROLE_RESPONSE_TRANSFORMER,
                        enabled);
            }
        }
        return out;
    }

    /** Mirrors the stock JavaScriptBuilder's first-library-wins/channel/context rules. */
    public static List<Surface> templates(Channel channel, List<com.mirth.connect.model.codetemplates.CodeTemplateLibrary> libraries) {
        List<Surface> out = new ArrayList<>();
        java.util.Set<String> handled = new java.util.HashSet<>();
        for (var library : libraries) {
            boolean included = library.getEnabledChannelIds().contains(channel.getId())
                    || !library.getDisabledChannelIds().contains(channel.getId()) && library.isIncludeNewChannels();
            for (var template : library.getCodeTemplates()) {
                if (!handled.add(template.getId()) || !included || !(template.getProperties() instanceof com.mirth.connect.plugins.python.PythonCodeTemplateProperties)
                        || template.getContextSet() == null) continue;
                var config = ((com.mirth.connect.plugins.python.PythonCodeTemplateProperties) template.getProperties()).clone();
                try { PythonContract.validateFunctionName(config.getFunctionName()); }
                catch (IllegalArgumentException invalid) { continue; }
                Connector source = channel.getSourceConnector();
                if (source != null && source.isEnabled() && template.getContextSet().contains(com.mirth.connect.model.codetemplates.ContextType.SOURCE_FILTER_TRANSFORMER)) {
                    out.add(new Surface(config, 0, name(source, "Source"), PythonContract.templateRole(PythonContract.ROLE_SOURCE_TRANSFORMER), template.getName(), true, false));
                }
                if (channel.getDestinationConnectors() != null) for (Connector destination : channel.getDestinationConnectors()) {
                    if (!destination.isEnabled()) continue;
                    for (var context : new com.mirth.connect.model.codetemplates.ContextType[] {
                            com.mirth.connect.model.codetemplates.ContextType.DESTINATION_FILTER_TRANSFORMER,
                            com.mirth.connect.model.codetemplates.ContextType.DESTINATION_RESPONSE_TRANSFORMER}) {
                        if (template.getContextSet().contains(context)) out.add(new Surface(config, destination.getMetaDataId(), name(destination, "Destination"),
                                PythonContract.templateRole(context == com.mirth.connect.model.codetemplates.ContextType.DESTINATION_FILTER_TRANSFORMER
                                        ? PythonContract.ROLE_DESTINATION_TRANSFORMER : PythonContract.ROLE_RESPONSE_TRANSFORMER), template.getName(), true, false));
                    }
                }
            }
        }
        return out;
    }

    public static java.util.Set<com.mirth.connect.model.codetemplates.ContextType> templateContexts() {
        return java.util.Set.of(com.mirth.connect.model.codetemplates.ContextType.SOURCE_FILTER_TRANSFORMER,
                com.mirth.connect.model.codetemplates.ContextType.DESTINATION_FILTER_TRANSFORMER,
                com.mirth.connect.model.codetemplates.ContextType.DESTINATION_RESPONSE_TRANSFORMER);
    }

    private static void rules(List<Surface> out, List<Rule> rules, int metadataId, String connectorName, String role, boolean enabled, boolean nested) {
        if (rules == null) return;
        for (Rule rule : rules) {
            boolean active = enabled && rule.isEnabled();
            if (rule instanceof PythonRule) out.add(new Surface((PythonRule) rule, metadataId, connectorName, role,
                    rule.getName() == null || rule.getName().isEmpty() ? "Rule " + rule.getSequenceNumber() : rule.getName(), active, nested));
            else if (rule instanceof IteratorRule && ((IteratorRule) rule).getProperties() != null)
                rules(out, ((IteratorRule) rule).getProperties().getChildren(), metadataId, connectorName, role, active, true);
        }
    }

    private static String name(Connector connector, String fallback) {
        return connector.getName() == null || connector.getName().isEmpty() ? fallback : connector.getName();
    }

    private static void steps(List<Surface> out, Transformer transformer, int metaDataId, String connectorName, String role, boolean enabled) {
        if (transformer == null || transformer.getElements() == null) {
            return;
        }
        walk(out, transformer.getElements(), metaDataId, connectorName, role, enabled, false);
    }

    private static void walk(List<Surface> out, List<Step> steps, int metaDataId, String connectorName, String role, boolean enabled,
            boolean insideIterator) {
        for (Step step : steps) {
            boolean stepEnabled = enabled && step.isEnabled();
            if (step instanceof PythonStep) {
                String name = step.getName() == null || step.getName().isEmpty() ? "Step " + step.getSequenceNumber() : step.getName();
                out.add(new Surface((PythonStep) step, metaDataId, connectorName, role, name, stepEnabled, insideIterator));
            } else if (step instanceof IteratorStep) {
                IteratorStep iterator = (IteratorStep) step;
                if (iterator.getProperties() != null && iterator.getProperties().getChildren() != null) {
                    walk(out, iterator.getProperties().getChildren(), metaDataId, connectorName, role, stepEnabled, true);
                }
            }
        }
    }
}
