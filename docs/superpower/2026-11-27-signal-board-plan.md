# Signal Board — one clear meaning for Signal, and a board Jobs can trust (`SIGNAL-BOARD-1`)

Status: **APPROVED 2026-11-27 — D-SB1…D-SB5 all DECIDED (operator, 2026-11-27); SB-0 done 2026-11-27 (results §10;
the live stop test is still owed); the D-SB5 age limit is 24 hours; SB-1 designed (§11); SB-1a built 2026-11-27 (§11.6), SB-1b built 2026-11-27 (§11.7), SB-1c built 2026-11-27 (§11.8): SB-1 complete; SB-2c built 2026-11-28 (§12), SB-2b built 2026-11-28 (§13).** Owner: this shift. Items: `module.event`, `module.engine`, `module.observability`, `module.config`.
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
no server or Space restart. SB-1 keeps it for Signal Types. G9 (a Job file changed on disk) is fixed in SB-2c (built 2026-11-28, §12).

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
  *(SB-1c shipped without it, 2026-11-27: the pick list keeps free text, so a Space-declared type can still be typed;
  see §11.8. D-SB8 stays open.)*
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

### 11.7 SB-1b as built (2026-11-27, lane off `origin/master` `6b1c0a0cd`)

- **Eight module providers**, each beside its constants class and listed in the module's
  `META-INF/services/com.gamma.signal.SignalTypeProvider`, fields read from each posting site:
  `AgentSignalTypes` (7 `agent.*` types posted by `EventLogAuditSink`; §11.1 said 10, but `inspecto-intelligence` only
  reads Signals), `BackupSignalTypes` (3), `CaseSignalTypes` (1), `ExchangeSignalTypes` (`exchange.*` plus
  `exchange.signal.delivered|undeliverable`), `InsightSignalTypes` (`insight.scan.completed`, `insight.found`; insights
  was missing from §11.4's list), `OpsSignalTypes` (1, two payload shapes), `ReconSignalTypes` (1),
  `ScreeningSignalTypes` (1). `inspecto-geo-link` and `inspecto-intelligence` post nothing.
- **Score types stay in core** (§11.5): `CollectorService` matches all three to release deferred Alert Rules, so they are
  a core contract; `ScoringSignals` / `AnomalySignals` keep referencing the `SignalType` constants.
- **Core change**: a family that declares no fields (`exchange.*`, whose payload is whatever keys the offer shares) is
  listed with `fieldsDeclared: false` and checked on its name only. The rule is generic; core names no module type.
- **Check on** (`signals.checkFields=true` in the pom) for the eleven modules that post: agent, anomaly, backup,
  case-management, exchange, insights, ops, predictive, reconciliation, scoring, screening. All eleven get
  `SignalPostCheckExtension` through the engine test-jar; exchange gained that test dependency here, because its
  forwarder posts from inside an EventLog tap, which swallows every `Throwable` (the verification pass showed 22
  misfits printed and the build green without it).
- **Not seen by any test yet** (fields read from the code, but no test posts them to a ledger, so the check cannot
  catch them drifting): `maintenance.backup.completed`, `maintenance.backup.verify_failed`, `insight.found`.
  `agent.model.called` and `agent.human.decided` gained a test in `EventLogAuditSinkTest`.
- **Guard**: `PENDING` stays empty and its wording no longer names SB-1b.
- **Board row text**: "SB-1b built 2026-11-27: a Signal Type provider in each posting module (agent, backup,
  case-management, exchange, insights, ops, reconciliation, screening), the check on in the eleven posting modules'
  tests. Next: SB-1c (Job form pick list); D-SB8 open."

### 11.8 SB-1c as built (2026-11-27, lane off `origin/master` `6ccab0ff6`)

