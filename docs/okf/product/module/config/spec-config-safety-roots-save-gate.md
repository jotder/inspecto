---
type: Concept
title: Config Safety Validator — Allowed roots, data refs and the one save gate
description: Part 2 of 4 of Config Safety Validator: A Space's allowed roots: declared + its OWN base (tier 3 2026-08-14, narrowed 2026-09-24); Data refs: a second verdict, deliberately not PathJail; One save gate for every door — SaveGate (2026-09-23)
tags: [config, safety, validation, path-jail, security]
timestamp: 2026-06-28T00:00:00Z
---

# Config Safety Validator — Allowed roots, data refs and the one save gate

Part 2 of 4 of [Config Safety Validator](spec-config-safety.md) · previous: [Path containment and the config write routes](spec-config-safety-path-containment.md) · next: [Job config decisions, the accepted-key census and the classification vocabulary](spec-config-safety-keys-vocabulary.md).

## A Space's allowed roots: declared + its OWN base (tier 3 2026-08-14, narrowed 2026-09-24)

`SafetyPolicy.defaultPolicy()` returns `SafetyPolicy.forSpace(CurrentSpace.id())`: the operator-declared
`-Dassist.safety.roots` plus **the bound Space's own base**, as registered (keyed by Space id) in
`com.gamma.config.safety.DiscoveredRoots`. Another Space's base is never an allowed root.

🔴 **Until 2026-09-24 this was the union of every hosted base** (`CROSS-SPACE-JAIL-1`). Probed and
confirmed: with two Spaces hosted and operator roots holding neither, `POST /spaces/acme/jobs` with an
absolute `backup_dir` inside `beta`'s `data/` returned **200** — every Space was an allowed root for every
other Space, at the 422 gate and in every run-time jail (`PathJail.allowedRoots()` reads the same
policy). Now it is **422**, and `ControlApiCrossSpaceJailTest` pins that plus the positive controls (a
Space's own base and an operator root stay allowed). Mutation-checked: restoring the union turns it,
`DiscoveredRootsTest.anotherSpacesBaseIsNotAnAllowedRoot` and `SpaceManagerTest` red.

- **Which Space is "current"** is the thread's space MDC — `ControlApi.bindSpace` for a `/spaces/{id}`
  request, `JobService` for a run, `CollectorService.underSpace` for a cycle. **An unbound thread is the
  `default` Space** and gets only a base registered under `default` — never a named Space's. That is the
  fail-closed direction: an un-prefixed request on a multi-Space host with no `default` Space dir now
  gets the operator roots only, where it used to get every Space's base.
- ⚠ **Boot must run AS the Space.** `SpaceBootstrap.load` is where a Space's `schema_file` refs meet the
  jail, and the boot thread is unbound — so `SpaceManager.loadAsSpace` binds the MDC to the Space being
  loaded. Without it a named Space's own pipelines are refused at boot (`SpaceManagerTest.aNamedSpacesOwnSchemaRefLoadsAtBootUnderNarrowedRoots`
  goes 1 → 0 loaded when the binding is removed).
- All 15 `ConfigSafetyValidator.check*` callers take `defaultPolicy()` on a bound thread, so none needed
  an explicit id; `forSpace(id)` is for a caller that already holds one.

The per-Space base (tier 3, 2026-08-14) removes the misconfiguration PATH-2 tier 3 named: the
write root was per-space and **dynamic** (`writeRoot()` derives from the current space) while the
policy list was global and **static** — create a space and forget to extend the property, and writes
into its `config/` passed the 403 gate while every schema/grammar ref inside it was refused at load.
Now both halves derive, and the declared list goes back to meaning only what it is for — destinations
**outside** the layout (`backup_dir: /mnt/backups`).

- **The seam points downward.** `inspecto` (SpaceManager) depends on `inspecto-config`, never the
  reverse — so the lifecycle **pushes** bases in and the policy reads. `discover()` registers each
  space **before** `SpaceBootstrap.load` (boot is exactly when refs meet the jail; registering after
  would refuse the configs the root exists to allow), and deregisters when the boot fails — a space
  that never joined leaves no root behind. The three runtime create paths share one `bootStarted`
  helper with the same order and the same failure cleanup.
