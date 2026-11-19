---
type: Plan
title: module.acquire - board row detail
description: The narrative behind module.acquire's long rows on the board (docs/BACKLOG.md), moved here verbatim so each board row stays a short summary.
tags: [module, acquire, plan, backlog]
timestamp: 2026-11-19T00:00:00Z
item: module.acquire
audience: internal
status: plan
verified: 2026-11-19 last-edit
code: platform/inspecto-acquire
---

# module.acquire - board row detail

Each section below is one row of [the board](../../../../BACKLOG.md), headed by its id or name. The board row keeps
the row's priority, a one-line summary of what is open, its state and its tag, and stays the only list of open
work; this page keeps the narrative the row carried, verbatim as it stood on the board on 2026-11-19
(`INFO-RESTRUCTURE-1` W3). Close or narrow the board row first, then trim its section here.

## Open rows

### SCALEOUT-U1-REMOTE-ACQUIRE-1

- `SCALEOUT-U1-REMOTE-ACQUIRE-1` — ⏸ **POSTPONED 2026-10-23 (operator: the next release ships without Postgres; Postgres-dependent work waits until after it).** **Remote acquisition (U1) is not distributed: two pods would both fetch the same remote file.** Evidence: the precondition exists (`AcquisitionLedgers.java` selects a Postgres ledger via `-Dacquire.ledger.backend=db`; `DbAcquisitionLedger.record`), but `StabilityGate.java:76,80` keeps its state in a per-process `ConcurrentHashMap` (`SHARED`, keyed by Space id), and the check-then-record window in the ledger has no claim. Decision owed: depends on D14; whether the claim is a ledger row with a lease or an advisory step. Scope: a claim surface in the ledger, a stability gate that reads shared state, a two-process test.
