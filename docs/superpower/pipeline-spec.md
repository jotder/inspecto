# The Pipeline — one consolidated specification

**Status:** working document for a redesign (2026-08-30). **This is the single place the Pipeline is
described *as a plan*.** It is written to be changed: the intent is to rewrite the subsystem from here.

> **Layer split (docs consolidation, 2026-09-01):** as-built truth lives in the OKF tier — the
> editor UI in `okf/frontend/features/pipeline-editor.md`, the backend model in
> `okf/backend/pipeline-graph/pipeline-graph-design.md`. This file stays the ACTIVE plan; when its
> waves drain, distill here-only durable facts into those concepts and archive it per the
> lifecycle rule.

**What this replaces.** Pipeline knowledge was spread across ~20 files and >500 KB, and no one of them
told you what a Pipeline *is*. This document states the whole subsystem as it stands today. The deep
files stay on disk as evidence and are mapped in §14; ⚠ **they are not maintained as a parallel truth
— when this document and one of them disagree, fix one of them, do not silently diverge.**

**How to read it.** §1–§4 are the authored surface. §5–§6 are the model. §7 is what actually runs.
§8–§9 are the seams. **§10 is the honest list of what is broken, missing, or contradictory**, and
**§11 is the proposed fix**, **§12 turns the gaps into work**, and **§13 records the decisions taken** —
so implementation can start without re-litigating them.

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
sequenced last for blast radius. ~~⚠ I could not confirm a Phase 7 SHIPPED marker; treat it as not
done.~~ ✅ **Corrected 2026-09-01: the rename SHIPPED** (`ff33246a`, 155 files — see §12 row 1); the
deferred residuals (DDL column, wire/persisted `batch_id` spellings, the `.toon` `processing.batch.*`
keys) are BACKLOG §4 rows, not Phase-7 work.

⛔ **So the first design decision is not "what should the Pipeline look like" — it is "do we finish the
approved amendment, or replace it?"** Starting a fresh design without answering that re-opens
settled ground and strands ~90% of a shipped migration. Everything in §10 below should be read as
"what the amendment has not yet reached", not as an unexplored field.

✅ **Decided 2026-08-31 (§13 D1): finish it, then extend.** §11's token model is the next step, not a
competitor to it.

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
| ~~`output_store` · `id`~~ | ✅ corrected 2026-09-01: **both are declared now** (`id` since 2026-08-02; `output_store` 2026-08-31 with the `stage-two-blocks-require-output-store` rule — §12 row 8). Current census: [`okf/backend/pipeline-graph/pipeline-config-keys.md`](../okf/backend/pipeline-graph/pipeline-config-keys.md) — 17 parser-only blocks, not this table's 18 |
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
   be converted through a custody dialog. *(Operator-reported, 2026-08-30.)* ✅ **CLOSED 2026-08-31** —
   per-format catalogue entries, and New-pipeline asks for the format (D3).
3. ⚠ **The UI's edge rules are looser than the validator's**, so a canvas edge can be built that save
   refuses. ✅ **CLOSED 2026-08-31** — the canvas now checks the target's served `accepts` at edge
   creation, with the server's own refusal text.
4. ⚠ **Two spellings for one concept** in several places (`parsing.grammar` vs `processing.grammar`,
   two fixed-width spellings). ✅ **CLOSED 2026-08-31** — and the framing was off: the write side was
   already canonical, so the live defect was that `ConfigSpecs` declared **only the deprecated key**.
5. ⚠ **Reference direction is inconsistent** (§4): pipelines point out to schemas, enrichments and jobs
   point in. "Everything related to this pipeline" needs a reverse scan. ✅ **CLOSED 2026-08-31** —
   `GET /pipelines/{n}/related` computes the closure server-side. **6(a) is now unblocked.**

### Gaps

6. 🔴 **Export/import is not a full set** (§9) — three separate defects. *(Operator-requested,
   2026-08-30.)* ✅ **CLOSED 2026-08-31** — all three, though (c)'s `schema` half was refuted and became
   BUNDLE-SCHEMA-1 instead.
