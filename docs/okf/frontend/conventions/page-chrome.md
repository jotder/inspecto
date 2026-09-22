# Page chrome — the shared header, filter bar, tiles, tabs and bulk actions

**One header, one filter bar, one empty state, one way to degrade, one way to ask for a choice.**
The as-built facts of the UI consolidation programme (plan archived 2026-09-22 —
[`ui-consolidation-plan.md`](../../../archived-documents/plans-archive/ui-consolidation-plan.md)); the binding
rules live in the `angular-ui` skill and the live examples at `/design`.

## What a routed pane is built from

| Piece | Component | Owns |
|---|---|---|
| Header | `<inspecto-page-header>` (`inspecto/components/page-header.component.ts`) | the page's ONLY `<h1>` at the shared `text-title` size (22 px / 600), a one-line subtitle that expands on click, the `?` explain affordance fed by the pane's glossary `terms`, `[badge]`, `[actions]` and `[tabs]` slots, `backLink`/`backLabel` for detail pages, `mono` for identifier titles, `eyebrow` |
| Filter row | `<inspecto-filter-bar>` | collapsed to a Filter toggle with the active count, a removable chip per active filter, Reset; fields open in a `<form>` beneath; the host queries on `(apply)`, never per keystroke |
| KPI tile | `<inspecto-stat-tile>` | label · value · hint, tabular numerals; an ABSENT value renders as an em dash with its reason, never as a `0` nobody measured; `contentValue` when the figure is a projected badge |
| Section strip | `<inspecto-section-tabs>` | a labels-only `mat-tab-group` with a count pill per section; the HOST renders the content, so nothing hides in a lazily mounted tab body |
| Selection actions | `<inspecto-bulk-actions>` | nothing at 0 selected, then a count chip and ONE `Actions ▾` menu with destructive entries after a divider |
| Empty chart | `<inspecto-chart>` | renders `<inspecto-empty-state>` when a series has no points, and on the host's `[empty]` verdict — a row of explicit zeros is indistinguishable from "nothing has run yet", and only the pane knows which |

48 routed panes render the header (2026-09-22); Link Analysis and Geo Map through its **`compact`** mode — one
40 px row, no padding or border, subtitle inline — because the full-bleed header overflowed their bounded IDE
row (Link Analysis to 1591 px in an 886 px viewport). The Pipelines editor's toolbar row keeps its own
structure and adopts only the compact typography: that row IS a toolbar, not a page header.

## The rules the pieces enforce

* **One `<h1>` per page.** `[headingLevel]="2"` exists for the one case a header is not the page — the `/design`
  live example, or a section mounted inside a pane that already owns the heading. Settings sections are
  dual-hosted this way; the Settings shell itself steps down to an eyebrow once a section is open, because
  `/settings/<section>` shipped with TWO `<h1>`s for months.
* **One filled primary action per page**, at the right end; everything else is an icon button or an overflow
  menu; refresh is always an icon button.
* **An absent optional module is `isFeatureAbsent(err)` (`inspecto/api/api-base.ts`), not an error.** Status
  `0/404/502/503/504` means "not deployed here"; the pane explains it in place with an info `<inspecto-alert>`
  and stays silent. It is duck-typed on `status` on purpose: an `instanceof HttpErrorResponse` gate answers
  "not absent" for a plain error object and puts the red toast back. `502` is the one every hand-rolled
  guard missed — behind `proxy.conf.json` a stopped backend arrives as a gateway error.
* **Grid headers wrap, they never clip.** `wrapHeaderText` + `autoHeaderHeight` + `minWidth: 96` in
  `INSPECTO_DEFAULT_COL_DEF`; `headerHeight` is deliberately NOT pinned in the theme params or the wrapped row
  cannot grow. Rows are 34 px.
* **The retired entity word stays off the screen.** "Batch" survives only as a grouping verb and in wire and
  persisted spellings (`batch_id`, `/runs/{n}/batches`, `totalBatches`, the `_batches_` ledger files, the
  established "batch ledger" phrase); every operator-facing label says **Consignment**. `[terms]` must be
  canonical glossary entries — `'Batch'` was being handed to the explain dialog, which has no such entry.
* **Titles follow their nav label** — Jobs (not "Scheduler"), Overview (not "Dashboard", a colliding glossary
  concept). Changing the nav instead is three edits, because the Access Catalog is derived from the nav tree.

## Traps measured on the way (each cost a cycle)

* 🔴 **Projected content goes to the FIRST matching `<ng-content>`, and a slot inside an un-rendered `@if`
  branch swallows it.** Adding the header's compact branch, with its own `[actions]` slot, silently emptied
  every standard pane's action row — the spec caught it, the build did not. Capture each slot once in an
  `<ng-template>` and stamp it with `*ngTemplateOutlet`; the header does exactly this.
* 🔴 **A `MatTabGroup` whose `[selectedIndex]` is bound to a getter over the selected id springs back on
  click.** Material decides whether to emit in `ngAfterContentChecked` by comparing the clicked index against
  the value its input carries; a derived binding re-asserts the old index in the same pass. Hold the index in
  the component.
* ⚠ **A synthetic click cannot drive a Material tab in jsdom** — the first one is swallowed whichever element
  it targets, later ones alternate by attempt order. Drive the component's handler; prove the click in the
  preview.
* ⚠ **An overlay that stops `keydown` propagation for its own controls swallows Escape too.** The
  Notifications panel could not be closed from the keyboard until `if (e.key !== 'Escape') e.stopPropagation()`.
* ⚠ **`ngModel` inside a `<form>` throws NG01352 without `standalone: true`** — the filter bar's panel became a
  real form (Enter submits; a div with a `keyup` handler fails `interactive-supports-focus`) and every control
  in it needed the option.
* ⚠ **A header codemod refuses three shapes:** a pane whose first `<h1>` sits inside a control-flow block (the
  cut crosses the block), an import inserted after the last line that *starts* an import (it lands inside a
  multi-line statement), and a bounded editor pane (full-bleed header overflows). 34 panes converted by codemod
  with a structural check that no `@if/@for/@switch/@case` block changed count; 14 by hand.

## Deliberately open

* `MAT-SELECT-SWEEP-1` (BACKLOG §4): 23 templates still use `mat-select` for a single choice; decision D8 scoped
  the sweep to Geo Map, the Link Analysis dock and toolbox, and Assistant. Table cells, grid toolbars, the two
  genuine multi-selects and the two per-row pattern-step editors stay dropdowns by rule.
* `EMPTY-GRID-HSCROLL-1` (BACKLOG §4): Incidents and Cases draw a horizontal scrollbar on an empty grid because
  their column minimum widths exceed the pane at narrow widths.
* `JOB-RUNS-DIALOG-DEAD-1` (BACKLOG §4): `jobs/job-runs.dialog.ts` has no opener.
* Backend, not UI: `SCHEMA-FILE-RESOLVES-AGAINST-CWD-1` and `HEAD-RESPONSE-STREAM-CLOSED-1` (BACKLOG §4).

## References

* Skill: `.claude/skills/angular-ui/SKILL.md` §4 (the components) and its measured-trap notes
* Gallery: `/design` (`modules/admin/design-system/`)
* [Accessibility](accessibility.md) · [Design tokens & styling](design-system-tokens.md) ·
  [Errors & connectivity](errors-and-connectivity.md)
* Archived plan, provenance only: [`ui-consolidation-plan.md`](../../../archived-documents/plans-archive/ui-consolidation-plan.md)
