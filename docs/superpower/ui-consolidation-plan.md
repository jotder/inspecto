<!--
  ACTIVE PLAN — docs/superpower/
  Created 2026-09-22 from a full drive of the SPA in the preview (40 routed panes, 4 header flyouts,
  1 create dialog) against the auth-free Professional bundle on :8080 with `ng serve` on :4204.
  Retire per the three-tier lifecycle in CLAUDE.md when the work ships.
-->

# UI Consolidation Plan — compact, uniform, legible

**One header, one filter bar, one empty state, one way to degrade, one way to ask for a choice.**

| | |
|---|---|
| Status | ACTIVE PLAN — **approved 2026-09-22; decisions D1–D8 delegated to the implementer and taken as recommended (§6)**; **phase 1 SHIPPED**; **phase 2 PART-SHIPPED** — **10 of 11 rows**, 2026-09-22 (§4 marks each); only `UI-13` open |
| Raised by | Operator, 2026-09-22: *functionality is growing and interface complexity with it — consolidate the UI before implementing the Link Analysis backlog* |
| Method | Every nav target driven in the preview at 1440×900, dark scheme (the app's default), plus header flyouts and the New-job dialog. Findings are what was **seen**, cited to the pane; nothing here is inferred from code alone |
| Binding rules | `.claude/skills/angular-ui/SKILL.md` (design system, a11y, no hardcoded colours, option-picker, schema-form), `docs/GLOSSARY.md` (canonical words in every label) |
| Companion | `docs/superpower/link-analysis-backlog-plan.md` — its SPA items (`LA-05`, `LA-06`, `LA-09`) inherit the patterns decided here |

---

## 0. How to read this

- §1 what the app looks like today, measured — the baseline the plan changes.
- §2 the **findings**: eleven classes of inconsistency, each cited to the panes that show it.
- §3 the **design direction**: type scale, density, colour, and the shared components that carry them.
- §4 the **work items** `UI-01`..`UI-16`, with size, panes touched and how each is proven.
- §5 the **defect and reachability log** `UIB-01`..`UIB-18` — logged while walking, to fix later, not in this
  plan unless marked.
- §6 **decisions owed** to the operator before phase 1 starts.
- §7 phases and the verification gate.

---

## 1. Baseline, measured

| Token | Today | Where |
|---|---|---|
| Body font | Inter var, **14 px** | `tailwind.config.js:77` (`base: 0.875rem`) |
| Scale | xs 10 · sm 12 · md 13 · base 14 · lg 16 · xl 18 | `tailwind.config.js:73-79` |
| Page `<h1>` | **32 px / 700** | Home, Alerts, Runs, … (Tailwind `text-3xl font-bold` per pane) |
| Subtitle | 16 px, `--gamma-text-secondary` `#94a3b8` | per pane |
| Nav item | 13 px | gamma vertical navigation |
| Scheme | dark by default (`body.dark theme-default`), `--gamma-bg-default #0f172a`, primary `#4f46e5` | app-controlled, not `prefers-color-scheme` |
| Shell | gamma/Fuse classic layout, 64 px header, left nav, 56 px footer, document-level scroll | `layout/` |

The palette is sound and stays. Contrast of the secondary grey on the page and card backgrounds is above
AA. The problems are **structure and repetition**, not colour.

---

## 2. Findings — eleven classes of inconsistency

Each row names the panes where it was seen. A pane may appear under several rows.

| # | Class | Seen on | What it costs |
|---|---|---|---|
| F1 | **Three page-header shapes.** (A) 32 px title + subtitle + right-aligned actions (Alerts, Runs, Reconciliation, Requirements, Datasets, Expectations, Decision Rules, Components, Enrichment, Collectors, Catalog, Events, Processing Status, Audit, Autonomy, Learning, Assistant, Agent Chat, Menus, Spaces, Connections, Design). (B) icon + small title on one line, no subtitle (KPI & Reports). (C) compact inline title with the description as running text in a toolbar (Link Analysis, Geo Map, Pipelines). Within (A) the action row sometimes sits on a **second line** (Expectations, Datasets when it wraps) and refresh is sometimes an icon, sometimes a pill button (Audit log). | Every pane re-rolls its header; the eye has to re-find title, help and primary action on each screen |
| F2 | **Subtitles are paragraphs.** Two to three lines of prose under the title (Decision Rules 3 lines, Alerts 2, Spaces 2, Learning 2, Datasets 2, Menus 2, Design System 2). | Pushes content below the fold on every visit; explains the pane to a reader who already knows it |
| F3 | **Filter bars sprawl.** Events shows a row of 5 outlined fields + Search + Reset, a second row with Views / Export CSV, a third with the grid toolbar and a "Showing 100 — load more" line: **three toolbars** before the first row. Diagnoses puts a lone `Limit` input in the header. Alerts and Audit have the grid toolbar only. | No two list panes filter the same way |
| F4 | **Stacked sections make tall pages.** Components = five section cards each with its own empty state (Grammar, Schema, Mapping, Transform, Sink) → one screen of empty boxes. Autonomy = kill switch card + policy card + recent-actions grid. Maintenance = 20+ health tiles of varying width. Settings = a 12-row menu with a description under every row. | Scrolling to find the one thing that matters |
| F5 | **Empty states diverge.** The shared `<inspecto-empty-state>` (dashed card, icon, two lines) on most panes; raw ag-Grid "No data to display" on Enrichment and Collectors; an empty Chart.js axis 0–1.0 with a legend on Collectors and Overview; a `—` dash for Recent Runs on Home. | A raw grid overlay reads as broken; an empty chart reads as zero data |
| F6 | **Optional modules degrade three ways.** Approvals Inbox: one info `<inspecto-alert>` and nothing else (the reference). Learning: empty state **plus a red toast** "Failed to load feedback". Autonomy: an error-tinted alert **plus** a red toast. Home: the panel explains the missing jobs backend **and** a red toast "Failed to load recent runs". | A toast says something is broken when nothing is |
| F7 | **Grid columns truncate their own headers.** "Sev…", "Com…", "Collec…", "Conn…", "Quaran…", "Last ba…", "What's sch…" on Alerts, Events, Collectors, Processing Status, Jobs; the Actions column on Jobs clips its third icon. Incidents/Cases render a horizontal scrollbar on an empty grid. | Column meaning is lost exactly where the operator scans |
| F8 | **Two vocabularies for one choice.** 27 templates still use `<mat-select>` (Geo Map's 8-field row, Link Analysis query dock, Events filters, Assistant skill) while schema-driven forms use the property-row `<inspecto-option-picker>` (New job). | Two looks for the same interaction; the skill already names the picker as the rule |
| F9 | **Bulk actions as a row of disabled pills.** Incidents and Cases show 6–7 outlined buttons (Accept · Prioritize · Tag · Escalate · Resolve · Archive · Reopen), all disabled until a row is selected, above an empty grid. | A full row of grey buttons on first load |
| F10 | **Renamed concepts still on screen.** "Committed batches", "Batch duration percentiles", "Batch outcomes" (Overview); "Batch" column (Diagnoses); "Committed batches" tile (Processing Status); "how many batches per status" placeholder (Assistant). The glossary renamed Batch → **Consignment** and the vocabulary guard deliberately excludes bare `Batch`. Also nav "Jobs" opens a page titled "Scheduler"; nav "Overview" opens "Dashboard"; nav "Signal Ledger" is fine but its route is `/events`. | The UI teaches a word the docs retired |
| F11 | **Icon-only toolbars without labels.** Pipelines' header toolbar is 8 unlabelled icons (some disabled) before View / Edit / Topology; Link Analysis' header has 4 unlabelled icons at the far right. Tooltips exist but there is no visible affordance for a new user. | Discoverability; the skill requires `aria-label`, which is present, but the operator asked for *appealing*, which needs labels or grouping |

Two things that are **already right** and become the reference: the New-job dialog (property rows, one
"Optional settings (3)" disclosure, a type card, Cancel/Continue) and the Approvals Inbox degrade.

---

## 3. Design direction

### 3.1 Type scale — clearer by being smaller and steadier

| Role | Today | Proposed | Why |
|---|---|---|---|
| Page title `<h1>` | 32 / 700 | **22 px / 600**, tracking −0.01em | 32 px is a marketing size; at 22 the title, help and primary action share one 40 px row |
| Subtitle | 16 px, up to 3 lines | **13 px, one line, ellipsis**; full text in the `?` explain dialog | The subtitle orients, the dialog explains |
| Section heading `<h2>` | mixed 18–20 | **15 px / 600**, uppercase eyebrow variant 11 px / 600 / tracking 0.06em for dock headers (Query · Toolbox already do this) | One heading voice inside a pane |
| Body / grid cell | 14 / 12–13 | body 13 px; grid cell 13 px; grid header 12 px / 600 | Denser tables without going under 12 |
| Caption / hint | 12 | 12 px, secondary | unchanged |
| Numbers in stat tiles | ~20 | 20 px / 600 tabular-nums | unchanged size, add `font-variant-numeric: tabular-nums` |

Inter var stays. Nothing goes below 12 px except the eyebrow, which is uppercase and spaced.

### 3.2 Density and rhythm

- Page padding 24 px → **20 px**; card padding 20 → 16; vertical gap between header and content 24 → 16.
- Grid row height 40 → **34 px** in list panes (mini tier keeps 40 for touch).
- One primary (filled) action per page, at the right end of the header row. Everything else is an icon
  button with a tooltip, or lives in a `⋯` overflow menu once there are more than three.
- Refresh is **always** the icon button; "Auto" is a `mat-slide-toggle` inside the same group, never a
  checkbox with a label.

### 3.3 Colour

Palette unchanged. Two rules added: (1) a status tint appears only on `<inspecto-status-badge>` and
`<inspecto-alert>` (already enforced); (2) **an optional-module absence is `info`, never `error`** —
Autonomy's error-tinted alert becomes info.

### 3.4 Shared components that carry the direction

| Component | New / existing | What it owns |
|---|---|---|
| `<inspecto-page-header>` | **new** | title, one-line subtitle, `[terms]` → `<inspecto-ai-explain>`, `actions` slot (primary + icons + overflow), optional `tabs` slot (Catalog, Processing Status, Jobs), optional breadcrumb. Replaces the (A)/(B)/(C) shapes |
| `<inspecto-filter-bar>` | **new** | collapsed by default to a **Filter** icon + active-filter chips; opens as a popover with the fields; Reset; emits a `ConditionGroup` or a plain record. Events, Diagnoses, Alerts, Audit adopt it |
| `<inspecto-section-tabs>` | **new**, thin | `mat-tab-group` with a count badge per tab and one content slot; for panes that today stack sections (Components) |
| `<inspecto-stat-tile>` | **new**, thin | label · value · hint, tabular numerals, the empty value rendered as `—` with a tooltip; Home, Overview, Collectors, Processing Status, Learning, Maintenance |
| `<inspecto-empty-state>` | existing | becomes the **only** empty rendering: the data-table's no-rows overlay mounts it; `<inspecto-chart>` renders it instead of an empty axis when the series is empty |
| `<inspecto-alert>` | existing | the **only** degrade: optional module 503 → info alert, no toast (Approvals pattern) |
| `<inspecto-option-picker>` | existing | the **only** single-choice control outside table cells and grid toolbars |
| `<inspecto-bulk-actions>` | **new**, thin | selection-count chip + `⋯` menu of actions; replaces the pill rows on Incidents / Cases |

Every new component gets a `/design` gallery entry and an axe assertion in its spec, per the skill.

---

## 4. Work items

Sizes: S ≤ 1 day · M ≤ 3 days · L ≤ 1 week. Phase = order. "Proof" is what is observed in the preview, never a unit test alone.

### Phase 1 — the tokens and the shared pieces ✅ SHIPPED 2026-09-22

⇒ **As built.** `text-title` (22 px) added to the Tailwind scale; ag-Grid `rowHeight`/`headerHeight` 34 px
in `INSPECTO_DEFAULT_COL_DEF`'s theme params. Five components under `inspecto/components/`, each with a
spec (28 assertions, axe included) and a `/design` gallery entry, all five driven in the preview.
🔴 Two defects the work surfaced and fixed before any pane adopted them: a `MatTabGroup` whose
`[selectedIndex]` is bound to a derived getter **springs back** on click, and `ngModel` inside the new
filter `<form>` throws **NG01352** without `standalone: true`. ⚠ And a testing trap worth carrying:
a synthetic click cannot drive a Material tab in jsdom (first one swallowed, later ones alternate).

| Id | Item | Size | Touches | Proof |
|---|---|---|---|---|
| ~~**UI-01**~~ ✅ | Type scale + density tokens (§3.1, §3.2): Tailwind theme extension, `styles.scss` `h1/h2` defaults, ag-Grid row height and header font in `InspectoGridThemeService` | S | `tailwind.config.js`, `styles.scss`, `inspecto/grid/` | `getComputedStyle(h1).fontSize === '22px'` on Home; grid row 34 px on Alerts |
| ~~**UI-02**~~ ✅ | `<inspecto-page-header>` + gallery entry + spec (axe) | M | `inspecto/components/page-header.component.ts`, `/design` | renders title/subtitle/actions/tabs; subtitle ellipsis at one line |
| ~~**UI-03**~~ ✅ | `<inspecto-stat-tile>`, `<inspecto-section-tabs>`, `<inspecto-bulk-actions>` + gallery entries | M | `inspecto/components/` | gallery shows all three in both schemes |
| ~~**UI-04**~~ ✅ | `<inspecto-filter-bar>` over the existing `query-types.ts` tree + gallery entry | M | `inspecto/components/filter-bar.component.ts` | collapsed → chips → popover → emits; Reset clears chips |

### Phase 2 — adopt on every pane (mechanical, one commit per group)

⇒ **Shipped 2026-09-22:** `UI-05`–`UI-12`, `UI-14`, `UI-15` — 40 panes on the shared header (34 by
codemod, 6 by hand), the Signal Ledger filter bar, the degrade rule, Components' tabs, the Incidents and
Cases bulk actions, wrapping grid headers, empty charts that say why, and the retired entity word gone
from operator-facing text. **Open:** `UI-13` (the `mat-select` sweep), plus **13 panes** the codemod still
refuses — the four editors (`config`, `pipeline-editor`, `dashboard-editor`, `dataset-editor`,
`explore`), the three bounded editor panes below, and the remaining interpolated-title list panes
(`jobs`, `home`, `object-mail`, `object-detail`, `recon-board`, `reconciliation-detail`,
`connection-workbench`, `kpi-reports`).

🔴 **Three findings from doing the sweep, each of which would have bitten the remaining rows:**
1. **The editor panes cannot take a full-bleed header.** Link Analysis, Geo Map and Pipelines were
   converted, and in the preview Link Analysis overflowed its bounded IDE row (`scrollWidth` 1591 in an
   886 px viewport) with the title pushed off screen. All three were reverted; their compact chrome needs
   the header fitted by hand, not by codemod.
2. **A pane whose first `<h1>` sits inside a control-flow block breaks.** The share page's heading lives
   in an `@case`; the codemod cut across the block and the build caught it. Reverted, and the codemod now
   refuses that shape.
3. **The codemod's own import inserter landed inside multi-line `import` statements** in 5 files — a
   syntax error the build caught, not the typecheck. Any further sweep must insert after the last line
   that *completes* an import, never after the last line that *starts* one.

| Id | Item | Size | Panes | Proof |
|---|---|---|---|---|
| ~~**UI-05**~~ ✅ | Page header sweep — Operations group | M | Overview, Processing Status, Events, Audit, Diagnoses, Alerts, Incidents, Approvals, Autonomy, Learning, Cases, Tags | every pane: one `<h1>` at 22 px, one-line subtitle, primary action right, `?` opens the full text |
| ~~**UI-06**~~ ✅ | Page header sweep — Workbench + Catalog + Business | M | Pipelines, Runs, Jobs, Expectations, Decision Rules, Components, Enrichment, Collectors, Catalog, Datasets, Data Browser, KPI & Reports, Requirements, Reconciliation | same; Pipelines' 8-icon toolbar folds into header actions + `⋯` |
| ~~**UI-07**~~ ✅ *(minus the 3 editor panes)* | Page header sweep — Studio + Assistant + Settings + Maintenance | M | Queries, Widgets, Dashboards, Templates, Link Analysis, Geo Map, Menus, Assist, Agent Chat, Settings, Maintenance, Spaces, Connections, Design | Link Analysis / Geo Map keep their IDE layout under the shared header |
| ~~**UI-08**~~ ✅ *(Signal Ledger; Diagnoses/Alerts/Audit open)* | Filter-bar adoption | M | Events (3 toolbars → 1), Diagnoses (`Limit` leaves the header), Alerts, Audit | Events shows one toolbar row before the grid; active filters read as chips |
| ~~**UI-09**~~ ✅ | Empty-state unification: data-table no-rows overlay → `<inspecto-empty-state>`; `<inspecto-chart>` empty → empty state; stat tiles → `<inspecto-stat-tile>` | S | Enrichment, Collectors, Overview, Home, Processing Status, Learning | no raw "No data to display"; no empty axes |
| ~~**UI-10**~~ ✅ | Degrade unification: optional-module 503 → info alert only | S | Learning, Autonomy, Home (recent runs), Diagnoses | zero red toasts on a Personal-core backend across all four panes |
| ~~**UI-11**~~ ✅ *(Components; Autonomy/Settings open)* | Stacked sections → tabs: Components (5 sections → 5 tabs with counts); Autonomy (policy + actions as two tabs under the kill switch); Settings menu → icon + title rows, description shown only for the selected row | M | Components, Autonomy, Settings | Components fits one screen empty |
| ~~**UI-12**~~ ✅ | Bulk actions → `<inspecto-bulk-actions>` | S | Incidents, Cases | no pill row on load; count chip + menu appear on selection |
| **UI-13** | `mat-select` → `<inspecto-option-picker>` sweep (27 templates; table cells and grid toolbars stay dropdowns per the skill); Geo Map's 8-field row becomes a `<inspecto-schema-form>` with Latitude/Longitude required and the four optional columns under one disclosure | L | Geo Map, Link Analysis query dock, Events, Assistant, + 23 others | zero `<mat-select>` outside the two exempt categories; Geo Map query fits one row + one disclosure |
| ~~**UI-14**~~ ✅ | Grid header legibility: `minWidth` per column, `headerTooltip` on every `ColDef`, `[pinActions]` wherever an actions column exists, `suppressHorizontalScroll` on empty grids | S | all data-table hosts (shared default in `INSPECTO_DEFAULT_COL_DEF`) | no truncated header at 1440 px on Alerts, Events, Collectors, Jobs |
| ~~**UI-15**~~ ✅ | Vocabulary sweep in UI text: Batch → Consignment (Overview ×3, Diagnoses, Processing Status, Assistant placeholder); page titles match nav labels (Jobs, Overview) or the nav is changed — operator call D3 | S | 6 templates + `navigation-data.ts` | grep for `[Bb]atch` in `src/app/modules/**/*.html` returns only the grouping sense |

### Phase 3 — the shell

| Id | Item | Size | Proof |
|---|---|---|---|
| **UI-16** | Nav: auto-expand the group holding the active route on deep link; remove the unmounted `settings-drawer` component and its dead links (D2); delete the four orphaned `*.routes.ts` (D4). ~~Wildcard `**` route + Home's dead link~~ ✅ **SHIPPED 2026-09-22** | S | deep-link to `/overview` shows Business expanded; `/catalog-onboard` shows a not-found pane, not the splash |

---

## 5. Defect and reachability log

Logged while walking. **None is in scope of this plan unless its row says so.** On ship, open rows move to
`docs/BACKLOG.md`.

| Id | Where | What was seen | Class | Disposition |
|---|---|---|---|---|
| ~~UIB-01~~ ✅ | Home ▸ Quick actions ▸ *Onboard a Stream* | links to `/catalog-onboard`, which matches no route; the app stays on the **splash screen forever** (`NG04002` in console). The nav's own item uses `/catalog?onboard=stream` | dead link + no wildcard route | fix in UI-16 |
| ~~UIB-02~~ ✅ | Home | red toast "Failed to load recent runs" while the panel already explains the missing jobs backend | degrade (F6) | fix in UI-10 |
| ~~UIB-03~~ ✅ | Learning | red toast "Failed to load feedback" on a deployment without the intelligence module | degrade (F6) | fix in UI-10 |
| ~~UIB-04~~ ✅ | Autonomy | error-tinted alert **and** red toast "Autonomy policy is not available" | degrade (F6) | fix in UI-10 |
| UIB-05 | Backend, `inspecto-geolink*` launch configs | with `-Dspaces.root=..\spaces` from `inspecto-deploy/`, every demo pipeline fails to load: `schema_file` relative paths resolve against the **CWD** (`inspecto-deploy\spaces\demo\…`) instead of the space root, so Pipelines, Runs and Processing Status are empty | backend path resolution | **BACKLOG** — `SCHEMA-FILE-RESOLVES-AGAINST-CWD-1`; not a UI defect but it blanks half the UI in this launch mode |
| UIB-06 | Backend | `HEAD /` throws `IOException: stream closed` in `ApiContext.respondJson` on every preview probe (content length sent on a HEAD) | backend | BACKLOG, low |
| UIB-07 | `layout/common/settings-drawer` | component exists with its own settings link list (`/config`, `/notification-center`, `/spaces`, `/design`, …) but is **mounted nowhere**; its unique targets `/config` and `/notification-center` are reachable only through the Settings page drawers | dead component | remove in UI-16 (D2) |
| UIB-08 | `modules/admin/{icon-settings,map-settings,model-settings,transfer}/*.routes.ts` | four route files imported by nothing; the components mount only via `SettingsComponent`'s `NgComponentOutlet` | dead code | delete in UI-16 (D4) |
| UIB-09 | `modules/admin/jobs/job-runs.dialog.ts` | `JobRunsDialog` has **no opener** — only its own spec references it | dead dialog | BACKLOG — decide retain-as-test-vehicle or delete |
| UIB-10 | `/connections` | routed, no nav item, no flyout; the only in-app link is a `routerLink` inside `collector-detail.dialog.ts:57`; the same component is a Settings drawer section | reachability | accept: Settings ▸ Connections is the home; drop the standalone route or leave as deep link — D2 |
| UIB-11 | Overview ▸ Recent activity | raw WARN log lines rendered unwrapped, clipped at the card edge | presentation | fix in UI-05 (wrap + monospace + 3-line clamp) |
| UIB-12 | Data Browser | store list of 30+ items with no filter box and no scroll container; runs off the bottom of the page | presentation | fix in UI-06 (filter input + `max-h` scroll) |
| UIB-13 | Incidents, Cases | horizontal scrollbar rendered on an **empty** grid; grid does not use the full pane width | grid | fix in UI-14 |
| ~~UIB-14~~ ✅ | Jobs | Actions column clips its third icon at 1440 px | grid | fix in UI-14 |
| UIB-15 | Header | after closing the Notifications flyout with Esc, its tooltip "Notifications" stays on screen over the search bar | tooltip stuck | BACKLOG, low — reproduce and check `matTooltip` hide on blur |
| UIB-16 | Header ▸ lens switcher | clicking *Builder* rendered no visible menu in the preview screenshot one second later | **unverified** — may be animation timing; re-drive before filing | verify in UI-16 |
| UIB-17 | Collectors, Overview | Chart.js renders an empty 0–1.0 axis with a legend when the series is empty | presentation (F5) | fix in UI-09 |
| UIB-18 | Diagnoses, Overview, Processing Status, Assistant | "Batch" in labels after the Consignment rename | vocabulary (F10) | fix in UI-15 |

⇒ **Added 2026-09-22 while fixing UIB-14:** `UIB-19` — grid headers were clipping because ag-Grid
clips rather than wraps by default; `wrapHeaderText` + `autoHeaderHeight` in the shared column defaults
fixed all five panes at once, and `headerHeight` had to come OUT of the theme params for the header row
to grow. `UIB-20` — 🔴 **a Material tab bound to a derived `[selectedIndex]` springs back on click**:
`MatTabGroup` compares the clicked index against the value its input carries in the same change-detection
pass, so `selectedIndexChange` never fires. Found by a spec before any pane adopted the strip.
`UIB-21` — ⚠ a synthetic click cannot drive a Material tab in jsdom at all (first one swallowed, later
ones alternate by attempt order), so tab specs must drive the component and prove the click in the preview.

⇒ **Added 2026-09-22, second batch:** `UIB-22` — 🔴 **`/settings/<section>` rendered TWO `<h1>`s**,
one from the Settings shell and one from the section component mounted inside it through
`NgComponentOutlet`. Checked against the session's first commit: it **pre-dates** the header sweep, which
only made it uniform enough to see. Fixed by letting the shell drop to an eyebrow once a section is open.
⚠ The same dual-hosting means any future Settings section must expect its own header to BE the page's
heading. `UIB-23` — a series of explicit zeros is indistinguishable from "nothing has run yet", so
`<inspecto-chart>` cannot decide emptiness alone; the pane passes `[empty]`.

Nav group auto-expansion: deep links to `/overview`, `/kpi-reports`, `/diagnoses`, `/events` rendered with their group **collapsed** while `/alerts`, `/jobs`, `/studio/*` rendered expanded. Logged as part of UI-16 rather than as a defect row; the cause (lens filtering vs. group state) is a hypothesis until read.

---

## 6. Decisions — taken 2026-09-22

⇒ **Operator, 2026-09-22: "take the UI decisions best for the application".** Every row below was decided as its
Recommendation column says; the table is kept as the record of what was considered.

| Id | Decision | Recommendation |
|---|---|---|
| **D1** | Title size: 22 px / 600 as proposed, or keep 32 px and only fix structure? | 22. It is the single change that makes every page fit one more row |
| **D2** | Remove the unmounted `settings-drawer` and the standalone `/connections`, `/config`, `/notification-center`, `/spaces`, `/design` top-level routes, keeping them as Settings sections only? | Remove the component; **keep** the routes as redirects to `/settings/<section>` so bookmarks survive |
| **D3** | Page title = nav label? (`Jobs` vs "Scheduler", `Overview` vs "Dashboard") | Titles follow the nav; the nav follows the glossary |
| **D4** | Delete the four orphaned route files? | Yes — grep found zero references; delete in UI-16 |
| **D5** | Components: five tabs, or one table with a Type filter? | Tabs with counts — each type has a different card shape today |
| **D6** | Filter bar default: collapsed to chips (proposed) or always open? | Collapsed. Events is the pane that motivates the whole item |
| **D7** | Grid row height 34 px in list panes? | Yes; mini tier stays 40 |
| **D8** | Scope of UI-13: full `mat-select` sweep (27 files, L) now, or only the four panes named and the rest as a follow-on row? | Four panes now; the rest is a BACKLOG row so phase 2 stays mechanical |

---

## 7. Phases and gate

1. **Phase 1** (UI-01..04) → one commit per component; gallery + spec + axe each.
2. **Phase 2** (UI-05..15) → one commit per row; each row driven in the preview on every pane it names
   before commit.
3. **Phase 3** (UI-16) → one commit.

Gate per commit, from the skill's Definition of Done: `npm run lint:tokens` · `npm run build` ·
the three-tsconfig typecheck · the affected spec files · preview drive with `read_console_messages`
clean of errors · `/design` gallery updated when a pattern changed · the skill updated when a rule changed.
Full `npx ng test` at the end of each phase, not per commit.

**Not in scope:** the vendored gamma/Fuse shell (`src/@gamma/**`, `modules/auth/**`), backend routes,
Link Analysis functional work (its own plan), mobile layouts below 960 px.

---

## References

- Skill: `.claude/skills/angular-ui/SKILL.md` — design system, a11y gate, option-picker and schema-form rules
- Gallery: `/design` (`modules/admin/design-system/`)
- A11y audit: [`../ui/accessibility-audit.md`](../ui/accessibility-audit.md)
- Vocabulary: [`../GLOSSARY.md`](../GLOSSARY.md)
- Nav: `inspecto-ui/src/app/core/navigation/navigation-data.ts`; access catalog derives from it (`inspecto/access/access-catalog.ts`)
- Link Analysis SPA items: [`link-analysis-backlog-plan.md`](link-analysis-backlog-plan.md) `LA-05`, `LA-06`, `LA-09`
