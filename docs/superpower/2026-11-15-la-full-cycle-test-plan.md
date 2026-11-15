<!--
  ACTIVE PLAN — docs/superpower/
  Created 2026-11-15 from a hands-on, full-cycle drive of Inspecto LA (Preview x la x demo, built from 11aa321c5).
  Board row: LA-FULL-CYCLE-TEST-1 (docs/BACKLOG.md §3.12). Archive when its phases ship or are split into their own rows.
  Re-checked 2026-11-15 against origin/master c8d94b4bd by reading the code (§3.6); NOT driven again.
-->

# Inspecto LA full-cycle user test — findings and fix plan (2026-11-15)

> **Status: in build since 2026-11-15.** The five decisions of §4.1 were taken that day under the operator's
> delegation ("take decisions with justification"), each recorded with its reason in §4.3, and the phases run as
> parallel lanes. The findings were re-checked against `origin/master` `c8d94b4bd` by reading the code (§3.6): 27
> commits landed after the tested build, four related board rows shipped, and four of the proposed fixes turned out
> to reverse recorded decisions.

## 1. What was tested, and how

- **Build:** `package.ps1 -Edition Preview -Product la -Distribution demo -NoBakeDemo` from `origin/master` `11aa321c5`,
  run on `127.0.0.1:8111` with Demo Users. Spaces: `la-showcase` and `la-claims` (scaffolded, then seeded with
  `tools/seed-la-showcase.mjs --apply` and `tools/seed-la-claims.mjs --apply`), plus one Space created in the test.
- **Method:** a headless Chrome driven step by step (click, type, read the page text, screenshot at 1440 × 900), acting as
  an end user with only the screens and the [user guide](../okf/product/module/la-core/user-guide.md).
- **The full cycle (Demo Admin):** Create a Space *Bank transfers* (Generic) → Add data `transfers.csv` (461 invented
  rows: a mule chain, one busy merchant, four dirty rows) → say what the columns hold → Add details `accounts.csv`
  (138 rows) → Start a graph from `MULE-HUB-01` → Expand → quick questions → Save the Investigation → step log →
  Save snapshot → File menu.
- **Other personas (showcase Space):** FM Analyst (lead: open the seeded case, Reveal, Dossier), FM Manager (approve the
  pending four-eyes expand), RA Analyst (reviewer, read-only), Demo Manager (not a member), SIU Analyst (Claims saved
  view, Whole Dataset tab), and a sweep of Alerts, Entity Lists, Entities, Geo and *What can I do next?*.

**Not tested:** the Basic edition (last tested 2026-11-10, see `2026-11-10-la-basic-ui-test-report.md`), a real identity
provider, phone width, Geo with data, Drafts rebase/promote, embed links, the Linux bundle, large data. No automated
test was run; every finding below was seen once on this build and is not yet reproduced by a test.

## 2. What works well

The end-to-end path does work: a new Space, two files, a graph, a recorded Investigation and a sealed Dossier in about
fifteen minutes. Column detection explains every choice. `' acc-1003 '` was normalised to `ACC-1003`. Expand previews say
what a step adds before it adds it. The four-eyes hold, the approval by a second person, the read-only reviewer and the
hidden-from-outsider Investigation all behaved as the guide says. The Dossier's hash check reported *Log intact*.

## 3. Findings

Severity: **A** blocks or misleads a first-time user · **B** costs real time or trust · **C** polish.
"Known" names an open board row that already covers it.

### 3.1 First run: Space, Add data, mapping

