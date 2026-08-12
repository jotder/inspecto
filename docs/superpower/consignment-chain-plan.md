# Consignment chain — UI ↔ concept ↔ engine map, status-flow gaps, repair plan

> **Status: ACTIVE (opened 2026-08-12).** Operator framing: *"not much gap, just chain broken"* — the
> conceptual pipeline (collection → Stream onboard EL → transforms → Sink, uniform plugin Steps with a
> status/event bus) is already the canon (GLOSSARY §2 *Consignment*, §2 *Step* seven verbs,
> Run ⊇ Consignment ⊇ File), and most of it is built. What is broken is the **chain between the built
> pieces**. Grounded 2026-08-12 by three traces (ingest mechanics, status flow, UI inventory); every
> claim below carries a file:line from a verified read.
>
> Vocabulary: user-facing **Consignment**; code still says `Batch*` pending the coordinated Phase-7
> sweep (GLOSSARY §13). `ConsignmentPlanner` landed early (`4390516c`).

## 1. The three-way map

UI component → conceptual step → UI properties → engine, in pipeline order.

| Conceptual step | UI surface (component) | UI properties | Engine |
|---|---|---|---|
| **Collection** (register/activate per Stream, connection, inbox, globs) | Palette node `acquisition`, label **Collect** — palette is server-published `GET /pipelines/node-types`, *lowerable types only* (`pipeline-editor.component.ts:548-565`); props via `NodeConfigDialog` → `InspectoSchemaFormComponent` rendering server `AttributeSpec[]`; `COLLECTOR_ATTRIBUTES` shared block | connection, poll dir, include/exclude globs, delete-after | `CollectorService.start` arms poll `:904` / acquisition tick `:909` / `CollectorWatcher` `:912` → `PipelineScheduler.dispatchCycle` → `CollectorProcessor.collect` (`:275-304`: discover → stability gate → gap → watermark → dedup → admission cap); connectors = `CollectorConnector` ServiceLoader SPI; `LocalFileSystemConnector.discover` walks inbox (`:69-89`) |
| **Consignment Generation** (group inbox files, size/count-bounded) | **NONE** — no node, no form field; knobs are `.toon`-only, and see G3: even free-form can't author them in the honoured shape | *(missing)* | `ConsignmentPlanner.plan` (`inspecto-etl`, renamed from `BatchPlanner` `4390516c`): group by schema/table, greedy pack to `processing.batch.max_files` OR `max_bytes`, oversize file → consignment of one; **sorted lexicographic by path, not file time** (`ConsignmentPlanner.java:43-44`); defaults `max_files=1`, `max_bytes=∞` (`PipelineConfig.java:1276-77`) |
| **Parse / Grammar** | Palette node `parser` — bypasses `NodeConfigDialog`, opens `GrammarEditorDialog` → shared `grammar-editor.component.ts`; sniff-and-apply (`:319-328`); "Save as reusable Grammar" → `use: grammar/<id>` | delimiter, header, skip lines, null strings, encoding, compression; fixedwidth/json/text_regex blocks; plugin `grammarSchema` from `GET /parsers` | `ParserPlugin` SPI (`Parsers.java:28-50`, ServiceLoader + 4 builtins); ingest seam `StreamingFileIngester` picked by `cfg.schemas().ingesterClass()` (`BatchProcessor.java:52-54`); ASN.1 tree→row via per-field dotted `selector` (`Asn1RecordIngester.java:109-129`) |
| **Schema Generation** | `SchemaEditorDialog` — but in the **Components pane**, not the pipeline editor (`schema-editor.dialog.ts:21-31`), no link from the editor; **no inference anywhere** (G1) | name / selector / type / description / unit / classification per field | hand-authored `raw.fields`; `POST /config/preview/schema` **validates** declared fields only (`ConfigRoutes.java:512-524`); ingest pins `auto_detect=false` (`DuckDbCsvIngester.java:248`); `FileSampler.java:55` already runs the DuckDB sniffer (`all_varchar=true`) |
| **Record Mapping** | `MappingEditorDialog` — Components pane, CSV import + diff (`mapping-editor.dialog.ts:270`); the `transform.map` **node** has no specs at all (free-form only) | targetColumn / sourceExpression / transformType | `DataTransformer.selectFor` (`:87`): mapping rules + `TRY_CAST`/`TRY_STRPTIME` + partition cols + `__src_id`; post-parse `where` (`:65-73`) |
| **Transforms** (Filter/Route/dedup/Join/Summarize/…) | Palette nodes `transform.filter/dedup/dedup.marker/route/join/summarize/map`, `enrichment` — droppable (lowerable); `select/derive/validate/split/merge` exist in the catalog but are **never droppable** | per-kind server `AttributeSpec[]`; free-form escape hatch (string values only, `node-config.dialog.ts:608-611`) | flat `steps:` chain in `*_pipeline.toon` (multiplicity Part A); **executes only in the job lane** — `PipelineJobRunner` → `PipelineExecutor`/`RowShaper`; EL refuses to arm a transform-carrying flat pipeline; A5-at-rest pairing (`output_store:` + `pipeline_config:` job) is the bridge |
| **Sink** (parquet/CSV, hive partitions) | Palette node `sink.persistent`; specs `database/format/compression` **only** — `partitions[]` not authorable on the node (exists on sink *components*, `component-form.dialog.ts:159-166`) | database, format, compression | `BatchIngestStrategy.writeAndTrace` (`:105`) → `PartitionWriter` DuckDB `COPY (FORMAT PARQUET, PARTITION_BY …)` (`:130-138`); job lane `PartitionSinkWriter` |
| **Sync/at-rest jobs** (`type: pipeline`, `on_pipeline`) | separate **Jobs pane** (`jobs/job-form.dialog.ts`); `on_pipeline` autocomplete is single-value though the engine takes a comma list (`JobConfig.java:23`); **`on_pipeline_gate` unreachable** (read off the job section, not a declared param — `JobService.java:614`) | type, schedule/event, params from `GET /jobs/types/{id}` | `JobService` cron/event/signal/manual → `PipelineJobRunner` (`pipeline:`/`flow:` or `pipeline_config:` A5-at-rest, `:186-196`) |
| **Status / Notification bus** | Runs list, Run detail (Batches/Lineage/Quarantine/Commits), batch drill-in keyed on `consignment_id`, Processing status, Dashboard; **in-editor step provenance edge-weights** (`pipeline-editor.component.ts:744-750`) — 404 unless `-Dprovenance.backend=duckdb` | — | `BatchEventBus` (sync fan-out, 8 subscribers incl. alerting, EventLog, scheduler chaining, JobService ×2, metrics); 3 CSV ledgers + `CommitLog` + `BatchManifest`; Signal ledger `pipeline.batch.committed|failed` |

