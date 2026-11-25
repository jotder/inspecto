package com.gamma.query;

import com.gamma.config.spec.Finding;
import com.gamma.sql.SqlGuard;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Evaluates a {@link KpiDefinition} as of a date (`ASSURE-KPI-DEFINITIONS-1`): the Measure over the current window
 * and over the comparison window, the delta, and the RAG band. Each window is ONE {@link MeasureCompiler} spec — the
 * Measure plus two typed filters on the {@code timeField} ({@code >= from}, {@code < to}, ISO date literals) — so
 * no author text reaches the statement unquoted, and the compiled text is still {@link SqlGuard}-checked before it
 * runs in the {@link QueryExecutor} sandbox. The relation is the caller's: the route resolves the Dataset (and
 * decides whether the caller may read it) before anything here runs.
 */
public final class KpiEvaluator {

    private KpiEvaluator() {}

    /** One evaluation. {@code value}/{@code comparisonValue} are {@code null} when the window holds no value. */
    public record Result(KpiDefinition.Window window, Double value, KpiDefinition.Window comparisonWindow,
                         Double comparisonValue, Double delta, Double deltaPct, KpiDefinition.Rag rag, String tone) {}

    /** The CTE the window's rows are cut into, named so it cannot collide with a Dataset id. */
    private static final String WINDOW = "__kpi_window";
    private static final java.time.format.DateTimeFormatter TSTZ =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssxxx");

    /**
     * The compiled statement for one window — package-visible so a test can read what runs. The window is cut in a
     * CTE by two typed {@code TIMESTAMPTZ} literals (local midnight in the KPI's zone, with its offset), and the
     * Measure is {@link MeasureCompiler}'s own SELECT over that CTE. With the session TimeZone set to the same zone
     * ({@link #run}), a DATE, TIMESTAMP or TIMESTAMPTZ column is compared on the same instants wherever it runs.
     */
    static MeasureCompiler.Compiled sql(KpiDefinition kpi, KpiDefinition.Window w) {
        String col = com.gamma.util.SqlIdent.q(kpi.timeField());
        MeasureCompiler.Compiled measure = MeasureCompiler.compile(new MeasureCompiler.Spec(WINDOW, List.of(kpi.measure()), List.of(),
                Map.of(), List.of(), List.of(), 1));
        // The window CTE carries no placeholders, so the Measure's parameters are the statement's, in order.
        return new MeasureCompiler.Compiled("WITH " + com.gamma.util.SqlIdent.q(WINDOW) + " AS (SELECT * FROM " + com.gamma.util.SqlIdent.q(kpi.dataset())
                + " WHERE " + col + " >= TIMESTAMPTZ '" + instant(kpi, w.from()) + "' AND " + col + " < TIMESTAMPTZ '"
                + instant(kpi, w.to()) + "') " + measure.sql(), measure.params());
    }

    private static String instant(KpiDefinition kpi, LocalDate d) {
        return d.atStartOfDay(kpi.zone()).format(TSTZ);
    }

    /**
     * @param relationSql the Dataset's trusted relation ({@link DatasetRelation#relationSql}), registered as a view
     *                    named after {@link KpiDefinition#dataset()}
     * @throws IllegalArgumentException when the compiled SQL fails the guard (never expected — defence in depth)
     * @throws SQLException             when DuckDB refuses the query (e.g. a column the relation lacks)
     */
    public static Result evaluate(KpiDefinition kpi, String relationSql, LocalDate asOf) throws SQLException, IOException {
        KpiDefinition.Window cur = kpi.current(asOf);
        Double value = run(kpi, relationSql, cur);
        KpiDefinition.Window cmpWindow = kpi.comparison(asOf);
        Double cmp = cmpWindow == null ? null : run(kpi, relationSql, cmpWindow);
        Double delta = value == null || cmp == null ? null : value - cmp;
        KpiDefinition.Rag rag = kpi.rag(value);
        return new Result(cur, value, cmpWindow, cmp, delta, KpiDefinition.deltaPct(cmp, value), rag,
                KpiDefinition.tone(rag));
    }

    /**
     * The same KPI over an explicit range of days ({@code from} and {@code to}, both inclusive) instead of the grain's
     * period to date: the Measure over the range, against the comparison the KPI declares applied to that range
     * ({@link KpiDefinition#comparisonOf}). A Dashboard's date picker reads a KPI tile through this.
     */
    public static Result evaluateRange(KpiDefinition kpi, String relationSql, LocalDate from, LocalDate to) throws SQLException, IOException {
        if (to.isBefore(from)) throw new IllegalArgumentException("range to " + to + " is before from " + from);
        KpiDefinition.Window cur = new KpiDefinition.Window(from, to.plusDays(1));
        Double value = run(kpi, relationSql, cur);
        KpiDefinition.Window cmpWindow = kpi.comparisonOf(cur);
        Double cmp = cmpWindow == null ? null : run(kpi, relationSql, cmpWindow);
        Double delta = value == null || cmp == null ? null : value - cmp;
        KpiDefinition.Rag rag = kpi.rag(value);
        return new Result(cur, value, cmpWindow, cmp, delta, KpiDefinition.deltaPct(cmp, value), rag, KpiDefinition.tone(rag));
    }

