---
type: Reference
title: Guards, claims and board rows - gotchas
description: Why guards fail in the direction of passing, how to falsify them, and why board rows, plans and findings are hypotheses.
tags: [guards, falsification, backlog, claims, gotchas]
timestamp: 2026-11-19T00:00:00Z
item: layer.platform
audience: internal
status: current
verified: 2026-11-19 last-edit
---

# Guards, claims and board rows - gotchas

Moved verbatim from the retired `PROJECT_NOTES.md` (D-IR5, 2026-11-19); the archived copy is `docs/archived-documents/project-notes-2026-11-19.md`. Each entry is cause, then rule.

- ✅ **The doc guards no longer read UNTRACKED markdown** (2026-09-22, `DOC-GUARDS-SCAN-IGNORED-SOURCES-1`
  closed). All three — `check-doc-citations.mjs`, `check-doc-links.mjs`, `check-doc-counts.mjs` — now take
  their SUBJECT set from `trackedPaths()`. Before this, another session's gitignored `*.local.md` could
  refuse your push: a gate red for one shift and green for another on the same commit, which is the
  property a gate exists to deny, and unfixable by its own rules because a stop hook rewrites the file.
  ⚠ The cost, stated: **a new doc is unchecked until `git add`ed.**

- ⚠ **`git checkout -- <file>` reverts to HEAD, not to your pre-experiment state** (2026-09-17). Falsifying a
  guard by planting a bad value and then "undoing" it that way **discarded a lane's real change** to the same
  file. Copy the file aside first, or restore the intended hunk from the patch afterwards. Same family as the
  standing "unstage, never delete" rule for a peer's work.

- ⚠ **`docs/BACKLOG.md` keeps a CLOSED row's original prose as an indented child bullet** under its own
  strikethrough header (the "Original row follows" convention). So **grepping a row ID lands on open-sounding
  text first**, and two lanes were tasked from already-shipped rows in one shift. ⇒ Assign work only from a
  **top-level, unstruck** bullet: `grep -nE '^- \*\*P[123]\*\*' docs/BACKLOG.md`.

- ⚠ **A row's enumerated call-site list is a hypothesis, and it understates as often as it overstates**
  (2026-09-17, four instances). `SQLIDENT-NINE-COPIES-1` named nine copies: there were **16** across five
  modules, four of them *inline* with no helper method, and **two of the nine were the previous fix**, not the
  defect. A job-param census globbed `*_job.toon` and structurally missed `*_job_template.toon`. One row named
  one `!Files.isDirectory` site where four existed; another named four demo schemas where the whole
  25-file corpus was affected. ⇒ **Grep the pattern, then re-derive the count.**

- ⚠ **A row's prescribed FIX can be unbuildable, and the reason is usually the module graph** (2026-09-17).
  `SQLIDENT`'s prescribed home (`inspecto-sql`) would have been a **cycle** — it depends on `inspecto-util`,
  where two of the copies lived. `PACK-SPI`'s prescribed `OptionalSpi` reuse is unreachable from
  `inspecto-engine`, because `inspecto-processor` depends on the engine, not the other way round. ⇒ Check the
  dependency direction before adopting a row's remedy.

- 🔴 **A GUARD FAILS IN THE DIRECTION OF PASSING — five instances in two days** (2026-09-16/17). Each was
  green while blind: a prose regex that required the number word to touch its noun, so a **bolded** count
  matched nothing; surefire reports surviving `mvn clean` (which only cleans modules the reactor REACHES),
  so a halted build summed a previous run's greens; an allow-list scope (`ROOTS`) that never scanned
  `inspecto/`, hiding a broken customer-facing README for months; a deny-list validated on a tree that
  lacked the thing it should deny (the packaged bundle output), so it was green for its author and
  2417-red on a checkout that had ever built one; and a shipped `probe()` written as
  `curl -fsS … || echo "(request failed)"` — **a probe that cannot fail**. ⇒ **Falsify every guard BOTH
  ways before trusting it**, and prove the probe returns a hit on a positive control. A guard you have
  only ever seen pass is not verified. ⛔ When a guard's reach is WIDENED, re-run it before assuming the
  case you were chasing is the only one — every widening this shift found more.

- 🔴 **"THE UPSTREAM SEAM HAS SHIPPED" WAS CHECKED ON THE TYPE, NOT THE SEAM** (2026-09-16). `AGT-5`'s
  external gate was discharged on 2026-09-08 because `DryRunProvider.class` is in the pinned eoiagent jar. It
  is — but `javap -public` on `eoiagent-platform`'s `PlatformBuilder` shows no `dryRunProvider(...)`; the only
  setter is on a gate builder the platform constructs internally, so the host can never supply one. A week of
  "actionable" that was not. Before discharging an external gate, check the ENTRY POINT the host would call.

