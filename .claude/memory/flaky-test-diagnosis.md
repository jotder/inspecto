---
name: flaky-test-diagnosis
description: An intermittent test failure on this Windows host is usually a file-handle or spawn race - capture the real exception before bisecting
metadata:
  type: feedback
---

**Capture the exception first; bisect last.** A test that fails about 1 run in 3 will mislead `git bisect`: four clean
runs at a commit happen by chance, so the bisect names an innocent commit (2026-11-17: the geo-link index tests were
pinned on the DATA-TIERS-1 commit, which never touches the index).

**Why:** the code often records only an exception's class (e.g. `IndexBuildService` keeps `IndexBuildException`,
never its message), so the failing run says nothing about the cause.

**How to apply:**
1. Re-run the test alone several times to measure the rate, and on the pre-change baseline too.
2. Add a temporary `e.printStackTrace()` at the swallow point (never commit it), loop the test until it fails, and read
   the cause.
3. Two Windows causes seen here, both transient, both fixed by a bounded retry:
   - `AccessDeniedException` renaming a directory (`Files.move` with `ATOMIC_MOVE`) while any handle inside it is
     still open — `IndexStore.publish` stage → `vNNNNNN` (`f1f279570`, same retry as `moveCurrent`).
   - `spawnSync python3` → `EACCES`: `python3` resolves first to the Windows Store app-execution alias, which refuses
     to start now and then under load (`tools/la_gen_io.test.mjs`, `ef2bd47b6`).
4. Prove the fix with a test that reproduces the race (e.g. hold a file open inside the directory for 150 ms) and
   falsify it.

Related: [[falsify-negative-tests]], [[maven-verdict-traps]].

Long form, with the original incidents (from the retired `PROJECT_NOTES.md`, 2026-11-19): [tests-build-verdict-gotchas.md](../../docs/okf/product/layer/platform/tests-build-verdict-gotchas.md).
