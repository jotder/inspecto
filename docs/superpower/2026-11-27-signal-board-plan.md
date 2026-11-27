# Signal Board — one clear meaning for Signal, and a board Jobs can trust (`SIGNAL-BOARD-1`)

Status: **DRAFT 2026-11-27 — direction agreed by the operator (D-SB1 decided); D-SB2…D-SB5 open; nothing built; next
is SB-0.** Owner: this shift. Items: `module.event`, `module.engine`, `module.observability`, `module.config`.
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
| G2 | Some facts a Job could usefully react to are plain Events, so no Job can listen to them. Candidates: a paused Pipeline, a broken config link, a file changed outside the server, a sequence gap. | 58 fixed types; how many are reactable is not yet counted (SB-0) |
| G3 | One fact is posted in several places. A committed Consignment is a `ConsignmentEvent` on its own bus, a `pipeline.commit` Signal (a mirror) and a `pipeline.batch.committed` Signal. A Job has two keys to react to it: `on_pipeline` and `on_signal`. | `JobService.mirrorPipelineCommit`, `ConsignmentAuditWriter`, `fireOnCommit` |
| G4 | There is no one list of Signal Types. Names are spread over eight constants classes and the Job Type declarations, and no type declares its payload fields. | `SignalType` class comment; the seven module classes |
| G5 | Nothing checks a Job's `on_signal` against what is ever posted, or its `when` and `bind` against the payload. A mistyped type is a Job that never fires, with no finding. | Not verified at the save gate: see §8 |
| G6 | A loop between Jobs is found only at run time, by the depth cut. The chain Job → type → Job is knowable at save time. | `cutChain` |
| G8 | Reaction looks live-only. The Job service reacts as a Signal is posted, and the queued follow-up Run is held in memory. If the server stops after a Signal is stored and before its Run starts, that reaction is probably lost, and nothing replays it at boot. | `onSignalEvent`, `signalFollowUps`; not verified: see §8 |
| G7 | Nobody can see the board. There is a ledger of what was posted, but no view of which types a Space posts, which Jobs listen, and which postings nobody heard. | No route or page |

## 5. Plan

**SB-0 Define and measure.** Docs and a read-only tool; no behaviour change.
- Rewrite the `GLOSSARY.md` §8 entries with §2, add **Signal Type** and **Signal Board**, and correct the two hub pages
  that repeat the old sentence.
- Classify the 58 fixed Event types: record only, or reactable. List every fact posted more than once.
- Settle the items of §8, G8 first: stop a server between a stored Signal and its Run, boot it, and see whether the
  Job runs.

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
- Roll-out follows that plan: a warning for a person first; an agent is refused (D-CO1).

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

## 7. Decisions

| # | Decision | Recommendation |
|---|---|---|
| **D-SB1** ✅ | Who is the board for, and does a Signal carry state? | **DECIDED:** Jobs; stateless |
| D-SB2 | Keep `on_pipeline` beside `on_signal`? | Keep the key, define it as short for `on_signal: pipeline.commit` on that Pipeline, one code path |
| D-SB3 | An `on_signal` type nothing posts: warn or refuse? | A person is warned (a Job Pack may arrive later); an agent is refused |
| D-SB4 | May a Space declare its own Signal Types in config? | Yes, in SB-1, so a Space's own Jobs can post a type with declared fields |
| D-SB5 | If G8 is confirmed: catch up at boot, and how far back? | Yes (SB-2b), bounded by an age limit so a long outage does not start a flood of stale Runs |

## 8. Not verified

- Whether a reaction is lost when the server stops between a stored Signal and its Run (G8), and whether anything
  replays it at boot.
- Whether the save gate already checks `on_signal`, `when` or `bind` in any way.
- Whether a committed Consignment also writes a `BATCH_COMMITTED` Event beside the two Signals.
- Whether a Job of a generic type (for example a SQL Job) can post a type of the author's choosing today.
- Where the Space's ledger file sits on disk under the data root.
- No build or test was run for this plan; it is a reading of the code.

## 9. Board

Filed 2026-11-27: `SIGNAL-BOARD-1` on `docs/BACKLOG.md` (P2, §3.5); this plan's row is in `docs/INDEX.md`.