- **A runtime-created space extends the roots immediately** — `defaultPolicy()` recomputes per call,
  so the next check sees it, no restart. **A deleted space's base leaves with it** (in `delete`'s
  per-space-registry teardown block), so the root set cannot only ever grow within a process lifetime.
- **The legacy flat / single-tenant space keeps property-only behaviour** — `SpaceManager.single`
  registers nothing, by construction (it never touches `DiscoveredRoots`). Likewise the engine CLI and
  job-runner entry points (`MainApp`, `CollectorProcessor`, `EnrichmentProcessor`, the job tasks) never
  run discovery, so for them the set is empty and the property remains their only source — unchanged.
  ⚠ **This is a decision, not a gap — and it was re-affirmed 2026-08-28** (PKG-6). A fresh bundle's
  `run.sh csv_example` died here (*"no allowed roots configured for 'schema_file'"*) because the
  one-shot CLI has no roots and the format-example pack uses `schema_file:` refs. The tempting fix —
  teach `CollectorProcessor` to derive roots the way the server does — would have reversed this
  sentence, so it was **refused**; `DiscoveredRootsTest`'s empty-means-empty invariant stands.
  **The launcher declares the root instead**, which is this section's own model: configuring roots is
  a deployment step, and `run.sh`/`run.bat` already resolve the pipeline path, so they pass
  `-Dassist.safety.roots=<that pipeline's space dir>` — the ONE space per invocation, never the whole
  `spaces/` tree, with an operator-supplied value still overriding it. See
  [Operations reference](../../layer/platform/admin-guide-operations-reference.md).
- ⚠ **The registry is process-global static.** A test that registers must `DiscoveredRoots.clear()` in
  a finally — a leaked base flips containment verdicts in unrelated tests. And an assertion about a
  discovered root must inspect `allowedRoots()` **content**, not run a jail check: surefire's
  reactor-wide roots already cover every `@TempDir`, so a verdict-based test passes vacuously.
- ⚠ `SafetyPolicy.withRoots(...)` (skill workspace, tests) deliberately **bypasses** the registry — an
  explicit policy is scoped, not widened.

⚠ **`PathJail` unified the CONFIG-path implementations, not every containment check in the codebase.**
A 2026-08-14 sweep found ~15 more outside that scope — the registry stores (three near-identical
copies; `ComponentStore` actually splits the logic in two), archive extraction, static file serving,
remote listings — disagreeing on symlinks, absolutisation and failure mode. They are **four different
problems**, and ⛔ putting them all on `PathJail` is the wrong fix: remote object keys are not local
`Path`s, and id-shaped names are not containment at all. The family split and the ranked fix order are
`BACKLOG.md` §6 **PATH-2** — which also records that grounding the sweep **refuted three of its own
claims**, so trust that row's 2026-08-14 close-out over the original framing.

## Data refs: a second verdict, deliberately not `PathJail`

`com.gamma.config.safety.DataRef` (2026-08-14) is the one answer for a `physicalRef`-shaped value — a
store ref resolved under a **space's data root**. It sits beside `PathJail` and is emphatically *not* a
caller of it.

⛔ **A data ref must never be routed through `PathJail`.** `PathJail` resolves a relative value against
the **working directory** — load-bearing for config refs, as above — while a data ref is meaningless
except relative to the data root. Forcing one onto the other would silently resolve `orders` against the
server CWD. Two roots, two verdicts.

