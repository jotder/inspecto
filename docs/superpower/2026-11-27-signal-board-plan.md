# Signal Board — one clear meaning for Signal, and a board Jobs can trust (`SIGNAL-BOARD-1`)

Status: **APPROVED 2026-11-27 — D-SB1…D-SB5 all DECIDED (operator, 2026-11-27); SB-0 done 2026-11-27 (results §10;
the live stop test is still owed); the D-SB5 age limit is 24 hours; SB-1 designed (§11); SB-1a built 2026-11-27 (§11.6), SB-1b and SB-1c open.** Owner: this shift. Items: `module.event`, `module.engine`, `module.observability`, `module.config`.
Builds on the shipped Signal Backbone ([spec-signal-backbone](../okf/product/module/event/spec-signal-backbone.md));
it replaces none of it. SB-2 lands inside the link graph of `SPACE-COHERENCE-1`
([plan](2026-11-27-space-coherence-plan.md)).

## 1. Why

The word **Signal** has drifted. `docs/GLOSSARY.md` §8 says an Event is a *view* of a Signal. The code is the other way
round: an Event is the stored row, and a Signal is one kind of row. The operator's intent (2026-11-27) is a third,
clearer statement, and this plan makes it the binding one:

> A Signal is a loose kind of Event that is posted **for a Job to act on**, with the extra facts the Job needs. All the
> Signals of a Space form a shared message board, the **Signal Board**: Jobs post to it and Jobs react to it.

Most of the board is already built. What is missing is the definition, a list of what can be posted, a check that a Job
listens to something that is ever posted, and a view of the board itself.

## 2. Definitions (to become binding in `docs/GLOSSARY.md` §8)

| Term | Meaning | Who owns the type names |
|---|---|---|
| **Event** | A record that something happened, one row of the Space's ledger. Includes log lines and audit rows. Nobody is expected to act on it. | The engine (`EventType`, fixed names) |
| **Signal** | An Event posted for a Job to act on. It carries an envelope: subject, actor, cause, Space, schema version, and a payload the Job can read. | Open dotted names; core, modules, Job Packs |
| **Signal Type** | The template of a Signal: its dotted name, who posts it and which payload fields it carries. | The catalog (SB-1) |
| **Signal Board** | The Signals of one Space as Jobs see them: which types are posted, by what, and which Jobs listen. | Derived, never authored |

Every Signal is stored as an Event, so there stays one ledger and one audit chain per Space. Not every Event is a
Signal. Alert and Notification remain consumers of the ledger. Agents may read the board (they already do); the board
is designed for Jobs.

**D-SB1 (DECIDED, operator, 2026-11-27):** the board is for Jobs, and a Signal is stateless. It is never claimed or
closed. The state of the work belongs to what the Signal starts (a Job Run, an Incident, a Case), linked back by
`causationId`. A Signal is never used up: every Job that listens to its type gets it, each on its own, and one Job
reacting takes nothing from another (operator, 2026-11-27).

**How a Job hears a Signal.** A Job is a definition, not a running thread, and it never looks at the board. The Job
service of the Space is the one listener: it subscribes to the Space's ledger once, the ledger calls it as each Signal
is stored, and it starts a Run for every Job that matches. Listening costs one comparison per posted Signal.

## 3. What exists (read on `origin/master` `9d71c7357`, 2026-11-27)

- **Envelope.** `Signal` is a 13-field record that round-trips onto an `Event` of type `SIGNAL` without loss.
- **Posting.** A Step or a Job calls `signals().emit(type, severity, payload)`; the Signal lands on the ledger of the
  Space the thread runs in. A Step may not post in the `exchange.*` namespace. A dry run logs and posts nothing.
- **Reacting.** `JobService.onSignalEvent` fires every enabled Job whose `on_signal` matches the type (exact, or
  `prefix.*`) and whose `when` guard passes. It already has: a self-loop guard, one folded follow-up Run per Job under
  a storm (`coalesce`), a bound on queued Runs (`job.signal.refused`), a chain-depth cut (`job.chain.cut`), the
  causation link, and dry-run inheritance. `bind` reads `$signal.<field>` from the payload.
