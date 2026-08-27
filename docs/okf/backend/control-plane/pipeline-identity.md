# Pipeline identity: rename & Save-as-template

**Routes:** `PipelineSettingsRoutes` — `POST /pipelines/{name}/label`, `POST /pipelines/{name}/save-as-template`;
`PipelineRenameRoutes` — `POST /pipelines/{name}/rename`. Shipped 2026-08-02 (T1/T2 → T4 UI → T3 full migration); as-built for
`docs/archived-documents/plans-archive/pipeline-rename-and-template-plan.md`.

## The identity model

`ConfigSpecs.pipeline()` splits `name` (display) from `id` (stable identity, `[a-z0-9][a-z0-9_]*`,
immutable once set). **Since 2026-08-17 a newly created pipeline carries `id:` from birth** — the UI's
`pipelineScaffold()` stamps it, and that function is the single payload builder behind *both* create
surfaces (the Pipelines editor's inline "New pipeline" and the Catalog `onboarding-create.dialog`), so
neither can drift from the other. It only stops the id following the name, which makes `label` below a
pure one-field edit rather than a stamp-then-edit.

The stamped value is `pipelineId()` — `derivedPipelineId()` (the faithful mirror of
`PipelineConfigParser.java:81`) **narrowed into the spec's alphabet**: anything outside `[a-z0-9_]`
becomes `_`, and a leading non-alphanumeric is prefixed (`"My-Pipe!"` → `my_pipe_`, `"café"` → `caf_`).
So the stamp is byte-identical to the derivation for any name that already slugs cleanly, and legal for
every other name the create form accepts.

⛔ **The id is deliberately NOT opaque/minted.** It names the config file, `<id>_commits.log`, the ledger
`source_id` and the Catalog Stream — a random id makes an operator's config directory unreadable. If a
fully-decoupled identity is ever wanted, that is a product decision with an on-disk cost, not a cleanup.

### The three-rules disagreement — RESOLVED 2026-08-17

Three rules derived an identity-ish string from `name` and did not agree: the `id` **pattern**
(`[a-z0-9][a-z0-9_]*`, enforced only on an **explicit** id), the **derivation** (lower-case, spaces
underscored, nothing else), and the **filename** (`[A-Za-z0-9][A-Za-z0-9._-]*`, taken from
`ConfigRoutes.identityField("pipeline")` → `name`, a field with **no pattern of its own**). Two live
defects fell out of that, both now closed:

- `"my-pipe"` derived an id its own spec rejects, so `pipelineScaffold` omitted the id — and because
  `PipelineRenameRoutes.rename` enforces the same pattern, such a pipeline was **un-renameable for life**.
- `"My Pipeline"` stamped a perfectly valid `my_pipeline` and was **422'd anyway**, because the filename
  came from `name` and a space is not a safe filename.

Fixed at the two ends rather than in the middle: `pipelineId()` narrows the slug (above), and a
pipeline's filename now comes from **`id`** — the field `rename` already used, so create and rename
finally agree. `identityFields("pipeline")` returns `["id", "name"]`, and a config still living under a
name-derived filename keeps being **edited in place** rather than forked into a second config beside it.

⛔ **The parser's fallback derivation was deliberately NOT narrowed.** It is what a config carrying no
`id:` is keyed by *today*; narrowing it would silently re-key every such pipeline already on disk — its
filename, `<id>_commits.log`, ledger `source_id` and Catalog Stream. The divergence is pinned by a spec
(`derivedPipelineId('my-pipe')` → `my-pipe`, `pipelineId('my-pipe')` → `my_pipe`) so the next reader does
not "tidy" it. Migrating the legacy path is a data migration, not an edit.

⚠ A fallback filename candidate is **probed** (`WriteGates.isSafeName`), never enforced. Calling
`safeName` there throws — which 422'd exactly the writes the id-keyed filename exists to enable. Caught
by a new test, not by review.

⚠ `ConfigFileSupport.fileBase` does not double-suffix, so an id ending in `_pipeline` yields
`<id>.toon`, not `<id>_pipeline.toon`.

`id` is **absent from every config written before 2026-08-02** — identity is
*derived* from `name` (`lowercase, spaces→underscores`) and baked into ~140 call sites: the config
filename, `<id>_commits.log`, the run-timestamped audit CSVs, the acquisition ledger's `source_id`, the
DuckDB status mirror's `pipeline` column, and the Catalog Stream. That embedding is why a rename is a
migration, not an edit, and why the feature ships in three tiers of cost:

| Tier | Route | Moves | Cost |
|---|---|---|---|
| Display-only | `label` | Stamps `id` (if absent), then edits `name`. Nothing else. | Free |
| New sibling | `save-as-template` | Nothing of the source's — writes a new config with every binding repointed. | Free (writes once) |
| Full migration | `rename` | The id itself — every artifact above. | The one with real risk |

## `label` — display-name-only rename

Stamps `id` with today's derived value (idempotent — a no-op if already set, which is now the normal case
for anything created after 2026-08-17), then writes the new `name`.
The file keeps its `<id>_pipeline.toon` name; dirs, audit trail, ledger keys and Stream are untouched, so
no dependent config needs rewriting. This is the route for "I don't need `id` to change" — most renames.

