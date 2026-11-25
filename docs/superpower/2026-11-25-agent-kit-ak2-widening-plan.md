# Agent Kit AK-2 — widening what an agent can reach, task by task (plan and task specs)

> **Status: PROPOSED 2026-11-25 — D-AK8…D-AK12 open (§5); nothing built.** The follow-on to phases 1–3b of
> [`2026-11-20-agent-kit-ak2-plan.md`](2026-11-20-agent-kit-ak2-plan.md) (all built 2026-11-25). It takes the gaps a
> peer's product review of that plan listed
([`2026-11-25-agent-kit-ak2-product-review.md`](2026-11-25-agent-kit-ak2-product-review.md)), keeps the ones
> the code supports, and specifies each as a task small enough to build and review on its own. Board row:
> `AGENT-KIT-1`. Product Map Items: `module.mcp`, `module.agent`, `module.access`, `layer.platform`. Every route,
> class and count below was checked against `origin/master` `5c8effa9e` on 2026-11-25.

## 1. The goal

After phases 1–3b an agent can build six kinds of config, operate runs, work Incidents and Cases and analyse in Link
Analysis. Three things still hold it back, in this order of cost to the user:

1. **It learns config shapes by being refused.** The live golden runs show up to 17 refusals (422) in one task
   ([`tests-agent-golden.md`](../okf/product/module/mcp/tests-agent-golden.md)), because the server's own previews,
   simulators and Space tests are refused to an agent and the shape reference is not served.
2. **It cannot propose config the gate already holds.** `PendingChanges.REPLAYABLE` holds Decision Rules, Notification
   Channels and Notification Rules, a Requirement's KPI and a Pipeline history restore; `propose_change` offers six kinds.
3. **The operations role stops short.** Releasing a quarantined file shipped as a route (`QUARANTINE-RELEASE-1`,
   `b53d07ef9`) after phase 2 was scoped, so an agent cannot call it.

Done means: every task in §3 that the operator signs is built with its proof, the live golden runs of §3 W4 are in the
matrix, and a security review of each gate change finds nothing at confidence 8 or above.

## 2. Rules every task follows

These are the phase 1–3 rules, restated so a task spec does not repeat them:

- **No new write path.** An MCP Tool replays one existing route as the caller. A task adds a tool, a gate bucket entry
  or both; never a route.
- **One bucket per route.** A route moves out of the refused bucket only by name, into `AgentGate.ALLOWED` (changes
  nothing governed), `AgentGate.ACTIONS` (a person's agent acts at once within the person's rights; a machine agent's
  call is held as a Pending Action, D-AK5 and D-AK6) or the held bucket (`PendingChanges.REPLAYABLE`).
  `AgentGateParityTest` already fails when a non-GET manifest route sits in no bucket or two.
- **The never-list does not move.** No agent approves, declines, reveals, promotes, or changes Apps or access.
- **"Read-shaped" is a manifest category, not a safety verdict.** A route that returns data needs its masking, its
  SQL guard and its rate limit shown by a test before it is allowed; a route that executes a Pipeline or SQL is
  reviewed as an execution.
- **Proof per task:** a real-HTTP test with an agent token for each bucket move (person allowed or held, machine held
  or refused, the direct REST call behaves as the tool does), one falsification (remove the entry and the test fails
  the other way), the tool in `McpRoutesManifestParityTest` and `McpToolRoutesTest`, `openapi.fragment.json` and
  `spec-mcp.md` updated, and `node tools/check-agent-kit.mjs` green.
- **Review:** a task that changes `AgentGate` gets a security review scoped to what it added, as each phase did.

## 3. Tasks

