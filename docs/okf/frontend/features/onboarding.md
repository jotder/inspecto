---
type: Feature
title: Stream & Reference Onboarding
description: Guided, resumable authoring of a data origin — a stage rail over the server-held Stage-1 pipeline draft.
resource: inspecto-ui/src/app/modules/admin/catalog/onboarding/onboarding-create.dialog.ts
tags: [feature, onboarding, catalog, acquisition, pipeline]
timestamp: 2026-07-16T00:00:00Z
---

# Stream & Reference Onboarding

> 🔴 **The stage-rail shell was DELETED 2026-08-16 (definition-surface unification P6-e).** Onboarding
> is now the **guided pipeline editor** — see [pipelines](pipelines.md). What survives here is the
> **create dialog** (`onboarding-create.dialog.ts`, still the entry point and still the only Import
> surface) and the *concepts*: the stage model, readiness, the draft-IS-server-state rule. The route
> `/catalog/onboard/:name(/:stage)` is a redirect (P6-a); the stage rail is the editor's checklist
> chips (P6-d); go-live, the Dataset hop, the enrichment/segment writes, the impact-aware delete and
> the stream-config export all live on the editor (P6-b/c/e). Everything below the banner still
> describes **behaviour that is live**, just under a different host, unless a line says otherwise.
> *(P7 folds the remainder into `pipelines.md` and archives this file.)*

Route `/catalog/onboard/:name(/:stage)` (one matcher route — the shell survives stage navigation),
entered from the Catalog Streams/References tabs' **Onboard Stream / Onboard Reference** header CTA
(lens-gated `canAuthorWorkbench`) or from the nav item **Platform ▸ Catalog ▸ Onboard Stream**, which
links to `/catalog?onboard=stream` — `CatalogComponent.ngOnInit` selects that tab and raises the same
create dialog *after* the rows load, so the name control can reject a duplicate inline instead of
waiting for the server's 409. (`?onboard=reference` works the same; only Stream is in the nav.) The create dialog asks the minimum (kind toggle + name +
optional description; directories derive from the space convention under a collapsed Advanced) and
writes a minimal `active: false` pipeline draft + registers it — **the server-held config IS the
draft** (shift-handover safe; no wizard state is ever stored). Vocabulary: [GLOSSARY](../../../GLOSSARY.md)
§2 *Onboard*; ⛔ never "wizard" in copy.

## The stage rail (kind-aware, jumpable — not a locked stepper)

- **Stream:** Collection → Parsing → Schema & Mapping → Enrichment *(optional)* → Dataset & Go-live.
- **Reference** (`produces: reference` written at create): Collection → Parsing → **Keys & Load**
  (the SAME schema pane, plus an honest full-replace load-policy note) → **Publish** (bindable-by-name
  note `ref: <normalized-id>`).

Per-stage readiness chips are **computed from the config blocks on every read** (Not configured /
Configured / Validated — Validated is session-only, from a passed sample test); lifecycle badge =
Draft → Ready (all required stages configured) → Live (`active: true`). Resume lands on the first
incomplete stage. Discard = `DELETE /config/pipeline/{name}` (refused while active).

**Take offline (2026-08-14) is the inverse of go-live and the same flag** — `saveBlock({active:
false})` from the publish pane, no dedicated route (the write surfaces already accepted a false
value; this was UI-only work). It exists because `active` had **no** UI write of `false` anywhere in
this flow, while both Discard and `DELETE /config/pipeline` refuse an active pipeline — so **a Live
stream could not be removed from the Catalog at all**. Taking it offline deliberately **keeps** the
landed data and the registered Dataset: they describe data already written, which stopping the
collector does not unwrite.

⚠ **A Live pipeline has no separate publish step — every stage save takes effect.** `/config/patch`
has no active-pipeline gate (unlike delete and rename, which 409), and `ConfigRegistry` is
mtime-keyed, so an edit to a live config is picked up on the next poll cycle with no operator action.
The shell used to claim the opposite ("It runs only when you go live"); it now swaps that copy while
live and raises a warning banner pointing at Take offline. *(Enrichments are the exception — no mtime
hot-reload, they need explicit re-registration.)*

