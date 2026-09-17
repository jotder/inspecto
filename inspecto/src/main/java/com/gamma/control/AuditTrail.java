package com.gamma.control;

import com.gamma.event.AuditAttrs;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.sun.net.httpserver.HttpExchange;

/**
 * The security audit trail's capture point — turns a state-changing Control API request into one
 * append-only audit {@link Event} ({@code type = }{@link EventType#AUDIT}). Called once from
 * {@link ControlApi#dispatch} after a request resolves, so it covers every current and future
 * mutating route from a single seam (no per-handler wiring) and the engine core stays
 * identity-agnostic — actor/IP/User-Agent are read only here, at the HTTP edge.
 *
 * <h3>What is audited</h3>
 * <ul>
 *   <li>Successful {@code POST}/{@code PUT}/{@code DELETE} that {@link #classify classify}s as a real
 *       mutation (create/update/delete/trigger/…); diagnostic POSTs ({@code /test}, {@code /preview},
 *       {@code /dry-run}, {@code /validate}, {@code /assist/*}) are skipped as non-mutating.</li>
 *   <li>{@code GET .../export} — data-export actions (Category B).</li>
 *   <li>{@link #accessDenied} — a non-GET request to a forbidden/unknown route (404) or a disallowed
 *       method on a read-only route (405): the auth-free analogue of a 401/403 attempt.</li>
 * </ul>
 * Auth-gated events (login/MFA/password, true 401/403) are out of scope until the security module lands.
 *
 * @since 4.0.0
 */
final class AuditTrail {

    private AuditTrail() {}

    /** A classified action: its dotted name and coarse category, or {@code null} when not auditable. */
    record Action(String name, String category) {}

    /**
     * Record an audit event for a resolved request, if it is auditable. Never throws — auditing must
     * not disturb the response (the underlying {@link EventLog#emit} already swallows sink failures).
     *
     * @param path the route path with the {@code /api} and {@code /spaces/{id}} prefixes already stripped
     */
    static void record(HttpExchange ex, String method, String path, int status) {
        try {
            Action action = classify(method, path);
            if (action == null) return;
            String actor = ApiContext.actor(ex);
            String targetType = resource(path);
            String targetId = targetId(path);
            // Say so when the action was REFUSED. The action name is the one that was attempted, so a
            // 4xx/5xx must not read as an accomplished mutation in the one log an investigator trusts.
            String outcome = status >= 400 ? " (refused, HTTP " + status + ")" : "";
            // The capability this request was PRIVILEGED by — present iff a capability check ran and
            // passed on the way in (ApiContext.requireCapability, Subject attached). Its absence on an
            // AUDIT row therefore means "an ordinary mutation, or Personal where nothing is checked";
            // its presence is what lets "every privileged write, by actor, by capability, in the window"
            // be one /audit/search query instead of a hand-built join (compliance plan step 4b).
            Object capability = ApiContext.attr(ex, ApiContext.ATTR_CAPABILITY);
            var event = Event.builder(EventType.AUDIT)
                    .source("audit")
                    .message(actor + " " + action.name() + (targetId == null ? "" : " " + targetId) + outcome)
                    .actor(actor).actorType(ApiContext.actorType(ex))
                    .action(action.name()).actionCategory(action.category())
                    .target(targetType, targetId)
                    .ip(ApiContext.ip(ex)).userAgent(ApiContext.userAgent(ex))
                    .attr(AuditAttrs.HTTP_METHOD, method)
                    .attr(AuditAttrs.HTTP_PATH, path)
                    .attr(AuditAttrs.HTTP_STATUS, status);
            if (capability != null) event.attr(AuditAttrs.CAPABILITY, capability);
            EventLog.current().emit(event);
        } catch (RuntimeException ignore) {
            // best effort — the audit trail must never break the request
        }
    }