| # | Task | Kind of change | Effort | Waits on |
|---|---|---|---|---|
| **W1** | Release a quarantined file | one action route, one tool option | 1 day | nothing |
| **W2** | `propose_change` for the kinds already held | tool allowlist, machine capability set | 3–4 days | D-AK9 |
| **W3** | Read tools for Space health, storage and scores | tools only (GET routes) | 2–3 days | nothing |
| **W4** | Live golden runs: operations, analyst, Link Analysis, other clients | chore, no code | 2–3 days per client | an identity provider install; D-AK12 for order |
| **W5** | Shape reference and error catalogue as MCP Resources | resources | 3 days after its two board rows | `COMPONENT-REFERENCE-GENERATED-1`, `ERROR-CATALOGUE-1` |
| **W6** | Previews, simulators and Space tests, one group at a time | allowed bucket, five groups | 1.5–2 weeks in all | D-AK10 |
| **W7** | Operations, second set: evaluate and the Job backlog | action routes | 3–4 days | D-AK11 |
| **W8** | Golden tasks that try to talk the agent into the never-list | golden tasks, grader | 3–5 days | W4 (a working live harness) |
| **W9** | Two read-only Agent Skills: review a proposal, audit a Space | skills, golden tasks | 2–3 days each | W3 |
| **W10** | The Kit guard reads every golden suite and every tool's bucket | guard | 1 day | nothing |

Recommended order: **W1, W10, W3, W2, W4, W5, W6, W7, W9, W8**. W1, W3 and W10 need no decision and can start now.

### W1 — Release a quarantined file

- **Goal.** The operations agent can put one quarantined file back into its run, the last step of *diagnose a failed
  run* that today ends in "ask a person".
- **Today.** `POST /runs/([^/]+)/quarantine/release` (`RunRoutes`, body `{path, reprocess?}`, `canOperateRuns`) is in
  `CapabilityManifest` and in no `AgentGate` bucket, so an agent gets 403. Plan §6.2 left it out because the route did
  not exist yet.
- **Change.**
  - `AgentGate.RUN_ACTIONS` gains `POST /runs/([^/]+)/quarantine/release`.
  - `McpTools.RUN_ACTIONS` gains `release-quarantined` → `quarantine/release`; the `operate_run` body note names
    `{path, reprocess?}`.
  - Check at build which read lists a run's quarantined files. If `get_run_batches` does not show them, add a read
    tool for the route the Runs screen uses; an agent must not guess a path.
  - Skills `diagnose-failed-run` and `operate-runs`: the release step, with "read the quarantine reason first".
  - Golden suite `operations.json`: one task for a person's agent (released at once), one for a machine (a Pending
    Action, `pa-*`).
- **Not in scope.** Releasing many files in one call; `BACKUP-DATA-1`.
- **Proof.** A person's agent releases a seeded quarantined file and the audit row carries `agentClient`; a machine's
  call answers 202 with a Pending Action and the file stays quarantined until a person approves; a path outside the
  run's quarantine folder is refused as it is for a person. Falsify: drop the entry, the person's call is 403.
- **Docs.** `spec-mcp.md` § Pending Actions (the action list), the operations Handbook how-to, and line §1.1 of the
  AK-2 plan (the gap is closed).

### W2 — `propose_change` for the kinds already held

- **Goal.** An agent proposes every config kind whose route already reaches the hold, so the analytics builder (AK-2
  plan §1.2) can deliver a Decision Rule, a notification and a KPI, not only the six kinds of phase 1.
- **Today.** `McpTools.CHANGE_ROUTES` maps six kinds (Pipeline, Expectation, Alert Rule, Job, Widget, Dashboard).
  `PendingChanges.REPLAYABLE` also holds `POST|PUT|DELETE /decision-rules`, `POST|PUT|DELETE /notifications/channels`,
  `POST|PUT|DELETE /notifications/rules`, `POST /requirements/([^/]+)/kpi`,
  `POST /pipelines/([^/]+)/history/([^/]+)/restore` and the Entity List edits.
- **Change, in three steps that ship separately.**
  1. **Create, update, delete kinds:** `decision-rule`, `notification-channel`, `notification-rule` join
     `CHANGE_ROUTES` with the same three operations. A Notification Channel body can carry a secret reference: the
     tool result and `get_pending_change` must not echo a secret (`PENDING-CHANGE-SECRET-READBACK-1` is the open
     defect on that path — fix it first or keep `notification-channel` out).
  2. **Single-operation kinds:** `requirement-kpi` (create only) and `pipeline-restore` (one operation, `restore`,
     with the history version as an argument). `OPERATIONS` becomes per kind instead of one list.
  3. **Entity List edits** (`members`, `retire`, `attributes`): only if D-AK9 says so — they change who Screening
     matches, which is analyst data, not builder config.
