package com.gamma.control;

import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.Idempotency;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.pipeline.ComponentStore;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.SpaceManager;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.gamma.access.Roles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SEC-IDEMPOTENCY-REPLAY-1 over real HTTP with armed Subjects: an {@code Idempotency-Key} replay is scoped to
 * the caller, runs after authentication / rate limiting / authorization, binds the request body, and never
 * caches a refusal. Before the fix every case below leaked or suppressed across callers.
 */
class ControlApiIdempotencyScopeTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private static final Authenticator FAKE_AUTH = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return Optional.empty();
        String iss = ex.getRequestHeaders().getFirst("X-Test-Issuer");   // stands in for the token's iss claim
        if (iss != null) Subject.stampIssuer(ex, iss);
        String token = auth.substring(7);   // "<id>:ro" is the same person holding no capabilities
        if (token.endsWith(":ro")) return Optional.of(new Subject(token.substring(0, token.length() - 3), Set.of()));
        return Optional.of(new Subject(token, Set.of(Roles.CAN_AUTHOR_WORKBENCH)));
    };

    private static final String ALICE_ROWS = "{\"table\":\"orders\",\"sql\":\"SELECT name FROM \\\"orders\\\" WHERE id = 1\"}";
    private static final String WIDGET = "{\"id\":\"w1\",\"vizType\":\"bar\"}";

    @AfterEach
    void tearDown() { Authenticators.forTest(null); }

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); }
    }

    private Ctx open(Path root) throws Exception {
        Authenticators.forTest(FAKE_AUTH);
        Path config = root.resolve("s1").resolve("config");
        Files.createDirectories(config.resolve("inbox"));
        Files.createDirectories(root.resolve("s1").resolve("duckdb"));
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
        Path partition = root.resolve("s1").resolve("data").resolve("orders").resolve("dt=2026");
        Files.createDirectories(partition);
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("idem_seed_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES (1,'alice'),(2,'bob')) t(id,name)) TO '"
                    + partition.resolve("data.parquet").toString().replace("\\", "/") + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        new ViewStore(config.resolve("views")).write(new ViewDefinition("sales_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('EU',10.0),('US',5.0)) AS t(region,amount)", "2026-09-29T00:00:00Z"));
        new ComponentStore(config.resolve("registry")).write("dataset", "sales_ds", Map.of("view", "sales_view"));
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    private HttpResponse<String> post(int port, String who, String path, String body, String key) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/spaces/s1" + path))
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString(body));
        if (who != null) b.header("Authorization", "Bearer " + who);
        if (key != null) b.header("Idempotency-Key", key);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> send(int port, String who, String path, String body, String key, String issuer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/spaces/s1" + path))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + who)
                .header("Idempotency-Key", key).header("X-Test-Issuer", issuer).POST(BodyPublishers.ofString(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static boolean replayed(HttpResponse<String> r) {
        return "true".equals(r.headers().firstValue("Idempotency-Replayed").orElse(null));
    }

    @Test
    void anotherCallerWithTheSameKeyGetsAFreshExecutionNeverTheOwnersBody(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> a = post(c.port, "alice", "/db/query", ALICE_ROWS, "K");
            assertEquals(200, a.statusCode(), a.body());
            assertTrue(a.body().contains("alice"));
            assertTrue(replayed(post(c.port, "alice", "/db/query", ALICE_ROWS, "K")), "the owner replays");

            String bobQuery = "{\"table\":\"orders\",\"sql\":\"SELECT name FROM \\\"orders\\\" WHERE id = 2\"}";
            HttpResponse<String> b = post(c.port, "bob", "/db/query", bobQuery, "K");
            assertFalse(replayed(b), "bob is not served alice's cache");
            assertEquals(200, b.statusCode(), b.body());
            assertFalse(b.body().contains("alice"), b.body());

            // A write: bob's same-key, same-body create really runs — the resource exists, so he gets his own 409.
            assertEquals(200, post(c.port, "alice", "/components/widget", WIDGET, "W").statusCode());
            HttpResponse<String> bw = post(c.port, "bob", "/components/widget", WIDGET, "W");
            assertFalse(replayed(bw));
            assertEquals(409, bw.statusCode(), bw.body());
        }
    }

    @Test
    void twoIssuersMintingTheSameSubDoNotShareEntries(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "alice", "/components/widget", WIDGET, "W", "https://idp-a").statusCode());
            assertTrue(replayed(send(c.port, "alice", "/components/widget", WIDGET, "W", "https://idp-a")), "same issuer replays");
            HttpResponse<String> other = send(c.port, "alice", "/components/widget", WIDGET, "W", "https://idp-b");
            assertFalse(replayed(other), "another IdP's 'alice' is another principal");
            assertEquals(409, other.statusCode(), other.body());
        }
    }

    @Test
    void twoSpellingsOfOneRouteShareAnEntry(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, post(c.port, "alice", "/components/widget", WIDGET, "W").statusCode());
            HttpResponse<String> encoded = post(c.port, "alice", "/components/%77idget", WIDGET, "W");
            assertTrue(replayed(encoded), "a percent-encoded spelling of the same route replays: " + encoded.body());
            assertEquals(200, encoded.statusCode());
            HttpResponse<String> query = post(c.port, "alice", "/components/widget?x=1", WIDGET, "W");
            assertEquals(422, query.statusCode(), "a different query string is a different request");
        }
    }

    @Test
    void canonicalPathCollapsesSlashesAndKeepsCase() {
        assertEquals("/components/widget", Idempotency.canonical("//components///widget/"));
        assertEquals("/", Idempotency.canonical("/"));
        assertNotEquals(Idempotency.canonical("/Components/widget"), Idempotency.canonical("/components/widget"));
    }

    @Test
    void anAnonymousCallerCannotReadACachedResponse(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, post(c.port, "alice", "/db/query", ALICE_ROWS, "K").statusCode());
            HttpResponse<String> anon = post(c.port, null, "/db/query", ALICE_ROWS, "K");
            assertEquals(401, anon.statusCode(), anon.body());
            assertFalse(replayed(anon));
            assertFalse(anon.body().contains("alice"), anon.body());
        }
    }

    @Test
    void anAnonymous401PreSeedDoesNotSuppressTheOwnersWrite(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(401, post(c.port, null, "/components/widget", WIDGET, "W").statusCode());
            HttpResponse<String> owner = post(c.port, "alice", "/components/widget", WIDGET, "W");
            assertFalse(replayed(owner), "the anonymous 401 was not cached against the key");
            assertEquals(200, owner.statusCode(), owner.body());
            assertEquals(409, post(c.port, "alice", "/components/widget", WIDGET, null).statusCode(), "the write landed");
        }
    }

    /** The same principal refused (403) and then granted: the refusal was not cached against the key. */
    @Test
    void aRefusalIsNeverReplayedToTheSameCallerOnceAllowed(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(403, post(c.port, "alice:ro", "/components/widget", WIDGET, "W").statusCode());
            HttpResponse<String> granted = post(c.port, "alice", "/components/widget", WIDGET, "W");
            assertFalse(replayed(granted), "the 403 was not cached");
            assertEquals(200, granted.statusCode(), granted.body());
        }
    }

    @Test
    void sameCallerSameBodyReplaysDifferentBodyIs422(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> first = post(c.port, "alice", "/components/widget", WIDGET, "W");
            HttpResponse<String> again = post(c.port, "alice", "/components/widget", WIDGET, "W");
            assertEquals(200, again.statusCode());
            assertTrue(replayed(again));
            assertEquals(first.body(), again.body());
            HttpResponse<String> other = post(c.port, "alice", "/components/widget", "{\"id\":\"w2\",\"vizType\":\"bar\"}", "W");
            assertEquals(422, other.statusCode(), other.body());
            assertFalse(replayed(other));
            assertTrue(other.body().contains("Idempotency-Key reused with a different request"), other.body());
        }
    }

    @Test
    void replaysAreRateLimited(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, post(c.port, "alice", "/db/query", ALICE_ROWS, "K").statusCode());
            int limited = 0;
            for (int i = 0; i < 30; i++) {
                HttpResponse<String> r = post(c.port, "alice", "/db/query", ALICE_ROWS, "K");
                if (r.statusCode() == 429) limited++;
                else assertTrue(replayed(r));
            }
            assertTrue(limited > 0, "a replay spends a token like the live request");
        }
    }

    @Test
    void biQueryResultsAreNeverServedCrossCaller(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            String q = "{\"dataset\":\"sales_ds\",\"measures\":[{\"agg\":\"sum\",\"field\":\"amount\"}],\"groupBy\":[\"region\"]}";
            HttpResponse<String> a = post(c.port, "alice", "/bi/query", q, "B");
            assertEquals(200, a.statusCode(), a.body());
            assertTrue(replayed(post(c.port, "alice", "/bi/query", q, "B")));
            assertFalse(replayed(post(c.port, "bob", "/bi/query", q, "B")), "bob executes his own query");
            HttpResponse<String> anon = post(c.port, null, "/bi/query", q, "B");
            assertEquals(401, anon.statusCode());
            assertFalse(replayed(anon));
        }
    }

    @Test
    void anOversizedResponseIsAnsweredButNotCached(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            String big = "{\"table\":\"orders\",\"sql\":\"SELECT repeat('x', 300000) AS big FROM \\\"orders\\\" LIMIT 1\"}";
            HttpResponse<String> first = post(c.port, "alice", "/db/query", big, "BIG");
            assertEquals(200, first.statusCode(), first.body());
            assertEquals("false", first.headers().firstValue(Idempotency.HEADER_CACHED).orElse(null));
            assertFalse(replayed(post(c.port, "alice", "/db/query", big, "BIG")));
        }
    }

    @Test
    void onlyDeterministicOutcomesAreCacheable() {
        for (int s : new int[] {200, 201, 202, 204, 400, 409, 422}) assertTrue(Idempotency.cacheable(s), "" + s);
        for (int s : new int[] {401, 403, 404, 429, 500, 503}) assertFalse(Idempotency.cacheable(s), "" + s);
    }

    @Test
    void oneCallerHoldsAtMostItsCapAndCannotFlushAnother() {
        Idempotency.Store store = new Idempotency.Store();
        store.put("bob-1", "sub:bob", "h", 200, new byte[0]);
        for (int i = 0; i < Idempotency.PER_CALLER_CAP + 20; i++) store.put("a-" + i, "sub:alice", "h", 200, new byte[0]);
        assertEquals(Idempotency.PER_CALLER_CAP, store.sizeFor("sub:alice"));
        assertNull(store.get("sub:alice", "a-0"), "alice's oldest was evicted");
        assertNotNull(store.get("sub:bob", "bob-1"), "bob's entry survives alice's flood");
    }

    @Test
    void oneCallersFloodAcrossTheOldGlobalCapLeavesAnothersReplay() {
        Idempotency.Store store = new Idempotency.Store();
        store.put("bob-1", "sub:bob", "h", 200, new byte[0]);
        // Callers (or one caller rotating client IPs) flood well past the old global 1000-entry LRU, which evicted
        // bob's entry as the oldest insert; bob stays active, so only idle partitions are evicted.
        for (int i = 0; i < Idempotency.MAX_PRINCIPALS + 10; i++) {
            for (int k = 0; k < Idempotency.PER_CALLER_CAP + 5; k++) store.put("k" + k, "sub:p" + i, "h", 200, new byte[0]);
            assertNotNull(store.get("sub:bob", "bob-1"), "bob's replay survives flood round " + i);
        }
    }

    @Test
    void manyAnonymousClientsShareOnePartitionAndNeverDisplaceAnAuthenticatedOne() {
        Idempotency.Store store = new Idempotency.Store();
        store.put("bob-1", "sub:bob", "h", 200, new byte[0]);
        for (int i = 0; i < 25; i++)
            for (int k = 0; k < 5; k++) store.put("k" + i + "-" + k, "anon@10.0.0." + i, "h", 200, new byte[0]);
        assertNotNull(store.get("sub:bob", "bob-1"), "25 anonymous IPs cost one slot, not 25");
        assertEquals(2, store.principals());
        assertEquals(Idempotency.PER_CALLER_CAP, store.sizeFor("anon@10.0.0.1"), "anonymous partition holds at most the per-caller cap");
    }

    @Test
    void anonymousCallersSharingAPartitionNeverShareAReplay() throws Exception {
        Idempotency.Store store = new Idempotency.Store();
        ExchangeAttributeScopeTest.FakeExchange a = new ExchangeAttributeScopeTest.FakeExchange("/x");
        ExchangeAttributeScopeTest.FakeExchange b = new ExchangeAttributeScopeTest.FakeExchange("/x");
        ApiContext.attr(a, ApiContext.ATTR_CLIENT_IP, "10.0.0.1");
        ApiContext.attr(b, ApiContext.ATTR_CLIENT_IP, "10.0.0.2");
        try {
            String pa = Idempotency.principal(a), pb = Idempotency.principal(b);
            assertNotEquals(pa, pb, "the anonymous principal carries the client IP");
            String ka = Idempotency.keyFor("POST", "/jobs/run", "s", pa, "K");
            String kb = Idempotency.keyFor("POST", "/jobs/run", "s", pb, "K");
            store.put(ka, pa, "h", 200, "A".getBytes());
            assertNotNull(store.get(pa, ka));
            assertNull(store.get(pb, kb), "B never gets A's cached response for the same key");
        } finally {
            ApiContext.attr(a, ApiContext.ATTR_CLIENT_IP, null);
            ApiContext.attr(b, ApiContext.ATTR_CLIENT_IP, null);
        }
    }

    @Test
    void aNullClientAddressHasNoPrincipalSoNothingIsCachedOrReplayed() throws Exception {
        ExchangeAttributeScopeTest.FakeExchange ex = new ExchangeAttributeScopeTest.FakeExchange("/x");   // no Subject, null remote address
        assertNull(Idempotency.principal(ex), "no Subject and no IP: no principal, never a shared 'anon@null' bucket");
    }

    @Test
    void aWriteFromAnExchangeWithNoClientAddressRunsUncachedAndUnreplayed(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            java.lang.reflect.Method stage = ControlApi.class.getDeclaredMethod("idempotency",
                    com.sun.net.httpserver.HttpExchange.class, String.class, String.class);
            stage.setAccessible(true);
            for (int caller = 0; caller < 2; caller++) {   // two callers, same key: neither is keyed, neither replays
                ExchangeAttributeScopeTest.FakeExchange ex = new ExchangeAttributeScopeTest.FakeExchange("/jobs/run");
                ex.getRequestHeaders().add("Idempotency-Key", "K");
                assertEquals(false, stage.invoke(c.api, ex, "POST", "/jobs/run"), "the write runs normally");
                assertNull(ApiContext.attr(ex, ApiContext.ATTR_IDEMPOTENCY_KEY), "no Pending marker: nothing is captured");
                assertNull(ex.getResponseHeaders().getFirst("Idempotency-Replayed"));
            }
        }
    }

    @Test
    void memoryStaysBoundedAsDistinctPrincipalsGrow() {
        Idempotency.Store store = new Idempotency.Store();
        for (int i = 0; i < 5000; i++) store.put("k", "sub:p" + i, "h", 200, new byte[0]);
        assertEquals(Idempotency.MAX_PRINCIPALS, store.principals());
        assertNull(store.get("sub:p0", "k"), "the least recently used principal was evicted");
        assertNotNull(store.get("sub:p4999", "k"), "the most recent principal is kept");
    }
}
