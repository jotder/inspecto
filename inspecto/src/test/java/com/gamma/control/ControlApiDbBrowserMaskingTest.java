package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.SpaceManager;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BI-QUERY-MASKING-1, the {@code /db} routes: a business-data STORE (not a Dataset) carries the classification of the
 * Datasets that read it, so {@code GET /db/table} and {@code POST /db/query} mask a classified key for a caller without
 * {@code canRevealLinkEntities}, refuse a sort / WHERE / JOIN on it, and leave a store with no classified column
 * byte-identical. Real HTTP + real DuckDB + an armed authenticator ({@code reader} holds nothing, {@code revealer} the
 * reveal capability), so a swapped capability is visible.
 */
class ControlApiDbBrowserMaskingTest {

    private static final String A = "441700900101", B = "441700900202";
    private final HttpClient client = HttpClient.newHttpClient();

    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer reader".equals(auth)) return Optional.of(new Subject("reader", Set.of()));
        if ("Bearer revealer".equals(auth)) return Optional.of(new Subject("revealer", Set.of("canRevealLinkEntities")));
        return Optional.empty();
    };

    /** These tests are about masking by default, which is Standard: this module's own classpath is Basic, where it is off. */
    @org.junit.jupiter.api.BeforeEach
    void standardEdition() {
        com.gamma.etl.EditionFeatures.overrideForTest(java.util.Set.of(com.gamma.etl.EditionFeatures.INVESTIGATIONS));
    }

    @AfterEach
    void restoreEdition() {
        com.gamma.etl.EditionFeatures.overrideForTest(null);
    }

    @AfterEach
    void clearAuthenticator() { Authenticators.forTest(null); }

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); }
    }

    /** Space s1 with two Parquet stores: {@code calls} (msisdn classified through a Dataset) and {@code plain} (nothing classified). */
    private Ctx open(Path root) throws Exception {
        Path base = root.resolve("s1");
        Path config = base.resolve("config");
        Files.createDirectories(config.resolve("inbox"));
        Files.createDirectories(base.resolve("duckdb"));
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
        for (String store : List.of("calls", "plain")) {
            Path partition = base.resolve("data").resolve(store).resolve("dt=2026");
            Files.createDirectories(partition);
            String parquet = partition.resolve("data.parquet").toString().replace("\\", "/");
            DuckDbUtil.loadDriver();
            File db = DuckDbUtil.tempDbFile("dbmask_seed_");
            try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
                st.execute("COPY (SELECT * FROM (VALUES ('" + A + "','EU',10.0),('" + A + "','EU',5.0),('" + B + "','US',7.0))"
                        + " t(msisdn,region,amount)) TO '" + parquet + "' (FORMAT PARQUET)");
            } finally {
                DuckDbUtil.deleteTempDb(db);
            }
        }
        ComponentStore reg = new ComponentStore(config.resolve("registry"));
        reg.write("dataset", "calls_ds", Map.of("physicalRef", "calls",
                "columns", List.of(Map.of("name", "msisdn", "classification", "MSISDN"), Map.of("name", "region"))));
        reg.write("dataset", "plain_ds", Map.of("physicalRef", "plain"));
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    private HttpResponse<String> as(String who, Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + who);
        b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static String query(String table, String sql) {
        return "{\"table\":\"" + table + "\",\"sql\":\"" + sql.replace("\"", "\\\"") + "\"}";
    }

    private static List<String> column(JsonNode rows, String name) {
        List<String> out = new ArrayList<>();
        rows.forEach(r -> out.add(r.get(name).asText()));
        return out;
    }

    private static void assertNoRaw(String body) {
        for (String raw : List.of(A, B)) assertFalse(body.contains(raw), "raw key " + raw + " must not appear: " + body);
    }

    @Test
    void theTableBrowserMasksAClassifiedKeyForAReaderOnly(@TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(root)) {
            HttpResponse<String> reader = as("reader", c, "GET", "/spaces/s1/db/table?name=calls", null);
            assertEquals(200, reader.statusCode(), reader.body());
            assertNoRaw(reader.body());
            JsonNode r = V1Body.of(reader.body());
            assertTrue(column(r.get("rows"), "msisdn").stream().allMatch(s -> s.startsWith("masked:")), r.get("rows").toString());
            assertEquals(Set.of("EU", "US"), Set.copyOf(column(r.get("rows"), "region")), "an unclassified column stays raw");
            assertTrue(r.get("masking").get("masked").asBoolean());

            JsonNode rev = V1Body.of(as("revealer", c, "GET", "/spaces/s1/db/table?name=calls", null).body());
            assertEquals(Set.of(A, B), Set.copyOf(column(rev.get("rows"), "msisdn")));

            JsonNode plain = V1Body.of(as("reader", c, "GET", "/spaces/s1/db/table?name=plain", null).body());
            assertFalse(plain.has("masking"), "a store with no classified column is untouched");
            assertEquals(Set.of(A, B), Set.copyOf(column(plain.get("rows"), "msisdn")));
        }
    }

    @Test
    void aSortOnAMaskedKeyIsRefusedForAReader(@TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(root)) {
            assertEquals(422, as("reader", c, "GET", "/spaces/s1/db/table?name=calls&sort=msisdn:desc", null).statusCode());
            assertEquals(200, as("revealer", c, "GET", "/spaces/s1/db/table?name=calls&sort=msisdn:desc", null).statusCode());
            assertEquals(200, as("reader", c, "GET", "/spaces/s1/db/table?name=calls&sort=region:desc", null).statusCode());
        }
    }

    @Test
    void adHocSqlMayOnlySelectAMaskedKey(@TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(root)) {
            HttpResponse<String> sel = as("reader", c, "POST", "/spaces/s1/db/query", query("calls", "SELECT msisdn, region FROM calls"));
            assertEquals(200, sel.statusCode(), sel.body());
            assertNoRaw(sel.body());
            assertTrue(column(V1Body.of(sel.body()).get("rows"), "msisdn").stream().allMatch(s -> s.startsWith("masked:")));

            // Derived and renamed: still masked, a count still a number.
            JsonNode d = V1Body.of(as("reader", c, "POST", "/spaces/s1/db/query", query("calls",
                    "SELECT CAST(msisdn AS BIGINT) AS m, msisdn AS renamed, count(*) AS n FROM calls GROUP BY ALL")).body());
            assertTrue(column(d.get("rows"), "m").stream().allMatch(s -> s.startsWith("masked:")), d.toString());
            assertTrue(column(d.get("rows"), "renamed").stream().allMatch(s -> s.startsWith("masked:")));
            assertTrue(d.get("rows").get(0).get("n").isNumber());

            // Every way to test or bisect the raw value is refused for a reader and allowed for a revealer.
            for (String probe : List.of(
                    "SELECT region FROM calls WHERE msisdn LIKE '4417009%'",
                    "SELECT region FROM calls WHERE msisdn = '" + A + "'",
                    "SELECT count(*) FROM calls WHERE CAST(msisdn AS BIGINT) > 441700900150",
                    "SELECT region, count(*) AS n FROM calls GROUP BY region HAVING max(msisdn) > '5'",
                    "SELECT msisdn, count(*) AS n FROM calls GROUP BY msisdn ORDER BY msisdn",
                    "SELECT count(CASE WHEN msisdn = '" + A + "' THEN 1 END) AS hit FROM calls",
                    "SELECT count(*) FILTER (WHERE msisdn = '" + A + "') AS hit FROM calls",
                    "SELECT region FROM (SELECT * FROM calls WHERE msisdn = '" + A + "') x",
                    "WITH w AS (SELECT msisdn, region FROM calls) SELECT region FROM w WHERE msisdn = '" + A + "'")) {
                HttpResponse<String> refused = as("reader", c, "POST", "/spaces/s1/db/query", query("calls", probe));
                if (refused.statusCode() == 200) {
                    // A probe the parse tree lets through must at least have its output masked (the CASE / FILTER counts).
                    assertNoRaw(refused.body());
                    assertTrue(column(V1Body.of(refused.body()).get("rows"), "hit").stream().allMatch(s -> s.startsWith("masked:")), probe);
                } else assertEquals(422, refused.statusCode(), probe + " -> " + refused.body());
                assertEquals(200, as("revealer", c, "POST", "/spaces/s1/db/query", query("calls", probe)).statusCode(), "a revealer: " + probe);
            }
            // A query that never names the key is unrestricted.
            assertEquals(200, as("reader", c, "POST", "/spaces/s1/db/query",
                    query("calls", "SELECT region, sum(amount) AS total FROM calls WHERE region = 'EU' GROUP BY region")).statusCode());
        }
    }

    /** BI-MASKING-SQL-SHAPE-LEAKS-1 (own server per test: the /db/query budget is 20 calls per caller). */
    @Test
    void aShapeThePositionModelCannotProveIsMaskedWhole(@TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(root)) {
            // A multi-column item shifts the positions, COLUMNS() names the key by pattern, a REPLACE star hides a CASE
            // inside a count, a lateral alias tests the key: refused, or every cell masked.
            for (String probe : List.of(
                    "SELECT count(COLUMNS('region|amount')), max(CAST(msisdn AS BIGINT)) AS m FROM calls",
                    "SELECT count(COLUMNS('region|amount')), max(CAST(msisdn AS BIGINT)) AS amount FROM calls",
                    "SELECT unnest({'a': region, 'b': amount}), CAST(msisdn AS BIGINT) AS m FROM calls",
                    "SELECT CAST(COLUMNS('msi.*') AS BIGINT) + 0 AS v FROM calls",
                    "SELECT count(COLUMNS(* REPLACE (CASE WHEN msisdn = '" + A + "' THEN 1 END AS msisdn))) FROM calls",
                    "SELECT msisdn AS k, k = '" + A + "' AS hit FROM calls",
                    // wrapped in a set operation or a subquery, the same shapes
                    "SELECT region FROM calls UNION ALL SELECT COLUMNS('msi.*') FROM calls",
                    "SELECT (SELECT max(COLUMNS('msi.*')) FROM calls) AS r",
                    "SELECT * FROM calls a, calls b",
                    "SELECT unlist({'a': region, 'b': amount}), CAST(msisdn AS BIGINT) AS m FROM calls")) {   // the second key comes back as msisdn_1
                HttpResponse<String> r = as("reader", c, "POST", "/spaces/s1/db/query", query("calls", probe));
                if (r.statusCode() == 200) {
                    V1Body.of(r.body()).get("rows").forEach(row -> row.forEach(cell ->
                            assertTrue(cell.isNull() || cell.asText().startsWith("masked:"), probe + " -> " + row)));
                } else assertEquals(422, r.statusCode(), probe + " -> " + r.body());
                assertEquals(200, as("revealer", c, "POST", "/spaces/s1/db/query", query("calls", probe)).statusCode(), "a revealer: " + probe);
            }
            // Positional ordering with no key selected, and a bare count of the key, stay as they were.
            assertEquals(200, as("reader", c, "POST", "/spaces/s1/db/query",
                    query("calls", "SELECT region, amount FROM calls ORDER BY 1")).statusCode());
            JsonNode n = V1Body.of(as("reader", c, "POST", "/spaces/s1/db/query",
                    query("calls", "SELECT count(*) AS n, count(msisdn) AS c FROM calls")).body());
            assertTrue(n.get("rows").get(0).get("n").isNumber() && n.get("rows").get(0).get("c").isNumber(), n.toString());
        }
    }

    /** BI-MASKING-SQL-SHAPE-LEAKS-1: oracles through a pattern, a position, ALL or an alias of the key are refused. */
    @Test
    void anOracleThroughAPatternAPositionOrAnAliasIsRefused(@TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(root)) {
            for (String probe : List.of(
                    "SELECT region FROM calls WHERE COLUMNS('msi.*') = '" + A + "'",
                    "SELECT msisdn AS k, region FROM calls ORDER BY 1",
                    "SELECT msisdn AS k FROM calls ORDER BY ALL",
                    "SELECT msisdn AS k, region FROM calls WHERE k = '" + A + "'",
                    "SELECT max(msisdn) AS k, region FROM calls GROUP BY region HAVING k > '5'",
                    "SELECT msisdn AS k, region FROM calls QUALIFY row_number() OVER (ORDER BY k) = 1",
                    "SELECT region, amount FROM calls ORDER BY COLUMNS(* REPLACE (msisdn AS region)) DESC",
                    "SELECT region FROM calls ORDER BY COLUMNS(* EXCLUDE (region, amount, dt))",
                    "SELECT * FROM calls ORDER BY 1",
                    "SELECT * FROM calls ORDER BY ALL",
                    "SELECT region FROM calls WHERE #1 = '" + A + "'",
                    "SELECT msisdn AS k, region FROM calls ORDER BY 1 COLLATE nocase DESC",
                    "SELECT count(*) AS n FROM (SELECT * FROM calls WHERE COLUMNS('msi.*') = '" + A + "')",
                    "SELECT count(*) AS n FROM (SELECT * FROM calls WHERE #1 LIKE '4417009001%')",
                    "SELECT region FROM calls WHERE '" + A + "' IN (SELECT COLUMNS('msi.*') FROM calls)",
                    "SELECT region, amount FROM (SELECT * FROM calls ORDER BY 1 DESC LIMIT 1)",
                    "SELECT * FROM calls UNION ALL SELECT * FROM calls ORDER BY 1",
                    "SELECT amount FROM calls ORDER BY (* LIKE 'msi%') DESC LIMIT 1",
                    "SELECT msisdn AS k, amount FROM calls ORDER BY * DESC LIMIT 1")) {
                assertEquals(422, as("reader", c, "POST", "/spaces/s1/db/query", query("calls", probe)).statusCode(), probe);
                assertEquals(200, as("revealer", c, "POST", "/spaces/s1/db/query", query("calls", probe)).statusCode(), "a revealer: " + probe);
            }
        }
    }
}
