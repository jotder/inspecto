---
type: Feature
title: "Observability — admin guide"
description: "Installing, securing and troubleshooting the Prometheus endpoint and the events feed."
tags: [observability, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Observability — admin guide

## Install

The module (`inspecto-observability`) ships in Standard and above and is bound at boot. Without it,
`/metrics` and every `/events*` route answer 503 naming the module, and the Events screen is hidden;
events and the audit trail are still recorded (audited actions stay readable via `/audit/search`).

## Configure

The module has no settings of its own. What it serves is decided by the event store
(`-Devents.backend`, `-Devents.dir`, `-Devents.views.file`) — see the
[event admin guide](../event/admin-guide.md). With the default in-memory backend `/events/search` sees
only the last 8192 events.

## Prometheus scraping

- Scrape `GET /metrics` (Prometheus text 0.0.4). The metric catalogue is
  [`ADVANCED_GUIDE.md`](../../../../ADVANCED_GUIDE.md) §7.
- ⚠ `/metrics` is **unauthenticated** by design. Expose the control port only to the scraper network,
  or front it with a proxy; it reveals pipeline names and operational rates.

## Secure

- Reading events needs a signed-in caller; saving or deleting a saved view needs `canAuthorWorkbench`
  (views are shared by all users).
- Audit rows are masked unless the caller holds `canRevealLinkEntities`.

## Back up

Nothing to back up here: the feed reads the event store, and saved views live in `events.views.file`.
Back those up as the event admin guide says.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `/events` or `/metrics` 503 naming `inspecto-observability` | module not in this edition / bundle | use a Standard or higher bundle |
| `/metrics` has no series for a pipeline | most pipeline metrics appear only after its first cycle or commit | run the pipeline once; check `SERVICE_STARTED` |
| Events vanish after restart | in-memory event store | durable backend, see the event admin guide |
| `POST /events/views` 403 | caller lacks `canAuthorWorkbench` | grant the capability |
| Search for a raw key finds nothing | caller is masked; search runs on masked text | expected; use an account with the unmask capability |
