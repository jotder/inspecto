package com.gamma.pipeline.exec;

import com.gamma.etl.Identifiers;
import com.gamma.util.SqlIdent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code transform.running} Step's config grammar and its one SQL template (catalog
 * {@code transform.analytics.running}, operator 2026-10-06) — parsed here once, so the run
 * ({@link RowShaper}), the recipe compiler and the save-time check refuse the same configs with the same words.
 *
 * <p>🔴 <b>No authored text reaches the SQL as text.</b> Every column name is validated against
 * {@link Identifiers} and double-quoted; {@code fn} is one of five fixed words; the window size is parsed to a
 * positive {@code int} and re-printed; the unit maps to a fixed DuckDB keyword.
 *
 * <p>State is per run: the window functions see only the relation they are handed. Velocity that must span
 * batches stays a SQL-over-sink Job (the payment pack's {@code pf_instrument_velocity}).
 */
public final class RunningWindow {

    private RunningWindow() {}

    /** The five aggregate functions, each a fixed SQL word. */
    public static final List<String> FUNCTIONS = List.of("count", "sum", "avg", "min", "max");

    private static final Pattern DURATION = Pattern.compile("^(\\d{1,9})\\s*([smhd])$");
    private static final Pattern ROWS = Pattern.compile("^(\\d{1,9})(\\s*rows?)?$");

    /** One measure: {@code fn(column) AS as}; {@code column} is null only for {@code count(*)}. */
    public record Measure(String fn, String column, String as) {}

    /**
     * A parsed config. {@code unit} is null for a row window, else one of SECOND/MINUTE/HOUR/DAY.
     */
    public record Spec(List<String> partitionBy, String orderBy, int size, String unit, List<Measure> measures) {
        public boolean isDuration() { return unit != null; }

        /** Every column the inbound relation must carry. */
        public List<String> inputColumns() {
            List<String> out = new ArrayList<>(partitionBy);
            out.add(orderBy);
            for (Measure m : measures) if (m.column() != null) out.add(m.column());
            return out;
        }

        /** {@code SELECT *, … FROM "input" WINDOW w AS (…)} — identifiers quoted, nothing interpolated. */
        public String select(String input) {
            StringBuilder sb = new StringBuilder("SELECT *");
            for (Measure m : measures)
                sb.append(", ").append(m.fn().toUpperCase(Locale.ROOT)).append('(')
                        .append(m.column() == null ? "*" : SqlIdent.q(m.column()))
                        .append(") OVER w AS ").append(SqlIdent.q(m.as()));
            sb.append(" FROM ").append(SqlIdent.q(input)).append(" WINDOW w AS (");
            if (!partitionBy.isEmpty()) {
                sb.append("PARTITION BY ");
                for (int i = 0; i < partitionBy.size(); i++)
                    sb.append(i == 0 ? "" : ", ").append(SqlIdent.q(partitionBy.get(i)));
                sb.append(' ');
            }
            sb.append("ORDER BY ").append(SqlIdent.q(orderBy)).append(' ');
            if (isDuration())
                sb.append("RANGE BETWEEN INTERVAL ").append(size).append(' ').append(unit)
                        .append(" PRECEDING AND CURRENT ROW");
            else
                sb.append("ROWS BETWEEN ").append(size - 1).append(" PRECEDING AND CURRENT ROW");
            return sb.append(')').toString();
        }
    }

    /**
     * Parse and validate {@code cfg}; throws {@link IllegalArgumentException} naming the first problem,
     * prefixed {@code transform.running}.
     */
    public static Spec parse(Map<?, ?> cfg) {
        List<String> partitionBy = new ArrayList<>();
        Object pb = cfg.get("partition_by");
        if (pb instanceof List<?> l) {
            for (Object o : l) if (o != null && !o.toString().isBlank()) partitionBy.add(ident(o, "partition_by"));
        } else if (pb != null && !pb.toString().isBlank()) {
            if (!(pb instanceof String)) throw bad("'partition_by' must be a list of column names");
            partitionBy.add(ident(pb, "partition_by"));
        }

        Object ob = cfg.get("order_by");
        if (ob == null || ob.toString().isBlank())
            throw bad("needs an 'order_by' column - the time column the window slides along");
        String orderBy = ident(ob, "order_by");

        Object w = cfg.get("window");
        if (w == null || w.toString().isBlank())
            throw bad("needs a 'window' - a duration (60m, 24h, 30s, 7d) or a row count (5 rows)");
        String ws = w.toString().trim().toLowerCase(Locale.ROOT);
        int size;
        String unit = null;
        Matcher d = DURATION.matcher(ws);
        Matcher r = ROWS.matcher(ws);
        if (d.matches()) {
            size = Integer.parseInt(d.group(1));
            unit = switch (d.group(2)) {
                case "s" -> "SECOND";
                case "m" -> "MINUTE";
                case "h" -> "HOUR";
                default -> "DAY";
            };
        } else if (r.matches()) {
            size = Integer.parseInt(r.group(1));
        } else {
            throw bad("window '" + w + "' is neither a duration (60m, 24h, 30s, 7d) nor a row count (5 rows)");
        }
        if (size <= 0) throw bad("window '" + w + "' must be greater than zero");

        if (!(cfg.get("measures") instanceof List<?> ms) || ms.isEmpty())
            throw bad("needs at least one 'measures' entry {fn, column, as}");
        List<Measure> measures = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < ms.size(); i++) {
            String at = "measures[" + i + "]";
            if (!(ms.get(i) instanceof Map<?, ?> m))
                throw bad(at + " must be a map {fn, column, as}");
            for (Object k : m.keySet())
                if (!Set.of("fn", "column", "as").contains(String.valueOf(k)))
                    throw bad(at + " has unknown key '" + k + "' (only fn, column, as)");
            String fn = m.get("fn") == null ? "" : m.get("fn").toString().trim().toLowerCase(Locale.ROOT);
            if (!FUNCTIONS.contains(fn))
                throw bad(at + ".fn '" + m.get("fn") + "' is not one of " + FUNCTIONS);
            Object c = m.get("column");
            String column = c == null || c.toString().isBlank() ? null : ident(c, at + ".column");
            if (column == null && !"count".equals(fn))
                throw bad(at + " needs a 'column' - only count may omit it");
            Object as = m.get("as");
            if (as == null || as.toString().isBlank()) throw bad(at + " needs an 'as' - the output column name");
            String alias = ident(as, at + ".as");
            if (!names.add(alias.toLowerCase(Locale.ROOT)))
                throw bad(at + ".as '" + alias + "' names the same column as an earlier measure");
            measures.add(new Measure(fn, column, alias));
        }
        return new Spec(List.copyOf(partitionBy), orderBy, size, unit, List.copyOf(measures));
    }

    /**
     * The first refusal against the inbound columns, or {@code null}: a referenced column missing, or an
     * {@code as} that collides with an inbound column (DuckDB would silently emit a duplicate name).
     */
    public static String columnRefusal(Spec spec, List<String> inbound) {
        for (String c : spec.inputColumns())
            if (inbound.stream().noneMatch(x -> x.equalsIgnoreCase(c)))
                return "transform.running: column '" + c + "' is not in the inbound data (have: " + inbound + ")";
        for (Measure m : spec.measures())
            if (inbound.stream().anyMatch(x -> x.equalsIgnoreCase(m.as())))
                return "transform.running: measure name '" + m.as() + "' collides with an inbound column";
        return null;
    }

    /** True when DuckDB can range a duration window over a column of this type. */
    public static boolean isTimeType(String duckType) {
        String t = duckType == null ? "" : duckType.toUpperCase(Locale.ROOT);
        return t.equals("DATE") || t.startsWith("TIMESTAMP");
    }

    private static String ident(Object o, String where) {
        String s = o.toString().trim();
        try {
            Identifiers.validate(s, "transform.running " + where);
        } catch (IllegalArgumentException e) {
            throw bad("'" + where + "' value '" + s + "' is not a plain column name ([A-Za-z_][A-Za-z0-9_]*)");
        }
        return s;
    }

    private static IllegalArgumentException bad(String msg) {
        return new IllegalArgumentException("transform.running " + msg);
    }
}
