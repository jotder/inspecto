package com.gamma.control;

import com.gamma.access.AccessPolicyStore;
import com.gamma.access.Roles;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether anyone could approve a held item ({@code none-eligible | unknown | ok}) - the reading a Pending Change, the
 * Space's approver roster and an Action Request all carry. It lives in the core (MODULE-REORG-P7) because the Pending
 * Change hold and {@link ApproverRoster} need it with the optional Action Requests module ABSENT; it was a static of
 * {@code ActionRequestRoutes} before. Never changes a four-eyes decision - a {@code none-eligible} item stays pending
 * (fail-closed); this only says so.
 *
 * <ul>
 *   <li>{@code none-eligible}: no role in the Space's table grants the capability (deny grants applied - every
 *       edition), or the Authenticator enumerates its principals (Demo) and every holder is a maker; and with no
 *       Authenticator (Personal): no Subject exists, so no one can decide.</li>
 *   <li>{@code ok}: an enumerated non-maker holds it - or, when the Authenticator cannot enumerate (OIDC), the Space's
 *       {@link ApproverRoster} names a group or a non-maker user.</li>
 *   <li>{@code unknown}: enumerating failed, or every enumerated non-maker holder is data-scoped (deciding also needs
 *       the linked object visible to them, which is not evaluable without their request).</li>
 * </ul>
 */
public final class ApproverCheck {

    public static final String NONE_ELIGIBLE = "none-eligible", UNKNOWN = "unknown", OK = "ok";

    private ApproverCheck() {
    }

    /** Last "approver check failed" log per root: at most one line per root per {@link #FAILURE_LOG_EVERY_MS}. */
    private static final ConcurrentHashMap<Path, Long> CHECK_FAILURE_LOGGED = new ConcurrentHashMap<>();
    private static final long FAILURE_LOG_EVERY_MS = 10 * 60_000L;

    /** {@link #compute} that never throws: an unreadable directory (a corrupt demo-users.toon) is "cannot tell". */
    public static String check(Path root, Set<Object> makers, String capability, boolean needsVisible) {
        try {
            return compute(root, makers, capability, needsVisible);
        } catch (RuntimeException e) {
            long now = System.currentTimeMillis();
            Long last = CHECK_FAILURE_LOGGED.get(root);
            if ((last == null || now - last >= FAILURE_LOG_EVERY_MS)
                    && (last == null ? CHECK_FAILURE_LOGGED.putIfAbsent(root, now) == null
                                     : CHECK_FAILURE_LOGGED.replace(root, last, now)))
                org.slf4j.LoggerFactory.getLogger(ApproverCheck.class)
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
}