- **The Job form's On signal is a pick list**: `onSignal` in `job-attributes.ts` is `type: 'autocomplete'`, fed by
  `signalTypeOptionLoader()` (`inspecto/components/entity-option-loaders.ts`) over `EventsService.signalTypes()`
  (`GET /signals/types`, Space-scoped by the interceptor). The value is the bare type (a family stays `prefix.*`); the
  label adds the title, and the filter matches both, so "failed" finds `job.run.failed`, `pipeline.batch.failed`, ….
- **Free text stays valid**, as the house rule for autocomplete fields says: a glob, and a type no provider declares
  yet (a Space-declared type, D-SB8), are typed in. So SB-1c did not wait for D-SB8; once D-SB8 lands, Space-declared
  types appear in the list through the same route.
- A refusal (a person without `canAuthorWorkbench`) or an unreachable route degrades to no suggestions; the field still
  works.
- Shown in the preview against a backend built from this lane: 52 core types listed, the bare type stored on pick, a
  free-text glob accepted. Not done here: showing the chosen type's payload fields beside `when` / `bind`, which is
  SB-2 (the save check).
- **Board row text**: "SB-1c built 2026-11-27: the Job form's On signal suggests the catalogued Signal Types, free text
  kept. SB-1 is complete. Next: SB-2 (a saved Job is checked against the catalog); D-SB8 open."

### 11.9 SB-2 as built (2026-11-28, lane off `origin/master` `d9a88975e`)

- **The check** (`platform/inspecto-engine`, `com.gamma.job.JobSignalCheck`, pure): a Job with `on_signal` gets four
  kinds of finding. `SIGNAL_TYPE_UNPOSTED`: the value matches no catalogued type (an exact name, matched
  case-insensitively as the dispatcher does; a `prefix.*` that overlaps no type or family, matched case-sensitively as
  the dispatcher does). An exact type the catalog lists itself is checked against that entry's fields only, never a
  covering family's, as `SignalTypeCatalog.find` resolves it. `SIGNAL_FIELD_UNDECLARED`:
  a `$signal.<field>` in `when` or in a `bind` value whose first path segment no matched type declares (`chainDepth`,
  the framework stamp, always passes; for a `prefix.*` a field any matched type declares passes); the fix lists the
  declared fields. `SIGNAL_FIELDS_NOT_DECLARED` (INFO): a matched type whose fields are not declared (a Job Pack's
  emit, `exchange.*`), so the fields cannot be checked; never a refusal. `JOB_CHAIN_LOOP`: saving the Job closes a
  chain on itself, named as `a → b → a`.
