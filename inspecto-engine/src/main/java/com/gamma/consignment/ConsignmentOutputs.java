package com.gamma.consignment;

import com.gamma.api.PublicApi;
import com.gamma.etl.LineageRow;
import com.gamma.etl.PartitionOutput;
import com.gamma.etl.TransformCompiler;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * <b>§11.3 slice 2 — turning ephemeral {@link PartitionOutput}s into registry rows.</b> The pure mapping half of
 * the output registry: {@link DbConsignmentOutputStore} persists {@link ConsignmentOutput}s, and this builds them
 * from what a write path actually has in scope.
 *
 * <p><b>Why two builders.</b> {@code row_count} cannot be copied from anywhere — a multi-file partitioned
 * {@code COPY} reports no per-file count back, so {@code PartitionWriter.reveal()} fills only
 * {@code (partition, outputFile, bytes)}. The count therefore has to come from a {@code COUNT(*)}, and the write
 * paths differ in whether one has already been run:
 * <ul>
 *   <li>the ingest path already computes {@code LineageCollector}'s per-{@code (srcId, partition)} matrix, so
 *       {@link #fromLineage} sums it per output file for free — no extra query;</li>
 *   <li>the enrichment and Pipeline-sink paths compute only a whole-run total, so they need
 *       {@link #countByPartition} first and then {@link #fromPartitionCounts}.</li>
 * </ul>
 * Both end at the same per-file number, which is what makes §7.2's reconciliation (summed {@code row_count} ==
 * detail count) hold across every path rather than just one.
 *
 * <p><b>One output file per partition per write.</b> Both builders key counts by partition, which is sound
 * because {@code PartitionWriter.reveal()} renames every staged file of a partition onto a single
 * {@code <baseName>_out.<ext>}. A multi-destination fan-out writes the same partition once per destination — each
 * file genuinely holds those rows, so each registry row correctly repeats the count.
 */
@PublicApi(since = "5.0.0")
public final class ConsignmentOutputs {

    private ConsignmentOutputs() {}

    /**
     * Registry rows for a write whose lineage matrix is already computed (the ingest path): the count for an
     * output file is the sum of every {@link LineageRow} naming it.
     *
     * <p>Lineage rows with a blank {@code outputFile} are ignored — {@code LineageCollector} emits those when a
     * partition present in the table had no corresponding revealed file, and they belong to no registry row.
     */
    public static List<ConsignmentOutput> fromLineage(String consignmentId, String runId, String tableName,
                                                      List<PartitionOutput> outputs, List<LineageRow> lineage) {
        return fromLineage(consignmentId, runId, tableName, outputs, lineage, null);
    }

    /**
     * {@link #fromLineage(String, String, String, List, List)} with the §3.4.3 schema fingerprint of the
     * resolved schema that wrote the Consignment stamped on every row ({@code null} when unknown).
     */
    public static List<ConsignmentOutput> fromLineage(String consignmentId, String runId, String tableName,
                                                      List<PartitionOutput> outputs, List<LineageRow> lineage,
                                                      String schemaFingerprint) {
        return fromLineage(consignmentId, runId, tableName, outputs, lineage, schemaFingerprint, null, null);
    }

    /**
     * {@link #fromLineage(String, String, String, List, List, String)} with §3.1's addressing columns: the
     * event-time {@code bounds} <b>keyed by output file</b>, and the {@code producer} that wrote it.
     *
     * <p><b>Why by file and not by partition</b>, when {@link #boundsByPartition} produces partition keys: a
     * Consignment's outputs are not all written from the same relation. Decision-rule routing writes rejected
     * rows to their own destination, and those files can land under a partition key the main write also used —
     * so a partition-keyed lookup would hand a routed file the main relation's event-time range. The caller
     * joins bounds onto only the outputs of the relation it measured; anything else is simply absent here,
     * which reads as "unknown" rather than as a range that is quietly wrong.
     */
    public static List<ConsignmentOutput> fromLineage(String consignmentId, String runId, String tableName,
                                                      List<PartitionOutput> outputs, List<LineageRow> lineage,
                                                      String schemaFingerprint,
                                                      Map<String, EventTimeBounds> bounds, String producer) {
        Map<String, Long> byPath = new HashMap<>();
        if (lineage != null)
            for (LineageRow r : lineage) {
                if (r.outputFile() == null || r.outputFile().isBlank()) continue;
                byPath.merge(r.outputFile(), r.rowCount(), Long::sum);
            }
        return build(consignmentId, runId, tableName, outputs,
                o -> byPath.getOrDefault(o.outputFile(), 0L), schemaFingerprint, bounds, producer);
    }

    /**
     * Registry rows for a write with no lineage matrix (enrichment, Pipeline sinks), given the per-partition
     * counts from {@link #countByPartition} over the same relation that was written.
     */
    public static List<ConsignmentOutput> fromPartitionCounts(String consignmentId, String runId, String tableName,
                                                              List<PartitionOutput> outputs,
                                                              Map<String, Long> rowsByPartition) {
        return fromPartitionCounts(consignmentId, runId, tableName, outputs, rowsByPartition, null, null);
    }

    /**
     * {@link #fromPartitionCounts(String, String, String, List, Map)} with addressing §3.1's columns — the
     * event-time {@code bounds} <b>keyed by partition</b>, and the {@code producer}.
     *
     * <p><b>Keyed by partition here, unlike {@link #fromLineage}</b>, which keys bounds by output file. That
     * asymmetry is not an oversight: {@code fromLineage} serves the ingest path, where decision-rule routing
     * writes rejected rows to their own destination under a partition key the main write may also have used, so
     * a partition-keyed lookup could hand a routed file the main relation's range. This path writes one relation
     * per destination, so the partition key is unambiguous and is what {@link #boundsByPartition} already
     * produces.
     */
    public static List<ConsignmentOutput> fromPartitionCounts(String consignmentId, String runId, String tableName,
                                                              List<PartitionOutput> outputs,
                                                              Map<String, Long> rowsByPartition,
                                                              Map<String, EventTimeBounds> bounds,
                                                              String producer) {
        Map<String, Long> counts = rowsByPartition == null ? Map.of() : rowsByPartition;
        Map<String, EventTimeBounds> byPartition = bounds == null ? Map.of() : bounds;
        // No fingerprint on this path: enrichment and Pipeline sinks derive their output from a query, not a
        // declared schema — their derived output schema arrives with the per-Step type flow (Phase 2 S2).
        return build(consignmentId, runId, tableName, outputs,
                o -> counts.getOrDefault(o.partition(), 0L), null,
                outputs == null ? null : keyByFile(outputs, byPartition), producer);
    }

    /** Re-key partition-keyed bounds onto output files, which is what {@link #build} joins on. */
    private static Map<String, EventTimeBounds> keyByFile(List<PartitionOutput> outputs,
                                                          Map<String, EventTimeBounds> byPartition) {
        Map<String, EventTimeBounds> byFile = new HashMap<>();
        for (PartitionOutput o : outputs) {
            EventTimeBounds b = byPartition.get(o.partition());
            if (b != null) byFile.put(o.outputFile(), b);
        }
        return byFile;
    }

    /**
     * {@code COUNT(*)} per Hive partition key over {@code table}, keyed exactly as
     * {@code PartitionWriter} names its partition directories ({@code col=val/col=val/…}) so the result joins
     * straight onto {@link PartitionOutput#partition()}.
     *
     * <p>An empty {@code partCols} means the relation was written unpartitioned, which
     * {@code PartitionSinkWriter} reports as the single partition {@code ""} — so the whole-table count is
     * returned under that key.
     */
    public static Map<String, Long> countByPartition(Connection conn, String table, List<String> partCols)
            throws SQLException {
        Map<String, Long> counts = new HashMap<>();
        if (partCols == null || partCols.isEmpty()) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
                counts.put("", rs.next() ? rs.getLong(1) : 0L);
            }
            return counts;
        }
        StringBuilder sql = new StringBuilder("SELECT ");
        for (String col : partCols) sql.append('"').append(col).append("\", ");
        sql.append("COUNT(*) AS n FROM \"").append(table).append("\" GROUP BY 1");
        for (int i = 1; i < partCols.size(); i++) sql.append(", ").append(i + 1);

        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql.toString())) {
            while (rs.next()) {
                StringBuilder key = new StringBuilder();
                for (int i = 0; i < partCols.size(); i++) {
                    if (i > 0) key.append('/');
                    key.append(partCols.get(i)).append('=').append(rs.getString(i + 1));
                }
                counts.put(key.toString(), rs.getLong(partCols.size() + 1));
            }
        }
        return counts;
    }

    /**
     * Per-partition {@link EventTimeBounds} over {@code table}'s {@code __event_time} column, keyed exactly as
     * {@link #countByPartition} keys its counts so the two join onto the same {@link PartitionOutput}
     * (consignment addressing §3.1).
     *
     * <p><b>Returns an empty map when the relation has no {@code __event_time} column</b> — the enrichment and
     * Pipeline-sink paths write a caller-authored relation that never went through {@code DataTransformer}, and
     * decision D3 says addressing degrades rather than breaks. A partition whose event times are all NULL (the
     * schema declared no date partition, or every row failed to parse) contributes no entry for the same reason:
     * an absent bound is honest, a zero-width bound at the epoch is not.
     *
     * <p>Bounds are formatted and differenced <b>in SQL</b>, not through JDBC temporal types: {@code strftime}
     * yields the exact ISO-8601 local text {@link EventTimeBounds} documents, with none of the default-timezone
     * reinterpretation a {@code java.sql.Timestamp} round-trip would introduce.
     */
    public static Map<String, EventTimeBounds> boundsByPartition(Connection conn, String table,
                                                                 List<String> partCols) throws SQLException {
        if (!hasEventTime(conn, table)) return Map.of();
        return boundsByPartition(conn, table, partCols, "\"" + TransformCompiler.EVENT_TIME_COL + "\"");
    }

    /**
     * {@link #boundsByPartition(Connection, String, List)} over an arbitrary event-time <b>expression</b> rather
     * than the internal {@code __event_time} column — for the write paths that never ran through
     * {@code DataTransformer} and so have no such column to aggregate.
     *
     * <p>The caller owns the expression, and therefore owns the claim that it <em>is</em> event time. A
     * Pipeline sink derives it from the {@code source} its own {@code partitions[]} entry declares
     * ({@code PartitionSinkWriter}); nothing here guesses which of a relation's columns is temporal, because a
     * wrong guess produces bounds that are confidently incorrect rather than absent.
     */
    public static Map<String, EventTimeBounds> boundsByPartition(Connection conn, String table,
                                                                 List<String> partCols, String eventTimeExpr)
            throws SQLException {
        String col = eventTimeExpr;
        String agg = "strftime(min(" + col + "), '%Y-%m-%dT%H:%M:%S'), "
                + "strftime(max(" + col + "), '%Y-%m-%dT%H:%M:%S'), "
                + "datediff('millisecond', min(" + col + "), max(" + col + "))";

        StringBuilder sql = new StringBuilder("SELECT ");
        for (String c : partCols == null ? List.<String>of() : partCols)
            sql.append('"').append(c).append("\", ");
        sql.append(agg).append(" FROM \"").append(table).append('"');
        int nKeys = partCols == null ? 0 : partCols.size();
        for (int i = 0; i < nKeys; i++) sql.append(i == 0 ? " GROUP BY 1" : ", " + (i + 1));

        Map<String, EventTimeBounds> bounds = new HashMap<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql.toString())) {
            while (rs.next()) {
                String min = rs.getString(nKeys + 1);
                String max = rs.getString(nKeys + 2);
                if (min == null || max == null) continue;   // all-NULL partition — no honest bound
                StringBuilder key = new StringBuilder();
                for (int i = 0; i < nKeys; i++) {
                    if (i > 0) key.append('/');
                    key.append(partCols.get(i)).append('=').append(rs.getString(i + 1));
                }
                bounds.put(key.toString(), new EventTimeBounds(min, max, rs.getLong(nKeys + 3)));
            }
        }
        return bounds;
    }

    /** Whether {@code table} carries the internal {@code __event_time} column (only relations built by
     *  {@code DataTransformer} do). */
    private static boolean hasEventTime(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM \"" + table + "\" LIMIT 0")) {
            var md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++)
                if (TransformCompiler.EVENT_TIME_COL.equals(md.getColumnName(i))) return true;
            return false;
        }
    }

    /**
     * The event-time day a file's rows belong to, read off the {@code year}/{@code month}/{@code day} segments of
     * a Hive partition key ({@code null} when the partition scheme carries no such triple).
     *
     * <p>⚠ <b>This is a write-time approximation, deliberately accepted for slice 2 and superseded by §10.1.</b>
     * The plan defines {@code record_day} as event time cut with the pipeline's <em>pinned</em> timezone (§5.6,
     * §10.1) and computed at load — which is not the same as the partition day whenever a Consignment carries
     * late-arriving data, or partitions on something other than event time. The two agree for the common case
     * (partitioning on the event-time date in the pinned zone) and diverge silently otherwise, so when §10.1's
     * load-time record-day lands it must <b>replace</b> this derivation rather than fall back to it.
     */
    /**
     * The event-time day a file's rows belong to — <b>from the file's real event-time bounds when it has them</b>
     * (addressing step 10, 2026-08-10), else from the {@code year}/{@code month}/{@code day} partition segments.
     *
     * <p>This is §10.1's replacement for the write-time approximation below, and it lands only where it is
     * unambiguous: <b>when {@code min} and {@code max} fall on the same day</b>. Then the bounds are strictly more
     * truthful than the partition key — they are the rows' actual event time, not a partition value that may have
     * been cut in a different timezone or derived from a different column. When a file straddles two days there
     * is no single record day to state, so the partition derivation stands (and may itself be {@code null}).
     *
     * <p>⚠ <b>Read {@code bounds}, not this.</b> {@code record_day} is one day per file where bounds are a real
     * interval, so it cannot express a straddling file at all. It is kept because it is in the schema and cheap,
     * not because anything should prefer it — nothing in the engine reads it today.
     */
    static String recordDay(String partitionKey, EventTimeBounds bounds) {
        String fromBounds = sameDay(bounds);
        return fromBounds != null ? fromBounds : recordDay(partitionKey);
    }

    /** The shared day of {@code bounds}, or {@code null} when absent, unparseable, or straddling two days. */
    private static String sameDay(EventTimeBounds bounds) {
        if (bounds == null || bounds.min() == null || bounds.max() == null) return null;
        if (bounds.min().length() < 10 || bounds.max().length() < 10) return null;
        String day = bounds.min().substring(0, 10);
        return day.equals(bounds.max().substring(0, 10)) ? day : null;
    }

    static String recordDay(String partitionKey) {
        if (partitionKey == null || partitionKey.isBlank()) return null;
        String year = null, month = null, day = null;
        for (String seg : partitionKey.split("/")) {
            int eq = seg.indexOf('=');
            if (eq <= 0) continue;
            String name = seg.substring(0, eq);
            String value = seg.substring(eq + 1);
            switch (name) {
                case "year"  -> year = value;
                case "month" -> month = value;
                case "day"   -> day = value;
                default      -> { }
            }
        }
        if (year == null || month == null || day == null) return null;
        Integer y = number(year), m = number(month), d = number(day);
        if (y == null || m == null || d == null) return null;
        return String.format("%04d-%02d-%02d", y, m, d);
    }

    /** {@code value} as a non-negative int, or {@code null} when it is not one — a partition value is free text. */
    private static Integer number(String value) {
        try {
            int n = Integer.parseInt(value.trim());
            return n < 0 ? null : n;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * One {@link ConsignmentOutput} per written file, stamped {@code LIVE} at {@code generation} 0 with a single
     * {@code writtenAt} for the whole registration — the files became visible together, at the reveal that
     * preceded this call.
     */
    private static List<ConsignmentOutput> build(String consignmentId, String runId, String tableName,
                                                 List<PartitionOutput> outputs,
                                                 ToLongFunction<PartitionOutput> rows,
                                                 String schemaFingerprint,
                                                 Map<String, EventTimeBounds> bounds, String producer) {
        if (outputs == null || outputs.isEmpty()) return List.of();
        String writtenAt = Instant.now().toString();
        return outputs.stream()
                .map(o -> new ConsignmentOutput(consignmentId, runId, tableName, o.partition(),
                        recordDay(o.partition(), bounds == null ? null : bounds.get(o.outputFile())),
                        o.outputFile(), rows.applyAsLong(o), o.bytes(),
                        writtenAt, 0, ConsignmentOutput.State.LIVE, schemaFingerprint,
                        bounds == null ? null : bounds.get(o.outputFile()), producer))
                .toList();
    }
}
