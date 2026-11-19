---
type: Spec
title: Docs maintenance — one fact one home, pages read whole, guards that only let debt shrink
description: How Inspecto's information base is shaped and kept right — the page header, size budget, history rule, docs-health ratchets, ledger roll and cadence — as built by INFO-RESTRUCTURE-1.
tags: [docs, process, guards, maintenance]
item: layer.platform
audience: internal
status: current
verified: 2026-11-20 last-edit
code: [tools/docs-health.mjs, tools/roll-backlog-ledger.mjs, tools/fill-doc-headers.mjs]
---

# Docs maintenance — one fact one home, pages read whole, guards that only let debt shrink

The three tiers of `CLAUDE.md` (product hubs → in-flight plans → archive) are the frame. This page holds the rules
for what goes *inside* the pages and the tools that keep them right. Built by `INFO-RESTRUCTURE-1` (W1–W4, 2026-11-19
to 2026-11-20); the plan is archived as
[`plans-archive/2026-11-19-information-restructure-and-maintenance-plan.md`](../../../../archived-documents/plans-archive/2026-11-19-information-restructure-and-maintenance-plan.md).
The board side (one board, tagged rows) is [`spec-work-tracking.md`](spec-work-tracking.md).

## Principles

1. **One fact, one home.** Everything else links to it or is generated from it.

   | Fact | Its only home | Everywhere else |
   |---|---|---|
   | Edition placement | `offerings/*.toon` | generated badges and matrices, or a link to `docs/EDITIONS.md` |
   | HTTP routes | `docs/api/openapi-v1.json` | generated reference |
   | Vocabulary | `docs/GLOSSARY.md` | links |
   | Items and modules | `product-map/` | generated hub indexes |
   | Open work | `docs/BACKLOG.md` rows | generated Open work sections |
   | Decisions and invariants | the hub's `spec-` page | links |
   | Engineering gotchas | `.claude/memory/` (the rule) and a hub `*-gotchas.md` page (the long form) | links |
   | Counts quoted in prose | the contract the count guard derives them from | `<!--count:…-->` markers |

2. **Current pages describe the current state.** History lives in git and the archive. A page may end with a short
   dated *Changes* list where a reader needs it. The one deliberate inline lesson log is the competitive landscape's
   corrections log (D-IR7).
3. **A page an agent can read whole:** target 40 KB (about 10k tokens), cap 60 KB (D-IR1). A page past the cap is
   split by topic into an entry page (its intro plus a **Parts** list) and parts named `<page>-<topic>.md` in the
   same hub, links and anchors re-pointed repo-wide.
4. **Moves, not deletions.** A moved block keeps its words; its relative links are re-pointed; git keeps the rest.
5. **Generated beats written** wherever a contract exists.

## Entrances by reader

| Reader | Entrance |
|---|---|
| Users and partners | the Handbook → `/help` and the public site (external pages) |
| Inspecto's engineers | the Handbook's internal render |
| AI agents developing Inspecto | `CLAUDE.md` → the `docs-lookup` skill → the hub `index.md` → one page; CodeGraph for code |
| AI agents using Inspecto | Agent Skills → links into Handbook pages |

## The page header (D-IR4)

Every hand-written hub page carries `type`, `title`, `description`, `item` (the hub's Product Map id), `audience`
(`external` or `internal`), `status` (`current`, `plan` or `history`), `verified` (`<date> <how>`) and `code` (the
paths it describes). `code` is optional on `layer/` pages, which link no code of their own; it is validated when
present.

- **`verified: <date> last-edit`** records when the page was last touched, **not** a content review. A real review
  replaces the suffix (for example `2026-12-01 reviewed`).
- **`tools/fill-doc-headers.mjs [--write] [--hub <kind/name>]`** adds missing fields to pages that already have
  `type`/`title`/`description`, never overwriting a value or a body line: `item` from the hub; `audience` from
  `docs/okf/product/layer/product/handbook.toon` (else internal); `status` plan for `plan-*`, else current; `verified`
  from the page's last commit; `code` only from what the Product Map links (module → its manifest's directory, addon →
  its Offering modules' directories, pack → its template or industry-pack directory, app → `apps/<id>` and its
  templates, edition → `offerings/<id>.toon`, product → `products/<id>.toon`, industry → `industries/<id>`).

## docs-health — the report and the ratchet

`tools/docs-health.mjs` measures tracked `docs/**/*.md` minus the archive. Generated pages (hub `index.md`,
`feature-db.md`, any page opening with a generator banner such as `> **Generated** by \`tools/…\``) are reported but
never ratcheted. A tracked doc missing on disk is skipped and counted.

