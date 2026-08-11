package com.gamma.consignment;

import com.gamma.api.PublicApi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * The {@link SummaryEmitter} that enforces §7.2 and collects what survived.
 *
 * <p><b>Storage lives in {@link SummaryWriter}, not here.</b> This class validates and holds; §7.3's writer turns
 * {@link #emitted()} into one Parquet file per (Consignment × record-day) and the adapter registers them. Keeping
 * the two apart is what let the guardrail ship and be proved red before any storage format existed — and the
 * guardrail's tests did not change when the sink arrived. §7.4's rollup cache is still design, deliberately: it
 * is a cache that may be deleted entirely without data loss, so nothing depends on it existing.
 */
@PublicApi(since = "5.0.0")
public final class GuardedSummaryEmitter implements SummaryEmitter {

    /**
     * Name segments that signal a measure is not additive. Declaring one {@code ADDITIVE} is the precise
     * silent-wrong-number case §7.2 warns about: summing averages, ratios, distinct counts or extrema is
     * arithmetically meaningless, and nothing downstream would flag it.
     *
     * <p>{@code min}/{@code max} are here too. They merge cleanly — but by {@code MIN}/{@code MAX}, not by
     * addition, and {@link Measure.Composability} has no member for that, so they must be declared
     * {@code BUCKETED} or {@code COMPUTED_FROM_DETAIL} rather than mislabelled additive.
     */
    private static final Pattern NON_ADDITIVE = Pattern.compile(
            "^(avg|average|mean|median|mode|ratio|rate|pct|percent|percentile|p\\d+"
                    + "|stddev|stdev|sd|variance|var|distinct|unique|nunique|min|max)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * The exact shape {@link EventTimeBounds} documents: ISO-8601 local date-<b>time</b>, optionally with a
     * fraction. Enforced here rather than left to a parse at write time because the registry compares these
     * <em>lexicographically</em> in SQL, and mixing widths (or admitting a bare {@code 2026-07-01}) makes that
     * comparison quietly wrong instead of loudly broken.
     */
    private static final Pattern EVENT_TIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?");

    private final List<SummaryRow> emitted = new ArrayList<>();

    @Override
    public void emit(SummaryRow row) {
        List<String> violations = check(row);
        if (!violations.isEmpty()) {
            StringJoiner why = new StringJoiner("; ");
            for (String v : violations) why.add(v);
            throw new IllegalArgumentException("summary row refused (§7.2) — " + why);
        }
        emitted.add(row);
    }

    /** The rows that passed, in emit order. */
    public List<SummaryRow> emitted() {
        return List.copyOf(emitted);
    }

    /**
     * Every §7.2 violation in {@code row}, empty when it is acceptable. Package-private so the guardrail can be
     * tested directly as well as through {@link #emit}.
     */
    static List<String> check(SummaryRow row) {
        List<String> out = new ArrayList<>();
        if (row == null) return List.of("the summary row is null");
        if (row.target() == null || row.target().isBlank())
            out.add("no target: a summary must say what it summarises");
        if (row.measures().isEmpty())
            out.add("no measures: at least the mandatory '" + COUNT + "' is required");

        Set<String> seen = new LinkedHashSet<>();
        for (Measure m : row.measures()) {
            if (m == null) {
                out.add("a null measure");
                continue;
            }
            String name = m.name();
            if (name == null || name.isBlank()) {
                out.add("a measure with no name");
                continue;
            }
            if (!seen.add(name))
                out.add("measure '" + name + "' is declared twice");
            if (m.composability() == null) {
                // The whole point of the guard: an undeclared measure is refused, never assumed additive.
                out.add("measure '" + name + "' declares no composability — §7.2 requires ADDITIVE, "
                        + "BUCKETED or COMPUTED_FROM_DETAIL, and it is never inferred");
                continue;
            }
            if (Double.isNaN(m.value()) || Double.isInfinite(m.value()))
                out.add("measure '" + name + "' is " + m.value()
                        + " — a non-finite value silently poisons every aggregate it merges into");
            if (m.composability() == Measure.Composability.ADDITIVE && isNonAdditive(name))
                out.add("measure '" + name + "' is declared ADDITIVE but its name says it is not — "
                        + "summing it is meaningless; declare it BUCKETED (carry the distribution) or "
                        + "COMPUTED_FROM_DETAIL (recompute when asked)");
        }

        Double count = row.count();
        if (count == null) {
            out.add("no '" + COUNT + "' measure — §7.2 makes it mandatory on every summary row, because it is "
                    + "what makes the aggregate reconcilable against detail");
        } else {
            if (count < 0) out.add("'" + COUNT + "' is negative (" + count + ")");
            Measure c = row.byName().get(COUNT);
            if (c != null && c.composability() != Measure.Composability.ADDITIVE)
                out.add("'" + COUNT + "' must be ADDITIVE, not " + c.composability());
        }
        out.addAll(checkBounds(row.bounds()));
        return out;
    }

    /**
     * Every problem with a declared event-time range, empty when it is absent (which is allowed) or sound.
     *
     * <p>A range is <b>optional but not approximate</b>: absent means "I do not know", and the reader degrades
     * to a full scan. Anything present is taken as fact by the Selector, so it is checked here — at the seam
     * the author calls — rather than at write time, where the same refusal would name a file instead of a row.
     */
    private static List<String> checkBounds(EventTimeBounds b) {
        if (b == null) return List.of();
        List<String> out = new ArrayList<>();
        if (b.min() == null || b.max() == null) {
            // A half-range is the dangerous shape: it reads as bounded but covers nothing on the open side.
            out.add("event-time bounds declare only " + (b.min() == null ? "a max" : "a min")
                    + " — declare both endpoints or none, because a half-open range prunes as if the missing "
                    + "side were empty");
            return out;
        }
        for (String s : List.of(b.min(), b.max()))
            if (!EVENT_TIME.matcher(s).matches())
                out.add("event-time bound '" + s + "' is not ISO-8601 local date-time (" + EVENT_TIME.pattern()
                        + ") — a date alone is a grain, not an event time, and collapses a whole day to its "
                        + "first instant");
        if (!out.isEmpty()) return out;

        if (b.min().compareTo(b.max()) > 0) {
            out.add("event-time bounds run backwards: min " + b.min() + " is after max " + b.max());
        } else {
            long derived = EventTimeBounds.of(b.min(), b.max()).spreadMs();
            if (b.spreadMs() != derived)
                out.add("event-time spreadMs is " + b.spreadMs() + " but " + b.min() + " → " + b.max()
                        + " is " + derived + "ms — build bounds with EventTimeBounds.of(min, max) rather than "
                        + "stating a spread that can disagree with its own endpoints");
        }
        return out;
    }

    /**
     * §7.2's <b>free reconciliation</b>: the summed {@code count} per target against the detail row count the
     * §11.3 registry recorded for it. Returns a description of every mismatch, or empty when they agree.
     *
     * <p>Reported, not thrown: a processor may legitimately summarise a filtered subset, so a mismatch is a
     * signal for the operator rather than proof of a defect. It is the same conservation check §7.2 describes,
     * available here for nothing because both numbers are already in hand.
     */
    public Optional<String> reconcile(List<ConsignmentOutput> outputs) {
        Map<String, Double> summarised = new LinkedHashMap<>();
        for (SummaryRow r : emitted) {
            Double c = r.count();
            if (c != null) summarised.merge(r.target(), c, Double::sum);
        }
        Map<String, Long> detail = new LinkedHashMap<>();
        if (outputs != null)
            for (ConsignmentOutput o : outputs)
                if (o.state() == ConsignmentOutput.State.LIVE)
                    detail.merge(o.tableName() == null ? "consignment" : o.tableName(), o.rows(), Long::sum);

        StringJoiner diff = new StringJoiner("; ");
        for (Map.Entry<String, Double> e : summarised.entrySet()) {
            long expected = detail.getOrDefault(e.getKey(), 0L);
            if (Math.abs(e.getValue() - expected) > 0.5d)
                diff.add(e.getKey() + ": summarised " + e.getValue().longValue()
                        + " vs. " + expected + " detail row(s)");
        }
        return diff.length() == 0 ? Optional.empty() : Optional.of(diff.toString());
    }

    /** Whether any {@code _}/{@code -}/{@code .}-separated segment of {@code name} signals non-additivity. */
    private static boolean isNonAdditive(String name) {
        if (NON_ADDITIVE.matcher(name).matches()) return true;
        for (String seg : name.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
            if (!seg.isEmpty() && NON_ADDITIVE.matcher(seg).matches()) return true;
        return false;
    }
}