7. 🔴 **No plugin can add a Step type** (§8) — the SPI is real but unused, and the deployment story is
   classpath-only. 🔴 **GROUNDED 2026-08-31: two of those three clauses are FALSE.** A contributed
   step type is authorable (`StepKindRegistry` + Stage 5's CONTRIBUTED lowering) and the SPI has three
   dev packs and a scaffolding template. **Only "classpath-only" survives**, and precisely:
   `JobPackManager` loads *only* `JobTypeProvider`/`ExpressionProvider`, so a node-type pack cannot hot
   load. **Fixed the same day** with an owner-keyed pack overlay on both node registries. ⚠ I first
   called it window-gated over the two committed contracts; that gate was not real — node-attributes
   compares a static table, and step-types' own comment says plugin types are additive at runtime.
   ✅ **CLOSED 2026-08-31** — see the Wave 2 table for what shipped.
   silently cannot run without it, and the only signal is a refusal at arming time. ✅ **CLOSED
   2026-08-31** — declared, plus a cross-field rule and its mock mirror, so the answer arrives at save.
9. ⚠ **`route` cannot run at rest** (§3) — it exists only on the ingest graph lane. ✅ **CLOSED
   2026-08-31** (message half) — **two** refusal sites, both now naming the lane where route does work.
10. 🔴 **Two authorities read the config and disagree** (§3) — a whole `parsing:` block, four `dirs.*`
    keys, `trigger:`, `steps:`, `route:` and five transform blocks are engine-read but spec-invisible.
    **The largest structural problem in the current design.** ⚠ **Measured 2026-08-31, and this line was
    partly wrong:** `parsing:` is **not** wholly invisible — `parsing.source_timezone` and
    `parsing.delimited.*` were already declared, and `parsing.grammar` was declared by Wave 0's item 4.
    The five transform blocks are exact (`dedup`, `duplicate_check`, `join`, `map`, `summarize`), and
    `trigger:`/`steps:`/`route:` are confirmed. True block-level debt is **18**, now pinned and
    ratcheted by `PipelineKeyCoverageContractTest`. The `dirs.*` leaf keys are a **separate, still-open**
    problem: `dirs` is declared as a block, so a block-level contract cannot see inside it.
11. ~~⚠ **`steps:` has no authoring surface.**~~ ✅ **CLOSED 2026-08-31 — the row was wrong twice.**
    The Recipe view's `<app-pipeline-step-cards>` **is** an ordered chain editor (cards in chain order,
    insert/remove/move up-down, nested `route` branches), live and wired with `[editable]`. And the
    "comma-separated `processor` string plus a positionally-aligned JSON array" it describes is **not
    `steps:` at all** — that is the `consignment.process` Job's param pair, i.e. item 12.
12. ~~⚠ **The post-sync chain is invisible in the editor.**~~ ✅ **CLOSED 2026-08-31** — authored as ordered
    steps in the Job dialog (`pipeline-waves-drain-plan.md` §2.1), which also surfaced the engine's
    accept-and-corrupt handling of a non-scalar config value (**CHAIN-CONFIG-1**). Original text:
    ⚠ **The post-sync chain is invisible in the editor.** `open-dag-pipeline-design.md` §6.4 marks
    stage 4 SHIPPED in its table (a read-only registered-outputs list) while its own prose two lines
    later says the lane is still invisible — 🔴 the document contradicts itself; the honest reading is
    *read-only view shipped, no canvas authoring*.
13. ~~⚠ **Fan-in is canvas-only by decision (D-6)**~~ ✅ **CLOSED 2026-08-31 by §13 D6** — kept
    canvas-only, deliberately, and a token model is explicitly *not* grounds to overturn it
    speculatively. Not open work; a recorded decision.
14. ⚠ **D-9 (cross-Consignment windowed dedup) is NAMED, not designed** — no persistence, winner
    policy or window-advance spec exists, despite being called "a designed fast-follow".
    ✅ **DESIGNED 2026-08-31** — all three answers in `pipeline-waves-drain-plan.md` §3, each forced by a
    code constraint rather than chosen freely. 🔴 Grounding also refuted two things this spec
    repeats: the losing rows are **not** automatically quarantined (`duplicate` is just a named relation
    whose fate the graph decides), and the winner is **non-deterministic** whenever `order_by` is
    omitted — which is why the design makes it *required* once a window is declared.
15. ⚠ **Phase 6's deletion half is release-gated** to a major bump, so the legacy read path and the new
    one coexist until then — by decision, not by neglect.

### Dead or misleading

16. `MULTI_SINK` is a dead constant still named in refusal documentation and the UI's error handling.
    ✅ **CLOSED 2026-08-31.**
