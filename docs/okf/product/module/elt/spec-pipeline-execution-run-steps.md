---
type: Concept
title: Pipeline execution — run steps
description: The work around a Pipeline run - the ordered registry of commit steps (COMMIT and RUN phases) and pre-run steps (GO, SKIP, reload, after), the import's steps (ImportCommitSteps, ImportPreRunSteps), the claim a run holds, and the cadence wake that honours each interval schedule's every.
resource: inspecto/src/main/java/com/gamma/service/CollectorService.java, inspecto/src/main/java/com/gamma/service/PipelineScheduler.java, inspecto/src/main/java/com/gamma/control/ImportCommitSteps.java, inspecto/src/main/java/com/gamma/control/ImportPreRunSteps.java
tags: [elt, engine, scheduler, commit-steps, pre-run-steps, import, cadence, trigger, onboarding]
timestamp: 2026-11-19T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-19 last-edit
code: platform/inspecto-etl
---

# Pipeline execution — run steps

What runs around a Pipeline run, before it reads its inbox and after it commits, and how an interval schedule keeps its
own cadence. Split from [spec-pipeline-execution.md](spec-pipeline-execution.md) on 2026-11-19 (the headings there point
here); the lanes, triggers and recovery stay on that page.

## Commit steps — the work a commit needs before it is done

(`BASIC-ELT-PROFILE-1` BE-1a, as built 2026-11-19; replaces the single `commitStep` of `LA-DATASET-COMPACT-2`.)
`CollectorService` keeps one **ordered registry of named commit steps** for the JVM:
`CollectorService.commitStep(name, order, phase, step)`. Steps of a phase run in ascending `order` (a tie: by name),
each after the one before has returned. Registering a name again replaces it (each ControlApi re-registers on attach).
A step that throws is logged and the next step runs; nothing throws out of the publishing thread (the bus also isolates
its subscribers, but that would skip the remaining steps). There are two phases:

