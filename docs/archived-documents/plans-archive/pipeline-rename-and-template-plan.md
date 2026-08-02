# Pipeline rename + Save-as-template — plan

**Status: ALL PHASES SHIPPED 2026-08-02 (T1 + T2 + T4, then T3). Full reactor: 2205 tests, 0 failures.**
As-built lives in
[`okf/backend/control-plane/pipeline-identity.md`](../../okf/backend/control-plane/pipeline-identity.md);
open residuals (UI wiring for the full `rename` route; automated `rename.journal` resumability) moved to
`docs/BACKLOG.md` §4. This plan is kept for provenance — read the OKF doc for current facts.

> ### As-built deltas — read before continuing (§2.2 and §3.4 were wrong)
>
> 1. **A template IS registered.** The plan's "never register it, so it is structurally unreachable" does not
>    work: `ConfigRegistry` — the read surface behind `GET /pipelines`, the catalog and the editor — is
>    rebuilt from the *same* list as the run registry (`CollectorService.java:485`), so excluding templates
>    would have made them invisible and unpromotable. Instead templates register normally and runnability is
>    gated at the three places that actually start work: `PipelineConfigParser` (refuses `template: true` +
>    `active: true`, which covers every authoring path and boot at once), `CollectorService.refuseIfTemplate`
>    (on `runPipeline` / `runPipelineOffThread` / `triggerRunAsync` — checked on each because the async paths
>    hand off to a worker thread, where a deeper throw would become a failed run record instead of a visible
>    refusal), and `PipelineScheduler.selectDue` (belt-and-braces skip).
> 2. **`template` is NOT a `FieldSpec`.** It is a lifecycle flag like `active`, which is also absent from
>    `ConfigSpecs.pipeline()` — declaring it would expose it as a schema-form field and emit `template: false`
>    into every config the form saves. `ConfigCodec.toToon` writes the raw map verbatim, so the key persists
>    regardless. The contradiction is enforced by a new **`CrossFieldRule` `template-cannot-be-active`**
>    (ERROR), so authoring gets a clean 422 instead of a config that silently vanishes from the read surface
>    at load time.
> 3. **T1 became a real route, not just an id stamp.** A bare `stampId` would have been unobservable: nothing
>    lets an operator edit the display name (the graph editor's URL name always wins), and
>    `PipelineLift` projects `identity().pipelineName()` as the graph's `name`, so a display name was not even
>    visible. T1 therefore ships as `POST /pipelines/{name}/label` (stamp `id`, then set `name`) plus a
>    `displayName` key in the list summary, emitted only when it differs from the identity.
> 4. **The relabel safety gate is a findings *diff*.** Re-running `ConfigSafetyValidator` over an existing
>    config fails it for `dirs.*` that were already on disk and never subject to the write-time policy — which
>    would make any deployment whose data lives outside the default allowed roots unrenameable. `relabel`
>    blocks only on ERROR findings the rewrite *introduces*. Found by the test, not by review.
> 5. **`CapabilityManifest` must list every gated route.** `CapabilityManifestTest` cross-checks the manifest
>    against the `withCapability` registration sites and fails the build on drift — add new routes there.
> 6. **T3's `isRunning` gate needed a new public `CollectorService` accessor.** `PipelineRunGuard.isRunning`
>    is package-private and documented "diagnostics / tests — never a gate" (its exclusion concern is
>    serializing *runs*, not vetoing an administrative migration). A rename migrates ledger/audit state a
>    live run would be reading and writing under the OLD id, and `active: false` doesn't rule that out (an
>    inactive pipeline is still manually triggerable) — so `CollectorService.isRunning(String)` now exists as
>    a narrow, documented exception for exactly this one caller. TOCTOU-limited by nature.
> 7. **`AcquisitionLedger.renameSource` needed implementing on `InMemoryAcquisitionLedger` too**, not just
>    `DbAcquisitionLedger`. The interface's default is a no-op (correct for a minimal implementer), but
>    `-Dacquire.ledger.backend` defaults to **memory** — in-memory IS the production default, not a test
>    double. Relying on the default would have silently skipped the ledger migration for most deployments.
> 8. **The dataset dependent-rewrite widened past `DataSourceBundleResolver.datasetReadsStore`**, which only
>    checks `physicalRef`. A real `kind: virtual` dataset config uses `sourceName` to name its store
>    directly (confirmed against `spaces/default/config/registry/datasets/premium_cdr_view.toon`) — an
>    unrewritten `sourceName` after a rename would leave the dataset silently reading nothing.
> 9. **`relabel`'s findings-diff bug recurs identically in `rename`'s config-write step**, for the same
>    reason: `dirs.*` outside the default allowed roots were never subject to the write-time safety policy,
>    so a bare re-validate would make any such deployment unrenameable. Same fix, same place it was found —
>    a test, not review.
> 10. **`relocateDirs: true` is refused (422), not silently ignored.** Accepting the flag and doing nothing
>    would make a caller believe their data tree moved when it didn't — a real deployment could disable
>    monitoring, then discover the alarms were watching an old path all along.
**Asks:** (1) rename a pipeline, (2) "Save as template" — a non-runnable sibling that carries the critical
config for standing up a *similar* stream, and that **cannot touch the original's data even if run by mistake**.

