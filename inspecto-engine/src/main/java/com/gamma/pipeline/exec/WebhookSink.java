package com.gamma.pipeline.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.acquire.SecretResolver;
import com.gamma.acquire.retry.RetryPolicy;
import com.gamma.api.PublicApi;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.PipelineNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Function;

/**
 * <b>The {@code sink.webhook} executor.</b> POSTs a committed sink branch's rows as JSON to the https
 * Connection the node names, {@code batch_size} rows per request. Called by {@link PartitionSinkWriter} on
 * the at-rest lane — the only lane it runs on ({@code PipelineConfig.prepare()} refuses the ingest lane).
 *
 * <h3>Body</h3>
 * {@code {"consignment": <id>, "sink": <node id>, "batch": <n>, "batches": <total>, "rows": [{col: value, …}]}}
 * — {@code batch} is 1-based. A value is written as a JSON number / boolean / string when it is one, and as
 * its string form otherwise (dates, timestamps, decimals-as-text are the receiver's to parse). An empty
 * relation sends nothing.
 *
 * <h3>Delivery semantics (same posture as the persistent sink: fail the branch)</h3>
 * Each POST runs under the node's {@code retry:} through the Collector's own {@link RetryPolicy}, carrying
 * {@code Idempotency-Key: <consignment>:<sink>:<batch>} — the SAME key on every attempt of one batch, so a
 * receiver can drop a retried duplicate. When a batch exhausts its retries the write throws: the
 * {@link BranchCommitCoordinator} records the branch failed and the source is not finalised. ⚠ Batches
 * already accepted stay accepted — HTTP has no rollback — so a re-run re-sends them under the same keys,
 * which is what the key is for. Keys are stable across a re-run only as far as the relation's row order is;
 * this class adds no {@code ORDER BY}.
 *
 * <h3>Egress (operator decision 2026-09-23)</h3>
 * The target is never authored on the pipeline. It is the named {@link ConnectionProfile} with connector
 * {@code https} — onboarded only under the admin-only {@code canOnboardConnections} grant — built as
 * {@code https://<host>[:<port>]<base_path>}; plain {@code http} is not a scheme this sink can build. The
 * bearer token is the profile's {@code password}, a {@link SecretResolver} reference resolved here at send
 * time and never logged. A profile declaring a tunnel or proxy is REFUSED: this sink does not dial through
 * either, and silently bypassing a proxy an administrator configured is a worse failure than refusing.
 */
@PublicApi(since = "4.0.0")
public final class WebhookSink {

