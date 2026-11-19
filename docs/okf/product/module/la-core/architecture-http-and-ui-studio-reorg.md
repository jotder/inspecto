---
type: Concept
title: Link Analysis — HTTP surface and Investigation UI — Studio UI reorganization
description: Part 4 of 4 of Link Analysis — HTTP surface and Investigation UI: Studio UI reorganization (LA-STUDIO-UI-REORG-1, as built 2026-10-28)
tags: [module, la-core, link-analysis, architecture, http, investigation, spa]
timestamp: 2026-11-15T00:00:00Z
---

# Link Analysis — HTTP surface and Investigation UI — Studio UI reorganization

Part 4 of 4 of [Link Analysis — HTTP surface and Investigation UI](architecture-http-and-ui.md) · previous: [Grow, Held and Exclude on the graph](architecture-http-and-ui-grow-exclude.md).

## 6.14 Studio UI reorganization (LA-STUDIO-UI-REORG-1, as built 2026-10-28)

The Studio was reorganized without removing a function; the layout is described in
[user-guide-la-core.md](user-guide-la-core-reference-notes.md#studio-layout-internals-la-studio-ui-reorg-1-phases-1-3-as-built-2026-10-27) *Studio layout*, and the plan with its "nothing removed" map
is archived as [`la-studio-ui-reorg-plan`](../../../../archived-documents/plans-archive/la-studio-ui-reorg-plan.md) §6.

- **Decisions (operator interview 2026-10-27, UIR-D1…D16):**
  - The Investigation tab split into the Act panel, a bottom dock and one ⚙ Settings dialog. The dialog also holds the old left Query panel, Entity Types, display rules, Entity Lists, Identities and Add / Upload data.
  - A second icon toolbar carries Canvas modes, selection and graph edits.
  - Plain drag pans, Shift+drag lassos, and there is no box select.
  - One Expand dialog for N degrees, with supernodes held.
  - Collapse is visual only, everywhere.
  - Four clean-up tools.
  - Layouts and overlays over the cap are disabled with a reason.
  - One Open dialog and a File menu.
  - One algorithm catalogue with a Browser / Server toggle.
  - The Domain profile picker is removed.
  - Dashboard Widgets and the embed viewer get a read-only toolset.
  - Editions lock single actions and hide whole areas.
- **Visual polish (operator, 2026-10-28):**
  - The title and its explain button moved back up from the status bar; the page-info line is gone.
  - The tool rail and the canvas toolbar share the header row (`la-toolbars`); File ▾ is the rail's first icon.
  - Every header button is icon-only, `aria-label` kept, name as `matTooltip`. A component style shrinks icon buttons to 30 px, with `!important` because the theme pins `.mat-mdc-icon-button` to 40 px with `!important`.
  - The rail does not shrink (it overlapped the canvas toolbar when it could), and the row container wraps as a last resort instead of clipping. The canvas toolbar is still not `overflow-hidden` (LA-REORG-CLEANUP-PANEL-CLIPPED-1). Measured: one row at 1,440 px with all 112 nodes selected and at 1,100 px folded.
  - The canvas toolbar folds by the header row's OWN width (`CANVAS_TOOLS_FOLD_PX` = 1,350, a `ResizeObserver` on `la-header` → `canvasToolsFolded`), not a viewport media query, so the console's side nav counts. Measured: the console at 1,440 px with its nav open (header 1,129 px) is one row with 921 selected; at 1,100 px with the nav open (header ~790 px) the rail alone is 580 px and the canvas toolbar wraps under it, unclipped.
  - Expand uses `mat_outline:open_with` and Clean up `mat_outline:cleaning_services`, from the registered Material sets.
- **Calls UIR-A1…A3, confirmed by the operator 2026-10-28** (proposed by the assistant, built as written):
  - A1: one ⚙ dialog that also holds *This Investigation*.
  - A2: Value Measures appears only in the catalogue.
  - A3: in an Investigation, clean-up records `hide` + `annotate`, one grouped Undo (`GrowExcludeStore.cleanUp`, `recordCleanup`). `keep` is not "keep only".
- **Industry pack (operator, 2026-10-28):**
  - A saved view never applies its own pack; the Space's pack always applies.
  - `loadView` clears `boundProfile`.
  - The view's stored pack is only shown in the Open preview and saved back unchanged.
  - Only an Investigation Template's `pack` binds one.
- **Read-only toolset:** `link-view-widget-tools.ts` (`widgetGraph`, `runWidgetAnalysis`) is shared by the dashboard Widget (`LinkViewWidgetComponent`) and the embed page (`projects/link-analysis/src/link-analysis/embed-view.page.ts`, drawn on `GraphViewComponent`; served at `/embed/inv/:token` by both the console and `la-app` since 2026-10-28, `LA-CONSOLE-EMBED-VIEWER-1`). Everything runs in the browser and sends no request.
  - Export appears only with the Widget's `allowExport`.
  - The embed viewer never offers Export, because an embed grant has no export flag.
  - Minting an embed needs `canShareInvestigation`, the lead role and `-Dla.embed.enabled=true` (503 otherwise).
- **Gotchas found by the drives:**
  - The second toolbar must not be `overflow-hidden`: it clipped the Clean up panel at every width, so every Clean up tool looked dead (`LA-REORG-CLEANUP-PANEL-CLIPPED-1`, fixed 2026-10-28). The toolbar fits at 1,300 px and folds into overflow menus below it, so nothing needs clipping.
  - The ⚙ Settings Esc handler ignores an Esc that another overlay already handled.
  - A case view sets `loading` before its awaits, and an unknown graph source clears it.
- **Live drive 2026-10-28** (a Demo Auth bundle on la-showcase; each Demo User was signed in through the sign-in picker in the browser):
  - **Embed minting:** `analyst` and `admin` get 200; `manager` and `reviewer` get 403; `outsider`, a non-member, gets 404 on the embed list.
  - **Read-only toolsets:** the embed viewer and the `la_showcase_overview` dashboard Widget (opened as `reviewer`) run Centrality, Communities and Cycles with no server request.
  - **`manager` and `admin`:** open `mule-hub-case` with the full File menu and ⚙ → Investigation settings. As `admin`, Share and embed offers *Create link*.
  - **`outsider`:** gets "This Investigation is not available…", the same answer whether or not the Investigation exists.
  - **`reviewer` (on Standard):** sees a "Read-only" banner. *Save this analysis* and *Delete* are off, with the reason. Expand…, Expand next degree and Expand the whole Working Set are off.
  - **Esc in ⚙ Settings,** after a real Graph model edit: the first Esc asks to discard the change; the second Esc closes that confirm without reopening it, and Settings stays open.
- **Edition and add-on locks, checked live the same day (UIR-D14).** The demo sign-in exists only on Advanced and Preview builds, and Basic has no sign-in at all. So Standard was run as the demo bundle without `inspecto-policy`, and Basic as a real `-Edition Basic` bundle.
  - **Advanced with `-Daddons.laInvestigations=false`:**
    - `/bootstrap` reports `laLevel: explorer`.
    - ⚙ has no *This Investigation* group.
    - File ▸ Close and Delete are off.
    - *Run on the server* and *Start an Investigation* are locked (`aria-disabled`) with "Needs the Investigations add-on…".
  - **Standard:**
    - `/bootstrap` reports `edition: standard`, and the Investigations add-on is present.
    - Geo, Entities and Alerts are in the navigation.
  - **Basic:**
    - `/bootstrap` reports `edition: basic` and `laLevel: explorer`, with `geoMap`, `entityPages` and `laWidgets` false and each with its reason.
    - The graph draws without sign-in.
    - Geo, Entities and Alerts are hidden from the navigation.
    - *Run on the server* is locked with "Investigations are in Standard and above…".
  - **Real `-Edition Basic` build with the console UI:**
    - The Widget picker's *Investigation views* offers only Reconciliation; there is no Link analysis or Geo map type.
    - The saved `la_showcase_overview` Widget still draws. That is by decision: the offer is cosmetic (D-LAE2).
    - Embed and Investigation routes answer 503 with the reason.
  - **Real `-Edition Standard` build with WSO2 sign-in:** `/bootstrap` reports `standard` / `investigations` / `oidc`.
  - **Every user was signed in on that real Standard build.** The browser started SSO and kept its own state, PKCE verifier and nonce.
    - The browser does not trust the local WSO2 self-signed certificate. So only the IdP login form was completed by a localhost script, using the synthetic users from `scripts/wso2-preview-la-setup.mjs`.
    - The browser then opened the returned callback, and the app finished its own exchange.
    - The la-showcase seed ran with real WSO2 tokens.
  - **Results per user:**
    - **`analyst`:** the full File menu, Investigation settings with *Create link*, and *Expand…* available.
    - **`manager`:** opens the Investigation; Share and embed shows no *Create link*.
    - **`admin`:** *Create link* is offered.
    - **`outsider`:** gets "This Investigation is not available…".
    - **`reviewer`:** may change Investigations here. In WSO2 it is also in the built-in `operations` group, which grants `canManageIncidents`. The demo roster makes it a reviewer only, so this is the identity setup, not a defect. Operator 2026-10-28: `reviewer` is a read-only reviewer on WSO2 too; `scripts/wso2-preview-la-setup.mjs` now lists it in `la-viewer` only and removes a user from any of its groups it is no longer listed in.
- **Embed Export:** stays unbuilt (operator 2026-10-28, "not now").
- **Full gate 2026-10-28 (`f0e8a50eb`):**
  - The reactor ran 56 of 56 modules: 11,280 tests, 0 failures, verdict guard PASS. The UI suite passed 6,779 tests.
  - Six stale tests that peer commits left on master were fixed first, none in the Link Analysis UI: two Job tests outran by the new retry, a new scoring route, a DELETE-only Investigation path, and two sign-in specs.
- **Deliberately not built:** Export in the embed viewer (it needs a grant flag first).