**Operator decisions taken (2026-08-02):**

| # | Decision | Chosen |
|---|---|---|
| D1 | Rename semantics | **Full identity migration** — the id moves, and persistent state moves with it |
| D2 | Template home + how "not runnable" is enforced | **In-space, hard-gated by a new `template: true` spec key** |
| D3 | What the copy carries | **Tokenize every environment binding**; carry the shape verbatim |

---

## 1. The identity model this builds on

`ConfigSpecs.pipeline()` already splits display name from identity
([`ConfigSpecs.java:56-70`](../../inspecto-config/src/main/java/com/gamma/config/spec/ConfigSpecs.java)):

- `name` — display. Required.
- `id` — optional, immutable, `[a-z0-9][a-z0-9_]*`. **Absent in every config that exists today.**
- When `id` is absent, identity is derived: `name.toLowerCase().replace(' ', '_')`
  ([`PipelineConfigParser.java:77-79`](../../inspecto-etl/src/main/java/com/gamma/etl/PipelineConfigParser.java)).

That derived identity — `PipelineConfig.identity().pipelineName()`, ~140 call sites — is embedded in
persistent state, which is why D1 is a migration and not an edit. **Full inventory of what must move:**

| # | Artifact | Key / filename format | Read path | Migration action |
|---|---|---|---|---|
| S1 | Config file | `<id>_pipeline.toon` | `SpaceBootstrap` scan | rename file, set `id`/`name` |
| S2 | Commit log | `<id>_commits.log` in the `status_dir` parent | `FileStatusStore.committedBatches` → `CommitLog.committedBatchIds` | **rename file.** Its `pipeline` CSV column is descriptive only, never filtered — leave the old text |
| S3 | Audit CSVs | `<id>_status_<ts>.csv`, `<id>_batches_<ts>.csv`, `<id>_lineage_<ts>.csv` | ⚠ **globbed by id prefix** — `FileStatusStore.readRuns` builds `pipelineName + infix + "*.csv"` ([`FileStatusStore.java:90-107`](../../inspecto/src/main/java/com/gamma/service/FileStatusStore.java)) | **rename every matching file** or all run history silently vanishes |
| S4 | DuckDB status mirror | `pipeline` column | `DbStatusStore.sync()`, DELETE-then-INSERT per pipeline ([`DbStatusStore.java:196`](../../inspecto/src/main/java/com/gamma/service/DbStatusStore.java)) | `UPDATE … SET pipeline=? WHERE pipeline=?`; old rows are otherwise orphaned, never cleaned |
| S5 | Acquisition ledger | `inspecto_acquisition_ledger`, PK `(source_id, relative_path)`; `source_id` = `collector.id`, **defaults to the pipeline id** ([`PipelineConfigParser.java:394,398`](../../inspecto-etl/src/main/java/com/gamma/etl/PipelineConfigParser.java)) | `CollectorProcessor.ledgerFilter` | ⚠ **no rename API exists.** Add one (§3.2). Miss it and every already-ingested file looks new and gets reprocessed |
| S6 | Ledger watermark | `inspecto_acquisition_db_watermark`, PK `(source_key)` | `DbAcquisitionLedger.dbWatermark` | same rename call |
| S7 | Catalog Stream | `stream:<id>` via `IdScheme.stream` | `MetadataGraphBuilder` | **nothing to move** — the graph is rebuilt from config, never persisted; just `invalidate()` |
| S8 | Marker files | `<markers>/<relative-path>.processed` | `MarkerManager.getMarkerPath` | **not id-keyed at all.** No action |
| S9 | In-memory per-id state | — | `PipelineRunGuard.forget(id)`, `PipelineScheduler.forget(id)` → `IntakeGovernor.shared().forget(id)` | already invoked by `CollectorService.unregisterPipeline(Path)` |
| S10 | Event history | events tagged `.pipeline(id)` at emit time | — | **deliberately NOT rewritten** — history records what was true then |

