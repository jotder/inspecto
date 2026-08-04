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
 * @param target   the logical target being summarised (a relation from {@code ConsignmentReader.relations()}).
 * @param keys     the grain — e.g. {@code {record_day=2026-07-01}}. Empty means one row for the whole target.
 * @param measures the measures at that grain; must include {@code count} (§7.2).
 */
@PublicApi(since = "5.0.0")
public record SummaryRow(String target, Map<String, String> keys, List<Measure> measures) {

    public SummaryRow {
        keys = (keys == null) ? Map.of() : Map.copyOf(keys);
        measures = (measures == null) ? List.of() : List.copyOf(measures);
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
