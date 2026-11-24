package com.gamma.query;

import com.gamma.analytics.time.TimeGrain;
import com.gamma.analytics.time.TimeSeriesSql;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import static com.gamma.util.SqlBuilder.typedOperand;
import static com.gamma.util.SqlIdent.q;
import static com.gamma.util.Values.trimToNull;

/**
 * Compiles a headless BI query spec (BI-7) — dataset + measures + dimensions + filters — into one guarded
 * DuckDB SELECT. The <b>server-side twin</b> of {@code inspecto-ui/.../viz/query-spec.ts#compileSpec} (that
 * file's declared "swap seam between offline AlaSQL and a backend DuckDB endpoint"): same aggregation set,
 * same {@code agg_field} measure ids, same filter semantics, so a widget's QuerySpec round-trips 1:1.
 *
 * <p>Everything is built from <b>validated identifiers and bound, typed parameters</b> — no caller SQL text
 * and no filter value ever enters the statement ({@code MEASURE-SQL-BIND-1}) — and the compiled text is still {@code SqlGuard}-checked by the route (defence in
 * depth). Unknown aggregation, operator, or a non-identifier field → {@link IllegalArgumentException}
 * (→ 422 at the route).
 */
public final class MeasureCompiler {

    /** Package-private for the same reason as {@link #AGGS}: the authoring form mirrors this pattern to
     *  validate {@code group_by} columns, and {@code MeasureGrammarContractTest} pins the two together. */
    static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** Package-private, not public: {@code MeasureGrammarContractTest} pins it against the committed
     *  {@code inspecto-ui/src/app/inspecto/contracts/measure-grammar.contract.json} the Angular authoring form
     *  validates from, so adding an aggregate here fails that test until the client agrees. */
    static final List<String> AGGS = List.of("count", "countDistinct", "sum", "avg", "min", "max",
            "median", "stddev", "variance", "p05", "p25", "p75", "p90", "p95", "p99");
    /** The fixed-name percentile aggregates (D-AN5) and the fraction each one asks {@code QUANTILE_CONT} for —
     *  continuous, interpolated between neighbours (D-AN3). A fixed name rather than a parameter keeps a Measure
     *  the {@code {agg, field}} pair (parked-rows D10). */
    static final Map<String, String> PERCENTILES = Map.of(
            "p05", "0.05", "p25", "0.25", "p75", "0.75", "p90", "0.9", "p95", "0.95", "p99", "0.99");
    /** Time buckets a temporal dimension may be grouped into. Package-private and contract-pinned for the
     *  same reason as {@link #AGGS}: the UI picks the grain, this side does the bucketing, and a grain the
     *  two disagree on silently groups by the wrong period. The UI's extra {@code auto} means "no grain"
     *  and is never sent. */
    static final List<String> GRAINS = List.of("day", "week", "month");
    /** Aggregates that are only defined relative to a {@link Window}'s as-of date (D-AN12): {@code recency(field)} is the
     *  whole days from the latest {@code field} in the window to the as-of date. Kept OUT of {@link #AGGS} so the
     *  shorthand {@code agg(field)} surfaces (summarize, KPI, Alert Rule, report) keep refusing what they cannot give an
     *  as-of; {@code MeasureGrammarContractTest} pins this list too. */
    static final List<String> AS_OF_AGGS = List.of("recency");
    /** The widest trailing window a spec may name, in days (ten years). */
    static final int MAX_WINDOW_DAYS = 3660;

    private MeasureCompiler() {}

    /** One aggregation over a column ({@code count} ignores the field; all others require one). */
    public record Measure(String agg, String field) {
        /** The stable result-column id ({@code sum_AMT}, {@code count}) — matches the UI's measureId(). */
        public String id() {
            return "count".equals(agg) ? "count" : (agg + "_" + field).replaceAll("[^A-Za-z0-9_]", "_");
        }
    }

    /** One filter term; {@code op} ∈ = != > >= < <= in like isNull notNull. */
    public record Filter(String field, String op, Object value) {}

    /**
     * A trailing window as of a date (C1, D-AN12): the rows whose {@code field}, read as a DATE, falls in
     * {@code (asOf − days, asOf]} — whole days, the timestamp's own wall-clock date, no zone involved. {@code days == null}
     * leaves it open at the start (everything up to and including {@code asOf}). Applied to EVERY measure and ratio of
     * the spec, and to nothing else: a window is a row restriction, not a Measure, so a Measure stays an
     * {@code {agg, field}} pair. {@code asOf} is always stated (never "now"), so a run is reproducible. It also gives
     * {@link #AS_OF_AGGS} their as-of.
     */
    public record Window(String field, LocalDate asOf, Integer days) {
        public Window {
            safeIdent(field, "window field");
            if (asOf == null) throw new IllegalArgumentException("window needs an 'asOf' date");
            if (days != null && (days < 1 || days > MAX_WINDOW_DAYS))
                throw new IllegalArgumentException("window 'days' must be 1.." + MAX_WINDOW_DAYS + ": " + days);
        }

        /** The exclusive lower bound {@code asOf − days}, or {@code null} for an open window. */
        LocalDate after() {
            return days == null ? null : asOf.minusDays(days);
        }
    }

