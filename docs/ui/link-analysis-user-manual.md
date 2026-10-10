# Link Analysis — User Manual

*For analysts: fraud, revenue assurance, money-laundering and network investigators. Written 2026-09-30 against
the shipped product; every control named here is a label you will see on screen. The developer-level mechanism
is in [`okf/frontend/features/link-analysis.md`](../okf/frontend/features/link-analysis.md).*

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

**Where:** *Studio → Link Analysis* (`/studio/link-analysis`).
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

**Fastest start:** the first screen offers three starter cards. **Follow the money (example)** opens a ready-made
saved view of your Space and draws it at once (shown only when your Space has one). **Explore a Dataset** opens the
Dataset picker — the Datasets that look like links are listed first, each with a one-line reason — and when you
pick one its from and to columns are filled in for you and the graph is drawn. **Open a saved view** opens
your saved views. The steps below are the same thing done by hand.

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

**Shortcut:** open **Open saved views** and load a ready-made one — the demo Space ships `mule_layering_ring`,
`mule_structuring`, `roaming_imsi_footprint` and others.

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
  wraps back into a row above the stacked panels. The page does not scroll on a normal window: the Link Analysis page is as tall as the window below the application header (it hides the page footer, which every other page keeps), the active-query chips sit in the title row, and the panels scroll inside themselves (measured 1440x900: canvas 506 x 708 px with both panels open; 1280x720: 652 x 528 with the Toolbox collapsed; 1000x700: 372 x 508). The canvas never drops below about 420 px high: in a shorter window the page scrolls instead (1280x500: canvas 637 x 432). The Query panel starts 220 px wide (it stays resizable, 200 to 560) and below 1300 px of window width the Toolbox starts collapsed to its icon rail; open it from the rail when you need it. Your explicit open or close is remembered for the rest of the browser session. On a narrow screen (under 960 px) the panels stack and the page scrolls.
* **Docks** are resizable (drag or arrow keys) and collapse to a rail (*Hide the query panel*, *Hide the
  toolbox*); **Hide the side panels** gives the canvas the whole width.
* **Canvas:** drag to pan, scroll to zoom, drag a node to move it, click a node for its details, click a branch
  to collapse it. **Fit the graph to the screen** re-frames everything.
* **Data strip** (bottom): the rows behind the graph as a table, with a SQL editor for questions the graph cannot
  ask. *Project result as graph* turns a query's rows back into a graph.
* **Undo / Redo** step through display and filter changes.

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

* The server returns at most **2 000 links** (hard ceiling 20 000) and the projection is capped at **500 nodes**;
  analysis in the browser is capped at **2 000 nodes**. The footer prints the caps.
* When a cap cuts data you see **Truncated** — never a silent "no results". Narrow the query, or use a server-side
  tool (*Find paths (server)*, *Run on server* for patterns, the Measures panel).
* Very busy hub nodes are folded into a **super-node** so they do not hide everything else.
* Above 20 links on the canvas, link labels are hidden (shown on hover or click) to keep the picture readable; *All link labels* in the Display menu overrides it.

---

## 5. Exploring the graph

### 5.1 View tools (Toolbox → **View**)

* **Layout** — how nodes are arranged: *Layered, Grid, Force, Clustering force, Radial, Degree ordered, Circular,
  Information density, Fruchterman,* and tree layouts (*Mind map, Organization chart, …*) that are enabled only when
  the data is tree-shaped.
* **Overlays** — *Legend*, *Working set* (top-N counts), *Node labels*, *Link labels*, *Level of detail*, *Minimap*,
  *Grid*.
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

### 5.3 Domain profiles

A profile (*Generic graph, Financial crime, Telecom, Supply chain, Cyber*) renames things in domain words and
**suggests** the tools that matter, marked with a badge. It changes emphasis only, never the data.

---

## 6. The analysis tools (Toolbox → **Analysis**)

One group opens at a time; results stay while you switch tabs. Everything runs in the browser over the graph you
loaded (cap 2 000 nodes; the heavier scores are capped lower and say so).

| Group | Answers |
|---|---|
| **Shortest path** | How are A and B connected? *Fewest hops*, or weighted by tie strength. |
| **All paths** | Every route between A and B up to a length. |
| **Find paths (server)** | The same across the **whole Dataset** in the database — for graphs too big to load. Shows depth searched, and warns if capped. |
| **Explain node** | A node's neighbourhood, degree and role in plain words. |
| **Centrality** | Who is most important: degree, betweenness (brokers), closeness, eigenvector, Katz, PageRank, HITS. |
| **Communities** | Natural clusters: label propagation or Louvain. |
| **Connected components** | Separate islands. |
| **Cycles** | Money or calls that return to where they started. |
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
and the queued entities seeded as its first step. You can remove one from the queue or clear it first.

