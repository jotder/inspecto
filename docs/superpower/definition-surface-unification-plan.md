# Definition-Surface Unification — ONE way to define a pipeline

**Status: ACCEPTED — P0 complete 2026-08-13 (all §11 questions resolved by operator).** Owner: UI + engine. Created 2026-08-13.

---

## 1. Problem & intent

Today the same objects (collection, parser/grammar, schema, enrichment, output) are definable in
**two hosts** with different look-and-feel, parameters, and save semantics:

- `/catalog/onboard/<name>/<stage>` — wizard panes, immediate per-block `POST /config/patch`.
- `/pipelines` editor — MatDialog node dialogs, in-memory patch + deferred whole-graph `PUT`.

The earlier unification (W0–W5, archived: `docs/archived-documents/plans-archive/onboarding-pipeline-unification.md`)
deliberately unified only the **inner editors** (`<inspecto-collector-config>`, `<inspecto-grammar-editor>`,
`<inspecto-enrichment-editor>`, `<inspecto-schema-form>`, shared `AttributeSpec` tables) and kept two hosts
because the persisted shapes differ. **This plan reverses that host decision**: the onboarding wizard was
built for the Stage-1 engine only (it authors 5 of the 12 lowerable node types — confirmed
`docs/okf/frontend/features/onboarding.md:4`, `onboarding-state.service.ts:37-52`), the pipeline editor is a
superset, so the editor becomes the **single definition surface** and the wizard is retired into a guided
mode over it.

**Target user capability** (the bar every slice is measured against):

> Define a complex grammar (properties **and** plugin file), create a schema and its mapping as properties
> *synchronized* from the parse result, see the live table and test at every step, and use SQL to select /
> verify while mapping — all in one place, one visual language, one save model.

## 2. Binding decisions proposed (discuss, then pin)

| # | Decision | Consequence |
|---|----------|-------------|
| **D1** | **One host: the `/pipelines` editor.** Node dialogs are replaced by *definition drawers*; the onboarding wizard route becomes a guided checklist over the same editor, then a redirect. | Reverses W5's "two hosts" decision — record the reversal in the OKF when shipped. |
| **D2** | **One save semantics: deferred.** Drawers are pure (`@Input` value / `@Output` change); the editor owns persistence — whole-graph `PUT` plus ordered *companion write-adapters* (schema file, enrichment config, segment schemas) executed by the host at Save time. | Kills the two-writers hazard (`collector-config.md:58`) instead of importing it. Panes stop injecting `OnboardingStateService`. |
| **D3** | **File dedup is Collection properties, not a node.** `collector.duplicate.*` (already in `COLLECTOR_ATTRIBUTES`) + marker-file dedup (`processing.duplicate_check.*`) fold into the Collection drawer as an "Already-seen files" property group. `transform.dedup.marker` leaves the palette. | Needs the strict-lower fix in §8-H1 first, or graph saves silently delete marker dedup. **Record dedup (`transform.dedup`) is untouched — it stays a Stage-2 node** per the 2026-08-11 decision. |
| **D4** | **Schema & mapping are *sync properties* of the parser output.** One derivation chain, server-side: parse preview → `POST /config/suggest/schema` → editable field/mapping grid → cast preview. The client-side `suggestTypes()` fork in the onboarding pane is retired. | One inference implementation (`SchemaSuggest.infer`), and mapping rules come with it for free (`ConfigRoutes.java:576-580`). |
| **D5** | **Sample is editor-level state**, not wizard state. A `DefinitionStateService` scoped to the editor tab holds `sample / parsePreview / schemaPreview`, so every drawer tests against the same thread. | Replaces `OnboardingStateService`'s preview slots; stage rail/lifecycle logic is NOT carried over. |

## 3. What already exists (grounded — build on it, don't rebuild)

- **Shared inner editors + spec tables** — both surfaces already render collector/parser/enrichment/output
  through the same components and `COLLECTOR_ATTRIBUTES`/`OUTPUT_ATTRIBUTES`; pipelines additionally honours
  **server-published** specs from `GET /pipelines/node-types` (`node-config.dialog.ts:47-56`). The unified
  drawers must keep the server-published path (onboarding's client-only `stageAttributesFor()` is the
  regression to avoid).
- **Query-over-sample already works**: the parsed table is `<inspecto-data-table tier="pro" sourceName="parsed">`
  — seeded `SELECT * FROM "parsed"`, client-side AlaSQL run + `(runOnServer)` hook (`data-table.component.ts:49-59`).
- **Server SQL**: `POST /db/query` (SqlGuard-checked, sandboxed, store registered as a view —
  `DbBrowserRoutes.java:142-200`).
