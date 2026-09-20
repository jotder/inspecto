# PIPELINE-DRYRUN-1 — whole-pipeline dry run design

Status: **partially shipped 2026-09-18** — gates 1-4 (acquisition-phase post-action skip, execution-phase dry
run, both signal-emit sites, the provenance marker) are all built and unit-tested, and gate 1 now has an
operator-facing trigger (`POST /runs/{name}/trigger?skipPostAction=true`, `CollectorService.runPipeline`/
`triggerRunAsync`/`runPipelineOffThread`, same-day follow-up). See `docs/BACKLOG.md`'s `PIPELINE-DRYRUN-1`
row (2026-09-18 block) for the full job-flow finding and the correction to this doc's `GET /provenance` claim
(it reads `DbProvenanceStore`, not `DbConsignmentOutputStore`). Does **not** yet close `PIPELINE-DRYRUN-1` —
do not archive this file until a single combined "dry-run this whole pipeline" route (both acquisition and
execution phases under one flag) lands or is explicitly descoped by the operator.

## Decisions (operator-confirmed 2026-09-18)

1. **Scope: full.** Acquisition (source fetch + land-then-ack) *and* graph execution are both in
   scope — the row's hazard (a), "land-then-ack must not fire", only matters if acquisition is covered.
2. **Mechanism: thread the existing `JobContext.dryRun()` flag to every known mutating surface**, not a
   unifying refactor. Same discipline as `DryRunServices`'s own stated failure mode: every mutating
   surface must be substituted or `dryRun()` becomes a lie. A fifth mutating surface added later without
   the same discipline silently breaks the guarantee — grep for `DatasetWriteSignal.emit` and any new
   `CollectorConnector.post` implementor when reviewing future PRs that touch acquisition/execution.
3. **Provenance: write a marked "would-have" row.** A dry run should be visible in the existing
   Lineage/Sankey overlay (`GET /provenance`), clearly tagged as simulated, rather than invisible or
   requiring a separate report surface.

## Why no single interception point exists

`PipelineExecutor.dryRun()` (T18) already exists but is scoped to previewing the transform→sink *shape*
over an already-materialized sample — it doesn't touch acquisition, doesn't run a real batch/coordinator,
and isn't wired to a full job run today. A real run is driven by two separate code paths with no common
ancestor:

- **Acquisition**: `CollectorProcessor` → `RemoteAcquisitionHandler.applyPostAction` (`:319-330`) →
  `connector.post(rf, action)` (`:323`) — the land-then-ack deletion. One call site; every
  `CollectorConnector` (S3/SFTP/FTP/Azure/GCS/Kafka/DbExport) implements `post(...)` but is reached only
  through this one site.
- **Graph execution**: `PipelineJobRunner.run(ctx)` → `PipelineExecutor.execute(...)` with a real
  `SinkWriter`/`BranchCommitCoordinator`/`sourceFinalize` (`PipelineJobRunner.java:352`).
- **Signal emission**: `DatasetWriteSignal.emit(...)` from `ConsignmentProcessJobType.java:350` and
  `MaterializeTask.java:145` — two independent sites. The shipped self-loop guard
  (`JobService.java:794`/`:840`) is scheduler-side trigger-matching, not a write-path gate — it does not
  protect a dry run's emit call.
- **Provenance ledger**: `DbConsignmentOutputStore.record(...)` (`:224`) — independent of the above.

