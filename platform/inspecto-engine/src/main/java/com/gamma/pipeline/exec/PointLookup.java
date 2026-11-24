package com.gamma.pipeline.exec;

import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.util.DuckDbUtil;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static com.gamma.util.SqlIdent.q;

/**
 * The point engine of a keyed {@code transform.join} (LOOKUP-1 P3): the Reference held in memory, answering one value
 * at a time the way the batch join answers each row. Built from the same node config and the same Reference SQL as
 * {@link RowShaper}'s keyed join (key text normalized in SQL, {@code filter}, {@code duplicates} + {@code order_by},
 * {@code exact_when}), then probed in Java: a hash map for {@code match: exact}, the key lengths present longest first
 * for {@code longest_prefix}. {@code range} and {@code asof} are not served yet and are refused by name.
 *
 * <p>Every returned value is held as text ({@code CAST … AS VARCHAR}), the form a test answer shows. The Reference is
 * read once, through a connection sealed exactly as a dry run's ({@link PipelineDryRun#seal}); more than
 * {@link #maxRows()} Reference rows refuses the build rather than fill the heap.
 */
public final class PointLookup {

    /** System property capping the Reference rows one point index may hold. */
    public static final String PROP_MAX_ROWS = "lookup.point.max_rows";
    static final long DEFAULT_MAX_ROWS = 1_000_000L;

    /** What one mapping of the join adds for a value: output column name to text, defaults applied. */
    public record MappingAnswer(String on, Map<String, String> columns) {}

    /**
     * The answer for one value: the value as compared (after {@code normalize:}), whether a Reference row matched, the
     * key that matched and how ({@code exact} | {@code prefix} | {@code none}), and what each mapping adds.
     */
    public record Answer(String compared, boolean hit, String matchedKey, String matchKind, List<MappingAnswer> mappings) {}

    private final String match;
    private final List<String> normalizers;
    private final List<RowShaper.JoinMapping> mappings;
    private final List<String> returned;
    private final Map<String, String[]> byKey;
    private final Map<String, Boolean> exactOnly;
    private final int[] lengths;
    private final long rows;
    private final Instant loadedAt;
    private final long buildMillis;

    private PointLookup(String match, List<String> normalizers, List<RowShaper.JoinMapping> mappings, List<String> returned,
                        Map<String, String[]> byKey, Map<String, Boolean> exactOnly, long buildMillis) {
        this.match = match;
        this.normalizers = normalizers;
        this.mappings = mappings;
        this.returned = returned;
        this.byKey = byKey;
        this.exactOnly = exactOnly;
        TreeSet<Integer> lens = new TreeSet<>();
        for (String k : byKey.keySet()) lens.add(k.codePointCount(0, k.length()));
        this.lengths = lens.descendingSet().stream().mapToInt(Integer::intValue).toArray();
        this.rows = byKey.size();
        this.loadedAt = Instant.now();
        this.buildMillis = buildMillis;
    }