- 🔴 **A GATE THAT HAS NEVER BEEN RUN IS NOT "UNVERIFIED", IT IS UNKNOWN — AND IT MAY BE ONE FLAG FROM RUNNABLE**
  (2026-09-16). The ELT §6 step-2 parity gate ("the whole suite executing through the compiled-recipe path")
  sat for months as prose. Passing `-Dingest.lane` through the root pom to surefire made it
  `mvn -Dingest.lane=graph test`; the first run turned 13 engine tests red for two reasons the lane itself
  names (sink-count mismatch on multi-schema fixtures; a Decision Rule routing rows). Parity does not hold.
  A projection round-trip test (`RecipeConverterTest`) is not an execution gate.

- 🔴 **REACHABILITY OF A FILE IS NOT REACHABILITY OF A METHOD** (2026-09-15). A P1 was filed, reported as
  confirmed, and refuted the same day on this exact gap. The claim: the graph ingest lane writes
  `run_id = null` into `consignment_outputs` and so escapes its UNIQUE key. Everything checked was true —
  the null-supplying line exists (`ConsignmentGraphRunner:82`), and the graph lane **is** reachable on the
  default `-Dingest.lane=auto`. The unchecked question was **which overload the caller uses**: that line
  lives in a two-arg `run` overload with no production caller, while production passes its own
  `IngestSinkWriter` and registers through `ConsignmentIngestor`, which mints a run id.
  ⇒ **Grep the CALL GRAPH, not the line.** "Class X is used in production" and "this constructor in class
  X runs in production" are different claims, and an overloaded method makes them come apart silently.
  ⚠ Same family as the dead-parity-mirror trap: grep for **callers**, not for the symbol's existence.

- 🔴 **AN OUTLIER AMONG SIBLINGS IS NOT EVIDENCE THAT THE OUTLIER IS THE MISTAKE** (2026-09-15). A route
  audit listed `POST /requirements` as gateable because both its siblings carry a capability gate and it
  does not — "an inconsistency, not a judgement call". It is **SEC-7(c)** and deliberate (anyone may raise
  a requirement; only a triager decides), pinned by a test that says so in its name. Gating it turned the
  build red. The same pass listed `POST /spaces`, which is the **recovery route**: deleting the last Space
  leaves a server hosting none, and a gate there bricks it exactly as an earlier `writeRoot()` resolution
  did — every route failing, *including the one that would recover it*.
  ⇒ Asymmetry is evidence that **something** was decided, not of which side was wrong. Read the sibling's
  test names before "fixing" the odd one out. ⚠ Note where the audit's rigour actually was: it reviewed
  its *correctly-ungated* buckets carefully and its *should-be-gated* bucket not at all.

- 🔴 **A ROW KEPT "FOR PROVENANCE" IS INDISTINGUISHABLE FROM AN OPEN ROW** (2026-09-15). A closed P1 sat on
  `BACKLOG.md` under its own closure note, marked *"(original row retained below for provenance)"*, and was
  counted as live work by every reader and every `grep`. ⇒ Closed work leaves the board; provenance is the
  OKF concept's job and git's. ⚠ The board's census had also been carried forward rather than recounted
  and was wrong by 17 rows — **recount on the way out of any shift that files one.**

- 🔴 **CHECK THAT A GUARD'S COMPARANDS CAN EVER BE EQUAL BEFORE WRITING IT.** A proposed one-line self-loop
  guard for the `on: dataset` trigger — compare the signal's `producer` to the pipeline name — **could not
  fire**: `producer` is `cfg.name()` (a JOB name) at `MaterializeTask:132` and `chain.get(i)` (a **processor
  component id**) at `ConsignmentProcessJobType:412`, never a pipeline name. It would have looked correct,
  passed review, and done nothing on the exact case it targeted. ⚠ Same family as *a guard I wrote could not
  fire* (2026-09-13) — the tell is a comparison between two values that were never defined as the same kind.

- 🔴 **A RED GATE HIDES EVERY GATE BEHIND IT.** CI stops at the first failing step, so a single red guard
  makes every later one unobservable. Measured 2026-09-14: `master` was carrying **four independent
  pre-existing failures stacked in one file** — a stale generated table, then five broken doc links, then
  two dead citations, then a genuinely host-dependent Maven test. Each became visible only when the one
  ahead of it went green, and the last two had been invisible for as long as the first was red.
  ⛔ **After fixing a CI gate, re-run rather than declaring victory** — "the build is green now" is a
  claim about one step until the whole pipeline has executed. ⚠ The same shape hides in any ordered
  pipeline: the pre-push hook, `package.ps1`'s staging steps, a reactor that stops at the first module.

- 🔴 **`existsSync` answers "is this on THIS disk", never "is this in the repository".** It says yes to a
  gitignored file, to build output, and — on a case-insensitive filesystem — to the wrong capitalisation.
  Both halves shipped (`LINKGUARD-CASE-1`, 2026-09-14): five docs linked `docs/okf/INDEX.md`, which does not exist
  (the tracked file is `index.md`), and two instruction files cited `.claude/sessions/snapshot.md`, which does not exist
  in a checkout either — a hook writes it into every working tree instead. Every local run was green and
  the runner was red. The seam is now `tools/tracked-paths.mjs` — `git ls-files`, case-exact, with implied
  directories. ⛔ Never fold case "to be safe" and never add an `existsSync` fallback: either restores the
  bug while staying green on Linux, where nothing would notice.
  ⇒ **Verify docs against a CLEAN EXPORT, not the working tree:** `git archive` HEAD into a temp dir, copy
  the uncommitted files over it, **`git init` + commit there** (the guards call git, and a guard that
  cannot run is not a pass), then run them from that directory.

