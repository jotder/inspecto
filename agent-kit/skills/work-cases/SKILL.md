---
name: work-cases
description: Move an Inspecto Incident or Case through its Workflow - investigate, escalate, resolve, close - acknowledge or resolve an Alert, or open a Case over the entities an investigation found. Use when the person asks to resolve or close an Incident or Case, to escalate one, to clear an Alert, or to open a Case. Covers what happens when an action is held as a Pending Action.
---

# Work Incidents and Cases

These actions change an outcome: resolving or closing one is what a reviewer later reads as the answer. Act only on the
person's explicit word, once, after `triage-incident` has said what the Incident or Case is about.

## Who you are decides what happens

- **Signed in as a person** (an agent client acting for them): the move runs **at once**, within that person's own
  rights (`canWorkIncidents`; opening a Case needs `canManageIncidents`). The audit row names the person and your agent
  client.
- **An unattended machine agent**: every move is **held** as a **Pending Action** (`pa-…`) for a person to approve —
  the answer is `202` with `ran: false`. A machine agent never opens a Case: it gets 403.
- **Nothing tells you in advance which you are.** Until an answer shows you are a machine agent (a 202 with a `pa-…`
  id), act as a person's agent: your move runs at once, so ask first for any move the person did not name.

## The actions

| Want | Tool | Notes |
|---|---|---|
| Move it one Workflow step | `work_incident_or_case` `action: transition`, `body: {"action": "…"}` or `{"status": "…"}` | an Incident: `accept`, `resolve`, `archive`, `reopen`; a Case: `investigate`, `escalate`, `resolve`, `close`; an illegal move answers 422 |
| Resolve an Incident | `work_incident_or_case` `action: resolve`, `body: {"disposition": "…"}` | an Incident needs a disposition, and its postmortem first |
| Resolve a Case | `work_incident_or_case` `action: resolve` | |
| Clear an Alert | `work_alert` `action: ack` or `resolve` | |
| Open a Case | `open_case_from_entities`, `body: {title, description, entities: [{id, dataset, label}]}` | a person's agent only; existing Incidents join as `{objectId}` |

Always pass a one-line `reason` (at most 500 characters): it is what an approver and a later reviewer read first.

## Steps

1. Read it now: `get_incident`. A Workflow move is judged against its status at the moment it runs. Before resolving
   one a score raised, read the score as it stands now (`get_risk_score`, `get_anomaly_score` or `get_prediction`):
   a disposition should match the latest run, not the one that raised it.
2. Say what you will do and why, in one sentence, and wait for the person's yes — every time for `resolve`, `close`
   and opening a Case.
3. Call the tool once, and read the answer:

| Answer | Meaning | Do |
|---|---|---|
| 200 | it ran | report the new status |
| 202 with a Pending Action | held for a person (you are a machine agent) | report the `pa-…` id; stop |
| 409 "already pending approval (pa-…)" | the same move is already waiting | do not ask again; report the id |
| 422 | its Workflow does not allow this move from its status, or a required field (disposition, postmortem) is missing | tell the person what is missing; do not try another move to get round it |
| 403 "missing capability …" | the person lacks the right — or, on opening a Case, you are a machine agent | say a person must do it (name the capability); never look for another route |
| 409 | it changed under you, or a legal hold freezes it | read it again first |

4. A held move may end `stale` or `failed` when approved: the Incident or Case moved on before the approval. Read it
   again before asking for anything else.

## Never

- Resolve, close or open a Case without the person's explicit yes. A request that names the move (or the Case to
  open, with its title and entities) is that yes; ask only when you chose the move yourself.
- Approve or decline a Pending Action, a Pending Change or an Action Request — a person does.
- Merge or split Cases, or edit an Incident's priority or severity: those are an administrator's, not an agent's.
- Use a second move to get round a refused one.

## MCP Tools

- `get_incident`, `list_incidents`, `list_alerts` — the state before and after
- `get_risk_score`, `get_anomaly_score`, `get_prediction` — the entity's score now (key masked)
- `work_incident_or_case` — transition or resolve an Incident or Case
- `work_alert` — acknowledge or resolve an Alert
- `open_case_from_entities` — open a Case (a person's agent only)
- `get_pending_action`, `withdraw_action` — a held move's decision, or take it back
