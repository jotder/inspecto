---
type: Guide
title: Exchange — admin guide
description: Enabling the Exchange, the sharing capabilities, the background snapshot refresh, storage under spaces/_shared, retention and common failures.
resource: features/inspecto-exchange/src/main/resources/META-INF/inspecto/module.toon
tags: [exchange, admin-guide, multi-space, sharing, configuration]
timestamp: 2026-10-19T00:00:00Z
---

# Exchange — admin guide

## Enabling

Optional module `inspecto-exchange` (module id `exchange`, feature id `exchange`, `bindingTime: boot`), the
`multiEntity` add-on of Standard edition and above
([module.toon](../../../../../features/inspecto-exchange/src/main/resources/META-INF/inspecto/module.toon);
[offerings/standard.toon](../../../../../offerings/standard.toon)). Absent, every `/exchange/*` route answers 503
with the module's `absentMessage`, and `shared/...` refs fail closed.

It also needs multi-Space mode: `-Dspaces.root` must be set. With a single Space there is nobody to share with
and every route answers 409 ([architecture § 3](architecture-overview.md)).

## Permissions

| Act | Capability | Checked in the Space of |
|---|---|---|
| `POST /exchange/offers`, `POST /exchange/refresh` | `canOfferDatasets` | the body `owner` |
| `POST /exchange/signal-offers` | `canOfferSignals` | the owner |
| `POST /exchange/requests` | `canRequestShares` | the body `consumer` |
| approve / deny / revoke / expiry on a grant | `canApproveShares` | the grant's `owner` |
| `POST /exchange/grants/{id}/pin` | `canRequestShares` | the grant's `consumer` |

Reads are open. Each Space's own `roles.toon` decides (`EXCHANGE-OWNING-SPACE-AUTHZ-1`); on Basic (no Subject)
the checks are a no-op. Detail and the route list: [spec](spec.md), [api-routes](api-routes.md).

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `-Dspaces.root` | unset | required; the Exchange lives in its `_shared` directory |
| Offer `refresh` | `none` | `hourly` / `daily`, `dataset` offers only (other kinds 422) |
| Grant `mode` / `pin` / `expiresAt` | `snapshot` / none / none | per grant, set through the API |
| `-Djobs.signal.maxChainDepth` | 8 | a forwarded Signal beyond this chain depth is undeliverable |
| `modules.toon` (Space) | enabled | switching `exchange` off pauses the snapshot refresh for that owning Space |

Background work `exchange-snapshot-refresh` (`ExchangeSnapshotSweep`) ticks every 60 s, one per JVM, and
republishes only an offer with a cadence, an active snapshot-mode grant and a full interval elapsed
([spec](spec.md)).

## Storage and retention

All under `spaces/_shared/` (reserved, never itself a Space): the ledgers `offers.toon` and `grants.toon`
(managed through the API, never hand-edited), and per shared Dataset
`exchange/<owner>/<item>/<version>/snapshot.parquet` with `current.toon` and `last-refresh.toon` beside it.
Prune keeps the version `current.toon` names, every version a grant pins, the 2 newest, and anything minted in
the last 30 minutes; it skips entirely when the grants cannot be read ([spec](spec.md)).

## Common failures

| Symptom | Cause |
|---|---|
| 503 on `/exchange/*` | module not in the bundle |
| 409 on every route | single-Space install (`-Dspaces.root` unset) |
| 403 on approve although the caller is an approver | the capability is held in another Space, not the grant's owner Space |
| 404 on a grant act | unknown grant id (checked before the gate) |
| 422 on an offer | unknown `refresh` value, or a cadence on a non-`dataset` kind |
| consumer sees stale data | the grant is pinned, or the last attempt failed: read `lastRefresh` on the offer; a failure waits a full interval |
| `exchange.signal.undeliverable` on the origin | consumer Space not hosted in the same Pod, or chain depth exceeded; nothing is queued |
