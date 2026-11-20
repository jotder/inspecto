---
type: Concept
title: Link Analysis — Watch this folder
description: Watch this folder (section 6.20 of the backend architecture) - the import inbox watched every 30 seconds, first as its own poller (BASIC-ONBOARD-1 P2), since BASIC-ELT-PROFILE-1 B1 as the import Pipeline's locked 30 s schedule run by the engine; the route, the feed answer, the migration, the snapshot exclusion, the UI and the tests.
resource: inspecto/src/main/java/com/gamma/control/InboxWatch.java, inspecto/src/main/java/com/gamma/control/ImportPreRunSteps.java, inspecto/src/main/java/com/gamma/control/DatasetFeed.java
tags: [module, link-analysis, onboarding, add-data, inbox, watch, scheduler, architecture]
timestamp: 2026-11-19T00:00:00Z
item: module.la-core
audience: internal
status: current
verified: 2026-11-19 last-edit
code: la/inspecto-la-core
---

# Link Analysis — Watch this folder

Section 6.20 of the backend architecture, split from [architecture-data-onboarding.md](architecture-data-onboarding.md)
on 2026-11-19 (the heading there points here). Section numbers continue those of
[architecture-la-core.md](architecture-la-core.md).

## 6.20 Watch this folder: the 30 s inbox poll (BASIC-ONBOARD-1 P2, as built 2026-11-05)

Plan [`basic-onboard-plan.md`](../../../../archived-documents/plans-archive/basic-onboard-plan.md) §4.6, §5 Q1; narrows DT-5 / DT-17 (a
Basic import inbox may poll; still no user-defined schedule). Class `inspecto/.../control/InboxWatch.java`.

