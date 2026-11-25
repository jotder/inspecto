---
type: How-to
title: "Let an AI agent work Incidents and Cases"
description: "Let an AI agent triage Incidents and work Cases on a Standard install: signed in as a person it comments, writes Briefs, accepts, assigns, resolves, writes the record (postmortem, category, impact, Findings, RCA, links) and opens Cases within that person's rights at once; as an unattended machine agent every such action waits as a Pending Action for a person to approve, opening a Case included."
item: module.mcp
audience: external
status: current
verified: 2026-11-25 code-reviewed
code: features/inspecto-mcp
---

# Let an AI agent work Incidents and Cases

## Goal

An AI agent does an Incident and Case analyst's work: it reads an Incident and its evidence, writes an Incident Brief,
comments, accepts and assigns the Incident, moves an Incident or Case through its Workflow, writes its record — the
postmortem, the category, the financial impact, the Findings values, an RCA skeleton and the correlation links to other
Incidents and Cases — resolves it, clears an Alert, and opens a Case over the entities an investigation found. Who the agent is decides what happens:

- **Signed in as a person** (through an agent client): the action runs **at once**, within that person's own rights,
  exactly as if the person had pressed the button. The audit row names the person and the agent client.
- **An unattended machine agent**: every action is **held** as a **Pending Action** and runs only once a person
  approves it — opening a Case too.

An agent never approves anything — a Pending Action, a Pending Change or the Action Request a Brief proposes — and never
merges, splits, tags or edits an Incident's priority or severity.

## Before you start

- **Edition:** Standard, Advanced or Preview with sign-in through an OpenID Connect provider, and the operational
  objects module (Incidents and Cases).
- **A person's agent:** set up as in *Let an AI agent propose changes*. The person needs `canWorkIncidents` to work
  Incidents and Cases — the postmortem, category and impact included — and `canManageIncidents` to open a Case.
  Commenting, the Findings values, an RCA skeleton and a link need only that they can see the Incident or Case.
- **A machine agent:** set up as in *Let an AI agent operate runs* (listed in `auth.oidc.agentMachineClients`), holding
  a role that grants `canWorkIncidents` — every action of a machine needs it, a comment, Findings, RCA or link
  included, and `canManageIncidents` to ask for a new Case. Since 2026-11-25 a machine
  agent keeps `canManageIncidents` and `canManageExclusionCategories` when its role grants them; every write stays
  held, but **`canManageExclusionCategories` also lets it read a restricted Entity List's members** (the Entity List
  itself, Screening hits, Investigation replays). Grant it to a machine role only if that is acceptable.
- **An approver:** a person who holds `canApproveChanges` and is on the Space's approver roster.

## Steps

1. **Give the agent the analyst skills** — `triage-incident` and `work-cases` in the bundle's `agent-kit/skills`
   folder (the vendor packages carry them already).
2. **Ask for an outcome**, for example "triage the new Incidents and hand the feed one to dana", or "the investigation
   of this Case found nothing — resolve it". The agent reads first (`list_incidents`, `get_incident`,
   `get_incident_briefs`) and asks before it resolves, closes or opens a Case. To have it write the record, give it the
   facts or point it at the evidence — "record the postmortem and the 1250 EUR the invoice confirms" — it writes only
   what it read, and says which fields it changed. The impact is replaced as a whole, so it reads the stored one first.
3. **A machine agent's action waits.** It reports a Pending Action id (`pa-…`). On the **Pending Changes** screen,
   open the **Pending Actions** tab: each row names the action (`POST /objects/<id>/resolve`), the machine that asked
   and its reason; the detail shows the request it will run, comment text and Workflow move included. Approve to run
   it — it runs as that machine, with every check made again — or decline it. A record edit shows its body (the
   postmortem text, the impact amounts) and an unlink shows the Incident it removes (`?to=`) in the request.

## Check it worked

The Pending Actions list answers, so the approver can see what machines asked for:

```check
GET /pending-actions
as admin
expect status 200
```

After a person's agent acts, the Incident's history and comments show the change, and the audit search for its agent
client shows the action's own row (for example `/objects/<id>/assign`, status 200) with the person as the actor. After
a machine agent asks, the list holds a Pending Action with status `pending`; once approved, its status is `approved`
and a comment it asked for is authored by the machine (`client:<id>`). A record edit's row is
`PUT /objects/<id>/postmortem` (or `category`, `impact`, `findings`), `POST /objects/<id>/rca`, or `POST` or `DELETE
/objects/<id>/links`; a link, unlink or RCA names the person who signed in, never a name in the request.

## What can go wrong

- 403 "missing capability canWorkIncidents" (or `canManageIncidents` for a new Case) — the person, or the machine's
  role, lacks the right. A machine agent whose role grants `canManageIncidents` has its new Case held as a Pending
  Action instead.
- 404 — no such Incident or Case, or one the person may not see.
- 422 "illegal transition" — the Workflow does not allow that move from the current status (a new Incident is
  `accept`ed, not acknowledged), or an Incident's resolve lacks its disposition or postmortem.
- 422 on a record edit — the body carries a key the route does not take (each writes one part and nothing else), an
  impact amount without a currency, or a Findings field the Case type does not have.
- 409 on an impact — the Incident or Case is closed; its books are shut.
- 409 "already pending approval (pa-…)" — the same action is already waiting; approve or decline that one.
- An approved action ends `stale` or `failed` — the Incident or Case moved on before approval; nothing ran.

## Next

- [Let an AI agent operate runs](admin-guide-howto-agent-operate-runs.md)
- [Let an AI agent analyse a Case in Link Analysis](admin-guide-howto-agent-link-analysis.md)
- [Let an AI agent propose changes](admin-guide-howto-agent-propose-changes.md)
