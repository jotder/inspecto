---
type: Concept
title: Kafka stream connector — architecture
description: How the kafka Collector connector plugs into acquisition - ServiceLoader discovery, slice-as-file, ledger offsets, stream lane and edition staging.
resource: providers/inspecto-connectors-kafka/
tags: [module, connectors-kafka, kafka, architecture]
timestamp: 2026-10-19T00:00:00Z
---

# Kafka stream connector — architecture

## Where it plugs in

```
Pipeline (collector.connection → *_connection.toon, connector: kafka)
   │
CollectorConnectors.forConfig ── ServiceLoader<CollectorConnectorFactory> ── KafkaConnectorFactory
   │                                  (egress gate on every bootstrap broker first)
   ▼
KafkaConnector.discover ─▶ slices <topic>-p<n>-<from>-<to>.ndjson  (one per partition backlog)
   │
fetchTo ─▶ dirs.poll ─▶ normal batch ingest (ConsignmentIngestor)
   │                         │ commit durable
   └── stash next offset ────┴─▶ AcquisitionLedger watermark (per topic-partition)
```

The connector turns a stream into files: each partition backlog becomes one slice file that flows
through the ordinary batch path, the same virtual-file pattern as the DB export connector. Nothing
downstream knows the data came from Kafka. The SPI is documented in the [connectors api](../connectors/api.md).

## Key decisions

* **Kafka is not the data path.** Urgency is a parameter on one node, not a second execution model; a
  Kafka *Collector* is an input like any other (`docs/BACKLOG.md`, "Kafka is not the data path").
* **The ledger holds offsets, not a consumer group.** `assign`/`seek` with no group coordinator means
  minimal broker ACLs and one durable frontier shared with the DB-export watermark machinery; the
  offset advances only after the batch commits, giving at-least-once per slice
  ([stream-consumer-design](../../../../archived-documents/plans-archive/stream-consumer-design.md)).
* **Its own module (MODULE-REORG-1 P7, 2026-10-07).** `kafka-clients` is the one connector dependency
  footprint distinct from the rest, so it left `inspecto-connectors` (which keeps sftp, ftp, ftps, db,
  s3, azure, gcs). Staged from Standard (`offerings/standard.toon`); Basic has no `kafka` scheme.
* **Every hop is egress-checked.** The client dials partition leaders the metadata names, not just the
  bootstrap list, so each leader passes the Safety Policy before a fetch.

## Two triggers, one path

| Trigger | Consumer lifetime | When it drains |
|---|---|---|
| scheduled acquisition cycle | one scan cycle | every cycle, per partition backlog |
| `trigger: {type: stream}` (`StreamLane`, ASSURE-PUSH-INGEST-1) | the lane's life | backlog ≥ N records, or a non-empty backlog waited T |

Both call the same `discover` / `fetchTo` and the same ingest, so the fence and the durable frontier
apply equally. Only Kafka answers `pendingRecords()`; other connectors return -1 and drain on T alone.

## Connection workbench

`KafkaConnectionWorkbench` serves `POST /connections/{id}/probe`, `GET /connections/{id}/explore` and
`GET /connections/{id}/sample` for any topic on the broker, read-only and isolated from the ledger.

More: [spec](spec.md) · [admin guide](admin-guide.md) · [connectors architecture](../connectors/architecture.md)
· [plan](plan.md).
