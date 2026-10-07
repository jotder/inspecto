package com.gamma.pipeline.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.acquire.retry.RetryPolicy;
import com.gamma.pipeline.BuiltinNodeType;
import com.gamma.pipeline.PipelineNode;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WebhookSink} over a real DuckDB relation and a capturing {@link WebhookSinkTransport} — everything
 * but the wire, which {@code HttpWebhookSinkTransportTest} (inspecto-notify-channels) covers against a JDK
 * {@code HttpServer}. The wire cannot be driven from here on purpose: this module ships no transport, and
 * {@link #theEngineBundlesNoTransport} pins that.
 */
class WebhookSinkTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN_PROP = "webhook.sink.test.token";

    private File db;
    private Connection conn;

    /** One captured POST. */
    record Post(URI url, String token, Duration timeout, String body, Map<String, String> headers) {}

    /** Records every attempt; fails the first {@code failFirst} of them. */
    static final class Capture implements WebhookSinkTransport {
        final List<Post> posts = new ArrayList<>();
        int failFirst;
        @Override public void post(URI url, String bearerToken, Duration timeout, String jsonBody,
                                   Map<String, String> headers) {
            posts.add(new Post(url, bearerToken, timeout, jsonBody, headers));
            if (posts.size() <= failFirst) throw new IllegalStateException("webhook returned HTTP 503");
        }
    }

    @BeforeEach
    void open() throws Exception {
        db = DuckDbUtil.tempDbFile("wh_");
        conn = DuckDbUtil.openConnection(db);
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE rows_in AS SELECT * FROM (VALUES (1,'a',1.5,DATE '2026-09-01'),"
                    + "(2,'b',NULL,DATE '2026-09-02'),(3,'c',3.0,DATE '2026-09-03'),(4,'d',4.25,DATE '2026-09-04'),"
                    + "(5,'e',5.0,DATE '2026-09-05')) t(id,name,amt,day)");
            st.execute("CREATE TABLE empty_in (id INT)");
        }
        System.setProperty(TOKEN_PROP, "s3cret");
        ConnectionRegistry.register(profile("hook", "https", null));
    }

    @AfterEach
    void close() throws Exception {
        ConnectionRegistry.clear();
        System.clearProperty(TOKEN_PROP);
        if (conn != null) conn.close();
        DuckDbUtil.deleteTempDb(db);
    }

    private static ConnectionProfile profile(String id, String connector, ConnectionProfile.Proxy proxy) {
        return new ConnectionProfile(id, connector, "hooks.example.test", 8443, null, "ingest/orders", null,
                "${SYS:" + TOKEN_PROP + "}", Map.of("timeout_seconds", "7"), null, proxy);
    }

    private static PipelineNode node(Map<String, Object> cfg) {
        return PipelineNode.of("webhook", BuiltinNodeType.SINK_WEBHOOK.type(), new LinkedHashMap<>(cfg));
    }

    private static final RetryPolicy.Sleeper NO_SLEEP = ms -> {};

    @Test
    void sendsRowsInBatchesToTheConnectionsHttpsEndpoint() throws Exception {
        Capture wire = new Capture();
        PipelineNode sink = node(Map.of("connection", "hook", "batch_size", 2));
        long sent = WebhookSink.deliver(conn, sink, "rows_in", "c-42", WebhookSink.plan(sink, wire), RetryPolicy::from);

        assertEquals(5, sent);
        assertEquals(3, wire.posts.size(), "5 rows at batch_size 2 ⇒ 2 + 2 + 1");
        Post first = wire.posts.get(0);
        assertEquals(URI.create("https://hooks.example.test:8443/ingest/orders"), first.url(),
                "the URL is BUILT from the Connection, https only — never authored on the pipeline");
        assertEquals("s3cret", first.token(), "the token is the Connection's SecretResolver reference, resolved");
        assertEquals(Duration.ofSeconds(7), first.timeout());

        Map<?, ?> body = JSON.readValue(first.body(), Map.class);
        assertEquals("c-42", body.get("consignment"));
        assertEquals("webhook", body.get("sink"));
        assertEquals(1, body.get("batch"));
        assertEquals(3, body.get("batches"));
        List<?> rows = (List<?>) body.get("rows");
        assertEquals(2, rows.size());
        long total = 0;
        for (Post p : wire.posts) total += ((List<?>) JSON.readValue(p.body(), Map.class).get("rows")).size();
        assertEquals(5, total, "every row is sent exactly once across the batches");
        Map<?, ?> anyRow = (Map<?, ?>) rows.get(0);
        assertEquals(List.of("id", "name", "amt", "day"), new ArrayList<>(anyRow.keySet()),
                "columns keep their names and order");
        assertInstanceOf(Number.class, anyRow.get("id"), "a number stays a JSON number");
        assertInstanceOf(String.class, anyRow.get("day"), "a DATE travels as its string form");

        assertEquals("c-42:webhook:1", first.headers().get(WebhookSink.IDEMPOTENCY_HEADER));
        assertEquals("c-42:webhook:3", wire.posts.get(2).headers().get(WebhookSink.IDEMPOTENCY_HEADER));
    }

    /** A rejected POST is retried under the node's retry: — with the SAME body and the SAME key each time. */
    @Test
    void retriesARejectedBatchUnderTheSameIdempotencyKey() throws Exception {
        Capture wire = new Capture();
        wire.failFirst = 2;
        PipelineNode sink = node(Map.of("connection", "hook", "batch_size", 10,
                "retry", Map.of("count", 2, "backoff", "FIXED", "initial_delay", "1s")));
        List<Long> sleeps = new ArrayList<>();
        long sent = WebhookSink.deliver(conn, sink, "rows_in", "c-7", WebhookSink.plan(sink, wire),
                r -> RetryPolicy.forTest(r.count(), RetryPolicy.Backoff.from(r.backoff()),
                        r.initialDelayMillis(), r.maxDelayMillis(), sleeps::add));

        assertEquals(5, sent);
        assertEquals(3, wire.posts.size(), "two failures, then the retry that succeeds");
        assertEquals(List.of(1_000L, 1_000L), sleeps, "the Collector's RetryPolicy drives the backoff");
        for (Post p : wire.posts) {
            assertEquals("c-7:webhook:1", p.headers().get(WebhookSink.IDEMPOTENCY_HEADER));
            assertEquals(wire.posts.get(0).body(), p.body(), "a retry re-sends the identical body");
        }
    }

    /** Out of retries ⇒ the write throws, so the branch fails and the source is not finalised. */
    @Test
    void aBatchThatExhaustsItsRetriesFailsTheWrite() throws Exception {
        Capture wire = new Capture();
        wire.failFirst = Integer.MAX_VALUE;
        PipelineNode sink = node(Map.of("connection", "hook", "batch_size", 2, "retry", Map.of("count", 1)));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> WebhookSink.deliver(conn, sink,
                "rows_in", "c-1", WebhookSink.plan(sink, wire),
                r -> RetryPolicy.forTest(r.count(), RetryPolicy.Backoff.FIXED, 1, 1, NO_SLEEP)));
        assertTrue(e.getMessage().contains("batch 1/3") && e.getMessage().contains("2 attempt"), e.getMessage());
        assertEquals(2, wire.posts.size(), "batch 2 is never attempted once batch 1 has failed");
    }

    @Test
    void anEmptyRelationSendsNothing() throws Exception {
        Capture wire = new Capture();
        PipelineNode sink = node(Map.of("connection", "hook"));
        assertEquals(0, WebhookSink.deliver(conn, sink, "empty_in", "c", WebhookSink.plan(sink, wire), RetryPolicy::from));
        assertTrue(wire.posts.isEmpty());
    }

    // ── refusals: each names its fix, none sends a byte ─────────────────────────

    /** EDG-01: Personal ships no transport, and a webhook write says which edition it needs. */
    @Test
    void withNoTransportTheWriteRefusesNamingTheEdition() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> WebhookSink.plan(node(Map.of("connection", "hook")), null));
        assertTrue(e.getMessage().contains("Professional"), e.getMessage());
    }

    @Test
    void theEngineBundlesNoTransport() {
        assertNull(WebhookSink.discoveredTransport(),
                "inspecto-engine must not register a WebhookSinkTransport — it ships in every edition");
    }

    @Test
    void refusesANonHttpsConnection() {
        ConnectionRegistry.register(profile("files", "sftp", null));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> WebhookSink.plan(node(Map.of("connection", "files")), new Capture()));
        assertTrue(e.getMessage().contains("'sftp'") && e.getMessage().contains("https"), e.getMessage());
    }

    @Test
    void refusesAConnectionWithAProxyRatherThanBypassingIt() {
        ConnectionRegistry.register(profile("proxied", "https",
                new ConnectionProfile.Proxy("HTTP", "proxy.test", 3128, null, null)));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> WebhookSink.plan(node(Map.of("connection", "proxied")), new Capture()));
        assertTrue(e.getMessage().contains("proxy"), e.getMessage());
    }

    @Test
    void refusesAnUnknownConnection() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> WebhookSink.plan(node(Map.of("connection", "nope")), new Capture()));
        assertTrue(e.getMessage().contains("'nope'"), e.getMessage());
    }

    @Test
    void refusesATokenReferenceThatDoesNotResolve() {
        System.clearProperty(TOKEN_PROP);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> WebhookSink.plan(node(Map.of("connection", "hook")), new Capture()));
        assertTrue(e.getMessage().contains("token reference"), e.getMessage());
        assertFalse(e.getMessage().contains("s3cret"));
    }

    @Test
    void refusesAnAuthoredUrlOnTheNode() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> WebhookSink.plan(node(Map.of("connection", "hook", "url", "https://elsewhere.test/")), new Capture()));
        assertTrue(e.getMessage().contains("url"), e.getMessage());
    }

    /** The node-level disable flag is not a webhook: key and must not trip the strict key check. */
    @Test
    void theEnabledFlagIsNotAWebhookKey() {
        assertNotNull(WebhookSink.plan(node(Map.of("connection", "hook", "enabled", true)), new Capture()));
    }

    /** The job lane's real writer dispatches a webhook node to WebhookSink — here, with no transport, to its refusal. */
    @Test
    void thePartitionSinkWriterDispatchesAWebhookNodeInsteadOfWritingBytes() {
        PartitionSinkWriter w = new PartitionSinkWriter(conn, db.getParent(), "base", "c-9", "r-1", "p");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> w.write(node(Map.of("connection", "hook")), "rows_in"));
        assertTrue(e.getMessage().contains("Professional"),
                "reached WebhookSink (not the 'declares no store' refusal): " + e.getMessage());
        assertTrue(w.outputs().isEmpty());
    }

    /** The dry-run writer resolves the target — so it refuses exactly what a real run would — and sends nothing. */
    @Test
    void theDryRunWriterRefusesWhatTheRunWouldRefuse() {
        DryRunSinkWriter w = new DryRunSinkWriter(conn, null, null, "p");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> w.write(node(Map.of("connection", "hook")), "rows_in"));
        assertTrue(e.getMessage().contains("Professional"), e.getMessage());
    }
}
