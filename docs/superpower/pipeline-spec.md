# The Pipeline — one consolidated specification

**Status:** working document for a redesign (2026-08-30). **This is the single place the Pipeline is
described.** It is written to be changed: the intent is to rewrite the subsystem from here.

**What this replaces.** Pipeline knowledge was spread across ~20 files and >500 KB, and no one of them
told you what a Pipeline *is*. This document states the whole subsystem as it stands today. The deep
files stay on disk as evidence and are mapped in §14; ⚠ **they are not maintained as a parallel truth
— when this document and one of them disagree, fix one of them, do not silently diverge.**

**How to read it.** §1–§4 are the authored surface. §5–§6 are the model. §7 is what actually runs.
§8–§9 are the seams. **§10 is the honest list of what is broken, missing, or contradictory**, and
**§11 is the proposed fix**, **§12 turns the gaps into work**, and **§13 is the interview** —
the decisions to take before implementing.

🔴 **Every claim here was checked against the code, not against the older docs.** Where the code and a
previous doc disagreed, the code won and the disagreement is recorded.

---

## 0. Read this before designing anything

🔴 **A redesign of this subsystem already exists, is approved, and is mostly shipped.**
`elt-final-amendment-plan.md` (v1.0 2026-08-05, amended v1.1) is exactly the rewrite one would reach
for — and it was motivated by the same complaint that prompts this document. Its target shape collapses
today's flat ~200-key config into **one authoring shape**:

```toon
trigger:    { … }                                   # one field, four shapes
steps:      [ collect, parse, map, dedup, sink ]    # seven verbs
guarantees: { file_dedup, backup, quarantine, markers, gap_watch, retention }
```

…and unifies what are today **three separate config types** — `*_pipeline.toon`, `*_enrich.toon`, and a
maintenance "materialize" task — into *"one concept for the user. Three execution paths for the engine
— unchanged."* Its stated premise about Guarantees is the design principle worth keeping:

> *"Housekeeping is runtime-guaranteed, not designer-wired… If a designer can forget to wire the status
> sink, they will."*

**Status:** Phases 0–5 complete (vocabulary, compiler, control plane/UI, the Guarantees fold, per-Step
park/drain). Phase 6 slices A–C2 shipped 2026-08-29 (narrow admission, multi-destination fan-out,
several writes per batch, versioned reference store). **What remains** is Phase 6's *deletion half* —
retiring the legacy read path, deliberately release-gated to a major bump so there is no permanent dual
format — and **Phase 7, the `Batch` → `Consignment` rename** (517 files, 39 `@PublicApi` types),
sequenced last for blast radius. ⚠ I could not confirm a Phase 7 SHIPPED marker; treat it as not done.

⛔ **So the first design decision is not "what should the Pipeline look like" — it is "do we finish the
approved amendment, or replace it?"** Starting a fresh design without answering that re-opens
settled ground and strands ~90% of a shipped migration. Everything in §10 below should be read as
"what the amendment has not yet reached", not as an unexplored field.

---

## 1. Vocabulary (binding)

`docs/GLOSSARY.md` is the source of truth for these words; they are repeated here because a redesign
that drifts on vocabulary re-creates the confusion this document exists to end.

| Word | Means |
|---|---|
| **Pipeline** | A named, authored **DAG of Steps** that turns raw source files into clean, partitioned Tables. Its `wiring` *is* its graph. ⛔ never "Flow". |
| **Step** | One unit of work: *Consignment in → Consignment out (+ rejects)*. ⛔ *Node* is the internal word; **Step** is user-facing. |
| **Run** | One execution of an Executable. |
| **Consignment** | A set of one or more files processed together as one unit of work. |
| **File** | A single collected file, with its own status. |
| **Collector** | The acquisition entity. ⛔ never "Source". |
| **Job** | An at-rest Executable — the consumer of what a Pipeline landed. |
| **Guarantee** | A property the runtime honours regardless of chain shape. **Never a Step.** |

**The containment rule:** **Run ⊇ Consignment ⊇ File**, each with its own status. Identity is
`(consignment_id, run_id)` — the Run is the *attempt*, so a reprocess is a new Run over the same
Consignment.

🔴 **A live vocabulary split a rewrite must decide about.** *Consignment* replaced *Batch* as the
entity name on 2026-08-03, and **the rename was never rolled out — the code and the HTTP API still say
`Batch`** (`Batch`, `batchId`, `BatchProcessor`, `/runs/{n}/batches`). The word *batch* legitimately
survives as an ordinary grouping adjective (`batch.max_files`). So today one word means two things in
two layers. Either finish the rename or retire it; leaving it is what makes the codebase hard to read.

