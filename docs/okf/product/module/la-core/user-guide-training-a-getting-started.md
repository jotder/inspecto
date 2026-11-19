---
type: Guide
title: "Inspecto LA training videos — A · Getting started: narration scripts"
description: "The spoken script of the training episodes in group A (Getting started), scene by scene, with the action on screen and the change it causes. Format and status: the overview, user-guide-training-scripts.md."
resource: tools/render-la-demo-video.mjs
tags: [module, la-core, link-analysis, user-guide, training, video]
timestamp: 2026-11-16T00:00:00Z
item: module.la-core
audience: internal
status: current
verified: 2026-11-18 last-edit
code: la/inspecto-la-core
---

# Inspecto LA training videos — A · Getting started

> Narration scripts for group A of the Inspecto LA training series. How to read an episode, the voice and the
> renderer: [the overview](user-guide-training-scripts.md). Draft, not recorded, not reviewed by product; `[check in UI: …]` marks a detail to
> confirm on the live product before recording.

## Episodes in A

| Episode | Title | Edition | Scenes | Spoken words | UI checks open |
|---|---|---|---|---|---|
| [A1](#a1_what-inspecto-la-is-and-a-tour-of-the-screen) | What Inspecto LA is, and a tour of the screen | Basic | 12 | 992 | 6 |
| [A2](#a2_spaces-the-home-page-and-saved-views) | Spaces, the home page and saved views | Basic | 12 | 850 | 7 |
| [A3](#a3_your-data-datasets-columns-and-mappings) | Your data: Datasets, columns and Mappings | Basic | 11 | 847 | 8 |
| [A4](#a4_entities-and-entity-types) | Entities and Entity Types | ? | 11 | 815 | 6 |

## A1_What-Inspecto-LA-is-and-a-tour-of-the-screen

> **A1 · What Inspecto LA is, and a tour of the screen** · Basic · Other Examples Space · the money-mule layering ring ·
> 7 min · user guide §1, §2 (*When you are stuck*), §4, §4.1, §17

- **Objectives:** say what Inspecto LA draws and why; use the ten *About* words as the product uses them; find every
  part of the screen (left menu, header toolbars, canvas, toolbox, dock, ⚙ Settings) by name.
- **Before:** a fresh unzip of `la-basic.zip`, the Other Examples Space, the home page. The left menu collapsed (icons
  only), the toolbox open on its **On screen** tab, the dock collapsed to its tabs.
- **Covers (plan §5):** 5.1 home link, Areas, *What can I do next?*, Space switcher (named only), user menu (Colour
  scheme), navigation collapse/expand and resize; 5.2a Link Analysis title, About (the ten terms, 5.7), File, Search
  nodes, Filter node types, Layout, Filter links by time, Industry pack glossary, Display options, Undo/Redo display
  change, Fit to screen, Export, Show the table, Full screen, Settings, Show/Hide the toolbox; 5.2b the canvas toolbar's
  groups (named only) and More canvas tools; 5.3 toolbox tabs and Quick questions (Busiest nodes); 5.2h dock (Rows,
  Results, resize, collapse); 5.4 the ⚙ Settings sections (named only); 5.6 `?` keyboard shortcuts.
- **Try it:** open *About Link Analysis* and find the word for what an Investigation currently shows; then ask *Busiest
  nodes* and press *Show on graph* on the first answer. Which account lights up? *Answer: Working Set; MULE-HUB-01, the
  hub account.*
- **Check:** is a saved view evidence? *Answer: no. Opening it re-reads live data, so it is a starting point; evidence is
  an Investigation's Dossier, in Standard.*
- **Gotchas:** Basic shows one toolbox tab (**On screen**) and the dock's **Rows** and **Results** only; the Whole Dataset
  and Investigation tabs, Geo, Entities and Alerts are Standard, and the voice says so rather than showing them. This
  Space runs the telecom Industry pack, so some answers count transfers as "calls". This saved view carries no date
  column (`PAYEE_COUNTRY` only), so the clock's menu says *No date or time column on these links.*; time filters are B4.
  Full screen and the canvas toolbar appear only once a graph is drawn.

### S01 · Who is connected to whom
- Voice: This is Inspecto LA training, episode A1: what Inspecto LA is, and a tour of the screen. Inspecto LA draws who is connected to whom from the rows of your data: transfers between accounts, calls between numbers. Fraud rarely shows in a single row. It shows in the shape many rows make together, and that shape is what this product puts on the screen. In this episode you learn the ten words the product is built on, and where everything on the screen lives. We are in the Basic edition, in the Other Examples Space.
- Action: intro card; then the home page of the Other Examples Space.
- Impact: none.

### S02 · The left menu
- Voice: Down the left edge is the menu, on every page. Inspecto LA home, at the top, takes you back to the home page. Under it are the areas: Link Analysis, where you draw graphs; Data, where your Datasets live; Entity Lists, for the entities you want to watch or leave out; and Settings. The Standard edition adds Geo, Entities and Alerts. On the Link Analysis page, Expand navigation, before the title, shows the labels, and Collapse navigation folds them back to icons. While the menu is expanded, drag its edge to make it wider.
- Chip: Left menu · Expand navigation
- Action: hover Inspecto LA home and each area icon (tooltips) ▸ open Link Analysis ▸ Expand navigation (double arrow before *Link Analysis*) ▸ drag the menu's edge from about 224 px to about 300 px ▸ Collapse navigation. [check in UI: the Basic menu lists Link Analysis, Data, Entity Lists and Settings, and no Geo, Entities or Alerts]
- Impact: before/after hold: icons only → icons with labels → a wider menu → icons only. Mark each area as the voice names it.

### S03 · Three helpers at the foot
- Voice: Three things sit at the foot of the menu. The light bulb is What can I do next?. It reads what this Space already holds, and offers at most three next steps, the recommended one first, each with one line saying why. This Space has data and saved graphs but no list yet, so it suggests Keep a list. Under it, the Space switcher names your Space; episode A2 covers it. The person icon is the user menu: its Colour scheme follows your system, or fixes Light or Dark.
- Chip: What can I do next? · User menu
- Action: What can I do next? ▸ hold on the dialog ▸ Close ▸ point at the Space switcher ▸ user menu ▸ Colour scheme: Dark ▸ Match system. [check in UI: on a fresh Basic unzip the dialog offers *Keep a list* (no Entity List in the Space); if it offers *Open a saved view*, *Find something interesting* and *Add data* instead, the voice says "it offers three steps, the recommended one first" and drops the Keep a list sentence]
- Impact: mark the recommended step and its *why* line; before/after hold of the scheme change.

### S04 · Open a graph, then learn its words
- Voice: Open Link Analysis. From the File menu choose Open…, and the saved view Money-mule layering ring. Twelve small accounts pay cash into one hub account, which passes it on through two relays to an offshore account. Before reading it, learn its words. The question mark near the right end of the header, About Link Analysis, defines the ten words this page is built on, and the whole series uses them exactly so.
- Chip: File · Open… · About Link Analysis
- Action: File ▸ Open… ▸ Saved views ▸ *Money-mule layering ring* ▸ Open ▸ wait for the draw ▸ About Link Analysis (the `?` icon between Full screen and Settings). [check in UI: the view's title as the Open dialog lists it]
- Impact: the ring drawn; mark MULE-HUB-01, the two relays and OFFSHORE-77; then the About dialog opens.

### S05 · Five words for the picture
- Voice: An Entity is a thing on the graph: a caller, an account, a device, each with an Entity Type. A Link is a call or a transaction between two Entities, with details such as its time and amount. A Dataset is a table of records; Link Analysis draws its graph from the rows of a Dataset. The Graph model is how those rows become a graph: which column is the From Entity, which the To Entity, and which give the kind of link. And Expand adds the Entities linked to the ones you pick, one degree at a time.
- Chip: About Link Analysis · Entity · Link · Dataset · Graph model · Expand
- Action: scroll the About dialog through the first five terms, one at a time.
- Impact: mark each term as the voice reads it; split screen: point at an account (Entity) and a line (Link) on the canvas behind the dialog.

### S06 · Five words for keeping work
- Voice: A Saved view is a named graph you keep. Opening it re-reads live data, so it is a starting point, not evidence. An Investigation is a recorded line of enquiry: an ordered, replayable log of your steps, each with who, when and why. The Working Set is what an Investigation currently shows. A Draft is your private copy of an Investigation's work, and a Dossier is its sealed report, with a chain of custody. In Basic you are always exploring: you look and try, and nothing is recorded. The last four words belong to the Standard edition.
- Chip: About Link Analysis · Saved view · Investigation · Working Set · Draft · Dossier
- Action: scroll to the last five terms ▸ Close.
- Impact: mark each term; mark *not evidence* in the Saved view definition.

### S07 · The first toolbar: file and view
- Voice: Now the header. After the title, the first toolbar works on the file and the view. File opens, saves and exports your work. Search nodes finds a node by its name. Filter node types and Filter links by time narrow what is drawn, and Layout rearranges it. The book, Industry pack glossary, explains this Space's own terms. The brush, Display options, changes how things look, with its own Undo and Redo. Then Fit to screen, Export, and Show the table, which opens the rows behind the picture.
- Chip: Tool rail · File · Display options
- Action: hover each icon of the first toolbar in order: File, Search nodes, Filter node types, Layout, Filter links by time, Industry pack glossary, Display options, Undo display change (Ctrl+Z), Redo display change (Ctrl+Y), Fit to screen, Export, Show the table. [check in UI: the glossary book shows for this Space's telecom pack, and Clear search and filters stays invisible while nothing is filtered]
- Impact: mark each tooltip as it appears.

### S08 · The second toolbar: the canvas
- Voice: The second toolbar works on the canvas. Canvas mode decides what a drag does: move the view, draw a lasso, or move a lens. Then the selection tools, and a count of what is selected. Then the graph edits: Expand, Collapse, Hide and Remove. The broom is Clean up. The last two icons are Held, the busy entities an expand skips, and Hidden by lists. On a narrow screen these fold into More canvas tools, the three dots.
- Chip: Canvas tools · Canvas mode · Clean up
- Action: hover Pan, Lasso, Fisheye, Link filter ▸ the four selection icons ▸ Expand, Collapse, Hide, Remove ▸ Clean up (broom) ▸ Held ▸ Hidden by lists; then narrow the window until More canvas tools (`⋯`) appears and open it ▸ restore the width.
- Impact: mark each group in turn; before/after hold of the fold into `⋯`.

### S09 · Full screen, Settings and the toolbox toggle
- Voice: At the right end, Full screen gives the graph the whole display. The gear is Settings: one dialog for what you set rarely. Its sections are listed on the left, each with a line of help: under This graph, the Graph model, Entity Types and Display rules; under Data, Entity Lists, Identities and Add data; and Installed modules. The last arrow, Hide the toolbox, gives the canvas more room, and Show the toolbox brings it back.
- Chip: Full screen · Settings · Hide the toolbox
- Action: Full screen ▸ hold ▸ Exit full screen ▸ Settings ▸ hover each section's help line ▸ close ▸ Hide the toolbox ▸ Show the toolbox. [check in UI: *This Investigation* is absent from the Settings sections while no Investigation is open]
- Impact: before/after holds: full screen; the Settings dialog; the canvas widening when the toolbox hides.

### S10 · The toolbox and Quick questions
- Voice: On the right is the toolbox. In Basic it has one tab, On screen: tools that run on the graph you can see. Standard adds the Whole Dataset and Investigation tabs. At the top are three Quick questions. Press Busiest nodes. The answer is a sentence, not a score, and the hub account comes first. Each answer offers Show on graph. Below the questions is the whole catalogue, ordered by the question each tool answers; category D covers it.
- Chip: Toolbox · On screen · Busiest nodes
- Action: toolbox ▸ On screen ▸ Quick questions ▸ Busiest nodes ▸ hold on the first answer ▸ scroll the catalogue's group titles. [check in UI: MULE-HUB-01 is the first answer, and its sentence counts "calls" under the telecom pack]
- Impact: mark the tab strip, the first answer's sentence and its three buttons.

### S11 · The dock
- Voice: Along the bottom is the dock. Rows shows the links and nodes behind the picture as a table; click a node row, and the graph selects that node. Results holds a long answer from the toolbox when you send it there. Drag the dock's edge to resize it, or collapse it to its tabs: it remembers both in this browser. And whenever you forget a key, press the question mark key for the list of keyboard shortcuts.
- Chip: Dock · Rows · Results
- Action: Show the table ▸ Rows ▸ Nodes ▸ click the MULE-HUB-01 row ▸ drag the dock's top edge up ▸ Collapse the dock ▸ Results (one line: *Long algorithm results open here…*) ▸ press `?` with the canvas focused ▸ Close.
- Impact: before/after hold: the row click selects MULE-HUB-01 on the canvas; mark the tab strip; then the Keyboard shortcuts dialog.

### S12 · Try it
- Voice: Your turn. Open About Link Analysis and find the word for what an Investigation currently shows. Then ask Busiest nodes, and press Show on graph on the first answer. Which account lights up? The answers are in the description. Next, in A2, Spaces, the home page and saved views.
- Action: outro card: *Try it* and *Next: A2 · Spaces, the home page and saved views*.
- Impact: none.

## A2_Spaces-the-home-page-and-saved-views

> **A2 · Spaces, the home page and saved views** · Basic · Telecom Space · the SIM-swap saved view · 6 min · user guide
> §3, §3.1, §4 (*the open saved view*), §10

- **Objectives:** say what a Space and an Industry pack are; switch Spaces; find, page and open a saved view from My
  work; read, rename and trace a saved view through its dialog; recognise Getting started on a Space with no data.
- **Before:** a fresh unzip of `la-basic.zip`, the home page of any Space other than Telecom. Saved views per page at 5.
- **Covers (plan §5):** 5.1 Space switcher (the Space list, New Space…), the home page (Space line, Space guide, area
  cards), My work, Saved views (Search saved views, Per page, paging), *What changed in your data* (named, shown in H2),
  Getting started (Try it with sample data / Try the sample, Use my own data, Open my team's data, Space guide); 5.2a
  the saved-view chip, the *Saved view* dialog (Name, Description, facts, Save, Version history).
- **Try it:** in Telecom, search the saved views for *split*, open the view you find and read its details. Which Dataset
  does it read, and which layout does it open in? *Answer: `mfs_split_deposits`: Dataset `mfs_screen_links_dataset`,
  layout Force.*
- **Check:** a Space and an Industry pack: which one holds your Datasets, and which one sets the words? *Answer: the
  Space holds the Datasets, members and settings; its Industry pack sets the words and the suggested tools.*
- **Gotchas:** the shipped Spaces' data is kept up to date by Pipelines, so *What changed in your data* is not drawn on
  any of their home pages (it lists Add data imports only): the voice says so and H2 shows it. Getting started is only
  the home page of a Space with no Dataset, so S11 makes a Scratch Space; *Try the sample* then adds a second Space
  (*Sample: invented data*). Both stay in the recording install. The rename in S09 is undone before S12.

### S01 · Where your work lives
- Voice: This is Inspecto LA training, episode A2: Spaces, the home page and saved views. Everything you do in Inspecto LA happens inside a Space, and the home page is where a Space's work waits for you. In this episode you switch Spaces, find and open a saved view, read and rename it, and meet the page a brand-new Space starts on. We are in the Basic edition, in the Telecom Space.
- Action: intro card.
- Impact: none.

### S02 · A Space, and the switcher
- Voice: A Space is your team's workspace, with its own Datasets, members and settings. One Space holds one kind of data, so wallet transfers and call records never mix. The Space switcher, at the foot of the left menu, names the Space you are in. Open it. The shipped demo holds eleven Spaces, one per industry, in order of name, with a tick on the current one. Choose Telecom. The page reloads on Telecom's home page, and from now on, everything you see belongs to this Space.
- Chip: Space switcher
- Action: Space switcher ▸ hold on the list ▸ Telecom ▸ wait for the reload. [check in UI: eleven Spaces listed by display name, the current one ticked, and *New Space…* / *New from sample…* under them]
- Impact: before/after hold: the switcher's name changes to Telecom; mark the *Space: Telecom* line under the title.

### S03 · The home page
- Voice: The home page names the product and, under it, the Space. While a Space is still being set up, a Space guide sits here and ticks each setup step as it is done; Telecom is complete, so the guide is gone. Then comes My work, and under it one card for each area, Link Analysis, Data and Entity Lists, each saying in one line what the area is for.
- Chip: Home · My work
- Action: hold on the header (*Inspecto LA*, the tagline, *Space: Telecom*) ▸ scroll to the area cards and back.
- Impact: mark the Space line, My work's title and the area cards.

### S04 · Saved views, a page at a time
- Voice: Under My work are the Saved views: every graph saved in this Space, the most recent first, each with its description. The line under the heading says what a saved view is: a graph you keep and the Entity Lists it uses, which re-reads live data, so it may differ when reopened. Telecom has six. Five show at a time; the counter reads one to five of six, and the arrow shows the next page. Per page sets five, ten or twenty-five.
- Chip: Saved views · Per page
- Action: point at the line under *Saved views* ▸ Next page of saved views (›) ▸ Previous page (‹) ▸ Per page: 10. [check in UI: the counter reads *1–5 of 6*]
- Impact: before/after hold: page 1 → page 2 (one card) → all six on one page; mark the counter.

### S05 · Search saved views
- Voice: With many views, search instead. Type sim swap in Search saved views: the list narrows to the matching view, here the SIM-swap view. Its description says what it keeps: every swap by a staff login that made fifteen or more within ten days, and what followed. A good description is what makes a saved view findable months later. Clear the box to see them all again.
- Chip: Search saved views
- Action: type `sim` in *Search saved views* ▸ hold on `mfs_sim_swap_bursts` ▸ clear the box. [check in UI: what the search matches (name only, or name and description); if `sim` also matches other descriptions, type `swap`]
- Impact: before/after hold: six cards → one; mark its description.

### S06 · What changed in your data
- Voice: One card is missing here, on purpose. When you bring a file in yourself with Add data, What changed in your data appears beside the saved views: one line per Dataset, its last import first, with how many rows it added or replaced. Telecom's data is kept up to date by Pipelines, so there is nothing of yours to list, and the card stays away rather than show an empty box. You will see it in episode H2, after your first file.
- Chip: What changed in your data
- Action: point at the empty space beside Saved views; cut-in still of the card from H2 (*transfers: imported …, +461 rows*), labelled *from episode H2*.
- Impact: mark the cut-in still.

### S07 · Open a view, and its chip
- Voice: Click the SIM-swap view's name. Link Analysis opens and draws it again, over the data as it is now. The view's name now sits beside the page title, on a chip with a bookmark. Hover it, and its description shows. The chip tells you, and anyone watching your screen, which saved view this picture came from.
- Chip: Saved view chip
- Action: click `mfs_sim_swap_bursts` ▸ wait for the draw ▸ hover the chip beside *Link Analysis*.
- Impact: mark the chip and its tooltip.

### S08 · The Saved view dialog, and the Industry pack
- Voice: Click the chip. The Saved view dialog holds the view's Name and Description, and its facts: the Dataset it reads, its layout, its Industry pack, how many Investigations use it, and its id. The Industry pack is the set of words and suggested tools a Space uses for its data: a mobile money pack speaks of wallets and transfers, a telecom pack of subscribers and calls. It belongs to the Space, not to you or to the view. This Space uses the neutral words, so the dialog shows Generic.
- Chip: Saved view · Industry pack
- Action: click the chip ▸ hold on the facts list (Dataset, Layout, Industry pack, Investigations, Id). [check in UI: *Industry pack* reads *Generic* for this view (the Telecom Space states no pack); ⚙ Settings ▸ Graph model shows the pack in force as *Generic graph*]
- Impact: mark each fact as the voice names it; mark *Generic*.

### S09 · Rename, and what Save keeps
- Voice: Rename the view, say to SIM swap bursts, staff logins, and press Save. Save changes only the name and the description, never the graph on screen; to keep a changed graph, you use File, Save view. Renaming keeps the id, so every link to this view keeps working. A view you may not change shows read-only, with the reason.
- Chip: Saved view · Name · Save
- Action: Name ✎ ▸ type *SIM swap bursts, staff logins* ▸ Save ▸ hold on the chip. (Rename back before S12.)
- Impact: before/after hold: the chip's name changes; the graph does not move.

### S10 · Version history
- Voice: Open the dialog again and press Version history. Each saved version of the view is listed, newest first, with when it was saved and a Restore button, so a rename or a changed graph can be traced back. That is a record of what the view was. It is still not evidence: opening any version re-reads live data.
- Chip: Version history
- Action: click the chip ▸ Version history ▸ hold ▸ close. [check in UI: what the history lists (a row per save, with who and when), and that the rename from S09 is its newest row; reword to what it shows]
- Impact: mark the newest row.

### S11 · A new Space starts on Getting started
- Voice: One more home page. Open the Space switcher and choose New Space…. Name it Scratch, choose Generic for the kind of data, and press Create. The new Space opens on Add data; press the home link instead. A Space with no Dataset starts on Getting started, with three ways in: Try it with sample data, Use my own data, which here is Add data, and Open my team's data, with the Space guide underneath. Press Try the sample: it creates a separate Space of invented transfers with a planted money-mule ring, and draws it for you.
- Chip: New Space… · Getting started · Try the sample
- Action: Space switcher ▸ New Space… ▸ Name *Scratch* ▸ What kind of data? Generic ▸ Create ▸ Inspecto LA home ▸ hold on Getting started ▸ Try the sample ▸ wait (*Creating the sample Space and loading its data…*) ▸ hold on the drawn ring. [check in UI: the Basic zip ships the `la-sample` template, so *Try the sample* is not greyed; the Space guide reads *1 of 4 done*]
- Impact: mark the three cards and the Space guide; then the sample graph.

### S12 · Try it
- Voice: Your turn. In Telecom, search the saved views for split, open the view you find, and read its details. Which Dataset does it read, and which layout does it open in? The answer is in the description. Next, in A3, your data: Datasets, columns and Mappings.
- Action: outro card: *Try it* and *Next: A3 · Your data*. [check in UI: *split* finds `mfs_split_deposits` only]
- Impact: none.

## A3_Your-data-Datasets-columns-and-Mappings

> **A3 · Your data: Datasets, columns and Mappings** · Basic · Banking and finance Space (plan: Mobile Money) · card
> authorisations, and the card-testing device · 6 min · user guide §3.4

- **Objectives:** find and describe a Dataset; say what each column is (Entity, Time, Measure, Category, Detail), with a
  Unit and whether it rides on each link; read what a Dataset holds with Discover; read one file a second way with a
  Mapping, and know what deleting a Mapping does and does not do.
- **Before:** a fresh unzip of `la-basic.zip`, the Banking and finance Space, the Data page.
- **Covers (plan §5):** 5.5 Data page (Streams and References groups, Find a Dataset, the Dataset header and chips,
  Rename or describe, Columns roles Entity / Time / Measure / Category / Detail, *holds*, Unit, Keep on each link,
  Discover with Show what is in it, Mappings: its own line, New mapping (Mapping name, From, From holds, To, To holds,
  Kind of link, When, Measure, Save Mapping), Explore, Edit, Delete, Other Datasets).
- **Try it:** run Discover on *Card authorisations* and read the busiest merchants. Which merchant tops the list, and why
  is that not a lead? *Answer (confirm at recording): M-052, a busy supermarket: hundreds of ordinary cards shop there,
  and none of them was later defrauded.*
- **Check:** what is the difference between a Dataset and a Mapping? *Answer: a Mapping is another way of reading a
  Dataset's rows as links; it copies nothing, files always go to the Dataset, and deleting a Mapping never deletes data.*
- **Gotchas:** **Space substituted.** The plan's Mobile Money Space holds four Datasets, and *Find a Dataset* only shows
  above six; Banking and finance holds dozens and has a file worth reading two ways (`card_auth_dataset`: card →
  merchant and card → device). The roles a shipped Pipeline Dataset shows before any edit are not stated in its config:
  S04 sets them and the voice describes the result, not the starting state. The rename and the Mapping persist in the
  install. Discover's *Draw these* is off on the Data page by design (*Draw from Explore: its Start box has the same
  Discover.*).

### S01 · Know what you hold
- Voice: This is Inspecto LA training, episode A3: your data. A graph is only as good as what you know about the rows behind it. Before you draw anything, it pays to know which tables you hold, what each column means, and how a row becomes a link. In this episode you find a Dataset, describe it, say what its columns are, look inside it, and read the same file a second way. We are in the Basic edition, in the Banking and finance Space.
- Action: intro card.
- Impact: none.

### S02 · The Data page, and Find a Dataset
- Voice: Open Data in the left menu. On the left is every Dataset of this Space, in two groups: Streams, the events that keep arriving, such as payments and authorisations, and References, which describe things, such as a register. Each line says where its rows come from, how many columns it has, and how many Mappings. This Space holds dozens, so the box at the top, Find a Dataset, narrows the list by name, id or description. Type card_auth, and pick the card authorisations.
- Chip: Data · Find a Dataset
- Action: left menu ▸ Data ▸ scroll the Streams and References groups ▸ Find a Dataset: type `card_auth` ▸ click `card_auth_dataset`. [check in UI: which group holds `card_auth_dataset` and `merchant_dataset`]
- Impact: before/after hold: the long list → the matches; mark the two group headings and the picked line.

### S03 · Rename or describe
- Voice: The header says what you opened: its name, its id with a button to copy it, and its description. The chips say it is a Stream, kept up to date by a Pipeline, with eight columns, and that its Time is auth_at. A name like card_auth_dataset is clear to whoever made it. Press Rename or describe, give it a name your colleagues would recognise, Card authorisations, and save. The id does not change, so nothing that reads this Dataset breaks.
- Chip: Rename or describe
- Action: hold on the header chips ▸ Rename or describe ▸ Dataset details: Name *Card authorisations* ▸ save ▸ hold on the header. [check in UI: the dialog's save button label, and that the chips read *Stream*, *Kept up to date by a Pipeline*, *8 columns*, *Time: auth_at*]
- Impact: before/after hold: the header's name changes; mark the unchanged *Id*.

### S04 · Five roles for a column
- Voice: Columns says what each column is, and there are five roles. Entity: the column holds an Entity Type, and links join on it. Time: a date or time, to play and filter by. Measure: a number to sum and filter by, an amount or a volume. Category: a few named values to filter by, such as a channel. And Detail: carried along, but never filtered on. Here card_id and merchant_id are Entities, holding Payment card and Merchant, auth_at is the Time, and amount the Measure. Set result, which says approved, declined or refund, to Category. Each change is saved at once.
- Chip: Columns · Entity · Time · Measure · Category · Detail
- Action: open the role picker of one column to show the five roles and their hints ▸ set `auth_at` to Time, `amount` to Measure, `result` to Category if not already so ▸ hold on `card_id holds` Payment card. [check in UI: the roles the shipped Dataset shows before the edit; skip the sets that are already right]
- Impact: mark each role's hint as the voice names it; mark `result` changing to Category.

### S05 · Unit, and Keep on each link
- Voice: A Measure also takes a Unit: what it counts in. Type GBP under amount, and its totals will say so. Each Time, Measure and Category column also has Keep on each link. Yes means the value rides on every link drawn from this Dataset, so the timeline, the filters and the profiles can use it, and the link index carries it. No keeps it off the links: a narrower index, but no filter and no play on that column. Leave amount and result at Yes.
- Chip: Unit · Keep on each link
- Action: amount ▸ Unit ✎ *GBP* ▸ open *Keep on each link* to show Yes / No and their hints ▸ leave Yes. [check in UI: the currency the card corpus means; if none, type a neutral unit such as *amount units* and say "type what it counts in"]
- Impact: mark the Unit field and the two Keep hints.

### S06 · Discover: what is in it
- Voice: Now look inside, before drawing a thing. Under Columns is Discover: press Show what is in it. One panel says how many link rows there are and the period they cover, about how many entities of each type, the kinds of link with their counts, the total and median of every Measure in its unit, and the ten busiest entities at each end. Counts of entities are close estimates, and say so. A part the Dataset cannot answer says why, never a zero nobody counted.
- Chip: Discover · Show what is in it
- Action: Discover ▸ Show what is in it ▸ scroll: the summary line, Entities, Kinds of link, Measures, Busiest ▸ hold on the two busiest tables. [check in UI: the kinds of link listed are approved / declined / refund (the mapping's kind is `result`), and the Measure line shows the GBP unit]
- Impact: mark each part as the voice names it.

### S07 · Mappings: how rows become links
- Voice: Last on the page are the Mappings: how this Dataset's rows are read as links. Every Dataset has its own. This one reads each row as a link from card_id to merchant_id, its kind from result, its time from auth_at, weighed by amount. But one file can answer more than one question. These same authorisations also say which device each online purchase came from.
- Chip: Mappings
- Action: scroll to Mappings ▸ hold on the line *card_auth_dataset (its own) · …*.
- Impact: mark the own Mapping's line.

### S08 · New mapping
- Voice: Press New mapping. Name it card_device: lower-case letters, digits and underscores. From is card_id, and From holds Payment card. To is device_id, and To holds Device: links join where columns hold the same Entity Type. When is auth_at, and Measure is amount; leave Kind of link at None. Press Save Mapping. A Mapping is a Dataset of its own over the same data: nothing is copied, and it appears everywhere a Dataset does.
- Chip: New mapping · Save Mapping
- Action: New mapping ▸ Mapping name `card_device` ▸ From `card_id` ▸ From holds Payment card ▸ To `device_id` ▸ To holds Device ▸ When `auth_at` ▸ Measure `amount` ▸ Kind of link: None ▸ Save Mapping. [check in UI: the header chip now reads *1 Mapping*]
- Impact: before/after hold: the new line `card_device · …` appears under the Dataset's own; mark it.

### S09 · Explore the second reading
- Voice: Press Explore beside the new Mapping. Link Analysis opens with card_device picked. Open the Explore the Dataset tab and press Show what is in it. At the top of the busiest devices sits one, DEV-02616, reached by about three hundred cards: one device trying card after card in a single night. Read as card to merchant, those tries hid among thousands of shop visits. Read as card to device, they are the first thing you see.
- Chip: Explore · Explore the Dataset
- Action: Explore (on `card_device`) ▸ Start area ▸ Explore the Dataset ▸ Show what is in it ▸ hold on the busiest *To* table. [check in UI: DEV-02616 tops the busiest devices with about 300 distinct cards (ground truth: 300 cards on 2026-06-14); reword the count to what the table shows]
- Impact: mark the DEV-02616 row.

### S10 · Edit, Delete, and what stays safe
- Voice: Back on the Data page, Edit changes a Mapping, and Delete removes only the Mapping, never the data. Files always go to the Dataset you added, never to a Mapping. A Dataset that Mappings read cannot be deleted until they are. And at the very end, Other Datasets lists the tables no Mapping is offered for: computed when read, holding no stored data, or with fewer than two columns to read as From and To.
- Chip: Edit · Delete · Other Datasets
- Action: Data ▸ `card_auth_dataset` ▸ hover Edit and Delete on `card_device` (do not press) ▸ scroll to *Other Datasets*.
- Impact: mark the two buttons and the Other Datasets paragraph.

### S11 · Try it
- Voice: Your turn. In the same Space, run Discover on Card authorisations and read the busiest merchants. Which merchant tops the list, and why is that not a lead? The answer is in the description. Next, in A4, entities and Entity Types.
- Action: outro card: *Try it* and *Next: A4 · Entities and Entity Types*. [check in UI: M-052 tops the busiest merchants]
- Impact: none.

## A4_Entities-and-Entity-Types

> **A4 · Entities and Entity Types** · Standard (plan: Basic) · Banking and finance Space · the card compromise, and
> a column nobody typed · 6 min · user guide §3.3, §4.1

- **Objectives:** say what an Entity Type is and why two columns join only on the same one; read the Entity Types your
  data holds; set what a column holds and add an Entity Type; open one entity's profile.
- **Before:** a fresh unzip of `la-standard.zip`, the Banking and finance Space, signed in as **Demo Admin** (Workbench
  author: adding a type needs it). The shipped Banking data has no phone column, so the episode adds a demonstration
  type *Phone number* and leaves every column as it is; delete that type after recording (Settings ▸ Entity Types).
- **Covers (plan §5):** 5.5 Entities page (Entity Types tab: cards, Primary in, approx., freshness badge, Not in your
  data; Columns tab: Holds, Not set, Show every column, primary Entity Type), New Entity Type (Name, Example value,
  Compare values as, Add Entity Type), Entity Type Profile, Open an Entity, Entity Profile; 5.4 ⚙ Settings ▸ Entity
  Types (list, New Entity Type, Delete and its refusal).
- **Try it:** on the Columns tab, find a column that is Not set, and decide what it holds. Which *Compare values as*
  would fit an e-mail address, and why? *Answer: Case ignored: two spellings of one address differ only by letter case
  or stray spaces.*
- **Check:** why would two phone columns holding Phone number change the graph? *Answer: columns join only when they
  hold the same Entity Type; then the same number in both files is one entity, so the rows link through it.*
- **Gotchas:** **edition changed to Standard.** On Basic the Entities area is not in the left menu and the two profile
  pages answer 503 (`entityPages` is Standard and up); the Entities page itself is reached on Basic only through the
  Space guide's *What the columns hold* step, which a shipped Space has already ticked. The voice says this once (S03).
  In S10 the Delete is tried on Merchant, which is in use and therefore refused: never confirm Delete on a type nothing
  uses during the recording.

### S01 · What joins two columns
- Voice: This is Inspecto LA training, episode A4: entities and Entity Types. Two columns join up in the graph only when they hold the same kind of thing, so naming that kind well decides which links you will ever see. In this episode you read the Entity Types your data holds, say what a column holds, add a type of your own, and open one entity's profile. We are in the Standard edition, in the Banking and finance Space, signed in as the administrator.
- Action: intro card.
- Impact: none.

### S02 · Entity Types, card by card
- Voice: An Entity Type is the kind of thing a column holds: a payment card, a merchant, a device. Every value in such a column is an Entity of that type. Open Entities in the left menu. The Entity Types tab has one card per type this Space knows: Bank customer, Company, Payment card, Merchant, Online account and more. A card says in how many Datasets the type appears, and a Dataset's primary type is chipped Primary in, with the Dataset's name. The count of distinct entities waits until you open the type: until then the card reads Not assessed.
- Chip: Entities · Entity Types
- Action: left menu ▸ Entities ▸ Entity Types tab ▸ hover the Merchant card's freshness badge ▸ zoom on a *Primary in …* chip.
- Impact: mark Distinct, Datasets and Data as of on one card; mark the chip.

### S03 · Not in your data, and Basic
- Voice: One note on editions: in Basic, this page has no place in the left menu, and the profiles you will open in a moment are Standard. The Columns tab, next, is still where the Space guide's What the columns hold step lands, in every edition.
- Chip: Not in your data
- Action: scroll to *Not in your data (n)* ▸ open it ▸ close it.
- Impact: mark the folded panel.

### S04 · Columns: who holds what
- Voice: Open the Columns tab. It answers the question the other way round: for each Dataset, each column that looks like an identifier, and what it holds. In the payments Dataset, from_customer holds Bank customer and to_company holds Company, so a payment links a customer to a company. But payment_id says Not set: nothing says what kind of thing it is, so it joins nothing. In the online accounts Dataset, account_id holds Online account, card_id holds Payment card and device_id holds Device. Two columns join up only when they hold the same type.
- Chip: Columns · Holds
- Action: Columns tab ▸ scroll to `online_account_dataset` ▸ hold on its rows ▸ scroll to `cardholder_dataset`. [check in UI: `phone` is listed as an identifier-like column with *Not set*; if not, tick *Show every column* first and say so]
- Impact: mark the two *Not set* values.

### S05 · New Entity Type
- Voice: Say your data had a phone number column, and no type for it yet. Press New Entity Type. Name: Phone number. Example value: type one phone, plus 44 7700 900123. Compare values as says when two spellings count as one value: Upper case, spaces trimmed; Digits only; Phone number; or Case ignored. Choose Digits only, and the line under the example shows how the value will be kept, every space and dash dropped. Press Add Entity Type. A name the Space already has is refused before anything is written.
- Chip: New Entity Type · Compare values as
- Action: New Entity Type ▸ Name *Phone number* ▸ Example value: paste the copied phone ▸ Compare values as ▸ hover the four choices' hints ▸ Digits only ▸ hold on *Kept as …* ▸ Add Entity Type. [check in UI: the *Kept as* line for the pasted value]
- Impact: mark the *Kept as* line; then the new type in the page's result line.

### S06 · Say what a column holds
- Voice: Back in Columns, each column has a Holds picker. Open it on payment_id: it lists every Entity Type of the Space, and choosing one would say what that column holds, with one line telling you what changed. We leave it alone here, because a payment's own number is not something to join on. In your own data you would pick Phone number for every phone column, and the same number in two files would then be one entity. The picker beside each Dataset's name sets its primary Entity Type: the type its graph leads with.
- Chip: Holds · Show every column · Primary Entity Type
- Action: `online_account_dataset` ▸ phone ▸ Holds: Phone number ▸ hold on the result line (*"phone" in online_account_dataset now holds Phone number.*) ▸ same for `cardholder_dataset` ▸ tick Show every column ▸ untick ▸ hover a primary picker. [check in UI: the result line's exact wording]
- Impact: before/after hold: *Not set* → *Phone number*, twice; mark the result line.

### S07 · The Entity Type Profile
- Voice: Now open the Merchant card: its Entity Type Profile gathers what every Dataset says about merchants. Identity, with how values are compared and whether the type is masked. Where it appears, its lineage, volume and quality, the shapes its values take, time, links, relationships and measures. And last, What is not here: a section the server could not read is named there, with its reason, never left out quietly.
- Chip: Entity Type Profile
- Action: Entity Types ▸ Merchant ▸ scroll the sections ▸ hold on *What is not here*. [check in UI: the Phone number card may read *Not assessed: not computed yet*; if so the voice adds "not profiled yet, and it says so"]
- Impact: mark each section heading as the voice names it.

### S08 · Open an Entity
- Voice: On a type's page you can open one entity. In Open an Entity, type M-317, the fuel station of the card compromise, and press Enter. The Entity Profile opens with what the data says about that one entity: when it was first and last seen, where it appears, its links and the lists it is on. A profile tells you what the data says about one entity before you draw a single link.
- Chip: Open an Entity
- Action: Merchant page ▸ Open an Entity: `M-317` ▸ Enter ▸ scroll the Entity Profile. [check in UI: where the Open an Entity field sits on the Entity Type page, and the profile's section names]
- Impact: mark the first and last seen line and the rows per day chart.

### S09 · The same types in Link Analysis
- Voice: The same types are in Link Analysis, under the gear: Settings, Entity Types. Each type in force is listed with its id, how its values are compared, and whether it is masked. The Phone number type we just added is on the list, and New Entity Type is here too, so you can add one without leaving the graph.
- Chip: Settings · Entity Types
- Action: Link Analysis ▸ Settings ▸ Entity Types ▸ hold on the list ▸ point at New Entity Type.
- Impact: mark the Phone number row (*phone_number · Digits only*).

### S10 · Delete, and why it is refused
- Voice: Delete removes a type nothing uses, after a confirmation that names it, and it cannot be undone. Try it on Merchant. It is refused, and the message says what still uses it: the Dataset columns that hold it. A type that an Entity List or an Investigation names is kept the same way: change or retire those first. And the last Entity Type of a Space can never be deleted. We keep Phone number out of this: it is only a demonstration type.
- Chip: Delete · Not changed
- Action: Merchant ▸ Delete ▸ confirm (*Delete Entity Type*) ▸ hold on the refusal. [check in UI: the refusal box (*Not changed* title) names the columns that hold Merchant; never press Delete on Phone number or any unused type]
- Impact: mark the refusal and the columns it names.

### S11 · Try it
- Voice: Your turn. On the Columns tab, find a column that is Not set. Decide what it holds, make an Entity Type for it if none fits, and choose how to compare its values. Which comparison fits an email address best, and why? The answer is in the description. Next, in B1, start a graph.
- Action: outro card: *Try it* and *Next: B1 · Start a graph*.
- Impact: none.
