---
type: Architecture
title: Module taxonomy — axes, offerings, gates (target model)
description: How modules are classified for itemized distribution — three axes, Offerings, the Installed/Enabled/Permitted gates, removal semantics. Target model from MODULE-REORG-1; P0 only so far.
resource: docs/superpower/module-architecture-reorg-plan.md
tags: [architecture, modules, offering, editions, itemization]
timestamp: 2026-10-06T00:00:00Z
---

# Module taxonomy

> 🟢 **Status (2026-10-06): target model; P0, P1 (part), P2a, P2b, P4a (removal semantics, characterised + fixed) and P5a (test kit + RouteModule TCK) built.** Vocabulary is in `docs/GLOSSARY.md` §15.
> Built: the per-module manifest (`META-INF/inspecto/module.toon`, all 34 modules), the activator
> (`com.gamma.module` in `inspecto-util`) and `GET /modules`; the per-Space **Enabled** gate (`modules.toon`, `GET|PUT /settings/modules` — a route of a
> disabled module answers 404 `MODULE_DISABLED`, `/bootstrap` `features{}` and `GET /modules` `enabledInSpace` follow it).
> Not built: directory regroup; Offerings exist as a checked artifact only (see below). Decisions D-MR1…D-MR12 and phases P0–P7 live in
> [module-architecture-reorg-plan.md](../../superpower/module-architecture-reorg-plan.md) until they ship.

## Manifest format (`META-INF/inspecto/module.toon`)
```
---
id: ops
title: Case management and objects
buildRole: implementation      # foundation | contract | platform | implementation
offeringRole: optional         # base | optional | provider | internal
bindingTime: boot              # build | boot | space | run
provides:
  features[1]: ops             # must equal the code's RouteModule.featureIds() (guard-tested)
  contracts[3]: JobTypeProvider,MaintenanceTaskProvider,ObjectEngineProvider
requires:
  modules[1]: la-core          # only real runtime edges; empty sections are omitted
```
The leading `---` line separates manifests when a shade merges them (the processor's does). ⚠ TOON has no comment
syntax — the `#` notes above are for this page only; never put one in a manifest. A module is **INERT** when a
required module is absent or inert, or a required contract is provided by no active module; `GET /modules` reports
each module's state with the reasons, plus loader diagnostics.

## Three axes (a module is classified on all three)
| Axis | Values |
|---|---|
| Build role | Foundation · Contract · Platform module · Implementation |
| Offering role | Base · Optional · Provider · Internal |
| Binding time | Build · Boot · Space · Run |

## Rules that carry the design
- A module boundary exists only for: optional shipping, distinct third-party footprint, swappability, or test
  isolation. Otherwise use packages.
- **Availability is computed from what bound** (as `/bootstrap` `features{}` already does), never declared.
- Three gates, never inside feature code: **Installed → Enabled (per Space) → Permitted (RBAC)**.
- **No licence engine**; `entitlementKey` is reserved in the manifest only.
- Closed central registries become **contribution points**; `EventType` is the model to copy.
- Config naming an absent module loads **inert with a diagnostic** and is never dropped on save.

## Seams as they are today (P1 survey, 2026-10-06)
- Route modules: ~58 built-ins are an explicit `List.of(...)` in `ControlApi` (`:590-604`); optional modules are
  already discovered by `OptionalSpi.all(RouteModule.class)` (`:606-619`, fail-soft) and appended after the
  built-ins. Registration is **first-match in order**, so the built-in order is load-bearing.
- Six hand-written `Absent*Routes` stubs answer 503 for absent optional modules (second closed registry).
- A duplicate (METHOD, pattern) fails boot.

## Offerings (`offerings/<id>.toon`)
An Offering composes modules and content; an edition is an Offering that `includes` another. Built in P6a as a
**checked** artifact — the bundle and pom still derive from `tools/bundle-modules.mjs`, and
`tools/check-offerings.mjs` fails when an Offering disagrees with it, with the pom profile, or with the manifests.
```
id: professional
title: Professional
tier: professional
includes[1]: personal
modules[14]: security,notify-channels,backup,entity-list,la-graph,la-storage,la-core,la-api,geo-link,exchange,metrics,events,ops,agent
addons:
  linkAnalysis:
    status: built
    modules[5]: la-graph,la-storage,la-core,la-api,geo-link
  incidents:
    status: planned
    hostedBy[2]: engine,ops
```
`modules` use manifest ids; modules with `offeringRole` base or internal are always present and never listed.
`addons` status `built` names modules that exist today, `planned` names where the code lives until its P7 move.
`contentPacks[N]` lists Space template ids under `spaces/_templates`; `defaultSpaceSettings:` is an empty
placeholder. Includes union modules and add-ons. Not yet in CI. Same TOON rules as manifests (no `#` lines).

## Related
[Architecture layers](architecture-layers.md) · [Editions model](editions/editions-model.md)
