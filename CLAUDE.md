
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

1. **Current knowledge → one product hub per Product Map Item:
   `docs/okf/product/<kind>/<name>/`** (mirrors `product-map/<kind>/<name>.toon`; `PRODUCT-DOCS-HUB-1`,
   2026-10-19). File name = slot: `plan` · `spec` · `architecture` · `features` · `feature-db` ·
   `user-guide` · `admin-guide` · `api` · `tests` · `brochure`, as `<slot>.md` or `<slot>-<topic>.md`.
   Platform-wide backend docs → `layer/platform`; UI-wide and whole-product sales docs →
   `layer/product`. A hub's `index.md` is generated (`node tools/render-product-docs.mjs`) — never
   hand-edit it. Plus the small root canon (GLOSSARY, INDEX, REQUIREMENTS, BACKLOG, USER_GUIDE,
   PROJECT_NOTES, BRANCHING, EDITIONS, FEATURE_INVENTORY, ADVANCED_GUIDE), the HTTP contract
   `docs/api/` and root `compliance/`. ⛔ Never create another docs tree or a doc outside a hub.
2. **Active plans → `docs/superpower/`** — a plan lives here ONLY while its work is in flight.
   **When the work ships: distill the durable as-built facts (decisions, seams, gotchas, deliberate
   deferrals) into the matching product hub (`docs/okf/product/…`), move still-open items to `docs/BACKLOG.md`, then
   `git mv` the plan to `docs/archived-documents/plans-archive/`.** The `handoff` skill checks this.
3. **History → `docs/archived-documents/`** — kept for provenance, never maintained, never linked
   as current.

**Open work = one board, tagged rows.** `docs/BACKLOG.md` is the only list of open work, defects
included (a row with `kind: defect` — never "Issue", which means Incident). Every new row ends its
first line with `[items: <product-map ids> · kind: feature|defect|chore · hold: …]`
(`tools/check-backlog-items.mjs`); each hub's **Open work** section is generated from those tags — never
keep a list of open work in a hub. **Feature path:** plan in `docs/superpower/` + tagged board rows →
build, commit scope = the item name (`feat(reconciliation): …`) → on ship, update the hub's plan / spec
/ guides, close the rows and archive the plan; then `node tools/render-product-docs.mjs` refreshes every
generated slot and Open work section. Detail: `docs/okf/product/layer/platform/spec-work-tracking.md`.

Never create a new root-level `docs/*.md` topic file — new knowledge goes into a product hub
(or an existing canon file).

## Working artifacts stay in the repo

**Never put work artifacts on the user profile.** Plans, specs, designs, hand-offs, notes — anything
produced for this project — must live **under the repo**, so they are IDE-readable and committable:

- **Plans / designs / specs** → `docs/superpower/` (or the right `docs/` file). Do **not** leave them in
  `~/.claude/plans/` or anywhere under the user profile. When a plan is approved, persist it in-repo.
- **Live working state / hand-off** → `SESSION_STATUS.local.md` (gitignored but in-repo).
- The user's auto-memory index is a thin pointer only; durable project knowledge belongs in the repo.
- **Team memory = `.claude/memory/`** (one lesson per file + `INDEX.md`, loaded at every session start for
  whoever works in this sandbox). A new engineering lesson goes there, not into a personal profile; personal or
  client-specific notes stay in the user's own memory and are never committed.

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
- **One lane per push (operator, 2026-10-20 — binding for every session and subagent).** Lanes may be BUILT in
  parallel, but each lane (or one small related group) is INTEGRATED and PUSHED on its own: in a clean sibling
  worktree from `origin/master` (never by merging in this shared checkout — peers' uncommitted edits block its
  fast-forward and the pre-push guards read the dirty tree), fix what it broke there, run the affected-build
  protocol plus the lane's named tests, merge `origin/master` again if it moved, then `git push origin HEAD:master`.
  One full gate per day or release. Detail and rationale: `.claude/memory/lane-integration-workflow.md`.
  `.githooks/pre-push` prints a note (never blocks) when it runs from a dirty tree.
