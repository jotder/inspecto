# Inspecto LA (Link Analysis) — User Manual

*For analysts: fraud, revenue assurance, money-laundering and network investigators. Written 2026-09-30 against
the shipped product; every control named here is a label you will see on screen. The developer-level mechanism
is in [`okf/product/module/la-core/user-guide-link-analysis.md`](user-guide-link-analysis.md).*

New to Link Analysis? Start with the shorter [end user guide](user-guide-end-user.md); this manual is the reference.

---

## 1. What Link Analysis is

Link Analysis turns **rows into a picture of who is connected to whom**. You point it at a **Dataset** (for
example money transfers, or telecom call records), say which columns are the two ends of a relationship, and it
draws every account, subscriber or device as a **node** and every transfer or call as a **link**. You then
explore the picture, run analysis tools that point at suspicious structure, and — when something matters — open
an **Investigation** that records every step you take so the result can be replayed, reviewed and handed over as
evidence.

It answers questions such as:

* Who sits in the middle of this money chain, and does it look like layering?
* Which accounts receive many small payments just under a reporting limit (**structuring**)?
* Which agent till takes most of the cash-out, and from how many different payers?
* Which subscribers share a device or partner and might be one person (**split identity**)?

**Where:** *Studio → Link Analysis* (`/studio/link-analysis`), or the standalone **Inspecto LA** application
(§2, *The standalone application*).
**Editions:** Professional and Enterprise. In Personal the page says it is not installed.
**You need:** to be signed in. Reading and exploring need nothing special; changing things needs the
capabilities listed in §11.

### Two modes — know which one you are in

| Mode | What it is | Use it for | Is it evidence? |
|---|---|---|---|
| **Exploration** | The query panel + canvas + toolbox. Every query re-reads live data. | Looking around, trying ideas, finding leads. | **No.** A saved *view* re-projects live data; reopening it later may differ. |
| **Investigation** | A recorded, ordered list of steps (the **op log**) over a **Working Set** of entities. | A case you may have to defend: replay it, fork it, hand it over. | **Yes**, once you issue a **Dossier** (§9.9). |

The screen says *"Not evidence — saved views re-project live data"* on the saved-views menu for this reason.

---

## 2. Quick start (five minutes)

**Fastest start: Start from...** (LA-ONBOARD-1 phase 6, OB-9). The first screen is one box. Pick the **Dataset**
(preselected from `?dataset=<id>`, which Add data's *Explore in Link Analysis* passes), then start from:

| Start | What you give | What it draws (Explorer level) |
|---|---|---|
| **One entity** | the **Entity Type first** (the Dataset's `primaryEntityType` preselected), then the value, with a type-ahead of matches, busiest first, each with its link rows and first / last seen | that entity plus one ring |
| **A list** | an Entity List | its members (at most 25) plus one ring |
| **A paste** | values, one per line (commas, semicolons and tabs also separate) | the values found plus one ring; values not in the Dataset are reported |
| **The whole Dataset** | nothing more | every link (the old Explore draw), warned first when the Dataset holds more Entities than the projection node cap |

Which Datasets can start a graph, and over which columns: the Dataset's own `defaultLinkMapping` (OB-8); else a Domain
Profile's `investigate` mapping over it; else the first saved view over it; else its two classified link-end columns.
An Entity Type can start a graph when a link end of such a Dataset holds it (`GET /inv/entity-types` binds it `source` /
`target`), or when it is the Dataset's primary type. The type-ahead is `POST /inv/entities/{type}/suggest` (the prefix in the
body; a masked type suggests nothing and is only counted as a whole).

A start draws through the **neighbours read** (`POST /inv/projection/neighbors`, one per value), never the whole-Dataset
projection, holds the busy neighbours above the Space's hub threshold (the Grow preview's own `held`), and marks the starting
entities as grown, so **Grow** continues from the ring. A line above the graph says what was drawn, how many were held and
which values were not found. On the **Investigations** level, **One entity** seeds an Investigation as before: a Domain
Profile that investigates the type binds its own mapping and presets (the override); any other type binds the start
Dataset's mapping with the server's default rung. A list or a paste is drawn and queued as the first seeds.

**Investigate** on an Entity's profile page (and the deep link `/studio/link-analysis?seed=<id>&entityType=<type>[&dataset=<datasetId>]`,
which an Incident or Case page offers as **Investigate … in Link Analysis** when a keyed Alert Rule recorded the entity) takes
the same path: on the Explorer level it draws that entity and one ring over the named Dataset, or over the first Dataset that
links its type. Before phase 6 the Explorer level stopped at "Investigations are available in Standard and above", dropped the
seed and offered *Choose a Dataset*, whose pick drew the whole Dataset (F-8, 312 nodes in the walkthrough). The Telecom profile
still binds `telecom_links_dataset` (`a_msisdn` → `b_msisdn`, link kind `link_kind`, time `last_seen`) for a phone number on
the Investigations level: the Investigation tab asks only the purpose (the title is pre-filled *Suspect &lt;id&gt;*);
**Investigate** creates the Investigation, seeds the number and expands one degree. **Expand next degree** goes one hop further,
up to degree 4, with the profile's presets (the 30 days up to the Dataset's latest event — "all available data" when that
cannot be read — at least 1 event per link, at most 500 links per entity, 10,000 rows per degree); each degree's result, any
budget cut or fan-out cap, and a degree held for four-eyes approval ("waiting for approval") is stated under the button.

Under the box: **Follow the money (example)** opens a ready-made saved view and draws it (shown only when your Space has
one), **Open a saved view**, **Explore a Dataset** (choose the columns yourself — the Datasets that look like links are listed
first) and **Add data** (where the application has it). The steps below are the same thing done by hand. The legend's
link-kind chips hide or show each kind of link on the canvas.

1. Open *Studio → Link Analysis*. Choose a **Domain profile** (for example *Financial crime — transactions* or
   *Telecom — call detail records*): it pre-selects the most useful tools.
2. In the **Query** panel pick the graph type **Entity/Link (from a Dataset)**, then the **Dataset**, the
   **Source entity column** and **Target entity column**. Optionally pick a **Link type column** (the <!-- vocab-allow: exact on-screen label -->
   channel, call type, …) and attribute columns (amount, time).
3. Run the query. The graph is drawn; the footer states *"193 nodes · 2000 links drawn"* and the render caps.
4. Try the **Toolbox → Analysis** groups: *Communities*, *Centrality*, *Suspicion score*, or *Pattern match* →
   **Structuring (smurfing)**. Matching nodes are highlighted.
5. Save what you found: **Save the current view** (keep the setup) or **Save this analysis** (seal a snapshot
   you can attach to a Case).

**Shortcut:** open **Open saved views** and load a ready-made one — the `la-showcase` Space Template ships
`mule_layering_ring`, `mule_structuring`, `mule_large_transfers` and `telecom_fraud_network`; the `demo` Space ships
`roaming_imsi_footprint` and others.

### The standalone application

Link Analysis also runs as its own application, **Inspecto LA**, without the rest of the studio. After sign-in its front page offers
three areas — **Link Analysis**, **Geo** and **Entity Lists** — and the same three sit in the slim menu down the
left edge (with the Space switcher and your user menu). A user who may change Link Analysis settings also sees
**Settings** there.

* **Sign-in.** A secured installation shows *Sign in with SSO*. A demonstration installation instead shows
  *Inspecto LA demonstration*, a warning *Demo sign-in — Not secure, local only*, and one button per **Demo User**
  (name and title). Pick one; there is no password. The `la-showcase` Space Template defines five: *Demo Admin*
  (also holds the settings role), *Demo FM Analyst* (fraud analyst, lead), *Demo FM Manager* (fraud manager, the
  second approver for four-eyes), *Demo RA Analyst* and *Demo Manager* (both view-only, §11).
* **When sign-in cannot start**, the page says so instead of showing a picker — see §13.

### Add data — Onboard a file

