# Information restructure and maintenance — plan

> **Status: APPROVED 2026-11-19 — the operator: "create a comprehensive … plan and go ahead".** Wave W1 (§6) is
> executed in the commit after this plan's own; W2–W4 open. Board row: `INFO-RESTRUCTURE-1` (§10).
> **Scope:** all the information Inspecto's people and agents read — the product hubs, the root canon, plans, the
> archive, `compliance/`, `product-map/`, `.claude/` (rules, team memory, skills, maps), code-adjacent READMEs and
> Space Template docs — and how it is kept right after the restructure.
> **Companion:** [`2026-11-19-user-enablement-and-agent-kit-plan.md`](2026-11-19-user-enablement-and-agent-kit-plan.md)
> builds the audience views on top of this base (the Handbook, its Develop branch and internal render, the Agent
> Kit). This plan restructures and maintains the base; that one presents it. Neither repeats the other.
> Every number below was measured on the repository on 2026-11-19.

## 1. Why

The three-tier model (`CLAUDE.md`: product hubs → in-flight plans → archive) is right, and it is how an AI agent
works best: a small always-loaded rule file, a skill that routes to a hub, a generated hub index, one page. What has
gone wrong is inside the pages:

| Finding | Measured | Cost |
|---|---|---|
| Pages too big to read whole | 46 hub pages over 60 KB, the largest 211 KB (`user-guide-elt.md`); `BACKLOG.md` 574 KB; `INDEX.md` 110 KB; `GLOSSARY.md` and `PROJECT_NOTES.md` about 178 KB each | an agent reads fragments and misses context; a person gives up |
| History inside current pages | about 480 inline notes ("corrected …", "(was …)", "until 2026-…", "refreshed …") across the hubs and root canon | current state is blurred; tokens spent on the past |
| The board's change ledger | 597 entries, 327 KB — 57 % of `BACKLOG.md` | git already holds it; nobody acts on it |
| The map's archive table | 87 KB of `INDEX.md`'s 110 KB | the map is too heavy to load |
| Facts restated in many places | edition placement restated across six sales and edition docs, all stale on 2026-11-19 (fixed in `e7fcc8076`, `07bbe2bb8`) | drift, found only by accident |
| Stale map text | `INDEX.md`'s header names six trees (`stakeholders/`, `ui/`, `ops/`, `roadmap/`, `wiki/`) that no longer exist | the map misleads |
| Stray root file | `docs/Inspecto Backlog Board.md`, a 63-row snapshot from 2026-10-18 (the board now has 93 rows) | a second, stale board |
| No link from a page to its code | 722 of 858 hub pages have a header, none names the code it describes | drift cannot be detected |

What works and stays: one hub per Product Map Item with fixed slot names; generated hub indexes and Open work
sections; the derived-count, vocabulary, link and citation guards; `.claude/memory/` as one lesson per file;
CodeGraph for code.

## 2. Principles

1. **One fact, one home.** Everything else links to it or is generated from it.

   | Fact | Its only home | Everywhere else |
   |---|---|---|
   | Edition placement | `offerings/*.toon` | generated badges and matrices, or a link to `EDITIONS.md` |
   | HTTP routes | `docs/api/openapi-v1.json` | generated reference |
   | Vocabulary | `GLOSSARY.md` | links |
   | Items and modules | `product-map/` | generated hub indexes |
   | Open work | `BACKLOG.md` rows | generated Open work sections |
   | Decisions and invariants | the hub's `spec-` page | links |
   | Engineering gotchas | `.claude/memory/` | links |
   | Counts quoted in prose | the contract the count guard derives them from | `<!--count:…-->` markers |

2. **Current pages describe the current state; history lives in git and the archive.** A page may end with a short
   dated *Changes* list where a reader needs it. A deliberate lesson log — the competitive landscape's corrections
   log — stays.
3. **A page an agent can read whole.** Target 40 KB (about 10k tokens), cap 60 KB.
4. **Every page carries one header** (D-IR4): `type`, `title`, `description`, `items`, `audience` (external or
   internal), `status` (current, plan or history), `verified` (date and how), `code` (the paths it describes).
5. **Generated beats written** wherever a contract exists.
6. **Moves, not deletions.** Nothing is lost in a restructure: a moved block keeps its words and its links are
   re-pointed; git keeps the rest.