    /**
     * Record an AUTHENTICATION event (2026-09-17): a session opened by code exchange, refreshed, or ended,
     * and the refused forms of the first two. Until this existed the trail held authorization decisions
     * and mutations only, and BACKLOG §6 carried "sign-in/sign-out are not audited" as working-as-designed
     * with a disclosure — but a disclosure is not a control, and "show me the sign-ins" is the first
     * question a CC6 auditor asks of an audit log. The IdP still owns the credential check (MFA, password,
     * lockout); what is recorded here is what THIS server did with the result: minted, rotated or ended a
     * session, or refused to. No token or code ever reaches the row — only the outcome, IP and user agent.
     *
     * @param action  {@code auth.exchange} / {@code auth.refresh} / {@code auth.logout}
     * @param ok      whether the server granted it; a refusal is an {@link EventType#ACCESS_DENIED} row
     * @param status  the HTTP status the caller received
     */
    static void authentication(HttpExchange ex, String action, boolean ok, int status) {
        try {
            String actor = ApiContext.actor(ex);
            String path = ex.getRequestURI().getPath();
            EventLog.current().emit(Event.builder(ok ? EventType.AUDIT : EventType.ACCESS_DENIED)
                    .source("audit")
                    .message(actor + " " + action + (ok ? "" : " (refused, HTTP " + status + ")"))
                    .actor(actor).actorType(ApiContext.actorType(ex))
                    .action(action).actionCategory("authentication")
                    .ip(ApiContext.ip(ex)).userAgent(ApiContext.userAgent(ex))
                    .attr(AuditAttrs.HTTP_METHOD, "POST")
                    .attr(AuditAttrs.HTTP_PATH, path)
                    .attr(AuditAttrs.HTTP_STATUS, status));
        } catch (RuntimeException ignore) {
            // best effort — the audit trail must never break the request
        }
    }

    /** Record a refused attempt. Two callers, with deliberately different scopes: an unknown or

     *  method-mismatched route (404/405) is recorded for <em>non-GET</em> only, because a bare GET there is
     *  usually an SPA deep link rather than an API attempt; an authentication or authorization refusal
     *  (401/403) on a route that <em>did</em> match is recorded for <em>every</em> method, GET included —
     *  the path is unambiguously an API call, and a refused read is a record worth keeping
     *  (`AUDIT-REFUSAL-GAP-1`). Never throws. */
    static void accessDenied(HttpExchange ex, String method, String path, int status) {
        try {
            String actor = ApiContext.actor(ex);
            // The capability a 403 was refused FOR, when a capability check is what refused it. Set by
            // ApiContext.requireCapability; absent on a 401 (authentication, no capability was reached)
            // and on a policy DENY (which records itself through policyDecision). Until 2026-09-15 this
            // name reached only the exception message and never the audit row — so the one question an
            // investigator asks of a refusal, "denied WHAT?", had no answer in the log (plan step 4a).
            Object capability = ApiContext.attr(ex, ApiContext.ATTR_CAPABILITY);
            var event = Event.builder(EventType.ACCESS_DENIED)
                    .source("audit")
                    .message(actor + " access.denied " + method + " " + path + " (" + status + ")"
                            + (capability == null ? "" : " missing " + capability))
                    .actor(actor).actorType(ApiContext.actorType(ex))
                    .action("access.denied").actionCategory("authorization")
                    .ip(ApiContext.ip(ex)).userAgent(ApiContext.userAgent(ex))
                    .attr(AuditAttrs.HTTP_METHOD, method)
                    .attr(AuditAttrs.HTTP_PATH, path)
                    .attr(AuditAttrs.HTTP_STATUS, status);
            if (capability != null) event.attr(AuditAttrs.CAPABILITY, capability);
            EventLog.current().emit(event);
        } catch (RuntimeException ignore) {
            // best effort
        }
    }