**Gotcha — the findings-diff.** Re-running `ConfigSafetyValidator` over a config already on disk fails it
for `dirs.*` that were never subject to the write-time policy (real deployments routinely keep data
outside the default allowed roots). `label` — and `rename`'s config-write step — block only on
**ERROR-level findings the rewrite introduces**, diffed against the same validation run over the
*unmodified* source. Found by a test, not by review, in both places it was needed. Any future route that
rewrites an existing config in place needs the same diff, not a bare re-validate.

## `save-as-template` — a non-runnable sibling

D2: a template is **registered normally** (the original plan's "never register it" doesn't work —
`ConfigRegistry`, the read surface behind `GET /pipelines`, rebuilds from the same list as the run
registry, so excluding a template would make it invisible and unpromotable). Runnability is gated at the
three places work actually starts instead: `PipelineConfigParser` (refuses `template: true` + `active:
true` — a `CrossFieldRule`, since `template` is a lifecycle flag like `active`, absent from the spec on
purpose so it doesn't become a schema-form field), `CollectorService.refuseIfTemplate` (on every manual
trigger path), and `PipelineScheduler.selectDue` (belt-and-braces skip).

D3 — the isolation guarantee, the ask's actual point (*"running the pipeline should not impact original
pipeline"*): every environment binding is repointed into `templates/<id>/` — `dirs.*`, `stream`,
`collector.id`, `output.ducklake.data_path` — and `processing.schema_file` is **copied**, not
referenced, so editing the template's schema cannot edit the source's. Companion `*_enrich.toon`/
`*_job.toon` and anything targeting the source are deliberately **not** copied — promoting a template is
an explicit act, not a side effect.

## `rename` — full identity migration

Moves the id itself. Everything below is what a naive "just edit the filename" would silently break.

**Persistent state that moves** (each keyed by the old id):

| What | Where | How it moves |
|---|---|---|
| Config file | `<id>_pipeline.toon` | Write `<newId>_pipeline.toon` (spec+safety gated, same as `/config/write`), delete the old file |
| Commit log | `<id>_commits.log` | `Files.move` — same directory, `dirs.*` are **not relocated** |
| Audit CSVs | `<id>_{status,batches,lineage}_*.csv` | `Files.move` each, glob-matched exactly as `FileStatusStore.readRuns` reads them |
| DuckDB status mirror | `pipeline` column, 5 tables | `DbStatusStore.renamePipeline` — one transaction, all tables |
| Acquisition ledger | `source_id` (dedup key) + DB-export watermark | `AcquisitionLedger.renameSource` — two `UPDATE`s under the ledger's monitor |
| Run-guard/scheduler bookkeeping | in-memory, keyed by id | Free: `unregisterPipeline`/`registerPipeline` already do this |
| Catalog Stream | derived from config at read time | Free: `registerPipeline` invalidates the catalog as a side effect |
| Event history | `Event.pipeline()` | **Deliberately NOT rewritten** — history records what was true then |

