# Completeness KPI — K4, the reader that refuses (`KPI-K4-1`)

> **Status (2026-09-16): DESIGN ONLY — no code written, nothing shipped.** This is the design pass the
> §2 *Completeness KPI hold* row asks for. That row is the one gate in §2 with **nothing to run**, because
> — re-grounded twice — **its first action is engineering, not a query**.
>
> **What this document is for.** K1 (`DbConsignmentOutputStore.dailyVolume`), K2's analysis half
> (`FileSequenceGaps.analyze`) and K3 (`VolumeBaseline.assess`) all shipped and all have **zero production
> callers**. **K4 — the reader that turns a store into a completeness answer — was never built**, and K4 was
> to be the caller. So there is no analysis to "make refuse": the refusal must be **designed into K4 as it is
> written**. That is one action, not two, and it is the single constraint that shapes everything below.
>
> ⛔ **This plan does not lift the hold.** It makes the hold's first action buildable and names what an
> operator still has to decide (§7). The still-undecided sequence-template question (§7-a) blocks only the
> **file** half; the **volume** half (§8 slice A) is unblocked and is the smallest shippable slice.
>
> **Prior art, tier 3:** `archived-documents/plans-archive/completeness-kpi-plan.md` §2b is ARCHIVED —
> history only, never cited as current. The design of record is
> `okf/capabilities/observability/observability.md` §3.9, which carries K1's and K3's acceptance criteria.

---

## 1. Grounding — verified against today's code, 2026-09-16

Every claim inherited from the §2 / §3 board rows was re-checked. **Six confirmed, four have drifted or are
wrong**, and one of the corrections that the board itself applied is **itself wrong**.

### 1.1 Confirmed

| # | Fact | Evidence |
|---|---|---|
| 1 | **K4 was never built.** No `kpi.completeness` job type, no descriptor, no reader | no match for `kpi.completeness` under `inspecto*/src/main/java` |
| 2 | `FileSequenceGaps.analyze` is **pure, with no production caller** — only `FileSequenceGapsTest` | `inspecto-acquire/src/main/java/com/gamma/acquire/FileSequenceGaps.java:117` |
| 3 | `VolumeBaseline.assess` is **pure, with no production caller** — only `VolumeBaselineTest` | `inspecto-engine/src/main/java/com/gamma/consignment/VolumeBaseline.java:79` |
| 4 | `dailyVolume` is **already fail-closed and has no production caller** — throws on a blank producer, throws on a query failure, with the comment that an empty list is indistinguishable from a breach | `DbConsignmentOutputStore.java:305-307` and `:327-332` |
| 5 | ⚠ **`SeqScope` ALREADY SHIPS** — nested enum, `PER_BUCKET` / `CONTINUOUS`. "Needs `SeqScope`" is a **stale blocker**: the type exists, only the wiring does not | `FileSequenceGaps.java:74-79` (constants at `:76` / `:78`) |
| 6 | ⚠ **`EventType` is a class of `public static final String` constants, not an enum**, deliberately open per its own javadoc, with no `kpi.*` entry. "Do not grow the `EventType` enum" is wrong **in kind** | `inspecto-event/src/main/java/com/gamma/event/EventType.java:19`, javadoc `:5-6` |
| 7 | `ServiceStores.openFileStageStore` returns `null` at the default, and the default is pinned by a test | `ServiceStores.java:236-237`; `ServiceStoresDefaultsTest.java:67` |
| 8 | `AcquisitionLedgers` **never returns null** — it falls back to `InMemoryAcquisitionLedger`, so nullness cannot tell memory from durable | `AcquisitionLedgers.java:156,169` |
| 9 | ⛔ `ConservationCheck.imbalances` is the **cautionary precedent, not the seam**; `RowShaper`'s refusal is the shape to imitate | `RowShaper.java:373-377` (see §2.2) |

### 1.2 🔴 Refuted / drifted — correct these on the board

