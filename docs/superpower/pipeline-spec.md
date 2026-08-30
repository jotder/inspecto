# The Pipeline — one consolidated specification

**Status:** working document for a redesign (2026-08-30). **This is the single place the Pipeline is
described.** It is written to be changed: the intent is to rewrite the subsystem from here.

**What this replaces.** Pipeline knowledge was spread across ~20 files and >500 KB, and no one of them
told you what a Pipeline *is*. This document states the whole subsystem as it stands today. The deep
files stay on disk as evidence and are mapped in §11; ⚠ **they are not maintained as a parallel truth
— when this document and one of them disagree, fix one of them, do not silently diverge.**

**How to read it.** §1–§4 are the authored surface. §5–§6 are the model. §7 is what actually runs.
§8–§9 are the seams. **§10 is the honest list of what is broken, missing, or contradictory** — the
part a redesign starts from.

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

## 11. Source map

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
