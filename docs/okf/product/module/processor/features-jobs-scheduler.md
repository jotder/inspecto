---
type: Feature
title: "Jobs and scheduler — features"
description: "Inspecto has two units of work:  - A **Consignment** is a set of files ingested and processed together, atomically, by the poll cycle   (parsing-ingest)."
tags: [processor, jobs-scheduler, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Jobs and scheduler — features

**Availability:** Offering: all tiers; Job Packs and some maintenance tasks (backup, restore) are Standard and above.

## What it does

Inspecto has two units of work:

- A **Consignment** is a set of files ingested and processed together, atomically, by the poll cycle
  ([parsing-ingest](../etl/architecture-parsing-ingest.md)). Its status flow is
  [consignment-status-flow](../engine/architecture-consignment-status.md).
- A **Job** is a configured, triggerable unit of work outside the poll cycle. Each Job names a
  **Job Type** (the template) and runs as a **Run** with a Run Log, Signals and artifacts.

`JobService` (`platform/inspecto-engine/src/main/java/com/gamma/job/JobService.java`) hosts the Jobs
of one Space, arms their triggers on the shared `Scheduler`, resolves parameters, bounds
concurrency and records every Run. The concept page is [jobs](spec-jobs.md);
the boundary with Steps is [job-vs-step](architecture-job-vs-step.md).

## Where it fits

Jobs are the platform's scheduler. Add-ons register Job Types (for example `alert.evaluate`,
`caserule.evaluate`, `incident.open`), and Pipelines run their at-rest chain as a `pipeline` Job
([pipelines-steps](../etl/architecture-pipelines-steps.md)). A Job reaches shared capabilities only through
[Platform Services](../../layer/platform/architecture-platform-services.md) it declares in `requires:`.

Job Types come from `JobTypeRegistry`, which records each type's source as `builtin`, `classpath`
or `pack:<owner>`. Built-ins include `pipeline`, `enrich`, `report`, `maintenance`, `sql.template`,
`mail.send`, `consignment.process`, `objectstore.export` and `space.comparison`. `GET /jobs/types`
lists exactly what registered, so a switched-off module's types disappear.

More: [screens](user-guide-jobs.md) · [API](api-jobs-scheduler.md) · [architecture](architecture-jobs-scheduler.md).