- ⚠ **A mutation anchor that is not unique proves nothing, and reports a hole that does not exist.** Twice
  on 2026-09-14 a falsification run flagged the guard as blind when the mutation had simply landed
  somewhere else — one anchor matched `run.bat` instead of `serve.bat` (the two carry identical lines),
  another was absent from the file entirely. ⛔ **Assert the anchor's occurrence count before believing
  either a red or a green**; a probe that cannot report "I did not apply" is not a probe.

- ⚠ **Run the guard sweep AFTER `git add`, not before.** `check-secrets.mjs` scans TRACKED files, so a
  brand-new script is exempt from it while untracked — the whole sweep comes back green on a file one
  `git add` away from failing. Worse, its pre-push pass scans the **push RANGE**, so a later fix commit
  does not clear a value introduced earlier in the same push; it takes a range rewrite.
  🔴 **This is not one guard's quirk — it is how the guards resolve, and it bit twice on 2026-09-14
  with a single new doc.** `check-vocabulary.mjs` and `check-doc-links.mjs` also work from
  `git ls-files`, so on a NEW file the pre-commit sweep is **doubly misleading**: the new file's own
  content is not scanned at all (vocabulary reported 218 docs clean; the same file failed on
  `[source-acquisition-entity]` the moment it was committed), while a link *to* it from a tracked file
  is reported BROKEN precisely because it is not yet tracked. ⇒ **a green sweep over an untracked file
  proves nothing, and a red link-guard pointing at your new file may just mean "not added yet".** Add,
  then sweep, then commit — and read a failure on a new path as a tracking question first.

- 🔴 **Grep the SYMBOL, never the file list a previous pass wrote down.** `DOC-DEADTOKEN-1` named three
  files carrying a dead `-Dcontrol.token`; a sweep for the symbol found **twelve**, plus a *second* dead
  flag (`-Dassist.read.token`, also dead) the row never mentioned, plus a live instance in a file the row recorded as
  already fixed — one that **ships inside the bundle**. ⚠ A cleanup that states its own scope narrowly
  makes everything outside it read as though it had been checked (2026-09-14).

- 🔴 **A row DERIVED from a decision inherits confidence without evidence.** `UI-POD-SCOPE-UNION-1` was
  filed as a consequence of a decision rather than from code; grounding it found **both** its remedies
  impossible — a signed plan (`enterprise-scale-out-plan.md` §5.5) already refused client-side routing
  outright, and no pod identity is serialized anywhere — and its premise false (`/bootstrap` does **not**
  feed the space-switcher; `session.service.ts` never reads `spaces`). ⚠ Worse, that unchecked claim had
  been copied into a shipped code comment, where it reads as verified fact. ⛔ **Never restate a row's
  claim in a comment without checking it — a comment outlives the row.**

- 🔴 **A doc rewrite silently retracts the claims other rows depend on.** Twice on 2026-09-12/13: the
  whitepaper's one-to-many sentence was deleted by the v1.2 rewrite a day *before* an operator chose to
  "build rather than drop" it, and `auth-security.md`'s *"`SpiSlot.active()` has no try/catch"* was true
  when written and falsified by PKG-5 routing that call through `OptionalSpi` — which is what let a
  misconfigured Professional build boot **wide open**. ✅ **When a stakeholder or concept doc is rewritten,
  re-ground every row that cites it**; `git log -S "<exact phrase>" -- <doc>` settles it in one command.

- ⚠ **A verification method can manufacture failures.**
  `[System.Management.Automation.Language.Parser]::ParseFile` reads a file as **ANSI**, and
  `inspecto/package.ps1` is UTF-8 with box-drawing characters — so it reports **40+ phantom syntax errors
  on an UNMODIFIED file**. Use `ParseInput` with an explicit UTF-8 read. ⇒ Before believing a gate has gone
  red on your change, run it on `git show HEAD:<file>`; a red on untouched content is a broken probe.

- 🔴 **`tools/rename-batch-to-consignment.mjs` is a MUTATING CODEMOD sitting among the guards, and it
  APPLIES BY DEFAULT** (`--dry-run` is the opt-in safe mode). Run 2026-09-10 in a "run all the guards" sweep,
  it silently renamed `Batch`→`Consignment` inside an unrelated test and **rewrote the file with CRLF**, then
  **exited 0** — indistinguishable from a guard passing. ⚠ **It is NOT in the pre-push set.** The sweep
  included it because `grep -oE 'tools/[a-z0-9-]+\.mjs' .githooks/pre-push` matched **its name inside a
  COMMENT** ("Its old-name set is PARSED from …"), not an invocation. ⇒ The pre-push guard set is the **seven**
  `check-*.mjs`; derive it from the `run_guard` lines, never from a name-grep of the whole hook, and never
  assume a `tools/*.mjs` is read-only because it lives beside the guards.

