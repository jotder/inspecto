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

    /** The compiled SELECT for one window — package-visible so a test can read what runs. */
    static String sql(KpiDefinition kpi, KpiDefinition.Window w) {
        return MeasureCompiler.compile(new MeasureCompiler.Spec(kpi.dataset(), List.of(kpi.measure()), List.of(),
                Map.of(), List.of(new MeasureCompiler.Filter(kpi.timeField(), ">=", w.from().toString()),
                        new MeasureCompiler.Filter(kpi.timeField(), "<", w.to().toString())), List.of(), 1));
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

    private static Double run(KpiDefinition kpi, String relationSql, KpiDefinition.Window w) throws SQLException, IOException {
        String sql = sql(kpi, w);
        List<Finding> findings = SqlGuard.check(sql);
        if (!findings.isEmpty())
            throw new IllegalArgumentException("compiled KPI query failed the SQL safety check: " + findings.get(0).message());
        QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request(kpi.dataset(), relationSql, sql, 1, 0,
                List.of(), List.of()));
        if (r.rows().isEmpty()) return null;
        return r.rows().get(0).get(kpi.measure().id()) instanceof Number n ? n.doubleValue() : null;
    }
}
