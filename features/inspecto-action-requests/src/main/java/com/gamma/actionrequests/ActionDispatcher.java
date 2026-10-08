package com.gamma.actionrequests;

import com.gamma.pipeline.exec.EgressAllowlist;
import com.gamma.spi.http.ApiContext;
import com.gamma.util.egress.EgressPolicy;
import com.gamma.pipeline.exec.WebhookSink;
import com.gamma.pipeline.exec.WebhookSinkTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Sends an approved {@link ActionRequests Action Request} ({@code ASSURE-ACTION-REQUESTS-1}) and records what
 * happened.
 *
 * <p><b>What is sent</b> — ONLY what the signed record holds: its method, its rendered payload and its
 * idempotency key, to the URL its Connection resolves to. The Connection is resolved here through
 * {@link WebhookSink#endpoint} — the webhook sink's egress rules, reused rather than copied: a registered
 * {@code https} Connection (onboarded under the admin-only {@code canOnboardConnections}), a host, no tunnel or
 * proxy, the bearer token resolved from its secret reference at send time — and the resolved URL must still be
 * the {@code targetUrl} the approver read, or nothing is sent. The wire is the edition's
 * {@link WebhookSinkTransport} (Professional / Enterprise; Personal bundles none, and the request fails naming
 * the edition). On top of that, the {@link EgressPolicy}: before EVERY attempt the host is resolved once, every
 * address is checked against the deny-by-default classes (loopback, link-local, private, CGNAT, multicast, this
 * host…) less the Space's {@link EgressAllowlist egress allowlist}, and the wire connects to THAT address (never
 * re-resolving), keeping the name for Host / SNI / certificate verification. A refusal fails the request at once,
 * nothing sent. Redirects are never followed and a 3xx fails the request without a retry.
 *
 * <p><b>Retries</b> — up to {@code -Daction.dispatch.maxAttempts} attempts (default 3, clamped 1..10), with
 * exponential backoff from {@code -Daction.dispatch.backoffMs} (default 1000 ms, doubling, capped at 30 s), each
 * under the Connection's {@code timeout_seconds}. The idempotency key is the SAME header on every attempt, so a
 * receiver that did get an earlier attempt can drop the repeat. Retryable: an I/O failure or timeout, a 5xx, 408
 * and 429; anything else fails at once.
 *
 * <p><b>Integrity</b> — the record is re-read and its MAC re-verified before EVERY attempt, and again before each
 * result is written: a record edited on disk after approval is never sent and never re-signed.
 *
 * <p>Never logs a payload, a token or a response body — only the id, the attempt and the status.
 */
