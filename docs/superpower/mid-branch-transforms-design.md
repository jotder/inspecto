# Mid-branch transforms in a `route:` branch — design only

**Status:** DESIGN, not scheduled (operator asked for a design pass only, 2026-08-26). No code.
**Scope:** let a `route:` branch carry its own `steps:` sub-chain instead of exactly one `sink`.

## Today's refusal

Both authoring surfaces refuse it, explicitly and by different mechanisms:

| Surface | Where | Behaviour |
|---|---|---|
| Recipe (`steps:`) | `RecipeCompiler.route()` — `inspecto-engine/.../pipeline/RecipeCompiler.java:382-390` | a branch's `steps:` must be exactly `[{sink: {...}}]`; anything else ⇒ `UNSUPPORTED_STEP` ("mid-branch transforms land with the branch-aware executor") |
| Flat config | `PipelineLift.branch()` — `inspecto-engine/.../pipeline/PipelineLift.java:293-376` | `join`/`dedup`/`summarize`/`route` are emitted on the **shared trunk before** the route node; there is no per-branch transform node at all |

So there is **no per-branch transform scaffolding to extend on either surface**. This is the largest of
the three branch-aware residuals and the only one that is genuinely unbuilt rather than gated.

## What has to be decided (the actual design content)

1. **Node identity per branch.** The lift already suffixes ids per schema (`PipelineLift.branch`), so
   the precedent exists: a branch's chain becomes `<key>` -suffixed node ids chained off the
   `route:<key>` edge. Decide the suffix grammar ONCE and use it in both surfaces, or the two paths
   drift (this is how `route:` itself acquired two spellings).
2. **Where the branch's sink attaches.** Today `route:<key>` pairs to a `sinks[]` destination **by
   database** (`RouteArming` rule 2). With a sub-chain, the last node of the chain feeds the sink and
   the `route:<key>` edge feeds the chain's FIRST node. The database pairing rule must therefore move
   from "the route edge's target" to "the chain's terminal node", or arming's pairing check silently
   passes while rows land nowhere.
3. **Which operators are legal mid-branch.** `join`/`dedup`/`summarize` are trunk operators today.
   `summarize` mid-branch changes row cardinality per branch, which interacts with per-branch lineage
   and the `(batch, branch)` commit ledger. Recommend starting with **row-preserving operators only**
   (`transform`/`filter`), and refusing cardinality-changing ones until there is a demand case —
   fail-closed, consistent with how `route:` itself shipped.
4. **Arming rules.** Every new shape needs its `RouteArming` refusal in the same change, since
   `RouteArming.refusals` is the single rule set both `prepare()` and the save path read. A chain whose
   terminal node is not a sink, or whose intermediate node fans out, must refuse at SAVE — the
   2026-08-26 pre-check lesson was that "it validates at load" is not good enough.

## Interactions worth pinning before building

- **Commit ledger.** `BranchCommitCoordinator.expectedBranches` is keyed by **sink node id**
  (`PipelineExecutor.java:184`, `sinkInputs.keySet()`), so a longer chain per branch does not change the
  commit contract — the terminal sink is still the branch's commit unit. This is the one place the
  design is already safe.
- **Multi-schema.** Out of scope and must stay refused — see `RouteArming` rule (4): `writeAndTrace`
  runs per segment while the divert lifts the whole graph, so multi-schema + route would run every
  schema's tree against every segment. Do not entangle the two.
- **`mode: clone`.** Orthogonal. Clone stays refused for lack of partial-commit *visibility*, not
  substrate.

## Recommendation

**Do not build this until a pipeline actually needs it.** The refusal message already names the
limitation precisely, which is the honest state. When demand arrives, the order is: decide (1)-(4)
above → extend `PipelineLift` emission → extend `RecipeCompiler.route()` to parse the sub-chain →
arming rules + tests in the same change → then lift the `UNSUPPORTED_STEP` refusal.
