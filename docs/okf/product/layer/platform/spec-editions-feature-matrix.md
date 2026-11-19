---
type: Spec
title: Editions — engineering specification — Feature × edition matrix — how to read it, core engine
description: Part 3 of 5 of Editions — engineering specification: Feature × edition matrix — how to read it, core engine
resource: offerings/
tags: [editions, packaging, offerings, gating, decisions]
timestamp: 2026-11-06T00:00:00Z
---

# Editions — engineering specification — Feature × edition matrix — how to read it, core engine

Part 3 of 5 of [Editions — engineering specification](spec-editions.md) · previous: [Preview, Basic, the data layer and the assembly model](spec-editions-tiers.md) · next: [Feature × edition matrix — Step Processors](spec-editions-step-processors.md).

## Feature × edition matrix (working board, opened 2026-09-02)

**How to read and work this board.** One row per product feature, one cell per edition. A cell is
addressed as `<row-id>/<P|S|E>` (e.g. `SEC-03/S`) so we can decide, build and deliver **cell-wise** — the letters
predate LA-EDITIONS-1 and are kept so existing references stay valid: **P = Basic, S = Standard, E = Advanced**.
Rows are grouped by the FEATURE_INVENTORY §1 areas plus the control-plane / security / compliance — ⚠ **there is deliberately no `UI` row group** (corrected 2026-09-08: this note claimed one). The SPA shell ships in every edition and adapts to what registered; a gated pane rides its module's row (`CP-09`, `CP-11`, `CP-13`). Shell requirement-of-record: [`okf/product/layer/product/plan-surfaces.md`](../product/plan-surfaces.md). Legacy wording:
surfaces EDITIONS.md §Matrix already splits. **Editions are build flavors of one codebase** — a core
feature is in every edition by construction, so most Basic/Standard/Advanced cells agree; the
board exists for the cells that *differ* or are *undecided*.

| Cell | Meaning |
|---|---|
| ✅ | shipped in this edition (the feature is in the bundle and exercised) |
| 🟡 | partial — shipped with a stated gap (see Notes) |
| 🔲 | planned for this edition, not built |
| — | deliberately not in this edition (a decision, not a gap) |
| ❓ | undecided — needs an operator call before anyone builds |
| *not stated* | the product deliberately states nothing either way (DATA-TIERS-1 DT-16, operator 2026-11-05: "no promise, keep silent") |

🔴 **A ✅ means the capability is PRESENT AND USABLE on that edition. It does NOT mean "gated to this
tier and above."** (Convention stated 2026-09-15, `SPEC-GREENCELL-1`.) The distinction is load-bearing
because **Basic is auth-free by design and enforces no capability gate at all**:
`ApiContext.requireCapability` is a deliberate no-op when no `Subject` is attached, so a route carrying a
capability check is still open there. Read the wrong way, every gated row looks like an overclaim on
Basic — which is exactly how `BI-4`/`BI-6`/`BI-7` were first filed as a security hole and then
**corrected to a documentation defect**. ⛔ Do **not** "fix" that by adding refusals to the routes: it
would break an edition's designed posture to satisfy a table. Where a capability is *enforced*, and by
what, is owned by [`okf/product/module/access/plan-security.md`](../../module/access/plan-security.md)
§capability-vocabulary — not by this matrix.

⚠ Keep the row IDs stable once referenced; append new rows at the end of their area. A cell that
changes state carries the date in Notes. Source of truth for *what* a feature is stays
[`FEATURE_INVENTORY.md`](features-feature-inventory.md) §1; this table only answers *which edition*.

### Core engine — platform capabilities (identical across editions by construction) [`ING` · `MET` · `PIP`]

