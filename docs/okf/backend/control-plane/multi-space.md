---
type: Concept
title: Multi-Space Hosting
description: SpaceManager → SpaceContext → CollectorService (wrapped, not rewritten); MDC-based singleton isolation; the /spaces seam.
resource: inspecto/src/main/java/com/gamma/service/SpaceManager.java
tags: [control-plane, multi-space, isolation, mdc, spaces]
timestamp: 2026-07-07T00:00:00Z
---

# Multi-Space Hosting

One server hosts many isolated **spaces** (projects). The ~40-method per-instance `CollectorService` is
**wrapped, not rewritten**.

* `SpaceManager` (`inspecto/src/main/java/com/gamma/service/SpaceManager.java`) — `single(CollectorService)`
  (single-tenant, byte-identical to pre-multi-space) or `discover(spacesRoot)` (boots each `spaces/<id>/`
  with a `config/` subtree). Runtime CRUD with no restart: `create`, `createFromBundle`, `delete(id, purge)`,
  serialized on `lifecycleLock` (reads are lock-free).
* `SpaceContext` (`…/service/SpaceContext.java`) — a thin per-space holder (id + `SpaceRoot` + manifest + its
  own unchanged `CollectorService`).
* `SpaceMigrator` (`…/service/SpaceMigrator.java`) — the **one-time CLI** that turns the flat layout into a
  `spaces/<id>/` directory (`configDir→config`, `database/→data`, `jobs_audit/→audit`, DuckDB files → `duckdb/`;
  idempotent, `--dry-run`). ⚠ Nothing invokes it at boot — this line said "on boot" until 2026-09-08.

## Singleton isolation via the `space` MDC

Five process-wide singletons — `EventLog`, `MetricRegistry` (its `space` label),
[`ConnectionRegistry`](../acquisition/connectors.md), `StabilityGate`, `AcquisitionLedgers` — resolve per-space
by reading `EventLog.currentSpaceId()`, which returns the `space` **SLF4J MDC** key or `"default"` when
absent. **The MDC does not cross thread-pool boundaries**, so every executor on the execution path must copy
the MDC onto its worker (see [gotchas](../gotchas/cross-cutting.md)). The `default` space sets no MDC → output
stays label-free (byte-identical single-space).

## API seam

⚠ **`DELETE /spaces/{id}` has TWO distinct `409`s** (`SpaceRoutes.java`): (1) a **single-tenant
refusal** — `requireMultiSpace` (`:118` → `:133-135`) fires first for every CRUD verb when
`supportsCrud()` is false: *"this server hosts a single space; launch with -Dspaces.root to manage
many"*; (2) a **last-space-on-disk purge refusal** (`:124-127`), only on `?purge=true` — the tree is
the last copy and `main()` refuses to boot an empty `-Dspaces.root`, so the escape is *"delete it
without `?purge=true` (the files stay for re-discovery), or create another space first"*.

`/spaces/{id}/…` is stripped + MDC-bound by [`ControlApi.dispatch`](control-api.md); un-prefixed paths use the
current/default space. In versioned URLs the space segment sits **after** `/v1`
(`/api/v1/spaces/<id>/…` — dispatch strips `/api/v1` first, then matches `/spaces/{id}/…`). Space CRUD
(server-global, un-prefixed), all in `SpaceRoutes.java`: `GET /spaces` (`:45`), `POST /spaces` (`:58`), `POST /spaces/import` (`:60`, from a bundle zip), `PUT /spaces/{id}` (`:62`, rename/re-describe), `DELETE /spaces/{id}?purge=` (`:64`), `GET /spaces/templates` (`:56`, the shipped-template gallery — empty, not 409, on a single-tenant server), and
`GET /spaces/_meta → {multiSpace}` (the UI capability probe — never infer from the space-list length). The
**whole-space zip** lives in `DataSourceRoutes.java:38-41`, not here: `GET /spaces/{id}/export`,
`POST /spaces/{id}/import/preview` (dry run) and `POST /spaces/{id}/import[?on_conflict=overwrite]`
(`BundleExporter` / `BundleImporter`, zip-slip jailed) — omitted from this page until 2026-09-08. Requirement of
record: [Spaces & tenancy capability spec](../../capabilities/spaces/spaces.md). The UI
side is the [spaces feature](../../frontend/features/spaces.md) + the global
[space interceptor](../../frontend/conventions/multi-space.md).
