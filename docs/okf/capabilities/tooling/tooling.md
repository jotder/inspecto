---
type: Capability
area: TOOL
title: Guards & repository tooling (TOOL) — capability spec
description: The requirement-of-record and as-built specification for the repo's own hygiene — the guard roster with its scopes, allowlists and exit codes, the four pipelines, the local pre-push layer, the developer scripts, the docs lifecycle and the shared Claude Code setup — plus the doctrine of guards that cannot fail.
status: current
written: 2026-09-09
supersedes-rows: REQUIREMENTS §4 NFR-9 and NFR-10 (this file corrects them, see §2)
---

# Guards & repository tooling (`TOOL`)

> **How to read this file.** §1–§2 are the *requirement of record*: where they disagree with
> `docs/REQUIREMENTS.md` `NFR-9` or `NFR-10`, **this file wins** and the disagreement is stated in place.
> §3 is the as-built specification, §4 the dated decisions, §5 what is not built, §6 what was refused,
> §7 the pointers, §8 how it is verified.
>
> ⛔ **This is a new area.** `docs/GLOSSARY.md` §14: the repo's own hygiene — the guards, the docs
> lifecycle, the `.claude/` setup — with board rows that mapped to no area before it existed.
>
> ✅ **The doctrine is already written and it is excellent.**
> [`guard-coverage.md`](../../backend/build-run/guard-coverage.md) is a `Practice` page titled *Guards
> that cannot fail*, swept after three findings in one shift turned out to be the same defect wearing
> different clothes. This spec does **not** restate it. What this spec adds is the thing that page
> deliberately is not: **the roster** — every guard with its scope, its waivers, its exit codes and its
> wiring, in one table (§3.2), because that inventory existed nowhere.

## 1. Purpose & scope

This capability is **how the repository defends itself from its own drift**. Every other area's spec has
found stale rows, dead paths and counts stated four ways. This area owns the machinery that is supposed
to catch those, and the doctrine for writing machinery that actually can.

**In scope**

* **The guard roster** — nine enforcement scripts plus the inline pipeline checks: what each reads, what
  it deliberately does not, its waiver surface, its exit-code discipline and where it is wired.
* **The doctrine** — the three shapes of guard that cannot fail, and the six rules for writing one that
  can. Owned by the `Practice` page; summarised here only as a pointer.
* **The four pipelines** — continuous integration, the client pipeline, the release pipeline, the branch
  policy: their order, their rationale, and what they do not gate.
* **The local layer** — the pre-push hook, why two of its guards sit on opposite sides of its own
  bypass, and the one check no pipeline can substitute for.
* **The developer scripts** — the pack scaffolder, the backend launcher, the acceptance seeder, a spent
  codemod, and the templates.
* **The docs lifecycle** — the three tiers, the rule that new knowledge goes into a concept rather than
  a new root file, and the archive's actual (load-bearing) status.
* **The shared Claude Code setup** — why it lives in the repository rather than a user profile, and what
  it contains.

**Out of scope (owned elsewhere)**

* **What each guard guards.** The vocabulary itself is `docs/GLOSSARY.md`; the controls that cite these
  gates as evidence are `CMP` ([`compliance/compliance.md`](../compliance/compliance.md)); the client
  gates' subject matter is `UI` ([`surfaces/surfaces.md`](../surfaces/surfaces.md)).
* **Packaging semantics and edition composition** → `PKG`. This area owns the *script's* hygiene, not
  what a bundle should contain. Where the two meet — the release path having never run — is recorded
  here because it is a tooling fact, and in `CMP` because a control depends on it.
* **The build itself** — the reactor, the modules, the test suites → the owning areas.

## 2. Requirements of record

| ID | Requirement (as it holds) | Status of record |
|---|---|---|
| **NFR-9** | **Quality gates** | 🟡 **SHIPPED, and the row understates and misstates it.** It names four things: the full reactor and client sweep, the token lint, an accessibility gate and a live smoke. 🔴 **There is no accessibility gate**: the library is a development dependency used inside unit specs, and no pipeline step gates on it. 🔴 **The live smoke exists only in the release pipeline, which has never executed.** And the row names **none** of the nine repository guards nor the coverage floors, which are the substance of what is actually enforced. ⚠ This matters beyond tidiness: an ISO control cites `NFR-9` as its *only* evidence for secure development and testing |
| **NFR-10** | **Vocabulary discipline** | 🔴 **SHIPPED, and both halves of the cell are wrong.** (a) The mechanism is not "enforced in review" — it is a guard wired into the pipeline **and** the pre-push hook, and a compliance control cites that script by name. (b) The scope claim "banned synonyms never appear in UI, model, API or docs" is far wider than the guard: of the **seven** hard bans the instruction file lists, **three have no rule at all**, one is enforced only in configuration keys, and one only in prose. See the table below |

