---
type: Feature
title: ELT — user guide — Steps, Step configuration and Collect
description: Part 2 of 7 of ELT — user guide: Steps — the vocabulary, the flags and the authoring model; Configuring a Step — the definition drawer; Collect — the acquisition Step
tags: [feature, pipelines, editor, authoring, g6, graph, workbench, schema, mapping, transform, parsing-ingest, enrichment, onboarding, use-cases, user-guide, collectors, acquisition, connections, runs, operations, detail, breadcrumb]
timestamp: 2026-11-18T00:00:00Z
---

# ELT — user guide — Steps, Step configuration and Collect

Part 2 of 7 of [ELT — user guide](user-guide-elt.md) · previous: [Getting started and the Pipeline editor](user-guide-elt-getting-started.md) · next: [Parse and schema authoring](user-guide-elt-parse-schema.md).

## 3. Steps — the vocabulary, the flags and the authoring model

### 3.1 The authoring shape

Three principles decide what the authored shape can express, and they are easy to violate by adding a feature
that looks harmless:

1. ⛔ **Only `route` creates a user-visible branch.** A filter's dropped rows, a validation's invalid rows, a
   parse's unmatched rows and a dedup's duplicates are **reject streams routed by the Guarantee** — the
   author never wires a reject; they tune where rejects rest.
2. **A recipe is a TREE, and what it deliberately cannot express is a DAG.** The trunk stays linear and
   branches nest; fan-in fights the batch model and stays canvas-authored.
3. 🔴 **Sending the same data to many destinations is not routing.** The `sinks:` list covers it as **one**
   Step. `mode: clone` is only for branches that **diverge in processing** after the split — reaching for it
   to write two copies is the mistake this principle exists to prevent.

**The graph is derived, and three transformations read the file — they are not the same transformation**
(conflating them is the most common mistake in this area):

| Transformation | Direction | Used by |
|---|---|---|
| **lift** | file → graph | Every read: the editor's read-only view, list summaries, and the live ingest lane — *an internal representation only, never a file rewrite* |
| **toMap / lower** | file → editable graph → file | The **authoring round trip**, and the only path that writes |
| **stageTwo** | file → a source-chain-sink graph | The at-rest job runner. It ignores the ingest head entirely and has its own refusal set |

**The round-trip seam is `toMap` and `lower`, not `lift`.** `toMap` calls `lift` internally for topology
only, then **overwrites every node's config with the verbatim raw section that node owns**. `lower` walks
the posted graph and **deep-copies the existing map**, mutating it in place — so the persisted file is
never a value taken straight off the graph. That is what makes unmodelled keys survive, and it is pinned
end to end: `toMap` → decode → `lower(strict)` must equal the original raw map. Three round-trip tests each
guard a different failure: the **verbatim** test (unmodelled keys included); a **real-codec file** test,
because "a block can be perfectly modelled, perfectly parsed from a map, and still be unwritable or
unreadable as a file"; and a **reachable-path** test that drives the editor's actual write route to an engine
field, because a key can be read by the executor while being unreachable from the file the editor writes.
Backend detail: [Pipeline spec — model](spec-pipeline-model.md).

### 3.2 The Step-type vocabulary is the engine's, and only the engine's

**Since 2026-09-02 the palette renders the served Step Processor TAXONOMY when it is available**
(`GET /pipelines/processor-catalog`, `ProcessorCatalog` in `inspecto-engine`, pinned by
`processor-catalog.contract.json`): eight families (Collectors & Ingestion · Extraction & Format Parsers ·
Data Quality · Transformers & Dimensional Modeling · Analytics/Time-Series · Enrichment & AI/ML · Control &
Governance · Sinks), **122**<!--count:processors--> processors, **every one visible** *(⚠ this said 121 until 2026-09-09; `processor-catalog.contract.json` carries 122<!--count:processors--> — 54<!--count:processors-delivered--> delivered / 17<!--count:processors-partial--> partial / 51<!--count:processors-planned--> planned)*. A processor whose `addable` flag is true (it maps
onto an authorable node type) is an ordinary add/drag entry for THAT node type; a planned processor, or a
capability that is not a Step (a Collector guarantee, a job type, a Studio surface), renders **inactive** —
`role=button aria-disabled`, tooltip and accessible name carrying why, a `soon` / `via <capability>` chip —
and never emits `pick`. Family headers count `addable/total`. The node-type groups below remain the
fallback for a server that does not serve the taxonomy (404 → `paletteProcessors` stays `null`). The
taxonomy is the operator's product board (`EDITIONS.md` §Step Processors) rendered in-product; the engine's
executable vocabulary is still exactly what follows.