    /**
     * The newest day present in the KPI's {@code timeField}, in its zone (for {@code asOf: latest}); {@code null} when
     * the Dataset holds no dated row. The caller caps it at today.
     */
    public static LocalDate latestDay(KpiDefinition kpi, String relationSql) throws SQLException, IOException {
        return edgeDay(kpi, relationSql, "max");
    }

    /**
     * The oldest day present in the KPI's {@code timeField}, in its zone; {@code null} when the Dataset holds no dated
     * row. KPI History starts here: a period before the data began has no value, not a {@code count} of 0.
     */
    public static LocalDate earliestDay(KpiDefinition kpi, String relationSql) throws SQLException, IOException {
        return edgeDay(kpi, relationSql, "min");
    }

    /** The Measure over one window, without a comparison: {@code null} when the window holds no value. */
    public static Double valueOf(KpiDefinition kpi, String relationSql, KpiDefinition.Window w) throws SQLException, IOException {
        return run(kpi, relationSql, w);
    }

    // ── I-12: target-attainment projection ──────────────────────────────────────────────────────────────────────

    /** The aggregates whose daily parts add up to the period — the only ones a period can be projected from. */
    public static final java.util.Set<String> ADDITIVE = java.util.Set.of("count", "sum");
    /** Days of history the projection reads before the period's last day to date (two years). */
    static final int HISTORY_DAYS = 730;

    /**
     * Where the period holding {@code asOf} lands (`BI-INSIGHTS-1` I-12): its value to date plus a forecast of the days
     * left, from the Analytics Kernel's {@link com.gamma.analytics.forecast.PeriodProjection}. The history is the KPI's
     * own Measure per day over the LIVE Dataset (at most {@value #HISTORY_DAYS} days back, from the first day with data),
     * read in one bucketed statement — never KPI History, whose rows are whole periods and cannot say what the rest of
     * a period holds. A day with no row is 0 (a count or sum of nothing). Computed at query time (D-BI7).
     *
     * @return the projection, or one with {@code projected = false} and the reason it cannot be made
     */
    public static Projection project(KpiDefinition kpi, String relationSql, LocalDate asOf) throws SQLException, IOException {
        KpiDefinition.Window cur = kpi.current(asOf);
        Double toDateValue = run(kpi, relationSql, cur);
        double toDate = toDateValue == null ? 0 : toDateValue;
        if (kpi.grain() == KpiDefinition.Grain.DAY)
            return Projection.none(kpi, "a day-grain KPI's period has no smaller bucket to forecast", toDateValue);
        if (!ADDITIVE.contains(kpi.measure().agg()))
            return Projection.none(kpi, "'" + kpi.measure().agg() + "' does not add up day by day, so the period cannot be "
                    + "projected from its days (count and sum can)", toDateValue);
        LocalDate periodEnd = kpi.grain().plus(cur.from(), 1);
        int horizon = (int) java.time.temporal.ChronoUnit.DAYS.between(cur.to(), periodEnd);
        LocalDate from = cur.to().minusDays(HISTORY_DAYS);
        Map<LocalDate, Double> byDay = daily(kpi, relationSql, new KpiDefinition.Window(from, cur.to()));
        LocalDate first = byDay.keySet().stream().min(LocalDate::compareTo).orElse(null);
        if (first == null) return Projection.none(kpi, "the Dataset holds no row in the last " + HISTORY_DAYS + " days", toDateValue);
        int n = (int) java.time.temporal.ChronoUnit.DAYS.between(first, cur.to());
        double[] series = new double[n];
        for (int i = 0; i < n; i++) series[i] = byDay.getOrDefault(first.plusDays(i), 0.0);
        var r = com.gamma.analytics.forecast.PeriodProjection.project(series, toDate, horizon,
                com.gamma.analytics.time.SeasonalityDetector.candidatesFor(com.gamma.analytics.time.TimeGrain.DAY), 7);
        return new Projection(r, toDateValue, kpi);
    }

    /**
     * A projection with the KPI's reading of it: the RAG of the landing ({@link KpiDefinition#rag}) and P(target met),
     * the goal being the target (direction up: at least; down: at most), else the green band's threshold, else the
     * green interval for direction band; no target and no bands ⇒ no probability.
     */
    public record Projection(com.gamma.analytics.forecast.PeriodProjection.Result result, Double toDate, KpiDefinition kpi) {
        static Projection none(KpiDefinition kpi, String reason, Double toDate) {
            return new Projection(new com.gamma.analytics.forecast.PeriodProjection.Result(false, reason,
                    toDate == null ? 0 : toDate, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, 0, 0, false, 0, null),
                    toDate, kpi);
        }

        public boolean projected() { return result.projected(); }

        public KpiDefinition.Rag rag() { return projected() ? kpi.rag(result.value()) : null; }

