package com.gamma.access;

import com.sun.net.httpserver.HttpExchange;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.gamma.control.ApiException;
import com.gamma.control.Authenticator;
import com.gamma.control.ErrorCodes;
import com.gamma.control.RequestAttrs;
import com.gamma.control.Subject;
import static com.gamma.util.Values.trimOrEmpty;

/**
 * Component sharing RBAC (R3, {@code docs/superpower/rbac-abac-plan.md} §3): decides a request's
 * access to one registry component from the component's optional sharing envelope —
 * {@code owner: <subject id>} plus {@code shares: [{subjectType: role|user, subjectId, access:
 * view|edit}]} — additive TOON keys inside the component content, absent on every existing
 * component.
 *
 * <p><b>Semantics.</b> A component without a {@code shares} key behaves exactly as today
 * ({@code owner} alone is provenance, stamped at create time, not a restriction). Once
 * {@code shares} is present (even empty) the component is restricted: the owner and holders of
 * {@code canConfigureAccess} (the governance escape hatch — an orphaned owner id must never brick a
 * component) have full access, a matching share grants {@code view} or {@code edit}, and everyone
 * else gets a 404 indistinguishable from absence (the SEC-7d contract — no existence leak).
 * Deleting a restricted component is owner-only; changing the envelope itself is owner-only on any
 * component that declares an owner. With no {@link Subject} attached (Personal edition, fail-open)
 * everything is allowed, unchanged.
 *
 * <p><b>Role matching.</b> The {@link Subject} is capabilities-only by design (guideline 13 — role
 * names never leave the authenticator or reach the SPA), so {@code subjectType: role} shares match
 * against {@link #ATTR_HELD_ROLES}, the lowercased <em>recognised</em> (table-backed) role names the
 * security module's authenticator stamps on the exchange at token-validation time — a server-internal
 * seam like {@link Roles#ATTR_CONFIG_ROOT}, never serialized to a response. An {@link Authenticator}
 * that does not stamp it simply matches no role shares (fail-closed); {@code subjectType: user}
 * shares match the IdP {@code sub} carried as {@link Subject#id()} (§8 Q4: opaque id, no user
 * directory needed).
 */
public final class ComponentAccess {
    private ComponentAccess() {}

    /** Exchange attribute carrying the authenticated subject's recognised role names (a lowercased
     *  {@code Set<String>}), stamped by the security module's {@link Authenticator} alongside the
     *  {@link Subject}. Server-internal only. Absent ⇒ no {@code subjectType: role} share matches. */
    public static final String ATTR_HELD_ROLES = "inspecto.access.heldRoles";

    /** The {@link #ATTR_HELD_ROLES} write seam for the security module's {@link Authenticator} — the
     *  one out-of-package writer. Request-scoped via {@code ApiContext.attr}, never the JDK's exchange
     *  map (shared across in-flight requests on pre-JDK-26 runtimes — see RequestAttrs.REQUEST_SCOPES). */
    public static void heldRoles(HttpExchange ex, Set<String> roles) {
        RequestAttrs.attr(ex, ATTR_HELD_ROLES, roles);
    }

    public static final String OWNER = "owner";
    public static final String SHARES = "shares";

    private static final int MAX_SHARES = 100;

    /** Access levels: NONE hides the component (404), VIEW reads, EDIT writes content,
     *  OWN additionally deletes and manages the envelope. */
    private static final int NONE = 0, VIEW = 1, EDIT = 2, OWN = 3;

    /** Whether this request may see {@code content} at all (list filtering + read routes). */
    public static boolean canView(HttpExchange ex, Map<String, Object> content) {
        return level(ex, content) >= VIEW;
    }

    /** The three answers of {@link #canViewAs}: a caller-less read cannot always say yes or no. */
    public enum AsOwner {
        /** The owner id (or an unrestricted component, or a held capability) grants view. */
        ALLOWED,
        /** Restricted, and no owner / {@code user} share names this id and no {@code role} share exists. */
        DENIED,
        /** Restricted, no owner / {@code user} share names this id, but a {@code role} share exists. A role is
         *  resolved only from the authenticator's token-time stamp ({@link #ATTR_HELD_ROLES}) and is never stored,
         *  so off-request it can neither be confirmed nor excluded. The caller MUST refuse (fail closed). */
        ROLE_ONLY
    }