- **Preview endpoints**: parsing (`POST /config/preview/parsing`), schema cast (`POST /config/preview/schema`),
  suggest (`POST /config/suggest/schema`), enrichment (`POST /enrichment/preview`), plugin parser
  (`POST /parsers/{id}/preview`), dry-run per-node counts (`POST /pipelines/authored/{id}/dry-run`, accepts a
  candidate graph so unsaved edits test fine), `sink.view` data (`GET /views/{name}/data`).
- **Drawer precedent**: the app's only true slide-over is `dashboard-drill-drawer.component.ts` (fixed right,
  `role="dialog"`); the editor's docks use `[inspectoSplit]`. There is **no shared drawer primitive yet** —
  this plan introduces one into the design system.

## 3b. The two surfaces today (screenshots, mock mode, pipeline `cdr_ingest`)

Captured 2026-08-13 from the offline dev server (`ng serve -c offline`, 1600×950). They make the split
visible: the *forms* are identical (shared inner editors), only the hosts and save affordances differ.

**Pipelines editor** — Recipe mode (step cards + Guarantees panel; note "File dedup — not configured"
is a *third* place dedup surfaces today) and Canvas mode (palette carries both `Dedup (marker)` and
`Dedup (record)` nodes):

![Pipelines editor — Recipe mode](assets/definition-surface/1-pipelines-editor-recipe.png)
![Pipelines editor — Canvas mode](assets/definition-surface/2-pipelines-editor-canvas.png)

**Pipeline dialogs** — grammar ("Edit Grammar · parse") and collector ("Configure · acq"; the collapsed
"Additional config" key/value escape hatch — where `poll` lives — has no pane equivalent):

![Grammar dialog](assets/definition-surface/3-pipelines-grammar-dialog.png)
![Collector dialog](assets/definition-surface/4-pipelines-collector-dialog.png)

**Onboarding wizard** — same collector/grammar forms rendered full-page with per-block Save buttons and
the stage rail. The Schema & Mapping shot caught a live instance of hazard §9-2: the `foreignManaged`
lock ("Schema managed elsewhere") fired because `cdr_ingest_schema.toon` doesn't match the guided
editor's convention path — exactly what the unified schema pane must eliminate:

![Onboarding — Collection stage](assets/definition-surface/5-onboard-collection.png)
![Onboarding — Parsing stage](assets/definition-surface/5-onboard-parsing.png)
![Onboarding — Schema locked by convention mismatch](assets/definition-surface/5-onboard-schema.png)
![Onboarding — Dataset & Go-live stage](assets/definition-surface/5-onboard-publish.png)

Extra observation for D3: dedup currently shows up in **three** visual places (collector properties,
two palette nodes, the Guarantees row) — the plan should also state how the Guarantees "File dedup"
row maps onto the folded Collection-drawer group.

## 4. Component inventory (names + responsibility)

| Component | New/refactor | Responsibility |
|---|---|---|
| `<inspecto-definition-drawer>` | **new** (design system) | Shared right slide-over shell: title, kind icon, dirty badge, Apply/Discard footer, `guardDirtyClose`, resize grip, a11y (`role="dialog"`, focus trap). Generalized from `dashboard-drill-drawer`. Gallery entry + axe spec mandatory. |
| `DefinitionStateService` | **new** (editor-scoped) | Per-tab sample thread: `sample`, `parsePreview`, `schemaPreview`, per-drawer dirty registry. NO stage rail, NO lifecycle, NO save — replaces `OnboardingStateService`'s preview role. |
| `<inspecto-collection-pane>` | refactor of `collection-pane` + collector path of `node-config.dialog` | Collector properties (server-published spec, tiered), connection binding, **"Already-seen files" group** = `collector.duplicate.{mode,on_change}` + `processing.duplicate_check.{enabled,marker_extension,retention_days}` (D3). Pure: `@Input() value` / `@Output() valueChange`. |
| `<inspecto-parser-pane>` | refactor of `parsing-pane` + `grammar-editor.dialog` | Grammar **properties + plugin file** in one pane; embedded sample rail; live parsed table (`tier="pro"` = SQL over sample); segments editor for ingestable plugin parsers (draft-only; writes happen via adapter at Save). |
| `<inspecto-schema-pane>` | merge of `schema-mapping-pane` + `schema-editor.dialog` | Sync-properties grid (D4): derive/re-sync from parse preview via server suggest; per-field include/name/selector/type(+description/unit/classification); mapping rules; cast preview incl. **mapped-output rows** (needs §8-B1); "Validate types" + rejected-rows table. |
| `<inspecto-enrichment-pane>` | rehost of `enrichment-pane` | Enrichment editor + preview; companion `<name>_enrich` write moves into a Save-time adapter. |
| `<inspecto-output-pane>` | refactor of `publish-pane` minus go-live | Sink properties (`OUTPUT_ATTRIBUTES` server-published), partitions. **Go-live/activate is NOT a pane** — it becomes a pipeline-level toolbar action (activate + Dataset registration), because activation is lifecycle, not node config. |
| `<inspecto-definition-checklist>` | **new** (thin) | Guided mode: a compact stage strip (Collect → Parse → Schema → Enrich → Publish) rendered in the editor toolbar for pipelines that came from "Onboard"; each chip opens the matching node's drawer and shows its finding count. Replaces the wizard shell. |
| Write-adapters (host-side, plain fns) | **new** | Ordered Save-time writes the graph PUT can't express: (1) schema `.toon` write then `processing.schema_file` bind, (2) enrichment config write + `POST /enrichment` re-register, (3) segment schema files then `parsing.plugin` patch. Single choke point, unit-tested. |