- Default: the report — size per tier, pages over budget, history notes, header completeness, ledger size, and the
  **drift list** (pages whose `code` paths changed after their `verified` date: a review queue, not a list of errors).
- `--ratchet` (in CI and so in the pre-commit run): fails on **new** debt against `tools/docs-health.baseline.json`.
- `--baseline-write`: records today's debt. A `git mv` of a baselined page needs it in the same commit.

| Ratchet | Fails when | Exempt |
|---|---|---|
| Size | a page passes 60 KB, or a baselined page grows more than 2 KB | the board (own ledger cap), the glossary, in-flight plans, generated pages |
| History notes | a page's count of inline history notes rises (the four shapes `tools/docs-health.mjs` counts; a "valid until" date is a current fact, not history) | the board, the glossary, the competitive landscape log, plans |
| Header | a hub page lacks the full header (`partialHeader` and `headerless` baselines may only shrink), or holds an invalid `audience`, `verified` or `code` | generated pages |
| Fact home | an edition placement table is written by hand outside `docs/EDITIONS.md` and `layer/platform/spec-editions*.md` | — |
| Ledger | the board's ledger passes 60 KB | — |

The audience check (no internal page or link reaching an external render) lives in `tools/check-handbook.mjs`, with the
Handbook manifest. When a ratchet fails, `.claude/memory/doc-guard-traps.md` says what to do.

## The board ledger (D-IR2)

The board keeps three days of ledger. `tools/roll-backlog-ledger.mjs [--days 3] [--today …] [--write]` (dry run by
default) moves older entries verbatim, newest first, to the top of `archived-documents/backlog-ledger-<year>.md`, links
re-pointed. The `handoff` skill runs it at shift end; the 60 KB cap is the safety net, so the calendar alone never
turns a commit red. A board row is about 800 characters at most; its narrative lives in the hub's
`plan-board-detail.md`.

## Archived plans (D-IR3)

The archived-plans table lives in `archived-documents/README.md`, the archive's front page and its one maintained
file; `tools/check-doc-links.mjs` checks it as a current doc. A retired plan adds its row there and drops its
in-flight row from `docs/INDEX.md` in the same change.

## Cadence

| When | What |
|---|---|
| Every commit | the ratchets and the existing guards (vocabulary, links, citations, counts, board tags, product docs) |
| Shift end | the `handoff` skill rolls the ledger and reads the health report |
| Every release | the drift list is worked to zero for the release's items; the Handbook's docs-as-tests run |
| Every quarter | the sales docs' market side is re-verified |
| When a plan ships | distil into its hub, archive, add its row to the archive's front page |

Ownership travels with the change: whoever changes an item updates its hub page in the same commit and bumps its
`verified`.

## Where things went (2026-11-19/20)

- **Root canon:** `PROJECT_NOTES.md` split into hub pages and archived (`archived-documents/project-notes-2026-11-19.md`
  maps each part to its home; D-IR5); `REQUIREMENTS.md` → `spec-requirements.md`, `FEATURE_INVENTORY.md` →
  `features-feature-inventory.md` (D-IR6); the stray board → `archived-documents/backlog-board-2026-10-18.md`;
  `GLOSSARY.md` §13's finished rename rows → `archived-documents/glossary-rename-map-done-2026-11-19.md`.
- **Code-adjacent READMEs** are pointers to their hub. Kept by design: `tools/templates/*/README.md` (scaffold output),
  `inspecto/README.md` and `inspecto/examples/README.md` (ship in the bundle), the repo root `README.md`, test-resource
  fixtures, the 14 `features/inspecto-agent` decision records and plans (a different ADR set from the agent hub's log).
- **Split pages:** 41 oversize hub pages became entry pages plus 161 parts; `spec-editions.md` became an entry page and
  five parts, written in part by `render-offerings-matrix` and `render-processor-board`. Guards that read a split page
  by path follow its text (`check-family-count`, the vocabulary allowlist).

## Known gaps

- `module/la-core/brochure-video-demo-case-scripts.md` (about 145 KB) is written by `tools/gen-la-demo-scripts.mjs`
  and opens with a ``> **Generated by `node tools/…` `` banner, which the `GENERATED_BANNER` pattern in `tools/docs-health.mjs` does not
  match (it expects `` `tools/ `` right after the backtick), so the size ratchet counts it; it is baselined (`DOCS-GENERATED-BANNER-1`).
- The drift list starts at 79 pages; most (61) were last edited the day before their module changed.
