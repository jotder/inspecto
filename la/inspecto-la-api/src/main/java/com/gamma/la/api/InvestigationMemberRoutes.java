package com.gamma.la.api;

import com.gamma.la.core.InvestigationVersionConflictException;
import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.ErrorCodes;
import com.gamma.control.RouteModule;
import com.gamma.control.Subject;
import com.gamma.audit.Event;
import com.gamma.audit.EventLog;
import com.gamma.la.core.InvestigationMembers;
import com.gamma.la.core.InvestigationMembers.Entry;
import com.gamma.la.core.InvestigationMembers.Op;
import com.gamma.la.core.InvestigationMembers.Role;
import com.gamma.la.core.LinkEventTypes;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * D7-1 — who may act on an Investigation (D19; design {@code la-separation-d7-design.md} §9). Membership is an
 * append-only record beside the Investigation ({@code members.jsonl}, {@link InvestigationMemberStore}); the creator is
 * the implicit first lead, and an Investigation with no record is owner-only exactly as before.
 *
 * <ul>
 *   <li>{@code GET  /inv/investigations/{id}/members} — the current roles and the history; any member reads.</li>
 *   <li>{@code POST /inv/investigations/{id}/members} — body {@code {subject, role}}: grant (or change) a role; lead only.</li>
 *   <li>{@code POST /inv/investigations/{id}/members/revoke} — body {@code {subject}}; lead only.</li>
 * </ul>
 *
 * <p>Gates (mutating routes): {@code canManageIncidents} 403 → write root 503 → 422 id → NON-MEMBER 404 (the same body
 * as an unknown id) → a member who is not a lead 403 (a member already knows the Investigation exists, so this leaks
 * nothing) → R3 / Enterprise PDP 404 → 422 body → the last lead cannot be revoked or demoted 422 → append. The lead
 * check, made again UNDER the Investigation's lock against the roles as they stand, is the real gate; the capability is the
 * Case-work precedent every Investigation write follows.
 */