| Phase | When | Handed | Must not |
|---|---|---|---|
| `COMMIT` | Every Consignment (SUCCESS or FAILED, dry runs included; the step filters), on the publishing thread, before the Space's Jobs are handed it. The subscriber is registered in the constructor, before `JobService.start()`. | that commit | be slow, start a run, or wait for ingest to end: the thread is inside the Consignment's `IngestActivity` scope and holds the run's claim |
| `RUN` | Once per run of a Pipeline that committed: after the run's last Consignment, outside every ingest scope, while the run's claim is still held (`commitStepRun`, around `runPipeline`'s and the poll's `runOne` run). A SUCCESS commit published outside a run (an enrichment's, a replay of rejected records, one Consignment each) runs it at once. | the run's last SUCCESS, non-dry commit | assume the Space's Jobs have not seen the commits; they have |

- **Why two phases.** A run commits once per Consignment, and `collector.consignment.max_files` (legacy `processing.batch`) defaults to `1`, so a run of
  N inbox files commits N times. Work that should happen once per run (an index build, the Expectations) would
  otherwise run N times. It also cannot run on the publishing thread: a Link Analysis index build waits while
  `IngestActivity.active()`, and that thread is inside its own Consignment's ingest scope, so the build would wait for
  itself until its timeout. `COMMIT` keeps the old guarantee (the work finishes before the Space's Jobs see the commit)
  for quick work such as compaction.
- **The import's steps** (`ImportCommitSteps`, control plane): `import.compaction` (100, `COMMIT`),
  `import.follow_on` (200, `RUN`: each `ImportFollowOn`, e.g. Link Analysis's `index`), `import.expectations` (300,
  `RUN`). They act only for a Pipeline Add data wrote for that Dataset (`OnboardImport.isOurs`), whose `import_steps`
  names `follow_on` (a Pipeline with no `import_steps`, written before BASIC-ONBOARD-1 P1, runs all four steps). Each
  claims the Dataset in `OnboardImport.IN_FLIGHT` and skips it when the claim is held. Add data's own run holds that
  claim and calls the same list in-run (`ImportCommitSteps.runAll`), so the steps run once, in the same order, with
  the same outcome shapes (`compaction`, `index`, `expectations`).
- ⚠ **A Standard feed's `la.index.build` Job still runs** (`DatasetFeed.indexJob`, offered by *Keep this up to date*).
  It now overlaps the `import.follow_on` step on a scheduled run: the follow-on's build waits for the Job's build
  (`LinkIndexFollowOn`, wait budget -1) and re-plans, so the index is not built wrong, only checked twice. It also
  still runs while `import.compaction` merges files, as before. Retiring the Job is BE-1 follow-up work, not done here.

### Pre-run steps — the other side of the run

(`BASIC-ELT-PROFILE-1` BE-1b, as built 2026-11-19.) The matching registry before a run reads its inbox:
`CollectorService.preRunStep(name, order, step)`, same rules as the commit steps (ascending `order`, a tie by name; a
second registration of a name replaces it; a step that throws is logged and the run goes ahead without it). A step runs
on the run's thread, under its run claim, outside every ingest scope and before acquisition, and answers a `PreRun`:
`GO`, `SKIP` (the run does not start and answers an empty result; the next tick looks again; the steps after it do not
run) or `then(after)`. Each `after` is told whether the run succeeded (no failed or cancelled source, nothing thrown),
last registered first, **after** the run's `RUN` commit steps. `stepRun` wraps `commitStepRun`: the poll's `runOne`
(`scheduled` true) and `runPipeline` (`scheduled` false) both pass through it; a dry run runs no pre-run step.

- **The import's steps** (`ImportPreRunSteps`, control plane): `import.judge_inbox` (100: `OnboardImport.judgeInbox` —
  every waiting file checked by the Dataset's columns, a refused one moved to `refused/` and recorded as a refused
  arrival) then `import.settle_new_columns` (200: `DatasetNewColumns.settle` — the new columns every file the run takes
  carries are kept). ONE engine step, `import.pre_run` (100), runs that list, because the second step reads what the
  first judged. Add data's in-run paths (Add a file, Run now) call the same list (`ImportPreRunSteps.runAll`), so the
  order and outcome shapes (`refused`, `keptColumns`, `newColumnsReason`) are one implementation.
- **The claim.** The engine step acts only for a Pipeline Add data wrote for that Dataset (`isOurs`). It takes the
  Dataset's `OnboardImport.IN_FLIGHT` claim and **holds it through the run** (released in its `after`); the import's
  commit steps treat a claim held by the run's own pre-run step as theirs (`ImportPreRunSteps.heldByRun`), so they
  still compact, follow on and check Expectations, and Add data cannot judge the inbox under the run. When someone else
  holds the claim (Add data's own run, a rewrite, a delete) a **scheduled** run is skipped and a person's run goes ahead
  untouched (Add data's own `runPipeline` is such a run: it already did the steps).
- **A file still being written.** A scheduled run of a `local` Collector with a stability window judges only the files
  unmodified for that window, and takes only those (`RunFiles.only` over the inbox until the run ends): a file written
  between the step and the read is neither judged half-written nor taken unjudged; it waits for a later run. A remote
  Collector's files land during the run, so its run judges what waits before it and is not narrowed.
- **The run reads what the step wrote.** A step that changes the Pipeline's config files answers
  `PreRun.reloadThen(after)`; the poll's run then reads the config again (`PipelineScheduler.reloaded`: a
  fingerprint-cached registry rebuild) instead of the one selection parsed, so a column kept for a file is read with
  that file in the same run. `runPipeline` reads the file per run anyway.
- **A failed run** takes a kept column back (`Job.rollback` in the `after`), as Add data's own run does. A run that
  sets a file aside after the step kept a column counts as failed for this, though the engine's own result counts no
  failure. The set-aside file is read from the Pipeline's quarantine folder itself (`FileStatusStore.quarantine`), not
  from the service's status store: the default `status.backend=db` store is a projection that catches up only after
  the run (the poll's `syncStatus`), so it would not yet hold the file (tested under both stores). Claims and kept columns are given back on every path, an `Error` included.
- **Watch this folder** (B1) is a scheduled run of this kind: the import Pipeline's locked `every: 30s` schedule with a
  `local` Collector and `stability {30s, 2}` (`InboxWatch`). For it the step also waits while `data.self_import` is
  off (SKIP) and keeps `watchError` for Data status (cleared by a run that succeeds).

### Honour each every (B1, operator 2026-11-19)

An interval schedule (`trigger: {type: schedule, every: …}`) runs on its own cadence, not only on the Space's poll tick
(`service.poll.seconds`, default 60 s), which alone would run an `every: 30s` Pipeline every 60 s. `CollectorService`
keeps a cadence wake beside the poll tick: it sleeps until the next interval-scheduled Pipeline is due
(`PipelineScheduler.nextCadenceDueMs`: last run plus `every`; a running one is left out), then starts only the
interval-scheduled Pipelines that are due (`dispatchCadenceCycle`, the same claim and stamping as the tick, so the two
never run one Pipeline twice). It waits at least a second and at most one poll tick between wakes (a new or edited
Pipeline is seen by then); when nothing due could start (still running, read-only) it looks again after at most 5 s.
`DEFAULT_POLL`, cron and event triggers are unchanged. Test: `CollectorServicePreRunStepsTest.anIntervalScheduleRunsOnItsOwnCadenceNotThePollTick`.
