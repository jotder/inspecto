package com.gamma.query;

import com.gamma.analytics.time.TimeGrain;
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
                       Map<String, Object> when, Window window, List<Ratio> ratios) {
        public Spec {
            ratios = ratios == null ? List.of() : List.copyOf(ratios);
        }

        /** The pre-{@code when} shape: every existing caller stays source-compatible. */
        public Spec(String dataset, List<Measure> measures, List<String> groupBy,
                    Map<String, String> grains, List<Filter> filters, List<Sort> orderBy, int limit) {
            this(dataset, measures, groupBy, grains, filters, orderBy, limit, null, null, null);
        }

        /** The pre-{@code window} shape: no window, no ratios. */
        public Spec(String dataset, List<Measure> measures, List<String> groupBy,
                    Map<String, String> grains, List<Filter> filters, List<Sort> orderBy, int limit,
                    Map<String, Object> when) {
            this(dataset, measures, groupBy, grains, filters, orderBy, limit, when, null, null);
        }

        /** The same spec with another row cap — how a route asks for ONE row past its cap to detect truncation. */
        public Spec withLimit(int newLimit) {
            return new Spec(dataset, measures, groupBy, grains, filters, orderBy, newLimit, when, window, ratios);
        }

        /** The same spec with another ORDER BY. */
        public Spec withOrderBy(List<Sort> newOrderBy) {
            return new Spec(dataset, measures, groupBy, grains, filters, newOrderBy, limit, when, window, ratios);
        }

        /** The same spec re-pointed at another relation — how a route runs it over a bound query's CTE. */
        public Spec withDataset(String newDataset) {
            return new Spec(newDataset, measures, groupBy, grains, filters, orderBy, limit, when, window, ratios);
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

        int limit = body.get("limit") instanceof Number n ? n.intValue() : defaultLimit;
        return new Spec(dataset, measures, groupBy, grains, filters, orderBy,
                Math.max(1, Math.min(maxLimit, limit)), when, window, ratios);
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
        List<String> select = new ArrayList<>();
        // A bucketed dimension keeps its own name as the alias, so the client reads back the same column
        // whether or not a grain was applied.
        for (String dim : spec.groupBy())
            select.add(dimExpression(dim, spec.grains()) + (isBucketed(dim, spec.grains()) ? " AS " + q(dim) : ""));
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
                    spec.groupBy().stream().map(d -> dimExpression(d, spec.grains())).toList()));
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
        return sql.toString();
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
        String format = "month".equals(grain) ? "%Y-%m" : "%Y-%m-%d";
        return "STRFTIME(" + TimeGrain.parse(grain).truncateSql(q(dim)) + ", '" + format + "')";
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
