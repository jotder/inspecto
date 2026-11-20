package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BASIC-ELT-PROFILE-1} O6 ({@code output.load}): the save path refuses a {@code replace} / {@code period} on a store
 * a replacement cannot rewrite safely (over real HTTP, {@code POST /config/write}: 422, nothing written, even inactive) and
 * accepts it on a single flat local Parquet store; a Standard Pipeline that saves it then replaces at each commit through
 * the {@code CollectorService}'s {@code output.load} commit step. Add a file's run-scoped choice is pinned over HTTP by
 * {@code ControlApiOnboardFilesTest}. Synthetic fixtures only.
 */
class ControlApiOutputLoadTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> write(Ctx c, String name, String output, String extra) throws Exception {
        String body = """
                {"type":"pipeline","config":{"name":"%s","active":false,
                   "dirs":{"poll":"in","database":"out/%s"},
                   "processing":{"threads":1,"schema_file":"calls_schema.toon"},
                   "output":%s%s}}""".formatted(name, name, output, extra);
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/config/write"))
                .POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private static String refused(HttpResponse<String> r, Path root, String name) throws Exception {
        assertEquals(422, r.statusCode(), r.body());
        JsonNode out = V1Body.envelope(r.body()).get("error").get("details");
        assertFalse(out.get("written").asBoolean());
        assertFalse(Files.exists(root.resolve(name + "_pipeline.toon")), "nothing written");
        return out.get("findings").toString();
    }

    private static final String FLAT = """
            raw:
              name: calls
              format: CSV
              fields[3]{name,selector,type}:
                ID,"0",VARCHAR
                AMT,"1",DOUBLE
                EVENT_DATE,"2",DATE
            mapping:
              canonicalName: calls
              rawName: calls
              rules[3]{targetColumn,sourceExpression,transformType}:
                ID,ID,DIRECT
                AMT,AMT,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
            """;

