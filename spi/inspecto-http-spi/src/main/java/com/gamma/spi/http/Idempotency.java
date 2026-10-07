package com.gamma.spi.http;

import com.gamma.control.Authenticator;
import com.gamma.control.Authenticators;
import com.gamma.control.Subject;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;

/**
 * {@code Idempotency-Key} support for retryable writes (W5; guideline 29). A POST/PUT/DELETE carrying
 * an {@code Idempotency-Key} header has its first JSON response cached (per-instance, TTL + LRU
 * bounded); a retry with the same key <b>replays</b> that response without re-running the handler — so
 * a retried job trigger returns the same {@code runId} without submitting a second run, and a retried
 * create does not 409.
 *
 * <p>🔴 <b>SEC-IDEMPOTENCY-REPLAY-1 (2026-09-29).</b> The stage used to run BEFORE authentication and keyed
 * on {@code method + path + key} only, so any caller — even anonymous — who sent another user's key read
 * back that user's cached response ({@code /db/query} rows included), an anonymous pre-seed cached a 401
 * that then suppressed the owner's real write for the TTL, and replays skipped the rate limiter. Now:
 * <ul>
 *   <li>the lookup runs in {@code ControlApi.routeDispatch} AFTER authenticate → rate limit → authorize, and
 *       before the handler (so a replay still skips re-execution);</li>
 *   <li>the key is scoped to the caller: {@code method + raw path (carries /api/v1 and /spaces/{id}) + bound
 *       Space + principal + key}, where the principal is the {@link Subject} id, or {@code anon@<client IP>}
 *       when no Subject is attached (Personal edition / a public route);</li>
 *   <li>the entry binds a SHA-256 of the request body — the same key with a different body is
 *       {@code 422 "Idempotency-Key reused with a different request"}, never the cached answer;</li>
 *   <li>only {@link #cacheable} statuses are stored; a response over {@link #MAX_RESPONSE_BYTES}, or a
 *       request body over {@link #MAX_REQUEST_BYTES}, is not cached and says so with
 *       {@code Idempotency-Cached: false}; each principal holds at most {@link #PER_CALLER_CAP} keys in its own partition,
 *       and at most {@link #MAX_PRINCIPALS} principals are held (the least recently used is evicted).</li>
 * </ul>
 *
 * <p><b>Scope:</b> covers retry-after-response. It does not dedupe two <em>simultaneously in-flight</em>
 * duplicates (no reservation step). The captured bytes are the pre-compression JSON, so a replay is written
 * uncompressed regardless of negotiation.
 */
public final class Idempotency {

    private Idempotency() {}

    private static final long TTL_MS = 10 * 60_000L;
    /** Keys one principal may hold at once; its oldest is evicted past this (one caller cannot flush everyone). */
    public static final int PER_CALLER_CAP = 50;
    /** Responses larger than this are answered but not cached ({@code Idempotency-Cached: false}). */
    static final int MAX_RESPONSE_BYTES = 256 * 1024;
    /** Request bodies larger than this are not hashed, so the write runs un-keyed ({@code Idempotency-Cached: false}). */
    public static final int MAX_REQUEST_BYTES = 1024 * 1024;
    public static final String HEADER_CACHED = "Idempotency-Cached";

    public record Entry(String principal, String bodyHash, int status, byte[] body, long expiresAt) {}

    /** The per-exchange marker a miss leaves for {@link #capture}: where to store, under which key, for whom. */
    public record Pending(Store store, String key, String principal, String bodyHash) {}

    /**
     * Which statuses a replay may answer with. 2xx is the point of the feature. 400/409/422 are deterministic
     * outcomes of the request's own content — the same body gets the same answer. Everything else is NOT a
     * property of the request: 401/403 (and a 404 an authorization check answers) depend on who asks and what
     * they hold right now, 429 on the bucket, 5xx may be transient — caching any of them lets one answer outlive
     * its cause, which is exactly how an anonymous 401 used to suppress the owner's real write.
     */
    public static boolean cacheable(int status) {
        return (status >= 200 && status < 300) || status == 400 || status == 409 || status == 422;
    }

    /** Principals holding entries at once; the least recently used principal's whole partition is evicted past
     *  this. {@code MAX_PRINCIPALS * PER_CALLER_CAP} = 1000 entries keeps the old global memory bound. */
    public static final int MAX_PRINCIPALS = 20;
    /** The one partition every anonymous caller shares, so an anonymous flood (many client IPs behind a proxy or
     *  NAT) costs ONE slot and can never displace an authenticated principal. Entry KEYS stay scoped to
     *  {@code anon@<ip>} (see {@link #keyFor}), so callers sharing the partition never share a replay. */
    static final String ANON_PARTITION = "anon";

    private static String partitionOf(String principal) {
        return principal.startsWith("anon@") ? ANON_PARTITION : principal;   // authenticated ids start "sub:"
    }

    /**
     * A per-{@link ControlApi}-instance bounded, TTL cache (not shared across instances, so no test leakage),
     * partitioned by principal (SEC-IDEMPOTENCY-GLOBAL-EVICTION-1): each principal has its own LRU of at most
     * {@link #PER_CALLER_CAP} keys, so a caller flooding keys only evicts its own entries. The partitions sit in
     * an access-ordered map capped at {@link #MAX_PRINCIPALS}, so memory stays bounded as principals grow.
     */
    public static final class Store {
        private final LinkedHashMap<String, LinkedHashMap<String, Entry>> byPrincipal = new LinkedHashMap<>(32, 0.75f, true);