## 4b. Finalized stage components (operator direction, 2026-08-13)

The operator fixed the component decomposition to **A. Collector → B. Parse (Extract) → C. Load** with
per-format parser icons. This section is the binding spec for the three drawers; config-key mappings are
grounded against the engine (`PipelineConfig.java`, `PipelineConfigParser.java`, `ParserRoutes.java`,
`TransformCompiler.java`).

### A. Collector drawer

| Property group | Config keys (grounded) | Notes |
|---|---|---|
| Source | `source.connector` = `local` \| connector module; `source.connection` → `*_connection.toon` profile | "Local inbox or Connection" toggle as today |
| Directories | local: `dirs.poll`; remote: connection-profile path | |
| Filter | `source.includes` / `source.excludes` (glob/regex lists, `PipelineConfig.java:343-350`) | supersedes legacy `processing.filePattern` |
| Remove after process | `source.post_action` → `onSuccess ∈ RETAIN\|DELETE\|MOVE\|RENAME\|TAG`, `archivePath`, `onUnsupported` (`PipelineConfig.java:559-581`) | default RETAIN |
| Frequency | `source.discovery` ∈ `poll`(default)/`watch` (watch = local only). ⚠ **No per-pipeline interval key exists** — the interval is service-level (`PipelineScheduler.pollIntervalMs`). | Backend item **B4** if per-collector frequency is wanted |
| Already-seen files (dedup) | `source.duplicate` + `processing.duplicate_check.*` | per D3; also `source.stability`, `source.incremental` in advanced tier |

### B. Parse drawer — one icon per parser under the PARSER palette category

Instead of one generic `parser` node + format tabs inside a dialog, the palette's PARSER category lists
**one icon per format**; each opens the same Parse drawer specialized to that format. All variants share:
sample load (file / paste into text area) → **Apply/Test** → parsed table (`tier="pro"`, SQL-queryable) →
rejected rows. **Consignment-generation config folds into this drawer** as an "Input batching" group:
`processing.batchMaxFiles`, `processing.batchMaxBytes`, `processing.batchOrder` (default `mtime` = arrival)
— grounded `PipelineConfig.java:69-72`, planned at collect time by `ConsignmentPlanner`. (Read-side
consignment addressing — Selector/watermark — stays engine-internal, not author-facing.)

| Icon | `parsing.frontend` / plugin | Grammar presented as | Extra |
|---|---|---|---|
| **Delimited** | `delimited` | properties: delimiter (comma/pipe/semicolon/custom), header, quote… (`csv_settings`) + output schema | |
| **Fixed length** | `fixedwidth` | **table of `{name, start, length}`** (`fixedwidth.fields`, `PipelineConfigParser.java:1016-1063`) + `min_record_length`, `binary`, `trim` + output schema | |
| **JSON / NDJSON** | `json` | path-notation field mapping (`:1104-1136`) | |
| **Text / regex** | `text_regex` | pattern with named capture groups (`:1137-1150`) | selectors must name a capture group |
| **ASN.1** | ParserPlugin `asn1` (NOT a `frontend` value) | `asn1.grammar` (X.680 module text; empty ⇒ structural TLV dump for unknown-vendor onboarding), `asn1.root_type`, `asn1.strictness`, header lengths | **flatten DSL does not exist yet** — see B5. Ingest goes through `parsing.plugin.ingester` = `Asn1RecordIngester` |
| **Custom** | `frontend: plugin` | **dropdown of deployed plugins from `GET /parsers`** (`{id, label, hierarchical, ingestable, ingesterClass, grammarSchema}`, `ParserRoutes.java:36-55`; ServiceLoader `META-INF/services/com.gamma.parse.ParserPlugin`) + plugin-specific grammar keys via `grammarSchema` | Save requires `ingestable:true`; hierarchical parsers without an ingester are **preview-only** by design (`ParserPlugin.java:27-33`) |
| XML | ParserPlugin `xml` | tree preview | preview-only until flatten (B5) |

Preview backing: `POST /config/preview/parsing` for frontends, `POST /parsers/{id}/preview` for plugins —
both exist today. The parser-plugin framework's P1–P3 (SPI, registry, routes, ASN.1 plugin) are **shipped
in code**; P4 (this UI adoption) is the open remainder — this plan absorbs it, and
`docs/superpower/parser-plugin-framework.md` should be closed into it.

