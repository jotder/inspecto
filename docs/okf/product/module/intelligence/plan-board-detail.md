---
type: Plan
title: module.intelligence - board row detail
description: The narrative behind module.intelligence's long rows on the board (docs/BACKLOG.md), moved here verbatim so each board row stays a short summary.
tags: [module, intelligence, plan, backlog]
timestamp: 2026-11-19T00:00:00Z
---

# module.intelligence - board row detail

Each section below is one row of [the board](../../../../BACKLOG.md), headed by its id or name. The board row keeps
the row's priority, a one-line summary of what is open, its state and its tag, and stays the only list of open
work; this page keeps the narrative the row carried, verbatim as it stood on the board on 2026-11-19
(`INFO-RESTRUCTURE-1` W3). Close or narrow the board row first, then trim its section here.

## Open rows

### AGENTIC-INVESTIGATION-1

- `AGENTIC-INVESTIGATION-1` — **an agent that works an Incident: explains why it fired, gathers evidence, quantifies value at risk and proposes the next action for a human to approve** (competitive gap AA, 2026-10-17: Mobileum RAID Agentic, Subex AI Agent Squads). Builds on the Assistant / intelligence agents and the Decision Kernel Consequence registry; proposals go through four-eyes (no autonomous Consequence). **Design 2026-10-18; S0 done; S1 BUILT 2026-11-01** — `POST|GET /incidents/{id}/briefs` (`inspecto-action-requests`): a deterministic Incident Brief (why it fired, cited evidence incl. scope-filtered similar Incidents, value at risk from the declared Impact ledger, never a guess) whose optional proposed next action is filed as a `pending` Action Request with the starting human and the agent as makers, so four-eyes refuses both (falsified test). **1 of 6 slices built (S1); open:** S2 Incident page panel, S3 rule-level `valueAtRisk` declaration, S4 Consequence-kind proposals + per-Space allow-list, S5 feedback loop + purge cascade, the model-written prose layer, S6 (could) scheduled top-N. Decisions §8 and S1-1…S1-5 (hub plan §10.1) assistant-decided, pending operator confirmation. → `okf/product/module/intelligence/plan-agentic-investigation.md` · `okf/product/layer/product/brochure-competitive-matrix.md`
