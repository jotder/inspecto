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
- the Pipelines editor's **`parse`** node — since 2026-08-15 either the right-dock **Parse drawer** or the
  `GrammarEditorDialog`, split by the rule below — see [Pipelines](pipelines.md).

## Two hosts in the Pipelines editor — and the dialog is nearly gone (2026-08-15)

Definition-surface P3a moved the common case out of the popup: a **`parser.delimited`** node defines in the
right-dock drawer (`<app-pipeline-parse-definition>`), over this same shared editor; P3b added
**`parser.fixedwidth`**, P3c **`parser.asn1`**, and P3d **`parser.json`** + **`parser.text_regex`** +
**`parser.plugin`** to the same pane. Since the templates-not-bindings change that includes
**grammar-bound** nodes, which the host materialises into an inline copy on open (`definitionDraft`) and
which migrate for real when the operator Applies.

**One pane serves every per-format subtype.** `PARSE_NODE_FRONTENDS` (exported from the pane) maps node
type → frontend and is the ONE list deciding both which parse nodes reach the drawer (the host's
`isDrawerParse`) and which format the editor is then locked to, so the two cannot desync. B6 banned a
generic parser **node type** with format tabs — not component reuse; the type still locks the format, so
no tabs appear and each format keeps its own palette entry. A second copy of the pane would only drift.
A parse type **absent** from that map keeps the dialog.

⛔ **The drawer template does not enumerate the subtypes.** It is `@if (dn.type === 'acquisition') … @else
<app-pipeline-parse-definition>`, because `openDefinition` only ever opens for `acquisition` or
`isDrawerParse`. It used to carry one `@case` per subtype — a second copy of the routing rule, free to
drift from the map that actually decides it. Adding a format is now a `PARSE_NODE_FRONTENDS` entry and
nothing else on the template side.

`GrammarEditorDialog` is left with two jobs: a **dangling** `use: grammar/<id>` whose component does not
exist, and **binary fixed width**. The plain `parser` type still has no drawer pane either — the plugin
subtype covers only nodes with an *authored* `parsing.plugin` block, not the unbound generic type.
⚠ "P3d retires the dialog entirely" was always false and stays false:
the dangling and binary cases are deliberate keeps. The dangling case is deliberate: with nothing to resolve there is no faithful copy
to migrate to, and seeding the drawer with defaults would replace the operator's broken reference with a
silently invented Grammar. The binary case (`record: bytes`, detected by `isBinaryFixedWidth`) lifts to
`parser.fixedwidth` like any other but carries its geometry in `processing.ingester_config`, so the pane's
slice table would govern nothing — operator decision 2026-08-16.

⚠ The earlier framing of this split — that bound nodes stay on the dialog *permanently* because updating a
bound Grammar is a write route the drawer lacks — is **superseded**. The resolution was to remove the
binding, not to build the write route.

## Shared surfaces the Parse pane composes

`<inspecto-segments-editor>` (`inspecto/segments/`) maps a hierarchical parser's decoded record tree onto flat
segment schemas. It lives in `inspecto/` rather than the Onboarding feature because **a feature may not import
another feature** and both hosts now need it — the `connection-form.dialog` relocation precedent. Like the
grammar editor it has **no write path**: the two hosts write different blocks (Onboarding's
`parsing.plugin.segments`, the Parse drawer's `parsing.asn1.segments`), which is exactly why the write stays
host-side.

## The last two built-ins — JSON and text/regex (P3d, 2026-08-16)

The plan's icon table always listed **six** formats while B6 named only four node types, so `json` and
`text_regex` had no type of their own and fell back to the plain `parser` plus the dialog. They now have
one each. The slice is deliberately unremarkable: both are ordinary `ParsingFrontend` members the shared
editor already rendered, `normalizeFrontend` already read and `clearMissingRoots` already cleared, so the
whole UI half was two `PARSE_NODE_FRONTENDS` entries. Everything else was the engine's five touchpoints
(`BuiltinNodeType`, `LOWERABLE`, `isParserType`, `SUBTYPE_FRONTENDS`, `USE_HOME`) plus the mock mirrors.

* Neither is implicit — **delimited alone is the parser's default** — so every such file retypes on a lift,
  and P3a's delicate "explicit only, don't reshape what's deployed" caveat costs nothing here (the same
  reasoning P3b recorded for fixed width).
* Each answers to **one** spelling, so unlike fixed width there is no alias to preserve.
* Each homes `grammar/` and nothing else. ⛔ **Not `ingester/`**: only `parser.asn1` gets that, and only
  because the config parser *synthesizes* the binding at load. On a plain built-in an `ingester/` ref is an
  authoring mistake and must refuse rather than be dropped.
* `isParserType` is now `PARSER` ∪ `SUBTYPE_FRONTENDS.keySet()` in both languages, rather than a chain of
  `equals` growing one arm per format.

## The generic custom-plugin subtype — `parser.plugin` (P3d slice D, 2026-08-16)

