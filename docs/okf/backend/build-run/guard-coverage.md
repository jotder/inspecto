---
type: Practice
title: Guards that cannot fail
description: The repo's enforcement surface — which guards are wired, the two shapes of guard that silently cannot fail, and the rules for writing one that can.
resource: tools/ · .github/workflows/ · .githooks/pre-push
tags: [ci, guards, testing, verification, packaging]
timestamp: 2026-09-07T00:00:00Z
---

# Guards that cannot fail

Swept 2026-09-07 after three separate findings in one shift turned out to be the same defect wearing
different clothes. This concept records **the shapes**, so the next one is recognised rather than
rediscovered. The per-item open work is `BACKLOG.md`; this file is the rule.

## The two shapes

**Shape 1 — the guard that is never reached.** It exists, it is correct, and nothing invokes it.
- `render-processor-board.mjs --check` advertised a "CI-friendly" mode for months that no workflow ran;
  the generated board had already drifted (fixed 2026-09-07).
- `branch-policy.yml`'s Conventional-Commits job is gated `if: github.event_name == 'pull_request'`,
  while `CLAUDE.md` says work lands directly on `master` with no PRs. Zero PRs ⇒ it has never run.
- The two guards positioned **after** `mvn test` in `ci.yml` — lean-core and dependency-lock — do not run
  while the test step fails, and it has failed on every retrievable run.

**Shape 2 — the test whose subject is its own module.** Coverage that is structurally incapable of
indicting the real risk, because the test supplies what production must supply.
- Every connector test lives inside `inspecto-connectors`, where the classes and their
  `META-INF/services` file are trivially on the test classpath. They passed for 85 days while the module
  reached no deployment at all (CONNECTORS-BUNDLE-1).
- `inspecto-security`'s tests passed because Nimbus is a compile dependency **there**, while the shipped
  16 KB jar carried none of it and every Standard/Enterprise bundle failed to boot; the core-side auth
  tests passed because they inject a lambda through `Authenticators.forTest`. Both halves green, the join
  untested. ✅ Fixed 2026-09-07 the same way as the connectors: a shaded `-sidecar` artifact with the core
  `provided`, plus a staged-artifact check in `package.ps1` asserting `com/nimbusds` and all three SPI
  registrations are present.

⚠ **The generalisation:** an SPI verified with an ambient-classpath `ServiceLoader.load(X.class)` *inside
the module that provides X* proves the class works, never that it is reachable. The one test in the
reactor that gets this right is `ScaffoldTemplatesTest` — it builds a real jar and loads it through a
separate `URLClassLoader`, filtering to providers that actually came from it. Copy that.

## Writing a guard that can fail

1. **Give it an emptiness floor.** A scan that matches nothing must fail, not pass. `check-dependencies.mjs`
   and `sbom.mjs` exit 2 on a zero-result resolve; `PipelineKeyCoverageContractTest` asserts
   `read.size() >= 35` with the comment *"a scan that silently matches nothing passes every assertion
   above"*. ✅ `check-secrets.mjs` gained one 2026-09-07 (`MIN_SCANNED_FILES`) — it had none while being
   the one guard that exists *because* secrets already leaked, and it degrades to a filesystem walk when
   `git ls-files` throws, so a bad root printed the same green tick as a clean repo. ⚠ Set the floor
   **from a measurement** (3366 files scanned ⇒ floor 1000), not from a round number far below it — see
   rule 6.
2. **Ratchet both ways.** An allowlist entry that stops suppressing anything is debt already paid, and a
   stale exemption silently forgives the next regression. `check-vocabulary.mjs` does this for all three
   of its file allowlists (`DOC_ALLOW`/`CONFIG_ALLOW`/`SOURCE_ALLOW`) and `PipelineKeyCoverageContractTest`
   for `UNDECLARED_BLOCKS`. Those are the only four in the repo; every other exemption surface is a
   one-way carve-out, including the inline `vocab-allow` / `ds-allow` / `secret-allow` markers.
3. **Anchor a source scan and assert the anchor.** `MapNodeKeyContractTest` asserts its scanned region
   was actually found before trusting the scan — *"the map-path region moved or was renamed — re-anchor
   this scan before trusting it"*. Without that, a moved region silently scans nothing.
4. **Prefer an assertion to an assumption — and where a skip is genuinely right, make it VISIBLE.**
   `assumeTrue` on corpus presence disarms the guard silently if a fixture path moves.
   `MappingMigrationTest` gets it right: *"the corpus must not be empty — this test would prove nothing"*.
   ⚠ When a test legitimately needs something the environment may not have (an external server, a native
   binary), put the assumption in **`@BeforeEach`, never `@BeforeAll`**: an aborted container reports
   `Tests run: 0, Skipped: 0` and the class vanishes from the totals, whereas per-test it reports
   `Skipped: N` *with the reason*. `PostgresStateStoreTest` is the reference — 11 skipped, each naming the
   property that turns it back on. Absent coverage you can see is a decision; absent coverage you cannot
   is the disease.
5. **Check the STAGED artifact — and where the risk is "does it work", RUN it.** `package.ps1` verifies
   the staged connector sidecar (8 factories, sshj, javax.mail) and the security sidecar (Nimbus + 3 SPI
   registrations); those are the only checks in the repo that inspect a packaged artifact. But
   SEC-SIDECAR-BOOT-1 proved inspection is not enough: every jar was *present* and the bundle still could
   not boot, because `ControlApi` resolves the Authenticator SPI at startup. So packaging now also
   **launches the bundle it just staged and waits for `/health`** — the one check that exercises the
   assembled classpath as a running process. ⚠ Assert the artifact's PROPERTIES for what you can enumerate;
   RUN it for what you cannot.
6. **A floor far below the actual is not a ratchet.** `addable >= 10` against 35 actual leaves 25 palette
   entries of silent headroom; asserted twice, Java and TS.

## Triggers that cannot fire

The same disease in prose. A standing refusal names a reopen trigger; if nothing can produce the
evidence, the refusal is permanent by accident rather than by decision. Every unfalsifiable trigger in
`BACKLOG.md` §6 is one of two shapes:

* **A measurement nobody takes** — D7 ("shows up in measured startup time"; nothing times startup),
  C6 connection-open, unpack re-ingest cost, bundle size, revalidation storm, fetch-lane wait.
  ⚠ The instruments are nearer than the rows suggest: `MetricRegistry` already publishes ~50 series, and
  four `-Dbench.run`-gated JUnit benchmarks establish the harness idiom.
* **An external event with no in-repo landing place** — a client policy requiring Vault, a multi-operator
  install, geometry demand, a named prospect. Uniform cheap fix: **name the file where the event gets
  recorded**, and the trigger becomes a file check.

⛔ Do not write a trigger you cannot check from this checkout. If the condition is genuinely external,
the trigger is *"when X is recorded in \<file\>"*, never *"when X happens"*.

## Scope exemptions are silent by default

A guard's SCOPE exempts more than its allowlist does, and nothing announces it:
`check-design-tokens.mjs` scans two roots, so `src/app/layout/**` — real inspecto-authored components —
can hardcode colours freely. ✅ `check-dependencies.mjs` resolved without an edition profile until
2026-09-07, so `inspecto-security`'s Nimbus tree — the one dependency tree a security reviewer most wants
under review — was the only one the lock never saw, while `compliance/controls-matrix.md` marked G7
CLOSED. It now resolves `-Pedition-enterprise` (25 modules, not 23). Audit a guard's scope apart from its
rules: the scope is where the silent exemptions live.
