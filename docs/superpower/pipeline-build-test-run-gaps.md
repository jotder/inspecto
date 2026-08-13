# Build → Test → Run: the ingestion authoring journey, and where it breaks

**Status:** IN FLIGHT (opened 2026-08-02). Gap analysis + remediation plan.
**Steps 0–4 SHIPPED 2026-08-02.** Step 5 (test against real data) is the remaining product gap — see §3.
Three findings from implementation changed the plan as written; they are recorded inline below (§2.1,
§2.4, §3 Step 4). Read those before assuming the original text still holds.
**Trigger:** operator question — *"how would a user build and run a data pipeline? we need to give
users a decent way to build → test → run data ingestion."* The inventory existed; the **journey** had
never been traced end-to-end.

**Scope:** the authoring journey only. Executing a richer graph is the separate
[`branch-aware-executor-plan.md`](branch-aware-executor-plan.md); this plan is about a user being able
to *build*, *see it work*, and *turn it on* without hitting a dead end.

---

## 1. The journey as built

| Step | Path | Verdict |
|---|---|---|
| **Build** | Onboarding wizard (`POST /config/write`) **or** graph editor (`PUT /pipelines/{name}/graph`) — both write the same canonical `<id>_pipeline.toon` | Works. Round-trips for ordinary topologies. |
| **Test** | dry-run · parser preview (the two that exist against a real backend) | **Still the hole.** The 404ing affordances are gone (§2.1), so the journey is now *honest* — but a user still cannot run their pipeline over their own files before arming it. Step 5. |
| **Run** | `active: true` → poll loop (`PipelineScheduler`), or `POST /runs/{name}/trigger` (fires even when inactive; only `template:true` is refused 409) | Works. |

Build and Run are sound. **The middle step is the hole**: a user cannot run their pipeline against
their own data before turning it on. Steps 0–4 removed the *lies* around that hole (dead buttons,
silent activation failures, save-time surprises); Step 5 fills the hole itself.

---

## 2. Gaps

### 2.1 — G1 · Two of three test affordances call endpoints that do not exist ⚠ HIGHEST

| Affordance | Calls | Reality |
|---|---|---|
| **"Run to here"** (inspector) — `pipelines.service.ts:349` | `POST /pipelines/authored/{id}/run?to={nodeId}` | **Never registered.** `PipelineRoutes.java:69` says *"NOT '.../run': that path is the editor's scratch-only run-to-here contract"* — reserved, never built. **404.** |
| **"Test"** (node config dialog) — `node-config.dialog.ts:397` | `POST /components/{type}/{id}/test` passing `node.type` + `node.id` | **Wrong resource.** Routes exist only for bare `transform`/`grammar`/`sink` + a *registered component name* (`ComponentRoutes.java:42-44`). A node supplies `transform.filter` + `filter_1` — both segments wrong. **404.** |
| **Dry-run** — `pipeline-dry-run-panel.component.ts:27` | `POST /pipelines/authored/{id}/dry-run` (`PipelineRoutes.java:946`) | Works, but over **user-typed synthetic JSON**, not the real files in the inbox. |

Shipping buttons that 404 is worse than not shipping them. **Decide per button: implement or remove.**

> **RETRACTED (2026-08-02).** An earlier revision of this file claimed there was "no `/components/*`
> route anywhere in the Java backend" and that `ComponentRoutes.java` did not exist. **That was wrong**
> — the file is at `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` and registers the
> test routes at lines 42-44, exactly as originally reported. The bad claim came from a grep run with a
> stale working directory (inside `inspecto-ui/`, which contains no Java). The original table above is
> correct; trust it.
>
> Verified live against a running backend: `GET /components/grammar` → **200**;
> `POST /components/transform.filter/filter_1/test` → **404**. The routes take a *literal*
> `transform`/`grammar`/`sink` segment plus a **registered component name**, and the dialog sends the
> node's dotted type + node id, so both segments miss.
>
> This means a real fix is plausible rather than impossible: map the dotted type to its family segment
> and pass the node's registry ref. It is not a pure repoint, though — a node with inline config binds
> no registered component, so there is nothing for `previewTransform` to look up. Scope that before
> ungating.
>
> **Resolved by gating, not deleting.** Both affordances are now hidden unless
> `environment.mockFlows` is on — the flag whose own comment already scopes it to "dry-run,
> per-processor test". Rationale: `run-to-here.dialog.ts` is a complete, working, mock-backed feature
> and is *exactly* the Step 5 UI; deleting it would mean rebuilding it. So the editor no longer lies
> against a real backend, and the offline demo is unchanged.