**The vocabulary guard against the bans it is supposed to enforce.** Ten rules across four passes:

| Ban | Prose | Config keys | Source identifiers | Operator messages | <!-- vocab-allow: names the banned term in order to state whether a rule covers it -->
|---|---|---|---|---|
| Flow → Pipeline | ✅ | ✅ (+ an authored-path rule) | ✅ | ✅ | <!-- vocab-allow: names the banned term in order to state whether a rule covers it -->
| Data Store → Dataset | ✅ | ✅ | — | — | <!-- vocab-allow: names the banned term in order to state whether a rule covers it -->
| Issue → Incident | — | ✅ | — | — |
| Source (acquisition) → Collector | ✅ | — | 🔴 **no rule** | 🔴 **no rule** | <!-- vocab-allow: names the banned term in order to state whether a rule covers it -->
| Cube → Matrix | ✅ | — | — | — | <!-- vocab-allow: names the banned term in order to state whether a rule covers it -->
| bare Rule → qualified Rule | 🔴 **no rule anywhere** | | | |
| Metric (BI) → Measure | 🔴 **no rule anywhere** | | | |
| Data Source → Stream/Reference | 🔴 **no rule anywhere** | | | | <!-- vocab-allow: names the banned term in order to state whether a rule covers it -->

⚠ **This is not a hypothetical gap.** A board row already suspected it: a Source-to-Collector slip in <!-- vocab-allow: names the banned term in order to state whether a rule covers it -->
two author-facing messages went unflagged, and the row asked whether the rule covered that case at all.
It does not — the message pass has only the two Flow rules. And one rule was **retired on purpose** <!-- vocab-allow: names the banned term in order to state whether a rule covers it -->
(2026-09-06) because the operator widened the glossary so an alert rule may watch either concept, which
withdrew the rule's premise.

**The board rows this area inherits are five, not four.** The glossary's count of four came from a
measurement taken *before* the 2026-09-07 and 2026-09-08 drains: seven un-areaed rows minus three
compliance ones. Against the current board the rows with no other home are the doc-lifecycle violations,
the index and archive hygiene residue, the graph-tool version sync, the vocabulary rollout's
release-gated remainder, and the guard-rule gap above.

## 3. Specification

### 3.1 The doctrine, in one paragraph and a pointer

[`guard-coverage.md`](../../backend/build-run/guard-coverage.md) is the rule; this is the summary. **Three
shapes of guard cannot fail**: one that is never reached, one whose subject is its own module (so the
thing it would catch is the thing it cannot see), and one whose subject is a number a human wrote in
prose. **Six rules make a guard able to fail**: give it an emptiness floor set from a measurement, not a
round number; make a ratchet move both ways; anchor a source scan and assert the anchor; prefer an
assertion to a skip, and make a legitimate skip *visible*; check the staged artifact and then **run** it;
and never set a floor so far below the actual value that it cannot fire. The page's own conclusion is the
sentence this whole area turns on: **a clean run says nothing about what it declined to look at.**

### 3.2 The guard roster

This is the inventory that existed nowhere. Nine scripts, plus two inline pipeline checks.

| Guard | Reads | Deliberately does not read | Waivers | Exit discipline | Wired |
|---|---|---|---|---|---|
| **Vocabulary** | Four passes: one curated user-facing file; committed configuration keys; nine knowledge trees plus nine named canon files; source identifiers and operator-visible messages | The archive (permanent, reasoned); **the instruction tree and the root instruction file**; two module documentation directories; test sources; resources; the guard scripts themselves | Three path-and-rule allowlists (8 + 3 + 14 entries) whose **reason is structurally load-bearing** — a falsy reason does not suppress. **A stale entry fails the build.** Per-line hatch needs no reason | 0 / 1 / **2 when it cannot reach git** | pipeline + hook |
| **Secrets** | Tracked files across sixteen extensions, plus a **push-range** pass over the pushed objects | Untracked files; worktrees; every other extension including keys, certificates and extensionless files | No path allowlist. A per-line hatch, indirect-key suffixes, sixteen placeholder patterns, a minimum length | 0 / 1 / **2 below a 1,000-file floor**; refuses rather than vouches when it cannot read the range | pipeline + hook (both passes) |
| **Doc links** | Three roots plus root markdown, working-tree | Two module documentation directories; the client tree; the tooling markdown | None. One exemption — archive-internal links — **printed on every run, pass or fail** | 0 / 1, with file and link floors; does not distinguish violation from cannot-run | pipeline + hook |
| **Gate tally** | One section of one file | Everything else | None | 0 / 1, with a row floor | pipeline + hook |
| **Coverage floors** | Every module coverage report it can find, summed; the client summary | 🔴 **No minimum module count** | None | 0 / 1; **no cannot-run code** | pipeline only |
| **Dependency review** | The resolved runtime graph under the enterprise profile, against a committed lock | Test scope; **the entire client dependency tree** | The lock *is* the waiver surface — a change is legitimised by committing the regenerated lock | 0 / 1 / **2 in three distinct ways**. The best separation of the nine | pipeline only |
| **Processor board** | Two files: a served contract and one table | Everything else in that file | Three sets of identifiers holding **operator product decisions**, with **no reason field and no stale detection** | 0 / 1 / 2 | pipeline only |
| **Bill of materials** | One resolution per packaged bundle | The wider reactor; test scope; the client tree; **what the shade step actually embedded** | None. The gate is a rule, not a count: nothing unhashed, and only first-party components unlicensed | 0 / 1 / 2 | packaging only |
| **Design tokens** | Two client roots | 🔴 A third root of real authored components, and the core directory | A file allowlist plus a per-line hatch | 0 / 1 | client pipeline |
| Lean-core boundary | Source grep **and** the resolved dependency tree | — | None | fails on either | pipeline | <!-- vocab-allow: names the banned term in order to state whether a rule covers it -->
| Retired branches + commit subjects | The event payload; the push or request range | — | None | fails per offending subject | branch policy |

