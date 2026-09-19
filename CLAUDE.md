
# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

## Canonical vocabulary (binding)

**`docs/GLOSSARY.md` is the single source of truth for what every concept is called.** Words must never be
confusing or ambiguous. In all UI text, model/field names, API routes, config keys, docs, and conversation:

- Use the **canonical term**; never a banned synonym. The hard bans: ⛔ *Flow* → **Pipeline** · ⛔ *Data Store*
  (relation) → **Dataset** · ⛔ *Issue* → **Incident** · ⛔ bare *Rule* → **Expectation / Alert Rule / Decision
  Rule** · ⛔ *Metric* (BI) → **Measure** · ⛔ *Source* (acquisition entity) → **Collector** · ⛔ *Data
  Source* → **Stream / Reference** (Catalog data origins). *(Source→Collector flipped 2026-07-14, §2/§3.)*
- **One concept → one word; one word → one concept.** Always distinguish **Type** (template) from **Instance**
  (e.g. *Visualization Type* → *Widget*).
- The rename rolls out **UI → model → backend** (map in `docs/GLOSSARY.md` §13). When you touch any of these
  layers, conform to the canonical term and update the touchpoint table.

## Documentation lifecycle (three tiers — keep it this way)

Docs live in exactly three tiers; **`docs/INDEX.md` is the map** and must be updated in the same
change that adds or retires a doc:

1. **Current knowledge → `docs/okf/`** (one concept per file, cross-linked) plus
   the small root canon (GLOSSARY, INDEX, REQUIREMENTS, BACKLOG, USER_GUIDE, PROJECT_NOTES,
   BRANCHING, EDITIONS, FEATURE_INVENTORY, ADVANCED_GUIDE) **and the audience- and surface-specific
   trees** `docs/stakeholders/`, `docs/api/`, `docs/ui/`, `docs/ops/`, `docs/roadmap/`, `docs/wiki/`
   and root `compliance/`. ⚠ Those six trees went unstated here until 2026-09-09 (consolidation
   step 9) even though `tools/check-vocabulary.mjs` has always scanned them — **a tier definition
   that omits part of its own tier is how a doc ends up with no stated home**, and it is the
   condition step 9 exists to end.
2. **Active plans → `docs/superpower/`** — a plan lives here ONLY while its work is in flight.
   **When the work ships: distill the durable as-built facts (decisions, seams, gotchas, deliberate
   deferrals) into the matching OKF concept, move still-open items to `docs/BACKLOG.md`, then
   `git mv` the plan to `docs/archived-documents/plans-archive/`.** The `handoff` skill checks this.
3. **History → `docs/archived-documents/`** — kept for provenance, never maintained, never linked
   as current.

Never create a new root-level `docs/*.md` topic file — new knowledge goes into an OKF concept
(or an existing canon file).

## Working artifacts stay in the repo

**Never put work artifacts on the user profile.** Plans, specs, designs, hand-offs, notes — anything
produced for this project — must live **under the repo**, so they are IDE-readable and committable:

- **Plans / designs / specs** → `docs/superpower/` (or the right `docs/` file). Do **not** leave them in
  `~/.claude/plans/` or anywhere under the user profile. When a plan is approved, persist it in-repo.
- **Live working state / hand-off** → `SESSION_STATUS.local.md` (gitignored but in-repo).
- The user's auto-memory index is a thin pointer only; durable project knowledge belongs in the repo.

## Shared team sandbox — shifts & handover

This checkout is shared by a team working in shifts under one account. **All Claude Code setup —
skills, agents, hooks, settings — lives in repo `.claude/`, never in the user profile**, so every
shift gets the identical environment.

- **Session-per-shift.** Resume from `.claude/sessions/snapshot.md` — it does not exist in a fresh
  checkout: both it and `SESSION_STATUS.local.md` are gitignored, written by a hook on every stop in this
  working tree. Resume from those two, not from old conversations. At shift end apply the `handoff` skill
  and end the session. Mid-task compaction is the failure mode — externalize state instead.
- Commits use the shared identity; work lands on `master` per the `release-workflow` skill — no
  per-user branches or PRs.

## Model & effort routing (token economy)

- **Delegate, don't read raw:** broad code searches → `Explore` (pass `model: "haiku"` for pure
  locating) or `backend-explorer` / `frontend-explorer`; builds/tests → `verify-runner`. Never parse
  full Maven/npm logs in the main thread.
- **Parallel forks:** independent research questions = multiple forks/agents launched in a single
  message, one per question — not sequential searches.
- Reserve the strongest model + extended thinking ("think hard") for architecture and design
  decisions; routine edits and mechanical refactors don't need it.
- Big specs/docs go into `docs/` files and are referenced by path — never pasted inline into prompts.
- **Verification gate:** non-trivial changes (3+ file edits, backend/API or infra changes) get a
  `verification` subagent PASS before reporting done — own checks and self-reports don't substitute.
- **Test at the UNIT level per change (operator, 2026-09-17).** Run the affected test classes
  (`-pl <module> -Dtest=A,B` — commas, never `+`) and move on. The FULL reactor gate
  (`mvn -o clean test -Pedition-enterprise`, ~10 min) runs only when the operator asks for it
  (GAUNTLET), before a push that touched shared seams, or at handoff — never once per edit.

## CodeGraph (code knowledge graph)

This project is indexed by **CodeGraph** (`.codegraph/`, machine-local + gitignored). It exposes
exactly **one** MCP tool, `codegraph_explore`, plus a CLI.

- **Reach for `codegraph_explore` FIRST** for orientation questions — *how does X work*, *where is X*,
  *what does changing X break* — and before editing an unfamiliar symbol. It returns the **verbatim
  source** of the relevant symbols grouped by file, plus the call path among them, in one capped call.
  ⚠ **Treat that source as already Read — do NOT re-open those files**, or the saving is cancelled.
- **It covers BOTH layers**: Java (`inspecto*/src`) and the Angular SPA (`inspecto-ui/src`), and a single
  result can cross them. ⛔ Do not carry over the old graphify assumption that the UI is unindexed.
- **Still use Grep/Read** for pinpoint work: a known file+line, a literal string, config/TOON/docs, or
  anything non-code. A graph is for *relationships*; grep is for *locations*.
- **CLI for relationship questions** grep cannot answer: `codegraph callers <symbol>` ·
  `codegraph callees <symbol>` · `codegraph impact <symbol>` · `codegraph affected <files...>`
  (which tests a change touches) · `codegraph query <name>` for a quick symbol lookup.
- **Refresh is automatic** — a file watcher syncs on save. After a big rebase or branch switch,
  `codegraph sync` catches up; `codegraph index` rebuilds from scratch.
- ⚠ **Delegation is still the bigger token lever.** For a broad sweep across many files, a subagent
  (`backend-explorer` / `frontend-explorer` / `Explore`) keeps the raw reads out of the main thread
  entirely. CodeGraph shrinks a lookup; a subagent removes it from this context.
