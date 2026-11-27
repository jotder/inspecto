package com.gamma.alert;

import com.gamma.query.ConditionSql;
import com.gamma.query.MeasureCompiler;
import com.gamma.util.SqlIdent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The SQL compiler of a windowed Alert Rule (PA-9b, D-PA40/D-PA42): one rule, the window buffer and the rows the
 * committed Consignment just added become one DuckDB statement that answers "which (key, window) breach now".
 *
 * <p>The statement reads two tables with the same columns ({@link #columns}): {@code fresh}, the rows of the
 * Consignment being evaluated, and {@code buffer}, every row whose windows may still be open (fresh included). It
 * evaluates only the <b>touched</b> (key, window) pairs — the keys and windows that a {@code fresh} row matching the
 * rule's {@code when} falls in — so a commit costs what it brought, not what the buffer holds. A touched window
 * counts only if it was still open before this commit: {@code start + size + lateness > watermark}, the
 * event-time Watermark the buffer had reached. Window starts are aligned to the epoch, in microseconds.
 *
 * <p>Nothing an author wrote reaches the statement text unquoted: columns are {@link SqlIdent#q quoted}, the
 * {@code when} tree is {@link ConditionSql#predicateBound bound}, the Measure comes from
 * {@link MeasureCompiler#aggregateSql}, the watermark and threshold are parameters, and the window sizes are
 * whole numbers derived from validated durations.
 */
final class WindowSql {

    private WindowSql() {}

    /** Result columns; prefixed so none can collide with a Dataset column. */
    static final String WINDOW_START = "__inspecto_ws";
    static final String VALUE = "__inspecto_value";
    static final String FIRST = "__inspecto_first";
    static final String LAST = "__inspecto_last";

    /** The result column carrying the {@code i}-th {@code by} key. */
    static String keyColumn(int i) {
        return "__inspecto_k" + i;
    }

    /** One statement and its positional parameters. */
    record Compiled(String sql, List<Object> params) {}

    /**
     * The columns the window buffer must hold for {@code rules}: the event time, the {@code by} keys, the
     * Measure's field, every field the {@code when} tree reads, and the {@code distinctOn} identity — in that order,
     * each once.
     */
    static List<String> columns(List<AlertRule> rules) {
        Set<String> out = new LinkedHashSet<>();
        for (AlertRule r : rules) {
            out.add(r.eventWindow().time());
            out.addAll(r.by());
            MeasureCompiler.Measure m = measure(r);
            if (m.field() != null) out.add(m.field());
            whenFields(r.when(), out);
            if (r.eventWindow().isPattern()) for (Object step : r.eventWindow().pattern().steps()) whenFields(step, out);
            out.addAll(r.eventWindow().distinctOn());
        }
        return List.copyOf(out);
    }

    /**
     * The breaching (key, window) pairs among those this commit touched and that were still open before it, ordered
     * by window start then key. Columns: {@link #keyColumn}{@code (i)} per {@code by} key, {@link #WINDOW_START}
     * (epoch µs), {@link #VALUE}, {@link #FIRST} and {@link #LAST} (the event times, epoch µs, of the window's first
     * and last counted row).
     *
     * @param watermarkUs the event-time Watermark (epoch µs) the buffer had reached before this commit;
     *                    {@link Long#MIN_VALUE} when it held nothing
     */
    static Compiled breaches(AlertRule rule, String buffer, String fresh, long watermarkUs) {
        AlertWindow w = rule.eventWindow();
        long size = micros(w.size()), slide = micros(w.slide()), late = micros(w.lateness());
        String ts = SqlIdent.q(w.time());
        ConditionSql.Bound when = ConditionSql.predicateBound(rule.when());
        String agg = MeasureCompiler.aggregateSql(measure(rule));

        List<String> keySelect = new ArrayList<>(), keyOut = new ArrayList<>(), keyJoin = new ArrayList<>();
        for (int i = 0; i < rule.by().size(); i++) {
            String c = SqlIdent.q(rule.by().get(i)), k = SqlIdent.q(keyColumn(i));
            keySelect.add("f." + c + " AS " + k);
            keyOut.add("o." + k);
            keyJoin.add("b." + c + " IS NOT DISTINCT FROM o." + k);
        }
        String ws = "o." + SqlIdent.q(WINDOW_START);
        List<String> groupBy = new ArrayList<>(keyOut);
        groupBy.add(ws);
        List<String> orderBy = new ArrayList<>();
        orderBy.add(ws);
        orderBy.addAll(keyOut);
        keyJoin.add("epoch_us(b." + ts + ") >= " + ws);
        keyJoin.add("epoch_us(b." + ts + ") < " + ws + " + " + size);

        String sql = "WITH " + SqlIdent.q("__inspecto_fw") + " AS (SELECT * FROM " + SqlIdent.q(fresh) + " WHERE " + when.sql() + "), "
                // every window a matching fresh row falls in: its latest start, then (slides - 1) earlier ones
                + SqlIdent.q("__inspecto_open") + " AS (SELECT DISTINCT "
                + String.join("", keySelect.stream().map(s -> s + ", ").toList())
                + "((epoch_us(f." + ts + ") // " + slide + ") - r.i) * " + slide + " AS " + SqlIdent.q(WINDOW_START)
                + " FROM " + SqlIdent.q("__inspecto_fw") + " AS f CROSS JOIN range(0, " + w.slides() + ") AS r(i)), "
                + SqlIdent.q("__inspecto_bw") + " AS (SELECT * FROM " + SqlIdent.q(buffer) + " WHERE " + when.sql() + ") "
                + "SELECT " + String.join("", keyOut.stream().map(s -> s + ", ").toList()) + ws + ", "
                + agg + " AS " + SqlIdent.q(VALUE) + ", "
                + "min(epoch_us(b." + ts + ")) AS " + SqlIdent.q(FIRST) + ", "
                + "max(epoch_us(b." + ts + ")) AS " + SqlIdent.q(LAST)
                + " FROM " + SqlIdent.q("__inspecto_open") + " AS o JOIN " + SqlIdent.q("__inspecto_bw") + " AS b ON "
                + String.join(" AND ", keyJoin)
                + " WHERE " + ws + " + " + (size + late) + " > ?"
                + " GROUP BY " + String.join(", ", groupBy)
                + " HAVING " + agg + " " + comparator(rule) + " CAST(? AS DOUBLE)"
                + " ORDER BY " + String.join(", ", orderBy);
        List<Object> params = new ArrayList<>(when.params());
        params.addAll(when.params());
        params.add(watermarkUs);
        params.add(rule.threshold());
        return new Compiled(sql, List.copyOf(params));
    }

    /** The buffer column numbering the commit that buffered a row (PA-9c: a sequence's touched scope). */
    static final String SEQ = "__inspecto_seq";

    /**
     * PA-9c {@code sequence}: the (key, A event) pairs with a B event for the same key at or after A and at most
     * {@code size} later, where A or B arrived in commit {@code seq} and A's Window was still open before it
     * ({@code a + size + lateness > watermark}). A row never pairs with itself. Columns as {@link #breaches}:
     * {@link #WINDOW_START} is A's event time (the fired table's window start), {@link #VALUE} how many B events
     * follow it, {@link #FIRST} A's and {@link #LAST} the first B's event time (epoch µs).
     *
     * @param buffer a table holding {@link #columns} plus {@link #SEQ}
     */
    static Compiled sequence(AlertRule rule, String buffer, long seq, long watermarkUs) {
        AlertWindow w = rule.eventWindow();
        long within = micros(w.size()), late = micros(w.lateness());
        String ts = SqlIdent.q(w.time()), sq = SqlIdent.q(SEQ), rid = SqlIdent.q("__inspecto_rid");
        ConditionSql.Bound a = ConditionSql.predicateBound(w.pattern().steps().get(0));
        ConditionSql.Bound b = ConditionSql.predicateBound(w.pattern().steps().get(1));
        List<String> keySelect = new ArrayList<>(), keyOut = new ArrayList<>(), keyJoin = new ArrayList<>();
        for (int i = 0; i < rule.by().size(); i++) {
            String c = SqlIdent.q(rule.by().get(i)), k = SqlIdent.q(keyColumn(i));
            keySelect.add("a." + c + " AS " + k);
            keyOut.add("a." + c);
            keyJoin.add("b." + c + " IS NOT DISTINCT FROM a." + c);
        }
        String aTs = "epoch_us(a." + ts + ")", bTs = "epoch_us(b." + ts + ")";
        keyJoin.add("a." + rid + " <> b." + rid);
        keyJoin.add(bTs + " >= " + aTs);
        keyJoin.add(bTs + " <= " + aTs + " + " + within);
        List<String> groupBy = new ArrayList<>(keyOut);
        groupBy.add(aTs);
        String all = "SELECT rowid AS " + rid + ", * FROM " + SqlIdent.q(buffer);
        String sql = "WITH " + SqlIdent.q("__inspecto_a") + " AS (" + all + " WHERE " + a.sql() + "), "
                + SqlIdent.q("__inspecto_b") + " AS (" + all + " WHERE " + b.sql() + ") "
                + "SELECT " + String.join("", keySelect.stream().map(x -> x + ", ").toList())
                + aTs + " AS " + SqlIdent.q(WINDOW_START) + ", count(*) AS " + SqlIdent.q(VALUE) + ", "
                + aTs + " AS " + SqlIdent.q(FIRST) + ", min(" + bTs + ") AS " + SqlIdent.q(LAST)
                + " FROM " + SqlIdent.q("__inspecto_a") + " AS a JOIN " + SqlIdent.q("__inspecto_b") + " AS b ON "
                + String.join(" AND ", keyJoin)
                + " WHERE (a." + sq + " = ? OR b." + sq + " = ?) AND " + aTs + " + " + (within + late) + " > ?"
                + " GROUP BY " + String.join(", ", groupBy)
                + " ORDER BY " + SqlIdent.q(WINDOW_START)
                + String.join("", java.util.stream.IntStream.range(0, rule.by().size())
                        .mapToObj(i -> ", " + SqlIdent.q(keyColumn(i))).toList());
        List<Object> params = new ArrayList<>(a.params());
        params.addAll(b.params());
        params.add(seq);
        params.add(seq);
        params.add(watermarkUs);
        return new Compiled(sql, List.copyOf(params));
    }

    /**
     * PA-9c {@code absence}: the (key, Window) pairs judged by moving the close frontier from {@code fromUs} to
     * {@code toUs} — Windows {@code [ws, ws + size)} with {@code fromUs < ws + size + lateness <= toUs} — where the key
     * had an event matching {@code when} in the slide {@code [ws - slide, ws)} and has none in the Window. Columns as
     * {@link #breaches}: {@link #VALUE} is 0, {@link #FIRST} and {@link #LAST} the key's last event before the
     * silence (epoch µs).
     */
    static Compiled absence(AlertRule rule, String buffer, long fromUs, long toUs) {
        AlertWindow w = rule.eventWindow();
        long size = micros(w.size()), slide = micros(w.slide()), late = micros(w.lateness());
        String ts = SqlIdent.q(w.time());
        ConditionSql.Bound when = ConditionSql.predicateBound(rule.when());
        List<String> keySelect = new ArrayList<>(), keyOut = new ArrayList<>(), keyJoin = new ArrayList<>();
        for (int i = 0; i < rule.by().size(); i++) {
            String c = SqlIdent.q(rule.by().get(i)), k = SqlIdent.q(keyColumn(i));
            keySelect.add("e." + c + " AS " + k);
            keyOut.add("r." + k);
            keyJoin.add("b." + c + " IS NOT DISTINCT FROM r." + k);
        }
        String ws = "r." + SqlIdent.q(WINDOW_START);
        keyJoin.add("epoch_us(b." + ts + ") >= " + ws);
        keyJoin.add("epoch_us(b." + ts + ") < " + ws + " + " + size);
        String sql = "WITH " + SqlIdent.q("__inspecto_bw") + " AS (SELECT * FROM " + SqlIdent.q(buffer) + " WHERE " + when.sql() + "), "
                // the Window right after each event's slide: the one a silence after that event would leave empty
                + SqlIdent.q("__inspecto_ref") + " AS (SELECT "
                + String.join("", keySelect.stream().map(x -> x + ", ").toList())
                + "((epoch_us(e." + ts + ") // " + slide + ") + 1) * " + slide + " AS " + SqlIdent.q(WINDOW_START) + ", "
                + "max(epoch_us(e." + ts + ")) AS " + SqlIdent.q(LAST)
                + " FROM " + SqlIdent.q("__inspecto_bw") + " AS e GROUP BY ALL) "
                + "SELECT " + String.join("", keyOut.stream().map(x -> x + ", ").toList()) + ws + ", "
                + "0 AS " + SqlIdent.q(VALUE) + ", r." + SqlIdent.q(LAST) + " AS " + SqlIdent.q(FIRST) + ", r." + SqlIdent.q(LAST)
                + " FROM " + SqlIdent.q("__inspecto_ref") + " AS r"
                + " WHERE " + ws + " + " + (size + late) + " > ? AND " + ws + " + " + (size + late) + " <= ?"
                + " AND NOT EXISTS (SELECT 1 FROM " + SqlIdent.q("__inspecto_bw") + " AS b WHERE " + String.join(" AND ", keyJoin) + ")"
                + " ORDER BY " + ws + String.join("", keyOut.stream().map(x -> ", " + x).toList());
        List<Object> params = new ArrayList<>(when.params());
        params.add(fromUs);
        params.add(toUs);
        return new Compiled(sql, List.copyOf(params));
    }

    /**
     * How many {@code fresh} rows matching the rule's {@code when} arrived too late for every window they fall in:
     * their latest window had closed ({@code end + lateness <= watermark}) before this commit. They are kept, and
     * alerted on by nothing.
     */
    static Compiled lateRows(AlertRule rule, String fresh, long watermarkUs) {
        AlertWindow w = rule.eventWindow();
        long size = micros(w.size()), slide = micros(w.slide()), late = micros(w.lateness());
        if (w.isPattern() && w.pattern().isSequence()) {
            // PA-9c: a step's event is late once every Window it could pair in (anchored at A, size long) has closed
            ConditionSql.Bound a = ConditionSql.predicateBound(w.pattern().steps().get(0));
            ConditionSql.Bound b = ConditionSql.predicateBound(w.pattern().steps().get(1));
            String sql = "SELECT count(*) FROM " + SqlIdent.q(fresh) + " WHERE ((" + a.sql() + ") OR (" + b.sql() + "))"
                    + " AND epoch_us(" + SqlIdent.q(w.time()) + ") + " + (size + late) + " <= ?";
            List<Object> params = new ArrayList<>(a.params());
            params.addAll(b.params());
            params.add(watermarkUs);
            return new Compiled(sql, List.copyOf(params));
        }
        ConditionSql.Bound when = ConditionSql.predicateBound(rule.when());
        String sql = "SELECT count(*) FROM " + SqlIdent.q(fresh) + " WHERE " + when.sql()
                + " AND (epoch_us(" + SqlIdent.q(w.time()) + ") // " + slide + ") * " + slide + " + " + (size + late) + " <= ?";
        List<Object> params = new ArrayList<>(when.params());
        params.add(watermarkUs);
        return new Compiled(sql, List.copyOf(params));
    }

    /** The comparator's SQL operator: a windowed rule takes gt or gte only ({@link AlertRule}). */
    private static String comparator(AlertRule rule) {
        return switch (rule.comparator()) {
            case "gt" -> ">";
            case "gte" -> ">=";
            default -> throw new IllegalArgumentException("a windowed alert takes comparator gt or gte, not " + rule.comparator());
        };
    }

    /** {@code count} or {@code agg(field)}, already validated by {@link AlertRule}. */
    static MeasureCompiler.Measure measure(AlertRule rule) {
        String m = rule.measure().trim();
        int open = m.indexOf('(');
        return open < 0 ? new MeasureCompiler.Measure("count", null)
                : new MeasureCompiler.Measure(m.substring(0, open), m.substring(open + 1, m.length() - 1).trim());
    }

    static long micros(java.time.Duration d) {
        return d.toNanos() / 1000;
    }

    /** Every {@code field} and {@code valueField} a Condition Language tree reads. */
    private static void whenFields(Object node, Set<String> out) {
        if (!(node instanceof Map<?, ?> m)) return;
        Object items = m.get("items") != null ? m.get("items") : m.get("conditions");
        if (items instanceof List<?> l) {
            for (Object it : l) whenFields(it, out);
            return;
        }
        for (String k : List.of("field", "valueField")) {
            Object f = m.get(k);
            if (f != null && !String.valueOf(f).isBlank()) out.add(String.valueOf(f).trim());
        }
    }
}