---

## 7. Pattern matching

Pick a **pattern pack** to pre-fill the motif, edit the thresholds (they are always visible), run it.

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
than returning "none". When the loaded graph was truncated, **Run on server** repeats the search across the whole
Dataset.

---

## 8. Saving, exporting and handing over

| Action | What it keeps | Notes |
|---|---|---|
| **Save the current view** | The setup: source, mapping, layout, profile, display options, filters | A **view** re-projects *live* data — not evidence. Loadable from **Open saved views**; has version history, comments and tags. |
| **Save this analysis** | A **sealed snapshot** of exactly what is on screen, with a SHA-256 fingerprint | Choose a Case to attach to, or **create a new Case from graph nodes** — tick the nodes that become the Case's first members (each becomes an Incident; the same entity reused later is reused, not duplicated). What is on screen means the Working Set while an Investigation is open. |
| **Attach to Case** | Links the current snapshot to an existing Case | Needs the operations module; otherwise the control explains why it is unavailable. |
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

### 9.2 Steps you can take

| Op | Effect |
|---|---|
| **seed** | Put starting entities into the Working Set (by id, or from an Entity List). |
| **expand** | Follow links one hop. Options (the *hop ladder*): direction, link kinds, time **window**, minimum events / distinct days, candidate degree bounds, fan-out cap, **budget**. |
| **window** | Set the time range later expands inherit. |
| **exclude** | Remove entities — a **reason is required**. Stays excluded. |
| **hide / keep** | Hide from view only; **keep** protects an entity from exclusion. |
| **annotate** | Attach a note, with an optional **confidence grade** (NATO/Admiralty, e.g. `B2`), to an entity or a link. |
| **resolve** | Merge identifiers the Space knows to be one person (§9.6). |
| **undo** | A recorded step that reverts the previous one. |

Clicking a node while an Investigation is open **selects** it for the next op. Switch *Show the query graph* to pick
seeds from the wider picture.

### 9.3 Replay, drift and fork

* **Replay** re-checks that the log still produces the recorded Working Set. **Re-read** re-runs each recorded query
  and shows **drift** — what changed in the data since you did it.
* **Re-order** steps creates a **fork**: a new Investigation showing its parent; the original stays untouched.

### 9.4 Working Set and rows
The **Working Set rows** table lists entities, links or exclusions with paging; it states if it is truncated and at
which step. **Coverage** shows how much of the world your steps actually looked at.

### 9.5 Entity Lists
Named lists of entities (watch lists, agent tills, accounts of interest) with ranges, network ranges (CIDR) and
expiry. A list can seed or exclude in bulk (*seedBy / excludeBy*); large lists may need a second approver.

### 9.6 Identity resolution
**Identity resolution** panel: assert that two identifiers are the same entity (for example a phone number and a
SIM), see a group's members and the assertions that joined them, retract one. Keys must be typed and normalised
(`msisdn:+4477…`). A **mapping Dataset** can import many pairs at once. Inside an Investigation the **resolve** op
shows merged nodes; by default this is **display only** — expanding or excluding still acts on the raw identifier
unless you choose **merged** on that step, which then treats a whole group as one entity. Matching of untyped values
is conservative: if more than one type could claim a value, it stays unmatched and the log says so.

### 9.7 Measures and Alert Rules
* **Measures** strip: entity count, link count, events, excluded count, deepest hop, links by kind.
* **Watch this measure** creates an **Alert Rule** on an Investigation's Measure. It fires through the normal alert
  path and can open an Incident. It watches the sealed Working Set.

### 9.8 Value Measures (whole-Dataset behaviour detectors)
Choose a Dataset and column roles (value, time, link kind), a window (from/to, or **last N hours/days**, at most 31
days, evaluated in UTC) and a Measure. Thresholds are shown and editable.

| Measure | Flags |
|---|---|
| **Pass-through** | Accounts forwarding nearly everything they receive (ratio, with retention = 1 − ratio). |
| **Velocity** | Accounts that forward money within hours of receiving it. |
| **Time-to-cash-out** | How fast received money reaches a cash-out. |
| **Cash-out concentration** | An agent/till taking a large share of all cash-out, and from how many payers. Can be limited to an **agent Entity List**. |
| **Structuring** | Payees collecting many in-band legs from several payers. |
| **Benefit-transfer** | A benefit payment passed on to one counterparty (default: at least 5 recipients). |
| **Value-weighted links** | Total value and count per pair (a weighting, not a test). |

