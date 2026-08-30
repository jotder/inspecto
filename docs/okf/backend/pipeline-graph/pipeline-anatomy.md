---
type: Concept
title: What a Pipeline actually is — files, graph, node rules, plugins, transfer
description: The concrete orientation — which files make a Pipeline, why the graph is DERIVED rather than stored, which Step types may connect, what a Java plugin can and cannot add, and what export/import really carries.
resource: inspecto-engine/src/main/java/com/gamma/pipeline/PipelineLift.java
tags: [pipeline, graph, config, plugins, bundle, orientation]
timestamp: 2026-08-30T00:00:00Z
---

# What a Pipeline actually is

The deep designs live in [`pipeline-graph-design.md`](pipeline-graph-design.md) and
[`design.md`](design.md). **This page is the orientation** — the concrete answers to "what am I even
looking at", written because the pieces are individually documented and nowhere assembled.

Two answers here surprise almost everyone, so they are up front:

- 🔴 **No file contains nodes and edges.** The graph is *derived* from the config on read and
  *flattened back* on write. Looking for a `nodes:`/`edges:` block is looking for something that does
  not exist.
- 🔴 **You cannot add a new node type with a plugin today.** The SPI exists and is unused; the node
  vocabulary is a closed enum. §5 says exactly what you *can* extend instead.

---

## 1. A Pipeline

> **Pipeline** — A named, authored **DAG of Steps** that turns raw source files into clean,
> partitioned Tables. The Pipeline's `wiring` *is* its graph. ⛔ never "Flow".
> — [`GLOSSARY.md:256`](../../../GLOSSARY.md)

A **Step** is one unit of work with a uniform contract: *Consignment in → Consignment out (+ rejects)*
(`GLOSSARY.md:264`). ⚠ **"Node" is the internal word; "Step" is the user-facing one** — the glossary
bans *Node* in UI copy.

⛔ **A Pipeline is in-motion; a Job is at-rest.** That line is binding (`GLOSSARY.md:280-288`): a Job
is **not** a Step and a Pipeline cannot nest one. They compose as producer/consumer over a shared
store — a Job fires on the Pipeline's `on_commit`, or binds by store name to a `sink.view`. Do not go
looking for a "run a job as a step" node; none has ever existed.

---

## 2. The files that make one Pipeline

One `*_pipeline.toon`, plus companions it references. Worked example —
`spaces/demo/config/orders/orders_pipeline.toon`:

| Section | What it configures | Required? |
|---|---|---|
| `name` / `id` / `active` / `version` | identity, and whether the Collector picks files up | `name` yes |
| `dirs.*` | poll · database · backup · temp · errors · quarantine · markers · status_dir · log_dir | `poll` + `database` yes |
| `collector` | the acquisition side — gap detection, duplicate check | no |
| `parsing` | which format frontend, and its grammar | no (but a runnable pipeline needs a parse story) |
| `processing` | threads, `file_pattern`, `schema_file`, `csv_settings`, `map`, batch/unpack/duckdb knobs | no |
| `steps[]` | **the ordered middle of the chain** — filter · join · dedup · summarize · route | no |
| `output` / `sinks[]` | format, compression, `filename_column`; `sinks[]` for several destinations | no |
| `output_store` | the store this pipeline writes to | no |

⚠ Only `name`, `dirs.poll`, `dirs.database` are spec-`required`
(`ConfigSpecs.pipeline()`). And `steps`, `collector`, `output_store` are **structural** keys owned by
the engine's pipeline compiler, not by the config-safety spec — validating a config does not validate
its wiring.

### Companions

| File | Holds | Bound by |
|---|---|---|
| `<name>_schema.toon` | `raw.fields[]` — the columns, selectors and types | `processing.schema_file` |
| `<name>_mapping.csv` | target ← source column rules | **two ways**: a sibling of the *schema* file, dual-read automatically (`MappingCsv.siblingFor`), **or** an explicit `processing.mapping_file` |
| `<name>_enrich.toon` | a Stage-2 at-rest recompute | the *enrichment* names the pipeline via `triggers.on_pipeline` — the pipeline does not name it |
| `jobs/<name>_job.toon` | a cron/event Job | the Job names its target; the pipeline does not |
| grammar component | reusable parse settings | `parsing.grammar` (inline `csv_settings` overrides it) |

⚠ **The suffixes are load-bearing.** `_pipeline.toon` and `_enrich.toon` are how the boot scanner
finds configs (`ConfigFileSupport.fileBase`); rename them and the config becomes invisible.