- **Use.** 88 Jobs in tracked Space and Template config use `on_signal`.
- **Reading.** `GET /signals`, `GET /signals/tree` (the causation forest) and a live stream. Agents read through the
  `signals_query` and `signal_timeline` tools.
- **Names.** 30 core Signal Types are constants in `SignalType`; seven modules keep their own constants class
  (`ReconSignals`, `BackupSignals`, `ScreeningSignals`, `ScoringSignals`, `CaseSignals`, `OpsSignals`,
  `AnomalySignals`). A Job Type declares the types it `emits`, and `JobTypeCatalog` flags a Job Type whose
  declaration disagrees with what it fires.
- **Fixed Event types.** `EventType` holds 58 constants.

## 4. Findings

| # | Finding | Evidence |
|---|---|---|
| G1 | The glossary states the relation backwards, so "Event" and "Signal" read as two names for one thing. | `GLOSSARY.md` §8 against `Signal.toEvent()` and `SignalType`'s own class comment |
| G2 | Some facts a Job could usefully react to are plain Events, so no Job can listen to them. **Counted in SB-0: 21 of the 58 fixed types; 6 more already have a Signal twin** (§10.3). | §10.3 |
| G3 | One fact is posted in several places. A committed Consignment is a `ConsignmentEvent` on its own bus, a `BATCH_COMMITTED` Event, a `pipeline.commit` Signal (a mirror) and a `pipeline.batch.committed` Signal. A Job has two keys to react to it: `on_pipeline` and `on_signal`. **SB-0 found four facts posted twice or more** (§10.3). | `JobService.mirrorPipelineCommit`, `ConsignmentAuditWriter`, `CollectorService` line 1403 |
| G4 | There is no one list of Signal Types. Names are spread over eight constants classes and the Job Type declarations, and no type declares its payload fields. | `SignalType` class comment; the seven module classes |
| G5 | Nothing checks a Job's `on_signal` against what is ever posted, or its `when` and `bind` against the payload. A mistyped type is a Job that never fires, with no finding. **Confirmed in SB-0** (§10.2). | `LinkCensus` declares `job.on_signal` and `job.when` with no check; `JobRoutes` never reads `on_signal` |
| G6 | A loop between Jobs is found only at run time, by the depth cut. The chain Job → type → Job is knowable at save time. | `cutChain` |
| G8 | Reaction is live-only. The Job service reacts as a Signal is posted, and the queued Run is held in memory. If the server stops after a Signal is stored and before its Run finishes, the reaction is not retried at boot. **Confirmed from the code in SB-0, not by a live stop** (§10.1). | `onSignalEvent`, `catchUpMissedFires` (cron only), `skipUnstarted` |
| G9 | A Job file changed on disk (a hand edit, a `git pull`) is not applied until the Space restarts; the Space lock reports the change but nothing re-reads it. Only the API, a bundle import and a promotion register a Job live. **Found in SB-0** (§10.2). | `upsertJob` callers: `JobRoutes`, `BundleRoutes`, `SpacePromotionRoutes`; no watcher on `config/jobs` |
| G7 | Nobody can see the board. There is a ledger of what was posted, but no view of which types a Space posts, which Jobs listen, and which postings nobody heard. | No route or page |

## 5. Plan

**SB-0 Define and measure.** Docs and a read-only tool; no behaviour change. **Done 2026-11-27 except the live stop
test; results in §10.**
- ✅ Rewrite the `GLOSSARY.md` §8 entries with §2, add **Signal Type** and **Signal Board**, and correct the hub page
  that repeated the old sentence (`plan-observability-scope.md`, OPS-1).