A rule based on one of these is an Alert Rule that fires **once, on the count of breaching entities**, and lists them.

### 9.9 Dossier — the evidence document
**Dossier** produces, from an Investigation: a summary and topology, a chronological ledger of every step with its
reason, the exclusions, score tables, and a **SHA-256 manifest** over every stored artefact. Download the
*steps* (plain language) and *method* statements, or the full JSON. **Verify** checks a manifest against the store and
reports anything **changed, missing or added**. Tampering is detectable.

### 9.10 Templates
**Save as template** turns an Investigation's method into a reusable recipe: seeds become parameters; your ad-hoc
exclusions are **dropped** (they name specific entities) and the preview tells you what was dropped or generalised.
**Instantiate** builds a new Investigation over the same or another Dataset.

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

---

## 10. Worked examples (demo Space)

**Find a money-mule ring.** Load `mule_layering_ring` (193 accounts). Toolbox → *Suspicion score* puts the relays
and the hub in the top decile. *Pattern match → Structuring (smurfing)* finds `MULE-HUB-01`: 96 in-band legs from 12
payers. Value Measures → *Pass-through* flags 13 accounts (relays at ≈ 0.985); *Velocity* shows them forwarding within an
hour. *Save this analysis → create a new Case from graph nodes*, tick the hub and relays.

**Find the cash-out point.** Value Measures → *Cash-out concentration* with cash-out kind `cash_out`: `TILL-06` holds
82 % of cash-out value from 8 payers. *Benefit-transfer* isolates `SKIMMER-01` (6 recipients).

**Check for one person behind two identities.** Load `roaming_imsi_footprint` (212 subscribers). The panel notes
*1 possible split identity*. In an Investigation open **Identity resolution**, assert the two identifiers, then **resolve**.

---

## 11. Who can do what

| You want to… | Needs |
|---|---|
| Explore, analyse, save a view, export | Sign-in (Builder lens for the studio) |
| Start an Investigation, run ops, link a Case, create a Case from nodes, save a snapshot to a Case | `canManageIncidents` |
| Bind an Alert Rule to a Measure | `canAuthorAlertRules` |
| See entity keys unmasked | `canRevealLinkEntities` (otherwise masked per the Space's setting) |
| Change Link Analysis settings | `canAuthorWorkbench` |

A refusal shows the server's reason. "Not found" on an Investigation can mean it does not exist, it is not yours, or
its Dataset is no longer shared with you — the system answers the same for all three on purpose.

**Masking.** Per Space, entity keys are shown *typed* (only sensitive types masked), *all* masked, or *none*.
Masked values appear as stable tokens you can still group and count by.

**Settings → Link Analysis** sets per-Space limits: the four-eyes thresholds (large expands wait for a second
person), the merged-traversal cap and the `seedBy` cap.

---

## 12. Limits and honest behaviour

* **Views are not evidence; snapshots and Dossiers are.** A snapshot captures what was on screen when you saved.
* Nothing is silently dropped: truncation, unmatched values, unvalued rows and refusals are all stated.
* Time-based tools read times in a stated zone (UTC for Measures); naive timestamps are assumed UTC.
* The server-side path search works directly over a Dataset and is certified at about one million links (5-hop walk, warm p95 under
  350 ms); above that a Space can switch on the edge index (Space setting `index.enabled`, off by default), measured to 100 million links.
* An Alert on an Investigation watches its **sealed** Working Set, not live data.

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

## 14. Vocabulary

**Dataset** — a table the platform can query. **Entity** — a node: a thing rows mention (account, subscriber).
**Link** — an edge folded from rows between two entities; carries a count and attributes. **View** — a saved
query setup. **Snapshot** — a sealed picture of what was on screen. **Investigation** — the recorded, replayable
work. **Working Set** — the entities an Investigation currently holds. **Op log** — its ordered steps.
**Dossier** — the evidence document. **Measure** — a computed number over a Dataset or Working Set.
**Alert Rule** — a watch that fires an Alert. **Incident / Case** — the operational objects you escalate to.
**Entity List** — a named set of entities. **Identity resolution** — declaring that two identifiers are one entity.