**Two structural facts about the roster.** No guard is local-only, and none is wired nowhere. And
nothing invokes any of them from the build, the client test runner, a package script or a session hook —
the only two wiring sites are the pipelines and the pre-push hook.

**Tracked versus working tree, in mirror image.** The vocabulary and secret guards take their file list
from the index, so a file that has never been staged is invisible to them. The link and coverage guards
walk the working tree instead. ⚠ **Correction to a claim made earlier in this consolidation:** the
pre-push run does *not* look harder than the local run — it is the same script with the same
index-derived scope. What changes is the file's own state, because a file must be committed to be pushed,
and **staging alone is enough** to expose it. The blind window is exactly *written but not yet staged*,
and the thing that closes it is the commit, not the hook. The index scoping is deliberate and reasoned:
local matches the pipeline, ignore rules come free, and runtime state that may hold real operator data is
never read.

### 3.3 Scope is where the silent exemptions live

The doctrine page records five instances. This spec adds a sixth, and it is the one closest to home.

| Instance | The exemption |
|---|---|
| The token guard scans two client roots | A third root of genuinely authored components can hardcode colour freely. ⚠ Two documents describe this exemption **differently and both incompletely**, each naming a different unscanned path and neither naming the core directory |
| The dependency lock resolved with no edition profile | The security module's cryptography tree — the one a reviewer most wants — was never under review while a control already said closed |
| The first coverage baseline | A repository-wide figure that omitted roughly a tenth of the code, because a build profile is inherited through parentage and never through aggregation |
| A capability drift check scanned one directory | It became narrower than its subject the moment the interface it guards went public. Widened in the same commit that opened the seam, which is the rule: **the two must move together** |
| The vocabulary guard, four times over | Each addition closed a tree a clean run had been declining to read. The fourth was hiding thirteen violations in the documents an executive reads, one of which instructed the reader to configure a job type that does not exist |
| 🔴 **The vocabulary guard, a fifth time — the instruction files** | **The root instruction file, which defines the bans, and the whole instruction tree of nine skills, three agent definitions and four reference maps, are in no pass.** The root file carries five banned words with no allowance markers while the guard reports green, which is only possible because it is unscanned. **220 of 503 tracked markdown files are in scope** |

🔴 **A SEVENTH instance, found 2026-09-10 (Sprint 7.1/7.6): neither documentation guard reads SOURCE
FILES.** The citation guard and the link guard both scope to markdown, so a javadoc or a TypeScript comment
could cite a moved path, name a renamed type, or delegate its authority to the never-maintained archive tier
and no run would ever say so. Measured: **five source files** did exactly that, and three of them pointed
"current knowledge" at a documentation page containing **zero** mentions of their subject — which made an
archived plan the only authority a reader could actually reach. Two more delegated to a plan that was about
to move. ⚠ The vocabulary guard *does* scan 1,546 Java and TypeScript files, so the asymmetry is per-guard,
not repo-wide, and that asymmetry is exactly what made it invisible: a green run of "the guards" says
nothing about a class only one of them looks for.

⚠ **A second lesson from the same sweep: the citation guard exempts `docs/superpower/` as a source**, which
is correct for an in-flight tier but means it could not see one active plan's links into a sibling plan. The
link guard has no such exemption and caught those. **Two guards with different scopes each caught half of
one move, and neither could have caught the other's half.**

