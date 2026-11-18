---
type: Capability
area: ACQ, ING, PIP
title: Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan
description: The plan of record for the ELT module — acquisition and connectivity (ACQ), Stage-1 ingestion and parsing (ING), Pipeline authoring (PIP-1) and Pipeline execution (PIP-2 through PIP-7) — its purpose and scope, requirements of record, dated decisions, what is not built, what was refused and how it is verified. The as-built mechanism lives in the spec files of this hub, the user guide and the Step catalog.
status: current
written: 2026-09-09
supersedes-rows: REQUIREMENTS §3.1 ACQ-1 through ACQ-7, §3.2 ING-1 through ING-6, §3.3 PIP-1 through PIP-7, and EDITIONS JOB-03/JOB-04/CP-03 (this file corrects them, see §ACQ.2, §2 and §EXE.2)
resource: platform/inspecto-acquire/src/main/java/com/gamma/acquire, platform/inspecto-engine/src/main/java/com/gamma/inspector, platform/inspecto-etl/src/main/java/com/gamma/etl, platform/inspecto-engine/src/main/java/com/gamma/parse, platform/inspecto-engine/src/main/java/com/gamma/pipeline
tags: [acq, ing, pip, capability, acquisition, connections, collectors, connectors, ledger, ingestion, parsing, consignment, grammar, parser-plugin, quarantine, expectation, pipeline, authoring, step-catalog, execution, scheduler, jobs, plan]
timestamp: 2026-11-18T00:00:00Z
---

# Acquisition, ingestion and Pipelines — plan (`ACQ`, `ING`, `PIP`)

