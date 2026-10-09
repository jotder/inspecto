package com.gamma.regreporting;

import com.gamma.access.Roles;
import com.gamma.access.WriteGates;
import com.gamma.audit.EventLevel;
import com.gamma.control.ApproverCheck;
import com.gamma.control.ApproverRoster;
import com.gamma.control.PendingChanges;
import com.gamma.ops.ObjectService;
import com.gamma.ops.OperationalObject;
import com.gamma.ops.link.LinkRelationship;
import com.gamma.ops.link.ObjectLink;
import com.gamma.ops.note.NoteKind;
import com.gamma.ops.note.ObjectNote;
import com.gamma.opsapi.ObjectRoutes;
import com.gamma.opsapi.OpsEngine;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.auth.Subject;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Regulatory Reporting surface ({@code REGULATORY-REPORTING-1}) — see {@link RegulatoryReports} for the model,
 * {@link ReportTemplate} for templates, {@link ReportRenderer} for rendering and {@link FileDrop} for delivery.
 * <pre>
 *   GET  /regulatory-reports/templates                         the Space's templates, with load problems
 *   GET  /regulatory-reports[?status=&amp;template=&amp;caseId=&amp;incidentId=]   newest first (capped, truncated, true total)
 *   GET  /regulatory-reports/{id}                              one, with its content, sourceChanged and approverCheck
 *   POST /regulatory-reports                                   draft from a Case / Incident        (canWorkIncidents)
 *   POST /regulatory-reports/{id}/request-approval             draft → pending                      (canWorkIncidents)
 *   POST /regulatory-reports/{id}/approve                      four-eyes approve → submitted|failed (canApproveChanges)
 *   POST /regulatory-reports/{id}/decline                      four-eyes decline                    (canApproveChanges)
 *   POST /regulatory-reports/{id}/retry                        re-submit a failed one, same bytes   (canApproveChanges)
 * </pre>
 *
 * <p><b>Draft gates, in order</b>: {@code canWorkIncidents} (the route) → write root 503 → an unknown body key 422 →
 * the template unknown or failing to load 422 → not exactly one of {@code caseId} / {@code incidentId} 422 → an input
 * the template does not read, or a non-scalar one 422 → no operational objects 503 → the subject absent or out of
 * the caller's scope 404 → a member Incident out of the caller's scope 403 (a partial filing is refused) → the drop
 * directory refused by the path jail 422 → the render refused (a required field empty, a value over its maxLength)
 * 422 → saved {@code draft}.
 *
 * <p><b>Decide gates, in order</b> (the Action Request / Pending Change pattern): the capability (the route) → an
 * authenticated Subject 403 → write root 503 → a body key other than {@code reason} 422 → an unsafe id 422 → no such
 * report (or not visible) 404 → a record failing its integrity check 409 → not {@code pending} (incl. just expired)
 * 409 → a maker (the author or the approval requester) deciding 403 → not on the approver roster 403. ALWAYS
 * four-eyes, not a policy option, and never {@code PendingChanges.hold}: a report is not config, and holding it there
 * as well would make one filing need two approvals.
 */
public final class RegulatoryReportRoutes implements RouteModule {

    @Override
    public Set<String> featureIds() {
        return Set.of("regulatoryReporting");
    }

    static final int LIST_CAP = 500;
    static final int MAX_INPUT_CHARS = 20_000;
    private static final Set<String> CREATE_KEYS = Set.of("template", "caseId", "incidentId", "inputs", "reason");
    private static final Set<String> DECIDE_KEYS = Set.of("reason");

    @Override
    public void register(ApiContext api) {
        // templates FIRST: registration is first-match, and "templates" would otherwise read as a (refused) report id
        api.get("/regulatory-reports/templates", (e, m) -> templates(api, e));
        api.get("/regulatory-reports", (e, m) -> list(api, e));
        api.get("/regulatory-reports/([^/]+)", (e, m) -> one(api, e, ApiContext.name(m)));
        api.post("/regulatory-reports", ApiContext.withCapability("canWorkIncidents",
                (e, m) -> create(api, e, api.body(e))));
        api.post("/regulatory-reports/([^/]+)/request-approval", ApiContext.withCapability("canWorkIncidents",
                (e, m) -> requestApproval(api, e, ApiContext.name(m), api.body(e))));
        api.post("/regulatory-reports/([^/]+)/approve", ApiContext.withCapability("canApproveChanges",
                (e, m) -> decide(api, e, ApiContext.name(m), true, api.body(e))));
        api.post("/regulatory-reports/([^/]+)/decline", ApiContext.withCapability("canApproveChanges",
                (e, m) -> decide(api, e, ApiContext.name(m), false, api.body(e))));
        api.post("/regulatory-reports/([^/]+)/retry", ApiContext.withCapability("canApproveChanges",
                (e, m) -> retry(api, e, ApiContext.name(m), api.body(e))));
    }