⚠ **Guarantees are not Steps** (`file_dedup`, `backup`, `quarantine` (always on), `markers`,
`gap_watch`, `retention`). The dedup boundary is an operator decision: **file** dedup ("seen this
file?") is the Guarantee; **record** dedup (business key + winner policy) is the `dedup` **Step**.

⛔ **A Pipeline is in-motion; a Job is at-rest, and a Job is NOT a Step.** A Pipeline cannot nest one.
They compose as producer/consumer over a shared store — a Job fires on `on_commit`, or binds by store
name to a `sink.view`. No `job` node type has ever existed, and this is deliberate.

---

## 2. What a Pipeline is, structurally

One `*_pipeline.toon` file, plus companions it references, plus the data directories it owns.

```
spaces/<space>/config/orders/
  orders_pipeline.toon        the Pipeline
  orders_schema.toon          its columns and types
  orders_mapping.csv          its column rules        (optional)
  orders_enrich.toon          an at-rest recompute    (optional, names the pipeline itself)
spaces/<space>/config/jobs/
  orders_job.toon             a cron/event Job        (optional, names the pipeline itself)
spaces/<space>/data/orders/
  database/ backup/ temp/ errors/ quarantine/ markers/ status/ logs/
```

⚠ **The filename suffixes are load-bearing.** `_pipeline.toon` and `_enrich.toon` are how the boot
scanner finds configs (`ConfigFileSupport.fileBase`). Rename the file and the config becomes invisible.

⚠ **A Pipeline is addressed by NAME; its file may live anywhere under the write root.** Every sample
here sits in `config/<name>/`. Resolution is registry-first, falling back to the write-root convention
(`ConfigFileSupport.resolveRegisteredConfigFile`). *(This was a defect until 2026-08-30: routes resolved
only against the write root, so delete/patch/read 404'd for every pipeline in a subdirectory.)*

⚠ **`name` vs `id`.** `name` is the display label; `id` is the stable identity. With no explicit `id`,
identity is *derived* from the name — so renaming without one is a **migration**, not an edit: ~140
call sites key on it, including the commit log and the acquisition ledger.

---

## 3. The configuration file

Worked example — `spaces/demo/config/orders/orders_pipeline.toon`:

```toon
name: orders
active: true
version: 1

dirs:
  poll:       spaces/demo/data/inbox/orders
  database:   spaces/demo/data/orders/database
  backup:     spaces/demo/data/orders/backup
  temp:       spaces/demo/data/orders/temp
  errors:     spaces/demo/data/orders/errors
  quarantine: spaces/demo/data/orders/quarantine
  markers:    spaces/demo/data/orders/markers
  status_dir: spaces/demo/data/orders/status
  log_dir:    spaces/demo/data/orders/logs

output:
  format: PARQUET
  compression: snappy

processing:
  threads: 2
  file_pattern: "glob:**/*.csv"
  duplicate_check: { enabled: true, marker_extension: .processed, retention_days: 30 }
  schema_file: spaces/demo/config/orders/orders_schema.toon
  csv_settings: { delimiter: ",", date_formats[1]: "%Y-%m-%d" }

output_store: rollup

steps[1]:
  - filter:
      where: "STATUS = 'SHIPPED'"

collector:
  gap_detection: { enabled: true, sequence: "ORDERS_{yyyyMMdd}" }
```

| Section | Configures | Required |
|---|---|---|
| `name` · `id` · `active` · `version` | identity; `active` arms the Collector | `name` |
| `trigger` | how a run starts — absent ⇒ the global poll cycle | no |
| `dirs.*` | the managed directories | `poll`, `database` |
| `collector` | acquisition — gap detection, duplicate check | no |
| `parsing` | the format frontend and its grammar | no |
| `processing` | threads · `file_pattern` · `schema_file` · `csv_settings` · `map` · `batch.*` · `unpack.*` · `duckdb.*` | no |
| `steps[]` | the ordered middle of the chain | no |
| `output` / `sinks[]` | format, compression, `filename_column`; `sinks[]` for several destinations | no |
| `output_store` | the resting store the **at-rest Stage-2 chain** writes | no — but see below |

⚠ **Only `name`, `dirs.poll`, `dirs.database` are spec-required.**

### 🔴 Two authorities read this file, and they disagree

The config is read by **`ConfigSpecs.pipeline()`** (the declarative spec the UI renders, the AI drafts
against, and the save gate validates) and by **`PipelineConfigParser`** (the engine loader that actually
runs it). **They are not the same surface.** Keys the engine reads that the spec does not declare:

| Missing from the spec | Consequence |
|---|---|
| the **entire `parsing:` block** — the design-of-record parsing surface | the UI and validator cannot describe or gate it; only the parser knows it exists. Cross-field *rules* reference `parsing.source_timezone` and `parsing.delimited.date_formats` **with no matching field declared** |
| `dirs.errors` · `dirs.quarantine` · `dirs.markers` · `dirs.log_dir` | four real directories the engine uses, invisible to validation |
| `output_store` · `id` | `output_store` is the Stage-2 arming condition (below); `id` is the stable identity |
| `route:` · `steps:` · `processing.disabled_steps` | the whole authored chain |
| `processing.dedup` · `.summarize` · `.join` · `.map` (and `duplicate_check` has only a rule) | five transform blocks |
| `trigger:` | **no field spec at all** — how a pipeline starts is undescribed |
| `processing.batch.max_bytes` · `.order` | only `max_files` is declared |

⛔ **This is the single largest structural problem in the current design.** It is why "the config
validates" and "the pipeline runs" are different statements, and why a UI form cannot be generated for
half the surface. A rewrite should make **one** declaration the source and derive both readers from it.

### ⚠ Keys whose ABSENCE is meaningful

Absence is not the same as a default in several places, and treating them alike silently changes
behaviour:

- `processing.intake.*` — absent means **inherit the global `-Dingest.*`**, not "use this value".
- `processing.csv_settings.ignore_errors` / `null_padding` / `store_rejects` — blank means "the
  engine's historical default", explicitly distinct from an authored `false`.
- `processing.unpack.data_extensions` — absent means the shipped default list; **explicitly empty means
  opt out entirely**. Two different states.

### ⚠ Derived, never authored

`schemaFingerprint` on a derived table; `id` when absent (derived from `name`); `stream` (defaults from
the pipeline name).

⚠ `dirs.errors` and `dirs.quarantine` default to subdirectories of `poll` when absent.

🔴 **`output_store:` is the Stage-2 arming condition, and this is the least discoverable rule in the
system.** `summarize`, `dedup`, `join` and any `steps:` chain **refuse to arm** without it:

> `steps: does not execute on the linear ingest path — author a top-level output_store: and run the
> chain at rest (pipeline_config: pipeline job), or keep the pipeline inactive (active: false)`

🔴 **A `route` step inside `steps:` is refused by BOTH paths** — the linear one cannot execute it and
the at-rest route cannot either, because one `output_store` cannot name N branches. Route demux exists
**only** on the ingest graph lane (§7.3).

⚠ **Two spellings, one concept, in more than one place:** `parsing.grammar` beats `processing.grammar`;
fixed width accepts two spellings. A rewrite should collapse these.

---

## 4. Companion files and how each binds

| File | Holds | Bound by |
|---|---|---|
| `<name>_schema.toon` | `raw.fields[]` — columns, selectors, types, partitions | `processing.schema_file` |
| `<name>_mapping.csv` | target ← source column rules | **two ways**: a sibling of the *schema* file, dual-read automatically (`MappingCsv.siblingFor`), **or** an explicit `processing.mapping_file` |
| `<name>_enrich.toon` | an at-rest recompute | the **enrichment names the pipeline** (`triggers.on_pipeline`); the pipeline does not name it |
| `jobs/<name>_job.toon` | a cron/event Job | the **Job names its target**; the pipeline does not |
| grammar component | reusable parse settings | `parsing.grammar` — inline `csv_settings` overrides it |

⚠ **The reference direction is inconsistent**: the pipeline points *out* to its schema and mapping, but
enrichments and jobs point *in* at the pipeline. That is why "everything related to this pipeline" is
not answerable by reading the pipeline file alone — it needs a reverse scan. §9 and §10 both turn on
this.

⚠ A config ref (`schema_file`, `mapping_file`, `grammar`) resolves **config-relative first,
working-directory second**. The portable form is a bare `<name>.toon` beside the pipeline.

---

## 5. 🔴 The graph is derived, not stored

**No file contains nodes and edges.** A graph-shaped authoring file (`*_flow.toon`) existed; its
authoring writes are **retired** and only reads are grandfathered.

```
*_pipeline.toon ──PipelineLift.lift()──▶ PipelineGraph ──PipelineEditable.lower()──▶ *_pipeline.toon
```

`GET /pipelines/{n}/graph/raw` lifts; `PUT /pipelines/{n}/graph` lowers back over the same file through
the same write gate. **The file remains the source of truth; the graph is a projection.**

### Which section becomes which Step

| Step | Derived from |
|---|---|
| `acq` | `collector:` + `dirs.poll` + `processing.file_pattern` |
| `gap` | `collector.gap_detection` (attached by a `gap` edge) |
| `parse` | `parsing:` + `processing.schema_file` / `csv_settings` / `schemas` / `segments` |
| `filter · join · dedup · summarize · route` | the ordered **`steps[]`** list |
| `map` | `processing.map` |
| `sink` | `output:` + `dirs.database`/`backup`/`temp`, or an entry in `sinks[]` |

### A Step

`PipelineNode(id, type, name, description, config, use)`

- `config` is **the raw config-file section verbatim** — which is why keys the graph does not model
  survive a round trip.
- `use` is a registry reference (`connection/<id>`, `grammar/<id>`), resolved at load.
- `name`/`description` are display only.
- Per-type config vocabulary is **server-published** (`GET /pipelines/node-types`). ⚠ Every key there
  **is** the engine's config key — no mapping layer exists, so a key that is not byte-identical
  silently no-ops.

### Edges

Declared vocabulary: `data` · `unmatched` · `gap` · `success` · `failure` · `on_commit` · `dropped` ·
`invalid` · `duplicate` · `route:<key>`.

⚠ **Declared ≠ emitted.** A lifted graph only ever contains **`data`, `gap`, `unmatched`, `route:*`**.
The rest appear in node types' declared out-sets and in `ConservationCheck`'s loss accounting.

🔴 **And the `data` edge is a fiction.** No records travel along it. The engine materialises one table
(`DataTransformer`, a single `CREATE TABLE AS SELECT`) and `COPY`s it out; `transform.map` is never
executed as a node at all. What the edge actually expresses is *"this Step's output is that Step's
input"* — a reference, not a flow. §11 proposes fixing the model to say what the engine already does.

### What lowering refuses

`UNSUPPORTED_NODE` · `NO_ACQUISITION` / `NO_PARSER` / `NO_PERSISTENT_SINK` · `PARSER_NO_SCHEMA` ·
`UNSUPPORTED_BINDING` · `UNSUPPORTED_MAP_KEY` · `MAPPING_CONFLICT` · `MULTI_MAP_CONFIG` ·
`MULTI_PARSER` · `PARSER_FRONTEND_MISMATCH`.

⛔ **`MULTI_SINK` is a dead constant** — it stopped firing when `sinks:` became a plural block. The
`MULTI_JOIN`/`MULTI_DEDUP`/`MULTI_ROUTE`/`MULTI_SUMMARIZE` codes were removed when `steps:` gained
ordering.

---

## 6. Step types and which may connect

28 builtin types in 5 categories. Each declares what it **accepts** and **emits**.

⚠ **Read this table as the CURRENT declaration, not as the intended model.** It is phrased in terms of
records flowing along a `data` edge; the runtime passes a Consignment token and resolves data by
reference (§11). The connection *rules* below survive that change — only the thing being passed is
misdescribed.

| Category | Types | Accepts | Emits |
|---|---|---|---|
| SOURCE | `acquisition`, `adapter` | — (entry) | `data`, `gap`, `failure` |
| PARSE | `parser`, `parser.delimited/.fixedwidth/.asn1/.json/.text_regex/.xlsx/.plugin` | `data` | `data`, `unmatched`, + named `route:*` |
| TRANSFORM | `transform.map/.filter/.select/.derive/.validate/.dedup/.dedup.marker/.join/.summarize/.split/.merge`, `enrichment` | `data` (`enrichment` also `on_commit`) | `data` (+ `dropped`/`invalid`/`duplicate`); `route` + named `route:*` |
| SINK | `sink.persistent`, `sink.materialized`, `sink.view` | `data` | `success`, `failure`, `on_commit` — **terminal for data** |
| CONTROL | `gap`, `alert`, `event` | outcome rels | — (leaves) |

**The rule, enforced by `PipelineValidator` at save *and* at execution:**

1. the source must emit the edge's rel → else `ILLEGAL_EMIT`
2. a `data` edge's target must accept `data` → else `ILLEGAL_ACCEPT`
3. an outcome/route edge's target must accept that rel **or** accept `data` → else `ILLEGAL_PAIRING`
4. no cycles → `CYCLE`

⚠ Clause 3 is a deliberate **handler exemption**: anything that consumes rows may also consume a
reject or route stream. An unregistered (plugin) type is **warned, not blocked**.

⚠ **The UI is looser than the server.** It offers rels from a published `emits` map and enforces splice
shape only — it never checks the target's `accepts`. A canvas edge can therefore be built that save
refuses. The validator is the single authority.

### The authored verb palette

Two different vocabularies are served, which is why the palette and the verb list disagree:

- `GET /pipelines/node-types` — **all 28 types**, grouped by category (what the canvas palette shows).
- `GET /pipelines/step-types` — the **9-entry verb catalogue**: `collect · parse · map · dedup ·
  transform`(filter) `· transform`(join) `· summarize · route · sink`.

🔴 **The verb catalogue authors the GENERIC `parser` type.** That contradicts the recorded decision
that *a parser is always format-specific*, and it is why a newly created pipeline shows an
unconfigured "Parse" Step. See §10.

---

## 7. What actually runs

### 7.1 Two stages

**Stage 1** is a per-batch multiplexer: ingest → coerce → partition → write. M input files → N
partitioned outputs, one DuckDB connection per batch, **no cross-batch state**. **Stage 2** is at rest
— enrichment, and the authored `steps:` chain.

🔴 **The dividing rule is batch independence, not statelessness per record.** Three cross-record
`GROUP BY`/stateful operations legitimately live in Stage 1 (lineage counts, output-bounds aggregation,
reference-store versioning) because none needs state shared across *concurrently running* batches.
Record `dedup` was moved out on 2026-08-11 for failing exactly this test.

### 7.2 The ingest path

`CollectorProcessor.acquire/ingest` (polls, plans Consignments via `ConsignmentPlanner`, bounded by
`batch.max_files`/`max_bytes`, default order `mtime`) → `BatchProcessor.process` (picks a
`BatchIngestStrategy`) → `CsvBatchStrategy` / `StreamingPluginBatchStrategy` → ingester →
`DataTransformer` (one `CREATE TABLE AS SELECT`) → `PartitionWriter` (`COPY … PARTITION_BY`) →
`BatchProcessor.commit`.

Consignment id: `<ts>_<slug>_<seq4>` — e.g. `20260830_095732_default_0001`.

**Crash safety is an ordering property**, not a transaction: DuckLake register → manifest → output
registration → backup → **markers LAST** → fingerprint/watermark. Markers are written last so a crash
mid-commit never leaves a stale "already processed" marker. `PartitionWriter` copies to staging with
`OVERWRITE_OR_IGNORE` then reveals by a **two-step atomic rename**.

### 7.3 Two lanes

A pipeline takes the **graph lane** only if: a scratch dir is configured · every lifted sink maps 1:1
to `cfg.sinks()` · the seed feeding the write is `transform.map` · **every sink hangs directly off that
seed** by a `data` edge (`BatchIngestStrategy.graphLaneCarries`). Otherwise it takes the flat lane.

🔴 **`transform.map` is never executed as a graph node.** It is the schema projection already
materialised by the ingest, folded into the write — the graph lane performs only the *write*, and never
re-runs parse or map. `dedup`/`join`/`summarize` between map and sink are **refused on both lanes** and
execute only at rest.

The graph lane commits per branch through a durable `BranchCommitLog` (under `dirs.temp`); the flat
lane loops `sinks[]` with no branch ledger.

### 7.4 What a run leaves behind

| Artifact | Records | Default |
|---|---|---|
| status ledger (CSV) | one row per member file | on |
| batches ledger (CSV) | per-Consignment roll-up incl. `cast_failures` (`-1` = not measured) | on |
| lineage ledger (CSV) | output ↔ input join with row counts per partition | on |
| commits log + `manifests/*.json` | the authoritative per-batch record | on (with `dirs`) |
| `consignment_outputs` registry | one row per output file: `record_day`, `rows`, state | on since 2026-08-10 |
| quarantine tree + `errors/*_errors.csv` | rejected files and rows | on |
| `BranchCommitLog` | per-branch commits | graph lane only |
| `inspecto_pipeline_provenance` | the node×rel count matrix | ⚠ **off** unless `-Dprovenance.backend` |

⚠ **Default-off stores are a recurring trap here** — `provenance`, `file_stages`, and the acquisition
ledger (in-memory by default) each look fine in a test and produce nothing on a stock deployment.

### 7.5 Triggers

`trigger:` is carried verbatim and classified by `PipelineTrigger` into schedule (`every`/`cron`) /
event / manual. **Absent ⇒ the pipeline stays on the global poll cycle.** Jobs additionally support
`on_pipeline:` (fires on a batch `SUCCESS` via `BatchEventBus`) and `on_signal:`.

---

## 8. Extension points — what a plugin can and cannot add

🔴 **You cannot add a new Step type today.** The vocabulary is the closed `BuiltinNodeType` enum. A
two-part SPI exists — `PipelineNodeType` (descriptor) + `PipelineNodeExecutor` (execution), each
`ServiceLoader`-discovered and layered *after* the built-ins so an edition can override one — but **no
external provider exists; every shipped type is a `BuiltinNodeType`.** The pack-hosted scaffold path
for new steps is gated behind unshipped platform-services work.

| SPI | Adds | New Step type? |
|---|---|---|
| `ParserPlugin` | a file-format parser in `GET /parsers`; may name an ingester | ❌ configures the `parse` Step |
| `StreamingFileIngester` | how a format loads into Tables; named by FQCN in `processing.ingester` | ❌ |
| `ConsignmentProcessor` | Consignment-scoped work selected by `id()` from a `consignment.process` **Job** | ❌ a Job kind |
| `JobTypeProvider` | a new **Job** type in the open `JobTypeRegistry` | ❌ |
| `enrichment` | custom SQL as a partition-scoped recompute at rest | ❌ |

⚠ **Deployment is classpath-only** (`-cp "inspecto.jar:your-jar.jar"`). The one drop-in directory that
exists (`-Djobs.packs.dir`) is for **Job packs**.

⚠ **If the node vocabulary changes, two committed contracts must be regenerated together** —
`node-attributes.contract.json` (`-Dnode.attributes.write=true`) and `step-types.contract.json`
(`-Dstep.types.write=true`). Regenerating one turns the full reactor red after a green targeted run.

---

## 9. Export and import

Three things share the word "export"; only the first moves configuration.

**Metadata bundle** — `POST /bundle/export` · `/bundle/preview` (read-only fit check: `new` /
`unchanged` / `drifted` / `unsupported`) · `/bundle/import` (upsert in dependency order).

```json
{ "format": "inspecto-metadata-bundle", "version": 2, "sourceSpace": "…",
  "items": [ { "kind": "authored-pipeline", "id": "orders", "content": {…},
               "provenance": { "contentHash": "sha256:…" } } ] }
```

Apply order: `connection → grammar → transform → sink → dataset → query → widget → dashboard →
reconciliation → authored-pipeline → job → saved-view`.

🔴 **Three defects, all confirmed:**
1. **Export carries exactly the items the caller lists.** The closure is derived **client-side**, and
   the UI sends one item — so an `authored-pipeline` export does **not** bring its schema, mapping or
   grammar.
2. **`enrichment` is not a bundle kind at all.** It cannot travel.
3. **`schema` and `mapping` are supported but absent from the apply order**, so they sort *last* —
   after the pipeline that references them.

**Not bundles:** `GET /pipelines/{id}/document` is a **Markdown** projection for sign-off, regenerated
on demand and never stored as truth. **Grammar CSV** (`<pipeline>_parser.csv`) is a portable template
for a Grammar; unknown option keys are listed, never applied.

---

## 10. What is broken, missing, or contradictory

The redesign's actual agenda. Each item is grounded, not suspected.

### Contradictions to resolve

1. 🔴 **Consignment vs Batch.** The entity was renamed in 2026-08-03; code and API still say `Batch`.
   Finish it or retire it.
2. 🔴 **The verb catalogue authors a generic `parser`**, against the recorded decision that a parser is
   always format-specific. A new pipeline therefore starts with an unconfigured "Parse" Step that must
   be converted through a custody dialog. *(Operator-reported, 2026-08-30.)*
3. ⚠ **The UI's edge rules are looser than the validator's**, so a canvas edge can be built that save
   refuses.
4. ⚠ **Two spellings for one concept** in several places (`parsing.grammar` vs `processing.grammar`,
   two fixed-width spellings).
5. ⚠ **Reference direction is inconsistent** (§4): pipelines point out to schemas, enrichments and jobs
   point in. "Everything related to this pipeline" needs a reverse scan.

### Gaps

6. 🔴 **Export/import is not a full set** (§9) — three separate defects. *(Operator-requested,
   2026-08-30.)*
7. 🔴 **No plugin can add a Step type** (§8) — the SPI is real but unused, and the deployment story is
   classpath-only.
8. ⚠ **`output_store:` as the Stage-2 arming condition is undiscoverable** (§3) — a `steps:` chain
   silently cannot run without it, and the only signal is a refusal at arming time.
9. ⚠ **`route` cannot run at rest** (§3) — it exists only on the ingest graph lane.
10. 🔴 **Two authorities read the config and disagree** (§3) — a whole `parsing:` block, four `dirs.*`
    keys, `trigger:`, `steps:`, `route:` and five transform blocks are engine-read but spec-invisible.
    **The largest structural problem in the current design.**
11. ⚠ **`steps:` has no authoring surface.** A chain is authored as a comma-separated `processor`
    string plus a positionally-aligned JSON array the author keeps aligned by hand.
12. ⚠ **The post-sync chain is invisible in the editor.** `open-dag-pipeline-design.md` §6.4 marks
    stage 4 SHIPPED in its table (a read-only registered-outputs list) while its own prose two lines
    later says the lane is still invisible — 🔴 the document contradicts itself; the honest reading is
    *read-only view shipped, no canvas authoring*.
13. ⚠ **Fan-in is canvas-only by decision (D-6)** — it is deliberately never user-wired in the verb
    vocabulary. A redesign must keep or explicitly overturn that.
14. ⚠ **D-9 (cross-Consignment windowed dedup) is NAMED, not designed** — no persistence, winner
    policy or window-advance spec exists, despite being called "a designed fast-follow".
15. ⚠ **Phase 6's deletion half is release-gated** to a major bump, so the legacy read path and the new
    one coexist until then — by decision, not by neglect.

### Dead or misleading

16. `MULTI_SINK` is a dead constant still named in refusal documentation and the UI's error handling.
17. Six of the ten declared edge relations never appear in a lifted graph.

---

## 11. Proposed fix — a Step passes a token, not data

**Operator proposal, 2026-08-30:** *a Step does not accept or emit data; it emits **control
information** — like a NiFi FlowFile — which may carry a path to data.*

🔴 **This is not a new direction. It is what the engine already does, and what the type system
contradicts.** The evidence:

| Already the token model | Where |
|---|---|
| The Step contract is *"Consignment in → Consignment out (+ rejects)"* | `GLOSSARY.md:264` |
| `ProcessorContext` — the SPI a third party implements — offers `consignmentId()`, `outputs()` (path · rows · bytes · partition · record-day · state), `read()` for SQL **by reference**, `summaries()`, `tables()`, `log()`, `signals()`. **There is no row stream in it.** | `ConsignmentProcessor` / `ProcessorContext` |
| The post-sync lane's carrier is **the output registry, not a piped relation** | `derived-tables-post-sync-lane` |
| `transform.map` is never executed as a node; one `CREATE TABLE AS SELECT` is materialised and `COPY`d | §7.3 |
| A Consignment already has an id, a status, member Files and registered outputs | §1, §7.4 |

Against that, `BuiltinNodeType` declares `accepts`/`emits` in terms of `PipelineRel.DATA`, which
asserts that records flow along an edge. **Both cannot be true.** The token model is the one the
runtime implements; the `DATA` edge is the one that produces the anomalies in §10.

### 12.1 What the current edge vocabulary conflates

Ten relations, doing four unrelated jobs:

| Job | Relations | Really a… |
|---|---|---|
| the token continues | `data` | **edge** |
| content demux | `route:<key>` | **edge** |
| records the Step did not pass on, with a cause | `unmatched` · `dropped` · `invalid` · `duplicate` | **edge**, but one kind with a reason — not four kinds |
| lifecycle outcome | `success` · `failure` · `on_commit` | **not an edge — a Signal** |
| an observation | `gap` | **not an edge — a Signal** |

That conflation is why six of the ten never appear in a lifted graph (§5) and why
`ConservationCheck` has to hand-list four of them as "loss" edges: they were never the same kind of
thing.

### 12.2 The proposed model

A Step consumes one **Consignment token** and emits zero or more tokens on named **outlets**:

```
token = { consignmentId, runId, attributes{…}, dataRefs[ {store, table, partition, path, rows} ] }
```

⚠ **`dataRefs` is a reference, never a payload.** A Step resolves data by reference when it needs it
(`ctx.read()`), which is what makes "map is folded into the write" honest rather than a special case:
the token's `dataRefs` are rewritten; nothing moves.

**Three outlet kinds replace ten relations:**

| Outlet | Meaning |
|---|---|
| `main` | the token continues |
| `reject:<reason>` | records the Step did not pass on — `reason` ∈ `unmatched`, `dropped`, `invalid`, `duplicate`, … |
| `route:<key>` | operator-named content branch |

**Lifecycle and observations leave the graph and become Signals.** `Signal` already carries
`subject`, `source`, `correlationId`, `causationId`, `severity` and a payload — everything
`success`/`failure`/`on_commit`/`gap` need. ⚠ This is existing machinery, not new: `gap` already emits
`SEQUENCE_GAP`, and Jobs already subscribe to commits via `on_pipeline`. The change is deleting the
edge spelling, not building a replacement.

### 12.3 What this fixes

| §10 item | Fixed how |
|---|---|
| 13 — six relations never appear | they stop being edges |
| 17 / `ConservationCheck`'s hand-list | one `reject:` kind with a reason; loss accounting reads the reason |
| 7 — no plugin can add a Step type | a Step becomes *token → tokens*, which is **exactly `ConsignmentProcessor`**. The SPI to open is one that already exists and already has the right shape |
| the map-folding special case | a Step that only rewrites `dataRefs` is an ordinary Step, not an exception the lane has to admit |
| 9 — `route` cannot run at rest | routing splits tokens; nothing about it is ingest-specific once data is by reference |
| 11 / park–drain | parking is *holding tokens at an outlet* — which is what `BranchCommitLog` already does |
| 8 — `output_store:` as a hidden arming condition | a Step declares its execution mode (`LOWERED` \| `EXECUTED`, the D0-B vocabulary); the config stops encoding it by side effect |

⚠ It also makes the **Stage 1 / Stage 2 split a property of the Step, not of the config's shape** —
`LOWERED` contributes SQL, `EXECUTED` runs imperative Java. That is precisely the open registry
platform-services D0-B already proposed, so this proposal and that one converge rather than compete.

### 12.4 What it does NOT fix, and what it costs

- ⛔ **It does not make the config non-flat.** Lift/lower stays until the amendment's target shape
  (§0) lands. The token model is about the *runtime contract*; the authoring shape is a separate
  decision.
- ⛔ **Fan-in still needs its own decision** (D-6 keeps it canvas-only). A token model makes fan-in
  *expressible* — several tokens converging — which is a reason to revisit D-6, not a reason to
  assume it.
- ⚠ **It is a breaking change to two committed contracts** (`node-attributes`, `step-types`) and to
  `BuiltinNodeType`'s declared sets, so it belongs with Phase 7's major-bump window (§0), beside the
  `Batch` → `Consignment` rename — which it also makes more urgent, since the token *is* the
  Consignment.
- ⚠ **`ConservationCheck` must keep working.** Its loss accounting is real and load-bearing; it moves
  from "these four rels are loss" to "a `reject:` outlet is loss, and its reason names which kind".

### 12.5 Suggested sequence

1. **Say it before building it** — correct `BuiltinNodeType`'s doc and this spec so `accepts`/`emits`
   describe *tokens*, and stop describing `data` as a record flow. Costs nothing, ends the
   contradiction.
2. **Delete the five non-edges** — move `success`/`failure`/`on_commit`/`gap` to Signals, which they
   effectively already are. Removes five of the ten relations with no runtime change.
3. **Collapse the rejects** into `reject:<reason>`; teach `ConservationCheck` the reason.
4. **Open the Step SPI** on the `ConsignmentProcessor` shape, with the `LOWERED`/`EXECUTED` mode from
   D0-B — the first point at which a third party can add a Step.
5. Only then revisit **fan-in** and the authoring shape.

⚠ Steps 1–3 are documentation and vocabulary and can land before any redesign is agreed; 4–5 are the
part that needs the §0 decision first.

---

## 12. How the gaps get filled

The §10 list, turned into work. ⚠ **Nothing here is scheduled** — it is the shape of the work and its
dependency order, so a decision can be taken with the cost visible.

**Two decisions gate the second half.** (a) §0 — finish the approved amendment or replace it.
(b) §11 — adopt the token model or keep the `DATA` edge. **Wave 0 and Wave 1 need neither**, which is
the point of separating them: roughly half the list can close before either decision is taken.

### Wave 0 — free now: vocabulary, dead code, honest messages

No decision, no design, no runtime change.

| Gap | Fix | Cost |
|---|---|---|
| **16** `MULTI_SINK` is dead | delete the constant and the UI's mention of it in its refusal handling | minutes |
| **17** six relations never appear · **§11 step 1** | correct `BuiltinNodeType`'s doc and this spec to describe **tokens**; stop calling `data` a record flow | an afternoon |
| **4** two spellings for one concept | declare `parsing.*` canonical; keep `processing.grammar` / `fixed_width` as **read-only aliases** that emit a deprecation `Finding` on save, and stop *writing* them | small |
| **9** `route` cannot run at rest | the refusal already exists — make its message name the ingest lane as the place route *does* work, instead of stating two negatives | minutes |

### Wave 1 — cheap engineering, still no decision needed

| Gap | Fix | Notes |
|---|---|---|
| **10** two authorities disagree — *the largest structural problem* | **Start with a sixth contract test**, not a rewrite: enumerate the keys `PipelineConfigParser` reads and assert every one is declared in `ConfigSpecs.pipeline()`. It fails today (that is the point), so land it with the known set as an explicit allow-list and **shrink the list** as keys are declared. | 🔴 This makes drift *visible* and **blocks new drift immediately**, which no amount of declaring keys does. There are already five contract tests (`NodeAttributes`, `StepTypes`, `NodeConfigName`, `MapNodeKey`, `BindKindHome`) — this is the idiomatic move here, not a novel one. |
| **8** `output_store:` arming is undiscoverable | declare it in the spec with a **cross-field rule** — "`steps`/`dedup`/`summarize`/`join` require `output_store`" — so the UI can show the requirement and a save returns a **field-anchored 422** | converts a run-time surprise into an authoring-time error; the refusal text already exists, it just fires too late |
| **3** UI looser than the validator | ⚠ `accepts` is **already published** on `GET /pipelines/node-types` — the UI simply does not read it. Check the target's `accepts` on edge creation, next to the existing `typeEmits` lookup | purely client-side; the server stays the authority |
| **5** reference direction is inconsistent | add **`GET /pipelines/{n}/related`** — the server-side closure: the schema and mapping it points at, plus the enrichments, jobs and datasets that point at *it* | one endpoint that also fixes 6, and is the honest answer to "what belongs to this pipeline" |
| **6** export/import is not a full set | three parts: **(a)** bundle export calls `related` instead of the UI deriving the closure; **(b)** add `enrichment` as a bundle kind; **(c)** put `schema`/`mapping` into `APPLY_ORDER` *before* `authored-pipeline` | (c) is a one-line ordering fix; (a) depends on 5 |
| **2** the verb catalogue authors a generic `parser` | emit one catalogue entry **per format** rather than one generic `parse`, and have New-pipeline write `parsing.frontend` so the lift types the Step immediately | ⛔ needs one product answer first: does New-pipeline **ask** for the format, or **default** to delimited? (§13 D3) |
| **ledgers served from a database** *(status / batches / lineage)* | the seam already exists end to end — `StatusStore` with a `FileStatusStore` and a `DbStatusStore` that projects into `inspecto_status_*` on **DuckDB or Postgres**, and every read route already goes through the interface, so **the UI needs no change**. The engine keeps appending the CSVs as the durable write-ahead | ⛔ blocked on §13 D4: flipping the default surfaced a possible **cross-space isolation** defect and made the status DB visible as a *business* store. The bundle seam and the degrade path shipped 2026-08-31; the default is a one-word change once D4 is answered |

### Wave 2 — gated on a decision

| Gap | Gated on | Fix |
|---|---|---|
| **7** no plugin can add a Step type | §11 | open the SPI on the `ConsignmentProcessor` shape with the `LOWERED`/`EXECUTED` mode (D0-B). The interface already has the right shape; what is missing is the registry and the mode |
| **11** `steps:` has no authoring surface · **12** post-sync chain invisible | §0 + a UX design | one surface serves both — an ordered chain editor is what makes the post-sync lane visible |
| **13** fan-in is canvas-only (D-6) | §11 | a token model makes fan-in *expressible*; that is a reason to **revisit** D-6, not to assume it is overturned |
| **1** `Batch` → `Consignment` | §0 (Phase 7) | mechanical but wide: 517 files, 39 `@PublicApi` types. ⚠ Do it as **one** commit with a codemod plus both contract regens — dripping it leaves the codebase in the split state indefinitely, which is the current complaint |
| **15** Phase 6's deletion half | the major-bump window | already decided; it just needs the window |

### Wave 3 — needs design before it can be estimated

| Gap | Why |
|---|---|
| **14** D-9 cross-Consignment dedup | it is **named, not designed**: no spec for where the ledger persists, the winner policy, or how the window advances. ⛔ Not schedulable until someone writes that; the "designed fast-follow" label is wrong |

### The honest shape of it

- **Wave 0 + Wave 1 close 10 of the 17 items** and need no architectural decision. The most valuable
  single item is the **key-coverage test** (10) — not because it fixes the drift, but because it stops
  it growing while the rest is decided.
- **Wave 2 is where the leverage is**, and all of it waits on the two decisions in §0/§11.
- ⚠ **Sequence matters in one place:** 5 → 6(a). Everything else in Waves 0–1 is independent and can
  be taken in any order, or by different people.

---

## 13. The interview — decisions to take before implementing

Each is posed so it can be answered in one sitting. **Options carry their consequence**, because a
decision taken without the cost is taken twice. ⚠ Ordered by what they unblock, not by size.

### D1 — Finish the approved amendment, or replace it? *(gates most of Wave 2)*

The ELT final amendment (§0) is approved and ~90% shipped: `trigger:` + `steps:` + `guarantees:`,
three config types unified into one user concept.

- **(a) Finish it** — Phase 6's deletion half + Phase 7's rename. Predictable, already designed,
  release-gated. Nothing new is learned.
- **(b) Replace it** — ⚠ strands a landed migration and re-opens settled ground (the seven verbs, the
  Guarantees fold, park/drain). Needs a reason the amendment cannot reach.
- **(c) Finish it, then extend** — the amendment lands, §11's token model becomes the *next* step.

### D2 — Adopt the token model? *(gates the Step SPI, fan-in, the edge cleanup)*

§11: a Step passes a Consignment token carrying **references**, never rows.

- **(a) Adopt fully** — ten relations become three; lifecycle and observations become Signals; the Step
  SPI opens on the shape `ConsignmentProcessor` already has. ⚠ Breaks two committed contracts, so it
  rides Phase 7's major window.
- **(b) Adopt the vocabulary only** — Wave 0 steps 1–3: say tokens, delete the five non-edges, collapse
  the rejects. **No runtime change**, and it can start today.
- **(c) Keep the `DATA` edge** — then §10's items 7, 13, 17 stay open permanently, and the docs must
  stop claiming rows flow, because they do not.

### D3 — Does New-pipeline ask for the parser format, or default to one? *(unblocks Wave 1 gap 2)*

Today the verb catalogue authors a generic `parser`, against the recorded decision that a parser is
always format-specific.

- **(a) Ask** — one more question at create time; the Step is typed immediately and never needs the
  custody dialog.
- **(b) Default to delimited** — no new question; a non-CSV author changes it once. ⚠ Guesses for them.
- **(c) Neither — leave the Step untyped until Parse is configured** — honest, but keeps the generic
  "Parse" Step that prompted the complaint.

### D4 — Serving the ledgers from a database: what is required first? *(live — attempted 2026-08-31)*

Flipping `status.backend` to `db` surfaced two real consequences (recorded at the declaration):

- 🔴 **Isolation**: `ControlApiMultiSpaceTest` saw one space report another's committed batch. **Is a
  DB-backed status store required to isolate per space exactly as the file store does?** If yes, that is
  a defect to fix before the flip; if the store is intentionally shared, the test encodes a stale
  assumption and should say so.
- **Catalog**: the status DuckDB now appears as a *business* store in `/db/catalog`. Should an
  operational store be visible there at all, or filtered out as `ops:*` is?

### D5 — `Batch` → `Consignment`: finish or retire? *(gap 1)*

The rename was decided 2026-08-03 and never rolled out; code and API still say `Batch`.

- **(a) Finish** — 517 files, 39 `@PublicApi` types, one commit with a codemod plus both contract
  regens, in the major window. ⛔ Not drip-fed: dripping leaves the split state indefinitely.
- **(b) Retire the rename** — keep `Batch` everywhere and delete the GLOSSARY entry. Honest, cheap, and
  loses a genuinely better word.
- ⚠ **Doing neither is the current state**, and it is the worst of the three.

### D6 — Fan-in: keep it canvas-only? *(gap 13; depends on D2)*

D-6 keeps fan-in out of the verb vocabulary. A token model makes it *expressible*.

- **(a) Keep D-6** — fan-in stays canvas-authored; the recipe stays a chain-plus-branches.
- **(b) Open it** — ⚠ then the authoring shape is a DAG, not a chain, and the Pipeline Document, the
  recipe compiler and the editor all follow.

### D7 — What shape is the `steps:` authoring surface? *(gap 11 + 12; needs UX)*

Today a chain is a comma-separated `processor` string plus a positionally-aligned JSON array kept
aligned by hand. One surface would also make the post-sync lane visible (gap 12).

### D8 — D-9 windowed dedup: what are the window semantics? *(gap 14 — not schedulable without this)*

⛔ It is **named, not designed**. Needed before it can be estimated at all: where the ledger persists,
the winner policy, and how the window advances.

### D9 — What exactly "belongs to" a Pipeline? *(unblocks gaps 5 + 6)*

`GET /pipelines/{n}/related` needs a definition. Schema and mapping are certain. **Enrichments and Jobs
that name the pipeline?** (they point inward — §4). **Datasets registered from its sink?** **Connections?**
⛔ The operator has already said connections are excluded from export; is that also true of "related"?

### D10 — Config: one declaration, or two readers kept honest? *(gap 10 — the largest)*

- **(a) Contract test now, single declaration later** — the recommended first move (§12): make drift
  visible and block new drift, then close the existing gap incrementally.
- **(b) Go straight to one declaration** — `ConfigSpecs` becomes the source and the parser reads from
  it. ⚠ Large, and it touches the loader every pipeline depends on.
- **(c) Accept two readers** — then the spec must stop being described as the config's definition.

---

## 14. Source map

**Code** — `inspecto-etl`: `PipelineConfig`, `PipelineConfigParser`, `DataTransformer`,
`PartitionWriter`, `Batch`, `ConsignmentPlanner`, `CommitLog`. `inspecto-engine`: `pipeline/`
(`PipelineLift`, `PipelineEditable`, `PipelineValidator`, `PipelineExecutor`, `BuiltinNodeType`,
`NodeAttributes`, `PipelineProjection`, `RecipeConverter`/`RecipeCompiler`), `inspector/`
(`CollectorProcessor`, `BatchProcessor`, `BatchIngestStrategy`), `consignment/`. `inspecto`:
`control/` (`PipelineGraphRoutes`, `ConfigReadRoutes`, `ConfigWriteRoutes`, `BundleRoutes`).

**Documents folded into this one** (still on disk as evidence; ⚠ not maintained in parallel):
`okf/backend/pipeline-graph/` — `pipeline-anatomy.md`, `design.md`, `pipeline-graph-design.md` (144 KB,
the deep design incl. its §14 backlog), `live-execution.md`, `multi-location-ingest.md`,
`step-park-drain.md` · `okf/backend/engine/` — `node-types.md`, `plugins.md`, `stage1-architecture.md`,
`ingestion.md`, `branch-aware-ingest.md`, `consignment-status-flow.md`, `consignment-addressing.md`,
`consignment-concurrency.md`, `parser-plugins.md`, `output-sinks.md`, `transforms-seams.md`,
`unpack-stage.md`, `pipeline-test-run.md` · `superpower/` — `elt-final-amendment-plan.md` (114 KB),
`consignment-elt-architecture.md` (87 KB), `open-dag-pipeline-design.md`, `step-workbench-design.md`,
`mid-branch-transforms-design.md`.

⚠ **Retirement is deliberately NOT done yet.** Those files describe what is live; they should be
archived only as the rewrite replaces each area, not in advance of it.
