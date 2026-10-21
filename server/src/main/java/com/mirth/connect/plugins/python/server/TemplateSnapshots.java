/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.ast.FunctionCall;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.PropertyGet;
import org.mozilla.javascript.ast.StringLiteral;
import com.mirth.connect.model.Channel;
import com.mirth.connect.plugins.python.PythonContract;

/** Checks the stock compiler's actual wrappers before its channel deploy hook returns. */
final class TemplateSnapshots {
    private static final String BRIDGE = "Packages.com.mirth.connect.plugins.python.server.PythonBridge.executeTemplate";

    private TemplateSnapshots() {}

    static void check(Channel model, List<Surfaces.Surface> templates, EngineAccess engine, List<String> problems) {
        try {
            if (model.getSourceConnector() != null && model.getSourceConnector().isEnabled()) {
                check(model, 0, PythonContract.ROLE_SOURCE_TRANSFORMER, templates, engine, problems);
            }
            if (model.getDestinationConnectors() != null) for (var destination : model.getDestinationConnectors()) {
                if (!destination.isEnabled()) continue;
                check(model, destination.getMetaDataId(), PythonContract.ROLE_DESTINATION_TRANSFORMER, templates, engine, problems);
                check(model, destination.getMetaDataId(), PythonContract.ROLE_RESPONSE_TRANSFORMER, templates, engine, problems);
            }
        } catch (RuntimeException failure) {
            problems.add("The compiled Python function snapshot could not be checked: " + failure.getMessage() + ". Retry deployment.");
        }
    }

    private static void check(Channel model, int metadataId, String phase, List<Surfaces.Surface> templates,
            EngineAccess engine, List<String> problems) {
        String source = engine.compiledTransformerSource(model.getId(), metadataId, PythonContract.ROLE_RESPONSE_TRANSFORMER.equals(phase));
        // Stock omits empty/default transformers; there is no callable wrapper in that phase.
        if (source == null) {
            for (var surface : Surfaces.of(model)) if (surface.enabled && surface.metaDataId == metadataId
                    && PythonContract.isFilterRole(surface.role) && !PythonContract.ROLE_RESPONSE_TRANSFORMER.equals(phase)) {
                problems.add("The compiled Python filter snapshot is missing (connector " + metadataId + "). Retry deployment.");
                break;
            }
            return;
        }
        Set<String> expected = new HashSet<>();
        for (var template : templates) {
            if (template.metaDataId == metadataId && template.role.equals(PythonContract.templateRole(phase))) {
                expected.add(PythonContract.specDigest(template.config));
            }
        }
        if (!PythonContract.ROLE_RESPONSE_TRANSFORMER.equals(phase)) {
            Set<String> filters = new HashSet<>();
            String filterRole = metadataId == 0 ? PythonContract.ROLE_SOURCE_FILTER : PythonContract.ROLE_DESTINATION_FILTER;
            for (var surface : Surfaces.of(model)) if (surface.enabled && surface.metaDataId == metadataId && surface.role.equals(filterRole))
                filters.add(PythonContract.specDigest(surface.config));
            if (!filters.equals(filterDigests(source))) problems.add("Python filter rules changed while channel filters were being compiled (connector "
                    + metadataId + "). Retry deployment after channel saves finish.");
        }
        if (!expected.equals(digests(source))) {
            problems.add("Python functions changed while channel transformers were being compiled (connector " + metadataId + ", " + phase
                    + "). Retry deployment after code template saves finish.");
        }
    }

    static Set<String> filterDigests(String source) { return digests(source, "__py.executeFilter"); }
    static Set<String> digests(String source) { return digests(source, BRIDGE); }
    private static Set<String> digests(String source, String target) {
        Set<String> digests = new HashSet<>();
        if (!source.contains(target.substring(target.lastIndexOf('.') + 1))) return digests;
        // Parse without executing user code. Text in comments and string literals is not a call.
        var options = new CompilerEnvirons();
        options.setLanguageVersion(Context.VERSION_ES6);
        options.setXmlAvailable(true);
        options.setReservedKeywordAsIdentifier(true);
        new Parser(options).parse(source, "compiled transformer snapshot", 1).visit(node -> {
            if (node instanceof FunctionCall call && target.equals(qualifiedName(call.getTarget()))
                    && !call.getArguments().isEmpty() && call.getArguments().get(0) instanceof StringLiteral literal
                    && literal.getValue().matches("[a-f0-9]{64}")) {
                digests.add(literal.getValue());
            }
            return true;
        });
        return digests;
    }

    private static String qualifiedName(AstNode node) {
        if (node instanceof Name name) return name.getIdentifier();
        if (node instanceof PropertyGet property) {
            String owner = qualifiedName(property.getTarget());
            return owner == null ? null : owner + "." + property.getProperty().getIdentifier();
        }
        // Do not serialize arbitrary call targets: stock Rhino cannot print
        // default-XML-namespace expressions inside immediately invoked functions.
        return null;
    }
}
