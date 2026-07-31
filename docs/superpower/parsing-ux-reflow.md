# Parsing UX reflow — Connection-first Collection + sample-on-top parsing flow

> On approval, persist this plan to `docs/superpower/parsing-ux-reflow.md` (repo rule: plans live in-repo).

## Context

Reviewing stream onboarding, the operator raised two UX problems and a target flow:

1. **Collection asks for Connector AND Connection** — but a Connection already carries its type
   (`ConnectionProfile.connector`). The ask: pick a Connection (or create one in place); derive the
   connector. Shift 17 already auto-adopts the connector on pick (mismatch is dangerous —
   `CollectorConnectors.forConfig` dispatches on `collector.connector` without checking it agrees
   with the profile); the select is now redundant UI.
2. **Parsing should read top-to-bottom**: choose file → view it (more lines than today's 8) →
   select file type → options/grammar → test → parsed output as table or tree. Tree-shaped data
   (JSON/XML/ASN.1) eventually gets a flatten DSL; both cases get column-type autodetection.

**Engine ground truth** (verified): Stage-1 parsing supports exactly `delimited`, `fixedwidth`,
`json`, `text_regex` (+ TOON-managed `plugin`) — `xml`/`asn1` are rejected
([PipelineConfigParser.java:687](inspecto-etl/src/main/java/com/gamma/etl/PipelineConfigParser.java)).
~~The JSON frontend reads **top-level keys only**; nested values are stringified; `records_path` is
locked to `$`~~ — **both halves now DONE** (nested field selectors 2026-07-31; nested
`records_path` 2026-07-31) in
[DuckDbCsvIngester.java](inspecto-etl/src/main/java/com/gamma/etl/DuckDbCsvIngester.java);
see `okf/backend/config/parsing-options-reference.md` §6.4 for the as-built contract.
`POST /config/preview/parsing` is the real engine path (rows capped at 1000). The Pipelines
**Parser config dialog** (9 types, grammar components, tree preview, ASN.1 modules) is a UI
prototype on a **mock-only** endpoint — `/components/grammar/preview` has no server route. The real
ASN.1 codec (`asn-parser/`) is a separate Maven project the inspecto reactor doesn't reference.

**Operator-confirmed scope**: reflow BOTH surfaces now (onboarding stage engine-real; Parser dialog
stays honest-prototype); flatten DSL is designed + backlogged as the immediate next engine task,
not built this round. This change is **UI + docs only** — no backend edits, no reactor impact.

⚠ Vocabulary: the new Collection toggle must NOT be labelled "Source" (banned term, GLOSSARY §2) —
use "Collect from: Local inbox | Connection".

## Part A — Collection stage: Connection-first

Files: `inspecto-ui/src/app/modules/admin/catalog/onboarding/collector-attributes.ts`,
`collection-pane.component.ts` (+spec), okf doc (Part E).

- Remove the `connector` AttributeSpec (the select). Add a pane-owned toggle above the form:
  **Collect from: Local inbox | Connection** (initial: Connection iff `collector.connection` is set
  OR stored connector ≠ local/blank).
- Connection mode: the existing `connection` autocomplete + Test connection + ＋New connection
  (`ConnectionFormDialog`) stay; show the derived connector read-only ("Connector: SFTP — from this
  connection"). Local mode: hide connection affordances; helper text names the inbox (`dirs.poll`).
- `save()` injects `collector.connector` explicitly (never a disabled form control — a disabled
  control drops out of the schema-form value, okf-documented trap): local ⇒ `local` and clear
  `connection`; Connection mode ⇒ the picked profile's connector; if no profile picked but a
  hand-authored non-local connector exists, keep it unchanged (don't destroy hand-authored TOON).
  Add `connector` to the `clearMissingRoots` roots set handling.
- Connection mode with no connection picked ⇒ save blocked with an inline message.
- Replace the shift-17 `ngAfterViewInit` adopt-subscription (superseded by derive-at-save + display).
- Rewrite the pane spec's adopt-on-pick tests for the new model (note: root `{provide: MatDialog}`
  is silently shadowed in these specs — use `TestBed.overrideComponent`, PROJECT_NOTES §6).

## Part B — Sample panel moves on top of the PARSING pane

Files: `onboarding-shell.component.html` (+spec), `sample-panel.component.ts` (+spec),
`parsing-pane.component.html/.ts` (+spec).

- Delete the right `<aside>` + `inspectoSplit="onboard-sample"` handle. **Operator revision
  (2026-07-30): the strip is mounted by the Parsing pane, not the shell** — it was first put above
  the stage outlet (common to every stage), which made it dead weight on Collection/Publish. The
  shell renders no sample UI at all; state stays session-held in `OnboardingStateService` so
  downstream stages keep reading the thread. Skipped in the plugin-ingester branch.
- Restyle the panel as a horizontal strip: one header row (file name · line count · compact thread
  chips for raw/parsed/cast replacing the "After parsing/After schema" blocks · Replace/Clear/
  Choose file/Paste actions · collapse toggle); expanded body = the raw `pre`,
  `RAW_PREVIEW_LINES` 8 → 40, max-height ~50vh, scrollable. Collapse signal, default expanded.
- Side effect (win): the sample is now visible below `lg` breakpoint (the aside was hidden).
- Capture semantics unchanged (256KB cap, session-held, invalidates downstream previews).

## Part C — Onboarding Parsing pane: guided flow, autodetect, tree view

Files: `parsing-pane.component.ts/.html` (+spec), `schema-mapping-pane.component.ts` (+spec),
new `parsing-sniff.ts` (+spec) in the onboarding dir; `parser-tree.component.ts` relocation (below).

- Pane order (sample strip sits above via the shell): file type toggle → options schema-form →
  Test/Save → results. Widen the results region (drop `max-w-3xl` for the preview block).
- **Format sniffer** (pure fn in `parsing-sniff.ts`): `sniffFrontend(text)` — NDJSON/JSON-array via
  JSON.parse probes; delimiter sniff over `,` `\t` `|` `;` (≥2 columns, consistent across first ~20
  lines); else null. Renders as a suggestion chip ("Looks like NDJSON — use JSON?") with one-click
  apply (sets frontend + prefills delimiter); never auto-applies.
- **Tree view**: for the `json` frontend add a Table | Tree toggle on the results.
  Tree = client-side parse of the sample's first ~50 records → `ParserTreeNode` forest (converter in
  `parsing-sniff.ts`), rendered by the existing presentational `app-parser-tree`. **Relocate**
  `parser-tree.component.ts` (+spec) from `modules/admin/pipelines/` to shared
  `app/inspecto/components/` and update the pipelines imports — cross-feature component imports are
  what the feature architecture bans (angular-ui skill governs; if it blesses cross-feature imports,
  skip the move). Under the tree, an honest note: nested values land as JSON text in one column —
  the engine reads top-level keys only (flatten DSL is backlogged).
- **Type autodetect** (pure fn): `suggestTypes(columns, rows)` → per-column
  `VARCHAR|DOUBLE|DATE|TIMESTAMP` (all-non-null-values-parse heuristics, conservative fallback
  VARCHAR — exactly the four honestly-castable types). The Schema stage's `deriveFromSample()`
  ([schema-mapping-pane.component.ts:162](inspecto-ui/src/app/modules/admin/catalog/onboarding/schema-mapping-pane.component.ts))
  uses the suggestion instead of hardcoded `'VARCHAR'`, marked "suggested"; the real TRY_CAST
  Validate types stays the verdict. No state-service change needed — the pane computes from
  `state.parsePreview()` it already reads.

## Part D — Parser config dialog reflow (Pipelines, stays mock-honest)

Files: `modules/admin/pipelines/parser-config.dialog.html/.ts` (+spec).

- Reorder the config step: (1) **Sample content full-width on top** — add a "Choose file" upload
  (text read, 256KB cap, same as onboarding) beside Test parse; keep paste/edit textarea + seeded
  samples; default height ~9rem → ~16rem (more lines); (2) Grammar + Parser type selects;
  (3) Properties full width, with the ASN.1 module picker + Grammar source stacked beneath;
  (4) Record viewer unchanged (table/tree). Keep fullscreen/per-pane maximize working.
- No new backend claims; the endpoint stays mock-only and that stays documented.
- Spec: add the file-upload test; adjust any layout-dependent assertions.

## Part E — Docs + backlog (same change)

- `docs/okf/frontend/features/onboarding.md`: rewrite the Collection-stage section (connection-first
  model), sample-as-thread section (top strip), parsing-stage flow (sniffer, tree view, suggested
  types).
- `docs/BACKLOG.md`: add **JSON flatten DSL (engine)** — unlock `parsing.json.records_path`
  (JSONPath subset) + nested field selectors (`$.a.b.c`) through `PipelineConfigParser` →
  `DuckDbCsvIngester.buildJsonReadSpec` → `ComponentPreview` → schema-stage selectors (DuckDB's
  `json_extract_string` already accepts nested paths — the engine just never exposes them); note
  XML/ASN.1 frontends are gated on the asn-parser integration decision (separate workstream), and
  the Parser dialog's `/components/grammar/preview` backend remains unimplemented.
- Persist this plan to `docs/superpower/parsing-ux-reflow.md`.

## Verification

1. **Apply the `angular-ui` skill BEFORE any edit** (binding). GLOSSARY conformance on all new copy.
2. Unit: vitest for every touched/new spec (collection pane, shell, sample panel, parsing pane,
   sniffer, schema pane, parser dialog, parser-tree at its new path).
3. Full UI gate per `build-verify`: lint + full `npm test` + production build (no reactor run —
   backend untouched).
4. Live: launch the UI dev server (`preview_start` via `.claude/launch.json`), walk
   Catalog ▸ Onboard Stream → Collection (toggle both modes, create-connection path) → capture a
   sample → Parsing (sniffer chip, test, table/tree) → Schema (suggested types), plus the Pipelines
   Parser dialog reflow; screenshot proof. Mock backend is fine — both previews are mocked offline.
5. Commit per `release-workflow` (feat:, master-only); **no push without an explicit ask**.