        /** P(target met), or {@code null} when there is no projection or nothing to meet. */
        public Double probability() {
            if (!projected()) return null;
            KpiDefinition.Bands b = kpi.bands();
            java.util.OptionalDouble p;
            if (kpi.direction() == KpiDefinition.Direction.BAND) {
                if (b == null) return null;
                p = result.probability(com.gamma.analytics.forecast.PeriodProjection.Goal.BETWEEN, b.greenLo(), b.greenHi());
            } else {
                Double t = kpi.target() != null ? kpi.target()
                        : b == null ? null : kpi.direction() == KpiDefinition.Direction.UP ? b.greenLo() : b.greenHi();
                if (t == null) return null;
                p = result.probability(kpi.direction() == KpiDefinition.Direction.UP
                        ? com.gamma.analytics.forecast.PeriodProjection.Goal.AT_LEAST
                        : com.gamma.analytics.forecast.PeriodProjection.Goal.AT_MOST, t, t);
            }
            return p.isPresent() ? p.getAsDouble() : null;
        }
    }

    /** The KPI's Measure per day of {@code w}, in its zone; a day with no row is absent. One bucketed statement. */
    static Map<LocalDate, Double> daily(KpiDefinition kpi, String relationSql, KpiDefinition.Window w) throws SQLException, IOException {
        MeasureCompiler.Compiled sql = dailySql(kpi, w);
        List<Finding> findings = SqlGuard.check(sql.sql());
        if (!findings.isEmpty())
            throw new IllegalArgumentException("compiled KPI query failed the SQL safety check: " + findings.get(0).message());
        int maxRows = (int) java.time.temporal.ChronoUnit.DAYS.between(w.from(), w.to()) + 1;
        QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request(kpi.dataset(), relationSql, sql.sql(), maxRows, 0,
                List.of(), List.of(), sql.params()), com.gamma.sql.SqlSandboxPolicy.defaultPolicy(), kpi.zone());
        Map<LocalDate, Double> out = new java.util.HashMap<>();
        for (Map<String, Object> row : r.rows()) {
            Object day = row.get(kpi.timeField());
            if (day == null) continue;
            out.put(LocalDate.parse(String.valueOf(day)), row.get(kpi.measure().id()) instanceof Number v ? v.doubleValue() : 0.0);
        }
        return out;
    }

    /** {@link #sql}'s window CTE with the Measure grouped by the day of {@code timeField} — package-visible for a test. */
    static MeasureCompiler.Compiled dailySql(KpiDefinition kpi, KpiDefinition.Window w) {
        String col = com.gamma.util.SqlIdent.q(kpi.timeField());
        int days = (int) java.time.temporal.ChronoUnit.DAYS.between(w.from(), w.to()) + 1;
        MeasureCompiler.Compiled measure = MeasureCompiler.compile(new MeasureCompiler.Spec(WINDOW, List.of(kpi.measure()),
                List.of(kpi.timeField()), Map.of(kpi.timeField(), "day"), List.of(), List.of(), days));
        return new MeasureCompiler.Compiled("WITH " + com.gamma.util.SqlIdent.q(WINDOW) + " AS (SELECT * FROM " + com.gamma.util.SqlIdent.q(kpi.dataset())
                + " WHERE " + col + " >= TIMESTAMPTZ '" + instant(kpi, w.from()) + "' AND " + col + " < TIMESTAMPTZ '"
                + instant(kpi, w.to()) + "') " + measure.sql(), measure.params());
    }

    private static LocalDate edgeDay(KpiDefinition kpi, String relationSql, String agg) throws SQLException, IOException {
        String sql = "SELECT CAST(CAST(CAST(" + agg + "(" + com.gamma.util.SqlIdent.q(kpi.timeField()) + ") AS TIMESTAMPTZ) AS DATE) AS VARCHAR) AS d FROM "
                + com.gamma.util.SqlIdent.q(kpi.dataset());
        List<Finding> findings = SqlGuard.check(sql);
        if (!findings.isEmpty())
            throw new IllegalArgumentException("compiled KPI query failed the SQL safety check: " + findings.get(0).message());
        QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request(kpi.dataset(), relationSql, sql, 1, 0,
                List.of(), List.of(), List.of()), com.gamma.sql.SqlSandboxPolicy.defaultPolicy(), kpi.zone());
        if (r.rows().isEmpty() || r.rows().get(0).get("d") == null) return null;
        return LocalDate.parse(String.valueOf(r.rows().get(0).get("d")));
    }

    private static Double run(KpiDefinition kpi, String relationSql, KpiDefinition.Window w) throws SQLException, IOException {
        MeasureCompiler.Compiled sql = sql(kpi, w);
        List<Finding> findings = SqlGuard.check(sql.sql());
        if (!findings.isEmpty())
            throw new IllegalArgumentException("compiled KPI query failed the SQL safety check: " + findings.get(0).message());
        QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request(kpi.dataset(), relationSql, sql.sql(), 1, 0,
                List.of(), List.of(), sql.params()), com.gamma.sql.SqlSandboxPolicy.defaultPolicy(), kpi.zone());
        if (r.rows().isEmpty()) return null;
        return r.rows().get(0).get(kpi.measure().id()) instanceof Number n ? n.doubleValue() : null;
    }
}
