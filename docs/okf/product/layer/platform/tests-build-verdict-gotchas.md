---
type: Reference
title: Build and test verdicts - gotchas
description: Ways a Maven, surefire or UI test verdict lied, how to read one, and how to count the reactor.
tags: [build, maven, surefire, testing, gotchas]
timestamp: 2026-11-19T00:00:00Z
---

# Build and test verdicts - gotchas

Moved verbatim from the retired `PROJECT_NOTES.md` (D-IR5, 2026-11-19); the archived copy is `docs/archived-documents/project-notes-2026-11-19.md`. Each entry is cause, then rule.

- 🔴 **Piping a gate into `head`/`grep` reports the PIPE's exit code**, not the gate's. This printed a
  false `TSC-OK` over a real TypeScript error. Redirect to a file and read `$?`.

- ⚠ **A test-count baseline taken from a `-Dtest=`-filtered run is NOT comparable to a plain run**
  (2026-09-19). Supplying ANY `-Dtest=` value — even a negation like `-Dtest='!SomeTest'` — switches
  Surefire's class discovery off its default `**/*Test.java` / `*Tests.java` / `*TestCase.java` patterns
  and onto "every class minus the excluded one". That sweep picks up classes the default run never sees:
  here four `*Benchmark` classes (`RescanBenchmark`, `PerfDeepDiveBenchmark`, `PluginIngestBenchmark`,
  `PipelineBenchmark`), **each of which reports `Tests run` == `Skipped`** — zero real executions, but
  both counters inflated. A filtered full gate read 4864/34; the plain gate on the same tree read
  4859/28. ⛔ **The HIGHER number was the misleading one.** Compare like with like, and treat a count
  that moved without a code change as bookkeeping until proven otherwise.

- 🔴 **`--fail-at-end` does NOT rescue a failed module's DEPENDENTS** (2026-09-19). Maven bans them
  ("banned from the build due to previous failures") and they are never exercised — they are not passing,
  they did not run. One undocumented route failing `OpenApiPathsContractTest` in an upstream module
  suppressed **~850 tests across 12 modules** — every Professional and Enterprise module — while the
  build reported a single red test. ⚠ **A doc-contract failure upstream is a VERIFICATION OUTAGE
  downstream, and it does not look like one.** Same shape as the stale doc guard at `ci.yml:64` that
  suppressed the whole reactor for a day behind six "known red" runs. Check the SKIPPED list, not just
  the failure count.

- 🔴 **Surefire's *"The forked VM terminated without properly saying goodbye. VM crash or System.exit
  called?"* is a TWO-PART question — read the second half first** (2026-09-19). `Process Exit Code: 1`
  with **no `hs_err_pid*` and no `.dumpstream`** means nothing crashed: something called `System.exit`.
  ⛔ `System.exit` is reachable from LIBRARY paths, not just `main` — `ServiceBootstrap:67` (empty config
  discovery under `exitIfEmpty`, reached from the PUBLIC `CollectorService.fromArgs`), and also
  `CollectorService`, `SpaceMigrator`, `EnrichmentProcessor`, `CollectorProcessor`. The forked JVM dies
  with no exception to catch and no stack trace, so the message blames a VM crash and the hunt starts in
  the wrong place. ⚠ **This exact symptom consumed two sessions and produced a confident, wrong "JDK 27
  crashes the fork" diagnosis** whose proposed fix (bump surefire 3.2.5 → 3.5.3) could not have worked —
  the fork was not failing at startup, it was running and then exiting. Check `java -version` of the
  FORK, not of Maven, before blaming a JDK. (`WorkflowConfigLoadTest-1`, closed `f1232b03`;
  `LIB-SYSTEM-EXIT-FROM-PUBLIC-API-1` tracks the library half.)

- ⚠ **A test fixture can write a name the production scanner cannot see.** `TestConfigs.write()` emits
  `pipeline_<hash>.toon` (PREFIX) while directory-scanning loaders match `*_pipeline.toon` (SUFFIX). 88
  test files use it and none noticed, because they load by explicit PATH — the trap springs only for a
  test that boots by SCAN, and then it presents as the JVM death above rather than as a missing file.

