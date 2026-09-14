# Duckle concept candidates — TRIAGE LIST, nothing decided, nothing built

**Status:** CANDIDATES (2026-09-14). This is a review list, not a plan and not BACKLOG rows.
The operator will add / remove / prioritise here first; only survivors move to
`docs/BACKLOG.md` §3. Until then nothing in this file is committed work.

**Upstream:** [slothflowlabs/duckle](https://github.com/slothflowlabs/duckle) — Rust + DuckDB,
self-hosted ETL, canvas or SQL authoring, plain-file workspace, headless runner + web console.
Beta v0.7.2, ~1.5k commits, MIT OR Apache-2.0. Same shape as inspecto; the value is in a
handful of crisply stated operational rules, not in its surface area.

**Explicitly out of scope (operator, 2026-09-14):** in-app git integration, component count,
UI languages, desktop/Tauri packaging, bundled local LLM.

**Grounding done before listing:** each item was checked against `inspecto*/src/main`,
`docs/okf/` and `docs/BACKLOG.md` so the "inspecto today" column is a grep result, not a guess.
Re-grep before acting — a row's premise goes stale (see `docs/PROJECT_NOTES.md`).

Vocabulary is canonical per `docs/GLOSSARY.md`: Pipeline, Dataset, Run, Incident,
Expectation, Alert Rule, Collector, Consignment.

---

## 1. Main candidates (rough payoff order)

| # | Concept (duckle's rule) | Inspecto today (grounded 2026-09-14) | Nearest inspecto home |
|---|---|---|---|
| C1 | **Dataset freshness SLA on a clock, not on failures.** An ownership rule declares `maximumAge` or `expectedAfterSchedule`. No declared limit ⇒ `unknown`, never `fresh`. A failed or *partial* run does not count as a refresh. A disabled/missing schedule makes the Dataset stale. `fresh→stale` alerts and `stale→fresh` sends an all-clear through the same Alert Rules; the all-clear is never held by a cooldown. `stale_since` carried across evaluations. Evaluated once a minute on its own thread, not the scheduler's. | **Absent.** All 13 Java "freshness" hits are notification *delivery-status* freshness (`DeliveryStatusAdapter`, `EnrichmentConfig`), not Dataset age. Nearest relative: the completeness KPI (K1+K2). BACKLOG: 0 rows. | Alert Rule / Incident; Catalog Dataset badge; agent skill `OperationalTables` |
| C2 | **Run diff = grouped recorded facts + rule-derived explanations.** Compare two Run receipts by kind: code, runtime, invocation, inputs, execution, output. Every explanation line traces to a listed difference — no generated prose. "Not compared" is stated explicitly. Absent is not zero (a run that died at node 2 has no counts after it). Secrets compared as `***` / digest. | **Absent as a surface.** Receipts + ledgers exist (`RunArtifactStore`, provenance rows). Only "diff" hits are `inspecto-intelligence` component tools. | Run ledger; agent diagnosers (`HeuristicDiagnoser`, `ModelDiagnoser`) as consumers |
| C3 | **Dead-property refusal at validate time.** A config key no component reads fails validation with a stable code + near-name suggestion (no suggestion when nothing is close). Accepted-names doc is *generated from the same map the checker enforces*. Strict at validate, warning at run; `x-` keys round-trip untouched. | **Partial, inconsistent.** `ComponentStore` refuses unknown keys; `RecipeCompiler` comment admits other unknown keys "stay"; `ArgumentDeriver` silently drops. Several past silent-loss defects in PROJECT_NOTES are this class. | `ConfigSafetyValidator`; node attribute specs / `step-types.contract.json` |
| C4 | **Parameter provenance with override record.** Two surfaces binding one parameter: later wins (documented rule); receipt records `{source, overrode:[...]}`. Only a *differing* value counts as an override. `secret` is a declared type; replaced with `***` in history, never dropped (so "was a token supplied?" stays answerable). All problems reported at once with stable codes (`param:unknown`, `param:missing`, …); undeclared names refused, not ignored. | **Parameter contract SHIPPED** (job-parameter-contract). Override/provenance record on the receipt: absent. | Job parameter contract; Run receipt |
| C5 | **Releases = content-addressed control-plane snapshots.** `build / diff / activate / rollback`. Activation *materialises* content (so A/B/A really executes A,B,A) and refuses before mutating, reporting every problem at once. Uncommitted work is named, not discarded. Pointer swap is one rename. Rollback deliberately ungated. Every Run records the release it *started* under. Only hashes + connection *references* stored, never values. | BACKLOG has ~55 release/promotion mentions; design reference, not new scope. | Existing release/promotion rows; `release-workflow` skill |
| C6 | **Workspace policy that can only narrow.** Denies union, allowlists intersect, permissions AND; `mode` from the server file only. Enforced at plan time *and* at the point of the act (network: every hop + DuckDB itself; state mutation: every watermark/offset advance). Prefixes match at a path boundary, not as strings. A named policy file that cannot be read **refuses the run**. | **Parts exist:** `PathJail`, `ConfigSafetyValidator`, `DataRef`. The structural narrowing rule and "unreadable policy refuses" are absent. ⚠ The prefix-boundary rule is exactly the guard defect corrected in phase C §5.4. | Config safety; sealed sandbox; edition gating |
| C7 | **Affected + contracts check against a git revision.** Which Pipelines does a change reach, each carrying the chain that reached it; asset edges *and* parent→child ref edges (reverse direction); deleting a producer is a change; canvas geometry ignored; dynamic paths listed as uncertain. Contract verdicts depend on the *reader*: remove a read column = breaking; unread = "possibly breaking", never "compatible"; downstream-of-transform = "revalidate" tier (no column lineage to prove either way). | Lineage/impact code exists (42 Java, 25 TS hits) but no CI-shaped gate over a diff. Note: *reads git objects*, so tied to git presence — flag if the git-integration exclusion covers this. | Lineage; `docs/api` breaking-change record; CI |
| C8 | **Baseline QA Expectation.** Profile current input vs the **median** of last N *accepted* profiles (row count; per column null count/rate, distinct, min, max, mean). Limits in either direction, % or absolute. `groupBy` + `requireExistingGroups` catches a missing partition when totals look normal. Profile accepted **only if the whole run succeeds**. Explicit `accept` / `clear` ops, audited with the replaced value; a refused run still records its profile. | **Absent as an Expectation kind.** "baseline/drift" hits are file-sequence gaps, diagnosers, backup. | Expectation kinds; FileSequenceGaps is a cousin |
| C9 | **A watcher is not a run.** A polling session has its own identity (`lastPollAt`, `pollCount`, `lastError`); only a poll that moves rows or fails mints a Run, naming the session as parent. `pollCount − runCount` = quiet time. Killed watcher reconciled to `interrupted` on next start, like a receipt. | Collector loop conflates polls and runs. Cheap to separate. | Collector / Consignment scheduler |
| C10 | **Named execution pools are admission only.** A pool answers "may this start now"; it never widens threads/memory caps. A Pipeline may *choose* a pool, never define one the server lacks (unknown ⇒ `default`). Queued run gets a durable id immediately with `queueReason`; becomes `running` with `queueMs`. A supervisor (Plan) takes no slot — holding one while waiting for a child that needs the same pool deadlocks. Metric: free permits per pool. | Scale-out phase B lease (`RunLease`, fenced db lease) is the seam. Rule set is a design constraint for it. | `enterprise-scale-out-plan.md` phase B |

## 2. Smaller items worth a line

| # | Rule | Note for inspecto |
|---|---|---|
| S1 | **Retention deletes nothing by default;** opt-in per category. Saved state (watermarks, resume positions, accepted contracts) is *never* touched — checked when the plan is built **and again when applied**. Audit log never pruned by age (it records the prune). `--dry-run` and real prune share one planning function. | We have retention_days on Catalog lifecycle; the "state is never housekeeping" split and dry-run-shares-planner are the takeaways. |
| S2 | **Last publication of an SLA-bearing Dataset is kept** regardless of the retention horizon — otherwise a 30-day window reports a 90-day SLA breached 45 days early. | Depends on C1. |
| S3 | **Publication-driven subscriptions.** One successful run = one publication event (not per asset). Failed or ceiling-stopped runs publish nothing. Publication, delivery and consumer run recorded as three states. Deliveries are *derived*, so a new subscriber receives past publications; delivered-once stays delivered; no self-subscription. | We have `on:dataset` trigger (UI-S7). Compare its semantics against these three rules. |
| S4 | **Item-level checkpointing** for paid per-row calls: result stored *as it arrives*, output stored not just success. Identity = business key **and** whole input row **and** stage config; no key ⇒ whole row (safe direction: costs reuse, never a wrong answer). Pruning explicit only. | Relevant to enrichment/AI steps. |
| S5 | **A route with no entry in the permission table requires admin** — a later route is locked down, not left open. | Check `ControlApi` default for unlisted routes. |
| S6 | **Refusals audited as carefully as successes** (`audit --outcome denied`). `allowed` means permitted, not succeeded. Reads not audited (a polling dashboard must not bury the record). | Compare with our audit trail scope. |
| S7 | **One id all the way through:** receipt, history record and log lines share the run id; log lines matched on a `run_id` *field*, not text, so a run that mentions another is not reported as its log. | Run model slices 3a–3c shipped `run_id`; verify the log-line field half. |
| S8 | **Typed parameters validated at one boundary** — every surface (UI, CLI, API, scheduler, agent) reaches substitution through one function. | Confirm our surfaces share one validator. |
| S9 | **Partitioned backfills** — each slice is an ordinary durable Run with its own receipt; `status / retry --partition / cancel`; a slice interrupted by a restart reconciles to `interrupted`. | BACKLOG has 12 backfill mentions; check overlap. |
| S10 | **Masking on previews, not on written data.** PII/secret masks apply to what an operator *looks at*; the sink is untouched. | Studio preview tier. |
| S11 | **Per-node materialisation mode** (`auto / view / memory / disk`) and per-node retry with backoff and memory cap as *Advanced* properties. | Step processor catalog; check what per-Step knobs exist. |
| S12 | **Catalog rebuild honesty:** saved graph records what it was built from; CLI rebuilds on read, console *says* "pipelines have changed since this was built" rather than rebuilding (viewer action must not write). Unnameable assets are *counted on every answer* so a partial graph never looks complete. | Catalog / lineage panes. |
| S13 | **Ownership rules: first match wins, globs; `lint` fails on rules that match nothing** (a renamed asset silently orphans a team). Unowned assets fail only under `--strict`. | Catalog owners; Alert Rule routing by owner/tag (C1). |
| S14 | **Two limiters read one config.** Runner (sync condvar) and scheduler (async semaphore) share the same numbers — "two limiters each parsing their own config is how the two schedulers came to disagree about time zones." | Scale-out B: one source for pool sizes. |
| S15 | **Cron in a named civil time zone with DST handling pinned and tested;** interval/file-watch schedules have no civil-time deadline and fall back to `maximumAge`. | We have `-Dops.timezone`; check schedule-side DST tests. |

## 3. Next step

Operator triages this table: strike rows, merge duplicates, rank. Survivors become BACKLOG §3
rows with the "inspecto today" column re-grepped at filing time. Then `git mv` this file to
`docs/archived-documents/plans-archive/` per the superpower lifecycle.