| # | The board says | Actually |
|---|---|---|
| R1 | `DbConsignmentOutputStore.dailyVolume` at **`:299`** | **`:304`.** `:299` is inside the javadoc. Blank-producer throw `:305-307`; query-failure throw `:331-332` |
| R2 | `ConservationCheck.imbalances` called from **`PipelineJobRunner:536`** | **`PipelineJobRunner.java:540`** |
| R3 | the acquisition ledger backend is `-Dacquire.ledger.backend` in **`AcquisitionLedgers.java:152`**, **"not an `OperationalDb.Family` member at all"** | 🔴 **The correction is itself wrong.** `OperationalDb.java:147` **does** declare `ACQUISITION_LEDGER("Acquisition ledger", "acquire.ledger.backend", "memory", Mode.DB_FLAG, …)`. Both statements are true at once: the `Family` entry exists **and** `AcquisitionLedgers` resolves the property independently with its own `System.getProperty` rather than through `OperationalDb.resolve()` — a **duplicated definition of one toggle**, which is the real finding. Also, `shared()` is at `:39`; `:152` is inside the `build(url, spaceId)` helper it delegates to |
| R4 | signal types land "as new `EventType` String constants" | 🔴 **Wrong in kind for the second time — see §5.** `kpi.completeness.*` is a **Signal type**, not an `Event.type`. `EventType.SIGNAL`'s own javadoc says a `Signal` persists as an Event of type `SIGNAL` and *its dotted signal-type rides in the attributes*. Putting a dotted type in `EventType` would mix two vocabularies |

**Confirmed exactly as written:** `FileSequenceGaps.java:117`, `FileSequenceGaps.java:74-79`,
`ServiceStores.java:237`, `OperationalDb.java:97`, `ServiceStoresDefaultsTest.java:67`,
`RowShaper.java:373`, `EventType.java:19`.

### 1.3 Two findings the board does not have yet

- 🔴 **Neither candidate filename store can be scanned over a window.** `DbFileStageStore` offers only the
  point lookup `stages(sourceId, relativePath)` (`DbFileStageStore.java:159`), and `AcquisitionLedger`
  offers only `find(sourceId, relativePath)` (`AcquisitionLedger.java:23`). K2's wiring therefore needs a
  **new read method**, not just a call — see §4.2. This is the concrete size of "the wiring half".
- ⚠ **`file_stages` is a best-effort index.** `DbFileStageStore.record` logs a write failure and never
  throws, on purpose ("an index beside the manifest"). A KPI built on it is complete only to the extent that
  index is — an honest caveat, and an operator call (§7-c).

---

## 2. The refusal, designed in

### 2.1 Why nullness is not the mechanism

The three stores K4 could lean on disagree about what absence looks like:

