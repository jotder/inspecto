package com.gamma.expectation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.analytics.ColumnProfiler;
import com.gamma.analytics.MedianMode;
import com.gamma.analytics.Stats;
import com.gamma.sql.SqlSandbox;
import com.gamma.sql.SqlSandboxPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The {@code baseline} Expectation kind (DUCKLE-C8): <b>profile</b> the target's current input, then compare
 * each profiled cell against the <b>median</b> of the same cell over the last N <em>accepted</em> profiles
 * ({@link BaselineProfileStore}). A cell outside the allowed rise/fall is a violation; with
 * {@code requireExistingGroups}, a group the baseline has but the current input lacks is one too — the
 * missing partition that normal-looking totals hide.
 *
 * <p>A profile is one map per group ({@code "(all)"} without {@code groupBy}; otherwise the JSON array of the
 * group's values) of cell → value: {@code row_count}, and per profiled column
 * {@code <col>.null_count|null_rate|distinct_count|min|max|mean}. {@code min}/{@code max}/{@code mean} are
 * numeric ({@code TRY_CAST … AS DOUBLE}), so they are {@code null} — and never compared — on a text column.
 * The profile SQL is the Analytics Kernel's {@link ColumnProfiler} (row per group, numeric bounds, exact distinct) and
 * the median is {@link Stats#median} {@link MedianMode#STANDARD} (ANALYTICS-CORE-1 M4 / M2);
 * {@code BaselineEvaluatorGoldenTest} pins both.
 *
 * <p>No baseline yet (zero accepted profiles, or a cell no accepted profile has) ⇒ nothing to compare ⇒ that
 * cell passes. That is the cold start: the first successful run's profile becomes the baseline.
 *
 * <p>Like {@link ExpectationEvaluator} every statement is server-built from validated inputs — column names
 * pass {@link Expectation.Baseline}'s identifier rule and are quoted, the relation is
 * {@link ExpectationEvaluator#parquetGlob}'s path-jailed reader — so no {@code SqlGuard} pass is needed.
 */
public final class BaselineEvaluator {

    private BaselineEvaluator() {}

    /** A group count past this is refused rather than stored — a {@code groupBy} on an id column is a mistake. */
    public static final int MAX_GROUPS = 1_000;
    /** Findings kept on the result; {@code violations} is always the true total. */
    public static final int MAX_FINDINGS = 50;
    static final String ALL = "(all)";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One out-of-limit cell (or missing group). {@code column} is null for {@code row_count}. */
    public record Finding(String group, String measure, String column, double baseline, double current,
                          String direction) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("group", group);
            m.put("measure", measure);
            if (column != null) m.put("column", column);
            m.put("baseline", baseline);
            m.put("current", current);
            m.put("direction", direction);
            return m;
        }
    }

    /** The comparison: the true violation count, the first {@link #MAX_FINDINGS} findings, the window size. */
    public record Comparison(long violations, List<Finding> findings, int baselineSize) {}

    // ── profiling ───────────────────────────────────────────────────────────────

    /** Profile the target's at-rest data for {@code exp}'s baseline parameters. */
    public static Map<String, Map<String, Double>> profile(Expectation exp, Path dataRoot)
            throws SQLException, IOException {
        Expectation.Baseline b = exp.baseline();
        String sql = profileSql(b, ExpectationEvaluator.parquetGlob(dataRoot, exp.target()));
        Map<String, Map<String, Double>> groups = new TreeMap<>();
        try (SqlSandbox sandbox = SqlSandbox.open(SqlSandboxPolicy.defaultPolicy());
             Statement st = sandbox.statement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                if (groups.size() == MAX_GROUPS)
                    throw new IllegalArgumentException("baseline groupBy " + b.groupBy() + " yields more than "
                            + MAX_GROUPS + " groups — group by a lower-cardinality column");
                int i = 1;
                List<String> key = new ArrayList<>();
                for (int g = 0; g < b.groupBy().size(); g++) key.add(rs.getString(i++));
                Map<String, Double> cells = new TreeMap<>();
                double rows = rs.getLong(i++);
                cells.put("row_count", rows);
                for (String c : b.columns()) {
                    double nulls = rs.getLong(i++);
                    cells.put(c + ".null_count", nulls);
                    cells.put(c + ".null_rate", rows == 0 ? null : nulls / rows);
                    cells.put(c + ".distinct_count", (double) rs.getLong(i++));
                    cells.put(c + ".min", doubleOrNull(rs, i++));
                    cells.put(c + ".max", doubleOrNull(rs, i++));
                    cells.put(c + ".mean", doubleOrNull(rs, i++));
                }
                groups.put(groupKey(key, b), cells);
            }
        }
        return groups;
    }

    /** One row per group: the group values, the row count, then per column null count, distinct count, min, max, mean. */
    static String profileSql(Expectation.Baseline b, String relation) {
        ColumnProfiler.Spec spec = ColumnProfiler.Spec.of(b.columns()).withGroupBy(b.groupBy())
                .withStatistics(ColumnProfiler.Statistic.ROW_COUNT, ColumnProfiler.Statistic.NULL_COUNT,
                        ColumnProfiler.Statistic.DISTINCT_COUNT, ColumnProfiler.Statistic.MIN, ColumnProfiler.Statistic.MAX,
                        ColumnProfiler.Statistic.MEAN)
                .withBounds(ColumnProfiler.Bounds.NUMERIC);
        return ColumnProfiler.rowPerGroup(relation + " AS __t", spec) + " LIMIT " + (MAX_GROUPS + 1);
    }

    private static Double doubleOrNull(ResultSet rs, int i) throws SQLException {
        double v = rs.getDouble(i);
        return rs.wasNull() ? null : v;
    }

    private static String groupKey(List<String> values, Expectation.Baseline b) {
        if (b.groupBy().isEmpty()) return ALL;
        try {
            return JSON.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);   // a list of strings always serialises
        }
    }

    // ── comparison ──────────────────────────────────────────────────────────────

    /**
     * Compare {@code current} against the median of {@code window} (the accepted profiles, as stored —
     * each carries a {@code groups} map). Pure; the whole of the kind's decision logic.
     */
    @SuppressWarnings("unchecked")
    public static Comparison compare(Expectation.Baseline b, Map<String, Map<String, Double>> current,
                                     List<Map<String, Object>> window) {
        List<Map<String, Object>> history = window.stream()
                .map(p -> (Map<String, Object>) p.getOrDefault("groups", Map.of())).toList();
        List<Finding> findings = new ArrayList<>();
        long violations = 0;

        for (Map.Entry<String, Map<String, Double>> g : current.entrySet()) {
            for (String measure : b.measures()) {
                List<String> cols = "row_count".equals(measure) ? java.util.Collections.singletonList(null) : b.columns();
                for (String col : cols) {
                    String cell = col == null ? measure : col + "." + measure;
                    Double now = g.getValue().get(cell);
                    if (now == null) continue;
                    List<Double> past = new ArrayList<>();
                    for (Map<String, Object> h : history)
                        if (h.get(g.getKey()) instanceof Map<?, ?> cells && cells.get(cell) instanceof Number n)
                            past.add(n.doubleValue());
                    if (past.isEmpty()) continue;   // no baseline for this cell yet
                    double median = Stats.median(array(past), MedianMode.STANDARD);
                    String dir = breach(median, now, b.maxIncrease(), b.maxDecrease(), b.limitUnit());
                    if (dir == null) continue;
                    violations++;
                    if (findings.size() < MAX_FINDINGS)
                        findings.add(new Finding(g.getKey(), measure, col, median, now, dir));
                }
            }
        }

        if (b.requireExistingGroups()) {
            TreeSet<String> known = new TreeSet<>();
            history.forEach(h -> known.addAll(h.keySet()));
            for (String group : known) {
                if (current.containsKey(group)) continue;
                // An absent group counts as zero rows in that profile, so a group seen once in a long window
                // is not "existing" — its median row count is 0. Only a group the baseline typically has is.
                List<Double> rows = new ArrayList<>();
                for (Map<String, Object> h : history)
                    rows.add(h.get(group) instanceof Map<?, ?> cells && cells.get("row_count") instanceof Number n
                            ? n.doubleValue() : 0.0);
                double median = Stats.median(array(rows), MedianMode.STANDARD);
                if (median <= 0) continue;
                violations++;
                if (findings.size() < MAX_FINDINGS)
                    findings.add(new Finding(group, "row_count", null, median, 0, "missing_group"));
            }
        }
        return new Comparison(violations, List.copyOf(findings), window.size());
    }

    /**
     * Trailing-days mode (LA-DATA-TRUST-1 S7, {@code trailingDays > 0}): each group - one event day, in key order -
     * is judged against the median row count of the up-to-{@code trailingDays} groups BEFORE it in the SAME profile,
     * so a half-delivered day is caught without any stored history. A group with fewer than {@code minPriorDays}
     * prior groups is not judged. Prior groups are the days that exist, not calendar-adjacent days: a missing day
     * is the Coverage view's finding, not a zero in the median. Pure.
     */
    public static Comparison compareTrailing(Expectation.Baseline b, Map<String, Map<String, Double>> current) {
        List<Map.Entry<String, Map<String, Double>>> days = new ArrayList<>(current.entrySet());
        days.sort(Map.Entry.comparingByKey());
        List<Finding> findings = new ArrayList<>();
        long violations = 0;
        for (int i = 0; i < days.size(); i++) {
            List<Double> prior = new ArrayList<>();
            for (int j = Math.max(0, i - b.trailingDays()); j < i; j++) {
                Double rows = days.get(j).getValue().get("row_count");
                if (rows != null) prior.add(rows);
            }
            Double now = days.get(i).getValue().get("row_count");
            if (now == null || prior.size() < b.minPriorDays()) continue;
            double median = Stats.median(array(prior), MedianMode.STANDARD);
            String dir = breach(median, now, b.maxIncrease(), b.maxDecrease(), b.limitUnit());
            if (dir == null) continue;
            violations++;
            if (findings.size() < MAX_FINDINGS)
                findings.add(new Finding(days.get(i).getKey(), "row_count", null, median, now, dir));
        }
        return new Comparison(violations, List.copyOf(findings), days.size());
    }

    private static double[] array(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * {@code "above"}/{@code "below"} when {@code current} leaves the allowed band around {@code baseline},
     * else {@code null}. A limit is inclusive — exactly {@code maxIncrease} above is still in band. In
     * {@code percent} the change is taken against {@code |baseline|}; from a zero baseline any move in a
     * limited direction is an infinite percentage and therefore a breach.
     */
    static String breach(double baseline, double current, Double maxIncrease, Double maxDecrease, String unit) {
        double delta = current - baseline;
        double change;
        if ("absolute".equals(unit)) {
            change = delta;
        } else if (baseline == 0) {
            change = delta == 0 ? 0 : Math.copySign(Double.POSITIVE_INFINITY, delta);
        } else {
            change = delta * 100.0 / Math.abs(baseline);
        }
        if (maxIncrease != null && change > maxIncrease) return "above";
        if (maxDecrease != null && -change > maxDecrease) return "below";
        return null;
    }
}
