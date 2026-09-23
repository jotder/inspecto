---
type: Plan
title: Pipelines workbench — MoSCoW analysis (v2, multi-domain)
description: Must / Should / Could / Won't for the Pipelines workbench as a GENERIC authoring surface — grounded in a sweep of all 26 shipped Pipelines across three spaces, six parse frontends and six Step kinds, a test run over real bytes for 24 of them, the durable OKF concepts, and one from-scratch build as the depth probe. v1 (single telecom feed) was wrong in three places; §2 says where.
tags: [pipelines-workbench, moscow, prioritisation, pipeline-graph, evaluation, multi-domain]
timestamp: 2026-09-22T00:00:00Z
---

# Pipelines workbench — MoSCoW analysis (v2, multi-domain)

> 🔴 **ARCHIVED 2026-09-23 — EXECUTED.** This is the spec the archived
> [`workbench-trust-plan.md`](workbench-trust-plan.md) built: 18 of its 20 `WB-nn` items shipped, `WB-15`
> (`S3`, decision `D3`) was REFUSED when its premise failed a count, all decisions `D2`–`D9` were signed, and the Could-tier domain demos closed
> (`DEMO-CORPUS-FORMAT-COVERAGE-1`, `c1621799` + `a6492868`). ⛔ **Do not read it for current behaviour** —
> the matrices below are the 2026-09-22 measurement, and most of their 🔴 cells are fixed. The truth lives in
> [`pipeline-test-run.md`](../../okf/backend/engine/pipeline-test-run.md),
> [`pipeline-editor.md`](../../okf/frontend/features/pipeline-editor.md),
> [`pipeline-config-keys.md`](../../okf/backend/pipeline-graph/pipeline-config-keys.md),
> [`editable-round-trip.md`](../../okf/backend/pipeline-graph/editable-round-trip.md) and
> [`pipeline-authoring.md`](../../okf/capabilities/pipeline-authoring/pipeline-authoring.md). Still-open
> items went to the board: `S8` → `PIPELINE-RUN-HISTORY-OVERLAY-1`, `S9` → `WORKBENCH-RESPONSIVE-FLOOR-1`,
> the history/layout Coulds → `PIPELINE-CONFIG-HISTORY-AND-LAYOUT-1`. The telecom business processors stay
> `PLANNED` in `ProcessorCatalog`; assist-authored Pipelines stay *design first* in `pipeline-authoring.md`.

## 0. The question this prioritises against

> **Goal.** An engineer can author a production ingest Pipeline for **any** shipped format and domain in
> the workbench, convince themselves it is correct, arm it, and afterwards tell whether it is still
> correct — **without leaving the UI and without reading TOON by hand.**
>
> **Timebox.** The next release cycle. "Won't" means *not this cycle*, never *not ever*.

The goal is about **trust across formats**, not features. v1 of this page reached the same goal from one
pipe-delimited telecom feed, which is a sample of one; the operator's objection — *the plan must be
generic, nothing specific to postmed_xdr* — was correct, and v2 is the answer to it.

## 1. Evidence base — four readings, none of them opinion

