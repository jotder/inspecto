# Pipeline multiplicity — plural transform blocks, and multi-location acquisition

**Status:** ACTIVE (opened 2026-08-11). Part A is a plan to execute; Part B is a design to settle before
anything is built. Operator decisions of 2026-08-11 — see `docs/BACKLOG.md` §4 *Pipeline graph*.

**One-line premise:** a pipeline should be constrained by whether a Step *accepts its neighbours*, not by
how many Steps of a kind exist. Today it is constrained by neither — it is constrained by how many slots
the flat config file happens to have.

---

## Where the restriction actually lives (grounded 2026-08-11)

The engine is **not** the limit.

- `PipelineExecutor.execute` (`pipeline/exec/PipelineExecutor.java:132-155`) is a generic **topological
  walker**: it Kahn-sorts the graph, then dispatches per node by category/type, keying every output
  relation by its own `nodeId`. Two `transform.dedup`, two `transform.filter`, two `transform.route` in
  sequence already execute correctly — that falls out of walking edges, it is not a feature anyone built.
- Multiple **source** nodes are already supported by design on this path: `PipelineJobRunner.seedsOf`
  collects every `source_store` node and `execute(..., Map<String,String> seeds, ...)` seeds them all
  (T32 Phase C); `transform.merge` is the N-input fan-in.

The limit is `PipelineEditable.lower()`, which compiles an authored graph back into the flat
`*_pipeline.toon`. That file holds **one** `processing.dedup`, **one** `processing.summarize`, **one**
`processing.join`, **one** `route:`. Before 2026-08-11 a second node was silently discarded; `2cf7005e`
made it refuse (`MULTI_DEDUP` / `MULTI_ROUTE` / `MULTI_SUMMARIZE`), joining `MULTI_JOIN`.

⚠ **Do not open this work by reverting `2cf7005e`.** The refusal is on the wrong side of where we are
going, but until the target format can hold multiples it is the only thing making the loss *visible*.
Reverting first re-creates the silent discard that `6e4d4be0` measured. **The refusal is removed in the
same slice that makes the format able to represent the thing.**

⚠ **`accepts()`/`emits()` validation does not replace it either.** That layer is real and already exists
(`PipelineNodeType.accepts()/emits()`, `PipelineValidator.checkWiring`) and is the right long-term
constraint — but a wiring-valid graph with two dedups still has nowhere to *put* the second one in a
single-slot file. The two checks answer different questions and both are needed.

⚠ **`transform.summarize` and `transform.join` do not execute at all**, on either path: `RowShaper.shape()`
(`pipeline/exec/RowShaper.java:64-76`) has no case for them and throws
`IllegalArgumentException("RowShaper cannot shape node type …")`; `BuiltinNodeType` calls them
"compile-only". **"Allow many" is moot for these two until "allow one" works** — so their plural blocks
are authoring-only until that lands, exactly as their single blocks are today.

---

## Part A — plural transform blocks (PLAN, executable)

**The template already exists and shipped:** `>1` persistent sink used to refuse with `MULTI_SINK`; the
sinks slice turned it into a plural `sinks:` list backed by a real `List<Sink>` on `PipelineConfig`
(`PipelineEditable.java:412-425`, `PipelineConfig.sinks()`). Follow it rather than inventing a shape.
`MULTI_SINK` survives only as a now-unreachable constant — a useful reminder of the end state.

**Ordering matters and is not free.** A list of filters is order-sensitive, and the flat file has no
edges. The sinks case dodged this (destinations are a set); dedup→summarize→route is a *sequence*. So a
plural block must carry enough to reconstruct the chain on the next lift, or a round-trip silently
reorders the pipeline. Decide this in slice 1, not later.

---

## A1 findings (grounded 2026-08-11) — these change what A1 decides

The two round-trip properties are pinned in `PipelineEditableTest`, `@Disabled` with the reason on them
(`twoFiltersSurviveTheRoundTrip`, `twoOfEachKindSurviveTheRoundTripInAuthoredOrder`). Both were run with
`-Djunit.jupiter.conditions.deactivate='*'` and **both fail today, for the right reasons** — the first
attempt failed on `FileNotFound: s.toon` instead, because `PipelineConfig.fromMap` resolves `schema_file`
eagerly; a red test for the wrong reason proves nothing, so `roundTrip` writes a real schema.