| # | Sev | Finding |
|---|---|---|
| 1 | A | **Add data is off in a Space just created through *Use my own data*.** The Getting started **Add data** button leads to *"Adding data is not available here … its data comes from Pipelines"*. The Space has no Pipeline; the reason is wrong and the admin must find **Settings → Add data**. |
| 2 | A | **The creating admin cannot save a view or change Link Analysis settings.** In the new Space, File ▸ Save view / Save view as read *Needs Workbench author*, and Settings → Link Analysis and Analysis limits are *Read-only* ("needs the Workbench authoring capability"). The seeded `admin` role lacks that capability, so quick-start step 8 and the Space guide's *First graph* step cannot be completed. |
| 3 | A | **One bad value hides the time column.** `booked_at` with 460 ISO timestamps and one `not-a-date` is typed Text with no *A guess* mark (while `amount` with one `abc` does get one), and *When* is "None: no column holds dates or times". |
| 4 | B | **Fixing it takes three edits per column and the screen argues back.** Changing Type to *Date and time* does not refresh the reason ("the values are text") or re-propose the role; the *When* picker offers only *None* until the column's Role is also set to Time; after picking, the reason still reads "no column holds dates or times". Same for *Weighed by* and `amount`. |
| 5 | B | **The import result does not account for dirty rows.** "461 rows were read; 461 were kept" next to "460 links": the row with no From is never explained, and the forced-type rows (`not-a-date`, `abc`) are not reported. The guide promises "rows set aside and why" and "each Expectation on the data"; neither appears. Arrivals: *Left out* and *Covers* are blank although a Time column is set, and the grid scrolls sideways at 1440 px. |
| 6 | B | **Sixteen *Confirm* clicks for the details file** (file type, separator, quote, every column type…), with no *Confirm all*. The events file needed two. |
| 7 | B | **After the details import there is no *Explore in Link Analysis* button**, only *Add another file*. |
| 8 | B | **Only three kinds of data** in Create a Space (Mobile Money, Telecom, Generic) while twenty industry templates ship; a bank or insurance user has no match. |
| 9 | C | Create a Space: **Name** is a pen-field showing "—", not a visible text box. The guide says the dialog lands on Add data; it lands on Getting started. |
| 10 | C | Grammar and jargon: "1 are not", "has 1 distinct values", "137 appear in transfers; 1 do not"; "the name 'phone' says msisdn", "holds msisdn". |

### 3.2 Masking and reading the graph

| # | Sev | Finding |
|---|---|---|
| 11 | A | **Everything is an alias, even to the admin who uploaded the file.** A new Space masks *Account* by default (typed masking). Node ids, the node dialog, quick-question answers, the step log and the Dossier all read `masked:ff4bc2bd…`. The quick start ("type `MULE` and pick from the list") fails: the form says *type the whole value. Nothing is suggested.* |
| 12 | A | **Reveal is buried and one-at-a-time.** *Show values (this is logged)* on the start form reveals only the look-up. The real Reveal is toolbox ▸ Investigation settings… ▸ Oversight and approvals ▸ Masked entities: one click per entity (nine for a nine-node graph), nothing on the canvas or the node dialog points to it, and the admin role already lists *See unmasked ids*. |
| 13 | B | **The showcase story is unreadable under the default.** The seeded Investigation is named "Mule hub masked:6021…"; its reasons and annotations carry aliases. A demo opens on a graph nobody can read. |
| 14 | B | **Masking is inconsistent.** `holder_name` (a personal name) and city show raw beside a masked account id; the same entity has two different aliases (`masked:ff4bc2bd…` in the node dialog, `masked:a2146be0…` in the step log). |
| 15 | B | **No node labels on a 15-node graph**, and no hint that labels are off. (`LA-OVERLAYS-HIDE-GRAPH-1` shipped 2026-11-15 without changing this, so no open row covers it now.) |

### 3.3 Canvas and layout

| # | Sev | Finding |
|---|---|---|
| 16 | B | **New-Space empty canvas offers only "Start from the Investigation tab →".** The bounded Start area with tabs (*Start a graph, Preset queries, Build a question, Explore the Dataset*) that the guide describes did not show on Preview with Investigations. |
| 17 | B | **Layout after a step.** Expanded nodes land inside the star on top of links; after Save the graph is redrawn with overlapping nodes, one node under the *Graph counts* overlay, and the start entity loses its highlight. |
| 18 | B | **Fit to screen leaves most of the canvas empty** on `claims_network` (426 nodes): two tiny unlinked groups are thrown to the corners, so the ring is a small blob; with the dock open the graph sits in the top quarter. (Related `LA-OVERLAYS-HIDE-GRAPH-1` and `LA-RADIAL-LAYOUT-SQUASH-1` both shipped 2026-11-15, after the tested build: drive again before any work.) |
| 19 | C | The blue "Drew 1 entity and 14 linked nodes" banner stays through later steps; the snackbar covers the minimap; *Fewest hops / Strongest ties* text is clipped (fixed since by `LA-CLIPPED-CONTROLS-1`); the Rows grid has an unnamed column. |
| 20 | C | No light/dark choice: the app renders dark even when the browser asks for light; the user menu holds only *Sign out*. |

### 3.4 Investigation, evidence and oversight

