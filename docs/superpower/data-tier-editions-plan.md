# Data layer by edition — what Basic, Standard and Advanced get from ingestion, Pipelines and data quality (`DATA-TIERS-1`)

**Status: PLAN 2026-10-21 — recommendations written for operator review, nothing built.** Decisions DT-1…DT-10
are the assistant's recommendations (operator asked for the plan "with your recommendations"); each needs operator
confirmation before its phase starts, because phase 2 removes capability from the free tier. When a phase ships,
distil the as-built facts into the product hubs (`edition/basic`, `module/etl`, `module/acquire`,
`layer/platform`), update `docs/EDITIONS.md`, move open items to `docs/BACKLOG.md`, and archive this file when the
last phase ships.

## 1. Why

The data layer — getting data in, shaping it and keeping it fresh — is the platform's core; the applications on
top of it (Inspecto LA, revenue assurance, fraud management, reconciliation, regulatory reporting) are what a
business pays for. Two problems today:

1. **An empty Basic install has no way in.** "Explore a Dataset" lists Datasets, but a fresh system has none, and
   the analyst CSV upload (`LA-ANALYST-UPLOAD-1`, built 2026-10-18) needs an existing Collector and Pipeline and
   answers 503 on Basic.
2. **Basic gives away continuous operation.** Basic is the former Personal build and still ships the whole data
   layer: every Collector, Pipelines with transforms, schedules, Jobs, Expectations and quarantine. Only the
   Investigation surface was moved out on 2026-10-17 (`LA-EDITIONS-1`). As the free tier, Basic should let anyone
   try any application on their own data once — not run it in production.

## 2. Principle

- **Basic = one-off import, enough to try an application.** Manual arrival only; a re-import replaces.
- **Standard = data that keeps flowing.** Scheduled and continuous ingestion, full shaping, quality, history. This
  is the paid line.
- **Advanced = scale and governance.** Multi-node, Postgres-backed operational stores, regulated publishing.
- **One engine, different gates.** Basic never forks the code: a Basic import runs on the same Collector →
  Pipeline → Dataset machinery underneath, hidden. The gate is an edition feature plus route checks — the same
  mechanism as the `investigations` gate (`EditionFeatures`, `ControlApi.requireModuleEnabled`).

## 3. Placement (recommended)

| Capability | Basic (free) | Standard | Advanced |
|---|---|---|---|
| Ways in (Collectors) | One-off import: upload CSV or Parquet; a file on this machine; one-off Postgres snapshot | Every Collector: inbox, SFTP, HTTP, database with incremental watermark, Kafka; industry formats (ASN.1 call records) | Same, plus object storage at scale and premium connectors |
| Arrival | Manual only; re-import replaces, and says so | Scheduled and continuous; late, duplicate and renamed-correction detection | Same, plus multi-node scale-out |
| Shaping | No-code: column types, rename, choose columns, filter rows | Full Pipeline: Step Processors, SQL steps, schemas, parsing grammars | Same |
| Lookup and join across Datasets | No | Reference Datasets, enrichment, joins | Plus SCD2 history (when built) |
| Data quality | Import checks only: required columns, type errors counted and shown | Expectations, quarantine, delivery Alerts, data trust | Plus maker-checker on configuration changes |
| Run history and statistics | Last import summary (rows in, rows rejected) | Run history, processing statistics, lineage and provenance, Analytics kernel statistics | Plus long-retention operational stores on Postgres |
| Outputs | Used inside Inspecto only | Publish and export sinks, Postgres publish, object-storage export | Plus regulated publishing (four-eyes) |
| Jobs and orchestration | None | Jobs, schedules, Signals, retries | Plus distributed coordination |

## 4. Decisions (assistant's recommendations; operator to confirm)

