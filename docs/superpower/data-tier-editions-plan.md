# Data layer by edition — what Basic, Standard and Advanced get from ingestion, Pipelines and data quality (`DATA-TIERS-1`)

**Status: PLAN — recommendations for operator review, nothing built.** Written 2026-10-21 (DT-1…DT-10); **merged
2026-10-22** with the parallel draft `PIPELINE-EDITIONS-1` (never pushed; operator: "merge into a single plan"),
which added the grounding in §3 and §7, the operator decisions DT-11…DT-14, and the open decisions DT-15…DT-19.
Each open decision needs operator confirmation before its phase starts, because the gates remove capability from
the free tier. When a phase ships, distil the as-built facts into the product hubs (`edition/basic`, `module/etl`,
`module/acquire`, `module/engine`, `layer/platform`), update `docs/EDITIONS.md`, move open items to
`docs/BACKLOG.md`, and archive this file when the last phase ships.

**Takes over** the ingestion half of [`la-edition-placement-changes-plan.md`](la-edition-placement-changes-plan.md)
(its §6, its §4.3 checks for D-LAE7…D-LAE11, and those decisions). That plan keeps the Link Analysis moves.

**Companions.**

- [`pipeline-editions-first-run-use-cases.md`](pipeline-editions-first-run-use-cases.md): console first-time user
  use cases, blank slate to a queryable Dataset (§6 answered by the operator 2026-10-22).
- `docs/okf/product/module/la-core/spec-first-time-user-use-cases.md` (drafted in parallel, not yet committed when
  this was written): the same journey in Inspecto LA, from a live blank-slate walkthrough (findings F-1…F-13).
- [`first-run-onboarding-plan.md`](first-run-onboarding-plan.md) (`ONBOARDING-FIRST-RUN-1`): the onboarding
  experience both use-case docs ask for, including the "Add data" path this plan's phase 1 needs.

## 1. Why

The data layer — getting data in, shaping it and keeping it fresh — is the platform's core; the applications on
top of it (Inspecto LA, revenue assurance, fraud management, reconciliation, regulatory reporting) are what a
business pays for. The pipeline layer exists to **serve those downstream applications**, so editions differ on
**workflow customisation and automation**, not on the authoring workbench (operator, 2026-10-22). Three axes:

1. **Automation.** Manual versus automatic execution, incremental summaries, index builds.
2. **Reach.** Steps, external system adapters, parsers (ASN.1 named as Advanced).
3. **Egress.** Export and communication with the outside world.

Two problems today:

1. **An empty Basic install has no way in.** "Explore a Dataset" lists Datasets, but a fresh system has none, and
   the analyst CSV upload (`LA-ANALYST-UPLOAD-1`, built 2026-10-18) needs an existing Collector and Pipeline and
   answers 503 on Basic. The console's own path is about 11 manual steps over 5 panes (console use cases §2); the LA
   walkthrough needed 9 API calls (LA use cases F-3, F-4).
2. **Basic gives away continuous operation.** Basic is the former Personal build and still ships the whole data
   layer: every Collector, Pipelines with transforms, schedules, Jobs, Expectations and quarantine. Only the
   Investigation surface was moved out on 2026-10-17 (`LA-EDITIONS-1`). As the free tier, Basic should let anyone
   try any application on their own data once — not run it in production.

## 2. Principle

- **Basic = one-off import, enough to try an application.** Manual arrival only; a re-import replaces.
- **Standard = data that keeps flowing.** Scheduled and continuous ingestion, full shaping, quality, history,
  outbound delivery. This is the paid line.
- **Advanced = scale, industry protocols and governance.** Multi-node, Postgres-backed operational stores, ASN.1
  and telecom decoders, PII masking, regulated publishing.
- **One product-wide edition switch** (DT-11). A downstream application's edition is the product's edition.
- **One engine, different gates.** Basic never forks the code: a Basic import runs on the same Collector →
  Pipeline → Dataset machinery underneath, hidden. Gates name **capabilities**, never editions
  (`EditionFeatures.present("pipeline.automation")`, not `edition == standard`); the edition decides which module
  declares which capability.
- **Never wrong data on any edition.** Whatever Basic loads is parsed, typed and deduplicated exactly as on
  Advanced, and rows it cannot load are counted and shown, never dropped silently (DT-15).
- **Locked with a reason, never hidden** (DT-13): a capability the edition lacks is shown disabled with one line
  on why and which edition has it.

## 3. What exists today (verified 2026-10-22)