- ✅ Classify the 58 fixed Event types: record only, or reactable. List every fact posted more than once.
- ✅ (from the code) Settle the items of §8, G8 first. ⏳ Still owed: the live stop test — stop a server between a
  stored Signal and its Run, boot it, and see whether the Job runs. It confirms G8; it does not change the plan.

**No restart, as a rule of this plan (operator, 2026-11-27).** Saving a Job or a Signal Type takes effect at once, with
no server or Space restart. SB-1 keeps it for Signal Types. G9 (a Job file changed on disk) is fixed in SB-2c.

**SB-2c Apply a Job file changed on disk.** When the Space lock finds a `config/jobs` file added, changed or removed
outside the server, the Job service applies it through the same path as the API save (`upsertJob` / `removeJob`),
after the same checks. A file that fails a check is not applied and stays a finding, so a half-written file never
replaces a working Job.

**SB-2b Catch-up at boot (only if SB-0 confirms G8).** The Job service keeps, per Job, a mark of the last Signal it
handled. At boot it reads the ledger from each Job's mark and starts the Runs that were missed, under the same guards
(`when`, folding, the queue bound, the chain cut). The mark is per Job, so several Jobs listening to one type catch up
independently. A Job saved for the first time starts at "now" and replays no history.

**SB-1 The Signal Type catalog.** One assembled list: type, who posts it (a Job Type, a Step, the engine), payload
fields with their types, the release it appeared in. It is built from what exists: `SignalType`, the module classes,
Job Type `emits`, loaded Job Packs. A module keeps owning its names; the catalog only gathers them.
- `GET /signals/types`, and the same list to the Job authoring form, so `on_signal` becomes a pick list.
- A parity test: a type posted in code and missing from the catalog fails the build.

**SB-2 Checks when a Job is saved.** `on_signal` names a catalogued type, or a prefix that matches at least one. Every
`$signal.<field>` in `when` and `bind` is a declared payload field. A chain of Jobs that closes on itself is a finding.
- `on_signal` becomes one declared link (Job → Signal Type) in the link graph of `SPACE-COHERENCE-1`, so impact
  analysis answers "which Jobs stop firing if this Job no longer posts this type".
- An `on_signal` type that nothing posts is a **warning for everyone, agents included** (D-SB3): a Job Pack or a later
  Job may post it, so the Job is saved and the finding names it. This is the one exception to "agents are refused"
  in D-CO1. The other findings of this phase (a `$signal.<field>` the type does not declare, a chain that closes on
  itself) follow D-CO1's roll-out: a warning for a person, a refusal for an agent.

**SB-3 The Signal Board view.** Per Space: each type, what posts it, which Jobs listen, when it was last posted, and
counts of Runs started, guards that said no, refusals and chain cuts. Two lists matter most: a type posted that no Job
hears, and a Job listening to a type nothing posts.
- `GET /signals/board`, a board tab on the Signal Ledger page, and the same read for agents.

**SB-4 One posting per fact.** Post each reactable Event of SB-0 as a Signal with a dotted name, and remove double
postings one at a time, each behind its own test. Old names keep working as aliases until no config uses them.

SB-0 and SB-1 are small. SB-2 and SB-3 are one lane each. SB-4 is the only phase that touches behaviour Jobs rely on,
so it goes last and one fact at a time.

## 6. Impact

- **Space config.** No file has to change. SB-2 may report existing Jobs; the count is an SB-0 output.
- **Storage.** None. The ledger, the audit chain and `Event` (about 500 callers) are untouched.
- **API.** Two new read routes. `openapi-v1.json` is regenerated with them.
- **Risk.** SB-4 can change how often a Job fires if a double posting is removed carelessly; hence one fact per change.

## 7. Decisions (all signed, operator, 2026-11-27)

