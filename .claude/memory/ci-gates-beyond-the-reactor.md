---
name: ci-gates-beyond-the-reactor
description: "A green local reactor is not green CI: no-build guards (ci.yml), Linux class ordering and reactor-only contract tests fail separately; CI runs nightly/on-demand only, so run guards locally before every push"
metadata:
  type: feedback
---

**CI cadence (operator 2026-10-09):** `ci.yml` (guards included) and `ui.yml` run only nightly, on
`workflow_dispatch`, PR, or `v*` tag; a branch push runs only `branch-policy` commit lint. A push therefore costs no CI
time: push each integrated lane (or small related group) once its targeted checks are green — see
[[lane-integration-workflow]] (operator 2026-10-20; this replaces "keep commits LOCAL, batch"); after a push touching shared seams offer
`gh workflow run CI --ref master` (and `UI`). Read `gh run list` after and fix a red master first.

**Run locally before every push:** every no-build guard step listed in `.github/workflows/ci.yml`
(`node tools/check-*.mjs`, `route-gating-report.mjs --check`, ...) with its REAL exit code, chained `&&` to the
commit. Known reds the reactor cannot show:
- `check-backlog-homes` / `check-backlog-items` — row pointers and `[items: ... · kind: ...]` tags.
- `route-gating-report.mjs --check` — regenerate after any route-file edit, even line shifts.
- `check-authgate-coverage.mjs` — counts a route covered only by a bare quoted literal in an ARMED test.
- `ImportLoaderInventoryTest` (new fixed file name a loader reads -> `ReservedConfigPaths` or allow-list) and
  `NoRawInMemoryDuckDbOpenContractTest` (`"jdbc:duckdb:"` literal in main source) fire only in full module runs.
- Linux-only: a static root leaked between test classes (`SpaceConfigRoot.clear()` in @BeforeEach/@AfterEach) —
  Windows class order hid it. (2026-11-15: three more tests hid behind it for two weeks, because the reactor stops at
  the first failed module and 31 modules never ran in CI — read `gh run list` after a push, a red nightly hides others.)
- A guard that matches pwsh's PRINTED error fails on the Linux runner: pwsh wraps error text at the console width
  (80 columns there). Catch and read `$_.Exception.Message` instead (`check-bundle-spaces.mjs`, 2026-11-15).
- Prettier moves line-based markers (`form-allow`, text a vocabulary guard reads): after formatting, run the line-based
  guards again; put `<!-- prettier-ignore -->` on a binding prettier must not split.

Related: [[new-route-checklist]], [[doc-guard-traps]].