- **What a Job posts, for the chain**: `job.run.started|completed|failed|rejected` (every Run) plus its Job Type's
  `emits`. A Job is never fired by its own Signal (the dispatcher's self-loop guard), so a loop needs two Jobs or more.
  A Job with a `when` guard is not a link of the chain (the guard may stop it and a save cannot evaluate it); a
  disabled Job is not either, and a disabled draft joins no chain.
- **Severity (D-SB3, D-CO1)**: `SIGNAL_TYPE_UNPOSTED` warns everyone, agents included. `SIGNAL_FIELD_UNDECLARED` and
  `JOB_CHAIN_LOOP` are refused (422 `CONFIG_VALIDATION_FAILED`, the finding code in the envelope, each problem and its
  fix in the message) for an agent Subject and a warning for a person. Only a finding the save **introduces** refuses
  an agent: one the stored Job already had stays a warning, so an agent can edit an inherited Job. A loop is one
  finding per Job whichever Jobs it runs through (the check names only the first loop it finds, and an edit that
  removes one path may name another the Job already closed).
- **SB-2 carries its own agent/person split** for these two findings until `SPACE-COHERENCE-1` SC-2 generalises it:
  one helper, `com.gamma.control.AgentStrict`, that SC-2 absorbs. `LinkRefusals`, `-Dlinks.save` and the D-LC3 flip
  are untouched; a person stays on the warning.
- **The save door** (`JobRoutes`): `POST /jobs`, `PUT /jobs/{name}` and `POST /jobs/{name}/enable` (enabling may close
  a chain) run the check before the maker-checker hold, so an agent never proposes what it would be refused. The
  response gains `warnings: [{severity, code, key, message, fix}]` when there are any, on a saved Job and on a held
  change's 202 alike (so an agent whose write is held still reads them). `disable` and `reschedule` cannot introduce a
  finding and are not checked. No route was added.
- **The link** (`LinkCensus`): `job.on_signal` and `job.when` now name `signal-type` (was `signal`) and carry a new
  check, `SAVE` ("save" in the anatomy §12.2 table, rendered by `tools/render-space-links.mjs`); 9 of the 48 links
  are now checked by nothing (`SPACE-COHERENCE-1` F2 said 11). The graph keeps them `UNCHECKED`, so the census save and
  delete refusals never judge them (a type nothing posts is a warning, D-SB3). `LinkGraph.signalListeners(type)` lists
  the Jobs whose `on_signal` fires on a type: the impact answer to "which Jobs stop firing if nothing posts it".
- **Tests**: `JobSignalCheckTest` (12), `LinkGraphTest` (+1), `ControlApiJobSignalChecksTest` (5, real HTTP: agent 422 and
  person warning for both refusable findings, enable closing a loop, an unposted type warning agent and person and
  refusing neither, an inherited finding not refusing an agent's edit).
- **Open, for later slices**: (1) A Job file changed on disk (SB-2c, §12) does not run these checks yet: they sit
  in `JobRoutes.signalChecks`, not in `refuseBeforeRegister`, the seam SB-2c applies files through. An on-disk edit has
  no Subject, so it would only gain the warnings (`JobSignalCheck.check(jobService, cfg)`). (2) `on_pipeline` (D-SB2: short for `on_signal: pipeline.commit`) is not
  followed in the chain: the commit mirror is posted with the Pipeline as source and depth 0, so the self-loop guard and
  the chain cut may not stop a Pipeline Job that listens to its own Pipeline's commit — not verified. (3) The Job form
  does not yet show the chosen type's fields beside `when` / `bind`. (4) `signalListeners` is not served by a route; the
  board (SB-3) or the SC-1 graph read can expose it. (5) An exact type that only families cover accepts the fields of
  every covering family, while the catalog resolves it to the longest one: lenient (a wrong field may pass), never a
  false refusal. (6) An edit that adds a second loop to a Job already in one is a warning, not a refusal (one loop
  finding per Job).
- **Board row text**: "SB-2 built 2026-11-28: a saved Job is checked against the catalog (`on_signal`, `$signal`
  fields in `when` / `bind`, a chain that closes on itself); an unposted type warns everyone, the other two refuse an
  agent and warn a person (own split in `AgentStrict` until SC-2); `on_signal` is a checked Signal Type link."

## 12. SB-2c as built (2026-11-28, lane off `origin/master` `d9a88975e`)

- **The seam is the Space lock's, not a second watcher.** `SpaceLock` gains an `OutsideApplier` (installed by
  `ControlApi`, removed at its close, as `ActivationGate` is). The scheduled computation (`computeNow`) hands it the
  files it newly marked changed outside the server — removals first, then added / modified / moved, each with the id
  its previous version declared — after the lock is written. The close-time flush never applies anything.
- **`OnDiskJobs` (`com.gamma.control`) applies a Job file through the API save path.** It binds the Space on the
  lock's thread, loads the file as `ServiceBootstrap.loadJobs` does (an unknown `template:` refuses it), parses it with
  `JobRoutes.parseJob` and checks it with `JobRoutes.refuseBeforeRegister` — the pre-write checks `persistJob` ran
  inline until now, split out so one method holds them. **A save check added there (SB-2) applies to on-disk edits
  with no further change.** Then `JobService.upsertJob`; a removed file calls `removeJob` and forgets the
  publication approval, as `DELETE /jobs/{name}` does. `JobService` is unchanged.
