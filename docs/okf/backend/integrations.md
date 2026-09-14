---
type: Reference
title: DuckLake & the warehouse query layer
description: Registering pipeline output in a DuckLake catalog, and the warehouse query layer that exposes it to DBeaver and other SQL clients through pg_duckdb.
resource: inspecto-etl/src/main/java/com/gamma/etl/DuckLakeRegistrar.java
tags: [integrations, ducklake, warehouse, pg-duckdb]
timestamp: 2026-07-16T00:00:00Z
---

# DuckLake & Warehouse Query Layer
> **Deep reference — the detail tier.** Start at [Backend section index](index.md) for the summary; this page is the long form it points to. *(Moved from the retired root-level `integrations.md` (docs consolidation, 2026-07-16). Retitled 2026-09-08: the remote-connector runbook that used to open this page is now [`acquisition/connectors-runbook.md`](acquisition/connectors-runbook.md); this file keeps only the warehouse subject its inbound links target.)*

> Part of the [Inspecto](../../../inspecto/README.md) documentation. See the [docs index](../INDEX.md).

## DuckLake Integration

DuckLake is a lakehouse format that uses a SQL database (PostgreSQL) as the catalog/metadata store, with data stored as Parquet files. This lets remote clients query the data using standard DuckDB tooling.

### Setup

1. **Enable PostgreSQL** on the server and create a database for the catalog:
   ```sql
   CREATE DATABASE ducklake_db;
   ```

2. **Configure the pipeline** (`<data_source>_pipeline.toon`):
   ```yaml
   output:
     format: PARQUET
     compression: snappy
     ducklake:
       enabled: true
       # ✅ MEASURED WORKING 2026-09-14 (AIRGAP-DUCKLAKE-PG-1 CLOSED), duckdb_jdbc 1.5.2.1 against a
       # live Postgres: DuckLake metadata tables were created IN the Postgres database and a second,
       # independent connection read the row back through the catalog.
       # ⛔ The backend prefix is "postgres:" followed by LIBPQ KEYWORDS — not a URL. A
       # postgresql:// or postgres:// URL carries no recognised backend prefix, so DuckLake reads
       # the whole string as a FILE PATH; that is what this line used to say and it never worked.
       # 🔴 And a value with NO prefix does not fail either — it silently creates a LOCAL DuckDB
       # file catalog named after the whole string, which on several nodes means each one quietly
       # gets its own private catalog. DuckLakeRegistrar.requireSharedCatalog refuses that when
       # -Dinspecto.topology=partitioned; on a single node a file catalog is correct and allowed.
       catalog_url: "postgres:dbname=ducklake_db host=localhost port=5432 user=etl_user password=password"
       data_path: "/opt/adj-lake"
       schema: <data_source>s
       table: <data_source>_data
   ```

3. **Run the ETL.** After each file is written, `DuckLakeRegistrar.register` (called from `ConsignmentIngestor`) will:
   - load the `ducklake` extension: cached `LOAD` → the file staged by `package.ps1` under
     `-Dduckdb.extension.dir` → `INSTALL` (the only step that downloads, and the last one tried —
     `AIRGAP-EXTENSIONS-1`, 2026-09-11; it used to be an unconditional `INSTALL ducklake FROM core`)
   - `ATTACH` the PostgreSQL catalog
   - Create the schema and table if they do not exist
   - `INSERT INTO` the DuckLake table by reading the just-written Parquet files

   On a **single-node** deployment (the default, and every Personal install) DuckLake registration is an
   **optional, non-fatal sidecar** — if it fails, the file is still marked processed and the failure is
   logged at WARN, and the Parquet output on disk is unaffected.

   ⛔ **Under `-Dinspecto.topology=partitioned` it is neither optional nor non-fatal.** Two rules apply,
   for one reason: several processes share this state, so Parquet that reaches no catalog is Parquet no
   other node can see, and a batch that produces invisible output must not report success.
   - **A failure is FATAL** (D10, 2026-09-14) — the batch fails rather than logging a warning.
   - **Registration is MANDATORY** (2026-09-14) — `enabled: false`, or no `output.ducklake` block at all,
     fails the batch too. D10 closed the path where registration *fails*; this closes the path where it is
     never *attempted*.
   - **The catalog must be a shared server**, not a local file. ⛔ A value with no backend prefix does not
     error, it quietly creates a **private file catalog** for that node, so it is refused on shape before
     the attach.

   ⚠ **Two registration sites, and that is the ceiling.** `ConsignmentIngestor.finalizeSource` serves
   **both** the flat and the branch-aware graph ingest lanes — they share one tail — and
   `PipelineJobRunner.registerInLakehouse` serves the at-rest pipeline-job lane (`job: type: pipeline`),
   which has no tail of its own. ⛔ A third site would risk registering the same files twice, which is what
   `DuckLakeRegistrationSiteContractTest` bounds.
   ✅ The pipeline-job lane registered **nothing** until 2026-09-14 (`DUCKLAKE-GRAPH-LANE-1` — an id that is
   a misnomer, filed believing the graph lane was the gap and corrected the same day). It takes its catalog
   from `-Dinspecto.ducklake.catalog` below rather than from `output.ducklake`, because an authored pipeline
   has no pipeline config at run time; its table is the sink's **store** name.

