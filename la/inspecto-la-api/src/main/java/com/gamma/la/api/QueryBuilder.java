package com.gamma.la.api;

import com.gamma.la.core.DatasetProvider;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gamma.util.SqlIdent.q;

/**
 * The no-code query builder (LA-QUERY-BUILDER-1, plan {@code docs/superpower/la-basic-discovery-plan.md} §3 P4): a question over
 * a Dataset's ROLE columns - who, grouped by From or To, has the most links, distinct counterparts or total of a Measure, with
 * From is / To is / Kind of link is / When between (any Time column) / Measure between (any Measure column) / at least -
 * answered as a table that can be drawn and saved as a Space preset ({@code preset-query/<id>}).
 *
 * <p><b>Declarative, never free SQL</b> (plan Q-2): {@link #parse} accepts exactly the keys below and refuses anything else; a
 * column must be one the Dataset gives that role (a Time column for {@code timeColumn}, a Measure for {@code sumColumn} and
 * {@code measureColumn}) and a real column of its relation; every value is bound. A saved preset is parsed the same way each
 * time it runs, so a stored query the Dataset no longer supports is refused, never half-applied.
 */
final class QueryBuilder {

    private QueryBuilder() {}

    /** The keys a query may carry. */
    static final Set<String> KEYS = Set.of("by", "rank", "sumColumn", "from", "to", "kinds", "timeColumn", "after", "before",
            "measureColumn", "measureMin", "measureMax", "min", "top");

    /**
     * One query. {@code by}: {@code from | to}. {@code rank}: {@code links | counterparts | sum} (sum needs {@code sumColumn}).
     * {@code from} / {@code to}: one entity (an id or an exploration alias). {@code kinds}: kinds of link. {@code after} /
     * {@code before}: a half-open window [after, before) on {@code timeColumn} (the primary Time when absent).
     * {@code measureMin} / {@code measureMax}: an inclusive range on {@code measureColumn} per link. {@code min}: keep the
     * groups whose ranked value is at least this. {@code top}: rows, 1 to {@link PresetQueries#MAX_ROWS}.
     */
    record Query(String by, String rank, String sumColumn, String from, String to, List<String> kinds, String timeColumn,
                 String after, String before, String measureColumn, Double measureMin, Double measureMax, Double min, int top) {}

