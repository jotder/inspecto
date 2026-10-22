---
type: Feature
title: "Telecom bypass (SIM-box) — decisions and as-built record"
description: "Slices B0 to B3 of TELCO-BYPASS-SIMBOX-1 as built: the answered decisions, gate G-SB, the measurements, the mutation passes, the deviations from the plan and the traps found."
tags: [telco-bypass, plan, as-built]
timestamp: 2026-10-22T00:00:00Z
---

# Telecom bypass (SIM-box) — decisions and as-built record

> **What this page is.** The durable record of slices **B0 to B3** of `TELCO-BYPASS-SIMBOX-1` (built 2026-10-20 and
> 2026-10-21), distilled from the original plan when it was consolidated on 2026-10-22. How the pack works today is in
> [architecture](architecture.md) and [admin guide](admin-guide.md); this page keeps **why**, what was measured and what was
> found on the way. **Open work** (B4 to B7, D-BP10, the deferred items below) lives in
> [`superpower/telco-packs-plan.md`](../../../../superpower/telco-packs-plan.md). Full history (design rationale, the B1 to B3
> narratives in their own words): the archived original
> [`bypass-simbox-detection-plan.md`](../../../../archived-documents/plans-archive/bypass-simbox-detection-plan.md), sections 16 to 19.

## Scope, as decided

CDR-based only. Signalling (matrix row F), test calls (row G), inline blocking or per-call validation, OTT / VoIP bypass, a box on a
foreign network and SMS A2P grey routes are out. A CDR pattern is evidence of **consistency with** a box, never proof. "Smart" means
deterministic, explainable and recomputable (the Risk Score and Anomaly Score standard); a trained model stays the roadmap gap
`G-21`. Interconnect-side fraud that is not bypass (IRSF, Wangiri, flash calls; row H) is the sibling plan in
[`../telco-fraud/plan-irsf-wangiri.md`](../telco-fraud/plan-irsf-wangiri.md).

## Decisions (answered 2026-10-20, operator: "go with the recommendations")

| ID | Decision as answered | As built, and how to reverse |
|---|---|---|
| D-BP1 | A new Space Template `telco-bypass`, not an edit of `telco-fraud` | Built in B1. `telco-fraud`'s pinned counts (14 Jobs, 14 Alert Rules, 58 golden Alerts) are untouched and `fraud_simbox` stays as the starter. Reverse: fold the template into `telco-fraud` and re-pin its golden. |
| D-BP2 | Optional CDR columns with per-signal degradation | Built: `answered`, `release_cause`, `imei`, `imsi` and every Reference are optional; a missing input is NULL and named in `missing`. The operator's real contract is still unknown (open item). |
| D-BP3 | The box Incident (`by: cluster_key`) is the Case anchor; SIM rows are evidence | Built in B3 (`tb_box_suspected`, `tb_box_case`). A per-SIM Incident never joins a box Case. |
| D-BP4 | Risk Score primary; the anomaly layer is one factor and never alerts alone; no ML | B4 builds the factor. |
| D-BP5 | Daily with persistence first; hourly is a later slice measured against `WS-21` | Built: `persist_days` 2 of `persist_window` 3, illustrative. |
| D-BP6 | Approval-gated Action Request (or a manual task until a provisioning API exists); only `watch` entries of 24 h or less auto-apply | The barring Action Request is not built; the runbook carries it as a manual step. |
| D-BP7 | B5 (interconnect-side CLI) demand-gated; boundary with the IRSF / Wangiri lane agreed first | Not started. |
| D-BP8 | Exposure in minutes, with an optional rate parameter; impact stays manual | Minutes built (`est_bypass_minutes`); the rate parameter is not. |
| D-BP9 | A TAC (device class) Reference is optional and never bundled | S14 is not computed. |

D-BP10 (commercial and matrix cell E) is **open**: see the plan.

## Gate G-SB

