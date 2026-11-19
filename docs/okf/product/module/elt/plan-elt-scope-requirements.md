---
type: Capability
title: Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan — Purpose, scope and requirements of record
description: Part 1 of 7 of Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan: Purpose & scope; Requirements of record
status: current
tags: [acq, ing, pip, capability, acquisition, connections, collectors, connectors, ledger, ingestion, parsing, consignment, grammar, parser-plugin, quarantine, expectation, pipeline, authoring, step-catalog, execution, scheduler, jobs, plan]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
verified: 2026-11-20 last-edit
code: platform/inspecto-etl
---

# Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan — Purpose, scope and requirements of record

Part 1 of 7 of [Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan](plan-elt.md) · next: [Where the mechanism lives, and decisions](plan-elt-mechanism-decisions.md).

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
[multi-location ingest](architecture-elt-acquisition-parsing.md#multi-location-ingest--the-composition-pattern), which is a `PIP` concern.

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
[`spec-editions-step-processors.md`](../../layer/platform/spec-editions-step-processors.md) (the `SP-ACQ-*` rows) is authoritative for the
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
as the failure ([spec](spec-acquisition-connectors-control.md#connection-profiles-secrets-host-keys-tunnels-and-proxies)).

🔴 **A requirement is not delivered until it is *reachable*.** For 85 days the remote connectors were
build-available and deploy-absent — no bundle shipped `inspecto-connectors` — until CONNECTORS-BUNDLE-1
(2026-09-07). "SHIPPED" against a module no bundle carries is the most expensive kind of true statement
([spec](spec-acquisition-connectors-control.md#the-connector-spi-and-the-resolvable-schemes)).

### 2.1 Ingestion

Six requirements from `REQUIREMENTS.md` §3.2 (that section was stripped to an index on 2026-09-09 — this file
is their only home), all recorded shipped. **Two describe a narrower build than exists, and one describes a
broader one.** ⚠ **`EDITIONS.md`'s feature × edition matrix is authoritative for the Edition column**; this
table mirrors it.

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| `ING-1` | Stage-1 M..N multiplexer: parse → transform → partition → commit, batch-atomic | Must | ✅ SHIPPED | All |
| `ING-2` | Format frontends: delimited grammar, fixed-width (text + binary), plugin `StreamingFileIngester` SPI | Must | ✅ SHIPPED — ⚠ **understated**: the built set is **eight formats** | All |
| `ING-3` | Compressed input streaming (gzip / bz2 / zip) | Must | ✅ SHIPPED — **superseded in shape** by the Unpack stage (2026-08-23): streams *and* Archives, at the Collector, with caps and a verdict ([acquisition spec](spec-acquisition-unpack.md)) | All |
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
- ✅ **`ING-6` has a product surface**: `ExpectationRoutes` serves list / evaluate /
  evaluate-one / create / update / delete, and the SPA's Expectations pane calls it.
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

## Changes

- 2026-11-15: `ING-6` corrected — it has a product surface; the 2026-09 "zero occurrences" search was a false negative.