### Finding 1 — ⚠ `transform.filter` is ALREADY losing nodes, silently, and the plan does not list it

Filter *looks* like the one kind that already allows many: `lower()` collects filters into a `List` and
there is no `MULTI_FILTER` refusal. It is not. The list is merged into a single `processing.csv_settings`
map with `putAll` (`PipelineEditable.java:398`) and `lift` emits **exactly one** Filter node. Measured:

```
authored [transform.filter, transform.filter]  →  round-trips to  [transform.filter, transform.map]
```

No refusal, no warning. Where two filters set the same key, `putAll` last-one-wins silently decides the
pipeline's behaviour. **This is the same data loss `2cf7005e` refused for the other four kinds, still
live** — in the kind most likely to be authored more than once, and the most order-sensitive of them all.
It was missed because a `List<PipelineNode>` in the source reads as "handled".

⇒ **`filter` joins A1's scope as a first-class kind**, not an afterthought. ⛔ Do not close this by adding
a `MULTI_FILTER` refusal — that walks away from the destination for the one kind whose plural form is
least controversial.

### Finding 2 — ⚠ cross-kind order is not in the file at all, so per-kind lists cannot restore it

`PipelineLift.branch` emits a **hard-coded** chain (`PipelineLift.java:187-238`):

```
map → [join] → [dedup] → [summarize] → [route] → sink
```

Nothing about order is read from the flat config; the order is a constant in the lift. Today that is
invisible, because with at most one node per kind a constant order is indistinguishable from a stored
one. A second node of any kind makes it visible: an authored `dedup → summarize → dedup` **cannot be
represented by per-kind plural lists however they are keyed**, because the lift will always emit both
dedups adjacent.

⇒ **A1's decision is not "explicit index vs. list position".** That question only covers order *within* a
kind. The actual choice is:

| Option | Shape | Cost |
|---|---|---|
| **(a) Per-kind plural lists** | `processing.dedup: [ … ]`, etc. Order within a kind = list position; order across kinds stays the lift's hard-coded constant | Cheapest, mirrors `sinks:` exactly, and **cannot express an interleaving**. Honest only if the engine also refuses to author one — i.e. the constraint moves from "one per kind" to "one *run* per kind", which is a smaller win than the premise promises |
| **(b) An ordered `steps:` sequence** | one list of `{kind, …config}` entries; order is the list, full stop | Expresses everything, is what the graph actually is, and is a genuinely new top-level format key — A2/A3/A5 all grow, and every `*_pipeline.toon` reader must accept both spellings for as long as the singular keys exist |

⛔ **Do not start A2 until this is chosen.** (a) and (b) produce different `PipelineConfig` surfaces, and
`twoOfEachKindSurviveTheRoundTripInAuthoredOrder` deliberately asserts the interleaved order so it
**cannot be made green by (a) alone** — if (a) is chosen, that test must be amended, and the amendment is
the record of the capability being given up.

⚠ The premise this plan opens with — "constrained by whether a Step accepts its neighbours, not by how
many exist" — is only fully delivered by (b). Under (a) the count limit becomes a sequence limit.

---

### Finding 3 — the five kinds are five different things in the file, and only two of them execute

Grounded 2026-08-11 while sizing A2/A5. "The plural blocks" is one phrase covering five unrelated
representations:

| Kind | How the file holds it today | Typed? | Executes on the flat path? |
|---|---|---|---|
| `dedup` | `processing.dedup` | `Dedup` record | ✅ `BatchIngestStrategy.java:129` |
| `filter` | ⚠ **`processing.csv_settings.where`, a single String** — a *field inside another block*, not a block | `String rowWhere` | ✅ `DataTransformer.java:65` |
| `summarize` | `processing.summarize` | `Summarize` record | ❌ `prepare()` refuses when `active` |
| `join` | `processing.join` | `Join` record | ❌ `prepare()` refuses when `active` |
| `route` | top-level `route:` | ⚠ raw `Map<String,Object>`, untyped passthrough | ❌ `prepare()` refuses when `active` |