### C. Load drawer (schema + map + table)

One drawer covering what today is split across the schema-mapping pane, the unbound schema-editor dialog,
and the sink dialog:

1. **Create schema** — derive from parsed sample (`POST /config/suggest/schema`, D4) or **import** an
   existing schema `.toon` / registry component.
2. **Map/transform (SQL-based)** — per-field mapping rules; `transformType: EXPR` already carries an
   **arbitrary DuckDB scalar expression emitted verbatim** (`TransformCompiler.java:80-129`; also `DIRECT`,
   `CONCAT_DT`, `FILENAME_DATE`). Per-row scalar only — aggregates/joins are Stage-2, not this drawer.
   This resolves open question §11-3: **the expression column is read-write in P4**, backed by EXPR.
3. **Table** — target format `output.format` ∈ **PARQUET | CSV only** (`OutputFormat.java:19-25`),
   partitions; live "mapped output" table (B1) + rejected rows, both SQL-queryable.

> **Vocabulary: RESOLVED 2026-08-13 — the stage is `Load`** (operator decision; "Sync" banned before
> first use). GLOSSARY §5 has the entry, §13 the rename-map row. The drawer component is
> **`<inspecto-load-pane>`** (merging this plan's `<inspecto-schema-pane>` + `<inspecto-output-pane>`
> rows in §4); Go-live stays a pipeline-level toolbar action outside Load.

### New/changed backend items from this decomposition

| # | Item |
|---|---|
| **B4** | Per-collector poll interval config key (today only service-level `PipelineScheduler.pollIntervalMs`) — or explicitly decide frequency stays service-level and the drawer shows it read-only. |
| **B5** | **Flatten DSL** for hierarchical parsers (ASN.1 records / XML trees → row sets). Deferred by the parser-plugin framework on purpose; required before ASN.1/XML/hierarchical-Custom icons can *Save* a load path rather than preview. ASN.1's record ingester covers the common CDR case in the interim. |
| **B6** | **DECIDED 2026-08-13: true per-format identity, end to end.** Each parser type is its own palette icon AND its own backend handling: a distinct node type (e.g. `parser.delimited`, `parser.fixedwidth`, `parser.asn1`, `parser.plugin`) dispatching through the parser framework (`com.gamma.parse.Parsers` + `ParserPlugin` SPI — which already wraps the 4 built-in adapters and plugins uniformly). Each type owns its grammar shape, its schema(s), and its complexities in isolation — no generic parser node, no format tabs. Rollout is **per-format, delimited first** (75%+ of real data is delimited files), then fixed-length, then ASN.1, then Custom — each slice isolates that format's handling before the next starts. |

## 5. The unified surface (wireframes)

### 5.1 Editor with definition drawer open (Parser)

```
┌──────────────────────────────────────────────────────────────────────────────────────┐
│ ▸ premium_cdr   [Collect ✓][Parse ●][Schema ○][Enrich –][Publish ○]   Save ▾  Go live │  ← toolbar + checklist chips
├──────────┬──────────────────────────────────────────────┬────────────────────────────┤
│ Palette  │                                              │ ≡ PARSER  cdr_parser   [×] │
│          │        ┌────────┐    ┌────────┐              │────────────────────────────│
│ Collect  │        │Collect │───▶│ Parse  │──▶ …         │ Properties  File/Plugin    │  ← tabs: grammar props + file
│ Parse    │        └────────┘    └────█───┘              │  frontend   delimited  ▾   │
│ Transform│                        (selected)            │  delimiter  ;              │
│ Sink     │                                              │  header     auto       ▾   │
│          │                                              │  … tiered, server spec …   │
│          │                                              │────────────────────────────│
│          │                                              │ SAMPLE  cdr_0813.csv  40 ln│  ← sample rail (editor-level)
│          │                                              │ raw → parsed·12c·38r·2 rej │
│          │                                              │┌──────────────────────────┐│
│          │                                              ││ SELECT * FROM "parsed"  ▶││  ← tier="pro" SQL over sample
│          │                                              ││ a_num │ b_ts │ dur │ ... ││
│          │                                              ││  ...  │  ... │ ... │     ││
│          │                                              │└──────────────────────────┘│
│          │                                              │          [Discard] [Apply] │
├──────────┴──────────────────────────────────────────────┴────────────────────────────┤
│ Validation (2) · Dry-run                                                              │  ← bottom dock unchanged
└──────────────────────────────────────────────────────────────────────────────────────┘
```

- Drawer replaces the right-dock Properties tab content when a node is opened for definition;
  the existing inspector summary remains the collapsed state. Apply = in-memory patch (D2);
  toolbar **Save** persists graph + runs write-adapters in order.

### 5.2 Schema & mapping as sync properties