| # | Reading | Scope | What it can and cannot tell |
|---|---|---|---|
| E1 | **API sweep of every shipped Pipeline** (`sweep.py`, 2026-09-22) | **26 Pipelines** — `default` 15 · `demo` 8 · `ucc` 3; lift, validate, lossless round-trip, display-projection PUT | Whether a behaviour is *universal* or a one-feed quirk. Cannot see the UI. |
| E2 | **Test run over real bytes** (`POST …/authored/{id}/run`, the editor's *Run to here*) | **24 Pipelines** that ship a sample file — delimited, PSV, JSON, Excel, fixed-width, text_regex, ASN.1 BER, XML; dedup, filter, route, sql, summarize, join | Which formats and Step kinds the builder's own test instrument can exercise. |
| E3 | **The durable OKF concepts** — `pipeline-editor.md`, `editable-round-trip.md`, `pipeline-authoring.md`, `pipeline-test-run.md`, `step-catalog.md` | What is a **recorded decision** vs a gap, and what is already shipped | Kept v1 from filing decisions as bugs — see §2. |
| E4 | **One from-scratch build**, driven in the browser | `postmed_xdr`, telecom, [`postmed-xdr-pipeline-build.md`](postmed-xdr-pipeline-build.md) | Depth: the only reading that saw the panes, the palette and the create flow. |

Processor taxonomy of record: `ProcessorCatalog` — **119 processors, 36 DELIVERED / 16 PARTIAL /
67 PLANNED** across eight families; `check-doc-counts` derives the same figures.

⚠ Everything below marked **measured** came from E1/E2 and is reproducible from the scratchpad
`sweep.py` against a served worktree. Everything marked **doc** is E3 and was verified against E1/E2
where it mattered (§2). Nothing is inferred from a code comment alone.

## 2. What v1 got wrong — corrections, not footnotes

A plan that quietly replaces its own errors teaches the next reader to trust plans. These are the load-bearing ones.

| v1 said | What is true | How it was settled |
|---|---|---|
| **`M1`/`M2` — "you cannot test the parse stage in the builder"** (row `DRYRUN-SEEDS-AFTER-PARSE-1`, now P3) | **The parse-stage test exists and works.** *Run to here* (`POST …/run?to=`) copies picked inbox files into a jailed scratch root and runs the **real frontend** over them; shipped 2026-08-14. **Measured:** 400 rows delimited, 5 JSON, 5 Excel, 3 fixed-width, 7 text_regex, 3 BER — all `seed=parse`, real per-Step relation counts downstream. v1 drove only the *dry-run panel*, which seeds after the parse **by design**, and generalised from it. | E3 → confirmed by E2 on three formats first, then 24 |
| **`D1` — "real frontend server-side or client approximation?"** | **Already decided: real, server-side, jailed** (`pipeline-test-run.md:12,70`). | E3, confirmed by E2 |
| **Could — "the `to=` cutoff is unbuilt server-side"** | **Built**, as the ancestor closure of the target with five pinned tests (`pipeline-test-run.md:95–119,147`). v1 carried a subagent's reading of a stale code comment. | E3 |
| **`M3` — "not yet tested" is a gap** (row `PIPELINE-NODE-TEST-STATE-STALE-1`, now P3) | **It is a recorded decision, twice:** *"the editor claims no validation it never ran"* (`pipeline-editor.md:701`) and *"never a second readiness opinion"* (`:693`); the test lane omits provenance **as its safety property** (`pipeline-test-run.md:43`). Changing it overturns a decision and can only ever join the *operate* lane. | E3 |
| `S1` — orphan drop is a usability gap (row `PALETTE-ADD-DROPS-ORPHAN-1`) | **Considered and declined** (`pipeline-editor.md:336`), and one-gesture *insert-between* **already exists** in the Recipe view (`:550`, CP-02 ✅). The residual ask is canvas parity. | E3 |
| `S4` — one shape per URL (row `GRAPH-READ-SHAPE-NOT-WRITE-SHAPE-1`) | **Deliberate split**, documented with a warning against exactly v1's mistake: *"do not cite `lift` as the round trip"* (`pipeline-authoring.md:550`). Residual ask is naming (`/graph` reads as the round-trip URL and is not). | E3 |
| `S8` — make `duplicate_check`'s grain obvious (row `DUPLICATE-CHECK-GRAIN-UNSTATED-1`) | The grain is stated **four times** in the durable docs (`step-catalog.md:171,447`; `pipeline-editor.md:270,272`). Only the UI label is silent. | E3 |
| `F2` — a missing counter (row `INGEST-REJECT-ACCOUNTING-1`) | **A documented-contract violation.** The docs promise *"never silently dropped"* (`step-catalog.md:91`) and *"a counted reject relation, so nothing disappears unaccounted"* (`:127`). The build measured a record disappearing unaccounted. **Upgraded**, not downgraded. | E3 + E4 |
| `W9` — cosmetic a11y (row `READONLY-LENS-PALETTE-ENABLED-1`) | **A documented-and-specced property that does not hold**: *"disables and hides individual affordances"* (`pipeline-authoring.md:313`), with editor specs pinning it for new/save/delete (`:591`) — and stopping short of the 36 palette controls. | E3 + E4 |

Two things v1 got right that the sweep made **universal** rather than anecdotal: the display projection
`GET …/graph` is not a valid `PUT` body on **26 of 26** (deliberate, above), and the lossless round-trip
**preserves every key on 25 of 25 that saved** while reformatting **24 of 25** — see §3.

## 3. As-built, measured across the corpus

### 3.1 The coverage matrix — format × surface

The artefact v1 could not produce. **Lift** = the editor renders the flat config as a typed graph;
**Drawer** = a typed Parse pane exists (E3); **Validate** = `/validate` returns no ERROR; **Save** = lossless
round-trip written with every key preserved; **Test run** = *Run to here* parses a real sample and rows
reach the Step after the parser.

| Frontend (shipped examples) | Lift | Drawer | Validate | Save | Test run reaches downstream |
|---|---|---|---|---|---|
| `parser.delimited` — 15 Pipelines, 5 domains | ✅ 15/15 | ✅ | ✅ | ✅ 15/15 | ✅ 14/14 sampled (400 · 660 · 622 rows…) |
| `parser.json` — 3 | ✅ | ✅ | ⚠ false `csv_settings` warning | ✅ | ✅ 3/3 |
| `parser.xlsx` — 1 | ✅ | ✅ | ⚠ false `csv_settings` warning | ✅ | ✅ 5 rows |
| `parser.fixedwidth` — 1 (+ `sites` text) | ✅ | ✅ (text) · dialog (binary, by decision) | ⚠ false `csv_settings` warning | ✅ | ✅ 3 · 7 rows |
| `parser.text_regex` — 1 | ✅ | ✅ | ⚠ partition warning (real) | ✅ | ✅ 7 rows |
| `parser.asn1` — 1 | ✅ | ✅ inline X.680 | ⚠ false `csv_settings` warning; **no ERROR** | 🔴 **REFUSED** `ERR_ARMED_WITHOUT_SCHEMA` | 🔴 parses 3 records, **reaches nothing** (`route:moCallRecord` edge not walked) |
| `parser.plugin` (XML) — 1 | ✅ | ✅ | ⚠ false `csv_settings` warning | ✅ | 🔴 parses 3, **reaches nothing** (`route:order`) |
| Dataset re-ingest (`collector: dataset`, parquet) — 1 | ✅ | ✅ | ⚠ false `csv_settings` warning | ✅ | ⚠ 200 *"no rows were parsed"* — a file test over a Dataset feed, not refused |

Reading the matrix: **six of eight frontends are on the delivered path end to end.** The two that are
not share one shape — a parser that **routes by record type** into per-segment mappers — and that shape
is exactly what pre-mediation telecom (ASN.1 CDR), XML documents and any multi-record-type feed need.
The `csv_settings` warning column is one rule fired on every non-delimited Pipeline (§4 `S10`).

### 3.2 Step kinds through the test run (measured, E2)

| Step kind | Example | Parsed → reached | Reading |
|---|---|---|---|
| `transform.dedup` | `dedup_step` | 8 → `dedup` 8 kept / 0 dup | real relation counts |
| `transform.filter` | `filter_step`, `orders` | 8 → 4 kept / 4 dropped · 11 → 6/5 | dropped rows are **counted** here (contrast `F2` at ingest) |
| `transform.route` | `route_step` | 18 → 8 / 10 by branch | branch fan-out visible |
| `transform.sql` | `sql_step` | 8 → 7 | |
| `transform.summarize` | `summarize_step` | 8 → 4 groups | |
| `transform.join` | `join_step`, `orders_enriched_rollup` | 🔴 **422** on test run **and** dry-run | not a join defect — see `S11`: the reference is at an unseeded path, and the error leaks a DuckDB internal instead of the refusal the *save* path already names (`JOIN_REFERENCE_MISSING`) |
| `transform.lookup` | `lookup_step` | — | ships **no sample file**; untestable as shipped |

### 3.3 The two properties that hold everywhere

- **Save is fail-closed** — the projection PUT was refused **26/26** with named findings and
  `written:false`; the one lossless-payload refusal (`asn1_example`) also left the file untouched.
- **Save preserves every key** — **25/25** written Pipelines re-read with **identical node configs**
  and **zero keys lost**, including engine-read/authoring-invisible `dirs` leaves. **24/25** were
  reformatted (blank lines, alignment, quoting, key order, trailing newline) — semantically lossless,
  textually noisy. ⚠ `editable-round-trip.md`'s "verbatim" means the **decoded map**, not the bytes; do
  not read it as whitespace preservation.

Neither property has a guard that would catch a regression (§4 `M5`).

## 4. The tiers — generic, revised

### MUST — the goal fails for some shipped format without these

**M1 · Segment-routed frontends must flow through the test run — GAP (measured, 2/8 frontends).**
`asn1_example` and `xml_example` both lift as `parse →(route:<segment>)→ map_<segment> → sink_<segment>`
with an `unmatched → quarantine` edge. *Run to here* parses them (3 records each) and then reports
*"the sample reached no node past the seed 'parse' — nothing downstream consumed it"*. The walk does not
follow a `route:` edge out of a parser. This is the pre-mediation telecom shape, the XML shape, and the
shape of every multi-record-type feed; for those the builder's only test instrument tests the decoder
and nothing after it. ✅ **`D5` signed 2026-09-22: walk the edge** — seed one relation per segment, as
production's multi-seed `execute` does; refusing segment-routed frontends by name was declined.
(`TESTRUN-SEGMENT-ROUTE-NO-FLOW-1`)

**M2 · The save gate and the validator must agree — GAP (measured).** For `asn1_example`,
`POST /validate` returns **no ERROR** while `PUT /graph` with the editor's own lossless payload refuses
**422 `ERR_ARMED_WITHOUT_SCHEMA`** — *"active: true but no schema is configured (processing.schema_file,
processing.schemas[], or a plugin ingester)"*. The gate does not count `parsing.asn1.segments{}` as a
schema. Net effect: a shipped, active, working ASN.1 Pipeline can be **opened but never saved** from the
workbench. Two gates, two answers, on the same config. ✅ **`D7` signed 2026-09-22: `segments{}` IS a
schema source** — one predicate, called by the gate and the validator alike.
(`SAVE-GATE-VS-VALIDATE-DISAGREE-1`)

