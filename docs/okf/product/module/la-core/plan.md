---
type: Plan
title: Link analysis core and ports - plan of record
description: What la-core is required to do, what is built, what is left and what was refused, with the shipped LA-COLUMN-ROLES-2 decisions.
resource: la/inspecto-la-core/
tags: [module, la-core, link-analysis, plan]
timestamp: 2026-11-15T00:00:00Z
---

# Link analysis core and ports - plan of record

`la-core` is the host-free domain of Link Analysis: the Investigation op log and its evaluator, the
`InvestigationStore` port with the filesystem store, Drafts storage, Graph Run, and the ports the bridge implements.
Design: [architecture](architecture.md) §3 and §5. Layer overview: [layer/la architecture](../../layer/la/architecture.md).

- [Required](#required) · [Built](#built) · [Left](#left) · [Refused](#refused) ·
  [Decided design: LA-COLUMN-ROLES-2](#decided-design-la-column-roles-2-2026-11-05) ·
  [Decided design: LA-FULL-CYCLE-TEST-1 phase 2](#decided-design-la-full-cycle-test-1-phase-2-2026-11-16)

## Required

- An **append-only, hash-sealed Investigation op log** folded deterministically into a Working Set
  ([architecture](architecture.md) §3.1).
- A **storage port** so Investigations can live on a filesystem or on PostgreSQL for multi-pod deployments: id-keyed,
  precondition-carrying, never falling back between backends (`LA-INVESTIGATION-STORE-DESIGN-1`, design
  [investigation-store-design](../../../../archived-documents/plans-archive/investigation-store-design.md), decisions D-IS1..D-IS12).
- **Drafts** (private forks of an Investigation) with hibernate / expire / rebase / promote (design
  [la-separation-d7-design](../../../../archived-documents/plans-archive/la-separation-d7-design.md)).
- **Ports pointing inward**: la-core names no index class, no engine class and no route; the host and the bridge reach it
  through ServiceLoader ([la-separation-execution-plan](../../../../archived-documents/plans-archive/la-separation-execution-plan.md)).
- **Graph Run**: a bounded in-process pool of graph algorithms over a Working Set or the link index, with budgets that
  refuse rather than silently cap ([architecture](architecture.md) §5).

## Built

| Item | Classes | Record |
|---|---|---|
| Op evaluator, op log, Working Set fold | `InvestigationEvaluator`, `InvestigationTime`, `LinkEventTypes` | [architecture](architecture.md) §3.1 |
| Store port + FS store (S1), `-Dinvestigations.backend` selection seam (S6), OS file lock against cross-process races (S7) | `InvestigationStore`, `FsInvestigationStore`, `InvestigationStores` | `LA-INVESTIGATION-STORE-DESIGN-1`, design §13 |
| Per-set size limit (64 MiB) and per-Investigation set budget (4 GiB), both refused 413 | `WorkingSetSizeLimit`, `InvestigationSetBudget` | same row, done 2026-10-10 |
| Drafts storage, lifecycle and checkpoints | `DraftStore`, `DraftLifecycle`, `DraftCheckpoints`, `DraftIndex` | [la-separation-d7-design](../../../../archived-documents/plans-archive/la-separation-d7-design.md) |
| Graph Run service, 29 algorithms, budgets | `GraphRunService`, `Algorithm`, `GraphBudget`, `InMemoryGraphEngine` | [architecture](architecture.md) §5 |
| Ports for the host bridge | `DatasetProvider`, `CasePort`, `CollectorCoveragePort`, `OutboundPort`, `UploadLandingPort` | implemented in [geo-link](../geo-link/index.md) |
| `threshold` and `snapshot` ops, `threshold` `measure` | evaluator | `LA-INVESTIGATION-OPS-DEFERRED-1` (partly built) |
| Every limit a configuration item, passed in by la-api | — | [la-configurable-limits-plan](../../../../archived-documents/plans-archive/la-configurable-limits-plan.md) |
| Inspecto LA onboarding, blank install to a first finding (`LA-ONBOARD-1`, phases 0-8, closed 2026-10-22): Investigations add-on at runtime, masking by sign-in, locked-with-reason, `/onboard/files`, Getting started and the Space guide, Add data, Entity Types per column, Start from..., My work, help on every screen, onboarding counters | `EditionFeatures.holdsInvestigationsAddon`, `OnboardRoutes`, `OnboardImport`, `OnboardingRoutes`, `OnboardingCounters`; SPA `la-app`, `inspecto-add-data`, `start-from.ts`, `next-steps.ts`, `term-help.ts` | [architecture](architecture.md) §3.4, §8, [architecture-data-onboarding](architecture-data-onboarding.md) §6.10, §6.11; archived [plan](../../../../archived-documents/plans-archive/la-onboarding-plan.md); acceptance [tests-live-drives](tests-live-drives.md) |
| Column roles, second part (`LA-COLUMN-ROLES-2`, shipped 2026-11-05): Mapping-level `carried` override, Entity Type profile Measures, edge width by the primary Measure, per-Dataset Filter by role | see [below](#decided-design-la-column-roles-2-2026-11-05) | [architecture-data-onboarding](architecture-data-onboarding.md) §6.15 |
| Performance work (`LA-PERF-*`, closed 2026-11-13) | browser seams and caps | [spec](spec.md) § Performance |
| Masking a user can live with (`LA-FULL-CYCLE-TEST-1` phase 2, findings 11-14 and 24, 2026-11-16): one audited Reveal for the nodes on screen with a reason, one alias per entity inside an Investigation, details masked with their id, showcase Investigation text without entity ids, Save defaults that do not repeat | see [below](#decided-design-la-full-cycle-test-1-phase-2-2026-11-16) | [architecture](architecture.md) §3.4; `ControlApiInvestigationRevealOnScreenTest`, `tools/seed-la-investigation-text.test.mjs` |

## Left

Open rows in [`docs/BACKLOG.md`](../../../../BACKLOG.md):

- `LA-INVESTIGATION-STORE-DESIGN-1` (P3): the operator-run precondition mutation checks; content-addressed sets
  (D-IS12 (b)) stay demand-gated.
- `LA-INVESTIGATION-OPS-DEFERRED-1` (P3): the unbuilt half of the Investigation model.
- `FsInvestigationStore` is self-declared TRANSITIONAL (path-keyed Draft code), and the legacy `SnapshotStore` still
  exists ([architecture](architecture.md) §10).

## Refused

- **Falling back from the database store to the filesystem** when the database is unusable: a fall-back on one pod
  forks the evidence (D-IS8, `InvestigationStores`).
- **`snapshot` sealing a `/inv/snapshots` artifact** and a **re-evaluable `threshold`**, both declined 2026-10-05
  (`LA-INVESTIGATION-OPS-DEFERRED-1`).
- **A delta set format to make Draft rebase linear**, closed as a decision (`LA-DRAFT-REBASE-COST-1`, BACKLOG change
  log 2026-10-04): the cost is the sealed Working Set hash, not the file format.
- **Client-only edge width** (carry the Measure and sum it on the client): it unfolds links, the reason carriage is
  off (LA-COLUMN-ROLES-2 D-2 c).

## Decided design: LA-COLUMN-ROLES-2 (2026-11-05)

The deliberate remainder of `LA-COLUMN-ROLES-1`. The **Mapping-level override of `carried`** needed no decision (a
Mapping's own `columns[].carried`, set in the Mapping form, seeded from the base for a new Mapping and from the Mapping
when edited). The other three were put to the operator; **answers D-1 b, D-2 a, D-3 b; all four parts shipped
2026-11-05** (as built: [architecture-data-onboarding](architecture-data-onboarding.md) §6.15).

| Decision | Chosen | Why / cost | Rejected |
|---|---|---|---|
| **D-1** Entity *Type* profile Measures (the one-Entity profile already had `EntityProfiles.measures`; `typeProfile` had none) | **b**: count / sum / min / max per binding × Measure, plus the per-Entity distribution (p50 / p90 / top) of each Measure's total, GROUP BY per Entity, sampled by key hash as `relationships` does. Sample size and cap live in the limits config (`entity_profile.*`); a test proves the sample stays bounded on a large synthetic Dataset | Each binding is its own row, so one Dataset's rows are never summed twice; the distribution is the cost class of `relationships` (the union read took 112 s on a 39 M-row Dataset), hence the sample | (a) aggregates only; (c) one pooled row per Measure name: a transfer naming two wallets counts twice |
| **D-2** Edge width from the link mapping's primary Measure | **a**: the server sums it. `edgeSql` adds `SUM(TRY_CAST(m AS DOUBLE)) AS total` beside `cnt` (no grouping change, links do not unfold), the link-index read sums the carried column the same way (`IndexedRead`), the edge gains `total`, the client fold adds totals; Display menu *Width: Count / <Measure>*, default Count. A link with no number has total null and is drawn thinnest | Nothing changes until asked | (b) width by the Measure always: changes every existing picture; (c) client-only, see Refused |
| **D-3** Category values for a read over several Datasets (Filter by role was empty there) | **b**: per-Dataset sections in Filter by role, each writing a per-Dataset filter on the server's multi-Dataset read; a test proves one Dataset's filter does not narrow the others | Needed a filter per Dataset on the multi-Dataset projection (one `filter` per read before) | (a) only Categories shared by name; (c) leave multi-Dataset reads without the panel |

## Decided design: LA-FULL-CYCLE-TEST-1 phase 2 (2026-11-16)

The two large calls were taken with the plan (`docs/archived-documents/plans-archive/2026-11-15-la-full-cycle-test-plan.md` §4.3): **D3** masked
stays the default and a holder of *See unmasked ids* gets one audited Reveal for the nodes on screen, with a reason;
**D4** aliases stay per Investigation and every surface inside one shows its alias. The smaller calls were taken in the
lane, each with its reason (as built: [architecture](architecture.md) §3.4).

| Decision | Chosen | Why | Rejected |
|---|---|---|---|
| Where Reveal lives | The line under the canvas (*N entities masked · Reveal…*), the node dialog's **Masked id** section and Oversight's **Reveal all…**, all one store action | Finding 12: nothing on the canvas or in the node dialog pointed to Reveal. The render-status line already says what the canvas draws, and a toolbar button would have to be mirrored in the folded overflow menu | A toolbar icon; a per-node button (nine clicks again) |
| What a reveal changes | Browser memory for the open Investigation only; the canvas draws the real id as `canvasLabel`, `label` stays the alias | Nothing raw is stored and no later response changes (D3: no read becomes a disclosure nobody chose); every op, search and selection keeps working because it still speaks the alias | A server-side per-actor reveal set: it would make every later read a silent disclosure |
| Audit row | One `LINK_ENTITY_REVEALED` per entity, the alias as `alias` and as target, the reason, `via` | LA-19 asks one row per entity; the old `tokens` attribute was redacted by the event sink's secret scrubber, so the trail never named the entity | One row naming many tokens |
| Where Reveal is offered | Only in a saved Investigation; an unsaved or exploration canvas says so | A reveal is recorded against an Investigation and its stated purpose (D-U5); exploration has neither | An exploration reveal under the Space key |
| Personal details | Text and date values masked with their id; numbers and flags readable; read raw with the reason once the screen revealed the id (the node's indicators, enrichment and Reference rows), audited per entity (`via: details`); under `all` a reveal lifts the key alone | Finding 14: a name, a city or a date beside a masked id identifies it. A count, score or measure does not, and `typed` already treats an unclassified number as no id (masking it hid the telecom Risk factor `alarms_recent`). Under `all` the Space masks those values whatever the key, and a revealed row may hold another entity's id, so it fails closed | `all`'s rule (text and integral values) under `typed`: it masked counts; a blank "hidden" value: it loses equality (two accounts with one holder), which an alias keeps |
| One alias per entity | Reference rows' key column reads the alias asked with; the Summary maps counterparts through `exploreAliases` | D4: the defect was two aliases on one screen, so the Investigation's surfaces translate; the Space key is untouched | One key everywhere (re-mints every sealed alias) |
| Save defaults under masking | `"<Type>, <time>"` / `"Graph on screen, <time>"` in the one app clock (`ledgerTime`) | Finding 24: every masked start defaulted to "Account". The value cannot enter (a raw id is visible to reviewers, an alias reads as nothing); the time makes two starts differ | A blank required Name: one more field to type |
| Showcase text | Roles, never ids, in every Investigation name, purpose, reason, annotation, reference label and Draft step; guarded by `tools/seed-la-investigation-text.test.mjs` | Finding 13: the server masks every id inside Investigation text, so "Mule hub MULE-HUB-01" read "Mule hub masked:6021…" | Unmasking titles: a title is visible to reviewers |

Left on purpose: seeded Case titles (Case Management,
not an Investigation) still name the story's entity; quick-question answers and the Dossier keep aliases after a reveal.
The deep-link reveal's redacted `tokens` attribute, left at first, now reads `alias` (LA-DEEPLINK-REVEAL-AUDIT-1, 2026-11-16).
