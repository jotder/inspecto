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
 * The PEER golden corpus (design §14, slice S3), generated deterministically in SQL ({@code range()} plus a
 * Weyl-style modular sequence — no RNG state). Dataset {@code traffic(msisdn, event_time, mb)}, 60 days 2026-08-02 ..
 * 2026-09-30 (scored 2026-09-30, {@code as_of} 2026-10-01), and the reference Dataset {@code subscribers(msisdn, plan)}
 * that carries the cohort. Background, one row a day, noise {@code n = ((7919·i + 104729·d) mod 41) − 20}:
 * {@code PRE} 120 subscribers {@code p000..} at {@code 100 + 10·(i mod 10) + n}; {@code POST} 120 {@code q000..} at
 * {@code 500 + 10·(i mod 10) + n}; {@code PROMO} 100 {@code r000..} at {@code 200 + 10·(i mod 10) + n}, DOUBLED on the
 * scored day (a promotion: the whole cohort moves together).
 *
 * <p>Planted, must be {@code high}: {@code simbox} (U2, PRE — always 7 short sessions of ≈ 50 MB a day: normal for
 * itself, moderately unusual vs peers on BOTH features); {@code adjuster} (U5, POST — always ≈ 5 000, ten times its
 * peers, on one feature); {@code promo_spike} (PROMO — usual ≈ 250, scored day 2 500 while its cohort only doubles).
 * Look-alikes, must stay {@code normal}: every PROMO member (the cohort shift); {@code newpeer} (PRE, 2 days of history
 * at a PRE-typical 140 — peer-scored only, D-AD12); {@code loner} (cohort {@code SOLO} of one — no peer score, D-AD11).
 */
public final class AnomalyPeerCorpus {
    private AnomalyPeerCorpus() {}

    public static final String MODEL = "peers";
    public static final LocalDate AS_OF = LocalDate.parse("2026-10-01");
    public static final List<String> PLANTED = List.of("adjuster", "promo_spike", "simbox");

    static final String NOISE = "((7919 * i + 104729 * d) % 41) - 20";
    static final String ROWS = "WITH d AS (SELECT range AS d FROM range(0, 60)), "
            + "pre AS (SELECT printf('p%03d', i) AS msisdn, d, 100 + 10 * (i % 10) + " + NOISE + " AS mb FROM range(0, 120) t(i), d), "
            + "post AS (SELECT printf('q%03d', i) AS msisdn, d, 500 + 10 * (i % 10) + " + NOISE + " AS mb FROM range(0, 120) t(i), d), "
            + "promo AS (SELECT printf('r%03d', i) AS msisdn, d, (200 + 10 * (i % 10) + " + NOISE + ") * CASE WHEN d = 59 THEN 2 ELSE 1 END AS mb "
            + "          FROM range(0, 100) t(i), d), "
            + "simbox AS (SELECT 'simbox' AS msisdn, d, 50 + ((104729 * d + 7919 * s) % 11) - 5 AS mb FROM range(0, 7) t(s), d), "
            + "adjuster AS (SELECT 'adjuster' AS msisdn, d, 5000 + ((104729 * d) % 41) - 20 AS mb FROM d), "
            + "promo_spike AS (SELECT 'promo_spike' AS msisdn, d, CASE WHEN d = 59 THEN 2500 ELSE 250 + ((104729 * d) % 21) - 10 END AS mb FROM d), "
            + "newpeer AS (SELECT 'newpeer' AS msisdn, d, 140 AS mb FROM d WHERE d >= 57), "
            + "loner AS (SELECT 'loner' AS msisdn, d, 300 + ((104729 * d) % 21) - 10 AS mb FROM d), "
            + "u AS (SELECT * FROM pre UNION ALL SELECT * FROM post UNION ALL SELECT * FROM promo UNION ALL SELECT * FROM simbox "
            + "      UNION ALL SELECT * FROM adjuster UNION ALL SELECT * FROM promo_spike UNION ALL SELECT * FROM newpeer "
            + "      UNION ALL SELECT * FROM loner) "
            + "SELECT msisdn, TIMESTAMP '2026-08-02 10:00:00' + to_days(CAST(d AS INTEGER)) AS event_time, CAST(mb AS DOUBLE) AS mb FROM u";

    static final String SUBSCRIBERS = "SELECT printf('p%03d', i) AS msisdn, 'PRE' AS plan FROM range(0, 120) t(i) "
            + "UNION ALL SELECT printf('q%03d', i), 'POST' FROM range(0, 120) t(i) "
            + "UNION ALL SELECT printf('r%03d', i), 'PROMO' FROM range(0, 100) t(i) "
            + "UNION ALL VALUES ('simbox', 'PRE'), ('adjuster', 'POST'), ('promo_spike', 'PROMO'), ('newpeer', 'PRE'), "
            + "('loner', 'SOLO')";

    public static Map<String, Object> plant(Path configRoot, Path dataDir) throws Exception {
        DuckDbUtil.loadDriver();
        ComponentStore store = new ComponentStore(configRoot.resolve("registry"));
        store.write("dataset", "traffic", Map.of("physicalRef", "traffic"));
        store.write("dataset", "subscribers", Map.of("physicalRef", "subscribers"));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (" + ROWS + ") TO '" + file(dataDir, "traffic") + "' (FORMAT PARQUET)");
            st.execute("COPY (" + SUBSCRIBERS + ") TO '" + file(dataDir, "subscribers") + "' (FORMAT PARQUET)");
        }
        Map<String, Object> model = model();
        store.write(AnomalyModel.KIND, MODEL, model);
        return model;
    }

    private static String file(Path dataDir, String name) throws Exception {
        Path dir = dataDir.resolve(name);
        Files.createDirectories(dir);
        return dir.resolve("data.parquet").toString().replace('\\', '/');
    }

    public static Map<String, Object> model() {
        Map<String, Object> m = new LinkedHashMap<>(AnomalyCorpus.model());
        List<Map<String, Object>> fs = ((List<?>) m.get("features")).stream().map(f -> {
            Map<String, Object> c = new LinkedHashMap<>((Map<String, Object>) f);
            c.put("dataset", "traffic");
            return c;
        }).toList();
        m.put("features", fs);
        Map<String, Object> peers = new LinkedHashMap<>();
        peers.put("by", List.of("plan"));
        peers.put("dataset", "subscribers");
        peers.put("minGroupSize", 30);
        m.put("peers", peers);
        return m;
    }
}
