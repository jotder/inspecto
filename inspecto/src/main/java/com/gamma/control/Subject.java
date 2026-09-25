package com.gamma.control;

import java.util.Map;
import java.util.Set;

/**
 * The authenticated caller (W6, edition concern — {@code docs/EDITIONS.md} "Security direction").
 * Carries only the resolved grants — never raw JWT claims or role names, which stay internal to the
 * {@link Authenticator} that derived them (guideline 13: the contract speaks in capability verbs,
 * never roles). Set on the exchange as {@link ApiContext#ATTR_SUBJECT} once per request by
 * {@link ControlApi#dispatch}; absent entirely on Personal edition (no {@link Authenticator} present,
 * so nothing ever attaches one).
 *
 * <p><b>Data scopes (SEC-7d).</b> {@link #dataScopes()} carries the caller's resolved data-visibility
 * grants for case-type roles ("a fraud analyst sees fraud cases" — {@code rbac-groundwork.md} §4 Q2):
 * an operational object tagged with a {@code caseType} attribute is visible only when that value is in
 * the set. <b>{@code null} means unscoped</b> — every non-case-type role (Ops/Power/Super) and every
 * pre-scoping caller sees everything, unchanged; an <b>empty set</b> means "only untyped objects".
 *
 * <p><b>Attributes (ABAC A1).</b> {@link #attributes()} is the additive subject-attribute map policy
 * conditions bind as {@code subject.*} — IdP claims <em>allowlisted</em> in the space's
 * {@code roles.toon} {@code identity.attributeClaims} (never the raw token), populated by the
 * Standard/Enterprise {@link Authenticator}. Always empty in core and on every pre-A1 caller; nothing
 * capability-gated may ever read it (grants stay capability-verbs-only).
 *
 * <p><b>Email (ses-sns-adapter-design §7).</b> {@link #email()} is the IdP's <em>verified</em> email
 * claim ({@code email} with {@code email_verified: true}), or {@code null}. It is the ONLY address a
 * per-user email notification may go to — never a caller-supplied one, because an editable address
 * would let any user route notifications (security ones included) to an arbitrary mailbox.
 */
public record Subject(String id, Set<String> capabilities, Set<String> dataScopes,
                      Map<String, Object> attributes, String email) {

    public Subject {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        email = email == null || email.isBlank() ? null : email.trim();
    }

    /** No verified email claim — every pre-§7 caller. */
    public Subject(String id, Set<String> capabilities, Set<String> dataScopes, Map<String, Object> attributes) {
        this(id, capabilities, dataScopes, attributes, null);
    }

    /** The attribute-free form — every pre-A1 caller (attributes = empty). */
    public Subject(String id, Set<String> capabilities, Set<String> dataScopes) {
        this(id, capabilities, dataScopes, Map.of(), null);
    }

    /** The unscoped form (every non-case-type role) — capabilities only, {@code dataScopes = null}. */
    public Subject(String id, Set<String> capabilities) {
        this(id, capabilities, null, Map.of(), null);
    }

    /** Whether this caller's data visibility is restricted ({@link #dataScopes()} non-null). */
    public boolean scoped() {
        return dataScopes != null;
    }
}