**Add data** (`/add-data` in Inspecto LA) is the one screen both applications use to turn a file into a Dataset
(`LA-ONBOARD-1` phase 4; the shared component `inspecto-add-data`). It calls the Add data routes
(`POST /onboard/files`, then `POST /onboard/files/{draftId}/commit`; [`api-onboarding-authoring.md`](../processor/api-onboarding-authoring.md)
"Add data"). On one screen:

1. **Drop or choose a file**, after saying whether it holds *Events between entities* (a Stream) or *Details about
   entities* (a Reference). The formats and the Space's `import.*` limits are shown before upload; Excel is read only
   when the bundle carries the DuckDB `excel` extension (known from the first draft, or from a refusal that says to
   save as CSV). *Read from a database or a remote system* is shown locked on Basic (`/bootstrap` `remoteSystems`;
   until the server names it, locked on the Basic edition with a built-in reason).
2. **Check the columns.** Every choice of the draft is pre-applied with its evidence. A `low` choice (fewer than 50
   sampled values, or values that disagree) is highlighted, marked *A guess*, listed above **Create and run**, and
   holds it back until confirmed or changed. Rename, change a type, **Leave out** a column; a table previews the first
   rows as kept. A different separator, header line or quote re-reads the file with that choice stated (the commit
   cannot change the format).
