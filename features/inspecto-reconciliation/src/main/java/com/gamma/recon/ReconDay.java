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
 * {@code role: temporal} ({@link DatasetRelation#temporalColumn}), else the Dataset's {@code dateField}. A Dataset
 * with neither is REFUSED (→ 422 naming the fix) rather than compared whole: the fail-closed choice of the two the
 * operator offered (operator, 2026-10-09), because a silently unscoped side would compare 30 days of one system
 * against one day of another and report every key as a Break.
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
            columns.add(temporalColumn(side.datasetId(), ds));
        }
        String fingerprint = fingerprint(base, datasets, dataRoot);

        List<String> available = fingerprint == null ? null : Cache.get("days|" + fingerprint);
        List<String> dayExprs = new ArrayList<>();
        List<String> types = new ArrayList<>();
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
        }
        if (day == null) {
            if (available.isEmpty())
                throw new IllegalArgumentException("none of the reconciled Datasets has a row with a day in its temporal column "
                        + columns + " yet - there is no day to reconcile");
            day = available.get(0);
        }
        List<ReconService.Side> sides = new ArrayList<>();
        for (int i = 0; i < base.sides().size(); i++) {
            ReconService.Side s = base.sides().get(i);
            sides.add(new ReconService.Side(s.datasetId(), "SELECT * FROM (" + s.relationSql() + ") AS __day WHERE "
                    + dayPredicate(types.get(i), columns.get(i), day), s.columnMap(), s.filter()));
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
        throw new IllegalArgumentException("dataset '" + datasetId + "' has no temporal column - a Reconciliation is "
                + "read one day at a time, so mark the Dataset's event-date column 'role: temporal' in its columns "
                + "(or set its dateField)");
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

        @SuppressWarnings("unchecked")
        static synchronized <T> T get(String key) { return (T) MAP.get(key); }
        static synchronized void put(String key, Object value) { MAP.put(key, value); }
        static synchronized void clear() { MAP.clear(); }
        static synchronized int size() { return MAP.size(); }
    }
}
