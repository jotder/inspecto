# Spaces

> **Level 3 module page** (`SOLUTION-SUITE-DOCS-1`). Layer: Platform base.
> Offering: every edition (multi-Space hosting needs `-Dspaces.root`). Checked against the code on 2026-10-17.

## 1. What it is

A **Space** is a fully isolated project environment inside one installation: its own config, data, audit
trail and DuckDB stores, its own engine instance, and its own module switches. A **Space Template** is a
reusable blueprint that seeds a new Space (Type → Instance). This page also covers the **Installed →
Enabled → Permitted** gates, because the middle gate is a per-Space setting.

The requirement of record is the [Spaces & tenancy capability spec](plan-spaces.md); the hosting
mechanism is [Multi-Space Hosting](../processor/architecture-multi-space.md); the module gates are defined in
[module taxonomy](../../layer/platform/architecture-module-taxonomy.md).

## 2. Who uses it

- **Operators** choose the boot mode (`-Dspaces.root`) and run the one-time `SpaceMigrator` CLI.
- **Administrators** create, rename and delete Spaces, seed them from templates or a whole-Space zip, and
  switch modules off per Space (`canAdminister`).
- **Every user** works inside one Space at a time; the SPA's space interceptor prefixes their calls.

## 3. Where it sits

| Piece | Code |
|---|---|
| Space registry and lifecycle | `inspecto/src/main/java/com/gamma/service/SpaceManager.java` |
| Per-Space holder (id + root + manifest + engine) | `inspecto/src/main/java/com/gamma/service/SpaceContext.java` |
| Where a Space's files live | `inspecto/src/main/java/com/gamma/service/SpaceRoot.java` |
| Flat-to-Space migration CLI | `inspecto/src/main/java/com/gamma/service/SpaceMigrator.java` |
| Space CRUD routes | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java` |
| Enabled gate (routes) | `ControlApi.requireModuleEnabled` in `inspecto/src/main/java/com/gamma/control/ControlApi.java` |
| Enabled gate (settings file) | `inspecto/src/main/java/com/gamma/control/ModuleSettings.java`, `ModuleSettingsRoutes` |
| Enabled gate (Jobs, sweeps, maintenance) | `inspecto/src/main/java/com/gamma/control/ModuleGate.java`, `JobModuleGate` |
| Installed gate (absent-module stubs) | `inspecto/src/main/java/com/gamma/control/AbsentModuleRoutes.java`, `ModulesRoutes` |
| Permitted gate | `ApiContext.withCapability` in `spi/inspecto-http-spi/src/main/java/com/gamma/spi/http/ApiContext.java` |
| Template catalog | `spaces/_templates/` (one directory per template) |

## 4. How it works

![Spaces request path and gates](../../assets/spaces.svg)

**Boot modes** (`ControlApi.main` reads the `spaces.root` system property):

- **default root** — no `-Dspaces.root` and no config args: discover mode over `<working dir>/spaces`, i.e.
  `<install_dir>/spaces` (the launchers run from the install dir), since 2026-10-30.
- **single** — no `-Dspaces.root` but config args given: `SpaceManager.single(CollectorService)` over `SpaceRoot.legacy()`, the
  pre-Spaces flat layout relative to the working directory. `supportsCrud()` is false, so Space CRUD answers
  409 "this server hosts a single space".
- **discover** — `-Dspaces.root=<dir>`: `SpaceManager.discover(root)` boots every `spaces/<id>/` that has a
  `config/` subtree, each over `SpaceRoot.under(base)`. Zero Spaces boots with a warning; Space-scoped routes
  then answer 503 "No Space is attached".
  **What is NOT a Space (`SpaceManager.notASpaceReason`, 2026-10-27).** A folder under the Spaces root is skipped
  when its name starts with `.` or `_`, or has a `-`/`_`/`.`/space-separated token `bak`, `snapshot`, `scratch` or
  starting `backup` (`mfs-backup-20261026`, `backup_mfs`, `mfs.bak`), or it has no `config/`. A copy of a Space
  holds `config/` and `space.toon` exactly like the original, so only the name keeps it from booting — before this
  an MFS rehearsal opened a backup copy as a live Space, wrote into it and drained the real Space's inbox.
  Each skipped folder that looks like a Space (has `config/` or `space.toon`) is logged once at boot:
  `Ignoring folder '<name>' under the Spaces root — not a Space: <reason>`. `space.toon` stays optional (pre-manifest
  Spaces boot). Keep backups outside the Spaces root, or name them with one of those tokens. ⚠ `POST /spaces`
  with such an id is not refused yet, so it would vanish at the next restart.

**Space root.** `SpaceRoot` is the one object deciding where a Space's state lives. For a directory Space
(`DirSpaceRoot`): `config/` (scanned `*.toon`), `data/` (partition stores; events under `data/events/`),
`audit/`, `duckdb/`, and authored Pipelines under `config/pipelines/` (an older `config/flows/` still
resolves). Each store accessor (`objectsDbUrl()`, `jobRunDbUrl()`, ...) gives the per-Space default; a
`-D` flag can still override it. `_`-prefixed directories (`_templates/`, `_shared/`) are never Spaces.

**Data root (`SPACE-DATA-ROOT-1`, 2026-10-30).** Config always stays in `spaces/<id>/` (`space.toon`, `config/`,
`config.secrets/`). The high-growth state — `data/` (inbox, lake, backups, events, …), `audit/` and `duckdb/` — sits
under `SpaceRoot.dataRoot()`, which is: `data_root:` in `space.toon` (absolute) → else `-Dspaces.data.root`/`<id>`
by convention (a mounted NFS / SMB / s3fs / CSI volume) → else the Space directory (today's layout). Configs do
not change: a path that resolves under `<space>/data|audit|duckdb` — `data/orders/inbox`, `../data/backups` — is
redirected onto the data root by `PathJail.onDataRoot`, and the data root is an allowed root of its own Space only.
At load (`SpaceDataRoot.claim`) a moved Space is refused, never split, when: `data_root` is relative, inside
`config/` or a `*.secrets` dir; the mount (its parent) is missing; the Space folder still holds files in `data/`,
`audit/` or `duckdb/` (move them first — nothing is moved at boot); or the `.inspecto-space` stamp in the data root
names another Space directory (a copied Space folder must get its own data root). A newly created Space's seeded
`data/` is moved onto its data root (`adoptSeeded`); `DELETE ?purge=true` purges the data root only when its stamp
names the Space; a bundle import drops the exporter's `data_root`.
⚠ DuckDB needs random writes: put `duckdb/` (the whole data root) on block storage or NFS, not on an
object-storage mount (s3fs / mountpoint-s3). On such a mount the inbox loses atomic rename (move = copy + delete).
**Space references.** A regular file `<spaces root>/<id>.ref` holding an absolute path boots the Space folder it
names (`SpaceManager.followRef`): the folder must be named `<id>`, exist, and lie outside the spaces root; a real
`<id>/` folder beside it wins. An unfollowable ref is skipped with `SPACE_REF_INVALID`. Create refuses an id taken
by a ref; `update` writes `space.toon` in the referenced folder; purge deletes only the `.ref` (the folder and its
data root stay).
**Overlap refusal (`SPACE_OVERLAP`).** Discovery resolves every candidate's folder and data root before any Space
boots: two overlapping Space folders (a ref target inside another, or containing the spaces root) are both refused;
a data root that is, contains or sits inside another Space's folder or data root refuses only the Space that
declared it. A runtime create meets the same rule in `SpaceDataRoot.stake`.
⚠ LA snapshots, LA upload records and the entity-list fact log write `config/audit/`, not the Space's `audit/`,
so they stay with the config.

**Request binding.** `ControlApi.dispatch` strips `/api/v1`, matches `/spaces/{id}/…`, rewrites the path
and binds the `space` SLF4J MDC key for the request; an un-prefixed path binds the current Space.
Process-wide singletons (`EventLog`, `MetricRegistry`, `ConnectionRegistry`, `StabilityGate`,
`AcquisitionLedgers`) resolve per Space through `EventLog.currentSpaceId()`.

**Lifecycle.** `create`, `createFromBundle`, `createFromTemplate`, `update` and `delete(id, purge)` run under
one `lifecycleLock`; reads are lock-free. `createFromTemplate` copies the template's `config/`, rewriting
`${SPACE}` tokens in every `.toon`, and copies `data/` verbatim.

**The three gates** (never inside feature code):

1. **Installed** — is the module on the class path? A known but absent module's routes answer
   `503 CAPABILITY_UNAVAILABLE` (`AbsentModuleRoutes`); `GET /modules` lists it `not-installed`.
2. **Enabled (per Space)** — `modules.toon` in the Space's config root holds a `disabled:` list. A route
   owned by a disabled feature answers `404 MODULE_DISABLED` before its handler runs; the module's Jobs
   are recorded `SKIPPED` and its gated background ticks pause (`ModuleGate`). An unreadable file disables
   nothing (fail-open). Core routes own no feature and are never gated here.
3. **Permitted (RBAC)** — `ApiContext.withCapability` demands a capability of the request's Subject
   (403 otherwise); with no Subject attached (Basic) it is a no-op.

## 5. Configuration

| Key / file | Where | Meaning |
|---|---|---|
| `-Dspaces.root=<dir>` | JVM flag | discover mode; unset = `<install_dir>/spaces`, or a single flat Space when config args are given |
| `<id>.ref` | spaces root | Space reference: absolute path of a Space folder kept elsewhere |
| `space.toon` | Space directory | the Space manifest (display name, description, optional absolute `data_root`) |
| `-Dspaces.data.root=<mount>` | JVM flag | convention data root `<mount>/<id>` for every Space without `data_root` |
| `modules.toon` → `disabled: [ids]` | Space config root | Enabled gate; absent file = all installed modules on; unknown ids kept as `inert` |
| `template.toon` → `name`, `tagline`, `description`, `icon`, `contents[]` | template directory | gallery entry, e.g. `spaces/_templates/orders-starter/template.toon` |

Space ids match `[a-z0-9][a-z0-9-]{0,62}`; `default` is reserved and cannot be renamed.

## 6. Screens

Moved to [Spaces — screens](user-guide-spaces.md).

## 7. API

Moved to [Spaces — API](api-spaces.md).

## 8. Interactions

- [Identity & access](../access/architecture-overview.md) — the Permitted gate and per-Space role tables.
- [Jobs & scheduler](../processor/architecture-jobs-scheduler.md) — Jobs of a disabled module are skipped (`JobModuleGate`).
- [Exchange](../exchange/architecture-overview.md) — the only sanctioned cross-Space path, by grant; its ledger lives under `_shared/`.
- [Storage](../entity-store/architecture-storage.md) — every per-Space store default comes from `SpaceRoot`.
- [Observability & backup](../observability/architecture-observability-backup.md) — the `space` label on metrics and events.
- Content packs ([telecom fraud](../../solution-pack/telco-fraud/architecture.md),
  [revenue assurance](../../solution-pack/business-assurance/architecture-revenue-assurance.md)) ship as Space Templates.

## 9. Limits and gotchas

- **The MDC does not cross thread pools.** Every executor on the execution path must copy it, or work lands
  in the `default` Space (see [cross-cutting gotchas](../../layer/platform/admin-guide-gotchas.md)).
- **`SpaceMigrator` is a CLI, not a boot step**, and a discover-mode server has no flat fallback.
- **`DELETE` has two 409s:** any CRUD verb on a single-tenant server, and `?purge=true` on the last Space
  directory on disk.
- **A whole-Space zip never imports into an existing Space** (403); it seeds a new Space only.
- **`SpaceRoot.legacy()` writes into the working directory** — start a single-Space server from the folder
  you intend it to use.
- **Enabled is fail-open:** an unreadable `modules.toon` disables nothing; the Permitted gate is unaffected.
- **Templates are not a Component kind**; an unreadable template is skipped with a warning.