> ⚠ **Two rows in this table have no capability owner** (measured 2026-09-09, step 8): **`XFM-04`** (Decision Rules) and **`OUT-04`** (auto-chunking + DuckDB scratch/memory tuning) are cited by **no** capability spec, while `XFM-01..03`/`OUT-01..03` moved to the Step Processors table as `SP-XFM-*`. They were left behind by that split. `XFM-04`'s subject (Decision Rule) belongs to `INC` by [`GLOSSARY.md` §14](../../../../GLOSSARY.md#14-capability-areas-the-functional-spine) and `OUT-04`'s to `PIP` execution — ⛔ but neither spec claims them yet, so this note records the gap rather than asserting an owner.

> Split 2026-09-02: the per-processor rows (parsers, transforms, sinks, acquisition adapters — the former
> ING-03/04, PRS-*, XFM-01..03, OUT-01..03) moved to the **Step Processors** table below; this table keeps
> the platform capabilities no single processor owns.

| ID | Feature | P | S | E | Notes |
|---|---|---|---|---|---|
| ING-01 | Multi-pipeline poll cycle (`active:` gate, M..N parallelism, file-pattern glob) | *not stated* | ✅ | ✅ | FEATURE_INVENTORY §A. P cell *not stated* since 2026-11-06 (DATA-TIERS-1 DT-16 / §5.3): Basic runs any configuration it is given, and the product makes no promise either way about Pipelines that run on their own there. What Basic offers is Add data's own arrival: *Run now*, and *Watch this folder* (a fixed 30 s poll of an import inbox: since B1 the import Pipeline's own locked `every: 30s` schedule) — *Data layer by edition* above |
| ING-02 | Consignment formation (`collector.consignment: max_files / max_bytes / order`) | ✅ | ✅ | ✅ | moved to the Collector 2026-09-02 (CONSIGNMENT-HOME-1); legacy `processing.batch` dual-read |
| ING-05 | Unpack stage (zip/tar/gz/bz2/Z) | ✅ | ✅ | ✅ | ⚠ **nested archives are refused by design** (`UnpackLimits.depth` = 1, any other value refused by name) — this row promised them until 2026-09-08; xz/zstd have no codec (BACKLOG §3) |
| SCH-01 | Schema registry: field types (all DuckDB scalars, fail-closed), rules (`DIRECT`/`EXPR`/`CONCAT_DT`/`FILENAME_DATE`), multi-format dates | ✅ | ✅ | ✅ | fail-closed types = `SchemaFieldTypes` (2026-08-22); the `rules[]` spelling now *loads by conversion* — the engine reads `fields[]` first (`MAPPING-SPELLING-1`, BACKLOG §1). *(This cell pointed at a §C that does not exist until 2026-09-08.)* |
| SCH-02 | Quarantine / reject routing | ✅ | ✅ | ✅ | |
| SCH-03 | Field classification metadata (PII / INTERNAL) | ✅ | ✅ | ✅ | metadata; Link Analysis masks entity ids by it (`maskingMode`, editions that ship `inspecto-geo-link`); no other surface masks on it (see SEC-08) |
| XFM-04 | Decision Rules (space-registry, rule-routed outputs) | ✅ | ✅ | ✅ | keep a pipeline on the flat lane when they route rows |
| OUT-04 | Auto-chunking of huge files, DuckDB scratch/memory tuning | ✅ | ✅ | ✅ | |
| JOB-01 | Job framework (`enrich`, `report`, `maintenance`, `pipeline`, `sql.template`, `consignment.process`, `objectstore.export`, `recon.run`, `risk.score`, `caserule.evaluate`, `objects.analytics`, `mail.send`) | ✅ | ✅ | ✅ | §F. ⚠ **`caserule.evaluate` and `objects.analytics` are `inspecto-ops` Job Types (EDG-01 cell 7) — unknown on Basic; `recon.run` is an `inspecto-reconciliation` Job Type and `risk.score` an `inspecto-scoring` Job Type (MODULE-REORG-1 P7) — also unknown on Basic**; the framework cell stays ✅ (corrected 2026-09-08, `okf/product/addon/incidents/plan-incidents.md` §2). `objectstore.export` is planned Standard and up as outbound delivery (DATA-TIERS-1 P5, locked in Basic's screens; not built 2026-11-06): today it registers on Basic as before |
| JOB-02 | Triggers: cron, `on_pipeline`, `on_signal` + `when` guards, `catch_up`, manual | *not stated* | ✅ | ✅ | P cell *not stated* since 2026-11-06: DT-16's Jobs gate was dropped (operator 2026-11-05, "no promise, keep silent"), so a Basic install arms the Jobs its Spaces define and the product states nothing either way. An import's own follow-on work (its link index, its Expectations) runs inside the person's run in every edition, never as a Job |
| JOB-03 | Maintenance task library (cleanup, ledger/runlog/notification/receipt/dedup/event prune, incident_purge, backup/restore/verify, storage report/trend, compact, materialize, db_maintenance) | 🟡 | ✅ | ✅ | P is 🟡 (corrected 2026-09-08): four of the listed tasks are not on Basic. `event_prune` added 2026-09-02 (COMPLY-3). ⚠ **`incident_purge` is an `inspecto-ops` task (EDG-01 cell 7) — unknown on Basic**; backup/restore/verify are `inspecto-backup` (cell 2), also not Basic (corrected 2026-09-08) |
| JOB-04 | Consignment concurrency broker (priority shares, intake caps) | ✅ | ✅ | ✅ | Intake file cap ON by default — `ingest.maxFilesPerCycle` = 500 (`IntakeGovernor.DEFAULT_MAX_FILES_PER_CYCLE`; operator, 2026-10-06, soak waived); `0` = unbounded. The byte cap stays off by default |
| JOB-06 | BI publication — `publish.postgres` Job: curated Datasets to a Postgres schema, full refresh (atomic swap) or partition-incremental, Catalog column comments (ASSURE-BI-PUBLICATION-1) | ⚠ | ✅ | ✅ | The Job Type is core and registers everywhere, but the PostgreSQL JDBC driver ships only as the Standard/Advanced `postgresql.jar` sidecar (PG-1), so on Basic a run fails with `No suitable driver`. Without an authenticator (Basic) four-eyes approval can never be met, so the Job cannot be created through the API, and sensitive-classified columns are never published. [`okf/product/layer/product/plan-studio.md`](../product/plan-studio.md) §3.6. Planned Standard and up as outbound delivery (DATA-TIERS-1 P5, not built 2026-11-06). Not to be confused with Add data's *Choose a table*, which READS a Postgres table on every edition through DuckDB, with no JDBC driver |
| JOB-05 | Risk Scores — `risk-score` model, `risk.score` Job, `GET /risk-scores/{model}/{entityKey}` + `POST /risk-scores/preview` (ASSURE-RISK-SCORE-1) | — | ✅ | ✅ | **NOT in Basic (MODULE-REORG-1 P7, 2026-10-07) — a deliberate behaviour change.** The two `/risk-scores` routes, the model/evaluator, the `risk.score` Job Type and the save-time checks of the `risk-score` kind left the core for the optional `inspecto-scoring` module (the Scoring half of the plan §8a Scoring and Lists add-on; it depends on `inspecto-entity-list` for the watch-list seam), staged from Standard up. On Basic the paths answer **503 "not installed"** (`AbsentModuleRoutes`), `/bootstrap` reports `features.scoring=false` and `risk.score` is an unknown Job Type; a `risk-score` component is accepted there as opaque config (nothing can evaluate it). The core keeps the pending Alert Rule feature (`PendingAlertRules`, over `RiskScoreOutputs`) and the `EvidenceMasker` (`com.gamma.mask`). Turning a high score into an Incident is an ordinary per-entity Alert Rule, so it follows Alert Rule authoring (Standard+, G9). The read route is `canWorkIncidents`-gated. [`okf/product/module/scoring/spec-risk-scores.md`](../../module/scoring/spec-risk-scores.md) |