final class ActionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ActionDispatcher.class);

    static final String IDEMPOTENCY_HEADER = WebhookSink.IDEMPOTENCY_HEADER;
    static final String PROP_MAX_ATTEMPTS = "action.dispatch.maxAttempts";
    static final String PROP_BACKOFF_MS = "action.dispatch.backoffMs";
    private static final long BACKOFF_CAP_MS = 30_000L;

    /** Where dispatches run — a virtual thread each; a test swaps in a direct executor to run them inline. */
    static volatile Executor executor = Executors.newVirtualThreadPerTaskExecutor();
    /** The outbound wire — the edition's transport; a test swaps in its own. */
    static volatile Supplier<WebhookSinkTransport> transport = WebhookSink::discoveredTransport;
    /** Name resolution for the egress check — the platform's; a test gives its target a name and an address. */
    static volatile EgressPolicy.Resolver resolver = EgressPolicy.SYSTEM;

    private ActionDispatcher() {}

    static int maxAttempts() {
        return Math.max(1, Math.min(10, Integer.getInteger(PROP_MAX_ATTEMPTS, 3)));
    }

    static long backoffMs() {
        return Math.max(0L, Math.min(BACKOFF_CAP_MS, Long.getLong(PROP_BACKOFF_MS, 1000L)));
    }

    /**
     * Start dispatching request {@code id}, which the caller has just saved as {@code approved} (or, for a retry,
     * {@code dispatched}). Resolves the Connection on THIS thread — it carries the Space — and hands the sending
     * to {@link #executor}. A Connection that no longer resolves, or resolves somewhere else than the approved
     * {@code targetUrl}, fails the request with nothing sent.
     */
    static void submit(Path root, String id) throws java.io.IOException {
        WebhookSinkTransport wire = transport.get();
        WebhookSink.Endpoint endpoint = null;
        String refusal = null;
        Map<String, Object> rec;
        synchronized (ActionRequests.lock()) {
            rec = ActionRequests.read(root, id);
            if (rec == null || ActionRequests.invalid(rec)) return;
        }
        if (wire == null) {
            refusal = "this bundle ships no outbound HTTP transport — Action Request dispatch is a "
                    + "Professional/Enterprise edition capability (inspecto-notify-channels). Nothing was sent.";
        } else {
            try {
                endpoint = WebhookSink.endpoint(String.valueOf(rec.get("connection")), "action request '" + id + "'");
                if (!endpoint.url().toString().equals(rec.get("targetUrl")))
                    refusal = "Connection '" + rec.get("connection") + "' now resolves to " + endpoint.url()
                            + ", not the approved " + rec.get("targetUrl") + " — nothing was sent";
            } catch (IllegalStateException e) {
                refusal = e.getMessage();
            }
        }
        if (refusal != null) {
            fail(root, id, refusal);
            return;
        }
        WebhookSink.Endpoint to = endpoint;
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        executor.execute(() -> {
            Map<String, String> before = MDC.getCopyOfContextMap();
            if (mdc != null) MDC.setContextMap(mdc);
            try {
                run(root, id, to, wire);
            } catch (Exception e) {
                log.warn("[ACTION] {} dispatch stopped: {}", id, e.getClass().getSimpleName());
            } finally {
                if (before != null) MDC.setContextMap(before);
                else MDC.clear();
            }
        });
    }

    /** Record a failure that happened before any attempt: no attempt counted, nothing sent. */
    private static void fail(Path root, String id, String reason) throws java.io.IOException {
        synchronized (ActionRequests.lock()) {
            Map<String, Object> rec = ActionRequests.read(root, id);
            if (rec == null || ActionRequests.invalid(rec)) return;
            rec.put("lastResponse", response(null, null, reason, 0));
            ActionRequests.transition(rec, ActionRequests.FAILED, "system");
            rec.put("completedAt", ActionRequests.now());
            ActionRequests.save(root, rec);
            ActionRequests.audit("system", "system", "action-request.failed", id + " not dispatched: " + reason, rec);
        }
    }

    /** The attempt loop. Package-private so a test can drive one dispatch synchronously. */
    static void run(Path root, String id, WebhookSink.Endpoint endpoint, WebhookSinkTransport wire) throws Exception {
        int max = maxAttempts();
        for (int n = 1; n <= max; n++) {
            String method, key, json;
            synchronized (ActionRequests.lock()) {
                Map<String, Object> rec = ActionRequests.read(root, id);
                if (rec == null) return;
                if (ActionRequests.invalid(rec)) {
                    log.warn("[ACTION] {} fails its integrity check — not sent", id);
                    ActionRequests.audit("system", "system", "action-request.refused",
                            id + " fails its integrity check — not sent", rec);
                    return;
                }
                Object status = rec.get("status");
                if (ActionRequests.APPROVED.equals(status)) {
                    ActionRequests.transition(rec, ActionRequests.DISPATCHED, "system");
                    rec.put("dispatchedAt", ActionRequests.now());
                    ActionRequests.save(root, rec);
                } else if (!ActionRequests.DISPATCHED.equals(status)) {
                    return;   // declined, already finished, or another dispatch owns it
                }
                method = String.valueOf(rec.get("method"));
                key = String.valueOf(rec.get("idempotencyKey"));
                json = ApiContext.JSON.writeValueAsString(rec.get("payload"));
            }

            WebhookSinkTransport.Response r = null;
            String error = null;
            String address = null;
            boolean egressRefused = false;
            try {
                // Resolve ONCE per attempt and check EVERY address; the wire connects to the checked one.
                java.net.InetAddress to = EgressPolicy.resolve(endpoint.url().getHost().replaceAll("^\\[|\\]$", ""),
                        EgressAllowlist.of(root), resolver);
                address = to.getHostAddress();
                r = wire.exchange(method, endpoint.url(), to, endpoint.bearerToken(), endpoint.timeout(), json,
                        Map.of(IDEMPOTENCY_HEADER, key), ActionRequests.EXCERPT_CAP);
            } catch (EgressPolicy.Refused refused) {
                egressRefused = true;
                error = "egress refused: " + refused.getMessage();
            } catch (Exception e) {
                error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + cap(e.getMessage()));
            }
            int code = r == null ? 0 : r.status();
            boolean ok = r != null && code / 100 == 2;
            boolean redirect = r != null && code / 100 == 3;
            boolean retryable = !egressRefused && (r == null || code / 100 == 5 || code == 408 || code == 429);
            log.info("[ACTION] {} attempt {}/{} -> {}", id, n, max, r == null ? "no response" : code);

            synchronized (ActionRequests.lock()) {
                Map<String, Object> rec = ActionRequests.read(root, id);
                if (rec == null || ActionRequests.invalid(rec) || !ActionRequests.DISPATCHED.equals(rec.get("status")))
                    return;   // never re-sign a record that changed under us
                int attempts = rec.get("attempts") instanceof Number num ? num.intValue() : 0;
                rec.put("attempts", attempts + 1);
                String why = redirect ? "the target answered a redirect (" + code + ") — redirects are never "
                        + "followed; the Connection's base URL is the only place this request may go" : error;
                Map<String, Object> resp = response(r == null ? null : code, r == null ? null : r.bodyExcerpt(), why, n);
                resp.put("address", address);
                rec.put("lastResponse", resp);
                rec.put("attemptLog", appendAttempt(rec.get("attemptLog"), n, address, r == null ? null : code, why));
                if (ok || !retryable || n == max) {
                    ActionRequests.transition(rec, ok ? ActionRequests.SUCCEEDED : ActionRequests.FAILED, "system");
                    rec.put("completedAt", ActionRequests.now());
                    ActionRequests.save(root, rec);
                    ActionRequests.audit("system", "system", ok ? "action-request.succeeded" : "action-request.failed",
                            id + (ok ? " delivered" : " failed") + " after " + (attempts + 1) + " attempt(s)"
                                    + (r == null ? "" : " (HTTP " + code + ")"), rec);
                    return;
                }
                ActionRequests.save(root, rec);
            }
            long wait = Math.min(BACKOFF_CAP_MS, backoffMs() << Math.min(20, n - 1));
            if (wait > 0) Thread.sleep(wait);
        }
    }

    /** The most attempts {@code attemptLog} keeps (the latest). */
    static final int ATTEMPT_LOG_CAP = 50;

    /** One line per attempt: which address it connected to (the checked one) and what came back. */
    @SuppressWarnings("unchecked")
    private static java.util.List<Object> appendAttempt(Object log, int n, String address, Integer status, String error) {
        java.util.List<Object> out = log instanceof java.util.List<?> l ? new java.util.ArrayList<>((java.util.List<Object>) l)
                : new java.util.ArrayList<>();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("attempt", n);
        a.put("address", address);
        a.put("status", status);
        a.put("error", error);
        a.put("at", ActionRequests.now());
        out.add(a);
        while (out.size() > ATTEMPT_LOG_CAP) out.remove(0);
        return out;
    }

    private static Map<String, Object> response(Integer status, String excerpt, String error, int attempt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("bodyExcerpt", excerpt == null ? null : cap(excerpt));
        m.put("error", error);
        m.put("attempt", attempt);
        m.put("at", ActionRequests.now());
        return m;
    }

    private static String cap(String s) {
        return s.length() > ActionRequests.EXCERPT_CAP ? s.substring(0, ActionRequests.EXCERPT_CAP) : s;
    }
}
