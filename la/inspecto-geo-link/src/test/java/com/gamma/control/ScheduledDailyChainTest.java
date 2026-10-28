package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.job.JobConfig;
import com.gamma.job.JobRun;
import com.gamma.la.api.InputFingerprintCache;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-DAILY-INGEST-1 - the whole daily chain on a SCHEDULE, with no hand-driven step and no planted file: a sample daily
 * feed lands in a Collector inbox one day at a time; the {@link CollectorService} poll scheduler (1 s) runs the Pipeline,
 * which writes the day as Parquet partitioned by {@code EVENT_DATE} straight into the Dataset's {@code physicalRef} store;
 * the commit is mirrored as {@code pipeline.commit}; a saved {@code la.index.build} Job on that Signal builds the index
 * (FULL on the first day because the Job states {@code allow_full}, APPEND on every later day); the next read sees the new day.
 *
 * <p>Closes the "honest seam" of {@code ControlApiIndexRefreshOnCommitTest} (there the day's Parquet is planted beside a
 * manual {@code runPipeline}): here the index reads exactly what the Pipeline committed.
 */
class ScheduledDailyChainTest {

    @BeforeAll static void raiseBudget() {
        System.setProperty("control.rateLimit.linkAnalysis.capacity", "10000");
    }

    @AfterAll static void restoreBudget() {
        System.clearProperty("control.rateLimit.linkAnalysis.capacity");
    }

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** CSV reads fields by position; the Parquet frontend reads them by column name. */
    private static String schema(boolean parquet) {
        String[] sel = parquet ? new String[]{"S", "T", "KIND", "TS", "C", "EVENT_DATE"} : new String[]{"0", "1", "2", "3", "4", "5"};
        return SCHEMA.formatted((Object[]) sel);
    }

    private static final String SCHEMA = """
            partitionKey: EVENT_DATE
            raw:
              name: xdr
              format: CSV
              fields[6]{name,selector,type}:
                S,"%s",VARCHAR
                T,"%s",VARCHAR
                KIND,"%s",VARCHAR
                TS,"%s",TIMESTAMP
                C,"%s",VARCHAR
                EVENT_DATE,"%s",DATE
            mapping:
              canonicalName: xdr
              rawName: xdr
              rules[6]{targetColumn,sourceExpression,transformType}:
                S,S,DIRECT
                T,T,DIRECT
                KIND,KIND,DIRECT
                TS,TS,DIRECT
                C,C,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
            """;

    /** The sample daily feed: one CSV per event date, a chain A>B>C>D>E growing by one hop a day. */
    private static final List<String> FEED = List.of(
            "A,B,call,2026-03-01 09:00:00,x,2026-03-01\nB,C,sms,2026-03-01 10:00:00,x,2026-03-01\n",
            "C,D,call,2026-03-02 09:00:00,y,2026-03-02\n",
            "D,E,call,2026-03-03 09:00:00,y,2026-03-03\n");

    private static Path pipeline(Path cfg, Path store, boolean parquet) throws Exception {
        Path schema = cfg.resolve("xdr_schema.toon");
        Files.writeString(schema, schema(parquet));
        String r = cfg.toString().replace('\\', '/');
        Path p = cfg.resolve("xdrfeed_pipeline.toon");
        Files.writeString(p, """
                name: xdrfeed
                active: true
                dirs:
                  poll: %1$s/inbox
                  database: %3$s
                  backup: %1$s/backup
                  temp: %1$s/temp
                  quarantine: %1$s/quarantine
                  markers: %1$s/markers
                  status_dir: %1$s/status
                  log_dir: %1$s/logs
                output:
                  format: PARQUET
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.%4$s"
                  schema_file: "%2$s"
                  csv_settings:
                    delimiter: ","
                    has_header: true
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d %%H:%%M:%%S"
                """.formatted(r, schema.toString().replace('\\', '/'), store.toAbsolutePath().toString().replace('\\', '/'),
                parquet ? "parquet" : "csv") + (parquet ? "parsing:\n  frontend: parquet\n" : ""));
        return p;
    }

    private static JobConfig job() {
        Map<String, Object> j = new java.util.LinkedHashMap<>();
        j.put("name", "xdr_index_daily");
        j.put("type", "la.index.build");
        j.put("on_signal", "pipeline.commit");
        j.put("when", "$signal.pipeline == xdrfeed");
        j.put("dataset", "xdr_ds");
        j.put("source_col", "S");
        j.put("target_col", "T");
        j.put("kind_col", "KIND");
        j.put("time_col", "TS");
        j.put("attr_cols", "C");
        j.put("allow_full", "true");                                                          // the first day has no index yet
        j.put("updatedBy", "analyst-1");
        j.put("owner", "analyst-1");
        return JobConfig.fromMap(Map.of("job", j));
    }

