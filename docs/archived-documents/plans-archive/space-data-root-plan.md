# Space data root — data, audit and DuckDB stores on a separate disk

Status: **shipped 2026-10-30, archived.** As-built: `docs/okf/product/module/config/architecture-spaces.md`
(Data root, Space references) and `admin-guide-configuration-reference.md`. Changed in build: no per-site switches
were needed for LA/entity-list (they write `config/audit/`, BACKLOG `SPACE-DATA-ROOT-CONFIG-AUDIT-1`); a
convention mount `-Dspaces.data.root` was added; an unstamped dir holding only `data/ audit/ duckdb/` may be
claimed (hand migration); `<id>.ref` Space references and the `<install_dir>/spaces` default root were added.
Items: module.config (Spaces), layer/platform (PathJail).

## Goal

All configuration stays in `spaces/<id>/` (`space.toon`, `config/`, `config.secrets/`). High-growth state —
`data/` (inbox, lake/partition stores, backups, index, events), `audit/` and `duckdb/` — may live on another
volume: a bigger local disk, NFS/SMB mount, or an S3 bucket mounted as a filesystem (s3fs, mountpoint-s3, CSI).

## Decisions (operator, 2026-10-30)

- One `data_root` per Space, declared in `space.toon`. No per-area mapping.
- `data/`, `audit/` and `duckdb/` all move with it.
- Mounted paths only. Native `s3://` is out of scope (inbox moves, backups and DuckDB need a real filesystem).

## Shape

```toon
# spaces/demo/space.toon
display_name: Demo
data_root: D:/inspecto-data/demo      # absolute; optional
```

Layout under `data_root` is the same layout the Space has today:
`<data_root>/data/…`, `<data_root>/audit/…`, `<data_root>/duckdb/…`.
Unset `data_root` = the Space directory itself → **byte-identical to today**.

## No config rewrite — the rebase rule

Configs keep writing `data/orders/…` (data paths resolve under the Space dir, `PathJail.resolveDataPath`) and
`../data/…` (job paths, resolved against `config/`). Rather than a new `${DATA}` token, one rule:

> Any path that resolves to `<space>/data`, `<space>/audit` or `<space>/duckdb` (or below) is rebased onto
> `<data_root>/…`.

So existing Spaces, templates and examples move by editing one line in `space.toon`. Applied in:

1. `DirSpaceRoot` — `dataDir()`, `eventsDir()`, `auditDir()`, `duckdb()` read `data_root` from the manifest.
2. `PathJail.resolveDataPath` / `resolveJobPath` — rebase after resolution, via a `spaceDir → dataRoot` map
   registered at `SpaceBootstrap.load` (PathJail is in `inspecto-config` and cannot see `SpaceRoot`).
3. Allowed roots — a registered `data_root` is automatically an allowed root for its Space; the deny/secrets
   checks (`refuseDataHome`, `refuseSecrets`) are applied against the rebased path too.
4. Hard-wired sites switched to `SpaceRoot` accessors: `KpiRoutes:272`, `TemplateSeedGate:73`,
   `SpaceLayoutContract:71`, `SpaceSamples:73`, `SpaceManager:598` (template seed writes data into the new root),
   `UploadRoutes:88/383`, `FsInvestigationLayout:25`, `SnapshotStore:54`, `EntityFactLog:72`,
   `PathJail:460/498`.

## Safety

- **Two Spaces, one data root** — refused at boot. `<data_root>/.inspecto-space` stamps the owning Space id;
  a mismatch (e.g. a copied Space folder still pointing at the original's data) refuses to load the Space.
  This keeps the MFS backup-copy incident (2026-10-27) from recurring through a shared data root.
- `data_root` must be absolute, must not be inside any Space's `config/`, nor a `*.secrets` dir, nor the
  spaces root itself.
- Unreachable mount at boot → the Space fails to load with a named error (never falls back to `<space>/data`,
  which would silently split data across two places).
- Space delete with `purge=true` purges `<data_root>` only when the stamp matches.
- Whole-Space zip export/import (`BundleExporter`/`BundleImporter`) reads data from `data_root`; import into a
  new Space writes under the new Space's default location (no `data_root` carried over).

## Mount caveats (documented, not enforced)

- DuckDB files on NFS/SMB/s3fs: single writer only, no `mmap`-hostile mounts; s3fs/mountpoint-s3 does not
  support DuckDB's random writes — put `duckdb/` on block storage or NFS, not object-storage mounts.
- Inbox on an object-store mount: atomic rename is not guaranteed — the collector's stability gate covers
  partial files, but `move` becomes copy+delete.

## Tests

- `SpaceRootDataRootTest` — unset = today's paths; set = rebased paths for every accessor.
- `PathJailTest` — `data/x` and `../data/x` rebase; escape, secrets and deny still refused after rebase.
- `SpaceBootstrapTest` — stamp mismatch refused; missing mount refused; relative `data_root` refused.
- An end-to-end run of the demo Space with `data_root` set to a temp dir: inbox → lake → backup land there,
  nothing written under `spaces/demo/data`.

## Out of scope

Native `s3://` data roots; per-area mapping; moving an existing Space's data (operator copies the folder,
then sets `data_root`).
