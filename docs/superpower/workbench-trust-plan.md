---
type: Plan
title: Pipelines workbench — spec and plan for a trustworthy generic ingest-authoring surface (`WB-*`)
description: Target state, work-item register, decision register with recommended answers to sign, API contracts, falsifiable gates and corrections — for making the Pipelines workbench author, save, test and arm ANY shipped format and Step kind end to end, with every trust surface giving one answer. Grounded on the 26-Pipeline sweep, a test run over real bytes for 24, the OKF concepts, and the seams read from source on 2026-09-22.
tags: [pipelines-workbench, plan, spec, pipeline-graph, ingestion, trust, multi-domain]
timestamp: 2026-09-22T00:00:00Z
---

# Pipelines workbench — a trustworthy generic ingest-authoring surface

Scope signed by the operator 2026-09-22: **the generic ingest-authoring workbench** — any shipped format and
Step kind authors, saves, tests and arms end to end, with trust signals that reconcile. Not a redesign of
the panes, not new processors. Decisions in §4 carry a **recommended answer each, for the operator to
sign**; work items are written against those answers and say where an amendment would change them.

## 0. How to read this

- §1 is the **as-built baseline** — what the workbench does today for each format and Step kind, measured,
  cited to code. Nothing here is work.
- §2 is the **target model** — the one principle the work builds toward, and the trust ladder it implies.
- §3 is the **work item register** — every pending item, one `WB-nn` id, with state, size, blocker and the
  seam it edits. Where a `BACKLOG.md` row exists the item names it; the row stays the tracker, this page
  is the plan.
- §4 is the **decision register** — every owed operator call with a recommendation. ⛔ None may be answered
  by an implementer in passing; ✍ marked where a signature was awaited. ✅ **As of 2026-09-22 none are:**
  all eight were signed as recommended and written into their durable homes (§8.2) the same day.
- §5 holds **contracts** (request/response and file shapes the items change); §6 the **acceptance gates**,
  each falsifiable; §7 the **corrections** this plan's own sources accumulated, kept so nobody re-derives them.

**This document is a register with a model in front of it, and it is built to die.** §8 states when
it is archived, what leaves it for which durable page, and the two conditions under which it is archived
*unbuilt* rather than kept as intent. Link Analysis wrote nine plan documents (2,637 lines) and archived
eight; the 616-line model plan carries the note *"nothing was built from it"*. The difference between a
plan that ships and one that is archived unbuilt was never the writing — it was whether the register was
worked. §8 is the check that this one is.

Status tags: ✅ SHIPPED (built, reachable, tested, cited) · 🟡 PARTIAL (something real exists, gap stated) ·
⬜ NOT BUILT (absence confirmed by running it, not merely unfound). Evidence tags: **E1** API sweep of 26 ·
**E2** test run over real bytes, 24 · **E3** OKF concepts · **E4** the browser-driven build · **E5** source read
2026-09-22 (file:line).

---

## 1. As-built baseline (grounded 2026-09-22)

### 1.1 What is universal

Measured on **26 of 26** shipped Pipelines (`default` 15 · `demo` 8 · `ucc` 3) — E1:

- Every flat config **lifts** into a typed graph (`GET …/graph/raw`, `PipelineEditable.toMap`).
- **Save is fail-closed.** PUTting the display projection back is refused 422 with named findings and
  `written:false` on all 26; the one lossless-payload refusal left its file byte-identical.
- **Save preserves every key** — 25/25 written Pipelines re-read with identical node configs and zero
  keys lost, including engine-read/authoring-invisible `dirs` leaves. 24/25 were **reformatted**.
- The projection `GET …/graph` and the round-trip pair `GET …/graph/raw` + `PUT …/graph` are a deliberate
  split (`pipeline-authoring.md:173,550`; `editable-round-trip.md:122`).

### 1.2 The coverage matrix — format × surface

**Test run** = *Run to here* (`POST …/authored/{id}/run?to=`) parses a real sample through the real
frontend into a jailed scratch root and rows reach the Step after the parser — E2.

| Frontend (shipped) | Lift | Typed drawer | `/validate` | Save | Test run |
|---|---|---|---|---|---|
| `parser.delimited` — 15, five domains | ✅ | ✅ | ✅ | ✅ | ✅ 14/14 sampled |
| `parser.json` — 3 | ✅ | ✅ | ⚠ false `csv_settings` warning | ✅ | ✅ 3/3 |
| `parser.xlsx` — 1 | ✅ | ✅ | ⚠ false warning | ✅ | ✅ |
| `parser.fixedwidth` — 1 text (+ binary via dialog, by decision) | ✅ | ✅ | ⚠ false warning | ✅ | ✅ |
| `parser.text_regex` — 1 | ✅ | ✅ | ⚠ partition warning (real) | ✅ | ✅ |
| `parser.asn1` — 1 | ✅ | ✅ inline X.680 | ⚠ false warning, **no ERROR** | 🔴 **422 `ERR_ARMED_WITHOUT_SCHEMA`** | 🔴 parses 3, **reaches nothing** |
| `parser.plugin` (XML) — 1 | ✅ | ✅ | ⚠ false warning | ✅ | 🔴 parses 3, **reaches nothing** |
| Dataset re-ingest (`collector: dataset`) — 1 | ✅ | ✅ | ⚠ false warning | ✅ | ⚠ `200 "no rows were parsed"` |

Six of eight frontends are on the delivered path end to end. The two that are not share one shape —
a parser that **routes by record type** into per-segment mappers — which is the pre-mediation telecom
shape, the XML shape, and every multi-record-type feed.

### 1.3 Step kinds through the test run (E2)

`dedup` 8 → 8 kept / 0 dup · `filter` 8 → 4 / 4 dropped (**counted**) · `route` 18 → 8 / 10 by branch ·
`sql` 8 → 7 · `summarize` 8 → 4 groups · **`join` — 422 on test run and dry-run** (§1.5) · `lookup` — ships
no sample, untestable as shipped.

### 1.4 The seams, as they are (E5)

