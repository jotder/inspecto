---
type: Spec
title: Editions — engineering specification — Preview, Basic, the data layer and the assembly model
description: Part 2 of 5 of Editions — engineering specification: Preview, Basic, the data layer and the assembly model
resource: offerings/
tags: [editions, packaging, offerings, gating, decisions]
timestamp: 2026-11-06T00:00:00Z
---

# Editions — engineering specification — Preview, Basic, the data layer and the assembly model

Part 2 of 5 of [Editions — engineering specification](spec-editions.md) · previous: [LA feature placement, the Matrix and the Offerings matrix](spec-editions-placement.md) · next: [Feature × edition matrix — how to read it, core engine](spec-editions-feature-matrix.md).

## Preview — not a customer-facing tier

**Preview exists only for testing/incubation (operator decision, 2026-09-21) and is deliberately not a
row in the matrix above.** It is the one place this document's organising rule gets an exception on
purpose: *"a feature an edition lacks is a module that edition does not bundle"* still holds, but Preview
answers it by bundling **every** optional module unconditionally, so a module still in incubation is
reachable for testing the day it compiles — without an edition decision having been made yet for where it
eventually lands (Basic/Standard/Advanced).

- `mvn -o clean package -Pedition-preview` (`pom.xml`) and `pwsh -File inspecto/package.ps1 -Edition
  Preview` build/stage it.
- `tools/bundle-modules.mjs`'s `bundleModules('Preview')` takes the whole module table unconditionally
  (not floor-based like the other editions), so a brand-new optional module needs **no edit there** to
  reach Preview the moment it exists. `pom.xml`'s `edition-preview` profile is static XML and does
  **not** auto-union — a new module must be added to it by hand or Preview silently stops being
  "everything" (comment at the profile, `pom.xml`).
- Today Preview's module set is byte-identical to Advanced's (nothing is currently incubation-only)
  — since P3d (2026-10-08) `serve.bat`/`serve.sh` read the edition from the bundle's
  `edition.properties` (`edition=Preview`) rather than guessing it from jar presence, so a Preview bundle
  reports `edition: Preview` at boot (and keeps the operational objects on DuckDB — only `Advanced`
  pins PostgreSQL). The distinction in module sets only starts to matter once an incubating module
  exists that Advanced doesn't carry.
- Never ship Preview to a customer, never advertise it externally, and never let it gate a compliance
  claim — it carries no compliance-scope guarantee at all.
- See [`okf/product/layer/platform/admin-guide-local-testing.md`](admin-guide-local-testing.md)
  for the companion no-IAM/no-Postgres local path (Demo User sign-in, an Advanced-only demo build).

## Basic — the Link Analysis explorer (LA-EDITIONS-1 phase 3, 2026-10-17)

**What ships.** `inspecto-entity-list`, `inspecto-la-core`, `inspecto-la-storage`, `inspecto-la-api` and
`inspecto-geo-link` are default-reactor modules (`pom.xml`) staged in every bundle (`tools/bundle-modules.mjs` floor
`all`, `offerings/basic.toon`), so a Basic install answers the explorer routes — projection, neighbours, paths, patterns,
Value Measures, Entity identities, the Entity Type list, resolve and suggest, reference rows, snapshots — **with no
sign-in** (Basic ships no Authenticator). The graph library the plan called `la-graph` is the core thin jar
`inspecto-graph`, already in every edition.

