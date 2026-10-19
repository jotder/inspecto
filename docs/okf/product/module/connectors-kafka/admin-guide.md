---
type: Guide
title: Kafka stream connector — admin guide
description: Configure a kafka Connection (brokers, topic, SASL, TLS passthrough), the stream trigger, offsets, metrics and failure modes.
resource: providers/inspecto-connectors-kafka/
tags: [module, connectors-kafka, kafka, collector, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Kafka stream connector — admin guide

The `inspecto-connectors-kafka` jar adds the `kafka` scheme to Collector connections. It is staged from
Standard upward (`offerings/standard.toon`); Basic has no `kafka` scheme and a Pipeline naming it fails
with "no source connector registered". Shared connection mechanics (profiles, secret references,
egress, probe) are in the [connectors runbook](../connectors/admin-guide-connectors-runbook.md); this
page covers what is Kafka-specific.

## 1. Connection profile (`<name>_connection.toon`)

A Kafka Collector **must** reference a connection profile; without one the factory refuses
("requires collector.connection to reference a *_connection.toon profile").

```yaml
connection:
  id: cdr_kafka
  connector: kafka
  host: broker1.example.com        # or options.bootstrap_servers
  port: 9092                       # default 9092
  username: inspecto               # optional: enables SASL
  password: ${ENV:KAFKA_PASSWORD}  # a secret reference, never a literal
  options:
    topic: cdr-events              # required
    start: earliest                # earliest | latest (first run only)
    max_records: 100000            # per-partition cap per drain
    payload: envelope              # envelope | raw
    export_ext: ndjson             # extension of the drained slice file
```

| Option | Default | Meaning |
|---|---|---|
| `topic` | (required) | The one topic this Collector consumes. |
| `bootstrap_servers` | `host:port` | Full list `h1:9092,h2:9092`; overrides `host`/`port`. |
| `start` | `earliest` | Position when no offset is stored yet. |
| `max_records` | `100000` | Positive integer; a longer backlog carries to the next cycle. |
| `payload` | `envelope` | `envelope`: one JSON object per record `{topic, partition, offset, timestamp, key, value}`; `raw`: the value verbatim, one per line, tombstones skipped (CSV over Kafka). |
| `export_ext` | `ndjson` | Use e.g. `csv` with `payload: raw` so the slice matches the Pipeline's file pattern. |
| `security_protocol` | `SASL_PLAINTEXT` with credentials, else `PLAINTEXT` | Set `SASL_SSL` or `SSL` for TLS. |
| `sasl_mechanism` | `PLAIN` | Passed to the client. The JAAS line is always `PlainLoginModule`. |
| `kafka.<prop>` | none | Passthrough: `kafka.fetch.max.bytes: 1048576` sets client property `fetch.max.bytes`. Values may be `${...}` secret references. |

An invalid `payload`, `start` or `max_records` is refused when the connector is built, at the start of
a cycle. `KafkaConnectorFactory` does not override `validate`, so the save itself accepts it; run the
connection probe after saving.

### TLS and certificates

There is no Kafka-specific keystore flag. Use `security_protocol: SASL_SSL` (or `SSL`) and the client's
own properties through the passthrough, for example `kafka.ssl.truststore.location`,
`kafka.ssl.truststore.password: ${ENV:KAFKA_TS_PASS}`, and for mutual TLS `kafka.ssl.keystore.location`
plus its passwords. Without a truststore the client uses the JVM default trust (the bundle runtime's
`cacerts`).

### Egress

Every broker in `bootstrap_servers` passes the Safety Policy before the connector is built, and every
partition leader the cluster metadata names is checked again before the first fetch. A leader host not
allowed by the Space's egress policy fails the cycle. Allow the full broker set, not only the bootstrap
host.

## 2. Triggering

* **Scheduled (default):** each acquisition cycle drains each partition's backlog into one slice file.
* **Continuous:** `trigger: {type: stream, records: N, max_wait: T}` (defaults 1000 records, 5 s) on the
  Pipeline keeps the consumer open, probes the backlog every 200 ms and drains at N records or after T.
  Backoff tunables `-Dstream.lane.backoff.baseMs` / `-Dstream.lane.backoff.maxMs` (1 s doubling to 60 s).
  Details: [acquisition plan](../acquire/plan-acquisition.md).

## 3. Offsets and delivery

* No consumer group: the connector uses `assign()` + `seek()` and never commits to the broker, so it
  needs only read/describe ACLs on the topic.
* The next offset per partition is stored in the acquisition ledger under
  `kafka:<connection>:<topic>:p<partition>`, and advances only **after** the batch is durable. A crash
  mid-ingest re-reads that slice: delivery is **at least once**.
* One uncommitted slice per partition at a time (the in-flight fence).
* If retention pruned past the stored offset, the drain resumes at the earliest available offset; the
  pruned records are lost on the broker side.
* Slices are named `<topic>-p<partition>-<from>-<to>.<ext>`, so ledger dedup ingests each once.
* Post-actions: only `RETAIN` is valid (there is no source file to delete or move).

## 4. Metrics

| Metric | Labels | Meaning |
|---|---|---|
| `inspecto_stream_lag_records` (gauge) | connection, topic, partition | Records past the committed frontier |
| `inspecto_stream_slices_drained_total` | connection, topic | Slices drained |

## 5. Failure modes

| Symptom | Cause | Fix |
|---|---|---|
| `needs options.topic` / `needs host/port or options.bootstrap_servers` | incomplete profile | Fill the profile |
| `Kafka drain got no records ... will retry next cycle` | broker unreachable or slice pruned between list and drain | Check reachability; it retries |
| `Kafka discovery failed ...` | metadata or offset lookup failed (auth, network, egress refusal) | Read the cause in the message; run the connection probe |
| Nothing ingested, no error | topic not created yet, or `start: latest` with no new records | Expected |
| Lag gauge keeps rising | drain slower than produce, or `max_records` too low | Raise `max_records`, or use the stream trigger |
| Binary payload garbled | values are decoded as UTF-8 text | Add a decode step downstream |

The connection workbench (probe / explore / sample) is read-only: its `write` check is always reported
skipped, and a sample never moves the stored offsets.

## References

* `providers/inspecto-connectors-kafka/src/main/java/com/gamma/acquire/kafka/KafkaConnector.java`
* [spec](spec.md) · [architecture](architecture.md) · [connections user guide](../connectors/user-guide-connections.md)
