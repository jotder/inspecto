---
type: Capability
title: Alerts & Incidents (INC)
description: The Alert → Incident → Case chain — Alert Rules and fired Alerts, promotion into managed Incidents, the operational-objects domain (lifecycle, SLA, escalation, queues, Cases, Findings, annotations, retention), notifications, and Diagnosis. The requirement of record for the INC area, its specification, its decisions, and what was refused.
resource: inspecto-ops/, inspecto-engine/src/main/java/com/gamma/alert, inspecto-engine/src/main/java/com/gamma/notify
tags: [inc, capability, alerts, alert-rules, incidents, cases, queues, escalation, sla, notifications, diagnosis, findings, retention]
timestamp: 2026-09-08T00:00:00Z
---

# Alerts & Incidents — capability spec (`INC`)

> **What this page is.** The single entry point for the INC capability: what was *required*, what is
> *built*, what is *left*, and what was *refused*. It is the front door to the mechanism, not a copy of it —
> §7 points at the `okf/` concepts that own the detail, and §5 points at `BACKLOG.md` rows rather than
> restating them. Third of the capability specs; the template is
> [`docs-consolidation-plan.md` §5.2](../../../archived-documents/plans-archive/docs-consolidation-plan.md); the area name and
> directory are fixed by [`GLOSSARY.md` §14](../../../GLOSSARY.md#14-capability-areas-the-functional-spine).
>
> **Canonical vocabulary** (`GLOSSARY.md` §4, §8, §9 — binding). The chain is **Alert → Incident → Case**.
> An **Alert Rule** watches an observability **Metric** (or, since 2026-09-06, a BI **Measure**) against a
> threshold and fires an **Alert**. An **Incident** is a tracked operational problem, lifecycle
> **Identified → Diagnosing → Resolved → Archived**; ⛔ never *Issue*. A **Case** groups related Incidents
> under one investigation. **Annotation** is the umbrella for a **Note** or a **Tag** hung off an **Annotation
> Target**. **Rule** is always qualified — Expectation / Alert Rule / Decision Rule / **Tag Rule** / **Case
> Rule** — never bare. A **Diagnosis** is an AI-assisted root-cause analysis of a failing Run or Collector. A
> **Notification** is the delivery of a Signal to a channel; **Event**, **Alert** and **Notification** are
> *views* over one signal ledger, never parallel stores.

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
| Which bundle carries `inspecto-ops` and `inspecto-notify-channels` | `PKG` — Editions & packaging. INC states *what* Personal loses, not how a module joins a profile |

## 2. Requirements of record

Five requirements from `REQUIREMENTS.md` §3.8 (that section was stripped to an index on 2026-09-09 — this file is their only home now). **Four of the five status or edition cells are wrong when read
against the build** — the largest correction rate of the three specs so far, and the reason it is worth
writing: three of the four went wrong on the same day, when EDG-01 cell 7 moved the whole objects domain
out of Personal (2026-09-08) and amended two neighbouring `EDITIONS.md` rows but none of these. ⚠
**`EDITIONS.md`'s feature × edition matrix is authoritative for the Edition column**; this table mirrors it.

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| `INC-1` | **Alert Rules** watch Metrics (and, since 2026-09-06, Measures); fired **Alerts** with severity | Must | ✅ SHIPPED — ⚠ the authoring form cannot express a Measure rule (§5) | All (the *feed*); ALERT **objects** S/E |
| `INC-2` | **Alert → Incident → Case** lifecycle, object-link graph, SLA, comments | Must | ✅ SHIPPED | **S/E** — was `All`; `inspecto-ops` since 2026-09-08 (CP-11) |
| `INC-3` | **Notification** delivery channels (email / webhook) + per-user preferences | Must | 🟡 PARTIAL — feed + rules + receipts shipped; channels S/E; **preferences are one global set**; `mail.send` reports success when no channel exists (§5) | Feed All (CP-12) · channels **S/E** (CP-15) |
| `INC-4` | Incident workflow depth: queues, escalation, watchers | Should | 🟡 **backend SHIPPED 2026-07-08, no UI** — queues, assignment, watchers and escalation are routes and TOON only; the queue store is in-memory only | **S/E** — was `All` ⛔ **WITHDRAWN 2026-09-10 (`CONSUMER-PAIRS-1`, operator):** the queue / watcher / escalation-policy route families are **RETIRED** — no client ever called them; the Incident object, assignment and triage stay. Re-file when a customer names on-call escalation → `BACKLOG.md` §3 `RETIRE-HALVES-1`. |
| `INC-5` | **Diagnosis**: AI-assisted RCA of a failing Run/Collector **producing an Incident** | Should | 🟡 **PARTIAL** — the RCA ships (`FailureReactor` → `DiagnosisStore`, `GET /assist/diagnoses`); **nothing creates an Incident from a Diagnosis** — the only bridge is a drafted Alert Rule (§3.9) | All |

**Corrections this table makes to its predecessor**, each verified against source:

- **`INC-2` and `INC-4` read `All`.** The whole `com.gamma.ops` domain — 31 files, `/objects*`, `/notes*`,
  `/queues*`, `/tags*`, `/workflows/{type}`, `/findings/{type}`, `/cases/rules*`, `/rca/templates` — is the
  optional `inspecto-ops` module since EDG-01 cell 7. A Personal bundle answers **49 paths `503`** naming
  the module (`AbsentObjectRoutes`), reports `features.ops = false`, and hides the Incidents, Case Manager
  and Tags navigation. `EDITIONS.md` CP-11 recorded this on the day; `OPS-01` and `SP-CTL-02` were amended
  on the day; these two rows were not. `BACKLOG.md` §5 lists `INC-3` as an edition-column mismatch and
  misses the two larger ones.
- **`INC-4` read a flat `SHIPPED`.** Every mechanism it names exists as a route (`POST /queues`,
  `POST /objects/{id}/assign|watch|unwatch`, `GET /objects/{id}/watchers`, `*_escalation.toon` applied by
  the SLA sweep) and **none has a UI**: the SPA has a single direct `assignee`, an `escalated` boolean and
  the Case `ESCALATED` state — no queue, watcher or escalation-policy surface. `QueueStore` has no Db
  implementation (`db-layer.md` says so; `REQUIREMENTS.md` called it "a noted follow-on" and no board row
  exists). A Should recorded green over a backend-only delivery is the shape that makes a register
  untrustworthy.
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
`objects.analytics` and `JOB-03` lists `incident_purge`, all three ✅ for Personal — but all three are
registered by `inspecto-ops` (`OpsJobTypes`, `OpsMaintenanceTasks`) and are **unknown Job Types on a Personal
build**. Cell 7 amended its two named neighbours and not these; the same class of miss
`PROJECT_NOTES.md` warned about ("read what the neighbouring matrix rows promise").

**One standing note no status token can carry:** ⛔ **`/alerts*` is not in the gated set.** `AlertService`
lives in `inspecto-engine`, `AlertRoutes` in core, and the fired-alert feed is a bounded in-memory ring, so
every edition evaluates rules and serves `GET /alerts`. What Personal loses is the **object** half: no
`ALERT` object is opened (`ObjectAccess` is empty), no Incident is promoted (`IncidentAccess` returns empty),
and `EventObjectBridge` is absent — which is exactly why `SP-CTL-02` is 🟡 on Personal (the gap *event*
fires, the ALERT *object* does not).

## 3. Specification

### 3.1 The chain, and where each link lives

| Link | Module | Personal |
|---|---|---|
| Alert Rules, evaluation, fired-alert feed, `ALERT_FIRED` | `inspecto-engine` `com.gamma.alert` + core `AlertRoutes` | ✅ |
| Promotion seam (`IncidentAccess`), `ObjectType`, `ObjectAccess` SPI, `AnnotationKinds`, `FindingsSpec`, `RcaTemplate` | `inspecto-engine` `com.gamma.objects` (core vocabulary) | present, inert |
| The objects domain: `ObjectService`, workflows, queues, escalation, notes, tags, Case Rules, `EventObjectBridge`, the three ops Job Types | **`inspecto-ops`** (`com.gamma.ops`, `com.gamma.opsapi`, `com.gamma.opsjob`) | ❌ 49 routes `503` |
| Notification feed, rules, preferences, receipts, suppression, `mail.send` | `inspecto-engine` `com.gamma.notify` + core routes | ✅ (no transport) |
| Delivery transports `SmtpEmailChannel`, `WebhookChannel` | **`inspecto-notify-channels`** | ❌ zero channels |
| Diagnosis | `inspecto-agent` `com.gamma.agent.diagnose` + core `com.gamma.assist` | ✅ |

### 3.2 Alert Rules

`AlertRule` (`inspecto-engine/src/main/java/com/gamma/alert/AlertRule.java`) is one record with **two
shapes**: the ledger-metric shape — `metric` ∈ `error_rate | failed_batches | rejected_files | duration_ms`,
comparator, threshold, `window`, optional `onPipeline` — and the **Measure shape** (BI-5, 2026-09-06) —
`dataset` (a Dataset component id) + `measure` (`count | agg(field)`, agg ∈ count / countDistinct / sum /
avg / min / max). Both take a `when` condition tree that scopes rows before aggregation (metric shape only).
`severity` ∈ `INFO | WARNING | CRITICAL`. Rules are files `<name>_alert.toon` under the write root
(2 committed examples, `spaces/demo/config/orders/`).

**Evaluation.** `AlertService` polls on a window-derived floor of 1 min, default 10 min
(`AlertService.java:411-413`); a breach emits `EventType.ALERT_FIRED` (`:227`) and the canonical
`alert-rule.fired` Signal (`:243`), and appends to a **bounded in-memory ring** the feed reads
(`GET /alerts`). `POST /alerts/evaluate` runs a sweep on demand. **The engine does not hot-load
`*_alert.toon`**: `POST/PUT/DELETE /alerts/rules[/{name}]` (`AlertRoutes.java:44-48`, gated
`canAuthorAlertRules`, fail-closed gate order, `ConfigCodec` + `AtomicFiles`) arm the rule in the running
`AlertService` in-process; a restart re-arms from the files. Since 2026-08-10 an `alerts` Platform Service
exists so `alert.evaluate` holds a real grant, and its dry-run stand-in must report "nothing was evaluated".

### 3.3 From an Alert to an Incident — five promotion paths, one seam

Every path that opens an Incident automatically does so through **`IncidentAccess`**
(`inspecto-engine/src/main/java/com/gamma/objects/IncidentAccess.java`) — a Platform Service `incidents`,
relocated to core and **narrowed** in EDG-01 cell 7 to return `Optional<String>` (the id) rather than the
object; the dry-run substitute records and opens nothing; on Personal it is empty and every caller
degrades to "no object".

| Path | Where | Rule |
|---|---|---|
| **Severity promotion** | `AlertService.promoteToIncident` (`:304-334`) | a `CRITICAL` (or `error`) rule opens a deduped `INCIDENT` — one open Incident per rule + pipeline — beside the `ALERT`; lower severities stay alerts. Since 2026-08-10 the Incident carries an **`ESCALATED_FROM` link to the ALERT** (actor `alert-rule:<name>`). ⚠ A *suppressed* promotion adds no edge |
| **Decision-Rule consequences** | `decision-rules.md` | `create-alert` records a signal **and** opens an object (2026-07-19); `create-incident` opens an Incident at any severity with no Alert Rule, deduped per rule (2026-07-24) |
| **Expectation breach** | `ExpectationRoutes` | the original signal → Incident dedup + open pattern the others reuse |
| **Reconciliation breach** | `ReconRunJob` (`jobs.md`) | same pattern, at **run** granularity — ONE aggregate Incident per reconciliation (scope = the reconciliation id), carrying only break counts |
| **A single reconciliation Break** | `POST /recon/promote` (`ReconRoutes`) | 🆕 **2026-09-11 (`BREAK-INCIDENT-1`)** — an operator promotes ONE Break from the board; deduped on `(reconciliation, key)` with `breakKey` as the dedupe attribute, carrying the break type, column and run id as evidence. ⚠ It **coexists** with the row above rather than replacing it: the Job says *"this reconciliation is breaching"*, a promotion says *"**this** Break is being worked"*. 🔴 The backlog row that asked for it claimed the tree's only promotion was Alert→Incident — `ReconRunJob` had been opening Incidents since it shipped, so the gap was **granularity, not mechanism** |
| **Ledger bridges** | `EventObjectBridge` (`inspecto-ops`) | `SEQUENCE_GAP` and `PIPELINE_CONSERVATION_IMBALANCE` (+ its legacy name) → an **ALERT object**, not an Incident |

Operator-created objects take the other door: `POST /objects` with **title + at least one link** (§3.4).
The auto-creation paths bypass the route and call `ObjectService.open` directly — which is why the first
object in an empty Space must come from one of them (there is nothing to link to yet).

### MTTR, and why `closedAt` could not supply it

`GET /objects/analytics?type=INCIDENT` has always reported a **`cycleTime`** — `closedAt − createdAt` over
terminal objects. 🔴 **That is not MTTR for an Incident, and reading it as such overstates every number.**
`closedAt` is stamped on the **terminal** state, and for an Incident the only terminal state is
`ARCHIVED` (`Workflow.defaultFor`: `IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED`). An Incident resolved
in two hours and archived a month later has a `closedAt` a month out. The analytics tile that showed this
count was even **labelled "Resolved"** until 2026-09-11 — it counts archived objects.

`INCIDENT-KPI-MTTR-1` (2026-09-11) adds **`ObjectService.ATTR_RESOLVED_AT`** (`resolvedAt`, epoch millis),
stamped in `commit()` — the single place every status change lands, so no transition path can bypass it —
whenever the target state is `RESOLVED`. `analytics()` then reports an **`mttr`** block beside
`cycleTime`, and the UI renders both with the server's own `definition` string underneath.

| Number | Anchor pair | Reads as |
|---|---|---|
| `cycleTime` | `createdAt` → `closedAt` | time to **archive** (the tidy-up is included) |
| `mttr` | `createdAt` → `resolvedAt` | time to **resolve** |

Three rules, each pinned by `ObjectServiceTest`:
* ⚠ **Most recent resolution wins.** A reopened Incident's first resolution did not hold, so measuring to
  it would report a fix that was not one. (Contrast `firstSeenAt` on a reconciliation Break, where FIRST is
  the meaningful end — there the question is *"how long has this been wrong"*, here *"how long until it was
  right"*.)
* ⛔ **An object with no recorded resolution is EXCLUDED from the mean, never counted as zero.** Everything
  resolved before this shipped has no stamp, so a freshly upgraded deployment reports `count: 0` and the UI
  shows an em-dash rather than a fabricated average.
* **The definition travels with the number.** Both blocks carry a `definition` string and the KPI tiles
  render it; a KPI whose meaning is implied is what this row exists to stop.

⛔ **MTTD is NOT built** and no number is published for it — deliberately, because it has no anchor.
Detection time needs a *first-signal* instant, and nothing records one on an Incident today. The proposed
anchor is **the earliest Signal at the Incident's `causationId` root → the Incident's `createdAt`**;
adopting it means reading the event store from the analytics path, which crosses a seam `ObjectService`
does not have today. Tracked as `INCIDENT-KPI-MTTD-1`. Publishing a placeholder would be worse than the
gap: an undefined KPI is indistinguishable from a measured one once it is on a dashboard.

### How a Break can be promoted when no Break is stored

🔴 **Reconciliation is stateless compute.** `POST /recon/run` and `/recon/breaks` recompute from SQL on
every call and persist nothing — a control probe for a domain `Break` type across the Java sources returns
zero, and the only match is `ReconService.BreakSet`, a transient paged result with no id and no store. The
C9 contract puts Break lifecycle on the **client** deliberately. So the Incident cannot hold a foreign key
to a Break row; there is none.

Instead the identity is **reconstructed from the request**: `(reconciliation, key)`, which is stable across
runs because it is what the comparison itself keys on. That pair is the dedupe key, so promoting the same
Break twice — by two operators, or after a nightly re-run — suppresses the second. ⛔ Do **not** "fix" this
by persisting Breaks to make the reference real: the Incident is the durable artifact, and that is the
design, not a shortcut.

Two consequences worth knowing before changing anything here:

- **The dedupe is on the KEY, not `(key, type, column)`.** One business key breaking on three columns is
  one thing for an operator to investigate. ⚠ When the same key later breaks a *different* way, the open
  Incident is reused and its attributes still describe the **first** observation.
- **Suppression lasts until the Incident is ARCHIVED, not merely RESOLVED.** Dedupe is over non-terminal
  objects, and for an Incident the only terminal state is `ARCHIVED`
  (`Workflow.defaultFor`: `IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED`). So an operator who resolves a
  promoted Break and sees it recur gets **no new Incident** until the old one is archived. That is the
  existing workflow's rule rather than this route's choice, and it is pinned by
  `ControlApiReconPromoteTest.suppressionLastsUntilTheIncidentIsArchivedNotMerelyResolved` — a change to
  the Incident terminal set would silently change how recurring Breaks behave.

⚠ The route reports a suppressed promotion as `{incidentId: null, deduped: true}` — it does **not** name
the surviving Incident, because the `IncidentAccess` seam reports suppression without returning an id and
widening the SPI for that alone was not worth it. A caller wanting the survivor lists the reconciliation's
Incidents (`GET /objects?type=INCIDENT`, correlation id = the reconciliation).

### 3.4 Incidents — the operational-objects domain

`ObjectType` = `ALERT | INCIDENT | CASE | TASK`, one table `inspecto_ops_objects` when durable, else
`InMemoryObjectStore` (`OpsEngineProvider.java:94-124` picks per store on `durable()`).

- **Workflows** (`inspecto-ops/src/main/java/com/gamma/ops/workflow/Workflow.java:150-192`): ALERT
  `OPEN → ACKNOWLEDGED → RESOLVED`; **INCIDENT `IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED`**, `reopen`
  from `RESOLVED|ARCHIVED → DIAGNOSING`, only `ARCHIVED` terminal; CASE `OPEN → INVESTIGATING → ESCALATED →
  RESOLVED → CLOSED`. `GET /workflows/{type}` serves the BFS-ordered states so a TOON-overridden workflow
  (`*_workflow.toon`, a boot-time scan) drives the same panes; `assign` **does not move status** (no
  `ASSIGNED` state); `/ack` is alert-only.
- **Categorization** is a 3-layer `attributes.category` path `L1/L2/L3` (`ObjectService.java:368-371`),
  enforced by the UI at latest on *Accept* (the `CategorizeDialog` is forced before the transition).
  **Priority** `CRITICAL · MAJOR · MINOR · LOW`. One **assignee** (the Incident Commander), optional at
  creation, set at triage — assignment is **direct**; queue-based routing at creation was deferred
  (product sign-off 2026-07-22).
- **Create contract** (2026-07-22): `POST /objects` requires a title (≤400) and a `links: [{to,
  relationship?} | id…]` array of **at least one** entry; every target is validated before `open()` so a
  dangling link cannot orphan the object; `relationship` defaults to `RELATED_TO`. `LinkRelationship` ∈
  `CONTAINS · ESCALATED_FROM · CAUSED_BY · RELATED_TO · MERGED_INTO · SPLIT_FROM`; the correlation graph is
  served depth-1.
- **The resolution pattern is a hard gate server-side** (I1, 2026-07-24): `ObjectService.commit`
  (`:1311-1316`) rejects `INCIDENT → RESOLVED` unless `attributes.postmortem` holds a non-blank **timeline**
  entry, a **cause analysis** entry (`causeAnalysis[]` + `causeMethod`, "5 Whys" default), a **corrective
  action**, and `dueAt` is set (`incidentResolutionGaps`, `:1342-1349`). The UI's `postmortemGaps` soft-warn
  is a *mirror* of the same four checks, not the gate. ⚠ `objects.md` and the archived design still call
  the backend gate "a follow-up"; it shipped.
- **SLA and escalation.** `dueAt` / `dueInMinutes` at creation; the sweep (`sweepIncidentSla`,
  `ObjectService.java:830-905`, cadence `-Dobjects.sla.sweep.seconds`, default 60, `0` disables) stamps
  `slaBreachedAt` once and emits `OBJECT_SLA_BREACH`; an **`EscalationPolicy`** (`*_escalation.toon`, 1
  committed example `spaces/demo/config/ops/sla_escalation.toon`) applied on breach bumps severity,
  re-routes to a queue and emits `OBJECT_ESCALATED`. The Case `targetDate` is a **loose** SLA — overdue hint
  only, no sweep.
- **Queues and watchers** (INC-4): `*_queue.toon` / `POST /queues`, members, routing `round_robin |
  least_loaded | manual` (`QueueRouter.pick`); `POST /objects/{id}/assign` (person or queue-routed) emits
  `OBJECT_ASSIGNED`; `POST /objects/{id}/watch|unwatch`, `GET /objects/{id}/watchers`. **`InMemoryQueueStore`
  is the only implementation** — "the lean default (INC-4)"; no `DbQueueStore` exists. **No UI** for any of
  it (§2, §5).
- **RCA templates** (`RcaTemplate`, `RcaTemplateRegistry`, `GET /rca/templates`, 1 committed example
  `orders_rca.toon`) seed the postmortem's cause-analysis structure; the **Postmortem** is the Incident's
  resolution artifact, the **Findings** are the Case's (§3.5).

### 3.5 Cases

A Case's **Contents** are the Incidents it `CONTAINS` — links, never an `attributes.caseId` (refused, §6).
**Merge** moves members, tags and watchers to the survivor and closes the absorbed Cases with a
`MERGED_INTO` link and marker (`ObjectService.java:993-1021`); **Split** carves members into a new Case tied
back by `SPLIT_FROM` (`:1052-1083`). Comments and attachments stay where they happened. A **Team** is
`attributes.assignees` (the lead stays `assignee`); **Findings** = **Disposition** (`confirmed ·
false-positive · recovered · written-off · inconclusive`) + impact + summary, with a *soft* no-disposition
prompt on resolve and a *soft* open-member warning on close (both deliberately not hard gates).

**Findings sections are deployment-authored** (D6 / C3, 2026-07-26): a `findings-spec` `ComponentStore`
kind per `ObjectType`, served by `GET /findings/{type}`, rendered by `<inspecto-schema-form>`; absent ⇒
`FindingsSpec.defaultFor()` reproduces today's exact shape; present ⇒ **fully replaces** the default
(field-level merge refused because it makes "remove a section" inexpressible). Values are validated on
`PATCH /objects/{id}` → `422` for a *declared* key only; an undeclared key is never rejected because
`attributes` is a shared bag. The `AttributeSpec` vocabulary is pinned by a cross-language contract
(`inspecto-ui/src/app/inspecto/contracts/attribute-spec.contract.json`, compared by `FindingsSpecContractTest` (Java) and `attribute-spec.contract.spec.ts` (TS)).

**Case Rules** (`CaseRule`, `/cases/rules`) are saved searches that auto-group: when ≥ *threshold* Incidents
match within a *window* they are grouped under one Case, opened or attached idempotently. Evaluation is on
demand from the pane **and** schedulable as the `caserule.evaluate` Job Type (`CaseRuleEvalJob`) — the
archived design's "scheduler auto-evaluation is a follow-up" is stale. `objects.analytics` samples
per-category counts into tall Parquet under `<dataDir>/ops_analytics/` as a `dataset` Run Artifact
(2026-07-25); binding it as a Studio Dataset is unbuilt (§5). ⚠ **No committed `*_case_rule.toon` or
`*_tag_rule.toon` exists.**

### 3.6 Annotations — Notes and Tags

Both address their subject as an **Annotation Target** `(targetKind, targetId)` from one shared vocabulary
(`AnnotationKinds`, core; `AnnotationTargets` resolves them and applies `RowScope`). A **Note** is a comment
or attachment *reference* (true upload refused, §6). **Tags** are a **central assignment store**, not a field:
`attributes.tags` is a *projection* since D7 (2026-07-26) — written on manual apply, Tag-Rule merge,
rule-raised creation, merge union and split, but never the source of truth. A **Tag Rule** is a saved
search that auto-tags matching Incidents/Cases on open and can be applied in bulk. The mail pane's tag menu
is still the only place a tag is *applied*; the `/tags` pane is a vocabulary pane. Mechanism: `tags.md`.

### 3.7 Retention — `incident_purge` and the D5 tiers

Retention is a **tier**, not a terminal state (D5, 2026-07-25): `CLOSED → ARCHIVED` and `ARCHIVED → purge`
are two separate windows. `incident_purge` (a maintenance task, `IncidentPurgeTask`, `inspecto-ops`):
`retention_days` **required, no default**; `max_count` default 1000; dry-run first; retention **derived**
from `closedAt + retention_days`, never stamped; a legal hold is fail-safe and re-checked inside `purge()`;
dependents (notes, links, tag assignments) are deleted **before** the object through **abstract** store
methods — a silent no-op `default` was refused because it would orphan rows quietly. Scoped to
`ObjectType.INCIDENT` because only its workflow has `ARCHIVED`. **G3 stance:** a purge is not "all trace
removed" — the append-only ledger keeps the Incident's `OBJECT_ACTIVITY`, including the purge record.
⛔ **Nothing schedules `incident_purge`, and nothing should**: a shipped default that hard-deletes business
records is indefensible; the operator opts in with a Job.

### 3.8 Notifications

Shipped 2026-06-29 as an **in-process MVP**, deliberately without a broker: `NotificationService` is an
`EventLog` subscriber handing off to a virtual-thread executor; append-only `EventStore`; feed routes
`/notifications/*` (list / unread-count / read / read-all / delete) and SSE `GET /notifications/stream`;
`NotificationRateLimiter` (rolling per-hour cap on identical notifications — the anti-loop safeguard);
`{{var}}` templates.

- **Channels.** `NotificationChannel` is a `ServiceLoader` SPI; in-app is intrinsic, **email and webhook are
  an edition seam** — `SmtpEmailChannel` (`notify.smtp.{host,from,to,user,pass}`) and `WebhookChannel`
  (`notify.webhook.{url,token}`) live in `inspecto-notify-channels` since EDG-01 cell 1 and **Personal
  registers zero channels**. Persisted destinations are a `channel` `ComponentStore` kind
  (`GET/POST/PUT/DELETE /notifications/channels[/{id}]`, `canAuthorWorkbench`, `kind=EMAIL` with an invalid
  target `422` at CRUD time) read live by dispatch; a `kind` with no discovered transport delivers nothing.
  ⚠ `ChannelConfig` has no `template` field, so a destination cannot override the rule-level template.
- **Rules** (`notification-rule` kind, `/notifications/rules*`, 2026-07-24) override the built-in
  event → notification table; chosen as a `ComponentStore` kind over a boot-scanned TOON, matching the
  channel precedent. `FLOW_CONSERVATION_IMBALANCE` gained a rule (category `ops`, `minLevel=WARN`) because
  the bridge opens an ALERT object for both directions.
- **Digest** is opt-in per destination (`digestMinutes`, 0 = immediate); in-app copies stay per event.
- **Preferences** are **one global set** (`NotificationPreferences`: "per-user preferences arrive with the
  auth module"), category × channel, critical categories locked on. The Notification Center's
  Preferences tab edits that one set.
- **Receipts and suppression.** Inbound delivery-status webhooks (D8, 2026-07-26): `statusAt` is
  `Map<DeliveryStatus, Long>`, first observation wins, verification precedes every write, an unknown
  `deliveryId` is `202`. `DbDeliveryReceiptStore` shipped 2026-09-07 and with it the **suppression
  policy**: complaint ⇒ permanent; hard bounce ⇒ TTL (`-Dnotify.suppression.bounce.ttl`, default `P30D`);
  ⛔ soft bounce **never** suppresses; `-Dnotify.suppression=off`. 🔴 It **arms itself only over a durable
  store** — an evicting cache answering "never bounced" for evidence it forgot must not collapse into
  "deliver". Unsuppress is an **override row** (`DELETE /notifications/suppressions?target=`) forgiving
  history up to its timestamp; a later bounce re-suppresses.
- 🔴 **`mail.send` succeeds with nothing sent.** `MailSendJob` returns
  `JobResult.ok("no email channel configured — nothing sent")` (`MailSendJob.java:81`) — a scheduled mail
  job reports green forever on a bundle with no transport. `BACKLOG.md` §6 records it as working-as-designed
  with a disclosure; it is listed in §5 because the register's `INC-3` cell names it as a defect.

### 3.9 Diagnosis

`FailureReactor` (`inspecto-agent/src/main/java/com/gamma/agent/diagnose/FailureReactor.java:17-33`)
subscribes to the `ConsignmentEventBus` for FAILED consignments and diagnoses them off-thread (bounded
queue + virtual threads) through `HeuristicDiagnoser` and, when a model is configured, `ModelDiagnoser`;
results land in `DiagnosisStore`, a **ring of 256**, and are served read-only by `GET /assist/diagnoses`.
The UI (`/diagnoses`, `DiagnosisDetailDialog`) shows root cause, a heuristic-only flag and a
**`suggestedAlertRuleToon`** the operator can copy. **That is the whole bridge to the chain.** No code in
`com.gamma.agent.diagnose` or `com.gamma.assist` opens an Incident, and no UI affordance launches a
Diagnosis from a failing Run — it is reactive to failure events only. The requirement's "producing an
Incident" is unbuilt (§2, §5).

### 3.10 What Personal loses, and how

`ObjectAccess` (core SPI, `Optional.empty()` on Personal) is provided by `OpsEngineProvider`, which
`inspecto-ops` registers under the **`com.gamma.service.ObjectEngineProvider`** service file — that file, plus
the module's `RouteModule`, `JobTypeProvider` and `MaintenanceTaskProvider` registrations, is the whole switch. Because the objects domain was **constructed by mandatory core**, cell 7
also needed a host-declared `ObjectEngineProvider` handle beside the seam — opening the stores needs
`SpaceRoot` / `OperationalDb` from the module above the engine. With the module absent:
`AbsentObjectRoutes` answers **49 paths `503`** naming the module; `BootstrapRoutes` sets `features.ops =
api.hasRoute("POST", "/objects")` (`:82`) — derived from what registered, not declared; the SPA drops the
`incidents`, `cases`, `tags` nav ids and the Incidents / Case Manager / Incident-detail panes render an
explained `<inspecto-alert>` ("Operational objects not installed"), never a toast; `DecisionRoutes`
(`:193-253`) splits its outcome three ways so an absent module is never reported as "Incident already open"
— the defect verification caught. Three ops Job Types leave with the module (§2). ⛔ `/alerts*` stays.

### 3.11 The UI seam

One `ObjectMailComponent` serves `/incidents` and `/cases` by route data — a Gmail-metaphor 3-pane shell
(folders My Cases / Escalated / Identified / Diagnosing / Resolved / Archived + Tags · list · detail) reading
`GET /workflows/{type}` rather than hard-coding transitions. Triage is **optimistic** (every bulk verb
patches rows to the expected post-state, then reconciles with the server object); merge / split / create are
request → refetch. `/alerts` (`AlertsComponent`) lists fired alerts and Alert Rules with a manual Evaluate
and the `AlertRuleFormDialog`, gated on `LensService.canAuthorAlertRules`. The **Notification Center** has
Channels / Rules / Deliveries / Preferences tabs and the bell reads the SSE stream with polling fallback.
`/diagnoses` is a data table with a detail dialog. ⚠ Neither the Notification Center nor the preferences
pane has an `okf/frontend` feature page (`features/index.md` says "not yet documented"); `alerts.md` and
`diagnoses.md` are stubs under 1 KB.

### 3.12 Public API surface

`@PublicApi(since = "4.0.0")` on: `ObjectType`, `ObjectAccess`, `IncidentAccess`, `AnnotationKinds`,
`FindingsSpec`, `RcaTemplate`, `TagAssignment`, `IdScheme` (core vocabulary, `inspecto-engine`);
`ObjectService`, `ObjectStore`, `DbObjectStore`, `InMemoryObjectStore`, `ObjectQuery`, `OperationalObject`,
`EscalationPolicy` (`inspecto-ops`). **Not** marked: `AlertRule`, `EventObjectBridge`, `OpsEngineProvider`.
⚠ The `@PublicApi` marker records *intent*, not exposure — nothing after 3.x has shipped
(`api-stability.md`). Making the dependent-cascade store methods **abstract** in MNT-14 was a MAJOR widening
of an unreleased surface, and was chosen anyway because a default would orphan rows.

## 4. Decisions

Dated, one line each, with the reason. Only decisions that still bind are listed; where one reversed an
earlier one, both appear.

### The chain and its vocabulary

| Date | Decision | Why |
|---|---|---|
| 2026-06-29 | Notifications ship as an **in-process MVP** — virtual-thread executor + append-only `EventStore`, **no broker** | A broker is a deployment dependency the target user counts do not justify; the idiom is the same one the run-claim seam uses |
| 2026-06-29 | The SaaS-shaped requirement was cut to fit: **one global preference set**, IP + UA not GeoIP, login/MFA events deferred, 401/403 audited as `ACCESS_DENIED` | The core is auth-free; there is no user to prefer per |
| 2026-07-06 (product) | R5 Decision Network: Alert-Rule and Expectation consequences mapped into the **`Consequence`** vocabulary; full kind promotion deferred | One word for "what a decision engine produces" across three rule engines |
| 2026-07-07 (product) | **Build the Alert-Rule authoring pane**; new capability `canAuthorAlertRules` | Of the three rule engines Alert Rules alone had no UI authoring |
| 2026-07-09 | Alert-rule writes **arm in-process**; the engine never hot-loads `*_alert.toon` | One arming path; a file watcher would be a second one the write route has to agree with |
| 2026-07-12 (product) | `/incidents` + `/cases` become a **mail-like 3-pane UI**; lifecycle renamed **`IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED`**; priority `Critical · Major · Minor · Low` | Triage is an inbox; the old `OPEN → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED` encoded assignment as a state |
| 2026-07-12 | `assign` **does not move status**; `/ack` stays alert-only; only `ARCHIVED` is terminal; reopen clears `closedAt` | Ownership and progress are different axes |
| 2026-07-12 | Case membership is **`CONTAINS` links**; `LinkStore` gains `remove()` (an amendment to its append-only contract) | One representation — the graph the UI and glossary already use |
| 2026-07-12 | Merge survivor: the absorbed Case is **`CLOSED` + `mergedInto`**, not a `MERGED` state | A state that means "look elsewhere" is a link wearing a status |
| 2026-07-12 | Open-member gate on Case close is a **soft warning** | Revisit only if the warning proves insufficient |
| 2026-07-19 | **Severity promotion**: `CRITICAL`/`error` rules open a deduped Incident beside the Alert | A high-severity breach must enter triage, not only a feed |
| 2026-07-19 | `create-alert` records a signal **and** opens an object | A consequence that only logs is not actionable |
| 2026-07-22 (product) | **Create contract**: title + **≥ 1 link** mandatory; assignment **direct**, queue routing at creation **deferred** | An unlinked object is not useful; there is no multi-analyst consumer yet |
| 2026-07-24 | **Resolution is hard-gated server-side** (I1): `commit()` rejects `→ RESOLVED` without the four postmortem sections | A UI-only check is bypassed by the API |
| 2026-07-24 | `create-incident` consequence: an Incident at any severity, deduped per rule | Some conditions warrant triage without being alerts |
| 2026-07-24 | Notification **rules** are a `ComponentStore` kind, not a boot-scanned TOON | Matches the `channel` precedent; the kind inherits CRUD, ETags and history for free |
| 2026-07-24 | Digest is **opt-in per destination**; in-app copies stay per event | Batching is a delivery choice, not a fact about the event |
| 2026-07-25 | **Retention is a tier** (D5): `CLOSED → ARCHIVED` and `ARCHIVED → purge` are two windows | "Archived" must not mean "about to be deleted" |
| 2026-07-26 | **D7**: tags become a generic cross-entity concept — *this* system generalized, not a second one; `attributes.tags` becomes a projection | Two tag systems would drift; the old row's claim that nothing wrote `attributes.tags` was simply false |
| 2026-07-26 | **D6 / C3**: Findings sections are a `findings-spec` kind that **fully replaces** the default; values validated `422`; the gate lives in `ObjectRoutes` | Field-level merge makes "remove a section" inexpressible; the engine stays store-agnostic |
| 2026-07-26 | D8 receipts: **first observation wins**; verification precedes every write; unknown id `202` | A provider retries; a later duplicate must not rewrite history |
| 2026-07-27 | **MNT-14 `incident_purge`**: `retention_days` required, derived not stamped, legal hold fail-safe, dependents first through **abstract** methods, `INCIDENT` only, **never scheduled by default** | A shipped default that hard-deletes business records is indefensible; a silent no-op default would orphan rows |
| 2026-07-27 | **G3**: a purge keeps the Incident's ledger trace, including the purge record | The ledger is append-only; that is the compliance claim |
| 2026-08-10 | The promoted Incident is **linked `ESCALATED_FROM`** its Alert | The correlation must be traversable in the graph, not implied by shared attributes |
| 2026-08-10 | An `alerts` Platform Service so `alert.evaluate` holds a real grant; dry-run must report "nothing evaluated" | A decorative grant hides a job that silently did nothing |
| 2026-08-15 | The `AttributeSpec` vocabulary is pinned by a **cross-language contract test** | A type added only in TypeScript made the server `422` a section the renderer could draw |
| 2026-09-06 | **Alert Rules widened to Measures** (BI-5): one record, two shapes; a thresholded Measure is a KPI alert | One rule kind for both senses of "a number crossed a line" |
| 2026-09-06 | Bounce/complaint handling **stays manual until receipts persist**; no auto-disable of a channel | One bad address must not silence a channel |
| 2026-09-07 | Suppression: complaint permanent, hard bounce TTL, **soft bounce never**, armed **only over a durable store**; unsuppress is an override row | An evicting cache cannot distinguish "clean" from "forgot"; pruning receipts would mask a dead address |

### Editions

| Date | Decision | Why |
|---|---|---|
| 2026-09-02 | **CP-12 split**: the in-app feed stays in every edition; delivery channels are CP-15 (Standard+) | The feed is the product's nervous system; a transport is a deployment |
| 2026-09-07 (operator) | Gating is **`ServiceLoader` modules**, never `-D` switches | A switch leaves the code in the bundle |
| 2026-09-07 | Cell 1: channels move to `inspecto-notify-channels`; Personal registers **zero** | `SmtpEmailChannel` had reached Personal through the connector sidecar, which is not edition-gated |
| 2026-09-08 (operator) | Cell 7: the **whole `com.gamma.ops` domain** becomes `inspecto-ops`; `OPS-01` and `SP-CTL-02` amended; ⛔ `/alerts*` stays | Operational objects are a multi-operator workflow; Personal is single-user. A gap still raises the *event* everywhere |
| 2026-09-08 | `features.ops` is **derived** from whether `POST /objects` registered | The flag cannot disagree with the build (contrast `authMode`, `SEC` §2) |

## 5. Not built

⛔ **Pointers, never copies.** Each row names its board id; the board is the authority for status and
priority. A row with no id is flagged `UNTRACKED` and needs filing before it can be scheduled.

### Tracked

| Item | Board id | What remains |
|---|---|---|
| D8 notification residuals — soft-bounce retry scheduling, an SES/SNS adapter, GeoIP, auth-gated per-user preferences | `BACKLOG.md` §3 *Notifications (D8 residuals)*; §3 *D8-SUPPRESS-1* | The SNS adapter needs its own review (subscription confirmation + outbound cert fetch from an unauthenticated callback) |
| `findings-spec` authoring UI | `BACKLOG.md` §3 *D6 spec-authoring UI* | Authored as TOON through generic `/components` CRUD today; nothing is broken without it |
| `kpi.completeness` Job Type — signal + deduped Incident on breach | `BACKLOG.md` §3 *Completeness KPI* K4 | Gated on the hold lifting |
| `CaseStore` interface + Postgres implementation; the 256-cap ring blocks AGT-5 embedding recall | `BACKLOG.md` §3 *Postgres multi-user*; §6 | Parked with the multi-user plan |
| MNT-14 residuals — no UI, no shipped Job instance, retention derived not stamped, `INCIDENT` only | `BACKLOG.md` §6 *MNT-14* | Standing; the operator opts in |
| Digest deliveries correlate to the digest, not per notification | `BACKLOG.md` §6 | `deliverWithReceipt` is the escape hatch |
| D7 startup backfill is a full object scan | `BACKLOG.md` §6 | Deliberately unfixed: nothing measures startup |
| `mail.send` succeeds with nothing sent | `BACKLOG.md` §6 (working-as-designed, disclosed) | Listed because `REQUIREMENTS.md` INC-3 calls it a defect — the two boards disagree on its *category*, not its existence |

### `UNTRACKED` — surfaced by this spec, no board row

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `SPEC-GLOSSARY-1`.
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`archived-documents/plans-archive/post-consolidation-sprints.md`](../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.

| Item | Evidence | Why it matters |
|---|---|---|
| **Diagnosis → Incident** — the defining clause of `INC-5` | no `INCIDENT` / `IncidentAccess` reference under `com.gamma.agent.diagnose` or `com.gamma.assist` | A Should recorded shipped; the only bridge is a copyable drafted Alert Rule. Needs a product call: auto-open (deduped per consignment, through `IncidentAccess`) or retitle the requirement |
| **Queue / watcher / escalation-policy UI** — `INC-4`'s user half | no `/queues`, `/watch` or escalation-policy consumer in `inspecto-ui/src/app` | Routes and TOON exist; an operator cannot see a queue, a watcher list or a policy in the product |
| **`QueueStore` Db parity** | `InMemoryQueueStore` is the only implementation; `db-layer.md` row "none" | Queues and their membership vanish on restart; `REQUIREMENTS.md` called this "a noted follow-on" in July and nothing filed it |
| **Measure rules in the authoring form** | `AlertRule` has `dataset` + `measure`; `ALERT_RULE_ATTRIBUTES` has neither | The 2026-09-06 widening is TOON-only; the pane cannot express the glossary's own definition |
| **`JOB-01` / `JOB-03` promise Personal three ops jobs** | `OpsJobTypes`, `OpsMaintenanceTasks` in `inspecto-ops` | Corrected in `EDITIONS.md` with this spec (notes on both rows) — recorded here because it is the cell-7 miss class, and the next gating cell will hit it again |
| **`ChannelConfig.template`** — a destination cannot override the rule-level template | `events-metrics.md` "still open"; the plan it rode is archived | The slice's home (`event-signal-backbone-plan` S2) is in the archive and no row survived it |
| **Studio-Dataset binding of `objects.analytics`** | `case-analytics-dataset-plan.md` is DRAFT, no code, uncited | The Parquet exists; nothing reads it as a Dataset |
| **Notification Center / preferences have no `okf/frontend` page**; `alerts.md` and `diagnoses.md` are stubs | `features/index.md` "not yet documented" | The UI half of INC has less concept coverage than any other area so far |

## 6. Refused & superseded

**This section exists because a refused idea with no recorded refusal gets re-proposed.** `BACKLOG.md` §6
does this for *work*; this does it for *design*, per capability. Each row states what was refused and the
reason — the reason is the load-bearing half.

### 6.1 Assignment as a state; membership as a field — REFUSED (2026-07-12)

The old lifecycle carried `ASSIGNED` and `IN_PROGRESS`; the mail redesign removed both. Ownership
(`assignee`) and progress (status) are different axes, and encoding one in the other made "reassign" a
state transition. Likewise Case membership as `attributes.caseId` was refused: it would create a second
representation beside the `CONTAINS` links the glossary, the UI and the correlation graph already use.

### 6.2 Hard gates where a soft warning was chosen — and the one place the reverse happened

Case close with open members, and Case resolve without a Disposition, are **soft warnings** by decision
("revisit only if insufficient"). Incident resolution is the deliberate exception: I1 made the four-section
postmortem a **server-side `422`**, because a UI-only check is bypassed by the API. ⚠ Two current docs
(`objects.md`, the archived `case-management-design.md`) still describe the backend gate as a follow-up;
`PROJECT_NOTES.md` and `ObjectService.commit` are right.

### 6.3 A message broker; email in core — REFUSED (2026-06-29)

Redis / RabbitMQ / Kafka were considered and refused: the in-process virtual-thread executor over an
append-only `EventStore` is the house idiom and the target user counts do not justify a broker. Email is an
**edition seam**, never core — a transport is a deployment concern and Personal ships none.

### 6.4 Auto-disable on bounce; collapsing hard and soft bounce; pruning receipts to unsuppress — REFUSED (2026-09-06/07)

One bad address must not silence a channel, so suppression is **per recipient**. Soft bounce never
suppresses — collapsing it with hard bounce would stop mail to anyone once over quota. "Unsuppress by
deleting the receipts" was rejected twice over: it is audit loss, and a permanent mask over a genuinely dead
address; the override row forgives history up to a timestamp and lets a later bounce re-suppress.

### 6.5 Findings: field-level merge, the `*_workflow.toon` pattern, `ConfigSpecs` reuse, `group`/`secret` — REFUSED (2026-07-26)

A present `findings-spec` **replaces** the default whole because merge makes "remove a section" inexpressible.
Cloning the C6 `*_workflow.toon` pattern was not viable — it is a boot-time scan of CLI path arguments with no
write root, no CRUD and no hot reload, a config surface an operator cannot edit through the product. Mapping
`AttributeSpec` onto backend `ConfigSpecs` / `FieldSpec` was lossy for zero reuse. `group` and `secret` are
frontend-only keys and a `422` on a findings-spec by design — `secret` on a stored-and-returned attribute
would mislead.

### 6.6 `incident_purge` defaults — REFUSED (2026-07-27)

No default `retention_days`; no scheduled instance; no silent `default` on the dependent-cascade store
methods (shipped **abstract**, a MAJOR widening, because a default orphans rows quietly); no per-store hooks
beside `JobService.objects()` (four independently-nullable fields would reintroduce the half-cascade). A
purge is not "all trace removed" (G3).

### 6.7 Receipt design refusals (D8, 2026-07-26)

No provider-minted delivery id (the id is minted locally and embedded outbound, so the `@PublicApi`
signature did not widen); no signature verification against a re-serialised `Map` (key order and whitespace
do not survive); not the Ed25519 SendGrid verifier the plan specified — it is ECDSA P-256 / SHA-256.

### 6.8 Case-management non-goals (2026-07-12)

No true file upload for Case documents (references only — upload touches the air-gap and attack-surface
posture and needs its own design); no auto-merge "similar cases" scoring (Case Rules are the hook); no
moving comments or attachments between Cases on split (history stays where it happened); Cases do not get
postmortems and Incidents do not get Contents, split/merge or a Team.

### 6.9 `-D` switches for edition gating; scheduling `incident_purge` — REFUSED (operator, 2026-09-07 / 2026-07-27)

Recorded here because both are INC consequences: a switch leaves the objects domain in the Personal bundle,
and a default purge deletes business records.

### 6.10 Superseded designs — what replaced them

| Superseded | By | Where recorded |
|---|---|---|
| `OPEN → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED` Incident lifecycle; *Issue* | `IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED`; *Incident* (2026-07-12) | `GLOSSARY.md` §9, §13 |
| The list-and-detail `objects.component` | the 3-pane `ObjectMailComponent` (the old component was deleted) | `objects.md` |
| `fiveWhys[5]` | variable-length `causeAnalysis[]` + `causeMethod`; legacy migrates on parse (I2) | archived `case-management-design.md` |
| `CASE_DISPOSITIONS` in `mail-model.ts` | the backend default ladder + `findings-spec` | `objects.md` |
| `attributes.tags` as storage | a projection of the central assignment store (D7) | `tags.md` |
| "The engine hot-loads `*_alert.toon`" | in-process arming by the write routes | `events-metrics.md`, the archived authoring plan's own correction |
| Alert Rules as raw files only | `ComponentStore`-backed CRUD (the `alert-rule` kind) | `decision-rules.md` |
| "Scheduler auto-evaluation of Case Rules is a follow-up" | the `caserule.evaluate` Job Type | `jobs.md` |
| "The backend resolution gate is a follow-up" | I1 shipped 2026-07-24 (`ObjectService.commit`) | `PROJECT_NOTES.md` §3 |
| MNT-14 plan §3 G1 / G4 framing | marked wrong in the plan's own header; `jobs.md` is the account | archived `mnt-14-incident-retention-plan.md` |
| D6's two premises ("reuse the C6 workflow/TOON pattern", "the `attribute-spec` renderer" = `ConfigSpecs`) | the `findings-spec` kind and the frontend `AttributeSpec` vocabulary | `objects.md` |
| `DecisionConsequence` | `Consequence` (aliased for back-compat) | archived `decision-network-plan.md` |
| The operations reference's curl walkthrough (`type=ISSUE`, `assign` → `start`, `-Dcontrol.token`) | rewritten with this spec: `INCIDENT`, `accept` → `resolve`, no token plane | `operations-reference.md` |
| `USER_GUIDE.md`'s "open → in-progress → resolved" and "list-and-detail" | corrected with this spec | `USER_GUIDE.md` |
| `STAKEHOLDER_OVERVIEW.md`'s "Issue & case management … open → assigned → in-progress → resolved → closed", "(cases/issues)", unconditional Personal availability | plan §8 cluster — reconciled at step 6, not here | — |

## 7. As-built mechanism (pointers only)

| Mechanism | Owning file | `resource:` | Read it for |
|---|---|---|---|
| Alert-rule authoring, severity promotion, the `ESCALATED_FROM` edge, notifications end to end (channels, rules, digest, receipts, suppression) | `docs/okf/backend/control-plane/events-metrics.md` (`Concept`) | `inspecto/src/main/java/com/gamma/control` | the long-form account §3.2, §3.3 and §3.8 were distilled from |
| `caserule.evaluate`, `objects.analytics`, `mail.send`, **`incident_purge`** (D5 tiers, G3, the abstract cascade) | `docs/okf/backend/control-plane/jobs.md` (`Concept`) | `inspecto-engine/src/main/java/com/gamma/job` | the retention account and the Job Types |
| `create-alert` / `create-incident` consequences; Alert Rule `when` | `docs/okf/backend/control-plane/decision-rules.md` (`Concept`) | `inspecto/src/main/java/com/gamma/control` | how a Decision Rule opens an object |
| Tags as a cross-entity graph; the Incident/Case adopter; Tag Rules | `docs/okf/backend/control-plane/tags.md` (`Concept`) | `inspecto-ops/src/main/java/com/gamma/ops/tag` | addressing, the central store, the projection |
| `inspecto_ops_objects`, durable vs in-memory stores, **"queues: none"**, `DbDeliveryReceiptStore` | `docs/okf/backend/engine/db-layer.md` (`Concept`) | `inspecto-engine` | what persists |
| Edition gating as a mechanism; the cell-7 recipe items 18–27 | `docs/okf/backend/editions/editions-model.md` (`Concept`) | `pom.xml` | `ObjectAccess` + `ObjectEngineProvider`, the census traps |
| The SLA sweep flags and the lifecycle walkthrough | `docs/okf/backend/build-run/operations-reference.md` (`Reference`) §Retention · §SLA | — | the operator's curl sequence (rewritten with this spec) |
| The Incidents & Cases UI: mail shell, create contract, optimistic triage, Findings spec, D7 | `docs/okf/frontend/features/objects.md` (`Feature`) | `inspecto-ui/src/app/modules/admin/objects` | the only page titled for the domain |
| Alerts pane · Diagnoses pane · Events pane | `docs/okf/frontend/features/alerts.md` · `diagnoses.md` · `events.md` (`Feature`) | `inspecto-ui/src/app/modules/admin/{alerts,diagnoses}` | ⚠ the first two are stubs under 1 KB |
| 🔴 **The operational-objects backend has no concept file.** `ObjectService`, the three workflows, categorization, the I1 gate, the SLA sweep, `EscalationPolicy`, queues and watchers, merge/split, Case Rules, `IncidentAccess`, `EventObjectBridge` are documented only in source, in eight scattered backend pages, and in this spec | *(none — gap; the plan predicted it: "no dedicated backend concept file exists anywhere")* | `inspecto-ops/src/main/java/com/gamma/ops` | the code. The `control-plane/index.md` enumeration has no Incident / Case / Objects entry at all |
| 🔴 **Notification Center and preferences have no feature page** | *(none — gap)* | `inspecto-ui/src/app/modules/admin/notification-center` | the code |

---

## 8. Verification

### 8.1 The objects domain — `inspecto-ops/src/test/` (39 files; runs only under `-Pedition-standard|enterprise`)

⚠ **The default `mvn -o clean test` does not compile `inspecto-ops`.** A green default build proves nothing
about the rows below; Standard is 31 modules / 4106 tests, Enterprise 32 / 4126 at HEAD.

| Class | Proves |
|---|---|
| `ObjectServiceTest` · `ObjectCoreTest` · `WorkflowTest` | the three workflows, `reopen`, `ARCHIVED` terminal, the I1 resolution gate, categorization |
| `ObjectServiceQueueTest` · `ControlApiQueueRoutesTest` | `QueueRouter` routing, assign / watch / watchers routes |
| `ObjectServiceCaseGroupTest` · `ControlApiCaseGroupTest` | Contents, merge (`MERGED_INTO`), split (`SPLIT_FROM`) |
| `ObjectServiceCaseRuleTest` · `ControlApiCaseRuleTest` · `CaseRuleEvalJobTest` | Case Rules: threshold + window, open-or-attach idempotence, the Job Type |
| `EventObjectBridgeTest` | `SEQUENCE_GAP` / conservation → ALERT object |
| `IncidentAccessTest` | the narrowed seam, dry-run opens nothing |
| `AlertServicePersistenceTest` | fired alerts persisted as objects when the module is present |
| `ControlApiFindingsSpecTest` | `GET /findings/{type}`, the `422` value gate, frontend-only keys refused |
| `ControlApiObjectsTest` · `ControlApiObjectsPageTest` · `ControlApiScopedObjectsTest` | the create contract (≥1 link, `400`/`404`), paging, SEC-7d data scopes |
| `ControlApiNoteRoutesTest` · `ControlApiTagRoutesTest` · `TagRuleTest` · `ObjectTagProjectionTest` · `TagAssignmentCoreTest` | Annotations: notes, tags as a projection, Tag Rules |
| `IncidentPurgeTaskTest` · `RetentionSweepSeamTest` | `retention_days` required, dry-run, legal hold, dependents first |
| `ObjectsAnalyticsJobTest` | the tall Parquet artifact |
| `DbObjectStoreTest` · `InMemoryObjectStoreTest` · `DbLinkStoreTest` · `LinkCoreTest` · `NoteCoreTest` | store parity — ⚠ no queue-store counterpart exists to test |
| `ControlApiDecisionRulesTest` · `ControlApiExpectationTest` | `create-alert` / `create-incident`; the Expectation → Incident path |
| `WorkflowConfigLoadTest` | `*_workflow.toon` boot-time override |

### 8.2 Alerts, notifications and diagnosis — the default reactor

| Class | Module | Proves |
|---|---|---|
| `AlertRuleTest` | `inspecto-engine` | both record shapes parse; `when` scoping |
| `ControlApiAlertRuleWriteTest` | `inspecto` | the write routes' gate order, in-process arming, `canAuthorAlertRules` |
| `NotificationServiceTest` | `inspecto-engine` | subscriber hand-off, rate limiter, preferences gating |
| `ControlApiNotificationsTest` · `ControlApiNotificationChannelsTest` · `ControlApiNotificationRulesTest` · `ControlApiNotificationStreamTest` | `inspecto` | feed routes, channel CRUD (`422` on a bad EMAIL target), rules, the SSE stream |
| `ControlApiCollectorNotifyTest` | `inspecto` | the ACQ push-notify seam that feeds the ledger |
| `RcaTemplateTest` · `CollectorServiceRcaTest` | `inspecto-engine` · `inspecto` | RCA templates and their registry |
| `FailureReactorTest` · `ModelDiagnoserTest` | `inspecto-agent` | failure subscription, off-thread diagnosis, the ring |
| `SmtpEmailChannelTest` · `WebhookChannelTest` | `inspecto-notify-channels` | the transports (edition profile only) |
| `NoChannelShipsInThePersonalBuildTest` | `inspecto` | Personal registers zero `NotificationChannel` providers |

### 8.3 UI specs — `inspecto-ui/src/app/` (vitest)

Fifteen specs sit on this mechanism: `object-mail.component.spec.ts`, `object-detail.component.spec.ts`,
`case-contents.component.spec.ts`, `merge-cases.dialog.spec.ts`, `object-create.dialog.spec.ts`,
`object-link.dialog.spec.ts`, `postmortem-panel.component.spec.ts`, `tag.dialog.spec.ts` (the mail pane,
contents, merge, create contract, linking, postmortem, tagging); `alerts.component.spec.ts`,
`alert-rule-form.dialog.spec.ts`; `notification-center.component.spec.ts`, `channel-form.dialog.spec.ts`,
`rule-form.dialog.spec.ts`, `notifications.component.spec.ts` (the Center and the bell);
`diagnoses.component.spec.ts`, `diagnosis-detail.dialog.spec.ts`.

### 8.4 SPI contracts and Job Type registration

| File | Provider |
|---|---|
| `inspecto-ops/src/main/resources/META-INF/services/com.gamma.service.ObjectEngineProvider` | `com.gamma.ops.OpsEngineProvider` — the host-declared handle that makes `ObjectAccess` non-empty |
| `inspecto-ops/src/main/resources/META-INF/services/com.gamma.control.RouteModule` | `ObjectRoutes`, `NoteRoutes`, `QueueRoutes`, `TagRoutes` — the 49 paths `AbsentObjectRoutes` stands in for |
| `inspecto-ops/src/main/resources/META-INF/services/com.gamma.job.JobTypeProvider` · `com.gamma.job.MaintenanceTaskProvider` | `OpsJobTypes$CaseRuleEvaluate`, `OpsJobTypes$ObjectsAnalytics` · `OpsMaintenanceTasks` (`incident_purge`) |
| `inspecto-notify-channels/src/main/resources/META-INF/services/com.gamma.notify.NotificationChannel` | `SmtpEmailChannel`, `WebhookChannel` |

### 8.5 Runnable examples

Committed config exercising the chain, all under `spaces/demo/config/` (mirrored in `inspecto-deploy/`):
two Alert Rules (`orders/orders_stream_failures_alert.toon`, `orders_volume_alert.toon`), one queue
(`ops/demo_ops_queue.toon`), one escalation policy (`ops/sla_escalation.toon`), one RCA template
(`ops/orders_rca.toon`). ⚠ **Zero committed Case Rules, Tag Rules, notification rules or channel
destinations**, and — by decision — no scheduled `incident_purge`.

### 8.6 Guards

`render-processor-board --check` pins `SP-CTL-02`'s Personal cell 🟡 through the `PARTIAL_ON_PERSONAL`
override in `ProcessorCatalog.java` (the gap event fires everywhere; the ALERT object does not) — the
generator could not express that until 2026-09-08 and its "just regenerate" advice would have flipped an
operator-approved decision back to ✅. `NoChannelShipsInThePersonalBuildTest` and the cell-7 Personal
baseline (23 modules / 3777 tests, `features.ops = false`) are the edition guards in test clothing.

### 8.7 Named coverage gaps (verified absent, not assumed)

| Gap | Evidence |
|---|---|
| **No test exercises Diagnosis → Incident** | there is no such path to test (§3.9) |
| **No queue-store parity test** | `InMemoryQueueStore` is the only implementation |
| **No test that a Personal bundle answers all 49 object paths `503`** | `AbsentObjectRoutes` is exercised by the cell-7 baseline run, not by a route-by-route assertion |
| **`mail.send` on a transport-less bundle has no failing test** | its success is the recorded behaviour |
| **No end-to-end SMTP or webhook delivery against a real endpoint** | both transports are tested against in-process fakes; delivery-status webhooks against synthetic signatures |
| **No committed Case Rule or Tag Rule** | `*_case_rule.toon` / `*_tag_rule.toon`: zero files (§8.5) |
