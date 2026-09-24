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
   ⚠ **Parsing the other side's source is the sanctioned idiom for a cross-language pin.** There is no
   shared artifact to compare against, and inventing one for two short lists costs more than it saves —
   so `MapNodeKeyContractTest` parsed `pipeline-editable.ts` directly, the same way it already did for
   `RowShaper` (⛔ the mirror itself was deleted 2026-09-24 with the rest of the dead TS lift/lower, `STEP-TYPES-DEAD-CLIENT-MIRRORS-1`, and that test method with it). Mutation-verified 2026-09-09: removing `fields` again fails exactly 1 of 4 Java tests
   naming the missing key, and exactly 1 of 74 UI tests (`lowers an authored fields projection instead of
   refusing it`, exit 1).
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

✅ **A third instance, 2026-09-08 — and a MEASUREMENT is the same shape as a guard.** The first
code-coverage baseline reported "repo-wide 82.92%" across 21 modules — the true figure is **81.01%**. Nine more code modules produced no
report at all — not because coverage was low, but because they were **never instrumented**:
`asn-parser/asn-decoders/pom.xml` is a separate root (`com.gamma.asn`) that the inspecto reactor only
**aggregates**, and a Maven profile is inherited through `<parent>`, never through aggregation. So ~13 300
lines across 91 files — about a tenth of the codebase — sat outside a number presented as repo-wide, and
`mvn -Pcoverage` exited 0 the whole time. 🔴 **A reported number tells you nothing about what it declined
to look at.** Had a threshold been set from that figure it would have been a gate that could not fail for
a tenth of the code. Fixed by duplicating the profile into the asn root, with a comment saying why it
cannot be inherited. ⚠ And a second, smaller exemption of the same family: a module with **zero tests**
writes no `jacoco.exec` at all, so it disappears from the denominator instead of dragging the percentage
down. Four modules do that here (~800 lines, immaterial) — but it is why a coverage percentage is never a
measure of *"how much code has tests"*, only of *"how much measured code was executed"*.

✅ **A fourth instance, 2026-09-13 — the vocabulary guard could not reach the file it was supposed to
police.** `tools/check-vocabulary.mjs`'s `SOURCE_RULES` (`:359`, applied at `:724`) held exactly **two**
rules, `flow-identifier` and `flow-message`, both Flow→Pipeline. <!-- vocab-allow: names the rename the two rule ids exist for --> **There was no Collector rule over source
files at all** — the Source→Collector rule is a *prose* rule and never ran over `.java`. ⛔ So the
offending author-facing message was never in the guard's reach, and no amount of tightening the existing
rules would have caught it. The fix was a third rule, `source-key-message`, not a stricter one.
🔴 **Building that rule found two defects reading could not**: (1) inheriting `flow-message`'s
`sentencesOnly` filter **missed the second message entirely**, because a concatenated fragment
(`"source.post_action.on_success=" + kind`) carries no whitespace and was discarded as a contract; and
(2) scanning template literals raw made **3 of the first 4 hits false positives** (`${source.kind}` is
code, not text), so interpolations are now stripped. ⚠ Both were caught by **mutating each message
separately**, not by reading. ⛔ **A guard that passes proves nothing until it has been proven red** —
once per rule, not once per guard.

