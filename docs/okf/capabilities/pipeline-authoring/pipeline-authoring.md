---
type: Capability
area: PIP
title: Pipeline authoring (PIP) — capability spec
description: The requirement-of-record and as-built specification for authoring a Pipeline — the derived graph and the token it passes, the round-trip seam, the five registration points a Step kind needs, the validation ladder and the one guard missing from it, the transform surface, the Step catalog and the editor's own shape. The first of the two PIP specs; execution is the sibling.
status: current
written: 2026-09-09
supersedes-rows: REQUIREMENTS §3.3 PIP-1 (this file corrects it, see §2); PIP-2..PIP-7 belong to the execution spec
---

# Pipeline authoring (`PIP`)

> **How to read this file.** §1–§2 are the *requirement of record*: where they disagree with
> `docs/REQUIREMENTS.md` §3.3 or an `docs/EDITIONS.md` row, **this file wins** and the disagreement is
> stated in place. §3 is the specification, §4 the dated decisions, §5 what is not built, §6 what was
> refused, §7 the pointers, §8 how it is verified.
>
> ⛔ **`PIP` is one requirement range and two specs.** `GLOSSARY.md` §14 fixes the split: **authoring** —
> the editor, the config contract, the Step catalog — is this file; **execution** — Run, Job, Trigger,
> Executable — is `okf/capabilities/pipeline-execution/pipeline-execution.md` (**not written yet** — the last slot). Of the seven rows in
> `REQUIREMENTS.md` §3.3, **only `PIP-1` is authoring**; `PIP-2` through `PIP-7` are the sibling's.
>
> ⚠ **This area has twenty-two current documents claiming authority and two active plans holding
> decisions of record.** This spec does **not** restate their mechanism — the capability index's own rule
> is that a capability doc which restates mechanism becomes a fourth copy of the truth. What it adds is
> the thing none of them has: **one number per fact, and the disagreements named** (§2).
>
> 🔴 **The single most important finding: author SQL is guarded when you preview it and when it runs, and
> unguarded when you save it** (§3.5). Nothing refuses it at save, and no test covers the gap.

## 1. Purpose & scope

This capability is **how a Pipeline comes to exist**. Not how it runs — how a person describes it, how that
description is validated, and how it survives a round trip through an editor without losing anything.

Two facts organise everything else, and both are decisions rather than consequences:

> **The graph is DERIVED, never stored.** No file contains nodes and edges. The flat `*_pipeline.toon` is
> the sole on-disk truth; the graph is a projection lifted from it and lowered back onto it.

> **A Step passes a TOKEN, not data.** A Step does not accept or emit rows — it emits control information
> that may carry a path to data. The `data` edge is a fiction: it expresses "this Step's output is that
> Step's input", which is a reference, not a flow of records.

**In scope**

* **The config contract** — the flat file's blocks, who declares each, and which keys are executable.
* **The derived graph** — lift, lower, and the verbatim round trip that protects unmodelled keys.
* **The Step catalog** — the thirty built-in node types, the processor taxonomy, and the difference
  between a type being *authorable*, *lowerable* and *executable*.
* **The registration points** a new Step kind must reach, and what fails at which layer when one is
  missed.
* **Author-time validation** — the ladder from shape to refusal, and precisely where it stops.
* **The transform surface** — the Record Transformer, the peer Fields and SQL views, and the reconciler.
* **Identity and lifecycle of the artifact** — id versus display name, save-as-template, rename.
* **The editor itself** — its shape, its panes, its palette, and what it blocks locally.

**Out of scope**

* **Everything about running** — the scheduler, Jobs, Triggers, Runs, ledgers, the ingest lanes and the
  at-rest job. Those are the execution spec's, including the fact that an authored `steps:` chain has
  **two** execution homes (§3.6 states the authoring consequence only).
* Grammar authoring detail — `okf/frontend/features/grammar-config.md` owns the Parse pane's internals.
* The processor taxonomy's per-row product status — that board is generated, and `EDITIONS.md` owns it.

## 2. Requirements of record

### 2.1 The one authoring row

| Row | Register says | Correction of record |
|---|---|---|
| **PIP-1** | Authored Pipeline DAGs, Steps = the **closed** `BuiltinNodeType` set, with **author-time validation** and a visual editor — `SHIPPED`, all editions | ⚠ **Three corrections.** (a) **"Closed" is superseded doctrine that happens to be true today.** `GLOSSARY.md` D0-B (2026-08-09) retired the closure: the real guarantee is compiler *totality* — a pipeline that parses is a pipeline that runs — which survives an **open** Step-kind registry. But that registry is unbuilt (no provider interface exists in any module), so the set is still closed in fact. Say "closed today, by an unbuilt registry rather than by design". (b) 🔴 **"Author-time validation" does not hold for an unknown node type** — the validator flags it as a **WARNING**, the codec stores `config` as an unchecked map, and the compiler silently drops an unknown-typed node. An invented vocabulary survives unnoticed. (c) 🔴 **Author SQL is not guarded at save** (§3.5). The row's own cell also reads `SHIPPED` and "is **not** shipped" in one sentence — the 2026-08-02 scope correction about sub-Pipeline nesting, which is right but reads as self-contradiction. ⚠ **Named for grep:** the store that keeps an unknown node's `config` as an unchecked map is `PipelineCodec` — until 2026-09-09 that name appeared only in `REQUIREMENTS.md`, so the class a next shift must open to fix (b) was unsearchable from here. |

