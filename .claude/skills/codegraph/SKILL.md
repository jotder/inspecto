---
name: codegraph
description: >
  How to use CodeGraph (codegraph_explore MCP tool and the codegraph CLI) on this repo, and its known
  traps: callers is not exhaustive, affected is broken for picking tests, queries rank lexically.
  Always use CodeGraph first for code questions and before editing code. Trigger before running any `codegraph` CLI command (callers / callees / impact / query / sync /
  index / status / affected), when choosing tests from a graph, or when a codegraph result looks wrong.
---

# CodeGraph on inspecto

The repo is indexed by **CodeGraph** (`.codegraph/`, machine-local, gitignored): one MCP tool,
`codegraph_explore`, plus a CLI. Moved here from root `CLAUDE.md` on 2026-11-13 to keep the always-loaded
file small; the rules are unchanged.

- **Always use `codegraph_explore` first for code (operator, 2026-11-13, binding).** Every *how does X work*,
  *where is X*, *who calls X*, *what does changing X break*, and every read of code before an edit — not Grep, Read
  or an exploration agent first. It returns the verbatim source of the relevant symbols grouped by file, plus the
  call path among them. ⚠ **Treat that source as already Read — do not re-open those files.** Need more? Call it
  again with the names it listed under "Not shown above".
- **Subagents have no MCP tool:** they use the CLI (`codegraph explore "<names>"`, `callers`, `callees`, `impact`);
  every agent prompt that touches code says "use CodeGraph first". The index lives in the shared checkout
  (`.codegraph/`, machine-local); a lane worktree has none, so query with `projectPath` /
  run the CLI from `C:\sandbox\inspecto-clean` — and Read your own unpushed edits, which the index does not hold.
- **It covers both layers**: Java (`inspecto*/src`) and the Angular SPA (`inspecto-ui/src`); one result can cross
  them. ⛔ The old graphify assumption that the UI is unindexed is wrong.
- **Grep/Read only where CodeGraph cannot answer:** docs, TOON/config, logs and other non-code; a literal string
  (an error message, a config key); or a gap its answer left.
- **CLI for relationship questions** grep cannot answer: `codegraph callers <symbol>` · `codegraph callees <symbol>`
  · `codegraph impact <symbol>` · `codegraph query <name>`. ⚠ `callers` reports IMPORTING FILES (namespace nodes at
  `:1`), not call sites, and is **not exhaustive** — 10 files for `SqlGuard` where `grep -rl` finds 40.
  Cross-check with grep before treating a caller list as complete.
- ⛔ **`codegraph affected <files...>` is BROKEN — never use it to pick tests** (`CODEGRAPH-AFFECTED-UNUSABLE-1`,
  v1.6.0, 2026-09-19): an Angular service returned 953 Java test files led by ASN.1 decoder tests;
  `SqlGuard.java` returned 1078 when the repo holds 1059 test files. It over-reports, so `-Dtest=` targets from it
  become a full-reactor run. Use `node tools/affected-modules.mjs`, or `impact` plus judgement.
- ⚠ **Name SYMBOLS in a query, don't phrase a concept** — ranking is substantially lexical. *"ConfigSafetyValidator
  validate control api route gating"* ranked `RowShaper.java` (methods named `validate` and `route`) above the
  real gating code.
- **Refresh is automatic** (a file watcher syncs on save). After a big rebase or branch switch run `codegraph sync`;
  `codegraph index` rebuilds from scratch. There is no `codegraph stats`; the command is `codegraph status`.
- ⚠ **Delegation is still the bigger token lever.** For a broad sweep across many files, a subagent
  (`backend-explorer` / `frontend-explorer` / `Explore`) keeps raw reads out of the main thread entirely.
