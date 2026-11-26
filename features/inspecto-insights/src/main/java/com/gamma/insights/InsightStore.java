package com.gamma.insights;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.alert.ScoreOutputDirs;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.SqlIdent;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Insights store of one Profile, {@code <dataDir>/insights_<profile>/} (plan §11.9): one Parquet holding every run
 * kept ({@link #RETENTION_DAYS}), rewritten atomically per run ({@link ScoreOutputDirs#swapIn}), and
 * {@code feedback.json} beside it — the readers' statuses keyed by type × slice, so feedback outlives the run that
 * showed it. The directory is claimed with {@link #MARKER} naming the Profile; another owner's is refused untouched.
 */
final class InsightStore {

    private InsightStore() {}

    static final String MARKER = ".insights-output";
    static final String GLOB = "insights-*.parquet";
    static final String FEEDBACK = "feedback.json";
    static final int RETENTION_DAYS = 400;
    static final List<String> STATUSES = List.of("useful", "not_useful", "expected");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    /** Column name → DuckDB type, in stored order. */
    static final Map<String, String> COLUMNS;
    static {
        Map<String, String> c = new LinkedHashMap<>();
        for (String s : List.of("insight_id", "profile_id", "run_id")) c.put(s, "VARCHAR");
        c.put("run_at", "TIMESTAMP");
        for (String s : List.of("insight_key", "insight_type", "measure", "dimension", "slice_value")) c.put(s, "VARCHAR");
        c.put("period", "DATE");
        for (String s : List.of("value", "expected", "delta", "impact", "p", "q", "score")) c.put(s, "DOUBLE");
        for (String s : List.of("band", "explanation", "query", "status")) c.put(s, "VARCHAR");
        c.put("first_seen", "TIMESTAMP");
        COLUMNS = java.util.Collections.unmodifiableMap(c);
    }

    static String store(String profileId) {
        return InsightProfile.PREFIX + profileId;
    }

    static Object lock(Path dir) {
        return LOCKS.computeIfAbsent(dir.toAbsolutePath().normalize(), k -> new Object());
    }

    /** The Profile's directory, created with its marker or accepted when the marker names it. */
    static Path claim(Path dataDir, String profileId) throws IOException {
        Path root = dataDir.normalize();
        Path dir = root.resolve(store(profileId)).normalize();
        if (!dir.startsWith(root) || dir.equals(root)) throw new IllegalArgumentException("insights store escapes the data root");
        ScoreOutputDirs.claim(dir, MARKER, InsightProfile.KIND, profileId);
        return dir;
    }

    /** The directory when it exists and is this Profile's, else {@code null} (never created by a read). */
    static Path existing(Path dataDir, String profileId) {
        Path dir = dataDir.normalize().resolve(store(profileId)).normalize();
        return Files.isDirectory(dir) && ScoreOutputDirs.ownedBy(dir, MARKER, profileId) ? dir : null;
    }

    /** Every stored row, as column → value ({@code period} as {@code YYYY-MM-DD}, timestamps as ISO instants). */
    static List<Map<String, Object>> read(Path dir) throws IOException, SQLException {
        boolean any;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, GLOB)) {
            any = files.iterator().hasNext();
        }
        if (!any) return new ArrayList<>();
        StringBuilder sel = new StringBuilder();
        for (Map.Entry<String, String> e : COLUMNS.entrySet()) {
            if (!sel.isEmpty()) sel.append(", ");
            sel.append(switch (e.getValue()) {
                case "DATE" -> "CAST(" + e.getKey() + " AS VARCHAR) AS " + e.getKey();
                case "TIMESTAMP" -> "epoch_ms(" + e.getKey() + ") AS " + e.getKey();
                default -> e.getKey();
            });
        }
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = DuckDbUtil.openInMemory(DuckDbUtil.spillDirUnder(dir.getParent()), List.of(dir));
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + sel + " FROM " + com.gamma.sql.SqlViews.parquet(
                     SqlIdent.sqlStr(dir.toString().replace('\\', '/') + "/" + GLOB), false, com.gamma.sql.SqlViews.Hive.OFF))) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    String name = md.getColumnLabel(i);
                    Object v = rs.getObject(i);
                    if (v != null && "TIMESTAMP".equals(COLUMNS.get(name))) v = Instant.ofEpochMilli(((Number) v).longValue()).toString();
                    else if (v instanceof Number n && "DOUBLE".equals(COLUMNS.get(name))) v = n.doubleValue();
                    row.put(name, v);
                }
                out.add(row);
            }
        }
        return out;
    }

    /** Rewrites the store with {@code rows}, dropping runs older than {@link #RETENTION_DAYS} before {@code at}. */
    static void write(Path dir, List<Map<String, Object>> rows, Instant at) throws IOException, SQLException {
        Instant keepFrom = at.minus(RETENTION_DAYS, ChronoUnit.DAYS);
        try (DirectoryStream<Path> junk = Files.newDirectoryStream(dir, "*.{tmp,stale}")) {
            for (Path p : junk) Files.deleteIfExists(p);
        }
        String name = "insights-" + at.toEpochMilli() + ".parquet";
        Path tmp = dir.resolve(name + ".tmp");
        StringBuilder ddl = new StringBuilder(), marks = new StringBuilder();
        for (Map.Entry<String, String> e : COLUMNS.entrySet()) {
            if (!ddl.isEmpty()) { ddl.append(", "); marks.append(", "); }
            ddl.append(e.getKey()).append(' ').append(e.getValue());
            // timestamps go in as UTC wall-clock text: a JDBC Timestamp would be stored in the host zone and read back
            // by epoch_ms as if UTC, shifting every row on each rewrite
            marks.append("DATE".equals(e.getValue()) ? "CAST(? AS DATE)"
                    : "TIMESTAMP".equals(e.getValue()) ? "CAST(? AS TIMESTAMP)" : "?");
        }
        try (Connection c = DuckDbUtil.openInMemory(DuckDbUtil.spillDirUnder(dir.getParent()), List.of(dir));
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE i (" + ddl + ")");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO i VALUES (" + marks + ")")) {
                for (Map<String, Object> r : rows) {
                    if (Instant.parse(String.valueOf(r.get("run_at"))).isBefore(keepFrom)) continue;
                    int i = 1;
                    for (Map.Entry<String, String> e : COLUMNS.entrySet()) {
                        Object v = r.get(e.getKey());
                        switch (e.getValue()) {
                            case "TIMESTAMP" -> {
                                ps.setString(i, v == null ? null : java.time.LocalDateTime.ofInstant(
                                        Instant.parse(String.valueOf(v)), java.time.ZoneOffset.UTC).toString());
                            }
                            case "DOUBLE" -> {
                                if (!(v instanceof Number n) || !Double.isFinite(n.doubleValue())) ps.setNull(i, java.sql.Types.DOUBLE);
                                else ps.setDouble(i, n.doubleValue());
                            }
                            default -> ps.setString(i, v == null ? null : String.valueOf(v));
                        }
                        i++;
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            st.execute("COPY i TO " + path(tmp) + " (FORMAT PARQUET)");
        }
        ScoreOutputDirs.swapIn(dir, tmp, name, GLOB);
    }

    // ── feedback ──

    /** key → {status, note, actor, at}. Empty when none was given. */
    static Map<String, Map<String, Object>> feedback(Path dir) throws IOException {
        Path f = dir.resolve(FEEDBACK);
        if (!Files.isRegularFile(f)) return new LinkedHashMap<>();
        return JSON.readValue(f.toFile(), new TypeReference<LinkedHashMap<String, Map<String, Object>>>() {});
    }

    static Map<String, String> statuses(Path dir) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : feedback(dir).entrySet())
            out.put(e.getKey(), String.valueOf(e.getValue().get("status")));
        return out;
    }

    /** Records one reader's status for {@code key} (the latest word wins), atomically. */
    static void putFeedback(Path dir, String key, String status, String note, String actor, Instant at) throws IOException {
        synchronized (lock(dir)) {
            Map<String, Map<String, Object>> all = feedback(dir);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("status", status);
            if (note != null) e.put("note", note);
            e.put("actor", actor);
            e.put("at", at.toString());
            all.put(key, e);
            Path tmp = dir.resolve(FEEDBACK + ".tmp");
            JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), all);
            Files.move(tmp, dir.resolve(FEEDBACK), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    static String json(Object v) {
        try {
            return JSON.writeValueAsString(v);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Object parse(Object s) {
        if (s == null) return null;
        try {
            return JSON.readValue(String.valueOf(s), Object.class);
        } catch (IOException e) {
            return s;
        }
    }

    private static String path(Path p) {
        return SqlIdent.sqlStr(p.toString().replace('\\', '/'));
    }
}