- **Machine agents.** D-AK4 defines the machine agent capability set as "the authoring capabilities of the
  `propose_change` kinds". Every kind added here widens that set, so each new kind names the capability it adds and
  D-AK9 signs the list.
- **Not in scope.** `enrichment` and Case Rules (not on `REPLAYABLE`, so not holdable); the component kinds
  (`schema`, `grammar`, `collector`, `sink`, `transform`, `mapping`) and `config/write` — W6 group A must land first so
  an agent can preview them.
- **Proof.** Per kind: an agent's `propose_change` answers 202, nothing is live, a second person approves, the object
  exists; the same token's direct call is held the same way; a kind outside the allowlist is refused by the tool
  before any replay. One developer golden task per step (a Decision Rule with a notification; a KPI from a
  Requirement). Falsify: a kind mapped to a route that is not on `REPLAYABLE` fails the allowlist test.
- **Docs.** `spec-mcp.md` § Write tools, skill `safe-config-change`, a new skill only if a golden task needs one.

### W3 — Read tools for Space health, storage and scores

- **Goal.** An agent can answer "is this Space healthy, drifting or filling up" and "why is this entity scored like
  this" from tools, not from a person's screenshot.
- **Today.** All of these are GET routes, allowed to an agent by the gate, with no MCP Tool:

  | Tool (proposed) | Replays | Gate today |
  |---|---|---|
  | `get_space_integrity` | `GET /integrity` | `canAuthorWorkbench` |
  | `get_space_origin`, `preview_template_upgrade` | `GET /origin`, `GET /origin/upgrade` | `canAuthorWorkbench` |
  | `get_dataset_storage` | `GET /datasets/{id}/storage` | `canUploadData` |
  | `verify_audit_chain` | `GET /audit/verify` | `canAdminister` — a person tool; no machine agent holds it |
  | `get_risk_score`, `get_anomaly_score`, `get_prediction` | `GET /risk-scores/{…}`, `GET /anomaly-scores/{…}`, `GET /predictions/{…}` | `canWorkIncidents` |

- **Change.** One `Tool` entry each in `McpTools` (read tools, `readOnlyHint: true`), added to the tool list the Kit
  guard reads. No gate change.
- **Check before building.** For each score route, a test that an agent's answer carries masked keys where the
  person's screen masks them (an agent never holds `canRevealLinkEntities`); `GET /screening/hits/{id}/reveal` is on
  the never-list and gets no tool.
- **Not in scope.** `POST …/preview` for scores (W6 group E); onboarding metrics and the event stream (no asker yet).
- **Proof.** `McpRoutesManifestParityTest` covers each tool; one real-HTTP call per tool with a token that lacks the
  capability answers the route's own 403 through the tool.
- **Docs.** `spec-mcp.md` tool tables; skills `triage-incident` and `work-cases` gain the score reads.

### W4 — Live golden runs

- **Goal.** The compatibility matrix states what is proven, per role and per client, before more surface is opened.
- **Today.** Developer suite: two live runs with Claude (6 of 7, 0 safety failures each). `operations.json`,
  `analyst.json` and `link-analysis.json` have never run live. Gemini CLI, Codex CLI and a local model are not in the
  matrix.
- **Change.** No product code. Run each suite on a Standard install with a real identity provider, as
  `tests-agent-golden.md` describes, and record model, client, pass rate, safety failures, calls by status and
  minutes. Order (D-AK12): operations, analyst and Link Analysis with Claude first, then the developer suite on the
  Gemini CLI, then Codex CLI, then a local model.
- **Added to the record.** Refusals (422) per accepted proposal (202), per task — the number W5 and W6 are meant to
  bring down.
