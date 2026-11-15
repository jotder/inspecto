<!--
  ACTIVE PLAN — docs/superpower/
  Created 2026-11-15 from a hands-on, full-cycle drive of Inspecto LA (Preview x la x demo, built from 11aa321c5).
  Board row: LA-FULL-CYCLE-TEST-1 (docs/BACKLOG.md §3.12). Archive when its phases ship or are split into their own rows.
-->

# Inspecto LA full-cycle user test — findings and fix plan (2026-11-15)

## 1. What was tested, and how

- **Build:** `package.ps1 -Edition Preview -Product la -Distribution demo -NoBakeDemo` from `origin/master` `11aa321c5`,
  run on `127.0.0.1:8111` with Demo Users. Spaces: `la-showcase` and `la-claims` (scaffolded, then seeded with
  `tools/seed-la-showcase.mjs --apply` and `tools/seed-la-claims.mjs --apply`), plus one Space created in the test.
- **Method:** a headless Chrome driven step by step (click, type, read the page text, screenshot at 1440 × 900), acting as
  an end user with only the screens and the [user guide](../okf/product/module/la-core/user-guide-link-analysis.md).
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
| 15 | B | **No node labels on a 15-node graph**, and no hint that labels are off. (Known: `LA-OVERLAYS-HIDE-GRAPH-1` covers "labels start off".) |

### 3.3 Canvas and layout

| # | Sev | Finding |
|---|---|---|
| 16 | B | **New-Space empty canvas offers only "Start from the Investigation tab →".** The bounded Start area with tabs (*Start a graph, Preset queries, Build a question, Explore the Dataset*) that the guide describes did not show on Preview with Investigations. |
| 17 | B | **Layout after a step.** Expanded nodes land inside the star on top of links; after Save the graph is redrawn with overlapping nodes, one node under the *Graph counts* overlay, and the start entity loses its highlight. |
| 18 | B | **Fit to screen leaves most of the canvas empty** on `claims_network` (426 nodes): two tiny unlinked groups are thrown to the corners, so the ring is a small blob; with the dock open the graph sits in the top quarter. (Related: `LA-OVERLAYS-HIDE-GRAPH-1`, `LA-RADIAL-LAYOUT-SQUASH-1`.) |
| 19 | C | The blue "Drew 1 entity and 14 linked nodes" banner stays through later steps; the snackbar covers the minimap; *Fewest hops / Strongest ties* text is clipped (Known: `LA-CLIPPED-CONTROLS-1`); the Rows grid has an unnamed column. |
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
| 32 | B | **Check access to a new Space.** FM Analyst, defined only in the showcase roster, landed in the admin's new Space (the browser remembered it) and could read its Datasets, arrivals and server inbox path; two calls answered 403 and surfaced as console errors instead of hidden controls. Investigations stayed private. Decide who may see a Space on creation. |
| 33 | C | Home: saved views are titled by ids (`provider_money_trail`), a sentence starts lower-case, and nothing names the current Space. The showcase Space is called "Link Analysis showcase" (Known: `LA-INDUSTRY-FOLD-1`). *What can I do next?* labels saved graphs "Open a case". Entities cards all read "Not assessed: not computed yet" with no way to compute. Geo gives no hint which Dataset has coordinates. Entity List type reads "Billing account" where the graph says "Account". |

## 4. Plan

Each phase is one lane from a fresh `origin/master`, with its own tests; phases 1 and 2 are the ones a first-time user hits.

| Phase | Goal | Changes | Done when |
|---|---|---|---|
| **1. First run works for its creator** (findings 1, 2, 9, 32) | The person who creates a Space can finish the quick start | Creating a Space through *Use my own data* turns *People may add data* on; the seeded `admin` role (and the Space creator) holds Workbench authoring; decide and enforce who sees a new Space; fix the "comes from Pipelines" reason | A real-HTTP test creates a Space as a Demo Admin, uploads a file, saves a view and changes masking, all 200; a user outside the Space gets 404 |
| **2. Masking a user can live with** (11–14) | A cleared user reads their own graph in one step | A **Reveal** control on the canvas toolbar and in the node dialog (one audited action for the nodes on screen, with a reason); one alias per entity everywhere; mask or unmask personal detail columns with their id; say on the start form how to reveal; showcase Investigations get names with no entity id | Driven live: admin and lead read real ids after one Reveal; reviewer and outsider still see aliases; an audit row per reveal |
| **3. Honest, forgiving import** (3–7, 10) | A file with a few bad rows maps itself | Type detection tolerates a small share of bad values and marks it *A guess*; a type change re-runs the role and link proposals and its reason text; **Confirm all**; the result lists rows left out with the reason and rows whose value was blanked; *Explore in Link Analysis* after a details import; fill *Left out* and *Covers* | The test file of this drive imports with ≤ 3 clicks after the drop and the result names all four dirty rows |
| **4. Index and data status agree** (21) | No warning on a healthy fresh import | Adding details rebuilds or keeps the link index; one status source for the Data page, the Investigation header and the Expand filter; plain words for a stale index and a **Rebuild** action; seeds leave a fresh index | After the two imports the header shows no warning; seeded cases open without *stale* |
| **5. Find the important things** (16, 22, 23, 24, 25) | Dossier, approvals and the start area are where people look | *Build Dossier* and *Members* in the Investigation actions menu and File; a pending-approvals badge and an **Approve / Deny** pair on the waiting line for an approver, with a confirm; Save defaults from the start value; no exclusion offer for a start entity; show the Start area tabs on every edition | Driven live as analyst and manager; unit tests for the defaults and the exclusion rule |
| **6. Canvas** (15, 17–20) | A drawn graph is readable without tuning | Labels on by default under a node threshold; place expanded nodes outside existing ones and keep positions on Save; Fit packs unlinked groups beside the main one; keep the start highlight; clear the banner on the next step; a light/dark setting | Screenshots of the 17-node and 426-node graphs with no overlap and no node under an overlay |
| **7. Words and time** (26–31, 33, 8) | One vocabulary, one clock | A wording pass over the strings listed in 28–30 and 33 (glossary terms only, no config keys); one time format with the zone, the viewer's zone by default; more kinds of data in Create a Space from the shipped industry packs | `check-vocabulary` extended to the Alerts page strings; a spec asserts the shared date pipe |

**Order:** 1 → 2 → 3 → 4, then 5–7 in any order. Phases 1–4 are P2; 5–7 are P3.

## 5. Evidence

Screenshots of this drive are in `docs/okf/product/assets/` (files `la-guide-*.png`) and are used by
the user guide. The test files were invented for the drive (`transfers.csv`, `accounts.csv`) and are not committed.
