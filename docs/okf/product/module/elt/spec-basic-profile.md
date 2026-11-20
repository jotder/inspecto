---
type: Concept
title: Basic as a locked profile of ELT — what is shared, what the operator ruled, what is left
description: The as-built map of BASIC-ELT-PROFILE-1 - Basic onboarding runs the ELT path with options locked (one run, one config, one set of engine seams), the operator rulings O1 to O7 and the build rulings, the screens that host core components, the Gate, and the open follow-ups by board row.
resource: platform/inspecto-etl/src/main/java/com/gamma/etl/BasicProfile.java, inspecto/src/main/java/com/gamma/control/ImportPreRunSteps.java, inspecto/src/main/java/com/gamma/control/ImportCommitSteps.java, inspecto/src/main/java/com/gamma/control/ImportFingerprints.java, platform/inspecto-engine/src/main/java/com/gamma/job/ArrivalLoadStep.java
tags: [elt, basic, onboarding, add-data, edition, profile, reuse]
timestamp: 2026-11-20T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-20 last-edit
code: platform/inspecto-etl
---

# Basic as a locked profile of ELT

`BASIC-ELT-PROFILE-1` (planned and built 2026-11-19 to 2026-11-20; the plan is archived as
[`2026-11-19-basic-elt-profile-plan`](../../../../archived-documents/plans-archive/2026-11-19-basic-elt-profile-plan.md),
which keeps the Phase 0 grounding, the per-slice as-built and the refuted premises). The operator's rule for every
change: **Basic is the ELT path with options locked, never a second implementation.** A capability Basic has, a
Standard Pipeline has through the same code; what makes it Basic is which schemes, triggers, Steps and screens the
Edition allows, declared in one place (`BasicProfile`), not by separate code. A change that adds a Basic behaviour
puts it in the engine or the Pipeline config first and lets Add data call it.

What a Basic import is: an ordinary registered Pipeline (`origin: import`, its schema and mapping written by
`OnboardImport.Plan`), run by `CollectorService`, so ingest, commit, quarantine, duplicate markers and the status
ledgers are the engine's own. An upgrade to Standard shows it as an editable Pipeline.

## What runs where now

| Basic behaviour | Now an ELT seam | Detail |
|---|---|---|
| Follow-on work after a commit (the link index, Expectations, compaction) | An ordered registry of commit steps, `COMMIT` and per-run `RUN` phases (`ImportCommitSteps`); Add data calls the same list in-run (BE-1a) | [spec-pipeline-execution-run-steps.md](spec-pipeline-execution-run-steps.md) |
| Judging the inbox and keeping a new column before a run | An ordered registry of pre-run steps, `GO` / `SKIP` / `then(after)` (`ImportPreRunSteps`, BE-1b); one engine step, `import.pre_run`, runs it for a run Add data did not start | same page |
| *Watch this folder* (B1) | The locked preset of *Keep this up to date*: the import Pipeline's `trigger.every: 30s` on a `local` Collector with `stability {30s, 2}`; the scheduler honours each Pipeline's own `every`; `InboxWatch`'s poller is gone (O1) | [architecture-watch-this-folder.md](../la-core/architecture-watch-this-folder.md) |
| Refusing the same bytes (B3) | The Collector's `duplicate.mode: content` and `AcquisitionLedger.findByChecksum`; `acquire.ledger.backend` defaults to `db`; Add a file's `409` asks the same ledger (`ImportFingerprints`) | [architecture-data-onboarding-arrivals-storage.md](../la-core/architecture-data-onboarding-arrivals-storage.md) |
| *Replace the period*, *Replace everything* (B4, B13, O6) | The engine's `ArrivalReplacer` (journal, one swap, heal, `REPLACED` registry state); the Pipeline key `output.load` run per Consignment by `ArrivalLoadStep`; Add a file passes a run-scoped choice | [spec-arrival-replace.md](spec-arrival-replace.md) |
| Compaction and retention (B6) | `compact` `mode: arrivals` (`ArrivalCompactor`); merged-away files are `COMPACTED_AWAY` | [spec-arrival-compaction.md](spec-arrival-compaction.md) |
| Keeping or leaving out a new column (B5) | One drift comparison, `SchemaDrift.diff` / `settle`; the schema is written through `DatasetFeed.rewriteSchema` with save gates and history | run-steps page |
| Format and type sniff (B7) | The engine's `SampleProfiler`; the preview, schema suggest and Add data give one type answer | [spec-parsing-sample-profiler.md](spec-parsing-sample-profiler.md) |
| A Postgres table copy (B8, O7) | The `postgres` connector scheme (`PostgresConnectorFactory`), a saved Connection per snapshot, one idempotent Pipeline switch | [api-onboarding-postgres-snapshot.md](../processor/api-onboarding-postgres-snapshot.md) |
| Which options Basic locks (Gate) | `BasicProfile`: kept schemes, the watch preset, kept Steps, `/bootstrap` screen flags and Basic's entry points; read by `/bootstrap`, `ProcessorCatalog`, `GET /jobs/types`, the feed and storage routes | [spec-editions-tiers.md](../../layer/platform/spec-editions-tiers.md) |