Two things make the sixth instance worth a row rather than a shrug. The guard's own comment supplies the
argument: past a certain age a banned synonym stops being wrong vocabulary and becomes **wrong
instructions** — and instruction files are where that lands hardest. And the guard's own scripts are
unscanned too, so an operator-facing string inside a guard cannot violate the vocabulary that guard
enforces.

⚠ **A new scope must be falsified with tracked probes, per directory.** The first attempt at the fourth
addition used untracked probes and all five slipped through silently, because the guard enumerates the
index.

### 3.4 The pipelines, and what they do not gate

**Continuous integration** runs one job with a database service container. Five pure-Node guards come
first, explicitly because they are fast and fail early; then the toolchain, the upstream agent build from
source, the reactor under the **enterprise profile** with coverage reporting, the coverage floors, the
lean-core boundary check, and the dependency review **last**, because the resolved graph must include what
the upstream build installed. Three ordering decisions are recorded rather than incidental: the reactor
step installs rather than tests, so the dependency guard's own fresh resolution can find reactor
snapshots — it had looked healthy locally only because a developer's local repository is warm, *a guard
passing on environment state rather than on the thing it checks*; the enterprise profile is used because
the profile is modules-only and two modules carrying **54 test methods** were built by no workflow that
runs tests; and the coverage profile is **reporting only**, with no check goal, so it cannot fail — the
floors live in the script.

**The client pipeline** is path-filtered and kept entirely separate so the client toolchain never enters
the reactor. Six steps: the token lint first (fail early), a formatting check that is deliberately
verify-only because a reflow can change what a template renders and can strand a suppression comment
from the line it applies to, **three** type-check configurations because the root one was checked by
nothing and is a genuinely different gate, the tests with coverage reporting, the floors, and the
production build as the final gate.

**What is not gated anywhere.** No linter for the client beyond formatting and the token check, despite a
commit message claiming one was wired. No accessibility step. **No vulnerability scanning of any kind** —
no dependency-alert service, no code scanning, and the dependency review says in its own comment that it
must not be cited as vulnerability management. No packaging, signing or smoke outside the release path.
The fat artifact is never built in the ordinary pipeline, by choice, because it is large and mostly
native libraries. And a module with no tests writes no report at all, so it leaves the coverage
denominator rather than dragging the percentage down.

### 3.5 The local layer

The pre-push hook is activated per clone, and this clone has it. Its helper is deliberately forgiving in
two ways that cap every local guard: **a deleted guard script passes silently**, and a missing runtime
**warns and passes** — both on the reasoning that a hook which fails every push on some machines gets
disabled entirely, after which no local layer runs at all, which is strictly worse.

⛔ **Two guards sit on opposite sides of the hook's own bypass, deliberately, and must not be tidied
together.** The secret scans sit **above** it, because the moment you most want that guard is a hurried
security push, and because a pushed secret cannot be un-pushed. The vocabulary, tally and link guards sit
**below** it, because a banned synonym is reversible and a credential is not.

**One check no pipeline can substitute for.** The push-range secret scan reads the objects being pushed,
not the tip. A credential committed and then moved to an environment variable one commit later is
invisible to a tree scan while the objects carrying the value still travel — and the pipeline only ever
sees the tip. This is the layer that exists because a real incident was pushed and spotted six weeks
later.

⚠ **An undocumented second bypass exists.** The retired-branch list is itself an environment override, so
emptying it skips that check without touching the documented switch.

### 3.6 The developer scripts

| Script | What it is | State |
|---|---|---|
| **Pack scaffolder** | Generates a job, processor or node-type pack from templates, reading coordinates from the reactor at generation time rather than hardcoding them. **Refuses** two of its five kinds by design, naming the slice that would unlock each — *refusals are honest*, rather than emitting a half-working skeleton for a mount the engine cannot host | Documented in three concept pages; invoked by no executor. 🔴 **Both refusal messages point at a plan that has since been archived**, so a developer who hits the gate is sent to a path that does not exist — and the link guard cannot catch it, because it reads only markdown |
| **Backend launcher** | Runs the control plane from the working tree, deriving the classpath at run time and prepending module output so fresh code shadows stale installed jars. The derivation is load-bearing as an *exclusion*: an optional module that is not a dependency can never leak onto the classpath | Wired in the development launch configuration. ⚠ One skill duplicates the boot with a hand-written classpath — the exact pattern this script was written to retire |
| **Acceptance seeder** | Builds a realistic space **entirely through the platform**, never by writing to a database, because seeding through the engine is what makes the audit, lineage and run-history surfaces worth testing. Two phases with a restart between them, and it refuses to run both at once because the space is only discovered at boot | Operator-run by design, with its own runbook |
| **Codemod** | A one-shot rename that moved a core domain word, in one commit, with longest-key-first replacement and three deliberate exclusions | 🔴 **Spent.** It has already run; re-running it is a no-op |
| **Templates** | Three packs consumed by the scaffolder and pinned by a test | 🔴 **One template hardcodes the engine version** where its two siblings use substitution tokens, contradicting the scaffolder's own stated invariant. Its test stamps the token and compiles rather than resolving through the build, so it cannot see a literal where a token belongs. **The next version bump breaks that generator silently** |

