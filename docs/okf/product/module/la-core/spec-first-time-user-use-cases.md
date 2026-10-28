---
type: Reference
title: Inspecto LA - first-time user use cases (blank product to first findings)
description: Goals, use cases and the guided path a beginner needs in the Inspecto LA UI, from an empty install through data, Entity Types, Entity Lists and the first graph; operator answers of 2026-10-22; reconciled with the console first-run use cases.
resource: inspecto-ui/projects/la-app/
tags: [module, la-core, link-analysis, onboarding, use-cases, spec]
timestamp: 2026-10-24T00:00:00Z
---

# Inspecto LA - first-time user use cases

**Status: use cases agreed with the operator 2026-10-22 (§8); built by `LA-ONBOARD-1` (phases 0-8, closed
2026-10-22, plan archived), with the follow-up fixes `LA-ONBOARD-FIXES-1`, `LA-ONBOARD-DEFECTS-1` and
`LA-ONBOARD-RESIDUALS-1` (open for its demand-gated items).** Each use case below carries an **As built** line
(checked against `inspecto-ui/projects/la-app` and `projects/link-analysis` on 2026-10-24) with the on-screen
names; **Today** keeps the 2026-10-22 walkthrough state the work started from. The acceptance drive is
[`tests-onboarding-live-drive.md`](tests-onboarding-live-drive.md) (Basic: first graph 2 min 40 s after first
launch); the as-built design is [architecture](architecture.md) §3.4, §6.10-§6.13.

**Evidence.** On 2026-10-22 a fresh Basic bundle (`la-app` UI, master `cb4feedd9`) was started on an empty Spaces
root and driven as a first-time user with a synthetic mobile-money file pair (`transfers.csv` 2,215 rows,
`accounts.csv` 312 rows, a hidden mule ring and one busy merchant). Today's state in each use case comes from
that walkthrough, marked **F-n** and listed in §7.

**Companion.** The [console first-run use cases](../etl/spec-first-time-user-use-cases.md)
(nine use cases UC-1…UC-9, help catalogue H1…H9) cover the console path from a file to a Dataset. This doc covers
what Inspecto LA adds on top of it. §9 maps the two and records what was reconciled. Edition rules for the data
layer are owned by `DATA-TIERS-1` (the draft `PIPELINE-EDITIONS-1` was merged into it and never published; its
D-PE decisions map to DT numbers there). Both use the same copy rules: **guide**, never "wizard"; **Onboard** is
the one verb for creating a data origin (GLOSSARY).

Related: [DATA-TIERS-1 plan](../../../../superpower/data-tier-editions-plan.md) ·
[LA-ONBOARD-1 plan](../../../../archived-documents/plans-archive/la-onboarding-plan.md) ·
[ONBOARDING-FIRST-RUN-1 console plan](../../../../superpower/first-run-onboarding-plan.md) · [user guide](user-guide-link-analysis.md) ·
[Space guide steps](user-guide-link-analysis.md#31-the-getting-started-page) · [GLOSSARY](../../../../GLOSSARY.md).

---

## 1. Who the beginner is

| Persona | Edition | Situation | What they bring |
|---|---|---|---|
| **P1 Solo analyst** | Basic (free, no sign-in or demo sign-in, own laptop, `la-app` only) | Downloaded Inspecto LA to look at their data. Has never seen a Pipeline. **Is their own administrator**: with no sign-in every permission check passes, so P1 does everything P3 does. | Files exported from another system (CSV, Parquet, JSON). |
| **P2 Team analyst** | Standard and above | The data is already loaded by a colleague. Needs to find the right Dataset and start. | A name, a number or an account to look at; a list of suspects. |
| **P3 Space administrator / data owner** | Standard and above | Makes data arrive regularly, decides Entity Types, keeps category lists. | Connections to real systems, knowledge of the data. |
| **P4 Supervisor / compliance lead** | Standard and above | Decides what must never be shown or followed (VIPs, politicians, defence, call centres). | Lists of protected or noisy entities. |

The beginner's vocabulary is the business one ("customer", "wallet", "phone number", "agent", "transfer",
"call"). Product terms (Dataset, Entity Type) are learned only when needed and always shown with a plain-words
explanation. Pipeline terms never appear on Basic (§8 A-6).

### 1.1 Two contexts: who the user is decides the rules, the LA level decides the screens

Inspecto LA runs in two very different places. Three questions are kept apart (operator, 2026-10-22, A-14 to A-16):

| Question | Decided by | Free install (no sign-in) | Enterprise install (sign-in, e.g. a Revenue Assurance product) |
|---|---|---|---|
| **Who is the user?** | Edition: Basic ships no authenticator (`authMode: none`); Standard and above sign in (OIDC) | One person who owns everything. There is no administrator; every permission check passes. | Many people with roles; administrators exist. |
| **How much Link Analysis?** | **LA level** (A-14): *Explorer*, or *Investigations* as a billable add-on enforced at runtime | Explorer (Basic) | Explorer, plus Investigations when the install holds the add-on (included by default on Standard and above) |
| **Where does data come from?** | Context, and a per-Space switch (A-15) | The user's own files ("Add data") | Datasets built by Pipelines; "Add data" only where an administrator turned it on |

**Rules that follow who the user is, never the LA level:**

