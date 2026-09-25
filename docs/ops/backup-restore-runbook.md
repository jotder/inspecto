# Backup & Restore Runbook (System Maintenance MNT-5/MNT-6)

**Scope:** per-space config (and optionally DuckDB) backup, verification, and restore — including
restore-into-a-new-space. Everything here runs through the `maintenance` Job Type; no shell scripts.
Plan of record (archived): `docs/archived-documents/plans-archive/system-maintenance-plan.md`; requirement of record:
`docs/okf/capabilities/observability/observability.md` §3.6.

## ⚠ Edition — Standard and above only (since 2026-09-07)

`backup`, `backup_verify` and `restore` are contributed by the optional **`inspecto-backup`** module, which
Standard and Enterprise bundles carry and **Personal does not** (EDITIONS `OPS-06`). On a Personal install
every job naming one of them fails at run with *unknown maintenance task '…' — not built into this bundle, and
no installed module provides it*. That is deliberate and loud: a silent skip would let a chained job proceed
as if the backup had happened. ⚠ The repo's sample `spaces/demo` nightly chain (bundles ship no Spaces since 2026-09-25) therefore stops at `config_backup`
on Personal; see EDITIONS `OPS-06`.

## ⚠ Path containment — read this before pointing a job outside the server root

Every path field on a maintenance job (`dir`, `backup_dir`, `archive_dir`, `archive`, `target_dir`,
and `out_dir` on reports) must resolve under an **allowed root** — as must a pipeline's
`schema_file` / `grammar` / `mapping_file`, which are now enforced when the config loads. The roots come from
`-Dassist.safety.roots` (a `;`-separated list) plus every hosted Space base — the
same list the control plane's 422 write gate enforces, so a value refused at authoring is refused at
run time for the same reason.

⛔ **There is no working-directory default.** With the property unset and no Space hosted the root list
is **empty**, and every jailed value then fails with `no allowed roots configured for '<field>'` — not
with a containment error. That is fail-closed by design (a CWD fallback existed until 2026-08-14 and
silently granted the server's working directory to every containment check). A single-tenant server —
the one-shot ETL CLI, or serve over a flat config dir — hosts no Space and so registers no root of its
own: for it the property is the **only** source, and declaring it is a required deployment step.

⚠ **A relative value on a job resolves against that job's Space config root**, not the server's
working directory (`JOB-DIR-CWD-CONTAINMENT-1`, operator 2026-09-16). From
`spaces/<space>/config` that makes the backup destination `../data/backups`. ⛔ **Do not write
`spaces/<space>/data/backups`** — the Space prefix is already in the base, so the old spelling doubles
to `spaces/<space>/config/spaces/<space>/data/backups`, and on a server whose working directory makes
the old path exist it is REFUSED outright rather than resolved silently. Either way it stays under the
default root. But a backup destination **outside** the server root — a mount, a NAS, another drive —
must be declared:

```
java ... -Dassist.safety.roots="/opt/inspecto;/mnt/backups" -cp inspecto.jar com.gamma.control.ControlApi
```

Without it the job **fails** with `path '/mnt/backups' declared by 'backup_dir' resolves to …,
outside the root …`. That is the intended behaviour, not a regression: an undeclared destination is
how a traversal value (`../../..`) turns into a write outside the deployment. Declare the root;
don't work around it by relocating the archive.

## Backup

Author a `maintenance` job with `task: backup` (see the live example
`spaces/demo/config/jobs/config_backup_job.toon`):

```
job:
  name: config_backup
  type: maintenance
  task: backup
  cron: "0 4 * * *"
  dir: .
  backup_dir: ../data/backups
  prefix: <space>_config
```

Each run produces `<prefix>_<timestamp>.zip` **plus a sidecar `<archive>.manifest.json`** carrying
the SHA-256 of the archive and of every file inside it, appends one row to the
`maintenance_backups` catalog Dataset (BI-queryable), records Run Artifacts, and emits
`maintenance.backup.completed`. Preview any backup with
`POST /jobs/<name>/trigger?dryRun=true`.

- **DuckDB stores:** run `task: db_maintenance` first (CHECKPOINT merges the WAL across the
  acquisition ledger + job-run + provenance stores), then include `duckdb/` in a backup of the
  space root. Never copy a `.duckdb` file while its owning service is writing without a prior
  CHECKPOINT.
- **Backup retention:** a `task: cleanup` job on the backup dir; ALWAYS set `min_keep` so a
  retention sweep can never delete the last backups
  (`dir: ../data/backups`, `retention_days: 30`, `min_keep: 5`).

## Verify

`task: backup_verify` with the same `backup_dir` checks the newest archive (or `all: "true"`, or
one named `archive:`) — archive hash first, then every entry hash. A mismatch fails the Run and
emits `maintenance.backup.verify_failed` (CRITICAL) — wire an Alert Rule to it. The demo chains
verification automatically via `on_signal: maintenance.backup.completed`
(`spaces/demo/config/jobs/backup_verify_job.toon`).

## Restore

`task: restore` is fail-closed: the sidecar manifest must be present and the archive hash must
match before a single byte is written; extraction is path-jailed under `target_dir`; every
extracted file is re-hashed against the manifest.

1. **Preview first** — `POST /jobs/<restore-job>/trigger?dryRun=true` reports file count, bytes,
   and every conflict (existing files in the target).
2. A real run **blocks on conflicts** unless `overwrite: "true"` is set.

### Restore into a new space (restore-into-new-environment)

1. Create the space (`POST /spaces` or `spaces/<new>/space.toon`) — this lays down the
   `config/ data/ audit/ duckdb/` axes.
2. Run a restore job: `archive: <backup zip>`, `target_dir: ../../<new>/config` (empty target →
   zero conflicts). ⚠ **The base is the config root of the Space the restore JOB lives in, not of the
   Space being restored into** — there is no "the new space" for a relative value to hang off, so
   crossing spaces means climbing out (`../../`) or writing an absolute path under a declared root.
   ⛔ `target_dir: spaces/<new>/config` is the pre-2026-09-16 spelling and now doubles or refuses.
3. Restart or hot-load: job/pipeline configs register on boot; components are picked up by the
   registry scan.
4. Smoke it: `GET /spaces/<new>/health`, `GET /spaces/<new>/jobs`, one representative pipeline
   trigger.

**Rollback of a bad restore:** the backup taken immediately before restoring (step 0 of any
restore into a *live* space: back up the current `config/` first) is the rollback path — restore
it with `overwrite: "true"`.
