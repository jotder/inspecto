# ELT Phase 4 S4 — per-Step `enabled:` with park/drain (D-13)

**Status:** IN FLIGHT 2026-08-28. Unblocked by the parity decision of record (elt-final-amendment-plan
§"Parity scope, RE-GROUNDED 2026-08-28"). Parent: `elt-final-amendment-plan.md` Phase 4; decision D-13.

## Grounding (verified 2026-08-28)

1. `PipelineNode.enabled()` is an in-memory bypass; `PipelineExecutor` (`:158,:239`) skips disabled
   nodes — **correct for dry-run/run-to-here, kept there**; park semantics apply to the at-rest lane only.
2. The flat-file home gap is real but the silent-loss bug is **latent, not live**: nothing can author a
   per-node `enabled` today (no node-level toggle in `NodeAttributes`; `*_flow.toon` authoring writes
   retired). The flag's durable home is greenfield.
3. `FileStage` records only boundaries `finalizeSource` crosses — a parked Consignment is *uncommitted*,
   so park state lives on the **manifest** (`parked_at` accretion); a `PARKED` stage row is only the
   queryable projection.
4. At rest, the executor is seeded **downstream of parse/map** (`graphWriteAndTrace:235-246`) — only the
   route→sink subtree executes as graph nodes, which bounds what may be disabled (see Refusals).

## The durable home — DECIDED: pipeline-level `processing.disabled_steps: [<stepId>, …]`

One key, one home. Lift overlays `enabled: false` onto the named nodes (canvas/dry-run behavior falls
out); lower derives the list back from node state — no per-branch enumerated-key edits in the two
hand-mirrored lowerers. Rejected: per-node key in every lower branch (7-branch × 2-mirror drift class);
graph-native only (the graph write path that could author it is retired ⇒ dead config by construction).

## Slices (each shippable + fail-closed; ship order S4-pre → a → b → c → d)

| Slice | Delivers | Gate |
|---|---|---|
| **S4-pre** | commit-log housekeeping: delete `branch_commit_<batchId>.log` on successful commit (kept on failure — it IS the partial-commit record) | reactor |
| **S4a** | config shape end-to-end: parse → `PipelineConfig` → lift overlay → lower/toMap round-trip (+ UI mock mirror) + **`StepDisableArming`** save/prepare gate that **refuses every non-empty list on an active pipeline** ("park semantics not yet available"), WARNING on inactive drafts (the RouteArming severity split) | reactor + UI |
| **S4b** | durable park: manifest `parked_at` + park-table materialization at the disabled boundary (RowShaper per-branch mechanism), no markers/backup/watermark, `PARKED` stage projection; arming relaxes exactly one shape — nodes strictly inside an armed route→sink subtree | reactor + conservation fixture: parks, inspectable, not re-ingested |
| **S4c** | drain/resume: `DrainCommand` (ReprocessCommand's cousin) re-seeds the runner at the parked boundary from the park table; triggered by the re-enabling save + explicit route; refuses while disabled or park table missing | end-to-end park→enable→drain output parity on the fixture |
| **S4d** | canvas toggle (`NodeAttributes` + **both committed contracts regenerated**) + parked-Consignments inspection surface | contract tests + UI gauntlet |

## Refusals (at SAVE and `prepare()`, never a silent skip at rest)

1. any `disabled_steps` on a pipeline with no armed `route:` (flat lane has no park boundary; answer:
   dry-run/run-to-here) — lifting this is Phase 6's non-route parity project;
2. disabling `collect`/`parse`/`map`/anything at-or-upstream-of the route node (executor is seeded below
   them; answer: `active: false` or the Stage-B half-pause);
3. disabling the `transform.route` node itself (the divert's engagement anchor);
4. an unknown step id (a typo must not become a silently-enabled step);
5. drain while still disabled, or with the park table gone (loud stop, the
   `guardAgainstCompactedOutputs` posture);
6. until S4b: every non-empty list on an active pipeline (the S4a gate);
7. the permanent postures stand unchanged (clone-mode, multi-schema+route, decision-rule /
   versioned-reference × branches) — `disabled_steps` adds no path around them.
