package com.gamma.query;

import com.gamma.alert.ScoreOutputDirs;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.SqlIdent;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * KPI History (`BI-INSIGHTS-1` P0, I-3): a KPI's value per period at its own grain, kept for {@value #RETENTION_YEARS}
 * years (D-BI10) in one parquet file per KPI under {@code <dataDir>/kpi_history_<kpi>/}. The period still in progress
 * is stored with {@code complete = false} and rewritten by every run until it closes (D-BI12).
 *
 * <p>A run evaluates the periods the history lacks, plus the current and the previous period (late data restates
 * one closed period), from the later of the data's first day and the retention start — a period before the data
 * began has no value, never a {@code count} of 0. The merged history replaces the file atomically
 * ({@link ScoreOutputDirs#swapIn}, which waits for readers holding the snapshot-swap lock).
 *
 * <p>Every row records the KPI {@link #definition} it was computed under; a run keeps only the rows of the KPI as it is
 * defined now, so a changed grain, Measure, zone, Dataset, time field or target rebuilds the whole history instead of
 * mixing periods of two definitions.
 *
 * <p>Who may run it and whether the Measure may be stored are the caller's decisions (the {@code kpi.snapshot} Job:
 * its author must view the Dataset, and a Measure that reveals a masked column is refused); nothing here checks them.
 */
public final class KpiHistory {

    private KpiHistory() {}

    /** The reserved store prefix: {@code kpi_history_<kpi>} is the history store and its Dataset id. */
    public static final String PREFIX = "kpi_history_";
    /** The marker file that says a history directory belongs to a KPI. */
    public static final String MARKER = ".kpi-history-output";
    public static final int RETENTION_YEARS = 3;
    static final String GLOB = "kpi-history-*.parquet";

    /** One period. {@code periodEnd} is exclusive; {@code value} is {@code null} when the period holds no value. */
    public record Row(LocalDate periodStart, LocalDate periodEnd, Double value, String rag, boolean complete) {}

    /** What one run did: periods evaluated, rows the history holds now, and its directory. */
    public record Summary(int evaluated, int rows, Path store) {}

    /** What a stored value depends on: rows computed under another definition are never kept. */
    static String definition(KpiDefinition kpi) {
        return String.join("|", kpi.dataset(), kpi.measureText(), kpi.timeField(), kpi.grain().wire(),
                kpi.zone().getId(), String.valueOf(kpi.direction()), String.valueOf(kpi.target()), String.valueOf(kpi.bands()));
    }

    public static String store(String kpiId) {
        return PREFIX + kpiId;
    }

    /** The first period start the history keeps as of {@code asOf}. */
    static LocalDate retentionStart(KpiDefinition.Grain g, LocalDate asOf) {
        return g.start(asOf.minusYears(RETENTION_YEARS));
    }

    /**
     * The period starts a run evaluates, oldest first: every period from the later of the data's first period and the
     * retention start up to the current one, except a period already stored complete — unless it is the previous
     * period, which is always restated. No data ({@code earliest == null}) evaluates nothing.
     */
    static List<LocalDate> periodsToEvaluate(KpiDefinition.Grain g, LocalDate earliest, LocalDate asOf,
                                             Set<LocalDate> storedComplete) {
        if (earliest == null) return List.of();
        LocalDate current = g.start(asOf);
        LocalDate previous = g.plus(current, -1);
        LocalDate from = retentionStart(g, asOf);
        if (g.start(earliest).isAfter(from)) from = g.start(earliest);
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(current); d = g.plus(d, 1))
            if (!storedComplete.contains(d) || !d.isBefore(previous)) out.add(d);
        return out;
    }

    /**
     * One period's row as of {@code asOf} (today in the KPI's zone): a period that ended before {@code asOf} is complete
     * and evaluated whole; the current one is evaluated to date and marked incomplete.
     */
    static Row evaluate(KpiDefinition kpi, String relationSql, LocalDate periodStart, LocalDate asOf)
            throws SQLException, IOException {
        LocalDate end = kpi.grain().plus(periodStart, 1);
        boolean complete = !end.isAfter(asOf);
        LocalDate to = complete ? end : asOf.plusDays(1);
        Double value = KpiEvaluator.valueOf(kpi, relationSql, new KpiDefinition.Window(periodStart, to));
        KpiDefinition.Rag rag = kpi.rag(value);
        return new Row(periodStart, end, value, rag == null ? null : rag.name(), complete);
    }

    /** {@code stored} with {@code fresh} laid over it by period, rows before the retention start dropped, oldest first. */
    static List<Row> merge(List<Row> stored, List<Row> fresh, LocalDate retentionStart) {
        TreeMap<LocalDate, Row> byPeriod = new TreeMap<>();
        for (Row r : stored) byPeriod.put(r.periodStart(), r);
        for (Row r : fresh) byPeriod.put(r.periodStart(), r);
        return new ArrayList<>(byPeriod.tailMap(retentionStart, true).values());
    }

    /**
     * Evaluate and store one KPI's history as of {@code asOf}. Runs of one KPI take turns in this JVM; the history
     * directory must be absent or this KPI's own ({@link ScoreOutputDirs#claim}).
     *
     * @param relationSql the source Dataset's trusted relation ({@link DatasetRelation#relationSql}), already
     *                    cleared for the caller
     */
    public static Summary snapshot(Path dataDir, KpiDefinition kpi, String relationSql, LocalDate asOf, Instant at)
            throws SQLException, IOException {
        Path dir = claim(dataDir, kpi.name());
        synchronized (RUNS.computeIfAbsent(dir.toAbsolutePath(), k -> new Object())) {
            cleanLeftovers(dir);
            List<Row> stored = read(dir, definition(kpi));
            Set<LocalDate> storedComplete = stored.stream().filter(Row::complete).map(Row::periodStart)
                    .collect(Collectors.toSet());
            LocalDate earliest = KpiEvaluator.earliestDay(kpi, relationSql);
            List<Row> fresh = new ArrayList<>();
            for (LocalDate p : periodsToEvaluate(kpi.grain(), earliest, asOf, storedComplete))
                fresh.add(evaluate(kpi, relationSql, p, asOf));
            List<Row> rows = merge(stored, fresh, retentionStart(kpi.grain(), asOf));
            write(dir, kpi, rows, at);
            return new Summary(fresh.size(), rows.size(), dir);
        }
    }

    /**
     * The KPI's history directory, created with this KPI's marker or accepted when the marker already names it; a
     * directory another owner made is refused untouched ({@link ScoreOutputDirs#claim}). A caller that registers the
     * history as a Dataset claims first, so a refused run registers nothing.
     */
    public static Path claim(Path dataDir, String kpiId) throws IOException {
        Path root = dataDir.normalize();
        Path dir = root.resolve(store(kpiId)).normalize();
        if (!dir.startsWith(root) || dir.equals(root))
            throw new IllegalArgumentException("kpi history store escapes the data root");
        ScoreOutputDirs.claim(dir, MARKER, "kpi", kpiId);
        return dir;
    }

    private static final Map<Path, Object> RUNS = new ConcurrentHashMap<>();

    /** The stored rows computed under {@code definition}, oldest first; empty when nothing was written yet. */
    static List<Row> read(Path dir, String definition) throws SQLException, IOException {
        boolean any;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, GLOB)) {
            any = files.iterator().hasNext();
        }
        if (!any) return List.of();
        List<Row> out = new ArrayList<>();
        try (Connection c = DuckDbUtil.openInMemory(DuckDbUtil.spillDirUnder(dir.getParent()), List.of(dir));
             PreparedStatement ps = c.prepareStatement("SELECT CAST(period_start AS VARCHAR) AS s, CAST(period_end AS VARCHAR) AS e, "
                     + "value, rag, complete FROM " + com.gamma.sql.SqlViews.parquet(
                     SqlIdent.sqlStr(dir.toString().replace('\\', '/') + "/" + GLOB), false, com.gamma.sql.SqlViews.Hive.OFF)
                     + " WHERE definition = ? ORDER BY period_start")) {
            ps.setString(1, definition);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double v = rs.getDouble("value");
                    Double value = rs.wasNull() ? null : v;
                    out.add(new Row(LocalDate.parse(rs.getString("s")), LocalDate.parse(rs.getString("e")), value,
                            rs.getString("rag"), rs.getBoolean("complete")));
                }
            }
        }
        return out;
    }

    private static void write(Path dir, KpiDefinition kpi, List<Row> rows, Instant at) throws SQLException, IOException {
        String name = "kpi-history-" + at.toEpochMilli() + ".parquet";
        Path tmp = dir.resolve(name + ".tmp");
        try (Connection c = DuckDbUtil.openInMemory(DuckDbUtil.spillDirUnder(dir.getParent()), List.of(dir));
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE h (kpi_id VARCHAR, grain VARCHAR, period_start DATE, period_end DATE, value DOUBLE, "
                    + "rag VARCHAR, complete BOOLEAN, snapshot_at TIMESTAMP, definition VARCHAR)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO h VALUES (?, ?, CAST(? AS DATE), CAST(? AS DATE), ?, ?, ?, ?, ?)")) {
                Timestamp ts = Timestamp.from(at);
                String def = definition(kpi);
                for (Row r : rows) {
                    ps.setString(1, kpi.name());
                    ps.setString(2, kpi.grain().wire());
                    ps.setString(3, r.periodStart().toString());
                    ps.setString(4, r.periodEnd().toString());
                    if (r.value() == null) ps.setNull(5, java.sql.Types.DOUBLE);
                    else ps.setDouble(5, r.value());
                    ps.setString(6, r.rag());
                    ps.setBoolean(7, r.complete());
                    ps.setTimestamp(8, ts);
                    ps.setString(9, def);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            st.execute("COPY h TO " + path(tmp) + " (FORMAT PARQUET)");
        }
        ScoreOutputDirs.swapIn(dir, tmp, name, GLOB);
    }

    private static void cleanLeftovers(Path dir) throws IOException {
        try (DirectoryStream<Path> junk = Files.newDirectoryStream(dir, "*.{tmp,stale}")) {
            for (Path p : junk) Files.deleteIfExists(p);
        }
    }

    private static String path(Path p) {
        return SqlIdent.sqlStr(p.toString().replace('\\', '/'));
    }
}