✅ **A fifth instance, 2026-09-16 — the ALLOW-LIST was the bug, and it had been green over the
customer's first page.** `tools/check-doc-links.mjs` scanned `ROOTS = ['docs', 'compliance', '.claude']`
plus root `*.md`. `inspecto/` was in none of them — and `inspecto/README.md` is the file `package.ps1`
step 7 copies to the **bundle root**, the first page a customer opens. It carried **29 dead links**
(`README-LINKS-BROKEN-IN-REPO-1`) across **13 distinct dead targets**, every one of them a doc the July
consolidation `f6faeae3` *relocated into* `okf/` — link rot created by the doc lifecycle's own
reorganisation, exactly the flavour the guard's own header records for `superpower/`. The guard reported
✓ the whole time, and printed its scope honestly on every run; nobody read the three roots as a list of
what was **not** covered. 🔴 **An allow-list answers "did we remember to add this tree?"; a deny-list
answers "is there a reason to skip this tree?"** — and only the second fails loudly when a fourth
doc-bearing directory appears. `ROOTS` is now `['.']`, the whole repo minus `SKIP_DIRS`
(`node_modules`/`.git`/`worktrees`/`dist`/`target`/`graphify-out`), which also folded away the separate
root-`*.md` pass. ⚠ **Widening cost nothing in noise**: 451 → 529 files, 1,802 checked links, and only
**3** further breaks (`inspecto-ui/README.md` ×2 — one to a plan deleted in `8172de90` with no successor;
`tools/templates/nodetype/README.md`, off by one directory level, the same mistake the header records for
the seven `SKILL.md` links). ⛔ One trap in the widening itself: with `ROOTS = ['.']` every path reads
`./docs/…`, so the `ARCHIVE_PREFIX` `startsWith` test stops matching and the 570-link archive exemption
silently evaporates — `slash()` now strips the `./`. Falsified both ways: RED at 32 on the pre-fix tree,
and RED again from a one-line mutation in `asn-parser/docs/` (a tree the old scope could never see).

## A third shape: the guard whose subject is a NUMBER a human wrote

The two shapes above are about a guard that cannot fail. This one is about work that **has no guard at all
because its subject is prose**, and the prose asserts something arithmetic.

**The instance.** BACKLOG §2 was rewritten on 2026-09-07 so every externally-gated row names a command a
shift can run — the whole point being to replace unfalsifiable gates with checkable ones. Hours later, the
same shift summarised the pass as **"13 of 15 still gated"**. The table had **16** rows, and **4** of them
had not been checked at all. Rows with *no evidence either way* had been summed into a count of gates that
*hold*. Nobody could catch that by reading: the number looked like evidence.

**Why no existing guard could have caught it.** The vocabulary guard reads words, the secrets guard reads
literals, the dependency guard reads a resolved graph. None of them reads a claim about a document's own
contents. And the claim was in the one place a reader trusts most — the section header.

**The fix, and the rule it generalises.** `tools/check-gate-tally.mjs` makes the section state its own
arithmetic in a fixed form and refuses a build where the sentence disagrees with the rows: it counts table
rows, counts `NOT RUN` markers, and requires run + not-run to account for every row exactly once. It
caught its own section going stale within minutes of being written.

> **When a document asserts a count about itself, that count is testable — so test it.** A tally in prose
> is a claim with no owner. Give the document a machine-readable shape for the claim, and a guard that
> re-derives it from the rows. ⛔ Fix the SENTENCE to match the rows, never the rows to match the sentence.

⚠ Two traps met while building it, both instances of the rules above:

* **The emptiness floor is not optional here either.** A parser that stopped matching the table would
  report `0 rows` and a tally over nothing would pass. `MIN_ROWS = 12`.
* 🔴 **`node guard.mjs | tail -3` reports `tail`'s exit code.** The first falsification run printed the
  right error message for all four probes and `exit=0` every time. The messages proved detection; they
  proved nothing about the exit code, which is the only thing CI reads. Re-run without the pipe.

⚠ And one from the same shift, in the check the guard's own subject names: `grep -c "NFR-7 ·"` over
`compliance/controls-matrix.md` returns **9** for **7** rows, because the sentence documenting the check
matches the check's own pattern. **Anchor a stated check (`^| NFR-7 ·`) or it counts its own
documentation.**

### The board's other self-claim: a row HEAD (`tools/check-board-heads.mjs`, 2026-09-25)

A ranked row's headline is also a claim about the row, and it rots the same way: on 2026-09-19 six of
nine rows a shift grounded were already shipped, blocked or duplicates (`BOARD-STALE-HEADS-1`). The
board-heads guard refuses `docs/BACKLOG.md` when **(a)** a row id heads a struck row *and* a live
ranked row, **(b)** a ranked row in §3–§5 carries a closed marker in its *head clause* (head + headline,
never the body), or **(c)** an id heads two ranked rows. Wired in `ci.yml` and `.githooks/pre-push`
beside the gate-tally guard; `node tools/check-board-heads.mjs [file]` takes an optional path so a
planted copy can be checked without touching the board.

