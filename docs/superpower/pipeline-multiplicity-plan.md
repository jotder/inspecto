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

Two consequences the plan was written without:

1. ⚠ **A5's real scope is `dedup` and `filter` — two kinds, not five.** The other three have nothing to
   honour because they do not run at all; `PipelineConfig.prepare()` refuses an `active` pipeline
   carrying any of them. Their plural form is authoring-only *on top of* an authoring-only feature.
2. ⚠ **`filter` has no plural home.** It is not a block that could become a list — it is one String
   inside the parse-settings block. Making it plural means either `where:` becomes a list of predicates
   (cheap, but AND is commutative so it can never be *positioned* relative to a dedup) **or** filters get
   lifted into a block of their own — which is a new key, i.e. the very thing option (a) exists to avoid.

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
steps:
  - dedup:     {keys: [msisdn]}
  - summarize: {group_by: [day], measures: [count]}
  - dedup:     {keys: [imsi]}
  - filter:    {where: "duration > 0"}
```

| Slice | Work |
|---|---|
| **A2** | One `List<Step>` (`kind` + config). ⚠ **The subtle part is back-compat:** the legacy singular keys must still parse *and* project into the step list at the position `PipelineLift`'s hard-coded chain implies — that projection is the bridge, and it is where a round-trip can silently reorder someone's pipeline. |
| **A3** | `lower()` emits `steps:` only when the chain is not expressible in the legacy singular keys; otherwise verbatim legacy. Same refusal deletions as (a). |
| **A4** | Mock mirrors A3, same commit. |
| **A5** | ⚠ **This is the whole decision.** Honouring an arbitrary order means the flat path walks a step list — which is *precisely what `PipelineExecutor` already does* on the graph-native path. Teaching path A to walk is re-implementing path B. The real A5 is therefore **route these pipelines to path B**, not extend path A. |
| **A6** | Wiring validation (unchanged by the choice). |

**Ceiling:** none on expressiveness. The cost is that A5 is not a slice of this plan at all — it is the
branch-aware executor, already tracked and already blocked.

### ⇒ What the choice actually is

**Not a file-format preference.** It is: *does the flat linear executor keep existing as the thing that
runs pipelines?*

- **(a) says yes.** Keep path A's fixed stage chain; allow repetition *within* a stage. Fully shippable
  now, A5 included, for the two kinds that execute. Buys: two dedups, two filters, actually running.
  Gives up interleaving for good.
- **(b) says no.** The flat file becomes a serialisation of the real graph, and running it means path B.
  Buys: everything the canvas can draw. Costs: A5 is blocked on work that is already blocked, so A2–A4
  would ship as authoring-only — the same posture `sinks:` has been in since it shipped (finding 4).

⚠ **A defensible third answer is "(a) now, (b) when path B lands."** They are not exclusive: (a)'s plural
lists are a strict subset of (b)'s step list, and a `steps:` reader could absorb them later. The cost of
sequencing it that way is one format migration instead of none — and the honest version of (a) says so up
front rather than presenting per-kind lists as the destination.

---

### Slices (option-independent parts)

**A1 — decide and pin the plural shape.** ✅ **Property tests pinned and proven red 2026-08-11**; findings
1–4 are the grounding. ⏳ **The (a)/(b) decision is OPEN and needs the operator.** `filter` is in scope
alongside `dedup`, `route`, `summarize`, `join`.

**A2–A5 — see the per-option tables above.** ⛔ Do not start A2 before the choice: the two produce
different `PipelineConfig` surfaces, and `twoOfEachKindSurviveTheRoundTripInAuthoredOrder` asserts the
interleaved order deliberately, so it **cannot go green under (a)** — amending it is the record of the
capability being given up.

**A4 — the mock moves in the same commit as A3** under either option.
`inspecto-ui/src/app/inspecto/mock/pipeline-editable.ts` must stop refusing exactly when the server does.
⚠ Standing rule: **a mock must never be more lenient *or stricter* than the server** — a preview that
refuses what the backend now accepts is the same class of bug, pointing the other way. → verify:
`pipeline-editable.spec.ts` green with the refusal specs rewritten as round-trip specs.

**A5 — ⚠ the slice that can silently do nothing**, under either option. A config that saves, loads and
runs only the first block is the same bug this plan exists to remove, relocated one layer down. → verify:
an end-to-end test with two **filters** proves both applied (assert row counts, never "it ran") — filters,
because with `dedup` they are the only two kinds that execute at all (finding 3).

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