        public synchronized Entry get(String principal, String key) {
            principal = partitionOf(principal);
            LinkedHashMap<String, Entry> part = byPrincipal.get(principal);
            if (part == null) return null;
            Entry e = part.get(key);
            if (e == null) return null;
            if (System.currentTimeMillis() > e.expiresAt()) {
                part.remove(key);
                if (part.isEmpty()) byPrincipal.remove(principal);
                return null;
            }
            return e;
        }

        public synchronized void put(String key, String principal, String bodyHash, int status, byte[] body) {
            LinkedHashMap<String, Entry> part = byPrincipal.computeIfAbsent(partitionOf(principal), p -> new LinkedHashMap<>(16, 0.75f, true));
            part.remove(key);
            part.put(key, new Entry(principal, bodyHash, status, body.clone(), System.currentTimeMillis() + TTL_MS));
            while (part.size() > PER_CALLER_CAP) part.remove(part.keySet().iterator().next());
            while (byPrincipal.size() > MAX_PRINCIPALS) byPrincipal.remove(byPrincipal.keySet().iterator().next());
        }

        public synchronized int sizeFor(String principal) {
            LinkedHashMap<String, Entry> part = byPrincipal.get(partitionOf(principal));
            return part == null ? 0 : part.size();
        }

        public synchronized int principals() {
            return byPrincipal.size();
        }
    }

    /** The raw Idempotency-Key header of a write, or {@code null} when idempotency does not apply. */
    public static String headerKey(HttpExchange ex, String method) {
        if (!("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method))) return null;
        String k = ex.getRequestHeaders().getFirst("Idempotency-Key");
        return (k == null || k.isBlank()) ? null : k.trim();
    }

    /** Who the cache entry belongs to: the authenticated Subject namespaced by its token's issuer (or, when the
     *  Authenticator stamps none, by the Authenticator itself), else a per-client anonymous principal. Two IdPs
     *  that mint the same {@code sub} therefore never share entries. {@code null} (fail closed: no caching, no
     *  replay, no in-flight fence) when there is neither a Subject nor a client IP - a shared bucket would let
     *  such callers replay each other's responses. */
    public static String principal(HttpExchange ex) {
        return ApiContext.subject(ex).map(s -> {
            String iss = ApiContext.attr(ex, ApiContext.ATTR_SUBJECT_ISSUER) instanceof String i ? i
                    : Authenticators.active().map(a -> a.getClass().getName()).orElse("-");
            return "sub:" + iss.length() + ":" + iss + ":" + s.id();   // length-prefixed: no issuer/id ambiguity
        }).orElseGet(() -> ApiContext.ip(ex) == null ? null : "anon@" + ApiContext.ip(ex));
    }

    /** The caller-scoped cache key. {@code routePath} is the path the router MATCHED - already percent-decoded,
     *  with {@code /api/v1} and {@code /spaces/{id}} stripped (the Space rides separately) - then canonicalised:
     *  duplicate slashes collapsed, a trailing slash dropped. Case is kept exact: routes match case-sensitively.
     *  So every spelling the router sends to one route shares one entry. */
    public static String keyFor(String method, String routePath, String space, String principal, String key) {
        return method + " " + canonical(routePath) + " space=" + space + " " + principal + " " + key;
    }

    public static String canonical(String path) {
        String p = path.replaceAll("/{2,}", "/");
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    /**
     * Read (up to {@link #MAX_REQUEST_BYTES}) and hash the request body, leaving it readable for the handler
     * via {@code ATTR_RAW_BODY}; {@code null} when it is larger — the prefix read is stitched back in front of
     * the rest of the stream so the handler still sees every byte.
     */
    public static String bodyHash(HttpExchange ex) throws IOException {
        String q = ex.getRequestURI().getRawQuery();   // the query is part of the request: a different one is a mismatch
        String query = q == null ? "" : q;
        if (ApiContext.attr(ex, ApiContext.ATTR_RAW_BODY) instanceof byte[] cached) return sha256(query, cached);
        InputStream in = ex.getRequestBody();
        byte[] head = in.readNBytes(MAX_REQUEST_BYTES + 1);
        if (head.length > MAX_REQUEST_BYTES) {
            ex.setStreams(new SequenceInputStream(new ByteArrayInputStream(head), in), null);
            return null;
        }
        ex.setStreams(new ByteArrayInputStream(head), null);   // a handler reading the stream directly still sees it
        ApiContext.attr(ex, ApiContext.ATTR_RAW_BODY, head);
        return sha256(query, head);
    }

    private static String sha256(String query, byte[] b) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(query.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            md.update((byte) 0);
            return HexFormat.of().formatHex(md.digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Cache a just-computed JSON response (pre-compression) for replay, when this exchange carries a key. */
    public static void capture(HttpExchange ex, int status, byte[] jsonBytes) {
        if (!(ApiContext.attr(ex, ApiContext.ATTR_IDEMPOTENCY_KEY) instanceof Pending p)) return;
        if (!cacheable(status)) return;
        if (jsonBytes.length > MAX_RESPONSE_BYTES) {
            ex.getResponseHeaders().set(HEADER_CACHED, "false");
            return;
        }
        p.store().put(p.key(), p.principal(), p.bodyHash(), status, jsonBytes);
    }

    /** Write a previously-cached response, flagged {@code Idempotency-Replayed: true}. */
    public static void replay(HttpExchange ex, Entry hit) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Idempotency-Replayed", "true");
        ex.sendResponseHeaders(hit.status(), hit.body().length);
        ex.getResponseBody().write(hit.body());
    }
}