> **Superseded 2026-11-19 (`BASIC-ELT-PROFILE-1` B1, operator ruling O1 reversing DT-17).** The poller below is retired.
> *Watch this folder* is now the locked preset of *Keep this up to date*: `PUT /datasets/{id}/watch {watch: true}`
> rewrites the import's own Pipeline through `DatasetFeed.rewritePipeline` (approval policy over Pipelines 409, save gate,
> history) to `trigger: {type: schedule, every: 30s}` with `collector.stability {window: 30s, size_checks: 2}` on its
> `local` Collector, and keeps `watchInbox: true` on the entry; off writes `trigger: manual` and removes that stability
> block. The engine's poll cycle runs it: the import's pre-run steps judge the inbox and keep new columns
> (`ImportPreRunSteps`), the per-file stability gate decides what is read (O1: per file, no longer the whole inbox),
> the commit steps compact, follow on and check Expectations (`ImportCommitSteps`). `watched` = the entry flag AND
> the preset; the feed keeps its shape (`watch`, `watchable` true while watched, `feed.kind: uploads`, `watchError`
> after a failed scheduled run): U4-Watch (2026-11-19) kept it, since the switch is a run switch over server state, not
> Collector authoring, and the preset's cadence and stability are fixed, so nothing of the Collector is shown. On
> Standard the card still offers *Keep this up to date* while watched, and saving it replaces the watch with an
> ordinary feed (`WATCH-KEEP-TWO-CONTROLS-1`). The run waits while `data.self_import` is off, as
> before. A watch survives a restart because it is the Pipeline. An entry switched on before B1 (`watchInbox: true`
> over a manual Pipeline) is moved onto the preset when the control plane starts (`InboxWatch.migrate`, through the
> same write path; under an approval policy over Pipelines it is logged and reads as off). *Stop the feed*
> (`DELETE /datasets/{id}/feed`) turns the watch off too. The 30 s holds on any host: the scheduler wakes for each
> Pipeline's own `every` (operator 2026-11-19, *honour each every*), not only on the Space's poll tick. A run that
> sets aside a file after its pre-run step kept a column for it takes the column back. **After the run (B3,
> `BASIC-WATCH-ARRIVALS-1`, 2026-11-20)** the pre-run step's after-hook writes the arrivals sidecar lines of every
> engine run of an import, as Run now does (`Job.recordArrival`: one `sender: system` line per file the run added to
> the per-file ledger, with the follow-on's index outcome, which `ImportCommitSteps` hands to the run that holds the
> claim, and the columns the run kept, none when they were taken back), and for a watched inbox sets `watchError`
> after a run that took a file: `InboxWatch.SERVER_FAULT` when the run failed, `'<file>' was set aside (<reason>)`
> (`InboxWatch.setAside`, the engine's quarantine reason, server paths taken out) when it set a file aside, cleared
> when it set none aside. The ledger rows and the quarantine entries are read on disk (`FileStatusStore`), so it holds
> under the database status store too. A run that finds nothing to take neither reads them nor touches `watchError`:
> it stays until the next run that takes a file (before B3 the 30 s run that found nothing cleared it at once). Not
> written for a scheduled run: `rowsFilteredOut` (Run now counts it with a pre-scan before the run), so the arrivals
> list says `rowsLeftOut: null` (not known) for such a file of an import with a row filter. See
> [spec-pipeline-execution.md](../elt/spec-pipeline-execution-run-steps.md#pre-run-steps--the-other-side-of-the-run).

| Route | What |
|---|---|
| `PUT /datasets/{id}/watch` | `{watch: true \| false}`; `canUploadData`; gates as the feed (`DatasetFeed.gate`); 422 not a boolean, 422 a Reference (a watched inbox adds rows), 409 a Pipeline already on a schedule (*Keep this up to date*), 409 an approval policy over Datasets; answers the feed read |

- **Every edition, off by default, one interval.** The toggle writes `watchInbox: true` on the Dataset entry (removed
  when off); the import Pipeline stays `trigger: manual`, so the scheduler never runs it. The feed read and the arrivals
  answer's `feed` carry `watch` (true only while the entry says so AND the trigger is manual) and, after a failed poll
  import, `watchError`: a refusal's own words with the server's paths taken out (`ServerFaults.withoutServerPaths`),
  or for anything else a fixed phrase - exception text never reaches the wire (ERR-5XX-ROUTE-BODIES); the detail is
  logged. The feed read also carries `watchable` (not a Reference, trigger manual): the UI offers the toggle on it.
- **One poller.** A single daemon thread (`inbox-watch`), fixed delay 30 s, polls every registered `InboxWatch.PollTarget`
  in turn under its Space's MDC (`tick()` is synchronized). The watched list is in memory: the toggle registers or
  removes a poll target, and each ControlApi's Spaces are read for `watchInbox` entries on the first tick after it starts
  (`attach`, from `OnboardRoutes.register`; its poll targets go on close).
- **A Postgres snapshot is never polled (operator 2026-11-05).** Its 30 s refresh target (`InboxWatch.Snapshot`) is
  removed: the toggle refuses a snapshot (422), `watched()` and `watchable` are false for one, and the inbox target
  skips it. *Refresh now* exports the table to `<data root>/_onboard/a<uuid>/` and moves the complete file into the
  inbox, where the replace path takes it like an upload
  ([`api-onboarding-authoring.md`](../processor/api-onboarding-authoring.md), *Export, then move*). The Dataset card's
  feed shows "The table is copied again only when you press Refresh now" in place of the toggle.
- **The same path as Run now.** `OnboardImport.runNow`'s body is now `importWaiting(Space, id, content)`, called by
  Run now and by the poll alike: columns checked by name, a refused file moved to `<id>/refused/` with its reason and a
  REFUSED arrival, the run, the follow-on (the link index, in the run in every edition), the arrivals. The poll's actor is
  `inbox-watch`. A 409 because a person's Run now or upload holds the import is skipped silently (the next tick looks
  again); any other failure is kept as `watchError` and logged. The daemon's start lock is not the class monitor
  `tick()` holds, so a toggle never waits for a running import.
- **No half-written file.** A run starts only when at least one file the run would take is waiting (files already
  imported under the same name are ignored) and EVERY such file is unmodified for 30 s and was seen at the same size on
  two polls - the numbers a folder feed writes into its Pipeline (`stability {window: 30s, size_checks: 2}`). Not the
  `StabilityGate` class: it forgets a file once it releases it, and the run takes the whole inbox, so the poll must
  see all of it settled at once (with the gate, two files settling on alternate ticks would never run).
- **The run takes only the checked files** (`BASIC-ONBOARD-SETTLE-1`, 2026-11-05). The poll hands the files it just
  checked to `importWaiting(…, only)`: the columns check judges only those, and the run is narrowed to them by
  `com.gamma.inspector.RunFiles.only(inbox, files)`, a scope keyed by the inbox folder's real path (not the Pipeline
  name, so two Spaces never narrow each other) that `CollectorProcessor.collect` applies on the run path only (a
  pending count still sees the whole inbox). A file created between the check and the run stays in the inbox and is
  checked by later polls. Run now passes no set: it takes the whole inbox, as before. Not covered: a file already
  checked that is rewritten in that instant is still taken (the scope matches by path, since the columns check may
  rewrite a file it accepts). Its other half, `RunFiles.complete`, lets a person's *Add a file* skip a feed's
  stability window (`ADD-FILE-FEED-1`, §6.18).