The board rows are accurate and this spec keeps them: **CP-02** (graph editor + Recipe view, insert-between,
insert-into-branch, undo/redo, snapshots, save-as-template), **CP-03** (validate → save → arm → test-run →
run → replay) and **CP-04** (bundle export/import with dependency closure) are ✅ in all three editions.
One qualifier belongs on CP-04: the metadata bundle's authored-pipeline kind still targets the **retired**
graph-shaped store, so a canonical pipeline transfers only by the datasource archive or the client-side
bundle (§5).

### 2.2 One number per fact

Four counts in this area are each stated two or three ways across current documents. In **every case the
authoring document is the wrong one.** Measured from code and the committed contract on 2026-09-09:

| Fact | Measured | What the docs say |
|---|---|---|
| Built-in node types | **30**<!--count:node-types--> | 20 in the glossary (and its list predates the per-format parsers), 28 in the active plan, 20 in the editor page |
| Recipe catalogue | **16<!--count:step-types--> entries over 9 verbs**, and **no `map` verb** | the active plan says 9 entries and includes `map`, a verb deleted 2026-09-05 |
| Step processors | **119**<!--count:processors--> — **35**<!--count:processors-delivered--> delivered, **17**<!--count:processors-partial--> partial, 67 planned | ✅ all agree since 2026-09-09 — the editor and mapping pages said 121 and were corrected; ⚠ this cell still read "both say 121" until 2026-09-09. Now derived: `tools/check-doc-counts.mjs` |
| Transform function catalogue | **23 in 7 categories** | the editor page says 24; the mapping page says both "~20" and "24", in one file |
| Live "too many of a kind" refusals | **2** — one for a second parser, one for a conflicting map config | two documents cite refusals for a second join and a second sink; **neither constant exists** |

The last row is the instructive one. The deleted constants survive only inside a Javadoc paragraph
explaining their own removal — and that paragraph is the best statement of the design in the area:

> A count is not what should constrain a pipeline — whether a step accepts its neighbours is.

A guard against this class already exists and is the pattern to copy: one contract test **re-parses the
executor's own source text** to catch a constant going stale relative to the code it claims to describe.

### 2.3 The one key whose documented status is contradictory

🔴 **`fields[]` is documented both as inert and as executable.** The config-key reference says it is an
authoring artifact **the engine never reads** and "must not be added to the contract"; the mapping page
says the engine reads only the SQL and `fields` rides along opaquely; the editor page says it is a **live
executable contract**.

**The code is unambiguous: it is executable.** The executor's map-node key set includes it, and the
projection test returns true when a node carries record fields — so `fields[]` *is* the mapping that runs.
The code comment beside the allow-list says why this matters:

> a key that becomes executable without joining this allow-list is silently dropped on save, which is the
> failure both constants exist to make impossible

This is not a harmless doc bug. **The reasoning that `fields` is inert is what produced the live drift**
recorded in §5: the client mirror of the authored-key set carried two keys where the server carries three,
and the missing one was `fields`. It was the fourth hand-mirrored map in this repository to drift, and
✅ **it is fixed and pinned as of 2026-09-09** — `MapNodeKeyContractTest` parses `pipeline-editable.ts`
and holds both sets against the Java ones, so the mirror cannot drift silently again.

## 3. Specification

### 3.0 The authoring shape, and the Pipeline Document

*(Distilled 2026-09-10 (Sprint 7.6) from the three archived plans; this was their only home.)* Three principles decide what the authored shape can express, and they are
easy to violate by adding a feature that looks harmless.

1. ⛔ **Only `route` creates a user-visible branch.** A filter's dropped rows, a validation's invalid rows, a
   parse's unmatched rows and a dedup's duplicates are **reject streams routed by the Guarantee** — the
   author never wires a reject; they tune where rejects rest.
2. **A recipe is a TREE, and what it deliberately cannot express is a DAG.** The trunk stays linear and
   branches nest; fan-in fights the batch model and stays canvas-authored.
3. 🔴 **Sending the same data to many destinations is not routing.** The `sinks:` list covers it as **one**
   Step. `mode: clone` is only for branches that **diverge in processing** after the split — reaching for it
   to write two copies is the mistake this principle exists to prevent.

**The Pipeline Document** (defined in `GLOSSARY.md`) is a generated review artifact, and its contract is what
keeps it honest:

- **Per-Step sections** — collect, parse, map, dedup, transform, route, summarize, sink — each with its own
  stated contents.