Any one clause trips it, and a feature module with a Job Type becomes a separate design: (a) the SIM-day stage cannot meet the
throughput budget; (b) an Alert Rule `by`, `stormCap` or Case grouping cannot express a **box** work item; (c) a stage's SQL cannot
be unit-tested in isolation; (d) a prospect names a signal the closed list S1 to S16 cannot express.

| Clause | State after B3 |
|---|---|
| (a) | **Holds.** Budget set in B0: a 5M-row day in 60 s on a 4-core allowance at the default memory cap, peak 2.5 GiB, no spill (120 s fails). B1's real Job: 6.8 s at 5M rows; the B3 cluster stage adds less memory than `tb_features` at the same row count. |
| (b) | **Holds, through the box Incident.** The SIM rule cannot express a farm (it storms above `stormCap` 1000); the box rule can (500 boxes a day is its ceiling). |
| (c) | **Holds.** `BypassAllowListTest` runs `tb_findings` in isolation, `BypassClusterTest` the two cluster stages. |
| (d) | Not tripped; no prospect has named a signal. |

## B0: the spike (2026-10-20)

A throwaway harness kept outside the repository: standalone DuckDB with the engine's settings (`duckdb_jdbc` 1.5.6.0, in-memory,
`allowed_directories`, `enable_external_access=false`, `lock_configuration=true`, `memory_limit` = the engine default
`max(1 GiB, 40 % of RAM ÷ 4)` = 3,246 MiB on a 31.7 GiB, 12-core Windows box). The statement passed `SqlGuard.check` with 0 findings;
`IncrementalSink`, Decision Rules, queueing and concurrent Jobs were not exercised (B1 closed that caveat for `tb_features`).

| Synthetic day | Wall | Peak DuckDB memory | Spill |
|---|---|---|---|
| 1M rows | 1.8 s | 593 MiB | 0 |
| 5M rows, default cap (three runs) | 9.8 / 12.6 / 14.6 s | 1.81 to 1.83 GiB | 0 |
| 5M rows, 4 threads | 9.1 s | 1.82 GiB | 0 |
| 5M rows, 1 thread | 24.3 s | 674 MiB | 0 |
| 5M rows, 1 GiB cap | 23.7 s | 1 GiB (at cap) | 370 MiB |
| 5M rows, 512 MiB cap | fails (Out of Memory) | at cap | n/a |
| 10M rows, default cap | 22.3 s | 3.15 GiB (97 % of the cap) | 0 |

About 380 bytes of peak memory per input row at 5M. **Memory, not time, is the binding limit.** The synthetic day planted 1,200 box
SIMs (20 boxes of 60), 1,000 call-centre SIMs and 500 couriers; a plain predicate flagged 1,200 of 1,200 box SIMs and none of the
look-alikes. Synthetic cell coordinates were random, so S6 speed values measured cost only, not signal quality.

The six answers, as they still apply:

1. Throughput and memory: the table above; G-SB(a) holds.
2. **`AlertRule.by` is a list** (two keys already shipped in the Risk and Anomaly Score Alert Rules; `PerEntityAlertTest`). Since
   ALERT-PER-RULE-1 (2026-10-14) there is **one Alert per Alert Rule** and **one Incident per key** (CRITICAL or ERROR; dedupe
   `<rule>|<keyId>`). Above `stormCap` (default 100) the rule keeps its Alert, opens **one storm Incident** (`<rule>|*`) and no per-key
   Incident opens or heals until the count drops; persisted open-key digests stop at `MAX_DIGESTS` 1,000. The probe groups the
   **whole** Dataset (every retained day) unless it reads a `…_latest` identity Dataset.
3. **Case grouping on a cluster key works through the Alert Rule's `by`**: `CaseRule.groupBy` reads `key.<groupBy>`, which the Alert
   Rule stamps for every `by` column. Traps: a NULL key is stamped as the **string `NULL`** (so every unclustered row would join one
   Case); the dedupe is the whole key tuple; above `stormCap` there is nothing to group; `threshold` and `windowMinutes` apply per group.