The last per-format slice, closing out P3d. Unlike JSON/text-regex, `parser.plugin` is not a built-in
`ParsingFrontend` — it hosts *any* served plugin whose `ingestable && ingesterClass` are both set and
that isn't already claimed by a dedicated subtype (today: everything except `asn1`), via a `<mat-select>`
the pane owns directly.

* **The pane fetches its own catalog** (`inject(ParsersService).list()`), rather than reaching through
  `@ViewChild` into the shared editor's internal `served()` signal — the same async-ordering trap P3c's
  ASN.1 slice hit twice (catalog resolving after `[type]` was set, `specs` rebuilding from defaults on a
  late-arriving spec list). A second independent fetch costs one extra network call and buys immunity
  from that whole bug class.
* **`USE_HOME` gets `grammar/` + `ingester/`, mirroring the plain `parser` type** — a plugin's ingester is
  authored in `parsing.plugin.ingester`, not synthesized, so this looks like it shouldn't need
  `DERIVED_USE` the way ASN.1 did. It still does: `PipelineLift.parserNode()` computes
  `use = "ingester/" + fqcn` unconditionally whenever `s.ingesterClass() != null`, regardless of subtype,
  so the LIFT presents that ref on *every* plugin-backed node before it is retyped — `DERIVED_USE` maps
  `parser.plugin → ingester/` for the same reason P3c needed it for ASN.1.
* **Cannot be proven end-to-end offline.** The mock catalog is a verbatim transcription of the real
  server's `Parsers.catalog()`, and the real server has no second ingestable plugin besides `asn1` — so
  only the node type, drawer routing, and the empty-catalog refusal message are preview-verifiable here;
  the actual pick → apply → save flow needs a real deployed third-party plugin.
* **Found, not fixed**: the plain `parser` type's `ingester/` use-home has no `DERIVED_USE` counterpart,
  so a legacy `processing.ingester`-configured pipeline that was never retyped to `parser.plugin` would
  hit `UNKNOWN_USE_KIND` on validate. Pre-existing, unrelated to this slice, flagged as a follow-up.

## A SERVED format in the pane — ASN.1 (P3c, 2026-08-16)

