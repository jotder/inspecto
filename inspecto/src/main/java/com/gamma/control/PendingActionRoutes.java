package com.gamma.control;

import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.access.WriteGates;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.auth.Subject;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
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
 * The Pending Action routes (AGENT-KIT-1 AK-2d, {@link PendingActions}): list and read them, approve or decline one
 * ({@code canApproveChanges}, four eyes, the approver roster — as a Pending Change, operator 2026-11-25), and withdraw
 * one's own. Approval replays the recorded request as the machine that asked ({@link PendingChanges#authorNow}), and only
 * when it still names an {@link AgentGate.Bucket#ACTION} route. An agent never decides one ({@link AgentGate} refuses
 * every {@code …/approve} and {@code …/decline}; this route refuses too).
 */
final class PendingActionRoutes implements RouteModule {

    static final int LIST_CAP = 500;

    @Override
    public void register(ApiContext api) {
        api.get("/pending-actions", (e, m) -> list(api, e));
        api.get("/pending-actions/([^/]+)", (e, m) -> one(api, e, ApiContext.name(m)));
        api.post("/pending-actions/([^/]+)/approve", ApiContext.withCapability("canApproveChanges",
                (e, m) -> decide(api, e, ApiContext.name(m), true, api.body(e))));
        api.post("/pending-actions/([^/]+)/decline", ApiContext.withCapability("canApproveChanges",
                (e, m) -> decide(api, e, ApiContext.name(m), false, api.body(e))));
        // Author-only, so no capability wrap: the handler's author check is the gate (CapabilityManifest EXEMPTIONS).
        api.post("/pending-actions/([^/]+)/withdraw", (e, m) -> withdraw(api, e, ApiContext.name(m), api.body(e)));
    }

