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
| **S4b** | durable park: manifest `parked_at` + park-table materialization at the disabled boundary (RowShaper per-branch mechanism), no markers/backup/watermark, `PARKED` stage projection; arming relaxes exactly one shape — nodes strictly inside an armed route→sink subtree. **AS BUILT 2026-08-28:** `PipelineExecutor.ParkWriter` hook (null on every scratch path — the bypass stays); `graphWriteAndTrace` COPYs the branch table to `dirs.backup()/parked/<batchId>__<nodeId>.parquet` and records it in the batchId-keyed `ParkedBranches` stash (the UnpackOrigins lifecycle idiom — threading `IngestOutcome`'s 8 ctor sites was rejected); `BatchProcessor.parkSource` writes the manifest (`parkedAt`/`parkedTables`, members `PARKED`) and moves plain originals to the park home — ⚠ an unpack EXPANSION product's original stays in the inbox (batch.max_files:1 splits an archive across batches; moving the shared original would strand siblings) and re-expands next cycle, the crash posture. Batch status = `PARKED` → new Signal `pipeline.batch.parked` (neither committed nor failed). The commit log is KEPT on park (drain's resume record). Arming: `StepDisableArming.parkableSinkIds` mirrors the lift's `sink__d<i>` grammar (legit only because armed route ⇒ single-schema ⇒ empty suffix), pinned VERBATIM by `PipelineLiftTest.parkableSinkIdsMatchTheLiftedGraph`; refusals: no armed route · non-parkable/unknown id · the route node · ALL branches (`active: false` is that ask). | reactor + the end-to-end fixture (`RouteIngestEndToEndTest.aDisabledBranchSinkParksTheConsignment`): parks, inspectable, original in the park home, not re-ingested, commit log kept |
| **S4c** | drain/resume: `DrainCommand` re-seeds from the park table and completes the batch. **AS BUILT 2026-08-28:** drain is **not a graph re-walk** — the park boundary IS the materialisation boundary, so nothing is left to execute between the park table and the write; drain reads each `parkedTables` Parquet back with `read_parquet`, writes it through the ordinary `IngestSinkWriter`, then runs `BatchProcessor.finalizeSource` for the WHOLE batch. Three findings forced the shape: (1) the branches that committed BEFORE the park hold their `PartitionOutput`/lineage/bounds **only in the ingest JVM's memory** and `BranchCommitLog` records branch *ids* alone — so park now writes a **`ParkedCommit` sidecar** (`<parkHome>/<batchId>__pending.json`) that drain consumes and deletes, or finalisation would register half a batch; (2) ⚠ **the coordinator's `SOURCE` row is NOT this lane's finalisation signal** — the ingest path passes a no-op `SourceFinalizer`, so the park run had already recorded `SOURCE` having finalised nothing; drain uses `BranchCommitCoordinator` only for the durable, idempotent per-BRANCH skip and runs the commit tail itself, exactly as `BatchProcessor.commit` does; (3) every poll-relative computation in `finalizeSource` (rel path, backup dest, marker, ledger key, stage rows) assumes the original is in the inbox, so drain **moves it back from the park home immediately before** finalising rather than mirroring five relativisations — the file is in the inbox for the length of the commit tail, the same idempotent posture a crash mid-commit always had. **Trigger: explicit route only** (operator, 2026-08-28) — `POST /runs/{name}/drain`, reprocess's sibling; a config save must not start batch work as a side effect. Refusals (409, reason verbatim): not parked · step still in `disabled_steps` (config is the truth) · unknown node · park table or sidecar missing · unpack-expansion members (their original re-expands and re-parks as a new batch). | `RouteIngestEndToEndTest.reEnablingTheStepAndDrainingCompletesTheParkedConsignment` (park → refuse → re-enable → drain → row conservation, both branches in one manifest, original in backup, artefacts consumed, second drain refused, no re-ingest) + `ControlApiTest` gate test + `ParkedCommitTest` |
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
