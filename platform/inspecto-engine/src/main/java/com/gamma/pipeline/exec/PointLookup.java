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
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import static com.gamma.util.SqlIdent.q;

/**
 * The point engine of a keyed {@code transform.join} (LOOKUP-1 P3): the Reference held in memory, answering one value
 * at a time the way the batch join answers each row. Built from the same node config and the same Reference SQL as
 * {@link RowShaper}'s keyed join (key text normalized in SQL, {@code filter}, {@code duplicates} + {@code order_by},
 * {@code exact_when}), then probed in Java: a hash map for {@code match: exact}, the key lengths present longest first
 * for {@code longest_prefix}; for {@code range} every row is checked against the value (its pick order kept for overlaps)
 * and for {@code asof} the key's rows. A tested value is compared as the batch join compares it: cast in DuckDB only
 * where the batch casts (to DATE for a DATE range bound; to DATE or TIMESTAMP for such an as-of from-column), a number
 * by value without rounding to the bound's type.
 *
 * <p>Every returned value is held as text ({@code CAST … AS VARCHAR}), the form a test answer shows. The Reference is
 * read once, through a connection sealed exactly as a dry run's ({@link PipelineDryRun#seal}); an index whose estimated
 * memory passes {@link #maxBytes()} refuses the build rather than fill the heap.
 */
public final class PointLookup {

    /** System property capping the estimated memory of one point index, in bytes. */
    public static final String PROP_MAX_BYTES = "lookup.point.max_bytes";
    static final long DEFAULT_MAX_BYTES = 256L * 1024 * 1024;

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
    private long rows;
    private final Instant loadedAt;

    /** A {@code range} Reference row: its bounds as DuckDB text ({@code null} = open), its pick order, its values. */
    private record RangeRow(String from, String to, long ord, String[] vals) {}

    /** An {@code asof} Reference row of one key: its effective-from as DuckDB text ({@code null} = since forever). */
    private record AsofRow(String from, String[] vals) {}

    /** {@code range}: every row; {@code asof}: the rows of each key. Empty for the other modes. */
    private List<RangeRow> ranges = List.of();
    private Map<String, List<AsofRow>> asof = Map.of();
    /** The bound columns' DuckDB types (the tested value is cast to each), and whether each compares as a number. */
    private String fromType, toType;
    private boolean fromNumeric, toNumeric;
    private boolean hasFrom, hasTo;
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