17. Six of the ten declared edge relations never appear in a lifted graph. ✅ **Wave 0 half CLOSED
    2026-08-31** — and grounding split the six into two unlike groups, which the one-line framing hid.
    `PipelineLift` builds only `data`, `unmatched` (parse → quarantine), `gap` (acquisition → gap) and
    `route:*`. Of the other six, **`dropped` / `invalid` / `duplicate` are real at runtime** —
    `RowShaper` returns them as named relations and `ConservationCheck` balances against them; they are
    simply not edges a *lift* draws, so "never appear" is true of the lift and false of the run.
    **`success` / `failure` / `on_commit` have no producer at all**: `on_commit` is only ever READ (the
    executor and validator skip it as a cross-pipeline trigger), and the other two are declared
    vocabulary nothing constructs. D2 moves that outcome set to Signals. `PipelineRel` and
    `BuiltinNodeType` now say this, and both say `accepts`/`emits` is **token** vocabulary — the
    constants keep their spelling (renaming breaks two committed contracts; that is Phase 7's half).

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

### Wave 0 — ✅ SHIPPED 2026-08-31 (all four)

No decision, no design, no runtime change. Reactor after: **BUILD SUCCESS, 3781 / 0 / 0 / 2** — the
recorded baseline, unmoved.

| Gap | Fix | Outcome |
|---|---|---|
| **16** `MULTI_SINK` is dead | delete the constant and the UI's mention of it in its refusal handling | ✅ constant deleted from `PipelineEditable`; it was declared once and referenced by **nothing** — safe because `@PublicApi(since="4.0.0")` is unreleased (newest ancestor tag is `v3.11.0`), so no shipped contract names it. Four comments cited it: two as a **live** example (`pipelines.service.ts`, `pipeline-editor.component.ts`) and one asserting the mock must refuse it (`pipelines.handler.ts`) — 🔴 that last one was **actively false**, since the server accepts a multi-sink graph. All now cite `MULTI_PARSER`, which still fires. The mock-editable comment explaining it is *not* a refusal is load-bearing and was kept. |
| **17** six relations never appear · **§11 step 1** | correct `BuiltinNodeType`'s doc and this spec to describe **tokens**; stop calling `data` a record flow | ✅ `PipelineRel` and `BuiltinNodeType` now state the token model and that `accepts`/`emits` is advisory token vocabulary, not a record-flow contract. Constants keep their spelling (renaming breaks two committed contracts — Phase 7's half, per D2). 🔴 Grounding **split the six into two unlike groups** — see §10 item 17. Also repointed two javadocs at `docs/okf/backend/pipeline-graph/pipeline-graph-design.md`; they cited a `docs/flow-graph-design.md` that has not existed since 2026-07-16 *and* whose name carried the banned synonym for **Pipeline**. |
| **4** two spellings for one concept | declare `parsing.*` canonical; keep `processing.grammar` / `fixed_width` as **read-only aliases** that emit a deprecation `Finding` on save, and stop *writing* them | ✅ 🔴 The real defect was narrower and worse than the row: the **write side was already canonical** (`PipelineEditable` writes `parsing.grammar`; `fixedwidth` is what a fresh node stamps), and the parser has preferred `parsing.grammar` for as long as the `parsing:` block has existed — but `ConfigSpecs.pipeline()` declared **only the deprecated `processing.grammar`** and never the canonical key. The spec published the wrong spelling and stayed silent on the right one. Both are declared now, and a `parsing-grammar-is-canonical` cross-field rule emits a **WARNING** (never an ERROR — the legacy key still reads, and refusing it would break deployed configs to make a naming point). ⛔ The `fixed_width` passthrough in `PipelineEditable.lower` is a **deliberate verbatim round-trip**, not drift — left alone. |
| **9** `route` cannot run at rest | the refusal already exists — make its message name the ingest lane as the place route *does* work, instead of stating two negatives | ✅ 🔴 There were **two** refusal sites, not one, and the row described the second. `PipelineLift.stageTwo` refuses a `route` step in an at-rest chain; `PipelineConfig.prepare()` refused with *"which neither the linear path nor the at-rest route can execute"* — two negatives that never told the author route works at all. Both now name route's home: the top-level `route:` block on the **ingest** lane, where the branch-aware executor gives each branch key its own sink. No test pinned either string. |

### Wave 1 — cheap engineering, still no decision needed

