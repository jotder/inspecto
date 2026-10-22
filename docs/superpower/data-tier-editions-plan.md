# Data layer by edition — what Basic, Standard and Advanced get from ingestion, Pipelines and data quality (`DATA-TIERS-1`)

**Status: PLAN — recommendations for operator review, nothing built.** Written 2026-10-21 (DT-1…DT-10); **merged
2026-10-22** with the parallel draft `PIPELINE-EDITIONS-1` (never pushed; operator: "merge into a single plan"),
which added the grounding in §3 and §7, the operator decisions DT-11…DT-14, and the open decisions DT-15…DT-19.
**Operator answers of 2026-10-22** recorded in the Inspecto LA first-time user use cases (§8, A-1…A-16; confirmed
for this plan by the operator the same day) settle DT-1, DT-2, DT-4, DT-6, DT-8, DT-13 and DT-15 (§5.1). Each open
decision needs operator confirmation before its phase starts, because the gates remove capability from the free
tier. When a phase ships, distil the as-built facts into the product hubs (`edition/basic`, `module/etl`,
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
- **Never wrong data on any edition.** Whatever Basic loads is parsed, typed, deduplicated, checked against its
  Expectations and quarantined exactly as on Advanced (DT-15).
- **Locked with a reason inside screens; the navigation stays lean** (DT-13): inside a screen a capability the
  edition lacks is shown disabled with one line on why and which edition has it; whole Standard+ pages (such as
  Alerts) stay out of the Basic navigation.

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
| Ways in | Files only: upload CSV / TSV, Parquet, JSON / NDJSON, and Excel when the bundle carries the DuckDB `excel` extension (DT-8); a file on this machine. A database or remote system is shown locked (DT-4) | Every Collector: inbox, SFTP, FTP/FTPS, S3, Azure, GCS, HTTP, database with incremental watermark, Kafka | Same, plus object storage at scale and premium connectors |
| Industry and protocol parsers | — | — | ASN.1 and the telecom vendor decoders (DT-12) |
| Arrival | Manual only: *Add a file* and *Run now*; a re-import replaces, and says so; a trigger-less Pipeline never polls (DT-17); an enrichment recomputes its full window when run by hand | Scheduled and continuous; event chains and signal-driven Jobs; incremental recompute; late, duplicate and renamed-correction detection (DT-16) | Same, plus multi-node scale-out |
| Shaping | No-code: column types, rename, choose columns, filter rows, dedupe; schema expression columns per DT-7 | Full Pipeline: Step Processors, SQL steps, parsing grammars | Same, plus PII masking |
| Lookup and join across Datasets | Show a Reference's details on nodes and filter by them; no joins that build new data (DT-6) | Reference Datasets, enrichment, joins | Plus SCD2 history (when built) |
| Data quality | Import checks, Expectations, quarantine and dedupe, as on every edition (DT-15) | Plus delivery Alerts and data trust | Plus maker-checker on configuration changes |
| Run history and statistics | Last import summary (rows in, rows quarantined) | Run history, processing statistics, lineage and provenance, Analytics kernel statistics | Plus long-retention operational stores on Postgres |
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
| DT-13 | **Locked with a reason inside screens; the navigation stays lean**, on every gate this plan adds: inside a screen the control is disabled, with one line saying why and which edition has it, using the server's `refusal()` wording; whole Standard+ pages (such as Alerts) stay out of the Basic navigation (refined by LA A-11). Overrides the 2026-10-21 phase 2 text ("the SPA hides the affordances"). |
| DT-14 | **The console work the gates need is part of this plan** (console use cases §6 Q3, Q4): *Add a file* and *Run now* together as one gesture on Basic (today upload is only in the Run-to-here test dialog and *Run now* only on the Collectors pane); Activate copy that follows the edition ("next poll cycle" is wrong on Basic); the `orders-starter` load-and-run sample button. The rest of the onboarding experience is `ONBOARDING-FIRST-RUN-1`. |
| DT-1 | **Basic's way in is "Add data"**: upload a file, one column-review step, then a Dataset; the empty "Explore a Dataset" card in Inspecto LA becomes this entry point (LA A-6). Built by `ONBOARDING-FIRST-RUN-1` as one path for the console and Inspecto LA. The 2026-10-21 wording also offered "import from Postgres"; DT-4 removes it. |
| DT-2 | **A Basic import runs on a hidden one-step Collector and Pipeline** per import, created and run behind the scenes; an upgrade to Standard shows it as an ordinary, editable Pipeline. No parallel ingest path (LA A-6). |
| DT-4 | **Files only on Basic; a database is shown locked** (LA A-12: "D-PE6 wins"). **Reverses** the 2026-10-21 DT-4 (a one-off Postgres snapshot in Basic). Every remote adapter, the `db` Collector included, is Standard and up, so the gate is **module absence**: `inspecto-connectors` leaves `offerings/basic.toon`. |
| DT-6 | **Show a Reference's details on nodes and filter by them on Basic; joins that build new Datasets stay Standard** (LA A-5). Refines the 2026-10-21 "no joins or lookups in Basic". |
| DT-8 | **Formats and limits for "Add data" on Basic**: CSV / TSV, Parquet, JSON / NDJSON (LA A-2), and **Excel when the bundle carries the DuckDB `excel` extension** (operator 2026-10-22: `parser.excel` is delivered, `ProcessorCatalog.java:114`; without the extension the picker says "save as CSV"). Limits through the configurable-limits catalogue: 500 MB per file, 10 million rows per import, 20 imported Datasets per Space. Corrects the 2026-10-21 "no native Excel reader today". |
| DT-15 | **Expectations, quarantine and dedupe stay on Basic** (LA A-13: "D-PE5 wins"). **Reverses** the 2026-10-21 table (import checks only on Basic). Standard adds delivery Alerts and data trust. |

### 5.2 Recommendations awaiting the operator

| # | Decision | Recommendation |
|---|---|---|
| DT-3 | **"Point at a file in place" (no copy) is NOT offered** for now. | Yes; revisit on demand for very large files |
| DT-5 | **No scheduling of any kind in Basic** — not even a daily re-import. Manual re-import only. | Yes, refined by DT-16 and DT-17 |
| DT-7 | **No-code shaping is in Basic** (types, rename, choose columns, filter rows); anything that needs a Step Processor or SQL is Standard. ⚠ **Conflict between the drafts:** the 2026-10-22 draft (D-PE5) kept schema `expression:` columns on Basic as part of schema landing; this wording moves them to Standard because they are SQL. | Shaping as worded, plus **dedupe** (DT-15). **Keep schema `expression:` columns on Basic**: they belong to schema landing, A-13 kept D-PE5's core set on Basic, and `orders-starter` uses two. `transform.explode` (exploding nested arrays into rows) is a Step: Standard; flattening by record path and dotted selectors stays a parse capability in Basic |
| DT-9 | **Existing configurations that use Standard features fail with a clear refusal on Basic**, naming the feature. Breaking changes are free; no shim. | Yes, except a trigger-less Pipeline (DT-17) |
| DT-10 | **Each application gets its own Basic form, decided per application** (Inspecto LA: the explorer). The others default to Standard and above until a free form is chosen. | Yes, under DT-11 |
| DT-16 | **What counts as automation.** | Continuous triggers, `EVENT` triggers, Jobs on cron / `on_pipeline` / `on_signal` (closing the hole where a signal-driven Job such as `la.index.build` still fires after a manual commit) and incremental recompute are Standard; on Basic an enrichment recomputes its full window when run by hand. **Maintenance Jobs stay automatic on Basic**, or retention never runs and storage grows without limit. Watermarks and the dedup ledger stay in every edition |
| DT-17 | **A trigger-less Pipeline on Basic** | **Never polls, is not refused**: refusing the default form would break every legacy config and both Basic packs. Explicit interval, cron, stream and `EVENT` triggers are refused (DT-9) |
| DT-18 | **Content packs on Basic** | `la-daily-feed` (polling, a commit-signal index build, Alert Rules) moves to Standard — this settles the undecided §3.8 board note of 2026-10-18 that kept it on Basic; `orders-starter` stays on Basic as a manual pack with the load-and-run button (DT-14), its derived column kept if DT-7 keeps expression columns |
| DT-19 | **Kafka** | Stays Standard: a mainstream integration that keeps Standard a complete automated product |

**Operator answers A-1…A-16** (LA use cases §8) not listed above concern Inspecto LA and onboarding (Spaces per
data domain, the primary entity per Dataset, the sample Space, beginner measures, analyst uploads per Space,
masking defaults); `ONBOARDING-FIRST-RUN-1` carries the ones it needs.

## 6. Gates (capabilities)

| Feature | Covers | Declared by | Gate kind |
|---|---|---|---|
| `pipeline.automation` | Continuous and event triggers, scheduled and signal-driven Jobs, incremental recompute (DT-5, DT-16, DT-17) | `inspecto-ops` (Standard+) | config: save, load, run |
| `pipeline.transform` | Step Processors beyond no-code shaping, SQL steps, enrichment, joins that build new data (DT-6, DT-7) | `inspecto-ops` | config, via a `feature` field on `ProcessorCatalog.Processor` |
| `pipeline.egress` | `objectstore.export`, `publish.postgres` (the webhook, mail and archive gates already exist) | `inspecto-ops` | config: Job save, load, run |
| module `connectors` | Every remote adapter: SFTP, FTP/FTPS, S3, Azure, GCS, the `db` Collector (DT-4) | Offering | module presence (Standard) |
| module `telecom-asn1` | ASN.1 and telecom decoders (DT-12) | Offering | module presence (Advanced) |

Every config feature gets a `refusal()` text (pinned by `EditionFeaturesTest`); every row gets a `/bootstrap`
`features.*` flag and a locked-with-reason control in the console (DT-13). Data quality needs no new gate:
Expectations, quarantine and dedupe stay in every edition (DT-15), Alert dispatch is already `alert.dispatch`, and
data trust is already behind `investigations`. `PII_MASK` (Advanced, `inspecto-policy`) is unchanged.

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
  branches); run-time precedent `RowShaper`'s mask check. If DT-7 moves schema `expression:` entries to Standard,
  the schema save and load path checks them too. Both generated contract JSONs under `inspecto-ui/` are
  regenerated in the same change.