| # | Decision | Decided |
|---|---|---|
| **D-SB1** ✅ | Who is the board for, and does a Signal carry state? | **Jobs; stateless.** Every Job that listens gets each Signal |
| **D-SB2** ✅ | Keep `on_pipeline` beside `on_signal`? | **Keep the key**, defined as short for `on_signal: pipeline.commit` on that Pipeline, on one code path; no config changes |
| **D-SB3** ✅ | An `on_signal` type nothing posts: warn or refuse? | **Warn everyone**, agents included (differs from the recommendation, which refused an agent); the finding names the type |
| **D-SB4** ✅ | May a Space declare its own Signal Types in config? | **Yes, in SB-1**, so a Space's own Jobs can post a type with declared fields |
| **D-SB5** ✅ | If G8 is confirmed: catch up at boot, and how far back? | **Yes (SB-2b), with an age limit of 24 hours** (operator, 2026-11-27), configurable per Space, so a long outage does not start a flood of stale Runs |

## 8. Not verified

- G8 by a live stop (the code is read, §10.1).
- Whether a Signal posted while a Space boots, before its Job service subscribes (`JobService.start`), can be missed.
- Whether a Job of a generic type (for example a SQL Job) can post a type of the author's choosing today.
- Where the Space's ledger file sits on disk under the data root.
- No build or test was run for this plan; it is a reading of the code.

## 9. Board

Filed 2026-11-27: `SIGNAL-BOARD-1` on `docs/BACKLOG.md` (P2, §3.5); this plan's row is in `docs/INDEX.md`.

## 10. SB-0 results (2026-11-27, read on `origin/master` `566072b62`)

### 10.1 G8: a reaction is not retried across a restart

- The Job service subscribes to the Space's ledger in `JobService.start` and reacts on the posting thread; each matched
  Job's Run goes to an in-memory worker pool. Nothing about a pending Run is written before it starts.
- **Planned stop:** a Run waiting for a slot is recorded SKIPPED ("the Space is shutting down", `skipUnstarted`); a
  running one is recorded CANCELLED. Both show in the Run history, and neither is run again at boot.
- **Crash:** a queued Run leaves no record at all.
- **Boot:** `catchUpMissedFires` replays only a cron Job with `catch_up: true` and a missed fire. Signal Jobs have no
  catch-up.
- So SB-2b is needed. **Age limit for D-SB5: 24 hours (confirmed, operator, 2026-11-27)**, the same as the default Run
  deadline ceiling (`job.deadlineCeilingSeconds` = 86400), configurable per Space. A missed Signal older than that is
  not replayed; the board (SB-3) lists it as missed.

### 10.2 Save checks and a Job edited on disk

- **Save:** `JobRoutes` never reads `on_signal`, and `LinkCensus` declares `job.on_signal` and `job.when` with no
  check (one of the 11 unchecked links of `SPACE-COHERENCE-1` §3). A mistyped type is saved silently. (G5)
- **Edited on disk:** only `JobRoutes`, `BundleRoutes` (import) and `SpacePromotionRoutes` call `upsertJob` /
  `removeJob`. No watcher re-reads `config/jobs`. The Space lock reports the change (`CONFIG_CHANGED_OUTSIDE`) but does
  not apply it, so the Job waits for a restart. (G9 → SB-2c)

### 10.3 The 58 fixed Event types

**Posted twice or more (4 facts, 6 Event types) — SB-4 picks one:**

| Fact | Event | Signal(s) |
|---|---|---|
| A Consignment committed | `BATCH_COMMITTED` | `pipeline.batch.committed`, `pipeline.commit` (mirror) — plus the `ConsignmentEvent` bus |
| A Consignment failed | `BATCH_FAILED` | `pipeline.batch.failed` |
| A Job Run started / failed | `JOB_STARTED`, `JOB_FAILED` | `job.run.started`, `job.run.failed` |
| An Alert fired / cleared | `ALERT_FIRED`, `ALERT_CLEARED` | `alert-rule.fired`, `alert-rule.cleared` |

The built-in Notification rules read the Event side (`BATCH_FAILED`, `JOB_FAILED`), so SB-4 moves those readers before
it removes a posting. The Alert Signal names are not in `SignalType` (more evidence for G4).

**Reactable, no Signal today (21) — SB-4 candidates:**