```
┌ SCHEMA & MAPPING  premium_cdr_schema                                     [×] ┐
│  Derived from parse result        [⟳ Re-sync from sample]  drift: 2 fields   │
│──────────────────────────────────────────────────────────────────────────────│
│  ☑ │ name        │ selector │ type      │ mapping (expr)      │             │
│  ☑ │ a_number    │ 0        │ VARCHAR   │ a_number            │  = direct   │
│  ☑ │ start_ts    │ 1        │ TIMESTAMP │ start_ts            │             │
│  ☑ │ duration_s  │ 2        │ DOUBLE    │ duration_s          │             │
│  ☐ │ filler      │ 3        │ VARCHAR   │ —                   │  excluded   │
│  … search · type filter · sort · paginate (500-col ready)                    │
│──────────────────────────────────────────────────────────────────────────────│
│  [Validate types]   ok 36 · rejected 2                                       │
│  ┌ Mapped output (36) ─────────────┐  ┌ Rejected (2) ──────────────────────┐ │
│  │ SELECT * FROM "mapped"        ▶ │  │ row │ field │ value │ reason       │ │
│  │ a_number │ start_ts │ duration  │  │ 17  │ start_ts │ "n/a" │ TRY_CAST  │ │
│  └──────────────────────────────────┘  └────────────────────────────────────┘ │
│                                                     [Discard]  [Apply]        │
└──────────────────────────────────────────────────────────────────────────────┘
```

- **Sync** means: fields/selectors/types are *derived* from the parse preview by the server
  (`POST /config/suggest/schema` — one implementation, returns mapping rules too), the human edits on top,
  and a **drift indicator** shows when the parse result no longer matches the schema draft (renamed column,
  new column, type vote changed). Re-sync merges, never clobbers manual edits (include-flags and manual
  type overrides win).
- The "Mapped output" table is the currently-missing preview (§8-B1). It is `tier="pro"`, so SQL selection
  works while mapping ("query for selection/map").

### 5.3 Collection drawer with dedup folded in

```
┌ COLLECTION  sftp inbox                                                   [×] ┐
│  Connection   [ conn/telco-sftp  ▾ ]  (binds use: connection/<id>)           │
│  Path/glob    /inbox/**/*.csv                                                │
│  … tiered collector properties (server-published spec) …                     │
│──────────────────────────────────────────────────────────────────────────────│
│  ▸ Already-seen files (dedup)                                    [advanced]  │
│     Detect duplicates by   [ content ▾ ]      (collector.duplicate.mode)     │
│     When a file changes    [ reprocess ▾ ]    (collector.duplicate.on_change)│
│     Marker files           [☑ enabled]        (processing.duplicate_check.*) │
│       marker extension  .done     retention   30 days                        │
│──────────────────────────────────────────────────────────────────────────────│
│                                                     [Discard]  [Apply]       │
└──────────────────────────────────────────────────────────────────────────────┘
```

No dedup node in the palette; one property group, two config homes (collector block + processing block),
written together by the host.

> Screenshots of the current two surfaces are in §3b; the wireframes above are the target, not the present.

## 6. Functionality matrix (target)

| Capability | Where | Backing |
|---|---|---|
| Grammar properties + plugin file in one pane | Parser drawer, two tabs | existing `<inspecto-grammar-editor>` + `POST /parsers/{id}/preview` |
| Live parsed table while defining | Parser drawer sample rail | `POST /config/preview/parsing` (candidate config + sample_text) |
| SQL over the sample while defining | parsed table `tier="pro"` | AlaSQL client-side; `(runOnServer)` → `POST /db/query` for stores |
| Schema creation synced from parser | Schema drawer "Re-sync" | `POST /config/suggest/schema` (server, single implementation) |
| Mapping as properties | Schema drawer grid column | suggest returns `mapping.rules` (DIRECT) seeded; expressions editable |
| Test while mapping | Schema drawer "Validate types" | `POST /config/preview/schema` + **new mapped-rows response (B1)** |
| Query for selection/map | Mapped-output table `tier="pro"` | client AlaSQL; server path via B1 |
| File dedup with no visual node | Collection drawer property group | D3 + strict-lower fix H1 |
| Per-node counts on real-shaped rows | bottom dock Dry-run (unchanged) | `POST /pipelines/authored/{id}/dry-run` |
| Stage-2 steps (dedup/route/summarize/join) | palette nodes, same drawers | unchanged; Stage-2 boundary respected |

## 7. What gets retired

- `node-config.dialog.ts` and `grammar-editor.dialog.ts` (replaced by drawers). Their specs migrate, not die:
  every behavioural case is re-pinned on the drawer components before deletion.
- The onboarding **shell/wizard** (`onboarding-shell`, stage rail, `OnboardingStateService` lifecycle):
  `/catalog/onboard/:name/:stage` becomes a redirect into the editor with the checklist chip focused.
  The **create dialog** ("Onboard" button) survives — it seeds the draft and opens the editor in guided mode.