4. **One Alert Rule cannot express score and `primary_signals`** (one Measure, one comparator, one threshold; `alert.when` is refused on
   a Dataset Measure Alert Rule). Built answer: the Job writes only qualifying rows (`tb_findings.alert_score`).
5. **An `allow` Entity List sidecar can be filtered on `expires_at` inside a Job read** (proved standalone; B2 proved it through the
   real Job). The store is `entity_list_<id>`, so ids use underscores; expiry has no write, the filter is the only enforcement; prefer a
   run-day parameter to `now()` so a backfill is reproducible.
6. `TelcoFraudTemplateGoldenTest` lives in module `inspecto` (`com.gamma.control`).

## B1: signals and corpus (commits `cf5dda252`, `add360e45`)

**Corpus.** `BypassCorpus`, seed 20261020, 18,614 CDR rows, 613 SIMs, fictional ranges only. Golden: every signal of the 211 scored
SIMs equals a plain-Java oracle to 1e-9; `box_like` equals the planted set (P1 60-SIM box, P3 20-SIM IMEI-spoofing box, one SIM just
inside each clause and the volume floor, and the pinned ambiguous pair L2 / L3: 86 SIMs); every other archetype is silent, including
every SIM exactly AT a threshold.

**Measurements** (standalone with the engine's settings): 1M rows 0.68 GiB; 5M 4.6 s, 1.78 GiB; 7.5M 7.9 s, 2.47 GiB; 10M 9.9 s,
3.14 GiB (97 % of the cap); 5M under a 1 GiB cap 12.1 s with 394 MiB spilled. **The real Job through `JobService`** at 5M rows
(400,000 SIMs, 2,700 scored): 6.8 s, process commit 2.37 GiB. The "above about 8M rows a day raise
`processing.duckdb.memory_limit` to 4 GiB" advice is interpolated between measured points and sits in the admin guide.

**Deviations and traps.**

- `new_batch_share` is the share of the dealer-and-day batch that is `box_like` today; "consecutive MSISDN" was not built. S13 and
  S14 are not computed; `has_imei` / `has_imsi` record presence only.
- ⚠ `avg(CASE WHEN x THEN 1.0 ELSE 0.0 END)` reads a NULL `x` as 0, so a missing input became a 0 share; such columns are written
  `CASE WHEN x IS NULL THEN NULL …`.
- Float aggregates (entropy sum, gap deviation) differ in the last digit with thread scheduling: compare to 1e-9, never bit for bit.
  Window ties are broken by `record_id`.
- The B0 harness's 12.6 s and 1.8 GiB were measured without the MT scan and the evidence ids; with them and without the
  `NOT MATERIALIZED` and `arg_min` changes (architecture, "Cost") the statement took 18 to 23 s at 2.9 GiB.

**Mutation pass.** Every clause threshold and comparator, the volume floor both ways, `short_s`, the roaming filter and each signal's
formula; six were real Maven runs, red for the named reason, the rest ran against the corpus in a scratch DuckDB and each changed a
column the oracle pins.

## B2: score, lists and the SIM Alert

**Tests and modules.** `BypassScoreTest` (6), `BypassAllowListTest` (10), `TelcoBypassGoldenTest` (5) and the opt-in
`BypassScaleMeasureTest` in `inspecto-scoring`; `BypassCorpus.generateB2()` in `inspecto`. ⚠ `inspecto` publishes a **test-jar** that
`inspecto-scoring` reads, so the golden reuses the corpus, and `tools/affected-modules.mjs` answers FULL for this lane (a module pom edit).

**Golden.** Night 1: 214 scored SIMs, 93 high; night 2: 90 SIMs alert exactly once, each with top factors
`b_diversity,no_inbound,short_calls`; X1 (high, four primary signals) is held by `agreement`; L2a (allowed) raises only
`tb_allowed_line_changed`; L2b alerts because its allow entry lapsed. Every stored score recomputes with `RiskScorer.recompute` to 1e-9.

**Deviations from the plan.**

- The five clause factors are 0-or-1 flags with `box_like`'s own comparisons (the names `b_diversity_excess` and
  `short_uniform_index` are kept but not graded, and `short_uniform_index` ignores `acd_cv`); only the shares and the two protective
  shares are graded. The protective inbound factor reads the inbound share, not a count of distinct callers.
