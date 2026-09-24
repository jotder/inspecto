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
* **`POST /bundle/preview` also returns `integrity: [...]`** (2026-09-25, load-as-draft D3) — the findings the
  previewed items would *introduce*, computed by the same `introducedIntegrityFindings` import enforces, but
  **advisory**: still read-only and ungated (a Business subject reads it; the import door keeps its 403).
  Empty when writes are disabled — there is no registry to judge against.
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
    imports differently offline and against a backend. Filed as **BUNDLE-SCHEMA-1** and **FIXED 2026-08-31** (the archived board snapshot records the close; the mock
    surface was deleted the same day, so only the server and the SPA remain and they agree) — ⚠ this paragraph
    cited a live `BACKLOG.md` §6 row that does not exist (corrected 2026-09-08).
  * **Decision 2026-09-06 (operator) — the word `schema` in a bundle manifest means the REGISTRY id**
    (`registry/schemas/<id>`). A pipeline-owned `<name>_schema.toon` and its `_mapping.csv` / `_structure.csv`
    siblings travel under their own kind (with the pipeline's dependency closure), never as `schema`. This is the
    pre-condition the BACKLOG row "Canonical-pipeline selective bundle export/import" was waiting on.
* `requires` classify `satisfied | different | missing` — *present-but-different* (2026-07-18) compares the
  ref's export-stamped `originHash` to the target's stored hash; a ref that travels hash-less (older bundle, or
  unresolvable at export) can only be `satisfied`/`missing`, so the classification degrades gracefully.

## Import as draft (SHIPPED 2026-09-25 for Dashboard, Widget, Dataset, authored Pipeline)

The editor-hosted alternative to write-through import: the incoming item opens in its editor as **unsaved
work**. Decisions D1–D8 (operator, 2026-09-25) are recorded in
[`bundle-load-as-draft-design.md`](../../../superpower/bundle-load-as-draft-design.md) §7; as built:

* **Where** — an extra *Import as draft…* item beside *Import…* in the **editor** transfer menus only
  (`<inspecto-transfer-menu [importDraft]="true" (draftImported)>`); libraries and Settings stay write-through
  (D8). Shipped on the Dashboard, Widget and Dataset editors and the authored Pipeline editor (D5). ⏳ Not
  yet: Link Analysis / Geo views. ⛔ `connection` never (a draft editor would invite typing a secret).
* **The dialog** is `ImportBundleDialog` with `mode: 'draft'`: the operator picks ONE row of the host's kind;
  the dialog never posts the target to `/bundle/import`. It closes with an `ImportDraft`
  (`inspecto-ui/src/app/inspecto/transfer/import-draft.ts`: content, `sourceSpace`, `targetExists`, `integrity`, `prerequisites`).
* **Prerequisites are written first, explicitly (D4).** `draftPrerequisites` = the target's closure *inside the
  bundle* minus what the Space already holds; those go through the ordinary `/bundle/import` (every gate) and
  the draft opens only after that call succeeds. A 422/503 or any failed item opens **no draft** and names
  what already landed — the `applyKpiReport` rule. An existing prerequisite is never overwritten.
* **Integrity is advisory (D3).** After the prerequisites, the dialog calls the read-only preview for the target
  alone; the editor's banner lists the findings. An unreadable preview (or an older server with no list) is
  `integrity: null` — shown as *not checked*, never as clean. ⚠ The check runs once, when the draft opens; edits
  made afterwards are not re-checked before Save.
* **Save is the pane's own route (D2)** — `POST /components/{kind}` for a new id, `PUT` for an existing one.
  When the draft landed on an existing id (D6) the editor first reads the stored copy: its content is the
  banner's diff baseline (`configDiff`, the AI-draft diff) and its `contentHash` is sent as **`If-Match`**, so
  the draft cannot clobber a concurrent edit. ⚠ Only a draft's Save sends `If-Match`; these editors' ordinary
  saves still write unconditionally, as before.
* **In memory only (D1).** Reload or navigation drops it; Discard reloads the stored item (edit) or leaves the
  create route. A draft for a *different* id than the open editor is carried across the one navigation by
  `ImportDraftHandoff` (consumed on take). 🔴 Two edit routes of one editor share a route config, so the
  router REUSES the component and `ngOnInit` (where the draft is taken) would never run — that hop bounces
  through the list route with `skipLocationChange`. 🔴 An editor that takes a draft must SKIP its plain stored
  load: both are async, and the stored copy landing second silently overwrote the draft (pinned by the
  Dataset editor's spec, mutation-checked).
* **Authored Pipeline (slice 5).** The draft opens as the pipeline's TAB, unsaved (undo holds the tab's
  previous state), keeping this Space's identity and lifecycle — a draft never renames or activates a
  pipeline; a new id lands `active: false`. Existing id: the editor reads **`GET …/graph/raw`** (the
  lossless shape — ⛔ never `GET …/graph`, a display projection that is not a valid PUT body) together with
  its `ETag`, and Save is the pane's own **`PUT /pipelines/{name}/graph`** with that ETag as **`If-Match`**
  (STORE-CONFLICT-DETECTION-1: 409 on a concurrent edit); the save stays key-preserving and fail-closed.
  New id: Save first runs the create route New pipeline uses (D5) — the space-convention scaffold via
  `POST /config/write` (409 if the id was taken meanwhile ⇒ nothing else is written) and `POST /runs` to
  register it — then the one graph `PUT`. The draft's tab survives a re-list although the id is not listed
  yet. One draft at a time. ⚠ The preview's `integrity` list is ALWAYS empty for a pipeline —
  `ComponentIntegrity` judges only dataset/query/widget/dashboard/reconciliation — so the editor shows a
  pipeline draft's references as **not checked**, never as clean; Validate and the save gate judge them.
  🔴 The bundle kind `authored-pipeline` reads/writes `PipelineStore` (`<root>/pipelines/`) server-side,
  while this editor (and the UI's `loadAll`) work on the REGISTERED `*_pipeline.toon` — a backend export of a
  registered-only pipeline reports it `missing`, and a write-through import lands in the authored store. The
  draft path is unaffected (its Save is the editor's own route), but the seam is open.
* ⛔ **No `enabled:false` stamp** — a Dataset/Widget/Dashboard has no inactive meaning; a stamped write-through
  would be visible, resolvable and counted while carrying a key nothing reads.

The UI side (one derivation `deriveRefs`, one format, every surface — Settings workbench + editor/library
transfer menus) lives in the frontend bundle. Design history: `docs/archived-documents/plans-archive/`
(`metadata-network-design.md`, `transportability-plan.md`, `metadata-bundle.md`).