- **Worked examples are produced by the shipped dry-run machinery running the PRODUCTION Step logic** over a
  bounded sample, so the document cannot demonstrate behaviour the engine does not have.
- **Sign-off binds to a fingerprint** over the recipe *and* every `use:`-referenced component, so an approval
  that predates the current config is detectable.
- **The review round trip is export → edit → upload → validate → dry-run preview diff old-vs-new on the same
  sample → apply**, which is what stops the review artifact and the runtime artifact diverging.
- ⛔ **Never stored as truth.** It is a projection of config, always.
- ⚠ **Its real risk is being mistaken FOR the truth** — a signed-off document reads like a contract while the
  config keeps moving. The fingerprint is the whole mitigation: it makes staleness cheap to surface.

⚠ **`parse` and `map` serve `attributes: []` deliberately** — each has a richer editor of its own, so a
generic attribute spec there would be a worse second way to author the same thing. And the palette publishes
**one entry per SHAPE, not per verb** (which is why `transform` appears more than once). Both rules lived
only in a contract test's comment until they were written down.

### 3.1 The artifact and its projection

One flat file per pipeline is the truth. Three transformations read it, and **they are not the same
transformation** — conflating them is the most common mistake in this area:

| Transformation | Direction | Used by |
|---|---|---|
| **lift** | file → graph | Every read: the editor's read-only view, list summaries, and the live ingest lane. Documented as *an internal representation only, never a file rewrite* |
| **toMap / lower** | file → editable graph → file | The **authoring round trip**, and the only path that writes |
| **stageTwo** | file → a source-chain-sink graph | The at-rest job runner. It ignores the ingest head entirely and has its own refusal set |

**The round-trip seam is `toMap` and `lower`, not `lift`.** `toMap` calls `lift` internally for topology
only, then **overwrites every node's config with the verbatim raw section that node owns**. `lower` walks
the posted graph and **deep-copies the existing map**, mutating it in place — so the persisted file is
never a value taken straight off the graph. That is what makes unmodelled keys survive, and it is pinned
end to end: `toMap` → decode → `lower(strict)` must equal the original raw map.

Three round-trip tests exist and each guards a different failure:

* the **verbatim** test — unmodelled keys included;
* a **real-codec file** test, because "a block can be perfectly modelled, perfectly parsed from a map, and
  still be unwritable or unreadable as a file";
* a **reachable-path** test that drives the editor's actual write route to an engine field, because a key
  can be read by the executor while being unreachable from the file the editor writes.

### 3.2 Thirty node types, and three different kinds of "supported"

The built-in vocabulary is thirty types across source and parse, transform, sink and control. Each carries
its category, label, what it accepts and what it emits — and the accepts/emits pair is explicitly
**token vocabulary, not a record-flow contract**.

Three orthogonal flags decide what a type can do, and ⛔ **they must not be collapsed**:

| Flag | Means | If absent |
|---|---|---|
| **authorable** | the palette offers it | it exists but cannot be added |
| **lowerable** | a save may persist it | the save **refuses** the node by name |
| **executable** | the executor has a branch for it | it saves fine and **throws at first run** |

Exactly two types differ on the first two today, deliberately. The reason is recorded in the code and is
worth keeping: filtering the palette on lowerability *"would force the choice between offering a node
nothing should create and refusing to save a graph that legitimately still carries one"* — an editor
opened before a fold still holds the old graph.

**Config shapes are published for a subset only.** Eleven of the thirty have a server-published attribute
table; the other nineteen get the typed free-form editor **by documented design**, not by omission.

### 3.3 Five registration points, not four

A new Step kind must be registered in five places. The commonly-cited list of four is incomplete, and the
fourth item overstates one layer:

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

### 3.4 One node type is executable and unreachable

⛔ **`transform.merge` has no authoring or persistence route at all.** It is absent from the lowerable set
and from the verb catalogue **by decision**, alongside four siblings; admitting them would silently reverse
those decisions. A graph carrying one is refused at save. So it is not "stuck on union" — it is
unreachable, and declaring config attributes for it would hand a config pane to a node that cannot be
saved.

It is also the **only** node type whose input *count* comes from graph structure. The executor takes its
inputs as a caller-supplied list built from the node's live inbound edges and requires at least two; every
other shapeable node gets exactly one input from a single edge. A join's second input is *config*-derived —
a reference resolved by name, not a second inbound edge — and a route's multiple *outputs* are structural
while its input follows the ordinary one-edge pattern.

That distinction is why the step workbench's input picker was refused on grounding: there are no input keys
to write, so a picker would have written a key nothing reads. **Edges stay authored on the canvas.**

### 3.5 The validation ladder, and the guard missing from it

A save climbs five gates in order:

1. **Shape** — a decode failure is a 400.
2. **Graph structure** — cycles, dangling edges, unresolvable references. ⚠ **Only an ERROR blocks**; an
   unknown type and an unaudited SQL step are WARNINGs and never block.
3. **Join references** — an unresolvable by-name join is a 422.
4. **Lowering** — completeness refusals in strict mode, plus structural refusals, returned as a **named
   `refusals[]`** with a 422.
