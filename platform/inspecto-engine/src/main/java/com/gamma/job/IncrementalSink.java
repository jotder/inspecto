package com.gamma.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static com.gamma.util.SqlIdent.sqlStr;

/**
 * The per-day partition replace behind an incremental {@code sql.template} Job (operator, 2026-10-10).
 *
 * <p>Layout: {@code <sink>/<column>=YYYY-MM-DD/part-<run>.parquet}, the date column held in the folder name
 * only (Hive style), so the store's {@code hive_partitioning} reader restores it — as the LAST column, typed
 * DATE. Only the lookback days are written; every other day folder is never opened.
 *
 * <p>Per day, under the sink lock: (1) the new rows land as {@code *.parquet.tmp}; (2) the live
 * {@code *.parquet} files are hidden as {@code *.stale}; (3) the tmp is revealed by ONE atomic move;
 * (4) the stale files are deleted. Recovery (start of every replace) reads each day folder: a {@code .tmp}
 * means step 3 never ran, so the stale files are restored (the OLD partition); no {@code .tmp} but
 * {@code .stale} means step 3 ran, so the stale files are dropped (the NEW partition). A crash therefore
 * leaves the old or the new partition, never a mix. A reader inside the step 2-3 window sees that one day
 * briefly empty — the same discipline as the full-snapshot swap. The sink's ownership marker is a dot-file
 * at its root and a replace never touches it.
 */
final class IncrementalSink {

    private static final Logger log = LoggerFactory.getLogger(IncrementalSink.class);
    static final String OWNER_MARKER = ".sql-template-incremental";
    static final String LOCK_FILE = ".incremental.lock";
    private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final ConcurrentHashMap<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();

    /** Test seam: runs after a day's live files are hidden and before the reveal (crash simulation). */
    static volatile Runnable afterHide = () -> { };

    /** Test seam: runs before each expired day folder is deleted (delete-failure simulation). */
    interface PruneHook { void before(Path dir) throws IOException; }
    static volatile PruneHook beforePrune = dir -> { };

    /** A template's zero-row schema seed; a day folder holding it is never pruned. */
    static final String SEED_FILE = "seed.parquet";

    /**
     * Delete every {@code <column>=YYYY-MM-DD} folder dated before {@code keepFrom} (retention, 2026-10-11).
     * Only exact ISO-date folder names; a folder holding {@link #SEED_FILE} and every other entry (dot-files
     * included) are left alone. A failed delete is logged and skipped — retention never fails the run.
     * @return the number of folders deleted
     */
    static int prune(Path sink, String column, LocalDate keepFrom) throws IOException {
        int deleted = 0;
        for (Path dir : list(sink, column + "=*")) {
            String n = dir.getFileName().toString();
            String d = n.substring(column.length() + 1);
            if (!Files.isDirectory(dir) || !DAY.matcher(d).matches()) continue;
            LocalDate day;
            try { day = LocalDate.parse(d); } catch (java.time.format.DateTimeParseException e) { continue; }
            if (!day.isBefore(keepFrom) || Files.exists(dir.resolve(SEED_FILE))) continue;
            try {
                beforePrune.before(dir);
                if (deleteContained(dir, dir.toRealPath())) deleted++;
                else log.warn("sql.template incremental: expired partition {} only partly pruned; kept", dir);
            } catch (IOException | RuntimeException e) {
                log.warn("sql.template incremental: could not prune expired partition {} ({}); kept", dir, e.toString());
            }
        }
        return deleted;
    }

    /**
     * Delete {@code p} without ever leaving {@code root} (the partition folder's real path). A link or other
     * reparse point (a Windows junction is {@code isOther()}, not a symlink, to Java) is never descended: only
     * the link entry itself is removed. A directory whose real path is outside {@code root} is left, WARN.
     * @return true when {@code p} is gone
     */
    private static boolean deleteContained(Path p, Path root) throws IOException {
        java.nio.file.attribute.BasicFileAttributes a = Files.readAttributes(p,
                java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (a.isSymbolicLink() || a.isOther()) {
            try { Files.delete(p); return true; }   // removes the link / junction entry, never its target
            catch (IOException e) {
                log.warn("sql.template incremental: left link {} in an expired partition ({})", p, e.toString());
                return false;
            }
        }
        if (!p.toRealPath().startsWith(root)) {
            log.warn("sql.template incremental: {} resolves outside its partition folder; not deleted", p);
            return false;
        }
        boolean all = true;
        if (a.isDirectory()) {
            List<Path> children = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) { ds.forEach(children::add); }
            for (Path c : children) all &= deleteContained(c, root);
        }
        if (all) Files.delete(p);
        return all;
    }

    record Outcome(long rows, int days, long dropped) { }

    private IncrementalSink() { }

