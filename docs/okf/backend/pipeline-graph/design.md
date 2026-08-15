---
type: Concept
title: Pipeline Graph Design
description: The PipelineGraph IR, PipelineLift, PipelineValidator, PipelineExecutor, and the PipelineNodeType registry.
resource: inspecto-engine/src/main/java/com/gamma/pipeline/PipelineGraph.java
tags: [pipeline-graph, ir, validator, executor, registry]
timestamp: 2026-07-07T00:00:00Z
---

# Pipeline Graph Design

Authoritative doc: [`pipeline-graph-design.md`](pipeline-graph-design.md) (incl. the T-checklist;
moved from `docs/flow-graph-design.md`, 2026-07-16).

* **IR** — `PipelineGraph` (`inspecto-engine/src/main/java/com/gamma/pipeline/PipelineGraph.java`) is an immutable
  `record(name, active, nodes, edges)` consumed by the executor, validator, and visualiser. `PipelineEdge` carries
  a `rel` (defaults to `"data"`) distinguishing record-flow from control edges (`success`/`failure`/
  `unmatched`/`gap`/`on_commit`). `PipelineNode` carries `id`, `type`, `name`/`description`, a `cfg` map, and an
  optional `use` [component](../components/component-registry.md) reference.
* **Lift** — `PipelineLift.lift(PipelineConfig)` (`…/pipeline/PipelineLift.java`) converts a legacy
  `*_pipeline.toon` into a `PipelineGraph` with no file rewrite: linear `acq → [dedup] → parse → [filter] → map →
  sink` for single-schema; a `parser` fan-out with `route:<table>` branches for selectors/segments.
* **Validator** — `PipelineValidator.validateOrThrow(graph)` (`…/pipeline/PipelineValidator.java`) separates hard
  ERRORs from WARNINGs over the IR alone: DAG over `data` edges (`CYCLE`), no same-graph `on_commit`
  (`ON_COMMIT_SAME_GRAPH`), no dangling endpoints, no duplicate ids, ≥1 entry node, and relationship wiring
  against each node type's `emits`/`accepts`.
* **Executor** — `PipelineExecutor.execute(…)` (`…/pipeline/exec/PipelineExecutor.java`) walks `data` edges
  topologically from a seed relation in DuckDB: `RowShaper` compiles each `transform.*` node to SQL; each
  `sink` delegates to an injected `SinkWriter`; source finalisation is gated by a `BranchCommitCoordinator`
  and run via `SourceFinalize`; a `ProvenanceCollector` records per-(node, rel) row counts, checked by
  `ConservationCheck`.
* **Node-type registry** — `PipelineNodeTypes` (`…/pipeline/PipelineNodeTypes.java`) is built at class-load from
  `BuiltinNodeType` enum values, then layered with `ServiceLoader<PipelineNodeType>` providers (an edition can
  override a built-in). `catalog()` feeds the UI palette + the validator's wiring check.

## The parser family — per-format node types (2026-08-15, `6bc685cf`)

Parse is a **family**, the way sink already was: the generic `parser` plus one type per format, with
`parser.delimited` the first (fixed-width / ASN.1 / plugin follow). Decided as B6 — no generic parse node
with format tabs, because each format owns its own grammar shape and complexities.

* **The lift retypes only on an EXPLICIT `parsing.frontend: delimited`.** Delimited is also the parser's
  *implicit* default (`PipelineConfigParser` defaults the key), so retyping every bare legacy file would
  change the node type of everything already deployed on a mere read. A file that never says the word keeps
  the plain `parser` type until its author opts in.
* **Lower stamps `frontend: delimited`** onto a palette-fresh subtype node — the file must say the word its
  type means, or the next lift silently loses the identity. A lifted node already carries it, so the
  round-trip stays byte-verbatim (the property `PipelineEditableTest` pins).
* Two refusals, both named: `PARSER_FRONTEND_MISMATCH` (a `parsing.frontend` contradicting the node's own
  type) and `MULTI_PARSER` (a second parser-family node — the flat file has one parse slot, and what used
  to be a silent last-one-wins became authorable once the palette offered two icons).
* The subtype's `use:` home is `grammar/` **only**, not `ingester/`: a plugin-ingester binding on a node
  whose type says *delimited* is a contradiction, refused rather than half-honoured.
* ⚠ **`use: grammar/<id>` is read-supported but NEVER authored** (operator decision 2026-08-15). Every
  engine-side piece of it is deliberately unchanged — `resolveGrammarRef`, the `PipelineEditable`
  lift/lower translation, `UNKNOWN_USE_REF`, `PARSER_NO_SCHEMA`'s `grammarBound` branch, `USE_HOME`, and
  the `BindKindHomeContractTest` tripwire — because a hand-authored file may still carry one. What
  changed is upstream: a Grammar component is now a **Template** the UI copies from, so nothing writes a
  new binding, and opening a bound node in the editor migrates it to an inline copy. ⛔ Do not "tidy up"
  the binding read path as dead code — it is the compatibility half of a deliberate split. See
  [`plans-archive/grammar-templates-not-bindings-plan.md`](../../../archived-documents/plans-archive/grammar-templates-not-bindings-plan.md).
* Grouping by **category, not type string**: `PipelineCompiler.compile` and `PipelineDryRun` ask
  `PipelineNodeTypes.isCategory(t, PARSE)`, mirroring the sink family. A new parse subtype needs no edit
  there — but it *does* need its own `LOWERABLE` and `USE_HOME` entries in `PipelineEditable`.
* **No `NodeAttributes` spec is published** for the type on purpose. Its grammar nests two levels
  (`parsing.delimited.*`) while the `key__nested` spec convention has only ever carried one, and the UI
  drawer owns the form shape — a best-guess table that looks authoritative is what that class's doc warns
  against. Consequently `node-attributes.contract.json` / `step-types.contract.json` were byte-unchanged.
* ⚠ `BindKindHomeContractTest` fired correctly when this landed: `NodeCategory.PARSE` had held exactly one
  type, and its tripwire asserts the derivation the UI's category-keyed picker depends on. The category
  stays *bindable* only because the new type arrived **with** a `grammar/` home.

UI side: [Grammar configuration](../../frontend/features/grammar-config.md).

Supporting: `PipelineCodec` (graph ↔ TOON map), `PipelineStore` (persists authored `*_flow.toon`), `PipelineCompiler`
(round-trips a *lifted* graph back to a `PipelineConfig` map — authored plain-map nodes are not
round-trippable this way).
