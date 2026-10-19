---
type: Feature
title: "Access policy — spec"
description: "Developer reference for platform/inspecto-access: the eight classes, their request-attribute seams, the per-Space config files they read and the gates they enforce."
tags: [access, spec]
timestamp: 2026-10-19T00:00:00Z
---

# Access policy — spec

Module `platform/inspecto-access`, package `com.gamma.access` (descriptor
[module.toon](../../../../../platform/inspecto-access/src/main/resources/META-INF/inspecto/module.toon): `buildRole: platform`,
`offeringRole: base`, `bindingTime: build`). It stores and serves tables; it holds no authenticator. Sign-in and
the ABAC engine plug in from providers (see [architecture-overview](architecture-overview.md)).

## Classes

| Class | Responsibility |
|---|---|
| [`Roles`](../../../../../platform/inspecto-access/src/main/java/com/gamma/access/Roles.java) | Role → capabilities + data scopes table. Built-in `SEED` merged with the Space's `roles.toon` (`Roles.FILE`); `effective(...)`, `validate(...)`, `attributeClaims(...)`. `KNOWN_CAPABILITIES` is derived from `CapabilityManifest`. |
| [`CapabilityManifest`](../../../../../platform/inspecto-access/src/main/java/com/gamma/access/CapabilityManifest.java) | The route → capability map (`ENTRIES`), the documented `EXEMPTIONS` (method, pattern, category, reason) and `PENDING_OPERATOR_CALLS` (empty). `capabilityFor` and `isExempt` match on the registered pattern string. |
| [`AccessGrants`](../../../../../platform/inspecto-access/src/main/java/com/gamma/access/AccessGrants.java) | Capabilities denied to a set of roles by Access Profiles: reads `registry/access-profiles/role-<name>.toon` and `registry/access-catalog/catalog.toon` under the config root. |
| [`AccessPolicyStore`](../../../../../platform/inspecto-access/src/main/java/com/gamma/access/AccessPolicyStore.java) | Loads, validates, lints and writes the Space's `access-policies.toon`. Subject attribute keys `id, capabilities, dataScopes, roles`; environment keys `action, route, space`. `requireLoadableUnder` refuses a config root whose policies do not load. |
| [`RowScope`](../../../../../platform/inspecto-access/src/main/java/com/gamma/access/RowScope.java) | Row-level ABAC read filter: only a `DENY` hides a row; with no `AccessDecider` on the classpath every row stays visible. A row `DENY` is audited, a row `ALLOW` is not (it would fire per row). |
| [`ComponentAccess`](../../../../../platform/inspecto-access/src/main/java/com/gamma/access/ComponentAccess.java) | Per-component `owner` / `shares` view, edit and delete checks; stamps the owner on create. Held roles arrive via the `inspecto.access.heldRoles` request attribute, written by the authenticator. |
| [`AuditTrail`](../../../../../platform/inspecto-access/src/main/java/com/gamma/access/AuditTrail.java) | Records mutating calls, authentication outcomes, access denials and policy decisions as events; `classify(method, path)` names the action and category. |
| [`WriteGates`](../../../../../platform/inspecto-access/src/main/java/com/gamma/access/WriteGates.java) | Shared write gates: `requireWriteRoot` (no write root → 503), `safeName`, `jail` / `jailToAllowedRoots` (path containment), `conflictIf` (409). |

## Seams

- **Config root per request.** `Roles.configRoot(ex, root)` binds the Space's config root (ControlApi's
  authenticate stage writes it); `AccessGrants`, `AccessPolicyStore` and `Roles` read their files from it.
  With no root bound the seed applies and nothing is denied.
- **Request attributes, not the exchange map.** Every seam goes through `RequestAttrs.attr` (request-scoped).
- **Caching.** The TOON readers cache parsed content keyed on the file text; covered by
  `AccessFileCacheSameTickTest` (see [tests-inventory](tests-inventory.md)).
- Public classes carry `@PublicApi(since = "4.0.0")` — intent only, see
  [API stability](../api/plan-api-stability.md).

## Files on disk (per Space config root)

`roles.toon` · `access-policies.toon` · `registry/access-profiles/role-<name>.toon` ·
`registry/access-catalog/catalog.toon`. The routes that edit them: [api-access](api-access.md).