5. **Specs and safety** on the lowered map — a 422 on any ERROR.

🔴 **The transform guard is not in that ladder.** The allow-list that refuses data-definition statements,
multiple statements and file-reading functions runs at the **describe preview** and at **execution** — ten
call sites across the engine, the query surfaces and the intelligence tools — and **zero times on any save
path**. The graph-save route does not reference it; neither does the lowering code, nor the Recipe
compiler, nor the spec validator.

So a hand-written SQL string that reads a file from disk, or issues a data-definition statement, **saves
cleanly** and only fails later. The honest characterisation matters: because the guard *is* enforced at
execution, this is **fail-late, not fail-open** — such a pipeline cannot run. But it can be authored,
saved and armed, and its refusal arrives at the worst moment. Blank-only is the sole SQL check at save.
**No test blocks this anywhere.**

Three other things can be saved and only fail at run: a destination list naming more than one target
(refused when loaded for execution), a route with no default branch (refused at arming), and a join whose
reference resolves by name but whose columns are the dry run's question.

### 3.6 The transform surface

One Step shapes a record. The executor dispatches a transform node on whether it is a **projection** —
which is decided by the node carrying record fields, not by its type — sending it either to the projection
path or to the verbatim-SQL path.

**The projection path is the Record Transformer.** It prefers an authored column list outright; otherwise
it derives one from a schema, from `fields`, or from legacy rules. ⚠ **An authored column list beats both
`rules` and `fields` when several are present**, and nothing refuses authoring more than one — the only
collision refused is a column list together with a declared mapping file.

**The legacy spelling is bridged at read time, permanently.** A stored legacy rule block is converted to
the field shape on **every read** and is never rewritten to disk. That read bridge is why keeping the old
read path costs nothing, and why deleting it was refused: the migration is by tool, never by breaking
stored schemas.

**Fields and SQL are two peer views of one Step.** The default view is Fields; a node that already carries
hand-written SQL with no fields opens on SQL instead, because hand-written SQL opens as what the author
wrote. Saving always writes the SQL, and writes fields only when the Fields view is active. **Hand-written
SQL is always saved back**, whether or not it reconciles. The reverse reconciler accepts only a flat
projection; anything with a filter, a join, grouping or a common table expression merely **disables the
Fields tab with a reason** and never blocks saving.

⚠ **Do not "clean up" `fields` off a transform node** — that deletes the author's grid, and on the
projection slot it deletes the mapping.

**The projection slot is identified by its id, never by its type.** The slot the lift fills between parser
and sink is always a transform-SQL node whose id follows a fixed grammar; the id grammar is the
discriminator. This is what replaced the deleted map type — **map is a slot, not a step**.

### 3.7 The editor

`/pipelines` is a **single route with no guard**. View, editor and topology are internal modes of one
component, selected by query parameters. Authoring is gated *inside* the component by a capability check
that disables and hides individual affordances — new, save, delete — while **the screen itself stays
reachable by every persona view**. This is independent confirmation of the settled rule that a persona view
never hides a screen.

**Node type dispatches to its pane through a hard-coded template chain**, not a registry: three bespoke
arms for acquisition, transform-SQL and the parser family, then a generic pane for every remaining kind. A
fifth bespoke pane means a fifth arm. That is deliberate — ⛔ a single polymorphic entry with a
discriminator does not work, because lowering dispatches on the node's *type*, never on config content.
⛔ And nothing may be keyed on the *verb*: one verb appears twice, so only the type is unique.

**The palette is served, twice over.** A node-type route always loads; a richer processor taxonomy loads
alongside it and renders every processor with its family, its counts and its undelivered entries greyed out
with a reason. 🔴 **When the taxonomy route fails, the error arm sets the value to nothing and says
nothing** — no message, no log, no banner. The palette silently falls back to the plainer list, and the
family headers, the counts and the "planned" entries vanish. The stored value cannot distinguish *failed*
from *not yet loaded* from *loaded empty*. The fallback's *rendering* is specced; **the host error arm that
produces it is not** — so the tested half is the symptom and the untested half is the cause.

**Refusals land in a dock, not a toast.** A 422's named refusals and findings are all rendered into a
persistent validation dock rather than a first-only toast. A 503 is special-cased to an explained
read-only message naming the missing write root.

**Apply is blocked by two different things**, and only one is a server statement: a 422 from the describe
route — deliberately the *only* server failure that may lock the pane, because an offline or unavailable
response must never stop an author saving — and a purely local per-row problem count that involves no
round trip at all.

### 3.8 Identity and lifecycle

Three tiers, by cost:

* **Relabel** is free — display name only. It first stamps the stable id, because identity is otherwise
  re-derived from the name and is baked into the filename, the commit log, the audit exports and the
  ledger.
* **Save-as-template** is free and isolating: the config is copied, marked as a template and inactive
  (refused together as active), and **every environment binding is repointed into a template directory**,
  with the schema copied rather than referenced. Companions are deliberately not copied — promoting a
  template is an explicit act, not a side effect. A template is registered normally; runnability is gated
  in three places instead, because an unregistered template would be invisible and unpromotable.
