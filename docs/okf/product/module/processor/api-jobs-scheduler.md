---
type: Reference
title: "Jobs and scheduler — API"
description: "Control-plane routes for Jobs and scheduler, with their gates."
tags: [processor, jobs-scheduler, api]
timestamp: 2026-10-19T00:00:00Z
---

# Jobs and scheduler — API

Routes for Jobs and scheduler. Design and internals: [Jobs and scheduler architecture](architecture-jobs-scheduler.md).

| Route | Purpose |
|---|---|
| `GET /jobs`, `POST /jobs`, `GET` / `PUT` / `DELETE /jobs/{name}` | CRUD |
| `POST /jobs/{name}/trigger[?dryRun=true]`, `/enable`, `/disable`, `/reschedule` | control |
| `GET /jobs/types`, `/jobs/types/{id}`, `/jobs/expressions`, `/jobs/processors` | descriptors |
| `GET /jobs/{name}/runs`, `/jobs/runs/{runId}`, `…/runs/{runId}/log`, `…/artifacts`, `…/diff/{other}` | Run history |
| `POST /jobs/runs/{runId}/replay` | replay a live Run |
| `GET /jobs/metrics`, `/jobs/runs`, `/jobs/failures` | reporting (needs `-Djobs.backend`) |
| `GET /jobs/packs`, `POST /jobs/packs/rescan` | Job Packs |
| `GET` / `PUT /system/scheduler` | concurrency settings |
