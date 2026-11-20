---
type: Concept
title: Arrival replace — Replace the period and Replace everything in the engine
description: The engine's ArrivalReplacer (BASIC-ELT-PROFILE-1 B4) - Replace the period's crash-safe rewrite of an import Dataset (journal, one swap under the store's lock, heal forward or back), the REPLACED registry state that Replace the period and Replace everything leave so a reprocess cannot bring replaced rows back, the reprocess refusal answered 409, and the output.load Pipeline key (O6) that runs a replace or a period at each commit, with Add a file's run-scoped choice.
item: module.elt
audience: internal
status: current
verified: 2026-11-20 last-edit
code: platform/inspecto-engine
---

# Arrival replace — *Replace the period* and *Replace everything* in the engine

(`BASIC-ELT-PROFILE-1` B4, as built 2026-11-20.) Add data's *Replace the period* (`LA-ARRIVAL-MODES-1`, built in the
control plane's `DatasetPeriod`) is now the engine's `com.gamma.job.ArrivalReplacer`, beside
[`ArrivalCompactor`](spec-arrival-compaction.md), whose merge map it keeps true. What the person sees is unchanged: the
same answer (`period {days, from, to, rowsReplaced, filesRewritten}`), the same arrivals, the same refusals. The Link
Analysis hub keeps the user-facing story:
[architecture-data-onboarding-arrivals-storage.md §6.17](../la-core/architecture-data-onboarding-arrivals-storage.md).

## What a replace does

After the run ADDED the upload's rows, every other Parquet file of the import Dataset (`<dataRoot>/<id>/database`, the
files this run wrote excepted) loses its rows on the calendar days the upload covers (`CAST(<When column> AS DATE)`, a
row with no date stays). A file left with no row goes. The steps (`replacePeriod`):

1. Heal first (a replace a crash interrupted is finished before anything is read).
2. Each touched file's rewrite is written beside it as a hidden `.period-<name>.tmp` (no Parquet glob reads it); the
   merged file of a compaction is rewritten in its row order and each arrival's segment re-counted.
3. The journal `<dataRoot>/<id>/period.pending` is written: the aside folder `database.period-<stamp>`, each file and
   its rewrite, the upload's own outputs, the merge map's next segments.
4. The merge map is copied into the aside folder; then **every** file is swapped at once under the store's
   `SnapshotSwapLock`: the original moves aside, the rewrite takes its path. A reader sees the old files or the new ones.
5. The merge map is committed with the re-counted segments; the journal says `swapped`.

Then the import decides:

| The import… | Call | What happens |
|---|---|---|
| succeeded | `drop` | every touched file is marked `REPLACED` in the Consignment output registry, the originals and the journal go |
| failed | `restore` | the originals come back, the merge map as it was, and the upload's own outputs go (no day left doubled); the journal says `restoring` until done |

Each is a no-op after the other. Add data's `OnboardImport.Job` reaches both through its run-scoped choice (below): a
rollback restores, `dropAside` drops.

## Crash safety

`ArrivalReplacer.heal(dataRoot, id)` finishes what the journal says was in progress — to the old state or the new one,
never a mix:

| Journal phase | Heal |
|---|---|
| none | nothing |
| `swap` (the process died mid-swap) | **forward**: the swap is finished, the map committed, then as `drop`. The engine committed the upload's rows, so its period is replaced |
| `swap`, but a rewrite not yet swapped is gone | **back**, as `restore` |
| `swapped` (died before the import was decided) | **forward**, as `drop` |
| `restoring` (died mid-restore) | **back**, the restore finished |
| unreadable | refused (`IOException`): never guessed at |