* **Rename** is the full identity migration — config, commit log, audit exports, ledger, status mirror and
  every dependent config — bracketed by a journal and resumable, because the steps span three failure
  domains and are not one transaction.

⛔ **The id is deliberately not opaque.** It names the config file, the commit log, the ledger and the
catalogue entry: *a random id makes an operator's config directory unreadable.* Decoupling it is a product
decision with an on-disk cost, not a cleanup.

⛔ **A single collector, permanently.** The list design was refuted by grounding: every stateful
acquisition subsystem is keyed on one durable collector id, so a list would force a ledger re-keying
migration to reach semantics that composition already provides.

## 4. Decisions (dated one-liners)

| Date | Decision | Who |
|---|---|---|
| 2026-08-02 | **Sub-Pipeline nesting ruled out by design.** In-motion and at-rest are a binding line, so an at-rest operator cannot be an in-motion node; they compose as producer and consumer over a shared store | product |
| 2026-08-09 | **The closed vocabulary is superseded (D0-B).** The real guarantee is compiler *totality*, which survives an open Step-kind registry where every registered kind declares that it lowers or executes; a kind with neither is refused fail-closed at arming | engineering |
| 2026-08-11 | **A single collector, permanently** — the list design was refuted by grounding | engineering |
| 2026-08-11 | **Multiplicity resolved by an ordered chain**, and the "too many of a kind" refusals deleted with it — a count is not what should constrain a pipeline | engineering |
| 2026-08-14 | **An authored reference has a home for two node kinds only**; on any other kind it is refused by name. The bug this fixed: the picker was keyed on category rather than type, so lowering dropped every other reference **in silence** while the save returned success | engineering |
| 2026-08-15 | **A load-time synthesis makes a binding derived**, and derived bindings are dropped silently — refusing them would make every binary-decoder pipeline unsaveable. The general rule: whenever a load-time synthesis invents something the lift can present, check what the save path will then think the author wrote | engineering |
| 2026-08-15 | ⛔ **A Grammar Template is a copy source, never a binding** — you copy from it, you never bind to it. Reverses the earlier store contract; the bound form stays readable but is never authored, and opening such a Step migrates it to an inline copy on save | operator |
| 2026-08-16 | ⛔ **No single drawer over schema, mapping and table** — it would span three Step types and break the one-node-in, one-node-out contract every drawer holds | operator |
| 2026-08-17 | **A new pipeline carries a stable id from birth**, stamped by one scaffold behind both create surfaces so neither can drift; the id is immutable once set | engineering |
| 2026-08-17 | ⛔ **The id is not opaque or minted** — it names the file, the commit log, the ledger and the catalogue entry | engineering |
| 2026-08-17 | ⛔ **The parser's fallback derivation is deliberately not narrowed** — narrowing silently re-keys every id-less pipeline on disk. Migrating the legacy path is a data migration, not an edit | engineering |
| 2026-08-17 (**A6**) | **Export lives in one always-visible home, un-gated** — a business-persona operator handing a config to support is the point | operator |
| 2026-08-13 | **Pipeline-level settings are their own route**, not the graph save, because the editable model deliberately never models non-node keys | engineering |
| 2026-08-29 | ⛔ **`authorable` and `lowerable` stay two flags** — collapsing them forces a choice between offering a node nothing should create and refusing to save a graph that legitimately still carries one | engineering |
| 2026-08-29 | **Providers layer last so an edition may override a built-in** without forking core | engineering |
| 2026-08-31 (**D2**) | **A Step passes a token, not data** — adopt the vocabulary now, the runtime model later. *"This is not a new direction. It is what the engine already does, and what the type system contradicts."* Overturned only by evidence some Step needs rows in flight — none found | operator |
| 2026-08-31 (**D1**) | **Finish the approved amendment, then extend — ⛔ do NOT replace it.** The meta-decision that legitimised every amendment phase; without it a future reader re-opens "redesign or finish?" from scratch. Overturned only by *a requirement the `trigger` / `steps` / `guarantees` shape provably cannot express* | operator |
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
| 2026-09-04 (same day, second flip) | 🔴 **The grid returns, with a typed function catalogue replacing the verb enum** — *"mapping with functions and parameters is not realistic"*: a raw SQL box is not an authoring surface for a non-technical user, and the legacy rule grid is not a mapping model either. ⚠ **The SQL-only middle state is not current** | operator |
| 2026-09-04 | **The Record Transformer** — one Step that sanitizes, casts, renames and computes, one row per output field over a typed catalogue, generating the projection it saves. Folded from three catalog labels plus type coercion; the schema *contract* could not fold, because the declared column and target type are the cast-failure audit's denominator | engineering |
| 2026-09-04, verified 2026-09-06 | **Parse does not drop columns — Transform owns exclusion.** The reason is cost, not tidiness: excluding in Parse edits the schema, which the compatibility gate can refuse to undo; excluding in Transform is a clause, reversible for free | engineering |
| 2026-09-05 | 🔴 **The map type is deleted; map is a slot, not a step** — the id grammar is the discriminator, never the type. A stored legacy rule block still loads, permanently | engineering |
| 2026-09-05 | **Fields and SQL are peer views of one Step**, and the earlier "never parse SQL back into rows" rule is superseded by a bounded reconciler | engineering |
| 2026-09-05 | **`fields` must be lowerable because it is executable** — a key that becomes executable without joining the allow-list is silently dropped on save | engineering |
| 2026-09-06 | **A Parse Step is three things and the drawer is its one home** — a grammar, the schemas it emits, and its other properties, including a dangling binding | engineering |
| 2026-09-06 | **Paths derive from the slug id too** — one identity for id, file and directories | engineering |
| 2026-09-06 | **Enrichment stays first-class; the retirement narrowed to nothing** after grounding found the one committed fixture does not migrate. ⛔ Not a pending rename — do not re-file it as one | operator |
| 2026-09-07 | **The workbench field list is capped at fifty rendered rows** with the filter always visible — 🔴 the cap applies to what is *rendered*, never to what is *searched*, because a cap that narrowed the search would hide columns with no way to reach them | operator |
| 2026-09-07 | ⛔ **The merge and join input picker is refused** — there are no input keys to write; a picker would have written a key nothing reads. Edges stay authored on the canvas | engineering |
| 2026-09-07 | ⛔ **Reference detection may not use a SQL parser** — that would be a second implementation of the dialect the engine owns. 🔴 And it cannot use a word boundary, because an underscore is a word character | engineering |