    /**
     * A ratio of two aggregates (C1, D-AN12): {@code num / den} as DOUBLE, NULL when the denominator is 0 or NULL. Its
     * column id is {@code <num id>_per_<den id>}. A separate list on the spec, so the {@code {agg, field}} Measure pair
     * and the "Risk factors take no free arithmetic" rule both stand; only the fixed {@link #AGGS} may appear in it.
     */
    public record Ratio(Measure num, Measure den) {
        public Ratio {
            if (num == null || den == null) throw new IllegalArgumentException("a ratio needs 'num' and 'den'");
        }

        public String id() {
            return num.id() + "_per_" + den.id();
        }
    }

    /**
     * One ORDER BY term. {@code agg} non-null marks a <b>hidden aggregate</b> term (CATEGORY-SORT-ORDER-1): the
     * {@code field} is a Dataset column that is NOT in the result, ordered as {@code agg(field)} while the
     * grouping stays unchanged. {@code null} = an ordinary term over a result column (a grouped Dimension or a Measure id).
     * {@code cosmetic} marks the chart's own category ordering over a grouped dimension (the SPA sets it): when that column is
     * a masked key the route DROPS the term for a caller who cannot reveal instead of refusing (BiResultMasking.stripCosmeticOrder).
     */
    public record Sort(String field, boolean descending, String agg, boolean cosmetic) {
        public Sort(String field, boolean descending, String agg) { this(field, descending, agg, false); }

        public Sort(String field, boolean descending) { this(field, descending, null, false); }

        public boolean hidden() { return agg != null; }
    }

    /** The comparison kinds a spec may ask for (BI-INSIGHTS-1 I-1). Contract-pinned with the SPA like {@link #AGGS}. */
    static final List<String> COMPARE_KINDS = List.of("previous", "lastYear", "target", "average");
    /** The suffixes of the three columns a comparison adds per Measure / ratio: the compared value, the change, the % change. */
    public static final String CMP = "__cmp", DELTA = "__delta", PCT = "__pct";

    /**
     * A comparison computed in the Widget's own statement (BI-INSIGHTS-1 I-1, D-BI7 — query time, no storage, the same
     * caps, guard, sandbox and masking). For every Measure and ratio {@code m} it adds {@code m__cmp} (the compared value),
     * {@code m__delta} ({@code m − m__cmp}) and {@code m__pct} (the change as a percentage of {@code |m__cmp|}). A comparison
     * that has no value — no row in the earlier bucket, a zero baseline for the % — is NULL, never 0.
     * <ul>
     *   <li>{@code previous}: the bucket before, along {@code field} (a grained {@code groupBy} column), per the other
     *       grouped columns — the kernel's {@code TimeSeriesSql.shift}, matched by bucket, so a gap gives NULL.</li>
     *   <li>{@code lastYear}: the same period a year earlier — a day looks at the same date (29 Feb at 28 Feb), a week 52 weeks
     *       back (the same weekdays), a month 12 months back.</li>
     *   <li>{@code target}: a constant, {@code target} (the SPA sends the bound KPI's target).</li>
     *   <li>{@code average}: the mean of the Measure over the result's rows along {@code field}, per the other grouped columns.</li>
     * </ul>
     * Each comparison sees only the rows the spec's filters, condition and window keep: the first bucket of a filtered range
     * has no previous value.
     */
    public record Compare(String kind, String field, Double target) {
        public Compare {
            if (kind == null || !COMPARE_KINDS.contains(kind))
                throw new IllegalArgumentException("unknown compare kind '" + kind + "' (one of " + COMPARE_KINDS + ")");
            if ("target".equals(kind) && (target == null || !Double.isFinite(target)))
                throw new IllegalArgumentException("compare 'target' needs a finite number 'target'");
            if (!"target".equals(kind) && target != null)
                throw new IllegalArgumentException("compare '" + kind + "' takes no 'target'");
            if (field != null) safeIdent(field, "compare field");
        }

        boolean shifted() {
            return "previous".equals(kind) || "lastYear".equals(kind);
        }
    }

    /** What one comparison column is: the kind, the Measure / ratio id it compares, and which part ({@code cmp | delta | pct}). */
    public record CompareColumn(String kind, String of, String part) {}

    /** The comparison columns a spec returns, by result column name, in select order; empty without a comparison. */
    public static Map<String, CompareColumn> compareColumns(Spec spec) {
        Map<String, CompareColumn> out = new LinkedHashMap<>();
        if (spec.compare() == null) return out;
        for (String id : valueIds(spec)) {
            out.put(id + CMP, new CompareColumn(spec.compare().kind(), id, "cmp"));
            out.put(id + DELTA, new CompareColumn(spec.compare().kind(), id, "delta"));
            out.put(id + PCT, new CompareColumn(spec.compare().kind(), id, "pct"));
        }
        return out;
    }

    private static List<String> valueIds(Spec spec) {
        List<String> ids = new ArrayList<>();
        for (Measure m : spec.measures()) ids.add(m.id());
        for (Ratio r : spec.ratios()) ids.add(r.id());
        return ids;
    }

    /** The aggregates a hidden {@code orderBy} term may carry; {@code min} is the default. Closed vocabulary. */
    static final List<String> ORDER_AGGS = List.of("min", "max");