3. **What does each column hold?** An Entity Type per column (`/inv/entity-types`), the link's **From** and **To**
   and optional kind, time and amount (Stream), the identifying column (Reference), and the main kind of thing (the
   Dataset's primary Entity Type). In the console the step is optional and starts closed: there, a high-confidence
   proposal is applied as detected and a low one is not applied unless the step is opened.
4. **Create and run** commits once. A file above `import.inline_max_bytes` answers 202 and the screen follows
   `GET /onboard/runs/{runId}` until it ends.

**The result in plain words** reads the run summary: rows read and kept, rows set aside (rejected, or a file set
aside with its reason), a replace with the row counts before and after, the link index (built with its links and
entities, up to date, skipped, or not built with the reason) and each Expectation. **Explore in Link Analysis**
opens `/link-analysis?dataset=<id>`: Link Analysis preselects that Dataset in **Start from...** (section 2) when it
has a link; a Dataset without one is picked in the Query panel, as **Explore a Dataset** does, and drawn when its from
and to columns can be suggested. **Add details about these entities** restarts
for a Reference; **Add another file** restarts.

**The Dataset card** of an imported Dataset (`origin: import`) shows *one-time import*, the last import, and **Add a
file** beside **Run now** (`POST /datasets/{id}/files?mode=replace|append`, `POST /datasets/{id}/run`; a Reference
is always replaced). **Keep this up to date** is locked on Basic (`/bootstrap` `pipelineAutomation`); on Standard and
above the console's card offers **Open in editor** (`/pipelines?guided=1&open=<pipeline>`) to someone who may author,
and Inspecto LA says *Ask an administrator to keep this up to date*. The Add data page lists a card per import of the
Space under the screen.

**When it is locked.** With sign-in the screen needs `canUploadData`, and the Space's `data.self_import` must be on
(`GET /settings/data`; off by default with sign-in, on by default with none). When either is missing, or the server
refuses an upload with 403, the whole screen is the reason (`inspecto-locked-action`) instead of an upload that
fails. A 503 (the Space has no data directory) is explained in place.

---

## 3. The screen

```
┌ title row: Link Analysis · About · Hide the side panels ───────────────────────────────┐
│ TOOLS │ QUERY dock (left)    │  CANVAS (the graph)        │ TOOLBOX dock (right)      │
│ rail  │  graph type, Dataset,│  pan · zoom · drag · click │  Analysis | View |        │
│ (left │  columns, filter     │  legend + Working-set      │  Investigation            │
│ edge) │                      │  overlays                  │                           │
├───────┴──────────────────────┴────────────────────────────┴───────────────────────────┤
│ footer: nodes · links drawn · caps · Data strip (the rows behind the graph)            │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

* **Tool rail** (far left, full canvas height): search, filter, layout, save this analysis, Attach to Case, the
  algorithms and Investigation shortcuts, saved views, display options, Undo / Redo, Fit, Show as list, Full screen
  and one **Export** menu (PNG, JSON, SVG, GraphML). The rail scrolls when the window is short; on a narrow screen it
  wraps back into a row above the stacked panels. The page does not scroll on a normal window: the Link Analysis page is as tall as the window below the application header (it hides the page footer, which every other page keeps), the active-query chips sit in the title row, and the panels scroll inside themselves (measured 1440x900: canvas 506 x 708 px with both panels open; 1280x720: 652 x 528 with the Toolbox collapsed; 1000x700: 372 x 508). The canvas never drops below about 420 px high: in a shorter window the page scrolls instead (1280x500: canvas 637 x 432). In the standalone Link Analysis app the areas (Link Analysis, Geo, Entity Lists, Alerts; Basic has no Geo or Alerts), the Space switcher and your user menu sit in a slim menu down the left edge, so the page gets the whole window height; the arrows at its foot expand it to show labels, and the choice is remembered. On a narrow screen that menu becomes a slim bar across the top. The Query panel starts 220 px wide (it stays resizable, 200 to 560) and below 1300 px of window width the Toolbox starts collapsed to its icon rail; open it from the rail when you need it. Your explicit open or close is remembered for the rest of the browser session. On a narrow screen (under 960 px) the panels stack and the page scrolls.
* **Docks** are resizable (drag or arrow keys) and collapse to a rail (*Hide the query panel*, *Hide the
  toolbox*); **Hide the side panels** gives the canvas the whole width.
* **Canvas:** drag to pan, scroll to zoom, drag a node to move it, click a node for its details, click a branch
  to collapse it. **Fit the graph to the screen** re-frames everything.
* **Data strip** (bottom): the rows behind the graph as a table, with a SQL editor for questions the graph cannot
  ask. *Project result as graph* turns a query's rows back into a graph.
* **Undo / Redo** step through display and filter changes.
* **Close** (the **×** at the right end of the title row, and the last item of **Open saved views**) closes what is
  open and goes back to the empty start page. Its label names what that is: *Close Investigation Mule hub*, *Close
  Draft d-7 of Investigation Mule hub*, *Close unsaved view mule_ring* (an imported view not saved yet) or *Close the
  analysis* (a drawn graph). It shows only while something is open. Closing an Investigation also takes
  `?investigation=` out of the address bar. Nothing is deleted: reopen an Investigation from the Investigation tab
  (**List Investigations**) and a view from **Open saved views**. An imported view you have not saved is asked about
  first, because Close would lose it.
* **What you have open stays open while you work elsewhere in the Space.** Go to Settings, Entities, Entity Lists or
  Alerts and come back, by the menu or by Back: the graph as you left it, its selection, search and filters, the
  panels and the open Investigation are all still there. Saving a setting, a *My browser* limit for example, closes
  nothing; the new value applies to what you draw next. A reload reopens only the open Investigation (its id is in the
  address bar); a drawn query graph does not survive a reload. **Switching Space closes everything that belongs to the
  old Space**, because the app reloads in the new one. The next time you open Link Analysis it says what was closed
  and the Space where it stays.

---

## 4. Building a graph

### 4.1 Choose what to draw

| Graph type | What you get |
|---|---|
| **Entity/Link (from a Dataset)** | Records of one Dataset become entities and links. **This is the one analysts use.** |
| **Entity/Link (several Datasets)** | Several Datasets in one graph (up to 16 mappings). The same entity appearing in more than one Dataset becomes **one node** that lists every Dataset it came from. If you cannot view even one of the Datasets, the whole query is refused — you never see a partial graph. |
| **Lineage (data assets)**, component model, pipeline | Platform graphs: where data comes from and what depends on what. Not for investigations. |

### 4.2 Map the columns

* **Source entity column → Target entity column** define a link (who → whom). <!-- vocab-allow: exact on-screen label -->
  When you pick a Dataset these are **filled in for you** if two columns look like the two ends of a link (payer /
  payee, sender / receiver, caller / callee, src / dst, from / to, …); a note says so and you can change either one.
  The Dataset picker lists such Datasets first and says why under each name. **Derive mapping** is a different,
  optional helper: it asks the assistant to draft the columns and needs permission to author configuration, so it
  stays greyed out for an analyst — the filled-in columns work without it.
* **Link type column** (optional) gives each link a kind (wire, card, cash_out, SMS, …). Kinds get colours and can be
  filtered.
* **Attribute columns** (optional) carry amounts, times or anything else onto the links; they feed the timeline,
  the value Measures and the pattern tools.
* **Add another mapping** stacks several column pairs into one graph.
* **Roots** and **depth** start from chosen entities and grow outwards; **direction** and **link-kind filters**
  narrow what is followed.

### 4.3 Filters — two stages, deliberately

1. **Apply locally** — instant, in your browser, over the links already loaded.
2. **Push to server** — the same condition is sent to the database, so it applies *before* the graph is folded and
   counts stay right on large data.

Conditions are a tree of AND/OR groups. A single bare condition is refused — wrap it in a group (*Group → add
condition*).

> **Mind the "≥ 5 000" trap.** A view filtered to "amount ≥ 5 000" hides exactly the small payments that
> structuring consists of. Pattern and Measure tools run over the **whole Dataset**, not over your filtered view,
> for this reason.

### 4.4 Size limits — and being told honestly

* By default the server returns at most **4 000 links** (ceiling 40 000), the canvas admits **1 000 nodes**, and
  analysis in the browser runs up to **2 500 nodes** (the heavier scores stop sooner and say so). The footer prints
  the caps in force. Every one of these numbers is a setting: your Space administrator sets the Space's values,
  and you can lower or raise the browser ones for your own machine (see *Settings → Link Analysis → My browser*,
  section 12).
* When a cap cuts data you see **Truncated** — never a silent "no results". Narrow the query, or use a server-side
  tool (*Find paths (server)*, *Run on server* for patterns, the Measures panel). The Analysis tab's
  **All algorithms (server)** group lists every algorithm the server offers (29), builds its form from the
  server's parameter list, and runs it on the open Investigation's Working Set whatever its size; the answer is
  summarised in words with *Highlight on canvas*. **Propagated risk** has its own inputs there: *weights* (per hop,
  prefilled with the Space default), *seeds* (add canvas nodes; empty = every scored node) and *nodeScores* (**Fill
  from indicators** reads the profile's indicators Dataset); its answer is a ranked table (score, raw, own,
  contributors) whose *Factors* expand to the contributing origins. The footer's **Installed modules** lists the Link Analysis modules
  and whether each is active, inert (with the reason), switched off in this Space, or not installed.
* Each server read says where it was answered: *Answered from the link index v3* (with a stale note when rows were
  added after the build) or *Answered from the Dataset because …*, with the reason in words — the index is switched
  off for this Space, the Dataset has no index yet, the index was built over different columns, the index cannot apply
  this filter or time zone, the walk is deeper than the index serves, or **the index is older than the Space allows and
  the Dataset's input files cannot be checked for changes** (reason `stale_unknown`; see *index max age* in §11). The same
  sentence appears on a sealed expand step. An unrecognised reason is shown as sent, never hidden.
* Very busy hub nodes are folded into a **super-node** so they do not hide everything else.
* Above 20 links on the canvas, link labels are hidden (shown on hover or click) to keep the picture readable; *All link labels* in the Display menu overrides it.

### 4.5 Entities: what the columns hold

Two columns join up in the graph and share one profile only when they hold the same **Entity Type**: a column holds a
type when its classification is one the type claims. The **Entities** page (left rail) is where you see and set this
(LA-ONBOARD-1 phase 5).

* **Entity Types** tab — one card per Entity Type that **occurs in this Space's data** (some Dataset column holds it),
  with its distinct count, Datasets and data-as-of date. Types a Dataset names as its **primary** Entity Type come
  first and carry *Primary in &lt;Dataset&gt;*. Types no column holds sit collapsed under **Not in your data (n)**;
  open it to see them. A type a Dataset names as primary but no column holds stays there: nothing would be drawn.
* **Columns** tab (also `?tab=columns`, where the Space guide's *What the columns hold* step lands) — per Dataset,
  each column that looks like an identifier: one already classified, a link end of the Dataset's default link
  mapping (*Links start here* / *Links end here*), or a name that suggests an Entity Type (*Looks like Account* for
  `from_account`) or ends in `id`. **Show every column** lists the rest. **Holds** sets the column's Entity Type at
  once, or *Not set* clears it; the change is written to the Dataset and the page reads the Entity Types again and
  says the result (*"to_account" in transfers now holds Account. Account is in 2 columns of 1 Dataset.*). A column
  classified with something no Entity Type claims says so in its note. The picker beside the Dataset name sets the
  Dataset's **primary Entity Type** (the type its graph leads with); *None* removes it. A Dataset that lists no
  columns says so; its columns cannot be set here.
* **New Entity Type** (top right) — for a kind of thing your pack does not know: a **Name**, an **Example value**
  (not stored: it shows *Kept as …*, how a value is compared) and **Compare values as**: *Upper case, spaces
  trimmed*, *Digits only*, *Phone number* or *Case ignored*. Its id is the name in lower case with `_` between words,
  and it claims the classification of the id in upper case (*Vehicle plate* → `vehicle_plate`, `VEHICLE_PLATE`). It
  is added to the Space's Link Analysis settings: the first one turns the inherited types into a stated list (the
  types in force plus the new one), so later pack relabels no longer apply to it. A new type is not masked; a name
  the Space already has, or a classification another type claims, is refused before anything is written. It shows
  under *Not in your data* until a column holds it.

All three changes need `canAuthorWorkbench` with sign-in (everyone may without sign-in). Without it the values read
as text and the action is shown locked with the reason (*Changing what a column holds needs permission to edit
Datasets in this Space*).

---

## 5. Exploring the graph

### 5.1 View tools (Toolbox → **View**)

* **Layout** — how nodes are arranged: *Layered, Grid, Force, Clustering force, Radial, Degree ordered, Circular,
  Information density, Fruchterman,* and tree layouts (*Mind map, Organization chart, …*) that are enabled only when
  the data is tree-shaped.
* **Overlays** — *Legend*, *Working set* (top-N counts), *Node labels*, *Link labels*, *Level of detail*, *Minimap*,
  *Grid*. On a canvas narrower than 1000 px (a laptop) the legend and Working set start as small pills you can
  expand; once you open or minimise one, this browser remembers your choice.
* **Lenses and selection** — a link-kind lens, hover / brush / lasso selection, fisheye, community hulls and
  edge bundling.
* **Display options** — labels, colour, shape, pattern and size per kind. Saved with the view.

### 5.2 Finding things

* **Search nodes** finds by label; **Clear search and filters** resets.
* **Node types** and link kinds can be switched on and off from the legend.
* **Timeline filter / Timeline cutoff** hides links after a chosen date in any date attribute.
* **Advanced search** opens a dialog with a SQL editor over the loaded rows (and *Run on server*); the result can be
  projected back as a graph.
* **Geo Map** — when entities carry a location and a shared key column, selecting an area on the Geo Map highlights
  the matching entities here, and selecting entities here highlights their points on the map.
* Click a node to see its details, **Explain node** (why is it interesting?), and **expand** it to pull in its
  neighbours one hop at a time.

#### The node detail dialog

Besides the node's rows (id, links, neighbours, Datasets, attributes) and its canvas actions, the dialog has three
sections. On an Investigation canvas, click a node once to select it, then click the selected node again to open
its detail dialog.

Risk and Enrichment look up only the entities they need (the node itself, or every drawn node for Compute risk and
*Fill nodeScores from indicators*), on the server (`POST /inv/reference-rows`, at most 500 keys a call), so they work
in a masked Space too: identifying values (the entity id, and any column classified MSISDN / IMSI / ACCOUNT / PII or as
a masked Entity Type; under masking mode `all`, every value except fractional numbers and yes/no flags) are shown as aliases and the section says *Entity
masking is on*, while numeric indicator scores stay readable. A lookup that matched more rows than one read returns is
flagged, and Compute risk is then held - an incomplete score map would understate risk.

* **Risk** — on a profile that maps an indicators Dataset (Telecom: `telecom_msisdn_indicators` by `msisdn`), the
  node's own **indicator score** and its factor columns. **Compute risk** runs `propagatedRisk` on the server over the
  Investigation's Working Set, seeded with every drawn node's indicator score, and then shows the node's propagated
  score (raw, own, contributors) and its top contributing origins, e.g. *+28 from 99979100001, 3 hops, weight
  0.35*. The answer is kept per Working Set, so other nodes show it without a new run. Without an open Investigation
  (or the *Run link graph analysis* capability) the button is held and says why. The weights are the Space default
  (`graph_run.propagated_risk_weights`, shipped 1.0 / 0.6 / 0.35 / 0.15).
* **Enrichment** — the node's CRM/KYC row when the profile maps one (Telecom: `crm_kyc` by `msisdn`; if the Space
  cannot read it, the section says so), and the Entity Lists of its Entity Type it is on.
* **Case history** — Standard and up only (the section is not shown on Basic). Collapsed; opening it reads the Incidents and Cases that reference the node's entity (Telecom:
  by `msisdn`), newest first: kind, title (a link to the Incident or Case), status, severity and date, and *via
  Incident …* for a Case found through an Incident it contains. *Show* filters all / open / closed. When the server
  searched only its newest objects the section warns that older ones may be missing. A masked node (an alias) is
  looked up on the server (`POST /inv/case-history`), which resolves the alias itself and never shows the number:
  titles show the alias instead; an alias the server no longer knows (it restarted) says so; an id too short or of another Entity Type, a busy server (*Too many lookups*) or any other
  failure is stated in the section, and reopening it tries again.
* **Actions** — **Add to watch list: …** / **Add to block list: …** (one button per watch or block list of the node's Entity Type that does
  not already hold it) asks for a reason first; a list under four-eyes answers *Waiting for approval* and nothing is
  written until a second person approves. The buttons need *Manage incidents*; without it they are disabled and the
  dialog says so. **Hide on canvas** (exploration only) takes the node and its links off the canvas; it changes no
  query and no Investigation.

### 5.3 Domain profiles

A profile (*Generic graph, Financial crime, Telecom, Supply chain, Cyber*) renames things in domain words and
**suggests** the tools that matter, marked with a badge. It changes emphasis only, never the data.

### 5.4 Grow and clean a graph

`LA-GROW-EXCLUDE-1` (phases 1–6, shipped 2026-10-22). Three controls on the graph, worded in the active pack's terms (Telecom:
*subscribers* and *calls*; Mobile Money: *wallets* and *transfers*). On an open Investigation's canvas every change is one
**recorded** step of the log (an existing op — `expand`, `exclude`, `excludeBy` or `undo` — so replay and the Dossier
are unchanged); on the explorer graph (Basic, or the query graph) it is **not recorded**. Every change ends with a
one-line result and **Undo** (also **Ctrl+Z**).

**Grow** (tool rail, or **G**) adds the next ring of linked items — the *expand* op. It grows from the selection, else
from the Investigation's outer ring (the explorer: the nodes not grown yet, the first 25).

* **Direction**: *Both ways* (default), *Outgoing* or *Incoming* — an icon and a word each.
* **How far**: *1 step* (default). *2 steps* is offered only when the preview says the first step stays under the
  frontier cap (`investigation.max_frontier`; the explorer: 25); otherwise the panel says why. Two steps are two
  recorded expands; Undo reverts both.
* **Hold busy numbers with more than N contacts** — on by default, N = the Space's hub threshold (`hub_threshold`,
  editable per Grow). Unticking it grows through the busy items too; that is the sealed *include hubs* override.
* **More options**: the link kinds to follow, and (an Investigation with a time column) the window — the
  Investigation's own, or all available data.
* **Preview before the change**: "This step adds about 342 numbers and 1,210 calls; 3 busy numbers will be held; 41
  numbers are removed by your exclusion lists", with the lists in force as chips and their counts. A step that would
  need four-eyes approval says so. The preview is a dry run (`POST /inv/investigations/{id}/expand/preview`; the
  explorer: ONE `POST /inv/projection/neighbors/preview` over every node it grows from, which counts only what is not
  on the graph yet and reads with the view's filter, so a graph the query drew whole previews 0). The explorer applies no
  exclusion list, so its preview names none; lists apply in an Investigation. A server without these routes shows
  *No preview on this server* and Grow still runs; the result line then says what it did. A refused preview (for
  example a 403) shows the server's reason in the panel.

**Held — busy numbers** (super nodes). Items above the threshold stay on the graph with a ring and a **held** badge
and are listed in the Held tray (bottom left) with their contact count. **Remove held** excludes all of them in one
recorded `exclude` step with the reason *busy number above N contacts*; **Expand anyway** grows through one item (the
recorded override). On the explorer, Remove held hides them locally. A first-time hint explains busy numbers.

**Exclude…** — right-click a node (or a selection), or press **X**:

* *From this Investigation only* — one `exclude` step with your reason (the explorer: *Hide from this graph*).
* *Add to a list…* — pick an analyst exclusion list of the items' Entity Type, or create one; the items are added to
  it (a list write, four-eyes where the list is governed) and the list is applied (`excludeBy`), so they stay out in
  every later step. A **category list** (kept by an administrator — VIPs, politicians, defence) is offered only to a
  holder of *Manage exclusion categories*; otherwise the dialog says how many category lists it left out. On the
  explorer the items are also hidden locally.

**Hidden by lists** (tool rail) — the exclusion panel: "41 numbers hidden by 3 lists"; each list in force as a chip
with who keeps it (*Category* / *Analyst*) and how many it removed; **Switch off** undoes a list's step (only the
latest step can be; on an older one the button says why) and **Switch on** applies a list that is
not in force. **Show what was removed** lists the excluded items with why and the step; a **restricted** list's
members are never listed to an analyst — its count is rounded (nearest 5, at least 5). With no list yet the panel
offers *Create your first list* and names the category lists the administrator set up. Without *Manage incidents*
every switch says why it is held.

**First-time hints.** The Grow panel, the Held tray and *Hidden by lists* each explain themselves once, in a few lines,
and name this chapter. **Got it** dismisses a hint for good in this browser (a private window shows it again).

Keyboard: **G** Grow, **X** Exclude…, **Ctrl+Z** undoes the last Grow or Exclude (then the display undo). Not in a text
field, a grid, a menu or a dialog. The `?` shortcuts help lists all three.

---

## 6. The analysis tools (Toolbox → **Analysis**)

One group opens at a time; results stay while you switch tabs. Everything runs in the browser over the graph you
loaded (default cap 2 500 nodes; each heavier score has its own lower cap, for example betweenness 500 and
suspicion score 1 000, and says so). Above an algorithm's cap the tool offers **Run on server** instead.

| Group | Answers |
|---|---|
| **Shortest path** | How are A and B connected? *Fewest hops*, or weighted by tie strength. |
| **All paths** | Every route between A and B up to a length. Parallel links between the same nodes count as ONE route, shown with how many links it stands for and their kinds. |
| **Find paths (server)** | The same across the **whole Dataset** in the database — for graphs too big to load. Shows depth searched, and warns if capped. One row per route (parallel links collapsed, as in All paths). |
| **Explain node** | A node's neighbourhood, degree and role in plain words. |
| **Centrality** | Who is most important: degree, betweenness (brokers), closeness, eigenvector, Katz, PageRank, HITS. |
| **Communities** | Natural clusters: label propagation or Louvain. |
| **Connected components** | Separate islands. |
| **Cycles** | Money or calls that return to where they started. Listed shortest first with the hop count; the selected cycle is the one drawn on the canvas (the shortest is drawn first). |
| **Cut points** | Nodes/links whose removal splits the network — single points of dependence. |
| **Cohesive groups** | k-core, triangles, cliques — tightly knit cells. |
| **Similarity & prediction** | Who looks alike; which links are likely missing. |
| **Flow & backbone** | Maximum throughput and minimum cut between two nodes; the spanning backbone. | <!-- vocab-allow: exact on-screen label -->
| **Suspicion score** | An explainable 0–100 composite with a per-node breakdown; the top decile is highlighted. |
| **Pattern match** | Find a shape (see §7). |

**What to do with a ranking.** In *Centrality*, *Suspicion score* and *Similarity & prediction*, click a row to
select that node and centre the canvas on it. Under the table, **Start an Investigation from the top results**
carries the top five nodes to the **Investigation** tab as *queued seed entities*; nothing is created. You fill in the
title and purpose and press **Start Investigation with 5 queued seeds** — only then is the Investigation created
and the queued entities seeded as its first step. You can remove one from the queue or clear it first. On Basic
there is no such action: the hint under the table says that recorded Investigations are in Standard and above.

---

## 7. Pattern matching

Pattern match opens on the first pack (its description is shown); *Custom motif* with no kinds set matches every link and says *Choose a pattern*. Pick a **pattern pack** to pre-fill the motif, edit the thresholds (they are always visible), run it.

| Pack | Looks for |
|---|---|
| **Layering chain** | Money passed through a run of intermediaries. |
| **Pass-through intermediary** | Accounts that receive and immediately forward. |
| **Inbound collector** | Many payers into one account. |
| **Call-forwarding relay** | Calls bounced through a relay. |
| **Circular flow** | Value that loops back. |
| **Shared associates** | Different entities sharing the same contacts. |
| **Layering network (split and merge)** | Money split across three or more parallel intermediaries that re-converge, each leg after the one before (the branching sibling of Layering chain). |
| **Circular financing (branching)** | Two successive split-and-merge rounds of value; confirm a true return to origin with Circular flow (Cycles). |
| **Structuring (smurfing)** | Many small deposits in a band (default 900 ≤ amount < 1 000) → a few intermediaries → re-converging. |

Patterns can require **time order** ("after the previous hop", "within N hours"), which needs a time column. If
the data cannot answer — no time column, or your filter removed every leg — the tool **says so and why** rather
than returning "none". To supply one, add the time column under **Attribute columns** in the Query panel, run the query again and pick it as the **Time column** under **Filter edges by time**; the starter-card graphs do not carry one. The message is raised at the top of the Toolbox and scrolls into view. When the loaded graph was truncated, **Run on server** repeats the search across the whole
Dataset.

A pack that pins link kinds needs a **link kind column**, and a pack with a window or an ordering needs a **time
column**. When yours are blank, the toolbox fills them from the domain profile. For telecom it uses `link_kind` and
`first_seen` and says *Using link_kind / first_seen for this pack*. If there is no column to fall back on, the run
button is disabled and the reason names what to set, for example *This pattern needs a time column — set Time to
first_seen*. The browser run can only use a time column the loaded links already carry. It cannot add a kind column
after the projection, so set **Link type** in the Query panel and re-run the query.

---

## 8. Saving, exporting and handing over

| Action | What it keeps | Notes |
|---|---|---|
| **Save the current view** | The setup: source, mapping, layout, profile, display options, filters | A **view** re-projects *live* data — not evidence. Loadable from **Open saved views**; has version history, comments and tags. |
| **Save this analysis** | A **sealed snapshot** of exactly what is on screen, with a SHA-256 fingerprint | Choose a Case to attach to, or **create a new Case from graph nodes** — tick the nodes that become the Case's first members (each becomes an Incident; the same entity reused later is reused, not duplicated). The button is disabled, with the reason, without *Incident management*. A masked entity cannot become a member (the server refuses a masked pseudonym as a Case key): on a Space that masks accounts the dialog says so, and you attach an Investigation to the Case or ask for Reveal rights instead. What is on screen means the Working Set while an Investigation is open. |
| **Attach to Case** | One step: **seals** what is on screen and attaches it to a Case — no separate save first | Pick an existing Case or **create a new Case from the selected nodes**. While an Investigation is open you may also **link the Investigation to the Case**. If a step after the seal fails, the dialog stays open and names the sealed snapshot id; **Retry** resumes from the failed step and never seals twice. A new Case that was created stays the target of the retry. If creating the Case got no definite answer (a timeout or server error), it **may have been created**: the dialog will not create another — check Cases, pick it under *An existing Case*, and retry. Needs the Incident management capability (`canManageIncidents`); without it the button is disabled and says why. |
| **Export** | PNG, SVG, JSON, GraphML | For reports and other tools. |
| **Share / Exchange** | A view can be shared with other Spaces | Comments travel with it. |
| **Widget** | A view or an Investigation's Working Set can be shown on a Dashboard (§9.11) | |

---

## 9. Investigations

Use an Investigation when you need an **auditable** trail. It is an ordered **op log** over a **Working Set**: a set
of entities you grow and prune deliberately. Every step is sealed with what it read, so it replays identically even
if the underlying data changes later.

### 9.1 Start one
Run a query with **one** Entity/Link mapping, open the **Investigation** tab and choose **Start Investigation**.
It binds that Dataset and its columns. (The query's filter is not carried into the Investigation; the panel says so.)
A Space may require a stated **purpose** for sensitive steps; when asked, it is recorded in the log.
Over a **pre-aggregated** Dataset (one row per pair with an event count, like `telecom_links`), bind its count column
as `eventsCol` when the Investigation is created: an expand then counts a link's events as the sum of that column, not
its rows (an empty count is one event). It must be a whole-number column. The Telecom profile's phone-number start (Start from... > One entity)
binds `events` for you. Without it, each row is one event, as before.
To have the entities an expand adds carry an **Entity Type** (so node actions such as *Add to watch list* find the
lists of that type), declare it when the Investigation is created: `entityType` for both columns, or `sourceType` /
`targetType` for each. Without it only the seed is typed. Under typed masking, a masked type also masks the entities
it is given to.

### 9.2 Steps you can take

| Op | Effect |
|---|---|
| **seed** | Put starting entities into the Working Set (by id, or from an Entity List). |
| **expand** | Follow links one hop. Options (the *hop ladder*): direction, link kinds, time **window**, minimum events / distinct days, candidate degree bounds, fan-out cap, **budget**, and a **hub threshold** (default 500, set per Space): an entity this step adds with more distinct contacts is added but flagged *high connectivity* and not expanded further unless you name it in `expandHubs` (or send `includeHubs`); an entity already in the Working Set is never newly flagged. Minimum events, the fan-out ranking and the budget order use the `eventsCol` sum when one is bound (§9.1). |
| **window** | Set the time range later expands inherit: from / to, slot, days of the week and **Excluded dates (holidays)** — a date, `FROM..TO`, or a yearly rule `*-MM-DD`, `*-MM-DOW#N` (the Nth weekday of a month, e.g. `*-11-THU#4`) or `*-MM-DOW#L` (the last one, e.g. `*-05-MON#L`), optional `Name=` first, at most 32 rules. |
| **exclude** | Remove entities — a **reason is required**. Stays excluded. |
| **hide / keep** | Hide from view only; **keep** protects an entity from exclusion. |
| **annotate** | Attach a note, with an optional **confidence grade** (NATO/Admiralty, e.g. `B2`), to an entity or a link. |
| **resolve** | Merge identifiers the Space knows to be one person (§9.6). |
| **undo** | A recorded step that reverts the previous one. |

Clicking a node while an Investigation is open **selects** it for the next op. Switch *Show the query graph* to pick
seeds from the wider picture. Pressing *Seed* returns the canvas to the Working Set, so *Expand one hop*, *Keep*,
*Hide* and *Exclude* act on the entity you just seeded.

**Not expanded yet.** On an Investigation's canvas an entity that no *expand* has followed yet is drawn with a
dotted outline (legend: *Dotted outline: not expanded yet*, with a count). Hovering it, or its detail dialog, says
*Not expanded yet*, and the Working Set's *expanded* column reads *Yes* or *Not yet*. A step that finds nothing still
marks its entity expanded. An entity a step added is itself unexpanded until you expand it, undoing a step reverts it,
and a *high connectivity* hub keeps its ring instead (it is already "shown, not expanded"). Use it to pick the next
node to expand when you go one node at a time.
Two buttons under that legend row help: *Next unexpanded* selects the next unexpanded entity (so *Expand one hop* acts on it) and *Only unexpanded* draws just the unexpanded frontier until you press it again.
On the exploration canvas (no Investigation) the same dotted outline appears once you have expanded a node with *Expand neighbours*: every node that expansion has not followed since the last query is dotted; a new query forgets the history. Nothing is recorded there.
The legend's link-kind chips change the DRAWING only: a hidden kind is still followed by the next *Expand one hop*, and the hide is not in the log or the Dossier. To leave a kind out of an expand, set *Link kinds* in the expand settings (that is recorded). In the Connect panel, clicking a connected pair's row emphasises that example route on the canvas.

**Choosing neighbours.** With one entity selected, **Choose neighbours…** in the Investigation tab lists its
neighbours, strongest first, with their event counts and link kinds. Tick some and press **Add selected**, or press
**Add the 10 strongest**: one *expand* step then follows the links to ONLY those neighbours and records which ones.
The list is a one-hop read of the whole Dataset with no time window and a row limit (it says when it is cut), so
treat it as a guide; the step you add uses the window and caps of the expand settings. A restricted step leaves the
entity *not expanded yet* (it still has unchosen neighbours), a list of 50 or fewer neighbours is never held for a
second approver, and a longer list is judged like any other expand. On a *high connectivity* hub the same override
confirmation as *Expand anyway* is asked first.

**High connectivity (hubs).** An entity flagged *high connectivity* is ringed on the canvas (legend: *Ringed: high
connectivity*), badged with its own sealed count — *High connectivity — 650 contacts (threshold 500), not expanded* —
in the panel and in the Working Set rows (logs sealed before the count was recorded show *more than 500 contacts*),
and listed under **High connectivity**. The degree result says whether the counts came from the link index or the
Dataset. *Expand next degree* leaves hubs out of its frontier and says how many it left out. To go
through one anyway, use **Expand anyway** on it (that entity only), or tick **Include hubs** on the expand settings
(every hub the expand reaches, including the next degree). Either asks you to confirm first, showing the
Investigation's purpose; the override is sealed in that step and the panel shows *Override recorded at step N*.
The hub threshold is set per Space in **Settings → Link Analysis → Hub threshold** (a whole number ≥ 1; blank = the
default, 500; the value in force is shown; editing needs the Workbench authoring capability, read-only otherwise).

Reading a Dataset's links (Explore a Dataset, Follow the money, saved views) is never held by the fan-out threshold - the row limit only caps what is drawn; only expanding ONE entity whose real link count is above the threshold waits. Without the Incident management capability the Investigation tab is read-only: Seed, Expand, Undo, marks, Fork a Draft and the Draft actions are disabled, with a note saying why. In the standalone Link Analysis app, a user with the Workbench authoring capability also sees a **Settings** link in the left rail (Settings > Link Analysis).

**Four-eyes (large expands).** An expand whose budget or fan-out cap is above the Space's four-eyes thresholds
(**Settings → Link Analysis**) does not run: it answers *Waiting for approval* and is held as a request, one at a time
per Investigation. Someone **other than you** who holds *Approve link expansions* decides it in the **Oversight** card at the bottom of the Investigation tab (in the studio it is also listed under **Approvals Inbox →
Link expansions**; the standalone application has no Approvals Inbox): each request shows the Investigation, who asked and when, its purpose, why it was held and, in
plain words, what it will read (frontier, Dataset, direction, link kinds, budget). **Approve** runs it at once as
your step, sealed with who approved it; **Deny…** takes an optional reason (at most 200 characters) and reads
nothing. You never see your own requests there, and the server refuses you deciding them. Where the Investigation
has members (§9.12) only a lead or reviewer may decide, so only they see it; an owner-only Investigation can be
decided by any approver. *All decisions* lists the ones already approved or denied.

Before you click, the expand form states what the next expand will read (its row budget and fan-out cap, the profile's presets filling what you leave blank) and says *Will need approval from a second person* when either is above the Space's threshold; with no threshold set (the shipped default) nothing is held. A held request's *waiting for approval* line names the limit crossed and the Settings key (`four_eyes_budget_above`, `four_eyes_fan_out_above`).

### 9.3 Replay, drift and fork

* **Replay** re-checks that the log still produces the recorded Working Set. **Re-read** re-runs each recorded query
  and shows **drift** — what changed in the data since you did it. Both stay available when you can only read (a
  reviewer): they change nothing, so a read-only role can check an Investigation.
* **Re-order** steps creates a **fork**: a new Investigation showing its parent; the original stays untouched.

**Members.** Under the Investigation's header, **Members** lists who works on it, each with a role chip (lead, analyst,
reviewer). A lead can **Grant** a role to a Subject id and **Revoke** a member; an Investigation always keeps at least one
lead, so revoking or demoting the last one is refused and the panel says so. Everyone else sees the list without the
controls. If you are not a member, the list is not shown at all.

**Drafts.** **Drafts** is your own working copy: **Fork a Draft** (one live Draft per person, up to 50 open in a Space),
then use **Working Set** to see its size, **Conflicts** to see what a rebase would do, **Rebase** to move it onto the
Investigation's current head (steps that were superseded or blocked are dropped, and the panel names them), **Promote** to
apply its steps to the Investigation, or **Discard**. A Draft shows a state badge (open, hibernated) and how many steps the
Investigation has moved since you forked. A promote that contains a sensitive expand is **held for four-eyes approval**: a
different person approves it under Oversight, and nothing is applied until then. Before you fork you may type a short name
(up to 80 characters, "Name this Draft (optional)"); the list shows it beside the Draft. The name is your own note: it is shown
as typed, is not masked, and appears in audit as a length only, so do not put anything in it you would not write on a whiteboard.

### 9.4 Working Set and rows
The **Working Set rows** table lists entities, links or exclusions with paging; it states if it is truncated and at
which step. **Coverage** (button **Check coverage**) shows which days of the Investigation's window have no rows at all in its
Dataset (*Days with no data* / *Every day has data*), because an empty result on a missing day says nothing about
activity. Where the Dataset can tell, a per-Collector table lists days covered and missing days; otherwise the panel
says *Per-Collector coverage is not assessed for this Dataset.*

### 9.5 Entity Lists
Named lists of entities (watch lists, agent tills, accounts of interest) with ranges, network ranges (CIDR) and
expiry. A list can seed or exclude in bulk (*seedBy / excludeBy*); large lists may need a second approver.

### 9.6 Identity resolution
The **Entity Lists** page also shows the **Identity Fact log**: its head (fact number and hash) and *Chain verified* —
the server re-checks every link of the hash chain on each read, so a broken chain shows *Chain NOT verified* and nothing
else. Cutting the end of a chain leaves a shorter valid chain, so note the head hash and compare it later. The same
panel has the **mapping Dataset** import (Dataset, two columns, optional types, a reason); it reports counts only.

**Identity resolution** panel: assert that two identifiers are the same entity (for example a phone number and a
SIM), see a group's members and the assertions that joined them, retract one. Keys must be typed and normalised
(`msisdn:+4477…`). A **mapping Dataset** can import many pairs at once. Inside an Investigation the **resolve** op
shows merged nodes; by default this is **display only** — expanding or excluding still acts on the raw identifier
unless you choose **merged** on that step, which then treats a whole group as one entity. Matching of untyped values
is conservative: if more than one type could claim a value, it stays unmatched and the log says so.

### 9.7 Measures and Alert Rules
* **Measures** strip: entity count, link count, events, excluded count, deepest hop, links by kind.
* **Watch** on a Measure (Measures strip or Value Measures) creates an **Alert Rule** on an Investigation's Measure. It fires through the normal alert
  path and can open an Incident. It watches the sealed Working Set.
* **A fired Alert names the Investigation, not the entities.** On the Alerts page, an Alert from an Investigation rule has
  an *Open Investigation* action (it opens Link Analysis on that Investigation; the standalone app has the same Alerts page in its menu, and the action stays inside it). The Alert carries the **count** only, by
  design: an Alert is readable by people who are not members of the Investigation, so the breaching entities are read in
  the Investigation (its Value Measures), where its access rules and masking apply.

### 9.8 Value Measures (whole-Dataset behaviour detectors)
**Where to find it:** open the **Value Measures** group in the Link Analysis toolbox (beside Suspicion score; "Find entities by money flow") - it pre-selects the Dataset and columns of the loaded Query - or use the Value Measures panel in the Investigation side pane, which is also where **Watch** (binding an Alert Rule) lives.
Choose a Dataset and column roles (value, time, link kind), a window (from/to, or **last N hours/days**, at most 31
days, evaluated in UTC) and a Measure. The Dataset, the link columns and the bound time column are prefilled from the open
Investigation; From and To are date pickers; **Run Measure** stays disabled, with the reason beside it, until the form is
valid. **Watch** is shown only to users who can author Alert Rules. Refusals for size (413) and the per-user rate limit
(a burst of 20, then one request every 3 seconds, 429) are explained in plain sentences.

| Measure | Flags |
|---|---|
| **Pass-through** | Accounts forwarding nearly everything they receive (ratio, with retention = 1 − ratio). |
| **Velocity** | Accounts that forward money within hours of receiving it. |
| **Time to cash-out** | How fast received money reaches a cash-out. |
| **Cash-out concentration** | An agent/till taking a large share of all cash-out, and from how many payers. Can be limited to an **agent Entity List**. |
| **Structuring** | Payees collecting many in-band legs from several payers. |
| **Benefit transfer** | A benefit payment passed on to one counterparty (default: at least 5 recipients). |
| **Value-weighted links** | Total value and count per pair (a weighting, not a test). |

A rule based on one of these is an Alert Rule that fires **once, on the count of breaching entities**, and lists them.

### 9.9 Dossier — the evidence document
**Dossier** produces, from an Investigation: a summary and topology, a chronological ledger of every step with its
reason, the exclusions, score tables, and a **SHA-256 manifest** over every stored artefact. Download the
*steps* (plain language) and *method* statements, or the full JSON. **Verify** checks a manifest against the store and
reports anything **changed, missing or added**. Tampering is detectable.

**Sealed bundle.** *Download sealed bundle* gives one JSON file to hand outside the system: the Dossier, the
Investigation's references and a seal over both (ids masked as the Space masks them). *Verify a bundle…* uploads a file
back: an edited file fails the **seal** ("edited after export"), and the server also checks the Dossier against the
store as it is now. Adding a reference after export never breaks a bundle.

**References** (below the Dossier) lists the Investigation's pointers to records in other systems (system, type, id,
optional URL and label). They are shown as text, never opened, never trusted and they grant no access; they cannot be
edited or deleted — add a new one to supersede a wrong one. Adding needs the Incident-management permission and the owner.

