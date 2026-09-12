package com.gamma.query;

import com.gamma.sql.SqlSandbox;
import com.gamma.sql.SqlSandboxPolicy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Dataset reconciliation execution (DAT-7; design {@code docs/superpower/reconciliation-board-design.md}).
 * Compares two Datasets at the <b>key-column grain</b> entirely inside an ephemeral DuckDB sandbox — each
 * side's trusted {@link DatasetRelation} SQL is registered as a view, then <b>server-built</b> SQL (never
 * caller-authored — there is no {@code SqlGuard} surface here) computes:
 * <ul>
 *   <li>{@link #run} — the Board's grain rows: one {@code FULL OUTER JOIN} of the two grouped sides
 *       (NULL key values match via {@code IS NOT DISTINCT FROM}, mirroring the UI engine's
 *       {@code keyOf(null) == ''} rule), plus exact per-side totals and a one-pass Break summary that
 *       stays exact even when the grain rows truncate;</li>
 *   <li>{@link #breaks} — the three Break sets at the same grain ({@code missing_right} = anchor-only,
 *       {@code missing_left} = other-only, {@code value_break} = matched outside tolerance), paged and
 *       optionally scoped to a Board dimension path.</li>
 * </ul>
 * Tolerance semantics are the SQL port of the UI's {@code withinTolerance} (reconciliation-types.ts,
 * locked 2026-07-03): {@code exact} | {@code absolute} | {@code percent} — percent is left-relative and a
 * zero left side requires exact equality. Row-level Break lifecycle (auto-close, preserved resolutions)
 * stays client-side per the C9 review-sheet contract; this service is stateless compute.
 */
public final class ReconService {

    private ReconService() {}

    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** Wire name of the implicit COUNT(*) measure (also the internal presence marker — never NULL for a group). */
    public static final String RECORDS = "__records";

    // ── spec ────────────────────────────────────────────────────────────────────────

    /**
     * One side of the reconciliation. {@code relationSql} is the trusted server-built relation
     * ({@link DatasetRelation}); {@code columnMap} maps unified column name → this side's physical column
     * (absent = same name); {@code filter} is a raw row-level predicate over the side's physical columns,
     * already {@link ExpressionGuard}-checked by {@link Spec#of}.
     */
    public record Side(String datasetId, String relationSql, Map<String, String> columnMap, String filter) {}

    /** One compared Measure: {@code agg} = sum|count; tolerance is the record-grain break truth (UI parity). */
    public record Measure(String name, String agg, String toleranceType, double tolerance) {}

    /**
     * How many rows per key each side may contribute — an <b>assertion</b>, not a matching strategy.
     *
     * <p>🔴 Read this before assuming it changes the join: it does not, and it must not. Each side is
     * pre-aggregated to one row per key ({@link #sideSql}), so for the canonical case — one invoice against
     * three payments — summing the payments and comparing to the invoice is <b>already the right
     * arithmetic</b>. What was missing is that a <em>duplicated</em> row was indistinguishable from a
     * genuinely larger value, so it reconciled clean. This declares the expected shape so that becomes a
     * Break instead.
     *
     * <p>⛔ {@link #MANY_TO_MANY} is the default and asserts NOTHING — anything else would change the
     * verdict of every reconciliation that predates this option. Design:
     * {@code docs/superpower/recon-cardinality-plan.md}.
     */
    public enum Cardinality {
        /** Exactly one row per key on both sides; more on either is a break. */
        ONE_TO_ONE,
        /** One row per key on the anchor, any number on the compared side. */
        ONE_TO_MANY,
        /** Any number on the anchor, one row per key on the compared side. */
        MANY_TO_ONE,
        /** No assertion — the shipped behaviour before this option existed, and the default. */
        MANY_TO_MANY;

        /** Parse a config value; {@code null}/blank ⇒ {@link #MANY_TO_MANY}. Throws on an unknown word (→ 422). */
        public static Cardinality fromConfig(String v) {
            if (v == null || v.isBlank()) return MANY_TO_MANY;
            for (Cardinality c : values())
                if (c.name().equalsIgnoreCase(v.trim())) return c;
            throw new IllegalArgumentException("cardinality must be one_to_one|one_to_many|many_to_one|many_to_many, got '"
                    + v + "'");
        }

