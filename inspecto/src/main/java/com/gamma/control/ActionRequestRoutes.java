package com.gamma.control;

import com.gamma.audit.EventLevel;
import com.gamma.objects.ObjectAccess;
import com.gamma.pipeline.exec.WebhookSink;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The Action Request surface ({@code ASSURE-ACTION-REQUESTS-1}) — see {@link ActionRequests} for the model and
 * {@link ActionDispatcher} for the sending.
 * <pre>
 *   GET  /action-requests[?status=&amp;incidentId=&amp;caseId=]  newest first (capped, truncated flag, true total)
 *   GET  /action-requests/{id}                             one, with the rendered payload the approver reads
 *   POST /action-requests                                  propose one from an Incident / Case → pending   (canWorkIncidents)
 *   POST /action-requests/{id}/approve                     four-eyes approve → dispatched               (canApproveChanges)
 *   POST /action-requests/{id}/decline                     four-eyes decline                            (canApproveChanges)
 *   POST /action-requests/{id}/retry                       re-dispatch a failed one, SAME idempotency key (canApproveChanges)
 *   POST /action-requests/{id}/mark-failed                 a request stuck in dispatched → failed, so it can be retried
 * </pre>
 *
 * <p><b>Create gates, in order</b>: {@code canWorkIncidents} (the route) → write root 503 → the body 422 (unknown
 * key, method, Connection id, payload template, idempotency key, reason, exactly-one linked Incident / Case) → no
 * operational objects (inspecto-ops) in this bundle 503 → the
 * linked object 404 → no outbound transport in this bundle 503 → the Connection does not resolve under the webhook
 * egress rules 422 → the rendered payload over {@link ActionRequests#MAX_PAYLOAD_BYTES} 413 → saved {@code pending}.
 *
 * <p><b>Decide gates, in order</b> (the Pending Change pattern): {@code canApproveChanges} (the route) → an
 * authenticated Subject 403 → write root 503 → a body key other than {@code reason} 422 → an unsafe id 422 → no such
 * request 404 → a record failing its integrity check 409 → not {@code pending} (incl. just expired) 409 → the
 * author deciding their own request 403. ALWAYS four-eyes — not a policy option. Retry: the same up to the status
 * check, which demands {@code failed}.
 *
 * <p><b>Not under the approval policy.</b> An Action Request is not config: it carries its own mandatory four-eyes
 * approval, so {@code ApprovalPolicy} governs no kind of it and {@code PendingChanges.hold} is never reached —
 * holding the proposal as a Pending Change as well would make one outbound call need two approvals.
 */
final class ActionRequestRoutes implements RouteModule {

    static final int LIST_CAP = 500;
    private static final Set<String> CREATE_KEYS = Set.of("connection", "method", "payloadTemplate",
            "idempotencyKey", "incidentId", "caseId", "reason", "context");
    private static final Set<String> DECIDE_KEYS = Set.of("reason");

    @Override
    public void register(ApiContext api) {
        api.get("/action-requests", (e, m) -> list(api, e));
        api.get("/action-requests/([^/]+)", (e, m) -> one(api, e, ApiContext.name(m)));
        api.post("/action-requests", ApiContext.withCapability("canWorkIncidents",
                (e, m) -> create(api, e, api.body(e))));
        api.post("/action-requests/([^/]+)/approve", ApiContext.withCapability("canApproveChanges",
                (e, m) -> decide(api, e, ApiContext.name(m), true, api.body(e))));
        api.post("/action-requests/([^/]+)/decline", ApiContext.withCapability("canApproveChanges",
                (e, m) -> decide(api, e, ApiContext.name(m), false, api.body(e))));
        api.post("/action-requests/([^/]+)/retry", ApiContext.withCapability("canApproveChanges",
                (e, m) -> retry(api, e, ApiContext.name(m), api.body(e))));
        api.post("/action-requests/([^/]+)/mark-failed", ApiContext.withCapability("canApproveChanges",
                (e, m) -> markFailed(api, e, ApiContext.name(m), api.body(e))));
    }

    // ── reads ───────────────────────────────────────────────────────────────────────────────────

    private Object list(ApiContext api, HttpExchange ex) throws IOException {
        requireReader(ex);
        Path root = api.writeRoot();
        String status = ApiContext.query(ex, "status");
        String incident = ApiContext.query(ex, "incidentId");
        String kase = ApiContext.query(ex, "caseId");
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> recs = new ArrayList<>();
        int total = 0;
        if (root != null) {
            synchronized (ActionRequests.lock()) {
                for (Map<String, Object> rec : ActionRequests.list(root)) {
                    ActionRequests.expireIfDue(root, rec);
                    if (blankOr(status, rec.get("status")) && blankOr(incident, rec.get("incidentId"))
                            && blankOr(kase, rec.get("caseId")) && visible(api, ex, rec)) {
                        total++;
                        if (items.size() < LIST_CAP) {
                            recs.add(rec);
                            items.add(redacted(ex, ActionRequests.summary(rec)));
                        }
                    }
                }
            }
            // outside the store lock, once per distinct maker set - not per item
            Map<Set<Object>, String> memo = new java.util.HashMap<>();
            for (int i = 0; i < recs.size(); i++) withApproverCheck(root, recs.get(i), items.get(i), memo);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total);
        out.put("truncated", total > items.size());
        return out;
    }

    private static boolean blankOr(String want, Object have) {
        return want == null || want.isBlank() || want.equals(have);
    }

    private Object one(ApiContext api, HttpExchange ex, String id) throws IOException {
        requireReader(ex);
        Path root = api.writeRoot();
        if (root == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no action request '" + id + "'");
        Map<String, Object> rec, view;
        synchronized (ActionRequests.lock()) {
            rec = ActionRequests.read(root, id);   // 422 on an unsafe id
            if (rec == null || !visible(api, ex, rec))
                throw new ApiException(404, ErrorCodes.NOT_FOUND, "no action request '" + id + "'");
            ActionRequests.expireIfDue(root, rec);
            view = redacted(ex, withEgress(root, ActionRequests.detail(rec)));
        }
        return withApproverCheck(root, rec, view, new java.util.HashMap<>());
    }

    /**
     * Verification finding 2 — reading needs {@code canWorkIncidents} OR {@code canApproveChanges}, checked
     * literally here because a manifest entry names one capability and this is an either-or. A no-op without a
     * Subject (Personal), like every capability gate.
     */
    private static void requireReader(HttpExchange ex) {
        Subject s = ApiContext.subject(ex).orElse(null);
        if (s != null && !s.capabilities().contains("canWorkIncidents") && !s.capabilities().contains("canApproveChanges"))
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "reading Action Requests needs canWorkIncidents "
                    + "or canApproveChanges");
    }

    /**
     * A request is visible exactly when its linked Incident / Case is — the object's data scope and row policy
     * ({@code AnnotationTargets.objectVisibleTo}). Invisible reads as absent (404), never 403. Without the ops module
     * there is no object to scope against, so nothing is visible; a record failing its integrity check (whose
     * linkage cannot be trusted) is visible only to an approver, who must see that it exists.
     */
    static boolean visible(ApiContext api, HttpExchange ex, Map<String, Object> rec) {
        if (ActionRequests.invalid(rec))
            return ApiContext.subject(ex).map(s -> s.capabilities().contains("canApproveChanges")).orElse(true);
        Object linked = rec.get("incidentId") != null ? rec.get("incidentId") : rec.get("caseId");
        ObjectAccess objects = HostContext.of(api).service().objects().orElse(null);
        if (linked == null || objects == null) return false;
        Map<String, Object> o = objects.summary(String.valueOf(linked)).orElse(null);
        return o != null && AnnotationTargets.objectVisibleTo(ex, o);
    }

    /** The target's response body is for approvers only: without {@code canApproveChanges} the excerpt is withheld. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> redacted(HttpExchange ex, Map<String, Object> view) {
        Subject s = ApiContext.subject(ex).orElse(null);
        if (s == null || s.capabilities().contains("canApproveChanges")) return view;
        if (view.get("lastResponse") instanceof Map<?, ?> last) {
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) last);
            copy.put("bodyExcerpt", null);
            copy.put("bodyRedacted", true);
            view.put("lastResponse", copy);
        }
        return view;
    }

    /**
     * The approver's view of where it goes (verification finding 1d): the host AS PARSED from the URL
     * ({@code URI.getHost()}), the port, the path, and whether the Space's egress allowlist names it — read live,
     * since the allowlist can change while the request waits.
     */
    static Map<String, Object> withEgress(Path root, Map<String, Object> view) {
        if (view.get("targetUrl") == null) return view;
        try {
            java.net.URI u = java.net.URI.create(String.valueOf(view.get("targetUrl")));
            String host = u.getHost() == null ? null : u.getHost().replaceAll("^\\[|\\]$", "");
            com.gamma.util.egress.EgressPolicy.Allowlist allow = EgressRoutes.allowlist(root);
            boolean listed = host != null && (allow.namesHost(host) || (com.gamma.util.egress.EgressPolicy.isIpLiteral(host)
                    && allow.cidrs().stream().anyMatch(c -> c.contains(java.net.InetAddress.ofLiteral(host)))));
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("scheme", u.getScheme());
            e.put("host", host);
            e.put("port", u.getPort() > 0 ? u.getPort() : 443);
            e.put("path", u.getRawPath());
            e.put("allowlisted", listed);
            view.put("egress", e);
        } catch (RuntimeException unparseable) {
            view.put("egress", Map.of("error", "the target URL does not parse"));
        }
        return view;
    }

    static final String NONE_ELIGIBLE = "none-eligible", UNKNOWN = "unknown", OK = "ok";

    /**
     * Whether anyone could approve this request (ASSURE-ACTION-REQUESTS-RESIDUALS-1 (6)). Never changes the
     * four-eyes decision — a {@code none-eligible} request stays {@code pending} (fail-closed), this only says so.
     * {@code none-eligible}: no role in the Space's table grants {@code canApproveChanges} (deny grants applied —
     * every edition), or the Authenticator enumerates its principals (Demo) and every holder is a maker.
     * {@code none-eligible} also with no Authenticator (Personal): no Subject exists, so no one can decide.
     * When roles grant it but the Authenticator cannot enumerate who holds them (OIDC), the Space's {@link ApproverRoster} answers — empty is
     * {@code none-eligible}, a group or a listed non-maker is {@code ok}.
     * {@code unknown}: enumerating failed, a tampered record, or every enumerated non-maker holder is data-scoped.
     * {@code ok}: an enumerated non-maker holds it.
     */
    static String approverCheck(Path root, Map<String, Object> rec) {
        return approverCheck(root, rec, new java.util.HashMap<>());
    }

    /** Last "approver check failed" log per root: at most one line per root per {@link #FAILURE_LOG_EVERY_MS}. */
    private static final java.util.concurrent.ConcurrentHashMap<Path, Long> CHECK_FAILURE_LOGGED =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long FAILURE_LOG_EVERY_MS = 10 * 60_000L;

    static String approverCheck(Path root, Map<String, Object> rec, Map<Set<Object>, String> memo) {
        if (ActionRequests.invalid(rec)) return UNKNOWN;   // its makers cannot be trusted
        Set<Object> makers = new java.util.HashSet<>();
        makers.add(rec.get("author"));
        if (rec.get("coAuthors") instanceof List<?> co) makers.addAll(co);
        return memo.computeIfAbsent(makers, m -> check(root, m, Roles.CAN_APPROVE_CHANGES, true));
    }

    /** {@link #compute} that never throws: an unreadable directory (a corrupt demo-users.toon) is "cannot tell". */
    static String check(Path root, Set<Object> makers, String capability, boolean needsVisible) {
        try {
            return compute(root, makers, capability, needsVisible);
        } catch (RuntimeException e) {
            long now = System.currentTimeMillis();
            Long last = CHECK_FAILURE_LOGGED.get(root);
            if ((last == null || now - last >= FAILURE_LOG_EVERY_MS)
                    && (last == null ? CHECK_FAILURE_LOGGED.putIfAbsent(root, now) == null
                                     : CHECK_FAILURE_LOGGED.replace(root, last, now)))
                org.slf4j.LoggerFactory.getLogger(ActionRequestRoutes.class)
                        .warn("approver check failed, reporting 'unknown': {}", e.toString());
            return UNKNOWN;
        }
    }

    /** {@code root} is the request's bound Space root: the one {@code ControlApi.dispatch} hands the Authenticator
     *  as {@code Roles.configRoot(ex)}, so the roles read here are the ones the approve gate's Subject was built from.
     *  {@code needsVisible}: deciding also needs the linked object visible to the approver (an Action Request does;
     *  a Pending Change's approve gate is the capability alone). */
    private static String compute(Path root, Set<Object> makers, String capability, boolean needsVisible) {
        // No Authenticator (Personal) => no Subject is ever attached => deciding is always 403.
        Authenticator auth = Authenticators.active().orElse(null);
        if (auth == null) return NONE_ELIGIBLE;
        boolean anyRole = Roles.effective(root).keySet().stream()
                .anyMatch(r -> JobAuthority.capabilitiesNow(List.of(r), root).contains(capability));
        if (!anyRole) return NONE_ELIGIBLE;
        Map<String, List<String>> who = auth.principals(root).orElse(null);
        // No principal directory (OIDC): the Space's approver roster decides (operator 2026-10-04, (6b)).
        if (who == null) return ApproverRoster.check(root, makers);
        // decide also needs visible(): the linked object must pass the approver's data scope and row policy, which
        // is not evaluable here without their request. So a scoped holder, or any authored Access Policy, reads unknown.
        // Only AUTHORED Access Policies count; the seeded space-isolation policies (inspecto-policy) do not. Safe today:
        // they engage only when an IdP 'space' claim is mapped, and the only Authenticator that enumerates principals
        // (so the only way to reach OK) is Demo sign-in, which carries no claims. Revisit if an enumerating IdP lands.
        boolean rowPolicies = !AccessPolicyStore.load(root).policies().isEmpty() || AccessPolicyStore.load(root).unreadable();
        Map<String, Roles.Def> defs = Roles.effective(root);
        boolean scopedHolder = false;
        for (Map.Entry<String, List<String>> p : who.entrySet()) {
            if (makers.contains(p.getKey())
                    || !JobAuthority.capabilitiesNow(p.getValue(), root).contains(capability)) continue;
            if (!needsVisible) return OK;
            boolean scoped = rowPolicies || p.getValue().stream().anyMatch(r -> r.startsWith("case:")
                    || (defs.get(r) != null && defs.get(r).dataScopes() != null));
            if (!scoped) return OK;
            scopedHolder = true;
        }
        return scopedHolder ? UNKNOWN : NONE_ELIGIBLE;
    }

    /** Adds {@code approverCheck} to a pending request's view (computed live: roles change while it waits). */
    static Map<String, Object> withApproverCheck(Path root, Map<String, Object> rec, Map<String, Object> view,
                                                 Map<Set<Object>, String> memo) {
        if (ActionRequests.PENDING.equals(rec.get("status"))) view.put("approverCheck", approverCheck(root, rec, memo));
        return view;
    }

    // ── create ──────────────────────────────────────────────────────────────────────────────────

    private Object create(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "action request");
        for (String k : body.keySet())
            if (!CREATE_KEYS.contains(k))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unknown key '" + k + "' — an action "
                        + "request takes " + new java.util.TreeSet<>(CREATE_KEYS));
        Map<String, Object> rec = propose(api, root, body, ApiContext.actor(ex), ApiContext.actorType(ex), "manual", List.of());
        return withApproverCheck(root, rec, withEgress(root, ActionRequests.detail(rec)), new java.util.HashMap<>());
    }

    /**
     * Validate, render and save one {@code pending} Action Request — the route's create and the Decision Rule
     * {@code invoke-api} consequence both come through here. Throws {@link ApiException} on every refusal.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> propose(ApiContext api, Path root, Map<String, Object> spec, String author,
                                       String authorType, String origin, List<String> coAuthors) throws IOException {
        String connection = ApiContext.str(spec, "connection");
        if (connection == null) throw invalid("'connection' (the id of an https Connection) is required");
        String method = String.valueOf(spec.getOrDefault("method", "POST")).toUpperCase(java.util.Locale.ROOT);
        if (!ActionRequests.METHODS.contains(method)) throw invalid("'method' must be one of " + ActionRequests.METHODS);
        Object template = spec.get("payloadTemplate");
        if (!(template instanceof Map<?, ?>)) throw invalid("'payloadTemplate' must be a JSON object");
        String key = ApiContext.str(spec, "idempotencyKey");
        if (key != null && !ActionRequests.SAFE_KEY.matcher(key).matches())
            throw invalid("'idempotencyKey' must match " + ActionRequests.SAFE_KEY.pattern());
        String reason = ApiContext.str(spec, "reason");
        if (reason != null && reason.length() > ActionRequests.MAX_REASON)
            throw invalid("'reason' is at most " + ActionRequests.MAX_REASON + " chars");
        Object ctx = spec.get("context");
        if (ctx != null && !(ctx instanceof Map<?, ?>)) throw invalid("'context' must be a JSON object");
        String incident = ApiContext.str(spec, "incidentId");
        String kase = ApiContext.str(spec, "caseId");
        if ((incident == null) == (kase == null))
            throw invalid("exactly one of 'incidentId' / 'caseId' is required — an Action Request is raised from one");

        ObjectAccess objects = HostContext.of(api).service().objects().orElse(null);
        if (objects == null)
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "operational objects (inspecto-ops) are not "
                    + "installed in this bundle, so there is no Incident or Case to raise an Action Request from");
        String linked = incident != null ? incident : kase;
        String kind = incident != null ? "incident" : "case";
        Optional<Map<String, Object>> obj = objects.summary(linked);
        if (obj.isEmpty() || !kind.equals(String.valueOf(obj.get().get("kind")).toLowerCase(java.util.Locale.ROOT)))
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no " + kind + " '" + linked + "'");

        if (ActionDispatcher.transport.get() == null)
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "this bundle ships no outbound HTTP "
                    + "transport — Action Requests are a Professional/Enterprise edition capability (inspecto-notify-channels)");
        WebhookSink.Endpoint endpoint;
        try {
            endpoint = WebhookSink.endpoint(connection, "action request");
        } catch (IllegalStateException refused) {
            throw invalid(refused.getMessage());
        }

        Map<String, Object> context = new LinkedHashMap<>();
        if (ctx instanceof Map<?, ?> m) context.put("context", m);
        if (incident != null) context.put("incident", Map.of("id", incident));
        if (kase != null) context.put("case", Map.of("id", kase));
        context.put("origin", origin);
        context.put("author", author);
        Map<String, Object> payload = (Map<String, Object>) ActionRequests.render(template, context);
        if (ApiContext.JSON.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8).length > ActionRequests.MAX_PAYLOAD_BYTES)
            throw new ApiException(413, ErrorCodes.PAYLOAD_TOO_LARGE, "the rendered payload is over "
                    + ActionRequests.MAX_PAYLOAD_BYTES + " bytes");

        Map<String, Object> rec = ActionRequests.draft(connection, endpoint.url().toString(), method, payload, key,
                incident, kase, origin, author, authorType, reason, ApprovalPolicy.forRoot(root).expiresAfterHours());
        rec.put("coAuthors", List.copyOf(coAuthors));   // the Decision Rule's makers — four-eyes excludes them too
        ActionRequests.transition(rec, ActionRequests.PENDING, author);
        synchronized (ActionRequests.lock()) {
            ActionRequests.save(root, rec);
        }
        ActionRequests.audit(author, authorType, "action-request.proposed", rec.get("id") + " proposed from " + kind
                + " " + linked + " → Connection '" + connection + "' (" + method + ")", rec);
        // once, at raise (reads never re-emit); approverCheck never throws, so a saved request is never 500'd
        if (NONE_ELIGIBLE.equals(approverCheck(root, rec)))
            ActionRequests.audit(author, authorType, "action-request.no-eligible-approver", rec.get("id")
                    + " has no eligible approver: no one holding canApproveChanges in this Space is outside its "
                    + "makers — it stays pending until it expires unless a role is granted", rec,
                    // Personal (no Authenticator) is none-eligible by construction — no one can ever decide there —
                    // so it is informational, not a warning on every raise
                    Authenticators.active().isPresent() ? EventLevel.WARN : EventLevel.INFO);
        return rec;
    }

    private static ApiException invalid(String message) {
        return new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, message);
    }

    // ── decide / retry ──────────────────────────────────────────────────────────────────────────

    /** The gates decide and retry share, up to the status check. Returns the record, under the store lock. */
    private static Map<String, Object> gated(ApiContext api, HttpExchange ex, Path root, String id, Map<String, Object> body) throws IOException {
        for (String k : body.keySet())
            if (!DECIDE_KEYS.contains(k))
                throw invalid("unknown key '" + k + "' — a decision carries only 'reason'; the target, method and "
                        + "payload are fixed when the request is created");
        String reason = ApiContext.str(body, "reason");
        if (reason != null && reason.length() > ActionRequests.MAX_REASON)
            throw invalid("'reason' is at most " + ActionRequests.MAX_REASON + " chars");
        Map<String, Object> rec = ActionRequests.read(root, id);   // 422 on an unsafe id
        if (rec == null || !visible(api, ex, rec))
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no action request '" + id + "'");
        if (ActionRequests.invalid(rec))
            throw new ApiException(409, ErrorCodes.CONFLICT, "action request '" + id + "' fails its integrity check "
                    + "(its MAC does not verify: it was not written by this server, or was edited since) — it cannot be "
                    + "decided or dispatched");
        ActionRequests.expireIfDue(root, rec);
        return rec;
    }

    private static Path subjectAndRoot(ApiContext api, HttpExchange ex, String what) {
        if (ApiContext.subject(ex).isEmpty())
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, what + " an action request needs an "
                    + "authenticated Subject — without one, the author and the approver cannot be told apart");
        return WriteGates.requireWriteRoot(api, "action request decision");
    }

    private Object decide(ApiContext api, HttpExchange ex, String id, boolean approve, Map<String, Object> body)
            throws IOException {
        Path root = subjectAndRoot(api, ex, approve ? "approving" : "declining");
        String by = ApiContext.actor(ex);
        Map<String, Object> rec;
        synchronized (ActionRequests.lock()) {
            rec = gated(api, ex, root, id, body);
            if (!ActionRequests.PENDING.equals(rec.get("status")))
                throw new ApiException(409, ErrorCodes.CONFLICT, "action request '" + id + "' is " + rec.get("status")
                        + ", not pending");
            if (by.equals(rec.get("author")))
                throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "four-eyes: '" + rec.get("author")
                        + "' proposed this action request and cannot " + (approve ? "approve" : "decline")
                        + " it — a different person must");
            if (rec.get("coAuthors") instanceof List<?> makers && makers.contains(by))
                throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "four-eyes: '" + by + "' edited the Decision "
                        + "Rule that raised this action request and cannot " + (approve ? "approve" : "decline")
                        + " it — a different person must");
            ApproverRoster.requireOnRoster(ex, root, (approve ? "approve" : "decline") + " an action request");
            rec.put(approve ? "approver" : "decidedBy", by);
            rec.put(approve ? "approvedAt" : "decidedAt", ActionRequests.now());
            rec.put("decisionReason", ApiContext.str(body, "reason"));
            ActionRequests.transition(rec, approve ? ActionRequests.APPROVED : ActionRequests.DECLINED, by);
            ActionRequests.save(root, rec);
        }
        ActionRequests.audit(by, ApiContext.actorType(ex), approve ? "action-request.approved" : "action-request.declined",
                id + (approve ? " approved" : " declined") + " by " + by, rec);
        if (approve) ActionDispatcher.submit(root, id);
        return current(root, id);
    }

    private Object retry(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        Path root = subjectAndRoot(api, ex, "retrying");
        String by = ApiContext.actor(ex);
        synchronized (ActionRequests.lock()) {
            Map<String, Object> rec = gated(api, ex, root, id, body);
            if (!ActionRequests.FAILED.equals(rec.get("status")))
                throw new ApiException(409, ErrorCodes.CONFLICT, "action request '" + id + "' is " + rec.get("status")
                        + " — only a failed request can be retried");
            ActionRequests.transition(rec, ActionRequests.DISPATCHED, by);
            rec.put("completedAt", null);
            ActionRequests.save(root, rec);
            ActionRequests.audit(by, ApiContext.actorType(ex), "action-request.retried", id + " retried by " + by, rec);
        }
        ActionDispatcher.submit(root, id);
        return current(root, id);
    }

    static final String PROP_STUCK_AFTER_MINUTES = "action.dispatch.stuckAfterMinutes";

    /** How long a request must sit in {@code dispatched} with no progress before an operator may mark it failed. */
    static long stuckAfterMinutes() {
        return Math.max(0L, Long.getLong(PROP_STUCK_AFTER_MINUTES, 15L));
    }

    /**
     * Verification finding 7 — a request left in {@code dispatched} (the process stopped mid-dispatch) is not
     * resumed at boot: nothing is ever re-sent automatically. An operator ({@code canApproveChanges}; not four-eyes —
     * this sends nothing and changes no content) moves it to {@code failed} once it has made no progress for
     * {@link #stuckAfterMinutes} (default 15, {@code -Daction.dispatch.stuckAfterMinutes}); a retry then re-sends it
     * under the SAME idempotency key, which is what lets a receiver drop a delivery it did get. Audited.
     */
    @SuppressWarnings("unchecked")
    private Object markFailed(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        Path root = subjectAndRoot(api, ex, "marking");
        String by = ApiContext.actor(ex);
        synchronized (ActionRequests.lock()) {
            Map<String, Object> rec = gated(api, ex, root, id, body);
            if (!ActionRequests.DISPATCHED.equals(rec.get("status")))
                throw new ApiException(409, ErrorCodes.CONFLICT, "action request '" + id + "' is " + rec.get("status")
                        + " — only a request stuck in dispatched can be marked failed");
            java.time.Instant last = java.time.Instant.EPOCH;
            if (rec.get("history") instanceof List<?> h && !h.isEmpty() && h.get(h.size() - 1) instanceof Map<?, ?> step)
                last = later(last, step.get("at"));
            if (rec.get("lastResponse") instanceof Map<?, ?> lr) last = later(last, lr.get("at"));
            long idle = java.time.Duration.between(last, java.time.Instant.now()).toMinutes();
            if (idle < stuckAfterMinutes())
                throw new ApiException(409, ErrorCodes.CONFLICT, "action request '" + id + "' last progressed " + idle
                        + " minute(s) ago — it can be marked failed after " + stuckAfterMinutes() + " minute(s) without progress");
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", null);
            resp.put("bodyExcerpt", null);
            resp.put("error", "marked failed by " + by + " after " + idle + " minute(s) stuck in dispatched — "
                    + "delivery was never confirmed; a retry re-sends it under the same idempotency key");
            resp.put("attempt", rec.get("attempts"));
            resp.put("at", ActionRequests.now());
            rec.put("lastResponse", resp);
            rec.put("decisionReason", ApiContext.str(body, "reason"));
            ActionRequests.transition(rec, ActionRequests.FAILED, by);
            rec.put("completedAt", ActionRequests.now());
            ActionRequests.save(root, rec);
            ActionRequests.audit(by, ApiContext.actorType(ex), "action-request.marked-failed",
                    id + " marked failed by " + by + " after " + idle + " minute(s) stuck in dispatched", rec);
        }
        return current(root, id);
    }

    private static java.time.Instant later(java.time.Instant a, Object at) {
        try {
            java.time.Instant b = java.time.Instant.parse(String.valueOf(at));
            return b.isAfter(a) ? b : a;
        } catch (RuntimeException unparseable) {
            return a;
        }
    }

    private static Map<String, Object> current(Path root, String id) throws IOException {
        synchronized (ActionRequests.lock()) {
            return withEgress(root, ActionRequests.detail(ActionRequests.read(root, id)));
        }
    }
}
