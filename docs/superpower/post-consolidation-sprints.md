# Post-consolidation plan — six sprints

> **Status: Sprints 1 and 2 DONE 2026-09-09; Sprints 3–6 proposed.** Written the shift the seventeenth capability spec landed
> (`35cc8579`). It sequences what the consolidation left behind: the remaining steps of
> [`docs-consolidation-plan.md`](docs-consolidation-plan.md) (steps 5–9, whose exit criteria are already
> written there and are **reused verbatim** below, never re-invented), plus the ~215 findings the seventeen
> specs surfaced and nobody has filed.
>
> ⚠ **A "sprint" here is one shift**, because that is the unit this sandbox actually works in
> (`CLAUDE.md` — session-per-shift, handoff at the end). Regroup them into calendar sprints freely; the
> ordering is what matters, not the packaging.

## 0. The one fact that sets the order

**`BACKLOG.md` §0 says P1 is drained with one queued row. The seventeen specs carry ~215 findings with no
row at all.**

| Where | Count |
|---|---|
| `BACKLOG.md` queued P1 | **1** (`SBOM-RESOLVE-1`) |
| `BACKLOG.md` P2 / P3 | 22 / 14 |
| Numbered findings in the specs' §5.3 lists | **~215** |

Top contributors: `editions` 33 · `pipeline-authoring` 31 · `pipeline-execution` 24 · `compliance` 24 ·
`assistant` 22 · `surfaces` 21 · `tooling` 18 · `studio` 17.

So the board is honest about what it knows and blind to what the consolidation learned. **Everything below
is ordered by that**: correct what actively misleads, then file, then attack the *classes* rather than the
instances, then close the programme.

⛔ **The binding triage rule, from `BACKLOG.md` §0 — do not relax it:** *a P1 must name the file it
changes.* A finding that cannot is a **decision** (§1) or a **design** (P2). Several of the 215 will
demote on contact; that is the rule working, not a problem.

---

## Sprint 1 — Correct what misleads

**Goal:** nothing in the repo tells an operator or a release decision something false.

Five items, each naming the file it changes. First act of the shift is a 30-minute skim of all seventeen
§5.3 lists for anything else red that names a file — cheap insurance against ordering these before triage.

| # | Item | Names | Why first |
|---|---|---|---|
| 1 | **`SBOM-RESOLVE-1`** — the bill of materials cannot generate on a clean runner, so the first tag fails at packaging | `release.yml` | Already the only queued P1, and it blocks a release outright |
| 2 | **The served maintenance descriptor** — advertises four tasks Personal refuses, hides seven shipped ones, and drives the authoring form | `JobService.java` | Operator-visible on every Personal install. Fix by **deriving the list from the dispatch**, plus a contract test — not by editing the string |
| 3 | **`SqlGuard` is on no save path** — author SQL with a data-definition statement or a file-reading function saves and arms cleanly, failing only at run | `PipelineEditable.java`, `RecipeCompiler.java` | Fail-late, not fail-open, so not a security hole — but the refusal arrives at the worst moment, and no test covers it |
| 4 | **Replay's bound is unstated** — the lookup is an in-memory capped map, lost on restart, while `CP-03` marks replay green in all three editions | `EDITIONS.md`, `observability.md` | The **caveat** is a doc fix and belongs here. *Persisting* the lookup is a design → P2, and the observability spec also blames the wrong flag |
| 5 | **Row 15's true readiness** — this session corrected the row that named a non-existent class, but the real blocker is untouched: §6 step 2, the parity gate through the compiled-recipe path | `pipeline-waves-drain-plan.md` §2.3 | A release-gated deletion whose gate nobody can currently evaluate |

**Exit:** items 1–4 shipped with a test each; item 5 has a written verdict on whether the parity gate holds.

### Sprint 1 — outcome (2026-09-09)

**The skim earned its keep: two items outranked things queued above, and both named their file.**

