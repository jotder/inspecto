---
name: falsify-negative-tests
description: "A refusal test proves nothing until the guard is removed and THAT test fails for the expected reason; how to mutate correctly, and what to do when the auto-mode classifier refuses the mutation"
metadata:
  type: feedback
---

Standing rule for security/concurrency code: falsify before believing a negative.

- **The probe must be one that would SUCCEED without the guard.** `read_csv('/etc/passwd')` "passed" on Windows
  because the file does not exist; a temp file the test creates showed the real hole. Compare failure MESSAGES,
  not the pass/fail bit; prefer narrow `assertThrows` types.
- **Another predicate may be doing the blocking** (different owner ids masked a broken epoch fence). Make the
  discriminating case (same owner) explicit.
- **Mutate only the semantics under test**: keep bindings/compilation intact (`AND (epoch = ? OR 1=1)`). A mutant
  that fails to compile, or a half-mutant of coupled clauses (UNIQUE <-> ON CONFLICT), goes red for the wrong
  reason. Best: revert the WHOLE feature to the old behaviour and check the failing values match what old code
  produces.
- **Scope the probe at the shared chokepoint** every caller uses; if one test stays green, suspect the probe's reach.
- Probe by editing source (`// PROBE - REVERT`), never `-DargLine`; grep it gone before committing.
  `git checkout -- <file>` reverts to HEAD and discards other real edits — copy the file aside instead.
- **Widened guard: falsify three ways** — red on planted, green on clean, and the planted input against the OLD
  guard passes (proves the widening is load-bearing).
- **A test through a MUTABLE SEAM proves the seam, not the production default.** 2026-10-25: a clock test that read a
  resettable static passed with the production default swapped to the wall clock, because earlier tests' `@AfterEach` had
  already restored the monotonic one (green in class order, red only when the method ran alone). Assert the default
  through a constant no test reassigns, or in a fresh classloader, and RUN THE MUTANT ON THE WHOLE CLASS, not one method.
  The same round also found `clockMs = DEFAULT_CLOCK` itself unproven: production reads the seam, not the constant.
- **A cache-key component duplicated by another component cannot be turned red** (the user in the key vs the access
  check; the masking mode vs the masking key). Either show where the duplicate lives and that no scenario escapes it
  (change the install default / active pack at runtime and re-read, both directions) or add the test that makes the
  component load-bearing alone. A verifier that drops each key part one at a time finds these in one pass.
- A test that cannot isolate what it names should be deleted with the gap recorded.
- **Auto-mode classifier refuses edits that weaken a guard** (even temporarily). Do not route around it: list the
  exact one-line mutation and the tests that must go red in `SESSION_STATUS.local.md` for the operator to run.

Related: [[maven-verdict-traps]], [[guard-scope-is-a-silent-exemption]].