### 3.7 The docs lifecycle and the shared setup

**Three tiers, and the archive's status is not what its policy sentence says.** Current knowledge lives in
concepts plus a small root canon; active plans live in one directory and only while their work is in
flight; history is kept for provenance. The rule that new knowledge goes into a concept rather than a new
root file is enforced socially, not by a guard. ⚠ The tier policy says the archive is *never linked as
current*; that sentence is **flatly false** — most of its files are cited from the current tier and at
least two dozen as authority. Deleting it was proposed and **refuted**, and is now a gated step.

**The shared setup lives in the repository, not a user profile**, so every shift gets an identical
environment. It holds four session-entry documents, three read-only explorer agents, five hooks, nine
skills, the permission and hook settings, and a development launch configuration. One hook does the thing
without which no local guard runs at all: it sets the hook path idempotently every session. ⚠ Two
documents state the opposite of the tracking rule, saying this tree is ignored when 32 files are tracked.
And a clone used outside the session tooling still needs the hook path set by hand, with nothing checking
that it was.

⚠ **Two live worktrees hold full second copies of the repository**, contributing 1,859 markdown files
against the real tree's few hundred. Three guards exclude them by name; every hand-rolled repository-wide
search must too, or it double-counts and mixes stale sources.

### 3.8 The release path has never run, and it carries two latent defects

The release pipeline triggers on a version tag. It **postdates every tag in the repository** — the newest
is three months older than the workflow, and an ancestry check confirms the workflow is not contained in
it. So the signing-key import, both packaging invocations, the examples smoke, the artifact assertions,
the verification pass and the publish step have only ever been **read**.

Two consequences follow, and neither could have been observed by anyone:

* 🔴 **The packaging script has never run on Linux**, and two things in it are shaped for Windows. One
  path is built from a variable that is unset on Linux. And **32 destination paths are built with a
  literal backslash**, including on the path every edition takes. Backslash is a legal filename character
  on Linux, so those copies would not fail — they would produce single files with backslashes in their
  names and a silently wrong bundle, which is worse than a crash.
* ⚠ **The verification step's claim to check "the public half only" describes the artifacts, not the
  trust root.** It runs in the same keyring that still holds the private key imported minutes earlier, so
  it cannot detect a key a customer would not have.

Two accuracy defects in the same area: the script's own header describes editions as the core plus one or
two jars, while the code builds **nine** modules — stale by seven gating cells; and the pipeline's step
name claims every edition is signed when **two of three** are, with no artifact for the third.

## 4. Decisions (dated one-liners)