| Item | State |
|---|---|
| `SBOM-RESOLVE-1` | ✅ `ad62d5a0` — `release.yml` installs the reactor under `-Pedition-enterprise` before packaging. The **profile** is load-bearing: the nine edition modules are profile-scoped, so a plain install leaves `inspecto-ops` absent, and that is the artifact Enterprise fails on. `ci.yml` already recorded this reasoning for the dependency guard; release.yml never got it |
| **The coverage guard's missing scope floor** *(added by the skim)* | ✅ `4be5c791` — it failed only on ZERO reports, so one stale module CSV passed as repo-wide: measured, it printed *"every floor met"* over **1 module / 427 instructions**. Now `MIN_BACKEND_MODULE_REPORTS = 20` against 29 modules with tests. Falsified both ways on one tree |
| The served maintenance descriptor | ✅ `f0e4dee2` — derived from the dispatch. `MaintenanceTaskContractTest` re-parses the switch's own `case` labels; mutation-proven (removing one id failed 3 of 5 tests, naming it) |
| `SqlGuard` on the save path | ✅ — one helper, both surfaces (graph save and Recipe compile), `SQL_STEP_REFUSED` carrying the guard's own message. Mutation-proven: removing the two call sites failed **exactly** the two integration tests and left the seven helper tests green, with the message *"Expected PipelineCompileException to be thrown, but nothing was thrown"* — the defect stated as a test |
| Replay's unstated bound | ✅ — `EDITIONS.md` `CP-03` gains the caveat, and the observability row's **404 is un-misattributed**: it was grouped with the `-Djobs.backend` projection routes, and the two failure modes are opposite |
| Row 15's true readiness | ✅ verdict recorded in `pipeline-waves-drain-plan.md` §2.3 — see below |

**Row 15's verdict.** Gate re-run: newest master-ancestor tag is still `v3.11.0`, so it holds. 🔴 The waves plan's own conclusion was **stale against its own table** — it claimed the lane flag "was
never built" two lines under a row recording it BUILT 2026-09-02, so its "two independent reasons" are now
**one**: step 4, no release has carried a flagged legacy path. ⚠ Step 2 is narrower than it reads: the
fixture round-trip sweep is green, but that proves projection parity, **not** the full suite executing through
the compiled path. And the blocker the backlog cited was a class that does not exist.

⚠ **Carried out of Sprint 1:** the packaging script's two Windows-shaped defects (32 destinations using a
literal backslash, and the script has never run on Linux). It names its file, but it **cannot be verified from
this sandbox** — proving it needs a Linux runner, which makes it a different shape of work from the rest of
this sprint. It belongs with the first-tag work, not with a shift that can only edit it blind.

---

## Sprint 2 — File the 215

**Goal:** every spec finding has a disposition. No finding lives only inside a capability doc.

Not a transcription job. The work is **deduplication**, because the same defect appears in several specs
from different angles:

* the seven **"server half shipped, no client consumer"** instances → one product conversation, one row
* the **counts stated N ways** family → one row per artifact, not per document
* the **hand-mirrored map** family → one row (see Sprint 3)
* the **2 GB memory default** → one row; it has five framings across eight documents

Then apply the P1 rule to each survivor: name the file, or demote to §1 (decision) or P2 (design).

**Exit (as refined — see below):** every **cross-cutting** finding carries a `docs/BACKLOG.md` row; every
area-specific one is acknowledged as living in its own spec's §5, which is its correct home.

### Sprint 2 — outcome (2026-09-09)

**Measured, not estimated: 217 findings across all 17 specs, 213 of them open.** The “~215” above was right,
though it took four passes to extract cleanly — the later specs use numbered lists under a `§5.x UNTRACKED`
heading, the earlier eight use **tables** under an unnumbered `### UNTRACKED`, and one uses neither shape.

🔴 **The exit criterion was wrong and is refined here.** It demanded a board row *or* a strike for every
one of the 213. That is the wrong target: **not every finding needs a board row.** A finding that is
area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 **is already filed**
— copying it onto the board gives it two homes and one of them goes stale, which is the very failure
`SPEC-STALEREF-1` exists to catch. So: **the board holds what crosses areas; a spec holds what belongs to
one.** Roughly 80 of the 213 are area-specific design and stay put, with a banner in each spec saying so.