| Gap | Fix | Notes |
|---|---|---|
| **10** two authorities disagree — *the largest structural problem* | ✅ **SHIPPED 2026-08-31** — `PipelineKeyCoverageContractTest` (inspecto-etl), the sixth contract test. **Measured debt: 18 blocks** the engine reads and the spec does not declare — 8 top-level (`active`, `collector`, `output_store`, `route`, `sinks`, `steps`, `template`, `trigger`) and 10 under `processing.*`. They are the `UNDECLARED_BLOCKS` allow-list, which **only ever shrinks**: a new undeclared block fails immediately, and a newly declared one must be *removed* or the ratchet test fails. | 🔴 Drift is now visible and **new drift is blocked**. ⚠ **Granularity is the block, deliberately** — reconstructing dotted leaf paths from a dozen nested sub-parsers would be a heuristic, and a heuristic census over-reports. So leaf-level drift *inside* a declared block (the `dirs.*` keys §10 names) is **not** covered; that needs its own move. 🔴 **Falsified in four directions before being trusted** — a new parser read, a stale allow-list entry, a broken comment strip, and a new shadowing local each fail it. |
| **8** `output_store:` arming is undiscoverable | ✅ **SHIPPED 2026-08-31** — `output_store` is now declared, and `stage-two-blocks-require-output-store` (ERROR) fires at save. The row named the four blocks exactly: `PipelineConfig.prepare()` carries **four** separate refusals (`summarize`, `dedup`, `join`, `steps`), all of the same `active && block != null && outputStore == null` shape. | converts a run-time surprise into an authoring-time error. ⚠ The predicate mirrors `prepare()`'s condition **including `active`** — an inactive draft may be incomplete, and refusing one would break authoring in progress. 🔴 An **empty** block is not an authored one (the engine tests the parsed field, which an empty section leaves null); a plain presence check refuses a pipeline the engine arms happily, and a test pins it. 🔴 The rule was **mirrored into the offline mock in the same change** — a server-only rule would have left the mock *more lenient than the server*, the exact hole this codebase keeps getting bitten by — with a handler-level test for the **wiring**, not just the function. **The ratchet then caught `output_store` in the allow-list for real: 18 → 17.** |
| **3** UI looser than the validator | ✅ **SHIPPED 2026-08-31** — the row was right: `accepts` is served on every node type and the editor stored only `emits`. `edgeRefusal()` (`pipeline-graph.ts`) now mirrors `PipelineValidator`'s three edge checks (`ILLEGAL_EMIT` / `ILLEGAL_ACCEPT` / `ILLEGAL_PAIRING`) and is consulted at **both** creation paths — the Connect choke point `addEdge`, and G6 drag-to-draw via a `connectRefusal` predicate that returns `false` from `onCreate` so **no phantom edge is drawn**. The rel picker also drops rels the target refuses. | purely client-side; the server stays the authority. 🔴 The mirror must be exact **both** ways — three details carry that and each has a test: an **unknown type is exempt** (the server's checks sit inside `ifPresent`, so a served/plugin type must not be refused), **`on_commit` is exempt** (cross-pipeline, target is not a local node), and an outcome edge passes if the target accepts that rel **or accepts `data`** (the handler exemption). ⚠ The edge's **current** rel is always still offered — a stored graph may carry a pairing this deployment no longer allows, and dropping it would silently re-label on open. **Verified in the preview on live served data**, not only in unit tests: 29 rules reached the component, `acq→parse` and `route→sink` allowed, `sink→parse` refused as *"sink.persistent does not emit 'data'…"* and that text rendered as a toast. |
| **5** reference direction is inconsistent | ✅ **SHIPPED 2026-08-31** — `GET /pipelines/{n}/related` (`PipelineRelatedRoutes` + `com.gamma.service.PipelineRelated`), documented at `docs/okf/backend/control-plane/pipeline-related.md`. 🔴 **Neither half is a new traversal, and that is the design:** the inward half is `PipelineDependents.scan` verbatim (already serving `/impact`, a superset of D9, bounded with a true total) and the outward half is `PipelineConfig.referencedFiles()` — *not* re-derived from config keys, because a second reader of the same config is exactly the drift item 10 exists to stop. | one endpoint that also fixes 6, and is the honest answer to "what belongs to this pipeline". 🔴 **Completeness is decoupled from labelling:** a `kind` is claimed only where the registry directory makes it certain, and every other read file is still reported as `kind: "file"` — which matters today, because the parser picks up a **sibling mapping CSV by convention with no config key naming it**, and a key-driven closure would silently drop it. ⚠ It answers for a **registered** pipeline, so a file on disk that nothing loaded is a 404 here and a 200 on `/impact` — deployed vs on-disk, pinned by a test. ⚠ Its 503 is a **scan-root** dependency, not a write gate. ⛔ Connections excluded (D9) — and **measured**: that exclusion's map half is unfalsifiable from this route, since a Connection never enters `referencedFiles()` at all; the test pins the observable guarantee and says so. |
| **6** export/import is not a full set | ✅ **ALL THREE SHIPPED 2026-08-31.** **(a)** `buildExport` consults `GET /pipelines/{n}/related` per selected pipeline and merges the server's edges into the closure. 🔴 **A real hole, not re-plumbing:** the client derives a pipeline's edges from `nodes[].use` **alone**, so a companion bound by CONFIG KEY (`parsing.grammar: grammar/cdr`) was invisible — such a pipeline exported **without its grammar**, and the import landed a pipeline that could not parse. **(b)** `enrichment` is a bundle kind with its own `BundleSource`. **(c)** `mapping` ordered before `authored-pipeline`. | ⚠ (a) uses only the **outward** `references[]`, and only entries carrying a `ref` — `dependents` is what needs the pipeline, and following those would export every job that triggers on it; a `path`-only entry is a plain file, not a bundle item. Server edges **merge with** derived ones (neither is a superset: a node `use:` on an unsaved draft has no server counterpart), the fetch happens **only when dependencies are followed**, and it **degrades** so an older server cannot fail an export. ⚠ (b)'s load-bearing half is that an import **registers** — `EnrichmentService` has no mtime hot-reload, so writing the file alone is a silent half-import. 🔴 (c)'s `schema` half was first **REFUTED** then **REINSTATED**: the 2026-07-31 retirement (unification W1) was real but was reversed on 2026-08-05, so `schema` IS a referenced, executable kind and was ordered on 2026-08-31 — see BACKLOG BUNDLE-SCHEMA-1. ⚠ The intermediate test asserting its *absence* was inverted. That grounding found a live defect — the server does not honour the retirement and writes a schema item the UI and its mock expect skipped — filed as **BUNDLE-SCHEMA-1**. |
| **2** the verb catalogue authors a generic `parser` | ✅ **SHIPPED 2026-08-31**, both halves. `stepCatalog()` emits one entry **per format**, and New-pipeline **asks** (D3), writing `parsing.frontend` so the lift types the Step immediately. | 🔴 Grounding found the sharper statement of the bug: the generic `parser` is **already** `READ_COMPAT_ONLY`, so `isAuthorable` refuses it and the canvas palette never offered it — yet `step-types` published it anyway. **One vocabulary, two served catalogues, disagreeing**, and an author could only act on the intersection. A new contract test pins that every offered step type is one the editor may actually author. ⚠ The format is **required and never defaulted** — guessing `delimited` would author a format nobody chose and re-create the generic Step by another route, which is what D3 weighed. 🔴 **The preview caught a defect no unit test would have:** Create correctly refused without a format while **nothing appeared on screen** — `<inspecto-option-picker>` derives its error from its OWN `required` input and its OWN touched signal, so a host validating on submit reaches neither. The host renders the message; a test now asserts the rendered `[role="alert"]`. **Verified end to end in the preview:** choosing Fixed-width wrote `parsing.frontend: fixedwidth` and the graph came back with `parser.fixedwidth` — no custody-dialog detour. |
| **ledgers served from a database** *(status / batches / lineage)* | the seam already exists end to end — `StatusStore` with a `FileStatusStore` and a `DbStatusStore` that projects into `inspecto_status_*` on **DuckDB or Postgres**, and every read route already goes through the interface, so **the UI needs no change**. The engine keeps appending the CSVs as the durable write-ahead | ⛔ blocked on §13 D4, and the blocker is **freshness**: `syncStatus()` projects the audit **once, at boot**, so a DB-backed store serves a snapshot frozen at startup. Refresh it on commit (`BatchEventBus` already publishes the event), then the default is a one-word change. The bundle seam and the degrade path shipped 2026-08-31 |

