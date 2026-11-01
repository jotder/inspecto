package com.gamma.predictive;

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
 * A synthetic churn corpus (no customer data): Dataset {@code orders(customer_id, order_date, amount)}, model
 * {@code churn} as of 2026-06-30 with a 90-day window (2026-04-01, 2026-06-30] and {@code minHistoryDays} 30:
 *
 * <pre>
 * intercept -1
 * quiet   recency(order_date)  cap 120, missing 120   coefficient  0.03
 * orders  count                log                    coefficient -1
 * spend   sum(amount)          z mean 100 scale 50    coefficient -0.5
 *
 * customer  first seen   quiet  orders  spend   logit                          score    band
 * steady    2025-01-05   2      3       300     -1 + 0.06 - ln 4 - 2   = -4.326294    1.3044  normal
 * lapsing   2025-09-01   60     1       60      -1 + 1.8  - ln 2 + 0.4 =  0.506853   62.4068  elevated
 * fading    2025-03-01   86     1       40      -1 + 2.58 - ln 2 + 0.6 =  1.486853   81.5605  high
 * gone      2025-06-01   (120)  0       0       -1 + 3.6  - 0    + 1   =  3.6        97.3403  high  (no row in the window)
 * newbie    2026-06-20   10 days of history &lt; 30                                       NULL     insufficient
 * future    -            its only order is after as_of: no row
 * (NULL)    -            a NULL key: dropped
 * </pre>
 */
final class ChurnCorpus {
    private ChurnCorpus() {}

    static final String MODEL = "churn";
    static final LocalDate AS_OF = LocalDate.parse("2026-06-30");
    static final Map<String, Double> LOGIT = Map.of("steady", -1 + 0.06 - Math.log(4) - 2, "lapsing",
            -1 + 1.8 - Math.log(2) + 0.4, "fading", -1 + 2.58 - Math.log(2) + 0.6, "gone", -1 + 3.6 + 0 + 1.0);
    static final Map<String, String> BAND = Map.of("steady", "normal", "lapsing", "elevated", "fading", "high",
            "gone", "high", "newbie", "insufficient");

    static final String ROWS = "SELECT * FROM (VALUES "
            + "('steady', TIMESTAMP '2025-01-05 10:00:00', 80.0), ('steady', TIMESTAMP '2026-05-20 10:00:00', 100.0), "
            + "('steady', TIMESTAMP '2026-06-10 10:00:00', 100.0), ('steady', TIMESTAMP '2026-06-28 10:00:00', 100.0), "
            + "('lapsing', TIMESTAMP '2025-09-01 09:00:00', 70.0), ('lapsing', TIMESTAMP '2026-05-01 09:00:00', 60.0), "
            + "('fading', TIMESTAMP '2025-03-01 12:00:00', 90.0), ('fading', TIMESTAMP '2026-02-01 12:00:00', 90.0), "
            + "('fading', TIMESTAMP '2026-04-05 12:00:00', 40.0), "
            + "('gone', TIMESTAMP '2025-06-01 08:00:00', 300.0), ('gone', TIMESTAMP '2026-01-15 08:00:00', 300.0), "
            + "('newbie', TIMESTAMP '2026-06-20 08:00:00', 25.0), "
            + "('future', TIMESTAMP '2026-07-02 08:00:00', 999.0), "
            + "(CAST(NULL AS VARCHAR), TIMESTAMP '2026-06-01 08:00:00', 5.0)"
            + ") t(customer_id, order_date, amount)";

    static Map<String, Object> plant(Path configRoot, Path dataDir) throws Exception {
        DuckDbUtil.loadDriver();
        ComponentStore store = new ComponentStore(configRoot.resolve("registry"));
        store.write("dataset", "orders", Map.of("physicalRef", "orders"));
        Path dir = dataDir.resolve("orders");
        Files.createDirectories(dir);
        String file = dir.resolve("data.parquet").toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (" + ROWS + ") TO '" + file + "' (FORMAT PARQUET)");
        }
        Map<String, Object> model = model();
        store.write(ChurnModel.KIND, MODEL, model);
        return model;
    }

    static Map<String, Object> model() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entityType", "customer");
        m.put("dataset", "orders");
        m.put("key", "customer_id");
        m.put("time", "order_date");
        m.put("window", 90);
        m.put("intercept", -1);
        m.put("inputs", List.of(
                input("quiet", "recency(order_date)", "cap", Map.of("cap", 120, "missing", 120), 0.03),
                input("orders", "count", "log", Map.of(), -1),
                input("spend", "sum(amount)", "z", Map.of("mean", 100, "scale", 50), -0.5)));
        m.put("minHistoryDays", 30);
        m.put("elevatedThreshold", 60);
        m.put("highThreshold", 80);
        return m;
    }

    static Map<String, Object> input(String name, String measure, String transform, Map<String, Object> params,
                                     double coefficient) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("measure", measure);
        m.put("transform", transform);
        m.putAll(params);
        m.put("coefficient", coefficient);
        return m;
    }
}
