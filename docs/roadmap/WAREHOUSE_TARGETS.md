# Inspecto as the feeder — warehouse and lake targets

> **Status: feature doc, 2026-09-15.** Where Inspecto is useful as the ETL/ELT tool that *populates* a
> central warehouse, lakehouse or analytical database — as opposed to being the analytical store itself.
> Every row carries a status token grounded in the tree on the date above; the matrix is a **roadmap**, not a
> capability claim. Companion to [`../okf/capabilities/data-plane/data-plane.md`](../okf/capabilities/data-plane/data-plane.md)
> (what the lakehouse is today) and [`../stakeholders/COMPETITIVE_LANDSCAPE.md`](../stakeholders/COMPETITIVE_LANDSCAPE.md)
> §4 (what may be claimed). Vocabulary per [`../GLOSSARY.md`](../GLOSSARY.md).

## 1. The proposition

Regulated estates already own a warehouse. What they lack is a trustworthy way to get operational files —
CDRs, ledgers, partner feeds, device logs — *into* it: parsed, deduplicated, reconciled, with a lineage trail
and a quality gate that stops a broken feed before it lands. That is the part of the pipeline Inspecto is
built for, and the part warehouse vendors leave to "your ETL tool".

```
   sovereign edge / on-prem                                        central analytics
 ┌───────────────────────────────────────────────┐              ┌───────────────────────────┐
 │  INSPECTO                                     │   Parquet    │  warehouse / lakehouse    │
 │  acquire → parse → dedup → gap-check →        │ ───────────► │  Snowflake · BigQuery ·   │
 │  reconcile → Breaks → Signals → lineage       │   or SQL     │  Databricks · Iceberg ·   │
 │  = the QUALITY GATE the warehouse does not have│ ───────────► │  Redshift · ClickHouse …  │
 └───────────────────────────────────────────────┘              └───────────────────────────┘
      data that cannot leave stays; only what passes the gate moves, and only the columns allowed to
```

Three things make Inspecto a good feeder rather than one more connector:

* **The gate is before the load.** A Break, a `SEQUENCE_GAP`, a schema drift or a quarantine happens on the
  edge node; the warehouse receives only committed, reconciled Datasets. Most ELT tools load first and let the
  warehouse discover the problem.
* **Parquet is the universal currency.** Every warehouse and every lake engine on the list below ingests
  Parquet from object storage natively. Inspecto already writes partitioned Parquet, locally and to an object
  store, so the first delivery mechanism needs no code in the target's dialect.
* **Sovereignty by construction.** The edge node runs air-gapped; the outbound hop is the *only* egress, it is
  a configured Collector-shaped job with its own Signals, and its credentials resolve through `SecretsProvider`.

## 2. What exists today (grounded 2026-09-15)

| Capability | Status | Where |
|---|---|---|
| Partitioned Parquet / CSV write to a local write-root, atomic reveal | ✅ built | `PartitionWriter`, `output:` block |
| Partitioned Parquet write to an **object store (`s3://`)**, no staging, MinIO-tested | ✅ built 2026-09-14 | `PartitionWriter` object-store lane (`d87b7890`) |
| DuckLake catalog on PostgreSQL — cross-node visibility of committed files | ✅ built | `DuckLakeRegistrar`, scale-out phases A/B |
| Several destinations per pipeline (`sinks:`) | 🟡 config authorable, **executor not wired** | archived `sinks-config-format-plan.md`; `PipelineConfig.Sink` javadoc |
| Outbound object-storage export as a scheduled job | 🟡 intent; sequence of record is an operator `aws s3 sync` | `EXPORT-1` (BACKLOG §3, P3), `object-storage-export.md` |
| JDBC **read** from any database | ✅ built | JDBC Collector |
| JDBC / SQL **write** to a database | ⛔ not built | no `JdbcSink` exists |
| Iceberg / Delta table **write** through a catalog | ⛔ not built; DuckDB supports both (§4) | extension staging: `tools/fetch-duckdb-extensions.mjs` |
| Streaming or NoSQL outbound | ⛔ not built | would be a `CollectorConnectorFactory`-style plugin |
| PostgreSQL as a query surface **over** Inspecto's lake (`pg_duckdb`) | 📐 signed design D13, no code | the reverse direction — the warehouse reads us |