- Positive weights sum to 80 until the device-cluster (12) and anomaly (8) factors exist; high is 60.
- ⚠ A Space Template cannot ship an Entity List: zero-row sidecar seeds with the list marker, the model ships without `watchList`,
  lists are created by hand. The watch list lowers only the persistence floor; the planned `min_events` lowering is not built.
- ⚠ Only a CRITICAL or error rule opens an Incident, so the INFO `tb_allowed_line_changed` raises one Alert counting the lines.
- `tb_findings` rejects a scores run older than its window; `cluster_key` was left to B3.

**Measurements.** Stage time over N scored SIMs: 3,000 SIMs `tb_factors` 0.18 s, `tb_risk_score` 7.5 s, `tb_findings` 0.21 s; 50,000:
0.39 s, 42.7 s, 0.65 s; 150,000: 0.60 s, 114 s, 1.4 s. The Risk Score Job dominates (about 0.75 ms a SIM) and refuses more than
200,000 entities by default. `tb_sim_high`: 900 and 1,000 alerting SIMs open one Incident each; 1,001 and 1,200 open ONE storm
Incident. Peak memory of these stages was not measured.

**Mutation pass.** 65 one-clause mutants of the shipped files, all red; one (F16) survived at first and was killed by a new
assertion; seven went red in the right test with a different message than first guessed. A `greatest(1, …)` guard was removed as
unreachable.

## B3: the cluster stage, the box Incident and Case

**Tests.** `BypassClusterTest` (10, the two stages in isolation), `TelcoBypassClusterGoldenTest` (3, `inspecto-scoring`, oracle from the
raw call rows, six planted boxes by name), `BypassBoxCaseRuleTest` (2, `inspecto-case-management`, the shipped rules on the real
object engine); `BypassScaleMeasureTest` (`TELCO_BYPASS_PERF=1`) gained two cases.

**Found and fixed in review.** `tb_cluster_members` counted MO calls of every service, so SMS inflated `calls_mo` and the shares;
fixed with `service = 'VOICE'` (`BypassClusterTest` goes red without it: 199 expected, 200 found).

**G-SB(b) re-check.** 1,200 alerting SIMs of 20 boxes: `tb_sim_high` (cap 1000) opens one storm Incident and no per-SIM Incident;
`tb_box_suspected` opens 20 Incidents. The box rule at 500 boxes opens 500 Incidents, at 501 one storm Incident.

**Measurements** (hash-based synthetic generator, 20 boxes of 60 SIMs). Member stage standalone at the default cap: 1.3M CDR rows
0.40 s, 448 MiB; 5.3M 1.5 s, 1,311 MiB; 10.3M 2.8 s, 2,081 MiB. The real `tb_cluster_members` Job: 0.60 s, 1.7 s, 3.8 s;
`tb_clusters` 0.10 to 0.16 s.

**Mutation pass.** Eight mutants, all red for the named reason (all-services `mo`, box size `>` for `>=`, cell before imei, allow
anti-join disabled, window end inclusive, `stormCap` 5, Alert Rule `by` the wrong column, Case Rule `groupBy: entity_key`). A first
allow-list mutant was a syntax error (red for the wrong reason) and was replaced.

## Deliberate deferrals after B3

Not built on purpose, carried as open work in the plan: the barring Action Request (manual until a provisioning API exists); box
persistence; an Incident text that refreshes on a later night (a persisting box opens one Incident and then nothing); S13's 7-day IMSI
churn, S14, S15's cell concentration and concurrent calls per IMEI, S16 money; `tb_block` and `tb_ranges` consumers; the watch list's
`min_events` lowering; the `runbook` link on the SIM Alert Rules; the D-BP8 rate parameter. Unmeasured: peak memory of the B2 stages,
more than 405,000 SIMs a day, a real operator's volume, CDR contract and precision.