    private Object list(ApiContext api, HttpExchange ex) throws IOException {
        Path root = api.writeRoot();
        String status = ApiContext.query(ex, "status");
        List<Map<String, Object>> items = new ArrayList<>();
        int[] n = {0};
        if (root != null) {
            Map<List<Object>, String> memo = new java.util.HashMap<>();
            PendingChanges.underStoreLock(root, () -> {
                for (Map<String, Object> rec : PendingActions.list(root)) {
                    PendingActions.expireIfDue(ex, root, rec);
                    if (status != null && !status.isBlank() && !status.equals(rec.get("status"))) continue;
                    n[0]++;
                    if (items.size() < LIST_CAP)
                        items.add(PendingChangeRoutes.withApproverCheck(root, rec, PendingActions.summary(rec), memo));
                }
                return null;
            });
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", n[0]);
        out.put("truncated", n[0] > items.size());
        return out;
    }

    private Object one(ApiContext api, HttpExchange ex, String id) throws IOException {
        Path root = api.writeRoot();
        if (root == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no pending action '" + id + "'");
        Map<String, Object> rec = PendingChanges.underStoreLock(root, () -> {
            Map<String, Object> r = PendingActions.read(root, id);
            if (r == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no pending action '" + id + "'");
            PendingActions.expireIfDue(ex, root, r);
            return r;
        });
        // The whole request — query and body included — is what an approval replays, so the approver sees all of it: a
        // drain or reprocess names its batch in the body, a trigger its params, and the query can carry ?dryRun= or
        // ?actor= (phase-2 security review: the approver decided on the action line alone).
        Map<String, Object> out = new LinkedHashMap<>(rec);
        return PendingChangeRoutes.withApproverCheck(root, rec, out, new java.util.HashMap<>());
    }

    @SuppressWarnings("unchecked")
    private Object decide(ApiContext api, HttpExchange ex, String id, boolean approve, Map<String, Object> body)
            throws Exception {
        Optional<Subject> subject = ApiContext.subject(ex);
        if (subject.isEmpty())
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "deciding an action needs an authenticated "
                    + "Subject — without one, the machine that asked and the approver cannot be told apart");
        if (subject.get().agent())
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "an agent client ('" + subject.get().agentClient()
                    + "') never approves or declines an action — a person must, signed in without the agent client");
        Path root = WriteGates.requireWriteRoot(api, "pending action decision");
        String reason = ApiContext.str(body, "reason");
        if (reason != null && reason.length() > PendingChanges.MAX_REASON)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'reason' is at most " + PendingChanges.MAX_REASON + " chars");
        // As a Pending Change: read → still pending? → decide → save, with the replay inside the store lock, so a second
        // decider blocks, re-reads the winner's record and gets 409 — an action never runs twice from one approval.
        return PendingChanges.<Object, Exception>underStoreLock(root, () -> {
            Map<String, Object> rec = PendingActions.read(root, id);   // 422 on an unsafe id
            if (rec == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no pending action '" + id + "'");
            if (PendingActions.invalid(rec))
                throw new ApiException(409, ErrorCodes.CONFLICT, "pending action '" + id + "' fails its integrity check "
                        + "(its MAC does not verify: it was not written by this server, or was edited since) — it cannot be decided");
            PendingActions.expireIfDue(ex, root, rec);
            if (!"pending".equals(rec.get("status")))
                throw new ApiException(409, ErrorCodes.CONFLICT, "pending action '" + id + "' is already " + rec.get("status")
                        + (rec.get("decidedBy") == null ? "" : " (decided by " + rec.get("decidedBy") + " at " + rec.get("decidedAt") + ")"));
            ApiContext.requireCapability(ex, String.valueOf(rec.get("approverCapability")));
            String by = ApiContext.actor(ex);
            if (Boolean.TRUE.equals(rec.get("fourEyes")) && by.equals(rec.get("author")))
                throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "four-eyes: '" + rec.get("author")
                        + "' asked for this action and cannot " + (approve ? "approve" : "decline") + " it — a different person must");
            ApproverRoster.requireOnRoster(ex, root, (approve ? "approve" : "decline") + " a pending action");
            String at = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
            rec.put("decidedBy", by);
            rec.put("decidedAt", at);
            rec.put("decisionReason", reason);

            if (!approve) {
                rec.put("status", "declined");
                PendingActions.save(root, rec);
                PendingActions.audit(ex, "pending-action.declined", rec.get("action") + " — " + id + " declined by " + by,
                        rec, b -> b.attr("reason", reason));
                return Map.of("pendingAction", PendingActions.summary(rec), "ran", false);
            }
            // Checked BEFORE anything is dispatched: a record naming any route but an action can never run.
            if (!PendingActions.actionRoute(rec))
                throw new ApiException(409, ErrorCodes.CONFLICT, "pending action '" + id + "' names a request ("
                        + rec.get("route") + ") that is not an operational action — nothing was dispatched");
            Map<String, Object> request = (Map<String, Object>) rec.get("request");
            Path rolesRoot = Roles.configRoot(ex) != null ? Roles.configRoot(ex) : root;
            Subject machine = PendingChanges.authorNow(rec, rolesRoot);   // the machine as it stands now, filtered
            Map<String, Object> marker = new LinkedHashMap<>(rec);
            marker.put("approvedBy", by);
            Map<String, Object> replayAttrs = new LinkedHashMap<>();
            replayAttrs.put(PendingActions.ATTR_APPROVED_ACTION, marker);
            replayAttrs.put(ApiContext.ATTR_SUBJECT, machine);
            replayAttrs.put(ComponentAccess.ATTR_HELD_ROLES, java.util.Set.copyOf(
                    rec.get("authorRoles") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.<String>of()));
            ApiContext.Replayed r = api.replay(ex, String.valueOf(request.get("method")), String.valueOf(request.get("path")),
                    String.valueOf(request.get("body")).getBytes(StandardCharsets.UTF_8), Map.of(), replayAttrs);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", r.status());
            result.put("body", parse(r.body()));
            rec.put("appliedStatus", r.status());
            boolean ran = r.status() >= 200 && r.status() < 300;
            // 409: the run moved on (resumed already, nothing to drain) — the action can never run as asked.
            rec.put("status", ran ? "approved" : r.status() == 409 ? "stale" : "failed");
            PendingActions.save(root, rec);
            PendingActions.audit(ex, "pending-action." + rec.get("status"), rec.get("action") + " — " + id
                    + (ran ? " approved by " + by + " and run" : " approved by " + by + " but its route answered " + r.status()),
                    rec, b -> b.attr("reason", reason).attr("status", r.status()));
            return Map.of("pendingAction", PendingActions.summary(rec), "ran", ran, "result", result);
        });
    }

    /** The asking machine takes its own still-pending action back. */
    private Object withdraw(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        if (ApiContext.subject(ex).isEmpty())
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "withdrawing an action needs an authenticated "
                    + "Subject — without one, no caller can be told to be the one who asked");
        Path root = WriteGates.requireWriteRoot(api, "pending action withdrawal");
        String reason = ApiContext.str(body, "reason");
        if (reason != null && reason.length() > PendingChanges.MAX_REASON)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'reason' is at most " + PendingChanges.MAX_REASON + " chars");
        return PendingChanges.underStoreLock(root, () -> {
            Map<String, Object> rec = PendingActions.read(root, id);
            if (rec == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no pending action '" + id + "'");
            if (PendingActions.invalid(rec))
                throw new ApiException(409, ErrorCodes.CONFLICT, "pending action '" + id + "' fails its integrity check — it cannot be withdrawn");
            PendingActions.expireIfDue(ex, root, rec);
            if (!"pending".equals(rec.get("status")))
                throw new ApiException(409, ErrorCodes.CONFLICT, "pending action '" + id + "' is already " + rec.get("status"));
            String by = ApiContext.actor(ex);
            if (!by.equals(rec.get("author")))
                throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "only '" + rec.get("author")
                        + "', who asked for it, may withdraw pending action '" + id + "' — an approver declines it instead");
            rec.put("status", "withdrawn");
            rec.put("decidedBy", by);
            rec.put("decidedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
            rec.put("decisionReason", reason);
            PendingActions.save(root, rec);
            PendingActions.audit(ex, "pending-action.withdrawn", rec.get("action") + " — " + id + " withdrawn by " + by,
                    rec, b -> b.attr("reason", reason));
            return Map.of("pendingAction", PendingActions.summary(rec), "ran", false);
        });
    }

    private static Object parse(String body) {
        try {
            return ApiContext.JSON.readValue(body, Object.class);
        } catch (IOException | RuntimeException notJson) {
            return body;
        }
    }
}