What stays Add data's own (it has no ELT twin): the review's step 3 (Entity Types, roles, the link), the draft and
commit of the import, the per-column mapping, *Keep only some rows* (it writes the engine's own
`processing.csv_settings.where`) and the arrivals list (a read over the engine's file ledger plus a sidecar).

## The screens

Add data lives in the core UI library (`inspecto-ui/src/app/inspecto/add-data/`; UI-0 moved it, Link Analysis places it
by import). Each row below hosts a core component or shares one vocabulary; the request bodies are pinned byte for byte
in the specs so a screen change cannot change a commit.

| Row | As built |
|---|---|
| U1-types | Basic's column types are `BASIC_COLUMN_TYPES` and `BASIC_COLUMN_TYPE_LABELS` in `inspecto-ui/src/app/inspecto/schema/basic-column-types.ts`: the named subset of `SCHEMA_TYPES` an import commit accepts (`OnboardImport.TYPES`). A spec asserts subset of `SCHEMA_TYPES` and equal to the Java set (hard-coded, change both). No visual change. |
| U1a | Step 2 (*Check the columns*) is `<inspecto-schema-metadata-grid mode="types">`: one row per column (`selector`, mapping name, type, *Leave out*) with the host's evidence and Confirm slot under each kept row. The host writes edits into its **mapping**, never `raw.fields[]` or `transform.sql` (O3), so the commit body is unchanged ("U1a parity"). Feed `typeRows` from the host's state on every change: the same selectors patch in place and an open name box keeps its caret. |
| U2 | `inspecto-connection-fields` is shared by the Connection dialog and Add data's *Choose a table* (`POSTGRES_CONNECTION_ATTRS`, password from `INSPECTO_PG_` only); the dialog edits a `postgres` Connection as itself, with no Routing. The test and the table list stay on `/onboard/postgres/*`. |
| U3 | The Grammar gains `decimal_separator` and a Parquet format; an import's `csv_settings.encoding` shows on the Encoding row. Add data's format labels come from `parsingAttributesFor('delimited')` and only a picked format value is stated. The server sniff shows per row ("Sample resolves to"). Share the seam, not the component: the grammar editor was a poor host. |
| U4-Keep | *Keep this up to date* hosts `<inspecto-collector-config>` (`[specs]` = the entries its route saves: the Connection and the ready marker; `[modes]` = `local`, `connection`; `[connectors]` = `sftp`, which filters the Connection list and makes `resolveConnector()` refuse another type). `stability.ready_marker` and `stability.size_checks` joined the Collector spec (`NodeAttributes`, the contract JSON and `COLLECTOR_ATTRIBUTES` together; `NodeConfigNameContractTest`), with no default, because a seeded key would add a `stability:` block and its presence turns the gate on. The *Check* interval stays Add data's own picker. |
| U4-Watch | Nothing to move: after B1 the switch is a run switch over server state (`PUT /datasets/{id}/watch {watch}`), the feed keeps its shape, and the preset's cadence is fixed, so no Collector surface shows. Both bodies are pinned byte for byte. |

Two habits the lanes settled on: a host of a shared component narrows it through inputs, and the shared component
refuses what its host cannot save (U4-Keep's `[connectors]`); and one rule for an Edition lock is read by both the
console and Add data (`connectorLock()` in `connection-types.ts` for Postgres).

## The operator's rulings (2026-11-19 and 2026-11-20)

- **O1.** *Watch this folder* becomes a scheduled `local` Collector and `InboxWatch` retires; DT-17 is reversed for
  import inboxes. Per-file stability replaces the whole-inbox settle rule. The scheduler honours each Pipeline's own
  `every`; the approval policy over Pipelines also covers the watch switch.
- **O2.** DT-9 stays as narrowed: the Gate lives in screens and Basic's routes, nothing is refused on load, and
  `output.load` is not refused on a Basic save either. The duplicate check (`collector.duplicate.mode`, `on_change`) is
  declared Basic's: it stays editable.
- **O3.** Rename and *Leave out* stay in the mapping.
- **O4.** A Postgres snapshot keeps one saved Connection, `<id>_postgres`, restricted to `INSPECTO_PG_` secrets, with
  the table or query in its options; the upload right is enough and the Connection is visible. An old inline snapshot
  migrates at the Pipeline switch.
- **O5.** ELT's wording (`parsingAttributesFor`, `TYPE_META`) is the one wording for formats and types.
- **O6.** The arrival mode is a Pipeline key `output.load: append | replace | period` with `output.period_column`, run per
  Consignment by a `COMMIT` step calling `ArrivalReplacer`; Standard may set it on a single local Parquet store only;
  Basic never writes it (Add a file passes a run-scoped choice; a watched feed stays `append`). A replacement that
  fails after the commit keeps the committed rows and logs ERROR (no Incident).
- **O7.** The snapshot Pipeline's switch to `collector.connector: postgres` waited for O6; one idempotent switch serves
  the commit and the migration (an old snapshot migrates at its first Run now), with Run now passing a run-scoped
  `replace`. A failed *Replace everything* undoes only the replacement. A snapshot has no feed: *Keep this up to date*
  and *Stop the feed* are refused `422`, as *Watch this folder* is.
- **Sniff and types.** Add data follows the sniff; `quote: ""` is written only when the first file holds a `"`. Dates in
  words and ISO timestamps with `T` are date or timestamp everywhere; `TIME` joins Basic's column types and registers
  as Dataset type `date`; a decimal comma is a number under the Grammar's `decimal_separator`; a column that is almost
  all numbers is text everywhere (a column that is almost all dates stays a date). The Link Analysis demo losing
  `amount` as its default link weight is accepted.
- **B3 and B4.** `acquire.ledger.backend` defaults to `db` on every edition; Standard's schema drift stays
  detection-only; a waiting file reusing a taken name with other bytes goes to `refused/` by default; an import left on
  `path` still answers Add a file's `409` from the ledger. The reprocess refusal is a path-free `409`; `REPLACED` is
  public and stays readable; an interrupted swap heals forward, a lost rewrite or a mid-restore crash heals back.
- **Out of scope.** New Basic capabilities (transformation, user schedules,
  other databases) stay out (`BASIC-ONBOARD-1` §7); renaming the build module `inspecto-etl` is GLOSSARY §13 debt.

## Seams worth knowing

- **One claim per import.** `OnboardImport.IN_FLIGHT` is the control plane's claim; Add data, a rewrite and a delete
  take it, and an engine run of an import takes it in its pre-run step. A run-scoped choice (`ArrivalLoadStep.override`)
  applies to whatever commits into the store while it is open, so every run but Add data's own is refused while Add
  data holds the Dataset (`PreRun.refused`; spec-arrival-replace).
- **The engine writes one output per file name** (default `output_per_member` off), so two files of one name overwrite
  each other's rows; an import refuses such a file, a Standard Pipeline does not (`OUTPUT-NAME-OVERWRITE-1`).
- **Journals and heals run before work, not at start-up:** a replace's heal runs before every replace, compaction and
  import run (see the open rows below).
- **A snapshot's run outside Add data** replaces at commit through `output.load: replace` and names each export apart
  (`<table>_<stamp>.parquet`); Run now keeps its own export for the column check, the `import.max_rows` cap and the 422
  wording.
- **The ledger is durable now:** a content-mode import survives a restart; *Replace everything* forgets the import's
  fingerprints before its run and puts them back if it is undone.

## Tests that pin the profile

`ControlApiBasicProfileGateTest` (each entry point on Basic and Standard over HTTP; what an offered one writes stays in
the profile), `BasicProfileTest` (including the console's `REMOTE_CONNECTORS` and `SCREEN_LOCK_FALLBACK` mirrors),
`ProcessorCatalogContractTest` (every acquisition names its scheme), `ControlApiImportPreRunStepsTest`,
`ControlApiImportCommitStepsTest`, and the per-seam tests named on the pages above. The UI specs pin the request bodies
and the type subset.

## Open work

Each follow-up is a board row in [`docs/BACKLOG.md`](../../../../BACKLOG.md) (§3.4 *Onboarding & bundles*); the hub's
Open work list in [index.md](index.md) is generated from it.

| Row | What is left | Owning page |
|---|---|---|
| `IMPORT-RUN-REFUSAL-GAPS-1` | Two kinds of run still commit while Add data's choice is open; a held claim refuses every manual run | [spec-arrival-replace.md](spec-arrival-replace.md) |
| `SNAPSHOT-OUTSIDE-RUN-NOW-1` | A switched snapshot's run outside Run now has no column check or row cap; an old feed on a snapshot cannot be stopped | [api-onboarding-postgres-snapshot.md](../processor/api-onboarding-postgres-snapshot.md) |
| `ARRIVAL-HEAL-AT-START-1` | No heal at start-up after a crash | spec-arrival-replace.md |
| `REFERENCE-LOAD-REPLACE-1` | `reference.load: replace` and Add a file's *Replace everything* are two ways to replace one store | spec-arrival-replace.md |
| `ARRIVALS-STANDARD-STORE-1` | Arrivals mode for a Standard Pipeline's own store; the run claim in the engine (B11) | [spec-arrival-compaction.md](spec-arrival-compaction.md) |
| `REPLACED-STATE-READERS-1` | Four readers of the registry ignore `REPLACED` | spec-arrival-replace.md |
| `IMPORT-NAME-CHECK-LEFTOVERS-1` | The name check misses a path with a long digit run; each waiting file is hashed twice | architecture-data-onboarding-arrivals-storage.md |
| `BASIC-PROFILE-TIDY-1` | Two copies of one constant, an unused constant, a stray javadoc | this page |
| `FEED-INDEX-JOB-ENGINE-1` | *Keep this up to date* still offers its own `la.index.build` Job | run-steps page |
| `ADD-DATA-DECIMAL-SEPARATOR-1` | Add data writes a decimal-comma column as a per-column expression, not `decimal_separator` | [spec-parsing-sample-profiler.md](spec-parsing-sample-profiler.md) |
| `PIPELINE-SCHEMA-HISTORY-ROUTES-1` | An import's schema history is not visible or restorable through the API | run-steps page |
| `KEEP-COLUMNS-ENCODING-1` | The rewrite that leaves a new column out reads and writes UTF-8 only | run-steps page |
| `ADD-DATA-DUPLICATE-CHECK-UI-1` | Add data cannot set the duplicate check (needs a design) | architecture-data-onboarding-arrivals-storage.md |
| `PIPELINE-EDITOR-OUTPUT-LOAD-1` | The Pipeline editor cannot set `output.load` (needs a design) | spec-arrival-replace.md |
| `BASIC-ELT-DUPLICATES-1` | Duplications Phase 0 found that were not built: column roles in three editors (D2), the row filter written twice in Add data (U5), two Pipeline scaffolds and two name rules (D3, D4), Add data's own Edition locks (D5 residue), the upload into the inbox (B9), followed runs (B10), the ledger-diff verdict (B15) | this page |
| `WATCH-KEEP-TWO-CONTROLS-1`, `CONVERT-TYPE-DECIMAL-MARK-1`, `GRAMMAR-NO-QUOTE-1`, `OUTPUT-NAME-OVERWRITE-1`, `REPROCESS-ARCHIVE-MEMBER-1` | Filed during the build | their own pages |

The details of the first six rows sit in each owning page's *Not done here* section; the two smallest groups are
here:

- **`BASIC-PROFILE-TIDY-1`:** `InboxWatch.EVERY` and `EVERY_MS` keep their own copy of `BasicProfile.WATCH_EVERY`
  (a test pins the two equal); `OnboardImport.AUTOMATION_FEATURE` is declared but read by tests only; the first javadoc
  above `everyAcquisitionProcessorIsJudgedByTheBasicProfileThroughItsScheme` in `ProcessorCatalogContractTest` is
  attached to nothing.
- **`BASIC-ELT-DUPLICATES-1`:** the list is from Phase 0 (2026-11-19) and was not re-read when the plan closed; read the
  code before acting on a line. The archived plan §5.1 and §5.2 hold the evidence.