    // ── reads ────────────────────────────────────────────────────────────────────────────────────

    private Object templates(ApiContext api, HttpExchange ex) {
        requireReader(ex);
        ReportTemplate.Catalog c = ReportTemplate.load(api.writeRoot());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", c.templates().values().stream().map(ReportTemplate::view).toList());
        out.put("problems", c.problems().stream().map(ReportTemplate.Problem::view).toList());
        return out;
    }

    private Object list(ApiContext api, HttpExchange ex) throws IOException {
        requireReader(ex);
        Path root = api.writeRoot();
        String status = ApiContext.query(ex, "status");
        String template = ApiContext.query(ex, "template");
        String incident = ApiContext.query(ex, "incidentId");
        String kase = ApiContext.query(ex, "caseId");
        List<Map<String, Object>> items = new ArrayList<>();
        int total = 0;
        if (root != null) {
            synchronized (RegulatoryReports.lock()) {
                for (Map<String, Object> rec : RegulatoryReports.list(root)) {
                    RegulatoryReports.expireIfDue(root, rec);
                    if (blankOr(status, rec.get("status")) && blankOr(template, rec.get("template"))
                            && blankOr(incident, rec.get("incidentId")) && blankOr(kase, rec.get("caseId"))
                            && visible(api, ex, rec)) {
                        total++;
                        if (items.size() < LIST_CAP) items.add(RegulatoryReports.summary(rec));
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

    private Object one(ApiContext api, HttpExchange ex, String id) throws IOException {
        requireReader(ex);
        Path root = api.writeRoot();
        if (root == null) throw notFound(id);
        Map<String, Object> rec;
        synchronized (RegulatoryReports.lock()) {
            rec = RegulatoryReports.read(root, id);   // 422 on an unsafe id
            if (rec == null || !visible(api, ex, rec)) throw notFound(id);
            RegulatoryReports.expireIfDue(root, rec);
        }
        Map<String, Object> view = RegulatoryReports.detail(rec);
        if (!RegulatoryReports.invalid(rec)
                && (RegulatoryReports.DRAFT.equals(rec.get("status")) || RegulatoryReports.PENDING.equals(rec.get("status")))) {
            // computed live: the approver must see a Case edited after the report was rendered
            try {
                String now = ReportRenderer.sourceFingerprint(context(api, ex, rec, Map.of()));
                view.put("sourceChanged", !now.equals(rec.get("sourceFingerprint")));
            } catch (ApiException gone) {
                view.put("sourceChanged", true);
                view.put("sourceError", gone.getMessage());
            }
            if (RegulatoryReports.PENDING.equals(rec.get("status"))) view.put("approverCheck", approverCheck(root, rec));
        }
        return view;
    }

    /**
     * Reading needs {@code canWorkIncidents} OR {@code canApproveChanges}, checked literally here because a manifest
     * entry names one capability and this is an either-or. A no-op without a Subject, like every capability gate.
     */
    private static void requireReader(HttpExchange ex) {
        Subject s = ApiContext.subject(ex).orElse(null);
        if (s != null && !s.capabilities().contains("canWorkIncidents") && !s.capabilities().contains("canApproveChanges"))
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "reading regulatory reports needs canWorkIncidents "
                    + "or canApproveChanges");
    }

    /**
     * A report is visible exactly when its Case / Incident is (SEC-7d scope + row policy). Invisible reads as absent.
     * A record failing its integrity check (whose linkage cannot be trusted) is visible only to an approver.
     */
    static boolean visible(ApiContext api, HttpExchange ex, Map<String, Object> rec) {
        if (RegulatoryReports.invalid(rec))
            return ApiContext.subject(ex).map(s -> s.capabilities().contains("canApproveChanges")).orElse(true);
        Object linked = rec.get("caseId") != null ? rec.get("caseId") : rec.get("incidentId");
        if (linked == null) return false;
        try {
            OperationalObject o = OpsEngine.of(api).get(String.valueOf(linked)).orElse(null);
            return o != null && ObjectRoutes.visibleTo(ex, o);
        } catch (ApiException noEngine) {
            return false;
        }
    }

    /** Whether anyone could approve this report — see {@link ApproverCheck}. Never changes the decision. */
    static String approverCheck(Path root, Map<String, Object> rec) {
        if (RegulatoryReports.invalid(rec)) return ApproverCheck.UNKNOWN;
        return ApproverCheck.check(root, new HashSet<>(RegulatoryReports.makers(rec)), Roles.CAN_APPROVE_CHANGES, true);
    }

    // ── the report context ───────────────────────────────────────────────────────────────────────

    /**
     * Assemble the report context from the record's subject (see {@link ReportRenderer}). Throws 404 when the
     * subject is absent or out of scope, 403 when a member Incident is out of the caller's scope.
     */
    static Map<String, Object> context(ApiContext api, HttpExchange ex, Map<String, Object> rec, Map<String, Object> inputs) {
        boolean isCase = rec.get("caseId") != null;
        String id = String.valueOf(isCase ? rec.get("caseId") : rec.get("incidentId"));
        String kind = isCase ? "case" : "incident";
        ObjectService svc = OpsEngine.of(api);   // 503 when the engine is absent
        OperationalObject subject = svc.get(id)
                .filter(o -> !o.isInert() && kind.equals(o.typeName().toLowerCase(Locale.ROOT)))
                .filter(o -> ObjectRoutes.visibleTo(ex, o))
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no " + kind + " '" + id + "'"));
        List<OperationalObject> incidents = new ArrayList<>();
        if (isCase) {
            for (ObjectLink l : svc.linksOf(id)) {
                if (!LinkRelationship.CONTAINS.equals(l.relationship()) || !id.equals(l.fromId())) continue;
                OperationalObject member = svc.get(l.toId()).orElse(null);
                if (member == null || member.isInert() || !"incident".equals(member.typeName().toLowerCase(Locale.ROOT)))
                    continue;
                if (!ObjectRoutes.visibleTo(ex, member))
                    throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "case '" + id + "' contains an Incident "
                            + "outside your scope - a regulatory report is never filed from part of a Case");
                incidents.add(member);
            }
            incidents.sort(java.util.Comparator.comparingLong(OperationalObject::createdAt).thenComparing(OperationalObject::id));
        } else incidents.add(subject);

        List<Map<String, Object>> evidence = new ArrayList<>();
        List<OperationalObject> holders = new ArrayList<>();
        holders.add(subject);
        for (OperationalObject i : incidents) if (!i.id().equals(subject.id())) holders.add(i);
        for (OperationalObject h : holders)
            for (ObjectNote n : svc.notesOf(h.id(), NoteKind.ATTACHMENT)) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("objectId", h.id());
                e.put("name", n.attributes().get("name"));
                e.put("uri", n.attributes().get("uri"));
                e.put("contentType", n.attributes().get("contentType"));
                e.put("caption", n.body());
                e.put("author", n.author());
                e.put("createdAt", iso(n.createdAt()));
                evidence.add(e);
            }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("id", rec.get("id"));
        report.put("createdAt", rec.get("createdAt"));
        report.put("author", rec.get("author"));
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("report", report);
        ctx.put("subject", objectView(subject));
        ctx.put("incidents", incidents.stream().map(RegulatoryReportRoutes::objectView).toList());
        ctx.put("evidence", evidence);
        ctx.put("input", inputs);
        return ctx;
    }

    /** An object's view for a report: its JSON view, timestamps as ISO-8601, less the volatile bookkeeping. */
    static Map<String, Object> objectView(OperationalObject o) {
        Map<String, Object> m = new LinkedHashMap<>(o.toMap());
        m.remove("watchers");
        m.remove("version");
        m.remove("updatedAt");
        m.put("createdAt", iso(o.createdAt()));
        m.put("closedAt", o.closedAt() > 0 ? iso(o.closedAt()) : null);
        return m;
    }

    private static String iso(long epochMillis) {
        return epochMillis <= 0 ? null : Instant.ofEpochMilli(epochMillis).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    // ── draft ────────────────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Object create(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "regulatory report");
        for (String k : body.keySet())
            if (!CREATE_KEYS.contains(k))
                throw invalid("unknown key '" + k + "' - a regulatory report takes " + new java.util.TreeSet<>(CREATE_KEYS));
        String templateId = ApiContext.str(body, "template");
        if (templateId == null) throw invalid("'template' (a Report Template id) is required");
        ReportTemplate.Catalog catalog = ReportTemplate.load(root);
        ReportTemplate t = catalog.get(templateId);
        if (t == null) {
            ReportTemplate.Problem p = catalog.problems().stream().filter(x -> x.id().equals(templateId)).findFirst().orElse(null);
            throw invalid(p == null ? "no Report Template '" + templateId + "'"
                    : "Report Template '" + templateId + "' does not load: " + String.join("; ", p.problems()));
        }
        String reason = ApiContext.str(body, "reason");
        if (reason != null && reason.length() > RegulatoryReports.MAX_REASON)
            throw invalid("'reason' is at most " + RegulatoryReports.MAX_REASON + " chars");
        String incident = ApiContext.str(body, "incidentId");
        String kase = ApiContext.str(body, "caseId");
        if ((incident == null) == (kase == null))
            throw invalid("exactly one of 'caseId' / 'incidentId' is required - a regulatory report is raised from one");
        Map<String, Object> inputs = new LinkedHashMap<>();
        Object rawInputs = body.get("inputs");
        if (rawInputs != null && !(rawInputs instanceof Map<?, ?>)) throw invalid("'inputs' must be a JSON object");
        if (rawInputs instanceof Map<?, ?> in) {
            Set<String> allowed = t.inputKeys();
            for (Map.Entry<?, ?> e : in.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (!allowed.contains(k))
                    throw invalid("input '" + k + "' is not read by template '" + templateId + "' - it reads " + allowed);
                Object v = e.getValue();
                if (v != null && !(v instanceof String || v instanceof Number || v instanceof Boolean))
                    throw invalid("input '" + k + "' must be text, a number or a boolean");
                if (v != null && String.valueOf(v).length() > MAX_INPUT_CHARS)
                    throw invalid("input '" + k + "' is over " + MAX_INPUT_CHARS + " characters");
                inputs.put(k, v);
            }
        }

        String author = ApiContext.actor(ex);
        String createdAt = RegulatoryReports.now();
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", RegulatoryReports.newId());
        rec.put("recordType", RegulatoryReports.DOMAIN);
        rec.put("status", RegulatoryReports.DRAFT);
        rec.put("template", t.id());
        rec.put("templateTitle", t.title());
        rec.put("format", t.format());
        rec.put("mediaType", ReportRenderer.mediaType(t.format()));
        rec.put("caseId", kase);
        rec.put("incidentId", incident);
        rec.put("author", author);
        rec.put("authorType", ApiContext.actorType(ex));
        rec.put("reason", reason);
        rec.put("createdAt", createdAt);
        rec.put("expiresAt", Instant.parse(createdAt).plus(PendingChanges.expiryHours(root), ChronoUnit.HOURS).toString());

        Map<String, Object> ctx = context(api, ex, rec, inputs);   // 503 / 404 / 403
        rec.put("subjectTitle", ((Map<String, Object>) ctx.get("subject")).get("title"));
        Path dropDir;
        try {
            dropDir = FileDrop.resolve(root, t.deliveryDir());
        } catch (IllegalArgumentException refused) {
            throw invalid("template '" + templateId + "' delivery.dir is refused: " + refused.getMessage());
        }
        ReportRenderer.Rendered r;
        try {
            r = ReportRenderer.render(t, ctx);
        } catch (ReportRenderer.Refused refused) {
            ApiException e = invalid("the report does not render: " + String.join("; ", refused.problems()));
            throw e;
        }
        rec.put("inputs", inputs);
        rec.put("content", r.content());
        rec.put("contentSha256", r.sha256());
        rec.put("sourceFingerprint", ReportRenderer.sourceFingerprint(ctx));
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("kind", t.deliveryKind());
        delivery.put("dir", dropDir.toString());
        delivery.put("fileName", rec.get("id") + "." + t.extension());
        rec.put("delivery", delivery);
        rec.put("requestedBy", null);
        rec.put("approver", null);
        rec.put("submission", null);
        List<Object> history = new ArrayList<>();
        history.add(Map.of("status", RegulatoryReports.DRAFT, "by", author, "at", createdAt));
        rec.put("history", history);
        synchronized (RegulatoryReports.lock()) {
            RegulatoryReports.save(root, rec);
        }
        RegulatoryReports.audit(author, ApiContext.actorType(ex), "regulatory-report.drafted", rec.get("id")
                + " drafted from " + (kase != null ? "case " + kase : "incident " + incident) + " with template '"
                + t.id() + "'", rec, null);
        return RegulatoryReports.detail(rec);
    }

    private static ApiException invalid(String message) {
        return new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, message);
    }

    private static ApiException notFound(String id) {
        return new ApiException(404, ErrorCodes.NOT_FOUND, "no regulatory report '" + id + "'");
    }

    // ── request approval / decide / retry ────────────────────────────────────────────────────────

    /** The gates every transition shares, up to the status check. Call under the store lock. */
    private static Map<String, Object> gated(ApiContext api, HttpExchange ex, Path root, String id, Map<String, Object> body)
            throws IOException {
        for (String k : body.keySet())
            if (!DECIDE_KEYS.contains(k))
                throw invalid("unknown key '" + k + "' - this carries only 'reason'; the content is fixed at draft");
        String reason = ApiContext.str(body, "reason");
        if (reason != null && reason.length() > RegulatoryReports.MAX_REASON)
            throw invalid("'reason' is at most " + RegulatoryReports.MAX_REASON + " chars");
        Map<String, Object> rec = RegulatoryReports.read(root, id);   // 422 on an unsafe id
        if (rec == null || !visible(api, ex, rec)) throw notFound(id);
        if (RegulatoryReports.invalid(rec))
            throw new ApiException(409, ErrorCodes.CONFLICT, "regulatory report '" + id + "' fails its integrity check "
                    + "(its MAC does not verify: it was not written by this server, or was edited since) - it cannot be "
                    + "approved or submitted");
        RegulatoryReports.expireIfDue(root, rec);
        return rec;
    }

    private static ApiException wrongStatus(String id, Object status, String want) {
        return new ApiException(409, ErrorCodes.CONFLICT, "regulatory report '" + id + "' is " + status + ", not " + want);
    }

    private Object requestApproval(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "regulatory report");
        String by = ApiContext.actor(ex);
        Map<String, Object> rec;
        synchronized (RegulatoryReports.lock()) {
            rec = gated(api, ex, root, id, body);
            if (!RegulatoryReports.DRAFT.equals(rec.get("status"))) throw wrongStatus(id, rec.get("status"), "draft");
            rec.put("requestedBy", by);
            rec.put("requestedAt", RegulatoryReports.now());
            rec.put("requestReason", ApiContext.str(body, "reason"));
            rec.put("expiresAt", Instant.now().truncatedTo(ChronoUnit.SECONDS)
                    .plus(PendingChanges.expiryHours(root), ChronoUnit.HOURS).toString());
            RegulatoryReports.transition(rec, RegulatoryReports.PENDING, by);
            RegulatoryReports.save(root, rec);
        }
        RegulatoryReports.audit(by, ApiContext.actorType(ex), "regulatory-report.approval-requested",
                id + " sent for approval by " + by, rec, null);
        if (ApproverCheck.NONE_ELIGIBLE.equals(approverCheck(root, rec)))
            RegulatoryReports.audit(by, ApiContext.actorType(ex), "regulatory-report.no-eligible-approver", id
                    + " has no eligible approver: no one holding canApproveChanges in this Space is outside its makers",
                    rec, Authenticators.active().isPresent() ? EventLevel.WARN : EventLevel.INFO);
        return current(root, id);
    }

