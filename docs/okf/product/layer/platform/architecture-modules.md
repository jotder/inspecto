---
type: Architecture
title: Modules
description: How the code is divided into Maven modules - the module list (derived from the root pom and the module manifests), the three classification axes, Offerings, the Installed / Enabled / Permitted gates, packaging, and the reasoning and playbook behind the reactor split.
resource: pom.xml
tags: [architecture, modules, reactor, maven, offering, editions, itemization]
timestamp: 2026-11-12T00:00:00Z
---

# Modules

> **Status:** current as of 2026-11-12, checked against the code.

This is the one current page on how the code is divided into modules: which modules exist, how each is
classified, how they are composed into editions and shipped, and why the reactor has this shape. For the whole
system see [architecture.md](architecture.md).

The module model was built by the module reorganisation (`MODULE-REORG-1`, phases P0 to P7, closed 2026-10-18).
Every module carries a manifest, the host activates modules from those manifests, a Space can switch an optional
module off, and Offerings decide what each edition ships. Three board rows remain open in `docs/BACKLOG.md`:
`MODULE-REORG-P3-THIN-JARS` (release signing needs the operator's keystore), `MODULE-REORG-P4-3` (a disabled
module's in-flight work is not cancelled) and `MODULE-REORG-P7-INCIDENTS` (one dated deletion). The decisions
(D-MR1 to D-MR12, declined items, deliberate exceptions) are in
[spec-module-reorg-decisions.md](spec-module-reorg-decisions.md); the traps are in
[spec-module-reorg-gotchas.md](spec-module-reorg-gotchas.md). Vocabulary: `docs/GLOSSARY.md` §15.

## 1. The module list

**Where this section comes from, and how to re-derive it.** It is read from two places only: the root `pom.xml`
(the default `<modules>` list and the three `edition-*` profile lists) and every
`src/main/resources/META-INF/inspecto/module.toon`. To
rebuild the tables: read the `<module>` entries of the root pom, with comments stripped, once for the default
list and once per profile; for each directory read the `artifactId` from its `pom.xml` and `id`, `buildRole`,
`offeringRole` and `bindingTime` from its manifest (`git ls-files` filtered to `META-INF/inspecto/module.toon`
lists them all). The "ships from" column is the lowest Offering that resolves the module:
`node tools/offering-classpath.mjs --edition <basic|standard|advanced|preview> --print` prints an edition's jars.
`tools/reactor-modules.mjs` is the helper the guard tools use to read the same `<module>` entries.

**Counts (2026-11-12).**

- The root pom names 48 module directories: 29 in the default list, and 19 more that only an edition profile
  adds. 47 of them are Inspecto modules (parent `inspecto-parent`); the other one is
  `providers/asn-parser/asn-decoders`, a separate reactor with its own parent that is aggregated here so that
  `asn-facade` builds in the same pass.
- Profiles: `edition-standard` adds 17 modules; `edition-advanced` and `edition-preview` each add 19 (the same
  list: the 17 plus `inspecto-policy` and `inspecto-la-store-pg`). There is no profile for Basic: Basic is the
  default build.
- There are 48 manifests: one per Inspecto module, plus `asn-facade` (id `asn-decoders`) inside the nested reactor.
- By build role: 8 foundation, 3 contract, 7 platform, 30 implementation. By offering role: 17 base, 19 optional,
  12 provider, none internal. By binding time: 18 build, 30 boot (no module manifest uses `space` or `run`).

**Directory rule (D-MR2).** A module's directory is `<group>/<artifactId>`; the groups are `spi/`, `platform/`,
`features/`, `la/` and `providers/`. Group directories hold plain modules and no aggregator pom. Two exceptions:
the product module is the root-level directory `inspecto` (artifactId `inspecto-processor`), and the nested ASN.1
reactor sits under `providers/asn-parser/`. Name a module to Maven by artifactId (`-pl :inspecto-engine`), never
by path.

**Reading the tables.** *Reactor* says when Maven builds the module: `default` (every build), `standard+` (the
`edition-standard`, `edition-advanced` and `edition-preview` profiles) or `advanced+` (`edition-advanced` and
`edition-preview`). *Ships from* is the lowest edition whose bundle carries the jar. The two differ on purpose:
some modules are in the default reactor so that their tests always run, but ship only from a higher edition.

### `spi/` (3) and the product module

| artifactId | Manifest id | Build role | Offering role | Binding | Reactor | Ships from |
|---|---|---|---|---|---|---|
| `inspecto-audit-spi` | `audit-spi` | contract | base | build | default | every edition |
| `inspecto-auth-spi` | `auth-spi` | contract | base | build | default | every edition |
| `inspecto-http-spi` | `http-spi` | contract | base | build | default | every edition |
| `inspecto-processor` (directory `inspecto`) | `processor` | platform | base | build | default | every edition |

### `platform/` (13)

| artifactId | Manifest id | Build role | Offering role | Binding | Reactor | Ships from |
|---|---|---|---|---|---|---|
| `inspecto-api` | `api` | foundation | base | build | default | every edition |
| `inspecto-util` | `util` | foundation | base | build | default | every edition |
| `inspecto-config` | `config` | foundation | base | build | default | every edition |
| `inspecto-sql` | `sql` | foundation | base | build | default | every edition |
| `inspecto-etl` | `etl` | foundation | base | build | default | every edition |
| `inspecto-event` | `event` | foundation | base | build | default | every edition |
| `inspecto-workflow` | `workflow` | foundation | base | build | default | every edition |
| `inspecto-acquire` | `acquire` | foundation | base | build | default | every edition |
| `inspecto-graph` | `graph` | platform | base | build | default | every edition |
| `inspecto-access` | `access` | platform | base | build | default | every edition |
| `inspecto-entity-store` | `entity-store` | platform | base | build | default | every edition |
| `inspecto-analytics` | `analytics` | platform | base | build | default | every edition |
| `inspecto-engine` | `engine` | platform | base | build | default | every edition |

### `features/` (15)

| artifactId | Manifest id | Build role | Offering role | Binding | Reactor | Ships from |
|---|---|---|---|---|---|---|
| `inspecto-entity-list` | `entity-list` | implementation | optional | boot | default | Basic |
| `inspecto-agent` | `agent` | implementation | optional | boot | default | Standard |
| `inspecto-intelligence` | `intelligence` | implementation | optional | boot | default | Advanced |
| `inspecto-backup` | `backup` | implementation | optional | boot | standard+ | Standard |
| `inspecto-exchange` | `exchange` | implementation | optional | boot | standard+ | Standard |
| `inspecto-observability` | `observability` | implementation | optional | boot | standard+ | Standard |
| `inspecto-ops` | `ops` | implementation | optional | boot | standard+ | Standard |
| `inspecto-case-management` | `case-management` | implementation | optional | boot | standard+ | Standard |
| `inspecto-action-requests` | `action-requests` | implementation | optional | boot | standard+ | Standard |
| `inspecto-regulatory-reporting` | `regulatory-reporting` | implementation | optional | boot | standard+ | Standard |
| `inspecto-reconciliation` | `reconciliation` | implementation | optional | boot | standard+ | Standard |
| `inspecto-scoring` | `scoring` | implementation | optional | boot | standard+ | Standard |
| `inspecto-screening` | `screening` | implementation | optional | boot | standard+ | Standard |
| `inspecto-anomaly` | `anomaly` | implementation | optional | boot | standard+ | Standard |
| `inspecto-predictive` | `predictive` | implementation | optional | boot | standard+ | Standard |

### `la/` (5)

| artifactId | Manifest id | Build role | Offering role | Binding | Reactor | Ships from |
|---|---|---|---|---|---|---|
| `inspecto-la-core` | `la-core` | platform | optional | boot | default | Basic |
| `inspecto-la-storage` | `la-storage` | implementation | optional | boot | default | Basic |
| `inspecto-la-api` | `la-api` | implementation | optional | boot | default | Basic |
| `inspecto-geo-link` | `geo-link` | implementation | optional | boot | default | Basic |
| `inspecto-la-store-pg` | `la-store-pg` | implementation | provider | boot | advanced+ | Advanced |

### `providers/` (10, plus the nested ASN.1 reactor)

| artifactId | Manifest id | Build role | Offering role | Binding | Reactor | Ships from |
|---|---|---|---|---|---|---|
| `inspecto-connectors` | `connectors` | implementation | provider | boot | default | Basic |
| `inspecto-agent-hosted` | `agent-hosted` | implementation | provider | boot | default | no Offering lists it |
| `inspecto-demo-auth` | `demo-auth` | implementation | provider | boot | default | no Offering; staged only by `package.ps1 -Distribution demo` |
| `inspecto-telecom-asn1` | `telecom-asn1` | implementation | provider | boot | default | Standard |
| `inspecto-oidc` | `oidc` | implementation | provider | boot | standard+ | Standard |
| `inspecto-secrets` | `secrets` | implementation | provider | boot | standard+ | Standard |
| `inspecto-geo-country` | `geo-country` | implementation | provider | boot | standard+ | Standard |
| `inspecto-connectors-kafka` | `connectors-kafka` | implementation | provider | boot | standard+ | Standard |
| `inspecto-notify-channels` | `notify-channels` | implementation | provider | boot | standard+ | Standard |
| `inspecto-policy` | `policy` | implementation | provider | boot | advanced+ | Advanced |
| `asn-facade` (in `providers/asn-parser/asn-decoders`) | `asn-decoders` | implementation | provider | build | default | no Offering; a build dependency of `inspecto-telecom-asn1` |

## 2. The three axes

Every module is classified once on each axis (`docs/GLOSSARY.md` §15).

| Axis | Values | Decides |
|---|---|---|
| Build role | Foundation · Contract · Platform module · Implementation | what the module may depend on |
| Offering role | Base (always present) · Optional (part of an Offering) · Provider (chosen by deployment) · Internal | whether it can be absent |
| Binding time | Build (classpath) · Boot (activator) · Space (enabled per Space) · Run (Platform Service grant, Job Pack) | how it is switched on |

A module boundary exists only for optional shipping, a distinct third-party footprint, swappability or test
isolation. Anything else is a package inside a module.

## 3. The manifest (`META-INF/inspecto/module.toon`)

The manifest of `inspecto-ops`, abridged (the `routes` line holds 43 entries):

```
---
id: ops
title: Case management and objects
buildRole: implementation
offeringRole: optional
bindingTime: boot
absentMessage: "Operational objects are not installed in this bundle - ..."
provides:
  features[1]: ops
  configKinds[3]: workflow,sla-policy,escalation-rule
  contracts[5]: ConsequenceProvider,JobTypeProvider,LinkedSubjectProvider,MaintenanceTaskProvider,ObjectEngineProvider
  storeFamilies[4]: OBJECTS,LINKS,NOTES,TAGS
  consequences[1]: create-incident
  jobTypes[1]: objects.analytics
  background[1]: sla-sweep
  maintenanceTasks[1]: incident_purge
  routes[43]: "GET /objects","GET /objects/analytics", ...
```

| Key | Meaning |
|---|---|
| `buildRole` · `offeringRole` · `bindingTime` | the three axes: `foundation` `contract` `platform` `implementation` · `base` `optional` `provider` `internal` · `build` `boot` `space` `run` |
| `absentMessage` | the text of the 503 answered when the module is absent |
| `provides.features` | must hold every id the code's `RouteModule.featureIds()` returns, and only ids the module declares in code (`featureIds()` or an `EditionFeatureProvider`); `Entitlements.featureEntitled` maps a feature to its module through this list (guard-tested by `ModuleManifestGuardTest`) |
| `provides.contracts` | the contribution-point interfaces the module implements |
| `provides.storeFamilies` | operational store families the module owns (`StoreFamilyProvider`) |
| `provides.consequences` | Consequence ids it registers (`ConsequenceProvider`) |
| `provides.jobTypes` | Job Type ids it registers; the Space module gate's owner table (parity-tested) |
| `provides.stepKinds` | Step Processor catalog ids it contributes (`inspecto-telecom-asn1` declares `parser.asn1.ber`) |
| `provides.configKinds` | registry kinds the module owns (a real roster kind, one owner) |
| `provides.background` · `provides.maintenanceTasks` | periodic work that pauses when a Space switches the module off |
| `provides.routes` | `METHOD path`, the exact regex registered, in registration order (parity-tested) |
| `requires.modules` | real runtime edges only, for example `modules[1]: ops` in `inspecto-case-management`; empty sections are omitted |

The leading `---` line separates manifests when a shade merges them. TOON has no comment syntax: never put a `#`
line in a manifest. The code that reads manifests is `com.gamma.module` in `inspecto-util` (`ModuleManifest`,
`ModuleManifests`, `ModuleActivator`, `KnownModules`).

A module is **inert** when a required module is absent or inert, or a required contract is provided by no active
module. `GET /modules` reports each module's state with the reasons, plus loader diagnostics.

**Known modules.** The processor's build copies every module's manifest, installed in this edition or not, to
`META-INF/inspecto/known-modules/<id>.toon` plus `index.txt` (`tools/KnownModules.java`, run from
`inspecto/pom.xml`). A bundle that left a module out therefore still answers that module's paths
`503 CAPABILITY_UNAVAILABLE` naming the module (`AbsentModuleRoutes`, text from `absentMessage`), and
`GET /modules` lists it `not-installed`. No hand-written stub class exists: a new optional module's stubs, parity
and OpenAPI paths need only its manifest. A module-side parity test (`ModuleRoutesParity`) keeps `provides.routes`
equal to what the module registers.

## 4. The three gates

A feature is reached through three gates, none of them inside feature code:
**Installed → Enabled (per Space) → Permitted (RBAC)**.

- **Installed.** Availability is computed from what bound, never declared: `/bootstrap` `features{}` and
  `GET /modules` report what the classpath brought. Since `PRODUCT-ASSEMBLY-1` PA-7a a signed entitlement
  (`conf/entitlement.toon` with its detached signature, read by `com.gamma.module.Entitlements`) can narrow this
  gate: an optional or provider module it does not list is not entitled, and neither are the features and Job
  Types only that module provides. With no entitlement file everything installed is entitled. The manifest's
  `entitlementKey` field stays reserved and is never consulted.
- **Enabled.** A Space switches an optional module off in `modules.toon` (`GET|PUT /settings/modules`). A route of
  a disabled module answers `404 MODULE_DISABLED`; `/bootstrap` `features{}` and `GET /modules` `enabledInSpace`
  follow. A module with no feature id (for example `backup` or `intelligence`) cannot be switched off.
- **Permitted.** Role capabilities, checked per route (`ApiContext.withCapability`).

**What removal and disable do, as built.**

- Config naming an absent module loads inert with a diagnostic and is never dropped on save. The registry-kind
  roster (`ComponentStore.WRITABLE_TYPES`) is one engine list, so an import judges a kind the same on every
  install: an absent module's kinds (`risk-score`, `reconciliation`) are accepted as inert config, and what an
  import refuses (access config, governance kinds, suffix-scanned ops configs) it refuses everywhere
  (`ConfigKindOwnershipTest`).
- `GET /modules` `inert{modulesToon, configKinds, jobs}` lists what the Space holds for an absent module, and an
  unhosted Job names it (`missingModule`).
- A Pipeline that uses a step kind an absent module declares in `provides.stepKinds` is listed with `hosted:false`,
  `missingModule` and `reason`; an `UNSUPPORTED_NODE` refusal names the module (`StepKindModules`,
  `ModuleRemovalPipelineTest`). A kind nobody declares names no module (unknown, not absent). A `frontend: asn1`
  Pipeline loads on any install, because the decoder is needed only to run it.
- Objects of an absent module's type load inert too. The object stores read `object_type` as text: a row whose
  type this build does not know is listed by `GET /objects` with `inert:true`, readable by id, refused on every
  write (`409`), and skipped by the SLA sweep, analytics and purge.
- A module switched off in a Space also stops its Jobs there: the fire is recorded `SKIPPED`, a manual trigger or
  replay answers `404 MODULE_DISABLED`, the config is untouched and the Job resumes when the module is re-enabled
  (`JobModuleGate`, `ModuleDisabledJobTest`). Periodic work is paused the same way: the owning tick asks the
  shared `ModuleGate` first, and `GET /modules` lists the paused ids as `backgroundPaused`. A Run already started
  is not interrupted.

## 5. Seams

- **Route modules.** The built-ins are an explicit ordered list in `ControlApi`; optional modules are discovered
  by `OptionalSpi.all(RouteModule.class)` (fail-soft) and appended after. Registration is first-match in order, so
  the built-in order is load-bearing; a duplicate (METHOD, pattern) fails boot.
- **Absent-module 503s** are synthesised from the manifests (§3).
- **Other contribution points**, discovered the same way: `StoreFamilyProvider` (operational store families),
  `ConsequenceProvider` (Decision Kernel Consequences), `HostBootHook` (a module's boot-time host installs) and
  `LinkedSubjectProvider`. Closed central registries become contribution points; `EventType` is the model to copy.
- **Edition features.** Where the executing code cannot leave a base module, a Standard-only module declares the
  feature instead (`EditionFeatureProvider`). See [architecture-editions.md](architecture-editions.md).

## 6. Offerings (`offerings/<id>.toon`)

An Offering composes modules and content; an edition is an Offering that `includes` another
(`standard` includes `basic`, `advanced` includes `standard`, `preview` includes `advanced`). The Offerings are
the one module table: `tools/bundle-modules.mjs` derives its rows from them and the manifests, so adding a module
means its manifest, its Offering line and a position in `CLASSPATH_ORDER`. `node tools/check-offerings.mjs` fails
when an Offering disagrees with the pom profile or the manifests.

Abridged from `offerings/standard.toon` (the file lists 16 add-ons and 9 content packs):

```
id: standard
title: Standard
tier: standard
posture:
  authMode: oidc
  eventsBackend: parquet
  objectsBackend: default
  postgresSidecar: bundled
includes[1]: basic
modules[19]: oidc,secrets,geo-country,connectors-kafka,telecom-asn1,notify-channels,backup,exchange,observability,ops,case-management,action-requests,regulatory-reporting,reconciliation,scoring,screening,anomaly,predictive,agent
addons:
  incidents:
    status: built
    modules[1]: ops
  scoringLists:
    status: built
    modules[2]: entity-list,scoring
```

- `modules` use manifest ids. Modules with `offeringRole` base are always present and never listed.
- `includes` unions modules and add-ons. `basic` lists 6 modules, `standard` 19, `advanced` 3
  (`policy`, `la-store-pg`, `intelligence`); `preview` lists none of its own.
- `addons` group modules under a sellable name with a `status`. `contentPacks[N]` lists Space Template ids under
  `spaces/_templates`. `defaultSpaceSettings:` is an empty placeholder.
- `posture:` holds four deployment facts per Offering, not inherited: `authMode` none|oidc, `eventsBackend`
  default|parquet, `objectsBackend` default|postgres, `postgresSidecar` absent|bundled. `check-offerings` holds
  them to that vocabulary and to what the launcher scenarios (`tools/check-launchers.mjs`) prove.
- `tools/render-offerings-matrix.mjs` writes the module by edition matrix, the add-on table, the posture table and
  the jar counts into the marked block of `docs/EDITIONS.md` (`--check` in CI).

Products, Apps and Industries (`products/`, `apps/`, `industries/`, `PRODUCT-ASSEMBLY-1`) sit above Offerings and
are not described here; see `docs/GLOSSARY.md` §15.

## 7. Packaging

- **Thin jars.** The 16 base libraries ship as one thin jar each; `inspecto.jar` holds the processor and the
  third-party code. The shade in `inspecto/pom.xml` has one unconditional configuration whose `artifactSet`
  excludes exactly those 16 artifacts. Optional and provider modules ship one jar each. The processor jar has no
  `Main-Class` (`CollectorProcessor` lives in the thin `inspecto-engine` jar), so the launchers start it from the
  classpath, never with `java -jar`.
- **Jar counts.** Every bundle stages the processor and the 16 core thin jars. On top of that Basic stages 6
  module jars, Standard 26 and Advanced and Preview 29: `bundleModules('Advanced')` in `tools/bundle-modules.mjs`
  returns 30<!--count:advanced-first-party-jars--> rows (the processor plus those 29), of which
  23<!--count:optional-modules--> are absent from Basic. The PostgreSQL driver sidecar is not counted.
- **Classpath.** `tools/offering-classpath.mjs` resolves the Offering (includes plus the `requires.modules`
  closure) and writes the bundle's `lib/modules.list` (one jar per line, classpath order, processor first) and
  `lib/edition.properties` (`edition=Standard`; `variant=demo` for a demo build). Every launcher reads
  `modules.list`; none keeps a jar list. `CLASSPATH_ORDER` is load-bearing for `ServiceLoader` first-match.
- **SBOM.** One CycloneDX SBOM per shipped jar under `sbom/`, beside the combined edition SBOM
  (`tools/sbom-modules.mjs --verify`, `tools/check-sbom-modules.mjs`).
- **Build id.** Every module jar carries the manifest attribute `Inspecto-Build-Id` (the git short sha, passed as
  `-Dinspecto.build.id=<sha>`, default `dev`; set once in the parent pom, with no timestamp so builds stay
  reproducible). At boot `ModuleActivator.resolve` starts a module whose stamp differs from the host's as inert;
  dependants follow. It never trips on an absent stamp, `dev`, an exploded directory or an unknown host.
  `GET /modules` shows `hostBuildId` and each module's `buildId`.
- **Runtime image.** `tools/jlink-modules.mjs` computes the jlink module set from `jdeps` over every staged jar,
  plus `tools/jlink-runtime-extra.txt` (reflection and `ServiceLoader` needs that `jdeps` cannot see, each with
  its reason). It is held against `tools/jlink-modules.lock`, which has one section per edition (Basic, Standard,
  Advanced, Preview); `--check` runs in CI. `package.ps1` links that set alone and fails closed on a lock
  mismatch. To change it on purpose: package the edition, run `--write-lock`, commit the lock.
- **Per-jar signing.** `package.ps1 -SignJars -JarKeystore -JarAlias [-TsaUrl]` signs every first-party jar; the
  password is read only from `INSPECTO_JARSIGN_STOREPASS`. `tools/check-jar-signatures.mjs <bundle>` demands that
  every jar verifies under one signer certificate. `release.yml` signs only when the three secrets exist
  (guard: `tools/check-release-signing.mjs`).
- **No JPMS (D-MR7).** The boundary is carried by the split-package guard
  (`tools/check-module-architecture.mjs`; ratchet baseline `tools/module-architecture-baseline.json`, only ever
  lowered, currently 0 split packages) and by signed per-module jars.

## 8. OpenAPI fragments

A module's HTTP paths live in `<module>/src/main/resources/META-INF/inspecto/openapi.fragment.json`
(`{ "paths": {...} }`); the core fragment is the one in `inspecto/`. `tools/openapi-merge.mjs [--write|--check]`
generates `docs/api/openapi-v1.json` by moving path entries as text (byte-stable). `docs/api/openapi-v1.json` is a
generated file: edit the fragment, never the document (CI runs `tools/check-openapi-fragments.mjs`). Schemas stay
in the core fragment.

## 9. Test kit and TCKs

The platform test kit is the test-jar of `inspecto-http-spi` (package `com.gamma.control.testkit`):
`FakeApiContext` (the in-repo `ApiContext` double), the abstract `RouteModuleContract` TCK (registers a route, no
duplicate (method, pattern), every mutating route behind `withCapability` or exempt with a reason, `featureIds()`
within the module's own manifest, registration is repeatable) and `ModuleRoutesParity`. Other SPI TCKs live in
the SPI's own test-jar. Six modules publish a test-jar: `inspecto-etl`, `inspecto-acquire`, `inspecto-engine`,
`inspecto-entity-store`, `inspecto-auth-spi` and `inspecto-http-spi`. A module with a route that needs the host at
`register()` moves that work into a `HostBootHook`. What the TCKs cannot drive is listed in
[spec-module-reorg-gotchas.md](spec-module-reorg-gotchas.md).

## 10. Why the reactor has this shape

**Binding constraints.** The system is framework-free (JDK `HttpServer`, manual dependency injection,
`ServiceLoader` SPI). There is one version (`inspecto-parent`, one `<version>` for every module) and one
deployable bundle per edition; modules are not released separately. Editions are build flavours of one codebase,
never branches.

**How the core was taken apart.** The product started as one module. It was split downwards, lowest layer first,
because a package can be extracted only when nothing it imports still lives above it:

1. `inspecto-api` came out first: it holds only `com.gamma.api.PublicApi`, the stability annotation, and
   everything else imports it. `inspecto-config` could follow only after that.
2. `inspecto-util` is a leaf with respect to `com.gamma`: it imports no other first-party package. The one class
   that reached upwards (`MainApp`, the command-line entry) moved to `com.gamma.inspector`, so the package moved
   out whole.
3. `inspecto-etl` became a foundation leaf once two upward imports were cut, which in turn freed
   `inspecto-event` (the event store with the registry it is mutually dependent on) and then `inspecto-acquire`,
   whose only upward dependency was `event`.
4. `inspecto-engine` is what remained of the execution cluster below the composition root `inspecto`. Two edges
   tied it to the root; one was cut by moving `Scheduler` to `inspecto-util`, the other by inverting a dependency
   through the `ReportRunner` SPI.
5. The contracts (`spi/`) and the optional modules followed in the edition extractions and the module
   reorganisation: a feature an edition lacks is a module that edition does not bundle.

**Build order.** Maven orders the reactor by dependencies; the default `<modules>` list is written in that order
(the nested ASN.1 reactor first, then `api`, `util`, `config`, `sql`, `graph`, `etl`, the `spi/` contracts and
`access`, `entity-store`, `event`, `workflow`, `analytics`, `acquire`, `engine`, then `inspecto`, then the
optional modules). Rules:

- A base module never depends on an optional or provider module outside test scope (checked 2026-11-12 over
  every base pom: the only such edge is the processor's test-scope dependency on `inspecto-telecom-asn1`). Core
  reaches an optional module only through an SPI it declares itself.
- An optional module may depend only on modules that ship in every edition where it ships.
- The host-free modules (`inspecto-graph`, the three `spi/` contracts, `inspecto-entity-store`,
  `inspecto-analytics`, the `la/` modules except `inspecto-geo-link`, `inspecto-oidc`, `inspecto-secrets`,
  `inspecto-geo-country`) may reach only the modules listed for them: `tools/check-module-deps.mjs` derives the
  module graph from the poms and compares it with its `ALLOWED` table, and the same policy is enforced at build
  time by `maven-enforcer` `bannedDependencies` in each governed pom.
- Since the split, the product module is not standalone: a build from `inspecto/` alone resolves only after a
  root `mvn install`. Every entry point builds through the root reactor. `package.ps1` runs
  `mvn clean package -pl inspecto,:inspecto-connectors,... -am` from the repo root.

## 11. Shared helper homes in `inspecto-util`

Duplicated `private static` helpers were consolidated into two homes. Reuse them; a new per-file copy is a
regression.

- **`com.gamma.util.Values`**: value coercion with the semantics in the name, because the historical copies
  differed subtly. `str` (null stays null, no trim) · `strOrEmpty` · `trimOrEmpty` · `trimToNull` · `blankToNull`
  (no trim) · `intOr` · `putIfPresent` · `fileSafe`; typed map access `mapAt` (instanceof-guarded, answers null)
  and `castMapAt` (bare-cast semantics: absent answers null, a non-map throws `ClassCastException`) · `listAt` ·
  `listOfMapsAt(m, key, where)`. Pick the method that matches the call site, never the closest one; `ValuesTest`
  pins every edge.
- **`com.gamma.util.SqlBuilder.quoteIdent`**: the DuckDB double-quote identifier escaper. It is not for CSV:
  `MappingCsv.quote` and `DbExportConnector.quote` quote CSV cells and are a different family despite similar
  bodies. It is also not for single-quote string literals.

Deliberately left as local copies, each different: `PipelineJobRunner.safe` (no null guard, where
`fileSafe(null)` yields `"_"`), `EventAlertBridge.putIfPresent` (a `Map<String,String>` that also excludes
blanks) and `RowShaper.str` (a `PipelineNode` accessor, not a map lookup).

## 12. Version management

- Shared external versions live once in the parent pom, as properties used by `<dependencyManagement>`: `junit`,
  `langchain4j`, `eoiagent`, `postgresql`, `hikaricp`, `jtoon`, `jackson` (2.x and 3.x), `bouncycastle`,
  `lz4-java`, `commons-beanutils`, `duckdb`, `univocity`, `commons-compress`, `commons-io`, `commons-lang3`,
  `gson`, `slf4j`, `logback` and `opencsv`. Modules declare those artifacts without a version.
- Reactor-internal dependencies on the base modules are parent-managed at `${project.version}`, including the six
  test-jars (§9).
- A dependency with a single owner stays pinned in that module, unless another pinned version reasons from it
  (`opencsv` was hoisted for that reason: the `commons-lang3` pin depends on it).
- Plugin versions (`compiler`, `surefire`, `jar`, `shade`, `enforcer`) are pinned in the parent
  `<pluginManagement>`: the offline reactor resolves no plugin version from a repository.
- JaCoCo's `coverage` profile lives in the parent (`mvn -Pcoverage test` instruments every module).

## 13. Extracting a module: the playbook

1. **Module-level acyclicity is not package-import acyclicity.** A package with clean imports can still be
   un-extractable: `config` imported only `PublicApi`, but `PublicApi` lived in the core while the core imported
   `config`. Check what the candidate imports that still lives above it, and extract the blocking leaf first.
2. **Keep the Java package name when moving a package.** The diff stays a pure rename and history survives
   (`git mv`). The exception is a move that would leave one package in two modules: the split-package guard
   (§7) refuses it, so rename then.
3. **Surefire's working directory is the module root.** A test that walks a module-relative fixture tree cannot
   move with its class: `ShippedExamplesRoundTripTest` stayed in `inspecto` with the `examples/` tree it reads.
4. **Gate a move on the full reactor.** A module move edits poms, and `node tools/affected-modules.mjs` answers
   `full` for a pom change. Run it under `-Pedition-advanced`: the default build compiles none of the
   profile-scoped modules.
5. **An import-anchored search does not prove a package is a leaf.** A class can reach upwards through a
   fully-qualified inline call with no `import` line. Scan for inline `com.gamma.<package>` references as well as
   imports, and let the reactor build find the rest.
6. **One non-leaf class in an otherwise-leaf package moves to its natural home**, so the package moves out whole
   and no split package is created.
7. **Over-counting is as wrong as under-counting.** A scan that does not strip comments reports edges that
   `javac` ignores: a `{@link}` in javadoc is not a compile edge, and an `import` kept only to resolve a
   `{@link}` is not one either. `pkggraph.py` (§14) strips comments, text blocks and string literals first.
8. **Import-clean is not build-clean.** Two things imports never show. Shared test fixtures: a test helper in a
   moved package that staying-behind tests import needs a test-jar (`com.gamma.etl.TestConfigs` in
   `inspecto-etl`). Resource-wired classes: a resource that names a moved class by its full name must travel with
   it (`logback.xml` names `EventStoreAppender`, so both live in `inspecto-event`). Inventory
   `META-INF/services/*` and the `*.xml` and `*.properties` resources before the move.
9. **Check where a type's consumers live before calling a relocation a cut.** Moving a type next to its
   dependency re-creates the edge from the other side when its consumers stay behind.
10. **A relocated type's references are not all under `src/`.** The scaffold templates under `tools/templates/`
    are compiled by a test, and a stale import there breaks the build. Search the whole repo.
11. **A nested public type makes its enclosing class an import for every caller** of a method that returns it.
    Promote the nested type before predicting that an interface will cut an edge; and note that a promoted type
    which lands in the same package removes the holder, not the edge.
12. **`@PublicApi` records an intention to publish, not exposure.** The stability promise binds only within a
    released major, and no `v4.x` tag exists. Before treating a relocation as breaking, check the newest release
    tag for the class: `git ls-tree -r --name-only v3.11.0`. Match the annotation in both spellings,
    `@PublicApi` and `@com.gamma.api.PublicApi`.
13. **A provider SPI goes where its arguments live.** `ObjectEngineProvider` is declared in `com.gamma.service`
    in the host module, because opening the stores needs `SpaceRoot` and `OperationalDb`, which live there.

The edition-specific half of this playbook (stubs, feature flags, falsification tests) is in
[architecture-editions.md](architecture-editions.md).

## 14. Package cycles inside modules

Maven keeps the module graph acyclic. Packages inside one module can still be mutually dependent, which matters
only when that module is to be split further. Three scripts in `docs/superpower/assets/` measure this; each
strips comments, text blocks and string literals before scanning, and counts imports and inline fully-qualified
names:

- `python docs/superpower/assets/pkggraph.py <root>`: the package-edge graph of `com.gamma.*` with its
  strongly-connected components. Pass `.` for the whole reactor (test trees are skipped) or one module's
  `src/main/java`.
- `python docs/superpower/assets/edgeholders.py <package> <package> ...`: which file holds each edge inside the
  given set of packages.
- `python docs/superpower/assets/fanmatrix2.py <root> <Class,Class,...>`: fan-in and fan-out of named classes.

Measured 2026-11-12 with `pkggraph.py .`: 115 packages, 7 cycles. Each cycle sits inside one module, so none
blocks a module extraction; each would have to be cut before that module could be divided.

| Packages in the cycle | Module | Note |
|---|---|---|
| `alert`, `catalog`, `catalog.spi`, `enrich`, `inspector`, `job`, `mask`, `notify`, `objects`, `parse`, `pipeline`, `pipeline.exec`, `query` (13) | `inspecto-engine` | the engine is one coarse module; a finer split is deliberate cycle-breaking work, not a mechanical move |
| `assist.spi`, `control`, `intelligence.spi`, `report`, `service` (5) | `inspecto` | the composition root; held by several holders at once, so one role interface does not cut it |
| `agent.kernel.agent`, `.observe`, `.retrieve`, `.tool` (4) | `inspecto-agent` | vendored code; leave |
| `ops`, `ops.tag`, `opsapi` (3) | `inspecto-ops` | one family that would move together |
| `analytics`, `analytics.distribution` (2) | `inspecto-analytics` | |
| `etl`, `etl.unpack` (2) | `inspecto-etl` | real code in both directions; cutting it is a redesign of the unpack seam |
| `event`, `metrics` (2) | `inspecto-event` | a deliberate pair: the event store and the registry it reports to |

## Where the rest of the model lives

Decision Kernel (Condition Language, Consequence registry):
[decisions](spec-module-reorg-decisions.md#decision-kernel-d-mr12) and
[Decision Rules](../../module/processor/spec-decision-rules.md). Incidents, Case Management, Action Requests and
Workflow & SLA: [incidents plan](../../addon/incidents/plan-incidents.md). Build and test commands:
[spec-build-test.md](spec-build-test.md). The API stability contract:
[plan-api-stability.md](../../module/api/plan-api-stability.md).

## Related

[Architecture](architecture.md) · [Editions](architecture-editions.md) ·
[Module reorganisation: decisions](spec-module-reorg-decisions.md) · [gotchas](spec-module-reorg-gotchas.md)