**`AcquisitionLedger.renameSource` had to be implemented on BOTH backends.** The interface's default is a
no-op (correct for a minimal implementer), but `-Dacquire.ledger.backend` defaults to **memory** —
`InMemoryAcquisitionLedger` IS the production default, not a test double. Relying on the interface default
would have made the ledger migration silently do nothing for most deployments while the response still
reported `ledgerRowsMoved`. Both backends now rekey their rows/watermark for real.

**Gates** (write-root 503 → source unknown 404 → jail 403 → `newId` shape 400/422 → source `active` 409 →
source *running* 409 → `newId` taken 409 → `relocateDirs` unsupported 422 → migrate):

- **The running-pipeline gate is the one deliberate exception to "`PipelineRunGuard.isRunning` is
  diagnostics, never a gate."** `active: false` alone doesn't rule out a live run — an inactive pipeline
  is still manually triggerable — and migrating ledger/audit state out from under a run in flight would
  corrupt it, unlike the guard's own exclusion concerns (which are about serializing *runs*, not about a
  one-shot administrative migration racing one). `CollectorService.isRunning(String)` is a narrow public
  wrapper added for exactly this caller; it is TOCTOU-limited by nature (a run can start immediately after
  it returns false) and documented as such — it narrows the window, not closes it.
- **`relocateDirs: true` is refused (422), not silently ignored.** `dirs.*` are left pointing where they
  already do (Stage-1 output trees are a bulk data move with real blast radius); a caller that asks for
  relocation gets a clear error rather than a response that looks successful while the trees stayed put.

**Dependent config rewrite** (`rewriteDependents`, default `true`) — best-effort per file, one malformed
sibling never aborts a migration whose state-moving steps already committed:

| Config | Key | Note |
|---|---|---|
| `*_enrich.toon` | `triggers.on_pipeline` | Direct files under the write root |
| `jobs/*_job.toon` | `on_pipeline` (top-level) | |
| `expectation` / `decision-rule` components | `target` when `targetType: pipeline` | `ComponentStore`-registered, `registry/<typeDir>/<id>.toon` |
| `dataset` components | `sourceName` **and** first segment of `physicalRef` | ⚠ widened past `DataSourceBundleResolver.datasetReadsStore`, which only checks `physicalRef` — a `kind: virtual` dataset uses `sourceName` directly, and a rename that missed it would leave the dataset silently reading nothing |

**Failure posture — not one transaction, by design.** DuckDB, the filesystem and the config write are
three different failure domains; steps 2–7 cannot be wrapped in a single commit. Ordering is chosen so the
config write lands *after* the state moves: a crash before it leaves the old config's file in place, so
the route's `catch` block can re-register it and keep the pipeline reachable — but ledger/audit state
already moved under earlier steps stays moved (a documented residual risk, not a bug). Every completed
step is appended to `<writeRoot>/rename.journal`, and since 2026-08-13 the journal is **read back by
`POST /pipelines/rename/resume`** (below) — an interrupted migration is finished, not reconciled by hand.

## `resume` — finishing an interrupted migration (shipped 2026-08-13)

`POST /pipelines/rename/resume` closes the failure-posture gap. The journal now **brackets** each
migration: a `begin` line (written before any state moves, recording the source file name, `newName` and
`rewriteDependents`) and a `completed` line (after the event emit). A `begin` with no `completed` is an
incomplete migration, and resume re-runs its remaining steps. Design points worth keeping:

- **Why a plain retry can't heal everything.** Two crash windows defeat it: between `AtomicFiles.write`
  of the new config and the source delete, both files exist → retry 409s on file-exists; after the source
  delete but before re-registration, the pipeline is registered under *neither* id → retry 404s. Resume
  handles both from journal + file state.
