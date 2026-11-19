---
type: Concept
title: Pipeline model — node types, the Step catalog, transforms and the editable round-trip — The editable round-trip — the parser family onward
description: Part 4 of 4 of Pipeline model — node types, the Step catalog, transforms and the editable round-trip: 
tags: [elt, engine, pipeline, plugins, spi, node-types, catalog, transform, seam, lift, lower, round-trip, authoring, gotcha, pipeline-graph, execution, lanes, ingest, job, scratch, test-run, dry-run, park, drain, post-sync, consignment, output, sink, partition, quarantine, ducklake, export, object-storage, s3, hdfs, job-type, post-action]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-19 last-edit
code: platform/inspecto-etl
---

# Pipeline model — node types, the Step catalog, transforms and the editable round-trip — The editable round-trip — the parser family onward

Part 4 of 4 of [Pipeline model — node types, the Step catalog, transforms and the editable round-trip](spec-pipeline-model.md) · previous: [Transforms, modularity seams and the editable round-trip](spec-pipeline-model-round-trip.md).

## The editable round-trip — the editor's graph ⇄ the canonical config (continued)

### 20. The parser family — per-format node types (2026-08-15, `6bc685cf`; merged from the former `design.md`, 2026-09-01)

Parse is a **family**, the way sink already was: the generic `parser` plus one type per format —
`parser.delimited` (P3a), `parser.fixedwidth` (P3b), `parser.asn1` (P3c), the `parser.json` /
`parser.text_regex` pair, and `parser.plugin` (all P3d, 2026-08-16) — the family is now closed. Decided as
B6 — no generic parse node with format tabs, because each format owns its own grammar shape and
complexities. `isParserType` is `PARSER` ∪ `SUBTYPE_FRONTENDS.keySet()`, so a subtype joins the family by
declaring its spellings and nothing else.

* **The lift retypes only on an EXPLICIT `parsing.frontend`.** Delimited is also the parser's *implicit*
  default (`PipelineConfigParser` defaults the key), so retyping every bare legacy file would change the
  node type of everything already deployed on a mere read. A file that never says the word keeps the plain
  `parser` type until its author opts in. ⚠ This caveat is **delimited's alone** — every other frontend
  (fixed width, ASN.1, JSON, text/regex) is never implicit, so those configs already declare themselves and
  all of them retype.
* **A subtype answers to every spelling of its frontend, and the set is the source of truth.**
  `SUBTYPE_FRONTENDS` maps each subtype to its spellings and `subtypeForFrontend` inverts it; both the
  lift's retype and the lower's mismatch check go through it, so the comparison is by **subtype, not by
  string**. Fixed width has two (`fixedwidth`, `fixed_width` — `PipelineConfigParser#parseFixedWidth`
  reads both) and neither contradicts the other. ⛔ **A lifted node keeps the spelling its author wrote**:
  canonicalising `fixed_width` on a read would rewrite a deployed file on the next save, and the verbatim
  round-trip is the property `PipelineEditableTest` pins.
