package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S2 over real HTTP: refreshing an offered Dataset publishes a snapshot (freshness surfaces in the
 * catalog), and the Exchange deletion fence blocks deleting an item still shared with a consumer.
 */
/*
 * ⚠ Package com.gamma.control, in the inspecto-exchange module — a TEST-SCOPE split package, the technique
 * inspecto-policy and inspecto-geo-link already use: this test constructs `new ControlApi(svc, 0)`
 * (package-private) so it drives the real dispatcher, and with the module on this classpath ExchangeRoutes is
 * discovered via META-INF/services exactly as in a Standard bundle. Moved verbatim from inspecto on
 * 2026-09-07 (EDG-01 cell 4); the only edit is V1Body -> the local json() helper below.
 */
class ControlApiExchangeSnapshotTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    @Test
    void refreshPublishesSnapshotAndFenceBlocksDelete(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"finance\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"audit\"}").statusCode());

            // seed a real Parquet Table in finance's data root + a dataset over it
            Path table = root.resolve("finance").resolve("data").resolve("tax_receipts");
            Files.createDirectories(table);
            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:"); Statement s = conn.createStatement()) {
                s.execute("COPY (SELECT * FROM (VALUES (1,'a'),(2,'b'),(3,'c')) t(amount,label)) TO "
                        + "'" + table.resolve("part.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
            }
            assertEquals(200, send(c.port, "POST", "/spaces/finance/components/dataset",
                    "{\"id\":\"tax_receipts\",\"physicalRef\":\"tax_receipts\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/exchange/offers",
                    "{\"kind\":\"dataset\",\"item\":\"tax_receipts\",\"owner\":\"finance\"}").statusCode());

            // ── refresh publishes a snapshot ──
            HttpResponse<String> refreshed = send(c.port, "POST", "/exchange/refresh",
                    "{\"owner\":\"finance\",\"item\":\"tax_receipts\"}");
            assertEquals(200, refreshed.statusCode(), refreshed.body());
            assertEquals(3, json(refreshed).get("rows").asInt());
            assertTrue(Files.exists(root.resolve("_shared").resolve("exchange")
                    .resolve("finance").resolve("tax_receipts").resolve("current.toon")));

            // freshness now surfaces in the catalog
            JsonNode offer = json(send(c.port, "GET", "/exchange/offers?owner=finance", null)).get(0);
            assertEquals(3, offer.get("freshness").get("rows").asInt());

            // ── grant to audit, then the deletion fence blocks deleting the offered dataset ──
            String id = json(send(c.port, "POST", "/exchange/requests",
                    "{\"kind\":\"dataset\",\"item\":\"tax_receipts\",\"owner\":\"finance\",\"consumer\":\"audit\"}"))
                    .get("id").asText();
            assertEquals(200, send(c.port, "POST", "/exchange/grants/" + id + "/approve", "").statusCode());
            assertEquals(409, send(c.port, "DELETE", "/spaces/finance/components/dataset/tax_receipts", null).statusCode(),
                    "cannot delete a dataset still shared with a consumer");

            // revoke lifts the fence
            assertEquals(200, send(c.port, "POST", "/exchange/grants/" + id + "/revoke", "").statusCode());
            assertEquals(200, send(c.port, "DELETE", "/spaces/finance/components/dataset/tax_receipts", null).statusCode());
        }
    }

    private void seedTable(Path root, String name) throws Exception {
        Path table = root.resolve("finance").resolve("data").resolve(name);
        Files.createDirectories(table);
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:"); Statement s = conn.createStatement()) {
            s.execute("COPY (SELECT * FROM (VALUES (1,'a'),(2,'b'),(3,'c')) t(amount,label)) TO "
                    + "'" + table.resolve("part.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
    }

    private String share(Ctx c, Path root, String item, String mode, boolean seed) throws Exception {
        assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"finance\"}").statusCode());
        assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"audit\"}").statusCode());
        if (seed) seedTable(root, item);
        assertEquals(200, send(c.port, "POST", "/spaces/finance/components/dataset",
                "{\"id\":\"" + item + "\",\"physicalRef\":\"" + item + "\"}").statusCode());
        assertEquals(200, send(c.port, "POST", "/exchange/offers",
                "{\"kind\":\"dataset\",\"item\":\"" + item + "\",\"owner\":\"finance\"}").statusCode());
        return json(send(c.port, "POST", "/exchange/requests",
                "{\"kind\":\"dataset\",\"item\":\"" + item + "\",\"owner\":\"finance\",\"consumer\":\"audit\""
                        + (mode == null ? "" : ",\"mode\":\"" + mode + "\"") + "}")).get("id").asText();
    }

    @Test
    void approvingASnapshotSharepublishesWithoutRefreshAndDoubleApproveIsRefused(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            String id = share(c, root, "tax_receipts", "snapshot", true);
            assertTrue(json(send(c.port, "GET", "/exchange/datasets/finance/tax_receipts", null)).path("freshness").isMissingNode());

            JsonNode approved = json(send(c.port, "POST", "/exchange/grants/" + id + "/approve", ""));
            assertEquals("active", approved.get("status").asText());
            assertEquals("published", approved.get("snapshots").get("tax_receipts").get("status").asText());

            JsonNode meta = json(send(c.port, "GET", "/exchange/datasets/finance/tax_receipts?consumer=audit", null));
            assertEquals(3, meta.get("freshness").get("rows").asInt());
            Path snap = root.resolve("_shared").resolve("exchange").resolve("finance").resolve("tax_receipts")
                    .resolve(meta.get("freshness").get("version").asText()).resolve("snapshot.parquet");
            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:"); Statement s = conn.createStatement();
                 java.sql.ResultSet rs = s.executeQuery("SELECT count(*) FROM read_parquet('"
                         + snap.toString().replace('\\', '/') + "')")) {
                rs.next();
                assertEquals(3, rs.getLong(1));
            }

            // double approve: refused, nothing republished
            assertEquals(409, send(c.port, "POST", "/exchange/grants/" + id + "/approve", "").statusCode());
            assertEquals(meta.get("freshness").get("version").asText(),
                    json(send(c.port, "GET", "/exchange/datasets/finance/tax_receipts", null)).get("freshness").get("version").asText());
        }
    }

    @Test
    void approvingALiveShareOrDenyingPublishesNothing(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            String id = share(c, root, "tax_receipts", "live", true);
            JsonNode approved = json(send(c.port, "POST", "/exchange/grants/" + id + "/approve", ""));
            assertEquals("active", approved.get("status").asText());
            assertFalse(approved.has("snapshots"));
            assertFalse(Files.exists(root.resolve("_shared").resolve("exchange").resolve("finance")
                    .resolve("tax_receipts").resolve("current.toon")));
        }
    }

    @Test
    void failedPublishKeepsTheApprovalAndReportsItSoRefreshCanRetry(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            // dataset points at a table that does not exist -> publish fails
            String id = share(c, root, "ghost", "snapshot", false);
            HttpResponse<String> r = send(c.port, "POST", "/exchange/grants/" + id + "/approve", "");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode d = json(r);
            assertEquals("active", d.get("status").asText());
            assertEquals("failed", d.get("snapshots").get("ghost").get("status").asText());
            assertFalse(d.get("snapshots").get("ghost").get("error").asText().isBlank());

            // the owner fixes the data and retries through the existing Refresh route
            seedTable(root, "ghost");
            assertEquals(200, send(c.port, "POST", "/exchange/refresh",
                    "{\"owner\":\"finance\",\"item\":\"ghost\"}").statusCode());
        }
    }

    @Test
    void refreshCadenceIsSetValidatedPreservedAndTheLastOutcomeIsListed(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            String id = share(c, root, "ghost", "snapshot", false);
            JsonNode listed = json(send(c.port, "GET", "/exchange/offers?owner=finance", null)).get(0);
            assertEquals("none", listed.get("refresh").asText(), "no cadence unless the sharer sets one");

            HttpResponse<String> set = send(c.port, "POST", "/exchange/offers",
                    "{\"kind\":\"dataset\",\"item\":\"ghost\",\"owner\":\"finance\",\"refresh\":\"daily\"}");
            assertEquals(200, set.statusCode(), set.body());
            assertEquals("daily", json(set).get("refresh").asText());
            // re-offering without the field (e.g. to edit the description) keeps the schedule
            assertEquals("daily", json(send(c.port, "POST", "/exchange/offers",
                    "{\"kind\":\"dataset\",\"item\":\"ghost\",\"owner\":\"finance\",\"description\":\"d\"}")).get("refresh").asText());

            HttpResponse<String> bad = send(c.port, "POST", "/exchange/offers",
                    "{\"kind\":\"dataset\",\"item\":\"ghost\",\"owner\":\"finance\",\"refresh\":\"weekly\"}");
            assertEquals(422, bad.statusCode(), bad.body());
            assertEquals("daily", json(send(c.port, "GET", "/exchange/offers?owner=finance", null)).get(0)
                    .get("refresh").asText(), "a refused cadence leaves the stored one untouched");

            // a failed publish (approve-time here) is listed as the last outcome; a successful refresh replaces it
            assertEquals(200, send(c.port, "POST", "/exchange/grants/" + id + "/approve", "").statusCode());
            JsonNode last = json(send(c.port, "GET", "/exchange/offers?owner=finance", null)).get(0).get("lastRefresh");
            assertEquals("failed", last.get("status").asText());
            assertEquals("approve", last.get("trigger").asText());
            assertFalse(last.get("error").asText().isBlank());
            seedTable(root, "ghost");
            assertEquals(200, send(c.port, "POST", "/exchange/refresh",
                    "{\"owner\":\"finance\",\"item\":\"ghost\"}").statusCode());
            last = json(send(c.port, "GET", "/exchange/datasets/finance/ghost", null)).get("lastRefresh");
            assertEquals("published", last.get("status").asText());
            assertEquals("manual", last.get("trigger").asText());
        }
    }

    @Test
    void aCadenceOnANonDatasetOfferIsRefused(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            share(c, root, "tax_receipts", "snapshot", true);
            assertEquals(200, send(c.port, "POST", "/spaces/finance/components/widget",
                    "{\"id\":\"w1\",\"dataset\":\"tax_receipts\"}").statusCode());
            HttpResponse<String> r = send(c.port, "POST", "/exchange/offers",
                    "{\"kind\":\"widget\",\"item\":\"w1\",\"owner\":\"finance\",\"refresh\":\"hourly\"}");
            assertEquals(422, r.statusCode(), r.body());
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return json(r.body()); }

    /** Parse a v1 response and peel the envelope's {@code data} (mirrors the control module's V1Body, which
     *  lives in inspecto's TEST tree and is not visible from another module — the inspecto-policy precedent). */
    private static com.fasterxml.jackson.databind.JsonNode json(String raw) throws Exception {
        com.fasterxml.jackson.databind.JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
        return n.has("data") ? n.get("data") : n;
    }
}