**What is withheld, and how.** One edition feature, `EditionFeatures.INVESTIGATIONS` (`investigations`), declared by
`inspecto-ops` (`OpsEditionFeatures`) — a module only Standard, Advanced and Preview bundle. Every Link Analysis route
module of the Investigation surface names it in `featureIds()` beside `geoLink` (Investigations, Drafts, Dossier and
bundle, Working Set, references, templates, coverage, compare, Case link, Case history, members, embeds and the public embed view,
Graph Run, link index builds, Dataset Files, measures / Alert Rules / standing detection, data-changes). When no
installed module declares it, the host answers those routes **`503 CAPABILITY_UNAVAILABLE`** — "Link Analysis
Investigations (…) are available in Standard and above — this build (Basic) is the Link Analysis explorer and does not
include them" — at dispatch (`ControlApi.requireModuleEnabled` → `EditionFeatures.gatesRoutes`), before the Space's own
module switch (404 `MODULE_DISABLED`). The rule this document keeps — *a feature an edition lacks is a module that
edition does not bundle* — still holds: Basic lacks the Investigation surface because it lacks `inspecto-ops`.
`/bootstrap` reports `features.geoLink: true, investigations: false`, `laLevel: "explorer"` and
`featureReasons.investigations` on Basic, and the SPA (`LA_FEATURES.investigations` / `featureReasons`, from
`SessionService`) shows the Investigation actions LOCKED with that line (`<inspecto-locked-action>`, `LA-ONBOARD-1` OB-10;
operator answer A-11 "locked with a reason inside screens"): the Investigation tab's *Start an Investigation*, "start an
Investigation from the top results", every Run-on-server / Run-on-index control and the edge-index and server-algorithm
tools. The `?investigation=` and `?seed=` openers still open nothing, so nothing offered can 503. The standalone Inspecto LA app also hides its **Alerts** area on
Basic (operator 2026-10-21): those are the standing-detection Alerts behind Investigations, so the rail and landing page
omit it (its nav entry in `apps/link-analysis/app.toon` carries `feature: investigations`) and a typed `/alerts` lands on the landing page (`investigationsGuard`). Pinned by `ControlApiBasicEditionLinkAnalysisTest`
(`la/inspecto-geo-link`: Basic answers an explorer route 200 with no sign-in and refuses one route per Investigation
module; Standard answers both) and the Explorer-level specs in `link-analysis.component.spec.ts` /
`link-analysis-toolbox.server-run.spec.ts`.

**The Investigations add-on: the same Explorer level on Standard and above** (`LA-ONBOARD-1` OB-5, operator-confirmed
2026-10-22, DATA-TIERS-1 DT-11 clarified). Investigations is a billable add-on (Product Map `addon.laInvestigations`,
`INS-AO-LA-INVESTIGATIONS`, `enforcement: runtime`) included by default wherever the edition carries `inspecto-ops`. An
installation that does not hold it starts with `-Daddons.laInvestigations=false` (for example in `INSPECTO_JAVA_OPTS`):
`EditionFeatures.present(INVESTIGATIONS)` then answers false, so the same routes answer `503 CAPABILITY_UNAVAILABLE` naming
the add-on ("… need the Investigations add-on (INS-AO-LA-INVESTIGATIONS), which this installation does not hold …") and
`/bootstrap` reports `laLevel: "explorer"`. The edition stays the one governance switch: sign-in, roles and enforced
masking are unchanged. Until a signed entitlement exists, whoever runs the server can claim the add-on; the one place the
proof is read is `EditionFeatures.holdsInvestigationsAddon()`. Basic never has it, whatever the setting says. Pinned by
`ControlApiInvestigationsAddonTest`.

**Masking on Basic is off by default, and display-only when on** (operator 2026-10-21; since `LA-ONBOARD-1` OB-6 the default
keys on SIGN-IN, operator answer A-16). A Space that states no `masking_mode` masks nothing when nobody signs in (`auth.mode`
none and no Authenticator: Basic); wherever people sign in the shipped `typed` holds, with or without the Investigations
add-on. The seam is `LinkAnalysisSettings.effectiveMaskingMode`, which every masking reader and `GET /settings/link-analysis`
`maskingModeInForce` go through; it asks the `MaskingDefault` provider the control plane ships (`EditionMaskingDefault`), so
the API and the SPA agree. Pinned by `ControlApiMaskingDefaultBySignInTest`.
⚠ It is the Space's ONE masking mode, so on Basic the BI reads (`BiResultMasking`: queries, KPIs, the DB browser and
Report Job exports) are unmasked by default too. The exception is a public dashboard share: its holder is anonymous and
cannot reveal, so it masks under the shipped `typed` unless the Space states a mode (`BI-QUERY-MASKING-1` review, 2026-10-23).
A Space that STATES a mode keeps it in every edition. Such a mode still masks entity ids in explorer answers, but with no
sign-in there is no Subject, so the capability an audited reveal needs (`canRevealLinkEntities`) is never checked and
anyone may reveal. Masking on Basic stops a casual glance; it is not access control. Enforced masking (and the audited
reveal on an Investigation) starts in Standard.

