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

### Slices

**A1 — decide and pin the plural shape.** For each of `dedup`, `route`, `summarize`, `join`: a list of
the existing block, plus whatever `lift` needs to restore order (an explicit `after:`/index, or list
position as the contract). Write the round-trip property as a test *first*: lift(lower(g)) preserves node
count and order for a graph with two of each. → verify: the test exists and fails.

**A2 — `PipelineConfig` reads the plural.** Mirror `List<Sink>`: `List<Dedup>`, etc., with the singular
key still accepted as a one-element list (every existing `*_pipeline.toon` must keep parsing byte-identically).
→ verify: existing config tests unchanged and green; a new test parses a plural block.

**A3 — `lower()` emits the plural and the refusals go.** One destination ⇒ no plural block, so a
single-transform pipeline round-trips verbatim (the sinks rule). Delete `MULTI_DEDUP`/`MULTI_ROUTE`/
`MULTI_SUMMARIZE` and their tests **in this slice**, replacing them with round-trip tests.
→ verify: `PipelineEditableTest` green; the A1 property test passes.

**A4 — the mock moves in the same commit as A3.** `inspecto-ui/src/app/inspecto/mock/pipeline-editable.ts`
must stop refusing exactly when the server does. ⚠ Standing rule: **a mock must never be more lenient
*or stricter* than the server** — a preview that refuses what the backend now accepts is the same class
of bug, pointing the other way. → verify: `pipeline-editable.spec.ts` green with the refusal specs
rewritten as round-trip specs.

**A5 — the flat execution path honours the plural.** ⚠ **This is the slice that can silently do nothing.**
`BatchProcessor`/`DataTransformer` read `processing.dedup` etc. as single blocks; emitting a plural the
executor ignores would produce a config that saves, loads, and quietly runs only the first. Ground where
each block is *read* before writing this slice. → verify: an end-to-end test with two filters proves both
applied (assert row counts, never "it ran").

**A6 — wiring validation becomes the real constraint.** Extend `PipelineValidator.checkWiring` so an
invalid neighbour pairing refuses with a named code, which is what should have been rejecting bad graphs
all along. → verify: a wiring-invalid graph refuses; a wiring-valid graph with N transforms saves.

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
