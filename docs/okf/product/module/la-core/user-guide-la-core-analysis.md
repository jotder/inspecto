---
type: Guide
title: Link Analysis — User Guide — Expand, analyse, match patterns and save
description: Part 3 of 7 of Link Analysis — User Guide: Expand and clean up the graph; The analysis catalogue; Pattern matching; Saving, exporting and the File menu
tags: [module, la-core, link-analysis, user-guide]
timestamp: 2026-11-15T00:00:00Z
item: module.la-core
audience: internal
status: current
verified: 2026-11-19 last-edit
code: la/inspecto-la-core
---

# Link Analysis — User Guide — Expand, analyse, match patterns and save

Part 3 of 7 of [Link Analysis — User Guide](user-guide-la-core.md) · previous: [The screen, building and looking at a graph](user-guide-la-core-building-graph.md) · next: [Investigations, Entity Lists, Alerts and Geo](user-guide-la-core-investigations.md).

## 7. Expand and clean up the graph

On an Investigation's canvas every change here is one **recorded** step of the step log (`expand`, `exclude`,
`excludeBy`, `hide`, `annotate` or `undo`, so replay and the Dossier are unchanged); while exploring it changes only
what you see. Every change ends with a one-line result and **Undo** (also **Ctrl+Z**). In a shared Investigation an
Undo takes back only the step it names: if a colleague recorded a newer step meanwhile, nothing is undone and the log
shows theirs. The words follow the Industry pack (Telecom: *subscribers* and *calls*).

