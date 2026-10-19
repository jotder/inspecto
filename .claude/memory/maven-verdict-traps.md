---
name: maven-verdict-traps
description: "Ways a Maven/Surefire run reports the WRONG verdict here: -Dtest '+', failIfNoSpecifiedTests spelling, echo exit codes, WARNING-level summaries, stale reports, no-op launches, System.exit, -DargLine"
metadata:
  type: reference
---

All paid for; each reports success or failure about something other than what you think you tested.

1. **`-Dtest=A+B` matches ZERO tests and exits 0** (`+` is the method separator). Commas only (also in CLAUDE.md).
2. **`-Dtest` with `-am` applies reactor-wide** and halts at the first upstream module ("No tests matching pattern").
   The working flag is **`-Dsurefire.failIfNoSpecifiedTests=false`**; `-DfailIfNoTests` / `-DfailIfNoSpecifiedTests`
   are silently ignored.
3. **Read the per-class `Tests run:` line for every named class.** Absent line = nothing ran, not a pass. When
   bisecting with `-Dtest=Class#method`, grep `Tests run: [1-9]` for the target class.
4. **Exit codes:** `mvn ... > log; echo $?` after a pipe or `; echo` reports the wrong process. Capture inside the
   compound: `( mvn ...; echo "MVN_EXIT=$?" > file )`. Same for `node tools/check-x.mjs | tail`.
5. **Module summaries with skips log at `[WARNING]`**, not `[INFO]`. Count module totals only (lines without
   `-- in <Class>` / `Time elapsed`), INFO and WARNING. A total that is a clean multiple of baseline = double count.
   A changed SKIP count is the signal worth chasing; name every skip.
6. **Compile failure leaves the PREVIOUS surefire report on disk**; a verify agent may report it as this run. If a
   reported line/message is not in the current source, suspect a stale report.
7. **No-op launches:** `cmd /c` from Git Bash can be path-mangled into an idle shell — exit 0, empty log. Use
   `MSYS_NO_PATHCONV=1` or call `mvn.cmd` directly; check log mtime and `^\[INFO\] BUILD (SUCCESS|FAILURE)`.
8. **Fail-fast reactor:** a red module SKIPS every later one — skipped is unverified, not passing (use
   `--fail-at-end` for cross-module probes).
9. **"VM crash or System.exit called?"** — read the second half: exit 1 with no `hs_err` means code called
   `System.exit` (e.g. CLI entry `exitIfEmpty`), not a JVM crash.
10. **`-DargLine=...` does NOT reach the forked JVM** (parent pom uses late-bound `@{argLine}`). Edit source for
    probes; for JVM props use `-DforkCount=0` + `MAVEN_OPTS`. Postgres tests: pass the URL via env
    `INSPECTO_TEST_PG_URL` (a `&` in `-D` is split by `mvn.cmd`); PG 18 rejects the Windows `Asia/Calcutta` zone —
    use `-Duser.timezone=Asia/Kolkata` (keep the offset, never UTC). Test-scope deps are not transitive.
11. **`mvn package` without an edition profile omits edition modules**; `target/` mtimes are not proof of what built.
12. A default-ON operational DB family can mint a real `.db` in the forked module CWD — pin the backend property to
    `jdbc:duckdb:` in tests, not the per-family `*.db.url`.

Related: [[stale-sibling-jar-trap]], [[subagent-claims-are-hypotheses]], [[falsify-negative-tests]].
