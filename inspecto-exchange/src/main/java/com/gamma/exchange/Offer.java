package com.gamma.exchange;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Dataset, Widget or saved View an owner Space has listed as shareable in the {@link Exchange} — the
 * metadata a consumer browses in the catalog <em>before</em> any grant exists. It carries only descriptive
 * metadata (never rows): the item's {@code kind}, its owner, a human description and its
 * {@code resultSet} (columns/types/analytic roles — the Result Set that travels with a Dataset so a
 * consumer never needs the producer's ETL-side Schema). Freshness is added by the snapshot refresh Job
 * (S2); at S1 an offer is metadata-only.
 *
 * @param kind        {@code dataset}, {@code widget} or {@code link-analysis-view}
 * @param item        the owner-side component id being offered
 * @param owner       the owning Space id
 * @param description free-text summary shown in the catalog
 * @param resultSet   descriptive Result Set metadata (may be empty until S2 populates it)
 * @param offeredBy   the actor who listed the offer
 * @param offeredAt   epoch millis the offer was listed/last updated
 * @param datasets    for a derived offer (a {@code widget}, a saved view), the ids of every Dataset it
 *                    reads — their grants travel with it (§3.5, BACKLOG D9). Empty for a Dataset offer.
 *                    A widget binds exactly one; a view may read several
 */
public record Offer(String kind, String item, String owner, String description,
                    Map<String, Object> resultSet, String offeredBy, long offeredAt,
                    List<String> datasets) {

    public Offer {
        resultSet = resultSet == null ? Map.of() : Map.copyOf(resultSet);
        datasets = datasets == null ? List.of() : List.copyOf(datasets);
    }

    /** A Dataset offer (no bound-dataset link). */
    public Offer(String kind, String item, String owner, String description,
                 Map<String, Object> resultSet, String offeredBy, long offeredAt) {
        this(kind, item, owner, description, resultSet, offeredBy, offeredAt, List.of());
    }

    /** Stable ledger key for an offer — unique per {@code (owner, kind, item)}. */
    public String key() {
        return owner + "~" + kind + "~" + item;
    }

    /** TOON/JSON-ready map (stable key order). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("item", item);
        m.put("owner", owner);
        m.put("description", description == null ? "" : description);
        m.put("resultSet", resultSet);
        m.put("offeredBy", offeredBy == null ? "" : offeredBy);
        m.put("offeredAt", offeredAt);
        m.put("datasets", datasets);
        return m;
    }

    @SuppressWarnings("unchecked")
    static Offer fromMap(Map<String, Object> m) {
        Object rs = m.get("resultSet");
        return new Offer(
                Ledger.str(m, "kind"), Ledger.str(m, "item"), Ledger.str(m, "owner"),
                Ledger.str(m, "description"),
                rs instanceof Map ? (Map<String, Object>) rs : Map.of(),
                Ledger.str(m, "offeredBy"), Ledger.asLong(m.get("offeredAt")), datasetsOf(m));
    }

    /**
     * The bound-Dataset ids off a persisted offer. {@code datasets} is the canonical spelling this record
     * writes; the singular {@code dataset} is read <em>only</em> to keep ledgers written before D9 widened
     * the field loadable — <b>do not "unify" it away</b>, and do not start writing it again (one concept,
     * one persisted spelling: that is what keeps a widget offer from developing a split brain).
     */
    private static List<String> datasetsOf(Map<String, Object> m) {
        if (m.get("datasets") instanceof List<?> l)
            return l.stream().filter(java.util.Objects::nonNull).map(Object::toString)
                    .filter(s -> !s.isBlank()).distinct().toList();
        String legacy = Ledger.str(m, "dataset");
        return legacy == null || legacy.isBlank() ? List.of() : List.of(legacy);
    }
}