        /** Lower-case wire spelling, as authored in config. */
        public String wire() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    /** A validated reconciliation spec — construct via {@link #of}. Side 0 is the anchor ("a"). */
    public record Spec(List<Side> sides, List<String> keyColumns, List<Measure> measures,
                       boolean includeRecordCount, Cardinality cardinality) {

        /** As {@link #of(List, List, List, boolean, Cardinality)} asserting nothing about cardinality. */
        public static Spec of(List<Side> sides, List<String> keyColumns, List<Measure> measures,
                              boolean includeRecordCount) {
            return of(sides, keyColumns, measures, includeRecordCount, Cardinality.MANY_TO_MANY);
        }

        /** Validate + normalize; throws {@link IllegalArgumentException} on anything unusable (→ 422). */
        public static Spec of(List<Side> sides, List<String> keyColumns, List<Measure> measures,
                              boolean includeRecordCount, Cardinality cardinality) {
            if (sides == null || sides.size() < 2 || sides.size() > 3)
                throw new IllegalArgumentException("expected 2 or 3 datasets (side 0 is the anchor), got "
                        + (sides == null ? 0 : sides.size()));
            if (keyColumns == null || keyColumns.isEmpty())
                throw new IllegalArgumentException("at least one key column is required");
            for (String k : keyColumns) safeIdent(k, "key column");
            List<Measure> ms = measures == null ? List.of() : measures;
            for (Measure m : ms) {
                safeIdent(m.name(), "compare column");
                if (RECORDS.equals(m.name()))
                    throw new IllegalArgumentException("'" + RECORDS + "' is reserved for the implicit record count");
                if (keyColumns.contains(m.name()))
                    throw new IllegalArgumentException("column '" + m.name() + "' cannot be both a key and a compare column");
                if (!"sum".equals(m.agg()) && !"count".equals(m.agg()))
                    throw new IllegalArgumentException("compare column '" + m.name() + "': agg must be sum|count, got '" + m.agg() + "'");
                if (!"exact".equals(m.toleranceType()) && !"absolute".equals(m.toleranceType())
                        && !"percent".equals(m.toleranceType()))
                    throw new IllegalArgumentException("compare column '" + m.name()
                            + "': toleranceType must be exact|absolute|percent, got '" + m.toleranceType() + "'");
                if (!(m.tolerance() >= 0))
                    throw new IllegalArgumentException("compare column '" + m.name() + "': tolerance must be >= 0");
            }
            if (ms.isEmpty() && !includeRecordCount)
                throw new IllegalArgumentException("nothing to compare: no compare columns and record count disabled");
            for (Side s : sides) {
                if (s.relationSql() == null || s.relationSql().isBlank())
                    throw new IllegalArgumentException("side '" + s.datasetId() + "' has no relation SQL");
                if (s.columnMap() != null)
                    for (Map.Entry<String, String> e : s.columnMap().entrySet()) {
                        safeIdent(e.getKey(), "columnMap name");
                        safeIdent(e.getValue(), "columnMap target");
                    }
                if (s.filter() != null) ExpressionGuard.check(s.filter());
            }
            return new Spec(List.copyOf(sides), List.copyOf(keyColumns), List.copyOf(ms), includeRecordCount,
                    cardinality == null ? Cardinality.MANY_TO_MANY : cardinality);
        }

        /** This side's physical column for a unified column name. */
        String physical(int side, String unified) {
            Map<String, String> map = sides.get(side).columnMap();
            return map == null ? unified : map.getOrDefault(unified, unified);
        }
    }

    // ── results ─────────────────────────────────────────────────────────────────────

    /**
     * The Board payload: grain rows shaped {@code {key:{dim:v}, a:{measure:v}, b:{…}, inA, inB}}, exact
     * per-side {@code totals} ({@code {a:{…}, b:{…}}}), and the exact Break {@code summary}
     * ({@code {groups, matchedKeys, byType:{missing_left, missing_right, value_break}}}; {@code byType}
     * also carries {@code cardinality_break} when, and only when, the spec declares a
     * {@link Cardinality}).
     */
    public record RunResult(List<Map<String, Object>> rows, Map<String, Object> totals,
                            Map<String, Object> summary, boolean truncated, long elapsedMs) {}

    /** One paged Break set: rows shaped like {@link RunResult} rows (single side for the missing sets). */
    public record BreakSet(List<Map<String, Object>> rows, int rowCount, boolean truncated) {}

    // ── execution ───────────────────────────────────────────────────────────────────

    /** Views the sides register as — index-stable so every builder can reference them. Side 0 = anchor. */
    private static final String[] VIEWS = {"__recon_a", "__recon_b", "__recon_c"};
    private static final String[] WIRE_SIDES = {"a", "b", "c"};

