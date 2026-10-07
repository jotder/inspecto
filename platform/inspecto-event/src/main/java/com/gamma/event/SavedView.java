package com.gamma.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A named, reusable event filter — the "Saved Views" feature of the Phase-1 Event Viewer. It stores
 * the same query-parameter keys the {@code GET /events/search} endpoint accepts ({@code level},
 * {@code type}, {@code pipeline}, {@code correlationId}, {@code q}, {@code from}, {@code to}), so a
 * saved view is just a bookmarked search the operator can re-apply with one click.
 *
 * @param name      unique view name (operator-chosen)
 * @param filters   search-param key→value map (only the set keys are present)
 * @param createdAt creation time (epoch millis)
 * @param extra     author-owned {@code x-} annotations (MODULE-REORG-P4-2): kept through a save; the routes refuse any other unmodelled key
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public record SavedView(String name, Map<String, String> filters, long createdAt, Map<String, Object> extra) {

    /** The keys a stored view models; any other key rides in {@link #extra}. */
    public static final Set<String> MODELLED = Set.of("name", "filters", "createdAt");

    public SavedView(String name, Map<String, String> filters, long createdAt) {
        this(name, filters, createdAt, Map.of());
    }

    public SavedView {
        filters = filters == null ? Map.of() : Map.copyOf(filters);
        extra = extra == null || extra.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(extra));
    }

    /** The keys of a stored/imported view document that the record does not model. */
    public static Map<String, Object> extraOf(Map<String, ?> doc) {
        Map<String, Object> out = new LinkedHashMap<>();
        doc.forEach((k, v) -> { if (!MODELLED.contains(k)) out.put(k, v); });
        return out;
    }

    /** JSON-ready view (stable key order). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("filters", filters);
        m.put("createdAt", createdAt);
        extra.forEach(m::putIfAbsent);
        return m;
    }
}