## 3. Four delivery mechanisms

Everything in §5 resolves to one of these. Build the mechanism once; a target is then a configuration.

| # | Mechanism | How the target receives data | What Inspecto needs | Status |
|---|---|---|---|---|
| **M1** | **Parquet drop + target-side load** | Inspecto writes Hive-partitioned Parquet to the target's object store; the target loads it with its own bulk primitive (`COPY INTO`, `LOAD DATA`, external/foreign table, `s3()` function) or reads it in place | object-store write ✅ · scheduled outbound job (`EXPORT-1`) 🟡 · a post-write hook that issues the load statement ⛔ | **nearest** — works today by runbook |
| **M2** | **Open table-format write through a catalog** | Inspecto commits new **Iceberg** or **Delta** snapshots via DuckDB's extensions (Iceberg REST catalog; Delta incl. Unity Catalog), or registers in **DuckLake** | stage `iceberg`/`delta`/`httpfs` extensions for air-gap · a `sinks:` kind per format · credentials via `SecretsProvider` | ⛔ build; DuckDB side is ready |
| **M3** | **Direct SQL sink** | Inspecto inserts into the target over JDBC, or uses DuckDB's `postgres`/`mysql` extensions to write natively | a `JdbcSink` (batched `INSERT`/`MERGE`, idempotent by run id) · the `sinks:` executor | ⛔ build |
| **M4** | **Connector plugin** | A jar implementing an outbound connector pages Datasets into a stream or document store | plugin lane (`CollectorConnectorFactory` shape, outbound) | 🔧 per target |

**Idempotency is the design constraint that crosses all four.** Locally a re-run overwrites; on an object
store a re-run **accumulates** unless the key is pinned (`PartitionWriter` javadoc, 2026-09-14); in a SQL target
it duplicates unless the sink keys on the run and merges. Every mechanism must carry the run identity into
the target — as the object key, the snapshot property, or the merge key.

## 4. Open table formats — where DuckDB already does the work

* **Iceberg.** DuckDB's `iceberg` extension writes (`CREATE TABLE`, `INSERT`, `UPDATE`, `DELETE`) when attached
  to an Iceberg **REST catalog**; path-based scans stay read-only. That covers AWS Glue, Snowflake Open
  Catalog / Polaris, Nessie, Tabular-style services and Databricks Unity's Iceberg REST endpoint.