🔴 **The palette is served twice over, and its fallback is silent.** A node-type route always loads; the
taxonomy loads alongside it. When the taxonomy route fails, the error arm sets the value to nothing and says
nothing — no message, no log, no banner; the palette silently falls back to the plainer list, and the family
headers, the counts and the "planned" entries vanish. The three host error arms are now tested (a failed
processor-catalog leaves `paletteProcessors` **`null`**, not `[]`, so the node-type groups stay; a failed
node-type route empties the palette; a served-but-EMPTY verb list is treated as *not served*). Whether a
degraded palette should also fire the alert component is an open product decision ([plan](plan-elt.md)).

The palette is a **faithful port of the backend enum `BuiltinNodeType`** as served by
`GET /pipelines/node-types`: `acquisition` (SOURCE), `parser` and the `parser.*`
per-format family (PARSE), the `transform.*` family + `enrichment` (TRANSFORM),
`sink.persistent|materialized|view` (SINK), `gap` (CONTROL; `adapter`/`alert`/`event` deleted, operator, 2026-10-06) — ⚠ the `gap` here is a
CONTROL **node type**, unrelated to the `gap` edge relation below. The palette publishes **one entry per
SHAPE, not per verb** (which is why `transform` appears more than once), and ⚠ **`parse` and `map` serve
`attributes: []` deliberately** — each has a richer editor of its own, so a generic attribute spec there would
be a worse second way to author the same thing.

Edge `rel`s are `PipelineRel` constants, and in the token vocabulary they are **three outlets**: `data`
(default — the token continues), `reject:<reason>` over `unmatched`/`dropped`/`invalid`/`duplicate`, and
operator-named `route:*`. 🔴 **`success`/`failure` are NOT sink-emitted** — an earlier revision of this
line said so; `PipelineRel` records that they are "constructed nowhere … declared vocabulary with no
producer at all". They, with `on_commit` and `gap`, are **Signals, not edges**. See
[the token model](spec-pipeline-model.md).

⚠ **An invented vocabulary survives unnoticed because nothing rejects it**: `PipelineValidator`
flags an unknown type as a *warning*, `PipelineCodec` stores `config` as an unchecked map, and
`PipelineCompiler` silently drops an unknown-typed node. The palette once carried 13 invented types
for exactly this reason. `pipelines.handler.spec.ts` pins the palette to the enum.

### 3.3 Three different kinds of "supported" — `authorable`, `lowerable`, `executable`

Three orthogonal flags decide what a type can do, and ⛔ **they must not be collapsed**:

| Flag | Means | If absent |
|---|---|---|
| **authorable** | the palette offers it | it exists but cannot be added |
| **lowerable** | a save may persist it | the save **refuses** the node by name |
| **executable** | the executor has a branch for it | it saves fine and **throws at first run** |

- **`lowerable`** (per type, served) answers *"can a save lower this back to the flat
  `*_pipeline.toon`?"* It does NOT mean "the engine can run it". Conflating the two is how the
  palette once presented 20 equal options, 11 failing at Save *(⚠ that count is the pre-per-format era — `BuiltinNodeType` carried **30** constants as of 2026-09-09)* with 422 `UNSUPPORTED_NODE`. The
  palette dims non-lowerable entries; a grandfathered pipeline containing them gets a **warning
  banner, not read-only** — deleting the offending Step is the only repair, and read-only would lock
  it out.
- **`authorable`** = lowerable ∧ not read-compat-only (`PipelineEditable.isAuthorable`). A retired
  type (e.g. `transform.dedup.marker`, folded into the acquisition Step) stays `lowerable` so an old
  graph still saves, but is never offered again. The flag is optional on the client type: an older
  server omits it and the palette falls back to `lowerable`. Read-compat is a **lower-only**
  property — nothing lifts a retired type, so its `editableConfig` branch is dead the moment the
  lift changes. Exactly two types differ on the first two flags today, deliberately: filtering the palette
  on lowerability *"would force the choice between offering a node nothing should create and refusing to
  save a graph that legitimately still carries one"* — an editor opened before a fold still holds the old graph.
- ⚠ Before refusing a type in `lower`, check whether **lift emits it**: `PipelineLift` emits
  `transform.filter` for any pipeline with row filters, so refusing it would break open-then-save.

