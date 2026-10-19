---
type: Reference
title: Attribute-based access policy — implementation spec
description: PolicyEngine evaluation, context binding, seeds, fail-closed rules and the PII_MASK feature provider.
resource: providers/inspecto-policy/
tags: [module, policy, abac, spec]
timestamp: 2026-10-19T00:00:00Z
---

# Attribute-based access policy — implementation spec

Package `com.gamma.policy`, two classes:

| Class | Registered in | Role |
|---|---|---|
| `PolicyEngine` | `providers/inspecto-policy/src/main/resources/META-INF/services/com.gamma.spi.auth.AccessDecider` | The policy decision point |
| `PolicyEditionFeatures` | `providers/inspecto-policy/src/main/resources/META-INF/services/com.gamma.etl.EditionFeatureProvider` | Declares `EditionFeatures.PII_MASK` |

Manifest: `providers/inspecto-policy/src/main/resources/META-INF/inspecto/module.toon` (`provides: AccessDecider`).

## One evaluator, three entry points

`decide`, `explain` and `simulate` all call the private `evaluate`, so the save-time lockout guard and the
"why denied?" explainer cannot drift from enforcement.

1. **Effective set** — authored policies overlaid on `SEED` by policy name (an authored policy named
   `space-isolation` replaces that seed; other seeds stay).
2. **Target** — empty `actions` / `resourceKinds` match anything; a `resourceKinds` target needs a resolved
   resource, so it never matches a route-level call.
3. **Condition** — the parsed `when` (`com.gamma.util.Conditions`) evaluated over the context.
4. **Combine** — first matching `deny` wins; else the last matching `allow`; else `ABSTAIN`.

`decide` stamps the matched policy name for the audit (`AccessDecider.matchedPolicy`); `explain` and
`simulate` never enforce, stamp or audit. `seededPolicies()` returns `SEED` for `GET /access/policies`.

## Context

| Prefix | Keys |
|---|---|
| `subject.` | `id`, `capabilities`, `dataScopes` (scoped subjects only), `roles` (held roles stamped by the authenticator), plus every claim allowlisted in `roles.toon` `identity.attributeClaims` |
| `env.` | `action` (`read` / `write` / `operate`), `route`, `space` (bound Space) |
| `resource.` | the row's attributes at row level; `resource.space` defaults to the bound Space |

## Seeds

```
space-isolation       deny when subject.space != null and env.space != null
                           and not (env.space == subject.space) and not canConfigureAccess
space-isolation-rows  deny when subject.space != null and resource.space != null
                           and not (resource.space == subject.space) and not canConfigureAccess
```

## Failure handling

* `AccessPolicyStore.effective(ex)` marked unreadable: `decide` returns `DENY` with matched policy
  `<policies-unreadable>` and logs a warning; `explain` returns the same verdict with an empty trace.
* Document validation (keys, effects, limits of 200 policies, 64 target values, 2000-character conditions,
  unknown references) lives in the core store, `platform/inspecto-access/src/main/java/com/gamma/access/AccessPolicyStore.java`.

## Tests

[tests-inventory](tests-inventory.md): engine unit tests plus real-HTTP enforcement, preview, lockout and
Investigation tests under `providers/inspecto-policy/src/test/java/com/gamma/control/`.
