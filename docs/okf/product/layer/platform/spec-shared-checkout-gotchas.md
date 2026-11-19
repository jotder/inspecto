---
type: Reference
title: Shared checkout and git - gotchas
description: Traps of the shared team checkout: peer sessions, the shared git index, staging, worktrees and commits.
tags: [git, shared-checkout, gotchas]
timestamp: 2026-11-19T00:00:00Z
item: layer.platform
audience: internal
status: current
verified: 2026-11-19 last-edit
---

# Shared checkout and git - gotchas

Moved verbatim from the retired `PROJECT_NOTES.md` (D-IR5, 2026-11-19); the archived copy is `docs/archived-documents/project-notes-2026-11-19.md`. Each entry is cause, then rule.

- 🔴 **The MAIN checkout cannot build, serve or pass the pre-push link guard on the `User` Windows profile; do all
  three from a clean worktree** (2026-10-11). `inspecto-demo/runtime-natives` is ACL-locked to the other Windows user
  (the profile that created it), so the `known-modules` exec step of `package.ps1` / any `-Pedition-advanced` reactor
  build, `check-doc-links.mjs` (pre-push) and `serve-demo` (DuckDB native unpack) all die with `AccessDenied`. A fresh
  `git worktree add` has no such folder: build, serve and `git push origin HEAD:master` from it (every guard still runs
  there). A `-NoRuntime` bundle needs a junction to the main `inspecto-demo/runtime`. `tools/run-backend.ps1` resolves
  sibling modules from `.m2`, so run `mvn -o -Pedition-advanced -pl inspecto -am -DskipTests install` from the
  worktree first, and add `-Dcontrol.bind=127.0.0.1` (via `JAVA_TOOL_OPTIONS`; the script has no bind switch, so it
  listens on every interface with no sign-in). Agent `isolation: "worktree"` is refused here: create worktrees by hand.

- **A peer session may be mid-merge in the main checkout** (`.git/MERGE_HEAD`): never run `git merge`/`pull` there.
  Merge lanes in a scratch worktree and push `HEAD:master` from it; `SESSION_STATUS.local.md` is stacked, newest
  section first, so a second handoff prepends instead of replacing.

- ⛔ **THE GIT INDEX AND WORKING TREE ARE SHARED BETWEEN SESSIONS** (2026-09-17). A peer's `git add` stages
  into *your* staging area, and their uncommitted file can turn *your* pre-push red — in one case failing
  a different guard depending on whether it was staged. Use `git commit --only <paths>`. ⚠ And when you
  find a file that is not yours: **UNSTAGE it, never delete it** — `git restore --staged` leaves it on
  disk. `rm` plus `git checkout --` destroyed a peer's uncommitted plan this shift; it survived only
  because they re-created it. ⚠ `git apply -3` also writes to the index, and swept another lane's files
  into the wrong commit twice in one day.

- 🔴 **THE SHARED TREE HAD A PEER'S UNCOMMITTED WORK DURING HANDOFF — AND IT BLOCKED THE ARCHIVAL STEP**
  (2026-09-16). At shift end `git status` showed 10 modified + 8 untracked files from another session
  (dead-property validation, accepted-config-keys, two new designs). Two of them were load-bearing for MY
  handoff: the peer was **editing `dataset-column-derivation-plan.md`**, the very plan the doc lifecycle said
  to archive, and had **`docs/INDEX.md` dirty** — and archiving a plan *requires* an INDEX edit. ⇒ the
  archival was **deferred, not skipped**, and recorded as owed. ⛔ Do not `git mv` a plan or edit `INDEX.md`
  while a peer has either dirty: that is how a peer's work gets swept into someone else's commit (it has
  happened here before). Stage by PATH, never `-A`, and read `git diff --cached` before committing.

