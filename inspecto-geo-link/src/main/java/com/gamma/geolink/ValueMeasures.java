package com.gamma.geolink;

import com.gamma.alert.AlertRule;
import com.gamma.query.QueryExecutor;
import com.gamma.util.SqlIdent;
import com.gamma.sql.SqlSandboxPolicy;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * <b>LA-18 value Measures</b> ({@code docs/superpower/link-analysis-backlog-plan.md} §2.6.1, DECIDED 2026-09-30) —
 * named Measures with VISIBLE thresholds over the WHOLE Dataset, never an opaque score. Each answers the entities
 * that breach its thresholds in a {@code [from, to)} window; an Alert Rule ({@code alert.valueMeasure}) watches the
 * COUNT of those entities and fires when it is above 0 (one Alert per rule, never per entity — G-42).
 *
 * <p>🔴 <b>Whole Dataset, never the view.</b> The relation is the Dataset's own ({@link InvRoutes#relationFor}); no
 * filter is accepted, so a view narrowed to {@code AMOUNT ≥ 5 000} cannot remove the sub-threshold legs structuring
 * is made of — the §2.6 trap.
 *
 * <p>Fences: every identifier checked against the relation's real columns by the caller and quoted here; every value
 * bound; a {@value #TIMEOUT_SECONDS} s statement timeout; at most {@value #MAX_ENTITIES} entities leave DuckDB
 * (more ⇒ {@code truncated}); the window at most {@value #MAX_WINDOW_DAYS} days.
 */
final class ValueMeasures {

    static final int MAX_ENTITIES = 10_000;
    static final int TIMEOUT_SECONDS = 10;
    static final int MAX_WINDOW_DAYS = 31;
    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** The Measures an Alert Rule may watch — {@link AlertRule#VALUE_MEASURES} mirrors this (pinned by test). */
    static final Set<String> ALERTABLE = Set.of("passThrough", "velocity", "timeToCashOut", "cashOutConcentration",
            "structuring", "benefitTransfer");
    /** Plus the one that is a weighting, not a test: readable, never alertable. */
    static final String VALUE_WEIGHTED_LINKS = "valueWeightedLinks";

    /** Each Measure's thresholds with their defaults (§2.6.1) — every one visible in the answer and editable. */
    static final Map<String, Map<String, Double>> DEFAULTS = Map.of(
            "passThrough", Map.of("minInbound", 10_000d, "minRatio", 0.90),
            "velocity", Map.of("minInbound", 10_000d, "maxHours", 24d),
            "timeToCashOut", Map.of("minInbound", 10_000d, "maxHours", 48d),
            "cashOutConcentration", Map.of("minShare", 0.20, "minPayers", 5d),
            "structuring", Map.of("min", 900d, "max", 1_000d, "minLegs", 10d, "minPayers", 5d),
            "benefitTransfer", Map.of("maxHours", 72d, "minShare", 0.50, "minRecipients", 5d),
            VALUE_WEIGHTED_LINKS, Map.of());
    /** The Measures that read a list of link kinds from {@code kindCol}. */
    private static final Map<String, String> KIND_LIST = Map.of("timeToCashOut", "cashOutKinds",
            "cashOutConcentration", "cashOutKinds", "benefitTransfer", "benefitKinds");
    private static final Set<String> COMMON = Set.of("name", "valueCol", "timeCol", "from", "to");

    /** One parsed, validated Measure: its name, columns, window, thresholds (defaults filled) and kind list. */
    record Spec(String name, String valueCol, String timeCol, String from, String to, Map<String, Double> thresholds,
                List<String> kinds) {

        /** The block as stored on an Alert Rule and answered to the caller — every threshold spelled out. */
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("valueCol", valueCol);
            m.put("timeCol", timeCol);
            m.put("from", from);
            m.put("to", to);
            m.putAll(thresholds);
            if (KIND_LIST.containsKey(name)) m.put(KIND_LIST.get(name), kinds);
            return m;
        }

        List<String> columns() {
            return List.of(valueCol, timeCol);
        }
    }

    /** The answer: the breaching entities (or the weighted pairs), whether capped, and the rows the window skipped. */
    record Result(List<Map<String, Object>> entities, boolean truncated, long rowsInWindow, long unvalued) {}

    private ValueMeasures() {}

    /** Parse and validate a block {@code {name, valueCol, timeCol, from, to, …thresholds}}; defaults fill the rest. */
    static Spec parse(Map<String, Object> block, boolean alertable) {
        String name = str(block.get("name"));
        if (name == null || !DEFAULTS.containsKey(name) || (alertable && !ALERTABLE.contains(name)))
            throw new IllegalArgumentException("value measure 'name' must be one of "
                    + (alertable ? ALERTABLE : DEFAULTS.keySet()) + ", got '" + name + "'");
        Map<String, Double> defaults = DEFAULTS.get(name);
        String kindKey = KIND_LIST.get(name);
        for (String k : block.keySet())
            if (!COMMON.contains(k) && !defaults.containsKey(k) && !k.equals(kindKey))
                throw new IllegalArgumentException("'" + k + "' is not a setting of " + name + " — its settings are "
                        + COMMON + " + " + defaults.keySet() + (kindKey == null ? "" : " + " + kindKey));
        String valueCol = ident(block, "valueCol");
        String timeCol = ident(block, "timeCol");
        LocalDateTime from = instant(block, "from");
        LocalDateTime to = instant(block, "to");
        if (!to.isAfter(from)) throw new IllegalArgumentException("'to' must be after 'from'");
        if (Duration.between(from, to).compareTo(Duration.ofDays(MAX_WINDOW_DAYS)) > 0)
            throw new IllegalArgumentException("the window may span at most " + MAX_WINDOW_DAYS + " days");
        Map<String, Double> thresholds = new LinkedHashMap<>();
        for (var d : new java.util.TreeMap<>(defaults).entrySet()) {
            Object v = block.get(d.getKey());
            double x;
            if (v == null) x = d.getValue();
            else if (v instanceof Number n) x = n.doubleValue();
            else try {
                x = Double.parseDouble(String.valueOf(v).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + d.getKey() + "' must be a number, got '" + v + "'");
            }
            if (!Double.isFinite(x) || x < 0) throw new IllegalArgumentException("'" + d.getKey() + "' must be ≥ 0");
            thresholds.put(d.getKey(), x);
        }
        if ("structuring".equals(name) && thresholds.get("max") <= thresholds.get("min"))
            throw new IllegalArgumentException("structuring 'max' must be above 'min'");
        List<String> kinds = List.of();
        if (kindKey != null) {
            Object raw = block.get(kindKey);
            List<?> items = raw instanceof List<?> l ? l : raw == null ? List.of() : List.of(String.valueOf(raw).split(","));
            kinds = items.stream().map(o -> String.valueOf(o).trim()).filter(s -> !s.isEmpty()).distinct().toList();
            if (kinds.isEmpty())
                throw new IllegalArgumentException(name + " needs '" + kindKey + "' — the link kinds that mark it");
        }
        return new Spec(name, valueCol, timeCol, from.toString(), to.toString(), thresholds, kinds);
    }

    /** The one-line statement of a Measure's thresholds, e.g. {@code passThrough ≥ 0.9 with inbound ≥ 10000}. */
    static String label(Spec s) {
        Map<String, Double> t = s.thresholds();
        return switch (s.name()) {
            case "passThrough" -> "out ÷ in ≥ " + js(t.get("minRatio")) + " with inbound ≥ " + js(t.get("minInbound"));
            case "velocity" -> "median hours from inbound to next outbound ≤ " + js(t.get("maxHours"))
                    + " with inbound ≥ " + js(t.get("minInbound"));
            case "timeToCashOut" -> "median hours from inbound to next cash-out " + s.kinds() + " ≤ "
                    + js(t.get("maxHours")) + " with inbound ≥ " + js(t.get("minInbound"));
            case "cashOutConcentration" -> "share of all cash-out " + s.kinds() + " ≥ " + js(t.get("minShare"))
                    + " from ≥ " + js(t.get("minPayers")) + " payers";
            case "structuring" -> "≥ " + js(t.get("minLegs")) + " legs " + js(t.get("min")) + " ≤ " + s.valueCol()
                    + " < " + js(t.get("max")) + " from ≥ " + js(t.get("minPayers")) + " payers";
            case "benefitTransfer" -> "≥ " + js(t.get("minRecipients")) + " recipients of " + s.kinds()
                    + " forwarding ≥ " + js(t.get("minShare")) + " of it within " + js(t.get("maxHours")) + " h";
            default -> "sum and count of " + s.valueCol() + " per pair";
        };
    }

    /**
     * Evaluate one Measure over the WHOLE relation {@code relationSql} (registered as {@code datasetId}).
     * {@code kindCol} may be null except for a Measure that reads link kinds.
     */
    static Result evaluate(String datasetId, String relationSql, String sourceCol, String targetCol, String kindCol,
                           Spec s) throws SQLException, java.io.IOException {
        if (KIND_LIST.containsKey(s.name()) && kindCol == null)
            throw new IllegalArgumentException(s.name() + " needs a link-kind column (linkKindCol)");
        List<String> binds = new ArrayList<>(List.of(s.from(), s.to()));
        String base = "WITH __l AS (SELECT CAST(" + q(sourceCol) + " AS VARCHAR) AS s, CAST(" + q(targetCol)
                + " AS VARCHAR) AS t, " + (kindCol == null ? "CAST(NULL AS VARCHAR)" : "CAST(" + q(kindCol) + " AS VARCHAR)")
                + " AS k, TRY_CAST(" + q(s.valueCol()) + " AS DOUBLE) AS v, TRY_CAST(" + q(s.timeCol())
                + " AS TIMESTAMP) AS ts FROM " + q(datasetId) + " WHERE " + q(sourceCol) + " IS NOT NULL AND "
                + q(targetCol) + " IS NOT NULL), __x AS (SELECT * FROM __l WHERE ts >= CAST(? AS TIMESTAMP) AND ts < "
                + "CAST(? AS TIMESTAMP)), __w AS (SELECT * FROM __x WHERE v IS NOT NULL)";
        SqlSandboxPolicy policy = SqlSandboxPolicy.withCaps(null, 0, TIMEOUT_SECONDS);

        Map<String, Object> stats = QueryExecutor.run(new QueryExecutor.Request(datasetId, relationSql,
                base + " SELECT count(*) AS n, count(*) FILTER (WHERE v IS NULL) AS unvalued FROM __x", 1, 0,
                List.of(), List.of(), List.copyOf(binds)), policy).rows().get(0);

        Map<String, Double> t = s.thresholds();
        String sql = switch (s.name()) {
            case "passThrough" -> {
                binds.add(num(t.get("minInbound")));
                binds.add(num(t.get("minRatio")));
                yield base + ", __in AS (SELECT t AS e, sum(v) AS inbound, min(ts) AS first_in FROM __w GROUP BY t),"
                        + " __out AS (SELECT w.s AS e, sum(w.v) AS outbound FROM __w w JOIN __in i ON w.s = i.e"
                        + " WHERE w.ts >= i.first_in GROUP BY w.s),"
                        + " __r AS (SELECT i.e AS entity, i.inbound, coalesce(o.outbound, 0) AS outbound,"
                        + " coalesce(o.outbound, 0) / i.inbound AS ratio FROM __in i LEFT JOIN __out o ON o.e = i.e)"
                        + " SELECT entity, inbound, outbound, ratio, 1 - ratio AS retention FROM __r"
                        + " WHERE inbound >= CAST(? AS DOUBLE) AND ratio >= CAST(? AS DOUBLE) ORDER BY ratio DESC, entity";
            }
            case "velocity", "timeToCashOut" -> {
                String outKinds = "";
                if (s.name().equals("timeToCashOut")) outKinds = " AND o.k IN (" + placeholders(s.kinds(), binds) + ")";
                binds.add(num(t.get("minInbound")));
                binds.add(num(t.get("maxHours")));
                yield base + ", __in AS (SELECT t AS e, sum(v) AS inbound FROM __w GROUP BY t),"
                        + " __g AS (SELECT i.t AS e, (SELECT min(o.ts) FROM __w o WHERE o.s = i.t AND o.ts >= i.ts"
                        + outKinds + ") AS nxt, i.ts FROM __w i)"
                        + " SELECT g.e AS entity, median(date_diff('second', g.ts, g.nxt)) / 3600.0 AS hours,"
                        + " count(*) AS legs, min(n.inbound) AS inbound FROM __g g JOIN __in n ON n.e = g.e"
                        + " WHERE g.nxt IS NOT NULL GROUP BY g.e"
                        + " HAVING min(n.inbound) >= CAST(? AS DOUBLE) AND median(date_diff('second', g.ts, g.nxt))"
                        + " / 3600.0 <= CAST(? AS DOUBLE) ORDER BY hours, entity";
            }
            case "cashOutConcentration" -> {
                String kinds = placeholders(s.kinds(), binds);
                binds.add(num(t.get("minShare")));
                binds.add(num(t.get("minPayers")));
                yield base + ", __c AS (SELECT * FROM __w WHERE k IN (" + kinds + "))"
                        + " SELECT t AS entity, sum(v) AS cashOut, sum(v) / (SELECT sum(v) FROM __c) AS share,"
                        + " count(DISTINCT s) AS payers FROM __c GROUP BY t"
                        + " HAVING sum(v) / (SELECT sum(v) FROM __c) >= CAST(? AS DOUBLE)"
                        + " AND count(DISTINCT s) >= CAST(? AS DOUBLE) ORDER BY share DESC, entity";
            }
            case "structuring" -> {
                binds.add(num(t.get("min")));
                binds.add(num(t.get("max")));
                binds.add(num(t.get("minLegs")));
                binds.add(num(t.get("minPayers")));
                yield base + " SELECT t AS entity, count(*) AS legs, count(DISTINCT s) AS payers, sum(v) AS total"
                        + " FROM __w WHERE v >= CAST(? AS DOUBLE) AND v < CAST(? AS DOUBLE) GROUP BY t"
                        + " HAVING count(*) >= CAST(? AS DOUBLE) AND count(DISTINCT s) >= CAST(? AS DOUBLE)"
                        + " ORDER BY legs DESC, entity";
            }
            case "benefitTransfer" -> {
                String kinds = placeholders(s.kinds(), binds);
                binds.add(num(t.get("maxHours")));
                binds.add(num(t.get("minShare")));
                binds.add(num(t.get("minRecipients")));
                yield base + ", __b AS (SELECT t AS r, v, ts FROM __w WHERE k IN (" + kinds + ")),"
                        + " __sk AS (SELECT DISTINCT o.t AS c, b.r AS r, o.s AS os, o.ts AS ots, o.v AS ov FROM __b b"
                        + " JOIN __w o ON o.s = b.r AND date_diff('second', b.ts, o.ts) BETWEEN 0"
                        + " AND CAST(? AS DOUBLE) * 3600 AND o.v >= CAST(? AS DOUBLE) * b.v)"
                        + " SELECT c AS entity, count(DISTINCT r) AS recipients, sum(ov) AS forwarded FROM __sk"
                        + " GROUP BY c HAVING count(DISTINCT r) >= CAST(? AS DOUBLE) ORDER BY recipients DESC, entity";
            }
            default -> base + " SELECT s AS source, t AS target, k AS kind, sum(v) AS total, count(*) AS links"
                    + " FROM __w GROUP BY s, t, k ORDER BY total DESC, source, target";
        };
        QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request(datasetId, relationSql, sql,
                MAX_ENTITIES, 0, List.of(), List.of(), binds), policy);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : r.rows()) rows.add(new LinkedHashMap<>(row));
        return new Result(rows, r.truncated(), ((Number) stats.get("n")).longValue(),
                ((Number) stats.get("unvalued")).longValue());
    }

    /**
     * Evaluate {@code s} over the whole Dataset an Investigation is bound to: its {@code sourceCol}/{@code targetCol}/
     * {@code linkKindCol} roles, the Measure's own {@code valueCol}/{@code timeCol}, every one checked against the
     * relation's real columns first. Throws {@link IllegalArgumentException} for an unknown column.
     */
    static Result forInvestigation(String datasetId, String relationSql, Map<String, Object> header, Spec s)
            throws SQLException, java.io.IOException {
        String src = str(header.get("sourceCol")), tgt = str(header.get("targetCol")), kind = str(header.get("linkKindCol"));
        List<String> columns = InvRoutes.relationColumns(datasetId, relationSql);
        for (String col : java.util.Arrays.asList(src, tgt, kind, s.valueCol(), s.timeCol()))
            if (col != null && !InvRoutes.containsIgnoreCase(columns, col))
                throw new IllegalArgumentException("unknown column '" + col + "' — not a column of dataset '" + datasetId + "'");
        return evaluate(datasetId, relationSql, src, tgt, kind, s);
    }

    private static String placeholders(List<String> values, List<String> binds) {
        binds.addAll(values);
        return String.join(", ", java.util.Collections.nCopies(values.size(), "?"));
    }

    private static String ident(Map<String, Object> block, String key) {
        String v = str(block.get(key));
        if (v == null) throw new IllegalArgumentException("value measure needs '" + key + "'");
        if (!SAFE_IDENT.matcher(v).matches())
            throw new IllegalArgumentException("unsafe column identifier '" + v + "' for " + key);
        return v;
    }

    private static LocalDateTime instant(Map<String, Object> block, String key) {
        String v = str(block.get(key));
        if (v == null) throw new IllegalArgumentException("value measure needs '" + key + "' (the window is required)");
        try {
            return v.length() == 10 ? LocalDate.parse(v).atStartOfDay() : LocalDateTime.parse(v.replace(' ', 'T'));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'" + key + "' must be an ISO date or date-time, got '" + v + "'");
        }
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String num(double d) {
        return Double.toString(d);
    }

    /** Numbers print as JS does (no trailing {@code .0}), as {@code PatternRoutes.label} does. */
    private static String js(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
    }

    private static String q(String ident) {
        return SqlIdent.q(ident);
    }
}
