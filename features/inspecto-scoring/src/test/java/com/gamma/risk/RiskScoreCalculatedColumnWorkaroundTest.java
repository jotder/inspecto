package com.gamma.risk;

import com.gamma.mask.EvidenceMasker;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.DatasetRelation;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-RISK-SCORE-RESIDUALS-1 item (7): a Risk Score factor has no arithmetic, so the supported workaround is to
 * put the arithmetic in a Dataset {@code calculated} column and aggregate THAT column. This test is the worked
 * example in {@code okf/product/module/scoring/spec-risk-scores.md}: it must stay in step with that doc.
 */
class RiskScoreCalculatedColumnWorkaroundTest {

    private static final Map<String, Object> DATASET = Map.of(
            "physicalRef", "wallet_tx",
            "calculated", List.of(
                    Map.of("name", "net_amount", "expr", "amount - fee"),
                    Map.of("name", "fee_pct", "expr", "round(100.0 * fee / nullif(amount, 0), 1)")));

    private static RiskScoreModel model(String measure) {
        return RiskScoreModel.fromMap("w", Map.of("entityType", "wallet", "highThreshold", 50,
                "factors", List.of(Map.of("id", "f", "dataset", "wallet_tx", "key", "wallet", "measure", measure,
                        "weight", 0.1))));
    }

    private static Path plant(Path root) throws Exception {
        DuckDbUtil.loadDriver();
        Path config = root.resolve("config"), data = root.resolve("data");
        new ComponentStore(config.resolve("registry")).write("dataset", "wallet_tx", DATASET);
        Path dir = Files.createDirectories(data.resolve("wallet_tx"));
        String file = dir.resolve("data.parquet").toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('w1', 100.0, 10.0), ('w1', 50.0, 5.0), ('w2', 200.0, 0.0), "
                    + "('w3', 0.0, 0.0)) AS v(wallet, amount, fee)) TO '" + file + "' (FORMAT PARQUET)");
        }
        return root;
    }

    private static Map<String, Double> score(Path root, String measure) throws Exception {
        Path config = root.resolve("config"), data = root.resolve("data");
        ComponentStore store = new ComponentStore(config.resolve("registry"));
        EvidenceMasker noMask = EvidenceMasker.of(store, config, List.of("wallet_tx"));
        var run = RiskScoreEvaluator.evaluate(model(measure), id ->
                DatasetRelation.relationSql(store.get("dataset", id).orElseThrow().content(), data, null), noMask);
        return run.scored().stream().collect(Collectors.toMap(RiskScorer.Scored::entityKey, RiskScorer.Scored::score));
    }

    @Test
    void aFactorAggregatesACalculatedColumnAndSavesAgainstTheSchema(@TempDir Path root) throws Exception {
        plant(root);
        // the save gate (same check the route runs) sees the calculated column as part of the Dataset Schema
        assertDoesNotThrow(() -> RiskScoreRoutes.requireStorable(root.resolve("config"),
                () -> root.resolve("data"), model("sum(net_amount)")));
        Map<String, Double> s = score(root, "sum(net_amount)");
        assertEquals(13.5, s.get("w1"), 1e-9, "(100-10) + (50-5)");
        assertEquals(20.0, s.get("w2"), 1e-9);
        assertEquals(0.0, s.get("w3"), 1e-9);
    }

    @Test
    void aRatioCalculatedColumnUsesNullifSoZeroDivisionIsNullNotAnError(@TempDir Path root) throws Exception {
        plant(root);
        Map<String, Double> s = score(root, "max(fee_pct)");
        assertEquals(1.0, s.get("w1"), 1e-9, "weight 0.1 x 10 percent: 10/100 and 5/50 are both 10 percent");
        assertEquals(0.0, s.get("w2"), 1e-9);
        assertTrue(s.get("w3") == null || s.get("w3") == 0.0, "amount 0 gives NULL, which scores as no signal: " + s.get("w3"));
    }

    @Test
    void arithmeticInsideTheMeasureStringIsStillRefused() {
        assertThrows(IllegalArgumentException.class, () -> model("sum(amount - fee)"));
    }
}