* **Lower stamps the CANONICAL frontend** (the first entry in the subtype's list) onto a palette-fresh
  node — the file must say the word its type means, or the next lift silently loses the identity. A
  lifted node already carries one, so the round-trip stays byte-verbatim.
* **A frontend the parser SYNTHESIZES a binding for makes that binding DERIVED** (P3c). `frontend: asn1`
  is sugar: `PipelineConfigParser#asn1PluginBlock` builds the `Asn1RecordIngester` wiring at load, so the
  lift reads a class back and presents `use: ingester/<fqcn>` on a node whose only *authored* home is
  `grammar/`. Refusing that ref would make every ASN.1 pipeline unsaveable — the AUTHOR-1(b) enrichment
  regression reached from the opposite direction — so `DERIVED_USE` maps `parser.asn1 → ingester/` and it
  is dropped in silence. ⚠ The rule generalises: **whenever a load-time synthesis invents something the
  lift can present, check what the save path will then think the author wrote.** ⛔ It does **not**
  generalise to the other built-in subtypes: nothing synthesizes an ingester for a plain built-in, so an
  `ingester/` ref on `parser.json` / `parser.text_regex` / `parser.delimited` / `parser.fixedwidth` is an
  authoring mistake and refuses with `UNSUPPORTED_BINDING`. **`parser.plugin` is the second exception,
  for a different reason**: its `USE_HOME` accepts `ingester/` too (like the plain `parser` type, since a
  plugin's ingester is an AUTHORED `parsing.plugin.ingester`, not synthesized) — but `PipelineLift.parserNode()`
  computes `use = "ingester/" + fqcn` unconditionally whenever `s.ingesterClass() != null`, with no check
  for *which* subtype the node will become, so the lift presents that derived ref on plugin-backed nodes
  too. `DERIVED_USE` therefore also maps `parser.plugin → ingester/`, for the identical reason as ASN.1.
  ⚠ **Found but out of scope**: the plain `parser` type accepts `ingester/` in `USE_HOME` with no matching
  `DERIVED_USE` entry, so a legacy `processing.ingester`-configured pipeline never retyped to
  `parser.plugin` would hit `UNKNOWN_USE_KIND` on validate today — a pre-existing gap, not fixed here.
* ⚠ **A record mode is not a node type.** Binary fixed width (`record: bytes`) lifts to
  `parser.fixedwidth` like any other — the type spans the format — but its field geometry lives in
  `processing.ingester_config` and is executed by `FixedWidthRecordIngester`, **not** by the
  `fixedwidth.fields[]` slices; `ComponentPreview` refuses to preview it and `DuckDbCsvIngester` excludes
  it from the native path. Operator decision 2026-08-16: it keeps the **dialog**, because the drawer
  pane's slice table would govern nothing. It needs no `ingester/` use-home either — binary reaches its
  ingester through the plain `processing.ingester` CLASS key, not a `use:` binding.
* Two refusals, both named: `PARSER_FRONTEND_MISMATCH` (a `parsing.frontend` contradicting the node's own
  type) and `MULTI_PARSER` (a second parser-family node — the flat file has one parse slot, and what used
  to be a silent last-one-wins became authorable once the palette offered two icons).
* **Every single-slot kind refuses a second node by name (2026-09-23).** `acquisition`, `gap` and
  `transform.dedup.marker` each own ONE slot in the flat file (the collector block, `gap_detection`, the
  marker-dedup keys) and were the last three still last-one-wins — a second node silently discarded the
  first one's config on save. They now refuse exactly like `MULTI_PARSER`: `MULTI_ACQUISITION`,
  `MULTI_GAP`, `MULTI_MARKER`, naming the SECOND node, the message naming the node that holds the slot,
  strict and lenient alike (the discard never depended on `strict`). Same 422 `refusals[]` shape through
  `PUT /pipelines/{name}/graph` (the SPA's `lowerGraph` mirror of it was deleted 2026-09-24).
  The lift emits at most one of each, so no shipped config can trip them (`LiftLowerFixtureSweepTest`
  green); the rule only closes the hand-built / canvas-authored case. Pinned by
  `PipelineEditableTest.aSecondNodeOfASingleSlotKindRefusesByName`,
  `ControlApiPipelineCrudTest.aSecondAcquisitionNodeRefusesWithANamedCode` (and, until their deletion
  2026-09-24, three `pipeline-editable.spec.ts` cases).
* Every subtype's `use:` home is `grammar/` **only**, not `ingester/`: a plugin-ingester binding on a node
  whose type already names its format is a contradiction, refused rather than half-honoured.
* ⚠ **`use: grammar/<id>` is read-supported but NEVER authored** (operator decision 2026-08-15). Every
  engine-side piece of it is deliberately unchanged — `resolveGrammarRef`, the `PipelineEditable`
  lift/lower translation, `UNKNOWN_USE_REF`, `PARSER_NO_SCHEMA`'s `grammarBound` branch, `USE_HOME`, and
  the `BindKindHomeContractTest` tripwire — because a hand-authored file may still carry one. What
  changed is upstream: a Grammar component is now a **Template** the UI copies from, so nothing writes a
  new binding, and opening a bound node in the editor migrates it to an inline copy. ⛔ Do not "tidy up"
  the binding read path as dead code — it is the compatibility half of a deliberate split. See
  [`plans-archive/grammar-templates-not-bindings-plan.md`](../../../../archived-documents/plans-archive/grammar-templates-not-bindings-plan.md).
* Grouping by **category, not type string**: `PipelineCompiler.compile` and `PipelineDryRun` ask
  `PipelineNodeTypes.isCategory(t, PARSE)`, mirroring the sink family. A new parse subtype needs no edit
  there — but it *does* need its own `LOWERABLE` and `USE_HOME` entries in `PipelineEditable`.
* **No `NodeAttributes` spec is published** for these types on purpose. Their grammars nest two levels
  (`parsing.delimited.*`, `parsing.fixedwidth.*`) while the `key__nested` spec convention has only ever
  carried one, and the UI drawer owns the form shape — a best-guess table that looks authoritative is what
  that class's doc warns against. Consequently `node-attributes.contract.json` stays byte-unchanged as
  each subtype lands.
* ⚠ `BindKindHomeContractTest` has fired correctly **twice** — at `parser.delimited` and again at
  `parser.fixedwidth`. Its tripwire asserts the exact PARSE type list plus the derivation the UI's
  category-keyed picker depends on. The category stays *bindable* only because each new type arrived
  **with** a `grammar/` home; one added without one must flip `bindKindFor('PARSE')` to null, and this
  test is where that shows up first.

UI side: [Grammar configuration](../config/user-guide-grammar-config.md).

### 21. The pipeline bundle — selective export/import with dependency closure (R2, 2026-09-01)

`PipelineBundleRoutes` closes TRANSFER-ARCH-1: canonical `*_pipeline.toon` transfer with closure.

- **`GET /pipelines/{name}/bundle`** (ungated read — the export posture every transfer surface
  shares; secrets never travel): an `application/zip` of `manifest.toon` (format
  `inspecto-pipeline-bundle` v1, satellites `{path, sha256}`, `requirements[]`, `notes[]`), the
  pipeline toon, every satellite byte-verbatim under its bare basename, and each `*_enrich.toon`
  companion. **Closure = `PipelineConfig.referencedFiles()`** — the engine's own resolution
  (beside the config, `PathJail.resolveConfigRef`); ⛔ no second resolver exists. `collector.connection` travels as a
  secret-free `requirements[]` entry; a literal secret-looking value is masked to `***` + noted.
- **`POST /pipelines/import?name=&conflict=refuse|overwrite|rename`** (`canAuthorWorkbench`; raw
  zip body + query params — the codebase has no multipart parser, matching
  `POST /spaces/{id}/import`). Gate order: write-root 503 → manifest/spec/sha256 422 → zip-slip
  jail 403 (checked against BOTH the requested and final destination) → conflict 409 unless the
  policy says otherwise (`overwrite` lands on the REGISTERED file — the saveGraph anti-shadow
  rule; `rename` takes the first free `<id>_2`…) → retarget inside each body (name/id/stream/
  collector.id; `active: false` always; dirs re-derived from the space convention; enrichment
  re-pointed on `name`/`triggers.on_pipeline`/`input.database`/`output.database`) → the FULL
  saveGraph gate (spec + safety + the three arming findings) → atomic writes, satellites before
  the pipeline, then register.
- ⚠ **Satellites are written before the safety gate and deleted on an ERROR verdict** —
  deliberate: `resolveSchemaRef`/`ConfigSafetyValidator.resolveRef` accept a config-relative
  candidate only when it exists on disk, so validating rewritten bare-basename refs against an
  empty directory would false-422 every bundle. A refused import leaves nothing written.
- Pinned over real HTTP by `ControlApiPipelineBundleTest` (round-trip with byte-compared
  satellites · conflict matrix · zip-slip · requirement-no-secret · 503/404/422 triple). ⚠ The
  capability-manifest drift guard fires on any new gated route — declare it in
  `CapabilityManifest` in the same change (only the FULL reactor showed it).
- The UI's Duplicate + row export still ride the client stream-bundle — migration onto these
  routes is the BACKLOG follow-up.
- **The Metadata Bundle's `pipeline` kind is this door (2026-09-25, BUNDLE-AUTHORED-PIPELINE-STORE-1
  option B, operator).** The route bodies were split into two package-private cores,
  `PipelineBundleRoutes.exportClosure` (→ `Closure{id, manifest, entries}`) and `importClosure`
  (everything after the write-root gate → `Imported{written, body}`), and `BundleRoutes`'
  `RegisteredPipelineBundleSource` calls them: the item's `content.closure` is `{manifest, files}` —
  the same manifest and entries as the zip, text as UTF-8 (`{base64}` otherwise) — so the sidecars
  travel INSIDE the one item and meet the same gates in the same order. ⚠ `exported_at` moved out of
  the core into the zip route: inside an item's content it made every export hash as drifted.
  ⚠ The item also carries the editable graph (`PipelineEditable.toMap`, the `graph/raw` shape) as a
  PROJECTION for lineage and Import as draft; a write-through import ignores it. ⚠ `BundleRoutes`
  decides skip-vs-overwrite first, so the core sees `conflict=overwrite` for an existing id and
  `refuse` otherwise (never `rename`); every `ApiException` / ERROR verdict becomes that item's
  `failed`. Pinned by `ControlApiPipelineKindBundleTest` (round trip into a fresh instance under a real
  Subject: 403 without the capability, registered + listed + dry-runs, skip/overwrite; tampered sha
  and graph-only items fail with nothing written).
- The metadata bundle's `authored-pipeline` kind is **export-only** since the same change — an import
  carrying one is 422 (W5: grandfathered `PipelineStore` graphs are never newly written) — and a
  `PipelineStore` graph no longer shadows a registered pipeline of the same id in Run to here /
  dry-run (the registered config wins; the store answers only an unregistered grandfathered id).

### 22. The Stream bundle's forward closure — the Reference Datasets a Pipeline reads (W5, 2026-09-24)

The per-Stream zip (the route keeps its legacy `datasources` path: `GET /spaces/{id}/datasources/{ds}/export` → `DataSourceBundleResolver` →
`BundleExporter`) already carried the **reverse** closure — what names the Pipeline (jobs, Decision Rules,
Expectations, Datasets over its store, Alert Rules). It now also carries the **forward** closure: what the
Pipeline itself reads. Operator decision 2026-09-24: build it. ⛔ `PipelineCompiler.toConfigMap` is
deliberately not migrated.

**Every way a Pipeline declares a Reference read** (grounded against the parser and the two executors):

| Where | Spelling | Carried? |
|---|---|---|
| `processing.join.reference` | `reference/<id>` | ✅ — surfaces as a `join` Step through `PipelineConfig.steps()` |
| top-level `steps[]` → `join.reference` | `reference/<id>` | ✅ — same seam |
| `route.branches[].steps[]` → `join.reference` | `reference/<id>` | ✅ — walked from the `route` Step config and `routeConfig()` |
| `*_enrich.toon` companion → `references.<alias>.ref` | `<id>` | ✅ — the companion itself travels too (matched on `triggers.on_pipeline`, the `PipelineBundleRoutes.companionsOf` rule) |
| any of the above as a **path** (`data/ref/x.csv`, `references.<alias>.path`) | a data path | ❌ — data, and a config bundle carries no data |
| `transform.lookup` | inline `mappings` | ❌ — reads nothing |
| recipe `transform: {join: references/<id>}` | `references/<id>` | ✅ — accepted beside `reference/`, as `PipelineConfigParser.referencePath` treats both as ids |

A by-name id resolves the way `ReferenceReader.sqlFor` resolves it at run time — the loaded Pipeline whose
`identity().pipelineName()` equals the id **and** that declares `produces: reference`; anything else (a
dangling id, a Pipeline that produces no Reference) is warned and not carried. A carried Reference is its
producer's `*_pipeline.toon` **plus its connection and schema files**, because the import gate 422s on a
Pipeline whose connection or schema the target lacks. The closure is **transitive** (a producer that joins
another Reference brings it) and never revisits the Stream itself, so a self-join or a cycle adds
nothing. `DataSourceBundle.files()` de-duplicates, so a file both closures reach — a connection the producer
shares, the same schema — rides once.

**Import round trip.** The manifest indexes the forward closure as `references: {<producer id>: [entries]}`,
listing only the entries that Reference alone brought. On `POST /spaces/{id}/import` (and its preview),
`BundleImporter.keepExistingReferences` drops every carried Reference the target already hosts — its entries
are not written and it is not a 409 conflict — and reports it in `referencesKept`; under
`?on_conflict=overwrite` nothing is narrowed and the carried copy replaces the target's.

**A missing connection warns, it does not refuse (operator, 2026-09-25).** Until then the Stream-bundle
import's connection gate was an ERROR — 422, nothing registered — which made moving a Stream into a
Space that has not yet onboarded its connection impossible. Now `BundleImporter.disableForMissingConnections`
runs on the zip entries **before** the write: a connection id is known when the target's registry holds it or
the bundle carries its `*_connection.toon`; every config that names an unknown one is written **switched off
by the switch its own kind reads** — a Pipeline (`collector.connection` or `webhook.connection`) gets
top-level `active: false` (`PipelineConfigParser`; the poll cycle runs only an active Pipeline), a job
(`job.connection`, `objectstore.export`) gets `job.enabled: false` (`JobConfig`). No cross-kind stamp: a
Pipeline has no `enabled` key, and a job no `active`. Only a disabled entry is re-serialised (canonical TOON);
every other entry keeps its exact bytes. The Pipeline still **registers** — it is present and inactive, so an
operator connects the profile and activates it in place. The response (and the preview) carries
`connectionWarnings: [{connection, code: WARN_UNRESOLVED_CONNECTION, message: "connect X to enable …",
disabled: [{kind, name, file}]}]`, one per missing connection; the preview's per-file finding is a WARNING, so
`valid` no longer goes false for this. The schema-existence and containment halves of the commit gate
(`DataSourceRoutes.referentialFindings`) are unchanged and still refuse the whole bundle. This now matches
`POST /pipelines/import`, which always warned (and always lands `active: false`). ⚠ `POST /spaces/import`
(seed a **new** Space from a whole-Space bundle) never ran this gate and still does not — a whole-Space export
carries its connections. Pinned by `ControlApiBundleImportTest.aBundleNamingAnUnknownConnectionImportsWith…`,
`…AllResolveCarriesNoWarnings…`, `previewReportsAnUnknownConnectionAsAWarningToo` and
`BundleImporterTest.disableForMissingConnections…`. An imported companion `*_enrich.toon` is
written but not hot-registered — it goes live on the Space's next boot, as in a whole-Space import.

Pinned by `DataSourceBundleResolverTest.carriesTheReferenceDatasetsThePipelineReadsAndNothingElse` (all
three by-name read sites, transitive hop, shared-file de-dup, the manifest index, and the negatives: path,
dangling id, non-producer, self-join, a Pipeline reading nothing) and, over real HTTP,
`ControlApiBundleImportTest.anExportCarriesTheReferenceItReadsAndAnImportRoundTripsOrKeepsTheTargetsOwn`.

### Every fixture survives the round trip — the standing gate (W0, proven 2026-09-06)

`LiftLowerFixtureSweepTest` (inspecto-engine) runs every `spaces/**/*_pipeline.toon` in the repo through the
editor's own seam — `PipelineEditable.toMap(cfg, raw)` → `PipelineCodec.fromMap` → a **strict** `lower` over the
original — and asserts the decoded original comes back verbatim (modulo the always-written `active`). All 22
fixtures pass, which is the proof the unification plan's W0 asked for before W4/W5. ⚠ The proof is about
THIS seam: a first cut used `PipelineLift.lift(cfg)` alone and every fixture "failed", because the bare lift
materialises engine defaults (`duckdb_threads=0`) and typed records (`PostActionConfig[…]`) that the editable
projection strips — that is the projection's business, not a loss. `RecipeConverterTest` gates the recipe
projection over the same fixtures; a hand-authored fixture must pass both before it is committed.

✅ **Verbatim means the decoded map, never the bytes (D4, signed 2026-09-22).** A workbench save rewrites
the whole TOON file: measured over the corpus, keys and values survive intact — including undeclared
`dirs` leaves and `description` — but blank lines, column alignment, quoting, key order within
`collector` and the trailing newline do not. 🔴 The rewritten quoting (`delimiter: "|"` →
`delimiter: |`) **looks like** the classic comment-character loss and was first read as corruption; it is
not — the reader parses those back identically and a re-run produced the same row counts. The operator
**accepted the churn** rather than fund a format-preserving writer, which would add a second drift
surface of its own; what is funded instead is the guard — a round trip through the real `PUT …/graph`
asserting the key set AND the re-read node configs (`WB-01`, which this in-process sweep cannot see).
⚠ So when a diff looks alarming, compare key sets and re-read values, never the text. Row:
`GRAPH-SAVE-REFORMATS-CONFIG-1`.

---
