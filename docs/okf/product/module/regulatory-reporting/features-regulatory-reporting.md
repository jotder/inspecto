---
type: Feature
title: "Regulatory Reporting — features"
description: "Regulatory Reporting turns a Case or an Incident into a regulator-format document (XML, CSV or JSON) from a **Report Template**, has a second person approve it, and submits the exact approved bytes to a jailed **file drop**."
tags: [regulatory-reporting, regulatory-reporting, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Regulatory Reporting — features

**Availability:** Offering: Standard and above (`regulatoryReporting` add-on, module `regulatory-reporting`). Checked against the code on 2026-10-17.

## What it does

Regulatory Reporting turns a [Case](../case-management/architecture-overview.md) or an [Incident](../../addon/incidents/architecture-incidents.md) into a regulator-format
document (XML, CSV or JSON) from a **Report Template**, has a second person approve it, and submits the exact
approved bytes to a jailed **file drop**. No live regulator API is built; file drop is the one delivery kind.

Module: `features/inspecto-regulatory-reporting` (manifest id `regulatory-reporting`, requires `ops`). Concept:
[Regulatory Reporting](spec.md).

## Where it fits

An add-on over the platform base. It reads the operational objects of [Case management](../case-management/architecture-overview.md) and
[Incidents](../../addon/incidents/architecture-incidents.md), reuses the [Maker-checker](../workflow/architecture-maker-checker.md) signing key and decision pattern, and writes
into the same path jail as the `report` Job's `out_dir`.

More: [screens](user-guide-regulatory-reporting.md) · [API](api-regulatory-reporting.md) · [architecture](architecture-overview.md).
