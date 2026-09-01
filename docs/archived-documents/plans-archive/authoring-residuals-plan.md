# Authoring residuals — solution specification (stage-1 close-out)

**Status: FULLY DRAINED 2026-09-02 — R1/R2/R4/R5/R6 shipped 2026-09-01, R3 + the
UI-migration-onto-R2 follow-up shipped 2026-09-02; only R7 (future sketch, unscheduled by design)
remains, so this plan is ready to distill+archive at the next handoff.** R3 as-built:
`route.branches[].steps[]` in the shared step grammar; lift flattening / lower reversal with
terminal-sink branch pairing and `UNSUPPORTED_BRANCH_STEP`; mid-branch kinds = the fork-executable
set (filter / consignment-scope dedup / summarize; join + windowed dedup + nested route REFUSE by
name at all five gates); Recipe compiler/converter refusal sites replaced by support; TS mirror
equal-never-stricter. 🔴 The R3 unblock verdict's named boundary was directionally wrong — route
pipelines never reach `graphLaneCarries`; the real seam was `engages`/`dataFedSinkCount`, extended
deliberately (recorded in `execution-residuals-plan.md` too). Bundle-UI migration: Duplicate + row
export ride `GET /pipelines/{name}/bundle` / `POST /pipelines/import`; the legacy JSON export
stays as the reader for old files. As-built: `okf/frontend/features/pipeline-editor.md` (R4/R5/R6),
`okf/backend/pipeline-graph/editable-round-trip.md` §21 (R2) and §16 (R1's coded findings).
Notable as-built deviations from this spec, all sound: R4's drag-coalescing was moot (node moves
never touch the model); R2 writes satellites before the safety gate and deletes them on refusal
(config-relative refs only validate against files that exist — net behavior unchanged); R2's
import transport is a raw `application/zip` body with query params (no multipart parser exists in
the codebase, matching `POST /spaces/{id}/import`). The stage-1
authoring-lifecycle analysis shipped its 9-item package the same day
(`okf/frontend/features/pipeline-editor.md` + `okf/backend/pipeline-graph/editable-round-trip.md`
record the as-built); this plan is the solution spec for what was FILED rather than built. Prior
art consulted: `C:\sandbox\incubetor\docs\wiki` (the DataForge Studio design wiki — same
framework-free Java + Angular religion; treat as prior art, never as truth about this repo). Its
three transferable mechanisms are adopted below: the diagnostic record with a separate
`actionableGuidance` field, descriptor-declared cardinality refused at connect-time AND
server-validate, and the subgraph **flattening pre-pass**. Its two gaps (dependency closure,
undo/redo) are original work here.

**Sequencing (operator 2026-09-01):** R1 → R2 → R3 (scheduled next after the stage-2 execution
analysis) → R4 → R5/R6 (small, any shift) · R7 is a future sketch, not scheduled.

---

## R1. Diagnostic upgrade — codes, guidance, and the one way to cite a key

**Problem.** Findings are `{severity, fieldPath, message}` prose; remediation is fused into the
message (or absent); refusal codes exist only on `PipelineCompileException`; and
COLLECTOR-ERRMSG-1 (BACKLOG §6): `PipelineConfigParser`'s error strings still cite the retired
`source.*` path for `collector:` keys — an operator fixing the named key edits a block nothing
reads.

