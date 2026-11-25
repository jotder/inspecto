---
name: analyse-in-link-analysis
description: Analyse an Inspecto Case or Incident in Link Analysis - explore the entities it names, expand their links a step at a time, find paths and patterns between them, run a graph analysis, and put what matters in a Draft for a person to promote. Use when the person asks who is linked to an entity, how two entities connect, whether a ring or pattern exists, or to build up an Investigation. Covers masking and what happens when a Draft write is held.
---

# Analyse in Link Analysis

Explore first, write later — and write only into **your own Draft**. A person promotes a Draft into the Investigation;
you never do, and you never reveal a masked key.

## Who you are decides what happens

- **Signed in as a person** (an agent client acting for them): queries run at once; opening and working in a Draft, and
  attaching an Investigation to a Case, run **at once** within the person's rights (a Draft needs membership of the
  Investigation; attaching needs `canManageIncidents`); starting a graph run needs `canRunLinkGraphAnalysis`.
- **An unattended machine agent**: queries run at once. A Draft write is **held** as a **Pending Action** (`pa-…`)
  for a person to approve, and only on an Investigation a lead added the machine to; so is attaching an Investigation
  to a Case, when its role grants `canManageIncidents`. It never starts a graph run (403).

## Masking

Every key you see may be masked (`masked:…`). Use it as it is — pass it back to `explore_entity`, `run_link_query` and
your Draft ops, which all accept it. Never ask anyone for the raw value and never try to reveal it: that is a person's
act, audited per entity.

## Steps

1. **Start from the Case.** `get_incident` (or the Case) names the entities; `list_investigations` and
   `get_investigation` `view: case` show whether an Investigation already serves it. Read its `working-set` first.
2. **Explore an entity**: `explore_entity` `view: profile`, then `view: summary` with the `dataset` it sits in; `run_link_query` `kind: case-history` for
   the Incidents and Cases that name it.
3. **Expand a step at a time**: `run_link_query` `kind: neighbors-preview` (how much one step adds), then
   `kind: neighbors`. Stop and say so when a step would add more than the person can read.
4. **Connect and look for shape**: `kind: paths` between two entities; `kind: branching` or `kind: temporal` for a
   motif or a burst; `run_preset` for the Space's saved questions. Nothing here is saved.
5. **A graph analysis** (a person's agent): `list_graph_algorithms`, then `start_graph_run` with
   `{investigationId, algorithm, params}`; read it with `get_graph_run`. Report the top results, not the whole list.
6. **Write it down in your Draft** only when the person wants it kept: `open_draft` on the Investigation, then
   `work_draft` `action: ops` with one op per call (`{"op": "seed", "ids": [...]}`, `{"op": "expand"}`,
   `{"op": "connect", …}`, `{"op": "annotate", …}`, `{"op": "exclude", …}`); `action: undo` takes the last one back;
   `action: discard` drops the Draft. Read it back with `get_draft` `view: working-set`.
7. **Hand it over**: report the Draft id and what it adds, and say a person must promote it. If the Investigation
   moved on, `get_draft` `view: conflicts` shows what a rebase would meet; `work_draft` `action: rebase` only on the
   person's word.

## Answers

| Answer | Meaning | Do |
|---|---|---|
| 200 / 201 | it ran | report what you found or what the Draft now holds |
| 202 with a Pending Action | held for a person (you are a machine agent) | report the `pa-…` id; stop |
| 404 on an Investigation | you are not a member of it (or it does not exist) | ask the person to have a lead add you; never guess ids |
| 403 on a Draft write | a reviewer role, or another member's Draft | work only in your own Draft |
| 403 "missing capability …" | the person (or your role) lacks the right | tell the person; never look for another route |
| 409 | your Draft is behind the Investigation, closed, or you already have one open | read `get_draft`, or reuse your open Draft |
| 429 | a heavy query or expand is queued out | wait and try once more; do not loop |

## Never

- Promote a Draft, approve or deny an expand, or reveal a masked key — a person does.
- Edit an Investigation directly, change its members, hand it off, or share it.
- Expand without a preview when the step could be large, or loop expands to "see everything".

## MCP Tools

- `list_investigations`, `get_investigation`, `get_draft` — the Investigation and your Draft
- `explore_entity`, `run_link_query`, `run_preset` — queries; nothing is saved
- `list_graph_algorithms`, `start_graph_run`, `get_graph_run`, `list_graph_runs`, `cancel_graph_run` — graph analysis
- `open_draft`, `work_draft` — your Draft
- `attach_investigation_to_case` — link an Investigation to its Case (a person's agent)
- `get_incident`, `get_pending_action`, `withdraw_action` — the Case, and a held write's decision