public final class InvestigationMemberRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        // ⚠ String LITERALS on purpose — CapabilityManifestTest's scanner matches only a literal argument.
        api.get("/inv/investigations/([^/]+)/members", (e, m) -> list(api, e, m.group(1)));
        api.post("/inv/investigations/([^/]+)/members", ApiContext.withCapability("canManageIncidents",
                (e, m) -> grant(api, e, m.group(1), api.body(e))));
        api.post("/inv/investigations/([^/]+)/members/revoke", ApiContext.withCapability("canManageIncidents",
                (e, m) -> revoke(api, e, m.group(1), api.body(e))));
    }

    private static Object list(ApiContext api, HttpExchange ex, String id) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.openAsMember(api, ex, id);
        return describe(inv, ex);
    }

    private static Object grant(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.openAsMember(api, ex, id);
        requireLead(ex, inv);
        String subject = ApiContext.str(body, "subject");
        if (!InvestigationMembers.validSubject(subject))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'subject', a single-line Subject id of 1..200 characters");
        Role role = Role.parse(ApiContext.str(body, "role")).orElseThrow(() ->
                new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'role' must be one of lead, analyst, reviewer"));
        for (int attempt = 1; ; attempt++) {   // the last-lead check and the append are one decision over ONE read of the list
            List<Entry> seen = InvestigationMemberStore.read(inv.store(), inv.id());
            Map<String, Role> current = InvestigationMemberStore.fold(inv.header().get("owner"), seen);
            requireLead(ex, current);
            if (current.get(subject) == role) return describe(inv, ex);   // already so: nothing to record
            refuseLastLead(current, Op.GRANT, subject, role);
            try {
                Entry e = InvestigationMemberStore.append(inv.store(), inv.id(), seen.size(), Instant.now().toString(), ApiContext.actor(ex), subject, role, Op.GRANT);
                emit(ex, LinkEventTypes.LINK_INV_MEMBER_GRANTED, "link.investigation.member.granted",
                        "link.investigation.member.granted — " + id + " " + subject + " " + role.wire(), id, e);
                break;
            } catch (InvestigationVersionConflictException lost) {
                if (attempt >= InvestigationMemberStore.MAX_ATTEMPTS)
                    throw new ApiException(409, ErrorCodes.CONFLICT_STALE_VERSION, lost.getMessage());
            }
        }
        return describe(inv, ex);
    }

    private static Object revoke(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.openAsMember(api, ex, id);
        requireLead(ex, inv);
        String subject = ApiContext.str(body, "subject");
        if (!InvestigationMembers.validSubject(subject))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'subject', a single-line Subject id of 1..200 characters");
        for (int attempt = 1; ; attempt++) {
            List<Entry> seen = InvestigationMemberStore.read(inv.store(), inv.id());
            Map<String, Role> current = InvestigationMemberStore.fold(inv.header().get("owner"), seen);
            requireLead(ex, current);
            Role held = current.get(subject);
            if (held == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "'" + subject + "' is not a member of investigation '" + id + "'");
            refuseLastLead(current, Op.REVOKE, subject, held);
            try {
                Entry e = InvestigationMemberStore.append(inv.store(), inv.id(), seen.size(), Instant.now().toString(), ApiContext.actor(ex), subject, held, Op.REVOKE);
                emit(ex, LinkEventTypes.LINK_INV_MEMBER_REVOKED, "link.investigation.member.revoked",
                        "link.investigation.member.revoked — " + id + " " + subject + " " + held.wire(), id, e);
                break;
            } catch (InvestigationVersionConflictException lost) {
                if (attempt >= InvestigationMemberStore.MAX_ATTEMPTS)
                    throw new ApiException(409, ErrorCodes.CONFLICT_STALE_VERSION, lost.getMessage());
            }
        }
        return describe(inv, ex);
    }

    private static void refuseLastLead(Map<String, Role> current, Op op, String subject, Role role) {
        Optional<String> why = InvestigationMembers.refusal(current, op, subject, role);
        if (why.isPresent()) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, why.get());
    }

    /** Every membership change is a lead's; an authenticated member who is not a lead is told so (403), not hidden. */
    private static void requireLead(HttpExchange ex, InvestigationRoutes.Inv inv) throws IOException {
        requireLead(ex, InvestigationMemberStore.roles(inv.store(), inv.id(), inv.header().get("owner")));
    }

    private static void requireLead(HttpExchange ex, Map<String, Role> roles) {
        Optional<Subject> s = ApiContext.subject(ex);
        if (s.isPresent() && !roles.getOrDefault(s.get().id(), Role.ANALYST).canManageMembers())
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "only a lead of this Investigation may grant or revoke members");
    }

    private static Map<String, Object> describe(InvestigationRoutes.Inv inv, HttpExchange ex) throws IOException {
        List<Entry> history = InvestigationMemberStore.read(inv.store(), inv.id());
        Map<String, Role> roles = InvestigationMembers.fold(String.valueOf(inv.header().get("owner")), history);
        List<Map<String, Object>> members = new ArrayList<>();
        roles.forEach((s, r) -> members.add(row("subject", s, "role", r.wire())));
        List<Map<String, Object>> hist = new ArrayList<>();
        for (Entry e : history)
            hist.add(row("seq", e.seq(), "ts", e.ts(), "actor", e.actor(), "subject", e.subject(), "role", e.role().wire(),
                    "op", e.op().wire()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("investigationId", inv.id());
        out.put("owner", inv.header().get("owner"));
        out.put("members", members);
        out.put("history", hist);
        out.put("you", ApiContext.subject(ex).map(s -> roles.get(s.id())).map(Role::wire).orElse(null));
        return out;
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** Best-effort audit (LA-04 pattern), emitted only AFTER the append: structured attrs only, never free text. */
    private static void emit(HttpExchange ex, String type, String action, String message, String id, Entry e) {
        try {
            UnaryOperator<Event.Builder> attrs = b -> b.attr("investigationId", id).attr("subject", e.subject())
                    .attr("role", e.role().wire()).attr("actor", e.actor());
            Event.Builder b = Event.builder(type).source("inv").message(message)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action(action).actionCategory("analysis");
            EventLog.current().emit(attrs.apply(b));
        } catch (RuntimeException ignored) {
            // best effort — the membership change is already recorded
        }
    }
}