- 🔴 **A written-down finding records the INSTANCE, not the class — so its number is a lower bound.** Every
  one of Sprint 7's seven cells (2026-09-10) was scoped from a board row, and **every row undercounted, in
  the same direction**: "~16 authority citations" was 19 documents plus 5 source files · "twenty documents"
  was 27 · "five undefined words" was five, but three of them had **more senses than the row claimed** ·
  "five dead classes" was four, plus a worse third defect the row never mentioned · "five orphan documents"
  was five, but four contained an item that was **refuted or already shipped**. Whoever writes a finding sees
  one example and writes that down. ⇒ **Plan the sweep, not the row**: re-derive the set from the repo before
  estimating, and expect the true count to be larger and the shape different.

- ⚠ **A row goes stale in BOTH directions, because nobody re-reads it when they fix the thing it
  describes.** Twice in the same sweep a row still demanded work that had been done: one pointer had been
  resolved the previous day, and one "two documents tell a contributor to edit a file that does not exist"
  clause had been repaired two days earlier — while two *different* documents had the same defect, live. ⇒
  Re-ground a row against the repo before working it **and** before believing it is still open.

- ⛔ **A false ✅ is worse than a blank, because nobody re-checks a tick.** Two plans recorded a migration
  command as existing; it was in no source file, and a release gate's precondition rested on it. When a step
  is marked done, the evidence goes on the same line — and when what is green is *narrower* than what the
  step asks (round-trip parity of a projection versus the suite executing through the new path), say which
  claim the evidence supports.

- 🔴 **A fixture-corpus parity gate proves nothing about a shape no fixture uses.** A round-trip gate ran
  green over every committed fixture while a whole authored spelling was unreadable — for **13 days** —
  because no fixture used that spelling. So a corpus gate's coverage is the corpus, not the format: when a
  new spelling is added, add a fixture that uses it in the same change. *(Distilled 2026-09-10 (Sprint 7.6) from the three archived plans; this was their only home.)*

- 🔴 **Ground a decision before asking it, and read a free-text answer against the QUESTION.** Of the decisions
  "owed to the operator" on 2026-09-10, three dissolved on measurement (`CONTRACT-ORPHAN-1`'s premise was a `grep`
  false negative; `MAPPING-SPELLING-1` conflated a finished migration with an unstarted one; the register's `PATH-2`
  "refusal" was unbuilt work). And one answer to "lease mechanism" was a query-surface idea — recorded against the
  wrong question it would have become a signed decision nobody made; read against the question it became D13.

- 🔴 **A guard whose scope is mostly EXEMPTION is measuring the wrong thing — so measure the obvious
  design before building it.** Three of Sprint 3's five guard designs died on measurement, and each would
  have shipped green while proving little. The sharpest example: a guard that scans prose for a stated
  count matched **14 lines over 238 current docs**, and its "failures" were a line reference
  (`Roles.java:121-131`), a sentence about one specific node type, and correction notes naming the old
  figure on purpose — while **missing** real phrasings like "the 119-entry catalog". ⛔ **A number in
  prose is indistinguishable from a line number, a version or a date fragment.** The shippable shape is
  the invariant the repo states about *itself*: a contract owns the number, docs mark the statement
  (`<!--count:ID-->`), the guard DERIVES it (`tools/check-doc-counts.mjs`).

- ⛔ **Name a count — or any id — after the SET, never the noun.** "Node types" denotes **three** sets in
  this repo: 31 (`BuiltinNodeType`, the roster), 12 (`node-attributes.contract.json`, only types with an
  attribute spec) and 16 (the recipe entries of the since-retired, 2026-09-24, step-types contract). ⚠ The first two moved together on
  2026-09-15 when `transform.profile` landed — they are unguarded prose here, so nothing caught them. "Transform functions" denotes 23
  (SQL mapping) *or* 30 (ASN vendor plugin). The five-way and three-way count spreads were caused by the
  **ambiguity, not the arithmetic** — nobody had written down that the noun covered several sets.

- ⚠ **Some counts have no single true value, and a guard must refuse to assert one.** Job types are **10**
  on Personal and **12** with `inspecto-ops`; maintenance tasks are **21** built-in ids across **20**
  switch arms plus **4** contributed (`soft_bounce_retry` added 2026-09-15). Both assemble from a built-in list plus `ServiceLoader` discovery,
  so "the count" does not exist until the classpath is fixed. A doc stating either must say which shape it
  means. A guard measures; it must not decide.

- 🔴 **A mutation that does not COMPILE proves nothing about a test.** Two trigger-vocabulary mutations
  (removing an enum constant that is imported elsewhere; adding a component to a record) were caught by
  the compiler, so the tests they were meant to exercise never ran. ⚠ **The tell is the absence of a
  `Tests run:` line** — a bare non-zero exit looks identical to a working mutation test. Prefer mutations
  that compile: ADD an enum constant, or **swap two same-typed record components** (legal, and it changes
  the component list).

- ⚠ **Falsify with COMMITTED files only.** `git checkout --` reverts to HEAD, so restoring a mutated file
  destroys any *uncommitted* work in it. Doing this mid-falsification silently wiped four freshly-seeded
  count markers and put a wrong figure into a commit message and a CI comment.

