# Accessibility audit — `inspecto-ui` (WCAG 2.2 Level AA)

**Date:** 2026-06-16 · **App:** `inspecto-ui/` (Angular 21 + Material M2 + Tailwind on the gamma/Fuse shell)
**Standard:** WCAG 2.2, conformance target **Level AA** · **Scope:** the inspecto operator panes (`modules/admin/**`)
and shared components (`inspecto/**`); vendored Fuse auth/error scaffolding excluded.

This is the **manual** half of UI/UX-audit Long-term #2 — an **audit + findings report**. Fixes were originally
**deferred** to a separate prioritized pass; that pass has since begun, so §1 and §3 are **living** — a finding's row
and its §1 entry move together as it closes (**last closed: F1, F2; F4 retired as N/A — 2026-09-20**). The **automated**
half (axe-core regression gate) is described below and is live in CI.

> This is a self-assessment by heuristic walkthrough + automated tooling, not a formal third-party VPAT/certification.

---

## 1. Already remediated (Immediate + Short-term + Medium-term batches)

Verified still in place; not re-listed as findings:

- **1.4.11 / 1.4.3 contrast** — semantic status tones consolidated in `status-badge.component.ts` (100/800 light,
  900/200 dark ≈ 6–8:1); `text-hint` tokens lifted to AA in `theming.js`.