**Twelve rows filed** — one operator decision and eleven work rows:

| Row | Section | Holds |
|---|---|---|
| `CONSUMER-PAIRS-1` | §1 decision | 🔴 **Ten** shipped halves with no counterpart — not seven. The plan under-counted because it missed the UI-half cases and the one **inverse** (a live client consumer with no producer) |
| `SPEC-STALEREF-1` | §4 | **23 stale paths and dead citations — the largest family, and one this plan did not predict.** Twenty-plus dead class names survive the Consignment rename, five of them in an active plan's “what actually runs” section |
| `SPEC-COUNTS-1` | §4 | Eight facts counted two to six ways; the *generated* artifact was right every time |
| `SPEC-NOPROOF-1` | §4 | Six Musts with no automated proof, including a scheduler with **no test class at all** |
| `SPEC-DEADSEAM-1` | §4 | Four declared seams with no implementation or caller — keep-or-delete verdicts, not builds |
| `SPEC-GREENCELL-1` | §3 | Five board cells green over something absent or bounded |
| `SPEC-AGT-EDITIONS-1` | §3 | Seven rows claim edition `All` where no bundle carries the code |
| `SPEC-DEPLOY-ROWS-1` | §3 | Fourteen deployment items with no board row at all |
| `SPEC-MOCKRESIDUE-1` | §5 | Twenty current docs describing a backend deleted 2026-08-31 |
| `SPEC-PLANSTALE-1` | §5 | Active plans stale against their own content |
| `SPEC-GLOSSARY-1` | §5 | Five load-bearing words undefined, or meaning two things |
| `SPEC-ORPHANPAGE-1` | §5 | ~20 shipped surfaces with no concept page (P3 on purpose) |

⚠ **The per-spec banners attribute by FAMILY, not per item.** Keyword attribution is a heuristic and it
produced at least two marginal matches on inspection, so the banner says so and tells a reader to check the
row before acting. Shipping false precision here would have manufactured exactly the stale-citation class
`SPEC-STALEREF-1` was filed to kill.

🔴 **A mistake worth recording:** the row-insertion script truncated `docs/BACKLOG.md` to **zero bytes,
twice.** Opening a file for write truncates it *before* the write, so a payload that fails to encode destroys
it — here a lone surrogate escape (the UTF-16 half-pair spelling of an emoji instead of the full code-point
spelling). Both times it was restored intact with `git checkout --`, because every prior edit was committed;
nothing was lost. **The fix is structural: encode the payload FIRST, write a temp file, then replace.**
⛔ Never open a tracked file for write before the bytes are known good — and note that this very
paragraph hit the same trap on its first attempt, where the atomic write then saved the file.

---

## Sprint 3 — Kill the classes, not the instances

**Goal:** the failure *classes* that produced most of the 215 stop being able to recur. Highest leverage
sprint in the plan: one guard each, instead of N fixes.

