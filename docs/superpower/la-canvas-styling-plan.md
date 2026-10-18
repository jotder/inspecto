# Link Analysis canvas styling and pack relabels (i2-gap 3) - plan

Status: in flight (2026-10-18). Residual of `LA-EDITIONS-RESIDUALS-1` item 2 plus the Entity-Type styling ask.

## Scope (decision-free slice, built)
- The canvas **legend** (`inspecto-link-analysis-legend`, used by the studio and the saved-view widget) draws the
  active Industry pack's relabels: `link_kind.<kind>` for link-kind chips, `entity_type.<kind>` for node rows.
  A key the pack does not state falls back to the raw kind (the neutral label the canvas already shows).
  Only the DISPLAY text changes: `data-kind`, the hidden-kind set and the saved view keep the raw kind.
- Reads the active profile through `termLabel(domainProfile(activeDomainProfileId()), ...)`, so it is reactive when
  the packs arrive and needs no new input on either host.

## Already present (not rebuilt)
Per-kind colour, shape (circle/square/diamond/...) and line pattern/size are the Display menu's
`GraphDisplayOptions` (`nodeColors`, `nodeShapes`, `edgeColors`, `edgePatterns`, `edgeSizes`), persisted with a saved view.

## Decisions (assistant-decided on the operator's delegation 2026-10-18; PENDING OPERATOR CONFIRMATION)
1. **Per-Entity-Type icons: BUILT (option 1, pending operator confirmation).** Nine Heroicons outline glyphs (MIT; the
   set `assets/icons/heroicons-outline.svg` already ships) were inlined into `GLYPH_LIBRARY` (`catalog-graph.ts`, the
   attribution is in the comment there) and keyed by Entity Type id in `ENTITY_TYPE_GLYPH` (subscriber, imsi, imei,
   msisdn, wallet, account, agent, handset, cell). `<inspecto-graph-view [entityIcons]>` (Link Analysis sets it) draws the
   icon tile; an explicit per-kind shape override WINS; a type with no glyph keeps its plain shape. No runtime sprite
   extraction, no async seam, no pack field. The shared icon picker also gains these glyphs.
2. **Conditional formatting: DEFERRED.** Open options: rule shape (attribute / measure predicate -> colour, shape,
   size, halo), persistence (saved view `display` vs the Space), precedence against per-kind overrides and the
   high-connectivity / unexpanded / selection halos, client-side evaluation only.
3. **Pattern picker relabel: BUILT** (`pattern.<pack id>` term -> picker label; its `definition` replaces the
   description when stated; ids and the loaded pack stay raw). **Typology terms (`term.<id>`: SIM box, IRSF, Wangiri,
   Float): no surface exists** - they are glossary-only entries and the SPA draws no glossary; nothing built. Follow-up: a **glossary popover** (not built).

## Tests (unit)
`link-analysis-overlays.component.spec.ts`: a pack installed with a link-kind and an entity-type term relabels the chip
text and node row, keeps `data-kind` raw, and the legend stays axe-clean; no pack = raw kinds.
`link-analysis-toolbox.component.spec.ts`: pack term relabels the picker option and description; ids and an unrelabelled pack unchanged.
