package com.gamma.control;

import com.gamma.config.safety.PathJail;
import com.gamma.sql.SqlViews;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.SqlIdent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * LA-ARRIVAL-MODES-1 <i>Replace the period it covers</i> (plan {@code la-data-arrival-plan.md} §4, P2): after an upload's
 * rows were ADDED, the rows of every OTHER Parquet file of the Dataset that fall on a day the upload covers are removed -
 * so a corrected week replaces exactly those days and every other day stays. Days are the calendar dates of the
 * Dataset's {@code eventDate} column ({@code CAST(… AS DATE)}, the value as stored, no zone shift); a row with no event
 * date is never removed.
 *
 * <p>Each touched file is rewritten beside itself, the original moved to {@code <id>/database.period-<stamp>/} first, so
 * {@link #restore} puts every original back and {@link #drop} discards them once the import has succeeded. A file left
 * with no row is removed (its arrival then reads as replaced). All reads and writes are sealed to {@code <dataRoot>/<id>}.
 */
final class DatasetPeriod {

    /** What the replacement did: the days the upload covers, the rows it removed, the files it rewrote or emptied. */
    record Result(List<String> days, long rowsRemoved, int filesTouched) { }

    private final Path base, database, aside;
    private final List<Path[]> moved = new ArrayList<>();   // {original path, its copy in the aside dir}

    DatasetPeriod(Path dataRoot, String id, String stamp) {
        this.base = dataRoot.resolve(id).toAbsolutePath().normalize();
        this.database = base.resolve("database");
        this.aside = base.resolve("database.period-" + stamp);
    }

    /**
     * Remove, from every Parquet file of the Dataset except {@code fresh} (the files this run wrote), the rows on a day
     * that {@code covering} (the upload's own output) holds. {@code covering} empty → nothing is removed.
     */
    Result replace(List<Path> covering, List<Path> fresh, String eventDate) throws IOException {
        if (covering.isEmpty()) return new Result(List.of(), 0, 0);
        String col = SqlIdent.q(eventDate);
        try (Connection duck = open(); Statement st = duck.createStatement()) {
            List<String> days = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT DISTINCT CAST(CAST(" + col + " AS DATE) AS VARCHAR) d FROM "
                    + source(covering) + " WHERE " + col + " IS NOT NULL ORDER BY d")) {
                while (rs.next()) days.add(rs.getString(1));
            }
            if (days.isEmpty()) return new Result(days, 0, 0);
            String in = "CAST(" + col + " AS DATE) IN (" + String.join(", ", days.stream().map(d -> "DATE " + SqlIdent.sqlStr(d)).toList()) + ")";
            long removed = 0;
            int touched = 0;
            List<Path> others;
            try (Stream<Path> w = Files.walk(database)) {
                others = w.filter(p -> p.getFileName().toString().endsWith(".parquet"))
                        .map(p -> p.toAbsolutePath().normalize())
                        .filter(p -> fresh.stream().noneMatch(f -> f.toAbsolutePath().normalize().equals(p)))
                        .sorted().toList();
            }
            for (Path f : others) {
                long hit;
                try (ResultSet rs = st.executeQuery("SELECT count(*) FILTER (WHERE " + in + "), count(*) FROM " + source(List.of(f)))) {
                    rs.next();
                    hit = rs.getLong(1);
                    if (hit == 0) continue;
                    removed += hit;
                    touched++;
                    boolean empties = hit == rs.getLong(2);
                    // the rewrite is named so no Parquet glob reads it, and never left behind
                    Path tmp = f.resolveSibling(".period-" + f.getFileName() + ".tmp");
                    try {
                        if (!empties)
                            st.execute("COPY (SELECT * FROM " + source(List.of(f)) + " WHERE " + col + " IS NULL OR NOT (" + in + ")) TO "
                                    + SqlIdent.pathStr(tmp) + " (FORMAT PARQUET)");
                        Path kept = aside.resolve(database.relativize(f));
                        Files.createDirectories(kept.getParent());
                        Files.move(f, kept, StandardCopyOption.ATOMIC_MOVE);
                        moved.add(new Path[] {f, kept});
                        if (!empties) Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE);
                    } finally {
                        Files.deleteIfExists(tmp);
                    }
                }
            }
            return new Result(days, removed, touched);
        } catch (SQLException failed) {
            throw new IOException("the period could not be replaced: " + failed.getMessage(), failed);
        }
    }

    /** Put every original back (the import failed after {@link #replace}). */
    void restore() throws IOException {
        for (int i = moved.size() - 1; i >= 0; i--) {
            Path[] m = moved.get(i);
            Files.deleteIfExists(m[0]);
            Files.move(m[1], m[0], StandardCopyOption.ATOMIC_MOVE);
        }
        moved.clear();
        OnboardRoutes.deleteTree(aside);
    }

    /** The import succeeded: the originals are no longer needed. */
    void drop() {
        moved.clear();
        OnboardRoutes.deleteTree(aside);
    }

    private Connection open() throws SQLException {
        try {
            DuckDbUtil.loadDriver();
        } catch (ClassNotFoundException missing) {
            throw new SQLException("DuckDB is not available", missing);
        }
        return DuckDbUtil.openInMemory(null, List.of(base));
    }

    private String source(List<Path> files) {
        for (Path f : files)
            if (!PathJail.contains(base, f.toAbsolutePath().normalize()))
                throw new IllegalArgumentException("a file outside the Dataset: " + f.getFileName());
        return SqlViews.parquet(SqlViews.pathList(files.stream().map(Path::toString).toList()), true, SqlViews.Hive.OFF);
    }

    static List<Path> outputs(Map<String, String> ledgerRow) {
        List<Path> out = new ArrayList<>();
        for (String p : ledgerRow.getOrDefault("output_paths", "").split(";"))
            if (!p.isBlank()) out.add(Path.of(p.trim()));
        return out;
    }
}
