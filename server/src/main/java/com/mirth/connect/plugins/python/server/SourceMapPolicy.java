/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.util.Map;
import java.util.Set;
import com.mirth.connect.donkey.server.Constants;

/** Authoritative Java policy: SDK validation is only an early diagnostic. */
public final class SourceMapPolicy {
    public static final Set<String> RESERVED = Set.of(Constants.DESTINATION_SET_KEY, Constants.BATCH_ID_KEY,
            Constants.BATCH_COMPLETE_KEY, Constants.BATCH_SEQUENCE_ID_KEY, Constants.REPROCESSED_KEY, Constants.REPLACED_KEY);
    private SourceMapPolicy() {}
    public static void validate(Map<String, Object> map) {
        if (map == null) throw new Wire.ValueException("sourceMap must be an object");
        for (String key : map.keySet()) {
            if (key == null || RESERVED.contains(key)) {
                throw new Wire.ValueException("sourceMap['" + key + "'] is reserved for the engine");
            }
        }
    }
}
