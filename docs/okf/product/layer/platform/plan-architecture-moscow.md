# Architecture work — MoSCoW analysis (2026-11-12)

> **Status:** analysis of 2026-11-12, operator-requested. A dated ranking, not a list of open work: the
> one list is [`docs/BACKLOG.md`](../../../../BACKLOG.md), and every item below is named by its board row.
> Items marked *(no row)* were found in the docs and are **not verified in the code**; the board row
> `ARCH-MOSCOW-UNTRACKED-1` owns checking each one and either filing or refusing it.
> Re-rank when the next MAJOR is tagged or a first customer deployment is scheduled.

## 1. What is ranked, and against what

**Scope.** Open gaps, risks, limits and deferred decisions in how Inspecto is built: module structure,
contracts and the API, storage and the data plane, scale-out and recovery, security, Editions and
packaging, the engine, the UI architecture, build and test, and the architecture docs. Business
capabilities are out of scope unless the row is really about a seam.

**Horizon.** Nothing is in production after `3.x`; trunk is `4.0.0-SNAPSHOT` and the next MAJOR has not
been tagged. So the question each item is ranked against is: **what must be true of the architecture
before `4.0.0` is tagged and installed for a first customer?**

| Class | Test applied |
|---|---|
| **Must** | Something is red or wrong today, or data can be lost or exposed, or a claim the release makes cannot be shown true without it. |
| **Should** | Clear value and no external blocker, or it can only land with a MAJOR, but the release is still honest without it. |
| **Could** | Worth doing when the area is next touched or when a customer asks; nothing depends on it. |
| **Won't (this time)** | Already refused or postponed with a stated reason. Each has a reopen trigger; none is forgotten. |

**Where the facts come from.** The six system-set pages ([architecture](architecture.md),
[modules](architecture-modules.md), [Editions](architecture-editions.md),
[Platform Services](architecture-platform-services.md), [UI](../product/architecture.md),
[Studio BI](../product/architecture-studio-bi.md)), the board, the active plans in `docs/superpower/`,
[API stability](../../module/api/plan-api-stability.md), the
[maturity assessment](../product/plan-maturity-assessment.md) and `compliance/controls-matrix.md`.
Module-level architecture pages were not read for this ranking, so observability has no item of its own.

## 2. Must — 8 items

| # | Item | What is open | Why it is a Must | Waits on |
|---|---|---|---|---|
| M1 | `IMPORT-LOADER-CASE-TOON-1` and `SPACE-TEST-SUITE-RED-1` | Three tests of `inspecto-processor` are red on master, all around the `space.test` Job Type of SE-6a: a loader reads the fixed name `case.toon`, which is neither reserved nor allow-listed; `space.test` reports `classpath` where a test expects `builtin`; and one test fails only inside the full suite. | Master is red, and an import could plant the file. | The SE-6a owner: reserve or allow, and which provenance is right. |
| M2 | `ARCH-MOSCOW-UNTRACKED-1` item 1 *(no row of its own)* | Two records of 2026-11-12 disagree on whether master is green. The product-assembly plan records a green full Advanced reactor gate over `6a7d8c20b` plus PA-9b. A run of the whole `inspecto-processor` suite on `fe87aa121` (2,733 tests) had the three failures of M1, and two of them fail alone on `e45b25f9d`. **Settled 2026-11-12:** one full Advanced reactor gate on `f0aa137da` built 57 of 57 modules and ran 12,188 tests with 3 failures, 0 errors and 149 skipped (94 of them Postgres-backed). The failures are exactly the three of M1. | Master is red by M1 only. The gate has to be repeated daily, as the lane workflow says, or this answer goes stale. | Done; M1 remains. |
| M3 | `JOB-SIGNAL-OVERLAP-SKIP-1` | An `on_signal` Job loses the last landing when a signal arrives while it runs. | A Dataset missed a month of data until another commit; three packs carry a cron workaround. | Nothing. |
| M4 | Release notes for the next MAJOR (§2 of the board) | Breaking route removals are typed `docs:` in git, so a `feat!:` scan misses them; every `since = "4.0.0"` type is unpublished. | The release notes are generated from commit types; they would be wrong. | The MAJOR tag. |
| M5 | `ARCH-MOSCOW-UNTRACKED-1` item 2 *(no row of its own)* and `MODULE-REORG-P3-THIN-JARS` | `release.yml` signing, SBOM and scan have never run on a tag; the keystore, secrets and TSA URL are owed. | Supply-chain claims are unproven and jars would ship unsigned. | Operator: keys and a release date. |
| M6 | `ARCH-MOSCOW-UNTRACKED-1` item 3 *(no row of its own)* | Columns classed `CONFIDENTIAL` (for example AML `account_id`, `wallet_id`, `msisdn`) sit outside `ColumnClassification.SENSITIVE`, so they are not masked. **Confirmed in code and fixed for the shipped templates 2026-11-14** (`TEMPLATE-CONFIDENTIAL-UNMASKED-1`: eight identifier columns of `aml`, `mobile-money` and `telco-fraud` reclassified). Still open: nothing warns about a class name that masks nothing, and the two masking vocabularies (`ColumnClassification.SENSITIVE` and the classes masked Entity Types claim) share only `MSISDN`, `IMSI` and `ACCOUNT` (`CLASSIFICATION-UNMASKED-CLASS-1`), and the `la-showcase` feed schemas (`LA-SHOWCASE-FEED-CLASSIFICATION-1`). | Exposure of personal data; the maturity assessment calls it a due-diligence finding and sizes it in days. | Verify in code first. |
| M7 | `ASSURE-OPERABILITY-1`, Deployment topology live validation, `DEPLOY-SERVICE-WRAPPER-1` | No recorded restore drill, RPO and RTO unmeasured, the kill -9 and reboot acceptance of the service wrapper unrun, the chart never installed. | The wrapper is the recovery mechanism; recovery that was never exercised is a claim, not a property. | Operator: targets and host access. |
| M8 | `JOB-SNAPSHOT-SWAP-READER-RACE-1` | `SqlTemplateJob.swapIn` deletes the old snapshot while a running reader still reads it. | A dependent Job fails all its retries (seen in `la-banking`). | Nothing. |