    public static RunResult run(Spec spec, int limit) throws SQLException, IOException {
        long t0 = System.nanoTime();
        int n = spec.sides().size();
        try (SqlSandbox sandbox = SqlSandbox.open(SqlSandboxPolicy.defaultPolicy())) {
            Connection conn = registerSides(sandbox, spec);

            List<Map<String, Object>> grain = select(conn, grainSql(spec, limit));
            boolean truncated = grain.size() > limit;
            if (truncated) grain = grain.subList(0, limit);

            List<Map<String, Object>> rows = new ArrayList<>(grain.size());
            for (Map<String, Object> r : grain) rows.add(shapeRow(spec, r));

            Map<String, Object> totals = new LinkedHashMap<>();
            for (int i = 0; i < n; i++)
                totals.put(WIRE_SIDES[i], measuresOf(spec, select(conn, totalsSql(spec, i)).get(0), null));

            // Anchor-relative pair summaries (design §6): one per non-anchor side. The legacy top-level
            // fields mirror the first pair (A↔B) so 2-way consumers are unchanged.
            List<Map<String, Object>> pairs = new ArrayList<>();
            for (int other = 1; other < n; other++) {
                Map<String, Object> s = select(conn, pairSummarySql(spec, other)).get(0);
                Map<String, Object> byType = new LinkedHashMap<>();
                byType.put("missing_left", s.get("missing_left"));
                byType.put("missing_right", s.get("missing_right"));
                byType.put("value_break", s.get("value_break"));
                if (spec.cardinality() != Cardinality.MANY_TO_MANY)
                    byType.put("cardinality_break", s.get("cardinality_break"));
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("side", WIRE_SIDES[other]);
                pair.put("matchedKeys", s.get("matched"));
                pair.put("byType", byType);
                pairs.add(pair);
            }
            Map<String, Object> groupsRow = select(conn, groupsSql(spec)).get(0);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("groups", groupsRow.get("groups"));
            summary.put("matchedKeys", pairs.get(0).get("matchedKeys"));
            summary.put("byType", pairs.get(0).get("byType"));
            summary.put("pairs", pairs);

            return new RunResult(rows, totals, summary, truncated, (System.nanoTime() - t0) / 1_000_000);
        }
    }

    /**
     * The Break sets at the recon grain for one anchor-relative pair, optionally scoped to a Board
     * dimension {@code path} (unified key column → value, compared as VARCHAR). {@code type} filters to
     * one set, or {@code null} returns all three. {@code other} picks the compared side (1 = "b",
     * 2 = "c" in a 3-way spec); rows always carry the anchor as {@code a} and the compared side as
     * {@code b} (roles, not identities — the route echoes the side).
     */
    public static Map<String, BreakSet> breaks(Spec spec, Map<String, String> path, String type,
                                               int other, int limit, int offset) throws SQLException, IOException {
        if (path != null)
            for (String dim : path.keySet())
                if (!spec.keyColumns().contains(dim))
                    throw new IllegalArgumentException("path column '" + dim + "' is not a key column");
        if (type != null && !type.equals("missing_left") && !type.equals("missing_right")
                && !type.equals("value_break") && !type.equals("cardinality_break"))
            throw new IllegalArgumentException(
                    "type must be missing_left|missing_right|value_break|cardinality_break, got '" + type + "'");
        if (other < 1 || other >= spec.sides().size())
            throw new IllegalArgumentException("side '" + (other >= 0 && other < WIRE_SIDES.length ? WIRE_SIDES[other] : other)
                    + "' is not a compared side of this reconciliation");

        try (SqlSandbox sandbox = SqlSandbox.open(SqlSandboxPolicy.defaultPolicy())) {
            Connection conn = registerSides(sandbox, spec);
            Map<String, BreakSet> out = new LinkedHashMap<>();
            if (type == null || type.equals("missing_right"))
                out.put("missing_right", breakSet(conn, spec, missingSql(spec, other, true, path, limit, offset), 'a', limit, false));
            if (type == null || type.equals("missing_left"))
                out.put("missing_left", breakSet(conn, spec, missingSql(spec, other, false, path, limit, offset), 'b', limit, false));
            if (type == null || type.equals("value_break"))
                out.put("value_break", spec.measures().isEmpty()
                        ? new BreakSet(List.of(), 0, false)
                        : breakSet(conn, spec, valueBreaksSql(spec, other, path, limit, offset), 'x', limit, false));
            // ⚠ MANY_TO_MANY asserts nothing, so it can never produce a row. Two consequences, both
            // deliberate: never build SQL whose WHERE is vacuously false, and — when no type was asked for
            // — do not add the key at all, so a reconciliation that predates this option gets a payload
            // byte-identical to before. An explicit ask still gets an explicit (empty) answer.
            boolean asserted = spec.cardinality() != Cardinality.MANY_TO_MANY;
            if (type == null ? asserted : type.equals("cardinality_break"))
                out.put("cardinality_break", asserted
                        ? breakSet(conn, spec, cardinalityBreaksSql(spec, other, path, limit, offset), 'x', limit, true)
                        : new BreakSet(List.of(), 0, false));
            return out;
        }
    }

