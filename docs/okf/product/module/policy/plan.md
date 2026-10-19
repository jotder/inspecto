---
type: Plan
title: Attribute-based access policy — plan of record
description: ABAC workstream A (A1-A5), policy operability and authoring guards, and PII masking — what module.policy owes, what shipped, what is left.
resource: providers/inspecto-policy/
tags: [module, policy, abac, plan]
timestamp: 2026-10-19T00:00:00Z
---

# Attribute-based access policy — plan of record

**Requirement:** Advanced deployments decide access from subject, resource and environment attributes, not
roles alone, with per-tenant Space isolation and an audit of every policy decision. The core stays auth-free
and consults an edition-supplied decider (`AccessDecider`, owned by [auth-spi](../auth-spi/index.md)).
Design of record: `docs/archived-documents/plans-archive/rbac-abac-plan.md` (workstream A) and, for authoring,
`docs/archived-documents/plans-archive/policy-authoring-ux-design.md`.

## Built

| Slice | What | Where |
|---|---|---|
| A1 | Allowlisted identity claims on the Subject | [oidc spec](../oidc/spec.md) |
| A2 | Condition grammar and per-Space `access-policies.toon` | `platform/inspecto-access/src/main/java/com/gamma/access/AccessPolicyStore.java` |
| A3 | Policy engine at the route and row enforcement points, deny-overrides | `providers/inspecto-policy/src/main/java/com/gamma/policy/PolicyEngine.java` |
| A4 (SPC-5) | Seeded `space-isolation` and `space-isolation-rows` denies | `PolicyEngine.SEED` |
| A5 | `access.denied` / `access.granted` audit naming the matched policy | `AccessDecider.matchedPolicy` |
| Operability | Seed visibility on `GET /access/policies`, `GET /access/explain` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| Authoring guards | Nine failure modes guarded at save, incl. `would-lock-out`; impact preview | same file, `POST /access/policies/preview` |
| SEC-08 | `PII_MASK` edition feature: the `mask` Step runs only where this module ships (built 2026-10-06) | `providers/inspecto-policy/src/main/java/com/gamma/policy/PolicyEditionFeatures.java` |
| LA-20 | Investigation Working Sets filtered by policy (`resourceKind: investigation`) | `providers/inspecto-policy/src/test/java/com/gamma/control/ControlApiInvestigationPolicyTest.java` |

## Decided

* A policy `allow` never bypasses a capability gate (deliberate tightening of the plan's combining order).
* An unreadable policy document denies every decision until fixed, never "no policies".
* Space isolation engages only when a `space` home-Space claim is mapped, and exempts `canConfigureAccess`.

## Left

No open BACKLOG row targets this module. Related open security work (masking of `/bi/query`,
`BI-QUERY-MASKING-1`) lives in the core, see [BACKLOG](../../../../BACKLOG.md).