## 5. Not built

### 5.1 Tracked and actionable

* **`AUTHORING-REDESIGN-1`** — the open letters. A structured table over the SQL for filter and join
  editing (✅ its precondition was **discharged 2026-09-07**: the engine can hand back its own parsed
  statement as data on a sealed connection, pinned by a test — so this reads an engine-produced tree
  rather than re-implementing the dialect in the client); macros as a function registry, demand-gated;
  column metadata editing, which needs a backend home for metadata on a transform node first; and a
  per-row "resolves to" line, for which no host resolves a sample against an attribute spec.
* **The processor catalogue's eighteen partial rows** — each is a product decision, picked one at a time
  by name.
* **Test-mapping on a generic parser node** — only reachable where the parse node is per-format; ⚠ the row
  needs re-scoping before anyone builds it, because its gate is discharged.
* **A host pane for the report builder** — no viable existing pane; a new surface, not an adoption.
* **Selective bundle export and import for the canonical file** — the metadata bundle's authored-pipeline
  kind still targets the **retired** graph-shaped store.
* **AI drafting has no applicable component kind** — three kinds have no backend spec, and no low-risk
  slice survives; design first.
* ✅ ~~**The client mirror of the authored-key set has drifted**~~ — **FIXED + PINNED 2026-09-09.** It was
  the fourth hand-mirrored map here to do so and the first to end up held by a test: the Java set is the
  source of truth and `MapNodeKeyContractTest` parses the TypeScript to keep it there (§2.3).
* **Two author-facing messages name things that do not exist** — one warns that only three Step kinds may
  run inside a branch when the code's own set also contains the transform-SQL kind.
* **The open Step-kind registry** — gated, and it is what the retired closure was superseded *by* (§2.1).

### 5.2 `UNTRACKED` — found writing this spec, no board row exists

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `SPEC-STALEREF-1`, `SPEC-COUNTS-1`, `SPEC-GREENCELL-1`, `SPEC-GLOSSARY-1`, `SPEC-PLANSTALE-1`, `SPEC-ORPHANPAGE-1`.
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`superpower/post-consolidation-sprints.md`](../../../superpower/post-consolidation-sprints.md) §Sprint 2.

Ranked. The first is the only one that can put a broken artifact into production.

1. ✅ ~~**The transform guard is absent from every save path**~~ **FIXED 2026-09-09.** `SqlGuard` had ten
   call sites and none was a save, so a hand-written `read_csv(...)`, a data-definition statement or a
   multi-statement string saved and armed cleanly and failed only at run — fail-late, never fail-open.
   `PipelineEditable.refuseUnsafeSql` now runs it on the graph save (once per `transform.sql` node, before
   classification, so it covers the chain **and** the projection slot) and `RecipeCompiler` calls the same
   helper, so both surfaces refuse identically with `SQL_STEP_REFUSED` carrying the guard's own message —
   the author is told *which* construct tripped it. ⚠ Blankness stays `SQL_STEP_EMPTY`'s business so one
   defect never reports twice. Pinned by `SqlSavePathGuardTest` (9 tests) and mutation-proven: removing the
   two call sites failed exactly the two integration tests and left the seven helper tests green.
2. 🔴 **`fields[]` is documented as inert in the config-key reference and as executable in the editor
   page** (§2.3). Code says executable. That wrong reasoning is what produced the live client-mirror
   drift, so correcting the reference is the actual fix for the drift row, not a cosmetic edit.