    /** The Dataset columns a spec orders by WITHOUT returning them — what the route must vet (existence, classification). */
    public static List<String> hiddenOrderColumns(Spec spec) {
        return spec.orderBy().stream().filter(Sort::hidden).map(Sort::field).distinct().toList();
    }

    /**
     * The parsed spec: at least one measure or one dimension; {@code dataset} is resolved by the route.
     * {@code grains} maps a {@code groupBy} column to the time bucket it is grouped into — absent = the
     * raw value, which is what every widget sent before the grain reached the wire.
     *
     * <p>{@code when} is an optional {@link ConditionTree} group (the structured condition authored in the UI),
     * ANDed after the flat {@code filters} and rendered by {@link ConditionSql#predicateBound} — escaped identifiers and
     * bound operand values, never author text. It is ADDITIVE: the flat {@code filters} keep their own typed-literal
     * semantics untouched (see {@link ConditionSql} for how {@code when} types by operand text instead).
     * {@code null} or empty = no extra constraint.
     */
    public record Spec(String dataset, List<Measure> measures, List<String> groupBy,
                       Map<String, String> grains, List<Filter> filters, List<Sort> orderBy, int limit,
                       Map<String, Object> when, Window window, List<Ratio> ratios, Compare compare) {
        public Spec {
            ratios = ratios == null ? List.of() : List.copyOf(ratios);
        }

        /** The pre-{@code compare} shape. */
        public Spec(String dataset, List<Measure> measures, List<String> groupBy,
                    Map<String, String> grains, List<Filter> filters, List<Sort> orderBy, int limit,
                    Map<String, Object> when, Window window, List<Ratio> ratios) {
            this(dataset, measures, groupBy, grains, filters, orderBy, limit, when, window, ratios, null);
        }

        /** The pre-{@code when} shape: every existing caller stays source-compatible. */
        public Spec(String dataset, List<Measure> measures, List<String> groupBy,
                    Map<String, String> grains, List<Filter> filters, List<Sort> orderBy, int limit) {
            this(dataset, measures, groupBy, grains, filters, orderBy, limit, null, null, null, null);
        }

        /** The pre-{@code window} shape: no window, no ratios. */
        public Spec(String dataset, List<Measure> measures, List<String> groupBy,
                    Map<String, String> grains, List<Filter> filters, List<Sort> orderBy, int limit,
                    Map<String, Object> when) {
            this(dataset, measures, groupBy, grains, filters, orderBy, limit, when, null, null, null);
        }

        /** The same spec with another row cap — how a route asks for ONE row past its cap to detect truncation. */
        public Spec withLimit(int newLimit) {
            return new Spec(dataset, measures, groupBy, grains, filters, orderBy, newLimit, when, window, ratios, compare);
        }

        /** The same spec with another ORDER BY. */
        public Spec withOrderBy(List<Sort> newOrderBy) {
            return new Spec(dataset, measures, groupBy, grains, filters, newOrderBy, limit, when, window, ratios, compare);
        }

        /** The same spec re-pointed at another relation — how a route runs it over a bound query's CTE. */
        public Spec withDataset(String newDataset) {
            return new Spec(newDataset, measures, groupBy, grains, filters, orderBy, limit, when, window, ratios, compare);
        }

        /** True when the result is aggregated: at least one measure or ratio. */
        boolean aggregated() {
            return !measures.isEmpty() || !ratios.isEmpty();
        }
    }