### 9.10 Templates
**Save as template** turns an Investigation's method into a reusable recipe: seeds become parameters; your ad-hoc
exclusions are **dropped** (they name specific entities) and the preview tells you what was dropped or generalised.
**Instantiate** builds a new Investigation over the same or another Dataset; you pick the template from a list of your own.

### 9.11 Widgets
**Pin to a Widget** puts a Working Set on a Dashboard. **Frozen** (default) shows the Working Set as of a pinned step;
**Live** follows it and shows what changed since. Only people allowed to read the Investigation see data; everyone
else sees "Not available to you". A Live Widget cannot be exported to another Space.

### 9.12 Linking to a Case, and who can see an Investigation
* An Investigation is **yours** by default — nobody else can open it.
* **Link to Case** (optional; needs the operations module) makes the **owner or assignee of that open Case**
  able to *read* it (log, Working Set, dossier, measures, coverage). They cannot change anything. Access ends when the
  Case closes or they are reassigned. An unlinked or unlicensed installation simply stays owner-only.
* The **Investigations** section of the Investigation tab lists what you own plus what is shared with you
  (**List Investigations**, backed by `GET /inv/investigations`). Each row has an **Open** button; a row shared through
  an open Case reads "shared through Case …  (read-only)". You can also open one by pasting its id into **Open by id**.