| Concern | Where | Behaviour today |
|---|---|---|
| Test-run walk seed | `PipelineExecutor.java:295` | `produced.put(seedNodeId, Map.of(PipelineRel.DATA, seedTable))` — **one relation, `data`** |
| Test-run edge following | `PipelineExecutor.java:322-329` `liveInbound` | follows an edge only if `produced.get(e.from()).containsKey(e.rel())`; a `route:<segment>` rel out of the parser is never present, so it is **silently skipped** |
| Live lane segment dispatch | `PipelineExecutor.java:94-98` `execute(conn, g, Map<nodeId,table> seeds, …)` + `RouteArming` / `SchemaSelector` | production seeds **one relation per segment**, keyed `route:<segment>` |
| Arming gate | `ConfigRoutes.java:90-111` `armedWithoutSchemaFindings` | checks `processing.schema_file`, `processing.schemas[]`, `processing.ingester`, `parsing.plugin.ingester` (`:99-103`); **not** `parsing.<frontend>.segments{}` |
| Arming gate call sites | `ConfigWriteRoutes.java:79,414` · `PipelineBundleRoutes.java:316` · `PipelineGraphRoutes.java:319` | write paths only; **`ConfigPreviewRoutes.java:40` (`/validate`) never calls it** |
| `csv_settings` warnings | `ConfigValidator.java:81-84` | guard is "`cfg.csv().dateFormats()` empty"; **no frontend check** |
| Reject rows | `DuckDbCsvIngester.java:712-` `drainRejects` → DuckDB `reject_errors` (`SELECT e.line, e.column_name, e.error_type, e.csv_line`) | one row **per column error** (DuckDB's grain); returns the count as `IngestResult.errorRows` (`:162`) |
| Reject count in the audit | `ConsignmentIngestor.java:748-751` | `rejected` = **member files** with `MemberStatus != SUCCESS`; `CsvIngestStrategy.java:165` sums only `parsedRows` into `totalInputRows` and **never reads `errorRows`** |
| Reference resolution (test paths) | `PipelineGraphRoutes.java:675-690` `dryRunReferences` | `ReferenceReader.parse/sqlFor` is in a try/catch → 422 named; the `CREATE OR REPLACE VIEW … AS SELECT * FROM <path>` at `:685-687` is **outside it**, so a missing file leaks DuckDB's message; test run reuses it (`:562`) |
| Reference check (save) | `PipelineValidator.java:101,302` `JOIN_REFERENCE_MISSING` | a shape rule — the field is present — not a disk check |
| Dry-run body | `PipelineGraphRoutes.java:64,468-489` | `api.body(e)` types the body as `Map` before any check; a bare array fails Jackson → **500** |
| Test-run connector rule | `PipelineGraphRoutes.java:583-595` `testRunRoot` | 501 only when `cfg.collector().hasConnection()` and the profile is not `local`; **`connector: dataset` with no profile takes the `dirs.poll` branch** → 200 "no rows" |
| Round-trip tests | `LiftLowerFixtureSweepTest` (every `spaces/**/*_pipeline.toon`, raw-map equality after `toMap → fromMap → lower`) · `PipelineEditableTest` | in-process; **never through `PUT /graph`**, so write-route drift (defaults, coercions, findings-driven rewrites) is unguarded |
| Node status | `pipeline-graph.ts:56,103-118` `computeNodeStatus`; fed by `testedStatus` (`pipeline-editor.component.ts:459`) from `RunToHereDialog` only | `/provenance` lands in `lastRunBatch`/`lastRunCounts` (`:1173-1189`), read by `nodeLastRun()` for the inspector strip, **never** by `computeNodeStatus` |
| Dry-run ↔ test run | `pipeline-dry-run-panel.component.ts:34,44-48` `capturedRows` from the per-tab sample thread (`.html:780`); `run-to-here.dialog.ts:240,296-311` keeps its result in a local signal and closes with no value | **no wiring** between the two instruments |
| Palette in read-only | `PipelinePaletteComponent` has no `readOnly`/`canAuthor` input (`pipeline-palette.component.ts:33-195`); `canAuthor()` (`pipeline-editor.component.ts:776`) gates `addFromPalette` (`:2459`) | buttons render enabled; the add is refused after the click; specs pin New/Save/delete (`.spec.ts:2431,2859`), **none touch the palette** |
| Palette add | `insertNode` (`pipeline-editor.component.ts:2511-2517`) → `addNodeToModel` only; selection unused | a **disconnected** node; `<app-pipeline-step-cards>` (the Recipe view) was **deleted in `6d3c68fa`**, so no one-gesture insert exists anywhere |
| Scaffold write location | `pipeline-scaffold.ts:85-129` derives `id`/`dirs`; `configApi.write('pipeline', scaffold)` (`pipeline-editor.component.ts:1214-1219`) carries no path — **the server picks `config/<name>_pipeline.toon`**; satellites take an explicit subdir per call site (SATELLITE-WRITE-1) | flat at the space root; all 26 shipped Pipelines live in `config/<name>/` |
| Toolbar | `pipeline-editor.component.html:164-233` | *Validate* · *Dry-run* (tooltip "Dry-run") · *Run to here* · *Preview data*; **nothing on screen says one seeds after the parse and the other parses real files** |
| Seeding samples into `data/**` | `spaces/demo/data/samples/seed-ops.ps1` seeds operational objects only | **no mechanism** copies `data/samples/**` into inboxes or `data/ref/` |

### 1.5 Structural consequences

1. **Two surfaces, two answers** is the recurring defect shape, not any single bug: gate vs validator
   (ASN.1), save-refusal vocabulary vs test-path vocabulary (join), file-grain vs row-grain under one
   column name (`rejected_count`), dry-run vs test run (two instruments, no handoff, no copy).
2. A routed-by-type frontend is **testable only to the decoder**, because the test walk seeds one `data`
   relation where production seeds one per segment.
3. Both shipped reference-join examples **fail on a fresh checkout** — not a join defect: the reference is
   at an unseeded path, and no seeding mechanism exists.
4. The documented promise *"never silently dropped … a counted reject relation"* (`step-catalog.md:91,127`)
   holds for the filter Step and **not for ingest**.

---

## 2. The target model — one question, one answer

**Principle.** Every question the workbench can be asked about a Pipeline — *is it valid? will it save?
will it arm? what does this file parse to? what reached which Step? what was rejected? has it run?* — has
**one predicate, called from every surface that answers it.** Two surfaces may render differently; they
may not disagree.

**The trust ladder.** A Pipeline climbs six rungs, each rung one surface, each surface truthful on its own
and consistent with the others:

| Rung | Surface | Today | After |
|---|---|---|---|
| authored | drawers, palette, canvas | ✅ | + one-gesture insert (`WB-14`), palette honest in read-only (`WB-13`) |
| validated | `/validate`, live findings | 🟡 frontend-blind rule; misses the arming predicate | one predicate for "has a schema" (`WB-03`), rules gated on frontend (`WB-04`) |
| saved | `PUT /graph` | ✅ fail-closed, key-preserving, **unguarded** | guarded through the real route (`WB-01`) |
| tested | *Run to here* | 🟡 6/8 frontends; join needs an unseeded file; two instruments | seeds per segment (`WB-08`), named refusals (`WB-05`, `WB-07`), the panel seeds from the run (`WB-12`), copy that says which instrument does what (`WB-02`) |
| armed | activate | ✅ | unchanged |
| ran | batch audit, `/provenance` | 🟡 `rejected_count` is files; dropped rows vanish from input | `rejected_files` + `rejected_rows`, input = parsed + rejected (`WB-09`); errors one row per line (`WB-10`); a *ran* badge on the card that is not a *tested* claim (`WB-11`) |

**What the model refuses.** It does not merge instruments (dry-run stays a post-parse rehearsal, test run
stays the real-bytes probe); it does not make node *tested* state read provenance (`pipeline-editor.md:693,
701` stand — §4 `D6`); it does not add processors; it does not reopen fan-in, nesting or the map-list spec
(`pipeline-authoring.md:517,533`; `editable-round-trip.md:370`).

---

## 3. Work item register — everything pending, one id each

Sizes: **S** ≤ half a day · **M** 1–2 days · **L** > 2 days. "Blocked on" names a §4 decision or another item.

### 3.1 Foundations — do first, no decisions needed

| Id | Item | State | Size | Blocked on | Detail |
|---|---|---|---|---|---|
| **WB-01** | **Round-trip guard through the HTTP write path** | ✅ **SHIPPED 2026-09-22** | S | — | New test beside `LiftLowerFixtureSweepTest`: for every `spaces/**/*_pipeline.toon`, boot `ControlApi` on an ephemeral port (house idiom), `GET …/graph/raw` → `PUT …/graph` → re-`GET`; assert (a) the config's key set is unchanged, (b) node configs are `equals` after re-read, (c) `written:true`. Adds what the in-process sweep cannot see: write-route defaults, `active` coercion, findings-driven rewrites. ⚠ It will **fail on `asn1_example` until `WB-03` lands** — that is the guard doing its job; pin the expectation and flip it with `WB-03`. Protects §1.1's two properties. Row: `GRAPH-SAVE-REFORMATS-CONFIG-1` (the guard half). ✅ **Shipped 2026-09-22** as `ControlApiPipelineGraphRoundTripSweepTest`: **25 of 26 shipped Pipelines save losslessly** through the real route, `asn1_example` refuses exactly as predicted, and the pin is **falsification-probed** — removing it turns the guard red, so it is not vacuous. 🔴 **Two corrections to this item as written.** (1) It canNOT sit beside `LiftLowerFixtureSweepTest`: that is `inspecto-engine`, which has no `ControlApi`. It lives in `inspecto`, package `com.gamma.control`, where the package-private `V1Body` envelope-unwrap helper is. (2) It canNOT drive the committed `spaces/` tree — the write path WRITES, and fixture paths are repo-relative while surefire's CWD is the module dir (`SCHEMA-FILE-RESOLVES-AGAINST-CWD-1`). Each fixture is staged into a temp copy with its `spaces/<space>/` prefix rewritten absolute. |
| **WB-02** | **Toolbar copy — say what each instrument does** | ✅ **SHIPPED 2026-09-22** | S | — | `pipeline-editor.component.html:170-180,216-224`: tooltips become *"Dry-run — rehearse the Steps after the parser over sample rows (no write)"* and *"Run to here — parse real inbox files through this Pipeline into a scratch root (no write)"*; the dry-run dock's heading already says the first, the toolbar does not. Zero logic. This is the gap that made an evaluator drive the wrong instrument and file its absence (§7.1). ✅ **Shipped 2026-09-22** — both tooltips and both `aria-label`s now name the instrument's seed and say nothing is written; the comment above the button records why they must not be shortened back to one word. `pipeline-editor.component.spec.ts`'s `RUN` label constant follows the new text; 188/188 pass. |
| **WB-16** | **One seed script: `data/samples/**` → inboxes and `data/ref/`** | ✅ **SHIPPED 2026-09-22** | S | — | `tools/seed-samples.mjs <space>|--all`: copies each Pipeline's sample dir into its `dirs.poll` and `data/samples/ref/*` into `data/ref/`; idempotent; printed plan. Wire into `.claude/launch.json` pre-step and the smoke skill. Turns `join_step` / `orders_enriched_rollup` from "fails on a fresh checkout" into runnable. Row: `REFERENCE-EXAMPLES-NEED-UNRUN-SEED-1`. ✅ **Shipped 2026-09-22** as `tools/seed-samples.mjs` — 23 inboxes, 2 reference sets, 225 working dirs across 4 spaces, idempotency proved by byte-comparing a second run; wired into all four space-serving launchers and `smoke` step 2. 🔴 **The row's cause was refuted while building it:** the per-space `seed-inbox.sh`/`.ps1` DO copy `ref/*`; what was missing is that nothing ran them, and their pipeline list is hand-written. The tool derives it from `dirs.poll` instead. |

### 3.2 Phase 1 — one predicate per question (Sprint A)

| Id | Item | State | Size | Blocked on | Detail |
|---|---|---|---|---|---|
| **WB-03** | **One "has a schema" predicate, called by the gate AND the validator** | ✅ **SHIPPED 2026-09-22** | M | **D7** ✅ | `ConfigRoutes.armedWithoutSchemaFindings` (`:90-111`) learns `parsing.<frontend>.segments{}` non-empty as a schema source (D7 recommended yes); extract the predicate; `ConfigPreviewRoutes` (`/validate`, `:40`) calls it so the two answer alike. Gate: `asn1_example` saves and validates the same. Row: `SAVE-GATE-VS-VALIDATE-DISAGREE-1`. ✅ **Shipped 2026-09-22** as `ConfigRoutes.hasSchemaSource`. 🔴 **The disagreement was DEEPER than this item said:** the predicate ignored `segments{}` **and** `POST /validate` never called the arming check in EITHER branch — so fixing only the predicate would have left the two surfaces disagreeing about every other active-but-schemaless config. Both halves landed. ⚠ The predicate scans every `parsing.*` sub-block for a non-empty `segments{}` rather than matching frontend NAMES — keying on names is how `asn1` came to be missed. Proof: `WB-01`'s sweep is green with an EMPTY pin — **26 of 26** now save losslessly. |
| **WB-04** | **Frontend-gated `csv_settings` rules** | ✅ **SHIPPED 2026-09-22** | S | — | `ConfigValidator.java:81-84`: emit the `date_formats`/`timestamp_formats` warnings only when the resolved frontend is delimited (and `csv_settings` is authored). 6/26 Pipelines stop being born `clean:false` for a rule that cannot apply. Row: `VALIDATE-CSV-RULE-FRONTEND-BLIND-1`. ✅ **Shipped 2026-09-22.** ⚠ The **delimiter** rule was gated too, not just the two format rules this item named: it is the same defect in the same block, and fixing two of three identical bugs is not a fix. `PipelineConfig` does not retain the `frontend:` token, so *delimited* is the negative of the five shape records plus a null `ingesterClass` — the idiom this file already used. Falsification-probed: forcing the gate open turns the new test red. |
| **WB-05** | **Named reference refusal on both test paths** | ✅ **SHIPPED 2026-09-22** | S | — | `PipelineGraphRoutes.dryRunReferences:685-687`: move the `CREATE OR REPLACE VIEW` into the try/catch; on failure raise the same **`JOIN_REFERENCE_MISSING`**-coded 422 the save path uses, carrying the resolved path. Test run inherits via `:562`. Row: `TESTRUN-REFERENCE-REFUSAL-UNNAMED-1`. ✅ **Shipped 2026-09-22** — the `CREATE OR REPLACE VIEW` is inside the guard and a missing reference now refuses 422 `JOIN_REFERENCE_MISSING` carrying the RESOLVED target, because *“which file did you actually look for”* is the next question every time. |
| **WB-06** | **Malformed dry-run body → 400** | ✅ **SHIPPED 2026-09-22** | S | — | `PipelineGraphRoutes:64/468`: catch the body-shape mismatch before `dryRunFlow` and answer 400 *"body must be an object: {sampleRows:[…], pipeline?}"*. Row: `DRYRUN-MALFORMED-BODY-500-1`. ✅ **Shipped 2026-09-22 — at the SHARED seam (`ControlApi.body`), not in the one route that surfaced it.** The 500 was every POST route's defect; a per-route catch would have left it everywhere else and invited one more copy at the next report. ⚠ A route's own, more specific 400 still reaches the caller — pinned, because that is the half a blanket catch would have broken. |
| **WB-07** | **Test run refuses non-file Collectors by name** | ✅ **SHIPPED 2026-09-22** | S | — | `testRunRoot:583-595`: before the `hasConnection()` branch, if `collector.connector` is not a file connector (`dataset`, `jdbc`, `kafka`…) → 501 with the same wording the connection path uses. Row: `TESTRUN-DATASET-COLLECTOR-SILENT-1`. ✅ **Shipped 2026-09-22** — `cfg.collector().hasDataset()` refuses 501 and names the Dataset, instead of 200 *“no rows were parsed”*, which read as *“your file is bad”*. |

### 3.3 Phase 2 — the walk (Sprint B)

| Id | Item | State | Size | Blocked on | Detail |
|---|---|---|---|---|---|
| **WB-08** | **Test run seeds one relation per `route:<segment>`** | ⬜ | M–L | **D5** ✅ | In `PipelineDryRun.run` (`:97-104`) / `PipelineExecutor.dryRun` (`:295`): when the seed node's frontend emits segments, seed `produced` with one table per segment keyed exactly as production does (`execute`'s multi-seed overload `:94-98`, `SchemaSelector`), plus `unmatched`. `liveInbound` (`:322-329`) then follows the existing edges unchanged. Gate: `asn1_example` and `xml_example` report `relations` past the parser and the quarantine sink's `unmatched` count. ⚠ Do not special-case the walker; the fix is the seed shape. Row: `TESTRUN-SEGMENT-ROUTE-NO-FLOW-1`. 🔴 **GROUNDED 2026-09-22 — this item's method reference DOES NOT EXIST, and the work is larger than “seed shape, not walker”.** Three corrections, each verified in source: (1) `execute`'s multi-seed overload keys `nodeId → table` with the relation ALWAYS `PipelineRel.DATA` — it is multi-SOURCE, not multi-segment, so “keyed exactly as production does” names a shape that is not there; no `nodeId → rel → table` seed exists anywhere. (2) `SchemaSelector` runs at LIFT time only (`PipelineLift` assigns `route:<key>` branch names when building edges) and is never invoked at seed/execute time. (3) Production's real per-segment pattern is `ConsignmentIngestStrategy.seedOfWrite` — one `execute` call per segment seeded at `map_<segKey>`, which walks each branch's TAIL and never traverses a `route:` edge at all. ✅ **What IS true and makes the item feasible:** `liveInbound` follows an edge iff the upstream node's produced-map contains a key equal to the edge's `rel`, so seeding `route:<seg> → table` on the parser node would be followed with NO walker change. ⚠ **The missing piece is the rows:** the test run bridges to the preview through `PipelineTestRun.sampleRows`, which reads the run's written outputs back as ONE FLAT list, and `PartitionOutput(partition, outputFile, bytes)` carries no segment label — so which segment produced which rows is not currently recoverable. `WB-08` therefore also needs a segment label plumbed through the output/sample bridge (or derived from the output path), across three modules. ⛔ Re-size it before starting: it is the largest item on this register, not an M–L seed tweak. |

