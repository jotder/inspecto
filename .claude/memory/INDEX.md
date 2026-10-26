# Team memory — engineering lessons for this repo (shared by every user of this sandbox)

Loaded into every session by `.claude/hooks/session-start-context.sh`. One lesson per file; open the note before working in its area.
Add a lesson as a new file here + one line below, in the same change. Personal or client-specific notes stay in the user's own Claude memory, never here.

## Build & test traps
- [Stale sibling jar](stale-sibling-jar-trap.md) — `-pl` without `-am` or `-rf` tests the stale ~/.m2 jar; find the symbol's owning module first; parallel lanes overwrite the shared repo mid-run → private `-Dmaven.repo.local` + `maven.repo.local.tail`
- [Maven verdict traps](maven-verdict-traps.md) — `-Dsurefire.failIfNoSpecifiedTests=false`, per-class `Tests run:`, WARNING summaries, stale reports, System.exit, -DargLine
- [Editions & toolchain](editions-and-toolchain.md) — Basic/Standard/Advanced/Preview profiles; default Standard; JAVA_HOME must be JDK 27
- [Falsify negative tests](falsify-negative-tests.md) — probe must succeed without the guard; whole-feature mutants; classifier refuses guard edits
- [CI gates beyond the reactor](ci-gates-beyond-the-reactor.md) — CI is nightly/on-demand; run ci.yml guards locally before every push
- [New route checklist](new-route-checklist.md) — literal capability, armed 401/403/200 test, regenerate openapi-v1.json
- [UI test traps](ui-test-traps.md) — `npx ng test` only, read exit code, dead spec files, drive the preview
- [Bundle & process traps](bundle-and-process-traps.md) — stale :8080 dist, jlinked modules, npm ci, cmd block expansion, orphan JVMs

## Editing & data traps
- [Shell edit traps](shell-edit-traps.md) — heredoc escapes/truncation, open(w), perl -i UTF-8, chain with &&; prefer the Edit tool
- [TOON config traps](toon-config-traps.md) — `#` line truncates, no single quotes, tabular arrays; decode + load + run
- [DuckDB & SQL rules](duckdb-and-sql-rules.md) — session TZ = host; render persisted SQL at read; DuckDB 2.0.0.0 pinned — lambda syntax, correctness settings, fail-closed AST reads

## Git & shared checkout
- [Shared tree & peer work](shared-tree-peer-work.md) — fetch first; red gate may be a peer's dirty files; stage shared docs by hunk
- [Worktree location traps](worktree-location-traps.md) — sibling `C:\sandbox\<name>` only; never junction node_modules
- [Push & branch policy](push-and-branch-policy.md) — master only, empty merge-forward set; **everyone works from `origin/master`, lanes and worktrees die when pushed (2026-10-26)**; push denials are per-attempt
- [Lane integration workflow](lane-integration-workflow.md) — build lanes in parallel, integrate + push ONE lane at a time from a clean sibling worktree; targeted tests per lane, one full gate per day/release

## Docs, guards & claims
- [Doc guard traps](doc-guard-traps.md) — citation headings are "References" (the vocabulary guard bans the acquisition word); git add new docs; absence phrase on the line; docs-relocate
- [Guard scope = silent exemption](guard-scope-is-a-silent-exemption.md) — audit what files a guard opens; parity tests for mirror lists
- [Claims are hypotheses](claims-are-hypotheses.md) — BACKLOG causes/status/file lists are often wrong; grep the symbol
- [Subagent claims are hypotheses](subagent-claims-are-hypotheses.md) — verify attributions and negatives with one command before relaying

## Environment & deploy
- [Preview LA demo](preview-la-demo.md) — only `C:\sandbox\inspecto-preview\inspecto-la`; no zips; restore runtime cacerts
- [OIDC: WSO2 / Keycloak](oidc-wso2-keycloak.md) — provider gotchas, JWT tokens, groups claim, bundle cacerts import

## Operator preferences
- [Standing preferences](operator-standing-preferences.md) — breaking changes free, no shims; decide and record when operator away
- [No data or client files in commits](no-data-or-client-files-in-commits.md) — synthetic fixtures only