    /**
     * LD-1 (standing detection, {@code docs/archived-documents/plans-archive/la-live-detection-design.md} §6): the Subject-less twin of
     * {@link #canView}, for a caller-less run (an Alert sweep) acting for a RECORDED id. Mirrors {@link #level} for the
     * owner id and {@code user} shares only; {@code capabilities} lets a caller honour {@code canConfigureAccess} and
     * a standing-detection sweep passes none, so it can never exceed the owner's live authority. Pure: no exchange,
     * no I/O. A {@code role} share is the one thing it cannot decide, hence {@link AsOwner#ROLE_ONLY}.
     */
    public static AsOwner canViewAs(String subjectId, Set<String> capabilities, Map<String, Object> content) {
        if (content == null) return AsOwner.DENIED;                       // nothing to judge: never ALLOWED
        if (!content.containsKey(SHARES)) return AsOwner.ALLOWED;         // unrestricted, as level()
        String id = trimOrEmpty(subjectId);
        String owner = trimOrEmpty(content.get(OWNER));
        if (!id.isEmpty() && id.equals(owner)) return AsOwner.ALLOWED;
        if (capabilities != null && capabilities.contains(Roles.CAN_CONFIGURE_ACCESS)) return AsOwner.ALLOWED;
        boolean roleShare = false;
        if (content.get(SHARES) instanceof List<?> shares) {
            for (Object o : shares) {
                if (!(o instanceof Map<?, ?> share)) continue;            // malformed entry grants nothing
                String subjectType = trimOrEmpty(share.get("subjectType"));
                if ("user".equals(subjectType) && !id.isEmpty() && id.equals(trimOrEmpty(share.get("subjectId"))))
                    return AsOwner.ALLOWED;
                if ("role".equals(subjectType)) roleShare = true;
            }
        }
        return roleShare ? AsOwner.ROLE_ONLY : AsOwner.DENIED;
    }

    /** 404 (indistinguishable from absence) unless the request may view the component. */
    public static void requireView(HttpExchange ex, String type, String id, Map<String, Object> content) {
        if (!canView(ex, content))
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no " + type + " component '" + id + "'");
    }