| Area | Event types |
|---|---|
| Pipeline | `PIPELINE_PAUSED`, `PIPELINE_RESUMED`, `PIPELINE_CONSERVATION_IMBALANCE` |
| Collection | `FILE_QUARANTINED`, `FILE_FETCH_FAILED`, `SOURCE_CIRCUIT_OPEN`, `SEQUENCE_GAP` |
| Config | `CONFIG_LINK_BROKEN`, `CONFIG_CHANGED_OUTSIDE` |
| Storage | `STORE_DELETE_CONFLICT`, `STORE_DELETE_UNFENCED` |
| Quality and output | `EXPECTATION_FAILED`, `REPORT_READY` |
| Sharing | `EXCHANGE_REFRESHED` |
| Entities | `ENTITY_LIST_CHANGED`, `ENTITY_IDENTITY_CHANGED` |
| Work objects | `OBJECT_OPENED`, `OBJECT_SLA_BREACH`, `OBJECT_LINKED`, `OBJECT_ASSIGNED`, `OBJECT_ESCALATED` |

**Record only (31) — stay Events:** `LOG`, `AUDIT`, `SIGNAL` (the carrier row), `ACCESS_DENIED`,
`SECURITY_TRIGGERED`, `SERVICE_STARTED`, `LAYOUT_CONTRACT_VIOLATION`, `PIPELINE_REGISTERED`, `PIPELINE_UNREGISTERED`,
`PIPELINE_RENAMED`, `SCHEDULER_SETTINGS_CHANGED`, `FILE_RECEIVED`, `FILE_DISCOVERED`, `FILE_FETCHED`,
`FILE_VALIDATED`, `FILE_ARCHIVED`, `FILE_STABLE`, `FILE_CHANGED`, `DEDUP_RECORDS_DROPPED`,
`FLOW_CONSERVATION_IMBALANCE` (legacy name), `EXCHANGE_OFFERED`, `EXCHANGE_REQUESTED`, `EXCHANGE_GRANTED`,
`EXCHANGE_DENIED`, `EXCHANGE_REVOKED`, `LA_UPLOAD`, `GEO_PROJECTED`, `GEO_ROUTES_PROJECTED`, `OBJECT_ACTIVITY`,
`OBJECT_NOTE`, `REPORT_TRUNCATED`.

Per-file progress, security and audit rows are high in volume or sensitive, and no Job is meant to start from them.
The sort is a judgement on names and comments, not on use; SB-4 reconfirms each before it promotes one.

## 11. SB-1 design (2026-11-27)

**Decided (operator, 2026-11-27):** **D-SB6** each module declares its own Signal Types through a provider it ships
beside its constants class (core never names a module's types); **D-SB7** every Signal Type gets its payload fields
in SB-1, not only the ones Jobs listen to today.

### 11.1 Inventory (read on `origin/master` `b0008af9f`)

About 65 types, posted from four places:

| Poster | Types | Where the names live today |
|---|---|---|
| Core Jobs and the Job service | 30 | `SignalType` constants |
| Core engine, outside a Job | about 12: `pipeline.batch.committed`, `.failed`, `.parked`, `.retry_exhausted`, `pipeline.step.abandoned`, `job.run.step`, `alert-rule.fired`, `alert-rule.cleared`, `expectation.violated`, `decision-rule.applied`, `decision-rule.create-alert`, `consignment.counted` | string literals at the posting line |
| Modules | 8 in seven `*Signals` classes, plus `exchange.signal.delivered` / `.undeliverable` | the module's constants class |
| Agents | 10 `agent.*` types | string literals in `inspecto-agent` and `inspecto-intelligence` |

`exchange.<opco>.<type>` is a family whose middle part is chosen at run time; it is declared once as a prefix.

### 11.2 Shape (core, `com.gamma.signal`, public API)

- `SignalTypeDecl(type, title, poster, fields, since)`: `type` is a dotted name, or a `prefix.*` family;
  `poster` names what posts it (`job-type:<id>`, `engine`, `step`, `agent`, `exchange`).
- `SignalField(name, kind, always, description)`: `kind` is `string | integer | number | boolean | list | map`;
  `always` says the field is present on every posting (a `when` or `bind` on a field that is not `always` gets a
  warning in SB-2, never a refusal).
- `SignalTypeProvider`: `List<SignalTypeDecl> signalTypes()`, found by `ServiceLoader` like `JobTypeProvider`. Core
  ships `CoreSignalTypes`; each module ships one beside its constants class.
- `SignalTypeCatalog`: gathers every provider, plus the dotted `emits` of a Job Pack's Job Types (listed with
  "fields not declared"). The same type from two providers is an error at assembly.