`asn1` is the first subtype that is **not a built-in `ParsingFrontend`**: the editor hosts it as the served
parser it already was (form built from `GET /parsers`' `grammarSchema`), while the node type locks it exactly
like the built-ins. Three consequences, each of which cost a test:

* **The pane assembles the block itself.** `GrammarEditorComponent.value()` is the built-ins' shape — it would
  stamp the editor's *internal* frontend (`delimited`) — so the pane's `parsingValue()` builds
  `{frontend: 'asn1', asn1: …}` from `grammar()` instead. ⛔ Don't "simplify" it back to `value()`.
* **`asn1.segments` is AUTHORED here** (P3d re-scope, 2026-08-16 — it was carried verbatim for one day).
  The pane projects the shared `<inspecto-segments-editor>` via `[grammarExtras]` and writes one schema
  toon per segment **before** emitting a block that references them — a config must never name a file that
  does not exist yet, so a failed write applies **nothing** and the pane stays dirty. This is the pane's
  ONE write and it is not a P2 exception: P2's rule is that a stage's own companion artifact stays a pane
  write, and it names segment schemas explicitly (the reusable `grammar` component is a THIRD entity,
  which is why *that* one is still emitted to the host). ⚠ Apply therefore hits the server while the node
  change is still only in-memory — discarding afterwards can orphan schema toons, exactly as Onboarding's
  `savePlugin` can. A **template** strips segments: a template is a Grammar copy, and segment paths are
  deployment, not grammar.
* **A keys-only segment refuses to Apply.** Saved segments re-hydrate from the toons they point at (keys
  AND columns); when a read 404s that segment arrives column-less, and a segment with no columns cannot
  describe a Table — so Apply refuses rather than writing an empty schema over it.
* **A served form cannot author what the catalog did not serve.** With the plugin absent the schema form holds
  no `asn1.*` keys at all, so Apply would write an EMPTY grammar over a deployed one and report success. The
  pane refuses instead (`asn1Unavailable`). The editor's own "jar not deployed" banner does **not** cover this:
  it keys on the `configuredIngester` FQCN input, which the drawer never passes.

⚠ **The shared editor's catalog fetch runs in its CONSTRUCTOR**, so with a synchronously-resolved source it
completes *before* Angular sets the inputs. `configuredIngester` already re-attempted for that reason; `type`
did not, and a served type silently presented as the default built-in. Both setters now re-attempt against an
already-arrived catalog — production HTTP is async, so only the served formats and tests ever saw it.

### 🔴 A spec swap used to discard the seeded values (fixed 2026-08-16)

`InspectoSchemaFormComponent.specs` **rebuilds every control from defaults** when reassigned, and Angular does
not re-run the sibling `initial` setter unless *that* binding's reference also changed. So any host whose spec
set resolves **asynchronously** seeded first and had its values thrown away second. For ASN.1 that meant a
deployed config opened with an **empty grammar**, and Apply would then write the emptied block back. The setter
now replays the last seed, exactly as it already replayed `extraValidators` for the same reason.

⚠ **This was invisible to every unit test** and was found only by driving the offline preview: with a
synchronous `of(catalog)` the plugin resolves *before* the specs bind, so the values survive and the suite is
green. The regression spec therefore uses an rxjs `delay` to reproduce production ordering — **when a component
reacts to a fetch, a test that resolves it synchronously is testing a different component.**

- **`[lockType]`** hides the format picker: for a per-format node the format *is* the node's type
  ([per-format node types](../../backend/pipeline-graph/design.md)), so offering a switch could only author
  a block the save path refuses with `PARSER_FRONTEND_MISMATCH`. The pane stamps its **derived** frontend
  (`frontend()`, read off the node type) onto the block it emits, never a literal.
- The pane follows the P2 pure-pane contract: `[node]` in, `(applied)`/`(dirtyChange)` out, no injected
  state, pristine reached by re-seeding from its own input (a failed host save must leave it dirty).
- ⚠⚠ **Importing this editor into a host drags `<inspecto-data-table>` in, which injects the REAL
  `MatDialog`.** A spec's plain `{provide: MatDialog, useValue}` is then **silently ignored** and every
  `dialog.open` dies inside Material with `Cannot read properties of undefined (reading 'push')` — ten green
  tests in `pipeline-editor.component.spec.ts` broke exactly this way. Use `TestBed.overrideProvider`.

Unlike [Collector configuration](collector-config.md), these two surfaces were **not** already one
feature before 2026-08-04: they shared only the renderer layer (`fieldSpecsToAttributes`,
`ParsersService`, `<app-parser-tree>`, `<inspecto-data-table>`, `<inspecto-schema-form>`), and the gap
between them held two live defects, both fixed as part of this unification (§ below).

## One store — always inline; templates are copies (2026-08-15)

A parse node's Grammar lives in its own top-level **`parsing:`** block, full stop — carried verbatim
on the node's config, never key-mapped (a key-by-key translation between `parsing:` and the legacy
`processing.csv_settings`/`schema_file`/`schemas` keys is lossy in both directions; see "Two
competing keys" below). An operator who wants to reuse a Grammar saves it as a **Grammar Template**:
that writes a `grammar` registry component and **leaves the node's block exactly where it was**.
"Start from a template" copies a stored Grammar into a node. Copies, never links.

⚠ **This reverses the 2026-08-04 contract**, under which "Save as reusable Grammar" MOVED the block
into the component and bound the node via `use: grammar/<id>`, so editing the component changed every
pipeline bound to it. The `use:` form remains **read-supported** — `PipelineConfigParser.resolveGrammarRef`,
the `PipelineEditable` lift/lower translation and `UNKNOWN_USE_REF` are all unchanged, because a
hand-authored file may still carry one — but nothing authors it, and **opening a bound node in the
editor migrates it to an independent inline copy on save**. Rationale and slices:
[`plans-archive/grammar-templates-not-bindings-plan.md`](../../../archived-documents/plans-archive/grammar-templates-not-bindings-plan.md).

🔴 **A Grammar component has TWO shapes, and reading one wrong loses data silently.** It is either the
**legacy flat** `csv_settings`-style map (`{delimiter, has_header, …}` at top level — every
pre-2026-08-04 component, and everything the Components registry form still writes) or an
**extracted `parsing:` block** (`{frontend, delimited: {…}}`). The editor seeds its property sheet by
flattening the block to `delimited__*` keys, so feeding it a flat component matches **no spec key** and
the form falls back to its **declared defaults** — a component storing `delimiter: "|"` displayed, and
would have re-saved, `","`. This was live on the dialog's bound-node path until 2026-08-15. Always read
component content through **`grammarContentAsParsingBlock()`** (`inspecto/grammar/grammar-block.ts`),
which lifts legacy top-level csv settings under `delimited:`; **`grammarSeedsFrontend(content, frontend)`**
is its companion gate (generalised from `isDelimitedGrammar` by P3b, since the picker must now filter per
format) and deliberately does NOT reuse the editor's `normalizeFrontend`, which maps anything unrecognised
to `delimited` and would offer an xlsx/asn1/html component as a delimited starting point.

⚠ That gate is **asymmetric on purpose**: a component declaring *nothing* qualifies for **delimited only**.
Undeclared means the legacy flat shape, and delimited is the engine's implicit default — offering such a
component to a fixed-width node would seed a slice table from a `{delimiter: '|'}` map, inventing a
Grammar nobody wrote. Each frontend's accepted spellings live in `FRONTEND_ALIASES` (delimited also
answers to `csv`/`dsv`; fixed width to `fixedwidth`/`fixed_width`).

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