⚠ **`dedup` in that table means the *distinct* dedup, and there are two dedups** (operator correction,
2026-08-11). **File dedup** (content-fingerprint) was **folded into the Acquisition/Collect node**
2026-08-04 (`PipelineEditable.java:74`) — it is a property of the collector, not a step in the chain, so
it is singular by construction and **plural does not apply to it**. **Distinct dedup** is
`transform.dedup` → `processing.dedup`, a `QUALIFY ROW_NUMBER() OVER (PARTITION BY keys ORDER BY
order_by)`, and ⚠ **it is per-Consignment only as of now** (`applyRecordDedup` runs over the batch write
table, not the store). Both are what Part A's plural is about; only the second is a chain step.

Two consequences the plan was written without:

1. ⚠ **Three of the five have no flat-path executor** (not "A5 is smaller" — see finding 5, the scope
   does not shrink, it moves). `PipelineConfig.prepare()` refuses an `active` pipeline carrying
   `summarize`, `join` or `route`, so on **path A** their plural form would be authoring-only stacked on
   authoring-only. Path B is a different story entirely.
2. ⚠ **`filter` has no plural home.** It is not a block that could become a list — it is one String
   inside the parse-settings block. Making it plural means either `where:` becomes a list of predicates
   (cheap, but AND is commutative so it can never be *positioned* relative to a dedup) **or** filters get
   lifted into a block of their own — which is a new key, i.e. the very thing option (a) exists to avoid.

### Finding 5 — ⚠ CORRECTION: option (b)'s executor already exists and already runs

An earlier draft of this plan (and the 2026-08-11 write-up) said (b)'s A5 "is the branch-aware executor,
already tracked and already blocked". **That was wrong.** The blocked item is Phase 4 S4 / Phase 6's
*unscoped output parity* (BACKLOG §4), which is not the same thing as walking a chain. Checked directly,
`RowShaper.shape()` (`pipeline/exec/RowShaper.java:64-76`) dispatches:

| Node kind | Path A (flat) | Path B (`PipelineExecutor` + `RowShaper`) |
|---|---|---|
| `transform.filter` | ✅ one `csv_settings.where` | ✅ `filter` |
| `transform.dedup` (distinct) | ✅ one `processing.dedup` | ✅ `dedup` — ⚠ via `type.startsWith("transform.dedup")`, so it covers **both** dedup kinds, and it additionally emits a `duplicate` relation path A discards |
| `transform.route` | ❌ `prepare()` refuses | ✅ `route` |
| `transform.split` / `validate` | ❌ not lowerable | ✅ `split` / `validate` |
| `transform.map` / `select` / `derive` | partial | ✅ `project` |
| `transform.summarize` / `join` | ❌ `prepare()` refuses | ❌ `shape()` throws — the one real gap |

So the walker that executes an arbitrary ordered chain **exists, is wired (`PipelineJobRunner`), and is
strictly more capable than the flat path** — it already runs N transforms of a kind in any order, because
that falls out of Kahn-sorting and keying outputs by `nodeId`. `summarize` and `join` are the only kinds
missing, and they are missing from *both* paths, tracked separately.

### Finding 6 — ⚠ the format was never tested through the FILE, and both spellings we documented were wrong

Grounded 2026-08-11 during A3, by probing the codec rather than reading it. **Every test of `steps:` —
and, it turned out, every test of the `sinks:` plural block that set the precedent — went through
`PipelineConfig.fromMap` with a hand-built Java map.** That skips the codec entirely, and the codec is
where a config format is actually decided: `ConfigCodec.toToon` is what `PipelineRoutes` writes on every
save (`PipelineRoutes.java:363`). A block can be perfectly modelled, perfectly parsed from a map, and
still be unwritable or unreadable as a file. Two defects were hiding in that gap:

| Spelling | What toon actually does |
|---|---|
| `steps:` (no element count) | decodes as a **map**, `{- dedup={…}}` — so the parser's `instanceof List` test never matched and **the entire chain was skipped in silence** |
| `- dedup: {keys: [ID]}` | the value decodes as a plain **String**, never a config block |

