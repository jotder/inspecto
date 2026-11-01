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
 * A synthetic RFM corpus (no customer data): Dataset {@code orders(customer_id, order_date, amount)}, model {@code rfm}
 * with a 90-day window and 2 bins, as of 2026-06-30 — so the window is (2026-04-01, 2026-06-30]. Hand-computed:
 *
 * <pre>
 * customer  recency  frequency  monetary   R F M   segment
 * champ     1        4          400        2 2 2   Champions
 * loyal     5        3          150        2 2 2   Champions
 * lapsed    81       2          600        1 2 2   At risk
 * newbie    2        1          10         2 1 1   New
 * future    71       1          20         1 1 1   Other   (its 2026-07-01 order is after as_of: not counted)
 * nullamt   15       1          0          1 1 1   Other   (its one amount is NULL: monetary 0)
 * outside   -        -          -                  -       (its only order is before the window: no row)
 * </pre>
 *
 * Edges (quantile_cont, bins 2): recency on minus days [-81, -10, -1], frequency [1, 1.5, 4], monetary [0, 85, 600].
 */
final class RfmCorpus {
    private RfmCorpus() {}

    static final String MODEL = "rfm";
    static final LocalDate AS_OF = LocalDate.parse("2026-06-30");
    static final Map<String, String> EXPECTED = Map.of("champ", "Champions", "loyal", "Champions", "lapsed", "At risk",
            "newbie", "New", "future", "Other", "nullamt", "Other");

    static final String ROWS = "SELECT * FROM (VALUES "
            + "('champ', TIMESTAMP '2026-06-29 10:00:00', 100.0), ('champ', TIMESTAMP '2026-06-20 10:00:00', 100.0), "
            + "('champ', TIMESTAMP '2026-06-10 10:00:00', 100.0), ('champ', TIMESTAMP '2026-05-15 10:00:00', 100.0), "
            + "('loyal', TIMESTAMP '2026-06-25 09:00:00', 50.0), ('loyal', TIMESTAMP '2026-06-01 09:00:00', 50.0), "
            + "('loyal', TIMESTAMP '2026-05-01 09:00:00', 50.0), "
            + "('lapsed', TIMESTAMP '2026-04-10 12:00:00', 300.0), ('lapsed', TIMESTAMP '2026-04-05 12:00:00', 300.0), "
            + "('newbie', TIMESTAMP '2026-06-28 23:59:00', 10.0), "
            + "('future', TIMESTAMP '2026-04-20 08:00:00', 20.0), ('future', TIMESTAMP '2026-07-01 08:00:00', 999.0), "
            + "('nullamt', TIMESTAMP '2026-06-15 08:00:00', CAST(NULL AS DOUBLE)), "
            + "('outside', TIMESTAMP '2026-03-01 08:00:00', 5000.0)"
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
        store.write(SegmentationModel.KIND, MODEL, model);
        return model;
    }

    static Map<String, Object> model() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entityType", "customer");
        m.put("dataset", "orders");
        m.put("key", "customer_id");
        m.put("time", "order_date");
        m.put("window", 90);
        m.put("monetary", "sum(amount)");
        m.put("bins", 2);
        m.put("segments", List.of(
                Map.of("label", "Champions", "recency", "2", "frequency", "2", "monetary", "2"),
                Map.of("label", "At risk", "recency", "1", "frequency", "2", "monetary", "*"),
                Map.of("label", "New", "recency", "2", "frequency", "1", "monetary", "*")));
        return m;
    }
}
