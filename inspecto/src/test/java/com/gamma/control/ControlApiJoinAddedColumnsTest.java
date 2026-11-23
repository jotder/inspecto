package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LOOKUP-1 P1d — "Columns this Step adds" over real HTTP: {@code POST /components/transform/describe} with a
 * {@code config} body lists the columns a keyed {@code transform.join} adds, from the Reference's columns, without
 * reading a row. Each refusal is the run's own refusal, by name.
 */
class ControlApiJoinAddedColumnsTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    /** Boots with test_etl and region_dim, a {@code produces: reference} Pipeline (inactive). */
    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Path region = TestConfigs.csv(dir.resolve("region"), PipelineConfigBatchTest.miniSchema())
                .name("REGION_DIM").format("PARQUET").write();
        Files.writeString(region, "produces: reference\n" + Files.readString(region).replace("active: true", "active: false"));
        CollectorService svc = new CollectorService(List.of(toon, region), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private HttpResponse<String> describe(int port, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/components/transform/describe"))
                .header("Content-Type", "application/json")
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private static final String LEGACY = """
        {"config":{"type":"transform.join","reference":"reference/dial_digit","key":"dial_digit_key",
          "match":"longest_prefix","lookups":[
            {"on":"SERVED_MSISDN","columns":{"provider_desc":"SERVED_MSISDN_OPER","nop_id_key":"SERVED_MSISDN_NOP_ID_KEY"}},
            {"on":"OTHER_MSISDN","columns":{"event_category_key":"EVENT_CATEGORY_KEY"},"details":true}]},
         "inputColumns":["id","SERVED_MSISDN","OTHER_MSISDN"%s],
         "referenceColumns":[{"name":"dial_digit_key","type":"VARCHAR"},{"name":"provider_desc","type":"VARCHAR"},
           {"name":"nop_id_key","type":"INTEGER"},{"name":"event_category_key","type":"VARCHAR"}]}""";

    @Test
    void aKeyedJoinListsTheColumnsItAddsWithTypeOriginAndLookup(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> res = describe(c.port, LEGACY.formatted(""));
            assertEquals(200, res.statusCode(), res.body());
            JsonNode cols = V1Body.of(res.body()).get("columns");
            assertEquals(List.of("SERVED_MSISDN_OPER", "SERVED_MSISDN_NOP_ID_KEY", "EVENT_CATEGORY_KEY",
                    "OTHER_MSISDN_matched_key", "OTHER_MSISDN_match_kind"),
                    java.util.stream.StreamSupport.stream(cols.spliterator(), false).map(n -> n.get("name").asText()).toList());
            JsonNode nop = cols.get(1);
            assertEquals("INTEGER", nop.get("type").asText());
            assertEquals("nop_id_key", nop.get("from").asText());
            assertEquals("SERVED_MSISDN", nop.get("lookedUpBy").asText());
            assertEquals("longest_prefix", nop.get("match").asText());
            assertEquals("match_kind", cols.get(4).get("detail").asText());
        }
    }

    @Test
    void aClashAnUnknownColumnAndANonKeyedJoinAre422ByName(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> clash = describe(c.port, LEGACY.formatted(",\"EVENT_CATEGORY_KEY\""));
            assertEquals(422, clash.statusCode(), clash.body());
            assertTrue(clash.body().contains("EVENT_CATEGORY_KEY") && clash.body().contains("clashes"), clash.body());

            HttpResponse<String> unknown = describe(c.port, LEGACY.formatted("").replace("\"nop_id_key\":", "\"nop_key\":"));
            assertEquals(422, unknown.statusCode(), unknown.body());
            assertTrue(unknown.body().contains("no column 'nop_key'"), unknown.body());

            HttpResponse<String> plain = describe(c.port, "{\"config\":{\"type\":\"transform.join\","
                    + "\"reference\":\"reference/region_dim\",\"on\":\"id\"},\"referenceColumns\":[\"id\"]}");
            assertEquals(422, plain.statusCode(), plain.body());
            assertTrue(plain.body().contains("keyed form"), plain.body());

            HttpResponse<String> notJoin = describe(c.port, "{\"config\":{\"type\":\"transform.filter\"}}");
            assertEquals(422, notJoin.statusCode(), notJoin.body());
        }
    }

    /** Without referenceColumns, the columns come from the producing Pipeline's declared schema. */
    @Test
    void withoutReferenceColumnsTheLoadedReferencePipelinesSchemaIsUsed(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> res = describe(c.port, "{\"config\":{\"reference\":\"reference/region_dim\","
                    + "\"key\":\"id\",\"on\":\"cust\"},\"inputColumns\":[\"cust\"]}");
            assertEquals(200, res.statusCode(), res.body());
            JsonNode cols = V1Body.of(res.body()).get("columns");
            assertFalse(cols.isEmpty(), res.body());
            for (JsonNode col : cols) {
                assertTrue(col.get("name").asText().startsWith("cust_"), res.body());
                assertFalse(col.get("from").isNull(), res.body());
            }

            HttpResponse<String> missing = describe(c.port, "{\"config\":{\"reference\":\"reference/nowhere\","
                    + "\"key\":\"id\",\"on\":\"cust\"}}");
            assertEquals(422, missing.statusCode(), missing.body());
            assertTrue(missing.body().contains("referenceColumns"), missing.body());

            HttpResponse<String> notReference = describe(c.port, "{\"config\":{\"reference\":\"reference/test_etl\","
                    + "\"key\":\"id\",\"on\":\"cust\"}}");
            assertEquals(422, notReference.statusCode(), notReference.body());
        }
    }
}
