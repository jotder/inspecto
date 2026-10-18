---
type: Feature
title: "Screening — features"
description: "Screening matches subjects (a key, a name, an identifier) against Entity Lists and keeps a reviewable record of every match a scheduled run finds."
tags: [screening, screening, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Screening — features

**Availability:** Offering: Standard and above (`screening` add-on, module `screening`).

## What it does

Screening matches subjects (a key, a name, an identifier) against [Entity Lists](../scoring/architecture-scoring-entity-lists.md) and keeps
a reviewable record of every match a scheduled run finds. Two paths share one matcher:

- **Check** - an ad-hoc, read-shaped call that returns Match Scores and persists nothing.
- **Run** - the `screening.run` Job Type screens every row of a Dataset and raises a **Screening Hit** for each
  match at or above the threshold that has no hit yet.

Module: `features/inspecto-screening` (manifest id `screening`, requires `entity-list` and `ops`). Deeper design
notes: [Screening concept](spec.md).

## Where it fits

An add-on over the platform base. It reads Entity Lists (`entity-list` module), runs as a Job through the
[Jobs scheduler](../processor/architecture-jobs-scheduler.md), and on confirm opens an [Incident](../../addon/incidents/architecture-incidents.md) and a
[Case](../case-management/architecture-overview.md). Bundles without the jar answer the routes with the manifest's `absentMessage`
("Standard edition and above").

More: [screens](user-guide-screening.md) · [API](api-screening.md) · [architecture](architecture-overview.md).