The shape rule is a character class, and that is what makes these refs **structurally** safe rather than
merely filtered: `[A-Za-z0-9][A-Za-z0-9._/-]*` admits no `\` (no UNC, no Windows separator) and no `:`
(no drive prefix), and the alphanumeric first character rejects a leading `/`, `-` or `.`. Traversal is
excluded by a separate `".."` substring test because `.` must stay legal *inside* a segment (a store
named `orders.v2`). `requireShape` is for the branch that resolves elsewhere — an Exchange
`shared/<owner>/<item>` snapshot; `requireUnder` adds containment. Violations throw
`IllegalArgumentException` → **422**: an unusable ref is a bad request about a dataset, not a containment
incident, so it deliberately does *not* throw `PathJail.Escape`.

⚠ **`requireUnder`'s containment branch is unreachable while the shape rule holds — and is kept anyway.**
It exists so the two rules cannot drift apart, which is the failure this class was created to end:
`DatasetRelation` and `ExpectationEvaluator` each carried their **own copy** of that pattern (the
latter's Javadoc admitted it was the "same shape as" the former), and the copies *had* drifted — both
checked shape, only one re-checked containment after resolving. Nothing was reachable through the gap,
but a boundary spelled twice is a boundary that drifts.

⚠ **Test the reason, not the throw.** Both rules raise `IllegalArgumentException`, so a type-only
assertion cannot tell "refused by shape" from "refused by containment" — and since shape refuses first
in every case, such a test reports the containment half as covered when it never ran. `DataRefTest`
asserts each exclusion's message individually.

**Symlinks under data refs: closed 2026-08-14 (tier 4, data-ref half).** `DataRef.requireUnder`'s
containment verdict is now `PathJail.contains` — resolution stays DataRef's (a ref resolves against the
data root, never the CWD), the verdict is the jail's single definition, symlink re-check included. Third
use of S2's trick: **unify the verdict, never the resolution.** The shape rule cannot see a link *inside*
the data root pointing out of it (`innocent/stolen` is perfectly ref-shaped); only the real-path re-check
can, and `DataRefTest` pins both directions (an escaping link is refused, an internal alias is not) via
`TestLinks`' junction fallback so the tests actually run on Windows. **Pinned in the same pass:
`PathJail.contains` returning `true` when the filesystem will not answer is DELIBERATE**, not an
oversight — it skips only the symlink *re*-check (structural `startsWith` has already passed), and the
null means perms or a race, which says nothing about which way to fail; refusing would turn transient IO
noise into a refusal of every legitimate config. ⚠ Tier 4's *other* sites (static serving, archive
extraction targets, the store `fileFor` helpers) still do not re-check symlinks — those are untidy, not
decided; nothing schedules them.

⚠ **The store glob is interpolated into SQL, never bound** — DuckDB's table functions take a literal.
`SqlViews.reader`/`pathList` double `'` → `''` (so a store under `O'Brien` renders a valid literal
rather than a broken one); `SqlViewsTest` pins it and ⛔ it must not be "simplified" away. The caller-supplied
store name on `DbBrowserRoutes` is sound on both axes — normalize + `startsWith` against an already
normalised `dataRoot`, then the escaped literal — and is now pinned at **403** (not merely "some 4xx":
a 404 would mean the jail never ran and the name fell through to the existence check).

⛔ **A containment check must never report success when it refuses.** Two did, and both were fixed
2026-08-14: `MetadataValidateTask.missingPhysical` skipped an escaping `physicalRef` as "not ours to
verify", so a space carrying one **audited clean** — an escaping ref is a *worse* finding than a missing
store, and it now emits one; and `SpaceManager.delete` logged *"Deleted + purged"* whether it deleted
the tree, found nothing on disk, or refused for escaping the spaces root — the three outcomes are now
distinct and the refusal throws. ⚠ Neither was a live escape: `spacesRoot` is absolute and normalized at
`discover()` and `SpaceId` forbids separators, so that guard held by construction. These were *reporting*
defects, which is exactly why they survived — a guard whose failure reads as success is not a guard.

⚠ **A Job's findings do not travel in its `JobResult`** — that record carries status + one message +
duration, and it is what `JobRunLedger`/`DbJobRunStore` persist. Detail goes to `JobContext.log()`
(persisted per-run) and to the Signal ledger (`metadata_validate` puts the whole list in
`maintenance.metadata.findings`), so **RCA is served**; ⛔ do not "fix" this by widening `JobResult`
(72 construction sites, and a findings list does not belong in a ledger column). The consequence is for
*tests*: driving the ctx-less `Job.run()` overload discards every finding and can only count them,
which cannot tell "reported the right thing" from "reported the wrong thing the right number of
times". Drive `run(ctx)` with `CapturingJobContext` (`com.gamma.job`, test sources — **one** double for
every Job test; the consignment copy was collapsed onto it 2026-08-14).

⛔ **Do NOT record audit findings as a Run Artifact.** Considered and rejected 2026-08-14 on visibility
+ stability: the Run Log is *already* queryable per run (`GET /jobs/{name}/runs/{runId}/log` and
`/logs`, the UI's live-tail panel) and the findings also ride the Signal ledger, so an artifact adds no
reachability — it adds a **second copy of the same information**, which is the exact failure mode that
produced the disagreeing-guards bug above. `ArtifactRecorder` is the right home for a *produced
thing* (`BackupTask`'s zip, `SqlTemplateJob`'s Parquet, `storage_report`'s per-axis sample), not for a
report the Run Log already carries.

**Why these live here and not in a `ConfigSpec`.** `FieldSpec`/`ConfigSpec` are flat-dotted-path only —
`FieldType.MAP`/`LIST` assert the container type and never walk into entries, and there is no
map-of-objects/list-of-objects primitive. Every repeated sub-shape in the codebase (`sinks[]`, and now
`references.<name>`) is therefore validated by a hand-written per-entry method here; `checkSink` is the
precedent `checkReference` follows. A future map-of-objects notion in the spec layer would subsume both.

Only `pipeline` and `enrichment` config types have a write surface to gate. This is tied to the write-gate:
when `-Dassist.write.root` is set, writes are jailed to that root and validated here (see
[auth & security](../access/admin-guide-auth-security.md)).

## One save gate for every door — `SaveGate` (2026-09-23)

`SaveGate.check` (`inspecto/src/main/java/com/gamma/control/SaveGate.java`) is **the** list of content
checks a config save runs, and every Pipeline-config door calls it: `POST /config/write`,
`POST /config/patch`, `PUT /pipelines/{name}/graph`, `POST /pipelines/import`, and `POST /validate`
(both branches — it reports what a save would refuse; it never refuses). A caller refuses on any ERROR
(`SaveGate.refuses`). The list, in order: spec validate · `ConfigSafetyValidator` (a job judged from the
Space config root, everything else from its own directory) · schema-file resolution (arming split — see
below) · the five arming checks · unknown collector Connection · the `webhook:` block · `AcceptedConfigKeys`
census · route-predicate columns · summarize measure types · step configs (lookup, filter, dedup, profile — G4)
· a chain with no output store (below).

🔴 **A delimited / fixed-width pipeline whose schema selects a column by NAME is REFUSED**
(`ERR_SELECTOR_NOT_INDEX`, `SaveGate.selectorFindings`, LA-ONBOARD-FIXES-1 F-4), active or not: the load
needs zero-based column indexes there and would refuse it, so the save reports the load's own message
instead of accepting what registration then refuses. Judged by a pure `PipelineConfig.fromMap` over the
draft from its own directory; every other parse refusal is left to the check that owns it.

🔴 **An ACTIVE pipeline whose `schema_file` resolves nowhere is REFUSED** (`ERR_SCHEMA_FILE_UNRESOLVED`,
`ConfigRoutes.schemaArmingFindings`, 2026-09-25); an inactive draft keeps the WARNING
(`WARN_SCHEMA_FILE_UNRESOLVED`), so a scaffold may name its `<id>_schema.toon` before the Parse Apply
writes it. It used to be a WARNING at every severity, reasoning the file "may be created after the save,
or belong to another host" — but an active config in this Space's tree is loaded by THIS server, and a
missing schema turns it into a "Does not load" row with no run. Driven 2026-09-25: a pipeline whose Parse
Apply had written its schema under the wrong name (`SCHEMA-FILE-NAME-1`) saved, validated and **activated**
clean, then never ran. Activate is `PUT …/graph` with `active: true`, so it is refused with the reason;
`/validate` reports the ERROR. The file-for-another-host case is exactly the inactive draft (and bundle
import, which always lands inactive). Pinned by `ControlApiSchemaFileRefTest`. ⚠ Only the save doors
changed: `RunRoutes`/`DataSourceRoutes` already used ERROR, the template copy keeps WARNING (templates
never run).

🔴 **Why one function.** Before G3 (`PROCESSOR-RELEASE-READINESS-1`) each route carried its own hand-kept
copy of that list, and the copies had drifted — reproduced over real HTTP before the fix:

| door | missing before G3 |
|---|---|
| `PUT …/graph` (its comment claimed "the same gate") | unknown collector Connection, key census |
| bundle import | key census, both TypeFlow checks |
| `/validate` draft | Connection, key census, both TypeFlow checks; safety was opt-in (`safety:true`) |
| every door, `/config/write` included | any `webhook:` check at all |

⚠ The row's other half — "config write lacks `routeColumnFindings` + `summarizeMeasureFindings`" — was
**already false**: `/config/write` ran both in a second gate after deriving the target. The shared gate
retired that second gate by running once, BEFORE any path is resolved (422 still precedes the subdir-jail
403, `WriteGateOrderTest`), judged from the *prospective* directory — the write root, or the requested
`subdir` when it stays inside it.

**The `webhook:` block** is judged at save as `WebhookSink.plan` judges it at run time, regardless of
`active`: it must parse (`ERR_WEBHOOK_INVALID` — an authored `url:`/`token:`, an unknown key, a
`batch_size` out of bounds; the parser's own message), its `connection` must name a Connection this Space
holds (`ERR_WEBHOOK_CONNECTION_UNKNOWN`), and that Connection must be `https`
(`ERR_WEBHOOK_CONNECTION_NOT_HTTPS`). Host, tunnel and proxy stay the Connection's own validation.

**The `excel:` block** (`sink.excel`, 2026-10-06) is judged the same way, regardless of `active`: it must parse
(`ERR_EXCEL_INVALID` — no `path`, a path that is absolute or carries `..`, not `.xlsx`, a sheet name Excel
refuses or a duplicate, `max_rows` out of bounds, an unknown key; the parser's own message), and every
sheet's `sql` must pass `SqlGuard` (`ERR_EXCEL_INVALID` on `excel.sheets[i].sql`). The run re-checks the
path with `PathJail` against the data root, symlinks included.

⛔ **The one deliberate difference is bundle import, and it is a recorded decision, not drift**
(`SaveGate.Referents.MAY_ARRIVE_LATER`): a pipeline bundle never carries its Connections (secrets never
travel) and always lands inactive, so a **missing** Connection — collector or webhook — is a
`WARN_UNRESOLVED_CONNECTION`, never a refusal. A Connection that exists but is the wrong kind is refused on
import too. `/validate` drops its `safety` flag: `safetyChecked` is always `true`.

Pinned by `ControlApiSaveGateParityTest`: every fault × every door, one verdict (35 cases + a clean control + a valid `excel:` block
+ the graph editor's own `sink.webhook` node shape). A new check added to `SaveGate` reaches all five doors
at once; a door that stops calling it goes red there.

### The four Pipeline edits joined the gate — and a guard enumerates every door (2026-09-26)

*`ASSURE-MAKER-CHECKER-1` S0.* `POST /pipelines/{name}/label`, `/settings`, `/save-as-template`
(`PipelineSettingsRoutes.java`) and `/rename` (`PipelineRenameRoutes.java`) used to hand-roll spec +
`ConfigSafetyValidator` only. They now run `SaveGate.check`. Label, settings and rename edit a config already
on disk, so they judge it **before and after** and refuse only the ERRORs the edit *introduces*
(`SaveGate.introduced`) — the posture they had, over the whole list now. Save-as-template runs the full gate
on the new config (its schema-file check is the gate's inactive-draft WARNING, as before).

`ConfigWriteFunnelTest` enumerates every mutating registration site in every reactor module (the scan
`CapabilityManifestTest` trusts), follows each handler through the methods it calls **in its own source
file**, and fails when a route that encodes TOON (`ConfigCodec.toToon` / `JToon.encode`) never reaches
`SaveGate`. The routes that write a kind `SaveGate` has no arm for are on its `NO_SAVE_GATE` table with a
reason: Tags and Case Rules (inspecto-ops), Connections (secret-aware CRUD), the five `/jobs` writers
(`JobRoutes` runs the job spec + `checkJob` itself — the two checks `SaveGate`'s job arm runs, not the whole
list), and `/bundle/import` (components, jobs, enrichments; Pipelines import through `/pipelines/import`).
Proved by mutation: stripping `SaveGate` from `/settings` turns the test red, naming the route.
⚠ The closure stops at the file boundary — a route whose write lives in another class (the settings
documents, whose records write themselves) is invisible to it.

### Step config checks (G4)

*Added 2026-09-23 (`PROCESSOR-RELEASE-READINESS-1` G4).* `ConfigRoutes.stepConfigFindings`, run by
`SaveGate` after the dedup-window check, refuses at save what `RowShaper` used to refuse only on the first
run. It reads the `steps:` chain and the legacy `processing.dedup` / `processing.profile` blocks:

| Step | refused at save |
|---|---|
| `lookup` | no `column`; no `mappings`; a mapping that is not `key=value` (named); a `column` the schema does not declare |
| `filter` | a blank `where`; a `where` that does not bind against the known columns (or is not a safe read-only expression) |
| `dedup` | an empty or absent `keys` list; a key the schema does not declare |
| `profile` | a named column the schema does not declare (an empty block still means "every column") |

Codes `ERR_STEP_CONFIG_INVALID` (active) / `WARN_STEP_CONFIG_INVALID` (inactive draft), the same
severity split the arming checks use. ⚠ **Columns are judged only while the row is still the schema's.**
From the first `sql`, `join`, `summarize`, `profile` or `route` step on, the inbound columns are unknown
here, and unknown is not wrong. A `lookup` with a `target` adds that column. An unreadable schema says
nothing, as with the TypeFlow checks.

**Branch sub-chains are walked** (as-built 2026-09-23): a `route:` branch's own `steps[]` — under a
`route` step in the chain (`steps[i].route.branches[b].steps[j].<kind>`) or under the legacy top-level
`route:` block (`route.branches[b].steps[j].<kind>`) — gets the same checks. Each branch starts from a
copy of the columns known at the route point, and the same reshaping rule applies inside it. **The filter
`where` column check reuses the route-predicate bind** of `routeColumnFindings`: `SqlGuard.check` on the
assembled `SELECT * FROM "input" WHERE …` probe, then `TypeFlow.describe` against the known columns, so
DuckDB's binder decides (no second predicate parser). The known columns are the raw fields **plus the
schema's `mapping.fields[]` names** (steps see the mapped row, so a `custom`-derived column like the shipped
filter_step's `GROSS` is real). 🔴 Only a genuine `Referenced column … not found` binder error is refused;
every other bind failure (unknown function, type mismatch, the probe's own mechanics) **fails open** — the
first cut refused the shipped `filter_step` Pipeline because it knew only the raw fields, and DuckDB's JDBC
wraps every bind error as "Attempting to execute an unsuccessful or closed pending query result".
**The legacy filter is checked too** (as-built 2026-09-24): it is `processing.csv_settings.where` (the key
`PipelineConfig.resolveSteps` projects as the first `filter` step — there is no `processing.filter` key),
bound first in the legacy order against raw + mapped columns, anchored at `processing.csv_settings.where`
with the `*_STEP_CONFIG_INVALID` codes; a blank value means "no filter" and is not refused. **A `route`
step's own branch predicates** (`steps[i].route.branches[<key>].where`) are bound against the columns
known at that point in the chain (skipped once they are unknown, e.g. after an `sql` step) by the same
helper `routeColumnFindings` uses (`branchPredicateFindings`), so they carry the
`ERR_/WARN_ROUTE_PREDICATE_COLUMN` codes, the same fail-open rule and the same SqlGuard-on-assembled-probe
rule; a blank predicate stays `routeArmingFindings`' refusal and is not double-reported. Pinned by
`StepConfigSaveFindingsTest`.

### A chain with no output store (2026-11-15, `TRANSFORMER-TWO-NODES-1`)

`ConfigRoutes.chainOutputStoreFindings`, run by `SaveGate` after the dedup-window check, reports every Step of
a Stage-2 chain on a Pipeline that sets no top-level `output_store:`. **The exact condition:** the Pipeline has
a non-empty `steps:` list, or a legacy `processing.dedup` / `summarize` / `profile` / `join` block, and
`output_store:` is absent or blank. Such a chain never runs: the ingest lane executes none of it, and the only
lane that does is the at-rest one (`PipelineLift.stageTwo`, from a `type: pipeline` Job), which writes that
store and refuses to lift without it. `PipelineConfig.requireRunnable` refuses to arm the same shape. One
finding per Step, anchored at `steps[i].<kind>` or `processing.<kind>`, naming the Step and the fix (set
`output_store:` and run the chain with a `type: pipeline` Job, or remove the Step). Arming split:
`ERR_CHAIN_WITHOUT_OUTPUT_STORE` on an active Pipeline (the save is refused, 422),
`WARN_CHAIN_WITHOUT_OUTPUT_STORE` on an inactive draft (it saves; activation is refused).

- **Every Step of the chain is reported, a Step that feeds a later one included.** `output_store:` belongs to
  the Pipeline, not to a Step: with it, every Step runs, and its output feeds the next; without it, none
  does. There is no per-Step "intermediate" case to exempt.
- **Never judged:** the projection slot (`processing.map`, the "Shape records" / "Shape &lt;table&gt;" node,
  Stage 1), a `route:` branch's own `steps[]` (ingest lane), the legacy row filter
  (`csv_settings.where`, Stage 1), and the `webhook:` / `excel:` blocks (the `ConfigSpecs` rule in the next
  point covers them).
- ⚠ **Not new for an active Pipeline.** `ConfigSpecs`' cross-field rule `stage-two-blocks-require-output-store`
  already refused that save (field `output_store`, no code, Step not named; it also covers `webhook:` and
  `excel:`), so an active save now carries both findings. What is new: the Step is named, the code is
  stable, and an inactive draft is told at the save rather than at activation.
- **A chain WITH a store but no Job that runs it is warned** (2026-11-15, `PIPELINE-CHAIN-NO-JOB-1`):
  `ConfigRoutes.chainWithoutJobFindings`, run by `SaveGate`, reports one `WARN_CHAIN_WITHOUT_PIPELINE_JOB`
  at `output_store` when the Pipeline has the same chain, a non-blank `output_store:`, and **no enabled
  `type: pipeline` Job whose `pipeline_config:` names this Pipeline's file**. The message names the fix:
  add a `type: pipeline` Job that runs `<pipeline>` (`pipeline_config:` its `*_pipeline.toon`,
  `on_pipeline: <pipeline>`), or enable the one you have. Decisions:
  - **Always a WARNING, never a refusal, active or not.** The Job is a separate config; refusing would make
    the order of authoring matter. A draft gets it too: that is when the author needs to hear a Job is owed.
  - **A Job is matched by the identity of the file it names,** not the file name: its `pipeline_config:`
    resolves exactly as `PipelineJobRunner` resolves it: from the run's own base (`SpaceConfigRoot.jobPathBase`,
    the config read root) through `PathJail.requireJobPathUnderAny` over `PathJail.allowedRoots()`, which also
    refuses a path outside the jail and a `*.secrets` directory. The file's registered id (`id:`, else `name:`
    lower-cased) must be this Pipeline's. One helper holds this rule, `PipelineConfigIds.registeredIdOf`
    in the engine, so the save gate and the run-time audit below cannot drift. The shipped samples name
    `orders_pipeline.toon` for `STEP_DEDUP`, so a file-name rule would warn on every one of them. A Job's
    `pipeline:` names an authored Pipeline and never lifts this chain, so it does not count; neither does a
    disabled Job, nor one whose path the runner would refuse (that file is never read) or whose file is missing.
  - ⚠ **Known limits — two cases the warning misses.** (1) A Job held by Space evolution (SE-1b) still counts
    as enabled, though a held Job does not run. (2) An enabled Job with no `cron:`, `on_pipeline:` or
    `on_signal:` silences the warning, though nothing ever starts it (only a manual run does).
  - **Doors:** every `SaveGate.check` that knows the Space's Jobs (`Referents.MUST_EXIST` with a live
    `ApiContext`): `/config/write`, the graph save, settings, rename, history restore, the onboarding and
    Dataset-feed writes, and `/validate` (the Pipeline's validate route, so the editor shows it before a
    save). **Not** a pipeline bundle import (`MAY_ARRIVE_LATER`: its Jobs may arrive after it, like its
    Connections) and not a Space Template seed (`TemplateSeedGate`, no hosted Space yet).
  - **Saving or deleting a Job does not re-judge Pipelines:** the warning lives on the Pipeline's save. The
    run-time side already exists: `JobService.auditOrphanOutputStores` (default on, run on a Job upsert or
    delete and on a registry rebuild) signals `maintenance.scheduler.findings` for an active store with no
    enabled `pipeline_config:` Job.
  - That run-time audit (`SchedulerAuditTask.orphanOutputStoreFindings`, shared with the on-demand
    `scheduler_audit` task) matches a Job by the **same identity rule** (2026-11-15,
    `SCHEDULER-AUDIT-PIPELINE-NAME-1`; it took the `pipeline_config:` basename less `_pipeline.toon` until
    then, and so reported every `07-steps` sample as an orphan). It resolves from the Space's own base:
    `JobService.auditOrphanOutputStores` binds the service's Space for the scan, as a Job run does, because a
    registry rebuild calls it from a thread bound to no Space. A Job whose file is missing, unreadable or
    outside the jail runs nothing, so it covers nothing. Pinned by `JobServiceOrphanAuditTest` and
    `MaintenanceLibraryTest.schedulerAuditFlagsAnOutputStoreNoPipelineConfigJobRuns`.
  - ⚠ The `webhook:` / `excel:` blocks also run only at rest but are not judged by this warning (only the
    chain is, as for the store-less finding above).
  - The default Space's `lookup_step` Pipeline (`spaces/default/config/lookup_step/`) had a chain and a
    store and no Job, so it got this warning when saved: a true finding. Since 2026-11-15
    (`DEFAULT-SPACE-LOOKUP-STEP-NO-JOB-1`) it has `jobs/lookup_step_rollup_job.toon`, shaped like its five
    siblings', so every chained Pipeline of the default Space has the Job that runs it. Pinned by
    `ControlApiChainWithoutJobTest` (the default Space's Jobs hosted, each chained Pipeline at `/validate`)
    and `DemoCorpusIngestTest` (the committed Job runs the lookup over the landed sample).

Pinned by `ControlApiChainOutputStoreArmingTest` (graph save active / inactive, the activation patch, a
chain with a store, the slot alone, a legacy block at `/validate`) and `ControlApiChainWithoutJobTest`
(no Job warns; a disabled Job still warns; an enabled Job naming the file silences it although the file
name differs from the Pipeline's; an enabled Job naming ANOTHER Pipeline's file leaves the warning; a
relative `pipeline_config:` resolves from the config read root; a Job whose path lies outside the jail
leaves the warning; a chain-less Pipeline and a store-less chain get no such warning; `/validate` on an
inactive legacy block warns; every shipped `pipeline_config:` Job resolves to its Pipeline by identity).

### Collector checks — throttle, breaker, archive (2026-09-24)

*Added 2026-09-24 (`PROCESSOR-RELEASE-READINESS-1`, the "fails clearly at save" point for
`control.throttle`, `control.circuitbreaker` and `sink.archive`).* Three faults used to save clean and do
nothing, or fail only at load:

| Fault | Before | Now |
|---|---|---|
| `collector.fetch.rate_limit: fast` | saved; `parseRate` threw at LOAD | 422 at save — the spec carries `parseRate`'s grammar (number, optional `KB`/`MB`/`GB`/`B`, optional `/s`/`ps`/`/sec`) |
| `collector.post_action.on_success: MOEV` (or an unknown `on_unsupported`) | saved; the run logged a warning and silently RETAINED | 422 at save — both are spec ENUMs (any case) |
| `on_success: MOVE` with a blank `archive_path` | saved; the connector moved the file onto its own path | `ERR_COLLECTOR_CONFIG_INVALID` (active) / `WARN_COLLECTOR_CONFIG_INVALID` (inactive draft) |
| `fetch`, `retry`, `circuit_breaker` or a non-RETAIN `post_action` on a **local** inbox | saved; `CollectorProcessor.acquire` returns at once for `local`, so none of them ever engaged | `WARN_COLLECTOR_KEY_INERT`, naming every inert key — a warning, never a refusal (harmless, and a Connection may be bound later) |

`ConfigRoutes.collectorFindings`, run by `SaveGate`, so every save door gets it. A Collector bound to a
`connection` is judged remote even with no `connector` — the form derives it from the Connection. Pinned
by `CollectorSaveFindingsTest` and, for the spec half, `CollectorResilienceSpecParityTest` (spec ⇔ parser on
the rate grammar; the unknown post-action is one-way — the engine would retain, the spec refuses).
