---
type: Guide
title: "Inspecto LA training videos — H · Administering a Space: narration scripts"
description: "The spoken script of the training episodes in group H (Administering a Space), scene by scene, with the action on screen and the change it causes. Format and status: the overview, user-guide-training-scripts.md."
resource: tools/render-la-demo-video.mjs
tags: [module, la-core, link-analysis, user-guide, training, video]
timestamp: 2026-11-16T00:00:00Z
---

# Inspecto LA training videos — H · Administering a Space

> Narration scripts for group H of the Inspecto LA training series. How to read an episode, the voice and the
> renderer: [the overview](user-guide-training-scripts.md). Draft, not recorded, not reviewed by product; `[check in UI: …]` marks a detail to
> confirm on the live product before recording.

## Episodes in H

| Episode | Title | Edition | Scenes | Spoken words | UI checks open |
|---|---|---|---|---|---|
| [H1](#h1_settings-for-a-space-and-for-you) | Settings for a Space and for you | Standard | 11 | 822 | 7 |
| [H2](#h2_from-your-own-file-to-a-first-finding) | From your own file to a first finding (capstone) | Basic | 12 | 830 | 7 |
| [H3](#h3_when-the-product-says-no) | When the product says no: locked controls, cut results and refusals | Basic → Standard | 12 | 859 | 4 |

## H1_Settings-for-a-Space-and-for-you

> **H1 · Settings for a Space and for you** · Standard · Other Examples Space · the Space's own setup · 6 min · user guide
> §4.1, §14, §15

- **Objectives:** find a setting by who it is for (you, this Space, this server); read and change masking, the link index
  and the Space's limits; say who can do what, by role and by edition; say where each limit lives, in three sections;
  check which Link Analysis modules a server runs.
- **Before:** a fresh unzip of `la-standard.zip`, the Other Examples Space, signed in as Demo Admin (it holds Workbench
  author, Access configuration and Administration here). Link Analysis open on any saved view, for S10.
- **Covers (plan §5):** 5.5 Settings page: My settings (My browser, Investigation access), This Space (Link Analysis:
  Masking mode, Entity Types in force, Link index, Server graph run, Embed links, Investigation store, Save Link Analysis
  settings; Analysis limits, Save Analysis limits; Add data, *People may add data in this Space*; Access: role cards,
  Show all capabilities, New role), This server (Host resources, Getting started counters), the *Read-only* marks; 5.4
  Settings dialog ▸ Installed modules; user guide §14 who can do what, by role and by edition; §15 every limit is a
  setting, in three sections (Analysis, Host resources, My browser), Reset to Space value.
- **Try it:** sign in as Demo Analyst and open Settings. Which sections are marked Read-only, and why? *Answer: settled at
  recording (expected: Link Analysis and Analysis limits, which need Workbench author; Access, which needs Access
  configuration; Host resources, a system administrator's; Getting started counters, which is always read-only).*
- **Check:** where would you lower a canvas cap for your own slow laptop, without changing it for anyone else?
  *Answer: My settings ▸ My browser; a personal value is never above the Space value, and Reset to Space value clears it.*
- **Gotchas:** this Space's own values (`link-analysis.toon`): four-eyes limit 2,000, link index on, Industry pack Telecom,
  Drafts at most 10 open. Change a value on camera only where the episode restores it. Index builds are refused on packaged
  installs until the packager stamps the index Job's owner (plan §6.4): do not build an index in this episode.
  Investigation access is not offered on Basic, which has no Investigations.

### S01 · Set rarely, find quickly
- Voice: This is Inspecto LA training, episode H1: settings. Most of the time you work on the canvas. Some things you set once and rarely touch: how ids are masked, how far an expand may reach, what each role may do. Inspecto LA sorts every setting by who it is for: you, this Space, or the whole server, and each one shows its value in force and says who may change it. We are in the Standard edition, in the Other Examples Space, signed in as the administrator.
- Action: intro card.
- Impact: none.

### S02 · Three groups
- Voice: In the rail, open Settings. The menu on the left has three groups. My settings: your own values, which nobody else sees. This Space: its settings, its limits, and its roles. This server: what every Space on this installation shares. Each section has one line saying what it holds. A section you may look at but not change says Read-only beside its name, so you know before you try.
- Chip: Settings · My settings · This Space · This server
- Action: rail ▸ Settings ▸ point at each group title and one line under each.
- Impact: mark the three group titles.

### S03 · My browser
- Voice: Start with My browser. These are your own limits, for this browser only: how many nodes the canvas draws, which browser algorithms run on how large a graph, how labels are shown. Each field shows the Space value, and blank means the Space value. You can set a lower one, say on a slow laptop, but never a higher one, and Keep in this browser saves it. Reset to Space value clears yours. Nobody else sees them, and the server never relies on them. We leave them as they are.
- Chip: My settings · My browser · Reset to Space value
- Action: My browser ▸ open one group ▸ type a lower value in one field ▸ Save ▸ Reset to Space value. [check in UI: the Save button's label in this section]
- Impact: mark *Space value* in the placeholder; then the reset.

### S04 · The Space's Link Analysis settings
- Voice: Under This Space, open Link Analysis. Masking mode decides which ids are shown as aliases: typed masks only the types marked masked, all masks every id, none masks nothing. The line under it says what is in force. Entity Types in force lists each type, how its values are compared, and whether it is masked. Link index says whether reads may be served from it. Then the per-hop weights for propagated risk, which classifications may never be shared by link, and which sites may frame the shared view. Save Link Analysis settings keeps the changes.
- Chip: This Space · Link Analysis · Masking mode
- Action: Link Analysis ▸ Masking (open the picker, read the three hints, close without changing) ▸ Entity Types in force ▸ Link index ▸ Server graph run ▸ Embed links ▸ point at Save Link Analysis settings. [check in UI: *In force:* lines for masking (expected typed) and the index (expected on)]
- Impact: mark each panel title as the voice names it.

### S05 · One thing that is not a setting
- Voice: At the foot of that page, one line: Investigation store, filesystem, chosen when the server starts; not a setting. Where Investigations are kept is decided by whoever runs the server, not on this page. On the Advanced edition it can be a shared database, which episode I2 explains. Below it, the Space's Industry pack, which names the words and suggested tools, and the categories of exclusion people may choose from.
- Chip: Investigation store
- Action: scroll to *Investigation store: filesystem (chosen when the server starts; not a setting)* ▸ scroll on to the Industry pack (Domain Profile) and the exclusion categories. [check in UI: the Industry pack section's title]
- Impact: mark the store line; then the pack in force, Telecom.

### S06 · Every limit is a setting
- Voice: Analysis limits holds this Space's caps and budgets, the ones the server enforces, in groups: Canvas, Browser algorithms, Display, Investigation, Connect, Traversal, and more. Each field says its default and the value in force. The busy limit and the four-eyes limit live here, among the Investigation limits, as do expand budgets, path depth and Draft limits. A value outside its range is refused, with the range. Blank means the shipped default. We change nothing on camera.
- Chip: Analysis limits · Hub threshold
- Action: Analysis limits ▸ open the group holding Hub threshold ▸ zoom on one field (default, range, In force) ▸ type a value above its range ▸ Save Analysis limits ▸ hold on the refusal ▸ clear the field. [check in UI: the labels *Hub threshold* and *Four eyes fan out above*, and the refusal's wording]
- Impact: mark the field's *In force* line; then the refusal naming the range.

### S07 · Add data
- Voice: Add data has one switch: People may add data in this Space. With sign-in, it is off until an administrator turns it on, and people also need the right to upload data. It is the first thing to check when someone says the Add data screen sent them to an administrator.
- Chip: Add data · People may add data in this Space
- Action: Add data ▸ zoom on the toggle and the line under it. [check in UI: the line *On* or *Off* and *(the default here)* in this build]
- Impact: mark the toggle and its sentence.

### S08 · Who can do what
- Voice: Access shows what each role may do in this Space. Each role is a card with its permissions in plain words: set up Datasets, author Alert Rules, build the link index, create and edit Investigations, run server algorithms, upload data, acknowledge and resolve Alerts. Who holds a role is set in the identity provider, not here. Built-in roles come with the product; authored ones are this Space's own, and New role adds one. Show all capabilities lists every capability a role holds or lacks.
- Chip: This Space · Access
- Action: Access ▸ scroll the role cards (la-analyst, la-manager, la-viewer, la-settings, admin) ▸ Show all capabilities ▸ point at New role. [check in UI: the role cards' capability chips match `roles.toon`]
- Impact: mark the capability chips on la-analyst and la-manager.

### S09 · And by edition
- Voice: What a role allows also depends on the edition. Basic has no sign-in, so it has no roles: anyone may explore, analyse and save views, and nothing is recorded. Investigations, four-eyes, Drafts, Case history and the server side of the toolbox start in Standard, and on Basic they show a line naming what has them. Advanced adds Access Policies, which can only narrow what a role allows. Whatever the edition, a greyed control names, in its tooltip, what is missing.
- Chip: Basic · Standard · Advanced
- Action: title card: the user guide §14 table (Basic / Standard and above), then back to the Access page.
- Impact: mark the Investigations row of the table.

### S10 · This server, and the modules
- Voice: Under This server, Host resources are the limits every Space on this installation shares: graph runs, index build memory, request rates. Only a system administrator changes them. Getting started counters show how first-time users fare here, counted on this server and never sent anywhere. One more place: in Link Analysis, the Settings gear, Installed modules, lists each Link Analysis module: active, inert with the reason, switched off in this Space, or not installed.
- Chip: Host resources · Getting started counters · Installed modules
- Action: Host resources ▸ Getting started counters ▸ rail ▸ Link Analysis ▸ Settings gear ▸ Installed modules. [check in UI: the states listed in this build]
- Impact: mark the three limit sections' homes; then the module states.

### S11 · Try it
- Voice: So: every setting sits under who it is for, says its value in force, and says who may change it; every limit lives in one of three sections, Analysis, Host resources, or My browser. Your turn. Sign in as the analyst, and open Settings. Which sections are marked Read-only, and why? The answer is in the description. Next, in H3, when the product says no.
- Action: outro card: *Try it* and *Next: H3 · When the product says no*.
- Impact: none.

## H2_From-your-own-file-to-a-first-finding

> **H2 · From your own file to a first finding (capstone)** · Basic · a new Space, *My transfers* · a mule chain in your
> own file · 6 min · user guide §2, §3.1, §3.2, §3.4, §3.5

- **Objectives:** make a Space for your data; bring a file in and account for every row; say what its columns hold and
  add details; read the same file a second way; draw a first graph, answer a first question, and save it to come back.
- **Before:** a fresh unzip of `la-basic.zip`, any Space's home page. In the recording folder, the series' two sample
  files `transfers.csv` (461 transfers between accounts, with four deliberately dirty rows) and `accounts.csv` (138
  accounts), copied from `inspecto/src/test/resources/onboard/full-cycle/` (synthetic, `tools/gen-la-import-fixture.mjs`).
- **Covers (plan §5):** 5.1 New Space… (Create a Space: Name, What kind of data?, Create); 5.5 Add data / Onboard a file
  (1. Choose the data: Events between entities / Details about entities, A file, Choose a file, the formats and limits
  line, *Read from another database or a remote system* locked; 2. Check the columns: A guess, Confirm, Confirm all N
  guesses; 3. Say what the columns hold: Entity Type, Each row is a link, From, To, Kind of link, When (a Time column),
  Weighed by (a Measure column); 4. Create and run: Dataset name; the result lines; Explore in Link Analysis, Add
  details about these entities, Which column identifies each entity?), Data page ▸ New mapping, ⚙ Settings ▸ Add data
  (Upload a CSV, named and contrasted); first graph (Start a graph, Expand), first Quick question (Route from A to B,
  Find route, Show on graph), Exclude… Hide from this graph, File ▸ Save view as, home page My work and *What changed in
  your data*.
- **Try it:** in your own Space, find the route from ACC-1002 to ACC-1033. Through which accounts does it pass? *Answer:
  settled at recording from Find route (the fixture's chain: sources ACC-1001…1006 → layer accounts ACC-1010…1021 →
  MULE-HUB-01 → cash-out accounts ACC-1030…1033).*
- **Check:** your result line says "1 row has no From, so it draws no link". Is that row lost? *Answer: no; the row is
  kept in the Dataset, it only draws no link, which is why a graph can have fewer links than the Dataset has rows.*
- **Gotchas:** the user guide's quick-start file (2,215 transfers, `MULE-HUB`) is kept outside the repository, so this
  episode uses the committed full-cycle fixture; **the two files must ship with the series download** (a prerequisite to
  add to the plan). The detected guesses (S04) and the result lines (S06) are read from the screen at recording: the
  fixture's README names the four dirty rows. The Generic kind may not offer an *Account* Entity Type until one is made;
  if so, S05 makes it with New Entity Type (A4).

### S01 · Your own file
- Voice: This is Inspecto LA training, episode H2: from your own file to a first finding. Everything so far ran on data the product ships. This time you start from a file of your own, a few hundred money transfers, and go all the way: a new Space, the file in, its columns described, a first graph, a first question answered, and a view saved to come back to. We are in the Basic edition, in a new Space.
- Action: intro card.
- Impact: none.

### S02 · A Space for this data
- Voice: Open the Space switcher and choose New Space…. Create a Space asks a Name, and What kind of data?: Mobile Money, Telecom or Generic, each with example column names. It sets the words Link Analysis uses for your data. These are bank transfers, so choose Generic, name the Space My transfers, and press Create. The new Space opens on Add data, ready for a file.
- Chip: New Space… · What kind of data?
- Action: Space switcher ▸ New Space… ▸ Name *My transfers* ▸ What kind of data? Generic ▸ Create ▸ wait for *Onboard a file*.
- Impact: mark the three kinds and their example columns.

### S03 · Choose the data
- Voice: Step one: choose the data. Say what is in the file: Events between entities, where each row links two things, or Details about entities, where each row describes one thing. Choose events, then drop transfers.csv here, or press Choose a file. The page lists the formats it reads and this Space's limits. Reading from another database or a remote system is locked on Basic, and says what has it.
- Chip: Events between entities · Choose a file
- Action: Events between entities ▸ A file ▸ point at the formats and limits lines and the locked *Read from another database or a remote system* ▸ Choose a file ▸ `transfers.csv`.
- Impact: mark the chosen file line (*transfers.csv, N rows, size*).

### S04 · Check the columns
- Voice: Step two: check the columns. What was detected is applied, with its reason beside it: the separator, the header line, each column's type. Two choices are only guesses, highlighted and marked A guess. booked_at is a date with a time, except one value that is not; amount is a number, except one. Each reason names the value that does not fit, and says it will be left blank. Read them, then press Confirm all two guesses.
- Chip: A guess · Confirm all guesses
- Action: hold on Separator / Header line / Quote and their reasons ▸ zoom on the `booked_at` and `amount` guesses and their reasons ▸ Confirm all 2 guesses. [check in UI: which columns are guesses and the button's number; the fixture holds `not-a-date` in booked_at and `abc` in amount]
- Impact: mark each guess and its reason; then the highlights clearing.

### S05 · What each column holds
- Voice: Step three: Say what the columns hold. from_account and to_account hold an Entity Type: choose Account. Each row is a link: From from_account, To to_account. Kind of link is channel; When, a Time column, is booked_at; and Weighed by, a Measure column, is amount. Without a From and a To, the page would warn that the file cannot be drawn as a graph.
- Chip: Each row is a link · From · To
- Action: Say what the columns hold ▸ from_account and to_account: Account ▸ From `from_account` ▸ To `to_account` ▸ Kind of link `channel` ▸ When `booked_at` ▸ Weighed by `amount`. [check in UI: what was already applied by detection; confirm any remaining guess]
- Impact: mark *Each row is a link* and its five choices.

### S06 · Create and run: every row accounted for
- Voice: Step four: the Dataset is named after the file, transfers. Press Create and run. The result accounts for every row, one line each. The rows read, and the rows kept. One value in booked_at, and one in amount, did not fit and were left blank. One row has no From, so it draws no link. One value, acc-1003 with stray spaces, is a second spelling of ACC-1003. And the link index was built. Nothing is dropped without a line saying so.
- Chip: Create and run
- Action: Dataset name `transfers` ▸ Create and run ▸ wait ▸ hold on the result lines one by one. [check in UI: the lines read *461 rows were read …; 461 rows were kept.*, the two *left blank* lines, *1 row has no From (from_account), so it draws no link.*, the second-spelling line, and the link index line]
- Impact: mark each result line as the voice reads it.

### S07 · Add details about these entities
- Voice: Press Add details about these entities, and choose accounts.csv: one row per account, with its holder, city and type. This time the page asks which column identifies each entity: account_id. Create and run. The details now travel with every account on the graph.
- Chip: Add details about these entities · Which column identifies each entity?
- Action: Add details about these entities ▸ Choose a file ▸ `accounts.csv` ▸ Which column identifies each entity? `account_id` ▸ confirm any guess ▸ Create and run ▸ hold on the result.
- Impact: mark the result's first line.

### S08 · A second reading, and the other upload
- Voice: Open Data. Your two Datasets are there, added here. One file can be read more than one way: press New mapping on transfers, name it transfers_by_channel, From from_account, To channel, and Save Mapping. Now the same rows also say which accounts use which channel, without copying anything. One more door is easy to confuse with this one: Settings, Add data, Upload a CSV, hands a file to a Pipeline's own Collector. Add data, which you used, makes a Dataset of its own.
- Chip: New mapping · Upload a CSV
- Action: Data ▸ `transfers` ▸ New mapping ▸ Mapping name `transfers_by_channel` ▸ From `from_account` (holds Account) ▸ To `channel` (holds Nothing: a detail) ▸ Save Mapping ▸ Link Analysis ▸ Settings ▸ Add data ▸ hold on Upload a CSV. [check in UI: To holds may be left as a detail; and what Upload a CSV says in a Space with no Collector that accepts uploads]
- Impact: mark the new Mapping line; mark the Upload a CSV section.

### S09 · A first graph
- Voice: Back in Link Analysis, Start a graph with transfers picked. Keep One entity and Account, and type MULE: the suggestion says how busy the match is. Pick MULE-HUB-01 and press Draw. The hub sits in the middle, with the accounts that paid it and the ones it paid on. Press G for the Expand form, choose From, the nodes not expanded yet, and press Expand: the accounts behind them arrive.
- Chip: Start a graph · Expand · G
- Action: Start a graph ▸ Dataset `transfers` ▸ One entity ▸ Account ▸ type `MULE` ▸ pick MULE-HUB-01 ▸ Draw ▸ G ▸ From: The nodes not expanded yet ▸ Expand. [check in UI: the suggestion line for MULE-HUB-01 (fixture: 76 links); the expand stays within 25 nodes from]
- Impact: before/after holds: the hub and its ring; then the second ring.

### S10 · A first question, answered
- Voice: The busy merchant, MERCH-001, has arrived with them: a hundred and twenty accounts pay it, and it only gets in the way. Right-click it, choose Exclude…, and Hide from this graph. Now the first question. In the toolbox, Quick questions, Route from A to B: from ACC-1001, where the money starts, to ACC-1030, where it is cashed out. Find route. One sentence answers: ACC-1001 reaches ACC-1030 through a layer account and the hub. Show on graph lights the chain. That is your first finding.
- Chip: Route from A to B · Find route · Show on graph
- Action: right-click MERCH-001 ▸ Exclude… ▸ Hide from this graph ▸ Exclude ▸ toolbox ▸ On screen ▸ Route from A to B ▸ From `ACC-1001` ▸ To `ACC-1030` ▸ Find route ▸ hold on the sentence ▸ Show on graph. [check in UI: MERCH-001 is drawn after the expand; the route sentence (*ACC-1001 reaches ACC-1030 in 3 links: …*); if ACC-1001 is not on the graph, expand once more first]
- Impact: mark the sentence; the chain glows.

### S11 · Save it, and come back
- Voice: Keep it. From the File menu, Save view as: name it Mule ring, and read the note: not evidence, because a saved view re-reads live data. Press Save view. On the home page, My work lists Mule ring, and beside it, at last, What changed in your data: transfers, imported today, with the rows it added. Next month, Add a file on the Data page brings the new transfers into the same Dataset.
- Chip: File · Save view as · My work
- Action: File ▸ Save view as ▸ Name *Mule ring* ▸ zoom on *Not evidence: …* ▸ Save view ▸ Inspecto LA home ▸ hold on My work and *What changed in your data*. [check in UI: the line reads *transfers: imported <today>, +461 rows*]
- Impact: mark the saved view card and the What changed line.

### S12 · Try it
- Voice: Your turn. In a Space of your own, bring in the two sample files, or a file of yours. Find the route from ACC-1002 to ACC-1033. Through which accounts does it pass? The answer is in the description. Next, in H3, when the product says no.
- Action: outro card: *Try it* and *Next: H3 · When the product says no*.
- Impact: none.

## H3_When-the-product-says-no

> **H3 · When the product says no: locked controls, cut results and refusals** · Basic → Standard · Telecom Space ·
> the dealer and the SIM swap · 6 min · user guide §15, §16

- **Objectives:** find the reason behind a greyed control; recognise a cut result and narrow it; read a refusal and know
  the next step; tell a sign-in or Space problem from a data problem; say why a view is not evidence.
- **Before:** a fresh unzip of `la-standard.zip`, the Telecom Space, signed in as Demo Analyst; for S10 a second sign-in as
  Demo Outsider. For S02 the Basic build with the Telecom Space. S09 and S11 use title cards: their messages cannot be
  triggered safely on camera.
- **Covers (plan §5):** user guide §16: a greyed control and its tooltip (*Needs …*), *Result cut short* (the guide's
  *Truncated*) and its two sentences, Layered greyed on a wide graph, `masked:…` ids, *waiting for approval*, *This
  Investigation is not available*, a pattern that finds nothing on a cut graph, *Merged step refused*, the 413 and 429
  sentences, the sign-in messages (*Single sign-on isn't set up*, *The server could not start a session*, *No Space
  attached*, *started without a session*); §15 views are not evidence; Basic's edition lock on the *Open in Inspecto LA* page.
- **Try it:** as Demo Analyst, open File and hover each greyed item. Which permission does each one name? *Answer:
  settled at recording (expected: Save view and Save view as… name Workbench author).*
- **Check:** an Investigation link answers "not available". Name the three things it can mean. *Answer: it does not
  exist, it is not yours, or its Dataset is no longer shared with you; the answer is the same on purpose.*
- **Gotchas:** the guide calls the cut-result banner *Truncated*; Link Analysis shows **Result cut short** (the Geo map
  says *Truncated*): use the screen's words. The 429 sentence states no budget (two budgets can refuse, and the Link
  Analysis one is tunable) and says how long to wait from the server's `Retry-After`. Telecom
  masks nothing, so S06 cuts to the Mobile Money footage of F7.

### S01 · No, and why
- Voice: This is Inspecto LA training, episode H3: when the product says no. Sooner or later a button is greyed, a result is cut short, or a request is refused. Inspecto LA never just says no. It says why, in words, and usually what to do next. In this episode you will meet the refusals you are most likely to see, read each one, and take the next step. We start once in the Basic edition, then work in the Standard edition, in the Telecom Space.
- Action: intro card.
- Impact: none.

### S02 · A lock names what has it
- Voice: On Basic, open a link that another system made to an Investigation. The Open in Inspecto LA page answers at once: opening a link from another tool is part of third-party integration, available in Standard and above; this edition is the explorer, so open Link Analysis and search for the entity instead. That is the pattern of every edition lock: it says what you are missing, and what you can do with what you have.
- Chip: Basic · edition lock
- Action: Basic build ▸ address bar `/open?investigation=sim-swap-takeover` ▸ hold on the error box.
- Impact: mark the sentence.

### S03 · A greyed control
- Voice: Now the Standard edition, signed in as the analyst. Open the File menu. Save view is greyed. Hover it, and the tooltip names what is missing: Workbench author, the right to set up Datasets and saved views. Every greyed control works this way. The cure is not a workaround: ask your administrator for the role, or ask someone who holds it. Episode H1 shows where roles are kept.
- Chip: File · Save view · Needs Workbench author.
- Action: Standard build ▸ Link Analysis ▸ File ▸ hover Save view. [check in UI: the tooltip *Needs Workbench author.*]
- Impact: mark the greyed item and its tooltip.

### S04 · Result cut short
- Voice: Open File, Explore Dataset, and draw the whole telecom link Dataset. A yellow banner says Result cut short: some nodes are not shown, because the graph reached the node cap, a thousand. It names the cap, and the next step: narrow the Dataset or add a filter. The Graph counts card has a Limits button that lists the caps in force. A cut graph is not wrong, but it is incomplete, and any count you read from it is a floor.
- Chip: Result cut short · Limits
- Action: File ▸ Explore Dataset… ▸ `telco_ra_screen_links_dataset` ▸ draw the whole Dataset ▸ hold on the banner ▸ Graph counts ▸ Limits. [check in UI: which start reaches the cap in this Space, and the banner's sentence *Some nodes are not shown: the graph reached the node cap (N). Narrow the Dataset or add a filter.*]
- Impact: mark the banner and the Limits list.

### S05 · Layered, greyed; a pattern that finds nothing
- Voice: On that wide graph, open Layout. Layered is greyed, and so are several others, with their reasons in the tooltip: they need a smaller graph, and the menu offers to reduce the graph to use more layouts. Force and Grid stay available. And beware the quiet no: a pattern run on a cut graph can find nothing, because the matching links were never drawn. When a tool finds nothing on a big graph, switch the toolbox to Whole Dataset, where it runs on the server over everything.
- Chip: Layout · Layered · Whole Dataset
- Action: Layout ▸ hover Layered ▸ Force ▸ toolbox ▸ Whole Dataset tab. [check in UI: Layered's reason *too wide (N nodes in one row, max 60)* or its node-cap reason on this graph]
- Impact: mark Layered's reason; then the Whole Dataset tab.

### S06 · Masked ids
- Voice: This Space masks its subscriber numbers, so the line under the canvas says a thousand entities masked, beside a Reveal button. Masked ids read masked, then a code. It is not a fault. The same code always stands for the same entity, so you can still work. To see a real value you need the right to see unmasked ids, and a saved Investigation to record the reveal against, with your reason. Episode F7 shows it end to end.
- Chip: masked:…
- Action: cut-in from F7 (Mobile Money): the masked canvas and *N entities masked · Reveal…*.
- Impact: mark two aliases.

### S07 · Waiting for approval
- Voice: In the Utilities Space, open the Investigation Meter tampering after one contractor's visits. One line in the Expand section says waiting for approval. Nothing is broken: that expand is above the Space's four-eyes limit, so it runs only when a second person approves it. Ask an approver, or take a smaller step under the limit. Episode F5 is the approver's side.
- Chip: waiting for approval
- Action: File ▸ Open… ▸ *SIM-swap wallet takeover* ▸ Investigation tab ▸ Expand ▸ zoom on the held line.
- Impact: mark *waiting for approval*.

### S08 · A step that is refused
- Voice: Some refusals arrive after you press a button. They appear where you pressed it, in words, and nothing is half done. A step on identities the Space has not resolved yet is refused until a resolve step is in force. A save the server refuses says the analysis was not saved, and gives the reason, usually a missing permission. Read the sentence before you try again: it is the server's own answer.
- Chip: refused · not saved
- Action: title card with the two sentences from user guide §16: *Merged step refused* and *The analysis was not saved*, each with its next step.
- Impact: mark each next step.

### S09 · Too big, too fast
- Voice: Two refusals protect the server. Too big: that is over a size limit, so nothing was stored. The next step is to narrow the step, fewer start entities or a shorter window, or to fork a smaller Investigation; an administrator can raise the limit. Too fast: too many requests. Every user has a short burst, then a steady pace, and the sentence says how many seconds to wait before you try again.
- Chip: over a size limit · Too many requests
- Action: title card with the two on-screen sentences (*That is over a size limit, so nothing was stored. …* and *Too many requests. Link Analysis limits how many requests each user can send in a short time. Wait 2 seconds and try again.*). [check in UI: the wait the 429 sentence names on the recording build]
- Impact: mark each sentence's next step.

### S10 · Not available
- Voice: Sign in as the outsider. The home page says you have no access to any Space: this Space admits only an administrator and the holders of a role it defines, and the outsider holds none. Open the link to the same Investigation, and it is not found, the same answer as for a Space that does not exist, on purpose, so that nobody can learn what is inside by asking for it. Ask an administrator to give you a role here.
- Chip: no access
- Action: sign out ▸ Demo Outsider ▸ hold on the home page ▸ address bar `/link-analysis?investigation=sim-swap-takeover` ▸ hold. [check in UI: the home page shows *You have no access to any Space*]
- Impact: mark the sentence.

### S11 · When you cannot even start
- Voice: The last kind of no comes before any work. On the sign-in page: single sign-on isn't set up, or the server could not start a session, with Try again. On a local install: no Space is attached yet, with where to copy one. And a page that started without a session asks you to reload to sign in. None of these is about your data. Each one is for the administrator who runs the server.
- Chip: Sign-in · No Space attached
- Action: title card: the sign-in page's four messages (*Single sign-on isn't set up*, *The server could not start a session* with Try again, *No Space attached*, *The server requires sign-in, but this page started without a session. Reload to sign in.*).
- Impact: mark *Try again* and the reload sentence.

### S12 · Try it
- Voice: One last no is a principle, not an error: a saved view is not evidence, because it reads live data, and only a snapshot or a Dossier is. Every refusal in Inspecto LA names its reason. Your turn. As the analyst, open the File menu and hover each greyed item. Which permission does each one name? The answer is in the description. Next, in I1, Access Policies on the Advanced edition.
- Action: outro card: *Try it* and *Next: I1 · Access Policies*.
- Impact: none.