### 2.2 — G2 · No authoring-time signal that a node cannot be saved

`LOWERABLE` is a `private static final Set` (`PipelineEditable.java:43`) holding 9 of the 20
`BuiltinNodeType`s. `GET /pipelines/node-types` exposes **no** lowerability flag
(`PipelineNodeType` = type/category/label/description/accepts/emits/emitsNamedRoutes), so the palette
renders all 20 identically and the user learns at Save via a 422 `UNSUPPORTED_NODE`.

⚠ The 2026-08-02 editor redesign **amplified this**: the palette went from a hidden popup to a
permanent, well-organised menu of 20 options, 11 of which cannot be saved.

### 2.3 — G3 · Refusals are a first-only transient toast

`showRefusals` (`pipeline-editor.component.ts:483`) takes `refusals[0]` and toasts it. Four bad nodes
= four save→fix→save cycles, each message vanishing. The **Validation dock** shipped in the same
redesign already renders a persistent, click-to-select-node findings list — refusals belong there.

### 2.4 — G4 · The two build doors have asymmetric completeness gates

The editor's save enforces strict mode — `NO_ACQUISITION` / `NO_PARSER` / `NO_PERSISTENT_SINK` /
`PARSER_NO_SCHEMA` (`PipelineEditable.java:207-218`, gated on `g.active() || existing.isEmpty()`).
Onboarding's `POST /config/write` enforces **only** spec + safety; `ConfigSpecs.pipeline()`
(`ConfigSpecs.java:89-92`) requires just `name`, `dirs.poll`, `dirs.database`.

**VERIFIED 2026-08-02 — this is a real, silent, permanent failure.** The trace narrowed *and* sharpened it:

- Parser and sink **cannot** actually be missing: `dirs.database` is `require()`d by the parser
  regardless of `active`, and a parser defaults to `delimited` CSV. So those two are structurally
  guaranteed, not a real gap.
- **`active: true` + no schema IS reachable, and it fails silently:**
  1. `PipelineConfigParser.parse` (`PipelineConfigParser.java:384-389`) hard-throws
     `IllegalArgumentException("active: true but no schema is configured…")`.
  2. But `ConfigRoutes.writeConfig` (`ConfigRoutes.java:110-171`) **never calls
     `PipelineConfig.load`/`fromMap`** — it runs spec/safety findings and writes bytes. The write
     returns **`written: true`, no error**, for a file that cannot be loaded.
  3. `ConfigRegistry.rebuild` (`ConfigRegistry.java:102-104`) catches the throw, emits one
     `log.warn("Could not load config …")`, and **omits the pipeline from the index**.
  4. `PipelineScheduler.selectDue` (`PipelineScheduler.java:227-230`) → `configForPath(...).orElse(null)`
     → `continue`. **Skipped every cycle, forever. No crash.**
- **Operator visibility: effectively zero.** One backend WARN line. No metric, no run record, nothing in
  the Runs pane — the pipeline never enters the index, so it cannot even produce a *failed* run.

⚠ **Severity note:** G1 fails *visibly* (a 404 the user sees). G4 **succeeds visibly and then does
nothing**, with no UI signal at all. Arguably the worse of the two.

> **Residual unknown RESOLVED (2026-08-02): the silent branch is the one users hit.** The wizard calls
> `registerPipeline` **only on initial create** (`onboarding-create.dialog.ts:341`) — and create always
> writes `active: false` (`:318`, hardcoded). Activation happens later in the Publish stage as
> `saveBlock({ active: true })` (`publish-pane.component.ts:114`), which is a plain
> `/config/write overwrite:true` with **no register call after it**. So the exact moment a user arms a
> pipeline was the moment with no feedback path at all.
>
> **FIXED.** `ConfigRoutes.armedWithoutSchemaFindings` adds an ERROR finding at write time, so the
> Publish-stage flip now 422s with the schema named. Deliberately narrower than the "call
> `PipelineConfig.fromMap` and surface any parse failure" fix this plan originally proposed: `fromMap`
> also hard-throws on an *unresolvable* schema reference, which `schemaFileFindings` intentionally
> keeps a WARNING (the file may be created after the save, or belong to another host). A blanket load-
> check would have silently converted that warning into a hard failure. Verified live: armed+no-schema
> → 422; `active:false` draft → 200; armed with an unresolvable ref → 200 + warning.

### 2.5 — G5 · Silent one-way door on grandfathered flows

A `*_flow.toon` containing non-lowerable nodes **opens** fine (`GET …/graph/raw` always lifts) but can
never be saved back. Nothing indicates this until the user has edited and clicked Save.

---

## 3. Plan

