package com.gamma.recon;

import com.gamma.query.DatasetRelation;
import com.gamma.sql.SqlSandbox;
import com.gamma.sql.SqlSandboxPolicy;
import com.gamma.util.SqlIdent;
import com.gamma.util.Values;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A Reconciliation is read ONE DAY at a time (RECON-PERF-1, operator 2026-10-09). Every side's relation is filtered
 * to one calendar day of its Dataset's <b>temporal column</b> — the {@code columns[]} entry carrying
 * {@code role: temporal} ({@link DatasetRelation#temporalColumn}), else the Dataset's {@code dateField}. A side
 * whose Dataset declares neither is REFUSED (→ 422 naming the fix) — whether the other sides are dated or not. There
 * is no whole-period fallback (operator, 2026-10-09, reversing the same day's {@code dayScoped:false} answer): a
 * monthly comparison is a Dataset dated by its period, read one day at a time like every other.
 *
 * <p>No requested day ⇒ the LATEST day present on any side. {@code availableDays} is the distinct days across the
 * sides, newest first, capped at {@link #MAX_DAYS}. Also holds the small per-(reconciliation, day) result cache
 * ({@link Cache}), keyed on the spec, the day and the sides' input-file stamps, so a page request reuses the day's
 * comparison instead of recomputing it.
 */
public final class ReconDay {
    private ReconDay() {}

    /** Most distinct days reported in {@code availableDays} (newest first). */
    public static final int MAX_DAYS = 366;
    /** Most input files fingerprinted per side; a larger store is simply not cached. */
    static final int MAX_FINGERPRINT_FILES = 50_000;

    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * A day-scoped spec. {@code fingerprint} identifies the inputs (spec + every side's file stamps) — {@code null}
     * when they cannot be enumerated (a view-backed or virtual Dataset, or a store over the file cap), and then
     * nothing derived from them is cached.
     */
    public record Scoped(ReconService.Spec spec, String day, List<String> availableDays, String fingerprint) {}

    /**
     * Resolve the reconciliation {@code config} to a spec scoped to {@code requestedDay} (ISO {@code yyyy-mm-dd}, or
     * {@code null} for the latest day present).
     *
     * @param datasetFor     a dataset id → its component config (throws as the caller reports an unknown dataset)
     * @param relationSqlFor a dataset id → its trusted relation SQL (ditto)
     * @param dataRoot       the Space's data root, for the input-file fingerprint ({@code null} ⇒ uncacheable)
     * @throws IllegalArgumentException on a bad day, a Dataset with no temporal column, or no dated rows (→ 422)
     */
    public static Scoped resolve(Map<String, Object> config, Function<String, Map<String, Object>> datasetFor,
                                 Function<String, String> relationSqlFor, Path dataRoot, String requestedDay)
            throws SQLException, IOException {
        String day = requestedDay == null || requestedDay.isBlank() ? null : isoDay(requestedDay.trim());
        ReconService.Spec base = ReconConfigLoader.buildSpec(config, relationSqlFor);
        List<String> columns = new ArrayList<>();
        List<Map<String, Object>> datasets = new ArrayList<>();
        for (ReconService.Side side : base.sides()) {
            Map<String, Object> ds = datasetFor.apply(side.datasetId());
            datasets.add(ds);
            columns.add(temporalColumn(side.datasetId(), ds));   // an undated side throws the named fix
        }
        String fingerprint = fingerprint(base, datasets, dataRoot);

        List<String> available = fingerprint == null ? null : Cache.get("days|" + fingerprint);
        List<String> dayExprs = new ArrayList<>();
        List<String> types = new ArrayList<>();
        List<ReconService.Side> sides = new ArrayList<>();
        try (SqlSandbox sandbox = SqlSandbox.open(SqlSandboxPolicy.defaultPolicy())) {
            Connection conn = sandbox.connection();
            for (int i = 0; i < base.sides().size(); i++) {
                String type = columnType(conn, base.sides().get(i), columns.get(i));
                types.add(type);
                dayExprs.add(dayExpr(type, columns.get(i)));
            }
            if (available == null) {
                List<String> branches = new ArrayList<>();
                for (int i = 0; i < base.sides().size(); i++)
                    branches.add("SELECT " + dayExprs.get(i) + " AS d FROM (" + base.sides().get(i).relationSql() + ") AS __r");
                available = new ArrayList<>();
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT d FROM (" + String.join(" UNION ", branches)
                             + ") AS __days WHERE d IS NOT NULL ORDER BY d DESC LIMIT " + MAX_DAYS)) {
                    while (rs.next()) available.add(rs.getObject(1).toString());
                }
                available = List.copyOf(available);
                if (fingerprint != null) Cache.put("days|" + fingerprint, available);
            }
            if (day == null) {
                if (available.isEmpty())
                    throw new IllegalArgumentException("none of the reconciled Datasets has a row with a day in its temporal column "
                            + columns + " yet - there is no day to reconcile");
                day = available.get(0);
            }
            for (int i = 0; i < base.sides().size(); i++) {
                ReconService.Side s = base.sides().get(i);
                String pruned = fingerprint == null ? null
                        : dayFiles(conn, s.relationSql(), datasets.get(i), dataRoot, columns.get(i), types.get(i), day,
                                fingerprint + "|" + i);
                sides.add(new ReconService.Side(s.datasetId(), "SELECT * FROM (" + (pruned == null ? s.relationSql() : pruned)
                        + ") AS __day WHERE " + dayPredicate(types.get(i), columns.get(i), day), s.columnMap(), s.filter()));
            }
        }
        ReconService.Spec scoped = new ReconService.Spec(List.copyOf(sides), base.keyColumns(), base.measures(),
                base.includeRecordCount(), base.cardinality(), base.carriedImpact());
        return new Scoped(scoped, day, available, fingerprint);
    }

    /** The Dataset's day column: {@code role: temporal}, else {@code dateField}; neither ⇒ 422 naming the fix. */
    static String temporalColumn(String datasetId, Map<String, Object> ds) {
        Optional<String> col = DatasetRelation.temporalColumn(ds);
        if (col.isPresent()) return col.get();
        String dateField = Values.trimToNull(ds == null ? null : ds.get("dateField"));
        if (dateField != null) {
            if (!SAFE_IDENT.matcher(dateField).matches())
                throw new IllegalArgumentException("dataset '" + datasetId + "': dateField must be a plain identifier, got '"
                        + dateField + "'");
            return dateField;
        }
        throw new IllegalArgumentException("Dataset '" + datasetId + "' has no date column; mark one 'role: temporal' in its "
                + "columns or set its dateField - a Reconciliation is read one day at a time");
    }

    /** {@code yyyy-mm-dd} or 422 — the only form that ever reaches SQL, as a {@code DATE '…'} literal. */
    static String isoDay(String v) {
        try {
            return LocalDate.parse(v).toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("day must be an ISO date (yyyy-mm-dd), got '" + v + "'");
        }
    }

    /** The column's DuckDB type name, upper-cased; a column the relation does not expose ⇒ 422. */
    private static String columnType(Connection conn, ReconService.Side side, String column) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM (" + side.relationSql() + ") AS __r LIMIT 0")) {
            ResultSetMetaData md = rs.getMetaData();
            for (int c = 1; c <= md.getColumnCount(); c++)
                if (md.getColumnLabel(c).equalsIgnoreCase(column)) return md.getColumnTypeName(c).toUpperCase(Locale.ROOT);
        }
        throw new IllegalArgumentException("dataset '" + side.datasetId() + "': temporal column '" + column
                + "' is not a column of the dataset");
    }

    private static boolean isTimestamp(String type) {
        return type.startsWith("TIMESTAMP");
    }

    /** The column as a DATE: itself, a CAST of a timestamp, or a TRY_CAST of anything else (text dates). */
    static String dayExpr(String type, String column) {
        String c = SqlIdent.q(column);
        if ("DATE".equals(type)) return c;
        return isTimestamp(type) ? "CAST(" + c + " AS DATE)" : "TRY_CAST(" + c + " AS DATE)";
    }

    /**
     * The one-day predicate. A DATE or TIMESTAMP column is compared bare (a half-open range for a timestamp), so the
     * Parquet reader can skip row groups on their min/max statistics; only a text column pays a per-row cast.
     */
    static String dayPredicate(String type, String column, String day) {
        String c = SqlIdent.q(column);
        if ("DATE".equals(type)) return c + " = DATE '" + day + "'";
        if (isTimestamp(type))
            return c + " >= DATE '" + day + "' AND " + c + " < DATE '" + LocalDate.parse(day).plusDays(1) + "'";
        return dayExpr(type, column) + " = DATE '" + day + "'";
    }

    /**
     * RECON-PERF-RESIDUALS-1 (1): the side's relation over ONLY the files that can hold {@code day}, or {@code null}
     * to read the whole store. A file is skipped only when its Parquet footer statistics PROVE the temporal column
     * holds no value on that day (every row group's min/max lies outside it) — exact for any layout, so a Hive
     * {@code year=/month=/day=} store prunes to its day's folder WITHOUT turning {@code hive_partitioning} on (that
     * stays off by decision: it would surface partition segments as new columns on every Dataset) and without
     * trusting a folder name, which a Pipeline may have cut from a different column than the temporal one. Only a
     * plain local {@code physicalRef} store ({@link DatasetRelation#relationSqlOverFiles}) over a bare {@code DATE} /
     * {@code TIMESTAMP} column prunes; a missing statistic keeps the file; a pruned relation whose column set differs
     * from the whole store's (a column added mid-life) is not used. Per-file ranges are cached on the input fingerprint.
     */
    static String dayFiles(Connection conn, String wholeRel, Map<String, Object> ds, Path dataRoot, String column,
                           String type, String day, String cacheKey) throws SQLException {
        if (dataRoot == null || !("DATE".equals(type) || "TIMESTAMP".equals(type))) return null;
        String decided = Cache.get("prune|" + cacheKey + "|" + day);
        if (decided != null) return decided.isEmpty() ? null : decided;
        String sql = prunedRelation(conn, wholeRel, ds, dataRoot, column, day, cacheKey);
        Cache.put("prune|" + cacheKey + "|" + day, sql == null ? "" : sql);
        return sql;
    }

    private static String prunedRelation(Connection conn, String wholeRel, Map<String, Object> ds, Path dataRoot,
                                         String column, String day, String cacheKey) throws SQLException {
        Optional<DatasetRelation.InputFiles> listed = DatasetRelation.inputFiles(ds, dataRoot, MAX_FINGERPRINT_FILES);
        if (listed.isEmpty() || listed.get().overLimit() || listed.get().files().size() < 2) return null;
        List<String> rels = listed.get().files().stream().map(DatasetRelation.FileStamp::path).toList();
        if (DatasetRelation.relationSqlOverFiles(ds, dataRoot, List.of(rels.get(0))) == null) return null;
        Map<String, LocalDate[]> ranges = Cache.get("stats|" + cacheKey);
        if (ranges == null) {
            ranges = fileDayRanges(conn, dataRoot, rels, column);
            Cache.put("stats|" + cacheKey, ranges);
        }
        LocalDate d = LocalDate.parse(day);
        List<String> kept = new ArrayList<>();
        for (String rel : rels) {
            LocalDate[] r = ranges.get(rel);
            if (r == null || !(d.isBefore(r[0]) || d.isAfter(r[1]))) kept.add(rel);
        }
        if (kept.size() == rels.size()) return null;
        if (kept.isEmpty()) return "SELECT * FROM (" + wholeRel + ") AS __none WHERE false";
        String sql = DatasetRelation.relationSqlOverFiles(ds, dataRoot, kept);
        return sql != null && columnNames(conn, sql).equals(columnNames(conn, wholeRel)) ? sql : null;
    }

    /** Each file's [min, max] day of {@code column} from its footer; a file with any unknown statistic is absent. */
    private static Map<String, LocalDate[]> fileDayRanges(Connection conn, Path dataRoot, List<String> rels, String column)
            throws SQLException {
        Map<String, String> relOf = new LinkedHashMap<>();
        for (String rel : rels) relOf.put(dataRoot.normalize().resolve(rel).normalize().toString().replace('\\', '/'), rel);
        String mn = "TRY_CAST(TRY_CAST(stats_min_value AS TIMESTAMP) AS DATE)";
        String mx = "TRY_CAST(TRY_CAST(stats_max_value AS TIMESTAMP) AS DATE)";
        Map<String, LocalDate[]> out = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT file_name, min(" + mn + "), max(" + mx + "), bool_or(" + mn
                     + " IS NULL OR " + mx + " IS NULL) FROM parquet_metadata("
                     + com.gamma.sql.SqlViews.pathList(new ArrayList<>(relOf.keySet())) + ") WHERE lower(path_in_schema) = '"
                     + column.toLowerCase(Locale.ROOT) + "' GROUP BY file_name")) {
            while (rs.next()) {
                String rel = relOf.get(rs.getString(1).replace('\\', '/'));
                if (rel == null || rs.getBoolean(4) || rs.getObject(2) == null || rs.getObject(3) == null) continue;
                out.put(rel, new LocalDate[]{LocalDate.parse(rs.getObject(2).toString()), LocalDate.parse(rs.getObject(3).toString())});
            }
        }
        return Map.copyOf(out);
    }

    private static List<String> columnNames(Connection conn, String relationSql) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM (" + relationSql + ") AS __r LIMIT 0")) {
            ResultSetMetaData md = rs.getMetaData();
            for (int c = 1; c <= md.getColumnCount(); c++) names.add(md.getColumnLabel(c).toLowerCase(Locale.ROOT));
        }
        java.util.Collections.sort(names);
        return names;
    }

    /** sha-256 over the spec and every side's input-file stamps; {@code null} = not enumerable ⇒ never cached. */
    static String fingerprint(ReconService.Spec spec, List<Map<String, Object>> datasets, Path dataRoot) {
        if (dataRoot == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(spec.toString().getBytes(StandardCharsets.UTF_8));
            for (Map<String, Object> ds : datasets) {
                Optional<DatasetRelation.InputFiles> files = DatasetRelation.inputFiles(ds, dataRoot, MAX_FINGERPRINT_FILES);
                if (files.isEmpty() || files.get().overLimit()) return null;
                md.update((byte) '#');
                for (DatasetRelation.FileStamp f : files.get().files())
                    md.update((f.path() + '|' + f.size() + '|' + f.mtimeMillis() + '\n').getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (RuntimeException unreadable) {
            return null;   // a store that cannot be listed is not cacheable; the comparison still runs (and reports)
        }
    }

    /**
     * The bounded in-memory result cache: at most {@link #MAX_ENTRIES} entries, least-recently-used evicted. Keys
     * carry the input fingerprint, so a changed file (size or mtime) or a changed config is a different key — stale
     * entries are never read, only aged out.
     */
    static final class Cache {
        static final int MAX_ENTRIES = 32;
        private static final Map<String, Object> MAP = new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Object> e) { return size() > MAX_ENTRIES; }
        };

        /** Hits per key kind (the key up to its first {@code |}: grain, days, stats, breaks, rows) — read by tests. */
        private static final Map<String, Long> HITS = new java.util.HashMap<>();

        @SuppressWarnings("unchecked")
        static synchronized <T> T get(String key) {
            Object v = MAP.get(key);
            if (v != null) HITS.merge(key.substring(0, Math.max(0, key.indexOf('|'))), 1L, Long::sum);
            return (T) v;
        }
        static synchronized long hits(String kind) { return HITS.getOrDefault(kind, 0L); }
        static synchronized void put(String key, Object value) { MAP.put(key, value); }
        static synchronized void clear() { MAP.clear(); }
        static synchronized int size() { return MAP.size(); }
    }
}
