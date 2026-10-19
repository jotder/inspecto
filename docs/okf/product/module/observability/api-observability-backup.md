---
type: Reference
title: "Observability and backup — API"
description: "Control-plane routes for Observability and backup, with their gates."
tags: [observability, observability-backup, api]
timestamp: 2026-10-19T00:00:00Z
---

# Observability and backup — API

Routes for Observability and backup. Design and internals: [Observability and backup architecture](architecture-observability-backup.md).

| Route | Purpose |
|---|---|
| `GET /metrics` | Prometheus exposition (public) |
| `GET /metrics/acquisition` | acquisition tiles as JSON (authenticated, core) |
| `GET /health`, `GET /ready`, `GET /health/details` | probes; details is auth-gated |
| `GET /status`, `/status/problem-files`, `/report`, `/runs/{name}/report` | run reporting |
| `GET /events`, `/events/search`, `/events/export`, `/events/views` | event ledger |
| `GET /signals`, `/signals/stream` | Signal read view |
| `GET /audit/search`, `/audit/export`, `/audit/verify` | audit trail |
| `POST /jobs/{name}/trigger?dryRun=true` | preview a backup or retention task |
