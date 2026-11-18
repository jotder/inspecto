---
type: Capability
title: Acquisition & connectivity (ACQ) — plan
description: The plan of record for the ACQ area — Connections, Collectors and the acquisition framework — its purpose and scope, requirements of record, dated decisions, what is not built, what was refused and how it is verified. The as-built mechanism is in spec.md.
resource: platform/inspecto-acquire/src/main/java/com/gamma/acquire
tags: [acq, capability, acquisition, connections, collectors, connectors, ledger, plan]
timestamp: 2026-11-18T00:00:00Z
---

# Acquisition & connectivity — plan (`ACQ`)

**What this covers.** The record of what was asked of acquisition (getting files from wherever they live
into a Pipeline's inbox, exactly once), what was decided along the way and why, what is still open, what was
deliberately refused, and which tests prove it. It is the page to read before proposing a change to
Collectors or Connections, so that a settled question is not reopened. How the engine actually works is in
[the specification](spec.md); the screens are in [the user guide](user-guide.md).

**Contents**

1. [Purpose & scope](#1-purpose--scope)
2. [Requirements of record](#2-requirements-of-record)
3. [Decisions](#decisions)
4. [Not built](#not-built)
5. [Refused & superseded](#refused--superseded)
6. [Verification](#6-verification)
7. [References](#references)

> **Canonical vocabulary.** The two authored nouns are **Connection** (a named endpoint + credential
> definition) and **Collector** (a configured collection task bound to one Connection). ⛔ The
> acquisition entity is never a *Source* — that flipped 2026-07-14 (`GLOSSARY.md` §2/§3). A *data <!-- vocab-allow: the doc states the ban it enforces -->
> origin* is a **Stream** or a **Reference**. The pipeline TOON block is `collector:`, and the parser
> reads only that spelling — there is no `source:` block and no alias for one.

## 1. Purpose & scope

ACQ is everything that happens **before** a byte becomes a record: reaching a remote system, deciding
which files are new, proving they are complete, and handing them to ingest exactly once. Its promise is
narrow and operational — *one declarative Collector onboards a feed, and a crash never double-loads it.*

**In scope:** Connections and their credentials; Collectors and their cadence; the connector SPI and the
schemes that implement it; discovery, stability detection, duplicate prevention, watermarks, gap
detection; retry, circuit-breaking and rate-limiting; post-fetch disposition of the remote file; the
acquisition ledger; the Unpack stage.

**Not in scope, and deliberately so:**

| Adjacent concern | Whose it is |
|---|---|
| Parsing a fetched file into records | `ING` — ingestion & parsing ([ETL plan](../elt/plan.md)) |
| What runs after a commit, and in what order | `PIP` — pipelines & orchestration |
| The Consignment as a unit of work, and its addressing | `PIP` / the engine's [Consignments spec](../engine/spec-consignments.md) |
| The Connections **pane** and the Collector drawer | `UI` — operator console |
| Who may operate a Collector run | `SEC` — capability gating (`canOperateRuns`) |

⚠ The boundary that is easy to get wrong: **acquisition never widens to "read from N places at once."**
Multi-location ingest is composition of existing pieces — see
[multi-location ingest](architecture.md#multi-location-ingest--the-composition-pattern), which is a `PIP` concern.

## 2. Requirements of record

Seven requirements, all shipped. ⚠ **The feature × edition matrix in
[`spec-editions.md`](../../layer/platform/spec-editions.md) (the `SP-ACQ-*` rows) is authoritative for the
Edition column**; this table mirrors it. The wording of the original requirement's 14 numbered areas is
[spec Part 1](spec.md#part-1--the-original-requirement).

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| `ACQ-1` | **Connections** — named endpoint + credential definitions (SFTP/FTP/FTPS, database), reused by many Collectors | Must | ✅ SHIPPED | All ⚠ |
| `ACQ-2` | **Collectors** — configured collection tasks (paths/queries, cadence, filename patterns, dedup policy) bound to one Connection | Must | ✅ SHIPPED | All |
| `ACQ-3` | Acquisition framework — ledgers, dedup, watermarks, gap detection, retry (phases A–F) | Must | ✅ SHIPPED | All |
| `ACQ-4` | Object-storage (S3/GCS/Azure/MinIO) **+ network-share (NFS/SMB)** connectors on the connector SPI | **Must** | 🟡 **PARTIAL — object storage shipped, network-share REFUSED** (§5.1) | All |
| `ACQ-5` | Streaming source consumer (e.g. a Kafka topic drained by a Collector) | Should | ✅ SHIPPED 2026-07-08 | All |
| `ACQ-6` | Push / event-driven file discovery (replace poll where the remote can notify) | Could | ✅ SHIPPED 2026-07-08 | All |
| `ACQ-7` | etag / version-aware dedup dimensions | Should | ✅ SHIPPED 2026-07-08 | All |

**Three corrections this table makes to its predecessor** (`REQUIREMENTS.md` §3.1), each verified
against source:

- **`ACQ-4` was marked a flat `SHIPPED`.** It is not: the requirement asked for object storage **and**
  network-share connectors, and the NFS/SMB half was **declined by design**, not delivered. A `Must`
  recorded as shipped while half its scope was refused is the shape that makes a requirement register
  untrustworthy — the refusal now has a home in §5.1 instead of being absorbed into a green tick.
- **`ACQ-6`'s route was wrong.** It documented `POST /sources/{id}/notify`; the route is
  **`POST /collectors/{id}/notify`** (`AcquisitionRoutes`), audited as `collector.notified`.
- **`ACQ-7`'s config key was wrong.** It documented `source.duplicate.mode`; the block is
  **`collector.duplicate`** (`PipelineConfigParser.parseCollector` — `duplicate` is read from the
  `collector` map).

⚠ **`ACQ-1` carries an edition nuance the matrix column cannot express.** Connections are `All`, but two
of `SecretResolver`'s five reference forms — `${FILE:…}` and `${KEYSTORE:…}` — are **Standard +
Advanced only** (SEC-07): a Basic bundle refuses the scheme *by name* and a connection test surfaces that
as the failure ([spec](spec.md#connection-profiles-secrets-host-keys-tunnels-and-proxies)).

🔴 **A requirement is not delivered until it is *reachable*.** For 85 days the remote connectors were
build-available and deploy-absent — no bundle shipped `inspecto-connectors` — until CONNECTORS-BUNDLE-1
(2026-09-07). "SHIPPED" against a module no bundle carries is the most expensive kind of true statement
([spec](spec.md#the-connector-spi-and-the-resolvable-schemes)).

## Decisions

Dated, one line each, with the reason — because the reason is what stops a decision being re-litigated.
Only decisions that still bind are listed; where one reversed an earlier one, both appear.

### Vocabulary

| Date | Decision | Why |
|---|---|---|
| 2026-07-14 | *Source* → **Collector** (and not *Poller*) for the acquisition entity | "Source" collided with the data-origin sense; "Poller" would have excluded push inputs. Rollout is DONE end-to-end — routes `/sources`→`/collectors`, the TOON block `source:`→`collector:`. Residual by design: the lineage attribute key, `Event.source()`, and `NodeCategory.SOURCE`. `GLOSSARY.md` §13 | <!-- vocab-allow: records the Source->Collector decision -->
| 2026-07-14 | *Data Source* → **Stream** + **Reference** | Two different natures of origin were wearing one name. Acquisition config is unaffected: it stays Connection + Collector | <!-- vocab-allow: records the Data Source->Stream/Reference decision -->

### Architecture & packaging

| Date | Decision | Why |
|---|---|---|
| 2026-08-11 | `PipelineConfig.collector` is **singular, permanently** | Every stateful subsystem — ledger, watermark, dedup, gap detection — is keyed on one collector id. A list would force a ledger re-key, which is a migration, not a feature |
| 2026-09-07 | The connector sidecar is **NOT edition-gated** | Remote acquisition is core, not a paid tier. `inspecto-connectors.jar` rides every bundle; the ~32 MB was accepted deliberately (CONNECTORS-BUNDLE-1) |
| 2026-09-06 | **SEC-07** — `${FILE}` / `${KEYSTORE}` are Standard + Advanced; the SPI core stays in `inspecto-acquire` | Keeps the lean core free of a secrets client while letting Basic refuse the scheme **by name** rather than failing obscurely. Vault deferred to policy, not code |
| 2026-06-24 → reversed | Connections as a standalone `inspecto-connect` repo with a cross-repo `@PublicApi` | See §5.2 — reversed in fact, and this plan is where that reversal is recorded |

### Connectivity, probe & secrets

| Date | Decision | Why |
|---|---|---|
| 2026-07-08 | Every remote connector is **SDK-free** — SigV4 for `s3`, SharedKey for `azure`, and (2026-07-22) a native JSON API + RS256 service-account JWT for `gcs` | A small SBOM is a FedRAMP asset; three cloud SDKs would have dwarfed the whole artifact |
| 2026-07-08 | `kafka` uses `assign()`+`seek()` with **no consumer group** | The consumed frontier rides the acquisition ledger watermark instead, so replay and crash-recovery reuse machinery that already exists and is already tested |
| 2026-09-24 | The stream consumer loop **stays in the Collector scan**; broker consumer groups stay out (STREAM-CONSUMER-1 Q2, Q4) | No latency target was named, so a continuous lane buys nothing yet; one offset store and fewer ACLs outweigh broker-visible lag |
| 2026-09-29 | The continuous `trigger: stream` lane is **built** (option B), reversing the 2026-09-24 deferral; push ingest is a DATA write, not a config write (ASSURE-PUSH-INGEST-1) | D-P7 named the latency target the deferral waited for; a push is data arriving at a Stream, so the write-root and approval gates (config) do not apply — it is gated as an operation, capped, rate-limited and audited |
| 2026-09-24 | A remote slice's frontier must **survive a restart**, for `kafka` AND `db` (STREAM-CONSUMER-1 Q3) | A restart must never re-ingest; built as a durable per-slice record, not a name-derived frontier |
| 2026-08-13 | HTTP `CONNECT` proxy dial-through **shipped, reversing an earlier fail-closed rejection** | The rejection assumed a JDK socket could tunnel transparently. It cannot, so the tunnel had to be explicit — the earlier "no" was based on a wrong premise, not a policy |
| 2026-09-06 | JDBC dial-through is **PostgreSQL only**, fail-closed elsewhere | Each driver's socket-factory hook differs; a generic claim would have been untestable |
| 2026-07-18 | The connection **probe is graded** (`REACHABILITY` → `AUTHENTICATE` → `READ` → `WRITE` → `LIST`) and WRITE is `skipped` for every remote scheme | A workbench must never write a scratch object into a customer system. Only `local` does write-and-delete. See §5.5 |
| 2026-09-17 | Connection test and probe need **`canOnboardConnections`**; explore and sample stay ungated reads | Test dials from the server's network position and probe reads through the saved credential |

### Poll cycle, consignments, dedup

| Date | Decision | Why |
|---|---|---|
| 2026-08-12 | Consignment ordering defaults to **`mtime`** (arrival), not filename | Filename order is a guess about someone else's naming; arrival order is a fact the filesystem already knows |
| 2026-09-02 | The batch knobs live at **`collector.consignment.{max_files,max_bytes,order}`** | The planner runs before any sink exists, so the knobs cannot hang off the sink (CONSIGNMENT-HOME-1) |
| 2026-09-01 | Dedup keys are **SHA-256 hashed** in the ledger | A ledger is an operational artifact that gets exported and read; raw paths leak customer structure |
| 2026-08-05 | `file_dedup` is a **Guarantee** (file grain); `dedup` is a **Step** (record grain) | One word was doing two jobs at two grains — the split is why either can be reasoned about |
| 2026-08-23 (operator, `d6cd55b7`) | **Unpack sits at the Collector, before planning**; the Consignment model is unchanged | An Entry is a File from birth; "update the Consignment" needs a merge the immutable manifest has no seam for |
| 2026-08-26 (operator) | Four Unpack verdicts; **`UNPACKED_PARTIAL` commits**; `EMPTY` ≠ `UNREADABLE` by `entriesFound()` | A bad Entry must not discard 499 good ones; an operator must hear "empty" apart from "locked" |
| 2026-08-26 → ratified 2026-09-06 | Lineage records the **Entry name**, never `archive!entry` nor the index prefix; the verdict reports at **archive level** with **entry-grain** detail, one ledger row per Archive per Run | An operator asks "did that archive land?" and then "which member failed?" — one answer cannot serve both; a data column must not carry a key consumers parse |
| 2026-08-28 | One decompression vocabulary (`Compression.INLINE_SUFFIXES`), **one-way** drift guard | The SPI is deliberately wider; reverse containment would forbid `.Z` / `.tar` |
| — | Unpack `depth` **= 1**; nested archives refused by name. Identity is extension-insensitive and the collision is **not a bug** | Recursion needs a bound nobody asked for; the allow-list is the remedy, a two-tier scheme would break the actual requirement |
| 2026-10-06 (operator) | `gap_detection` gains `file_template` + `seq_scope`; a date-only template means one expected file per day | The completeness KPI and the delivery check need a second token and a per-day expectation the `sequence` grammar cannot express |
| 2026-11-17 | The per-cycle **byte cap also bounds the fetch** when every listed file has a size (MoSCoW C8); the file-count cap stays after landing | Moving the adaptive, on-by-default file cap would change every remote Collector's default |

🔴 **One gap in the decision record, stated rather than papered over: there is no dated decision anywhere
in the current tier for the stability gate** — the mechanism that decides a remote file has stopped
growing. It is the single most consequential piece of acquisition (get it wrong and you ingest a
half-written file), it is fully built and documented as *mechanism* in
[spec Phase B](spec.md#phase-b--the-readiness--stability-gate), and *why* its thresholds are what they are is
recorded nowhere. ⛔ Do not invent one. It needs an operator to state the intent, and until then the defaults
are as-built facts with no recorded rationale.

## Not built

⛔ **Pointers, never copies.** Each row names its board id; the board is the authority for status and
priority. A row with no id is flagged `UNTRACKED` and needs filing before it can be scheduled.

### Partial — the capability exists, the acquisition-node path is unproven

| Item | Board id | What remains |
|---|---|---|
| S3 / GCS object ingest | `SP-ACQ-06`, `SP-ACQ-08` | The connectors ship and are tested; there is **no proven end-to-end acquisition-node run**. ⚠ This is not a contradiction of `ACQ-4` — *a connector is not a Step* (§5.7) |
| Azure ADLS Gen2 | `SP-ACQ-07` | Blob works; the Gen2 hierarchical namespace is unproven |
| Kafka consumer as a Collector | `SP-ACQ-09` | The per-scan drain ships, hardened to slice-level at-least-once. What keeps the cell 🟡: it is proven only against `MockConsumer` and a fake offset-tail connector; the real-broker test (`KafkaRealBrokerTest`, 2026-09-29) exists but SKIPS where no broker runs, which is every build so far. Consumer-group semantics stay out by design |
| Excel workbook fan-out | `SP-ACQ-05` | One sheet per file today; multi-sheet fan-out unbuilt |
| The stream lane's Incident hop | — (ASSURE-PUSH-INGEST-1) | Event → committed slice is measured (p95 1030 ms); alert evaluation after the commit has no harness, so D-P7 (p95 ≤ 30 s event → Incident) is not proven end to end |

### Planned — declared, unstarted

`SP-ACQ-10` … `SP-ACQ-20` — eleven 🔲 rows: Pulsar, Kinesis, SQS, RabbitMQ, MQTT, Debezium CDC, Delta,
Iceberg, Snowflake/BigQuery readers, a REST poller, a webhook receiver and gRPC. ⚠ They are *declared*
so they are not re-proposed as new ideas; none is scheduled.

**Still future (declared, so it is not re-proposed as new):** a presigned-URL / STS credential mode for `s3`;
a Vault/KMS `SecretResolver` scope. The SPI makes each non-disruptive. (The remote-fetch bandwidth cap that
used to sit here was built 2026-11-17 — see below.)

### Open elsewhere on the board

| Item | Board id | What remains |
|---|---|---|
| Vault / KMS secret provider | `BACKLOG.md` §6 "Decided 2026-09-06" note (`GAP-6` in older docs; no open row) | Deferred by the SEC-07 decision, not blocked: only when a client policy requires it |
| ~~"Listed-not-yet-fetched" gauge~~ | branch-aware residual **(e)** | **DROPPED (operator, 2026-10-06)** — recorded on the *Branch-aware executor residuals* row |
| ~~Remote-fetch bandwidth cap (was `acquire.maxFilesPerCycle`)~~ | branch-aware residual **(f)** | **BUILT 2026-11-17 (MoSCoW C8)**: `-Dingest.maxBytesPerCycle` is applied before the fetch when every listed file carries `RemoteFile.size` ([spec](spec.md#the-poll-cycle-and-its-two-timers)); an unsized listing keeps the cap after landing. The file-count cap stays after landing |
| Multi-part archives; xz / zstd codecs; `.Z` round-trip test; a stale `META-INF/services` header | Unpack (11), `BACKLOG.md` §3 *Onboarding, Catalog, Parsing* | `depth: 1` is deliberate (§5.8); *multi-part* (`.z01`, split RAR) is simply unbuilt; xz/zstd is a dependency sign-off, not a build |
| Unpack (5)(8)(10) — partial never fails; nested refused; crash mid-archive re-ingests committed Entries by design | `BACKLOG.md` §6 | LEAVE |
| ~~Missing connection not caught until first poll~~ | — (closed) | **Now caught at save**: a remote Collector whose `collector.connection` names no profile in the Space is an ERROR finding at `/config/write` (`ConfigRoutes.unknownConnectionFindings`); a Space import with a missing connection is a WARNING that writes the dependents switched off (2026-09-25) |
| Acquisition ledger defaults to `memory` | — (the *Completeness KPI hold* that carried it was lifted 2026-10-06) | 🔴 The default loses the ledger on restart, and with it the committed slice frontier. The completeness KPI no longer depends on it: `kpi.completeness` reads the consignment-output store and refuses unless that store is durable (`KpiCompletenessJob`) |

### 🔴 UNTRACKED — no board row exists; file before scheduling

> Filed as a family 2026-09-09 (Sprint 2) with no cross-cutting rows: every item here is area-specific, and
> a design finding recorded in its owning plan is already filed — copying it onto the board would give it two
> homes. See [`post-consolidation-sprints.md`](../../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.

| Item | Where its design lives | Why it matters |
|---|---|---|
| Credentials & network profiles as **their own referenced resources** | `plans-archive/acquire-controller-service-design.md` §2.1 | The one untracked item with real design content and no home. Today a `tunnel` is **inline** on the Connection, so two Collectors through the same bastion duplicate it — and a rotation edits N places |
| Controller-service **VALID/INVALID lifecycle + run-gating** | same, §3 | Verified absent: validation gates *save* and *compile*, never *run*. A Collector whose Connection has gone bad still starts a run and fails inside it |
| `acquisition.list` / `acquisition.fetch` node split | same, §4 | Verified: `BuiltinNodeType.ACQUISITION` is the only acquisition node. ⚠ The SPI seam for the split already exists, so this is smaller than it reads |
| A "referencing components" read view | same | "What breaks if I change this Connection?" has no answer today |
| Presigned-URL / STS credentials for `s3` | `AwsSigV4`'s class javadoc states the scope reason in-code | Long-lived keys are the only option today |

## Refused & superseded

**This section exists because a refused idea with no recorded refusal gets re-proposed.** `BACKLOG.md` §6
does this for *work*; this does it for *design*. Each row states what was refused and the reason — the
reason is the load-bearing half.

### 5.1 An in-process NFS / SMB / CIFS client — REFUSED, and it is the security boundary

`ACQ-4` asked for "network-share (NFS/SMB) connectors on the connector SPI". **There is no share
connector and there will not be one.** The path validator **rejects UNC outright**
(`PathJail` refuses a value that "is a UNC/network path", the rule `ConfigSafetyValidator` documents as
"reject UNC/network paths"; `DataRef` bans the `\` separator that `\\host\share` needs). That rejection is not a gap someone forgot to close: it is the boundary that
keeps an authored config from reaching an arbitrary host on the network.

**What serves the need instead:** mount the share at the OS level and point the built-in `local`
connector at it ([spec](spec.md#network-shares--the-declined-design-recorded-once)). The editions matrix's
`SP-ACQ-01` (`spec-editions.md`) is titled "**Local / NFS** directory watcher" — `LocalFileSystemConnector`, the
default collector — and reads `✅✅✅`, so NFS **is** served; what is refused is an in-process protocol client.

✅ **The two tiers now agree.** The old root `ROADMAP.md` §3.2 kept the half open, while `REQUIREMENTS.md`
closed it as the mounted-share pattern. Its successor, [`plan-roadmap.md`](../../layer/platform/plan-roadmap.md)
§3.2, records the half as "Not delivered, and not pending — REFUSED by decision" (framing corrected
2026-09-09). **The refusal wins, because the code enforces it.**

### 5.2 A standalone `inspecto-connect` repo with a cross-repo `@PublicApi` — REVERSED IN FACT

Decided 2026-06-24 (`plans-archive/brainstorm-tingly-storm.md`): build connections as their own
repository behind a stable cross-repo `@PublicApi`. **Verified against the tree: no such repo or module
exists.** What happened instead is `inspecto-acquire` (the SPI) plus the optional `inspecto-connectors`
(the implementations) — which delivers the same dependency isolation *without* the cross-repo
versioning cost the plan itself had flagged as its main risk.

The half that was **kept** is worth naming, because it is why the reversal was cheap: the UI-first
contract freeze. The connection contract was frozen from the consumer's side first, so moving the
implementation was a packaging decision rather than an interface renegotiation.

⚠ Residue: `@PublicApi(since = "4.0.0")` marks eight `inspecto-acquire` SPI types (`CollectorConnector`,
`CollectorConnectorFactory`, `ExportConnector`, `ExportConnectorFactory`, `DiscoveryContext`, `RemoteFile`,
`PostAction`, `AcquisitionException`). It is **not** a stale marker: trunk is `4.0.0-SNAPSHOT`, and per the
[API stability policy](../api/plan-api-stability.md) `since = "4.0.0"` means "will become public API in 4.0.0"
(the deleted `4.x` branch never shipped), so these types may still change before that release.

### 5.3 NiFi's FlowFile session/queue runtime — REFUSED

Adopt NiFi's *authoring contracts*, not its *runtime*. A live inter-node queue would make the pipeline a
stateful cluster; the flow-graph decision is recorded as locked. The consequence to keep in mind when
reading any NiFi-shaped design doc: **"predecessors ran" means materialized output on disk, not a queue
handoff.**

### 5.4 Enriching `POST /components/connection/{id}/test` — SUPERSEDED

The controller-service design proposed extending that route. The UI's frozen
`/connections/{id}/probe | explore | sample` triad won, and the design predates the contract. Not a
rejection on merit — a document that aged past its subject.

### 5.5 A WRITE probe against a customer system — REFUSED

`WRITE` is deliberately `skipped` for `db`, `s3`, `gcs`, `azure` and `kafka`. A workbench must never
create a scratch object or publish a probe record into someone's production system. Only `local`
performs write-and-delete, where the blast radius is a temp file we own.

### 5.6 Edition-gating the connector sidecar — REFUSED

~32 MB, accepted. Remote acquisition is core capability, not a paid tier; gating it would make Basic
a demo rather than a product (2026-09-07).

### 5.7 "A connector is not a Step" — the standing answer to an apparent contradiction

`SP-ACQ-06` / `SP-ACQ-08` read 🟡 "no proven end-to-end acquisition-node run" while `ACQ-4` reads
FULLY CLOSED. Both are true and neither is stale: a **Connection kind** existing (tested connector,
real credentials, real listing) is a different claim from an **acquisition node** having been proven end
to end in a pipeline run. It is recorded here so the pair is not "reconciled" by flattening one of
them.

### 5.8 Other standing refusals, with their reasons

| Refused | Reason |
|---|---|
| A second Kafka lane for urgent topics | Urgency is a *parameter* on one node, not a second lane |
| The stream consumer as a long-running Job (STREAM-CONSUMER-1, 2026-09-24) | `JobService` models finite, non-overlapping Runs: a permanent Run holds one of the global in-flight slots forever, every later fire records `SKIPPED`, and a Job has no path to the pipeline's inbox, run guard or acquisition ledger, so it would duplicate the scheduler's wiring. A Run that never ends also makes Run history and duration meaningless |
| A Kafka slice named after its reached range, with the frontier derived from the name | The commit is source-agnostic and the ledger key is not in the name; a DB-export watermark (a timestamp with `:`) cannot live in a portable file name. One durable record serves both connectors |
| A watermark for the `dataset` connector | Snapshots are timestamp-named and marker dedup already gives re-ingest-on-refresh; do not build one on the assumption it is missing |
| Nested archives, and partial-archive failure | `depth: 1` is deliberate; a nested archive is an unbounded expansion in a jailed path |
| Extending the ingester SPI to emit files; "updating" a Consignment with expanded Entries | Unpack produces *files*, the wrap-SPI produces *records*; the manifest is immutable and `ManifestStore` has no merge; forcing an Archive's Entries into one Consignment would void the `max_files` / `max_bytes` memory bound |
| A two-tier logical-name scheme to stop the `report.csv` / `report.json` collision | It would break the requirement the mechanism exists for; the allow-list is the remedy |
| Crash-mid-archive exactly-once | Relies on `OVERWRITE_OR_IGNORE`; true exactly-once would need a per-entry ledger, which costs more than the failure |
| A per-entry Signal in Unpack; reusing the batch semaphore for Unpack | Unpack runs before planning, so the Consignment semaphore cannot bound it |
| Fetch-lane fairness | Stays FIFO until a real fetch-lane wait is observed |
| Vault / KMS **now** | Client policy, not code — the SEC-07 seam is already in place for when it lands |
| `PathJail` for the watermark store | A deliberate LEAVE: the store is engine-owned, not author-supplied, so the jail would guard nothing |
| `/metrics/acquisition` as an edition-gated cell | Core and ungated — an operator on any edition must see whether data is arriving |
| Bounds-based window pruning | Measured 1.3–2.6× faster for 29–88× fewer rows — the win did not survive contact with real cardinality |
| Merging the manual-run `runGuard` with the background `acquireGuard` | It would serialise ingest behind fetch again; the overlap it allows is benign |

⚠ **Do not carry forward** `BACKLOG.md` §6's "the TOON `source:` block stays". It is refuted:
`PipelineConfigParser` reads only `collector` (`parseCollector`), and no committed TOON carries a top-level
`source:`. Only `NodeCategory.SOURCE` survives, and that is an enum constant, not authored config.

## 6. Verification

### 6.1 Decision-logic and ledger unit tests — `platform/inspecto-acquire/src/test/java/com/gamma/acquire/`

25 test classes (24 here + `retry/RetryPolicyTest`) plus the `testkit/` connector-factory TCK. The table
lists the core ones; not listed: `AcquisitionLedgerPruneTest`, `DayManifestTest`,
`FileSequenceGapsBucketStartGoldenTest`, `StreamLaneTest`. `SecretResolverTest` is listed with them but lives
in `spi/inspecto-auth-spi`. The acquisition core is deliberately built as pure, injectable-clock decision
logic, so almost every invariant in the spec is provable without a network or a sleep.

| Class | Proves |
|---|---|
| `StabilityGateTest` | growing-file hold, release on quiescence, the `size_checks` gate, per-source isolation, gate shared **per space** |
| `DuplicatePolicyTest` | `Decision{NEW,DUPLICATE,CHANGED}` across `Mode{PATH,METADATA,CHECKSUM,ETAG}` incl. the etag→version→metadata fallback; `OnChange` reprocess/alert flags; `configStringsMapToEnums()` pins the config-string→enum mapping and the null/unknown defaults |
| `AcquisitionLedgerTest` | the ledger contract across in-memory **and** DuckDB backends, pre-etag schema migration, and a failed replace rolling back to the prior fingerprint |
| `AcquisitionLedgerWatermarkTest` | `highWatermark` = max(`lastModified`) per source, in-memory + DB, and the safe no-op default |
| `AcquisitionLedgersTest` | ledgers isolated per space (MDC routing) |
| `ChecksumsTest` | known hash vectors, SHA-256 default + change detection, unknown-algorithm failure |
| `IntegrityCheckerTest` | post-fetch size/etag verification, skip-when-no-size, quoted-etag checksum |
| `GapDetectorTest` | timestamp-template series: hourly hole, day rollover, monthly step, invalid-date skip |
| `GapTrackerTest` | the fire-once/refire-on-reopen state machine, per-source isolation, reset |
| `FileSequenceGapsTest` | the two-token windowed variant: interior hole, trailing hours, truncated tail, zero-padding, malformed template |
| `CircuitBreakerTest` | closed→open→half-open, threshold trip, cooldown, per-source isolation |
| `retry/RetryPolicyTest` | `NONE` runs once, retry-until-success, exhaust-and-rethrow, exponential doubling + clamp, linear backoff |
| `RateLimiterTest` | unlimited short-circuit, burst-then-throttle, refill over elapsed time |
| `IntakeGovernorTest` | the T15 contrast: hot policy apply, overrun halves, floor, hysteresis band, per-pipeline override incl. zero-exempt, hard-cap mode |
| `RetrievalPlannerTest` | the plan choice — backup vs. streamable vs. temp-staging |
| `PostActionTest` | `archive_path` date-token resolution |
| `ConnectionProfileTest` | field/tunnel/options load, secret masking in `toMap`, proxy-block parse+mask, **`everyFieldSurvivesAMapRoundTrip`** (the `base_path`/`basePath` data-loss guard) |
| `ConnectionTesterTest` | reachable/unreachable probe, local-profile no-network shortcut, missing host/port failure |
| `SecretResolverTest` (`spi/inspecto-auth-spi`) | all five forms — `recognisesReferences`, `resolvesSystemPropertyScope` (incl. bare-scope fallback), `missingEnvReferenceResolvesToNull`, `literalIsPassedThrough`, **`fileAndKeystoreSchemesAreRefusedNamingTheEditionWhenNoProviderIsBundled`** (the SEC-07 gate), `aBundledProviderServesTheSchemesItSupports` |
| `ConnectionRegistryTest` | register/find/remove by id, null-registration ignored, per-space isolation |
| `LocalConnectionWorkbenchTest` | probe/explore/sample, **path jailing**, CSV vs. raw-line fallback |
| `LocalFileSystemConnectorTest` | discover/open/fetchTo/post-action + ready-marker readiness |

### 6.2 Connector, workbench and proxy tests — `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/`

30 test classes — the per-scheme and workbench tests below, a `*FactoryTckTest` per factory (the
`testkit` contract of §6.1), `DbExportConnectorFactoryValidateTest`, `CollectorProcessorRemoteCycleTest` and
the export/egress tests (`ObjectStoreEgressTest`, `ObjectStoreExportJobTest`, `PinnedObjectStoreHttpTest`,
`SafetyPolicyEgressTest`) — plus 4 helpers (the 2 relay fixtures, `ObjectStoreEgressFixture`, `OutputRows`).
⚠ The `com/gamma/connect/notify/*` tests (six classes, two helpers) also live in this module but are a
**notification** subject, not ACQ. The Kafka tests (`KafkaConnectorTest`, `KafkaConnectionWorkbenchTest`,
`KafkaConnectorFactoryTckTest`, `KafkaStreamLaneTest`, `KafkaEgressTest`, `KafkaRealBrokerTest`) moved with the
connector to `providers/inspecto-connectors-kafka/src/test/java/com/gamma/acquire/kafka/`.

- Per-scheme: `SftpConnectorTest` (host-key pinning, discover/fetch/resume, dedup-on-rerun, parallel fetch,
  workbench, SOCKS5 + HTTP-CONNECT tunnelling) · `FtpConnectorTest` (+ proxy, unknown-proxy fail-closed) ·
  `FtpsConnectorTest` (TLS mode parsing, asserts `scheme()=="ftps"`) · `FtpTunnelConnectorTest` (bastion +
  passive-port-range parsing) · `S3ConnectorTest` · `AzureBlobConnectorTest` · `GcsConnectorTest` (each:
  discover w/ pagination + etag, depth bound, fetch, move+delete post-actions) · `KafkaConnectorTest`
  (Kafka module; offset-range discover, **ledger-watermark resume**, NDJSON envelope drain, raw-payload mode,
  non-RETAIN post-action rejection, the **in-flight fence** and its self-release, the lag gauge) ·
  `DbExportWatermarkCycleTest` (real poll cycles: frontier advances, **restart between land and commit**,
  fence across a restart) · `DbExportConnectorTest` (query→CSV, `:watermark` placeholder rewrite,
  incremental export **commits then advances**).
- Auth signing, which is what "SDK-free" rests on: `AwsSigV4Test` (vanilla vector, S3 sha256, RFC3986
  encoding) · `AzureSharedKeyTest` (string-to-sign, `x-ms-` headers, canonicalized resource).
- Workbenches: `S3ConnectionWorkbenchTest` · `AzureBlobConnectionWorkbenchTest` ·
  `GcsConnectionWorkbenchTest` (each an in-process JDK `HttpServer` stub per store) ·
  `DbConnectionWorkbenchTest` (schema/table/column explore, bounded sample + truncation flag) ·
  `KafkaConnectionWorkbenchTest` (Kafka module; topic→partition explore; **sample without touching the ledger
  watermark**).
- Proxy: `DbConnectionsProxyTest` — the JDBC half dialled through both relays at a bare listener, asserting
  the relay was asked for the database; unroutable shapes refused.
- Fixtures (not test classes): `MiniSocks5Relay.java`, `MiniHttpConnectRelay.java` — both multi-connection,
  because FTP opens a *second* passive data connection through the same factory; `MiniHttpConnectRelay`
  additionally records the auth header. 🔴 These relays are the only thing that caught the
  `createSocket()`-vs-`connect()` bypass of the HTTP proxy: the happy path still returned the right file.
- Test infrastructure declared in `providers/inspecto-connectors/pom.xml`: Apache MINA `sshd-core` + `sshd-sftp` and
  Apache `ftpserver-core` (all `<scope>test</scope>`) give **real in-process SSH/SFTP and FTP servers**;
  in the Kafka module, kafka-clients' in-jar `MockConsumer` means the suite needs no broker (only
  `KafkaRealBrokerTest` wants one, and skips without it); the ACQ test resource committed here is
  `providers/inspecto-connectors/src/test/resources/ftps-test-keystore.jks`.
- Slice frontier: `RemoteSliceFrontierRekeyTest` (engine), `RemoteSliceFrontierCommitTest` (inspecto: restart
  and fence against the scheduler's split timers), `DbExportWatermarkCycleTest` (connectors).

### 6.3 Unpack — `platform/inspecto-etl/src/test/java/com/gamma/etl/unpack/`

`UnpackStage`, `UnpackLimits`, `UnpackLedger`, `UnpackOrigins` tests; `CompressionTest.inlineVocabularyIsOwnedByPlugins`
(the one-way drift guard); the zip-slip jail pinned with a **re-packed** archive; the `EMPTY` vs `UNREADABLE`
`entriesFound()` split; `LogicalNamesTest` (the one permitted `ConfigSpecs` mirror of the default
`data_extensions`).

### 6.4 Control-plane real-HTTP route tests — `inspecto/src/test/java/com/gamma/control/`

Domain-primary (four):

| Class | Routes / gates |
|---|---|
| `ControlApiCollectorsAndConnectionsTest` | `GET /collectors`; `POST/GET/PUT/DELETE /connections[/{id}]` — create, duplicate id → **409**, delete-while-in-use → **409**, `POST /connections` → **503** when the write root is unavailable |
| `ControlApiCollectorNotifyTest` | `GET /collectors`; `POST /collectors/{id}/notify` → **202** + two 404-unknown cases; and `AuditTrail.classify("POST","/collectors/feed-a/notify")` — **this is the test that pins `collector.notified`** |
| `ControlApiConnectionsTest` | `GET /connections`, `GET/PUT/DELETE /connections/{id}` 404-unknown, `POST /connections/{id}/test` |
| `ControlApiConnectionProbeTest` | `POST /connections/{id}/probe`, `GET …/explore`, `GET …/sample` — **400/422/501/404** across local vs. unsupported-remote schemes |

Incidental (the route string is an example for another concern, but they do exercise the path):
`ControlApiVersionedSurfaceTest` (`/collectors` under `/api/v1`, `/api`, `/api/v2`) ·
`ApiContextV1DerivationTest` (v1 prefix derivation over `/collectors` variants) · `AuditTrailTest`
(`PUT /connections/{id}`, `POST /connections/{id}/test` classification) · `ControlApiBundleNewKindsTest`
(`POST /connections`, `GET /connections/{id}` as bundle-import kinds).

### 6.5 SPI contracts (`META-INF/services`)

| File | Providers |
|---|---|
| `providers/inspecto-connectors/src/main/resources/META-INF/services/com.gamma.acquire.CollectorConnectorFactory` | the **seven**: `SftpConnectorFactory`, `FtpConnectorFactory`, `FtpsConnectorFactory`, `DbExportConnectorFactory`, `S3ConnectorFactory`, `AzureBlobConnectorFactory`, `GcsConnectorFactory` (`KafkaConnectorFactory` is in `providers/inspecto-connectors-kafka/src/main/resources/META-INF/services/...`) |
| `platform/inspecto-engine/src/main/resources/META-INF/services/com.gamma.acquire.CollectorConnectorFactory` | `com.gamma.inspector.DatasetCollectorConnectorFactory` — the `dataset` scheme, from a different module |
| `providers/inspecto-secrets/src/main/resources/META-INF/services/com.gamma.auth.secrets.SecretsProvider` | `com.gamma.secrets.FileKeystoreSecretsProvider` — the SEC-07 edition seam |
| `inspecto/src/test/resources/META-INF/services/com.gamma.acquire.CollectorConnectorFactory` | `com.gamma.service.FakeRemoteConnectorFactory`, `com.gamma.service.FakeOffsetTailConnectorFactory` (test-only remote schemes) |

`platform/inspecto-engine/src/test/java/com/gamma/inspector/DatasetCollectorConnectorFactoryTest.theFactoryIsServiceLoaderDiscoverable()`
loads the SPI through `ServiceLoader`; so does the factory TCK (`testkit/CollectorConnectorFactoryContract`,
`schemeIsServedByExactlyOneFactory`), which every `*FactoryTckTest` runs.

### 6.6 Runnable examples

✅ **One committed example now exercises a non-local connector, and it was DRIVEN live 2026-09-15**
(`COLLECTOR-DATASET-UNPROVEN-1`, closed): `spaces/demo/config/orders/orders_by_region_feed_pipeline.toon` —
`collector: {connector: dataset, dataset: datasets/orders_by_region}` plus the `trigger: {type: event, on: dataset,
from: …}` half, `parsing.frontend: parquet`. On a multi-Space server (`-Dspaces.root=spaces`, Standard modules on
the classpath) `POST /spaces/demo/jobs/orders_by_region_materialize/trigger` → 202 → SUCCESS, 4 rows; the
`DatasetWriteSignal` fired the feed **in the same second** (batch `SUCCESS`, 4 rows, 1 partition, 0 rejected,
180 ms) and the Parquet landed under `spaces/demo/data/orders_by_region_feed/database/`. 🔴 Two things the run
found that no test had: (a) the committed materialize job was **broken** — `measures: "gross,sum"` is not the
measure grammar (`sum(gross)`), so the demo's own materialize had never run; (b) the demo space **does not boot on
the core jar at all** — `ops_analytics_sample_job.toon` declares `type: objects.analytics` (an `inspecto-ops` job
type) and `SpaceManager` SKIPPED the whole space with a WARN (`DEMO-SPACE-PERSONAL-UNBOOTABLE-1`, fixed
2026-09-15: a job of an unregistered type is now skipped at boot, not the Space — see
[jobs](../processor/spec-jobs.md)). ⚠ Before
the Dataset exists the feed's first poll fails loudly (`unknown dataset 'orders_by_region'`) — fail-fast, by
design; the registry component `orders_by_region.toon` is committed so a fresh checkout resolves it at boot.
⚠ Otherwise every runnable committed `*_pipeline.toon` uses `connector: local` (153 occurrences). The two
remote ones are the **`_reference/`** templates, which need external infrastructure and are not runnable
offline: `inspecto/examples/_reference/sftp-collector/orders_pipeline.toon` (`connector: sftp` with
`fetch` + `rate_limit`, `retry`, `stability`, `post_action`) and
`inspecto/examples/_reference/db-export/orders_pipeline.toon` (`connector: db` with `fetch`, `retry`). What is
runnable is the acquisition *semantics* layer over the local connector:

| Example | Exercises |
|---|---|
| `inspecto/examples/05-acquisition/gap-detection/pipeline.toon` | `collector.gap_detection` |
| `inspecto/examples/05-acquisition/dedup-rerun/pipeline.toon` | ⚠ **not** an acquisition key — its dedup is `processing.duplicate_check` |
| `inspecto/examples/05-acquisition/intake-throttle/pipeline.toon` | the per-cycle intake cap, `processing.intake { max_files_per_cycle }` |
| `inspecto/examples/06-serve/sequence-gap/feed_pipeline.toon` | `collector.gap_detection` under the serve runner |
| `inspecto/examples/06-serve/checksum-change/orders_pipeline.toon` | `collector.duplicate { mode: checksum, algorithm: SHA256, on_change: alert }` |
| `inspecto/examples/06-serve/incremental-watermark/feed_pipeline.toon` | `collector.duplicate { mode: metadata }` + `collector.incremental { watermark: last_modified }` |
| `inspecto/examples/07-steps/collect/pipeline.toon` | `connector`, `discovery: poll`, `include[]`, `exclude[]`, `recursive_depth`, `gap_detection` (mirrored at `spaces/default/config/collect_step/collect_step_pipeline.toon`) |
| `spaces/ucc/config/voucher/voucher_pipeline.toon` | the only runnable **`collector.stability`** (the other is in `_reference/sftp-collector`) — `window: 10s`, `size_checks: 2` — and `collector.consignment { max_files, max_bytes }` |

Runners: `inspecto/examples/run-example.ps1` / `.sh` (batch) and `serve-example.ps1` / `.sh` (serve mode —
required for gap/fingerprint/watermark/stability, which need more than one cycle; the `phase2/` two-cycle
protocol and `mtimes.txt`). Self-checking `probes.txt` assertions exist for the three `06-serve/*` examples
above and **not** for any of the three `05-acquisition/*`.

Connection profiles — six committed. Three sit beside the `_reference/` pipelines that bind them
(`_reference/sftp-collector/warehouse_sftp_connection.toon`, `_reference/db-export/orders_db_connection.toon`,
`_reference/webhook-sink/orders_hook_connection.toon`). The other three exercise the Connections API surface
rather than collection (no pipeline references any of them): `spaces/default/config/connections/local_demo_connection.toon`
(`sftp`, `${ENV:DEMO_SFTP_PASSWORD}`; ⚠ `host: 127.0.0.1 port: 8080` reads as a probe fixture aimed at the
control-plane port, not a working SFTP endpoint) · `spaces/demo/config/connections/demo_local_connection.toon`
(`local`, the only one that resolves offline) · `spaces/demo/config/connections/warehouse_sftp_connection.toon`
(`sftp`, `${ENV:WAREHOUSE_SFTP_PASSWORD}`; `spaces/demo/config/README.md` labels it "reference shape").
**No committed `.toon` anywhere contains a `tunnel:` or `proxy:` block** — those shapes live only in
`connectors-runbook.md`, `ConnectionProfileTest`'s inline TOON, and the UI's `connection-form.dialog.ts`.

### 6.7 Guards

- `tools/check-secrets.mjs` — the ACQ-relevant guard: it exists so a credential cannot be reintroduced as a
  literal, and it explicitly blesses `${ENV:…}` as *the* sanctioned way not to hold a secret. Two modes,
  because tree-scan alone hands out a false green when a secret is committed and moved one commit later;
  `.githooks/pre-push` runs both. Wired into `ci.yml`.
- `tools/check-vocabulary.mjs` — rule `source-acquisition-entity` enforces Collector over Source. <!-- vocab-allow: names the guard rule by id -->
  ✅ The path-keyed `DOC_ALLOW` waiver for the old `integrations.md` H1 was deleted 2026-09-08 when the
  connector runbook was split out; the guard's self-retirement rule would otherwise have failed the build. The
  guard's comments still record the identical error class fixed once before — a documented `GET /sources`
  that is really `GET /collectors`.
- `tools/check-doc-links.mjs` — wired into `.githooks/pre-push` + `ci.yml`; any ACQ file move must keep the
  link baseline at zero dangling.
- `tools/check-coverage.mjs` — ⚠ the floors are **repo-wide, not per-module** (its header comment), so there is no
  `inspecto-acquire` floor to cite; do not claim one.

### 6.8 Named coverage gaps (verified absent, not assumed)

1. 🔴 **No test asserts the registered-scheme set.** The number seven is only the line count of the
   `inspecto-connectors` services file; `FtpsConnectorTest` and
   `DatasetCollectorConnectorFactoryTest.theFactoryIsServiceLoaderDiscoverable` each assert a single scheme,
   and the factory TCK checks that a scheme has *at most* one factory, not that it is registered. Nothing
   counts or lists them together, so a dropped `META-INF/services` line would ship silently — the exact
   failure mode of the palette-fallback incident.
2. 🔴 **No test feeds a raw `ssh-rsa AAAA…` line into `host_key`.** `SftpConnectorTest` covers
   `wrongHostKeyFingerprintRejectsConnect()` (a bogus colon-hex fingerprint) and
   `strictHostKeyWithoutAnyPinRefusesConnect()`. The rejection of a raw key line follows from
   `HostKeyPolicy` handing the string to sshj's fingerprint verifier — it is code-supported by
   construction, but **not covered by a test with that input**, so state it as a design property, never as
   a tested one.
3. The three `05-acquisition/*` examples (`gap-detection`, `dedup-rerun`, `intake-throttle`) have no
   `probes.txt`, so they are demonstrable but not self-checking.
4. Features needing external infra (SFTP, a database, an https receiver, a DuckLake catalog) now ship as
   `inspecto/examples/_reference/` templates (`inspecto/examples/README.md`, *Reference templates*); they are
   reference shapes, not runnable offline, so no committed example *drives* a remote fetch (§6.6).
5. `.Z` has no round-trip test; xz / zstd have no codec to test (`BACKLOG.md` §3).

## References

| Mechanism | Owning file |
|---|---|
| The original requirement, the engine as built, the Unpack stage | [spec.md](spec.md) |
| Poll cycle and phases A–F summary, the Collectors module page, multi-location ingest | [architecture.md](architecture.md) |
| Screens and the Collector configuration surface | [user-guide.md](user-guide.md) |
| Connector SPI, remote schemes, profiles, secrets, workbench, proxy chain | [connectors/spec-connectors.md](../connectors/spec-connectors.md) |
| Operator runbook: profile YAML, host-key table, FTPS, bastion, db-export | [connectors/admin-guide-connectors-runbook.md](../connectors/admin-guide-connectors-runbook.md) |
| Module boundary + dependency confinement | [connectors/architecture.md](../connectors/architecture.md) |
| What happens after the inbox | [ETL plan](../elt/plan.md) · [engine plan](../engine/plan.md) |
