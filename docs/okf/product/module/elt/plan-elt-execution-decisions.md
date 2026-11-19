---
type: Capability
title: Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan — Decisions — Pipeline execution onward
description: Part 3 of 7 of Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan: 
status: current
tags: [acq, ing, pip, capability, acquisition, connections, collectors, connectors, ledger, ingestion, parsing, consignment, grammar, parser-plugin, quarantine, expectation, pipeline, authoring, step-catalog, execution, scheduler, jobs, plan]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
verified: 2026-11-19 last-edit
code: platform/inspecto-etl
---

# Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan — Decisions — Pipeline execution onward

Part 3 of 7 of [Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan](plan-elt.md) · previous: [Where the mechanism lives, and decisions](plan-elt-mechanism-decisions.md) · next: [Not built and open](plan-elt-not-built.md).

## 4. Decisions (continued)

### EXE.4 Pipeline execution — decisions (dated one-liners)

| Date | Decision | Who |
|---|---|---|
| 2026-06-17 | ⛔ **No `ingest` job type** — ingestion is the Pipeline's sole responsibility and an `ingest` job is a config error; a Job is strictly downstream over data at rest, never re-acquisition | design |
| 2026-06-18 | **An authored Pipeline at rest is hosted as a Job**, on the existing scheduler — the Job is the only seam that reads data already written | engineering |
| 2026-07-09 | **Job Types are plugins on an open registry keyed by string id**, not an enum; descriptors drive the generated authoring forms | engineering |
| 2026-07-09 | **An unresolved required parameter fails the Run before user code**, in a rejected state | engineering |
| 2026-07-12 | **Housekeeping is tasks on one job type — never shell scripts or host cron**, so findings emit signals an Alert Rule can consume | engineering |
| 2026-07-12 | **A task with no preview does nothing on a dry run** — fail-closed, never falling through to the real action | engineering |
| 2026-07-18 | ⛔ **`…/run` and `…/trigger` must never be merged** — one simulates into scratch, the other operates | engineering |
| 2026-07-20 | **A cron registration returns a cancellable handle and removal cancels it**, with the fire-time guard kept as a second line — otherwise a deleted job's self-re-arming chain ticks as an inert no-op **forever** | engineering |
| 2026-07-20 | **A parameter type mismatch rejects exactly like a missing required parameter** — else the raw string reaches the job's own parse and throws uncaught mid-run | engineering |
| 2026-07-20 | **Job-pack quiesce, both halves** — the loader is pinned per in-flight Run and a stale job is rejected rather than run, because a Run already executing pack code must not have its resources yanked | engineering |
| 2026-07-25 (**T15**) | **Cycle overrun, not inbox lag, is the throttle signal** — admitting fewer files cannot reduce inbox age, so throttling on it is **positive feedback** that pins a healthy-but-backlogged pipeline at the floor | engineering |
| 2026-07-25 (**D12**) | **Chunking on by default at 8 GiB** — the threshold exists for pathological single files, so it sits far above any routine input | operator |
| 2026-07-25 (**D5**) | **Incident retention is a retention tier, not archive-is-terminal** — otherwise "archived" would mean kept forever, the posture compliance must bound | operator |
| 2026-07-27 | **Named a purge, not a prune, and the window is required with no default** — a defaulted window on the one task that hard-deletes business records would be indefensible | engineering |
| 2026-07-27 | **Retention is derived from the close time, not stamped** — trade-off taken consciously: shortening the window later retroactively widens eligibility | engineering |
| 2026-07-27 (**G3**) | **A purge is not "all trace removed"** — the append-only ledger outlives the record, including its own purge entry, asserted **by a test** because it is a reviewer's first question | engineering |
| 2026-07-27 | **Correctness comes from the cutoff, not the ordering**; legal hold is re-checked *inside* the purge and dependents go before the object — the cutoff in the predicate kills the "reports zero prunable against a fully-expired corpus" failure | engineering |
| 2026-07-27 | **The memory cap was measured at 2 GB** — ~2.2× the highest observed peak. ⚠ Peak memory does **not** scale with input here; a cap governs the blocking operators, and they **hard-fail instead of spilling**, so an aggressive cap turns working jobs into failing ones | engineering |
| 2026-08-01 | **Exclusion moved from one global lock to a per-pipeline non-reentrant guard** — a reentrant lock would not have deadlocked, it would have silently re-entered and **double-ingested the inbox** | engineering |
| 2026-08-02 | **Sub-Pipeline and embedded-Job nesting ruled out by design** — in-motion versus at-rest is a *binding* line; they compose as producer and consumer over a shared store | product |
| 2026-08-03 | **Run ⊇ Consignment ⊇ File replaces Run ⊇ Batch ⊇ File**, identity being the run-and-consignment pair, because the Run is the attempt and a reprocess is a new Run over the same Consignment | vocabulary |
| 2026-08-04 | **Post-sync authors never touch the Job surface** — the context delegates member by member, because an accessor would leak the whole surface into a third-party contract | engineering |
| 2026-08-04 | **Reading is a narrow seam, never a raw database handle** — a handle makes the forbidden read-modify-write trivially expressible, which had made the plan's own acceptance test unsatisfiable | engineering |
| 2026-08-04 | **Relations come from the registry, never a directory glob** — a partition directory holds files from every Consignment that wrote that day | engineering |
| 2026-08-04 | **Composability must be declared; undeclared is refused** — non-composable measures produce quietly wrong numbers, and guarding the write alone never stopped a reader averaging an average | engineering |
| 2026-08-04 | **A summary-write failure fails the Run** — the numbers are the output, and losing them silently makes a green Run a lie | engineering |
| 2026-08-05 (**D-2**) | **A converter plus one flagged verification minor, then the legacy readers are deleted — no permanent dual format.** The flag *is* the verification window, and it is the gate on Row 15 | operator |
| 2026-08-05 (**D-9**) | **Within-Consignment dedup in v1**; the windowed keyed ledger is designed and **never faked with unbounded history** | operator |
| 2026-08-05 (**D-12**) | **The Consignment rename is taken inside the major window and sequenced last**, with read-aliases for persisted rows rather than a hard cutover — post-release the same rename costs a deprecation cycle | operator |
| 2026-08-05 (**D-13**) | **Per-Step pause with park-at-boundary semantics**, so a parked Consignment stays uncommitted; dry-run and run-to-here remain the scratch-only paths | operator |
| 2026-08-06 | **"Job" un-banned** — again the canonical user-facing term for a scheduled Executable over data at rest | operator |
| 2026-08-07 | **Capability arrives by registration, never by editing a switch**, and a collision **fails closed in all three paths** — a pack redeclaring a token is rejected whole rather than shadowing it | engineering |
| 2026-08-07 | **Three-way resolution, not two** — an unregistered token rejects, a declared token with no value here falls through; conflating them is what let a typo silently use the default | engineering |
| 2026-08-09 (**D0**) | **A Job reaches an engine facility only through a declared grant, fail-closed at registration** — an undeclared service stays absent and the menu grows by demand, never speculatively | engineering |
| 2026-08-11 | **Record dedup moved out of the first stage** for failing the batch-independence test — the dividing rule is **batch independence**, not statelessness per record | engineering |
| 2026-08-12 | **Consignment ordering defaults to file arrival time**; name-ordering is the opt-in for feeds with unreliable stamps, and any other value is refused at parse | operator |
| 2026-08-15 | **One operations zone governs both cron firing and the date-token family** — a job firing just after midnight in one zone and resolving its date token in another is an **off-by-one-day generator**. Unset inherits the host; set-but-unresolvable **throws at startup**, never a silent fallback | engineering |
| 2026-08-15 | 🔴 **It is not the data's timezone key** — that describes the data's timestamps, and wiring it into the scheduler would make firing depend on **directory scan order** | engineering |
| 2026-08-15 | ✅ **Three reader sites stay on the host zone and the sweep is closed** — each is the read half of a pair over a zone-naive stored string; ⛔ converting a reader alone silently offsets every window, and in the compactor's case **drops rows** | engineering |
| 2026-08-15 | ⛔ **No key writable through the settings document may also be read from a launch flag at use time** — split ownership of one fact is exactly what that decision forbids | engineering |
| 2026-08-25 | **A permit counts a Consignment being executed — never a Pipeline, never a run**, so the four tiers are comparable numbers | engineering |
| 2026-08-25 | **Stride scheduling, not priority ordering** — the lowest priority *provably* keeps a non-zero share, with no banked credit, and the ratio is unit-testable | engineering |
| 2026-08-25 | ⛔ **The ingest run budget is not redundant with the broker** — a claim the plan made and the code refuted; it bounds everything a cycle does *before* the first broker permit | engineering |
| 2026-08-25 | **A shrink drains, never interrupts** — aborting a mid-commit ingest is not something the commit sequence was designed for; it is crash-*idempotent*, a different property | engineering |
| 2026-08-26 | **Route executes on the poll-driven ingest path**, diverting at the one choke point that holds the live connection and the materialised table | engineering |
| 2026-08-26 | **Fail-closed route arming — six rules, each refusing by name**, because every refused shape would drop rows *silently* | engineering |
| 2026-08-26 | **The arming rules have one statement and two callers** in two config states — restating them over raw maps is the hand-mirrored-map drift this repo has already paid for three times | engineering |
| 2026-08-26 | **The save-time arming pre-check ships, and the earlier reason for deferring it was wrong** — arming did validate at load, but neither validate nor write called it, and **a fail-closed gate the author never sees is a log line** | engineering |
| 2026-08-26 | **Provenance follows the key, never file presence** — the cap is nullable end to end, and an explicit null hands ownership back to the flag live. Before it, saving anything on the form **silently seized provenance to the file forever** | engineering |
| 2026-08-26 | **The memory-limit grammar is served, never mirrored**, anchored and portable, with the bound **inside** the pattern — a bound beside the grammar lets the form accept what the write refuses | engineering |
| 2026-08-26 | **Every scheduler-settings change is journalled with deltas**, only actual changes and only moved keys — a path-classified audit row cannot carry *what the numbers became*, which is the half an incident review asks for | operator |
| 2026-08-26 (**D11**) | **The resource pair ships, both owned by the server configuration** — the Run bound default-on in code, the memory limit served with **no code default**, because total exposure is the limit times the concurrent Run count | operator |
| 2026-08-26 | **The Run bound counts in-flight Runs even while unbounded, and clearing reverts rather than freezes** — 🔴 a semaphore subclass that re-derived "did this Run take a permit?" from the current cap ended at **seven permits under a cap of four, permanently** | engineering |
| 2026-08-29 | **The graph lane is no longer route-only** — a non-routed pipeline is admitted wherever the write is provably reproducible, and the fan-out gains **per-destination crash resumption the flat loop never had**; parity is *proven by a differencing test*, not asserted | engineering |
| 2026-08-29 | **Decision rules are a space-registry fact, not a config property** — applied once above the fork, with their *result* part of the admission | engineering |
| 2026-08-29 | **A pipeline with no scratch directory stays flat** rather than parking its branch ledger in a shared temporary directory — a stale branch log makes the coordinator skip the branch and **the batch writes nothing** | engineering |
| 2026-08-29 | **The engagement predicate counts branches, not sink nodes** — the node-count version engaged for plain fan-out and was refuted by its own falsification test, with a stale test pin encoding the wrong belief | engineering |
| 2026-08-29 | **Disabling a Step means two different things per lane** — scratch bypasses in memory, ingest pauses durably, because a bypass there would **lose rows** | engineering |
| 2026-08-29 | **One durable home for disabled steps, and the enabled flag is structural, never a declared attribute** — a per-node key in every lower branch is a seven-branch, two-mirror drift class | engineering |
| 2026-08-30 | **Sealing dropped, not deferred** — the partition-state machinery bought nothing the completeness measure does not | operator |
| 2026-08-31 (**D2**) | **A Step receives a token and resolves data by reference** — edges never carry records; the runtime model converges later | operator |
| 2026-08-31 | **The rename landed as one commit scoped by concept, not by string** — reusing the post-sync processor's name would have broken plugin authors and the one-word-one-concept rule | engineering |
| 2026-09-01 | **Both graph sources are optional in the descriptor and "pick one" is enforced at run time** — declaring either required made the at-rest job **unrepresentable** | engineering |
| 2026-09-01 | **The orphan output-store check is default-on in every space**, one signal per transition, with a kill switch — until then it fired only from a maintenance job that no shipped space but the demo had, so a stock space had **no orphan detection at all** | engineering |
| 2026-09-01 | **One concept page owns "which lanes exist"** — there had been about six framings across five files, none authoritative | docs |
| 2026-09-02 (**X1**) | **Bounded commit retry** with durable per-file sidecars and quarantine on exhaustion — the retry already existed and was **unbounded**. ⚠ Grounding **refuted the plan's premise**: nothing classifies transient from fatal, and the real gap was the opposite of the plan's fear | engineering |
| 2026-09-02 (**X2**) | **Cross-lane provenance records which ingest Consignments an at-rest run read**, fed from the selector's kept files and **never from the rows** — ordinary outputs carry no per-row batch id, and **unknown is not empty** | engineering |
| 2026-09-02 | **One recovery affordance per lane** — reprocess retracts the Consignment's dedup claims, drain resumes a park, replay re-runs at rest | engineering |
| 2026-09-02 | **The drain does not re-walk** — it registers the park tables and runs the real finalisation, keeping the branch commits as its resume record | engineering |
| 2026-09-02 | **The lane flag ships, extracted pure so it is testable** — Row 15's precondition, not its trigger | engineering |
| 2026-09-06 | **Clone mode arms, and committed branches become visible** — the durable per-branch log already existed; only the read surface was missing | operator |
| 2026-09-06 | **The fetch lane stays first-in-first-out** — no contention has been observed, and the trigger to revisit is the **first observed fetch-lane wait** | engineering |
| 2026-09-07 | **The backup tasks are contributed, not built in** — on Personal they are unknown tasks **refused loudly**, never a silent skip a chained job would read as success | engineering |
| 2026-09-08 | **The incident purge had to be removed from the built-in dispatch, not merely also provided** — a named case always beats a contributed provider, so leaving it would silently keep it on every edition | engineering |
| 2026-09-20 (**`PIPELINE-DRYRUN-1`**) | **A dry run skips `strategy.ingest` WHOLE rather than substituting its ~20 durable sites** — per-sink gating is the shape that misses one; skipping is true by construction. ⚖ Accepted cost: a dry run answers *what would this cycle touch*, **not** *would these files parse* (§EXE.3.11) | engineering |
| 2026-09-20 (**`PIPELINE-DRYRUN-1`**) | **`?dryRun=true` IMPLIES `?skipPostAction=true`**, OR-ed in at two levels — a dry run that acked and deleted the customer remote file is the worst failure the feature can have, and must not depend on a caller remembering | engineering |
| 2026-09-20 (**`PIPELINE-DRYRUN-1`**) | **The dry run PUBLISHES a MARKED `ConsignmentEvent`**, which narrowly overturns *cron/event/signal fires are always real* — safe only because there is exactly ONE fan-out, so the consumer set is closed and each was made to honour or refuse loudly (§EXE.3.11) | engineering |
| 2026-09-23 (**`DRYRUN-INVISIBLE-ON-FLAT-LANE-1` a**) | **A flat-lane dry run writes ONE provenance record flagged `simulated`** — a deliberate exception to *skip the pass whole*, metadata only and the ONLY write a dry run makes (counts are the truthful zeros of a skipped pass), so the overlay promise holds on the lane the feature shipped for rather than being retracted (§EXE.3.11) | operator |
| 2026-09-24 (**`FLAT-DRYRUN-COUNTS-ZERO-1`**) | **A dry run PARSES: `strategy.ingest` runs CONTAINED instead of skipped** — over member copies, against `forScratchRun`, under a suffixed batch id, with `EventLog.CONTAINED` bound — so the 2026-09-20 row's accepted cost is lifted without substituting a single site; provenance counts become real and each member is reported by kind (`WOULD_LAND` / `REJECTED` / `SKIPPED` / `FAULT`), the evidence X4's replay default waits on. ⛔ No replay default was picked (§EXE.3.11) | engineering |