- ⛔ **A ratchet floor must EQUAL the current count, not sit below it.** Floors set below the marked count
  left slack: deleting a marker left 2 of 3 and the guard went green. Slack in a floor is exactly where
  the thing being counted disappears unnoticed.

- ⚠ **Archiving a plan is not covered by any single guard.** `check-doc-citations` flags dead paths but
  exempts `docs/superpower/` **as a source**, so it is blind to one in-flight plan linking another;
  `check-doc-links` has no such exemption and catches those. **Java javadoc is outside both** — two
  production files cited an archived plan as their design authority. Run both, and grep the code too.

- **The repo guards are NOT in the local build loop — run them before every commit.** Neither the
  Maven reactor nor `ng test` runs `node tools/check-secrets.mjs` or `node tools/check-vocabulary.mjs`,
  so a violation is invisible until CI. On 2026-08-26 **`master` was found sitting RED** on the
  vocabulary guard, unnoticed for an unknown stretch, purely because nothing local ran it. Both are now
  wired into `.githooks/pre-push` (`aa038358`, `442e99d7`) and `core.hooksPath` is set automatically at
  Claude Code session start — but a clone used outside Claude Code still needs
  `git config core.hooksPath .githooks` by hand.
  ⚠ **The two guards sit on OPPOSITE sides of `UCC_RELEASE_GUARD_DISABLE`, deliberately:** secrets
  **above** it (a leaked credential is irreversible, and a hurried security push is exactly when you
  want the check), vocabulary **below** it (that override exists so a human can push an emergency
  security backport, which a banned synonym must not block). ⛔ Do not "tidy" them into one block.
  ⚠ **A THIRD guard joined them 2026-08-28: `node tools/check-dependencies.mjs`** (dependency review,
  compliance G7) — but it is **not** in the pre-push pair and does **not** belong there: it shells out
  to Maven, so it costs a reactor resolution rather than a second. It runs in `ci.yml` **after** the
  Maven build (it needs the eoiagent install), which is why the "two pure-Node guards run BEFORE the
  build" shape below no longer describes the whole set. Locally:
  `MVN_CMD=<mvn> MVN_OFFLINE=1 node tools/check-dependencies.mjs`; after a REVIEWED dependency change
  re-run with `--update` and commit `tools/dependencies.lock` in the same commit.

  ⚠ **A FOURTH joined 2026-09-08: `node tools/check-coverage.mjs`** (GUARD-SWEEP-1g), enforcing
  **repo-wide** coverage floors — backend instructions ≥78% / branches ≥64%, UI statements ≥70% /
  branches ≥66%, against measured baselines of 81.01%/67.19% and 73.82%/70.04% (2026-09-08); re-measured
  2026-10-15 at 86.08%/73.59% (48 backend modules) and 81.51%/76.64% (553 UI spec files). Like the dependency
  guard it is **not** pre-push: it needs `mvn … -Pcoverage` (≈6 min) or a UI `test:coverage` run first,
  so it lives in `ci.yml` (`--backend`) and `ui.yml` (`--ui`). ⚠ Those scope flags are load-bearing —
  `ci.yml` never builds the UI and `ui.yml` never builds Java, so one "all inputs must exist" rule would
  fail whichever job legitimately lacks the other half; each named scope still fails loudly when ITS own
  input is missing. ⛔ Not `jacoco:check`: that goal binds per module, so it would gate `inspecto-util`
  (49.6%) and `asn-golden` (4.4%) on their own numbers instead of the one repo-wide floor.
  🔴 **The first baseline was wrong in a way that looked right** — "82.92% across 21 modules" while nine
  more modules were never instrumented, because a Maven profile is inherited through `<parent>` and
  never through aggregation, and `providers/asn-parser/asn-decoders/pom.xml` is a separate root the reactor merely
  aggregates. A tenth of the codebase sat outside a "repo-wide" number while `mvn -Pcoverage` exited 0.

  ⚠ **`node tools/check-sbom-modules.mjs` joined 2026-09-09** (`f2eaeec8`). 🔴 **This sentence said it
  brought `ci.yml` to "six" pure-Node guards; it was wrong when written** — there were ten, and the number
  had simply not been recounted. Measured 2026-09-14 by listing the steps: **twelve** pure-Node guard steps
  run before the JDK is even set up, plus the two Maven-dependent ones above, plus the `launchers-windows`
  job. ⛔ Never increment a count in prose — list the steps and count them, which is the same rule
  `check-doc-counts.mjs` enforces on product counts and cannot enforce here. It
  holds the shipped bill of materials' first-party module set against `inspecto/package.ps1` — which
  enumerates the staged jars **three** times (`$modules`, the `Copy-Item` staging steps, the boot-smoke
  classpath) — and fails if any two disagree. It exists because the generator's table said four artifacts
  while the bundle carried ten or eleven, for a document that ships **inside** the signed, checksummed
  archive. ⛔ There is **no `node --test` anywhere in this repo**: a tooling check is a `tools/check-*.mjs`
  script that exits non-zero and is wired as a `ci.yml` step. Anything else runs nowhere.

