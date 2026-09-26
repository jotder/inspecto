package com.gamma.control;

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
        api.get("/action-requests/([^/]+)", (e, m) -> one(api, ApiContext.name(m)));
        api.post("/action-requests", ApiContext.withCapability("canWorkIncidents",
                (e, m) -> create(api, e, api.body(e))));
        api.post("/action-requests/([^/]+)/approve", ApiContext.withCapability("canApproveChanges",
                (e, m) -> decide(api, e, ApiContext.name(m), true, api.body(e))));
        api.post("/action-requests/([^/]+)/decline", ApiContext.withCapability("canApproveChanges",
                (e, m) -> decide(api, e, ApiContext.name(m), false, api.body(e))));
        api.post("/action-requests/([^/]+)/retry", ApiContext.withCapability("canApproveChanges",
                (e, m) -> retry(api, e, ApiContext.name(m), api.body(e))));
    }

    // ── reads ───────────────────────────────────────────────────────────────────────────────────

    private Object list(ApiContext api, HttpExchange ex) throws IOException {
        Path root = api.writeRoot();
        String status = ApiContext.query(ex, "status");
        String incident = ApiContext.query(ex, "incidentId");
        String kase = ApiContext.query(ex, "caseId");
        List<Map<String, Object>> items = new ArrayList<>();
        int total = 0;
        if (root != null) {
            synchronized (ActionRequests.lock()) {
                for (Map<String, Object> rec : ActionRequests.list(root)) {
                    ActionRequests.expireIfDue(root, rec);
                    if (blankOr(status, rec.get("status")) && blankOr(incident, rec.get("incidentId"))
                            && blankOr(kase, rec.get("caseId"))) {
                        total++;
                        if (items.size() < LIST_CAP) items.add(ActionRequests.summary(rec));
                    }
                }
            }
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

    private Object one(ApiContext api, String id) throws IOException {
        Path root = api.writeRoot();
        if (root == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no action request '" + id + "'");
        synchronized (ActionRequests.lock()) {
            Map<String, Object> rec = ActionRequests.read(root, id);   // 422 on an unsafe id
            if (rec == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no action request '" + id + "'");
            ActionRequests.expireIfDue(root, rec);
            return ActionRequests.detail(rec);
        }
    }

    // ── create ──────────────────────────────────────────────────────────────────────────────────

    private Object create(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "action request");
        for (String k : body.keySet())
            if (!CREATE_KEYS.contains(k))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unknown key '" + k + "' — an action "
                        + "request takes " + new java.util.TreeSet<>(CREATE_KEYS));
        return ActionRequests.detail(propose(api, root, body, ApiContext.actor(ex), ApiContext.actorType(ex), "manual"));
    }

    /**
     * Validate, render and save one {@code pending} Action Request — the route's create and the Decision Rule
     * {@code invoke-api} consequence both come through here. Throws {@link ApiException} on every refusal.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> propose(ApiContext api, Path root, Map<String, Object> spec, String author,
                                       String authorType, String origin) throws IOException {
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

        ObjectAccess objects = api.service().objects().orElse(null);
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
        ActionRequests.transition(rec, ActionRequests.PENDING, author);
        synchronized (ActionRequests.lock()) {
            ActionRequests.save(root, rec);
        }
        ActionRequests.audit(author, authorType, "action-request.proposed", rec.get("id") + " proposed from " + kind
                + " " + linked + " → Connection '" + connection + "' (" + method + ")", rec);
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
        if (rec == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no action request '" + id + "'");
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

    private static Map<String, Object> current(Path root, String id) throws IOException {
        synchronized (ActionRequests.lock()) {
            return ActionRequests.detail(ActionRequests.read(root, id));
        }
    }
}
