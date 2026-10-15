# Link Analysis analyst coverage test (UI-only, impersonated analyst)

*Status: started 2026-10-15. Bundle: `inspecto-la-app` launch config (Enterprise demo auth, Space `la-showcase`, seeded with
`tools/seed-la-showcase.mjs`). Every step is done through the browser UI as a Demo User; no direct API calls except to
read back evidence. Results are in section 3; a use case marked NOT DRIVEN was not driven.*

## 1. Use cases

| Id | Persona | Goal | Surfaces covered |
|---|---|---|---|
| UC1 | `fm.analyst` | Find a mule ring from a saved view | Start cards, Open saved view, Suspicion score, Pattern match (Structuring), Explain node |
| UC2 | `fm.analyst` | Build a graph by hand from a Dataset | Query panel, column mapping, Filters, size limits, footer counts, legend chips |
| UC3 | `fm.analyst` | Quantify behaviour | Value Measures: Pass-through, Velocity, Cash-out concentration |
| UC4 | `fm.analyst` | Run an Investigation end to end | Create, seed, expand, exclude, annotate, window, Identity resolution, snapshot, replay and drift |
| UC5 | `fm.analyst` + `fm.manager` | Four-eyes expansion | A fan-out above the limit is held; the second approver releases it |
| UC6 | `fm.analyst` | Hand over evidence | Save this analysis, Case from nodes, Dossier, export |
| UC7 | `fm.analyst` | Investigate a number | MSISDN card, Expand next degree, Telecom profile |
| UC8 | `fm.analyst` | Know what the graph stands on | Data trust strip: freshness, coverage, Files, index state |
| UC9 | `ra.analyst` / `demo.manager` | Read-only user is refused honestly | Disabled or hidden controls, a clear refusal, no write |
| UC10 | `admin` | Operate | Settings, Entity Lists, Geo area, Share (embed link), Reveal |
| UC11 | any | Usability and accessibility sweep | Keyboard path, empty and error states, dark and light, narrow window |

## 2. Method

Sign in as a Demo User on the sign-in page, drive only visible controls, record per use case: PASS / DEFECT / USABILITY with
the on-screen evidence. Each finding gets an id `F-n`, a severity (P1 wrong or lost data, P2 blocks a task, P3 friction)
and, when fixed, the commit.

## 3. Results

Run 2026-10-15 on a bundle rebuilt from the working tree (`package.ps1 -Edition Enterprise -DemoAuth -Ui la-app`, 11m51s) after the first
bundle turned out to be from 2026-10-11 and lacked the Link expansions screen: **check a bundle's jar and `ui\index.html` dates before
testing from it.** Driven through the browser as Demo Users; the pane was 875-1430 px wide during the run.

| Id | Use case | Result |
|---|---|---|
| UC1 | Mule ring from the saved view | PARTIAL: 112 nodes / 224 links as in the manual; Suspicion score top-20. Layering chain refused for want of a time column (F-6). *Explain node*, cash-out not driven. |
| UC2 | Build a graph by hand | NOT DRIVEN |
| UC3 | Value Measures | PARTIAL: form opens; prefill defect found and fixed (F-8). A Measure was not run. |
| UC4 | Investigation end to end | PARTIAL: opened `mule-hub-case` (9 entities, 7 links, 1 excluded), Expand next degree. Seed, exclude, window, identity, snapshot, replay not driven. |
| UC5 | Four-eyes | PASS: analyst's expand held ("waiting for approval", request p1); manager approved it in the Oversight card; ran as step 9; Working Set 9 -> 15 entities. |
| UC6 | Evidence hand-over | NOT DRIVEN |
| UC7 | Investigate a number | NOT DRIVEN |
| UC8 | Data trust | PARTIAL: strip reads "Data as of 4 Sep 2026 . 3 of 3 days . index v2". The seed reported "4 covered, missing 2026-09-05"; not reconciled. |
| UC9 | Read-only reviewer (`ra.analyst`) | PASS: opens and reads; 31 controls disabled, each with the missing capability named. |
| UC10 | Admin | NOT DRIVEN |
| UC11 | Usability sweep | PARTIAL, findings below. No axe pass this run. |

### Findings

| Id | Sev | Finding | Status |
|---|---|---|---|
| F-1 | P2 | After a graph draws, the Query panel collapses to its summary and the **Masking badge disappears**; `masked:<hash>` ids then have no on-screen reason. | FIXED (query-panel html + spec); confirmed live on the rebuilt bundle. |
| F-6 | P2 | A tool's refusal ("This pattern requires the hops to be in time order...") renders in the Analysis alert at the TOP of the Toolbox, far above the button at the bottom of a long panel, and was gone on the next read: the click looks like it did nothing. The suggested *Layering chain* also cannot run on the starter-card graph, which carries no time column. | Alert half FIXED (scrolls into view; spec). Starter card carrying a time column: OPEN. |
| F-7 | P3 | Oversight (Approve / Deny) sits at the very bottom of the Investigation panel, far from *Expand next degree* where "waiting for approval" is shown. | OPEN |
| F-8 | P2 | **Value Measures opened with every field empty** although the Investigation knows its Dataset, payer, payee, link kind and time column: the toolbox never passed the Investigation header to the component, which already supports seeding. | FIXED (toolbox `investigationHeader` input; confirmed live: Dataset, PAYER_ACCOUNT, PAYEE_ACCOUNT, CHANNEL, BOOKED_AT prefilled). A spec of the toolbox wiring itself was not added (the child needs heavy providers). |
| F-9 | P3 | The Oversight card showed a raw nanosecond instant (`2026-10-15T01:36:58.322167800Z`) and "budget above —" for an unset limit. | FIXED (local time; names only the limits that are set; spec). |
| F-10 | P3 | Expand status line repeated: "Expand one hop from every entity: 6 new entities, 7 links added." appears twice under the button. | OPEN |
| F-11 | P3 | On the Investigation canvas the `masked:<16 hex>` labels are drawn large and overlap each other and the links. Shortening the label while keeping the full alias in a tooltip would help; not done, as aliases are also used to pick and search. | OPEN |
| F-12 | P3 | Investigation list shows the title rewritten with an alias ("Mule hub masked:6065..."): a title containing an entity id is masked. Probably intended; unconfirmed. | OPEN, question |
| F-2 | P3 | *Risk Score model* field under Suspicion score is an empty box with no placeholder. | OPEN |
| F-3 | P3 | Stacked layout (< 960 px): a Toolbox rail icon opens its panel below the fold without scrolling it into view. | OPEN, seen once |
| F-4 | P3 | Sign-in (studio shell, 800 px): Demo User name overlaps its title line. | OPEN, screenshot only; not re-seen in the la-app bundle |
| F-5 | P3 | In the studio shell the analyst lands on a Business Lens pointing at `kpi-reports`. | OPEN, observation (the la-app bundle is the product shape) |
| F-13 | P3 | The *Open by id* row of the Investigation tab shows "—" with a pencil and a disabled Open button before anything is typed. | OPEN, observation |

Not a defect, recorded so nobody repeats it: `/approvals` in the la-app bundle redirects to `/`; four-eyes decisions are made in the Oversight card of the Investigation.