* **The markers are deliberately narrow** — uppercase `CLOSED` (except `fail CLOSED`), `✅` directly
  followed by SHIPPED/CLOSED/DONE, `had`/`already shipped|closed`, a `**SHIPPED`/`**DONE` headline, a
  strike in the head, and a wholly struck ranked row. Lowercase `shipped`, `BUILT` and `decided` are
  *not* markers: open rows on today's board use them for the parts that shipped around a residual
  (`✅ the comparison SHIPPED … (residuals)`), and a guard that matched them would be mostly exemption.
* **The head clause must end at the headline.** The first cut took the first ` — ` anywhere in the row,
  and on a head whose bold name itself contains a dash it swallowed half the body. The separator now has
  to follow the name token directly.
* **Floors from a measurement:** 37 ranked rows, 13 with an id head on 2026-09-25; `MIN_RANKED = 20`,
  `MIN_IDS = 8`. Falsified on planted copies: every (a)/(b)/(c) shape, the floor and a renumbered board
  exited 1; `fail CLOSED` in a headline stayed green.

## Instance, 2026-09-12: a number with NINE mirrors, missed by hand twice in two shifts

`tools/check-family-count.mjs` is the third-shape guard (§"a NUMBER a human wrote") applied to a count
that is **derived**, not authored: the size of `OperationalDb.Family`.

The roster's size is restated in **nine** places outside the enum — two test tripwires plus seven
sentences across `db-layer.md`, `data-plane.md` and the scale-out plan. Adding a family therefore means a
nine-file sweep, and on **2026-09-12 it was missed twice in one day**: `EVENTS` (D6) took the roster
12 → 13, `RUN_LEASE` (phase B1) took it 13 → 14, and each time the two test tripwires caught it — but
only after a **~14-minute reactor run**, and neither says anything about the prose.

**The lesson, and it generalises past this enum.** The tripwires were not missing and were not weak; they
worked both times. What was wrong was the *cost of learning* and the *coverage*: a fact restated in nine
places cannot be maintained by discipline, and a failure that takes fourteen minutes to surface will be
discovered late by whoever is least expecting it. ⇒ **When a value is derived from code and restated in
prose more than once or twice, the restatements need a guard, not a convention.**

⚠ Two shape details worth copying:
* The guard reads the **enum** and treats every statement as the thing that must agree — never the
  reverse. Its failure message says so, because the tempting fix under time pressure is to edit the enum
  count in a test.
* It deliberately does **not** check that a new family is *correct* (honours the shared URL, has a
  `SpaceRoot` accessor, is reported by `/system/db`). `OperationalDbTest`'s loop and
  `ControlApiSystemRoutesTest` own those and must stay — a guard that appears to cover a neighbouring
  invariant is how the real one gets deleted.
* ⚠ It skips `inspecto-deploy/`, which is gitignored build output carrying stale copies of the same docs.
  Failing on a generated artifact teaches the next shift to ignore the guard.

Falsified in both directions before wiring: drifted prose and a drifted tripwire each fail with the file,
line and both numbers named.

### 2026-09-16 — and its pattern was BLIND to the emphasised statements

🔴 **It went GREEN for four days on `db-layer.md` saying fourteen on one line and fifteen on another, in
one file.** The pattern was `\b([a-z]+)(?:\s+|-)famil(?:y|ies)\b`: it required the number word to touch
`famil…` directly, so **`the **fourteen** families` matched nothing** — the `**` sit between them. The
fix is two `\**`, and it is the whole change.

⛔ **The general shape: a prose guard that skips MARKDOWN EMPHASIS skips the sentences its authors were
most deliberate about.** Bold is what a writer reaches for on the load-bearing number, so the blind spot
is anti-correlated with the statements that matter.

