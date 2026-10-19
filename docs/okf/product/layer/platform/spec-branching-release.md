---
type: Concept
title: Branching & Release Policy
description: Versions are branches; editions are not. Merge-forward propagation, SemVer, Conventional Commits.
resource: docs/BRANCHING.md
tags: [branching, release, semver, conventional-commits, merge-forward]
timestamp: 2026-06-28T00:00:00Z
---

# Branching & Release Policy

Authoritative doc: `docs/BRANCHING.md`. Two axes, never confused:

* **Versions = git branches.** Active: `master` (newest mainline) + the current `N.x` (today `4.x`). Retired/
  EOL and frozen: `1.x`, `2.x`, `3.x` (no commits/pushes/tags; never a propagation target).
* **Editions = build flavors** (see [editions model](architecture-editions-model.md)) — never branches.

**Versioning**: SemVer `vMAJOR.MINOR.PATCH` + Conventional Commits — `fix:`→PATCH, `feat:`→MINOR,
`feat!`/`BREAKING CHANGE:`→MAJOR; `chore/docs/refactor/test/build/ci/perf/style/revert`→none. One version
spans all editions.

**Commit scope = Product Map Item; release notes come from git (2026-10-19).** `feat` / `fix` / `perf`
commits are scoped by the item they change (`feat(reconciliation): …`; general scopes `ci`, `deps`,
`docs`, `tools`, `release` … for the rest). `tools/release-notes.mjs <from> [<to>] [--item <id>]` turns
the range into whole-product notes (by item, then BREAKING / feat / fix / perf / docs) plus one file per
item under `target/release-notes/`; commits without an item scope (all history before this rule) are
attributed by the module directories, product hubs and product-map files they touch.
`tools/check-commit-scope.mjs` is the advisory, opt-in commit-msg hook — see the `release-workflow` skill.

**Propagation = MERGE-FORWARD (oldest → master).** A `fix:` lands on the **oldest still-supported branch it
affects**, then merges forward up to `master` (`4.x → master`) — a fix may never silently regress in a newer
line. `feat:` goes to `master` only (empty merge-forward set). Refuse to commit/push/tag on retired
`1.x`/`2.x`/`3.x`.

This is enforced by three layers: a Claude Code hook (agent reminder), `.githooks/pre-push` (local block),
and CI `branch-policy.yml` (un-bypassable backstop).
