---
type: Reference
title: Collector connector SPI
description: The Java contract for Collector and export connectors - CollectorConnectorFactory, CollectorConnector, ConnectionWorkbench, ExportConnectorFactory, registration and the TCK.
resource: platform/inspecto-acquire/
tags: [module, connectors, spi, api]
timestamp: 2026-10-19T00:00:00Z
---

# Collector connector SPI

A connector module adds a transport (a `connector:` scheme on a connection profile) without touching the
engine. The contracts live in `platform/inspecto-acquire/src/main/java/com/gamma/acquire/`; the
implementations in `providers/inspecto-connectors` (sftp, ftp, ftps, db, s3, azure, gcs) and
`providers/inspecto-connectors-kafka` (kafka). The HTTP routes that use them (`/connections/...`) are in
the [HTTP contract](../../../../api/README.md); scheme behaviour is in [spec-connectors](spec-connectors.md).

## `CollectorConnectorFactory`

A connector is bound to one Pipeline's config, so ServiceLoader loads **factories**, and
`CollectorConnectors.forConfig` asks the one whose scheme matches.

| Method | Semantics |
|---|---|
| `String scheme()` | Lower-case token matched against `connection.connector`. Exactly one factory per scheme. |
| `CollectorConnector create(PipelineConfig cfg)` | Build from the Pipeline config alone. |
| `create(cfg, ConnectionProfile profile)` (default → `create(cfg)`) | Build with the resolved connection profile (host, port, credentials as `${...}` references, base path, `options`). `profile` is null when the Pipeline names no connection. |
| `ConnectionWorkbench workbench(profile)` (default `null`) | Probe / explore / sample surface. `null` = unsupported: probe checks report skipped, explore and sample refuse. |
| `void validate(profile)` (default accepts) | Called when a Connection is **saved** (`POST/PUT /connections`, bundle import). Refuse with `IllegalArgumentException` only, and **never dial**. |

Before any connector is built, `CollectorConnectors` passes every host the profile names (target, SSH
bastion, proxy, the host in `options.jdbc_url`, each broker in `options.bootstrap_servers`) through the
Safety Policy egress gate.

## `CollectorConnector`

One instance per configured Collector per scan cycle; it may hold a session and is closed when the cycle
ends (`AutoCloseable`). Instances need not be thread-safe.

| Method | Semantics |
|---|---|
| `scheme()` | Same token as the factory. |
| `EnumSet<Capability> capabilities()` | `STREAM`, `RANDOM_ACCESS`, `RESUMABLE`, `DELETE`, `MOVE`, `RENAME`, `TAG`, `ETAG`, `VERSIONING`. Drives retrieval planning and post-action validation. |
| `List<RemoteFile> discover(DiscoveryContext ctx)` | List candidates after the include/exclude/depth filters in `ctx`. Not de-duplicated (the ledger does that). No destructive action; a missing source answers an empty list. |
| `Readiness readiness(RemoteFile)` | `READY`, `NOT_READY` or `UNKNOWN` (engine falls back to size/mtime stabilisation). |
| `InputStream open(RemoteFile)` | Stream from the source, no local copy. |
| `Path fetchTo(RemoteFile, Path dest)` | Write the bytes **once** at `dest` (the final resting place, e.g. the backup dir). Never download to temp then move. |
| `void post(RemoteFile, PostAction)` | Retain / delete / move / rename / tag on the source side. Refuse what the capabilities do not allow. |
| `long pendingRecords()` (default `-1`) | Backlog for the continuous stream lane; -1 = cannot tell. Only Kafka answers. |
| `close()` (default no-op) | Release the session. |

Throw `AcquisitionException` for transport failures.

## `ConnectionWorkbench`

Bound to one profile, closed after use. `check(ProbeCheck, sampleLimit)` answers `AUTHENTICATE`, `READ`,
`WRITE`, `LIST` (reachability is generic, in `ConnectionProber`) as a `CheckOutcome` (`ok`, `fail`,
`skipped`). `explore(path)` returns `ResourceNode`s (`DIR`, `FILE`, `BUCKET`, `SCHEMA`, `TABLE`,
`COLUMN`); `sample(path, limit)` returns a bounded, never-persisted `SampleResult`. Throw `PathEscape`
(mapped to 403) or `NoSuchPath` (404). Secrets never travel through it: a probe reports whether a
reference resolves, never its value.

## `ExportConnectorFactory`

The outbound mirror: `scheme()` plus `ExportConnector exporter(ConnectionProfile)`, which must be lazy
(no dial in construction) and refuse only with `IllegalArgumentException`. Registered in
`META-INF/services/com.gamma.acquire.ExportConnectorFactory`.

## Registration

List each factory class, one per line, in
`META-INF/services/com.gamma.acquire.CollectorConnectorFactory` inside the jar, as
`providers/inspecto-connectors/src/main/resources/META-INF/services/com.gamma.acquire.CollectorConnectorFactory`
does, and declare `provides: contracts: CollectorConnectorFactory` in the jar's
`META-INF/inspecto/module.toon`. Put the jar on the classpath; the built-in `local` scheme needs no
factory.

## Minimal example

```java
public final class EchoConnectorFactory implements CollectorConnectorFactory {
    @Override public String scheme() { return "echo"; }
    @Override public CollectorConnector create(PipelineConfig cfg) { return create(cfg, null); }
    @Override public CollectorConnector create(PipelineConfig cfg, ConnectionProfile p) {
        if (p == null) throw new IllegalArgumentException("echo needs collector.connection");
        return new EchoConnector(p);   // implements CollectorConnector; dial lazily in discover()
    }
    @Override public void validate(ConnectionProfile p) {
        if (p.host() == null || p.host().isBlank()) throw new IllegalArgumentException("echo needs host");
    }
}
```

## Conformance (TCK)

Extend `CollectorConnectorFactoryContract` (or `ExportConnectorFactoryContract`) from
`platform/inspecto-acquire/src/test/java/com/gamma/acquire/testkit/` and return your factory, as
`providers/inspecto-connectors-kafka/src/test/java/com/gamma/acquire/kafka/KafkaConnectorFactoryTckTest.java`
does. It checks: lower-case scheme, exactly one factory per scheme on the classpath, `validate` never
dials (1 s bound against unroutable `192.0.2.1`), refusals are only `IllegalArgumentException`, a refusal
never echoes the credential, and `validate` is repeatable.
