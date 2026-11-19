---
type: Capability
title: Alerts & Incidents (INC) — Purpose, scope and requirements of record
description: Part 1 of 5 of Alerts & Incidents (INC): Purpose & scope; Requirements of record
tags: [inc, capability, alerts, alert-rules, incidents, cases, queues, escalation, sla, notifications, diagnosis, findings, retention]
timestamp: 2026-09-08T00:00:00Z
---

# Alerts & Incidents (INC) — Purpose, scope and requirements of record

Part 1 of 5 of [Alerts & Incidents (INC)](plan-incidents.md) · next: [Specification](plan-incidents-specification.md).

## 1. Purpose & scope

INC is everything that turns **a fact the platform noticed into work a person owns and closes**: the rule
that decides a metric crossed a line, the Alert that records it, the promotion of a serious Alert into an
Incident with an owner and a deadline, the Case that groups related Incidents, the escalation that fires
when the deadline passes, the notification that reaches someone, and the retention that eventually removes
the record. Its defining property is the **chain**: nothing in it is a free-standing list, every object is
linked to the one that raised it.

**In scope:** Alert Rules and their evaluation; fired Alerts; the promotion paths into Incidents (alert
severity, Decision-Rule consequences, Expectation and reconciliation breaches, the gap and conservation
bridges); the operational-objects domain — `ALERT` / `INCIDENT` / `CASE` / `TASK`, workflows, categorization,
priority, assignment, links, the resolution pattern, the SLA sweep and escalation policies, queues and
watchers; Cases with Contents, merge and split, Findings and Disposition, Case Rules; Notes and Tags as
Annotations; `incident_purge` retention and `objects.analytics`; the notification feed, channels, rules,
digests, preferences, delivery receipts and suppression; Diagnosis; and the Personal-edition gating of all of
it.

**Not in scope, and deliberately so:**

| Adjacent concern | Whose it is |
|---|---|
| The signal ledger itself, `EventLog`, the Events screen, Metrics exposition | `OPS` — Observability & maintenance. INC *consumes* the ledger (an Alert Rule reads it) and *emits* into it (`ALERT_FIRED`, `OBJECT_*`) |
| Expectations and Decision Rules as engines — the condition tree, `simulate`, record routing | `PIP` (execution) — `decision-rules.md`. INC owns only their `create-alert` / `create-incident` consequences |
| Tags as a cross-entity label graph over Datasets, Dashboards, Widgets, saved views | `MET` — `tags.md`. INC owns the Incident/Case adopter and Tag Rules |
| Who may author an Alert Rule or configure a channel (`canAuthorAlertRules`, `canAuthorWorkbench`) | `SEC` — the capability seam |
| The Assistant's tools, model settings, the agent runtime | `AGT` — Assistant. INC owns the *Diagnosis* product surface and its (absent) Incident hand-off |
| Which bundle carries `inspecto-ops` and `inspecto-notify-channels` | `PKG` — Editions & packaging. INC states *what* Basic loses, not how a module joins a profile |

## 2. Requirements of record

Five requirements from `REQUIREMENTS.md` §3.8 (that section was stripped to an index on 2026-09-09 — this file is their only home now). **Four of the five status or edition cells are wrong when read
against the build** — the largest correction rate of the three specs so far, and the reason it is worth
writing: three of the four went wrong on the same day, when EDG-01 cell 7 moved the whole objects domain
out of Basic (2026-09-08) and amended two neighbouring `EDITIONS.md` rows but none of these. ⚠
**`EDITIONS.md`'s feature × edition matrix is authoritative for the Edition column**; this table mirrors it.

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| `INC-1` | **Alert Rules** watch Metrics (and, since 2026-09-06, Measures); fired **Alerts** with severity | Must | ✅ SHIPPED — ⚠ the authoring form cannot express a Measure rule (§5). **+ 2026-09-23 (LA-23):** a fourth rule kind watches a Measure over a Link Analysis Investigation's Working Set (`investigation:` + `relation:` + `measure:`), authored ONLY through the owner-gated `POST /inv/investigations/{id}/alert-rules` (the generic `/alerts/rules` refuses it) and evaluated only while the owner's binding matches; fires through the same ALERT → CRITICAL Incident path, scoped to the Investigation. Reads the SEALED Working Set, not current data — [`link-analysis-backlog-plan.md`](../../../../archived-documents/plans-archive/link-analysis-backlog-plan.md) §5.8 | All (the *feed*); ALERT **objects** S/E; Investigation rules P/E (need `inspecto-geo-link`) |
| `INC-2` | **Alert → Incident → Case** lifecycle, object-link graph, SLA, comments | Must | ✅ SHIPPED | **S/E** — was `All`; `inspecto-ops` since 2026-09-08 (CP-11) |
| `INC-3` | **Notification** delivery channels (email / webhook) + per-user preferences | Must | 🟡 PARTIAL — feed + rules + receipts shipped; channels S/E; **preferences are one global set**; ~~`mail.send` reports success when no channel exists~~ ✅ returns **SKIPPED** since `ad29e683` (§5) | Feed All (CP-12) · channels **S/E** (CP-15) |
| `INC-4` | Incident workflow depth: queues, escalation, watchers | ⛔ **WITHDRAWN** | ⛔ **RETIRED 2026-09-14 (`RETIRE-HALVES-1`)** — the queue family, the three watcher routes and the escalation engine are **deleted from the code**, not merely undocumented. Assignment is now person-only (`POST /objects/{id}/assign` takes `assignee`); the Incident object, the SLA breach sweep and triage stay. ⚠ `watchers` survives as an object **attribute** (the merge union feeds it) with no route. Re-file when a customer names on-call escalation. 🔁 **Escalation REVERSED 2026-09-29 (D-P4, `ASSURE-WORKFLOW-SLA-1`)**: authored Escalation Rules + SLA policy + Workflow are back with an authoring UI (see *Incident governance* below); queues and watcher routes stay retired | **n/a** — withdrawn, not gated |
| `INC-5` | **Diagnosis**: AI-assisted RCA of a failing Run/Collector **producing an Incident** | Should | 🟡 **PARTIAL** — the RCA ships (`FailureReactor` → `DiagnosisStore`, `GET /assist/diagnoses`); **nothing creates an Incident from a Diagnosis** — the only bridge is a drafted Alert Rule (§3.9) | All |

