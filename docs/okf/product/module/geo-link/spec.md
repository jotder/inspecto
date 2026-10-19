---
type: Reference
title: Geo and link adapters — implementation spec
description: Classes, invariants and failure handling of inspecto-geo-link, the Link Analysis bridge to the platform.
resource: la/inspecto-geo-link/
tags: [module, geo-link, link-analysis, spec]
timestamp: 2026-10-19T00:00:00Z
---

# Geo and link adapters — implementation spec

Package `com.gamma.geolink` (`la/inspecto-geo-link/src/main/java/com/gamma/geolink/`). Compile dependencies:
`inspecto-processor`, `inspecto-la-api`, `inspecto-la-core`, `inspecto-entity-list`, `inspecto-entity-store`,
`inspecto-etl`, `inspecto-http-spi` (`la/inspecto-geo-link/pom.xml`).

## Classes

| Class | Role | Invariants |
|---|---|---|
| `EngineDatasetProvider` | `DatasetProvider` port | Pure delegation; maps engine records to LA-owned records so no engine type reaches la-core / la-api. |
| `HostCasePort` | `CasePort` | Visibility through `AnnotationTargets.objectVisibleTo`. |
| `HostCollectorCoveragePort` | `CollectorCoveragePort` | Joins `batches` to `lineage` audit CSVs per Pipeline; keeps batches that wrote one of the Dataset's stores; best-effort per file. |
| `HostOutboundPort` | `OutboundPort` | Endpoint = a registered `https` Connection, no tunnel or proxy; a LITERAL token is refused; `EgressPolicy.resolve` before every attempt (a refusal is not retried); retry on I/O, 5xx, 408, 429 under the Collector `RetryPolicy`; never logs payload, token or body. |
| `HostUploadLandingPort` | `UploadLandingPort` | Answers only for a Collector with `collector.accepts_upload: true` polling a local directory. |
| `ScheduledLinkIndexBuilder` | `LinkIndexBuilder` | Absent this module, the `link-index` Job fails closed. |
| `WorkingSetMeasures` | Measure evaluator + `InvestigationMeasureProbe` | Measure shorthand `count` / `agg(field)` (count, countDistinct, sum, avg, min, max) validated and compiled by the BI grammar's one home (`MeasureCompiler`); evaluated in a sealed in-memory DuckDB table; `sum/avg/min/max` numeric only; answers only for a BOUND rule whose hash and owner match. |
| `InvestigationMeasureRoutes` | `/inv/investigations/{id}/measures`, `alert-rules`, `standing-detection` | Opened through `InvestigationRoutes.open` (owner-only, R3 Dataset gate, PDP): a non-owner gets 404 before any capability check; writes need `canAuthorAlertRules`. |
| `InvestigationDataChangesRoutes` + `DataChanges` | `GET …/data-changes` | Same access as `…/coverage` (owner or linked-Case member); persists nothing; window days in the window's zone, day-of-week mask and exclusions NOT applied; strictly newer than last look. |
| `IntegrationWebhooks` | signed event webhooks | Events `investigation.created`, `draft.promoted`, `alert.fired`, `approval.needed`; header `X-Inspecto-Signature: sha256=<hex>` over `<timestamp>.<body>`; `X-Inspecto-Delivery-Id` / `Idempotency-Key` = source event id; payload carries no entity id. |
| `EmbedExpirySweepModule` | daemon timer | Every 10 minutes over Spaces with `geoLink` on; a no-op unless `-Dla.embed.enabled=true`. |

Route table with capabilities: [api-routes](api-routes.md).

## Failure handling

- Coverage and data-changes without attribution evidence answer `assessed:false`, never "no changes".
- A webhook delivery never slows or fails the act it reports (asynchronous); every outcome is audited on the
  Space's ledger.
- An unreadable integration settings file reads as not configured — nothing leaves (`LaIntegration`, la-api).
- A rule that fires discloses the Investigation id, relation, Measure, value and threshold to Alert/Incident
  readers, never an entity id; the binding response says so.

## Tests

`la/inspecto-geo-link/src/test/java/` — see [tests-inventory](tests-inventory.md).

## References

- [architecture](architecture.md) · [la-core architecture](../la-core/architecture.md)