`JobContext.dryRun()` (`JobContext.java:60`) already exists and is already consumed once, to wrap
`PlatformServices` in `DryRunServices` (`JobService.java:1303`, `PackTestHarness.java:183` — both line
numbers drifted from the original BACKLOG row's 1266/174). This design extends what already listens to
that flag rather than inventing a second one.

## What "acquisition and execution share one ctx.dryRun()" requires

✅ **ANSWERED 2026-09-19 — they are GENUINELY SEPARATE, and the honest answer is worse than "thread it".**
`CollectorProcessor` never references `JobContext` at all; execution mints a fresh `RunContext` per firing
(`JobService.java:1238`, flag set at `:1295`). The two phases meet only at `fireOnCommit`
(`JobService.java:818-823`), which builds `new Firing(Map.of(), commitPayload(event), false)` — **`dryRun`
is hardcoded `false` for every chained firing.**
🔴 **And the obvious fix does not work.** `PipelineJobRunner` returns early under dry run
(`:381`, "dry run: pipeline validated, nothing written") **before** it publishes the `ConsignmentEvent` at
`:391` — so an execution dry run emits no event and there is nothing downstream to inherit from. The real
exposure is the other direction: an ACQUISITION dry run (`POST /runs/{name}/trigger?skipPostAction=true`) lands
files without acking, and the chained job then processes them **for real**.
⛔ **There is no existing carrier.** `ConsignmentEvent` (`inspecto-etl/.../ConsignmentEvent.java:44-47`) is
a fixed-field `@PublicApi(since = "4.0.0")` record with no attribute map, and `LedgerEntry` likewise; the
flag must be ADDED. That means a component on a published record (34 construction sites — 4 in main, 30 in
test; the file's own pre-v3.7.0 back-compat constructor is the idiom to follow) plus deciding which of the
four publish sites is authoritative for "this batch was simulated".
⇒ **Re-size: this is NOT the S–M residual the board implies.** It is a published-API change plus a
four-site decision, and it should be taken as a plan step with that stated, not slipped in as wiring.

Original instruction, now discharged — confirm before implementing (first task below): does the SAME `JobContext`/trigger cover both
`CollectorProcessor`'s acquisition pass and `PipelineJobRunner`'s execution pass for one pipeline, or are
they genuinely separate Job types/triggers (e.g. a scheduled "acquire" poll vs. an on-demand "process"
run)? If separate, `dryRun` must be threaded explicitly from whichever caller holds `ctx` into the other's
constructor/call — do not assume a single ambient flag reaches both without checking.

## The four gates

1. **`RemoteAcquisitionHandler.applyPostAction`** — skip the `connector.post(...)` call under dry run;
   log what would have happened (mirror `DryRunServices`'s `log.info("dry run: would …")` style).
2. **`PipelineJobRunner` → `PipelineExecutor.execute(...)`** — under dry run, pass a no-op `SinkWriter`,
   a no-op `sourceFinalize`, and a `BranchCommitCoordinator` variant that never persists/commits. Do NOT
   reuse `PipelineExecutor.dryRun()` (T18) as-is — it's shaped for a bounded sample preview, not a full
   batch with a real `batchId`/coordinator lifecycle. Prefer no-op strategy objects passed into the
   existing `execute(...)` signature, which already takes these as explicit parameters — no new
   interception point needed there.
3. **The two `DatasetWriteSignal.emit(...)` sites** (`ConsignmentProcessJobType.java:350`,
   `MaterializeTask.java:145`) — gate directly on the resolved dry-run flag.
4. **`DbConsignmentOutputStore.record(...)`** — under dry run, write the row with a `simulated: true`
   (or equivalent) marker instead of skipping, so `GET /provenance` can render it distinctly. Confirm the
   provenance schema/UI can carry that marker before assuming it's a one-line change — it may need a
   column addition and a UI badge.

## Step 5 — the cross-phase flag (the residual, scoped 2026-09-19)

Gates 1-4 shipped. What remains is the one the row calls "no single route fires both phases", and it is
**not wiring** — it is a published-API change plus a decision. Scoped here rather than slipped into a
commit, because taking it as wiring is how it would ship half-done.

**The exposure, stated precisely.** `POST /runs/{name}/trigger?skipPostAction=true` puts ACQUISITION in dry run:
files land, `connector.post` is skipped, nothing is acked. The chained execution job then processes those
files **for real** — `fireOnCommit` builds `new Firing(Map.of(), commitPayload(event), false)`
(`JobService.java:818-823`), hardcoding `dryRun=false` for every `on_pipeline` firing. ⚠ So a user who
asks for a dry run today gets a real write on the execution side. That is the defect; "the phases don't
share a flag" is only its mechanism.

🔴 **The obvious fix is closed off.** Threading the flag through the event that already chains them
cannot work: `PipelineJobRunner` returns early under dry run (`:381`, *"dry run: pipeline validated,
nothing written"*) **before** it publishes the `ConsignmentEvent` at `:391`. An execution dry run emits no
event at all, so there is nothing for a downstream firing to inherit. Any design that starts "carry the
flag on the event" must first say which side publishes it and when.

**No carrier exists.** `ConsignmentEvent` (`inspecto-etl/.../ConsignmentEvent.java:44-47`) is a fixed-field
`@PublicApi(since = "4.0.0")` record — no attribute map; `LedgerEntry` likewise. The flag must be ADDED to
a published record with **34 construction sites (4 in main, 30 in test)**. ✅ The file's own pre-v3.7.0
back-compat constructor is the idiom: add the component, keep a delegating overload, and the 30 test sites
compile untouched.

**Owed decisions — ALL THREE ANSWERED by the operator, DECIDED 2026-09-20:**

1. ✅ **DECIDED — the authoritative publish site is `ConsignmentAuditWriter:178`.** It is the site the
   defective route actually reaches, and it fans to **both** the bus and the Signal ledger, so it covers
   both chaining paths at once (`JobService:822` `on_pipeline` and `JobService:847` `on_signal`).
   ⛔ **Rejected: `PipelineJobRunner:391`** — unreachable under dry run, because the runner returns early
   at `:381` before it ever publishes. ⛔ **Rejected: marking all four sites** — `EnrichJob:88` and
   `EnrichmentService:263` would then assert simulation about enrichment runs that never consulted the flag.
2. ✅ **DECIDED — an acquisition-only dry run does NOT publish a `ConsignmentEvent`.** The chain stops
   cleanly and the operator sees nothing downstream. ⛔ **Rejected: publish the event marked as a dry
   run** — every downstream consumer would then have to honour the flag or silently act for real (a
   fail-open shape), and it would overturn the written invariant at `JobService.java:947-949`
   (*"cron/event/signal fires are always real"*). This makes step 5 mostly `CollectorProcessor`-side.
3. ✅ **DECIDED — no version call needed** (already settled by the grounding pass). `ConsignmentEvent` is
   `@PublicApi(since = "4.0.0")` and is confirmed **absent from `v3.11.0`**, so the record may be amended
   freely; only a release-notes line is owed. (See the standing “@PublicApi marks INTENT, not exposure”
   finding.)

### Interim: the query parameter renamed to `?skipPostAction=true` — SHIPPED 2026-09-20

Approved and built ahead of the step-5 build, because the honest name is owed now and does not depend on it.
`POST /runs/{name}/trigger?skipPostAction=true` became **`?skipPostAction=true`**, and the v1 response body's
`dryRun` field became `skipPostAction`.

**Why.** The parameter's real, deliberate scope — as its own javadoc already stated — is *"acquisition still
fetches, but the source-side post-action never fires"*. It does **not** suppress the ingest write. The name
`dryRun` overpromised exactly the guarantee the step-5 defect above shows it cannot make, and the parameter
was **absent from `docs/api/openapi-v1.json` entirely**, so the name was all an API consumer had to go on.
The capability is unchanged: polling a `post_action=DELETE` pipeline without acking still works.

**Scope of the rename — verified before touching anything.** The renamed chain is
`RunRoutes.triggerPipeline:159` → `CollectorService.triggerRunAsync` / `runPipelineOffThread` /
`runPipeline` (boolean overloads) → `MultiCollectorProcessor.runAll(…, boolean)` →
`CollectorProcessor.run(…, boolean)` → `CollectorProcessor.acquire(cfg, boolean)` →
`RemoteAcquisitionHandler.materializeRemote/fetchOne/applyPostAction`. Every boolean-carrying overload on
that chain has **exactly one entry point, `RunRoutes:159`** — the internal names were renamed the whole way
down for the same honesty reason. ⛔ The job framework's unrelated `dryRun` (MNT-1: `JobContext.dryRun()`,
`JobRoutes:155`, `DryRunServices`, the `*Task` previews, `SpaceMigrator`, and the UI's `dryRunAuthored`)
shares **no** method on this chain and was deliberately left untouched — it is a genuine preview and keeps
the name. `docs/api/openapi-v1.json` now documents `skipPostAction` on the route, stating plainly that the
ingest write still happens for real.

**Then build, in this order:** (a) `ConsignmentEvent` gains `boolean dryRun` + back-compat overload;
(b) the authoritative publish site sets it from `ctx.dryRun()`; (c) `fireOnCommit` reads
`event.dryRun()` instead of the hardcoded `false`; (d) the end-to-end test below, which is the only thing
that proves the two phases actually agree.

⚠ **Size: M, not the S–M the board implies** — a published record, four sites to triage, one hardcoded
constant, and a test that must span both phases in one call.

### Step 5 grounding + decision brief (2026-09-20)

Every claim above was re-grounded against code. **Four facts held exactly; four were wrong or incomplete,
and one of the four makes the live defect materially worse than this section states.**

**Held.** `PipelineJobRunner` returns early under dry run at **`:381`** and publishes the `ConsignmentEvent`
at **`:391`** — exact, the early return really does precede the publish. `ConsignmentEvent`
(`inspecto-etl/src/main/java/com/gamma/etl/ConsignmentEvent.java`) is a fixed-field
`@PublicApi(since = "4.0.0")` record of ten components with no attribute map, and it does carry the
7-arg pre-v3.7.0 back-compat constructor named as the idiom. **The construction-site count is exactly
right: 34 — 4 in main, 30 in test** (`grep -rn "new ConsignmentEvent(" --include=*.java`, excluding
`target/`).

**Corrected — line drift.** The hardcoded literal is at **`JobService.java:822`**; the method
`fireOnCommit` spans **`:818-823`**. The `:815-821` this doc and the BACKLOG row carried pointed at the
method's javadoc, not its body. Fixed in both.

**Corrected — there are TWO hardcoded firings, not one.** `grep "new Firing("` over main sources returns
four sites:

| Site | Path | `dryRun` arg |
|---|---|---|
| `JobService.java:822` | `fireOnCommit` — the `on_pipeline` chain | **hardcoded `false`** |
| `JobService.java:847` | `onSignalEvent` — the `on_signal` chain | **hardcoded `false`** |
| `JobService.java:955` | `triggerRun(name, actor, args, dryRun)` — the manual `/jobs/{name}/trigger` surface | real, threaded |
| `JobService.java:1088` | `Firing.NONE` constant | `false` (not a firing decision) |

⚠ **`:847` matters here and was missed.** A dry-run ingest also emits the canonical
`pipeline.batch.committed` Signal onto the ledger — `CollectorProcessor.java:176` wires
`audit.setTerminalBatchSink(PipelineConsignmentSignal::emit)` **unconditionally** — so the `on_signal`
chain fires for real too. Fixing only `fireOnCommit` leaves half the chaining unprotected.
`ctx.dryRun(firing.dryRun())` is set once, at `JobService.java:1295`.

🔴 **Corrected, and this is the important one — the defect is NOT "the chained job writes for real". The
`?skipPostAction=true` run writes for real BY ITSELF, in-process, with no chained job configured at all.**
`CollectorProcessor.run(cfg, onCommit, dryRun)` (`:92-95`) calls `acquire(cfg, dryRun)` — dry-run-gated —
and then calls **`ingest(cfg, onCommit)` with no flag at all**. Ingest always parses, always writes
outputs, always writes the audit/commit-log rows, and always publishes the `ConsignmentEvent`. The route
chain is `RunRoutes.java:159` (`dryRun` query parse) → `:162`/`:167` →
`CollectorService.triggerRunAsync`/`runPipelineOffThread` → `runPipeline(name, dryRun)` (`:1671`) →
`MultiCollectorProcessor.runAll(List.of(p), 1, bus.sink(), dryRun)` (`:1681`). ⚠ **This is documented as
deliberate, not accidental** — `CollectorService.java:1667-1668` says in so many words *"Ingest still
commits: this closes the acquisition-phase gap only … not a preview of the ingest write"*, and
`CollectorProcessor.java:87-91` repeats it. ⇒ The flag's scope is *"do not ack the remote source"*, while
its name and the operator-facing docs promise *"dry run"*. **The chained-job hole is a second instance of
the same lie, downstream of the first — not the defect itself.**

**Corrected — "four publish sites" is imprecise, and the one this defect travels through is the fourth,
not the first.** Three of the four call `bus.publish` directly; the fourth constructs the event and fans it
to two consumers:

| # | Site | What it publishes / when | On the `?skipPostAction=true` path? |
|---|---|---|---|
| 1 | `PipelineJobRunner.java:391` | `bus.publish` of a `SUCCESS` event after a **graph-lane** batch commits. Unreachable under `ctx.dryRun()` — the `:381` early return precedes it. | ❌ no — different lane |
| 2 | `EnrichJob.java:88` | `bus.publish` of a `SUCCESS` event after a Stage-2 **enrichment Job** run. Never consults any dry-run flag. | ❌ |
| 3 | `EnrichmentService.java:263` | `bus.publish` of a `SUCCESS` event after an **event/schedule/CLI-triggered enrichment recompute**. Never consults a dry-run flag. | ❌ |
| 4 | `ConsignmentAuditWriter.java:178` | **Constructs** the event on every *terminal* batch (`SUCCESS` **and** `FAILED`) and hands it to `commitListener` (→ the bus) **and** `terminalBatchSink` (→ the `pipeline.batch.committed` Signal). Wired from `CollectorProcessor.java:168-176`. | ✅ **yes — this is the one** |

#### Decision (a) — which publish site is authoritative for "this batch was simulated"?

**Options.**
- **(a1) `ConsignmentAuditWriter:178` only.** Concretely: thread the dry-run flag into `ingest` →
  `ConsignmentAuditWriter`, and set `dryRun` on the event it builds. This is the **only** site the
  `?skipPostAction=true` route actually reaches, and it protects **both** chain paths at once (`commitListener`
  and `terminalBatchSink` receive the same instance), which is exactly what the `:847` finding demands.
  Left unprotected: nothing on this route. A future *graph-lane* dry run that somehow published would not
  be marked — but it cannot publish today (`:381`).
- **(a2) `PipelineJobRunner:391` only** — what this doc previously implied. Concretely: **a no-op.** That
  site is unreachable under dry run, and it is not on the defective route. Choosing it fixes nothing and
  leaves the real exposure open.
- **(a3) All four "for symmetry".** Sites 2 and 3 never consult a dry-run flag, so the component could
  only ever be hardcoded `false` there — asserting "this was real" about runs nobody asked about. Harmless
  but noise; it does not buy the guarantee and it invites a later reader to believe enrichment honours a
  mode it does not.

⇒ **The evidence favours (a1) decisively.** The previous framing picked the wrong site because it reasoned
from "the execution lane is where writes happen" rather than from the route the defect is reported on.
Sites 2 and 3 should take `false` via the existing 7-arg back-compat constructor — no edit at all.

#### Decision (b) — does an acquisition-only dry run publish an event?

**What it does today, end to end:** fetch → land in inbox → **skip** the remote post-action (the only
thing the flag does) → **ingest for real** (parse, write outputs, write audit + commit-log rows) →
publish a `ConsignmentEvent` **indistinguishable from a real one** → emit `pipeline.batch.committed` →
`JobService.onConsignmentEvent` (`:788-808`) fires every Job with a matching `on_pipeline:` **immediately
and in-process**, each with `dryRun=false`, and `onSignalEvent` does the same for `on_signal:`. A
downstream Job is *not* required for real writes to occur; it only widens the blast radius.

- **(b-yes) publish, marked.** Downstream consumers still see the batch — the run is visible in the audit,
  provenance and the run-detail API — and each chained firing inherits `dryRun=true` and refuses to write.
  Cost: every consumer of `ConsignmentEvent` must now *honour* the flag, and today only the Job path could;
  `EnrichmentService`/`EnrichJob` subscribe to commits and would silently recompute for real unless they
  are gated too. ⚠ It also contradicts a written invariant: `JobService.java:945-949` states *"Only this
  manual path can request it — cron/event/signal fires are always real."* Choosing (b-yes) **changes that
  rule**, and the change should be recorded, not slipped in.
- **(b-no) do not publish.** The chain simply stops at the dry-run boundary; nothing downstream runs, and
  the operator sees the acquisition preview and nothing after it. Cost: the run is invisible to
  enrichment/observability consumers, and "what would have happened downstream" — the thing the feature is
  for — is not shown.

⇒ These are genuinely different products and this one is the operator's to call. **But note it is
currently moot in the worst way**: today the answer is "publish, unmarked, and everything downstream runs
for real" — the option nobody would choose. Whichever is picked, the work lands mostly in
`CollectorProcessor.ingest`/`ConsignmentAuditWriter`, **not** in `JobService` as this doc previously
guessed.

#### Decision (c) — does amending an `@PublicApi(since = "4.0.0")` record need a version call?

✅ **Answered by this repo's own written policy — no call is owed, and it is free.**
`docs/okf/backend/control-plane/api-stability.md` §"Release baseline" is explicit: the newest release on
`master`'s ancestry is **v3.11.0**; `since = "4.0.0"` means *"will become public API in 4.0.0"*, not *"has
been public since"*; and **an element whose `since` is `4.0.0` has never been published in any release, so
it may still be moved, renamed, or changed freely** — the stability promise binds *within a released
major*. The doc names the exact test and it passes here: `git ls-tree -r --name-only v3.11.0 | grep
ConsignmentEvent.java` returns **nothing** — the type did not exist in the last shipped release.

⚠ The same page records that the premise *"`@PublicApi` ⇒ breaking ⇒ bump"* has been **written down and
refuted three times** already (the Source→Collector rename, <!-- vocab-allow: names the Source→Collector rename itself --> the `ConsignmentProcessor` SPI widening, the
architecture plan's Phase C cycle cuts); this doc's owed-decision #3 was a fourth instance of the same
inherited assumption. ⇒ **Adding a `boolean dryRun` component is a plain additive change on an unreleased
type.** Even under the released-API rules it would be *additive* (minor). Trunk is already
`4.0.0-SNAPSHOT`, so it ships inside the pending MAJOR with no bump of its own. The only obligation is
**documentary**: add a line to the "Release notes — the pending MAJOR" draft in `api-stability.md` in the
same commit, per that page's own standing instruction.

#### The live defect's severity, and a decision-free interim mitigation

**Severity: high, and higher than the row states.** `?skipPostAction=true` today performs real, unrecoverable-in-
principle writes on the ingest half — every time, on every pipeline, with no chained Job needed. The one
thing it does protect (the remote post-action) is the single worst failure named in the BACKLOG row, so
the flag is not useless; but a caller reading "dryRun" reasonably expects no writes and gets a full
commit plus live downstream triggering. **This is the repo's own recorded failure mode — a flag that
silently does the opposite of what it says.** Mitigating factors: the behaviour is documented in the
javadoc at both `CollectorService.java:1667-1668` and `CollectorProcessor.java:87-91`, and ingest writes
are the pipeline's normal, idempotent-by-batch output rather than a destructive act.

✅ **A safe interim mitigation exists and needs none of the three decisions.** In `RunRoutes.triggerPipeline`
(`RunRoutes.java:158-167`), after parsing `dryRun` at `:159` and **before** dispatching at `:162`/`:167`,
refuse the request when `dryRun` is true — a **422** with a stable error code (e.g.
`ERR_DRYRUN_NOT_SUPPORTED`) whose message says the flag covers acquisition only, that ingest and any
chained execution still commit, and that whole-pipeline dry run is not yet available. ⚠ **Scope it to the
route, not to the service**: `CollectorService.runPipeline(name, true)` and the `CollectorProcessor`
overloads must keep working, because `RemoteAcquisitionStagingTest` pins them and gate 1 is genuinely
shipped underneath. This is one guard clause in one method plus one route test, touches no published type,
and pre-empts none of (a)/(b)/(c).

⚖ **The one real cost, stated so it is not discovered later:** this *removes* the only operator-facing way
to reach gate 1, so a pipeline with `collector.post_action.on_success=DELETE` goes back to having no way to
poll without acking. ⇒ **The choice is between a flag that under-delivers loudly and one that
over-delivers silently**, and the alternative to refusing is renaming the parameter to say what it does
(e.g. `skipPostAction=true`), which keeps the capability and stops the lie at the same cost. ⛔ Not
implemented — operator's call.

## Verification

- Unit/integration tests per gate (this repo's convention: unit-level per change, not the full reactor
  gate — see CLAUDE.md's "Model & effort routing").
- An end-to-end test that runs a real pipeline in dry-run mode against a source connector that would
  otherwise delete its remote file, and asserts: the remote file still exists, no sink table gained rows,
  no `dataset.write` signal fired (no downstream pipeline triggered), and a `simulated: true` provenance
  row exists for the run.
