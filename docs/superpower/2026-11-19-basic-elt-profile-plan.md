# Basic onboarding as a locked profile of ELT (`BASIC-ELT-PROFILE-1`)

> **Status: APPROVED to start 2026-11-19 (operator: "write the plan and go parallel").** Phase 0 (verify) and UI-0
> (move Add data into the core UI library) run now, in parallel lanes. The build phases (UI-1, BE-1 … BE-4) start
> after Phase 0 confirms or corrects their rows below. Shipped predecessor: `BASIC-ONBOARD-1`
> (`archived-documents/plans-archive/basic-onboard-plan.md`).

## 1. The goal in the operator's words

> *"We had a plan for basic pipelining. This must be a subset of the powerful pipelining for data acquisition we
> have. It must reuse both backend and frontend components wherever applicable."* (2026-11-19)

**Rule this plan holds every change to:** Basic is the ELT path with options locked, never a second implementation.
A capability Basic has, a Standard Pipeline has through the same code; what makes it Basic is which schemes,
triggers, Steps and screens the Edition allows — enforced in one place (the config-safety validator and Edition
features), not by separate code.

## 2. What already reuses the ELT path (verified 2026-11-19)

- **The run.** Every Basic import runs a registered Pipeline: `OnboardImport.Job.runChecked` calls
  `CollectorService.runPipeline(id)`, so ingest, commit, quarantine, duplicate markers and the status ledgers are the
  engine's own.
- **The config.** Add data writes an ordinary `<id>_pipeline.toon` (`origin: import`, `trigger: manual`, standard
  `dirs`, `processing`, `csv` blocks) and `<id>_schema.toon` (raw fields + mapping) that the engine loads like any
  other Pipeline (`OnboardImport.Plan.pipeline` / `schema`). An upgrade to Standard shows it as an editable Pipeline.

## 3. What runs beside the ELT path (to verify in Phase 0)

Found 2026-11-19 by reading the classes and their imports; **each row is a hypothesis until Phase 0 confirms it**.

### 3.1 Backend (`inspecto/src/main/java/com/gamma/control/`, about 6,700 lines)

| # | Basic piece | Lines | ELT part it parallels | Target |
|---|---|---|---|---|
| B1 | `InboxWatch` — its own 30 s poller thread | 367 | the Collector poll cycle (discovery, stability gate, fingerprint ledger; `module/elt/spec-acquisition.md` Part 2) and the scheduler | a `local` Collector on the normal cycle; Basic allows only that scheme at a fixed 30 s |
| B2 | `ImportFollowOn` SPI (LA's link index) | — | post-sync Step chains / Job chaining (`module/elt/spec-pipeline-execution.md`) | a registered post-sync Step type; LA's index runs the same way on every Edition |
| B3 | `DatasetArrivals` — duplicate refusal by content hash, arrivals list | 415 | the acquisition fingerprint ledger, `MarkerManager` | a Pipeline option in the engine (`processing.duplicate_check` by content) |
| B4 | `DatasetPeriod` — *Replace the period* | 197 | Consignment revisions (`module/elt/spec-consignments.md` §A.4) | an arrival mode on the Pipeline, executed by the engine |
| B5 | `DatasetNewColumns` — keep / leave a new column | 283 | schema evolution in the Pipeline schema | a schema option the engine applies |
| B6 | `DatasetCompaction` — compaction and retention | 705 | the maintenance Job lane | a maintenance Job the import arms |
| B7 | `OnboardDraft` — its own format sniff (`sniff_csv`) | 1,137 | the Parser registry and parse preview; the grammar sniff the editor uses | one sniff service both use |
| B8 | `PostgresSnapshot` — DuckDB `postgres` extension | 481 | the `db` Collector (`DbExportConnector`) | a `postgres` connector scheme in the acquisition SPI (still DuckDB-based: no driver on Basic, typed Parquet), so an upgrade shows a normal Collector; the `db` Collector stays for incremental loads |

### 3.2 Frontend

Add data is about 9,400 lines in `inspecto-ui/projects/link-analysis/src/add-data/`, though it is a platform feature
(placed by the console `/add-data` and by LA). It reuses generic core widgets (option picker, alert, data table) and
none of the ELT authoring components:

| # | Add data part | Core component it parallels |
|---|---|---|
| U1 | column review (names, types, roles, Entity Types) | `inspecto/schema/schema-fields-editor`, `schema-metadata-grid` |
| U2 | `postgres-table` (connect, test) | `inspecto/connections/connection-form.dialog`, `api/connection-probe.service` |
| U3 | format detection on upload | `inspecto/grammar/grammar-editor`, `grammar/parsing-sniff` |
| U4 | `dataset-feed` (watch this folder) | `inspecto/collector/collector-config.component` |

## 4. Phases

| Phase | Scope | Depends on | Size |
|---|---|---|---|
| **P0-BE verify** | Each of B1–B8: confirm or correct the parallel, list what the ELT part lacks for Basic's behaviour, the risk of moving, and the tests that pin today's behaviour. Output: §5 below. Read-only. | — | S |
| **P0-UI verify** | Each of U1–U4: can the core component host a simplified Basic mode (inputs, outputs, what it lacks), or must it be split? Output: §5. Read-only. | — | S |
| **UI-0 move** | Move `projects/link-analysis/src/add-data/` into the core UI library (`@inspecto/core`), keep LA's placement working by import, no behaviour change. Prerequisite for every UI row. | — | M |
| **UI-1 reuse** | U1–U4 one at a time on the core components in a Basic mode. | UI-0, P0-UI | L |
| **BE-1 poller + follow-on** | B1, B2. | P0-BE | M |
| **BE-2 arrival rules** | B3–B6 as engine Pipeline options; `OnboardImport` shrinks to the review screen's draft and commit. Standard Pipelines gain the same rules. | P0-BE, BE-1 | L |
| **BE-3 sniff** | B7. | P0-BE | S |
| **BE-4 Postgres scheme** | B8. | P0-BE | M |
| **Gate** | Basic's locks (schemes, triggers, Steps, screens) in the config-safety validator and Edition features; a test that a Basic Space refuses each Standard-only option. | BE-1…BE-4 | S |

Parallel now: P0-BE, P0-UI, UI-0. Each build phase is its own lane, integrated and pushed one at a time.

## 5. Phase 0 findings

*(filled in when the verify lanes report)*

## 6. Not in scope

- New Basic capabilities (transformation, user schedules, other databases) — `BASIC-ONBOARD-1` §7 stands.
- Renaming the build module `inspecto-etl` (GLOSSARY §13 debt).

## References

- `archived-documents/plans-archive/basic-onboard-plan.md` (`BASIC-ONBOARD-1`, shipped 2026-11-05)
- `okf/product/module/processor/api-onboarding-authoring.md`, `okf/product/module/la-core/architecture-data-onboarding.md` §6.20
- `okf/product/module/elt/spec-acquisition.md`, `spec-ingest.md`, `spec-consignments.md`, `spec-pipeline-execution.md`