**Config shapes are published for a subset only.** **34**<!--count:node-types-with-attributes--> of the **49**<!--count:node-types--> built-in node types have a server-published attribute
table; the rest get the typed free-form editor **by documented design**, not by omission (§4).

### 3.4 Five registration points a new Step kind must reach

The commonly-cited list of four is incomplete, and the fourth item overstates one layer:

1. **The type enum and its attribute table** — makes the type *known* and describable. Nothing more.
2. **The executor's dispatch** — a hard-coded branch per built-in. A provider seam exists, but **only for
   contributed non-built-in types**; a new built-in without its own branch compiles, saves, and throws at
   first run.
3. **The lowerable set and the chain-kind map** — the first gates whether a save may persist the node at
   all; the second maps a chain node's type to the flat file's kind string on write.
4. **The runtime lift** — ⚠ **an ordinary new kind needs no code here.** The lift derives a chain node's
   type generically from its kind string. Only route-shaped kinds need extra bookkeeping for branch and
   sink fan-out.
5. 🔴 **The Recipe compiler's per-verb switch** — the point the four-item list misses. A Step wired
   through all of the above is **still not authorable through the linear Recipe surface** without a case
   here; an unmatched verb is refused as unsupported. A sixth, weaker surface controls whether the type is
   merely *discoverable* in the served verb catalogue.

**No single test fails when a type is added without full registration.** Every enum value is
auto-registered into the known-types map, so it is immediately visible to the catalogue, while the
lowerable set, the kind map, the attribute table and the executor's dispatch are four independently
maintained collections. A missed registration surfaces as a **named refusal at save** or an **exception at
execution** — never as a build failure. That is partly deliberate: several built-ins are excluded from
authoring and lowering forever.

**One node type is executable and unreachable.** ⛔ `transform.merge` has no authoring or persistence route
at all: it is absent from the lowerable set and from the verb catalogue **by decision**, alongside four
siblings; admitting them would silently reverse those decisions, and a graph carrying one is refused at save
with `UNSUPPORTED_NODE`. It is also the **only** node type whose input *count* comes from graph structure: the
executor takes its inputs as a caller-supplied list built from the node's live inbound edges and requires at
least two (`RowShaper.merge`); every other shapeable node gets exactly one input from a single edge. A join's
second input is *config*-derived — a reference resolved by name, not a second inbound edge — and a route's
multiple *outputs* are structural while its input follows the ordinary one-edge pattern.

**The pane dispatch is a hard-coded template chain, not a registry**: three bespoke arms for acquisition,
transform-SQL and the parser family, then a generic pane for every remaining kind. A fifth bespoke pane means
a fifth arm. ⛔ A single polymorphic entry with a discriminator does not work, because lowering dispatches on
the node's *type*, never on config content; ⛔ and nothing may be keyed on the *verb*: one verb appears twice,
so only the type is unique.

### 3.5 A spec `key` IS the engine's config key — there is no mapping layer

`AttributeSpec.key` is written **verbatim** into the Step's config; the app has no case-conversion
or key-mapping layer anywhere (the only transform is `component-model/flat-keys.ts`). A wrong spec
key produces a control persisting a value nothing reads, and nothing rejects it. Rules learned
closing D1–D9 (full history: `NodeConfigNameContractTest` docblock and git history):

- **Bind a key check to the runtime that executes the file this editor actually saves.** The editor
  writes the flat `*_pipeline.toon`; "read somewhere in `PipelineLift`/`RowShaper`" is too weak —
  `RowShaper` is reachable only from the authored-graph path, and `POST/PUT /pipelines/authored`
  are 405. Use **`PipelineEditable`**, not `PipelineLift.lift`, as the left-hand side: the legacy
  lift emits a *different vocabulary* (`includes` plural vs `include` verbatim).
- **A key with zero readers may be MISNAMED, not phantom** — check for a differently-named engine
  equivalent before deleting (upsert-by-key is `reference: {load: upsert, key: [...]}`, not
  `key_columns`). **Deadness is per Step type** (`mode` is real for `transform.route`, `table` for
  `sink.persistent`) — never a global dead-key list.
- **`transform.filter` has two filtering moments — never collapse them.** Pre-parse
  `include_*`/`exclude_*` (+ `filter_target_column`) inline into the `read_csv` SELECT over ONE raw
  physical column; post-parse `where` is SQL over the mapped, typed columns. `amount > 0` is
  inexpressible as a regex over unparsed text and vice versa. ⚠ `filter_target_column` lifts only
  alongside a pre-parse list.