⚠ **Widening it immediately found a second drifted file** (`data-plane.md`) plus a stale coverage claim
riding along with the count — *"leaving only the acquisition ledger uncovered"*, false since 2026-09-12.
⇒ **When a guard's reach grows, re-run it before assuming the one case you were chasing is the only one.**

🔴 **The measurement that "confirmed" the stale claim was itself blind, in the same shift.** Counting
`Db*.open(` call sites in `PostgresStateStoreTest` returns fourteen and silently drops `DbDedupLedger`,
which is built with `new DbDedupLedger(conn)`. ⛔ Two construction idioms, one probe — the absence it
reports is an artifact of the probe. Grep the **type name**, then explain each hit; do not grep one call
shape and count.

## Instance, 2026-09-07: a guard scoped to one directory

`CapabilityManifestTest` finds `withCapability(` registration sites by regex-scanning **one directory**,
`src/main/java/com/gamma/control`. That was the whole world while `RouteModule` was package-private — nothing
else *could* register a route. The moment the SPI went public (EDG-01 cell 3a), the scan's scope became
narrower than the thing it guards: a gated route in an optional module would have registered at runtime and
been invisible to the drift check, both directions. **A guard whose scope is narrower than its subject is an
exemption nobody wrote down.** Widened to every module's `src/main/java` in the same commit that opened the
seam — the two must move together, or the seam opens a hole the guard used to cover by accident.

## Instance, 2026-09-08: the guard that could not pass AT ALL

The inverse of Shape 1. `tools/check-coverage.mjs` takes `--backend` / `--ui` so each CI job enforces
the half it actually builds — `ci.yml` builds Java and never the UI, `ui.yml` the reverse. But the
backend's must-exist check was written as a bare `if (csvs.length === 0) { process.exit(1) }`, with no
reference to the `wantBackend` flag computed ten lines above it. So `check-coverage.mjs --ui` died
demanding `jacoco.csv` before it ever reached the UI block — in the one job that by design never
produces one. The "Coverage floors (UI)" step was red from `b4bdc6d6` until `--ui` was gated properly.

Three things make this worth recording:

- **The intent was documented in two places and the code contradicted both.** The comment beside the
  `ui.yml` step says it "reports the backend as skipped here … rather than presenting half the picture
  as all of it", and the script's own UI arm says the mirror-image thing. Prose describing the
  behaviour is not the behaviour; only the exit code is.
- **A dedicated error path was unreachable.** The `--ui`-requested-but-missing branch could never fire,
  because the backend exit always won first. An error message nobody can reach is dead code that reads
  like coverage.
- **The failure was LOUD, not silent** — the step went red immediately. Shape 1 and Shape 2 are guards
  that pass when they should fail; this one failed when it should pass, which is safer but still
  worthless: a gate that cannot go green teaches people to ignore it, and it hid the real UI numbers
  (74.18% statements / 70.24% branches, both comfortably over their floors) behind an infrastructure
  error the whole time.

**Falsify every ENTRY POINT, not just every outcome.** The original was described as "falsified in all
four directions"; the flag combinations are what actually needed enumerating. The fix was re-checked
across seven: `--ui` with and without UI data, `--backend` with and without jacoco, `--backend` with
jacoco above and below the floors, and no-arg with only one half present and with neither.

## A fourth shape: the guard whose SCAN ROOT includes what the repository is not

Shapes 1-3 are about a guard's *rules*. This one is about the **set of files it looks at**, which is
just as load-bearing and is almost never reviewed. A generator that walks the tree to derive its
subject is only as correct as its skip list — and a skip list is written once, against the directories
that existed that day.

`tools/route-gating-report.mjs` derives the mutating-route inventory in
`compliance/evidence/route-gating.md` by walking for `.java` files, skipping `target`, `node_modules`
and `.git`. It did **not** skip `.claude/`. This sandbox keeps agent worktrees there — seventeen of
them on 2026-09-16, each a full second copy of the source — so on any machine with one checked out the
scan saw every route twice and wrote the citations pointing at the copy. **63 of the 172 rows in that
document cited `.claude/worktrees/agent-<id>/inspecto/src/main/java/...`**: paths that exist in no
clone, no bundle, and not on the CI runner.

