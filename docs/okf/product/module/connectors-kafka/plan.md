---
type: Plan
title: Kafka stream connector — plan
description: Requirement of record for the kafka Collector connector - what was required, built, left and refused.
resource: providers/inspecto-connectors-kafka/
tags: [module, connectors-kafka, kafka, plan]
timestamp: 2026-10-19T00:00:00Z
---

# Kafka stream connector — plan

## Required

`SP-ACQ-09` (`docs/EDITIONS.md`): a Kafka consumer as a Collector, so records arriving on a topic enter
the same Pipelines as files. Origin ACQ-5 in the acquisition roadmap.

## Built

| When | What |
|---|---|
| 2026-07-24 | `KafkaConnectionWorkbench`: probe / explore / sample ([connectors spec](../connectors/spec-connectors.md)). |
| 2026-09-24 | `STREAM-CONSUMER-1` closed: frontier on the ledger watermark, re-keyed on land and durable across a restart, one uncommitted slice per partition, lag gauge ([stream-consumer-design](../../../../archived-documents/plans-archive/stream-consumer-design.md)). |
| 2026-09-29 | `ASSURE-PUSH-INGEST-1`: the continuous `trigger: stream` lane and `pendingRecords()` ([acquisition plan](../acquire/plan-acquisition.md)). |
| 2026-10-06 | Safety Policy: Kafka partition-leader egress check (T12) under `DUCKLE-C6-POLICY-NARROWING-1`. |
| 2026-10-07 | MODULE-REORG-1 P7: moved to its own module `inspecto-connectors-kafka`, staged from Standard; Basic loses the scheme by decision. |

As-built: [spec](spec.md), [architecture](architecture.md), [admin guide](admin-guide.md).

## Left open

* Proof against a real broker is opt-in only: `KafkaRealBrokerTest` skips unless a broker answers at
  `-Dkafka.it.bootstrap`; no broker ships with the build or CI (`docs/EDITIONS.md` row SP-ACQ-09).
* Restart backoff for the stream lane was filed as `ASSURE-STREAM-LANE-BACKOFF-1` and is now built
  (`StreamLane.Backoff`).
* An outbound Kafka export (Dataset → topic) is a proposal only, in
  [warehouse targets](../connectors/plan-warehouse-targets.md).

## Refused (by decision)

* **Broker consumer groups / broker-side commits:** the ledger is the only frontier.
* **A Kafka execution lane** replacing batch ingest: "Kafka is not the data path" (`docs/BACKLOG.md`).
* **Kafka in Basic:** a deliberate edition change in MODULE-REORG-1 P7.
