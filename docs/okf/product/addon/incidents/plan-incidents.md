---
type: Capability
title: Alerts & Incidents (INC)
description: The Alert → Incident → Case chain — Alert Rules and fired Alerts, promotion into managed Incidents, the operational-objects domain (lifecycle, SLA, escalation, queues, Cases, Findings, annotations, retention), notifications, and Diagnosis. The requirement of record for the INC area, its specification, its decisions, and what was refused.
resource: features/inspecto-ops/, platform/inspecto-engine/src/main/java/com/gamma/alert, platform/inspecto-engine/src/main/java/com/gamma/notify, inspecto-ui/src/app/modules/admin/notification-center/
tags: [inc, capability, alerts, alert-rules, incidents, cases, queues, escalation, sla, notifications, diagnosis, findings, retention]
timestamp: 2026-09-08T00:00:00Z
item: addon.incidents
audience: internal
status: plan
verified: 2026-11-19 last-edit
code: features/inspecto-ops
---

# Alerts & Incidents — capability spec (`INC`)

> **What this page is.** The single entry point for the INC capability: what was *required*, what is
> *built*, what is *left*, and what was *refused*. It is the front door to the mechanism, not a copy of it —
> §7 points at the `okf/` concepts that own the detail, and §5 points at `BACKLOG.md` rows rather than
> restating them. Third of the capability specs; the template is
> [`docs-consolidation-plan.md` §5.2](../../../../archived-documents/plans-archive/docs-consolidation-plan.md); the area name and
> directory are fixed by [`GLOSSARY.md` §14](../../../../GLOSSARY.md#14-capability-areas-the-functional-spine).
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

## Parts

This page is split by topic into 5 parts; each part lists the sections it holds.

1. **[Purpose, scope and requirements of record](plan-incidents-scope.md)** — 1. Purpose & scope · 2. Requirements of record
2. **[Specification](plan-incidents-specification.md)** — 3. Specification
3. **[Specification — Incidents as operational objects](plan-incidents-operational-objects.md)**
4. **[Notifications, decisions and not built](plan-incidents-notifications-decisions.md)** — 4. Decisions · 5. Not built
5. **[Refused, as-built pointers and verification](plan-incidents-refused-verification.md)** — 6. Refused & superseded · 7. As-built mechanism (pointers only) · 8. Verification