**What this covers.** The plan of record for the ELT module, end to end, in four capability areas:
**acquisition** (`ACQ` — getting files from wherever they live into a Pipeline's inbox, exactly once),
**ingestion** (`ING` — how files in the inbox become typed, committed rows), **Pipeline authoring** (`PIP-1` —
how a person describes a Pipeline, how that is checked, and how it survives the editor) and **Pipeline
execution** (`PIP-2` … `PIP-7` — how the ingest engine and the Job engine run the work). For each it says what was
required, what was decided and why, what is still open, what was refused, and which tests prove it. Read it
before proposing a change, so a settled question is not reopened; the as-built mechanism is in this hub's
spec files (§3) and the screens in [the user guide](user-guide-elt.md).

**Contents**

1. [Purpose & scope](#1-purpose--scope) — [§ACQ.1](#acq1-acquisition--connectivity-acq) acquisition · [§1.1](#11-ingestion-ing) ingestion · [§1.2](#12-pipeline-authoring-pip-1) authoring · [§EXE.1](#exe1-pipeline-execution-pip-2--pip-7) execution
2. [Requirements of record](#2-requirements-of-record) — [§ACQ.2](#acq2-acquisition--seven-requirements) · §2.1–§2.4 · [§EXE.2](#exe2-pipeline-execution--the-requirement-of-record)
3. [Where the as-built mechanism lives](#3-where-the-as-built-mechanism-lives) — ingestion and authoring · [§EXE.3](#exe3-pipeline-execution--specification-where-it-now-lives) · [§EXE.7](#exe7-pipeline-execution--as-built-pointers-where-they-now-live)
4. [Decisions](#4-decisions) — [§ACQ.3](#acq3-acquisition--decisions) · §4.1–§4.3 · [§EXE.4](#exe4-pipeline-execution--decisions-dated-one-liners)
5. [Not built and open](#5-not-built-and-open) — [§ACQ.4](#acq4-acquisition--not-built) · §5.1–§5.5 · [§EXE.5](#exe5-pipeline-execution--not-built)
6. [Refused & superseded](#6-refused--superseded) — [§ACQ.5](#acq5-acquisition--refused--superseded) · §6.1–§6.2 · [§EXE.6](#exe6-pipeline-execution--refused--superseded)
7. [Verification](#7-verification) — [§ACQ.6](#acq6-acquisition--verification) · §7.1–§7.9 · [§EXE.8](#exe8-pipeline-execution--verification)
8. [References](#8-references) — [§ACQ.7](#acq7-acquisition--references) · ingestion, authoring and execution
- Appendix — [Parked: parser field tiers interview kit (D13)](#parked-parser-field-tiers-interview-kit-d13)

> **Section numbers.** This plan merged four on 2026-11-18 (`DOCS-ELT-CONSOLIDATE-1`). **Plain numbers**
> (§1–§8 and their sub-sections) are the former ingestion & authoring plan's, unchanged. **`§ACQ.n`** are the
> former acquisition plan's sections and **`§EXE.n`** the former Pipeline-execution plan's, each keeping its old
> number behind the prefix and placed under the matching topic — so §ACQ.3 (acquisition decisions) sits in §4
> and §EXE.8 (execution verification) in §7. Every "§" reference inside an `ACQ` or `EXE` section was rewritten
> to the prefixed form. ⚠ A requirement id is a hyphenated code (`ACQ-4`, `PIP-2`); a section is "§" plus a dot
> (`§ACQ.4`, `§EXE.2.3`). The parser field tiers interview kit (D13), parked, is the final appendix.

> **Canonical vocabulary** (`GLOSSARY.md` §2, §5, §6-B — binding). A **Consignment** is a set of files ingested
> and processed together as one unit of work; ⛔ never *Batch* for the entity (the wire spellings `batch_id` /
> `batches` stay by decision). A **Parser** reads one format into rows; a **Grammar** is the authored options
> that tell it how, living **inline** on the parse Step; a **Grammar Template** is a Grammar saved to the
> `grammar` kind as a **copy source** — ⛔ never "linked", "shared" or "referenced". An **Archive** is a
> container file; an **Entry** is one inner file — ⛔ never *member*; **Unpack** is the Collector-level stage
> ([acquisition spec](spec-acquisition.md#part-3--the-unpack-stage)). An **Expectation** is the data-quality rule
> engine; a **Segment** is one record kind a plugin ingester emits (⚠ undefined in the glossary — §5).
>
> **Acquisition vocabulary.** The two authored nouns are **Connection** (a named endpoint + credential
> definition) and **Collector** (a configured collection task bound to one Connection). ⛔ The
> acquisition entity is never a *Source* — that flipped 2026-07-14 (`GLOSSARY.md` §2/§3). A *data <!-- vocab-allow: the doc states the ban it enforces -->
> origin* is a **Stream** or a **Reference**. The pipeline TOON block is `collector:`, and the parser
> reads only that spelling — there is no `source:` block and no alias for one.
>
> ⛔ **`PIP` is one requirement range with two halves, both in this file.** `GLOSSARY.md` §14 fixes the split:
> **authoring** — the editor, the config contract, the Step catalog — is §1.2 and the plain-numbered authoring
> sections; **execution** — Run, Job, Trigger, Executable — is the `EXE` part. Of the seven rows in
> `REQUIREMENTS.md` §3.3, **only `PIP-1` is authoring**; `PIP-2` through `PIP-7` are execution's. "Orchestration"
> is folded into execution; the word is not a glossary term. Where this file disagrees with
> `docs/REQUIREMENTS.md` or an `docs/EDITIONS.md` row, **this file wins** and the disagreement is stated in place.

## 1. Purpose & scope

### ACQ.1 Acquisition & connectivity (`ACQ`)

ACQ is everything that happens **before** a byte becomes a record: reaching a remote system, deciding
which files are new, proving they are complete, and handing them to ingest exactly once. Its promise is
narrow and operational — *one declarative Collector onboards a feed, and a crash never double-loads it.*

**In scope:** Connections and their credentials; Collectors and their cadence; the connector SPI and the
schemes that implement it; discovery, stability detection, duplicate prevention, watermarks, gap
detection; retry, circuit-breaking and rate-limiting; post-fetch disposition of the remote file; the
acquisition ledger; the Unpack stage.

**Not in scope, and deliberately so:**

| Adjacent concern | Whose it is |
|---|---|
| Parsing a fetched file into records | `ING` — ingestion & parsing ([§1.1](#11-ingestion-ing)) |
| What runs after a commit, and in what order | `PIP` — pipelines & orchestration |
| The Consignment as a unit of work, and its addressing | `PIP` / the [ingest spec — Consignments](spec-consignments.md) |
| The Connections **pane** and the Collector drawer | `UI` — operator console |
| Who may operate a Collector run | `SEC` — capability gating (`canOperateRuns`) |

⚠ The boundary that is easy to get wrong: **acquisition never widens to "read from N places at once."**
Multi-location ingest is composition of existing pieces — see
[multi-location ingest](architecture-elt.md#multi-location-ingest--the-composition-pattern), which is a `PIP` concern.

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
[§ACQ.1](#acq1-acquisition--connectivity-acq).)

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
and the at-rest job ([§EXE.1](#exe1-pipeline-execution-pip-2--pip-7)), including the fact that an authored `steps:` chain has
**two** execution homes; Grammar authoring detail ([grammar configuration](../config/user-guide-grammar-config.md));
the processor taxonomy's per-row product status (generated; `EDITIONS.md` owns it).

### EXE.1 Pipeline execution (`PIP-2` … `PIP-7`)

The plan of record for how Inspecto runs work: the ingest engine that moves files into stores and the Job
engine that works over data at rest. It states what is required (and where older registers are wrong), the
decisions taken and when, what is still open, what was deliberately refused, and how the claims are checked.
It is trimmed: the as-built mechanism that used to be §EXE.3 now lives in the spec files (§EXE.3 below maps each old
section to its new home), so this part stays a short list of commitments and open work.

> ⚠ **When written (2026-09-09) the area had twenty-seven current documents and six plans holding decisions
> of record**, one declared single-owner concept page, and exactly **one** practice-typed page — the
> smallest file in the set; the operator-facing procedure lived in a 71 KB *reference*, typed as a lookup
> table rather than a procedure. Since 2026-11-18 the execution documents are
> [spec-pipeline-model.md](spec-pipeline-model.md), [spec-pipeline-execution.md](spec-pipeline-execution.md) and [spec-sinks.md](spec-sinks.md) (the Pipeline model, execution and sinks) and [user-guide-elt.md](user-guide-elt.md),
> beside [spec-ingest.md](spec-ingest.md), [spec-consignments.md](spec-consignments.md) and [spec-parsing.md](spec-parsing.md) (ingest, Consignments and parsing) and [architecture-elt.md](architecture-elt.md).
>
> 🔴 **The loudest finding is a release gate resting on a class that does not exist** (§EXE.2.3).

This capability is **how work actually runs**. The organising fact — and the thing most of the
documentation blurs — is that there is not one execution engine here. There are two, they are triggered
independently, and they share one config vocabulary:

> **The ingest engine** polls an inbox, plans Consignments and drives a Pipeline over files *in motion*.
> **The Job engine** fires on cron, on a commit, on a signal or by hand, and runs work over data *at
> rest* — including a Pipeline's own second stage, hosted as a Job.

The code names this split itself: one of its trigger enums is annotated *the two-scheduler split*. Almost
every count in this area is stated two or three ways because a document measured one engine and wrote the
sentence as though it covered both.

The boundary between them is binding, not stylistic: in-motion and at-rest compose as **producer and
consumer over a shared store, never by nesting** — which is why sub-Pipelines and an `ingest` job type are
both design refusals rather than gaps (§EXE.6).

**In scope**

* **The two engines** — what triggers each, what bounds each, and where they meet.
* **The two trigger models**, their different vocabularies, and which one each board row describes.
* **The two concurrency mechanisms** and why retiring either was refused.
* **The ingest lane fork** — flat versus graph, what admits a write to the graph lane, and the flag that
  overrides it.
* **The at-rest runner** and the two lifts that serve different lanes.
* **Run identity and the ledgers** — Run ⊇ Consignment ⊇ File, the commit log, the run ledger, the async
  trigger contract and idempotent replay.
* **Resource caps and the bounded retry** — what is on by default and what is not.
* **The post-sync derived lane**, the maintenance library, and retention.
* **Failure handling** — quarantine, the orphan gate, park and drain, and the one recovery affordance per
  lane.

**Out of scope**

* Authoring anything — the editor, the config contract, the Step catalog, the registration points. All
  authoring's (§1.2).
* Acquisition mechanics (the collectors, the ledger, the stability gate) — `ACQ`.
* The signal and event backbone as a subsystem — `OPS`. This spec uses signals; it does not own them.

## 2. Requirements of record

### ACQ.2 Acquisition — seven requirements

Seven requirements, all shipped. ⚠ **The feature × edition matrix in
[`spec-editions.md`](../../layer/platform/spec-editions.md) (the `SP-ACQ-*` rows) is authoritative for the
Edition column**; this table mirrors it. The wording of the original requirement's 14 numbered areas is
[spec Part 1](spec-acquisition.md#part-1--the-original-requirement).

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| `ACQ-1` | **Connections** — named endpoint + credential definitions (SFTP/FTP/FTPS, database), reused by many Collectors | Must | ✅ SHIPPED | All ⚠ |
| `ACQ-2` | **Collectors** — configured collection tasks (paths/queries, cadence, filename patterns, dedup policy) bound to one Connection | Must | ✅ SHIPPED | All |
| `ACQ-3` | Acquisition framework — ledgers, dedup, watermarks, gap detection, retry (phases A–F) | Must | ✅ SHIPPED | All |
| `ACQ-4` | Object-storage (S3/GCS/Azure/MinIO) **+ network-share (NFS/SMB)** connectors on the connector SPI | **Must** | 🟡 **PARTIAL — object storage shipped, network-share REFUSED** (§ACQ.5.1) | All |
| `ACQ-5` | Streaming source consumer (e.g. a Kafka topic drained by a Collector) | Should | ✅ SHIPPED 2026-07-08 | All |
| `ACQ-6` | Push / event-driven file discovery (replace poll where the remote can notify) | Could | ✅ SHIPPED 2026-07-08 | All |
| `ACQ-7` | etag / version-aware dedup dimensions | Should | ✅ SHIPPED 2026-07-08 | All |

**Three corrections this table makes to its predecessor** (`REQUIREMENTS.md` §3.1), each verified
against source:

- **`ACQ-4` was marked a flat `SHIPPED`.** It is not: the requirement asked for object storage **and**
  network-share connectors, and the NFS/SMB half was **declined by design**, not delivered. A `Must`
  recorded as shipped while half its scope was refused is the shape that makes a requirement register
  untrustworthy — the refusal now has a home in §ACQ.5.1 instead of being absorbed into a green tick.
- **`ACQ-6`'s route was wrong.** It documented `POST /sources/{id}/notify`; the route is
  **`POST /collectors/{id}/notify`** (`AcquisitionRoutes`), audited as `collector.notified`.
- **`ACQ-7`'s config key was wrong.** It documented `source.duplicate.mode`; the block is
  **`collector.duplicate`** (`PipelineConfigParser.parseCollector` — `duplicate` is read from the
  `collector` map).

⚠ **`ACQ-1` carries an edition nuance the matrix column cannot express.** Connections are `All`, but two
of `SecretResolver`'s five reference forms — `${FILE:…}` and `${KEYSTORE:…}` — are **Standard +
Advanced only** (SEC-07): a Basic bundle refuses the scheme *by name* and a connection test surfaces that
as the failure ([spec](spec-acquisition.md#connection-profiles-secrets-host-keys-tunnels-and-proxies)).

🔴 **A requirement is not delivered until it is *reachable*.** For 85 days the remote connectors were
build-available and deploy-absent — no bundle shipped `inspecto-connectors` — until CONNECTORS-BUNDLE-1
(2026-09-07). "SHIPPED" against a module no bundle carries is the most expensive kind of true statement
([spec](spec-acquisition.md#the-connector-spi-and-the-resolvable-schemes)).

### 2.1 Ingestion

Six requirements from `REQUIREMENTS.md` §3.2 (that section was stripped to an index on 2026-09-09 — this file
is their only home), all recorded shipped. **Two describe a narrower build than exists, and one describes a
broader one.** ⚠ **`EDITIONS.md`'s feature × edition matrix is authoritative for the Edition column**; this
table mirrors it.

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| `ING-1` | Stage-1 M..N multiplexer: parse → transform → partition → commit, batch-atomic | Must | ✅ SHIPPED | All |
| `ING-2` | Format frontends: delimited grammar, fixed-width (text + binary), plugin `StreamingFileIngester` SPI | Must | ✅ SHIPPED — ⚠ **understated**: the built set is **eight formats** | All |
| `ING-3` | Compressed input streaming (gzip / bz2 / zip) | Must | ✅ SHIPPED — **superseded in shape** by the Unpack stage (2026-08-23): streams *and* Archives, at the Collector, with caps and a verdict ([acquisition spec](spec-acquisition.md#part-3--the-unpack-stage)) | All |
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
(`STEP-TYPES-DEAD-CLIENT-MIRRORS-1`). Correcting the config-key reference is still owed (§5.4 item 2).

### EXE.2 Pipeline execution — the requirement of record

#### EXE.2.1 The six execution rows

| Row | Register says | Correction of record |
|---|---|---|
| **PIP-2** | Medallion ELT: raw → clean partitioned → derived — `SHIPPED`, all | ✅ Accurate. Worth adding what the register does not say: the derived tier is a **separate post-sync lane**, Consignment-addressed and read **after** commit, because emitting from the sink would pipe the pre-commit scratch relation. |
| **PIP-3** | Incremental event-driven processing: on-pipeline commit triggers, watermarks, cron + catch-up — `SHIPPED`, all | ⚠ **Two trigger models, and this row mixes them.** Catch-up is **Job-only** and replays exactly **one** missed fire; watermarks are per at-rest job, not per trigger. The pipeline-side model has no catch-up at all and adds an interval kind, a default-poll kind and coalescing. See §EXE.2.2. |
| **PIP-4** | Scheduler + Jobs (atomic Executables; **Run ⊇ Batch ⊇ File** status hierarchy) — `SHIPPED`, all | 🔴 **The hierarchy was renamed on 2026-08-03 to Run ⊇ Consignment ⊇ File**, and identity is the run-and-consignment pair, because a reprocess is a *new Run over the same Consignment*. This register is what the board, the backlog and commit messages cite, and it is still stale — as are three other current files, one of which contradicts itself twelve lines apart. |
| **PIP-5** | Async run triggers: 202 + runId + poll, idempotent replay — jobs **and** pipelines — `SHIPPED`, all | ⚠ Accurate for the contract. **But replay's backing store is in-memory** (§EXE.2.3), so the *replay* half is bounded in a way no register or board row states. |
| **PIP-6** | Job templates — `SHIPPED` | ✅ Accurate, and the design detail is worth keeping: templates resolve **once, at load**, so the scheduler and the job service only ever see plain, fully-resolved configs. ⚠ **Named for grep (2026-09-09):** the file suffix is `*_job_template.toon`, substitution is `${param}`, and a job references a template with the `template:` and `params:` keys. The mechanism was described here; the four literals a reader would search for lived only in `REQUIREMENTS.md`. |
| **PIP-7** | Maintenance job library (retention, compaction, housekeeping) — `SHIPPED` | 🔴 **Understated by a factor of five, and nothing is armed.** The row names four tasks. The engine handles **23** built-in ids, and five more arrive from providers (§EXE.2.2). The "curated library" it points at is **five example job files** an operator must copy; no scheduler config ships, and the shipped default space contains no maintenance job at all. The whole housekeeping posture is opt-in — which is correct by decision, but "SHIPPED" without that word misreads. ⚠ **Named for grep (2026-09-09):** the contributed backup-verification task id is `backup_verify`, and the retention tasks take `min_age_days` — ⛔ keep it beyond the reprocess horizon, because reprocessing a compacted-away consignment is unsupported. |

#### EXE.2.2 One number per fact

Every count in this area is stated two or more ways, and the pattern from the authoring spec repeats: the
*narrative* documents are wrong and the *generated or catalogued* ones are right. Measured 2026-09-09:

| Fact | Measured | What the documents say |
|---|---|---|
| Job types | **10 core + 2 from the operations module = 12** | the glossary says 4 in its binding entry and 9 in its own rename table; the jobs concept page says 4, naming a deprecated enum; the board lists 10 — but a *different* 10, omitting alert evaluation and the sample, and including the two module ones |
| Maintenance tasks | **23 built-in + 5 contributed = 28** (re-counted 2026-11-15: `MaintenanceJob.BUILT_IN_TASKS`, counting `heartbeat` and `noop` apart; contributed `backup`, `backup_verify`, `restore`, `incident_purge`, `audit_anchor_export`) | the requirement says 4; the board says 16; the jobs page says 13 + 3; **the served descriptor says 16** |
| Trigger vocabulary | **two models** — pipeline: interval, cron, event (`commit`/`dataset`), manual, default-poll, and since the push-ingest lane stream (six `PipelineTrigger.Kind`s, re-counted 2026-11-15), with coalescing and no catch-up; job: cron, on-pipeline, on-signal + guard, manual, with catch-up | the binding glossary entry lists 4 and **omits on-signal entirely** while counting one mechanism twice; the active plan says 3; the jobs page says 4; the board and the boundary page say 5 |
| Built-in node types | **49**<!--count:node-types--> | an execution page says 29 — a *fifth* wrong count of this one, which the authoring spec did not catch |
| Lanes | the declared owner says **five**; three other pages say **two**, three different ways | and the owner's own table **omits the post-sync lane** that another page calls one of two |

🔴 **The served maintenance descriptor is wrong in both directions at once, and it drives the UI.** The
authoring form is descriptor-driven, so an operator sees exactly what that string lists. It advertises
**four tasks that do not exist on Personal** — the three backup tasks and the incident purge — and the
purge is precisely the one deliberately *removed* from the built-in dispatch so it would not be on every
edition. And it **hides seven shipped tasks**: the dedup, event, partition and receipt prunes, the
reference compaction, the supersede retirement, and the heartbeat. On Personal the form offers four things
that will be refused and can never reach seven that work.

#### EXE.2.3 Three rows that are green over something bounded or absent

🔴 **A release gate rests on a class that does not exist.** The backlog's pipeline-graph row — cited as
Row 15's parity blocker — reads *"`ConsignmentGraphRunner` has zero production callers, blocked on ingest output
parity, do not discharge by wiring `engages()`"*. Measured: **there is no such class** (it was renamed in
the 2026-08-31 Consignment commit), and the class that does exist is **called in production twice over** —
its `engages()` drives the live lane admission, and its `run(...)` is invoked on the ingest path. The row
is wrong on the name *and* on the claim, and it is load-bearing for a release-gated item.

🔴 **Replay is backed by an in-memory map, capped and lost on restart.** `CP-03` lists
*validate → save → arm → test-run → run → replay* as ✅ in all three editions with no caveat, and two more
documents present replay as unconditional. The route resolves the original run from a synchronized
insertion-ordered map with an eviction hook — so **replay of anything evicted, or of anything at all after
a restart, is a 404**. The observability spec additionally misattributes that 404 to the durable job
backend flag; it is the wrong cause, and the two failure modes are opposite — replay works with the
projection off for a run still in memory, and fails with the projection on for one that has been evicted.

**The intake file cap is on by default** — `ingest.maxFilesPerCycle` = 500
(`IntakeGovernor.DEFAULT_MAX_FILES_PER_CYCLE`; operator, 2026-10-06, the 2026-09-15 soak precondition
waived). An explicit `0` — system property or per-Pipeline `intake.max_files_per_cycle` — means unbounded.
Adaptive back-pressure and `ingest.minFilesPerCycle` are unchanged; the byte cap stays off by default.

#### EXE.2.4 The default that does not exist, in six more places

The embedded database's memory cap is documented as a shipped 2 GB default in six current locations beyond
the two the editions spec already corrected. The resolver returns **nothing** when no value is installed,
no scheduler config ships, and the committed corpus sets the key to an empty string.

The instructive one: **the backlog asserts the 2 GB default in its own standing-decisions list while its
own gap row, in the same file, records the opposite** and says explicitly not to close the gap off that
decision. Two more give two further framings — one says the default is the database's own ~80% of RAM,
another shows 16 GB, and a third lists the key among "fixed ceilings" without noting that none is
installed. Five framings of one absent default.

⚠ **The measurement behind the number is sound and worth keeping** even though the default was never
installed: 2 GB was ~2.2× the highest observed peak, and the finding that matters is that peak memory does
**not** scale with input on this path — what a cap governs is the blocking operators, and they **hard-fail
instead of spilling**. An aggressive cap turns working jobs into failing ones. That is why no default is
defensible without knowing the host. ✅ **Closed 2026-09-26 (GAP-4)** by a default that does know it: 40 %
of the JVM-visible RAM ÷ 4, floored at 1 GiB (`DuckDbUtil.defaultMemoryLimit`).

#### EXE.2.5 "The only bound" is stale in three documents and in the code

Three current files state that the job concurrency flag is *the only bound on Runs*, one of them adding
*"default 0 = unbounded"*. The concurrency half of the resource-cap pair has been **on by default at four
since 2026-08-26**, owned by the server configuration with the flag as a bootstrap default only. And
🔴 **the class's own javadoc still says "0 = unbounded, the default"** directly above the constant that
sets it to four.

There are also **two** bounds, not one (§EXE.3.3), so the sentence is wrong twice.

## 3. Where the as-built mechanism lives

The specification sections of the two former capability files were trimmed on 2026-11-18. Each shipped fact
now lives once, in its owner:

| Mechanism | Owner |
|---|---|
| Consignment planning, `collector.consignment`, the run budget (`sources.max × processing.threads × duckdb_threads`) | [ingest spec — Consignments and ingest](spec-ingest.md) |
| `ConsignmentIngestor`, the two strategies, union vs generation mode (`large_file_bytes`, `flush_records`), staged write and reveal, the crash-safe commit order, unkeyed → flat store (E1) | [ingest spec](spec-ingest.md) |
| `route:` on the poll path and `-Dingest.lane` | [ingest spec](spec-ingest.md) · [Pipeline spec — execution](spec-pipeline-execution.md) |
| The `parsing:` block, the frontends and their engines, `ParserPlugin` / `Parsers`, ASN.1, the XML bridge, every parsing key and its refusals | [ingest spec — parsing](spec-parsing.md) |
| The wrap-SPI (`StreamingFileIngester`, `RecordSink`, `DuckDbRecordSink`, the Appender), segments, the two `partitions[]` readers | [ingest spec](spec-ingest.md) |
| Unpack — placement, caps, verdict, ledger, identity | [acquisition spec Part 3](spec-acquisition.md#part-3--the-unpack-stage) |
| Casting, cast-failure measurement (`-1` = not measured), quarantine reasons, reject rows, the ledgers, in-memory live progress | [architecture](architecture-elt.md) · [ingest spec](spec-parsing.md) |
| The source time zone (`SourceZones`), the Appender, threads | [engine — DuckDB](../engine/spec-duckdb.md) · [ingest spec — parsing](spec-parsing.md) |
| Per-field date format and epoch timestamps; content refusal (`processing.refusal`); the `postmed_xdr` reference build; the daily-feed template, export contract and delivery checks; the Parse pane and field tiers | [user guide](user-guide-elt.md) |
| The authoring model (derived graph, three flags, five registration points, the Pipeline Document and its workbook), the transform surface, the editor, identity and lifecycle | [user guide](user-guide-elt.md) · [Step catalog](spec-step-catalog.md) |
| The round trip (`toMap` / `lower`), lift and `stageTwo`, the validation ladder, the node-type model | [Pipeline spec — model](spec-pipeline-model.md) |
| Identity, rename, save-as-template, config history | [Pipeline identity](../processor/spec-pipeline-identity.md) |

### EXE.3 Pipeline execution — specification, where it now lives

The as-built specification that stood here (§EXE.3.1–§EXE.3.11) moved on 2026-11-18 (`DOCS-ELT-CONSOLIDATE-1`) into
the spec files. A "§EXE.3.x" reference elsewhere in this file resolves through this table.

| Was | Now |
|---|---|
| §EXE.3.1 Two engines | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Two engines and their triggers* |
| §EXE.3.2 Two trigger models (incl. the `dataset.write`-on-completion ordering and the `on: dataset` self-loop guard) | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Two trigger models*; the entry-node trigger grammar in [architecture-elt.md](architecture-elt.md), the Job trigger fields in [../processor/spec-jobs.md](../processor/spec-jobs.md) |
| §EXE.3.3 Two concurrency mechanisms (the Run bound, the ingest budget, the four-layer broker, stride scheduling) | [spec-consignments.md](spec-consignments.md) |
| §EXE.3.4 The ingest lane fork | [spec-consignments.md](spec-consignments.md) (admission, the `-Dingest.lane` flag, decision rules, scratch dir, the engagement predicate, lane parity); the parity gate's status, the per-segment admission and the reason-for-flat list in [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Lane-fork admission notes* |
| §EXE.3.5 The at-rest runner and the two lifts | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *The at-rest runner, and the two lifts* |
| §EXE.3.6 Run identity, ledgers and the async contract | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Run identity, ledgers and the async contract*; intrinsic vs inherited file status in [architecture-elt.md](architecture-elt.md) |
| §EXE.3.7 Caps and the bounded retry | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Caps and the bounded retry*; the memory limit in [../engine/spec-duckdb.md](../engine/spec-duckdb.md) |
| §EXE.3.8 The post-sync derived lane | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Post-sync step chains*; the Job-side decisions (registry-not-glob reads, declared composability, a summary-write failure fails the Run) in [../processor/spec-jobs.md](../processor/spec-jobs.md) |
| §EXE.3.9 Maintenance and retention | [../processor/spec-jobs.md](../processor/spec-jobs.md) (the maintenance library, contributed tasks, retention, the purge and legal hold); compaction (quiet-window, crash-journal safe, query-transparent; a compacted-away batch refuses reprocess) in [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Post-sync step chains* |
| §EXE.3.10 Failure, park and recovery | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Failure and recovery, per lane* and *Per-Step `enabled` — park and drain* |
| §EXE.3.11 Whole-pipeline dry run (`PIPELINE-DRYRUN-1`) | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Whole-pipeline dry run* |

### EXE.7 Pipeline execution — as-built pointers, where they now live

The as-built pointer table (concern → symbol → known gap) moved to
[spec-pipeline-execution.md](spec-pipeline-execution.md) — *Code pointers*. ⚠ **Cite the symbol, not the line** —
and in this area, **check the symbol still exists** (§EXE.5.3 item 7).

## 4. Decisions

Dated, one line each, with the reason — because the reason is what stops a decision being re-litigated.
Only decisions that still bind are listed; where one reversed an earlier one, both appear. Unpack decisions
are acquisition's ([§ACQ.3](#acq3-acquisition--decisions), *Poll cycle, consignments, dedup*).

### ACQ.3 Acquisition — decisions

#### Vocabulary

| Date | Decision | Why |
|---|---|---|
| 2026-07-14 | *Source* → **Collector** (and not *Poller*) for the acquisition entity | "Source" collided with the data-origin sense; "Poller" would have excluded push inputs. Rollout is DONE end-to-end — routes `/sources`→`/collectors`, the TOON block `source:`→`collector:`. Residual by design: the lineage attribute key, `Event.source()`, and `NodeCategory.SOURCE`. `GLOSSARY.md` §13 | <!-- vocab-allow: records the Source->Collector decision -->
| 2026-07-14 | *Data Source* → **Stream** + **Reference** | Two different natures of origin were wearing one name. Acquisition config is unaffected: it stays Connection + Collector | <!-- vocab-allow: records the Data Source->Stream/Reference decision -->

#### Architecture & packaging

| Date | Decision | Why |
|---|---|---|
| 2026-08-11 | `PipelineConfig.collector` is **singular, permanently** | Every stateful subsystem — ledger, watermark, dedup, gap detection — is keyed on one collector id. A list would force a ledger re-key, which is a migration, not a feature |
| 2026-09-07 | The connector sidecar is **NOT edition-gated** | Remote acquisition is core, not a paid tier. `inspecto-connectors.jar` rides every bundle; the ~32 MB was accepted deliberately (CONNECTORS-BUNDLE-1) |
| 2026-09-06 | **SEC-07** — `${FILE}` / `${KEYSTORE}` are Standard + Advanced; the SPI core stays in `inspecto-acquire` | Keeps the lean core free of a secrets client while letting Basic refuse the scheme **by name** rather than failing obscurely. Vault deferred to policy, not code |
| 2026-06-24 → reversed | Connections as a standalone `inspecto-connect` repo with a cross-repo `@PublicApi` | See §ACQ.5.2 — reversed in fact, and this plan is where that reversal is recorded |

#### Connectivity, probe & secrets

| Date | Decision | Why |
|---|---|---|
| 2026-07-08 | Every remote connector is **SDK-free** — SigV4 for `s3`, SharedKey for `azure`, and (2026-07-22) a native JSON API + RS256 service-account JWT for `gcs` | A small SBOM is a FedRAMP asset; three cloud SDKs would have dwarfed the whole artifact |
| 2026-07-08 | `kafka` uses `assign()`+`seek()` with **no consumer group** | The consumed frontier rides the acquisition ledger watermark instead, so replay and crash-recovery reuse machinery that already exists and is already tested |
| 2026-09-24 | The stream consumer loop **stays in the Collector scan**; broker consumer groups stay out (STREAM-CONSUMER-1 Q2, Q4) | No latency target was named, so a continuous lane buys nothing yet; one offset store and fewer ACLs outweigh broker-visible lag |
| 2026-09-29 | The continuous `trigger: stream` lane is **built** (option B), reversing the 2026-09-24 deferral; push ingest is a DATA write, not a config write (ASSURE-PUSH-INGEST-1) | D-P7 named the latency target the deferral waited for; a push is data arriving at a Stream, so the write-root and approval gates (config) do not apply — it is gated as an operation, capped, rate-limited and audited |
| 2026-09-24 | A remote slice's frontier must **survive a restart**, for `kafka` AND `db` (STREAM-CONSUMER-1 Q3) | A restart must never re-ingest; built as a durable per-slice record, not a name-derived frontier |
| 2026-08-13 | HTTP `CONNECT` proxy dial-through **shipped, reversing an earlier fail-closed rejection** | The rejection assumed a JDK socket could tunnel transparently. It cannot, so the tunnel had to be explicit — the earlier "no" was based on a wrong premise, not a policy |
| 2026-09-06 | JDBC dial-through is **PostgreSQL only**, fail-closed elsewhere | Each driver's socket-factory hook differs; a generic claim would have been untestable |
| 2026-07-18 | The connection **probe is graded** (`REACHABILITY` → `AUTHENTICATE` → `READ` → `WRITE` → `LIST`) and WRITE is `skipped` for every remote scheme | A workbench must never write a scratch object into a customer system. Only `local` does write-and-delete. See §ACQ.5.5 |
| 2026-09-17 | Connection test and probe need **`canOnboardConnections`**; explore and sample stay ungated reads | Test dials from the server's network position and probe reads through the saved credential |

#### Poll cycle, consignments, dedup

| Date | Decision | Why |
|---|---|---|
| 2026-08-12 | Consignment ordering defaults to **`mtime`** (arrival), not filename | Filename order is a guess about someone else's naming; arrival order is a fact the filesystem already knows |
| 2026-09-02 | The batch knobs live at **`collector.consignment.{max_files,max_bytes,order}`** | The planner runs before any sink exists, so the knobs cannot hang off the sink (CONSIGNMENT-HOME-1) |
| 2026-09-01 | Dedup keys are **SHA-256 hashed** in the ledger | A ledger is an operational artifact that gets exported and read; raw paths leak customer structure |
| 2026-08-05 | `file_dedup` is a **Guarantee** (file grain); `dedup` is a **Step** (record grain) | One word was doing two jobs at two grains — the split is why either can be reasoned about |
| 2026-08-23 (operator, `d6cd55b7`) | **Unpack sits at the Collector, before planning**; the Consignment model is unchanged | An Entry is a File from birth; "update the Consignment" needs a merge the immutable manifest has no seam for |
| 2026-08-26 (operator) | Four Unpack verdicts; **`UNPACKED_PARTIAL` commits**; `EMPTY` ≠ `UNREADABLE` by `entriesFound()` | A bad Entry must not discard 499 good ones; an operator must hear "empty" apart from "locked" |
| 2026-08-26 → ratified 2026-09-06 | Lineage records the **Entry name**, never `archive!entry` nor the index prefix; the verdict reports at **archive level** with **entry-grain** detail, one ledger row per Archive per Run | An operator asks "did that archive land?" and then "which member failed?" — one answer cannot serve both; a data column must not carry a key consumers parse |
| 2026-08-28 | One decompression vocabulary (`Compression.INLINE_SUFFIXES`), **one-way** drift guard | The SPI is deliberately wider; reverse containment would forbid `.Z` / `.tar` |
| — | Unpack `depth` **= 1**; nested archives refused by name. Identity is extension-insensitive and the collision is **not a bug** | Recursion needs a bound nobody asked for; the allow-list is the remedy, a two-tier scheme would break the actual requirement |
| 2026-10-06 (operator) | `gap_detection` gains `file_template` + `seq_scope`; a date-only template means one expected file per day | The completeness KPI and the delivery check need a second token and a per-day expectation the `sequence` grammar cannot express |
| 2026-11-17 | The per-cycle **byte cap also bounds the fetch** when every listed file has a size (MoSCoW C8); the file-count cap stays after landing | Moving the adaptive, on-by-default file cap would change every remote Collector's default |

🔴 **One gap in the decision record, stated rather than papered over: there is no dated decision anywhere
in the current tier for the stability gate** — the mechanism that decides a remote file has stopped
growing. It is the single most consequential piece of acquisition (get it wrong and you ingest a
half-written file), it is fully built and documented as *mechanism* in
[spec Phase B](spec-acquisition.md#phase-b--the-readiness--stability-gate), and *why* its thresholds are what they are is
recorded nowhere. ⛔ Do not invent one. It needs an operator to state the intent, and until then the defaults
are as-built facts with no recorded rationale.

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

### EXE.4 Pipeline execution — decisions (dated one-liners)

| Date | Decision | Who |
|---|---|---|
| 2026-06-17 | ⛔ **No `ingest` job type** — ingestion is the Pipeline's sole responsibility and an `ingest` job is a config error; a Job is strictly downstream over data at rest, never re-acquisition | design |
| 2026-06-18 | **An authored Pipeline at rest is hosted as a Job**, on the existing scheduler — the Job is the only seam that reads data already written | engineering |
| 2026-07-09 | **Job Types are plugins on an open registry keyed by string id**, not an enum; descriptors drive the generated authoring forms | engineering |
| 2026-07-09 | **An unresolved required parameter fails the Run before user code**, in a rejected state | engineering |
| 2026-07-12 | **Housekeeping is tasks on one job type — never shell scripts or host cron**, so findings emit signals an Alert Rule can consume | engineering |
| 2026-07-12 | **A task with no preview does nothing on a dry run** — fail-closed, never falling through to the real action | engineering |
| 2026-07-18 | ⛔ **`…/run` and `…/trigger` must never be merged** — one simulates into scratch, the other operates | engineering |
| 2026-07-20 | **A cron registration returns a cancellable handle and removal cancels it**, with the fire-time guard kept as a second line — otherwise a deleted job's self-re-arming chain ticks as an inert no-op **forever** | engineering |
| 2026-07-20 | **A parameter type mismatch rejects exactly like a missing required parameter** — else the raw string reaches the job's own parse and throws uncaught mid-run | engineering |
| 2026-07-20 | **Job-pack quiesce, both halves** — the loader is pinned per in-flight Run and a stale job is rejected rather than run, because a Run already executing pack code must not have its resources yanked | engineering |
| 2026-07-25 (**T15**) | **Cycle overrun, not inbox lag, is the throttle signal** — admitting fewer files cannot reduce inbox age, so throttling on it is **positive feedback** that pins a healthy-but-backlogged pipeline at the floor | engineering |
| 2026-07-25 (**D12**) | **Chunking on by default at 8 GiB** — the threshold exists for pathological single files, so it sits far above any routine input | operator |
| 2026-07-25 (**D5**) | **Incident retention is a retention tier, not archive-is-terminal** — otherwise "archived" would mean kept forever, the posture compliance must bound | operator |
| 2026-07-27 | **Named a purge, not a prune, and the window is required with no default** — a defaulted window on the one task that hard-deletes business records would be indefensible | engineering |
| 2026-07-27 | **Retention is derived from the close time, not stamped** — trade-off taken consciously: shortening the window later retroactively widens eligibility | engineering |
| 2026-07-27 (**G3**) | **A purge is not "all trace removed"** — the append-only ledger outlives the record, including its own purge entry, asserted **by a test** because it is a reviewer's first question | engineering |
| 2026-07-27 | **Correctness comes from the cutoff, not the ordering**; legal hold is re-checked *inside* the purge and dependents go before the object — the cutoff in the predicate kills the "reports zero prunable against a fully-expired corpus" failure | engineering |
| 2026-07-27 | **The memory cap was measured at 2 GB** — ~2.2× the highest observed peak. ⚠ Peak memory does **not** scale with input here; a cap governs the blocking operators, and they **hard-fail instead of spilling**, so an aggressive cap turns working jobs into failing ones | engineering |
| 2026-08-01 | **Exclusion moved from one global lock to a per-pipeline non-reentrant guard** — a reentrant lock would not have deadlocked, it would have silently re-entered and **double-ingested the inbox** | engineering |
| 2026-08-02 | **Sub-Pipeline and embedded-Job nesting ruled out by design** — in-motion versus at-rest is a *binding* line; they compose as producer and consumer over a shared store | product |
| 2026-08-03 | **Run ⊇ Consignment ⊇ File replaces Run ⊇ Batch ⊇ File**, identity being the run-and-consignment pair, because the Run is the attempt and a reprocess is a new Run over the same Consignment | vocabulary |
| 2026-08-04 | **Post-sync authors never touch the Job surface** — the context delegates member by member, because an accessor would leak the whole surface into a third-party contract | engineering |
| 2026-08-04 | **Reading is a narrow seam, never a raw database handle** — a handle makes the forbidden read-modify-write trivially expressible, which had made the plan's own acceptance test unsatisfiable | engineering |
| 2026-08-04 | **Relations come from the registry, never a directory glob** — a partition directory holds files from every Consignment that wrote that day | engineering |
| 2026-08-04 | **Composability must be declared; undeclared is refused** — non-composable measures produce quietly wrong numbers, and guarding the write alone never stopped a reader averaging an average | engineering |
| 2026-08-04 | **A summary-write failure fails the Run** — the numbers are the output, and losing them silently makes a green Run a lie | engineering |
| 2026-08-05 (**D-2**) | **A converter plus one flagged verification minor, then the legacy readers are deleted — no permanent dual format.** The flag *is* the verification window, and it is the gate on Row 15 | operator |
| 2026-08-05 (**D-9**) | **Within-Consignment dedup in v1**; the windowed keyed ledger is designed and **never faked with unbounded history** | operator |
| 2026-08-05 (**D-12**) | **The Consignment rename is taken inside the major window and sequenced last**, with read-aliases for persisted rows rather than a hard cutover — post-release the same rename costs a deprecation cycle | operator |
| 2026-08-05 (**D-13**) | **Per-Step pause with park-at-boundary semantics**, so a parked Consignment stays uncommitted; dry-run and run-to-here remain the scratch-only paths | operator |
| 2026-08-06 | **"Job" un-banned** — again the canonical user-facing term for a scheduled Executable over data at rest | operator |
| 2026-08-07 | **Capability arrives by registration, never by editing a switch**, and a collision **fails closed in all three paths** — a pack redeclaring a token is rejected whole rather than shadowing it | engineering |
| 2026-08-07 | **Three-way resolution, not two** — an unregistered token rejects, a declared token with no value here falls through; conflating them is what let a typo silently use the default | engineering |
| 2026-08-09 (**D0**) | **A Job reaches an engine facility only through a declared grant, fail-closed at registration** — an undeclared service stays absent and the menu grows by demand, never speculatively | engineering |
| 2026-08-11 | **Record dedup moved out of the first stage** for failing the batch-independence test — the dividing rule is **batch independence**, not statelessness per record | engineering |
| 2026-08-12 | **Consignment ordering defaults to file arrival time**; name-ordering is the opt-in for feeds with unreliable stamps, and any other value is refused at parse | operator |
| 2026-08-15 | **One operations zone governs both cron firing and the date-token family** — a job firing just after midnight in one zone and resolving its date token in another is an **off-by-one-day generator**. Unset inherits the host; set-but-unresolvable **throws at startup**, never a silent fallback | engineering |
| 2026-08-15 | 🔴 **It is not the data's timezone key** — that describes the data's timestamps, and wiring it into the scheduler would make firing depend on **directory scan order** | engineering |
| 2026-08-15 | ✅ **Three reader sites stay on the host zone and the sweep is closed** — each is the read half of a pair over a zone-naive stored string; ⛔ converting a reader alone silently offsets every window, and in the compactor's case **drops rows** | engineering |
| 2026-08-15 | ⛔ **No key writable through the settings document may also be read from a launch flag at use time** — split ownership of one fact is exactly what that decision forbids | engineering |
| 2026-08-25 | **A permit counts a Consignment being executed — never a Pipeline, never a run**, so the four tiers are comparable numbers | engineering |
| 2026-08-25 | **Stride scheduling, not priority ordering** — the lowest priority *provably* keeps a non-zero share, with no banked credit, and the ratio is unit-testable | engineering |
| 2026-08-25 | ⛔ **The ingest run budget is not redundant with the broker** — a claim the plan made and the code refuted; it bounds everything a cycle does *before* the first broker permit | engineering |
| 2026-08-25 | **A shrink drains, never interrupts** — aborting a mid-commit ingest is not something the commit sequence was designed for; it is crash-*idempotent*, a different property | engineering |
| 2026-08-26 | **Route executes on the poll-driven ingest path**, diverting at the one choke point that holds the live connection and the materialised table | engineering |
| 2026-08-26 | **Fail-closed route arming — six rules, each refusing by name**, because every refused shape would drop rows *silently* | engineering |
| 2026-08-26 | **The arming rules have one statement and two callers** in two config states — restating them over raw maps is the hand-mirrored-map drift this repo has already paid for three times | engineering |
| 2026-08-26 | **The save-time arming pre-check ships, and the earlier reason for deferring it was wrong** — arming did validate at load, but neither validate nor write called it, and **a fail-closed gate the author never sees is a log line** | engineering |
| 2026-08-26 | **Provenance follows the key, never file presence** — the cap is nullable end to end, and an explicit null hands ownership back to the flag live. Before it, saving anything on the form **silently seized provenance to the file forever** | engineering |
| 2026-08-26 | **The memory-limit grammar is served, never mirrored**, anchored and portable, with the bound **inside** the pattern — a bound beside the grammar lets the form accept what the write refuses | engineering |
| 2026-08-26 | **Every scheduler-settings change is journalled with deltas**, only actual changes and only moved keys — a path-classified audit row cannot carry *what the numbers became*, which is the half an incident review asks for | operator |
| 2026-08-26 (**D11**) | **The resource pair ships, both owned by the server configuration** — the Run bound default-on in code, the memory limit served with **no code default**, because total exposure is the limit times the concurrent Run count | operator |
| 2026-08-26 | **The Run bound counts in-flight Runs even while unbounded, and clearing reverts rather than freezes** — 🔴 a semaphore subclass that re-derived "did this Run take a permit?" from the current cap ended at **seven permits under a cap of four, permanently** | engineering |
| 2026-08-29 | **The graph lane is no longer route-only** — a non-routed pipeline is admitted wherever the write is provably reproducible, and the fan-out gains **per-destination crash resumption the flat loop never had**; parity is *proven by a differencing test*, not asserted | engineering |
| 2026-08-29 | **Decision rules are a space-registry fact, not a config property** — applied once above the fork, with their *result* part of the admission | engineering |
| 2026-08-29 | **A pipeline with no scratch directory stays flat** rather than parking its branch ledger in a shared temporary directory — a stale branch log makes the coordinator skip the branch and **the batch writes nothing** | engineering |
| 2026-08-29 | **The engagement predicate counts branches, not sink nodes** — the node-count version engaged for plain fan-out and was refuted by its own falsification test, with a stale test pin encoding the wrong belief | engineering |
| 2026-08-29 | **Disabling a Step means two different things per lane** — scratch bypasses in memory, ingest pauses durably, because a bypass there would **lose rows** | engineering |
| 2026-08-29 | **One durable home for disabled steps, and the enabled flag is structural, never a declared attribute** — a per-node key in every lower branch is a seven-branch, two-mirror drift class | engineering |
| 2026-08-30 | **Sealing dropped, not deferred** — the partition-state machinery bought nothing the completeness measure does not | operator |
| 2026-08-31 (**D2**) | **A Step receives a token and resolves data by reference** — edges never carry records; the runtime model converges later | operator |
| 2026-08-31 | **The rename landed as one commit scoped by concept, not by string** — reusing the post-sync processor's name would have broken plugin authors and the one-word-one-concept rule | engineering |
| 2026-09-01 | **Both graph sources are optional in the descriptor and "pick one" is enforced at run time** — declaring either required made the at-rest job **unrepresentable** | engineering |
| 2026-09-01 | **The orphan output-store check is default-on in every space**, one signal per transition, with a kill switch — until then it fired only from a maintenance job that no shipped space but the demo had, so a stock space had **no orphan detection at all** | engineering |
| 2026-09-01 | **One concept page owns "which lanes exist"** — there had been about six framings across five files, none authoritative | docs |
| 2026-09-02 (**X1**) | **Bounded commit retry** with durable per-file sidecars and quarantine on exhaustion — the retry already existed and was **unbounded**. ⚠ Grounding **refuted the plan's premise**: nothing classifies transient from fatal, and the real gap was the opposite of the plan's fear | engineering |
| 2026-09-02 (**X2**) | **Cross-lane provenance records which ingest Consignments an at-rest run read**, fed from the selector's kept files and **never from the rows** — ordinary outputs carry no per-row batch id, and **unknown is not empty** | engineering |
| 2026-09-02 | **One recovery affordance per lane** — reprocess retracts the Consignment's dedup claims, drain resumes a park, replay re-runs at rest | engineering |
| 2026-09-02 | **The drain does not re-walk** — it registers the park tables and runs the real finalisation, keeping the branch commits as its resume record | engineering |
| 2026-09-02 | **The lane flag ships, extracted pure so it is testable** — Row 15's precondition, not its trigger | engineering |
| 2026-09-06 | **Clone mode arms, and committed branches become visible** — the durable per-branch log already existed; only the read surface was missing | operator |
| 2026-09-06 | **The fetch lane stays first-in-first-out** — no contention has been observed, and the trigger to revisit is the **first observed fetch-lane wait** | engineering |
| 2026-09-07 | **The backup tasks are contributed, not built in** — on Personal they are unknown tasks **refused loudly**, never a silent skip a chained job would read as success | engineering |
| 2026-09-08 | **The incident purge had to be removed from the built-in dispatch, not merely also provided** — a named case always beats a contributed provider, so leaving it would silently keep it on every edition | engineering |
| 2026-09-20 (**`PIPELINE-DRYRUN-1`**) | **A dry run skips `strategy.ingest` WHOLE rather than substituting its ~20 durable sites** — per-sink gating is the shape that misses one; skipping is true by construction. ⚖ Accepted cost: a dry run answers *what would this cycle touch*, **not** *would these files parse* (§EXE.3.11) | engineering |
| 2026-09-20 (**`PIPELINE-DRYRUN-1`**) | **`?dryRun=true` IMPLIES `?skipPostAction=true`**, OR-ed in at two levels — a dry run that acked and deleted the customer remote file is the worst failure the feature can have, and must not depend on a caller remembering | engineering |
| 2026-09-20 (**`PIPELINE-DRYRUN-1`**) | **The dry run PUBLISHES a MARKED `ConsignmentEvent`**, which narrowly overturns *cron/event/signal fires are always real* — safe only because there is exactly ONE fan-out, so the consumer set is closed and each was made to honour or refuse loudly (§EXE.3.11) | engineering |
| 2026-09-23 (**`DRYRUN-INVISIBLE-ON-FLAT-LANE-1` a**) | **A flat-lane dry run writes ONE provenance record flagged `simulated`** — a deliberate exception to *skip the pass whole*, metadata only and the ONLY write a dry run makes (counts are the truthful zeros of a skipped pass), so the overlay promise holds on the lane the feature shipped for rather than being retracted (§EXE.3.11) | operator |
| 2026-09-24 (**`FLAT-DRYRUN-COUNTS-ZERO-1`**) | **A dry run PARSES: `strategy.ingest` runs CONTAINED instead of skipped** — over member copies, against `forScratchRun`, under a suffixed batch id, with `EventLog.CONTAINED` bound — so the 2026-09-20 row's accepted cost is lifted without substituting a single site; provenance counts become real and each member is reported by kind (`WOULD_LAND` / `REJECTED` / `SKIPPED` / `FAULT`), the evidence X4's replay default waits on. ⛔ No replay default was picked (§EXE.3.11) | engineering |

## 5. Not built and open

⛔ **Pointers, never copies.** Each row names its board id; the board is the authority for status and
priority. A row with no id is flagged `UNTRACKED` and needs filing before it can be scheduled. The 2026-09-09 (Sprint 2) filing matched findings to
cross-cutting board rows by family (`CONSUMER-PAIRS-1`, `SPEC-STALEREF-1`, `SPEC-COUNTS-1`, `SPEC-NOPROOF-1`,
`SPEC-GLOSSARY-1`, `SPEC-GREENCELL-1`, `SPEC-PLANSTALE-1`, `SPEC-ORPHANPAGE-1`); ⚠ none of these ids is an open
board row today (re-checked 2026-11-15, `DOCS-AGENT-WRITTEN-REVIEW-1`) — history in
`docs/archived-documents/backlog-snapshot-2026-09-24.md` and
[`post-consolidation-sprints.md`](../../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.
Area-specific design findings stay here deliberately: the board holds what **crosses** areas, a plan holds
what belongs to **one**.

### ACQ.4 Acquisition — not built

#### Partial — the capability exists, the acquisition-node path is unproven

| Item | Board id | What remains |
|---|---|---|
| S3 / GCS object ingest | `SP-ACQ-06`, `SP-ACQ-08` | The connectors ship and are tested; there is **no proven end-to-end acquisition-node run**. ⚠ This is not a contradiction of `ACQ-4` — *a connector is not a Step* (§ACQ.5.7) |
| Azure ADLS Gen2 | `SP-ACQ-07` | Blob works; the Gen2 hierarchical namespace is unproven |
| Kafka consumer as a Collector | `SP-ACQ-09` | The per-scan drain ships, hardened to slice-level at-least-once. What keeps the cell 🟡: it is proven only against `MockConsumer` and a fake offset-tail connector; the real-broker test (`KafkaRealBrokerTest`, 2026-09-29) exists but SKIPS where no broker runs, which is every build so far. Consumer-group semantics stay out by design |
| Excel workbook fan-out | `SP-ACQ-05` | One sheet per file today; multi-sheet fan-out unbuilt |
| The stream lane's Incident hop | — (ASSURE-PUSH-INGEST-1) | Event → committed slice is measured (p95 1030 ms); alert evaluation after the commit has no harness, so D-P7 (p95 ≤ 30 s event → Incident) is not proven end to end |

#### Planned — declared, unstarted

`SP-ACQ-10` … `SP-ACQ-20` — eleven 🔲 rows: Pulsar, Kinesis, SQS, RabbitMQ, MQTT, Debezium CDC, Delta,
Iceberg, Snowflake/BigQuery readers, a REST poller, a webhook receiver and gRPC. ⚠ They are *declared*
so they are not re-proposed as new ideas; none is scheduled.

**Still future (declared, so it is not re-proposed as new):** a presigned-URL / STS credential mode for `s3`;
a Vault/KMS `SecretResolver` scope. The SPI makes each non-disruptive. (The remote-fetch bandwidth cap that
used to sit here was built 2026-11-17 — see below.)

#### Open elsewhere on the board

| Item | Board id | What remains |
|---|---|---|
| Vault / KMS secret provider | `BACKLOG.md` §6 "Decided 2026-09-06" note (`GAP-6` in older docs; no open row) | Deferred by the SEC-07 decision, not blocked: only when a client policy requires it |
| ~~"Listed-not-yet-fetched" gauge~~ | branch-aware residual **(e)** | **DROPPED (operator, 2026-10-06)** — recorded on the *Branch-aware executor residuals* row |
| ~~Remote-fetch bandwidth cap (was `acquire.maxFilesPerCycle`)~~ | branch-aware residual **(f)** | **BUILT 2026-11-17 (MoSCoW C8)**: `-Dingest.maxBytesPerCycle` is applied before the fetch when every listed file carries `RemoteFile.size` ([spec](spec-acquisition.md#the-poll-cycle-and-its-two-timers)); an unsized listing keeps the cap after landing. The file-count cap stays after landing |
| Multi-part archives; xz / zstd codecs; `.Z` round-trip test; a stale `META-INF/services` header | Unpack (11), `BACKLOG.md` §3 *Onboarding, Catalog, Parsing* | `depth: 1` is deliberate (§ACQ.5.8); *multi-part* (`.z01`, split RAR) is simply unbuilt; xz/zstd is a dependency sign-off, not a build |
| Unpack (5)(8)(10) — partial never fails; nested refused; crash mid-archive re-ingests committed Entries by design | `BACKLOG.md` §6 | LEAVE |
| ~~Missing connection not caught until first poll~~ | — (closed) | **Now caught at save**: a remote Collector whose `collector.connection` names no profile in the Space is an ERROR finding at `/config/write` (`ConfigRoutes.unknownConnectionFindings`); a Space import with a missing connection is a WARNING that writes the dependents switched off (2026-09-25) |
| Acquisition ledger defaults to `memory` | — (the *Completeness KPI hold* that carried it was lifted 2026-10-06) | 🔴 The default loses the ledger on restart, and with it the committed slice frontier. The completeness KPI no longer depends on it: `kpi.completeness` reads the consignment-output store and refuses unless that store is durable (`KpiCompletenessJob`) |

#### 🔴 UNTRACKED — no board row exists; file before scheduling

> Filed as a family 2026-09-09 (Sprint 2) with no cross-cutting rows: every item here is area-specific, and
> a design finding recorded in its owning plan is already filed — copying it onto the board would give it two
> homes. See [`post-consolidation-sprints.md`](../../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.

| Item | Where its design lives | Why it matters |
|---|---|---|
| Credentials & network profiles as **their own referenced resources** | `plans-archive/acquire-controller-service-design.md` §2.1 | The one untracked item with real design content and no home. Today a `tunnel` is **inline** on the Connection, so two Collectors through the same bastion duplicate it — and a rotation edits N places |
| Controller-service **VALID/INVALID lifecycle + run-gating** | same, §3 | Verified absent: validation gates *save* and *compile*, never *run*. A Collector whose Connection has gone bad still starts a run and fails inside it |
| `acquisition.list` / `acquisition.fetch` node split | same, §4 | Verified: `BuiltinNodeType.ACQUISITION` is the only acquisition node. ⚠ The SPI seam for the split already exists, so this is smaller than it reads |
| A "referencing components" read view | same | "What breaks if I change this Connection?" has no answer today |
| Presigned-URL / STS credentials for `s3` | `AwsSigV4`'s class javadoc states the scope reason in-code | Long-lived keys are the only option today |

### 5.1 Ingestion — tracked

| Item | Board id | What remains |
|---|---|---|
| A drop-in `plugins/` jar directory (the ASN.1 grammar as a stored `.asn` file SHIPPED 2026-09-23 — [ingest spec — parsing](spec-parsing.md)) | `BACKLOG.md` §3 *Parsing (Stage-1)* | a trust decision |
| **D13 parser field tiers** — the onboarding-observation session; `tier: 'required'` ships `required: false`. The pre-agreed analysis rule and the deliverable that closes it are in [grammar configuration](../config/user-guide-grammar-config.md); the kit is the parked appendix [§ Parked: parser field tiers interview kit](#parked-parser-field-tiers-interview-kit-d13) | `BACKLOG.md` §2 | externally gated |
| **Row 15** — delete the legacy flat read path (`-Dingest.lane` is the precondition) | `BACKLOG.md` §2 | release-gated |
| D-8 **XLSX export** (zero groundwork, no spreadsheet library) | `BACKLOG.md` §3 | |
| Column metadata editing on the Transform pane (Parse D2) | `BACKLOG.md` §3 (e) | built for chain Steps 2026-10-11; option (C) — the Step's declaration REPLACING the fail-closed rule — not built (`AUTHORING-REDESIGN-1` (e)) |
| `SP-PRS-09…17` planned parsers (key-value, YAML, ASN.1 PER/XER, EBCDIC, protobuf, Avro, PCAP, Grok, syslog) | `EDITIONS.md` board | no BACKLOG row |
| Delimited robustness knobs (a)(c) | archived board snapshot 2026-09-06 only | no live row |
| Re-delivery semantics of a daily feed (replace vs append of the partition, late-data effect on the Link Analysis Index) | `LA-DAILY-INGEST-1` T4 | until T4 lands, treat a re-delivered day as unverified; a date DROPPED by a correction still leaves the old file for that date (roadmap T4 b2) |
| Content refusal: the Collector's discovery logs and its acquisition ledger see the inbox name before the check runs | — | not covered by the refusal |
| Unpack items (xz/zstd, `.Z` round-trip, multi-part archives, `data_extensions[0]:`, stale services header) | [§ACQ.4](#acq4-acquisition--not-built) | |

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

### 5.5 Execution's, not authoring's

`PIP-2` through `PIP-7` — the medallion lanes, incremental triggers, the scheduler and Jobs, async run
triggers, job templates and the maintenance library — belong to Pipeline execution ([§EXE.1](#exe1-pipeline-execution-pip-2--pip-7); what is not
built there is [§EXE.5](#exe5-pipeline-execution--not-built)), as do the **second execution home of an authored chain** and the requirement row
whose status hierarchy still uses a word the glossary retired in 2026-08.

### EXE.5 Pipeline execution — not built

#### EXE.5.1 Tracked, externally gated

* **Row 15 — the deletion half of the legacy flat read path.** Gate: a minor release on the trunk after the
  newest ancestor tag, shipping the converter and the lane flag. ⛔ Not closable by code, and ⛔ not to be
  started on momentum. 🔴 The earlier gate was **unfalsifiable by construction** — it named a tag on a
  retired line that can never be a trunk ancestor. Of its four steps, one is shipped, the parity gate through
  the compiled path is **met** (2026-09-16, §EXE.3), one shipped 2026-09-02, and the deletion awaits a release.
  ⚠ Its stated parity blocker is the phantom-class row in §EXE.2.3.
* **Cross-lane drill-down and the step-info envelope**, and **the batch-id rename trio** — both ride the
  next major tag. ⚠ The rename trio is **not enumerated in the drafted release notes**.
* **Distributed scheduler coordination** — ⚠ **no backlog row of its own**; it exists only as an Enterprise
  board cell and an unscoped gate.

#### EXE.5.2 Tracked and actionable

* ✅ ~~**Record-level replay from quarantine**~~ **SHIPPED 2026-09-28 (X4):** `POST /runs/{name}/replay-rejects`
  and `processing.reject_mode: eject | all_or_nothing` (§EXE.3).
* ✅ ~~**The retry deferrals**~~ **SHIPPED:** the per-pipeline `processing.retry {max_attempts, initial_backoff,
  max_backoff}` block (2026-09-25, `PipelineConfigParser`, absent keys inherit `-Dingest.retry.*`) and
  `POST /runs/{name}/retries/retry-now|cancel {file}` (`RunRoutes`; retry-now clears the backoff, keeps the
  attempt count).
* A pre-materialise cap to save fetch bandwidth —
  ⚠ the latter is **design-first** (checked 2026-09-16): no unit, config key or enforcement point exists
  anywhere. `IntakeGovernor` caps FILES per cycle after listing; a pre-materialise cap would have to cap BYTES
  or files *before* the remote fetch, which needs the connector to expose size before download and a
  decision on what happens to the remainder (deferred to the next cycle, or refused). Owed as a §EXE.1 input.
* **Branch-aware residuals** — ~~multi-schema with route~~ (segment-scoped lift shipped 2026-09-24); mid-branch
  transforms in the route verb; three node kinds still unimplemented anywhere; the destination-list
  follow-ups.
* ✅ ~~**Platform services stage two and three**~~ **SHIPPED 2026-09-28 → 2026-10-04** (S2-1…S2-3: node types
  declare an execution mode, the pack Step seam `StepExecutor` / `StepContext` with grants and watchdog; S3-1…S3-3:
  packs contribute Platform Services, provider pinning, disable/enable, read-only `DatasetAccess`); the
  job-side watchdog is `JobDeadline` (2026-10-06, §EXE.3). Design archived 2026-10-04.
* **Consignment addressing residuals** — a dead field that is always zero and never read; a task that must
  be configured or every full recompute leaves a complete extra copy on disk.
* **The consignment-ELT residual** — the batch table is **structurally singular**, one schema per row while
  a Consignment emits a row set per schema. An open decision, not a bug.
* **The completeness measure** — wiring, and a job type that must **refuse loudly** when the registry
  backend is off. ⚠ Two of its components have **no production caller today**. 🔴 Its hold is neither
  carrier- nor query-gated: its first action is *engineering*.
* **Surge admission** — open. *(The memory-cap default that shared this row shipped 2026-09-26, GAP-4, §EXE.2.4.)*

#### EXE.5.3 `UNTRACKED` — found writing this spec, no board row exists

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `SPEC-STALEREF-1`, `SPEC-GREENCELL-1`, `SPEC-GLOSSARY-1`, `SPEC-PLANSTALE-1`.
> ⚠ **None of these ids is an open board row today** (re-checked 2026-11-15, `DOCS-AGENT-WRITTEN-REVIEW-1`): `SPEC-STALEREF-1` closed 2026-09-09 when `tools/check-doc-citations.mjs` became a guard, `SPEC-NOPROOF-1` closed 2026-09-09 when all six Musts had tests, and the rest were closed, refuted or split into their own rows by the 2026-09-24 board consolidation. Their history: `docs/archived-documents/backlog-snapshot-2026-09-24.md` and `docs/archived-documents/plans-archive/post-consolidation-sprints.md`.
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`archived-documents/plans-archive/post-consolidation-sprints.md`](../../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.

Ranked. The first three are visible to an operator.

1. 🔴 **A release gate rests on a class that does not exist, with a claim that is false of the class that
   does** (§EXE.2.3). Fix the row's name and its claim before anyone reasons about Row 15's readiness again.
2. ✅ ~~**The served maintenance descriptor advertises four tasks Personal refuses and hides seven
   shipped ones**~~ **FIXED 2026-09-09.** `MaintenanceJob.BUILT_IN_TASKS` is now the one declaration of
   the switch's ids (20 then, 23 on 2026-11-15), `availableTasks()` adds whatever a provider on **this** classpath contributed,
   and the descriptor is built from that — so the form offers exactly what will not throw, per edition.
   Pinned by `MaintenanceTaskContractTest`, which **re-parses the switch's own `case` labels**; proven by
   mutation (removing one id failed three of its five tests, naming the id).
3. 🔴 **Replay is backed by an in-memory capped map, lost on restart**, while a board row marks it ✅ in all
   three editions and another spec misattributes its 404 to the wrong flag (§EXE.2.3). Either persist the
   lookup or caveat the row.
4. ✅ ~~**The 2 GB memory default is asserted in six more current locations**~~ — moot since 2026-09-26: a code
   default exists (GAP-4, `DuckDbUtil.defaultMemoryLimit`, §EXE.2.4), so the documents now owe *that* number,
   not "none".
5. 🔴 **"The only bound" is stale in three documents and in the class's own javadoc**, which says the
   default is unbounded directly above the constant that sets it to four (§EXE.2.5).
6. 🔴 **The status hierarchy is still stated with the retired word** in the register and three more current
   files — and the user guide **contradicts itself twelve lines apart**. One of these was recorded as
   "corrected" by an earlier spec and is still on disk.
7. 🔴 **Twenty-plus dead class citations** survive the 2026-08-31 rename, including **five in the active
   plan's "what actually runs" section** and two cited *by line number*.
8. ⚠ **"Lane" means four different partitions across five documents**, and the declared owner's own table
   **omits the post-sync lane** that another page calls one of two.
9. ⚠ **A job type id is misspelled in a concept page** — an underscore where the registered id has a dot.
   An operator copying it authors an unknown type.
10. ⚠ **An event name in a design page does not match the emitted constant** — the page uses the retired
    prefix, which survives only as a legacy alias, while a sibling page uses the canonical name and neither
    cross-references the other.
11. ⚠ **The retired pipeline word still names the concept in three execution documents**, including route
    templates and method names in prose.
12. ⚠ **The declared lane owner contradicts itself on the orphan gate thirty-four lines apart** — the
    second statement is the pre-2026-09-01 state that another page explicitly labels history.
13. ⚠ **The active plan says a new Step type cannot be added today**, contradicted by its own later row and
    by the boundary page; and it states the graph admission in its **pre-generalisation** form.
14. ⚠ **Two more self-contradictions**: a durable-store check recorded as both owed and discharged, and the
    seed count given as exactly one against a page that records the relaxation to one-or-more.
15. ⚠ **Record-level lineage is simultaneously refused and open** on one board — "won't do" in one spec and
    a live actionable row in another.
16. ⚠ **Refused-as-a-default versus planned-for-Enterprise** is the real position on distributed
    coordination, and **no document states it that way** — two say it is refused, three treat it as
    Enterprise scope.
17. ⚠ **No scheduler test class exists at all** for the ingest engine — recorded only in prose.
18. ⚠ **The retry's escape hatch is in no document**, and the descriptor's own count and the glossary's
    trigger entry are both wrong in ways nothing checks.

## 6. Refused & superseded

**This section exists because a refused idea with no recorded refusal gets re-proposed.** `BACKLOG.md` §6
does this for *work*; this does it for *design*. Each row states what was refused and the reason — the
reason is the load-bearing half. Unpack refusals (extending the ingester SPI to emit files, partial-archive
failure, nested archives, `archive!entry` in a data column, a per-entry Signal, the batch semaphore for Unpack)
are acquisition's ([§ACQ.5](#acq5-acquisition--refused--superseded), chiefly §ACQ.5.8).

### ACQ.5 Acquisition — refused & superseded

#### ACQ.5.1 An in-process NFS / SMB / CIFS client — REFUSED, and it is the security boundary

`ACQ-4` asked for "network-share (NFS/SMB) connectors on the connector SPI". **There is no share
connector and there will not be one.** The path validator **rejects UNC outright**
(`PathJail` refuses a value that "is a UNC/network path", the rule `ConfigSafetyValidator` documents as
"reject UNC/network paths"; `DataRef` bans the `\` separator that `\\host\share` needs). That rejection is not a gap someone forgot to close: it is the boundary that
keeps an authored config from reaching an arbitrary host on the network.

**What serves the need instead:** mount the share at the OS level and point the built-in `local`
connector at it ([spec](spec-acquisition.md#network-shares--the-declined-design-recorded-once)). The editions matrix's
`SP-ACQ-01` (`spec-editions.md`) is titled "**Local / NFS** directory watcher" — `LocalFileSystemConnector`, the
default collector — and reads `✅✅✅`, so NFS **is** served; what is refused is an in-process protocol client.

✅ **The two tiers now agree.** The old root `ROADMAP.md` §3.2 kept the half open, while `REQUIREMENTS.md`
closed it as the mounted-share pattern. Its successor, [`plan-roadmap.md`](../../layer/platform/plan-roadmap.md)
§3.2, records the half as "Not delivered, and not pending — REFUSED by decision" (framing corrected
2026-09-09). **The refusal wins, because the code enforces it.**

#### ACQ.5.2 A standalone `inspecto-connect` repo with a cross-repo `@PublicApi` — REVERSED IN FACT

Decided 2026-06-24 (`plans-archive/brainstorm-tingly-storm.md`): build connections as their own
repository behind a stable cross-repo `@PublicApi`. **Verified against the tree: no such repo or module
exists.** What happened instead is `inspecto-acquire` (the SPI) plus the optional `inspecto-connectors`
(the implementations) — which delivers the same dependency isolation *without* the cross-repo
versioning cost the plan itself had flagged as its main risk.

The half that was **kept** is worth naming, because it is why the reversal was cheap: the UI-first
contract freeze. The connection contract was frozen from the consumer's side first, so moving the
implementation was a packaging decision rather than an interface renegotiation.

⚠ Residue: `@PublicApi(since = "4.0.0")` marks eight `inspecto-acquire` SPI types (`CollectorConnector`,
`CollectorConnectorFactory`, `ExportConnector`, `ExportConnectorFactory`, `DiscoveryContext`, `RemoteFile`,
`PostAction`, `AcquisitionException`). It is **not** a stale marker: trunk is `4.0.0-SNAPSHOT`, and per the
[API stability policy](../api/plan-api-stability.md) `since = "4.0.0"` means "will become public API in 4.0.0"
(the deleted `4.x` branch never shipped), so these types may still change before that release.

#### ACQ.5.3 NiFi's FlowFile session/queue runtime — REFUSED

Adopt NiFi's *authoring contracts*, not its *runtime*. A live inter-node queue would make the pipeline a
stateful cluster; the flow-graph decision is recorded as locked. The consequence to keep in mind when
reading any NiFi-shaped design doc: **"predecessors ran" means materialized output on disk, not a queue
handoff.**

#### ACQ.5.4 Enriching `POST /components/connection/{id}/test` — SUPERSEDED

The controller-service design proposed extending that route. The UI's frozen
`/connections/{id}/probe | explore | sample` triad won, and the design predates the contract. Not a
rejection on merit — a document that aged past its subject.

#### ACQ.5.5 A WRITE probe against a customer system — REFUSED

`WRITE` is deliberately `skipped` for `db`, `s3`, `gcs`, `azure` and `kafka`. A workbench must never
create a scratch object or publish a probe record into someone's production system. Only `local`
performs write-and-delete, where the blast radius is a temp file we own.

#### ACQ.5.6 Edition-gating the connector sidecar — REFUSED

~32 MB, accepted. Remote acquisition is core capability, not a paid tier; gating it would make Basic
a demo rather than a product (2026-09-07).

#### ACQ.5.7 "A connector is not a Step" — the standing answer to an apparent contradiction

`SP-ACQ-06` / `SP-ACQ-08` read 🟡 "no proven end-to-end acquisition-node run" while `ACQ-4` reads
FULLY CLOSED. Both are true and neither is stale: a **Connection kind** existing (tested connector,
real credentials, real listing) is a different claim from an **acquisition node** having been proven end
to end in a pipeline run. It is recorded here so the pair is not "reconciled" by flattening one of
them.

#### ACQ.5.8 Other standing refusals, with their reasons

| Refused | Reason |
|---|---|
| A second Kafka lane for urgent topics | Urgency is a *parameter* on one node, not a second lane |
| The stream consumer as a long-running Job (STREAM-CONSUMER-1, 2026-09-24) | `JobService` models finite, non-overlapping Runs: a permanent Run holds one of the global in-flight slots forever, every later fire records `SKIPPED`, and a Job has no path to the pipeline's inbox, run guard or acquisition ledger, so it would duplicate the scheduler's wiring. A Run that never ends also makes Run history and duration meaningless |
| A Kafka slice named after its reached range, with the frontier derived from the name | The commit is source-agnostic and the ledger key is not in the name; a DB-export watermark (a timestamp with `:`) cannot live in a portable file name. One durable record serves both connectors |
| A watermark for the `dataset` connector | Snapshots are timestamp-named and marker dedup already gives re-ingest-on-refresh; do not build one on the assumption it is missing |
| Nested archives, and partial-archive failure | `depth: 1` is deliberate; a nested archive is an unbounded expansion in a jailed path |
| Extending the ingester SPI to emit files; "updating" a Consignment with expanded Entries | Unpack produces *files*, the wrap-SPI produces *records*; the manifest is immutable and `ManifestStore` has no merge; forcing an Archive's Entries into one Consignment would void the `max_files` / `max_bytes` memory bound |
| A two-tier logical-name scheme to stop the `report.csv` / `report.json` collision | It would break the requirement the mechanism exists for; the allow-list is the remedy |
| Crash-mid-archive exactly-once | Relies on `OVERWRITE_OR_IGNORE`; true exactly-once would need a per-entry ledger, which costs more than the failure |
| A per-entry Signal in Unpack; reusing the batch semaphore for Unpack | Unpack runs before planning, so the Consignment semaphore cannot bound it |
| Fetch-lane fairness | Stays FIFO until a real fetch-lane wait is observed |
| Vault / KMS **now** | Client policy, not code — the SEC-07 seam is already in place for when it lands |
| `PathJail` for the watermark store | A deliberate LEAVE: the store is engine-owned, not author-supplied, so the jail would guard nothing |
| `/metrics/acquisition` as an edition-gated cell | Core and ungated — an operator on any edition must see whether data is arriving |
| Bounds-based window pruning | Measured 1.3–2.6× faster for 29–88× fewer rows — the win did not survive contact with real cardinality |
| Merging the manual-run `runGuard` with the background `acquireGuard` | It would serialise ingest behind fetch again; the overlap it allows is benign |

⚠ **Do not carry forward** `BACKLOG.md` §6's "the TOON `source:` block stays". It is refuted:
`PipelineConfigParser` reads only `collector` (`parseCollector`), and no committed TOON carries a top-level
`source:`. Only `NodeCategory.SOURCE` survives, and that is an enum constant, not authored config.

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
| `processing.csv_settings` / `processing.ingester`; the standalone `*.grammar.toon` file | the unified `parsing:` block (2026-07-07); Grammars inline on the Step; the `grammar` kind as a template | [ingest spec — parsing](spec-parsing.md) |
| `processing.batch.*` | `collector.consignment.*` (2026-09-02) | `GLOSSARY.md` §13 |
| `Batch` / `BatchProcessor` / `BatchIngestStrategy` / `CsvBatchStrategy` | `Consignment` / `ConsignmentIngestor` / `ConsignmentIngestStrategy` / `CsvIngestStrategy` (2026-08-31) | `GLOSSARY.md` §13 |
| Record dedup in Stage-1 | the at-rest `dedup` Step (2026-08-11) | [architecture](architecture-elt.md) |
| The `year=1900` sentinel bucket | a flat unpartitioned store (E1) | [ingest spec](spec-parsing.md) |
| `use: grammar/<id>` as a live binding (2026-08-04) | Grammar Templates as copies (2026-08-15); the form read-only | `GLOSSARY.md` §5 |
| "P3d retires `GrammarEditorDialog` entirely" | binary fixed-width and the unmappable generic `parser` keep it (operator, 2026-08-16); the dangling case moved to the drawer 2026-09-06 | [grammar configuration](../config/user-guide-grammar-config.md) |
| The 4-tab delimited surface (U1–U5); `<inspecto-property-rows>`; `GrammarTemplateDialog`; the 9-type hardcoded `parser-types.ts`; the mock `/components/grammar/preview`; the Onboarding Parsing stage | the sectioned pane, one schema-form per section, Grammar CSV, the served `ParserPlugin` contract, the editor as the guided surface | [grammar configuration](../config/user-guide-grammar-config.md), [ingest spec — parsing](spec-parsing.md) |
| XML preview-only; `xml.` grammar root | `XmlRecordIngester`; keys under `ingester_config` (2026-08-30) | [ingest spec — parsing](spec-parsing.md) |
| The node-count engagement predicate for the graph lane | branch count (`dataFedSinkCount`) — "refuted by its own falsification test" | [ingest spec](spec-ingest.md) |
| "An absolute refusal of `steps:` was the original design and was WRONG" | `steps[]` runs at rest via a `type: pipeline` job | [architecture](architecture-elt.md) |
| `DATA_RULES` / `TransformCompiler` as the mapping lane | the Record Transformer (2026-09-05) | `configuration.md`; [Pipeline spec — model](spec-pipeline-execution.md) |
| `generation_threshold_bytes`; "nested archives"; LDIF `[PROPOSED]`; "XML today" preview-only; "nine format types" and `Run ⊇ Batch ⊇ File` | corrected with the ING capability spec, 2026-09-08 | those files |
| The 4.1 account of the parsing grammar (`*.grammar.toon`, `csv_settings`) | superseded 2026-07-07; flagged, a rewrite owed | [ingest spec — parsing](spec-parsing.md) |

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

### EXE.6 Pipeline execution — refused & superseded

| Item | Verdict | Why |
|---|---|---|
| **An `ingest` job type** | ⛔ **REFUSED** 2026-06-17 | A Job is strictly downstream over data at rest; ingestion is the Pipeline's sole responsibility |
| **Sub-Pipeline / embedded-Job nesting** | ⛔ **REFUSED by design** 2026-08-02 | In-motion versus at-rest is a binding line; they compose as producer and consumer over a shared store |
| **A global priority-ordered queue** | ⛔ **REFUSED — do not re-propose** | Strict ordering *is* the starvation trap, and it reorders Consignments within a pipeline, breaking arrival order |
| **Nested semaphores per tier** | ⛔ **REFUSED** | No global view for the fairness decision, and cross-tier ordering is a deadlock hazard |
| **A separate consignment-generation service** | ⛔ **REFUSED** | Planning stays in the poll cycle; only *admission to execution* is shared |
| **Retiring the ingest run budget** | ⛔ **REFUSED — the plan claimed it, the code refuted it** | It bounds a different *phase*, not the same fact twice |
| **A cap computed from memory ÷ the live concurrency** | ⛔ **REJECTED** (the GAP-4 default divides by a fixed 4) | The divisor is routinely unknown and the ingest path has its own limiter, so any formula is wrong *in exactly the overcommit case it was meant to prevent* |
| **A temporary-directory-size default** | ⛔ **NONE, permanently** | None is defensible without knowing the volume |
| **Capping preview and dry-run connections** | ⛔ **REFUSED, and must stay so** | They run over bounded samples |
| **Read-path connection reuse** | ⛔ **NOT the lever** | Measured: a warm open is 24 ms; no contradicting measurement exists |
| **Route at rest** | ⛔ **REFUSED at two sites** | Route's home is the ingest lane. ⚠ Both refusals used to be *negatives that never told the author route works at all*; both now name its home, and **no test pinned either string** |
| **Decision rules together with route** | ⛔ **REFUSED at runtime** | A rule that actually routed rows keeps the pipeline flat; the admission cannot see rules statically |
| **Multi-schema together with route** | ✅ **ARMS since 2026-09-24** | Each per-schema write walks its own schema's slice (`PipelineLift.scope`); ONE shared `route:` whose predicates must bind in every schema, else arming refuses — [spec-ingest.md](spec-ingest.md) § *Multi-schema route* |
| **A node between projection and write on either ingest lane** | ⛔ **REFUSED on both** | Carrying it means executing it at rest, which is second-stage work; **batch independence is the dividing rule** |
| **Cross-branch all-or-nothing commit** | ⛔ **OUT OF SCOPE deliberately** | One healthy branch stays committed when a sibling fails, and the failed branch retries independently |
| **A versioned reference store per branch** | ⛔ **REFUSED** | One version history across branches is ill-defined |
| **Per-step signals for live position** | ⛔ **REFUSED** | A signal is a durable ledger write inside the run claim — and because signals trigger jobs, a mid-batch signal invites triggered work to fire *while the claim is held* |
| **Periodic persistence of progress** | ⛔ **REFUSED** | Stale by construction, recurring writes for a value that expires in seconds, crash-orphaned rows to clean — and the post-crash question is already answered by the ledgers |
| **Folding intrinsic and inherited file status into one enum** | ⛔ **REFUSED** | A committed file inside a failed Consignment is real and common; folding loses *which files made it* |
| **A rollup cache** | ⛔ **deliberately ABSENT** | Nothing depends on it, read-time aggregation has not been shown too slow, and building it adds the one mutable-looking thing in the system **ahead of evidence** |
| **An on-commit job trigger** | 🔴 **DOES NOT EXIST** | Commit-fired jobs ride the signal bus. ⛔ Do not cite the graph-structure refusal as what keeps job work at rest |
| **Growing the event enum for new facts** | ⛔ **BANNED — emit a signal** | And **per-file facts belong in a ledger row**: a signal per file floods the bus on a large Consignment |
| **A third-party scheduler library** | ⛔ **REJECTED** | The config file is the durable schedule |
| **Inbox lag or pending depth as throttle inputs** | ⛔ **BANNED** | Positive feedback; they stay observability surfaces |
| **Kafka as a second execution model** | ⛔ **REFUSED, never re-opened** | Urgency is a parameter on one node. ⛔ Do not re-file it; a Kafka *collector* is a different, open question |
| **Timeout or cancellation on either engine** | ⛔ **NONE — a recorded gap** | Cancelling a handle un-arms only future fires |
| **Sealing** | **DROPPED, not deferred** 2026-08-30 | The partition-state machinery bought nothing the completeness measure does not |
| **Per-record lineage** | ⛔ **REFUSED / won't do now** | Per-batch ancestry is the accepted grain. ⚠ See §EXE.5.3 item 15 — one board says both |
| **Distributed-by-default** | ⛔ **REFUSED as a default** | ⚠ But planned as Enterprise scope, unscoped — and no document states the distinction (§EXE.5.3 item 16) |
| **A partial archive failing its Consignment** | ⛔ **REFUSED** | The partial verdict is **reporting, never a gate**; it is a Run-level fact, never a manifest member row |
| **Nested archives** | ⛔ **REFUSED** | Depth is pinned at one |
| **Crash-mid-archive re-ingest** | **BY DESIGN** | Relies on idempotent overwrite; revisit only with a measured cost. ⚠ The bounded retry does not cover it — it records a *returned* failure, and a crash returns nothing |
| **Member-level transient classification** | ⛔ **stays as it is** | The catch sites collapse every read failure into a permanent quarantine across four strategies; nothing classifies transient from fatal (§EXE.3.7) |
| **Multi-user relational deployment** | ⛔ **PARKED** 2026-09-06 | Until a multi-operator install exists |
| **Mail delivery reporting success with no channel** | **DELIBERATE** | ⛔ Do not "fix" without an operator call — but know that a scheduled mail job **reads green while delivering nothing** |
| **A built-in with an unsatisfiable grant** | **ACCEPTED** asymmetry | Only the pack and classpath paths refuse; recorded deliberately |
| **The step-scaffold generator for a new kind** | ✅ **BUILT** for pack-hosted Steps — `node tools/scaffold.mjs new step` (isolated classloader, `StepContext`, watchdog; platform-services S2-3) | The original gate stands for a *classpath* node type: it gets no hot deploy, no isolated loader and no watchdog |
| **Third-party lowerable Step kinds** | ⛔ **CLOSED** | Until a fragment guard exists |
| **Reusing the flow-job sink writer for ingest destinations** | ⛔ **NEVER** | It is shaped for one root, with no lineage, and self-registers |

## 7. Verification

### ACQ.6 Acquisition — verification

#### ACQ.6.1 Decision-logic and ledger unit tests — `platform/inspecto-acquire/src/test/java/com/gamma/acquire/`

25 test classes (24 here + `retry/RetryPolicyTest`) plus the `testkit/` connector-factory TCK. The table
lists the core ones; not listed: `AcquisitionLedgerPruneTest`, `DayManifestTest`,
`FileSequenceGapsBucketStartGoldenTest`, `StreamLaneTest`. `SecretResolverTest` is listed with them but lives
in `spi/inspecto-auth-spi`. The acquisition core is deliberately built as pure, injectable-clock decision
logic, so almost every invariant in the spec is provable without a network or a sleep.

| Class | Proves |
|---|---|
| `StabilityGateTest` | growing-file hold, release on quiescence, the `size_checks` gate, per-source isolation, gate shared **per space** |
| `DuplicatePolicyTest` | `Decision{NEW,DUPLICATE,CHANGED}` across `Mode{PATH,METADATA,CHECKSUM,ETAG}` incl. the etag→version→metadata fallback; `OnChange` reprocess/alert flags; `configStringsMapToEnums()` pins the config-string→enum mapping and the null/unknown defaults |
| `AcquisitionLedgerTest` | the ledger contract across in-memory **and** DuckDB backends, pre-etag schema migration, and a failed replace rolling back to the prior fingerprint |
| `AcquisitionLedgerWatermarkTest` | `highWatermark` = max(`lastModified`) per source, in-memory + DB, and the safe no-op default |
| `AcquisitionLedgersTest` | ledgers isolated per space (MDC routing) |
| `ChecksumsTest` | known hash vectors, SHA-256 default + change detection, unknown-algorithm failure |
| `IntegrityCheckerTest` | post-fetch size/etag verification, skip-when-no-size, quoted-etag checksum |
| `GapDetectorTest` | timestamp-template series: hourly hole, day rollover, monthly step, invalid-date skip |
| `GapTrackerTest` | the fire-once/refire-on-reopen state machine, per-source isolation, reset |
| `FileSequenceGapsTest` | the two-token windowed variant: interior hole, trailing hours, truncated tail, zero-padding, malformed template |
| `CircuitBreakerTest` | closed→open→half-open, threshold trip, cooldown, per-source isolation |
| `retry/RetryPolicyTest` | `NONE` runs once, retry-until-success, exhaust-and-rethrow, exponential doubling + clamp, linear backoff |
| `RateLimiterTest` | unlimited short-circuit, burst-then-throttle, refill over elapsed time |
| `IntakeGovernorTest` | the T15 contrast: hot policy apply, overrun halves, floor, hysteresis band, per-pipeline override incl. zero-exempt, hard-cap mode |
| `RetrievalPlannerTest` | the plan choice — backup vs. streamable vs. temp-staging |
| `PostActionTest` | `archive_path` date-token resolution |
| `ConnectionProfileTest` | field/tunnel/options load, secret masking in `toMap`, proxy-block parse+mask, **`everyFieldSurvivesAMapRoundTrip`** (the `base_path`/`basePath` data-loss guard) |
| `ConnectionTesterTest` | reachable/unreachable probe, local-profile no-network shortcut, missing host/port failure |
| `SecretResolverTest` (`spi/inspecto-auth-spi`) | all five forms — `recognisesReferences`, `resolvesSystemPropertyScope` (incl. bare-scope fallback), `missingEnvReferenceResolvesToNull`, `literalIsPassedThrough`, **`fileAndKeystoreSchemesAreRefusedNamingTheEditionWhenNoProviderIsBundled`** (the SEC-07 gate), `aBundledProviderServesTheSchemesItSupports` |
| `ConnectionRegistryTest` | register/find/remove by id, null-registration ignored, per-space isolation |
| `LocalConnectionWorkbenchTest` | probe/explore/sample, **path jailing**, CSV vs. raw-line fallback |
| `LocalFileSystemConnectorTest` | discover/open/fetchTo/post-action + ready-marker readiness |

#### ACQ.6.2 Connector, workbench and proxy tests — `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/`

30 test classes — the per-scheme and workbench tests below, a `*FactoryTckTest` per factory (the
`testkit` contract of §ACQ.6.1), `DbExportConnectorFactoryValidateTest`, `CollectorProcessorRemoteCycleTest` and
the export/egress tests (`ObjectStoreEgressTest`, `ObjectStoreExportJobTest`, `PinnedObjectStoreHttpTest`,
`SafetyPolicyEgressTest`) — plus 4 helpers (the 2 relay fixtures, `ObjectStoreEgressFixture`, `OutputRows`).
⚠ The `com/gamma/connect/notify/*` tests (six classes, two helpers) also live in this module but are a
**notification** subject, not ACQ. The Kafka tests (`KafkaConnectorTest`, `KafkaConnectionWorkbenchTest`,
`KafkaConnectorFactoryTckTest`, `KafkaStreamLaneTest`, `KafkaEgressTest`, `KafkaRealBrokerTest`) moved with the
connector to `providers/inspecto-connectors-kafka/src/test/java/com/gamma/acquire/kafka/`.

- Per-scheme: `SftpConnectorTest` (host-key pinning, discover/fetch/resume, dedup-on-rerun, parallel fetch,
  workbench, SOCKS5 + HTTP-CONNECT tunnelling) · `FtpConnectorTest` (+ proxy, unknown-proxy fail-closed) ·
  `FtpsConnectorTest` (TLS mode parsing, asserts `scheme()=="ftps"`) · `FtpTunnelConnectorTest` (bastion +
  passive-port-range parsing) · `S3ConnectorTest` · `AzureBlobConnectorTest` · `GcsConnectorTest` (each:
  discover w/ pagination + etag, depth bound, fetch, move+delete post-actions) · `KafkaConnectorTest`
  (Kafka module; offset-range discover, **ledger-watermark resume**, NDJSON envelope drain, raw-payload mode,
  non-RETAIN post-action rejection, the **in-flight fence** and its self-release, the lag gauge) ·
  `DbExportWatermarkCycleTest` (real poll cycles: frontier advances, **restart between land and commit**,
  fence across a restart) · `DbExportConnectorTest` (query→CSV, `:watermark` placeholder rewrite,
  incremental export **commits then advances**).
- Auth signing, which is what "SDK-free" rests on: `AwsSigV4Test` (vanilla vector, S3 sha256, RFC3986
  encoding) · `AzureSharedKeyTest` (string-to-sign, `x-ms-` headers, canonicalized resource).
- Workbenches: `S3ConnectionWorkbenchTest` · `AzureBlobConnectionWorkbenchTest` ·
  `GcsConnectionWorkbenchTest` (each an in-process JDK `HttpServer` stub per store) ·
  `DbConnectionWorkbenchTest` (schema/table/column explore, bounded sample + truncation flag) ·
  `KafkaConnectionWorkbenchTest` (Kafka module; topic→partition explore; **sample without touching the ledger
  watermark**).
- Proxy: `DbConnectionsProxyTest` — the JDBC half dialled through both relays at a bare listener, asserting
  the relay was asked for the database; unroutable shapes refused.
- Fixtures (not test classes): `MiniSocks5Relay.java`, `MiniHttpConnectRelay.java` — both multi-connection,
  because FTP opens a *second* passive data connection through the same factory; `MiniHttpConnectRelay`
  additionally records the auth header. 🔴 These relays are the only thing that caught the
  `createSocket()`-vs-`connect()` bypass of the HTTP proxy: the happy path still returned the right file.
- Test infrastructure declared in `providers/inspecto-connectors/pom.xml`: Apache MINA `sshd-core` + `sshd-sftp` and
  Apache `ftpserver-core` (all `<scope>test</scope>`) give **real in-process SSH/SFTP and FTP servers**;
  in the Kafka module, kafka-clients' in-jar `MockConsumer` means the suite needs no broker (only
  `KafkaRealBrokerTest` wants one, and skips without it); the ACQ test resource committed here is
  `providers/inspecto-connectors/src/test/resources/ftps-test-keystore.jks`.
- Slice frontier: `RemoteSliceFrontierRekeyTest` (engine), `RemoteSliceFrontierCommitTest` (inspecto: restart
  and fence against the scheduler's split timers), `DbExportWatermarkCycleTest` (connectors).

#### ACQ.6.3 Unpack — `platform/inspecto-etl/src/test/java/com/gamma/etl/unpack/`

`UnpackStage`, `UnpackLimits`, `UnpackLedger`, `UnpackOrigins` tests; `CompressionTest.inlineVocabularyIsOwnedByPlugins`
(the one-way drift guard); the zip-slip jail pinned with a **re-packed** archive; the `EMPTY` vs `UNREADABLE`
`entriesFound()` split; `LogicalNamesTest` (the one permitted `ConfigSpecs` mirror of the default
`data_extensions`).

#### ACQ.6.4 Control-plane real-HTTP route tests — `inspecto/src/test/java/com/gamma/control/`

Domain-primary (four):

| Class | Routes / gates |
|---|---|
| `ControlApiCollectorsAndConnectionsTest` | `GET /collectors`; `POST/GET/PUT/DELETE /connections[/{id}]` — create, duplicate id → **409**, delete-while-in-use → **409**, `POST /connections` → **503** when the write root is unavailable |
| `ControlApiCollectorNotifyTest` | `GET /collectors`; `POST /collectors/{id}/notify` → **202** + two 404-unknown cases; and `AuditTrail.classify("POST","/collectors/feed-a/notify")` — **this is the test that pins `collector.notified`** |
| `ControlApiConnectionsTest` | `GET /connections`, `GET/PUT/DELETE /connections/{id}` 404-unknown, `POST /connections/{id}/test` |
| `ControlApiConnectionProbeTest` | `POST /connections/{id}/probe`, `GET …/explore`, `GET …/sample` — **400/422/501/404** across local vs. unsupported-remote schemes |

Incidental (the route string is an example for another concern, but they do exercise the path):
`ControlApiVersionedSurfaceTest` (`/collectors` under `/api/v1`, `/api`, `/api/v2`) ·
`ApiContextV1DerivationTest` (v1 prefix derivation over `/collectors` variants) · `AuditTrailTest`
(`PUT /connections/{id}`, `POST /connections/{id}/test` classification) · `ControlApiBundleNewKindsTest`
(`POST /connections`, `GET /connections/{id}` as bundle-import kinds).

#### ACQ.6.5 SPI contracts (`META-INF/services`)

| File | Providers |
|---|---|
| `providers/inspecto-connectors/src/main/resources/META-INF/services/com.gamma.acquire.CollectorConnectorFactory` | the **seven**: `SftpConnectorFactory`, `FtpConnectorFactory`, `FtpsConnectorFactory`, `DbExportConnectorFactory`, `S3ConnectorFactory`, `AzureBlobConnectorFactory`, `GcsConnectorFactory` (`KafkaConnectorFactory` is in `providers/inspecto-connectors-kafka/src/main/resources/META-INF/services/...`) |
| `platform/inspecto-engine/src/main/resources/META-INF/services/com.gamma.acquire.CollectorConnectorFactory` | `com.gamma.inspector.DatasetCollectorConnectorFactory` — the `dataset` scheme, from a different module |
| `providers/inspecto-secrets/src/main/resources/META-INF/services/com.gamma.auth.secrets.SecretsProvider` | `com.gamma.secrets.FileKeystoreSecretsProvider` — the SEC-07 edition seam |
| `inspecto/src/test/resources/META-INF/services/com.gamma.acquire.CollectorConnectorFactory` | `com.gamma.service.FakeRemoteConnectorFactory`, `com.gamma.service.FakeOffsetTailConnectorFactory` (test-only remote schemes) |

`platform/inspecto-engine/src/test/java/com/gamma/inspector/DatasetCollectorConnectorFactoryTest.theFactoryIsServiceLoaderDiscoverable()`
loads the SPI through `ServiceLoader`; so does the factory TCK (`testkit/CollectorConnectorFactoryContract`,
`schemeIsServedByExactlyOneFactory`), which every `*FactoryTckTest` runs.

#### ACQ.6.6 Runnable examples

✅ **One committed example now exercises a non-local connector, and it was DRIVEN live 2026-09-15**
(`COLLECTOR-DATASET-UNPROVEN-1`, closed): `spaces/demo/config/orders/orders_by_region_feed_pipeline.toon` —
`collector: {connector: dataset, dataset: datasets/orders_by_region}` plus the `trigger: {type: event, on: dataset,
from: …}` half, `parsing.frontend: parquet`. On a multi-Space server (`-Dspaces.root=spaces`, Standard modules on
the classpath) `POST /spaces/demo/jobs/orders_by_region_materialize/trigger` → 202 → SUCCESS, 4 rows; the
`DatasetWriteSignal` fired the feed **in the same second** (batch `SUCCESS`, 4 rows, 1 partition, 0 rejected,
180 ms) and the Parquet landed under `spaces/demo/data/orders_by_region_feed/database/`. 🔴 Two things the run
found that no test had: (a) the committed materialize job was **broken** — `measures: "gross,sum"` is not the
measure grammar (`sum(gross)`), so the demo's own materialize had never run; (b) the demo space **does not boot on
the core jar at all** — `ops_analytics_sample_job.toon` declares `type: objects.analytics` (an `inspecto-ops` job
type) and `SpaceManager` SKIPPED the whole space with a WARN (`DEMO-SPACE-PERSONAL-UNBOOTABLE-1`, fixed
2026-09-15: a job of an unregistered type is now skipped at boot, not the Space — see
[jobs](../processor/spec-jobs.md)). ⚠ Before
the Dataset exists the feed's first poll fails loudly (`unknown dataset 'orders_by_region'`) — fail-fast, by
design; the registry component `orders_by_region.toon` is committed so a fresh checkout resolves it at boot.
⚠ Otherwise every runnable committed `*_pipeline.toon` uses `connector: local` (153 occurrences). The two
remote ones are the **`_reference/`** templates, which need external infrastructure and are not runnable
offline: `inspecto/examples/_reference/sftp-collector/orders_pipeline.toon` (`connector: sftp` with
`fetch` + `rate_limit`, `retry`, `stability`, `post_action`) and
`inspecto/examples/_reference/db-export/orders_pipeline.toon` (`connector: db` with `fetch`, `retry`). What is
runnable is the acquisition *semantics* layer over the local connector:

| Example | Exercises |
|---|---|
| `inspecto/examples/05-acquisition/gap-detection/pipeline.toon` | `collector.gap_detection` |
| `inspecto/examples/05-acquisition/dedup-rerun/pipeline.toon` | ⚠ **not** an acquisition key — its dedup is `processing.duplicate_check` |
| `inspecto/examples/05-acquisition/intake-throttle/pipeline.toon` | the per-cycle intake cap, `processing.intake { max_files_per_cycle }` |
| `inspecto/examples/06-serve/sequence-gap/feed_pipeline.toon` | `collector.gap_detection` under the serve runner |
| `inspecto/examples/06-serve/checksum-change/orders_pipeline.toon` | `collector.duplicate { mode: checksum, algorithm: SHA256, on_change: alert }` |
| `inspecto/examples/06-serve/incremental-watermark/feed_pipeline.toon` | `collector.duplicate { mode: metadata }` + `collector.incremental { watermark: last_modified }` |
| `inspecto/examples/07-steps/collect/pipeline.toon` | `connector`, `discovery: poll`, `include[]`, `exclude[]`, `recursive_depth`, `gap_detection` (mirrored at `spaces/default/config/collect_step/collect_step_pipeline.toon`) |
| `spaces/ucc/config/voucher/voucher_pipeline.toon` | the only runnable **`collector.stability`** (the other is in `_reference/sftp-collector`) — `window: 10s`, `size_checks: 2` — and `collector.consignment { max_files, max_bytes }` |

Runners: `inspecto/examples/run-example.ps1` / `.sh` (batch) and `serve-example.ps1` / `.sh` (serve mode —
required for gap/fingerprint/watermark/stability, which need more than one cycle; the `phase2/` two-cycle
protocol and `mtimes.txt`). Self-checking `probes.txt` assertions exist for the three `06-serve/*` examples
above and **not** for any of the three `05-acquisition/*`.

Connection profiles — six committed. Three sit beside the `_reference/` pipelines that bind them
(`_reference/sftp-collector/warehouse_sftp_connection.toon`, `_reference/db-export/orders_db_connection.toon`,
`_reference/webhook-sink/orders_hook_connection.toon`). The other three exercise the Connections API surface
rather than collection (no pipeline references any of them): `spaces/default/config/connections/local_demo_connection.toon`
(`sftp`, `${ENV:DEMO_SFTP_PASSWORD}`; ⚠ `host: 127.0.0.1 port: 8080` reads as a probe fixture aimed at the
control-plane port, not a working SFTP endpoint) · `spaces/demo/config/connections/demo_local_connection.toon`
(`local`, the only one that resolves offline) · `spaces/demo/config/connections/warehouse_sftp_connection.toon`
(`sftp`, `${ENV:WAREHOUSE_SFTP_PASSWORD}`; `spaces/demo/config/README.md` labels it "reference shape").
**No committed `.toon` anywhere contains a `tunnel:` or `proxy:` block** — those shapes live only in
`connectors-runbook.md`, `ConnectionProfileTest`'s inline TOON, and the UI's `connection-form.dialog.ts`.

#### ACQ.6.7 Guards

- `tools/check-secrets.mjs` — the ACQ-relevant guard: it exists so a credential cannot be reintroduced as a
  literal, and it explicitly blesses `${ENV:…}` as *the* sanctioned way not to hold a secret. Two modes,
  because tree-scan alone hands out a false green when a secret is committed and moved one commit later;
  `.githooks/pre-push` runs both. Wired into `ci.yml`.
- `tools/check-vocabulary.mjs` — rule `source-acquisition-entity` enforces Collector over Source. <!-- vocab-allow: names the guard rule by id -->
  ✅ The path-keyed `DOC_ALLOW` waiver for the old `integrations.md` H1 was deleted 2026-09-08 when the
  connector runbook was split out; the guard's self-retirement rule would otherwise have failed the build. The
  guard's comments still record the identical error class fixed once before — a documented `GET /sources`
  that is really `GET /collectors`.
- `tools/check-doc-links.mjs` — wired into `.githooks/pre-push` + `ci.yml`; any ACQ file move must keep the
  link baseline at zero dangling.
- `tools/check-coverage.mjs` — ⚠ the floors are **repo-wide, not per-module** (its header comment), so there is no
  `inspecto-acquire` floor to cite; do not claim one.

#### ACQ.6.8 Named coverage gaps (verified absent, not assumed)

1. 🔴 **No test asserts the registered-scheme set.** The number seven is only the line count of the
   `inspecto-connectors` services file; `FtpsConnectorTest` and
   `DatasetCollectorConnectorFactoryTest.theFactoryIsServiceLoaderDiscoverable` each assert a single scheme,
   and the factory TCK checks that a scheme has *at most* one factory, not that it is registered. Nothing
   counts or lists them together, so a dropped `META-INF/services` line would ship silently — the exact
   failure mode of the palette-fallback incident.
2. 🔴 **No test feeds a raw `ssh-rsa AAAA…` line into `host_key`.** `SftpConnectorTest` covers
   `wrongHostKeyFingerprintRejectsConnect()` (a bogus colon-hex fingerprint) and
   `strictHostKeyWithoutAnyPinRefusesConnect()`. The rejection of a raw key line follows from
   `HostKeyPolicy` handing the string to sshj's fingerprint verifier — it is code-supported by
   construction, but **not covered by a test with that input**, so state it as a design property, never as
   a tested one.
3. The three `05-acquisition/*` examples (`gap-detection`, `dedup-rerun`, `intake-throttle`) have no
   `probes.txt`, so they are demonstrable but not self-checking.
4. Features needing external infra (SFTP, a database, an https receiver, a DuckLake catalog) now ship as
   `inspecto/examples/_reference/` templates (`inspecto/examples/README.md`, *Reference templates*); they are
   reference shapes, not runnable offline, so no committed example *drives* a remote fetch (§ACQ.6.6).
5. `.Z` has no round-trip test; xz / zstd have no codec to test (`BACKLOG.md` §3).

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
| **The Incident hop after a commit has no harness** | [§ACQ.4](#acq4-acquisition--not-built) |

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

### EXE.8 Pipeline execution — verification

**What is actually enforced today**

* **The event-trigger filters** — `PipelineSchedulerEventTriggerTest` (2026-09-09, closing the last of
  `SPEC-NOPROOF-1`): a **non-SUCCESS** commit triggers nothing, a trigger naming itself does not
  re-fire on its own commit (the self-loop guard, whose absence turns one commit into an unbounded run
  loop), a paused pipeline stays paused for EVENT triggers, `from` tolerates case and a `flows/` prefix,
  and a `on: commit` pipeline ignores a Dataset write. Mutation-proven. ➕ **2026-09-15**: the `on: dataset` side now has the same three-way cover — the producing pipeline is suppressed, another pipeline’s write still fires it, and an **unowned** write (a cron/manual job) still fires it. ⛔ The third is not padding: reading “no owning pipeline” as “suppress” would disable materialize-then-trigger entirely, silently. 🔴 **The board row that asked for
  this said the scheduler "has no test class at all" — literally true, materially misleading**:
  `CollectorServiceTriggerTest` already proved its loop semantics end-to-end (DEFAULT_POLL, interval,
  cron, manual, the event trigger, the `on: dataset` fence) under a collaborator's name. ⚠ Grep the
  behaviour, not the identifier. ⛔ `dueThisTick`/`cronDue` stay proven from the outside: they are private
  and `runOne` calls real ELT, so reaching them directly would need reflection or a production change.

* **A lane-parity test that diffs flat against graph writes** — parity is proven, not asserted. The
  strongest control in this area.
* **A stage-two lift test** covering the happy path and the route refusal.
* **A bounded-retry test**, and an orphan-audit test.
* **Ingest lock tests** pinning that the per-pipeline guard is shared as one instance and that the hand-off
  is off-thread.
* **Scheduler-settings tests** pinning configuration-over-flag precedence.
* **A purge test asserting the ledger outlives the record** — including its own purge entry.
* **An operations-zone test written to be falsified on purpose.**

**Falsify, don't read — six probes**

1. **Grep for the class the backlog names as Row 15's blocker.** Zero hits falsifies the row; then grep the
   real runner's `run(` for its production caller.
2. **Open the maintenance job form on a Personal build.** Four offered tasks will be refused and seven
   working ones are missing.
3. **Restart the server, then replay a completed run.** A 404 is the §EXE.2.3 bound, not a bug in the route.
4. **Start with nothing installed and read the effective memory limit.** Nothing is the answer six docs
   deny.
5. **Read the concurrency javadoc against the constant twelve lines below it.** They disagree.
6. **Author a maintenance job by copying the post-sync page's job type id.** The underscore makes it an
   unknown type.

**A capability-pointer check over this file** — it is **`tools/check-doc-citations.mjs`, committed 2026-09-09**
and wired into `ci.yml` and `.githooks/pre-push`. It checks this file's backticked symbols
against `git ls-files` and the module tree — and in this area, that check would have caught item 7 of §EXE.5.3.

## 8. References

### ACQ.7 Acquisition — references

| Mechanism | Owning file |
|---|---|
| The original requirement, the engine as built, the Unpack stage | [spec-acquisition.md](spec-acquisition.md) |
| Poll cycle and phases A–F summary, the Collectors module page, multi-location ingest | [architecture-elt.md](architecture-elt.md) |
| Screens and the Collector configuration surface | [user-guide-elt.md](user-guide-elt.md) |
| Connector SPI, remote schemes, profiles, secrets, workbench, proxy chain | [connectors/spec-connectors.md](../connectors/spec-connectors.md) |
| Operator runbook: profile YAML, host-key table, FTPS, bastion, db-export | [connectors/admin-guide-connectors-runbook.md](../connectors/admin-guide-connectors-runbook.md) |
| Module boundary + dependency confinement | [connectors/architecture.md](../connectors/architecture-connectors.md) |
| What happens after the inbox | [§1.1](#11-ingestion-ing) ingestion, [§1.2](#12-pipeline-authoring-pip-1) authoring, [§EXE.1](#exe1-pipeline-execution-pip-2--pip-7) execution (this file) |

### Ingestion, authoring and execution — references

⚠ **Cite the symbol, not the line** — executor line citations in this area were wrong every time.

| Concern | Where it lives | Note |
|---|---|---|
| The type vocabulary | `BuiltinNodeType` (**49**<!--count:node-types--> constants) | ⚠ Counted 20/28/20 in three docs |
| Published config shapes | `NodeAttributes` + its committed contract | **34**<!--count:node-types-with-attributes--> of the built-ins, by design |
| The authoring round trip | `PipelineEditable.toMap` / `.lower` | [Pipeline spec — model](spec-pipeline-model.md) |
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
| The editor, the panes, the palette, unmodelled config, the transform pane | [user-guide-elt.md](user-guide-elt.md) | |
| Identity & lifecycle | `PipelineSettingsRoutes`, `PipelineRenameRoutes` | [Pipeline identity](../processor/spec-pipeline-identity.md) |
| The backend graph model | [architecture](architecture-elt.md) · [Pipeline spec — model](spec-pipeline-model.md) | |
| The Step catalog | [step-catalog](spec-step-catalog.md) | counts agree with code |
| The config contract | [pipeline-config-keys](../config/spec-pipeline-config-keys.md) | 🔴 the `fields[]` contradiction |
| Pipeline execution (PIP-2..7) | [§EXE.1](#exe1-pipeline-execution-pip-2--pip-7) – [§EXE.8](#exe8-pipeline-execution--verification) (this file) | formerly the engine hub's plan |
| The archived active plans | `plans-archive/pipeline-spec.md`, `plans-archive/pipeline-waves-drain-plan.md` (archived 2026-09-10) | D1–D10 are §4.3 here and the [Pipeline spec — model](spec-pipeline-model.md); the plans are provenance |

## Parked: parser field tiers interview kit (D13)

Folded in 2026-11-18 (`DOCS-ELT-CONSOLIDATE-1`) from `plan-parser-field-tiers-interview.md`; its content is
unchanged below. Board row of record: see §5.1 (*D13 parser field tiers*).

> ⏸ **PARKED 2026-10-22 — design only, demand-gated; not in flight.** To resume: move it back to docs/superpower/ and rank its BACKLOG row.
>
> *Status before parking:* ✅ **BACK IN THE ACTIVE TIER 2026-09-16 — the gate has fired and this kit is being run.**
> It was archived 2026-09-09 as an unexecuted research instrument; a real onboarding user is now
> available, so the work is in flight and the plan lives here again (docs lifecycle tier 2). It returns
> to `plans-archive/` when the session has been run and its deliverable landed.
>
> ✅ **RE-GROUNDED 2026-09-16 against today's UI**, as the board row demanded before scheduling. The
> drift was worse than "inventory and task script": the kit's own **premise** and its **second question**
> were both false, and half its deliverable was impossible. Every correction is marked 🔴 **RE-GROUNDED**
> in place below. What did NOT drift: every per-lane **tier** assignment, the session protocol, the
> capture marks (except **F**), the plugin-lane caveat and the three deliberate no-default fields.
>
> ✅ **The load-bearing half stays in the current tier**, not here: the two questions, the
> **pre-agreed analysis rule**, the capture marks and the deliverable are in
> [`okf/product/module/config/user-guide-grammar-config.md`](../config/user-guide-grammar-config.md)
> §"The D13 field-tier session". ⛔ **That rule's entire evidentiary force comes from being agreed
> BEFORE the session — re-grounding a refuted PREMISE is not re-deriving the RULE, and the rule below is
> untouched.**

**Kit:** Parser field tiers — interview #2 observation-session kit (D13)

**Status:** READY TO RUN — awaiting a scheduled session with a real onboarding user. ·
**Prepared:** 2026-08-28 · **Owner decision of record:** D13 (2026-07-25) parked the tier
assignment until "a real onboarding-observation session (interview #2); explicitly NOT an
engineering guess". This kit is that session, prepared: protocol, tasks, per-field capture
sheets, and the analysis rule that converts observations into tier assignments.
**Backlog row of record:** `BACKLOG.md` §7 *Parser required-vs-advanced field tiers*.

### What the session must decide (two questions, not one)

1. **Tier placement** — for every parser/grammar field. Today's placements are engineering guesses
   recorded in `parsing-attributes.ts` `tier:` values.
   🔴 **RE-GROUNDED 2026-09-16 — this question's framing was FALSE on the pane the session observes.**
   It used to read *"`required` (top, always visible) vs `optional` (second disclosure) vs `advanced`
   (collapsed)"*. On the Parse pane **tier no longer controls disclosure at all — only ORDER**: the pane
   renders `<inspecto-schema-form [flat]="true">` per section
   (`grammar-editor.component.html`), and *"every tier renders in one single column, in tier
   order"* (`schema-form.component.ts`). **All three tiers are always visible**; the only
   collapsing is the section accordion. The tiered, non-flat path survives in job/alert dialogs only.
   ⇒ the session still decides ORDER and grouping, and ⚠ the analysis rule's *"advanced tier: never
   touched"* bucket now scores a field the participant **can always see** — score it as written, and
   record that fact against the result rather than adjusting the rule.
2. **What "required" means** — 🔴 **RESTATED 2026-09-16; the 2026-08-28 grounding was REFUTED.**
   It claimed *"every `tier:'required'` field carries `required: false` validators … the lone hard
   default is `transform.route`'s `mode`"*. The derivation is
   `return spec.required ?? spec.tier === 'required';` (`attribute-spec.ts`) — so an **omitted**
   `required:` key means **validator-ENFORCED**, and several fields omit it:
   **`text_regex__pattern`** (`parsing-attributes.ts`) among the parser fields, and **nine node
   attributes** (`transform.sql.sql`, `transform.route.mode`, `transform.dedup.keys`,
   `transform.summarize.group_by`/`measures`, `transform.join.reference`/`on`,
   `transform.lookup.column`/`mappings`). ⇒ **"required" today means enforced for SOME fields and
   visual-only for others, with no stated principle** — which is a sharper question than the original,
   not a softer one. The session must observe whether users submit with a top-tier field untouched
   **and get a bad outcome**; that, not intuition, decides whether the tier's meaning should be made
   uniform, and in which direction.
   ⚠ **Score both kinds.** A field that is already enforced cannot produce an **M** (the validator stops
   the submit) — so an enforced field's evidence is **S**/**A** at the point of refusal, and an M on an
   unenforced top-tier field is the evidence for adding one.

### Session protocol

- **Participant:** one real onboarding user (data-ops operator unfamiliar with this product's
  editor; NOT a teammate who built it). 60–90 min, screen-shared or in person.
- **Environment:** a fresh bundle (`inspecto-deploy.zip`), `seed-inbox` run so all four
  format-example feeds are present (csv / fixedwidth / excel / json — the pack under
  `spaces/default/config/`), server via `serve.sh`. ⚠ Do NOT pre-open any dialog for them.
- **Facilitator rule:** think-aloud, no steering. Answer a direct question only after logging
  it as an **ASKED** mark (the ask itself is data).
- **Tasks** (each starts from the Pipelines list, ends at a green test run —
  Build → Test is the journey of record, `okf/product/module/elt/spec-pipeline-execution.md`):
  1. Onboard the **csv** sample (delimited lane — the richest field set).
     🔴 **RE-GROUNDED: "4 tabs" is wrong.** The pane shows **three collapsible sections**, the first
     expanded (`grammar-editor.component.ts`), labelled *"How the file is written" / "How values
     are understood" / "When a row looks wrong"* (`parsing-attributes.ts`). The only real tabs
     left are **Sample | Parsed** (`grammar-editor.component.html`) — a different control the kit
     never mentioned. Per-format first sections: xlsx *"Sheet & range"*, json *"Format & records"*,
     fixedwidth *"Record layout"*.
  2. Onboard the **fixedwidth** sample (slice table + robustness defaults differ).
  3. Onboard the **excel** sample (sheet/range/header — no encoding/compression).
  4. Onboard the **json** sample (format select drives `dependsOn` visibility).
  5. (Stretch) point the csv feed at a *deliberately dirty* copy (a junk header line +
     one short row) — this is what exercises the *"When a row looks wrong"* section for real.
     ⚠ **Confound to note, not remove:** that section now carries an inline `inspecto-alert`
     (`grammar-editor.component.html`) telling the user rejected rows land in the rejects
     ledger. It may itself change behaviour; log whether the participant reads it.
- **Recording:** one capture sheet per lane (below); mark each field the first time each
  applies. 🔴 **RE-GROUNDED: there are no tab switches to timestamp.** The equivalent observable events
  are **section open/close** and the per-section **"N changed" badge**
  (`grammar-editor.component.html`). Note every hesitation > ~5 s on a visible field.
  ⚠ **The pane's dominant surface has no capture row and needs one:** the single *"Columns that come
  out"* table (`schema-fields-editor.component.html`) — Use · # · Name · Type · Sample value · Also
  known as, plus a search box and a *"Detect column types"* property. Much of a 60–90 min session will
  be spent there; the column-metadata grid left this pane (D2 → Transform).

### Capture sheet — marks

Per field: **T** touched (changed the value) · **D** left at default and outcome fine ·
**S** stumbled (hesitation, wrong value then corrected, or visible confusion) ·
**A** asked the facilitator · **M** missed-but-needed (left untouched and the test run
failed/was wrong because of it) · **F** found-late (needed it, looked in the wrong **section** first).
🔴 **RE-GROUNDED: "tab" → "section".** `AttributeSpec.tab` was renamed `.section` (frontend-only) in
`d012f721`; `grammarTabsFor` still exists under that name but now returns the SECTION shell. The mark
is unchanged in meaning.

### Analysis rule (how marks become tiers — agreed BEFORE the session so the data decides)

- **required tier:** any field marked **M** by the participant, or **T** in ≥3 of 4 lanes'
  happy paths. If a required-tier field is ever **M**, that is the evidence for adding a real
  validator (question 2) — one observation suffices to file it; two decide it.
- **optional tier:** **T** or **S** only on the dirty-data task (task 5), or lane-specific
  touches (e.g. `xlsx__range`).
- **advanced tier:** never touched in any task, and no **A** — regardless of what an engineer
  thinks its importance is.
- A field marked **F** stays in its evidence-assigned tier but files a *section placement* note —
  section membership (`grammarTabsFor`) is a separate, cheaper fix than tiering.

### Field inventory (the served truth to score against)

Grounded 2026-08-28 from `parsing-attributes.ts` (the five built-in lanes; UI-owned) and
`node-attributes.ts` (fallback mirror of `GET /pipelines/node-types`). ⚠ For plugin lanes
(ASN.1 etc.) the form renders from the **served** `GET /parsers` `grammarSchema` — score those
against the live payload, not any file. ⚠ Three fields deliberately have **no default**
(`delimited__strict_mode`, `delimited__engine`, `xlsx__stop_at_empty`): a spec default would
materialize into stored grammar copies on save — the session must not "fix" that.

#### Delimited — sections: *How the file is written* / *How values are understood* / *When a row looks wrong*

🔴 **RE-GROUNDED 2026-09-16.** No tier changed. What changed: the `files` section was dissolved, one
field was **deleted**, one field the kit never listed is present on **every** lane, the robustness keys
are `delimited__*`-prefixed (the kit wrote them bare), and **nine defaults** were wrong.

| Field | Today's tier | Default | Label the participant sees |
|---|---|---|---|
| `delimited__delimiter` | required | `,` | Column separator |
| `delimited__has_header` | required | true | First row is the header |
| `delimited__quote` | optional | `"` | Text quote |
| `delimited__escape` / `comment` | optional | — | / Ignore lines starting with |
| `delimited__skip_header_lines` / `skip_junk_lines` / `skip_tail_lines` / `skip_tail_columns` | optional | `0` (all four) | Skip lines at the top / Skip unreadable lines at the top (up to) / Skip lines at the bottom / Drop extra columns on the right |
| `encoding` (shared key) | advanced | `utf-8` | — |
| `source_timezone` | optional | — | "Source time zone" (~418 options) <!-- vocab-allow: quotes the label the participant actually sees on screen --> |
| `delimited__date_formats` / `timestamp_formats` / `null_strings` | optional | — | … / Words that mean "no value" |
| `delimited__strict_mode` / `delimited__engine` | optional | *(none, deliberate)* | Strict CSV rules (RFC-4180) / Reader |
| `delimited__ignore_errors` / `__null_padding` / `__store_rejects` | optional | `true` / false / `true` | Rows that cannot be read go to the review bin / Fill missing columns with empty / Keep rejected rows for review |
| `delimited__rejects_table` / `__rejects_scan` / `__rejects_limit` | advanced | `reject_errors` / `reject_scans` / — | Review bin table / / Stop keeping bad rows after |
| `include_prefixes` / `include_regex` / `exclude_prefixes` / `exclude_regex` / `filter_target_column` | advanced | — | — |
| `compression` | optional | `auto` | Compressed file |

🔴 **`where` (pre-parse SQL) is GONE** — deleted by `d012f721` (D3: *"the row filter property is gone …
`transform.filter` is the Step"*). Do not score it.
🔴 **`source_timezone` is on ALL FOUR scripted lanes** (`parsing-attributes.ts, :429, :546, :658`)
and the kit never mentioned it — the largest single inventory gap. A session that never shows it
observes nothing about it.
⚠ **Where the dissolved `files` section's content went:** `encoding` and `compression` moved into the
FIRST section; the collection pointer is now **read-only text** on Parse (*"Reads: `<value>` — from the
`<source>` collector step"*) and is edited on the **Collector** pane; partitioning moved to the **Sink**
pane. A script step sending a participant to a *"Files & MetaData"* tab sends them nowhere.

#### Fixedwidth
`delimited__has_header` (required, true) · slice table (own editor, not a spec field) ·
`fixedwidth__min_record_length` (optional) · `fixedwidth__trim` (BOTH) · shared date/timestamp
formats · the `delimited__*` robustness set with `__null_padding` defaulting **true** (same key,
different engine default — help text carries the difference) · `encoding`/`compression` (advanced)
· 🔴 **`source_timezone` (optional)** — add to the sheet.

#### Excel
`xlsx__sheet` (required, none = first sheet) · `xlsx__header` (required, true) · `xlsx__range`
(optional, A1-notation validated) · `xlsx__normalize_names` (optional) · shared date/timestamp
formats · `xlsx__stop_at_empty` (optional, no default — deliberate) · `xlsx__ignore_errors`
(optional) · 🔴 **`source_timezone` (optional)** — add to the sheet. No encoding/compression.

#### JSON
`json__format` (required, `newline`, label *"Document shape"*) · `json__records_path` (optional, `$`,
hidden on newline) · `delimited__skip_header_lines` (advanced) · shared date/timestamp formats ·
`json__ignore_errors` (optional, auto-format only) · `json__maximum_object_size` (advanced,
hidden on newline) · `compression` (advanced) · 🔴 **`source_timezone` (optional)** — add to the sheet.

#### text_regex (flat — its specs declare no `section`, so no accordion renders)
`text_regex__pattern` (required, no default, named capture groups) ·
`delimited__skip_header_lines` (advanced) · the `delimited__*` robustness set (padding true) ·
`encoding` (advanced).

🔴 **RE-GROUNDED — and this lane is the sharpest gap in the whole kit.** `text_regex__pattern` is the
ONE parser field that is genuinely **validator-enforced** (it omits `required:`, so
`spec.required ?? spec.tier === 'required'` resolves true) — i.e. it is the only lane that can show the
participant what question 2 is actually about. ⛔ **And there is no `text_regex` sample pack under
`spaces/default/config/`**, so the four-task script never reaches it. Either add a fifth sample before
the session, or record explicitly that question 2 was observed on node attributes only — do not let the
gap pass unnamed, or the session will answer question 2 from the lanes where nothing is enforced.

### Deliverable of the session

One PR: updated `tier:` values in `parsing-attributes.ts`, each change annotated with the observation
that earned it; plus a decision note on question 2 into `okf/product/module/config/user-guide-grammar-config.md`.
Then this plan returns to `plans-archive/` per the docs lifecycle and the board row closes.

🔴 **RE-GROUNDED: the `node-attributes.ts` half is IMPOSSIBLE and is struck.** Since NODE-TYPE-MIRRORS-1
(2026-09-15) that file is ~35 lines importing a generated contract
(`app/inspecto/contracts/node-attributes.contract.json`) and holds **zero** `tier:` entries — node tiers
are authored in **Java** (`NodeAttributes.java`) and regenerated into the JSON. A PR editing
`node-attributes.ts` would change nothing at all. ⇒ if the session reaches node config, the change lands
in `NodeAttributes.java` and the contract is regenerated; ⛔ do not hand-edit the JSON either.
