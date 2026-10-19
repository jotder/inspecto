---
type: Concept
title: Attribute-based access policy — architecture
description: How the policy engine plugs into the control plane's two enforcement points through the AccessDecider SPI.
resource: providers/inspecto-policy/
tags: [module, policy, abac, architecture, spi, editions]
timestamp: 2026-10-19T00:00:00Z
---

# Attribute-based access policy — architecture

```
request -> authenticate (oidc / demo-auth) -> Subject
        -> route PEP (ControlApi authorize stage) --decide(route level)--> PolicyEngine
               DENY  -> 403, audited access.denied
               ALLOW / ABSTAIN -> capability, Access Profile and sharing gates (unchanged)
        -> handler -> RowScope filter --decide(row level, resourceKind + resource)--> PolicyEngine
               DENY  -> row hidden (404 / filtered)
```

* **Seam** — `AccessDecider` (`spi/inspecto-auth-spi/src/main/java/com/gamma/spi/auth/AccessDecider.java`),
  found by `ServiceLoader`. The core ships no decider: without this jar every decision is skipped and Basic /
  Standard behave byte-identically. Dropping the jar on the classpath is what turns policy on.
* **Policy store** — per Space, `access-policies.toon` in the Space's config root, parsed and cached by the core
  (`platform/inspecto-access/src/main/java/com/gamma/access/AccessPolicyStore.java`); the engine only reads
  `AccessPolicyStore.effective(ex)` for the request's bound Space.
* **Defense in depth** — `ALLOW` and `ABSTAIN` both fall through to the existing gates; only `DENY` changes
  the outcome.
* **Audit** — the engine stamps the matched policy; the core writes the audit entry
  ([events](../event/index.md)).
* **Second contract** — `EditionFeatureProvider` declares `PII_MASK`, so the pipeline `mask` Step is legal
  only in bundles that carry this module ([etl](../etl/index.md)); elsewhere it is refused with
  `ERR_EDITION_FEATURE` at save and at run.
* **Editions** — staged from Advanced (`offerings/advanced.toon`) and in Preview; built by the
  `edition-advanced` Maven profile.

Full security model: [admin-guide-auth-security](../access/admin-guide-auth-security.md).