| Date | Decision | Why |
|---|---|---|
| 2026-07-02 | The shared setup is **consolidated into the repository**: three skills added, hooks pruned from thirteen to three by deleting eleven that were never wired, local permissions reset and the generic ones promoted to the committed file, five unused plugins disabled after usage was confirmed at zero, and thirteen plans moved off the user profile into the repo | one environment per shift |
| 2026-08-03 | **Vocabulary enforcement becomes a build guard, not a review convention** | makes the glossary enforceable instead of aspirational |
| 2026-08-04 | The configuration-key allowlist **doubles as the rename debt register**, with a stale-entry rule making the debt **self-retiring** | the rename announces itself when the last user goes |
| 2026-08-04 | Build-time generation of a shared table **rejected**: a generated artifact silently absorbs drift | — |
| 2026-08-26 | **Operator-visible messages join the guarded surface** — the prior passes read identifiers only, *which left the words a user actually reads as the least-guarded surface in the repo* | it found fifty |
| 2026-08-26 | The two guards move into the pre-push hook, after **master was found sitting red on the vocabulary guard**, unnoticed, purely because nothing local ran it | — |
| 2026-08-26 | ⛔ The two hook guards sit on **opposite sides of the bypass** and must not be tidied into one block | a secret is irreversible; a synonym is not |
| 2026-08-26 | **Timing is part of a guard's design**: ask when it fires *relative to the harm*, and for a reminder, watch it fire once | the secret guard had run only after a push made the secret public |
| 2026-08-28 | The dependency review is a **review device, never a scanner** — it proves nobody looked, never that a version is safe | offline by constraint |
| 2026-08-29 | A **scope audit** finds both pure-Node guards had silently exempted a tier; the secret guard regains the archive, because *archiving a runbook does not redact it* | — |
| 2026-09-06 | One vocabulary rule is **retired** because the operator widened the glossary and withdrew its premise | a rule whose concept changed is not a rule |
| 2026-09-07 | **A guard that cannot fail is not a guard** — eight fixes in one sweep, including the edition profile, the lock's scope, an emptiness floor, commit linting on the push range, all three type configurations, and warn-only counters becoming a failing rule | — |
| 2026-09-07 | A legitimately skipped test's assumption goes **per test, never per class** — per class reports zero tests and the class vanishes from the totals | make a skip visible |
| 2026-09-07 | Packaging now **launches** the bundle it staged and waits for health — inspection was not enough, because every jar had been present and the bundle still could not boot | check the staged artifact, then run it |
| 2026-09-07 | A service container restores a database test suite without reversing the removal of the embedded harness: **a service container is an existing server** | eleven tests had been skipped, so the one piece of non-portable SQL was proven nowhere |
| 2026-09-08 | **Repository-wide coverage floors, deliberately below the measured baseline** — a floor set at the current number fires on noise and teaches people to ignore it | — |
| 2026-09-08 | The per-module check goal is **not** used, because it would gate two low-coverage modules on their own numbers instead of one repository-wide floor | — |
| 2026-09-08 | The coverage profile is **duplicated** into a second reactor root with a comment saying why it cannot be inherited | a profile is inherited through parentage, never through aggregation |
| 2026-09-08 | The link guard's archive exemption is **printed on every run, pass or fail** — *an unprinted scope is an unaudited one*, applied in advance this time rather than after the fact | — |
| 2026-09-08 | Capability specs go under the concept tree **specifically to inherit vocabulary scanning for free**, avoiding the silent-exemption trap a new top-level tree would open | — |
| 2026-09-08 | A new guard scope must be falsified with **tracked** probes, per directory | the first attempt used untracked probes and all five slipped through |
| 2026-09-08 | **Falsify every entry point, not just every outcome** — a coverage flag had made one job impossible to pass | — |
| 2026-09-09 | This spec: `NFR-9` and `NFR-10` corrected; the roster written down for the first time; the instruction files recorded as the fifth scope gap; the release path's two latent Linux defects recorded | this file §2, §3.2, §3.3, §3.8 |

## 5. Not built

### 5.1 Tracked (a board row exists)

| Item | Row |
|---|---|
| **Doc-lifecycle violations** — two plans that can only be archived when the work they describe closes | §5, release-gated |
| **Index and archive hygiene residue** — deletion is a gated step, ⛔ not before every citation and orphan has a destination | §5 |
| **Graph-tool version sync** — the version markers agree while the tracked skill copy and the installed copy differ by roughly 300 lines; *comparing version markers will never tell you* | §5 |
| **The vocabulary rollout's release-gated remainder** — the wire-level rename, plus deleting one allowlist entry, which the stale-entry rule then turns into the rename announcing itself | §4 |
| **The guard-rule gap** — whether the message pass covers the Source-to-Collector case. §2 answers it: **it does not** | §4 | <!-- vocab-allow: names the banned term in order to state whether a rule covers it -->
| A derived map that has drifted four times — ⛔ do not fix it by hand a fifth time, pin it | §4 |
| The continuous-integration evidence document a compliance artifact expects | §5 → `CMP` |

