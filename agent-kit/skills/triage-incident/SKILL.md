---
name: triage-incident
description: Triage an Inspecto Incident - read it and its evidence, build an Incident Brief, comment, accept it and assign it to the right person. Use when the person asks what an Incident is about, to triage new Incidents, to write a Brief, or to hand an Incident to someone. Covers what happens when an action is held as a Pending Action.
---

# Triage an Incident

Read first, then act once. Triage changes who works an Incident and what its thread says — it never closes one; closing
is `work-cases`.

## Who you are decides what happens

- **Signed in as a person** (an agent client acting for them): a comment, a Brief, accepting or an assignment runs **at
  once**, within that person's own rights (`canWorkIncidents`; a comment needs only that they can see the Incident).
  The audit row names the person and your agent client.
- **An unattended machine agent**: every one of them is **held** as a **Pending Action** (`pa-…`) for a person to
  approve — the answer is `202` with `ran: false`. Nothing changed yet.

## Steps

1. **Find it.** `list_incidents` (filter by `status` or `severity`), then `get_incident` for its fields, history and
   who has it. An Incident you cannot see answers 404 — never guess at one.
2. **Read the evidence.** `get_incident_briefs` for Briefs already written; for a run-raised Incident, follow
   `diagnose-failed-run`. `list_alerts` shows the Alert that raised it. For an Incident a score raised, read the
   entity's score and why: `get_risk_score` (the factors that add up to it), `get_anomaly_score` (the per-feature
   explanation and recent runs) or `get_prediction` (a Churn Score or Customer Segment, with its explanation). The
   key comes back masked; `get_risk_score` takes that masked key back as is.
3. **Build a Brief** when none says why it opened: `build_incident_brief` with the Incident id. It gathers the evidence,
   similar Incidents and the value at risk. Add `body: {"propose": {...}}` only when the person wants the next step
   filed as an Action Request — a person other than the one who asked must approve it, never you.
4. **Say what you found** in a comment: `add_comment` with one short paragraph — the cause, the evidence, the next step.
   Name a Brief by its id rather than pasting it.
5. **Accept and assign** only on the person's word: `work_incident_or_case` `action: transition` with
   `body: {"action": "accept"}` (a new Incident moves from IDENTIFIED to DIAGNOSING), then `action: assign` with
   `body: {"assignee": "<user>"}`. `action: ack` is for the object types whose Workflow has it — an Incident's does
   not, and answers 422.

Always pass a one-line `reason` (at most 500 characters): the audit row keeps it, and an approver reads it first.

## Answers

| Answer | Meaning | Do |
|---|---|---|
| 200 | it ran | report what changed |
| 202 with a Pending Action | held for a person (you are a machine agent) | report the `pa-…` id; stop; `get_pending_action` shows the decision later |
| 409 "already pending approval (pa-…)" | the same action is already waiting | do not ask again; report the id |
| 403 "missing capability …" | the person lacks `canWorkIncidents` | tell the person; never look for another route |
| 404 | no such Incident, or one the person may not see | check the id with `list_incidents` |
| 422 | not an Incident (a Brief), or a move its Workflow does not allow | read `get_incident` again; do nothing else |
| 409 | the Incident changed under you, or it is under a legal hold | read it again before anything else |

`withdraw_action` takes back a Pending Action you asked for while it still waits.

## Never

- Resolve, close or transition an Incident here — that is `work-cases`, and only on the person's word.
- Approve or decline a Pending Action, a Pending Change or an Action Request — a person does.
- Paste a masked value into a comment: what the Incident shows you masked stays masked.
- Assign to someone the person did not name.

## MCP Tools

- `list_incidents`, `get_incident`, `get_incident_briefs`, `list_alerts` — the Incident and what raised it
- `get_risk_score`, `get_anomaly_score`, `get_prediction` — an entity's score and its explanation (key masked)
- `build_incident_brief` — investigate and save a Brief, optionally filing its next step as an Action Request
- `add_comment` — say what you found on the Incident's thread
- `work_incident_or_case` — accept or assign it
- `get_pending_action`, `withdraw_action` — a held action's decision, or take it back