**Preview carries all of it (phase 7).** Preview bundles every module of every edition and builds with demo sign-in:
`package.ps1 -Edition Preview -Distribution demo` (Advanced or Preview; `tools/offering-classpath.mjs --edition preview --demo`
swaps the OIDC trio for `inspecto-demo-auth.jar`). Held by the `Preview carries every module …` test in
`tools/offering-classpath.test.mjs` (the generator table, the plain and demo classpaths and the `edition-preview`
profile) and by `tools/check-offerings.mjs` (Offering == bundle == pom profile).

## Data layer by edition (DATA-TIERS-1, as built 2026-11-06)

Operator rule: *"Basic is not a demo; it must be usable."* **Basic runs any configuration it is given.** Since the
2026-11-05 re-plan (DT-9 narrowed, [`data-tier-editions-plan.md`](../../../../archived-documents/plans-archive/data-tier-editions-plan.md) §5.3) the
edition line in the data layer is **what each edition's screens offer**, not what the engine refuses: no DATA-TIERS-1 gate refuses a Pipeline,
Step, Collector kind or trigger when a Basic install loads its configuration. The edition refusals that predate it stay
(a PII `mask` Step, DuckLake registration and the compliance archive are refused on load, `EditionFeatures.requirePipeline`;
Alert Rules are held, not armed). The engine is the same in every edition; what differs is the offer.

**Add data — the three ways in, every edition.** Add data is a platform feature with three ways in and one default
Pipeline (read, arrange, write Parquet, then each application's follow-on; Inspecto LA's is its link index):

1. **Upload a file** (CSV / TSV, Parquet, JSON / NDJSON, Excel when the bundle carries the DuckDB `excel` extension), or
   point at a large file in place, re-checked by size and modified time on every read (DT-3).