| # | Sev | Finding |
|---|---|---|
| 21 | B | **A fresh import shows a warning at once.** After Save: *WARNING — Data as-of date not known · days not assessed · index v1 (stale)*, and the Expand filter says the link index "is out of date … (the Dataset's relation SQL changed since the index was built)". Adding the details file made the index stale, yet the Data page says *graph up to date* and nothing offers a rebuild. Both seeded showcase cases also open on *index v2 (stale)*. |
| 22 | B | **The Dossier is hard to find**: section 8 of 9 inside Investigation settings…, not in File or the Investigation actions menu (which holds Settings / Close / Delete). |
| 23 | B | **An approver gets no prompt.** The manager sees the same "waiting for approval… Ask an approver" text as the requester, with no Approve button there and no badge or inbox; Approve / Deny live in Investigation settings ▸ Oversight. Approve is one click with no confirm or reason. |
| 24 | B | **Save defaults invite duplicates:** Name "Account", Purpose "Review of an Account and its links", never the start value. |
| 25 | B | **Quick question *Busiest nodes* proposes excluding the Investigation's own start entity** (14 links) as if it were a busy merchant. |
| 26 | C | A no-op Expand ("added 0 nodes") is still recorded as a step. Save snapshot's button reads *Save analysis* and shows no confirmation; a new Case from graph nodes is refused "ask for Reveal rights". |
| 27 | C | The read-only reviewer still sees the full Expand panel and the *How to investigate* coaching; the reason reads "needs Incident management". |

### 3.5 Words, time and access

| # | Sev | Finding |
|---|---|---|
| 28 | B | **Wrong vocabulary on a bank case:** "From the 3 subscribers started from", "adds about 6 subscribers and 7 calls" (it seeded four accounts). |
| 29 | B | **Configuration keys and developer phrasing in user text:** "maxFanOut unbounded (threshold 2000), set by four_eyes_fan_out_above"; "-Dla.embed.enabled=true"; "members (owner or assignee) of an open Case … writes stay owner-only"; "Fill the required fields to run."; "Instantiate a template…"; "Open Cases from the objects store"; Dossier rows "outside [1, unbounded) - min inclusive, max exclusive", "as of fact 0", "no score vectors in the evidence", nanosecond timestamps; "its Files name no event day"; "Identity Fact log". |
| 30 | B | **Alerts page breaks the glossary:** "Metric", "batch ledger", a *Pipeline* column holding an Investigation id, `gte 1`, `passThrough`, snake_case rule ids as names. |
| 31 | B | **Three time formats:** "2026-11-15 07:51 UTC" (step log), "11/15/2026, 1:21:24 PM" (oversight), "2026-11-15 13:11" (Data, local, no zone). |
| 32 | A | **Check access to a new Space.** *(Raised from B on the re-check: it is the only finding about who can read data.)* FM Analyst, defined only in the showcase roster, landed in the admin's new Space (the browser remembered it) and could read its Datasets, arrivals and server inbox path; two calls answered 403 and surfaced as console errors instead of hidden controls. Investigations stayed private. Decide who may see a Space on creation. |
| 33 | C | Home: saved views are titled by ids (`provider_money_trail`), a sentence starts lower-case, and nothing names the current Space. The showcase Space is called "Link Analysis showcase" (Known: `LA-INDUSTRY-FOLD-1`). *What can I do next?* labels saved graphs "Open a case". Entities cards all read "Not assessed: not computed yet" with no way to compute. Geo gives no hint which Dataset has coordinates. Entity List type reads "Billing account" where the graph says "Account". |

### 3.6 Re-check against current master (`c8d94b4bd`, 2026-11-15)

Done by reading the code, not by driving the app again. "Stands" means the code that produces the finding is unchanged.
Findings 5, 8, 9, 13, 17, 22 and 25 were **not re-checked** and are assumed to stand.

