---
name: release-workflow
description: >
  MANDATORY checklist for committing, pushing, tagging, or releasing in this repo. MUST be applied
  BEFORE any git commit / push / tag. Encodes the binding branch & version policy from
  docs/BRANCHING.md: versions=branches (active: master + current N.x; retired/EOL: 1.x/2.x/3.x),
  editions=build flavors (never branches), SemVer + Conventional Commits, and the MERGE-FORWARD
  (oldest supported → master) propagation rule. Trigger on any git commit/push/tag, release, or
  branching question.
---

# Release & Branch Workflow (binding)

Canonical policy: [docs/BRANCHING.md](../../docs/BRANCHING.md). This skill is the operational
checklist. Enforced by three layers: a Claude Code hook (agent reminder), `.githooks/pre-push`
(local block — **also runs the committed-secret guard**, above the release override, because CI
catches a leaked secret only after it is already public, **and the canonical-vocabulary guard**,
below the override so it never blocks an emergency security backport), and CI
`.github/workflows/branch-policy.yml` (un-bypassable backstop).
**One-time per clone:** `git config core.hooksPath .githooks`.

## CURRENT STATE (2026-08-17) — apply this before the checklist below

**Nothing is in production after `3.x`.** Work goes along `master`; the next major is cut as a release
branch later. So **`master` is the only line anyone works on** and the **merge-forward set is always
empty** (there is no older *supported* line for a `fix:` to land on).

⛔ **`4.x` NO LONGER EXISTS — deleted 2026-08-17 (operator call).** It never became a maintenance line
and held nothing `master` lacked. Do not try to check it out, back-merge to it, or treat any `4.x` in
the examples below as a live branch. The `v4.0.0` / `v4.0.0-RC1` **tags survive** and are ancestors of
`master`.

Practically, checklist step 4 below resolves to: *"propagation set: empty — `master` only"*. State it
and proceed; do not go looking for an older branch and do not ask the operator to re-confirm it for
routine work. Everything else in this skill still applies — classify the commit, never touch retired
`1.x`/`2.x`/`3.x`, editions are build flavors.

⚠ The rest of this file describes the model for **when a release exists and has users**. It is not
wrong, it is **not yet in force**. See `docs/BRANCHING.md` §0-A.

## The two axes — never confuse them

- **Versions = git branches.** Active: `master` (newest mainline) — **today the only one**; there is no
  current `N.x` (see CURRENT STATE).
  **Retired/EOL (FROZEN — no commits/pushes/tags, never a propagation target): `1.x`, `2.x`, `3.x`.`**
- **Editions (Personal / Standard / Enterprise) = build flavors** (Maven profiles + `ServiceLoader`
  modules + `-D` flags). **Editions are NEVER branches.**

## Versioning

SemVer `vMAJOR.MINOR.PATCH`. **Conventional Commits:** `fix:`→PATCH, `feat:`→MINOR,
`feat!:`/`BREAKING CHANGE:`→MAJOR; `chore/docs/refactor/test/build/ci/perf/style/revert`→none.
Scope encouraged (`fix(etl):`, `feat(ui):`). One version spans all editions; artifacts differ by
classifier (`-personal` / `-standard`).

## Propagation = MERGE-FORWARD (oldest → master)

A `fix:` lands on the **oldest still-supported branch it affects**, then merges forward to `master`
(`4.x → master`). A fix may never silently regress in a newer line. `feat:` goes to `master` only.

## MANDATORY checklist (every commit / push / tag, every branch)

1. **Classify** the change (Conventional Commit type) → SemVer effect + target line.
2. **Find the oldest supported branch** affected.
3. If it's a `fix:` and you're on a *newer* line → **STOP**, relocate the fix down to that branch first.
4. **Enumerate every supported branch** that still needs the change, **ask the user to confirm the
   merge-forward set**, then execute merges up to `master`.
5. **Refuse** to commit/push/tag on retired `1.x` / `2.x` / `3.x`. A security backport to an EOL line
   needs a **human** to set `UCC_RELEASE_GUARD_DISABLE=1` and document it — agents ask, never self-authorize.
6. **Editions** are build flavors — never create/push `personal`/`standard` branches.
7. **Tag** releases `vX.Y.Z` on the branch they ship from.

## Common commands

```bash
# Bug fix present in the shipped major:
git checkout 4.x && git checkout -b fix/<name>      # commit as fix(scope): …
git checkout master && git merge --no-ff 4.x && git push origin master   # merge-forward

# Feature (next release): branch from master, commit feat(scope): …, PR into master only.
```

End commit messages with the required co-author trailer when committing on the user's behalf.