    /** The Dataset's role columns a query may name: its Time columns and its Measures (each a relation's own spelling). */
    record Columns(List<String> times, List<String> measures) {
        Map<String, Object> toMap(List<DatasetDiscovery.Measure> withUnits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", times);
            List<Map<String, Object>> ms = new ArrayList<>();
            for (DatasetDiscovery.Measure x : withUnits) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("column", x.column());
                e.put("unit", x.unit());
                ms.add(e);
            }
            m.put("measure", ms);
            return m;
        }
    }

    /**
     * The Time columns: every column whose role is {@code temporal}, plus the primary Time (the link mapping's, or the
     * back-compatible read) first; the Measures: {@link DatasetDiscovery#measures}.
     */
    static Columns columns(Map<String, Object> dataset, List<DatasetProvider.Column> real, PresetQueries.Roles roles) {
        List<String> times = new ArrayList<>();
        if (roles.time() != null) times.add(roles.time());
        if (dataset.get("columns") instanceof List<?> cols)
            for (Object o : cols)
                if (o instanceof Map<?, ?> c && "temporal".equalsIgnoreCase(String.valueOf(c.get("role")))) {
                    String r = realName(real, c.get("name"));
                    if (r != null && !times.contains(r)) times.add(r);
                }
        List<String> measures = new ArrayList<>();
        for (DatasetDiscovery.Measure m : DatasetDiscovery.measures(dataset, real, roles)) measures.add(m.column());
        return new Columns(times, measures);
    }

    private static String realName(List<DatasetProvider.Column> real, Object name) {
        if (!(name instanceof String s)) return null;
        for (DatasetProvider.Column c : real)
            if (c.name().equalsIgnoreCase(s.trim()) && PresetQueries.SAFE_IDENT.matcher(c.name()).matches()) return c.name();
        return null;
    }

    /** The query in {@code body}, validated against the Dataset's roles; {@link IllegalArgumentException} names what is wrong. */
    static Query parse(Object body, PresetQueries.Roles roles, Columns cols) {
        if (!(body instanceof Map<?, ?> m)) throw new IllegalArgumentException("'query' must be an object");
        for (Object k : m.keySet())
            if (!KEYS.contains(String.valueOf(k))) throw new IllegalArgumentException("a query has no '" + k + "' (allowed: " + KEYS + ")");
        if (roles.from() == null || roles.to() == null)
            throw new IllegalArgumentException("the Dataset does not say which columns link one entity to another (a Mapping's From and To)");
        String by = text(m, "by", "from");
        if (!by.equals("from") && !by.equals("to")) throw new IllegalArgumentException("'by' must be from or to");
        String rank = text(m, "rank", "links");
        if (!Set.of("links", "counterparts", "sum").contains(rank))
            throw new IllegalArgumentException("'rank' must be links, counterparts or sum");
        String sumColumn = column(m, "sumColumn", cols.measures(), "a Measure");
        if (rank.equals("sum") && sumColumn == null) throw new IllegalArgumentException("'rank: sum' needs 'sumColumn', a Measure");
        List<String> kinds = new ArrayList<>();
        if (m.get("kinds") != null) {
            if (!(m.get("kinds") instanceof List<?> l)) throw new IllegalArgumentException("'kinds' must be a list");
            for (Object o : l) {
                if (!(o instanceof String s) || s.isBlank()) throw new IllegalArgumentException("'kinds' holds text values only");
                if (!kinds.contains(s.trim())) kinds.add(s.trim());
            }
            if (!kinds.isEmpty() && roles.kind() == null)
                throw new IllegalArgumentException("'kinds' needs the Dataset to map a kind of link; it does not");
        }
        String timeColumn = column(m, "timeColumn", cols.times(), "a Time");
        String after = instant(m, "after"), before = instant(m, "before");
        if ((after != null || before != null) && timeColumn == null) timeColumn = roles.time();
        if ((after != null || before != null) && timeColumn == null)
            throw new IllegalArgumentException("'after' / 'before' need a Time column; the Dataset has none");
        if (after != null && before != null && after.compareTo(before) >= 0)
            throw new IllegalArgumentException("'after' must be before 'before'");
        String measureColumn = column(m, "measureColumn", cols.measures(), "a Measure");
        Double measureMin = number(m, "measureMin"), measureMax = number(m, "measureMax");
        if ((measureMin != null || measureMax != null) && measureColumn == null)
            throw new IllegalArgumentException("'measureMin' / 'measureMax' need 'measureColumn', a Measure");
        if (measureMin != null && measureMax != null && measureMin > measureMax)
            throw new IllegalArgumentException("'measureMin' must be at most 'measureMax'");
        Double min = number(m, "min");
        if (min != null && min < 0) throw new IllegalArgumentException("'min' must be at least 0");
        Double topD = number(m, "top");
        int top = topD == null ? 50 : (int) Math.floor(topD);
        if (top < 1 || top > PresetQueries.MAX_ROWS)
            throw new IllegalArgumentException("'top' must be 1 to " + PresetQueries.MAX_ROWS);
        return new Query(by, rank, sumColumn, entity(m, "from"), entity(m, "to"), List.copyOf(kinds), timeColumn, after, before,
                measureColumn, measureMin, measureMax, min, top);
    }

    private static String text(Map<?, ?> m, String key, String dflt) {
        Object v = m.get(key);
        if (v == null) return dflt;
        if (!(v instanceof String s) || s.isBlank()) throw new IllegalArgumentException("'" + key + "' must be text");
        return s.trim();
    }

    private static String entity(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null || (v instanceof String s && s.isBlank())) return null;
        if (!(v instanceof String s)) throw new IllegalArgumentException("'" + key + "' must be text");
        return s.trim();
    }

    /** A named column: null when absent; one of {@code allowed} (case-insensitive), as the relation spells it, else refused. */
    private static String column(Map<?, ?> m, String key, List<String> allowed, String role) {
        Object v = m.get(key);
        if (v == null) return null;
        if (v instanceof String s) for (String a : allowed) if (a.equalsIgnoreCase(s.trim())) return a;
        throw new IllegalArgumentException("'" + key + "' must name " + role + " column of the Dataset: " + allowed);
    }

    private static Double number(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        try {
            double d = v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v).trim());
            if (Double.isFinite(d)) return d;
        } catch (NumberFormatException notANumber) {
            // refused below
        }
        throw new IllegalArgumentException("'" + key + "' must be a number");
    }

    /** An ISO instant ({@code 2026-10-01T00:00:00Z}) or a day ({@code 2026-10-01}, its start in UTC), as a UTC timestamp literal. */
    private static String instant(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null || (v instanceof String s && s.isBlank())) return null;
        String s = String.valueOf(v).trim();
        try {
            return LocalDateTime.ofInstant(Instant.parse(s), ZoneId.of("UTC")).toString();
        } catch (DateTimeParseException notAnInstant) {
            try {
                return LocalDate.parse(s).atStartOfDay().toString();
            } catch (DateTimeParseException notADay) {
                throw new IllegalArgumentException("'" + key + "' must be a day (2026-10-01) or an instant (2026-10-01T00:00:00Z)");
            }
        }
    }

    /** The result columns, in order: the entity, distinct counterparts, links, and the total of {@code sumColumn} when set. */
    static List<PresetQueries.Column> resultColumns(Query q) {
        List<PresetQueries.Column> cols = new ArrayList<>(List.of(new PresetQueries.Column("entity", "Entity", true),
                new PresetQueries.Column("counterparts", "Distinct counterparts", false),
                new PresetQueries.Column("links", "Links", false)));
        if (q.sumColumn() != null) cols.add(new PresetQueries.Column("total", "Total " + q.sumColumn(), false));
        return cols;
    }

    /**
     * The statement: {@code binds} receives, in order, the From entity, the To entity, the kinds, the window, the Measure range
     * and the minimum. {@code from} / {@code to} are the unmasked values (the route resolves aliases).
     */
    static String sql(String datasetId, Query q, PresetQueries.Roles r, String from, String to, List<String> binds) {
        String by = q("from".equals(q.by()) ? r.from() : r.to()), other = q("from".equals(q.by()) ? r.to() : r.from());
        String total = q.sumColumn() == null ? null : "SUM(TRY_CAST(" + q(q.sumColumn()) + " AS DOUBLE))";
        StringBuilder sql = new StringBuilder("SELECT CAST(").append(by).append(" AS VARCHAR) AS entity, COUNT(DISTINCT ").append(other)
                .append(") AS counterparts, COUNT(*) AS links");
        if (total != null) sql.append(", ").append(total).append(" AS total");
        sql.append(" FROM ").append(q(datasetId)).append(" WHERE ").append(q(r.from())).append(" IS NOT NULL AND ").append(q(r.to()))
                .append(" IS NOT NULL");
        if (from != null) {
            sql.append(" AND CAST(").append(q(r.from())).append(" AS VARCHAR) = ?");
            binds.add(from);
        }
        if (to != null) {
            sql.append(" AND CAST(").append(q(r.to())).append(" AS VARCHAR) = ?");
            binds.add(to);
        }
        if (!q.kinds().isEmpty()) {
            sql.append(" AND CAST(").append(q(r.kind())).append(" AS VARCHAR) IN (");
            for (int i = 0; i < q.kinds().size(); i++) {
                sql.append(i == 0 ? "?" : ", ?");
                binds.add(q.kinds().get(i));
            }
            sql.append(")");
        }
        String ts = q.timeColumn() == null ? null : "TRY_CAST(" + q(q.timeColumn()) + " AS TIMESTAMP)";
        if (q.after() != null) {
            sql.append(" AND ").append(ts).append(" >= CAST(? AS TIMESTAMP)");
            binds.add(q.after());
        }
        if (q.before() != null) {
            sql.append(" AND ").append(ts).append(" < CAST(? AS TIMESTAMP)");
            binds.add(q.before());
        }
        String v = q.measureColumn() == null ? null : "TRY_CAST(" + q(q.measureColumn()) + " AS DOUBLE)";
        if (q.measureMin() != null) {
            sql.append(" AND ").append(v).append(" >= CAST(? AS DOUBLE)");
            binds.add(String.valueOf(q.measureMin()));
        }
        if (q.measureMax() != null) {
            sql.append(" AND ").append(v).append(" <= CAST(? AS DOUBLE)");
            binds.add(String.valueOf(q.measureMax()));
        }
        String ranked = switch (q.rank()) {
            case "counterparts" -> "COUNT(DISTINCT " + other + ")";
            case "sum" -> total;
            default -> "COUNT(*)";
        };
        String order = switch (q.rank()) {
            case "counterparts" -> "counterparts";
            case "sum" -> "total";
            default -> "links";
        };
        sql.append(" GROUP BY 1");
        if (q.min() != null) {
            sql.append(" HAVING ").append(ranked).append(" >= CAST(? AS DOUBLE)");
            binds.add(String.valueOf(q.min()));
        }
        return sql.append(" ORDER BY ").append(order).append(" DESC NULLS LAST, entity").toString();
    }

    /** The query as stored and echoed: only its set keys. */
    static Map<String, Object> toMap(Query q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("by", q.by());
        m.put("rank", q.rank());
        if (q.sumColumn() != null) m.put("sumColumn", q.sumColumn());
        if (q.from() != null) m.put("from", q.from());
        if (q.to() != null) m.put("to", q.to());
        if (!q.kinds().isEmpty()) m.put("kinds", q.kinds());
        if (q.timeColumn() != null) m.put("timeColumn", q.timeColumn());
        if (q.after() != null) m.put("after", q.after());
        if (q.before() != null) m.put("before", q.before());
        if (q.measureColumn() != null) m.put("measureColumn", q.measureColumn());
        if (q.measureMin() != null) m.put("measureMin", q.measureMin());
        if (q.measureMax() != null) m.put("measureMax", q.measureMax());
        if (q.min() != null) m.put("min", q.min());
        m.put("top", q.top());
        return m;
    }
}
