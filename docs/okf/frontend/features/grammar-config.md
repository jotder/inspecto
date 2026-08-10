---
type: Feature
title: Grammar configuration (the one surface)
description: The single shared surface and single store contract behind both Grammar-authoring screens.
resource: inspecto-ui/src/app/inspecto/grammar/grammar-editor.component.ts
tags: [feature, parsing, onboarding, pipelines, grammar]
timestamp: 2026-08-04T00:00:00Z
---

# Grammar configuration

Two screens author how raw bytes become rows — a **Grammar** (`docs/GLOSSARY.md` §Grammar; ⛔ never
"parser config" / "parse options", banned synonyms) — and they are now **one** feature:

- Onboarding's **Parsing** stage (`/catalog/onboard/<name>/parsing`) — see [Onboarding](onboarding.md);
- the Pipelines editor's **`parse`** node dialog (`GrammarEditorDialog`) — see [Pipelines](pipelines.md).

Unlike [Collector configuration](collector-config.md), these two surfaces were **not** already one
feature before 2026-08-04: they shared only the renderer layer (`fieldSpecsToAttributes`,
`ParsersService`, `<app-parser-tree>`, `<inspecto-data-table>`, `<inspecto-schema-form>`), and the gap
between them held two live defects, both fixed as part of this unification (§ below).

## One store — inline by default, extractable to a component

A parse node's Grammar lives in its own top-level **`parsing:`** block by default — carried verbatim
on the node's config, never key-mapped (a key-by-key translation between `parsing:` and the legacy
`processing.csv_settings`/`schema_file`/`schemas` keys is lossy in both directions; see "Two
competing keys" below). An operator who wants to **reuse** a Grammar across pipelines explicitly
promotes it — "Save as reusable Grammar" — which writes a `grammar` registry component and binds the
node via `use: grammar/<id>`. Extraction **moves** the block; a node never carries both a `parsing:`
config and a `use: grammar/*` binding at once.

A Grammar component can itself be either shape now: the legacy **flat** `csv_settings`-style map
(`{delimiter, has_header, ...}` at top level — how every pre-2026-08-04 component was written), or an
**extracted `parsing:` block** (`{frontend, delimited: {...}, ...}` — how extraction writes one
today). `PipelineConfigParser` discriminates by a **nested** `delimited`/`plugin` root — the one
structural difference between the shapes — and resolves either one; inline config on the node still
wins over either shape, same as it always has.

## One component — `<inspecto-grammar-editor>`

`inspecto/grammar/grammar-editor.component.ts`: format picker (built-ins ∪ the served `GET /parsers`
catalog, degrading to built-ins-only on a fetch failure — the Onboarding behaviour; the old dialog's
empty dropdown was strictly worse), the sniff-and-suggest chip, sample input (`sampleMode: 'own' |
'host'`), the schema-driven property sheet, the fixed-width slice table, Test parse (table or record
tree), and the unserved-plugin warning. **No write path** — hosts read `value()` (a built-in's
`parsing:`-shaped block) or `grammar()` (a plugin's nested options alone) and persist through their
own route, exactly the rule `<inspecto-collector-config>` and `<inspecto-enrichment-editor>` follow.

- ⚠ **Reassigning `<inspecto-schema-form>`'s `specs` rebuilds every control from its declared
  default**, and a stable `initial` reference is not re-applied — switching file format must carry the
  in-progress values across the swap (`seed.update(...)` before `frontend.set(...)`), or the operator's
  typing vanishes. Found again here after first appearing in the collector-config extraction.
- ⚠ **A saved plugin Grammar is identified by `ingesterClass` (the FQCN), never a parser id** — a
  stored config has no id, only the class name, so re-selecting the served parser on load needs the
  full catalog. `configuredIngester` is an `@Input` **setter**, not a plain field: inputs are not bound
  when the constructor runs, so both load orders (catalog-then-input, input-then-catalog) must retry
  the rehydrate.
- ⚠ **Selecting a preview-only plugin must not mark the editor dirty** — only an *ingestable* plugin
  (one with a served `ingesterClass`) is a real edit. A host that treats a preview-only pick as dirty
  raises an unsaved-changes guard the operator can never satisfy, because such a plugin has nothing
  to save.
- **Not absorbed: the segments editor.** Projected via `[grammarExtras]` because segments need one
  schema `.toon` written per segment *before* the block that references them — a host-owned write the
  editor deliberately has no path for. Only the Onboarding Parsing stage projects it; the Pipelines
  dialog leaves plugin Grammars preview-only (below).

## Both defects found in the gap, fixed before the UI unified

1. **A `use: grammar/<id>` binding never reached disk.** `PipelineEditable.lower()` translated only
   the `connection/` `use:` prefix; a grammar-bound parser node was either silently dropped or the
   save was refused `PARSER_NO_SCHEMA`. Fixed: `lower()`/the editable lift translate `use:
   grammar/<id>` ↔ `parsing.grammar` both ways, and `PipelineConfigParser.resolveGrammarRef` resolves
   the ref to `<configDir>/registry/grammars/<id>.toon` (the path `ComponentStore` writes).
2. **Two competing keys, and the editor wrote the loser.** Onboarding wrote top-level `parsing:`;
   the graph editor wrote `processing.csv_settings`, which the engine overlays *underneath*
   `parsing:` — so a pipeline-editor edit was invisibly masked by a stale `parsing:` block, and
   `parsing:` didn't even survive `lower()` far enough to be **displayed** back (it rode through as
   an unmodeled key). Fixed: the parser node now owns the top-level `parsing:` block directly,
   carried verbatim through `editableConfig`/`lower` — no key mapping, nothing to lose.

Both were pinned by a failing test before the fix landed (`PipelineEditableTest`,
`UnifiedParsingBlockTest`) — correctness shipped before the UI extraction, so the shared component
couldn't hide a defect behind a surface that merely looked unified.

## Plugin Grammars stay preview-only in the Pipelines dialog

A plugin parser (e.g. an ASN.1/BER decoder) needs per-segment schema files in addition to its
Grammar, and only the Onboarding Parsing stage can author those (the host-owned `[grammarExtras]`
write). The Pipelines `GrammarEditorDialog` lets an operator **test** a plugin Grammar but refuses to
**save** one, pointing at the Parsing stage instead — rather than writing a `parser_type` key no
engine code has ever read, which is what the dialog did before this unification.

## Deferred (logged to `docs/BACKLOG.md`)

- ~~**The unknown-`use:`-prefix refusal.**~~ **SHIPPED 2026-08-10** — see
  `okf/backend/pipeline-graph/pipeline-graph-design.md` for the as-built. Two claims this section made
  were wrong: `ComponentRegistry.resolve` *did* have a caller (`ComponentStore.get`; only `isKnown` had
  none), and the seam was `PipelineValidator.checkWiring`, not `ConfigSafetyValidator`. A binding whose
  kind is valid but whose named component is absent now refuses at save with `UNKNOWN_USE_REF` (422).
- **Slice 6's live browser smoke** was not run this shift (the preview pane denied navigation); the
  new `grammar-editor.dialog.spec.ts` (10 cases) covers the same seams (inline-default save, extract,
  bound-edit round-trip, unbind, legacy `parser_type` read, plugin-save refusal) but a real
  `ng serve` round-trip is still outstanding.