* To annotate a link, pick it in **Annotate a link**, type the note and press **Annotate link**; the sealed notes
  appear under **Link annotations**, and the Dossier carries the same link id.

### 9.13 Share an Investigation (embed link)

**Share (embed link)** in the Investigation tab (next to Members and Drafts) gives someone without an account a
**read-only, sealed view** of the Investigation through a link.

* **Off unless the host runs with `-Dla.embed.enabled=true`.** Otherwise the panel says so and its controls are disabled.
* **Who can.** Minting and revoking need the `canShareInvestigation` capability **and** being a *lead* of this Investigation;
  everyone else sees the list and a read-only note.
* **Create link.** Give an optional label and a lifetime in hours (the Space's default is pre-filled; the limit is the Space's
  maximum, set in Settings > Link Analysis). The link is shown **once** with a Copy button - it is not stored and is not shown again,
  so copy it before pressing Done. A refused classification, a lifetime above the maximum or a missing role is shown with the server's reason.
* **What the link shows.** The same view the Dossier is built from, read-only and sealed. Ids are masked per the Space's masking
  mode (stated in the dialog). Anyone holding the link can read it until it **expires** or you **revoke** it, and can copy what they
  see. It cannot be framed on other sites.
* **The list.** Each link shows its label, a status (active, expired, revoked), when it expires and how many times it was
  viewed. **Revoke** (with a confirmation) cuts access at once; this cannot be undone - create a new link instead.