- **A guard, hook or reminder that never reaches anyone looks identical to one with nothing to say.**
  Three instances, all 2026-08-26: the committed-secret guard ran only in CI, i.e. only *after* a push
  had already made the secret public; the `PreCompact` hook emitted an invalid shape
  (`hookSpecificOutput.additionalContext` is not valid for PreCompact) and had **never once fired**,
  failing silently every compaction; and `route:` arming validated only at registration, so a save
  returned `written: true` and the operator learned at the next run. ⛔ When wiring any check, ask **when
  it fires relative to the harm** — and for a reminder, watch it fire once. Its failure text appears only
  at the moment it runs.

- **A residual buried in a BACKLOG row marked ✅/SHIPPED is the likeliest thing in the file to be already
  done.** Four were found stale in one shift (2026-08-15): a `PipelineJobRunner` gap closed *one day* after
  it was written up, a regression test recorded as "worth adding" that already existed, a retention-docs
  gap whose work had landed in `operations-reference.md` rather than the runbook the row named, and a
  "8/8 stores, one missing" criterion that was really 10 stores with three missing. The cause is
  structural: a residual records the file its author *expected* the fix to take, the fix lands elsewhere in
  a later shift, and the row's ✅ header stops anyone re-reading the prose. ⛔ **Grep the CLAIM, not the
  path the row names** — each of these was refuted in under two minutes by checking the behaviour. Three
  would otherwise have been re-implemented on top of working code.

