---
type: Spec
title: Agent Space tests (AGENT-KIT-1 W6 group C) — design and as-built
description: How a delegated person's AI agent runs a Space's test cases through the MCP endpoint or directly (POST /tests/run), why a machine agent may not, what a run writes (nothing outside a deleted scratch root), how long it runs, why its answer carries no data rows, the run_space_tests MCP Tool, the skills and golden task, and the tests that prove it.
tags: [agent-kit, mcp, agents, authoring, security]
timestamp: 2026-11-26T00:00:00Z
item: module.mcp
audience: internal
status: current
verified: 2026-11-26 code-reviewed
code: features/inspecto-mcp
---

# Agent Space tests

Widening plan `docs/superpower/2026-11-25-agent-kit-ak2-widening-plan.md` § W6, group C; decision **D-AK10**
(signed 2026-11-25): a **delegated person's** agent may run the Space's test cases, as it may run a Pipeline under
D-AK2; a **machine** agent may not. The developer skills now say: preview, dry-run, **run the Space tests**, then
propose. The cases are the Space's own ([Space tests](../config/spec-space-tests.md)): a folder per case under
`<space>/tests/`, each feeding one Pipeline its own sample files and stating the rows, rejects, checksum or column
checks it expects.

## The route and the gate

`POST /tests/run` (`SpaceTestRoutes`) keeps its own gate, `canAuthorWorkbench`. It joins `AgentGate.ALLOWED` and
`AgentGate.PERSON_ONLY` (beside `POST /pipelines/authored/{id}/run`), so:

| Caller | Answer |
|---|---|
| a person's agent holding `canAuthorWorkbench` | the route's own answer (200 with the verdicts) — the same body its person gets |
| a person's agent without it | the route's own 403 naming `canAuthorWorkbench`, not the agent gate's |
| a machine agent, whatever it holds | 403 "an agent machine client may not run a Pipeline or the Space tests — a delegated person may" |

`GET /tests` (the list of cases) was already open to every agent as a read.

## The risk, settled

- **What a run writes: nothing that outlives it.** Each case runs through `PipelineTestRun.run`, the run-to-here
  engine, into a fresh scratch root in the JVM temp dir that `SpaceTestRunner.run` deletes in `finally`.
  `PipelineConfig.forScratchRun` re-roots every destination (poll, database, every sink and route branch, temp,
  errors, quarantine, logs) under it and drops status, lineage, marker, backup and DuckLake registration; the input
  files are **copied** in, so a quarantine never moves a case file. Only the ingest pass runs — never the commit, the
  batch audit or the provenance record, which are the whole production side-effect surface (the consignment output
  registry, file stages, the acquisition ledger, the `pipeline.batch.*` Signal). The ambient emitters inside the pass
  write to a throwaway `EventLog.CONTAINED`. A webhook or Excel sink never runs on that lane: `IngestSinkWriter` refuses
  both, so a case cannot POST rows to a Connection. A case fed by a Dataset is refused ("a case feeds files"). The case
  folder is only read (`SpaceTestRunner.load` jails every input to it). No route writes `tests/`, so an agent cannot
  add or edit a case.
- **How long it runs.** Synchronously, on the request: at most `SpaceTestRunner.MAX_CASES` (200) cases a run, each one
  Pipeline over that case's own files. Both are a person's work, never an agent route's; an agent
  chooses only which existing cases to run. No rate limit is added: the caller is a person's agent, which can do
  nothing here its person cannot do directly with the same token rights.
- **No data rows in the answer.** The answer is `{passed, total, failed, cases[{name, pipeline, passed, error?,
  expectations[{expectation, expected, actual, passed}]}]}`: counts, an md5 checksum, the null or distinct count of a
  column, and the `min` or `max` of a column **the case names** — one value per check, chosen by the case's author,
  never a row. The values come from the case's sample data, not from a live Dataset; a Pipeline step that joins a
  Reference reads it, read-only, and a check on a joined column shows at most its min or max. An `error` carries the
  batch's own message, which can quote one sample value (a cast failure). Masking is not applied, because nothing in
  the answer is a Dataset read; this is stated, not proven by a masking test.