- **`pipeline.egress`.** `ObjectStoreExportJobType` and `PostgresPublishJobType`
  (`platform/inspecto-engine/.../job/`) refuse at Job save, load and run.
- **Remote adapters (module `connectors`).** `offerings/basic.toon` drops `connectors`; a remote Collector kind is
  then unknown on Basic, so the save gate and the Connection routes must answer with the feature-naming refusal
  (DT-9), not a bare unknown-kind error (to check: what they answer today).
- **ASN.1.** `offerings/standard.toon` → `advanced.toon`, the add-on entry. For both Offering moves:
  `node tools/render-offerings-matrix.mjs --check`, `node tools/check-offerings.mjs`, the product map edition items.
- **`/bootstrap` and the console.** `features.pipelineAutomation`, `pipelineTransform`, `pipelineEgress`,
  `remoteSystems`; one shared locked-with-reason component; the editor, Jobs pane, step palette,
  Collect stage and Dataset offers key on them. Plus the DT-14 console work.

## 8. Phases

Each phase: a test per gate that fails without the gate (Basic vs Standard via `EditionFeatures.overrideForTest`,
reset in teardown); `node tools/affected-modules.mjs` for the test set; an independent `verification` PASS; one
lane per push.

| Phase | Content | Depends on | Effort |
|---|---|---|---|
| 1 | **"Add data" import (Basic and up)** — delivered by `ONBOARDING-FIRST-RUN-1` (its O2–O4); this plan owns its edition rules: hidden Collector + Pipeline (DT-2), files only (DT-4), formats, the Excel extension check and limits (DT-8) | decided | see that plan |
| 2 | Groundwork: feature constants, `refusal()` texts, `OpsEditionFeatures` declarations, `/bootstrap` flags, the shared locked-with-reason component | §5.2 answered | 1 day |
| 3 | Automation gate: save and load refusals, scheduler and acquire driver, Job save, Job scheduler and signal dispatcher, enrichment wiring; Activate copy and the manual-run gesture (DT-14) | 2 | 4 to 5 days |
| 4 | Reach: both Offering moves (`connectors` out of Basic, `telecom-asn1` to Advanced), feature-naming refusals for remote kinds, the Collect stage's locked remote systems | 2 | 1.5 days |
| 5 | Egress gate and its console locks | 2 | 1 day |
| 6 | Step catalog `feature` field, refuse walk, schema expression check, palette locks, contract regeneration | 2 | 3 to 5 days |
| 7 | Upgrade path: a test that boots Basic, imports, then boots Standard on the same Space and edits the import as an ordinary Pipeline | 1, 3 | 1 day |
| 8 | Content packs (DT-18), release note, `docs/EDITIONS.md` matrix and rows (JOB-02, ING-01, SP-PRS-06, the remote SP-ACQ rows, JOB-01 / JOB-06, the SP-XFM rows), the edition hubs, the platform whitepaper's edition ladder, user guides | 3 to 6 | 1 day |
| 9 | Per-application Basic forms (DT-10), application by application | — | per application |

