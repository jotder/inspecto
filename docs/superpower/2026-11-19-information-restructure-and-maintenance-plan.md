# Information restructure and maintenance — plan

> **Status: APPROVED 2026-11-19 — the operator: "create a comprehensive … plan and go ahead".** **W1 executed
> 2026-11-19:** `BACKLOG.md` 577 → 280 KB (28 ledger entries of the last three days kept, 570 moved), `INDEX.md`
> 111 → 23 KB, the stray board archived — nothing lost, checked line by line against the commit before. **W2 done
> 2026-11-19:** `tools/docs-health.mjs` (report, `--ratchet`, `--baseline-write`) with `tools/docs-health.baseline.json`,
> `tools/roll-backlog-ledger.mjs`, 21 tests, a CI guard step the pre-commit hook runs, the link guard over
> `archived-documents/README.md`, the `handoff` step and a team-memory note; as built in §6.1. W3–W4 open.
> Board row: `INFO-RESTRUCTURE-1` (§10).
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
| History inside current pages | about 480 inline notes ("corrected …", "(was …)", "until 2026-…", "refreshed …") across the hubs and root canon — one pattern's count; between about 370 and 920 depending on the pattern | current state is blurred; tokens spent on the past |
| The board's change ledger | 597 entries, 327 KB — 57 % of `BACKLOG.md` | git already holds it; nobody acts on it |
| The map's archive table | 87 KB of `INDEX.md`'s 110 KB | the map is too heavy to load |
| Facts restated in many places | edition placement restated across six sales and edition docs, all stale on 2026-11-19 (fixed in `e7fcc8076`, `07bbe2bb8`) | drift, found only by accident |
| Stale map text | `INDEX.md`'s header names six trees, five of which (`stakeholders/`, `ui/`, `ops/`, `roadmap/`, `wiki/`) no longer exist; only `api/` does | the map misleads |
| Stray root file | `docs/Inspecto Backlog Board.md`, a 63-row snapshot from 2026-10-18 (the board had 94 rows on 2026-11-19) | a second, stale board |
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
4. **Every page carries one header** (D-IR4): `type`, `title`, `description`, `item` (the key 201 hub pages already
   use), `audience` (external or
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
| `PROJECT_NOTES.md` | 176 KB | split: gotchas → `.claude/memory/` or hub pages, decisions → hub specs, its map → `INDEX.md`; then archive (D-IR5) — **done** (§6.2) | W3 |
| `USER_GUIDE.md` | 51 KB | keep → the Handbook's screen reference | companion |
| `ADVANCED_GUIDE.md` | 41 KB | keep → the Handbook's operator track | companion |
| `FEATURE_INVENTORY.md` | 30 KB | moved into the platform hub as `layer/platform/features-feature-inventory.md` (D-IR6) — **done** | W3 |
| `REQUIREMENTS.md` | 26 KB | moved into the platform hub as `layer/platform/spec-requirements.md` (D-IR6) — **done** | W3 |
| `BRANCHING.md`, `EDITIONS.md` | 15 KB, 12 KB | keep; `EDITIONS.md`'s matrix generated from the offerings | W3 |
| `Inspecto Backlog Board.md` | 3 KB | archive | W1 |

**Elsewhere:** `.claude/` stays the agent layer — `CLAUDE.md` (14 KB, always loaded), team memory, skills, and the
three locator maps, which stay thin and point into the hubs. Code-adjacent READMEs (3 in `providers/`, 5 in
`tools/`, a few more) become short pointers to their hub. The other Markdown beside the code stays
where it is but is reviewed in W3: 14 decision records and plans of `features/inspecto-agent` (candidates to fold
into the agent hub; the one `features/` README, `docs/adr/README.md`, is one of the 14) and 2 runtime prompt files under `features/inspecto-intelligence/src/main/resources/prompts/`,
which are product resources, not docs, and are never cut. Space Template docs (40 under `spaces/_templates/`, plus
`spaces/demo/config/README.md`) stay with their template: they ship with it.

## 4. Maintenance — how it stays right

**In the change that alters behaviour** (no separate docs pass): the hub page is updated in the same commit and its
`verified` date bumped; a new route regenerates the OpenAPI document (existing); an edition change edits
`offerings/` and regenerates what derives from it; a closed board row regenerates the Open work sections (existing,
`tools/render-product-docs.mjs`); a shipped plan is distilled into its hub and archived (existing, `CLAUDE.md`).

**Guards, in the pre-commit run** (existing ones stay — vocabulary, links, citations, counts, board tags, product
docs). New, all as ratchets that let existing debt only shrink:

| Guard | Fails when |
|---|---|
| Size | a page grows past 60 KB, or a page already past it grows more than 2 KB (the board, glossary and plans exempt) |
| History notes | a page's count of inline history notes rises |
| Header | a new hub page lacks its base header, or a header holds an invalid `audience`, `verified` or `code` |
| Fact home | an edition table is written by hand outside `EDITIONS.md` and `spec-editions.md` |
| Audience | an internal page or link reaches an external render (with the Handbook manifest, `USER-HANDBOOK-1` P1) |
| Ledger | the board's ledger passes 60 KB |

*As built in W2, with the reasons for each departure from this table's first draft: §6.1.*

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
| **W1** | **Mechanical, lossless — done 2026-11-19:** the board ledger rolled to `archived-documents/backlog-ledger-2026.md`, keeping 3 days (570 entries moved, 28 kept; `BACKLOG.md` 577 → 280 KB); the archived-plans table moved from `INDEX.md` to the archive's front page, `archived-documents/README.md`, with 475 links re-pointed (`INDEX.md` 111 → 23 KB); `INDEX.md`'s header made current, its former note kept beside the table; the stray board archived as `archived-documents/backlog-board-2026-10-18.md` | a day |
| **W2** | **Guards and header — done 2026-11-19 (§6.1):** the header schema and its check; the size, history-note, header, edition-table and ledger ratchets; `tools/docs-health.mjs`; the ledger-roll tool the `handoff` skill runs; the link guard extended to `archived-documents/README.md`. The audience check waits for the Handbook manifest (`USER-HANDBOOK-1` P1) | done in a day |
| **W3** | **Judgment work — lane 2 done 2026-11-19:** the 82 board rows over 800 characters (71 ranked, 11 standing refusals) cut to a summary, state, `→` hub pointer and unchanged tag, their narratives moved verbatim to each hub's `plan-board-detail.md` (25 hubs; `BACKLOG.md` 294 → 132 KB, checked line by line); **lane 3 (root canon and READMEs) done 2026-11-19, §6.2**; `GLOSSARY.md` §13's 18 finished rows moved to `archived-documents/glossary-rename-map-done-2026-11-19.md` (182 → 164 KB). **lane 1 (oversize hub pages) done 2026-11-20:** 41 hub pages over 60 KB (4.4 MB) split by topic, each into an entry page (its intro plus a Parts list) and 2-8 parts of at most 40 KB (161 parts, `<page>-<topic>.md` in the same hub), lossless by line-level check, links and anchors re-pointed repo-wide (`docs/archived-documents/README.md` included), split guides placed in the Handbook, about 40 inline history notes in the moved text put in dated *Changes* lists (wire-contract "(was …)" renames kept); guards that read a split page by path follow its text (`check-family-count`, the vocabulary allowlist). Left over the cap: `spec-editions.md` (`render-offerings-matrix` and `render-processor-board` write into it; splitting needs those tools changed) and the generated `brochure-video-demo-case-scripts.md` (`tools/gen-la-demo-scripts.mjs`). Left: history notes in unsplit pages moved out page by page; the §13 rename map's rows as they finish | 2–3 weeks; mostly mechanical, suited to low-effort agents with spot checks |
| **W4** | **Joined with the Handbook's P1 inventory:** one pass over the 858 hub pages fills each header (type, audience, track, `code`) — the same pass the Handbook needs. **Lane 2 (headerless pages) done 2026-11-20:** the 119 hub pages without a base header got the full D-IR4 header (type, title from the H1, a one-sentence description written from the page, item, audience from `handbook.toon`, status, `verified: <last commit> last-edit`, `code` for module and solution-pack hubs only), bodies byte-identical; the `headerless` baseline is empty. **Lane 3 (docs-health leftovers) done 2026-11-20:** the four hand-written edition tables outside their homes became a link to `docs/EDITIONS.md` (customer pages) or the spec-editions placement (the la-core page, its gate facts kept as a list); `spec-editions.md` split into an entry page and five parts (placement, tiers, feature-matrix, step-processors, platform-board; ≤ 33 KB, lossless), `render-offerings-matrix` and `render-processor-board` write the placement and step-processors parts and `EDITION_HOMES` admits `spec-editions-*.md`; inline history notes 99 → 3 — the three left sit in frontmatter (header lanes), the competitive landscape keeps its log (D-IR7) | inside the Handbook's P1 |

### 6.1 W2 as built (2026-11-19)

- **`tools/docs-health.mjs`** measures tracked `docs/**/*.md` minus the archive; generated pages (hub `index.md`,
  `feature-db.md`, and any page opening with a generator banner such as `> **Generated** by \`tools/…\`` — the tests
  inventories and api-routes pages) are reported but never ratcheted. Default: a report. `--ratchet`: fails on new
  debt against `tools/docs-health.baseline.json`. `--baseline-write`: records today's debt (44 pages over 60 KB, 57
  pages carrying history notes, 119 hub pages without a header, 4 pages with hand-written edition tables). First
  report: 887 pages, 49 over the cap (with the board, glossary and plans), 136 history notes on ratcheted pages in four
  shapes ("until <date>" left out: "valid until …" is a current fact), the base header on most hand-written hub pages
  and the full one on none, the ledger 28.7 KB, drift 0. A tracked doc missing on disk is skipped and counted, not a
  crash; a `git mv` of a baselined page needs `--baseline-write` in the same commit.