| # | Now | Where it comes from |
|---|---|---|
| 1 | Stands, **by design** | Add data is off by default wherever people sign in (`DataSettings.selfImportDefault`), and an import may not switch it on. The reason text is fixed in `OnboardRoutes.java:127` and `add-data.model.ts:994`. The Add data page already shows an administrator *"You may turn it on: Settings, Add data"* (`la-app/add-data.page.ts:39`, in the tested build too): the hint exists and was missed, so it is too weak. |
| 2 | Stands, **operator decision open** | The seeded `admin` role holds no `canAuthorWorkbench` (`Roles.java:279`; only `power` and `super` do). `SPACE-CREATE-PARTIAL-1` closed 2026-11-15 by hiding the settings step and left the grant "an operator decision". |
| 3 | Stands, cause found | `OnboardDraft.typeChoice` (`OnboardDraft.java:890`): a column that is four-fifths numbers is typed text as a low-confidence choice (*A guess*); there is no such branch for dates or times, so a mostly-timestamp column is "the values are text" at high confidence. `timestampFormat` also needs every sampled value to parse. |
| 4 | Stands | The reasons are written once, on the server, when the draft is made (`EntityShapes.java:477`); a type change on screen does not ask again. |
| 6 | Stands | No *Confirm all* exists in the Add data component. |
| 7 | Stands | `add-data.component.html:11` offers *Explore in Link Analysis* only when the import's kind is `stream`. |
| 10 | Not re-checked for plurals | *"says msisdn"* / *"holds msisdn"* are server reason texts. |
| 11 | Stands, **by design** | A sign-in install masks by Entity Type by default (`EditionMaskingDefault`, operator answer A-16). |
| 12 | Stands, **by design** | The Oversight panel reveals "one entity at a time; each reveal is … recorded" (`link-analysis-oversight.component.ts:101`). |
| 14 | Stands, **by design** for the two aliases | Exploration reads use the Space's one identity key (`MaskTokens.spaceKey`); an Investigation mints aliases under its own key (`MaskTokens.java:32`). The raw `holder_name` beside a masked id was not re-checked. |
| 15 | Stands | `nodeLabels = signal(false)` (`link-analysis.component.ts:1596`). |
| 16 | Stands, **by design** | `LA-UNSAVED-INVESTIGATION-1` D1: "the start form lives only in the Investigation tab". The user guide describes the other behaviour; one of the two is wrong. |
| 18 | **Partly fixed, drive again** | Every fit now keeps the pills, the status line and a margin clear (`LA-OVERLAYS-HIDE-GRAPH-1`), and Radial lays out each connected group round its best-connected node (`LA-RADIAL-LAYOUT-SQUASH-1`). Whether the default layout still throws unlinked groups to the corners is not known. |
| 19 | **Partly fixed** | The clipped *Fewest hops / Strongest ties* toggle is fixed (`LA-CLIPPED-CONTROLS-1`). The banner (`start-from.ts:330`), the snackbar and the unnamed column stand. |
| 20 | Stands, small | `la-app/app.config.ts:32` sets `scheme: 'dark'`; the root component already follows the browser when the scheme is `auto`. |
| 21 | Stands, cause narrowed | *Stale* here is `IndexStaleness` `RELATION_SQL_CHANGED` (`IndexStaleness.java:73`): the Dataset's definition no longer hashes to what the index recorded. Adding a file alone does not do that (`IndexBuilder` blanks the file list). **To confirm:** the details import rewrote the Stream Dataset's definition. |
| 23 | Stands | The waiting text and *"Ask an approver"* are one string for every reader (`investigate-number.ts:696`). |
| 24 | Stands, **a consequence of 11** | The Save defaults are `"<Type> <value>"`, and `"<Type>"` alone when the type is masked (`start-from.ts:83`). Unmasked, the name carries the start value. |
| 26 | Stands | *Save analysis* is `link-analysis-evidence.dialogs.ts:208`. |
| 27 | Stands | *"needs Incident management"* is used in ten places. |
| 28 | **Not found, drive again** | No screen text spells "subscribers" in the Link Analysis UI or API at this commit; it is built from the Domain Profile's terms, so the Generic profile's words need checking on a running build. |
| 29 | Mostly stands | Still present: `maxFanOut` / `four_eyes_fan_out_above`, *Fill the required fields to run.*, *Instantiate a template…*, *Open Cases from the objects store*, *min inclusive, max exclusive*, *as of fact*, *no score vectors*, *name no event day*, *Identity Fact log*. `-Dla.embed.enabled=true` was left on purpose and waits on an operator call (`LA-UI-TEXT-PASS-2`, item 1). |
| 30 | Stands, **not a Link Analysis file** | The strings live in the shared Alerts screens (`inspecto-ui/src/app/modules/admin/alerts/`), so the fix belongs to `addon.incidents` and changes every product. |
| 31 | Stands | Oversight formats with `toLocaleString()` (`link-analysis-oversight.component.ts:160`). A shared `ledgerTime()` exists since `DATES-ONE-ZONE-1` and is used in four files: adopt it. |
| 33 | Stands where checked | *Open a case* is `la-app/next-steps.ts:88`; *not computed yet* is `EntityProfiles.java:895`; *Billing account* is the Telecom pack's term for `account`, shown in a Space that is not Telecom. |

