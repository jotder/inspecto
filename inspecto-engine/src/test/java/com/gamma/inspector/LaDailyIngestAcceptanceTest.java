package com.gamma.inspector;

import com.gamma.acquire.DayManifest;
import com.gamma.acquire.GapTracker;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.enrich.ReferenceReader;
import com.gamma.etl.*;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.signal.DeliveryAnomalySignal;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LA-DAILY-INGEST-1 T9 - the end-to-end acceptance run on a synthetic corpus, through the REAL ingest path
 * ({@link ConsignmentIngestor#process}, which commits, backs up, marks and records the per-day manifest): seven daily
 * files of one feed with one day never delivered, one LATE day, a same-name re-run, a renamed correction and a part added
 * after the day had looked complete; then a dimension (Reference) change seen by a read-time join.
 *
 * <p>Acceptance (roadmap T9): AC-02 a re-run gives identical counts and leaves no stale file; the missing day is reported
 * as a gap (from the manifest, after every file has left the inbox); the late day is NOT an anomaly; the renamed
 * correction and the changed part count ARE signalled; the dimension change is visible without rewriting a fact file.
 * The index half (append, backfill parity, waiting on a live build) is pinned by {@code IndexAppendTest} and geo-link
 * {@code ScheduledIndexBuildTest} (T5, T7); this engine module cannot reach the index.
 */
class LaDailyIngestAcceptanceTest {

    /** 2020-04-08T06:00Z: the last complete day is 2020-04-07. */
    private static final long NOW = Instant.parse("2020-04-08T06:00:00Z").toEpochMilli();

    private final List<Event> seen = new CopyOnWriteArrayList<>();
    private final Consumer<Event> sub = seen::add;

    @AfterEach
    void off() {
        EventLog.current().removeSubscriber(sub);
        GapTracker.shared().reset();
    }

    private static final String SCHEMA = """
            partitionKey: EVENT_DATE
            raw:
              name: ev
              format: CSV
              fields[3]{name,selector,type}:
                SUBSCRIBER_ID,"subscriber",VARCHAR
                EVENT_DATE,"event_date",DATE
                AMOUNT,"amount",DOUBLE
            mapping:
              canonicalName: ev
              rawName: ev
              rules[3]{targetColumn,sourceExpression,transformType}:
                SUBSCRIBER_ID,SUBSCRIBER_ID,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
                AMOUNT,AMOUNT,DIRECT
            """;

    /** {@code n} rows of one event date, subscribers S1/S2 alternating. */
    private static String day(int d, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++)
            sb.append("{\"subscriber\":\"S").append(1 + i % 2).append("\",\"event_date\":\"2020-04-0").append(d)
                    .append("\",\"amount\":1.0}\n");
        return sb.toString();
    }

    @Test
    void sevenDaysALateFileARerunACorrectionAMissingDayAndADimensionChange(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        EventLog.current().addSubscriber(sub);
        int seq = 0;
        for (int d : new int[] {1, 2, 4, 6, 7}) ingest(cfg, name(d, 1), day(d, 3), ++seq);   // day 5 never comes
        ingest(cfg, name(3, 1), day(3, 3), ++seq);                                          // day 3 arrives LATE
        assertEquals(List.of(), kinds(), "a conforming feed with a late whole day raises no delivery anomaly");

        // AC-02: a same-name re-run of day 1 is a redelivery, gives identical counts and leaves no stale file
        ingest(cfg, name(1, 1), day(1, 3), ++seq);
        assertEquals(1, outFiles(cfg, "day=01").size(), "no stale file");
        assertEquals(3, rows(cfg, "day=01"), "identical counts after the re-run");

        // the correction re-sent under a NEW name: both land, the day doubles, and it is signalled
        ingest(cfg, "xdr_20200404_part0001_fix.jsonl", day(4, 3), ++seq);
        assertEquals(6, rows(cfg, "day=04"), "the platform does not fix a renamed correction (T4 feed rule) ...");
        // a part added to day 2 after later days were delivered
        ingest(cfg, name(2, 2), day(2, 2), ++seq);
        assertEquals(List.of("REDELIVERED", "RENAMED_CORRECTION", "PART_COUNT_CHANGED"), kinds(), "... but it is detected");

        // the manifest is what remains: every file has left the inbox (backed up)
        try (Stream<Path> inbox = Files.list(Path.of(cfg.dirs().poll()))) {
            assertEquals(0, inbox.count(), "processed files left the inbox");
        }
        DayManifest m = DayManifest.read(DeliveryCheck.manifestFile(cfg));
        assertEquals(6, m.days().size(), "six delivered days");
        assertEquals(3, m.days().get("20200401").get(0).rows(), "rows per part come from the real lineage");

        // the missing day is reported, even with an empty inbox; a persistent gap fires once
        assertEquals(List.of("20200405"), DeliveryCheck.detectFileGaps(cfg, List.of(), NOW));
        assertEquals(1, seen.stream().filter(e -> EventType.SEQUENCE_GAP.equals(e.type())).count());
        assertEquals(List.of(), DeliveryCheck.detectFileGaps(cfg, List.of(), NOW));

        // a dimension change: the next Reference file is visible to a read-time join, no fact file rewritten
        PipelineConfig dim = dim(dir.resolve("dim"));
        landDim(dim, "subscribers", "d1", "S1", "GOLD", "S2", "SILVER");
        Path anyFact = outFiles(cfg, "day=06").get(0);
        long size = Files.size(anyFact), mtime = Files.getLastModifiedTime(anyFact).toMillis();
        String before = segments(cfg, dim);
        assertTrue(before.startsWith("S1:GOLD:") && before.contains(",S2:SILVER:"), before);
        landDim(dim, "subscribers", "d2", "S1", "PLATINUM");
        String after = segments(cfg, dim);
        assertEquals(before.replace("S1:GOLD:", "S1:PLATINUM:"), after, "the same facts see the new segment");
        assertEquals(size, Files.size(anyFact));
        assertEquals(mtime, Files.getLastModifiedTime(anyFact).toMillis(), "no fact file was rewritten");
    }

    // ---- helpers ----

    private static String name(int d, int part) {
        return "xdr_2020040" + d + "_part000" + part + ".jsonl";
    }

    private List<String> kinds() {
        List<String> out = new ArrayList<>();
        for (Event e : seen) {
            String msg = String.valueOf(e.message());
            if (msg.startsWith(DeliveryAnomalySignal.TYPE + " ")) out.add(msg.split(" ")[1]);
        }
        return out;
    }

    private static void ingest(PipelineConfig cfg, String name, String body, int seq) throws Exception {
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path f = inbox.resolve(name);
        Files.writeString(f, body, StandardCharsets.UTF_8);
        SchemaSelector.Selection sel = new SchemaSelector.Selection(cfg.schemas().single(), null);
        Consignment batch = new Consignment(cfg.identity().runTimestamp() + "_la_" + String.format("%04d", seq), "la", null,
                List.of(new Consignment.Member(f.toFile(), 0, f.toFile().length(), sel)));
        ConsignmentIngestor.process(batch, cfg, new ConsignmentAuditWriter(
                cfg.dirs().statusFilePath(), cfg.dirs().batchesFilePath(), cfg.dirs().lineageFilePath()));
    }

    private static List<Path> outFiles(PipelineConfig cfg, String dayDir) throws Exception {
        Path root = Path.of(cfg.dirs().database());
        if (!Files.exists(root)) return List.of();
        try (Stream<Path> w = Files.walk(root)) {
            return w.filter(p -> p.getFileName().toString().endsWith("_out.csv"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/" + dayDir + "/"))
                    .sorted().toList();
        }
    }

    private static long rows(PipelineConfig cfg, String dayDir) throws Exception {
        long n = 0;
        for (Path p : outFiles(cfg, dayDir)) n += Files.readAllLines(p).stream().filter(l -> l.contains("S1") || l.contains("S2")).count();
        return n;
    }

    private static PipelineConfig dim(Path dir) throws Exception {
        Files.createDirectories(dir);
        return PipelineConfig.fromMap(Map.of(
                "name", "SUBSCRIBER_DIM", "produces", "reference",
                "reference", Map.of("load", "upsert", "key", List.of("SUBSCRIBER_ID"), "refresh_seconds", 86400),
                "dirs", Map.of("poll", dir.resolve("in").toString(), "database", dir.resolve("db").toString(),
                        "temp", dir.resolve("temp").toString()),
                "output", Map.of("format", "PARQUET"),
                "processing", Map.of("threads", 1)));
    }

    /** One daily Reference file (stable name): pairs of {SUBSCRIBER_ID, SEGMENT}. */
    private static void landDim(PipelineConfig cfg, String stem, String batchId, String... kv) throws Exception {
        StringBuilder v = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2)
            v.append(v.isEmpty() ? "" : ", ").append("('").append(kv[i]).append("', '").append(kv[i + 1]).append("', 0)");
        File tmp = ConsignmentIngestStrategy.openTempDb(cfg, "la_dim_");
        try (Connection c = DuckDbUtil.openConnection(tmp); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE transformed AS SELECT * FROM (VALUES " + v + ") AS t(SUBSCRIBER_ID, SEGMENT, __src_id)");
            ConsignmentIngestStrategy.writeAndTrace(c, "transformed", List.of(), cfg, cfg.dirs().database(), stem, batchId,
                    Map.of(0, stem + ".csv"), "");
        } finally {
            DuckDbUtil.deleteTempDb(tmp);
        }
    }

    /** subscriber:segment:fact-rows over every landed fact file, joined at READ time. */
    private static String segments(PipelineConfig facts, PipelineConfig dim) throws Exception {
        String glob = Path.of(facts.dirs().database()).toAbsolutePath().toString().replace('\\', '/') + "/**/*_out.csv";
        String ref = ReferenceReader.sqlFor(new EnrichmentConfig.Reference("dim", null, null, dim.identity().pipelineName()),
                List.of(dim));
        String sql = "SELECT f.SUBSCRIBER_ID, d.SEGMENT, count(*) FROM read_csv_auto('" + glob
                + "', union_by_name=true) f JOIN (SELECT * FROM " + ref + ") d USING (SUBSCRIBER_ID) GROUP BY ALL ORDER BY 1";
        StringBuilder out = new StringBuilder();
        File tmp = DuckDbUtil.tempDbFile("la_join_");
        try (Connection c = DuckDbUtil.openConnection(tmp); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next())
                out.append(out.isEmpty() ? "" : ",").append(rs.getString(1)).append(':').append(rs.getString(2)).append(':')
                        .append(rs.getLong(3));
        } finally {
            DuckDbUtil.deleteTempDb(tmp);
        }
        return out.toString();
    }

    private static PipelineConfig cfg(Path dir) throws Exception {
        String d = dir.toString().replace('\\', '/');
        Path schema = dir.resolve("ev_schema.toon");
        Files.writeString(schema, SCHEMA, StandardCharsets.UTF_8);
        Path pipe = dir.resolve("ev_pipeline.toon");
        Files.writeString(pipe, ("""
                name: LA_DAILY
                version: 1
                dirs:
                  poll: %s/inbox
                  database: %s/db
                  backup: %s/backup
                  temp: %s/temp
                  errors: %s/errors
                  quarantine: %s/quarantine
                  markers: %s/markers
                  status_dir: %s/status
                output:
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.jsonl"
                  schema_file: %s
                  csv_settings:
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                parsing:
                  frontend: json
                  json:
                    format: newline
                collector:
                  gap_detection:
                    file_template: "xdr_{yyyyMMdd}_part{seq}*"
                    seq_scope: PER_BUCKET
                """).formatted(d, d, d, d, d, d, d, d,
                schema.toString().replace('\\', '/')), StandardCharsets.UTF_8);
        return PipelineConfig.load(pipe.toString());
    }
}