Phases 4 and 5 are independent of phase 3 and can be built beside it. Phase 6 goes last: it is the largest and
depends on DT-7. Each new route clears the four endpoint gates.

## 9. What breaks (no shim; release note)

- Existing Basic installs stop polling, scheduling and chaining; their Pipelines run when started by hand.
- Basic configs that use a transformation Step, a remote Collector (the `db` Collector included),
  `objectstore.export` or `publish.postgres` are refused at load, naming the feature (DT-9); schema expression
  columns too if DT-7 moves them.
- Standard installs lose the ASN.1 decoder (DT-12); no shipped template uses it.
- `la-daily-feed` moves to Standard (DT-18).
- Every Space Template that ships a schedule must say "Standard and above" or its Basic seed must skip it (the
  la-showcase seed already skips Investigation steps on Basic, `ceb68fef3`).

## 10. Risks and not confirmed

- **The Excel extension** (DT-8): whether every bundle flavour carries the DuckDB `excel` extension offline; phase
  1 checks it and the file picker follows the answer.
- (The 2026-10-21 risks about the Postgres extension offline and database secrets without sign-in fell away with
  DT-4: Basic imports no database.)
- **Where application data lives in Basic**: imported Datasets sit in the Space store; retention and deletion
  follow the Space.
- **Existing hidden gates.** `7c6d77c80` hides Alerts on Basic, which matches DT-13's lean navigation (a whole
  Standard+ page); controls inside screens follow the locked-with-reason rule.
- Not confirmed: what the save gate, the Connection routes and the pane answer for a remote kind once
  `inspecto-connectors` is absent; whether any shipped
  template uses an `EVENT` trigger; whether `inspecto-connectors`' delivery-status adapters are used by anything
  Basic keeps; the 2026-10-20 line numbers in §7.

## 11. Board

P2 `DATA-TIERS-1` in `BACKLOG.md` §3.8 (hold: operator), indexed in §1 *Decisions owed on individual rows*.

## References

- `docs/EDITIONS.md` (current edition matrix), `docs/archived-documents/plans-archive/inspecto-la-editions-plan.md`
  (the 2026-10-17 edition decisions), `docs/superpower/la-analyst-upload-plan.md` (the existing upload route),
  `docs/okf/product/layer/platform/` (data-layer hubs).