- **Departures from §4, decided while building:**
  - *Size* gives a baselined page a 2 KB allowance so a fix can land, and exempts pages that grow by design: the board
    (its ledger has its own cap), the glossary (new terms) and in-flight plans.
  - *History notes* exempt the board, the glossary, the competitive landscape's corrections log (D-IR7) and plans.
  - *Header* requires the base header (`type`, `title`, `description`) on new hub pages and validates `audience`,
    `verified` and `code` wherever present; the full D-IR4 header is reported, not required, until W4.
  - *Fact home* covers the one restated fact measured as drifting — edition placement tables — outside `EDITIONS.md`
    and `spec-editions.md`.
  - *Ledger* caps the ledger at 60 KB instead of failing on entries older than three days, so the calendar alone
    never turns a commit red; the three days are kept by `tools/roll-backlog-ledger.mjs`, which the `handoff` skill runs.
  - *Audience* waits for the Handbook's manifest, which is where external and internal renders exist.
- **`tools/roll-backlog-ledger.mjs [--days 3] [--today …] [--write]`** moves ledger entries before the window, verbatim
  and newest first, to the top of `archived-documents/backlog-ledger-<year>.md`, links re-pointed; dry run by default.
- **The link guard** (`tools/check-doc-links.mjs`) checks `archived-documents/README.md` as a current doc: 7,066 → 7,551
  links checked, all resolving; falsified with one broken front-page link (exit 1).
