# Module architecture reorganisation — plan (2026-10-06, revised the same day)

> 🟡 **IN FLIGHT (2026-10-07) — decisions D-MR1…D-MR12 all taken (operator, 2026-10-06, §8); P0..P7 each have a shipped slice, none is finished.** Row: `MODULE-REORG-1` (+ the follow-up rows below). Not shipped as a whole, so the plan stays here.
>
> | Phase | State | Commits |
> |---|---|---|
> | P0 vocabulary + baseline | shipped | `08c2b2848` |
> | P1 open the registries | partial (routes, governable kinds, `features{}`/SPA nav shipped) | `0957bc0a5`, `62b82003f` |
> | P2 manifest + activator + gates | partial (P2a manifest/activator/`GET /modules`; P2b per-Space Enabled; directory regroup DONE 2026-10-07, `61d156298` + `77a7419f0`) | `ba3b20b6c`, `657527ae7` |
> | P3 packaging | partial (P3a build-id stamp + boot check; P3b manifest-synthesised 503 stubs + known-modules; no thin jars) | `eb9fb19ab`, P3b (below) |
> | P4 removal semantics | partial (P4a characterised + fixed; P4-1..P4-3 filed) | `42c98fd6d` |
> | P5 test kit + TCKs | partial (P5a kit + RouteModule TCK) | `33feba371` |
> | P6 Offerings | partial (P6a Offerings as a checked artifact; build not re-plumbed) | `b26188cb3` |
> | P7 selective moves | partial: observability `0489ac3a0`, security split `33c3e5e4d`, EgressPolicy `4eb64c000`, Kafka `e2783a9bf`, ASN pack `0c0e483d5`, Workflow models `4e863cb6d`, access module `9dc9df34c`, Reconciliation `c5cb8ea4f`, Scoring `064d3114d` + `e51a73bec` + `900e2bc0a`, condition language `e5f22ebe9` + `d3215dcfa`; modules screen `70c2ee075`; CI wiring `c404ee383`; path-agnostic guards `27b210988` | as listed |
>
> **Remaining work (each is a BACKLOG row, §4 *Module architecture*):**
> - **P1** — `MODULE-REORG-P1-FAMILY`: `OperationalDb.Family` as a contribution point; entangled with `ServiceStores`/Incidents, so it goes with the Incidents extraction. (The roster grew to **16** on 2026-10-07 with `ALERTS`, P7 slice 2 — one more hand-edited count.) Also open, no row yet: contributed OpenAPI fragments per module; RBAC capabilities contribution (P1-D3, also P2 `provides`).
> - **P2** — `MODULE-REORG-D-MR2`: directory regroup DONE 2026-10-07 (directories only; layout in §8 D-MR2); capabilities in `provides` remain.
> - **P3** — `MODULE-REORG-P3-THIN-JARS`: thin per-module jars, Offering-driven launcher classpath, jdeps-derived jlink set, per-module SBOM; signing needs split packages 8 to 0 (incl. the deliberate telecom-asn1 exception). **8 -> 3 done 2026-10-07** (acquire `70e70379d`, service `dd5712511`, entitylist `d0e4db143`, event `4e0f7bc5b`, intelligence `67bc0c97f`); left: `com.gamma.control` (4 modules) + the two deliberate telecom-asn1 splits (`ingester`, `parse`).
> - **P4** — P4-1..P4-3 already filed (`MODULE-REORG-P4-1`, `-P4-2`, `-P4-3`).
> - **P5** — `MODULE-REORG-P5-TCKS` (P5a + P5b + P5c shipped 2026-10-07; remaining blockers per class in P5c): processor-free route tests (exchange's host installs now live in `HostBootHook`; 16 more la-api route classes and geo-link `InvestigationMeasureRoutes` driven by the RouteModule TCK); TCKs for Authenticator, TokenRelay, CollectorConnectorFactory, NotificationChannel, DescriptionProvider, MaintenanceTaskProvider, JobTypeProvider.
> - **P6** — EDITIONS/FEATURE_INVENTORY generation needs a `posture{}` section and capabilities in `provides`; bundle generator driven by Offerings (rides on `MODULE-REORG-P3-THIN-JARS`).
> - **P7** — `MODULE-REORG-P7-INCIDENTS` (Incidents / Case Management split, Action Requests, Workflow & SLA slice 2 sweep behind a governed-item contract, linked-subject contract; **open design question:** `alert` depends on the object substrate via `ObjectAccess` and Alerts persist ALERT objects, contradicting "Personal has Alerts but no operational objects" unless `ObjectAccess` has a null/in-memory implementation on Personal); `MODULE-REORG-P7-KERNEL` (Decision Kernel steps 1, 3-7: Expectation non_null/range/regex onto the tree, Tag and Case Rule filters, Notification Rules, Risk filters, Escalation match, Access Policies' Conditions text compiled into the tree, retire the duplicate evaluator, Consequence registry — **slice 1 DONE 2026-10-07**, see §8b); `MODULE-REORG-P7-CONTRACTS` (contract modules shed AccessPolicies/AuditTrail/EventLog/AuditChain/InMemoryEventStore/SecretScrubber/MetricRegistry/Roles family/CapabilityManifest, order per the 2026-10-06 survey; the access cluster and the event cluster are DONE 2026-10-07, see §6).
> - **Guards / review** — `MODULE-REORG-GUARD-1` (DONE 2026-10-07 — `ImportLoaderInventoryTest` row + `ReactorModules` loops, `RouteInventoryTest` gate moved), `MODULE-REORG-REVIEW-1` (reviewer checklist).
> - **Decision Kernel** — `MODULE-REORG-P7-KERNEL`: steps 1, 3, 4, 5 (additive `when`, `b9c3271fc`) and 6 (`952538fe9`) shipped; step 7 declined; the Risk and Escalation Rule editors' `when` UI shipped 2026-10-07 (see section 8b, "Decision Kernel step 5 / step 6 SPA follow-up"); left: editing a stored Escalation Rule, duplicate-evaluator retirement (see section 8b, "step 5 / step 6 as built").
> Inputs: *Enterprise-Grade Modular Architecture Guidelines* (PDF, 4 pages) and the "System Architecture
> Topology" mock-up (four layers + a per-module inspector). Operator brief: long-term benefit across
> development, test, packaging, offering, editioning and deployment of itemized distribution; **no version
> compatibility, no shims** — change the thing and fix its call sites.
>
> ⚠ **Revision note.** The first draft of this plan mapped the PDF onto the codebase almost literally. The
> operator asked for independent judgement. §1 records where the guidelines fall short for Inspecto and where
> the first draft followed them blindly; §2–§8 are rewritten on that basis. The measured findings (§3) stand.

## 1. Where the guidelines fall short here — and what to do instead

| # | The guidelines say | Why that is not enough for Inspecto | Instead |
|---|---|---|---|
| 1 | Three module kinds: SPI, Platform Service, Feature | One list mixes three independent questions: *is it a contract or an implementation?* (build), *does a customer buy it?* (offering), *when is it bound?* (build, boot, Space, Run). A Provider and a Feature are both implementations; "Product stack" is a grouping, not a kind | Classify every module on **three axes** (§2.1). The first draft's six-kind flat list repeated the PDF's mistake |
| 2 | The Feature module is the unit of sale | A customer buys a **capability**, which spans a backend module, a UI feature, Providers, Platform Service grants, docs — and **content**: Space templates, pipeline packs, dashboards, Alert / Decision Rule sets. For an assurance product much of the value *is* content (`templates/`, `spaces/`, `packs-dev/`, Space bundles). The guidelines never itemize content | An **Offering** is a separate descriptor that composes modules **and** content packs (§2.3). Code modules are never the commercial unit |
| 3 | SPI artifacts contain zero logic | Moving `SpiSlot` (the fail-closed loader), `Roles` parsing or `ErrorCodes` out of the contract into a "kernel" does not remove a dependency — every implementer then needs the kernel too. Purity by LOC is the wrong measure | Contracts may hold logic that *is part of the contract's meaning* (validation of its own DTOs, default methods, the fail-closed loader). Contracts hold **no I/O, no state, no third-party dependency**. Judge a contract by *stability and direction* (who depends on it, how often it changes), not by its line count. The first draft's "logic in contracts → 0" measure is withdrawn |
| 4 | Itemize by module | Every module boundary costs build time, test setup and wiring. 35-line modules (`metrics`) add cost and no option | Draw a module boundary **only** when the code (a) is shipped optionally, (b) carries a distinct **third-party footprint** (size, CVE exposure, licence, export control — e.g. `connectors` bundles `sshj`, `commons-net`, `kafka-clients` and `postgresql` in one jar), (c) is swappable, or (d) needs isolation for test speed. Otherwise use packages with an enforced package rule. Fold or split modules by these four tests, not by taxonomy |
| 5 | Editions are static compositions; SPIs decide infra compatibility | Edition is one dimension of several: **capability tier × add-ons × deployment profile (air-gapped, cloud, demo) × identity mode × scale (single node vs HA lease)**. Inspecto already leaks one into another: maker-checker cannot exist on Personal because Personal has no authenticator — a *deployment* fact surfacing as an *edition* gap | Model profiles as **orthogonal** axes. Availability is the result of **requirement resolution**: a Feature declares it requires an identity contract; whether one is bound decides availability, with no hand-written edition matrix (§2.2) |
| 6 | Features call `entitlementService.assertAuthorized(tenant, feature)` | Licence checks scattered through feature code are a smell and drift. The guidelines also conflate licensing with authorisation. Inspecto's unit of tenancy is the **Space**, not a tenant | Three distinct gates at the edges, never inside feature logic: **Installed** (the jar is present and its requirements resolve) → **Enabled** (switched on for this Space) → **Permitted** (RBAC capability for this user). Features contain no licence code |
| 7 | Declare everything in the manifest | Declared flags drift from reality. Inspecto already has the better pattern: `/bootstrap`'s `features{}` is computed from `hasRoute(...)`, "never an edition guess" | The manifest declares **requirements and contributions**; **availability is computed from what actually bound**. The first draft proposed declared `featureFlag:` / `nav:` lists — that would have reintroduced drift and is withdrawn |
| 8 | Consumer-driven contract tests (Pact), schema-per-module migrations, OCI images | Built for networked microservices. Inspecto is one process; its persisted state is TOON config, per-Space stores and DuckDB/OperationalDb families | In-process **TCKs** for contracts with two or more implementers; a **platform test kit** so a Feature is tested without booting the processor; **storage-family ownership** per module instead of schema-per-module |
| 9 | (silent) | **Removing an item** is the hard part of itemized deployment: a Space config that references a node type, a Job Type or a Decision Rule consequence from a removed module; data in a store family nobody owns any more; a backup restored into an install that lacks the module. Inspecto has been bitten here (a rebuild-from-modeled-state write silently dropped every key it did not model; a stale jar hid behind the palette's silent fallback) | Define **removal semantics** (§2.5): config naming an absent capability loads **inert with a diagnostic**, is never rewritten or dropped; every store family has an owning module; backup and restore carry families of absent modules opaquely |
| 10 | (silent) | The real coupling that stops itemization in this codebase is not module purity — it is **closed central registries** that every feature must edit (§3.2) | **Open every closed registry into a contribution point** before moving any code. This is the highest-value step and the first draft did not name it |

Kept from the guidelines: one-way dependency, a Platform that never imports a Feature, Features testable
against stubs, editions as compositions, a manifest per module, TCKs, and inert-until-activated modules.
Dropped (operator brief and fit): SemVer ranges and a version BOM — replaced by a **single build-id stamp**
(every jar of an install must carry the same build id, checked at boot); adapter bridges; OCI/service-mesh
topologies; a licence engine (D-MR3).

⛔ **Vocabulary.** The PDF's "Platform Services" means horizontal modules; in this repo **Platform Service**
already means the grantable engine facility a Job or pack requests with `requires:`, and **capability** means
RBAC. The new layer is called **Platform module**; `docs/GLOSSARY.md` gets the axes, the three gates and
**Offering** in the same change as P0.

## 2. Target model

### 2.1 Three axes per module

| Axis | Values | Decides |
|---|---|---|
| **Build role** | Foundation · Contract · Platform module · Implementation | what it may depend on |
| **Offering role** | Base (always present) · Optional (part of an Offering) · Provider (chosen by deployment profile) · Internal | whether it can be absent |
| **Binding time** | Build (classpath) · Boot (activator) · Space (enabled per Space) · Run (Platform Service grant, Job Pack) | how it is switched on |

Examples: `inspecto-security` = Implementation · Provider · Boot. `inspecto-ops` = Implementation ·
Optional · Boot + Space. `inspecto-engine` = Platform module · Base · Build. A Job Pack = Implementation ·
Optional · Run.

### 2.2 Requirement resolution, not edition tables
Each manifest says what the module **provides** (contracts it implements, capabilities, routes, config kinds,
store families) and what it **requires** (contracts, other modules). At boot the activator resolves the graph:
an unsatisfied module stays inert and is reported by name with the missing requirement. Edition, add-ons and
deployment profile only decide *which jars are present*; what is available follows. Maker-checker then
becomes available wherever an identity contract is bound, with no edition rule written anywhere.

### 2.3 Offering = composition of modules and content
`offerings/<id>.toon` names modules, UI features, content packs (Space templates, pipeline packs, dashboards,
Alert / Decision Rule sets) and default Space settings. An edition is an Offering that lists other Offerings.
The bundle generator, the EDITIONS matrix and the SBOM are generated from Offerings.

### 2.4 Three gates
**Installed** (jar present, requirements resolved, build id matches) → **Enabled** (per Space; default
from the Offering) → **Permitted** (RBAC). `/bootstrap` reports all three; the SPA reads them instead of its
three hard-coded nav id sets.

### 2.5 Removal semantics
- Config that references an absent capability **loads inert**, is shown with a diagnostic naming the missing
  module, and is never rewritten, normalised away or dropped by a save.
- Each store family declares its owning module; an absent owner leaves the data untouched and unread.
- Backup, restore and Space bundles carry families and config of absent modules opaquely.
- The existing 503-for-absent-route behaviour stays, but stubs are **synthesised** from the manifests of
  modules that are known but not installed.

## 3. Findings — the codebase today

Counts were measured by read-only subagents over `git ls-files` unless marked ✔ (re-checked by me).

### 3.1 Structure

| Topic | Today |
|---|---|
| Split packages ✔ | **3** (was 7 at baseline; 2026-10-07): `com.gamma.control` across **4** modules (processor 121 files, auth-spi 24, http-spi 8, entity-store 2) and the deliberate telecom-asn1 pair `ingester` / `parse`. Fixed: `acquire` (auth-spi now `com.gamma.auth.secrets`), `service` (`OptionalSpi` -> `com.gamma.spi`), `entitylist` (store -> `com.gamma.entitystore`), `event` (audit-spi -> `com.gamma.audit`), `intelligence` (module glue -> `com.gamma.intelligence.agent`), `pipeline.exec` (earlier). They block per-module **signed jars** (a package split across jars with different signers fails at class load), not only JPMS |
| Contract modules | ~5.7k of ~6.2k LOC in `audit/auth/http-spi` is concrete. Per §1 #3 that is acceptable where it is the contract's own semantics (`SpiSlot`, DTO validation) and not acceptable where it is policy or state (`AccessPolicies`, `EgressPolicy`, `AuditTrail`, `InMemoryEventStore`) |
| Feature code in Platform | `inspecto-engine` (63k LOC) holds ~15k LOC of feature code (`query` 5.0k, `notify` 3.5k, `alert` 2.0k, `objects` 1.5k, `catalog` 1.8k, `risk` 0.9k) plus feature Job types inside `job`. The processor holds ~25 feature route files (~7–9k LOC). `inspecto-entity-store` imports `com.gamma.control` (lower imports upper) |
| Lower layers | Clean: api, util, config, sql, etl, event, acquire import nothing feature-like |
| Isolation | The five `la-*` modules do not depend on the processor. Of 15 others, all declare it; 7 import none of its classes. `HostContext.service()/.spaces()` return concrete `CollectorService` / `SpaceManager` |
| Extension mechanism | Sound: ~35 `ServiceLoader` contracts, a fail-closed `SpiSlot`, absent routes answer 503, `features{}` computed from bound routes |
| Packaging | One shaded `inspecto.jar` (~94 MB) plus sidecar jars, a `jlink` runtime with a hand-kept module set, the UI dist. Only Job Packs can be added at run time |
| Tests | One TCK (`InvestigationStoreContract`). CI runs one all-modules reactor (`-Pedition-enterprise`); no per-edition assembly test |

`ControlApi` is **not** a god class (1.6k LOC; routes already live in ~70 `RouteModule`s). `policy` →
`ops`/`geo-link` is **test scope only**. ~415 route registrations (grep estimate).

### 3.2 Closed central registries — the real itemization blockers

| Registry | Where | What adding or removing a feature costs |
|---|---|---|
| Built-in route list ✔ | `ControlApi.java:585-596`, a `List.of(...)` | a core edit per feature |
| Absent-module 503 stubs | `Absent*Routes` in `com.gamma.control` (one hand-written class each) | a core class per optional feature |
| Feature flags | `BootstrapRoutes.features()`, one `hasRoute` probe per feature | a core edit |
| SPA nav gating | three hard-coded id sets in `navigation.service.ts` | a UI edit that drifts from the backend |
| RBAC capabilities ✔ | `CapabilityManifest.ENTRIES` (auth-spi) → `Roles.KNOWN_CAPABILITIES` | a contract-module edit per capability |
| Governable config kinds ✔ | `ApprovalPolicy.GOVERNABLE` (processor) | a module's own kind cannot be put under maker-checker |
| API contract ✔ | one `docs/api/openapi-v1.json` | a new route fails the reactor in a module never touched |
| Edition / bundle lists | 4 pom profiles, `tools/bundle-modules.mjs`, two `$modules` strings in `inspecto/package.ps1`, `check-module-deps.mjs`, `docs/EDITIONS.md`, `docs/FEATURE_INVENTORY.md` | 5+ files per module; Preview is a manual union |
| `jlink` module set | `$runtimeModules` in `package.ps1` | a module needing a `jdk.*` module breaks the zip |
| Operational store families | `OperationalDb.Family` (processor) | a new store family is a core edit (§8c) |

✔ **The counter-example to copy:** `EventType` is deliberately open — `Event.type` is a free-form string
and the class holds conventions only, so a module emits a new type without touching it. Every registry above
should work the same way: modules **contribute** entries; the platform validates uniqueness at boot.

## 4. Where every module goes

"Move?" applies §1 #4: code moves only when it is optional, has its own footprint, is swappable, or needs test
isolation.

| Module (today) | Build role · Offering role | Move? |
|---|---|---|
| api, util, config, sql, etl, acquire, event | Foundation · Base | no |
| asn-decoders + engine `Asn1ParserPlugin` | Implementation · Provider (Telecom industry pack, §8a) | **yes** — ✅ DONE 2026-10-07 (P7): new `inspecto-telecom-asn1` (module id `telecom-asn1`, `offeringRole: provider`, `provides.contracts: ParserPlugin`; the manifest schema has no capability/feature slot for a parser, so none) holds `Asn1ParserPlugin`, `Asn1GrammarSource`, `Asn1RecordIngester` + `Asn1ParserPluginTest`, `Asn1RecordIngesterTest`, the ASN.1 half of `DemoCorpusIngestTest` (now `DemoAsn1CorpusIngestTest`), its own `ParserPlugin` services file, the `asn-facade` dependency (the nested standalone reactor stays where it is) and a shaded sidecar. The engine lost the three classes, the `asn-facade` dependency and the Asn1 services line. **Decision — FQCN/package: UNCHANGED, split package ACCEPTED for `com.gamma.parse` and `com.gamma.ingester`.** `com.gamma.ingester.Asn1RecordIngester` is a PERSISTED config string (`frontend: asn1` synthesises it in `PipelineConfigParser`, hand-written `plugin.ingester` names it, `DecodeProfile`/`ParserRoutes`/bundles carry it as a string, and a user's saved Pipeline TOON may name it); renaming the package would need an alias in config parsing plus a rewrite of saved configs for zero functional gain, and breaking changes to *code* are free but to *persisted strings* are not. No shipped example/demo/template/Space TOON names the FQCN (grep of `spaces/`, `inspecto/examples`, `templates`), only tests and the sugar's synthesis. Cost: `check-module-architecture` split packages **6 → 8** (`com.gamma.parse`: engine + telecom-asn1; `com.gamma.ingester`: engine + telecom-asn1) — a KNOWN, DELIBERATE exception to the "split packages gone" end state (§6 P3, and the per-module jar-signing remark), to be retired only by a package rename + persisted-config migration if jar signing ever needs it. **Catalog honesty:** `ProcessorCatalog.Pack` marks `parser.asn1.ber` as provided by `inspecto-telecom-asn1`; `asMap()` resolves its status at READ time from whether `Parsers` holds an `asn1` plugin — absent → `planned` (the palette's existing inactive rendering; never addable because the node type is withheld), `requires: inspecto-telecom-asn1`, note naming the module; present → `delivered` (+ `requires`). The static `PROCESSORS` row stays DELIVERED (the board/doc counts describe the pack's delivery), the committed contract describes a fully-equipped install, `ProcessorCatalogContractTest` pins the absent state with a registered stub, and the module's `ProcessorCatalogPackTest` pins the present state with the real plugin; `PluginIngesters.notLoaded` now names the module for the ASN.1 ingester. **⚠ Behaviour change — Personal no longer ships the ASN.1 decoder:** bundled from Professional, Enterprise and Preview (and the `-DemoAuth` demo build) exactly where `inspecto-connectors-kafka` is; `offerings/professional.toon` gains the `telecomIndustryPack` add-on (`built`, `telecom-asn1`); `docs/EDITIONS.md` SP-PRS-06 Personal cell is now `—`. Consequences to know: the sample `inspecto/examples/02-parsing/asn1-frontend`, the demo Space's `msc_cdr` and the default Space's `asn1_example` pipelines need the pack and fail naming it on Personal (no Personal content pack uses `frontend: asn1`; verified by grep). Wiring touched: root pom (default reactor, like `inspecto-intelligence` — the `inspecto` module's tests take it at test scope), `bundle-modules.mjs`, `package.ps1` (build list, sidecar find/copy/verify incl. services-entry check, launcher classpaths, demo list, boot-smoke), `check-launchers.mjs`, `dependencies.lock` header, `.gitignore`, `render-processor-board.mjs` (SP-PRS-06 and SP-ACQ-09 Personal cells are now generated, not hand-edited). |
| audit-spi, auth-spi, http-spi | Contract · Base | keep as three; move **policy and state** (`AccessPolicies`, `EgressPolicy` — DONE 2026-10-06 → `inspecto-util` `com.gamma.util.egress`, `AuditTrail`, `InMemoryEventStore`) out; keep `SpiSlot`, DTO validation, `ErrorCodes`. **Findings 2026-10-07 (P7 contracts attempt, nothing moved):** (a) `AccessPolicies` CANNOT move to `inspecto-policy` — policy is Enterprise-only while `AccessRoutes`/`ActionRequestRoutes` (core) use it in Professional, and the `AccessDecider` SPI itself returns `AccessPolicies.Policy` (auth-spi would need policy: a cycle). It also reaches `Roles`, `ApiException`, `RequestAttrs`, `WriteGates` (all auth-spi), so no module below auth-spi can hold it. **DONE 2026-10-07 (recipe applied, no cycle; `AccessPolicies` in auth-spi now holds no I/O, Logger or `AtomicFiles`):** keep in auth-spi only the contract — `ACTIONS`, `RESOURCE_KINDS` and the `Policy`/`Doc`/`Warning` records — and move the store (cache, `load`/`effective`, `validate`, `lint`, `write`, `requireLoadableUnder`, Logger, `AtomicFiles`/`JToon` I/O) to a new core class `AccessPolicyStore` in `inspecto` `com.gamma.control` (same package, no new split); rewrite 3 core call sites + `PolicyEngine` + its test. The guard inventory row was approved by the operator and added: `ConfigWriteFunnelTest.WRITER` matches `\w*store\w*.write(` so the moved call makes `AccessRoutes#savePolicies` a writer that does not reach the hold; the row is `Map.entry("AccessRoutes#savePolicies", <reason: the Access Policies document is not a ComponentStore kind; ApprovalPolicy.GOVERNABLE excludes it>)` (use `NOT_GOVERNED`). Do NOT rename the class to dodge the regex. (b) `AuditTrail` is package-private and `RowScope` (auth-spi) calls `AuditTrail.policyDecision`; `RowScope` calls `AccessDeciders` and the `Roles` family; `ApiContext` (http-spi), `ControlApi`, `AuthRoutes`, `PendingChangeRoutes`, `SchedulerRoutes`, `RefusalConfigAudit` and `inspecto-intelligence` use it. It cannot leave auth-spi alone: it must move WITH `RowScope` (+ `AccessDeciders`) into `inspecto-http-spi` or core (both already above auth-spi and audit-spi, same package `com.gamma.control`), making the moved methods public — i.e. the Roles-family step. It is not a file-writer (the earlier survey note was wrong): it classifies HTTP calls into `EventLog.emit`. `EventLog` lives in `inspecto-audit-spi` (`com.gamma.event`) so no lower-module drag. (c) `SecretScrubber` not attempted (order: stop after a blocked step). **Next-step recipe:** one move for the cluster `AccessPolicies` store + `AuditTrail` + `RowScope` + `AccessDeciders` + `Roles` family → core/`http-spi` (`com.gamma.control` stays split by design), (the `WRITERS` row is in); then `EventLog`/`AuditChain`/`InMemoryEventStore`/`MetricRegistry` (200+ importers) to `inspecto-event`. **DONE 2026-10-07 (`9dc9df34c`):** the cluster went to a new Base module `inspecto-access` (`com.gamma.access`), not core/http-spi; `RequestAttrs` stayed in auth-spi (a cycle: see §6 "P7 contracts: access module as built"). `EventLog`/`InMemoryEventStore`/`SecretScrubber`/`MetricRegistry` **DONE 2026-10-07 (`fdc98a488`)** -> `inspecto-event` behind `EventSink`; `AuditChain` split (static contract stays, writer to `AuditChainLinker`); see §6 "P7 contracts: event cluster as built". |
| engine execution core (`pipeline`, `pipeline.exec`, `consignment`, `inspector`, `enrich`, `parse`, `ingester`, `signal`) | Platform · Base | no |
| engine `job` framework | Platform · Base | split framework from feature Job types **only** for types whose feature is Optional |
| engine `objects` + `ops` object substrate | Implementation · Optional, Professional up (§8a, D-MR11) | **yes** → an **Incidents** module (stores, notes, links, tags, INCIDENT); `Workflow`/`SlaPolicy`/`EscalationRule` → Workflow & SLA — ✅ slice 1 DONE 2026-10-07 (P7): new Base leaf `inspecto-workflow` (module id `workflow`, `buildRole: foundation`, `offeringRole: base`, package `com.gamma.workflow`, no split) holds the three models **plus `ObjectType`** (the engine's `ObjectAccess`/`FindingsSpec`/`IncidentAccess` and the core `ComponentRoutes` need it, so the module must sit below the engine; Base, not optional, and the add-on stays optional only in slice 2). The SLA sweep (`ObjectService.sweepIncidentSla`) still lives in `ops`: slice 2 needs the governed-item contract; CASE/TASK/Case Rules stay in `ops` = Case Management |
| engine `notify`, `alert`, `query`, `catalog` | Implementation · Base (§8a) | **no** — stay in the engine; enforce package boundaries instead |
| engine `risk` + `RiskScoreJobType` + `RiskScoreRoutes` | Implementation · Optional (§8a add-on) | **yes** → **Scoring & Lists**, with `entity-list` — ✅ DONE 2026-10-07 (P7): new `inspecto-scoring` (module id `scoring`, `offeringRole: optional`, `provides.features: scoring`, `contracts: JobTypeProvider, ComponentKindValidator`, `requires: entity-list`, package `com.gamma.risk`, no split package) holds `RiskScoreModel`, `RiskScorer`, `RiskScoreEvaluator`, `RiskScoreJobType` (`risk.score` through the `ServiceLoader` `JobTypeProvider` loop; data root from the running Space), `RiskScoreRoutes` (feature id `scoring`) and `RiskScoreKindValidator`, plus their tests (`ControlApiRiskScore*`, `RiskScore*`, `PendingAlertRulesTest`, `PaymentFraud*`). Three commits: (A) `EvidenceMasker` → engine `com.gamma.mask` (`of(...)` takes Dataset ids, no model type); (B) `ComponentKindValidator` SPI in `inspecto-http-spi` + `com.gamma.alert.RiskScoreOutputs` (kind, `risk_scores_` prefix, `_latest`, owner marker, `ownedBy`) — `PendingAlertRules` stays core; DECISION: without the module a `risk-score` component is accepted as opaque config and `risk_scores_` is not reserved (the reconciliation precedent; nothing can evaluate it); (C) the module, `WatchListFeed` moved to `inspecto-entity-list` (`com.gamma.entitylist`, the module implementing it — not entity-store, which is shaded into the LA jars and would not reach a thin scoring jar), `AbsentRiskScoreRoutes` 503 stub (closed absent-stub registry 7 → 8; `ScoringAbsentSurfaceParityTest`, `NoScoringShipsInThePersonalBuildTest`, `RiskScoreRoutesContractTest`). **BEHAVIOUR CHANGE: Personal no longer ships Risk Scores** (`/risk-scores*` 503, `features.scoring=false`, `risk.score` unknown). `offerings/professional.toon` `scoringLists` is `built` with `modules: [entity-list, scoring]`; bundles Professional 21, Enterprise/Preview 24. `openapi-v1.json` byte-equal; `route-gating.md` path/line shift only. |
| `ReconRunJob` + `ReconRoutes` | Implementation · Optional (§8a add-on) | **yes** → **Reconciliation** — ✅ DONE 2026-10-07 (P7): new `inspecto-reconciliation` (module id `reconciliation`, `offeringRole: optional`, `provides.features: reconciliation`, `contracts: JobTypeProvider, ComponentDeleteHook`, package `com.gamma.recon`, no split package) holds `ReconRoutes` (feature id `reconciliation`), the comparison engine (`ReconService`, `ReconBreaks`, `ReconConfigLoader`, `ReconStateStore` — moved out of engine `com.gamma.query`), `ReconRunJob` + the new `ReconRunJobType` (`recon.run` through the `ServiceLoader` `JobTypeProvider` loop; descriptor `requires: [objects]`, the Job resolves the Space data root from `SpaceConfigRoot` and the Incident seam from `JobContext.services()` at run time), and the tests (`ControlApiRecon*`, `Recon*Test`, `TelcoRaGoldenTest` + `TelcoRaCorpus`). Core kept: the `reconciliation` config component kind (`ComponentStore`/`ComponentRegistry`/`ComponentIntegrity`), the `/recon/` throttling prefix, the CapabilityManifest rows (`withCapability` literals unchanged), and a new `AbsentReconRoutes` 503 stub (**the registry of absent-module stubs grew by one — it is the known closed list**, parity pinned by `ReconAbsentSurfaceParityTest`, absence by `NoReconciliationShipsInThePersonalBuildTest`). Two small contracts were needed and added: `ComponentDeleteHook` in `inspecto-http-spi` (the processor's `DELETE /components/reconciliation/{id}` used to call `ReconStateStore.delete` directly; the module now cleans its own state) and a null-safe grant in `PlatformServiceRegistry` (an `objects` service bound to `null` — no `inspecto-ops` — is granted as absent instead of throwing from `Map.copyOf`, so a bundle without ops keeps `recon.run` signal-only). **BEHAVIOUR CHANGE: the Personal edition no longer ships Reconciliation** (Standard, Professional, Enterprise and Preview profiles / bundles only; EDITIONS CP-10 P cell now `—`). `offerings/professional.toon`: `reconciliation` add-on is now `built`. `openapi-v1.json` is byte-equal (the stub table carries the ten paths); `route-gating.md` regenerated for path/line shift only (no gating changed). |
| `ActionRequests`, `ActionDispatcher`, `ActionRequestRoutes` | Implementation · Optional (§8a add-on) | **yes** → **Action Requests** |
| processor platform half (`control` plumbing, `service`) | Platform · Base | no |
| processor other feature routes | Implementation · Base | register by `ServiceLoader` (P1) — no physical move |
| ops, exchange, entity-list, events, backup, agent, intelligence | Implementation · Optional | keep; trim the processor dependency to narrow interfaces |
| metrics (1 file), events (1 file) | Implementation · Optional, Professional up | **merge** into one Observability module (D-MR5) — ✅ DONE 2026-10-06: `inspecto-observability` (module id `observability`; Java packages `com.gamma.metricsapi` + `com.gamma.eventsapi` unchanged; both RouteModules in one services file; directory stays at repo root until the regroup step) |
| connectors | Implementation · Provider | **split per third-party footprint** — ✅ DONE 2026-10-07 (P7 decision, §1 #4 boundary test): only **Kafka** has its own footprint (kafka-clients 3.9.x) → new `inspecto-connectors-kafka` (module id `connectors-kafka`; `KafkaConnector`, `KafkaConnectorFactory`, `KafkaConnectionWorkbench` in new package `com.gamma.acquire.kafka` — no split package; shaded sidecar; Professional up). `PatternFilter` moved to `inspecto-acquire` (`com.gamma.acquire`) because both connector modules use it. **SFTP/FTP/FTPS, DB export and the object stores (S3/Azure/GCS) stay in `inspecto-connectors`:** the object stores use only the JDK HTTP client + gson (no distinct third-party footprint) and DB export is entangled with the SSH tunnel (`SshTunnel`) the file connectors share. ⚠ **Behaviour change: Personal no longer ships Kafka** (§8a puts Premium Connectors outside Base); `offerings/professional.toon` `premiumConnectors` add-on is now `built` on `connectors-kafka` |
| security | Implementation · Provider | **split into three** providers now (D-MR6) — ✅ DONE 2026-10-06: `inspecto-oidc` (module id `oidc`: `OidcAuthenticator`, `OidcTokenRelay`, `RoleMapper`; Authenticator + TokenRelay; Nimbus, shaded sidecar), `inspecto-secrets` (`secrets`: `FileKeystoreSecretsProvider`; SecretsProvider; no third-party dependency, thin jar), `inspecto-geo-country` (`geo-country`: `MaxMindGeoCountryResolver`; GeoCountryResolver; maxmind-db, shaded sidecar). Names follow the glossary words *Authenticator* / *Secrets* and the `geo-country` seam; the one Java package `com.gamma.security` spanned all three, so it became `com.gamma.oidc`, `com.gamma.secrets`, `com.gamma.geocountry` (no split package). Every edition that shipped `inspecto-security` ships all three; offering `modules[15]` lists `oidc,secrets,geo-country`; per-offering trimming is a later step |
| demo-auth, notify-channels, agent-hosted, policy, la-store-pg | Implementation · Provider | keep; govern |
| la-core, la-api, la-graph, la-storage | Platform + Implementation · Optional | keep as one Link Analysis family |
| geo-link | Implementation · Optional | keep; it adapts six LA ports to platform data |
| inspecto-ui | UI | read the three gates from `/bootstrap` (P2) |

## 5. Design notes

- **Manifest** (TOON, `META-INF/inspecto/module.toon`): `id`, build role, offering role, `provides`
  (contracts, capabilities, routes, config kinds, store families, OpenAPI fragment), `requires`,
  `entitlementKey` (reserved, unused — D-MR3). No declared availability flags.
- **Contribution points** replace §3.2: route modules by `ServiceLoader` only; 503 stubs synthesised from
  manifests of known-but-absent modules; capabilities, governable kinds and OpenAPI fragments contributed per
  module and merged at boot (the OpenAPI document becomes a generated artifact, checked for collisions).
- **Packaging:** thin core jar + one jar per module in `modules/`; the launcher builds the classpath from the
  Offering; per-module SBOM; the `jlink` set derived with `jdeps` from the jars actually shipped; the build-id
  stamp checked at boot (**partly done, P3a**: stamped on every jar and checked at boot; the thin-core + `modules/` split is not). Per-module jars can then be signed — which requires the split packages gone.
- **Test:** a **platform test kit** (real-HTTP control plane over in-memory stores, a fake Space, a fake
  authenticator with two principals for four-eyes) so a Feature's tests do not boot the full processor;
  TCKs for every contract with two or more implementers (`Authenticator`, `TokenRelay`, `RouteModule`,
  `CollectorConnectorFactory`, `NotificationChannel`, `DescriptionProvider`, `MaintenanceTaskProvider`,
  `JobTypeProvider`) and for every contract a third party may implement; per **assembly** tests — each Offering
  boots, `/modules` matches its composition, and each Optional module's absence is tested once (the existing
  absent-parity tests generalised) — no combinatorial edition × feature matrix.
- **Selective test runs:** the manifest graph gives a reliable "which modules does this change affect" answer —
  `codegraph affected` is known broken and must not be used for this.

## 6. Phases — ordered by value to itemization

Each phase ends green: affected tests per change (`-pl <module> -Dtest=A,B`), the full reactor once at phase
end, and every `ci.yml` no-build guard before any push.

| Phase | Scope | Exit measure |
|---|---|---|
| **P0 Vocabulary + baseline** (S) | GLOSSARY: axes, three gates, Offering, Platform module. Report-only guards: split packages, closed registries count, modules per axis, domain vocabulary inside generic engines (§8a). OKF concept `backend/module-taxonomy.md` | baseline numbers recorded |
| **P1 Open the registries** (L) | Route list → `ServiceLoader`; synthesised 503 stubs; contributed capabilities, governable kinds, OpenAPI fragments; `features{}` and SPA gating from bound state | adding an Optional feature touches **only its own module** (proved by adding a sample module in a test) |
| **P2 Manifest + activator + three gates + regroup** (L) | regroup directories and artifactIds by kind (D-MR2); `module.toon` everywhere; requirement resolution; `GET /modules` (the mock-up's topology view, fed by the live graph); per-Space Enabled; build-id stamp | an unsatisfied module is inert and named; Personal reports maker-checker as "requires identity" |
| **P3 Packaging** (M/L) | thin jars, Offering-driven launcher classpath and bundle, `jdeps`-derived `jlink` set, per-module SBOM; split `connectors` by footprint; fix the 7 split packages | one edition source of truth; per-Offering boot test in CI |
| **P4 Removal semantics** (M) | inert config with diagnostics, store-family ownership, opaque backup/restore of absent families | removing a module and re-adding it loses nothing (tested) |
| **P5 Test kit + TCKs** (M) | platform test kit; TCKs listed in §5; drop the processor dependency from the 7 modules that import none of it | a Feature's test suite runs without `inspecto-processor` |
| **P6 Offerings with content** (M) | `offerings/*.toon` composing tier + add-ons + function packs + industry packs (§8a); EDITIONS / FEATURE_INVENTORY generated | a pack is an itemized, installable unit; a template that `requires` two packs installs only where both are present |
| **P7 Selective code moves** (M, was L–XL) | Per §8a only: `Workflow`, `SlaPolicy`, `EscalationRule`, the business calendar and the SLA sweep → a **Workflow & SLA** module behind a governed-item contract; `ActionRequests`, `ActionDispatcher`, `ActionRequestRoutes` → an **Action Requests** module behind a linked-subject contract; the object substrate (engine `objects` + the `ops` stores, notes, links, tags, `ObjectRoutes`) and INCIDENT → an **Incidents** module; CASE/TASK/Case Rules stay in `ops` as **Case Management**; `risk` + its Job Type + routes → **Scoring & Lists** (with `entity-list`); recon Job + routes → **Reconciliation**; `asn-decoders` + `Asn1ParserPlugin` → Telecom industry pack; `metrics` + `events` → one Observability module (D-MR5); `inspecto-security` → three providers (D-MR6); the nine rule kinds onto the Decision Kernel after the spike (D-MR12); split `connectors` into a core and premium connector packs; contract modules shed policy/state. `alert`, `notify`, `query`, `catalog` stay | each move justified by a §1 #4 test |

The approval-SPI extraction (archived 2026-10-06 as superseded: `archived-documents/plans-archive/approval-spi-extraction-plan.md`) folds into P1: governable kinds become a
contribution point, which is what that plan's D-AS3 asked for.

### P6a as built (2026-10-06 — Offerings as a checked artifact; additive, the build is NOT re-plumbed)
- `offerings/{personal,professional,enterprise,preview}.toon` at the repo root: the four editions as the profiles and
  `bundle-modules.mjs` have them today. Professional `includes` Personal, Enterprise includes Professional, Preview
  includes Enterprise (verified subset-shaped: Preview's set is byte-identical to Enterprise's, as EDITIONS says).
  Each lists `modules` (manifest ids), `addons` (built = names modules that exist today; planned = `hostedBy` where the
  code lives until its P7 move), `contentPacks` (the six `spaces/_templates/*` the bundle ships in every edition;
  `packs-dev/*` are dev-only and not shipped) and an empty `defaultSpaceSettings` placeholder.
- `tools/check-offerings.mjs` (+ `.test.mjs`, 15 cases) fails on: a module id with no manifest; an Offering whose module
  set differs from `bundleModules(edition)` or from the edition pom profile (profile = bundle set minus the default-reactor
  modules); a `requires.modules` edge leaving the Offering; a built add-on naming a missing or not-included module; a
  missing content pack; an unknown include or a cycle. **Always-present rule (documented at the top of the script):**
  decided on manifest `offeringRole`, not `buildRole` — `base`/`internal` modules (only `processor` is staged) are never
  listed and are ignored on both sides; `connectors` is `provider` and ships everywhere, so Personal lists it.
  Printed as one line in `check-module-architecture.mjs`'s report. **Wired into the `ci.yml` guards job (2026-10-07)**, with the architecture report and a `--ratchet` regression gate: `tools/module-architecture-baseline.json` holds `splitPackages` (8) and `populatedRegistries` (8); CI fails only if a count RISES — lower the file as work lands. Per-registry sizes (routes, capabilities) are not ratcheted: they grow legitimately.
- **Finding — `docs/EDITIONS.md` Packaging row is stale** against the profiles: Professional "NINE optional modules, 12
  staged jars" vs the real 14 optional modules (Personal 2 + 14 = 16 first-party jars, +`postgresql.jar`); the row omits
  `entity-list`, `la-graph`, `la-storage`, `la-core`, `la-api`; Enterprise "10 modules, 13 staged jars" vs 19 first-party
  (adds `policy`, `la-store-pg`, `intelligence`). `pom.xml` `edition-standard` is a legacy alias of Professional.
- **What generating EDITIONS / FEATURE_INVENTORY would need (not done, per instruction):** per-edition module and jar
  counts from the Offering closure (already derivable here); the non-module matrix rows (transport, AuthN, secrets,
  compliance) have no home in an Offering yet — they need a `posture{}` section; capability rows need each manifest's
  `provides.features` plus capability ids (P1-D3, not yet in `provides`); the add-on `status` field is what separates
  "shipped" from "planned" in a generated table. `defaultSpaceSettings` stays `{}` until a real default is decided.

### P7 Scoring & Lists survey (2026-10-07 — written read-only; the extraction it scheduled shipped the same day, see the §4 row)

**Verdict at survey time: STOP — the code could not leave the core behind one small interface (superseded: the three contracts below were built the same day).** Reconciliation (same day) needed one
tiny SPI (`ComponentDeleteHook`) and a null-safe grant; risk scoring is wired into the core in three further places
that each need their own contract, plus one package move that is a precondition. Forcing it would have shipped a
module whose absence breaks the save gate, the template seed gate and the pipeline masker.

**What is in scope today.** Engine `risk`: `RiskScoreModel` (311 LOC), `RiskScoreEvaluator` (331), `RiskScorer` (94),
`WatchListFeed` (36, a `ServiceLoader` seam already implemented by `inspecto-entity-list`'s `RiskWatchListFeed`),
**`EvidenceMasker` (246 — not scoring at all, see 1)**; engine `job.RiskScoreJobType` (129, package-private, registered
by `JobService` line 562 with a `dataDir`); processor `control.RiskScoreRoutes` (338, `/risk-scores*`, capability
literals in `CapabilityManifest` ~line 183); tests `RiskScore*Test`, `RiskScorerTest`, `RiskScoreJobTest`,
`RiskScoreAlertTest`, `PaymentFraudTemplateGoldenTest`, `ControlApiRiskScoreTest`, `RiskScoreWatchListFeedTest`.

**The three entanglements (each is a real core caller, found by grepping the symbols, not the file list):**

1. **`EvidenceMasker` lives in `com.gamma.risk` but is the platform's masking and classification resolver.** Callers:
   `RowShaper` + `MaskSpec` (pipeline executor), `PostgresPublishJobType`, `AuditReadMasking` (processor),
   `EngineDatasetProvider` + `HostCollectorCoveragePort` (`inspecto-geo-link`). It must stay below any scoring
   module. *Precondition, no contract needed:* move it (with its `EvidenceMaskerTest`) to a new engine
   package (`com.gamma.mask`), 7 call sites + 4 doc comments, then `com.gamma.risk` holds scoring only.
2. **The save gate knows `risk-score`.** `ComponentRoutes.validateKind` (a static reached by the authoring route,
   `BundleRoutes`, `BiTemplates` and `DatasetRegistration`, and by the template stager through the explicit-roots
   overload) calls `RiskScoreModel.fromMap`, `RiskScoreRoutes.requireStorable` (Schema columns and output-name
   collisions against the registry) and — for every `dataset` / `sink` — `RiskScoreRoutes.requireNotReserved` (the
   `risk_scores_*` output stores are reserved). *Smallest contract:* a `ComponentKindValidator` SPI in
   `inspecto-http-spi` — `String type()`, `void validate(String id, Map content)`, `void requireStorable(Path writeRoot,
   Supplier<Path> dataRoot, String id, Map content)`, `void requireNotReserved(Path writeRoot, String type, String id,
   Map content)` — looped over `OptionalSpi` inside `validateKind`. Absent module = the `risk-score` component kind
   stays accepted as opaque config (as `reconciliation` is today) but nothing validates it; an explicit decision is
   needed on whether that is acceptable or whether the kind must also be refused when the module is absent.
3. **`PendingAlertRules` (TEMPLATE-RISK-SCORE-ALERT-RULE-1) is a core feature built on the risk model.**
   `PendingAlertRules` reads `RiskScoreModel.SCORES_PREFIX/LATEST_SUFFIX` and `RiskScoreEvaluator.ownedBy`;
   `TemplateSeedGate`, `AlertRoutes` (`GET /alerts/pending`), `ImportCapabilityGuard`, `PendingChanges` and
   `CollectorService` (the `risk.score.produced` subscriber) all reach it. *Smallest contract:* keep
   `PendingAlertRules` in the core and move only the **three naming/ownership facts** it needs into a tiny core holder
   (`SCORES_PREFIX`, `LATEST_SUFFIX`, `ownedBy`) that the module also uses — no SPI — because the pending-rule
   mechanism is about Alert Rules, not scoring; the alternative (moving the feature into the module behind a
   `TemplateSeedCheck` + signal-listener SPI) touches five core files and is not recommended.

**Decision on the shape (recorded now so the next session does not re-litigate it): two artifacts, one add-on.**
Keep `inspecto-entity-list` as it is (its name stays honest) and create **`inspecto-scoring`** (module id `scoring`,
package `com.gamma.risk`, feature id `scoring`, `RiskScoreRoutes`, `RiskScoreJobType` → `ServiceLoader`
`JobTypeProvider`, `AbsentRiskScoreRoutes` 503 stub). `offerings/professional.toon` `scoringLists` becomes
`built` with `modules: [entity-list, scoring]`. Renaming `inspecto-entity-list` to absorb risk would cost a
full-sweep rename (pom profiles, bundle-modules, package.ps1 ×9 sites, launchers, docs) for no gain, and the
direction of dependency must be `scoring → entity-list`: **`WatchListFeed` moves down into `inspecto-entity-store`
or `inspecto-entity-list`** (the module implementing it), not the other way round, or the feed could not be implemented
without a cycle. Existing feature id `entityList` is untouched (the five legacy keys never change); the new key is
`scoring`.

**Build order when it is scheduled:** (a) move `EvidenceMasker`; (b) `ComponentKindValidator` + the three-fact holder;
(c) move `WatchListFeed`; (d) create `inspecto-scoring` as for Reconciliation (`ComponentDeleteHook` is not needed —
risk output stores are Datasets, not component-side state); (e) the `RiskScoreRoutes` capability literals stay literal in
the module and the `CapabilityManifest` rows are unchanged; (f) tests: `ControlApiRiskScoreTest`, `RiskScoreJobTest`,
`RiskScoreAlertTest`, `PaymentFraudTemplateGoldenTest` move with it (the last drives the engine alert path, which the module sees through its
`inspecto-processor` dependency). **Behaviour change to record when built:** Personal loses `/risk-scores*`
and the `risk.score` Job Type (it keeps the Entity-List-less `WatchListFeed` absence it has today).

## 6-A. P0 baseline (recorded 2026-10-06, `node tools/check-module-architecture.mjs`)

✅ P0 shipped: GLOSSARY §15, OKF `backend/module-taxonomy.md`, the report-only guard (not wired into CI).
Baseline: **7** split packages · **10** closed registries (built-in route list 59, `Absent*` stubs 6, `hasRoute`
probes 6, SPA nav ids 9, capabilities 216, `OperationalDb.Family` 8, `jlink` set 13, `openapi` paths 423) ·
35 modules with main Java · telecom words in generic code: engine 51, alert 4, query 4, la-api 2, la-graph 3
(possibly similarity `sim`), la-core/storage/store-pg 0 → "LA for other domains" looks like content, not code.
P1 survey: the built-in route list is at `ControlApi` `:590-604`; optional modules already load via
`OptionalSpi.all(RouteModule.class)`; registration is first-match so the built-in order is load-bearing — step 1
needs an `order()` on `RouteModule` and a golden registration-order test.

### P1 decision log
- **P1-D1 (2026-10-06): the built-in route list stays an explicit list.** Optional modules already load through
  `OptionalSpi.all(RouteModule.class)` and are appended after the built-ins, so "an Optional feature touches only
  its own module" already holds for *routes*. Moving the ~58 core built-ins (mostly package-private classes, in a
  first-match order that is load-bearing) to `ServiceLoader` would cost making them public and buy no itemization.
  The remaining route-side blocker is the hand-written `Absent*Routes` stubs, which needs the P2 manifests (the
  surface of an absent module must be known from somewhere that is present). P1 therefore targets the other
  registries first: capabilities, governable kinds, `OperationalDb.Family`, `features{}` and SPA nav gating.
- **P1-D2 (2026-10-06): governable kinds opened first** — `GovernableKindProvider` (`inspecto-http-spi`), the processor
  unions fail-soft providers; `inspecto-entity-list` contributes `entity-list`. An absent module's kind is not
  governable (a policy naming it is refused 422).
- **P1-D3 (2026-10-06): RBAC capability contribution DEFERRED to P2.** Only 8 of 216 capabilities are exclusive to
  an optional module (4 `exchange`, 4 `la-api`); moving them drags `Roles.CAN_*` constants, `SEED` grants and the
  literal-only scanner guards (`CapabilityManifestTest`, `route-gating-report`, authgate) — guard edits that need the
  operator — and makes the role vocabulary depend on the classpath. It lands with the P2 manifest, where `provides`
  names the capabilities and the scanner can be made per-module. Recipe: `CapabilityContributor` SPI in `auth-spi`,
  contributors use string literals only (class-init cycle `Roles → CapabilityManifest → contributor → Roles`).
- **P1-D4 (2026-10-06): `features{}` / SPA gating** — `RouteModule.featureIds()` default, collected only after a
  successful `register()`; five legacy keys kept; SPA `navFeature` replaces the three id sets.

### P2a as built (2026-10-06 — manifest, activator, `GET /modules`; the directory regroup is NOT done)
- `inspecto-util` `com.gamma.module`: `ModuleManifest`, `ModuleManifests.load(ClassLoader)` (fail-soft per file; enum
  fields and duplicate ids validated), `ModuleActivator.resolve` (fixpoint from all-active; reasons name each unmet
  module or contract). `module.toon` is in all 34 modules with `src/main/java` (33 under `inspecto*` plus
  `asn-decoders` in the nested `asn-facade`); roles follow §4. `GET /modules` = `ModulesRoutes`, appended LAST in
  `ControlApi`'s list, read-only, no capability, one generated skeleton in `openapi-v1.json`.
- **P2a-D1: `provides.features` is checked against the code, not trusted** — `ModuleManifestGuardTest` fails when a
  module with main Java has no manifest, or its features differ from the strings in its `featureIds()`; it also
  resolves the whole source tree and demands every module ACTIVE. `check-module-architecture.mjs` prints
  *modules without manifest* (0).
- **P2a-D2: `requires.modules` only for real runtime Maven edges** — la-core→la-graph, la-storage→la-core,
  la-api→la-core+la-storage, la-store-pg→la-core, geo-link→la-api+la-core, entity-list→entity-store,
  agent-hosted→agent. `exchange`→`ops` and `policy`→`ops`/`geo-link` are test scope, so NOT declared. The host
  (`processor`) is implicit and never listed. `provides.contracts` lists the SPIs a module implements
  (its `META-INF/services` files).
- **Gotcha — `geoLink` is provided by `la-api`, not `geo-link`:** `GeoRoutes`/`InvRoutes` (which declare it) live in
  `inspecto-la-api`; `geo-link` only adapts ports. The manifest follows the code.
- **Gotcha — shaded jars collapse same-named resources.** All 34 files share one path, so the processor's shade got
  an `AppendingTransformer` for it; each file therefore starts with a `---` line and the loader splits on that
  line. A **sidecar** shade (`agent`, `connectors`) has no such transformer and is not on the host's class path.
  Verified 2026-10-06: the `-Pedition-enterprise` fat jar's merged resource holds 14 manifests (the processor's dependency closure; optional modules ship as separate jars).
- **Gotcha — TOON:** empty sections are simply omitted from the files; the loader still tolerates `{}` for an empty
  key and `[0]:` arrays. A `#` line is data, not a comment (JToon) — never put one in a manifest.
- **Build-id stamp: SKIPPED.** Only the shaded processor jar carries `Implementation-Version` (HOME-VERSION-1);
  module jars are unstamped, so a per-jar boot check has nothing to compare. It needs a P3 packaging change first.
- Deferred to later P2 steps: directory/artifactId regroup (D-MR2), per-Space Enabled gate, capabilities in
  `provides` (P1-D3), 503 stubs synthesised from manifests, `/bootstrap` reading the three gates.
- `route-gating.md` was already stale from the P1 `featureIds()` line shifts; it was regenerated with this change.

### P3a as built (2026-10-07 — the build-id stamp and its boot check; the jar split is NOT done)
- **Stamp:** manifest attribute `Inspecto-Build-Id` on every module jar, set ONCE in the parent pom: `maven-jar-plugin`
  `pluginManagement` (pinned 3.4.2, the version the default lifecycle already resolved offline) for thin jars; the
  sidecar shades keep the project jar's manifest, and the processor's `ManifestResourceTransformer` repeats the entry
  next to `Implementation-Version` (HOME-VERSION-1). Verified: processor, `inspecto-util` (thin) and the connectors
  sidecar all carry the same value.
- **Decision — the value is the git short sha ONLY, from a property:** `-Dinspecto.build.id=<sha>`, default `dev`.
  No timestamp (a timestamp makes every jar of two builds of one commit differ, and breaks reproducibility) and no
  `buildnumber-maven-plugin` (not in the offline `~/.m2`). `package.ps1` computes the sha (`git rev-parse --short HEAD`,
  `dev` without git) and passes it to all three of its `mvn` runs; CI passes the same property.
  `Inspecto-Module-Id` was **not** added: a jar can hold several manifests and `artifactId` is not the module id.
- **Boot check:** `ModuleManifests.load(loader, hostClass)` reads each `module.toon` URL's jar manifest (`ModuleManifest.buildId`,
  filled by the loader, never declared in the file) and the host jar's stamp (`Loaded.hostBuildId`, the jar holding
  `ModulesRoutes`). `ModuleActivator.resolve(manifests, hostBuildId)` starts a mismatched module INERT, reason
  `build id <x> does not match host <y>`; its dependants go inert through the existing fixpoint. The loader also adds a
  diagnostic per mismatch. The activator stays pure (stamps arrive as data).
- **Never trips on:** an absent stamp, `dev`, an exploded directory (no manifest) or an unknown host — the IDE/test
  class path is the common case. A module merged into the processor's shaded `module.toon` carries the host's own stamp,
  so only a sidecar jar can mismatch.
- **Surface:** `GET /modules` gains `hostBuildId` and per-module `buildId`. `openapi-v1.json` holds a schema-less skeleton for the
  route, so it did not change. The loader now opens each resource uncached (a cached `jar:` stream left the jar locked on Windows).
- **Packaging check:** `package.ps1` fails when the staged `inspecto*.jar` files carry more than one distinct non-`dev`
  build id, or when a fresh build's id differs from the staged one. **Not run end to end** (`package.ps1` was parse-checked only).

### P3c as built (2026-10-07 - the jlink runtime set is derived, not hand-kept)
- **Tool:** `tools/jlink-modules.mjs` = `jdeps --multi-release 27 --ignore-missing-deps --print-module-deps` over EVERY staged jar (first-party + sidecars; a shaded
  sidecar is one jar so it covers its bundled deps) UNION `tools/jlink-runtime-extra.txt` (7 reflection/ServiceLoader needs jdeps cannot see, each with a WHY),
  held against `tools/jlink-modules.lock` (per edition; `--write-lock` refreshes). `--check` needs no jars (lock well-formed; extra.txt names are real
  `java --list-modules` modules) - CI-safe; to wire it add `node tools/jlink-modules.mjs --check` to the guards job in `ci.yml` (NOT done in this step).
  It FAILS when a jar `bundle-modules.mjs` says the edition stages is absent, because `--ignore-missing-deps` would otherwise silently shrink the set.
- **package.ps1** runs it after staging, in WARN-AND-USE-UNION mode: runtime = the old hand list (13) UNION the derived set, delta printed, so this step cannot
  shrink a working runtime. The hand list stays until a few bundles show an empty delta.
- **The real delta (Professional, 22 staged jars):** jdeps found 13 modules, all already in the hand list EXCEPT three it was missing:
  `java.security.jgss` + `java.security.sasl` (kafka-clients / sshj SASL + Kerberos paths) and `jdk.jfr` (a shaded dependency imports `jdk.jfr`).
  Computed set 16; hand-only 0. These three were absent from every earlier runtime, so a Kafka SASL or JFR code path would have thrown NoClassDefFoundError there.
  `jdk.crypto.ec` and `jdk.zipfs` come only from extra.txt (jdeps cannot see them) - the reason that file exists.
- **Proof on the bundle's OWN `runtime/bin/java`** (not the build JVM, per the section 9 trap): `--list-modules` shows the 16 roots plus their transitive requires
  (java.datatransfer, java.logging, java.prefs, java.transaction.xa, java.xml = 21); the bundle booted on it (`ControlApi`, no oidc jar) answered `/health` UP,
  ran the `06-serve/pipeline-job` example (ingest committed Parquet, event-fired job and a manual `POST /jobs/sales_rollup/trigger` both `SUCCESS`),
  and `/api/v1/modules` listed 32 ACTIVE + 8 not-installed (oidc, policy, la-store-pg, intelligence, ... correctly absent). The oidc/JWKS path was exercised only
  by package.ps1's own boot smoke (it loads `inspecto-oidc.jar` on that runtime; no JWKS fetch). Windows runtime only; the Linux image is the same module list.
- **Locks:** `[Professional]` and `[Enterprise]` written (Enterprise, 25 jars incl. intelligence + la-store-pg + policy, derives the SAME 16 modules; its package.ps1 boot smoke passed). Personal and Preview are not yet written - run `--write-lock` on their staged output.

### P3b as built (2026-10-07 — 503 stubs synthesised from manifests; the jar split is still NOT done)
- **`provides.routes`** (`ModuleManifest.Provides.routes`): `"METHOD path"` strings, `path` = the exact regex registered, in registration
  order (first-match: a catch-all stays after the literals it would swallow). Filled for the 8 optional modules that had a hand-written
  stub: `la-api` (69) + `geo-link` (6, `InvestigationMeasureRoutes`), `entity-list` 7, `exchange` 12, `observability` 8 (`/metrics` + the events feed),
  `ops` 49, `reconciliation` 10, `scoring` 2. Quote every entry in the TOON inline array: a `[^/]+`, a space or a `(` needs it (round-trip pinned in `ModuleManifestsTest`).
- **Parity guard per module** (replaces the four hand-kept `Absent*Routes.SURFACE` parity tests and adds three): `ModuleRoutesParity` (http-spi test kit)
  registers the module's own discovered `RouteModule`s on a `FakeApiContext` and asserts registered == `provides.routes` in BOTH directions. `exchange` is the
  exception until P5c: its `register()` needed host services, so its test booted `ControlApi`; since P5c it uses the fake context like the others.
- **`absentMessage`** (optional manifest key): the 503 text for an absent module. Kept the old per-module texts so clients and the `No*Ships*` tests (which
  assert the module name appears) are unaffected; `la-api` and `geo-link` both carry the geo-link text (a Personal client is told what to install); the metrics and events texts
  merged into one for `observability`. Default when absent: `<title> is not installed in this bundle - provided by the optional <id> module.`
- **known-modules:** `tools/KnownModules.java` (run by `exec-maven-plugin` 3.6.4 at `process-resources` in `inspecto/pom.xml`, `${java.home}` java; Maven because every
  build must have it, a Java source launcher because the offline repo has no antrun/assembly copy-with-rename) copies EVERY `module.toon` of the source tree to
  `META-INF/inspecto/known-modules/<id>.toon` plus `index.txt` (40 manifests; shaded jar verified). `KnownModules.load` (inspecto-util) reads them.
  `AbsentModuleRoutesTest` asserts the copy equals the source tree's manifests id-for-id and record-for-record.
- **`AbsentModuleRoutes`** (processor) replaces the 8 classes: for each known manifest in index order it stubs every route `hasRoute` says unclaimed. Deliberately NOT
  filtered by "module not active": `hasRoute` is the exact old semantics, so a module on the class path that failed to link still answers 503, and nothing changes for an installed one.
  Stub order across modules is now alphabetical by module id (was a hand-picked list); `AbsentModuleRoutesTest.noStubShadowsALaterStub` proves no stub shadows a later one.
  `openapi-v1.json` is byte-identical (`ApiContractTest`, `OpenApiPathsContractTest`).
- **`GET /modules`:** known-but-absent modules are listed `state: "not-installed"` with `reason` (and `reasons`) `module <id> is not on the class path`, `enabledInSpace: false`;
  `ModuleActivator.resolve(..., knownAbsent)` makes a dependant go INERT with `requires module 'x', which is not on the class path`. The SPA's Modules screen filters the new state out.
- **Registry ratchet:** Absent stubs 8 -> 0, `populatedRegistries` 8 -> 7 (`tools/module-architecture-baseline.json`).

### P2b as built (2026-10-06 — the per-Space Enabled gate, D-MR10)
- **Document:** `modules.toon` = `disabled: [feature ids]` in the Space config root (absent = all enabled, so a newly
  installed module defaults to enabled). `ModuleSettings` (read cache keyed by mtime+size, atomic write) holds the TOON
  I/O; `ModuleSettingsRoutes` = `GET|PUT /settings/modules` (sibling of `/settings/egress`; `canAdminister`, audit
  action `module-settings.changed` as an `AUDIT` event, no new EventType). Reserved from import (`ReservedConfigPaths`).
- **Validation:** an id must be in `registeredFeatures()` (an installed module's `featureIds()`), otherwise 422 — that
  also covers core feature names such as `authoring`. Stored ids that no installed module declares are **inert**: listed
  in `inert`, disable nothing, and are re-added by every save, as are unmodelled keys of the file (tested).
- **Enforcement:** `ControlApi` stamps each route a discovered module registered with that module's feature ids (the new
  `Route.features`, taken from the route-table slice around `register()`); `requireModuleEnabled` runs after
  authenticate + authorize (a refused caller learns nothing) and before idempotency/handler, in `routeDispatch` and in
  `replay`. Answer: **404 `MODULE_DISABLED`** (new `ErrorCodes` constant) vs the 503 `CAPABILITY_UNAVAILABLE` of a module
  that is not installed. Core routes and absent-module stubs own no feature, so `/bootstrap`, `/modules` and
  `/settings/modules` can never be disabled. `ApiContext.disabledFeatures()` (default empty) exposes the current Space's set.
- **Reads:** `/bootstrap` `features{}` = registered AND not disabled (the five legacy keys, plus every other installed
  feature id under its own key); `GET /modules` gains `enabledInSpace` (per request; the manifest load stays cached).
- **Deviation (decided here):** a `modules.toon` that cannot be parsed reads as *nothing disabled* with a warning and
  `unreadable: true` on the GET, and a PUT over it is refused 422 (a save would drop what it holds). Not fail-closed
  like `approvers.toon`: this gate narrows the product surface only; the Permitted gate (capabilities) is untouched.
- **Gotchas:** `ConfigWriteFunnelTest` scans a route file for `JToon.encode`, so the TOON write lives in
  `ModuleSettings`, not the route class (the settings-record pattern; no `NO_SAVE_GATE` row needed). The module
  test class path carries no optional module, so the tests switch off `TestDiscoveredRoutes` (feature `testDiscovered`).
  `OpenApiPathsContractTest -Dopenapi.paths.write=true` also reformats three unrelated hand-edited lines; they were
  restored. The error-code enum in `openapi-v1.json` is hand-kept: `MODULE_DISABLED` added.
- **Closed 2026-10-07:** `ImportLoaderInventoryTest.everyFixedNameALoaderReadsIsReservedOrAllowedWithAReason` was RED since P2a
  (`META-INF/inspecto/module.toon` read by `ModuleManifests.java`): the `ALLOWED` row (a classpath
  resource an import cannot plant) was added with operator approval.
- **Done 2026-10-07:** the settings screen (SPA) for `/settings/modules` — Settings ▸ Modules (see `docs/okf/frontend/features/spaces.md`).
- **Deferred:** the SPA still reads `/bootstrap` only for gating. A disabled
  module's background Jobs and stores are NOT stopped (the gate is on the HTTP surface; P4 owns removal semantics).

### P5a as built (2026-10-06 — platform test kit + RouteModule TCK; the processor dependency is NOT yet dropped)
- **Kit home:** the **test-jar of `inspecto-http-spi`** (`src/test/java/com/gamma/control/testkit`), not a new module (§1 #4).
  Consumers declare `inspecto-http-spi` `type test-jar`, scope test (managed in the root pom). `FakeApiContext` is the one
  in-repo `ApiContext` double (`ControlApi` is the only other implementation): it records `(method, pattern, withCapability
  capability, stub)` per route, answers `hasRoute` (stubs excluded, like the host), `registeredFeatures`/`disabledFeatures`
  defaults, `writeRoot`/`dataRoot`, `body`, and `dispatch(method, concretePath, body[, Subject])` runs a handler in-memory — with a
  `Subject`, `withCapability` enforces exactly as the host does. `replay` throws (needs the real dispatcher).
  `PortHarness` (la-api) was migrated onto it (it is now ~30 lines of delegation; `LaApiPortsTest` unchanged, 6/6).
- **TCK:** abstract `RouteModuleContract` (`module()` + optional `exemptMutatingRoutes()` = key `METHOD pattern` to reason). Five
  tests: registers at least one route; no duplicate `(method, pattern)`; every POST/PUT/PATCH/DELETE is behind `withCapability` or
  exempt (a stale exemption fails too, so the list cannot rot); `featureIds()` within the module's OWN `module.toon`
  `provides.features` (located by loading only the class's code-source through an isolated class loader, so the other modules'
  manifests on the class path are invisible); two fresh registrations yield the same routes. Exemption reasons mirror
  `CapabilityManifest.EXEMPTIONS`.
- **Driven without booting the processor (8 classes, 5 modules, 40 tests):** `EntityListRoutes`, `EventRoutes`, `MetricsRoutes`,
  `ObjectRoutes`, `NoteRoutes`, `TagRoutes` (ops), `GeoRoutes`, `InvRoutes` (la-api). Note: these modules still have
  `inspecto-processor` on their compile class path; what changed is that a route test no longer needs `ControlApi`/a Space.
- **Cannot yet be driven — `ExchangeRoutes`:** `register()` itself calls `HostContext.of(api).spaces()` (to install
  `SharedRefResolver`, the signal forwarder and two fence hooks), so any `ApiContext` that is not the host's `HostContext` throws
  `IllegalState`. Blocker for dropping its processor dependency: those installs belong in a boot hook that receives the Space
  registry, not in `register()`. Not mocked on purpose. Not attempted: the remaining la-api route classes (many take host-free
  ports and could subclass the TCK next), `inspecto-geo-link` and `inspecto-policy`.
- **Still open for P5:** the TCKs of §5 other than RouteModule (done in P5b below); the "drop the processor dependency from the 7 modules" step.

### P5b as built (2026-10-07 — the other SPI TCKs; tests + test-jar plumbing only, one production fix)
- **Homes (the SPI's own test-jar, consumers declare `type test-jar`, scope test, managed in the root pom):** `inspecto-acquire`
  (`com.gamma.acquire.testkit`: `CollectorConnectorFactoryContract`, `ExportConnectorFactoryContract`), `inspecto-auth-spi`
  (`com.gamma.control.testkit`: `AuthenticatorContract`, `TokenRelayContract`), `inspecto-engine` (`com.gamma.notify.testkit`,
  `com.gamma.job.testkit`, `com.gamma.catalog.spi.testkit`, `com.gamma.parse.testkit`: `NotificationChannelContract`,
  `JobTypeProviderContract`, `MaintenanceTaskProviderContract`, `DescriptionProviderContract`, `ParserPluginContract`). Each
  contract's Javadoc states, per test, the defect it catches. `registered()` is a seam over `ServiceLoader` so a self-test can
  plant a duplicate.
- **Each assertion is proven able to fail:** `ConnectorFactoryTckSelfTest` (11), `AuthTckSelfTest` (13), `JobTckSelfTest` (13),
  `NotificationChannelTckSelfTest` (7), `DescriptionProviderTckSelfTest` (6), `ParserPluginTckSelfTest` (9) run every contract
  against a deliberately broken in-test implementer (and a sound one) and expect `AssertionFailedError`. Contract methods are
  package-private, so a self-test lives in the contract's package.
- **Driven (concrete subclasses, 38 classes over 15 modules):** collect factories `sftp ftp ftps db s3 azure gcs` (connectors),
  `kafka`, `dataset` (engine), export `s3`; `OidcAuthenticator`/`OidcTokenRelay` (in-memory JWKS, a stand-in IAM that refuses every
  grant) and `DemoAuthenticator`/`DemoTokenRelay`; `WebhookChannel`, `SmtpEmailChannel` (unconfigured instances only, no message leaves);
  Job Types `mail.send sql.template object.store.export postgres.publish consignment.process` (engine), `CaseRuleEvaluate`,
  `ObjectsAnalytics` (ops), `ReconRunJobType`, `RiskScoreJobType`; maintenance `OpsMaintenanceTasks`, `BackupTaskProvider`,
  `AuditAnchorExportProvider`; describers `NoopDescriptionProvider`, `AiDescriptionProvider` (over `FakeModelProvider`); parsers
  `XmlParserPlugin`, `Asn1ParserPlugin`.
- **Bug found and fixed (production, minimal):** `OpsMaintenanceTasks.run` ignored its `task` argument and ran `incident_purge` for ANY name,
  so a mistyped `task:` executed a purge (dry-run or not). It now throws `IllegalArgumentException` like `BackupTaskProvider`'s switch default.
  No other implementer failed a contract, including the 100 000-level nested XML / BER samples (no stack overflow).
- **Not driven, with the blocker (this is the finding):**
  - **`create(...)` of every `CollectorConnectorFactory`, `workbench`, and egress/SSRF behaviour** - needs a `PipelineConfig` and a real or faked endpoint; the
    contract covers `scheme()` and `validate()` only. The two test-fixture factories (`FakeRemoteConnectorFactory`, `FakeOffsetTailConnectorFactory`, processor test tree) are not subclassed.
  - **`JobTypeProvider.create(config)` and running a Job**, and the existence check that each `requires:` id names a Platform Service this host registers: those ids are
    registered only in `CollectorService` (processor), so `JobTypeProviderContract.knownPlatformServices()` defaults to empty; a hand-kept mirror would drift. Uniqueness of
    ids across ALL providers is checked only against the module's own `ServiceLoader` view - no module's test class path holds every provider; `JobTypeRegistry`'s
    duplicate guard remains the runtime backstop. The engine built-ins registered with `JobTypeProvider.of(..)` inside `JobService.registerBuiltins` (and `AlertEvaluateJob`,
    `IncidentOpenJob` etc.) need a `JobService` and are not subclassed; `tools/templates/job`'s provider is a template, not buildable here.
  - **Maintenance tasks' real work:** every task runs in `dryRun` against a bare context (no `JobService`/`JobContext`), so for `BackupTaskProvider` and
    `AuditAnchorExportProvider` the "never returns null" check is vacuous (both refuse by exception without their host); dispatch rules are what is proven.
  - **Positive paths** of `Authenticator`/`TokenRelay` (a valid credential yields a Subject/Tokens), a delivered notification, an AI description from a live model: implementation-specific
    and kept in each module's own tests. `NotificationChannel` has two implementers only (the in-app store is not a channel).
  - `DescriptionProviderContract.concurrentUseAgreesWithSerialUse` assumes a deterministic provider; a live model is out of scope.
- **Still open for P5:** the "drop the processor dependency from the 7 modules" step; Exchange's boot hook (see P5a). **Both done in P5c below.**

### P5c as built (2026-10-07 — Exchange boot hook, 17 more route classes on the TCK, 7 processor edges dropped/demoted)
- **Exchange host-install design.** `ExchangeRoutes.register()` now only registers routes, so it runs on any `ApiContext`. The four installs it used to make
  (`SharedRefResolver`, `ExchangeSignalForwarder` tap, `SignalOfferGrants`, `SharedItemConsumers`) moved to `ExchangeBootHook implements HostBootHook`
  (`META-INF/services/com.gamma.control.HostBootHook`, discovered fail-soft with `OptionalSpi.all`, called once by the `ControlApi` constructor right after
  the discovered route modules register, before the absent-module stubs). **Deviation from the design note:** the interface lives in the processor beside
  `HostContext` (`com.gamma.control.HostBootHook`, `afterRoutes(SpaceManager)`), not in `inspecto-http-spi`: its one argument is a host type the SPI cannot name,
  and the narrowest honest context is the Space registry (every install needed only that). It is the 37th SPI extension point (`spi-extension-points` 36 to 37, four doc lines).
  The hook is not gated by per-Space `featureIds` (the old installs were not either).
- **Proofs.** `ExchangeBootHookTest` (real `ControlApi` boot, seams reset to `NONE` and the forwarder tap removed first): all four are installed at boot.
  A host WITHOUT the module is pinned by the existing `NoExchangeShipsInThePersonalBuildTest` (every seam `NONE`, zero taps). `ExchangeRoutesContractTest` (5, nothing exempt:
  every Exchange write is gated) and `ExchangeRoutesManifestParityTest` (now `ModuleRoutesParity.assertParity`, no boot). `ControlApiExchange*` (real behaviour) unchanged and green.
- **Route classes converted (`*RoutesContractTest` over `RouteModuleContract`, 5 tests each):** la-api `Pattern`, `ValueMeasure`, `Investigation`, `Dossier`, `DossierBundle`,
  `InvestigationReference`, `WorkingSet`, `InvestigationTemplate`, `InvestigationCoverage`, `InvestigationComparison`, `InvestigationCase`, `EntityIdentity`, `GraphRun`, `Index`,
  `InvestigationMember`, `Draft` (with `Geo`/`Inv` from P5a that is all 18 la-api RouteModules), and geo-link `InvestigationMeasureRoutes`. Exemptions (6 classes) copy
  the `CapabilityManifest.EXEMPTIONS` reasons. All 17 needed only the `ApiContext` at `register()` (request-time host needs are fine). **No class remains blocked.**
  geo-link's parity test already existed (`GeoLinkAbsentSurfaceParityTest`).
- **Edges (`inspecto-processor`).** Dropped outright (no processor class imported in main OR test), real deps declared in its place with the same `provided` scope:
  `inspecto-connectors`, `inspecto-connectors-kafka` (acquire, engine), `inspecto-agent-hosted`, `inspecto-notify-channels` (engine). Demoted to `test` scope (main imports none, tests boot it):
  `inspecto-backup` (+ acquire, engine), `inspecto-demo-auth` (+ auth-spi, access, util, provided), `inspecto-policy` (+ http-spi, access, etl, event, util). `inspecto-geo-country` and
  `inspecto-oidc` were already test scope. The "7" was really 9 main-clean modules; none of the five that stay have the guard's `ALLOWED` rows (no new allowed edge needed).
  Every other module still imports processor classes (`HostContext`, `ControlApi`, `PendingChanges`, `ServerFaults`, `Cursor`, ...) and keeps the edge.
- **Gate:** full reactor 9023 tests, 50/50 modules, one transient Windows `AccessDeniedException` in `ControlApiRestoreJobGateTest` (green 3/3 alone).

### P4d as built (2026-10-07 - the last writers: Tag, Tag Rule, Case Rule, Saved View; Value / Investigation Measure characterised; `MODULE-REORG-P4-2` closed)

Same policy as *P4b* / *P4c* (`x-` kept, any other key refused 422 `ERR_UNKNOWN_CONFIG_KEY` naming it, a re-save REPLACES with what was posted). The survey below is the
COMPLETE list of writers that persist what they rebuild from a typed record or a field-by-field read: a grep for `toMap()` passed to a write / save / persist call over every
module, plus a read of each `*Routes` that stores a config (not the earlier survey's file list).

| Writer (door) | Before | After |
|---|---|---|
| Tag (`POST /tags`, `TagRoutes.ensureTag` adoption, `POST /tags/{name}/rename`) | `x-` and any other key **dropped behind a 200**; a rename wrote the destination file with only `name` + `createdAt` | `x-` kept (create, GET, the `*_tag.toon`, a re-load, **rename carries it to the new file**), other keys refused. `Tag` gains `extra` (3rd component; the 2-arg constructor stays) |
| Tag Rule (`POST /tags/rules`) | dropped behind a 200 (also a typo inside `filter`) | `x-` kept, other keys refused; the nested `filter` may hold only its six criteria (`TagRule.Filter.KEYS`; an `x-` there is refused, it has nowhere to live); a Tag **rename rewrites the rule file and keeps the annotation** (`ObjectService.renameTag` carries `extra`). Flattened-filter authoring sugar (`type`, `q`, ... at top level) is modelled, not unknown |
| Case Rule (`POST /cases/rules`) | dropped behind a 200 | same as Tag Rule (`CaseRule.MODELLED`, `extra`) |
| Saved View (`POST /events/views`, bundle `saved-view` import) | body read field-by-field: every other key **dropped**, and a nested `filters` object (what GET returns) saved the view with **no filter at all**, both behind a 200 | `x-` kept (response, `GET /events/views`, the JSON store on reload, bundle import), other keys refused - including `filters` and `createdAt` on POST (flat keys only; the server stamps `createdAt`). A bundle item with an unknown key fails that item naming it. `SavedView` gains `extra` |
| Investigation Measure (`POST|PUT /inv/investigations/{id}/alert-rules*`, writes an Alert Rule) | already **refused** any key outside its closed `RULE_FIELDS` / `VALUE_RULE_FIELDS` (422 naming it, nothing armed), `x-` included by design (the Investigation owns the rule) | unchanged; **pinned for the first time** by `ControlApiInvestigationAlertRuleTest.anUnmodelledKeyIsRefusedNamingItAndNothingIsArmed` |
| Value Measure (`GET /inv/value-measures`; the `valueMeasure` block of a bound rule) | a GET persists nothing; the block is parsed by `ValueMeasures.parse`, which **refuses** any setting outside the measure's own list, naming it | unchanged; **pinned** by `ControlApiValueMeasureTest.anUnmodelledKeyIsRefusedNamingIt` (block and rule body, `zz_cfg` and `x-team`) |
| Job (`POST|PUT /jobs*`, bundle import) | every unrecognised key lands in `JobConfig.params` (string-valued) and round-trips: **preserved** (P4a) | unchanged |
| Pending alert-rule seed, Decision Rule `create-alert` | go through `AlertRoutes.parse` / `AlertRule.extra` | unchanged (P4b) |
| `ViewStore` (`sink.view` definitions), `ReconStateStore`, operational objects' attributes | written by the engine from run state, not authored config | out of scope (not a door an author posts a document to) |

- **Why the module edge did not change.** `AuthorKeys` (processor `control`) was package-private; it is now `public` with the same four static methods, and `inspecto-ops`
  and `inspecto-observability` already depend on the processor (they import `ApiException`, `ErrorCodes`, `RouteModule`), so no new edge: `check-module-deps` and `check-module-architecture
  --ratchet` stay green. `Extras.of` (ops `tag` package, 8 lines) only collects a block's unmodelled keys into the record; `AuthorKeys` still owns the policy (what is refused).
- **Records.** `Tag`, `TagRule`, `CaseRule`, `SavedView` gain a trailing `extra` map exactly as `AlertRule` did (`toMap` emits it with `putIfAbsent`, so it cannot override a modelled key); the old-arity
  constructors stay for the engine's own call sites. `fromMap` / the store's loader fill `extra` with EVERY unmodelled key (a hand-edited file still loads and round-trips, as for Alert Rule); only the route refuses.
- **Doors not carried.** Tag / Tag Rule / Case Rule files are refused by bundle import by design (`ImportPaths.REFUSED_SUFFIXES`), and none is held for approval (no maker-checker policy covers them), so create/update and the
  boot re-load are the whole surface.
- **Shipped configs.** `spaces/demo/config/ops/{hot_tag,critical_incidents_tagrule,incident_burst_caserule}.toon` carry only modelled keys (`name`; `name tag filter`; `name title threshold windowMinutes category tags filter`).
  No other Space, template, example or pack ships a Tag, Tag Rule, Case Rule or Saved View. Nothing needed a non-`x-` carry-forward.
- **SPA: no change.** The Tag Rules and Case Rules dialogs (`tag-rules.dialog.ts`, `case-rules.dialog.ts`) are create-only forms: they list stored rules but never load one into the form to edit, so there is no stored document
  whose `x-` keys an edit-save could drop (re-saving a name replaces, as designed). No SPA surface posts a Saved View. So `authorKeys(stored)` has no new call site.
- **Tests.** `OpsWritersUnmodelledKeysTest` (3, real HTTP: Tag / Tag Rule / Case Rule - refusal naming the key with nothing written, `x-` in the response, GET, the file and a re-load, replace-on-resave, rename carrying the
  annotation to the new Tag file and the rewritten rule file) and `SavedViewUnmodelledKeysTest` (2: create/list/replace/refusals incl. nested `filters`; bundle import keeping `x-` and failing the item for `zz_cfg`). RED first against the
  unmodified code: ops 3/3 failed, each as a 200 for the `zz_cfg` probe (the `x-` assertions sit behind it); Saved View: the create test failed the same way, the bundle test could not run red (the bundle door
  is write-root gated; fixed in the test, run only green). The two Measure characterisations passed against the unchanged code, as they should.
- **Not verified.** A Saved View file written by an older build loads (extras empty, by construction, not tested). The Tag Rule `x-` inside `filter` refusal is stricter than Expectation's
  (`x-` allowed top-level only) - deliberate, a filter has no home for an annotation.

### P4c as built (2026-10-07 - the unmodelled-key policy on the writers after Alert Rule)

Policy as in *P4b* Step A (`x-` kept, any other key refused 422 naming it), applied by `AuthorKeys` (inspecto `control`: `carry` copies the posted `x-` keys into the
rebuilt map, `requireModelled` refuses the rest) to the writers that persist what they REBUILD from a typed `toMap()`. Surveyed by reading each route, not by grep alone.

| Writer (door) | Before | After |
|---|---|---|
| Alert Rule (`/alerts/rules`, `/components/alert-rule`, import) | dropped behind a 200 | **kept / refused** (P4b, `AlertRule.extra`) |
| Decision Rule, Pipeline, Job (P4a) | preserved | unchanged |
| KPI (`/components/kpi`, Requirement action, import) | **already right**: persists the raw body after `KpiDefinition.fromMap`, which refuses any key outside `KEYS` unless `x-` (`KpiDefinitionTest` pins both) | unchanged; the row's premise (a `toMap()` rebuild) was wrong for KPI |
| Expectation (`POST|PUT /expectations`) | `x-` **dropped behind a 200** (route rebuilt from `Expectation.toMap()`); any other key already refused by the DUCKLE-C3 census (422 naming the key, no `ERR_UNKNOWN_CONFIG_KEY` code text - left as is, its tests pin the wording) | `x-` kept through create / update / held / import (`/components/expectation` and bundle import already persisted the raw body and run the same census) |
| Notification Rule (`/notifications/rules*`) | `x-` **and every other key dropped behind a 200** | `x-` kept, other keys refused `ERR_UNKNOWN_CONFIG_KEY`; `NotificationRule.MODELLED` also accepts the store's `name` stamp (a GET-then-PUT replays it) |
| Notification Channel (`/notifications/channels*`) | same as Rule | same as Rule (`ChannelConfig.MODELLED`); its PUT is a server-side MERGE, so a stored `x-` key also survives a PUT that omits it (unlike Rule/Expectation, where PUT replaces) |
| `/components/notification-rule` and `/components/channel` raw doors, bundle import of both | persisted any key raw (nothing dropped, nothing refused) | refuse a non-`x-` unmodelled key in `ComponentRoutes.validateKind` (keys only, no required-field check: a raw door never ran one) |
| Tag, Tag Rule (`TagRoutes.persist(Map.of("tag", tag.toMap()))`), Case Rule | rebuild from `toMap()`, no unknown-key handling: **DROP** | **kept / refused** - see *P4d as built* |
| Value Measure / Investigation Measure specs, Saved View (`EventRoutes`, `BundleRoutes`) | rebuilt from a `toMap()`; **not characterised** | characterised in *P4d as built*: the two Measures were never droppers; Saved View **kept / refused** |
| Connection, Approval Policy | read-only / refuses unknown keys by its own parser | unchanged |

- **Shipped configs.** Every shipped Expectation (`spaces/*/config/registry/expectations`, 6 files, `spaces/_templates/*`) carries only modelled keys (`name targetType target column kind min severity`);
  no Notification Rule or Channel is shipped anywhere (`spaces/`, templates, demo). So nothing needed a non-`x-` carry-forward.
- **Tests.** `RebuiltWritersUnmodelledKeysTest` (6, real HTTP with a Subject): per writer, create / GET / GET-then-PUT / PUT-replaces, a held save approved by a second approver, a bundle import keeping `x-`
  and failing the item for an unknown key. RED first against the old code: 6/6 failed, each as a 200 with the key gone (or a 200 for `zz_cfg`). Guards run green: `ApiContractTest`, `OpenApiPathsContractTest`
  (no route or schema changed), `ModuleRemoval*`, `ConfigWriteFunnelTest`, `ImportLoaderInventoryTest`, `RouteInventoryTest`, `CapabilityManifestTest`, `ModuleManifestGuardTest`.
- **SPA (the section 9 trap, fixed small).** The Rule dialog PUT replaces from a rebuilt body and the Expectation dialog likewise, so an edit-save would have dropped the `x-` key the server now keeps.
  `authorKeys(stored)` (`inspecto/api/author-keys.ts`) spreads the stored document's `x-` keys into both save bodies; specs in `author-keys.spec.ts`, `rule-form.dialog.spec.ts`,
  `expectation-form.dialog.spec.ts`. The Channel dialog needs nothing (merge PUT).
- **Remaining:** none - the rest were done in *P4d as built* (row closed).

### P4b as built (2026-10-07 — Alert Rule unmodelled-key policy, then inert-config diagnostics on the wire)

**Step A - `MODULE-REORG-P4-2` (policy decided by the operator-away rule; recorded, not asked).**
- **Policy.** An author-owned `x-` key is an annotation and is KEPT by every door that stores a rule and returned by `GET /alerts/rules`; any other
  unmodelled key is REFUSED 422 `ERR_UNKNOWN_CONFIG_KEY`, naming the key, nothing written. Why: the Pipeline precedent (refuse, `x-` allowed) is the least
  surprising for a typo (`threshhold` must not look applied), and the Decision Rule precedent (keep everything) is reserved for rules whose consequence
  config belongs to modules. The grep that decided it: all 24 shipped Alert Rule TOONs (`spaces/_templates/*`, `spaces/demo`) carry only modelled keys; the
  one non-modelled key in any (`afterRiskScore`, `pending/alert-rules/`) is the deferred-seed marker `PendingAlertRules.ruleBody` strips before the rule parses.
  So no shipped config needed carry-forward of a non-`x-` key.
- **Mechanism.** `AlertRule` gains an `extra` map (the 20th record component; the 19-argument constructor stays). `fromMap` fills it with every key outside
  `AlertRule.MODELLED` (the modelled keys plus the R3 `shares` envelope key, which the save routes carry themselves); `toMap` emits it after the modelled keys with
  `putIfAbsent`, so an extra can never override a modelled key. `fromMap` stays lenient (a hand-edited stored rule still arms); the refusal is in
  `AlertRoutes.parse`, which every write door already runs (`POST|PUT /alerts/rules`, `/components/alert-rule`, a bundle import item, a Decision Rule's
  `create-alert`, a Space Template seed, a deferred Risk Score rule). PUT is replace-with-posted: what the client sent is stored, so a key it stops sending is gone.
- **Held saves and import.** `PendingChanges.hold` is given the `persisted(...)` map, which now carries the extras, so the approved write keeps them (pinned);
  a bundle import writes the item's content as-is after `parse`, so an `x-` key rides and an unknown key fails that ITEM naming it (pinned).
- **Tests.** `AlertRuleUnmodelledKeysTest` (4, real HTTP with a Subject: refusal by name, create/update/GET/PUT-replace, held-then-approved, bundle import),
  `AlertRuleTest.unmodelledKeysRideThroughFromMapAndToMap_neverOverridingAModelledOne`, and `ModuleRemovalRulesTest`'s former "dropped, pinned until P4 policy"
  test flipped to "refused loudly". The pin was the RED characterisation: it passed against the old code (200, key gone), the `x-` cases failed on it.
- **Not done (recorded on the row).** The same sweep for the other `toMap()`-rebuilding writers (Expectation, Notification Rule, KPI) is not started; each
  needs its own look at what its door stores.

**Step B - `MODULE-REORG-P4-1` (the smallest additive wire change).**
- **`GET /jobs` list.** `JobView` gains `hosted` (boolean) and `reason` (text, null when hosted). A Job whose type nothing registers (an optional module or Job Pack
  that is not installed) now lists `hosted:false`, a `reason` naming the type, and an EMPTY `nextFire` (it used to show a live one for a job that can never fire).
  `hosted` is `JobTypeRegistry.has(type)`, the same test the boot skip and the 503 trigger use, so the three surfaces cannot disagree. Pinned by
  `ModuleRemovalJobTest.anAbsentTypeJobIsListedAsNotHostedWithAReasonAndNoNextFire` and `JobServiceTest` (hosted case). No route added; `ApiContractTest` and
  `OpenApiPathsContractTest` untouched (the list is a schema-less skeleton).
- **Deliberately generic reason, no `missingModule` field.** The manifests do not declare job types (`provides` has features, contracts, capabilities, config kinds,
  store families, routes, consequences). Adding `provides.jobTypes` means a new `Provides` component plus a declaration in every module that contributes a Job Type
  and a parity test each - not cheap, and a Job Pack type has no module at all. So the reason says "no installed module or Job Pack provides it".
- **SPA.** The Jobs list name cell shows a "Not installed" status badge (text, not colour alone) and the reason as screen-reader text plus a tooltip; an older server
  that sends no `hosted` shows nothing. `jobs.component.spec.ts` pins the badge, the reason text and the no-badge cases.
- **Not done (remaining on the row).** `GET /jobs/{name}` is the raw stored config (its ETag and the PUT round trip hash it), so a `hosted` flag does not belong
  there; a Pipeline refused for a plain unknown key still does not name a module (there is no module to name for a typo); and the `GET /modules` section of
  inert references (config files naming a module that is not installed, fed by the manifests) is not built.

### P4a as built (2026-10-06 — removal semantics: characterised first, then only what was broken and small)
Scenario: config naming a capability no installed module provides (stand-ins `zz.absent-module-node`, `zz.absent-module-job`, `zz-absent-action`). Tests: `ModuleRemovalPipelineTest`, `ModuleRemovalJobTest`, `ModuleRemovalRulesTest`, `ModuleRemovalBundleTest` (inspecto) and `ModuleRemovalBackupTest` (inspecto-backup).

| Case | Verdict | Fixed here |
|---|---|---|
| Pipeline, flat config with a `steps:` kind no module registers | **INERT** — loads as a listed load failure (`loadError`), never run; `GET /graph/raw` is a loud 422; no read rewrites the file | the load error now says a removed module's file is left untouched and loads again once installed |
| Pipeline `PUT /graph` with an unregistered node type | **REFUSED-LOUDLY** — 422 `UNSUPPORTED_NODE`, nothing written; dry run is 200 with a `NOT previewed` warning naming the type | the refusal now names the missing module (only for a type nothing registers; a registered-but-not-authorable type keeps its old wording) |
| Pipeline plain unknown key (`zz_module`) | **REFUSED-LOUDLY** at save (`ERR_UNKNOWN_CONFIG_KEY`), file unchanged; does not name a module | filed `MODULE-REORG-P4-1` |
| Pipeline author-owned `x-` key | **PRESERVED** through GET `/graph/raw` → PUT `/graph` | — (pinned) |
| Job of an unregistered type: list, detail | **PRESERVED** — listed with every key (but with a live `nextFire`) | wire flag shipped in *P4b as built* below |
| Job trigger | was a misleading **404** "no job named" for a listed job | now **503 `CAPABILITY_UNAVAILABLE`** naming the type and the missing module |
| Job enabled save (`PUT`, `reschedule`, `enable`, `POST` of a typo'd type) | was **a 500 after the file had been rewritten AND the job dropped from memory** (`persistJob` wrote, then `upsertJob` threw) — DROPPED | now **422 before any write**; file byte-identical, job stays listed. A *disable* stays allowed and keeps every key |
| Decision Rule with an unknown consequence action | **PRESERVED** through create / update / simulate (top-level and consequence keys); `apply` reports `skipped` | the `skipped` detail now names the missing module |
| Alert Rule with an unmodelled key | **DROPPED silently on a 200** (`persisted()` rebuilds from `AlertRule.toMap()`) — pinned by `…IsDroppedBySaveWithA200_pinnedUntilP4Policy` | policy decided and shipped in *P4b as built* below (`x-` kept, other keys refused) |
| Space bundle export (whole Space) | **PRESERVED** — a directory walk, no roster; carries `modules.toon` and unknown registry kinds byte-for-byte | — |
| Bundle clone import (`refuseReserved=false`, new-Space seed) | **PRESERVED** verbatim | — |
| Bundle import into an existing Space | Job of an absent type **PRESERVED**; `modules.toon` **REFUSED by design** (a Space settings document, as `branding.toon`); a registry entry of a kind no module registers is **REFUSED-LOUDLY and all-or-nothing** (before the first byte) | roster derived from installed modules + module-naming message: `MODULE-REORG-P4-3` |
| `modules.toon` stored unknown id | **PRESERVED** (P2b, `ModuleSettingsRoutesTest`) | — |
| Backup / restore (`inspecto-backup`) | **PRESERVED** — `BackupTask` walks the directory (filter: secrets only) and restore writes entries verbatim, so no family roster exists to go stale | — |

- **The bug worth the phase:** the Job save order. `persistJob` wrote the TOON, then `JobService.upsertJob` did `removeJobInternal` and `build`, which throws for an unregistered type. Any authoring verb on an enabled ghost Job therefore rewrote its file (cron re-quoted) and unhosted it until reboot. The docs claimed "`upsertJob` still refuses an unknown type, so a typo is a 4xx" — it was a 500 with a side effect. The gate is one check at the top of `persistJob` (`c.enabled() && jobTypeView(type).isEmpty()`), because `buildDisabled` is lenient by design.
- **Not changed, deliberately:** the engine's boot-time skip of unregistered Job types (documented, `DEMO-SPACE-PERSONAL-UNBOOTABLE-1`); `ImportPaths`' shape allowlist; the Pipeline unknown-key gate. No route was added (`ApiContractTest` untouched); no guard inventory was edited.
- **Open (rows filed):** `MODULE-REORG-P4-1` inert-config diagnostics on the wire; `MODULE-REORG-P4-2` Alert Rule unmodelled-key policy; `MODULE-REORG-P4-3` store-family ownership, the bundle kind roster and stopping a disabled module's background work.

### P7 Incidents slice 1 as built (2026-10-07 — the Alert records port; no persistence change, no behaviour change)

`AlertService` no longer holds `ObjectAccess`. It holds a narrow port, **`AlertRecords`** (`com.gamma.alert`), plus
`IncidentAccess` (promotion stays there, the Platform Service a granted Run also uses). Name: not "store" (GLOSSARY:
the physical backend) and not "ledger" (Signal / batch ledgers); `Records` collides with nothing.

- **Port methods (7):** `hasActiveAlert(scope, rule)` · `activeAlertIndex(scope, attribute)` ·
  `activeIncidentIndex(scope, attribute)` · `openAlert(title, message, severity, scope, attrs) -> id` ·
  `resolveAlert(id, actor)` · `reopenIncident(id, actor)` · `linkEscalation(incidentId, alertId, actor)`.
  Only what `AlertService` calls; the other ~8 `ObjectAccess` methods (tags, summary, findByStatus, eventSubscriber...)
  stay on `ObjectAccess` for their other consumers.
- **Two implementations:** `ObjectBackedAlertRecords` (adapter over `ObjectAccess`, `ESCALATED_FROM` literal moved in) and
  `NoAlertRecords` (events-only null object: reads empty/false, writes no-op, paired `NO_INCIDENTS` opens nothing).
  `AlertRecords.of(Optional<ObjectAccess>)` / `incidentsOf(...)` select them; `CollectorService` calls both
  (ops present -> object-backed, Personal -> events-only). The old `AlertService(..., ObjectAccess)` constructors stay
  as a delegating convenience (most tests build that way). There is deliberately **no in-memory *recording* store**:
  that would be a behaviour change (Personal would start remembering Alerts); see the product call below.
- **Null guards removed: 8** `objects == null` early-returns (`retireKeys` x2, `measureOpen`, `healMeasure`,
  `seedOpenKeys`, `persistKeyObjects`, `healKey`, `persistAlertObject`) **+ 1** `objects == null ? null : IncidentAccess.over`
  ternary (the survey's "~12" over-counted; `incidents` was never separately guarded) = **0 left**.
  One trade: the events-only path now builds the attribute maps it then hands to a no-op (not observable).
- **Characterisation first** — `AlertRecordsParityTest` (9 tests) pins, on BOTH wirings, the scenarios: same key twice
  -> one fired entry + one `ALERT_FIRED` + Signal; critical raises no Incident events-only (and one with objects — the
  twin that makes "nothing touched" falsifiable); restart re-fires once (events-only; object-backed dedupes the persisted
  ALERT but still re-fires the in-memory entry); scalar Measure heal/relapse + restart (events-only has **no open-Alert
  memory across a restart**, pinned); `by` rule open keys, heal, relapse, restart (events-only re-fires every
  still-breaching key once); freshness fire/cooldown/restart; the explicit-port constructor through a call-counting port;
  wiring selection. The 73 pre-existing alert tests (`AlertServiceTest`, `PerEntityAlertTest`, `MeasureAlertTest`,
  `FreshnessAlertTest`...) pass unchanged.
- **Three `FakeObjectAccess` copies** (engine, scoring, reconciliation tests) are NOT consolidated; recorded.
- **Still-open product call — should Personal keep Alert history across restarts?** Today it does not (in-memory ring,
  dedupe state lost on restart). Options: **(a)** move ALERT out of the generic object substrate into a small
  alert-owned persisted record (Personal-safe; also what lets Incidents extract) — needs a persistence move + migration;
  **(b)** give `ObjectAccess` an in-memory/file implementation on Personal — keeps the substrate, drags operational
  objects into Personal, contradicting `NoOperationalObjectsShipInThePersonalBuildTest`; **(c)** accept events-only
  (status quo; Personal never remembers). **Recommendation (a), in two steps:** step 1 = this port (done); step 2 = an
  `AlertRecords` implementation over an alert-owned store, behind the same port, with the ALERT export/import path.
  **DECIDED 2026-10-07 (operator): option (a)** — built as slice 2 below.

### P7 Incidents slice 2 as built (2026-10-07 — Alerts leave the object substrate; Personal keeps Alert history)

**Operator decision (2026-10-07):** Personal MUST keep Alert history and restart-safe de-duplication — option (a). The
Alert records now live in an Alert-owned **store**, behind the unchanged-in-spirit `AlertRecords` port, on EVERY edition.

- **Name:** the persistence interface is **`AlertStore`** (`com.gamma.alert`, GLOSSARY: *store* = the physical backend; a
  role-word compound is ordinary engineering, §0). Not "ledger" (Signal / batch ledgers). Implementations
  **`DbAlertStore`** (plain JDBC, portable `VARCHAR`/`BIGINT` DDL, one table `inspecto_alerts`, `db-layer.md` §3.14) and
  **`InMemoryAlertStore`**. `AlertRecords.of(AlertStore, Optional<ObjectAccess>)` returns the single implementation
  **`StoredAlertRecords`**; `ObjectBackedAlertRecords` and `NoAlertRecords` are **deleted** (the `NO_INCIDENTS`
  "opens nothing" constant moved onto the port). The store owns the Alert half; ops, when present, supplies only the Incident
  half (`activeIncidentIndex`, `reopenIncident`, the link).
- **Family `ALERTS`** — the 16th `OperationalDb.Family` (`alerts.backend`, default **`db`**, `Mode.DB_FLAG`,
  `alerts.db.url/.user/.password`, `SpaceRoot.alertsDbUrl()` -> `<space>/duckdb/inspecto-alerts.db`; PostgreSQL through the shared
  `-Dinspecto.db=postgres`, schema-per-Space like its siblings). **P1-FAMILY registry grew by one hand-edit**: the two count
  tripwires (`OperationalDbTest`, `ControlApiSystemRoutesTest`) 15 -> 16 and the seven prose mentions
  (`check-family-count.mjs`) — the cost row `MODULE-REORG-P1-FAMILY` exists to remove. Surefire pins
  `-Dalerts.backend=memory` in the root pom (no test creates a DuckDB file).
- **The CWD trap, answered.** `LegacySpaceRoot.alertsDbUrl()` resolves under `-Dassist.write.root/duckdb/` when one is set (the
  operator named that directory); with none it has no directory of its own, so `ServiceStores.openAlertStore` keeps the Alerts
  **in memory, loudly (`StoreHealth` DEGRADED)** rather than create `inspecto-alerts.db` in the working directory.
  Production Personal runs under a Space (`DirSpaceRoot`), so it is durable. Pinned by `AlertStoreWiringTest`. An explicit
  `-Dalerts.backend=db` whose URL cannot open fails boot; the default degrades to memory (alerting never stops a boot).
- **`ESCALATED_FROM` is now a cross-store reference.** `ObjectAccess.linkSubject(fromId, subjectKind, subjectId, relationship, actor)`
  (default no-op) / `ObjectService.linkSubject` add an edge whose far end is NOT an object (`toType` `ALERT`, no existence
  check on it); the Alert row keeps `incident_id`. `ObjectService.link` now shares one `addLink` with it, behaviour unchanged.
  The incident detail's link payload is **unchanged** (`{from, fromType, to, toType: "ALERT", relationship}`); `GET
  /objects/{id}/graph` lists the edge but has no node for the Alert (it never had one for a deleted object). The UI resolves no
  link target by id, so nothing in the SPA moved (no SPA change, no SPA test run). The OBJECT_LINKED event still emits.
- **Behaviour changes, loud:** (1) **Personal keeps Alert history across restarts** — `GET /alerts` is still the in-memory ring
  (`AlertService.recent`, shape byte-compatible: `ApiContractTest` / openapi byte-equal), but the ring is **re-seeded at
  construction from `AlertRecords.recentFired`** (the fired `Alert.toMap()` is stored). (2) **A restart no longer re-fires an
  open Alert** for ledger / freshness / measure / investigation rules: `fire()` finds no cooldown memory but an open record, so it
  starts the cooldown clock and fires nothing (a persisting breach re-announces on the normal cadence; a heal resolves the record,
  so a relapse still fires at once). (3) The **freshness all-clear now resolves the Alert** (it could not — `ObjectAccess` had no
  transition) and also fires from the open record after a restart. (4) **No `OBJECT_OPENED`/object events for rule-fired
  Alerts** and no ALERT row in `GET /objects` / the Objects UI for them (gap / imbalance ALERTs from the Event bridge remain).
- **One-shot migration (`AlertMigration`, wired in `CollectorService` before `AlertService` reads the store):** when the Alert
  store is EMPTY and ops is present, every still-active (OPEN / ACKNOWLEDGED) ALERT object is copied into the store **under its own
  id** (so an existing Incident `ESCALATED_FROM` edge keeps pointing at it), keeping state, scope, severity, title/message,
  attributes and `createdAt`; the gap / imbalance ALERTs (`rule` = `sequence_gap` / `conservation_imbalance`) are skipped, resolved
  ALERTs are history and stay. Idempotent (non-empty store = skip), never deletes (the object rows stay), logs the count. Needs a
  new read: `ObjectAccess.activeDetail(kind)` (default empty). Evidence: `AlertMigrationOpsTest` (real seeded `ObjectService`).
- **What still references `ObjectType.ALERT` (deliberate, nothing broken):** `Workflow.defaultFor(ALERT)` (the Alert store reuses
  that workflow for its transitions); `EventObjectBridge` (gap / imbalance events open ALERT objects — **kept**: it is not an Alert
  Rule firing, it does not duplicate the store, and moving it needs the ops module to depend on `AlertRecords`, a bigger contract);
  `ObjectType.ALERT` in `ObjectRoutes` validation, `FindingsSpec` default, the SPA `governance-model` (governed types) and
  `InspectoIntelligenceAgent.scanRemediableState` (`findByStatus(ALERT, OPEN)`) — **left**: the agent's `alert_triage` ack goes over
  `POST /objects/{id}/ack`, so it now sees only the bridge's ALERT objects, **not rule-fired Alerts** (a narrowed read; a real
  fix is an Alert ack/resolve route + store read — see the open items). The enum value stays: persisted ALERT rows and the SPA
  need it.
- **Still open (filed in BACKLOG `MODULE-REORG-P7-INCIDENTS`):** no operator **ack / resolve route** for a stored Alert (it used
  to be `POST /objects/{id}/ack|resolve`; ledger-metric Alerts have no heal edge, so their record stays open until a rule change or
  a route exists — the in-process cooldown still re-announces them); no retention for resolved rows; the intelligence agent's
  `alert_triage` over stored Alerts; moving the Event bridge's ALERTs into the store; the Workflow/SLA governance of the ALERT
  type (`governance-model`) is now moot for rule-fired Alerts. **Not verified:** live PostgreSQL (`PostgresSchemaPerSpaceTest`
  skips without a database; the DDL is plain `VARCHAR`/`BIGINT`).

### P7 Incidents slice 3 as built (2026-10-07 — operator ack / resolve routes for stored Alerts)

**Regression fixed:** slice 2 moved rule-fired Alerts out of `GET /objects`, so `POST /objects/{id}/ack|resolve` no longer
reached them and Personal never had a route. **Survey of what an operator could do to an ALERT object before:** ack / resolve /
transition / assign / notes / tags through `ObjectRoutes` (`canWorkIncidents` for ack, resolve, transition, assign; Workflow
`OPEN -ack-> ACKNOWLEDGED -resolve-> RESOLVED`, plus `OPEN -resolve-> RESOLVED`), and the Objects detail pane offered ack / resolve
buttons (`object-detail.component.ts`). The Alerts pane (`alerts.service.ts`) only ever read `GET /alerts*` — it never had an
ack / resolve affordance, so **no SPA change was made** (follow-up filed). Assign / notes / tags have no stored-Alert equivalent
(not in scope).

- **Routes** (core `AlertRoutes`): `POST /alerts/([^/]+)/ack` and `.../resolve`, `withCapability("canWorkIncidents", ...)` —
  the SAME capability the object routes used. Two `CapabilityManifest` entries; `canWorkIncidents` is in the ops-side roles, and on a
  build with no Subject (Personal) the gate is a no-op, exactly like every other capability. Actor = the authenticated Subject, else
  the body's `actor`, else `operator` (a body field never re-attributes a Subject's act). **Unknown id -> 404 `NOT_FOUND`; a move
  illegal from the state (second ack, any move after RESOLVED) -> 422** — what `ObjectRoutes` answered, not 200-idempotent. 200 returns
  `{id, state, title, severity, scope, openedAt, closedAt, closedBy, incidentId}`.
- **Port:** `AlertRecords.acknowledgeAlert / findAlert / recentAlertRows`; `AlertStore.get / recentRows` (`recentFired` is now a
  default over `recentRows`). The state change goes through `AlertService.acknowledge / resolve` -> the port; no `*store*.write(` in the
  route class (`ConfigWriteFunnelTest`, `DecisionRuleWritersTest` unchanged and green).
- **`GET /alerts` reflects state:** each entry the store holds a record for gains **optional `id` and `state`** (OPEN / ACKNOWLEDGED /
  RESOLVED); the ring is still the source of order and the documented members are unchanged (`/alerts` is an undocumented generated
  skeleton in `openapi-v1.json`). The join is by `Alert` value equality against `recentAlertRows(capacity)` — one query per GET. An
  Alert the store de-duplicated into an already-open record has no row of its own and carries no `id`. Incident promotion link untouched.
- **Audit:** no classifier change — `AuditTrail.classify` keys on the last path segment (`ack` -> acknowledged, `resolve` -> resolved),
  so the new paths are audited already; pinned by `ControlApiAlertAckTest` (one AUDIT row, capability + actor).
- **Evidence:** `ControlApiAlertAckTest` (8 tests, real HTTP, Personal-shaped classpath: 401, 403, 200 ack then resolve, 404 both verbs,
  422 repeat, resolve-without-ack, Subject not re-attributable, state in `GET /alerts`, audit row, no-Authenticator honour-system actor).
  `openapi-v1.json` +2 generated skeleton paths (hand-kept schema block left byte-identical); `route-gating.md` regenerated (lines moved + 2 rows).
- **Still open (BACKLOG):** an Alerts-pane ack / resolve affordance; the `alert_triage` agent acking through the new route; the with-ops
  HTTP twin of the test lives only as this Personal-shaped class (the ops module's `ObjectRoutes` is not involved in these routes).

### P7 contracts: access module as built (2026-10-07, `9dc9df34c` — the auth contract sheds policy and state)

New Base module **`platform/inspecto-access`** (id `access`, `platform`/`base`, package **`com.gamma.access`**). Moved out of
`inspecto-auth-spi` (and, for the store, out of core): `Roles`, `AccessGrants`, `ComponentAccess`, `WriteGates`,
`CapabilityManifest`, `AuditTrail`, `RowScope`, `AccessPolicyStore` (+ `ComponentAccessAsOwnerTest`). Depends on api, util,
config, audit-spi, auth-spi; **never** on core `inspecto`. `inspecto-http-spi` (`ApiContext` stands on `Roles.ATTR_CONFIG_ROOT`
and `ComponentAccess.ATTR_HELD_ROLES`) now depends on it, so access sits BELOW http-spi. auth-spi dropped its `audit-spi`
dependency (nothing left in it emits events). Package rename chosen over keeping `com.gamma.control`: mechanical by script,
and it takes 8 classes out of the `com.gamma.control` split (auth-spi 24 -> 17 files, processor 121 -> 119; split-package
count stays **3**, unchanged).

- **`RequestAttrs` did NOT move to `http-spi` (cycle).** `Subject.stampIssuer` and `AccessDecider.matchedPolicy` (both
  contracts that stay in auth-spi) call it, and http-spi depends on auth-spi, so moving it makes auth-spi need http-spi.
  It stays in auth-spi (it imports only `HttpExchange`, JDK). Moving it would first need those two helpers relocated to the
  access module; not worth it, no cost today.
- **No cycle for the cluster.** The only remaining auth-spi references to the moved classes were javadoc `{@link}`s; they
  became `{@code}`. The inverse (access -> auth-spi: `Subject`, `AccessDecider(s)`, `Authenticator`, `ApiException`,
  `ErrorCodes`, `RequestAttrs`, `SweepExchange`, `GeoCountryResolvers`, `AccessPolicies`) is the intended direction.
- **Made public because a cross-module caller needs it** (previously package-private, same-package access across the old
  split): in access every cross-module member of `Roles` (`FILE`, `SEED`, `KNOWN_CAPABILITIES`, `Doc`, `effective`, `load`,
  `validate`, `attributeClaims`, `write`), `ComponentAccess` (`OWNER`, `SHARES`, `requireView`, `requireDelete`, `onCreate`,
  `onUpdate`), `AccessGrants.deniedCapabilities`, `AccessPolicyStore` (the whole API), `CapabilityManifest` (class,
  `Entry`/`Exemption`/`Pending`, `ENTRIES`, `EXEMPTIONS`, `PENDING_OPERATOR_CALLS`, lookups), `AuditTrail` (class, `Action`,
  `record`, `authentication` x2, `accessDenied`, `policyDecision`, `classify`); in auth-spi: `AccessDeciders` (class, `active`,
  `forTest`: the moved `ComponentAccessAsOwnerTest` is in another package), `GeoCountryResolvers` (class, `active`),
  `SweepExchange` (class + constructor), `AccessPolicies.Doc.ABSENT`.
- **Guard rows: none.** `ConfigWriteFunnelTest`, `DecisionRuleWritersTest`, `CapabilityManifestTest`, `ImportLoaderInventoryTest`
  and `ModuleManifestGuardTest` needed no row (the scanners walk `ReactorModules`; the manifest scan found the literal
  entries at the new path). Only `tools/check-module-deps.mjs` ALLOWED changed: `inspecto-access` added to http-spi,
  entity-store, la-core, la-storage, la-api, la-store-pg and oidc (each module's enforcer allowlist moved with it);
  auth-spi lost `inspecto-audit-spi`. `tools/route-gating-report.mjs` / `check-module-architecture.mjs` read
  `CapabilityManifest.java` as text: path updated; `compliance/evidence/route-gating.md` regenerated (line numbers only,
  276 routes / 208 gated / 68 exempt, unchanged).
- **Gotcha:** a NEW module's `pom.xml` must be `git add`ed before `ModuleManifestGuardTest` is green (it compares the reactor
  with `git ls-files`). The mover's comment-stripper loses track at `"""` text blocks, so two test files missed their new
  imports; the compile caught both.

### P7 contracts: event cluster as built (2026-10-07, `fdc98a488` — the audit contract sheds the implementations)

**Moved to `platform/inspecto-event`** (Base, below the engine; already depends on audit-spi): `EventLog`, `InMemoryEventStore`,
`SecretScrubber` (package **`com.gamma.audit` -> `com.gamma.event`**, mechanical by script) and `MetricRegistry` (package
`com.gamma.metrics` unchanged, module only; `EventLog` and `MetricRegistry` call each other, so they cannot be split). Their
tests moved with them (`EventCoreTest`, `SecretScrubberTest`, `MetricRegistryTest`). **Stay in `inspecto-audit-spi`:** `Event`,
`EventType`, `EventLevel`, `EventQuery`, `EventStore` (interface), `AuditAttrs`, `AuditChain`, and the new `EventSink`.

**Dependency-graph analysis (why this shape).** The ALLOWED table of `check-module-deps` forbids `inspecto-event` (and `etl`)
in the closure of `http-spi`, `entity-store`, `la-*` and `oidc`; `inspecto-access` sits below `http-spi`, so `access -> event`
is banned too. Those modules held the only concrete-`EventLog` callers below the event module: `AuditTrail` (access, 4 sites)
and `la-api` (24 sites, all `EventLog.current().emit`). `etl` only names the class in a test via `Class.forName`. Everything
else (engine, processor, acquire, ops, exchange, intelligence, geo-link, agent, scoring, entity-list, backup, connectors-kafka,
demo-auth, policy, telecom-asn1) already sees `inspecto-event`, so **no pom edge was added** and the `check-module-deps` ALLOWED
table is unchanged.

**Decisions.**
- **`EventSink`** (audit-spi): `emit(Event)`, default `emit(Event.Builder)`, static `current()`. `current()` resolves ONCE through
  `ServiceLoader` to `com.gamma.event.CurrentEventSink` (registered in `inspecto-event` `META-INF/services/com.gamma.audit.EventSink`),
  which delegates every call to `EventLog.current()` (per-Space routing is resolved at call time). With no implementation on the
  classpath it is a **no-op** (events dropped, never an error): a contract-only deployment (standalone Link Analysis without
  `inspecto-event`) records no audit trail. Nothing builds that today; if it ever does, supply an `EventSink` there.
  `AuditTrail` and the 19 `la-api` files emit through `EventSink.current()`; the other ~250 importers keep the concrete class
  where they already see `inspecto-event` (not churned).
- **`AuditChain` split, not moved.** `EventStore`'s default methods (`chainHead`, range reads, unlinked count) are written against
  the chain's static format and verifier (`chained`, `seq`, `canonical`, `hash`, `TYPES`, `GENESIS`), so those STAY in the
  contract (`CHAIN_KEYS` made public). The per-log mutable writer state (`headSeq`/`headHash`/`headTs`, `reset`, `link`) moved to a
  package-private `com.gamma.event.AuditChainLinker`. Moving the whole class would have needed those defaults re-homed in every
  store: a bigger contract change, not taken.
- **`MetricRegistry` -> `inspecto-event`, not `inspecto-util`:** it uses `EventLog.SPACE_MDC_KEY` and `EventLog` counts events
  through it; a `util` home would make util depend on event (cycle).
- Persisted-name check: `logback.xml`, `package.ps1` and `tools/*.mjs` hold no `com.gamma.audit.EventLog` string; the one
  `Class.forName("com.gamma.audit.EventLog")` (`EventLogLeakDetector`, etl tests) was rewritten by the mover. `EventType` string
  constants are data, untouched.
- **Guard rows: none.** One doc-count figure moved: the derived `spi-extension-points` count is **35** (was 34) because `EventSink`
  is found through `ServiceLoader`; the four doc lines were updated, the guard untouched.

## 7. Success measures (baseline → target)

| Measure | Today | Target |
|---|---|---|
| Files outside its own module touched to add an Optional feature | 5+ core/UI/build files | 0 |
| Closed central registries (§3.2) | 10 | 0 |
| Hand-maintained edition/module lists | 5+ | 1 (Offerings) |
| Split packages | 7 | 0 |
| Per-Offering boot tests | 0 | one each |
| Contracts with ≥2 implementers covered by a TCK | 1 | all |
| Features whose tests need the full processor | 15 | only those using host internals |
| Config referencing an absent module that survives a save unchanged | untested | tested, 100 % |

## 8. Decisions — all taken (operator, 2026-10-06)

| # | Question | Decision |
|---|---|---|
| D-MR1 | Contract modules | **Keep three** (`audit-spi`, `auth-spi`, `http-spi`); move policy and state out (`AccessPolicies`, `EgressPolicy`, `AuditTrail`, `InMemoryEventStore`); contract-semantics logic (`SpiSlot`, DTO validation, `ErrorCodes`) stays |
| D-MR2 | Rename / regroup directories by kind | **Yes, once, in P2** (operator chose this over the recommendation to defer): directories `spi/ platform/ features/ providers/ la/` with aggregator poms, artifactIds by kind, done together with the manifests so docs, citations and guards are rewritten once Prerequisite done (path-agnostic guards): CapabilityManifestTest and ModuleManifestGuardTest find modules from the poms (`ReactorModules`, inspecto test tree) and `tools/check-module-architecture.mjs` + `check-authgate-coverage.mjs` derive the module from the reactor poms (`tools/reactor-modules.mjs`), so none of them reads "first path segment"/"sibling of the repo root" any more. **The directory move itself is DEFERRED by judgement to a dedicated quiet window.** Evidence (D-MR2 survey): 338 files carry directory paths, 617 carry artifactId tokens, ~414 `package.ps1` references, ~144 `-pl` lines, 988 backticked doc citations, 213 doc links; Option A (directories only, artifactIds stay) is ~80% cheaper than Option B (directories + artifactIds). The value of the regroup beyond manifests + Offerings is human navigability only. ⚠ This is a SEQUENCING judgement, NOT a reversal of the operator's "yes, once": it needs the operator's confirmation to drop it or to schedule the window. Recommended recipe: two commits - (1) pure `git mv` of the module directories plus the aggregator poms' `<module>` paths, nothing else; (2) path rewrites (`tools/bundle-modules.mjs` `dir:`, `inspecto/package.ps1`, `-pl` lines -> `-pl :artifactId`, doc citations/links, hard-coded registry paths in `check-module-architecture.mjs`); `.gitignore` `**/target/`; verify with the full reactor + every ci.yml no-build guard. **Regroup dry-run (2026-10-07):** operator decision = do it NOW, directories only, artifactIds unchanged; scripted as `tools/regroup-modules.mjs` (default dry-run, `--apply` refuses unless the tree is clean and the branch is master, makes the two commits above; tests `tools/regroup-modules.test.mjs`). Table: `spi/` 3, `platform/` 10, `features/` 9, `la/` 6 (incl. geo-link), `providers/` 11 (incl. the nested `asn-parser` reactor as a unit); every module in the root pom and every `module.toon` is covered; `inspecto` stays at the root (moving it would touch ~219 files naming `inspecto/` plus every CI jar path and `package.ps1`). No aggregator poms: plain `<module>features/inspecto-ops</module>` entries keep every parent chain and coordinate. Dry-run on the real tree: 1 + 257 files would change (commit 1: 39 `git mv` + 92 root-pom `<module>` entries; commit 2: 38 child `<relativePath>`, 39 `.gitignore` lines, 24 `dir:` entries, ~49 `$modules` and `-pl` tokens in `package.ps1` to `:artifactId`, 408 doc path citations in 96 files, 35 doc links in 5 files, 38 Java `..`-relative test paths in 36 files). Found by applying the script to a scratch copy and running every no-build guard: five tools read a path where they now need a module identity (`check-doc-counts` scanned only top-level `inspecto*` dirs and would silently under-count, `check-module-deps` and `check-sbom-modules` and `reactor-modules` alias and `run-backend.ps1`); the script patches them by exact text. After that scratch copy was green on every guard and tool test (`check-secrets` is red on master already, SEC-INCIDENT-1) and `route-gating-report.mjs` regenerates the evidence table. Printed, never guessed: 186 quoted bare module tokens in tools (artifactId tables, left alone), 2 glob lines, 4 `user.dir`/ancestor walks in tests (checked, benign). Not run: Maven (the reactor build and the full gate are the Phase C list the script prints). **DONE 2026-10-07:** applied as `61d156298` (pure moves) + `77a7419f0` (rewrites), then a fallout commit. As built: directories only, artifactIds unchanged, `inspecto` at the root, no aggregator poms. Gotchas found by the real apply: (1) `--apply` ran `git add -A` BEFORE the `.gitignore` rewrite, so the moved (formerly ignored) `target/` build output was staged into commit 1 (351 files); the script now stages with `git add -u`; (2) the script rewrote its own test file's fixtures (now in `FIXTURE_FILES`); (3) `check-secrets` was red on master on three TCK dummy constants, shortened under the 16-char floor before the apply; (4) `-pl` needs `:artifactId` now. |
| D-MR3 | Licence / entitlement engine | **None**; Installed / Enabled / Permitted gate everything; `entitlementKey` reserved in the manifest |
| D-MR4 | Manifest | **TOON at `META-INF/inspecto/module.toon`** |
| D-MR5 | `metrics` and `events` (one file each) | **Merge into one Observability module**, included from Professional up (CP-13 unchanged) |
| D-MR6 | Split `inspecto-security` | **Yes, now** (operator chose this over "on demand"): three providers — OIDC authenticator + token relay, secrets provider, geo-country resolver |
| D-MR7 | JPMS | **No**; the split-package guard and signed per-module jars give the boundary |
| D-MR8 | Thin per-module jars | **Yes** (P3) |
| D-MR9 | What is sold separately | **The offering map in §8a** |
| D-MR10 | Per-Space enablement | **Yes** (P2) |
| D-MR11 | Where Incidents sit | **Its own add-on, from Professional up**; EDG-01 cell 7 and OPS-02 stand |
| D-MR12 | One Decision Kernel for the nine rule kinds | **Yes, spike first** (§8b): the Consequence registry lands in P1; the Condition Language is adopted kind by kind in P7 after the spike |

## 8a. D-MR9 — the offering map (✅ decided, operator 2026-10-06)

**Test for "separately saleable"** — all five must hold, otherwise the capability is Base:
1. **A distinct buyer or budget** recognises it (a persona or team that asks for it by name).
2. **The base stays coherent without it** — no core workflow is left with a dead end.
3. **Separable at reasonable cost** — already a module, or a clean seam (§3).
4. **Its absence breaks no security or compliance promise.**
5. **It carries its own cost or risk** — third-party libraries, an LLM, a database, outbound egress — which is
   what makes pricing it separately fair.

### Base — every Offering, never removed
Ingest, Collectors (file/SFTP), parsing, Pipelines, Consignments, schema and Catalog, Expectations, **Alerts**,
in-app notifications, **Studio** (Datasets, Queries, Widgets, Dashboards), Jobs and scheduling, Decision Rules,
Spaces, the audit log, the maker-checker hold, and the condition evaluation that the rule kinds share (§8b).

**Alerts and Incidents are two separate modules (operator, 2026-10-06), and Incidents does not need Case
Management.** Alerts are base. **Incidents is an add-on included from Professional up** (D-MR11, decided). The
chain is Events/Signals → **Alerts** (Alert Rules evaluate) → **Incidents** (an Alert promoted, through the
`IncidentAccess` Platform Service, when the Incidents add-on is installed) → *optional* **Cases and Tasks** (Case
Management add-on). Personal keeps its recorded posture (EDG-01 cell 7): Alerts, no operational objects. An
install with Incidents but without Case Management still raises and works Incidents.
Today one object model carries all four: `ObjectType { ALERT, INCIDENT, CASE, TASK }` (engine `objects`), with
the object, note, link and tag stores, `ObjectService` and `ObjectRoutes` in `inspecto-ops`. The split: the
**Incidents** module takes that shared object substrate (stores, notes, links, tags, `ObjectRoutes`) and the
INCIDENT type; **Case Management** keeps CASE and TASK plus `CaseRule` / `CaseRuleEvalJob`. ⚠ To verify before P7:
whether ALERT objects are written to that store today (then Alerts depend on the substrate too) and how far
`ObjectService` already separates the types.

Why not sell Alerts, notifications or Studio separately: they fail test 2 — without them a data-assurance
platform cannot show or flag anything, and every edition already ships them (EDITIONS CP-08, CP-12; the SPA
deliberately does not gate Alerts). Moving `alert`, `notify`, `query` or `catalog` out of the engine would cost
an XL move for no offering value.

### Add-ons — horizontal, sold to any customer

| Add-on | Modules | Why it passes the test |
|---|---|---|
| **Incidents** (D-MR11, operator 2026-10-06) | the object substrate (engine `objects` + the `ops` object, note, link and tag stores, `ObjectRoutes`) and the INCIDENT type | included in every Offering from **Professional up**; never Personal (EDG-01 cell 7 stands). Carries the Enterprise "Postgres mandatory" rule for operational objects (OPS-02) |
| **Case Management** | `ops` remainder: Cases, Tasks, Case Rules (`CaseRule`, `CaseRuleEvalJob`) | the assurance operations team; already optional (CP-11). Requires **Incidents** and **Workflow & SLA**; offers **Action Requests** from Cases when that add-on is present |
| **Reconciliation** (operator, 2026-10-06) | `ReconRunJob`, `ReconRoutes`, recon boards, break sets (CP-10) | domain-neutral: revenue in telecom; stock, orders and shipments in retail and supply chain. The RA function pack requires it |
| **Scoring & Lists** (operator, 2026-10-06) | engine `risk` (`RiskScoreModel`, `RiskScorer`, `RiskScoreEvaluator`), `RiskScoreJobType`, `RiskScoreRoutes`, `entity-list` (Entity Lists, watch-list feed) | domain-neutral: entity types are already free-form. The FM function pack requires it; credit, churn or supplier-risk packs could too |
| **Action Requests** (operator, 2026-10-06) | four-eyes approved outbound calls: create → approve/decline → dispatch with bounded retries under one idempotency key, the `approverCheck`, the Decision Rule `invoke-api` consequence — **raisable from any module** | today `ActionRequests`, `ActionDispatcher`, `ActionRequestRoutes` sit in the processor, link only to an Incident or Case (`inspecto-ops`), and send through `inspecto-notify-channels` (CP-16). The add-on links to anything that implements a small **linked-subject contract** (id, visibility check for the approver's data scope, a place to show the answers): Incidents and Cases first, then e.g. reconciliation breaks, Link Analysis investigations, Entity List entries. It **requires Integration & Delivery** for the wire (egress allowlist, pinned connect) and uses the base approver roster and four-eyes rules, which stay base because the Pending Change hold needs them too |
| **Workflow & SLA** (operator, 2026-10-06) | authored Workflows (states, transitions), SLA policies with a business calendar, escalation — **usable by any module**, not only Case Management | today the models are dependency-free (`Workflow` 325 LOC, `SlaPolicy` 179, `EscalationRule` 100 in engine `objects`) but only `ops` applies them, keyed by its own object types. The add-on applies them to anything that implements a small **governed-item contract** (state, priority, owner, timestamps, allowed transitions): Incidents and Cases first, then e.g. reconciliation breaks, Entity List reviews, Link Analysis investigations. `inspecto-agent`'s own `EscalationPolicy` **stays separate** (operator re-decision 2026-10-06, after the spike showed it is an LLM retry ladder, not an escalation rule); it gets its own canonical name in P0. Base features (the maker-checker expiry, Action Request expiry) keep their simple built-in expiry and never depend on the add-on |
| **Link Analysis & Investigations** | `la-*`, `geo-link`, the `la-app` UI | the fraud investigator; already optional (CP-09) and already ships as its own UI application — the strongest standalone candidate, possibly a product of its own |
| **AI Assist & Intelligence** | `agent`, `agent-hosted`, `intelligence` | LLM cost and data-egress risk; air-gapped customers must be able to omit it (CP-14) |
| **Integration & Delivery** | `notify-channels`, the `publish.postgres` BI publication Job, outbound webhooks | outbound egress is a security decision; needs the Postgres driver (CP-15, JOB-06) |
| **Premium Connectors** | `connectors` split: Kafka (**built** 2026-10-07 as `inspecto-connectors-kafka`); cloud object stores and DB export **stayed in core `connectors`** by the footprint test (P7 decision, §4) | distinct third-party footprint (`kafka-clients`); file/SFTP/object stores/DB export stay Base |
| **Multi-entity / Group** | `exchange` + per-Space isolation | only meaningful with several Spaces — the group/regulator federation proposal (SEC-06, SEC-10) |
| **Screening** (gap, 2026-10-07) | fuzzy name and identifier matching of entities against Entity Lists (sanctions, PEP, internal deny lists), with match scoring and review | ❌ not built (`SCREENING-1`); domain-neutral: customer, wallet, supplier and partner screening. Required by the AML function pack |
| **Regulatory Reporting** (gap, 2026-10-07) | report templates in regulator formats (e.g. SAR / STR), assembled from Incidents, Cases and evidence, with maker-checker before submission and a submission log | ❌ not built (`REGULATORY-REPORTING-1`); required by the AML and Compliance Audit function packs |
| **Anomaly Detection** (gap, 2026-10-07) | behavioural anomaly scoring per entity with an explanation of the contributing features, beyond today's Expectation baselines and forecast bands | ❌ not built (`ANOMALY-DETECTION-1`); strengthens every fraud and assurance pack |
| **Real-time Decisioning** (gap, 2026-10-07) | an inline decision point on a stream (e.g. allow / block before a call or transaction completes), evaluated by the Decision Kernel | ❌ not built (`REALTIME-DECISIONING-1`); a large design: today the engine is batch over Consignments |
| **Predictive Analytics** (gap, 2026-10-07) | segmentation, churn and demand forecasting models usable from Studio | ❌ not built (`PREDICTIVE-ANALYTICS-1`); lower priority; required by a Sales & Marketing BI function pack |

### Solution packs — per domain, mostly content
Each pack = **Space Templates** (Pipelines, Expectations, Alert and Decision Rule sets, Dashboards, thresholds as
configuration) that **requires** add-ons; a pack carries its own code only when no domain-neutral add-on can
express its computation. Content is where most domain value sits (assurance plan §1: "content packs ship as Space
Templates").

| Pack | Requires | Content |
|---|---|---|
| **Revenue Assurance** | Reconciliation | RA Space Templates |
| **Fraud Management** | Scoring & Lists (pairs with Link Analysis) | FM Space Templates |
| **Business Assurance / payment fraud** | — | Space Templates only |
| **AML** (gap, 2026-10-07) | Screening, Regulatory Reporting, Scoring & Lists, Link Analysis | typologies (smurfing, structuring, threshold and watch-list monitoring) as Space Templates — `PACK-AML-1` |
| **Sales & Marketing BI** (gap, 2026-10-07) | Predictive Analytics, Reconciliation | commission verification, distribution and churn content — `PACK-SALES-MARKETING-1` (later) |

### Tiers — deployment and compliance posture, not features
**Personal** (single user, no IAM), **Professional** (IAM, HTTPS, RBAC, Postgres, backup, `/metrics`, events
feed), **Enterprise** (ABAC, enforced Space isolation, shared stores, HA, certifications). Backup, metrics and
the events feed belong to the tier — operational posture is not sold item by item.

### Competitive scan — LATRO (2026-10-07)

Public product pages of LATRO (telecom and mobile-money assurance: Defend, Assure, Explore, Assure Fintech, managed
services) were mapped onto this offering map. Already covered: reconciliation, threshold rules, risk scoring,
allow/deny lists, link analysis and geo, case workflows, dashboards, data onboarding, telecom RA, telecom fraud
(CDR-based) and business assurance. Gaps added above as add-ons and packs: **Screening**, **Regulatory
Reporting**, **Anomaly Detection**, **Real-time Decisioning**, **Predictive Analytics**, the **AML** function pack,
and — as industry packs — **Mobile Money** (`PACK-MOBILE-MONEY-1`: wallet / agent / bank reconciliation,
commissions and fees, agent fraud, KYC checks) and missing **telecom fraud content** (`TELCO-FRAUD-CONTENT-GAPS-1`:
recharge, data-charging bypass, internal fraud). Out of scope as software: signalling probes, radio scanning
kits, direction finding, device forensics and managed services. Order: AML (+ Screening, Regulatory Reporting) →
Mobile Money → Anomaly Detection → telecom content → Real-time Decisioning → Predictive Analytics.

### More solution packs will follow (operator, 2026-10-06)
Named so far by the operator: **compliance Audit**, **BI for other domains**, **LA for other domains**, and new
verticals — **mobile money**, **fixed-line**, **cable / IPDR-based analytics**, and beyond telecom **retail** and
**supply chain** (named in the operator's reply when asked what "Fx" in the first note meant).

**Two kinds of pack, composed — not one pack per vertical × function.** With five functions (RA, FM, Compliance
Audit, BI, LA) and six-plus verticals, one pack per pair would mean thirty packs that copy each other. Instead:

| Pack kind | Holds | Examples |
|---|---|---|
| **Function pack** — domain-neutral | generic Space Templates written against neutral entity and measure names, requiring the add-ons it uses | Revenue Assurance (→ Reconciliation), Fraud Management (→ Scoring & Lists), Compliance Audit, BI, Link Analysis |
| **Industry pack** — the vertical's data | record formats and parsers, Collector connectors, entity types, link kinds, reference data, the mapping from the vertical's fields to the function packs' neutral names | Telecom mobile (ASN.1 CDRs), fixed-line, cable / IPDR, mobile money, retail, supply chain |

A sellable solution such as *"Mobile money fraud"* is then an **Offering** = Fraud Management function pack +
Mobile money industry pack + a thin layer of Space Templates that `requires` both. The manifest's `requires`
(§2.2) is what makes this composition safe: a template installs only where both packs are present.

Consequence for §4: `asn-decoders` and the engine's `Asn1ParserPlugin` / `parser.asn1.ber` catalog entry stop
being Base and become a **Provider in the Telecom industry pack** — a retail or supply-chain install should not
ship an ASN.1 telecom decoder. An IPDR collector or parser, when built, lands in the cable industry pack the same
way.

**Domain-neutrality, first measurement (2026-10-06, grep for telecom terms in generic modules — preliminary):**
almost every hit is an event-bus "subscriber", a code comment or an example in a message. Real telecom coupling
found so far: the ASN.1 parser in the core processor catalog; `RiskScoreModel.ENTITY_TYPES`
(`subscriber, account, device, sim, dealer, channel, partner`), which is a telecom-flavoured suggestion list but
**accepts any other token**, so not a blocker; and examples such as `MSISDN` in messages. Link Analysis reads its
entity types from configuration (`EntityTypes`, `LinkAnalysisSettings` in `inspecto-entity-store`), so "LA for
other domains" looks like a content job, not a code job — to be confirmed by P0's check.

Three design requirements follow, and they are binding on P1–P6:
1. **A new solution pack needs zero platform edits.** It ships as Space Templates, plus at most one domain module
   and one UI feature, and installs as an Offering item. P1's exit measure (an Optional feature touches only its
   own module) applies to packs too.
2. **Generic engines stay domain-neutral.** Studio/BI, Link Analysis, Expectations, Alerts, Decision Rules and the
   audit log carry no domain vocabulary in code; entity types, link kinds, measures, thresholds and labels come
   from the pack's configuration. "LA for other domains" and "BI for other domains" depend on this.
   ⚠ Not yet verified for Link Analysis: whether telecom-specific entity or link types are hard-coded in
   `la-core` / `la-api` / `geo-link` or come from configuration. P0 adds a domain-neutrality check (report-only)
   and the answer decides whether LA needs work before a second LA pack.
3. **A domain module is the exception, not the rule.** A pack gets code only when its domain needs a computation
   no domain-neutral add-on can express; Reconciliation and Scoring & Lists were promoted to add-ons for exactly
   that reason, so today no pack needs code of its own.

### What this changes in the plan
- P7 moves only: Workflow & SLA, Action Requests, Incidents (object substrate) and Case Management apart;
  `risk` + `entity-list` → Scoring & Lists; recon → Reconciliation; the ASN.1 parser → Telecom industry pack;
  the `connectors` split. `alert`, `notify`, `query`, `catalog` stay in the engine.

## 8c. Storage and state — the base layer the first drafts left implicit

Raised by the operator (2026-10-06): the plan read as if the platform had no database. It has **no single
database and no ORM**, but it does have a storage layer with three kinds of state (as built:
[`okf/backend/engine/db-layer.md`](../okf/backend/engine/db-layer.md) §1):

| Kind of state | What | Where and how | Today's code |
|---|---|---|---|
| **Business data** | ingested records | Hive-partitioned Parquet/CSV under the Space's data directory, queried through DuckDB (`read_parquet` / `read_csv`); DuckLake registration | `SqlViews`, `DatasetRelation`, `DuckDbRecordSink`, `DuckLakeRegistrar`, the Sinks |
| **Configuration** | authored Components, Pipelines, Views, Connections, settings | TOON files under the Space's `registry/`, versioned in `.history/`, written atomically | `ComponentStore`, `AtomicFiles`, `ConfigSafetyValidator`, the path jail |
| **Operational data** | facts about the system's own operation: job runs, ingest status, provenance, Consignment outputs, file stages, dedup and acquisition ledgers, run lease, delivery receipts, operational objects | one DuckDB file per family by default, **Postgres** via `-Dinspecto.db=postgres` (driver as the `postgresql.jar` sidecar); events append-only in Parquet | `OperationalDb` (+ its `Family` roster), `ServiceStores`, `JdbcDrivers`, `DbRunLease`, `ParquetEventStore` |

Everything is **per Space** (`SpaceRoot` resolves the file topology), and backup / restore (tier, Professional
up) carries all three.

**Where it sits in the target model:** a **Storage** row in the Platform base — Lakehouse (business data),
Configuration, Operational DB, Space roots, Sinks — with the backends as **Providers**: the Postgres driver
sidecar, the Postgres Investigation store (`la-store-pg`), and later a shared object store (OPS-05).

**What the reorganisation changes in it:**
1. 🔴 **A tenth closed registry: `OperationalDb.Family`.** The roster of operational-store families is one enum
   in the processor, and optional modules (`inspecto-ops`) open their stores through it so there is "ONE place
   that decides how an operational DB is addressed". Under §1 #10 it becomes a contribution point: a module's
   manifest declares the families it owns (name, default backend, Postgres schema); the platform still owns
   *how* a family is addressed and selected. Lands in P1.
2. **Store-family ownership** is the hook for removal semantics (P4): an absent module's families stay on disk,
   unread, and travel through backup and restore.
3. **Storage contracts stay with their capabilities** (`ObjectStore`, `StatusStore`, `InvestigationStoreProvider`
   …); no central storage SPI module is added (consistent with D-MR1). Each such contract with two or more
   implementations gets a TCK (P5) — `InvestigationStoreContract` is the existing model.

## 8b. Rule evaluation — consolidate, do not add an engine (D-MR12, OPEN)

**Question (operator, 2026-10-06): don't we need a rule engine?** The platform already has nine rule kinds, each
with its own evaluator, measured 2026-10-06:

| Kind | Where | LOC | Evaluates over |
|---|---|---|---|
| Alert Rule | engine `alert/AlertRule` (+ `AlertService`) | 532 | Datasets (set-based) |
| Decision Rule | engine `pipeline/DecisionRules`, `query/DecisionRuleApplier`, `query/RuleTemplate` | 97 + 309 + 163 | Datasets (set-based) |
| Expectation | processor `expectation` | ~850 | Datasets (set-based) |
| Risk factors | engine `risk` | ~880 | Datasets (set-based) |
| Notification Rule | engine `notify/NotificationRule` | 120 | one event (in memory) |
| Tag Rule | `ops/tag/TagRule` | 151 | one object (in memory) |
| Case Rule | `ops/tag/CaseRule` | 91 | one object (in memory) |
| Escalation Rule | `inspecto-workflow` `workflow/EscalationRule` | 100 | one object over time |
| Access Policy | auth-spi `AccessPolicies` + `inspecto-policy` | 349 + 195 | one request (in memory) |

**A shared condition language already exists and is barely used.** `inspecto-util`'s `Conditions` was built for
"one policy engine, many policy kinds" (RBAC/ABAC plan §4 A2, 2026-07-23): a small closed grammar
(`and`/`or`/`not`, `==`, `!=`, `in`, `contains`) parsed into a predicate over a context map, and its own header
names Tag Rules and Notification Rules as intended adopters. Today **only Access Policies use it**.

**Recommendation — no general-purpose rule engine** (a Drools-style engine is a large dependency, a second
language for authors, and a poor fit for set-based rules that must run inside DuckDB). Instead, one base
capability with three parts:
1. **One condition language** — `Conditions` — adopted by every in-memory kind (Notification, Tag, Case,
   Escalation, Access). For the set-based kinds (Alert, Decision, Expectation, risk), the same grammar compiles to
   a SQL predicate checked by `SqlGuard`, so authors learn one syntax. ⚠ Needs a spike: whether the set-based kinds'
   current expressions fit the grammar or need it extended.
2. **One trigger → condition → consequence model** — *when* (event, signal, schedule, object change) / *if*
   (conditions) / *then* (consequences) — shared by the kinds instead of nine bespoke shapes. Each kind keeps its
   canonical name and authoring surface (one word → one concept).
3. **An open consequence registry** — modules contribute consequences, like every other §3.2 registry: base
   contributes *notify* and *tag*; Incidents contributes *open Incident*; Case Management *open Case*; Action
   Requests *invoke-api*; Workflow & SLA *escalate*. A consequence whose module is absent is reported unavailable
   by the activator, never silently skipped (today `invoke-api` reports *skipped* on Personal).

Lands in P1 (the consequence registry is a contribution point) and P7 (adopting the shared grammar kind by
kind).

**Canonical names (picked 2026-10-06, confirmed by the operator the same day; enter `docs/GLOSSARY.md` in P0).** The glossary already binds the two
outer terms, so nothing is coined for them: a **Decision Engine** is "anything that turns conditions/signals into
Consequences" (each rule kind is one), and a **Consequence** is the typed action it produces. New names cover only
the shared machinery:
- **Decision Kernel** — the base capability every Decision Engine runs on: the Condition Language, the
  when / if / then model and the Consequence registry. A Decision Engine is a *kind* (Alert Rule, Tag Rule…); the
  Decision Kernel is the one runtime they share. ⛔ Not "rule engine" (bare Rule is banned) and not "policy engine"
  (Policy is taken by Access, Approval and SLA policies).
- **Condition Language** — the **condition tree** (`ConditionTree` / `ConditionSql` / `query-eval.ts`), with
  `Conditions` as a text notation that parses into it (✅ confirmed by the operator after the spike, 2026-10-06).
- **Consequence registry** — the open contribution point for Consequences (an existing term plus "registry").
⚠ `inspecto-agent` has a package `agent.kernel` — a code name, not a glossary term; note the shared word where
the two meet.

### Decision Kernel spike — specification (D-MR12, runs before P7 adoption)

**Goal:** prove or refute that one Condition Language serves all nine Decision Engines, in memory and compiled to
SQL, before any kind is migrated. Time-box: one lane, read-mostly; code only in a throwaway worktree.

**Questions to answer, each with evidence:**
1. **Coverage.** For each kind, list every condition form its authored configs use today (committed TOON under
   `spaces/` and `templates/`, plus test fixtures): comparisons, ranges, `in`, regex/like, null checks, time
   windows, aggregates, joins. Which forms does `Conditions` lack?
2. **SQL compilation.** Can each `Conditions` construct compile to a DuckDB predicate that `SqlGuard` accepts, with
   identifiers bound to declared Dataset columns only (no free SQL)? Prove on Alert Rule and Decision Rule
   fixtures: same rows selected before and after.
3. **Set-based extras.** Alert Rules and risk factors use aggregates and windows. Do those belong in the condition
   (grammar grows) or stay in the kind's own shape around a condition (grammar stays small)? Recommend one.
4. **Trigger model.** Map each kind's current trigger (event, signal, schedule, object change, request) onto one
   when / if / then shape; list kinds that do not fit.
5. **Consequences.** List every Consequence each kind can produce today against the glossary's Consequence list;
   name the module that would contribute each to the Consequence registry.
6. **Cost.** Per kind: LOC removed, LOC added, authoring-surface change for users, and migration of existing
   configs (no compatibility shims — configs are rewritten).

**Exit:** a short report appended here with a per-kind verdict (*adopt as is* / *adopt after grammar extension
X* / *stays bespoke because Y*), the grammar extensions accepted, and the P7 adoption order. ⛔ The spike changes
no shipped behaviour; any prototype lives in a worktree under `.claude/worktrees/` and is deleted after.

### Decision Kernel spike — report (2026-10-06, read-only; three parallel investigations)

**🔴 The premise was wrong: the platform has TWO condition languages, and the richer one is not `Conditions`.**

| | `Conditions` (`inspecto-util`, 282 LOC) | Condition tree (`ConditionTree` 249 + `ConditionSql` 194, engine `query`; UI twin `query-eval.ts`) |
|---|---|---|
| Form | text expression | JSON tree `{kind: group, op: AND\|OR, items: [{kind: condition, field, operator, value, value2}]}` |
| Operators | `==` `!=` `in` `contains`, `and` `or` `not` | `= != < <= > >=`, `between`, `in`, `contains`, `startsWith`, `endsWith`, `isNull`, `isNotNull`; groups AND/OR; **no `not`** |
| Backends | in memory only | in memory **and** DuckDB SQL |
| Users today | Access Policies only | Alert Rule `when`, Decision Rule `when`, Expectation `condition`, the SPA's query builder |
| Compares | field to field (`resource.space != subject.space`) | field to literal only |

So the **Condition Language should be the condition tree**, not `Conditions`: it already has both backends, the
operators most kinds need, a UI builder, and three of the nine kinds. `Conditions` becomes a **text notation that
parses into the same tree** (kept for Access Policies), once the tree gains what it lacks. The canonical name
stands; its referent changes (✅ confirmed, operator 2026-10-06).

**Tree extensions the kinds need:** `not` (negated group); field-to-field operand; a case-insensitive flag on
string operators; `matches` (regex, for Expectations); derived context fields supplied by each kind's context
builder (`ageMinutes`, `levelRank`, a normalised status). One semantic to settle: the tree treats `''` as null in
`isNull`, `Conditions` does not.

**Per-kind verdict**

| Kind | Verdict | Stays bespoke |
|---|---|---|
| Decision Rule | **on the tree already** | — |
| Alert Rule | `when` on the tree already | metric / measure, threshold, window, `by` group-by, freshness — the kind's own shape around a condition |
| Expectation | `condition` on the tree; `non_null` and `range` map to `isNull` / `between`; `regex` after `matches` | `referential` (subquery) and `baseline` (profile comparison) |
| Risk factor | `filters` move to the tree | aggregate, key, weight, cap |
| Tag Rule | filter → tree (needs case-insensitive flag) | status alias fold → done by the context builder |
| Case Rule | filter → tree (same as Tag Rule) | count-within-window grouping |
| Notification Rule | → tree (needs `levelRank`, case-insensitive) | — |
| Escalation Rule | match → tree (needs `ageMinutes`, breach flag) | fire-once ledger, sweep |
| Access Policy | keep the `Conditions` text, compiled into the tree once `not` and field-to-field exist | — |

**What it saves, honestly:** little code (~50 LOC of single-item matchers, plus one of the two duplicate
evaluators). The value is one authoring surface and builder for every kind, one place to lint references, and the
Consequence registry — not deleted code.

**Two findings outside the question:**
- 🔴 **Decision Rules build `DELETE` / `UPDATE` / `CREATE TABLE AS` / `COPY` on the in-flight table by string
  concatenation of `ConditionSql` output, guarded by escaping only — no `SqlGuard`, no bound parameters**
  (`DecisionRuleApplier.java:185-276`). Not shown to be exploitable here; recorded for the security review before
  the kernel makes this path the shared one — ✅ filed as `DECISION-RULE-SQL-GUARD-1` (BACKLOG §3.8).
- ⚠ **The agent's `EscalationPolicy` is not an escalation rule.** It is an LLM retry ladder (`BumpModelTier` →
  `HumanHandoff(queue)` → `Abstain`), procedural and synchronous; only its confidence threshold is a condition. The
  2026-10-06 decision to fold it into Workflow & SLA rested on a false premise — ✅ **re-decided the same day: it
  stays in the agent, under its own canonical name.**

**P7 adoption order:** (1) Expectation `non_null` / `range` onto the tree; (2) tree extensions (`not`,
field-to-field, case-insensitive, `matches`); (3) Tag and Case Rule filters; (4) Notification Rules; (5) Risk
filters; (6) Escalation match; (7) Access Policies' `Conditions` text compiled into the tree (last:
security-sensitive); then retire the duplicate evaluator.

### Consequence registry slice 1 as built (2026-10-07) — `MODULE-REORG-P7-KERNEL`

The string switch in `DecisionRoutes.executeOne` is gone for every action except `invoke-api`; consequences are
looked up in a registry. Commits `4ac4aa0e3` (SPI + registry + built-ins), `1b11157e9` (ops provider, manifest,
status contract), `0983c0bf4` (catalog route), `85ff6dfd1` (SPA).

- **SPI** `com.gamma.decision.ConsequenceProvider` (engine; a new package, so no split): `id()`, `displayName()`,
  `group()` (`platform|routing|notify|object|integration`), `requires()` (host service ids), and
  `execute(ConsequenceContext, Map consequence)`. ⚠ It takes the WHOLE consequence map, not just `params`:
  the routing actions read `destination` and `start-job` reads `target`. No `paramSchema()` — the editor's
  one-secondary-input model lives in `consequence.ts` (`consequenceInputSpec`) and a schema would duplicate it.
- **`ConsequenceContext`** is the narrow host view (rule, actor, `automatic`, matched record, `objects()`,
  `emitSignal`, `triggerJob`/`jobDisabled`, `triggerPipeline`, `authorAlertRule`, `has(serviceId)`); `HostContext`
  and `ApiContext` never cross it. The implementation is a private record inside `DecisionRoutes`. Only `objects`
  is a service id: `start-job` deliberately declares none, because `jobService()` is empty on a space with no jobs
  (that is "no such job", not an absent module).
- **Registry** `Consequences.load(loader)`: built-ins first, then `ServiceLoader` providers, fail-soft (a provider
  that fails to load, or reuses an id, is logged and skipped; a module can never displace a built-in).
- **Built-ins** (`BuiltInConsequences`): `emit-signal`, `create-alert`, `start-job`, `trigger-pipeline`,
  `render-widget`, `generate-report`, and the four routing actions, whose providers only DESCRIBE them
  (`DecisionRuleApplier` still executes them per batch; `execute` reports `skipped`, as before).
  `create-incident` moved to `features/inspecto-ops` (`CreateIncidentConsequence`; service file + `module.toon`
  `provides.consequences` + `contracts`). `invoke-api` stays in `DecisionRoutes` until Action Requests is a module.
- **`provides.consequences`** is a new `ModuleManifest.Provides` list (parsed by `ModuleManifests`; the 6-arg
  constructor stays). Known-modules copies carry it, so an install without ops still knows `create-incident` exists.
- **Status contract (breaking, operator 2026-10-07).**

| Situation | `status` | `detail` |
|---|---|---|
| provider ran | `executed` | the provider's own |
| nothing to do on demand (routing actions; `start-job` on a disabled job when automatic; no such job / pipeline) | `skipped` | the provider's own |
| provider present, a `requires()` service missing | `unavailable` | `requires platform service '<id>'` |
| action declared by a known-modules manifest `provides.consequences`, module not installed (e.g. `create-incident` on Personal) | `unavailable` | the module's `absentMessage` |
| `invoke-api` with no operational objects | `unavailable` | no Incident to raise it on |
| action nobody ever declared | `skipped` | `unknown action ... no installed module provides it` (P4a pin unchanged) |

  The bug this fixes: an absent ops module used to report `create-incident` as `executed`. Rule save is unchanged
  (unknown/absent actions and every key are PRESERVED, pinned by `ModuleRemovalRulesTest`); no `warnings` field was
  added to the save response — the catalog route and the editor carry the warning instead.
- **`GET /decision-rules/consequences`** (ungated like its sibling `GET /decision-rules`):
  `[{id, displayName, group, available, reason?, module?, requires?}]`, ordered by group
  (routing, platform, notify, object, integration) then registration order. Absent-module rows have
  `displayName = id` and `group = platform` (a manifest names ids only, not groups).
- **SPA.** `PLATFORM_ACTIONS` is deleted; the editor loads the catalog. Unavailable actions show `(not installed)` as
  text, are disabled in the select for new choices, and a stored rule naming one renders an inline warning. Two
  defects found on the way and fixed: `consequenceInputSpec` threw for an action it did not know (the editor could
  not open such a rule), and saving would have rebuilt the consequence and dropped its params — an unavailable row
  is now saved back exactly as stored.
- **Not on the registry yet:** Alert Rule actions, Tag/Case rule effects, Notification channels, Escalation target;
  `invoke-api` until Action Requests is a module. Condition Language: step 5 (Risk filters) is approved to proceed
  with an additive optional `when` on `MeasureCompiler.Spec`; step 7 (Access Policies' `Conditions`) is DECLINED for
  now — the tree is fail-open where `Conditions` fails closed; revisit only on operator request.

### Decision Kernel step 2 as built (2026-10-07) — the Condition Language tree extensions

P7 adoption order step 2. Java `ConditionTree` + `ConditionSql` and the UI twin `query-eval.ts` (plus the
optional fields in `query-types.ts`) gained four extensions. Every existing authored tree evaluates
identically — each extension is a new optional key or a new operator.

| Extension | Shape chosen | Why |
|---|---|---|
| `not` | `negate: true` on a **group** (`{kind:'group', op, negate:true, items}`) | One optional key on the existing shape; an op `NOT` with one item would need a new arity rule. Empty / incomplete negated group stays "no constraint" (matches all), like any empty group. In SQL: `(NOT COALESCE((…), FALSE))` — plain `NOT` over SQL's NULL would drop a row the in-memory walk keeps (a NULL cell is "not > 3"). |
| field-to-field | `valueField: '<field>'` beside `value` on a leaf; valid on `= != < <= > >= contains startsWith endsWith`; setting both `value` and `valueField` is refused | Identifier goes through the same `SqlIdent.q` quoting as `field` (nothing widened). A right-hand cell has no column type, so typing is **per row and symmetric** in both backends: both cells numeric → numbers; else both date-like → instants; else strings. A NULL right cell never matches. |
| case-insensitive | `ignoreCase: true`; valid on `= != in contains startsWith endsWith matches`; refused on `< <= > >= between isNull isNotNull` | Applies in the string branch only (a numeric compare is unchanged). SQL: `LOWER(CAST(f AS VARCHAR)) = lower-literal`. `contains/startsWith/endsWith` were already case-insensitive, so the flag is accepted and a no-op there (it cannot make them case-sensitive). |
| `matches` | operator; `value` is the pattern; DuckDB `regexp_matches(CAST(f AS VARCHAR), 'pat'[, 'i'])`, Java `Pattern.find()` | Partial match in both. Pattern is a single-quote-escaped literal. |

**Dialect gap (matches).** DuckDB runs RE2; the JVM runs `java.util.regex`. Validation (`ConditionTree.validate`,
called from `requireGroupRoot`, so both backends and save-time callers share it) refuses a pattern that
fails to compile, exceeds `MAX_PATTERN_LENGTH` (256), or contains lookaround, atomic groups, possessive
quantifiers or back-references — so an accepted pattern means the same in both. Residual gaps: Unicode
class details, `.` vs line terminators, and the check is textual (it can refuse a legal escaped backslash followed by a digit, or an escaped plus followed by +). There is
**no catastrophic-backtracking mitigation** beyond the length cap in the in-JVM evaluator (RE2 in SQL is linear).
The browser twin does not validate: an uncompilable pattern simply matches nothing there; Java is the authority.

**Settled semantic — `''` is null for `isNull`.** The tree keeps treating `''` and NULL alike for `isNull` /
`isNotNull` in both backends (pinned by `ConditionExtensionsTest.emptyStringCountsAsNullForIsNullInBothBackends`).
The `Conditions` text notation in `inspecto-util` (Access Policies) distinguishes them — that is a known
difference to reconcile when step 7 compiles `Conditions` text into the tree (map its null test to
`isNull` AND a non-empty guard, or add a strict operator then).

**Tests.** `ConditionExtensionsTest` (21; each feature selects the same row ids through `ConditionTree` and
through `ConditionSql` executed in DuckDB, plus hostile quotes / semicolons / comments / backslashes /
`%` `_` per operator and a column whose name contains `"`), `query-eval.spec.ts` (+8). 🔴 `query-eval.ts`
has **no application caller** — `evaluateRows` is exported and spec-tested only; the live evaluators are
Java `ConditionTree` (simulate, Alert Rule `when`, Expectation) and `ConditionSql` (Decision Rule applier). The
TS twin is kept in step as the reference the builder preview will use, not because anything runs it today.
`query-sql.ts` (illustrative SQL preview) was NOT extended.

**Follow-ups.** (1) Builder UI — **DONE 2026-10-07.** The live builder is `query-condition-group.component`
(mounted by the Alert Rule, Decision Rule, Expectation, Dashboard filter and Pipeline filter editors; the Queries/Dataset
panel nests it). It gained `matches` (string columns), a per-condition *Compare to another field* switch + field picker
(`valueField`), an *Ignore case* switch, and a per-group *NOT* switch (group `aria-label` reads `NOT (…)`); the new
framework-free `query/condition-rules.ts` holds the operator sets, `validateCondition` (a client mirror of Java
`validate`; its regex check is `new RegExp`, NOT RE2 — advisory, the server is the gate; the error is a `role="alert"` note
wired by `aria-describedby`) and `describeGroup`. `query-sql.ts` (preview + param compiler) now emits the Java `ConditionSql`
shapes. `sql-ast.ts` is unchanged on purpose: it only reads SQL into a tree and refuses what it does not recognise. Hosts
clone the tree and the editor mutates in place, so unmodelled keys survive save (pinned by a round-trip spec).
(2) Step 1: Expectation `non_null` / `range` / `regex` onto the tree — **DONE 2026-10-07**, see "Decision Kernel step 1 as built".
(3) Step 3 (Tag and Case Rule filters) — **DONE 2026-10-07**, see "Decision Kernel step 3 as built"; step 4 (Notification Rules) **DONE 2026-10-07**, see "Decision Kernel step 4 as built"; step 5 (Risk filters) findings only, needs the BI `MeasureCompiler` contract, see "step 5 — FINDINGS"; step 6 (Escalation match) not started.
(4) `DECISION-RULE-SQL-GUARD-1` stays open: the new operators keep today's discipline (quoted identifiers,
escaped literals) but the appliers still concatenate. (5) `query-eval.ts` date parsing treats a zone-less
date-time as local time where Java treats it as UTC (pre-existing for literals; now also field-to-field).

### Decision Kernel step 1 as built (2026-10-07) — Expectation onto the tree

**Decision: the shorthand kinds stay authorable, as sugar.** Grep: six shipped Expectation TOONs (`spaces/demo`, `spaces/ucc`,
`_templates/orders-starter`, `_templates/telco-ra`; all `non_null` or `range`) plus dozens of test and UI-spec bodies author them, and
the Expectation editor, the agent's `expectationDraft` suggestions and `ConfigSpecs` all key on them. Migrating them away would buy
nothing the sugar does not, so no config migrated and no TOON was rewritten. `Expectation.violationTree()` expands `non_null` →
`AND[isNull]`, `range` → `AND[isNotNull, OR[< min, > max]]` (an open end simply omits its leaf — no `between`, since a violation is
the *outside*), `regex` → `AND[isNotNull, NOT AND[matches]]`; `condition` returns its own `when`; `referential` / `baseline`
return `null` and stay bespoke. `ExpectationEvaluator` renders the tree through `ConditionSql.predicate`; the per-kind
`rangePredicate`, the regex literal builder and the number formatter were deleted (identifier checks and the referential
predicate remain).

**Parity.** `ExpectationEvaluatorParityTest` (5) was written first, against the old hand-built SQL, over a seeded Parquet corpus
(NULLs, a non-numeric text column, fractional / open bounds, a quote and `%` in the data and pattern): every hand-computed count
matched the old implementation, and the same counts hold after the switch. TelcoRaGoldenTest (the shipped telco-ra Expectations
plus the planted blank opening balance), `ControlApiExpectationTest`, `ControlApiExpectationCensusTest` and the baseline tests
are unchanged and green.

**Two deliberate behaviour differences** (both pinned): (1) `''` is null to the tree, so `non_null` now also flags empty-string
cells (old `IS NULL` did not) — and `regex` skips them; (2) a `regex` pattern is validated at save by `ConditionTree.validate`
(no lookaround / back-references, 256 characters), where it used to fail at first evaluation inside RE2.

### Decision Kernel step 3 as built (2026-10-07) — Tag Rule and Case Rule filters onto the tree

**Decision: the flat filter stays the authoring form (sugar), evaluation moves to the tree.** Two shipped configs
(`spaces/demo/config/ops/*_tagrule.toon`, `*_caserule.toon`), the `/tags/rules` and `/cases/rules` bodies and their UI forms
all author `type` / `q` / `status` / `priority` / `severity` / `category`, so nothing was migrated and no TOON was rewritten.
`TagRule.Filter.tree()` expands the fields (`Case Rule` reuses the same `Filter`); `Filter.matches` runs
`ConditionTree.matched(tree, [context(o)])`. The hand-coded matcher and its `equalsIgnoreCase` / `toLowerCase` calls are gone.

**Context builder.** `Filter.context(OperationalObject)` supplies `type`, `status`, `priority`, `severity`, `category`,
`text`. The status alias fold is done there for an Incident (`OPEN`→`IDENTIFIED`, `ASSIGNED` / `IN_PROGRESS`→`DIAGNOSING`,
`CLOSED`→`ARCHIVED`); because the operand's fold depends on the *object's* type (a CASE `OPEN` must still equal a rule's
`OPEN`), the status leaf is `OR[AND[type = INCIDENT, status = fold(v)], AND[type != INCIDENT, status = v]]`. A single static
folded leaf would have stopped a rule saying `OPEN` from reaching a non-incident `OPEN`.

**Parity.** `TagRuleFilterParityTest` (7) was written first against the old matcher over a six-object corpus (three
Incident lifecycles, a CASE, a TASK with a lower-case status, null priority / severity / category): selections were
identical before and after. `TagRuleTest`, `CaseRuleEvalJobTest`, `ObjectService*`, `ControlApiTagRoutesTest`,
`ControlApiCaseRuleTest`, `PerEntityAlertObjectsTest`, `OpsJobTypesRunInAServiceTest` unchanged and green.

**One deliberate difference (pinned):** `category` was a case-sensitive `startsWith`; the tree's `startsWith` is always
case-insensitive (`ignoreCase` is a no-op there), so a rule `pipeline` now also matches category `Pipeline / Ingest`. The
other criteria keep their semantics (priority / severity stay exact; `q` stays a substring over title + " " + description).
**Not done:** letting an author write a `when` tree on a Tag / Case Rule directly (the builder would need the context fields
as a field catalogue) — a follow-up, not needed to retire the duplicate matcher.

### Decision Kernel step 4 as built (2026-10-07) — Notification Rule match onto the tree

**Decision: `eventType` / `minLevel` stay the authoring form (sugar).** No shipped TOON authors a Notification Rule (the built-ins are
`NotificationRules.defaults()`; authored ones arrive via `/notifications/rules*` and the Notification Center form), so nothing was
migrated. `NotificationRule.tree()` expands to `AND[type = eventType (ignoreCase), levelRank >= minLevel.ordinal()]`;
`matches` runs `ConditionTree.matched(tree(), [matchContext(e)])`. The context builder `matchContext` supplies `type` and
`levelRank` (the `EventLevel` ordinal, so a minimum is a numeric floor). The hand-coded `equalsIgnoreCase` / `atLeast` matcher is
gone; template rendering (`context(e)`) is untouched.

**Parity.** `NotificationRuleMatchParityTest` (6) was written first against the old matcher over a ten-event corpus (all five levels,
a case variant, a longer type, `_` vs `.`, `%`, a quote / semicolon / comment type): identical fire-sets before and after.
`NotificationRuleTest`, `NotificationRulesTest`, `NotificationServiceTest`, `SecurityTriggersTest`, `ControlApiNotificationRulesTest` green.

**One deliberate difference (pinned):** a blank `eventType` fires nothing (an empty leaf reads as "no constraint" on the tree, so
`matches` guards it; the old matcher fired only on a blank-typed event, which cannot occur). `fromMap` already refuses blank.
Residual: `=` ignore-case folds with `toLowerCase(Locale.ROOT)` where the old matcher used `equalsIgnoreCase` (differs only for
exotic Unicode case pairs).

### Decision Kernel step 5 — Risk factor `filters` (FINDINGS; superseded by "step 5 / step 6 as built" below)

Stopped here on purpose. Risk `filters` are not an in-memory single-row matcher: `RiskScoreModel.Factor.filters` is a list of
`{field, op, value}` handed to the **shared BI query contract** `MeasureCompiler` (`Spec.filters`, `filterTerm`), which builds a
grouped `WHERE` for the value query and the evidence query alike (`RiskScoreEvaluator`, and the S3 `forEntity` preview which
appends a `key = entity` term). The same `MeasureCompiler.Filter` serves `BiRoutes` and every widget `QuerySpec`, which the UI mirrors 1:1.
Moving Risk onto the tree therefore means giving `MeasureCompiler.Spec` a `when` tree rendered through `ConditionSql.predicate` —
a change to a BI contract with other consumers — or Risk building its own `WHERE` beside `MeasureCompiler`, which is the opposite
of consolidation. Vocabulary gaps to settle first: `like` (SQL pattern) has no tree operator; `in` takes a JSON list in
`MeasureCompiler` but a comma string in the tree; op aliases (`eq` `ne` `gt` `gte` `lt` `lte` `==` `<>`) and `notNull`; literals are
typed from the Java value (`Number` / `Boolean` verbatim) where the tree types from the column. Authoring survey: no shipped TOON
sets `filters`; tests (`RiskCorpus`, `RiskScoreModelTest`, `ControlApiRiskScore*Test`) and the UI (`risk-score-form.ts`
`RISK_FILTER_OPS`, `risk-score-factors.component.ts`) author the flat form. **Recommendation:** do it as part of making
`MeasureCompiler` filters a tree (the BI query contract), with the flat form kept as sugar, in one change covering BiRoutes
and the UI QuerySpec — not as a Risk-only edit. Step 6 (Escalation match) was not started; note
`EscalationRule` itself holds no matcher (`on: breach | age`, `afterMinutes`, `target`) — the match lives in the `ObjectService`
sweep, and `inspecto-workflow` would need the tree on its classpath.

### Decision Kernel step 5 / step 6 as built (2026-10-07) — `MODULE-REORG-P7-KERNEL`

**Step 5 — Risk filters, ADDITIVE (operator-approved 2026-10-07; `b9c3271fc`).** The flat BI `filters` were NOT converted:
`ConditionSql` types a literal from its operand text and wraps columns in `CAST`/`TRY_CAST`, which defeats Parquet pushdown and
changes semantics. Instead `MeasureCompiler.Spec` gained an optional `when` (a `ConditionTree` group, secondary 7-arg constructor so
every existing caller is source-compatible); `parse` reads `when` and runs `requireGroupRoot` + `validate`; `compile` appends
`AND ConditionSql.predicate(when)` after the untouched flat terms (an empty/absent tree changes no SQL). `BiRoutes`'
bound-query rewrap carries `when` through. `RiskScoreModel.Factor` gained `when` (key added to `FACTOR_KEYS`), threaded through
`specBody`; `forEntity` still appends the flat `{key,=,entity}` filter and keeps `when`; `referencedColumns` walks the tree
(`field` and `valueField`, groups `items`/`conditions`, any depth) for the Schema check. Nothing in the SPA changed.
- **Parity corpus** (`RiskScoreWhenParityTest`, 10 tests, seeded Parquet through the real executor in UTC; `MeasureCompilerWhenTest`,
  6): identical entity ids, counts AND Risk scores for flat vs `when` on `=` / `!=` with NULL and `''` cells, DATE and TIMESTAMPTZ
  `>=`, and hostile values (`'`, `\`, `;--`, `_`, an injection string).
- **Deliberate differences, PINNED (do not "fix" either side):** (1) typing by OPERAND TEXT - a `when` operand `007` looks numeric so
  it compares `TRY_CAST(col AS DOUBLE)` and matches `7`, `07`, `007`; flat `007` is an exact string; (2) `''` is null only in `when`
  (`isNull` sees `''`, flat `IS NULL` does not); (3) flat `like` passes `%`/`_` as wildcards, `when contains` escapes them; (4) flat
  `in` takes a list so a value may hold a comma, `when in` splits one string on commas.
- **Hostile input:** a hostile column name in `when` is quoted by `SqlIdent.q` (binder error, no injection; the flat path refuses it at
  parse); values are escaped by `ConditionSql`; a bare-leaf, non-object or lookahead-regex `when` is refused at save. No string-building
  was widened (DECISION-RULE-SQL-GUARD-1 stays open).
- **SqlGuard finding:** the Risk SQL path is NOT `SqlGuard`-checked. `RiskScoreEvaluator` compiles with `MeasureCompiler` and calls
  `QueryExecutor.run(Request, SqlSandboxPolicy)`; the sandbox policy is the boundary and the compiler emits only quoted identifiers and
  escaped literals. (`BiRoutes` guards its route-built text; Risk does not.) Unchanged by this step; flagged for the guard row.
- **Follow-up (UI, DONE 2026-10-07):** see "Decision Kernel step 5 / step 6 SPA follow-up" below; a factor edited in the SPA no longer drops `when`.

**Step 6 — Escalation match (`952538fe9`).** `EscalationRule` (inspecto-workflow, which must not depend on the engine) gained an
OPAQUE `Map<String,Object> when` and a pure-map `matchTree()` that folds the `priority` sugar into an AND leaf
(`priority = P`, `ignoreCase`) beside `when`; `null` when the rule narrows nothing. Validation with `ConditionTree.requireGroupRoot`
sits where the engine is reachable: `GovernanceRegistry` (load: an invalid rule is not served / last valid stays) and
`ComponentRoutes` (write: 422). `ObjectService.escalate` replaces the hand-written priority check with
`ConditionTree.matched(matchTree, [escalationContext(cur, now)])`; the trigger marker, the `rule@marker` fire-once ledger and
`sweepIncidentSla` stay bespoke. Context fields: `type`, `status` (workflow state upper-cased, NOT folded like TagRule's), `priority`
(trimmed), `severity`, `category`, `assignee`, `ageMinutes`, `minutesToDue`, `resolutionBreached`, `responseBreached`, `escalated`
(0/1). `minutesToDue` is `Integer.MAX_VALUE` with no deadline: the Condition Language reads a blank cell as 0 (JS `Number('')`), so
an empty value would have matched `minutesToDue <= 10` as due NOW (found by the test, pinned). Parity: priority-only rules keep their
exact semantics (case variants, padded value, null priority never matches, equality not prefix) - `GovernanceSweepTest` (17) +
`ObjectServiceTest` (35). UI follow-up: `governance-model.ts` rebuilds a rule from modeled fields and would drop `when` on a SPA save.

**Decision Kernel step 5 / step 6 SPA follow-up (2026-10-07).** Both silent-loss defects were proven red by specs, then fixed.
- **Risk factor.** `RiskFactorDraft` gained `when` (a deep-copied `ConditionGroup`) and `extra` (every factor key the form does not model);
  `toRiskScoreContent` writes `extra` first, then the modelled keys, then `when` only while the tree has items (an emptied tree is dropped).
  A stored `when` the editor cannot model (no `kind: 'group'` + `items`, e.g. TOON-authored `conditions`) is kept VERBATIM in `extra` and shown
  as a read-only note - never reinterpreted. `risk-score-factors.component` mounts `inspecto-query-condition-group` in a per-factor disclosure
  ("Advanced filter (condition tree)", `aria-expanded`/`aria-controls`, open on load when a tree exists) with help text on flat filters vs `when`;
  the field list is the factor Dataset's columns WITH types (`columnMetaFor`, the same read the flat pickers use). `validateGroup`
  (`condition-rules.ts`) is the per-condition `validateCondition` walked over a tree; its messages render in a `role="alert"` list the
  disclosure button points at (`aria-describedby`), and `validate()` opens a collapsed section holding an invalid tree. The server stays the gate.
- **Escalation Rule.** `escalationRuleContent(draft, stored)` carries every key it does not model forward from the stored rule (generic, not
  `when`-only); a modelled key the author cleared stays cleared; `draft.when` undefined keeps the stored tree, an empty group removes it.
  The Settings > Incident governance Add Escalation Rule form (the only per-rule field editor; stored rules are list/delete only today) has the
  same disclosure ("Advanced match (condition tree)") over `ESCALATION_CONTEXT_COLUMNS` (type, status, priority, severity, category, assignee,
  ageMinutes, minutesToDue, resolutionBreached, responseBreached, escalated - strings and 0/1 or numeric cells).
- **Deliberately left:** there is no edit-a-stored-Escalation-Rule UI, so the preserve path (`stored` argument) has no SPA caller yet; the
  condition tree's text input is raw (a `when` operand is typed by its text server-side, see the pinned parity differences above). No live
  browser drive was done.

**Known flake - `SafetyPolicyFilesTest.aRunPinsItsPolicyAtPlanTimeAndTheNextRunSeesTheTightenedFile:191` (fixed `5556bf0b3`).** Not
reproducible in three quiet runs (15/15 each). Root cause is a real test-timing assumption: `SafetyPolicyFiles.load` caches a parsed
file by `(mtime millis, size)`, and the test rewrites `max_threads: 4` to `max_threads: 1` - the SAME byte length - so a rewrite landing
in the same mtime tick read as "unchanged" and returned the stale 4 ("expected 1 but was 4"). Not caused by a concurrent `ng test`; any
load makes the tick collision likelier. Fix, assertion untouched: the test's `write` helper stamps every rewrite strictly later than the
file it replaces. The production cache key is unchanged (a same-size same-millisecond hand edit is a theoretical stale read there too;
not worth a content hash).

### Target picture
![Inspecto target module architecture](assets/module-target-architecture.svg)
- Offerings (P6) become: one tier + any add-ons + any solution packs.
- ⚠ Commercial judgement is the operator's: this map applies the five tests to what the code and the edition
  matrix show; it does not rest on market or pricing data.

## 9. Risks and traps (from this repo's own history)

- 🔴 **A guard that goes vacuous** — e.g. `ConfigWriteFunnelTest` finds holds with the regex
  `PendingChanges\.hold\w*\(`; a rename makes it match nothing and pass. Every moved symbol needs the guards
  that grep for it re-pointed and proved red by hand (the auto-mode classifier refuses an agent editing a guard
  to prove it red — queue the mutations for the operator).
- 🔴 **A green full reactor is not green CI** — route-gating, `openapi-v1.json`, authgate coverage and the
  `guards` job fire only in the reactor or CI. Run every `ci.yml` no-build guard before a push.
- 🔴 **Packaging runtime** — a module needing a `jdk.*` module broke every ingest in the zip once; prove each
  Offering on `runtime/bin/java`, not the build JVM.
- 🔴 **Silent loss on save** — a rebuild-from-modeled-state write dropped every key it did not model. P4 must
  test that config naming an absent module survives a save byte-for-byte in meaning.
- 🔴 **Stale sibling jar** — `-pl` without `-am` tests a stale jar of a moved module; never `-Dtest=A+B`.
- ⚠ **Shared checkout** — each phase in a worktree under `.claude/worktrees/` (never `%TEMP%`); merge, never
  overwrite, `SESSION_STATUS.local.md`.
- ⚠ **The `job` package** imports every feature package (73 imports of `pipeline`, 30 of `signal`, 23 of
  `consignment`, 16 of `query`, 15 of `notify`) — touch it only for Job types whose feature is Optional.

## 10. Not verified

Re-checked by me: the split packages, the hard-coded route list, `EventType` being open, `CapabilityManifest`
entries feeding `Roles.KNOWN_CAPABILITIES`, `ApprovalPolicy.GOVERNABLE`, the single `openapi-v1.json`, and
`connectors`' third-party dependencies. Subagent-measured and not re-derived: module and package LOC, the
feature Job type LOC inside `job` (estimate), ~415 route registrations (grep estimate), the UI mapping (from
service and nav names), the bundle sizes. Still to read before P1: `docs/EDITIONS.md` and
`docs/FEATURE_INVENTORY.md` in full, which store families exist per module, and how Space bundles treat
unknown config kinds today.
