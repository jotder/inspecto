---
type: Concept
title: Acquisition — specification — Retry, the connector SPI, connection profiles and control
description: Part 2 of 2 of Acquisition — specification: Phase F — retry, circuit breaker, post-action; The connector SPI and the resolvable schemes; Connection profiles, secrets, host keys, tunnels and proxies; The control surface; Observability; Network shares — the declined design, recorded once; Defaults worth not 'fixing'; References
tags: [acquisition, requirements, discovery, stability, dedup, watermark, guarantees, unpack, decompression, collector, archive, consignment]
timestamp: 2026-11-18T00:00:00Z
---

# Acquisition — specification — Retry, the connector SPI, connection profiles and control

Part 2 of 2 of [Acquisition — specification](spec-acquisition.md) · previous: [File connectivity, discovery, stability and collection guarantees](spec-acquisition-file-collection.md).

## Phase F — retry, circuit breaker, post-action

`RetryPolicy` (`platform/inspecto-acquire/src/main/java/com/gamma/acquire/retry/RetryPolicy.java`) wraps the
connectivity-sensitive connector calls — `discover` and per-file `fetchTo` — so a flaky endpoint gets
bounded retries instead of failing the whole cycle on the first hiccup. The base delay before the *n*th
retry grows from `initial_delay` toward `max_delay` on the configured curve, is clamped to `max_delay`, and
is then **full-jittered** — the actual sleep is uniform in `[0, base]` — to avoid a thundering herd of
pollers retrying in lockstep. Sleep and jitter source are injectable; `RetryPolicy.NONE` performs exactly
one attempt. Config `collector.retry: { count: 0, backoff: EXPONENTIAL, initial_delay: 1s, max_delay: 60s }`
(EXPONENTIAL/FIXED); an absent block is a single attempt.

`CircuitBreaker` (`platform/inspecto-acquire/src/main/java/com/gamma/acquire/CircuitBreaker.java`) is **per-source**,
keyed by `collector.id` on a process-wide `shared()` instance. On repeated connectivity failure it trips
`OPEN` and the engine *skips* that source for a cooldown rather than hammering a dead endpoint every cycle;
after the cooldown a single `HALF_OPEN` trial runs — success closes it, another failure re-opens it. The
clock is injectable. Thresholds are **passed per call** rather than stored, so one shared instance serves
every pipeline with no per-source coupling. Config `collector.circuit_breaker: { failure_threshold: 5,
cooldown: 5m }`; absent ⇒ never trips.

`PostAction` decides what happens to the **source-side** file after successful processing, validated against
the connector's declared `Capability` set before being applied. `Kind`: `RETAIN` (default) · `DELETE` ·
`MOVE` · `RENAME` · `TAG`. `resolveTemplate` expands `archive_path` date tokens **longest-first** so `yyyy`
is not eaten by `yy` (`yyyy yy MM dd HH mm ss`). Config `collector.post_action: { on_success: RETAIN,
archive_path: …, tags: {…}, on_unsupported: WARN_AND_CONTINUE }` — `on_unsupported` is the requirement §9 policy for
"delete requested on a read-only source"; a post-action that fails at runtime bumps
`inspecto_post_actions_failed_total` and **the file is still ingested**.
⚠ Both `RemoteAcquisitionHandler` author-facing messages name **`collector.post_action.on_success`**.

## The connector SPI and the resolvable schemes

The SPI — `CollectorConnector` (`discover`, `readiness`, `open`, `fetchTo`, `post`, and the `Capability`
enum `STREAM, RANDOM_ACCESS, RESUMABLE, DELETE, MOVE, RENAME, TAG, ETAG, VERSIONING`),
`CollectorConnectorFactory` (`scheme()` + `create(cfg, profile)` + the optional `workbench(profile)` hook,
registered via `META-INF/services`) and `ConnectionWorkbench` — lives in the core (`inspecto-acquire`, package
`com.gamma.acquire`) and is described with the per-scheme libraries in [connectors](../connectors/spec-connectors.md).