- 🔴 **A RED REACTOR IN THE SHARED TREE IS NOT EVIDENCE UNTIL `git status` SAYS WHOSE CHANGES IT COMPILED**
  (2026-09-15, eleventh shift). A full `-Pedition-advanced` run went red in 41 tests across
  `DbStatusStoreTest`, `CollectorServiceTest`, `RunLeaseContractTest`, `ControlApiTest` — none touched by the
  change under test. No concurrent build, no contention in the log. `git status` showed **nine uncommitted
  engine files belonging to the other session**: half-finished work on three board rows, compiled and tested
  with mine. The same patch on a clean base was 4530/0/0/28. ⇒ **Verify in a detached worktree at a PINNED
  SHA** (`git worktree add --detach <path> $(git rev-parse HEAD)` — `HEAD` moved under the peer mid-run and
  their "isolated" green turned out to contain my code), apply only your patch, and **assert a fact that
  differs between base and patch** before building (a per-class `@Test` count caught what a total could
  not). ⚠ **Keep the worktree under `C:\sandbox\`**: `ControlApiJobCrudTest`'s save-time containment
  assertion fails in a `%TEMP%` checkout and passes under `C:\sandbox` and on the CI runner — path-sensitive,
  not red. ⛔ Never `git stash` in the shared tree to isolate; ⛔ never `git checkout -- <file>` inside the
  worktree to undo a mutation (it reverts to base and loses the patch — copy aside/back). ⚠ Two verify
  agents' aggregate sums were wrong the same day (by 226 and by 1): recount from the per-module `Results:`
  blocks yourself, and beware per-class + per-module lines summed together (≈2×).

- 🔴 **STAGING A SHARED FILE BY PATH SWEEPS THE OTHER SESSION'S HUNKS INTO YOUR COMMIT** (2026-09-15).
  `87a4d97c` was staged by explicit path — the standing rule — and still carries the peer's three
  `BACKLOG.md` rows, because both sessions were editing the same file. "Never `git add -A`" protects
  against other FILES, not against a collision inside one. For `BACKLOG.md`, `INDEX.md`, `PROJECT_NOTES.md`:
  `git diff -U0 -- <file> | grep -c '^@@'` must equal the hunks you wrote, and each must be yours, before the
  file is staged. The content landed correctly; only the attribution is wrong, and `6770d031` says so.

- 🔴 **A RED GATE CAN ALSO BE A CONCURRENT BUILD — this checkout is SHARED.** Measured 2026-09-14: a full
  reactor run died in `inspecto-engine` with `NoClassDefFoundError` for `com.gamma.util.CurrentSpace` and
  `SingleConnectionSource` — classes that compile fine, declared at **compile scope**, in a module that had
  reported SUCCESS seconds earlier. Cause: another session ran `mvn clean` in the **same working tree**,
  deleting `platform/inspecto-util/target/classes` mid-run. ⛔ **Before debugging a classpath error, `ls -la` the
  missing `.class` and compare its mtime to your run** — a file re-created *during* your build is the tell.
  Then simply re-run: it did not reproduce, and the suite passed 4475/0. ⚠ The same race can corrupt any
  measurement taken here; a lone anomalous failure deserves one re-run before it earns a bug report.

- 🔴 **STAGING IS NOT SAFE PARKING — a peer session's stop-hook will commit and push your staged tree.**
  Twice now (latest 2026-09-14, `519673a7`): 38 staged files of one shift's refactor landed inside another
  session's commit, under a message describing something else entirely, credited to another model, and were
  pushed before they could be verified. ⛔ **Commit as soon as a coherent unit exists** rather than holding
  a large `git add -A` while you verify — and when a shared-trunk commit turns out to misdescribe its own
  contents, **record the facts forward** (a follow-up doc commit) rather than rewriting pushed history.
  ⚠ Corollary: a dirty file you did not create belongs to someone else — never sweep it into your commit.

- ⛔ **Confirm another repository's DEFAULT BRANCH before pushing to it.** `jotder/inspect-agent`'s is
  `main`, not `master`; a `HEAD:master` push silently created a stray branch instead of landing, and the
  only signal was the words `* [new branch]` in the push output.
  `gh repo view <owner>/<repo> --json defaultBranchRef -q .defaultBranchRef.name` (2026-09-14).

- 🔴 **Uncommitted work here is volatile — a peer can DISCARD it, and `git status` then reads exactly like
  "you did nothing".** Measured 2026-09-10: a peer staged this shift's in-progress tree, aborted the commit
  on an empty message, then reset/cleaned it. **HEAD never moved, so there was no reflog entry and no
  stash**, and four new files were simply gone. ✅ It was fully recoverable because **`git add` writes each
  staged file into the object database as a loose blob that an aborted commit leaves behind**: select loose
  objects by mtime (`find .git/objects -type f -newermt '<HH:MM>' | grep -v /pack/`) and `git cat-file -p`
  them out. ⚠ **`git fsck --lost-found` listed none of the eight** — mtime selection is what worked, and
  `.git/COMMIT_EDITMSG` still named the peer's staged file set, which is how the staging was known at all.
  ⛔ Only *staged* content is recoverable this way. ⇒ Snapshot to the scratchpad (`git diff > …patch` plus
  the untracked files) at every milestone; a `git clean` cannot reach it.

- 🔴 **This checkout can be worked by TWO shifts at once, and a shared tree breaks builds in ways that
  look like code defects.** Observed 2026-08-26: a second agent ran `mvn -o clean test` four minutes into
  another shift's identical run, and its `clean` wiped `target/` mid-flight ⇒ `NoClassDefFoundError` on a
  sibling module's class, whole real-HTTP test classes erroring at ~0.004 s each. **That shape means a
  wiped target, not a broken change.** The same session also saw the tree go red because the other shift
  had deleted a class (`Values`) that a third module still imported — a transient mid-refactor state, not
  a regression. And a `git push` answered *"Everything up-to-date"* because the other shift's push had
  already carried the commit up. **Before trusting any build or git verdict on this box: `git log
  --oneline -1` (your `HEAD` moves when the other agent commits) and check for an `mvn` process you do
  not own.** ⛔ Never run two Maven builds on one tree. The durable fix is a worktree per shift.
  ⚠ A process-poll loop that greps for `mvn.cmd` **matches its own command line** and never reaches
  zero — match the java process, or exclude the shell wrapper.
  ⚠ **And "no java processes" is never true on this box** — long-lived stale JVMs run for days
  (2026-08-27: one alive since 2026-08-25). An `until [ ... -eq 0 ]` waiter never terminates and a
  plain count reads "busy" forever. Filter on `StartTime -gt (Get-Date).AddMinutes(-N)`, or better,
  wait on the build log's own `BUILD SUCCESS|FAILURE` marker.

- 🔴 **`.claude/worktrees/` holds a FULL second checkout pinned to an OLD commit** (2026-08-27:
  `sweet-ritchie-072797` at `60b7a6b9`, 1,437 `.java` files). **Any repo-wide census that walks the
  tree double-counts and silently mixes stale sources in** — a fan-in measurement reported exactly 2×
  on every class before it was excluded. Exclude `.claude/`, `.git/` and `target/` in every tree walk;
  `git ls-files` / `git grep` are safe because they only see the index.

- **`git commit` commits the INDEX, and on this shared checkout a shift can hand you a dirty one.**
  Shifts end without committing, so `git status` may open with work *already staged* — on 2026-08-15 a
  previous shift's staged `git mv` was silently swallowed into an unrelated `docs:` commit, because
  `git commit -F-` takes whatever is in the index regardless of what you just added. ⛔ **Run
  `git diff --cached --stat` immediately before every commit** and confirm the list is exactly what you
  intend. (Caught on `git show --stat` and unwound before pushing; nothing reached origin.) The same
  hazard is why staging here is always explicit paths — ⛔ never `git add -A` at the repo root, where the
  untracked `spaces/**` is real operator CDR data.

- **`git mv` stages the rename from the INDEX, not the working tree.** Edit a doc and *then*
  `git mv` it, and the edits stay **unstaged** — `git status` shows `RM` (renamed + modified), which
  is easy to skim past, and the commit ships the file's *pre-edit* content at its new path. This bites
  the documentation lifecycle directly (root `CLAUDE.md`: rewrite a plan's status header, then archive
  it), and it did: the platform-services plan landed in `archived-documents/` still claiming work
  remained. **Either `git add` the new path explicitly after the move, or verify with
  `git show HEAD:<newpath>`.**

- **Never start a foreground build while a background one is running in this tree.** A concurrent
  `mvn clean` wipes the sibling build mid-run. On 2026-09-12 a gauntlet reported `MVN_EXIT=1` with 11
  of 26 modules and looked exactly like a regression; it was self-inflicted. ⚠ Related: always check
  **both** `BUILD SUCCESS` and the module count, because a clobbered run can still print a plausible
  test total (it printed 3752 against a true 4315).

- 🔴 **BESIDE AN ACTIVE PEER, `git commit -- <explicit paths>` IS THE ONLY SAFE COMMIT — `git add` by
  path does NOT protect you.** This tree is shared, and a peer's files can already be **staged** before your
  session starts. On 2026-09-17 `docs/INDEX.md` and a new plan sat in the index as someone else's work-in-
  progress; `git add <my files>` left them staged, so a plain `git commit` would have shipped their work
  under my message — the incident this repo has already recorded twice, from both directions.
  ⇒ **Commit with a pathspec** (`git commit -F - -- pathA pathB …`): it commits the working-tree content of
  exactly those paths and leaves every foreign index entry untouched. Verify afterwards with
  `git status --short` that their entries are still `M `/`A ` (staged, uncommitted).
  ⚠ **One trap in that workflow:** a **new UNTRACKED** file cannot be committed by pathspec —
  `git commit -- <new file>` fails with *“did not match any file(s) known to git”*. `git add` that ONE file
  first (safe — it is yours), then pathspec-commit. ⚠ The by-hunk rule still applies to a SHARED file both
  sessions edit; the pathspec rule is for disjoint files with a dirty shared index.