- The name contract is checked by **driving the save**, not comparing strings:
  `PipelineEditable.toMap → fromMap → [set key] → lower → toToon → PipelineConfig.load → assert
  sentinel` (`NodeConfigNameContractTest`). Equality is only asserted where the Step's cfg IS the
  vocabulary; `acquisition`/`sink.persistent` carry whole raw blocks and the shared tables are a
  curated subset by design.

### 3.6 Step config specs are SERVED; the client table is a fallback

`GET /pipelines/node-types` carries `attributes[]` per type (authored in
`platform/inspecto-engine/.../pipeline/NodeAttributes.java`); the drawer prefers them and
`pipelines/node-attributes.ts` is the fallback until the catalog resolves.

- **A served empty array ≠ absent**: empty = the server says the type has no schema (honoured, never
  re-enables the client table); absent = catalog not yet answered (`??`).
- 🔴 **The Step vocabulary feeds a committed contract**:
  ⚠ **It covers 34<!--count:node-types-with-attributes--> of the 49<!--count:node-types--> built-in node types** — only those that carry an attribute spec. 🔴 That gap is the root of the five-way "node types" count: `BuiltinNodeType` is the roster and this contract is the subset with attributes — two different sets, one noun. Both figures are derived by `tools/check-doc-counts.mjs`.
  `inspecto/contracts/node-attributes.contract.json` (`NodeAttributesContractTest` +
  `node-attributes.spec.ts`), regenerated with `-Dnode.attributes.write=true`. (The second one,
  `step-types.contract.json`, was retired 2026-09-24 (`STEP-TYPES-DEAD-CLIENT-MIRRORS-1`) with `GET /pipelines/step-types`.) ⚠ `inspecto/contracts/`
  is byte-compared and `.prettierignore`-exempted by path. When a contract test fails, decide which
  side is wrong FIRST — regenerating moves the goalposts.
- ⚠ Serialization must use `LinkedHashMap` — `Map.of`/`Map.copyOf` are unordered and make the
  contract test fail at random.
