# Unify collector configuration: onboarding Collection stage ↔ pipeline acquisition node

> **SHIPPED and ARCHIVED 2026-08-04 — provenance only, not current.** All slices landed
> (`5951a06b` · `438f8c09` · `9eba0e9a` · `61dc8280` · `9ff462e6` · `60193cd4`).
> As-built knowledge: [`docs/okf/frontend/features/collector-config.md`](../okf/frontend/features/collector-config.md).
> Open items moved to [`docs/BACKLOG.md`](../BACKLOG.md) §4 ("Collector config").
>
> Two departures from the plan as written, both deliberate:
> - **Slice 1's dedup split was superseded by slice 1b** (operator decision, grounded mid-flight):
>   `transform.dedup.fingerprint` had no runtime, so it was removed rather than fed, and both
>   surfaces render the whole table. The shared-table move stands; only the subset derivations went.
> - **No `/design` gallery entry.** The gallery holds design-system primitives; this is a domain
>   composite that injects `ConnectionsService`, exactly like `<inspecto-enrichment-editor>`, which
>   is likewise absent. Recorded in BACKLOG so it is a decision, not an oversight.
>
> One bug was found in the extraction and fixed with the shared component: reassigning
> `<inspecto-schema-form>`'s `specs` rebuilds every control from its declared default, so the mode
> toggle silently discarded everything typed so far.

## Context

The onboarding route `/catalog/onboard/<name>/collection` and the pipeline editor's `acquisition` node configure the same thing. The **store, model, spec table, form renderer, and engine are already unified** (W2/U-D, 2026-07-31): both author the `collector:` block of `spaces/<space>/config/<name>_pipeline.toon`, parsed into `PipelineConfig.Collector` ([PipelineConfig.java:289](inspecto-etl/src/main/java/com/gamma/etl/PipelineConfig.java)), rendered from `COLLECTOR_ATTRIBUTES` ([collector-attributes.ts:16](inspecto-ui/src/app/inspecto/component-model/collector-attributes.ts)) via the shared `<inspecto-schema-form>`, executed by `CollectorProcessor` → `CollectorConnectors.forConfig`.

Four divergences remain; user decisions (2026-08-04):

1. **Dedup fields** (`duplicate__*`): ~~follow pipeline, remove from the collection pane~~ **REVERSED 2026-08-04 (operator decision, grounded)**: file duplicate detection executes inside the `CollectorProcessor` poll cycle (`ledgerFilter` reads `collector.duplicate`; incremental/gap-detection likewise) — there is no post-collection dedup runtime. `transform.dedup.fingerprint` is pure graph-editor fiction (`PipelineLift` synthesizes it; `lower()` dissolves it back into `collector:`; `PipelineCompiler` emits no step). → **Fold dedup into the acquisition node and REMOVE the fingerprint node** (slice 1b): both surfaces render the full `COLLECTOR_ATTRIBUTES`; drop `TRANSFORM_DEDUP_FINGERPRINT` from `BuiltinNodeType`, `NodeAttributes`, `PipelineLift`, `PipelineEditable` (`LOWERABLE`/`NOT_ACQ_OWNED` — the acquisition node carries `duplicate`/`incremental` directly), `PipelineCompiler`; regenerate `node-attributes.contract.json`; mirror in `mock/pipeline-editable.ts` + the palette port. A stale graph posting a fingerprint node hits the existing `UNSUPPORTED_NODE` 422. `transform.dedup.marker` is NOT touched — it seeds real `processing.duplicate_check` settings. Slice 1's subset derivations are removed again (superseded, not reverted — the shared-table move stands).
2. **Connection semantics**: presented as a binding on both surfaces, but **at rest stays `collector.connection`** — grounding showed `use: connection/<id>` is edit-time-only (synthesized [PipelineLift.java:127](inspecto-etl/src/main/java/com/gamma/etl/PipelineLift.java), lowered back [PipelineEditable.java:247](inspecto-etl/src/main/java/com/gamma/etl/PipelineEditable.java)); runtime reads only `collector.connection` ([CollectorConnectors.java:28](inspecto-acquire/src/main/java/com/gamma/acquire/CollectorConnectors.java)). No at-rest change, no migration.
3. **One UI component** for the collector chrome (mode toggle, connection picker/test/create, derived connector, schema-form) — today duplicated between pane and dialog.
4. **One server-side-merged block write**: onboarding's `POST /config/write` is a wholesale file replace after a *client-side* merge ([ConfigRoutes.java:161](inspecto/src/main/java/com/gamma/control/ConfigRoutes.java), [onboarding-state.service.ts:204](inspecto-ui/src/app/modules/admin/catalog/onboarding/onboarding-state.service.ts)) — a stale-read clobber race. Graph save already merges server-side via `PipelineEditable.lower(g, existing)` and **keeps doing so** — rerouting it would put two writers on one operation.