    /** The memory cap of one index, from {@link #PROP_MAX_BYTES} (default 256 MiB). */
    public static long maxBytes() {
        String v = System.getProperty(PROP_MAX_BYTES);
        if (v == null || v.isBlank()) return DEFAULT_MAX_BYTES;
        try {
            return Math.max(1, Long.parseLong(v.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_BYTES;
        }
    }

    /**
     * The estimated heap an index holds while it is built: per row an array and a map entry, per held text a String
     * object plus two bytes a character (the JVM's worst case, UTF-16). An estimate, deliberately on the high side:
     * the cap refuses before the heap is at risk, not after.
     */
    private static final class Budget {
        static final long ROW = 64, TEXT = 40;
        final String id;
        final long cap = maxBytes();
        long used;

        Budget(String id) { this.id = id; }

        void row(String key, String[] vals, String... extra) {
            long b = ROW + text(key);
            for (String v : vals) b += 8 + text(v);
            for (String v : extra) b += text(v);
            used += b;
            if (used > cap)
                throw new IllegalArgumentException("transform.join node '" + id + "': the Reference needs more than "
                        + cap + " bytes in memory, the point engine's cap (" + PROP_MAX_BYTES + ")");
        }

        private static long text(String v) { return v == null ? 0 : TEXT + 2L * v.length(); }
    }

    /** The estimated memory this index holds, in bytes. */
    private long bytes;
    public long bytes() { return bytes; }

    public long rows() { return rows; }
    public Instant loadedAt() { return loadedAt; }
    public long buildMillis() { return buildMillis; }

    /**
     * A stamp of the files a Reference reads, as its resolver declares them ({@code readDirs} / {@code readFiles}):
     * file count, total bytes and the newest modification time. A commit to a Reference Dataset or an edited file
     * changes it, so a kept index can tell it is stale without reading a row. {@code null} when the resolver declares
     * no files — the change cannot then be seen, and only an explicit reload refreshes the index.
     */
    public static String referenceStamp(String reference, RowShaper.ReferenceResolver references) {
        List<Path> roots = new ArrayList<>(references.readDirs(reference));
        roots.addAll(references.readFiles(reference));
        if (roots.isEmpty()) return null;
        long files = 0, bytes = 0, newest = 0;
        for (Path root : roots) {
            if (!Files.exists(root)) continue;
            try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
                for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    java.nio.file.attribute.BasicFileAttributes a =
                            Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class);
                    files++;
                    bytes += a.size();
                    newest = Math.max(newest, a.lastModifiedTime().toMillis());
                }
            } catch (java.io.IOException | java.io.UncheckedIOException e) {
                return "unreadable:" + e.getMessage();   // a changed answer still differs from the last stamp
            }
        }
        return files + ":" + bytes + ":" + newest;
    }
    public String match() { return match; }

    /**
     * Build the index of keyed join {@code node}, reading its Reference through {@code references} on a fresh sealed
     * scratch connection that is removed afterwards.
     *
     * @throws IllegalArgumentException the node is not a keyed exact / longest-prefix join, its config is refused as
     *                                  the batch join would refuse it, or the Reference is over the memory cap
     */
    public static PointLookup load(PipelineNode node, RowShaper.ReferenceResolver references) throws Exception {
        long t0 = System.nanoTime();
        String id = node.id();
        String match = RowShaper.strOrNull(node, "match");
        if (match == null) match = RowShaper.JOIN_MATCH_EXACT;
        boolean prefix = RowShaper.JOIN_MATCH_LONGEST_PREFIX.equals(match);
        boolean range = RowShaper.JOIN_MATCH_RANGE.equals(match);
        boolean asof = RowShaper.JOIN_MATCH_ASOF.equals(match);
        if (!RowShaper.JOIN_MATCHES.contains(match))
            throw new IllegalArgumentException("transform.join node '" + id + "' has match '" + match
                    + "' — use one of " + RowShaper.JOIN_MATCHES);
        String keyCol = RowShaper.strOrNull(node, "key");
        String fromCol = RowShaper.strOrNull(node, "from");
        String toCol = RowShaper.strOrNull(node, "to");
        // the batch join's own shape rules (RowShaper.keyedJoin), so a config refused there is refused here
        if (range) {
            if (keyCol != null)
                throw new IllegalArgumentException("transform.join node '" + id + "': match: range reads from:/to:, not key:");
            if (fromCol == null && toCol == null)
                throw new IllegalArgumentException("transform.join node '" + id + "': match: range needs from: and/or to:");
        } else if (asof) {
            if (keyCol == null || fromCol == null || RowShaper.strOrNull(node, "at") == null)
                throw new IllegalArgumentException("transform.join node '" + id + "': match: asof needs key:, from: and at:");
            if (toCol != null)
                throw new IllegalArgumentException("transform.join node '" + id + "': match: asof reads from: only");
        } else {
            if (keyCol == null)
                throw new IllegalArgumentException("transform.join node '" + id + "' is not a keyed lookup (no key:)");
            if (fromCol != null || toCol != null)
                throw new IllegalArgumentException("transform.join node '" + id + "': from:/to: only apply to match: range "
                        + "(from: also to match: asof)");
        }
        String reference = RowShaper.strOrNull(node, "reference");
        if (reference == null)
            throw new IllegalArgumentException("transform.join node '" + id + "' needs a 'reference'");
        List<String> normalizers;
        try {
            normalizers = RowShaper.joinNormalizers(node.cfg("normalize"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("transform.join node '" + id + "': " + e.getMessage());
        }
        if (range && !normalizers.isEmpty())
            throw new IllegalArgumentException("transform.join node '" + id + "': normalize: applies to match: exact "
                    + "and longest_prefix, not range");
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
                for (String c : java.util.Arrays.asList(keyCol, fromCol, toCol))
                    if (c != null && refCols.stream().noneMatch(r -> r.equalsIgnoreCase(c)))
                        throw new IllegalArgumentException("transform.join node '" + id + "': the Reference has no column '"
                                + c + "' (it has " + refCols + ")");
                // no input rows here: the rows' own column names are not checked, only the Reference's
                List<RowShaper.JoinMapping> mappings = RowShaper.joinMappings(node, RowShaper.joinOn(node), refCols, List.of());
                List<String> returned = new ArrayList<>();
                for (RowShaper.JoinMapping m : mappings)
                    for (String c : m.columns().keySet())
                        if (returned.stream().noneMatch(r -> r.equalsIgnoreCase(c))) returned.add(c);
                Budget budget = new Budget(id);
                String vals = returned.stream().map(c -> ", CAST(t." + q(c) + " AS VARCHAR)")
                        .collect(java.util.stream.Collectors.joining());
                String pick = "refuse".equals(dups) ? null
                        : "ORDER BY (" + orderBy + ") " + ("last".equals(dups) ? "DESC" : "ASC") + " NULLS LAST, CAST(t AS VARCHAR)";

                if (range) {
                    // the batch join's Reference rows and overlap refusal, verbatim (RowShaper.rangeJoin)
                    String refRows = "SELECT t.*, row_number() OVER () AS __lk_rn FROM " + q(ref) + " t"
                            + (filter == null ? "" : " WHERE (" + filter + ")");
                    if (pick == null) RowShaper.refuseRangeOverlap(conn, id, refRows, fromCol, toCol);
                    // the pick order of overlapping rows: the batch join ranks the matching rows by the same order
                    String sql = "SELECT " + (fromCol == null ? "NULL" : "CAST(t." + q(fromCol) + " AS VARCHAR)") + ", "
                            + (toCol == null ? "NULL" : "CAST(t." + q(toCol) + " AS VARCHAR)") + ", "
                            + (pick == null ? "0" : "row_number() OVER (" + pick + ")") + vals
                            + " FROM (" + refRows + ") t";
                    List<RangeRow> rows = new ArrayList<>();
                    try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                        while (rs.next()) {
                            RangeRow r = new RangeRow(rs.getString(1), rs.getString(2), rs.getLong(3),
                                    values(rs, 4, returned.size()));
                            budget.row(r.from(), r.vals(), r.to());
                            rows.add(r);
                        }
                    }
                    PointLookup p = new PointLookup(match, normalizers, mappings, returned, Map.of(), Map.of(),
                            (System.nanoTime() - t0) / 1_000_000);
                    p.ranges = rows;
                    p.rows = rows.size();
                    p.bytes = budget.used;
                    p.bounds(conn, ref, fromCol, toCol);
                    return p;
                }
                if (asof) {
                    // the batch join's as-of Reference rows: key normalized as text, filter, one row per (key, date)
                    String refKey = RowShaper.normalized("CAST(t." + q(keyCol) + " AS VARCHAR)", normalizers);
                    String from = "t." + q(fromCol);
                    String sql = "SELECT " + refKey + ", CAST(" + from + " AS VARCHAR)" + vals + " FROM " + q(ref)
                            + " t WHERE t." + q(keyCol) + " IS NOT NULL" + (filter == null ? "" : " AND (" + filter + ")")
                            + (pick == null ? "" : " QUALIFY row_number() OVER (PARTITION BY " + refKey + ", " + from
                            + " " + pick + ") = 1");
                    Map<String, List<AsofRow>> byKeyAsof = new HashMap<>();
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    long n = 0;
                    try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                        while (rs.next()) {
                            n++;
                            String k = rs.getString(1), f = rs.getString(2);
                            if (!seen.add(k + "\u0000" + f))
                                throw new IllegalArgumentException("transform.join node '" + id + "': the Reference has "
                                        + "more than one row for " + keyCol + "=" + k + " effective " + f + " — clean the "
                                        + "Reference, or set duplicates: first|last with order_by:");
                            String[] v = values(rs, 3, returned.size());
                            budget.row(k, v, f);
                            byKeyAsof.computeIfAbsent(k, x -> new ArrayList<>()).add(new AsofRow(f, v));
                        }
                    }
                    PointLookup p = new PointLookup(match, normalizers, mappings, returned, Map.of(), Map.of(),
                            (System.nanoTime() - t0) / 1_000_000);
                    p.asof = byKeyAsof;
                    p.rows = n;
                    p.bytes = budget.used;
                    p.bounds(conn, ref, fromCol, null);
                    return p;
                }

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

                Map<String, String[]> byKey = new HashMap<>();
                Map<String, Boolean> exactOnly = new HashMap<>();
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql.toString())) {
                    while (rs.next()) {
                        String k = rs.getString(1);
                        String[] v = values(rs, 3, returned.size());
                        budget.row(k, v);
                        if (byKey.put(k, v) != null)
                            throw new IllegalArgumentException("transform.join node '" + id + "': the Reference has more "
                                    + "than one row for " + keyCol + "=" + k + " — clean the Reference, or set "
                                    + "duplicates: first|last with order_by:");
                        if (rs.getBoolean(2)) exactOnly.put(k, true);
                    }
                }
                PointLookup p = new PointLookup(match, normalizers, mappings, returned, byKey, exactOnly,
                        (System.nanoTime() - t0) / 1_000_000);
                p.bytes = budget.used;
                return p;
            }
        } finally {
            deleteScratch(scratch);   // PipelineDryRun.removeScratch only removes dryrun_* / flowjob_* dirs
        }
    }

    private static String[] values(ResultSet rs, int first, int n) throws SQLException {
        String[] out = new String[n];
        for (int i = 0; i < n; i++) out[i] = rs.getString(first + i);
        return out;
    }

    private static final java.util.Set<String> NUMERIC = java.util.Set.of("TINYINT", "SMALLINT", "INTEGER", "BIGINT",
            "HUGEINT", "UTINYINT", "USMALLINT", "UINTEGER", "UBIGINT", "UHUGEINT", "FLOAT", "DOUBLE", "DECIMAL");

    /** The bound columns' DuckDB types: the tested value is cast to each, and a number compares as a number. */
    private void bounds(Connection conn, String ref, String fromCol, String toCol) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + q(ref) + " LIMIT 0")) {
            java.sql.ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                String t = md.getColumnTypeName(i).toUpperCase(Locale.ROOT);
                boolean num = NUMERIC.contains(t.replaceAll("\\(.*", ""));
                if (md.getColumnName(i).equalsIgnoreCase(String.valueOf(fromCol))) { fromType = t; fromNumeric = num; }
                if (md.getColumnName(i).equalsIgnoreCase(String.valueOf(toCol))) { toType = t; toNumeric = num; }
            }
        }
        hasFrom = fromCol != null;
        hasTo = toCol != null;
    }

    /**
     * Two values in one bound's DuckDB text form, compared as DuckDB orders them: numbers by value, everything else
     * (text, and ISO dates and times, whose text sorts as they do) by code point, DuckDB's binary UTF-8 order.
     */
    private static Integer compare(String a, String b, boolean numeric) {
        if (numeric) return numCompare(a, b);
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            int x = a.codePointAt(i), y = b.codePointAt(j);
            if (x != y) return Integer.compare(x, y);
            i += Character.charCount(x);
            j += Character.charCount(y);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /**
     * Two numbers by value, never rounded to either side's type (the batch join compares a number in its own type):
     * {@code -inf} &lt; every finite number &lt; {@code inf} &lt; {@code nan}, DuckDB's order. {@code null} — unknown,
     * so no match — when either is not a number.
     */
    private static Integer numCompare(String a, String b) {
        java.math.BigDecimal[] x = new java.math.BigDecimal[1], y = new java.math.BigDecimal[1];
        int ca = numClass(a, x), cb = numClass(b, y);
        if (ca < 0 || cb < 0) return null;
        if (ca != cb) return Integer.compare(ca, cb);
        return ca == 1 ? x[0].compareTo(y[0]) : 0;
    }

    /** 0 = -inf, 1 = finite (its value in {@code out[0]}), 2 = inf, 3 = nan, -1 = not a number. */
    private static int numClass(String s, java.math.BigDecimal[] out) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        switch (t) {
            case "nan", "-nan", "+nan": return 3;
            case "inf", "+inf", "infinity", "+infinity": return 2;
            case "-inf", "-infinity": return 0;
            default:
                try {
                    out[0] = new java.math.BigDecimal(t);
                    return 1;
                } catch (NumberFormatException e) {
                    return -1;
                }
        }
    }

    /**
     * The value as the batch join compares it with a bound of {@code type}: cast in DuckDB only when the batch casts it
     * ({@code castTypes}: DATE for a range bound, DATE or TIMESTAMP for an as-of from-column), else as it is.
     */
    private static String asCompared(String value, String type, java.util.Set<String> castTypes) {
        if (value == null || type == null || !castTypes.contains(type)) return value;
        return castIn(value, type)[0];
    }

    /**
     * A range value is cast to a DATE bound (the batch casts it) and to a timestamp bound: there the batch's typed value
     * is widened by DuckDB (a DATE becomes midnight), which text comparison would miss. Never to a number — that rounds.
     */
    private static final java.util.Set<String> RANGE_CASTS = java.util.Set.of("DATE", "TIMESTAMP",
            "TIMESTAMP WITH TIME ZONE", "TIMESTAMP_S", "TIMESTAMP_MS", "TIMESTAMP_NS");
    private static final java.util.Set<String> ASOF_CASTS = java.util.Set.of("DATE", "TIMESTAMP");

    /** {@code SELECT CAST(TRY_CAST(? AS type) AS VARCHAR)} per type in DuckDB: the value as the batch join compares it. */
    private static String[] castIn(String value, String... types) {
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < types.length; i++)
            sql.append(i == 0 ? "" : ", ").append(types[i] == null ? "NULL" : "CAST(TRY_CAST(__lk_v AS " + types[i] + ") AS VARCHAR)");
        sql.append(" FROM (SELECT CAST(? AS VARCHAR) AS __lk_v)");
        try (Connection c = java.sql.DriverManager.getConnection("jdbc:duckdb:");
             java.sql.PreparedStatement ps = c.prepareStatement(sql.toString())) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                String[] out = new String[types.length];
                for (int i = 0; i < out.length; i++) out[i] = rs.getString(i + 1);
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the value as the Reference's type: " + e.getMessage(), e);
        }
    }

    /** As {@link #test(String, String)} with no time (an {@code asof} lookup then matches only rows with no from-date). */
    public Answer test(String value) { return test(value, null); }

    /**
     * Look up one value; {@code null} (no value) matches nothing, as a NULL key does in the batch join. {@code at} is
     * the row's time for {@code match: asof} (ignored otherwise), read as the from-column's type.
     */
    public Answer test(String value, String at) {
        if (RowShaper.JOIN_MATCH_RANGE.equals(match)) return rangeTest(value);
        if (RowShaper.JOIN_MATCH_ASOF.equals(match)) return asofTest(value, at);
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
        return answer(v, row, matched, kind);
    }

    /** {@code match: range}: the row whose {@code [from, to]} holds the value (an open bound reaches forever). */
    private Answer rangeTest(String value) {
        RangeRow best = null;
        if (value != null) {
            String vf = hasFrom ? asCompared(value, fromType, RANGE_CASTS) : null;
            String vt = hasTo ? asCompared(value, toType, RANGE_CASTS) : null;
            // as the batch SQL: (from IS NULL OR v >= from) AND (to IS NULL OR v <= to); an unknown comparison fails
            for (RangeRow r : ranges) {
                Integer cf = !hasFrom || r.from() == null || vf == null ? null : compare(vf, r.from(), fromNumeric);
                Integer ct = !hasTo || r.to() == null || vt == null ? null : compare(vt, r.to(), toNumeric);
                boolean in = (!hasFrom || r.from() == null || (cf != null && cf >= 0))
                        && (!hasTo || r.to() == null || (ct != null && ct <= 0));
                if (in && (best == null || r.ord() < best.ord())) best = r;
            }
        }
        String mk = best == null ? null
                : (best.from() == null ? "" : best.from()) + ".." + (best.to() == null ? "" : best.to());
        return answer(value, best == null ? null : best.vals(), mk, best == null ? "none" : "range");
    }

    /** {@code match: asof}: the key's latest row effective at or before {@code at} (an empty from-date = since forever). */
    private Answer asofTest(String value, String at) {
        String k = value == null ? null : normalize(value, normalizers);
        AsofRow best = null;
        if (k != null) {
            String t = asCompared(at, fromType, ASOF_CASTS);
            for (AsofRow r : asof.getOrDefault(k, List.of())) {
                if (r.from() != null) {
                    // from <= t: false, or unknown (no time, not a number), skips the dated row
                    Integer c = t == null ? null : compare(r.from(), t, fromNumeric);
                    if (c == null || c > 0) continue;
                }
                // ORDER BY from DESC NULLS LAST: a dated row beats the undated one, the later date the earlier
                if (best == null || (best.from() == null && r.from() != null)
                        || (best.from() != null && r.from() != null && compare(r.from(), best.from(), fromNumeric) > 0))
                    best = r;
            }
        }
        String mk = best == null ? null : k + "@" + (best.from() == null ? "" : best.from());
        return answer(k, best == null ? null : best.vals(), mk, best == null ? "none" : "asof");
    }

    private Answer answer(String v, String[] row, String matched, String kind) {
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