What makes this its own shape rather than an ordinary bug:

- **The guard was working perfectly.** It compared committed output against generated output and they
  matched — *on the machine that generated them*. Both sides were wrong together, which is the one
  disagreement a self-comparing guard structurally cannot report.
- **The output is auditor-facing, and `compliance/controls-matrix.md` cites it as "CI-enforced against
  drift, never hand-typed."** The enforcement was real; what it enforced was environment-dependent.
- **The fix moved no numbers.** Still 172 routes, 109 gated, 63 exempt, 0 undeclared — strip the
  worktree prefix from the 63 old rows and they are byte-identical to the new ones. ⇒ A count-based
  check would never have caught it. Only the *paths* were wrong, and nothing was checking those.

⛔ **When a tool walks the tree to derive its subject, its skip list is part of the contract.** Exclude
scratch and worktree roots (`.claude/`) explicitly, and prefer deriving the file set from
`git ls-files` — what the repository *is* — over `readdirSync`, which reports what a working copy
*happens to contain*.

## Instance, 2026-09-22: a GITIGNORED session note blocked a push (`DOC-GUARDS-SCAN-IGNORED-SOURCES-1`)

The fourth shape again, and this time against the rule this page already states. `git push` on `master`
was refused by the pre-push citation guard:

```
SESSION_STATUS.local.md:20  path does not resolve:  inspecto-event/EventType.java
```

`SESSION_STATUS.local.md` is **untracked and gitignored** (`.gitignore:124`, `*.local.md`) and is
rewritten by a stop hook on every session, so it exists in every working tree and in no checkout — the
same file class as `.claude/sessions/snapshot.md`, which does not exist in a fresh checkout and which
`tools/tracked-paths.mjs`'s own header already names. A peer's in-flight note, about work unrelated to the push, blocked the push.

**The rule had already been applied to half of each guard.** `tracked-paths.mjs` (`LINKGUARD-CASE-1`,
2026-09-14) moved the **target** side to `git ls-files` precisely because "the working tree is not the
artifact". The **subject** side — the set of markdown files the guard reads *from* — still walks the
tree: `check-doc-citations.mjs:227` uses `readdirSync` with a `SKIP_DIRS` deny-list, and nothing in that
list excludes files git would not hand you.

Measured 2026-09-22 by writing a gitignored `PROBE.local.md` at the repo root and running each guard:

| Guard | Reads untracked/ignored markdown as a subject? |
|---|---|
| `check-doc-citations.mjs` | **YES** — named the file, exit 1 (the incident above) |
| `check-doc-links.mjs` | **YES** — named the file, exit 1 |
| `check-doc-counts.mjs` | **YES** — named the file, exit 1 |
| `check-vocabulary.mjs` | no — `git ls-files` only, exit 0 |
| `check-secrets.mjs`, `check-nul-bytes.mjs` | no — tracked files only, by design |

⚠ **The first probe of this was INVALID and reported a clean pass.** It cited
`inspecto-nope/DoesNotExist.java`, whose first segment is not a real directory, so the guard never
treated the token as a repository path at all — every guard exited 0 and the hole looked absent. Only a
probe whose first segment exists reproduces — `inspecto-event/NopeDoesNotExist.java` never existed,
but `inspecto-event/` does, which is the whole difference — and the same
citation in a **tracked** doc failing is what proves the probe valid rather than the guard broken. ⇒ A
negative result from a probe that could not have succeeded is not evidence.

⛔ **Two consequences worth stating.** A guard that reads what the working copy *happens to contain* is
**not reproducible between shifts** — it can be red for one session and green for another on the same
commit, which is the property a gate exists to deny. And it is **unfixable by its own rules**: editing
the offending line does not last, because the hook regenerates the file (verified — the correction made
to unblock this push was gone within the hour).