Save path: **one editor**, `PUT /pipelines/{name}/graph` → server lowers to flat `*_pipeline.toon`
(`pipelines.service.ts:330-350`); no UI writes `*_flow.toon` (authored-flow routes retired → 405;
the editor's class-doc comment still claiming `*_flow.toon` is stale — `pipeline-editor.component.ts:104`).

## 2. Status flow per Consignment (the flowfile analogy)

| Flowfile property | Ingest lane (EL) | Job lane (T) |
|---|---|---|
| Identity | `batchId` = `TS_slug_seq`; `BatchManifest` authoritative per-file | `runId`; `batchId` in provenance rows |
| Status | strings `SUCCESS/FAILED/EMPTY`, file `QUARANTINED_*` → CSV ledgers + `CommitLog` (`BatchAuditWriter.java:33-57,97-105`) | `JobRun` → `DbJobRunStore` (`job_runs`) |
| Per-step provenance | **never written** (grep-verified); nearest: `FileStage` enum `REGISTERED→…→WATERMARK_ADVANCED` (`FileStage.java:17-30`), **default-off** | **sole writer**: `PipelineExecutor:157-168,249-253` → `inspecto_pipeline_provenance` `(pipelineId, batchId, nodeId, rel, rowCount, runTs)`, **default-off** (`-Dprovenance.backend=duckdb`) |
| Step lifecycle events | **none** — only terminal `BatchEvent`; `IngestProgress` is poll-only, erased at batch end | **none** — node loop silent until run end |
| Data → next step | `raw_f<srcId>` → `raw_input` → `transformed` → COPY (identity intact) | materialized `<outPrefix>__<relkey>` tables; `produced` map `nodeId→{rel:table}`; `RunArtifact` JSONL per output |
| **Across the lane seam** | `BatchEvent` carries `batchId, partitions, outputRows, rejectedCount…` (`BatchEvent.java:44-47`) — but `JobService.onBatchEvent` fires the flow job with **`Firing.NONE`** (`:790-797`): identity dropped; the job re-reads the whole store glob or a `(pipelineId, store)` watermark (`PipelineJobRunner.java:210-238`) | **exception that proves the design**: `mirrorPipelineCommit` (`JobService.java:656-666`) republishes the same fields as a `pipeline.commit` Signal; `consignment.process` binds `consignment_id: $signal.batchId` (`ConsignmentProcessJobType.java:83-85`) and enumerates via the `consignment_outputs` registry → `ConsignmentReader` |

## 3. Gap register

| # | Gap | Severity | Evidence |
|---|---|---|---|
| **G1** | Schema generation missing entirely (500+-field sources are hand-typed) | HIGH / authoring | §1 Schema row |
| **G2** | Consignment order is lexicographic path, not file time | MED / semantics | `ConsignmentPlanner.java:43-44` |
| **G3** | **Consignment-grouping key-shape defect**: editor lowers **flat** `processing.batch_max_files/_bytes` (`PipelineEditable.java:99-100`), parser reads **nested** `processing.batch:{max_files,max_bytes}` only (`PipelineConfigParser.java:184-188`). No UI path — including free-form — can author grouping the engine honours. Both sides individually tested. Third instance of *"a config-format slice is NOT verified by a `fromMap` test"* | **HIGH / silent correctness** | verified both reads 2026-08-12 |
| **G4** | `on_pipeline` drops consignment identity (`Firing.NONE`) — per-Consignment traceability structurally impossible across the seam, though the Signal path already threads it | HIGH / lineage | `JobService.java:790-797` vs `:656-666` |
| **G5** | The flowfile ledger ships dark: `FileStage`, `consignment_outputs`, provenance = three separate default-off flags; in-editor provenance 404s without the flag | MED / observability | §2 |
| **G6** | No per-step lifecycle events in either lane ("consignment X at step 3/5" cannot be shown live) | MED / observability | §2 |
| **G7** | Two half-engines; unifier `BatchGraphRunner` unwired | tracked | ⛔ **operator-gated** (`elt-final-amendment-plan.md` §Phase-4-S4/6) — not re-planned here |
| **G8** | Minor authoring dead-ends: `output_store` absent from UI; `on_pipeline_gate` unreachable; sink `partitions[]` not on the node; Schema/Mapping editors unlinked from the pipeline editor; `on_pipeline` field is single-value; XML parser preview-only; stale `*_flow.toon` comment | LOW×7 / friction | §1, UI trace |

## 4. Plan

Ordered by leverage; each slice independently shippable. **S1–S3 are engine/UI work with no
operator decision needed. G7 stays gated and is out of scope here.**

- [x] **S1 — SHIPPED 2026-08-12** (with S5, one commit). Three mirrors the plan missed, all caught by
  the suites: `RecipeConverter` must carry the `batch` map or a round trip deletes it (the real
  `voucher_pipeline.toon` fixture proved it), `step-types.contract.json` is a SECOND served contract
  (regenerate via `-Dstep.types.write=true`), and two UI specs pinned "sink = database + OUTPUT only"
  by identity. Guard: `ConsignmentGroupingFileRoundTripTest` (5 tests, incl. legacy-flat healing and
  wholesale-map preservation). **Fix G3 (the key-shape defect).** Canonical shape = the documented nested
  `processing.batch:{max_files,max_bytes}` (FEATURE_INVENTORY row). `PipelineEditable` lowers the
  sink node's grouping fields into the nested block (and lifts them out of it); add
  `AttributeSpec`s (`batch.max_files`, `batch.max_bytes`, int-typed, bounds per
  `ConfigSafetyValidator.java:84-92`) so it's a real form field, not escape-hatch.
  → verify: a **file-level** round-trip test (graph → `lower` → `toToon` → disk → `load` →
  `PipelineConfig.fromMap` → `batchMaxFiles()==500`), mirroring `PipelineSinksFileRoundTripTest`;
  plus editor round-trip of a pre-existing nested block.
- [ ] **S2 — Thread the Consignment through `on_pipeline` (G4).** `onBatchEvent` builds a
  `Firing` from the `BatchEvent` payload exactly as `onSignalEvent` does (`$trigger.batchId`,
  `$trigger.partitions`, …) instead of `Firing.NONE`. Additive: jobs that bind nothing behave
  identically. → verify: a `type: pipeline` job with `bind: {consignment_id: $trigger.batchId}`
  resolves it on a real commit-triggered run; existing `JobServiceTest` on_pipeline tests unchanged.
- [ ] **S3 — EL-side step provenance (G5/G6 first half).** `BatchProcessor`/`CsvBatchStrategy`
  record `ProvenanceRow(pipelineId, batchId, nodeId=step, rel, rowCount)` at parse / map /
  quarantine / sink boundaries via the existing `DbProvenanceStore` writer; the in-editor
  edge-weight view then works for ingest pipelines too. → verify: run a smoke pipeline with
  `-Dprovenance.backend=duckdb`, assert rows for `parse`/`sink` with matching counts.
- [ ] **S4 — Schema inference at authoring time (G1).** New control route (e.g.
  `POST /config/suggest/schema`): `FileSampler` + DuckDB sniff **without** `all_varchar` +
  `TRY_CAST` voting over the sample → draft `raw.fields` (+ identity mapping) returned for human
  edit in `SchemaEditorDialog`; never auto-applied (same posture as grammar `suggest()`); ingest
  keeps `auto_detect=false`. → verify: route test over a CSV + a JSON sample; editor shows the
  draft.
- [x] **S5 — SHIPPED 2026-08-12** (with S1). `processing.batch.order: name|mtime`, garbage refused at
  parse time; NAME-default reproducibility pinned by test. **Consignment ordering knob (G2).** `processing.batch.order: name|mtime`
  (default `name`, today's behaviour — mtime is copy-fragile, so opt-in). → verify: planner test
  with shuffled mtimes.
- [ ] **S6 — UI chain repair (G8 + G3's surface).** Consignment Generation fields on the Collect
  or Sink panel (decide placement with the operator); link Schema/Mapping editors from the parse
  node dialog; `on_pipeline` multi-value + `on_pipeline_gate` select; sink `partitions[]` on the
  node; fix the stale `*_flow.toon` comment. → verify: contract JSONs (`node-attributes.contract.json`)
  regenerate + `NodeAttributesContractTest` green.
- [ ] **S7 — Step lifecycle signals (G6, design first).** Decide grain (per step-start/stop Signal
  vs. periodic `IngestProgress` persistence) before building — event volume on the sync bus is the
  constraint (`ingestLock` deadlock note, PROJECT_NOTES). Not started until S1–S3 land.

## 5. Related

[`okf/backend/engine/stage1-architecture.md`](../okf/backend/engine/stage1-architecture.md) ·
[`okf/backend/engine/consignment-addressing.md`](../okf/backend/engine/consignment-addressing.md) ·
[`okf/backend/pipeline-graph/multi-location-ingest.md`](../okf/backend/pipeline-graph/multi-location-ingest.md) ·
[`elt-final-amendment-plan.md`](elt-final-amendment-plan.md) (G7 gate) · GLOSSARY §2/§6-B/§13