**Dependent config that names the old id** (rewrite, since under D1 identity moves):

| Key | File kind | Reader |
|---|---|---|
| `triggers.on_pipeline` | `*_enrich.toon` | `EnrichmentConfig` |
| `on_pipeline` (top level) | `*_job.toon` | `JobConfig` |
| `target` (when `targetType: pipeline`) | expectation | `Expectation` |
| `target` (when `targetType: pipeline`) | decision / alert rule | `DecisionRules` |
| `sourceName`, first segment of `physicalRef` | dataset | `DataSourceBundleResolver.datasetReadsStore` |
| `stream` (when explicitly set to the old id) | pipeline | `PipelineConfigParser` |

Widgets/dashboards reference *component* names, not the pipeline id — insulated once datasets are fixed.
`flow:` in `*_job.toon` names an **authored graph** under `<writeRoot>/flows/`, a separate identity space —
out of scope.

### 1.1 Stated assumption — data directories are NOT relocated

`dirs.*` values embed the pipeline name by convention (`pipelineScaffold` writes `data/inbox/<name>`,
`data/<name>/database`, …), but they are operator-chosen paths, not identity. **Default: the migration
rewrites config identity, audit files, ledger keys and dependent refs, and leaves `dirs.*` pointing where
they already point.** Relocating a Stage-1 output tree is a bulk data move with real blast radius and is
therefore a separate opt-in (`relocateDirs: true`, §3.3) — not the default. A renamed pipeline that keeps
writing to `data/<oldname>/database` is cosmetically odd but correct.

---

## 2. Part A — `template: true` and Save-as-template

### 2.1 Why the copy is safe

D2's hard gate is enforced by **never registering a template**, which makes it structurally unreachable by
every run path at once — the same guarantee the existing `spaces/_templates/` underscore sentinel gets from
`SpaceManager.discover`, but in-space. No trigger route, no poll cycle and no scheduler can reach a config
that is not in the run registry, so there is exactly one gate to get right instead of five.

`active: false` alone is **not** sufficient and is the trap to avoid: an inactive pipeline is still
registered and still runnable via `POST /runs/{name}/trigger`, pointed at the original's inbox and output.

### 2.2 Backend changes

1. **Spec key** — add to `ConfigSpecs.pipeline()`:
   `template` (BOOLEAN, optional, default `false`) — "A non-runnable authoring template. Never registered,
   never polled, never triggerable. Clear this to arm the pipeline."
2. **Parser** — `PipelineConfigParser.parse`: `b.template = …getOrDefault("template", "false")`; add
   `PipelineConfig.template()`. Fail-safe default `false` keeps every existing config byte-identical.
3. **Refuse registration** — `CollectorService.registerPipeline(Path)`: if `cfg.template()`, throw the
   house `IllegalStateException` → mapped 409 by `RunRoutes` alongside the existing duplicate-id refusal.
4. **Skip at boot** — `SpaceBootstrap` config scan: skip a `template: true` pipeline with an INFO log
   (`"skipping template pipeline '<id>' — not registered"`). Mirrors how `_templates` is skipped.
5. **List surface** — ⚠ **the design consequence to handle.** `GET /pipelines` projects
   `api.service().pipelines()`, i.e. the *run* registry ([`PipelineRoutes.java:69-71`](../../inspecto/src/main/java/com/gamma/control/PipelineRoutes.java)),
   so an unregistered template is invisible to the editor. Merge templates into `flowSummaries` from the
   config-scan read surface (`ConfigRegistry`, which is read-surface-only by design), each flagged
   `template: true`. **The run registry stays template-free — only the read surface gains them.**
   → *Verify first:* confirm `ConfigRegistry` is reachable per-space from `ApiContext`; if not, scan the
   space config dir directly in the read surface.