### 11.3 Read and check

- `GET /signals/types` → each type with `title`, `poster`, `fields`, `since` and `source` (`core`, `module:<id>`,
  `pack:<id>`). It follows the new-route checklist (literal capability, armed tests, `openapi-v1.json` regenerated).
- **Checked on every post, in tests.** With `-Dsignals.checkFields=true` (set for every Surefire run), posting a Signal
  whose type is not catalogued, or whose payload has a key its type does not declare, or lacks an `always` field,
  fails the test that posted it. In production the same check is off. This keeps the fields true without a
  hand-kept list of who posts what: every existing test that posts a Signal becomes a check of its declaration.
- **A guard for names.** `tools/check-signal-types.mjs` refuses a dotted string literal passed straight to
  `new Signal(…)`, `emit(…)` or `emitSignal(…)` in main code unless that name is declared by some provider. It runs in
  the pre-commit set.

### 11.4 Slices

| Slice | Scope | Size |
|---|---|---|
| SB-1a | Core records, SPI, catalog, `CoreSignalTypes` (the 30 constants and the engine literals, with fields), `GET /signals/types`, the post-time check, the guard | one lane |
| SB-1b | A provider with fields in each module: anomaly, backup, case-management, ops, reconciliation, scoring, screening, exchange, agent and intelligence | one lane; each module's own tests run under the post-time check |
| SB-1c | The `on_signal` pick list in the Job form | small; UI |

The post-time check is switched on for a module only when that module's provider exists, so SB-1a can land before
SB-1b without failing module tests.

### 11.5 Found while designing

- **Nothing in config can post a Signal of the author's choosing today.** Only code posts; `ConsignmentProcessJobType`
  passes a type through, but its callers are code too. So D-SB4 (a Space declares its own types) also needs a way to
  post one from config, for example a Step or a Job parameter. That is a new decision, **D-SB8**, before SB-1c.
- Three score types (`risk.score.produced`, `anomaly.score.produced`, `churn.score.produced`) are core `SignalType`
  constants although their Jobs live in modules; SB-1b checks whether they move.
- **Correction (SB-1a, 2026-11-27): config CAN post a Signal of the author's choosing.** A Decision Rule's
  `emit-signal` consequence (`BuiltInConsequences.emitSignal`) takes its type from `params.type` (default
  `decision-rule.<rule>`) and its payload keys from `params.payload`. D-SB8 is still needed (a Job or Step cannot), but
  it must also decide how these authored types are declared. Until then the post-time check lets an uncatalogued type
  posted with `source.kind = decision-rule` through.

### 11.6 SB-1a as built (2026-11-27, lane off `origin/master` `c6a73d065`)

- **API** (`platform/inspecto-engine`, `com.gamma.signal`, `@PublicApi(since = "4.0.0")`): `SignalField(name, kind,
  always, description)` with `Kind` `string|integer|number|boolean|list|map`; `SignalTypeDecl(type, title, poster,
  fields, since)` (dotted name or `prefix.*` family; a repeated field or a bad name is refused); `SignalTypeProvider`
  (`signalTypes()` plus `source()`, `core` or `module:<id>`, found through `EntitledServices.load` like
  `JobTypeProvider`); `SignalTypeCatalog.assemble(providers, packEmits)`: a type from two providers is an
  `IllegalStateException`, a pack emit is listed with `fieldsDeclared: false` and never overrides a provider, `find`
  takes the exact name else the longest family. `chainDepth` is a framework stamp (`STAMPED`), never a type's field.
  `JobService.signalTypeCatalog()` adds the Space's loaded Job Packs' dotted `emits`.