| Axis | Today | Mechanism |
|---|---|---|
| Automation | **Nothing is gated.** A Pipeline without `trigger:` polls every tick (`PipelineTrigger` `DEFAULT_POLL` → `Scheduler.LOOP`); cron, `on_pipeline`, `on_signal` and manual Job triggers are ✅ in every edition (board JOB-02, ING-01). Enrichments recompute incrementally on `on_pipeline` (the freshness path, `EnrichmentConfig`); `la.index.build` runs on `pipeline.commit` (`LaIndexBuildJob`). | none |
| Reach: parsers | ASN.1 BER decoder (SP-PRS-06) is Standard and up: module `telecom-asn1` in `offerings/standard.toon`, add-on `telecomIndustryPack`. No shipped Space template uses it. | module presence |
| Reach: adapters | The local file Collector is base (`platform/inspecto-acquire/.../LocalFileSystemConnector.java`). **Every remote adapter** (SFTP, FTP/FTPS, S3, Azure Blob, GCS and the `db` export Collector, `DbExportConnector`) lives in one jar, `providers/inspecto-connectors`, which **Basic stages** (`offerings/basic.toon`). No shipped template uses a remote Collector. Kafka is Standard and up (`connectors-kafka`). | module presence |
| Reach: Steps | Only `transform.mask` is gated (Advanced, `quality.pii.mask`). `ProcessorCatalog` has no edition field. | config feature |
| Data quality | Expectations, quarantine and Alert Rules run in every edition except Alert dispatch (`alert.dispatch`, Standard). | config feature |
| Egress | Standard and up already: webhook sink (SP-SNK-14), delivery channels (CP-15, `notify-channels`), Action Requests (CP-16), metrics and audit export (CP-13), the compliance archive and DuckLake (`sink.archive`, `sink.ducklake`). **Open on Basic:** the `objectstore.export` Job type (JOB-01, base engine), `publish.postgres` (JOB-06: registers on Basic, fails for want of the JDBC driver), Pipeline bundle export (CP-04). | mixed |

The three gate mechanisms (module presence, config features refused at save, load and run time, route features
answered `503 CAPABILITY_UNAVAILABLE`) are in `platform/inspecto-etl/.../EditionFeatures.java`; the Link Analysis
plan §3 describes them.

## 4. Placement (recommended)

Cells marked **(DT-n)** depend on an open decision in §5.

| Capability | Basic (free) | Standard | Advanced |
|---|---|---|---|
| Ways in | One-off import: upload CSV or Parquet; a file on this machine; one-off Postgres snapshot (DT-4) | Every Collector: inbox, SFTP, FTP/FTPS, S3, Azure, GCS, HTTP, database with incremental watermark, Kafka | Same, plus object storage at scale and premium connectors |
| Industry and protocol parsers | — | — | ASN.1 and the telecom vendor decoders (DT-12) |
| Arrival | Manual only: *Add a file* and *Run now*; a re-import replaces, and says so; a trigger-less Pipeline never polls (DT-17) | Scheduled and continuous; event chains and signal-driven Jobs; incremental recompute; late, duplicate and renamed-correction detection (DT-16) | Same, plus multi-node scale-out |
| Shaping | No-code: column types, rename, choose columns, filter rows, dedupe (DT-7) | Full Pipeline: Step Processors, SQL steps, schemas with expression columns, parsing grammars | Same, plus PII masking |
| Lookup and join across Datasets | No; display of Reference details on nodes per DT-6 | Reference Datasets, enrichment, joins | Plus SCD2 history (when built) |
| Data quality | Import checks: required columns, type errors and unparseable rows counted, shown and kept aside (DT-15) | Authored Expectations, quarantine review, delivery Alerts, data trust | Plus maker-checker on configuration changes |
| Run history and statistics | Last import summary (rows in, rows rejected) | Run history, processing statistics, lineage and provenance, Analytics kernel statistics | Plus long-retention operational stores on Postgres |
| Outputs | Used inside Inspecto only; Pipeline bundle export (configuration, not data) | Publish and export sinks: webhook, mail and delivery channels, Postgres publish, object-storage export | Plus regulated publishing (four-eyes), egress governance (demand-gated) |
| Jobs and orchestration | Maintenance Jobs only (cleanup, prune, retention; DT-16) | Jobs, schedules, Signals, retries | Plus distributed coordination |

**The edition story.** Basic: one person loads a file by hand and tries an application on it. Standard: data
arrives by itself from remote systems, is shaped and checked, and results are pushed out. Advanced: industry
protocols, masking, scale and organisational control.

