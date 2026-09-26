---
type: Plan
title: Assurance capability plan — the customer-independent product work, reprioritised
description: The generic RA / FM / BA product capabilities that need no customer input, ordered into five dependency waves so they are built before any assurance project starts; customer-dependent work is parked.
tags: [plan, revenue-assurance, fraud-management, business-assurance, payment-fraud, roadmap]
timestamp: 2026-09-26T00:00:00Z
---

# Assurance capability plan — build the product before the project

> **Status: ACTIVE 2026-09-26 — decisions answered (§5), waves 1–2 on the board, wave 1 in flight.**
> **Why this exists.** An assurance bid was planned workstream by workstream (`WS-01 … WS-46`) in a
> local, git-excluded working set (`superpower/rfp-mvno-assurance/build-plan.md`, listed in
> [`../INDEX.md`](../INDEX.md)). That project is **not confirmed**. The operator's call (2026-09-26):
> build the capabilities that are **generic product** — useful to any telecom assurance customer and
> needing **no customer data, feed samples, partner or sign-off** — so they exist when a real project
> starts. This file re-orders that work by product value and dependency, not by bid milestone.
> The `WS-xx` ids are kept so the two plans stay traceable.
>
> ⚠ **No customer material here or in any commit.** Every fixture is synthetic; content packs ship as
> Space Templates with thresholds as configuration, never tuned to one operator's feeds.

## 1. The test for "generic"

A workstream is in scope when all three hold:

1. **No customer input.** It can be specified, built and accepted on synthetic data.
2. **Not a one-customer shape.** Any assurance customer would use it unchanged (thresholds, factor
   weights and list contents are configuration, never code).
3. **Only operator decisions block it.** Product calls the operator can make today are fine; calls that
   belong to a customer (their Head of Fraud's factor table, their payment gateway's API, their
   hosting) are not.

A workstream that is *mostly* generic is split: the generic half is scheduled here, the rest is parked
(§4).

## 2. Status on master (grounded 2026-09-26)

The bid plan's "today" column dates from 2026-09-24. Re-checked by grepping symbols on master:

| WS | Capability | Status | Evidence / what is left |
|---|---|---|---|
| WS-11 | Server-side Break store | **SHIPPED, narrower** | `inspecto-engine/src/main/java/com/gamma/query/ReconStateStore.java` + `ReconBreaks.java` (`5a39d21f3`, `f206c2711`): `open / resolved / auto_closed`, auto-close, survives restart. Left: `assigned`, per-Break `lastSeenAt` and `occurrences`, ageing and recurrence. |
| WS-10 | Financial-impact ledger + Disposition | **SHIPPED** 2026-09-26 (`ASSURE-IMPACT-LEDGER-1`; residuals `ASSURE-IMPACT-LEDGER-RESIDUALS-1`) | Typed `Impact` on Incident/Case via `PUT /objects/{id}/impact`, `outstanding` derived; Disposition required on Incident resolve (+ duplicate, accepted-risk); `impact_ledger` Dataset from the `objects.analytics` run — snapshots per run, so freshness is the job's cadence. As-built in `okf/capabilities/incidents/incidents.md`. |
| WS-18 | Per-entity Alerts | NOT STARTED | `AlertRule.java` has no key columns; `AlertService` keeps one Incident per Alert Rule + Pipeline. |
| WS-13 | Maker-checker for human changes | NOT STARTED | `SaveGate.java` validates content, it does not hold changes for approval. `PipelineSettingsRoutes.java` and `PipelineRenameRoutes.java` still write outside `SaveGate`. |
| WS-12 | Entity Lists | PARTLY BUILT (by `LA-17`, 2026-09-26) | exact-key lists with the four purposes over the Identity Fact log (`/inv/entity-lists*`, Standard/Enterprise only); no ranges, CIDR, expiry, sidecar or four-eyes yet — and deliberately no `ComponentStore` kind (D-P10, reconciled by D-M8). |
| WS-24 | Action Requests | NOT STARTED | `DecisionRoutes.java`: `invoke-api` still records a stub Signal. |
| WS-20 | KPI definitions | PARTIAL | UIE-1…4 (`f0217d21b`) gave the tile format, target and compare (`kpi.component.ts`). Left: a server-side KPI definition (Measure + target + bands + period + comparison period). |
| WS-22 | Explainable risk score | NOT STARTED | no scoring component or Job. |
| WS-25 | Tamper-evident audit | NOT STARTED | 2026-09-26 made the trail correct and durable (`AuditTrail.java`, `1de1dadd5`); no hash chain, no verify route. |
| WS-21 | Near-real-time ingest | PARTIAL | scan-driven Kafka loop hardened (`STREAM-CONSUMER-1`, design archived); no push route, no continuous lane, never run against a real broker. |
| WS-17 | SQL access for BI tools | NOT STARTED | no Postgres publication. |
| WS-27 | Excel export + attachments | PARTIAL | an xlsx writer already ships: DuckDB's `excel` extension, staged offline (BACKLOG *D-8 XLSX export*, `PipelineDocumentXlsxTest`). `ReportJob.java` does not use it yet (json / csv / png / pdf); mail has no attachments. |
| WS-23 | Workflow editor, SLA, escalation | PARTIAL | `Workflow.java` is file-authored only; the SLA sweep is a flat per-object deadline. Queues / escalation / watchers were **deleted on purpose** 2026-09-14 (`RETIRE-HALVES-1`). |
| WS-32 | Bundle the intelligence module | NOT STARTED | `EDITIONS.md` says *never bundled*. |
| — | `sql.template` Job (multi-Dataset, `on_pipeline`) | **SHIPPED** | `SqlTemplateJob.java` — this is what lets detection and features avoid the Step Processor hold. |