    /** Parse the {@code POST /bi/query} body into a validated {@link Spec}. */
    /**
     * Split the measure <b>shorthand</b> — {@code count} | {@code agg(field)} — into the
     * {@code {agg, field}} maps {@link #parse} consumes. The shorthand is what a human authors: a
     * {@code transform.summarize} node's {@code measures[]}, a materialize job's comma-separated
     * {@code measures}, a report job's the same.
     *
     * <p>⚠ This is the grammar's split, and it belongs here beside {@link #AGGS} and {@link #parse}
     * rather than at each caller. All three production call sites — {@code MaterializeTask},
     * {@code ReportJob} and {@code RowShaper.summarize} — now go through it
     * (`MEASURE-SHORTHAND-ONE-HOME-1`).
     *
     * <p>⛔ {@code RecipeVerbParityTest} deliberately still hand-rolls the split and <b>must keep doing
     * so</b>: it exists to prove the recipe path produces byte-compatible measures, and a parity test
     * that calls the thing it is checking proves nothing. That copy is independent verification, not
     * duplication.
     *
     * @param shorthand the authored entries; each is {@code toString().trim()}ed, so a TOON list of
     *                  plain strings and a pre-split config value both work
     * @param context   names the offending config in the exception (e.g. {@code "node 'roll'"}), or
     *                  {@code null}/blank for an unprefixed message. ⚠ The prefix is optional ONLY so
     *                  the three migrated call sites keep their messages byte-identical — a job says
     *                  "measure must be…", a pipeline node says "node 'x': measure must be…". Prefer a
     *                  context in new callers.
     * @throws IllegalArgumentException on an entry that is neither {@code count} nor {@code agg(field)}
     */
    public static List<Map<String, Object>> splitShorthand(List<?> shorthand, String context) {
        String prefix = context == null || context.isBlank() ? "" : context + ": ";
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : shorthand) {
            String m = String.valueOf(o).trim();
            if ("count".equals(m)) { out.add(Map.of("agg", "count")); continue; }
            int p = m.indexOf('(');
            if (p < 0 || !m.endsWith(")"))
                throw new IllegalArgumentException(prefix
                        + "measure must be count or agg(field), got '" + m + "'");
            out.add(Map.of("agg", m.substring(0, p), "field", m.substring(p + 1, m.length() - 1)));
        }
        return out;
    }

    /** The aggregations that require a NUMERIC field. {@code min}/{@code max} order dates and text
     *  perfectly well, and {@code count}/{@code countDistinct} ignore the value entirely — so only
     *  these are a type error rather than a taste question: the sum, the mean and the statistics. */
    public static final List<String> NUMERIC_AGGS = List.of("sum", "avg",
            "median", "stddev", "variance", "p05", "p25", "p75", "p90", "p95", "p99");

    public static Spec parse(Map<String, Object> body, int defaultLimit, int maxLimit) {
        String dataset = trimToNull(body.get("dataset"));
        if (dataset == null) throw new IllegalArgumentException("body must include 'dataset'");

        Window window = parseWindow(body.get("window"));
        List<Measure> measures = new ArrayList<>();
        if (body.get("measures") instanceof List<?> ms)
            for (Object o : ms)
                if (o instanceof Map<?, ?> m) measures.add(parseMeasure(m, window != null));
        List<Ratio> ratios = new ArrayList<>();
        if (body.get("ratios") instanceof List<?> rs)
            for (Object o : rs) {
                if (!(o instanceof Map<?, ?> r))
                    throw new IllegalArgumentException("each ratio must be an object {num: {agg, field}, den: {agg, field}}");
                for (Object k : r.keySet())
                    if (!"num".equals(k) && !"den".equals(k))
                        throw new IllegalArgumentException("unknown ratio key '" + k + "' (num, den)");
                if (!(r.get("num") instanceof Map<?, ?> n) || !(r.get("den") instanceof Map<?, ?> d))
                    throw new IllegalArgumentException("a ratio needs 'num' and 'den', each {agg, field}");
                ratios.add(new Ratio(parseMeasure(n, false), parseMeasure(d, false)));
            }

        List<String> groupBy = new ArrayList<>();
        if (body.get("groupBy") instanceof List<?> gs)
            for (Object g : gs) groupBy.add(safeIdent(trimToNull(g), "groupBy column"));

        if (measures.isEmpty() && ratios.isEmpty() && groupBy.isEmpty())
            throw new IllegalArgumentException("spec needs at least one measure, ratio or groupBy column");

        Map<String, String> grains = new LinkedHashMap<>();
        if (body.get("grains") instanceof Map<?, ?> gm)
            for (Map.Entry<?, ?> e : gm.entrySet()) {
                String field = safeIdent(trimToNull(e.getKey()), "grain column");
                String grain = trimToNull(e.getValue());
                if (grain == null || !GRAINS.contains(grain))
                    throw new IllegalArgumentException("unknown time grain '" + grain + "' (one of " + GRAINS + ")");
                // A grain on a column that is not grouped would silently do nothing — refuse instead.
                if (!groupBy.contains(field))
                    throw new IllegalArgumentException("grain column '" + field + "' is not in groupBy");
                grains.put(field, grain);
            }

        List<Filter> filters = new ArrayList<>();
        if (body.get("filters") instanceof List<?> fs)
            for (Object o : fs)
                if (o instanceof Map<?, ?> f)
                    filters.add(new Filter(safeIdent(trimToNull(f.get("field")), "filter field"),
                            trimToNull(f.get("op")), f.get("value")));

        List<Sort> orderBy = new ArrayList<>();
        if (body.get("orderBy") instanceof List<?> os)
            for (Object o : os)
                if (o instanceof Map<?, ?> s)
                {
                    String field = safeIdent(trimToNull(s.get("field")), "orderBy field");
                    boolean desc = "desc".equalsIgnoreCase(trimToNull(s.get("dir")));
                    String agg = trimToNull(s.get("agg"));
                    if (agg != null && !ORDER_AGGS.contains(agg))
                        throw new IllegalArgumentException("unknown orderBy agg '" + agg + "' (one of " + ORDER_AGGS + ")");
                    boolean inResult = groupBy.contains(field) || measures.stream().anyMatch(m -> m.id().equals(field))
                            || ratios.stream().anyMatch(r -> r.id().equals(field));
                    // An explicit agg, or a field that is not a result column, orders by a hidden aggregate of the Dataset column.
                    if (agg == null && !inResult) agg = "min";
                    if (agg != null && ((measures.isEmpty() && ratios.isEmpty()) || groupBy.isEmpty()))
                        throw new IllegalArgumentException("orderBy '" + field + "' (hidden " + agg
                                + ") needs at least one measure and one groupBy column");
                    boolean cosmetic = Boolean.TRUE.equals(s.get("cosmetic")) && agg == null && groupBy.contains(field);
                    orderBy.add(new Sort(field, desc, agg, cosmetic));
                }

        Map<String, Object> when = null;
        if (body.get("when") != null) {
            if (!(body.get("when") instanceof Map<?, ?> w))
                throw new IllegalArgumentException("'when' must be a condition group object");
            ConditionTree.requireGroupRoot(w);
            ConditionTree.validate(w);
            Map<String, Object> copy = new LinkedHashMap<>();
            w.forEach((k, v) -> copy.put(String.valueOf(k), v));
            when = copy;
        }

        Compare compare = parseCompare(body.get("compare"), measures, ratios, groupBy, grains, orderBy);

        int limit = body.get("limit") instanceof Number n ? n.intValue() : defaultLimit;
        return new Spec(dataset, measures, groupBy, grains, filters, orderBy,
                Math.max(1, Math.min(maxLimit, limit)), when, window, ratios, compare);
    }

    /**
     * The body's {@code compare}: {@code {kind, field?, target?}}; unknown keys refused; {@code null} = none. {@code field}
     * defaults to the one grained {@code groupBy} column ({@code average}: else the first {@code groupBy} column).
     */
    private static Compare parseCompare(Object raw, List<Measure> measures, List<Ratio> ratios, List<String> groupBy,
                                        Map<String, String> grains, List<Sort> orderBy) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> c)) throw new IllegalArgumentException("'compare' must be an object {kind, field?, target?}");
        for (Object k : c.keySet())
            if (!"kind".equals(k) && !"field".equals(k) && !"target".equals(k))
                throw new IllegalArgumentException("unknown compare key '" + k + "' (kind, field, target)");
        String kind = trimToNull(c.get("kind"));
        Double target = null;
        if (c.get("target") != null) {
            if (!(c.get("target") instanceof Number n)) throw new IllegalArgumentException("compare 'target' must be a number");
            target = n.doubleValue();
        }
        String field = trimToNull(c.get("field"));
        Compare probe = new Compare(kind, field, target);   // validates kind / target / field shape
        if (measures.isEmpty() && ratios.isEmpty())
            throw new IllegalArgumentException("compare needs at least one measure or ratio");
        if (orderBy.stream().anyMatch(Sort::hidden))
            throw new IllegalArgumentException("compare cannot be combined with an orderBy on a column outside the result");
        List<String> grained = groupBy.stream().filter(grains::containsKey).toList();
        if (probe.shifted()) {
            if (field == null) {
                if (grained.size() != 1)
                    throw new IllegalArgumentException("compare '" + kind + "' needs one time dimension with a grain"
                            + (grained.isEmpty() ? "" : " - name it as 'field' (one of " + grained + ")"));
                field = grained.get(0);
            } else if (!grained.contains(field))
                throw new IllegalArgumentException("compare field '" + field + "' must be a groupBy column with a time grain");
        } else if ("average".equals(kind)) {
            if (groupBy.isEmpty()) throw new IllegalArgumentException("compare 'average' needs at least one groupBy column");
            if (field == null) field = grained.size() == 1 ? grained.get(0) : groupBy.get(0);
            else if (!groupBy.contains(field))
                throw new IllegalArgumentException("compare field '" + field + "' must be a groupBy column");
        } else field = null;
        Compare compare = new Compare(kind, field, target);
        Spec shape = new Spec("x", measures, groupBy, grains, List.of(), List.of(), 1, null, null, ratios, compare);
        for (String col : compareColumns(shape).keySet())
            if (groupBy.stream().anyMatch(g -> g.equalsIgnoreCase(col)))
                throw new IllegalArgumentException("groupBy column '" + col + "' clashes with a compare column name");
        return compare;
    }

    /** One {@code {agg, field}} body entry. An {@link #AS_OF_AGGS} name needs the spec's window ({@code windowed}). */
    private static Measure parseMeasure(Map<?, ?> m, boolean windowed) {
        String agg = trimToNull(m.get("agg"));
        String field = trimToNull(m.get("field"));
        boolean asOf = agg != null && AS_OF_AGGS.contains(agg);
        if (agg == null || !(AGGS.contains(agg) || asOf))
            throw new IllegalArgumentException("unknown aggregation '" + agg + "' (one of " + AGGS
                    + (windowed ? ", and with a window " + AS_OF_AGGS : "") + ")");
        if (asOf && !windowed)
            throw new IllegalArgumentException("aggregation '" + agg + "' is relative to a window's as-of date: "
                    + "the spec needs a 'window' (and a ratio cannot use it)");
        if (!"count".equals(agg)) safeIdent(field, "measure field");
        return new Measure(agg, field);
    }

    /** The body's {@code window}: {@code {field, asOf: "YYYY-MM-DD", days?}}; unknown keys refused. {@code null} = none. */
    private static Window parseWindow(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> w)) throw new IllegalArgumentException("'window' must be an object {field, asOf, days}");
        for (Object k : w.keySet())
            if (!"field".equals(k) && !"asOf".equals(k) && !"days".equals(k))
                throw new IllegalArgumentException("unknown window key '" + k + "' (field, asOf, days)");
        String field = trimToNull(w.get("field"));
        String asOfText = trimToNull(w.get("asOf"));
        LocalDate asOf;
        try {
            asOf = asOfText == null ? null : LocalDate.parse(asOfText);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("window 'asOf' must be a YYYY-MM-DD date: '" + asOfText + "'");
        }
        Integer days = null;
        if (w.get("days") != null) {
            if (!(w.get("days") instanceof Number n) || n.doubleValue() != n.intValue())
                throw new IllegalArgumentException("window 'days' must be a whole number: " + w.get("days"));
            days = n.intValue();
        }
        return new Window(field, asOf, days);
    }

    /**
     * A compiled statement whose filter values are JDBC parameters: one {@code ?} in {@code sql} per entry of
     * {@code params}, in order ({@code MEASURE-SQL-BIND-1}).
     */
    public record Compiled(String sql, List<Object> params) {

        /**
         * The text, for a site that embeds it in a statement it cannot bind ({@code COPY ... TO},
         * {@code CREATE TABLE ... AS}). Only a spec without {@code filters} / {@code when} has no parameters, so
         * any other spec is refused rather than run with its placeholders unset.
         */
        public String unboundSql() {
            if (!params.isEmpty())
                throw new IllegalStateException("measure spec carries " + params.size() + " bound value(s); it must run as a prepared statement");
            return sql;
        }
    }

    /**
     * Compile the spec to the guarded SELECT (the dataset is referenced by its registered view name). Every
     * {@code filters} and {@code when} operand value is bound as a parameter, never rendered into the text —
     * only validated, quoted identifiers and fixed SQL reach the statement.
     */
    public static Compiled compile(Spec spec) {
        List<Object> params = new ArrayList<>();
        String sql = build(spec, params);
        return new Compiled(sql, List.copyOf(params));
    }

    /**
     * The same statement with operand values rendered as escaped literals — for DISPLAY only (the SQL a BI
     * response echoes) and for save-time validation. Never executed.
     */
    public static String render(Spec spec) {
        return build(spec, null);
    }

    /** {@code p} collects bound operand values; {@code null} renders them as escaped literals. */
    private static String build(Spec spec, List<Object> p) {
        if (spec.compare() != null) return buildCompare(spec, p);
        StringBuilder sql = new StringBuilder(aggregate(spec, p, false));
        appendOrderAndLimit(spec, sql);
        return sql.toString();
    }

    /** The reserved CTE names a comparison statement is built from. */
    private static final String CMP_BASE = "__cmp_base", CMP_SHIFT = "__cmp_shift";

    /**
     * A spec with a {@link Compare}: {@code WITH __cmp_base AS (<the aggregate, grained columns as bucket starts>)
     * [, __cmp_shift AS (<kernel shift>)] SELECT <dims formatted as without a comparison>, <values>, <cmp, delta, pct>
     * FROM … ORDER BY … LIMIT …}. Every bound value sits in the base, so the parameters keep their order; the outer
     * statement carries only quoted identifiers and fixed SQL.
     */
    private static String buildCompare(Spec spec, List<Object> p) {
        Compare c = spec.compare();
        List<String> ids = valueIds(spec);
        List<String> others = spec.groupBy().stream().filter(g -> !g.equals(c.field())).toList();
        StringBuilder sql = new StringBuilder("WITH ").append(q(CMP_BASE)).append(" AS (")
                .append(aggregate(spec, p, true)).append(")");
        String from = CMP_BASE;
        if (c.shifted()) {
            TimeGrain grain = TimeGrain.parse(spec.grains().get(c.field()));
            int periods = 1;
            TimeGrain step = null;
            if ("lastYear".equals(c.kind()))
                switch (grain) {
                    case DAY -> step = TimeGrain.YEAR;
                    case WEEK -> periods = 52;
                    case MONTH -> periods = 12;
                    default -> throw new IllegalArgumentException("compare 'lastYear' has no rule for grain '" + grain.wire() + "'");
                }
            List<TimeSeriesSql.Shifted> shifted = ids.stream().map(id -> new TimeSeriesSql.Shifted(id, id + CMP)).toList();
            String shift = TimeSeriesSql.shift(new TimeSeriesSql.Shift(CMP_BASE, c.field(), grain, null, others, periods,
                    shifted, step));
            sql.append(", ").append(q(CMP_SHIFT)).append(" AS (").append(shift).append(")");
            from = CMP_SHIFT;
        }
        List<String> select = new ArrayList<>();
        for (String dim : spec.groupBy())
            select.add(isBucketed(dim, spec.grains()) ? formatBucket(q(dim), spec.grains().get(dim)) + " AS " + q(dim) : q(dim));
        for (String id : ids) {
            String v = q(id);
            // min / max may return text or a date: the change and the mean are read as numbers through VARCHAR (a cast
            // every type has), so a value with no number reads NULL instead of failing the statement.
            String nv = asNumber(v);
            String cmp = switch (c.kind()) {
                case "target" -> "CAST(" + c.target() + " AS DOUBLE)";   // a parsed finite double, printed by Java
                case "average" -> "AVG(" + nv + ") OVER (" + (others.isEmpty() ? ""
                        : "PARTITION BY " + String.join(", ", others.stream().map(com.gamma.util.SqlIdent::q).toList())) + ")";
                default -> q(id + CMP);
            };
            String ncmp = "previous".equals(c.kind()) || "lastYear".equals(c.kind()) ? asNumber(cmp) : cmp;
            select.add(v);
            select.add(cmp + " AS " + q(id + CMP));
            select.add("(" + nv + " - " + ncmp + ") AS " + q(id + DELTA));
            select.add("100.0 * (" + nv + " - " + ncmp + ") / NULLIF(ABS(" + ncmp + "), 0) AS " + q(id + PCT));
        }
        sql.append(" SELECT ").append(String.join(", ", select)).append(" FROM ").append(q(from));
        appendOrderAndLimit(spec, sql);
        return sql.toString();
    }

    /** {@code expr} as a DOUBLE, NULL when it holds no number (text, a date). */
    private static String asNumber(String expr) {
        return "TRY_CAST(CAST(" + expr + " AS VARCHAR) AS DOUBLE)";
    }

    /**
     * A statement prefixed by a {@code WITH "name" AS (…) } clause (a bound query, a KPI window): a compiled statement that
     * already opens with {@code WITH} (a comparison) has its CTEs appended to the prefix's, since two {@code WITH}s do not parse.
     */
    public static String withPrefix(String withClause, String sql) {
        if (sql.startsWith("WITH ")) return withClause.stripTrailing() + ", " + sql.substring("WITH ".length());
        return withClause + sql;
    }

    /** {@code SELECT … FROM … WHERE … GROUP BY …}; {@code rawBuckets} returns a grained column as its bucket start. */
    private static String aggregate(Spec spec, List<Object> p, boolean rawBuckets) {
        List<String> select = new ArrayList<>();
        java.util.function.Function<String, String> grouped = dim -> rawBuckets && isBucketed(dim, spec.grains())
                ? TimeGrain.parse(spec.grains().get(dim)).truncateSql(q(dim)) : dimExpression(dim, spec.grains());
        // A bucketed dimension keeps its own name as the alias, so the client reads back the same column
        // whether or not a grain was applied.
        for (String dim : spec.groupBy())
            select.add(grouped.apply(dim) + (isBucketed(dim, spec.grains()) ? " AS " + q(dim) : ""));
        for (Measure m : spec.measures()) select.add(measureExpression(m, spec.window(), p) + " AS " + q(m.id()));
        for (Ratio r : spec.ratios()) select.add(ratioExpression(r) + " AS " + q(r.id()));

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(select.isEmpty() ? "*" : String.join(", ", select))
                .append(" FROM ").append(q(spec.dataset()));

        List<String> whereTerms = new ArrayList<>();
        for (Filter f : spec.filters()) whereTerms.add(filterTerm(f, p));
        if (spec.when() != null && !spec.when().isEmpty()) {
            String w;
            if (p == null) {
                w = ConditionSql.predicate(spec.when());
            } else {
                ConditionSql.Bound b = ConditionSql.predicateBound(spec.when());
                w = b.sql();
                if (!"TRUE".equals(w)) p.addAll(b.params());
            }
            if (!"TRUE".equals(w)) whereTerms.add(w);
        }
        if (spec.window() != null) windowTerms(spec.window(), whereTerms, p);
        if (!whereTerms.isEmpty()) {
            List<String> terms = whereTerms;
            sql.append(" WHERE ").append(String.join(" AND ", terms));
        }
        if (spec.aggregated() && !spec.groupBy().isEmpty())
            // The full expression, not the alias: a bucketed dimension's alias is the raw column's own name,
            // so grouping by the alias would be ambiguous with the underlying column.
            sql.append(" GROUP BY ").append(String.join(", ",
                    spec.groupBy().stream().map(grouped).toList()));
        return sql.toString();
    }

    /** {@code ORDER BY … LIMIT n} over the result's own column names (or a hidden aggregate, never with a comparison). */
    private static void appendOrderAndLimit(Spec spec, StringBuilder sql) {
        if (!spec.orderBy().isEmpty()) {
            List<String> terms = new ArrayList<>();
            for (Sort s : spec.orderBy())
                terms.add((s.hidden() ? s.agg().toUpperCase() + "(" + q(s.field()) + ")" : q(s.field()))
                        + (s.descending() ? " DESC" : " ASC"));
            sql.append(" ORDER BY ").append(String.join(", ", terms));
        } else if (!spec.groupBy().isEmpty() && spec.limit() < Integer.MAX_VALUE) {
            // BI-QUERY-TRUNCATION-1: DuckDB promises no order, so a LIMIT with no ORDER BY returned an ARBITRARY
            // subset of the groups. Order by the grouping keys so the same query returns the same rows. An
            // Integer.MAX_VALUE limit is the "no cap" sentinel (it cannot truncate) and stays unsorted.
            sql.append(" ORDER BY ").append(String.join(", ",
                    spec.groupBy().stream().map(d -> q(d) + " ASC").toList()));
        }
        sql.append(" LIMIT ").append(spec.limit());
    }

    private static boolean isBucketed(String dim, Map<String, String> grains) {
        return grains != null && grains.containsKey(dim);
    }

    /**
     * The grouped expression for one dimension: the quoted column, or — when a grain applies — its bucket
     * key <b>formatted as the UI's offline {@code bucketValue} formats it</b> ({@code YYYY-MM-DD} for
     * day/week, the week's Monday since DuckDB's {@code DATE_TRUNC('week', …)} is Monday-based;
     * {@code YYYY-MM} for month). Formatting to text rather than returning a TIMESTAMP is what makes the
     * live and offline paths produce the same category labels for the same widget.
     */
    private static String dimExpression(String dim, Map<String, String> grains) {
        String grain = grains == null ? null : grains.get(dim);
        if (grain == null) return q(dim);
        return formatBucket(TimeGrain.parse(grain).truncateSql(q(dim)), grain);
    }

    /** A bucket start as the UI's category label: {@code YYYY-MM-DD}, or {@code YYYY-MM} for a month. */
    private static String formatBucket(String bucket, String grain) {
        String format = "month".equals(grain) ? "%Y-%m" : "%Y-%m-%d";
        return "STRFTIME(" + bucket + ", '" + format + "')";
    }

    /** The aggregate of a measure in a spec: {@link #aggExpression}, or a window-relative one bound to the as-of date. */
    private static String measureExpression(Measure m, Window w, List<Object> p) {
        if (!AS_OF_AGGS.contains(m.agg())) return aggExpression(m);
        if (w == null) throw new IllegalArgumentException("aggregation '" + m.agg() + "' needs a window (its as-of date)");
        safeIdent(m.field(), "measure field");
        // whole days from the latest date in the window to the as-of date; NULL when the group has no dated row
        return "DATE_DIFF('day', CAST(MAX(" + q(m.field()) + ") AS DATE), " + dateParam(w.asOf(), p) + ")";
    }

    /** {@code num / den} as DOUBLE, NULL for a zero or NULL denominator. */
    private static String ratioExpression(Ratio r) {
        return "CAST(" + aggExpression(r.num()) + " AS DOUBLE) / NULLIF(CAST(" + aggExpression(r.den()) + " AS DOUBLE), 0)";
    }

    /** The window's row restriction: {@code CAST(f AS DATE) <= asOf} and, when it has {@code days}, {@code > asOf − days}. */
    private static void windowTerms(Window w, List<String> where, List<Object> p) {
        String col = "CAST(" + q(w.field()) + " AS DATE)";
        where.add(col + " <= " + dateParam(w.asOf(), p));
        if (w.after() != null) where.add(col + " > " + dateParam(w.after(), p));
    }

    /** A DATE operand: bound as {@code CAST(? AS DATE)} when {@code p} collects values, else a rendered {@code DATE '…'}. */
    private static String dateParam(LocalDate d, List<Object> p) {
        if (p == null) return "DATE '" + d + "'";
        p.add(d.toString());
        return "CAST(? AS DATE)";
    }

    /**
     * The validated SQL aggregate for one measure, for a caller that builds its own query around the grammar (the
     * {@code transform.timeseries.resample} Step, ANALYTICS-CORE-1 M5) — so the grammar is still evaluated only here.
     */
    public static String aggregateSql(Measure m) {
        if (!AGGS.contains(m.agg()))
            throw new IllegalArgumentException("unknown aggregation '" + m.agg() + "' (one of " + AGGS + ")");
        if (!"count".equals(m.agg())) safeIdent(m.field(), "measure field");
        return aggExpression(m);
    }

    /** The SQL aggregate for a measure — the same set as the UI's aggExpression(). */
    private static String aggExpression(Measure m) {
        return switch (m.agg()) {
            case "count" -> "COUNT(*)";
            case "countDistinct" -> "COUNT(DISTINCT " + q(m.field()) + ")";
            case "sum", "avg", "min", "max", "median" -> m.agg().toUpperCase() + "(" + q(m.field()) + ")";
            // SAMPLE statistics (n - 1), as Excel's STDEV / VAR and the common BI tools default to.
            case "stddev" -> "STDDEV_SAMP(" + q(m.field()) + ")";
            case "variance" -> "VAR_SAMP(" + q(m.field()) + ")";
            case "p05", "p25", "p75", "p90", "p95", "p99" ->
                    "QUANTILE_CONT(" + q(m.field()) + ", " + PERCENTILES.get(m.agg()) + ")";
            default -> throw new IllegalArgumentException("unknown aggregation '" + m.agg() + "'");
        };
    }

    private static String filterTerm(Filter f, List<Object> p) {
        String col = q(f.field());
        String op = f.op() == null ? "=" : f.op().trim();
        return switch (op) {
            case "=", "==", "eq" -> col + " = " + typedOperand(f.value(), p);
            case "!=", "<>", "ne" -> col + " <> " + typedOperand(f.value(), p);
            case ">", "gt" -> col + " > " + typedOperand(f.value(), p);
            case ">=", "gte" -> col + " >= " + typedOperand(f.value(), p);
            case "<", "lt" -> col + " < " + typedOperand(f.value(), p);
            case "<=", "lte" -> col + " <= " + typedOperand(f.value(), p);
            case "like" -> col + " LIKE " + typedOperand(trimToNull(f.value()), p);
            case "isNull" -> col + " IS NULL";
            case "notNull" -> col + " IS NOT NULL";
            case "in" -> {
                if (!(f.value() instanceof List<?> vs) || vs.isEmpty())
                    throw new IllegalArgumentException("'in' filter needs a non-empty value list");
                List<String> lits = new ArrayList<>();
                for (Object v : vs) lits.add(typedOperand(v, p));
                yield col + " IN (" + String.join(", ", lits) + ")";
            }
            default -> throw new IllegalArgumentException("unknown filter op '" + op + "'");
        };
    }

    private static String safeIdent(String s, String what) {
        if (s == null || !SAFE_IDENT.matcher(s).matches())
            throw new IllegalArgumentException("unsafe " + what + " '" + s + "'");
        return s;
    }
}
