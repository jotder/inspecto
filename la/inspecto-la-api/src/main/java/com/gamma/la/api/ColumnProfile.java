package com.gamma.la.api;

import com.gamma.analytics.ColumnProfiler;
import com.gamma.la.core.DatasetProvider;
import com.gamma.la.core.DatasetProviders;
import com.gamma.sql.SqlSandboxPolicy;
import com.gamma.util.SqlIdent;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The one column-profile helper (Entity profile plan §4.1): row count, non-NULL count and approximate distinct count of
 * several columns of ONE Dataset in ONE aggregate pass. Two callers: the overlap profile ({@code POST
 * /inv/schema/overlap-profile}, LA-15), which measures distinct over {@code CAST(col AS VARCHAR)}, and the Entity Type
 * profile, which measures it over the column's normalised Entity key (an empty key is not an Entity, so it is NULLed out).
 * The SQL is the Analytics Kernel's {@link ColumnProfiler} (ANALYTICS-CORE-1 M4: row per group, approximate distinct over
 * {@code distinctOf}, clamped to the non-NULL rows); {@code ColumnProfileGoldenTest} pins the figures.
 */
final class ColumnProfile {

    private ColumnProfile() {}

    /** One column's cardinality profile. {@code nulls} = {@code rows - nonNull}. */
    record Stats(String dataset, String column, String relationSql, long rows, long nonNull, long distinct) {
        long nulls() {
            return rows - nonNull;
        }
    }

    /**
     * The overlap profile's measure: distinct over the value as VARCHAR, so it is measured in the same domain as the pair's
     * union count - comparing a raw-typed cardinality against a VARCHAR union would make inclusion-exclusion meaningless
     * across mismatched column types.
     */
    static final UnaryOperator<String> AS_VARCHAR = c -> "CAST(" + c + " AS VARCHAR)";

    /** {@link #stats(String, String, List, UnaryOperator, SqlSandboxPolicy)} under the platform's default sandbox policy. */
    static List<Stats> stats(String dataset, String relationSql, List<String> cols, UnaryOperator<String> distinctOf)
            throws SQLException, IOException {
        return stats(dataset, relationSql, cols, distinctOf, null);
    }

    /**
     * One aggregate query over {@code dataset} covering every column in {@code cols}. {@code distinctOf} maps a quoted column
     * identifier to the SQL expression whose approximate distinct count is taken; every identifier is quoted here, so only
     * registry and validated names reach SQL. {@code policy} null = the default sandbox policy.
     */
    static List<Stats> stats(String dataset, String relationSql, List<String> cols, UnaryOperator<String> distinctOf,
                             SqlSandboxPolicy policy) throws SQLException, IOException {
        // The sketch can overshoot on small counts: the profiler clamps it to the non-NULL rows.
        String sql = ColumnProfiler.rowPerGroup(SqlIdent.q(dataset), ColumnProfiler.Spec.of(cols)
                .withStatistics(ColumnProfiler.Statistic.ROW_COUNT, ColumnProfiler.Statistic.NULL_COUNT, ColumnProfiler.Statistic.DISTINCT_COUNT)
                .withDistinct(ColumnProfiler.Distinct.APPROXIMATE, distinctOf));
        DatasetProvider.Request req = new DatasetProvider.Request(dataset, relationSql, sql, 1, 0, List.of(), List.of());
        DatasetProvider.Result r = policy == null ? DatasetProviders.require().run(req) : DatasetProviders.require().run(req, policy);
        Map<String, Object> row = r.rows().get(0);
        long rows = num(row.get("row_count"));
        List<Stats> out = new ArrayList<>(cols.size());
        for (int i = 0; i < cols.size(); i++)
            out.add(new Stats(dataset, cols.get(i), relationSql, rows, rows - num(row.get("null_count_" + i)),
                    num(row.get("distinct_count_" + i))));
        return out;
    }

    static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
