# Build → Test → Run: the ingestion authoring journey, and where it breaks

**Status:** IN FLIGHT (opened 2026-08-02). Gap analysis + remediation plan.
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
| **Test** | dry-run · "Run to here" · node "Test" · parser preview | **Broken — see §2.1.** |
| **Run** | `active: true` → poll loop (`PipelineScheduler`), or `POST /runs/{name}/trigger` (fires even when inactive; only `template:true` is refused 409) | Works. |

Build and Run are sound. **The middle step is the hole**: a user cannot run their pipeline against
their own data before turning it on.

---

## 2. Gaps

### 2.1 — G1 · Two of three test affordances call endpoints that do not exist ⚠ HIGHEST

| Affordance | Calls | Reality |
|---|---|---|
| **"Run to here"** (inspector) — `pipelines.service.ts:349` | `POST /pipelines/authored/{id}/run?to={nodeId}` | **Never registered.** `PipelineRoutes.java:69` says *"NOT '.../run': that path is the editor's scratch-only run-to-here contract"* — reserved, never built. **404.** |
| **"Test"** (node config dialog) — `node-config.dialog.ts:397` | `POST /components/{type}/{id}/test` passing `node.type` + `node.id` | **Wrong resource.** Routes exist only for bare `transform`/`grammar`/`sink` + a *registered component name* (`ComponentRoutes.java:42-44`). A node supplies `transform.filter` + `filter_1` — both segments wrong. **404.** |
| **Dry-run** — `pipeline-dry-run-panel.component.ts:27` | `POST /pipelines/authored/{id}/dry-run` (`PipelineRoutes.java:946`) | Works, but over **user-typed synthetic JSON**, not the real files in the inbox. |

Shipping buttons that 404 is worse than not shipping them. **Decide per button: implement or remove.**

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

> Residual unknown (small, targeted): does the wizard call `registerPipeline` after `/config/write`?
> If yes, the schema case at least surfaces as a synchronous error to that one caller
> (`CollectorService.java:944-951` rethrows). If it relies on passive poll-loop pickup, it is fully
> silent. **One UI trace decides which branch users actually hit.**

### 2.5 — G5 · Silent one-way door on grandfathered flows

A `*_flow.toon` containing non-lowerable nodes **opens** fine (`GET …/graph/raw` always lifts) but can
never be saved back. Nothing indicates this until the user has edited and clicked Save.

---

## 3. Plan

**Step 0 — close G4 (DONE: traced; now fix).** Confirmed a silent permanent no-op. Fix is small and
narrow: make `POST /config/write` **load-check** what it just wrote for `type=pipeline` (call
`PipelineConfig.fromMap` and return the parse failure as a 422 finding), so an `active:true`
schema-less pipeline is refused at write instead of accepted and ignored.
→ verify: writing `active:true` with no schema returns 422 naming the schema; writing `active:false`
still succeeds (drafts must stay writable).
→ also: one UI trace to settle whether the wizard registers after write (residual unknown, §2.4).

**Step 1 — stop lying to the user (G1).** Per button, implement or remove:
- *Run to here* — implement the reserved `?to={nodeId}` scratch run, or delete the inspector action.
- *Node Test* — repoint at a real endpoint, or delete the dialog action.
→ verify: no affordance in the editor issues a request that 404s (assert in the e2e/preview pass).

**Step 2 — early signal (G2).** Add `lowerable: boolean` to `GET /pipelines/node-types`; grey out
those palette entries with a "flow-only" tooltip.
→ verify: palette renders 9 enabled / 11 disabled against the live endpoint; mock matches the server
(⚠ a mock more lenient than the server is the known failure mode — pin it in `pipelines.handler.spec.ts`).

**Step 3 — persistent refusals (G3).** Route **all** refusals into the Validation dock, click-to-select
the offending node. Plumbing already exists.
→ verify: a 3-unsupported-node graph lists 3 findings in one save attempt.

**Step 4 — one-way door banner (G5).** On lift, if the graph contains a non-lowerable node, mark the
editor read-only with an explanatory banner instead of failing at Save.
→ verify: opening a grandfathered flow shows the banner and disables Save.

**Step 5 — the real one: test against real data.** Give the user a bounded run over actual inbox files
with no writes. Largest piece; scope after Steps 0–4 land.

**Sequencing note:** Steps 2–4 are all in the editor redesigned on 2026-08-02 and are small. Step 1 is
the one a real user hits first. Step 5 is the actual product gap.

---

## Links

- Node-type / execution inventory + the three gates: [`../BACKLOG.md`](../BACKLOG.md) "Branch-aware executor"
- Pipeline vs Job (in-motion vs at-rest): [`../okf/backend/pipeline-graph/pipeline-graph-design.md`](../okf/backend/pipeline-graph/pipeline-graph-design.md) §3.8
- Editor shell as-built: [`../okf/frontend/features/pipelines.md`](../okf/frontend/features/pipelines.md)
- Onboarding authoring: [`../okf/backend/control-plane/onboarding-authoring.md`](../okf/backend/control-plane/onboarding-authoring.md)