### 3.4 Phase 3 — accounting (Sprint B)

| Id | Item | State | Size | Blocked on | Detail |
|---|---|---|---|---|---|
| **WB-09** | **Reject accounting that honours the contract** | ✅ **SHIPPED 2026-09-22** | M | **D2** ✅ | `CsvIngestStrategy.java:165` carries `ing.errorRows()` into `IngestOutcome`; `ConsignmentIngestor.java:748-751` writes **two** columns: `rejected_files` (today's value, renamed) and `rejected_rows` (the sum); `total_input_rows` = parsed + rejected rows so the ledger reconciles to the file. `ConsignmentAuditWriter.java:52-56,149-160` header + line; `ConsignmentEvent` field. Breaking header change is free (nothing after 3.x shipped). Row: `INGEST-REJECT-ACCOUNTING-1`. ✅ **Shipped 2026-09-22.** 🔴 **The header had SEVEN construction/mirror sites, not the four this item listed** — the two extra were found by the COMPILER (`DrainCommand`, and four test fixtures across three modules), which is the argument for the typed `ConsignmentRow` over the string header: a stale string mirror fails silently, a stale constructor does not. ✅ And the rename ALIGNED an existing inconsistency: the alert measure was already named `rejected_files` while reading a column called `rejected_count`. ⚠ **`error_rate` changes meaning and that is the point** — `total_input_rows` now counts what ARRIVED, so a file that silently dropped a record reports a real error rate instead of 0%; thresholds tuned against the old always-reconciling numerator may now fire. |
| **WB-10** | **Errors CSV: one row per bad line** | ✅ **SHIPPED 2026-09-22** | S | — | `DuckDbCsvIngester.writeRejects`: group `reject_errors` by `line`, emit `line_number, columns (c9–c17), reason, raw_line`. 🔴 **NOT independent of `WB-09` — it is its PREREQUISITE, and this item said otherwise.** `writeRejects` returned its count once per `reject_errors` ROW, i.e. once per offending COLUMN, and that count IS `IngestResult.errorRows()`. So `rejected_rows` would have shipped inflated by the number of missing columns (9× in the measured case) had `WB-10` not landed first. ✅ **Shipped 2026-09-22** ahead of `WB-09`: the errors report groups by line, names the offending columns as a range, and the returned count is now bad LINES. Row: `INGEST-ERRORS-CSV-PER-COLUMN-1`. |

### 3.5 Phase 4 — trust and shape (Sprint C)

| Id | Item | State | Size | Blocked on | Detail |
|---|---|---|---|---|---|
| **WB-11** | **A *ran* badge that is not a *tested* claim** | ✅ **SHIPPED 2026-09-22** | S–M | **D6** ✅ | Keep `computeNodeStatus` (`pipeline-graph.ts:103`) and its `testedStatus` feed untouched (the test lane, `pipeline-stages.ts:13`). Add a separate card badge from `lastRunCounts` (already loaded, `:1173-1189`) — *"last run: 400 rows · 2026-09-22"* — and reword the finding to *"not yet tested in this session"*. Respects `:693/:701` by adding a third source, not a second opinion. Row: `PIPELINE-NODE-TEST-STATE-STALE-1`. ✅ **Shipped 2026-09-22 — but MOST OF IT WAS ALREADY BUILT.** `nodeLastRun()` already fed the inspector a *“Last run: N row(s) · <ts>”* line from `lastRunCounts`; only the finding's wording was outstanding, and it now reads *“not yet tested in this session”*. 🔴 **The real cause of the complaint was elsewhere and is fixed under `WB-12`:** the test-run dialog never returned its result, so `applyRunOutcomes` — the code that marks nodes tested — had NEVER run. Nodes read *not yet tested* even in the session that had just tested them. |
| **WB-12** | **Dry-run panel seeds from the last test run** | ✅ **SHIPPED 2026-09-22** | S–M | — | `RunToHereDialog` (`run-to-here.dialog.ts:240,296-311`) returns `result` through `MatDialogRef.close`; the editor routes `relations[seed].rows` into the tab's sample thread (`.html:780` `capturedRows`), so *"Use the captured sample"* is one click after a run. Row: `DRYRUN-SEEDS-AFTER-PARSE-1`. ✅ **Shipped 2026-09-22, and it uncovered a DEAD SEAM.** `RunToHereDialog`'s Close button was a bare `mat-dialog-close` carrying no value, so `afterClosed()` always emitted `undefined` and the editor's `if (r) applyRunOutcomes(r)` never fired — `applyRunOutcomes` has exactly one call site, so **the canvas ✓ marks a test run is supposed to leave had never been applied at all.** Nothing failed; the outcome simply never arrived. Closing with the result repairs both that and this row's handoff. |
| **WB-13** | **Palette disabled in the read-only lens** | ✅ **SHIPPED 2026-09-22** | S | — | Add `@Input() readOnly` to `PipelinePaletteComponent`; host binds `[readOnly]="!canAuthor()"`; buttons get `disabled` + `aria-disabled`. Extend `pipeline-editor.component.spec.ts:2859` to assert 0 enabled `Add …` controls in the Business lens. Row: `READONLY-LENS-PALETTE-ENABLED-1`. ✅ **Shipped 2026-09-22** — `readOnly` input on the palette, `[disabled]` + `[draggable]` on BOTH add-button families (the processor catalog and the type catalog), host binds `[readOnly]="!canAuthor()"`, and the spec asserts zero enabled controls in the Business lens. |
| **WB-14** | **One-gesture insert on the canvas** | ✅ **SHIPPED 2026-09-22** | M | **D8** ✅ | `insertNode` (`:2511-2517`): when a node is selected, add the new node **after** it — rewire the selected node's outgoing `data` edge through the new node (`addEdge`/`removeEdge` in `pipeline-graph.ts`); else the current bare add. Validation keeps catching orphans. Row: `PALETTE-ADD-DROPS-ORPHAN-1` (corrected, §7.3). ✅ **Shipped 2026-09-22** as `addAfterSelection`: with a node selected the new Step is wired `sel → new → old`, rewiring the selection's outgoing **`data`** edge only — a `route:`/`reject:` outlet carries a branch meaning an inserted Step has no business inheriting. ⛔ With nothing selected the bare add stands, per D8: guessing an anchor is how an “add” silently rewires a graph the author was not editing. Both cases pinned. |
| **WB-15** | **Created Pipelines land in `config/<name>/`** | ✅ **SHIPPED 2026-09-22** | M | **D3** ✅ | Server-side in the `configApi.write('pipeline', …)` handler (the client sends no path — `pipeline-scaffold.ts`, `pipeline-editor.component.ts:1214-1219`): choose `config/<id>/<id>_pipeline.toon`; audit every SATELLITE-WRITE-1 call site (`pipeline-config-definition.component.ts:311`, `schema-editor.dialog.ts:42`, `grammar-editor.dialog.ts:197`) to pass the same subdir. Guard: a test that creates through the route and asserts the path. Row: `UI-CREATED-PIPELINE-FLAT-HOME-1`. ✅ **Shipped 2026-09-22**, server-side in `ConfigWriteRoutes`. ⛔ **Bound LATE — only when the target does not already exist.** Several shipped Pipelines live in a per-DOMAIN directory (`config/postmed/postmed_xdr_pipeline.toon`), not one named for the id, so redirecting an EDIT would fork a shadow config beside the real one — the exact failure the legacy-name adoption above it exists to prevent. Both the create and the do-not-relocate cases are pinned. |
| **WB-17** | **Samples for the sample-less, demos for the synthetic-only** | ⬜ | M | `WB-16` | `lookup_step` gets a sample file; one domain-shaped demo each for ASN.1, Excel, fixed-width, XML and `route` in `spaces/demo`. Could-tier: value is in coverage, not features. Row: `DEMO-CORPUS-FORMAT-COVERAGE-1`. |
| **WB-18** | **The projection says what it is** | ✅ **SHIPPED 2026-09-22** | S | **D9** ✅ | `GET …/graph` response gains `links.roundTrip: …/graph/raw` and `metadata.readOnlyProjection: true`; no rename (D9 recommended). Row: `GRAPH-READ-SHAPE-NOT-WRITE-SHAPE-1`. ✅ **Shipped 2026-09-22** with two deliberate deviations. (1) The link is derived from the **request path**, never composed from a constant — this route is also reached under `/spaces/{id}`, and a hardcoded `/api/v1/pipelines/…` would hand a space-scoped caller a URL pointing outside its own space. (2) The flag sits on the RESOURCE, not under a `metadata` key: the v1 envelope already owns `metadata` and `links`, and shadowing them inside `data` would be two things with one name — the defect Sprint A spent itself closing. |
| **WB-19** | **Formatting churn — decide, document, guard** | ✅ **SHIPPED 2026-09-22** | S | **D4** ✅ | D4 recommended: accept the churn; `WB-01` guards semantic losslessness; `editable-round-trip.md` states *"verbatim = decoded map, not bytes"* in one sentence. No writer work. Row: `GRAPH-SAVE-REFORMATS-CONFIG-1` (the decision half). ✅ **COMPLETE 2026-09-22 — all three parts, none of which was code.** Decide: `D4` signed. Document: the sentence is in `editable-round-trip.md` beside the standing gate. Guard: `WB-01` ships the round trip through the real `PUT …/graph`. |
| **WB-20** | **Fix the stale Recipe-view claim and the row that rested on it** | ✅ **done in this change** | S | — | `pipeline-editor.md:550` now records that `<app-pipeline-step-cards>` was removed in `6d3c68fa`; `PALETTE-ADD-DROPS-ORPHAN-1` re-edited (§7.3). |

### 3.6 Sequencing

`WB-01`, `WB-02`, `WB-16` first (no decisions, half a day each, and `WB-01` is the safety net for
everything after). Then Sprint A (`WB-03`…`WB-07`) — five small changes that end the *two answers*
shape. Sprint B (`WB-08`, `WB-09`, `WB-10`) is the substantive engineering. Sprint C is polish and
trust. `D2`, `D5`, `D7` gated Sprint B and `D3`, `D6`, `D8` gated Sprint C — ✅ **all eight were signed
2026-09-22 as recommended (§4), so no item on this register is decision-blocked any more.** Every `✍`
marker below is discharged; what remains is build order, not permission.

---

## 4. Decision register — owed operator calls, with recommendations to sign

| # | Question | Recommendation | Evidence | Forecloses | Items |
|---|---|---|---|---|---|
| **D1** | Real frontend server-side vs client approximation for the parse test | ✅ **already answered** — real, server-side, jailed (`pipeline-test-run.md:12,70`) | E2, E3 | — | — |
| **D2** ✅ | What does `rejected_count` mean? | ✅ **SIGNED 2026-09-22 — split it.** `rejected_files` (today's semantics: members not `SUCCESS`) **and** `rejected_rows` (sum of `IngestResult.errorRows`); `total_input_rows` = parsed + rejected rows. Rename rather than overload — breaking the audit header is free. Written into `okf/backend/engine/consignment-status-flow.md` and `okf/backend/pipeline-graph/step-catalog.md` the same day. | E5 `ConsignmentIngestor:748-751`, `CsvIngestStrategy:165`, `IngestResult:16` | "one number that means both" | WB-09 |
| **D3** ✅ | Where does a UI-created Pipeline live? | ✅ **SIGNED 2026-09-22 — `config/<id>/`, server-side.** Every shipped Pipeline uses it; *"one identity for id, file and directories"* (`pipeline-authoring.md:405`) points the same way; satellites get the subdir too. Written into `okf/capabilities/pipeline-authoring/pipeline-authoring.md` §4 and `okf/frontend/features/pipeline-editor.md` the same day. | E4, E3 | flattening 26 Pipelines | WB-15 |
| **D4** ✅ | Formatting churn on save | ✅ **SIGNED 2026-09-22 — accept it.** Fund the guard (`WB-01`), not a format-preserving writer; map-verbatim ≠ byte-verbatim is now stated in `okf/backend/pipeline-graph/editable-round-trip.md` beside the standing gate. | E1 24/25, `editable-round-trip.md:494` | a writer with its own drift surface | WB-19 |
| **D5** ✅ | Segment-routed frontends in the test run | ✅ **SIGNED 2026-09-22 — walk the edge** by seeding one relation per segment (production's seed shape). Refusing would leave 2/8 frontends — and every multi-record feed — untestable past the decoder. Written into `okf/backend/engine/pipeline-test-run.md` the same day. | E2, E5 `PipelineExecutor:94-98,295,322-329` | a named 501 for `parser.asn1`/`plugin` | WB-08 |
| **D6** ✅ | May node state read operate-lane provenance? | ✅ **SIGNED 2026-09-22 — not into `tested`.** Add a distinct *ran* badge from `lastRunCounts`; reword the finding. Keeps `pipeline-editor.md:693/701` intact — written beside those lines the same day. | E3, E5 `pipeline-graph.ts:103`, `editor:459,1173` | merging lanes; a second readiness opinion | WB-11 |
| **D7** ✅ | Is `parsing.<frontend>.segments{}` a schema for the arming gate? | ✅ **SIGNED 2026-09-22 — yes.** It is the only schema source a segment-routed frontend has, and the validator already treats the config as sound. One predicate, both call sites. Written into `okf/backend/pipeline-graph/pipeline-config-keys.md` the same day. | E1, E5 `ConfigRoutes:99-103`, `ConfigPreviewRoutes:40` | a shipped ASN.1 Pipeline that can never be saved | WB-03 |
| **D8** ✅ | Reopen the declined orphan-drop? | ✅ **SIGNED 2026-09-22 — yes, narrowly.** The decline (`pipeline-editor.md:336`) assumed the Recipe view's insert-between as the alternative; that component was deleted in `6d3c68fa`. Wire an added Step after the **selected** node only; bare add otherwise. The decline is amended in place the same day. | E5 `:2511-2517`, git | auto-connect with no selection | WB-14 |
| **D9** ✅ | Rename `GET …/graph`? | ✅ **SIGNED 2026-09-22 — no.** Add `links.roundTrip` + a projection flag to its response and one sentence at the route. Renaming touches every client for a discoverability gain a link provides. Written into `pipeline-authoring.md` §7's pointer row the same day. | E1 26/26, E3 | `/graph/view` | WB-18 |

✅ **All eight owed calls were signed 2026-09-22, each as recommended**, and each was written into its
durable home (§8.2) the same day rather than waiting for its item to ship. ⛔ The `✍` markers in §3 are
therefore discharged; nothing on the register is decision-blocked. *(Kept for provenance: an amendment to
`D2` would change `WB-09`'s columns; to `D5`, `WB-08` would become a named refusal (S, not M–L); to `D8`,
`WB-14` would be dropped.)*

---

## 5. Contracts

### 5.1 Batch audit CSV — `WB-09` (after `D2`)

```
consignment_id,pipeline,schema_name,output_table,start_time,end_time,status,
member_count,rejected_files,rejected_rows,total_input_rows,total_output_rows,
output_file_count,total_output_bytes,duration_ms,error,cast_failures
```
Invariant per batch: `total_input_rows = total_output_rows + rejected_rows` when no Step drops rows
between parse and sink (gate `G-B2`). `rejected_files` keeps today's value.

### 5.2 Errors CSV — `WB-10`

```
line_number,columns,reason,raw_line
18,"c9-c17",MISSING COLUMNS,"20260904-000016|MED-20260904-001|…"
```
One row per source line. `columns` is a range or list; `reason` is DuckDB's `error_type`.

### 5.3 Reference refusal on test paths — `WB-05`

```json
{ "error": { "errorCode": "CONFIG_VALIDATION_FAILED",
  "details": { "refusals": [ { "code": "JOIN_REFERENCE_MISSING", "nodeId": "join",
     "message": "reference 'spaces/default/data/ref/region_dim.csv' does not resolve — run tools/seed-samples.mjs default, or point the join at a registered Reference" } ] } } }
```
Same `code` the save path emits (`PipelineValidator:101`).

### 5.4 Dry-run body — `WB-06`

`POST …/authored/{id}/dry-run` body **must** be an object: `{ "sampleRows": [ {…}, … ], "pipeline"?: {…} }`.
A non-object body → `400 "body must be an object: {sampleRows:[…], pipeline?}"`. Unchanged otherwise.

### 5.5 Test run over a non-file Collector — `WB-07`

`501 "run-to-here supports file sources only; this Pipeline's Collector is 'dataset' — test it by triggering
a real run"`. Same route, same status the connection path already uses.

### 5.6 Test-run result for a segment-routed frontend — `WB-08`

`PipelineRunResult` shape unchanged. `relations[]` gains one entry per `route:<segment>` out of the parser
and one `unmatched` entry; `seedNode` stays the parser. Clients that ignore unknown `rel` values are
unaffected.

### 5.7 Projection response — `WB-18`

`GET …/graph` adds `links.roundTrip: "/api/v1/spaces/{s}/pipelines/{n}/graph/raw"` and
`metadata.projection: "read-only"`. Body otherwise unchanged; `PUT` semantics unchanged.

### 5.8 Seed script — `WB-16`

`node tools/seed-samples.mjs <space> [--dry-run]` · `--all`. For each `*_pipeline.toon` in the space: copy
`data/samples/<name>/*` (or `<dir>/`) into `dirs.poll`; copy `data/samples/ref/*` into `data/ref/`. Prints
the plan; exit 1 if a Pipeline names a sample dir that does not exist.

---

## 6. Acceptance gates — falsifiable, house style

Every gate names the input, the observation and what would refute it. Baseline gates hold today and are
re-checked on every change.

### 6.1 Baseline (hold today)

| Gate | Holds by | Check |
|---|---|---|
| G-0.1 | E1 | Projection PUT is refused 422 with `written:false` on all 26; the file is byte-identical after |
| G-0.2 | E1 | Lossless PUT preserves the key set and re-reads identical node configs on 25/26 (`asn1_example` excepted until `WB-03`) |
| G-0.3 | E2 | Test run over one sample reaches the Step after the parser for the six file frontends (rows > 0) |
| G-0.4 | E2 | `filter` reports `dropped` with a count equal to input − kept |

### 6.2 Foundations

| Gate | Item | Passes when | Refuted if |
|---|---|---|---|
| G-F1 | WB-01 | the new test runs every committed Pipeline through the real `PUT` and is **red on `asn1_example`** before `WB-03`, green after | it is green on `asn1_example` before `WB-03` (then it is not going through the gate) |
| G-F2 | WB-02 | both tooltips name what the instrument seeds from | a reader of the toolbar alone cannot say which one parses files |
| G-F3 | WB-16 | on a fresh clone, `seed-samples.mjs --all` then test run: `join_step` and `orders_enriched_rollup` reach `join` with rows > 0 | either still 422 |

### 6.3 Sprint A — one predicate

| Gate | Item | Passes when | Refuted if |
|---|---|---|---|
| G-A1 | WB-03 | for every committed Pipeline, `/validate` ERROR-set equals the `PUT /graph` refusal set; `asn1_example` saves | any Pipeline validates clean and is refused on save, or vice versa |
| G-A2 | WB-03 | an active Pipeline with **no** schema source of any kind is still refused `ERR_ARMED_WITHOUT_SCHEMA` on both paths | the predicate widened to "anything" |
| G-A3 | WB-04 | 0 of the 6 non-delimited Pipelines emit a `csv_settings.*` warning; all 15 delimited ones still do when the lists are empty | the rule stopped firing for delimited too |
| G-A4 | WB-05 | a join with a missing reference path returns `JOIN_REFERENCE_MISSING` on save, dry-run and test run, and the message names the path | any of the three shows a DuckDB internal |
| G-A5 | WB-06 | a bare-array body → 400 with the expected shape; `{sampleRows:[]}` still → 400 "at least one sample row" | a 500 remains reachable with a valid JSON body |
| G-A6 | WB-07 | `orders_by_region_feed` test run → 501 naming `dataset`; `postmed_xdr` → 200 unchanged | a file Collector is refused |

### 6.4 Sprint B — walk and accounting

| Gate | Item | Passes when | Refuted if |
|---|---|---|---|
| G-B1 | WB-08 | `asn1_example`: `relations` contains `map_moCallRecord` with rows > 0 and `quarantine` with `unmatched` ≥ 0; `xml_example`: `map_order` rows > 0; `postmed_xdr` unchanged (400 at `map`) | delimited results change, or the walker gained a frontend branch |
| G-B2 | WB-09 | the 20-row defect file: `total_input_rows=20`, `rejected_rows=1`, `rejected_files=0`, `total_output_rows=19`; clean files: `rejected_rows=0` | the ledger still reconciles 19 = 19 with a record missing |
| G-B3 | WB-09 | a quarantined member still yields `rejected_files=1` | file-grain information was lost in the split |
| G-B4 | WB-10 | the same file yields **one** errors row for line 18 with `columns` `c9-c17` | nine rows remain |

### 6.5 Sprint C — trust and shape

| Gate | Item | Passes when | Refuted if |
|---|---|---|---|
| G-C1 | WB-11 | a Pipeline with a batch in `/provenance` shows the *ran* badge; its `NodeStatus` is unchanged by that badge; `testedStatus` still comes only from the dialog | `computeNodeStatus` reads `lastRunCounts` |
| G-C2 | WB-12 | after a test run, the dry-run panel offers the run's seed rows without a Parse-drawer visit | the panel still starts from `[ {} ]` |
| G-C3 | WB-13 | Business lens: 0 enabled `Add …` controls, `aria-disabled` on each; authoring lens unchanged | the spec passes with the palette absent from the DOM (hiding is not the ask) |
| G-C4 | WB-14 | with `map` selected, adding a filter yields edges `map → filter → sink` and validation reports 0 orphans; with nothing selected, the bare add is unchanged | the old edge survives alongside the new (fan-out) |
| G-C5 | WB-15 | creating `x` through the route writes `config/x/x_pipeline.toon`; a schema satellite lands beside it | a satellite lands at the root |
| G-C6 | WB-18 | `GET …/graph` carries `links.roundTrip` resolving to a 200 | — |

---

## 6a. What `WB-01` measured on the day it landed (2026-09-22)

The guard is also the first census of the write path taken through the route rather than in process:

| Outcome | Count | Which |
|---|---|---|
| **Saved losslessly** — PUT `written:true` and the re-read editable graph is IDENTICAL | **25** | every shipped Pipeline but `asn1_example` |
| **Refused, known and pinned** | **1** | `asn1_example` — 422 `ERR_ARMED_WITHOUT_SCHEMA`, *"active: true but no schema is configured"* |

✅ So the passthrough contract holds **through the HTTP route**, not merely at the in-process seam — which
is what `§1.1`'s two properties claimed and nothing checked. ⚠ The refusal is an INDEPENDENT reproduction
of `SAVE-GATE-VS-VALIDATE-DISAGREE-1`: it was found by driving the corpus, not by re-reading the row.

🔴 **Three harness defects were paid for before the guard was honest**, each of which would have made it
report the wrong thing, and all three are the same shape — *a guard that fails toward passing, or toward a
message that says nothing*:

1. **The pin check ran BEFORE the census**, so the first two runs reported *“expected 1 refusal, got 0”*
   and printed nothing about what the write path did. The census now goes first. A run spent to learn only
   what the bookkeeping expected is a run wasted.
2. **Staging relativized from the config root while the path rewrite assumed the space root**, so every
   staged config looked for its schema one directory too high and **all 26 fixtures** failed with
   *"registered no pipeline at all"* — which reads exactly like 26 broken Pipelines and was a one-line
   harness bug.
3. **An absolute Windows path begins `C:/`, and inside a TOON array row that parses as a KEY.**
   `sinks[2]{database,format}:` rows in `route_step` stopped being rows (*"Array length mismatch: declared
   2, found 0"*). The rewritten field is now quoted. ⚠ Staging relative to `target/` was tried first and
   **rejected**: it removes the drive letter but made five at-rest Step Pipelines refuse with *"must
   declare a top-level `output_store`"* for reasons never established — **an unexplained failure inside a
   guard is worse than the problem it fixes.**

---

## 7. Corrections and lessons carried forward

1. **"You cannot test the parse stage in the builder" — false.** *Run to here* is that test and has been
   since 2026-08-14. The evaluator drove the dry-run panel (post-parse by design) and generalised. Two
   instruments sat side by side with one-word tooltips; `WB-02` exists because of this.
2. **"Not yet tested" is a recorded decision** (`pipeline-editor.md:693,701`), not a gap; and the test lane
   omits provenance as its safety property (`pipeline-test-run.md:43`). `WB-11` adds a badge, not an opinion.
3. **The Recipe view does not exist.** `pipeline-editor.md:550` says `<app-pipeline-step-cards>` *is* the
   ordered chain editor with insert-between; it was deleted in `6d3c68fa`. A row was rescoped on that
   sentence earlier the same day and has been re-edited; the concept now records the removal (`WB-20`).
   ⇒ **A concept page is a decision record, not a source-of-truth for what exists** — verify existence in
   source before citing a component.
4. **"`collector: dataset` would hit the 501"** — a subagent's inference from the predicate; measured 200.
   `testRunRoot` reaches the 501 only through a bound connection profile (`:583-595`). Read the branch, not
   the message.
5. **The reject discrepancy is not a missing counter** — it is two quantities under one name plus a value
   dropped between records (`IngestResult.errorRows` → nowhere). Diagnose the data path before naming a fix.
6. **A wrong probe reports a clean pass.** A citation whose first segment is not a real directory is not
   a repository path to the guard; a bare-array body never reaches the 400 branch. Confirm a probe can
   fail before believing it did not.
7. **The formatting churn is not corruption.** Unquoted scalars re-read identically; `verbatim` in the
   round-trip docs means the decoded map. `D4` is about diffs, not data.
8. **Documents did not build Link Analysis; an item list did.** Nine plan documents, eight archived; the
   surviving artefact is a register. This page's answer to that is §8, not a resolution.

## 8. Lifecycle — how this document leaves the in-flight tier

CLAUDE.md's rule is that a plan lives in `docs/superpower/` **only while its work is in flight**, and that
when work ships the durable facts are distilled into the matching OKF concept, open items move to
`BACKLOG.md`, and the plan is `git mv`ed to `plans-archive/`. Link Analysis shows the failure mode that
rule does not prevent: a plan that is *never worked* is never "shipped", so it never triggers archival,
and sits in the active tier looking like intent. This section closes that gap.

### 8.1 Three exits, one of which must fire

| Exit | Trigger | What happens |
|---|---|---|
| **Shipped** | every `WB-nn` in §3 is ✅ or explicitly ⛔ refused with a decision entry | distil per §8.2; move remaining ⛔ rationale to `BACKLOG.md` §6 *Standing refusals*; `git mv` to `plans-archive/`; INDEX row moves to the archive table |
| **Partially shipped, stalled** | **no `WB-nn` changes state for 30 days** (next check: **2026-10-22**) | the shipped items are distilled per §8.2 *now*; the unshipped remainder is re-filed as `BACKLOG.md` rows with the plan's evidence copied into each; the plan is archived with the header *"PARTIAL — stalled <date>; items X..Y unbuilt, see rows"*. ⛔ It is **not** kept in the active tier as intent. |
| **Superseded** | the operator refuses the model in §2 or the scope changes | archive with the refusal recorded in the header; nothing is "kept for later" |

The 30-day clock is deliberate and short: this plan's items are S–M with one M–L; a month with no state
change means it is not being worked, and a register nobody works is documentation debt with a
misleading tier label. ⚠ The clock is checked by hand at each `handoff` — there is no guard for it. If a
guard is wanted, it is one rule in `check-doc-counts`' spirit: *a `superpower/*.md` whose INDEX row's date
is > 30 days old and whose register has no ✅ fails the build*. That guard is **not** in this plan's scope;
it is noted so the next person to hit this does not think it was overlooked.

### 8.2 The distillation map — decided now, so archival is mechanical

Each durable fact this plan carries already has a home. Nothing here is knowledge that exists *only* in
this plan; what the plan adds is the register and the model, and those are what get archived.

| Fact (from §1.4 / §4) | Durable home | Written when |
|---|---|---|
| Test-run seed is one relation per segment; `liveInbound` follows any present rel | `okf/backend/engine/pipeline-test-run.md` (the walk section) | ✅ **written 2026-09-22** (decision `D5` + the measured as-built gap); flips to as-built when `WB-08` ships |
| One "has a schema" predicate; `segments{}` is a schema source; `/validate` calls the gate's predicate (`D7`) | `okf/backend/pipeline-graph/pipeline-config-keys.md` (arming rules) | ✅ **written 2026-09-22** (decision); as-built note flips when `WB-03` ships |
| `rejected_files` / `rejected_rows` semantics; input = parsed + rejected (`D2`) | `okf/backend/engine/consignment-status-flow.md` (batch ledger section) and `step-catalog.md` (the *never silently dropped* promise, scoped to Steps until ingest follows) | ✅ **written 2026-09-22** (decision); the promise's ingest half flips when `WB-09` ships |
| Reference refusal is one code on all three paths | `okf/backend/pipeline-graph/editable-round-trip.md` (DRYRUN-1 section) | `WB-05` ships |
| The projection carries `links.roundTrip` (`D9`) | `pipeline-authoring.md` pointer-table row for `PipelineLift.lift` + §4 | ✅ **written 2026-09-22** (decision); flips to as-built when `WB-18` ships |
| `config/<id>/` is the write location for created Pipelines (`D3`) | `pipeline-authoring.md` (beside the 2026-09-06 id/paths decision) + `pipeline-editor.md` (`pipelineScaffold`) | ✅ **written 2026-09-22** (decision); flips to as-built when `WB-15` ships |
| Formatting churn accepted; verbatim = decoded map (`D4`) | `editable-round-trip.md` (beside the standing sweep gate) | ✅ **written 2026-09-22** — `WB-19` is now documentation-complete; only `WB-01`'s guard remains |
| *Ran* badge is a third source, not a readiness opinion (`D6`) | `pipeline-editor.md` beside `:693/:701` | ✅ **written 2026-09-22** (decision); flips to as-built when `WB-11` ships |
| The orphan-drop decline is reopened; selected-node insert (`D8`) | `pipeline-editor.md:336` (the decline, amended in place) | ✅ **written 2026-09-22**, the day `D8` was signed |
| The Recipe view no longer exists | `pipeline-editor.md:550` | ✅ **already written** (this change) |
| The round-trip guard goes through the HTTP route, and why | `okf/backend/build-run/guard-coverage.md` | `WB-01` ships |
| Which surfaces disagreed and why that is the defect shape (§1.5) | `pipeline-authoring.md` §*Corrections* | at archival, as one paragraph |

Rule: **a signed decision is written into its durable home the day it is signed**, not when its item
ships — a decision is a fact the moment the operator signs it, and the enterprise-scale-out plan's
`✅ SIGNED` rows show what happens otherwise (the plan becomes the only record).

### 8.3 What this document must never accumulate

- **No as-built facts that are not also in an OKF page or a BACKLOG row.** §1.4 is a *citation table*,
  not a home; every row points at source. If a fact appears here first, its durable home is written in
  the same change.
- **No second model.** §2 is one principle and one ladder. A change of model is a `D`-entry and, if
  accepted, a rewrite — not a §2.8.
- **No item without a gate.** Every `WB-nn` has a `G-*` row that says what refutes it. An item added
  without one is not on the register.
- **Size.** If this document passes ~600 lines, something in it belongs in a concept page or a row, and
  the excess moves there before anything else is added.

## References

- [`pipelines-workbench-moscow.md`](pipelines-workbench-moscow.md) — the v2 MoSCoW this plan executes; §2 corrections, §3.1 matrix
- [`postmed-xdr-pipeline-build.md`](postmed-xdr-pipeline-build.md) — the depth probe (E4)
- [`pipeline-test-run.md`](../okf/backend/engine/pipeline-test-run.md) — the test run's contract and jail
- [`editable-round-trip.md`](../okf/backend/pipeline-graph/editable-round-trip.md) — lift/lower and the sweep test
- [`pipeline-editor.md`](../okf/frontend/features/pipeline-editor.md) — the recorded decisions (`:336`, `:693`, `:701`)
- [`pipeline-authoring.md`](../okf/capabilities/pipeline-authoring/pipeline-authoring.md) — standing refusals and the id/path decision
- [`guard-coverage.md`](../okf/backend/build-run/guard-coverage.md) — why `WB-01` goes through the real route
