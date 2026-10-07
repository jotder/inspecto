package com.gamma.control;

import com.gamma.api.PublicApi;
import com.gamma.util.Conditions;

import java.util.List;
import java.util.Set;

/**
 * Access Policies — authorable allow/deny statements over subject/resource/environment attributes
 * (ABAC A2, {@code docs/superpower/rbac-abac-plan.md} §4; vocabulary {@code docs/GLOSSARY.md} §1-A —
 * ⛔ never bare "Rule"). Authored as a per-space settings doc {@value #FILE} (same discipline as
 * {@code roles.toon}): each policy is {@code {name, effect: allow|deny, target: {actions?,
 * resourceKinds?}, when?}} where {@code when} is a {@link Conditions} expression over
 * {@code subject.* / resource.* / env.*} attribute maps.
 *
 * <p>This class is the CONTRACT only — the vocabulary and the parsed shapes the {@link AccessDecider} SPI
 * speaks. Storing, validating and serving the documents is {@code AccessPolicyStore} (core, any edition can
 * read them); <em>evaluation and enforcement</em> are the Enterprise policy engine's job (A3's
 * {@code PolicyEngine implements AccessDecider} in {@code inspecto-policy}).
 *
 * <p><b>Fail-closed</b> (enforced by {@code AccessPolicyStore}): conditions are parsed at load time, so a doc that no longer parses — TOON
 * damage or a condition edit that breaks the grammar — marks the whole doc unreadable; the engine
 * must treat that as deny-loudly, never as "no policies" (mirrors {@link Roles}' suspended grants).
 * Authoring-time violations are 422s, so an unparseable doc can only arise from on-disk edits.
 */
@PublicApi(since = "4.0.0")
public final class AccessPolicies {
    private AccessPolicies() {}

    /** The environment action verbs a policy target may name (plan §4 A1). */
    public static final Set<String> ACTIONS = Set.of("read", "write", "operate");

    /**
     * The resource kinds the row-level PEPs pass ({@code RowScope.visible} callers): the operational
     * object types lower-cased ({@code ObjectRoutes}, {@code AnnotationTargets}) and
     * {@code investigation} ({@code InvestigationRoutes}). There is no registry, so this list is it —
     * a {@code resourceKinds} value outside it can never match (F4, a save-time warning).
     */
    public static final Set<String> RESOURCE_KINDS = Set.of("alert", "incident", "case", "task", "investigation");


    /**
     * One parsed policy. {@code actions}/{@code resourceKinds} empty ⇒ the target dimension is
     * unconstrained (matches every action / every resource kind); {@code when} blank ⇒ the policy
     * applies whenever the target matches ({@link #condition} is constant-true then). The
     * {@link #condition} is pre-parsed — evaluation never re-parses.
     */
    public record Policy(String name, String effect, Set<String> actions, Set<String> resourceKinds,
                         String when, Conditions.Condition condition) {
        public Policy {
            actions = Set.copyOf(actions);
            resourceKinds = Set.copyOf(resourceKinds);
        }

        public boolean deny() {
            return "deny".equals(effect);
        }
    }

    /** The authored doc + its readability (unreadable ⇒ the engine denies loudly, never skips).
     *  {@code error} names what made it unreadable — the policy and the failed check (null when readable). */
    public record Doc(List<Policy> policies, boolean unreadable, String error) {
        static final Doc ABSENT = new Doc(List.of(), false, null);

        public Doc {
            policies = List.copyOf(policies);
        }
    }

    /** A save-time finding that does not refuse the doc (policy-authoring-ux-design.md §4): the policy,
     *  a stable {@code code}, and a human message. */
    public record Warning(String policy, String code, String message) {}
}
