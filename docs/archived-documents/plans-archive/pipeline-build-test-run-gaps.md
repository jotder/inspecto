# Build → Test → Run: the ingestion authoring journey, and where it breaks

**Status:** ✅ **COMPLETE — ARCHIVED 2026-08-14.** Opened 2026-08-02; gap analysis + remediation plan.
**Steps 0–4 SHIPPED 2026-08-02, Step 5 (5a/5b/5c) SHIPPED 2026-08-14.** The whole journey is closed.
⚠ **This file is history, not current knowledge.** The durable as-built lives in
[`../../okf/backend/engine/pipeline-test-run.md`](../../okf/backend/engine/pipeline-test-run.md) and
[`../../okf/frontend/features/pipelines.md`](../../okf/frontend/features/pipelines.md); the one residual
(the node-config dialog's `/components/*` test affordance, still mock-gated) is a
[`../../BACKLOG.md`](../../BACKLOG.md) §Pipelines row. Read those, not this.
Several findings from implementation changed the plan as written; they are recorded inline below (§2.1,
§2.4, §3 Step 4, and the three Step-5 as-built sections). Read those before assuming the original text
still holds — **the plan was wrong about 5b's regression risk**, and says so at the end.
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

**Step 5 — the real one: test against real data. ✅ SHIPPED 2026-08-14** (5a + 5b + 5c; as-built in the
three sections below). Give the user a bounded run over
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
of these**, and a test run that does even one of them is worse than no feature at all.

##### ✅ DECIDED 2026-08-14 — approach, and why the drafted options were both wrong

The three drafted options were **(a)** thread a `dry` boolean ⛔, **(b)** extract a pure parse step, **(c)**
redirect every output path by config. Grounding the ingest path retired all three in favour of a fourth
that is strictly safer, because **the separation already exists structurally and needs no new code at all**:

**The side effects are not interleaved with the parse — they are three separate statements after it.**
`BatchProcessor.process` (`:51-87`) is exactly: `strategy.ingest(batch, cfg)` (`:58`), then
`commit` (`:70`), then `writeAudit` (`:82`), then `recordProvenance` (`:86`). **Call the first and stop.**

That single decision disposes of every destination that is *not* config-derived — and there are four,
which is precisely why option (c) as drafted would **not** have been airtight:

| Not config-derived | Resolved by | Lives in |
|---|---|---|
| `AcquisitionLedgers` (dedup fingerprint + DB-export watermark) | `-Dacquire.ledger.backend` / `.db.url` | `finalizeSource` (inside `commit`) |
| `ConsignmentOutputStores.record` | `-Dconsignment.outputs.backend`, per-space registry | `finalizeSource` |
| `FileStages.record` | `-Dfile.stages.backend`, per-space registry | `finalizeSource` |
| `PipelineBatchSignal::emit` | ambient `EventLog.current()` (space MDC, not the config) | `writeAudit` |
| `ProvenanceStores.record` | process-wide registry | `recordProvenance` |

**All five live inside the three statements we do not call.** So they are avoided by the call graph, not by
a flag and not by config redirection — nothing can forget to set them.

⚠ **But the ingest half is NOT side-effect-free on its own — one thing bites, and config redirection alone
does not fix it.** `CsvBatchStrategy` calls `QuarantineManager.quarantine` (`:109,117,131`) for an
unreadable / field-mismatched / empty member, and that does
**`Files.move(inputFile, …, REPLACE_EXISTING)`** on the **real inbox file**
(`QuarantineManager.java:69`). Its *destination* is `dirs.quarantine` and so is redirectable — but the
**source** is the user's actual file, so redirecting the destination just moves their file somewhere else.
**A test run over a malformed file would make that file vanish from the inbox** — the single worst outcome
this feature could have, and the one a "just point the dirs at scratch" reading would have shipped.

**⇒ The design is two structural containments, both by construction:**

1. **Call-graph containment** — invoke `strategy.ingest(batch, cfg)` only; never `commit` / `writeAudit` /
   `recordProvenance`. Kills all five non-config-derived destinations above.
2. **Filesystem containment** — **copy the picked files into a scratch poll dir** and point the config's
   `dirs` at a scratch tree. The quarantine move then can only ever touch our copy. The ingest half
   touches exactly **five** dirs — verified by enumeration, a closed set:
   `poll` · `database` · `errors` · `quarantine` · `temp` — plus each `cfg.sinks()[i].database()` on
   fan-out. Redirect all of them.

Neither containment relies on the other, which is the point: a mistake in one is still caught by the other.
Note also that constructing the `Batch` directly bypasses `CollectorProcessor.ingest` entirely, so the
dedup/marker layer (`MarkerManager`, `ledgerFilter`) never runs either.

⚠ **Do not "optimise" the copy in step 2 into a move or a bare path hand-off.** The copy *is* the
containment. A hardlink would also work on one filesystem (moving one link leaves the other intact) and
would avoid the I/O, but it fails across volumes — treat it as a later optimisation behind a fallback,
never the default.

##### 5a SHIPPED 2026-08-14 — as-built, and one lesson about testing this

`PipelineConfig.forScratchRun(Path)` (the config half) + `PipelineTestRun` (`com.gamma.inspector` — it
must live there, since `BatchIngestStrategy`, `CsvBatchStrategy`, `StreamingPluginBatchStrategy` and
`IngestOutcome` are all package-private). Batches come from the real `ConsignmentPlanner.plan` with the
same `SchemaResolver` idiom `CollectorProcessor` uses, so schema selection is not re-implemented.

`forScratchRun` also nulls the **commit-half** destinations (`backup`, `markers`, status/batches/lineage,
manifests, commit log) even though the commit half is never called — defence in depth, so a future caller
that does call it still cannot write to production state. `backup == null` is what makes the source-file
backup a no-op. Scratch lifecycle is deliberately the **caller's** (`deleteScratch`), because the parsed
output under the scratch root is what a preview reads back.

⚠ **Lesson — "the file is still there" is a test that can pass for the wrong reason.** The suite's first
version asserted only that the picked inbox files survived. A falsification probe (make `stage` hand over
the *original* paths instead of the copies) **did not fail that test**: `QuarantineManager`'s own
poll-root guard (`:59-61`) threw instead of moving, so the file survived for an entirely unrelated reason
and the batch merely went `FAILED`. The assertion now also pins the containment **positively** — the
staged copy must be found quarantined *inside the scratch root*, proving the move happened and landed on
the copy rather than never happening at all. Re-probed after strengthening: 2 tests fail, including that
one, for the right reason. **If you touch the staging logic, re-run that probe rather than trusting green.**

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
  - **5a-i · the parse half — ✅ SHIPPED 2026-08-14.** `PipelineTestRun.run(cfg, pickedFiles, scratchRoot)`
    parses real files through the real ingest path with zero production side effects (6 tests, incl. a
    falsification probe). See the as-built note below.
  - **5a-ii · the bridge to the graph — ✅ SHIPPED 2026-08-14.** `PipelineTestRun.sampleRows(result,
    outputFormat, limit)` reads the scratch outputs back as `List<Map<String,Object>>` — exactly the shape
    `PipelineDryRun.run` seeds from — so the existing per-relation counts + sample table (which the UI
    already renders) can be computed from real data. Reads through `SqlViews.reader` rather than a
    hand-built `read_*(`, with `hive_partitioning` **off** for `DatasetRelation`'s reason: partition
    columns are not part of the parsed row and would misrepresent what the pipeline produced. Bounded by
    `limit`; an `EMPTY`/`FAILED` run samples to an empty list rather than throwing (the caller decides
    whether that is a warning, since `PipelineDryRun` refuses an empty sample).
    ⚠ **Scope, stated honestly: this is the bridge, not the composition.** Nothing yet *calls*
    `PipelineDryRun` with these rows, because that needs a `PipelineGraph` — which is resolved at the
    route. **That wiring is 5c's**, and it is now a few lines rather than a design problem.
- **5b · stop-at-node cutoff. ✅ SHIPPED 2026-08-14** — see the as-built below. Original brief: thread a
  target set through the `topoOrder` walk. ⚠ **That walk is shared
  with production `execute`** — add an overload whose default is "no cutoff" so the production path stays
  byte-identical, and pin that with a test. This is the slice most likely to cause a regression far from
  where it was edited.
- **5c · route + ungate. ✅ SHIPPED 2026-08-14** — `PipelineRoutes.testRun` + `ControlApiPipelineTestRunTest`
  (5 tests over real HTTP). The gate is off (`scratchRunAvailable = true`), so **run-to-here now works
  against a real server**. As-built notes after the original requirements below.
  Register `POST /pipelines/authored/{id}/run?to={nodeId}`, then flip the
  `environment.mockFlows` gate off for run-to-here. With 5a shipped the body is: resolve the graph,
  `PipelineTestRun.run(...)` → `sampleRows(...)` → `PipelineDryRun.run(graph, rows, references)`, and
  `deleteScratch` in a `finally`. Two things to get right:
  - ⚠ **Jail the `files` body.** As specified it accepts caller-supplied paths, so without containment this
    is an arbitrary-file-read over HTTP. This is the one place where the still-open "config-declared paths
    resolve unjailed" BACKLOG row becomes **reachable rather than defence-in-depth** — do not ship 5c
    without it. **Contract settled 2026-08-14 by grounding the UI; build to this:**
    - **The paths are connection-relative, NOT absolute and NOT inbox-relative.** The dialog fills them
      from `probe.explore(connectionId, …)`, and `LocalConnectionWorkbench` builds each
      `ResourceNode.path` as `root.relativize(p)` with forward slashes (`:96`). So they are relative to
      the **connection's `base_path`**.
    - **⇒ The jail root is that connection's `base_path`, and it MUST be derived server-side**, not taken
      from the request. Fortunately that is already forced: `runToNode` sends only `{files}` + `?to=`
      (`pipelines.service.ts:499-505`) — no connection id — and the editor derives `connectionId` from the
      pipeline's own SOURCE node `use: connection/<id>` binding via `parseUseRef`
      (`pipeline-editor.component.ts:1271-1274`). The route must do the same: pipeline id → config/graph →
      source node's connection → profile `base_path`. **Never accept a client-supplied root.**
    - **Reuse the existing containment, don't write a second one.** `LocalConnectionWorkbench.jail`
      (`:102-107`) is `root.resolve(path).normalize().startsWith(root)` throwing
      `ConnectionWorkbench.PathEscape`, which the HTTP edge already maps to **403**
      (`ConnectionRoutes.exploreConnection`). Using the same primitive as the *picker* is what keeps the
      two surfaces from disagreeing about what is reachable — a picker that allows X and a runner that
      allows Y is how this kind of hole appears. ⚠ It is currently `private`; extracting it is the
      "extract, don't copy" call, and it is not the same shape as
      `ConfigSafetyValidator.checkPathValue`, which is **advisory** (returns `Finding`s for authoring)
      rather than enforcing. Do not mistake one for the other.
    - **Remote connections are out of scope for 5a/5c** — a non-local connector has no local path to
      stage from; those files reach the inbox via acquisition first. Refuse them explicitly rather than
      resolving to something surprising.
  - **Permission gate:** follow the `DecisionRoutes` precedent — `/simulate` is `canAuthorWorkbench`,
    `/apply` is `canOperateRuns`. A test run is a simulate, so **`canAuthorWorkbench`**.
  - **Response shape is already pinned by the mock** the UI renders against (per-relation counts + sample
    table). Match it, and pin it by spec the way Step 2 pinned the node-type list in
    `pipelines.handler.spec.ts` — a laxer mock is the exact failure mode that convention exists to kill.
    The TS contract is `PipelineRunResult` (`pipelines.service.ts:238-245`):
    `{seedNode, toNode, files[], relations[], output|null, warnings[]}`. ⚠ Note it **already has
    `warnings[]`**, which is where the `to=` honesty below belongs — no new field is needed. The editor
    consumes `relations[].{node, rel, rowCount}` and treats `rel === 'unmatched' && rowCount > 0` as a
    per-node ✕ (`pipeline-editor.component.ts:1286-1290`), so those three keys are load-bearing.
  - ⚠ **Decide what `to=` does while 5b is unbuilt — do not silently ignore it.** Without the cutoff the
    run covers the whole graph, so a response that echoes `toNode` while having run past it is a lie the
    canvas will render as ✓ on nodes that were never bounded. Either refuse a `to=` naming a
    non-terminal node, or run full and **say so in `warnings[]`** (the DRYRUN-2 precedent: the warning
    exists precisely for "this result is not what you would assume"). The second is preferred — it keeps
    5c shippable ahead of 5b and keeps the affordance honest.

**Sizing: multi-shift.** Three separable hard problems, not one task. 5a alone is a plausible shift.

##### 5c SHIPPED 2026-08-14 — as-built

`POST /pipelines/authored/{id}/run?to=` → `PipelineRoutes.testRun`, gated `canAuthorWorkbench`. The
jail landed as designed: `LocalConnectionWorkbench.jail(Path, String)` was **extracted to a public
static** (the private instance method now delegates), so the picker and the runner share one
containment. The root comes from `testRunRoot` — the pipeline's `source.connection` profile `base_path`,
or `dirs.poll` when it binds none — **derived from the pipeline, never from the request**. Non-`local`
connectors are **501**, not silently resolved.

⚠ **The jail's test was falsification-probed** (replace `jail(...)` with a plain `resolve()`): the escape
test goes red, and the failure output is the vulnerability in the clear — `../secret.csv` was read and
**its parsed contents returned in the response body**. Keep that test; it is the whole reason the route
is safe to expose.

**Two grains meet in the response, deliberately.** `relations[]` counts the seeded **sample**
(`TEST_RUN_SEED_ROWS` = 1000, because `PipelineDryRun` is in-memory and a picked file is unbounded),
while `output.rowCount` is the **full** parse. A warning names the difference whenever they can
disagree, so neither number is quietly mistaken for the other. Per-file quarantine outcomes also surface
as warnings — the operator is told the malformed file *would* be quarantined even though nothing moved.

⚠ **The offline mock was made LESS capable on purpose.** It truncates the graph via `subgraphTo`, so it
honoured `to=` while the server (no 5b) does not. A mock that over-promises is exactly the G2 failure
mode, so it now appends the same "ran the whole graph" warning. **When 5b lands, remove that warning in
both `pipelines.handler.ts` and `PipelineRoutes.testRun` together.**

##### 5b SHIPPED 2026-08-14 — as-built, and the premise that was wrong

`PipelineExecutor.dryRun(..., String stopAtNodeId)` + `PipelineDryRun.run(..., String stopAtNodeId)`, both
`null`-defaulting overloads; `PipelineRoutes.testRun` passes `to` straight through. **Step 5 is now closed.**

⚠ **The plan's stated regression risk did not exist, and grounding is what showed it.** The brief said the
walk "is shared with production `execute`", implying an overload was needed to keep `execute` byte-identical.
In fact `execute` and `dryRun` are **separate loop bodies** that merely share the private `topoOrder` helper.
The cutoff therefore threads through `dryRun` only and **`execute` was not touched at all** — a stronger
guarantee than the overload the plan asked for. Don't inherit the "shared walk" claim; it was never true.

**The cutoff is the ancestor closure of the target, NOT a prefix of the topological order** (`ancestorsOf`,
reverse BFS over non-`on_commit` edges). Topo order is arbitrary between sibling branches, so a truncated
prefix runs whichever sibling happens to sort first and reports counts for a branch the operator never asked
about — which the canvas then marks ✓. This is the same rule the offline mock's `subgraphTo` already used, so
mock and server now agree; the "ran the whole graph anyway" warning was removed from **both** together, as 5c
required.

⚠ **The falsification probe caught a bad test before it caught the bad code — the lesson worth carrying.**
Probing with the prefix implementation, the headline sibling test **passed**: Kahn's order for the fork
fixture is `acq, left, right, …`, so bounding at `left` yields the correct set *by coincidence*. Only
bounding at the sibling that sorts **later** discriminates. The test now stops at `right` and says why in a
comment. A green suite proved nothing here until the probe was run — exactly the trap 5a recorded.

Two smaller decisions: an unknown `to=` **throws → 400** rather than silently widening to the whole graph;
and a cutoff above every sink does **not** trip DRYRUN-2's "no sink received any rows" warning (that warning
requires a non-empty `sinks` list, so bounded-on-purpose stays distinct from would-write-nothing). The
`files` are still parsed in **full** under a cutoff — the parse is what seeds the walk, so `to=` makes the
answer narrower, never the work smaller.

---

## Links

- **Test run as-built (the current knowledge): [`../../okf/backend/engine/pipeline-test-run.md`](../../okf/backend/engine/pipeline-test-run.md)**
- Node-type / execution inventory + the three gates: [`../../BACKLOG.md`](../../BACKLOG.md) "Branch-aware executor"
- Pipeline vs Job (in-motion vs at-rest): [`../../okf/backend/pipeline-graph/pipeline-graph-design.md`](../../okf/backend/pipeline-graph/pipeline-graph-design.md) §3.8
- Editor shell as-built: [`../../okf/frontend/features/pipelines.md`](../../okf/frontend/features/pipelines.md)
- Onboarding authoring: [`../../okf/backend/control-plane/onboarding-authoring.md`](../../okf/backend/control-plane/onboarding-authoring.md)