## 5. Decisions

### 5.1 Decided by the operator (2026-10-22)

| # | Decision |
|---|---|
| DT-11 | **One product-wide edition switch** (Basic / Standard / Advanced); no per-application capability sets. DT-10's per-application Basic forms are placements under this one switch. |
| DT-12 | **ASN.1 and the telecom decoders move to Advanced** (module `telecom-asn1` and add-on `telecomIndustryPack` from `offerings/standard.toon` to `advanced.toon`). Overrides this plan's 2026-10-21 placement (Standard). |
| DT-13 | **Locked with a reason, never hidden**, on every gate this plan adds: the control is disabled, with one line saying why and which edition has it, using the server's `refusal()` wording. Overrides the 2026-10-21 phase 2 text ("the SPA hides the affordances"). |
| DT-14 | **The console work the gates need is part of this plan** (console use cases §6 Q3, Q4): *Add a file* and *Run now* together as one gesture on Basic (today upload is only in the Run-to-here test dialog and *Run now* only on the Collectors pane); Activate copy that follows the edition ("next poll cycle" is wrong on Basic); the `orders-starter` load-and-run sample button. The rest of the onboarding experience is `ONBOARDING-FIRST-RUN-1`. |

### 5.2 Recommendations awaiting the operator

| # | Decision | Recommendation |
|---|---|---|
| DT-1 | Basic's way in is **"Add data"**: upload a file or import from Postgres, one column-review step, then a Dataset. The empty "Explore a Dataset" card in Inspecto LA becomes this entry point. | Yes. Built by `ONBOARDING-FIRST-RUN-1` as one path for the console and Inspecto LA. |
| DT-2 | A Basic import runs on a **hidden one-step Collector and Pipeline** per import, so an upgrade to Standard shows it as an ordinary, editable Pipeline. No parallel ingest path. | Yes |
| DT-3 | **"Point at a file in place" (no copy) is NOT offered** for now. | Yes; revisit on demand for very large files |
| DT-4 | **Postgres one-off snapshot is in Basic** (read-only account, password as a Space secret reference; falls back to the JDBC `db` Collector run once). ⚠ The 2026-10-22 draft recommended the opposite (every remote adapter Standard, by dropping `inspecto-connectors` from Basic). | **Keep DT-4.** Consequence: `inspecto-connectors` stays in Basic for the `db` run-once fallback, so the other remote adapters are refused by a config feature (`ingest.remote`), not by module absence. Splitting the jar is the alternative if a module gate is preferred. |
| DT-5 | **No scheduling of any kind in Basic** — not even a daily re-import. Manual re-import only. | Yes, refined by DT-16 and DT-17 |
| DT-6 | **No joins or lookups in Basic.** Reference Datasets, enrichment and joins are Standard. | Yes for joins that make new data; **allow showing a Reference's details on nodes and filtering by them** (LA use cases Q-5), which is display, not a join |
| DT-7 | **No-code shaping is in Basic** (types, rename, choose columns, filter rows); anything that needs a Step Processor or SQL is Standard. | Yes, plus **dedupe** (correctness). Schema `expression:` columns are SQL, so Standard: the `orders-starter` derived column goes on Basic (DT-18). `transform.explode` (nested arrays) is a Step: Standard, with flattening by record path and dotted selectors staying a parse capability in Basic |
| DT-8 | **Import limits in Basic** through the configurable-limits catalogue: default 500 MB per file, 10 million rows per import, 20 imported Datasets per Space. XLSX not accepted: save as CSV. | Yes |
| DT-9 | **Existing configurations that use Standard features fail with a clear refusal on Basic**, naming the feature. Breaking changes are free; no shim. | Yes, except a trigger-less Pipeline (DT-17) |
| DT-10 | **Each application gets its own Basic form, decided per application** (Inspecto LA: the explorer). The others default to Standard and above until a free form is chosen. | Yes, under DT-11 |
| DT-15 | **What "data quality" Basic keeps.** The 2026-10-21 table gave Basic import checks only; the 2026-10-22 draft kept Expectations and quarantine in every edition. | **Middle:** Basic keeps the import checks (required columns, type errors, unparseable rows) **and keeps those rows aside, counted and shown** (correctness); authored Expectations, quarantine review, Alerts and data trust are Standard |
| DT-16 | **What counts as automation.** | Continuous triggers, `EVENT` triggers, Jobs on cron / `on_pipeline` / `on_signal` (closing the hole where a signal-driven Job such as `la.index.build` still fires after a manual commit) and incremental recompute are Standard. **Maintenance Jobs stay automatic on Basic**, or retention never runs and storage grows without limit. Watermarks and the dedup ledger stay in every edition |
| DT-17 | **A trigger-less Pipeline on Basic** | **Never polls, is not refused**: refusing the default form would break every legacy config and both Basic packs. Explicit interval, cron, stream and `EVENT` triggers are refused (DT-9) |
| DT-18 | **Content packs on Basic** | `la-daily-feed` (polling, a commit-signal index build, Alert Rules) moves to Standard — this settles the undecided §3.8 board note of 2026-10-18 that kept it on Basic; `orders-starter` stays on Basic as a manual pack with the load-and-run button (DT-14) and without its derived column (DT-7) |
| DT-19 | **Kafka** | Stays Standard: a mainstream integration that keeps Standard a complete automated product |

