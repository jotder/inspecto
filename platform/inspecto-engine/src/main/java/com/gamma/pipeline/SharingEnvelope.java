package com.gamma.pipeline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The sharing envelope ({@code owner}, {@code shares}) of something derived from several components — KPI History
 * (`BI-INSIGHTS-1` I-3), a model's score Datasets (I-14): readable only by who may read EVERY source. A component
 * without {@code shares} is open to everyone, so the shared ones' envelope applies; shared ones must be shared alike,
 * since an envelope cannot state an intersection.
 */
public final class SharingEnvelope {

    private SharingEnvelope() {}

    /**
     * {@link #of(List)} over {@code sources} plus, for every Mapping Dataset among them ({@code origin: mapping}), its
     * {@code basedOn} base — recursively through Mappings (`BI-INSIGHTS-1` loose ends A, operator 2026-11-26): a Mapping
     * reads its base's rows, so the base's readers bound the derived thing too. A base the registry cannot resolve, or a
     * Mapping chain that loops, refuses (fail closed).
     *
     * @throws SecurityException when two sources (bases included) are shared differently, or a base cannot be resolved
     */
    public static Map<String, Object> of(ComponentStore store, List<Map<String, Object>> sources) {
        return of(withBases(store, sources));
    }

    /** {@code sources} followed by each Mapping's base chain. */
    static List<Map<String, Object>> withBases(ComponentStore store, List<Map<String, Object>> sources) {
        List<Map<String, Object>> out = new ArrayList<>(sources);
        for (Map<String, Object> s : sources) {
            Set<String> chain = new HashSet<>();
            Map<String, Object> cur = s;
            while ("mapping".equals(String.valueOf(cur.get("origin")))) {
                if (!(cur.get("basedOn") instanceof String base) || base.isBlank() || !chain.add(base))
                    throw new SecurityException("a Mapping's base cannot be resolved — refused");
                Map<String, Object> b;
                try {
                    b = store.get("dataset", base).map(ComponentRegistry.Component::content).orElse(null);
                } catch (RuntimeException malformed) {
                    b = null;
                }
                if (b == null) throw new SecurityException("the base Dataset '" + base + "' of a Mapping does not exist — refused");
                out.add(b);
                cur = b;
            }
        }
        return out;
    }

    /**
     * @return the derived thing's {@code owner} / {@code shares} (empty = open)
     * @throws SecurityException when two sources are shared differently
     */
    public static Map<String, Object> of(List<Map<String, Object>> sources) {
        Map<String, Object> shared = null;
        for (Map<String, Object> s : sources) {
            if (!s.containsKey("shares")) continue;
            if (shared == null) shared = s;
            else if (!(Objects.equals(shared.get("owner"), s.get("owner")) && Objects.equals(shared.get("shares"), s.get("shares"))))
                throw new SecurityException("its sources are shared differently; it would show one source's values to "
                        + "another's readers — share them alike, or leave all but one unshared");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (shared != null) for (String k : List.of("owner", "shares")) if (shared.containsKey(k)) out.put(k, shared.get(k));
        return out;
    }
}