* **Delta.** The `delta` extension gained **writes and time travel** in 2026 and speaks to Unity Catalog. ⚠ A
  known gap: writes to some Databricks-*managed* tables fail on a writer-compat feature flag (duckdb-delta
  #289) — external Delta locations are the safe target until that closes.
* **DuckLake.** Already Inspecto's own catalog; a customer running DuckLake on their Postgres can be fed by
  registration alone.

Because the write path is inside the engine Inspecto already embeds, M2 is a **packaging and configuration**
job (stage the extensions, add the sink kind, wire credentials), not an integration project.

## 5. Target matrix

Status tokens: ✅ works today by configuration + runbook · 🟡 works with `EXPORT-1` scheduled export · 🔧 needs
M2/M3 built (configuration once built) · 🧩 plugin (M4). "Edition" is where the outbound job would sit.

### 5.1 Cloud data warehouses

| Target | Mechanism | Load path | Status | Notes |
|---|---|---|---|---|
| **Snowflake** | M1 → `COPY INTO` from an external stage; or M2 Iceberg via Snowflake Open Catalog | Parquet on S3/GCS/Azure → stage → `COPY INTO` / Snowpipe auto-ingest | ✅ M1 · 🔧 M2 | Snowpipe watching the bucket turns M1 into near-real-time with zero Snowflake code |
| **Google BigQuery** | M1 → `LOAD DATA` / external table over GCS; BigLake Iceberg | Parquet on GCS | ✅ M1 · 🔧 M2 | Hive partition layout is understood natively |
| **Amazon Redshift** | M1 → `COPY` from S3, or Spectrum external table | Parquet on S3 | ✅ M1 | Spectrum reads the Parquet in place |
| **Databricks SQL / Lakehouse** | M2 Delta (Unity) or M1 → `COPY INTO` | Delta commit, or Parquet on the workspace's storage | 🔧 M2 · ✅ M1 | see §4 managed-table caveat |
| **Microsoft Fabric Warehouse / OneLake · Azure Synapse** | M1 → `COPY INTO` / OneLake shortcut; M2 Delta | Parquet or Delta on ADLS Gen2 / OneLake | ✅ M1 · 🔧 M2 | OneLake is Delta-native; M2 makes tables appear without a load step |
| **Firebolt · Teradata VantageCloud · Oracle ADW** | M1 → external table / `COPY` | Parquet on the cloud's object store | ✅ M1 | vendor-specific load DDL in the runbook |

### 5.2 Lakehouses and catalogs

| Target | Mechanism | Status | Notes |
|---|---|---|---|
| **Apache Iceberg** via AWS Glue · Polaris · Nessie · Unity REST | M2 | 🔧 | the single highest-leverage build: one sink kind covers every Iceberg catalog |
| **Delta Lake** (external locations, Unity) | M2 | 🔧 | writes + time travel available in DuckDB (2026) |
| **DuckLake** | M2 (registration) | ✅ | Inspecto's own catalog; zero new code |
| **Apache Hudi** | M1 | 🟡 | no DuckDB writer; Hudi ingests Parquet via its own tooling |
| **Hive Metastore / HDFS** | M1 → `hdfs://` or S3-compatible gateway; `MSCK REPAIR` / `ALTER TABLE ADD PARTITION` | 🟡 | `EXPORT-1` names HDFS explicitly |
| **Dremio · Starburst Galaxy** | M2 Iceberg or M1 external | 🔧 / ✅ | both read Iceberg and raw Parquet |

### 5.3 Query engines over object storage

| Target | Mechanism | Status | Notes |
|---|---|---|---|
| **Amazon Athena · Trino · Presto · Apache Spark** | M1 (Parquet in place) or M2 Iceberg | ✅ / 🔧 | Hive partitioning gives partition pruning for free |

### 5.4 Real-time OLAP databases

| Target | Mechanism | Status | Notes |
|---|---|---|---|
| **ClickHouse** | M1 → `INSERT … SELECT FROM s3()`; or M3 JDBC | ✅ M1 · 🔧 M3 | `s3()` table function reads Parquet directly |
| **StarRocks · Apache Doris** | M1 → `FILES()` / Broker load; M3 MySQL-protocol via DuckDB `mysql` ext | ✅ M1 · 🔧 M3 | |
| **Apache Druid · Apache Pinot** | M1 → native batch ingestion spec over Parquet | 🟡 | ingestion spec generated from the Dataset schema is a natural M1 hook |

### 5.5 Relational and MPP databases

| Target | Mechanism | Status | Notes |
|---|---|---|---|
| **PostgreSQL** (incl. Citus, Timescale) | M3 via DuckDB `postgres` extension (native `INSERT`), or JDBC | 🔧 | DuckDB writes Postgres natively — fastest M3 |
| **MySQL / MariaDB** | M3 via DuckDB `mysql` extension, or JDBC | 🔧 | |
| **Oracle · SQL Server · IBM Db2** | M3 JDBC; or M1 → external tables over object store (Oracle `DBMS_CLOUD`, SQL Server PolyBase) | 🔧 / ✅ | |
| **Teradata · Vertica · Greenplum · Exasol · SAP HANA** | M3 JDBC bulk; M1 external tables where offered | 🔧 / ✅ | Vertica and Greenplum read Parquet external tables directly |
| **SQLite / DuckDB files** | M3 native | 🔧 | edge-to-edge hand-off, no server |

### 5.6 Streaming, search and document stores

| Target | Mechanism | Status | Notes |
|---|---|---|---|
| **Apache Kafka · Redpanda** | M4 outbound connector (Dataset → topic, one record per row) | 🧩 | pairs with an inbound Kafka Collector plugin |
| **Elasticsearch / OpenSearch** | M4 bulk API | 🧩 | investigation results (Link Analysis suspicion lists) are the natural payload |
| **MongoDB** | M4 bulk write, or M1 → `mongoimport` of JSON lines | 🧩 / 🟡 | JSON lines is already an output the JSON frontend round-trips |

## 6. What to build, in order

1. **`EXPORT-1` as a real job** — scheduled outbound export with its own Signals, run-keyed object names, and
   a post-write **load hook** that issues the target's statement (`COPY INTO`, `MSCK REPAIR`, ClickHouse
   `INSERT … s3()`). Turns every ✅ M1 row from runbook into feature. Effort **S–M**.
