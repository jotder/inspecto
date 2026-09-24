# Design — the X1 retry deferrals (operator cancel / retry-now, and `processing.retry`)

**Row:** `BACKLOG.md` §3 *EXECUTION-RESIDUALS X4 + X1 deferrals*, the **X1 half only**.
⛔ **X4 is NOT in scope and must not be started**: the row records that the operator refused its question —
the replay default is to be *scoped against a sandbox*, i.e. `PIPELINE-DRYRUN-1`, and both options are risky
to default to until a dry-run makes the choice observable.
**Status:** written 2026-09-16 as design only. **All four §5 decisions answered by the operator 2026-09-25** (each as recommended) and the affordance **BUILT** the same day — as-built facts in [execution-lanes](../okf/backend/pipeline-graph/execution-lanes.md) § *The COMMIT retry affordance*. The §2 `processing.retry` block was **also built** the same day (the `AcceptedConfigKeys` work §2 waited on had landed): see execution-lanes § *Per-pipeline `processing.retry`*. ⚠ Of the "two committed contracts" §2 names, only `node-attributes.contract.json` still exists — the step-types contract was deleted 2026-09-24 (`STEP-TYPES-DEAD-CLIENT-MIRRORS-1`). Open: the UI (Q3).

## 1. What exists today (grounded, not assumed)

`CommitRetry` (`inspecto-engine/.../inspector/CommitRetry.java`) is what X1 shipped:

* One small **JSON sidecar per member FILE** under `<status_dir>/retries/`, mirrored by poll-relative path
  exactly like the markers and the quarantine tree. **No `status_dir` ⇒ no record ⇒ unbounded retry**, which
  is the pre-X1 behaviour.
* **Grain is deliberate:** the failure is per Consignment, the record per FILE, because `batchId` is minted
  per cycle and the planner may regroup members — the file path is the only identity stable across
  re-encounters. Members the strategy already quarantined are skipped: *their fate is decided*.
* Exponential backoff with jitter; on exhaustion the file is quarantined under `retry_exhausted` with a
  CRITICAL `pipeline.batch.retry_exhausted` Signal. **Poison stops, loudly.**
* Public surface: `recordFailure(batch, cfg, error)` · `clear(batch, cfg)` · `due(cfg, candidates)` ·
  `recordFor(file, cfg)`. Callers are `CollectorProcessor` and `ConsignmentIngestor` only.
* ⚠ **Every method is best-effort and never throws** — "recovery bookkeeping must not mask the failure it is
  recording". Any affordance built on it inherits that constraint.

**The affordance today is: delete the sidecar by hand, or run `reprocess`.** There is no route, and — the
part the row does not say — **no READ surface either**: nothing lists which files are waiting, how many
attempts they have had, or when they are next due. An operator cannot see the queue they are being asked to
manage.

## 2. 🔴 The other deferral got HARDER today, and that is the finding

The row's first deferral is a per-pipeline **`processing.retry`** block. `CommitRetry`'s own javadoc says
why it was deferred: *"a config key rides two committed contracts, which is its own change"* (node-attributes
+ step-types).

⚠ **As of 2026-09-16 it rides a third.** A peer shift shipped `2c310d1c feat(config)!: refuse a config key no
component reads`, with an `AcceptedConfigKeys` map and a strict seam. ⇒ a new `processing.retry` block is now
**refused by validation until it is declared there**, and the declaration is granular by BLOCK, in a file
another shift is actively editing. ⛔ **Do not start `processing.retry` while that work is in flight** — it
would collide in the one file both changes must touch, and the failure mode (a config key that validates in
one branch and is refused in the other) is invisible until the merge.

⇒ **Of the two X1 deferrals, only the affordance is startable now.** That is the recommendation of this pass.

## 3. What the affordance actually needs — three operations, not two

The row names "cancel / retry-now". Grounding says a usable affordance is **three**:

| Operation | Why | Shape |
|---|---|---|
| **List** | an operator cannot cancel what they cannot see; nothing exposes the retry queue today | `GET` — read, open by policy |
| **Retry now** | clear the backoff so the next cycle admits the file immediately | mutating |
| **Cancel** | stop retrying this file — but see Q1, "stop" is ambiguous | mutating |