- **Refused = not applied, and reported.** A file that fails a check (a half-written one, an unknown type, a link the
  Space refuses) leaves the running Job as it was. Its `CONFIG_CHANGED_OUTSIDE` event carries the outcome in a new
  `applied` attribute (`applied: …`, `unchanged: …` or `not applied: <the check's message>`), at WARN when refused;
  the lock keeps the file marked changed outside. Fixing the file is a new change and is applied.
- **Rules beyond the API's:** a file whose Job already runs with the same config (judged as the boot loads it, before
  the stricter save parse) is `unchanged` — the first computation after a boot re-finds files edited while the server
  was down, which the boot already loaded. `OnDiskJobs` remembers, per file, the Job it last ran (seeded from the
  lock's previous id the first time), because the lock records every version it saw, refused ones included: a rename
  removes the Job the file really ran, and a half-written name never blocks the finished write. A file onto a name
  another file runs is refused, as `POST /jobs` answers 409. A system Job is refused, as the API refuses it. An
  identical copy of a running Job under a new path reports `unchanged`, not a duplicate — and deleting that copy
  removes the Job although the original file still declares it (it comes back at the original's next change or a
  restart).
- **The trigger.** The lock computes only when asked (boot, a server write, a read of its report). A light poll in
  `OnDiskJobs` walks each Space's config tree and stats every live `*_job.toon` every 5 s (`-Djobs.ondisk.poll.seconds`, `0` = off) and
  asks the lock to recompute when a time or size changed. `-Dspace.integrity=off` turns all of it off.
- **Deliberate limits.** A hand edit made within the lock's slack (about 2 s) of a server write in the same Space is
  attributed to the server and not applied until the next change; the lock does not see removals in a computation
  that also covers a server write (its existing rule). A file moved with changed content is a removal plus an add, so
  if the new file is refused the Job is gone. In a Space whose approval policy governs Jobs, an applied edit is still
  held from automatic runs until accepted (the existing activation hold), exactly as after a restart.
- **Tests:** `ControlApiOnDiskJobsTest` over real HTTP — an edit, a new file and a rename apply with no restart; a
  removed file removes the Job; a half-written file and a nameless one are not applied, the Job keeps its type and
  state, the event is WARN with the reason, and the finished write of the same file is then applied; a rename after a
  refused write leaves no orphan Job; a cut name followed by the finished write is not refused as a duplicate (the two
  probes of the verification review).
- **Board row text**: "SB-2c built 2026-11-28: a Job file changed on disk is applied live through the API save
  checks (the Space lock's change detection plus a 5 s poll); a file that fails a check is not applied and its
  CONFIG_CHANGED_OUTSIDE event says why."

## 13. SB-2b as built (2026-11-28, lane off `origin/master` `d9a88975e`)

- **One collaborator.** `com.gamma.job.SignalCatchUp` holds the marks and the boot replay. `JobService` changes stay
  small: the per-Job body of `onSignalEvent` moved, unchanged in its guards, into `reactToSignal(JobConfig, Signal)`,
  which the live subscriber and the boot replay both call, so `when`, the self-loop guard, the chain cut, the queued-Run
  bound, folding (`coalesce`), dry-run inheritance and the causation link are one code path. Each outcome tells the
  collaborator: a guard that said no, a refusal or a fold is handled at once; a Run is handled when it leaves the
  worker. Other hooks: `start()` (the cut, then `catchUpMissedSignals`), `upsertJob` / `removeJob`, the three early
  returns and the `finally` of `submitRun`.
- **The mark** is per Job: `(time, signal id)` of the last Signal it handled, in `signal-marks.tsv` in the Job audit
  directory (temp file, then an atomic move). It is a **low-water mark**: Runs finish out of order, so it never passes
  a Signal whose Run is still in flight. A crash therefore replays that Signal and every later one of the Job —
  delivery is **at least once**: a later Signal whose Run had finished may run again. A Run that a planned stop skipped
  or cancelled (`closing`, status `SKIPPED` / `CANCELLED` / still `RUNNING`) pins its Signal, so the next boot replays it.
- **Boot.** `start()` takes the cut just before the live subscriber attaches, then reads the Space ledger's Signals
  (`EventQuery` on type `SIGNAL`) from the oldest mark to the cut, oldest first, at most 100 000 rows (a longer backlog
  is a WARN and its oldest part is skipped). Each Signal goes to every enabled `on_signal` Job whose type matches and
  whose mark is behind it. Before the subscriber attaches, `SignalCatchUp.hold` **holds** each listening Job: its
  mark does not move until the replay ends (and the cut sits in its in-flight set as a sentinel), so a live Signal
  handled before or during the replay — even one stamped just before the cut — cannot move the mark past the backlog.
  The replay releases the hold at its end, whatever happens. Found by the verification review in two passes;
  regression tests `aLiveSignalHandledBeforeTheReplayDoesNotSkipTheBacklog` and
  `aLiveSignalStampedJustBeforeTheCutDoesNotSkipTheBacklog`.
- **New listening replays nothing.** A Job with no mark starts at the cut (at boot: a Job added while the server was
  down, and every Job on the first boot with this code). A save that adds a Job, enables it or changes its
  `on_signal` starts it at "now"; other edits keep the mark; a removal forgets it. SB-2c's on-disk apply goes through
  `upsertJob`, so it gets the same rule.
- **Age limit (D-SB5).** Default 24 hours; per Space `config/signal-catch-up.toon` with `max_age_hours: <n>` (a
  positive whole number; anything else reads as 24), read by `JobService.signalCatchUpSettings(configRoot)`, wired in
  `CollectorService`. The file is reserved from import (`ReservedConfigPaths`). An older missed Signal is not replayed;
  each such Job gets one `job.signal.missed` WARN Signal (new core Signal Type, catalog now 53 types / 31 constants):
  `job`, `onSignal`, `count`, `oldestAt`, `newestAt`, `signalIds` (first 100), `maxAgeHours`. SB-3 lists it.
- **Tests** (`SignalCatchUpTest`, engine): a Signal stored while the service was down starts its Run at boot, and a
  third boot replays nothing; the age limit (a 2-hour-old Signal under a 1-hour limit is posted as missed, a fresh one
  runs); a new Job replays no history, a save that changes `on_signal` restarts the mark, a removal forgets it; two Jobs
  on one type catch up from their own marks; the low-water mark; a skipped Run pins the mark across a restart; the
  setting's default and per-Space value.
- **Not covered / known edges.** The live stop test of §8 is still owed (the tests stop and start the service on one
  in-memory ledger). A Job disabled in config with an old mark and enabled again by a file edit made while the server
  was down replays, at boot, the Signals of that window (within the limit). A Signal built before the cut but stored
  just after the subscriber attached may be handled twice (at least once). A mark is written on each advance; a very
  high Signal rate costs one small file write per handled Signal per Job. Marks order by (milliseconds, Signal id),
  and the id is random, so of two Signals in one millisecond one can sort behind the mark and be lost to a crash. A
  Job on a rarely posted type keeps an old mark, so each boot reads the ledger from it (up to 100 000 rows). The mark
  does not record `on_signal`, so an `on_signal` changed on disk while the server was down replays the new type from
  the old mark. The Parquet event store sorts by time alone, so the 10 000-row pages can repeat or drop Signals that
  share a millisecond at a page edge. If `start()` throws between `hold` and the replay (a pack watcher, cron arming,
  the cron catch-up), the held marks stay frozen for that process; the Space's start fails with it. All are low; none
  is fixed in SB-2b.
- **Board row text**: "SB-2b built 2026-11-28: each on_signal Job keeps a durable mark; at boot the missed Signals
  are replayed under the same guards, older than 24 h (per Space) posted as `job.signal.missed`."
