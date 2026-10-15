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
| UC2 | Build a graph by hand | PASS: Dataset picker lists link-like Datasets first with a reason; picking `mule_ring_legs_dataset` filled FROM_ACCOUNT / TO_ACCOUNT; Run query drew 20 nodes / 20 links with the Masking badge. Filters not driven. |
| UC3 | Value Measures | PARTIAL: form opens; prefill defect found and fixed (F-8). A Measure was not run. |
| UC4 | Investigation end to end | PARTIAL: opened `mule-hub-case` (9 entities, 7 links, 1 excluded), Expand next degree. Seed, exclude, window, identity, snapshot, replay not driven. |
| UC5 | Four-eyes | PASS: analyst's expand held ("waiting for approval", request p1); manager approved it in the Oversight card; ran as step 9; Working Set 9 -> 15 entities. |
| UC6 | Evidence hand-over | PASS: *Save this analysis* froze a 112-node snapshot and showed its SHA-256; the Dossier of `mule-hub-case` reads "Log intact, all 9 recorded hashes agree", lists nine steps including the four-eyes approval, and masks an entity id inside a free-text reason. *Case from nodes* and the sealed bundle download not driven. Found F-15. |
| UC7 | Investigate a number | PASS: MSISDN card switched the profile to Telecom, asked the purpose, created the Investigation and expanded one degree: 403 entities, 403 links (30 days to 29 Sep 2026). Found F-14. |
| UC8 | Data trust | PARTIAL: strip reads "Data as of 4 Sep 2026 . 3 of 3 days . index v2". The seed reported "4 covered, missing 2026-09-05"; not reconciled. |
| UC9 | Read-only reviewer (`ra.analyst`) | PASS: opens and reads; 31 controls disabled, each with the missing capability named. |
| UC10 | Admin | PASS (read, plus Reveal): Reveal turned the hub's alias into its real id in one click, marked "reveal audited" (no reason is asked: a policy question, F-16). The embed Share card says embed links are off on this host; the mint itself was proven in the earlier LA-EMBED-VIEW-1 pass and not re-run.  Settings shows each limit with its in-force value; Entity Lists shows both lists and a verified Identity Fact chain (7 facts). Opening another user's Investigation link as admin gives a clear red "not available" message. Minting an embed link and Reveal not driven. |
| UC11 | Usability and axe | axe-core 0 violations (dark) on Link Analysis with a graph, the open Investigation, Entity Lists, Settings, Geo and Alerts. One run on the Investigation page right after a Reveal reported 3 (colour contrast on text read as black, an ag-grid header and a grid scroll region); two re-runs were clean, so it is recorded as not reproducible. Light mode, the Save dialog and the Value Measures form were not scanned. |

### Findings

| Id | Sev | Finding | Status |
|---|---|---|---|
| F-1 | P2 | After a graph draws, the Query panel collapses to its summary and the **Masking badge disappears**; `masked:<hash>` ids then have no on-screen reason. | FIXED (query-panel html + spec); confirmed live on the rebuilt bundle. |
| F-6 | P2 | A tool's refusal ("This pattern requires the hops to be in time order...") renders in the Analysis alert at the TOP of the Toolbox, far above the button at the bottom of a long panel, and was gone on the next read: the click looks like it did nothing. The suggested *Layering chain* also cannot run on the starter-card graph, which carries no time column. | Alert FIXED (scrolls into view; spec). The starter card still has no time column by design of its mapping; the messages now say where to add one: *Attribute columns* in the Query panel, then *Time column* under *Filter edges by time* (they used to point at a Query-panel field that does not exist). |
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
| F-14 | P2 | The Investigation tab keeps the previous tab's scroll position (the three tabs share one scroll container), so the *Investigate a number* card switches to it with the required **Purpose** field out of view; seen three times. | FIXED (resets to the top on a tab change; spec fails with the fix removed). Confirmed live only by the unit spec: the fix was built after the last live pass. |
| F-15 | P2 | *Save this analysis* is enabled for a user without *Incident management*; only after typing a title does it fail, saying "The analysis could not be saved" and hiding the server's reason (`missing capability 'canManageIncidents'`). *Attach to Case* next to it is disabled with its reason. | FIXED for the message (the dialog now shows the server's reason; spec). The button is now disabled, with the reason, for a user without Incident management (spec). |
| F-16 | P3 | Reveal is one click with no reason asked; each reveal is audited. Whether a reason should be required is a policy call. | OPEN, question |
| F-13 | P3 | The *Open by id* row of the Investigation tab shows "—" with a pencil and a disabled Open button before anything is typed. | OPEN, observation |

Not a defect, recorded so nobody repeats it: `/approvals` in the la-app bundle redirects to `/`; four-eyes decisions are made in the Oversight card of the Investigation.

| F-17 | P2 | **Case from graph nodes is unavailable on a masked Space**: masked pseudonyms are refused by the server as Case keys, so with `typed` masking the ring view offers no member, and the dialog blamed "a known source Dataset". The manual (section 10) tells the analyst to tick the hub and relays. | MESSAGE FIXED (names masking, points to Reveal rights or linking an Investigation to a Case; spec). The manual sentence needs a masking caveat: OPEN. |
| F-18 | P1 (a11y) | Four toolbar menus (*Search nodes*, *Filter edges by time*, *Save the current view*, *Display options*) put a form in Material's `role="menu"` panel, and *Filter node types* put checkboxes there: axe `aria-required-children`, critical, whenever one is open. Earlier audits did not open them. | FIXED: node types are `menuitemcheckbox` items; the four form menus are re-roled to a named `dialog` by `FormMenuDirective` (spec). Measured live: all 11 toolbar menus clean. |
| F-19 | P3 | *Verify this manifest* is disabled with no stated reason until *Build dossier* has produced a manifest. | OPEN |

**Driven in this round:** Case from graph nodes (F-17); Dossier *Build dossier* then *Verify this manifest* ("The manifest matches the store now", root `sha256:d892...`); the toolbar menus under axe. **Not downloaded:** *Download sealed bundle* writes a file to the machine and was left for the operator. **Still not driven:** the Filter predicate drawer (not found on the la-app list view), light mode (a theme switch is not available from the page here, so a class toggle gave invalid contrast figures, discarded), minting an embed link on a host with embeds on.