- **2.4.6 / 1.3.1 headings** — exactly one semantic `<h1>` per page (17 pages).
- **4.1.2 names** — icon-only buttons carry `aria-label` (shared `InspectoActionsCell` + layout buttons).
- **2.4.7 focus visible** — global `outline:none` replaced with a `:focus-visible` ring.
- **1.3.1 tables** — `scope="col"/"row"` across data tables.
- **3.3.1 / 3.3.3 forms** — Reactive forms with inline `<mat-error>`; `markAllAsTouched()` on invalid submit.
- **1.3.1 status not by color alone** — status badges always render the text token alongside the color.
- **2.2.2 pause/stop/hide** — the Events live-tail auto-refresh is opt-in via a toggle (not auto-playing).
- **prefers-reduced-motion** — `<inspecto-skeleton>` disables its pulse under reduced-motion.
- **1.1.1 chart text alternative (was F1)** — `chart.component.ts` renders the canvas with `role="img"` +
  an `altText` summary (chart type plus each category's first-series value, capped at 10). Shipped after
  this audit was written; the F1 row below is retained struck-through for provenance.
- **4.1.3 status messages (was F2)** — `<inspecto-data-table>` announces its displayed row count through a
  visually-hidden `role="status" aria-live="polite"` region, fed by the grid's own `rowDataUpdated` /
  `firstDataRendered` / `filterChanged` events. One shared region covers **every** grid host, so async
  loads, live-tail ticks and quick-filter keystrokes are all announced. Blank while `loading`, so a
  mid-fetch "0 rows" is never read out.

---

## 2. Automated gate (axe-core)

- **Wiring:** `axe-core` (devDependency) run inside the existing **vitest + jsdom** unit tests via
  `src/app/inspecto/testing/a11y.ts` → `expectNoA11yViolations(el)`. Runs in CI as part of `npm run test:ci`
  (the **Unit tests** step of `.github/workflows/ui.yml`) — no browser needed.
- **Covered today:** the shared design-system primitives — `status-badge`, `skeleton`, `empty-state`
  (`*.a11y.spec.ts`). Extend by adding an `expectNoA11yViolations(fixture.nativeElement)` assertion to any
  component spec.
- **Rules excluded in jsdom** (and why): `color-contrast` (needs real painting/layout — jsdom has none; contrast is
  covered manually above and the design-system token guard prevents off-palette colors), and the page-level rules
  `region` / `landmark-one-main` / `page-has-heading-one` / `html-has-lang` / `document-title` / `bypass` (meaningless
  against an isolated component fixture — they belong to a future route-level/browser pass).
- **Limitation:** unit-level axe verifies roles/names/aria/structure, **not** rendered contrast or full-page
  landmark/reflow behavior. Those need the real-browser pass noted in §5.

---

## 3. Findings (fixes deferred)

Severity: **Moderate** = a real AA gap affecting some users; **Low** = edge/needs-verification or minor.

| # | WCAG SC (level) | Severity | Location | Finding | Recommendation |
|---|---|---|---|---|---|
| ~~F1~~ | 1.1.1 Non-text Content (A) | ~~Moderate~~ **FIXED** | `inspecto/components/chart.component.ts` | ~~The Chart.js `<canvas>` has no text alternative.~~ Resolved — see §1. | — |
| ~~F2~~ | 4.1.3 Status Messages (AA) | ~~Moderate~~ **FIXED** | `inspecto/data-table/` (all grid hosts) | ~~Result counts and live-tail row additions are not announced.~~ Resolved 2026-09-20 in the shared data-table — see §1. | — |
| ~~F3~~ | 1.4.1 Use of Color (A) | ~~Low~~ **Conformant** | `chart-tokens.ts` series | **Verified 2026-09-20.** Chart.js renders a legend by default, so every genuinely multi-series chart labels its series in text. Only two charts disable the legend and **neither is multi-series**: the `gauge` branch in `viz/viz-render.component.ts` (a single value) and `objects/case-analytics.dialog.ts` (one dataset whose per-bar colors are decorative — each bar is identified by its x-axis label, not its color). The F1 `altText` also enumerates every label/value pair. Dash/pattern for line series remains an optional enhancement, not a conformance gap. | — |
| ~~F4~~ | 2.4.11 Focus Not Obscured (AA, **new in 2.2**) | ~~Low~~ **N/A** | — | **Premise does not hold** (verified 2026-09-20): there is no `position: sticky` / fixed header in authored source, and the classic layout's header and footer are both `relative` (`classic.component.html`), so they scroll away with the page and cannot obscure a focused control. No `scroll-margin-top` is needed. Re-open if a sticky toolbar is ever introduced. | — |
| F5 | 2.5.8 Target Size (Minimum) (AA, **new in 2.2**) | **Low** | `gamma-mat-dense` toolbar controls; ag-Grid action icons | Standard `mat-icon-button` is 40×40 (pass ≥24×24). Dense filter controls and grid action icons need spot-verification at ≥24×24 with adequate spacing. | Measure dense controls; bump to ≥24×24 (or add spacing exception) where short. |
| F6 | 2.5.7 Dragging Movements (AA, **new in 2.2**) | **Low** | ag-Grid column resize/reorder | These are drag gestures. ag-Grid exposes header keyboard interaction + a column menu, so a non-drag path likely exists — **verify**. | Confirm sort/resize/reorder are reachable without dragging; document the keyboard path. |
| F7 | 1.4.10 Reflow (AA) | **Low** | Toolbars/forms at 320px | Wide data grids may scroll horizontally (data-table exception applies). Verify filter toolbars and forms reflow without loss at 320px / 400% zoom. | Manual check at 320 CSS px; the toolbars already use `flex-wrap`. |

### Conformant / notable (no action)

- **3.3.8 Accessible Authentication (AA, new in 2.2)** — `/connect` accepts a pasted operator token; no cognitive
  test, paste allowed → **conformant**.
- **2.1.2 No keyboard trap** — MatDialog manages focus trap/restore → **conformant**.
- **3.2.3 Consistent Navigation** — single shared nav across layouts → **conformant**.

---

## 4. Remaining fix priority

F1 and F2 are **fixed**; F3 was verified **conformant**; F4 was **retired** as not applicable (see their rows).

Still open — **F5 / F6 / F7**, all three verification tasks that need a real browser (measure dense control
hit areas at ≥24×24; confirm ag-Grid's non-drag path for column resize/reorder; check reflow at 320 CSS px
and 400% zoom). None is a code change until the measurement says otherwise, and none can be settled in
jsdom — which is why they are the natural scope of the real-browser pass in §5, not a separate effort.

> ⚠ When closing a finding, update **both** its row here and §1 in the same change. F1 shipped well before
> this doc recorded it, so the audit advertised a deferred gap that no longer existed — check the code
> before scheduling work off this table.

## 5. Out of scope here (future)

- **Real-browser axe pass** (e.g. Playwright) to cover `color-contrast`, landmarks, full-page reflow — deliberately
  not added now (keeps CI browser-free per the project's lean-deps stance).
- Vendored Fuse auth/error pages (`modules/auth/**`).
- A formal third-party audit / VPAT.