    /** {@link #requireView} then 403 unless the request may edit (owner, edit share, or unrestricted). */
    static void requireEdit(HttpExchange ex, String type, String id, Map<String, Object> content) {
        requireView(ex, type, id, content);
        if (level(ex, content) < EDIT)
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED,
                    type + " component '" + id + "' is shared view-only");
    }

    /** Delete gate: unrestricted components keep today's behavior; a restricted (shared) component
     *  may only be deleted by its owner / an access admin — an edit share is not ownership. */
    public static void requireDelete(HttpExchange ex, String type, String id, Map<String, Object> content) {
        requireView(ex, type, id, content);
        if (content.containsKey(SHARES) && level(ex, content) < OWN)
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED,
                    "only the owner may delete shared " + type + " component '" + id + "'");
    }

    /** Create-path shaping: validate the envelope (422) and stamp {@code owner} from the
     *  authenticated subject when absent (provenance; no restriction until shares are added).
     *  OWNER-IS-CREATOR (operator, 2026-09-28): a body {@code owner} naming anyone but the caller is a 403
     *  unless the caller holds canConfigureAccess or canAdminister, since owner-routed alerting would
     *  otherwise let a creator address a rule's alerts to anyone. */
    public static Map<String, Object> onCreate(HttpExchange ex, Map<String, Object> content) {
        Map<String, Object> out = new LinkedHashMap<>(content);
        if (RequestAttrs.attr(ex, RequestAttrs.ATTR_SUBJECT) instanceof Subject s && !out.containsKey(OWNER))
            out.put(OWNER, s.id());
        validate(out);
        requireOwnerIsCaller(ex, out.get(OWNER));
        return out;
    }

    /** 403 when {@code owner} names someone other than the authenticated caller, unless the caller holds
     *  canConfigureAccess or canAdminister. No subject (Personal): no check. */
    private static void requireOwnerIsCaller(HttpExchange ex, Object owner) {
        if (!(RequestAttrs.attr(ex, RequestAttrs.ATTR_SUBJECT) instanceof Subject s)) return;
        if (s.capabilities().contains(Roles.CAN_CONFIGURE_ACCESS) || s.capabilities().contains(Roles.CAN_ADMINISTER)) return;
        if (owner != null && !trimOrEmpty(owner).equals(s.id()))
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED,
                    "'owner' must be the caller ('" + s.id() + "'); only an access admin may name another owner");
    }

    /**
     * Update-path shaping: enforce edit access against the <em>current</em> envelope, carry the
     * envelope forward when the body omits it (a plain content save must never strip protection),
     * validate (422), and reject envelope changes from anyone but the owner / an access admin (403).
     * Returns the content to persist.
     */
    public static Map<String, Object> onUpdate(HttpExchange ex, String type, String id,
                                        Map<String, Object> current, Map<String, Object> incoming) {
        requireEdit(ex, type, id, current);
        Map<String, Object> merged = new LinkedHashMap<>(incoming);
        if (!merged.containsKey(OWNER) && current.containsKey(OWNER)) merged.put(OWNER, current.get(OWNER));
        if (!merged.containsKey(SHARES) && current.containsKey(SHARES)) merged.put(SHARES, current.get(SHARES));
        validate(merged);
        boolean envelopeChanged = !Objects.equals(current.get(OWNER), merged.get(OWNER))
                || !Objects.equals(current.get(SHARES), merged.get(SHARES));
        if (envelopeChanged && !ownsEnvelope(ex, current))
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED,
                    "only the owner may change 'owner'/'shares' on " + type + " component '" + id + "'");
        // OWNER-IS-CREATOR (operator, 2026-09-28): a first claim of an owner-less component claims it for the caller.
        if (trimOrEmpty(current.get(OWNER)).isEmpty() && !Objects.equals(current.get(OWNER), merged.get(OWNER)))
            requireOwnerIsCaller(ex, merged.get(OWNER));
        return merged;
    }

    // ── decision ─────────────────────────────────────────────────────────────────

    private static int level(HttpExchange ex, Map<String, Object> content) {
        if (!(RequestAttrs.attr(ex, RequestAttrs.ATTR_SUBJECT) instanceof Subject s)) return OWN;  // Personal: fail-open
        String owner = trimOrEmpty(content.get(OWNER));
        boolean ownerMatch = !owner.isEmpty() && owner.equals(s.id());
        boolean admin = s.capabilities().contains(Roles.CAN_CONFIGURE_ACCESS);
        if (!content.containsKey(SHARES)) return ownerMatch || admin ? OWN : EDIT;  // unrestricted
        if (ownerMatch || admin) return OWN;
        int best = NONE;
        Set<String> held = heldRoles(ex);
        if (content.get(SHARES) instanceof List<?> shares) {
            for (Object o : shares) {
                if (!(o instanceof Map<?, ?> share)) continue;   // malformed entry grants nothing
                String subjectType = trimOrEmpty(share.get("subjectType"));
                String subjectId = trimOrEmpty(share.get("subjectId"));
                boolean match = ("user".equals(subjectType) && subjectId.equals(s.id()))
                        || ("role".equals(subjectType) && held.contains(subjectId.toLowerCase(Locale.ROOT)));
                if (match) best = Math.max(best, "edit".equals(trimOrEmpty(share.get("access"))) ? EDIT : VIEW);
            }
        }
        return best;
    }

    /** May this request manage the envelope? True with no subject, for an access admin, for the
     *  declared owner — and for anyone when no owner is declared yet (first claim on a legacy doc). */
    private static boolean ownsEnvelope(HttpExchange ex, Map<String, Object> current) {
        if (!(RequestAttrs.attr(ex, RequestAttrs.ATTR_SUBJECT) instanceof Subject s)) return true;
        if (s.capabilities().contains(Roles.CAN_CONFIGURE_ACCESS)) return true;
        String owner = trimOrEmpty(current.get(OWNER));
        return owner.isEmpty() || owner.equals(s.id());
    }

    /** The stamped recognised role names, lowercased; empty when unstamped. The read seam paired with
     *  {@link #heldRoles(HttpExchange, Set)} — deciders read through here, never the JDK exchange map. */
    public static Set<String> heldRoles(HttpExchange ex) {
        if (!(RequestAttrs.attr(ex, ATTR_HELD_ROLES) instanceof Set<?> roles)) return Set.of();
        Set<String> out = new java.util.LinkedHashSet<>();
        for (Object r : roles) if (r != null) out.add(String.valueOf(r).toLowerCase(Locale.ROOT));
        return out;
    }

    // ── validation (write-path 422s — stored malformed entries just grant nothing) ──

    /** Validate the sharing envelope inside {@code content}; throws {@link ApiException} 422. */
    static void validate(Map<String, Object> content) {
        if (content.containsKey(OWNER) && trimOrEmpty(content.get(OWNER)).isEmpty())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'owner' must be a non-blank subject id");
        if (!content.containsKey(SHARES)) return;
        if (!(content.get(SHARES) instanceof List<?> shares))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'shares' must be a list of {subjectType, subjectId, access}");
        if (shares.size() > MAX_SHARES)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "too many shares (max " + MAX_SHARES + ")");
        for (Object o : shares) {
            if (!(o instanceof Map<?, ?> share))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "every share must be an object {subjectType, subjectId, access}");
            String subjectType = trimOrEmpty(share.get("subjectType"));
            if (!"role".equals(subjectType) && !"user".equals(subjectType))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "share 'subjectType' must be 'role' or 'user', got '" + subjectType + "'");
            if (trimOrEmpty(share.get("subjectId")).isEmpty())
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "share 'subjectId' is required");
            String access = trimOrEmpty(share.get("access"));
            if (!"view".equals(access) && !"edit".equals(access))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "share 'access' must be 'view' or 'edit', got '" + access + "'");
        }
    }
}
