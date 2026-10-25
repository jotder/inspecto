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

            // Renamed and grouped bare: still masked, a count still a number.
            JsonNode d = V1Body.of(as("reader", c, "POST", "/spaces/s1/db/query", query("calls",
                    "SELECT msisdn AS renamed, count(*) AS n, count(DISTINCT msisdn) AS d FROM calls GROUP BY msisdn")).body());
            assertTrue(column(d.get("rows"), "renamed").stream().allMatch(s -> s.startsWith("masked:")), d.toString());
            assertTrue(d.get("rows").get(0).get("n").isNumber());

            // Every way to test or bisect the raw value is refused for a reader and allowed for a revealer
            // (BI-MASKING-FREE-SQL-ORACLES-1: a key may be named only bare - a cast, CASE or function over it is refused too).
            for (String probe : List.of(
                    "SELECT CAST(msisdn AS BIGINT) AS m FROM calls",
                    "SELECT CASE WHEN msisdn = '" + A + "' THEN 'hit' ELSE 'miss' END AS z FROM calls",
                    "SELECT count(*) AS n FROM calls GROUP BY substr(msisdn, 1, 6)",
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
                assertEquals(422, refused.statusCode(), probe + " -> " + refused.body());
                assertEquals(200, as("revealer", c, "POST", "/spaces/s1/db/query", query("calls", probe)).statusCode(), "a revealer: " + probe);
            }
            // A run-time error is a yes / no answer (422 when the key exists, 200 when not): refused before anything runs.
            HttpResponse<String> oracle = as("reader", c, "POST", "/spaces/s1/db/query", query("calls",
                    "SELECT CASE WHEN msisdn = '" + B + "' THEN error('x') ELSE 'ok' END AS z FROM calls"));
            assertEquals(422, oracle.statusCode(), oracle.body());
            assertTrue(oracle.body().contains("may only be"), "refused by the masking vet, not by the error: " + oracle.body());
            // ...also when a star or unnest sits in the same item (a star deep inside an expression is not a bare use)
            for (String hidden : List.of(
                    "SELECT CASE WHEN COLUMNS('msisdn') = '" + B + "' THEN error('x') ELSE 1 END AS z FROM calls",
                    "SELECT CASE WHEN msisdn = '" + B + "' THEN error('x') ELSE 1 END + 0 * unnest([1]) AS z FROM calls",
                    "SELECT count(*) FILTER (WHERE COLUMNS('msisdn') LIKE '4417%') AS n FROM calls")) {
                HttpResponse<String> h = as("reader", c, "POST", "/spaces/s1/db/query", query("calls", hidden));
                assertEquals(422, h.statusCode(), hidden + " -> " + h.body());
                assertTrue(h.body().contains("may only be"), "refused by the vet before it runs: " + hidden + " -> " + h.body());
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
                // BI-MASKING-FREE-SQL-ORACLES-1: a shape that computes over the key is refused; only a bare star (a self-join,
                // a set operation, a subquery) may still come back, masked whole
                boolean bareShape = probe.startsWith("SELECT * FROM") || probe.contains("UNION ALL") || probe.startsWith("SELECT (SELECT");
                if (!bareShape) assertEquals(422, r.statusCode(), "computes over the key: " + probe + " -> " + r.body());
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
                    // a join on the key the ON check cannot see (BI-MASKING-FREE-SQL-ORACLES-1 verification): USING holds names
                    "SELECT count(*) AS n FROM calls a JOIN calls b USING (msisdn) WHERE a.region = 'EU' AND b.region = 'US'",
                    "SELECT count(*) AS n FROM calls a NATURAL JOIN calls b",
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

    /**
     * A sibling Dataset's classified column named with a leading blank (" msisdn") reaches the masking through the lineage, which
     * lower-cased the name but did not trim it: the masked set held " msisdn", every comparison used "msisdn", and a Widget bound
     * to a saved query grouped and filtered the raw key.
     */
    @Test
    void aSiblingKeyNameWithSurroundingBlanksIsMaskedAsTrimmed(@TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(root)) {
            Path partition = root.resolve("s1").resolve("data").resolve("spc").resolve("dt=2026");
            Files.createDirectories(partition);
            String parquet = partition.resolve("data.parquet").toString().replace("\\", "/");
            DuckDbUtil.loadDriver();
            File db = DuckDbUtil.tempDbFile("dbmask_blank_");
            try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
                st.execute("COPY (SELECT * FROM (VALUES ('" + A + "','EU'),('" + B + "','US')) t(\" msisdn\", region))"
                        + " TO '" + parquet + "' (FORMAT PARQUET)");
            } finally {
                DuckDbUtil.deleteTempDb(db);
            }
            ComponentStore reg = new ComponentStore(root.resolve("s1").resolve("config").resolve("registry"));
            reg.write("dataset", "spc_sib", Map.of("physicalRef", "spc",
                    "columns", List.of(Map.of("name", " msisdn", "classification", "MSISDN"))));
            reg.write("dataset", "spc_ds", Map.of("physicalRef", "spc"));
            reg.write("query", "spc_q", Map.of("type", "sql", "datasetId", "spc_ds",
                    "text", "SELECT \" msisdn\" AS k, region FROM spc_ds"));
            String count = "\"measures\":[{\"agg\":\"count\"}]";
            HttpResponse<String> group = as("reader", c, "POST", "/spaces/s1/bi/query",
                    "{\"dataset\":\"spc_ds\",\"query\":\"spc_q\"," + count + ",\"groupBy\":[\"k\"]}");
            if (group.statusCode() == 200) {
                JsonNode rows = V1Body.of(group.body()).get("rows");
                assertNoRaw(rows.toString());
                rows.forEach(row -> assertTrue(row.get("k").asText().startsWith("masked:"), row.toString()));
            } else assertEquals(422, group.statusCode(), group.body());
            assertEquals(422, as("reader", c, "POST", "/spaces/s1/bi/query", "{\"dataset\":\"spc_ds\",\"query\":\"spc_q\"," + count
                    + ",\"filters\":[{\"field\":\"k\",\"op\":\"like\",\"value\":\"4417009001%\"}]}").statusCode(),
                    "a filter on the key name must not bisect it");
        }
    }

    /**
     * BI-MASKING-SIBLING-TYPED-CLASS-1: {@code /db/table} masks a store column that a Dataset with a blank-padded {@code physicalRef}
     * classifies with a masked type outside SENSITIVE (IMEI). NEGATIVE PROBE: before the fix the padded ref missed the store's
     * own columns and the SENSITIVE-only lineage dropped IMEI, so the column came back raw.
     */
    @Test
    void aStoreColumnClassifiedImeiByAPaddedPhysicalRefIsMaskedOnDbTable(@TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(root)) {
            ComponentStore reg = new ComponentStore(root.resolve("s1").resolve("config").resolve("registry"));
            reg.write("dataset", "plain_pad", Map.of("physicalRef", " plain ",
                    "columns", List.of(Map.of("name", "msisdn", "classification", "IMEI"))));
            HttpResponse<String> t = as("reader", c, "GET", "/spaces/s1/db/table?name=plain", null);
            assertEquals(200, t.statusCode(), t.body());
            assertNoRaw(t.body());
            assertTrue(column(V1Body.of(t.body()).get("rows"), "msisdn").stream().allMatch(s -> s.startsWith("masked:")), t.body());
        }
    }

    /** BI-MASKING-ROW-VALUE-LEAKS-1: a rename in FROM, or the row itself as a value, returns the key: refused, or every cell masked. */
    @Test
    void aFromClauseRenameOrTheRowAsAValueIsMaskedWhole(@TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(root)) {
            for (String probe : List.of(
                    "SELECT m FROM calls t(m)",
                    "SELECT CAST(m AS BIGINT) AS v FROM calls t(m)",
                    "SELECT unnest AS amount FROM calls, unnest([CAST(msisdn AS BIGINT)])",
                    "SELECT c AS region FROM calls c",
                    "SELECT region, main.calls AS j FROM calls",
                    "SELECT to_json(c) AS j FROM calls c",
                    "SELECT CAST(c['msisdn'] AS BIGINT) AS v FROM calls c",
                    "SELECT value AS region FROM calls UNPIVOT (value FOR col IN (msisdn))")) {
                HttpResponse<String> r = as("reader", c, "POST", "/spaces/s1/db/query", query("calls", probe));
                if (r.statusCode() == 200) {
                    JsonNode rows = V1Body.of(r.body()).get("rows");
                    assertNoRaw(rows.toString());
                    rows.forEach(row -> row.forEach(cell ->
                            assertTrue(cell.isNull() || cell.asText().startsWith("masked:"), probe + " -> " + row)));
                } else assertEquals(422, r.statusCode(), probe + " -> " + r.body());
            }
        }
    }

    /** BI-MASKING-ROW-VALUE-LEAKS-1: an oracle through a FROM rename or the row as a value is refused. */
    @Test
    void anOracleThroughAFromRenameOrTheRowIsRefused(@TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(root)) {
            for (String probe : List.of(
                    "SELECT region FROM calls t(m) WHERE m = '" + A + "'",
                    "SELECT region FROM calls c WHERE c['msisdn'] = '" + A + "'",
                    "SELECT region FROM calls c WHERE struct_extract(c, 'msi' || 'sdn') = '" + A + "'",
                    "SELECT region FROM calls WHERE to_json(calls) LIKE '%4417009001%'",
                    "SELECT region FROM calls c ORDER BY c",
                    "SELECT region FROM calls, unnest([msisdn]) u(x) WHERE x = '" + A + "'",
                    "SELECT region FROM calls WHERE to_json(main.calls) LIKE '%4417009001%'",
                    "SELECT msisdn, row_number() OVER (ORDER BY msisdn DESC) AS r FROM calls",
                    "SELECT msisdn, count(*) OVER (PARTITION BY msisdn) AS n FROM calls")) {
                assertEquals(422, as("reader", c, "POST", "/spaces/s1/db/query", query("calls", probe)).statusCode(), probe);
                assertEquals(200, as("revealer", c, "POST", "/spaces/s1/db/query", query("calls", probe)).statusCode(), "a revealer: " + probe);
            }
        }
    }

    /**
     * BI-MASKING-ROW-VALUE-LEAKS-1: the Space alias of a value is the same in every query, so a DERIVED value aliased the same
     * way decodes the key one digit at a time ({@code substr(msisdn,1,1)} next to {@code '4'}), and a rank over it the order.
     * Only a key selected bare keeps its Space alias; a derived cell gets an alias nothing else shares.
     */
    @Test
    void aDerivedValueOfAKeyCannotBeMatchedAgainstAKnownValue(@TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(root)) {
            // BI-MASKING-FREE-SQL-ORACLES-1: a digit or a rank derived from the key is refused before it runs
            assertEquals(422, as("reader", c, "POST", "/spaces/s1/db/query", query("calls",
                    "SELECT msisdn, substr(msisdn, 1, 1) AS d, CAST(msisdn AS BIGINT) % 10 AS r FROM calls")).statusCode());
            JsonNode rows = V1Body.of(as("reader", c, "POST", "/spaces/s1/db/query", query("calls",
                    "SELECT msisdn, '4' AS k, '1' AS one FROM calls")).body()).get("rows");
            assertEquals(3, rows.size(), rows.toString());
            String spaceAlias = "masked:[0-9a-f]{16}";
            for (JsonNode row : rows) {
                assertTrue(row.get("msisdn").asText().matches(spaceAlias), "the bare key keeps its alias: " + row);
                // a value the caller typed must not carry a Space alias, or '4' would be compared with a known key
                for (String col : List.of("k", "one")) {
                    assertTrue(row.get(col).asText().startsWith("masked:"), row.toString());
                    assertFalse(row.get(col).asText().matches(spaceAlias), col + " must not be linkable: " + row);
                }
            }
            // A typed full-number guess reached through a lateral alias is not the bare column: no Space alias to compare.
            JsonNode guess = V1Body.of(as("reader", c, "POST", "/spaces/s1/db/query", query("calls",
                    "SELECT '" + A + "' AS msisdn, msisdn AS k")).body()).get("rows").get(0);
            assertFalse(guess.get("k").asText().matches(spaceAlias), "a typed guess must not get the key's alias: " + guess);
            assertFalse(guess.get("msisdn").asText().matches(spaceAlias), guess.toString());
            // ...nor through an output NAME built at run time, which no text rule sees.
            JsonNode built = V1Body.of(as("reader", c, "POST", "/spaces/s1/db/query", query("calls",
                    "SELECT unnest(json_transform('{\"ms' || 'isdn\":\"" + A + "\"}', '{\"ms' || 'isdn\":\"VARCHAR\"}'))"))
                    .body()).get("rows").get(0);
            assertFalse(built.get("msisdn").asText().matches(spaceAlias), "a built name must not get the key's alias: " + built);
            // ...nor by an item that expands to several columns, which shifts the bare key's position onto the guess.
            for (String shift : List.of(
                    "SELECT * REPLACE ('" + A + "' AS region), msisdn FROM calls",
                    "SELECT unnest({'a': 1, 'b': '" + A + "'}), msisdn FROM calls")) {
                JsonNode s = V1Body.of(as("reader", c, "POST", "/spaces/s1/db/query", query("calls", shift)).body()).get("rows");
                s.forEach(row -> row.forEach(cell ->
                        assertFalse(cell.asText().matches(spaceAlias), "a shifted position must not get the alias: " + shift + " -> " + row)));
            }
            // The bare key keeps the Space alias: one id, one alias (the A rows match each other and the table browser).
            JsonNode table = V1Body.of(as("reader", c, "GET", "/spaces/s1/db/table?name=calls", null).body()).get("rows");
            assertEquals(Set.copyOf(column(table, "msisdn")), Set.copyOf(column(rows, "msisdn")));
        }
    }
}
