---
type: Feature
title: Stream & Reference Onboarding
description: Guided, resumable authoring of a data origin — a stage rail over the server-held Stage-1 pipeline draft.
resource: inspecto-ui/src/app/modules/admin/catalog/onboarding/onboarding-shell.component.ts
tags: [feature, onboarding, catalog, acquisition, pipeline]
timestamp: 2026-07-16T00:00:00Z
---

# Stream & Reference Onboarding

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

## Sample-as-thread (a strip at the top of the PARSING stage)

One captured sample (file ≤256KB or paste, session-held) threads through the stages: raw → parsed
(`POST /config/preview/parsing`, real DuckDB) → cast-checked (`POST /config/preview/schema`,
TRY_CAST). A new sample or re-parse invalidates the downstream hops. Since the 2026-07-29 reflow
the panel is a **collapsible full-width strip mounted by the Parsing pane itself** — the stage that
consumes it (choose the file → view it → pick a type and options below) — replacing the old
right-hand aside. ⚠ It is deliberately **not** in the shell: on Collection/Publish it was dead
weight, and the state is session-held in `OnboardingStateService`, so downstream stages still read
the thread without rendering the panel (`onboarding-shell.component.spec` pins that the shell does
not render it; the parsing pane pins that it precedes the file-type picker). It is also skipped in
the plugin-ingester branch, where there is nothing to configure. The header row carries the thread
chips (lines / parsed / cast) and capture actions, the body shows up to 40 raw lines, and it is now
visible on small screens too. The schema pane DERIVES its fields from the parsed columns
(frontend-aware selectors: positional for delimited/fixedwidth, verbatim key for json/text_regex)
and offers only the four honestly-cast types (VARCHAR/DOUBLE/DATE/TIMESTAMP — exactly what
`TransformCompiler.direct()` casts), **prefilled with sample-suggested types**
(`suggestTypes` in `parsing-sniff.ts` — a type is suggested only when every non-blank sampled
value matches; "Validate types" stays the verdict).

## Parsing stage flow (choose file → view → type → options → test → table/tree)

The pane reads top-to-bottom, starting with its own sample strip: file-type toggle (with a **sniffed
suggestion chip** — `sniffFrontend` recognises NDJSON / JSON-array / consistent delimiters and is
applied only by click, never automatically, prefilling the sniffed delimiter) → per-frontend
options → Test parse → full-width results. For the `json` frontend the results offer a
**Table | Tree toggle**: the tree (`jsonSampleToTree` + the shared `app-parser-tree`, relocated to
`inspecto/components/`) renders the sample's own records client-side and carries the honest note
that the engine reads **top-level keys only** — a nested value lands as JSON text in one column
(the flatten DSL is BACKLOG'd engine work; `parsing.json.records_path` is locked to `$`).

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
`POST /parsers/{id}/preview` (table or record tree), and **Save is disabled with an honest note**
(preview-only until the flatten configuration; ingestable customs stay TOON-authored until the
segments editor). ⚠ The plugin preview is pane-local — the sample thread's parsed hop (and thus the
Schema stage derivation) is fed only by the four built-ins the draft can actually go live with.

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
SFTP factory an Azure profile. Rules encoded in `collection-pane.component.ts`:
- Connection mode requires a **saved** profile (unknown ids are refused at save — the whole point
  is that the id resolves to a connector); ＋New connection opens the shared `ConnectionFormDialog`.
- A hand-authored TOON with a non-local connector and no Connection is **grandfathered**: the pane
  keeps the stored connector rather than destroying it.
- Switching to Local inbox deletes `collector.connection` and writes `connector: local`.
⚠ The connector is injected in `save()`, never held in a (disabled) form control — a disabled
control drops out of the schema form's value and the key would vanish from the written TOON.

## Seams & gotchas

Backend seams: [onboarding-authoring](../../backend/control-plane/onboarding-authoring.md). The
create dialog silently derives the full dir convention (`status_dir` et al — without it the Runs
history stays empty) and `processing.duplicate_check` (the collector-level `duplicate:` block is a
no-op on the legacy local poll path — without markers the same file re-ingests every cycle).
Catalog list rows show Draft/Live from `attrs.active` (References included, via the produced-origin
graph attrs); "Ready" is visible in the shell header only. Offline via the `onboarding.handler`
mock (config write/read/delete, both previews, register pair).