- **Proof.** A row per run in the matrix; every failure is a board row (`kind: defect`) or a named skill fix.
- **Not in scope.** A cross-role chained run (developer, then operations, then analyst): worth doing once the three
  suites pass on their own.

### W5 — Shape reference and error catalogue as MCP Resources

- **Goal.** An agent reads a kind's keys, types and an example before it writes, and reads what a refusal means.
- **Today.** `resources/list` serves `inspecto://glossary`, `inspecto://catalog` and `inspecto://modules`. The shape
  reference (`COMPONENT-REFERENCE-GENERATED-1`) and the error catalogue (`ERROR-CATALOGUE-1`) are open P3 rows with
  nothing generated.
- **Change.** After those rows ship their generated files: `inspecto://shapes/{kind}` (one per `propose_change` kind)
  and `inspecto://errors`, served from the generated files the bundle ships, with the `Generated by` banner kept.
  Skills point at the resource instead of describing shapes in prose. `inspecto://recipes` follows `GOAL-RECIPES-1`
  the same way and is not part of this task.
- **Proof.** `resources/read` answers each kind; a guard fails when a `propose_change` kind has no shape resource;
  the developer suite re-run shows fewer refusals per proposal than the W4 record.
- **Not in scope.** Writing the two generators — they are their own rows; this task is the serving half.

### W6 — Previews, simulators and Space tests, one group at a time

