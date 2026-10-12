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
 * ANOMALY-DETECTION-RESIDUALS-1 (b), decision D8(b), for the mobile-money and AML packs (sibling of
 * {@link TelcoFraudPackAnomalyPropertyTest}): the SHIPPED pack models scored over a fabricated 35-day corpus
 * (2026-08-27 .. 2026-09-30, generated in SQL, no RNG) assert PROPERTIES only. In each pack 30 background entities
 * behave steadily, {@code spike} jumps on the last day (2026-09-30), {@code steady} is the look-alike (same level
 * every day). No exact score is pinned.
 */
class PackFraudAmlAnomalyPropertyTest {

    private static final Path TEMPLATES = Path.of("..", "..", "spaces", "_templates").toAbsolutePath().normalize();

    /** One pack scored over its fabricated corpus. */
    private record Pack(ComponentStore store, ViewStore views, Path data, AnomalyModel model) {
        Map<String, AnomalyScorer.Scored> scoreFor(String asOf) throws Exception {
            AnomalyScoreEvaluator.Run run = AnomalyScoreEvaluator.evaluate(model, LocalDate.parse(asOf), id ->
                    DatasetRelation.relationSql(store.get("dataset", id).orElseThrow().content(), data, views));
            return run.scored().stream().collect(Collectors.toMap(AnomalyScorer.Scored::entityKey, s -> s));
        }
    }

    private static Pack mm;
    private static Pack aml;

    private static void parquet(Path data, String ref, String sql) throws Exception {
        Path dir = data.resolve(ref);
        Files.createDirectories(dir);
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (" + sql + ") TO '" + dir.resolve("data.parquet").toString().replace('\\', '/')
                    + "' (FORMAT PARQUET)");
        }
    }

    private static Pack load(String pack, String modelId, Path data) {
        Path config = TEMPLATES.resolve(pack).resolve("config");
        ComponentStore store = new ComponentStore(config.resolve("registry"));
        return new Pack(store, new ViewStore(config.resolve("views")), data,
                AnomalyModel.fromMap(modelId, store.get(AnomalyModel.KIND, modelId).orElseThrow().content()));
    }

    @BeforeAll
    static void plant(@TempDir Path dir) throws Exception {
        String who = "who AS (SELECT printf('n%02d', i) AS id, i FROM range(0, 30) t(i) "
                + "UNION ALL SELECT 'spike', 3 UNION ALL SELECT 'steady', 4), "
                + "d AS (SELECT range AS d FROM range(0, 35)), ";
        // mobile-money: 2-4 transactions a day of 40-100 USD; the spike wallet makes 40 transactions on day 34
        Path mmData = dir.resolve("mm");
        parquet(mmData, "wallet_txn/database",
                "WITH " + who + "n AS (SELECT id, d, i, 2 + ((7919 * i + 104729 * d) % 3) AS k FROM who, d), "
                + "t AS (SELECT id, d, i, j FROM n, range(0, 40) r(j) "
                + "      WHERE j < CASE WHEN id = 'spike' AND d = 34 THEN 40 ELSE k END) "
                + "SELECT id AS wallet_id, TIMESTAMP '2026-08-27 10:00:00' + to_days(CAST(d AS INTEGER)) AS txn_ts, "
                + "40 + ((31 * i + 17 * d + 13 * j) % 61) AS amount FROM t");
        parquet(mmData, "wallets/database",
                "SELECT printf('n%02d', i) AS wallet_id, 'T1' AS kyc_tier FROM range(0, 30) t(i) "
                + "UNION ALL SELECT 'spike', 'T1' UNION ALL SELECT 'steady', 'T1'");
        mm = load("mobile-money", "mm_wallet_activity", mmData);
        // aml: 1-3 transfers a day of 900-1500 USD plus a cash-in filler; the spike account wires 20 x 9000 on day 34
        Path amlData = dir.resolve("aml");
        parquet(amlData, "aml_txn/database",
                "WITH " + who + "n AS (SELECT id, d, i, 1 + ((7919 * i + 104729 * d) % 3) AS k FROM who, d), "
                + "t AS (SELECT id, d, i, j, CASE WHEN id = 'spike' AND d = 34 THEN 20 ELSE k END AS cnt "
                + "      FROM n, range(0, 20) r(j)) "
                + "SELECT id AS account_id, TIMESTAMP '2026-08-27 10:00:00' + to_days(CAST(d AS INTEGER)) AS txn_ts, "
                + "CASE WHEN id = 'spike' AND d = 34 THEN 'WIRE' ELSE 'TRANSFER' END AS txn_type, "
                + "CASE WHEN id = 'spike' AND d = 34 THEN 9000 ELSE 900 + ((37 * i + 11 * d + 7 * j) % 601) END AS amount "
                + "FROM t WHERE j < cnt "
                + "UNION ALL SELECT id, TIMESTAMP '2026-08-27 11:00:00' + to_days(CAST(d AS INTEGER)), 'CASH_IN', 5000 FROM n");
        aml = load("aml", "aml_account_transfers", amlData);
    }

    private static void assertSpikeElevatedAndTop(Map<String, AnomalyScorer.Scored> s, String feature) {
        assertEquals(32, s.size(), "30 background + spike + steady");
        AnomalyScorer.Scored spike = s.get("spike");
        assertTrue("elevated".equals(spike.band()) || "high".equals(spike.band()),
                "the planted day must be elevated or high, was " + spike.band() + " " + spike.score());
        double best = s.values().stream().filter(x -> !x.entityKey().equals("spike"))
                .mapToDouble(AnomalyScorer.Scored::score).max().orElseThrow();
        assertTrue(spike.score() > best, "it outranks every normal entity: " + spike.score() + " vs " + best);
        assertTrue(spike.features().stream().anyMatch(f -> f.feature().equals(feature)), feature + " is explained");
    }

    private static void assertOthersNormal(Pack p) throws Exception {
        for (var e : p.scoreFor("2026-10-01").entrySet())
            if (!e.getKey().equals("spike")) assertEquals("normal", e.getValue().band(), e.getKey());
        assertEquals("normal", p.scoreFor("2026-09-30").get("spike").band(), "spike the day before");
    }

    @Test
    void mobileMoneyPlantedWalletIsElevatedAndTop() throws Exception {
        assertSpikeElevatedAndTop(mm.scoreFor("2026-10-01"), "txn_count");
    }

    @Test
    void mobileMoneyLookAlikeAndOthersStayNormal() throws Exception {
        assertOthersNormal(mm);
    }

    @Test
    void amlPlantedAccountIsElevatedAndTop() throws Exception {
        assertSpikeElevatedAndTop(aml.scoreFor("2026-10-01"), "out_count");
    }

    @Test
    void amlLookAlikeAndOthersStayNormal() throws Exception {
        assertOthersNormal(aml);
    }
}