---

## 10. Worked examples (demo Space)

**Find a money-mule ring.** Load `mule_layering_ring` (on the `la-showcase` Space Template: 112 accounts over the three landed days). Toolbox → *Suspicion score* puts the relays
and the hub in the top decile. *Pattern match → Structuring (smurfing)* finds `MULE-HUB-01`: 96 in-band legs from 12
payers. Value Measures → *Pass-through* flags 13 accounts (the two relays at ≈ 0.985, the hub at 0.979, `SHELL-A` above 1); *Velocity* shows them forwarding within
hours (median 1.5 h and 2.5 h for the relays, 6 h for the hub). *Save this analysis → create a new Case from graph nodes*, tick the hub and relays.

**Find the cash-out point.** Value Measures → *Cash-out concentration* with cash-out kind `cash_out`: `TILL-06` holds
97 % of cash-out value from 8 payers (reproduced by `ControlApiLaShowcaseFiguresTest`). *Benefit-transfer* isolates `SKIMMER-01` (6 recipients).

**Check for one person behind two identities.** In the `demo` Space load `roaming_imsi_footprint` (each roaming IMSI
linked to the visited networks that billed it): IMSI `001010000000042` touches four partners in three days, which
*Explain node* and *Centrality* surface. Spellings of one operator (the corpus has *Cinder Wireless* written four ways)
are merged into one node automatically; for two different identifiers of one person (a phone number and a SIM), open an
Investigation, use **Identity resolution** to assert them, then **resolve**. *(Unverified: whether the screen
announces a count of possible split identities.)*