⚠ **A pipeline is addressed by NAME, and its file can live anywhere under the write root** — every
sample here sits in `config/<name>/<name>_pipeline.toon`. Resolution is registry-first with the
write-root convention as fallback (`ConfigFileSupport.resolveRegisteredConfigFile`).

⚠ **`name` vs `id`:** `name` is the display label; `id` is the stable identity. With no explicit `id`
the identity is *derived* from the name — so renaming without one is a **migration**, not an edit
(~140 call sites key on it, the commit log and acquisition ledger among them).

---

## 3. 🔴 The graph is derived, not stored

There is no `nodes:`/`edges:` block. A graph-shaped authoring file (`*_flow.toon`) did once exist and
its **authoring writes are retired** — grandfathered configs stay readable
(`pipeline-graph-design.md:1335`).

```
*_pipeline.toon  ──PipelineLift.lift()──▶  PipelineGraph  ──PipelineEditable.lower()──▶  *_pipeline.toon
   (the file)                              (nodes+edges)                                   (same file)
```

`GET /pipelines/{n}/graph/raw` lifts; `PUT /pipelines/{n}/graph` lowers. **The file stays the source of
truth**; the graph is a projection of it.

### Which config section becomes which Step

| Step | Comes from |
|---|---|
| `acq` (acquisition) | `collector:` + `dirs.poll` + `processing.file_pattern` |
| `gap` (control) | `collector.gap_detection` — attached by a `gap` edge |
| `parse` | `parsing:` + `processing.schema_file`/`csv_settings`/`schemas`/`segments` |
| `filter · join · dedup · summarize · route` | the ordered **`steps[]`** list |
| `map` | `processing.map` |
| `sink` | `output:` + `dirs.database`/`backup`/`temp`, or an entry in `sinks[]` |

Node ids are stamped by the lift (`acq`, `parse`, `gap`, `quarantine`, …), suffixed per branch.

### What a Step carries

`PipelineNode(id, type, name, description, config, use)`.

- `config` is **the raw config-file section verbatim** — which is why keys the graph does not model
  survive a round trip.
- `use` is a registry reference (`connection/<id>`, `grammar/<id>`) resolved at load.
- `name`/`description` are display only.

Per-type config vocabulary is **server-published** on `GET /pipelines/node-types`
(`NodeAttributes`). ⚠ Every key there **is** the engine's config key — there is no mapping layer, so
a key that is not byte-identical silently no-ops.

### Edges

An edge carries a `rel`. The full vocabulary (`PipelineRel`): `data` · `unmatched` · `gap` ·
`success` · `failure` · `on_commit` · `dropped` · `invalid` · `duplicate` · `route:<key>`.

⚠ **Declared ≠ emitted.** A lifted graph only ever contains `data`, `gap`, `unmatched` and
`route:*`. The others are declared in node types' out-sets and used by `ConservationCheck`'s loss
accounting — they are vocabulary, not edges you will see in a lifted pipeline.

---

## 4. Which Step types may connect

Each type declares what it **accepts** (in) and **emits** (out) in `BuiltinNodeType`.

| Category | Types | Accepts | Emits |
|---|---|---|---|
| SOURCE | `acquisition`, `adapter` | — (entry) | `data`, `gap`, `failure` |
| PARSE | `parser`, `parser.delimited/.fixedwidth/.asn1/.json/.text_regex/.xlsx/.plugin` | `data` | `data`, `unmatched`, **+ named `route:*`** |
| TRANSFORM | `transform.map/.filter/.select/.derive/.validate/.dedup/.dedup.marker/.join/.summarize/.split/.merge`, `enrichment` | `data` (`enrichment` also `on_commit`) | `data` (+ `dropped`/`invalid`/`duplicate`); `transform.route` **+ named `route:*`** |
| SINK | `sink.persistent`, `sink.materialized`, `sink.view` | `data` | `success`, `failure`, `on_commit` — **terminal for data** |
| CONTROL | `gap`, `alert`, `event` | outcome rels | — (leaves) |

**The rule, enforced by `PipelineValidator`:**

1. the source must emit the edge's rel → else `ILLEGAL_EMIT`
2. for a `data` edge, the target must accept `data` → else `ILLEGAL_ACCEPT`
3. for an outcome/route edge, the target must accept that rel **or** accept `data` → else
   `ILLEGAL_PAIRING`

⚠ That third clause is a deliberate **handler exemption**: anything that consumes rows can also
consume a reject or route stream. Plus `CYCLE`. Enforced at **save** *and* at **execution**; an
unregistered (plugin) type is warned, not blocked.

⚠ **The UI is looser than the server.** It offers rels from a published `emits` map and enforces
splice shape only — it does not check the target's `accepts`, so a canvas edge can be built that the
server refuses on save. The validator is the single authority.

