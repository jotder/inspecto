package com.gamma.inspector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.control.ControlApi;
import com.gamma.etl.*;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mixed-load benchmark (ASSURE-OPERABILITY-1): streaming plugin ingest (the {@code PluginIngestBenchmark}
 * path — {@link StreamingPluginIngestStrategy} → PARQUET, 30 day-partitions) runs while N concurrent readers
 * hammer {@code POST /api/v1/bi/query} on a real {@link ControlApi} over real HTTP.
 *
 * <p>Three phases, each measured: readers alone (baseline latency), ingest alone (baseline throughput), then
 * both at once. The readers' Dataset is a view over {@code range(bench.readRows)} aggregated by a 30-value key,
 * i.e. a CPU-bound dashboard aggregation on the same host and DuckDB native library the ingest uses — it does
 * NOT read the files the ingest is writing (that would measure a torn-read question, not contention).
 *
 * <p>Gated on {@code -Dbench.run=true}. Run:
 * <pre>
 *   mvn -o -pl inspecto -am test -Dtest=MixedLoadBenchmark -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Dbench.run=true -Dbench.rows=1000000 -Dbench.readers=4
 * </pre>
 */
class MixedLoadBenchmark {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Same stub as PluginIngestBenchmark: one record per {@code ID,EVT_DATE} line. */
    public static class BenchStreamingIngester implements StreamingFileIngester {
        @Override
        public void ingest(File file, RecordSink sink, int srcId, PipelineConfig cfg) throws Exception {
            try (var lines = Files.lines(file.toPath())) {
                for (var it = lines.iterator(); it.hasNext(); ) {
                    String line = it.next();
                    if (line.isBlank()) continue;
                    int comma = line.indexOf(',');
                    sink.emit("EVT", line.substring(0, comma), line.substring(comma + 1));
                }
            }
        }
    }

    @Test
    void ingestWhileReadersQuery(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("bench.run"),
                "MixedLoadBenchmark skipped — pass -Dbench.run=true to run it");
        int rows = Integer.getInteger("bench.rows", 1_000_000);
        int readers = Integer.getInteger("bench.readers", 4);
        int readRows = Integer.getInteger("bench.readRows", 2_000_000);
        long soloReadMs = Long.getLong("bench.soloReadMs", 5_000L);

        File input = dir.resolve("events_20200403.bin").toFile();
        generate(input, rows, 30);
        // one config per ingest phase: re-ingesting the same Consignment under one config is deduplicated
        PipelineConfig soloCfg = buildConfig(dir, "solo");
        PipelineConfig mixedCfg = buildConfig(dir, "mixed");