- **`origin/master` is the only line (operator, 2026-10-26 — binding for every session, peer and subagent).**
  There are no long-lived, per-person or per-topic branches and no parked worktrees (2026-10-26: 117 local branches,
  45 of them Link Analysis, and 9 LA worktrees had piled up, every one of them already merged).
  - **Start** a lane from a fresh `git fetch` then `git worktree add --detach C:\sandbox\<lane> origin/master` — never
    from the shared checkout's local `master`, which is not a base.
  - **End** the lane the moment it is pushed: `git worktree remove` it and delete its private Maven repo
    (`C:\sandbox\.m2-<lane>`); if you made a branch, delete it too. One session per lane; archive it when the lane is pushed.
  - **Unpushed work is not parked.** An unfinished lane may stay for ONE shift as one worktree, listed in
    `SESSION_STATUS.local.md` with its owner and date; anything unlisted and unpushed at the next shift is removed.
  - **Shift audit** (start and end): `git worktree list` shows the shared checkout plus listed lanes only, and
    `git branch` shows `master` only. The shared checkout fast-forwards (`git merge --ff-only origin/master`) when clean.
  - **Never delete unpushed commits blindly:** `git cherry origin/master <branch>` first; bundle what differs
    (`git bundle create … <refs> ^origin/master`) into `C:\sandbox\archive\`.

## Model & effort routing (token economy)

- **Effort follows the task, automatically (operator, 2026-10-19).** The session default is `high`
  (`.claude/settings.json`). Effort is fixed per agent, so route work to the agent whose effort fits — this rule
  is the standing instruction to pass `effort` when spawning:
  - **design, architecture, protocol, review, root-cause of a defect** → stay in the main thread at high, or
    spawn with `effort: "xhigh"`; the `verification` agent (Opus, high) gives the independent verdict.
  - **writing docs from known facts, implementing a decided change** → `effort: "high"` (the default).
  - **locating, bulk mechanical edits, running builds** → `backend-explorer` / `frontend-explorer` /
    `verify-runner` (`effort: low` in their definitions), or `effort: "low"` on a general agent.
  - If the session itself was started at low effort and the next task is design or review, ask the operator
    once to raise it (app effort picker) rather than proceeding at low.

- **Delegate, don't read raw:** broad code searches → `Explore` (pass `model: "haiku"` for pure
  locating) or `backend-explorer` / `frontend-explorer`; builds/tests → `verify-runner`. Never parse
  full Maven/npm logs in the main thread.
- **Parallel forks:** independent research questions = multiple forks/agents launched in a single
  message, one per question — not sequential searches (but see the agent budget below).
- **Agent budget (operator, 2026-10-25 — session-usage limit).** Every agent re-loads CLAUDE.md plus the
  tool and skill lists (about 40–50k tokens) before it reads a file, and the first three exploration agents
  of one session used about 211k tokens: a launch costs more than most lookups. (1) A single-fact lookup is
  one CodeGraph/grep call in the main thread, never an agent. (2) Do not launch exploration agents at session
  start or before the operator's first concrete ask. (3) At most 3 agents at once. (4) Locate-only work passes
  `model: "haiku"`. (5) A verification agent runs once per non-trivial lane; a re-verification is scoped to the
  findings it raised, not a re-run of everything. (6) When the operator caps usage: targeted tests only, and
  say in every report that the full gate was NOT run.
- Reserve the strongest model + extended thinking ("think hard") for architecture and design
  decisions; routine edits and mechanical refactors don't need it.
- Big specs/docs go into `docs/` files and are referenced by path — never pasted inline into prompts.
- **Verification gate:** non-trivial changes (3+ file edits, backend/API or infra changes) get a
  `verification` subagent PASS before reporting done — own checks and self-reports don't substitute.
- **Test only what a change reaches (operator, 2026-09-17; protocol `AFFECTED-BUILD-1` 2026-10-19).** Run
  `node tools/affected-modules.mjs` — it prints mode `none | affected | full` and the exact Maven commands
  (changed modules + every dependent + modules whose tests read a changed file). While iterating, narrow with
  `-Dtest=A,B` (commas, never `+`). The FULL reactor gate (`mvn -o clean test -Pedition-advanced`, ~10 min) runs
  only when the tool says `full`, the operator asks (GAUNTLET), or at release — never once per edit. ⛔ Never
  `-pl X -am` alone as proof: it skips every dependent. Protocol: `docs/okf/product/layer/platform/spec-build-test.md`.

## Deterministic checks run themselves — don't spend turns on them

- **`.githooks/pre-commit` runs `tools/precommit.mjs`** on every commit: EVERY no-build step of the CI `guards`
  job (read from `ci.yml` at commit time, so it cannot drift), read-only guards 6 in parallel then test steps one at a time (~30–40 s), plus the affected-module
  test plan when Java changed. Stage everything, commit, and fix what it reports — never re-run those guards by
  hand "to be sure", and never `--no-verify`. It warns when tracked files have unstaged edits: the guards read
  the working tree, so those were judged too.
- **New laptop / broken build → `node tools/dev-doctor.mjs`** first: it names environment traps (JAVA_HOME ≠
  JDK 27, missing eoiagent in `~/.m2`, hooks path unset, unreadable folders) before they masquerade as code failures.

## Editing on this Windows box (Git Bash)

- **Never put regexes or backslashes in inline `python -c`, `node -e`, `sed` or heredoc scripts** — Git Bash
  collapses `\\` and mangles `/c/...` paths (four failed patches in one session, 2026-10-19). Use the Edit tool
  for exact replacements; for anything scripted, Write the script to a file and run the file.
- Docker paths in Git Bash need `MSYS_NO_PATHCONV=1`, or `/w` becomes `W:/`.

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
  `codegraph callees <symbol>` · `codegraph impact <symbol>` · `codegraph query <name>` for a quick
  symbol lookup. ⚠ `callers` reports IMPORTING FILES (namespace nodes at `:1`), not call sites, and
  is **not exhaustive** — it returned 10 files for `SqlGuard` where `grep -rl` finds 40. Cross-check
  with grep before treating a caller list as complete.
- ⛔ **`codegraph affected <files...>` is BROKEN — never use it to pick tests.** Tested 2026-09-19 on
  v1.6.0: `affected inspecto-ui/src/app/inspecto/api/config.service.ts` — an **Angular** service —
  returns **953 Java test files**, led by `asn-parser` ASN.1 decoder tests that cannot possibly be
  affected; `affected platform/inspecto-sql/.../SqlGuard.java` returns **1078** when the whole repo holds
  **1059** test files. It fails toward over-reporting, so selecting `-Dtest=` targets from it silently
  becomes a full-reactor run — the opposite of the unit-test-per-change rule above. Use `impact` plus
  judgement, or the module the change lives in. (`CODEGRAPH-AFFECTED-UNUSABLE-1`)
- ⚠ **Name SYMBOLS in a query, don't phrase a concept** — ranking is substantially lexical. Asking
  *"ConfigSafetyValidator validate control api route gating"* put `RowShaper.java` (a DuckDB row
  splitter whose methods happen to be named `validate` and `route`) above the actual gating code.
- **Refresh is automatic** — a file watcher syncs on save. After a big rebase or branch switch,
  `codegraph sync` catches up; `codegraph index` rebuilds from scratch. ⚠ There is no `codegraph
  stats`; the command is `codegraph status`.
- ⚠ **Delegation is still the bigger token lever.** For a broad sweep across many files, a subagent
  (`backend-explorer` / `frontend-explorer` / `Explore`) keeps the raw reads out of the main thread
  entirely. CodeGraph shrinks a lookup; a subagent removes it from this context.
