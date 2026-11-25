---
type: Spec
title: Agent case actions and record edits (AK-2e, W11) — design and as-built
description: What an AI agent may do to Incidents, Cases and Alerts through the MCP endpoint — comment, Brief, accept, assign, resolve, transition, open a Case, and write the record (postmortem, category, impact, Findings, RCA, correlation links) — the gates each route keeps, how a machine agent's action is held as a Pending Action, attribution, the MCP Tools, the golden tasks and the security review.
tags: [agent-kit, mcp, agents, incidents, cases, security]
timestamp: 2026-11-26T00:00:00Z
item: module.mcp
audience: internal
status: current
verified: 2026-11-26 code-reviewed
code: features/inspecto-mcp
---

# Agent case actions and record edits

Split out of [spec-mcp.md](spec-mcp.md) on 2026-11-26 (size cap). The agent gate, Pending Actions and identity
sections there are the ground rules these build on.

## Case actions (AK-2e)

Phase 3, the Incident and Case analyst (plan §1.1, §4.6 analyst rows, §6.4). The operator's answers of 2026-11-25:
exactly the §4.6 list (no Incident record edits — reversed the same day by D-AK8, § Record edits (W11) below),
a new Case for people only (superseded by W2 step 3, operator 2026-11-25: a machine agent's new Case is held as a
Pending Action), and `CASE-TRANSITION-FOUR-EYES-1` left a
board row. No new machinery: the routes join the action bucket, so the gate and the [Pending Action](spec-mcp.md#pending-actions-ak-2d)
hold above apply unchanged.

- **The routes** (`AgentGate.CASE_ACTIONS`), each still on its own gate: `POST /objects/{id}/comments` (the
  `collaboration` exemption — anyone who can see the object), `POST /incidents/{id}/briefs`,
  `POST /objects/{id}/ack|assign|resolve|transition`, `POST /alerts/{id}/ack|resolve` (`canWorkIncidents`), and
  `POST /cases/from-entities` (`canManageIncidents`). `AgentGateParityTest` pins the list and each route's gate.
- **A person's agent** takes them at once, within the person's rights, audited with `agent_client`; the object's own
  history records the person for an ack, assign, resolve or transition (`actorOf`: the Subject, never a body `actor`).
- **A machine agent's** are held as Pending Actions, approved by the change approvers and replayed as the machine — a
  comment it asked for is authored `client:<id>`. Its new Case from entities is held the same way: until W2 step 3 it
  was refused (403, no record) because `canManageIncidents` was not in `Roles.AGENT_MACHINE_CAPABILITIES`; the
  operator granted it on 2026-11-25 (§ Entity List edits), and `ControlApiAgentCaseActionsTest.
  aMachinesNewCaseFromEntitiesIsHeldNotRun` pins the hold. A comment is
  exempt from a capability, so a machine's hold on it needs `canWorkIncidents` (`AgentGate.MACHINE_HOLD_CAPABILITY`):
  otherwise a machine with no right could queue comments on any id (phase-3 security review, plan §6.5).
- **A Brief** files at most a `pending` Action Request, whose makers are the asking person and the Brief's agent
  identity; its own four-eyes decide gate refuses both, and an agent never decides one (the never-list).
- **Analyst MCP Tools** (`McpTools.CASE_TOOLS`, all `write: true`): `add_comment`, `build_incident_brief`,
  `work_incident_or_case` (`action` one of `ack`, `assign`, `resolve`, `transition` → `POST /objects/{id}/…`),
  `work_alert` (`ack`, `resolve`) and `open_case_from_entities`, each with a one-line `reason`. The reads were
  already there (`list_incidents`, `get_incident`, `get_incident_briefs`, `list_alerts`).
  `McpToolRoutesTest.everyCaseToolReplaysACaseActionAndEveryCaseActionHasATool` pins tools to routes both ways.
- **Agent Skills and golden tasks:** `triage-incident` (read, Brief, comment, accept, assign — never close) and
  `work-cases` (Workflow moves, resolve, Alerts, open a Case — always on the person's yes); the analyst suite
  `agent-kit/golden/analyst.json` (seven tasks since W2: read without acting, Brief and comment, accept and assign,
  resolve a Case, a machine's resolve held, a machine's new Case held — until W2 step 3 "a machine never opens a
  Case" — and a machine's Entity List member held; and since W11 two record edits, § Record edits (W11)).
  `ACTION_PATH` in `tools/run-agent-golden.mjs` covers
  the case routes. Its live run is open.
- **The screen:** unchanged — the *Pending Actions* tab lists case actions too, its detail showing the comment text or
  Workflow move it will run.
- **Tests:** `ControlApiAgentCaseActionsTest` (`inspecto-case-management`, real HTTP: a person's agent comments on,
  accepts and assigns an Incident at once and its row names the agent client, a business user's agent is 403 and
  nothing is held; a machine's Case transition is 202 and nothing changed, its approve 403, the detail shows the
  move, another person's approval runs it; a machine's comment is held and on approval is the machine's; a machine's
  new Case is 403 with no record; a machine without `canWorkIncidents` cannot queue a comment), `AgentGateParityTest`,
  `McpToolRoutesTest`.

## Record edits (W11)

Widening plan W11, D-AK8 as signed 2026-11-25 (not as recommended): an agent writes an Incident's or Case's record —
what a regulator may read later — under the case-action rules above, unchanged.

- **The routes** join `AgentGate.CASE_ACTIONS`, each on its own gate: `PUT /objects/{id}/postmortem`, `/category`,
  `/impact` (`canWorkIncidents`; each writes its one attribute and answers 422 on any other key) and
  `PUT /objects/{id}/findings`, `POST /objects/{id}/rca`, `POST` and `DELETE /objects/{id}/links` (the
  `collaboration` exemption). The four exempt routes are in `AgentGate.MACHINE_HOLD_CAPABILITY` (`canWorkIncidents`),
  as the comment is, so a machine without that right is refused before the hold and leaves no record.
  `AgentGateParityTest` pins the sixteen case actions with their gates, and that `PATCH /objects/{id}`, merge and split
  stay refused. Tags, merge, split and the Workflow transition guard (`CASE-TRANSITION-FOUR-EYES-1`) are out of scope.
- **Attribution.** A link, an unlink and an RCA skeleton record the signed-in Subject (`ObjectRoutes.actorOf`), never a
  body or query `actor`; before W11 those three took the body's (the query's, for an unlink) even for a signed-in caller.
  The postmortem, category, impact and Findings already recorded `ApiContext.actor`.
- **Pending Actions.** A PUT and a DELETE replay as a POST does (`ControlApi.replay` takes any method; the query rides
  the replayed URI). The duplicate check compares the path with its query, so two unlinks of different Incidents are
  two Pending Actions; the approver's detail shows the whole request, `?to=` included.
- **MCP Tool** `edit_incident_or_case_record` (`McpTools.CASE_TOOLS`, `write: true`, `destructiveHint: false`):
  `id`, `action` one of `postmortem`, `category`, `impact`, `findings` (→ `PUT /objects/{id}/…`), `rca`, `link`
  (→ `POST`), `unlink` (→ `DELETE /objects/{id}/links?to=&relationship=`, from a body `{to, relationship?}` of ids and
  nothing else), the route's own `body`, and a one-line `reason`. `McpTools.RECORD_EDITS` maps each action.
- **Agent Skills and golden tasks:** `work-cases` § *Write the record* (only from evidence the agent read; read the
  stored impact first, as `impact` replaces it; ask first, `MCP-AGENT-POSTURE-1`; name every field changed) and
  `triage-incident` step 5; the analyst suite gains `an-08-record-postmortem-impact` (a person's agent writes both) and
  `an-09-machine-impact-held`. The grader's `isAction` accepts the PUT routes and `DELETE …/links`. Their live run is open.
- **Security review (2026-11-25).** What an agent may now write and who reads it: the postmortem, category and impact
  feed the Incident's resolution gate, Accept and the impact ledger a regulator reads. (1) No right is widened: each route
  keeps its own gate, and a person's agent never exceeds the person. (2) A machine's record edit is always held and
  replays as the machine, so every value it wrote passed another person's approval with the body in view. (3)
  Attribution: the body-`actor` re-attribution of link, unlink and RCA was fixed (above). (4) The exempt routes' machine
  hold needs `canWorkIncidents`, so a right-less machine cannot fill the approvers' queue. (5) An unlink still meets the
  legal-hold refusal (`ObjectService.unlink`). Residual: a machine's held edit to an object outside its data scope is
  recorded and refused only at approval (the hold runs before the scope guard, as for every case action since AK-2e);
  the approver sees the id. Accepted, as phase 3 did. Also low and accepted (verification, 2026-11-26): the duplicate
  check compares the query and body as text, so a reordered query or a reformatted body is a second Pending Action (as
  for bodies before W11); and a machine that loses `canWorkIncidents` after its Findings, RCA or link edit was held still
  has it applied on approval — the approved replay skips the hold, as for a comment.
- **Tests:** `ControlApiAgentRecordEditsTest` (`inspecto-case-management`, real HTTP, per route: a person's agent writes
  at once and the audit row names `agent_client`; a business user's agent gets exactly the business user's answer — 403
  on the three `canWorkIncidents` routes; a machine's edit is 202, nothing changed, the approver sees the request with
  its query, another person's approval applies it; two unlinks of different Incidents are two Pending Actions; a machine
  without `canWorkIncidents` is 403 on every route with no record; a body or query `actor` does not re-attribute a link
  or RCA), `AgentGateParityTest`, `McpToolRoutesTest.unlinkCarriesItsOtherEndInTheQuery`, `tools/run-agent-golden.test.mjs`.
