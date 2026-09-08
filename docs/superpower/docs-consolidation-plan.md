# Docs consolidation — one functional spine, one capability spec per area

**Status:** PROPOSAL — taxonomy and tier placement need an operator decision (§5) before any file moves.
**Opened:** 2026-09-08.
**Goal (operator's words):** *"consolidate spec and plan docs into an organized set... create a little more
functional directories under okf and consolidate into a single document under it... it's difficult to find
information from within those."*

---

## 0. Summary

**The diagnosis.** The file count is a symptom. The disease is that the product is sliced **five different
ways** (capability · packaging tier · config shape · code layer · screen layout) and no two agree, so there
is no capability-oriented entry point — "consignment" appears in 48 of the 192 current-tier docs, and none
of them answers *what was required · what is built · what is left · what was refused*. Merging files without
picking one spine just produces bigger unsearchable files; this repo has already paid for that twice
(`BACKLOG.md` at 505 KB, `INDEX.md` at 879 lines).

**The design.** One functional spine — `REQUIREMENTS.md` §3's existing area IDs, re-sized from 16 to 15 —
and one document per area under `docs/okf/capabilities/<area>/` with a fixed 8-section shape whose §6
("Refused & superseded") is where contradictory ideas get killed once instead of being re-proposed. `okf/`'s
159 layer files are **not** moved.

**What the four audits changed.** Three of the four overturned part of this plan's first draft:

| First draft said | Evidence said |
|---|---|
| Put the capability docs in a sibling tier, because `okf/` is strictly as-built | `okf/` is **already ~12 % requirement/intent**, with open-gap sections in 45 of 159 files → **the operator's "under okf" was right**; it is a form change, not a kind change (§5.7) |
| Delete `archived-documents/` — 63 % of doc bytes, "never linked as current" | **93 of 143 files are cited from the current tier, ≥24 as *authority***, and 7 are orphans → **convert, do not delete** (§5.5) |
| The changelog is in `REQUIREMENTS.md`'s Requirement cell | It is in the **Status** cell — 19 exceed 300 chars, worst 1,216. And **99 of 101 rows read SHIPPED/PARTIAL/SUPERSEDED**: the requirements doc is a *completion register* (§3) |
| Dissolving `superpower/` is cheap | Cheap by link (50), **not by content: 20 of ~51 open items are untracked**, 12 in one plan whose distillation target does not exist (§5.10) |

**Findings acted on ahead of any restructuring — all four SHIPPED 2026-09-08, guards green:**

1. ✅ **The missing link guard** — `tools/check-doc-links.mjs`, wired into `.githooks/pre-push` + `ci.yml`,
   falsified six ways. Baseline **1,378 links, zero dangling**: it found 14 breaks, **7 of them in
   `.claude/skills/*/SKILL.md`** pointing at docs that do not resolve (§6.1).
2. ✅ **The vocabulary guard's scope hole** — 9 current-tier files in 5 directories were outside every
   scope. 13 violations drained, including `STAKEHOLDER_OVERVIEW.md` §11's instruction to stand up a
   `type: flow` job, which is not a real job type. Green over 200 docs; scope falsified per directory (§9).
3. ✅ **`GLOSSARY.md` §13 — the binding naming authority — under-reported its own progress.** All five
   "NOT STARTED" rows rewritten from verified source: two were **DONE**, one **narrowed to nothing**, one
   **dropped as done-by-absence**, one genuinely **PARTIAL** (§8.1). Note the direction — the inverse of
   `INDEX.md` once calling a delivered plan "NOTHING BUILT".
4. ✅ **`BACKLOG.md`'s phantom work** — **two** shipped items (GAP-2, GAP-8) struck from the deployment row
   and marked in the source plan first, plus its stale `(after §1 D1–D8 are signed)` gate dropped. ⚠ A
   third, **GAP-4, was verified STILL OPEN** and kept: only the concurrency half of D11 is on by default.

Plus one found while running the guards: 🔴 **`master` was already red** on
`render-processor-board.mjs --check`, and the guard's own "just regenerate" advice would have **silently
reverted an operator-approved product decision** (`SP-CTL-02`'s Personal cell, 🟡 → ✅) because the
generator had no way to express "delivered platform-wide, degraded on Personal". Fixed at the source: a
`PARTIAL_ON_PERSONAL` override in the renderer, and the note moved into `ProcessorCatalog.java` (the note
is *Java*, three layers up from the board) and regenerated through `ProcessorCatalogContractTest`.

**The duplication drain — RE-GROUNDED and partly done (§5.8):**

- 🔴 **The audit's framing was wrong.** There are 12 banner files, not 17; all are cited from inside
  `okf/`; and **5 of 8 concept/deep-doc pairs are the bundle's own stated design** ("summarize and link…
  they don't replace"). The real defect was that **all 12 deep docs had NO frontmatter**, so no reader
  could tell which of two files on a subject was authoritative. ✅ Fixed: `type: Reference` /
  `type: Architecture` frontmatter + a tier statement on all 12.
- ✅ **9 content defects verified against source and fixed** — including `toon-config.md` calling `source:`
  a live block (the parser reads only `collector:`), `configuration.md` pointing at a `DATA_RULES` registry
  that does not exist, `architecture.md` putting `ops/` in the wrong module *and* listing
  `StreamingFileIngester` as a ServiceLoader SPI (it is FQCN reflection; 22 real SPIs ship), a
  `-Dstatus.backend` default documented backwards, a two-place wrong Java floor, and `SERVER_GLOBAL`
  listed as 6 paths when the interceptor has 8.
- ✅ **3 code defects filed** to `BACKLOG.md` §4 (not papered over in docs): the `MAP_AUTHORED` TS↔Java
  drift — the **fourth** hand-mirrored map to drift here — and two author-facing messages naming things
  that do not exist.
- ⚠ **~25 rows remain ⚠ REPORTED, deliberately unactioned.** Acting on them would mean rewriting ~330 KB
  of the most-linked tier on unverified claims, and **two audit claims in this session proved wrong in
  opposite directions**. The register cites where to look for every one (§5.8.1).
- ⛔ **Two retire recommendations REFUSED after reading the files**: `toon-config.md` (holds the
  `PipelineConfigParser` navigation guide and the `toToon` gotcha) and everything in group 10. A third,
  `architecture-layers.md`, is a genuine candidate — its premise predates the reactor split — and now
  carries a 🔴 warning instead, because this session's own frontmatter pass had made it look authoritative.

**Decisions needed:** (a) confirm `docs/okf/capabilities/` placement + the charter amendment (§5.7);
(b) the archive is now a *conversion*, with deletion a separate gated call (§5.5); (c) approve the
15-area set and the `GLOSSARY` naming pass that must precede the directories (§5.1.1).

**Total contradictions found: 32 clusters** (§8). Steps 0/0b are useful regardless of (a)–(c).

---

## 1. Measured state (2026-09-08)

| Tier | Files | Bytes | Policy |
|---|---|---|---|
| Root canon (`docs/*.md`) | 10 | 477 KB | durable, audience-facing |
| `okf/` | 159 | 1,525 KB | current as-built truth |
| `superpower/` | 8 | 342 KB | active plans only |
| `stakeholders/` + `roadmap/` | 8 | 83 KB | audience-targeted |
| `ops/` + `api/` + `ui/` + `wiki/` | 7 | 62 KB | runbooks, contracts, audits |
| **Current tier total** | **192** | **2,489 KB** | |
| `archived-documents/` | 223 | 4,158 KB | never maintained, never linked as current |
| **Total `docs/`** | **415** | **6,648 KB** | |

Link topology (markdown links from current-tier docs): **1,252** total, of which **1,055 point into
`okf/`**, 148 into `archived-documents/`, and only **50 into `superpower/`** (from 31 distinct files).
447 `docs/` files are graphify-indexed, so doc paths are graph node identities.

## 2. Diagnosis — the file count is the symptom, taxonomy collision is the disease

The product is currently sliced **five different ways**, and no two slices agree:

| Doc | Its taxonomy | Grouping principle |
|---|---|---|
| `REQUIREMENTS.md` §3 | 16 areas with stable IDs — ACQ, ING, PIP, DAT, BI, INV, OPS, INC, SPC, MET, API, SEC, AGT, EOI, UI, PKG | **capability** |
| `EDITIONS.md` §"Feature × edition matrix" | 6 groups — Core engine · Step Processors · Control plane & authoring · Security & identity · State/scale/ops · Compliance & supply chain | packaging tier |
| `FEATURE_INVENTORY.md` §1 | 11 groups A–K — Stage-1 ingest, Parsing frontends, Schema & validation, Transforms, Sinks, Jobs, … | TOON config shape |
| `okf/` | `backend/{engine,acquisition,control-plane,pipeline-graph,config,editions,agent,modules,…}` · `frontend/{features,conventions,design-system,services}` · `agentic/` | **code layer** |
| `USER_GUIDE.md` | Business · Operations · Platform · Settings · Assistant | product navigation |

Two further docs are ordered by *time*, not subject: `BACKLOG.md` (grouped by what happens next) and
`roadmap/` (Now/Next/Later).

**Consequence.** There is no capability-oriented entry point. To answer the four questions a shift actually
asks about a subject — *what was required · what is built · what is left · what was refused* — you must open
five documents in five different orders and reconcile them yourself. Measured subject sprawl in the current
tier: **"consignment" appears in 48 of the 192 current-tier docs**, "expectation" in 40, "unpack" in 19, "pipeline" in 129.

**Why merely merging files would not fix it.** Fewer, bigger documents under the *same* five taxonomies are
just as unsearchable — and this repo has already paid for the big-document failure mode twice: `BACKLOG.md`
reached 505 KB / 3,288 lines (roughly half its rows already closed) before the 2026-09-06 rewrite, and
`INDEX.md` reached 879 lines of struck-through per-plan narrative before 2026-09-07. Consolidation only pays
if it is consolidation **onto one spine**.

## 3. A second, structural finding — the requirement tier has become a completion register

Two measured defects in `REQUIREMENTS.md` §3, both of which the capability template has to fix by design:

**(a) The as-built narrative lives in the Status cell.** *(An earlier draft of this plan said the
Requirement cell — that was wrong, and the location changes the remedy.)* Requirement cells are fine: only
**one** exceeds 300 characters (INV-2, at 273). **Nineteen Status cells exceed 300 characters**, and 16
whole rows exceed 500. Worst by Status length: `SEC-7` (1,216) · `BI-6` (944) · `DAT-6` (911) · `ACQ-4`
(783) · `INC-4` (649) · `INC-3` (641) · `BI-8` (604) · `API-5` (587) · `DAT-5` (575) · `MET-5` (526).
`API-5`'s cell documents *deleted* machinery; `INC-3`'s carries a live 🔴 defect; `ACQ-4` and `INC-3`
duplicate the same CONNECTORS-BUNDLE-1 post-mortem. The Edition cell has drifted the same way — `INV-2`,
`OPS-2`, `INC-3`, `SEC-8` all exceed 150 characters against a `P`/`S`/`E`/`All` convention.

So the remedy is not "rewrite the requirements" but **strip the Status cell to a token plus a date, and move
its narrative into §3 Specification / §4 Decisions of the owning capability doc** — where a post-mortem is
readable, and where it is written once instead of twice.

**(b) §3 has no slot for open work at all.** **99 of its 101 rows read `SHIPPED`, `PARTIAL` or
`SUPERSEDED`.** The requirements-of-record document is, in practice, a *completion register* — while 55 open
rows live in `BACKLOG.md` with no requirement-level home. That is the structural reason a reader cannot
answer "what is required of this capability and how much of it exists": the two halves of the answer are in
different documents, keyed differently, and neither cross-references the other per row.

The template's §2 (requirements of record, every row with a status) and §5 (not built → pointer to the
BACKLOG row) exist precisely to put both halves on one page without duplicating the work board.

## 4. Hard constraints any restructuring must respect (grounded, not assumed)

1. **`okf/` is the link hub — 1,055 of 1,252 current-tier links point into it.** Physically re-homing its
   159 layer files into functional directories is the most expensive change available, and there is **no
   link guard** to catch the rot: `tools/` holds `check-vocabulary`, `check-secrets`, `check-dependencies`,
   `check-coverage`, `check-gate-tally` — nothing checks markdown links. DOCS-LINKS-1 was a manual sweep.
2. **The vocabulary guard's `DOC_ALLOW` is keyed by path (`<path>::<ruleId>`) and has a stale-waiver rule
   that FAILS the build when a keyed path leaves scope.** Seven current waivers are keyed to files a
   consolidation would plausibly touch — `okf/backend/integrations.md`, `okf/backend/log.md`,
   `okf/backend/engine/db-layer.md`, `okf/frontend/features/link-analysis.md`, plus four on `GLOSSARY.md`
   and `PROJECT_NOTES.md`. Any move of those files must re-key its waiver **in the same change**.
3. **Guard scope is a named list, not a glob.** `DOC_TREES = ['docs/okf', 'docs/superpower', 'compliance',
   'docs/stakeholders']` plus a named `ROOT_CANON` file list. A brand-new top-level tree (e.g.
   `docs/capabilities/`) would be **silently unscanned** for canonical vocabulary until added — the
   silent-exemption trap this repo has already hit three times. Anything under `docs/okf/**` is scanned for
   free.
4. **Doc paths are graphify node identities** — 447 `docs/` files are in `graphify-out/manifest.json`. Moves
   require `graphify update .` in the same change (AST-only, no API cost).
5. **`EDITIONS.md` is authoritative for the Edition column** (REQUIREMENTS.md §2 concedes this). A
   consolidated capability doc must *mirror*, never restate, edition facts.
6. **The archive is fully recoverable from git** — all 227 files under `archived-documents/` are tracked,
   across 2,100 commits. `git log --all --diff-filter=D -- <path>` then `git show <sha>:<path>` retrieves
   any of them. Git, not a directory, is the provenance tier.

---

## 5. The design

### 5.1 Pick ONE functional spine: `REQUIREMENTS.md` §3's area IDs, re-sized

Adopt the existing area IDs as the single subject axis. Why this taxonomy and not a fresh one:

- Its IDs are **already the traceability handles** — cited by `BACKLOG.md` rows, `EDITIONS.md`'s
  gating-debt section, commit messages, and `REQUIREMENTS.md` §5/§6. A new scheme invalidates every
  existing citation.
- It is the only one of the five taxonomies that is **capability-shaped**. The other four are shaped by code
  layer, packaging tier, config syntax, or screen layout — legitimate secondary views, none of them what a
  reader wants when they ask "what about acquisition?".

**But 16 is the wrong number, and four of the names are wrong.** Measured per-area load (REQ §3 rows +
open BACKLOG rows + ROADMAP items, and the as-built `okf/` files that back them):

| Area | REQ | BACKLOG | ROADMAP | okf files / KB | Verdict |
|---|---|---|---|---|---|
| PIP | 7 | 9 | 3 | 23 / 440 | **split** — BACKLOG already splits it (authoring vs execution) |
| SEC | 9 | 3 | 1 | 3 / 47 | keep |
| AGT | 7 | 4 | 2 | 8 / 74 | keep; absorb EOI |
| ING | 6 | 4 | 1 | 9 / 187 | keep — **75 KB of parse truth sits in `frontend/features`** |
| OPS | 6 | 4 | 1 | 13 / 164 | keep (⚠ name collides with the **Ops Lens**) |
| API · UI | 7 · 8 | 3 · 2 | 1 · 1 | 4 / 35 · 20 / 38 | keep |
| ACQ · DAT · MET | 7 · 6 · 5 | 1 · 3 · 3 | 2 · 1 · 0 | 10 / 84 · 10 / 128 · 11 / 78 | keep |
| PKG | 4 | 4 | 3 | 7 / 90 | keep — **under-specified in REQ** relative to its open load |
| INC | 5 | 3 | 0 | 4 / 26 | keep — 🔴 **no dedicated backend concept file exists anywhere** |
| BI | 8 | 2 | 0 | 2 / 8.5 | **merge with INV** — zero backend coverage; `frontend/features/studio.md` is the de-facto backend spec |
| INV | 4 | 0 | 0 | 3 / 29 | **merge into BI** — smallest area, and see the naming defect below |
| SPC | 5 | 2 | 0 | 4 / 17 | thin; keep (tenancy is a real boundary) |
| EOI | 7 | 1 | 1 | 5 / 11 | **fold into AGT** — a separate repo, not an Inspecto capability |
| *(none)* | 0 | **7** | 1 | — | **two areas are missing** |

Three structural changes fall out:

1. **Split `PIP`** into authoring and execution — 19 tracked items and 440 KB of as-built truth, and
   `BACKLOG.md` already uses that exact split in its own subheadings.
2. **Merge `BI` + `INV`; fold `EOI` into `AGT`.** IDs stay stable — `BI-n` and `INV-n` simply share one
   document, as do `AGT-n` and `EOI-n`.
3. **Add the two missing areas:** **compliance** (3 open rows, currently orphaned under NFR-7 with no
   functional home, despite owning the whole `compliance/` tree and `controls-matrix.md`) and a
   **repo/tooling** area for the 4 vocabulary-, guard- and docs-hygiene rows. That accounts for the 7
   BACKLOG rows that map to no area today.

Net: **16 → 15 areas** (16 − INV − EOI + PIP-split + compliance + tooling), each with a real load behind it.

#### 5.1.1 ⚠ Four area names fail the binding vocabulary rule

`GLOSSARY.md` is binding, and these names become directory names — so they have to be fixed first, not
inherited. None uses a hard-banned word, but four are not canonical:

- 🔴 **`INV` "Investigation studios"** — "Investigation" is never defined, and `GLOSSARY` §1-A puts **both**
  Link Analysis and Geo Map Analysis *inside* **Studio**. A top-level `INV` sibling of `BI` contradicts the
  glossary outright. (Independent reason to merge it into a single **Studio** area.)
- 🔴 **`EOI` "Agentic framework as a product"** — "Agentic" has **zero** glossary hits, and `EOI` is an
  opaque acronym for a separate repo. (Independent reason to fold it into `AGT`.)
- 🔴 **`UI` "Operator console UX"** — "console" appears once, lowercase, inside the Lens definition; the
  canonical surfaces are **Workbench / Studio / Ops**. `STAKEHOLDER_OVERVIEW` §11 still lists the console's
  name as an *open decision*, and a `ui/` directory collides with `inspecto-ui/`.
- ⚠ **`BI` "BI Studio & presentation"** — the canonical surface is bare **Studio**; "presentation" is
  undefined.

Also undefined-but-not-banned: `PKG` "Packaging" (zero hits — and **Edition** has no `GLOSSARY` entry at
all, only `EDITIONS.md`), `API` "integration" (zero hits), `AGT` "embedded intelligence" (a module name, not
a concept), `ACQ` "Acquisition" (adjectival only). And **`OPS` collides with the Ops Lens** — one word, two
concepts, which is precisely what the vocabulary rule forbids.

**Therefore step 2 of §6 gains a precondition: a `GLOSSARY.md` pass that gives every area a canonical name
and an entry.** Directory names are the most expensive kind of name to change later.

✅ **DONE 2026-09-08 — `GLOSSARY.md` §14.** Fifteen areas, seventeen spec slots (`PIP` is two), every one with a
directory and a defining entry: `BI`+`INV` → **Studio** (`studio/`), `AGT`+`EOI` → **Assistant**, `OPS` →
**Observability & maintenance** (`observability/` — the Ops Lens keeps the word *Ops*), `UI` → **Surfaces &
Lenses** (`surfaces/`), `API` → **Control API**, `PKG` → **Editions & packaging** (`editions/`), plus the new
`CMP` **Compliance** (prefix inherited from `EDITIONS.md`'s `CMP-01…03`) and `TOOL` **Guards & repository
tooling**. Four terms that became directory names got their first entry: Control API, Edition, Compliance,
Guard. `okf/capabilities/index.md` lists all seventeen slots; `REQUIREMENTS.md` §3 points at the table until
step 5 re-keys its headings.

### 5.2 One capability spec per area, with a fixed section order

`docs/okf/capabilities/<area>/<area>.md` — the operator's "single document under a functional directory".
Every one has the same eight sections, so a reader learns the shape once:

| § | Section | Content | Replaces |
|---|---|---|---|
| 1 | Purpose & scope | one paragraph, canonical vocabulary, what is *not* in scope | — |
| 2 | Requirements of record | the `<AREA>-n` table: ID · Requirement · MoSCoW · Status · Edition — **requirement statements only**, as-built narrative stripped | `REQUIREMENTS.md` §3.x |
| 3 | Specification | the durable design: contracts, config keys, semantics, invariants | `superpower/` plans; the archive |
| 4 | Decisions | dated one-liners: what was decided, by whom, why | plans' §decisions; `PROJECT_NOTES.md` §3 |
| 5 | Not built | gated / postponed / on-hold — **one line + a pointer to its `BACKLOG.md` row**, never a copy | plans' open tails |
| 6 | Refused & superseded | ideas explicitly rejected, and specs replaced by later ones, each with the reason | *nothing today — this is the gap* |
| 7 | As-built mechanism | links into `okf/backend/…` and `okf/frontend/…` — **pointers only, no content** | — |
| 8 | Verification | the tests, guards and runnable examples that prove it | scattered |

**§6 is the answer to "some ideas would be contradictory."** Today a refuted idea survives in whichever plan
proposed it, with nothing marking it dead — so it gets re-proposed. `BACKLOG.md` §6 does this for *work*
("standing refusals — keep so nobody re-files"); §6 here does it for *design*, per capability.

**§5 and §7 are pointers by construction.** This is what stops the capability doc becoming a fourth copy of
the truth: open work stays owned by `BACKLOG.md`, mechanism stays owned by the `okf/` layer files, editions
stay owned by `EDITIONS.md`. The capability doc owns only §2, §3, §4 and §6 — the requirement, the spec, the
decision, and the refusal.

### 5.3 Size budget — so this does not become the next 505 KB page

A directory per area with **one front-door document**. If an area's §3 Specification exceeds **~40 KB**, it
splits into named sub-specs in the same directory and the front door keeps §1/§2/§4/§5/§6/§7/§8 as its
index. `PIP` is the near-certain split (pipelines already span `pipeline-spec.md` at 71 KB plus
`pipeline-waves-drain-plan.md`); most areas will be one file. Enforced by review, and cheap to check.

### 5.4 What dissolves, and what it costs

| Document | Disposition | Cost |
|---|---|---|
| `superpower/` — 8 plans, 342 KB | durable spec → §3/§4 of the owning capability doc · open items → `BACKLOG.md` · files `git mv` → `plans-archive/` | **cheap by link (50 from 31 files), NOT cheap by content: 20 of ~51 open items are not in `BACKLOG.md`** — see §5.10 |
| `REQUIREMENTS.md` §3 (16 tables) | move out to the capability docs; the file keeps §1 scope, §2 conventions, §4 NFRs, §5 MoSCoW rollup, §6 sequencing, §7 risks, §8 traceability — becoming the **cross-area rollup** it is good at | shrinks to roughly a third |
| `EDITIONS.md`, `FEATURE_INVENTORY.md` | keep their tables (authoritative for edition and TOON shape); **re-key groups to the 16 IDs** | low, mechanical |
| `archived-documents/` — 223 files, 4,158 KB (**63 % of all doc bytes**) | **not deleted** — converted: 7 ORPHANs distilled, ≥24 authority citations resolved (§5.5) | higher than first estimated; 93 of 143 files are cited from the current tier |
| `okf/backend/**`, `okf/frontend/**` — 159 files | **NOT moved** | — |

### 5.5 The archive — REFUTED 2026-09-08, do not delete it

The first draft of this plan proposed deleting `archived-documents/` outright: 63 % of the doc byte mass in
a tier whose own policy is "never maintained, never linked as current", fully recoverable from git. **The
audit refuted that.** Both halves of the policy sentence are false of the actual tree:

| Claim | Audit finding |
|---|---|
| "everything was distilled first" | **mostly true** — 134 of 143 `plans-archive/` files are homed, 2 more are parked and tracked (`postgres-multi-user-plan` → BACKLOG §6; `4x-public-pkce-plan` → §2 SEC-INCIDENT-1). But **7 are ORPHANs**, ~152 KB, holding content with no current home. |
| "never linked as current" | **flatly false** — **93 of 143 files are cited from the current tier**, across 219 lines, of which **at least 24 are authority citations** ("the design", "holds the full evidence", "the grounded refutation"). |

The authority citations are the fatal ones — these are not provenance pointers, they are current docs
delegating their truth to the archive:

- **`REQUIREMENTS.md` §8 Traceability** lists four archived plans as grounding docs, *peer to* `GLOSSARY.md`,
  `EDITIONS.md` and `ROADMAP.md`.
- **`okf/backend/modules/review-coverage.md`** carries an archive path in its graphify `resource:`
  frontmatter — the knowledge graph indexes the archive as a concept source.
- **`okf/frontend/features/grammar-config.md:126`** points at `plans-archive/assets/authoring-redesign-mockup/`
  — the mockup the build is measured against.
- `docs/api/README.md` calls `api-contract-design.md` "**the design**"; `GLOSSARY.md` delegates four
  separate rationales; `okf/` concepts delegate "the full phasing (L0–S3)" and "the grounded refutation".
- In-flight `superpower/completeness-kpi-plan.md:21` says it "deliberately overrides
  `consignment-elt-architecture.md` §8's central claim" — unreadable without the archived claim.

**The seven ORPHANs** (each needs a destination before anything is deleted):

| File | KB | Unique content with no current home | Area |
|---|---|---|---|
| `modularization-optimization-plan.md` | 38 | standing refusals C2/C4/C6 — **BACKLOG §6 self-confesses they "survive only in" this archive file** | PKG |
| `snazzy-painting-platypus.md` | 39 | the pre-`inspecto-ui` DevExtreme operator-UI spec; superseded in fact, **supersession recorded nowhere** | UI |
| `acquire-controller-service-design.md` | 22 | unbuilt and in no BACKLOG row: the NiFi Controller-Service contract mapping, the **List+Fetch decomposition** of the acquisition node, the VALID/INVALID run-gating rule, per-source scheduling | ACQ |
| `frontend-review-and-completion-plan.md` | 22 | the 8-step per-pane review protocol + "Definition of Done for a pane" — **zero hits in `docs/` or the `angular-ui` skill** | UI |
| `system-maintenance-plan.md` | 21 | §3's COULD list (11 features), the demotion rationales, §6's three open product questions — only MNT-14 is tracked | OPS |
| `brainstorm-tingly-storm.md` | 5 | the decision to build connections as a standalone `inspecto-connect` repo with stable `@PublicApi` — reversed in fact, **no §6 refusal row records it** | ACQ |
| `claude-usage-audit-2026-07-02.md` | 5 | process/meta only (hook pruning, model routing) — not product-load-bearing | meta |

**Revised disposition.** The archive is not dead weight to delete; it is **partly a mis-filed current
tier**. The work is *conversion*, not deletion, and it is exactly what the capability docs are for:

1. Distil the 7 ORPHANs into their area's §3/§4/§6 — five of them are §6 "Refused & superseded" rows
   (`snazzy-painting-platypus`, `brainstorm-tingly-storm`, `modularization-optimization`'s C2/C4/C6) and two
   are §5 "Not built" rows (`acquire-controller-service-design`, `system-maintenance-plan`'s COULD list).
   `frontend-review-and-completion-plan`'s pane-review protocol belongs in the `angular-ui` skill, not a doc.
2. Resolve the ≥24 authority citations: each is a current doc that must either absorb the fact it delegates,
   or the archived text must be promoted. `REQUIREMENTS.md` §8 and the `review-coverage.md` graphify
   `resource:` entry are the two that most clearly should not point at the archive at all.
3. Only then is the residue safely deletable — and by that point it is genuine provenance, which git already
   holds. **Deletion is step 8, gated on 1 and 2, and is a separate operator call once the evidence is in.**

**Bookkeeping defects found in passing** (cheap, worth fixing in step 1): `INDEX.md`'s table claims to
narrate 46 archived plans and actually narrates 52 of 143; `record-transformer-replaces-map-plan` (shipped
2026-09-05) and `path-containment-unification` were archived with **no row at all**;
`legacy-surface-removal-plan` still reads "in flight"; `4x-public-pkce-plan` carried "do not archive until
rotation is confirmed" and was archived anyway; `metadata-network-design.md` has a broken link to a
non-existent `schemas/` directory.

### 5.6 Close the link-guard gap in the same work

Add `tools/check-doc-links.mjs` — pure Node, no dependencies, in the house style of the other five guards —
resolving every relative markdown link under `docs/` and `compliance/` and failing on a dangling target.
Wire it into `.githooks/pre-push` and `.github/workflows/ci.yml` alongside the vocabulary guard. Without it,
a restructuring that rewrites hundreds of links is unverifiable, and the DOCS-LINKS-1 class of problem
returns. Falsify it in both directions before trusting it (break a link deliberately; confirm it fires).

### 5.7 Where the capability docs live — the operator's instinct was better than my first answer

The operator asked for functional directories **under `okf/`**. My first draft argued against it on the
grounds that `okf/index.md` defines that tier as:

> "each `.md` file is one concept … Concept files **summarize and link** the deep topic docs (each cites its
> authoritative doc); **they don't replace them**." · "**The `okf/` tier is a constraint register, not a
> backlog.**"

**The `okf/` audit substantially refuted that objection.** Measured over 22 representative files,
KB-weighted, the tier's actual content is ~55 % as-built · ~18 % invariant · **~12 % requirement/intent** ·
~8 % how-to · ~7 % history. The intent shows up in three shapes:

- **whole requirement files** — `okf/backend/acquisition/data-acquisition-framework.md` calls itself "the
  original requirement"; `okf/backend/engine/object-storage-export.md` is a "Recommendation of record" for
  unscheduled work; `living-operational-system.md` names five promised-but-absent component kinds;
- **69 planned (unbuilt) processors** inside `okf/backend/pipeline-graph/step-catalog.md`;
- **"Still open / gap / deliberately not built" sections in 45 of the 159 files.**

So `okf/` is *already* not strictly as-built, and it already hosts authoritative requirement text. What it
lacks is not the content kind but the **form**: zero MoSCoW tables, no ID-keyed requirement rows. Putting
the capability docs under `okf/` is therefore a **form change, not a kind change** — and the charter
sentence I quoted describes an aspiration the tier has not held for some time.

**Revised recommendation: `docs/okf/capabilities/<area>/` — what the operator asked for.** It also inherits
vocabulary-guard scanning for free (`DOC_TREES` already covers `docs/okf`), avoiding the silent-exemption
trap a new top-level tree would open.

Two things to carry over from the objection, because they were the useful part:

1. **Preserve the property the audit found `okf/` already maintains:** intent is *always labelled, dated,
   and co-located with "what exists today · what is missing"*. The template's §2 status column, §5
   (not built → BACKLOG pointer) and §6 (refused & superseded) are exactly that discipline written down.
   The failure mode being guarded against is real and this repo has paid for it — `INDEX.md` called a
   fully-delivered plan "NOT APPROVED, NOTHING BUILT" for two days by reading intent as as-built.
2. **Amend `okf/index.md`'s charter and CLAUDE.md's doc-lifecycle in the same change**, so the tier's stated
   definition matches what it contains. The current mismatch is itself a finding: a charter that says
   "constraint register, not a backlog" over a tier with open-gap sections in 45 files is a doc nobody can
   use to decide where something belongs.

### 5.8 The duplication debt — RE-GROUNDED and partly drained 2026-09-08

**The audit's framing was substantially wrong, and the correction changes the work.** It reported "10
subjects documented 2–4 times over" caused by "17 surviving `> Moved from` banners". Grounded:

- There are **12** banner files, not 17.
- **All 12 are cited from inside `okf/`** (3–12 citing files each). None is orphaned.
- 🔴 **5 of 8 small `type: Concept` files DO cite their large sibling.** That is not duplication — it is
  the bundle's own stated design: `okf/index.md` says concept files "**summarize and link** the deep topic
  docs (each cites its authoritative doc); **they don't replace them**."

So the deep docs were never rival accounts. What actually broke on 2026-07-16 is that the deep topic docs
were **moved inside the bundle**, where nothing distinguished them from concepts any more:

🔴 **All 12 had NO frontmatter — in a bundle whose charter is "each `.md` file is one concept with YAML
frontmatter".** So a reader landing on `configuration.md` (41 KB) had no way to tell whether it or
`toon-config.md` (3.8 KB) was authoritative, and neither said. *That* is the real defect behind "it's
difficult to find information", and it is a **labelling** problem, not a merge problem.

**Fixed (mechanical half, done):** all 12 now carry frontmatter on the existing schema
(`type`/`title`/`description`/`resource`/`tags`/`timestamp`) — `type: Reference` for ten,
`type: Architecture` for the two architecture maps, both values already in the bundle's vocabulary. Each
one's provenance banner became a **tier statement** naming the summary side, e.g. *"Deep reference — the
detail tier. Start at *TOON Configuration* (`toon-config.md`) for the summary; this page is the long form it
points to."* Where no concept genuinely summarises the file, the banner names the section `index.md` that
introduces it — verified from the citation map, never invented. ⚠ Seven `description:` values I first wrote
contained `": "`, which is invalid as a plain YAML scalar; caught by an audit of the whole bundle (the
other 116 files were clean) and repaired to em-dashes.

**Also found while doing it — flagged, not fixed:** **15 further `okf/` content files carry no frontmatter
either**, ~237 KB including `modules/reactor.md` (44 KB), `frontend/features/inline-ai-authoring.md`
(36 KB) and `backend/agent/embedded-intelligence.md` (33 KB). The 14 `index.md` files and 2 `log.md` are
exempt by charter; these 15 are not. Same defect class, larger surface — a separate pass.

#### 5.8.1 The remediation register

Contradiction hunts were run per subject with instructions to **settle every behavioural disagreement
against source code**, because a merge that averages two accounts bakes in whichever is wrong. Rows marked
✅ **VERIFIED** were confirmed by me directly against the tree and are already fixed; rows marked ⚠
**REPORTED** come from an audit and **must be re-verified before acting** — two audit claims in this same
session were wrong in opposite directions (one called a live BACKLOG row shipped; one called a file with
unique content redundant).

**Group 4 — TOON config (`configuration.md` · `toon-config.md` · `pipeline-config-keys.md`)**

| # | Defect | State |
|---|---|---|
| 4.1 | `toon-config.md` listed **`source:`** as the live acquisition block. `PipelineConfigParser` reads only `collector` (`:218`, `:376`); the sole `"source"` reads are `partitions[].source`, a *column* reference; there is no alias; and **zero committed `*_pipeline.toon` carries a top-level `source:`**. | ✅ **VERIFIED · FIXED** |
| 4.2 | `toon-config.md` claimed `grammar` is "still CWD-only". `resolveGrammarRef` delegates to `resolveSchemaRef(ref, configDir, "grammar")` (`:1181-1185`) — it is config-relative-first like `schema_file`. Only `dirs.*` is CWD-only. | ✅ **VERIFIED · FIXED** |
| 4.3 | `configuration.md`'s level-2 extension seam told the reader to "add a `ColumnRule` to the `DATA_RULES` registry in `etl/TransformCompiler`". **`DATA_RULES` does not exist anywhere in the tree.** The live seam is the `RecordTransform` catalog paired with `sql-functions.ts`, pinned by `RecordTransformContractTest`. | ✅ **VERIFIED · FIXED** |
| 4.4 | ~90 lines of `configuration.md` §2 present `mapping.rules[]` + `transformType` as the source of truth; `DataTransformer.recordFields` reads `fields` first and `TransformCompiler`'s own javadoc says rules "no longer compile here". `configuration.md:252` already contradicts its own section. | ⚠ REPORTED |
| 4.5 | `pipeline-config-keys.md` states the coverage ratchet as 17 entries / 7 top-level parser-only with `collector` parser-only; reportedly 16 / 6 with `collector` declared. The page also contradicts itself at `:53-55`. | ⚠ REPORTED |
| 4.6 | `#` comments: `toon-config.md` "tolerates" vs `configuration.md` "does not support". Both half-right (`toMap` lenient, `toMapStrict` not) — ⛔ whatever is written, the 🔴 **truncation** note must survive: a `#` line above a block truncates the file. | ⚠ REPORTED |

⛔ **`toon-config.md` must NOT be retired**, against the audit's recommendation. I read all 52 lines: it
owns three things nothing else records — the `toToon` tabular-array gotcha with its exact error string, the
**`PipelineConfigParser` navigation guide** ("the split is by *state*, not size", why the head and
`processing`/`dirs` stay inline, why `parseParsing` returns a private `Grammar` record), and the W1b
resolution rule including "the CWD branch is not a security boundary". Its subject is the **codec and the
parser's internal structure**; `configuration.md`'s is the **key reference** — and its first line already
says so. The division of labour was already correct; only its two stale claims were not.

**Group 5 — Step/node catalog (`node-types.md` · `catalog-vs-executors.md` · `step-catalog.md`)**

The three-way split is **correct** and should be kept: `node-types.md` owns the plugin seam + emit table,
`catalog-vs-executors.md` the taxonomy↔executor seam + `transform.sql` internals, `step-catalog.md` the
contract-generated authoring reference. For *which kinds exist and their status*, `step-catalog.md` is the
authority — it was right in every code-settled dispute. All rows below are ⚠ **REPORTED**, unverified:

| # | Defect |
|---|---|
| 5.1 | `catalog-vs-executors.md` calls `transform.lookup` PARTIAL / "no inline static map anywhere in the engine"; it is DELIVERED (`BuiltinNodeType:109`, `RowShaper.lookup()`, `ProcessorCatalog` `Status.DELIVERED`). |
| 5.2 | `node-types.md`'s RECIPE_VERBS list includes `map` and omits `sql`/`lookup`; the code has 16 entries and no `map`. Contradicts its own §earlier text. |
| 5.3 | Catalog size stated as 121 (`catalog-vs-executors.md`) vs 119 (`step-catalog.md`); 119 is right — 121 is the pre-fold count. |
| 5.4 | `step-catalog.md`'s own frontmatter says 33/17/69 delivered/partial/planned, its body says 34/16/69; the catalog says **34/18/67** — `acquisition.file.s3` and `.gcs` are `PARTIAL` but sit in the Planned table. **"69 planned" is wrong.** |
| 5.5 | Step kinds stated as 15; `step-types.contract.json` has 16 (not bumped when `lookup` landed). |
| 5.6 | `catalog-vs-executors.md` treats `transform.map`/`TransformCompiler` as live paths (refuted), cites an `app-pipeline-load-definition` component that no longer exists, and **every `RowShaper.java:NNN` line reference is stale** — the class moved to `com.gamma.pipeline.exec`. |
| 5.7 | `node-types.md`'s emit table omits `transform.lookup` — the one table whose job is to prevent an unsafe fold. |

**Group 7 — one defect verified early, in the most-referenced architecture file**

| # | Defect | State |
|---|---|---|
| 7.1 | `backend/architecture.md` (28 inbound, the most-linked architecture doc) put **`ops/` in `inspecto-engine`**. EDG-01 cell 7 moved the whole `com.gamma.ops` domain to the optional **`inspecto-ops`** module *the day before*. Fixed, and the note now records the `ObjectAccess` + `ObjectEngineProvider` seam and that `notify/` is split across `inspecto-engine` + `inspecto-notify-channels`. | ✅ **VERIFIED · FIXED** |
| 7.2 | That same file had **no link at all** to `architecture-layers.md`, the 16 KB deep layer map — so the bundle's main architecture entry point gave no path to its own deep tier. Pointer added. | ✅ **VERIFIED · FIXED** |

**Group 1 — Parse pane / SQL-first (`pipeline-editor.md` · `grammar-config.md` · `schema-mapping-authoring.md`)**

⚠ There are **four** accounts, not three — `okf/frontend/features/index.md` is a fourth and the stalest.
**Authority: none single, and ⛔ do not merge.** The three-way split is an explicit operator decision
(`pipeline-editor.md:12-22`) and is right: `pipeline-editor.md` = the editor shell, `grammar-config.md` =
the Grammar/Parse surface, `schema-mapping-authoring.md` = the post-parse panes. What broke is that all
three wrote *across* the boundary. 🔴 **Scoreboard: schema-mapping right in 7 of 11 disputes,
pipeline-editor 2, grammar-config 1 — no file is reliably right**, so "keep the newest" would ship errors
in both directions. All rows ⚠ **REPORTED**:

| # | Defect |
|---|---|
| 1.1 | `pipeline-editor.md:404-409` says persisted `transform.sql` config is `{sql}` only and *"do **not** add `fields`"*; `submit()` writes both on every Apply and `readFields` rehydrates on load. **The prohibition would delete a live contract.** |
| 1.2 | `pipeline-editor.md:399-403` describes the pane as one SQL `<textarea>` + the shared `<inspecto-schema-fields-editor>`; it is a bespoke grid + function catalog + `Fields\|SQL` switch + CodeMirror, and `schema-fields-editor` is **not imported**. |
| 1.3 | `schema-mapping-authoring.md:243-246` says CodeMirror is "wired to only two unrelated surfaces… never here" — stale, there are three importers including this pane; BACKLOG letter (b) is closed. |
| 1.4 | `transform.map` is described as "NOT removed… legacy path" in `schema-mapping-authoring.md:25-26` (and its `resource:` frontmatter) while the same file says DELETED at `:171,180`. It **is** deleted. ⚠ `pipeline-editor.md:486-502` has the mirror problem — a present-tense Load-pane section under its own DELETED banner. |
| 1.5 | `grammar-config.md:80` shows a "Use" column on the Parse table; D8 removed include-control and the code comment says so. |
| 1.6 | `grammar-config.md:22-27` calls D8–D10 "decided, NOT built" and cites `AUTHORING-WIDE-1`; they were built the same day. ⚠ Neither doc gets Parse's *filter* right — it is a type **dropdown**, not counted chips. |
| 1.7 | Parse pager default given as 10 vs 50 — **two different pagers**; Parse is 50, Transform is 10. A merge that "resolves" this to one number would be wrong either way. |
| 1.8 | `pipeline-editor.md:698` omits `transform.sql` from `BRANCH_STEP_TYPES` — ✅ *this one I verified: the set does contain it (SQL-BRANCH-1, 2026-09-06), and the author-facing toast is wrong too → filed in `BACKLOG.md` §4.* |
| 1.9 | `GrammarEditorDialog` custody has **three incompatible lists, two of them inside `pipeline-editor.md`**; `:726-729` is the current one. |
| 1.10 | "Types & columns tab" / "Column metadata list" — split verdict: the metadata grid **is** gone, but "Types & columns" is a real current *section* (an expansion panel, not a `MatTab`). Stale only in the word "tab". |

**Group 2 — acquisition framework (`framework.md` · `data-acquisition-framework.md`)**

Authority: **`framework.md`** (the only as-built account). ⚠ It currently **disclaims its own authority**,
pointing at the requirements file, which points at the archive tier — a chain terminating in
never-maintained docs. All rows ⚠ **REPORTED** except where noted:

| # | Defect |
|---|---|
| 2.1 | `data-acquisition-framework.md:36` documents **`POST /sources/{id}/notify`**; the route is `POST /collectors/{id}/notify`. ⚠ `check-vocabulary.mjs` already records the identical error class for a documented `GET /sources`. |
| 2.2 | The same file uses a **`source.*` config prefix** throughout (`:16,32,38`, §13). ✅ *Verified by me in group 4: the parser reads only `collector`, with no alias, and no committed `*_pipeline.toon` has a top-level `source:`.* |
| 2.3 | `framework.md:48` gives `stability.sizeChecks`; the key is **`size_checks`** (`sizeChecks()` is the accessor). `pipeline-editor.md:248` already has it right. |
| 2.4 | "Phases A–F shipped on **`4.x`**" — that branch was deleted 2026-08-17 with its tags. Echoed by `acquisition/index.md`. |
| 2.5 | Three "(future)" claims the file's own status banner refutes — SSH tunnelling/proxy, event notification, and an SFTP+FTP-only delivered list. Proxy dial-through shipped. |
| — | ⚠ **Defaults are correct in both files** (ledger `memory`, staging `<dirs.temp>/acquire`, back-pressure 0, region `us-east-1`) — recorded so a merge does not "fix" them. |
| — | ⛔ Retiring the requirements framing needs two blocks extracted first: the connector-status banner and the **mounted-share / UNC-jail security note**, which exist nowhere else. |

**Group 3 — connectors / host-key / bastion (`acquisition/connectors.md` · `integrations.md` · `modules/connectors.md`)**

Authority: **`acquisition/connectors.md`**. `modules/connectors.md` **stays** (a `type: Module` stub
answering a different question) with two edits. Retire candidate is **only `integrations.md`'s
§"Remote source connectors"** — the file is *not* wholly subsumed: its second half is the DuckLake +
pg_duckdb warehouse layer, a different subject, and that half is what its inbound links target. It should
survive **retitled as the warehouse doc**.

| # | Defect |
|---|---|
| 3.1 | 🔴 `integrations.md:12` and `modules/connectors.md:16` call **S3/GCS/Azure "future"**; `META-INF/services` registers **eight** schemes (sftp, ftp, ftps, db, s3, kafka, azure, gcs). Two of three files call a shipped, SBOM-relevant capability future. NFS/SMB is a **declined** design, not a pending one. |
| 3.2 | 🔴 **`integrations.md:79` is RIGHT and the newer file is wrong** — the group's one case where the "Moved from" doc wins: `host_key` takes a **fingerprint** (the field is literally named `fingerprint`), so a raw key line cannot work. ⚠ **`docs/FEATURE_INVENTORY.md:147` is wrong too** (`host_key: "ssh-rsa AAAA…"`) — root canon, fix in the same change. |
| 3.3 | `modules/connectors.md:12` "all network dependencies" is stale by two — `kafka-clients` and `gson` are declared compile deps. ⚠ `connectors.md:95` also mis-frames gson as merely transitive. |
| 3.4 | `integrations.md:18` lists 2 of 5 secret schemes and omits the SEC-07 edition gate making `${FILE}`/`${KEYSTORE}` Standard+Enterprise only. |
| 3.5 | The DuckLake `INSTALL … FROM core` vs sandbox-seal tension is **reconcilable and the docs should say so** (the registrar uses a fresh unsealed connection); but the owning class is `DuckLakeRegistrar`, not `CollectorProcessor` as `integrations.md:218` says. |

🔴 **Merge hazard — group 3 must edit the guard in the same commit.** `tools/check-vocabulary.mjs` holds a
**path-keyed** `DOC_ALLOW` waiver for `integrations.md::source-acquisition-entity`, justified as "Remote
*Sources* are data origins". <!-- vocab-allow: quotes the guard's own waiver justification --> That justification does **not** cover the passages using `source:` in the
banned acquisition-entity sense. Retiring that section makes the waiver unused → the guard's
self-retirement rule fails the build; carrying the prose into `connectors.md` fails there because it has no
waiver. This is the §4.2 constraint with a concrete instance.

**Group 6 — `StreamingFileIngester` / parsing frontends**

Authority: a **split** — `parsing-options-reference.md` owns the config surface (the only file right on the
two biggest disputes), `plugins.md` owns the SPI author contract. Retire candidate:
**`engine/parsing-grammar.md`** (40 lines, read end-to-end, self-identifies as a pointer page), salvaging
the TOON scalar-list trap.

| # | Defect | State |
|---|---|---|
| 6.1 | 🔴 Plugin config home: `plugins.md` uses only `processing.ingester/segments`, `parser-plugins.md` only `frontend: plugin` + `parsing.plugin.*`. **Both are live — it is a 3-way precedence**, and `parsing-options-reference.md:214-216` is the *only* file that says so. ⛔ A merge must carry that sentence. | ⚠ REPORTED |
| 6.2 | 🔴 `parser-plugins.md:190-191` states `asn1` is "**never** a `parsing.frontend` value … and always will be"; `FRONTENDS` has **ten** tokens including `asn1`, `xlsx`, `excel`, `parquet`. **The emphatic claim forbids what the compiler accepts.** | ⚠ REPORTED |
| 6.3 | Frontend counts are given as three / six / seven / "two engines" / "four built-ins" across the group. Root cause: **"frontend" names two different concepts** — byte→row mechanisms vs legal config values. Must be split, or "exactly three" reads as a false constraint. `parquet` is documented nowhere. | ⚠ REPORTED |
| 6.4 | `parser-plugins.md` self-contradicts on ASN.1: `ingestable: true` at `:65-66` vs "preview-only (no `ingesterClass()`)" at `:143-144`. The former is right. | ⚠ REPORTED |
| 6.5 | `parser-plugins.md:23` cites `@PublicApi 5.3.0` — **fabricated**; the real value is `4.0.0` and `5.3.0` has zero hits repo-wide. | ⚠ REPORTED |
| 6.6 | `plugins.md:166-167`'s example uses the **deprecated** `processing.batch.max_files` alias while its own `:86` uses the canonical `collector.consignment.max_files`. | ⚠ REPORTED |
| 6.7 | 🔴 `architecture.md` listed `StreamingFileIngester` as a **ServiceLoader SPI**. It is **FQCN reflection** off `schemas().ingesterClass()` (`UnionModeIngester.java:181`) — no `META-INF/services` entry. `ParserPlugin`, which *is* one, was missing from the list. **22 SPIs ship in `src/main`.** | ✅ **VERIFIED · FIXED** |

**Group 7 — architecture (`architecture.md` · `architecture-layers.md` · `stage1-architecture.md` · `overview.md`)**

⚠ Two of my own premises were corrected here: `stage1-architecture.md` is **not** barely referenced (16
inbound, more than `architecture.md`'s 14); only `architecture-layers.md` (3) is. Authority:
**`architecture.md`** — the only one of the four that knows the reactor split happened.

| # | Defect | State |
|---|---|---|
| 7.1 | `architecture.md` put `ops/` in `inspecto-engine`; EDG-01 cell 7 moved `com.gamma.ops` to `inspecto-ops` the day before. | ✅ **VERIFIED · FIXED** |
| 7.2 | `architecture.md` had **no link at all** to the deep layer map. | ✅ **VERIFIED · FIXED** |
| 7.3 | 🔴🔴 `architecture.md` deferred to `reactor.md` as "authoritative map". **That target is stale**: headed *"Reactor shape (2026-07-22)"*, it mentions `inspecto-ops`, `-events`, `-metrics`, `-exchange`, `-geo-link` **zero times**. So no file in the group — nor the one they defer to — knew the current module list. Now flagged in place, with the count (23 = 14 default + 9 profile) and "rebuild from `pom.xml`". | ✅ **VERIFIED · FIXED** |
| 7.4 | 🔴🔴 `architecture-layers.md:27` calls the backend a "**layered monolith**" — a pre-reactor-split premise, so its §1 diagram, §2 package table and §2 "extraction blockers" describe a shape that is gone (~40 % actively wrong, not merely stale). ⚠ **This session's own frontmatter pass made it look authoritative**, so a 🔴 warning now sits at its top naming what is still sound (§5's two event buses, §7's DuckDB/`SqlSandbox` boundary). **Retirement candidate.** | ✅ **VERIFIED · WARNED** |
| 7.5 | `architecture-layers.md:123` claims "**Eight** ServiceLoader SPIs" — **22** ship. Two of its stated SPI *gaps* are refuted by code (`PipelineNodeType` has implementors; `RouteModule` is wired as a registry). | ✅ VERIFIED (count) · warning added |
| 7.6 | `overview.md:27` says "the Maven artifactIds were **not** renamed (so dir ≠ artifactId)" — false for 13 of 14, and its 6-row module map omits ~16 modules. ⚠ Audit recommends merging `overview.md` into `architecture.md` rather than keeping it as a peer. | ⚠ REPORTED |
| 7.7 | `architecture-layers.md` uses `BatchEventBus`/`BatchIngestStrategy`; all five `Batch*` names have **zero** files in any `src/main` (the rename shipped as `Consignment*`). It also calls that rename "still pending". | ⚠ REPORTED |
| 7.8 | Version inflation across all four: `4.8` and `5.3.0` are fabricated, `4.3.0` is wrong (real `4.0.0`); ceiling is `@PublicApi since = "4.0.0"` and the newest tag is `v3.12.0`. | ⚠ REPORTED |
| 7.9 | ⚠ **Two "stale" findings that must NOT be acted on.** (a) `stage1-architecture.md:409` says `route:` "waits on the branch-aware executor" — the *conclusion* is right (the at-rest route still refuses one) but the reason is stale; rewriting the reason is fine, **deleting the rule removes a live refusal**. (b) The audit first flagged `PipelineLift.stageTwo` as stale and then **self-corrected**: it is the live at-rest lift, called and tested. ⛔ Do not "fix" it. | ⚠ REPORTED (do-not-touch) |

**Group 8 — auth / OIDC (`editions/auth-security.md` · `modules/security.md`)**

Authority: **`auth-security.md`**. All rows ⚠ **REPORTED**:

| # | Defect |
|---|---|
| 8.1 | `modules/security.md` says jlink is "not yet re-verified against Nimbus; run with `-NoRuntime` until it is". `package.ps1:20-25` records PKG-4 **VERIFIED 2026-07-07** and `REQUIREMENTS.md` has it SHIPPED/RESOLVED. It is reportedly **the last place in the repo claiming PKG-4 is open**. |
| 8.2 | `security.md`'s only caveat is that retired one, and it omits both live ones — the no-boot-without-`-Dauth.oidc.jwksUri`/`.issuer` precondition and SEC-SIDECAR-BOOT-1. Its `RoleMapper` "token claims → Roles" description is the pre-R1 mechanism, and "the fifth Maven module" is a July ordinal. |

⚠ The audit recommends **retiring `modules/security.md`** (30 lines, every bullet subsumed except the
wrong one). Plausible, but ⛔ **do not act on it without reading the file** — the same audit pass
recommended retiring `toon-config.md`, which turned out to hold three unique load-bearing facts (§5.8.1).

**Group 9 — launch flags / `package.ps1` (`operations.md` · `operations-reference.md` · `build-test.md`)**

🔴 **The damaged group.** `operations-reference.md` — 66 KB, moved wholesale, and until this session the
only file in the bundle with no frontmatter at all — had drifted on twelve cross-cutting facts. Authority
is a **three-way split, not a merge**: `operations.md` owns the curated flag table and entry points,
`build-test.md` owns build/reactor/`package.ps1`/bundle, and `operations-reference.md` keeps only its long
tail (`ura` suite, CSV schemas, retention, crash ordering, exit codes, container).

| # | Defect | State |
|---|---|---|
| 9.1 | §"Status backend" was headed **"file (default)"** and its body said the default reads on-disk artifacts. The default flipped to **`db`** on 2026-08-31 — `OperationalDb.java:132` declares `STATUS("Status", "status.backend", "db", …)`. ⚠ `ServiceStores.java:252`'s javadoc is stale the same way — do not settle this against it. | ✅ **VERIFIED · FIXED** |
| 9.2 | ⛔ **`-Dcontrol.token` is a DEAD flag — zero Java readers.** It survives in 3 launch examples plus **27** `Authorization: Bearer secret` curls and **6** `CONTROL_TOKEN` uses, none of which authenticates anything; `package.ps1`, `serve.sh` and `serve.bat` still emit it inertly. | ✅ **VERIFIED** · file demoted from auth authority with a prominent block; the 36 individual examples are **left** for a dedicated pass |
| 9.3 | ⛔ **`-Dui.static.log=DEBUG` does not exist** in any Java source — yet two places instruct you to enable it or "the abort will be invisible". | ✅ **VERIFIED** · named in the demotion block |
| 9.4 | Java floor stated as **"Java 25 or later"** in two places. `pom.xml:165` `maven.compiler.release` is **24**, `package.ps1:49` says "Java 24+", and the shipped container is `temurin:24-jre`. Three real floors exist: **24** product / **25+** agent modules / **26** build toolchain. | ✅ **VERIFIED · FIXED** (both places) |
| 9.5 | `java -jar` (1 use) and `mvn clean package` (1 use) are both wrong: the launcher uses `-cp` (RUNSH-CP-1) and packaging is `-pl inspecto,inspecto-connectors -am -DskipTests -q`. ⚠ `package.ps1:43`'s own header is reportedly stale the same way. | ✅ named in the demotion block · ⚠ the `package.ps1` header is REPORTED |
| 9.6 | `operations.md` frames `-Dauth.mode` as an "edition runtime toggle"; it is read only as a *label* (`BootstrapRoutes.java:55,83`) — the classpath is the switch. | ⚠ REPORTED |
| 9.7 | `-Dspaces.root` documented with "default `./spaces`" in two files; reportedly there is **no flag default** (unset ⇒ single-tenant) and `spaces` comes from `serve.sh:8`. | ⚠ REPORTED |
| 9.8 | `/metrics` described as needing "no extra agent or sidecar"; the exposition moved to the optional `inspecto-metrics` and **503s on Personal**. | ⚠ REPORTED |
| 9.9 | `build-test.md` says "**seven** edition modules … the **five** EDG-01 ones"; reportedly **nine and seven**. Its 23/31/32 module and test counts are correct. | ⚠ REPORTED |
| 9.10 | `operations.md` documents no `-Dcontrol.bind` — reportedly **the unauthenticated-exposure flag**, and it should be in the curated table. | ⚠ REPORTED |
| 9.11 | Banned vocabulary: `operations.md` "config/**flow**/connection write-back"; `operations-reference.md` carries an entire "**Issue** Tracker" section while the same file says Incident elsewhere. ⚠ Neither trips the guard (the rules do not match these positions) — so this needs a prose pass, and the heading rename carries anchor-link risk. | ⚠ REPORTED · heading named in the demotion block |

**Group 10 — Spaces (`control-plane/multi-space.md` · `conventions/multi-space.md` · `features/spaces.md` · `configuration.md` §Spaces)**

🔴 **The layer split is NOT the problem here** — the audit's original "clearest case where the layer split
created the duplication" is refuted. The backend and frontend files barely overlap and agree where they do.
Both real errors live in the **fourth** file: `configuration.md` §Spaces grew an API section it cannot
maintain. Authority: **`control-plane/multi-space.md`** owns concept + wire protocol; `configuration.md`
§Spaces should shrink to on-disk layout + `SpaceMigrator`, with all route prose removed.

| # | Defect | State |
|---|---|---|
| 10.1 | `conventions/multi-space.md` listed `SERVER_GLOBAL` as **6** paths; `space.interceptor.ts:13` has **8** — `/public` (BI-6 anonymous share tokens) and `/exchange` were missing, so a doc reader would wrongly expect those to be space-scoped. | ✅ **VERIFIED · FIXED** |
| 10.2 | `configuration.md:45` gives `GET /spaces/acme/pipelines` as the example; it **404s** — business routes serve only under `/api/v1`. Both multi-space files state the after-`/v1` rule correctly. | ⚠ REPORTED |
| 10.3 | `configuration.md`'s CRUD list omits `GET /spaces/_meta` and `GET /spaces/templates`. | ⚠ REPORTED |
| 10.4 | `DELETE /spaces/{id}` has **two distinct 409s** — a single-tenant refusal and a last-space-on-disk purge refusal — documented one each in two different files. State both or neither. | ⚠ REPORTED |

⚠ **Retire nothing in group 10.** `conventions/multi-space.md` keeps 3 unique facts plus the enforceable
"don't re-roll per-space logic in features" rule, which has no backend home; `features/spaces.md` is not
subsumed either (branding, slug derivation, template UX, the export/import constraint live nowhere else).

#### 5.8.1b Adjudication evidence, and what is still un-applied

The four adjudications are preserved in-repo at
[`design/docs-consolidation-adjudications/`](design/docs-consolidation-adjudications/) — `adj-g1.md`,
`adj-g23.md`, `adj-g45.md`, `adj-g6789.md` (158 KB). Each carries the per-row verdict, the `file:line`
evidence, and a **ready-to-apply `FILE:` / `OLD:` / `NEW:` block** for every `UNDISPUTED-DOC` row. They are
working files, not canon: retire them with the plan.

**Applied 2026-09-08: ~60 edits.** Groups 1, 2, 3, 4, 5 are fully applied; group 6's `parser-plugins.md`
and `parsing-options-reference.md` rows, group 7's `architecture-layers.md` row, group 8's
`modules/security.md` row and group 10's `configuration.md` rows are applied.

✅ **ALL 31 blocks in `adj-g6789.md` are now applied (2026-09-08).** 28 fenced + 3 inline, every one
accounted for. The last pass closed groups 7, 8 and 9 — including the two highest-value ones:

- `overview.md:27` claimed "the Maven **artifactIds were not** [renamed] (so dir ≠ artifactId)". It is
  **22 of 23 with `dir == artifactId`**, the sole exception being `inspecto/` → `inspecto-processor` —
  which the file's own table already proved, and which the `java-backend` skill states outright. Its
  `:48` release-line claim (a live `4.x`) is corrected too: `master` is the only line, `4.x` and its
  `v4.0.0`/`v4.0.0-RC1` tags were deleted 2026-08-17.
- `build-run/operations.md` gained the missing **`-Dcontrol.bind`** row — the unauthenticated-exposure
  flag: unset means EVERY interface, in every edition, and Personal ships no `Authenticator`.

Also: `stage1-architecture.md`'s "Since multi-space (4.x)" and `operations-reference.md`'s ⛔ *Issue*
Tracker heading (renamed only after confirming **nothing** links `#issue-tracker`; the one live inbound
anchor is `#batch-processing`, untouched). `inspecto/package.ps1`'s header lost its `java -jar`
instruction — comment-only, verified parse-clean — because the launcher uses `-cp` (RUNSH-CP-1), and
`java -jar` ignores `-cp` outright, making every sidecar unreachable.

⛔ **Both do-not-touch warnings were honoured and re-verified:** `stage1-architecture.md`'s at-rest
`route:` refusal keeps its RULE (only the stale parenthetical reason went — the rule is pinned by
`PipelineStageTwoLiftTest`), and `PipelineLift.stageTwo` is untouched.

🔴 **A trap worth recording: two of these blocks are INSERTIONS whose `OLD` survives inside their
`NEW`.** An "already applied?" check that only asks whether `OLD` is absent reports them as pending
forever, and re-running would have **duplicated** a `-Dcontrol.bind` table row and a two-409s note.
Verified each inserted item appears exactly once. When re-applying a block set, test whether `NEW` is
already present — not just whether `OLD` still is.

⚠ One block carried a deliberate distortion for the working file's sake: row 7.6's `NEW` had its
`./architecture-layers.md` link repointed so `adj-g6789.md` itself would satisfy `check-doc-links.mjs`.
Applying it verbatim put the wrong depth into `overview.md`; the recorded caveat and the guard both
caught it, and the sibling form is restored.

**Also applied, in code** (javadoc-only, verified by a full `mvn -o clean test`: 23 modules / 3777 tests /
0 failures): `Parsers.java` said "the four built-ins" where `BuiltinParsers.IDS` has **six**;
`ParserRoutes.java` carried a fabricated **`v5.3.0`** — the source the docs' phantom version came from;
`PipelineProjection.java` still described a `map` verb authoring `transform.map`, deleted 2026-09-05.
Fixing the source is what stops the doc error regrowing.

#### 5.8.2 THE DISPUTES BUCKET — two items, and both need an operator call

Every register row was re-grounded against the implementation on 2026-09-08 by four parallel
adjudications, each required to settle behavioural disagreements against source and to classify every row
as `UNDISPUTED-DOC` (code clear, doc wrong), `UNDISPUTED-CODE` (docs right, the code is the defect) or
`DISPUTED`. **Of ~50 rows, 47 were undisputed and are fixed. Three were disputed; one of those I closed by
measurement rather than decision.** These two remain.

---

**D-1 — `configuration.md` §2: the doc describes a shape the tool still emits, but the engine no longer
prefers.** *(Row 4.4. The only dispute where the resolution is probably a CODE change.)*

The evidence on both sides is confirmed:

- **The engine prefers `fields[]`.** `DataTransformer.recordFields` reads `mapping.fields` first and treats
  `mapping.rules` as a converted legacy fallback; `TransformCompiler` says verbatim that "the rules
  themselves no longer compile here".
- **The generators still WRITE `rules[]`.** `SchemaExtractor.java:193` does `mapping.put("rules", rules)`
  and `ConfigPreviewRoutes.java:335` emits `transformType: DIRECT`. And `configuration.md:160` frames §2
  explicitly as "**Machine-generated** by `create-schema`".

So ~90 lines of §2 are *not* stale in the way the register assumed — they document what the tool actually
produces today. ⛔ **Rewriting the doc alone would make it describe a file shape nothing emits**, which is
strictly worse than the current state. `transformType` also remains a live validated vocabulary
(`MappingRules` rejects typos; the `FILENAME_DATE`→`EVENT_DATE`-only refusal is armed).

| | Option | Consequence |
|---|---|---|
| **A** ✅ | **Switch the generators to emit `fields[]`**, then rewrite §2 around it | One authored spelling end to end. Touches `SchemaExtractor` + `ConfigPreviewRoutes`, and needs a fixture sweep — **24 committed `*_schema.toon` still carry an inline `mapping:` block and zero are migrated** (the single `*_mapping.csv` in the tree is untracked). This is the same debt `RECORD-TRANSFORMER-1` was filed for. |
| B | Keep §2 leading with `rules[]`, labelled "legacy but still generated" | Zero code risk, but freezes two authored spellings and keeps every reader deciding which one to write. |

**Recommendation: A**, staged — emit `fields[]` from the generators, keep the `rules[]` READ path
(it must stay: hand-authored and already-committed configs depend on it), migrate the 24 fixtures, then
rewrite §2. ⚠ There is currently **no BACKLOG row holding this**: `RECORD-TRANSFORMER-1` survives only as
absorbed sub-item (d) inside Row 15. Two sub-facts are settled and safe to fix independently of the
decision: `configuration.md:222` cites `TransformCompiler.direct`, a method that no longer exists (the
coercion moved to `RecordTransform`'s `keep`), and `:247` names the removed compile lane.

---

**D-2 — is `overview.md` a peer of `architecture.md`, or should it be merged in?** *(Row 7.6's structural
half. The factual defect in it — "the Maven artifactIds were not renamed", false for 22 of 23 dirs — is
UNDISPUTED and already handled; only the structure question is open.)*

| | Option | Consequence |
|---|---|---|
| **A** ✅ | **Keep the peer, decide it once inside the capability spine (§5.1)** | `overview.md`'s non-duplicated value is its "Design ethos" block plus its pointer set; `architecture.md` is the as-built map and is already the group authority. Costs nothing now. |
| B | Merge `overview.md` into `architecture.md` | Folds the module map into the file that now carries the correct 23-module count, but relocates 14+ inbound links and grows the single most-linked architecture doc. |

**Recommendation: A.** Every `okf/` section has an `overview`/`index` pair; deciding this one file's fate in
isolation would settle by accident a question that §5.1 has to answer for all sixteen areas at once.

---

**D-3 — CLOSED 2026-09-08 by measurement, not by decision.** `modules/security.md` claimed
`-Pedition-standard` adds "41 tests" and nothing in the repo could confirm it. Ran it: **`inspecto-security`
has 34 tests** (`OidcAuthenticatorTest` 24 + `OidcTokenRelayTest` 7 + `FileKeystoreSecretsProviderTest` 3),
inside a 31-module / 4106-test reactor; the bare default reactor is 23 / 3777. Doc corrected. *A dispute
that only needed a number is not a dispute — run the command.*

#### 5.8.2 Where the drain stands

**Done:** the reframing (the pairing is by design; the defect was tier illegibility) · frontmatter + a tier
statement on all 12 deep docs · **9 verified content defects fixed** across `toon-config.md`,
`configuration.md`, `architecture.md`, `operations-reference.md` and `conventions/multi-space.md` · the
register below for the rest.

**Deliberately not done, and why:** ~25 rows are marked ⚠ **REPORTED** — an audit found them, I have not
confirmed them against the tree. Acting on all of them would mean rewriting ~330 KB of the most-linked
tier in the repo on unverified claims, and in this same session **two audit claims proved wrong in
opposite directions**: one called a live `BACKLOG` row (GAP-4) shipped, and one called a file with three
unique load-bearing facts (`toon-config.md`) redundant. ⛔ **Verify each row against source before acting
on it** — the register cites where to look for every one.

**Also outstanding:** the 15 `okf/` content files (~237 KB) still carrying no frontmatter, and the two
retire *recommendations* (`modules/security.md`, and trimming `conventions/multi-space.md`) that need a
human read first.

### 5.8.3 The ACQ pilot — BUILT 2026-09-08

[`okf/capabilities/acquisition/acquisition.md`](../okf/capabilities/acquisition/acquisition.md) — the
first capability spec, all eight sections, **75 KB**. The tier lives at
[`okf/capabilities/`](../okf/capabilities/index.md) with its own listing, and a new frontmatter
`type: Capability` (nothing validates the type vocabulary, so the addition is free).

**Size budget held, barely.** §3 Specification is **39.6 KB** against the ~40 KB split threshold of §5.3,
so ACQ stays a single document. ⚠ That is the *smallest* real area by as-built weight — `PIP` carries 440 KB
across 23 files and will certainly split. Treat 40 KB as a real trigger, not a formality.

**What writing §2 found, which is the argument for the whole exercise.** Three defects in
`REQUIREMENTS.md` §3.1, each verified against source and each fixed in both places:

- **`ACQ-4` read a flat `SHIPPED`** while half its scope — the NFS/SMB network-share half — had been
  **refused by design**. A `Must` recorded green over a refusal is what makes a requirement register
  untrustworthy; it is now 🟡 PARTIAL with the refusal owning §6.1.
- **`ACQ-6`'s route** was `POST /sources/{id}/notify`; it is `/collectors/{id}/notify`.
- **`ACQ-7`'s config key** was `source.duplicate.mode`; the block is `collector.duplicate`. A fourth,
  `source.discovery` → `collector.discovery`, turned up while checking and is fixed too.

🔴 **And the thing no requirement row said:** for **85 days the remote connectors were build-available and
deploy-absent** — `inspecto-connectors` was a reactor module no bundle shipped, so `connector: s3` could
be authored, compiled and tested and did nothing in a deployment (fixed 2026-09-07). *A requirement is not
delivered until it is reachable.* §2 now carries that as a standing note, because no status token can.

**Verification of the assembled doc, because 526 of its lines were distilled by an agent.** Two
independent passes (each with a mandatory control probe) agree: **33/33 referenced source files exist,
46/46 named test classes exist, every real config key and route resolves.** The residual flags are all
explained by the doc's own framing — `acquire.maxFilesPerCycle` is listed in §5 as unbuilt and
`POST /components/connection/{id}/test` in §6.4 as superseded, so both are *correctly* absent from code.
One genuine imprecision was found and fixed: §3.9 was headed "the eight registered schemes", but eight is
the count of the **optional module**; ten values resolve (8 in `inspecto-connectors` + `dataset` in
`inspecto-engine` + the built-in `local`), and a bundle without the sidecar resolves only two.

⚠ **The first verifier reported EVERYTHING missing — including files I had grepped minutes earlier.**
`subprocess.run(..., shell=True)` on Windows runs **cmd.exe**, where `find`/`grep`/`cat` are not the Unix
tools, so every probe returned empty. Same zero-for-everything shape as the AGT-5 gate. A verification
harness needs a control probe *before* its results are read — the fixed one now fails loudly if the
control does not match.

#### The migration verdict for ACQ's six source files

| File | Verdict |
|---|---|
| `acquisition/connectors.md` | **KEEP** — the densest and most nearly correct of the six; the ACQ mechanism tier |
| `acquisition/framework.md` | **KEEP** — ~40 % is unique (the two-timer/two-guard split, B4 back-pressure, the MDC-routed singletons) |
| `acquisition/data-acquisition-framework.md` | **Requirement body absorbed by §2; its 75-line delivery banner collapses.** ⛔ Two blocks extracted first: the mounted-share/UNC note (now §6.1) and the connector-status banner |
| `backend/integrations.md` §"Remote source connectors" | **ABSORBED into §3.10–§3.12.** The file survives **retitled as the warehouse doc** — its second half (DuckLake + pg_duckdb) is a different subject and is what its inbound links target. 🔴 Its path-keyed `DOC_ALLOW` waiver must be re-keyed in the same commit |
| `modules/connectors.md` | **KEEP** — answers packaging, not behaviour |
| `acquisition/index.md` | **KEEP** — the section map, now pointing *up* to the capability spec |

**Net: one file's first half is absorbed; no file is deleted.** That is the shape to expect per area —
consolidation here means *one authority per subject*, not fewer files.

✅ **The owed tail was drained 2026-09-08** (`050cff9f`): `integrations.md` lines 15–203 became
`acquisition/connectors-runbook.md` (the ACQ spec's §7 cites it as the runbook tier — *absorbed* meant the
spec content, not the copy-pasteable how-to), the file was retitled as the DuckLake/warehouse doc, the waiver
was **deleted** rather than re-keyed (adjudication `adj-g23` §A: its only hit was the H1), the moved text's
`source.*` keys were corrected to `collector.*`, and the 64-line delivery banner collapsed to a pointer.
The two ACQ ORPHANs had already landed (§5 rows for the controller-service gating + list/fetch split; §6.2 for
the `inspecto-connect` reversal). **Still owed for ACQ:** 🔴 **five UNTRACKED items §5 surfaced that have no board
row**, the substantial one being *credentials & network profiles as their own referenced resources* —
today a `tunnel` is inline on the Connection, so two Collectors through one bastion duplicate it and a
rotation edits N places.

### 5.9 What the layer split has actually cost — the operator's complaint, measured

**12 of the 16 areas have their as-built truth split across `okf/backend/` and `okf/frontend/`.** Worst
cases, and they are not marginal:

- **`ING`** — **75 KB of parse-and-mapping truth sits in `frontend/features/`** (`grammar-config.md`,
  `schema-mapping-authoring.md`), away from `backend/engine/parser-plugins.md` and
  `backend/config/parsing-options-reference.md`.
- **`BI`** — `Widget Builder` and `Dashboard Builder` appear **zero times** under `backend/`, and `/bi/query`
  gets **two lines** in the whole tier; `frontend/features/studio.md` is the de-facto backend spec.
- **`INV`** — `Entity Projection`, `GeoQuery` and `MapLibre` appear **zero times** under `backend/`.
- **`INC`** — 🔴 **no concept file exists anywhere**; incidents survive only as fragments in
  `engine/db-layer.md` §3.1, `control-plane/events-metrics.md`, `jobs.md` and `tags.md`.

This is the concrete form of "it's difficult to find information": for twelve of sixteen capabilities the
answer is in two trees, and for four of them one side is simply missing. The capability doc's §7 (as-built
pointers) is what re-joins them without moving a file.

**And it is also why `okf/backend/**` and `okf/frontend/**` must NOT be re-homed functionally.** 16 files —
**426 KB, 28 % of the tier** — span five or more areas, and 47 span four or more. `step-catalog.md`,
`gotchas/cross-cutting.md`, `architecture-layers.md`, `agent/embedded-intelligence.md`,
`control-plane/jobs.md`, `engine/db-layer.md`, `signal-backbone.md`, `tags.md` and
`features/pipeline-editor.md` are cross-cutting **because the file's value *is* the join** — one bus with
many consumers, one label graph over five entity kinds, one lift between editor and config, one store
inventory for the whole platform. Filing each under a single capability would destroy the thing that makes
it worth reading.

---

### 5.10 ⚠ One active plan cannot be archived yet — `deployment-topology-plan.md`

The 8 active plans hold **~51 open items, of which 20 are not tracked in `BACKLOG.md`** — so a naive
"distil and archive" would silently drop them. The distribution is lopsided:

| Plan | Durable spec | Open items NOT in BACKLOG | Inbound citations |
|---|---|---|---|
| `deployment-topology-plan.md` | ~30 % | **12** | 2 docs + 1 code |
| `elt-final-amendment-plan.md` | ~15 % | 2 | 4 docs + 2 plans + 1 code |
| `agt-6-plan.md` | ~18 % | 2 | 6 docs |
| `pipeline-spec.md` | ~20 % | 1 | **10 docs** + 1 plan + 2 code |
| `pipeline-waves-drain-plan.md` | ~7 % | 1 | 3 docs + 1 plan + 1 code |
| `completeness-kpi-plan.md` | ~35 % | 1 | 2 docs + 1 plan + **3 code** |
| `compliance-certifications-plan.md` | ~13 % | 0 | 2 docs + 1 plan |
| `parser-field-tiers-interview-plan.md` | ~50 % | 0 | 2 docs |

🔴 **`deployment-topology-plan.md` is the only one whose archival would lose *design*, not provenance.** It
holds 12 of the 20 untracked items (SCR-1/4/5/6/7, the §8 preflight, §9 VER, §4 sizing, T4 DR, the phases,
D5, D7), and **its stated distillation target `okf/backend/build-run/deployment-topologies.md` does not
exist** — there are zero hits repo-wide for `warm standby` or `systemd`. Its content must be written into a
`PKG`/`OPS` capability doc *before* it moves, and that is real authoring work, not a `git mv`.

Conversely `pipeline-spec.md` has the widest blast radius — **10 citing docs plus 2 code references** — so
it is the one to move last, and only together with `pipeline-waves-drain-plan.md`.

## 6. Sequencing

Each step names its own verification, so the work can loop without re-clarification. Steps 0-2 decide
whether this works; everything after step 3 is replication.

| # | Step | Verify |
|---|---|---|
| 0 | ✅ **DONE 2026-09-08** — `tools/check-doc-links.mjs` written, wired into `.githooks/pre-push` + `ci.yml` | Falsified six ways (broken current link · that link made valid · a broken archive-internal link · a current link into a missing archive file · external/anchor targets · the emptiness floor). **Baseline: 1,378 links, ZERO dangling** — 14 pre-existing breaks were fixed in the same change (§6.1), so every later step's floor is zero, not a tolerance |
| 0b | ✅ **DONE 2026-09-08** — the 5 directories joined `DOC_TREES`, all 13 violations drained, the `type: flow` instruction corrected | Guard green over 200 docs with the wider scope, and the scope itself **falsified per directory** with tracked probes (§9) |
| 1 | 🟡 **FIRST CUT 2026-09-08** (`7d2a3c27`) — `REQUIREMENTS.md` §8's four archive rows now point at current homes with a provenance line; `review-coverage.md`'s `resource:` names the reactor, not the archive; **all 5 bookkeeping defects fixed** (INDEX 46→54 rows incl. the 2 missing; `legacy-surface-removal` header; `4x-public-pkce` note; `metadata-network-design` links). **Remaining:** the other ~16 authority citations — ~~`docs/api/README.md` "the design"~~ and ~~`api-v1.md` "design of record"~~ (both absorbed by the `API` spec 2026-09-08), `GLOSSARY.md`'s four rationales, `grammar-config.md:126`'s mockup, the okf "full phasing"/"grounded refutation" delegations, `completeness-kpi-plan.md:21` | no current-tier doc cites an archive file *as its authority*; link guard green |
| 1b | 🟡 **2 of 7 DONE** — the two ACQ ORPHANs landed in the pilot (`acquire-controller-service-design` → §5; `brainstorm-tingly-storm` → §6.2). Remaining 5 land with their area: `modularization-optimization` C2/C4/C6 → `editions/` §6 · `snazzy-painting-platypus` → `surfaces/` §6 · `system-maintenance-plan` COULD list → `observability/` §5 · `frontend-review-and-completion-plan` → the `angular-ui` skill · `claude-usage-audit` → none (meta) | each ORPHAN named in the audit has a destination; BACKLOG §6 stops citing an archive file as the sole home of C2/C4/C6 |
| 1.5 | **Drain the `okf/` duplication debt** (§5.8) — the 10 subjects stated 2-4× and the 17 surviving `> Moved from` banners | one account per subject; ⚠ **must precede step 3**, or the contradictions become load-bearing |
| 2 | ✅ **DONE 2026-09-08** (`64cc47d5`) — `GLOSSARY.md` §14: every area named, directoried and defined; `INV`→Studio, `OPS`→Observability & maintenance, `EOI`→Assistant, `UI`→Surfaces & Lenses, `API`→Control API; **Edition**, **Control API**, **Compliance**, **Guard** entries added; `CMP` and `TOOL` areas created (§5.1.1) | every directory name resolves to a `GLOSSARY` entry; vocabulary guard green — **verified: 207 docs clean** |
| 3 | ✅ **BUILT 2026-09-08** (`b8bcb803`, tail `050cff9f`) — `ACQ` (§5.8.3): all eight sections, 75 KB, machine-verified twice; both ACQ ORPHANs landed; the `integrations.md` split and delivery-banner collapse done. ⚠ The template has not been *judged* by the operator yet — the next area is the moment to change the shape if it is wrong | operator signs off the 8-section template **on the real thing** before it is replicated |
| 4 | Replicate per area, **one commit each**. 🟡 **16 of the 17 slots done** — **`PIP` authoring** (`okf/capabilities/pipeline-authoring/pipeline-authoring.md`, 2026-09-09, 47 KB; the area with the most competing documents so far — 22 current files claiming authority, two active plans holding D1–D10, and **no `Practice`-typed doc at all**. 🔴 **`SqlGuard` is absent from EVERY save path** — it runs at `/components/transform/describe` and at execution (10 call sites) and **zero** times at save, so author SQL with DDL/DML or `read_csv(...)` saves and arms cleanly and fails only at run; no test covers it. Fail-late, not fail-open. 🔴 **`fields[]` is documented as inert in `pipeline-config-keys.md` and as executable in `pipeline-editor.md`** — code says executable (`RowShaper.MAP_NODE_CONFIG_KEYS`), and that wrong reasoning is precisely what left the client mirror carrying 2 keys where Java carries 3 (the `BACKLOG` §4 drift). 🔴 **Four counts each stated 2–3 ways and the AUTHORING doc is wrong every time**: node types **30** (docs 20/28/20), recipe **16 entries over 9 verbs with no `map`** (docs: 9 incl. a verb deleted 2026-09-05), processors **119** (docs 121), transform functions **23** (docs 24 and “~20” in one file). 🔴 **Two docs cite `MULTI_JOIN`/`MULTI_SINK` as live refusals** — only `MULTI_PARSER` and `MULTI_MAP_CONFIG` exist; the others survive only in a Javadoc explaining their deletion. 🔴 **An unknown node type is only a WARNING** — the codec stores config unchecked and the compiler silently DROPS the node, so PIP-1's “author-time validation” does not hold there. 🔴 **The registration points are FIVE, not four** (`RecipeCompiler`'s verb switch is the missed one) and no test fails when one is skipped. `transform.merge` is executable code with **no authoring or persistence route at all**. 16 `UNTRACKED`. ⚠ **One sweep claim was FALSE and not propagated** — an alleged banned word in `pipeline-graph/index.md:29`; the line says nothing of the kind and the guard was green at HEAD). Previously 🟡 **15 of the 17 slots done** — **`PKG`** (`okf/capabilities/editions/editions.md`, 2026-09-09, 56 KB; **also the distillation target step 7 required**, so `deployment-topology-plan.md` §3.9–§3.13 is now durable and the plan can move once Phases 0–5 ship. ✅ **`tools/sbom.mjs` declared 4 first-party jars where Standard stages 11 and Enterprise 12** (10 and 11 of them first-party) — it knew none of the seven EDG-01 modules, javax.mail appeared in NO shipped SBOM, and its own comment claimed to be the staging table; it ships inside a signed archive and nothing tested it. **FIXED 2026-09-09**: the set moved to `tools/bundle-modules.mjs` and `tools/check-sbom-modules.mjs` guards it in CI. 🔴 **No Standard artifact is ever built** — `release.yml` does Personal + Enterprise, and four docs describe a bundle that does not exist. 🔴 **The launchers detect the edition from JAR PRESENCE**, so an “Enterprise (superset of Standard)” bundle enables ABAC for a Standard customer. 🔴 **`BootstrapRoutes.edition()` is two-valued** (from `auth.mode`, not from what registered) so Enterprise is unreportable — and the plan's own VER-2 acceptance row inherited the defect. 🔴 **EDG-01 cell 7 — the largest, 44 stubbed paths — has NO Personal-side test** (6 of 7 cells do). 🔴 **`OPS-07` marks the jlink runtime ✅✅✅ and no released bundle contains one** (every step passes `-NoRuntime`). D3 was signed as a 2 GB default that does not exist and the plan says three things about it in one file; the staged-jar set is stated EIGHT ways with five wrong incl. code; the Java floor is 26 in two docs and 25 on disk; 23 `UNTRACKED` items, 12 of them the deployment plan's own rows that have no BACKLOG home at all — `preflight`, `standby` and `disaster recovery` appear NOWHERE in `BACKLOG.md`). Previously 🟡 **14 of the 17 slots done** — **`TOOL`** (`okf/capabilities/tooling/tooling.md`, 2026-09-09, 40 KB; the guard ROSTER written down for the first time — `guard-coverage.md` is a rules register, not an inventory; 🔴 `check-coverage.mjs` has NO module floor: run here it blessed **1 module / 427 instructions** as "every floor met"; 🔴 `NFR-10` was wrong BOTH ways — not "enforced in review", and **3 of 7 bans have no rule at all**; 🔴 `NFR-9` names an a11y gate that does not exist and a live smoke that has never run; 🔴 `release.yml` has never executed so `package.ps1` has never run on Linux, where 32 destinations use a literal backslash; 🔴 the vocabulary guard's FIFTH scope gap is the instruction files incl. the file that DEFINES the bans (220 of 503 tracked md in scope); 🔴 **five capability specs cited `verify_cap.py`, which is not in the repo** — corrected in this commit; 18 `UNTRACKED`). Previously 🟡 **13 of the 17 slots done** — **`CMP`** (`okf/capabilities/compliance/compliance.md`, 2026-09-08, 30 KB; ✅ the FIRST register that holds up under its own rule — every class the matrix cites exists at the path it names; 🔴 the three holes the Studio/Assistant/UI specs predicted are ALL confirmed (no control for the anonymous public embed, the agent approval gate/kill switch, or accessibility); 🔴 the signing control has NEVER executed (the workflow and every SBOM/signing mechanism are 4.x-era work and no `v4.x` tag exists); 🔴 the ISO logging row promises auth events "arrive with the security module" — the module shipped and sign-in is still unaudited; the availability row was stale against its own gap ledger; 8 `UNTRACKED`). Previously 🟡 **12 of the 17 slots done** — **Surfaces & Lenses** (`okf/capabilities/surfaces/surfaces.md`, 2026-09-08, 48 KB; 🔴 three frontend docs say navigation is "filtered by the active Lens" when the code, the product decision and the user guide all say a Lens never hides a screen; 🔴 two docs tell a contributor to edit a nav file deleted with the mock backend, one as the definition of done for adding a page; `UI-3`'s "WCAG 2.2 AA" is a self-assessment with **seven findings still open** and NO compliance control; `UI-5` is provenance not a gate and no doc agrees on the route count; the design-system count is stated SEVEN ways; `EDITIONS` has no `UI` row yet claimed one, and the shell IS gated in three places; 21 `UNTRACKED` incl. a 35 KB unbuilt shell spec in `docs/wiki/`, 9 undocumented panes, 11 undocumented components). Previously 🟡 **11 of the 17 slots done** — **Assistant** (`okf/capabilities/assistant/assistant.md`, 2026-09-08, 50 KB; 🔴 seven `AGT` rows claim edition `All` and **no bundle ships any of the code** — `/assist/*` and `/agent/*` answer 503 in every artifact, deliberately (`PKG-5`); `EOI-7`'s "no SNAPSHOT anywhere" is refuted by the parent pom's `0.2.0-SNAPSHOT` pin of an unpublished upstream CI rebuilds from a branch head; the upstream version is stated FOUR ways and the repo name TWO; `okf/agentic/` pointed at a local path that does not exist; the tool-belt count is stated SIX ways (23, pinned by a test); `AGT-6a` is far more shipped than "PLANNED"; the agent's governance controls appear in NO compliance control; 22 `UNTRACKED`) · **Studio** (`okf/capabilities/studio/studio.md`, 2026-09-08, 56 KB; `INV-3` was in the wrong area AND promised `All` for machinery cell 7 had gated — the FOURTH missed neighbour; the PMTiles basemap does not exist; `INV-1`'s two "open" items had shipped; `studio.md` named a deleted directory and a route with no caller; 17 `UNTRACKED`). ⚠ **Bookkeeping note:** the Studio row, the `INDEX.md` entry and this cell were written in the Assistant commit — the Studio commit's own bookkeeping script failed silently and was not re-checked. Previously 🟡 **9 of the 17 slots done** (the `ACQ` pilot + areas #2–#9; ⚠ this cell read "8 of 16" until 2026-09-08 — it omitted the pilot and used a denominator matching neither the 15 areas nor the 17 slots of §5.2/`GLOSSARY.md` §14) — `OPS` (`okf/capabilities/observability/observability.md`, 2026-09-08, 54 KB; `OPS-2`'s cell said the exposition was ungated a day after EDG-01 gated it; "tamper-evident"/"immutable"/"sign-ins" overclaimed the audit log in `EDITIONS`, `USER_GUIDE` and `GLOSSARY`; `OPS-4`'s "off by default" was true of one ledger in three; `jobs.md` asserted the `memory_limit=2GB` default DAT had already refuted; `signal-backbone.md` said `causationId` was unthreaded when `JobService` threads it; `GET /signals/stream` has no client — the sixth "no consumer"; nine `UNTRACKED` items). `ING` (`okf/capabilities/ingestion/ingestion.md`, 2026-09-08, 52 KB; `ING-6` Expectations have NO UI — the fifth "no client consumer" Must; `ING-2` understated ten tokens/eight formats and a `parquet` built-in on no board; `EDITIONS` promised nested archives the engine refuses; `ingest-wrap-spi.md` named a config key that does not exist; `transforms-seams.md` still carried the pre-rename `Batch*Strategy` names and `DATA_RULES`; six `UNTRACKED` items). `DAT` (`okf/capabilities/data-plane/data-plane.md`, 2026-09-08, 49 KB; the SPA never calls `POST /queries/{id}/run` — `DAT-3`'s Must has no client consumer, the FOURTH such finding; the DuckDB `memory_limit` default was asserted by two pages and exists in no code; the Postgres store count read 6/7/10/11 on one page and is 9 of 12 measured; `status.backend` default was documented backwards; the warehouse half of `integrations.md` had no intent banner; seven `UNTRACKED` items). `MET` (`okf/capabilities/metamodel/metamodel.md`, 2026-09-08, 47 KB; `MET-1`'s Component shape is the SPA's model not the store; `MET-3`'s delete protection is `use:` refs + Exchange grants only; `component-registry.md` said `schema` is NOT a component 34 days after the reversal and called a stale `If-Match` "precondition-failed"; two current docs cited a BACKLOG row that exists only in the archive snapshot; 🔴 the Catalog read model `com.gamma.catalog` has NO concept file; eight `UNTRACKED` items). `SPC` (`okf/capabilities/spaces/spaces.md`, 2026-09-08, 42 KB; §2 found `SPC-3` green over CONTENT THAT NO LONGER EXISTS — the four vertical templates were mock seed packs deleted 2026-08-31, one template ships, and the binding GLOSSARY, USER_GUIDE and PRODUCT_CAPABILITIES repeated the claim; `api-stability.md` stated the DELETE-purge rule backwards; `multi-space.md` said the migrator runs on boot; six `UNTRACKED` items). `API` (`okf/capabilities/control-api/control-api.md`, 2026-09-08, 51 KB; absorbs the `docs/api/README.md` + `api-v1.md` "design of record" delegations to the archive — two of step 1's authority citations closed; §2 corrected `API-3` (If-Match honoured not required, SPA never sends it), `API-6` (blueprints are untested documentation), `API-7` (no surface guard); the served `openapi-v1.json` still claimed the retired legacy table; seven `UNTRACKED` items led by OpenAPI covering 19 of ~332 routes with a test that cannot see the gap). `INC` (`okf/capabilities/incidents/incidents.md`, 2026-09-08, 56 KB; §2 corrected FOUR of five cells: `INC-2`/`INC-4` were `All` after cell 7 made them Standard+, `INC-4` has no UI and an in-memory-only queue store, `INC-5`'s Diagnosis produces no Incident; `JOB-01`/`JOB-03` promised Personal three `inspecto-ops` jobs; eight `UNTRACKED` items; the backend objects domain has NO concept file — §7 gap row, as predicted). `SEC` (`okf/capabilities/security/security.md`, 2026-09-08, 61 KB, §3 ≈ 28 KB so no split). The operator judged the ACQ template right before it was replicated. What §2 found this time: `SEC-3` named a retired vendor class in the binding register, `SEC-8` was green over an unbuilt half, risk R4 contradicted its own §3 row, `EDITIONS.md` had a fictional `actor=anonymous` and a `SEC-12` row marked unbuilt for a feature whose SPA half shipped 2026-07-26; six `UNTRACKED` items surfaced (§5), the substantial one being that a Standard bundle cannot boot with auth off. Recommended next: `INC` (small, no backend concept file — the spec will have to say so) | per area: link guard green · vocabulary guard green · `graphify update .` run |
| 5 | **Strip `REQUIREMENTS.md` to the cross-area rollup** — §1/§2/§4/§5/§6/§7/§8; the 19 oversized Status cells move to §3/§4 of their capability doc | every `<AREA>-n` ID resolves to exactly one capability doc; no ID orphaned |
| 6 | **Reconcile the 15 contradiction clusters** (§8) as §2/§5/§6 rows | each cluster has one answer; `STAKEHOLDER_OVERVIEW` §9/§10.3 no longer contradicts the build |
| 7 | **Dissolve `superpower/`** — ⚠ **author the `PKG`/`OPS` deployment-topology spec FIRST** (§5.10), file the 20 untracked open items, then `git mv` each plan and add its INDEX row. `pipeline-spec.md` moves last (10 citing docs) and only with the waves plan | all 20 untracked items filed; `superpower/` holds only working assets; the 50 inbound links repointed |
| 8 | **Re-key `EDITIONS.md` + `FEATURE_INVENTORY.md`** groups to the area IDs | each group heading carries its area ID; edition facts still stated once |
| 9 | **Update `INDEX.md`, `okf/index.md`'s charter and CLAUDE.md's doc-lifecycle** (§5.7) | the tier's stated definition matches what it contains |
| 10 | **Archive residue** — a separate operator call once steps 1/1b are done (§5.5) | ⛔ not before: deletion is gated on every citation and ORPHAN having a destination |

### 6.1 What the link guard found on its first run (all fixed 2026-09-08)

Zero tolerance was affordable only because the 14 pre-existing breaks were fixed in the same change. The
mix of causes is itself the argument for having the guard:

| Cause | Count | Detail |
|---|---|---|
| A module split the doc never followed | 4 | `okf/backend/engine/db-layer.md` pointed into `inspecto-engine/` for `DbObjectStore`, `DbLinkStore`, `DbNoteStore` and `PostgresStateStoreTest` — all now in `inspecto-ops/` |
| A package move inside one module | 1 | same file: `ObjectType` is in `com/gamma/objects/`, not `com/gamma/ops/` |
| A class name that never existed | 1 | `okf/backend/engine/plugins.md` linked `StreamingPluginBatchStrategy`; the code calls it **`StreamingPluginIngestStrategy`** |
| Link rot created BY the doc lifecycle | 1 | `okf/frontend/features/geo-map.md` still pointed into `superpower/` at a plan archived 2026-09-06 — the archival step broke a live link and nothing noticed |
| 🔴 **Skills pointing at nothing** | **7** | every `.claude/skills/*/SKILL.md` link to `../../docs/…` resolved to `.claude/docs/`, which does not exist. Four skills instructed an agent to read `PROJECT_NOTES.md`, `EDITIONS.md` or `BRANCHING.md` and the guidance simply was not there. Nothing renders a 404 in a terminal — this class is undetectable without a guard |

⚠ **Broken links inside `docs/archived-documents/**` are ignored** — 544 of them — because that tier is
never maintained by policy. Links pointing *into* it are checked, and the exemption plus its live count is
printed on **every** run, pass or fail. An unprinted scope is an unaudited one: §9's lesson, applied in
advance this time rather than after the fact.

## 7. Grounding

Four parallel audits were run 2026-09-08 before any file was moved. All four are in, and are the basis of §5.1, §5.5,
§5.7, §5.8, §5.9, §5.10 and §8. **Three of the four overturned part of this plan's first draft**, which is
the reason they were run before any file moved:

| Audit | What it changed |
|---|---|
| `plans-archive/` (143 files) | **Refuted §5.5's "delete the archive"** — 93 of 143 files are cited from the current tier, ≥24 as *authority*; 7 ORPHANs hold content with no home. Deletion became step 10, gated. |
| `BACKLOG` + `roadmap` + `REQUIREMENTS` | **Corrected §3's premise** (the changelog is in the *Status* cell, not the Requirement cell); sized the areas (16 → 15); found 15 contradiction clusters and 4 non-canonical area names. |
| `okf/` (159 files) | **Weakened §5.7's objection to placing the docs under `okf/`** — the tier already carries ~12 % requirement/intent, with open-gap sections in 45 of 159 files. Also found the §5.8 duplication debt and the 28 % cross-cutting mass that forbids re-homing. |
| the 8 active plans | **Refuted "dissolving `superpower/` is cheap"** — ~51 open items, **20 untracked**, 12 of them in `deployment-topology-plan.md`, whose distillation target does not exist (§5.10). Added 17 contradiction clusters, incl. the `GLOSSARY.md` §13 defect (§8.1). |

Raw findings: `active-plans-digest.md`, `archive-audit.md`, `backlog-roadmap-req-map.md`,
`okf-functional-map.md` in the session scratchpad.

## 8. The contradiction register — what "some ideas would be contradictory" actually means

The operator's premise was correct, and the audits found far more than expected: **32 contradiction
clusters** across `REQUIREMENTS.md` / `BACKLOG.md` / `ROADMAP.md` / `STAKEHOLDER_OVERVIEW.md` / `GLOSSARY.md`
and the 8 active plans. These are not stylistic drift — several would mislead a reader into building or
promising the wrong thing. Each becomes a §2/§5/§6 row in its area's capability doc; the register is the
migration checklist.

### 8.1 🔴 The worst one is in the BINDING authority — `GLOSSARY.md` §13

`CLAUDE.md` makes `GLOSSARY.md` "the single source of truth for what every concept is called", and §13 is
its rename-status map. **Five §13 rows are marked "NOT STARTED", citing the ELT plan. I verified three of
them directly against main source and their named touchpoints are live:**

| §13 row | Marked | Verified in main source |
|---|---|---|
| L803 Node → **Step** | NOT STARTED | `/pipelines/step-types` is served (`PipelineListRoutes.java:31`) **and** `/pipelines/node-types` still is (`PipelineListRoutes.java:18`, `NodeAttributes.java:10`) — i.e. the row's own prescribed "serve both during rollout" state. It is **mid-rollout, not unstarted.** |
| L807 `mapping:` → **Mapping** (own CSV kind) | NOT STARTED | `MappingCsv` is wired live — `ComponentRegistry.java:155`, `ComponentStore.java:213,294`; `MappingCsv.java` + `StructureCsv.java` both exist in `inspecto-util/src/main/`. |
| L808 bare `dedup` → **`file_dedup`** | NOT STARTED | `RecipeCompiler.java:169,182,185` recognises `file_dedup` as a recipe key with its own validation messages; `:32` documents the `file_dedup` → `collector.duplicate` fold. |

The remaining two are the audit's findings, which I did **not** verify myself: L806 (`materialize`/D-7) is
reportedly contradicted by `BACKLOG.md` §6 ("done-by-absence"), and L805 (Enrichment/D-4) reportedly
describes an end-state **the plan itself withdrew on 2026-09-06**.

`GLOSSARY.md` was edited **2026-09-08** and still carries all five. Note the direction of the error: this is
the *inverse* of the scar this repo already has (`INDEX.md` calling a delivered plan "NOTHING BUILT"). The
naming authority under-reports its own progress, which means every shift reading §13 plans work that is
partly done. **This should be fixed on its own, ahead of the consolidation.**

### 8.2 Four plan items describe work already shipped under other IDs — and BACKLOG inherited three

`deployment-topology-plan.md` §10 lists as open: D1/SCR-11 the container image (the Dockerfile is in
`package.ps1:1039-1063`, shipped as PKG-3) · D2/GAP-8 the Postgres driver (`postgresql.jar`,
`package.ps1:529-539`, shipped as PG-1) · D3/GAP-4 the DuckDB memory cap (`duckdb.md:37`, on by default,
shipped as D11) · GAP-2/SCR-8 Enterprise packaging (`package.ps1:61` ValidateSet, shipped as EDG-01 — the
stated blocker is gone). **`BACKLOG.md` §3 inherited three of these as open rows**, so the board is
carrying phantom work. The same plan contradicts itself inside §10: D3 is simultaneously signed (banner),
"needs product call" (table), and an open gap (GAP-4).

Likewise **`compliance-certifications-plan.md` §3 still calls G1/G3/G5 open** ("SBOM … CONFIRMED STILL A
GAP") while `compliance/controls-matrix.md:97,99,101` closed all three on 2026-09-02 as COMPLY-1/2/3 — and
the "C3-remainder is org-gated" claim in `INDEX.md:106` + `BACKLOG.md:247` is over-broad: G1/G2/G3/G4 are
closed product work, only N3 and N7 remain.

### 8.3 The rest

**The worst — a stakeholder-facing doc that contradicts the build:**

- **`roadmap/STAKEHOLDER_OVERVIEW.md` §9/§10.3** marks `inspecto-security`, the object-storage connectors and
  the unified `parsing:` block as "**Planned**", under a heading reading "Next (committed direction, **not
  yet started**)" — against `ROADMAP.md`'s own "✅ SHIPPED 2026-07-24 / 07-22 / 07-07". It also lists ACQ-7
  etag dedup as Next; that shipped 2026-07-08. This is the document an executive reads.
- **`ACQ-4` NFS/SMB has four different answers**: REQ §5 "FULLY CLOSED 2026-07-22" · ROADMAP §3.2 "**Not
  delivered:** the NFS/SMB-CIFS half" · BACKLOG §5 "unresolved and needs grounding" · STAKEHOLDER §9
  "Planned".
- **`ROADMAP.md` §3.5 commits to work that is a standing refusal** — "jail the database temp directory",
  which `BACKLOG.md` §6 records as PATH-2, "grounded 2026-08-26 'do not build it'".
- **`ROADMAP` L4** lists push discovery as demand-gated LATER; `REQ ACQ-6` has it SHIPPED 2026-07-08
  (`/sources/{id}/notify` + `source.discovery: watch`).
- **Priority inversion** — `ROADMAP` §6: authoring polish "is now the front of the queue"; `BACKLOG`:
  "Nothing is queued", every authoring row P2.
- **`N4` edition realignment** — ROADMAP/STAKEHOLDER: "uncommitted/ungated... gated on stakeholder
  go-ahead"; BACKLOG: EDG-01 decided and **all cells shipped**, PKG rows SHIPPED.

**Internal contradictions inside single documents:**

- `BACKLOG` §0 "P1 is drained... Nothing is queued" vs §3's "**P1** · EDG-01" — whose tail then reads
  "Nothing remains on this row"; plus a "✅ DONE 2026-09-08 GUARD-SWEEP-1" row on a page whose own rules say
  closed rows are deleted, not kept.
- `BACKLOG` §7 cites "the new §3 row NAME-DIRS-1"; §0 says it "was deleted unbuilt on 2026-09-07", and it is
  absent from §3 — a duplicate map pointing at a dead alias.
- `BACKLOG` §5 is **stale on 4 of its 5 IDs** — it claims the SEC-8/OPS-2/INC-3/INV-2 edition columns are
  wrong; all four are already fixed in `REQUIREMENTS.md`. Only DAT-6 remains.
- `PIP-1` reads `SHIPPED` and "is **not** shipped" *in the same cell*. `UI-4` is a **Must** marked
  SUPERSEDED and never removed. `AGT-6a` — REQ "pending decision asks D1-D4" vs BACKLOG §1 "None. All 28
  rows were decided on 2026-09-06". REQ §5 still scopes `MET-5` as pending ("~1 shift, no migration") while
  §3 has it SHIPPED 2026-07-09.
- **Three documents concede their own staleness in writing**: REQ §2 says its Edition column is a stale
  mirror of `EDITIONS.md`; ROADMAP §9 concedes T1/T2 and N1/N2/N4 are stale; ROADMAP L2 says it is "no
  longer demand-gated" while sitting in the demand-gated table.

**Plan-internal staleness — a plan that contradicts its own shipped state** (the audit's findings):

- `elt-final-amendment-plan.md` §9 still calls D-9 "a designed fast-follow"; `pipeline-spec.md:745` records
  it COMPLETE 2026-09-01 — *before the plan's own last edit*.
- `pipeline-spec.md` §12 presents Row 1 (`Batch`→`Consignment`) as open with **two different wrong file
  counts** (517 and ~185); its own §0, the waves plan (`ff33246a`, 155 files), `INDEX.md` and `BACKLOG.md`
  all say shipped. §8 also asserts "You cannot add a new Step type today", which `okf/backend/engine/
  node-types.md:45-53` and the real packs refute — self-corrected at §10/§12 but never revised in place.
  The document also has **two sections numbered §12**.
- `agt-6-plan.md`'s closing line still lists the `projection_author` `columns.items` defect; `BACKLOG.md`
  says fixed 2026-07-28 and retired. `okf/frontend/features/inline-ai-authoring.md:9` calls the plan active
  "for … + A5" while its own body documents A5 as shipped.
- `REQUIREMENTS.md:219` + `:313-317` still read AGT-6a "ready to schedule pending D1–D4"; D1–D4 were
  answered 2026-07-26 and all phases shipped.
- The waves plan (line 199) says the `/batches` routes gain a `consignments` spelling; `GLOSSARY.md:811` and
  `BACKLOG.md` §6 say wire spellings are **deliberately unchanged**, riding the next MAJOR.
- `completeness-kpi-plan.md` K5 is **three-way split**: the plan and `BACKLOG.md` §5 say SHIPPED 2026-09-07,
  while `BACKLOG.md` §3 and `INDEX.md:108` still list it as held. Its hold is stated three mutually
  exclusive ways — "a carrier fact", "not carrier-gated and not query-gated, first action is engineering",
  and "the completeness-KPI query" against "There is no such query to run".
- `BACKLOG.md:206` gates the deployment row "(after §1 D1–D8 are signed)" while §1 reads "None. All 28 rows
  were decided" — `BACKLOG.md`'s own §7 already flags this.
- `compliance-certifications-plan.md` §6 Q6 is answered in its own banner and left unstruck three lines
  below as "gated on the rbac-abac R-workstreams"; the SOC-2 observation window is filed as two separate §2
  rows (its §7 admits this).
- `parser-field-tiers-interview-plan.md` cites "BACKLOG §7" twice for a row now in §2 (§7 is the duplicate
  map).

## 9. ✅ A guard scope hole found while checking the above — CLOSED 2026-09-08

`tools/check-vocabulary.mjs` had been reporting **green** while scanning
`DOC_TREES = ['docs/okf', 'docs/superpower', 'compliance', 'docs/stakeholders']` plus a **named file list**
of root canon. That left **9 current-tier files in 5 directories outside every scope** — `docs/roadmap/`
(2), `docs/ops/` (3), `docs/api/` (2), `docs/ui/` (1), `docs/wiki/` (1) — all listed in `INDEX.md` as
current, audience-facing docs. The guard's own `ROOT_CANON` comment had **named four of those five as
unscanned since 2026-08-29**, as the very reason root canon is a named file list. Naming a gap is not
closing it; `docs/wiki/` was in no list at all.

What the gap was hiding: **13 rule violations**, all in `docs/roadmap/` — 10 in `STAKEHOLDER_OVERVIEW.md`
(7 `bare-flow` + 3 `source-acquisition-entity`) and 3 in `ROADMAP.md`. ⚠ *An earlier draft of this section
said "46" for the first file. That was a **raw grep of the word**, not a violation count — the rule
correctly ignores lowercase prose and config-key senses. The actionable number is 13.*

The load-bearing one: `STAKEHOLDER_OVERVIEW.md` §11 told the reader to stand up a **`type: flow` job**,
which is not a real job type — the engine accepts `enrich | report | maintenance | pipeline`
(`JobConfig.java:20`), and the worked example is `inspecto/examples/06-serve/pipeline-job/rollup_job.toon`.
Past a certain age a banned synonym stops being wrong vocabulary and becomes **wrong instructions**.

**What was done.** The five directories joined `DOC_TREES`. All 10 stale hits in `STAKEHOLDER_OVERVIEW.md`
were FIXED rather than allowlisted — the §4 pane inventory (→ Pipelines / Collectors / Incidents), the §10
tables, the §11 remediation (→ `type: pipeline`), and the §12 glossary entry, rewritten to define
**Pipeline** and record the superseded spelling. `ROADMAP.md` §9's "residual vocabulary" bullet was
**re-grounded** — both halves of what it claimed were stale — and its three genuine citations of the
retired word now carry a per-line `<!-- vocab-allow: … -->` with a reason, the house pattern. The guard is
green over **200** docs, up from 190.

⚠ **The new scope was falsified per directory, not inferred from a green run.** A tracked probe carrying a
banned word was planted in each of the five and caught in each; one planted in `docs/archived-documents/`
was correctly *not* caught, confirming that tier stays exempt. 🔴 The first attempt used **untracked**
probes and all five slipped through silently — this guard enumerates *tracked* files, so an untracked probe
proves nothing. That is the same mechanism by which an untracked doc skips the guard entirely, and it very
nearly produced a false "the fix works" here.

Still open in that document: ~26 **lowercase** prose uses of the retired word ("how data flows through",
the `flow-graph` compound, "authored flows") that `bare-flow` does not flag. That is a prose pass, and it
is now recorded accurately in `ROADMAP.md` §9 instead of being mis-stated there.

## 10. Explicitly not proposed

- **Moving `okf/backend/**` or `okf/frontend/**` files into functional directories.** 1,055 inbound links,
  447 graphify-indexed doc nodes, seven path-keyed vocabulary waivers — maximum cost, and it works against
  the goal: the layer split is *right* for "how is this built", which is what a backend engineer opening
  `okf/backend/engine/` actually wants. The capability doc supplies the missing subject view by pointing
  into them (§7 of the template), not by absorbing them.
- **Deleting anything before its content has a destination.** Every deletion in §6 is gated on a prior
  distillation step.
- **A new requirement ID scheme.** The 16 existing prefixes stay; they are load-bearing citations.