| Behaviour | No sign-in | Sign-in |
|---|---|---|
| Masking of entity ids | Off by default (anyone could reveal anyway) | On and enforced; reveal audited (A-16) |
| Category exclusion lists (admin-owned, auto-applied, restricted) | The user owns and sees every list; "restricted" has no effect | Enforced: admin-owned, auto-applied, members hidden without the reveal right |
| "Add data" | Always available | Per Space, off by default; when on, only with `canUploadData` (A-15) |
| Pipelines | Hidden; "Add data" runs them behind the scenes | Built by data owners in the console; analysts see "kept up to date by…" |
| "Ask an administrator" | Never shown | Shown where a right is missing |

**The LA level only decides which screens exist** (Explorer: graph, Grow, algorithms, lists, saved views as cases;
Investigations: recorded steps, approvals, Dossiers, integrations), the same in both contexts. An enterprise
install without the Investigations add-on is the "basic LA inside an enterprise product" case: full governance,
explorer screens, Investigation actions shown locked with the add-on as the reason. The edition stays the one
product-wide switch for governance (DATA-TIERS-1 DT-11).

## 2. What the beginner wants (goals)

| Goal | In the user's words | Typical start |
|---|---|---|
| **G1 Who is linked to this one?** | "Show me everyone this account / number deals with, and who they deal with." | One known entity. |
| **G2 What is suspicious in here?** | "I have a month of transfers. Are there rings, hubs, chains?" | A whole Dataset, no starting point. |
| **G3 Are these linked to each other?** | "Here are 40 suspects. Are they connected, and through whom?" | A set of entities (a list, a paste, an alert). |
| **G4 Clean the picture** | "Take out the supermarket, the call centre and the bank's own accounts." | A noisy graph. |
| **G5 Keep and show what I found** | "Save this as my case, send it to my manager, find it again next week." | A finished graph. |
| **G6 Keep it current** | "Next month's file should just show up." | Repeated data (Standard and above). |

**Primary entity (per Dataset, §8 A-7).** Each Dataset has a primary Entity Type: the kind of thing its rows
are mostly about (transfers → account; a device log → device identity). It is proposed when the data is added
(UC-03 step 3) and changeable on the Dataset's card. It decides what that Dataset's graph labels and leads with.

| Data | Usually primary | Usually secondary |
|---|---|---|
| Mobile-money transfers | Stored-value account / wallet | Agent or till, device, phone number, location |
| Call records | Phone number (MSISDN) | Subscriber identity (IMSI), device identity (IMEI), cell / location |
| Payments | Account | Customer, card, device, IP, merchant |
| Anything else | Whatever the from/to columns hold | Anything classified in other columns |

**Primary set.** Besides one entity, users start from a **set**: an Entity List (suspects, watch list), the
result of an algorithm (top 20 busiest, one community), or an alert's entities (Standard and above).

**A case on Basic (§8 A-1b).** Spaces are per data domain (§8 A-1), and Basic has no Investigations. A case on
Basic is a **named saved view** (graph, starting entities, exclusions) plus the **Entity Lists** it uses; the home
page groups them under **My work**. Recorded Investigations, approvals and Dossiers stay Standard and above.

## 3. The beginner's path at a glance

```
0 First launch ─► 1 Space ─► 2 Data in ─► 3 What is in it ─► 4 Focus ─► 5 First graph
                                                                         │
             9 Come back ◄── 8 Keep & share ◄── 7 Analyse ◄── 6 Grow & clean
```

| Stage | Use cases | Basic on 2026-10-22 | As built (2026-10-24) |
|---|---|---|---|
| 0 First launch | UC-01 | No guidance; Link Analysis shows a query form over nothing (F-1) | Built: *Getting started* page and *Space guide* |
| 1 Space | UC-02 | Impossible with zero Spaces (F-2) | Built: switcher *No Space yet*, *Create a Space* |
| 2 Data in | UC-03, UC-04, UC-05, UC-06 | Impossible: no upload on Basic; 9 setup steps over the API, no UI (F-3, F-4) | Built: *Add data* → *Onboard a file*; UC-06 automation not built |
| 3 What is in it | UC-07, UC-08 | Entity Types are read-only; no screen sets which column holds which Entity Type (F-5) | Built: *Entities* page, *Columns* tab, *New Entity Type* |
| 4 Focus | UC-09, UC-10 | Entity Lists work; no general single-entity start (F-7) | Built: *Start from...* box |
| 5 First graph | UC-11 | "Investigate" draws the whole Dataset (F-8) | Built: a start draws one ring |
| 6 Grow & clean | UC-11 | Grow, held tray and Exclude work, but only after a whole-Dataset draw | Built |
| 7 Analyse | UC-12 | Paths, communities and centrality work and found the ring | Built: *Find something interesting* |
| 8 Keep & share | UC-13 | Saved views and export exist; next-step text points to Investigations (F-10) | Built: *Save as a case*, *My work* |
| 9 Come back | UC-14 | Re-import has no UI on Basic | Built: *Add a file*, *Run now*, *What changed in your data* |

---

## 4. Use cases

Each use case gives the goal, today's state, the target flow and the help involved. Help ids **H1-H9** are the
console catalogue's (§9); everything is deterministic on Basic (§8 A-9). "Basic" means every edition unless
marked **Standard+**. A capability the edition lacks is shown **locked with one line of reason** inside a screen;
whole Standard+ pages (Alerts, Investigations) stay out of the Basic navigation (§8 A-11).

### UC-01 First launch: choose how to start