    /** Per-side column inventory ({@code SELECT * … LIMIT 0} metadata) + cross-side auto-matches. */
    public static Map<String, Object> columns(List<Side> sides) throws SQLException, IOException {
        try (SqlSandbox sandbox = SqlSandbox.open(SqlSandboxPolicy.defaultPolicy())) {
            Connection conn = sandbox.connection();
            List<Map<String, Object>> perSide = new ArrayList<>();
            List<Map<String, ColMeta>> metaBySide = new ArrayList<>();
            for (Side side : sides) {
                Map<String, ColMeta> meta = new LinkedHashMap<>();
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT * FROM (" + side.relationSql() + ") AS __r LIMIT 0")) {
                    ResultSetMetaData md = rs.getMetaData();
                    for (int c = 1; c <= md.getColumnCount(); c++)
                        meta.put(md.getColumnLabel(c),
                                new ColMeta(md.getColumnLabel(c), md.getColumnTypeName(c), numeric(md.getColumnType(c))));
                }
                List<Map<String, Object>> cols = new ArrayList<>();
                for (ColMeta m : meta.values()) cols.add(m.wire());
                Map<String, Object> ds = new LinkedHashMap<>();
                ds.put("dataset", side.datasetId());
                ds.put("columns", cols);
                perSide.add(ds);
                metaBySide.add(meta);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("datasets", perSide);
            out.put("matches", matches(sides, metaBySide));
            return out;
        }
    }

    private record ColMeta(String name, String type, boolean numeric) {
        Map<String, Object> wire() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("type", type);
            m.put("numeric", numeric);
            return m;
        }
    }