M2 is done (2026-11-12). M3 is done (2026-11-13: a coalesced Signal follow-up now waits for the Run in flight).
Of the remaining six, M8 needs no decision from anyone and can start now.

## 3. Should — 16 items

| # | Item | What is open | Why not a Must |
|---|---|---|---|
| S1 | `ARCH-MOSCOW-UNTRACKED-1` item 4 *(no row of its own)* | Six config types (`enrichment`, `job`, `schema`, `expectation`, `widget`, `dashboard`) accept unknown keys. **Checked in code 2026-11-14: stale.** `enrichment` has a census in `AcceptedConfigKeys`; `widget`, `dashboard` and `expectation` are refused at the top level by `ComponentRoutes.refuseUnknownComponentKeys`. Only `schema` and `job` remain open, each for a recorded reason (`spec-config-safety.md`, "The accepted-key census"). | Fail-open by omission, against the fail-closed design, but nothing is known to exploit it. First to promote if the check confirms it. |
| S2 | ~~`PUBLIC-API-SURFACE-1`~~ **shipped 2026-11-12** | ~~`JobTypeProvider`, `ConsequenceProvider` and `CollectorConnector` lack `@PublicApi`; no guard diffs marked signatures between releases.~~ All six extension points and the types they expose are marked; `tools/check-public-api.mjs` holds the marked signatures to `platform/inspecto-api/public-api.txt`. | Built on operator request before a partner asked. |
| S3 | Row 15 — ELT Phase 6 deletion half | The legacy flat read path stays beside the graph lane. | Two read paths are a cost, not a fault; needs an operator call, then rides the MAJOR. |
| S4 | X-Actor full removal · `batch_id` rename trio · X5 and the StepInfo envelope | Breaking cleanups that can only land with a MAJOR. | Missing the MAJOR means carrying them to the next one. |
| S5 | `ENTITLEMENT-SPI-NARROWING-1` | A signed entitlement closes routes, features and Job Types of a left-out module, but not its Consequences, Step kinds, Collector connectors, maintenance tasks or boot hooks. | A Decision Rule can still fire a Consequence of a module the customer did not buy; no customer build is narrowed yet. |
| S6 | Platform Services Stage 2 | `DatasetAccess` pins per call; a Run holds no snapshot. | Two reads in one Run may see different file lists; no failure has been reported. |
| S7 | `SCALEOUT-OBJSTORE-DATABASE-DIR-1` | Object-store `dirs.database` is written by ingest; sinks, References and the readers still refuse it. | Phase C of scale-out stays partial; single-Pod installs are unaffected. |
| S8 | `DATA-TIERS-1` · `PRODUCT-ASSEMBLY-1` with `CONTAINER-AIRGAP-PROOFS-1` | The data layer by Edition is unfinished; the Kubernetes install and the air-gapped bundle are unproven. | In flight under their own plans; a Basic or Standard zip install does not depend on them. |
| S9 | `SPACE-LINK-CENSUS-1` (then `SPACE-EVOLUTION-1`, `PIPELINE-LINK-GAPS-1`) | Link keys are hard-coded in six checks, so rename and impact scans miss links. | LC-1 has shipped (the board row's text lags); the rest is open and nothing is red. |
| S10 | `LA-PERF-N10-1` | About 3,550 native threads and 1.4 GB at idle with 48 DuckDB files; no thread cap per instance. | Suspected, not proven, as the cause of slow projections. Measure before changing. ✅ Shipped 2026-11-12: the threads were DuckDB 2.0's `async_threads` pool, now capped (5,332 → 1,158 threads, p50 579 → 450 ms); the projection tail was not caused by it (`la-api/spec-server-profile.md`). |
| S11 | `ARCH-MOSCOW-UNTRACKED-1` item 6 *(no row of its own)* | `/metrics` and `/health/details` are unauthenticated by design and fenced only by a proxy; the preflight, proxy and TLS templates, off-site backup, verify and upgrade scripts are unbuilt. | Safe behind a correctly set proxy; the scripts make that the default, not an instruction. |
| S12 | `ARCH-MOSCOW-UNTRACKED-1` item 7 *(no row of its own)* | Retention and legal hold cover Incidents only. | The Advanced brochure promises legal hold; either build it or narrow the claim. |
| S13 | `DEMO-BAKE-WAL-RACE-1` | Closing a busy Space gives up after 10 s and leaves `.wal` files. | Seen while baking demo images; the row itself says it is open for a production stop. |
| S14 | `ARCH-MOSCOW-UNTRACKED-1` item 8 *(no row of its own)* for the two lint halves; `LA-PERF-N4-1` for the layouts | UI: "the core never imports a feature" is not lint-enforced, and 48 feature-to-feature imports rest on review. Link Analysis graph layouts run on the main thread (2.3 to 3.6 s at 767 nodes). | The convention holds today; the freeze is a usability cost. The lint is small. |
| S15 | `ARCH-DOC-CONTRADICTIONS-1` | Fourteen places (one settled since) where current docs contradict each other (thin-jar count, shading, Postgres multi-user state, D15, old Edition names and more). | Docs, not behaviour; but a reader acting on the wrong page gets the wrong answer. |
| S16 | `DOCS-AGENT-WRITTEN-REVIEW-1` | A work list of wrong claims in plan and admin-guide pages remains; module architecture pages were not re-checked. | Same reason as S15; done a module at a time when next touched. |

## 4. Could — 14 items

| # | Item | What is open |
|---|---|---|
| C1 | `ARCH-MOSCOW-UNTRACKED-1` item 9 *(no row of its own)* | A 13-package cycle inside `inspecto-engine` and a 5-package cycle in the composition root. Nothing is blocked; it only limits dividing the engine further. |
| C2 | `ARCH-MOSCOW-UNTRACKED-1` item 10 *(no row of its own)* | UI: TypeScript `strict` is off (the Angular template checks are on); the app runs Zone.js with every application component `OnPush`. |
| C3 | `ARCH-MOSCOW-UNTRACKED-1` item 11 *(no row of its own)* | OpenAPI quality: 430 of 559 operations have skeleton summaries. Only `backend-smoke.spec.ts` covers the operations panes end to end. |
| C4 | `MODULE-REORG-P4-3` | A Run already started is not interrupted when its module is switched off. No work item until a switchable module owns a subscription. |
| C5 | `MODULE-REORG-P7-INCIDENTS` | Delete `AlertMigration` one release after the Alert store ships. |
| C6 | `ANALYTICS-CORE-1` (Could batch) | Closed 2026-11-13: decisions D-AN10 to D-AN35 operator-confirmed 2026-11-12, the last residuals built. |
| C7 | Consignment addressing | The ingest-side accessor has no consumer; `retire_superseded` is off by default. |
| C8 | Branch-aware executor residuals | The pre-fetch intake cap needs a listing-with-sizes connector seam. |
| C9 | `REALTIME-DECISIONING-1` | The engine is batch; windowed Alert Rules on a `trigger: stream` Pipeline are in progress (PA-9a and PA-9b built, the rest designed). Large, and worth it only for card-authorisation or pre-call-blocking bids. |
| C10 | `LA-INDEX-SCALE-MEASURE-1` | Link index behaviour is measured to about 10^8, not 10^9. |
| C11 | `JOB-LOADER-SAVED-VIEW-SUFFIX-1` · `UI-SCHEMA-FORM-EDITOR-1` | A WARN on every boot (fixed 2026-11-13: the boot scan skips `registry/`); the last file on the forms lint allow-list. |
| C12 | `DUCKDB-2-WINDOW-SELF-JOIN-UPSTREAM-1` · `CODEGRAPH-AFFECTED-UNUSABLE-1` · `AGT-5` | Three upstream reports or seams owed outside this repo. |
| C13 | `LA-SHOWCASE-RELEASE-RESIDUALS-1` · `NATIVE-LICENCE-LINUX-MACOS-1` | The Linux product zip; two licence calls on native libraries. |
| C14 | `DOCS-FUNCTIONALITY-HUBS-1` | Whether `functionality/` items get hubs. |

## 5. Won't (this time) — refused or postponed, each with its reopen trigger

| Item | Decision | Reopens when |
|---|---|---|
| Known package cycles (`ARCH-OPS-SCC`, `ARCH-F-CARVEOUT`, `etl` and `etl.unpack`, `agent.kernel.*`) and the `CollectorService` decomposition | Refused, board §6: an 85-file ripple for no extraction it would unblock. | A module extraction is blocked by one of them. |
| `inspecto-query`, `-job`, `-enrich` extraction | Refused, board §6. `enrich` is the one clean candidate. | On explicit request. |
| Shared base for the store pairs, a BOM, DuckDB connection reuse | Refused, board §6: a warm open is 24 ms. | The 7th store pair, an external consumer, or a contrary measurement. |
| JPMS, an RBAC capability SPI, OpenAPI merged at boot | Declined in the module reorganisation decisions. | Not stated. |
| Vault or KMS provider (`SEC-07`) | Refused, board §6. | A client policy requires it. |
| Storage shapes: Kafka as the data path, a whole Space on S3, a database per Space, PgBouncer, a rollup cache | Declined in the data-plane plan. | Not stated. |
| Active/active, ZooKeeper or etcd, a cluster engine, the Kubernetes Lease API | Declined in the scale-out plan. | Not stated. |
| Horizontal scale-out beyond what is built: `SCALEOUT-HELM-NPOD-1`, `SCALEOUT-U1-REMOTE-ACQUIRE-1`, `SCALEOUT-LIVE-HALVES-1`, `SPACES-FROM-PARTITION-MAP-1`, distributed Consignment execution | Postponed by the operator. `INGEST-MULTIFILE-BATCH-REPLAN-DUP-1` (a duplicate output after failover) stays acceptable only while that path is kept off. | A multi-Pod deployment is scheduled. Before it: verify `/auth/refresh` statelessness and SPA chunk skew on a rolling update. |
| Postgres multi-user beyond the pool and schema-per-Space; `TEST-ENV-PG-ASN-UNRUN-1` (the Postgres-backed tests and five `asn-*` modules cannot run on the shared profile; 36 processor tests skip here); `LA-INVESTIGATION-STORE-DESIGN-1` with `LA-INVESTIGATION-DELETE-PG-1` | Postponed by the operator: the next release ships without Postgres. The cost is stated, not hidden: a green reactor does not cover the Postgres stores. | The Postgres release is scheduled. The test environment comes first. |
| Compliance certification (NFR-7, SOC 2 Type II) | Externally gated: auditor, pen test, an observation window not started. Months. | An engagement starts. |
| Masking edges, `PATH-2`, Decision Kernel step 7 | Refused or declined. Step 7 leaves the condition tree failing open where `Conditions` fails closed. | Not stated; step 7 deserves a second look if Access Policy text is ever compiled. |
| Mid-write interrupt, fetch-lane FIFO, startup backfill, `PKG-5`, connector sidecar gating, publishing eoiagent to a registry | Refused, board §6. | Each row states its own trigger. |

## 6. What this ranking says

1. **Master is red by three tests, and only those.** The full gate of 2026-11-12 on `f0aa137da` (M2) showed
   that. M1 comes before everything else: decide the red tests, then keep the full gate as the daily gate
   the lane workflow already calls for.
2. **The release path is the largest unproven piece.** M4, M5 and M7 are all "it exists and has never
   run": the release workflow, the recovery drill, the service wrapper acceptance. None needs design; each
   needs an operator input and one execution.
3. **Two correctness defects in Jobs (M3, M8) are the only Musts that are plain engineering.**
4. **Structure is in good shape.** No Must is about module boundaries: the split-package count is 0, the
   remaining cycles are inside one module each, and the refusals in §5 have held.
5. **Ten items have no board row.** They come from docs, not code. `ARCH-MOSCOW-UNTRACKED-1` checks each
   and files or refuses it; M6 (masking of `CONFIDENTIAL` columns) and S1 (unknown config keys) first.

## 7. Limits of this analysis

- The facts were gathered from documents and the board on 2026-11-12, by a research pass that did not open
  the code. "Not on the board" means no row was found, not that the gap is confirmed.
- Sizes are given only where a document states one.
- Several source documents contradict each other (S15); where they did, the board and the code-checked
  system-set pages were preferred.
- Hold states of `REALTIME-DECISIONING-1` and `SCALEOUT-U1-REMOTE-ACQUIRE-1` were not confirmed.