**Step 0 — close G4. ✅ SHIPPED.** `ConfigRoutes.armedWithoutSchemaFindings` returns an ERROR finding
for `active: true` with no schema source (schema_file / schemas[] / plugin ingester), reusing the
existing findings→422 path. Narrower than the `PipelineConfig.fromMap` load-check originally proposed
here — see §2.4 for why a blanket gate would have regressed the deliberate schema-file WARNING.
→ verified: live probe gives 422 naming the schema for armed+no-schema, 200 for an `active:false`
draft, and 200+warning for armed with an unresolvable ref. Three tests in `ControlApiConfigWriteTest`.

**Step 1 — stop lying to the user (G1). ✅ SHIPPED (by gating).** Both affordances are hidden unless
`environment.mockFlows`. Neither was deleted: run-to-here is a complete mock-backed feature and is
literally the Step 5 UI. See the correction in §2.1 — the `/components` surface does not exist at all.
→ verified: with the real backend, the editor renders no *Run to here* and no *Test processor*; no
console errors.

**Step 2 — early signal (G2). ✅ SHIPPED.** `lowerable: boolean` added to `GET /pipelines/node-types`
(`PipelineProjection.catalog()` → new `PipelineEditable.isLowerable`). Non-lowerable palette entries
are disabled, non-draggable, dimmed, with a tooltip saying they cannot be saved.
→ verified against the live endpoint: **9 enabled / 11 disabled**, 20 total. The mock↔server contract
is pinned by name in `pipelines.handler.spec.ts` (the laxer-mock failure mode), plus a backend
assertion in `ControlApiPipelinesTest`.

**Step 3 — persistent refusals (G3). ✅ SHIPPED.** `showRefusals` now maps **every** refusal into
`findings` and opens the Validation dock (click-to-select already existed); the toast is reduced to a
count pointing there.
→ verified: a 3-refusal save yields 3 persistent findings, node ids preserved, dock opened.

**Step 4 — one-way door (G5). ✅ SHIPPED — but as a warning banner, NOT read-only.** The plan said
"mark the editor read-only". **That was wrong:** deleting the offending node is exactly how a user
makes a grandfathered pipeline saveable again, so read-only would have locked out the only repair
path. The editor now shows a warning banner naming the offending types and stays fully editable.
→ verified by unit test (`unsupportedNodes`): stays silent until the type catalog loads (no crying
wolf), then lists the non-lowerable node ids. Not reproducible in the live preview — every pipeline
lifted from a flat config is lowerable by construction, which is the point.

**Step 5 — the real one: test against real data. ⬜ REMAINING.** Give the user a bounded run over
actual inbox files with no writes. This is now the *only* thing standing between Build and Run.
Notably, the UI is already built and working against the mock (`run-to-here.dialog.ts`, file picker,
per-relation counts, sample table) — Step 1 gated it rather than deleting it precisely so this step is
a backend job, not a rebuild. What is missing on the server (per the feasibility pass):
- reading actual inbox files and running the real ingest/parse stage — `PipelineDryRun` is strictly
  synthetic-rows-in-memory and skips parsing entirely;
- a stop-at-node cutoff — `PipelineExecutor.dryRun` has no partial-graph primitive;
- registering the reserved `POST /pipelines/authored/{id}/run?to={nodeId}` (`PipelineRoutes.java:69`),
  which must stay scratch-only and never fire a production run.
Estimated **medium/large**. Flip `mockFlows`-gating off for run-to-here when it lands.

#### Step 5 — re-grounded 2026-08-14, and sliced

**Every premise above was re-verified against current source and all of it holds.** Stated explicitly
because the neighbouring BACKLOG rows have a poor record (four of five taken on 2026-08-13 had stale
premises) — **this one is accurate; do not spend another shift re-scoping it.** Corrections and detail:

- `PipelineRoutes.java` registers only `dry-run` and `trigger` — **`:77-81`**, not `:69` (citation drift).
  The in-place comment already states `run?to=` "must never fire a production run".
- `PipelineDryRun.run` requires non-empty `sampleRows` (`:66-114`, guard at `:77-78`) and seeds at the
  parser/entry node (`seedNodeOf`, `:174-180`). Its own javadoc `:24-25` concedes "the acquisition/parse
  stage upstream of the seed is not exercised here".
- `PipelineExecutor.dryRun` (`:203-229`) walks `topoOrder(g)` over the **whole** graph. No stop-at parameter.
- ⚠ **DRYRUN-1/DRYRUN-2 (2026-08-13) did not shrink this.** They added a `ReferenceResolver` param and a
  `warnings` list to the same file — orthogonal to all three gaps.