## 3. The reprioritised order — five waves

Each wave is ordered by dependency first, then by how many later items it unblocks. Estimates are
engineer-weeks, carried from the bid plan and cut where §2 shows work already done. ⚠ They come from
code reading, not delivery history.

### Wave 1 — detection and case foundations · ≈ 7–11 eng-wk

The two gaps every detector and every Incident hits. Nothing in waves 2–5 is credible without them.

| # | Item | Eng-wk | Why first | Acceptance |
|---|---|---|---|---|
| 1.1 | ✅ **SHIPPED 2026-09-26** — **WS-18 Per-entity Alerts** — optional `by` key columns on a Dataset-measure Alert Rule; one Alert and one Incident per breached key; dedupe per (Alert Rule, key); auto-resolve when the key heals; storm cap; save-time Schema check | 2–4 | Without it a detector says "breached", never *who*. Blocks every content pack and the risk score. | 40 planted offenders → 40 Incidents; re-fire → 0; healed key resolves; a Case Rule groups them into one Case |
| 1.2 | ✅ **SHIPPED 2026-09-26** — **WS-11 finish** — `assigned` state, `lastSeenAt`, `occurrences`, ageing and recurrence computed server-side | 0.5–1 | Small; closes a shipped feature properly | a recurring Break counts up across runs; ageing shows on the Recon Board |
| 1.3 | **WS-10 Impact ledger + Disposition** — typed impact on Incident and Case (`outstanding` derived, never stored); Disposition required on resolve, extending the GLOSSARY §9 ladder; an impact ledger Dataset any Measure can read; audited, server-validated | 4–6 | Every RA / FM / BA KPI is "money found / recovered / prevented" | loss KPIs compute from resolved Incidents on synthetic data |

### Wave 2 — governance and action · ≈ 11–17 eng-wk

Turns findings into controlled action. Order is forced: lists need approval, dispatch needs both.

| # | Item | Eng-wk | Notes |
|---|---|---|---|
| 2.1 | **WS-13 Maker-checker** — step 0: route the four Pipeline writes (`/label`, `/settings`, `/save-as-template`, `/rename`) through `SaveGate` or record why not, plus a guard test enumerating every mutating route; then per-kind approval policy, Pending Change, approve / decline / expire, approver ≠ author (403), stale base (409), shared diff view | 4–6 | copy the Link Analysis four-eyes pattern, not its names |
| 2.2 | ⏸ **ON HOLD 2026-09-26 (operator)** — Link Analysis (`LA-17`) leads the Entity List; an unverified lane branch is kept for later evaluation (BACKLOG `ASSURE-ENTITY-LISTS-1`). **WS-12 Entity Lists** — allow / block / watch; key types msisdn, imsi, imei, iccid, prefix / range, country, operator, dealer, device, instrument token, BIN, IP, CIDR; reason, added-by, expires-at; Parquet sidecar for 10⁵ entries; **range and CIDR matching** (equal-key join cannot do it) | 4–6 | needs D-P5; one kind with Link Analysis `LA-17` (D-P10) |
| 2.3 | **WS-24 Action Requests** — `ActionDispatcher`: target Connection, payload template, `draft → pending → approved → dispatched → succeeded / failed`, idempotent retries, linked Incident, audit; replaces the `invoke-api` stub | 3–5 | accepted against a stub HTTP endpoint — no real target system needed |

### Wave 3 — measurement and scoring · ≈ 11–17 eng-wk

