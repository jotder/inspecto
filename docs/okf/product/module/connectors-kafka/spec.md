---
type: Reference
title: Kafka stream connector — spec
description: Implementation of KafkaConnector, its factory and workbench - slicing, ledger offsets, the in-flight fence, client properties and egress checks.
resource: providers/inspecto-connectors-kafka/
tags: [module, connectors-kafka, kafka, spec]
timestamp: 2026-10-19T00:00:00Z
---

# Kafka stream connector — spec

Package `com.gamma.acquire.kafka`, three classes, dependency `org.apache.kafka:kafka-clients` 3.9.x
(shaded with its compression codecs into the module jar, see `providers/inspecto-connectors-kafka/pom.xml`).
The contract they implement is in the [connectors api](../connectors/api.md).

| Class | Role |
|---|---|
| `KafkaConnectorFactory` | `scheme()` = `kafka`; `create(cfg, profile)` refuses a null profile; `workbench(profile)` returns a `KafkaConnectionWorkbench`. Registered in `META-INF/services/com.gamma.acquire.CollectorConnectorFactory`. |
| `KafkaConnector` | The Collector connector: one instance per scan cycle (or per stream lane), one consumer for its life. |
| `KafkaConnectionWorkbench` | Probe / explore (`topic → partition` tree) / sample for any topic on the broker. Throwaway consumer, no ledger interaction, `write` check always skipped. |

## Construction

The constructor parses options and fails with `IllegalArgumentException` on a missing `topic`, a
`payload` other than `envelope|raw`, a `start` other than `earliest|latest`, or a non-positive
`max_records`. It does not dial. The Safety Policy gate (`EgressGate.current()`) is captured on the
planning thread, because a worker thread does not see the run pin.

## Discover

For each partition of the topic:

1. Check every partition leader host against the egress gate (`requireLeaders`).
2. `from` = ledger watermark `kafka:<id>:<topic>:p<n>`, else beginning (or end with `start: latest`),
   then clamped up to the beginning offset (retention).
3. Publish `inspecto_stream_lag_records` = end − from, even when fenced.
4. Skip the partition if `AcquisitionLedgers.hasPendingDbWatermark` (an uncommitted slice exists).
5. `to = min(end, from + max_records)`; emit `RemoteFile` named `<topic>-p<n>-<from>-<to>.<ext>` if the
   include/exclude filter accepts it.

A missing topic answers an empty list. Any `RuntimeException` becomes `AcquisitionException`.

`pendingRecords()` repeats the arithmetic over unfenced partitions for the stream lane's probe.

## Drain (`fetchTo`)

Parses partition/from/to from the slice name, `assign` + `seek(from)`, polls 1 s at a time, writes one
line per record (envelope JSON or raw value) until `to`. Five consecutive empty polls end the drain early
(range shrank). If nothing was read it throws, so the cycle retries. Otherwise it stashes the reached
offset with `AcquisitionLedgers.stashDbWatermark`; `ConsignmentIngestor.commit` persists it only after
the batch is durable. `open()` drains to a self-deleting temp file. Capabilities: `STREAM` only;
`post()` refuses everything but `RETAIN`.

## Client properties

`bootstrap.servers` from `options.bootstrap_servers` or `host:port` (default 9092); byte-array
deserializers; `enable.auto.commit=false`; `auto.offset.reset=earliest`; `client.id=inspecto-<id>`. With
a username: `security.protocol` (default `SASL_PLAINTEXT`), `sasl.mechanism` (default `PLAIN`) and a
`PlainLoginModule` JAAS line with the password resolved by `SecretResolver` and quote-escaped. Every
`options.kafka.*` key is copied without the prefix, its value secret-resolved.

## Invariants

* Offsets never live in a consumer group; the ledger is the only frontier.
* At most one uncommitted slice per partition.
* Each leader host is egress-checked before the first fetch of a call.
* A workbench sample never advances the frontier.

## Tests

`KafkaConnectorTest`, `KafkaConnectionWorkbenchTest` (on `MockConsumer`), `KafkaEgressTest`,
`KafkaStreamLaneTest`, `KafkaConnectorFactoryTckTest` (platform TCK) and `KafkaRealBrokerTest` (skips
unless a broker answers at `-Dkafka.it.bootstrap`, default `localhost:9092`). See
[tests-inventory](tests-inventory.md).