    @Test
    void theSaveRefusesEveryStoreAReplacementCannotRewriteAndAcceptsAFlatParquetStore(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            Files.writeString(root.resolve("calls_schema.toon"), FLAT);
            String twoSinks = refused(write(c, "two_sinks", "{\"format\":\"PARQUET\",\"load\":\"replace\"}",
                    ",\"sinks\":[{\"database\":\"out/a\"},{\"database\":\"out/b\"}]"), root, "two_sinks");
            assertTrue(twoSinks.contains("\"output.load\"") && twoSinks.contains("needs a single local Parquet store")
                    && twoSinks.contains("writes to 2 destinations"), twoSinks);
            String csv = refused(write(c, "csv_store", "{\"format\":\"CSV\",\"load\":\"period\",\"period_column\":\"EVENT_DATE\"}", ""),
                    root, "csv_store");
            assertTrue(csv.contains("its output format is CSV"), csv);
            String noColumn = refused(write(c, "no_column", "{\"load\":\"period\"}", ""), root, "no_column");
            assertTrue(noColumn.contains("output.load=period needs output.period_column"), noColumn);
            assertEquals(1, V1Body.envelope(write(c, "no_column", "{\"load\":\"period\"}", "").body()).at("/error/details/findings").size(), "one finding, not two");
            String unknown = refused(write(c, "unknown_column", "{\"load\":\"period\",\"period_column\":\"WHEN\"}", ""),
                    root, "unknown_column");
            assertTrue(unknown.contains("\"output.period_column\"") && unknown.contains("'WHEN' is not declared"), unknown);
            String badMode = refused(write(c, "bad_mode", "{\"load\":\"merge\"}", ""), root, "bad_mode");
            assertTrue(badMode.contains("output.load"), badMode);

            Files.writeString(root.resolve("calls_schema.toon"), "partitionKey: EVENT_DATE\n" + FLAT);
            String partitioned = refused(write(c, "partitioned", "{\"load\":\"replace\"}", ""), root, "partitioned");
            assertTrue(partitioned.contains("its schema partitions the store"), partitioned);
            HttpResponse<String> appendOk = write(c, "partitioned_append", "{\"load\":\"append\"}", "");
            assertEquals(200, appendOk.statusCode(), "append is never refused: " + appendOk.body());

            Files.writeString(root.resolve("calls_schema.toon"), FLAT);
            HttpResponse<String> ok = write(c, "flat_period", "{\"format\":\"PARQUET\",\"load\":\"period\",\"period_column\":\"EVENT_DATE\"}", "");
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(Files.readString(root.resolve("flat_period_pipeline.toon")).contains("period_column: EVENT_DATE"));
        }
    }

    /** A flat Parquet Pipeline {@code name} under {@code dir} that loads by {@code load}. */
    private static Path pipeline(Path dir, String name, String load) throws Exception {
        Files.createDirectories(dir.resolve("inbox"));
        Path schema = dir.resolve(name + "_schema.toon");
        Files.writeString(schema, FLAT);
        String d = dir.toString().replace('\\', '/');
        Path p = dir.resolve(name + "_pipeline.toon");
        Files.writeString(p, """
                name: %1$s
                active: true
                dirs:
                  poll: %2$s/inbox
                  database: %2$s/out/%1$s
                  backup: %2$s/backup
                  temp: %2$s/temp
                  quarantine: %2$s/quarantine
                  markers: %2$s/markers
                  status_dir: %2$s/status
                  log_dir: %2$s/logs
                output:
                  format: PARQUET
                  %3$s
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  duplicate_check:
                    enabled: true
                    marker_extension: .processed
                  schema_file: "%4$s"
                  csv_settings:
                    delimiter: ","
                    has_header: true
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                """.formatted(name, d, load, schema.toString().replace('\\', '/')));
        return p;
    }

    private static List<String> rows(Path store) throws Exception {
        DuckDbUtil.loadDriver();
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT ID || ':' || CAST(EVENT_DATE AS VARCHAR) FROM read_parquet('"
                     + store.toString().replace('\\', '/') + "/**/*.parquet') ORDER BY 1")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    @Test
    void aStandardPipelineReplacesAtEachCommit(@TempDir Path dir) throws Exception {
        Path period = pipeline(dir.resolve("p"), "calls_period", "load: period\n  period_column: EVENT_DATE");
        Path replace = pipeline(dir.resolve("r"), "calls_snap", "load: replace");
        try (CollectorService svc = new CollectorService(List.of(period, replace), 3600, 1)) {
            Path pIn = dir.resolve("p").resolve("inbox"), rIn = dir.resolve("r").resolve("inbox");
            Files.writeString(pIn.resolve("week1.csv"), "ID,AMT,EVENT_DATE\na,1,2026-10-01\nb,2,2026-10-02\n");
            Files.writeString(rIn.resolve("snap1.csv"), "ID,AMT,EVENT_DATE\na,1,2026-10-01\nb,2,2026-10-02\n");
            svc.runPipeline("calls_period").orElseThrow();
            svc.runPipeline("calls_snap").orElseThrow();
            Files.writeString(pIn.resolve("week2.csv"), "ID,AMT,EVENT_DATE\nc,3,2026-10-01\n");
            Files.writeString(rIn.resolve("snap2.csv"), "ID,AMT,EVENT_DATE\nc,3,2026-10-05\n");
            svc.runPipeline("calls_period").orElseThrow();
            svc.runPipeline("calls_snap").orElseThrow();
        }
        assertEquals(List.of("b:2026-10-02", "c:2026-10-01"), rows(dir.resolve("p").resolve("out").resolve("calls_period")),
                "the second week replaced the 1st and kept the 2nd");
        assertEquals(List.of("c:2026-10-05"), rows(dir.resolve("r").resolve("out").resolve("calls_snap")),
                "the second snapshot replaced the first");
        assertFalse(Files.exists(dir.resolve("p").resolve("out").resolve("calls_period.period.pending")), "settled");
    }
}
