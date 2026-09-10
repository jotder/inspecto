# Post-consolidation plan — eight sprints

> ⛔ **ARCHIVED 2026-09-10 — provenance only, never maintained.** Sprints 1–7 all closed; **Sprint 8+ is
> owned by `docs/BACKLOG.md` §0 and `docs/superpower/enterprise-scale-out-plan.md`, not by any plan
> document.** Read the sprint outcomes below for what each one actually found; read the board for what is
> open.
>
> **Its durable lessons are `docs/PROJECT_NOTES.md` §4:** 🔴 a written-down finding records the **instance,
> not the class**, so its number is a lower bound — all seven of Sprint 7's cells were scoped from a board
> row and **every one undercounted, in the same direction**; and ⚠ a row goes **stale in both directions**,
> because nobody re-reads it when they fix the thing it describes.

> **Status 2026-09-10: Sprints 1–5 DONE; Sprint 6 is steady state; Sprints 7–8 ADDED by operator decision
> (2026-09-10) — one bounded documentation closeout, then pure implementation from a ranked queue. Every
> operator decision this plan ever carried is closed (see the last section). This plan archives when Sprint 7
> closes; Sprint 8 is owned by `BACKLOG.md` §0 and the signed scale-out plan, not by this document.**
>
> *(Original status: Sprints 1 and 2 DONE 2026-09-09; Sprints 3–6 proposed.)* Written the shift the seventeenth capability spec landed
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
> ✅ **ALL FIVE CLASSES CLOSED 2026-09-09.** The last two went the same way as the first three — by
> measuring before designing, and both times the obvious design was the wrong one.
>
> **`SPEC-COUNTS-1` (a count is stated N ways).** `tools/check-doc-counts.mjs`, wired into `ci.yml` +
> `.githooks/pre-push`, falsified in five directions. 20 statements across 11 docs are now DERIVED from
> the contract that owns them. 🔴 **The root cause was the noun, not the arithmetic**: "node types"
> denoted three sets (30 in the enum / 11 with attribute specs / 16 recipe entries), so no single number
> could be right and nobody had written that down. 🔴 **The prose-scanning design was built as a census
> FIRST and measured unshippable**: 14 matches over 238 docs, whose "failures" were line references and
> deliberate correction notes, while MISSING real phrasings. A number in prose is indistinguishable from
> a line number. ⛔ `job types` and `maintenance tasks` are deliberately excluded — both totals are
> edition-dependent, so asserting one number would be a falsehood.
>
> **The served descriptor (the trigger vocabulary).** `TriggerVocabularyContractTest`, 6 tests.
> 🔴 `ExpressionDecl.TriggerKind` turned out to be a **parallel hand-maintained list that only decorates
> the published descriptor** — referenced nowhere else in the engine bar one `ON_SIGNAL` import, while the
> dispatch decides a trigger from config predicates and a literal `"manual"`. Now pinned to `JobConfig`'s
> record components by reflection, and to the binding glossary, parsed.
>
> The call-site-audit row was already discharged for the transform guard in Sprint 1.
>
> ⚠ **Two things the sprint taught that outlast it.** First, **measure the obvious guard before building
> it**: three of the five designs died on measurement — the citation allowlist, the prose count scanner,
> and asserting one number for an edition-dependent set — and each would have shipped as mostly exemption.
> Second, **a mutation that does not compile proves nothing**: two of the descriptor mutations were caught
> by the compiler, so those tests were never exercised until the mutations were redesigned to compile.

