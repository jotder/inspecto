package com.gamma.anomaly;

import com.gamma.pipeline.ComponentStore;
import com.gamma.util.DuckDbUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The golden corpus (design §14), generated deterministically in SQL ({@code range()} plus a Weyl-style modular
 * sequence — no RNG state): Dataset {@code usage(msisdn, event_time, mb)}, 60 days 2026-08-02 .. 2026-09-30, the
 * scored day 2026-09-30 ({@code as_of} 2026-10-01). 300 background subscribers {@code n000..n299}: one row a day,
 * {@code mb = 100 + 10·(i mod 50) + ((7919·i + 104729·d) mod 41) − 20}.
 *
 * <p>Planted anomalies: {@code spike} (usual ≈ 300 MB in one session; scored day 10 sessions of 300 MB) and
 * {@code masked} (usual ≈ 200 MB, three 5 000 MB days inside its baseline, scored day 900 MB — a mean/σ baseline
 * is inflated by those days and misses it). Look-alikes that must stay {@code normal}: {@code heavy} (always ≈ 5 000),
 * {@code flat} (always exactly 5, scored 6 — only the MAD floor keeps it from z = ∞), {@code newbie} (2 days of
 * history at 9 999 — insufficient), {@code drop} (usual ≈ 300, nothing on the scored day — a fall, and the model
 * looks {@code up}).
 */
public final class AnomalyCorpus {
    private AnomalyCorpus() {}

    public static final String MODEL = "usage";
    public static final LocalDate AS_OF = LocalDate.parse("2026-10-01");
    public static final List<String> PLANTED = List.of("masked", "spike");
    public static final List<String> LOOK_ALIKES = List.of("drop", "flat", "heavy", "newbie");

    /** {@code day d} is 2026-08-02 + d, d = 0..59; d = 59 is the scored day. */
    static final String ROWS = "WITH d AS (SELECT range AS d FROM range(0, 60)), "
            + "bg AS (SELECT printf('n%03d', i) AS msisdn, d, 100 + 10 * (i % 50) + ((7919 * i + 104729 * d) % 41) - 20 AS mb "
            + "       FROM range(0, 300) t(i), d), "
            + "spike AS (SELECT 'spike' AS msisdn, d, 300 + ((104729 * d) % 21) - 10 AS mb FROM d WHERE d < 59 "
            + "          UNION ALL SELECT 'spike', 59, 300 FROM range(0, 10)), "
            + "masked AS (SELECT 'masked' AS msisdn, d, CASE WHEN d IN (40, 47, 52) THEN 5000 WHEN d = 59 THEN 900 "
            + "           ELSE 200 + ((104729 * d) % 21) - 10 END AS mb FROM d), "
            + "heavy AS (SELECT 'heavy' AS msisdn, d, 5000 + ((104729 * d) % 41) - 20 AS mb FROM d), "
            + "flat AS (SELECT 'flat' AS msisdn, d, CASE WHEN d = 59 THEN 6 ELSE 5 END AS mb FROM d), "
            + "newbie AS (SELECT 'newbie' AS msisdn, d, 9999 AS mb FROM d WHERE d >= 57), "
            + "dropped AS (SELECT 'drop' AS msisdn, d, 300 + ((104729 * d) % 21) - 10 AS mb FROM d WHERE d < 59), "
            + "u AS (SELECT * FROM bg UNION ALL SELECT * FROM spike UNION ALL SELECT * FROM masked UNION ALL "
            + "      SELECT * FROM heavy UNION ALL SELECT * FROM flat UNION ALL SELECT * FROM newbie UNION ALL SELECT * FROM dropped) "
            + "SELECT msisdn, TIMESTAMP '2026-08-02 10:00:00' + to_days(CAST(d AS INTEGER)) AS event_time, CAST(mb AS DOUBLE) AS mb FROM u";

    public static Map<String, Object> plant(Path configRoot, Path dataDir) throws Exception {
        DuckDbUtil.loadDriver();
        ComponentStore store = new ComponentStore(configRoot.resolve("registry"));
        store.write("dataset", "usage", Map.of("physicalRef", "usage"));
        Path dir = dataDir.resolve("usage");
        Files.createDirectories(dir);
        String file = dir.resolve("data.parquet").toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (" + ROWS + ") TO '" + file + "' (FORMAT PARQUET)");
        }
        Map<String, Object> model = model();
        store.write(AnomalyModel.KIND, MODEL, model);
        return model;
    }

    public static Map<String, Object> model() {
        Map<String, Object> mb = new LinkedHashMap<>();
        mb.put("id", "data_mb");
        mb.put("label", "Data volume (MB)");
        mb.put("dataset", "usage");
        mb.put("key", "msisdn");
        mb.put("time", "event_time");
        mb.put("measure", "sum(mb)");
        mb.put("direction", "up");
        mb.put("weight", 1);
        Map<String, Object> sessions = new LinkedHashMap<>();
        sessions.put("id", "sessions");
        sessions.put("label", "Sessions");
        sessions.put("dataset", "usage");
        sessions.put("key", "msisdn");
        sessions.put("time", "event_time");
        sessions.put("measure", "count");
        sessions.put("direction", "up");
        sessions.put("weight", 1);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entityType", "subscriber");
        m.put("window", 28);
        m.put("minBaselinePoints", 7);
        m.put("features", List.of(mb, sessions));
        return m;
    }
}
