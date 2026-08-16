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

Parse is a **family**, the way sink already was: the generic `parser` plus one type per format —
`parser.delimited` (P3a), `parser.fixedwidth` (P3b), `parser.asn1` (P3c) and the `parser.json` /
`parser.text_regex` pair (P3d), with the custom plugin type still to follow. Decided as
B6 — no generic parse node with format tabs, because each format owns its own grammar shape and
complexities. `isParserType` is `PARSER` ∪ `SUBTYPE_FRONTENDS.keySet()`, so a subtype joins the family by
declaring its spellings and nothing else.

* **The lift retypes only on an EXPLICIT `parsing.frontend`.** Delimited is also the parser's *implicit*
  default (`PipelineConfigParser` defaults the key), so retyping every bare legacy file would change the
  node type of everything already deployed on a mere read. A file that never says the word keeps the plain
  `parser` type until its author opts in. ⚠ This caveat is **delimited's alone** — every other frontend
  (fixed width, ASN.1, JSON, text/regex) is never implicit, so those configs already declare themselves and
  all of them retype.
* **A subtype answers to every spelling of its frontend, and the set is the source of truth.**
  `SUBTYPE_FRONTENDS` maps each subtype to its spellings and `subtypeForFrontend` inverts it; both the
  lift's retype and the lower's mismatch check go through it, so the comparison is by **subtype, not by
  string**. Fixed width has two (`fixedwidth`, `fixed_width` — `PipelineConfigParser#parseFixedWidth`
  reads both) and neither contradicts the other. ⛔ **A lifted node keeps the spelling its author wrote**:
  canonicalising `fixed_width` on a read would rewrite a deployed file on the next save, and the verbatim
  round-trip is the property `PipelineEditableTest` pins.
* **Lower stamps the CANONICAL frontend** (the first entry in the subtype's list) onto a palette-fresh
  node — the file must say the word its type means, or the next lift silently loses the identity. A
  lifted node already carries one, so the round-trip stays byte-verbatim.
* **A frontend the parser SYNTHESIZES a binding for makes that binding DERIVED** (P3c). `frontend: asn1`
  is sugar: `PipelineConfigParser#asn1PluginBlock` builds the `Asn1RecordIngester` wiring at load, so the
  lift reads a class back and presents `use: ingester/<fqcn>` on a node whose only *authored* home is
  `grammar/`. Refusing that ref would make every ASN.1 pipeline unsaveable — the AUTHOR-1(b) enrichment
  regression reached from the opposite direction — so `DERIVED_USE` maps `parser.asn1 → ingester/` and it
  is dropped in silence. ⚠ The rule generalises: **whenever a load-time synthesis invents something the
  lift can present, check what the save path will then think the author wrote.** ⛔ It does **not**
  generalise to the other subtypes: nothing synthesizes an ingester for a plain built-in, so an
  `ingester/` ref on `parser.json` / `parser.text_regex` / `parser.delimited` / `parser.fixedwidth` is an
  authoring mistake and refuses with `UNSUPPORTED_BINDING`.
* ⚠ **A record mode is not a node type.** Binary fixed width (`record: bytes`) lifts to
  `parser.fixedwidth` like any other — the type spans the format — but its field geometry lives in
  `processing.ingester_config` and is executed by `FixedWidthRecordIngester`, **not** by the
  `fixedwidth.fields[]` slices; `ComponentPreview` refuses to preview it and `DuckDbCsvIngester` excludes
  it from the native path. Operator decision 2026-08-16: it keeps the **dialog**, because the drawer
  pane's slice table would govern nothing. It needs no `ingester/` use-home either — binary reaches its
  ingester through the plain `processing.ingester` CLASS key, not a `use:` binding.
* Two refusals, both named: `PARSER_FRONTEND_MISMATCH` (a `parsing.frontend` contradicting the node's own
  type) and `MULTI_PARSER` (a second parser-family node — the flat file has one parse slot, and what used
  to be a silent last-one-wins became authorable once the palette offered two icons).
* Every subtype's `use:` home is `grammar/` **only**, not `ingester/`: a plugin-ingester binding on a node
  whose type already names its format is a contradiction, refused rather than half-honoured.
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
* **No `NodeAttributes` spec is published** for these types on purpose. Their grammars nest two levels
  (`parsing.delimited.*`, `parsing.fixedwidth.*`) while the `key__nested` spec convention has only ever
  carried one, and the UI drawer owns the form shape — a best-guess table that looks authoritative is what
  that class's doc warns against. Consequently `node-attributes.contract.json` / `step-types.contract.json`
  stay byte-unchanged as each subtype lands.
* ⚠ `BindKindHomeContractTest` has fired correctly **twice** — at `parser.delimited` and again at
  `parser.fixedwidth`. Its tripwire asserts the exact PARSE type list plus the derivation the UI's
  category-keyed picker depends on. The category stays *bindable* only because each new type arrived
  **with** a `grammar/` home; one added without one must flip `bindKindFor('PARSE')` to null, and this
  test is where that shows up first.

UI side: [Grammar configuration](../../frontend/features/grammar-config.md).

Supporting: `PipelineCodec` (graph ↔ TOON map), `PipelineStore` (persists authored `*_flow.toon`), `PipelineCompiler`
(round-trips a *lifted* graph back to a `PipelineConfig` map — authored plain-map nodes are not
round-trippable this way).
