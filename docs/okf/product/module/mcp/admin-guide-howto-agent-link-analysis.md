---
type: How-to
title: "Let an AI agent analyse a Case in Link Analysis"
description: "Let an AI agent explore links, paths and patterns in Link Analysis and put what it finds in a Draft: signed in as a person it queries, drafts and starts graph runs within that person's rights at once; as an unattended machine agent a lead has added to an Investigation, every Draft write waits as a Pending Action. No agent promotes a Draft or reveals a masked key."
item: module.mcp
audience: external
status: current
verified: 2026-11-25 code-reviewed
code: features/inspecto-mcp
---

# Let an AI agent analyse a Case in Link Analysis

## Goal

An AI agent does a link analyst's legwork: it explores the entities a Case names, expands their links a step at a
time, finds paths and patterns between them, runs a graph analysis, and writes what matters into its own **Draft** of
an Investigation. A person reads the Draft and promotes it — or not. Who the agent is decides what happens:

- **Signed in as a person** (through an agent client): queries, Draft work, attaching an Investigation to a Case and
  graph runs run **at once**, within that person's own rights. The audit row names the person and the agent client.
- **An unattended machine agent**: queries run at once; every Draft write is **held** as a **Pending Action** for a
  person to approve, and only on an Investigation a lead added the machine to; so is attaching an Investigation to a
  Case (when its role grants `canManageIncidents`). It never starts a graph run.

No agent promotes a Draft, edits an Investigation directly, reveals a masked key or approves an expand. Every key an
agent sees is masked when the Space masks.

## Before you start

- **Edition:** Standard, Advanced or Preview with sign-in through an OpenID Connect provider, and Link Analysis with
  the Space's index built (graph runs need it).
- **A person's agent:** set up as in *Let an AI agent propose changes*. The person needs membership of the
  Investigation (lead or analyst) and *Manage Incidents and Cases* (`canManageIncidents`) for Draft work and to attach
  to a Case; `canRunLinkGraphAnalysis` for graph runs.
- **A machine agent:** set up as in *Let an AI agent operate runs* (listed in `auth.oidc.agentMachineClients`), with a
  Space role that grants **`canDraftInvestigations`** by name — *Manage Incidents and Cases* does not carry over to a
  machine.
- **An approver:** a person who holds `canApproveChanges` and is on the Space's approver roster.

## Steps

1. **Give the agent the skill** `analyse-in-link-analysis` in the bundle's `agent-kit/skills` folder (the vendor
   packages carry it already).
2. **For a machine agent, add it to the Investigation.** As the Investigation's lead, open **Members** and add the
   machine (`client:<id>`) as an analyst. It sees that Investigation and no other; removing the member cuts it off.
3. **Ask for an outcome**, for example "how is this account linked to the flagged merchant?", then "put that chain in
   a Draft for me". The agent previews an expand before running it and reports the Draft id.
4. **Promote the Draft yourself** (or discard it) from the Investigation's **Drafts** panel, as for any analyst's
   Draft — the promote four-eyes still applies.
5. **A machine agent's Draft writes wait.** On the **Pending Changes** screen, **Pending Actions** tab, each row names
   the write (`POST /inv/investigations/<id>/drafts/<draft>/ops`) and the detail shows the op it will add. Approve to
   run it as the machine, with every check made again, or decline it.

## Check it worked

The Investigation's Drafts list names the agent's Draft and its actor:

```check
GET /inv/investigations
as admin
expect status 200
```

After a person's agent drafts, the Draft's actor is the person and the audit rows of its writes name the agent client.
After a machine agent asks, its Pending Action is `pending`; once approved, the Draft's actor is `client:<id>`.

## What can go wrong

- 404 on an Investigation — the agent's person (or the machine) is not a member. Have a lead add it.
- 403 "missing capability canDraftInvestigations" — the machine's role does not grant it by name.
- 403 on promote, reveal or an expand decision — refused to every agent, by design.
- 409 on a Draft write — the Draft is behind the Investigation (rebase it) or closed, or the agent already has one
  open on that Investigation.
- 429 — a heavy query or expand is queued out; the agent waits and tries once more.
- An approved machine write ends `failed` (404) — the machine was removed from the Investigation before approval.

## Next

- [Let an AI agent work Incidents and Cases](admin-guide-howto-agent-work-incidents.md)
- [Let an AI agent operate runs](admin-guide-howto-agent-operate-runs.md)