3. 🔴 **Four counts are each stated two or three ways, and the authoring document is wrong every time**
   (§2.2). Node types, recipe entries, processors, transform functions. Two of them already have a
   correction recorded elsewhere that was never applied.
4. 🔴 **Two current documents cite refusals that do not exist** — a second join and a second sink. Only
   two "too many of a kind" constants are live.
5. 🔴 **Eight current documents still name the deleted map type or the superseded simple grid as
   current**, including one file whose own scope statement and frontmatter describe a surface its §2
   heading says was deleted, and one index line that is stale in three ways at once.
6. 🔴 **An unknown node type is only a warning** (§2.1). The validator warns, the codec stores config as
   an unchecked map, and the compiler silently drops the node — so an invented vocabulary survives
   unnoticed. This is the one place PIP-1's "author-time validation" does not hold, and it should either be
   an error or be stated in the requirement.
7. 🟡 ~~**The palette's silent fallback has no host-level spec**~~ **TESTED 2026-09-09** (§3.7). Five
   tests in `pipeline-editor.component.spec.ts` pin all three host error arms: a failed processor-catalog
   leaves `paletteProcessors` **`null`** (not `[]`) so the node-type groups stay, a failed node-type route
   empties the palette, and a served-but-EMPTY verb list is treated as *not served*. ⚠ The `null`-vs-`[]`
   distinction is the load-bearing one — `[]` would turn a degraded server into a confident claim that
   this build has no processors. Mutation-proven (each semantic flipped fails exactly its own test).
   ⛔ **Still a product decision, deliberately not encoded in a test:** whether a degraded palette should
   also fire the alert component. By this project's convention it should, and nothing fires it today — but
   asserting that would bake in a decision nobody has made.
8. ⚠ **`A6` is an orphan decision.** The export-home decision of 2026-08-17 appears **exactly once** in
   the entire current document set, with no board row and no other home. This spec's §4 is now its second.
9. ⚠ **Every executor line-number citation in the authoring documents is wrong, and so is its package
   path** — the class moved into an execution sub-package. Four documents cite it by line. **Cite the
   symbol, not the line** — the same lesson the compliance and editions areas each recorded.
10. ⚠ **A deleted component is cited as the current mapping UI** in a concept page, and named again in a
    heading that says it was deleted.
11. ⚠ **An identity page says "nothing open" four lines above citing a board row that does not exist** —
    and that citation is precisely what caused a phantom row to be filed and then deleted unbuilt.
12. ⚠ **The active plan states a sibling document's size as 144 KB; it is 18 KB** — the figure predates a
    split the same list describes.
13. ⚠ **Two notes point at the wrong backlog section** for the authoring row.
14. ⚠ **One editor pane is undocumented anywhere in the frontend tier** — a guarantees checklist panel
    whose own docblock cites a plan that is not among its page's cross-links.
15. ⚠ **The requirement's own cell reads `SHIPPED` and "is not shipped" in one sentence** (§2.1). Already
    flagged once in the consolidation plan; never fixed.
16. ⚠ **`transform.merge` is executable code with no authoring route** (§3.4). Correctly refused rather
    than half-exposed, but nothing on any board says the executor carries a node nobody can reach.

### 5.3 Not this spec's

`PIP-2` through `PIP-7` — the medallion lanes, incremental triggers, the scheduler and Jobs, async run
triggers, job templates and the maintenance library — belong to
`okf/capabilities/pipeline-execution/pipeline-execution.md` (**not written yet** — the last slot). Two items that look like authoring are
also its: the **second execution home of an authored chain** (§3.6 notes only the authoring consequence),
and the requirement row whose status hierarchy still uses a word the glossary retired in 2026-08.

## 6. Refused & superseded

| Item | Verdict | Why |
|---|---|---|
| **Sub-Pipeline / embedded-Job nesting** | ⛔ **REFUSED by design** 2026-08-02 | In-motion and at-rest are a binding line; they compose as producer and consumer over a shared store |
| **The closed type vocabulary** | **SUPERSEDED** 2026-08-09 (D0-B) | Totality, not closure, was the real guarantee — and it survives an open registry. ⚠ That registry is unbuilt, so the set is closed in fact today |
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

## 7. As-built pointers

⚠ **Cite the symbol, not the line** — four documents in this area cite the executor by line and every one
of those citations is wrong (§5.2 item 9).

