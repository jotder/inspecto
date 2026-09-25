package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Found driving the UI 2026-09-25: a delimited Pipeline whose types live in a sibling Structure CSV
 * ({@code web_orders_structure.csv} overriding {@code raw.fields}), with a Record Transformer slot
 * ({@code processing.map.fields}, every row {@code keep}), ran SUCCESS but stored every declared type as
 * VARCHAR. {@code keep} must pass the parsed (declared) type through to the stored output.
 */
class TypedOutputThroughRecordTransformerTest {

    @Test
    void keepFieldsInTheRecordTransformerSlotStoreTheStructureCsvTypes(@TempDir Path dir) throws Exception {
        assertStoredTypes(webOrders(dir));
    }

    private static void assertStoredTypes(PipelineConfig cfg) throws Exception {
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Files.writeString(inbox.resolve("web_orders_20260925.csv"), """
                ORDER_ID,ORDER_DATE,CUSTOMER,REGION,AMOUNT,CURRENCY
                1001,2026-09-01,Acme,EMEA,42.10,EUR
                1002,2026-09-02,Globex,APAC,7.00,USD
                1003,2026-09-03,Initech,NA,100.5,USD
                1004,2026-09-04,Umbrella,EMEA,0.99,EUR
                1005,2026-09-05,Hooli,NA,12.34,USD
                """, StandardCharsets.UTF_8);

        CollectorProcessor.run(cfg);

        Path db = Path.of(cfg.dirs().database());
        try (var files = Files.walk(db)) {
            assertEquals(java.util.List.of(), files.map(f -> f.getFileName().toString())
                            .filter(n -> n.endsWith(".csv")).toList(),
                    "a Pipeline that declares no output format stores Parquet — a CSV store is text, and "
                            + "reads back VARCHAR whatever the Structure CSV declared");
        }
        assertEquals("5", scalar(db, "COUNT(*)::VARCHAR"), "all five rows are stored");
        assertEquals("BIGINT|DATE|VARCHAR|VARCHAR|DOUBLE|VARCHAR", scalar(db,
                        "typeof(ORDER_ID) || '|' || typeof(ORDER_DATE) || '|' || typeof(CUSTOMER) || '|' || "
                                + "typeof(REGION) || '|' || typeof(AMOUNT) || '|' || typeof(CURRENCY)"),
                "`keep` passes each Structure-CSV type through to the STORED column — the VARCHAR fields stay VARCHAR");
        assertEquals("42.1", scalar(db, "CAST(MAX(AMOUNT) FILTER (WHERE ORDER_ID = '1001') AS VARCHAR)"));
    }

    /** The UI-authored shape: schema file with no inline fields, a sibling structure CSV, a keep-only slot. */
    static PipelineConfig webOrders(Path dir) throws Exception {
        Path conf = Files.createDirectories(dir.resolve("config/web_orders"));
        Files.writeString(conf.resolve("web_orders_schema.toon"), """
                raw:
                  name: web_orders
                  format: CSV
                  types: auto
                mapping:
                  fields[6]:
                    - name: ORDER_ID
                      from: ORDER_ID
                      fn: keep
                    - name: ORDER_DATE
                      from: ORDER_DATE
                      fn: keep
                    - name: CUSTOMER
                      from: CUSTOMER
                      fn: keep
                    - name: REGION
                      from: REGION
                      fn: keep
                    - name: AMOUNT
                      from: AMOUNT
                      fn: keep
                    - name: CURRENCY
                      from: CURRENCY
                      fn: keep
                """, StandardCharsets.UTF_8);
        Files.writeString(conf.resolve("web_orders_structure.csv"), """
                field,type,selector,unit,description,classification
                ORDER_ID,BIGINT,0,,,
                ORDER_DATE,DATE,1,,,
                CUSTOMER,VARCHAR,2,,,
                REGION,VARCHAR,3,,,
                AMOUNT,DOUBLE,4,,,
                CURRENCY,VARCHAR,5,,,
                """, StandardCharsets.UTF_8);
        String d = dir.toString().replace('\\', '/');
        Path toon = conf.resolve("web_orders_pipeline.toon");
        Files.writeString(toon, """
                name: web_orders
                active: false
                dirs:
                  poll:       %1$s/data/inbox/web_orders
                  database:   %1$s/data/web_orders/database
                  backup:     %1$s/data/web_orders/backup
                  temp:       %1$s/data/web_orders/temp
                  errors:     %1$s/data/web_orders/errors
                  quarantine: %1$s/data/web_orders/quarantine
                  status_dir: %1$s/data/web_orders/status
                processing:
                  file_pattern: "glob:**/*.csv"
                  schema_file: web_orders_schema.toon
                  csv_settings:
                    delimiter: ","
                    date_formats[1]: "%%Y-%%m-%%d"
                  map:
                    fields[6]:
                      - name: ORDER_ID
                        from: ORDER_ID
                        fn: keep
                      - name: ORDER_DATE
                        from: ORDER_DATE
                        fn: keep
                      - name: CUSTOMER
                        from: CUSTOMER
                        fn: keep
                      - name: REGION
                        from: REGION
                        fn: keep
                      - name: AMOUNT
                        from: AMOUNT
                        fn: keep
                      - name: CURRENCY
                        from: CURRENCY
                        fn: keep
                """.formatted(d), StandardCharsets.UTF_8);
        return PipelineConfig.load(toon.toString());
    }

    static String scalar(Path root, String expr) throws Exception {
        assertTrue(Files.isDirectory(root), "no output written under " + root);
        String glob = root.toString().replace('\\', '/') + "/**/*.parquet";
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + expr + " FROM read_parquet('" + glob + "')")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
