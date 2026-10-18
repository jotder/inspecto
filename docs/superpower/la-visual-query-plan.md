# Link Analysis - visual query builder (i2 "search-around") - plan

Status: PLAN + first slice built (2026-10-18). Active plan; on ship, distill into `docs/okf/frontend/features/link-analysis.md`
and move open items to `docs/BACKLOG.md`.

## Goal
An i2-style visual search: pick an entity type, add conditions ("follow link kind K, in direction D, to neighbour type T"),
run across the graph, and get the matching paths highlighted/added. Vocabulary per `docs/GLOSSARY.md` (Entity, Link, Pipeline etc.; the
UI says "link kind" and "entity type", never "Flow"/"Issue"/bare "Rule").

## Hard constraints
- The Investigation op vocabulary is CLOSED (`seed seedBy expand exclude excludeBy keep threshold window hide annotate snapshot`, plus `resolve`
  under its own decision). **No twelfth op.** The builder is a FRONT END over existing ops and the motif matcher.
- BACKLOG section 6 declines are not re-proposed (notably alternating expansion in Connect; Connect "only links among" forms).

## Design
A `SearchAroundSpec { startType?, conditions: {direction, linkKind?, neighbourType?}[] }` is a linear path motif, i.e. exactly a
`PatternStep[]` (step 0 = start type; each condition = one hop). It is NOT a new query language.

Two run modes, both existing machinery:
1. **On the loaded graph** - compile to steps, call `matchPattern` (client, `graph-analysis.ts`); matches become a `GraphSelection`
   (same path the motif builder uses). No backend, no op, not recorded in an Investigation. Bounded by the loaded graph / `ANALYSIS_NODE_CAP`.
2. **Beyond the loaded graph** - successive `expand` ops, one per condition, from the current frontier (the existing *Expand one hop* /
   choose-neighbours path, with its link-kind restriction). Recorded as ordinary `expand` entries, so the Dossier/court rendering
   needs nothing new.

Reuse map: motif builder + `PatternStep`/`matchPattern` (graph-analysis.ts) - pattern packs (`pattern-packs.ts`; a pack opens as a
spec via `stepsToSearchAround`, and a spec can be saved as a pack through the existing per-Space `pattern-pack` component) -
expand rungs (`investigation-expand-rung.component`) - choose-neighbours - pattern-result selection/highlight - plain-language step
rendering (`describeSearchAround`).

## Built (decision-free slice)
`inspecto-ui/projects/link-analysis/src/link-analysis/search-around.ts` - pure, framework-free: spec -> `PatternStep[]`,
inverse for non-temporal path motifs, validation (one condition required), plain-language description. Spec:
`search-around.spec.ts` (3 tests). No UI wired: the placement of the panel is a decision (below).

## Decisions owed (superseded by the block below)
1. **Placement/surface**: a new "Search around" mode in the existing Pattern section of the toolbox (builder gets a friendlier form view) vs a
   separate panel/dialog. Affects UI only.
2. **Mode 2 semantics**: running beyond the loaded graph as a chain of `expand` ops needs the expand rung to filter by NEIGHBOUR TYPE as well
   as link kind. If `expand` today restricts only by link kind, a neighbour-type restriction is either a client-side post-filter (admits
   non-matching neighbours into the Working Set first - not equivalent) or an extension of `expand`'s parameters (a change to an
   existing op, needing a decision entry though not a twelfth op). Verify the current `expand` params before choosing.
3. **Branching / OR conditions** ("neighbour type A or B", several outgoing branches): linear motifs cannot express them; branching motifs
   exist only as `BranchStage` fan-in/fan-out thresholds. Out of scope until decided.
4. **Recording**: whether a loaded-graph search leaves any Investigation trace (default here: none, like the motif builder today).
5. **Temporal conditions**: reuse `afterPrevious`/`maxGapHours` in the form (needs the pane's time column) or omit in v1.

## Next steps once decisions land
form view component (OnPush, schema-free reactive form, `<inspecto-option-picker>` for kinds/direction, axe assertion) - wire to toolbox
Pattern section - mode 2 after decision 2 - update OKF + `/design` gallery if a new shared pattern appears.

## Decisions (assistant-decided, pending operator confirmation)
1. Placement: a "Search around" view inside the toolbox Pattern section (toggle beside the step list), not a new panel.
2. Beyond-the-graph mode: v1 = link kind + direction only (verified: the expand rung's settings are Direction and Link kinds, no neighbour type).
   Neighbour-type conditions work on the loaded graph only; no client post-filter. Extending `expand` with a neighbour type is deferred and
   needs a decision entry (not a twelfth op).
3. No OR / branching in v1. 4. A loaded-graph search leaves no Investigation trace (as the motif builder). 5. No temporal conditions in v1
   (a temporal motif shows no Search around view, only the step list).

## Built (2026-10-18, second slice)
`link-analysis-search-around.component.ts` (presentational, OnPush; start type, per-condition direction / link kind / neighbour type,
words list, Search around button) wired into `link-analysis-toolbox.component` (`aroundView`, `aroundSpec`, `setAroundSpec`; the spec
edits `patternSteps`, Find matches path = `runPattern`). Residual: the beyond-the-graph run (chained `expand` with link kind + direction)
is NOT built; the form states that Expand never follows a neighbour type. Not driven in a live preview.