- Onboarding's client-side `suggestTypes()` inference fork (D4).
- `transform.dedup.marker` palette entry (D3). The *config keys* survive unchanged; only the node
  representation goes.
- The publish pane as a pane; go-live becomes a toolbar action with the same guard rails
  (`active:true` + Dataset registration, `canAuthorWorkbench`).

## 8. Backend work items

| # | Item | Why |
|---|---|---|
| **B1** | Extend `POST /config/preview/schema` to also return the successfully-mapped row set (bounded, e.g. ≤200 rows), not just `okCount/rejectedRows` (`ConfigRoutes.java:521-550`). | "Visualize table while mapping" has no backing today — only counts + rejects exist. |
| **B2** | Publish attribute specs for the marker-dedup keys (`processing.duplicate_check.*`) so the Collection drawer's dedup group is server-spec'd like everything else (`NodeAttributes.java:198-215` has no entry). | Server-published beats client tables (W4 lesson). |
| **B3** | Suggest-endpoint drift support: accept the current schema draft alongside `sampleRows`, return a field-level diff (`new/renamed/type-changed/missing`) instead of a full replacement. | Powers the drift indicator + non-clobbering re-sync in §5.2. |
| **H1 (hazard, must precede D3)** | `PipelineEditable.lower` strict mode deletes `processing.duplicate_check` + `dirs.markers` when no marker node is present (`PipelineEditable.java:398-400`). Change lowering so these keys are owned by the *collector-side* definition, not by node presence. | Otherwise retiring the node makes every graph save silently disable marker dedup. |
| **H2 (hazard)** | The onboarding create dialog silently injects `processing.duplicate_check` (BACKLOG `:248`, "surfaced by no stage pane"). Once the Collection drawer owns these keys, the injection must become visible/owned there. | Close the invisible-config hole while we're in the file. |

## 9. Known hazards & traps (from grounding — respect these)

1. **Two-writers**: never let a drawer write config while the graph tab is dirty. D2's pure-pane contract is
   the fix; adapters run only inside host Save. (`collector-config.md:58`.)
2. **`ConfigService.write` names files from `raw.name`**, not from an argument (`onboarding.md:72-79`) — the
   schema adapter must set `raw.name` deliberately; node id ≠ config name.
3. **`schema` identity is ambiguous** — a `ConfigSpecs` type (path-addressed `.toon`) *and* historically a
   registry component id; `platform-kinds.ts:42` vs `onboarding.md:60-66` are already in tension. The plan's
   schema pane binds via `processing.schema_file` (path) only; do not resurrect node-binds-schema-component.
4. **`RowShaper`/`where` trap** (`pipelines.md:185-195`): the editor saves the flat `*_pipeline.toon`; don't
   surface properties only the `*_flow.toon` runtime consumes. Bind every drawer field to the runtime that
   executes the file this editor actually saves.
5. **Server-published spec precedence**: an *empty* served `attributes[]` means "no schema", only *absent*
   falls back to client tables (`node-config.dialog.ts:47-56`). Keep that contract in the drawers; it is
   pinned by `NodeAttributesContractTest` + `node-attributes.spec.ts` (byte-compared contract JSON).
6. **Editor shell gotchas** (`pipelines.md:88-92`): self-bounded height `calc(100dvh - 120px)`, split handle
   stays mounted when collapsed, G6 host needs its own `ResizeObserver`, palette `groups` must be a signal input.
7. **Sample limits**: sample panel caps at 256 KB / 40 lines; parse preview caps `sample_text` at 1 MB
   (`ConfigRoutes.java:469`). The unified sample rail inherits both.
8. **Record dedup boundary**: `prepare()` refuses Stage-2 steps without `output_store:`
   (`stage1-architecture.md:337-380`). The checklist/guided mode must not offer Stage-2 nodes as "stages".

## 10. Phasing (each slice independently shippable + verifiable)