Board rows this plan named that have **shipped** since the tested build: `LA-OVERLAYS-HIDE-GRAPH-1`,
`LA-CLIPPED-CONTROLS-1`, `LA-RADIAL-LAYOUT-SQUASH-1`, and `LA-UI-TEXT-PASS-2` (narrowed to five operator calls).
`SPACE-CREATE-PARTIAL-1`, which this plan did not name, closed the same day and bears on finding 2.

## 4. Plan

Each phase is one lane from a fresh `origin/master`, with its own tests; phases 1 and 2 are the ones a first-time user hits.

### 4.1 The five decisions

Four proposed fixes reverse a recorded decision, and one finding asks for a decision without offering one. The
questions and options are below; the answers taken are in §4.3, and each is also recorded in the hub it changes.

| # | Question | Today | Options |
|---|---|---|---|
| D1 (finding 2) | May the seeded `admin` role, or a Space's creator, save views and change Link Analysis settings? | No: `canAuthorWorkbench` is `power` and `super` only; left open by `SPACE-CREATE-PARTIAL-1` | (a) grant it to `admin`; (b) grant it to the creator in the Space they made; (c) keep it and make the quick start say which role finishes it |
| D2 (finding 1) | Is Add data on in a Space made through *Use my own data*? | Off wherever people sign in; an administrator turns it on | (a) the create dialog turns it on for a caller who holds `canAdminister`; (b) keep it off and make the locked panel carry a *Turn on* button for that caller; (c) keep as is, fix only the reason text |
| D3 (findings 11, 12) | What does a holder of *See unmasked ids* see by default? | Aliases, with reveal one entity at a time inside an Investigation (A-16, LA-19) | (a) one audited *Reveal* for the nodes on screen, with a reason; (b) raw by default for a holder, each read audited; (c) keep, and say on the start form and the node dialog where reveal is |
| D4 (finding 14) | One alias per entity across exploration and every Investigation? | The Space has one key; each Investigation has its own | (a) keep (an alias cannot be matched across Investigations) and say so on screen; (b) one key everywhere, accepting that it can |
| D5 (finding 32) | Who sees a Space the moment it is created? | Any signed-in user of the install who lands in it reads its Datasets, arrivals and inbox path | (a) the creator only, others by grant; (b) the roster of the Space it was created from; (c) keep, and state it in the admin guide |

### 4.3 Decisions taken (2026-11-15, under the operator's delegation; the operator may reverse any of them)

| # | Decision | Why | What it does not change |
|---|---|---|---|
| D1 | **(a)** The seeded `admin` role holds `canAuthorWorkbench`. | `admin` already holds `canConfigureAccess`, so it can give itself the capability: withholding it protects nothing, and it stops the role named "administrator" from finishing the quick start (save a view, change a Link Analysis setting). The lane checks that premise in the code first and does not grant if it is false. | The gate on every `PUT /settings/*` stays; `business`, `operations` and `support` gain nothing. |
| D2 | **(a) and (b)** *Use my own data* turns Add data on for a creator who holds `canAdminister`; the locked panel carries a **Turn on Add data** button for that same caller. | Choosing *Use my own data* is an explicit statement of intent, and the switch still goes through the existing administrator gate, so no gate is loosened. The text link that was there was missed by a careful tester. | Add data stays off by default wherever people sign in; an import still cannot switch it on. |
| D3 | **(a)** Masked stays the default; a holder of *See unmasked ids* gets one audited **Reveal** for the nodes on screen, with a reason. | It keeps the privacy default (A-16) and one audit row per entity (LA-19), and turns nine clicks into one. Raw-by-default for a holder (b) would make every read a disclosure nobody chose. | Reviewers and outsiders still see aliases; nothing is revealed without a recorded reason. |
| D4 | **(a)** Aliases stay per Investigation. Inside an Investigation every surface shows that Investigation's alias. | An alias that cannot be matched across Investigations is a privacy property, and one key everywhere would change every alias already minted, sealed Dossiers included. The defect seen was two aliases for one entity on one screen, and that is what gets fixed. | Exploration outside an Investigation keeps the Space's one key. |
| D5 | **(a)** A new Space is readable by its creator and by install administrators only; everyone else is admitted. | It is the only finding about who can read data, so it fails closed. The lane first reproduces the observation in a test and decides, with its reason, what happens to Spaces that exist today. | Basic, where nobody signs in, is unchanged. |

