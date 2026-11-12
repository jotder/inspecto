package com.gamma.alert;

import com.gamma.util.DuckDbUtil;
import com.gamma.util.SqlIdent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Evaluates the windowed Alert Rules of one Stream Dataset on each commit (PA-9b, D-PA38/D-PA40/D-PA41).
 *
 * <p><b>State is the window buffer</b> (gate G-RT, PA-9a: a query that globs the Dataset pays for every file the
 * Stream ever left, and misses the stated latency after two hours of drains). The buffer is a DuckDB table holding
 * only the columns the rules read ({@link WindowSql#columns}) and only rows whose windows may still be open. It is
 * derived state, never a second record: {@link #load} rebuilds it at boot from the committed files, and a row is
 * trimmed once every window it falls in has closed past its lateness.
 *
 * <p><b>One evaluation per committed Consignment</b> ({@link #commit}). The caller hands over that Consignment's own
 * committed files — never a folder glob, which also matches {@code .staging/} files a concurrent Consignment is
 * renaming away, and never an arrival high-water mark, which out-of-order commits skip past (PA-9a). Those rows
 * scope the evaluation: only the keys and windows they touch are computed.
 *
 * <p><b>Effectively-once Alerts.</b> Delivery stays at-least-once per slice. With {@code distinctOn} a re-delivered
 * event is not buffered twice, so it cannot inflate a count; and every (rule, key, window start) that has fired is
 * kept in {@link FiredWindows}, so nothing raises twice. Alerts go through {@link AlertService#raiseFromEvent} with
 * the dedupe key {@code <rule>:<key>:<window start>}; a CRITICAL rule's Alert is promoted to an Incident there.
 *
 * <p>One evaluator serves one (Dataset, rule set): every rule names the same Dataset, event-time column and
 * {@code distinctOn}. Not thread-safe across instances on one Dataset; each method is synchronized.
 */
public final class WindowedRuleEvaluator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WindowedRuleEvaluator.class);
    private static final String BUFFER = "window_buffer";
    private static final String FRESH = "window_fresh";

    private final String dataset;
    private final List<AlertRule> rules;
    private final List<String> columns;
    private final String time;
    private final List<String> distinctOn;
    private final long maxSpanUs;
    private final FiredWindows fired;
    private final AlertService alerts;
    private final Connection duck;
    private boolean buffered;
    private long watermarkUs = Long.MIN_VALUE;

    /** One evaluated breach: what fired (or was already recorded) for one key and window. */
    public record Breach(String rule, Map<String, Object> key, long windowStartMs, long windowEndMs, double value,
                         long firstEventMs, long lastEventMs, boolean newlyFired) {}

    /**
     * What one commit did.
     *
     * @param breaches    every breaching (rule, key, window) the commit touched, with {@code newlyFired} false when it
     *                    had already fired
     * @param lateEvents  per rule, the committed rows that matched its {@code when} but fell only in closed windows
     * @param watermarkUs the event-time Watermark after the commit (epoch µs)
     */
    public record Outcome(List<Breach> breaches, Map<String, Long> lateEvents, long watermarkUs) {
        /** The breaches that fired for the first time in this commit (one Alert per key may stand for several windows). */
        public List<Breach> newlyFired() {
            return breaches.stream().filter(Breach::newlyFired).toList();
        }
    }

    /**
     * @param readRoots the folders the committed files live under; the buffer's DuckDB connection can read nowhere else
     * @param spillDir  the DuckDB spill folder ({@code null}: the JVM temp folder's {@code .duckdb_tmp})
     */
    public WindowedRuleEvaluator(String dataset, List<AlertRule> rules, List<Path> readRoots, Path spillDir,
                                 FiredWindows fired, AlertService alerts) throws SQLException {
        if (rules == null || rules.isEmpty()) throw new IllegalArgumentException("a window evaluator needs at least one rule");
        AlertWindow first = rules.get(0).eventWindow();
        for (AlertRule r : rules) {
            if (!r.isWindowed() || !r.dataset().equals(dataset))
                throw new IllegalArgumentException("rule '" + r.name() + "' is not a windowed rule over dataset '" + dataset + "'");
            if (!r.eventWindow().time().equals(first.time()) || !r.eventWindow().distinctOn().equals(first.distinctOn()))
                throw new IllegalArgumentException("the windowed rules of one evaluator must share the event-time column "
                        + "and distinctOn; rule '" + r.name() + "' does not");
        }
        this.dataset = dataset;
        this.rules = List.copyOf(rules);
        this.columns = WindowSql.columns(this.rules);
        this.time = first.time();
        this.distinctOn = first.distinctOn();
        this.maxSpanUs = this.rules.stream()
                .mapToLong(r -> WindowSql.micros(r.eventWindow().size().plus(r.eventWindow().lateness()))).max().orElseThrow();
        this.fired = fired;
        this.alerts = alerts;
        this.duck = DuckDbUtil.openInMemory(spillDir, readRoots);
    }

    /**
     * Every committed Parquet file under {@code folder}, sorted: a file in a folder whose name starts with a dot
     * ({@code .staging/<consignment>/…}) is in flight, not committed, and is never listed. For the boot rebuild.
     */
    public static List<Path> committedFiles(Path folder) throws IOException {
        if (folder == null || !Files.isDirectory(folder)) return List.of();
        try (Stream<Path> w = Files.walk(folder)) {
            return w.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".parquet"))
                    .filter(p -> {
                        Path rel = folder.relativize(p);
                        for (int i = 0; i < rel.getNameCount() - 1; i++)
                            if (rel.getName(i).toString().startsWith(".")) return false;
                        return true;
                    })
                    .sorted().toList();
        }
    }

    /** Rebuild the buffer from committed files at boot: buffered, the Watermark advanced, nothing evaluated. */
    public synchronized void load(List<Path> committed) throws SQLException {
        append(committed);
        trim();
    }

    /** Evaluate one committed Consignment's files (see the class doc). */
    public synchronized Outcome commit(List<Path> consignmentFiles) throws SQLException {
        long before = watermarkUs;
        if (!append(consignmentFiles)) return new Outcome(List.of(), Map.of(), watermarkUs);
        List<Breach> out = new ArrayList<>();
        Map<String, Long> late = new LinkedHashMap<>();
        long nowMs = System.currentTimeMillis();
        for (AlertRule rule : rules) {
            late.put(rule.name(), lateRows(rule, before));
            List<Breach> found = breaches(rule, before);
            // the newly breaching windows, grouped by key in window order: a burst in a sliding window breaches every
            // window it falls in at once, and raises ONE Alert for its key (the earliest window), not one per window
            Map<String, List<Breach>> fresh = new LinkedHashMap<>();
            for (Breach b : found) {
                String keyId = AlertService.keyId(b.key());
                if (!fired.contains(rule.name(), keyId, b.windowStartMs()))
                    fresh.computeIfAbsent(keyId, k -> new ArrayList<>()).add(b);
            }
            boolean storm = rule.isGrouped() && fresh.size() > rule.stormCap();
            if (storm) raiseStorm(rule, fresh.values().stream().map(l -> l.get(0)).toList(), nowMs);
            else for (List<Breach> windows : fresh.values()) raise(rule, windows.get(0), windows.size(), nowMs);
            for (Breach b : found) {
                List<Breach> keyWindows = fresh.get(AlertService.keyId(b.key()));
                boolean isNew = keyWindows != null && keyWindows.contains(b);
                if (isNew) fired.record(rule.name(), AlertService.keyId(b.key()), b.windowStartMs(), nowMs);
                out.add(isNew ? b : new Breach(b.rule(), b.key(), b.windowStartMs(), b.windowEndMs(), b.value(),
                        b.firstEventMs(), b.lastEventMs(), false));
            }
            AlertWindow w = rule.eventWindow();
            // a window whose end + lateness is at or below the Watermark is never evaluated again
            if (watermarkUs != Long.MIN_VALUE) fired.purgeBefore(rule.name(), (watermarkUs - WindowSql.micros(w.size().plus(w.lateness()))) / 1000);
        }
        trim();
        return new Outcome(out, late, watermarkUs);
    }

    /** The event-time Watermark the buffer has reached (epoch µs; {@link Long#MIN_VALUE} before any row). */
    public synchronized long watermarkUs() {
        return watermarkUs;
    }

    /** How many rows the buffer holds. */
    public synchronized long bufferedRows() throws SQLException {
        if (!buffered) return 0;
        try (Statement st = duck.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + SqlIdent.q(BUFFER))) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Read {@code files} into the fresh table (the rule columns only, rows with an event time only, one row per
     * {@code distinctOn} identity not already buffered), append it to the buffer and advance the Watermark.
     * {@code false} when no file was given.
     */
    private boolean append(List<Path> files) throws SQLException {
        if (files == null || files.isEmpty()) return false;
        List<String> paths = files.stream().map(p -> p.toAbsolutePath().normalize().toString()).toList();
        String cols = String.join(", ", columns.stream().map(SqlIdent::q).toList());
        String ts = SqlIdent.q(time);
        try (Statement st = duck.createStatement()) {
            // the Dataset's own store read (union by name, hive keys as VARCHAR), over the named files only
            String read = "SELECT " + cols + " FROM " + com.gamma.sql.SqlViews.reader("PARQUET", paths, true)
                    + " WHERE " + ts + " IS NOT NULL";
            if (!distinctOn.isEmpty())
                read += " QUALIFY row_number() OVER (PARTITION BY "
                        + String.join(", ", distinctOn.stream().map(SqlIdent::q).toList()) + ") = 1";
            st.execute("CREATE OR REPLACE TEMP TABLE " + SqlIdent.q(FRESH) + " AS " + read);
            if (!buffered) {
                st.execute("CREATE TEMP TABLE " + SqlIdent.q(BUFFER) + " AS SELECT * FROM " + SqlIdent.q(FRESH) + " LIMIT 0");
                buffered = true;
            }
            if (!distinctOn.isEmpty()) {
                // at-least-once delivery: a re-delivered event is already buffered and must not count twice
                List<String> same = distinctOn.stream()
                        .map(c -> "b." + SqlIdent.q(c) + " IS NOT DISTINCT FROM " + SqlIdent.q(FRESH) + "." + SqlIdent.q(c)).toList();
                st.execute("DELETE FROM " + SqlIdent.q(FRESH) + " WHERE EXISTS (SELECT 1 FROM " + SqlIdent.q(BUFFER)
                        + " AS b WHERE " + String.join(" AND ", same) + ")");
            }
            st.execute("INSERT INTO " + SqlIdent.q(BUFFER) + " SELECT * FROM " + SqlIdent.q(FRESH));
            try (ResultSet rs = st.executeQuery("SELECT max(epoch_us(" + ts + ")) FROM " + SqlIdent.q(FRESH))) {
                rs.next();
                long max = rs.getLong(1);
                if (!rs.wasNull()) watermarkUs = Math.max(watermarkUs, max);
            }
        }
        return true;
    }

    /** Drop the rows every window of every rule has closed on: {@code time + size + lateness <= watermark}. */
    private void trim() throws SQLException {
        if (!buffered || watermarkUs == Long.MIN_VALUE) return;
        try (PreparedStatement ps = duck.prepareStatement("DELETE FROM " + SqlIdent.q(BUFFER) + " WHERE epoch_us("
                + SqlIdent.q(time) + ") + " + maxSpanUs + " <= ?")) {
            ps.setLong(1, watermarkUs);
            ps.executeUpdate();
        }
    }

    private List<Breach> breaches(AlertRule rule, long before) throws SQLException {
        WindowSql.Compiled c = WindowSql.breaches(rule, BUFFER, FRESH, before);
        long sizeMs = rule.eventWindow().size().toMillis();
        List<Breach> out = new ArrayList<>();
        try (PreparedStatement ps = prepare(c); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> key = new LinkedHashMap<>();
                for (int i = 0; i < rule.by().size(); i++) key.put(rule.by().get(i), rs.getObject(WindowSql.keyColumn(i)));
                long startMs = rs.getLong(WindowSql.WINDOW_START) / 1000;
                out.add(new Breach(rule.name(), key, startMs, startMs + sizeMs, rs.getDouble(WindowSql.VALUE),
                        rs.getLong(WindowSql.FIRST) / 1000, rs.getLong(WindowSql.LAST) / 1000, true));
            }
        }
        return out;
    }

    private long lateRows(AlertRule rule, long before) throws SQLException {
        if (before == Long.MIN_VALUE) return 0;
        try (PreparedStatement ps = prepare(WindowSql.lateRows(rule, FRESH, before)); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private PreparedStatement prepare(WindowSql.Compiled c) throws SQLException {
        PreparedStatement ps = duck.prepareStatement(c.sql());
        for (int i = 0; i < c.params().size(); i++) ps.setObject(i + 1, c.params().get(i));
        return ps;
    }

    /** One Alert for a key's earliest newly breaching window; {@code windows} says how many windows breached with it. */
    private void raise(AlertRule rule, Breach b, int windows, long nowMs) {
        String keyLabel = b.key().isEmpty() ? dataset : AlertService.keyLabel(b.key());
        String window = Instant.ofEpochMilli(b.windowStartMs()) + " .. " + Instant.ofEpochMilli(b.windowEndMs());
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("rule", rule.name());
        evidence.put("dataset", dataset);
        if (!b.key().isEmpty()) evidence.put("key", keyLabel);
        evidence.put("windowStart", Instant.ofEpochMilli(b.windowStartMs()).toString());
        evidence.put("windowEnd", Instant.ofEpochMilli(b.windowEndMs()).toString());
        evidence.put("measure", rule.measure());
        evidence.put("value", String.valueOf(b.value()));
        evidence.put("firstEvent", Instant.ofEpochMilli(b.firstEventMs()).toString());
        evidence.put("lastEvent", Instant.ofEpochMilli(b.lastEventMs()).toString());
        evidence.put("windows", String.valueOf(windows));
        String message = rule.measure() + " = " + number(b.value()) + " (" + rule.comparator() + " " + number(rule.threshold())
                + ") for " + keyLabel + " in the window " + window
                + (windows > 1 ? " (and " + (windows - 1) + " overlapping window(s))" : "");
        Alert alert = new Alert(rule.name(), rule.severity(), dataset, rule.measure(), b.value(), rule.comparator(),
                rule.threshold(), AlertWindow.text(rule.eventWindow().size()), nowMs, message, evidence);
        String title = b.key().isEmpty() ? Alert.title(rule, dataset) : Alert.keyTitle(rule, dataset, keyLabel);
        alerts.raiseFromEvent(alert, title, dataset,
                rule.name() + ":" + AlertService.keyId(b.key()) + ":" + b.windowStartMs(), evidence,
                "CRITICAL".equals(rule.severity()));
    }

    /** Above the rule's {@code stormCap}, one Alert reports how many keys breached instead of one per key. */
    private void raiseStorm(AlertRule rule, List<Breach> fresh, long nowMs) {
        long firstWindow = fresh.stream().mapToLong(Breach::windowStartMs).min().orElseThrow();
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("rule", rule.name());
        evidence.put("dataset", dataset);
        evidence.put("storm", "true");
        evidence.put("stormCap", String.valueOf(rule.stormCap()));
        evidence.put("breachedKeys", String.valueOf(fresh.size()));
        evidence.put("windowStart", Instant.ofEpochMilli(firstWindow).toString());
        String message = fresh.size() + " keys breached " + rule.measure() + " " + rule.comparator() + " "
                + number(rule.threshold()) + " in one commit (above the storm cap of " + rule.stormCap() + ")";
        Alert alert = new Alert(rule.name(), rule.severity(), dataset, rule.measure(), fresh.size(), rule.comparator(),
                rule.threshold(), AlertWindow.text(rule.eventWindow().size()), nowMs, message, evidence);
        alerts.raiseFromEvent(alert, Alert.title(rule, dataset), dataset, rule.name() + ":storm:" + firstWindow, evidence,
                "CRITICAL".equals(rule.severity()));
    }

    private static String number(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    @Override
    public synchronized void close() {
        try {
            duck.close();
        } catch (SQLException e) {
            log.debug("window buffer of '{}' did not close cleanly: {}", dataset, e.getMessage());
        }
    }
}