## 6. Gates (capabilities)

| Feature | Covers | Declared by | Gate kind |
|---|---|---|---|
| `pipeline.automation` | Continuous and event triggers, scheduled and signal-driven Jobs, incremental recompute (DT-5, DT-16, DT-17) | `inspecto-ops` (Standard+) | config: save, load, run |
| `pipeline.transform` | Step Processors beyond no-code shaping, SQL steps, schema expression columns, References, enrichment, joins (DT-6, DT-7) | `inspecto-ops` | config, via a `feature` field on `ProcessorCatalog.Processor` |
| `pipeline.quality` | Authored Expectations, quarantine review, data trust (DT-15) | `inspecto-ops` | config |
| `pipeline.egress` | `objectstore.export`, `publish.postgres` (the webhook, mail and archive gates already exist) | `inspecto-ops` | config: Job save, load, run |
| `ingest.remote` | SFTP, FTP/FTPS, S3, Azure, GCS Collectors; `db` other than the one-off snapshot (DT-4) | `inspecto-ops` | config: save, load, acquisition |
| module `telecom-asn1` | ASN.1 and telecom decoders (DT-12) | Offering | module presence (Advanced) |

Every feature gets a `refusal()` text (pinned by `EditionFeaturesTest`), a `/bootstrap` `features.*` flag, and a
locked-with-reason control in the console (DT-13). `PII_MASK` (Advanced, `inspecto-policy`) is unchanged.

## 7. What each gate touches

Line numbers are from the 2026-10-20 survey (Link Analysis plan §4.3), not re-read.

- **`pipeline.automation`.** `EditionFeatures.pipelineRefusals` (draft and parsed forms) refuses an explicit
  interval, cron, stream or `EVENT` trigger; reaches `SaveGate`, `ConfigRegistry`, `SpaceManager`. At run time
  `PipelineScheduler.dueThisTick` returns false for every non-`MANUAL` kind, **including `DEFAULT_POLL`**, when the
  feature is absent (DT-17); the same guard sits on the acquire driver (`dispatchAcquireCycle`, the
  `CollectorService` timers). Jobs: the Job save gate refuses cron / `on_pipeline` / `on_signal` on a non-maintenance
  Job type, and the Job scheduler and signal dispatcher skip them. Enrichment: `EnrichmentService` does not wire the
  `on_pipeline` freshness path or `schedule_seconds`.
- **`pipeline.transform`.** A `feature` field on `ProcessorCatalog.Processor` (null = every edition) so the
  palette, the save gate and the docs read one list; a refuse walk modelled on `maskRefusals` (it walks route
  branches); run-time precedent `RowShaper`'s mask check. Schema `expression:` entries are checked by the schema
  save and load path. Both generated contract JSONs under `inspecto-ui/` are regenerated in the same change.
- **`pipeline.quality`.** Expectation component save and load; the quarantine review routes; the import summary
  keeps counting kept-aside rows on Basic (DT-15).
- **`pipeline.egress`.** `ObjectStoreExportJobType` and `PostgresPublishJobType`
  (`platform/inspecto-engine/.../job/`) refuse at Job save, load and run.
- **`ingest.remote`.** The Collector save gate and `CollectorProcessor`'s acquisition check (precedent: the archive
  refusal at `CollectorProcessor.java:416`), exempting the one-off snapshot path of DT-4.
- **ASN.1.** `offerings/standard.toon` → `advanced.toon`, the add-on entry, `node tools/render-offerings-matrix.mjs
  --check`, `node tools/check-offerings.mjs`, the product map edition items.