### 5.2 UNTRACKED — found 2026-09-09, no board row yet

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `SPEC-STALEREF-1`, `SPEC-COUNTS-1`, `SPEC-GREENCELL-1`, `SPEC-GLOSSARY-1`.
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`archived-documents/plans-archive/post-consolidation-sprints.md`](../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.

1. ✅ ~~**Capability specs cite a pointer-check tool that is not in the repository.**~~ **FIXED 2026-09-09
   as `tools/check-doc-citations.mjs`**, wired into `ci.yml` and `.githooks/pre-push`. It was **nine**
   specs, not the five counted here — this row was itself an instance of `SPEC-COUNTS-1`. The better of
   the two options was taken (commit the checker, not reword the sections), and the nine §8 sections now
   name a step anybody can run.

   🔴 **Its first run found 152 stale citations, against the 23 the consolidation had counted by hand** —
   39 dead paths and 113 citations of Java types the 2026-08-31 Consignment rename retired, across 37
   files. All 152 are repaired in the same commit. The load-bearing design lesson is what the guard does
   **not** do: the first version checked that every backticked CamelCase name exists in the tree, and
   measurement killed it — 4,654 such citations, 56 distinct names absent, and the large majority of those
   absences were legitimate (third-party types, deliberately-unbuilt designs, renames recorded on
   purpose). That guard would have been ~45 entries of allowlist and 11 of rule. ⚠ **A guard whose scope
   is mostly exemption is this area's recorded failure three times over**, so check B narrowed to the one
   objective invariant available: the rename map the repository itself commits, PARSED from the codemod
   rather than mirrored, because a hand-mirrored map drifts.
2. ✅ ~~**The coverage guard has no minimum module count.**~~ **FIXED 2026-09-09.** It had found **one**
   module report, computed 97.89% over its 427 instructions against a 78% floor, and reported every floor
   met with a success exit — it failed only on *zero* reports, so a single stale build directory
   manufactured a green number. `tools/check-coverage.mjs` now carries `MIN_BACKEND_MODULE_REPORTS = 20`
   (29 reactor modules hold a `src/test` directory, so the floor sits well below the real set and far above
   one). Falsified in both directions on the same tree: the previous version printed *"every floor met"*
   and exited 0; the current one exits 1 naming the module it found. ⚠ The scope check is deliberately
   skipped when there are **zero** backend reports, because `ui.yml` runs this guard for the client half
   alone. The lesson the doctrine page gains: a guard that rejects *none* of its input still accepts a
   **fraction** of it, which is the same shape and harder to see.
3. 🔴 **Three of seven vocabulary bans have no rule at all** (§2), one is enforced only in configuration
   keys and one only in prose. Either the requirement's scope claim narrows to what is enforced, or the
   rules grow. The board row that suspected this can then close.
4. 🔴 **The instruction files are outside the vocabulary guard** (§3.3) — including the file that defines
   the bans. Adding them means auditing the legitimate ban statements first, which is exactly the work the
   fourth scope addition did.
5. 🔴 **The packaging script has never run on Linux and carries two Windows-shaped defects** (§3.8). The
   backslash paths are the dangerous one because they fail silently.
6. **The scaffolder's refusal messages point at an archived path**, and one template hardcodes a version
   its siblings tokenise, with a test that cannot see it (§3.6).
7. **No vulnerability scanning exists anywhere**, and the dependency review explicitly must not be cited
   as such. Whether that is acceptable is a product decision; today it is an unstated gap.
8. **No client linter is wired** despite a commit message claiming one, and the accessibility library runs
   only inside unit specs while a requirement names an accessibility gate.
9. **Four accuracy defects in pipeline and script prose**: the pipeline header describes a two-module
   reactor finishing in thirty seconds; the vocabulary guard is described as two passes when it has four;
   the release step claims every edition is signed; the packaging header describes editions by their
   pre-gating composition.
10. **The doctrine page states one defect in the present tense that was fixed the same day**, and asserts
    a pipeline run history that cannot be checked from a checkout — which its own rule forbids.
11. **The client coverage baseline is stated two ways**, once inside this area's own concept page, and the
    outlier is that page.
12. **The board section states its own row count two ways**, and the guard that exists to police exactly
    that arithmetic validates one sentence and not the other — the guard's documented failure mode,
    reproduced inside the section it guards.
13. **The link count is stated three ways** across two documents and the guard's own comment.
14. **The token guard's scope exemption is documented differently in two places**, each naming a different
    unscanned path, neither naming a third.
15. **A tracked hook comment names a hook that does not exist**, which is the class the link guard was
    written for, in a file type it does not read.
16. **Two documents say the shared setup tree is ignored** when 32 files are tracked; and nothing checks
    that a clone outside the session tooling has its hook path set.
17. **The concept tier's charter says it is a constraint register, not a backlog**, which the consolidation
    plan records as a charter the tier has not held; amending it is an unstruck plan step.
18. **The archive holds refusals that survive nowhere else** — three standing refusals were archived
    without being distilled into a concept, which the board itself calls the real residual.

## 6. Refused & superseded

| Item | Verdict | Why |
|---|---|---|
| The per-module coverage check goal | **Refused** | it would gate two low-coverage modules on their own numbers instead of one repository-wide floor |
| The dependency review in the pre-push hook | **Refused** | it shells a full resolution; a hook must cost seconds |
| The coverage floors in the pre-push hook | **Refused** | it needs a full instrumented build first |
| Tidying the two hook guards into one block | **⛔ Refused** | they sit on opposite sides of the bypass by design |
| A vulnerability scanner via the dependency lock | **⛔ Refused** | *it proves nobody looked, never that a version is safe* |
| One vocabulary rule | **Superseded 2026-09-06** | the operator widened the glossary and withdrew its premise |
| Banning the bare word in source identifiers broadly | **Refused as scoped** | a measurement found the overwhelming majority were unrelated compounds or sanctioned uses — *a noisy guard gets disabled* |
| The vocabulary pass over test sources | **Refused** | test names are read as prose and carry no contract; it would have tripled the change for no reader benefit |
| Renaming three test literals | **⛔ Refused** | they are the only proof the dual-read compatibility path works |
| Build-time generation of a shared table | **Rejected** | a generated artifact silently absorbs drift |
| Deleting the archive tier | **Refuted** | most of it is cited from the current tier, two dozen files as authority; now a gated step |
| Re-homing the concept tree into functional directories | **Not proposed** | over a thousand inbound links, hundreds of graph nodes, and path-keyed waivers |
| A new requirement identifier scheme | **Not proposed** | the existing prefixes are load-bearing citations |
| A new top-level documentation tree for capability specs | **Refused** | it would be **silently unscanned** for vocabulary until someone added it |
| Two of the scaffolder's five kinds | **⛔ Refused by design** | *refusals are honest* — do not emit a half-working skeleton for a mount the engine cannot host, and name the slice that would unlock it |
| A build archetype for the scaffolder | **Refused** | archetypes resolve from a repository; this one builds air-gapped |
| The obvious template token syntax | **Refused** | a generated build file legitimately contains the build tool's own substitutions |
| A packaging switch for the agent modules | **⛔ Refused** | until the runtime floor conflict is resolved |
| Publishing build artifacts | **Refused** | reopen on an external consumer; there is none |
| A distributed or per-user session setup | **Refused** | all setup lives in the repository so every shift is identical |
| New root-level topic documents | **Refused** | new knowledge goes into a concept or an existing canon file |

## 7. As-built pointers

| Concern | Artifact |
|---|---|
| The doctrine | [`guard-coverage.md`](../../backend/build-run/guard-coverage.md) — `Practice`, *Guards that cannot fail* |
| The guards | `tools/check-vocabulary.mjs`, `check-secrets.mjs`, `check-doc-links.mjs`, `check-gate-tally.mjs`, `check-coverage.mjs`, `check-dependencies.mjs` + `tools/dependencies.lock`, `render-processor-board.mjs`, `sbom.mjs`; `inspecto-ui/tools/check-design-tokens.mjs` |
| The pipelines | `.github/workflows/ci.yml`, `ui.yml`, `release.yml`, `branch-policy.yml` |
| The local layer | `.githooks/pre-push` |
| Packaging | `inspecto/package.ps1` |
| Developer scripts | `tools/scaffold.mjs`, `tools/run-backend.ps1`, `tools/seed-uat.ps1`, `tools/rename-batch-to-consignment.mjs`, `tools/templates/` |
| Build and test practice | [`build-test.md`](../../backend/build-run/build-test.md); [`testing-and-build.md`](../../frontend/conventions/testing-and-build.md); [`design-system-tokens.md`](../../frontend/conventions/design-system-tokens.md) |
| Branch and release policy | `docs/BRANCHING.md`; [`branching-release.md`](../../backend/editions/branching-release.md) |
| The docs lifecycle | `CLAUDE.md` §Documentation lifecycle; `docs/INDEX.md` |
| The shared setup | `.claude/` — four entry documents, three agents, five hooks, nine skills, settings, launch configuration |
| The guard roster in prose | `docs/PROJECT_NOTES.md` — the only prior inventory, and never a table |

**Gap rows.** The doctrine page is a **rules register, not an inventory**: it never enumerates the roster
with wiring, scope and floors, which is why §3.2 exists. Its own resource line omits the client token
guard it discusses. The prior inventory lived only in two stretches of note prose. And ✅ **the pointer
check the specs cite is now in this repository** — `tools/check-doc-citations.mjs`, committed
2026-09-09 and wired into both pipelines (§5.2 item 1, now closed; it was **nine** specs, not five).

## 8. Verification

* **What a next shift can actually run**, from the repository root:
  `node tools/check-vocabulary.mjs`, `node tools/check-doc-links.mjs`, `node tools/check-gate-tally.mjs`.
  Those three are the pure-Node guards that need no build. The dependency review and the coverage floors
  need a reactor build first; the bill of materials needs a staged bundle.
* ⚠ **Stage a new document before trusting a green vocabulary run.** The guard enumerates the index, so an
  unstaged file is invisible; `git add -N` is enough.
* 🔴 **The coverage guard's green is not evidence** until it has a module floor (§5.2 item 2). Check the
  module count it prints before believing a percentage.
* **Falsify, don't read** — four probes, each aimed at a claim in this file. Empty a guard's scope and
  confirm it exits non-zero rather than reporting clean. Add a banned synonym to the root instruction file
  and confirm nothing fires. Run the coverage guard with one stale build directory and watch it pass. And
  cut a throwaway version tag to make the release path execute for the first time, which is the only way
  to learn whether the packaging script works on Linux.