### Wave 2 — gated on a decision

🔴 **GROUNDED 2026-08-31 — three of these five rows were not what the table said.** See
[`pipeline-waves-drain-plan.md`](pipeline-waves-drain-plan.md) §1 for the reads behind each verdict.

| Gap | Gated on | Verdict after grounding |
|---|---|---|
| ~~**7** no plugin can add a Step type~~ | ~~§11~~ | ✅ **CLOSED 2026-08-31.** 🔴 **All three clauses were wrong or spent.** A plugin step type was already authorable (`StepKindRegistry` admits a contributed kind at parse time; a CONTRIBUTED node type lowers to a `steps:` entry — open-dag §11 Stage 5) and the SPI was never unused (`packs-dev/{acme.masker,acme.reconcile,acme.redact}` + `tools/templates/{nodetype,processor,job}`). **"Classpath-only" was the real remainder and is now fixed:** both node registries gained an owner-keyed **pack overlay** and `JobPackManager` loads `PipelineNodeType`/`PipelineNodeExecutor` from a pack jar, so a node-type-only pack hot-deploys. ⚠ I first called this window-gated on the theory that hot types would drift the two committed contracts — **false**: node-attributes compares a static Java table, and step-types' own comment says it runs with no providers on the classpath *because* plugin types are additive at runtime. ⛔ A pack may not redefine a built-in TYPE; it may specialise a built-in verb's EXECUTOR. Details: `pipeline-waves-drain-plan.md` §2.2 |
| **11** `steps:` has no authoring surface | — | ✅ **SHIPPED — the row was wrong.** `<app-pipeline-step-cards>` is live and wired (`pipeline-editor.component.html:492`, `[editable]="canAuthor()"`), reached by the **Recipe** view toggle: one card per Step in chain order, insert-between, remove, **move up/down** (`moveStepInChain`, tested in `pipeline-graph.spec.ts`), `route` branches nested. Order is node order and lowers at `pipeline-editable.ts:757`. 🔴 The row's *description* — a comma-separated `processor` string plus a positionally-aligned JSON array — describes **row 12's** subject, not this one |
| **12** post-sync chain invisible | ~~a UX design~~ | ✅ **SHIPPED 2026-08-31.** `<app-job-chain-editor>` in `JobFormDialog`: when the Job Type declares both `processor` and `chain_config`, they leave the generated form and the author edits **ordered steps** — one row per step with its own config, accessible move up/down — emitting the two params **aligned by construction**. 🔴 Grounding found the value contract too: `chainConfigsOf` stringifies every config value, so a nested one is **accepted and corrupted** (`{"columns":["a"]}` → `"[a]"`), and a null NPEs. Both refused at authoring time; the engine half is **CHAIN-CONFIG-1**. Details: `pipeline-waves-drain-plan.md` §2.1 |
| **13** fan-in is canvas-only (D-6) | ~~§11~~ | ✅ **DECIDED, not open** — §13 **D6** keeps it canvas-only and says a token model is not a reason to overturn it speculatively. A closed row; no work |
| **1** `Batch` → `Consignment` | §0 (Phase 7) | mechanical but wide: 517 files, 39 `@PublicApi` types. ⚠ Do it as **one** commit with a codemod plus both contract regens — dripping it leaves the codebase in the split state indefinitely, which is the current complaint |
| **15** Phase 6's deletion half | the major-bump window | already decided; it just needs the window |