2. **An import inbox**: files dropped there are imported with *Import waiting files* (*Run now*), or, when the person
   turns on *Watch this folder* on the Dataset card, by a fixed 30 s poll (off by default, not configurable). Since
   `BASIC-ELT-PROFILE-1` B1 (2026-11-19, O1 reversing DT-17) the watch is the import Pipeline's own locked schedule
   (`trigger.every: 30s`, run on its own cadence by the engine's scheduler), not a separate poller.
3. **Choose a table**: a Postgres table or a read-only `SELECT`, copied as a *replace everything* snapshot. *Refresh
   now* exports the table into a temporary folder and moves the finished file into the Dataset's inbox; a snapshot is
   **never polled** (since 2026-11-05). Read through DuckDB's `postgres` extension, already in every bundle; a
   write-capable login is refused, and only a `${ENV:INSPECTO_PG_…}` password reference is resolved.

| Data layer | Basic | Standard | Advanced |
|---|---|---|---|
| Add data: upload, inbox (*Watch this folder*, 30 s), Postgres snapshot (*Refresh now*) | ✅ | ✅ | ✅ |
| Column review: names, types, column roles (Entity · Time · Measure · Category · Detail), Entity Types | ✅ | ✅ | ✅ |
| Row filter on the review step, and *Edit filter* on the Dataset card (applies to the next files) | ✅ | ✅ | ✅ |
| Expectations, quarantine and dedupe, evaluated in the import's own run (DT-15) | ✅ | ✅ | ✅ |
| Arrivals list, name-checked columns, duplicate refusal, *Replace the period*, compaction (automatic, and *Compact now*) | ✅ | ✅ | ✅ |
| A later file's new columns kept as details (*Keep new columns as details* on *Add a file*; unattended runs keep them; `LA-ARRIVAL-NEW-COLUMNS-1`) | ✅ | ✅ | ✅ |
| Retention policy on an import Dataset (`PUT /datasets/{id}/storage`; *keep N arrivals* counts each arrival, also after *Replace the period*, `LA-DATASET-COMPACT-2`) | — every file stays, compacted | ✅ | ✅ |
| *Keep this up to date*: a watched folder or SFTP feed on a chosen schedule (`PUT /datasets/{id}/feed`) | — locked | ✅ | ✅ |
| Discover, entity summary, query builder, preset questions (Inspecto LA, feature `geoLink`) | ✅ | ✅ | ✅ |
| Add data's *another database or a remote system* | — locked | not locked: the screen says a Pipeline reads them | as Standard |
| Enrichment offer on the Dataset page (*Add the details from X to each row*) | — locked; join in a Studio query | ✅ | ✅ |
| A NEW remote Connection (SFTP, FTP / FTPS, `db`) and *Connection* in the Collect stage (`remoteSystems`, P4) | — locked | ✅ | ✅ |
| Step palette: Steps beyond no-code shaping (`pipelineTransform`, P6; the catalog's `featureFlag`) | — locked; *Row filter* and dedupe stay | ✅ | ✅ |
| A NEW `objectstore.export` / `publish.postgres` Job (`pipelineEgress`, P5) | — locked | ✅ | ✅ |
| ASN.1 and telecom decoders (module `telecom-asn1`, add-on `telecomIndustryPack`, DT-12) | — | — | ✅ |
| `la-daily-feed` Space Template (DT-18) | — | ✅ | ✅ |

**How the locks work (as built 2026-11-17).** Four **screen gates**, each an edition feature declared by `inspecto-ops`
(`OpsEditionFeatures`), so Standard and up have them and Basic does not: `pipeline.automation` (`PIPELINE_AUTOMATION`),
`pipeline.transform` (`PIPELINE_TRANSFORM`), `pipeline.egress` (`PIPELINE_EGRESS`) and `collector.remote`
(`REMOTE_SYSTEMS`). `EditionFeatures.SCREEN_FLAGS` maps each to its `/bootstrap` key: `features.pipelineAutomation`,
`pipelineTransform`, `pipelineEgress` and `remoteSystems` are true or false, and a withheld one has a `featureReasons`
line under the same key (`EditionFeatures.lockedReason`, one short line naming the edition). Two static markers tell the
screens which offer a gate covers: `GET /pipelines/processor-catalog` puts `featureFlag` on each processor
(`ProcessorCatalog.featureOf`: the remote acquisitions → `remoteSystems`; every mapped `transform.*` Step except
`transform.filter`, `transform.dedup` and `transform.mask`, plus reference enrichment → `pipelineTransform`), and
`GET /jobs/types` puts `featureFlag: pipelineEgress` on `objectstore.export` and `publish.postgres`
(`EditionFeatures.EGRESS_JOB_TYPES`). The console reads them through `LensService.screenLocks()` (`edition-locks.ts`; a
flag the server does not report is offered): the Step palette shows a gated processor locked with the reason, the Collect
stage locks its *Connection* choice, the Connection form locks a NEW `db` / `ftp` / `ftps` / `sftp` / object-store /
Kafka Connection, and the Job form locks a NEW Job of a gated type. **None of them refuses anything on the server**: a
Pipeline, Connection or Job already using one loads, runs and stays editable (a node already collecting through a
Connection keeps that mode). `pipeline.automation` alone is also checked by its routes: the feed and storage routes
answer `503 CAPABILITY_UNAVAILABLE` on Basic (a retention policy already stored is ignored there, so Basic never drops
rows), and their reads report it (`automatic`, `retentionAvailable`). The product states nothing either way about
scheduled or signal-driven Jobs on Basic (DT-16). Upgrade: a Basic import is an ordinary editable Pipeline on Standard
(`ControlApiDataTierUpgradeTest`).

### Release note — the data layer by edition (2026-11-17)

- **Standard:** the ASN.1 and telecom decoders (`telecom-asn1`, the Telecom industry pack) are Advanced only (DT-12). A
  Standard install's ASN.1 Pipeline is listed `hosted:false` with `missingModule: telecom-asn1`; no shipped Space Template
  uses it.
- **Basic:** the `la-daily-feed` Space Template is Standard and above (DT-18: `minEdition: standard`, `requiresModules: ops`).
- **Basic, locked with a reason in the screens:** a new remote Connection and the Collect stage's *Connection*, the step
  palette's Steps beyond no-code shaping, new `objectstore.export` and `publish.postgres` Jobs; `/bootstrap` reports the
  four screen gates (`pipelineAutomation`, `pipelineTransform`, `pipelineEgress`, `remoteSystems`) with their reasons.
- **Nothing breaks on load.** Existing configurations using them load, run and stay editable on Basic.

### Release note — the data layer by edition (2026-11-06)

- **New in every edition:** Add data's three ways in (upload, an import inbox with *Watch this folder*, a Postgres
  table snapshot by *Refresh now*), column roles, row filtering and *Edit filter*, the arrivals list with compaction,
  a later file's new columns kept as details, and Discover, the entity summary and the query builder in Inspecto LA.
- **Standard and up:** *Keep this up to date* and a retention policy now work on a real Standard bundle (before
  2026-11-05 no module declared `pipeline.automation`, so both were refused everywhere outside tests). An import's link
  index is still built in the person's own run.
- **Basic:** *Keep this up to date* is locked with a reason, and so are another database or a remote system in Add data
  and enrichment on the Dataset page; the retention form is not shown (every file stays, compacted). A Postgres snapshot is refreshed by *Refresh now* only; the
  30 s refresh it had for part of 2026-11-05 is gone.
- **Nothing breaks on load.** A Basic install keeps running the configuration it has: no DATA-TIERS-1 gate refuses a
  Step, Collector kind or trigger (DT-9 narrowed); the older refusals (PII masking, DuckLake, the archive) are unchanged. The breaking changes the plan's §9 listed are not shipped.

Reverses DT-4 (Postgres only), narrows DT-5 / DT-17 to the import inbox, widens DT-10. As built:
[`okf/product/module/la-core/architecture-watch-this-folder.md`](../../module/la-core/architecture-watch-this-folder.md) (§6.20),
[`okf/product/module/processor/api-onboarding-authoring.md`](../../module/processor/api-onboarding-authoring.md);
plan [`archived-documents/plans-archive/basic-onboard-plan.md`](../../../../archived-documents/plans-archive/basic-onboard-plan.md).

## UI flavor — not an edition

`package.ps1 -Product inspecto|la` (default `inspecto`; since `PRODUCT-ASSEMBLY-1` PA-2, 2026-11-10, replacing `-Ui gamma|la-app` of 2026-10-02) chooses which Angular application the bundle's `ui/` serves (the Product's `host`): the
Inspecto console (`gamma`) or the Link Analysis application (`la-app`). It is orthogonal to `-Edition`: there is no fifth edition, no
Maven profile and no edition branch for it, every edition can ship either, and the Link Analysis backend modules stay where the
matrix puts them (the explorer in every edition, the Investigation surface Standard and above). A bundle holds exactly one application. An LA-only edition (only the Link Analysis
modules plus security) remains a possible later promotion, not a decision taken. See
[`okf/product/layer/platform/spec-build-test.md`](spec-build-test.md) (*Packaging*).

