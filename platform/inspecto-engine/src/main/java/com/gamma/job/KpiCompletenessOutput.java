package com.gamma.job;

import com.gamma.util.DuckDbUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The {@code kpi_completeness} Job-output store (operator, 2026-10-09: Signals behind a Dataset): ONE Parquet file
 * under {@code <dataRoot>/kpi_completeness/}, one row per Pipeline-day, UPSERTED — a re-run of the same day replaces
 * that day's row, never appends a second. A {@code kpi_completeness} Dataset ({@code physicalRef: kpi_completeness})
 * reads it, and an ordinary Widget renders it.
 *
 * <p>Ownership follows the Risk Score output pattern ({@code RiskScoreOutputs}): the directory is created with the
 * {@link #OWNER_MARKER} file; an existing directory WITHOUT that marker is refused untouched, so the Job never
 * writes into a directory something else made. Writes are whole-file: read the current rows, drop the
 * Pipeline-day, add the new row, {@code COPY} to a temp file, atomic move over the old one.
 */
public final class KpiCompletenessOutput {

    /** The store's directory name under the Space data root, and the Dataset's {@code physicalRef}. */
    public static final String DIR = "kpi_completeness";
    public static final String FILE = "kpi_completeness.parquet";
    static final String OWNER_MARKER = ".kpi-completeness-output";
    private static final String OWNER = "kpi.completeness";

    private KpiCompletenessOutput() {}

    /** Upsert the Pipeline-day row carried by a {@code kpi.completeness.evaluated} payload. */
    public static synchronized Path upsert(Path dataRoot, Map<String, Object> payload, Instant evaluatedAt)
            throws IOException, SQLException {
        Path dir = dataRoot.resolve(DIR).normalize();
        claim(dir);
        Path file = dir.resolve(FILE);
        Path tmp = dir.resolve(FILE + ".tmp");
        Files.deleteIfExists(tmp);
        try (Connection c = DuckDbUtil.openInMemory(DuckDbUtil.spillDirUnder(dataRoot), List.of(dir));
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE k (pipeline VARCHAR, record_day DATE, status VARCHAR, rows BIGINT, "
                    + "baseline_rows BIGINT, deviation DOUBLE, missing_files BIGINT, unknown_streak_days INTEGER, "
                    + "evaluated_at TIMESTAMP)");
            if (Files.exists(file)) st.execute("INSERT INTO k SELECT * FROM read_parquet(" + sqlStr(file) + ")");
            try (PreparedStatement del = c.prepareStatement("DELETE FROM k WHERE pipeline = ? AND record_day = CAST(? AS DATE)")) {
                del.setString(1, (String) payload.get("pipeline"));
                del.setString(2, (String) payload.get("recordDay"));
                del.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO k VALUES (?, CAST(? AS DATE), ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, (String) payload.get("pipeline"));
                ps.setString(2, (String) payload.get("recordDay"));
                ps.setString(3, (String) payload.get("status"));
                setLong(ps, 4, payload.get("rows"));
                setLong(ps, 5, payload.get("baselineRows"));
                if (payload.get("deviation") instanceof Number d) ps.setDouble(6, d.doubleValue());
                else ps.setNull(6, Types.DOUBLE);
                setLong(ps, 7, payload.get("missingFiles"));
                if (payload.get("unknownStreakDays") instanceof Number n) ps.setInt(8, n.intValue());
                else ps.setNull(8, Types.INTEGER);
                ps.setTimestamp(9, Timestamp.from(evaluatedAt));
                ps.executeUpdate();
            }
            st.execute("COPY (SELECT * FROM k ORDER BY pipeline, record_day) TO " + sqlStr(tmp) + " (FORMAT PARQUET)");
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return file;
    }

    /** Create {@code dir} with the marker, or accept it when the marker is ours; anything else is refused. */
    static void claim(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(OWNER_MARKER), OWNER);
            return;
        }
        Path marker = dir.resolve(OWNER_MARKER);
        if (!Files.isRegularFile(marker) || !OWNER.equals(Files.readString(marker).trim()))
            throw new IllegalStateException(OWNER + " refuses to write into '" + dir.getFileName()
                    + "': the directory exists and was not created by this Job");
    }

    private static void setLong(PreparedStatement ps, int i, Object v) throws SQLException {
        if (v instanceof Number n) ps.setLong(i, n.longValue());
        else ps.setNull(i, Types.BIGINT);
    }

    private static String sqlStr(Path p) {
        return "'" + p.toAbsolutePath().toString().replace('\\', '/').replace("'", "''") + "'";
    }
}
