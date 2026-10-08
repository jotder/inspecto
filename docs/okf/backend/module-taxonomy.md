---
type: Architecture
title: Module taxonomy — axes, offerings, gates (target model)
description: How modules are classified for itemized distribution — three axes, Offerings, the Installed/Enabled/Permitted gates, removal semantics. Built by MODULE-REORG-1: manifests, activator, gates, Offerings (checked artifact), thin-jar bundle; the P7 optional-module splits are still landing.
resource: docs/superpower/module-architecture-reorg-plan.md
tags: [architecture, modules, offering, editions, itemization]
timestamp: 2026-10-08T00:00:00Z
---

# Module taxonomy

> 🟡 **Status (2026-10-07): in flight; every phase P0..P7 has a shipped slice, none is finished** (phase table and remaining work: the plan header; open items are `MODULE-REORG-*` rows in `docs/BACKLOG.md`). Vocabulary is in `docs/GLOSSARY.md` §15.
> Built: the per-module manifest (`META-INF/inspecto/module.toon`, every module), the activator
> (`com.gamma.module` in `inspecto-util`) and `GET /modules`; the per-Space **Enabled** gate (`modules.toon`, `GET|PUT /settings/modules` — a route of a
> disabled module answers 404 `MODULE_DISABLED`, `/bootstrap` `features{}` and `GET /modules` `enabledInSpace` follow it).
> Directory regroup DONE 2026-10-07 (layout below). Offerings exist as a checked artifact only (see below). Decisions D-MR1…D-MR12 and phases P0–P7 live in
> [module-architecture-reorg-plan.md](../../superpower/module-architecture-reorg-plan.md) until they ship.

