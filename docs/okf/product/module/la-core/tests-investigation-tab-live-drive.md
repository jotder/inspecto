# Investigation tab - live re-measure of the undriven workflows (2026-11-02)

*The click / depth targets of the Investigation tab redesign (`LA-INVESTIGATE-UX-1`, archived plan
[`2026-10-31-la-investigation-tab-redesign-plan.md`](../../../../archived-documents/plans-archive/2026-10-31-la-investigation-tab-redesign-plan.md)
§2) for the workflows its first live drive (§7.1, 2026-11-01) did not reach. Clicks are pointer presses (typing a value
counts as one); depth is the number of popups or dialogs stacked at the deepest point. Re-run this after any change to
the Investigation tab's start form, Expand filter, Clean up or canvas toolbar.*

## 1. Set-up

| Item | Value |
|---|---|
| Bundle | `package.ps1 -Edition Preview -DemoAuth -Ui la-app -NoBakeDemo` *(removed 2026-11-10 by `PRODUCT-ASSEMBLY-1` PA-2; today `package.ps1 -Edition Preview -Product la -Distribution demo -NoBakeDemo`)*, built from `origin/master` `dc1300f1b` in a sibling worktree |
| Server | the staged `inspecto-demo\bin\serve-demo.bat` (root `serve-demo.bat` before BUNDLE-LAYOUT-1) on `127.0.0.1:8092`, started in a terminal and stopped with Ctrl+C (graceful) |
| Space | a COPY of the demo's `la-showcase`; the shared demo at `inspecto-preview\inspecto-la` was not touched |
| User | Demo User `admin`, built-in browser pane (800 × 600 shown, 1024 px CSS width) |
| Investigation | ONE throwaway on `telecom_links_dataset` (time column `last_seen`), suspect MSISDN 99979100001, 10 recorded steps, deleted with *⋯ ▸ Delete…* (its log then answers 404; the seeded *mule-hub-case* untouched). A first attempt on `mule_transfers_dataset` (2 steps) was deleted through the API once it showed that Dataset has one Entity Type and no usable time column |
| Evidence | every result read from `GET …/inv/investigations/{id}/log` and the counts line, not only the screen |

No shipped la-showcase Dataset binds two Entity Types: `telecom_links_dataset` is MSISDN → MSISDN and
`mule_transfers_dataset` is Account → Account. P3 could not be driven there (see §2).

## 2. Results

| # | Workflow | Target: clicks · depth | Measured | Result | Evidence |
|---|---|---|---|---|---|
| W1 | Seed from one entity, Dataset not yet picked | 4 · 1 | **5 · 1** (tab, Dataset popup, pick, type the value, *Start*) | FAIL by one (= baseline) | `seed` + `expand` steps 1–2. A Time column of type DATE was offered and broke Start: `LA-INVESTIGATE-TIMECOL-DATE-1` |
| W1 | *Investigate this* on a node of an explored graph | 2 · 1 | 3 · 1 (right-click, *Investigate this*, *Start*), then refused | **FAIL** | the form is filled with the masked label `masked:c2e9…`; *Start* says it cannot be investigated: `LA-INVESTIGATE-THIS-MASKED-1` |
| W3 | Expand with the *30 days* chip | +1 · 0 | +2 · 0 the first time (open the Time group, *30 days*), **+1 · 0** after (the group stays open, `la.fold.expand.time`) | PASS | step 3 `expand`, window *30 days up to the latest event*; the group summary reads *Last 30 days* |
| W4 | Cap fan-out at 50 per entity | 2 + typing · 0 | 3 + typing · 0 the first time (open *Fan-out and busy*, pen, 50, *Expand*), 2 + typing after | PASS | step 4 `expand` *at most 50 links per entity, strongest first*; 895 → 1,038 entities |
| P3 | Clean up ▸ hide an Entity Type | 2 · 0 | — | NOT DRIVEN | no la-showcase Dataset binds two Entity Types (above) |
| P4 | Clean up ▸ hide everything outside a time window | 2 · 0 | **3 + typing · 0** (From pen, type, *Find what is outside the window*, *Hide 79 with no link event in the window*) | PASS in function, FAIL on count | steps 5–6: `hide` 79 + `annotate` *Clean up: no link event in [2026-09-20T00:00:00Z, the end)*; counts *79 hidden*. No window chip; the fields' example instants are placeholders: `LA-INVESTIGATE-CLICKS-2` (1) |
| P5 | Two nodes, *Keep paths between these 2* | 3 · 0 | **3 · 0** (click A, Shift+click B, *Keep paths between the 2 selected (hide 948)*) | PASS | steps 7–8: `hide` 948 + `annotate` *Clean up: kept the paths between …*; 11 entities left on screen |
| K1 | Lasso a subgraph, *Keep* on the selection bar | 2 · 0 | lasso not drivable here; Keep = **K** key (1 · 0) | PARTIAL | step 9 `keep` 2, counts *2 kept*. The pane's drag is a straight line (encloses nothing) and the canvas ignores synthetic pointer paths, so the lasso needs a person; at 1024 px the selection bar's *Keep* is folded away and not in ⋯: `LA-INVESTIGATE-CLICKS-2` (2) |
| W5 | Node menu ▸ *Expand, this once ▸ Incoming only* (re-check) | 2 · 1 | **3 · 2** (right-click, *Expand, this once*, *Incoming only*) | PASS (one press and one level over, as on 2026-11-01) | step 10 `expand` `direction in` |