**Audit.** The route's own audit row is named by the generic POST verb, `test.created` (`AuditTrail.classify`), as
`POST /pipelines/authored/{id}/run` is `pipeline.created`; it writes nothing. The golden grader's `no-direct-write`
skips `POST …/tests/run` (`WRITES_NOTHING_PATH`, `tools/run-agent-golden.mjs`) so a run is not counted as a write.

## The MCP Tool

`run_space_tests` (read, `readOnlyHint: true`, as `dry_run_pipeline`) — one optional argument `body`, the route's
own body: `{cases: [names]}` runs those, no body runs every case. A refusal comes back as the route's status
(`isError`, "HTTP 403 … may not run a Pipeline or the Space tests").

**The cases test the Pipelines as they run now, not a draft.** `SpaceTestRoutes` resolves each case's Pipeline from
the loaded config (`configFor`), so running them before `propose_change` gives the baseline (which cases already fail)
and running them after approval shows what the change broke. The skills say so.

## Skills and golden task

- `safe-config-change`: new step 5, **Run the Space tests** (steps renumbered 6–7), and a row for the machine's 403;
  `author-pipeline`: run them before proposing and after approval; a machine agent may not.
- Developer golden task `dev-14-space-tests`: a person adds two cases over the import Pipeline (`shipments_rows`
  expects 36 rows, `shipments_rows_stale` 40); the agent must call `run_space_tests`, name the failing case and the 36
  rows that landed, and propose nothing. Its live run is part of W4.

## Tests

- `ControlApiAgentSpaceTestsTest` (real HTTP, the demo Space's two shipped cases): a person's agent gets 200 with both
  cases passing, every expectation is `{expectation, expected, actual, passed}` only, a named case runs alone, and a
  before-and-after read (every file under the temp root — config, tests, data, registry, audit — and the Pending
  Changes) is unchanged; the person's own token gets the same body; a machine agent holding `canAuthorWorkbench` is 403
  by the gate and changes nothing; a person's agent without the right gets the route's own 403.
- `AgentGateParityTest` (the route is allowed, person-only and still `canAuthorWorkbench` in the manifest);
  `ControlApiMcpWriteToolsTest` (the tool end to end for a person's agent; annotated read-only); `McpToolRoutesTest`
  and `McpRoutesManifestParityTest` (the tool replays a manifest route).
- `tools/run-agent-golden.test.mjs`: a run audited `test.created` on `POST /tests/run` is not a write; the same label on
  another path is.
- **Falsified** (each test turned red with its guard removed, then restored): `POST /tests/run` out of `ALLOWED` (the
  person's agent gets 403); out of `PERSON_ONLY` (the machine gets 200, and only that test fails); `ApiContext.Gated`
  skipping the `canAuthorWorkbench` check (the agent without the right gets 200, and only that test fails); the grader's
  `WRITES_NOTHING_PATH` skip removed. Removing the route's `withCapability` is no mutant: the server then refuses to boot
  ("undeclared mutating route").

## Security review

Scoped to what group C adds, by the lane's `verification` subagent before integration (2026-11-26): **PASS**.

- The new entry is the exact key `POST /tests/run`, judged on the route pattern after the `/spaces/{id}` prefix is
  stripped, on both doors (a direct request and every in-process replay, the MCP tool included), so nothing widens
  through the prefix and a machine is refused through MCP too. A person's own console token may call the read-only
  tool through MCP, as it may call the route directly (as `dry_run_pipeline`).
- The engine is not new to agents: `POST /pipelines/authored/{id}/run` (D-AK2) already runs `PipelineTestRun.run`.
- "Writes nothing durable" was traced and confirmed; the reviewer's own mutant (the run not re-rooted to scratch)
  turned the before-and-after test red.
- Not blocking, stated here: there is no timeout or rate limit on a synchronous run of up to 200 cases; a joined
  Reference column's min or max and the error text are not masked; `deleteScratch` is best effort, so a Windows file
  handle can leave a scratch folder in the JVM temp dir (not product state); and `PipelineTestRun` clears the
  in-memory progress snapshot of the live Pipeline of the same name after each batch, so a real run of it at the same
  moment loses its progress display (it did so already for the run-to-here preview).
