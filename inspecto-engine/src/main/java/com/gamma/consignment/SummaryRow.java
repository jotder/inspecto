package com.gamma.consignment;

import com.gamma.api.PublicApi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One summary row a processor emits: the grain it is summarising at, plus its {@link Measure}s.
 *
 * <p>{@code count} is <b>not</b> a field here even though §7.2 makes it mandatory — it is a measure like any
 * other, so a row that omits it is a <em>runtime</em> refusal by {@link SummaryEmitter} rather than a
 * compile-time impossibility. That is deliberate: §7.2's requirement is a rule the seam enforces and a test can
 * prove red, and a mandatory record component could not be caught failing.
 *
 * <p><b>{@code bounds} is how a summary gets event-time addressing at all (§3.1), and it is stated, never
 * derived.</b> Every other write path folds bounds out of the rows it is writing; a summary row has no rows —
 * it is already an aggregate — so the only party that ever saw the detail is the processor that aggregated it.
 * ⚠ The tempting shortcut is to read a grain key such as {@code record_day} as if it were an event time. It is
 * not: it is the <em>bucket</em> the rows fell into, so bounds derived from it collapse a whole day to a single
 * instant, and the Selector then <b>skips</b> a file that does overlap the query — a false negative, which is
 * strictly worse than the honest {@code null} of a row that declares nothing.
 *
 * @param target   the logical target being summarised (a relation from {@code ConsignmentReader.relations()}).
 * @param keys     the grain — e.g. {@code {record_day=2026-07-01}}. Empty means one row for the whole target.
 * @param measures the measures at that grain; must include {@code count} (§7.2).
 * @param bounds   the event-time range of the <b>detail rows behind this row</b>, or {@code null} when the
 *                 processor does not know it. Build it with {@link EventTimeBounds#of}. Optional by design:
 *                 an absent bound degrades addressing, a wrong one corrupts it.
 */
@PublicApi(since = "4.0.0")
public record SummaryRow(String target, Map<String, String> keys, List<Measure> measures,
                         EventTimeBounds bounds) {

    public SummaryRow {
        keys = (keys == null) ? Map.of() : Map.copyOf(keys);
        measures = (measures == null) ? List.of() : List.copyOf(measures);
    }

    /** A row that declares no event time — the shape every processor written before addressing existed uses. */
    public SummaryRow(String target, Map<String, String> keys, List<Measure> measures) {
        this(target, keys, measures, null);
    }

    /** The mandatory row count (§7.2), or {@code null} when the row omits it — which is a refusal. */
    Double count() {
        for (Measure m : measures)
            if (SummaryEmitter.COUNT.equals(m.name())) return m.value();
        return null;
    }

    /** The measures by name, preserving emit order (the order a refusal message reports them in). */
    Map<String, Measure> byName() {
        Map<String, Measure> out = new LinkedHashMap<>();
        for (Measure m : measures) if (m != null) out.put(m.name(), m);
        return out;
    }
}