- **Actor:** P1 (any). **Trigger:** no Space, or a Space with no Dataset (first-run detection keys on "no Dataset
  yet", not "zero Runs", as in the console use cases).
- **Goal:** know in 10 seconds what Inspecto LA does and take the first step.
- **Today:** the home page lists Link Analysis, Entities and Entity Lists. Link Analysis opens a full query form
  although nothing exists; no message says why it is empty (F-1).
- **Target flow:**
  1. A **Getting started** page replaces the home cards while setup is incomplete:
     - **Try it with sample data** (H7) - creates a sample Space from the `la-showcase` template, loads and runs
       its data from one button, opens a ready graph; labelled as invented data. Also offered as **New from
       sample** in the Space switcher, to anyone who may create Spaces (§8 A-8).
     - **Use my own data** - UC-02 then UC-03 (a file dropped on this page starts UC-03 directly, H8).
     - **Open my team's data** (when Spaces exist) - Space switcher and UC-05.
  2. The **Space guide** (H1) stays visible (collapsible) until done: *Space · Data · What the columns hold ·
     First graph · (optional) A list*. Each line opens the step; each tick comes from server state.
- **Done when:** the user is inside a Space with data, or inside the sample Space.
- **As built: done.** The front page is **Getting started** while there is no Space or no Dataset: cards **Try it
  with sample data** (button **Try the sample**), **Use my own data** (**Create a Space**, or **Add data** when a
  Space exists) and **Open my team's data**; the **Space guide** below them ticks *Space · Data · What the columns
  hold · First graph · A list (optional)* from server state. The sample is the Basic-safe **`la-sample`** template
  (Space *Sample: invented data*), not `la-showcase`, which cannot be created on Basic.

### UC-02 Create a Space (one per data domain)

- **Actor:** P1, P3. **Trigger:** first launch, or **New Space** in the switcher.
- **Goal:** one long-lived place for one kind of data, holding many cases (§8 A-1).
- **Today:** with zero Spaces the switcher is not drawn, so a Space can only be made through the API
  (`POST /spaces`). Once one exists the switcher appears with "New space…" (F-2).
- **Target flow:**
  1. Name it after the data ("Mobile money", "Call records").
  2. **What kind of data?** Mobile Money · Telecom · Generic (pack). This sets the default Entity Types and
     wording. Three example column names per pack help the user recognise their data.
  3. Create → **Add data to this Space** (UC-03).
- **As built: done.** With zero Spaces the switcher reads **No Space yet** and offers **New Space…** and **New from
  sample…** (F-2 fixed). The **Create a Space** dialog asks a **Name** and **What kind of data?** (*Mobile Money*,
  *Telecom*, *Generic*, each with three example columns), then lands on *Onboard a file*. With sign-in only an
  administrator may create a Space.

### UC-03 Add a file of events (transfers, calls, messages)

- **Actor:** P1 (Basic), P2, P3. **Trigger:** **Add data**, a dropped file (H8), the empty "Explore a Dataset"
  card, or the Space guide.
- **Context (§1.1):** always available with no sign-in. With sign-in, available only in Spaces where an
  administrator turned it on, and only with `canUploadData`; elsewhere the control is locked with "Data in this
  Space comes from Pipelines - ask an administrator" (A-15).
- **Goal:** turn a file into something Link Analysis can draw, without learning Pipelines.
- **Today:** no UI on Basic. The analyst upload lives inside Investigations (Standard+) and only drops the file in
  a Collector's inbox. Setup took 9 steps over the API (parse preview, schema, a schema rewrite because suggested
  selectors are names but must be column numbers, Pipeline write, register, copy the file to the inbox, trigger,
  Dataset definition with column classification, saved view) (F-3, F-4).
- **Target flow ("Add data", one screen, file first):** this is the console's file-first onboarding (console UC-2,
  UC-9) embedded in Inspecto LA, with one step added for Link Analysis.
  1. **Drop the file**: CSV / TSV, Parquet, JSON or NDJSON. Excel when the bundle carries the DuckDB `excel` extension, otherwise "save as CSV" (DATA-TIERS-1 DT-8). Limits
     shown before upload: 500 MB per file, 10 million rows per import, 20 imports per Space, all configurable
     (§8 A-2). A database is not offered on Basic; *Read from a database* is shown locked (§8 A-12).
  2. **Check the columns** (H2): detected format, delimiter, header and types are pre-applied with their evidence;
     low-confidence choices are highlighted and need a click. Rename, drop a column, filter rows.
  3. **What does each column hold?** (the Link Analysis step, H9): Inspecto proposes, the user confirms:
     - *who* columns → Entity Type (account, phone number…), from value shapes and names; the most common one is
       proposed as the Dataset's **primary Entity Type** (§8 A-7);
     - *link* → which column is **from** and which is **to**; optional kind, time and amount;
     - everything else → details shown on links.
  4. **Create and run** → one confirm saves the Dataset, its Entity Type mapping and a default link mapping. On
     Basic the Collector and Pipeline behind it are created and run behind the scenes and never shown; the Dataset
     card says "one-time import" with its summary (§8 A-6).
  5. **Problems in plain words** (H3): Expectations, quarantine and dedupe run on every edition (§8 A-13), so the
     summary says "12 rows have no to_account - kept aside, *Show rows*".
  6. Lands on the first graph choice (UC-09) with the new Dataset picked.