🔴 **Wave 2 is down to two rows, on one gate.** Rows **7**, **11**, **12** and **13** are
CLOSED (7 and 12 built 2026-08-31; 11 and 13 were already done and mis-recorded). **Rows 1 and 15
remain, both waiting on the major-bump window** — a release call, not a design gap: D5 names that
window for the rename, and row 15's gate protects deployed 3.x configs whose legacy read path it
deletes.

### Wave 3 — needs design before it can be estimated

| Gap | Why |
|---|---|
| **14** D-9 cross-Consignment dedup | it is **named, not designed**: no spec for where the ledger persists, the winner policy, or how the window advances. ⛔ Not schedulable until someone writes that; the "designed fast-follow" label is wrong |

### The honest shape of it

**2026-08-31: Waves 0 and 1 are DRAINED — 11 of the 17 items.** Wave 0 all four; Wave 1 items 10, 8, 3,
5, 6 (all three parts) and 2. Everything still open is **gated**, not merely unscheduled:

| Still open | Gate |
|---|---|
| ~~ledgers served from a database~~ | ✅ **CLOSED 2026-08-31.** The freshness gate was met (`runPipeline` refreshes after every triggered run; `StatusProjectionFreshnessTest`, falsified) and the operator then took the default: `OperationalDb.Family.STATUS` is now **`db`**, so every deployment serves status/batches/lineage from the projection — DuckDB on Personal, PostgreSQL where the bundle carries the driver and a URL. ⚠ Read surface only: the CSVs stay the durable write-ahead, and `openStatusStore` degrades to the file store with a warning rather than failing boot. 🔴 D4's recorded diagnosis was stale twice over — see §13 D4 |
| ~~**11** the `steps:` authoring surface~~ | ✅ **CLOSED 2026-08-31 — the row was wrong**: the Recipe view's step cards are that editor, live and wired |
| ~~**13** fan-in (D-6)~~ | ✅ **CLOSED 2026-08-31** — decided by D6, not open work |
| ~~**12** the post-sync chain's authoring surface~~ | ✅ **CLOSED 2026-08-31** — an ordered-step editor over the `consignment.process` Job's param pair. The engine-side value contract it exposed is **CHAIN-CONFIG-1** |
| ~~**7** node-type packs~~ | ✅ **CLOSED 2026-08-31** — owner-keyed pack overlay on both node registries; `JobPackManager` loads the two node SPIs. 🔴 The committed-contract gate I claimed for it did not exist |
| **1** `Batch`→`Consignment` | Wave 2 — **operator timing (D5)**. ✅ Inventory ready: `pipeline-waves-drain-plan.md` §2.3 — measured scope is **~185 files, not 517**; the data half is already done behind `Csv.LEGACY_HEADERS`; the codemod exclusions (JDBC `addBatch`, the concurrency sense, the ledger's prose name) are listed |
| **15** Phase 6's deletion half | Wave 2 — 🔴 **a RELEASE EVENT, not the vague "window"**: D-2 requires *converter + one flagged verification minor, then* deletion. The converter exists; **no minor has shipped** (nothing after 3.x has), so deleting now skips the verification window D-2 exists to provide. Not closable by code |
| **14** D-9 cross-Consignment dedup | Wave 3 — ✅ **DESIGNED 2026-08-31**, `pipeline-waves-drain-plan.md` §3: ledger = a new per-space `OperationalDb.Family` (default `duckdb`, never off) · winner = declared `order_by`, **required** once `scope:` is a window · window advance = a `MaintenanceJob` task aged by **event time, not mtime**. 🔴 Its sharpest risk is named there: a reprocess must **retract** that Consignment's keys or re-ingested rows are permanently suppressed |

🔴 **What the drain actually taught, and what a future reader should not have to re-learn:** of the
eleven rows, **six were mis-framed** — 4, 9, 16, 17 in Wave 0, then 6(c) and 2. A row's stated cause is
a hypothesis; grounding it first changed the work in every one of those cases, and in 6(c) it changed
the *answer* (the `schema` half was refused outright and became BUNDLE-SCHEMA-1). Two of the fixes —
gap 3's edge mirror and gap 2's create question — were only proved by driving the **preview**, and gap
2's real defect (a refusal with nothing on screen) was invisible to a green unit suite.

- The most valuable single item was the **key-coverage test** (10) — not because it fixes the drift,
  but because it stops it growing while the rest is decided. It has already earned that: declaring
  `output_store` for gap 8 made the ratchet fire on a real change, not a probe.
- **Wave 2 is where the leverage is**, and all of it waits on the two decisions in §0/§11.
- ⚠ **Sequence matters in one place:** 5 → 6(a). Everything else in Waves 0–1 is independent and can
  be taken in any order, or by different people.

---

## 13. Decisions — taken 2026-08-31

Each was posed with its cost and is now answered. ⚠ **A decision recorded without its reason gets
re-litigated**, so each carries why, and what would overturn it.

### D1 — Finish the approved amendment, then extend it. *(not: replace)*

The ELT amendment is approved and ~90% shipped. Replacing it strands a landed migration and re-opens
settled ground — the seven verbs, the Guarantees fold, park/drain — for no stated reason the amendment
cannot reach. **Finish Phase 6's deletion half and Phase 7's rename; treat §11's token model as the
next step, not a competitor.** *Overturned by:* a requirement the `trigger`/`steps`/`guarantees` shape
provably cannot express.

### D2 — Adopt the token vocabulary now; the runtime model with Phase 7.

Two-part, because the halves have very different costs. **Now (no runtime change):** say *token*, not
record-flow; move `success`/`failure`/`on_commit`/`gap` to Signals; collapse the four reject relations
into `reject:<reason>`. That closes §10 items 13 and 17 and shrinks 16, and it can start today.
**With Phase 7's major window:** the full model plus the Step SPI, since it breaks two committed
contracts. *Overturned by:* evidence that some Step genuinely needs rows in flight rather than a
reference — none found.

### D3 — New-pipeline **asks** for the parser format.

The recorded decision is that a parser is always format-specific; defaulting to delimited guesses for
the author and re-creates the generic Step by another route. One question at create time types the
Step immediately and removes the custody-dialog detour. ⚠ The palette already offers seven specific
parsers, so asking is consistent with what the author already sees.

### D4 — ⛔ Do **not** serve the ledgers from a database until the projection refreshes on commit.
### ✅ **CLOSED 2026-08-31** — the refresh landed, and the operator then flipped the default to `db`.

🔴 **The blocker is freshness, and my first diagnosis of it was wrong.** `CollectorService.syncStatus()`
projects the on-disk audit into the store **exactly once, at boot**. Nothing re-projects on commit, so
a DB-backed store serves a snapshot frozen at startup — a run triggered after boot reports **no
commits at all**. That is how `ControlApiMultiSpaceTest` failed: it read *its own* commit back as
empty. ⚠ **There was no cross-space leak** — `statusDbUrl()` resolves under the space root, so the
store is already per-space.

**The fix is well-scoped:** refresh the projection when a batch commits — `BatchEventBus` already
publishes the event — or write through. Once that lands the default is a one-word change, and
`ServiceStores.openStatusStore` is already written for it (single declaration, and it degrades to the
file store rather than failing boot).

> ✅ **SHIPPED 2026-08-31 — and D4's own diagnosis was stale in two places.** 🔴 **(1) "projects exactly
> once, at boot" is FALSE**: `PipelineScheduler.runOne` already refreshed once a poll cycle's last run
> finished, and its comment said so. The real gap was **the trigger paths** — a manual API run, a
> `notify`, an `on_commit` chained run and a dataset-write trigger all funnel through
> `CollectorService.runPipeline`, which refreshed nothing, so a triggered commit was invisible until the
> next cycle. The refresh is now hooked **there**, once per run, after the run and outside the run guard.
> ⚠ **Not** on `BatchEventBus`, despite this row suggesting it: the bus delivers **synchronously on the
> ingest thread** and fires **per batch**, so projecting the whole audit from a listener would put
> repeated DB work on the ingest path. ⚠ And **not** inside `runOne`, which deliberately batches the
> cycle's refresh — `runOne` does not route through `runPipeline`, so the two never double up.
> 🔴 **(2) The "rides along" worry does not occur**: `DbStatusStore` implements `BrowsableStore`, so
> `/db/catalog` already lists it as `ops:<id>` / `kind: "operational"`, and the file lives in the space's
> `duckdb/` dir, not `data/`. Pinned by `StatusProjectionFreshnessTest` (3 cases), **falsified**:
> commenting out the one call fails 2 of 3, and the file-store case correctly keeps passing.
> ✅ **And the default was then FLIPPED to `db`** (operator decision, same day, once the gate was met).
> That was deliberately kept as a separate call from the fix: it changes every deployment's read surface.
> ⚠ Reversible per deployment with `-Dstatus.backend=file`, and the on-disk audit remains the source of
> truth either way.

**Rides along:** the status DuckDB then appears as a *business* store in `/db/catalog`. An operational
store arguably does not belong there — filter it as `ops:*` already is.

*Shipped meanwhile:* the Standard/Enterprise bundle selects PostgreSQL when it carries the driver
**and** a URL is configured (the edition seam in the launch script). ⚠ Presence alone must never
select it — `verifySelectable()` fails the boot without a URL.

### D5 — Finish the `Batch` → `Consignment` rename, in Phase 7's window, as one commit.

Doing neither is the current state and is the worst of the three: one word means two things in two
layers. ⛔ **Not drip-fed** — 517 files and 39 `@PublicApi` types in a single commit with a codemod and
both contract regens, or the split persists indefinitely. ⚠ D2's token model makes this more urgent,
because the token *is* the Consignment.

### D6 — Keep D-6: fan-in stays canvas-only, for now.

A token model makes fan-in *expressible*; that is not a reason to overturn a standing decision
speculatively. **Revisit only if D2's runtime half lands**, at which point the authoring shape becomes
a DAG rather than a chain and the Document, compiler and editor all follow.

### D7 — One surface serves both the `steps:` chain and the post-sync lane.

Gaps 11 and 12 are the same missing thing: an ordered chain editor. Building two would leave the
post-sync lane a second-class citizen. ⚠ The visual design is genuinely open and needs UX; the
*constraint* is recorded so it is not designed as two.

### D8 — D-9 is **not schedulable**, and stops being called a fast-follow.

⛔ It is named, not designed: nothing states where the ledger persists, the winner policy, or how the
window advances. **Calling it "a designed fast-follow" is the actual defect** — it invites someone to
estimate work that has no design. It returns to the board only with those three answers.

> ✅ **The three answers were written 2026-08-31** — `pipeline-waves-drain-plan.md` §3. D8's condition is
> therefore met and D-9 **is schedulable**, as a build with a named correctness risk: a reprocess is a
> whole-Consignment supersede with **no row-level retraction anywhere**, so a ledger keyed only on "have
> I seen this key" would permanently suppress every re-ingested row. The ledger row must carry its
> producing `consignmentId` and retract in the same transaction as `registry.supersede`. ⚠ Scheduling it
> is still an operator call — the design does not schedule itself.

### D9 — "Related to a pipeline" = what it owns, what names it, and what an import needs.

`GET /pipelines/{n}/related` returns: its **schema(s)** and **mapping**; the **enrichments** and
**jobs** that name it (they point inward — §4); the **datasets** registered from its sinks; and the
**shared components it references** (grammar), because an import without them fails.

⛔ **Connections are excluded** — the operator's call, and the right one: they carry environment and
credentials, and a bundle that moved them would move a deployment's identity between spaces.

### D10 — Contract test first; single declaration later.

The largest structural problem (§10 item 10) does **not** start by declaring keys one at a time — that
never stops the drift growing. It starts with a sixth contract test asserting every key
`PipelineConfigParser` reads is declared in `ConfigSpecs`, landed with today's gap as an explicit
allow-list that then shrinks. ⚠ It fails today; that is the point. Five such contract tests already
exist, so this is the house pattern.

---

## 14. Where this lives — code and documents

**Code** — `inspecto-etl`: `PipelineConfig`, `PipelineConfigParser`, `DataTransformer`,
`PartitionWriter`, `Batch`, `ConsignmentPlanner`, `CommitLog`. `inspecto-engine`: `pipeline/`
(`PipelineLift`, `PipelineEditable`, `PipelineValidator`, `PipelineExecutor`, `BuiltinNodeType`,
`NodeAttributes`, `PipelineProjection`, `RecipeConverter`/`RecipeCompiler`), `inspector/`
(`CollectorProcessor`, `BatchProcessor`, `BatchIngestStrategy`), `consignment/`. `inspecto`:
`control/` (`PipelineGraphRoutes`, `ConfigReadRoutes`, `ConfigWriteRoutes`, `BundleRoutes`).

**Documents folded into this one** (still on disk as evidence; ⚠ not maintained in parallel):
`okf/backend/pipeline-graph/` — `pipeline-graph-design.md` (`design.md` was merged into it 2026-09-01) (144 KB,
the deep design incl. its §14 backlog), `live-execution.md`, `multi-location-ingest.md`,
`step-park-drain.md` · `okf/backend/engine/` — `node-types.md`, `plugins.md`, `stage1-architecture.md`,
`ingestion.md`, `branch-aware-ingest.md`, `consignment-status-flow.md`, `consignment-addressing.md`,
`consignment-concurrency.md`, `parser-plugins.md`, `output-sinks.md`, `transforms-seams.md`,
`unpack-stage.md`, `pipeline-test-run.md` · `superpower/` — `elt-final-amendment-plan.md` (114 KB),
`consignment-elt-architecture.md` (87 KB), `open-dag-pipeline-design.md`, `step-workbench-design.md`,
`mid-branch-transforms-design.md`.

⚠ **Retirement is deliberately NOT done yet.** Those files describe what is live; they should be
archived only as the rewrite replaces each area, not in advance of it.
