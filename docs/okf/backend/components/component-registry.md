---
type: Concept
title: Component Registry
description: Reusable grammar/transform/sink components (now + dataset/widget/dashboard/query), the /components + /pipelines routes, ETag optimistic concurrency, preview and safe-delete.
resource: inspecto-engine/src/main/java/com/gamma/pipeline/ComponentStore.java
tags: [components, registry, grammar, transform, sink, etag, routes]
timestamp: 2026-07-07T00:00:00Z
---

# Component Registry

Components are the `use:`-referenced building blocks of authored [Pipelines](../pipeline-graph/pipeline-graph-design.md). They
live under `<write-root>/registry/<type>/` as TOON files, addressed by `<type>/<name>`.

* **Types**: `connection`, `grammar`, `transform`, `sink`, `alert`. ⚠ **`schema` is NOT a component**
  (retired 2026-07-31, unification W1): a schema lives only in the path-addressed config TOON the engine
  executes (`processing.schema_file`), because no code path ever resolved a component id into a runnable
  schema and `bindKindFor` never offered it as bindable. `ComponentStore.WRITABLE_TYPES`
  was **widened in W3** to also persist `dataset`, `widget`, `dashboard`, and `query` — the seam that lets the
  UI's Studio kinds store for real instead of mock-only. (The UI also persists a `rule` type, used by the
  data-table rule save — see the UI bundle.)
* **Storage**: `ComponentStore` (`inspecto-engine/src/main/java/com/gamma/pipeline/ComponentStore.java`) — CRUD over the
  registry dir; `ComponentRegistry` holds the `Component` record (`type`, `name`, `ref`, `content`).
* **Optimistic concurrency** (W3): `ContentHash` (mirrors the UI's `content-hash.ts`, parity-pinned by test)
  hashes each component's content; `/components` responses carry an **`ETag`**, reads honour
  `If-None-Match` (304), and writes honour `If-Match` (precondition-failed on a stale hash).
* **Safe delete**: `PipelineReferences` scans every authored Pipeline's nodes for `use:` references;
  `ComponentRoutes` returns `409` if a component is still referenced.

## Routes

* `/components/{type}` — `GET` list, `GET /{id}`, `POST` create, `PUT /{id}` update, `DELETE /{id}` (safe).
* `/components/{type}/{id}/test` — preview a transform/grammar/sink over sample rows on a **throwaway**
  (the `schema` variant is gone with the kind; the equivalent cast check is `POST /config/preview/schema`)
  DuckDB connection (`ComponentPreview`), never touching production.
* `/pipelines…` — the `Pipeline*Routes` modules (`inspecto/src/main/java/com/gamma/control/PipelineListRoutes.java`,
  alongside `PipelineGraphRoutes`, `PipelineRenameRoutes`, `PipelineSettingsRoutes` and the shared
  `PipelineSupport` helpers): `GET /pipelines`
  (lifted pipelines), `GET /pipelines/node-types` (the editor palette catalog), `GET /pipelines/combined` (the
  store-joined pipeline+job topology). Authoring goes through the graph round-trip — `PUT
  /pipelines/{name}/graph` (+ `GET …/graph/raw`) — owned by the
  [pipeline-graph bundle](../pipeline-graph/editable-round-trip.md); the old `/pipelines/authored/*`
  CRUD surface is retired (grandfathered flows stay readable/runnable/deletable). The
  store superimposition (`PipelineStores.superimpose`) joins consumer `source_store` names to producer `store`
  names — no `on_pipeline` name-coupling — and a `DeletionFence` guards store deletion.

## The Component metamodel

Every reusable artifact is a **Component** `{ kind, id, config, parts?, wiring? }` — atomic kinds have no
parts/wiring; composites (Pipeline, Dashboard, Job) add parts plus a wiring strategy (a Pipeline's graph *is*
its wiring). The relationship graph is **derived, never stored**: composition (`part-of`) ∪ reference (`uses`)
edges, with a single `deriveRefs` lineage derivation (R1) feeding the reuse-graph, delete protection, and
[bundle](../control-plane/metadata-bundle.md) closure alike. Persistence stays federated per kind —
`WRITABLE_TYPES` is widened only when a kind needs real storage; it is deliberately **not** a single generic
wiring editor nor a storage unification. Adoption (D0→P4) completed 2026-06-28; design of record:
`docs/archived-documents/plans-archive/component-model.md` (+ its adoption plan).

The UI counterparts are the [components](../../frontend/features/components.md) and
[Pipelines](../../frontend/features/pipeline-editor.md) features in the frontend bundle.
