---
type: Concept
title: Metadata Bundle & Transportability
description: The cross-instance config bundle — v2 envelope (refs + provenance + requires), BundleRoutes export/preview/import, drift detection, idempotent re-promotion, and the config-not-data boundary.
resource: inspecto/src/main/java/com/gamma/control/BundleRoutes.java
tags: [control-plane, bundle, transportability, promotion, content-hash, drift]
timestamp: 2026-07-16T00:00:00Z
---

# Metadata Bundle & Transportability

A bundle moves **configuration, never data rows** between instances (staging → production promotion). It is a
*serialized, self-describing subgraph* of the component graph: v1 carried `{kind, id, content}` and re-derived
dependencies on both sides; **v2** (R6, shipped 2026-07-06) adds `items[].refs` (outgoing lineage edges, each
`included | external`), `items[].provenance` (`sourceSpace`, `exportedAt`, `contentHash` — SHA-256 of the
canonical JSON), and top-level `requires` (the deduped external refs — the bundle's contract with the target).
v1 files stay importable (refs/provenance optional ⇒ derived on the target). Schema:
[`metadata-bundle.schema.json`](../../../api/schemas/metadata-bundle.schema.json). Distinct from the
**Space zip bundle** (whole-space clone, [multi-space](multi-space.md)).

## Backend endpoints (SPC-4, shipped 2026-07-07)

`BundleRoutes` (`com.gamma.control`) serves the v2 envelope for the `ComponentStore.WRITABLE_TYPES` kinds
(grammar/schema/transform/sink/dataset/query/widget/dashboard) plus, since 2026-07-18, `authored-pipeline`
(`PipelineStore`, round-tripped through `PipelineCodec`), `job` (`JobService`'s live registry — import
hot-registers via `upsertJob`, exactly like the `/jobs` write routes), and `saved-view` (the event-viewer
`SavedViewStore`; **not** the run-generated `pipeline.ViewStore` `sink.view` definitions, which aren't
authored config), and, since 2026-07-25, `connection` (the live `CollectorService` connection registry —
**reference-only, secrets stripped**; see the boundary section). Every supported kind is read/written through the uniform `BundleSource` seam regardless
of its backing store:

* **`POST /bundle/export`** — `{items, provenance?, requires?}` → `{bundle, missing}`; real content + real
  `provenance.contentHash`; each resolvable `requires` ref is stamped with an `originHash` (the source's stored
  content hash); an unsupported kind is a **422** (honest boundary), never a silent omission.
* **`POST /bundle/preview`** — read-only fit-check: per item `new | unchanged | drifted | unsupported` (incoming
  hash, normalized to the stored form, vs the target's), each `requires` entry `satisfied | different | missing`
  (`different` = present but at a different version — the carried `originHash` disagrees with the target's). No writes.
* **`POST /bundle/import`** — sequential upsert in dependency order (referenced kinds first), gated
  `canAuthorWorkbench` → write-root 503 → **integrity pre-check 422** (MNT-16: `ComponentIntegrity` blocks only
  findings the import would *introduce* — computed over (registry ∪ incoming) minus pre-existing). Existing
  defaults to skip (per-item `overwrite` opt-in); identical hash ⇒ `unchanged` (idempotent re-promotion);
  per-item outcomes, the batch never aborts.

## Boundary & invariants

* **Secrets never travel** — a connection's secret-bearing fields export **stripped**, `${ENV:…}` references only.
* **Data never travels** — a dataset item is metadata (columns/roles/measures/query); runtime state (runs,
  batches, Incidents, watermarks) and server TOON config are out of scope by design.
* **`connection` — SHIPPED 2026-07-25 (BACKLOG D2): reference-only, secrets stripped.** A bundle may carry a
  `connection`, but **never a secret value in any form** — not plaintext, not bundle-encrypted. Only the
  `${ENV:…}` reference travels, so an importing installation must have the referenced env/secret provisioned
  independently; if it does not, the connection imports with an unresolvable reference and fails closed at
  first use rather than at import. Rationale: a bundle is a promotion/transport artifact that lands in git, CI
  and support tickets, so an encrypted-secret option would put credential material in all three.
  * ⚠ **Strip, do not mask.** `ConnectionProfile.toBundleMap()` (new; **not** `toMap()`, which keeps masking
    for the UI) **omits** a literal `password` / `tunnel.password` / `proxy.password` / secret-ish `options`
    key entirely. A `***` sentinel would be a persisted lie that round-trips back into the target as a
    literal-looking value. `SecretResolver.isReference` is the predicate; `ConnectionProfile.isSecretKey`
    (widened from the private `looksSecret`) is the one rule both views share.
  * ⚠ **The bundle uses the on-disk key spelling `base_path`, not the API's `basePath`** (corrected
    2026-07-25, one day after the kind shipped). A bundle is a file, so it follows the `*_connection.toon`
    canon; `ConnectionProfile.fromMap` accepts either spelling, so bundles exported by the original
    2026-07-25 build (camelCase) still import. The local key translation `ConnectionBundleSource.parse` used
    as a workaround is gone. **Cosmetic consequence:** re-exporting an unchanged profile differs by that one
    line from a bundle stored before the fix, which can read as spurious drift for bundles kept in git.
  * **Import is defence in depth** — a secret-looking field that is present, non-blank and not a `${…}`
    reference (including `***`) fails *that item*, so a bundle can never smuggle a raw secret in.
  * `connection` is **first in `APPLY_ORDER`** (no outbound refs; an authored pipeline's source may reference
    it) and is **not** in `INTEGRITY_KINDS` — `ComponentIntegrity`'s ref graph covers only `ComponentStore`
    kinds. Persistence reuses `ConnectionRoutes.persistConnection` (jail → atomic write → hot-register), so an
    imported profile behaves exactly like a `POST /connections`.
* `requires` classify `satisfied | different | missing` — *present-but-different* (2026-07-18) compares the
  ref's export-stamped `originHash` to the target's stored hash; a ref that travels hash-less (older bundle, or
  unresolvable at export) can only be `satisfied`/`missing`, so the classification degrades gracefully.

The UI side (one derivation `deriveRefs`, one format, every surface — Settings workbench + editor/library
transfer menus) lives in the frontend bundle. Design history: `docs/archived-documents/plans-archive/`
(`metadata-network-design.md`, `transportability-plan.md`, `metadata-bundle.md`).