| # | Item | Eng-wk | Notes |
|---|---|---|---|
| 3.1 | **WS-20 KPI definitions** — a server-side KPI (Measure + target + bands + period + comparison period); the tile reads it instead of hand-set inputs; a delivered `kpi` Requirement can create one | 4–6 | the widget half shipped (UIE-1…4) |
| 3.2 | **WS-22 Explainable Risk Score** — weighted-factor component per entity type; `score = Σ weight × indicator`, 0–100, with `factors[]`; feeds Incident priority and, above a threshold, a watch Entity List through 2.1 | 4–6 | **Job form**, not a Step (Step Processor hold) |
| 3.3 | **WS-25 Tamper-evident audit** — `prevHash` / `hash` chained per Space with a daily anchor; `GET /audit/verify` names the first bad record | 3–5 | masking stays out (D-P8) |

### Wave 4 — integration and operability · ≈ 18–30 eng-wk

Order inside the wave is free; these are independent.

| # | Item | Eng-wk | Notes |
|---|---|---|---|
| 4.1 | **WS-21 remainder** — push ingest `POST /streams/{id}/records` (NDJSON / CSV, capped, idempotency-keyed); a real-broker Kafka test; the continuous lane once a latency target is named | 4–6 | needs D-P7 |
| 4.2 | **WS-17 BI publication** — a publication **Job** writing curated Datasets into a Postgres schema with Catalog column comments | 3–5 | Job form (hold) |
| 4.3 | **WS-27 Excel export + e-mail attachments** — xlsx server-side, size-capped attachments | 2–3 | needs D-P6 |
| 4.4 | **WS-23 Workflow editor, SLA per priority, Escalation Rules** | 4–6 | needs D-P4 — it reverses a 2026-09-14 deletion |
| 4.5 | **Operability, generic half** of WS-01 / 02 / 04 / 26: nightly throughput floor in CI; mixed ingest + dashboard load measured; `PostgresStateStoreTest` run on a real Postgres; a two-node active / passive drill on plain Linux with measured RPO / RTO and a runbook; an offline vulnerability scan over the SBOM; a single-replica Helm chart | 4–8 | needs D-P3 for the scanner |
| 4.6 | **WS-32 Bundle the intelligence module** | 1–2 | needs D-P2 |

### Wave 5 — content on the platform (Space Templates) · ≈ 33–51 eng-wk

Content is what a buyer sees. It comes last because it stands on waves 1–3. Each pack ships as a Space
Template (`spaces/_templates/<id>/`) with a **synthetic golden corpus** (planted cases + planted
look-alikes that must stay silent), a golden test counting detections and false positives, dashboards,
KPI definitions and runbooks. Thresholds are configuration.

| # | Pack | Eng-wk | Scope |
|---|---|---|---|
| 5.1 | **Telecom fraud (WS-15, generic half)** | 6–9 | top ten typologies first: IRSF, Wangiri, SIM-box, premium-rate, roaming high usage, SIM-swap, subscription / identity, dealer activations, voucher / EVD, payment reversal — windows in `sql.template`, Alert Rules keyed on the offender (1.1) |
| 5.2 | **Telecom revenue assurance (WS-16, generic half)** | 6–9 | Reconciliation, re-rating, roll-forward and settlement controls on **canonical** synthetic schemas; the vendor-specific feed mapping is parked (§4) |
| 5.3 | **Payment fraud (WS-40…44, generic half)** | 18–28 | synthetic attempt / dispute / SIM-change corpus and a fail-closed card-number tripwire (WS-40); feature Datasets via `sql.template` (WS-41); payment typologies (WS-42); payment Risk Score with a **default** factor table as configuration (WS-43); disputes, labels with a maturity flag, payment KPIs (WS-44) |
| 5.4 | **Business assurance (WS-28, generic half)** | 3–5 | seasonal forecast (Holt-Winters in SQL) as a Measure function; margin model by product / channel / partner |

5.1 wave 1 can start as soon as 1.1 lands; it need not wait for all of waves 2–4.

### Totals

| Waves | Eng-wk |
|---|---|
| 1–3 (platform core) | ≈ 29–45 |
| 4 (integration, operability) | ≈ 18–30 |
| 5 (content) | ≈ 33–51 |
| **All generic work** | **≈ 80–126** |
| Parked for a real project (§4) | ≈ 15–25 |

About **85 %** of the bid plan's product effort can be built now; what is left for project start is
mostly integration against the customer's own systems.

## 4. Parked — needs the customer (do not start)