| Class | Instances found | The one fix |
|---|---|---|
| 🟡 **A generated artifact is authoritative to its consumer and unverified by its producer** — **assertion DONE 2026-09-09, one decision owed** | the bill of materials (fixed this week); the served API contract documents 19 paths / **24 operations against 266 live registrations = 9.0 %** (measured; the "6 % of ~332" was a unit error) with a test that could not see the gap | **DONE:** `ApiContractTest.openApiCoverageOfTheLiveSurfaceIsMeasuredAndRatcheted` measures live → doc, **prints the figure every run**, ratchets the documented counts and floors the live one. Mutation-proven both ways. ⛔ **Owed to the operator, not to a guard:** document the rest, or adopt exemplar coverage deliberately |
| ✅ **A hand-mirrored map drifts** — **CLOSED 2026-09-09** | four instances; the live one carried two keys where the server had three | **DONE:** `pipeline-editable.ts` carries `fields`, and `MapNodeKeyContractTest` **parses that TypeScript** to hold both sets against the Java ones. Mutation-proven in both languages. Not a fifth hand-edit |
| ✅ **A count is stated N ways** — **CLOSED 2026-09-09** | node types **5** ways, maintenance tasks **5**, job types **4**, processors **3**, transform functions **3**, triggers **4** | **DONE:** `tools/check-doc-counts.mjs`, wired into `ci.yml` + `.githooks/pre-push`, falsified in **four** directions. 20 statements across 11 docs carry a `<!--count:ID-->` marker and are **derived** from the owning contract at run time. 🔴 The root cause was the **noun**, not the arithmetic: "node types" denoted three sets (30 roster / 11 with attribute specs / 16 recipe entries), so no single number could be right — marker ids name the SET. ⛔ `job types` and `maintenance tasks` are deliberately excluded: both totals are edition-dependent, so one number would be a falsehood. ⚠ The prose-scanning design was built as a census FIRST and measured unshippable — 14 matches over 238 docs, all false positives (line numbers), while missing real phrasings |
| **A guard is absent from one call site** | the transform guard: ten call sites, zero on any save path | Audit the **call-site list**, not the rule list. Ask which paths a guard does *not* sit on |
| ✅ **A served descriptor drifts from its own dispatch** — **CLOSED 2026-09-09** | the maintenance descriptor (Sprint 1); the trigger vocabulary in the binding glossary | **DONE:** `TriggerVocabularyContractTest` (6 tests, 0.09 s). 🔴 The grounding found worse than a stale glossary: `ExpressionDecl.TriggerKind` is the vocabulary the server PUBLISHES in every parameter descriptor's `availableIn`, and it is referenced **nowhere else in the engine** bar one `ON_SIGNAL` import — the real dispatch decides a trigger from config predicates (`hasCron()`, `hasSignal()`, `onPipeline()`) and a literal `"manual"`. A fifth job trigger could be added and the served vocabulary could never mention it, with nothing failing. Now derived: the enum against `JobConfig`'s record components by reflection, and **both vocabularies against `GLOSSARY.md`, parsed** — the same technique that closed the hand-mirrored-map class, because a binding glossary that is hand-typed is a mirror like any other. ⛔ No trigger name is written down in the test. Mutation-proven in four compiling directions |

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

