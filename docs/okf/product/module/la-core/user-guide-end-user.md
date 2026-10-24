# Inspecto LA (Link Analysis) — End User Guide

*For analysts who investigate fraud, revenue leakage, money laundering or telecom abuse. Written 2026-10-15 from a
hands-on run of the standalone Inspecto LA application as five Demo Users. It is the short, task-by-task guide;
the full reference (every control, limit and capability) is the [user manual](user-guide-manual.md).
Every control named here is a label you see on screen.*

---

## 1. What it does, in one minute

Link Analysis draws **who is connected to whom** from the rows of a **Dataset** — transfers between accounts, calls
between numbers. Each account or number is a **node**; each transfer or call is a **link**. You look at the picture,
run tools that point at suspicious shapes, and, when something matters, open an **Investigation**: a recorded,
replayable list of the steps you took that can be handed over as evidence.

Two modes, and the difference matters:

| | **Exploring** | **Investigation** |
|---|---|---|
| What it is | Query panel, canvas, toolbox over live data | A recorded op log over a *Working Set* of entities |
| Is it evidence? | **No.** Re-running it later may show something different. | **Yes**, once you build a Dossier (section 8). |
| Use it to | Look around, try ideas, find leads | Work a case you may have to defend |

---

## 2. Getting started: from a blank install to a first finding

About ten minutes, with the screens alone. The example is the one the product is tested with (see the
[onboarding live drive](tests-onboarding-live-drive.md)): a file of money transfers (`transfers.csv`) in which a
few accounts pass money along a chain, a file describing the accounts (`accounts.csv`), and one very busy merchant
that would otherwise hide everything.

1. **Open Inspecto LA.** On a new installation there is nothing yet, so the front page is **Getting started**
   (below). To look around first, **Try the sample** loads invented transfers and opens a ready graph. For your own
   data, **Create a Space**: name it after the data (*Mobile money*) and choose what kind of data it is.
2. **Add data.** You land on **Onboard a file**. Choose *Events between entities* and drop `transfers.csv`. The
   screen applies what it detected and says why; a choice that was only a guess is marked **A guess** - press
   **Confirm** or change it.
3. **Say what the columns hold.** `from_account` and `to_account` hold an **Account**; a link goes **From**
   `from_account` **To** `to_account`; pick the time and amount columns if the screen did not. Press **Create and
   run**. The result says how many transfers between how many accounts were kept, and anything set aside.
4. **Add details (optional).** **Add details about these entities** brings in `accounts.csv`; say which column
   identifies each account (`account_id`). Names and segments then show on the graph.
5. **Start from one account.** **Explore in Link Analysis** opens **Start from...** with your Dataset picked.
   Keep **One entity** and **Account**, type the first letters of the account you are worried about (`MULE`) and pick
   it from the list, which shows how busy each match is. **Start** draws it and everyone it is linked to.
6. **Grow and clean.** **Grow** (or **G**) shows what the next step adds before it adds it; very busy accounts are
   held, not drawn. An account that links to almost everyone (a big merchant) only hides the pattern: right-click it,
   **Exclude…**, and add it to a list whose purpose is *Never follow these (exclusion)*, so it stays out of every
   later step too.
7. **Find something interesting.** In the toolbox, **Analysis** offers three plain questions: **Who is busiest**,
   **Which groups stick together** (the chain shows as one group) and **How does A reach B** (from the victim's
   account to the cash-out account, the path the money took). Each answer is one sentence, with **Show on graph**,
   **Start from these** and **Add to a list**.
8. **Save as a case.** **Save as a case** (the bookmark) asks a name, *Mule ring*. A case keeps the graph's start and
   the lists it uses; it re-reads your data when it opens, so it is not evidence.
9. **Come back.** The front page now shows **My work**: open *Mule ring* to draw it again over the data as it is
   then. **What changed in your data** says when each file was last brought in; **Add a file** on the Dataset card
   brings in next month's file.

**When you are stuck.** **What can I do next?** (the light bulb in the left menu) is on every screen: it reads what
your Space already holds and offers the next step, with one line saying why, and the Space guide. A word with a
**?** beside it (*Dataset*, *Entity Type*, *Entity List*, *case*) explains itself in one sentence when you press the
**?**. An empty page always says why it is empty and offers the one thing that fills it. Something your edition or
your role does not include is shown greyed out with one line naming what has it; it is never a button that fails.