The first is the exact silent-discard shape this whole format was introduced to remove, recreated in its
own reader — and it was the spelling the plan, the `Step` javadoc, the parser's own comment and its error
message all taught. Both are now refusals (the non-list `steps` names the working spelling in the message),
and `PipelineStepsFileRoundTripTest` (in `inspecto`, the only module that can see both `ConfigCodec` and
`PipelineEditable`) is the standing guard over graph → lower → toToon → disk → load → config → lift.

⚠ **The lesson generalises past this plan:** a config-format slice is not verified by a `fromMap` test.
`sinks:` still has no file-level test.

### Finding 4 — ⚠ the `sinks:` precedent option (a) mirrors never got its own A5

`sinks:` is the template this plan tells you to follow. It is **authoring-only**: `resolveSinks`
(`PipelineConfig.java:976`) builds the list and the editor round-trips it, but `prepare()` refuses more
than one destination for execution until the branch-aware executor lands. So the precedent demonstrates
A2+A3+A4 and **stops exactly where A5 begins** — and the branch-aware executor is itself blocked
(BACKLOG §4, ELT amendment Phase 4 S4 / Phase 6).

Copying that precedent faithfully therefore delivers *authoring*, and honesty about it comes from an
explicit `prepare()` refusal, not from the format.

---

## The two options, sliced side by side

⚠ **The slice list below used to describe option (a) only** — "mirror `List<Sink>`: `List<Dedup>`" is
already a choice. Written out per option, the real difference is not syntax.

### Option (a) — per-kind plural lists

```toon
processing:
  dedup:
    - {keys: [msisdn], order_by: "ts desc"}
    - {keys: [imsi]}
  summarize:
    - {group_by: [day], measures: [count]}
```

| Slice | Work |
|---|---|
| **A2** | `List<Dedup>`, `List<Summarize>`, `List<Join>`, `List<Map>` for `route`. Singular key resolves to a one-element list — copy `resolveSinks`. ⚠ **`filter` needs its own answer first** (finding 3.2). |
| **A3** | `lower()` emits a plural only when >1, so single-transform pipelines round-trip verbatim. Delete `MULTI_DEDUP`/`MULTI_ROUTE`/`MULTI_SUMMARIZE`. Stop merging filters into `csv_settings`. |
| **A4** | Mock mirrors A3, same commit. |
| **A5** | Loop where the code today calls once: `BatchIngestStrategy:129` applies each `Dedup` in turn; `DataTransformer:65` ANDs each predicate. **summarize/join/route: nothing to do — they do not execute.** |
| **A6** | Wiring validation (unchanged by the choice). |

**Ceiling:** ⛔ `dedup → summarize → dedup` stays unrepresentable, permanently. `PipelineLift` still emits
its fixed chain, so per-kind lists mean *all dedups, then all summarizes*. The constraint moves from "one
node per kind" to "one **run** per kind" — narrower than the plan's opening premise.

### Option (b) — an ordered `steps:` sequence

```toon
steps[4]:
  - dedup:
      keys[1]: MSISDN
  - summarize:
      group_by[1]: RECORD_DAY
      measures[1]: count
  - dedup:
      keys[1]: IMSI
  - filter:
      where: "duration > 0"
```

⚠ **That syntax is exact, and an earlier draft of this plan had it wrong** — see *Finding 6*. The
element count is required, and the config must be an indented block, never an inline `{…}`.

| Slice | Work |
|---|---|
| **A2** | One `List<Step>` (`kind` + config). ⚠ **The subtle part is back-compat:** the legacy singular keys must still parse *and* project into the step list at the position `PipelineLift`'s hard-coded chain implies — that projection is the bridge, and it is where a round-trip can silently reorder someone's pipeline. |
| **A3** | `lower()` emits `steps:` only when the chain is not expressible in the legacy singular keys; otherwise verbatim legacy. Same refusal deletions as (a). |
| **A4** | Mock mirrors A3, same commit. |
| **A5** | ⚠ **This is the whole decision.** Honouring an arbitrary order means walking a step list — which is *precisely what `PipelineExecutor` already does* (finding 5). Teaching path A to walk is re-implementing path B. So A5 is **route a `steps:` pipeline to path B**, not extend path A. |
| **A6** | Wiring validation (unchanged by the choice). |

**Ceiling:** none on expressiveness. A5 is not "extend the flat executor" — it is a routing decision onto
an executor that already exists and is already wired.