> ### ✅ DONE 2026-09-09 — the operator took the decision, and five plans are archived
>
> **The decision: the looser reading.** Once a plan's decisions are signed, its design is distilled and its
> open items are filed, the plan is **provenance** — archive it, and the **board** owns the remainder.
>
> ⚠ **Archiving was never the work; distilling was.** Surveying the ten plans against their own status
> lines had produced "zero are archivable", and that survey was wrong in one direction and incomplete in
> the other:
>
> * 🔴 **`agt-6-plan.md` was archivable under the STRICT reading too, and had been for 43 days.** Its
>   stated blocker was the `kpi_report_builder` host + AGT-6b + a cosmetic defect; two of the three were
>   discharged on 2026-07-28, the day after its last edit. The host shipped as an **adoption**, inverting
>   the plan's own premise. Four documents — including a board row that said of itself *"this row is what
>   keeps `superpower/agt-6-plan.md` out of the archive"* — carried the refuted claim for six weeks.
>   ⛔ **A plan's stated reason to stay is a HYPOTHESIS.** I reported it as fact.
> * 🔴 **The looser reading's own precondition — "design distilled" — was NOT met for four of the five.**
>   Roughly fifteen items existed only in the plans, and every one is now in the current tier:
>
> | Plan | What would have gone dark |
> |---|---|
> | `deployment-topology-plan.md` | six tables — sizing, failure→tier, **the signed D6 RPO/RTO service levels**, nine preflight rows, **VER-1…VER-12**, phase sequencing + the T4 promote order. Three `SCR-*` acceptance criteria were defined as pointers *into* the plan |
> | `compliance-certifications-plan.md` | **the C1–C6 workstream definitions** the controls matrix keys its whole ledger on, and **a signed operator decision** (Q7, FedRAMP Moderate) that the current tier still described as an open assumption |
> | `completeness-kpi-plan.md` | a signed **refusal** (inventing an expected row count), the second silent-zero trap, K2's two structural limits, all four verify gates — plus **two Java javadocs** citing the plan as their design authority |
> | `parser-field-tiers-interview-plan.md` | the **pre-agreed analysis rule**, whose entire force comes from being agreed *before* the session — and a board gate that closed on this file's own path, which archiving would have made uncheckable |
>
> ⚠ **Two guards with different scopes each caught half of one move**, and neither could have caught the
> other's half: the citation guard flags dead paths but exempts `superpower/` **as a source**, so it was
> blind to a sibling plan's links; the doc-link guard has no such exemption and caught them. Java javadoc
> and an active plan's own citations are outside both — the fourth instance in this programme of
> **a guard's scope being a silent exemption**.
>
> ⚠ Each archived plan carries an **ARCHIVED banner** naming where its durable content went and — for the
> DRAFT — enumerating the claims the current tier refutes, so an unratified proposal cannot be mistaken
> for a decision of record. ⛔ Archived does not mean done: the completeness KPI is still on hold, the
> interview is still owed, and Phases 0–5 are still unbuilt.
>
> **Remaining in `superpower/` (five entries, all genuinely in flight):** `pipeline-spec.md` +
> `pipeline-waves-drain-plan.md` (they move together when row 15 closes, and row 15 needs a **release**),
> `elt-final-amendment-plan.md` (Phase 6's deletion half is release-gated), `docs-consolidation-plan.md`
> and this file — plus `assets/` and `design/` working files. ⛔ This file archives when its last sprint
> closes.

**Goal:** plan step 7 — its stated precondition (authoring the deployment-topology spec first) **is** met:
that shipped as area #15 (`896b2e4b`). What is not met is the lifecycle condition above.

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

No exit criterion — this is the resumption of normal service, not a project. ⚠ In practice Sprint 6's first two
picks (`quality.schema.drift`, then `MAPPING-GEN-1`, `STANDARD-BUNDLE-1`, `SPACES-GOVERNOR-1`) shipped on 2026-09-10,
and the same day's decision round changed what comes next — hence Sprints 7 and 8 below.

---

## Sprint 7 — Documentation closeout (one shift; decided 2026-09-10)

**Goal:** end the documentation programme so that nothing after it is documentation work. The operator chose this
over interleaving and over deferral: *one bounded docs sprint now, then pure coding.*

| # | Work | Its stated exit |
|---|---|---|
| 7.1 | [`docs-consolidation-plan.md`](docs-consolidation-plan.md) **step 1** — the ~16 remaining authority citations into archive files (`GLOSSARY.md`'s four rationales, `grammar-config.md:126`, the okf "full phasing" / "grounded refutation" delegations, the completeness-kpi pointer) | *no current-tier doc cites an archive file as its authority; link guard green* |
| 7.2 | **step 1b** — the remaining 5 of 7 ORPHAN archive docs land in their area specs (`modularization-optimization` C2/C4/C6 → `editions/` §6 · `snazzy-painting-platypus` → `surfaces/` §6 · `system-maintenance-plan` COULD list → `observability/` §5 · `frontend-review-and-completion-plan` → the `angular-ui` skill · `claude-usage-audit` → none, meta) | *each ORPHAN has a destination; `BACKLOG.md` §6 no longer cites an archive file as the sole home of C2/C4/C6* |
| 7.3 | `SPEC-GLOSSARY-1` — `Segment`, `Control`, `Evidence` defined; the two `Case`s and the two `Stream`s split; the PII/INTERNAL classification vocabulary owned | *`GLOSSARY.md` is binding; one word → one concept; vocabulary guard green* |
| 7.4 | `SPEC-MOCKRESIDUE-1` — the 20 current-tier docs that still describe the deleted mock backend, incl. the two that tell a contributor to edit a file that does not exist | *`git grep` for the mock layer's names returns only the archive tier and the removal record — the count is the acceptance test* |
| 7.5 | `SPEC-PLANSTALE-1` remainder — the pipeline spec's "what actually runs" (five dead classes, "a new Step type cannot be added") corrected before it moves | *a plan's header agrees with its own later rows* |
| 7.6 | **Archive the three release-gated plans** (`pipeline-spec.md`, `pipeline-waves-drain-plan.md`, `elt-final-amendment-plan.md`) — operator 2026-09-10: after a **distillation diff** against `pipeline-execution.md` / `pipeline-authoring.md`, repointing the 7 inbound citations; the board keeps row 15 | *`superpower/` holds the signed scale-out plan and nothing else once 7.7 runs* |
| 7.7 | ✅ **DONE 2026-09-10** — both plans closed, lessons distilled into `PROJECT_NOTES.md` §4 and `okf/capabilities/tooling/tooling.md`, both `git mv`'d with an `INDEX.md` row each | *the in-flight tier is exactly one plan; `INDEX.md` matches* |

⛔ **Not in Sprint 7:** `SPEC-ORPHANPAGE-1` stays P3 (an undocumented pane is a smaller problem than a wrongly
documented one); consolidation step 10 is CLOSED as *no deletion* — the archive is provenance. `STREAM-CONSUMER-1`'s
design pass is engineering, not documentation, and belongs in Sprint 8.

### Sprint 7 — outcome (2026-09-10): ALL SEVEN CELLS DONE

| # | Shipped | What the grounding changed |
|---|---|---|
| 7.1 | `ecb6e929` | The "~16 authority citations" were **19 doc sites + 5 SOURCE files**. `GLOSSARY.md`'s "four rationales" were **eight**; the completeness-KPI pointer had been **resolved the day before** (this plan's own row was stale); and six `design of record` delegations were never named, one of them a **section heading**. 🔴 **A fifth instance of a guard's scope being a silent exemption:** the citation guard reads markdown only, so three javadocs pointed "current knowledge" at a page containing **zero** mentions of their subject — making the archived plan the only authority a reader could reach |
| 7.2 | `747c8ab2` | **Four of the five ORPHANs had an item that was refuted or half-shipped.** `C2` was **half shipped 20 days before the recount that called it untouched**; the maintenance list's first COULD had **already shipped and was documented twice at the destination**, so landing it as deferred would have made one file contradict itself; the console plan was ~80 % absorbed already; and the frontend review's **boundary lint was never built** — no ESLint config is tracked at all |
| 7.3 | `84fe8e42` | **Three of the five words had MORE senses than the row claimed** (`Control` five, `Stream` five, `Case` four): a two-way collision is the floor, not the finding. Two factual errors fell out — `CMP-01…03` "shipped controls" are **eight edition FEATURE rows**, and the Watermark entry claimed completeness of a Catalog Stream when the code keys it on the **output table**. The `Case` rename is code over four published routes, so it was **filed, not applied** |
| 7.4 | `cd15d4e7` | **27 documents, not twenty** — and the residue reached two buyer-facing pages, an **agent definition**, and a **status VALUE** in the requirements legend. 🔴 The row's own "two documents" clause was **stale**; two *different* instruct-to-edit-a-missing-file cases were live, one of them **120 lines below its own page's correction banner**. The acceptance test as stated was unmeetable and is refined in the row |
| 7.5 | `04262b3d` | Four dead classes, not five — dead for **ten days** inside the document whose own §12 records the rename. The "new Step type" claim was wrong in **every** clause. 🔴 And the worst defect the row never named: **a false ✅ on a release gate's precondition** |
| 7.6 | `83c39a3c` | **31 items lived only in the three plans**; four became board rows and two missing steps joined row 15. The diff caught the false converter tick, **D1 having no current-tier row at all**, and the **Pipeline Document having no concept home**. Three `GLOSSARY` citations were pinned to plan **line numbers**, which a `git mv` does not fix |
| 7.7 | this commit | Both driving plans closed and archived; the in-flight tier is **one** plan |

🔴 **The one pattern behind all seven.** Every cell's stated scope was an **undercount**, and in the same
direction: 16→19+5 · 20→27 · 5 words→3 with extra senses · 5 classes→4 but a worse third defect · 5
ORPHANs→4 with refuted items. A finding written down when it is noticed records **the instance**, not the
class — so a row's number is a lower bound, and the sweep is what finds the size. ⚠ Twice the row was also
**stale in the other direction** (7.1's completeness pointer, 7.4's two-documents clause), because nobody
re-reads a row when they fix the thing it describes.

**First command:** `node tools/check-doc-links.mjs` then `git grep -n 'archived-documents' -- 'docs/okf/**/*.md' docs/GLOSSARY.md | grep -v 'ARCHIVED\|provenance'` — the step-1 citations are the ones that remain.

---

## Sprint 8+ — Pure implementation (decided 2026-09-10; owned by the board and the signed plan)

**Goal:** code only. The operator ranked the queue; each item names its proof before it starts.

| Rank | Work | Owner of record | Proof it is done |
|---|---|---|---|
| 1 | **Scale-out spikes S1–S5** ([`enterprise-scale-out-plan.md`](enterprise-scale-out-plan.md) §10, each ≤ half a day). Order by what runs here: **S2** (`PartitionSinkWriter` reveal seam) and **S4** (two `ControlApi` in one JVM — which statics collide) are code-reading, runnable offline; **S3** (HikariCP in the `-o` cache) is a one-command check; **S1** (two DuckDB processes, one MinIO bucket, one `ducklake:postgres:` catalog) and **S5** (pg_duckdb on a Postgres) need MinIO + Postgres | plan §10 | each spike's result written into the plan's §3 seam it tests; a failed S1 reopens D4 |
| 2 | **Phase A — shared state** (plan §5.1/§5.2): `-Dinspecto.topology=partitioned` profile; every `*.backend` on Postgres with fallback a boot failure; `events.backend=db`; the pool (P1+P2); **the lease** — `RunLeaseContractTest` first (§7), then `PostgresRunLease`, `lastRunAtMs` on the lease row, `JobService` cron arming through it. ⚠ **This is Standard's T4 DR deliverable (D8)** — ships in the Standard bundle, never behind `inspecto-policy` | plan §5, `editions.md` §3.9 T4 | the §7 contract test green on two JVMs sharing one Postgres; T4's promote runbook needs no manual step |
| 3 | **Phase B — Space→pod assignment** (plan §5.3): static partition map; no pod polls an inbox it does not own; `SPACES-GOVERNOR-1` already Space-keyed the admission state | plan §5.3 | two pods, disjoint Spaces, one Postgres: every batch committed exactly once |
| 4 | **Phase C — the shared lakehouse** (plan §5.4): `dirs.database` as `s3://…`, `CatalogCommit` as visibility, `RenameReveal` only on local paths, `DuckLakeRegistrar` fatal (D10), object-store-aware containment; then **D13's external query surface** if S5 passed | plan §5.4 | a file written by pod 1 is queryable from pod 2 exactly at commit; a half-written file never is |
| 5 | **The five `CONSUMER-PAIRS-1` rows** — `CLIENT-HALVES-1` (If-Match · permissions[] via the interceptor · SSE) · `EXPECTATIONS-UI-1` (a Must) · `STUDIO-HALVES-1` · `AGT-ARTIFACT-1` · `RETIRE-HALVES-1`. Single-node, mostly UI; **the filler whenever a spike is blocked on infrastructure** | `BACKLOG.md` §3 | each spec's §2 row flips from "no client" to shipped, or the route is gone |
| 6 | Sprint 6's product partials by name (`sink.api.webhook` → `sink.notify.email` → `transform.diff.compare`), `OPENAPI-GEN-1`, `STREAM-CONSUMER-1`'s design pass | `BACKLOG.md` §3/§4 | the processor catalog flips; the route table generates the OpenAPI skeleton |

⛔ **Two rules carried forward from Sprints 1–5, because they were the shift's most expensive lessons:** a change ships
with a **falsified** proof (the mutant that compiles and fails on the intended assertion — `-pl X -am`, never `-pl X`),
and a reactor total is **re-summed from the log** by the shift, never taken from a verify agent's report.

---

## What needs the operator, not a shift

✅ **Nothing, as of 2026-09-10.** Every decision this section ever held is closed, by the operator, with the
verdict recorded where the work lives:

| Decision | Closed | Where |
|---|---|---|
| `MAPPING-SPELLING-1` — should `create-schema` emit `mapping.fields[]`? | 2026-09-10 — yes; **shipped the same day** (`b77b0826`, all THREE generators) | `configuration.md` §2 |
| its sidecar half | 2026-09-10 — no, inline is the norm | `BACKLOG.md` §6 |
| Enterprise bundle self-identifying as Enterprise for a Standard customer | 2026-09-10 — a distinct Standard bundle; **shipped** (`3f0d3a93`) | `editions.md` §3.5/§3.7 |
| `CONSUMER-PAIRS-1` — the ten server halves with no client | 2026-09-10 — **per row, one sitting**: 7 adopt · 1 keep-as-API · 2 retire | each owning spec's §2; `BACKLOG.md` §3 five grouped rows |
| the three release-gated `superpower/` plans | 2026-09-10 — archive now, after a distillation diff | `docs-consolidation-plan.md` step 7 |
| archive residue (step 10) | 2026-09-10 — keep as provenance, no deletion | `docs-consolidation-plan.md` step 10 |
| the doc-hygiene remainder (steps 1/1b, `SPEC-GLOSSARY-1`, `SPEC-MOCKRESIDUE-1`, `SPEC-PLANSTALE-1`) | 2026-09-10 — **one bounded docs sprint now, then pure coding** | the sprint plan update that follows this section |

🔴 **What this section taught.** Three of its entries were resolved by *measurement* before they could be decided
(`MAPPING-SPELLING-1`'s premise was half wrong; `CONTRACT-ORPHAN-1`'s premise was a false negative; the register's
`PATH-2` refusal was not a refusal). A decision put to the operator on a wrong premise is worse than an undecided one.
Ground first, ask second — and read a free-text answer against the QUESTION before recording it (an operator's
answer to "lease mechanism" turned out to be a query-surface idea, and became its own decision, D13).