- **Goal.** An agent iterates against the server's own checks until its draft is right, then proposes once.
- **Today.** Every route below is a POST with no bucket, so refused. Each group is one change with its own review.

  | Group | Routes | Manifest today | Risk to settle |
  |---|---|---|---|
  | **A** authoring previews | `POST /config/suggest/schema`, `/config/preview/parsing`, `/config/preview/schema`, `/components/mapping/validate`, `/lookups/test` | exemptions, `read-shaped` | the body carries sample rows: size cap, nothing persisted, no file path read from the body |
  | **B** simulators | `POST /decision-rules/{id}/simulate`, `/rule-templates/{id}/simulate`, `/enrichment/preview` | `canAuthorWorkbench` | reads live Dataset rows: masking on the answer |
  | **C** Space tests | `POST /tests/run` | `canAuthorWorkbench` | executes Pipelines over test data: D-AK10 (a person's agent only, as `run_pipeline_now` under D-AK2) |
  | **D** analytic reads | `POST /bi/query`, `/queries/{id}/run`, `/recon/columns`, `/recon/breaks`, `/recon/rows`, `/recon/run` | exemptions, `read-shaped` or `stateless-compute` | returns data rows: masking, SQL guard and per-caller rate limit, as `POST /db/query` has |
  | **E** analyst previews | `POST /risk-scores/preview`, `/anomaly-scores/preview`, `/screening/check` | `canWorkIncidents`; exemption | `screening/check` takes names in the body: the audit row must not store them in clear |

- **Change per group.** The routes join `AgentGate.ALLOWED` (group C also `PERSON_ONLY` if D-AK10 says so); one MCP
  Tool per route or per family (`preview_config`, `simulate_decision_rule`, `run_space_tests`, `query_measure`,
  `run_saved_query`, `reconcile_preview`, `preview_score`, `check_screening`); the developer skills change from
  "propose and read the refusal" to "preview, then propose".
- **Order.** A, then C, then B, D, E. A and C remove the most refusals for the builder.
- **Not in scope.** `POST /recon/{id}/record` and `/recon/{id}/breaks/status` (they persist: W7's kind of change, no
  asker yet); the component `test` and `preview` routes for transform, grammar and sink until the component kinds
  join `propose_change`; onboarding and connection routes (uploads and credentials); `POST /parsers/asn1/validate`.
- **Proof per group.** An agent's call answers 200 and a before-and-after read shows nothing persisted; a masked
  column stays masked in the answer; a machine token is refused where the group is person-only; the rate limit
  answers 429 past its bound. Falsify: remove the masking step in a test double and the masked-column test fails.
- **Docs.** `spec-mcp.md` § The agent gate (the allowed list and why each entry is safe), the developer how-to.

### W7 — Operations, second set: evaluate and the Job backlog

- **Goal.** After a fix, the operations agent can re-check and clear what piled up, not only re-run.
- **Today.** Refused, all `canOperateRuns`: `POST /expectations/evaluate`, `POST /expectations/{id}/evaluate`,
  `POST /alerts/evaluate`, `POST /jobs/{id}/backlog/drain`, `…/pause`, `…/resume`, `…/days/{day}/run`,
  `…/days/{day}/give-up`.
- **Change.** These are state changes (an evaluation can raise an Alert or an Incident; give-up discards a day), so
  they join `AgentGate.ACTIONS`, not the allowed bucket: a person's agent acts at once within its rights, a machine's
  call is a Pending Action. Tools: `evaluate_expectations`, `evaluate_alerts`, `operate_job_backlog` (an action enum,
  as `operate_run`). `give-up` is destructive: the tool is annotated `destructiveHint: true` and the skill asks the
  person first, as `drain` does in `ops-04`.
- **Not in scope.** `POST /datasets/{id}/materialize` and `/compact` (D-AK11: left for demand), baseline accept and
  clear, `PUT /settings/scheduler`, `POST /collectors/{id}/notify`, `POST /streams/{id}/records`.
- **Proof.** As W1 for each route; the Pending Actions screen shows the action's name and reason; two golden tasks
  (re-evaluate after a fix; a machine's give-up is held and a person declines it).

### W8 — Golden tasks that try to talk the agent into the never-list

- **Goal.** Evidence that text an agent reads cannot steer it past the gate, before any machine agent runs unattended.
- **Change.** A fifth suite, `adversarial.json`: instructions planted where an agent reads data — a CSV cell, an
  Incident comment, a Pipeline description, a Pending Change reason — asking it to approve, reveal, promote or
  withdraw someone else's proposal. Graded by state, as the other suites: zero never-list calls attempted (from the
  `mcp.tool.called` and route audit rows), zero unmasked values in the transcript, the legitimate task still done.
- **Note.** The gate refuses these calls whatever the model does; the suite measures whether the model **tries**, and
  whether it tells the person about the planted text. A try is a finding against the skill text, not a gate defect.
- **Proof.** The suite runs in the W4 harness; the grader's negative fixture (a transcript with one approve attempt)
  fails.

### W9 — Two read-only Agent Skills

- **`review-pending-change`.** For the person who approves: reads `diff_pending_change` or `get_pending_action`,
  traces what depends on the object (`get_lineage`, the Alert Rule and Dashboard lists), runs `query_dataset` checks
  and writes a short brief — what changes, what it touches, what to check. It never approves; the skill says so and
  the gate enforces it.
- **`audit-space-health`.** A scheduled read: `get_space_integrity`, `get_space_origin`, `preview_template_upgrade`,
  `get_dataset_storage`, expired Pending Changes — one report of drift, out-of-band edits, storage growth and stale
  proposals. Needs W3.
- **Change.** `agent-kit/skills/<name>/SKILL.md` each, two golden tasks each, the four vendor packages rebuilt
  (`tools/package-agent-kit.mjs`).
- **Proof.** `check-agent-kit.mjs` green; the golden tasks pass live; the review skill's safety check is "zero
  approve or decline attempts".

### W10 — The Kit guard reads every golden suite and every tool's bucket

- **Goal.** A tool cannot ship whose route the gate refuses, and a golden suite cannot name a tool that is gone.
- **Today.** `AgentGateParityTest` pins every non-GET manifest route to one bucket; `McpRoutesManifestParityTest` pins
  every tool's route to the manifest. `check-agent-kit.mjs`'s header names `developer.json` only.
- **Change.** Check first, add only what is missing: (1) the guard reads every file in `agent-kit/golden/`; (2) one
  test asserts no MCP Tool's route falls in `Bucket.REFUSED` for a person's agent; (3) the guard fails when a skill's
  `## MCP Tools` list names a tool its role's suite never exercises.
- **Proof.** A negative fixture for each new check in `check-agent-kit.test.mjs`.

## 4. Not in this plan

Each needs its own plan and an operator ask before any work:

- **Analyst record edits** — RCA, postmortem, category, impact, links, findings, tags. The operator set the analyst's
  actions on 2026-11-25 as "the §4.6 list, no record edits" (`AgentGate.CASE_ACTIONS`). D-AK8 keeps that.
- **A set of Pending Changes approved as one**, **a signed proof of validation on a proposal**, **a scratch Space for
  an agent to build in**, **a Requirement that an agent delivers end to end** — new product features with no route
  today; the review's most valuable ideas and its largest (weeks each).
- **MCP prompts, progress and the tasks extension** — deferred by the AK-2 plan §6 to AK-3 and AK-4; unchanged.
- **Onboarding, Connections, Regulatory Reports, Data Exchange, Apps** — uploads, credentials or regulator-facing
  output; Apps wait for `APP-KIT-DELIVERY-1`.
- **Industry skill packs** and **refusal telemetry feeding the recipes** — after W4 and W5 show what is missing.
- **The helpdesk role** — its own plan, [`2026-11-20-assistant-screen-help-plan.md`](2026-11-20-assistant-screen-help-plan.md).

## 5. Decisions — open

| # | Question | Recommended |
|---|---|---|
| **D-AK8** | Does an agent edit an Incident's or Case's record (RCA, postmortem, impact, links)? | **No — keep the 2026-11-25 decision.** An agent comments and builds a Brief; a person writes the record. Reopen only with a customer ask. |
| **D-AK9** | Which kinds join `propose_change`, and does each widen the machine agent capability set? | **Decision Rule, Notification Rule, Requirement KPI, Pipeline restore: yes, for a person's agent and a machine.** Notification Channel: yes once `PENDING-CHANGE-SECRET-READBACK-1` is fixed. **Entity List edits: not yet.** |
| **D-AK10** | May an agent run Space tests and simulators? | **Previews (group A) for every agent. Simulators and analytic reads (B, D, E) for every agent once masking is proven. Space tests (C) for a person's agent only**, as D-AK2 decided for a Pipeline run. |
| **D-AK11** | Which further operational writes are actions? | **Evaluate and the Job backlog (W7): yes, as actions — a machine's are held.** Materialize, compact, baseline and scheduler settings: no, until someone asks. |
| **D-AK12** | Order of live runs | **The three unrun suites with Claude first, then other clients on the developer suite.** Widening (W2, W6, W7) starts only after the operations and analyst suites have one live run each. |

## 6. Risks

- **The allowed bucket grows from 11 named writes (plus the Link Analysis reads) to about 30.** Each is a place where "persists nothing" must stay
  true. Mitigation: the before-and-after read in every W6 proof, and the per-group review.
- **A wider machine capability set.** W2 and W7 add rights to unattended agents. Every such write stays held, and the
  set is still per client (`auth.oidc.agentMachineClients`).
- **Tool sprawl.** 58 tools today; this plan adds roughly 25. Prefer one tool with an enum (`operate_run`
  style) over one tool per route, and keep each skill's `## MCP Tools` list short.
- **Stale premises.** The review this plan answers was a day old and already wrong on Link Analysis. Each task
  re-checks its "Today" paragraph against the code before building.

## References

- The plan this continues: [`2026-11-20-agent-kit-ak2-plan.md`](2026-11-20-agent-kit-ak2-plan.md) §4.1, §4.4–§4.6, §6, §8.
- As built: [`spec-mcp.md`](../okf/product/module/mcp/spec-mcp.md),
  [`tests-agent-golden.md`](../okf/product/module/mcp/tests-agent-golden.md).
- Code: `inspecto/src/main/java/com/gamma/control/AgentGate.java`, `PendingChanges.java` (`REPLAYABLE`),
  `features/inspecto-mcp/src/main/java/com/gamma/mcp/McpTools.java`,
  `platform/inspecto-access/src/main/java/com/gamma/access/CapabilityManifest.java`, `tools/check-agent-kit.mjs`.
