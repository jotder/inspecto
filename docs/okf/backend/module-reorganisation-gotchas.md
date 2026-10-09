---
type: Guide
title: Module reorganisation - traps and gotchas
description: The traps the module reorganisation (MODULE-REORG-1) hit, each with its cause and the rule, so the next module move, split or packaging change does not repeat them.
resource: docs/okf/backend/module-taxonomy.md
tags: [architecture, modules, gotchas, packaging, testing, guards]
timestamp: 2026-10-09T00:00:00Z
---

# Module reorganisation - traps and gotchas

Context: [module-taxonomy.md](module-taxonomy.md), [decisions](module-reorganisation-decisions.md). Each line is cause, then rule.

## Moving code
- **Regroup recipe: two commits.** Pure moves first (`git mv` only, so history follows), then the path rewrites in a second commit; mixing them hides the renames from git.
- **Mover scripts sweep too much.** `tools/regroup-modules.mjs` ran `git add -A` and committed `target/` files. Rule: stage movers by explicit path and read `git diff --cached --stat` before the commit.
- **Mover comment-strippers lose track at Java text blocks.** The stripper lost its place at `"""`, so two test files missed their rewrite. Rule: grep for the old path/package after any scripted rewrite; never trust the script's own report.
- **Sibling-directory scans go silent after a move.** A guard that reads "sibling of the repo root" or "first path segment" matches nothing once modules sit under `spi/ platform/ features/`. Rule: derive modules from the poms with `ReactorModules` (Java) / `tools/reactor-modules.mjs`; `-pl` takes `:artifactId`, never a path.
- **Guard inventories are keyed by `Class#method`.** Moving `discard` into a new class turned `ConfigWriteFunnelTest` and `DecisionRuleWritersTest` red; an inventory regex that matches nothing after a rename PASSES (a vacuous guard). Rule: every moved symbol gets the guards that grep for it re-pointed and proved red by hand; guard-inventory edits need the operator.
- **Visibility widens for a sibling module** (`OpsEngine`, `ObjectRoutes.requireVisible`, ...). Rule: reuse the SEC-7d data-scope check verbatim, never copy it.
- **A hand-kept mirror of real state drifts** (counts, jar lists, route tables, a test's copy of a table). Rule: derive it (`tools/render-offerings-matrix.mjs`, `check-family-count.mjs`, manifest synthesis) or pin it with a parity test (`ModuleRoutesParity`).

## Generated files
- **Generated blocks and documents are reverted by their guard.** `docs/api/openapi-v1.json` is generated from fragments (`openapi-merge.mjs --write`); the marked block of `docs/EDITIONS.md` from Offerings. Rule: edit the source (fragment, `offerings/*.toon`, manifest), regenerate, run the `--check`. A merge conflict resolved by regex once put a path OUTSIDE `paths`: compare the path SET, not just that the JSON parses.
- **The error-code enum in the OpenAPI document is hand-kept** (add the new code with the route).
- **A table that cannot express a decision** (a hand-edited note on a generated row) makes the generator revert it. Rule: keep that row hand-authored outside the block.

## Packaging
- **Package EVERY edition after any `package.ps1` change** (Personal, Professional, Enterprise, Preview). `Set-StrictMode` unset variables kill an edition nobody packaged (Personal was dead for weeks; guard `check-package-strict`).
- **A jar dropped beside the launcher is no longer on the classpath**: the Offering-written `modules.list` decides the classpath and the edition.
- **Shaded processor jar: `-pl` without `clean` runs stale classes**, and a `-pl`/`-rf` run without `-am` tests a stale sibling jar. Rule: `clean`, and build with `-am`.
- **A module needing a `jdk.*` module** broke every ingest in the zip once. Rule: prove each Offering on the bundle's own `runtime/bin/java`; `jlink-modules.lock` and `jlink-runtime-extra.txt` carry the set.
- **Sign before the SBOM** (a signed jar has a different hash).
- **A green full reactor is not green CI.** Route-gating, the OpenAPI contract, authgate coverage and the CI guards job fire only there. Run every `ci.yml` no-build guard before pushing.
- **`.claude/launch.json` jar lists are static copies** of `tools/offering-classpath.mjs --print`; re-derive them whenever a module is split or added.

## Loading and failing soft
- **`OptionalSpi` is fail-soft; a base provider must fail closed.** An absent or unloadable optional module contributes nothing silently; anything that must exist belongs in a base provider. A module's features are collected only after `register()` succeeded.
- **Shaded jars collapse same-named resources** (every `module.toon` shared one path); the `---` separator in manifests tolerates a merge. A thin jar keeps its own.
- **TOON has no comment syntax**: a `#` line in a manifest or Offering corrupts it.
- **The per-Space `modules.toon` cache** keys on (mtime, size): a same-size rewrite inside one filesystem tick reads stale (a flaky test, fixed in `cee0153eb`, was exactly this).
- **A rebuild-from-modeled-state write drops every key it does not model**: never write a typed record back without carrying the unmodelled keys (`x-` kept, anything else refused 422).
- **A disabled module's in-flight Run is not interrupted**, and `backup` / `intelligence` have no feature id so they cannot be switched off.

## Testing
- **`-Dtest=A+B` runs ZERO tests and reports SUCCESS**: use commas, and count `Tests run`.
- **Run gates from a path NOT under `.claude/` or `%TEMP%`** (a safety test goes falsely red).
- **A full UI `ng test` overlapping a Maven gate flakes timing tests.** Run them one at a time; one Maven agent at a time.
- **Tests with no Subject make `withCapability` a no-op**: they pass against an UNGATED route. Give the test a real Subject.
- **A negative test needs a probe that would otherwise succeed**; mutate the whole feature, not one clause.
- **What the TCKs cannot drive:** connector `create`, workbench and egress/SSRF behaviour; `JobTypeProvider.create` and running a Job; the existence of each `requires:` Platform Service (registered only in the processor); positive `Authenticator`/`TokenRelay` paths; a live-model description. Those stay in each module's own tests.
- **A new ROUTE needs an `openapi-v1.json` entry**: the failure shows up in a module you never touched. A route whose `register()` needs the host (Exchange once did) cannot be driven by the test kit: move the host installs into a `HostBootHook`.
- **A real defect a TCK found:** `OpsMaintenanceTasks.run` ignored its `task` argument and purged for any name (fixed: it throws `IllegalArgumentException`).

## Docs
- **Hand-kept counts drift** (module counts, jar counts, family counts, SPI extension points): `check-doc-counts` derives the policed ones; do not add a new hand count.
- **A new doc must be `git add`ed** before the doc-link guard resolves it (it reads `git ls-files`).
