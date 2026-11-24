---
type: Concept
title: Build & Test — Packaging — bundle contents; open rows
description: Part 3 of 3 of Build & Test: Open rows this concept owns
tags: [build, test, maven, duckdb, packaging]
timestamp: 2026-06-28T00:00:00Z
item: layer.platform
audience: internal
status: current
verified: 2026-11-20 last-edit
---

# Build & Test — Packaging — bundle contents; open rows

Part 3 of 3 of [Build & Test](spec-build-test.md) · previous: [Packaging — package.ps1](spec-build-test-packaging.md).

## Packaging — `package.ps1` (continued)

### Bundle contents — the docs tree

`package.ps1` step 7 stages the `docs/` tree into every edition bundle, file by file (not one recursive copy, so a single locked file cannot truncate the rest) — **but only the CURRENT tier**. The two non-current tiers that `CLAUDE.md` defines are withheld by name:

| tier | tree | in the bundle |
|---|---|---|
| 1 — current knowledge | `okf/` + the root canon + `stakeholders/ api/ ui/ ops/ roadmap/ wiki/` | **ships** |
| 2 — active plans | `superpower/` | **withheld** |
| 3 — history | `archived-documents/` | **withheld** |

🔴 **Until 2026-09-16 step 7 shipped all of `docs/` with nothing excluded.** Measured from the entry table of the 2026-09-15 `inspecto-deploy.zip`: **491 docs files, of which 246 were `archived-documents/` and 26 were `superpower/`** — i.e. **55 % of the shipped documentation was material the project itself declares not current**, complete with the archive's ~570 known-broken internal links, superseded designs and refuted claims. Found while REFUTING `GAP-10`, which had worried that 13 archived files were *missing* from the bundle; the real exposure was the exact inverse.

**How the exclusion is written, and why that shape.** `$docsExcludedTrees` is an explicit **list of names**, one entry per tree with the tier reason beside it, matched against the **first path segment only** — never a substring anywhere in the path, which would also have swept e.g. OKF files whose own path contains one of those words. ⛔ Do not collapse it into a pattern or a glob: the reason a tree is out is a *tier decision*, and a glob records no reason. Adding a tree to the list is a product call, not a refactor.

**It fails closed in both directions**, because a filter that also drops wanted docs is worse than the defect it fixes. After the copy, step 7 throws `DOCS TIER LEAK` if any excluded tree reached the bundle, and `DOCS OVER-FILTERED` if any of `$docsRequiredEntries` (`okf`, `stakeholders`, `api`, `ui`, `ops`, `roadmap`, `wiki`, `INDEX.md`, `GLOSSARY.md`, `USER_GUIDE.md`, `ADVANCED_GUIDE.md`, `EDITIONS.md`) is missing. Packaging then prints the staged / withheld counts.

⚠ **Known consequence (decided and fixed below): the shipped docs carried 323 links that resolve to nothing.** Withholding a tree does not rewrite the documents that pointed into it. **Recounted 2026-09-16 with `tools/check-bundle-doc-links.mjs`** (the first count, 198, was by hand and was low — it counted only the two *tier* trees, and only from `docs/`):

| what the link points at | links | note |
|---|---|---|
| `docs/archived-documents/**` | 181 | withheld tier |
| `docs/superpower/**` | 13 | withheld tier |
| `docs/BACKLOG.md` | 22 | withheld audience — **the first count missed these entirely** |
| `docs/archived-documents/project-notes-2026-11-19.md` | 8 | withheld audience — likewise |
| a repo path that exists but never ships (`inspecto/**` source citations, `compliance/**`) | 66 | **not** caused by the withholding decision |
| climbs above the bundle root (`examples/README.md` moved up one level) | 4 | caused by step 4b's relocation |
| **broken in the repository too** | 29 | pre-existing rot, see below |

**224 of the 323 are the withholding decision's own doing**, across **124 distinct withheld target files**. The concentration matters more than the total: **97 are in `INDEX.md` alone** — the front door, which lists both withheld trees as sections — and 41 in `okf/product/module/engine/spec-db-layer.md`. `GLOSSARY.md` and `ADVANCED_GUIDE.md` are among the rest.

