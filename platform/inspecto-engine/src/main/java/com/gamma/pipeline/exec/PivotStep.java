package com.gamma.pipeline.exec;

import com.gamma.etl.Identifiers;
import com.gamma.query.MeasureCompiler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.gamma.util.SqlIdent.q;
import static com.gamma.util.SqlIdent.sqlStr;

/**
 * The config grammar of {@code transform.matrix.pivot} ({@code steps:} kind {@code pivot}, ANALYTICS-CORE-1 S4): rows to
 * columns — one output row per {@code group_by} key, one column per <b>declared</b> pivot value, each holding one
 * aggregate of the Measure grammar ({@link MeasureCompiler#aggregateSql}) over that key's rows with that value.
 *
 * <p>⛔ <b>No dynamic pivot.</b> The values are listed in the config ({@code values}, at most {@link #MAX_VALUES}); a
 * pivot over whatever values the data holds would make the output's columns depend on the data, which no schema, save
 * check or downstream Step can judge. A row whose pivot value is not listed (or is NULL) adds to no column; its key still
 * gets an output row.
 *
 * <p>Values are matched as text: {@code CAST(pivot_column AS VARCHAR) = 'value'}. The output column is
 * {@code prefix + value} and must be a plain column name. No authored text reaches the SQL as text: names are validated
 * and quoted, values are SQL string literals, the aggregate is the grammar's.
 */
public final class PivotStep {

    private PivotStep() {}

    static final String KIND = "transform.matrix.pivot";

    /** The most pivot values (output columns besides the keys). */
    public static final int MAX_VALUES = 200;

    private static final Set<String> KEYS = Set.of("group_by", "pivot_column", "values", "measure", "prefix");

    /** A parsed config. */
    public record Spec(List<String> groupBy, String pivotColumn, List<String> values, MeasureCompiler.Measure measure,
                       String prefix) {

        /** The columns the inbound relation must carry. */
        public List<String> inputColumns() {
            List<String> out = new ArrayList<>(groupBy);
            out.add(pivotColumn);
            if (measure.field() != null) out.add(measure.field());
            return out;
        }

        /** The pivoted columns, one per value, in the declared order. */
        public List<String> pivotedColumns() {
            return values.stream().map(v -> prefix + v).toList();
        }

        /** The output columns: the keys, then the pivoted columns. */
        public List<String> outputColumns() {
            List<String> out = new ArrayList<>(groupBy);
            out.addAll(pivotedColumns());
            return out;
        }

        /** One row per key, one filtered aggregate per value. */
        public String select(String input) {
            String agg = MeasureCompiler.aggregateSql(measure);
            List<String> cols = new ArrayList<>();
            for (String g : groupBy) cols.add(q(g));
            for (String v : values)
                cols.add(agg + " FILTER (WHERE CAST(" + q(pivotColumn) + " AS VARCHAR) = " + sqlStr(v) + ") AS "
                        + q(prefix + v));
            String keys = String.join(", ", groupBy.stream().map(c -> q(c)).toList());
            return "SELECT " + String.join(", ", cols) + " FROM " + q(input) + (groupBy.isEmpty() ? "" : " GROUP BY " + keys);
        }
    }

    /** Parse and validate a config; {@link IllegalArgumentException} names the first problem. */
    public static Spec parse(Map<?, ?> cfg) {
        StepKeys.known(cfg, KIND, KEYS);
        List<String> groupBy = StepKeys.columns(cfg, "group_by", KIND);
        String pivot = StepKeys.column(cfg, "pivot_column", KIND, null, "the column whose values become columns");
        if (groupBy.stream().anyMatch(g -> g.equalsIgnoreCase(pivot)))
            throw StepKeys.bad(KIND, "'pivot_column' '" + pivot + "' is also a group_by column");
        if (!(cfg.get("values") instanceof List<?> raw) || raw.isEmpty())
            throw StepKeys.bad(KIND, "needs an explicit 'values' list - the pivot values that become columns; a pivot "
                    + "over whatever values the data holds is refused (its columns would be unbounded)");
        if (raw.size() > MAX_VALUES)
            throw StepKeys.bad(KIND, "takes at most " + MAX_VALUES + " 'values', got " + raw.size());
        Object m = cfg.get("measure");
        if (StepKeys.blank(m)) throw StepKeys.bad(KIND, "needs a 'measure' - count or agg(field), e.g. sum(AMOUNT)");
        MeasureCompiler.Measure measure;
        try {
            Map<String, Object> one = MeasureCompiler.splitShorthand(List.of(m.toString()), null).get(0);
            measure = new MeasureCompiler.Measure(String.valueOf(one.get("agg")),
                    one.get("field") == null ? null : String.valueOf(one.get("field")).trim());
            MeasureCompiler.aggregateSql(measure);
        } catch (IllegalArgumentException e) {
            throw StepKeys.bad(KIND, "measure '" + m + "': " + e.getMessage());
        }
        Object p = cfg.get("prefix");
        String prefix = StepKeys.blank(p) ? "" : p.toString().trim();
        List<String> values = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String g : groupBy) seen.add(g.toLowerCase(Locale.ROOT));
        for (Object o : raw) {
            if (o == null) throw StepKeys.bad(KIND, "'values' has an empty entry - a NULL pivot value adds to no column");
            String v = o.toString();
            String name = prefix + v;
            try {
                Identifiers.validate(name, KIND + " values");
            } catch (IllegalArgumentException e) {
                throw StepKeys.bad(KIND, "value '" + v + "' gives column name '" + name + "', not a plain column name "
                        + "([A-Za-z_][A-Za-z0-9_]*) - set a 'prefix' such as v_");
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT)))
                throw StepKeys.bad(KIND, "output column '" + name + "' appears twice - the values and the group_by "
                        + "columns must give distinct names");
            values.add(v);
        }
        return new Spec(List.copyOf(groupBy), pivot, List.copyOf(values), measure, prefix);
    }

    /** The first refusal against the inbound columns, or {@code null}. */
    public static String columnRefusal(Spec spec, List<String> inbound) {
        return StepKeys.columnRefusal(KIND, spec.inputColumns(), List.of(), inbound, "");
    }

    /**
     * {@code null} when the measure's field has a usable type, else the refusal: {@code sum}, {@code avg} and the
     * statistics need a number ({@link MeasureCompiler#NUMERIC_AGGS}).
     */
    public static String typeRefusal(Spec spec, String fieldType) {
        if (spec.measure().field() == null || !MeasureCompiler.NUMERIC_AGGS.contains(spec.measure().agg())) return null;
        String t = fieldType == null ? "" : fieldType.toUpperCase(Locale.ROOT).trim();
        return t.startsWith("DECIMAL") || TimeSeriesStep.NUMERIC.contains(t) ? null
                : KIND + ": measure " + spec.measure().agg() + "(" + spec.measure().field() + ") needs a number, not "
                        + fieldType;
    }
}
