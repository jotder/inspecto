---
type: TestsInventory
title: Attribute-based access policy — tests inventory
item: module.policy
description: Generated list of the test classes for module.policy, with how to run them.
tags: [tests, module]
---

# Attribute-based access policy — tests inventory (`module.policy`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `providers/inspecto-policy/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.policy`](../../../../../product-map/module/policy.toon).

6 test class(es): 1 unit, 5 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl providers/inspecto-policy
mvn -o test -pl providers/inspecto-policy -Dtest=ControlApiInvestigationPolicyTest,ControlApiPolicyEnforcementTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `ControlApiInvestigationPolicyTest` | `com.gamma.control` | real-HTTP control-plane test | `providers/inspecto-policy/src/test/java/com/gamma/control/ControlApiInvestigationPolicyTest.java` | LA-20 / D-E7 on the Advanced edition, end to end: with {@code inspecto-policy} AND {@code inspecto-geo-link} on |
| `ControlApiPolicyEnforcementTest` | `com.gamma.control` | real-HTTP control-plane test | `providers/inspecto-policy/src/test/java/com/gamma/control/ControlApiPolicyEnforcementTest.java` | The Advanced edition end-to-end (ABAC A3): with {@code inspecto-policy} on the classpath the |
| `ControlApiPolicyLockoutTest` | `com.gamma.control` | real-HTTP control-plane test | `providers/inspecto-policy/src/test/java/com/gamma/control/ControlApiPolicyLockoutTest.java` | F7 — self-lockout ({@code docs/archived-documents/plans-archive/policy-authoring-ux-design.md} §1, S2): a draft that would |
| `ControlApiPolicyPreviewTest` | `com.gamma.control` | real-HTTP control-plane test | `providers/inspecto-policy/src/test/java/com/gamma/control/ControlApiPolicyPreviewTest.java` | {@code POST /access/policies/preview} over real HTTP (policy-authoring-ux-design.md S4, D6/D7): the |
| `PolicyEditionFeaturesTest` | `com.gamma.policy` | unit | `providers/inspecto-policy/src/test/java/com/gamma/policy/PolicyEditionFeaturesTest.java` | SEC-08 (operator 2026-09-02, built 2026-10-06): PII masking is Advanced-only. This module ships only in the |
| `PolicyEngineTest` | `com.gamma.policy` | real-HTTP control-plane test | `providers/inspecto-policy/src/test/java/com/gamma/policy/PolicyEngineTest.java` | The Advanced PDP's own semantics (ABAC A3), exercised through {@link PolicyEngine#decide} on a |