## 3. Target structure

The tiers do not change. The entrances do (the companion plan builds them):

| Reader | Entrance |
|---|---|
| Users and partners | the Handbook → `/help` and the public site (external pages) |
| Inspecto's engineers | the Handbook's internal render (*Inside Inspecto*) |
| AI agents developing Inspecto | `CLAUDE.md` → the `docs-lookup` skill → the hub `index.md` → one page; CodeGraph for code |
| AI agents using Inspecto | Agent Skills → links into Handbook pages |

**The root canon, one by one:**

| File | Size | Disposition | Wave |
|---|---|---|---|
| `BACKLOG.md` | 574 KB | keep; ledger rolled to the archive (keeps 3 days); rows cut to a short summary with the narrative moved into the hub | W1, W3 |
| `INDEX.md` | 110 KB | keep; archived-plans table moved to the archive's front page; header made current; the hub map generated | W1, W3 |
| `GLOSSARY.md` | 178 KB | keep (binding); finished rows of the §13 rename map move to the archive | W3 |
| `PROJECT_NOTES.md` | 176 KB | split: gotchas → `.claude/memory/` or hub pages, decisions → hub specs, its map → `INDEX.md`; then archive (D-IR5) | W3 |
| `USER_GUIDE.md` | 51 KB | keep → the Handbook's screen reference | companion |
| `ADVANCED_GUIDE.md` | 41 KB | keep → the Handbook's operator track | companion |
| `FEATURE_INVENTORY.md` | 30 KB | generate from the hubs' feature pages and the examples, or move into the platform hub (D-IR6) | W3 |
| `REQUIREMENTS.md` | 26 KB | generate the MoSCoW roll-up from hub specs and the Product Map, or archive (D-IR6) | W3 |
| `BRANCHING.md`, `EDITIONS.md` | 15 KB, 12 KB | keep; `EDITIONS.md`'s matrix generated from the offerings | W3 |
| `Inspecto Backlog Board.md` | 2 KB | archive | W1 |

**Elsewhere:** `.claude/` stays the agent layer — `CLAUDE.md` (14 KB, always loaded), team memory, skills, and the
three locator maps, which stay thin and point into the hubs. Code-adjacent READMEs (16 in `features/`, 10 in
`providers/`, 5 in `tools/`, a few more) become short pointers to their hub. Space Template docs (41 in `spaces/`)
stay with their template: they ship with it.

## 4. Maintenance — how it stays right

**In the change that alters behaviour** (no separate docs pass): the hub page is updated in the same commit and its
`verified` date bumped; a new route regenerates the OpenAPI document (existing); an edition change edits
`offerings/` and regenerates what derives from it; a closed board row regenerates the Open work sections (existing,
`tools/render-product-docs.mjs`); a shipped plan is distilled into its hub and archived (existing, `CLAUDE.md`).

**Guards, in the pre-commit run** (existing ones stay — vocabulary, links, citations, counts, board tags, product
docs). New, all as ratchets that let existing debt only shrink:

| Guard | Fails when |
|---|---|
| Size | a page grows past 60 KB, or a page already past it grows |
| History notes | the count of inline history notes rises |
| Header | a new or edited page lacks a complete header |
| Fact home | an edition table is written by hand outside a generated block |
| Audience | an internal page or link reaches an external render (shared with the Handbook) |
| Ledger | the board's ledger holds entries older than its retention |

**A health report**, `tools/docs-health.mjs` (W2): size per tier, pages over budget, history notes, header
completeness, ledger size, and the **drift list** — pages whose `code` paths changed after their `verified` date.

**Cadence:**

| When | What |
|---|---|
| Every commit | the guards above |
| Shift end | the `handoff` skill rolls the board ledger past its retention and reads the health report |
| Every release | the drift list is worked to zero for the release's items; the Handbook's docs-as-tests run |
| Every quarter | the sales docs' market side is re-verified (already their rule) |
| When a plan ships | distil, archive, add its row to the archive's front page |

Ownership travels with the change, as the lane model already says: whoever changes an item updates its hub in the
same commit; the `handoff` skill checks it at shift end.

## 5. What an AI agent gets from this