- **A shared attribute table is correct per *block*, not per *node***: `COLLECTOR_ATTRIBUTES` serves
  two adopters that reach `collector:` differently, so a key can be real for one and unreachable for
  the other. Treat "declared but unreachable" as a per-adopter question; the fix is **derive the
  adopter's table (never fork it) or move the key to the Step the engine reads it from** — and ask
  where the engine reads the key before choosing ([§21 Collectors](user-guide-elt-collectors-runs.md#21-collectors-and-collector-configuration)).
- Sinks are specced only for the `output:` keys the backend reads; remaining `transform.*` types are
  **deliberately unspecced**, falling back to the typed extra-config editor (§4). Declared
  defaults **persist on save** even when untouched (product-confirmed: configs stay explicit).

---

## 4. Configuring a Step — the definition drawer

Every drawer-served kind — filter, route, join, summarize, record dedup, sinks, gap detection,
plugins, and `enrichment` with its companion write+register — configures in the right-dock
definition drawer via `pipeline-config-definition.component.ts`. (`NodeConfigDialog` is retired,
2026-08-21; only PARSE keeps a dialog path — see §6.)

- **Selection is configuration** (flipped THREE times, final by explicit operator ask 2026-08-22 —
  ⛔ do not change again without the operator): selecting a Step opens its config pane directly
  (`followSelectionIntoDefinition`); a DIRTY pane confirms before following. The slim summary
  survives only where the pane cannot serve: the read-only lens and dialog-custody parse Steps
  (auto-popping a modal stays obnoxious — those keep select-then-Configure). Palette add opens the
  new Step's pane. ⚠ `inspectorSummaryNode` was hard-wired to `null` from `57b8e4b67` until
  2026-09-25, so clicking a custody Step (e.g. the format-less `parser` Catalog ▸ Onboard Stream
  scaffolds) left the idle hint up; it now summarises exactly the non-drawer kinds, with an
  **Open Grammar editor** button (PIPE-PROPS-CUSTODY-1).
- **Identity is on the page**: the inspector's `compact` identity strip renders above all definition
  panes — Name + Description as always-visible inputs seeded from the Step, committed on blur via
  `rename` (a no-op blur emits nothing). ⚠ All panes pass the Step's own `name`/`description`
  through their build explicitly — the Step is rebuilt from scratch, so omitting them DELETES the
  name on every apply. `renameSelected` also patches the open draft.
- **Apply is armed by an edit** — each pane feeds `dirtyChange`; deriving dirty "on interaction"
  has failed twice (a derived output schema not counted; mapping edits not counted at all — the
  Load pane emitted on `form.valueChanges`; programmatic seeding ends `markAsPristine`).
  🔴 A spec for dirty-arming must drive the DOM: `setValue` does not mark a control dirty, so a
  programmatic test passes against the broken build. Apply is an **in-memory patch** (plan D2);
  Save writes the pipeline.
- **Unmodelled keys render in a typed editor**, not a generic key/value grid:
  `pipeline-extra-config.component.ts` shows each unmodelled key by name with a TYPE-matched
  validated control (boolean select · numeric · JSON textarea · text). `buildConfiguredNode` takes
  typed `extras`; pristine entries emit the ORIGINAL value reference so an untouched value (e.g.
  route `branches`) survives an apply byte-identical. Keys are not editable; adding one is offered
  only where the type has no schema. Since 2026-09-05 every definition pane's `Additional config` is a
  property-row list (label · value · pencil) and every Collector sub-form is flat — no Advanced gear, no
  chevron disclosure.
- **Flat ↔ nested bridging**: nested `collector:` sub-blocks are authored with `__` spec keys
  (`duplicate__mode`); the drawer runs `nestKeys` on save and `flattenBlock` on load. Both halves
  matter — without the load half a real nested block falls into the extra-config editor as a JSON
  string and, applied last, overwrites the schema form's own value. Save **deep-merges** each
  nested root over the Step's prior config (`mergeBlock`): several engine-read sub-keys have no
  `AttributeSpec` (`duplicate.algorithm`, `stability.size_checks`, `post_action.tags`, …), and a
  rebuild would silently destroy hand-authored TOON.
- ⚠ `connection` is stripped by both lift and lower (carried on `use:`, never mirrored in config) —
  the attribute **writes the binding**: seeded from `use` on load, written back to `use` on save. A
  Connection is NOT a `ComponentType` (`GET /components/connection` does not exist); the picker
  lives in the shared `<inspecto-collector-config>`.
- **Apply is blocked by two different things**, and only one is a server statement: a 422 from the describe
  route — deliberately the *only* server failure that may lock the pane, because an offline or unavailable
  response must never stop an author saving — and a purely local per-row problem count that involves no
  round trip at all (§8).

---

## 5. Collect — the `acquisition` Step

Renders the shared **`<inspecto-collector-config>`** over the shared `COLLECTOR_ATTRIBUTES` — mode
toggle (Local inbox | Connection | Dataset), Test connection, create-a-Connection, derived
`collector.connector`. Full contract: [§21 Collectors](user-guide-elt-collectors-runs.md#21-collectors-and-collector-configuration) below; engine side:
[acquisition spec](spec-acquisition.md).

**Marker dedup lives on the acquisition Step** (file-grain duplicate detection is decided in the
poll cycle; `transform.dedup.marker` is retired read-compat). Config keys unchanged
(`processing.duplicate_check` + `dirs.markers`):

- `duplicate_check` is an explicit boolean, authoritative in both directions — presence of
  `retention_days` is NOT the switch (clearing a detail field must not silently disable dedup), and
  an explicit `false` must not fall through to a legacy marker Step. One statement of the rule,
  `PipelineLift.markerHome`, is called by all three readers.
- The switch's label states its grain — **Marker dedup (file)** — and its help names the record-grain
  alternative (the `dedup` Step, `transform.dedup`, at rest via `output_store:`): a builder read the
  bare "Marker dedup" as covering duplicate records, which pass through it by design
  (`DUPLICATE-CHECK-GRAIN-UNSTATED-1`, 2026-09-23). `processing.duplicate_check` is **file-grain**
  (marker/path-keyed): a re-emitted record passes.
- The marker keys are their own `MARKER_DEDUP` list, NOT part of `COLLECTOR_ATTRIBUTES` — they live
  in `processing:`/`dirs:` and are only borrowed by the Step; folding them into the block table
  would hand the other adopter fields it writes to a block nothing reads.
- ⚠ `lower` dumps the acquisition Step's config wholesale into `collector:` minus `ACQ_FOREIGN_KEYS`
  — a borrowed key missing from that set lands in the wrong block silently.
- ⚠ The group renders `tier: 'required'` + `required: false` (always visible, never mandatory) — as
  `optional` it sat behind the disclosure and rendered as a heading over nothing; only driving the
  preview showed it.

⛔ **A single collector, permanently** (2026-08-11): the list design was refuted by grounding — every
stateful acquisition subsystem is keyed on one durable collector id, so a list would force a ledger re-keying
migration to reach semantics that composition already provides.

---