### ⇒ What the choice actually is

**Not a file-format preference.** It is: *does the flat linear executor keep existing as the thing that
runs pipelines?*

- **(a) says yes.** Keep path A's fixed stage chain; allow repetition *within* a stage. Ships now, A5
  included, for the kinds path A executes. Gives up interleaving for good.
- **(b) says no.** The flat file becomes a serialisation of the real graph; running it means path B —
  which already runs, already handles every kind path A does **plus** route, split and validate, and
  already executes N transforms of a kind in any order.

## DECISION — (b), the ordered `steps:` sequence

**Recommended and taken 2026-08-11**, operator having delegated the call and directed that scope not be
lowered. The deciding facts, in order of weight:

1. ⚠ **Finding 5 removes (b)'s only real cost.** The argument for (a) was "(b)'s A5 is blocked". It is
   not — that was my error, corrected above. The walker exists, is wired, and is *more* capable than the
   flat path. (b) does not need new execution machinery; it needs the file to stop lying about what the
   graph is.
2. **(a) does not actually avoid a new key** (finding 3.2). `filter` has no plural home without one, and
   filter is the kind most likely to be authored twice. So (a)'s headline advantage is largely illusory,
   while its ceiling is permanent.
3. **(a)'s ceiling bites the case the operator named.** Distinct dedup is per-Consignment and key-set
   specific; chaining two with a transform between them is a *normal* shape, not an exotic one. Under
   (a) the flat file can never express it, however the lists are keyed.
4. **(a) is throwaway.** Its per-kind lists are a strict subset of (b)'s step list, so choosing (a) buys
   a shipping date and pays for it with a second format migration plus a mental model ("per-kind stages")
   that the migration then breaks.
5. **The premise of this plan is only delivered by (b).** "Constrained by whether a Step accepts its
   neighbours, not by how many exist" is (b). Under (a) the count limit becomes a sequence limit, which
   is the same restriction wearing a different hat.

⚠ **What (b) does NOT solve, and must not be claimed to:** `summarize` and `join` still execute nowhere
(`RowShaper.shape()` throws for both). (b) makes them *representable in order*; it does not make them
run. That is the separate `RowShaper` work already tracked in BACKLOG.

⚠ **The risky slice is A2, not A5.** Legacy singular keys must keep parsing *and* project into the step
list at the position `PipelineLift`'s hard-coded chain implies. Get that projection wrong and an existing
pipeline silently reorders on its next save — the exact failure this plan exists to prevent, introduced
by the fix for it. Property-test the projection before writing the reader.

---

### Slices (option-independent parts)

**A1 — decide and pin the plural shape.** ✅ **DONE 2026-08-11.** Property tests pinned and proven red;
findings 1–5 are the grounding; **decision taken: option (b)**. Scope is all five kinds — `filter`,
`dedup` (distinct), `route`, `summarize`, `join` — deliberately **not** reduced to the two that execute
today. ⚠ `twoOfEachKindSurviveTheRoundTripInAuthoredOrder` stays exactly as written: under (b) it is
reachable, so it is the acceptance test for A2/A3 rather than a record of something given up.

**A2 — `PipelineConfig` reads `steps:`.** ✅ **SHIPPED 2026-08-11 (`240b0da6`).** `Step {kind, config}` +
`PipelineConfig.steps()`, fed by an explicit top-level `steps:` list or, absent one, the legacy singular
blocks projected into the lift's order. As-built notes:

- **A `steps:` entry is a single-key map** of kind → config (⚠ spelled as an indented block, **not**
  `- dedup: {keys: […]}` — see *Finding 6*), not a flat
  `{kind: dedup, …}`. Chosen so `kind` can never collide with a config key of the same name, and so a
  malformed entry is a structural error rather than a silently-ignored one.
- **Order is list position, full stop.** No `after:` key, no index — a second ordering channel is a second
  source of truth, and the two disagree the first time someone hand-edits the file.
- ⚠ **The two spellings are mutually exclusive**, refused in the parser with the offending legacy keys
  named. There is no non-arbitrary position at which a legacy block joins an authored sequence.