### 2.3 What the copy contains (D3)

`POST /pipelines/{name}/save-as-template` with `{ id, name? }` reads the source config and writes a sibling:

**Tokenized (repointed to a per-template sandbox — every one of these is what would otherwise collide):**

| Key | New value | Collision it prevents |
|---|---|---|
| `id`, `name` | the requested new id / name | duplicate-id refusal at construction |
| `template` | `true` | — (the gate itself) |
| `active` | `false` | belt-and-braces |
| `dirs.*` | `templates/<newid>/{inbox,database,backup,temp,errors,quarantine,markers,status,logs}` | reads the original's inbox; writes its output tree, audit CSVs, commit log |
| `stream` | explicitly `<newid>` | silently joining the original's Catalog Stream when left implicit |
| `collector.id` | explicitly `<newid>` | sharing the original's ledger dedup key + watermark |
| `output.ducklake.data_path` (if present) | `templates/<newid>/ducklake` | writing the original's DuckLake tables |
| `processing.schema_file` | `<newid>_schema.toon`, **the source schema copied alongside** | pointing at a file the operator will then edit in place under the original's name |

Placeholder paths must be **real, write-root-jailed paths** — `ConfigSafetyValidator.checkPipeline`
resolves every `dirs.*` against `SafetyPolicy.allowedRoots`, so a literal `${TODO}` token would fail the
write. `templates/<newid>/…` is valid, jailed, self-evidently non-production, and cannot collide.

**Carried verbatim (the "critical configurations" worth replicating):** `produces`, `reference.*`,
`processing.threads`, `file_pattern`, `duplicate_check.*`, `csv_settings`/parsing + delimiter + frontend,
`collector.discovery` / `duplicate.mode` / `post_action` / `connector` / `gap_detection`, `output.format` +
`compression`, and `trigger` (safe: an unregistered template is never scheduled).

**Deliberately NOT copied — this is the "non-existing processors/jobs" the ask calls for:** companion
`*_enrich.toon` and `*_job.toon` files, and any expectation / decision-rule / dataset / widget / dashboard
that targets the original. The template is one pipeline file plus its schema. Promoting it is an explicit
follow-up act, not a side effect of copying.

---

## 3. Part B — rename as a migration route

### 3.1 Route

`POST /pipelines/{name}/rename` → `{ newId, newName?, relocateDirs?: false, rewriteDependents?: true }`
(`canAuthorWorkbench`). Gate order per the `endpoint` skill:

1. write-root missing → **503**
2. source pipeline unknown → **404**
3. `newId` fails `[a-z0-9][a-z0-9_]*` → **422**
4. source is `active: true` → **409** *(mirrors `ConfigRoutes.deleteConfig`'s existing refusal wording:
   "deactivate (`active: false`) before renaming")*
5. source is currently running — `PipelineRunGuard.isRunning(oldId)` → **409**
6. `newId` already taken by any pipeline **or** `<newId>_pipeline.toon` exists → **409**
7. then migrate.

### 3.2 New ledger API

`AcquisitionLedger` has no rename (its public surface is `find` / `record` / `highWatermark` /
`dbWatermark` / `recordDbWatermark` / `prune` / `countPrunable` / `maintenance` / `close`). Add:

```java
/** Repoint every ledger row and watermark from {@code oldSourceId} to {@code newSourceId}; returns rows moved. */
int renameSource(String oldSourceId, String newSourceId);
```

`DbAcquisitionLedger` implements it as two UPDATEs (ledger + watermark) in one transaction. Safe only while
stopped — DuckDB is single-writer, which gate 5 already guarantees. ⚠ `BACKLOG` §6 already flags
`DbAcquisitionLedger.record()`'s DELETE+INSERT as atomic only via the single connection + monitor; hold the
same monitor here and do not widen that latent issue.

### 3.3 Migration order (delete-then-add, never add-then-delete)

`CollectorService`'s constructor **and** `registerPipeline` both reject duplicate ids
(`6d371d66`, 2026-08-01), so a momentary two-config overlap during a hot rename would trip the guard.

