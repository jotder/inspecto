---
type: Capability
title: Ingestion, parsing and Pipeline authoring (ING, PIP) — plan
description: The plan of record for the ETL module — Stage-1 ingestion and parsing (ING) and Pipeline authoring (PIP-1) — its purpose and scope, requirements of record, dated decisions, what is not built, what was refused and how it is verified. The as-built mechanism lives in the engine hub, the user guide and the Step catalog.
resource: platform/inspecto-engine/src/main/java/com/gamma/inspector, platform/inspecto-etl/src/main/java/com/gamma/etl, platform/inspecto-engine/src/main/java/com/gamma/parse, platform/inspecto-engine/src/main/java/com/gamma/pipeline
tags: [ing, pip, capability, ingestion, parsing, consignment, grammar, parser-plugin, quarantine, expectation, pipeline, authoring, step-catalog, plan]
timestamp: 2026-11-18T00:00:00Z
---

# Ingestion, parsing and Pipeline authoring — plan (`ING`, `PIP-1`)

**What this covers.** The record for the two halves of the ETL module: **ingestion** (how files in a
Pipeline's inbox become typed, committed rows) and **Pipeline authoring** (how a person describes a
Pipeline, how that description is checked, and how it survives the editor). It says what was required, what
was decided and why, what is still open, what was refused, and which tests prove it. Read it before proposing a
change, so a settled question is not reopened. How the engine runs a Pipeline is in the
[engine hub](../engine/plan.md); how an author uses the editor is in [the user guide](user-guide.md).

**Contents**

