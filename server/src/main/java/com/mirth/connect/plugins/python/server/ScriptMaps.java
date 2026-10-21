/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The engine maps of one call: a snapshot for Python, and the
 * validated set/delete operations Python returned. Operations apply only to
 * writable maps and only after the whole result has been validated.
 */
public final class ScriptMaps {

    public static final String CHANNEL = "channelMap";
    public static final String CONNECTOR = "connectorMap";
    public static final String SOURCE = "sourceMap";
    public static final String RESPONSE = "responseMap";
    public static final String GLOBAL = "globalMap";
    public static final String GLOBAL_CHANNEL = "globalChannelMap";
    public static final String CONFIGURATION = "configurationMap";

    private static final class Entry {
        final Map<String, Object> live;
        final Map<String, Object> snapshot;
        final boolean writable;
        final Map<String, String> aliases = new LinkedHashMap<>();

        Entry(Map<String, Object> live, boolean writable) {
            this.live = live;
            this.snapshot = live == null ? new HashMap<>() : new HashMap<>(live);
            this.writable = writable && live != null;
        }
    }

    /** One validated change to a live map. */
    public static final class Operation {
        public final String map;
        public final String key;
        public final boolean delete;
        public final Object value;
        final Map<String, Object> target;

        Operation(String map, String key, boolean delete, Object value, Map<String, Object> target) {
            this.map = map;
            this.key = key;
            this.delete = delete;
            this.value = value;
            this.target = target;
        }

        public void apply() {
            if (delete) {
                target.remove(key);
            } else {
                target.put(key, value);
            }
        }
    }

    private final Map<String, Entry> maps = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public ScriptMaps add(String name, Object live, boolean writable) {
        if (live instanceof org.mozilla.javascript.Wrapper) live = ((org.mozilla.javascript.Wrapper) live).unwrap();
        maps.put(name, new Entry(live instanceof Map ? (Map<String, Object>) live : null, writable));
        return this;
    }

    /** The complete shared stores available to the current channel. */
    public ScriptMaps shared(EngineAccess engine, String channelId) {
        return add(GLOBAL, engine.globalMap(), true)
                .add(GLOBAL_CHANNEL, engine.globalChannelMap(channelId), true)
                .add(CONFIGURATION, engine.configurationMap(), false);
    }

    public ScriptMaps message(com.mirth.connect.userutil.ImmutableConnectorMessage message) {
        return add(CHANNEL, message.getChannelMap(), true).add(CONNECTOR, message.getConnectorMap(), true)
                .add(SOURCE, message.getSourceMap(), false).add(RESPONSE, message.getResponseMap(), true)
                .responseDestinations(message.getDestinationIdMap());
    }

    /** Match stock ResponseMap.get/containsKey lookup by destination name. */
    public ScriptMaps responseDestinations(Map<String, ?> ids) {
        Entry response = maps.get(RESPONSE);
        if (response != null && ids != null) for (Map.Entry<String, ?> destination : ids.entrySet()) {
            try {
                if (!(destination.getValue() instanceof Number)) throw new NumberFormatException();
                int id = new java.math.BigDecimal(destination.getValue().toString()).intValueExact();
                response.aliases.put(destination.getKey(), "d" + id);
            } catch (ArithmeticException | NumberFormatException failure) {
                throw new Wire.ValueException("destinationIdMap must map destination names to integer metadata IDs");
            }
        }
        return this;
    }

    public Map<String, Object> wire() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> e : maps.entrySet()) {
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("writable", e.getValue().writable);
            spec.put("values", Wire.encodeMap(e.getValue().snapshot, e.getKey()));
            if (!e.getValue().aliases.isEmpty()) spec.put("aliases", new LinkedHashMap<>(e.getValue().aliases));
            out.put(e.getKey(), spec);
        }
        return out;
    }

    public Wire.OriginalValues originals() {
        return (map, key) -> {
            Entry entry = maps.get(map);
            if (entry == null || !entry.snapshot.containsKey(key)) {
                throw new Wire.ValueException("The script referred to " + map + "['" + key + "'], which this call did not provide");
            }
            return entry.snapshot.get(key);
        };
    }

    /** Validates the returned map changes. Nothing is applied. */
    @SuppressWarnings("unchecked")
    public List<Operation> parse(Object delta) {
        List<Operation> ops = new ArrayList<>();
        if (delta == null) {
            return ops;
        }
        if (!(delta instanceof Map)) {
            throw new Wire.ValueException("The script result has malformed map changes");
        }
        for (Map.Entry<String, Object> e : ((Map<String, Object>) delta).entrySet()) {
            Entry entry = maps.get(e.getKey());
            if (entry == null) {
                throw new Wire.ValueException(e.getKey() + " is not available in this context");
            }
            if (!entry.writable) {
                throw new Wire.ValueException(e.getKey() + " is read-only");
            }
            if (!(e.getValue() instanceof Map)) {
                throw new Wire.ValueException("The script result has malformed changes for " + e.getKey());
            }
            Map<String, Object> change = (Map<String, Object>) e.getValue();
            Object sets = change.get("set");
            Object deletes = change.get("delete");
            if (!change.keySet().equals(new java.util.HashSet<>(java.util.Arrays.asList("set", "delete")))
                    || !(sets instanceof Map) || !(deletes instanceof List)) {
                throw new Wire.ValueException("The script result has malformed set/delete changes for " + e.getKey());
            }
            if (sets instanceof Map) {
                for (Map.Entry<String, Object> set : ((Map<String, Object>) sets).entrySet()) {
                    Object value = Wire.decode(set.getValue(), e.getKey() + "['" + set.getKey() + "']", originals(), 0);
                    // The engine's concurrent global stores reject null. Reject
                    // it before committing any of this invocation's changes.
                    if (value == null && (GLOBAL.equals(e.getKey()) || GLOBAL_CHANNEL.equals(e.getKey()))) {
                        throw new Wire.ValueException(e.getKey() + "['" + set.getKey() + "'] cannot be None; delete the key instead");
                    }
                    ops.add(new Operation(e.getKey(), set.getKey(), false, value, entry.live));
                }
            }
            if (deletes instanceof List) {
                for (Object key : (List<Object>) deletes) {
                    if (!(key instanceof String)) throw new Wire.ValueException("The script result has a non-string delete key for " + e.getKey());
                    if (((Map<?, ?>) sets).containsKey(key)) throw new Wire.ValueException("The script result both sets and deletes " + e.getKey() + "['" + key + "']");
                    ops.add(new Operation(e.getKey(), (String) key, true, null, entry.live));
                }
            }
        }
        return ops;
    }

    public static Set<String> names() {
        return new java.util.LinkedHashSet<>(java.util.Arrays.asList(CHANNEL, CONNECTOR, SOURCE, RESPONSE, GLOBAL, GLOBAL_CHANNEL, CONFIGURATION));
    }
}