**Go-live also registers the Dataset** (2026-07-30, shipped as S1 of the since-superseded
onboarding↔pipeline *split* plan; the behaviour **stays** under the current
`superpower/onboarding-pipeline-unification.md` — it is no longer "the handoff artifact between two
planes", but Datasets remain the query surface and this is independently useful): after the
`active: true` save succeeds, the publish
pane writes a `dataset` component (`id` = the normalized pipeline name, `kind: physical`,
`physicalRef` = the store) via the shared `ComponentsService` — deliberately **not** studio's
`DatasetsService` (cross-feature import ban). Idempotent by **physicalRef, not id** (any existing
dataset pointing at the store wins); every failure downgrades to a toastr *warning* carrying the
manual recipe, because activation has already succeeded. **Streams only** — a Reference's store is
consumed by name in enrichments, and its upsert/SCD2 layouts carry system columns. ⚠ The registered
dataset deliberately carries **no `sourceName`** (the editor's source select offers only
`SAMPLE_SOURCE_NAMES`, so any value would be a lie; `physicalRef` is the binding that matters) — the
list card therefore reads `source: data` from `fromContent`'s default until split S2 wires that picker
to the Catalog's real stores. Do not "fix" it by inventing a sample-source value.

## Stream configuration export / import (2026-07-31)

**Export** is a toolbar-menu item — on the onboarding shell until P6-e, **on the pipeline editor**
since (⛔ deleting the shell without re-homing it would have left the format import-only: the create
dialog still reads a bundle, so nothing could produce one); **Import** is on the New Stream dialog,
which becomes "Create from import". Format `inspecto-stream-config` v1, pure logic
in `inspecto/transfer/stream-bundle.ts` (+ `stream-transfer.service.ts` for the I/O).

⚠ **Deliberately NOT a `BundleKind`** of the Metadata Bundle (`inspecto/transfer/bundle.ts`). That
format carries **Studio component-registry** artifacts addressed by **id** (`/components/{type}/{id}`);
an onboarded Stream lives in the **config** namespace addressed by **path** (`ConfigService`,
`/config/...`), and the two collide on the word *schema* — `BundleKind` already has `'schema'` meaning
the registry component, so filing a Stream schema there would import it into the wrong store. Stream
satellites are also **paths embedding the source space**, which must be rewritten for the target —
something the bundle's id-based `BundleRef` cannot express. The sibling format reuses the proven
primitives (`hashContent`, parse/validate shape, object-URL download) instead.

**What travels:** the pipeline body, `processing.schema_file`'s schema, every per-segment schema of a
plugin parser, and the `<name>_enrich` companion. **What does not:** `name` (the target names its
own), `active` (an import is ALWAYS an inactive draft — importing as live would start processing on
someone else's server), `dirs` (re-derived from the target convention), and a Connection referenced by
`collector.connection` — it carries credentials, so it is reported as a **requirement** instead. A
literal secret-looking value is masked to `***` at export and reported (a config should only hold
`${ENV:…}` references; a literal is an authoring mistake, not something to ship to a file).

⚠ **A config's OWN identity field decides the file it is written to** — `ConfigService.write` derives
the name from the content, never from a separate argument. So every satellite must be retargeted
*inside* its body: a schema self-names via `raw.name`, an enrichment via top-level `name`. Getting
this wrong is destructive, and it happened: the first live round-trip wrote the enrichment back to the
SOURCE's `<source>_enrich`, clobbering an unrelated config while leaving the imported stream with no
enrichment. A second pass then found `output.database` keeping the **source space** — an imported
enrichment writing into another space. Both are pinned by regression specs; the enrichment is now
retargeted on `name`, `triggers.on_pipeline`, `input.database` and `output.database` (author's
intermediate layout preserved, root and stream leaf re-pointed). Neither defect was visible in unit
tests alone — reading the written files off disk is what surfaced them.

⚠ Import writes **satellites before the pipeline**, so the pipeline never names a file that does not
exist yet (the same ordering rule the Schema stage and segments editor follow), and uses **no
`overwrite`** on the pipeline so an import can never silently replace an existing stream. Kind comes
from the file and the toggle is locked — a Reference imported as a Stream would change its load
semantics. Every rewrite is stated in the dialog BEFORE the write. No new mock handler is needed: both
sides replay existing `ConfigService` reads/writes.

## Sample-as-thread (a strip at the top of the PARSING stage)

One captured sample (file ≤256KB or paste, session-held) threads through the stages: raw → parsed
(`POST /config/preview/parsing`, real DuckDB) → cast-checked (`POST /config/preview/schema`,
TRY_CAST). A new sample or re-parse invalidates the downstream hops. Since the 2026-07-29 reflow
the panel is a **collapsible full-width strip mounted by the Parsing pane itself** — the stage that
consumes it (choose the file → view it → pick a type and options below) — replacing the old
right-hand aside. ⚠ It is deliberately **not** in the shell: on Collection/Publish it was dead
weight, and the state is session-held in `OnboardingStateService`, so downstream stages still read
the thread without rendering the panel (`onboarding-shell.component.spec` pins that the shell does
not render it; the parsing pane pins that it precedes the file-type picker). The header row carries the thread
chips (lines / parsed / cast) and capture actions, the body shows up to 40 raw lines, and it is now
visible on small screens too. The schema pane DERIVES its fields from the parsed columns
(frontend-aware selectors: positional for delimited/fixedwidth, verbatim key for json/text_regex)
and offers only the four honestly-cast types (VARCHAR/DOUBLE/DATE/TIMESTAMP — exactly what
`TransformCompiler.direct()` casts), **prefilled with sample-suggested types**
(`suggestTypes` in `parsing-sniff.ts` — a type is suggested only when every non-blank sampled
value matches; "Validate types" stays the verdict).

**The field editor is a paged table over the FormArray (2026-07-31)** — CDR feeds land 500+
columns, so the flat control list became a windowed table: search (name/source) + type filter +
sortable headers (numeric-aware for delimited's positional selectors) + `mat-paginator`
(25/50/100, default 50 — only one page of controls is in the DOM). The **FormArray stays the
single source of truth**; the window signals only choose which rows render, and are deliberately
NOT reactive to name-cell keystrokes (re-sorting under the caret makes rows jump). A header
master-checkbox includes/excludes the **filtered** set across pages (Gmail-select semantics).
The Type cell renders a data-format icon + hint via a closed `TYPE_META` map
(text ▬ / number # / date 📅 / date&time 🕐 — `mat-select-trigger`, icons all present in
`heroicons-outline.svg`). ⚠ **Save reveals hidden problems**: an invalid or duplicate name on a
filtered-out row or another page would block Save with nothing visibly wrong — `buildFields`
clears the filters and jumps to the offending row's page, and the toast names the row. ⚠ The spec
does NOT assert rendered `<mat-icon>` counts — jsdom has no icon sprite and the registry error
aborts the trigger view; visual proof is a preview job. There is deliberately **no per-field
"format" column** — a schema field is `{name, selector, type}`; date/timestamp format masks are
pipeline-level `csv_settings`, and a per-field column would imply rigor the engine does not apply.

## Parsing stage flow (choose file → view → type → options → test → table/tree)

The stage is a thin host over the shared `<inspecto-grammar-editor>` (2026-08-04) — see
[Grammar configuration](grammar-config.md) for the store contract and what is shared with the
Pipelines `parse` node dialog.

The pane reads top-to-bottom, starting with its own sample strip: file-type toggle (with a **sniffed
suggestion chip** — `sniffFrontend` recognises NDJSON / JSON-array / consistent delimiters and is
applied only by click, never automatically, prefilling the sniffed delimiter) → per-frontend
options → Test parse → full-width results. For the `json` frontend the results offer a
**Table | Tree toggle**: the tree (`jsonSampleToTree` + the shared `app-parser-tree`, relocated to
`inspecto/components/`) renders the sample's own records client-side and carries the honest note
that the engine reads. ⚠ **That note is now out of date in the engine's favour** (2026-07-31): a
dotted `raw.fields[].selector` reaches a nested value (`addr.city`), and `parsing.json.records_path`
now accepts a dotted path to a nested record array (`payload.records`) — see
`okf/backend/config/parsing-options-reference.md` §6.4. The tree itself is unchanged; only the
caveat it carries needs revisiting when the pane next gets attention.

**`records_path` became authorable 2026-07-31** (W2/U-D): `parsing-attributes.ts` offers
`json__records_path` (default `$`) for the `json` frontend, gated
`dependsOn: {key: 'json__format', notEquals: 'newline'}`. The gate is not cosmetic —
`PipelineConfigParser.parseJson` **hard-fails** a nested path under `format: newline`, since in NDJSON
each line is already a record and there is no enclosing document to walk. Hiding the field for NDJSON
is therefore the difference between an unauthorable shape and a config that saves and then dies at
load. Whatever authors this next must keep the flat `__` key lowering to the nested
`json.records_path`: a flat key that reached disk would be silently ignored by the parser, which is a
failure with no error message.

**Every row-preview surface in onboarding is `<inspecto-data-table>`** (2026-07-30). They were
`<inspecto-query-panel>` mounts bound `[source]`-only, i.e. the query *builder* used as a dumb table,
which is why they looked unlike the ~20 other grids in the app. Tiers are deliberate: the **parsed
sample** (builtin, plugin, and the Pipelines Parser dialog) is `tier="pro"` with `sourceName="parsed"`,
so its **offline SQL editor over the sample rows** comes up seeded `SELECT * FROM "parsed"` and the
author never has to know a table name; the schema stage's **rejected rows** and the **enrichment
preview** are the default `standard` tier — search · column chooser · CSV export, no SQL, because
there is nothing to explore there that the pane does not already say. `query-panel` now has exactly
two hosts — Studio ▸ Queries and the Dataset editor — the two that consume its `(queryChange)` output.
⚠ A host spec must `await TestBed.compileComponents()` (the table `@defer`-loads blocks; the pro tier
loads CodeMirror) and stub `InspectoGridThemeService`, which otherwise chains to the app shell's
`GAMMA_APP_CONFIG`.

Since 2026-07-30 the toggle also appends the **served plugin parsers** (`GET /parsers` — XML today,
ASN.1/vendor formats when their plugins deploy; `okf/backend/engine/parser-plugins.md`): their
options form renders the served grammar schema (`fieldSpecsToAttributes`), Test parse runs the real
`POST /parsers/{id}/preview` (table or record tree), and **Save is disabled with an honest note** for a
preview-only plugin (one that is not `ingestable` or names no `ingesterClass` — XML today), because
there is nothing truthful to write. ⚠ The plugin preview is pane-local — the sample thread's parsed hop
(and thus the Schema stage derivation) is fed only by the four built-ins the draft can actually go live
with.

**Segments editor** (`segments-editor.component`, shipped 2026-07-30) unlocks guided Save for an
*ingestable* plugin parser. It lives in the Parsing stage rather than being a new onboarding stage
(stages are static arrays with no runtime-conditional precedent, and the editor needs the preview tree
directly above it). **Derive from preview** proposes one segment per record type with a column per leaf
path — deliberately destructive, it is the "start from my data" action. Save writes one schema toon per
segment (`ConfigService.write('schema', …)`, the Schema stage's convention-path idiom) and only then
patches `parsing.plugin`, so the pipeline never names a schema file that does not exist yet. A bespoke
nested `FormArray` is unavoidable: `FieldSpec` cannot express "a list of segments each with a list of
columns".

**Re-opening a plugin stream is possible at all only since 2026-07-31** (unification W2 / U-E). Until
then the pane held a `pluginManaged` guard that was a **whole-pane lockout**, not a read-only view: a
config with `parsing.plugin` or `processing.ingester` rendered one "author that in the pipeline TOON
directly" alert and nothing else. Because `savePlugin` writes `frontend: 'plugin'` — exactly what the
guard matched — **a plugin config saved through this pane's own segments editor could never be reopened
here.** The guard predated the served catalog, the grammar-schema options form and the segments editor,
and was not lifted when they shipped.
Lifting it needs `rehydratePlugin`, and that is the load-bearing part: a guided Save stores
`parsing.plugin.ingester` (the **FQCN**), never the parser id, so the id is recoverable only by matching
`ingesterClass` against the served `/parsers` catalog. Without it `frontend: 'plugin'` normalizes to
`delimited`, the pane would present a plugin pipeline as delimited, and a Save would overwrite its
parsing block. Two rules to preserve:
- Restoration must **not** go through `setType` — that marks a user action, and a dirty pane on arrival
  prompts "discard changes?" for a config nobody touched.
- When the FQCN matches nothing served (plugin jar not deployed), `unservedPlugin` renders a warning
  naming the class. Silence there would let the built-in fallback read as the pipeline's real parser.

**Re-opening a saved stream restores columns, not just keys** (2026-07-31 — closes the shipped
residual). `parsing.plugin.segments` stores only `segment key → schema-toon path`, so the pane reads
each toon back (`ConfigService.read('schema', …)`, the same call the Schema stage uses) and rebuilds the
column rows via `segmentDraftFrom` — the exact inverse of the `schemaDraftFor` that wrote them.
Previously the editor re-hydrated keys alone and every re-edit needed a destructive re-derive.
Three details that are load-bearing:
- The schema NAME comes from the stored path's basename (`schemaNameFromPath`), not from recomputing
  it off the segment key — so a hand-authored path, or one written under a different space id than the
  one currently selected, still re-hydrates.
- Reads are **per-segment and non-fatal**: a 404 is silent (a pipeline may legitimately reference a
  schema an interrupted save never wrote) and any other error warns; either way that segment falls back
  to keys-only, i.e. exactly the previous behaviour.
- The late read is **dropped if the operator has already edited** — the editor's `initial` setter
  rebuilds the whole `FormArray`, so applying it over their work would silently discard it.

## Enrichment stage (Streams, optional)

Opt-in pane authoring the companion `EnrichmentConfig` (`<pipeline>_enrich`): reference bindings
(**by-name first** — the picker offers only pipeline-produced Reference Datasets, minus self — with
a file-path fallback) + CodeMirror transform SQL. Wiring is derived, never asked: input = this
pipeline's Stage-1 output, `triggers.on_pipeline` = the engine-normalized id
(`name.toLowerCase().replace(' ','_')` — what `BatchEvent.pipeline()` carries), output = the
space's `enriched/` convention. **Every save re-registers** (`POST /enrichment`) because
enrichments do not hot-reload by mtime; a register failure downgrades to a warning (the file is
saved; it loads on restart).

## Collection stage: Connection-first — `connector` is never asked

Since the 2026-07-29 reflow there is **no Connector select at all**. The stage asks one question —
**Collect from: Local inbox | Connection** — and `collector.connector` is derived and injected at
save time (`local`, or the picked Connection's own `ConnectionProfile.connector`, shown read-only
next to the picker). This is not cosmetic: `CollectorConnectors.forConfig` dispatches on
`collector.connector` and hands the profile named by `collector.connection` to *that* factory
**without checking the two agree** — `connector: sftp` plus an Azure Connection silently gives the
SFTP factory an Azure profile. Since 2026-08-04 the whole surface is the shared
`<inspecto-collector-config>` — the pane is a thin host that only persists — so the Pipelines
`acquisition` node dialog behaves identically; see [Collector configuration](collector-config.md).
Rules encoded there:
- Connection mode requires a **saved** profile (unknown ids are refused at save — the whole point
  is that the id resolves to a connector); ＋New connection opens the shared `ConnectionFormDialog`.
- A hand-authored TOON with a non-local connector and no Connection is **grandfathered**: the pane
  keeps the stored connector rather than destroying it.
- Switching to Local inbox deletes `collector.connection` and writes `connector: local`.
⚠ The connector is injected in `save()`, never held in a (disabled) form control — a disabled
control drops out of the schema form's value and the key would vanish from the written TOON.

## Seams & gotchas

Backend seams: [onboarding-authoring](../../backend/control-plane/onboarding-authoring.md). Every
stage saves its block through **`POST /config/patch`** (2026-08-04), which deep-merges server-side
instead of replacing the file after a client-side merge — that replace was a stale-read clobber, and
one route fixed it for every stage. ⚠ A cleared key must travel as `null`, not `undefined`
(`nullifyDeletes`), or JSON drops it and the merge keeps the old value. The
create dialog silently derives the full dir convention (`status_dir` et al — without it the Runs
history stays empty) and `processing.duplicate_check` (the collector-level `duplicate:` block is a
no-op on the legacy local poll path — without markers the same file re-ingests every cycle).
Catalog list rows show Draft/Live from `attrs.active` (References included, via the produced-origin
graph attrs); "Ready" is visible in the shell header only. Offline via the `onboarding.handler`
mock (config write/read/delete, both previews, register pair).