🔴 **29 of them are broken in the repo as well, and nothing was watching.** They are almost all in `inspecto/README.md` — the file step 7 copies to the **bundle root** as the customer's first page — pointing at `docs/architecture.md`, `docs/configuration.md`, `docs/operations.md`, `docs/plugins.md`, `docs/v3-agent-mvp.md` and friends — every one a dead path that does not exist since the docs consolidation. `tools/check-doc-links.mjs` cannot see them: its `ROOTS` are `docs`, `compliance`, `.claude` + root `*.md`, and `inspecto/` is in none of them. ⛔ This is **not** part of the withholding decision and must not be folded into it — it needs no product call, only the correct targets.

✅ **Decided and shipped 2026-09-16: rewrite at package time** (option (a)). **Single-sourced 2026-09-24:** the rewrite is `tools/bundle-doc-rewrite.mjs`, which step 7 runs as a CLI over the staged bundle and which the guard imports and applies in simulated mode — one implementation, so the guard reports what SURVIVES packaging. Until then it was a PowerShell regex the guard never modelled, so the guard stayed red at **321** after the fix shipped and a later pass closed the row calling that red "working as designed". Per link, outside code fences, decided by RESOLVING the target (not a substring match on the tree name): a **withheld** target becomes `label (internal document - not shipped)`; a **relocated** target (`inspecto/README.md` → `README.md`, `inspecto/examples/` → `examples/`, from either end) is re-pointed; anything else is left for the guard to report. Measured 2026-09-24: **250 neutralised, 15 re-pointed, 321 → 56** — same figures in simulated mode and over a staged copy with `--bundle`. The 56 left were all citations of files that exist in the repo but never ship — source code (`db-layer.md` alone had 38), root `compliance/` (5 in `INDEX.md`), and `../inspecto-ui/README.md` climbing above the bundle root (2 in the root README).

