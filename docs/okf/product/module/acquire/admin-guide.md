---
type: Feature
title: "Acquisition and collectors — admin guide"
description: "Operating acquisition: the -D flags for the acquisition ledger and intake admission control, readiness and circuit-breaker behaviour, health and metrics routes, and common failures."
tags: [acquire, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Acquisition and collectors — admin guide

Module `platform/inspecto-acquire` (Base, every Edition). Collector and Connection authoring is in
[user-guide-collector-config](user-guide-collector-config.md); routes are in [api-collectors](api-collectors.md).

## JVM flags

| Flag | Default | Effect |
|---|---|---|
| `-Dacquire.ledger.backend` | `memory` | `db` keeps the pre-fetch dedup ledger in a database. With `memory`, dedup history is lost on restart and already-fetched files can be fetched again. |
| `-Dacquire.ledger.db.url` | `jdbc:duckdb:inspecto-acquisition.db` | JDBC URL of the ledger when the backend is `db` (a relative DuckDB file lands in the working directory). |
| `-Dacquire.ledger.db.user` / `-Dacquire.ledger.db.password` | unset | Credentials for a server database ledger. |
| `-Dingest.maxFilesPerCycle` | `500` | Files one poll cycle may admit; `0` turns admission control off. |
| `-Dingest.minFilesPerCycle` | `1` | Floor the adaptive controller may halve down to. |
| `-Dingest.backpressure.adaptive` | `true` | `false` pins the cap at `maxFilesPerCycle` (a hard cap). |
| `-Dingest.maxBytesPerCycle` | `0` (off) | Byte cap per cycle — bytes, not files: one large file passes a count cap. |

A malformed numeric value falls back to its default. Code:
[`AcquisitionLedgers`](../../../../../platform/inspecto-acquire/src/main/java/com/gamma/acquire/AcquisitionLedgers.java),
[`IntakeGovernor`](../../../../../platform/inspecto-acquire/src/main/java/com/gamma/acquire/IntakeGovernor.java).

## Runtime behaviour to know

- **Per Space.** The ledger, the stability gate and the circuit breaker each keep one instance per Space.
- **Half-written files.** [`StabilityGate`](../../../../../platform/inspecto-acquire/src/main/java/com/gamma/acquire/StabilityGate.java)
  holds a file until the connector reports it ready, or until it is unmodified for the window and the same size
  over several polls. Producers that write a sentinel can use a `ready_marker`
  ([`ReadyMarker`](../../../../../platform/inspecto-acquire/src/main/java/com/gamma/acquire/ReadyMarker.java)); a template
  without `{name}` is treated as a suffix.
- **Dead endpoints.** [`CircuitBreaker`](../../../../../platform/inspecto-acquire/src/main/java/com/gamma/acquire/CircuitBreaker.java)
  opens after repeated connect/list failures, skips that Collector for a cooldown, then allows one half-open trial.

## Monitor

- `GET /health/details` — store health; the `acquisitionLedger` entry is `NOT_CONFIGURED` (memory backend),
  `UP` (database open) or degraded (database failed to open, fell back to memory).
- `GET /metrics/acquisition` — acquisition counters.
- `POST /connections/{id}/test` and `POST /connections/{id}/probe` — check that a Connection is reachable.

## Common failures

| Symptom | Cause / fix |
|---|---|
| Files re-fetched after a restart | Ledger is in memory; set `-Dacquire.ledger.backend=db`. |
| Health shows the ledger degraded | The ledger URL did not open (path, lock, credentials); acquisition continues in memory — fix the URL and restart. |
| A Collector stops polling for a while | Circuit breaker open after endpoint failures; test the Connection. |
| Files never picked up | Not yet stable, or the `ready_marker` sentinel is missing. |
| Backlog drains slowly | Admission cap; raise `ingest.maxFilesPerCycle` or `ingest.maxBytesPerCycle`. |