2. **Stage `iceberg`, `delta`, `httpfs` extensions** into the air-gap set and prove loadable (the same shape as
   `AIRGAP-EXTENSIONS-1`; ⚠ staged ≠ loadable). Effort **S**.
3. **`sinks:` executor** — the archived plan's Stage A step 3; unblocks more than one destination per pipeline.
   Effort **M**.
4. **Iceberg sink kind** (M2) — one build, every catalog in §5.2. Effort **M**. Then Delta.
5. **`JdbcSink`** (M3) — batched, run-keyed `MERGE`; DuckDB-native for Postgres/MySQL. Effort **M**.
6. **Outbound connector SPI** (M4) — Kafka first. Effort **M**, plugin lane thereafter.

None of this changes the edge story: Personal and Standard keep zero external runtime services; the warehouse
is the *customer's* system, reached only by the outbound job.

## 7. What must not be claimed yet

* "Inspecto loads into Snowflake / BigQuery / Redshift" — true only as *Parquet drop plus your load statement*
  until step 1 ships. Say "lands Parquet your warehouse loads natively".
* "Iceberg / Delta writer" — DuckDB has it; Inspecto has not staged or wired it (steps 2 and 4).
* "JDBC sink" — read is built, write is not.
* Any row here as a product feature in [`../stakeholders/INSPECTO_ENTERPRISE_WHITEPAPER.md`](../stakeholders/INSPECTO_ENTERPRISE_WHITEPAPER.md)
  without the matching §4.1 row in the landscape register.

## 8. References

* DuckDB — [Lakehouse formats](https://duckdb.org/docs/current/lakehouse_formats) ·
  [Writing to Iceberg](https://duckdb.org/docs/current/core_extensions/iceberg/writing) ·
  [Iceberg extension](https://duckdb.org/docs/lts/core_extensions/iceberg/overview) ·
  [Delta extension](https://duckdb.org/docs/current/core_extensions/delta) ·
  [Delta grows up: writes, Unity Catalog and time travel (2026-05-07)](https://duckdb.org/2026/05/07/delta-uc-updates) ·
  [duckdb-delta #289 — managed-table write failure](https://github.com/duckdb/duckdb-delta/issues/289)
* In-repo — `PartitionWriter` object-store lane (`d87b7890`, 2026-09-14) · `EXPORT-1` (BACKLOG §3) ·
  `docs/archived-documents/plans-archive/sinks-config-format-plan.md` · `docs/okf/backend/engine/object-storage-export.md` ·
  `tools/fetch-duckdb-extensions.mjs` (the staged extension set)