**Beginner counters.** Inspecto counts, on this server only, how far first-time users get: when it was first started,
when the first Space, the first Dataset and the first graph appeared, when the Space guide was finished, and at which
step people stopped. Nothing else is kept (no names, no values) and nothing is sent anywhere. Administrators see it
in **Settings → Getting started counters**.

---

## 3. Signing in and finding your way

1. Open the application and pick yourself on the sign-in page. A demonstration install lists Demo Users with no
   password; a secured install shows **Sign in with SSO**.
2. The front page offers **Link Analysis**, **Geo**, **Entity Lists** and **Alerts** (and **Settings** if you may
   change them). The same areas sit in the slim menu down the left edge, with the Space switcher and your user menu.

### The Getting started page (a new install)

Until your Space holds data, the front page is **Getting started** instead of the area cards. It appears while
there is no Space yet, or the current Space has no Dataset yet (a table Inspecto keeps). Three ways in:

* **Try it with sample data** — **Try the sample** creates a Space called *Sample: invented data*, loads three
  days of invented money transfers with a planted money-mule ring, and opens Link Analysis with the ring drawn
  (the `la-sample` Space Template; nothing in it is real). If part of the data does not load, the card names what
  failed and still offers **Open the sample Space**.
* **Use my own data** — **Create a Space** asks two things: a **Name** (one Space per kind of data, such as
  *Mobile money* or *Call records*) and **What kind of data?**: *Mobile Money*, *Telecom* or *Generic*, each shown
  with three example column names. The choice sets the words Link Analysis uses for your data (the Space's Domain
  Profile). Then you land on **Add data** (below). When a Space already exists, the button is **Add data** itself.
* **Open my team's data** — shown when Spaces exist: the Space switcher, to move to the Space your team uses.

A choice you cannot take is greyed out with one line saying why: with sign-in, only an administrator may create a
Space; a server running a single Space cannot add a sample one.

The **Space switcher** in the left menu works the same way from the first minute: with no Space yet it reads
**No Space yet** and offers **New Space…** and **New from sample…** to anyone who may create a Space (everyone
when nobody signs in; with sign-in, Space administrators).

**The Space guide.** Under the choices, and later above the area cards, the **Space guide** lists the setup steps
and ticks each one from what the server holds: **Space** (one is open), **Data** (a Dataset exists), **What the
columns hold** (a Dataset says which columns hold accounts, numbers or other things), **First graph** (a graph is
saved in the Space) and, optional, **A list** (an Entity List exists). Click a line to go to that step; collapse
the guide with the arrow (the choice is remembered in this browser). It disappears once the four required steps
are ticked.

**My work.** Once the Space holds data, the front page shows **My work** under the Space guide:

* **Cases** - every case saved in the Space (section 6, *Save as a case*), the most recent first, with when it was
  saved and the Entity Lists it uses (*Lists: started from Suspects; leaves out Busy merchants*). Click one to open
  its graph. A case re-reads your data each time it opens: it is not evidence.
* **What changed in your data** - one line per Dataset you brought in with **Add data**, its last import first:
  *transfers: imported 1 Oct 2026, +2,104 rows*, or after another file *transfers: re-imported 3 Oct 2026, 2,300
  rows loaded* (the line counts what the run loaded; it does not say whether the new file replaced the rows or was
  added to them). Rows a run set aside are counted too. Data a Pipeline keeps up to date is not listed here.

### Add data: onboard a file

**Add data** opens the screen **Onboard a file**. It turns one of your files into a Dataset that Link Analysis can
draw, on one screen, in four steps:

1. **Drop or choose a file.** First say what is in it: *Events between entities* (transfers, calls, messages) or
   *Details about entities* (customers, accounts, devices). Then drop the file on the box or press **Choose a
   file**. The screen lists what it reads (CSV or TSV, Parquet, JSON or NDJSON; Excel workbooks when this
   installation includes Excel support, otherwise save the sheet as CSV) and this Space's limits (for example *up to
   500 MB and 10,000,000 rows per file; 20 imports per Space*) before anything is sent. *Read from a database or a
   remote system* is shown locked on Basic, with the reason.
