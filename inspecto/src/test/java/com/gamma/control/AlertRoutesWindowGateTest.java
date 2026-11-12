package com.gamma.control;

import com.gamma.alert.AlertRule;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PA-9b: the save gates of a windowed Alert Rule ({@link AlertRoutes#requireWindow}), each 422 paired with the accepted
 * shape one key away. The gates run inside {@link AlertRoutes#parseContent}, which every door that writes an Alert Rule
 * runs; the last test drives one door over real HTTP.
 */
class AlertRoutesWindowGateTest {

    @BeforeEach
    void standardBuild() {
        com.gamma.etl.EditionFeatures.overrideForTest(java.util.Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH,
                com.gamma.etl.EditionFeatures.REALTIME));
    }

    @AfterEach
    void restoreEdition() {
        com.gamma.etl.EditionFeatures.overrideForTest(null);
    }

    /** {@code root} = the write root (registry + Pipelines), {@code data} = the data root. */
    private record Space(Path root, Path data) {}

    private static Space space(Path dir, String triggerBlock, String batchBlock) throws Exception {
        Path root = Files.createDirectories(dir.resolve("config")), data = Files.createDirectories(dir.resolve("data"));
        Path store = Files.createDirectories(data.resolve("cdr_stream").resolve("event_day=2026-11-12"));
        try (Connection d = DriverManager.getConnection("jdbc:duckdb:"); Statement st = d.createStatement()) {
            st.execute("COPY (SELECT 'A1' AS a_number, 'B1' AS b_number, 1.0 AS duration_s, "
                    + "TIMESTAMP '2026-11-12 10:00:00' AS event_ts, 'x' AS event_text, 'cdr' AS topic, 0 AS \"partition\", "
                    + "7 AS \"offset\") TO '" + store.resolve("part-0.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        new ComponentStore(root.resolve("registry")).write("dataset", "voice_cdr",
                new HashMap<>(Map.of("physicalRef", "cdr_stream")));
        writePipeline(root.resolve("pipelines"), "CDR_STREAM", triggerBlock, batchBlock);
        return new Space(root, data);
    }

    private static void writePipeline(Path dir, String name, String triggerBlock, String batchBlock) throws Exception {
        Files.createDirectories(dir);
        Path schema = dir.resolve("schema.toon");
        Files.writeString(schema, PipelineConfigBatchTest.miniSchema());
        String toon = """
                name: %1$s
                active: true
                %2$sdirs:
                  poll: %3$s/inbox
                  database: %3$s/db
                  backup: %3$s/backup
                  temp: %3$s/temp
                  quarantine: %3$s/quarantine
                  markers: %3$s/markers
                  status_dir: %3$s/status
                  log_dir: %3$s/logs
                output:
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  schema_file: "%4$s"
                %5$s  csv_settings:
                    delimiter: ","
                    has_header: true
                """.formatted(name, triggerBlock, dir.toString().replace('\\', '/'),
                schema.toString().replace('\\', '/'), batchBlock);
        Files.writeString(dir.resolve(name.toLowerCase() + "_pipeline.toon"), toon);
    }

    private static final String STREAM = "trigger:\n  type: stream\n  records: 500\n  max_wait: 1s\n";
    private static final String BATCH_64 = "  batch:\n    max_files: 64\n";

    private static Map<String, Object> rule(String time, List<String> distinctOn) {
        Map<String, Object> w = new HashMap<>(Map.of("type", "sliding", "size", "10m", "slide", "1m", "time", time));
        if (distinctOn != null) w.put("distinctOn", distinctOn);
        Map<String, Object> m = new HashMap<>();
        m.put("name", "wangiri-burst");
        m.put("dataset", "voice_cdr");
        m.put("measure", "countDistinct(b_number)");
        m.put("by", List.of("a_number"));
        m.put("comparator", "gte");
        m.put("threshold", 30);
        m.put("severity", "CRITICAL");
        m.put("window", w);
        return m;
    }

    private static AlertRule save(Space s, Map<String, Object> body) {
        return AlertRoutes.parseContent(s.root(), s::data, body);
    }

    private static void refused(Space s, Map<String, Object> body, String fragment) {
        ApiException e = assertThrows(ApiException.class, () -> save(s, body));
        assertEquals(422, e.status, e.getMessage());
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }

    @Test
    void aStreamPipelineWithBatchedDrainsAndATimestampColumnIsAccepted(@TempDir Path dir) throws Exception {
        Space s = space(dir, STREAM, BATCH_64);
        AlertRule r = save(s, rule("event_ts", List.of("topic", "partition", "offset")));
        assertTrue(r.isWindowed());
    }

    @Test
    void theTimeColumnMustBeATimestampInTheSchema(@TempDir Path dir) throws Exception {
        Space s = space(dir, STREAM, BATCH_64);
        save(s, rule("event_ts", null));
        refused(s, rule("event_text", null), "is VARCHAR in dataset 'voice_cdr'; a Window needs an event-time TIMESTAMP");
        refused(s, rule("arrived_ts", null), "alert.window.time 'arrived_ts' is not in the Schema of dataset 'voice_cdr'");
    }

    @Test
    void everyDistinctOnColumnMustBeInTheSchema(@TempDir Path dir) throws Exception {
        Space s = space(dir, STREAM, BATCH_64);
        save(s, rule("event_ts", List.of("topic", "offset")));
        refused(s, rule("event_ts", List.of("topic", "seq")), "alert.window.distinctOn column(s) [seq] are not in the Schema");
    }

    /** PA-9d (D-PA44): without the Real-time Detection Add-on a windowed rule is refused at the door, naming the Add-on. */
    @Test
    void aWindowedRuleNeedsTheRealtimeAddOn(@TempDir Path dir) throws Exception {
        Space s = space(dir, STREAM, BATCH_64);
        save(s, rule("event_ts", null));
        com.gamma.etl.EditionFeatures.overrideForTest(java.util.Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
        refused(s, rule("event_ts", null), "ERR_EDITION_FEATURE: Real-time Detection");
        refused(s, sequence("tumbling"), "needs the Real-time Detection Add-on (INS-AO-REALTIME-DETECTION)");
        Map<String, Object> plain = rule("event_ts", null);
        plain.remove("window");
        assertFalse(save(s, plain).isWindowed(), "a rule with no window does not need the Add-on");
    }

    /** PA-9d (owed by 9b and 9c): every field a windowed rule reads must be in the Schema, one key from accepted. */
    @Test
    void everyFieldTheRuleReadsMustBeInTheSchema(@TempDir Path dir) throws Exception {
        Space s = space(dir, STREAM, BATCH_64);
        Map<String, Object> when = rule("event_ts", null);
        when.put("when", Map.of("kind", "group", "op", "AND", "items",
                List.of(Map.of("kind", "condition", "field", "duration_s", "operator", "<", "value", "3"))));
        save(s, when);
        when.put("when", Map.of("kind", "group", "op", "AND", "items",
                List.of(Map.of("kind", "condition", "field", "duraton_s", "operator", "<", "value", "3"))));
        refused(s, when, "reads column(s) [duraton_s] that are not in the Schema of dataset 'voice_cdr'");

        Map<String, Object> measure = rule("event_ts", null);
        measure.put("measure", "countDistinct(b_numbr)");
        refused(s, measure, "reads column(s) [b_numbr]");

        Map<String, Object> step = sequence("tumbling");
        save(s, step);
        step.put("pattern", Map.of("type", "sequence", "steps", List.of(
                Map.of("kind", "group", "op", "AND", "items", List.of(Map.of("kind", "condition", "field", "duration_s", "operator", "<", "value", "3"))),
                Map.of("kind", "group", "op", "AND", "items", List.of(Map.of("kind", "condition", "field", "durations", "operator", ">", "value", "600"))))));
        refused(s, step, "reads column(s) [durations]");
    }

    @Test
    void theWritingPipelineMustHaveAStreamTrigger(@TempDir Path dir) throws Exception {
        save(space(dir.resolve("ok"), STREAM, BATCH_64), rule("event_ts", null));
        refused(space(dir.resolve("poll"), "", BATCH_64), rule("event_ts", null),
                "Pipeline 'cdr_stream' (writing dataset 'voice_cdr') has no trigger {type: stream}");
        refused(space(dir.resolve("manual"), "trigger:\n  type: manual\n", BATCH_64), rule("event_ts", null),
                "has no trigger {type: stream}");
    }

    @Test
    void oneDrainMustCommitAsOneConsignment(@TempDir Path dir) throws Exception {
        save(space(dir.resolve("two"), STREAM, "  batch:\n    max_files: 2\n"), rule("event_ts", null));
        refused(space(dir.resolve("one"), STREAM, "  batch:\n    max_files: 1\n"), rule("event_ts", null),
                "has processing.batch.max_files 1; set it to at least 2");
        refused(space(dir.resolve("default"), STREAM, ""), rule("event_ts", null), "processing.batch.max_files 1");
    }

    @Test
    void theDatasetMustBeWrittenByAPipeline(@TempDir Path dir) throws Exception {
        Space s = space(dir, STREAM, BATCH_64);
        ComponentStore reg = new ComponentStore(s.root().resolve("registry"));
        save(s, rule("event_ts", null));
        // the same data under a store no Pipeline writes
        Files.move(s.data().resolve("cdr_stream"), s.data().resolve("orphan"));
        reg.write("dataset", "voice_cdr", new HashMap<>(Map.of("physicalRef", "orphan")));
        refused(s, rule("event_ts", null), "no Pipeline writes 'orphan'");
    }

    @Test
    void aSchemaThatCannotBeReadIsRefused(@TempDir Path dir) throws Exception {
        Space s = space(dir, STREAM, BATCH_64);
        Map<String, Object> body = rule("event_ts", null);
        body.put("dataset", "absent");
        body.remove("by");   // the by gate would refuse first, for the same reason
        refused(s, body, "alert.window cannot be checked against the Schema of dataset 'absent'");
    }

    @Test
    void theWindowBoundsAre422AtTheDoor(@TempDir Path dir) throws Exception {
        Space s = space(dir, STREAM, BATCH_64);
        Map<String, Object> body = rule("event_ts", null);
        ((Map<String, Object>) body.get("window")).put("lateness", "51m");
        refused(s, body, "size + lateness must be at most 1h");
        ((Map<String, Object>) body.get("window")).put("lateness", "50m");
        save(s, body);
    }

    /** PA-9c: a sequence over voice_cdr — a short call, then a long one from the same A-number within 10 minutes. */
    private static Map<String, Object> sequence(String windowType) {
        Map<String, Object> w = new HashMap<>(Map.of("type", windowType, "size", "10m", "time", "event_ts"));
        if ("sliding".equals(windowType)) w.put("slide", "1m");
        Map<String, Object> m = new HashMap<>();
        m.put("name", "short-then-long");
        m.put("dataset", "voice_cdr");
        m.put("by", List.of("a_number"));
        m.put("window", w);
        m.put("pattern", Map.of("type", "sequence", "steps", List.of(
                Map.of("kind", "group", "op", "AND", "items", List.of(Map.of("kind", "condition", "field", "duration_s", "operator", "<", "value", "3"))),
                Map.of("kind", "group", "op", "AND", "items", List.of(Map.of("kind", "condition", "field", "duration_s", "operator", ">", "value", "600"))))));
        return m;
    }

    @Test
    void aPatternIs422AtTheDoorOneKeyAwayFromAccepted(@TempDir Path dir) throws Exception {
        Space s = space(dir, STREAM, BATCH_64);
        AlertRule ok = save(s, sequence("tumbling"));
        assertTrue(ok.eventWindow().pattern().isSequence());
        refused(s, sequence("sliding"), "a sequence alert.pattern is anchored at its first event");
        Map<String, Object> absence = sequence("sliding");
        absence.put("pattern", Map.of("type", "absence"));
        assertTrue(save(s, absence).eventWindow().pattern().isAbsence());
        absence.put("pattern", Map.of("type", "absence", "within", "5m"));
        refused(s, absence, "alert.pattern.within is not a pattern key");
    }

    @Test
    void aPatternTakesTheSameStreamGatesAsAWindowedMeasure(@TempDir Path dir) throws Exception {
        save(space(dir.resolve("ok"), STREAM, BATCH_64), sequence("tumbling"));
        refused(space(dir.resolve("poll"), "", BATCH_64), sequence("tumbling"), "has no trigger {type: stream}");
        refused(space(dir.resolve("one"), STREAM, ""), sequence("tumbling"), "processing.batch.max_files 1");
    }

    @Test
    void thePostRouteRefusesAWindowedRuleOnADatasetNoStreamWrites(@TempDir Path cfg, @TempDir Path root) throws Exception {
        // a view-backed Dataset: readable Schema, TIMESTAMP column, but no physicalRef naming a Pipeline
        new com.gamma.pipeline.ViewStore(root.resolve("views")).write(new com.gamma.pipeline.ViewDefinition(
                "cdr_view", "pipeline-x", List.of(), "SELECT * FROM (VALUES ('A1', 'B1', TIMESTAMP '2026-11-12 10:00:00')) "
                + "AS t(a_number, b_number, event_ts)", "2026-11-12T00:00:00Z"));
        new ComponentStore(root.resolve("registry")).write("dataset", "voice_cdr", new HashMap<>(Map.of("view", "cdr_view")));
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        try (CollectorService svc = new CollectorService(List.of(pipe), 3600, 1)) {
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            try {
                String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(rule("event_ts", null));
                HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                                URI.create("http://localhost:" + api.port() + "/api/v1/alerts/rules"))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(422, r.statusCode(), r.body());
                assertTrue(r.body().contains("declares no physicalRef naming the Pipeline that writes it"), r.body());
                assertTrue(new ComponentStore(root.resolve("registry")).get("alert-rule", "wangiri-burst").isEmpty(),
                        "nothing was written");
            } finally {
                api.close();
            }
        } finally {
            System.clearProperty("assist.write.root");
        }
    }
}