⚠ **Both mutating routes must declare a posture or the server will not boot** — that control shipped
2026-09-16 (`ControlApi.requireDeclaredPosture`): a capability via `ApiContext.withCapability("…")` with the
capability spelled as a **string literal** (the manifest scan matches the literal, not a constant — it caught
exactly that mistake the day the control landed), or a recorded `CapabilityManifest` exemption. The evidence
table under `compliance/evidence/route-gating.md` is **generated**, so regenerate it in the same change or
CI fails. **`canOperateRuns` is the fitting capability**: this is operating a run, and `DecisionRoutes`
already uses it for the neighbouring act.

## 4. Constraints any implementation inherits

* ⛔ **Never throw.** `CommitRetry` is best-effort by design; a route over it must fail soft and report, not
  500 because a sidecar is unreadable.
* ⚠ **Address by poll-relative FILE path, not by `batchId`** — the grain is per file precisely because
  `batchId` is not stable across re-encounters. A route keyed on batch would be unusable on the second cycle.
* ⚠ **A file already quarantined has had its fate decided** and `CommitRetry` skips it. The affordance must
  say so rather than appear to act.
* ⚠ **No `status_dir` ⇒ no records at all.** The list must distinguish *"no retries pending"* from
  *"this pipeline keeps no retry state"* — they look identical and mean opposite things.
* ⚠ **`clear(batch, cfg)` takes a Consignment**, so a per-file variant is needed; adding it is the first code
  step, and it belongs beside the existing method rather than in the route.

## 5. Decisions — ANSWERED 2026-09-25 (operator)

**Q1 — What does "cancel" MEAN?** (a) **Quarantine now** under a distinct reason (e.g. `retry_cancelled`),
deciding the file's fate exactly as exhaustion does — consistent with how the system already ends a retry
loop, and the file stops being reconsidered; or (b) **drop the record only**, which — read carefully — does
not stop anything: with no sidecar the file is retried *unboundedly*, i.e. "cancel" would restore the
pre-X1 poison behaviour under a reassuring name. **Recommendation: (a).** ✅ **Decided 2026-09-25: (a) — quarantine now under `retry_cancelled`, never "drop the record".** ⛔ (b) is the trap the row's
"delete the sidecar" workaround already falls into, and naming it "cancel" makes it worse.

**Q2 — Does "retry now" reset the ATTEMPT COUNT, or only the due time?** Resetting attempts makes a poison
file immortal (the cap never bites); resetting only the due time keeps the cap meaningful.
**Recommendation: due time only**, and say so in the response. ✅ **Decided 2026-09-25: due time/backoff only, attempt count kept — the response's `attemptsKept` + `note` say so** — an operator who expects a full reset and
gets exhaustion two cycles later will file a bug against the cap.

**Q3 — Whose surface is this: a route, the UI, or both?** The row calls it an "affordance" without saying.
A route alone is usable via the API; the run-detail pane is where an operator would look
(`okf/frontend/features/run-detail.md` already carries the *"reprocess is whole-batch only"* note this row is
paired with). **Recommendation: route first**, UI as a separate follow-up — the route is testable and the
pane needs its own design. ✅ **Decided 2026-09-25: routes first, UI later (not built).**

**Q4 — Is the LIST route per pipeline, or global?** Per pipeline matches where `status_dir` lives and keeps
it space-scoped by construction; a global list would need to enumerate pipelines and re-raise the
cross-space question that postponed the space-comparison row. **Recommendation: per pipeline.** ✅ **Decided 2026-09-25: per pipeline.**

## 6. Verification, when it is built

A real-HTTP test in the control-plane idiom (the `endpoint` skill's house style): both mutating routes gated
and refused without the capability, the list distinguishing "none pending" from "no retry state", retry-now
moving a file's due time without resetting attempts, and cancel quarantining under its own reason. Plus the
`CapabilityManifestTest` congruence check and a regenerated route-gating evidence table — both fail by design
if the new routes are added to only one side.