- 🔴 **FOUR NEW FALSE-GREEN MECHANISMS, all of which report SUCCESS** (2026-09-17). (1) `mvn … ; echo
  "EXIT=$?"` records the **echo's** status, so a failed build reports exit 0 — capture Maven's status
  directly. (2) `-DfailIfNoSpecifiedTests=false` is **silently ignored**; the working spelling is
  `-Dsurefire.failIfNoSpecifiedTests=false`, and without it a filtered `-am` run dies at `asn-core` for the
  wrong reason. (3) **`-Dtest=A+B+C` runs ZERO tests and reports BUILD SUCCESS** — `+` is Surefire's *method*
  separator, so the whole thing is one literal pattern matching nothing. Commas are required. (4) A subagent
  reporting **"Verdict: PASS" from the wrong tree and without `-Pedition-advanced`** — 23 modules instead
  of 32, so every edition-gated module was skipped, with the verdict read off the log rather than from
  `check-reactor-verdict.mjs`. ⇒ **Re-derive every verdict with the tool, on the tree you are about to
  commit.**
  ⚠ And one **false RED**, which is rarer and makes people "fix" a non-problem: with `node_modules` absent in
  a fresh worktree, `npx tsc` resolves to an unrelated npm package and fails with a message that reads exactly
  like a type error.

