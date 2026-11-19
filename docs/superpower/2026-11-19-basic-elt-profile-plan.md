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
triggers, Steps and screens the Edition allows — enforced in one place (one Basic-profile declaration read by the
Edition features' screen flags and Basic's routes; DT-9 as narrowed — see §5.3 O2), not by separate code.

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
| **Gate** | Basic's locks (schemes, triggers, Steps, screens) in one Basic-profile declaration read by Edition features' screen flags and Basic's routes (DT-9 as narrowed: no refuse-on-load, §5.3 O2); a test that each Basic entry point refuses each Standard-only option. | BE-1…BE-4 | S |

Parallel now: P0-BE, P0-UI, UI-0. Each build phase is its own lane, integrated and pushed one at a time.

**Revised by Phase 0 (2026-11-19)** — the order the build follows, each first slice safe to ship alone:

| Order | Phase | First slice | Blocked by |
|---|---|---|---|
| 1 | **BE-1a** commit-step registry (B2, B14) | `CollectorService.commitStep` → an ordered, per-run-coalesced list; compaction, follow-ons, Expectations registered; Add data calls the same function in-run | — |
| 1 | **BE-3** sample profiler (B7) | extract the dialect sniff as a public engine service; `OnboardDraft` calls it, keeping its fallbacks; type unification next, with golden diff tests. **Type rulings built 2026-11-19** (`lane/basic-u3-types`): one type answer in the preview, suggest and Add data (`SampleProfiler.types`), Basic gains `TIME` (spec-parsing §P.3.6) | — |
| 1 | **UI-1 U1-types** | Basic's column types move to `core/schema` as the named subset of `SCHEMA_TYPES` | UI-0 |
| 2 | **BE-1b** pre-run seam, then B1 | pre-run step runs `judgeInbox` + `settleNewColumns` for scheduled import runs; then *Watch* = locked Keep preset | O1 for B1 |
| 2 | **UI-1 U4-Keep** | ready marker + size checks into the Collector spec; host `collector-config` for *Keep this up to date* | — |
| 3 | **BE-2** B6 → B4 → B3 → B5 | B6: engine owns the merge map, `compact mode: arrivals`, registry marking. **B3 part 1 built 2026-11-20** (`lane/basic-be2-b3`): engine `collector.duplicate.mode: content` (`DuplicatePolicy.Mode.CONTENT`, `AcquisitionLedger.findByChecksum`: the same bytes under any name, two copies in one cycle; a Standard option in `ConfigSpecs` and the Collector editor, every existing mode unchanged); `BASIC-WATCH-ARRIVALS-1` closed (an engine run of an import writes its arrivals lines; a watched run that sets a file aside says so in `watchError`); the `InboxWatch` U4-Watch comment. **Left (operator call):** Add a file's 409 and the import Pipeline on `content` — the ledger defaults to memory (`acquire.ledger.backend`), so the refusal would not survive a restart as the sidecar's does, and *Replace everything* must forget the ledger's bytes | BE-1a |
| 3 | **UI-1 U1a, U3** | compact types mode; shared format wording, merged sniff after BE-3. **U3 built 2026-11-19** (`lane/basic-u3-types`): the Grammar gains `decimal_separator` and a Parquet format (encoding and `records_path` were there; an import's `csv_settings.encoding` now shows on the Encoding row); Add data's format labels come from `parsingAttributesFor('delimited')`; only a picked format value is stated. The server sniff already shows per row ("Sample resolves to"). **Left:** Add data still writes a decimal-comma column as a per-column `EXPR` mapping, not the key (the writer is `OnboardImport.Plan.pipeline`, another lane's file); TIME is not yet a type a later file's new column settles to (`DatasetNewColumns`, BE-1b's file) and the Dataset entry calls a TIME column `string` (`OnboardImport.datasetType`) | O3, O5 |
| 4 | **BE-4** B8 | `postgres` connector factory called by `refresh`; Pipeline switch after B4. **Part 1 built 2026-11-19** (`lane/basic-be4-postgres`): the scheme (`PostgresConnectorFactory`, engine), test/draft/Run now through it, a saved `postgres` Connection `<id>_postgres` per snapshot, pre-BE-4 inline snapshots read as they are. Left: the Pipeline switch, `ProcessorCatalog`/Gate exemption | O4 |
| 4 | **UI-1 U2, U4-Watch** | after BE-4 / BE-1b. **U2 built 2026-11-19** (`lane/basic-u2-postgres-form`): `inspecto-connection-fields` shared by the Connection dialog and Add data's *Choose a table* (`POSTGRES_CONNECTION_ATTRS`, password `INSPECTO_PG_` only); request bodies pinned byte for byte; the test and copy stay on `/onboard/postgres/*` (probe/explore need a saved Connection, `postgres` has no workbench); the dialog edits a `postgres` Connection as itself, with no Routing. **U4-Watch built 2026-11-19** (`lane/basic-u4-watch`): nothing left to move in the UI. After B1 the switch still reads `watch` / `watchable` and sends `PUT /datasets/{id}/watch {watch}`; the server keeps the feed's shape (`feed.kind: uploads` while watched), so the inbox words stay right, and the preset's cadence and stability are fixed (30 s, 30s/2), so no Collector surface is shown (P0-UI: keep the switch). Built: both bodies pinned byte for byte (`{"watch":true}`, `{"watch":false}`), B1's watched answer switched off, the `watch` / `watchable` comments. **Left:** `InboxWatch`'s javadoc still says the shape holds "until the UI's U4-Watch slice" (a backend comment, next backend lane) | |
| 5 | **Gate** | one Basic-profile declaration read by screen flags, `ProcessorCatalog` and Basic's routes; a test per Basic entry point; nothing refused on load | O2 |

New rows for later: U1b (step 3 stays LA; converge D2), U5 (UI-only row-filter duplicate), D3/D4 (one scaffold and
name rule for Add data and the catalog entry), B9–B11 (upload, followed runs, run claim onto the engine's).

## 5. Phase 0 findings

### 5.1 P0-UI (verified 2026-11-19 against `d1120c864`; spot-checked)

Short forms: `add-data/` = `inspecto-ui/projects/link-analysis/src/add-data/`, `core/` = `inspecto-ui/src/app/inspecto/`.

**Corrections to §3.2.** Add data's production code is about 5,640 lines (the 9,400 counted specs and the testing
helper). `schema-metadata-grid` has no production host (only `/design`); `collector-config` has one host (the
Pipelines Collector pane); the angular-ui skill's "Onboarding Collection/Parsing stage" adopters no longer exist.
**Basic has capabilities ELT lacks:** the server accepts `decimal`, `encoding` and JSON `recordPath` for an import
(`OnboardRoutes.java:41,415-435`, `OnboardImport.java:408-409,574-577,627-628`) — though Add data's UI never sends
them (`FormatOverrides`, `add-data.model.ts:205-209`) — the Grammar has no decimal-mark key, and the grammar editor
has no Parquet format (`parsing-attributes.ts:115-121`), so an upgraded Parquet import would show as delimited
(inferred).

| Row | Verdict | Target and conditions | Size |
|---|---|---|---|
| **U1** column review | **Split.** U1a (schema half): Add data's 6 types are a subset of `SCHEMA_TYPES`; the grid could host a compact `types` mode with an evidence/Confirm slot and a per-row change output. **Blockers:** Add data's rename and *Leave out* write the **mapping** (`OnboardImport.java:557-591`), while the grid edits `raw.fields[]` only and D8 puts dropping a column in `transform.sql` (spec `schema-fields-editor.component.spec.ts:88`). U1b (step 3: Entity Types, roles, link) has no core counterpart and stays an LA panel; its parallels are Studio `dataset-columns` and LA `entity-columns` (see D2). | U1a after decision (a) | L (U1a M) |
| **U2** Postgres form | **Do not host before BE-4.** `connection-form` is a dialog, saves a named Connection, tests reachability only, and **locks `db` on Basic** (`connection-types.ts:225`) while Add data offers Postgres on Basic (D5). Once a snapshot keeps a normal Connection: extract an embeddable `connection-fields` piece; move the read-only and table-list checks to the database probe/explore routes. The table/query picker stays Add data's. | after BE-4, decision (c) | M |
| **U3** format detection | **Poor fit to host** (the editor would be almost entirely hidden; `previewFn` cannot return a draft). **Share the seam, not the component:** one server sniff (BE-3) shown as the editor's suggestion too, and one dialect vocabulary (Add data's choices from `parsingAttributesFor('delimited')`). Add Parquet (and decimal / encoding / `recordPath`) to the Grammar. | wording now; merge after BE-3; decision (b) | S → M |
| **U4** watch / feed | **Split.** *Watch this folder* is a run switch over server state, not Collector authoring: keep it; after BE-1 its PUT writes `trigger.every: 30s` on the local Collector (reuse happens in the backend). *Keep this up to date* (Standard) is a direct `collector-config` parallel — the server already writes an ordinary `collector:` block (`DatasetFeed.java:203-250`); host `collector-config` with restricted modes once `stability__ready_marker` / `stability__size_checks` join the Collector spec (Java `NodeAttributes`, the contract JSON and `COLLECTOR_ATTRIBUTES` together). | Watch after BE-1; Keep now | S / M |

**Duplication the plan missed (new rows):**

| # | Duplication |
|---|---|
| U5 | *Keep only some rows* writes `processing.csv_settings.where` (`ImportRowFilter`); ELT has the `transform.filter` Step's `where` with `app-pipeline-filter-predicate`. Inside Add data the condition rows are written twice (`add-data.component.html:343-433`, `import-filter-edit.component.ts`). Needs a backend decision. |
| D2 | Three editors for column roles / Entity Types: Add data step 3, Studio `dataset-columns` (own `ROLES`), LA `entity-columns`. |
| D3 | Two Pipeline scaffolds: server `OnboardImport.Plan.pipeline` and UI `component-model/pipeline-scaffold.ts` (different default inbox). |
| D4 | Two name rules for one Pipeline id: `DATASET_NAME` (`add-data.model.ts:610`) vs `onboarding-create.dialog.ts:299-306`. |
| D5 | Two sources for the Edition lock: `AddDataAccess` reads `LA_FEATURES`; core reads `LensService.screenLocks()` — they disagree on Postgres for Basic. **Postgres closed by U2:** one rule, `connectorLock()` in `connection-types.ts`, read by the dialog and `AddDataAccess.postgresLock`. Still open: `AddDataAccess`'s other locks lock a missing `/bootstrap` flag on Basic where core offers it, with different fallback wording. |
| D6 | The feed's SFTP picker filters `ConnectionsService.list()` by hand instead of `connectionOptionLoader`. |

The catalog `onboarding-create.dialog` is **not** a third implementation: it is the Standard entry into ELT (it
scaffolds a Pipeline and opens the Pipelines editor), but it is a third entry point with its own scaffold and name
rule (D3, D4) that should converge with Add data's.

**Operator decisions before UI-1:** (a) where Basic's rename and *Leave out* live — the mapping (today) or
`transform.sql` (D8); (b) one wording for the format choices and the type labels; (c) whether a Postgres snapshot
keeps a saved Connection (BE-4's shape).

**UI-1 order:** U1-types (S, first slice: Basic's `ColumnType` / labels move to `core/schema` as the named subset of
`SCHEMA_TYPES`, a spec asserts subset ⊂ `SCHEMA_TYPES` and = `OnboardImport.TYPES`; no visual change) → U4-Keep (M)
→ U1a (M, after (a)) → U3 (S → M) → U2 (M, after BE-4) → U4-Watch (S, after BE-1). U1b and U5 are not UI-1 work.

### 5.2 P0-BE (verified 2026-11-19 against `d1120c864`; spot-checked)

Paths under `inspecto/src/main/java/com/gamma/control/` unless named.

**Cross-cutting findings.**
1. **ELT already has Basic's watch under another name.** Standard's *Keep this up to date* rewrites the import
   Pipeline into a `local` Collector with `stability {30s, 2}`, `duplicate {checksum, SHA256, ignore}` and
   `trigger {schedule, every}` through the full write path (`DatasetFeed.java:240-261`, `rewritePipeline` `:314-359`).
   So B1 is *Watch = a locked preset of Keep this up to date*, not a new Collector.
2. **A scheduled ELT run skips everything Add data adds around its run** (before: name check and set-aside, new
   columns; after: follow-on, Expectations, the arrivals sidecar, `watchError`). The engine's only seam for it is
   `CollectorService.commitStep` — ONE static `BiConsumer`, overwritten by each call
   (`inspecto/.../service/CollectorService.java:157-166`), used today by compaction alone (`DatasetCompaction.java:88`).
   **Most of BE-1 and BE-2 is: make it an ordered list of commit steps, plus a matching pre-run step.**
3. **Two recorded operator decisions conflict with this plan** (`archived-documents/plans-archive/data-tier-editions-plan.md`):
   DT-17 (an import's Pipeline stays `trigger: manual`; the 30 s poll is `InboxWatch`, not a trigger) — B1 reverses
   it; **DT-9 narrowed** ("gate in screens, not on load: Basic runs any configuration it is given") — a Gate in the
   config-safety validator reverses it. §4 below is revised to keep DT-9; both need the operator's ruling (§5.3).
4. **The run reuses the engine, its verdict does not:** `runChecked` drops the `RunResult` of `runPipeline`
   (`OnboardImport.java:897`) and re-derives the outcome by diffing the status ledgers (`:883-936`); `import_steps`
   (`:102`) names four Steps no engine code reads.

| Row | Verdict | Better target | Size, depends on |
|---|---|---|---|
| **B1** `InboxWatch` | Real. Whole-inbox settle rule vs the engine's per-file `StabilityGate`; ELT lacks a ≤30 s tick (Space tick floor 60 s, `SchedulerRoutes.java:528`; feed minimum 1 m, `DatasetFeed.java:58`), all pre/post-run work, and the `data.self_import` / `IN_FLIGHT` checks (inferred). | *Watch* calls `rewritePipeline` with a locked preset (`local`, `every: 30s`, stability 30s/2); 30 s tick or per-Pipeline cadence; pre-run seam runs `judgeInbox` + `settleNewColumns` for `origin: import`; delete `InboxWatch`, migrate `watchInbox`. Per-file instead of whole-inbox is a user-visible change. | M; after B2 + pre-run seam; **reverses DT-17** |
| **B2** `ImportFollowOn` | Partly. ELT's "post-sync Step" is the asynchronous `consignment.process` lane (no processors ship) and cannot put a result in the person's answer (DT-16). | `commitStep` → an ordered, per-run-coalesced registry; compaction, follow-ons and Expectations register there, gated by `import_steps: follow_on` (making the key real); Add data calls the same function in-run. Standard feeds gain the index and Expectations at commit; `DatasetFeed.indexJob` and the double build go. | S–M; **first build slice** |
| **B3** `DatasetArrivals` | Only the duplicate check is a parallel (the arrivals list is a read over the engine's file ledger + a sidecar). Plan's key was wrong: content checking is `collector.duplicate.mode`; the ledger is keyed by path, so a unique `add_<stamp>_…` name never matches; the engine skips quietly where Basic refuses. | a content-identity `DuplicatePolicy.Mode` + `AcquisitionLedger.findByChecksum`; Add a file's 409 calls the same lookup. Standard gains re-sent-under-a-new-name detection. | M; independent |
| **B4** `DatasetPeriod` | Not a parallel of revisions (those supersede whole files in the at-rest lane). No row-level replace-by-day exists in the engine; commits are append-only. | an arrival mode `append | replace | period(column)` executed as an engine commit step; needs the merge map in the engine (B6). | L; after B6, B2; data-loss risk |
| **B5** `DatasetNewColumns` | Real; ELT only *detects* drift (`SchemaDrift`, delimited only). `grow` writes the schema with a bare `AtomicFiles.write` (`DatasetNewColumns.java:207`) — no SaveGate, no history (a defect against the write-path rule). | one engine drift service (unify `checkColumns`, `typed`, `SchemaDrift`, `SchemaSuggest.Drift`); the schema write goes through `rewritePipeline`; triggered by the pre-run seam. | M–L; with B7 |
| **B6** `DatasetCompaction` | Same concept, different semantics: ELT `compact` (`PartitionCompactor`) merges per partition by age, no arrival order, no map, no retention, but marks `COMPACTED_AWAY` — which `DatasetCompaction` does not (a reprocess may duplicate rows today, inferred). | engine maintenance variant `compact` `mode: arrivals` owning the merge map; two triggers (the B2 commit step in-run, and a Job). | L; after B2 |
| **B7** `OnboardDraft` | Real, and **three** implementations: `OnboardDraft` (`sniff_csv` + its own delimiter, encoding, decimal, confidence), `ComponentPreview.sniffDialect` / `sniffColumnTypes`, `SchemaSuggest.infer` (TRY_CAST voting, own date formats). | an engine `SampleProfiler` (dialect + types with evidence) all three call. The Parse pane gains confidence, encoding and decimal detection. | **M** (not S); independent |
| **B8** `PostgresSnapshot` | Real. Precedent for a driverless engine scheme: `DatasetCollectorConnectorFactory`. Gaps: `ConnectionProfile` resolves any `${ENV:…}` (inferred) so the `INSPECTO_PG_` rule must move into the scheme; `REMOTE_ACQUISITION` needs an exemption; replace-everything is still control-plane (B4). | a `postgres` `CollectorConnectorFactory` (DuckDB, typed Parquet, env prefix enforced) that `refresh` calls first; the Pipeline switches to `collector.connector: postgres` after B4. | M; switch after B4 |

**Missed parallels (new rows):** B9 the upload into the inbox (`OnboardRoutes.receive` + `landFromDraft`) vs
`POST /pipelines/authored/{id}/inbox`; B10 followed runs (own 2-thread pool, `RUNS`, `GET /onboard/runs/{id}`) vs the
engine's 202 + `runId` contract; B11 the `IN_FLIGHT` claim vs `runGuard` (the scheduler ignores `IN_FLIGHT`); B12
`checkColumns` / `judgeInbox` + `refused/` vs `SchemaDrift` + quarantine (joins B5); B13 *Replace everything*
`setAside` vs `reference.load: replace` (joins B4); B14 in-run Expectations vs `ExpectationEvaluateJobType` (joins
B2); B15 the ledger-diff verdict; B16 the schema write without SaveGate (B5); the Postgres `_snapshot.toon` inline
connection vs Connection profiles (joins B8). No parallel: `ImportFilterEdit` (uses `rewritePipeline`),
`ImportRowFilter` (writes the engine's own `csv_settings.where`, which `PipelineEditable` shows as `transform.filter`
— this narrows U5 to a UI-only duplicate), `DatasetCoverage`, `DatasetEntryRules`, `DatasetRegistration`,
`OnboardMatches`, `OnboardFixes`, `OnboardJson`.

**Edition gating today:** `EditionFeatures` refuses on save (`pipelineRefusals(draft)`, via `SaveGate.editionFindings`)
and on load (`requirePipeline`, `ConfigRegistry.java:166`) — today only archive, DuckLake and `mask` — plus
`SCREEN_FLAGS` and `ROUTE_FEATURES`; `ProcessorCatalog` lists Standard Steps and acquisitions (`NO_CODE_STEPS`,
`REMOTE_ACQUISITION`); `ConfigSafetyValidator` has no edition awareness. Missing for a Gate: trigger flags, one
Basic-profile declaration, the `postgres` exemption, telling a Basic entry point from hand-authored config.

### 5.3 Decisions for the operator

**Ruled 2026-11-19 (operator):** O1 **yes** — *Watch this folder* becomes a scheduled `local` Collector and
`InboxWatch` retires (DT-17 reversed for import inboxes); O2 **keep DT-9 as narrowed** — the Gate lives in screens and
Basic's routes, nothing refused on load; O3 **the mapping** — rename and *Leave out* stay in the mapping; O4 **yes** — a
Postgres snapshot keeps a saved Connection restricted to `INSPECTO_PG_` secrets. O5 not asked: ELT's wording is used
unless the operator objects.

| # | Decision | Recommendation |
|---|---|---|
| O1 | DT-17: may *Watch this folder* become a scheduled `local` Collector (`trigger.every: 30s`), retiring `InboxWatch`? Per-file stability replaces the whole-inbox settle rule. | Yes — it is the only way B1 reuses ELT |
| O2 | DT-9: keep the Gate in screens and Basic's routes (one Basic-profile declaration), not refuse-on-load | Keep DT-9 as narrowed |
| O3 | Where Basic's rename and *Leave out* live: the mapping (today) or `transform.sql` (D8) | The mapping (no data change; the schema grid gains a mapping-name column only in Basic mode) |
| O4 | Does a Postgres snapshot keep a saved Connection (BE-4 / U2)? | Yes, a Connection profile restricted to `INSPECTO_PG_` secrets |
| O5 | One wording for format choices and type labels | ELT's (`parsingAttributesFor`, `TYPE_META`) |

## 6. Not in scope

- New Basic capabilities (transformation, user schedules, other databases) — `BASIC-ONBOARD-1` §7 stands.
- Renaming the build module `inspecto-etl` (GLOSSARY §13 debt).

## References

- `archived-documents/plans-archive/basic-onboard-plan.md` (`BASIC-ONBOARD-1`, shipped 2026-11-05)
- `okf/product/module/processor/api-onboarding-authoring.md` (Postgres: `api-onboarding-postgres-snapshot.md`), `okf/product/module/la-core/architecture-watch-this-folder.md` §6.20
- `okf/product/module/elt/spec-acquisition.md`, `spec-ingest.md`, `spec-consignments.md`, `spec-pipeline-execution.md`