- ✅ **The projection is cross-checked against `PipelineLift`, not against a constant.**
  `PipelineStepsProjectionTest` (in `inspecto-engine`, the module that can see both) *reads the expected
  order off the lift* across eight block combinations, with the blocks inserted in deliberately wrong
  declaration order so a projection that merely echoes it cannot pass. It was green on first run and
  therefore proved nothing until `dedup`/`summarize` were swapped in the projection and it went red —
  one position of drift is caught, and the message reports the lift's real chain.
- ⚠ **`steps()` has no consumer yet, by design.** The flat path still reads `dedup()` / `csv.rowWhere()`;
  the graph path walks the graph. A `steps:` file is authoring-only until A5, the posture `sinks:` has
  had since it shipped, and the accessor javadoc says so.
- ⚠ **Cross-module gotcha:** `-pl inspecto-engine` resolves `inspecto-etl` from `~/.m2`, not the reactor,
  so new symbols are invisible until `mvn -o install -pl inspecto-etl`. Bit once here; will bite A3.

**A3 + A4 — ✅ SHIPPED 2026-08-11.** `lower()` emits `steps:` only for a chain the singular keys cannot
hold; `MULTI_DEDUP`/`MULTI_ROUTE`/`MULTI_SUMMARIZE`/`MULTI_JOIN` deleted; filters no longer merged into
one `csv_settings`; both A1 properties green with `@Disabled` removed. As-built, including four things
the plan did not say:

- ⚠ **A3 needed a `lift()` half, which the plan's A3 text does not mention.** Its own verify criterion —
  the A1 round-trip properties — goes graph → lower → config → **lift** → graph, so emitting `steps:`
  without consuming it could never have gone green. `PipelineLift` now walks an explicit chain post-map
  and keeps its proven hard-coded emission for legacy files, gated on a new
  `PipelineConfig.hasExplicitSteps()`. The two paths are kept apart deliberately: every pipeline in
  existence lifts through the legacy one, and collapsing them would put an untested rewrite under all of
  them to save a dozen lines.
- ⚠ **`prepare()` could not see a `steps:` pipeline at all, so A5's trap was live in A3.** The three
  arming guards test the *typed* fields (`route`/`summarize`/`join`), and an explicit `steps:` file never
  populates them — the parser refuses both spellings together, so they are null whatever the chain says.
  A `steps:` pipeline carrying a summarize would have armed and run on the linear path, which reads
  `dedup()`/`csv.rowWhere()` and would have applied **none** of the chain. A fourth guard refuses to arm
  an explicit chain; A5 lifts it.
- ⚠ **Legacy-expressible is about ORDER as well as count**, which the plan framed as a count question
  throughout. One dedup and one summarize fit the singular keys either way round, but the file stores no
  order, so an authored `summarize → dedup` would come back reversed — a two-node pipeline quietly
  changing meaning with nothing over-full about it. Out-of-order ⇒ `steps:`, same as a repeat. The two
  conditions turned out to be **one** strictly-increasing test: a repeat has the same position as its
  predecessor, so it fails `<=` too. A separate "seen this kind" set was written first and proved dead —
  deleting it turned no test red, which is how it was found.
- ⚠ **The pinned A1 expectation was unreachable as written.** It compared against a raw `transform.*`
  list, which includes the `transform.map` schema projection the lift emits for every branch, so
  `[filter, filter]` was being compared with `[filter, map]`. Red for the right reason *at the time*, but
  no implementation could ever have satisfied it. `transformChain` now restricts to authorable kinds, the
  rule `PipelineStepsProjectionTest.liftedChain` already used.

**A4 — the mock moves in the same commit as A3** under either option.
`inspecto-ui/src/app/inspecto/mock/pipeline-editable.ts` must stop refusing exactly when the server does.
⚠ Standing rule: **a mock must never be more lenient *or stricter* than the server** — a preview that
refuses what the backend now accepts is the same class of bug, pointing the other way. → verify:
`pipeline-editable.spec.ts` green with the refusal specs rewritten as round-trip specs.