- **The journal supplies discovery + parameters; the file state is truth for the config step.** Resume
  never step-skips off journal lines (journal writes are best-effort and can be lost) — instead every
  step is idempotent and simply re-run: the ledger/status-mirror renames match zero rows once moved
  (verified on both ledger backends), the audit-file glob matches nothing once renamed, and each
  dependent rewrite checks `equalsIgnoreCase` before touching a file. Only the config write branches on
  what exists: src-only → full validated write; new-only → skip; both → delete the source; neither → 409,
  manual reconciliation.
- **Fail-closed identity checks before touching anything.** A surviving `<newId>_pipeline.toon` must
  decode with `id == newId` (else 409 — never delete the source under a squatter), the source file must
  still carry the old identity, and the lifecycle gates re-run (the failed attempt's recovery re-registers
  the source, which may have been *reactivated or started* since).
- ⚠ `renameAuditFiles` derives the old commit-log **file name from `oldId`**, taking only the *parent*
  from the config's `commitLogPath` — because resume may hold only the NEW config, whose own
  `commitLogPath` already carries the new id (it is parser-derived as `<parent>/<pipelineName>_commits.log`).
  Trusting the config's file name verbatim would have moved the new log onto itself.
- **Explicitly operator-invoked, never a startup hook** — an automatic state migration at boot would act
  without operator intent. Several incomplete migrations → 409 listing them; body `{oldId, newId}` picks
  one. Migrations journaled before the bracket existed have no recorded parameters and stay
  manual-reconciliation cases (deliberate). The `PIPELINE_RENAMED` event is at-least-once: a crash between
  the emit and the `completed` line duplicates it on the next resume — history noise, never state
  corruption.
- A 422 findings-refusal now journals a `refused:` line but leaves the bracket **open** — honest, because
  ledger/audit state moved in steps 2–4 before the refusal; fixing the config and resuming (or re-running
  rename, whose `completed` closes every open bracket for the pair) completes the half-moved state.
- No UI or mock surface: resume is an operator/API recovery action; the offline mock has no
  partial-failure model and must not pretend to (mock never claims work it didn't do).

## UI wiring (shipped 2026-08-13)

The ⋮ menu's **"Change id…"** entry (`PipelineChangeIdDialog`, `pipeline-change-id.dialog.ts`) calls the
full migration — its own entry, deliberately separate from "Rename…" (`label`), whose "the identity
stays…" copy is only accurate for the route it names. The dialog leads with a warning alert stating the
migration scope, validates the new id inline (pattern + duplicate, the same checks the template dialog
runs), and keeps the confirm button disabled until the operator types the *current* id — the
`requireText` typed-confirmation shape, warranted by this being the one identity action with real risk.

As-built notes worth keeping:

- **The menu gate reads `model()?.active`, not the list row.** `setActive()` updates the open model but
  never patches the `flows()` summary, so `selectedSummary()?.active` is stale immediately after a
  deactivate — the first gate attempt used it and stayed disabled. The model is the authoritative
  in-editor lifecycle state.
- **An unlabelled pipeline's display name follows the id.** The server keeps `name` unless `newName` is
  sent, and for a never-relabelled pipeline `name` IS the old id — so a bare migration leaves every
  tab/list caption showing the retired identity (found in-browser, not by tests). The UI sends
  `newName = newId` when the pipeline has no custom label; an explicit label survives untouched.
- After success the editor rewrites the flow row + open-tab id and re-`select()`s the new id — the old id
  is gone from the registry, so anything still addressing it would 404.
- The mock (`pipelines.handler.ts` `rename()`) mirrors the server's gate order (404 unknown → 400 missing
  → 422 shape → 409 active → 409 taken) and reports **real zero** counts for ledger/audit/dependents —
  the mock has no model for those artifacts and must not claim work it didn't do.

## Backlog (not built)

- Nothing open. ~~Automated resumability from `rename.journal`~~ shipped 2026-08-13 (§ above).