| # | Decision |
|---|---|
| DT-1 | Basic's way in is **"Add data"**: upload a file or import from Postgres, one column-review step, then a Dataset. The empty "Explore a Dataset" card in Inspecto LA becomes this entry point. |
| DT-2 | A Basic import runs on a **hidden one-step Collector and Pipeline** per import (no transforms beyond the no-code shaping), so an upgrade to Standard shows it as an ordinary, editable Pipeline. No parallel ingest path. |
| DT-3 | **"Point at a file in place" (no copy) is NOT offered** for now: a Dataset whose file changes underneath gives answers that move, which undercuts the evidence story even in the free tier. Revisit on demand for very large files. |
| DT-4 | **Postgres one-off snapshot is in Basic**, read-only account, with the password stored as a Space secret reference (never in config). If the DuckDB Postgres extension is not available offline in a bundle, it falls back to the JDBC `db` Collector run once. |
| DT-5 | **No scheduling of any kind in Basic** — not even a daily re-import. Manual re-import only. |
| DT-6 | **No joins or lookups in Basic.** Reference Datasets, enrichment and joins are Standard. |
| DT-7 | **No-code shaping is in Basic** (types, rename, choose columns, filter rows); anything that needs a Step Processor or SQL is Standard. |
| DT-8 | **Import limits in Basic** through the configurable-limits catalogue, generalised to the platform: default 500 MB per file, 10 million rows per import, 20 imported Datasets per Space. XLSX is not accepted: save as CSV (no native Excel reader today). |
| DT-9 | **Existing configurations that use Standard features fail with a clear refusal on Basic** ("this Pipeline uses scheduling, available in Standard and above"), never silently. Breaking changes are free (operator standing preference), but the refusal names the feature. |
| DT-10 | **Each application gets its own Basic form, decided per application** (Inspecto LA: the explorer, decided 2026-10-17). The others (revenue assurance, fraud, reconciliation, regulatory reporting) default to Standard and above until a free form is chosen for them. |

## 5. Phases

1. **"Add data" import (Basic and up)** — upload CSV / Parquet, Postgres snapshot, column review with no-code
   shaping, hidden Collector + Pipeline, import summary; limits from DT-8. Inspecto LA's empty state links to it.
   Reuses `LA-ANALYST-UPLOAD-1`'s landing and ingest chain; its 503-on-Basic gate moves to the new Basic route.
2. **Gate the data layer on Basic** — new edition feature(s) (e.g. `pipelines`, `schedules`, `dataQuality`) gating
   Collector types other than the import, Step Processors, Jobs and schedules, Expectations, Reference / enrichment,
   publish sinks; clear refusals per DT-9; the SPA hides the affordances as it does for Investigations.
3. **Upgrade path** — a Basic import opens as an ordinary Pipeline in Standard (it already is one underneath);
   prove it with a test that boots Basic, imports, then boots Standard on the same Space.
4. **Per-application Basic forms** — revisit DT-10 application by application.
5. **Docs** — `docs/EDITIONS.md` matrix, the edition hubs, the platform whitepaper's edition ladder, user guides.

Each new route clears the four endpoint gates; each phase runs the affected-build protocol; the full gate once per
release.

## 6. Risks and open questions

- **Breaking for current Basic users** (phase 2): anyone running scheduled Pipelines on Basic today loses them.
  Nothing after 3.x shipped, so the impact is internal demos and templates — but every Space Template that ships a
  schedule must say "Standard and above" or the Basic seed must skip it (the la-showcase seed already skips
  Investigation steps on Basic, `ceb68fef3`).
- **Postgres extension offline** (DT-4): whether the bundled DuckDB carries the Postgres extension for every
  platform; otherwise the JDBC fallback.
- **Secrets without sign-in** (DT-4): Basic has no users; the stored database password is protected only by file
  permissions on the host. Document it.
- **Where application data lives in Basic**: imported Datasets sit in the Space store like any other; retention and
  deletion follow the Space.

## References

- `docs/EDITIONS.md` (current edition matrix), `docs/archived-documents/plans-archive/inspecto-la-editions-plan.md`
  (the 2026-10-17 edition decisions), `docs/superpower/la-analyst-upload-plan.md` (the existing upload route),
  `docs/okf/product/layer/platform/` (data-layer hubs).
