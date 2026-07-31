# Onboarding ↔ Pipeline split — contract vs processing

> **Status: DESIGN — direction approved by the operator 2026-07-30; no build started.**
> Decisions D-A–D-C pinned below; slices S1–S6. Standing UI mandate for every slice
> (operator, 2026-07-30): **reuse shared components even when they need small changes** —
> never duplicate similar functionality; the reuse map is §5.

## 1. Why (the operator's observation, 2026-07-30)

Stream Onboarding and Pipeline creation read as the same act. They are not — but the product lets
one word span two concepts:

1. **Stage-1 ingest** (`<name>_pipeline.toon`) — what onboarding authors: Collector + Grammar +
   Schema + output store. This *is* "defining metadata for external data"; its product is a
   governed, typed, partitioned Table with dedup/quarantine/file-lineage.
2. **The authored processing graph** (`*_flow.toon`, the Pipelines editor) — parse/transform/sink
   nodes, run as jobs, step-level provenance.

The **Enrichment stage inside onboarding is the overlap made visible**: business transformation
(reference joins + SQL) living in the metadata-declaration flow, persisted as a third bespoke
config kind (`_enrich.toon`) that belongs to neither plane.

## 2. Target model (approved direction)

- **Onboard = declare the contract with the outside world.** Reach (Connection/Collector) → shape
  (Grammar) → meaning (Schema, keys, partitioning) → landing (Table **+ auto-registered Dataset**).
  Onboarding *ends* at "typed, queryable, governed." No business logic here.
- **Pipeline = processing over landed data.** Enrich / filter / aggregate / derive — reads
  Datasets/stores, writes derived stores. No outside-world concerns here.
- **Jobs = tasks.** A pipeline is dataflow; a job is a task; pipeline runs are scheduled or
  event-triggered *via* the job plane.
- **The handoff artifact is the Dataset.** Lineage already bridges the planes on the store name
  (`GET /lineage?store=` — ingest file-lineage ⟷ flow step-provenance).

## 3. Engine facts (verified in source 2026-07-30 — the model is mostly already true)

- **Jobs already have the event trigger**: `on_pipeline` subscribes to the `BatchEventBus`,
  matches `SUCCESS` + pipeline name, and `submit()`s off-bus (deadlock-safe) —
  `okf/backend/control-plane/jobs.md`. A Stage-2 pipeline can already run per committed batch.
  **No new trigger machinery is needed.**
- `PipelineBatchSignal` emits canonical `pipeline.batch.committed|failed` Signals, and Triggers v2
  (`on_signal:` + `when:` + `bind:`) exists — a second, richer trigger path if the plain one is
  ever too coarse.
- `EnrichJob` (`inspecto-engine/…/job/EnrichJob.java`) already runs an enrichment as a job (full
  recompute) **and publishes a chain `BatchEvent`** so downstream jobs/enrichments cascade — the
  migration's engine seams exist.
- ⚠ **The real migration question is semantics, not triggering**: the registered
  `EnrichmentService` path runs per committed batch; `EnrichJob` is a full recompute. Moving
  enrichment authoring to the pipeline plane must not silently turn incremental cost into
  full-recompute cost. Verify `EnrichmentService`'s incremental contract before S4 builds.

## 4. Decisions pinned

- **D-A — the Enrichment stage becomes a template over the pipeline plane, not a third config
  kind.** The guided pane (by-name reference bindings + transform SQL) is kept as UX, but its Save
  authors a **standard Stage-2 pipeline** seeded with source = the stream's store, trigger =
  `on_pipeline`, output = the space's `enriched/` convention. Existing `_enrich.toon` files are
  **grandfathered — no forced migration**: the pane keeps reading them, `POST /enrichment`
  registration stays for them, and new streams simply stop creating them.
- **D-B — reuse the `on_pipeline` job trigger.** No engine trigger work. The open question is the
  incremental-vs-recompute semantics above, owned by S4.