| Store | Family | Default | What a reader sees when it is not durable |
|---|---|---|---|
| `consignment_outputs` (K1's substrate) | `consignmentOutputs` | `duckdb` — **on** (`OperationalDb.java:95`) | `ConsignmentOutputStores.shared()` → `null` |
| `file_stages` (K2's substrate) | `fileStages` | `none` — **off** (`ServiceStores.java:237`, `OperationalDb.java:97`) | `openFileStageStore` → `null` |
| acquisition ledger | `acquisitionLedger` | `memory` (`OperationalDb.java:147`) | ⚠ **a working object** — `InMemoryAcquisitionLedger` |

⇒ Nullness answers for two of the three and lies about the third, and even where it answers it cannot say
**why** (toggle off? open failed?). **`StoreHealth.of(spaceId)` is the one place the resolved backend is
still known** (`inspecto-util/src/main/java/com/gamma/util/StoreHealth.java:112`), returning
`Map<String, Resolved>` where `Resolved(family, status, target, detail)` carries
`Status ∈ {UP, NOT_CONFIGURED, DEGRADED}` (`:54-72`). **So `StoreHealth.of` is K4's general mechanism, for
every family, including the two where nullness would have worked.** One rule, not three.

🔴 **Absence of an entry is the trap inside the mechanism.** `StoreHealth`'s own class javadoc:
*"A family with no entry was never opened, which is not the same as `NOT_CONFIGURED`… absence must never be
rendered as `UP`: nothing has been measured."* A reader that only checks `status != UP` passes a family
that was never opened at all. **K4 must treat an absent entry exactly as it treats `NOT_CONFIGURED`.**

### 2.2 The shape — imitate `RowShaper`, not `ConservationCheck`

`RowShaper.windowedDedup` refuses **before doing any work**, naming what was declared, what is missing, and
the exact `-D` toggle that caused it (`RowShaper.java:373-377`):

```
if (ctx == null || !ctx.hasLedger())
    throw new IllegalStateException("transform.dedup node '" + node.id() + "' declares scope: "
            + "window(...) but no ExecutionContext with a dedup ledger was supplied — …"
            + "(scratch/dry-run paths, or a space with -Ddedup.ledger.backend=none)");
```

⛔ `ConservationCheck.imbalances` (`PipelineJobRunner.java:540`) is the **opposite** pattern and must not be
conscripted: it is default-off behind `-Dprovenance.backend` and is silently absent on a stock deployment —
precisely the failure mode a completeness KPI exists to rule out.

### 2.3 The design

One private helper, called as K4's **first act**, before any store is opened or read:

```
/** Refuse unless `family` resolved to a durable backend in this space. Absence == not configured. */
private static void requireDurable(String spaceId, String family, String toggle, String forWhat) {
    StoreHealth.Resolved r = StoreHealth.of(spaceId).get(family);          // StoreHealth.java:112
    if (r != null && r.status() == StoreHealth.Status.UP) return;
    throw new IllegalStateException("kpi.completeness needs a durable '" + family + "' store to "
            + forWhat + ", but this space resolved " + describe(r) + " — a completeness KPI that "
            + "reads an absent or in-memory store reports 'nothing missing' for a pipeline that "
            + "received nothing, which is the one answer it exists to rule out. Configure -D"
            + toggle + " (and its .db.url) for this space, or remove this job.");
}
// describe(null) -> "no record that the store was ever opened"; otherwise
// "<status> (target: <target>) — <detail>" from Resolved.target()/detail().
```

| Aspect | Decision |
|---|---|
| **Call site** | `KpiCompletenessJob.run(JobContext)`, first statement after the space id is resolved. `consignmentOutputs` is **always** required; `fileStages` is required **only when the file half is configured** (a `sequence_template` is present) — a volume-only job must not demand a store it never reads |
| **Space id** | `SpaceRoot.id()` / `EventLog.currentSpaceId()` — ⛔ never the thread MDC as a substitute; `StoreHealth`'s javadoc records why the id is passed in |
| **What it throws** | `IllegalStateException`, unchecked, uncaught — the `RowShaper` voice. ⛔ **Not** a `JobResult.failed(...)`: a returned failure is a value a future caller can ignore, and K1's own comment already argues that a quiet non-answer is the defect |
| **What the operator sees** | `JobService` turns the thrown exception into a **FAILED run** plus a `job.run.failed` Signal at `Severity.CRITICAL` (`JobService.java:1303`), visible in the Jobs pane and the Signal Ledger. ⛔ **No `kpi.completeness.evaluated` signal is emitted and no zero is written anywhere** — there is no number to be mistaken for a measurement |
| **Dry run** | `ctx.dryRun()` (`JobContext.java:60`) is checked **after** the refusal, not before: a dry run that skips the store check would validate a configuration that cannot run |
| **Belt and braces** | `ConsignmentOutputStores.shared()` is still null-checked at the point of use, throwing the **same** message. Nullness detects; `StoreHealth` explains. Where nullness cannot detect (the acquisition ledger), `StoreHealth` is the only detector — which is why it, not nullness, is the stated rule |

**Verify gate** (the §3.9 K4 gate, made concrete): a space booted with
`-Dconsignment.outputs.backend=none` fails the `kpi.completeness` run with a message naming that exact
toggle; the run's signal ledger contains `job.run.failed` and **no** `kpi.completeness.*` entry.

---

## 3. K4's job wiring

Grounded on two existing job types: **`recon.run`** (`JobService.java:490-494`, implementation
`ReconRunJob.java`) — a cron'd job that emits a Signal and opens a **deduped** Incident on breach, which is
this job's exact shape — and **`alert.evaluate`** (`JobService.java:543-544`) for the fluent
`ParameterDecl` style.

### 3.1 Registration

Built-in, registered in the `JobService` constructor beside `recon.run`, via
`JobTypeProvider.of(descriptor, factory)` (`JobTypeProvider.java:13-46`):

```
registry.register(JobTypeProvider.of(new JobTypeDescriptor("kpi.completeness", "Completeness KPI",
        "Reads one Pipeline's received volume per record-day from the output registry and reports "
        + "how far a day sits below its rolling baseline; optionally counts missing files against a "
        + "sequence template.",
        PARAMS, List.of("kpi.completeness.evaluated", "kpi.completeness.breached"),
        List.of(), List.of("incidents")),
        c -> new KpiCompletenessJob(c, dataDir)));
```

- `JobTypeDescriptor(id, title, description, parameters, emits, artifacts, requires)` —
  `JobTypeDescriptor.java:15-17`. `requires: ["incidents"]` grants `IncidentAccess` through Platform
  Services (registered as `"incidents"` at `CollectorService.java:424`), which is the **current** idiom and
  is preferable to `ReconRunJob`'s post-construction `Supplier<ObjectAccess>` hack — that supplier is
  described in `ReconRunJob`'s own javadoc as a wiring workaround.
- ⛔ Built-in, **not** a `META-INF/services` pack: it reads core engine stores. The ServiceLoader path
  (`JobService.java:550-556`) is for optional modules.

### 3.2 `ParameterDecl`s

`ParameterDecl.required/optional/of(...)` — `ParameterDecl.java:62,66,74-117`.

| Name | Type | Req. | Default | Notes |
|---|---|---|---|---|
| `pipeline` | STRING | ✅ | — | the `producer` passed to `dailyVolume`. ⚠ **Required, not derived from `on_pipeline:`** — see §7-d |
| `record_day` | STRING | — | yesterday, in the job's zone | the day assessed; explicit for backfills |
| `baseline_window` | INT | — | `28` | → `VolumeBaseline.assess(window)` |
| `min_baseline_days` | INT | — | `7` | → `assess(minBaselineDays)`; below it the answer is `NO_BASELINE` |
| `tolerance` | NUMBER | — | `0.3` | fraction below baseline that is still ordinary |
| `sequence_template` | STRING | — | *(none)* | ⚠ **presence turns the file half on** and makes `fileStages` a refusal family. Where it comes from is **§7-a, undecided** |
| `seq_scope` | STRING, `options(PER_BUCKET, CONTINUOUS)` | conditionally ✅ | — | ⛔ **required whenever `sequence_template` is set.** `FileSequenceGaps` refuses to infer it and says why (`:67-73`); a default here would invent a gap at every bucket boundary or hide every real one |
| `collector` | STRING | conditionally ✅ | — | the `source_id` whose filenames are read; required with `sequence_template` |

### 3.3 Cron

Nothing job-type-specific. The schedule is a `JobConfig` field (`JobConfig.java:47`, TOON key
`cron: "0 2 * * *"` documented at `:22`), armed by `JobService.armCron` (`:605-612`) from `start()`
(`:583`). **One config per Pipeline** is the ordinary `*_job.toon` shape, as
`inspecto/examples/06-serve/pipeline-job/rollup_job.toon` shows:

```
job:
  name: cdr_completeness
  type: kpi.completeness
  cron: "0 2 * * *"
  on_pipeline: cdr_pipeline
  params:
    pipeline: cdr_pipeline
    sequence_template: "CDR_{yyyyMMddHH}_{seq}_*"
    seq_scope: PER_BUCKET
    collector: cdr_inbox
```

⛔ No fan-out machinery — the archived plan's decision, and still right: one job config per Pipeline.

### 3.4 Signal and Incident

- **Every run** emits `kpi.completeness.evaluated` via `ctx.signals().emit(type, severity, payload)`
  (`SignalEmitter.java:12`), `Severity.WARN` on `BREACH`, else `Severity.INFO` — exactly
  `ReconRunJob`'s `ctx.signals().emit("recon.run.completed", breaks > 0 ? WARN : INFO, payload)`.
- **On breach only**, a second `kpi.completeness.breached` at `Severity.WARN` carrying the same payload —
  the fact an Alert Rule watches without having to inspect a status field.
- **Incident**, deduped, through the declared grant:
  `ctx.services().get(IncidentAccess.class).openIncident(title, message, severity, scope, attrs, "pipeline")`
  — `IncidentAccess.java:41-43`. Dedup is **central**, not per-job: `IncidentAccess.over(...)` suppresses
  the call when an active `INCIDENT` in `scope` already carries the same value under `dedupeAttribute`
  (`IncidentAccess.java:53-67`). **`scope` = the pipeline id, `dedupeAttribute` = `"pipeline"`** ⇒ one open
  Incident per Pipeline across repeated runs, which is the §3.9 K4 gate verbatim. `DryRunServices.java:51`
  already supplies a no-op `IncidentAccess`, so a dry run opens nothing.
- ⛔ **Do not open an Incident for `NO_BASELINE` / `NO_OBSERVATION`.** They are *unknown*, not breached;
  three of `VolumeBaseline.Status`'s four values are not "healthy" and only `BREACH` is an incident
  (`VolumeBaseline.java:36-46`).

---

## 4. Wiring K1 / K2 / K3 into K4

All three are pure or store-local and take their inputs as arguments; K4 is the only thing that holds a
store. The order matters: **refuse first (§2.3), read second, assess third.**

### 4.1 K1 → K3, the volume half (no new engine code)

```
String to   = recordDay;                                  // the day assessed
String from = recordDay.minusDays(baselineWindow);        // the rolling window's far edge
List<DailyVolume> series =
        ConsignmentOutputStores.shared().dailyVolume(pipeline, from, to);      // K1, :304
VolumeBaseline.Assessment a =
        VolumeBaseline.assess(series, recordDay, baselineWindow, minBaselineDays, tolerance);  // K3, :79
```

- ⛔ **Pass `series` through untouched.** `assess` already ignores the null-`recordDay` bucket by contract
  and already treats an absent day as `NO_OBSERVATION` rather than zero. Pre-filtering in K4 would
  duplicate — and eventually contradict — a rule that is pinned by `VolumeBaselineTest`.
- ⛔ **Do not invent a calendar.** `dailyVolume`'s javadoc: days with no registered output are *absent from
  the result, not zero*, because only the caller knows which days were expected. K4 does **not** know
  either, so it adds none.

### 4.2 K2, the file half (needs one new store read)

```
List<String> names = fileStages.relativePaths(collector, from.minusOneBucket(), to.plusOneBucket());  // NEW
FileSequenceGaps.Report gaps =
        FileSequenceGaps.analyze(sequenceTemplate, names, windowFrom, windowTo, seqScope);   // K2, :117
```

- 🔴 **`relativePaths(...)` does not exist.** `DbFileStageStore` has only `stages(sourceId, relativePath)`
  (`:159`), and `AcquisitionLedger` only `find(...)` (`:23`). Adding a windowed read to `DbFileStageStore`
  — table `source_id, relative_path, batch_id, stage, recorded_at` (`DbFileStageStore.java:70-73`) — is
  the smaller change and keeps the acquisition ledger's point-lookup contract untouched.
- ⚠ **`recorded_at` is processing time, not the filename's time.** A file named for hour `H` can be
  recorded hours later, so the fetch window must be **padded by at least one bucket on each side** and the
  template matching left entirely to `analyze`, whose `Report.unmatched` counts the strays. ⛔ Never filter
  by parsing the filename in SQL — two parsers for one template is how they drift.
- The two structural limits stay limits and must not be "fixed": only **interior** holes are countable (a
  truncated tail is undetectable from names alone), and an empty bucket yields **buckets**, not a file
  count. `Report.missingFiles` / `Report.emptyBuckets` keep them apart (`FileSequenceGaps.java:90-105`).

---

## 5. `KPI-UNKNOWN-1` — UNKNOWN must survive store → signal → UI

**The rule, stated once and applied at every hop: a number that is not known is absent or JSON `null`.
Never `0`.**

| Hop | Representation |
|---|---|
| **Store** | `DailyVolume(recordDay = null, files, rows)` — the unknown-day bucket, sorted last, deliberately kept inside the range filter because it has no day to compare (`DbConsignmentOutputStore.java:284`, SQL at `:308-313`). Sinks with no partition or event time (Dataset and enrichment sinks) land here |
| **K4 result** | `record Completeness(String pipeline, String recordDay, VolumeBaseline.Status status, long files, long rows, Long baselineRows, Double deviation, int baselineDays, UnknownBucket unknown, FileSequenceGaps.Report gaps)` with `record UnknownBucket(long files, long rows)`, **`null` when the store returned no unknown bucket**. ⛔ Reuse `VolumeBaseline.Status` — do **not** add a parallel `UNKNOWN` constant beside it: `NO_BASELINE` and `NO_OBSERVATION` already *are* the unknowns, and a second enum saying the same thing differently is how the two drift |
| **Signal payload** | `status`, `files`, `rows`, `baselineDays`; `baselineRows` **omitted** (not `0`) when there is none; `deviation` JSON `null` (not `0`) wherever there is nothing to divide by — *including a baseline of exactly zero, where a shortfall is undefined, not −100 %* (`VolumeBaseline.java:52-56`); `unknownDayFiles` / `unknownDayRows` **present only when an unknown bucket exists**, ⛔ never folded into `files`/`rows` |
| **Event ledger** | one `EventType.SIGNAL` Event; the dotted type, severity and JSON payload ride in its attributes (`EventType.java`, `SIGNAL` javadoc) — no schema change, nothing to lose the distinction in |
| **UI** | `statusTone()` (`inspecto-ui/src/app/inspecto/components/status-badge.component.ts:30`) maps `BREACH → error`, and **leaves `NO_BASELINE` / `NO_OBSERVATION` to fall through to `neutral`, which is correct**. ⛔ **Do not add them to the `success` list** — grey is the honest colour for "not measured"; green would assert a measurement that was never taken. Render an em dash or the word *unknown* for a missing number, ⛔ never `0`, and show the unknown-day bucket as its own row, never summed into the day |

**Verify gate:** a Pipeline whose sink has null `bounds` produces a signal whose payload carries
`unknownDayRows > 0` and **no** `rows: 0` for the target day, and whose UI badge is neutral, not green.

---

## 6. Signal type naming

🔴 **The board's framing — "as new `EventType` String constants" — is wrong in kind, for the second time on
this row.** `EventType.java:19` is indeed a constants class and not an enum (the first correction was
right). But its vocabulary is **`Event.type`**: `UPPER_SNAKE` values like `LOG`, `BATCH_COMMITTED`,
`ALERT_FIRED`. `kpi.completeness.evaluated` is a **Signal type**, a different namespace —
`EventType.SIGNAL`'s own javadoc says a `Signal` persists as an Event *of type `SIGNAL`*, with "its dotted
signal-type, severity and JSON payload … in the attributes". Adding a dotted value to `EventType` would
mix the two vocabularies in one class.

**Where dotted Signal types actually live today: nowhere.** They are bare literals scattered across the
engine — `"job.run.failed"` and `"job.run.rejected"` (`JobService.java:1213,1242,1303`),
`"recon.run.completed"` (`ReconRunJob.java`), `"alert.evaluate.completed"` (`AlertEvaluateJob.java:79`),
`"pipeline.batch.failed"` / `".parked"` (`PipelineConsignmentSignal.java:35`), re-listed as literals again
in `SchedulerAuditTask.java:74` and `SignalIngress.java`. There is **no** `SignalType` class in
`com.gamma.signal/` — the archived plan's warning ("a constants class should land before ~10 string
literals do") was already overtaken before it was written.

**Proposal:** `kpi.completeness.evaluated` and `kpi.completeness.breached` as literals in slice A (matching
every other job type, and declared in the descriptor's `emits:` list, which is the real contract the
`/jobs/types` route publishes), with a `com.gamma.signal.SignalType` constants class collapsing the
existing scattered literals as a **separate, non-blocking** slice (§8-C). ⛔ Not `EventType`. See §7-e —
this is an operator call, not a decision this document takes.

---

## 7. Open — operator calls, not answered here

⛔ **None of these is invented an answer below. They are queued, not resolved.**

- **(a) Where does the sequence template come from?** The Collector's existing one, a job parameter, or the
  Collector's with a per-job override. **Still undecided since 2026-08-30** and it blocks the whole file
  half (§4.2) plus the `sequence_template` / `collector` parameters (§3.2). The volume half does not wait
  on it.
- **(b) Which durable home does the filename history get?** Flip `-Dfile.stages.backend` from `none` to
  `duckdb` (`ServiceStores.java:237`, `OperationalDb.java:97`), or leave the default alone and let K4
  refuse. **This design refuses** — it needs no default change to be correct — but flipping the default is
  the only thing that makes the file half work on a stock deployment, and it carries a write-volume cost.
- **(c) Is a best-effort index an acceptable KPI substrate?** `DbFileStageStore.record` logs write failures
  and never throws, by design. A missing-file count read from it is complete only to the extent that index
  is.
- **(d) Should `pipeline` default from `on_pipeline:`?** They would be the same string in every realistic
  config, and §3.2 makes `pipeline` required rather than deriving it silently. `dailyVolume` refuses a
  blank producer for the same reason (`:305-307`).
- **(e) `SignalType` constants class now, or literals now and the class later?** See §6. Either way
  ⛔ not `EventType`.
- **(f) Defaults for `baseline_window` / `min_baseline_days` / `tolerance`** — 28 / 7 / 0.3 are proposals
  only. The archived plan calls a wrong baseline window "visible and harmless to change", so a default is
  fine here — but it is still the operator's number.
- **(g) One Incident or two when both halves breach?** A volume breach and a missing-file breach on the
  same Pipeline currently dedup onto the same `scope` + `"pipeline"` key (§3.4) and would collapse into one
  Incident. If they should triage separately, the dedupe attribute has to distinguish them.
- **(h) Severity for the unknowns.** `NO_BASELINE` / `NO_OBSERVATION` emit `.evaluated` at `INFO` in this
  design. If a Pipeline sitting at `NO_OBSERVATION` for a week should be noticed, that is a WARN — and a
  different rule (a streak), not a single-run status.
- **(i) Board hygiene.** §2 and §3 carry the drifted citations and the wrong `OperationalDb.Family` claim
  corrected in §1.2 (R1–R4). ⚠ The main thread owns `docs/BACKLOG.md`; this document does not edit it.

---

## 8. Steps — smallest shippable slice first

| # | Slice | Verify |
|---|---|---|
| **A** | **The volume half, whole.** `KpiCompletenessJob` + descriptor + `ParameterDecl`s + registration; `requireDurable` (§2.3) as its first act; K1 → K3 (§4.1); `.evaluated` / `.breached` signals; deduped Incident via `IncidentAccess`; **`KPI-UNKNOWN-1` carried end to end from day one** (§5). ⛔ UNKNOWN is *in* this slice — retrofitting a distinction is the exact mistake this row has already made twice | `-Dconsignment.outputs.backend=none` fails the run visibly and names the toggle · a store family with **no `StoreHealth` entry** also refuses · a halved day breaches and opens **exactly one** Incident across three runs · a null-`bounds` Pipeline's payload carries `unknownDayRows` and no `rows: 0` · a real cron arms |
| **B** | **The file half.** New `DbFileStageStore.relativePaths(sourceId, from, to)` with a padded window; `fileStages` added to the refusal families when `sequence_template` is set; K2 wiring (§4.2) | ⛔ **blocked on §7-a** · a window-edge silent hour is found · an interior hole is exact · `unmatched > 0` surfaces a wrong template instead of reading as "nothing missing" |
| **C** | `com.gamma.signal.SignalType` constants class, collapsing the ~10 scattered dotted literals (§6). Non-blocking, independent of A and B | every existing literal replaced; no behaviour change |
| **D** | UI surface for the KPI beyond the Signal Ledger — a tile that renders UNKNOWN as unknown | a neutral badge, an em dash where there is no number, the unknown-day bucket on its own row |

**Tests** (per the `test-author` skill): engine unit tests mirroring `com.gamma.job`, plus a real
`JobService` test that arms cron and drives the job — extending the pattern in
`inspecto/src/test/java/com/gamma/control/`. **Every JVM launch needs `--enable-native-access=ALL-UNNAMED`**
(DuckDB JNI); verify with `mvn -o clean test`, and with `-Pedition-enterprise` if anything shared moves.
🔴 **K1 is the whole risk** (§3.9) — K2–K4 are assembly over existing parts.

---

## 9. Vocabulary

Canonical per `docs/GLOSSARY.md`: **Pipeline** (⛔ never *Flow*), **Collector** (⛔ never *Source* for the
acquisition entity), **Incident** (⛔ never *Issue*), **Signal**, **Dataset**, **Measure** — and a
thresholded Measure is a **KPI**, which is what `kpi.completeness` is. ⚠ **Naming tension, stated rather
than hidden** (inherited, unchanged): the requirement is a KPI *not related with* Consignment and the
substrate is *named* `consignment_outputs`. The coupling is nominal — it is the ordinary per-output-file
registry on the normal write path. **If the name later matters, that is a rename, not a redesign.**