    private static final Logger log = LoggerFactory.getLogger(WebhookSink.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The only connector scheme a webhook target may be. */
    public static final String CONNECTOR = "https";
    /** Request header carrying {@code <consignment>:<sink>:<batch>}, identical on every retry of a batch. */
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    /** Connection option naming the per-request timeout in seconds (default {@link #DEFAULT_TIMEOUT_SECONDS}). */
    public static final String TIMEOUT_OPTION = "timeout_seconds";
    /** The webhook notification channel's default, reused. */
    public static final long DEFAULT_TIMEOUT_SECONDS = 10L;

    private WebhookSink() {}

    /** Where a webhook node sends, fully resolved — everything but the rows. */
    public record Target(URI url, String bearerToken, Duration timeout, PipelineConfig.Webhook webhook,
                         WebhookSinkTransport transport) {
        @Override public String toString() {       // never print the token
            return "Target[" + url + ", batch_size=" + webhook.batchSize() + "]";
        }
    }

    /**
     * Resolve {@code sink} without sending anything: its config, the edition's transport, the Connection and
     * its token. Every refusal a real send would hit is raised here — which is what a dry run calls.
     */
    public static Target plan(PipelineNode sink) {
        return plan(sink, discoveredTransport());
    }

    static Target plan(PipelineNode sink, WebhookSinkTransport transport) {
        Map<String, Object> cfg = new LinkedHashMap<>(sink.config());
        cfg.remove("enabled");                     // the node-level flag, not a webhook: key
        PipelineConfig.Webhook w;
        try {
            w = PipelineConfig.Webhook.fromMap(cfg);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("sink '" + sink.id() + "': " + e.getMessage(), e);
        }
        if (transport == null)
            throw new IllegalStateException("sink '" + sink.id() + "' is a webhook, and this bundle ships no "
                    + "outbound HTTP transport — webhook delivery is a Professional/Enterprise edition capability "
                    + "(inspecto-notify-channels). Nothing was sent.");
        ConnectionProfile p = ConnectionRegistry.find(w.connection()).orElseThrow(() -> new IllegalStateException(
                "sink '" + sink.id() + "' names Connection '" + w.connection() + "', which is not registered "
                        + "in this space"));
        if (!CONNECTOR.equals(p.connector()))
            throw new IllegalStateException("sink '" + sink.id() + "': Connection '" + p.id() + "' is a '"
                    + p.connector() + "' connection — a webhook target must be an '" + CONNECTOR + "' connection");
        if (p.tunnel() != null || p.proxy() != null)
            throw new IllegalStateException("sink '" + sink.id() + "': Connection '" + p.id() + "' declares a "
                    + (p.tunnel() != null ? "tunnel" : "proxy") + ", which the webhook sink does not dial "
                    + "through — refused rather than bypassed");
        if (p.host() == null || p.host().isBlank())
            throw new IllegalStateException("sink '" + sink.id() + "': Connection '" + p.id() + "' has no host");
        String path = p.basePath() == null || p.basePath().isBlank() ? "/"
                : (p.basePath().startsWith("/") ? p.basePath() : "/" + p.basePath());
        URI url;
        try {
            url = new URI(CONNECTOR, null, p.host(), p.port() > 0 ? p.port() : -1, path, null, null);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("sink '" + sink.id() + "': Connection '" + p.id()
                    + "' does not form a valid URL: " + e.getMessage(), e);
        }
        String token = null;
        if (p.password() != null && !p.password().isBlank()) {
            token = SecretResolver.resolve(p.password());
            if (token == null)
                throw new IllegalStateException("sink '" + sink.id() + "': the token reference on Connection '"
                        + p.id() + "' does not resolve on this host");
        }
        long timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        String t = p.options().get(TIMEOUT_OPTION);
        if (t != null && !t.isBlank()) {
            try {
                timeoutSeconds = Long.parseLong(t.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("sink '" + sink.id() + "': Connection '" + p.id() + "' option "
                        + TIMEOUT_OPTION + " must be a whole number of seconds, got: " + t);
            }
            if (timeoutSeconds < 1)
                throw new IllegalStateException("sink '" + sink.id() + "': " + TIMEOUT_OPTION + " must be ≥ 1");
        }
        return new Target(url, token, Duration.ofSeconds(timeoutSeconds), w, transport);
    }

    /** Send {@code inputTable}'s rows; returns the number of rows accepted. Throws when a batch fails. */
    public static long deliver(Connection conn, PipelineNode sink, String inputTable, String consignmentId)
            throws Exception {
        return deliver(conn, sink, inputTable, consignmentId, plan(sink), RetryPolicy::from);
    }

    /** Test seam: a planned target and the retry policy factory injected. */
    static long deliver(Connection conn, PipelineNode sink, String inputTable, String consignmentId,
                        Target target, Function<PipelineConfig.Retry, RetryPolicy> retries) throws Exception {
        int batchSize = target.webhook().batchSize();
        RetryPolicy policy = retries.apply(target.webhook().retry());
        String quoted = "\"" + inputTable.replace("\"", "\"\"") + "\"";
        long total;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM " + quoted)) {
            rs.next();
            total = rs.getLong(1);
        }
        if (total == 0) {
            log.info("[PIPELINEJOB] sink '{}' → webhook '{}': no rows, nothing sent", sink.id(),
                    target.webhook().connection());
            return 0L;
        }
        long batches = (total + batchSize - 1) / batchSize;
        long sent = 0L;
        int batch = 0;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + quoted)) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            List<Map<String, Object>> rows = new ArrayList<>(batchSize);
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int c = 1; c <= cols; c++) row.put(md.getColumnLabel(c), jsonValue(rs.getObject(c)));
                rows.add(row);
                if (rows.size() == batchSize) {
                    send(target, policy, sink, consignmentId, ++batch, batches, rows);
                    sent += rows.size();
                    rows = new ArrayList<>(batchSize);
                }
            }
            if (!rows.isEmpty()) {
                send(target, policy, sink, consignmentId, ++batch, batches, rows);
                sent += rows.size();
            }
        }
        log.info("[PIPELINEJOB] sink '{}' → webhook '{}': {} row(s) in {} request(s)", sink.id(),
                target.webhook().connection(), sent, batch);
        return sent;
    }

    private static void send(Target target, RetryPolicy policy, PipelineNode sink, String consignmentId,
                             int batch, long batches, List<Map<String, Object>> rows) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("consignment", consignmentId);
        body.put("sink", sink.id());
        body.put("batch", batch);
        body.put("batches", batches);
        body.put("rows", rows);
        String json = JSON.writeValueAsString(body);
        Map<String, String> headers = Map.of(IDEMPOTENCY_HEADER, consignmentId + ":" + sink.id() + ":" + batch);
        try {
            policy.execute(() -> {
                target.transport().post(target.url(), target.bearerToken(), target.timeout(), json, headers);
                return null;
            });
        } catch (Exception e) {
            throw new IllegalStateException("sink '" + sink.id() + "' → webhook '" + target.webhook().connection()
                    + "': batch " + batch + "/" + batches + " was not accepted after " + policy.attempts()
                    + " attempt(s): " + e.getMessage(), e);
        }
    }

    /** JSON-native values pass through; anything else travels as its string form. */
    private static Object jsonValue(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean) return v;
        if (v instanceof Number n) {
            if (n instanceof Double d && (d.isNaN() || d.isInfinite())) return d.toString();
            if (n instanceof Float f && (f.isNaN() || f.isInfinite())) return f.toString();
            return n;
        }
        return v.toString();
    }

    /** The first bundled {@link WebhookSinkTransport}, or {@code null} — Personal bundles none. */
    static WebhookSinkTransport discoveredTransport() {
        return ServiceLoader.load(WebhookSinkTransport.class).findFirst().orElse(null);
    }
}
