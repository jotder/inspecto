package com.gamma.anomaly;

import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ANOMALY-DETECTION-RESIDUALS-1 (b), decision D8(b): the SHIPPED telco-fraud pack model {@code tf_subscriber_usage}
 * scored over a fabricated five-week CDR corpus asserts PROPERTIES only - a known anomalous day is elevated, a
 * normal day is normal. No exact score is pinned here (those live in {@link AnomalyGoldenCorpusTest}), so a
 * legitimate pack-model tune does not break this test; a pack model that stops detecting does.
 *
 * <p>The corpus is generated in SQL (no RNG): 30 background subscribers {@code n00..n29} with one outgoing voice
 * call, one SMS and one data session a day for 35 days 2026-08-27 .. 2026-09-30; {@code spike} behaves identically
 * except that on 2026-09-30 its outgoing voice is 80x its usual; {@code steady} is the look-alike (same level,
 * every day, including the 30th).
 */
class TelcoFraudPackAnomalyPropertyTest {

    private static final Path PACK_CONFIG =
            Path.of("..", "..", "spaces", "_templates", "telco-fraud", "config").toAbsolutePath().normalize();
    private static final String MODEL = "tf_subscriber_usage";

    private static ComponentStore store;
    private static ViewStore views;
    private static Path data;
    private static AnomalyModel model;

    @BeforeAll
    static void plant(@TempDir Path dir) throws Exception {
        data = dir.resolve("data");
        Path cdr = data.resolve("cdr").resolve("database");
        Files.createDirectories(cdr);
        String file = cdr.resolve("data.parquet").toString().replace('\\', '/');
        String sql = "WITH d AS (SELECT range AS d FROM range(0, 35)), "
                + "who AS (SELECT printf('n%02d', i) AS a_number, i FROM range(0, 30) t(i) "
                + "        UNION ALL SELECT 'spike', 3 UNION ALL SELECT 'steady', 4), "
                + "base AS (SELECT a_number, d, i, 300 + 20 * (i % 10) + ((7919 * i + 104729 * d) % 61) - 30 AS secs "
                + "         FROM who, d), "
                + "voice AS (SELECT a_number, d, 'VOICE' AS service, "
                + "          CASE WHEN a_number = 'spike' AND d = 34 THEN secs * 80 ELSE secs END AS duration_s FROM base), "
                + "sms AS (SELECT a_number, d, 'SMS' AS service, 1 AS duration_s FROM base), "
                + "dat AS (SELECT a_number, d, 'DATA' AS service, secs AS duration_s FROM base) "
                + "SELECT TIMESTAMP '2026-08-27 10:00:00' + to_days(CAST(d AS INTEGER)) AS start_ts, a_number, "
                + "       'MO' AS direction, service, CAST(duration_s AS INTEGER) AS duration_s FROM "
                + "(SELECT * FROM voice UNION ALL SELECT * FROM sms UNION ALL SELECT * FROM dat)";
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (" + sql + ") TO '" + file + "' (FORMAT PARQUET)");
        }
        store = new ComponentStore(PACK_CONFIG.resolve("registry"));
        views = new ViewStore(PACK_CONFIG.resolve("views"));
        model = AnomalyModel.fromMap(MODEL, store.get(AnomalyModel.KIND, MODEL).orElseThrow().content());
    }

    private static Map<String, AnomalyScorer.Scored> scoreFor(String asOf) throws Exception {
        AnomalyScoreEvaluator.Run run = AnomalyScoreEvaluator.evaluate(model, LocalDate.parse(asOf), id ->
                DatasetRelation.relationSql(store.get("dataset", id).orElseThrow().content(), data, views));
        return run.scored().stream().collect(Collectors.toMap(AnomalyScorer.Scored::entityKey, s -> s));
    }

    @Test
    void theKnownAnomalousDayIsElevatedAndTheTopScore() throws Exception {
        Map<String, AnomalyScorer.Scored> s = scoreFor("2026-10-01");
        assertEquals(32, s.size(), "30 background + spike + steady, scored for 2026-09-30");
        AnomalyScorer.Scored spike = s.get("spike");
        assertTrue("elevated".equals(spike.band()) || "high".equals(spike.band()),
                "the 80x voice day must be elevated or high, was " + spike.band() + " " + spike.score());
        double best = s.values().stream().filter(x -> !x.entityKey().equals("spike"))
                .mapToDouble(AnomalyScorer.Scored::score).max().orElseThrow();
        assertTrue(spike.score() > best, "it outranks every normal subscriber: " + spike.score() + " vs " + best);
        assertTrue(spike.features().stream().anyMatch(f -> f.feature().equals("voice_out_s") && f.share() > 0.5),
                "the voice feature carries most of the explanation");
    }

    @Test
    void aNormalDayAndTheLookAlikeStayNormal() throws Exception {
        Map<String, AnomalyScorer.Scored> thirtieth = scoreFor("2026-10-01");
        assertEquals("normal", thirtieth.get("steady").band(), "the look-alike on the anomalous date");
        for (var e : thirtieth.entrySet())
            if (!e.getKey().equals("spike")) assertEquals("normal", e.getValue().band(), e.getKey());
        // the SAME entity, the day before the spike: nothing unusual yet
        assertEquals("normal", scoreFor("2026-09-30").get("spike").band(), "spike on a normal day");
    }
}