- 🔴 **A TEST CAN BE GREEN FOR A REASON THAT IS NOT THE CODE** (2026-09-17).
  `ControlApiBundleNewKindsTest`'s bundle-escape guard passed or failed depending on three machine facts and
  none of them the security property: the allowed roots are
  `${session.executionRootDirectory};${java.io.tmpdir}` (`pom.xml:418`), the value resolved against the
  **process working directory** because the test helper clears `assist.write.root` before the request, and six
  `..` clamps at the drive root on Windows. `-Djava.io.tmpdir=C:\` flipped the verdict with **no code change**.
  ⛔ It was announced as "master is RED on a security guard" before being grounded — and the gate was in fact
  sound. ⇒ **Probe the gate DIRECTLY (call the validator by hand) before believing a test about it**, and pin
  such a property against an absolute path constructed to be outside every root, never by counting `..`.

- ⚠ **A PER-MODULE GREEN IS NOT A TREE GREEN** (2026-09-16/17, hit four times). `inspecto-exchange` is
  edition-gated, so no lane's `mvn -o -pl inspecto -am test` compiles it — it failed a combined run every
  lane had passed. A cross-field rule change in `inspecto-config` went red three modules downstream in
  `inspecto-intelligence`. ⛔ `mvn -o -pl :inspecto-ops -am` does not even RESOLVE without
  `-Pedition-standard`, which is why the repo's only sweep over every committed Space config never ran in
  a default build. ⇒ Gate on `tools/check-reactor-verdict.mjs`, never on a sum of surefire reports.

- 🔴 **THREE WAYS A BUILD VERDICT LIED IN ONE SHIFT** (2026-09-15) — all three produced confident,
  specific, wrong answers:
  1. **A stale `build.log` in a reused scratchpad path.** A delegated verification reported two failures
     verbatim from an earlier run, both already fixed. ⛔ Tie any delegated verdict to a fresh run: ask for
     the summed per-module lines and check them against the known baseline.
  2. **`-pl <module>` without `-am`** — four tests erroring with
     `java.io.IOException: HTTP/1.1 header parser received no bytes`, which is **a server that never
     booted** against a stale sibling jar, not the defect it looked like.
  3. **`-Dtest='A+B'`** is not valid syntax: it runs **nothing** and reports `BUILD SUCCESS`.
     Use commas. (Same family as the no-op-build-reports-exit-0 note below.)

- ⚠ **COUNT THE REACTOR BY SUMMING SUREFIRE REPORTS, NOT BY PARSING THE LOG** — `**/target/surefire-reports/
  *.txt`, one file per class, is authoritative and sidesteps the `[WARNING]`-level-module trap entirely. Use
  `**`, not `*`: the five `asn-*` modules are nested under `providers/asn-parser/asn-decoders/`, so a single-level glob
  reports **21 modules / 4429** — a plausible-looking total rather than an obviously broken one (true: 26 /
  4508). ⚠ And when a total does not reconcile, **suspect the BASELINE'S PROVENANCE first**: an "unexplained
  +2" on 2026-09-15 was entirely a briefing error — the baseline had been measured when a test class held 9
  tests and the reconciliation was stated against the 11 it held later. ⇒ record *when* a baseline was taken
  and *what the changed classes held then*, not just the number.

- ⚠ **A ZONE WITH NO DST CANNOT TEST DST.** Every `CronExpression` test ran in **UTC** and the one
  cross-midnight case in **Asia/Kolkata** — neither shifts — so the one behaviour a civil-zone cron exists to
  get right was unverified for as long as the feature existed. The mechanism turned out correct; the gap was
  the fixture's constants. ⇒ check what a fixture actually *exercises*, not that it exists. Pinned
  2026-09-15 in `CronExpressionTest`: spring-forward **skips** the hour that does not exist (a daily 02:30
  job does not run on 2026-03-08 — a real operational consequence), autumn-back fires **once, not twice**.

- ⚠ **A test that asserts a RESOURCE QUANTITY is host-dependent, and CI is the small host.**
  `ConfigSafetyValidator` bounds `processing.threads` by `availableProcessors()` — 12 on this sandbox, 4
  on a GitHub runner — so a test writing `threads: 7` was a 200 here and a 422 there for as long as it
  existed. ✅ **Reproduce with `JDK_JAVA_OPTIONS="-XX:ActiveProcessorCount=4"`, which reaches the forked
  test JVM.** ⛔ The older recipe here — `MAVEN_OPTS` plus `-DforkCount=0` — **hung the reactor** inside
  `ControlApiPreferencesTest`. The two variables were separated on 2026-09-14 and the verdict is that
  **`forkCount=0` was the cause and the core count was innocent**: forked at 4 cores the full enterprise
  reactor completes at **4475/0/0/24**, identical to 12 cores, and `ControlApiPreferencesTest` itself
  passes in 0.115 s (`CORECOUNT-SWEEP-1`).
  ⚠ **That result masks the core count ONLY** — the box kept 32 GB. Behaviour on a 4-core host with
  proportionally less RAM is still not established, and is deliberately not carried as an open row.

- 🔴 **`mvn compile` does NOT rebuild a downstream module when you change a SUPERTYPE's API.** Maven's
  incremental compiler looks at each module's own sources, so removing a method from an interface in
  `inspecto-util` produced an all-green `BUILD SUCCESS` in **4.5 seconds**, every module at `0.0x s`,
  while fifteen callers were still broken. ⛔ Use `clean` for any cross-module signature change, and treat
  a suspiciously fast green build as a no-op until proven otherwise (2026-09-14, `OPS-03`).

- 🔴 **Local dev services live in [`dev-infra/`](tests-dev-infra.md) — and two test suites SKIP
  without them, which is not a pass.** `docker compose -f dev-infra/docker-compose.yml up -d` brings up
  PostgreSQL (for `PostgresStateStoreTest`, 15 tests) and WSO2 Identity Server (for
  `OidcAgainstRealProviderTest`, 2). Every command needed to arm them is in that README because none is
  guessable: the Postgres URL must arrive via **env var** (Windows `mvn.cmd` re-parses args and drops the
  password after the `&`), PG 18 rejects the legacy `Asia/Calcutta` zone a Windows JVM sends (fix with
  `Asia/Kolkata`, **never `UTC`** — that would move `record_day` boundaries), and WSO2 must be registered
  with `ext_token_type: JWT` or it issues an opaque token and every request 401s silently.

- 🔴 **`-DargLine` does NOT reach the forked surefire JVM.** The parent POM is
  `<argLine>@{argLine} …</argLine>`, and that late-bound property resolves the **project** property — a
  command-line `-D` does not override it. `systemPropertyVariables` is also too late for anything the JVM
  fixes at startup (the default TimeZone). ✅ **Use `JDK_JAVA_OPTIONS` — it reaches the FORK, so nothing
  has to be unforked** (confirmed 2026-09-14 with `-X`: the flag is absent from the forked command line
  under `-DargLine`, and under `JDK_JAVA_OPTIONS` the JVM prints `NOTE: Picked up JDK_JAVA_OPTIONS`
  **twice** — Maven's JVM and the fork — with `availableProcessors()` reading 4 inside). ⛔ The route this
  note used to recommend, **`-DforkCount=0` plus `MAVEN_OPTS`, is the one that HUNG the reactor**; it was
  never the core count. See `CORECOUNT-SWEEP-1`, settled 2026-09-14: forked at 4 cores the full reactor
  passes **4475/0/0/24**, identical to 12 cores.
  ⚠ Related: `-Dtest='A+B'` silently runs **nothing** under `failIfNoSpecifiedTests=false` (exit 0, empty
  log) — the separator is a **comma**.

- 🔴 **A skipping test can hide a broken harness, not just absent coverage.** `PostgresStateStoreTest`
  skipped cleanly from 2026-09-07 while `inspecto-ops` had **no JDBC driver at all** — `inspecto/` and
  `platform/inspecto-engine/` declare it test-scope and **test scope is not transitive**. When a skip's
  precondition finally arrives, treat the code as untested rather than as coverage that merely paused.

- 🔴 **A build-running subagent may return BEFORE the build finishes, and its "standing by" reads exactly
  like a result.** Happened twice on 2026-09-11, once after 11 minutes. ⇒ **Block on the terminal marker
  yourself** — `until grep -qE "BUILD SUCCESS|BUILD FAILURE" <log>; do sleep 15; done` — and confirm the
  log's mtime has stopped advancing before trusting it. ⚠ A partial Maven log is indistinguishable from a
  passing run with fewer tests: the first red run here stopped at `inspecto-processor` and **13 modules
  never executed at all**, including the one carrying a third of the change.

- 🔴 **Re-sum the reactor's module lines yourself; do not relay a subagent's arithmetic.** One reported a
  phantom "−5 test drift" by treating a handed-over baseline as *passed* when it was *Tests run*. The real
  delta was exactly the tests added. ⚠ Handoff tallies in this repo are written `run / fail / err / skip` —
  the first number is **Tests run, including skips**, and reading it as "passed" manufactures a regression.

- 🔴 **AN OPEN IDE SILENTLY CORRUPTS YOUR REACTOR RUN — 36 phantom failures, measured 2026-09-10.** A full
  `mvn -o clean test` failed with **36 errors in `inspecto-engine`**: `NoClassDefFoundError` on *production*
  classes (`com.gamma.pipeline.MappingRules`) and `javac` unable to compile the scaffold templates. Nothing
  in the change under test touched any of it, and **a re-run of the same module minutes later was
  2487/0/0/4 BUILD SUCCESS with zero edits in between.** ⚠ **The tell: the "missing" class file is present
  in `target/classes` AFTER the run.** The cause is an IDE background build writing the same output tree —
  `Get-Process java` showed **IntelliJ's Maven embedder** (`-Didea.maven.embedder.version`) and a **Redhat
  JDT language server**, both live on this checkout; IntelliJ compiles a Maven project straight into
  `target/classes`, so it races surefire. ⛔ Do not report a reactor failure that implicates code you did
  not touch until you have listed the live JVMs and **re-run the failing module**. A red module in this
  checkout is a hypothesis, not a result.

- 🔴 **Four reactor modules log their `Tests run:` SUMMARY at `[WARNING]`, not `[INFO]`** — Maven does that
  whenever a module has any skipped test. So a verify run that greps `^\[INFO\] Tests run:` finds **22
  modules and sums to ~3443** against a real **26 / 4199**, and the shortfall reads like a regression rather
  than a log-parsing bug, sending the reader hunting a build break that does not exist. ⚠ It has a **mirror
  image**: told to count WARNING lines as well, a reader then double-counts. ⇒ Match **both** levels, count
  only lines WITHOUT `-- in` (those are per-class, not per-module), and **assert the module count** — 26
  today — before trusting the total. Re-confirmed 2026-09-10, when a verify agent caught itself mid-report.

- 🔴 **A verify agent's reactor total is a claim, not a measurement.** Two reports in one shift under-summed
  (3430 for a 4178 run) by skipping modules; the third was right only because it was told how to sum. Re-sum the
  per-module `Tests run:` lines — the ones WITHOUT `-- in` — from the log before writing any number down.

- ⚠ **Re-sum a Maven reactor by hand; a naive grep under-reports by hundreds.** `[INFO] Tests run:` alone
  gave **3422** against a true **4176**, because **four module summaries print at `[WARNING]` level** when
  `Skipped > 0`, and per-*class* lines share the prefix with per-*module* ones (summing both gave 7552).
  The module summary is the line with **no** `Time elapsed` and **no** `-- in <class>`, matched across
  `INFO|WARNING|ERROR`.

- ⚠ **A verify agent's build log is overwritten per run, and a no-op build looks like a pass.**
  Launching Maven via `cmd /c` from Git Bash can silently do nothing (MSYS mangles `/c`) — **exit 0
  and an empty log**, indistinguishable from success. Always check the log's **mtime** against your
  run's start time, and prefer `MSYS_NO_PATHCONV=1` / invoking `mvn.cmd` directly.

- **A green suite across a behaviour change means the behaviour is UNPINNED, not that it is right.** On
  2026-08-15 the offline mock's lift was changed to emit a `transform.map` node unconditionally, altering
  the graph topology of *every* pipeline without an authored projection — and the full UI suite passed with
  a **zero delta**, 2433/5 before and after. Nothing anywhere asserted the node's existence, which is
  exactly why the drift had survived since `processing.map` shipped. ⛔ When a change alters observable
  behaviour and no test notices, **stop and add the guard** rather than banking the green run; then falsify
  it (restore the old behaviour and watch it fail). The same shift's `record()` transaction and
  `bind-kinds` contract were both confirmed this way. Related: the backlog row for the drift had itself
  warned that "the node-count assertions must be updated deliberately" — **there were none**.

- **A profile-scoped module is invisible to the verify loop that everyone actually runs.**
  `inspecto-oidc` / `inspecto-policy` live in the parent's *profile-scoped* `<modules>`
  (`-Pedition-standard` / `-Pedition-advanced`), not the default list — so `mvn -o clean test`
  reports **BUILD SUCCESS while both edition builds are broken**. The 2026-08-10 artifactId rename
  proved it: the two poms still declared `file-processor-parent`, which is a *non-resolvable parent*,
  and nothing caught it because the routine loop never loads them. **Any change to the parent pom, a
  shared artifactId, or a managed dependency must be verified with
  `mvn -o clean test -Pedition-advanced`** — that profile is the only one that pulls every module in.
  Grepping `*/pom.xml` for the old name finds these two instantly; the reactor never will.

- **Surefire `-Dtest=A,B` wants commas.** The `+` form matches nothing. On its own it at least fails with
  "No tests matching pattern … were executed", which reads like a missing class rather than a bad separator —
  but ⛔ **combined with `-Dsurefire.failIfNoSpecifiedTests=false` it reports BUILD SUCCESS having run
  nothing at all**, and those two flags are habitually passed together. Observed again 2026-08-15 on
  `-Dtest=ControlApiSystemRoutesTest+OperationalDbTest`: green build, no `Tests run:` line anywhere.
  **A pass is only a pass if a `Tests run:` line appeared for each class you named** — otherwise run them
  as separate invocations.
  ⚠ **The same trap fires on a class name that simply DOES NOT EXIST** (2026-09-13): `-Dtest=` named
  `RemoteAcquisitionHandlerTest`, which is not a class in this tree — the real one is
  `RemoteAcquisitionStagingTest` — and because a SIBLING name in the same list did match, surefire ran the
  sibling and swallowed the miss entirely. BUILD SUCCESS, no "Running…" line for the absent class, and the
  totals looked plausible. 🔴 **Confirm each class BY NAME in `target/surefire-reports/TEST-<fqcn>.xml`**;
  arithmetic on a total ("16 presumably includes the 4 new ones") is not confirmation.

- **Ask a verify agent for the MODULE COUNT, not just the total.** Two `-Pedition-advanced -fae` runs on
  the same tree reported 3355 and 3458 (2026-08-18); nothing had changed but the summariser, which dropped a
  module. The known cause is Maven logging `Tests run:` at `[WARNING]` for any module with skips, so a grep
  filtered on `[INFO]` silently loses whole modules — but the *total alone cannot reveal that*, because a
  plausible number looks like a real one. **19 test-reporting modules** is the current shape of a full
  enterprise run; a total that moves without a module count is not evidence either way.

- **Moving a private field breaks the tests that reflect on it — grep `getDeclaredField` first.**
  `CollectorServicePipelineForgetTest` read `PipelineScheduler.lastRunAtMs` reflectively; the B2 move
  required re-pointing it at `PipelineRunGuard` *and* switching its driver from `runAllOnce()` to
  `runPipeline(id)`, because its no-`trigger:` fixture is exactly the `DEFAULT_POLL` case the new
  elision stops stamping. A reflective test fails at RUN time with `NoSuchFieldException`, not at
  compile time, so the compiler will not warn you.

- **🔴 CAUSE FOUND 2026-09-12 (correcting the note below): "transient" `NoClassDefFoundError` in an
  untouched module is CONCURRENT MAVEN RUNS IN ONE TREE — it is self-inflicted, not flaky.** It happened
  **four times** in one shift (`asn-golden` ×2, `inspecto-etl`, plus one ambiguous run) and the pattern was
  identical every time: a **background** gauntlet still running while **foreground** targeted/mutation
  builds were fired in the same checkout. The sibling's `clean` wipes `target/classes` mid-run, so a class
  that is *demonstrably on disk when you look* was absent when the other JVM's surefire loaded it.
  ⛔ **The tell: `NoClassDefFoundError` for a class you can `ls`.** ⚠ A stale JVM is NOT the cause — that
  was this note's first hypothesis and it was wrong. ⛔ **One build at a time in this tree, full stop**;
  before launching a gauntlet, confirm no other build is live, and never fire a targeted run "just
  quickly" while one is in flight. 🔴 The expensive part is not the lost build — it is that a clobbered run
  looks exactly like a real regression in code you never touched, and costs a diagnosis every time.

- **🔴 A REAL flake did exist, and it was an ASYNC BOOT TASK racing the test body — diagnose by making
  the race LOSE, not by re-running.** `ControlApiProblemFilesTest.limitBounds…` was intermittent on the
  `inspecto` module's only gate (measured 2026-09-17: 1 of 3 full-module runs red at an **unchanged** tree,
  green 8/8 alone). Cause: the status read surface is a DB **projection**, refreshed only at boot and at the
  end of a poll cycle — and `CollectorService.start()` schedules the first cycle with **initial delay 0**, so
  that cycle's sync ran concurrently with the test, which seeded its ledger *after* boot. Win the race, green;
  lose it, the route honestly returns 0 rows. ⚠ **The `@TempDir`/shared-state/mtime hypotheses were all wrong**
  — every test had its own temp root. ⇒ **The technique that settled it in one run: insert a `Thread.sleep`
  to force the losing order**, which turned a 1-in-3 flake into a deterministic, byte-identical failure; the
  same probe passing afterwards is then real proof the race is gone. ⚠ **A retry/poll helper was not a fix but
  a FALSE one** — after the boot cycle there is no second sync inside any sane deadline, so `awaitTotal`'s
  10s loop could never recover the miss; it only widened the window it was papering over. Fix + the
  seed-before-boot rule: `e5e4ee8f`; mechanism in `okf/product/layer/platform/admin-guide-operations-reference.md`
  § "Status backend".

- **⚠ A full reactor build can fail TRANSIENTLY in an untouched module on Windows — re-run before
  believing it.** *(Kept for the diagnosis technique; the CAUSE is the entry above, not staleness.)* Twice on 2026-09-12 `mvn -o clean test -Pedition-advanced` died in the `asn-*` subtree
  on sources nobody had edited: once as `asn-golden` test-compile errors (*"package com.gamma.asn.schema
  does not exist"*), once as `asn-schema` failing at test execution with `NoClassDefFoundError` for **its
  own just-compiled classes**. Both vanished on a clean re-run that then went fully green. The signature
  is a **missing-symbol or missing-class failure in a module your change does not touch**, and it looks
  exactly like a real regression. 🔴 Before chasing it: confirm the module's sources are unmodified
  (`git status`), check the installed jar actually contains the "missing" symbol (`jar tf`), and look for
  a **stale `java.exe`** — a leftover JVM holding file locks is the likeliest cause on this platform.
  ⛔ Do not "fix" the untouched module, and do not commit over a red build without establishing which of
  the two it is.

- **⚠ `-pl` on the `asn-*` subtree SILENTLY DROPS `-Pedition-advanced`** — Maven warns *"The requested
  profile could not be activated because it does not exist"* and carries on with the default profile set,
  because the profile is declared at the Inspecto root, not in that subtree's poms. So a narrow `-pl`
  re-run **does not exercise the same build as the gauntlet** and cannot, on its own, clear a failure the
  full build produced. Use it to gather evidence, never as the proof. (Sibling of the known
  `mvn package` w/o a profile SKIPS the edition modules trap.)

- **⚠ Counting a reactor's modules by its dot-leader summary lines UNDER-reports.** A regex keyed on
  `\.{5,}` matched 12 of 32 on a full green build — which, against the "fewer than 26 = clobbered partial"
  rule, reads as a catastrophic partial. Count `^\[INFO\] Building ` lines instead, and treat a surprising
  module count as a suspect *regex* before a suspect *build*.

- 🔴 **A COMPILE IS NOT A VERIFICATION, and a reactor halt reads as a pass.**
  A lane reported "core shipped" on a clean `test-compile`; the first real run was **5 errors**. Separately,
  a red in an UPSTREAM module **halts the reactor**, leaving downstream modules `SKIPPED` — and under
  `mvn -q` that reads as a clean run. One agent came within a sentence of certifying tests that never
  executed. ⇒ **Demand per-module `Tests run` totals and treat a SKIPPED module as a NON-VERDICT.**
  ⚠ `-DfailIfNoTests=false` is NOT the flag — `-Dsurefire.failIfNoSpecifiedTests=false` is, and the wrong
  one turns every upstream module into a reactor-halting red. ⚠ `mvn -q` can also emit an EMPTY log that
  reads as success; do not use `-q` to verify.

### A shared seam needs the MODULE suite — `-Dtest=` hides the failure in the direction that matters (2026-09-22/23)

Twice in one shift a change passed its own targeted test class and broke the suite:

- a new control-plane TEST booted one `ControlApi` per fixture — green alone, **37 other control-plane
  tests** then failed `HTTP/1.1 header parser received no bytes` in the same surefire fork;
- a PRODUCT change to `ConfigWriteRoutes` (nest a created Pipeline under `config/<id>/`) passed
  `-Dtest=ControlApiPipelineCrudTest` 20/20, **was pushed**, and broke 23 tests — an unregistered config
  is resolved at the write ROOT, so nesting it made read/patch/delete answer 404. `origin/master` was red
  until the revert.

⚠ The per-change unit-test rule (run the affected classes, move on) is right for a leaf change and
**wrong for a shared seam** — a write path, a route table, a scratch-resource pattern. Green-alone /
red-in-suite is invisible exactly where it costs most: the change looks verified.

🔴 Writing the lesson down does not make it apply itself. The first instance was documented in
`okf/product/layer/platform/tests-guard-coverage.md` hours before the second one shipped.

⚠ **The sibling rule, from the same programme: a DECISION's premise is a hypothesis too.** `D3` was
signed on *“every shipped Pipeline lives in a per-Pipeline directory”*, implemented twice and reverted
twice before anyone counted: `config/orders/` holds THREE Pipelines, and the directory names are domain
words, not ids. **“Every shipped X does Y” is a claim to COUNT before a decision rests on it** — it is
one command, and it would have saved a signing and two implementations.
