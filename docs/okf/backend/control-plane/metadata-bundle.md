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
**reference-only, secrets stripped**; see the boundary section), and, since 2026-08-31, `enrichment`
(the `<write-root>/<id>_enrich.toon` companion — pipeline spec gap 6b). Every supported kind is
read/written through the uniform `BundleSource` seam regardless of its backing store:

* **`POST /bundle/export`** — `{items, provenance?, requires?}` → `{bundle, missing}`; real content + real
  `provenance.contentHash`; each resolvable `requires` ref is stamped with an `originHash` (the source's stored
  content hash); an unsupported kind is a **422** (honest boundary), never a silent omission.
* **`POST /bundle/preview`** — read-only fit-check: per item `new | unchanged | drifted | unsupported` (incoming
  hash, normalized to the stored form, vs the target's), each `requires` entry `satisfied | different | missing`
  (`different` = present but at a different version — the carried `originHash` disagrees with the target's). No writes.
* **`POST /bundle/export`** — since 2026-08-31 the UI's closure for an authored pipeline is seeded by
  [`GET /pipelines/{name}/related`](pipeline-related.md) (gap 6a). 🔴 The client derives a pipeline's
  edges from `nodes[].use` **alone**, so a companion bound by CONFIG KEY (`parsing.grammar: grammar/cdr`)
  was invisible and such a pipeline exported **without its grammar**. ⚠ Only the **outward**
  `references[]` are followed, and only entries carrying a `ref`; server edges MERGE with the derived
  ones (neither is a superset), and the call degrades so an older server cannot fail an export.
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
  * `enrichment` (2026-08-31) is the Stage-2 companion a bundle used to leave behind, so a pipeline
    travelled without its derived columns. ⚠ **An import REGISTERS, it does not merely persist** —
    `EnrichmentService` has no mtime hot-reload, so writing the file alone imports an enrichment that
    does nothing until the next restart, a silent half-import. It mirrors
    `EnrichmentRoutes.registerEnrichment` (validate → atomic write → register), exactly as the `job`
    kind hot-registers. ⚠ The `_enrich` suffix is load-bearing: `ServiceBootstrap` indexes enrichments
    BY it, so a file written without it drops out of the scan on the next restart
    (`ConfigFileSupport.fileBase` is the one place that rule lives). It sorts **after**
    `authored-pipeline` — `triggers.on_pipeline` makes it the referencer, not the referenced.
  * **The apply order's invariant is "a referenced kind precedes its referencer"**, not "every
    supported kind is listed". Omission means *apply last*, which is CORRECT for a kind that references
    a pipeline (`expectation`, `decision-rule`). `mapping` was ordered before `authored-pipeline` on
    2026-08-31 (gap 6c) because it had been absent and therefore applied after the pipeline naming it;
    `grammar` beside it was right all along, which is what made the omission easy to miss.
  * 🔴 **`schema` is deliberately NOT ordered, and the server disagrees with the UI about it.** It was
    retired as a bundle kind on 2026-07-31 (unification W1) — a schema lives only in the config TOON the
    engine executes — and `transfer/bundle.ts` keeps it in the TYPE only so an older bundle still
    parses. But `supported()` reuses `ComponentStore.WRITABLE_TYPES`, which still carries `schema`, so
    **the server WRITES a schema item the UI and its offline mock expect skipped**: the same old bundle
    imports differently offline and against a backend. Filed as **BUNDLE-SCHEMA-1** (`BACKLOG.md` §6) —
    a product call, since whichever way it resolves, one of the three surfaces changes.
* `requires` classify `satisfied | different | missing` — *present-but-different* (2026-07-18) compares the
  ref's export-stamped `originHash` to the target's stored hash; a ref that travels hash-less (older bundle, or
  unresolvable at export) can only be `satisfied`/`missing`, so the classification degrades gracefully.

The UI side (one derivation `deriveRefs`, one format, every surface — Settings workbench + editor/library
transfer menus) lives in the frontend bundle. Design history: `docs/archived-documents/plans-archive/`
(`metadata-network-design.md`, `transportability-plan.md`, `metadata-bundle.md`).