    /** Columns whose normalized name exists on every side → suggested unified bindings. */
    private static List<Map<String, Object>> matches(List<Side> sides, List<Map<String, ColMeta>> metaBySide) {
        List<Map<String, Object>> matches = new ArrayList<>();
        for (ColMeta first : metaBySide.get(0).values()) {
            String norm = normalize(first.name());
            Map<String, String> byDataset = new LinkedHashMap<>();
            byDataset.put(sides.get(0).datasetId(), first.name());
            boolean everywhere = true;
            boolean numeric = first.numeric();
            for (int i = 1; i < metaBySide.size(); i++) {
                ColMeta hit = null;
                for (ColMeta m : metaBySide.get(i).values())
                    if (normalize(m.name()).equals(norm)) { hit = m; break; }
                if (hit == null) { everywhere = false; break; }
                byDataset.put(sides.get(i).datasetId(), hit.name());
                numeric &= hit.numeric();
            }
            if (!everywhere) continue;
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("name", first.name());
            match.put("numeric", numeric);
            match.put("columns", byDataset);
            matches.add(match);
        }
        return matches;
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static boolean numeric(int sqlType) {
        return switch (sqlType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.FLOAT, Types.REAL,
                 Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> true;
            default -> false;
        };
    }

    // ── SQL builders (package-private for unit tests; all identifiers pre-validated) ──

    /** One side's grouped CTE body: unified keys aliased k0…, measures m0…, COUNT(*) as mr (presence marker). */
    static String sideSql(Spec spec, int side) {
        StringBuilder sb = new StringBuilder("SELECT ");
        List<String> keys = spec.keyColumns();
        for (int i = 0; i < keys.size(); i++)
            sb.append(q(spec.physical(side, keys.get(i)))).append(" AS ").append(q("k" + i)).append(", ");
        for (int i = 0; i < spec.measures().size(); i++)
            sb.append(aggExpr(spec, side, i)).append(", ");
        sb.append("COUNT(*) AS ").append(q("mr"))
          .append(" FROM ").append(VIEWS[side]).append(whereFilter(spec, side))
          .append(" GROUP BY ");
        for (int i = 0; i < keys.size(); i++) sb.append(i > 0 ? ", " : "").append(i + 1);
        return sb.toString();
    }

    /** The aggregation term for measure {@code i} on a side: {@code SUM("phys") AS "m<i>"} (or {@code COUNT}). */
    private static String aggExpr(Spec spec, int side, int i) {
        Measure m = spec.measures().get(i);
        return ("count".equals(m.agg()) ? "COUNT(" : "SUM(")
                + q(spec.physical(side, m.name())) + ") AS " + q("m" + i);
    }

    /** The optional {@code WHERE (<ExpressionGuard-checked filter>)} clause for a side, or empty. */
    private static String whereFilter(Spec spec, int side) {
        String filter = spec.sides().get(side).filter();
        return filter == null ? "" : " WHERE (" + ExpressionGuard.check(filter) + ")";
    }

    /** The Board grain query: FULL OUTER JOIN chain of the grouped sides on NULL-safe key equality. */
    static String grainSql(Spec spec, int limit) {
        int n = spec.sides().size();
        StringBuilder sb = new StringBuilder(with(spec)).append("SELECT ");
        for (int i = 0; i < spec.keyColumns().size(); i++)
            sb.append(coalesceKey(spec, i, n)).append(" AS ").append(q("k" + i)).append(", ");
        for (int s = 0; s < n; s++) {
            for (int i = 0; i < spec.measures().size(); i++)
                sb.append("__s").append(s).append('.').append(q("m" + i)).append(" AS ").append(q("s" + s + "_m" + i)).append(", ");
            sb.append("__s").append(s).append('.').append(q("mr")).append(" AS ").append(q("s" + s + "_mr"))
              .append(s < n - 1 ? ", " : "");
        }
        sb.append(" FROM ").append(joinChain(spec, n))
          .append(" ORDER BY ").append(orderByKeys(spec, "")).append(" LIMIT ").append(Math.max(0, limit) + 1);
        return sb.toString();
    }

    /** `COALESCE(__s0."ki", …, __s{n-1}."ki")` — the unified key column across all joined sides. */
    private static String coalesceKey(Spec spec, int keyIdx, int n) {
        List<String> parts = new ArrayList<>();
        for (int s = 0; s < n; s++) parts.add("__s" + s + "." + q("k" + keyIdx));
        return n == 1 ? parts.get(0) : "COALESCE(" + String.join(", ", parts) + ")";
    }

    /** The n-side FULL OUTER JOIN chain: each further side matches the coalesced keys of the sides before it. */
    private static String joinChain(Spec spec, int n) {
        StringBuilder sb = new StringBuilder("__s0");
        for (int s = 1; s < n; s++) {
            List<String> terms = new ArrayList<>();
            for (int i = 0; i < spec.keyColumns().size(); i++)
                terms.add(coalesceKey(spec, i, s) + " IS NOT DISTINCT FROM __s" + s + "." + q("k" + i));
            sb.append(" FULL OUTER JOIN __s").append(s).append(" ON ").append(String.join(" AND ", terms));
        }
        return sb.toString();
    }

    /** Exact distinct key-group count across ALL sides (immune to the grain LIMIT). */
    static String groupsSql(Spec spec) {
        return with(spec) + "SELECT COUNT(*) AS \"groups\" FROM " + joinChain(spec, spec.sides().size());
    }

    /** One side's exact totals (no GROUP BY — immune to grain truncation). */
    static String totalsSql(Spec spec, int side) {
        StringBuilder sb = new StringBuilder("SELECT ");
        for (int i = 0; i < spec.measures().size(); i++)
            sb.append(aggExpr(spec, side, i)).append(", ");
        sb.append("COUNT(*) AS ").append(q("mr")).append(" FROM ").append(VIEWS[side]).append(whereFilter(spec, side));
        return sb.toString();
    }

    /** One-pass exact Break summary for the anchor↔{@code other} pair (value_break counts (key × column) entries). */
    static String pairSummarySql(Spec spec, int other) {
        String o = "__s" + other;
        StringBuilder sb = new StringBuilder(with(spec))
                .append("SELECT ")
                .append("COUNT(*) FILTER (WHERE __s0.\"mr\" IS NOT NULL AND ").append(o).append(".\"mr\" IS NULL) AS \"missing_right\", ")
                .append("COUNT(*) FILTER (WHERE ").append(o).append(".\"mr\" IS NOT NULL AND __s0.\"mr\" IS NULL) AS \"missing_left\", ")
                .append("COUNT(*) FILTER (WHERE __s0.\"mr\" IS NOT NULL AND ").append(o).append(".\"mr\" IS NOT NULL) AS \"matched\", ");
        if (spec.measures().isEmpty()) {
            sb.append("0 AS \"value_break\"");
        } else {
            List<String> terms = IntStream.range(0, spec.measures().size())
                    .mapToObj(i -> "COALESCE(SUM(CASE WHEN __s0.\"mr\" IS NOT NULL AND " + o + ".\"mr\" IS NOT NULL AND NOT "
                            + within("__s0." + q("m" + i), o + "." + q("m" + i), spec.measures().get(i))
                            + " THEN 1 ELSE 0 END), 0)")
                    .toList();
            sb.append('(').append(String.join(" + ", terms)).append(") AS \"value_break\"");
        }
        // ⚠ Only when an assertion is declared — the column is absent otherwise, so a reconciliation that
        // predates this option gets the same summary row it always got. Counts KEYS, unlike value_break,
        // which counts (key × column): a key has one cardinality, not one per compare column.
        if (spec.cardinality() != Cardinality.MANY_TO_MANY)
            sb.append(", COUNT(*) FILTER (WHERE __s0.\"mr\" IS NOT NULL AND ").append(o)
              .append(".\"mr\" IS NOT NULL AND (").append(cardinalityViolation(spec, o))
              .append(")) AS \"cardinality_break\"");
        sb.append(" FROM __s0 FULL OUTER JOIN ").append(o).append(" ON ").append(keyJoin(spec, 0, other));
        return sb.toString();
    }

    /**
     * Keys on one side of the anchor↔{@code other} pair and absent on the other.
     * {@code anchorOnly} true → missing_right (anchor has, other doesn't; rows aliased {@code sa_*});
     * false → missing_left (other has, anchor doesn't; rows aliased {@code sb_*}).
     */
    static String missingSql(Spec spec, int other, boolean anchorOnly, Map<String, String> path, int limit, int offset) {
        String present = anchorOnly ? "__s0" : "__s" + other;
        String absent = anchorOnly ? "__s" + other : "__s0";
        String prefix = anchorOnly ? "sa_" : "sb_";
        StringBuilder sb = new StringBuilder(with(spec)).append("SELECT ");
        for (int i = 0; i < spec.keyColumns().size(); i++)
            sb.append(present).append('.').append(q("k" + i)).append(", ");
        for (int i = 0; i < spec.measures().size(); i++)
            sb.append(present).append('.').append(q("m" + i)).append(" AS ").append(q(prefix + "m" + i)).append(", ");
        sb.append(present).append('.').append(q("mr")).append(" AS ").append(q(prefix + "mr"))
          .append(" FROM ").append(present).append(" LEFT JOIN ").append(absent).append(" ON ").append(keyJoin(spec, 0, other))
          .append(" WHERE ").append(absent).append(".\"mr\" IS NULL").append(pathPredicate(spec, path, present + "."))
          .append(" ORDER BY ").append(orderByKeys(spec, present + "."))
          .append(" LIMIT ").append(Math.max(0, limit) + 1).append(" OFFSET ").append(Math.max(0, offset));
        return sb.toString();
    }

    /**
     * Matched keys of the anchor↔{@code other} pair whose per-side row counts violate the declared
     * {@link Cardinality}.
     *
     * <p>🔴 <b>No new aggregation, and no change to the join.</b> {@code COUNT(*) AS mr} was already
     * computed per side per key by {@link #sideSql} and already carried through the join — it was simply
     * reduced to a presence boolean and discarded. This reads the number that was always there.
     *
     * <p>⚠ Scoped to <b>matched</b> keys (a JOIN, exactly like {@link #valueBreaksSql}) on purpose: a key
     * present on one side only is already reported as {@code missing_left}/{@code missing_right}, and
     * emitting a cardinality break for it too would double-report one fact.
     */
    static String cardinalityBreaksSql(Spec spec, int other, Map<String, String> path, int limit, int offset) {
        String o = "__s" + other;
        String violates = cardinalityViolation(spec, o);
        StringBuilder sb = new StringBuilder(with(spec)).append("SELECT ");
        for (int i = 0; i < spec.keyColumns().size(); i++)
            sb.append("__s0.").append(q("k" + i)).append(", ");
        for (int i = 0; i < spec.measures().size(); i++)
            sb.append("__s0.").append(q("m" + i)).append(" AS ").append(q("sa_m" + i)).append(", ")
              .append(o).append('.').append(q("m" + i)).append(" AS ").append(q("sb_m" + i)).append(", ");
        sb.append("__s0.").append(q("mr")).append(" AS ").append(q("sa_mr")).append(", ")
          .append(o).append('.').append(q("mr")).append(" AS ").append(q("sb_mr"))
          .append(" FROM __s0 JOIN ").append(o).append(" ON ").append(keyJoin(spec, 0, other))
          .append(" WHERE (").append(violates).append(')')
          .append(pathPredicate(spec, path, "__s0."))
          .append(" ORDER BY ").append(orderByKeys(spec, "__s0."))
          .append(" LIMIT ").append(Math.max(0, limit) + 1).append(" OFFSET ").append(Math.max(0, offset));
        return sb.toString();
    }

    /**
     * The predicate that makes a matched key violate the declared {@link Cardinality}.
     *
     * <p>⚠ Shared by {@link #cardinalityBreaksSql} and {@link #pairSummarySql} deliberately: a summary
     * that counted a different thing from the break list it summarises is worse than no summary.
     */
    private static String cardinalityViolation(Spec spec, String o) {
        String anchorMany = "__s0." + q("mr") + " > 1";
        String otherMany = o + "." + q("mr") + " > 1";
        return switch (spec.cardinality()) {
            case ONE_TO_ONE -> anchorMany + " OR " + otherMany;
            case ONE_TO_MANY -> anchorMany;     // the anchor side must contribute exactly one row
            case MANY_TO_ONE -> otherMany;      // the compared side must contribute exactly one row
            case MANY_TO_MANY -> throw new IllegalStateException(
                    "many_to_many asserts nothing and must be short-circuited before building SQL");
        };
    }

    /** Matched keys of the anchor↔{@code other} pair where any compare column is outside its tolerance. */
    static String valueBreaksSql(Spec spec, int other, Map<String, String> path, int limit, int offset) {
        String o = "__s" + other;
        StringBuilder sb = new StringBuilder(with(spec)).append("SELECT ");
        for (int i = 0; i < spec.keyColumns().size(); i++)
            sb.append("__s0.").append(q("k" + i)).append(", ");
        for (int i = 0; i < spec.measures().size(); i++)
            sb.append("__s0.").append(q("m" + i)).append(" AS ").append(q("sa_m" + i)).append(", ")
              .append(o).append('.').append(q("m" + i)).append(" AS ").append(q("sb_m" + i)).append(", ");
        sb.append("__s0.").append(q("mr")).append(" AS ").append(q("sa_mr")).append(", ")
          .append(o).append('.').append(q("mr")).append(" AS ").append(q("sb_mr"));
        List<String> broken = IntStream.range(0, spec.measures().size())
                .mapToObj(i -> "NOT " + within("__s0." + q("m" + i), o + "." + q("m" + i), spec.measures().get(i)))
                .toList();
        sb.append(" FROM __s0 JOIN ").append(o).append(" ON ").append(keyJoin(spec, 0, other))
          .append(" WHERE (").append(String.join(" OR ", broken)).append(')')
          .append(pathPredicate(spec, path, "__s0."))
          .append(" ORDER BY ").append(orderByKeys(spec, "__s0."))
          .append(" LIMIT ").append(Math.max(0, limit) + 1).append(" OFFSET ").append(Math.max(0, offset));
        return sb.toString();
    }

    /**
     * SQL port of the UI's {@code withinTolerance} over aggregated (numeric) values. NULL on one side only
     * is a mismatch; NULL on both matches. Percent is left(anchor)-relative; a zero anchor requires equality.
     */
    static String within(String a, String b, Measure m) {
        String tol = String.valueOf(m.tolerance());
        return switch (m.toleranceType()) {
            case "absolute" -> "(CASE WHEN " + a + " IS NULL OR " + b + " IS NULL THEN " + a + " IS NOT DISTINCT FROM " + b
                    + " ELSE ABS(" + a + " - " + b + ") <= " + tol + " END)";
            case "percent" -> "(CASE WHEN " + a + " IS NULL OR " + b + " IS NULL THEN " + a + " IS NOT DISTINCT FROM " + b
                    + " WHEN " + a + " = 0 THEN " + b + " = 0"
                    + " ELSE ABS(" + a + " - " + b + ") / ABS(" + a + ") * 100 <= " + tol + " END)";
            default -> "(" + a + " IS NOT DISTINCT FROM " + b + ")";
        };
    }

    private static String with(Spec spec) {
        List<String> ctes = new ArrayList<>();
        for (int i = 0; i < spec.sides().size(); i++) ctes.add("__s" + i + " AS (" + sideSql(spec, i) + ")");
        return "WITH " + String.join(", ", ctes) + " ";
    }

    /** NULL-safe key equality between two grouped sides (NULL dim values match each other). */
    private static String keyJoin(Spec spec, int left, int right) {
        return IntStream.range(0, spec.keyColumns().size())
                .mapToObj(i -> "__s" + left + "." + q("k" + i) + " IS NOT DISTINCT FROM __s" + right + "." + q("k" + i))
                .collect(Collectors.joining(" AND "));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    private static Connection registerSides(SqlSandbox sandbox, Spec spec) throws SQLException {
        Connection conn = sandbox.connection();
        for (int i = 0; i < spec.sides().size(); i++)
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE VIEW " + VIEWS[i] + " AS " + spec.sides().get(i).relationSql());
            }
        return conn;
    }

    /**
     * Shape a pair-scoped break query: {@code roles} 'a' = anchor only, 'b' = compared side only, 'x' = both.
     *
     * <p>{@code alwaysCounts} forces the per-side row count into the payload even when
     * {@code includeRecordCount} is off — for a cardinality break the count IS the evidence, so omitting it
     * would report a violation the reader cannot interpret.
     */
    private static BreakSet breakSet(Connection conn, Spec spec, String sql, char roles, int limit,
                                     boolean alwaysCounts)
            throws SQLException {
        List<Map<String, Object>> raw = select(conn, sql);
        boolean truncated = raw.size() > limit;
        if (truncated) raw = raw.subList(0, limit);
        List<Map<String, Object>> rows = new ArrayList<>(raw.size());
        for (Map<String, Object> r : raw) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", keysOf(spec, r));
            if (roles != 'b') row.put("a", withCount(spec, measuresOf(spec, r, "sa_"), r, "sa_", alwaysCounts));
            if (roles != 'a') row.put("b", withCount(spec, measuresOf(spec, r, "sb_"), r, "sb_", alwaysCounts));
            rows.add(row);
        }
        return new BreakSet(rows, rows.size(), truncated);
    }

