# Pipeline identity: rename & Save-as-template

**Routes:** `PipelineRoutes` — `POST /pipelines/{name}/label`, `POST /pipelines/{name}/save-as-template`,
`POST /pipelines/{name}/rename`. Shipped 2026-08-02 (T1/T2 → T4 UI → T3 full migration); as-built for
`docs/archived-documents/plans-archive/pipeline-rename-and-template-plan.md`.

## The identity model

`ConfigSpecs.pipeline()` splits `name` (display) from `id` (stable identity, `[a-z0-9][a-z0-9_]*`,
immutable once set). `id` is **absent from every config written before 2026-08-02** — identity is
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

Stamps `id` with today's derived value (idempotent — a no-op if already set), then writes the new `name`.
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
step is appended to `<writeRoot>/rename.journal` — **evidence for manual reconciliation, not an automated
resume mechanism.** A retried rename after a partial failure is a fresh call with its own gates; nothing
reads the journal back.

## Backlog (not built)

- **UI wiring for `rename`.** The Pipelines editor's ⋮ menu still only offers `label` (as "Rename…") and
  `save-as-template`; there is no affordance for the full migration yet. The existing rename dialog's copy
  ("the identity stays…") is accurate for what it calls, but a real "change id" action needs its own,
  clearly distinguished entry — conflating the two in one dialog would misrepresent which artifacts move.
- **Automated resumability from `rename.journal`.** Today it is an audit trail an operator reads by hand.