- **The UI is fully built, not a sketch:** `pipelines.service.ts:495-505` `runToNode(id, nodeId, files)`
  already POSTs this exact URL with a `files` body; `run-to-here.dialog.ts:29-33` already picks inbox files
  through the connection "Explore" tree. **The backend route is the only absent piece.**

⚠ **The hard part is NOT reading the files — it is suppressing the ingest path's side effects.** The real
path (`BatchProcessor` / `BatchIngestStrategy`) does not merely parse: it writes the file-status and batch
ledgers, moves/consumes inbox files, writes to `dirs.database`, and emits events. **A test run must do none
of these**, and a test run that does even one of them is worse than no feature at all. Three ways to get
that, in descending order of preference:

- **(c) Isolation by construction — RECOMMENDED.** Run the real path against a config whose every output
  path is redirected to scratch. Mirrors the 2026-08-13 launcher lesson (derive it so the rule holds *by
  construction* rather than by a flag someone can forget). **Prerequisite to verify first: that every
  side-effecting destination is redirectable by config** — `dirs.database`, the ledgers, the event sink,
  and inbox consumption. If any one is not, this degrades to (b).
- **(b) Extract the pure parse step** (file → rows) and skip `BatchProcessor` entirely. Safer than (a),
  but risks duplicating the CSV-builtin vs plugin-ingester dispatch — extract, do not copy.
- **(a) Thread a `dry`/`scratch` boolean through the ingest path — ⛔ AVOID.** One missed branch means a
  *test* run mutates production state. This is the trap; do not take it because it looks smallest.

**Reusable seams (this does not start from zero):** `BatchIngestStrategy.openTempDb`/`scratchDir`
(`:308-329`) already resolves a scratch dir from `dirs.temp` / `processing.duckdb.temp_directory`
**independent of the production `dirs.database` root** — i.e. the row's "must stay scratch-only"
guarantee already exists rather than needing invention. `DuckDbUtil.tempDbFile`/`deleteTempDb`
(`:64,84,261`) is the scratch-DB lifecycle `PipelineDryRun` already uses. Stage A's earlier extraction of
`BatchProcessor.finalizeSource` (so `commit` delegates to it) is evidence the parse half and the commit
half **are** separable — confirm that before designing against it.

**Slices — ship in this order, each independently valuable:**

- **5a · real files, full graph, no cutoff.** Parse N picked inbox files into a scratch DuckDB and run the
  whole graph, replacing `sampleRows`. Converts "synthetic rows" into "real files" and is the bulk of the
  user value on its own. Settle (c)-vs-(b) here.
- **5b · stop-at-node cutoff.** Thread a target set through the `topoOrder` walk. ⚠ **That walk is shared
  with production `execute`** — add an overload whose default is "no cutoff" so the production path stays
  byte-identical, and pin that with a test. This is the slice most likely to cause a regression far from
  where it was edited.
- **5c · route + ungate.** Register `POST /pipelines/authored/{id}/run?to={nodeId}`, then flip the
  `environment.mockFlows` gate off for run-to-here. Two things to get right:
  - ⚠ **Jail the `files` body to the pipeline's configured inbox.** As specified it accepts caller-supplied
    paths, so without containment this is an arbitrary-file-read over HTTP. This is the one place where the
    still-open "config-declared paths resolve unjailed" BACKLOG row becomes **reachable rather than
    defence-in-depth** — do not ship 5c without it.
  - **Permission gate:** follow the `DecisionRoutes` precedent — `/simulate` is `canAuthorWorkbench`,
    `/apply` is `canOperateRuns`. A test run is a simulate, so **`canAuthorWorkbench`**.
  - **Response shape is already pinned by the mock** the UI renders against (per-relation counts + sample
    table). Match it, and pin it by spec the way Step 2 pinned the node-type list in
    `pipelines.handler.spec.ts` — a laxer mock is the exact failure mode that convention exists to kill.

**Sizing: multi-shift.** Three separable hard problems, not one task. 5a alone is a plausible shift.

---

## Links

- Node-type / execution inventory + the three gates: [`../BACKLOG.md`](../BACKLOG.md) "Branch-aware executor"
- Pipeline vs Job (in-motion vs at-rest): [`../okf/backend/pipeline-graph/pipeline-graph-design.md`](../okf/backend/pipeline-graph/pipeline-graph-design.md) §3.8
- Editor shell as-built: [`../okf/frontend/features/pipelines.md`](../okf/frontend/features/pipelines.md)
- Onboarding authoring: [`../okf/backend/control-plane/onboarding-authoring.md`](../okf/backend/control-plane/onboarding-authoring.md)