- **Wiring:** one CI guard step runs the ratchet and both test files (21 tests), so the pre-commit hook runs it too; the
  `handoff` skill rolls the ledger and reads the report; `.claude/memory/doc-guard-traps.md` tells the next author what
  to do when the ratchet fails.

### 6.2 W3 root canon and READMEs as built (2026-11-19)

- **`PROJECT_NOTES.md` (D-IR5)** split verbatim, relative links re-pointed, archived as
  `archived-documents/project-notes-2026-11-19.md`, whose header maps each part to its home. §1 → a section of
  `layer/platform/architecture-modules.md`; §2 (topic map) and §7 (related sandboxes) → the end of `INDEX.md`; §3 →
  `layer/platform/spec-platform-decisions.md` (multi-space → `layer/product/spec-multi-space.md`); §4's 161 gotchas by
  topic → `layer/platform/` `spec-shared-checkout-gotchas.md`, `tests-build-verdict-gotchas.md`,
  `tests-guards-and-claims-gotchas.md`, `spec-shell-editing-gotchas.md`, `architecture-backend-gotchas.md`;
  `module/engine/spec-engine-gotchas.md`; `module/config/spec-config-gotchas.md`; UI ones with §6 →
  `layer/product/architecture-ui-conventions.md`; §5 → `module/engine/spec-engine-seams.md`; DATA-GOV-1 →
  `module/asn-decoders/spec-asn-decoders.md`. Hub pages rather than one memory file per gotcha: the team-memory
  lessons already hold the distilled rule, so each matching lesson now ends with a pointer to its long form.
  Losslessness: every one of the file's 360 content blocks is found, whitespace- and link-normalised, in a current
  doc; only its preamble, archive header and seven numbered section headings are not.
