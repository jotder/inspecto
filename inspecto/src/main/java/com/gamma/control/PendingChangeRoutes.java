package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Pending Change inbox and the approval policy (`ASSURE-MAKER-CHECKER-1` S1/S3) — see {@link PendingChanges}
 * for the hold and the apply.
 * <pre>
 *   GET  /settings/approval                    the Space's per-kind policy ({approval, expiresAfterHours, governable})
 *   PUT  /settings/approval                    replace it — canAdminister, validated fail-closed (422)
 *   GET  /pending-changes[?status=&amp;kind=]      the Space's Pending Changes, newest first (capped, truncated flag)
 *   GET  /pending-changes/{id}                 one, with the content it replaces and the content it proposes
 *   GET  /pending-changes/{id}/diff            a line diff of the two (the Pipeline history diff)
 *   POST /pending-changes/{id}/approve         apply it through the route it was proposed through
 *   POST /pending-changes/{id}/decline         close it unapplied
 * </pre>
 *
 * <p><b>Decide gates, in order</b> (the Link Analysis four-eyes pattern, D-U7): {@code canApproveChanges} (the
 * route) → an authenticated Subject 403 (without one an author and an approver cannot be told apart) → write
 * root 503 → an unsafe id 422 → no such change 404 → already decided or expired 409 → the kind's
 * {@code approverCapability} 403 → with {@code fourEyes}, the author deciding their own change 403. Approve then
 * replays the original request ({@link ApiContext#replay}); the replay's own refusal comes back as its status
 * — a stale base is 409 and closes the change as {@code stale}, anything else leaves it pending.
 *
 * <p>⚠ Deliberately apart from {@code /agent/approvals*}, the AI-agent tool-call governance inbox: that one
 * decides whether an assistant may act; this one decides whether a human's config change lands.
 */
final class PendingChangeRoutes implements RouteModule {

    /** The most Pending Changes one list returns; {@code total} still reports the true count. */
    static final int LIST_CAP = 500;

    @Override
    public void register(ApiContext api) {
        api.get("/settings/approval", (e, m) -> readPolicy(api));
        // canAdminister, not canAuthorWorkbench: turning maker-checker off is the one change an author must
        // not be able to make — and it is not itself governable, or the policy could never be lifted.
        api.put("/settings/approval", ApiContext.withCapability("canAdminister",
                (e, m) -> writePolicy(api, api.body(e))));
        api.get("/pending-changes", (e, m) -> list(api, e));
        api.get("/pending-changes/([^/]+)/diff", (e, m) -> diff(api, e, ApiContext.name(m)));
        api.get("/pending-changes/([^/]+)", (e, m) -> one(api, e, ApiContext.name(m)));
        api.post("/pending-changes/([^/]+)/approve", ApiContext.withCapability("canApproveChanges",
                (e, m) -> decide(api, e, ApiContext.name(m), true, api.body(e))));
        api.post("/pending-changes/([^/]+)/decline", ApiContext.withCapability("canApproveChanges",
                (e, m) -> decide(api, e, ApiContext.name(m), false, api.body(e))));
    }

    // ── policy ──────────────────────────────────────────────────────────────────────────────────

    private Object readPolicy(ApiContext api) {
        ApprovalPolicy p = ApprovalPolicy.forRoot(api.writeRoot());
        Map<String, Object> out = p.toMap();
        out.put("failedClosed", p.failedClosed());
        out.put("governable", ApprovalPolicy.GOVERNABLE);
        out.put("defaultApproverCapability", ApprovalPolicy.DEFAULT_APPROVER);
        return out;
    }

    private Object writePolicy(ApiContext api, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "approval policy write");
        Map<String, Object> doc = new LinkedHashMap<>(body);
        ApprovalPolicy p;
        try {
            p = ApprovalPolicy.parse(doc, true);
        } catch (IllegalArgumentException refused) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, refused.getMessage());
        }
        p.write(root.resolve(ApprovalPolicy.FILE));
        return readPolicy(api);
    }

    // ── reads ───────────────────────────────────────────────────────────────────────────────────

    private Object list(ApiContext api, HttpExchange ex) throws IOException {
        Path root = api.writeRoot();
        String status = ApiContext.query(ex, "status");
        String kind = ApiContext.query(ex, "kind");
        List<Map<String, Object>> items = new ArrayList<>();
        int total = 0;
        if (root != null) {
            synchronized (PendingChanges.lock()) {
                for (Map<String, Object> rec : PendingChanges.list(root)) {
                    PendingChanges.expireIfDue(ex, root, rec);
                    if (status != null && !status.isBlank() && !status.equals(rec.get("status"))) continue;
                    if (kind != null && !kind.isBlank() && !kind.equals(rec.get("kind"))) continue;
                    total++;
                    if (items.size() < LIST_CAP) items.add(PendingChanges.summary(rec));
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total);
        out.put("truncated", total > items.size());
        return out;
    }

    private Object one(ApiContext api, HttpExchange ex, String id) throws IOException {
        Map<String, Object> rec = require(api, ex, id);
        Map<String, Object> out = new LinkedHashMap<>(rec);
        if (out.get("request") instanceof Map<?, ?> req) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("method", req.get("method"));
            r.put("path", req.get("path"));
            out.put("request", r);   // the raw body is the author's request, not the reviewer's business
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object diff(ApiContext api, HttpExchange ex, String id) throws IOException {
        Map<String, Object> rec = require(api, ex, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("kind", rec.get("kind"));
        out.put("name", rec.get("name"));
        out.put("operation", rec.get("operation"));
        out.putAll(PipelineHistory.diff(lines((Map<String, Object>) rec.get("current")),
                lines((Map<String, Object>) rec.get("proposed"))));
        return out;
    }

    /** The content as the TOON a file holds, line by line — the same text the Pipeline history diff compares. */
    private static List<String> lines(Map<String, Object> content) {
        return content == null ? List.of() : ConfigCodec.toToon(content).lines().toList();
    }

    private static Map<String, Object> require(ApiContext api, HttpExchange ex, String id) throws IOException {
        Path root = api.writeRoot();
        if (root == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no pending change '" + id + "'");
        synchronized (PendingChanges.lock()) {
            Map<String, Object> rec = PendingChanges.read(root, id);
            if (rec == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no pending change '" + id + "'");
            PendingChanges.expireIfDue(ex, root, rec);
            return rec;
        }
    }

    // ── decide ──────────────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Object decide(ApiContext api, HttpExchange ex, String id, boolean approve, Map<String, Object> body)
            throws Exception {
        Optional<Subject> subject = ApiContext.subject(ex);
        if (subject.isEmpty())
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "approving a change needs an authenticated "
                    + "Subject — without one, the author and the approver cannot be told apart");
        Path root = WriteGates.requireWriteRoot(api, "pending change decision");
        String reason = ApiContext.str(body, "reason");
        if (reason != null && reason.length() > PendingChanges.MAX_REASON)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'reason' is at most " + PendingChanges.MAX_REASON + " chars");
        synchronized (PendingChanges.lock()) {
            Map<String, Object> rec = PendingChanges.read(root, id);   // 422 on an unsafe id
            if (rec == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no pending change '" + id + "'");
            PendingChanges.expireIfDue(ex, root, rec);
            if (!"pending".equals(rec.get("status")))
                throw new ApiException(409, ErrorCodes.CONFLICT, "pending change '" + id + "' is already " + rec.get("status"));
            ApiContext.requireCapability(ex, String.valueOf(rec.get("approverCapability")));
            String by = ApiContext.actor(ex);
            if (Boolean.TRUE.equals(rec.get("fourEyes")) && by.equals(rec.get("author")))
                throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "four-eyes: '" + rec.get("author")
                        + "' proposed this change and cannot " + (approve ? "approve" : "decline") + " it — a different person must");
            String at = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();

            if (!approve) {
                rec.put("status", "declined");
                rec.put("decidedBy", by);
                rec.put("decidedAt", at);
                rec.put("decisionReason", reason);
                PendingChanges.save(root, rec);
                PendingChanges.audit(ex, "pending-change.declined", rec.get("kind") + " '" + rec.get("name")
                        + "' — " + id + " declined by " + by, rec, b -> b.attr("reason", reason));
                return Map.of("pendingChange", PendingChanges.summary(rec), "applied", false);
            }

            Map<String, Object> request = (Map<String, Object>) rec.get("request");
            Map<String, Object> marker = new LinkedHashMap<>(rec);
            marker.put("approvedBy", by);   // AuditTrail stamps it on the replayed write beside the author (actor)
            Map<String, String> headers = new LinkedHashMap<>();
            if (request.get("headers") instanceof Map<?, ?> h) h.forEach((k, v) -> headers.put(String.valueOf(k), String.valueOf(v)));
            // D-P13: the write is the AUTHOR's, re-checked as they stand now — the approver needed only the
            // approver capability above. The replay runs as the author; their recorded roles ride with them.
            Path rolesRoot = Roles.configRoot(ex) != null ? Roles.configRoot(ex) : root;
            Subject author = PendingChanges.authorNow(rec, rolesRoot);
            Map<String, Object> replayAttrs = new LinkedHashMap<>();
            replayAttrs.put(ApiContext.ATTR_APPROVED_CHANGE, marker);
            replayAttrs.put(ApiContext.ATTR_SUBJECT, author);
            replayAttrs.put(ComponentAccess.ATTR_HELD_ROLES, java.util.Set.copyOf(
                    rec.get("authorRoles") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.<String>of()));
            ApiContext.Replayed r = api.replay(ex, String.valueOf(request.get("method")), String.valueOf(request.get("path")),
                    String.valueOf(request.get("body")).getBytes(StandardCharsets.UTF_8), headers, replayAttrs);
            boolean applied = r.status() >= 200 && r.status() < 300 && "verified".equals(marker.get("outcome"));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", r.status());
            result.put("body", parse(r.body()));
            if (applied) {
                rec.put("status", "approved");
                rec.put("decidedBy", by);
                rec.put("decidedAt", at);
                rec.put("decisionReason", reason);
                rec.put("appliedStatus", r.status());
                PendingChanges.save(root, rec);
                PendingChanges.audit(ex, "pending-change.approved", rec.get("kind") + " '" + rec.get("name")
                        + "' — " + id + " approved by " + by + " and applied", rec, b -> b.attr("reason", reason));
                return Map.of("pendingChange", PendingChanges.summary(rec), "applied", true, "result", result);
            }
            // Stale: the hold's base check, or the route's own If-Match gate (it runs first when the author
            // sent one) — either way the target moved after the author read it, so this change can never land.
            if ("stale".equals(marker.get("outcome"))
                    || ErrorCodes.CONFLICT_STALE_VERSION.equals(errorField(r.body(), "errorCode"))) {
                rec.put("status", "stale");
                rec.put("decidedBy", by);
                rec.put("decidedAt", at);
                PendingChanges.save(root, rec);
                PendingChanges.audit(ex, "pending-change.stale", rec.get("kind") + " '" + rec.get("name") + "' — " + id
                        + " could not be applied: its base version is no longer current", rec);
            } else {
                PendingChanges.audit(ex, "pending-change.apply-refused", rec.get("kind") + " '" + rec.get("name")
                        + "' — " + id + " approval refused by its route (" + r.status() + ")", rec,
                        b -> b.attr("status", r.status()));
            }
            int status = r.status() >= 200 && r.status() < 300 ? 409 : r.status();
            String message = errorField(r.body(), "message");
            if (r.status() == 403 && message != null)
                message = "the author '" + rec.get("author") + "' no longer holds what this change needs (" + message
                        + ") — the change is applied as its author, re-checked now";
            throw new ApiException(status, status == 409 ? ErrorCodes.CONFLICT : errorField(r.body(), "errorCode"),
                    "pending change '" + id + "' was not applied: " + (message == null
                            ? "its route answered " + r.status() + " without reaching the approved write" : message));
        }
    }

    private static Object parse(String body) {
        try {
            return ApiContext.JSON.readValue(body, Object.class);
        } catch (IOException | RuntimeException notJson) {
            return body;
        }
    }

    /** A field of a v1 error body ({@code {error: {errorCode, message, …}}}), or {@code null}. */
    private static String errorField(String body, String field) {
        if (!(parse(body) instanceof Map<?, ?> m) || !(m.get("error") instanceof Map<?, ?> e)) return null;
        return e.get(field) == null ? null : String.valueOf(e.get(field));
    }
}