### 4.2 Phases

| Phase | Goal | Changes | Done when |
|---|---|---|---|
| **1. First run works for its creator** (findings 1, 2, 9, 32) — *gated on D1, D2, D5* | The person who creates a Space can finish the quick start | As D1, D2 and D5 decide; in every case a reason text that is true for a Space with no Pipeline, and the guide corrected to say where the dialog lands | A real-HTTP test creates a Space as a Demo Admin and walks the quick start as decided; a user D5 excludes gets 404 on the Space's Datasets, arrivals and inbox |
| **2. Masking a user can live with** (11–14, 24) — *gated on D3, D4* | A cleared user reads their own graph in one step | As D3 and D4 decide; mask or unmask personal detail columns with their id; say on the start form how to reveal; showcase Investigations get names with no entity id; the Save defaults follow (finding 24) | A real-HTTP test: a holder reads raw ids as decided, with an audit row per reveal; a reviewer and an outsider still get aliases. Then driven live once |
| **3. Honest, forgiving import** (3–7, 10) | A file with a few bad rows maps itself | In `OnboardDraft.typeChoice`, a column that is mostly dates or times is typed as such at low confidence, as numbers already are; a type change on screen asks the server for the roles and reasons again; **Confirm all**; the result lists rows left out with the reason and rows whose value was blanked; *Explore in Link Analysis* after a details import; fill *Left out* and *Covers* | The committed fixture (§5) imports with ≤ 3 clicks after the drop and the result names all four dirty rows; a draft test pins the mostly-timestamp column |
| **4. Index and data status agree** (21) | No warning on a healthy fresh import | **First:** reproduce in a test which act changes the Stream Dataset's definition hash (§3.6, finding 21). Then: that act rebuilds or keeps the link index; one status for the Data page, the Investigation header and the Expand filter; plain words for a stale index and a **Rebuild** action; seeds leave a fresh index | A real-HTTP test: after the two imports the staleness answer is clean; seeded cases open without *stale* |
| **5. Find the important things** (16, 22, 23, 25) | Dossier, approvals and the start area are where people look | *Build Dossier* and *Members* in the Investigation actions menu and File; a pending-approvals badge and an **Approve / Deny** pair on the waiting line for an approver, with a confirm; no exclusion offer for a start entity; settle finding 16 (the guide or `LA-UNSAVED-INVESTIGATION-1` D1 gives way) | Component specs for the approver's line, the menu entries and the exclusion offer; then driven live as analyst and manager |
| **6. Canvas** (15, 17, 19, 20; 18 after a new drive) | A drawn graph is readable without tuning | Labels on by default under a node threshold; place expanded nodes outside existing ones and keep positions on Save; keep the start highlight; clear the banner on the next step; the la-app scheme follows the browser (`auto`) with a choice in the user menu. Finding 18 only if a drive on current master still shows it | Layout specs: no two node boxes overlap on the 17-node fixture after Expand and after Save; screenshots of the 17-node and 426-node graphs |
| **7. Words and time** (26–29, 31, 33) | One vocabulary, one clock | A wording pass over the strings §3.6 lists for 26, 27, 29 and 33 (glossary terms only, no config keys; `-Dla.embed.enabled=true` stays with `LA-UI-TEXT-PASS-2`); every Link Analysis time through `ledgerTime()`; pack terms only in a Space of that pack. Finding 28 after a new drive | `check-vocabulary` covers the changed strings; a spec asserts the Oversight panel uses the shared time format |

**Moved out of this plan** (each needs its own board row when picked up):

- **Finding 30** (the Alerts page vocabulary) is in the shared Alerts screens: an `addon.incidents` row, not a Link Analysis phase.
- **Finding 8** (more kinds of data in Create a Space) is a feature, and overlaps `LA-INDUSTRY-FOLD-1`.

**Order:** the §4.1 decisions, then 1 → 2 → 3 → 4, then 5–7 in any order. Phases 3 and 4 need no decision and may start
at once. Phases 1–4 are P2; 5–7 are P3.

## 5. Evidence

Screenshots of this drive are in `docs/okf/product/assets/` (files `la-guide-*.png`) and are used by
the user guide. The test files were invented for the drive (`transfers.csv`, `accounts.csv`) and are not committed.
**Owed before phase 3:** a synthetic copy of both files (461 and 138 rows, the four dirty rows named in a comment)
committed as a test fixture, so the phase's "done when" can be run.
