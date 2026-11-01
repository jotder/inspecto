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
import java.util.Map;

/**
 * A synthetic forecast corpus (no customer data), as of 2026-06-30 (a Tuesday).
 *
 * <p>Dataset {@code sales(sku, sold_at TIMESTAMP, qty DOUBLE)}, day grain, history 28 days (2026-06-03..2026-06-30,
 * day {@code t} = 2026-06-03 + t):
 * <pre>
 * trend   qty = 100 + 2t + S[t mod 7], S = -3,-2,-1,0,1,2,3   (two rows a day, halves; plus rows on 2026-05-01 and
 *         on 2026-06-02, the day before the history, that must not count)
 * gappy   qty = 5 on t mod 7 in {0, 3}, NO row on the other days (they are 0)
 * short   10 days of history (t = 18..27) &lt; 14                -> insufficient
 * future  its only row is after as_of                          -> no row
 * (NULL)  a NULL key                                           -> dropped
 * </pre>
 * Model {@code demand}: holt-winters (α .5, β .3, γ .2), horizon 10 — trend + season is reproduced exactly, so
 * step h forecasts {@code 100 + 2(27 + h) + S[(27 + h) mod 7]} with a zero backtest error; seasonal-naive misses the
 * trend by 2·7 = 14 a bucket on the hold-out, so the relative MAE is 0. Model {@code naive}: seasonal-naive, horizon
 * 7, 80 % interval — trend's backtest MAE = RMSE = 14 and the interval is {@code ±1.2816·14}.
 *
 * <p>Dataset {@code weekly(sku, sold_at, qty)}, week grain, history 104: sku {@code w} has one row each Wednesday of
 * the 104 complete weeks from 2024-07-01 to the week of 2026-06-22, {@code qty = 10 + (w mod 52)}, and one row in
 * the incomplete week of as_of (2026-06-30) that must not count. Model {@code weekly}: seasonal-naive, horizon 4 —
 * forecasts 10, 11, 12, 13 for the weeks of 2026-06-29 .. 2026-07-20.
 */
final class ForecastCorpus {
    private ForecastCorpus() {}

    static final LocalDate AS_OF = LocalDate.parse("2026-06-30");
    static final LocalDate T0 = LocalDate.parse("2026-06-03");
    static final double[] S = {-3, -2, -1, 0, 1, 2, 3};

    static double trend(int t) { return 100 + 2 * t + S[t % 7]; }

    static final String SALES = "SELECT sku, CAST(sold_at AS TIMESTAMP) AS sold_at, qty FROM ("
            + "SELECT 'trend' AS sku, DATE '2026-06-03' + CAST(t AS INTEGER) + INTERVAL 9 HOUR AS sold_at, "
            + "  (100 + 2 * t + [-3, -2, -1, 0, 1, 2, 3][CAST(t % 7 AS INTEGER) + 1]) / 2.0 AS qty FROM range(28) r(t) "
            + "UNION ALL SELECT 'trend', DATE '2026-06-03' + CAST(t AS INTEGER) + INTERVAL 17 HOUR, "
            + "  (100 + 2 * t + [-3, -2, -1, 0, 1, 2, 3][CAST(t % 7 AS INTEGER) + 1]) / 2.0 FROM range(28) r(t) "
            + "UNION ALL SELECT 'trend', TIMESTAMP '2026-05-01 09:00:00', 100000.0 "
            + "UNION ALL SELECT 'trend', TIMESTAMP '2026-06-02 23:00:00', 100000.0 "
            + "UNION ALL SELECT 'gappy', DATE '2026-06-03' + CAST(t AS INTEGER) + INTERVAL 12 HOUR, 5.0 "
            + "  FROM range(28) r(t) WHERE t % 7 IN (0, 3) "
            + "UNION ALL SELECT 'short', DATE '2026-06-03' + CAST(t AS INTEGER) + INTERVAL 12 HOUR, 1.0 "
            + "  FROM range(18, 28) r(t) "
            + "UNION ALL SELECT 'future', TIMESTAMP '2026-07-02 08:00:00', 999.0 "
            + "UNION ALL SELECT CAST(NULL AS VARCHAR), TIMESTAMP '2026-06-10 08:00:00', 5.0)";

    static final String WEEKLY = "SELECT sku, CAST(sold_at AS TIMESTAMP) AS sold_at, qty FROM ("
            + "SELECT 'w' AS sku, DATE '2024-07-03' + CAST(7 * w AS INTEGER) + INTERVAL 10 HOUR AS sold_at, "
            + "  CAST(10 + w % 52 AS DOUBLE) AS qty FROM range(104) r(w) "
            + "UNION ALL SELECT 'w', TIMESTAMP '2026-06-30 10:00:00', 5000.0)";

    static void plant(Path configRoot, Path dataDir) throws Exception {
        DuckDbUtil.loadDriver();
        ComponentStore store = new ComponentStore(configRoot.resolve("registry"));
        for (Map.Entry<String, String> ds : Map.of("sales", SALES, "weekly", WEEKLY).entrySet()) {
            store.write("dataset", ds.getKey(), Map.of("physicalRef", ds.getKey()));
            Path dir = dataDir.resolve(ds.getKey());
            Files.createDirectories(dir);
            String file = dir.resolve("data.parquet").toString().replace('\\', '/');
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
                st.execute("COPY (" + ds.getValue() + ") TO '" + file + "' (FORMAT PARQUET)");
            }
        }
        store.write(ForecastModel.KIND, "demand", demand());
        store.write(ForecastModel.KIND, "naive", naive());
        store.write(ForecastModel.KIND, "weekly", weekly());
    }

    /** The holt-winters model over {@code sales}. */
    static Map<String, Object> demand() {
        Map<String, Object> m = base("sales", "day", "holt-winters", 10, 28);
        m.put("alpha", 0.5);
        m.put("beta", 0.3);
        m.put("gamma", 0.2);
        return m;
    }

    /** The seasonal-naive model over {@code sales}, 80 % interval. */
    static Map<String, Object> naive() {
        Map<String, Object> m = base("sales", "day", "seasonal-naive", 7, 28);
        m.put("interval", 80);
        return m;
    }

    /** The week-grain seasonal-naive model over {@code weekly}. */
    static Map<String, Object> weekly() {
        return base("weekly", "week", "seasonal-naive", 4, 104);
    }

    private static Map<String, Object> base(String dataset, String grain, String method, int horizon, int history) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entityType", "product");
        m.put("dataset", dataset);
        m.put("key", "sku");
        m.put("time", "sold_at");
        m.put("measure", "sum(qty)");
        m.put("grain", grain);
        m.put("method", method);
        m.put("horizon", horizon);
        m.put("history", history);
        return m;
    }
}