        Path writeRoot = Files.createDirectories(dir.resolve("root"));
        Path pipe = PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve("cfg")), "");
        System.setProperty("assist.write.root", writeRoot.toString());
        CollectorService svc;
        ControlApi api;
        try {
            svc = new CollectorService(List.of(pipe), 3600, 1);
            api = new ControlApi(svc, 0);
            api.start();
        } finally {
            System.clearProperty("assist.write.root");
        }
        try {
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("load_view", "flow-x", List.of(),
                    "SELECT (i % 30) AS day, i * 1.0 AS amount FROM range(" + readRows + ") t(i)",
                    "2026-09-29T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "load_ds", Map.of("view", "load_view"));
            URI uri = URI.create("http://localhost:" + api.port() + "/api/v1/bi/query");
            String body = """
                    {"dataset":"load_ds","measures":[{"agg":"sum","field":"amount"},{"agg":"count"}],
                     "groupBy":["day"],"orderBy":[{"field":"day","dir":"asc"}]}""";

            // warm-up (JIT + DuckDB) — one query
            assertEquals(200, query(HttpClient.newHttpClient(), uri, body));

            System.out.printf("%n=== MixedLoadBenchmark: ingest %,d rows, %d readers, view over %,d rows ===%n",
                    rows, readers, readRows);

            // phase 1 — readers alone for a fixed window
            Readers solo = new Readers(readers, uri, body);
            solo.start();
            Thread.sleep(soloReadMs);
            ReadStats r1 = solo.stop();

            // phase 2 — ingest alone
            double i2 = ingest(soloCfg, input, rows);

            // phase 3 — ingest while readers run
            Readers mixed = new Readers(readers, uri, body);
            mixed.start();
            double i3 = ingest(mixedCfg, input, rows);
            ReadStats r3 = mixed.stop();

            System.out.printf("%n--- summary ---%n");
            System.out.printf("readers alone      : %s%n", r1);
            System.out.printf("ingest alone       : %,.0f rows/s%n", i2);
            System.out.printf("ingest + readers   : %,.0f rows/s (%.0f%% of alone)%n", i3, 100 * i3 / i2);
            System.out.printf("readers during ing.: %s%n", r3);
            assertEquals(0, r1.errors + r3.errors, "every /bi/query must answer 200 under load");
            assertTrue(r3.count > 0, "readers must complete at least one query during ingest");
        } finally {
            api.close();
            svc.close();
        }
    }

    // ── readers ──────────────────────────────────────────────────────────────

    private record ReadStats(int count, int errors, double seconds, long p50, long p95, long max) {
        @Override public String toString() {
            return String.format("%d queries in %.2fs = %.1f q/s, p50 %d ms, p95 %d ms, max %d ms, errors %d",
                    count, seconds, count / seconds, p50, p95, max, errors);
        }
    }

    private static final class Readers {
        final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicInteger errors = new AtomicInteger();
        final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        final List<Thread> threads = new ArrayList<>();
        long t0;

        Readers(int n, URI uri, String body) {
            for (int i = 0; i < n; i++) {
                threads.add(new Thread(() -> {
                    HttpClient c = HttpClient.newHttpClient();
                    while (running.get()) {
                        long t = System.nanoTime();
                        try {
                            if (query(c, uri, body) != 200) errors.incrementAndGet();
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                        latencies.add((System.nanoTime() - t) / 1_000_000);
                    }
                }, "bi-reader-" + i));
            }
        }

        void start() { t0 = System.nanoTime(); threads.forEach(Thread::start); }

        ReadStats stop() throws InterruptedException {
            running.set(false);
            for (Thread t : threads) t.join();
            double sec = (System.nanoTime() - t0) / 1e9;
            List<Long> l = new ArrayList<>(latencies);
            Collections.sort(l);
            if (l.isEmpty()) return new ReadStats(0, errors.get(), sec, 0, 0, 0);
            return new ReadStats(l.size(), errors.get(), sec,
                    l.get(l.size() / 2), l.get(Math.min(l.size() - 1, (int) (l.size() * 0.95))), l.getLast());
        }
    }

    private static int query(HttpClient c, URI uri, String body) throws Exception {
        HttpResponse<String> r = c.send(HttpRequest.newBuilder(uri)
                .method("POST", HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 200) JSON.readTree(r.body());
        return r.statusCode();
    }

    // ── ingest (PluginIngestBenchmark's union-mode path) ─────────────────────

    private static double ingest(PipelineConfig cfg, File input, int rows) throws Exception {
        Path db = Path.of(cfg.dirs().database());
        if (Files.exists(db)) {
            try (var walk = Files.walk(db)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) { } });
            }
        }
        SchemaSelector.Selection sel = new SchemaSelector.Selection(Map.of(), null);
        Consignment batch = new Consignment(cfg.identity().runTimestamp() + "_evt_0001", "evt", null,
                List.of(new Consignment.Member(input, 0, input.length(), sel)));
        long t = System.nanoTime();
        IngestOutcome out = new StreamingPluginIngestStrategy().ingest(batch, cfg);
        double sec = (System.nanoTime() - t) / 1e9;
        if (!"SUCCESS".equals(out.status())) throw new IllegalStateException("ingest " + out.status() + ": " + out.error());
        assertEquals(rows, out.lineage().stream().mapToLong(LineageRow::rowCount).sum(), "ingest conserves rows");
        return rows / sec;
    }

    private static void generate(File f, int rows, int days) throws Exception {
        try (BufferedWriter w = Files.newBufferedWriter(f.toPath())) {
            for (int i = 0; i < rows; i++) {
                int day = (i % days) + 1;
                w.write("E" + i + ",2020-04-" + (day < 10 ? "0" : "") + day + "\n");
            }
        }
    }

    private static PipelineConfig buildConfig(Path dir, String tag) throws Exception {
        Path schema = dir.resolve("evt_schema_" + tag + ".toon");
        Files.writeString(schema, """
                partitions[3]{column,source,type}:
                  year,EVT_DATE,DATE_YEAR
                  month,EVT_DATE,DATE_MONTH
                  day,EVT_DATE,DATE_DAY
                raw:
                  name: evt
                  format: CSV
                  fields[2]{name,selector,type}:
                    ID,"0",VARCHAR
                    EVT_DATE,"1",DATE
                mapping:
                  canonicalName: evt
                  rawName: evt
                  rules[2]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                    EVT_DATE,EVT_DATE,DIRECT
                """);
        String base = dir.resolve("ingest_" + tag).toString().replace("\\", "/");
        Path pipeline = dir.resolve("evt_pipeline_" + tag + ".toon");
        Files.writeString(pipeline, """
                name: EVT_ETL
                version: 1
                dirs:
                  poll: %1$s/inbox
                  database: %1$s/db
                  backup: %1$s/backup
                  temp: %1$s/temp
                  errors: %1$s/errors
                  quarantine: %1$s/quarantine
                  status_dir: %1$s/status
                  log_dir: %1$s/logs
                output:
                  format: PARQUET
                  compression: snappy
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.bin"
                  ingester: %2$s
                  segments:
                    EVT: %3$s
                  csv_settings:
                    delimiter: ","
                    skip_header_lines: 0
                    skip_junk_lines: 0
                    skip_tail_lines: 0
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                """.formatted(base, BenchStreamingIngester.class.getName(), schema.toString().replace("\\", "/")));
        return PipelineConfig.load(pipeline.toString());
    }
}