## Instance, 2026-09-22: a guard that passed ALONE and broke 37 tests in the suite (`WB-01`)

`ControlApiPipelineGraphRoundTripSweepTest` drives every shipped Pipeline through the **real HTTP write
path** — `GET …/graph/raw` → `PUT …/graph` → re-`GET`, asserting the editable graph is IDENTICAL.

⚠ **Why it is not enough that `LiftLowerFixtureSweepTest` exists.** That sweep proves the same corpus
survives the editable seam IN PROCESS. It never boots the control plane, so it cannot see what the write
ROUTE adds: route defaults, `active` coercion, findings-driven rewrites, the spec + safety gate, the
atomic write. Those are the layers a workbench save actually goes through.

🔴 **The guard was GREEN alone and turned 37 OTHER control-plane tests red in the module suite**, all
failing `HTTP/1.1 header parser received no bytes`. It booted one `ControlApi` + `CollectorService` per
fixture — 26 servers and 26 DuckDB dedup ledgers in one surefire fork. It now boots **one** service over
all 26 staged fixtures, which `CollectorService(List<Path>)` supports directly.

⛔ **The rule this buys: a new control-plane test is verified by running the MODULE, never only by
`-Dtest=`.** Green-alone/red-in-suite is invisible to the per-change unit-test rule, and it is invisible
in the direction that matters — the guard looks like it works.

🔴 **The same trap then bit a PRODUCT change in the same shift.** `WB-15` (a created Pipeline lands in
`config/<id>/`) passed its targeted test class 20/20, was pushed, and broke 23 tests: an unregistered
config is resolved at the write ROOT, so nesting a created file left read, patch and delete answering
404. Reverted. **A change to a SHARED seam needs the module suite whether it is test code or product
code** — writing the lesson down for the harness did not make it apply itself to the next commit.

✅ **Falsification-probed, which is what makes it a guard rather than a green tick:** its pinned-refusal
map starts empty, and removing a pin (or adding a fixture that refuses) turns it red. When `WB-03` fixed
the arming gate, the pin had to be dropped in the same change — by design, so a fix cannot be absorbed
silently.

## Instance, 2026-09-16: three unrelated defects behind ONE red gate

`master` CI was red all day — at least six runs from 08:33 onward — and every one read as the same
known failure. It was not one failure. It was three, stacked, each invisible until the one above it
was cleared:

1. `ci.yml:64` — the Step Processors board was stale against its contract (a shipped feature the board
   had not caught up with). Cheap guard, trivial fix.
2. `ci.yml:163` — the route-gating evidence carried the 63 phantom citations above. **Never ran** while
   step 1 was red.
3. `ci.yml:291` — `Run tests (all editions)`. **The reactor had not executed a single test all day.**
   When it finally did, it surfaced a real failure: `PipelineDocumentXlsxTest.writesARealWorkbook`
   erroring because DuckDB's `excel` extension cannot load on a clean runner — which in turn falsified
   the premise `D-8` had been closed on the day before.

The mechanism is ordinary GitHub Actions semantics — a job stops at its first failing step — and that
is exactly why it is dangerous. **The guards are ordered cheapest-first, so the cheapest possible
failure buys silence for everything behind it**, including the expensive signal the pipeline exists to
produce. A stale generated table, which nobody would rank above P3, suppressed the entire test suite.

⛔ **Do not read "CI is red" as "CI has one problem."** The only honest reading of a red gate is *"the
first failing step failed, and everything after it is UNKNOWN"* — and "unknown" gets wider the earlier
the step sits. ✅ **FIXED 2026-09-17: the fifteen pure-Node guards now run as their own `guards` job in
`ci.yml`, a sibling of `test`, so a red guard and the reactor report on the same push.** The note that
follows is kept as the record of why it was owed a decision. ⚠ Was recorded rather than fixed: making the doc guards non-aborting (collect all, fail at
the end) or moving them *after* the test step would have surfaced all three in one run instead of three.
That is a real change to `ci.yml` job structure and is owed a decision, not a drive-by edit.