**A3 — `lower()` emits `steps:` and the refusals go.** Emit `steps:` only when the chain is not
expressible in the legacy singular keys, so an unchanged pipeline round-trips verbatim. Delete
`MULTI_DEDUP`/`MULTI_ROUTE`/`MULTI_SUMMARIZE` **in this slice**. ⚠ Also stop merging filters into one
`csv_settings` map (finding 1) — that is a silent loss with no code to delete, so it is the easy one to
leave behind. → verify: the A1 property tests pass with `@Disabled` **removed**.

⚠ **One structural consequence A3 hands to A5: a chain filter sits POST-map, a legacy filter sits
PRE-map.** The lift wires the legacy filter between parser and map, because `csv_settings` carries two
different things fused into one node — the post-parse `where` predicate *and* the pre-parse
include/exclude lists, which anchor the CSV reader on a column index and therefore have to run before
mapping. A walked chain emits every step after map. For a `where` that is arguably more correct; for the
pre-parse lists it is wrong. Nothing executes today (arming is refused), so it is a representation
question, not a live bug — but **A5 must not route a chain carrying pre-parse filter keys** without
splitting them back out.

**A5 — ⚠ the slice that can silently do nothing.** A config that saves, loads and runs only the first
block is this plan's own bug, relocated one layer down. Under (b) A5 is a **routing** decision: a
`steps:` pipeline runs on path B (finding 5), which already walks it. → verify: an end-to-end test with
two **filters** and two **distinct dedups** proves each applied, by row count — never "it ran". ⚠ Do not
mark A5 done while `summarize`/`join` still throw in `RowShaper`; they are representable after A3 and
executable only after that separate work, and a `steps:` file carrying one must refuse to arm, exactly as
`prepare()` does today.

**A6 — wiring validation becomes the real constraint.** Extend `PipelineValidator.checkWiring` so an
invalid neighbour pairing refuses with a named code, which is what should have been rejecting bad graphs
all along. Unaffected by the (a)/(b) choice. → verify: a wiring-invalid graph refuses; a wiring-valid
graph with N transforms saves.

**Out of scope, deliberately:** making `summarize`/`join` executable (see the ⚠ above) — separate work,
tracked in BACKLOG.

---

## Part B — multi-location acquisition (DESIGN, decide before building)

**Neither path supports it today.** `PipelineConfig.collector` is a single `Collector` record
(`inspecto-etl/.../PipelineConfig.java:302-327`), and the entire poll cycle is keyed on one collector id:
`AcquisitionLedgers.shared().highWatermark(cfg.collector().id())`, `GapTracker.shared().newGaps(id, …)`,
`StabilityGate`, `CircuitBreaker`, `RetryPolicy`, the incremental watermark — every one of them
(`inspector/CollectorProcessor.java`). Path B's multi-seed mechanism is **not** this: it re-reads several
at-rest stores in a job, with no `CollectorConnector` involved.

**What exists instead:** `MultiCollectorProcessor` runs N *independent* pipelines concurrently in one JVM,
each with its own single-arity ledger and output. It sidesteps merging rather than solving it.

### Questions this design must answer before a line is written

1. **Identity.** Is a per-source id introduced, or does one pipeline keep one collector id and the sources
   become sub-identities? Every stateful subsystem above keys off that answer, and the ledger is durable —
   a re-keying is a migration, not a refactor.
2. **Merge semantics.** N discovered file sets become how many batches? One batch per source (simple
   ledger, N downstream runs) or one merged batch (one run, but what is its watermark, and what happens
   when one source is late or down)?
3. **Failure isolation.** Today a circuit-breaker trip stops *the* collector. With N, does one bad source
   stop the pipeline, or degrade it? A pipeline that silently proceeds on 3 of 4 sources is a data-loss
   surface with no signal.
4. **Does it beat the existing model?** `MultiCollectorProcessor` + a downstream `transform.merge` over the
   landed stores already achieves multi-location ingest with **zero engine change**. This design has to be
   better than that, not merely more elegant on the canvas.

⛔ **Do not start Part B by turning `collector()` into a list.** That is the last step, not the first: the
answers above determine whether the list is even the right shape.

---

## Related

- `docs/okf/backend/engine/consignment-addressing.md` — the `sinks:` plural precedent's as-built.
- `docs/BACKLOG.md` §4 *Pipeline graph* — the row this plan discharges.
- `2cf7005e` (the refusal), `6e4d4be0` (the measurement that justified it).