On an Investigation's canvas **Ctrl+Z** undoes the newest recorded step and **Ctrl+Shift+Z** (or **Ctrl+Y**) **redoes**
it: Redo records the undone step again with the same parameters, through the same checks as the first time (an
expand over the four-eyes thresholds is held again). A new step clears what can be redone. The rail's Undo / Redo
arrows stay the display history (layout, colours, folds). The Investigation tab has the same **Undo** / **Redo** pair
at its top. In an unsaved Investigation they undo and redo the unsaved steps (nothing is recorded until Save,
[§11.1](user-guide-la-core-investigations.md#111-start-one)).

### 7.1 Expand

**Expand** (canvas toolbar, **G**, the canvas toolbar's **More** menu, or right-click a node ▸ *Expand…*) opens one
form. With an Investigation open it is the **Expand** section of the **Investigation** tab: the tab opens and the form
takes focus (right-click ▸ *Expand filter…* makes that node the *From*); there **G** with entities selected expands
them at once with the filter, and right-click ▸ **Expand** does the same for that node. On a graph you are only exploring it opens over the canvas, and
**Cancel** closes it. The form, most important first:

* **From**: the selection, the outer ring (next degree), the start entities or the whole Working Set, one press each;
  the line under it says what that is, for example *From the 1 wallet started from*.
* **Expand** with its **preview line**, which says what it would add before anything changes: *This step adds about
  342 numbers and 1,210 calls; 3 busy numbers will be held; 41 numbers are removed by your exclusion lists*. A step
  that would need four-eyes approval says so. **Expand** runs it degree by degree. In an Investigation each degree is
  a recorded `expand` step, and one Undo takes back the whole Expand. A server without the preview shows *No preview
  on this server* and Expand still runs.

Under the button is the **Expand filter**: six folds, each saying on one line what is set, a dot on any group set away
from its default, *N changed* with **Reset to defaults** at the top and a reset inside each changed group. No group opens
a popup. Direction and Depth start open; the others remember whether you left them open. In an Investigation the
filter is what **every** expand uses — the button, a double-click, the right-click menu and **G** — and it stays as you
set it while the Investigation is open, also when the tab is closed and shown again.

* **Direction**: *Both ways*, *Outgoing*, *Incoming* (and *Both ways, mutual* in an Investigation), one press each.
* **Depth**: a **− 3 +** Degrees stepper, from 1 up to the Industry pack's maximum. The arrow keys step it, Home and End jump
  to the ends; at an end the button says why it does nothing. **While the newest step is an Expand, Degrees is that
  Expand's size** (a line under the stepper says so): lower it and the Expand's outermost degrees leave the canvas and
  the recorded Expand, with the entities there and the links only they carried; raise it and the extra degrees expand
  from the outer ring into the same Expand. The first degree always stays. Unsaved, the Expand is cut or extended in
  place (Ctrl+Z restores it); in a saved Investigation the removed degrees are `undo` steps (newest first, a
  colleague's newer step refuses it), kept in the log, and Ctrl+Z does not bring them back (raise Degrees again; Ctrl+Z
  does undo a raise). In an unsaved Investigation, raising grows with the Expand's own Direction, link kinds and Hold, whatever the form now
  says. An Expand and a Degrees change never run at the same time; the later one is ignored. It applies about 0.3 s after you stop clicking. A reopened
  Investigation whose newest step is an Expand binds the stepper to that Expand too, whoever made it. When the newest
  step is not an Expand (a start, a clean-up), or the Expand was recorded before 2026-11-01, the stepper is only
  the next Expand's request, as before.
* **Link kinds** (*All link kinds* / *2 of 5 link kinds*): one chip per kind, pressed = followed. Without a known list of
  kinds an Investigation types them (comma-separated).
* **Time** (Investigation only): chips for the *Investigation window*, *All data*, the last *7 / 30 / 90 days* (counted
  back from the Dataset's latest event, never from today; when that time cannot be read the Expand keeps the
  Investigation window and says so) and *Custom* (a window for this Expand only).
* **Strength** (Investigation only, *At least 3 events*): the minimum events per link and the minimum distinct days.
* **Fan-out and busy** (*Busy over 500 links · 50 per entity*): **Hold busy entities (more than *H* linked)**, on by
  default with *H* = the Space's **busy limit** (default 500) — unticking it expands through every busy entity the
  Expand reaches, asks you to confirm and logs the override —, the most neighbours per entity, the fewest and most links
  a neighbour may have and the row limit per degree.

A field in error opens its fold when you press Expand. A blank field uses the Industry pack's setting.

**Double-click** a node not expanded yet to expand it with the filter. **Right-click** a node (or the selection) on an
Investigation's canvas for everything you can do with it, each a recorded step: **Expand** (the filter, **G**),
**Expand, this once** (*Outgoing only*, *Incoming only*, *Two degrees*, *Only <kind> links*: the filter with that one
thing changed, for this step only), **Shrink** (hide the entities only it links to), **Keep** (**K**), **Hide** (**H**),
**Select neighbours**, **Exclude…** (**X**), **Collapse / unfold** and, on a busy entity, **Expand anyway**. With no
Investigation open, right-click offers **Investigate this**: the Investigation tab's start form, filled with that
entity and its Entity Type. On a Space that masks the entity, the node shows only its alias: if you may reveal masked
entities, the form is filled with the real value through the audited reveal (the form says the reveal is recorded);
otherwise the value stays blank and the form says why. With entities selected, **G** (or **E**) expands them with the filter, **K** keeps and **H** hides them; the
canvas toolbar has **Keep** beside **Hide** (when the toolbar is folded into **⋯**, **Keep the selected** is there). A node no expand has followed yet has
a **dotted outline** (legend: *Dotted outline: not expanded yet*; the dots are close-set and slightly heavier than
other outlines, so the ring stays visible on a projector); on an Investigation's canvas *Next unexpanded* and
*Only unexpanded* under that legend row help you go one node at a time.

**Where new nodes go.** An Expand of up to 50 new nodes leaves every node you see where it is and draws the new ones
**outside** what is already there: past the ring of the entity they hang off, on the side facing away from the rest of
the graph, never on top of another node or a link. A bigger Expand lays the whole graph out again. The layout a graph
opened with stays while you expand it; pick another in **Graph layout** to change it. On an Investigation, each **start
entity** keeps a heavier outline and a small **start** tab through every step, centred on the node's top edge. A
node's name is written under it, unless there it would lie on a start tab of a node just below: then it moves above
its node, or beside it, wherever it is clear, and back once the nodes move apart.

**Remove** (**Delete**, right-click, or the bin on the canvas toolbar) is offered while you explore, outside an open
Investigation. It takes the selected nodes off the graph, together with every neighbour left with no link at all once
they are gone (in either direction; only their direct neighbours are checked). It keeps nothing out: a later Expand
that reaches a removed node draws it again. **Exclude…** is the one that keeps a node out. One Undo (Ctrl+Z) puts a
Remove back. If you then Save the work as an Investigation, each removed node still absent is recorded as a hide.

### 7.2 Held: busy entities

Busy entities link to thousands of others and hide the real links. Expand holds them: they stay on the graph with a
ring and a *held* badge, and **Held (n)** opens the held tray with how many entities each links to. **Exclude held**
(**Hide held** while exploring) takes them all out in one step (recorded as `exclude` with the reason *busy number above N
contacts*); **Expand anyway** expands through one of them (the recorded override).

### 7.3 Collapse

**Collapse** (**C**, the canvas toolbar, or right-click) folds a node's branch — the neighbours that link to nothing
else — into a **+n** badge; double-click an expanded node to fold or unfold it. It only changes what you see, also
inside an Investigation: nothing is recorded and the Working Set does not change.

### 7.4 Clean up

**Clean up** (the broom) offers **Keep paths between start entities** (keep them and every node on a path of at most *L*
links between two of them), **Highlight shortest paths** (glow, nothing removed; **Esc** clears), **Trim dangling
leaves**, **Keep only selected** and **Remove selected**. Each first says what it would do (*hides 16 of 112
entities*) with **Apply** / **Cancel**. While exploring it hides with Undo; in an Investigation it is recorded as a
`hide` with a reason filled in for you.

In an Investigation the tab's **Clean up** fold does the same over the Working Set in **one press each**, the count on
the button (a button with nothing to do is disabled and says why): **Hide 16 dangling leaves**, **Hide 12 with fewer
than [3] events** (the stepper sets the events), **Hide 40 imei** (one button per Entity Type when there are several),
**Keep paths between the 2 selected** (exactly two selected; the stepper sets the longest path in links) and **Keep only
the 9 selected**. Each is one recorded `hide` with its reason noted; **Exclude instead of hide** makes it one `exclude`
carrying that reason. Start entities and kept entities are never taken away. Undo takes it back. The fold works on an
unsaved Investigation too: each press hides on screen and Save records it as the `hide` and its reason; *Exclude
instead of hide* needs a saved Investigation. On a saved Investigation with a time column, **Outside a time window**
offers the chips **7 days**, **30 days** and **90 days** (the days up to the latest event, as in the Expand filter's
Time group): a chip fills From, leaves To blank and reads at once, so the next press is the Hide. Or type a From and/or
To (ISO with an offset or Z, `[From, To)`; a blank field says what blank means): **Find what is outside the window** reads the Dataset
now for the entities none of whose links has an event in it, and **Hide 7 with no link event in the window** records
them as one `hide` whose reason names the window (the Working Set carries link counts, not link times, so the read is
live and only its answer is recorded). An entity whose links carry no time at all is never counted as outside. A
**Time window** step (More steps) is different: it changes what later expands read.

### 7.5 Exclude… and Hidden by lists

**Exclude** (canvas toolbar, right-click, or **X** with nodes selected):

* *From this Investigation only* — one `exclude` step with your reason (exploring: *Hide from this graph*). The
  reason field is in the same dialog, with this Investigation's last three reasons as one-press chips. The canvas
  toolbar's Exclude opens this same dialog.
* *Add to a list…* — an exclusion list of the entities' type, or a new one; they are added (four-eyes where the list
  is governed) and the list is applied, so they stay out of every later step. A **category list** kept by your
  administrator (VIPs, politicians) is offered only to a holder of *Manage exclusion categories*.

**Hidden by lists (n)** shows the lists in force — *41 numbers hidden by 3 lists* — each with who keeps it and how
many it removed; **Switch off** / **Switch on** a list, and **Show what was removed**. A **restricted** list's members
are never listed to an analyst; its count is rounded (nearest 5, at least 5) here, in the step log and in the Dossier.

**First-time hints.** Expand, the Held tray and Hidden by lists each explain themselves once and name this chapter;
**Got it** hides the hint in this browser. Keyboard: **G** Expand (the Investigation tab's Expand section when an Investigation is open), **X** Exclude…, **C** Collapse, **Ctrl+Z** undo —
not in a text field, grid, menu or dialog.

---

## 8. The analysis catalogue

The right panel's **On screen** and **Whole Dataset** tabs.

**Quick questions** (On screen tab): three plain questions about the graph on screen.

* **Busiest nodes** — the five entities with the most links, each in one sentence (*MERCHANT-BIG takes part in 270
  transfers, the most on this graph.*). When the busiest has at least 5 links and twice the next, it asks: *Add it to
  an exclusion list?* (button **Add to exclusion list**). An open Investigation's start entities are never offered for
  exclusion: they are busy because the graph was drawn round them, and their row says so (*It is a start entity: the
  graph was drawn round it.*); the question then goes to the busiest other entity, judged against the next other one.
* **Tight groups** — groups of three or more that link mostly among themselves (*Group 1: 9 accounts,
  14 transfers among them within 3 days*).
* **Route from A to B** — choose **From** and **To**, then **Find route**: the shortest chain following the links'
  direction, or in either direction when there is none (it says which).

Every result row offers **Show on graph**, **Start from these** (a new graph from those entities and one ring) and
**Add to a list** (purpose in plain words: *Watch these*, *Exclusion*, *Known good*, *Block*; a reason is
recorded).

**One catalogue, one entry per algorithm**, ordered by the question each answers. It is split by where a tool runs:

* **The On screen tab** works on the graph on screen. It holds every tool that runs in the browser, including those
  that can also run on the whole Dataset.
* **The Whole Dataset tab** lists the server algorithms (29 on a standard install). It runs them over the whole Dataset
  or the open Investigation's Working Set, whatever its size, with the answer summarised in words and *Highlight on
  canvas*. It also holds the tools that only read the server: *Find paths*, *Value Measures*, *Burst and periodicity*
  and *Link index*.
* **A tool on both tabs** shows only that tab's controls in each. *Explain node* runs its neighbourhood on screen; its
  link-index reads are in the Whole Dataset tab. *Pattern match* finds matches on screen, and its **Search whole
  Dataset** is in the Whole Dataset tab.

Each row says what it does in one line, and its limit (*Up to 2,500 nodes*; a server algorithm says *Instant up to
100,000 nodes*: above that it is queued). A row that also runs on the other tab is chipped *Also on Whole Dataset* or
*Also on screen*; a row the Industry pack suggests is chipped *Suggested*. Over the on-screen limit the On screen tab
says so (*too big to answer on screen: use the Whole Dataset tab*), and that tool's **Run on server** is then in the
Whole Dataset tab, listed with the same settings.

A button that cannot run yet stays focusable and says why, as its tooltip and as a line under it, in one of a few fixed
phrases: *Pick a node first.*, *Pick a From and a To node first.*, *Fill the required fields to run.*, *Searching…*,
*Open an Investigation first.* A tool that needs a time column says *Needs a time column: add one in Settings ▸ Graph
model, then pick it in the toolbar's time filter.*

The tools keep their results when you switch tabs.

On the Explorer level and on Basic, the Whole Dataset tab still runs the Dataset reads that need no Investigation:
*Find paths*, *Value Measures*, *Burst and periodicity* and the pattern's **Search whole Dataset**. One locked line
after them, *Run on the whole Dataset*, says what has the server algorithms.

Click a ranking row to select and centre that node. A long result has **Open in dock** (the dock's **Results**
tab). Each server algorithm field shows a plain name with a one-line hint under it (*Weight per hop*, *Risk origins*,
*Longest path*, *Direction*: *Both ways / Outgoing / Incoming*).

| Group | Answers |
|---|---|
| **Shortest path**, **All paths** | How are A and B connected? Parallel links between two nodes count as one route, shown with how many links it stands for. |
| **Explain node** | A node's neighbourhood, degree and role in plain words |
| **Centrality** | Who is most important or a broker: degree, betweenness, closeness, eigenvector, Katz, PageRank, HITS |
| **Communities**, **Connected components** | Natural clusters (label propagation, Louvain) and separate islands |
| **Cycles** | Money or calls that return to where they started, shortest first |
| **Cut points** | The single node or link that holds the network together |
| **Cohesive groups** | k-core, triangles, cliques |
| **Similarity & prediction** | Who looks alike; which links are likely missing |
| **Suspicion score** | An explainable 0–100 composite with a per-node breakdown; the top decile is highlighted |
| **Pattern match** | Find a shape ([§9](#9-pattern-matching)) |
| **Value Measures** | Who stands out by money flow: pass-through, velocity, cash-out concentration, structuring, benefit transfer |
| **Burst and periodicity** | When activity spikes, and what repeats on a schedule |
| **Propagated risk** (Server) | Risk spread from scored nodes over the links: *Weight per hop*, *Risk origins*, *Risk scores* (**Use risk scores**); a ranked table (*Score*, *Uncapped*, *Own risk*, *Contributors*) whose *Factors* expand to the contributing origins |

**Possible duplicates** (On screen ▸ Similarity) lists the 20 pairs of entities most likely to be one entity. Only
entities of the same Entity Type are paired, and only pairs not already linked to each other; a rare shared neighbour
counts for more than a busy hub, and each row gives its reason as the kinds of neighbour the pair shares (*shares
device, phone*). Clicking a pair highlights both on the canvas. It scores the graph on the canvas, not the whole
Dataset, with the same node limit as *Predict links*. Why only 20: when it was measured, all 20 top suggestions were
true duplicates, while below them about one suggestion in four was two people sharing an address or a home device, so
these are suggestions: read the reason before merging. Inside an Investigation, with *Manage Incidents and Cases*, each row has
**Merge…**, which fills the same form as **Merge** on the canvas toolbar ([§11.7](user-guide-la-core-investigations.md#117-identity-resolution)); a pair that
form would refuse (a masked alias, an entity with no type, or *Only entities in the open Investigation can be merged*)
shows a warning instead. Nothing is written until you confirm in ⚙ Settings ▸ Identities. On a graph where every node
has one kind, the list is plain link prediction.

**From a ranking to an Investigation.** Under a ranking, **Start an Investigation from the top results** queues the
top five nodes on the **Investigation** tab; nothing is created until you give the purpose and press **Start
Investigation with 5 queued seeds**. On Basic the hint says recorded Investigations are in Standard and above.

**Value Measures** read the whole Dataset in a window of at most 31 days (UTC), not the filtered picture. Choose the
Dataset and column roles (value, time, link kind) — prefilled from an open Investigation — a window and a Measure;
**Run Measure** stays disabled, with the reason, until the form is valid.

| Measure | Flags |
|---|---|
| **Pass-through** | Accounts forwarding nearly everything they receive (ratio; retention = 1 − ratio) |
| **Velocity** | Accounts that forward money within hours of receiving it |
| **Time to cash-out** | How fast received money reaches a cash-out |
| **Cash-out concentration** | An agent/till taking a large share of all cash-out, and from how many payers (can be limited to an agent Entity List) |
| **Structuring** | Payees collecting many in-band legs from several payers |
| **Benefit transfer** | A benefit payment passed on to one counterparty (default: at least 5 recipients) |
| **Value-weighted links** | Total value and count per pair (a weighting, not a test) |

---

## 9. Pattern matching

**Pattern match** opens on the first pattern, with its description; pick a **Pattern** to pre-fill the steps, edit
the thresholds (always visible) and run it with **Find matches**. *Custom pattern* says *Pick a pattern or build your
own. A step with no link kind matches any link.* A step list can also be shown as **Conditions** (start Entity Type,
then direction, link kind and Entity Type per step).

**Use selection as pattern** (`LA-PRODUCT-NEXT-1` phase 5a, 2026-10-30; `pattern-from-selection.ts`). Box-select two
or more entities on the graph, then press it beside the Pattern picker. The selection and the links among them load as
a motif you can loosen and run with **Find matches**:
- **A chain** (each entity linked to the next) loads as steps:
  - each step's direction, link kind and Entity Type come from the selection; an untyped entity is a wildcard;
  - the chain is read the way most of its links run;
  - with a time column, a hop that happened after the previous one gets an **After the previous hop** chip. Remove the
    chip to drop that order.
- **A star** (many entities linked to one) loads as branching stages:
  - *many send to one*, *one sends to many*, or both in turn. "In turn" means after the previous stage, and only when
    every outgoing link came after every incoming one;
  - each stage's breadth is the number of selected counterparties;
  - a value band `[min, max)` is added only when the legs carry exactly one numeric column besides the time.

Anything else is refused in plain words: a selection that isn't all linked, a mixed shape, or a star with two-way links.
Three entities are always read as a chain: a two-leg fan-in (`s1 → hub ← s2`) loads as an outgoing step followed by an
incoming one, not as a star. Each stage shows its link kind, Entity Type and order as removable chips. The chips show
on packs too.

Calls made:
- **Order only, never the example's exact gaps or windows:** those would fit this one example alone.
- **No hidden constraint:** every constraint a derived step or stage carries (order, link kind, Entity Type) is shown and removable, for
  packs too.
- ***Search whole Dataset* stays where it was** (a truncated graph on the Server side); a derived motif runs there like
  a pack.
- **In an Investigation, a derived fan runs in the browser over the drawn graph.** The over-the-Investigation server
  run stays a pack-only path.

| Pack | Looks for |
|---|---|
| **Layering chain** | Money passed through a run of intermediaries |
| **Pass-through intermediary** | Accounts that receive and immediately forward |
| **Inbound collector** | Many payers into one account |
| **Call-forwarding relay** | Calls bounced through a relay |
| **Circular flow** | Value that loops back |
| **Shared associates** | Different entities sharing the same contacts |
| **Layering network (split and merge)** | Money split across three or more parallel intermediaries that re-converge |
| **Circular financing (branching)** | Two successive split-and-merge rounds of value |
| **Structuring (smurfing)** | Many small deposits in a band (default 900 ≤ amount < 1 000) → a few intermediaries → re-converging |

The names above are the Industry pack's words. In neutral (Generic) words the call-forwarding pack reads *Relay chain
(any link kind)*, the cash-out Value Measures of [§8](#8-the-analysis-catalogue) read *Time to withdrawal* and
*Withdrawal concentration*, and the Measures form says *sender*, *receiver* and *Intermediary list*.

**Search around** (in the Pattern section: a *Steps / Search around* toggle) shows the same steps as plain conditions:
a start Entity Type, then per condition a direction, a link kind and a neighbour type, with the words spelled out, and a
**Search around** button that runs the same search as **Find matches**. It runs over the graph on screen only and
records nothing, like the pattern builder. The toggle is not there for a time-ordered pattern (*after the previous
hop*, a largest gap), which only the step list can show, nor for a branching pattern. On the telecom pack it shows for
*Call-forwarding relay*, *Circular flow* and *Shared associates*, and not for *Layering chain*, *Pass-through
intermediary* or the staged patterns. **Custom pattern** resets it to a blank two-step search. Not offered: searching
beyond the graph on screen, a neighbour type on Expand (Expand never follows a neighbour type), OR / branching and time
conditions.

A pack that pins link kinds needs a **link kind column**; one with a window or an order ("within N hours") needs a
**time column**. Left blank, they are filled from the Industry pack (Telecom: `link_kind` and `first_seen`, *Using
link_kind / first_seen for this pack*). With nothing to fall back on the run button is disabled and names what to set.
**If a tool says it needs a time column:** add one under **Attribute columns** in ⚙ Settings ▸ Graph model, run the
query again, then pick it as the **Time column** under **Filter edges by time**; the message is raised at the top of
the right panel. The browser cannot add a kind column after the graph is drawn: set the link type column and re-run.
When the data cannot answer the tool **says so and why** instead of "none"; when the loaded graph was truncated, the
Server side repeats the search across the whole Dataset.

---

## 10. Saving, exporting and the File menu

**File ▾** (the tool rail's first icon):

| Item | What it does |
|---|---|
| **New Investigation** (**New** without Investigations) | Closes what is open (unsaved work asks Save, Discard or Cancel). With Investigations it opens the start form in the Investigation tab ([§11.1](user-guide-la-core-investigations.md#111-start-one)); without them, back to *Start a graph* |
| **Open…** | One dialog, two tabs. **Saved views**: search, filter by Tag, Dataset and last change; **Open**, **Rename**, **Duplicate**, **Delete** (asks first), **Version history**, **Comments**, **Tags**, **Share…**. **Investigations**: the ones you may read plus **Recent**; filter by owner, Dataset, date and **Show** (All, Recent, Mine, Via a Case); type an id to open one directly. The owner shows by display name, the one they signed in with when they created the Investigation; an Investigation created before 2026-11-01 shows its owner's login id (your own show your name). The picked item's actions sit in the dialog's bottom bar beside **Close**, so they stay in view however long the list is; *Last changed* and *Created* are in your local time, as every other recorded time (`DATES-ONE-ZONE-1`). |
| **Explore Dataset…** | Opens ⚙ Settings ▸ Graph model with its guided Dataset picker: choose a Dataset and its From and To columns yourself ([§5.2](user-guide-la-core-building-graph.md#52-graph-model-draw-a-dataset-yourself)) |
| **Examples ▸ Follow the money** | Opens the Space's ready-made money-mule view and draws it now. Greyed out when the Space has none |
| **Save view** | Saves over the open view (with none open it asks a name). Saving, renaming, duplicating and deleting views needs the *Workbench author* permission; without it these items are greyed out and the menu says *Needs Workbench author.* under them. Every greyed-out File item says why (*Draw a graph first.*, *Open an Investigation or saved view first.*). A view shared with others may be deleted only by its owner |
| **Save view as** | A **Name** and optional description; names the lists the graph uses (the list it started from, the exclusion lists you added to) and says once that a saved view re-reads live data and is not evidence |
| **Save snapshot…** | Freezes what is on screen (the Working Set while an Investigation is open) with a SHA-256 fingerprint; can attach it to a Case or **create a new Case from graph nodes**. Greyed out with *Draw a graph first.* while nothing is drawn |
| **Build Dossier…** · **Members…** | Only while an Investigation is open: its Dossier ([§11.9](user-guide-la-core-investigations.md#119-evidence-snapshot-dossier-sealed-bundle)) or its Members ([§11.5](user-guide-la-core-investigations.md#115-replay-drift-drafts-and-members)) in a dialog. The same two are in the Investigation's `⋯` menu, and both stay in Investigation settings |
| **Import or export** | Moves views between Spaces as a file |
| **Close** | Back to the start; the page keeps its full height |
| **Delete** | Deletes the open view or Investigation; names it and asks first |

Without Investigations (Basic, or no Investigations add-on) the menu shows **Start an Investigation from this**
locked, with one line naming what has it.

A **saved view** keeps the graph's start, mapping, layout, display options, filters and the Entity Lists it uses.
Opening it draws its start again over the data as it is then — the list's members as they are now, the entities you
started from and expanded, each with one ring — and hides again whatever its exclusion lists hold now. A view saved
from **Explore Dataset…** reruns that query. Reloading the page reopens the open saved view (its id is in the address,
`?view=<id>`); Close, running a different query, investigating an entity, an Investigation replacing the graph and
importing a draft forget it, because the screen no longer shows that view, and a view that no longer exists is forgotten
after one warning. A graph drawn from the Start box or an imported draft is not reopened by a reload. A view names a *Case* only by attaching: a Case is the Incident-management
record you attach evidence to.

| Other ways out | Notes |
|---|---|
| **Attach to Case** (canvas toolbar) | One step: seals what is on screen and attaches it to a Case — an existing one, or **create a new Case from the selected nodes**; with an Investigation open you may also link the Investigation. If a step after the seal fails, the dialog names the sealed snapshot and **Retry** never seals twice. Needs *Manage Incidents and Cases*. A masked entity cannot become a Case member, and Reveal does not change that (it shows real identifiers on your screen only): link the Investigation to a Case instead (**Investigation settings… ▸ Case**). |
| **Export** (tool rail) | PNG, SVG, JSON, GraphML |
| **Widget** | A view or an Investigation's Working Set on a Dashboard ([§11.10](user-guide-la-core-investigations.md#1110-widgets-sharing-and-embed)) |

---