- **The poll re-checks on every look** that the Space exists, Add data is on (else it waits), the entry still says
  watch, is not a Reference (the key can be set through another door) and is still an import Add data wrote (`OnboardImport.requireOwnImport`, split out of `importDataset`), else
  the poll target is dropped.
- **`watchInbox` through the generic door** (`PUT /components/dataset/{id}`, `canAuthorWorkbench`) is not refused. For
  an inbox that is no wider than the toggle: a builder can already put the import Pipeline on a schedule. For a
  Postgres snapshot it is: a builder may turn on the 30 s refresh, which Run now (`canUploadData`) otherwise does. The
  operator accepted this as a bounded widening (2026-11-05): the same stored connection and table, and an import can
  no longer plant `_snapshot.toon` (`ImportPaths.REFUSED_SUFFIXES`). Recorded as the `PUT /watch` ACTS reason in
  `ImportCapabilityGuardTest`.
- ⚠ **Superseded the same day (operator, 2026-11-05): a snapshot is never polled.** *Refresh now* exports the table and
  moves the finished file into the inbox; `InboxWatch.Snapshot`, `PostgresSnapshot.poll` and the card's 30 s toggle
  were removed (`InboxWatch.watched` excludes `importSource: postgres`). The bullet below records the first design, which no longer exists.
- ~~**A Postgres table snapshot (P3) refreshes on the same toggle.**~~ For an entry with `importSource: postgres` the toggle
  registers `InboxWatch.Snapshot` instead: each poll runs `PostgresSnapshot.poll` (copy the table again; replace
  everything only when the bytes differ from the last arrival; no settle check - the server makes the copy itself). A
  snapshot is never inbox-polled; a Reference snapshot may refresh (it replaces). The feed read says `snapshot`; the
  card mounts the feed component in snapshot mode (*Refresh every 30 seconds*, no inbox); Data status says *Refreshed
  from its table every 30 seconds*. Test: `ControlApiPostgresSnapshotTest#theThirtySecondRefreshCopiesTheTableOnlyWhenItChanged`
  (real Postgres).
- **Cost (P0 V3, measured):** under 1 % of one core at 1,000 empty watched inboxes; files left waiting are the driver.
- **UI:** `dataset-feed.component.ts` - a *Watch this folder* slide toggle in the Inbox block when the feed is
  `watchable`; the inbox text says it is checked every 30 seconds; a refusal flips the toggle back and says why; a
  failed poll import shows as a warning. Data status (`feedSummaryLine`): *Inbox watched, checked every 30 seconds ·
  N files waiting · the last import failed: …*.
- Tests: `ControlApiInboxWatchTest` (real HTTP; since B1 the engine's poll cycle is driven directly
  (`ControlApiImportPreRunStepsTest.poll`): the preset written and removed, a settled import with a refused file set
  aside and the follow-on and Expectation after, a file still being written waiting alone, a file that settles after
  the pre-run check, off and a restart, a kept column taken back after a run that did not succeed or that set the file
  aside (with `watchError` naming the file), Add data off, *Stop the feed*, the startup migration, refusals, armed
  401/403/200; B3: a scheduled run's arrivals line with its index outcome and kept column, and a file set aside said
  in `watchError` and kept until a run that takes a file, each under the file and the database status stores),
  `dataset-feed.component.spec.ts` (toggle, refusal, schedule hides it, the Data status words; since U4-Watch both bodies
  the switch sends, `{"watch":true}` and `{"watch":false}`, pinned byte for byte, and B1's watched answer switched off).
