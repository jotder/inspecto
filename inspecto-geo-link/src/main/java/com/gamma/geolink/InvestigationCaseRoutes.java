package com.gamma.geolink;

import com.gamma.control.AnnotationTargets;
import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.ErrorCodes;
import com.gamma.control.RouteModule;
import com.gamma.control.Subject;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.objects.ObjectAccess;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * LA-24 — sharing an Investigation with a <b>Case team</b> (operator decision 2026-09-30, plan §4 D-U10).
 *
 * <ul>
 *   <li>{@code GET    /inv/investigations/{id}/case} — the link and what it grants the caller (owner or member).</li>
 *   <li>{@code PUT    /inv/investigations/{id}/case} — body {@code {caseRef}}; owner-only, and the caller must see
 *       the Case.</li>
 *   <li>{@code DELETE /inv/investigations/{id}/case} — owner-only.</li>
 * </ul>
 *
 * <p><b>The link is OPTIONAL</b> and lives OUTSIDE the sealed header ({@code case-link.json}), so it can be set at
 * create or later and removed without touching evidence. An Investigation with no link works exactly as before.
 *
 * <p><b>Independence.</b> This module names no {@code inspecto-ops} type. It reaches Cases only through core's
 * {@link ObjectAccess} seam ({@code CollectorService.objects()}), which the optional ops module fills via
 * {@code ServiceLoader} — empty on a bundle without it. Absent the seam a link can still be stored, but it grants
 * nothing and every response says why; sharing falls back to owner-only.
 *
 * <p><b>What a link grants</b> ({@link #grants}, decided LIVE on every read — nothing is cached or recorded as a
 * grant): READ-ONLY access (log, Working Set, Dossier, measures, this link) to a <b>member</b> of the linked Case —
 * its owner or assignee (a Case carries no team list) — while the Case exists, is a {@code case}, is not closed,
 * and is visible to that member (SEC-7d scope + row policy, {@link AnnotationTargets#objectVisibleTo}). Writes stay
 * owner-only. ⛔ This is NOT a policy ALLOW: D-E7's PDP still runs after it and can only narrow; a DENY hides the
 * Investigation from everyone, Case members included. A non-member still gets a 404 indistinguishable from absence.
 */
public final class InvestigationCaseRoutes implements RouteModule {

    private static final Pattern CASE_REF = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    @Override
    public void register(ApiContext api) {
        // ⚠ String LITERALS on purpose — CapabilityManifestTest's scanner matches only a literal argument.
        api.get("/inv/investigations/([^/]+)/case", (e, m) -> status(api, e, m.group(1)));
        api.put("/inv/investigations/([^/]+)/case", ApiContext.withCapability("canManageIncidents",
                (e, m) -> link(api, e, m.group(1), api.body(e))));
        api.delete("/inv/investigations/([^/]+)/case", ApiContext.withCapability("canManageIncidents",
                (e, m) -> unlink(api, e, m.group(1))));
    }

    /** {@code GET …/case} — gates: {@link InvestigationRoutes#openForRead} (503 · 422 · 403 · 404 non-member). */
    private static Object status(ApiContext api, HttpExchange ex, String id) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.openForRead(api, ex, id);
        return describe(api, ex, inv);
    }

    /**
     * {@code PUT …/case} — gates: {@code canManageIncidents} 403 → {@link InvestigationRoutes#open} owner-only
     * (503 · 422 · 403 · 404) → a missing/unsafe {@code caseRef} 422 → a Case the caller cannot see 404 → a closed
     * Case 409 → write the link atomically.
     */
    private static Object link(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.open(api, ex, id);
        Map<String, Object> record = linkRecord(api, ex, ApiContext.str(body, "caseRef"));
        if (record == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'caseRef'");
        write(ex, inv.store(), id, record);
        return describe(api, ex, inv);
    }

    /** {@code DELETE …/case} — gates: {@code canManageIncidents} 403 → owner-only {@link InvestigationRoutes#open}. */
    private static Object unlink(ApiContext api, HttpExchange ex, String id) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.open(api, ex, id);
        String prior = caseRef(inv.store(), id);
        if (inv.store().deleteCaseLink(id))
            emit(ex, EventType.LINK_INVESTIGATION_CASE_UNLINKED, "link.investigation.case.unlinked",
                    "link.investigation.case.unlinked — " + id + " from " + prior,
                    b -> b.attr("investigationId", id).attr("caseId", String.valueOf(prior)));
        return describe(api, ex, inv);
    }

    // ── shared with InvestigationRoutes (create) ───────────────────────────────────────────────────────

    /**
     * Validate a {@code caseRef} for linking by the caller, BEFORE anything is written: null when none was given;
     * 422 unsafe; with the ops seam present, 404 unless it names a Case the caller can see, 409 when it is closed.
     * Without the seam it cannot be checked — the link is stored {@code verified:false} and grants nothing.
     */
    static Map<String, Object> linkRecord(ApiContext api, HttpExchange ex, String caseRef) {
        if (caseRef == null) return null;
        if (!CASE_REF.matcher(caseRef).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'caseRef' must match " + CASE_REF.pattern());
        Optional<ObjectAccess> objects = api.service().objects();
        if (objects.isPresent()) {
            Map<String, Object> c = objects.get().summary(caseRef).orElse(null);
            if (c == null || !"case".equals(c.get("kind")) || !AnnotationTargets.objectVisibleTo(ex, c))
                throw new ApiException(404, ErrorCodes.NOT_FOUND, "no case '" + caseRef + "'");
            if (!Boolean.FALSE.equals(c.get("closed")))
                throw new ApiException(409, ErrorCodes.CONFLICT, "case '" + caseRef + "' is closed — a closed Case shares nothing");
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("caseRef", caseRef);
        r.put("linkedBy", ApiContext.actor(ex));
        r.put("linkedAt", Instant.now().toString());
        r.put("verified", objects.isPresent());
        return r;
    }

    /** Store a validated link record and audit it. */
    static void write(HttpExchange ex, SnapshotStore store, String id, Map<String, Object> record) throws IOException {
        store.writeCaseLink(id, ApiContext.JSON.writeValueAsString(record));
        Object caseRef = record.get("caseRef");
        emit(ex, EventType.LINK_INVESTIGATION_CASE_LINKED, "link.investigation.case.linked",
                "link.investigation.case.linked — " + id + " → " + caseRef,
                b -> b.attr("investigationId", id).attr("caseId", String.valueOf(caseRef))
                        .attr("verified", String.valueOf(record.get("verified"))));
    }

    /**
     * Whether {@code subject} — not the owner — may READ this Investigation as a member of its linked Case. False
     * (fail closed) with no link, no ops seam, an unknown/non-Case/closed Case, a Case the subject cannot see, or a
     * subject who is neither the Case's owner nor its assignee. Never throws on a bad link file.
     */
    static boolean grants(ApiContext api, HttpExchange ex, SnapshotStore store, String id, Subject subject) {
        try {
            String caseRef = caseRef(store, id);
            if (caseRef == null) return false;
            ObjectAccess objects = api.service().objects().orElse(null);
            if (objects == null) return false;
            Map<String, Object> c = objects.summary(caseRef).orElse(null);
            if (c == null || !"case".equals(c.get("kind")) || !Boolean.FALSE.equals(c.get("closed"))) return false;
            boolean member = subject.id().equals(c.get("owner")) || subject.id().equals(c.get("assignee"));
            return member && AnnotationTargets.objectVisibleTo(ex, c);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static String caseRef(SnapshotStore store, String id) throws IOException {
        String raw = store.readCaseLink(id);
        if (raw == null) return null;
        @SuppressWarnings("unchecked") Map<String, Object> m = ApiContext.JSON.readValue(raw, Map.class);
        Object ref = m.get("caseRef");
        return ref == null ? null : String.valueOf(ref);
    }

    /** The link, whether the caller reads as owner or Case member, and why sharing is (not) in effect. */
    private static Map<String, Object> describe(ApiContext api, HttpExchange ex, InvestigationRoutes.Inv inv)
            throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("investigationId", inv.id());
        String raw = inv.store().readCaseLink(inv.id());
        @SuppressWarnings("unchecked") Map<String, Object> link = raw == null ? null : ApiContext.JSON.readValue(raw, Map.class);
        out.put("caseRef", link == null ? null : link.get("caseRef"));
        out.put("linkedBy", link == null ? null : link.get("linkedBy"));
        out.put("linkedAt", link == null ? null : link.get("linkedAt"));
        Optional<Subject> s = ApiContext.subject(ex);
        boolean owner = s.isEmpty() || Objects.equals(s.get().id(), inv.header().get("owner"));
        out.put("access", owner ? "owner" : "case-member");
        out.put("readOnly", !owner);
        boolean ops = api.service().objects().isPresent();
        out.put("sharing", link != null && ops);
        out.put("reason", link == null ? "not linked to a Case — owner-only"
                : !ops ? "Case management (inspecto-ops) is not installed — the link is stored but grants nothing; owner-only"
                : "members (owner or assignee) of an open Case they can see get READ-ONLY access; writes stay owner-only");
        return out;
    }

    /** Best-effort audit (LA-04 pattern): an audit failure never fails the write. */
    private static void emit(HttpExchange ex, String type, String action, String message,
                             UnaryOperator<Event.Builder> attrs) {
        try {
            Event.Builder b = Event.builder(type).source("inv").message(message)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action(action).actionCategory("analysis");
            EventLog.current().emit(attrs.apply(b));
        } catch (RuntimeException ignored) {
            // best effort — the link is already written
        }
    }
}
