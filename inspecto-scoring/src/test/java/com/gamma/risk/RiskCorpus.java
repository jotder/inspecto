package com.gamma.risk;

import com.gamma.pipeline.ComponentStore;
import com.gamma.util.DuckDbUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A synthetic subscriber corpus on real DuckDB/Parquet, shared by the Job and Alert Rule tests.
 *
 * <pre>
 * topups(msisdn, status, amount, topup_id)   m1: 3 FAILED (10,20,30) · m2: 1 FAILED (5) · m3: 1 OK (100)
 * sim_swaps(msisdn, swap_id)                 m1: 2 (or 0 when healed) · m4: 1
 * </pre>
 * Model {@code subs}: failed_topups = count(FAILED) × 10 cap 25 · sim_swaps = count × 20 · spend = sum(amount) × 0.01;
 * high at 50. Expected: m1 = 25 + 40 + 0.6 = 65.6 (high) · m2 = 10 + 0 (missing) + 0.05 · m3 = 0 (missing) + 0 + 1
 * · m4 = 0 (missing) + 20 + 0 (missing).
 */
public final class RiskCorpus {

    private RiskCorpus() {}

    public static final String MODEL = "subs";

    /** Plant the Datasets (registry + Parquet) and the model under {@code configRoot}/{@code dataDir}. */
    public static Map<String, Object> plant(Path configRoot, Path dataDir, boolean m1Healed) throws Exception {
        DuckDbUtil.loadDriver();
        ComponentStore store = new ComponentStore(configRoot.resolve("registry"));
        store.write("dataset", "topups", Map.of("physicalRef", "topups"));
        store.write("dataset", "sim_swaps", Map.of("physicalRef", "sim_swaps"));
        parquet(dataDir.resolve("topups"), "SELECT * FROM (VALUES "
                + "('m1','FAILED',10.0,'t1'),('m1','FAILED',20.0,'t2'),('m1','FAILED',30.0,'t3'),"
                + "('m2','FAILED',5.0,'t4'),('m3','OK',100.0,'t5')) AS v(msisdn, status, amount, topup_id)");
        parquet(dataDir.resolve("sim_swaps"), "SELECT * FROM (VALUES "
                + (m1Healed ? "" : "('m1','s1'),('m1','s2'),") + "('m4','s3')) AS v(msisdn, swap_id)");
        Map<String, Object> model = model();
        store.write("risk-score", MODEL, model);
        return model;
    }

    public static Map<String, Object> model() {
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put("id", "failed_topups");
        failed.put("label", "Failed top-ups");
        failed.put("dataset", "topups");
        failed.put("key", "msisdn");
        failed.put("measure", "count");
        failed.put("filters", List.of(Map.of("field", "status", "op", "=", "value", "FAILED")));
        failed.put("weight", 10);
        failed.put("cap", 25);
        failed.put("evidence", List.of("topup_id", "amount"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entityType", "subscriber");
        m.put("highThreshold", 50);
        m.put("factors", List.of(failed,
                Map.of("id", "sim_swaps", "dataset", "sim_swaps", "key", "msisdn", "measure", "count",
                        "weight", 20, "evidence", List.of("swap_id")),
                Map.of("id", "spend", "dataset", "topups", "key", "msisdn", "measure", "sum(amount)",
                        "weight", 0.01)));
        return m;
    }

    private static void parquet(Path dir, String select) throws Exception {
        Files.createDirectories(dir);
        Files.deleteIfExists(dir.resolve("data.parquet"));   // a re-plant rewrites the Dataset
        String file = dir.resolve("data.parquet").toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (" + select + ") TO '" + file + "' (FORMAT PARQUET)");
        }
    }
}