## Slices (each committable to master per release-workflow)

### Slice 1 — shared spec derivation; dedup off the collection pane
- [collector-attributes.ts](inspecto-ui/src/app/inspecto/component-model/collector-attributes.ts): add `COLLECTOR_DEDUP_ATTRIBUTES` (the `duplicate__*` subset) and `COLLECTOR_ACQUISITION_ATTRIBUTES` (the rest) — moving the D9 derivation from the pipelines feature into shared `component-model` (onboarding cannot cross-import a feature). Export via `component-model/index.ts`. Specs remain the same shared objects (derivation, not fork).
- [node-attributes.ts:94-105](inspecto-ui/src/app/modules/admin/pipelines/node-attributes.ts): delete local `DEDUP_FINGERPRINT_ATTRIBUTES`/`ACQUISITION_ATTRIBUTES`, import the shared ones; **rewrite the D9 header note** (lines 80-92) to record the new state. Served/mock contract stays byte-identical (same spec objects) — no backend change.
- [stage-attributes.ts:21-22](inspecto-ui/src/app/modules/admin/catalog/onboarding/stage-attributes.ts): `'collection'` → `COLLECTOR_ACQUISITION_ATTRIBUTES`; document the no-dedup-surface consequence at the lookup.
- Verify: UI `test:ci` (add assertion: collection stage specs contain no `duplicate__*` key), `lint:tokens`, build — via verify-runner.

### Slice 2 — `POST /config/patch` (server-side merged block write, backend)
Chosen over merge-flags on `/config/write` (keeps existing callers byte-identical) and over a pipeline-specific blocks route (type-generic ⇒ every onboarding stage gets the fix from one route).
- Semantics: body `{type, name, patch, subdir?}`; decode existing TOON; deep-merge (maps recurse, scalars/lists replace, JSON `null` deletes key); validate the whole merged draft; atomic write. Response shape = `writeConfig`'s (`type/written/path/name/bytes/findings`) so client findings-routing is unchanged.
- [ConfigRoutes.java](inspecto/src/main/java/com/gamma/control/ConfigRoutes.java): extract writeConfig's gate+write tail into a shared private helper; per the `endpoint` skill gate order: write-root 503 → unknown type 404 → bad body 400 → safeName 422 → subdir jail 403 → missing target 404 (patch needs an existing file; "use /config/write") → ERROR findings 422 (`written:false`) → atomic `ConfigCodec.toToon` write. Small package-private `deepMerge` (null-deletes) in ConfigRoutes — no new util class.
- New real-HTTP `ControlApiConfigPatchTest` modeled on `ControlApiConfigWriteTest`: one test per gate + (a) patching `{collector:…}` leaves `parsing`/`output` byte-identical (anti-clobber regression), (b) `connection: null` deletes the key, (c) unmodeled keys survive.
- Verify: `mvn -o test -pl inspecto`, then full `mvn -o clean test` via verify-runner.