**Seen, not filed:** the one-off *Incoming only* and the 30-day chip both carried into later expands the filter set
before them (the chip's hint says *This Expand only* while the chip stays selected; the window equalled the
Investigation's own here, so the step log cannot tell); the header says *this Investigation sets no time window* on an
Investigation whose time column is `last_seen`.

## 3. Re-drive after the fixes (2026-11-02)

`LA-INVESTIGATE-TIMECOL-DATE-1`, `-THIS-MASKED-1` and `-CLICKS-2` fixed (as built in
[`architecture.md`](architecture.md) §6.9). Same set-up as §1: `package.ps1 -Edition Preview -DemoAuth -Ui la-app -NoBakeDemo` *(removed 2026-11-10 by `PRODUCT-ASSEMBLY-1` PA-2; today `package.ps1 -Edition Preview -Product la -Distribution demo -NoBakeDemo`)*
built from the fix worktree, `serve-demo.bat` on `127.0.0.1:8092`, a COPY of `la-showcase`, Demo User `admin`, 1024 px.

| Defect | Workflow | Result | Evidence |
|---|---|---|---|
| TIMECOL-DATE | W1 start on `mule_transfers_dataset`, Time column popup | **PASS** | the popup lists *None* and **BOOKED_AT · TIMESTAMP** only (`BOOKED_DATE` and the text columns are gone; `BOOKED_AT`, not declared on the Dataset, is now offered). Start from MULE-HUB-01 with `BOOKED_AT`: `POST …/inv/expand/preview` 200, *Drew 1 entity and 14 linked subscribers*. The first build showed only *None*: `/db/table` sends the coarse `date` for both columns, so the stored type is now read with `typeof()` |
| THIS-MASKED | right-click a masked node ▸ *Investigate this* | **NOT DRIVEN live** | the pane's synthetic right-click did not reach a G6 node on `telecom_fraud_network` (Find centred the hub; no node menu opened). Covered by the canvas spec: a revealer gets the real key through `POST /inv/deep-links/resolve {reveal: true}`; anyone else gets no request, a blank value and the reason. A person should re-check it once |
| CLICKS-2 (1) | Clean up ▸ Outside a time window on a saved throwaway (time column `BOOKED_AT`) | **PASS** | the fields read *Blank: from the start* / *Blank: to the end*; *30 days* sets From = 30 days before the latest event (`2026-08-05T00:28:00.000Z`), To blank, the chip pressed, and reads at once: *Hide 0 with no link event in the window* (every one of the 15 entities had an event in the window, so nothing to hide; *7 days* the same). Chip + Hide = 2 presses where there is something to hide |
| CLICKS-2 (2) | 1024 px, ⋯ ▸ Select all, ⋯ ▸ *Keep the selected (K)* | **PASS** | ⋯ shown (canvas tools folded); the item is enabled with a selection; the counts read *15 kept* |
| CLICKS-2 (3) | W1 with the Dataset not yet picked | unchanged, 5 · 1 | the baseline; the Dataset pick is the extra press. Accepted, not changed |

The throwaway `inv-5a025dc5-…` was deleted with *Investigation actions ▸ Delete* (its folder is gone; `mule-hub-case` and
`delivery-gap-case` untouched); the server was stopped with Ctrl+C (graceful: *ControlApi stopped*) and the viewport reset.

## 4. W5: is flattening worth it?

Not on its own. The one-off sits one submenu deep because the node menu's top level is already ten items; lifting
*Incoming only* to the top would make the menu longer for every right-click to save one press on a rare action. The
common case already costs 1 · 0 (double-click with the tab's filter) or 2 · 1 (*Expand*), and an analyst who wants
incoming-only repeatedly sets the Direction chip once and keeps double-clicking. If it is ever done, the cheaper way is
a keyboard path (e.g. Shift+G for the opposite direction) rather than another top-level item.
