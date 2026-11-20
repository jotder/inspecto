---
type: Concept
title: Arrival replace — Replace the period and Replace everything in the engine
description: The engine's ArrivalReplacer (BASIC-ELT-PROFILE-1 B4) - Replace the period's crash-safe rewrite of an import Dataset (journal, one swap under the store's lock, heal forward or back), the REPLACED registry state that Replace the period and Replace everything leave so a reprocess cannot bring replaced rows back, and the reprocess refusal answered 409.
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

Each is a no-op after the other. Add data's `OnboardImport.Job` pushes `restore` on its undo stack and calls `drop`
from `dropAside`.

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
- *Replace everything*: `ArrivalReplacer.everythingReplaced(setAside, database)` from `dropAside`, every Parquet file
  the replace set aside (`database.prev-<stamp>`) by its path in `database` — unless the run wrote that path again.

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

## Not done here

- **The arrival mode as a Pipeline option executed by an engine commit step** (`append | replace | period(column)`,
  plan §5.2 B4): a design fork the plan does not settle (the key's shape, whether a Standard Pipeline may set it, its
  meaning for a partitioned or multi-sink store); left for the operator. Add data still chooses the mode per upload.
- B13: *Replace everything*'s `setAside` itself (a crash between the set-aside and the run leaves the Dataset empty;
  no heal) and its convergence with `reference.load: replace`.
- A heal at start-up, so a crash's mix does not wait for the Dataset's next run.
- Arrivals mode for a Standard Pipeline's own store (B6's leftover).

Tests: `ArrivalReplacerTest` (engine: exact days removed and the drop marks; a failed import restores; a replace that
fails before its swap still removes the upload's rows; a crash mid-swap heals forward; mid-restore heals back; after
the swap or mid-drop heals forward; a lost rewrite heals back; the next replace and a compaction heal first; the merge
map stays true both ways; *Replace everything* marks what it set aside; both swaps wait for a reader),
`ControlApiReprocessRefusalTest` (real HTTP: 409 for a period-rewritten and a set-aside arrival, the kept rows still
read, the next run finishes a crashed replace, 400 / 404 and a plain reprocess), `ReprocessCommandTest`,
`DbConsignmentOutputStoreTest`, `ControlApiDatasetCompactionTest`, `ControlApiOnboardFilesTest`.