2. **Check the columns.** What was detected is already applied, with the reason beside it ("every one of 1000
   sampled lines splits into 6 fields on ','"). A choice that was only a guess is highlighted and marked **A
   guess**: press **Confirm**, or change it. You can rename a column, change its type or **Leave out** a column; a
   table shows the first rows as they will be kept. Choosing another separator, header line or quote reads the
   file again.
3. **What does each column hold?** For each column, the kind of thing it holds (an *Entity Type*, such as Account or
   Phone number) or *Nothing: a detail*. For events: which column a link goes **From** and **To**, and optionally its
   kind, time and amount. For details about entities: **Which column identifies each entity?**. And the main kind of
   thing in the file. Without a From and a To the screen warns that Link Analysis cannot draw the Dataset as a graph.
4. **Create and run.** The Dataset name is filled in from the file name (lower-case letters, digits and `_`).
   One sentence says what will happen; **Create and run** waits until every guess is confirmed or changed. A very
   large file runs on the server, and the screen follows it until it ends.

The result says what happened in plain words: how many rows were read and kept, rows set aside and why, whether the
link index was built, and each check (Expectation) on the data. Then:

* **Explore in Link Analysis** opens Link Analysis with the new Dataset picked in **Start from...** (section 5), so you
  start from one entity, a list or the whole Dataset.
* **Add details about these entities** starts again for a file that describes them (such as `accounts.csv`).
* **Add another file** starts again.

**The Dataset card** (on the result, and on the Add data page for every Dataset already imported into the Space)
says *one-time import* and shows the last import. **Add a file** brings in the next file (for events, choose
whether it *replaces the data* or *adds its rows*; details about entities are always replaced) and **Run now**
runs the import again. **Keep this up to date** is locked on Basic: there, an import runs only when you add a file
or press Run now. On a secured installation it says *Ask an administrator to keep this up to date*.

If adding data is not allowed in this Space, the screen says why instead (for example *Add data is turned off in
this Space: its data comes from Pipelines - ask an administrator to turn it on*). With no sign-in it is allowed
unless someone turned it off for the Space; with sign-in an administrator turns it on per Space (**Settings → Add data**, *People may add data in this Space*; the
locked screen points an administrator there), and you need the right to upload data.

**Entities: what your data holds.** The **Entities** page shows the kinds of thing your data holds (accounts, phone
numbers, devices…), called **Entity Types**: only the ones some column of your data holds, the main one of each
Dataset first. The others wait, folded, under **Not in your data**. Its **Columns** tab (where the guide's *What the
columns hold* step takes you) lists each column that looks like an identifier with what it holds or **Not set**:
pick an Entity Type to set it (two columns holding the same type join up in the graph) and the page says what
changed. The picker beside each Dataset name is its **primary** Entity Type. **New Entity Type** adds one your data
needs: a name, an example value (the page shows how it will be compared) and how to compare values. These changes
need the right to edit Datasets when you sign in; without it they are greyed out with the reason.

**What you can do depends on your role.** If a button is greyed out, hover it: it says which capability is missing.

| You want to | You need |
|---|---|
| Explore, analyse, export | Just sign-in |
| Start an Investigation, save an analysis, link a Case | *Incident management* |
| Approve a held large expand | *Approve link expansions* (and never your own) |
| See real account numbers instead of aliases | *Reveal* rights |
| Change limits and masking | *Settings* rights |

---

## 4. The screen

* **Left panel, Query:** which Dataset to draw, and which columns are the two ends of a link.
* **Middle, Canvas:** the graph. Drag to pan, scroll to zoom, click a node for its details, drag a node to move it.
  **Fit the graph to the screen** re-frames everything.
* **Right panel, Toolbox:** three tabs: **Analysis** (the tools), **View** (layout and display) and
  **Investigation** (your recorded work).
* **Top bar:** search, filter, layout, **Save this analysis**, **Attach to Case**, saved views, undo and redo,
  **Show as list**, full screen and **Export**.
* **Bottom bar:** how many nodes and links are drawn, and the **Data** strip showing the rows behind the graph.

On a narrow window the panels stack; open the Toolbox and scroll down to it. A window about 1300 px wide or more
shows all three panels side by side.

**Masked ids.** If you see names such as `masked:1d4d76be…`, your Space hides sensitive ids. The same alias always
stands for the same account, so you can still group, count, search and follow it. The query panel says
**Masking: typed (ids shown as aliases)**. Someone with Reveal rights can turn an alias back into the real id.

---

## 5. Start here: Start from...

The first screen is one **Start from...** box. Pick the **Dataset** first (the one you just added is already
picked when you come from *Explore in Link Analysis*), then what to start from:

* **One entity** - pick the **Entity Type** first (the Dataset's main one is already chosen: accounts for
  transfers, phone numbers for calls), then type the value. As you type, matching values appear with how busy they
  are: *MULE-HUB-01 - 10 links, first seen 3 Sep 2026, last seen 30 Sep 2026*. Any Entity Type works - an account is
  no longer refused as "not a phone number". A masked type suggests nothing: type the whole value - or, if you may
  reveal masked values, choose *Show its values* (each look-up is recorded). The Entity Type you pick is what the
  graph is drawn as, also when it is not the Dataset's main one.
* **A list** - pick an Entity List (suspects, a watch list).
* **A paste** - paste values, one per line (commas and semicolons work too).
* **The whole Dataset** - draws every link at once. When the Dataset holds more entities than the node cap, the
  box warns you first: the graph is cut to the busiest, and starting from one entity is usually clearer.
* **The busiest, or a group** - once a graph is drawn, *Find something interesting* (section 7) answers who is
  busiest and which groups stick together; **Start from these** on a result starts a new graph from those entities.

**Start** draws what you chose and everything linked to it - **one ring** - with busy numbers held (see *Grow and
clean the graph* below), so **Grow** continues from there one step at a time. A line above the graph says what was
drawn and lists any value that is not in the Dataset. A list or a paste draws at most 25 values in one start.

With Investigations (Standard and above, with the add-on), **One entity** seeds an Investigation instead: state the
**Purpose / legal basis** and press **Investigate**; it creates the Investigation, seeds the entity and draws one
degree of everyone it is linked to. A list or a paste is drawn as above and queued as the first seeds of the
Investigation you start.

**Investigate** on an Entity's page takes the same path: it starts from that entity, never from the whole Dataset.

### 4.1 Use an example or a saved view
Under the box, **Follow the money (example)** opens a ready-made view and draws it at once. **Open a saved
view** lists your Space's views. A saved view re-reads live data; it is a starting point, not evidence. **Add data**
brings a file into the Space.

### 4.2 Draw a Dataset yourself
**Explore a Dataset** lets you choose the columns:
1. Under **Dataset**, choose one. The list shows the ones that look like links first, each with the reason.
2. The two **entity columns** are filled in for you. Change them if the link runs the other way. Optionally set a
   **Link type column** (channel, call type) and **Attribute columns** (amount, country, time).
3. Press **Run query**. The footer says, for example, *20 nodes · 20 links drawn*.

> **Tip:** the Purpose field is required. If the Investigation tab opens scrolled somewhere else, scroll to its top.

---

## 6. Looking at the graph

| To find | Use |
|---|---|
| A particular node | **Search nodes** (top bar) |
| Only some kinds of node | **Filter node types** |
| A better layout | **Graph layout** |
| Links in a time range | **Filter edges by time**; needs a time column among the **Attribute columns** |
| The rows behind the picture | **Show as list**, or the **Data** strip |

**Display options** (the brush) switch node and link labels, colours and shapes. A dotted outline in the legend marks
a node you have not expanded yet. **Undo** and **Redo** step through display and filter changes.

### Grow and clean the graph

* **Grow** (or press **G**) adds the next ring of linked numbers. It opens on safe defaults — both ways, one step,
  busy numbers held — and tells you what the step would add before anything changes. Press **Grow**, or **Cancel**.
  After it, one line says what happened, with **Undo** (or **Ctrl+Z**). In a shared Investigation, Undo takes back
  only the step it names: if a colleague recorded a newer step meanwhile, nothing is undone and the log shows theirs.
* **Busy numbers** — call centres, switchboards, shops — link to thousands of others and hide the real links. Grow
  holds them: they stay on the graph with a ring and a *held* badge, listed in the **Held** tray. **Remove held**
  takes them all out in one step; **Expand anyway** grows through one of them.
* **Right-click a node** (or press **X** with nodes selected) → **Exclude…**: *From this Investigation only*, or
  *Add to a list…* so it stays out of every later step too. Some lists are kept by your administrator (VIPs,
  politicians); you can use them, not change them.
* **Hidden** (tool rail) shows the lists in force — "41 numbers hidden by 3 lists" — lets you switch one on or off,
  and shows what was removed.

In an Investigation each of these is a recorded step; without one (Basic) they change only what you see. The first
time each panel opens, a short note explains it; **Got it** hides the note in this browser.

### Save as a case

A **case** is a graph you keep: a named view plus the Entity Lists it uses. **Save as a case** (the bookmark in the
top bar) asks a **Name** and an optional description, names the lists the graph uses - the list it started from and
the exclusion lists you added entities to from it - and says once that a case re-reads live data and is not
evidence. The case appears under **My work** on the front page. Opening it draws its start again over the data as
it is then: the list's members as they are now, the entities you started from and grew, each with one ring; then
whatever its exclusion lists hold now is hidden again. A case saved from **Explore a Dataset** reruns that query.

Without Investigations (Basic, or a signed-in install without the Investigations add-on), the menu also shows
**Start an Investigation from this** locked, with one line naming what has it. With Investigations, the
Investigation tab starts one as before. To keep a frozen copy, use **Save this analysis** (section 8).

---

## 7. The analysis tools (Toolbox → Analysis)

**Find something interesting** sits at the top of the Analysis tab: three plain questions about the graph on screen.

* **Who is busiest** - the five entities with the most links on this graph, each read in one sentence (*MERCHANT-BIG
  takes part in 270 transfers, the most on this graph.*). When the busiest one has at least 5 links and twice the links of the
  next, it asks: *MERCHANT-BIG has 270 transfers. Add it to an exclusion list?*
* **Which groups stick together** - groups of three or more that link mostly among themselves (*Group 1: 9
  accounts, 14 transfers among them within 3 days*; the days are said when a time column is chosen).
* **How does A reach B** - choose **From** and **To**: the shortest chain following the links' direction, or in
  either direction when there is none (it says which).

Every result row offers **Show on graph** (highlights it), **Start from these** (a new graph from those entities and
one ring, as the Start box draws) and **Add to a list** (an existing list of the same Entity Type, or a new one with
its purpose in plain words: *Watch these*, *Never follow these*, *Known good*, *Block*; a reason is recorded). An
exclusion list you add to becomes one of the case's lists, and without an Investigation its entities are hidden at once
(Undo brings them back).

The full toolbox follows. Open one group at a time; results stay while you switch tabs. Click a row in a ranking to select that node and
centre the canvas on it.

| Group | Use it to answer |
|---|---|
| **Shortest path**, **All paths** | How are A and B connected? |
| **Explain node** | What is this node's neighbourhood and role? |
| **Centrality** | Who is most important or a broker? |
| **Communities**, **Connected components** | What are the natural clusters and separate islands? |
| **Cycles** | Where does money or traffic return to where it started? |
| **Cut points** | What single node or link holds the network together? |
| **Suspicion score** | Which nodes look riskiest (0 to 100, with the reasons)? |
| **Pattern match** | Find a shape: a layering chain, a structuring fan-in, a circular flow |
| **Value Measures** | Which entities stand out by money flow: pass-through, cash-out concentration, structuring, benefit transfer |

**If a tool says it needs a time column.** Pattern packs that care about order (such as *Layering chain*) need a time
column. Add one under **Attribute columns** in the Query panel, run the query again, then pick it as the **Time
column** under **Filter edges by time**. The message appears at the top of the Toolbox; it scrolls into view when it
is raised.

**Value Measures** read the whole Dataset in a window of at most 31 days, not the filtered picture. Inside an open
Investigation the Dataset and columns are filled in from it; you choose the Measure, the value column and the window.

---

## 8. Working a case: Investigations

Open an Investigation when a lead is worth defending. From a ranking, **Start an Investigation from the top
results** queues the top five nodes as seeds; from a number, use section 5.3; or use the **Investigation** tab.

**What you do inside it.** Each of these is recorded as a numbered step with who, when and why:
* **Seed** entities, then **Expand next degree** (one hop) or **Expand the whole Working Set**.
* **Exclude** an entity (give a reason), **Annotate** it, set a **Time window**, apply a **Threshold**.
* **Identity resolution:** state that two identifiers are the same person, with a reason. It is reversible.
* **Fork a Draft** to try a side line without touching the main log, then **Promote** or **Discard** it.

**Large expands need a second person.** An expand above the Space's limit does not run. It says **waiting for
approval** and names the request (for example *p1*). A different person with approval rights opens the same
Investigation, finds the request in the **Oversight** card at the bottom of the Investigation tab, and presses
**Approve** or **Deny**. Approving runs the expand as your step, stamped with who approved it. Nobody can approve
their own request.

**Evidence.**
1. **Save this analysis** freezes what is on screen as a **snapshot**, with a SHA-256 fingerprint, and can attach it to a
   Case. A *saved view* re-reads live data; a *snapshot* does not change.
2. **Build dossier** produces the document: the steps, who took them, and a check that the log is intact. Masked
   ids stay masked, even inside free-text reasons.
3. **Verify this manifest** confirms the Dossier still matches what is stored. **Download sealed bundle** gives one
   file for someone outside the system; any edit to it breaks its seal.
4. **Compare and seal** compares two time windows (which links and entities appeared or changed) and seals the difference as a step.

**Cases.** *Attach to Case* needs *Incident management*. *Create a new Case from graph nodes* needs real, unmasked
entities: on a Space that masks accounts it offers no node, and the dialog says so. Link the Investigation to a Case
instead, or ask for Reveal rights.

---

## 9. Entity Lists, Alerts and Geo

* **Entity Lists:** named watch lists of accounts or numbers, with every change on the record. With an Investigation
  open, click an entity on the canvas and **Add selection** to a list; without one, use **Add to a list** on a *Find
  something interesting* result. A Space with no list yet is offered **first lists to start with** from its kind of
  data (a telecom Space: *Call centres* to leave out, *Lines to watch*; mobile money: *Merchants*, *Wallets to
  watch*; otherwise *Busy entities to leave out* and *Entities to watch*). **Create** opens *New list* filled in.
* **Alerts:** fired Alerts, and a way into the Investigation behind a standing detection.
* **Geo:** places entities and routes on a map, over a Dataset with coordinates.

---

## 10. Worked example: a money-mule ring

1. Press **Follow the money (example)**. About 112 accounts are drawn.
2. Toolbox → **Suspicion score** → **Score nodes**. The hub and its relays sit at the top.
3. **Pattern match**, pick **Structuring**, **Find matches**: the hub receiving many small payments from different payers.
4. Press **Start an Investigation from the top results**, state your purpose, and expand one degree.
5. **Save this analysis**, then **Build dossier** and **Verify this manifest**. Send the sealed bundle to the reviewer.

---

## 11. When something looks wrong

| You see | It means | Do this |
|---|---|---|
| A button is greyed out | You lack the capability named in its tooltip | Ask your administrator |
| *No preview on this server* in Grow | The server is older than the Grow preview | Grow anyway; the result line says what it added, with Undo |
| `masked:…` everywhere | Your Space masks sensitive ids | Ask for Reveal rights if you need real ids |
| *Waiting for approval* | The expand is above the four-eyes limit | Ask a second approver to open the Investigation |
| A red *Investigation is not available* | It does not exist, is not yours, or its Dataset is no longer shared with you | Ask its owner to add you as a member |
| *No node on this graph can become a Case member* | The entities are masked | Link an Investigation to the Case, or ask for Reveal rights |
| A tool says it needs a time column | The pattern is time-ordered | See the tip in section 7 |
| *The analysis was not saved* with a reason | The server refused it; the reason is shown | Read the reason; usually a missing capability |
| The graph stops at a node limit | A cap protects the browser | Narrow the query, or use the *(server)* variant of the tool |

---

## 12. Words you will meet

| Word | Meaning |
|---|---|
| **Dataset** | A table of rows Link Analysis reads |
| **Entity / node / link** | An account, number or device; a transfer or call between two of them |
| **Working Set** | The entities currently in an Investigation |
| **Op log** | The recorded list of steps in an Investigation |
| **Draft** | A side line forked from the main log |
| **Snapshot** | A frozen copy of an analysis, with a fingerprint |
| **Dossier** | The evidence document built from an Investigation |
| **Entity List** | A named watch list |
| **Measure** | A computed figure over a Dataset, such as pass-through ratio |
| **Alert Rule** | A saved condition that raises an Alert when a Measure crosses a level |
| **Space** | Your team's workspace, with its own Datasets, members and settings |