⛔ `MULTI_SINK` is a **dead constant** — it stopped firing when `sinks:` became a plural block. Do not
document it as a live refusal.

---

## 5. 🔴 Adding a new Step type with a Java plugin

**You cannot, today.** The node vocabulary is the closed `BuiltinNodeType` enum. A two-part SPI exists
— `PipelineNodeType` (descriptor) and `PipelineNodeExecutor` (execution), each `ServiceLoader`-discovered
and layered *after* the built-ins so an edition can override one — but **no external provider exists,
and every shipped type is a `BuiltinNodeType`**. The pack-hosted `scaffold.mjs new step` path is
explicitly gated behind unshipped platform-services work.

What you **can** extend today, and what each actually adds:

| SPI | Adds | A new node type? |
|---|---|---|
| `StreamingFileIngester` | a way to load a format into Tables; referenced by FQCN in `processing.ingester` | ❌ |
| `ParserPlugin` | a new file-format parser in `GET /parsers`; may name an ingester | ❌ (configures the `parser` Step) |
| `ConsignmentProcessor` | a unit of Consignment-scoped work, selected by `id()` from a `consignment.process` **Job** | ❌ (a Job kind) |
| `JobTypeProvider` | a new **Job** type in the open `JobTypeRegistry` | ❌ |
| `enrichment` (`POST /enrichment`) | custom SQL as a partition-scoped recompute at rest | ❌ |

⚠ **Deployment is classpath-only** for these: `-cp "inspecto.jar:your-jar.jar"`. The one drop-in
directory that exists (`-Djobs.packs.dir`) is for **Job packs**, not node types or ingesters.

**So the practical answer to "add a new processing step":** express it as an existing verb in the
`steps:` chain (`filter`/`join`/`dedup`/`summarize`/`route`) or as an `enrichment`; if it is about
reading a format, write a `ParserPlugin`/`StreamingFileIngester`; if it is at-rest work, write a
Job type.

⚠ **If the node vocabulary ever does change, two committed contracts must be regenerated together** —
`node-attributes.contract.json` (`-Dnode.attributes.write=true`) and `step-types.contract.json`
(`-Dstep.types.write=true`), pinned by `NodeAttributesContractTest` / `StepTypesContractTest`.
Regenerating one and not the other turns the full reactor red after a green targeted run.

---

## 6. Export and import

Three different things share the word "export"; only the first moves configuration.

**Metadata bundle** — `POST /bundle/export` · `/bundle/preview` (read-only fit check: `new` /
`unchanged` / `drifted` / `unsupported`) · `/bundle/import` (upsert in dependency order). Envelope:

```json
{ "format": "inspecto-metadata-bundle", "version": 2, "exportedAt": "…", "sourceSpace": "…",
  "items": [ { "kind": "authored-pipeline", "id": "orders", "content": {…},
               "provenance": { "contentHash": "sha256:…" } } ] }
```

Supported kinds are `ComponentStore.WRITABLE_TYPES` plus `authored-pipeline`, `job`, `saved-view`,
`connection`. Apply order puts referenced kinds first: `connection → grammar → transform → sink →
dataset → query → widget → dashboard → reconciliation → authored-pipeline → job → saved-view`.

🔴 **Three sharp edges, all confirmed in code:**

1. **Export carries exactly the items the caller lists.** `BundleRoutes` does not walk a pipeline's
   companions — the closure is derived **client-side**. So an `authored-pipeline` export does *not*
   automatically bring its schema, mapping or grammar.
2. **`enrichment` is not a bundle kind at all.** It cannot travel in a bundle today.
3. **`schema` and `mapping` are supported but absent from the apply order**, so they sort *last* on
   import — after the pipeline that references them. Not dropped, not refused; just last.

**Not bundles, do not confuse them:**
- `GET /pipelines/{id}/document` — a **Markdown** projection for business sign-off, regenerated on
  demand, never stored as truth.
- **Grammar CSV** (`<pipeline>_parser.csv`) — a UI-side portable template for a *Grammar*, imported
  and exported from the Grammar editor. Unknown option keys are listed, never applied.

---

## Related

[`pipeline-graph-design.md`](pipeline-graph-design.md) (the deep design) ·
[`design.md`](design.md) (IR, lift, validator, executor) ·
[`../engine/node-types.md`](../engine/node-types.md) (the node-type seam) ·
[`../engine/plugins.md`](../engine/plugins.md) (plugin authoring) ·
[`../config/configuration.md`](../config/configuration.md) (every config key).
