package com.gamma.pipeline;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One {@code route:} branch entry, typed at the editor round-trip seam (JAVA-SIMP-2 seam #2).
 * The two modeled keys — {@code key} (the {@code route:<key>} edge name) and {@code database}
 * (the branch↔sink join key {@code PipelineLift} pairs branches back with) — are typed fields;
 * {@code source} is the entry's verbatim map, carried whole so unmodeled per-branch keys
 * <b>and their authored order</b> survive by construction ({@link #toMap} substitutes the modeled
 * values in place and never rebuilds the entry — a reordered entry is a spurious file diff on a
 * no-edit save, the W3 defect class). The historical branch-destruction bug was exactly this seam:
 * an untyped {@code List<Map>} mutated in place with {@code database} as an implicit join key.
 */
record RouteBranch(String key, String database, Map<String, Object> source) {

    /** Typed view over one verbatim branch entry (the entry map is copied, never aliased). */
    static RouteBranch fromMap(Map<?, ?> entry) {
        Map<String, Object> src = new LinkedHashMap<>();
        entry.forEach((k, v) -> src.put(String.valueOf(k), v));
        Object key = src.get("key");
        Object db = src.get("database");
        return new RouteBranch(key == null ? null : String.valueOf(key),
                db == null ? null : String.valueOf(db), src);
    }

    /** This branch with {@code database} restamped (in place when present — order preserved). */
    RouteBranch withDatabase(String database) {
        return new RouteBranch(key, database, source);
    }

    /** The entry as persisted: the verbatim source with the modeled values substituted in place. */
    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>(source);
        if (key != null) out.put("key", key);
        if (database != null) out.put("database", database);
        return out;
    }

    /** The {@code branches} list of {@code config}, typed — or {@code null} when absent/not a list.
     *  A non-map entry (malformed by hand) yields a {@code null} element so callers keep it verbatim. */
    static List<RouteBranch> listFrom(Map<String, Object> config) {
        if (!(config.get("branches") instanceof List<?> branches)) return null;
        return branches.stream()
                .map(b -> b instanceof Map<?, ?> m ? fromMap(m) : null)
                .toList();
    }
}