    /** The row cap, from {@link #PROP_MAX_ROWS} (default one million). */
    public static long maxRows() {
        String v = System.getProperty(PROP_MAX_ROWS);
        if (v == null || v.isBlank()) return DEFAULT_MAX_ROWS;
        try {
            return Math.max(1, Long.parseLong(v.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_ROWS;
        }
    }

    public long rows() { return rows; }
    public Instant loadedAt() { return loadedAt; }
    public long buildMillis() { return buildMillis; }
    public String match() { return match; }

    /**
     * Build the index of keyed join {@code node}, reading its Reference through {@code references} on a fresh sealed
     * scratch connection that is removed afterwards.
     *
     * @throws IllegalArgumentException the node is not a keyed exact / longest-prefix join, its config is refused as
     *                                  the batch join would refuse it, or the Reference is over the row cap
     */
    public static PointLookup load(PipelineNode node, RowShaper.ReferenceResolver references) throws Exception {
        long t0 = System.nanoTime();
        String id = node.id();
        String match = RowShaper.strOrNull(node, "match");
        if (match == null) match = RowShaper.JOIN_MATCH_EXACT;
        boolean prefix = RowShaper.JOIN_MATCH_LONGEST_PREFIX.equals(match);
        if (!prefix && !RowShaper.JOIN_MATCH_EXACT.equals(match))
            throw new IllegalArgumentException("transform.join node '" + id + "': testing a value covers match: exact "
                    + "and longest_prefix so far, not " + match);
        String keyCol = RowShaper.strOrNull(node, "key");
        if (keyCol == null)
            throw new IllegalArgumentException("transform.join node '" + id + "' is not a keyed lookup (no key:)");
        String reference = RowShaper.strOrNull(node, "reference");
        if (reference == null)
            throw new IllegalArgumentException("transform.join node '" + id + "' needs a 'reference'");
        List<String> normalizers;
        try {
            normalizers = RowShaper.joinNormalizers(node.cfg("normalize"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("transform.join node '" + id + "': " + e.getMessage());
        }
        String exactWhen = RowShaper.strOrNull(node, "exact_when");
        if (exactWhen != null && !prefix)
            throw new IllegalArgumentException("transform.join node '" + id + "': exact_when only applies to "
                    + "match: longest_prefix");
        String filter = RowShaper.strOrNull(node, "filter");
        String dups = RowShaper.strOrNull(node, "duplicates");
        if (dups == null) dups = "refuse";
        if (!RowShaper.JOIN_DUPLICATES.contains(dups))
            throw new IllegalArgumentException("transform.join node '" + id + "' has duplicates '" + dups
                    + "' — use one of " + RowShaper.JOIN_DUPLICATES);
        String orderBy = RowShaper.strOrNull(node, "order_by");
        if (!"refuse".equals(dups) && orderBy == null)
            throw new IllegalArgumentException("transform.join node '" + id + "': duplicates: " + dups
                    + " needs order_by: — which row of a repeated key is the " + dups);

        PipelineGraph one = new PipelineGraph(id, false, List.of(node), List.of());
        Path scratch = Files.createTempDirectory("lookup_");
        try {
            File db = DuckDbUtil.tempDbFile("lookup_", scratch);
            try (Connection conn = DuckDbUtil.openConnection(db)) {
                PipelineDryRun.seal(conn, one, references, scratch);
                String ref = references.resolve(conn, reference);
                List<String> refCols = RowShaper.columnsOf(conn, ref);
                if (refCols.stream().noneMatch(r -> r.equalsIgnoreCase(keyCol)))
                    throw new IllegalArgumentException("transform.join node '" + id + "': the Reference has no column '"
                            + keyCol + "' (it has " + refCols + ")");
                // no input rows here: the rows' own column names are not checked, only the Reference's
                List<RowShaper.JoinMapping> mappings = RowShaper.joinMappings(node, RowShaper.joinOn(node), refCols, List.of());
                List<String> returned = new ArrayList<>();
                for (RowShaper.JoinMapping m : mappings)
                    for (String c : m.columns().keySet())
                        if (returned.stream().noneMatch(r -> r.equalsIgnoreCase(c))) returned.add(c);

                // the batch join's Reference rows, verbatim: key normalized as text, filter, the duplicate pick
                String key = q(keyCol);
                String refKey = RowShaper.normalized("CAST(t." + key + " AS VARCHAR)", normalizers);
                StringBuilder sql = new StringBuilder("SELECT ").append(refKey).append(" AS __lk_key");
                sql.append(", ").append(exactWhen == null ? "false" : "coalesce((" + exactWhen + "), false)")
                        .append(" AS __lk_exact");
                for (String c : returned) sql.append(", CAST(t.").append(q(c)).append(" AS VARCHAR)");
                sql.append(" FROM ").append(q(ref)).append(" t WHERE t.").append(key).append(" IS NOT NULL")
                        .append(filter == null ? "" : " AND (" + filter + ")");
                if (!"refuse".equals(dups))
                    sql.append(" QUALIFY row_number() OVER (PARTITION BY ").append(refKey).append(" ORDER BY (")
                            .append(orderBy).append(") ").append("last".equals(dups) ? "DESC" : "ASC")
                            .append(" NULLS LAST, CAST(t AS VARCHAR)) = 1");

                long cap = maxRows();
                Map<String, String[]> byKey = new HashMap<>();
                Map<String, Boolean> exactOnly = new HashMap<>();
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql.toString())) {
                    while (rs.next()) {
                        String k = rs.getString(1);
                        if (byKey.size() >= cap)
                            throw new IllegalArgumentException("transform.join node '" + id + "': the Reference has more "
                                    + "than " + cap + " rows, the point engine's cap (" + PROP_MAX_ROWS + ")");
                        String[] vals = new String[returned.size()];
                        for (int i = 0; i < vals.length; i++) vals[i] = rs.getString(3 + i);
                        if (byKey.put(k, vals) != null)
                            throw new IllegalArgumentException("transform.join node '" + id + "': the Reference has more "
                                    + "than one row for " + keyCol + "=" + k + " — clean the Reference, or set "
                                    + "duplicates: first|last with order_by:");
                        if (rs.getBoolean(2)) exactOnly.put(k, true);
                    }
                }
                return new PointLookup(match, normalizers, mappings, returned, byKey, exactOnly,
                        (System.nanoTime() - t0) / 1_000_000);
            }
        } finally {
            deleteScratch(scratch);   // PipelineDryRun.removeScratch only removes dryrun_* / flowjob_* dirs
        }
    }

    /** Look up one value; {@code null} (no value) matches nothing, as a NULL key does in the batch join. */
    public Answer test(String value) {
        String v = value == null ? null : normalize(value, normalizers);
        String[] row = null;
        String matched = null;
        String kind = "none";
        if (v != null) {
            if (RowShaper.JOIN_MATCH_LONGEST_PREFIX.equals(match)) {
                int len = v.codePointCount(0, v.length());
                for (int n : lengths) {
                    if (n > len) continue;
                    String k = v.substring(0, v.offsetByCodePoints(0, n));
                    String[] r = byKey.get(k);
                    if (r == null || (exactOnly.containsKey(k) && n != len)) continue;
                    row = r;
                    matched = k;
                    kind = n == len ? "exact" : "prefix";
                    break;
                }
            } else if ((row = byKey.get(v)) != null) {
                matched = v;
                kind = "exact";
            }
        }
        List<MappingAnswer> out = new ArrayList<>();
        for (RowShaper.JoinMapping m : mappings) {
            Map<String, String> cols = new LinkedHashMap<>();
            for (Map.Entry<String, String> c : m.columns().entrySet()) {
                String val = row == null ? null : row[indexOf(c.getKey())];
                if (val == null)
                    val = m.defaults().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(c.getValue()))
                            .map(Map.Entry::getValue).findFirst().orElse(null);
                cols.put(c.getValue(), val);
            }
            if (m.matchedKeyAs() != null) cols.put(m.matchedKeyAs(), matched);
            if (m.matchKindAs() != null) cols.put(m.matchKindAs(), kind);
            out.add(new MappingAnswer(m.on(), cols));
        }
        return new Answer(v, row != null, matched, kind, out);
    }

    private int indexOf(String col) {
        for (int i = 0; i < returned.size(); i++) if (returned.get(i).equalsIgnoreCase(col)) return i;
        throw new IllegalStateException("no returned column " + col);
    }

    /**
     * The value through {@link RowShaper#normalized}'s own SQL, evaluated by DuckDB — never a Java copy of it: DuckDB's
     * {@code trim}, {@code lower} and {@code upper} differ from Java's on Unicode (no-break spaces, dotted I, final
     * sigma, ß), and the point engine must compare exactly as the batch join does. A bare in-memory connection running
     * one parameterized, config-built expression; the value is a bound parameter, never SQL text.
     */
    static String normalize(String v, List<String> normalizers) {
        if (normalizers.isEmpty()) return v;
        try (Connection c = java.sql.DriverManager.getConnection("jdbc:duckdb:");
             java.sql.PreparedStatement ps = c.prepareStatement(
                     // the expression repeats its input (strip_leading_zeros twice), so bind the value once
                     "SELECT " + RowShaper.normalized("__lk_v", normalizers)
                             + " FROM (SELECT CAST(? AS VARCHAR) AS __lk_v)")) {
            ps.setString(1, v);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not normalize the value: " + e.getMessage(), e);
        }
    }

    /** Remove the scratch dir and everything in it (the build's temporary DuckDB file). */
    private static void deleteScratch(Path scratch) {
        try (java.util.stream.Stream<Path> walk = Files.walk(scratch)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (java.io.IOException ignored) {
            // best effort: a leftover temp dir is not a failed lookup
        }
    }
}
