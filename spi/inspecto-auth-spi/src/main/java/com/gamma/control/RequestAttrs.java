package com.gamma.control;

import com.sun.net.httpserver.HttpExchange;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The request-scoped attribute names, their per-request storage, and the identity helpers that read them —
 * the part of the old {@code ApiContext} static surface that the auth classes ({@link Subject}, {@code Roles},
 * {@code ComponentAccess}, {@code RowScope}, {@code AccessDecider}, {@code AuditTrail}…) stand on.
 *
 * <p>Extracted in D-1 step 3 so those classes no longer name {@code ApiContext}: that interface references them
 * ({@code Subject}, {@code Roles}, {@code ComponentAccess}, {@code Authenticator}) and a back-reference made the two
 * halves of the contract impossible to split into separate modules. {@code ApiContext} keeps one-line delegates and
 * aliases, so every existing {@code ApiContext.attr(…)} / {@code ApiContext.ATTR_*} call site is unchanged.
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public final class RequestAttrs {
    private RequestAttrs() {}

    // ── per-exchange attributes set by ControlApi.dispatch (v1 transport spine, v4.8.0) ─────────
    //
    // ⚠ "Per-exchange" is a JDK-VERSION-DEPENDENT promise, not a guarantee. `ExchangeImpl` picks its
    // attribute map like this:
    //
    //     private static final boolean perExchangeAttributes =
    //         !System.getProperty("jdk.httpserver.attributes", "").equals("context");
    //     ...
    //     this.attributes = perExchangeAttributes ? new ConcurrentHashMap<>()
    //                                             : getHttpContext().getAttributes();
    //
    // So the map is private to the exchange only by DEFAULT and only on a JDK new enough to have that
    // switch. Where it resolves to the HttpContext's map, every attribute below is shared by every
    // request the server ever handles — ControlApi serves everything from one createContext("/") — and a
    // value stamped by one request is readable by the next. Anything that must not leak across requests
    // therefore has to be either derived from the request itself (see #v1) or cleared at dispatch —
    // every constant below is on ControlApi.REQUEST_SCOPED_ATTRS, cleared as dispatch's first act.
    /** The request's correlation id (caller-supplied {@code Correlation-ID} header, or issued). */
    public static final String ATTR_CORRELATION_ID = "inspecto.correlationId";
    /** {@code System.nanoTime()} at dispatch start (v1 {@code metadata.durationMs}). */
    public static final String ATTR_START_NANOS    = "inspecto.startNanos";
    /** The original request path incl. the {@code /api/v1} prefix (v1 {@code links.self}). */
    public static final String ATTR_SELF_PATH      = "inspecto.selfPath";
    /** A specific {@code ErrorCodes} value chosen by the throwing site (else derived from status). */
    public static final String ATTR_ERROR_CODE     = "inspecto.errorCode";
    /** The {@code Idempotency.Pending} marker for this exchange (present only for a keyed write that missed). */
    public static final String ATTR_IDEMPOTENCY_KEY   = "inspecto.idempotency.key";
    /** The request body's raw bytes, cached because {@code ex.getRequestBody()} is single-read (D8). */
    public static final String ATTR_RAW_BODY          = "inspecto.rawBody";
    /** The client IP resolved against the trusted-proxy list at dispatch start ({@code #ip}, SEC review F3). */
    public static final String ATTR_CLIENT_IP         = "inspecto.clientIp";
    /** The authenticated {@code Subject} (W6), set by {@code ControlApi#dispatch} once an
     *  {@code Authenticator} validates the request; absent on Personal edition (no Authenticator present)
     *  and on the public bootstrap/health surface. */
    public static final String ATTR_SUBJECT           = "inspecto.subject";
    /** The issuer ({@code iss}) of the token the {@code Subject} was verified from, stamped by the Authenticator. */
    public static final String ATTR_SUBJECT_ISSUER    = "inspecto.subject.issuer";
    /** The capability {@code #requireCapability} CHECKED on this exchange — set only when a Subject was
     *  attached, i.e. only when a check actually ran. Read by {@code AuditTrail}: on a 403 it is the
     *  capability that was missing, on a permitted write the one the write was privileged by. Absent on
     *  Personal (nothing is checked there) and on any route with no gate (compliance plan step 4a/4b). */
    public static final String ATTR_CAPABILITY        = "inspecto.capability";
    /** SEC-7(b): the capability set applicable to the single resource this response carries, declared by
     *  the route via {@code #resourcePermissions}; {@code Envelope} intersects it with the Subject's
     *  session grants. Absent ⇒ the envelope keeps the session-wide array (lists, un-migrated routes). */
    public static final String ATTR_RESOURCE_PERMISSIONS = "inspecto.resourcePermissions";
    /** Cursor pagination (api-contract-design §7): a list route's paging block, declared via
     *  {@code #pagination}; {@code Envelope} emits it under {@code metadata.pagination}. Absent ⇒ no block. */
    public static final String ATTR_PAGINATION = "inspecto.pagination";
    /** Pod-scoped response (`POD-SCOPE-DIVERGENCE-1`): this payload describes only the Pod that answered,
     *  declared via {@code #podScoped}; {@code Envelope} emits it as {@code metadata.podScoped}. */
    public static final String ATTR_POD_SCOPED = "inspecto.podScoped";
    /** The Pending Change an approval is applying (`ASSURE-MAKER-CHECKER-1`): stamped ONLY on the in-process
     *  replay {@code #replay} runs, where {@code PendingChanges.hold} reads it as "this write was approved —
     *  verify it is the one that was, then let it through". Absent on every request a client sends. */
    public static final String ATTR_APPROVED_CHANGE = "inspecto.approvedChange";
    /** Extra {@code Map<String,Object>} attributes a handler adds to its request's AUDIT row (push ingest
     *  records the record count and byte size here — never the payload). */
    public static final String ATTR_AUDIT_ATTRS = "inspecto.auditAttrs";

    // ── the per-exchange attribute SCOPE — the storage behind every ATTR_* above ────────────────
    //
    // SEC-EXCHANGE-ATTRS, closed for real 2026-08-19: request-scoped values are NEVER stored via
    // HttpExchange.set/getAttribute. On any pre-JDK-26 runtime that map is the shared HttpContext map
    // (see the block comment above), and clearing it at dispatch start only fixed the SEQUENTIAL leak —
    // two requests IN FLIGHT still raced on it. Observed shipping on the bundle's GraalVM 25 runtime:
    // 53 of 1200 concurrent static-asset responses carried ANOTHER request's file, because the
    // effective path itself rode that map (request B read request A's path between A's setPath and A's
    // serveStatic). The same race crossed ATTR_RAW_BODY (a handler reading another request's body) and,
    // under auth, ATTR_SUBJECT/ATTR_HELD_ROLES (another request's identity).
    //
    // This map is keyed by exchange IDENTITY (HttpExchange does not override equals), so a scope is
    // per-request by construction on every runtime. Writes create the scope lazily; a null value is a
    // remove (matching the old setAttribute(k, null) idiom); ControlApi's correlation stage — the
    // outermost pipeline stage — drops the whole scope in its finally, so entries cannot outlive their
    // request. Do not "simplify" back to exchange attributes: the JDK's map choice is a static final
    // read at class-init, invisible to every test run on JDK 26.
    public static final ConcurrentHashMap<HttpExchange, ConcurrentHashMap<String, Object>> REQUEST_SCOPES = new ConcurrentHashMap<>();

    /** The request-scoped value stamped under {@code key}, or {@code null}. Never reads the JDK map. */
    public static Object attr(HttpExchange ex, String key) {
        ConcurrentHashMap<String, Object> scope = REQUEST_SCOPES.get(ex);
        return scope == null ? null : scope.get(key);
    }

    /** Stamp a request-scoped value ({@code null} removes). Never writes the JDK map. */
    public static void attr(HttpExchange ex, String key, Object value) {
        if (value == null) {
            ConcurrentHashMap<String, Object> scope = REQUEST_SCOPES.get(ex);
            if (scope != null) scope.remove(key);
        } else {
            REQUEST_SCOPES.computeIfAbsent(ex, x -> new ConcurrentHashMap<>()).put(key, value);
        }
    }

    /** Drop the whole scope — the outermost stage's finally. After this the request left no trace. */
    public static void dropAttrScope(HttpExchange ex) { REQUEST_SCOPES.remove(ex); }

    /** The request's correlation id (set by dispatch on every request), or {@code null} pre-dispatch. */
    public static String correlationId(HttpExchange ex) {
        Object v = attr(ex, ATTR_CORRELATION_ID);
        return v == null ? null : v.toString();
    }

    /** The authenticated {@code Subject}, when {@code ControlApi#dispatch} resolved one for this request
     *  (W6: Standard edition, security module present). Empty on Personal edition. */
    public static java.util.Optional<Subject> subject(HttpExchange ex) {
        return attr(ex, ATTR_SUBJECT) instanceof Subject s ? java.util.Optional.of(s) : java.util.Optional.empty();
    }

    /** Request header set by the client-side agent surface (S6) when a mutating call executes a
     *  human-confirmed agent/decision proposal — e.g. the A2UI {@code invoke} confirm-then-apply flow —
     *  so the audit trail can attribute it to the agent session rather than the browsing human. Additive
     *  only: absent on every existing caller, so the default (human) path is unchanged. */
    public static final String HEADER_AGENT_SESSION = "X-Agent-Session";

    /** The acting identity for the audit trail. When the security module authenticated this request
     *  (W6), the resolved {@code Subject}'s id is authoritative. Otherwise, when the request carries
     *  {@code #HEADER_AGENT_SESSION} (S6 — an agent-confirmed apply), the actor is {@code agent:<sessionId>}.
     *  Otherwise (Personal edition, or a public route no {@code Authenticator} ran on) the actor is the
     *  caller-supplied {@code X-Actor} header, defaulting to {@code appUser} — the historic auth-free
     *  behaviour, unchanged. On Standard/Enterprise the header never reaches here: {@code ControlApi}'s
     *  authenticate stage rejects any {@code X-Actor} outright (SEC-7a spoof guard), so the actor is
     *  always the authenticated {@code Subject}. */
    public static String actor(HttpExchange ex) {
        if (attr(ex, ATTR_SUBJECT) instanceof Subject s) return s.id();
        String agentSession = ex.getRequestHeaders().getFirst(HEADER_AGENT_SESSION);
        if (agentSession != null && !agentSession.isBlank()) return "agent:" + agentSession.trim();
        String a = ex.getRequestHeaders().getFirst("X-Actor");
        return (a == null || a.isBlank()) ? "appUser" : a.trim();
    }

    /** The audit {@code actorType} for this request: {@code "agent"} when {@code #HEADER_AGENT_SESSION}
     *  is present (and no authenticated human {@code Subject} overrides it), else the historic
     *  {@code "user"}. Additive-only companion to {@code #actor} — see there for precedence. */
    public static String actorType(HttpExchange ex) {
        if (attr(ex, ATTR_SUBJECT) instanceof Subject) return "user";
        String agentSession = ex.getRequestHeaders().getFirst(HEADER_AGENT_SESSION);
        return (agentSession != null && !agentSession.isBlank()) ? "agent" : "user";
    }

    /** The originating client IP, as resolved once per request by {@code ControlApi} against
     *  {@code -Dcontrol.trustedProxies} ({@code TrustedProxies}, SEC review F3): {@code X-Forwarded-For} is
     *  believed only from a trusted direct peer, right-most untrusted hop first. Without that stamp (an
     *  exchange that never went through the pipeline) it is the socket peer — never a header value.
     *  {@code null} only if the address is somehow unavailable. */
    public static String ip(HttpExchange ex) {
        if (attr(ex, ATTR_CLIENT_IP) instanceof String stamped) return stamped;
        var addr = ex.getRemoteAddress();
        return addr == null || addr.getAddress() == null ? null : addr.getAddress().getHostAddress();
    }

    /** The request {@code User-Agent}, or {@code null} if absent. */
    public static String userAgent(HttpExchange ex) {
        return ex.getRequestHeaders().getFirst("User-Agent");
    }
}