**Solution (adopting the wiki's findings shape).**
- `Finding` gains two ADDITIVE optional fields: `code` (stable `ERR_`/`WARN_`-prefixed
  SCREAMING_SNAKE, grouped by category: Topology / Arming / Schema / Safety / Parsing) and
  `guidance` (what to DO, structurally separate from what is WRONG). ⚠ `Finding` is a shared
  record — check `@PublicApi` exposure before the change; additive fields with null-tolerant
  serialization keep the envelope compatible.
- One catalog class owns the codes; the arming pre-checks (`armedWithoutSchema`, `routeArming`,
  `stepDisable`), the validator issues (already coded), and the `PipelineCompileException` codes
  all register there. New rule: **a message names the actual entities and values** ("branch 'apac'
  has no where:" already complies); **`fieldPath` is the ONE way to cite a config key**
  (`collector.duplicate.mode`, `route.branches[1].where`) — which is what makes a stale key
  citation a visible one-owner defect rather than fused prose.
- **COLLECTOR-ERRMSG-1 closes inside this slice**: sweep `PipelineConfigParser`'s message strings
  to `collector.*` fieldPaths (message strings only; ⛔ no parsed key renames).
- UI: the Validation dock renders `guidance` as its own line under each finding and gains the
  All / Errors / Warnings filter; jump-to-Step already exists.

**Acceptance.** A findings-shape contract test (additive fields serialize; absent stays absent);
the parser-message sweep pinned by asserting the `collector.*` spelling in the refusal bodies the
existing arming tests already read; dock spec asserts the rendered guidance line.

## R2. TRANSFER-1 — server-side selective bundle for canonical pipelines

**Problem (TRANSFER-ARCH-1, BACKLOG §6).** A canonical `*_pipeline.toon` has no selective
dependency-closure export: the metadata bundle's `authored-pipeline` kind serves the RETIRED
`*_flow.toon` store; the only transfers are the whole-config-tree datasource zip and the
client-composed stream-config bundle.

**Solution (server-side bundle route — operator direction).**
- `GET /pipelines/{name}/bundle` → zip: `manifest.toon` (format id + version, pipeline id,
  satellite inventory with per-entry sha256, requirements) + the pipeline toon + its **closure**:
  `processing.schema_file`, every `schemas[].schema_file`, per-segment schemas
  (`parsing.<asn1|plugin>.segments` values), a `processing.grammar` file, and the
  `<id>_enrich.toon` companion. Closure enumeration reuses the engine's own reference resolution
  (`resolveSchemaRef`/config-relative W1b rules) — ⛔ never a second hand-rolled resolver. W3's
  portable bare-basename refs make the zip self-contained by construction (the wiki's
  relative-path-confinement lesson, which this repo already half-adopted).
- **Secrets never travel**: a `collector.connection` becomes a manifest `requirements[]` entry
  (profile name + connector type), exactly the client bundle's rule; a literal secret-looking
  value is masked and reported.
- `POST /pipelines/import` (multipart zip, body `{name?, conflict: refuse|overwrite|rename}`):
  gate order per the `endpoint` skill (write-root 503 → manifest/spec 422 → zip-slip path jail
  403 (BundleImporter precedent) → name conflict 409 unless the policy says otherwise → atomic
  writes, satellites BEFORE the pipeline). Import retargets INSIDE each body (a config's own
  identity field decides its file): `name`, `triggers.on_pipeline`, `input/output.database`,
  dirs re-derived from the space convention; **always lands `active: false`**.
- The metadata bundle's `authored-pipeline` kind stays serving grandfathered flows only; its docs
  and the export UI point at this route for canonical pipelines. The editor's Duplicate and the
  Open-dialog row export MIGRATE to this route once shipped (the client bundle stays as the
  reader for old exported files).
- ⚠ The config-namespace vs registry-id collision on the word *schema*
  (`okf/frontend/features/onboarding.md`) is why this is NOT a new `BundleRoutes.WRITABLE_TYPES`
  kind.

**Acceptance.** Real-HTTP round-trip: export a multi-schema + enrich pipeline, import under a new
name into a second space root, byte-compare satellites, assert retargeted leaves and
`active:false`; conflict-policy matrix (refuse 409 / overwrite / rename); zip-slip refusal; a
`connection` travels as a requirement with no secret material. **File-level, not fromMap** — the
config-format lesson.

## R3. MIDBRANCH-1 — per-branch `steps:` sub-chains (scheduled next after stage-2 analysis)

**Problem.** A `route:` branch cannot carry transforms; both authoring surfaces refuse it today
(`superpower/mid-branch-transforms-design.md` — that design doc's problem statement stands and
this section supersedes its open questions).

**Solution (format + flattening pre-pass).**
- Format: `route.branches[].steps[]` — the SAME ordered-step vocabulary as top-level `steps:`
  (one grammar, shared builders, so the two cannot drift — the `RecipeConverter` lesson). The
  branch keeps `{key, where, database}`; its `steps` are the chain between the route Step and
  that branch's sink.
- **Execution = flattening at lift, per the wiki's pre-pass**: `PipelineLift` expands each
  branch's chain into ordinary nodes wired `route:<key> → step₁ → … → sink`; the executor and the
  ingest graph fork (`ConsignmentGraphRunner`) keep consuming a flat DAG and never learn the
  concept. Lower reverses it: chain nodes between a `route:<key>` edge and its branch sink write
  back into that branch's `steps[]`.
- Arming: `RouteArming` extends to walk branch chains (a mid-branch Step needing `output_store:`
  semantics is N/A — branches execute in the ingest lane; kinds allowed mid-branch = the Stage-1
  executable set, others refuse by name at save with an R1-coded finding).
- UI: the Recipe view's branch rows gain nested step rows (the step-cards component already
  renders nested route branches); the canvas renders the flattened form it already knows.

**Acceptance.** File-level round-trip (graph → lower → toToon → disk → load → lift, chain order
preserved per branch); an executor test proving a branch chain transforms only its branch's rows;
a save refusal test for a non-executable mid-branch kind; UI step-cards spec for nested rows.
⛔ Do not start before the stage-2 execution analysis lands — the branch lanes are its subject.

## R4. UNDO-1 — snapshot-stack undo/redo per tab

**Problem.** No undo/redo; a wrong delete or apply is only recoverable by closing without saving.
No prior art in the wiki (their P2 row is unbuilt) — original design.

**Solution (bounded snapshot stack — operator direction).**
- Per-tab `{undo: Snapshot[], redo: Snapshot[]}` beside `cachedModels`; a Snapshot is the
  serialized editable-graph JSON (small — the model, never the G6 scene). Capture at the existing
  mutation choke points (every path that sets `dirty` — the one-effect mirror already enumerates
  them); **position drags coalesce** (one snapshot per drag end); cap 50, drop oldest.
- Ctrl+Z / Ctrl+Y (extends the existing keydown handler; same canAuthor gate as Ctrl+S). Restore
  replaces the tab's model and re-renders **locally** — ⛔ NEVER via `select()`, which is a
  server load that discards edits. Dirty recomputes against the last-saved baseline, so undoing
  back to the saved state clears the flag honestly. Stack survives a save (undo past a save
  re-arms dirty); cleared on tab close (matching `forgetTab`). A DIRTY definition drawer is
  outside the stack — its own Apply/Discard owns that state; an undo while the drawer is dirty
  confirms first (the existing destructive-confirm pattern).
- ⚠ The snapshot must be taken from the same serialization the save path uses
  (`buildConfiguredNode`-consistent), or an undo could restore a shape the save would refuse.

**Acceptance.** Specs drive the DOM per the dirty-arming rule: mutate → undo → model identical to
pre-mutation and dirty false at baseline; redo restores; cap eviction; drawer-dirty confirm; a
restored graph SAVES (round-trip through the real lower in a spec against the TS mirror).

## R5. Open-dialog MRU + pins (small)

localStorage `'inspecto.pipelines.mru'` (last ~8 opened ids) + a pinned set; the Open dialog gains
Pinned / Recent sections above the full list (search unchanged, storage try/caught, unknown names
dropped on render — same rules as the open-tab persistence).

## R6. Dataset-hop banner (small)

Activation's Dataset registration stays fire-and-forget (P6-b confirmed: idempotent by
`physicalRef`, never reverses an activation). Upgrade the failure surface from a transient toast
to a **persistent, actionable banner** in the editor (connectivity-banner pattern) with a Retry
that re-invokes the registration (safe — idempotent). 503-on-optional-module = explained panel,
never a toast (the standing rule).

## R7. Convert-to-composite (FUTURE SKETCH — not scheduled)

The wiki's bottom-up affordance: select N Steps → "Convert to subgraph" with boundary-port
proxies, drill-in breadcrumb, flattened at compile time. Recorded as the natural growth path
AFTER R3 (same flattening machinery); ⛔ no work before R3 ships and a real reuse driver exists
(the `.dgsub`-style reusable module is exactly the Grammar-Template class of problem — copy
semantics, never live bindings).

---

## Corrections this spec makes to the record

- `pipeline-editor.md`'s Known-gaps "single-slot inversion question" was STALE: multiplicity was
  resolved 2026-08-11 (ordered `steps:`, `MULTI_*` refusals deleted); still deliberately
  last-one-wins: `acquisition`, `gap`, `dedup.marker` (and `parser` refuses via `MULTI_PARSER`).
  Fixed in the same change as this spec.
- BACKLOG rows TRANSFER-ARCH-1 and COLLECTOR-ERRMSG-1 now point here (R2, R1).