> ### Progress — the enabler + two of the five classes DONE 2026-09-09 (one leaves a decision owed)
>
> **`SPEC-STALEREF-1`'s class is closed.** `tools/check-doc-citations.mjs` is committed and wired into
> `ci.yml` + `.githooks/pre-push`, falsified in both directions. ⚠ **That family is not one of the five
> rows below** — it is the *enabler* the board row named, and it was taken first because nine capability
> specs' §8 sections instructed a next shift to run a checker that existed only in a session scratchpad.
> It found **152** stale citations against the 23 counted by hand (39 dead paths, 113 dead type names,
> 37 files), all repaired in the same commit.
>
> 🔴 **The transferable finding, and it changes how the five below should be attempted.** The first
> design of that guard was the obvious one — *every backticked CamelCase name must exist in the tree* —
> and measurement killed it: 4,654 citations, 56 distinct absences, and the large majority legitimate
> (third-party types, deliberately-unbuilt designs, renames recorded on purpose). It would have shipped
> as roughly 45 entries of allowlist and 11 of rule, and **a guard whose scope is mostly exemption is a
> recorded failure of this repo three times over.** What made it shippable was narrowing to an invariant
> the repository states about *itself* — the committed rename map, parsed rather than mirrored — plus one
> allow rule reused by both checks: a citation is fine when the line records the history (names the
> replacement, or states the absence). **Look for the self-stated invariant before writing any of the
> five guards below; if a guard needs a waiver list to go green, it is measuring the wrong thing.**
>
> **Then the second row of the table, "a hand-mirrored map drifts", also closed** — and it validated that
> rule. `MAP_AUTHORED` was live-broken: `pipeline-editable.ts` carried `['columns','rules']` against the
> server's three, so for four days the editor **refused a key the server accepts** and dropped it on the
> round trip, advertising the wrong accepted set in its own refusal message. The fix is the pin the plan
> asked for and not a fifth hand-edit: the Java set is the source of truth and `MapNodeKeyContractTest`
> parses the TypeScript to hold it there. ⚠ A cross-language pin has to read the other side's source —
> there is no shared artifact for two short lists, and inventing one costs more than it saves.
>
> **Then the first row, "a generated artifact is unverified by its producer", got its assertion.** Every
> other check in `ApiContractTest` ran doc → live, so the contract covering a fraction of the surface was
> invisible to the suite named as its enforcement. `openApiCoverageOfTheLiveSurfaceIsMeasuredAndRatcheted`
> now runs live → doc: 19 paths / **24 operations against 266 live registrations + 73 absent-module stubs
> = 9.0 %**, printed pass or fail. 🔴 **The measurement immediately corrected the finding that asked for
> it** — "6 % of ~332" divided documented *paths* by *(method, pattern)* registrations, a `SPEC-COUNTS-1`
> instance hiding inside a `SPEC-STALEREF-1` sibling. ⚠ A per-route comparison is unavailable (patterns vs
> templates), so the guard ratchets and floors instead of pinning. 🔴 **And one of my own premises was wrong,
> caught by running it:** I justified the floor as "the live count is edition-dependent". Measured under
> `-Pedition-enterprise` it is **266, identical to the default reactor** — optional modules depend on
> `inspecto-processor` and the core declares none of them, so a test in the core can never load one. The
> figure is therefore the **core** surface and an **upper bound** on a shipped Enterprise bundle's coverage.
> ⛔ **The posture decision — document the remaining surface, or adopt exemplar coverage deliberately —
> is the operator's and is still owed. A guard measures; it must not decide.**
>
> **Still open: two of the five** — the count guard (`SPEC-COUNTS-1`) and deriving the remaining served
> descriptor (the trigger vocabulary in the binding glossary). The call-site-audit row was already
> discharged for the transform guard in Sprint 1.

| Class | Instances found | The one fix |
|---|---|---|
| 🟡 **A generated artifact is authoritative to its consumer and unverified by its producer** — **assertion DONE 2026-09-09, one decision owed** | the bill of materials (fixed this week); the served API contract documents 19 paths / **24 operations against 266 live registrations = 9.0 %** (measured; the "6 % of ~332" was a unit error) with a test that could not see the gap | **DONE:** `ApiContractTest.openApiCoverageOfTheLiveSurfaceIsMeasuredAndRatcheted` measures live → doc, **prints the figure every run**, ratchets the documented counts and floors the live one. Mutation-proven both ways. ⛔ **Owed to the operator, not to a guard:** document the rest, or adopt exemplar coverage deliberately |
| ✅ **A hand-mirrored map drifts** — **CLOSED 2026-09-09** | four instances; the live one carried two keys where the server had three | **DONE:** `pipeline-editable.ts` carries `fields`, and `MapNodeKeyContractTest` **parses that TypeScript** to hold both sets against the Java ones. Mutation-proven in both languages. Not a fifth hand-edit |
| **A count is stated N ways** | node types **5** ways, maintenance tasks **5**, job types **4**, processors **3**, transform functions **3**, triggers **4** | Derive from the committed contract where one exists; where none does, one guard that counts. In every case the *narrative* doc was wrong and the *generated* one was right |
| **A guard is absent from one call site** | the transform guard: ten call sites, zero on any save path | Audit the **call-site list**, not the rule list. Ask which paths a guard does *not* sit on |
| **A served descriptor drifts from its own dispatch** | the maintenance descriptor (Sprint 1); the trigger vocabulary in the binding glossary | Derive the descriptor from the dispatch |