---

## 11. Who can do what

| You want to… | Needs |
|---|---|
| Explore, analyse, save a view, export | Sign-in (Builder lens for the studio) |
| Start an Investigation, run ops, Drafts, link a Case, create a Case from nodes, save a snapshot to a Case, add an entity to a watch or block list | `canManageIncidents` |
| Run a server graph run (*All algorithms (server)*, *Compute risk*) | `canRunLinkGraphAnalysis` |
| Build a link index | `canBuildLinkIndex` |
| Approve or deny a held large expand (never your own) | `canApproveLinkExpansions` |
| Bind an Alert Rule to a Measure | `canAuthorAlertRules` |
| See entity keys unmasked | `canRevealLinkEntities` (otherwise masked per the Space's setting) |
| Change Link Analysis settings (and see **Settings** in the standalone app) | `canAuthorWorkbench` |
| Set what a Dataset column holds, a Dataset's primary Entity Type, add an Entity Type (Entities page, §4.5) | `canAuthorWorkbench` |

**Read-only access.** Without `canManageIncidents` you can still read: the Investigation tab says *Changing an
Investigation needs the Incident management capability - you can read it*, and Seed, Expand, Undo, marks and the Draft
actions are disabled. In the `la-showcase` demo, *Demo RA Analyst* and *Demo Manager* are in this position (a
view-only role); *Demo FM Analyst* holds manage-incidents, graph-run, index-build and Alert-Rule authoring;
*Demo FM Manager* is the second approver; *Demo Admin* also holds `canAuthorWorkbench`.

**Read-only access.** Without a capability the screen shows the control disabled (or the form replaced) with a
sentence naming what it needs, instead of a button that answers 403. What each kind of user sees:

| Without… | What is read-only, and the note you see |
|---|---|
| `canManageIncidents` (for example the `la-viewer` role) | You can open, read and replay Investigations. Start Investigation, Seed, Expand, Exclude, Annotate, Undo, Mark, Re-order, Drafts, Case link (Link to Case / Unlink), Members (Grant / Revoke), Add reference, Save and Instantiate template, Attach to Case, and Entity List and Identity edits are disabled with *"…needs the Incident management capability"*. The Identity Fact log is not read at all (its groups reveal which identifiers belong together). |
| `canAuthorAlertRules` | *Watch* (arming an Alert Rule), standing detection and *Edit rule* on a bound rule are disabled, with *"…needs the Alert Rule authoring capability"*. |
| `canAuthorWorkbench` | Settings → Link Analysis shows its values but offers no Save (*"Read only"*), and the Link Analysis settings link is absent from the Link Analysis app's left rail. |
| `canRunLinkGraphAnalysis`, `canBuildLinkIndex` | *Run on server* and *Build index* are disabled and say why. |

Still available to everyone who can read: the Dossier (build, download, verify), verifying a sealed bundle, graph runs'
read side, and every view and analysis tool.

A refusal shows the server's reason. "Not found" on an Investigation can mean it does not exist, it is not yours, or
its Dataset is no longer shared with you — the system answers the same for all three on purpose.

**Masking.** Per Space, entity keys are shown *typed* (only sensitive types masked), *all* masked, or *none*. A Space that
sets nothing uses *typed* in Standard and above, and *none* in Basic.
Masked values appear as stable tokens you can still group and count by.
The same rule applies on the query graph, the node list, paths, patterns and Value Measures as in the Working Set: a *Masking: typed|all|none*
badge in the query panel says which applies, and stays visible after the panel collapses to its summary. A masked entity shows as an alias (`masked:...`); pick, seed and expand it as you
would a name. The Investigation gives the seeded entity its own alias, so the Working Set row differs from the query graph's;
the Selected line keeps them one entity and marks it *(masked)*. An alias keeps working after a server restart.

**Settings → Link Analysis** sets per-Space limits: the four-eyes thresholds (large expands wait for a second
person), the merged-traversal cap and the `seedBy` cap. It also shows and, with the authoring capability, changes the
**masking mode** (`typed`, `all` or `none`, each explained beside the field, with the mode in force), the **link index**
(whether reads are served from a fresh index, plus its build workers, waiting line and disk budget, and the longest age in hours of an index that may still serve a Dataset whose files cannot be checked for changes - older, the read comes from the Dataset and says so; default 24 hours), the default
**server graph-run budget** (nodes, edges, time, shown with the server's ceilings) the Draft limits (default: 50 open per Space, hibernate after 60 minutes, expire after 30 days; the `la-showcase` template sets 10, 60 and 14) and the **embed links** (the default and the longest lifetime in hours, 1 to 8760, the default not above the longest, and the classifications that may not be shared by link; the feature itself is OFF unless the host runs with `-Dla.embed.enabled=true`; defaults 72 h and 720 h). Embed links are an API feature only: there is no screen to mint or open one yet. It lists the
Entity Types in force and names the active Investigation store (filesystem or PostgreSQL) read only. Without the
capability every field is read only.

---

## 12. Limits and honest behaviour

* **Views are not evidence; snapshots and Dossiers are.** A snapshot captures what was on screen when you saved.
* Nothing is silently dropped: truncation, unmatched values, unvalued rows and refusals are all stated.
* Time-based tools read times in a stated zone (UTC for Measures); naive timestamps are assumed UTC.
* The server-side path search works directly over a Dataset and is certified at about one million links (5-hop walk, warm p95 under
  350 ms); above that a Space can switch on the edge index (Space setting `index.enabled`, on by default since 2026-10-10; set it false to switch it off), measured to 100 million links.
* An Alert on an Investigation watches its **sealed** Working Set, not live data.
* **Every limit is a setting.** *Settings → Link Analysis* lists each one with its default, its allowed range and
  the value in force, in three sections: **Analysis** (the Space's limits: expand budgets, path depth and time,
  connect seeds and hops, page sizes; set by a Space administrator), **Host resources** (the server's own:
  Graph Run ceilings and pools, index build memory, rate limits; set by a system administrator, read-only for
  everyone else), and **My browser** (canvas, labels, layouts and browser algorithm caps for your own machine,
  never above the Space's value; *Reset to Space value* undoes yours). A value outside its range is refused with
  the range in the message.

## 13. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| "Truncated" banner | A cap cut the data. Narrow the query or use a server-side tool. |
| A pattern returns nothing, with an explanation | No time column chosen, or your filter removed the legs it needs. |
| Pattern finds nothing on a big graph but should | Use **Run on server** — the browser graph was truncated. |
| "Every node mapping needs a Dataset and an id column" | Fill all required fields before Run. |
| Investigation says not found | It is not yours, or its Dataset access changed. Ask the owner. |
| Merged step refused | No **resolve** is in force at that point — add one first. |
| Two accounts that are one person appear separately | Assert an identity (§9.6); near-duplicate spellings are merged automatically. |
| Link Analysis menu missing | Personal edition, or the module is not installed. |
| Sign-in page: **The server could not start a session** | The server answered an error when the page asked how to sign in (for example two Spaces define the same Demo User differently: *Demo User '…' is defined differently in Spaces '…' and '…'*). The page shows the server's message and **Try again**; fix the configuration, then reload. No picker or SSO button is shown meanwhile. |
| Sign-in page: **Single sign-on isn't set up** | SSO has not been configured on this installation; ask the administrator. |
| Sign-in page: **Sign-in failed — We couldn't complete sign-in. Please try again.** | The sign-in did not complete; try again. |
| *The server requires sign-in, but this page started without a session. Reload to sign in.* | Your session ended or the server's edition changed under an open page; reload. |
| *No Space is attached - drop a Space folder into the spaces root … or create one in Settings -> Spaces* (503) | The installation hosts no Space; an administrator must add or create one. |
| *Changing an Investigation needs the Incident management capability - you can read it.* | Your role is read-only (§11). Ask for `canManageIncidents`. |
| *That is over a size limit, so nothing was stored.* (413) | The step or Investigation passed its size limit. Narrow the step (fewer seeds, a shorter window) or fork a smaller Investigation; an administrator can raise the limit. |
| *Too many requests…* (429) | Link Analysis allows a burst of 20 requests per user, then one every 3 seconds. Wait and retry. |
| Waiting for approval | A large expand is held for four-eyes (§9.2); a second person decides it. |

## 14. Vocabulary

**Dataset** — a table the platform can query. **Entity** — a node: a thing rows mention (account, subscriber).
**Link** — an edge folded from rows between two entities; carries a count and attributes. **View** — a saved
query setup. **Snapshot** — a sealed picture of what was on screen. **Investigation** — the recorded, replayable
work. **Working Set** — the entities an Investigation currently holds. **Op log** — its ordered steps.
**Dossier** — the evidence document. **Measure** — a computed number over a Dataset or Working Set.
**Alert Rule** — a watch that fires an Alert. **Incident / Case** — the operational objects you escalate to.
**Entity List** — a named set of entities. **Identity resolution** — declaring that two identifiers are one entity.
