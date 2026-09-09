# Post-consolidation plan — six sprints

> **Status: PROPOSED 2026-09-09, not started.** Written the shift the seventeenth capability spec landed
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
**First command:** `node tools/check-sbom-modules.mjs` — see what the peer session's new guard already
covers before touching item 1.

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

**Exit:** every numbered item in every spec's §5.3 either carries a `BACKLOG.md` row id or is struck in
place with a one-line reason. `grep -c UNTRACKED docs/okf/capabilities/*/*.md` tells you when you are done.
**First command:** `grep -n "UNTRACKED" -A3 docs/okf/capabilities/editions/editions.md` — start with the
largest list.

---

## Sprint 3 — Kill the classes, not the instances

**Goal:** the failure *classes* that produced most of the 215 stop being able to recur. Highest leverage
sprint in the plan: one guard each, instead of N fixes.

| Class | Instances found | The one fix |
|---|---|---|
| **A generated artifact is authoritative to its consumer and unverified by its producer** | the bill of materials (fixed this week); the served API contract covers **19 of ~332** routes with a test that cannot see the gap | A guard per generated artifact holding it against its own source of truth — the pattern the peer session just established |
| **A hand-mirrored map drifts** | four instances; the authored-key mirror is **live** today, carrying two keys where the server has three | Pin it verbatim, or derive one side. ⛔ Not a fifth hand-edit |
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