- **D-C — vocabulary (GLOSSARY, binding): "Pipeline" = the processing graph only.** The Stage-1
  ingest config stops being user-facing "Pipeline": a Stream *has* an ingest contract; users
  *build* Pipelines on top of it. Rollout is **UI-first** per GLOSSARY §13 (Catalog
  Streams/References column, onboarding shell copy, USER_GUIDE §4.3), with a GLOSSARY row +
  touchpoint entries landing in S5. **Backend identifiers are out of scope** — `pipelineName`,
  `_pipeline.toon`, `BatchEvent.pipeline()` all stay; identity churn is forbidden (the
  identity/display split, BACKLOG §4, just made *names* safe to change — do not re-key anything).

## 5. UI reuse map (the operator's mandate — reuse even when it needs a small change)

| Need | Reuse | Change needed |
|---|---|---|
| Dataset/store picking (3+ hosts) | the enrichment pane's reference picker → promote to a shared picker in `inspecto/components/` | generalize its filter (today: pipeline-produced References minus self) to any catalog store/Dataset, with kind + Draft/Live badge |
| Row previews | `<inspecto-data-table>` tiers | none — the tier system is the contract (pro only where exploring rows is the point) |
| Transform SQL authoring | the enrichment pane's CodeMirror block | extract standalone only if S4 needs it outside the pane |
| Grammar/options forms | `<inspecto-schema-form>` + `fieldSpecsToAttributes` | none |
| Parser preview | Parser dialog + `app-parser-tree` (already shared) | none |
| Create-with-inline-duplicate-check | the `OnboardingCreateDialog` pattern | reuse the pattern for "Create pipeline from this Stream" |

## 6. Slices (build order — S1–S3 independently shippable, each closes a recorded gap)

- **S1 — auto-register the Dataset at go-live. ✅ SHIPPED 2026-07-30.** The Publish pane's
  activate also writes a `dataset` component (physicalRef = the stream's normalized name = the
  store) via the shared `ComponentsService` (not studio's `DatasetsService` — cross-feature import
  ban). Idempotent by physicalRef (any dataset already pointing at the store wins, whatever its
  id); failures downgrade to a warning with the manual recipe; Streams only (a Reference's store
  is consumed by name and carries system columns). Closed "the Stream→Dataset hop is entirely
  manual" (BACKLOG §4 *Catalog lifecycle*).
- **S2 — the shared store/Dataset picker** (reuse map row 1), adopted by: the Dataset editor's
  source control (replaces `SAMPLE_SOURCE_NAMES`), the pipeline editor's source nodes (replaces
  free-text store names), and later the S4 template.
- **S3 — the bridge affordance: "Create pipeline from this Stream."** Catalog row action + a
  go-live-pane link → the Pipelines editor with a pre-seeded source node.
- **S4 — the enrichment template (D-A).** The enrichment pane's authoring UX relocated/reused in
  the pipeline plane, authoring a standard flow; the onboarding stage keeps a thin entry that
  opens it. Gate: the semantics verification in §3 first.
- **S5 — copy & vocabulary pass (D-C).** Catalog, onboarding shell, USER_GUIDE §4.3, GLOSSARY row
  + touchpoints.
- **S6 — retire the stage** (only after S4 soaks): the Enrichment stage drops from the Stream rail
  for new drafts; readiness chips and the "Ready" computation adjust.

## 7. Non-goals

- **No editor unification.** The guided rail is the right tool for declaring a contract (linear,
  sample-threaded, resumable); the node graph is the right tool for composing transforms.
- **No backend rename** of Stage-1 identifiers; no identity churn (D-C).
- **No forced `_enrich.toon` migration** (D-A).
- **No new trigger machinery** (D-B).

## 8. Verification (per slice, when built)

UI slices: the angular-ui gate (`lint:tokens` + full `ng test` + production build) + a live
offline walk (S1: go-live → the Dataset appears in Catalog ▸ Datasets; S3: the editor opens
seeded). Any new route: the endpoint skill (real-HTTP test class, every gate). Engine touches
(S4 semantics): full reactor per build-verify, baseline re-derived never quoted. Commits per
release-workflow — `feat:` → master only; no push without an explicit ask.
