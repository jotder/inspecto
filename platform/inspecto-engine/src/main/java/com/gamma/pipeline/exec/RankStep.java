package com.gamma.pipeline.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.gamma.util.SqlIdent.q;

/**
 * The config grammar of {@code transform.analytics.rank} ({@code steps:} kind {@code rank}, ANALYTICS-CORE-1 S4): a rank
 * per group by a stated order, then the top N kept or the rank added as a column. Parsed once, so the run
 * ({@link RowShaper}), the recipe compiler and the save gate refuse the same configs with the same words.
 *
 * <p><b>The tie-break is mandatory and deterministic.</b> The window orders by {@code order_by}, then {@code tie_break},
 * each with its stated direction and NULLs last in both directions. For {@code row_number} it then orders by
 * <b>every remaining inbound column</b>, ascending, NULLs last — so two rows still tied are equal in every column and
 * either one gives the same output. For {@code rank} and {@code dense_rank} rows equal in {@code order_by} and
 * {@code tie_break} share a rank (that is the method), so {@code top: N} can keep more than N rows of a group.
 *
 * <p>🔴 No authored text reaches the SQL as text: column names are validated and quoted, the method and the directions
 * are fixed words, {@code top} is a parsed number.
 */
public final class RankStep {

    private RankStep() {}

    static final String KIND = "transform.analytics.rank";

    /** The largest {@code top}. */
    public static final int MAX_TOP = 1_000_000;
    /** The default rank column. */
    public static final String DEFAULT_RANK_COLUMN = "rank";
    /** The scratch name of the rank when {@code keep_rank: false}; never left in the output. */
    static final String SCRATCH_RANK = "__rank";

    private static final Set<String> KEYS = Set.of("group_by", "order_by", "tie_break", "method", "top", "rank_column",
            "keep_rank");

    /** The ranking function. */
    public enum Method {
        ROW_NUMBER, RANK, DENSE_RANK;

        public String configName() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Method parse(String s) {
            for (Method m : values()) if (m.configName().equalsIgnoreCase(s.trim())) return m;
            return null;
        }
    }

    /** One ordering term: a column and its direction; NULLs always last. */
    public record Order(String column, boolean descending) {
        String sql() {
            return q(column) + (descending ? " DESC" : " ASC") + " NULLS LAST";
        }
    }

    /** A parsed config; {@code top} is {@code null} when every row is kept. */
    public record Spec(List<String> groupBy, List<Order> orderBy, List<Order> tieBreak, Method method, Integer top,
                       String rankColumn, boolean keepRank) {

        /** The columns the inbound relation must carry. */
        public List<String> inputColumns() {
            List<String> out = new ArrayList<>(groupBy);
            for (Order o : orderBy) out.add(o.column());
            for (Order o : tieBreak) out.add(o.column());
            return out;
        }

        /** The columns the Step adds. */
        public List<String> addedColumns() {
            return keepRank ? List.of(rankColumn) : List.of();
        }

        /** The window's ORDER BY over the inbound columns: the stated terms, then (row_number) every other column. */
        String windowOrder(List<String> inbound) {
            List<String> terms = new ArrayList<>();
            Set<String> named = new HashSet<>();
            for (Order o : orderBy) { terms.add(o.sql()); named.add(o.column().toLowerCase(Locale.ROOT)); }
            for (Order o : tieBreak) { terms.add(o.sql()); named.add(o.column().toLowerCase(Locale.ROOT)); }
            if (method == Method.ROW_NUMBER)
                for (String c : inbound)
                    if (named.add(c.toLowerCase(Locale.ROOT))) terms.add(new Order(c, false).sql());
            return String.join(", ", terms);
        }

        /** Every inbound row with its rank (or only the top N of each group). */
        public String select(String input, List<String> inbound) {
            String r = keepRank ? rankColumn : SCRATCH_RANK;
            String partition = groupBy.isEmpty() ? ""
                    : "PARTITION BY " + String.join(", ", groupBy.stream().map(c -> q(c)).toList()) + " ";
            String inner = "SELECT *, " + method.configName() + "() OVER (" + partition + "ORDER BY "
                    + windowOrder(inbound) + ") AS " + q(r) + " FROM " + q(input);
            if (top == null) return inner;
            return "SELECT *" + (keepRank ? "" : " EXCLUDE (" + q(r) + ")") + " FROM (" + inner + ") WHERE " + q(r)
                    + " <= " + top;
        }
    }

