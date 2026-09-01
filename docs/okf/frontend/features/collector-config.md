---
type: Feature
title: Collector configuration (the one surface)
description: The single shared surface and single store behind both collector-authoring screens.
resource: inspecto-ui/src/app/inspecto/collector/collector-config.component.ts
tags: [feature, collectors, acquisition, onboarding, pipelines, connections]
timestamp: 2026-08-04T00:00:00Z
---

# Collector configuration

One surface authors a pipeline's `collector:` block: the [pipeline editor](pipeline-editor.md)'s
**`acquisition`** Step definition drawer (also reached through guided mode's Collect chip — the
onboarding stage-rail shell that used to host a second screen was deleted 2026-08-16, see
[Onboarding](onboarding.md)).

Historically two screens rendered it; they always shared the store, the model, the spec table, the
form renderer and the engine (W2/U-D, 2026-07-31), and the chrome/write-route/node-model gap was
unified 2026-08-04. The per-*block*-not-per-*node* rules below date from that two-adopter era and
still hold — the shared table must stay correct for any future second adopter.

## One store, one spec table

Both write `collector:` in `spaces/<space>/config/<name>_pipeline.toon`, parsed into
`PipelineConfig.Collector`, executed by `CollectorProcessor` → `CollectorConnectors.forConfig`.
Both render `COLLECTOR_ATTRIBUTES` (`inspecto/component-model/collector-attributes.ts`) — **the**
table, asserted by object identity on both sides so a forked copy fails the suite.

## One component — `<inspecto-collector-config>`

`inspecto/collector/collector-config.component.ts`: the **Local inbox | Connection | Dataset** mode
toggle, the schema form, Test connection, create-a-Connection in place (shared
[ConnectionFormDialog](connections.md)), and the derived-connector readout. **No write path** —
hosts read `value()` / `resolveConnector()` and persist through their own route, because the two
persisted shapes genuinely differ: a `collector:` block vs. a node's raw config plus a
`use: connection/<id>` binding.

- ⚠ **`connector` is never asked, always derived** (local ⇒ `local`, else the picked Connection's own
  type). `CollectorConnectors.forConfig` dispatches on `collector.connector` while handing that
  factory the profile named by `collector.connection`, and never checks the two agree — so one place
  must decide both. A hand-authored non-local connector with no Connection is *grandfathered*, not
  destroyed.
- ⚠ **Un-binding a Connection is the mode toggle, not blanking the picker.** An empty picker while
  still in Connection mode is refused; a Connection-less non-local collector is the state that used
  to fail at run time.
- ⚠ **An unreachable Connections service is not a verdict.** `connections.list()` degrades to `[]` on
  error, which reads exactly like a list that answered and lacks the id — so the ghost-id refusal used
  to fire on an unchanged, previously-valid node whenever the service was down. The component tracks
  `loading | ok | failed` separately from the array: only `ok` licenses *"not a saved Connection"*, and
  under `failed` the id the node was **loaded with** keeps its stored connector. A *newly picked* id is
  still refused there — there is nothing to derive its connector from — with a distinct "could not be
  loaded, retry" message, so the guard is narrowed, not weakened.
- ⚠ **A host swapping the spec list at runtime must carry the live values across the swap.**
  Reassigning `<inspecto-schema-form>`'s `specs` rebuilds every control from its declared default,
  so the mode toggle silently wiped the form until this component started re-seeding.

## Dataset mode — consuming another Pipeline's output (UI-S7, 2026-08-28)

The third mode authors the **dataset entry** the engine shipped in ELT Phase 3 S3c-2: `collector:
{connector: dataset, dataset: <id>}`, resolved to the producer's snapshot dir fresh at every acquire
cycle by the `dataset` connector scheme, which copies new parquet snapshots into this pipeline's
inbox. See [Pipelines](pipeline-editor.md) and `okf/backend/engine/`'s S3 concepts.

- The mode owns its field the same way Connection does: `dataset` is asked **only** in Dataset mode,
  `connection` only in Connection mode, and `connector` stays **derived** (Dataset mode ⇒ `dataset`).
- ⚠ **`post_action__*` is hidden in Dataset mode.** The dataset connector **forces RETAIN** — a
  consumer must never delete or move a producer's snapshots, whatever its config says — so offering
  the knob would author config the engine silently ignores (the written-but-never-read shape).
- Resolve **fails closed** with no Dataset picked, which is the engine's own paired gate
  (`PipelineConfigParser` refuses `dataset` without `connector: dataset` and vice versa) moved up
  front, rather than discovered at run time.

### The trigger half

The entry node also carries `trigger__on` / `trigger__from` / `trigger__coalesce`, authoring
`{type: event, on: dataset, from: datasets/<id>, coalesce: …}` — the S3b trigger the `dataset.write`
Signal fires. Two rules:

- ⚠ **`trigger.type` is DERIVED, never asked**: picking an event `on` writes `type: event`, and
  clearing it withdraws the derived type. `PipelineTrigger.of` only reads `on`/`from` under
  `type: event`, so an `on:` under a schedule type is config nothing reads.
- ⚠ **The modeled trigger leaves are form-authoritative.** `buildConfiguredNode`'s deep merge is
  root-granular, so without deleting a blanked leaf explicitly a cleared `on`/`from` would be
  resurrected from the stored value on every save.
- `on: commit` (an upstream Pipeline commit) is deliberately **not** in the form — it stays an
  Additional-config key rather than a second event vocabulary to keep in step.

## One write route — `POST /config/patch`

Onboarding's stage saves were a wholesale file replace after a **client-side** merge: a stale-read
clobber race. `POST /config/patch` (`ConfigWriteRoutes.java`) decodes the stored TOON, deep-merges the
posted block server-side (maps recurse, scalars/lists replace, JSON `null` **deletes** a key),
validates the whole merged draft, and writes atomically — same response shape as `/config/write`,
so findings routing is unchanged. Every onboarding stage got the fix from that one route.
See [Config](config.md).

The graph save (`PUT /pipelines/{name}/graph`) keeps merging through `PipelineEditable.lower(g,
existing)` — deliberately **not** rerouted, which would put two writers on one operation.

⚠ TS delete markers must be `null` on the wire (`nullifyDeletes` in `component-model/flat-keys.ts`) —
JSON drops `undefined`.

## Dedup belongs to acquisition (the `transform.dedup.fingerprint` removal)

File duplicate detection executes **inside the `CollectorProcessor` poll cycle** — `ledgerFilter`
reads `collector.duplicate` before a file is ever collected. `transform.dedup.fingerprint` therefore
never had a runtime of its own: `PipelineLift` synthesized it, `lower()` dissolved it back into
`collector:`, and `PipelineCompiler` emitted no step for it. Modelling it as a transform told the
operator the check happens *after* collection, and its node overlay silently beat a `duplicate.mode`
typed on the acquisition node (the D9 tripwire).

It was removed 2026-08-04. `duplicate` and `incremental` ride the acquisition node, where the engine
reads them; a stored graph carrying a fingerprint node is refused at save with `UNSUPPORTED_NODE`.

⚠ **`transform.dedup.marker` is a different thing and was not touched** — it seeds real
`processing.duplicate_check` settings. Only `gap_detection` remains a collector-block key owned by a
node other than acquisition (`NOT_ACQ_OWNED` in `PipelineEditable`).

## At rest

`collector.connection` is the persisted form; `use: connection/<id>` is **edit-time only** —
synthesized by `PipelineLift`, lowered back by `PipelineEditable`. Runtime reads only
`collector.connection`. Presenting it as a binding on both surfaces needed no migration.
