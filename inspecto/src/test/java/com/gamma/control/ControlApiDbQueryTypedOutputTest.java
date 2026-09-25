package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.CollectorProcessor;
import com.gamma.service.SpaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Found driving the UI 2026-09-25: the {@code web_orders} Pipeline (types in a sibling Structure CSV, a
 * keep-only Record Transformer slot) ran SUCCESS, yet {@code POST /db/query} answered {@code typeof(...) =
 * VARCHAR} for its BIGINT/DATE/DOUBLE columns. The stored output is read back over real HTTP here, after a
 * real ingest, so a type lost on the read path fails as surely as one lost on the write path.
 */
class ControlApiDbQueryTypedOutputTest {

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void dbQueryReportsTheStoredTypesOfAStructureCsvPipeline(@TempDir Path root) throws Exception {
        Path config = Files.createDirectories(root.resolve("s1/config"));
        Files.createDirectories(root.resolve("s1/duckdb"));
        Path data = root.resolve("s1/data");
        Path toon = writeWebOrders(config, data);

        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        try {
            spaces.startAll();
            api.start();

            PipelineConfig cfg = PipelineConfig.load(toon.toString());
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

            HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + api.port() + "/api/v1/spaces/s1/db/query"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"table\":\"web_orders\",\"sql\":"
                            + "\"SELECT typeof(ORDER_ID) AS t_id, typeof(ORDER_DATE) AS t_date, "
                            + "typeof(AMOUNT) AS t_amount, typeof(CUSTOMER) AS t_customer, "
                            + "CAST(AMOUNT AS VARCHAR) AS amount FROM web_orders WHERE CUSTOMER = 'Acme'\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            JsonNode row = V1Body.of(r.body()).get("rows").get(0);
            assertEquals("BIGINT", row.get("t_id").asText(), r.body());
            assertEquals("DATE", row.get("t_date").asText(), r.body());
            assertEquals("DOUBLE", row.get("t_amount").asText(), r.body());
            assertEquals("VARCHAR", row.get("t_customer").asText(), "a declared VARCHAR stays VARCHAR");
            assertEquals("42.1", row.get("amount").asText());
        } finally {
            api.close();
            spaces.close();
        }
    }

    /** The UI-authored shape: a schema with no inline fields, its sibling Structure CSV, a keep-only slot. */
    private static Path writeWebOrders(Path config, Path data) throws Exception {
        StringBuilder keep = new StringBuilder();
        for (String f : new String[]{"ORDER_ID", "ORDER_DATE", "CUSTOMER", "REGION", "AMOUNT", "CURRENCY"})
            keep.append("      - name: ").append(f).append("\n        from: ").append(f).append("\n        fn: keep\n");
        Files.writeString(config.resolve("web_orders_schema.toon"), """
                raw:
                  name: web_orders
                  format: CSV
                  types: auto
                mapping:
                  fields[6]:
                """ + keep.toString().indent(-2), StandardCharsets.UTF_8);
        Files.writeString(config.resolve("web_orders_structure.csv"), """
                field,type,selector,unit,description,classification
                ORDER_ID,BIGINT,0,,,
                ORDER_DATE,DATE,1,,,
                CUSTOMER,VARCHAR,2,,,
                REGION,VARCHAR,3,,,
                AMOUNT,DOUBLE,4,,,
                CURRENCY,VARCHAR,5,,,
                """, StandardCharsets.UTF_8);
        String d = data.toString().replace('\\', '/');
        Path toon = config.resolve("web_orders_pipeline.toon");
        Files.writeString(toon, """
                name: web_orders
                active: false
                dirs:
                  poll:       %1$s/inbox/web_orders
                  database:   %1$s/web_orders/database
                  backup:     %1$s/web_orders/backup
                  temp:       %1$s/web_orders/temp
                  errors:     %1$s/web_orders/errors
                  quarantine: %1$s/web_orders/quarantine
                  status_dir: %1$s/web_orders/status
                processing:
                  file_pattern: "glob:**/*.csv"
                  schema_file: web_orders_schema.toon
                  csv_settings:
                    delimiter: ","
                    date_formats[1]: "%%Y-%%m-%%d"
                  map:
                    fields[6]:
                """.formatted(d) + keep, StandardCharsets.UTF_8);
        return toon;
    }
}
