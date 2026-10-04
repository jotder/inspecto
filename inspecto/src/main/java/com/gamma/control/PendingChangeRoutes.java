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
import java.util.Set;

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
 *   POST /pending-changes/{id}/withdraw        its AUTHOR takes it back, unapplied (no capability — author-only)
 * </pre>
 *
 * <p><b>Decide gates, in order</b> (the Link Analysis four-eyes pattern, D-U7): {@code canApproveChanges} (the
 * route) → an authenticated Subject 403 (without one an author and an approver cannot be told apart) → write
 * root 503 → an unsafe id 422 → no such change 404 → already decided or expired 409 → the kind's
 * {@code approverCapability} 403 → with {@code fourEyes}, the author deciding their own change 403. Approve then
 * replays the original request ({@link ApiContext#replay}); the replay's own refusal comes back as its status
 * — a stale base is 409 and closes the change as {@code stale}, anything else leaves it pending.
 *
 * <p><b>Withdraw gates, in order</b> (`ASSURE-MAKER-CHECKER-RESIDUALS-1` (2)): an authenticated Subject 403 (as
 * decide — without one there is no author to be) → write root 503 → reason 422 → an unsafe id 422 → no such
 * change 404 → integrity 409 → already decided or expired 409 → the caller is not its author 403. No route
 * capability: the only person who may withdraw a change is the one who proposed it, so the gate IS the
 * author check (a {@code self-service} exemption in {@link CapabilityManifest}). Same compare-and-set as decide.
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
                (e, m) -> writePolicy(api, e, api.body(e))));
        api.get("/pending-changes", (e, m) -> list(api, e));
        api.get("/pending-changes/([^/]+)/diff", (e, m) -> diff(api, e, ApiContext.name(m)));
        api.get("/pending-changes/([^/]+)", (e, m) -> one(api, e, ApiContext.name(m)));
        api.post("/pending-changes/([^/]+)/approve", ApiContext.withCapability("canApproveChanges",
                (e, m) -> decide(api, e, ApiContext.name(m), true, api.body(e))));
        api.post("/pending-changes/([^/]+)/decline", ApiContext.withCapability("canApproveChanges",
                (e, m) -> decide(api, e, ApiContext.name(m), false, api.body(e))));
        // Author-only, so no capability wrap: the handler's author check is the gate (CapabilityManifest EXEMPTIONS).
        api.post("/pending-changes/([^/]+)/withdraw", (e, m) -> withdraw(api, e, ApiContext.name(m), api.body(e)));
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

    private static byte[] randomBytes() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        return b;
    }

    /**
     * The {@link com.gamma.job.PostgresPublishJobType.ApprovalVerifier} the control plane installs: an approval record
     * is honoured only if the Pending Change it names exists under this config root, passes its MAC check, is
     * {@code approved}, is for that Job, and carries the same nonce.
     */
    static boolean verifyPublicationApproval(Path root, Map<String, Object> record) {
        Object id = record.get("pendingChange"), nonce = record.get("nonce"), job = record.get("job");
        if (id == null || nonce == null || job == null) return false;
        try {
            Map<String, Object> pc = PendingChanges.read(root, String.valueOf(id));
            return pc != null && !PendingChanges.invalid(pc) && "approved".equals(pc.get("status"))
                    && String.valueOf(nonce).equals(String.valueOf(pc.get("publicationNonce")))
                    && pc.get("proposed") instanceof Map<?, ?> proposed
                    && String.valueOf(job).equals(PendingChanges.publicationName(proposed))
                    // the record's fingerprints must be the ones the MAC'd Pending Change was approved with — an
                    // approval file edited on the host fails here
                    && record.get("fingerprints") instanceof Map<?, ?> recorded
                    && pc.get("publicationFingerprints") instanceof Map<?, ?> approved
                    && stringMap(recorded).equals(stringMap(approved));
        } catch (RuntimeException | java.io.IOException unreadable) {
            return false;
        }
    }

    private static Map<String, String> stringMap(Map<?, ?> m) {
        Map<String, String> out = new java.util.TreeMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        return out;
    }

    private Object writePolicy(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        // Operator, 2026-09-28: with no Authenticator the canAdminister wrap is a no-op, so anyone could switch
        // four-eyes off - refuse the route outright. Personal edition cannot change the policy through the API.
        if (Authenticators.active().isEmpty())
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "the approval policy cannot be changed "
                    + "on a server with no Authenticator configured");
        // Operator, 2026-09-28: an empty body used to parse as {} and write the OFF policy - turning four-eyes
        // off must be explicit ({"approval": {}}).
        if (body == null || body.isEmpty())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "an empty body is refused - send "
                    + "{\"approval\": {}} to turn every hold off explicitly");
        Path root = WriteGates.requireWriteRoot(api, "approval policy write");
        ApprovalPolicy before = ApprovalPolicy.forRoot(root);
        Map<String, Object> doc = new LinkedHashMap<>(body);
        ApprovalPolicy p;
        try {
            p = ApprovalPolicy.parse(doc, true);
        } catch (IllegalArgumentException refused) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, refused.getMessage());
        }
        p.write(root.resolve(ApprovalPolicy.FILE));
        // Verification finding 5: turning maker-checker on, off or wider is itself an act an auditor asks
        // about — one dedicated row with who, and the policy before and after.
        Map<String, Object> was = before.toMap();
        was.put("failedClosed", before.failedClosed());
        Map<String, Object> now = p.toMap();
        try {
            com.gamma.event.EventLog.current().emit(com.gamma.event.Event.builder(com.gamma.event.EventType.AUDIT)
                    .source("audit").message(ApiContext.actor(ex) + " changed the approval policy")
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("approval-policy.changed").actionCategory("configuration")
                    .attr("before", ApiContext.JSON.writeValueAsString(was))    // JSON, not Map.toString
                    .attr("after", ApiContext.JSON.writeValueAsString(now)));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // best effort, like every audit emit on a request path
        }
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
            int[] n = {0};
            Map<List<Object>, String> memo = new java.util.HashMap<>();
            PendingChanges.underStoreLock(root, () -> {
                for (Map<String, Object> rec : PendingChanges.list(root)) {
                    PendingChanges.expireIfDue(ex, root, rec);
                    if (status != null && !status.isBlank() && !status.equals(rec.get("status"))) continue;
                    if (kind != null && !kind.isBlank() && !kind.equals(rec.get("kind"))) continue;
                    n[0]++;
                    if (items.size() < LIST_CAP) items.add(withApproverCheck(root, rec, PendingChanges.summary(rec), memo));
                }
                return null;
            });
            total = n[0];
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
        return withApproverCheck(api.writeRoot(), rec, out, new java.util.HashMap<>());
    }

    /**
     * Whether anyone could approve this pending change (ASSURE-ENTITY-LISTS-RESIDUALS-1 (4)), for every kind: the
     * same {@code none-eligible | unknown | ok} reading an Action Request carries, over the change's own
     * {@code approverCapability}, with its author out when {@code fourEyes}. Computed live, informational only.
     */
    static Map<String, Object> withApproverCheck(Path root, Map<String, Object> rec, Map<String, Object> view,
                                                 Map<List<Object>, String> memo) {
        if (!"pending".equals(rec.get("status")) || root == null) return view;
        String cap = String.valueOf(rec.get("approverCapability"));
        Set<Object> makers = Boolean.TRUE.equals(rec.get("fourEyes")) ? Set.<Object>of(String.valueOf(rec.get("author"))) : Set.<Object>of();
        view.put("approverCheck", memo.computeIfAbsent(List.of(cap, makers),
                k -> ActionRequestRoutes.check(root, makers, cap, false)));
        return view;
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
        return PendingChanges.underStoreLock(root, () -> {
            Map<String, Object> rec = PendingChanges.read(root, id);
            if (rec == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no pending change '" + id + "'");
            PendingChanges.expireIfDue(ex, root, rec);
            return rec;
        });
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
        // The compare-and-set (`ASSURE-MAKER-CHECKER-MULTIPOD-1`): read → still pending? → decide → save, all under
        // the store's cross-process lock, and the APPLY (the replay) inside it too — so a second Pod deciding the
        // same change blocks, then re-reads the winner's record and gets 409; it can never apply it again.
        return PendingChanges.<Object, Exception>underStoreLock(root, () -> {
            Map<String, Object> rec = PendingChanges.read(root, id);   // 422 on an unsafe id
            if (rec == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no pending change '" + id + "'");
            if (PendingChanges.invalid(rec))
                throw new ApiException(409, ErrorCodes.CONFLICT, "pending change '" + id + "' fails its integrity check "
                        + "(its MAC does not verify: it was not written by this server, or was edited since) — it cannot be decided");
            PendingChanges.expireIfDue(ex, root, rec);
            if (!"pending".equals(rec.get("status")))
                throw new ApiException(409, ErrorCodes.CONFLICT, "pending change '" + id + "' is already " + rec.get("status")
                        + (rec.get("decidedBy") == null ? "" : " (decided by " + rec.get("decidedBy") + " at " + rec.get("decidedAt") + ")"));
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
            // Re-verification finding 2 (i): only a route that reaches the hold before it writes may be replayed —
            // checked BEFORE anything is dispatched, so a record naming any other route cannot write at all.
            if (request == null || !PendingChanges.replayable(String.valueOf(request.get("method")),
                    String.valueOf(request.get("path"))))
                throw new ApiException(409, ErrorCodes.CONFLICT, "pending change '" + id + "' names a request ("
                        + (request == null ? "none" : request.get("method") + " " + request.get("path"))
                        + ") that is not a maker-checker route — nothing was dispatched");
            // ASSURE-BI-PUBLICATION-1: a publication is approved for its CONTENT. The Connection and Datasets must be
            // what they were when it was proposed, and a Connection with insecure_tls needs an administrator.
            Map<String, String> publication = null;
            if (rec.get("proposed") instanceof Map<?, ?> proposedJob
                    && PendingChanges.isPublicationWrite(String.valueOf(rec.get("kind")), (Map<String, Object>) proposedJob)) {
                publication = PendingChanges.publicationFingerprints(api, proposedJob);
                if (!(rec.get("publicationFingerprints") instanceof Map<?, ?> then)
                        || !com.gamma.job.PublicationApproval.changed(stringMap(then), publication).isEmpty())
                    throw new ApiException(409, ErrorCodes.CONFLICT, "the publication changed since it was proposed ("
                            + (rec.get("publicationFingerprints") instanceof Map<?, ?> t
                                    ? String.join(" | ", com.gamma.job.PublicationApproval.changed(stringMap(t), publication))
                                    : "no fingerprint recorded")
                            + ") — nothing was applied; propose it again");
                if (PendingChanges.insecureTls(api, proposedJob)) ApiContext.requireCapability(ex, Roles.CAN_ADMINISTER);
            }
            requireAttachUnchanged(root, api, rec);
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
            if (applied && publication != null) {
                java.util.Set<String> caps = ApiContext.subject(ex).map(Subject::capabilities).orElse(java.util.Set.of());
                String nonce = java.util.HexFormat.of().formatHex(randomBytes());
                rec.put("publicationNonce", nonce);   // saved with the approved record below (MAC'd)
                com.gamma.job.PublicationApproval.record(root, PendingChanges.publicationName((Map<?, ?>) rec.get("proposed")),
                        publication, by, caps, nonce, String.valueOf(rec.get("id")));
            }
            if (applied) {
                rec.put("status", "approved");
                rec.put("decidedBy", by);
                rec.put("decidedAt", at);
                rec.put("decisionReason", reason);
                rec.put("appliedStatus", r.status());
                recordAttachApproval(root, rec, by);
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
        });
    }

    /**
     * The author takes their own still-pending change back: status {@code withdrawn}, terminal, signed and
     * audited exactly as a decline is. Under the store lock — so a withdraw racing an approve (on this Pod or
     * another) leaves exactly one outcome, the loser 409.
     */
    private Object withdraw(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        if (ApiContext.subject(ex).isEmpty())
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "withdrawing a change needs an authenticated "
                    + "Subject — without one, no caller can be told to be its author");
        Path root = WriteGates.requireWriteRoot(api, "pending change withdrawal");
        String reason = ApiContext.str(body, "reason");
        if (reason != null && reason.length() > PendingChanges.MAX_REASON)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'reason' is at most " + PendingChanges.MAX_REASON + " chars");
        return PendingChanges.underStoreLock(root, () -> {
            Map<String, Object> rec = PendingChanges.read(root, id);   // 422 on an unsafe id
            if (rec == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no pending change '" + id + "'");
            if (PendingChanges.invalid(rec))
                throw new ApiException(409, ErrorCodes.CONFLICT, "pending change '" + id + "' fails its integrity check "
                        + "(its MAC does not verify) — it cannot be withdrawn");
            PendingChanges.expireIfDue(ex, root, rec);
            if (!"pending".equals(rec.get("status")))
                throw new ApiException(409, ErrorCodes.CONFLICT, "pending change '" + id + "' is already " + rec.get("status")
                        + (rec.get("decidedBy") == null ? "" : " (decided by " + rec.get("decidedBy") + " at " + rec.get("decidedAt") + ")"));
            String by = ApiContext.actor(ex);
            if (!by.equals(rec.get("author")))
                throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "only its author '" + rec.get("author")
                        + "' may withdraw pending change '" + id + "' — an approver declines it instead");
            rec.put("status", "withdrawn");
            rec.put("decidedBy", by);
            rec.put("decidedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
            rec.put("decisionReason", reason);
            PendingChanges.save(root, rec);
            PendingChanges.audit(ex, "pending-change.withdrawn", rec.get("kind") + " '" + rec.get("name")
                    + "' — " + id + " withdrawn by its author " + by, rec, b -> b.attr("reason", reason));
            return Map.of("pendingChange", PendingChanges.summary(rec), "applied", false);
        });
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

    /**
     * ASSURE-XLSX-ATTACHMENTS-1 round 4: an attachment approval approves the fingerprint FIXED AT HOLD TIME. Before the
     * replay, the live content (the proposed Job expanded against today's templates, today's Dataset and view SQL)
     * must still hash to it, else 409 and nothing is applied.
     */
    private static void requireAttachUnchanged(java.nio.file.Path root, ApiContext api, Map<String, Object> rec) {
        if (!(rec.get("attachFingerprint") instanceof String then)) return;
        @SuppressWarnings("unchecked") Map<String, Object> proposed = (Map<String, Object>) rec.get("proposed");
        String now;
        try {
            now = JobWriteGuard.fingerprint(String.valueOf(rec.get("kind")), proposed, root, api);
        } catch (ApiException notApprovable) {
            throw new ApiException(409, ErrorCodes.CONFLICT, notApprovable.getMessage() + " — nothing was applied");
        }
        if (!then.equals(now))
            throw new ApiException(409, ErrorCodes.CONFLICT, "what this report Job would attach, or to whom, changed "
                    + "since it was proposed (its Dataset, view or template) — nothing was applied; propose it again");
    }

    /** After a successful replay: record the hold-time fingerprint, bound to this Pending Change (never the live Job). */
    private static void recordAttachApproval(java.nio.file.Path root, Map<String, Object> rec, String by)
            throws java.io.IOException {
        if (!(rec.get("attachFingerprint") instanceof String fp)) return;
        String nonce = com.gamma.job.AttachApprovals.newNonce();
        rec.put("attachNonce", nonce);   // saved with the approved record right after (MAC'd)
        com.gamma.job.AttachApprovals.record(root, JobWriteGuard.name((Map<?, ?>) rec.get("proposed")), fp,
                String.valueOf(rec.get("id")), by, nonce);
    }

    /**
     * The {@link com.gamma.job.AttachApprovals.Verifier} the control plane installs (round 5): an approval record is
     * honoured only if the Pending Change it names exists under this config root, passes its MAC check, is
     * {@code approved}, is for that Job, carries the same {@code attachNonce}, and fixed the same fingerprint.
     */
    static boolean verifyAttachApproval(java.nio.file.Path root, String job, Map<String, Object> record) {
        Object id = record.get("pendingChange"), nonce = record.get("nonce"), fp = record.get("fingerprint");
        if (id == null || nonce == null || fp == null || job == null) return false;
        try {
            Map<String, Object> pc = PendingChanges.read(root, String.valueOf(id));
            return pc != null && !PendingChanges.invalid(pc) && "approved".equals(pc.get("status"))
                    && String.valueOf(nonce).equals(String.valueOf(pc.get("attachNonce")))
                    && String.valueOf(fp).equals(String.valueOf(pc.get("attachFingerprint")))
                    && pc.get("proposed") instanceof Map<?, ?> proposed && job.equals(JobWriteGuard.name(proposed));
        } catch (RuntimeException | java.io.IOException unreadable) {
            return false;
        }
    }
}