    private JsonNode data(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", "Bearer owner");
        b = "GET".equals(method) ? b.GET() : b.method(method, HttpRequest.BodyPublishers.ofString(body));
        var r = client.send(b.build(), BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static boolean reaches(JsonNode d, String node) {
        for (JsonNode p : d.get("paths"))
            for (JsonNode n : p.get("nodes")) if (node.equals(n.asText())) return true;
        return false;
    }

    /** Waits for the n-th finished run of the Job; nothing calls runPipeline or runJob - the scheduler does. */
    private static JobRun awaitRun(CollectorService svc, int n) throws Exception {
        for (int i = 0; i < 6_000; i++) {
            List<JobRun> done = svc.jobService().orElseThrow().runsFor("xdr_index_daily").stream()
                    .filter(r -> !"RUNNING".equals(r.status())).toList();
            if (done.size() >= n) return done.stream().max(Comparator.comparing(JobRun::startTime)).orElseThrow();
            Thread.sleep(10);
        }
        throw new AssertionError("run " + n + " of la.index.build never finished: "
                + svc.jobService().orElseThrow().runsFor("xdr_index_daily"));
    }

    @Test
    void aSampleDailyFeedFlowsCollectorPipelinePartitionedDatasetIndexOnTheScheduleAlone(@TempDir Path cfg, @TempDir Path root) throws Exception {
        runChain(cfg, root, false);
    }

    /** The same chain with each day delivered as a Parquet file read by {@code parsing.frontend: parquet}. */
    @Test
    void aParquetDailyFeedFlowsTheSameChainOnTheScheduleAlone(@TempDir Path cfg, @TempDir Path root) throws Exception {
        runChain(cfg, root, true);
    }

    /** Writes one day as a typed Parquet file beside the inbox, then moves it in so the poll never sees a partial file. */
    private static void landParquet(Path cfg, Path inbox, String day, String rows) throws Exception {
        Path csv = cfg.resolve("gen_" + day + ".csv"), tmp = cfg.resolve("gen_" + day + ".parquet");
        Files.writeString(csv, "S,T,KIND,TS,C,EVENT_DATE\n" + rows);
        try (var c = DriverManager.getConnection("jdbc:duckdb:"); var st = c.createStatement()) {
            st.execute("COPY (SELECT S, T, KIND, CAST(TS AS TIMESTAMP) AS TS, C, CAST(EVENT_DATE AS DATE) AS EVENT_DATE FROM read_csv('"
                    + csv.toString().replace('\\', '/') + "', header=true, all_varchar=true)) TO '"
                    + tmp.toString().replace('\\', '/') + "' (FORMAT parquet)");
        }
        Files.move(tmp, inbox.resolve("XDR_" + day + ".parquet"), StandardCopyOption.ATOMIC_MOVE);
    }

    private void runChain(Path cfg, Path root, boolean parquet) throws Exception {
        Authenticators.forTest(ex -> "Bearer owner".equals(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")))
                ? Optional.of(new Subject("analyst-1", Set.of("canBuildLinkIndex"))) : Optional.empty());
        InputFingerprintCache.forTest(System::currentTimeMillis, 0L);
        String storeName = "daily_chain_" + System.nanoTime();
        Path store = Path.of("database").resolve(storeName);
        Path inbox = Files.createDirectories(cfg.resolve("inbox"));
        System.setProperty("assist.write.root", root.toString());
        CollectorService svc = new CollectorService(List.of(pipeline(cfg, store, parquet)), List.of(), List.of(job()), 1, 1, null);
        ControlApi api = null;
        try {
            new ComponentStore(root.resolve("registry")).write("dataset", "xdr_ds",
                    Map.of("physicalRef", storeName, "owner", "analyst-1", "shares", List.of()));
            svc.start();                                                                       // arms the poll scheduler and the Job layer
            api = new ControlApi(svc, 0);
            api.start();

            for (int d = 0; d < FEED.size(); d++) {
                String day = "2026030" + (d + 1);
                if (parquet) landParquet(cfg, inbox, day, FEED.get(d));
                else Files.writeString(inbox.resolve("XDR_" + day + ".csv"), "S,T,KIND,TS,C,EVENT_DATE\n" + FEED.get(d));

                JobRun run = awaitRun(svc, d + 1);
                assertEquals("SUCCESS", run.status(), run.message());
                assertTrue(run.trigger().startsWith("signal:pipeline.commit"), run.trigger());
                assertTrue(run.message().contains(d == 0 ? "full" : "append"), "day " + day + ": " + run.message());

                try (Stream<Path> w = Files.walk(store)) {                                     // the Pipeline wrote the day's partition into the store
                    String dd = "day=0" + (d + 1);
                    assertTrue(w.anyMatch(p -> p.toString().replace('\\', '/').contains("/" + dd + "/")
                            && p.toString().endsWith(".parquet")), "no " + dd + " partition under " + store);
                }
                String start = String.valueOf((char) ('A' + d));                                   // two hops back from the newest node
                JsonNode read = data(api.port(), "POST", "/inv/traversal/recursive-paths", "{\"dataset\":\"xdr_ds\",\"sourceCol\":\"S\","
                        + "\"targetCol\":\"T\",\"startNode\":\"" + start + "\",\"maxDepth\":2}");
                assertEquals("index", read.at("/source/kind").asText(), read.get("source").toString());
                String newest = String.valueOf((char) ('C' + d));
                assertTrue(reaches(read, newest), "day " + day + " is visible: " + start + " reaches " + newest);
                if (d + 1 < FEED.size())
                    assertTrue(!reaches(read, String.valueOf((char) ('D' + d))), "the next day has not landed yet");
            }
            assertEquals(FEED.size(), svc.jobService().orElseThrow().runsFor("xdr_index_daily").size(), "exactly one build per landed day");
        } finally {
            if (api != null) api.close();
            svc.close();
            System.clearProperty("assist.write.root");
            if (Files.isDirectory(store)) try (Stream<Path> w = Files.walk(store)) {
                w.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
    }
}