**Corrections this table makes to its predecessor**, each verified against source:

- **`INC-2` and `INC-4` read `All`.** The whole `com.gamma.ops` domain — `/objects*`, `/notes*`,
  `/tags*`, `/workflows/{type}`, `/findings/{type}`, `/cases/rules*`, `/rca/templates` — is the
  optional `inspecto-ops` module since EDG-01 cell 7 (⛔ `/queues*` was in this list until
  `RETIRE-HALVES-1` retired it, 2026-09-14). A Personal bundle answers **46 paths `503`** naming
  the module (`AbsentModuleRoutes`), reports `features.ops = false`, and hides the Incidents, Case Manager
  and Tags navigation. `EDITIONS.md` CP-11 recorded this on the day; `OPS-01` and `SP-CTL-02` were amended
  on the day; these two rows were not. `BACKLOG.md` §5 lists `INC-3` as an edition-column mismatch and
  misses the two larger ones.
- **`INC-4` read a flat `SHIPPED`** over a backend-only delivery whose every mechanism had **no UI** —
  the shape that makes a register untrustworthy. ⛔ **Resolved by deletion 2026-09-14 (`RETIRE-HALVES-1`):**
  rather than build the missing UI, the operator retired the capability. `POST /queues`, the three
  `watch|unwatch|watchers` routes, `QueueStore`/`QueueRouter` and `EscalationPolicy` are gone, so the
  `QueueStore` Db-parity follow-on `REQUIREMENTS.md` noted in July is moot. What the SPA always had —
  a direct `assignee`, an `escalated` boolean and the Case `ESCALATED` state — is now the whole truth.
- **`INC-5` read `SHIPPED`** for a requirement whose defining clause is "producing an Incident". The
  diagnoser subscribes to failed consignments, stores read-only `Diagnosis` records in a 256-entry ring, and
  the UI's detail dialog offers a copyable `suggestedAlertRuleToon`. No code path in `com.gamma.agent.diagnose`
  or `com.gamma.assist` references `ObjectType.INCIDENT`, `IncidentAccess` or `ObjectService`. A Diagnosis
  reaches the incident workflow only if an operator turns the drafted rule into an Alert Rule and it later
  fires. `GLOSSARY.md` §9 defines Diagnosis as producing an Incident; the glossary describes the intent, the
  register must describe the build.
- **`INC-1`** is right as written but hides a seam: the `AlertRule` record carries the BI-5 Measure shape
  (`dataset` + `measure`, 2026-09-06) and the authoring pane's `ALERT_RULE_ATTRIBUTES` does not — a Measure
  rule can be authored only as TOON.

**Two edition-matrix rows this spec corrects in `EDITIONS.md`:** `JOB-01` lists `caserule.evaluate` and
`objects.analytics` and `JOB-03` lists `incident_purge`, all three ✅ for Basic — but all three are
registered by `inspecto-ops` (`OpsJobTypes`, `OpsMaintenanceTasks`) and are **unknown Job Types on a Basic
build**. Cell 7 amended its two named neighbours and not these; the same class of miss
`PROJECT_NOTES.md` warned about ("read what the neighbouring matrix rows promise").

**One standing note no status token can carry:** ⛔ **`/alerts*` is not in the gated set.** `AlertService`
lives in `inspecto-engine`, `AlertRoutes` in core, and the fired-alert feed is a bounded in-memory ring, so
every edition evaluates rules and serves `GET /alerts`. What Basic loses is the **Incident** half: no
Incident is promoted (`IncidentAccess` returns empty). A sequence gap or a conservation imbalance is a stored Alert on
every edition (`EventAlertBridge`, 2026-10-08), so `SP-CTL-02` is ✅ on Basic too. **Working a fired Alert (2026-10-07):** rule-fired Alerts live in the Alert-owned
store, not as objects, so an operator works one with `POST /alerts/{id}/ack` and `POST /alerts/{id}/resolve` (core
`AlertRoutes`, `canWorkIncidents`, every edition) — the `id` and lifecycle `state` ride each `GET /alerts` entry; an unknown
id is 404, a move illegal from the Alert's state (a second ack, anything after RESOLVED) is 422.
