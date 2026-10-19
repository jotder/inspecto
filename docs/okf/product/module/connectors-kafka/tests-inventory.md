---
type: TestsInventory
title: Kafka stream connector — tests inventory
item: module.connectors-kafka
description: Generated list of the test classes for module.connectors-kafka, with how to run them.
tags: [tests, module]
---

# Kafka stream connector — tests inventory (`module.connectors-kafka`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `providers/inspecto-connectors-kafka/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.connectors-kafka`](../../../../../product-map/module/connectors-kafka.toon).

6 test class(es): 6 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl providers/inspecto-connectors-kafka
mvn -o test -pl providers/inspecto-connectors-kafka -Dtest=KafkaConnectionWorkbenchTest,KafkaConnectorFactoryTckTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `KafkaConnectionWorkbenchTest` | `com.gamma.acquire.kafka` | unit | `providers/inspecto-connectors-kafka/src/test/java/com/gamma/acquire/kafka/KafkaConnectionWorkbenchTest.java` | The Kafka {@link ConnectionWorkbench} against kafka-clients' in-jar {@link MockConsumer} — offline, no |
| `KafkaConnectorFactoryTckTest` | `com.gamma.acquire.kafka` | unit | `providers/inspecto-connectors-kafka/src/test/java/com/gamma/acquire/kafka/KafkaConnectorFactoryTckTest.java` | {@link KafkaConnectorFactory} against the platform's CollectorConnectorFactory TCK (MODULE-REORG-1 P5b). |
| `KafkaConnectorTest` | `com.gamma.acquire.kafka` | unit | `providers/inspecto-connectors-kafka/src/test/java/com/gamma/acquire/kafka/KafkaConnectorTest.java` | Drives {@link KafkaConnector} against kafka-clients' in-jar {@link MockConsumer} — offline, no broker: |
| `KafkaEgressTest` | `com.gamma.acquire.kafka` | unit | `providers/inspecto-connectors-kafka/src/test/java/com/gamma/acquire/kafka/KafkaEgressTest.java` | T12 of {@code policy-narrowing-design.md} (Kafka leader hosts): split out of the core connectors module's |
| `KafkaRealBrokerTest` | `com.gamma.acquire.kafka` | unit | `providers/inspecto-connectors-kafka/src/test/java/com/gamma/acquire/kafka/KafkaRealBrokerTest.java` | {@link KafkaConnector} against a REAL broker (ASSURE-PUSH-INGEST-1) — the check {@code MockConsumer} cannot |
| `KafkaStreamLaneTest` | `com.gamma.acquire.kafka` | unit | `providers/inspecto-connectors-kafka/src/test/java/com/gamma/acquire/kafka/KafkaStreamLaneTest.java` | The continuous lane (ASSURE-PUSH-INGEST-1) over a real {@link KafkaConnector} and kafka-clients' |