Fewer, whole pages; one place to look for each kind of fact; a header that names the code, so an agent can go from
page to code (and CodeGraph from code to page); no history to read past; a board and a map that load in one read.
The rules an agent must follow stay in `CLAUDE.md` and team memory, both small and always loaded.

## 6. Waves

| Wave | Delivers | Size |
|---|---|---|
| **W1** | **Mechanical, lossless (executed 2026-11-19):** the board ledger rolled to `archived-documents/backlog-ledger-2026.md`, keeping 3 days; the archived-plans table moved from `INDEX.md` to the archive's front page, `archived-documents/README.md`, links re-pointed; `INDEX.md`'s header made current; the stray board archived | a day |
| **W2** | **Guards and header:** the header schema and its check; the size, history-note, fact-home, audience and ledger ratchets; `tools/docs-health.mjs`; a ledger-roll tool the `handoff` skill runs | about 1 week |
| **W3** | **Judgment work:** board rows cut to a short summary with their narratives moved into hubs (23 rows run past 2,000 characters); `PROJECT_NOTES.md` split; `REQUIREMENTS.md` and `FEATURE_INVENTORY.md` generated or folded; the 46 oversize pages split, largest first (`tools/docs-relocate.mjs` re-points links); history notes moved out page by page; code-adjacent READMEs cut to pointers; the §13 rename map's finished rows archived | 2–3 weeks; mostly mechanical, suited to low-effort agents with spot checks |
| **W4** | **Joined with the Handbook's P1 inventory:** one pass over the 858 hub pages fills each header (type, audience, track, `code`) — the same pass the Handbook needs | inside the Handbook's P1 |

## 7. Decisions

| # | Question | State |
|---|---|---|
| **D-IR1** | Page size budget | **approved** — target 40 KB, cap 60 KB, as a ratchet |
| **D-IR2** | Board ledger retention | **approved** — the board keeps 3 days; older entries roll verbatim to a yearly archive file |
| **D-IR3** | Home of the archived-plans table | **approved** — `archived-documents/README.md`, the archive's front page |
| **D-IR4** | Page header fields | **approved** — as §2.4 |
| **D-IR5** | `PROJECT_NOTES.md` | recommended: split as §3, then archive — confirmed when W3 starts |
| **D-IR6** | `REQUIREMENTS.md`, `FEATURE_INVENTORY.md` | **open** — generate from the hubs, or fold into the platform hub |
| **D-IR7** | Which history stays inline | **approved** — deliberate lesson logs only (the competitive landscape's corrections log) |

## 8. Risks

- **Broken links on moves.** Mitigation: moved blocks have their relative links re-pointed by script; the link guard
  runs on every commit; `tools/docs-relocate.mjs` for page moves.
- **Peers editing the same files.** Mitigation: one lane per push from a clean worktree off `origin/master`
  (`CLAUDE.md`); W1 touches only `BACKLOG.md`'s header, `INDEX.md` and the archive.
- **Losing information.** Mitigation: moves, not deletions (§2.6); verification after each wave.
- **Guard fatigue.** Mitigation: ratchets fail only on new debt, never on the backlog of old debt.

## 9. Relationship to existing rules

`CLAUDE.md`'s documentation lifecycle is unchanged: three tiers, one hub per Product Map Item, `INDEX.md` updated
when a doc is added or retired. One step moves: a retired plan's row now goes into the archived-plans table in
`archived-documents/README.md` instead of the bottom of `INDEX.md`; `INDEX.md` still loses the plan's in-flight row
in the same change.

## 10. Board row

`INFO-RESTRUCTURE-1` (P2, `kind: chore`) — W2–W4 after W1; items `layer.platform` and `layer.product`.

## References

`CLAUDE.md` (documentation lifecycle, lane model); `docs/INDEX.md`; `docs/BACKLOG.md` (row tags, count markers);
`archived-documents/backlog-snapshot-2026-09-24.md` and `archived-documents/index-snapshot-2026-09-07.md` (the earlier
consolidations of the same two files); `tools/check-doc-counts.mjs`, `tools/check-vocabulary.mjs`,
`tools/check-doc-links.mjs`, `tools/check-doc-citations.mjs`, `tools/render-product-docs.mjs`,
`tools/docs-relocate.mjs`; the companion plan.