1. `unregisterPipeline(oldPath)` — evicts run-guard / scheduler / governor state (S9)
2. `ledger.renameSource(oldId, newId)` (S5, S6)
3. rename `<oldId>_commits.log` and every `<oldId>_{status,batches,lineage}_*.csv` in the status parent (S2, S3)
4. `UPDATE` the DuckDB status mirror's `pipeline` column (S4)
5. if `relocateDirs` — move the `dirs.*` trees whose path contains `<oldId>` as a whole segment, and rewrite
   those values (§1.1: **off by default**)
6. write `<newId>_pipeline.toon` with `id: <newId>` + `name`, via the existing `/config/write` machinery
   (spec + `ConfigSafetyValidator` + `AtomicFiles.write`); delete `<oldId>_pipeline.toon`
7. if `rewriteDependents` — rewrite each key in §1's dependent table across the space's toons
8. `registerPipeline(newPath)`; `MetadataGraphService.invalidate()` (S7)
9. emit a `PIPELINE_RENAMED` event carrying both ids (S10 stays untouched)

**Failure posture:** steps 2–7 are not one transaction and cannot be. Order is chosen so the *config* write
(step 6) lands after the state moves: a crash before it leaves the old config registered against
already-renamed state, which the route reports as a partial migration naming the completed steps. Record a
`rename.journal` line per completed step in the write root so a rerun is resumable rather than guesswork.
**This is the plan's main residual risk and the reason for the stop-first gates.**

### 3.4 Cheap prerequisite worth doing first

Every existing config omits `id`. Add a `stampId` step (write `id: <derived>` into a config that lacks it,
changing nothing observable) so operators can opt into the safe **label-only** rename — edit `name` freely
with zero migration — without going through §3.3 at all. This is ~20 lines, is what the spec's own
docstring recommends, and will be the right answer for most renames. It does not replace D1; it sits beside it.

---

## 4. Part C — UI

Both actions go in the pipeline editor's `#flowMenu` ⋮ menu
([`pipeline-editor.component.html:38-43`](../../inspecto-ui/src/app/modules/admin/pipelines/pipeline-editor.component.html)),
which today holds only "Delete pipeline". House item shape: `<button mat-menu-item (click)="…">` +
`<mat-icon svgIcon="heroicons_outline:…">` + `<span>`.

- **`heroicons_outline:pencil-square`** → "Rename pipeline…" → `PipelineRenameDialog`
- **`heroicons_outline:document-duplicate`** → "Save as template…" → `PipelineSaveAsTemplateDialog`

Both dialogs follow [`rule-save.dialog.ts`](../../inspecto-ui/src/app/inspecto/rule/rule-save.dialog.ts):
standalone, `OnPush`, inline template, exported `…Data` interface for `MAT_DIALOG_DATA`, `saving` signal,
`uniqueNameValidator(() => taken)` from
[`unique-name.ts`](../../inspecto-ui/src/app/inspecto/investigation/unique-name.ts) so a duplicate id blocks
**inline** rather than relying on the server 409, `dialog.open(…, { width: '520px', autoFocus: false })`.
⚠ The rename dialog must **exclude the current id** from the taken list or it blocks its own prefilled value.

Rename carries more than one field (new id, new name, `relocateDirs`, `rewriteDependents`) so a dialog is
justified; contrast `tags.component.ts`'s inline rename, whose comment notes a single field does not warrant one.

Error handling per the pane's existing idiom: 503 → latch `unavailable()` + the fixed read-only message
(`onWriteError`); 409 → `setErrors({ duplicate: true })` on the id control; refusal details via
`showRefusals()` before the generic path; otherwise `apiErrorMessage(err, fallback)`.
`PipelinesService` gains `rename(name, body)` and `saveAsTemplate(name, body)`. `PipelineSummary` gains
`template: boolean`; templates render with a badge and a disabled activate control. After a successful
rename, `select(newId)` and update the `flows` signal optimistically (this pane does not refetch).

---

## 5. Verification

Per the `build-verify` skill (`mvn -o` reactor + UI vitest). Success criteria:

1. **Back-compat** — every existing pipeline config parses with `template=false`, boots and registers
   exactly as before. No golden-file churn.
2. **Template is unreachable** — a `template: true` config: is skipped at boot; `POST /runs` → 409;
   appears in `GET /pipelines` flagged; `POST /runs/{id}/trigger` → 404 (never registered).
3. **Isolation** — save-as-template from a pipeline with a full `dirs.*` block, then diff: assert **no**
   `dirs.*` value, `stream`, `collector.id` or `data_path` equals the source's. This is the ask's core
   guarantee and gets a dedicated test.