## Assembly model (how an edition is produced)

| Mechanism | Used for |
|---|---|
| **Separate Maven module** — **the list below is illustrative; the authoritative set is derived by `tools/bundle-modules.mjs`** (re-derived 2026-10-07: 20 optional modules): `inspecto-oidc`, `inspecto-secrets`, `inspecto-geo-country`, `inspecto-policy`, `inspecto-notify-channels`, `inspecto-backup`, `inspecto-geo-link`, `inspecto-exchange`, `inspecto-observability`, `inspecto-ops` | Standard-and-up code (OIDC resource-server, TLS, RBAC; the webhook + SMTP delivery transports; the backup/restore maintenance tasks; cross-space sharing; the Prometheus `/metrics` exposition). Basic simply doesn't bundle it. (The geo map + link analysis modules were in this group until LA-EDITIONS-1 phase 3 moved the explorer into every edition.) Beyond those, **Advanced-only and optional**, is `inspecto-la-store-pg` (2026-10-04, `LA-INVESTIGATION-STORE-DESIGN-1` S6): the PostgreSQL Link Analysis Investigation store a second pod needs to share a Space. It is a thin jar over plain JDBC, built under `-Pedition-advanced` / `-Pedition-preview`, staged by `package.ps1`, and **inert until `-Dinvestigations.backend=db`**: every edition, Advanced included, defaults to the filesystem. |
| **Maven profiles** (`-Pedition-standard` / `-Pedition-advanced`) | Which modules + shade includes go into the fat-JAR. `edition-advanced` = `edition-standard` + `inspecto-policy`. 🔴 **There is no `edition-basic` profile** — the parent POM declares `edition-standard`, `edition-preview` and `edition-advanced` (plus `coverage`). **Basic is the DEFAULT**, i.e. the reactor with no profile at all, which is what `package.ps1` does (`if ($Edition -ne 'Basic')` guards every Standard+ step). ⚠ The old text named a third flag; Maven only *warns* on a profile that does not exist and then builds the default, so a shift following this table would have got the right bundle for the wrong reason and learnt nothing from the build. |
| **`ServiceLoader`** | Runtime discovery — absent module ⇒ the no-op impl is the only one found (mirrors the optional assist agent). **Since 2026-09-07 this includes whole route groups**: `com.gamma.spi.http.RouteModule` is a public SPI, discovered and registered *after* the built-in list (first-match order is preserved; a colliding `(method, pattern)` fails the boot). |
| **`-D` flags** (`-Dauth.mode`, TLS on/off) | Runtime toggles within an edition. |
| **`package.ps1 -Edition …`** | Emits the per-edition bundles from one build. |