    private static Path subjectAndRoot(ApiContext api, HttpExchange ex, String what) {
        if (ApiContext.subject(ex).isEmpty())
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, what + " a regulatory report needs an "
                    + "authenticated Subject - without one, the maker and the checker cannot be told apart");
        return WriteGates.requireWriteRoot(api, "regulatory report decision");
    }

    private Object decide(ApiContext api, HttpExchange ex, String id, boolean approve, Map<String, Object> body)
            throws IOException {
        Path root = subjectAndRoot(api, ex, approve ? "approving" : "declining");
        String by = ApiContext.actor(ex);
        Map<String, Object> rec;
        synchronized (RegulatoryReports.lock()) {
            rec = gated(api, ex, root, id, body);
            if (!RegulatoryReports.PENDING.equals(rec.get("status"))) throw wrongStatus(id, rec.get("status"), "pending");
            if (RegulatoryReports.makers(rec).contains(by))
                throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "four-eyes: '" + by + "' made this regulatory "
                        + "report (drafted it or sent it for approval) and cannot " + (approve ? "approve" : "decline")
                        + " it - a different person must");
            ApproverRoster.requireOnRoster(ex, root, (approve ? "approve" : "decline") + " a regulatory report");
            rec.put(approve ? "approver" : "decidedBy", by);
            rec.put(approve ? "approvedAt" : "decidedAt", RegulatoryReports.now());
            rec.put("decisionReason", ApiContext.str(body, "reason"));
            RegulatoryReports.transition(rec, approve ? RegulatoryReports.APPROVED : RegulatoryReports.DECLINED, by);
            RegulatoryReports.save(root, rec);
            RegulatoryReports.audit(by, ApiContext.actorType(ex), approve ? "regulatory-report.approved"
                    : "regulatory-report.declined", id + (approve ? " approved" : " declined") + " by " + by, rec, null);
            if (approve) submit(root, rec, by, ApiContext.actorType(ex));
        }
        return current(root, id);
    }

    private Object retry(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        Path root = subjectAndRoot(api, ex, "retrying");
        String by = ApiContext.actor(ex);
        synchronized (RegulatoryReports.lock()) {
            Map<String, Object> rec = gated(api, ex, root, id, body);
            if (!RegulatoryReports.FAILED.equals(rec.get("status"))) throw wrongStatus(id, rec.get("status"), "failed");
            RegulatoryReports.audit(by, ApiContext.actorType(ex), "regulatory-report.retried", id + " retried by " + by, rec, null);
            submit(root, rec, by, ApiContext.actorType(ex));
        }
        return current(root, id);
    }

    /**
     * Deliver the pinned bytes to the pinned drop directory and record the outcome — {@code submitted} (the AUDIT row
     * {@code regulatory-report.submitted} is the submission log) or {@code failed} with the reason. Under the lock.
     */
    @SuppressWarnings("unchecked")
    static void submit(Path root, Map<String, Object> rec, String by, String byType) throws IOException {
        Map<String, Object> delivery = (Map<String, Object>) rec.get("delivery");
        String id = String.valueOf(rec.get("id"));
        try {
            String content = String.valueOf(rec.get("content"));
            if (!com.gamma.job.ApprovalFingerprint.sha256(content.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .equals(rec.get("contentSha256")))
                throw new IOException("the content does not match its pinned SHA-256");
            FileDrop.Delivered d = FileDrop.deliver(root, String.valueOf(delivery.get("dir")),
                    String.valueOf(delivery.get("fileName")), content, String.valueOf(rec.get("contentSha256")));
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("kind", delivery.get("kind"));
            s.put("file", d.file().toString());
            s.put("at", RegulatoryReports.now());
            s.put("alreadyPresent", d.alreadyPresent());
            rec.put("submission", s);
            rec.put("lastError", null);
            RegulatoryReports.transition(rec, RegulatoryReports.SUBMITTED, by);
            RegulatoryReports.save(root, rec);
            RegulatoryReports.audit(by, byType, "regulatory-report.submitted", id + " submitted to "
                    + d.file().getFileName() + " (sha256 " + rec.get("contentSha256") + ")", rec, null);
        } catch (IOException | RuntimeException failed) {
            rec.put("lastError", failed.getMessage());
            RegulatoryReports.transition(rec, RegulatoryReports.FAILED, by);
            RegulatoryReports.save(root, rec);
            RegulatoryReports.audit(by, byType, "regulatory-report.submission-failed", id + " was not submitted: "
                    + failed.getMessage(), rec, EventLevel.WARN);
        }
    }

    private static Map<String, Object> current(Path root, String id) throws IOException {
        synchronized (RegulatoryReports.lock()) {
            return RegulatoryReports.detail(RegulatoryReports.read(root, id));
        }
    }
}