- **Help:** a running summary ("2,215 transfers between 312 accounts, 1 to 30 Sep 2026; one account receives 32% of
  rows - it may be a merchant"); every proposal says why it was proposed.
- **Done when:** the Dataset is listed, its Entity Types are assessed, and a graph can be drawn.
- **As built: done, except row filtering.** The button is **Add data**, the screen **Onboard a file** (route
  `/add-data`; API `POST /onboard/files`; every edition). Step 1 asks *Events between entities* or *Details about entities*, then **Choose a
  file**; *Read from a database or a remote system* is locked on Basic. Step 2 marks a low-confidence choice **A
  guess** with **Confirm**; columns can be renamed, retyped or **Leave out**. Step 4 is **Create and run**. The result
  offers **Explore in Link Analysis**, **Add details about these entities** and **Add another file**. With sign-in
  the per-Space switch is **Settings → Add data** (off by default). Not built: no-code row filtering
  (`LA-ONBOARD-RESIDUALS-1` item 6). An Add data import is shared with its Space (`LA-ONBOARD-DEFECTS-1`).

### UC-04 Add a Reference file (who the entities are)

- **Actor:** P1, P2. **Trigger:** **Add details about accounts** after UC-03, or **Add data → It describes
  entities** (the short form of *Onboard Reference*).
- **Goal:** names, KYC level, address or segment shown on the nodes, and usable to filter and colour.
- **Today:** loads only as another Dataset (the `accounts` file); nothing attaches it to the graph on Basic.
- **Target flow (allowed on Basic, §8 A-5):** the same "Add data" screen; step 3 asks "Which column identifies the
  entity?" (e.g. `account_id` → Account) and treats the other columns as **entity details**. Link Analysis shows
  them on nodes and filters and colours by them. Re-importing replaces the previous version and says how many rows
  changed (a Reference is mutable, GLOSSARY). Joins that build new Datasets inside a Pipeline stay Standard+
  (DATA-TIERS-1 DT-6) and are shown locked.
- **Help:** "312 accounts described; 295 of them appear in your transfers; 17 do not" (H9).
- **As built: import done.** *Details about entities* on the same screen asks **Which column identifies each
  entity?**; a later **Add a file** always replaces a Reference. Not verified on 2026-10-24: whether nodes can be
  filtered and coloured by the details (the node detail reads Reference rows through `POST /inv/reference-rows`, which
  predates this work), and the "N of them appear in your transfers" count.

### UC-05 Use a Dataset that a Pipeline already builds

- **Actor:** P2. **Edition:** any where Datasets exist (Standard+ in practice).
- **Goal:** find the right Dataset quickly and trust it.
- **Today:** "Explore a Dataset" lists Datasets and ranks the ones that look like links first (good). Freshness,
  origin and update pattern are not shown in the picker.
- **Target flow:** the picker shows per Dataset: plain description, rows, time span, last update, "kept up to date
  by a Pipeline" or "one-time import", its primary Entity Type and the others it holds.
- **As built: partly.** An import's Dataset card says *one-time import* with its last import, and the Start box picks
  a Dataset that can start a graph. The full per-Dataset picker line (time span, last update, origin, Entity Types) was
  not verified on 2026-10-24.

### UC-06 Keep data arriving - **Standard+**

- **Actor:** P2 asks, P3 builds. **Goal:** next month's file appears without repeating UC-03.
- **Today:** only in the full console (`/collectors`, `/pipelines`); not in `la-app`.
- **Target flow:**
  1. After a first successful import, **Keep this up to date** (watch a folder or a schedule) turns it into an
     ordinary Pipeline; the Dataset card says "updates daily from inbox X". On Basic the item is shown locked:
     Basic is manual-only (DATA-TIERS-1 DT-5, DT-17), so **Add a file** and **Run now** sit together on the Dataset card (UC-14).
  2. A person without the right to configure sees **Ask an administrator to keep this up to date** instead.
  3. Anything beyond that (joins, SQL steps, remote Collectors) is **Open in editor** in the console, pre-filled.
     Inspecto LA never edits Pipelines itself (§8 A-6).
- **As built: hand-off only.** The Dataset card shows **Keep this up to date** locked on Basic, *Ask an administrator
  to keep this up to date* with sign-in, and an *Open in editor* hand-off. The automation itself is not built: the
  import Pipeline uses a manual trigger until DT-17, and no module declares the `pipeline.automation` feature yet
  (`LA-ONBOARD-RESIDUALS-1` item 3, demand-gated).

### UC-07 Say what the columns hold (Entity Types)

- **Actor:** P1, P3. **Goal:** the graph knows that `from_account` and `account_id` are the same kind of thing,
  so they join up and get one profile.
- **Today:** Entity Types are listed read-only in Settings; the classification that ties a column to an Entity Type
  has no screen anywhere and was set by API (F-5). The Entities page lists every Entity Type of every pack (nine for
  a money-only Space), all "Not assessed"; "Masked: Yes" sits next to "Masking mode none" (F-6).
- **Target flow:**
  1. Done inside UC-03 step 3 for new data.
  2. **Entities page → Columns** lists each Dataset column that looks like an identifier with its Entity Type or
     "not set", and lets the user set or change it. Changing it re-assesses that Dataset.
  3. **New Entity Type** (name, an example value, how to normalise) for things the pack does not know.
  4. The Entities page shows only Entity Types that occur in this Space's data; the others sit under "Not in your
     data".
- **Help:** value-shape detection (phone number lengths, IMEI check digit, IBAN pattern, e-mail) with the reason
  shown; "2 columns hold accounts: transfers.from_account, transfers.to_account - they are the same accounts".
- **As built: done.** The **Entities** page lists only the Entity Types the data holds (the rest folded under **Not in
  your data**); its **Columns** tab shows each identifier-like column with its Entity Type or **Not set**, with the
  Dataset's **primary** Entity Type picker beside its name; **New Entity Type** asks a name, an example value and how
  to compare. Route `PUT /datasets/{id}/columns/{column}`. F-5, F-6 and F-12 fixed.

### UC-08 Say what a link is

- **Actor:** P1, P2. **Goal:** the right arrows: who paid whom, who called whom, which device a number used.
- **Today:** the query panel's from/to pickers work and are suggested from column names. Mapping is saved only as a
  view; until one exists entity profiles show links as "not assessed" (F-9).
- **Target flow:** links are defined once per Dataset (UC-03 step 3, or later on the Dataset's card) as its
  **default link mapping**, reused by every start without asking again. Several link kinds from one Dataset are
  allowed (payer → payee; payer → device).
- **Help:** a 5-row preview drawn as arrows while choosing columns.
- **As built: mapping done.** The commit saves `defaultLinkMapping {from, to, kind?, time?, amount?, details?}` and
  `primaryEntityType` on the Dataset entry; Entity profiles and every start read it (F-9 fixed). Not found in the
  code on 2026-10-24: the 5-row arrow preview, and editing the link mapping later from the Dataset card.

### UC-09 Choose the focus (one entity or a set)

- **Actor:** any. **Goal:** start from what matters, not from a hairball.
- **Today:** a seeded start exists only as "Investigate a number" (Phone number) and refuses an account ("A number
  is 6 to 15 digits") (F-7). "Investigate" on an account's profile in Basic asks for a Dataset and then draws the
  whole Dataset (312 nodes) instead of the account (F-8).
- **Target flow:** one **Start** box on the Link Analysis page: "Start from…"
  - **one entity** - the user **picks the Entity Type first** (the picked Dataset's primary type preselected), then
    types the value with type-ahead showing matches and their link counts (§8 A-7b);
  - **a list** - pick an Entity List (UC-10);
  - **a paste** - paste values; unknown values reported;
  - **the busiest / a community** - after UC-12;
  - **the whole Dataset** - allowed, with a warning above the node cap.
  The start draws the chosen entities plus one ring of neighbours (busy ones held), so Grow continues from there.
  "Investigate" on an Entity profile does the same for that entity.
- **Help:** "MULE-HUB: 10 links in transfers, first seen 3 Sep, last seen 30 Sep"; chips such as "Start from the 5
  busiest accounts".
- **As built: done.** The **Start from...** box offers **One entity** (Entity Type first, type-ahead with link
  counts), **A list**, **A paste** (at most 25 values per start) and **The whole Dataset** (warns above the node cap);
  the busiest and groups come from *Find something interesting* → **Start from these**. On the Investigations level
  *One entity* seeds an Investigation (**Purpose / legal basis**, **Investigate**). *Investigate* on an Entity's page
  starts from that entity (F-7, F-8 fixed). Since 2026-10-25 (D-LAE4, operator-confirmed) Entity profile pages are
  Standard and up: Basic hides the Entities area and answers the two profile reads 503.

### UC-10 Keep lists of entities (suspects, watch, exclusion)

- **Actor:** P1, P2; P4 for category lists (`canManageExclusionCategories`; on Basic P1 holds it). **Goal:**
  remember who matters, check new data against them, keep noise out.
- **Today:** Entity Lists work on an empty Space (create; purpose allow / block / watch / exclusion; add members).
  Category exclusion lists (admin, auto-applied, restricted) shipped in `LA-GROW-EXCLUDE-1`.
- **Target flow:**
  1. Create from the graph (select → "Add to a list…"), from a paste, or from a file column (pick the column and
     its Entity Type).
  2. Purpose in plain words: *Watch these* · *Never follow these* (exclusion) · *Known good* · *Block*.
  3. A list shows how many members appear in the Space's data and where.
  4. Lists can be a starting set (UC-09) and an exclusion while growing (UC-11).
- **Help:** after UC-12 centrality, "MERCHANT-BIG has 270 links. Add it to an exclusion list?"; first lists
  suggested from the pack ("Call centres", "Merchants").
- **As built: mostly.** **Add to a list** on a *Find something interesting* row and **Exclude… → Add to a list…** on
  the graph write to an existing list of the same Entity Type or a new one; one purpose vocabulary across the Entity
  Lists page, New list and Add to a list (*Never follow these (exclusion)* …); a list is a start in the Start box;
  pack-suggested lists come from the Domain Profile. Not verified on 2026-10-24: creating a list from a file column,
  and the "members that appear in the data" count.

### UC-11 First graph from a start, then grow and clean

- **Actor:** any. **Goal:** G1 and G4.
- **Today:** Grow (preview, both ways, held busy numbers, Undo), the held tray, right-click Exclude and the
  Hidden-by-lists panel all work and agree with their previews (fixed 2026-10-22). On Basic they are reached only
  after drawing a whole Dataset, so a typical first Grow adds nothing.
- **Target flow:** UC-09 start → graph with one ring → **Grow** (G) one step at a time with preview → busy numbers
  held with a reason → **Remove held** or **Expand anyway** → **Exclude** (X) into this view only or a list →
  **Undo** (Ctrl+Z).
- **Help:** existing first-time hints; a "why is this node here?" line on every node (which row and which step).
- **As built: done, except the "why is this node here?" line** (not found in the code on 2026-10-24). A start draws
  one ring; **Grow** (**G**) with preview, the **Held** tray (**Remove held**, **Expand anyway**), **Exclude…** (**X**),
  the **Hidden** panel and **Undo** (**Ctrl+Z**) follow. The acceptance drive grew from a one-entity start.

### UC-12 Discover without a starting point

- **Actor:** any. **Goal:** G2 and G3.
- **Today:** works. Shortest path VICTIM-01 → CASHOUT-A found the chain (4 hops); communities found a 9-node group
  (the ring); centrality put MERCHANT-BIG first (270). At laptop width the legend and Working Set boxes cover most
  of the canvas (F-11); centrality's next-step text says "start an Investigation", which Basic lacks (F-10).
- **Target flow:** a **Find something interesting** panel with three plain buttons: *Who is busiest* · *Which
  groups stick together* · *How does A reach B*. Each result row offers *Show on graph*, *Start from these*
  (UC-09), *Add to a list* (UC-10).
- **Help:** a one-line reading of each result in the pack's words ("Group 2: 9 accounts, money moves in and out
  within 3 days"); never a number without a sentence.
- **As built: done.** **Find something interesting** sits at the top of Toolbox → **Analysis**: **Who is busiest**,
  **Which groups stick together**, **How does A reach B**, each row with **Show on graph**, **Start from these**, **Add
  to a list**. F-10 fixed (no Investigation hint on Basic); F-11 fixed (legend and Working set start as pills below a
  1000 px canvas).

### UC-13 Keep and share what was found

- **Actor:** any; Investigations are **Standard+**. **Goal:** G5.
- **Today:** saved views, image and data export, Close; Investigations and Dossiers on Standard+.
- **Target flow:** **Save as a case** names the view (it appears under **My work** with its lists) and states that
  a view re-reads live data and is not evidence; **Export** offers picture, table of nodes and links, and a one-page
  summary. With the Investigations LA level **Start an Investigation from this** records the steps from here; with
  the Explorer level (Basic, or an enterprise install without the Investigations add-on, A-14) that action is shown locked with one line on
  what an Investigation adds.
- **As built: done.** **Save as a case** (the bookmark in the top bar, the former *Save view*) asks a **Name**, names
  the lists the graph uses and says a case is not evidence; the case appears under **My work**. **Start an
  Investigation from this** is locked on the Explorer level with one line. Not verified on 2026-10-24: a one-page
  summary in **Export**.

### UC-14 Come back later

- **Actor:** any. **Goal:** pick up where they left off, with newer data if any.
- **Today:** the open graph survives navigation and settings (fixed 2026-10-22); saved views reopen; re-import has no
  UI on Basic.
- **Target flow:** home shows **My work** (cases: views with their lists, most recent first) and **What changed in
  your data** ("transfers: re-imported 1 Oct, +2,104 rows"). On Basic the Dataset card has **Add a file** and **Run
  now** side by side (DT-5, manual-only; the run also builds the Dataset's link index and evaluates its Expectations, DT-16); a re-import of a one-time Dataset replaces it and says so.
- **As built: done, with one gap.** The home page shows **My work**: **Cases** (most recent first, with their lists)
  and **What changed in your data** (*transfers: imported …, +N rows*; a re-import reads *N rows loaded*). The
  Dataset card has **Add a file** (events: replace or add rows; details: replace) and **Run now**. Gap: *What changed*
  cannot tell a replace from an append or give the net change (`LA-ONBOARD-RESIDUALS-1` item 7).

### UC-15 Get help when stuck

- **Actor:** any. **Goal:** never a dead end.
- **Today:** first-time hints in Grow, held tray and exclusions; tooltips; "why" texts on disabled controls in the
  new panels. Elsewhere empty states often say nothing (F-1, F-2).
- **Rules for every screen:**
  1. Every empty state names the reason and offers the one next action.
  2. Every edition limit is a locked control with one line naming the edition (H4), never a control that only fails.
  3. Product terms appear with a plain explanation on first use (Dataset = "a table Inspecto keeps"; Entity Type =
     "a kind of thing, like an account").
  4. The Space guide (H1) and a **What can I do next?** button are always one click away.
  5. Help on Basic is deterministic (H1-H5, H7-H9). The assistant (H6, e.g. *Derive mapping*) is Standard+ where
     configured and shown locked on Basic (§8 A-9).
  6. Beginner progress is counted locally on every installation (§8 A-10): time from launch to first graph, Space
     guide completion, the step where people stop. Nothing is sent anywhere.
- **As built: done.** **What can I do next?** (the light bulb in the left menu) on every la-app screen; a **?** beside
  a product term explains it; locked controls via `inspecto-locked-action` with the reason; onboarding counters in
  `onboarding-counters.tsv` (`GET /onboarding/metrics`, `POST /onboarding/events`), shown in **Settings → Getting
  started counters**. "First launch" is the first server start, not the first time a person opened the app.

---

## 5. Which data path for which data (shown to the user)

| The user has… | Path | Edition | Becomes |
|---|---|---|---|
| A file of events (transfers, calls) | UC-03 **Add data** | Basic | a one-time Dataset (Add a file + Run now to add more) |
| A file that describes entities (customers, devices) | UC-04 **Add details** | Basic | a Reference Dataset shown on nodes (re-import replaces) |
| Data a colleague already loads | UC-05 pick a Dataset | any | (existing) |
| Files that keep arriving | UC-06 **Keep this up to date** | Standard+ | a Pipeline-built Dataset |
| A database or a remote system (SFTP, S3, Kafka) | UC-06 **Open in editor** | Standard+ (DT-4) | a Pipeline-built Dataset |
| Several files or systems to join, check or clean | UC-06 **Open in editor** | Standard+ | Pipelines with enrichment |

## 6. Concepts a beginner meets, and when

| Concept | Needed for LA? | First met at | Shown as |
|---|---|---|---|
| Space | yes | UC-02 | "a place for one kind of data" |
| Dataset | yes | UC-03 | "a table Inspecto keeps" |
| Entity Type / Entity | yes | UC-03 step 3, UC-07 | "a kind of thing / one of them" |
| Link (from, to, kind) | yes | UC-03 step 3, UC-08 | arrows in a preview |
| Entity List | yes | UC-10 | "a list of entities you keep" |
| Saved view / case | yes | UC-13 | "a graph you can open again" |
| Reference | yes | UC-04 | "details about entities" |
| Collector, Pipeline, schema, parse settings, sink, run, trigger | **no** | Standard+ hand-off only | "kept up to date by…" |

The 2026-10-22 setup needed about 6 Pipeline concepts and 8 API calls to reach the 2 concepts Link Analysis uses (a
Dataset, and which columns are from, to and which Entity Type). The target is zero Pipeline concepts on Basic.

## 7. Gaps found in the 2026-10-22 walkthrough

All thirteen were addressed by `LA-ONBOARD-1` and `LA-ONBOARD-FIXES-1` (F-6, F-10, F-11, F-12 in phase 0; F-1, F-2 in
phase 3; F-3, F-4 by the `/onboard/files` route and the Add data screen; F-5 in phase 5; F-7, F-8 in phase 6; F-9 by
`defaultLinkMapping`). F-13 is listed in the plan's phase 0 (unknown `/api/…` paths) and was not re-checked here.

| # | Finding | Use case |
|---|---|---|
| F-1 | Link Analysis shows a full query form with no Space and no data; no reason, no next step. | UC-01, UC-15 |
| F-2 | With zero Spaces there is no Space switcher and no "New Space"; a Space can only be made through the API. | UC-02 |
| F-3 | Basic has no way to bring data in: the upload is inside Investigations (503 on Basic) and only drops files into a Collector inbox. | UC-03 |
| F-4 | Making a Dataset took 9 API steps; `/config/suggest/schema` proposes column selectors by name, which registration then refuses ("must be a zero-based column index"), and fixing them trips the schema compatibility gate. | UC-03 |
| F-5 | No screen sets a column's classification (which Entity Type a column holds), the one setting LA depends on. | UC-07 |
| F-6 | The Entities page lists all nine pack Entity Types, all "Not assessed"; "Masked: Yes" next to "Masking mode none". | UC-07 |
| F-7 | The only single-entity start is "Phone number"; it refuses an account value. | UC-09 |
| F-8 | "Investigate" on an account's profile (Basic) draws the whole Dataset instead of that account. | UC-09 |
| F-9 | Entity profile links stay "not assessed" until a saved view or index mapping exists over the Dataset. | UC-08 |
| F-10 | Centrality's next-step text tells a Basic user to start an Investigation. | UC-12, UC-13 |
| F-11 | At laptop width the legend and Working Set boxes cover most of the canvas. | UC-12 |
| F-12 | Enter in "Open an Entity" does nothing; only the Open button works. | UC-07 |
| F-13 | `GET /datasets` answers 200 with the app's HTML instead of 404 (the list is `/components/dataset`). | UC-05 |

## 8. Operator answers (2026-10-22)

| # | Question | Answer |
|---|---|---|
| A-1 | One Space per case, or per data domain? | **Per data domain, on every edition.** Cases live inside a Space. |
| A-1b | What holds one case on Basic (no Investigations)? | **A named saved view plus the Entity Lists it uses**, grouped under *My work*. |
| A-2 | Formats and limits for "Add data" on Basic? | **CSV / TSV, Parquet, JSON / NDJSON**; Excel when the bundle carries the DuckDB `excel` extension (superseded by DATA-TIERS-1 DT-8, which owns edition rules); DT-8 limits (500 MB, 10 M rows, 20 imports per Space), configurable. |
| A-5 | May Basic show Reference details on nodes and filter by them? | **Yes: display and filter.** Joins that build new Datasets stay Standard+. |
| A-6 | Does Inspecto LA show Pipelines? | **Hidden on Basic; hand-off on Standard+.** Basic's "Add data" creates and runs the Pipeline behind the scenes (the Basic user is their own administrator and the Basic bundle has no console). Standard+: *Open in editor* in the console, or *Ask an administrator* without the right. |
| A-7 | Where is the primary entity set? | **Per Dataset**: proposed in "Add data", changeable on the Dataset's card. |
| A-7b | What does "Start from one entity" search? | **The user picks the Entity Type first**, then the value. |
| A-8 | When is the sample Space offered? | **First launch and New from sample in the Space switcher**, to anyone who may create Spaces; one button loads and runs it. |
| A-9 | Smart help on Basic? | **Deterministic only.** Assistant is Standard+ where configured, locked on Basic. |
| A-10 | Measure beginners? | **Always on, local**: counted on the installation on every edition, never sent anywhere. |
| A-11 | Locked or hidden for what an edition lacks? | **Locked with a reason inside screens; the navigation stays lean** (whole Standard+ pages such as Alerts stay out of the Basic nav). |
| A-12 | Postgres snapshot on Basic (DT-4) vs remote adapters Standard+ (now DT-4)? | **Files only on Basic** (the remote-adapter rule wins; DATA-TIERS-1 DT-4); a database is shown locked. |
| A-13 | Import checks only on Basic (DATA-TIERS-1) vs Expectations, quarantine, dedupe on Basic (now DT-15)? | **Keep them on Basic** (DATA-TIERS-1 DT-15). |

| A-14 | Can an enterprise (sign-in) install run Link Analysis without Investigations? | **Yes: Investigations becomes a billable add-on enforced at runtime, included by default on Standard and above**; the edition stays the only governance switch (reconciled with DATA-TIERS-1 DT-11, operator-confirmed 2026-10-22). Basic stays Explorer with no sign-in. Today the `investigations` feature follows the edition, so this is a change. |
| A-15 | May analysts import their own files in a sign-in Space? | **An administrator decides per Space**, off by default; when on, only people with `canUploadData`. Always on with no sign-in. |
| A-16 | What decides the masking default? | **Whether people sign in**, not the edition name: no sign-in → off, sign-in → typed (follows from §1.1). Today `EditionMaskingDefault` keys on the Basic edition, which gives the same result only because Basic is the edition without sign-in. |

A-12 and A-13 reverse parts of DATA-TIERS-1; that plan records the change (its DT-4 and the data-quality row).

## 9. Reconciliation with the console first-run use cases and `ONBOARDING-FIRST-RUN-1`

**Division of work.** The console use cases own the path from a file to a queryable Dataset (parse, schema, run,
failures) and the shared help catalogue. This doc owns what Link Analysis adds: the Entity Type and link step, the
primary entity, the focus, lists, the graph, and the Basic `la-app`-only install.

| This doc | Console use case | Relation |
|---|---|---|
| UC-01 First launch | UC-1 Sample data; H1 Space guide; H7; H8 | Same guide and sample button; LA adds the `la-showcase` sample and *Open my team's data*. First-run detection on "no Dataset yet" in both. |
| UC-02 Create a Space | §2 step 2 | LA adds the pack choice; per-domain Spaces (A-1). |
| UC-03 Add data | UC-2 file first (core); UC-9 start from my app; H2, H3, H8 | **Same path**: LA embeds console UC-2 and adds step 3 (Entity Types and links, H9). File first, pre-applied detection with low confidence flagged, one confirm: as answered for the console. |
| UC-03 JSON | UC-3 JSON or nested | Same; flattening is Basic, exploding arrays follows DT-7. |
| UC-04 Reference | UC-4 lookup table | Console left "does Basic onboard References" open; **A-5 answers it: yes**, and LA shows details on nodes; in-Pipeline enrichment stays Standard+ (locked). |
| UC-14 Come back | UC-5 next file arrives | Same: **Add a file** and **Run now** together on Basic (DT-5, DT-14). |
| UC-06 Keep data arriving | UC-5 Standard path; UC-6 remote system | Same; LA never edits Pipelines (A-6), it hands off. |
| UC-03 step 5 | UC-7 failed run / quarantined rows | Same cause-and-fix messages (H3); DT-15 keeps Expectations on Basic (A-13). |
| UC-05, UC-03 step 6 | UC-8 feed a downstream app | Console's *Explore as a network in Link Analysis* offer lands on UC-09 with the Dataset picked. |
| UC-15 rules | H4 locked with reason; H6 assistant | Same rule, with A-11's lean-nav exception for whole pages; deterministic help on Basic. |
| UC-07 to UC-13 | - | LA only. |

**Terms aligned.** "guide" (never "wizard"); "Space guide" (H1) replaces this doc's earlier "setup checklist";
"Onboard" stays the console verb, "Add data" is its short form inside Inspecto LA.

**Settled with the console session (2026-10-22).** `DATA-TIERS-1` owns the edition rules of the data layer;
`ONBOARDING-FIRST-RUN-1` is the console companion of `LA-ONBOARD-1`, which builds the shared pieces (the
`/onboard/files` route, sample loading, `inspecto-locked-action`, onboarding counters, the walkthrough fixes) and
the one Add data component both apps use (OF-1, with the OF-2 confidence rule). Exploding nested arrays follows
DT-7.

## References

- Walkthrough commits: `b1102e267` (open/close), `5dd7f4206`, `1b4f96721`, `fc9e0a9c3`, `559ae5d2e`, `cb4feedd9`
  (`LA-GROW-EXCLUDE-1`).
- [DATA-TIERS-1](../../../../superpower/data-tier-editions-plan.md) decisions DT-1…DT-10 (DT-4 and the data-quality
  placement revised by A-12, A-13).
- [Entity List spec](../entity-list/spec.md) (purposes, category lists).
- [GLOSSARY](../../../../GLOSSARY.md): Stream, Reference, Dataset, Entity Type, Collector, Pipeline, Onboard.
