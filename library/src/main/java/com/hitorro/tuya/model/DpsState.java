package com.hitorro.tuya.model;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * A snapshot of a Tuya device's Data Points (DPs). Keys are stringified integer
 * DP indexes (e.g. "1" for the main switch); values are heterogeneous — bool,
 * number, or string depending on the schema.
 */
public record DpsState(Map<String, Object> dps, Instant at) {

    public DpsState {
        dps = (dps == null) ? Map.of() : Collections.unmodifiableMap(dps);
        if (at == null) at = Instant.now();
    }

    public static DpsState empty() {
        return new DpsState(Map.of(), Instant.now());
    }
}