| Concern | Where it lives | Gap |
|---|---|---|
| The type vocabulary | `BuiltinNodeType` (30 constants) | ⚠ Counted 20/28/20 in three docs |
| Published config shapes | `NodeAttributes` + its committed contract | 11 of 30 by design |
| The authoring round trip | `PipelineEditable.toMap` / `.lower` | — |
| The read-only projection | `PipelineLift.lift` | ⚠ Documented as *internal only, never a file rewrite*; do not cite it as the round trip |
| The at-rest projection | `PipelineLift.stageTwo` | Execution spec's |
| Executor dispatch | `RowShaper.shape` in `com.gamma.pipeline.exec` | ⚠ Four docs cite the wrong package **and** wrong lines |
| The executable map-node keys | `RowShaper.MAP_NODE_CONFIG_KEYS` | 🔴 Contradicted by the config-key reference (§2.3) |
| The authored map keys | `PipelineEditable.MAP_AUTHORED` (3 keys) | ✅ Client mirror pinned to it by `MapNodeKeyContractTest` (2026-09-09) |
| Live refusal codes | `PipelineEditable` constants | ⚠ Two docs cite two that do not exist |
| Graph validation | `PipelineValidator` | 🔴 Unknown type is a WARNING (§2.1) |
| The Recipe layer | `RecipeCompiler` (16<!--count:step-types--> entries, 9 verbs) / `RecipeConverter` | 🔴 The fifth registration point (§3.3); no guard call (§3.5) |
| The transform guard | `SqlGuard` — 10 call sites | 🔴 **Zero on any save path** |
| The legacy read bridge | `DataTransformer.recordFields` | — |
| The function catalogue | `sql-functions.ts` (23 in 7 categories) | ⚠ Counted 24 and ~20 in docs |
| The processor catalogue | `processor-catalog.contract.json` (119<!--count:processors--> = 34/18/67) | ✅ The two docs that counted 121 were corrected 2026-09-09; every statement of this number is now derived from the contract by `tools/check-doc-counts.mjs` |
| The editor shell | `PipelineEditorComponent` + its template chain | ⚠ Dispatch is hard-coded, not a registry |
| The palette | `PipelinePaletteComponent` + two served routes | 🔴 Host error arm unspecced |
| The transform pane | `PipelineTransformSqlDefinitionComponent` + the reconciler | — |
| Unmodelled config | `pipeline-extra-config.component.ts` + `node-config-build.ts` | — |
| Identity & lifecycle | `PipelineSettingsRoutes`, `PipelineRenameRoutes` | — |
| The editor-UI truth | [`pipeline-editor.md`](../../frontend/features/pipeline-editor.md) | ⚠ Three of the four bad counts |
| The backend authoring model | [`editable-round-trip.md`](../../backend/pipeline-graph/editable-round-trip.md) | ⚠ Cites a dead refusal |
| The backend graph model | [`pipeline-graph-design.md`](../../backend/pipeline-graph/pipeline-graph-design.md) | ⚠ Says the deleted type is never executed as a node |
| The Step catalog | [`step-catalog.md`](../../backend/pipeline-graph/step-catalog.md) | ✅ Counts agree with code |
| The config contract | [`pipeline-config-keys.md`](../../backend/pipeline-graph/pipeline-config-keys.md) | 🔴 The `fields[]` contradiction |
| ~~The active plans~~ | **ARCHIVED 2026-09-10** — `plans-archive/pipeline-spec.md`, `plans-archive/pipeline-waves-drain-plan.md` | D1–D10 are now §3.0/§ *Decisions* of this spec and `okf/backend/engine/node-types.md`; the plans are provenance |

There is **no `Practice`-typed document in this area at all** — six concepts, five features, three
references, one architecture, one seam, one pointer, four untyped indexes and two plans.

## 8. Verification

**What is actually enforced today**

* **Three round-trip tests**, each guarding a different failure: verbatim including unmodelled keys; the
  real codec through a file; and the reachable path from the editor's write route to an engine field.
* **A repo-wide Recipe parity gate** — compile of the projection of every committed pipeline fixture must
  equal the original, and it asserts the fixture list is non-empty so an empty sweep cannot pass silently.
* **Committed server-table contracts** for node attributes and the step catalogue, which the client also
  reads and which regenerate only deliberately.
* **A constant-drift contract** that re-parses the executor's own source text — the strongest guard here,
  and the pattern the four bad counts and the mirror drift both need.
* **A binding-home contract**: every category offering a picker must have a home on the save path.
* **Editor specs** pinning the save payload, refusals landing in the dock, the 422-only Apply block, and
  authoring affordances hidden in the read-only persona view.

**Falsify, don't read — five probes**

1. **Save a pipeline whose transform SQL reads a file from disk.** It will save. That is the §3.5 gap; a
   refusal at save means it was fixed.
2. **Count the type enum's constants** before quoting any node-type number. Three docs disagree with it.
3. **Diff the client mirror of the authored-key set against the server constant.** Two entries versus
   three means the drift is still live.
4. **Break the taxonomy route and open the palette.** A silently plainer palette with no message is the
   §3.7 gap.
5. **Add a type to the enum and nothing else, then save a graph using it.** The refusal you get names
   which of the five registration points you missed — and no build or test failed on the way.

**A capability-pointer check over this file** — it is **`tools/check-doc-citations.mjs`, committed 2026-09-09**
and wired into `ci.yml` and `.githooks/pre-push`. It checks this file's backticked symbols
and paths against `git ls-files` and the module tree.