- **Pure-Node CI guards run BEFORE the Maven build** in `ci.yml`, so any of them can fail a
  green-code push (`tools/check-dependencies.mjs` runs AFTER it — see the guards note above).
  ⚠ **`tools/check-doc-links.mjs` joined them 2026-09-08** — the SIXTH repo guard: every relative
  markdown link in a current doc must resolve (1,415 links, zero dangling). Links inside
  `docs/archived-documents/**` are ignored (never-maintained tier) but links pointing INTO it are
  not, and the exemption plus its live count prints on every run. Links inside a ``` or ~~~ fence
  are skipped: a link in a code fence is quoted text, not a live link.
  🔴 **The lesson the guard roster keeps re-teaching: read a guard's success line as a CLAIM, not a
  result.** Three instances inside two days — `check-coverage --ui` could never pass, so `ui.yml`'s
  step was red from the moment it landed; `check-vocabulary`'s pass 1 announced "9 user-facing
  doc(s) clean" for 54 days while reading ONE (8 of 9 entries were dead paths and the count came
  from the LIST, not the read — now a `stale-scope` rule fails the build on a dead entry); and
  AGT-5's gate ran `gh search code`, which returns zero for EVERY term against that repo, control
  included. A guard's SCOPE is a silent exemption — audit it apart from its rules, and never trust
  a zero without a control probe that must succeed.
  The two that have always run first:
  `tools/check-vocabulary.mjs` (banned synonyms in user-facing docs, **plus banned KEYS in the committed
  TOON config corpus** since 2026-08-04 — it reads `git ls-files`, not the working tree, so local matches
  CI and `spaces/**` runtime state is never scanned; its `CONFIG_ALLOW` doubles as the Flow→Pipeline Tier-3
  debt register and fails when an entry goes stale; **plus the operator-visible MESSAGES since 2026-08-26** — pass 4 scanned only IDENTIFIERS, so a banned word inside a 4xx body, a Signal message or a template label was invisible to all four passes, which left the words a user actually reads as the least-guarded surface in the repo) and `tools/check-secrets.mjs` (a
  secret-ish key assigned a ≥16-char literal — SEC-INCIDENT-1). Both take a per-line `vocab-allow` /
  `secret-allow` comment as the escape hatch.
  🔴 **SCOPE AUDIT 2026-08-29 — both guards silently exempted a tier, and a guard's scope is the part
  nobody re-reads.** `check-secrets` skipped `archived-documents/`, borrowed from the vocabulary guard's
  exclusion list — but the two ask **different questions**: a lint asks "is this doc maintained" (so
  linting prose nobody may edit is unfixable noise), while the secret guard asks "is this doc
  **COMMITTED**". 195 tracked files were invisible, including `legacy-api-sunset-runbook.md` — precisely
  the class the `.md` extension exists for, since **archiving a runbook does not redact it**. The archive
  was verified clean when scope was restored, so it closed a hole rather than a leak. `check-vocabulary`
  missed `docs/stakeholders/**` and the **root canon — including `GLOSSARY.md`, the file that DEFINES the
  bans**; four canon files carried live `Flow` residue the rename program had already retired everywhere
  it looked. ⚠ The canon is a **named file list, not `docs/`**, because `docs/` also holds `ops/`,
  `roadmap/`, `api/`, `ui/` and the permanently-unscanned archive. **A new tier is unscanned by default
  and nothing says so** — that is the recurring shape.
  ⚠ **`check-secrets.mjs` was "master-only" while `4.x` still carried the
  live OAuth secrets** (merging it forward would have pinned that branch's CI red, BACKLOG §5); P1 fixed
  the code there and the branch itself was deleted 2026-08-17, so the caveat is now history. A third guard,
  `npm run lint:tokens`, runs in the separate path-filtered `ui.yml` (§6). A fourth,
  **`tools/check-dependencies.mjs`** (2026-08-28), diffs the resolved runtime dependency graph against
  `tools/dependencies.lock` — it runs LAST in `ci.yml` because it needs the eoiagent install, and it is
  a **review** device, never a vulnerability scanner. ⚠ Its lock was generated on a developer machine;
  if CI ever resolves differently the remedy is one `--update` commit, but a persistent local-vs-CI
  split is a design problem to settle, not to paper over.

- **🔴 A PLAN SECTION CAN BE STALER THAN THE CODE IT PLANS — ground every bullet before building it.**
  Scale-out §5.3 listed five work items on 2026-09-12; **three were already shipped**: `IntakeGovernor`'s
  Space key (fixed 2026-09-10 — and recorded as closed in **§12 of the same document**), per-tenant ABAC
  (shipped 2026-07-24, *seven weeks* before the bullet was read), and "partition by Space", which was
  always a statement of direction rather than work. ⚠ The cost of not checking is building something that
  exists; the cost of checking is one read-only grounding pass. ⛔ Treat a plan bullet exactly like a
  BACKLOG row's stated cause — a hypothesis. **Check §12 / the defects section of the same plan first**:
  it is where the closures get recorded when the work section does not get updated.

- 🔴 **A PROBE THAT CANNOT RETURN A HIT REPORTS "ABSENT" AND EXITS 0. Silence is not evidence.**
  Three instances in one shift (2026-09-16), two of them by the same person, one committed before it was
  caught:
  - `git ls-tree HEAD providers/asn-parser/` run from a **module subdirectory** prints nothing and **exits 0**,
    because `ls-tree` pathspecs are CWD-relative. The subtree is fully tracked (70 files). That false claim
    reached five agent briefings, a board row and a commit message — and **five agents independently
    "confirmed" it, because they all ran the probe they were given.**
  - `ls -d legacy-code` at the repo root, for a claim about `providers/asn-parser/asn-decoders/legacy-code/`. The pom
    exists, is a declared `<module>`, and its `<sourceDirectory>../../src/main/java</sourceDirectory>` is
    exactly the tree the rows said it compiles. A row was FILED and COMMITTED on that zero, then retracted.
  - The mirror error: `grep poi` over the poms returns **nine hits, every one a substring of
    "point"/"policy"** — a false POSITIVE. Match `org.apache.poi` or `<artifactId>poi`.
  - **THIRD occurrence, 2026-09-17, same lineage:** a row claimed `providers/asn-parser/src/` holds *“66 Java files that
    no pom compiles (`providers/asn-parser/pom.xml` does not exist)”* — true about that filename, and irrelevant.
    `legacy-code/pom.xml`, one level down, sets `<sourceDirectory>../../src/main/java</sourceDirectory>`,
    which resolves to exactly that tree; `legacy-code` is an unconditional `<module>`, and its
    `target/classes` holds **41 `.class` files** from a real build. ⛔ **Its remedy was “delete the tree”.**
    The row even carried a disclaimer saying it was *not* the same subject as the retracted one — exactly
    backwards. (What survived: the **21 files under `src/test/`** genuinely are unbuilt — no
    `testSourceDirectory`, no `target/test-classes`.)
  ⇒ **Four rules.** (1) Run probes from the repo ROOT, and scope the probe to the claim — a claim about a
  nested path is not tested at the root. (2) Before believing a zero, **prove the probe can return a hit**
  (run it against something you know is there). (3) ⛔ **Agreement between agents is not corroboration when
  they share a probe.** Independent confirmation means a *different* method, not another caller.
  (4) ⛔ **A Maven module's source root need not live under its own directory** — `grep -r "<sourceDirectory>"`
  before concluding any tree is unbuilt, and check `target/classes` for the artefact rather than arguing
  from poms.
  ⚠ Prior art, same class: the grep helper whose quoted-pathspec default produced literal-quote false zeros
  for a whole batch.

- ⚠ **On a long-lived board, the failure modes are structural, not random.** Measured over 21 grounded
  items: **14 were already shipped, impossible, or misdescribed.** The recurring shapes, each of which cost
  at least one round-trip: a **headline** goes stale while its detail block is updated (one row said
  "nothing retries" while a table 500 lines away said SHIPPED — same file); a row **title misreads its own
  source** (GAP-10 said "bundle missing 13 docs"; the spec described an NTFS deny-ACL needing no repo
  change); **superseded text kept as an indented sub-bullet** reads as live status (status is the STRUCK
  line ABOVE it); and worst, a stale **INSTRUCTION** ("take the shape as written and do not improvise it")
  describing work that had shipped the day before — an instruction outliving its facts gets ACTED on.
  ⇒ The durable answer is not more sweeps but **derived guards**: `check-doc-counts` (rank census),
  `route-gating-report --check` (route inventory) and `check-backlog-homes` (owning-doc pointers) each went
  RED on real drift within hours of existing. ⚠ `check-backlog-homes` can only check **14 of 54** pointers,
  because 32 of 55 rows carry no identifier — and the rows that had rotted were disproportionately the
  ungreppable ones. **Requiring an id on every row is the outstanding structural fix.**

### A per-feature timing table hides a per-option cliff (2026-09-23)

A performance sweep timed **"centrality"** once, using that control's DEFAULT metric — `degreeCentrality`,
**2 ms** at 1 999 nodes. The same dropdown offers seven algorithms, and one of them,
`betweennessCentrality`, takes **9 757 ms at 2 000 nodes**. Measured per component:

| | 500 | 1 000 | 2 000 |
|---|---|---|---|
| **betweennessCentrality** | 425 ms | 2 930 ms | **9 757 ms** |
| pageRank + kCore + triangleCount + degreeCentrality | — | — | **129 ms combined** |

🔴 **That single number propagated into a decision and left a real defect in place for a day.** It made
`suspicionScore` look like "the sole outlier" (it is only slow because it CALLS betweenness), so the
low cap was applied to the **caller** while the **cause** stayed on the shared 2 000 ceiling — and an
analyst choosing *Betweenness* from the list froze the main thread for about ten seconds, which is
precisely the freeze the decision was answering.

⛔ It also made a whole backlog item incoherent: "move the 27 algorithms to a Web Worker" is the wrong
shape when 26 of them cost 129 ms combined and a worker boundary serialises a 500-node graph each way.

**Rule: time every option a control offers, not the control.** And when a benchmark reports one figure for
a feature that dispatches to N implementations, treat the figure as measuring the default and nothing else.

### A mechanism that reports on a VIEW of reality certifies only what it can see (2026-09-23)

Four separate guards and one spec file were all reporting confidently about things they could not see. The
shape repeats often enough to be worth naming: **every check has a scope, and its scope is where its silent
exemptions live.** State the scope on every run, or the check quietly certifies a subset.

* **`tools/check-authgate-coverage.mjs` scanned one module of five.** It reported 95 gated routes; there are
  **121**. Twenty-six in `inspecto-ops`/`inspecto-exchange`/`inspecto-events`/`inspecto-geo-link` were never
  checked for an armed test — and an armed test written in one of those modules could not move its verdict.
  `CapabilityManifestTest` was widened to every sibling module on 2026-09-07; this never was. It also
  under-credited what it COULD see: a space-scoped route is registered bare (`/settings/link-analysis`) but
  SERVED under `/spaces/{id}/…`, which is the URL a real test must write, so the end-to-end anchored match
  missed four routes that had armed coverage all along. ⇒ widened, matcher fixed, **scope roster printed on
  every run**, baseline re-derived 79 → 94.
* 🔴 **`AbsentGeoLinkRoutes.SURFACE` is a hand-kept mirror, and it drifted by four routes.** The core declares
  no dependency on the optional `inspecto-geo-link` module, so that table is the ONLY view the core has —
  and **two** mechanisms read it: the 503 "not installed" stubs a Personal build serves, and the OpenAPI
  skeleton generator. One omission, two silent failures: the paths **404ed instead of 503ing**, and were
  **absent from `openapi-v1.json` while the contract guard stayed green**. Its javadoc had asked since
  2026-09-07 that both lists be kept in sync. ⇒ `GeoLinkAbsentSurfaceParityTest` now asserts both directions,
  and a THIRD copy of the same list (in `NoGeoLinkShipsInThePersonalBuildTest`) was removed — it had been
  passing by never touching the drifted routes.
* 🔴 **A spec file dying in the CONSTRUCTOR reports N failures that read as ONE broken thing.**
  `link-analysis.component.spec.ts` was **28/28 red** because its `ActivatedRoute` stub carried only
  `snapshot.queryParamMap` while a new `?case=` deep link reads the live observable. None was an assertion
  failure. The file went dead the day that field landed, taking the workspace/dock behaviour, query-failure
  handling, undo/redo and the evidence flow with it — **and an evidence-flow rewrite shipped the same day
  with its only integration test already dead.** ⇒ fix the TEST DOUBLE, not the component: hardening a field
  initializer so a partial fake stops throwing trades a loud failure for a quiet one.

⚠ **Corollary for numbers:** do not subtract one tool's total from another's. `check-authgate-coverage.mjs`
and `route-gating-report.mjs` scan identically (121 registrations each, verified) but REPORT different
populations — the latter covers MUTATING routes and omits gated GETs, hence 118. Subtracting produced a
confident wrong figure ("23 invisible") that was repeated before it was checked.

⚠ **A DI change is invisible to type-checking.** Removing public methods from an injectable compiles clean;
only running the specs finds a caller doing `new Service()` outside an injector (NG0203).

⚠ **A measurement is a DEFAULT, never a truth, and a zero needs a positive control.** Decision D-S4's
split-identity risk measured **exactly zero** across 24 candidate entity columns — because the generator emits
every entity from a canonical literal, so the failure was *structurally possible and empirically
unobservable*, the worst state for a decision to rest in. The zero was only trustworthy because known-bad
values were run through the same probe and it fired. ⇒ a deliberately dirty fixture now carries the risk, and
`tools/check-split-identity-fixture.mjs` keeps a regeneration from quietly removing it.