    /**
     * Record an access-policy decision (ABAC A5): a policy {@code DENY} (a 403 at the route level or a
     * hidden row at the row level) or a policy-matched {@code ALLOW} at the route level. Emitted as an
     * {@link EventType#ACCESS_DENIED} ({@code access.denied}) / {@link EventType#AUDIT}
     * ({@code access.granted}) event carrying the matched policy name. Never throws.
     *
     * @param granted      true for a policy-matched allow, false for a deny
     * @param abacAction   the ABAC action verb the decision covered ({@code read}/{@code write}/{@code operate})
     * @param route        the effective route path (prefixes stripped)
     * @param resourceType the resolved row's kind at the row level, else null (route-level decision)
     * @param resourceId   the resolved row's id at the row level, else null
     * @param policy       the matched Access Policy name (or a fail-closed marker), null when unnamed
     */
    static void policyDecision(HttpExchange ex, boolean granted, String abacAction, String route,
                               String resourceType, String resourceId, String policy) {
        try {
            String actor = ApiContext.actor(ex);
            String verb = granted ? "access.granted" : "access.denied";
            EventLog.current().emit(Event.builder(granted ? EventType.AUDIT : EventType.ACCESS_DENIED)
                    .source("audit")
                    .message(actor + " " + verb + " " + abacAction + " " + route
                            + (policy == null ? "" : " (policy " + policy + ")"))
                    .actor(actor).actorType(ApiContext.actorType(ex))
                    .action(verb).actionCategory("authorization")
                    .target(resourceType, resourceId)
                    .ip(ApiContext.ip(ex)).userAgent(ApiContext.userAgent(ex))
                    .attr(AuditAttrs.HTTP_PATH, route)
                    .attr(AuditAttrs.ABAC_ACTION, abacAction)
                    .attr(AuditAttrs.POLICY, policy));
        } catch (RuntimeException ignore) {
            // best effort — auditing must never break the request
        }
    }

    /**
     * Map a resolved request to an auditable {@link Action}, or {@code null} when it carries no audit
     * value. Pure (no I/O) so it is unit-testable. The {@code path} has prefixes stripped.
     */
    static Action classify(String method, String path) {
        if (path == null || path.isEmpty()) return null;
        // Export actions are GET but auditable (Category B); nothing else GET is.
        if ("GET".equals(method)) {
            return path.endsWith("/export") ? new Action(resource(path) + ".exported", "export") : null;
        }
        if (!"POST".equals(method) && !"PUT".equals(method) && !"DELETE".equals(method)) return null;
        // Non-mutating POSTs: diagnostics / previews / assist chat, and the user's own notification-feed
        // housekeeping (read/delete) — not part of the security audit trail.
        if (path.endsWith("/test") || path.endsWith("/preview") || path.endsWith("/dry-run")
                || path.equals("/validate") || path.startsWith("/assist")
                || path.startsWith("/notifications")) return null;

        String last = lastSegment(path);
        String category = "DELETE".equals(method) ? "destructive"
                : (path.startsWith("/config") || last.equals("write")) ? "configuration"
                : "data_mutation";
        String verb = switch (method) {
            case "DELETE" -> "deleted";
            case "PUT" -> "updated";
            default -> switch (last) {     // POST
                case "trigger" -> "triggered";
                case "pause" -> "paused";
                case "resume" -> "resumed";
                case "reprocess" -> "reprocessed";
                case "approve" -> "approved";     // POST /exchange/grants/{id}/approve
                case "deny" -> "denied";
                case "revoke" -> "revoked";
                case "notify" -> "notified";      // POST /collectors/{id}/notify (ACQ-6 push discovery)
                case "ack" -> "acknowledged";
                case "resolve" -> "resolved";
                case "transition" -> "transitioned";
                case "import" -> "imported";
                case "write" -> "written";
                case "evaluate" -> "evaluated";
                case "delete" -> "deleted";       // e.g. /events/views/{name}/delete
                default -> "created";
            };
        };
        return new Action(resource(path) + "." + verb, category);
    }

    /**
     * First path segment, singularised — with {@code runs} mapped back to {@code pipeline} (the ingest
     * resource is exposed at {@code /runs}, but the audited entity is a pipeline config):
     * {@code /runs/x} → {@code pipeline}; {@code /} → {@code service}.
     */
    private static String resource(String path) {
        String[] seg = path.split("/");
        String head = seg.length > 1 && !seg[1].isEmpty() ? seg[1] : "service";
        if (head.equals("runs")) return "pipeline";   // /runs is the ingest route; the audited entity is a pipeline
        return head.endsWith("s") ? head.substring(0, head.length() - 1) : head;
    }

    /** The {@code {id}} after the resource (second segment), or {@code null} when the path is a collection. */
    private static String targetId(String path) {
        String[] seg = path.split("/");
        return seg.length > 2 && !seg[2].isEmpty() ? seg[2] : null;
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