**M3 · Reject accounting must honour the documented contract — GAP (E4, contract in E3).** A 20-row file
that lost one truncated record reported `rejected_count = 0` and `total_input_rows = 19`, against the
durable promise *"never silently dropped … a counted reject relation, so nothing disappears
unaccounted"*. The filter Step keeps that promise (§3.2); ingest does not. ✅ **`D2` signed 2026-09-22:
split it** — `rejected_files` **and** `rejected_rows`, with `total_input_rows` = parsed + rejected rows.
(`INGEST-REJECT-ACCOUNTING-1`)

**M4 · Reference-dependent examples must run on a fresh checkout — GAP (measured, 2 Pipelines).**
`join_step` and `orders_enriched_rollup` point at `spaces/<space>/data/ref/region_dim.csv`; the file
ships at `data/samples/ref/` and nothing copies it. Both fail test run **and** dry-run with a leaked
DuckDB internal (*"Attempting to execute an unsuccessful or closed pending query … No files found that
match the pattern"*) where the save path names the same condition `JOIN_REFERENCE_MISSING`. Seed the
reference in the same step that seeds the inbox, and name the refusal. (`REFERENCE-EXAMPLES-NEED-UNRUN-SEED-1`)

**M5 · Guard the two properties that already hold — SHIPPED, unguarded.** §3.3. One test:
`GET /graph/raw → PUT /graph → assert key set unchanged AND node configs re-read identical`, over the
fixture corpus. It would have caught a writer that started dropping keys, and it does not exist.

**M6 · The generic authoring lifecycle — SHIPPED (E3, E4).** Create with a format picker, open multi-tab,
rename, change id, delete with impact, duplicate, save-as-template, activate/deactivate, park a Step;
typed drawers for every frontend but binary fixed-width (a decision); *Run to here* over real bytes with
a jailed scratch root. Listed so the tier is honest about what it already gets.

### SHOULD — painful, with a workaround

| # | Item | Measured / doc | Workaround |
|---|---|---|---|
| S1 | **One-gesture insert, anywhere.** 🔴 **Corrected 2026-09-22 — this row twice said "the Recipe view already has insert-between, give the canvas parity". It does not: `<app-pipeline-step-cards>` was deleted in `6d3c68fa` (2026-09-18) and `inspecto-ui/src` has no Recipe view.** There is no one-gesture insert on any surface, and `insertNode` (`:2511-2517`) is a bare `addNodeToModel` that ignores selection. ✅ `D8` (signed 2026-09-22) reopens the decline narrowly: wire an added Step after the **selected** node, bare add otherwise. | E3 `:336` · E4 · E5 `:2511-2517` | ⛔ none — the named workaround does not exist; insert mid-chain by hand is three graph operations |
| S2 | **Format-preserving writer, or an accepted decision that churn is the cost.** 24/25 saves rewrite the file. Hand-editability is an asserted value (`pipeline-editor.md:255`). ✅ `D4` **signed 2026-09-22: the churn is ACCEPTED** — no writer is funded; `WB-01`'s guard is what protects the semantic round trip instead. The row survives as the guard, not the writer. | E1 | hand-restore |
| S3 | **Created Pipelines in `config/<name>/`.** Scaffold lands at the space config root; all shipped Pipelines use a per-Pipeline directory; *"one identity for id, file and directories"* (`pipeline-authoring.md:405`) points the same way, and `pipelineScaffold()` is the single choke point to guard. ✅ `D3` **signed 2026-09-22: `config/<id>/`, chosen server-side** (the client sends no path). | E4 · E3 | move by hand |
| S4 | **Name the projection.** `/graph` is the read-only view and `/graph/raw` the round-trip pair — deliberate, documented, and still the natural wrong guess for a client. ✅ `D9` **signed 2026-09-22: no rename** — the response carries `links.roundTrip` and a `readOnlyProjection` flag instead, so no client breaks. | E1 26/26 · E3 | use `/graph/raw` |
| S5 | **Disable the palette in the read-only lens.** 36 `Add …` controls render enabled where the docs say affordances are *"disabled and hidden"*; the handler guard holds. Extend the existing spec to the palette. | E4 · E3 `:313,:591` | none needed |
| S6 | **Node test state — a *ran* badge beside `tested`, not inside it.** v1's `M3`. ✅ `D6` **signed 2026-09-22: provenance may NOT feed `tested`** — `:693`/`:701` stand; the remedy is a third source (`lastRunCounts`) plus rewording the finding to *“not yet tested in this session”*. | E3 | `/runs` |
| S7 | **Dry-run panel seeds from the test run, not a textarea.** The parse-stage test exists (`M1` corrected); the panel that shows per-node samples still starts from hand-typed JSON, and the two instruments do not hand off. E3 says *"Use the captured sample"* appears once a Parse-drawer thread exists — two hops away and invisible from the panel. | E4 · E3 `:593` | run the Parse drawer first |
| S8 | **Run history, not just the last run.** Last-batch overlay only. | inventory | `/runs` |
| S9 | **A responsive floor for the 3-pane shell.** At ~660px the canvas is a sliver. | E4 | widen |
| S10 | **Frontend-aware validation rules.** *"csv_settings.date_formats is empty — TRY_STRPTIME will return NULL for any DATE column"* fires on **6 of 26** Pipelines that have **no** `csv_settings` (JSON, Excel, ASN.1, fixed-width, XML, parquet re-ingest). A Pipeline is born `clean:false` for a rule that cannot apply to it, which trains authors to skip warnings. (`VALIDATE-CSV-RULE-FRONTEND-BLIND-1`) | E1 | ignore it |
| S11 | **Name the missing-reference refusal on the test paths.** The save path says `JOIN_REFERENCE_MISSING`; test run and dry-run leak the DuckDB message. Same condition, one name. (`TESTRUN-REFERENCE-REFUSAL-UNNAMED-1`) | E2 | read the internal |
| S12 | **Malformed dry-run body → 400, not 500.** A bare JSON array (the natural first guess for "sample rows") produces `500 Cannot deserialize value of type LinkedHashMap…`; the contract is `{sampleRows:[…], pipeline?}`. (`DRYRUN-MALFORMED-BODY-500-1`) | E1 | read the service |
| S13 | **Dataset-fed Pipelines refuse a file test clearly.** `orders_by_region_feed` (`collector: dataset`) returns `200 "no rows were parsed"` for a file-based test run; the docs say non-local connectors are **501**. A Dataset feed is not local either. (`TESTRUN-DATASET-COLLECTOR-SILENT-1`) | E2 · E3 `:80` | none |
| S14 | **`duplicate_check` label text.** Grain is file/marker; the docs say so four times; the pane does not. | E3 | read the concept |
| S15 | **Errors report one row per bad line.** Nine rows for one truncated line, each repeating the raw line. No doc owns the artefact's shape. | E4 · E3 | read past it |

### COULD — real value, clearly deferrable

- **Persisted history / diff** of a Pipeline config; in-session undo/redo is capped at 50 and lost on reload.
- **Persisted node coordinates** — recomputed layout is fine at four nodes, less so for a routed graph.
- **Domain-shaped demos for the synthetic-only surfaces.** ASN.1, Excel, fixed-width, XML and `route`
  exist **only** as `spaces/default` examples; `lookup_step` ships no sample at all. A builder never sees
  these surfaces under realistic data, and neither did this sweep beyond one file each.
  (`DEMO-CORPUS-FORMAT-COVERAGE-1`)
- **Telecom business processors** — `transform.telecom.rating`, `.roaming` (TAP3/CIBER), `.simbox`,
  `parser.asn1.per` are `PLANNED`; `parser.asn1.ber` is delivered. The telecom gap is business logic,
  not ingestion — and after `M1`, not testing either.
- **Assist-authored Pipelines** — unexercised; `pipeline-authoring.md:428` says *"design first"*.

### WON'T — this cycle, with the reason

- **The 67 `PLANNED` processors as a wave.** The palette already renders them honestly; pull forward on demand.
- **Analytics / Time-Series / Semantic inside the ingest canvas** (0 delivered / 3 partial / 11 planned) — Studio's surface.
- **Fan-in beyond the canvas, sub-Pipeline nesting** — `⛔ DEFERRED (D6)` and `⛔ REFUSED by design` in `pipeline-authoring.md:517,533`; not reopened here.
- **Collaborative editing** — no demand recorded; guards assume one author per tab.

## 5. One screen

| Tier | Count | Shipped | The sentence |
|---|---|---|---|
| **Must** | 6 | 2 (`M5`, `M6`) | Six of eight frontends author, save and test end to end. The two that route by record type **test nothing past the decoder** and one of them **cannot be saved at all**; ingest reject accounting breaks a written promise; two shipped reference examples fail on a fresh checkout. |
| **Should** | 15 | 0 | Mostly one-change fixes; the recurring theme is **two surfaces that answer differently** (validate vs save, save vs test-run refusals, dry-run vs test run). |
| **Could** | 5 | 0 | History, layout, domain demos, telecom business logic. |
| **Won't** | 4 | — | The processor backlog as a wave; analytics on the ingest canvas; standing refusals. |

**If only three things ship:** `M1` (walk `route:` edges out of a parser in the test run), `M2` (one
schema notion for the gate and the validator), `M3` (reject accounting, after `D2`). Together they make the
builder trustworthy for the formats it already lifts and saves. `M5` is an afternoon and protects the
rest.

## 6. Domain lens — what each shipped domain needs, and whether it is on the delivered path

Grounded in the corpus, not imagined: each row names the shipped Pipeline(s) that represent the domain.

| Domain (Pipelines) | What its feed needs from the workbench | Delivered path? |
|---|---|---|
| **Telecom, post-mediation** (`postmed_xdr`, `roaming_tap`, `payments`-like PSV) | delimited parse, null-string handling, wide typed schemas, file-grain dedup, gap detection | ✅ end to end; the depth probe |
| **Telecom, pre-mediation** (`asn1_example`; `ucc/sites`, `topups`) | ASN.1 BER decode **routed by record type** into per-segment mappers, quarantine of unmatched | ⚠ decodes; **`M1` + `M2`** block test and save |
| **Retail / reference-joined** (`orders*`, `join_step`, `lookup_step`) | reference join at rest, lookup maps, summarize, `output_store:` | ⚠ authors and saves; **`M4`** blocks testing on a fresh checkout; lookup untestable as shipped |
| **Logistics, JSON** (`shipments`, `json_example`, `topups`) | NDJSON/array records, `records_path`, gap detection | ✅ end to end (5 · 3 · 7 rows) |
| **Financial crime** (`mule_transfers`) | delimited, high row counts (660), downstream Link Analysis | ✅ ingest end to end; analysis is another surface |
| **Document / XML** (`xml_example`) | plugin ingester routed by element | ⚠ same shape as ASN.1 — **`M1`** |
| **Spreadsheet** (`excel_example`) | sheet/range/header, `excel` extension fail-closed | ✅ (synthetic only — Could) |
| **Dataset re-ingest** (`orders_by_region_feed`) | a Pipeline fed by a Dataset, not files | ⚠ authors and saves; **`S13`** — file test does not apply and does not say so |

The lens says the same thing as the matrix from the other side: **the delivered path is
delimited/JSON/Excel/fixed-width over files**, and the gaps cluster on **routed-by-type decoding** and
**reference dependence**, which are domain-independent shapes rather than domain features.

## 7. Decisions owed

✅ **ALL EIGHT OWED CALLS WERE SIGNED 2026-09-22, each as recommended**, and each was written into its
durable home the same day — the map is `workbench-trust-plan.md` §8.2. Nothing in the tiers above is
decision-blocked any more; what is left is build order. The table is kept as the record of what was
asked and what was answered.

| # | Decision | Status | Why it blocks |
|---|---|---|---|
| D1 | Real frontend server-side vs client approximation for the parse test | **✅ answered** — real, server-side, jailed (`pipeline-test-run.md:12,70`) | — |
| D2 | Is `rejected_count` *records the parser refused* or *records not persisted*? | ✅ **signed 2026-09-22 — NEITHER: split it** into `rejected_files` + `rejected_rows` | unblocks `M3` / `WB-09`; `total_input_rows` = parsed + rejected rows |
| D3 | UI-created Pipelines move to `config/<name>/`, or the shipped ones flatten? | ✅ **signed 2026-09-22 — `config/<id>/`, chosen server-side** | unblocks `S3` / `WB-15`; satellites take the same subdir |
| D4 | Formatting churn accepted, or a format-preserving writer funded? | ✅ **signed 2026-09-22 — churn ACCEPTED, no writer funded** | `S2` becomes the guard (`WB-01`), not a writer |
| D5 | Should a `route:<segment>` edge out of a parser be walked by the test run, or does the test run declare segment-routed frontends out of scope with a named 422? | ✅ **signed 2026-09-22 — WALK it** (one seed relation per segment) | unblocks `M1` / `WB-08`; the refusal was declined |
| D6 | Does node test state ever read operate-lane provenance? Overturns `pipeline-editor.md:693/701`. | ✅ **signed 2026-09-22 — NOT into `tested`; a separate *ran* badge** | `:693`/`:701` stand; unblocks `S6` / `WB-11` |
| D7 | Is a `parsing.<frontend>.segments{}` block *a schema* for the arming gate? | ✅ **signed 2026-09-22 — YES**, one predicate for gate and validator alike | unblocks `M2` / `WB-03`; the disagreement ends |

## References

- [`postmed-xdr-pipeline-build.md`](postmed-xdr-pipeline-build.md) — the depth probe (E4) and its eleven filed rows
- [`pipeline-test-run.md`](../../okf/backend/engine/pipeline-test-run.md) — the test run this page's `M1` correction rests on
- [`editable-round-trip.md`](../../okf/backend/pipeline-graph/editable-round-trip.md) — the round-trip contract behind `M5` and `S2`
- [`pipeline-editor.md`](../../okf/frontend/features/pipeline-editor.md) — the recorded decisions behind `S1`, `S5`, `S6`
- [`pipeline-authoring.md`](../../okf/capabilities/pipeline-authoring/pipeline-authoring.md) — the standing refusals in *Won't*
- `inspecto-engine/src/main/java/com/gamma/pipeline/ProcessorCatalog.java` — the 119-processor taxonomy