1. [Purpose & scope](#1-purpose--scope)
2. [Requirements of record](#2-requirements-of-record)
3. [Where the as-built mechanism lives](#3-where-the-as-built-mechanism-lives)
4. [Decisions](#4-decisions)
5. [Not built and open](#5-not-built-and-open)
6. [Refused & superseded](#6-refused--superseded)
7. [Verification](#7-verification)
8. [References](#8-references)

> **Canonical vocabulary** (`GLOSSARY.md` §2, §5, §6-B — binding). A **Consignment** is a set of files ingested
> and processed together as one unit of work; ⛔ never *Batch* for the entity (the wire spellings `batch_id` /
> `batches` stay by decision). A **Parser** reads one format into rows; a **Grammar** is the authored options
> that tell it how, living **inline** on the parse Step; a **Grammar Template** is a Grammar saved to the
> `grammar` kind as a **copy source** — ⛔ never "linked", "shared" or "referenced". An **Archive** is a
> container file; an **Entry** is one inner file — ⛔ never *member*; **Unpack** is the Collector-level stage
> ([acquire spec](../acquire/spec.md#part-3--the-unpack-stage)). An **Expectation** is the data-quality rule
> engine; a **Segment** is one record kind a plugin ingester emits (⚠ undefined in the glossary — §5).
>
> ⛔ **`PIP` is one requirement range and two plans.** `GLOSSARY.md` §14 fixes the split: **authoring** —
> the editor, the config contract, the Step catalog — is this file; **execution** — Run, Job, Trigger,
> Executable — is the [engine plan](../engine/plan.md). Of the seven rows in `REQUIREMENTS.md` §3.3, **only
> `PIP-1` is authoring**; `PIP-2` through `PIP-7` are the engine's. Where this file disagrees with
> `docs/REQUIREMENTS.md` or an `docs/EDITIONS.md` row, **this file wins** and the disagreement is stated in place.

## 1. Purpose & scope

### 1.1 Ingestion (`ING`)

ING is **the road from a file in the inbox to rows a Dataset can read**: how files are grouped into a
Consignment, how each is decoded whatever its format, how records are typed and where the ones that fail go,
and how the whole group lands atomically or not at all. Its defining properties are that **every format
converges on one DuckDB backend** — native reads where DuckDB can, Java decoders wrapping the same core where it
cannot — and that **a failed Consignment leaves no trace of having been processed**, so retry is implicit.

**In scope:** the poll cycle's ingest half (`CollectorProcessor.ingest`, `MultiCollectorProcessor`),
`ConsignmentPlanner` and `collector.consignment`; `ConsignmentIngestor`, the two `ConsignmentIngestStrategy`
implementations, union and generation modes, the crash-safe commit order (`CommitLog`, `MarkerManager`,
`PartitionWriter` staging and reveal); the `parsing:` block and its **10**<!--count:parsing-frontend-tokens--> frontend tokens; `ParserPlugin` and
`Parsers`; `StreamingFileIngester` / `RecordSink` / `DuckDbRecordSink` (the wrap-SPI); the delimited, fixed-width,
JSON, text/regex, XLSX, Parquet, ASN.1 and XML lanes; Grammars, Grammar Templates and the `grammar` kind;
schema casting, cast-failure measurement, quarantine reasons and reject rows; Expectations; the source time
zone at parse; the Parse pane, grammar drawer and previews. (The Unpack stage is acquisition's:
[acquire plan](../acquire/plan.md).)

**Not in scope, and deliberately so:**

| Adjacent concern | Whose it is |
|---|---|
| Reaching the remote system, deciding a file is new and stable, landing it in the inbox, Unpack | `ACQ` — ING starts where `acquire` hands to `ingest` |
| The Schema and Mapping *registry*, `SchemaFieldTypes` as vocabulary, `fields[]` vs `rules[]` | `MET` — ING *applies* the cast and reports its failures |
| The Record Transformer (`transform.sql`), Steps, the graph lane, `route:`, the at-rest chain | `PIP` — ING owns the flat ingest lane up to the landed store |
| What a landed store means to a read, `DatasetRelation`, the store-layout contract | `DAT` |
| Decision Rules and Alert Rules (the other two rule engines) | `PIP` / `INC` — ING owns Expectations because `REQUIREMENTS.md` homes them here |
| The signal ledger, gauges as an observability surface | `OPS` |

### 1.2 Pipeline authoring (`PIP-1`)

This capability is **how a Pipeline comes to exist**. Not how it runs — how a person describes it, how that
description is validated, and how it survives a round trip through an editor without losing anything.

Two facts organise everything else, and both are decisions rather than consequences:

> **The graph is DERIVED, never stored.** No file contains nodes and edges. The flat `*_pipeline.toon` is
> the sole on-disk truth; the graph is a projection lifted from it and lowered back onto it.

> **A Step passes a TOKEN, not data.** A Step does not accept or emit rows — it emits control information
> that may carry a path to data. The `data` edge is a fiction: it expresses "this Step's output is that
> Step's input", which is a reference, not a flow of records.

**In scope:** the config contract (the flat file's blocks, who declares each, which keys are executable);
the derived graph (lift, lower, the verbatim round trip that protects unmodelled keys); the Step catalog — the
**49**<!--count:node-types--> built-in node types, the processor taxonomy, and the difference between a type
being *authorable*, *lowerable* and *executable*; the registration points a new Step kind must reach;
author-time validation; the transform surface; identity and lifecycle of the artifact; the editor itself.

**Out of scope:** everything about running — the scheduler, Jobs, Triggers, Runs, ledgers, the ingest lanes
and the at-rest job ([engine plan](../engine/plan.md)), including the fact that an authored `steps:` chain has
**two** execution homes; Grammar authoring detail ([grammar configuration](../config/user-guide-grammar-config.md));
the processor taxonomy's per-row product status (generated; `EDITIONS.md` owns it).

## 2. Requirements of record

### 2.1 Ingestion

Six requirements from `REQUIREMENTS.md` §3.2 (that section was stripped to an index on 2026-09-09 — this file
is their only home), all recorded shipped. **Two describe a narrower build than exists, and one describes a
broader one.** ⚠ **`EDITIONS.md`'s feature × edition matrix is authoritative for the Edition column**; this
table mirrors it.

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| `ING-1` | Stage-1 M..N multiplexer: parse → transform → partition → commit, batch-atomic | Must | ✅ SHIPPED | All |
| `ING-2` | Format frontends: delimited grammar, fixed-width (text + binary), plugin `StreamingFileIngester` SPI | Must | ✅ SHIPPED — ⚠ **understated**: the built set is **eight formats** | All |
| `ING-3` | Compressed input streaming (gzip / bz2 / zip) | Must | ✅ SHIPPED — **superseded in shape** by the Unpack stage (2026-08-23): streams *and* Archives, at the Collector, with caps and a verdict ([acquire spec](../acquire/spec.md#part-3--the-unpack-stage)) | All |
| `ING-4` | Schema casting + reject routing (quarantine: unreadable / mismatch / sink-flush fail) | Must | ✅ SHIPPED | All |
| `ING-5` | Unified `parsing:` block + JSON/NDJSON + text/regex frontends | Must | ✅ SHIPPED 2026-07-07 — ⚠ "LDIF block-records stay PROPOSED" is stale: blank-line record splitting is **live** via `text_regex.record_split` (2026-08-28) | All |
| `ING-6` | **Expectation** engine: data-quality rules against a Schema | Must | ✅ **SHIPPED, server and UI.** `/expectations*` CRUD + evaluate; the SPA's Expectations pane (`/admin/expectations`, `inspecto-ui/src/app/modules/admin/expectations/`, `ExpectationsService`, dating from 2026-07-04, C2); evaluation is a COUNT over **at-rest Parquet**, not at ingest | All |

**Corrections this table makes to its predecessor and the concept pages**, each verified against source:

- **`ING-2` names three frontends; `PipelineConfigParser.FRONTENDS` accepts **10**<!--count:parsing-frontend-tokens--> tokens for
  eight formats** — `delimited`, `fixedwidth`/`fixed_width`, `json`, `text_regex`, `xlsx`/`excel`, `parquet`,
  `asn1`, `plugin` — and `BuiltinParsers.IDS` serves **6**<!--count:builtin-parsers--> DuckDB-native built-ins including **`parquet`**,
  which appears on **no** board row, no `step-catalog.md` row and no `EDITIONS.md` `SP-PRS-*` row. The
  documentation stated the count as three, five, six, seven and ten in six places; the code says ten tokens,
  eight formats, six built-ins.
- ✅ **`ING-6` has a product surface** (corrected 2026-11-15): `ExpectationRoutes` serves list / evaluate /
  evaluate-one / create / update / delete, and the SPA's Expectations pane calls it; the 2026-09 "zero
  occurrences" search was a false negative.
- **`ING-5`'s LDIF note is stale**; the parsing-options reference contradicted itself on it and was corrected.
- **`EDITIONS.md` `ING-05` promises "nested archives"**; `UnpackLimits.depth` defaults to **1** and any other
  value is **refused by name** — a `.tar.gz` inside a `.zip` is extracted as an opaque Entry and quarantines.
- **The wrap-SPI page named the mode selector `processing.streaming.generation_threshold_bytes`**; the
  parser reads **`large_file_bytes`** (`PipelineConfigParser.java`) and no other page or code uses the first name.
- **The seams page described a `DATA_RULES` registry**; the types are `ConsignmentIngestStrategy` /
  `CsvIngestStrategy` / `StreamingPluginIngestStrategy` (2026-08-31) and `DATA_RULES` does not exist.

**Expectations, as built (`ING-6`).** `com.gamma.expectation` (`inspecto`): an `expectation` component (a
condition tree in the shared query-types grammar) targets a pipeline or job output; **`ExpectationEvaluator`**
counts violating records with a server-built `COUNT` over the target's **at-rest Parquet** in a DuckDB sandbox
(unsealed — it reads Parquet by absolute path); a FAILED check opens a deduped `expectation:<name>` Incident
through `IncidentAccess` and emits `EXPECTATION_FAILED` → notifications. Routes (`ExpectationRoutes.java`):
`GET /expectations`, `POST /expectations/evaluate`, `POST /expectations/{name}/evaluate`, `POST | PUT | DELETE`
gated `canAuthorWorkbench`. ⚠ **This is not ingest-time validation** — `REQUIREMENTS.md` homes it under ING and
words it "validating records against a Schema"; the glossary homes it under Rules; the build is an at-rest
count. ⚠ No page owns Expectations as a subject (§5).

**One standing note no status token can carry:** ⚠ **A Consignment that fails leaves nothing behind on
purpose.** Markers and fingerprints are written **last** in the commit order, so a FAILED Consignment has no
"already processed" record and its files are rediscovered on the next poll — retry is implicit, bounded since
X1 by `-Dingest.retry.max` (exhaustion quarantines with reason `retry_exhausted`). Read every status and every
ledger with that in mind: a file's own outcome and its Consignment's outcome are **two facts** (a `COMMITTED`
file inside a `FAILED` Consignment is normal), and collapsing them loses the question reprocessing asks.

### 2.2 Pipeline authoring — the one row

| Row | Register says | Correction of record |
|---|---|---|
| **PIP-1** | Authored Pipeline DAGs, Steps = the **closed** `BuiltinNodeType` set, with **author-time validation** and a visual editor — `SHIPPED`, all editions | ⚠ **Three corrections.** (a) **"Closed" is superseded doctrine.** `GLOSSARY.md` D0-B (2026-08-09) retired the closure: the real guarantee is compiler *totality* — a pipeline that parses is a pipeline that runs — which survives an **open** Step-kind registry. That registry is live: `PipelineNodeTypes` loads every `PipelineNodeType` provider on the classpath (and `JobPackManager` from a pack's loader), and the `StepKindRegistry` SPI (`inspecto-etl`, answered by `NodeTypeStepKinds`) lets a `steps:` kind name a registered plugin type — so the set is open, and `BuiltinNodeType` is only its built-in part. (b) 🔴 **"Author-time validation" does not hold for an unknown node type** — the validator flags it as a **WARNING**, the codec (`PipelineCodec`) stores `config` as an unchecked map, and the compiler silently drops an unknown-typed node. An invented vocabulary survives unnoticed. (c) ✅ **Author SQL is guarded at save** since 2026-09-09 (`PipelineEditable.refuseUnsafeSql`, `SqlSavePathGuardTest`). The row's own cell also reads `SHIPPED` and "is **not** shipped" in one sentence — the 2026-08-02 scope correction about sub-Pipeline nesting, which is right but reads as self-contradiction. |

The board rows are accurate and this plan keeps them: **CP-02** (graph editor + Recipe view, insert-between,
insert-into-branch, undo/redo, snapshots, save-as-template), **CP-03** (validate → save → arm → test-run →
run → replay) and **CP-04** (bundle export/import with dependency closure) are ✅ in all three editions.
The qualifier CP-04 carried until 2026-09-25 — the metadata bundle's authored-pipeline kind targeted the
**retired** graph-shaped store — is closed: the new `pipeline` kind carries the canonical file and its
sidecars through the §21 import core, and `authored-pipeline` is export-only
([metadata-bundle](../processor/spec-metadata-bundle.md)).

### 2.3 One number per fact

Measured from code and the committed contract on 2026-09-09:

| Fact | Measured | What the docs said |
|---|---|---|
| Built-in node types | **49**<!--count:node-types--> | 20 in the glossary (and its list predates the per-format parsers), 28 in the active plan, 20 in the editor page |
| Recipe catalogue | ⛔ **retired 2026-09-24** — `GET /pipelines/step-types` + its contract went with their last reader, the Recipe view; `RecipeCompiler`'s verb switch is the vocabulary | the active plan said 9 entries and included `map`, a verb deleted 2026-09-05 |
| Step processors | **122**<!--count:processors--> — **54**<!--count:processors-delivered--> delivered, **17**<!--count:processors-partial--> partial, 51 planned | ✅ all agree since 2026-09-09; derived by `tools/check-doc-counts.mjs` |
| Transform function catalogue | **23 in 7 categories** | the editor page said 24; the mapping page said both "~20" and "24" |
| Live "too many of a kind" refusals | **2** at the time — one for a second parser, one for a conflicting map config (five since 2026-09-23, with `MULTI_ACQUISITION` / `MULTI_GAP` / `MULTI_MARKER`) | two documents cited refusals for a second join and a second sink; **neither constant exists** |

The deleted constants survive only inside a Javadoc paragraph explaining their own removal — and that
paragraph is the best statement of the design in the area:

> A count is not what should constrain a pipeline — whether a step accepts its neighbours is.

A guard against this class already exists and is the pattern to copy: one contract test **re-parses the
executor's own source text** to catch a constant going stale relative to the code it claims to describe.

### 2.4 The one key whose documented status was contradictory

🔴 **`fields[]` was documented both as inert and as executable.** The config-key reference said it is an
authoring artifact **the engine never reads** and "must not be added to the contract"; the editor page says
it is a **live executable contract**. **The code is unambiguous: it is executable** — `RowShaper.MAP_NODE_CONFIG_KEYS`
includes it and the projection test returns true when a node carries record fields, so `fields[]` *is* the
mapping that runs. The comment beside the allow-list says why this matters: *"a key that becomes executable
without joining this allow-list is silently dropped on save"*.

That wrong reasoning is what produced the client-mirror drift: for four days the client REFUSED a key the
server accepts (`UNSUPPORTED_MAP_KEY`, advertising `[columns, rules]`) and dropped it on the round trip. Fixed
and pinned 2026-09-09 (`MapNodeKeyContractTest`); ⛔ since 2026-09-24 the TS mirror no longer exists — the
whole TS lift/lower had been dead since the mock went (`f1553136`) and was deleted
(`STEP-TYPES-DEAD-CLIENT-MIRRORS-1`). Correcting the config-key reference is still owed (§5.2 item 2).

## 3. Where the as-built mechanism lives

The specification sections of the two former capability files were trimmed on 2026-11-18. Each shipped fact
now lives once, in its owner:

| Mechanism | Owner |
|---|---|
| Consignment planning, `collector.consignment`, the run budget (`sources.max × processing.threads × duckdb_threads`) | [engine — Consignments](../engine/spec-consignments.md) · [engine — ingest](../engine/spec-ingest.md) |
| `ConsignmentIngestor`, the two strategies, union vs generation mode (`large_file_bytes`, `flush_records`), staged write and reveal, the crash-safe commit order, unkeyed → flat store (E1) | [engine — ingest](../engine/spec-ingest.md) |
| `route:` on the poll path and `-Dingest.lane` | [engine — ingest](../engine/spec-ingest.md) · [engine — execution](../engine/spec-pipeline-execution.md) |
| The `parsing:` block, the frontends and their engines, `ParserPlugin` / `Parsers`, ASN.1, the XML bridge, every parsing key and its refusals | [engine — parsing](../engine/spec-parsing.md) |
| The wrap-SPI (`StreamingFileIngester`, `RecordSink`, `DuckDbRecordSink`, the Appender), segments, the two `partitions[]` readers | [engine — ingest](../engine/spec-ingest.md) |
| Unpack — placement, caps, verdict, ledger, identity | [acquire spec Part 3](../acquire/spec.md#part-3--the-unpack-stage) |
| Casting, cast-failure measurement (`-1` = not measured), quarantine reasons, reject rows, the ledgers, in-memory live progress | [engine architecture](../engine/architecture.md) · [engine — ingest](../engine/spec-ingest.md) |
| The source time zone (`SourceZones`), the Appender, threads | [engine — DuckDB](../engine/spec-duckdb.md) · [engine — parsing](../engine/spec-parsing.md) |
| Per-field date format and epoch timestamps; content refusal (`processing.refusal`); the `postmed_xdr` reference build; the daily-feed template, export contract and delivery checks; the Parse pane and field tiers | [user guide](user-guide.md) |
| The authoring model (derived graph, three flags, five registration points, the Pipeline Document and its workbook), the transform surface, the editor, identity and lifecycle | [user guide](user-guide.md) · [Step catalog](spec-step-catalog.md) |
| The round trip (`toMap` / `lower`), lift and `stageTwo`, the validation ladder, the node-type model | [engine — Pipeline model](../engine/spec-pipeline-model.md) |
| Identity, rename, save-as-template, config history | [Pipeline identity](../processor/spec-pipeline-identity.md) |

## 4. Decisions

Dated, one line each, with the reason. Only decisions that still bind are listed; where one reversed an
earlier one, both appear. Unpack decisions are acquisition's ([acquire plan](../acquire/plan.md#decisions)).

### 4.1 The lane and the commit

| Date | Decision | Why |
|---|---|---|
| 2026-06-28 | **Three frontends, one DuckDB backend** | every format converges on mapping + transform + partition + lineage |
| 3.10.0 → 3.11.0 | `StreamingFileIngester` + `RecordSink` are the plugin SPI; whole-file `FileIngester` **removed** | one contract; the framework picks union vs generation by size |
| — | **Markers and fingerprints go last**; a FAILED Consignment leaves no record | retry is implicit and safe |
| 2026-08-11 | `processing.dedup` **leaves Stage-1**; refused at `prepare()`; the Step runs at rest | dedup over a landed store is decidable; mid-stream is not |
| 2026-08-12 (operator) | Consignment `order` defaults **`mtime`**; `name` opt-in; else refused | arrival order is what a feed means; stamps lie only sometimes |
| 2026-08-19 (E1) | An unkeyed segment writes a **flat store**; the `year=1900` sentinel retired | a sentinel partition is a lie about the data |
| 2026-08-19 (E2) | The two `partitions[]` readers **stay separate** — ingest fail-closed, sink degrades | a wrong layout at ingest corrupts the store; a good sink write must not fail over an advisory |
| 2026-08-19 (E3) | The Appender path is **the** wrap; staged CSV is for out-of-process producers only | built, faster, no second disk write |
| 2026-08-26 → 2026-09-06 | `route:` executes on the poll path; `mode: clone` **arms** (reversing the first refusal) | the graph lane admits non-route pipelines; committed branches must be visible |
| 2026-09-01 | Orphan `output_store` audit **default-on** (`-Djobs.orphan.audit=false` to kill) | a landed store nobody reads is a silent loss |
| 2026-09-02 (CONSIGNMENT-HOME-1) | `processing.batch.*` → **`collector.consignment.*`**, dual-read, healed on save | the planner runs in the poll cycle before any sink exists |
| 2026-09-06 | A plugin pipeline can **plan** a Consignment (audit label `schema`) | a `null` label had thrown for every plugin pipeline |
| 2026-09-07 | **Intrinsic and inherited status never collapse** into one enum | a `COMMITTED` file in a `FAILED` Consignment is exactly what reprocessing asks about |
| 2026-09-30 | **Content refusal is opt-in per Pipeline** (`processing.refusal: restricted_quarantine`), moving a refused file whole to a restricted store; it replaced the purge seam (`PURGED_REFUSED`) | data content could trigger the purge seam, it applied to every Pipeline, and it deleted irreversibly |
| 2026-10-04 (operator) | **Restricted-file retention is mandatory**: `processing.refusal_retention_days` defaults to 7, must be 1..30 | PCI: no stored card number |
| 2026-10-06 (operator) | A daily feed's backfill is the same door as a daily load, then ONE full index build; a Job run that finds a build in flight WAITS and re-plans | no per-day index builds to merge; the daily trigger need not be paused |
| 2026-10-14 (owner call) | A corrected same-name day lands only under `collector.duplicate {mode: checksum, on_change: reprocess}`; the default path markers skip it | no new key; the fingerprint ledger already carries the semantics |

### 4.2 Parsing and plugins

| Date | Decision | Why |
|---|---|---|
| 2026-07-07 | One **`parsing:`** block; `csv_settings` / `processing.ingester` are aliases and `parsing:` wins | one home per concept |
| 2026-07-31 | **ASN.1 served**: grammar optional for preview, required for ingest; hex-first; INTEGER 42 is never `"*"` | a structural dump is honest; a faked decode is not |
| 2026-07-31 | `frontend: asn1` **synthesises** `parsing.plugin` and refuses a co-present one | one config key must win |
| 2026-07-31 | JSON `records_path` dotted; `format: array` + `ignore_errors` **refused at load**; `[*]` refused | each would load looking honoured and fail every Consignment, or return zero rows silently |
| 2026-08-01 | The `asn-parser` reactor split resolved; corpus tests **opt-in and data-gated** — *skipped is the expected state* | the corpus is customer data (DATA-GOV-1) |
| 2026-08-16 (operator) | Binary fixed-width **keeps `GrammarEditorDialog`** | its geometry is `ingester_config`; the slice table would govern nothing |
| 2026-08-19 (X1–X3) | XLSX shipped on the `excel` extension with a **three-layer fail-closed load**; `read_xlsx columns` **refused, not faked** | the parameter does not exist on 1.5.2.1 |
| 2026-08-28 | `text_regex.record_split` verified live — **LDIF block records are live** | blank-line splitting is the whole LDIF need |
| 2026-08-30 | `XmlRecordIngester`: **preview and ingest share one walker**; a selector names a leaf that occurs once; XML keys under `ingester_config` | a second walker would resolve authored selectors to NULL silently |
| — | **A built-in parser id cannot be overridden**; a duplicate id fails startup | the built-ins' preview *is* the ingest engine |
| 2026-09-04/06 | `strict_mode` is a **three-way select** (engine default on / on / off), not `default: true` | a placeholder writes nothing; a default must equal the engine's |
| 2026-09-25 | A field's own `raw.fields[].format` **replaces**, never joins, the Pipeline's date formats for that field | a shared list trying other patterns first is how `%d/%m/%Y` silently reads as `%m/%d/%Y` |
| 2026-11-15 (`MAPPING-EPOCH-TIMESTAMP-1`) | `epoch` / `epoch_ms` are reserved `format` values on a `TIMESTAMP` field | an epoch count lands as UTC with no `sql` step |
| 2026-11-17 (DT-12) | The ASN.1 frontend (`inspecto-telecom-asn1`) is **Advanced only** | edition placement of the telecom decoder |

### 4.3 Authoring

| Date | Decision | Who / why |
|---|---|---|
| 2026-08-02 | **Sub-Pipeline nesting ruled out by design.** In-motion and at-rest are a binding line, so an at-rest operator cannot be an in-motion node; they compose as producer and consumer over a shared store | product |
| 2026-08-04 (user) | Store "both": inline by default, extractable to a `grammar` component; *Grammar* canonical, "parser config" banned | one word, one store |
| 2026-08-09 | **The closed vocabulary is superseded (D0-B).** The real guarantee is compiler *totality*, which survives an open Step-kind registry where every registered kind declares that it lowers or executes; a kind with neither is refused fail-closed at arming | engineering |
| 2026-08-11 | **A single collector, permanently** — the list design was refuted by grounding | engineering |
| 2026-08-11 | **Multiplicity resolved by an ordered chain**, and the "too many of a kind" refusals deleted with it — a count is not what should constrain a pipeline | engineering |
| 2026-08-13 | **Pipeline-level settings are their own route**, not the graph save, because the editable model deliberately never models non-node keys | engineering |
| 2026-08-14 | **An authored reference has a home for two node kinds only**; on any other kind it is refused by name. The bug this fixed: the picker was keyed on category rather than type, so lowering dropped every other reference **in silence** while the save returned success | engineering |
| 2026-08-15 | **A load-time synthesis makes a binding derived**, and derived bindings are dropped silently — refusing them would make every binary-decoder pipeline unsaveable. The general rule: whenever a load-time synthesis invents something the lift can present, check what the save path will then think the author wrote | engineering |
| 2026-08-15 (operator) | ⛔ **A Grammar Template is a copy source, never a binding** — reverses the `use: grammar/<id>` contract of 2026-08-04; the bound form stays readable but is never authored, and opening such a Step migrates it to an inline copy on save | every other *Template* in the glossary is a copy source; a live binding made an edit reach Steps nobody opened |
| 2026-08-16 (operator) | ⛔ **No single drawer over schema, mapping and table** — it would span three Step types and break the one-node-in, one-node-out contract every drawer holds | operator |
| 2026-08-17 | **A new pipeline carries a stable id from birth**, stamped by one scaffold behind both create surfaces so neither can drift; the id is immutable once set | engineering |
| 2026-08-17 | ⛔ **The id is not opaque or minted** — it names the file, the commit log, the ledger and the catalogue entry | engineering |
| 2026-08-17 | ⛔ **The parser's fallback derivation is deliberately not narrowed** — narrowing silently re-keys every id-less pipeline on disk. Migrating the legacy path is a data migration, not an edit | engineering |
| 2026-08-17 (**A6**) | **Export lives in one always-visible home, un-gated** — a business-persona operator handing a config to support is the point | operator |
| 2026-08-19 | **Grammar CSV** round-trip replaces "Save as template…"; `GrammarTemplateDialog` deleted | a file is the portable form |
| 2026-08-29 | ⛔ **`authorable` and `lowerable` stay two flags** — collapsing them forces a choice between offering a node nothing should create and refusing to save a graph that legitimately still carries one | engineering |
| 2026-08-29 | **Providers layer last so an edition may override a built-in** without forking core | engineering |
| 2026-08-31 (**D2**) | **A Step passes a token, not data** — adopt the vocabulary now, the runtime model later. *"This is not a new direction. It is what the engine already does, and what the type system contradicts."* Overturned only by evidence some Step needs rows in flight — none found | operator |
| 2026-08-31 (**D1**) | **Finish the approved amendment, then extend — ⛔ do NOT replace it.** The meta-decision that legitimised every amendment phase. Overturned only by *a requirement the `trigger` / `steps` / `guarantees` shape provably cannot express* | operator |
| 2026-08-31 | **The `data` edge is a fiction** — it expresses a reference, not a flow | engineering |
| 2026-08-31 (**D3**) | **A new pipeline asks for the parser format** — a parser is always format-specific, and defaulting guesses for the author | engineering |
| 2026-08-31 (**D6**) | **Fan-in stays canvas-only for now** — a token model makes fan-in expressible, and that is not a reason to overturn a standing decision speculatively | engineering |
| 2026-08-31 (**D7**) | **One surface serves both the chain and the post-sync lane** — building two leaves the second one second-class. The visual design is open; the constraint is what is recorded | engineering |
| 2026-08-31 (**D9**) | **"Related to a pipeline"** means what it owns, what names it, and what an import needs — ⛔ connections excluded, because a bundle that moved them would move a deployment's identity between spaces | operator |
| 2026-08-31 (**D10**) | **Contract test first, single declaration later** — declaring keys one at a time never stops the drift growing. *"It fails today; that is the point."* | engineering |
| 2026-09-01 | **Layer split**: the editor page owns the editor, the graph bundle owns the backend model, and the active plan stays a plan | operator |
| 2026-09-01 | **`description` is declared, parsed, projected and display-only**; blank clears the key rather than persisting an empty string | engineering |
| 2026-09-02 | **The palette renders the served processor taxonomy** — the product board rendered in-product, with every processor visible and non-addable ones inactive with a reason. The engine's executable vocabulary stays separate | operator |
| 2026-09-02 | **Mid-branch sub-chains ship**; a join, a windowed dedup and a nested route refuse by name at save | engineering |
| 2026-09-03 → 2026-09-04 | 🔴 **The simple five-verb grid and its "a hand edit locks the Step" rule were superseded and deleted the day the operator reviewed them live** | operator |
| 2026-09-04 | **Transform goes SQL-first** — one author projection over the typed upstream relation, never a split | operator |
| 2026-09-04 (same day, second flip) | 🔴 **The grid returns, with a typed function catalogue replacing the verb enum** — *"mapping with functions and parameters is not realistic"*. ⚠ **The SQL-only middle state is not current** | operator |
| 2026-09-04 (operator, D1–D4) | The **sectioned Parse pane**; plain-language labels; mockup rows with no engine key **not built**; grounded defaults vs help-only | a UI knob must name an engine key |
| 2026-09-04 | **The Record Transformer** — one Step that sanitizes, casts, renames and computes, one row per output field over a typed catalogue, generating the projection it saves. Folded from three catalog labels plus type coercion; the schema *contract* could not fold, because the declared column and target type are the cast-failure audit's denominator | engineering |
| 2026-09-04, verified 2026-09-06 (**D8**) | **Parse does not drop columns — Transform owns exclusion.** Excluding in Parse edits the schema, which the compatibility gate can refuse to undo; excluding in Transform is a clause, reversible for free | engineering |
| 2026-09-04 (**D9**, **D10**) | Both field tables are built for a wide feed; Transform opens on *Changed*, not *All* | real feeds carry hundreds of fields |
| 2026-09-05 | 🔴 **The map type is deleted; map is a slot, not a step** — the id grammar is the discriminator, never the type. A stored legacy rule block still loads, permanently | engineering |
| 2026-09-05 | **Fields and SQL are peer views of one Step**, and the earlier "never parse SQL back into rows" rule (D4) is superseded by a bounded reconciler | engineering |
| 2026-09-05 | **`fields` must be lowerable because it is executable** — a key that becomes executable without joining the allow-list is silently dropped on save | engineering |
| 2026-09-06 (PARSE-HOME-1) | **A Parse Step is three things and the drawer is its one home** — a grammar, the schemas it emits, and its other properties, including a dangling binding | three surfaces for one Step confused every author |
| 2026-09-06 | **Paths derive from the slug id too** — one identity for id, file and directories | engineering |
| 2026-09-06 | **Enrichment stays first-class; the retirement narrowed to nothing** after grounding found the one committed fixture does not migrate. ⛔ Not a pending rename — do not re-file it as one | operator |
| 2026-09-07 | **The workbench field list is capped at fifty rendered rows** with the filter always visible — 🔴 the cap applies to what is *rendered*, never to what is *searched* | operator |
| 2026-09-07 | ⛔ **The merge and join input picker is refused** — there are no input keys to write; a picker would have written a key nothing reads. Edges stay authored on the canvas | engineering |
| 2026-09-07 | ⛔ **Reference detection may not use a SQL parser** — that would be a second implementation of the dialect the engine owns. 🔴 And it cannot use a word boundary, because an underscore is a word character | engineering |
| 2026-09-22, 🔴 **REVERSED 2026-09-23** | ⛔ **A created Pipeline stays FLAT at the space config root; `config/<id>/` is REFUSED (D3).** It was signed on the premise that *every shipped Pipeline lives in a per-Pipeline directory* — counted on disk, that is false (`config/orders/` holds THREE Pipelines; directory names are DOMAIN words: `config/postmed/` → `postmed_xdr`, `config/mule/` → `mule_transfers`). `subdir:` gives an author a domain home. 🔴 **The lesson is the premise: "every shipped X does Y" is a claim to COUNT before a decision rests on it.** | operator (reversed on the operator's behalf) |
| 2026-09-22 (**D9**) | **`GET …/graph` is NOT renamed; it says what it is instead.** It gains `links.roundTrip: …/graph/raw` plus `readOnlyProjection: true` (`WB-18`). 🔴 PUTting a `/graph` body back is refused 422 on 26 of 26 shipped Pipelines; a rename would have broken every client for the same gain. Row `GRAPH-READ-SHAPE-NOT-WRITE-SHAPE-1` | operator |
| 2026-09-22 (**D6**) | Node test state may **not** read operate-lane provenance into `tested`; a distinct *ran* badge is the remedy (`WB-11`) | a signal wrong for every Pipeline in production is one readers learn to ignore |
| 2026-09-22 (**D8**) | The orphan-drop decline is **amended**: with a node selected, an added Step is wired after it (`WB-14`) | the Recipe view's insert-between it rested on was deleted |
| 2026-09-23 (operator, `PIPELINE-LOAD-FAILURE-INVISIBLE-1`) | **A Pipeline that does not load is a BROKEN ENTRY in the Open dialog, never a missing one** | a broken file must be openable to be repaired |
| 2026-09-24 | Retention of the server-side config history is **a per-Space setting**, default 50 | the same cap as undo/redo, changeable by the operator |
| 2026-10-11 (operator, option B) | A Step column's sensitive classification is merged into masking **additively only**; it never clears `UNKNOWN_LINEAGE` | an omission must never unmask a column |

## 5. Not built and open

⛔ **Pointers, never copies.** Each row names its board id; the board is the authority for status and
priority. A row with no id is flagged `UNTRACKED`. The 2026-09-09 (Sprint 2) filing matched findings to
cross-cutting board rows by family (`CONSUMER-PAIRS-1`, `SPEC-STALEREF-1`, `SPEC-COUNTS-1`, `SPEC-NOPROOF-1`,
`SPEC-GLOSSARY-1`, `SPEC-GREENCELL-1`, `SPEC-PLANSTALE-1`, `SPEC-ORPHANPAGE-1`); ⚠ none of these ids is an open
board row today (re-checked 2026-11-15, `DOCS-AGENT-WRITTEN-REVIEW-1`) — history in
`docs/archived-documents/backlog-snapshot-2026-09-24.md` and
[`post-consolidation-sprints.md`](../../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.
Area-specific design findings stay here deliberately: the board holds what **crosses** areas, a plan holds
what belongs to **one**.

### 5.1 Ingestion — tracked

| Item | Board id | What remains |
|---|---|---|
| A drop-in `plugins/` jar directory (the ASN.1 grammar as a stored `.asn` file SHIPPED 2026-09-23 — [engine — parsing](../engine/spec-parsing.md)) | `BACKLOG.md` §3 *Parsing (Stage-1)* | a trust decision |
| **D13 parser field tiers** — the onboarding-observation session; `tier: 'required'` ships `required: false`. The pre-agreed analysis rule and the deliverable that closes it are in [grammar configuration](../config/user-guide-grammar-config.md); the kit is [plan-parser-field-tiers-interview.md](plan-parser-field-tiers-interview.md) | `BACKLOG.md` §2 | externally gated |
| **Row 15** — delete the legacy flat read path (`-Dingest.lane` is the precondition) | `BACKLOG.md` §2 | release-gated |
| D-8 **XLSX export** (zero groundwork, no spreadsheet library) | `BACKLOG.md` §3 | |
| Column metadata editing on the Transform pane (Parse D2) | `BACKLOG.md` §3 (e) | built for chain Steps 2026-10-11; option (C) — the Step's declaration REPLACING the fail-closed rule — not built (`AUTHORING-REDESIGN-1` (e)) |
| `SP-PRS-09…17` planned parsers (key-value, YAML, ASN.1 PER/XER, EBCDIC, protobuf, Avro, PCAP, Grok, syslog) | `EDITIONS.md` board | no BACKLOG row |
| Delimited robustness knobs (a)(c) | archived board snapshot 2026-09-06 only | no live row |
| Re-delivery semantics of a daily feed (replace vs append of the partition, late-data effect on the Link Analysis Index) | `LA-DAILY-INGEST-1` T4 | until T4 lands, treat a re-delivered day as unverified; a date DROPPED by a correction still leaves the old file for that date (roadmap T4 b2) |
| Content refusal: the Collector's discovery logs and its acquisition ledger see the inbox name before the check runs | — | not covered by the refusal |
| Unpack items (xz/zstd, `.Z` round-trip, multi-part archives, `data_extensions[0]:`, stale services header) | [acquire plan](../acquire/plan.md#not-built) | |

### 5.2 Ingestion — `UNTRACKED`

| Item | Evidence | Why it matters |
|---|---|---|
| **`parquet` is a built-in frontend on no board** | `BuiltinParsers.IDS`; `FRONTENDS` | shipped, untested-as-a-row, invisible to `EDITIONS.md` and `step-catalog.md` |
| **The frontend count is stated six ways** (3 / 5 / 6 / 7 / 9 / 10) across five pages and the user guide | §2.1 | FOUR sets share one noun: **10**<!--count:parsing-frontend-tokens--> `parsing.frontend` tokens (eight formats once the two aliases fold), **6**<!--count:builtin-parsers--> DuckDB-native built-ins, **7**<!--count:parser-node-types--> `parser.*` node types, and three byte→row *mechanisms* (a prose taxonomy, not a count in code). The three derivable sets are marked and derived by `tools/check-doc-counts.mjs` (2026-09-09); the mechanism sense is written as *mechanisms*, never *frontends* |
| **`Segment` has no glossary entry** | `GLOSSARY.md` | the load-bearing plugin-ingest word is undefined in the binding vocabulary |
| **The "Batch" names survive in `duckdb.md`, `FEATURE_INVENTORY.md`** | `ConsignmentIngestStrategy`, `CsvIngestStrategy` | corrected in the seams page; the rest are a sweep |
| **No page owns Expectations as a subject** (`com.gamma.expectation`); the accounts are §2.1 here, `REQUIREMENTS.md`'s cell and the decision-rules condition-tree paragraph | *(gap)* | `inspecto/src/main/java/com/gamma/expectation` has no concept page |
| **The flat lane's deletion (Row 15) has no test because it is not done** | engine | |

### 5.3 Authoring — tracked and actionable

* **`AUTHORING-REDESIGN-1`** — the open letters. Macros as a function registry, demand-gated; column
  metadata option (C) (above); and a per-row "resolves to" line, for which no host resolves a sample against an
  attribute spec. *(The structured table over the SQL for filter editing shipped 2026-09-23 as (c); column
  metadata on chain Steps shipped 2026-10-11 as (e); "Sample resolves to" shipped on the Parse pane as (i).)*
* **The processor catalogue's partial rows** — each is a product decision, picked one at a time by name.
* **A host pane for the report builder** — no viable existing pane; a new surface, not an adoption.
* **AI drafting has no applicable component kind** — three kinds have no backend spec, and no low-risk
  slice survives; design first.
* **Two author-facing messages name things that do not exist** — one warns that only three Step kinds may
  run inside a branch when the code's own set also contains the transform-SQL kind.
* **Migrate Duplicate and the row export onto the bundle routes** — both still ride the client stream-bundle.
* **A Parse Apply over a stale `partitions[]` seed can clobber a Sink edit made meanwhile** (BACKLOG).
* **Single-slot inversion** — `processing.join`, `recordDedup`, `routeNode`, `summarizeNode` are last-one-wins,
  silently; inverting it is an operator call (BACKLOG §4).
* **An inline-config Step of other kinds cannot be tested by any existing backend surface** — the component
  `/test` routes resolve through `ComponentStore` and 404 on absence; do not "finish" this without a deliberate
  decision.
* **Comparing two runs side by side** — the run overlay is pick, not compare; a per-edge delta view is not built.

### 5.4 Authoring — `UNTRACKED`, ranked

1. 🔴 **An unknown node type is only a warning** (§2.2). The validator warns, the codec stores config as
   an unchecked map, and the compiler silently drops the node — so an invented vocabulary survives
   unnoticed. It should either be an error or be stated in the requirement.
2. 🔴 **`fields[]` is documented as inert in the config-key reference and as executable in the editor
   page** (§2.4). Code says executable; correcting the reference is the actual fix for the drift row.
3. 🔴 **Counts stated several ways** (§2.3) — node types, recipe entries, processors, transform functions.
4. 🔴 **Two current documents cited refusals that do not exist** — a second join and a second sink.
5. 🔴 **Eight current documents still name the deleted map type or the superseded simple grid as
   current**, including one index line stale in three ways at once. (Not re-checked after the 2026-11-18
   consolidation, which merged several of them.)
6. ⚠ **Whether a degraded palette should also fire the alert component** — the three host error arms are
   tested (2026-09-09, `pipeline-editor.component.spec.ts`; ⚠ `null`-vs-`[]` is the load-bearing distinction),
   but by this project's convention a degraded palette should alert, and nothing fires it today. A product
   decision, deliberately not encoded in a test.
7. ⚠ **`A6` was an orphan decision** — it appeared once in the current document set; §4.3 is now its second home.
8. ⚠ **Every executor line-number citation in the authoring documents is wrong, and so is its package
   path** — the class moved into an execution sub-package. **Cite the symbol, not the line.**
9. ⚠ **A deleted component is cited as the current mapping UI** in a concept page.
10. ⚠ **An identity page says "nothing open" four lines above citing a board row that does not exist** — the
    citation that caused a phantom row to be filed and then deleted unbuilt.
10a. ⚠ **A plan states a sibling document's size as 144 KB; it is 18 KB** — the figure predates a split the
    same list describes.
11. ⚠ **Two notes point at the wrong backlog section** for the authoring row.
12. ⚠ **One editor pane is undocumented anywhere in the frontend tier** — a guarantees checklist panel whose
    own docblock cites a plan that is not among its page's cross-links.
13. ⚠ **The requirement's own cell reads `SHIPPED` and "is not shipped" in one sentence** (§2.2).
14. ⚠ **`transform.merge` is executable code with no authoring route.** Correctly refused rather than
    half-exposed, but nothing on any board says the executor carries a node nobody can reach.

### 5.5 Not this plan's

`PIP-2` through `PIP-7` — the medallion lanes, incremental triggers, the scheduler and Jobs, async run
triggers, job templates and the maintenance library — belong to the [engine plan](../engine/plan.md), as do
the **second execution home of an authored chain** and the requirement row whose status hierarchy still uses
a word the glossary retired in 2026-08.

## 6. Refused & superseded

**This section exists because a refused idea with no recorded refusal gets re-proposed.** Each row states
what was refused and the reason — the reason is the load-bearing half. Unpack refusals (extending the ingester
SPI to emit files, partial-archive failure, nested archives, `archive!entry` in a data column, a per-entry
Signal, the batch semaphore for Unpack) are in the [acquire plan](../acquire/plan.md#refused--superseded).

### 6.1 Ingestion

| Refused | Why |
|---|---|
| **One partition grammar (E2); staged CSV from inside a plugin** (2026-08-19) | Folding either `partitions[]` reader into the other imports a hard failure into the degrade lane or softens the fail-closed one. The Appender is built and faster; never demote to staging CSV in-process |
| **Config that would load looking honoured and then fail** — refused at load | `format: array` + `ignore_errors`; `records_path [*]`; `compression: zip | tar | Z` in config (only `auto | gzip | zstd | none`); a plugin with no `segments`; `TIMESTAMPTZ` without a zone; `%z` formats; a `root_type` without a grammar. Each is a caller error, not a silent fallback |
| **Knobs nothing has needed** | ASN.1 trailer length and `lengthOffset` / `lengthSize` / endianness (no corpus file uses them); `decimal_separator` at the dialect (ingest reads VARCHAR); newline style, `sample_size`, `all_varchar`, `auto_type_candidates`, `names`, `union_by_name`, `buffer_size`, file globs in the grammar (auto-detected, all-VARCHAR by design, or the Collector's); `read_xlsx columns` (does not exist — refused, not faked); a `timezone_column` editor (~418 zone names per row) |
| **Per-step Signals and periodic progress persistence** (consignment chain) | A Signal is a durable write on the claim-holding thread and triggers `on_signal` jobs while the claim is held (the re-entrancy class `PROJECT_NOTES.md` §6 forbids); periodic persistence is stale by construction and leaves crash-orphaned rows. Live gauges stay in memory; the ledgers answer the post-crash question |
| **Overriding a built-in parser id** | The built-ins' preview is the ingest engine, so an override lets preview diverge from production |
| **A breaking engine slice removing `use: grammar/` (D3)** | It costs nothing, is pinned, and a hand-authored file may still carry it; the resolution was to stop *authoring* the binding, not to build a write route that removes it |
| **A selector naming a container or a repeated sibling** | It yields NULL — "a lie dressed as a decoded value" |
| **A partial decode on an ASN.1 / XML parse error** | the whole file fails (`QUARANTINED_UNREADABLE`), never a prefix |
| **The purge seam for content refusal (`PURGED_REFUSED`)** (2026-09-30) | data content could trigger it, it applied to every Pipeline, and it deleted irreversibly; replaced by `restricted_quarantine` |
| **A rename-and-redeliver correction of a daily file** | a correction under a NEW name doubles every row of the date — the feed owner's error, not fixed in the platform, detected by the delivery check |

**Superseded designs — what replaced them:**

| Superseded | By | Where recorded |
|---|---|---|
| Whole-file `FileIngester` (since 1.3.0) | `StreamingFileIngester` (3.11.0 — a deliberate within-major exception) | `api-stability.md` |
| `processing.csv_settings` / `processing.ingester`; the standalone `*.grammar.toon` file | the unified `parsing:` block (2026-07-07); Grammars inline on the Step; the `grammar` kind as a template | [engine — parsing](../engine/spec-parsing.md) |
| `processing.batch.*` | `collector.consignment.*` (2026-09-02) | `GLOSSARY.md` §13 |
| `Batch` / `BatchProcessor` / `BatchIngestStrategy` / `CsvBatchStrategy` | `Consignment` / `ConsignmentIngestor` / `ConsignmentIngestStrategy` / `CsvIngestStrategy` (2026-08-31) | `GLOSSARY.md` §13 |
| Record dedup in Stage-1 | the at-rest `dedup` Step (2026-08-11) | [engine architecture](../engine/architecture.md) |
| The `year=1900` sentinel bucket | a flat unpartitioned store (E1) | [engine — ingest](../engine/spec-ingest.md) |
| `use: grammar/<id>` as a live binding (2026-08-04) | Grammar Templates as copies (2026-08-15); the form read-only | `GLOSSARY.md` §5 |
| "P3d retires `GrammarEditorDialog` entirely" | binary fixed-width and the unmappable generic `parser` keep it (operator, 2026-08-16); the dangling case moved to the drawer 2026-09-06 | [grammar configuration](../config/user-guide-grammar-config.md) |
| The 4-tab delimited surface (U1–U5); `<inspecto-property-rows>`; `GrammarTemplateDialog`; the 9-type hardcoded `parser-types.ts`; the mock `/components/grammar/preview`; the Onboarding Parsing stage | the sectioned pane, one schema-form per section, Grammar CSV, the served `ParserPlugin` contract, the editor as the guided surface | [grammar configuration](../config/user-guide-grammar-config.md), [engine — parsing](../engine/spec-parsing.md) |
| XML preview-only; `xml.` grammar root | `XmlRecordIngester`; keys under `ingester_config` (2026-08-30) | [engine — parsing](../engine/spec-parsing.md) |
| The node-count engagement predicate for the graph lane | branch count (`dataFedSinkCount`) — "refuted by its own falsification test" | [engine — ingest](../engine/spec-ingest.md) |
| "An absolute refusal of `steps:` was the original design and was WRONG" | `steps[]` runs at rest via a `type: pipeline` job | [engine architecture](../engine/architecture.md) |
| `DATA_RULES` / `TransformCompiler` as the mapping lane | the Record Transformer (2026-09-05) | `configuration.md`; [engine — Pipeline model](../engine/spec-pipeline-model.md) |
| `generation_threshold_bytes`; "nested archives"; LDIF `[PROPOSED]`; "XML today" preview-only; "nine format types" and `Run ⊇ Batch ⊇ File` | corrected with the ING capability spec, 2026-09-08 | those files |
| The 4.1 account of the parsing grammar (`*.grammar.toon`, `csv_settings`) | superseded 2026-07-07; flagged, a rewrite owed | [engine — parsing](../engine/spec-parsing.md) |

### 6.2 Authoring

| Item | Verdict | Why |
|---|---|---|
| **Sub-Pipeline / embedded-Job nesting** | ⛔ **REFUSED by design** 2026-08-02 | In-motion and at-rest are a binding line; they compose as producer and consumer over a shared store |
| **The closed type vocabulary** | **SUPERSEDED** 2026-08-09 (D0-B) | Totality, not closure, was the real guarantee — and it survives an open registry — which is now live, so the set is open in fact too |
| **The simple five-verb grid, and its editing lock** | **SUPERSEDED + DELETED** 2026-09-04, the day it was reviewed live | A raw SQL box is not an authoring surface for a non-technical user; a verb enum is not a mapping model either. Replaced by a typed function catalogue |
| **The SQL-only middle state** | ⚠ **NOT CURRENT** — the surface flipped twice on 2026-09-04 | Recorded because a reader landing on the middle state would build the wrong thing |
| **The map type** | ⛔ **DELETED** 2026-09-05 | Replaced by the Record Transformer; map is a slot identified by id grammar, not a type |
| **Three catalog labels plus type coercion** | **FOLDED** into one row 2026-09-04 | Three labels over one grid. The schema *contract* could not fold — the declared column and target type are the cast-failure audit's denominator |
| **Deleting the legacy rule read path** | ⛔ **REFUSED** 2026-09-05 | It stays readable **permanently**; migration is by tool, never by breaking stored schemas |
| **An authored reference on any kind but two** | ⛔ **REFUSED** by name | Every other kind carries its settings inline. The rule is one-way and deliberate: a picker requires a home |
| **A map-list attribute spec for branch config** | ⛔ **REFUTED** | A specced key is form-owned and would destroy the derived pair |
| **The merge and join input picker** | ⛔ **REFUSED on grounding** 2026-09-07 | There are no input keys to write; edges stay authored on the canvas |
| **A SQL parser in the client for reference detection** | ⛔ **REFUSED** | It would be a second implementation of the dialect the engine owns |
| **Folding the transform family into one SQL node** | ⛔ **ASSESSED AND REFUSED** 2026-08-29 | Filter, validate, dedup and route each *split* rather than project; and folding split, merge or summarize into enrichment would move operators after commit and across pipelines — a change of execution moment, not a rename |
| **Deleting the dedup-marker type** | ⛔ **REFUTED** 2026-08-29 | It is already in its correct terminal state: known, lowerable, unauthorable, unspecced. Marking it deprecated would be actively harmful. Method note: read a type's *consumers*, not the comment nearest it |
| **Graph-shaped authoring writes** | **RETIRED** | Grandfathered readable, runnable and deletable; never newly written |
| **A single drawer over schema, mapping and table** | ⛔ **REFUSED** 2026-08-16 | It would span three Step types and break the one-in, one-out contract every drawer holds |
| **A plural collector** | ⛔ **REFUSED PERMANENTLY** 2026-08-11 | Refuted by grounding: every stateful acquisition subsystem is keyed on one durable id |
| **Fan-in beyond the canvas** | ⛔ **DEFERRED** (D6) | Expressibility is not a reason to overturn a standing decision speculatively |
| **A validation-gate plan for schema authoring** | **SUPERSEDED the day it was drafted** 2026-09-03 | Judged too complex; the operator chose the parse-pane redesign and the SQL-first transform instead. Nothing was built from it |
| **Scalar user functions outside the gated letter** | ⛔ **REFUSED** | Measured: the driver exposes no scalar-function API, so "user function" can only mean a per-connection macro |
| **Keying anything on the verb** | ⛔ **BANNED** | One verb appears twice; only the type is unique. And a single polymorphic entry with a discriminator cannot work, because lowering dispatches on type, never on config content |
| **The step-scaffold generator for a new type** | ⛔ **GATED** | A classpath type gets no hot deploy, no isolated loader and no watchdog |
| **The offline mock backend** | ⛔ **DELETED** 2026-08-31 | The editor now needs a real control plane |
| **A per-Pipeline config directory `config/<id>/`** | ⛔ **REFUSED** (D3, reversed 2026-09-23) | it would add a third layout; a bare `schema_file` resolves beside its own config |
| **Renaming `GET …/graph`** | ⛔ **REFUSED** (D9, 2026-09-22) | a link buys the discoverability; a rename breaks every client |
| **A `renamed` drift category** | ⛔ **cannot exist** | from draft + sample, a rename is indistinguishable from remove + add |
| **An AST → SQL write path on the Filter Step** | ⛔ **REFUSED, for good** (Q2) | the round trip drops comments and re-spells everything |

## 7. Verification

### 7.1 The lane and the commit — `inspecto-engine` · `inspecto-etl`

`ConsignmentIngestorPluginTest` (the end-to-end wrap pin: a toy `StreamingFileIngester` into a partitioned store
and — `unkeyedSegmentWritesAFlatStoreWithLineage` — a flat one, asserting rows, layout and lineage),
`FinalizeSourceConcurrencyTest` (8 Consignments finalising through the shared DB stores; the same-file marker
race with exactly one loser), `ConsignmentAuditWriterTest` (contiguous ledger blocks under concurrency),
`ConsignmentStatusAccessTest`, `PartitionWriterTest` · `PartitionWriterFormatTest`, `PipelineLiftTest`,
`PipelineJobRunnerTest` (the store-layout contract), the `ConsignmentPlanner` and `MarkerManager` tests.
Content refusal: `RefusalQuarantineTest` (incl. `aMappingExpressionCannotReadTheRestrictedStore` and a byte-wise
scan of the whole Space), `FailureTextTest` (six real DuckDB error shapes), the payment pack's
`PaymentFraudTemplateGoldenTest`. Daily feed: `LaDailyIngestAcceptanceTest#aCorrectedSameNameDayReplacesItsPartitionAndAnUnchangedOneIsSkipped`,
`LaDailyFeedTemplateRulesTest`, `IndexAppendTest.aThirtyDayBackfillAnswersEveryReadLikeTheDayByDayRunAcrossThreeCompactions`,
`ControlApiSpaceTemplateSeedGateTest.everyShippedTemplateApplies`.

### 7.2 Parsing lanes and plugins

`DelimitedGrammarTest` · `FixedWidthTest` (`inspecto-etl`; ⚠ both **skip** when their fixture pipeline is
absent — two of the reactor's five standing skips), `ParsersTest` (registry, duplicate-id failure, the
display-flag stub), `XmlRecordIngesterTest` (`previewLabelsAreTheSelectorsThatResolve` — the one-walker
rule), `Asn1ParserPlugin` tests, `RealGrammarsTest` · `ParityCheckTest` (`asn-parser`; **skip** unless
`-Dasn.corpus.tests=true` and the customer corpus is on disk — DATA-GOV-1; the other three standing skips),
`ExcelExtension` tests, `SourceZones` tests, `PipelineConfigParser` tests (`FRONTENDS`, alias precedence,
`records_path`, refusals at load), `ConfigLoaderTest` (`partitions[0]:` round-trips to an empty list).

### 7.3 Casting, quarantine, expectations — `inspecto` · `inspecto-etl`

`DataTransformer` cast-failure tests (`-1` = blank), `QuarantineManager` tests, `ControlApiExpectationTest`
(`inspecto-ops` — the routes, the deduped Incident, `EXPECTATION_FAILED`), `ExpectationEvaluator` tests,
`expectations.component.spec.ts` and `expectation-form.dialog.spec.ts`.

### 7.4 Authoring — what is enforced today

* **Three round-trip tests**, each guarding a different failure: verbatim including unmodelled keys; the
  real codec through a file; and the reachable path from the editor's write route to an engine field
  (`NodeConfigNameContractTest`).
* **A repo-wide Recipe parity gate** — compile of the projection of every committed pipeline fixture must
  equal the original, and it asserts the fixture list is non-empty so an empty sweep cannot pass silently.
* **Committed server-table contracts** for node attributes (`inspecto/contracts/node-attributes.contract.json`,
  `NodeAttributesContractTest` + `node-attributes.spec.ts`) and the processor catalogue
  (`processor-catalog.contract.json`), which the client also reads and which regenerate only deliberately; the
  `step-types` pair was retired 2026-09-24. Also `measure-grammar.contract.json` (`MeasureGrammarContractTest`)
  and `contracts/sql-ast.contract.json` (`SqlAstContractTest`, `sql-ast.spec.ts`).
* **A constant-drift contract** that re-parses the executor's own source text — the strongest guard here.
* **A binding-home contract**: every category offering a picker must have a home on the save path.
* `SqlSavePathGuardTest` (9 tests; mutation-proven: removing the two call sites failed exactly the two
  integration tests), `MapNodeKeyContractTest`, `PipelineEditableTest` (single-slot discard behaviour pinned per
  slot), `RouteArmingTest`, `ControlApiRouteArmingTest`, `RouteIngestEndToEndTest`, `ControlApiStepColumnMetadataTest`,
  `MetadataGraphServiceTest`, `EvidenceMaskerTest`, `PostgresPublishJobTest`, `PipelineDocumentXlsxTest`
  (staged mode, and on Linux in CI since 2026-09-27), `XlsxSheetMergerTest`.
* **Editor specs** pinning the save payload, refusals landing in the dock, the 422-only Apply block,
  authoring affordances hidden in the read-only persona view, and the palette's three host error arms
  (`pipeline-editor.component.spec.ts`, five tests); `pipelines.handler.spec.ts` pins the palette to the enum.

### 7.5 UI specs on the parse surface — vitest (~30)

`pipeline-parse-definition.component.spec.ts`, `grammar-editor.component.spec.ts`, `grammar-editor.dialog.spec.ts`,
`grammar-csv.spec.ts`, `parsing-attributes.spec.ts`, `parsing-sniff.spec.ts`, `parser-tree.component.spec.ts`,
`pipeline-collection-definition.component.spec.ts` (consignment + unpack attributes), `run-detail.component.spec.ts`,
`batch-detail.dialog.spec.ts`, plus the pipeline-editor suite.

### 7.6 Committed artifacts

`inspecto/examples/02-parsing/` — nine format-lane examples (`fixedwidth`, `json-frontend`, `text-regex-frontend`,
`xlsx-frontend`, `xml-plugin-frontend`, `pipe-delimited`, `no-header`, `compressed-gzip`, `asn1-frontend`), each a
`pipeline.toon` (+ `grammar.toon` for fixed-width); `07-steps`; 144 `.toon` files under `inspecto/examples/` in all (counted 2026-11-15).
⚠ **No committed `parquet` example** and no committed `expectation` component.

### 7.7 Guards

`CompressionTest`'s one-way vocabulary guard; `ParsersTest`'s duplicate-id and display-flag pins; the
`node-attributes` contract pair; `check-vocabulary` keeps *Batch* (the entity), *member* (for an Entry) and
*parser config* out of the docs; `SchemaFieldTypes` at load; `tools/check-doc-counts.mjs` derives every marked
count; **`tools/check-doc-citations.mjs`** (committed 2026-09-09, wired into `ci.yml` and `.githooks/pre-push`)
checks backticked symbols and paths against `git ls-files` and the module tree.

### 7.8 Named coverage gaps (verified absent, not assumed)

| Gap | Evidence |
|---|---|
| **Five reactor tests skip by default** — `RealGrammarsTest` ×2, `ParityCheckTest` (corpus-gated), `DelimitedGrammarTest`, `FixedWidthTest` (fixture-gated) | `assumeTrue` in each |
| **No `parquet` frontend example or board row** | §7.6 |
| **The `tier: 'required'` fields ship `required: false`** — the D13 observation is un-run | `BACKLOG.md` §2 |
| **The flat lane's deletion (Row 15) has no test because it is not done** | engine |
| **The Incident hop after a commit has no harness** | [acquire plan](../acquire/plan.md#not-built) |

### 7.9 Falsify, don't read — five authoring probes

1. **Save a pipeline whose transform SQL reads a file from disk.** A refusal at save (`SQL_STEP_REFUSED`) is
   the 2026-09-09 fix holding; a clean save means it regressed.
2. **Count the type enum's constants** before quoting any node-type number.
3. **Diff the server's authored-key set (`MAP_AUTHORED`) against `RowShaper.MAP_NODE_CONFIG_KEYS`** — there is no
   client mirror any more; a key in one and not the other is the drift returning.
4. **Break the taxonomy route and open the palette.** A silently plainer palette with no message is the
   §5.4 item 6 decision still open.
5. **Add a type to the enum and nothing else, then save a graph using it.** The refusal you get names
   which of the five registration points you missed — and no build or test failed on the way.

## 8. References

⚠ **Cite the symbol, not the line** — executor line citations in this area were wrong every time.

| Concern | Where it lives | Note |
|---|---|---|
| The type vocabulary | `BuiltinNodeType` (**49**<!--count:node-types--> constants) | ⚠ Counted 20/28/20 in three docs |
| Published config shapes | `NodeAttributes` + its committed contract | **34**<!--count:node-types-with-attributes--> of the built-ins, by design |
| The authoring round trip | `PipelineEditable.toMap` / `.lower` | [engine — Pipeline model](../engine/spec-pipeline-model.md) |
| The read-only projection | `PipelineLift.lift` | ⚠ *internal only, never a file rewrite*; do not cite it as the round trip. D9: its `GET …/graph` response carries `links.roundTrip` and `readOnlyProjection` (`WB-18`) |
| The at-rest projection | `PipelineLift.stageTwo` | engine's |
| Executor dispatch | `RowShaper.shape` in `com.gamma.pipeline.exec` | ⚠ older docs cite the wrong package **and** wrong lines |
| The executable map-node keys | `RowShaper.MAP_NODE_CONFIG_KEYS` | 🔴 contradicted by the config-key reference (§2.4) |
| The authored map keys | `PipelineEditable.MAP_AUTHORED` (3 keys) | no client mirror since 2026-09-24; `MapNodeKeyContractTest` holds it to `RowShaper` |
| Live refusal codes | `PipelineEditable` constants | |
| Graph validation | `PipelineValidator` | 🔴 unknown type is a WARNING (§2.2) |
| The Recipe layer | `RecipeCompiler` (11 verbs) / `RecipeConverter` | the fifth registration point; calls `refuseUnsafeSql` since 2026-09-09 |
| The transform guard | `SqlGuard` | on the save path since 2026-09-09 (`PipelineEditable.refuseUnsafeSql`) |
| The legacy read bridge | `DataTransformer.recordFields` | |
| The function catalogue | `sql-functions.ts` (23 in 7 categories) | |
| The processor catalogue | `processor-catalog.contract.json` (122<!--count:processors--> = 34/18/67) | every statement of this number is derived by `tools/check-doc-counts.mjs` |
| The editor, the panes, the palette, unmodelled config, the transform pane | [user-guide.md](user-guide.md) | |
| Identity & lifecycle | `PipelineSettingsRoutes`, `PipelineRenameRoutes` | [Pipeline identity](../processor/spec-pipeline-identity.md) |
| The backend graph model | [engine architecture](../engine/architecture.md) · [engine — Pipeline model](../engine/spec-pipeline-model.md) | |
| The Step catalog | [step-catalog](spec-step-catalog.md) | counts agree with code |
| The config contract | [pipeline-config-keys](../config/spec-pipeline-config-keys.md) | 🔴 the `fields[]` contradiction |
| The engine's plan (execution, PIP-2..7) | [engine plan](../engine/plan.md) | |
| The archived active plans | `plans-archive/pipeline-spec.md`, `plans-archive/pipeline-waves-drain-plan.md` (archived 2026-09-10) | D1–D10 are §4.3 here and the [engine Pipeline model](../engine/spec-pipeline-model.md); the plans are provenance |