## Manifest format (`META-INF/inspecto/module.toon`)
```
---
id: ops
title: Case management and objects
buildRole: implementation      # foundation | contract | platform | implementation
offeringRole: optional         # base | optional | provider | internal
bindingTime: boot              # build | boot | space | run
absentMessage: "Operational objects are not installed in this bundle ..."   # optional: the 503 text when the module is absent
provides:
  features[1]: ops             # must equal the code's RouteModule.featureIds() (guard-tested)
  contracts[3]: JobTypeProvider,MaintenanceTaskProvider,ObjectEngineProvider
  storeFamilies[4]: OBJECTS,LINKS,NOTES,TAGS         # operational store families the module owns (StoreFamilyProvider)
  consequences[1]: create-incident                   # Consequence ids it registers (ConsequenceProvider)
  jobTypes[1]: objects.analytics                     # Job Type ids it registers (JobTypeProvider); the Space module gate's owner table (parity-tested)
  configKinds[3]: workflow,sla-policy,escalation-rule  # registry kinds the module owns (guard: a real roster kind, one owner)
  routes[2]: "GET /objects","GET /objects/([^/]+)"   # METHOD path, the exact regex registered, in registration order (parity-tested)
requires:
  modules[1]: la-core          # only real runtime edges; empty sections are omitted
```
The leading `---` line separates manifests when a shade merges them (the processor's does). ⚠ TOON has no comment
syntax — the `#` notes above are for this page only; never put one in a manifest. A module is **INERT** when a
required module is absent or inert, or a required contract is provided by no active module; `GET /modules` reports
each module's state with the reasons, plus loader diagnostics.

**`provides.routes` and known-modules (P3b).** An optional module declares its HTTP surface; a module-side parity test
(`ModuleRoutesParity`) keeps it equal to what the module registers. The processor's build copies EVERY module's manifest
(installed in this edition or not) to `META-INF/inspecto/known-modules/<id>.toon` + `index.txt` (`tools/KnownModules.java`, run
from `inspecto/pom.xml`), so a bundle that left a module out still answers its paths `503 CAPABILITY_UNAVAILABLE` naming the
module (`AbsentModuleRoutes`, text from `absentMessage`), and `GET /modules` lists it `not-installed`. The eight hand-written
`Absent*Routes` classes are gone: a new optional module's stubs, parity and OpenAPI paths need only its manifest.

## Three axes (a module is classified on all three)
| Axis | Values |
|---|---|
| Build role | Foundation · Contract · Platform module · Implementation |
| Offering role | Base · Optional · Provider · Internal |
| Binding time | Build · Boot · Space · Run |

## Directory layout (D-MR2, 2026-10-07 — directories only, artifactIds unchanged)
Group directories hold plain modules (no aggregator pom): the root pom lists `<module>platform/inspecto-engine</module>` etc.
Name a module to Maven by artifactId (`-pl :inspecto-engine`), never by path. `inspecto` (the product module, `package.ps1`) stays at the root.
| Directory | Modules |
|---|---|
| `spi/` | inspecto-audit-spi, inspecto-auth-spi, inspecto-http-spi |
| `platform/` | inspecto-api, -util, -config, -sql, -etl, -event, -workflow, -acquire, -entity-store, -engine |
| `features/` | inspecto-agent, -backup, -entity-list, -exchange, -intelligence, -observability, -ops, -reconciliation, -scoring |
| `la/` | inspecto-la-core, -la-api, -la-graph, -la-storage, -la-store-pg, -geo-link |
| `providers/` | inspecto-agent-hosted, -connectors, -connectors-kafka, -demo-auth, -geo-country, -notify-channels, -oidc, -policy, -secrets, -telecom-asn1, asn-parser (nested reactor, moves as a unit) |
`tools/regroup-modules.mjs` did the move (spent; kept as the record of what changed).

## Rules that carry the design
- A module boundary exists only for: optional shipping, distinct third-party footprint, swappability, or test
  isolation. Otherwise use packages.
- **Availability is computed from what bound** (as `/bootstrap` `features{}` already does), never declared.
- Three gates, never inside feature code: **Installed → Enabled (per Space) → Permitted (RBAC)**.
- **No licence engine**; `entitlementKey` is reserved in the manifest only.
- Closed central registries become **contribution points**; `EventType` is the model to copy.
- Config naming an absent module loads **inert with a diagnostic** and is never dropped on save.
- **Objects of an absent module's type load inert too (2026-10-08).** The object stores read `object_type` as text: a row whose type is no `ObjectType` of this build (a module removed, or the retired `ALERT`) is listed by `GET /objects` with `inert:true` and the diagnostic "type X is not installed/known: left untouched", readable by id, refused on every write (`409`), skipped by the SLA sweep, analytics and purge, and an `ObjectLink` endpoint kind is carried as text so its edges still load.
- **Removal and disable, as built (P4e).** The registry-kind roster (`ComponentStore.WRITABLE_TYPES`) is one engine list, so an
  import judges a kind the same on every install: an absent module's kinds (`risk-score`, `reconciliation`) are accepted as inert
  config, never dropped; what an import refuses (access config, governance kinds, suffix-scanned ops configs) it refuses everywhere
  (`ConfigKindOwnershipTest`). `provides.configKinds` / `jobTypes` declare ownership. `GET /modules` `inert{modulesToon, configKinds,
  jobs}` lists what the Space holds for an absent module, and an unhosted Job names it (`missingModule`). A module switched off in a
  Space (`modules.toon`) also stops its Jobs there: the fire is recorded `SKIPPED` ("switched off in this Space"), a manual trigger
  or replay answers 404 `MODULE_DISABLED`, the config is untouched and it resumes when re-enabled (`JobModuleGate`, `ModuleDisabledJobTest`).
  Periodic work is paused the same way (P4f): a module declares `provides.background` (ops: `sla-sweep`) and
  `provides.maintenanceTasks` (ops: `incident_purge`), and the owning tick asks the shared `ModuleGate` first - skipped for that
  Space, logged once per transition, resumed on re-enable; `GET /modules` lists the paused ids as `backgroundPaused`. A Run already
  started is not interrupted, and a module with no feature id (`backup`, `intelligence`) cannot be switched off (plan, *P4f as built*).

## Seams (current)
- Route modules: the built-ins are an explicit ordered list in `ControlApi`; optional modules are discovered by
  `OptionalSpi.all(RouteModule.class)` (fail-soft) and appended after. Registration is **first-match in order**, so the
  built-in order is load-bearing; a duplicate (METHOD, pattern) fails boot.
- Absent-module 503s are synthesised from the manifests (`provides.routes` + `absentMessage`, see above); no hand-written stub remains.
- Other open contribution points discovered the same way: `StoreFamilyProvider` (operational store families), `ConsequenceProvider` (Decision Kernel Consequences), `HostBootHook` (a module's boot-time host installs), `LinkedSubjectProvider`.

<details><summary>History: the P1 survey (2026-10-06), superseded</summary>

At P1 the ~58 built-ins were an explicit `List.of(...)` in `ControlApi` (`:590-604`) with optional modules appended
(`:606-619`), and six hand-written `Absent*Routes` stubs answered 503 for absent optional modules (a second closed
registry). Those stubs were replaced by manifest synthesis in P3b.
</details>

## Offerings (`offerings/<id>.toon`)
An Offering composes modules and content; an edition is an Offering that `includes` another. Built in P6a as a
**checked** artifact — the bundle and pom still derive from `tools/bundle-modules.mjs`, and
`tools/check-offerings.mjs` fails when an Offering disagrees with it, with the pom profile, or with the manifests.
```
id: professional
title: Professional
tier: professional
includes[1]: personal
modules[21]: oidc,secrets,geo-country,connectors-kafka,telecom-asn1,notify-channels,backup,entity-list,la-graph,la-storage,la-core,la-api,geo-link,exchange,observability,ops,case-management,action-requests,reconciliation,scoring,agent
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
placeholder. Includes union modules and add-ons. Same TOON rules as manifests (no `#` lines). `node tools/check-offerings.mjs` is the guard. The example above is abridged from `offerings/professional.toon` (the file also lists every other add-on).

**`posture:` (P6b, optional).** Four verifiable deployment facts per Offering, not inherited: `authMode` none|oidc, `eventsBackend`
default|parquet, `objectsBackend` default|postgres, `postgresSidecar` absent|bundled. `check-offerings` holds them to that closed
vocabulary and to what the launcher scenarios (`tools/check-launchers.mjs`) and `classpath(edition)` prove. **Generator:**
`tools/render-offerings-matrix.mjs` writes the module x edition matrix, the add-on table, the posture table and the jar counts
into the marked block of `docs/EDITIONS.md` (`--check` in CI); transport, secrets, compliance and the capability rows stay
hand-authored there.

## Packaging (thin jars, P3d-P3f)
Detail and as-built notes: [plan §6 P3d, P3e, P3f](../../superpower/module-architecture-reorg-plan.md).
- `tools/offering-classpath.mjs` resolves the Offering (includes + the `requires.modules` closure) and asserts it equals what `tools/bundle-modules.mjs` ships; it writes the bundle's `modules.list` (one jar per line, classpath order, processor first) and `edition.properties` (`edition=Professional`; `variant=demo` for a demo build). Every launcher reads `modules.list`; none hand-keeps a jar list.
- The first-party core ships as 14 thin jars plus `inspecto.jar` (the processor and third-party code), listed in `core.list`; optional modules ship one jar each, in `modules.list` order after the core.
- One CycloneDX SBOM per shipped jar under `sbom/`, beside the combined edition SBOM (`tools/sbom-modules.mjs --verify`).

## Related
[Architecture layers](architecture-layers.md) · [Editions model](editions/editions-model.md)
