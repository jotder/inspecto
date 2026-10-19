---
type: Feature
title: "Alerts — features"
description: "An **Alert Rule** is an operator-saved, deterministic threshold check; an **Alert** is the stored record a breach raises."
tags: [incidents, alerts, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Alerts — features

**Availability:** Offering: every edition (the evaluator and Alert store are in `platform/inspecto-engine`).

## What it does

An **Alert Rule** is an operator-saved, deterministic threshold check; an **Alert** is the stored record a
breach raises. `AlertService` (`platform/inspecto-engine/src/main/java/com/gamma/alert/AlertService.java`)
is the evaluator, and the `alerts` [Platform Service](../../layer/platform/architecture-platform-services.md) (`AlertAccess`) is how a Job
reaches it. No model runs here: the agent's `diagnose-and-alert` skill only drafts a rule
(`features/inspecto-agent/src/main/java/com/gamma/agent/skill/AlertRuleTool.java`); a human saving it arms it.

## Where it fits

Platform base, engine layer. Rules persist as `alert-rule` components under `<write-root>/registry`, with
the same CRUD contract as Expectations and Decision Rules. Fired Alerts go to an `AlertStore`
(`DbAlertStore` or `InMemoryAlertStore`). High-severity breaches cross into the [Incidents](architecture-incidents.md)
add-on only through the `incidents` Platform Service. Delivery to people is the [Notifications](../../module/notify-channels/architecture-overview.md)
module's job: an Alert carries a recipient attribute taken from the rule's `owner`.

More: [screens](user-guide-alerts.md) · [API](api-alerts.md) · [architecture](architecture-alerts.md).