4. **Rename preserves history** — ingest 2 files, rename, then `GET /runs/{newId}/commits` and `/batches`
   return the pre-rename rows (proves S2–S4), and re-dropping an already-ingested file is still deduped
   (proves S5).
5. **Rename gates** — real-HTTP test per the `endpoint` skill covering all seven gates in §3.1.
6. **Dependents** — rename with `rewriteDependents`, then assert the enrich/job/expectation/dataset refs
   resolve to `newId` and `MetadataGraphService.structural()` has `stream:<newId>` and no `stream:<oldId>`.
7. **UI** — dialog specs mirroring `rule-save.dialog.spec.ts` (invalid → no call; duplicate → inline
   `duplicate` error, no call; happy path asserts payload + `close`; `expectNoA11yViolations`).

## 6. Sequencing

| Phase | Scope | Status |
|---|---|---|
| **T1** | `POST /pipelines/{name}/label` — stamp `id`, set display `name`, surface `displayName` | ✅ **SHIPPED** 2026-08-02 |
| **T2** | `template` flag (config + parser + cross-field rule), the three run gates, `save-as-template` route | ✅ **SHIPPED** 2026-08-02 |
| **T3** | Part B — `renameSource` ledger API, rename route, migration + journal | ✅ **SHIPPED** 2026-08-02 |
| **T4** | Part C — UI menu items, two dialogs, service methods, mock, specs | ✅ **SHIPPED** 2026-08-02 |

### T1 + T2 as-built surface

| Thing | Where |
|---|---|
| `template` field / accessor / builder | `inspecto-etl/.../PipelineConfig.java` |
| parse + `template`×`active` refusal | `inspecto-etl/.../PipelineConfigParser.java` |
| `template-cannot-be-active` rule | `inspecto-config/.../ConfigSpecs.java` |
| `refuseIfTemplate` + public `isTemplate` | `inspecto/.../service/CollectorService.java` |
| poll-cycle skip | `inspecto/.../service/PipelineScheduler.java` |
| `save-as-template` + `label` + `neutralizeForTemplate` + `copySchemaFile` | `inspecto/.../control/PipelineRoutes.java` |
| trigger 409 mapping + reprocess guard | `inspecto/.../control/RunRoutes.java` |
| route capability entries | `inspecto/.../control/CapabilityManifest.java` |
| 10 real-HTTP tests (every gate + the isolation guarantee) | `inspecto/src/test/java/com/gamma/control/ControlApiPipelineTemplateTest.java` |

### T4 as-built surface

| Thing | Where |
|---|---|
| `saveAsTemplate()` / `label()` + `template`/`displayName` on `PipelineSummary` | `inspecto-ui/src/app/inspecto/api/pipelines.service.ts` |
| ⋮ menu items, `isTemplate`/`selectedSummary`, the two handlers | `.../modules/admin/pipelines/pipeline-editor.component.{ts,html}` |
| the two dialogs (+ specs, axe-checked) | `.../pipelines/pipeline-{template,rename}.dialog.ts` |
| offline handlers + strictness spec | `.../inspecto/mock/handlers/pipelines.handler.{ts,spec.ts}` |

**T4 notes for whoever picks up T3:**

1. **`Activate` is not rendered for a template at all** (rather than disabled): the server refuses every
   run path, so an affordance that can only fail is worse than none. Promotion = clearing `template: true`.
2. **The mock's `liftConfig` puts the raw `name:` on the graph where the server's `PipelineLift` puts the
   derived IDENTITY.** `configSummary` now overrides it with the store key. Left alone, a relabelled
   pipeline listed under its new label and then 404'd on `GET /pipelines/{name}/graph/raw` — a
   mock-more-lenient-than-server bug that only appears once renaming exists. **T3 must keep that override
   honest**, since a real rename moves the identity itself.
3. The rename dialog states plainly that the identity does not move. When T3 lands, that copy is what
   needs revisiting — the two operations will need distinguishing in the menu ("Rename" vs "Change id").

On ship: distil as-built facts into `docs/okf/` (pipeline identity + control-plane concepts), move residuals
to `docs/BACKLOG.md`, `git mv` this plan to `docs/archived-documents/plans-archive/`, then `graphify update .`
