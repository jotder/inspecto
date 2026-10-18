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

## Decisions owed (operator) - NOT built
1. **Per-Entity-Type icons.** Glyph source (heroicons subset vs an uploaded asset), and whether a pack may ship a
   default icon per `entity_type.<id>` (a pack "relabels, never adds a concept": an icon is a new pack field).
2. **Conditional formatting model.** Rule shape (attribute / measure predicate -> colour, shape, size, halo?),
   where it persists (saved view `display` vs the Space), precedence against per-kind overrides and the
   high-connectivity / unexpanded / selection halos, and whether it is evaluated client-side only.
3. **Pattern-picker relabel** (`pattern.<pack>`) and typology terms: separate surface, not in this slice.

## Tests (unit)
`link-analysis-overlays.component.spec.ts`: a pack installed with a link-kind and an entity-type term relabels the chip
text and node row, keeps `data-kind` raw, and the legend stays axe-clean; no pack = raw kinds.