**Exit:** each class has a guard wired into a pipeline, and each guard is **falsified in both directions** —
proven to fail on a seeded defect and to pass clean. A guard that has never been seen to fail is not yet a
guard.
**First command:** `node tools/check-openapi-routes.mjs 2>/dev/null || ls tools/` — establish whether the
API-contract guard exists at all.

---

## Sprint 4 — Close the programme

**Goal:** the remaining consolidation steps. **Exit criteria are quoted from
[`docs-consolidation-plan.md`](docs-consolidation-plan.md) step table and are not restated here.**

| Step | Work | Its stated exit |
|---|---|---|
| **5** | Strip `REQUIREMENTS.md` to the cross-area rollup; the 19 oversized status cells move into their capability docs | *"every `<AREA>-n` ID resolves to exactly one capability doc; no ID orphaned"* |
| **6** | Reconcile the 15 contradiction clusters as §2/§5/§6 rows | *"each cluster has one answer; `STAKEHOLDER_OVERVIEW` §9/§10.3 no longer contradicts the build"* |
| **8** | Re-key `EDITIONS.md` + `FEATURE_INVENTORY.md` groups to the area IDs | *"each group heading carries its area ID; edition facts still stated once"* |
| **9** | Update `INDEX.md`, `okf/index.md`'s charter and `CLAUDE.md`'s doc-lifecycle | *"the tier's stated definition matches what it contains"* |

⚠ Step 5 is the one with teeth: seventeen specs now claim to be the requirement-of-record for their rows,
and `REQUIREMENTS.md` still carries the full status text for all of them. Until it is stripped, **every row
has two owners** — which is the exact condition the consolidation existed to end.

**First command:** `grep -c "now have an owner\|now has an owner" docs/REQUIREMENTS.md` — count how many
sections already carry an owner banner.

---

## Sprint 5 — Dissolve `superpower/`

**Goal:** plan step 7, **now unblocked** — its stated precondition was authoring the deployment-topology
spec first, and that shipped as area #15 (`896b2e4b`).

Its stated exit: *"all 20 untracked items filed; `superpower/` holds only working assets; the 50 inbound
links repointed."*

Order matters here and the plan says so: **`pipeline-spec.md` moves last** (ten citing documents) and only
together with the waves plan. Six plans currently hold decisions of record — archiving one before its
durable facts are distilled loses design, which is precisely the trap the deployment plan was rescued from.

⛔ **This file is itself in that tier.** It archives when its last sprint closes.

**First command:** `ls docs/superpower/*.md | wc -l && grep -rl "superpower/" docs --include=*.md | wc -l`

---

## Sprint 6 — Back to product

**Goal:** steady state. With the board trustworthy again, the ranked `§3` work is the queue: the **eighteen
partial** step processors (each a named product decision), the deployment phases 0–5, and the authoring
letters whose preconditions are discharged.

No exit criterion — this is the resumption of normal service, not a project.

---

## What needs the operator, not a shift

**One decision is pending and its premise is half wrong.** `MAPPING-SPELLING-1` asks whether the schema
generators should emit the new mapping spelling. Measured 2026-09-09:

* the generators write the legacy shape ✅ **but not** the type field — that is deliberately omitted, with a
  code comment saying so;
* **all 24 committed schemas are already on the new spelling** — zero carry the legacy one.

So the row conflates two migrations: the **spelling** change (already complete in the committed corpus) and
the **external sidecar** extraction (not started, and its only example in the tree is untracked). ⛔ Correct
the row before answering it, or the answer authorises work that is partly done and partly a different task.

**Two more calls that are product, not engineering:** whether an Enterprise bundle may keep
self-identifying as Enterprise when handed to a Standard customer (§editions), and whether the seven
"no client consumer" server halves get clients or get retired.