### Slice 3 — onboarding saves through the patch route
- `ConfigService.patch(...)` in `inspecto-ui/src/app/inspecto/api/config.service.ts`; [onboarding-state.service.ts saveBlock:204](inspecto-ui/src/app/modules/admin/catalog/onboarding/onboarding-state.service.ts) POSTs the patch instead of client-merging + overwrite. Keep the optimistic `config.set(mergeBlock(...))`. Delete markers: map `undefined` → `null` before POST (JSON drops `undefined`) — tiny helper next to `clearMissingRoots` in `component-model/flat-keys.ts`.
- Findings routing (`stageForPath` prefix buckets) is unchanged — the route validates the whole merged draft and returns the same result shape. All stage panes save via `saveBlock`, so the stale-read clobber dies for every stage with zero pane edits.
- Mock parity: implement `/config/patch` in `mock/handlers/onboarding.handler.ts` with identical strictness (404 on missing file, null-deletes), pinned in its handler spec.
- Verify: UI `test:ci` + SMOKE onboard (draft → save collection → re-read shows other blocks intact).

### Slice 4 — extract `<inspecto-collector-config>`; adopt in the Collection pane
- New `inspecto-ui/src/app/inspecto/collector/collector-config.component.ts` (standalone, OnPush, signals, per angular-ui skill): local/connection toggle, schema-form over passed specs (connection spec filtered in local mode), Test connection + result alert, New connection via shared `ConnectionFormDialog` (gated `canAuthorWorkbench()`), derived-connector readout, `connectionOptionLoader`. Host API mirrors schema-form: inputs `specs/initial/storedConnector`; `(submitted)`; methods `validate()/isDirty()/value()/resolveConnector()/markPristine()`. **No write path** — hosts own persistence. Component spec incl. axe assertion.
- [collection-pane.component.ts](inspecto-ui/src/app/modules/admin/catalog/onboarding/collection-pane.component.ts) becomes a thin host: save = validate → resolveConnector → `clearMissingRoots(nestKeys(value()), roots)` + `connector` → `saveBlock({collector})`. All current logic (mode derivation from stored config, grandfathered hand-authored connector, dirty check) moves into the shared component.
- Update angular-ui SKILL adopters list + `/design` gallery.
- Verify: UI `test:ci`, `lint:tokens`, preview smoke of the collection stage round-trip.

### Slice 5 — adopt in the acquisition node dialog
- [node-config.dialog.ts](inspecto-ui/src/app/modules/admin/pipelines/node-config.dialog.ts) acquisition branch (`isAcquisition` :303-359, save :531-537): render the shared component instead of the generic schema-form section; seed from schema-known config keys + the `use:`-lifted connection; `storedConnector` from `config['connector']`. Save keeps existing nestKeys/merge coercion, now **also writes the derived `connector`** (the presentation half of decision 2), and lifts connection onto `use:` exactly as today. Free-form "Additional config" stays; exclude `connector` from free-form seeding. Dialog gains Test/New-connection affordances for free.
- No mock change (persisted shape identical); `NodeConfigNameContractTest` unaffected (`connector` is not a spec key).
- Verify: GAUNTLET (full reactor tests + UI lint/test/build) + preview smoke: edit acquisition node → save → file's `collector.connector` equals the profile's connector; `use: connection/<id>` round-trips.

### Close-out
- Distill as-built facts into the matching OKF concept; move plan to `docs/archived-documents/plans-archive/`; update `docs/INDEX.md`; `graphify update .`; update D9-related memory/comment trail.

## Reused utilities
TS: `flattenBlock/nestKeys/clearMissingRoots/mergeBlock/KEY_SEP` (`component-model/flat-keys.ts`), `connectionOptionLoader`, `ConnectionFormDialog`, `InspectoSchemaFormComponent`. Java: `ConfigCodec`, atomic write helpers, `ConfigSafetyValidator`/`ConfigSpecs`, existing writeConfig finding helpers.

## Verification (end-to-end)
1. Per-slice: verify-runner (`mvn -o clean test`, UI `test:ci`/`lint`/build).
2. After slice 5: SMOKE flows — onboard a draft, save collection with a Connection; open the same pipeline in the editor, confirm the acquisition node shows identical values; save from the editor; re-read the TOON — `collector.*` consistent, `duplicate/incremental/gap_detection` untouched, no clobber of `parsing/processing/output`.