    private static Map<String, Object> shapeRow(Spec spec, Map<String, Object> r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", keysOf(spec, r));
        for (int i = 0; i < spec.sides().size(); i++)
            row.put(WIRE_SIDES[i], measuresOf(spec, r, "s" + i + "_"));
        row.put("inA", r.get("s0_mr") != null);
        row.put("inB", r.get("s1_mr") != null);
        if (spec.sides().size() > 2) row.put("inC", r.get("s2_mr") != null);
        return row;
    }

    private static Map<String, Object> keysOf(Spec spec, Map<String, Object> r) {
        Map<String, Object> key = new LinkedHashMap<>();
        for (int i = 0; i < spec.keyColumns().size(); i++)
            key.put(spec.keyColumns().get(i), r.get("k" + i));
        return key;
    }

    /** Unified-named measure values from an internal-alias row; {@code prefix} null = totals row (no prefix). */
    private static Map<String, Object> measuresOf(Spec spec, Map<String, Object> r, String prefix) {
        String p = prefix == null ? "" : prefix;
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < spec.measures().size(); i++)
            m.put(spec.measures().get(i).name(), r.get(p + "m" + i));
        if (spec.includeRecordCount()) m.put(RECORDS, r.get(p + "mr"));
        return m;
    }

    /**
     * The side payload with its row count present when the caller needs it as evidence.
     *
     * <p>{@link #measuresOf} already emits {@link #RECORDS} when {@code includeRecordCount} is on, so this
     * only fills the gap when it is off — never overwriting a value already shaped there.
     */
    private static Map<String, Object> withCount(Spec spec, Map<String, Object> side, Map<String, Object> r,
                                                 String prefix, boolean alwaysCounts) {
        if (alwaysCounts && !spec.includeRecordCount()) side.put(RECORDS, r.get(prefix + "mr"));
        return side;
    }

    private static List<Map<String, Object>> select(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int c = 1; c <= n; c++) row.put(md.getColumnLabel(c), wire(rs.getObject(c)));
                rows.add(row);
            }
            return rows;
        }
    }

    /** ISO-8601-coerce temporals for the jsr310-free control-plane mapper (mirrors QueryExecutor). */
    private static Object wire(Object v) {
        return v instanceof java.time.temporal.Temporal ? v.toString() : v;
    }

    private static String pathPredicate(Spec spec, Map<String, String> path, String qualifier) {
        if (path == null || path.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : path.entrySet()) {
            int i = spec.keyColumns().indexOf(e.getKey());
            sb.append(" AND CAST(").append(qualifier).append(q("k" + i)).append(" AS VARCHAR) = ")
              .append(lit(e.getValue()));
        }
        return sb.toString();
    }

    /** Stable key ordering; {@code qualifier} is empty for output aliases or a CTE prefix like {@code "__s0."}. */
    private static String orderByKeys(Spec spec, String qualifier) {
        return IntStream.range(0, spec.keyColumns().size())
                .mapToObj(i -> qualifier + q("k" + i) + " NULLS LAST")
                .collect(Collectors.joining(", "));
    }

    private static void safeIdent(String s, String what) {
        if (s == null || !SAFE_IDENT.matcher(s).matches())
            throw new IllegalArgumentException(what + " must be a plain identifier, got '" + s + "'");
    }

    private static String q(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private static String lit(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