    /** Parse and validate a config; {@link IllegalArgumentException} names the first problem. */
    public static Spec parse(Map<?, ?> cfg) {
        StepKeys.known(cfg, KIND, KEYS);
        List<String> groupBy = StepKeys.columns(cfg, "group_by", KIND);
        List<Order> orderBy = orders(cfg, "order_by");
        if (orderBy.isEmpty())
            throw StepKeys.bad(KIND, "needs a non-empty 'order_by' list - the columns that decide the rank, e.g. AMOUNT desc");
        List<Order> tieBreak = orders(cfg, "tie_break");
        Set<String> seen = new HashSet<>();
        for (Order o : orderBy) if (!seen.add(o.column().toLowerCase(Locale.ROOT)))
            throw StepKeys.bad(KIND, "orders by '" + o.column() + "' twice");
        for (Order o : tieBreak) if (!seen.add(o.column().toLowerCase(Locale.ROOT)))
            throw StepKeys.bad(KIND, "'tie_break' repeats '" + o.column() + "', already ordered by");
        Method method = Method.ROW_NUMBER;
        Object m = cfg.get("method");
        if (!StepKeys.blank(m)) {
            method = Method.parse(m.toString());
            if (method == null) throw StepKeys.bad(KIND, "'method' '" + m + "' is not one of " + methods());
        }
        Long top = StepKeys.wholeNumber(cfg, "top", KIND, 1, MAX_TOP);
        boolean keepRank = StepKeys.bool(cfg, "keep_rank", KIND, true);
        if (!keepRank && top == null)
            throw StepKeys.bad(KIND, "keep_rank: false needs 'top' - otherwise the Step would change nothing");
        String rankColumn = StepKeys.column(cfg, "rank_column", KIND, DEFAULT_RANK_COLUMN, null);
        return new Spec(List.copyOf(groupBy), List.copyOf(orderBy), List.copyOf(tieBreak), method,
                top == null ? null : top.intValue(), rankColumn, keepRank);
    }

    /** The first refusal against the inbound columns, or {@code null}. */
    public static String columnRefusal(Spec spec, List<String> inbound) {
        List<String> added = new ArrayList<>(spec.addedColumns());
        if (!spec.keepRank()) added.add(SCRATCH_RANK);
        return StepKeys.columnRefusal(KIND, spec.inputColumns(), added, inbound, "set 'rank_column' to another name");
    }

    /** The config spellings of the methods. */
    public static List<String> methods() {
        return Arrays.stream(Method.values()).map(Method::configName).toList();
    }

    /** {@code COL}, {@code COL asc} or {@code COL desc} per entry (a single string is a list of one). */
    private static List<Order> orders(Map<?, ?> cfg, String key) {
        Object v = cfg.get(key);
        List<?> raw = v instanceof List<?> l ? l : StepKeys.blank(v) ? List.of() : List.of(v);
        List<Order> out = new ArrayList<>();
        for (Object o : raw) {
            if (StepKeys.blank(o)) continue;
            String[] parts = o.toString().trim().split("\\s+");
            boolean desc = false;
            if (parts.length == 2 && parts[1].equalsIgnoreCase("desc")) desc = true;
            else if (parts.length > 2 || (parts.length == 2 && !parts[1].equalsIgnoreCase("asc")))
                throw StepKeys.bad(KIND, "'" + key + "' entry '" + o + "' must be a column, optionally followed by asc or desc");
            out.add(new Order(StepKeys.columnName(parts[0], key, KIND), desc));
        }
        return out;
    }
}
