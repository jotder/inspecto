package com.gamma.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

/**
 * The shared HTTP plumbing a {@link RouteModule} needs to register and serve routes without
 * depending on the {@link ControlApi} host directly. ControlApi is the sole implementation; the
 * indirection lets cohesive route groups live in their own classes (lower coupling, thinner host).
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public interface ApiContext extends WriteRootProvider {

    /** Returned by a handler that has already written its own (non-JSON) response. */
    Object HANDLED = new Object();

    /**
     * Shared response serialiser. {@code java.time} values are written as their ISO-8601
     * {@code toString()} rather than pulled in through a JSR-310 module: a DuckDB {@code DATE} column
     * reaches a response as a {@link java.time.LocalDate} (via {@code JdbcRows.toMaps}), and a bare
     * mapper fails those with {@code REQUIRE_HANDLERS_FOR_JAVA8_TIMES} — turning a dry-run or store
     * read that actually succeeded into a 500. Serialising at the edge keeps the row maps carrying
     * real temporals for every non-JSON caller, and keeps the SBOM free of another Jackson artifact.
     */
    ObjectMapper JSON = new ObjectMapper()
            .registerModule(new SimpleModule().addSerializer(Temporal.class, new ToStringSerializer()));

    // The per-exchange attribute NAMES, their scope and the identity helpers live in RequestAttrs (D-1 step 3: the auth
    // classes sit below this interface). These aliases and delegates keep every ApiContext.ATTR_* / ApiContext.attr(…)
    // call site compiling unchanged.
    String ATTR_CORRELATION_ID = RequestAttrs.ATTR_CORRELATION_ID;
    String ATTR_START_NANOS = RequestAttrs.ATTR_START_NANOS;
    String ATTR_SELF_PATH = RequestAttrs.ATTR_SELF_PATH;
    String ATTR_ERROR_CODE = RequestAttrs.ATTR_ERROR_CODE;
    String ATTR_IDEMPOTENCY_KEY = RequestAttrs.ATTR_IDEMPOTENCY_KEY;
    String ATTR_RAW_BODY = RequestAttrs.ATTR_RAW_BODY;
    String ATTR_CLIENT_IP = RequestAttrs.ATTR_CLIENT_IP;
    String ATTR_SUBJECT = RequestAttrs.ATTR_SUBJECT;
    String ATTR_SUBJECT_ISSUER = RequestAttrs.ATTR_SUBJECT_ISSUER;
    String ATTR_CAPABILITY = RequestAttrs.ATTR_CAPABILITY;
    String ATTR_RESOURCE_PERMISSIONS = RequestAttrs.ATTR_RESOURCE_PERMISSIONS;
    String ATTR_PAGINATION = RequestAttrs.ATTR_PAGINATION;
    String ATTR_POD_SCOPED = RequestAttrs.ATTR_POD_SCOPED;
    String ATTR_APPROVED_CHANGE = RequestAttrs.ATTR_APPROVED_CHANGE;
    String ATTR_AUDIT_ATTRS = RequestAttrs.ATTR_AUDIT_ATTRS;

    /** JSON bodies at or above this size are gzipped when the client sent {@code Accept-Encoding: gzip}. */
    int GZIP_MIN_BYTES = 1024;

    /**
     * True when this request arrived under the {@code /api/v1} prefix (v1 envelope semantics).
     *
     * <p><b>Derived from the request URI on every call, and deliberately not cached on the exchange.</b>
     * It used to read an {@code inspecto.v1} attribute stamped by {@code ControlApi.normalizePath}, which
     * is only correct while exchange attributes are private to the exchange — see the note on the
     * attribute block above. Where they resolve to the shared {@code HttpContext} map, the flag latched
     * TRUE for the life of the server after the first {@code /api/v1} request, and two things followed:
     * the API-5 guard in {@code ControlApi.routeDispatch} stopped retiring the unversioned surface, so a
     * browser deep link to an Angular route sharing a name with a route pattern ({@code /pipelines}) was
     * answered with API JSON instead of the SPA shell; and {@code /health} came back wrapped in a v1
     * envelope quoting the <em>previous</em> request's {@code links.self}.
     *
     * <p>The prefix is the whole input, so there is nothing to store. Keep it that way: a derived answer
     * cannot go stale, and this one is a pure function of a URI the caller cannot make ambiguous —
     * {@code /api/v1x} shares six characters with {@code /api/v1/} and is a different namespace.
     */
    static boolean v1(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        return path.equals("/api/v1") || path.startsWith("/api/v1/");
    }

    ConcurrentHashMap<HttpExchange, ConcurrentHashMap<String, Object>> REQUEST_SCOPES = RequestAttrs.REQUEST_SCOPES;

    /** See {@link RequestAttrs#attr(HttpExchange, String)}. */
    static Object attr(HttpExchange ex, String key) { return RequestAttrs.attr(ex, key); }

    /** See {@link RequestAttrs#attr(HttpExchange, String, Object)}. */
    static void attr(HttpExchange ex, String key, Object value) { RequestAttrs.attr(ex, key, value); }

    /** See {@link RequestAttrs#dropAttrScope}. */
    static void dropAttrScope(HttpExchange ex) { RequestAttrs.dropAttrScope(ex); }

    /** See {@link RequestAttrs#correlationId}. */
    static String correlationId(HttpExchange ex) { return RequestAttrs.correlationId(ex); }

    /** See {@link RequestAttrs#subject}. */
    static java.util.Optional<Subject> subject(HttpExchange ex) { return RequestAttrs.subject(ex); }

    /** AuthZ gate (W6): when a {@link Subject} is attached (Standard edition, authenticated request) it
     *  must carry {@code capability}, else {@code 403 PERMISSION_DENIED}. A no-op on Personal edition — no
     *  {@link Authenticator} is ever present there, so no {@link Subject} is ever attached and every route
     *  stays open, unchanged. */
    static void requireCapability(HttpExchange ex, String capability) {
        if (attr(ex, ATTR_SUBJECT) instanceof Subject s) {
            // Recorded on the exchange BEFORE the outcome is known, so both readers see it: on a 403,
            // AuditTrail.accessDenied stamps the capability that was MISSING; on a pass, AuditTrail.record
            // stamps the capability this write was PRIVILEGED by. Until 2026-09-15 the name lived only in
            // the exception message below and was gone before the audit event was built (compliance plan
            // step 4a/4b). ⚠ Deliberately inside the Subject branch: on Personal no check runs, so no
            // capability is stamped — an event claiming "privileged by X" on an edition that checks
            // nothing would tell an auditor something false.
            attr(ex, ATTR_CAPABILITY, capability);
            if (!s.capabilities().contains(capability))
                throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "missing capability '" + capability + "'");
        }
    }

    /** SEC-7(b): declare the capability set applicable to the single resource this response carries —
     *  design-of-record `docs/superpower/resource-permissions-design.md`. The v1 envelope then emits
     *  {@code permissions = subject grants ∩ applicable} (per-resource ∩ resource-state, §8) instead of
     *  the session-wide array. An affordance signal only — enforcement stays {@link #requireCapability}. */
    static void resourcePermissions(HttpExchange ex, java.util.Set<String> applicable) {
        attr(ex, ATTR_RESOURCE_PERMISSIONS, java.util.Set.copyOf(applicable));
    }

    /** Declare this list response's cursor-pagination block (api-contract-design §7): {@code cursor}
     *  echoes the request cursor (null = first page), {@code nextCursor} is the opaque token for the next
     *  page (null = last page). {@link Envelope} emits it under {@code metadata.pagination} on a v1
     *  response; a no-op for the legacy (unversioned) view, which stays byte-for-byte unchanged. */
    /**
     * Declare that this response describes only the <b>Pod</b> that answered, so a reader must not treat
     * it as a fleet-wide view ({@code POD-SCOPE-DIVERGENCE-1}). {@link Envelope} emits it as
     * {@code metadata.podScoped} on a v1 response; a no-op for the legacy (unversioned) view, which stays
     * byte-for-byte unchanged — the same contract {@link #pagination} keeps.
     *
     * <p>🔴 <b>This declares a divergence, it does not fix one.</b> The operator decision
     * (2026-09-12) was to keep the per-Pod behaviour and end the SILENCE about it: system-scope knobs must
     * still be set on every Pod, and the UI is what must union a roster across Pods
     * ({@code UI-POD-SCOPE-UNION-1}). ⛔ Declaring the scope without a consumer acting on it leaves the
     * operator exactly as misled, just with more JSON.
     */
    static void podScoped(HttpExchange ex) {
        attr(ex, ATTR_POD_SCOPED, Boolean.TRUE);
    }

    static void pagination(HttpExchange ex, String cursor, String nextCursor, int limit, long total) {
        java.util.Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("cursor", cursor);
        p.put("nextCursor", nextCursor);
        p.put("limit", limit);
        p.put("total", total);
        attr(ex, ATTR_PAGINATION, p);
    }

    /** Wrap {@code h} so it first runs the {@link #requireCapability} gate for {@code capability} — the
     *  one-line opt-in a write route uses to declare "this action needs X" (W6, guideline 13: capability
     *  verbs, never roles). Route registration otherwise unchanged. */
    /**
     * A handler that CARRIES the capability it demands (route-gating plan step 3a). It used to return a
     * bare lambda, which made the gate invisible to the router: the manifest test's regex over source was
     * the only inventory anyone had. A marked handler gives the running server its own inventory, which is
     * what {@code ControlApi.register} needs to refuse an undeclared mutating route at boot.
     */
    record Gated(String capability, RolesRootOf owner, Handler inner) implements Handler {
        Gated(String capability, Handler inner) { this(capability, null, inner); }

        @Override public Object handle(com.sun.net.httpserver.HttpExchange ex, java.util.regex.Matcher m)
                throws Exception {
            // The owner is resolved only when a Subject is attached: on Personal nothing is checked, so the
            // handler keeps answering its own 400/404/409 exactly as before.
            if (owner == null) requireCapability(ex, capability);
            else if (subject(ex).isPresent()) requireCapabilityIn(ex, owner.configRoot(ex, m), capability);
            return inner.handle(ex, m);
        }
    }

    static Handler withCapability(String capability, Handler h) {
        return new Gated(capability, h);
    }

    /**
     * The config root of the Space that OWNS what an installation-scope route acts on — whose role table,
     * not the bound Space's, decides the capability ({@code EXCHANGE-OWNING-SPACE-AUTHZ-1}). May throw the
     * route's own {@link ApiException} (400/404/409) when the owner cannot be resolved.
     */
    @FunctionalInterface
    interface RolesRootOf {
        Path configRoot(HttpExchange ex, java.util.regex.Matcher m) throws Exception;
    }

    /**
     * {@link #withCapability(String, Handler)} for an un-prefixed route that acts on ONE Space's resource
     * (the Exchange: a grant, an offer). Such a route binds to the default Space, so the attached
     * {@link Subject} carries the DEFAULT Space's grants; this gate re-derives the caller's grants under
     * {@code owner}'s role table instead and demands {@code capability} THERE.
     */
    static Handler withCapability(String capability, RolesRootOf owner, Handler h) {
        return new Gated(capability, java.util.Objects.requireNonNull(owner), h);
    }

    /**
     * AuthZ gate against a NAMED Space's role table: re-runs the active {@link Authenticator} on this
     * request's own credential with {@link Roles#ATTR_CONFIG_ROOT} pointed at {@code configRoot}, and
     * demands {@code capability} of the Subject that yields. A no-op when no Subject is attached
     * (Personal), like {@link #requireCapability}. Fail-closed: no config root, no authenticator, a
     * credential that no longer resolves, or a different identity → 403. The request's bound-Space
     * attributes ({@code ATTR_CONFIG_ROOT}, held roles) are restored afterwards.
     */
    static void requireCapabilityIn(HttpExchange ex, Path configRoot, String capability) {
        if (!(attr(ex, ATTR_SUBJECT) instanceof Subject bound)) return;
        attr(ex, ATTR_CAPABILITY, capability);
        Subject there = null;
        Authenticator a = Authenticators.active().orElse(null);
        if (configRoot != null && a != null) {
            Object priorRoot = attr(ex, Roles.ATTR_CONFIG_ROOT);
            Object priorHeld = attr(ex, ComponentAccess.ATTR_HELD_ROLES);
            try {
                Roles.configRoot(ex, configRoot);
                there = a.authenticate(ex).orElse(null);
            } finally {
                attr(ex, Roles.ATTR_CONFIG_ROOT, priorRoot);
                attr(ex, ComponentAccess.ATTR_HELD_ROLES, priorHeld);
            }
        }
        if (there == null || !there.id().equals(bound.id()) || !there.capabilities().contains(capability))
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED,
                    "missing capability '" + capability + "' in the owning space");
    }

    void get(String pattern, Handler h);

    void post(String pattern, Handler h);

    void put(String pattern, Handler h);

    void patch(String pattern, Handler h);

    void delete(String pattern, Handler h);

    /**
     * Whether a REAL handler owns {@code METHOD pattern} — the exact string a {@link RouteModule} passed, not a
     * path to match — <b>excluding</b> absent-module stubs registered through {@link #stub}. Added 2026-09-07
     * (EDG-01 cell 3b) so two things can be DERIVED from what actually registered rather than guessed from
     * the edition: the {@code /bootstrap} {@code features} flags, and the stubs themselves, which register
     * only where the module did not.
     *
     * <p>🔴 The exclusion is the whole point, learned the hard way: the first version counted stubs, so
     * {@code /bootstrap} reported {@code geoLink: true} on a Personal build because the 503 stub had claimed
     * the pattern. "A route exists" and "the feature is installed" are different questions.
     */
    boolean hasRoute(String method, String pattern);

    /** Feature ids declared by the route modules that registered ({@link RouteModule#featureIds()}). */
    default java.util.Set<String> registeredFeatures() {
        return java.util.Set.of();
    }

    /**
     * Register an <b>absent-module stub</b>: a handler that answers for an optional feature's path when the
     * feature's module is not on the classpath (typically a 503 that explains itself). It occupies the route
     * table like any handler — first-match dispatch, duplicate-refused — but does NOT make {@link #hasRoute}
     * true, so a capability flag derived from {@code hasRoute} stays honest. Register stubs LAST, after
     * {@code ServiceLoader} discovery, and only for patterns {@code hasRoute} reports unclaimed.
     *
     * @since 4.0.0
     */
    void stub(String method, String pattern, Handler h);

    /** Parse the request body as a JSON object map (an empty map when the body is empty). */
    Map<String, Object> body(HttpExchange ex) throws IOException;

    /** What an in-process {@link #replay} answered: the status the route sent and its body text. */
    record Replayed(int status, String body) {}

    /**
     * Re-run {@code METHOD path} (route-table form, no {@code /api/v1} prefix, an optional {@code ?query}) in-process with {@code body},
     * as the caller of {@code outer} — its Subject and bound Space — and capture the answer instead of
     * sending it (`ASSURE-MAKER-CHECKER-1`: approving a Pending Change applies it through the SAME route, so
     * every gate that route runs runs again). {@code attrs} are stamped on the replay's own request scope;
     * {@code headers} are the original request's headers worth keeping (its {@code If-Match}). The replay is
     * authorized (the PDP) and audited like a request; a route's refusal comes back as its status.
     */
    Replayed replay(HttpExchange outer, String method, String path, byte[] body, Map<String, String> headers,
                    Map<String, Object> attrs) throws Exception;

    /**
     * The request body's <b>raw bytes</b>, exactly as they arrived (D8).
     *
     * <p>Needed by any route that verifies a provider signature: real providers sign the raw payload, and
     * re-serialising a parsed {@code Map} would not reproduce it — key order and whitespace are not
     * preserved, so signatures would fail non-deterministically rather than never.
     *
     * <p>{@code ex.getRequestBody()} is a <b>single-read</b> stream, so the bytes are read once and cached
     * on the exchange ({@link #ATTR_RAW_BODY}); {@link #body} reads through this. That makes the two safe
     * to call in either order and in any combination — deliberately, because the failure mode of a
     * read-twice seam is a silently <i>empty</i> body rather than an exception. The returned array is the
     * cached one: <b>do not mutate it.</b>
     */
    byte[] rawBody(HttpExchange ex) throws IOException;

    /**
     * {@link #rawBody(HttpExchange)} with a hard cap: a body over {@code maxBytes} is
     * {@code 413 PAYLOAD_TOO_LARGE}, refused on its declared {@code Content-Length} before any byte is read,
     * or — for an undeclared (chunked) length — after reading at most {@code maxBytes + 1} bytes. For
     * unauthenticated routes, where nothing else stops a caller buffering an arbitrary body into the heap.
     */
    byte[] rawBody(HttpExchange ex, int maxBytes) throws IOException;

    // service(), sseStreams() and spaces() moved to HostContext (inspecto-processor): this SPI names no host class.

    /**
     * Registers {@code hook} to run once when this API closes, so a route module that owns a resource (a worker pool)
     * can release it with the server instead of leaking it. The default registers nothing - a context with no
     * lifecycle (a test double) simply never calls it. A hook that throws never stops the others.
     */
    default void onClose(Runnable hook) {}

    /** The configured write root, or {@code null} when filesystem writes are disabled. */
    Path writeRoot();

    /** The bound space's data directory (where partition stores live), or {@code null} if unavailable.
     *  Used by query execution to resolve a dataset's {@code physicalRef} to its at-rest Parquet (W4). */
    Path dataRoot();

    String HEADER_AGENT_SESSION = RequestAttrs.HEADER_AGENT_SESSION;

    /** See {@link RequestAttrs#actor}. */
    static String actor(HttpExchange ex) { return RequestAttrs.actor(ex); }

    /** See {@link RequestAttrs#actorType}. */
    static String actorType(HttpExchange ex) { return RequestAttrs.actorType(ex); }

    /** See {@link RequestAttrs#ip}. */
    static String ip(HttpExchange ex) { return RequestAttrs.ip(ex); }

    /** See {@link RequestAttrs#userAgent}. */
    static String userAgent(HttpExchange ex) { return RequestAttrs.userAgent(ex); }

    /**
     * Decode the {@code key} query-string parameter, or {@code null} if absent. Parses the RAW query and decodes once:
     * {@code getQuery()} is already percent-decoded, so decoding it again turned {@code %2B} into a space and split a
     * value on an encoded {@code %26}.
     */
    static String query(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return null;
        for (String kv : q.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(key))
                return URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    /** Null-safe, blank-as-null string field from a parsed body map. */
    static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return (v == null || v.toString().isBlank()) ? null : v.toString();
    }

    /** Extract the {@code sampleRows} array from a request body (each element a row map); empty if absent. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> sampleRows(Map<String, Object> body) {
        List<Map<String, Object>> sample = new ArrayList<>();
        if (body.get("sampleRows") instanceof List<?> rows) {
            for (Object o : rows) if (o instanceof Map<?, ?> r) sample.add((Map<String, Object>) r);
        }
        return sample;
    }

    /** Parse {@code s} as an int, or {@code def} when blank/non-numeric. */
    static int parseIntOr(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * Optional {@code ?limit=&offset=} slice over an already-in-memory list (ui-design-review R6a —
     * the pipeline/job registries are unbounded today). An absent/blank {@code limit} returns
     * {@code all} unchanged — byte-identical to the pre-existing behavior for every caller that
     * doesn't pass it. A negative {@code offset} clamps to 0; an offset past the end yields an empty list.
     */
    static <T> List<T> paged(List<T> all, HttpExchange ex) {
        String limitParam = query(ex, "limit");
        if (limitParam == null || limitParam.isBlank()) return all;
        int limit = Math.max(0, parseIntOr(limitParam, all.size()));
        int offset = Math.max(0, parseIntOr(query(ex, "offset"), 0));
        if (offset >= all.size()) return List.of();
        return all.subList(offset, Math.min(all.size(), offset + limit));
    }

    /** Write {@code body} as JSON with an explicit status (e.g. a 422 with a findings payload); returns
     *  {@link #HANDLED}. A {@code /api/v1} request gets the {@link Envelope} shaping (legacy bodies are
     *  byte-for-byte unchanged); bodies ≥ {@link #GZIP_MIN_BYTES} are gzipped when the client accepts it. */
    static Object respondJson(HttpExchange ex, int status, Object body) throws IOException {
        Object payload = v1(ex) ? Envelope.shape(ex, status, body) : body;
        byte[] bytes = JSON.writeValueAsBytes(payload);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        Idempotency.capture(ex, status, bytes);   // cache the pre-compression body for a keyed-write replay (W5)
        bytes = maybeGzip(ex, bytes);
        if ("HEAD".equals(ex.getRequestMethod())) {   // headers only: a body on HEAD makes the JDK warn, then the write throws
            ex.sendResponseHeaders(status, -1);
            return HANDLED;
        }
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        return HANDLED;
    }

    /** Gzip {@code bytes} (setting {@code Content-Encoding}) when large enough and the client
     *  negotiated it via {@code Accept-Encoding: gzip}; otherwise return them untouched. */
    private static byte[] maybeGzip(HttpExchange ex, byte[] bytes) throws IOException {
        if (bytes.length < GZIP_MIN_BYTES) return bytes;
        String accept = ex.getRequestHeaders().getFirst("Accept-Encoding");
        if (accept == null || !accept.toLowerCase(java.util.Locale.ROOT).contains("gzip")) return bytes;
        var out = new java.io.ByteArrayOutputStream(Math.max(64, bytes.length / 4));
        try (var gz = new java.util.zip.GZIPOutputStream(out)) {
            gz.write(bytes);
        }
        ex.getResponseHeaders().set("Content-Encoding", "gzip");
        return out.toByteArray();
    }

    /** Write {@code text} with an explicit {@code Content-Type} (e.g. {@code text/csv}); returns {@link #HANDLED}. */
    static Object respondText(HttpExchange ex, String text, String contentType) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        return HANDLED;
    }

    /**
     * Write {@code bytes} with an explicit {@code Content-Type} and a download filename; returns
     * {@link #HANDLED}. ⚠ Deliberately NOT gzipped: an xlsx is already a zip container, so a second
     * encoding costs CPU and saves nothing.
     */
    static Object respondBinary(HttpExchange ex, byte[] bytes, String contentType, String filename)
            throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        return HANDLED;
    }

    /** Decode the first captured path segment (the {@code id} in {@code /things/{id}}). */
    static String name(Matcher m) {
        return URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
    }

    /** Decode the {@code g}-th captured path segment (e.g. group 2 = the {@code id} in {@code /{type}/{id}}). */
    static String param(Matcher m, int g) {
        return URLDecoder.decode(m.group(g), StandardCharsets.UTF_8);
    }
}