The core engine never contains `if (edition == …)` branches; it depends on SPIs (`Authenticator`,
`SecretsProvider`, …) and the **build** decides which implementation ships. One SemVer version spans all
editions. 🔴 **No classifier is emitted**: all three flavours write the same
`inspecto-deploy-<platform>.zip` path (Since 2026-09-17 the zip is named after the EMBEDDED runtime's platform — `inspecto-deploy-<platform>.zip` (`windows_amd64`, `linux_amd64`), read off the jlink image, never the host OS (`RELEASE-BUNDLE-PLATFORM-MISMATCH-1`).) and `release.yml` **collects** them via `tools/release-collect.sh` as `inspecto-deploy-<edition>-<platform>.zip`, failing when the runner's own platform is missing.
There is no `-standard` artifact at all and no `-advanced` classifier — the two names this line used
are exactly the pair the pipeline does not produce.

## Status — core is auth-free (2026-06-16)

The hand-rolled bearer-token control plane that earlier versions baked into `ControlApi`
(per-route `Scope` + `Tokens` + `requireAuth`, `-Dcontrol.token` / `-Dassist.*.token` (removed), the
Angular token-paste `/connect` screen + `authInterceptor` + route guard) has been **removed
from the common core / `master`**. The Basic edition is now genuinely auth-free: every
ControlApi route is open and the SPA boots straight to the dashboard with no login.

Authentication is no longer a core concern — it becomes an **edition** concern. The
Standard/Advanced editions re-introduce it out-of-band (see below) behind an `Authenticator`
SPI seam, so the engine keeps no auth code and fixes/features land once in common. This realigns
the code with the model already described here: editions add modules; they are never branches.

**Status (2026-07-06, W6):** the `Authenticator` SPI (`com.gamma.spi.auth.Authenticator`/`Subject`)
and the AuthN/AuthZ gate in `ControlApi.dispatch` are shipped in the core (edition-neutral — a no-op
when no implementation is on the classpath). The `inspecto-oidc` module ships the Standard
implementation (`OidcAuthenticator`, Nimbus JOSE+JWT + JWKS) and is reactor-gated behind the
`edition-standard` Maven profile, so it is never built or resolved by a routine Basic build.
`package.ps1 -Edition Standard` builds and bundles it; see `docs/okf/product/module/processor/plan-control-api.md` (design of record; the 2026-07-06 design is archived)
§10 W6 for the full slice and `docs/api/deployment/` for WSO2/Keycloak blueprints.

## Security direction (Standard)

Authentication is **delegated to an external IAM** — the app validates IAM-issued JWTs (Nimbus + JWKS:
issuer/audience/expiry) and enforces authorization from claims. The IAM owns user management, AD/LDAP
federation, and SAML brokering, so none of that lives in the Java core. The Angular UI uses OIDC
Authorization Code + PKCE (public client, no shipped secret).

This is **incremental hardening on the existing framework-free core** — *not* a Spring Boot / Quarkus
migration. At 5–15 users with a capabilities-based RFP, a framework buys nothing the IAM + small libraries
don't, and a lean dependency tree is a FedRAMP asset (small SBOM, fewer CVEs to attest). The full
assessment + 7-phase hardening roadmap is maintained alongside this repo's planning notes.

## Advanced (partially shipped 2026-07-23) — keep the seams open

**Shipped — the ABAC policy engine (rbac-abac-plan §4):** the `edition-advanced` Maven profile
(= `edition-standard` + `inspecto-policy`). The module registers a `PolicyEngine` on the core's
`com.gamma.spi.auth.AccessDecider` ServiceLoader seam; authored per-space Access Policies
(`access-policies.toon`, the shared `Conditions` grammar) then evaluate at the route-level authorize
stage (deny = 403) and the row-level `RowScope` filter (deny = the SEC-7d 404/filtered contract).
Basic and Standard never bundle the module and behave byte-identically. Build/test:
`mvn -o clean test -Pedition-advanced`.

**Shipped 2026-07-25 — the packaging flavor.** `package.ps1 -Edition Advanced` now emits a deployable
Advanced bundle, closing the last gap between "the profile builds" and "an operator can ship it". It is
a **superset of Standard** (mirroring the profile relation), so it builds `inspecto-oidc` *and*
`inspecto-policy` under `-Pedition-advanced` and bundles both `inspecto-oidc.jar` and
`inspecto-policy.jar`. The generated `bin/serve.sh`/`bin\serve.bat` read the edition from the bundle's
`lib/edition.properties` (P3d, 2026-10-08 — they used to sniff jar presence: security jar ⇒ `Standard`,
plus policy jar ⇒ `Advanced`; that heuristic survives only as the fallback for a hand-assembled directory)
and every edition above Basic gets `-Dauth.mode=oidc`; the classpath is the bundle's `lib/modules.list`,
written by `tools/offering-classpath.mjs` from the Offering. **No new runtime flag exists or is needed** — `inspecto-policy` is found
solely through `META-INF/services/com.gamma.spi.auth.AccessDecider`, so the classpath entry *is* the
switch. Basic bundles remain byte-for-byte unchanged. Since 2026-09-29 (`ASSURE-INTELLIGENCE-BUNDLE-1`)
Advanced also stages `inspecto-intelligence.jar` (CP-14 `/agent/*`), found the same way through
`META-INF/services/com.gamma.intelligence.spi.IntelligenceAgent` and loaded through `OptionalSpi`, so a host
whose Java cannot link it boots without it and `/agent/*` answers 503.

**Shipped 2026-07-24 — per-tenant space isolation (A4 = SPC-5):** `PolicyEngine.SEED` carries two
engine-resident seeded policies (`space-isolation`, `space-isolation-rows`) denying access outside
the subject's home space; they engage only once a `space` claim is mapped via `roles.toon`
`identity: {attributeClaims}`, exempt `canConfigureAccess` holders, and are tailorable/disableable
by authoring a policy of the same name in `access-policies.toon`.

Already fits: stateless stage-1 engine, **stateless JWT auth** (no server session ⇒ horizontal scale),
pluggable `DbStatusStore` (Postgres) / `ObjectStore` db backend / `ParquetEventStore`, `SecretsProvider`.
Will need later (don't preclude now): distributed scheduler coordination, all state on shared backends
(Postgres + object store for Parquet + shared secrets), work distribution.

## Changes

- 2026-09-09: the assembly model corrected — no Maven classifier is emitted per edition.