| Phase | Scope | Verify |
|---|---|---|
| **P0 — decisions** ✅ | Pin D1–D5 with the operator; record reversal-of-W5 rationale. | **DONE 2026-08-13** — doc ACCEPTED; §11 all resolved (dock+maximize, chips-only, run-to-here deferred, hard redirect). |
| **P1 — drawer shell** ✅ | `<inspecto-definition-drawer>` in the design system + gallery + axe spec; editor right dock hosts it; **collector path only** (smallest node kind) rendered inside, dialogs untouched elsewhere. | **DONE 2026-08-13.** Drawer opens in the dock (preview-verified on `cdr_ingest`); Apply = in-memory patch; the dialog's split/build logic extracted to `node-config-build.ts` (shared, not copied); collector dialog specs re-pinned on `pipeline-collection-definition.component`; UI gate green (2308 passed / 5 skipped). |
| **P2 — pure-pane refactor** ✅ | Panes lose `OnboardingStateService` injection → `@Input`/`@Output`; `DefinitionStateService` (sample thread only) lands; wizard temporarily *hosts* the pure panes so both surfaces run the same components during transition. **Landed in slices, not as one step** (grounded 2026-08-15: ~1,260 lines of pane code + ~1,440 of spec churn is not one commit): P2-0 `DefinitionStateService` (`50a26923`), P2-1 shell `@switch` replacing `NgComponentOutlet` — outputs cannot bind through an outlet, the plan's real hidden dependency (`168e397b`), P2-2 Collection (`91b4e4c1`), P2-3 Parsing + sample panel (`d529dde5`), P2-4 Schema & Mapping (`6cd03e32`), P2-5 Enrichment (`8c128a5f`), P2-6 Publish (`1c039ce0`), close-out (dual dirty contract collapsed, `registerDirtyCheck` removed). **Three as-built rules the plan text did not have:** (a) pristine is reached by RE-SEEDING from the pane's own input, never by marking pristine on emit — this host's save is async and can fail, and a failed save must leave the pane dirty; (b) where re-seeding cannot work (Enrichment: the editor is already dirty and re-hydrating would rebuild the form from a value it holds) the pane recognises the draft it emitted BY IDENTITY; (c) a stage's own companion artifact (segment schemas, `<name>_schema`) stays a pane write and is emitted-after, but anything the HOST holds or a third entity (the go-live Dataset) is the host's. The dirty registry was NOT made per-drawer — it is gone entirely, replaced by the `dirtyChange` output. | **DONE 2026-08-15** — all five panes pure, wizard fully functional; onboarding suite 171/12 passing (was 155 at P2-0). Preview-verified per slice, not just unit-tested. |
| **P3a — Delimited parser** | First per-format slice, end to end: `parser.delimited` node type (engine + node-types publish + lift/lower of existing `frontend: delimited` configs), palette icon, Parse drawer with grammar-as-properties + output schema + sample rail + parsed `tier="pro"` table; `grammar-editor.dialog`'s delimited path retired. Covers 75%+ of real data. **ENGINE HALF DONE 2026-08-15 (`6bc685cf`); mock parity `8c847dbe`; Parse drawer `489b429c`; palette-drop path GROUNDED + pinned 2026-08-15. Residual #2 (the dialog's delimited path) RESOLVED by operator decision 2026-08-15 — see [Grammar templates, not bindings](grammar-templates-not-bindings-plan.md): the live `use: grammar/<id>` binding is retired in favour of copy-from-template, after which every `parser.delimited` reaches the drawer and the dialog serves only the plain `parser` type. P3a is CLOSED here; the follow-through is that plan's S1–S4.** **Palette-drop as-built — no code change was needed:** the palette seeds `{id, type}` with no config at all, and `isDrawerParse` keys only on the type plus the absence of a `grammar/` use, so a config-less fresh drop routes to the drawer; the pane's `parsingBlock()` `{}` fallback then seeds the editor from the DECLARED delimited defaults, so Apply emits a complete `{frontend, delimited:{delimiter:',', has_header:true}}` — the drawer cannot emit an empty `parsing:` block. The suspected `PARSER_NO_SCHEMA` hole is therefore not one: a delimited Grammar is complete without a schema (`has_header` reads the header row), which is exactly what the block's mere presence attests, and a node never opened has no `parsing` key and refuses correctly (both directions already pinned in `pipeline-editable.spec.ts`). Verified live in the offline preview: drop → Configure → drawer on the bare node → Apply → Save refused with `MULTI_PARSER` against the pipeline's existing parse slot. As-built decisions: the lift retypes on an **explicit** `parsing.frontend: delimited` only — delimited is also the implicit default, and retyping bare legacy files would flip everything deployed on a read; lower stamps `frontend: delimited` onto a palette-fresh node so the identity survives the next lift; a contradictory frontend refuses (`PARSER_FRONTEND_MISMATCH`) and a second parser-family node refuses (`MULTI_PARSER`, replacing silent last-one-wins); the subtype homes `grammar/` but not `ingester/`; compiler/dry-run group the parse slot by `NodeCategory.PARSE`, mirroring the sink family. **No `NodeAttributes` spec published yet** — the drawer half decides the form shape over the nested `parsing.delimited.*` grammar (the `key__nested` spec convention has only ever carried one level); node-attributes/step-types contract JSONs unchanged, `BindKindHomeContractTest`'s tripwire updated (PARSE stays bindable, both types homed). **UI as-built:** the drawer hosts the delimited pane only when the node's Grammar is INLINE — a `use: grammar/<id>` node stays on the dialog, because updating a reusable component is a write route and the drawer's Apply is an in-memory patch (D2); the shared editor gained `[lockType]` (a per-format node's format IS its type, so the picker could only author a `PARSER_FRONTEND_MISMATCH`); and the editor spec now needs `TestBed.overrideProvider(MatDialog)` because the new pane pulls `<inspecto-data-table>` in, which injects the real dialog. | A delimited stream defined entirely from the editor; legacy `frontend: delimited` configs lift into the new node and lower back byte-identical ✅ (engine, `PipelineEditableTest`); parse preview + SQL-over-sample work; contract tests updated ✅. |
| **P3b — Fixed length** | Same pattern, isolated: `parser.fixedwidth` node + `{name, start, length}` grammar table + drawer. Nothing outside fixed-width handling is touched. | Fixed-width stream end-to-end; delimited slice unaffected (regression suite). |
| **P3c — ASN.1** | `parser.asn1` node over the existing ParserPlugin; grammar textarea (X.680) + strictness/header props; load path via record ingester until B5 flatten lands. | ASN.1 sample previews from the drawer; ingest-capable config saves; TLV-dump mode works with empty grammar. |
| **P3d — Custom** | `parser.plugin` node; dropdown off `GET /parsers` (`grammarSchema`-driven form); preview via `POST /parsers/{id}/preview`; Save gated on `ingestable:true`. Retires `grammar-editor.dialog` entirely; absorbs parser-plugin-framework P4. ⚠ This row was in direct contradiction with P3a's as-built (which kept the dialog permanently for grammar-BOUND nodes, whose component write the drawer deliberately lacks). **Resolved 2026-08-15 in P3d's favour, and now UNBLOCKED**: [Grammar templates, not bindings](grammar-templates-not-bindings-plan.md) shipped all four slices, retiring the binding itself. The dialog's only remaining jobs are the plain `parser` type — which this row's own pane replaces — and a *dangling* `use: grammar/<id>`, which deliberately has no drawer path. Retiring the dialog is now a matter of building the `parser` pane, with no write route left to preserve. | A deployed plugin definable end-to-end; hierarchical/non-ingestable plugins correctly preview-only. |
| **P4 — schema sync** | B1 + B3 backend; `<inspecto-schema-pane>` (merged grid, drift/re-sync, mapped-output preview); schema write-adapter; retire client `suggestTypes()`. | Derive → edit → validate → see mapped rows → Save binds `processing.schema_file`; drift indicator fires on sample change; `schema-editor.dialog` cases re-pinned. |
| **P5 — dedup fold** | H1 + B2 backend first; Collection drawer dedup group; `transform.dedup.marker` leaves palette; H2 injection made visible. | Graph save round-trip preserves `processing.duplicate_check`; a legacy graph *with* a marker node still lifts (read-compat); marker dedup provably still runs (smoke). |
| **P6 — host collapse** | Checklist chips (guided mode), publish→toolbar action, enrichment/segment write-adapters, `/catalog/onboard` → redirect; wizard shell + `OnboardingStateService` lifecycle deleted. | Onboard-create → guided editor → go-live end-to-end; old route redirects; e2e + GAUNTLET green. |
| **P7 — close-out** | Distill as-built into `okf/frontend/features/` (pipelines + onboarding concepts merge), BACKLOG sweep, archive this plan. | `handoff` checklist clean; `graphify update .`. |