    /** Why this sink cannot take a day-partitioned replace for {@code job}, or {@code null} when it can
     *  (absent, empty, or already laid out as {@code <column>=YYYY-MM-DD} folders owned by this job). */
    static String layoutRefusal(Path sink, String column, String job) throws IOException {
        if (!Files.isDirectory(sink)) return null;
        Path marker = sink.resolve(OWNER_MARKER);
        if (Files.exists(marker)) {
            String owner = Files.readString(marker, StandardCharsets.UTF_8).trim();
            if (!owner.equals(job)) return "sink '" + sink.getFileName() + "' is owned by incremental job '" + owner + "'";
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(sink)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.startsWith(".")) continue;
                if (Files.isDirectory(p) && n.startsWith(column + "=") && DAY.matcher(n.substring(column.length() + 1)).matches())
                    continue;
                return "sink '" + sink.getFileName() + "' is not laid out by day on '" + column + "' (found '" + n
                        + "'); an incremental Job needs an empty sink or " + column + "=YYYY-MM-DD folders";
            }
        }
        return null;
    }

    /** Replace exactly the {@code lookback} day partitions ending at {@code day} with {@code table}'s rows. */
    static Outcome replace(Connection conn, String table, Path sink, IncrementalSpec inc, LocalDate day,
                           String job, String runId) throws IOException, SQLException {
        String col = "\"" + inc.column().replace("\"", "\"\"") + "\"";
        String type = null;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + table + " LIMIT 0")) {
            java.sql.ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++)
                if (md.getColumnLabel(i).equals(inc.column())) type = md.getColumnTypeName(i);
        }
        if (type == null)
            throw new IllegalStateException("incremental column '" + inc.column() + "' is not in the Job's output");
        if (!"DATE".equals(type.toUpperCase(Locale.ROOT)))
            throw new IllegalStateException("incremental column '" + inc.column() + "' must be DATE, is " + type);
        LocalDate from = day.minusDays(inc.lookback() - 1L);
        long dropped = count(conn, "SELECT count(*) FROM " + table + " WHERE " + col + " IS NULL OR " + col
                + " < DATE '" + from + "' OR " + col + " > DATE '" + day + "'");
        Files.createDirectories(sink);
        Object jvmLock = JVM_LOCKS.computeIfAbsent(sink.toAbsolutePath().normalize(), k -> new Object());
        synchronized (jvmLock) {
            try (FileChannel ch = FileChannel.open(sink.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = ch.lock()) {
                String refusal = layoutRefusal(sink, inc.column(), job);
                if (refusal != null) throw new IllegalStateException(refusal);
                Path marker = sink.resolve(OWNER_MARKER);
                if (!Files.exists(marker)) Files.writeString(marker, job, StandardCharsets.UTF_8);
                recoverAll(sink, inc.column());
                long rows = 0;
                String part = "part-" + safeRun(runId) + ".parquet";
                for (LocalDate d = from; !d.isAfter(day); d = d.plusDays(1)) {
                    Path dir = sink.resolve(inc.column() + "=" + d);
                    String pick = " FROM " + table + " WHERE " + col + " = DATE '" + d + "'";
                    long n = count(conn, "SELECT count(*)" + pick);
                    Path tmp = null;
                    if (n > 0) {
                        Files.createDirectories(dir);
                        tmp = dir.resolve(part + ".tmp");
                        try (Statement st = conn.createStatement()) {
                            st.execute("COPY (SELECT * EXCLUDE (" + col + ")" + pick + ") TO "
                                    + sqlStr(tmp.toString().replace('\\', '/')) + " (FORMAT PARQUET)");
                        }
                    } else if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    swapDay(dir, tmp, part);
                    rows += n;
                }
                if (inc.retain() > 0) {
                    try {
                        int pruned = prune(sink, inc.column(), day.minusDays(inc.retain() - 1L));
                        if (pruned > 0) log.info("sql.template incremental: pruned {} day partition(s) of {} older than {} day(s)",
                                pruned, sink.getFileName(), inc.retain());
                    } catch (IOException e) {
                        log.warn("sql.template incremental: retention prune of {} skipped ({})", sink.getFileName(), e.toString());
                    }
                }
                return new Outcome(rows, inc.lookback(), dropped);
            }
        }
    }

    private static void swapDay(Path dir, Path tmp, String part) throws IOException {
        List<Path> stale = new ArrayList<>();
        for (Path p : list(dir, "*.parquet")) {
            Path hidden = p.resolveSibling(p.getFileName() + ".stale");
            Files.move(p, hidden, StandardCopyOption.ATOMIC_MOVE);
            stale.add(hidden);
        }
        afterHide.run();
        if (tmp != null) Files.move(tmp, dir.resolve(part), StandardCopyOption.ATOMIC_MOVE);
        for (Path p : stale) Files.delete(p);
        if (tmp == null) deleteIfEmpty(dir);
    }

    /** Roll every interrupted day back (a {@code .tmp} survives) or forward (only {@code .stale} survives). */
    static void recoverAll(Path sink, String column) throws IOException {
        for (Path dir : list(sink, column + "=*")) {
            if (!Files.isDirectory(dir)) continue;
            List<Path> tmps = list(dir, "*.tmp");
            List<Path> stale = list(dir, "*.stale");
            if (tmps.isEmpty() && stale.isEmpty()) continue;
            if (!tmps.isEmpty()) {
                for (Path t : tmps) Files.delete(t);
                for (Path s : stale) {
                    String n = s.getFileName().toString();
                    Files.move(s, s.resolveSibling(n.substring(0, n.length() - ".stale".length())), StandardCopyOption.ATOMIC_MOVE);
                }
                log.warn("sql.template incremental: rolled back an interrupted replace of {}", dir);
            } else {
                for (Path s : stale) Files.delete(s);
                log.warn("sql.template incremental: completed an interrupted replace of {}", dir);
            }
            deleteIfEmpty(dir);
        }
    }

    private static long count(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static List<Path> list(Path dir, String glob) throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) { ds.forEach(out::add); }
        return out;
    }

    private static void deleteIfEmpty(Path dir) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            if (ds.iterator().hasNext()) return;
        }
        Files.delete(dir);
    }

    private static String safeRun(String runId) {
        String r = runId == null ? "" : runId.replaceAll("[^A-Za-z0-9_-]", "_");
        return (r.isEmpty() ? "run" : r) + "-" + System.currentTimeMillis();
    }
}