- **`CoreSignalTypes`**: 52 types, fields read from each posting site: the 30 `SignalType` constants, the engine posts
  outside a Job (`pipeline.batch.committed|parked|failed|retry_exhausted`, `pipeline.step.abandoned`, `dataset.write`,
  `audit.write_failed`, `collector.delivery.anomaly`, `collector.file.problem`, `quality.schema_drift`,
  `alert-rule.fired|cleared`, `expectation.violated`, `decision-rule.applied|create-alert|render-widget|generate-report`)
  and the core Job Types whose types are not `SignalType` constants (`space.comparison.completed`,
  `kpi.snapshot.completed`, `expectation.evaluate.completed`, `space.test.completed|failed`). §11.1 was short: the six
  `*Signal` classes and the five Job-Type constants were not in it, and `job.run.step` / `consignment.counted` exist
  only in tests.
- **`GET /signals/types`** → `{types, count}`, each with `type, title, poster, fields, since, source, fieldsDeclared`.
  Gated on `canAuthorWorkbench` (the Job form's audience) although `/signals` and `/jobs/types` are ungated; manifest
  entry, core fragment, `openapi-v1.json` regenerated; `ControlApiSignalTypesTest` (401 / 403 / 200).
- **Post-time check** (`SignalPostCheck`): runs at the ledger write, `EventLog.emit`, through a test-only
  `EventLog.postCheck` hook that `Signal` installs when it loads with `-Dsignals.checkFields=true`; a Signal built and
  handed straight to a consumer (a test fixture) is not checked. A misfit throws `AssertionError`, so the posting sites
  that swallow a `RuntimeException` cannot hide it. ⚠ A Run posts on a worker thread whose `Future` nobody reads, so
  the throw alone vanished there (found by the verification pass: a misfit on `job.run.completed` showed only as
  "expected a job run within 10s"). Every misfit is therefore also recorded and printed to stderr, and
  `SignalPostCheckExtension` (engine test-jar, JUnit auto-detection, so the processor's tests get it too) fails the
  running test when one was recorded. Root pom: `signals.checkFields=false`, passed to Surefire;
  `inspecto-engine` and `inspecto-processor` set it `true`. Openings: a type a loaded pack declares passes on its name,
  and an uncatalogued type from a Decision Rule passes (D-SB8). Test-only types are declared by `TestSignalTypes`
  (engine test-jar, source `test`). Five tests posted real core types with payloads the real code never writes
  (`RunContextCausationTest`, `ControlApiConsignmentTrailTest`, `ControlApiDatasetFreshnessTest`,
  `ControlApiSignalsTest`, `ControlApiSignalsStreamTest`); their fixtures now carry the declared shape. No declaration
  was loosened to make a test pass.
- **Guard** `tools/check-signal-types.mjs` (+ fixture test), in the CI `guards` job: a dotted literal as the type of
  `new [com.gamma.signal.]Signal(id, "<t>", …)` (the id may be a call), `emit("<t>", Severity.X, …)` or
  `emitSignal("<t>", …)` in main code must be declared by a
  provider (as a literal, or as a `Class.CONSTANT` the provider names). `PENDING` (module literals SB-1b declares) is
  empty: no module posts a literal type today.
- **Board row text** (`docs/BACKLOG.md`): "SB-1a built 2026-11-27: the Signal Type catalog (`com.gamma.signal`), 52 core
  types with fields, `GET /signals/types`, the post-time check on in engine and processor tests, the name guard. Next:
  SB-1b (module providers, then the check on per module), SB-1c (Job form pick list); D-SB8 open."