It runs, under the Dataset's claim, before every replace, at the start of every compaction (`ArrivalCompactor.merge`,
so a mix is never merged) and before every run of the import (`ImportPreRunSteps.runAll`: Add a file, Run now and a
scheduled run's pre-run step). Between a crash and the Dataset's next run, replace or compaction a reader may see the
mix. A crash before the journal is written leaves only hidden rewrites, which no read sees.

## The registry: `REPLACED`

`ConsignmentOutput.State.REPLACED`: some or all of the file's rows were replaced by a later arrival. Set by
`ConsignmentOutputStores.markReplaced` (only `LIVE` rows move; paths matched normalised, as `markCompactedAway`):

- *Replace the period*: each file it rewrote or removed, at `drop` (or the heal's forward finish).
- *Replace everything* (B13, 2026-11-20): `replaceEverything` at the upload's commit, marked at `drop` as the period is:
  every Parquet file of the import but the ones the run wrote. (Until B13 Add a file moved the whole `database` folder
  aside before the run, so a crash between the move and the run left the Dataset empty.)

**`REPLACED` is readable.** A rewritten file keeps its path and holds the rows the replacement kept, so
`unreadablePaths` (what `ConsignmentSelector` subtracts from every read and `retire_superseded` deletes), `isReadable`
and `sourcesForPaths` count it as readable, like `LIVE`. `dailyVolume` leaves it out (its `row_count` still counts the
replaced rows). A later compaction of a `REPLACED` file marks it `COMPACTED_AWAY`. Proved by
`ControlApiReprocessRefusalTest`: with `REPLACED` read as unreadable, a Dataset read loses the rewritten file's kept
rows (8 rows read as 3).

## The reprocess refusal

`ReprocessCommand` refuses a Consignment whose outputs are `COMPACTED_AWAY` or `REPLACED` before anything moves, as
`ReprocessCommand.Refused`: its message names the affected files (for the log), its `reason()` names no server path —
how many outputs, what happened to them, what to do instead (load a corrected file, or rewrite the partitions).
`POST /runs/{id}/reprocess` answers it `409 CONFLICT` with that reason (board `REPROCESS-REFUSAL-500-1`, closed by the
operator ruling 2026-11-20). Before B4 a reprocess of a replaced arrival re-read its backup and brought the replaced
rows back.

## The arrival mode as a Pipeline key: `output.load` (O6)

(Built 2026-11-20 on the operator ruling O6.) A Stream's store says how each Consignment's rows arrive:

```toon
output:
  format: PARQUET
  load: period              # append (the default) | replace | period
  period_column: EVENT_DATE # period only: the stored column whose calendar days an arrival replaces
```

- **Run per Consignment at commit.** The `CollectorService` registers the COMMIT step `output.load` (order 50, before
  an import's compaction at 100 and before the Space's Jobs see the commit). On each SUCCESS, non-dry Consignment it
  calls `com.gamma.job.ArrivalLoadStep.commit`: `period` is `replacePeriod` over the Consignment's own outputs,
  `replace` is `replaceEverything` (every other Parquet file of the store goes; a Consignment that wrote no file never
  empties the store). Each one is final at once (`drop`: the files marked `REPLACED`). A replacement that fails (an
  older file without the period column, a value that is no date) puts the older files back and KEEPS the Consignment's
  rows (`abandon`: the engine committed them, so they are never deleted behind a SUCCESS ledger row); the log says at
  ERROR that they were appended. The config path's journal says `keepArrival`, so a heal back (a crash during the
  put-back, or a rewrite lost in a crash mid-swap) keeps those rows too; Add a file's run-scoped choice does not set it
  (a refused import's own rows go, as in B4). A replacement a crash interrupted is finished (`heal`) at the store's next commit,
  whatever its mode by then (not while a run-scoped choice is open: its pending replacement is its caller's).
- **A single local Parquet store only.** `PipelineConfig.arrivalLoadRefusal()` refuses `replace` / `period` on a
  Reference (`reference.load` governs it), more than one destination, a non-Parquet or DuckLake output, an object-store
  `dirs.database`, a partitioned schema, more than one schema and `route:` branches. The load (`requireRunnable`) and the
  save (`SaveGate.arrivalLoadFindings`, finding on `output.load`, 422 at `POST /config/write`) apply the same rule;
  `ConfigSpecs` refuses an unknown mode and a `period` without `period_column` (`output-period-needs-column`), the parser
  a `period_column` the schema does not declare. `append` is never refused.
- **Any store folder.** `ArrivalReplacer.forStore(store, stamp)` works on a Pipeline's own `dirs.database`; its journal
  is `<store>.period.pending` and its aside folder `<store>.period-<stamp>` beside the store (an import's `database`
  folder keeps `period.pending` and `database.period-<stamp>`). The merge map is only an import's.
- **A run-scoped choice** (`ArrivalLoadStep.override(store, load, file)`, closed by the caller) wins over the config for
  the whole run: it replaces at the ONE Consignment that holds `file` (the inbox file name, matched in the on-disk file
  ledger) - or the run's first, when no file is named - and every other Consignment of the run appends; what the run
  committed before it is never removed. Its replacement waits for the caller: `close()` makes it final, `restore()`
  undoes it. One choice per store at a time. **Basic never writes `output.load`:** Add a file opens this choice before
  its run for every mode (`OnboardImport.Job.runLoad`: `replace`, `period` and `append` - an append wins over a
  Pipeline's own `output.load`, a Postgres snapshot's), checks it after the run (`requireLoaded`, `replacedPeriod`), and
  makes it final in `dropAside` or undoes it in a rollback - the same answer, arrivals and refusals as before. A watched
  feed stays `append`. A Postgres snapshot's Run now takes the same path with `replace` (O7, below).

## A Postgres snapshot's Pipeline (O7)

(Built 2026-11-20 on the operator ruling O7.) A snapshot's Pipeline reads the table itself: `collector.connector:
postgres` through its saved Connection, with `output.load: replace` and `processing.duplicate_check.enabled: false`. A
run outside Add data (`POST /pipelines/{id}/run`, a trigger) exports the table (a name of its own per cycle,
`<table>_<stamp>.parquet`, because each output file is named after its input and a fixed name would overwrite the last
output in place, where no replacement can put it back) and replaces the rows at its commit through the config path.
Run now keeps its own export - the column check, the `import.max_rows` cap, the 422 wording - and takes the Add a file
path with a run-scoped `replace` naming its export; its run fetches nothing itself (`RunFiles.noFetch`).

**No other run while a run-scoped choice is open.** The choice applies to whatever commits into the store while it is
open, so the import's pre-run step refuses every run of the Pipeline but Add data's own while Add data holds the
Dataset (`ImportPreRunSteps`, `PreRun.refused`). Without it a `POST /pipelines/{id}/run` started during a Run now
committed its own copy, the open choice took it as Run now's, and the replace kept it (57 rows for 45). The same holds
for Add a file's *Replace everything* and *Replace the period*.

**A failed *Replace everything* undoes only the replacement** (operator ruling 2026-11-20): the originals come back and
the upload's rows go; rows of other waiting files the same run committed stay, as their ledger says they were taken
(the old folder set-aside put the whole folder back, theirs gone with it). The switch, the
migration of an old snapshot and the trade-offs are on the seam's page:
[api-onboarding-postgres-snapshot.md](../processor/api-onboarding-postgres-snapshot.md).

Tests: `PipelineConfigArrivalLoadTest` (parse, refusals at load), `ArrivalLoadStepTest` (a Standard store: period,
replace, append / failed / dry-run / empty untouched, a failed replacement keeps the committed rows (mid-swap, a crash in the put-back, a lost rewrite), a crash healed at the next append commit but not while a run-scoped choice is open; the run-scoped choice: its file only, the run's earlier files
kept, restore and close, one per store), `ControlApiOutputLoadTest` (real HTTP: `POST /config/write` 422 for two sinks,
CSV, a partitioned schema, a missing or undeclared column, an unknown mode, 200 on a flat Parquet store; a Standard
Pipeline replacing at each commit through the service), `ControlApiOnboardFilesTest` (Add a file's period and, since
B13, Replace everything through the run-scoped choice, unchanged answers), `ControlApiSnapshotExportTest` (O7: a
switched snapshot's Run now and a run outside Add data each leave one copy).

## Not done here

- B13's other half: Add a file's *Replace everything* on a Reference and `reference.load: replace` are still two ways
  to replace one store.
- `output.load` in the Pipeline editor (a Standard screen); an edition Gate for it on a Basic build.
- A heal at start-up, so a crash's mix does not wait for the Dataset's next run.
- Arrivals mode for a Standard Pipeline's own store (B6's leftover).

Tests: `ArrivalReplacerTest` (engine: exact days removed and the drop marks; a failed import restores; a replace that
fails before its swap still removes the upload's rows; a crash mid-swap heals forward; mid-restore heals back; after
the swap or mid-drop heals forward; a lost rewrite heals back; the next replace and a compaction heal first; the merge
map stays true both ways; *Replace everything* marks what it removed, never the run's own file; both swaps wait for a reader),
`ControlApiReprocessRefusalTest` (real HTTP: 409 for a period-rewritten and a set-aside arrival, the kept rows still
read, the next run finishes a crashed replace, 400 / 404 and a plain reprocess), `ReprocessCommandTest`,
`DbConsignmentOutputStoreTest`, `ControlApiDatasetCompactionTest`, `ControlApiOnboardFilesTest`.