- **`/bootstrap` and the console.** `features.pipelineAutomation`, `pipelineTransform`, `pipelineQuality`,
  `pipelineEgress`, `ingestRemote`; one shared locked-with-reason component; the editor, Jobs pane, step palette,
  Collect stage and Dataset offers key on them. Plus the DT-14 console work.

## 8. Phases

Each phase: a test per gate that fails without the gate (Basic vs Standard via `EditionFeatures.overrideForTest`,
reset in teardown); `node tools/affected-modules.mjs` for the test set; an independent `verification` PASS; one
lane per push.

| Phase | Content | Depends on | Effort |
|---|---|---|---|
| 1 | **"Add data" import (Basic and up)** — delivered by `ONBOARDING-FIRST-RUN-1` (its O2–O4); this plan owns its edition rules: hidden Collector + Pipeline (DT-2), Postgres snapshot (DT-4), limits (DT-8) | DT-1, DT-2, DT-4, DT-8 | see that plan |
| 2 | Groundwork: feature constants, `refusal()` texts, `OpsEditionFeatures` declarations, `/bootstrap` flags, the shared locked-with-reason component | §5.2 answered | 1 day |
| 3 | Automation gate: save and load refusals, scheduler and acquire driver, Job save, Job scheduler and signal dispatcher, enrichment wiring; Activate copy and the manual-run gesture (DT-14) | 2 | 4 to 5 days |
| 4 | Reach: `ingest.remote`, ASN.1 Offering move, the Collect stage's locked remote systems | 2 | 1.5 days |
| 5 | Quality and egress gates and their console locks | 2 | 2 days |
| 6 | Step catalog `feature` field, refuse walk, schema expression check, palette locks, contract regeneration | 2 | 3 to 5 days |
| 7 | Upgrade path: a test that boots Basic, imports, then boots Standard on the same Space and edits the import as an ordinary Pipeline | 1, 3 | 1 day |
| 8 | Content packs (DT-18), release note, `docs/EDITIONS.md` matrix and rows (JOB-02, ING-01, SP-PRS-06, the remote SP-ACQ rows, JOB-01 / JOB-06, the SP-XFM rows), the edition hubs, the platform whitepaper's edition ladder, user guides | 3 to 6 | 1 day |
| 9 | Per-application Basic forms (DT-10), application by application | — | per application |

Phases 4 and 5 are independent of phase 3 and can be built beside it. Phase 6 goes last: it is the largest and
depends on DT-7. Each new route clears the four endpoint gates.

## 9. What breaks (no shim; release note)

- Existing Basic installs stop polling, scheduling and chaining; their Pipelines run when started by hand.
- Basic configs that use a transformation Step, a schema expression column, a remote Collector, an authored
  Expectation, `objectstore.export` or `publish.postgres` are refused at load, naming the feature (DT-9).
- Standard installs lose the ASN.1 decoder (DT-12); no shipped template uses it.
- `la-daily-feed` moves to Standard; `orders-starter` loses its derived column on Basic (DT-18).
- Every Space Template that ships a schedule must say "Standard and above" or its Basic seed must skip it (the
  la-showcase seed already skips Investigation steps on Basic, `ceb68fef3`).

## 10. Risks and not confirmed

- **Postgres extension offline** (DT-4): whether the bundled DuckDB carries the Postgres extension for every
  platform; otherwise the JDBC fallback.
- **Secrets without sign-in** (DT-4): Basic has no users; the stored database password is protected only by file
  permissions on the host. Document it.
- **Where application data lives in Basic**: imported Datasets sit in the Space store; retention and deletion
  follow the Space.
- **Existing hidden gates.** `7c6d77c80` hides Alerts on Basic; DT-13 applies to the gates this plan adds, and
  whether older hidden gates convert is a separate call.
- Not confirmed: what the Connection routes and pane do for a refused remote kind on Basic; whether any shipped
  template uses an `EVENT` trigger; whether `inspecto-connectors`' delivery-status adapters are used by anything
  Basic keeps; the 2026-10-20 line numbers in §7.

## 11. Board

P2 `DATA-TIERS-1` in `BACKLOG.md` §3.8 (hold: operator), indexed in §1 *Decisions owed on individual rows*.

## References

- `docs/EDITIONS.md` (current edition matrix), `docs/archived-documents/plans-archive/inspecto-la-editions-plan.md`
  (the 2026-10-17 edition decisions), `docs/superpower/la-analyst-upload-plan.md` (the existing upload route),
  `docs/okf/product/layer/platform/` (data-layer hubs).