⚠ **"Eight connectors" is the count of the optional module, not of the registry.** Verified from `META-INF/services/com.gamma.acquire.CollectorConnectorFactory`: **seven** factories ship in `inspecto-connectors` (`sftp`, `ftp`, `ftps`, `db`, `s3`, `azure`, `gcs`), **one** (`kafka`) in the premium module `inspecto-connectors-kafka` (MODULE-REORG-1 P7, 2026-10-07; Standard and above — Basic has no Kafka), **two** more in `inspecto-engine` (`DatasetCollectorConnectorFactory` → `connector: dataset`, UI-S7; `PostgresConnectorFactory` → `connector: postgres`, BASIC-ELT-PROFILE-1 BE-4, 2026-11-19), and `local` is the built-in default that needs no jar. So eleven values resolve, and a bundle without the sidecar resolves only `local`, `dataset` and `postgres`. *(Two more entries exist under `inspecto/src/test/resources` — `FakeRemoteConnectorFactory` and `FakeOffsetTailConnectorFactory` — and are test-only; do not count them.)*

⚠ **`https` is a Connection scheme, not a Collector scheme** (2026-09-23). No `CollectorConnectorFactory` serves it: an `https` Connection is the target of the outbound **Webhook sink** (`sink.webhook`, [step-catalog](spec-step-catalog-outbound-sinks.md#webhook--sinkwebhook--the-outbound-webhook)), onboarded under the same `canOnboardConnections` grant as every other Connection. That grant is what makes a Connection the egress gate. `host`/`port`/`base_path` build the URL, `password` is the bearer-token reference, and the option `timeout_seconds` sets the request timeout. The sink refuses a tunnel or proxy. A Collector bound to an `https` Connection fails as an unknown scheme.

🔴 **For 85 days the remote connectors were build-available and deploy-absent.** `inspecto-connectors` was a
reactor module no bundle shipped, so `connector: s3` and its siblings could be authored, compiled and tested and
then did nothing in a deployment. Fixed 2026-09-07 (CONNECTORS-BUNDLE-1): it rides every bundle as the shaded
`inspecto-connectors.jar` sidecar. *(Since MODULE-REORG-1 P7, 2026-10-07, `kafka` rides its own premium
`inspecto-connectors-kafka` jar, Standard and above.)*

**The `dataset` scheme.** `inspecto-engine` registers `com.gamma.inspector.DatasetCollectorConnectorFactory`,
`scheme() == "dataset"`, which consumes another store's parquet snapshots. It is parsed with two fail-closed
refusals: `connector: dataset` without `collector.dataset` is refused, and `collector.dataset` without
`connector: dataset` is refused rather than silently ignored (`PipelineConfigParser.parseCollector`); a
`datasets/<id>` ref is normalised to the bare id.

🔴 **Its `post` action is FORCED to `RETAIN`, whatever the config says** — `post()` passes
`PostAction.RETAIN` to the delegate unconditionally
(`DatasetCollectorConnectorFactory`, whose own comment reads *"Retain is the only honest
post-action here, whatever the config says"*). ⛔ **A consumer can never delete, move or rename a
producer's snapshots.** A safety property that overrides authored config must be documented, or an operator
authoring `post: delete` will believe it took effect.

**Why this needs no watermark, and why an earlier watermark-runner design was superseded.** Snapshots are
**timestamp-named**, and the poll inbox is the pipeline's own `dirs.poll`, so the **existing marker dedup
already gives correct re-ingest-on-refresh semantics** — parquet was only ever missing an *ingest* lane, not
a new consumption mechanism, and no parser fence was needed. ⛔ Do not build a watermark here on the
assumption that one is missing.

⚠ **There is no "split acquire/ingest timers" scheduler for mid-graph Steps, and a docs simplification once
implied one.** The closest primitives are the collector poll loop, the stability gate and the high-watermark
filter — all of which gate **discovery**, not mid-graph Steps. The durable inbox is `dirs.poll` itself, and a
drain is simply the next poll.

**The `postgres` scheme (BASIC-ELT-PROFILE-1 BE-4, 2026-11-19).** `inspecto-engine` registers
`com.gamma.inspector.PostgresConnectorFactory`, `scheme() == "postgres"`: one Postgres table (`options.schema` +
`options.table`) or one read-only query (`options.query`, a single `SELECT` / `WITH`), copied into one typed Parquet
file per cycle (`<table>.parquet` / `query.parquet`, `STREAM` only, `post` is a no-op). It reads through DuckDB's
`postgres` extension, so it needs **no JDBC driver and Basic has it**; the JDBC `db` scheme stays the one for
incremental (`watermark_column`) loads. A time-zoned timestamp lands as its UTC wall time and a type with no import
column type (UUID, JSON, arrays, intervals) as text.
- **Fail closed on the profile** (`PostgresCollectorConnector`, run by `validate` at every Connection save — `POST` /
  `PUT /connections`, bundle import — and again when a connector is built): host, `database` and `username` are
  plain names, the port 1–65535 (absent = 5432), what to copy is named, and nothing else is accepted (a tunnel, a
  proxy or any other option is refused).
- 🔴 **The password is a `${ENV:INSPECTO_PG_…}` reference and nothing else** — no raw value, no `${SYS:…}` /
  `${FILE:…}`, no other variable. Whoever names the host also names the variable sent to it, and
  `ConnectionProfile` resolves any reference, so the rule lives in the scheme: an administrator decides which
  secrets may go to a database by naming the variable. It is resolved only at connect time into a DuckDB
  `TEMPORARY SECRET`, never logged or put in a connection string, and every error text is scrubbed of it.
- **Read-only three ways:** the attach is `READ_ONLY` (Postgres runs `transaction_read_only = on`), a query must be
  one `SELECT`/`WITH`, and every session refuses a login that could write (superuser, or INSERT / UPDATE / DELETE /
  TRUNCATE on any non-system table).
- **Add data's Postgres snapshot builds its connector here** (`PostgresSnapshot` → `PostgresConnectorFactory.connector`)
  and keeps a saved `postgres` Connection, `<dataset id>_postgres` (operator ruling O4). Its Pipeline still takes the
  export through the inbox; switching it to `collector.connector: postgres` waits for the replace-by-arrival step
  (plan B4). The `REMOTE_ACQUISITION` edition listing (`ProcessorCatalog`) does not list `postgres` yet: that is the
  Basic Gate's work.

**Object storage is deliberately SDK-free (ACQ-4).** The auth, profile mapping, capabilities and pagination of
`s3`, `gcs` and `azure` are in [connectors](../connectors/spec-connectors.md). One detail is recorded only here:
Azure lists blobs with `NextMarker` pagination, Range-resume fetch, and MOVE/RENAME = Copy Blob + Delete
**guarded on `x-ms-copy-status: success`** so a pending copy never deletes the source.

**`db` and `kafka` are the "virtual slice file" idiom.** `db` runs a date-templated JDBC query and
materialises the result as CSV; `kafka` drains a partition's unconsumed backlog per cycle into
`<topic>-p<partition>-<from>-<to>.<ext>`, which flows through the normal batch path — **no core-engine
change**. Kafka offsets are **not** a broker consumer group: the connector `assign()`+`seek()`s and the
consumed frontier rides the ledger watermark, persisted only after the batch commits (at-least-once; a crash
mid-ingest re-drains the slice rather than skipping it). Broker consumer groups stay out by operator decision
(STREAM-CONSUMER-1 Q4, 2026-09-24): one offset store, fewer ACLs, no broker-visible lag. The loop is the
Collector scan, plus — since ASSURE-PUSH-INGEST-1 (2026-09-29) — the continuous lane below for a Pipeline that
opts in with `trigger: {type: stream}`. The Kafka module itself: [connectors-kafka](../connectors-kafka/spec-connectors-kafka.md).

**One uncommitted slice per partition — the in-flight fence (STREAM-CONSUMER-1, 2026-09-24).** With split
acquire and ingest timers, a second acquisition cycle before the commit read the same stored frontier and
minted an overlapping slice under a new name, and the overlap ingested twice. Now `KafkaConnector.discover`
skips a partition, and an incremental `db` export (`watermark_column` set) skips its export, while
`AcquisitionLedgers.hasPendingDbWatermark(key)` is true: a stash for that key exists, its slice file still
exists, and its value is not already the committed one. The last two conditions make the fence release
itself. A slice quarantined out of the inbox, or a stash that outlived its commit, cannot hold a partition
forever. After a restart, `SliceFrontiers.restore` (Phase E) re-arms it. ⚠ **A slice that never ingests holds its
partition.** For example, a slice whose name the pipeline's `file_pattern` rejects stays in the inbox, so
nothing further drains from that partition. Before the fence, such a Collector re-drained a growing
overlapping slice every cycle instead. **Lag:** `inspecto_stream_lag_records{connection,topic,partition}` =
end offset − *committed* frontier, refreshed on every discovery (a landed-but-uncommitted slice still counts).
Also exported: `inspecto_stream_slices_drained_total{connection,topic}` and
`inspecto_slice_frontiers_committed_total{pipeline}` (Kafka and DB export), all three on `GET
/metrics/acquisition`. That JSON *is* the Collector's stream status surface: no field was added to `GET
/collectors`.

**The delivery grain is the slice, not the row.** Slice-name identity plus the fence give slice-level
at-least-once: after a crash at most one slice per partition is re-delivered. Row-level exactly-once is out
of scope by design. The default `options.payload: envelope` already carries `topic`, `partition` and
`offset` on every record, so a Dataset that needs row-level dedup keys on those downstream.

**Why the loop is the Collector scan (STREAM-CONSUMER-1 design pass, 2026-09-24).** Three homes were
weighed. (A) the scheduler's acquisition tick, hardened — **built**; its latency floor is the acquisition
cadence, or a `POST /collectors/{id}/notify` wake-up. (B) a continuous `trigger: stream` lane in
`PipelineScheduler` — **deferred** at the time, then built (below). (C) a long-running Job — **refused**
([plan](plan-elt.md)). The at-least-once fixes (Phase E and the fence) were needed under every
option, so they landed inside the row rather than as a separate P1 (Q1).

**The continuous lane — option B, built (ASSURE-PUSH-INGEST-1, 2026-09-29).** D-P7 named the target the
deferral waited for (p95 ≤ 30 s event → Incident), so B shipped as designed: *A plus a different trigger*.
`trigger: {type: stream, records: N, max_wait: T}` (defaults 1000 records, 5 s) gives an active remote
Pipeline one `StreamLane` (`inspecto-acquire`): a virtual thread that holds the Collector's connector OPEN (for
Kafka, one consumer for the lane's life) and every 200 ms asks `CollectorConnector.pendingRecords()`. It drains
when the backlog reaches N, or when a non-empty backlog has waited T. The drain is option A's path unchanged —
`CollectorProcessor.acquire` under the same `acquireGuard` claim the tick takes, then the ordinary
`runPipeline` — so the fence, the durable frontier and slice-level at-least-once all carry over, and a crash
mid-slice re-delivers that one slice. Kafka's probe skips fenced partitions, so an uncommitted slice cannot make
the lane spin. A connector that cannot count (`-1`, every non-Kafka one) drains on T alone.

- An `AcquisitionException` from the probe is an ordinary source failure (reopen next probe); anything else
  thrown by the probe or the drain marks the lane DEAD — ERROR log, `inspecto_stream_lane_deaths_total{pipeline}`,
  connector closed — and a reconcile replaces it.
- Both are spaced by a bounded exponential backoff with equal jitter (`StreamLane.Backoff`,
  ASSURE-STREAM-LANE-BACKOFF-1): the first failure of a streak retries at once, then 1 s doubling to a 60 s cap
  (`-Dstream.lane.backoff.baseMs` / `-Dstream.lane.backoff.maxMs`). The death backoff is held per Pipeline by
  `PipelineScheduler` so it outlives each replaced lane; the probe backoff is per lane. The stack trace (and the
  probe-failure WARN) logs once per streak; a healthy probe or drain resets it.
- The drain re-checks paused before acquiring and again before ingesting.
- Wiring: `PipelineScheduler.reconcileStreamLanes` runs on every acquisition tick (so hot-reload, pause and
  activation start or stop lanes), `forget` closes a lane, and `CollectorService.close` closes them all.
  `STREAM` stays LOOP-scheduled: the ordinary ticks keep running as the floor, and a lane only makes drains
  happen sooner.
- ⚠ The lane is per process: under Advanced scale-out every pod runs one, and the `acquireGuard`/run lease
  make the extra ones no-ops rather than duplicates.
- **Measured** (MockConsumer, N=200, T=1 s, 569 events in 20 random bursts): event → committed slice frontier
  p50 563 ms, **p95 1030 ms**. Through a real `CollectorService` with real ingest (`faketest` connector,
  T=1 s): file arrival → committed batch 1.6 s. ⛔ **Not measured: the Incident hop.** Alert evaluation after
  the commit is outside the lane and has no harness here, so D-P7 is *not yet proven end to end*.
  `KafkaRealBrokerTest` runs against a real broker at `-Dkafka.it.bootstrap` (default `localhost:9092`) and
  SKIPS with that message when none answers — no broker ships with the build.

## Connection profiles, secrets, host keys, tunnels and proxies

**Profiles and secrets** — `<name>_connection.toon` (`ConnectionProfile`), the deliberate `base_path` /
`basePath` pair and its round-trip guard, the five `SecretResolver` reference forms, `ConnectionRegistry`, and
the four proxy dial-through ships (SOCKS5, FTP/FTPS, HTTP `CONNECT` with the `TunnellingSocket` redirect,
PostgreSQL JDBC) are owned by [connectors](../connectors/spec-connectors.md); the copy-pasteable shapes are in
the [connectors runbook](../connectors/admin-guide-connectors-runbook.md). ⛔ No `#` comment lines in any
`ConfigCodec` file — JToon rejects them. Facts recorded here because the connectors page states them
differently or not at all:

- ⚠ **SEC-07 (2026-09-06): `${FILE}` and `${KEYSTORE}` are Standard + Advanced only.** They are served by
  the `SecretsProvider` SPI in the core, whose implementation is `inspecto-secrets`'s
  `FileKeystoreSecretsProvider`, discovered by ServiceLoader
  (`providers/inspecto-secrets/src/main/resources/META-INF/services/com.gamma.auth.secrets.SecretsProvider`). A
  bundle without that module — Basic — **refuses the scheme with an `IllegalStateException` naming the
  edition, never a silent `null`**; a connection test surfaces that refusal as its failure. A Vault/KMS scope
  is the Enterprise follow-on. ⚠ `connectors-runbook.md` (formerly `integrations.md`) once listed only 2 of
  the 5 forms and omitted this gate; its current text is correct — prefer it over any older account.
- Only `ConnectionRegistry.find` is consulted at `forConfig` time; a missing profile yields `null` and the
  factory decides. Since 2026-09-25 a remote Collector whose `collector.connection` names no profile in the
  Space is an ERROR finding at save ([plan](plan-elt.md)).
- **Proxy precedence.** A proxy is **ignored when an SSH `tunnel:` is also configured**, because the tunnel
  already rewrites the dial target to a local loopback forward. Fail-closed on what cannot be routed: an
  unrecognised proxy `type`, a JDBC driver other than PostgreSQL, and a proxy combined with an SSH tunnel on
  the JDBC path.

**Host-key policy.** `HostKeyPolicy` (`providers/inspecto-connectors/src/main/java/com/gamma/acquire/connectors/HostKeyPolicy.java`) is
derived from the profile's `options` (`HostKeyPolicy.from`). Three knobs, purely additive — with none of them
set the legacy accept-on-connect behaviour is unchanged (and logged at debug):

| Option | Effect |
|---|---|
| `host_key` | 🔴 a key **FINGERPRINT** — `SHA256:<base64>` or OpenSSH MD5 colon-hex. The field is literally named `fingerprint` and is handed to sshj's `client.addHostKeyVerifier(fingerprint)`, so a **raw `ssh-rsa AAAA…` key line cannot work**. `connectors-runbook.md`'s host-key table is right and any doc showing a raw key line is wrong. (`docs/okf/product/layer/platform/features-feature-inventory.md` once showed one; its host-key row now says a raw key line is rejected.) |
| `known_hosts` | path to an OpenSSH `known_hosts` file; the host must have an entry. Works across hops. |
| `strict_host_key: true` | when set and neither of the above is configured, **refuse to connect** rather than silently accept any key. |

Over a bastion, `HostKeyPolicy.bastionView` encodes the asymmetry: a single `host_key`
fingerprint matches **one** host, so it pins the *target* and does not apply to the bastion; `known_hosts`
can verify both hops. For a `db`/`ftp`/`ftps` profile reached through a tunnel the bastion is the only SSH
hop, so `host_key`/`known_hosts` pin **it** (`DbConnections.open`, `FtpConnector` — both pass
`HostKeyPolicy.from(profile)` to `SshTunnel.open`).

**SSH bastion.** `SshTunnel` is an `AutoCloseable` SSH TCP-forward honoured by `sftp`, `db`, `ftp` and
`ftps` via the profile's `tunnel:` block. FTP is the special case: it opens a **control** connection *and*
separate **passive data** connections, so the bastion must carry both — set `options.passive_ports` to the
range the server advertises (`PassivePorts`); each port is forwarded loopback→server and the client is told
to dial the loopback. Active mode cannot traverse a tunnel, so a tunnelled FTP connection is always passive.
⚠ Tunnel FTP *without* `passive_ports` and only the control channel is forwarded — transfers fail unless the
server's passive ports happen to be independently reachable (the connector logs a warning).

## The control surface

| Route | Notes |
|---|---|
| `GET /collectors` | flat view of every pipeline's acquisition config (`AcquisitionRoutes`); since 2026-09-15 each row also carries the pipeline's **polling session** — `lastPollAt`, `pollCount`, `lastPollError`, `lastPollErrorAt` (`PipelineScheduler.pollStates`, `DUCKLE-C9-WATCHER-NOT-A-RUN-1`). ⚠ IN-MEMORY and lossy across restart by design: this is liveness ("is this collector alive, when did it last look, what did it last say?"), not an audit trail — the durable record of work is the Run/Consignment ledger. ⛔ It corrects no miscount: a quiet poll never minted a Run (`CollectorProcessor.ingest` returns before `RunIds.next()`, fixed `1fda46d5`), which is why the row was rescoped to observability before it was built. `lastError` is a message, not a metric, because a Prometheus gauge cannot say *what* failed. 🔴 Since `POLL-STATE-BLIND-TO-CONNECTOR-FAILURE-1` (closed 2026-09-15) an **acquisition that throws before landing anything** — a connector that cannot be built (unknown dataset, unreachable host) — is recorded as a poll that happened and failed (`lastPollError: "acquisition failed: …"`); before that the row stayed all-null while the log carried the ERROR. ⚠ Still NOT a poll: an event-triggered run (`on: dataset` / `on: commit`) — those are Runs, and `pollCount` counts scheduler polls only, by design. |
| `POST /collectors/{id}/notify` | ACQ-6 push discovery; `canOperateRuns`; `202`+`Location` under v1; audited `collector.notified` |
| `POST /streams/{id}/records` | **push ingest** (ASSURE-PUSH-INGEST-1). `{id}` is the Collector id (`GET /catalog/streams`). NDJSON (`application/x-ndjson`) or CSV (`text/csv`), validated WHOLE before anything lands; one server-named file `push-<utc>-<8 hex>.<ext>` moved atomically into `dirs.poll`, so the ordinary Collector path ingests it. Gates: `canOperateRuns` · `429` per-caller push bucket (burst 60, 10/s) · `404` unknown Stream · `501` Dataset-fed · `409` template, `pipeline paused` (paused or not active — refused rather than stranded in an unwatched inbox), or the same caller's `Idempotency-Key` still in flight · `415` other types · `413` over `-Dstreams.push.max_bytes` (8 MiB) or `-Dstreams.push.max_records` (100 000) · `413` also for a KEYED push over the W5 hash window (`Idempotency.MAX_REQUEST_BYTES`, 1 MiB), which the stage would run un-keyed · `422` not UTF-8, a malformed line (named), no records, or a `file_pattern` that would never pick the file up. A leading UTF-8 BOM is stripped for both formats, before validation and before the write. A keyed replay returns the first `{file}` to the same caller only — the key is the W5 one (Space + principal + path + body hash, SEC-IDEMPOTENCY-REPLAY-1), and the in-flight reservation reuses it, so two callers' equal keys never collide. One AUDIT row `stream.records_pushed` with `records`, `bytes`, `file` — never the payload. ⛔ **Not a config write**: data arriving at a Stream is what a Collector landing a fetched file does, so no write-root gate and no approval hold. ⚠ The idempotency store is in-memory per process (10 min): a replay after a restart lands the batch again; row-level dedup is downstream, as for Kafka. |
| `GET /metrics/acquisition` | JSON snapshot of the twelve acquisition metrics (`AcquisitionRoutes.ACQ_METRICS`), complementing the text-only Prometheus `/metrics` |
| `GET /connections`, `GET /connections/{id}` | profiles, **secret-masked** |
| `POST`/`PUT`/`DELETE /connections[/{id}]` | ⚠ writes require **`canOnboardConnections`** — its own Admin-only grant, deliberately *not* `canAuthorWorkbench`, because Connections are the credential/egress surface (`ConnectionRoutes.register`). |
| `POST /connections/{id}/test` | TCP reachability + latency + secret **resolvability**; `canOnboardConnections` since 2026-09-17 (it dials from the server's network position); `404` unknown id; `422` on `?target=tunnel\|proxy` with none configured, or an unsupported target |
| `POST /connections/test` | probes an **unsaved** profile from the create/edit form; `?target=connection\|tunnel\|proxy` picks the hop; `canOnboardConnections` since 2026-09-17 |
| `POST /connections/{id}/probe` · `GET …/explore?path=` · `GET …/sample?path=&limit=` | the graded workbench. Gates: `404` unknown id · `422` unknown check name · `400` missing sample path · **`403` path escape (jail)** · `404` unknown path · `501` connector without workbench support · `502` protocol failure. `probe` needs `canOnboardConnections` (since 2026-09-17: it reads through the saved credential); `explore` and `sample` are reads with no capability gate. |

The probe orchestration (`ConnectionProber` — 🔴 **a probe never fabricates a check it did not run**) and the
per-scheme workbenches are in [connectors](../connectors/spec-connectors.md). Their two standing disciplines:
**remote sample fetches the whole file only when the listed size is ≤ 8 MiB** (`SAMPLE_FETCH_CAP`), and
**WRITE is always *skipped* for DB, object stores and Kafka** — a workbench never mutates a database, writes a
scratch object into someone's production bucket, or produces a probe record onto a topic to prove write access.

## Observability

Events (`spi/inspecto-audit-spi/src/main/java/com/gamma/audit/EventType.java`), all emitted through `AcquisitionTelemetry` with
`CollectorProcessor`'s FQN as the event `source` so the stream stayed byte-identical when the emitters moved
out: `FILE_DISCOVERED` · `FILE_STABLE` · `FILE_FETCHED` (carries `bytes`) · `FILE_VALIDATED` ·
`FILE_FETCH_FAILED` · `FILE_QUARANTINED` · `FILE_CHANGED` · `FILE_ARCHIVED` (carries `action`) ·
`SOURCE_CIRCUIT_OPEN` · `SEQUENCE_GAP` (carries `expected`, `sequence`, `unit`).

Metrics, all labelled by pipeline: `inspecto_files_discovered_total` · `inspecto_files_downloaded_total` ·
`inspecto_downloads_failed_total` · `inspecto_duplicates_skipped_total` · `inspecto_watermark_skipped_total`
· `inspecto_post_actions_failed_total` · `inspecto_bytes_transferred_total` · `inspecto_fetch_seconds`
(histogram) · `inspecto_active_connections` (gauge) · `inspecto_files_waiting_stability` (gauge) ·
`inspecto_acquire_backpressure_skips_total` · `inspecto_slice_frontiers_committed_total`. Two more are labelled
by connection instead (see the connector SPI section): `inspecto_stream_lag_records` (gauge, + `topic`, `partition`) and
`inspecto_stream_slices_drained_total` (+ `topic`). `AcquisitionTelemetry` methods are pure leaves — each only
emits an event or bumps a metric, carrying no acquisition logic.

## Network shares — the declined design, recorded once

⛔ **NFS/SMB/CIFS is a declined design, not a gap.** The engine deliberately has **no** in-process SMB/NFS
client, and the config safety validator **rejects UNC paths** (`\\server\share`) at the path jail — that is
the security boundary. To collect from a share: mount it at the OS level (Windows
`net use X: \\server\share /persistent:yes`; Linux `mount -t nfs|cifs … /mnt/share`), add the mount point to
the jail via `-Dassist.safety.roots=<existing roots>;X:\` (a `;`-separated list), and point `dirs.poll` (or
`base_path`) at the mounted path. The built-in `local` connector then handles discovery / stability / dedup
identically to a local inbox, and credentials, reconnection and caching stay the OS's job, where they are
audited and battle-tested. Why it is refused: [plan](plan-elt.md).

## Defaults worth not "fixing"

Recorded because both source files agreed on them and a merge could plausibly "correct" one: ledger backend
**`memory`** · staging dir **`<dirs.temp>/acquire`** · back-pressure high-water **0 (off)** · S3 region
**`us-east-1`** · duplicate mode **`path`** · guarantee **`BEST_EFFORT`** · post-action **`RETAIN`** ·
`on_unsupported` **`WARN_AND_CONTINUE`** · discovery **`poll`** · `recursive_depth` **`-1`** ·
consignment `order` **`mtime`** · stability `window` **`30s`** / `size_checks` **`2`** · retry
`initial_delay` **`1s`** / `max_delay` **`60s`** / backoff **`EXPONENTIAL`** · breaker `failure_threshold`
**`5`** / `cooldown` **`5m`** · `parallel_fetch` **`1`** · `rate_limit` **0 = unlimited**.

---

# Part 3 — The Unpack stage

Moved to its own page: **[spec-acquisition-unpack.md](spec-acquisition-unpack.md)** — where the stage sits, naming, skipped entries, the archive verdict, the run-level unpack ledger, extension-insensitive identity and standing traps.

---

## References

| Mechanism | Owning file |
|---|---|
| Plan of record — requirements, decisions, open work, refusals, verification | [plan-elt.md](plan-elt.md) |
| Poll cycle, phases A–F summary, the Collectors module page, multi-location ingest | [architecture-elt.md](architecture-elt.md) |
| Connector SPI, the remote schemes, stage-then-land, profiles, secrets, workbench, proxy chain | [connectors/spec-connectors.md](../connectors/spec-connectors.md) |
| The Kafka connector, its fence and stream lane | [connectors-kafka/spec-connectors-kafka.md](../connectors-kafka/spec-connectors-kafka.md) |
| Operator runbook: profile YAML, host-key table, FTPS, bastion, db-export | [connectors/admin-guide-connectors-runbook.md](../connectors/admin-guide-connectors-runbook.md) |
| Module boundary + dependency confinement of the connectors jar | [connectors/architecture-connectors.md](../connectors/architecture-connectors.md) |
| Consignments, the ingest strategies and commit order that consume the landed inbox | [spec-ingest.md](spec-ingest.md) (Part I ingest, Part II Consignments) |
| Screens and the Collector configuration surface | [user-guide-elt.md](user-guide-elt.md) |

## Changes

- 2026-09-13: both `RemoteAcquisitionHandler` messages corrected — they had named a key that does not exist.
