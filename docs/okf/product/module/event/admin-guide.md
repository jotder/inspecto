---
type: Feature
title: "Event model and log — admin guide"
description: "How to choose, place, back up and troubleshoot the event store and audit chain."
tags: [event, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Event model and log — admin guide

Every edition records events (pipeline lifecycle, Alerts, audit rows and every INFO/WARN/ERROR log
line). Reading them over HTTP needs the [observability](../observability/admin-guide.md) module.

## Choose a backend

Set at boot with `-Devents.backend` (read by `ServiceStores.openEventStore`):

| Value | Store | Survives restart | Notes |
|---|---|---|---|
| `memory` (default) | `InMemoryEventStore`, ring of 8192 events | no | oldest dropped first; health shows `NOT_CONFIGURED` |
| `parquet` | `ParquetEventStore`, Hive-partitioned Parquet by level/year/month/day | yes | root `-Devents.dir`, default the Space's events directory |
| `db`, `postgres`, `postgresql` or a `jdbc:` URL | `DbEventStore` | yes | the only backend several pods can read; credentials `-Devents.db.user` / `-Devents.db.password` |

Other keys: `-Devents.views.file` — saved-views JSON file (unset = views in memory only). Reference
table: [`ADVANCED_GUIDE.md`](../../../../ADVANCED_GUIDE.md) §9.

Log capture is set in `platform/inspecto-event/src/main/resources/logback.xml`: INFO and above go to
the event store, DEBUG/TRACE only to the console. `-Dui.static.log=DEBUG` turns on one line per served
UI asset on a packaged bundle.

## Files on disk (parquet backend)

- Parquet files under `<events.dir>`, Hive-partitioned by level, year, month and day. A flush happens at 1000 buffered
  events or when the oldest is 10 s old.
- `pending.jsonl` — write-ahead journal; replayed into Parquet on the next open after a crash.
- `pending.held.jsonl` — journalled events held back because an unreadable Parquet file may already
  hold them; retried on each open. Investigate before deleting.
- `.chain-writer.lock` — one audit-chain writer per directory; a second process on the same directory
  is refused and its rows land unlinked.

## Back up and retain

- Back up the whole events directory (or the event database) with the Space; do not copy a directory
  while another process writes it.
- Retention is a maintenance Job, `event_prune` with `retention_days`. It removes whole old days and
  writes an `events.pruned` AUDIT row; audit verification treats missing days before a verified prune
  cutoff as retention, any other gap as truncation. Details: [spec](spec-events-metrics.md).

## Health and metrics

- `GET /health/details` reports the `events` store as its opener resolved it (`UP`, `NOT_CONFIGURED` or degraded, with the reason).
- `inspecto_events_total{level,type}` counts every emitted event (scrape `GET /metrics`).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Events gone after restart | default `memory` backend | `-Devents.backend=parquet` (or a database) |
| Log: *Could not open Parquet event store … falling back to in-memory* | directory not writable or locked | fix the path / permissions; until then the audit trail is not durable (store reported degraded) |
| Log: *Could not open event DB … falling back to in-memory* | bad URL or credentials | check the `jdbc:` URL (case-sensitive) and `events.db.*`; fatal under `-Dinspecto.topology=partitioned` |
| Space refuses to start: *run ONE pod per Space … or -Devents.backend=parquet* | a second pod claimed the chain writer of a shared `DbEventStore` | one writer pod per event database |
| Saved views lost on restart | `events.views.file` unset | set it to a file under the write root |
| DEBUG lines missing from the event feed | by design (ThresholdFilter at INFO) | read them on the console |