- **`REQUIREMENTS.md`, `FEATURE_INVENTORY.md` (D-IR6)** moved with `tools/docs-relocate.mjs`, frontmatter added.
  `tools/render-feature-db.mjs` keys inventory rows on a link into a module hub, which now reads `../../module/<id>/`;
  its pattern takes both forms, and every generated `feature-db.md` is identical but for the inventory's path.
- **Code-adjacent READMEs** cut to a pointer, content moved verbatim: `providers/asn-parser/asn-decoders/README.md` →
  `module/asn-decoders/architecture-decoder-modules.md`; `providers/asn-parser/corpus{,-synthetic}/README.md` →
  `module/asn-decoders/tests-corpus.md`; `dev-infra/README.md` → `layer/platform/tests-dev-infra.md`;
  `product-map/README.md` → a section of `layer/platform/spec-product-map.md`; `inspecto-ui/README.md` → a section of
  `layer/product/architecture-ui-conventions.md`. **Kept, by design:** the 5 `tools/templates/*/README.md` are scaffold
  output (`{{placeholders}}`, emitted by `tools/scaffold.mjs`), not docs; `inspecto/README.md` becomes the bundle's
  root page and `inspecto/examples/README.md` ships with the examples; `inspecto-ui/docs/README.md` was already a
  pointer; the repo root `README.md` is the engineering front door; test-resource READMEs are fixtures. **The 14
  `features/inspecto-agent` records stay** — the agent hub's `spec-adr-log.md` logs eoiagent's ADRs, a different
  set, so none is duplicated: `docs/AGENT_ARCHITECTURE.md`, `docs/AGENT_KERNEL_K0_K1_PLAN.md`,
  `docs/AGENT_KERNEL_U0_U1_PLAN.md`, `docs/AGENT_KERNEL_R1_PLAN.md`, `docs/adr/README.md` and `docs/adr/adr-0001`…`adr-0009`.
  `providers/asn-parser/docs/*.md` (7 design notes, not READMEs) were not in scope.

## 7. Decisions

| # | Question | State |
|---|---|---|
| **D-IR1** | Page size budget | **approved** — target 40 KB, cap 60 KB, as a ratchet |
| **D-IR2** | Board ledger retention | **approved** — the board keeps 3 days; older entries roll verbatim to a yearly archive file |
| **D-IR3** | Home of the archived-plans table | **approved** — `archived-documents/README.md`, the archive's front page |
| **D-IR4** | Page header fields | **approved** — as §2.4 |
| **D-IR5** | `PROJECT_NOTES.md` | **signed 2026-11-19** (operator: "continue with your recommendation") — split as §3, then archive. Done in W3 |
| **D-IR6** | `REQUIREMENTS.md`, `FEATURE_INVENTORY.md` | **signed 2026-11-19** (operator: "continue with your recommendation") — fold into the platform hub as hub pages, content kept, links re-pointed; no generators. Done in W3 |
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
in the same change. W1 wrote that step into `CLAUDE.md` (documentation lifecycle), the `handoff` skill and
`spec-work-tracking.md` (the feature path), and `CLAUDE.md` now names the archive's front page as the one maintained
file in the history tier.

## 10. Board row

`INFO-RESTRUCTURE-1` (P2, `kind: chore`) — W2–W4 after W1; items `layer.platform` and `layer.product`.

## References

`CLAUDE.md` (documentation lifecycle, lane model); `docs/INDEX.md`; `docs/BACKLOG.md` (row tags, count markers);
`archived-documents/backlog-snapshot-2026-09-24.md` and `archived-documents/index-snapshot-2026-09-07.md` (the earlier
consolidations of the same two files); `tools/check-doc-counts.mjs`, `tools/check-vocabulary.mjs`,
`tools/check-doc-links.mjs`, `tools/check-doc-citations.mjs`, `tools/render-product-docs.mjs`,
`tools/docs-relocate.mjs`; the companion plan.
