/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.mirth.connect.plugins.python.PythonApi.Diagnostic;

/**
 * Bounded save-time checks for requirements text. Full parsing happens in the
 * resolver during Prepare; these checks only reject what is never allowed:
 * pip options, URLs, paths, VCS references and the plugin-owned SDK.
 */
public final class Requirements {

    public static final int MAX_LINES = 1000;
    private static final Pattern NAME_START = Pattern.compile("^([A-Za-z0-9][A-Za-z0-9._-]*)");

    private Requirements() {}

    /** Converts line endings to LF and removes trailing whitespace and trailing blank lines. */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(stripTrailing(line)).append('\n');
        }
        String result = out.toString();
        while (result.endsWith("\n\n")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.equals("\n") ? "" : result;
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    public static List<Diagnostic> precheck(String text, int maxBytes) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        String normalized = normalize(text);
        if (normalized.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            diagnostics.add(Diagnostic.of("REQUIREMENTS_TOO_LARGE", "Requirements exceed " + maxBytes + " bytes", "requirementsText", null));
            return diagnostics;
        }
        String[] lines = normalized.split("\n", -1);
        if (lines.length > MAX_LINES) {
            diagnostics.add(Diagnostic.of("REQUIREMENTS_TOO_LARGE", "Requirements exceed " + MAX_LINES + " lines", "requirementsText", null));
            return diagnostics;
        }
        for (int i = 0; i < lines.length; i++) {
            String problem = lineProblem(lines[i]);
            if (problem != null) {
                Diagnostic d = Diagnostic.of("REQUIREMENT_NOT_ALLOWED", "Line " + (i + 1) + ": " + problem, "requirementsText",
                        "Use a package name with optional extras, version constraints and environment markers.");
                d.line = i + 1;
                diagnostics.add(d);
            }
        }
        return diagnostics;
    }

    static String lineProblem(String raw) {
        String line = raw.trim();
        int hash = line.indexOf(" #");
        if (line.startsWith("#") || line.isEmpty()) {
            return null;
        }
        if (hash >= 0) {
            line = line.substring(0, hash).trim();
        }
        if (line.endsWith("\\")) {
            return "line continuations are not supported";
        }
        if (line.startsWith("-")) {
            return "pip options such as " + line.split("\\s+")[0] + " are not allowed; package sources are operator settings";
        }
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("://") || lower.startsWith("file:") || lower.matches(".*\\s@\\s.*") || lower.matches("^[^;]*@.*")) {
            return "direct URL and VCS requirements are not allowed";
        }
        if (lower.startsWith(".") || lower.startsWith("/") || lower.startsWith("~") || lower.matches("^[a-z]:[\\\\/].*") || lower.contains("\\")) {
            return "local paths are not allowed";
        }
        for (String vcs : new String[] { "git+", "hg+", "svn+", "bzr+" }) {
            if (lower.startsWith(vcs)) {
                return "VCS requirements are not allowed";
            }
        }
        java.util.regex.Matcher m = NAME_START.matcher(line);
        if (!m.find()) {
            return "expected a package name";
        }
        String name = m.group(1).toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
        if (name.equals(Sdk.DISTRIBUTION)) {
            return "the oie-python SDK is managed by the extension and cannot be listed";
        }
        return null;
    }
}
