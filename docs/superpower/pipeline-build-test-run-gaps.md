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

> **CORRECTION (2026-08-02, during the fix).** The table above understated it for the node Test button.
> There is **no `/components/*` route anywhere in the Java backend** — not the dotted-type variant, not
> the bare `transform`/`grammar`/`sink` ones. The whole `/components` surface is mock-only. (An earlier
> exploration pass reported `ComponentRoutes.java:42-44` as the registration site; **that file does not
> exist**. Treat the original table's Reality column for row 2 as wrong.)
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
assertion in `ControlApiFlowsTest`.

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

---

## Links

- Node-type / execution inventory + the three gates: [`../BACKLOG.md`](../BACKLOG.md) "Branch-aware executor"
- Pipeline vs Job (in-motion vs at-rest): [`../okf/backend/pipeline-graph/pipeline-graph-design.md`](../okf/backend/pipeline-graph/pipeline-graph-design.md) §3.8
- Editor shell as-built: [`../okf/frontend/features/pipelines.md`](../okf/frontend/features/pipelines.md)
- Onboarding authoring: [`../okf/backend/control-plane/onboarding-authoring.md`](../okf/backend/control-plane/onboarding-authoring.md)