Ordering rationale: P1–P3 are pure-UI and reversible; P4–P5 need the backend items; P6 is the only
destructive step and comes last, after both surfaces have run on identical components for several phases.

## 11. Open questions — ALL RESOLVED 2026-08-13 (operator)

1. **Drawer vs widened right dock — RESOLVED: right dock + maximize toggle.** The drawer replaces the
   right-dock Properties content (persistent, resizable via `[inspectoSplit]`); a maximize toggle covers
   wide grids (schema/Load). Not a floating slide-over.
2. **Guided mode depth — RESOLVED: chips only.** Checklist chips + finding counts; no "next suggested
   step" affordance, no wizard lifecycle/resume state carried over.
3. ~~Mapping expressions~~ **RESOLVED 2026-08-13 (§4b-C)**: the expression column is read-write in P4,
   backed by the existing `transformType: EXPR` (verbatim DuckDB scalar, author-owned validity).
4. **`run-to-here` — RESOLVED: deferred.** Dry-run + previews cover "test while defining"; the
   run-to-here backend (`PipelineRoutes.java:77` reserved) stays a separate future item, not this plan.
5. **Wizard deprecation — RESOLVED: hard redirect in P6.** No read-only flag release; by P6 both
   surfaces will have run identical components for several phases.

---

*Grounding sources: `docs/okf/frontend/features/{onboarding,pipelines,collector-config,grammar-config}.md`,
`docs/archived-documents/plans-archive/onboarding-pipeline-unification.md`, `PipelineEditable.java`,
`NodeAttributes.java`, `ConfigRoutes.java`, `DbBrowserRoutes.java`, `CollectorProcessor.java`, and the
onboarding/pipelines component sources cited inline.*
