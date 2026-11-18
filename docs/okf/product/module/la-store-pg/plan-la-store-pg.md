---
type: Plan
title: Link analysis PostgreSQL store - plan of record
description: What the PostgreSQL Investigation store is required to do, what is built, what is left and what was refused.
resource: la/inspecto-la-store-pg/
tags: [module, la-store-pg, link-analysis, postgres, plan]
timestamp: 2026-10-19T00:00:00Z
---

# Link analysis PostgreSQL store - plan of record

Row of record: `LA-INVESTIGATION-STORE-DESIGN-1` in [`docs/BACKLOG.md`](../../../../BACKLOG.md). Design:
[investigation-store-design](../../../../archived-documents/plans-archive/investigation-store-design.md) (decisions
D-IS1..D-IS12, slices S0..S7, as-built in its §13).

## Required

- Let Investigations (op log, sealed sets, members, references, workflow records, Drafts, embed grants) live in
  PostgreSQL so several pods can serve one Space safely (the filesystem store is safe only within one volume).
- Implement the same `InvestigationStore` contract as the filesystem store, byte for byte on sealed data (D-IS5: `text`,
  never `jsonb`).
- Serialise with a row lock in a short transaction, never an advisory lock held across a request (D-IS3/D-IS4).
- One schema per Space (D-IS8); the Space-wide Draft cap enforced across pods (D-IS9).
- Fail closed: an unusable database is 503, never a fall-back to files.

## Built

| Slice | What | When |
|---|---|---|
| S2 | `PgInvestigationStore`, every port method incl. Drafts; contract green on PostgreSQL 18.6 | 2026-10-04 |
| S6 | `PgInvestigationStoreProvider` registered for `-Dinvestigations.backend=db`; one shared HikariCP pool; bootstrap under an advisory lock; append-only triggers; staged in Advanced and Preview | 2026-10-04 |
| S7 | two-JVM race test on real PostgreSQL: 4/4 green, no race | 2026-10-05 |
| limits | per-set size limit and per-Investigation set budget (`la_investigation.set_bytes`), both 413 | 2026-10-10 |
| boot posture | WARN + `live.investigations` on `/health/details`, 503 per request, no boot refusal | 2026-10-10 |
| embeds | `la_embed_grant` table (`LA-EMBED-PG-STORE-1`, closed) | 2026-10-13 |
| live run | contract 45/45 on real PostgreSQL (`LA-DEMO-SEED-1`, closed) | 2026-10-13 |

## Left

- The operator-run precondition mutation checks listed in the design §13.4-§13.5 (`LA-INVESTIGATION-STORE-DESIGN-1`).
- Content-addressed sets (D-IS12 (b)): demand-gated.
- The class Javadoc of `PgInvestigationStore` still says "NOT wired anywhere yet"; it is wired since S6.

## Refused

- **Dual write, import or migration between backends**: one backend per Space (`InvestigationStores`).
- **A fall-back to the filesystem** when the database is down.
- **Changing the connection without a restart**: no route edits JVM properties (operator decision, design §13.7).
- **`jsonb` storage** of lines and sets (D-IS5).
