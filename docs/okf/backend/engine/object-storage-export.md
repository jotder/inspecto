# Object-storage export (S3 / HDFS) — posture and options

**Status:** DISCUSSED 2026-08-28, not scheduled — the operator asked for the analysis to be kept
for later work. The schedulable row is **EXPORT-1** in [`BACKLOG.md`](../../../BACKLOG.md) §4.
**Related:** [dataset consumption / the `dataset` connector](../../../superpower/elt-final-amendment-plan.md)
(S3c, the inbound mirror of this question) · [connectors](../acquisition/connectors.md) ·
[operations reference](../build-run/operations-reference.md).

## The grounded facts (2026-08-28)

1. **Nothing pushes outbound today.** Every connector in `inspecto-connectors` (S3, GCS, Azure,
   SFTP, FTP, Kafka, DB-export) is *acquisition-side* — they fetch **in**. Outputs are local
   parquet written by `PartitionWriter`.
2. **`output.ducklake` registers, it does not relocate.** `DuckLakeRegistrar` ATTACHes a DuckLake
   catalog (`DATA_PATH` from config) and registers already-written *local* paths — bytes stay put.
3. **The engine is deliberately local-filesystem-shaped.** Atomic temp+rename writes
   (`AtomicFiles`), marker files, path jails, DuckDB file databases, config-registry directory
   scans, poll-dir semantics. S3 has no atomic rename and no real directories, so a whole *space*
   on S3 (natively or via a FUSE mount) silently changes the meaning of the crash-safe commit
   ordering. **Posture: space (config + working state) local; data tier exported.**
4. Reusable plumbing already in-repo for an outbound build: `AwsSigV4`,
   `AbstractHttpObjectStoreConnector`, and the object-store workbench family.

## Options, by ambition

| Option | Effort | What it gets |
|---|---|---|
| **Operator sync** — `aws s3 sync` / rclone of `data/<store>/database/` on a schedule | zero code, works today | outputs mirrored to S3; consumers read via DuckDB `httpfs` |
| **Push post-action / upload task** — an outbound mirror of the connector SPI: a post-commit uploader (job task or sink option) reusing `AwsSigV4` + the object-store HTTP plumbing | small–medium build | product-managed, per-pipeline "also deliver committed outputs to `s3://…`", audited |
| **DuckLake `DATA_PATH 's3://…'`** — DuckDB writes parquet straight to S3 via `httpfs` | medium — a real re-plumb: `PartitionWriter` owns the local write + swap today | the data tier *lives* on S3, catalog-managed; the space stays local |
| **HDFS directly** | the expensive one — DuckDB has no HDFS support; `hadoop-client` is a huge dependency tree colliding with the lean-SBOM / framework-free non-negotiable; WebHDFS-over-REST is buildable like the other HTTP connectors but only if mandated | — |

## Recommendation of record

If the Hadoop cluster exposes an **S3-compatible gateway** (Ozone S3, MinIO, or the org's object
store), everything reduces to the S3 column — reuse `AwsSigV4`, never take a Hadoop dependency.
Sequence: **operator sync first** to prove the consumption pattern, then build the **push
post-action** only if it earns a place. The full-space-on-S3 idea is recommended *against* for
the semantic reasons in fact 3, not effort.