4. **Read every node's slices** — set the deployment's shared catalog and any query can reach the whole
   lakehouse, not just what this node wrote:

   ```bash
   -Dinspecto.ducklake.catalog="postgres:dbname=ducklake_db host=db port=5432 user=etl_user password=..."
   -Dinspecto.ducklake.data="/mnt/lake"
   ```

   Both or neither: a catalog with no data path cannot be attached and a data path with no catalog names
   nothing, so half the pair is refused at the point of use rather than left to return short results.
   Unset is the normal single-node case and changes nothing.

   The catalog is attached as **`lake`**, so a query reaches it as `lake.<schema>.<table>` — from
   `/bi/query` and the Query Library alike. **Visibility is the catalog commit**: a slice appears exactly
   when its registering transaction committed, never half-written. ⚠ It can only show what the write side
   registered — which, since 2026-09-14, is every lane.

### Remote access via DBeaver

Each remote user installs the **DuckDB JDBC driver** in DBeaver and connects using the ducklake extension pointed at the same PostgreSQL catalog. Parquet files must be on a path accessible from the client (network share / NFS mount).

```sql
-- In a DBeaver DuckDB connection
INSTALL ducklake FROM core;
LOAD ducklake;
-- The backend prefix is postgres: + libpq KEYWORDS. A postgresql:// or postgres:// URL is read
-- as a FILE PATH by DuckLake and does not work (measured 2026-09-14).
ATTACH 'ducklake:postgres:dbname=ducklake_db host=server port=5432 user=user password=password'
    AS lake (DATA_PATH '/mnt/adj-lake');

SELECT * FROM lake.<data_source>s.<data_source>_data
WHERE year = '2000' AND month = '01'
LIMIT 100;
```

---

## Warehouse Query Layer — DBeaver via pg_duckdb

> ⚠ **Operator runbook, not a product feature** (banner added 2026-09-08). Everything below runs on a *customer's*
> PostgreSQL: install `pg_duckdb`, run the bundled `warehouse_setup.sql` (repo root, outside the Maven build), create
> roles and views by hand. **No `pg_duckdb` code exists in this repo and nothing tests this layer.** The DuckLake
> registrar above registers already-written local Parquet paths — bytes never move. Requirement of record:
> [DAT capability spec](../capabilities/data-plane/data-plane.md) §3.10.

Parquet output can be queried directly from DBeaver (or any PostgreSQL client) without loading data into PostgreSQL. The `pg_duckdb` extension embeds DuckDB inside PostgreSQL as a transparent execution engine — users connect with a standard PostgreSQL driver and DuckDB is invisible to them.

```
DBeaver (laptop)  →  PostgreSQL :5432  →  pg_duckdb extension  →  database/**/*.parquet
```

No data is copied into PostgreSQL. PostgreSQL handles only the wire protocol; DuckDB does all I/O and vectorised execution against the Parquet files on disk.

### One-time server setup

**1. Install pg_duckdb on the Linux server**

```bash
# PostgreSQL 16 example — replace version number as needed
apt-get install -y postgresql-16-pgduckdb

# Enable the extension (requires a PostgreSQL restart)
psql -U postgres -c "ALTER SYSTEM SET shared_preload_libraries = 'pg_duckdb';"
sudo systemctl restart postgresql
```

**2. Apply `warehouse_setup.sql`**

```bash
# From the bundle root — substitute your actual data path
export DATA_ROOT=/opt/ura/sandbox
sed "s|DATA_ROOT|${DATA_ROOT}|g" warehouse_setup.sql > warehouse_setup_final.sql
psql -U postgres -d yourdb -f warehouse_setup_final.sql
```

**3. Create login accounts** (edit the commented block at the bottom of the file):

```sql
CREATE USER alice WITH PASSWORD 'changeme' IN ROLE analyst;
CREATE USER bob   WITH PASSWORD 'changeme' IN ROLE <data_source>_analyst;
```

### Views in the `warehouse` schema

| View | Source path | Columns | Partition key |
|---|---|---|---|
| `<data_source>_cdr` | `database/<data_source>/<data_source>_cdr/**` | 537 | EVENT_DATE (extracted from filename) |
| `<data_source>_main` | `database/<data_source>/<data_source>_main/**` | 116 | TRANSACTION_START_DATE |
| `<data_source>_other` | `database/<data_source>/<data_source>_other/**` | 76 | TRANSACTION_START_DATE |
| `<data_source>` | `database/<data_source>/**` | 477 | REVERSAL_DATE |
| `<data_source>_all` | union of all 3 <data_source> views | common cols | — |
| `data_catalog` | partition summary across all tables | — | — |

### Roles

| Role | Access |
|---|---|
| `analyst` | all warehouse views |
| `<data_source>_analyst` | <data_source>_cdr, <data_source>_main, <data_source>_other, <data_source>_all |
| `<data_source>_analyst` | <data_source> only |

### DBeaver connection

Use the standard **PostgreSQL** driver. No special configuration needed.

| Field | Value |
|---|---|
| Host | `your-linux-server` |
| Port | `5432` |
| Database | `yourdb` |
| Driver | PostgreSQL |

Partition pruning is automatic — DuckDB reads only the files that match the `WHERE` predicates:

```sql
-- Check what data has landed across all tables
SELECT * FROM warehouse.data_catalog ORDER BY table_name, year, month, day;

-- Query with partition pruning (reads only year=2020/month=01/day=01 files)
SELECT * FROM warehouse.<data_source>_cdr
WHERE year = 2020 AND month = 1 AND day = 1
LIMIT 100;
```

---