✅ **Decided and shipped 2026-09-25 (operator): neutralise those at package time too.** A third class in the same rewrite: a target that ships nowhere in the bundle but **exists in the repo** (resolved from the linking file's *repo* location) becomes `label (`repo/path` - not shipped)` — the label kept, the repo path kept in code so a reader holding the source can still find it, the same `label (… - not shipped)` shape as the withheld class. **The repo docs are unchanged**; only the bundle copy is. "Exists in the repo" is the tracked-file set in the guard and the checkout the script lives in (`tools/..`) when step 7 runs it. ⛔ A target that exists in **neither** the bundle nor the repo is still left alone — that is rot, and the guard must keep reporting it. Measured 2026-09-25, simulated mode: **261 neutralised, 15 re-pointed, 56 citations unlinked, 56 → 0 dangling, exit 0.** Mutation proof: a planted markdown link to a missing sibling file in `GLOSSARY.md` and a planted link of a non-existent class under `../inspecto/src/main/java/` in `INDEX.md` both came back `BROKEN IN THE REPO TOO`, exit 1. → `BUNDLE-DANGLING-LINKS-1`

**`tools/check-bundle-doc-links.mjs` is how this is measured, and re-measured.** It has two modes. By default it **simulates** the bundle from `git ls-files` plus the exclusion lists **parsed out of `package.ps1` itself** — no `pwsh`, no build, so CI and a sandboxed agent can both run it; `--bundle <dir>` walks a **real** staged bundle and is the verdict. ⛔ The guard never restates the exclusion lists: a check that keeps its own copy of what it checks drifts from it silently. A failed parse or an empty list exits **2 (cannot run)**, and an emptiness floor fails a run that scanned almost nothing — the recurring failure here is a probe that could not return a hit reporting "absent" and exiting 0. ✅ **Wired into `ci.yml` and `.githooks/pre-push` 2026-09-25** (simulated mode, directly after `check-doc-links.mjs`), once the rewrite above took it to 0. Until then it was deliberately kept out: red on the 56 undecided source-path citations, and a build red on an undecided question teaches the next shift to ignore it. `--bundle` mode is not wired — it needs a staged bundle, i.e. a `package.ps1` run.

✅ **`BACKLOG.md` and `PROJECT_NOTES.md` are ALSO withheld, by operator decision 2026-09-16** — but through a **second, separate list**, `$docsExcludedFiles`, with its own assertion (`DOCS AUDIENCE LEAK`). ⛔ **The two lists are deliberately not merged.** These two files are current-tier by `CLAUDE.md`'s own canon list and are accurate and maintained; they are withheld because of **audience** — `BACKLOG.md` is the internal defect board, naming open P1s by identifier in the product the customer just installed. A tier exclusion and an audience exclusion are different claims, and collapsing them into one list would lose why either tree is out. ⚠ This paragraph read *"Still shipping … a separate product call"* until that decision landed the same day.

Root `compliance/` is **not** under `docs/` and has never shipped in the bundle — verified from the same zip entry table. → `BUNDLE-SHIPS-THE-ARCHIVE-1` in `BACKLOG.md`

## Open rows this concept owns

⚠ Listed here so the concept knows what it owes; the board carries the detail. A pointer from a row to
this file is a claim, checked by `tools/check-backlog-homes.mjs`.

- **`IMPORT-LOADER-INVENTORY-RED-1`** — `ImportLoaderInventoryTest` red on master since 2026-11-19; cause not yet found.
- **`UI-BUNDLE-BUDGETS-RED-1`** — the la-app and gamma bundle-size checks red on master since 2026-11-19.
- **`DUCKDB-RAW-OPEN-POINTLOOKUP-1`** — `NoRawInMemoryDuckDbOpenContractTest` red on master since 2026-11-24: `PointLookup.java:450`/`:572` open an in-memory DuckDB without `DuckDbUtil.openInMemory(spillDir)`.
- **`AI-OPTIN-VERIFY-1`** — the `-Pai` builds of `AI-OPTIN-1` (reactor `-Pedition-advanced,ai`, `package.ps1 -Edition Standard`, `check-dependencies.mjs`) not yet run on a machine with eoiagent; see [packaging](spec-build-test-packaging.md).

- ~~**`TESTCONFIGS-PREFIX-SUFFIX-TRAP-1`**~~ CLOSED 2026-10-04: `TestConfigs.write()` now emits `<hash>_pipeline.toon`, the SUFFIX every scanning loader matches; pinned by `TestConfigsNameTest`.

- ~~**`LIB-SYSTEM-EXIT-FROM-PUBLIC-API-1`**~~ ✅ **CLOSED 2026-09-20 (`cf40aaf3f`).**
  `ServiceBootstrap.buildFrom(…, exitIfEmpty=true)` — reached from the PUBLIC `CollectorService.fromArgs`
  — now throws the unchecked `EmptyConfigException` instead of calling `System.exit(1)`; only the CLI mains
  (`CollectorService.main`, `ControlApi.main`) catch it and exit 1, so CLI behaviour is unchanged. Pinned by
  `FromArgsEmptyConfigTest`. ⚠ **The trap it caused is still worth knowing: Surefire reports a
  `System.exit` in a test JVM as "The forked VM terminated without properly saying goodbye. VM crash or
  System.exit called?" — read the second half of that question first.** It cost two sessions and a wrong
  JDK-27 diagnosis (`WorkflowConfigLoadTest-1`, closed `f1232b03`). Not done: the `System.exit` sites in
  `SpaceMigrator`, `EnrichmentProcessor` and `CollectorProcessor` (separate call chains, not reachable
  from `fromArgs`).

- ~~**`OPENAPI-CONTRACT-RED-ON-MASTER-1`**~~ ✅ **CLOSED 2026-09-19 (`e5c4f17cd`)** — `GET /assist/skills`
  documented via `-Dopenapi.paths.write=true`. ⛔ It was far worse than filed: one failing module's
  dependents are BANNED by Maven even under `--fail-at-end`, so a red `OpenApiPathsContractTest` silently
  left ~850 tests across twelve Standard/Advanced modules unexercised (20 modules built vs 32).

- ~~**`REACTOR-VERDICT-CI-1`**~~ ✅ **CLOSED 2026-09-25** — `ci.yml`'s `test` job tees its reactor run
  to a log under pipefail and gates on `check-reactor-verdict.mjs` in an `if: always()` step; as-built in
  *A halted reactor is a SILENT PASS* above. ⛔ Deliberately NOT pre-push (operator 2026-09-25): it judges
  a *build*, not repo state, and producing a log at push time means a ~20-minute reactor per push.
- ~~**`BUNDLE-DANGLING-LINKS-1`**~~ ✅ **SHIPPED 2026-09-25** — withheld-doc and relocated-target links
  fixed at package time by `tools/bundle-doc-rewrite.mjs` (shared with the guard, 2026-09-24), and the
  56 surviving repo-only citations neutralised by the same rewrite (operator decision 2026-09-25); the
  guard reports **0** and is wired into CI and pre-push.

- ~~**`README-LINKS-BROKEN-IN-REPO-1`**~~ ✅ **CLOSED 2026-09-16 (`0e6f9a784`)** — thirteen dead targets in
  `inspecto/README.md` (the bundle's first page) re-pointed, and `check-doc-links.mjs` widened to the whole
  repo so the file is visible at all; see `DOC-COUNTS-GUARD-SCOPE-1` below. ⛔ Repo rot must be fixed at
  the target, never swallowed by the package-time rewrite.

- ~~**`EDITION-GATED-TESTS-IN-WRONG-HOME-1`**~~ ✅ **CLOSED 2026-09-25 (this commit)** — test classes that
  guarded CORE behaviour from inside `inspecto-ops`, which the default reactor never builds
  (`mvn -o -pl :inspecto-ops -am test` does not even resolve without `-Pedition-standard`). Severity was LOW:
  `ci.yml` runs `-Pedition-advanced` with tests, so CI always ran them — the exposure was the local
  `mvn -o clean test` loop only. `RepoSpacesConfigValidationTest` moved to `inspecto`; `ControlApiDbBrowserTest`,
  `ControlApiDecisionRulesTest` and `PostgresStateStoreTest` SPLIT (ops siblings `ControlApiDbBrowserOpsTablesTest`,
  `ControlApiDecisionRuleApplyTest`, `PostgresOpsStoreTest`); `ControlApiReconPromoteTest` moved (`2f0b181f9`);
  the last two closed by the split below. ⛔ Never "fixed" by adding the module to the default `<modules>` —
  that reverses signed decision EDG-01 cell 7.

### `DOC-COUNTS-GUARD-SCOPE-1` — the allow-list that caused the README row, removed from its sibling too

`README-LINKS-BROKEN-IN-REPO-1` was fixed at the **shape**: `check-doc-links.mjs` went from
`ROOTS = ['docs','compliance','.claude']` to the whole repo minus a deny-list (`ROOTS = ['.']`), which is what
made the customer-facing `inspecto/README.md` visible at all. Verified 2026-09-17 by falsifying it BOTH
ways — four dead links planted in **tracked** files across four previously-invisible trees fail it by name
(including `inspecto/README.md → ../docs/architecture.md`, the exact link the row was filed about), and the
clean tree passes at 1802 links over 529 files.

✅ **CLOSED 2026-09-17 — `tools/check-doc-counts.mjs` now has the same shape as its sibling:** `ROOTS = ['.']`
minus the identical `SKIP_DIRS` deny-list. The walk went from **493** markdown files to **529** (in-scope, after
the `docs/archived-documents` exemption: **253 → 289**), gaining 36 files in nine previously-invisible trees —
`features/inspecto-agent/docs/` ×14, `providers/asn-parser/docs/` ×10, `tools/templates/*/README.md` ×3, plus `inspecto/README.md`,
`inspecto-ui/README.md`, `dev-infra/`, `spaces/`, `platform/inspecto-engine/`, `features/inspecto-intelligence/`. The widening cost
**no findings and no noise**: those 36 carry ZERO `<!--count:*-->` markers, so the row's "latent, not live"
assessment held exactly. The 68 marked statements and their derived values are unchanged.

⚠ **The deny-list was re-checked against THIS guard rather than assumed to transfer, and two entries turned out
to be load-bearing for counts specifically:** `graphify-out` holds dated `GRAPH_REPORT.md` snapshots carrying **8**
`count:parser-node-types` markers frozen at the value each snapshot day derived, and `inspecto-deploy` — the
packaged bundle — carries a stale COPY of the whole docs tree, **485** markdown files with **59** markers. Widening
without the deny-list would fail the build on generated copies nobody edits. ⛔ **Neither exists in a fresh clone or
a fresh worktree**, which is the trap the sibling's own header records: the change was therefore verified a second
time against the built main checkout (535 walked, 295 in scope, 68 markers, zero leaked from build output), not
only against the clean worktree where it trivially looks green.

Falsified BOTH ways, and the negative direction is the one that proves the fix load-bearing: a deliberately wrong
`999` carrying the `count:parser-node-types` marker, appended to **tracked** `inspecto/README.md`, fails it by name
(`inspecto/README.md:491 states 999, but … derives 7`, exit 1) while the **pre-fix** guard exits **0** on the very
same marker. ⚠ Plant into a file that already exists and is tracked — `git status` must read `M`, not `??`; an
earlier lane's `>>` created the file it meant to test and proved only that untracked files are scanned.

⚠ **Writing THIS section turned the guard red, which is worth keeping:** unlike `check-doc-links.mjs` and
`check-vocabulary.mjs`, `check-doc-counts.mjs` does **not** strip fenced blocks, so a marker quoted literally in
prose — even inside backticks or a code fence — is scanned as a live marker. Documenting one by example is a
standing failure; name the id (`count:<id>`) without the comment delimiters instead. Widening the scope widened
this hazard to every markdown file in the repo.

⚠ **A trap that cost two lanes a build each:** `-DfailIfNoSpecifiedTests=false` is **silently ignored** by
this Surefire (3.5.3). The working spelling is `-Dsurefire.failIfNoSpecifiedTests=false`; without it a
filtered `-am` run dies at `asn-core` — red for the wrong reason.

### Two guard-scope rows this concept still owns (filed 2026-09-17)

**`DOC-COUNTS-FENCED-MARKER-1`** — `check-doc-counts.mjs` does **not strip fenced blocks**, unlike
`check-doc-links.mjs` and `check-vocabulary.mjs`. A count marker quoted literally in prose, in backticks, or
inside a fence is scanned as a live assertion. Found by hitting it while writing the section above; the DOC was
fixed, never the guard. ⛔ Widening the scope to the whole repo widened this trap to every markdown file in it,
so documenting markers by example is now impossible anywhere.

**`README-VOCAB-SCOPE-1`** — `check-vocabulary.mjs`'s `USER_FACING` list holds only
`docs/USER_GUIDE.md`, and its tree scan covers `docs/**` plus the root canon, **not module READMEs**. So
`inspecto/README.md` — the file `package.ps1` copies to the bundle root as the customer's FIRST page — is
unchecked for banned synonyms. ⚠ The sixth guard-scope blind spot in three days, and the same family as
`README-LINKS-BROKEN-IN-REPO-1` (fixed at the shape) and `DOC-COUNTS-GUARD-SCOPE-1` (fixed at the shape) —
which is the precedent for fixing this one.

### The guard-scope family, closed 2026-09-17 — and what the seventh instance cost to find

Three more guards moved from an allow-list to a deny-list, joining `check-doc-links.mjs`:

- **`check-vocabulary.mjs`** (`README-VOCAB-SCOPE-1`) — pass 3 now carries `DOC_SKIP =
  ['docs/archived-documents/']`. ⚠ Its pass 4 reads Java/TS through a **different rule set**; the prose pass is
  markdown-only, which is what made the reshape cheap. 61 files newly in scope, **three** violations, all the
  data-origin sense of that word as a bare table-column header — recorded in `DOC_ALLOW` with reasons, since
  none is a stale synonym. ⛔ One is a **golden fixture** whose text the document generator emits; renaming
  that header to satisfy a guard would have broken its test. `inspecto/README.md` is promoted to `USER_FACING`,
  the no-allowlist pass, because there is no audience below the bundle's first page.
- **`check-doc-citations.mjs`** (`CITATION-GUARD-SCOPE-1`) — the **seventh** instance of the shape. 36 newly
  scoped files carried 8 dead citations, including two type names renamed in 2026-08 still shipping on
  `inspecto/README.md`. ⛔ The `./`-strip in `slash()` is **load-bearing** under `ROOTS = ['.']`: `EXEMPT_TIERS`
  is a plain `startsWith`, so without it both never-maintained tiers silently re-enter scope.
- **`check-doc-counts.mjs`** (`DOC-COUNTS-FENCED-MARKER-1`) — now strips fenced blocks like four siblings.
  ⚠ **Inline backticks are deliberately still scanned:** the floor ratchet has drifted from its stated design
  (68 markers, floors summing to 55), so a marker hidden behind one stray backtick would stop being policed
  rather than trip the floor.

✅ **Three guards now independently derive the same current-tier corpus** — 289 markdown files
(`check-doc-citations` reaching it as 276 + the 13 `docs/superpower/` files it alone exempts, because an
in-flight plan citing an unbuilt path is the plan working).

⛔ **The proof that matters is old-green/new-red, not red-then-green.** Every one of these was falsified by
running the *previous* guard against the same planted input: red-then-green only shows a guard can fail;
old-green/new-red shows the widening does something. Two of the three were invisible on today's corpus
otherwise.

### `EDITION-GATED-TESTS-IN-WRONG-HOME-1` — closed by SPLITTING, and the trap that made moving whole unsafe

`ControlApiDecisionRulesTest` (5 core / 3 ops), `ControlApiDbBrowserTest` (7 / 1) and `PostgresStateStoreTest`
(11 / 5) were **split, not moved**: the core half runs in the default reactor, an ops sibling keeps every case
needing operational objects. 32 tests before, 32 after; no package churn; no new dependency.

🔴 **Imports do not establish ops-independence.** `ControlApiDecisionRulesTest` imports nothing from
`com.gamma.ops`, yet three of its tests drive `GET /objects`. In a default build one **failed** and another
**passed vacuously** — with ops absent, the `/objects` envelope happens to satisfy its `== 1`. Moving the class
whole would have landed a vacuously-green test. The positive control caught it; the triage did not.

**The last two (2026-09-25, this commit)** — `ControlApiAccessDeciderTest` and `ControlApiScopedObjectsTest`
needed a non-ops vehicle, not a file move:

- `ControlApiAccessDeciderTest` **split 3 / 1.** Its three route-level cases (DENY → 403, ABSTAIN falls
  through, action classification, never consulted without a Subject) used `GET /objects` only as "some
  authenticated read route"; they now drive core `GET /pipelines` in `inspecto`. The row-level case needs real
  objects behind `ObjectRoutes` and stays in ops as `ControlApiAccessDeciderObjectsTest`.
- `ControlApiScopedObjectsTest` **stays whole in ops — every one of its assertions guards ops code.** The
  `/objects` filter, 404 and graph pruning are `ObjectRoutes.visibleTo` in `inspecto-ops`. What core owns is
  the other copy of the SEC-7d rule, `AnnotationTargets`' `object` arm (`caseType` vs `Subject.dataScopes`,
  then `RowScope`) — which had **no test anywhere**. New `AnnotationTargetsScopeTest` (4 tests) pins it in the
  default reactor against `FakeObjectEngineProvider` (same thread-scoped classloader swap as
  `ControlApiReconPromoteTest`, never registered module-wide) and a `FakeExchange` carrying the Subject.
- Counted by `assert*(` calls: **35 before** (15 + 20, all edition-gated) → **50 after** (10 core AccessDecider
  + 15 `AnnotationTargetsScopeTest` in the default build; 5 + 20 still in ops). Nothing retracted.
- Mutation-checked: dropping the `dataScopes` check in `AnnotationTargets`, forcing `RowScope.visible` true,
  and removing the DENY throw in `ControlApi.authorize` each turned the matching core test red with the right
  values (`expected: <403> but was: <404>` — the handler ran; "Expected ApiException … nothing was thrown" on
  the out-of-scope and row-denied objects).