| Item | Why it waits |
|---|---|
| WS-03 feed validation | needs the customer's vendor CDR, HLR, PCRF samples |
| Vendor-specific halves of 5.1 / 5.2 | thresholds and mappings tuned to real feeds |
| WS-30 signalling-control partner integration | needs a partner choice |
| WS-45 payment gateway integration | needs the gateway's API |
| WS-46 telco risk-signal lookup | a commercial / privacy call (D-11 in the bid plan); generic in shape, so it can move up if the operator wants it as product |
| Customer-specific halves of WS-01 / 02 / 04 / 26 | customer hardware, volumes and sizing, penetration test, national control mapping, in-country hosting |
| The customer's factor table, Risk Tiers, payment scope, card-data posture, IP / BIN enrichment choice | the customer signs these; 5.3 ships defaults |
| Bid workstreams B-01 … B-08 | commercial |

**Not in this plan for other reasons:** WS-14 velocity / baseline Steps (Step Processor hold, operator
2026-09-23 — SQL windows cover detection meanwhile); WS-29 investigation evidential controls (owned by
[`link-analysis-backlog-plan.md`](link-analysis-backlog-plan.md), which is re-landing it); ML scoring and
Arabic / RTL (roadmap).

## 5. Operator decisions owed (internal — no customer needed)

Each has a recommendation, so a wave can proceed on it if the operator is away.

✅ **All ten answered 2026-09-26 (operator: "go with recommendations").** Each row's *Recommendation*
column is now the decision. D-P10 is decided as **one component kind, named Entity List**, serving both assurance and Link
Analysis. Waves 1–2
are on `BACKLOG.md` (D-P9).

| # | Decision | Blocks | Recommendation |
|---|---|---|---|
| D-P1 | Canonical names: **Entity List**, **Pending Change**, **Action Request**, **Risk Score**, **Risk Tier**, **Escalation Rule** (bid plan D-06) | 2.1–2.3, 3.2, 4.4 | adopt; GLOSSARY entries land with each item |
| D-P2 | Which edition bundles the intelligence module | 4.6 | Enterprise first |
| D-P3 | Offline vulnerability scanner and how its database reaches CI | 4.5 | a mirrored database snapshot refreshed on a connected host |
| D-P4 | Reverse the 2026-09-14 deletion of queues / escalation / watchers | 4.4 | escalation only (reassign, notify, raise priority); keep queues deleted |
| D-P5 | Expiring blocks (≤ 24 h) apply at once and are reviewed after; permanent ones need four-eyes | 2.2 | yes |
| D-P6 | Reverse `BI-4` (paths, not attachments) and choose the xlsx writer | 4.3 | yes, with a size cap; the writer is the already-staged DuckDB `excel` extension (no new dependency) |
| D-P7 | Name a near-real-time latency target | 4.1 | p95 ≤ 30 s event-to-Incident, measured |
| D-P8 | Classification-driven masking in audit hardening | 3.3 | defer; hash chain only |
| **D-P10** | One concept, two names: the assurance allow / block / watch list (bid plan: *Control List*) and the Link Analysis `LA-17` **Entity List** (a Space-scoped set of typed Entity keys with a purpose). | 2.2, `LA-17` | ✅ **DECIDED 2026-09-26 (operator): one kind, named Entity List** — the GLOSSARY name, so nothing is renamed. Purposes widen to allow · block · watch · exclusion; entries may be single keys or key ranges (number prefix, CIDR); Link Analysis consumes it through `excludeBy` / `seedBy`. *Control List* is not used. ⚠ **Storage reconciled 2026-09-26 (operator, `LA-17` D-M8):** "one kind" = one concept in ONE store — the per-Space append-only **Identity Fact** log that `LA-17` already ships (`EntityFactLog`, routes `/inv/entity-lists*`), NOT a `ComponentStore` kind; its purposes are already `allow · block · watch · exclusion`. WS-12 adds ranges / CIDR, `expires-at`, the Parquet sidecar and four-eyes to that store, and likely moves it out of `inspecto-geo-link` into core. See [`link-analysis-entity-model-design.md`](link-analysis-entity-model-design.md) §6. |
| D-P9 | Board rows: put each wave's items on `BACKLOG.md` now, or keep this plan off the board like the bid plan | scheduling | put waves 1–2 on the board now |

## 6. How each item is done

The house rules in `CLAUDE.md` and the skills apply unchanged: breaking changes are free; every new
route clears the four gates (OpenAPI entry, literal capability in `CapabilityManifest`, auth-gate
coverage, a real-HTTP test **with a Subject**); unit tests per change, the full reactor gate at the end
of each wave; a verification-subagent PASS; GLOSSARY in the same change. At the end of a wave, re-drive
the local assurance demo Space against it — it is the acceptance harness, but nothing from it is
committed.

## 7. Lifecycle

When a wave ships, distil its as-built facts into the matching OKF concept, move open residuals to
`BACKLOG.md`, and strike the wave here. When all five ship (or the operator stops the programme),
`git mv` this file to `archived-documents/plans-archive/`.
