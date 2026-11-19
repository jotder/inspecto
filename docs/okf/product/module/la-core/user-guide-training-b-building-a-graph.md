---
type: Guide
title: "Inspecto LA training videos — B · Building a graph: narration scripts"
description: "The spoken script of the training episodes in group B (Building a graph), scene by scene, with the action on screen and the change it causes. Format and status: the overview, user-guide-training-scripts.md."
resource: tools/render-la-demo-video.mjs
tags: [module, la-core, link-analysis, user-guide, training, video]
timestamp: 2026-11-16T00:00:00Z
item: module.la-core
audience: internal
status: current
verified: 2026-11-18 last-edit
code: la/inspecto-la-core
---

# Inspecto LA training videos — B · Building a graph

> Narration scripts for group B of the Inspecto LA training series. How to read an episode, the voice and the
> renderer: [the overview](user-guide-training-scripts.md). Draft, not recorded, not reviewed by product; `[check in UI: …]` marks a detail to
> confirm on the live product before recording.

## Episodes in B

| Episode | Title | Edition | Scenes | Spoken words | UI checks open |
|---|---|---|---|---|---|
| [B1](#b1_start-a-graph) | Start a graph: one entity, a list, pasted values, the whole Dataset | Basic | 11 | 770 | 8 |
| [B2](#b2_preset-queries-and-build-a-question) | Preset queries and Build a question | Basic | 11 | 725 | 6 |
| [B3](#b3_explore-the-dataset-and-the-graph-model) | Explore the Dataset and the Graph model | Basic | 11 | 721 | 7 |
| [B4](#b4_find-and-filter-search-node-types-time-timeline) | Find and filter: search, node types, time, timeline | Basic | 12 | 846 | 4 |

## B1_Start-a-graph

> **B1 · Start a graph: one entity, a list, pasted values, the whole Dataset** · Basic · Telecom Space · SIM-swap
> takeover · 5 min · user guide §5.1

- **Objectives:** start a graph from one entity with the type-ahead; compare several values with a paste and read what
  was not found; know what an Entity List start and a whole-Dataset start draw, and when not to use the second.
- **Before:** a fresh unzip of `la-basic.zip`, the Telecom Space, Link Analysis open on an empty canvas (the Start area,
  tab *Start a graph*).
- **Covers (plan §5):** 5.2i Start a graph: Start from (One entity, Entity List, Pasted values, Whole Dataset), Dataset,
  the Data status line, Entity Type, the value box and its suggestions, the *what happens* line, the submit button
  (Draw / Start from this list / Start from these / Draw the whole Dataset), the start's result line, values not in the
  Dataset, the 25-value cap, the whole-Dataset warning, *Open, Explore Dataset and Examples are in the File menu*, Add
  data; 5.2a File ▸ New.
- **Try it:** start from One entity, `A-088`, one of the agents where the drained money was cashed out. Which wallets
  cashed out there? *Answer: settled at recording from the drawn graph (ground truth: the five mule wallets WL-07936 to
  WL-07940 cash out at A-088 and A-093).*
- **Check:** you paste 40 values. What does one start draw? *Answer: the first 25 and their links; the line above the
  canvas reports the other 15 as more values not drawn.*
- **Gotchas:** the Basic *Start a graph* box has **no Degrees field**: it always draws the start and one ring (Degrees is
  in the Investigation start form, F1); the plan's row is corrected here. `mfs_screen_links_dataset` has one Entity Type
  for both ends (*Telecom entity*), so the type is preselected. The Space has no Entity List on a fresh Basic unzip, so
  S08 shows the empty message; E2 builds lists. Busy limit 500: neither S-41 nor S-72 is held.

### S01 · Something you already know
- Voice: This is Inspecto LA training, episode B1: start a graph. Every graph starts from something you already know: one suspect, a list of them, a handful of values from a complaint, or, now and then, everything. In this episode you try each way of starting, and see what each one draws. We are in the Basic edition, in the Telecom Space, on a SIM-swap story: customers whose phones went dead, and whose wallets were empty two hours later.
- Action: intro card.
- Impact: none.

### S02 · The Start area
- Voice: Open Link Analysis with nothing drawn. The empty canvas is the Start area: four tabs over one panel, Start a graph, Preset queries, Build a question and Explore the Dataset. This episode is the first tab. Its heading says what it does: draw a few entities and their links, then Expand one degree at a time. Its first field, Start from, is the choice this episode is about: One entity, Entity List, Pasted values, or Whole Dataset.
- Chip: Start a graph · Start from
- Action: Link Analysis ▸ hold on the Start area's tab strip ▸ open *Start from* to show its four choices and hints ▸ keep One entity.
- Impact: mark the four tabs; mark the four choices.

### S03 · Pick the Dataset
- Voice: Pick the Dataset first: it is the table the graph is drawn from. Choose mfs_screen_links_dataset, the links the fraud screens selected. A Space with several Datasets lists them all here, so pick the one that matches the question you are asking, because the graph shows only what that table holds.
- Chip: Dataset · Data status
- Action: Dataset ▸ `mfs_screen_links_dataset` ▸ hold on the *Data status:* line. [check in UI: the status line's wording for this Dataset (*graph up to date*, or files behind)]
- Impact: mark the Data status line.

### S04 · One entity, with suggestions
- Voice: Start from One entity. Entity Type is the kind of thing you look for. This Dataset holds one, Telecom entity, so it is chosen for you. Now type the first letters of what you know: S-4. The suggestions say how busy each match is, in links, and when it was first and last seen. Pick S-41, a call-centre login. The line under the form says what Draw will do: draw it and its links; busy entities are held, and Expand goes further.
- Chip: One entity · Entity Type
- Action: Entity Type (preselected *Telecom entity*) ▸ value box: type `S-4` ▸ hold on the suggestion list ▸ pick `S-41` ▸ zoom on the *what happens* line. [check in UI: S-41's suggestion line (*N links, first seen …, last seen …*)]
- Impact: mark S-41's suggestion line, then the *what happens* line.

### S05 · Draw, and its receipt
- Voice: Press Draw. S-41 sits in the middle, with every number whose SIM it swapped around it. A line above the canvas says what was drawn: one entity and its linked entities, and that Expand goes one degree further. In Basic a start always draws one ring: the start, and everything linked to it directly. Thirty of those swaps fall within ten days in June. That burst is the story, and the result line is your receipt for what the start did.
- Chip: Draw
- Action: Draw ▸ wait for the draw ▸ hold on the start's result line above the canvas. [check in UI: the result line reads *Drew 1 entity and N linked …*; ground truth: 40 swapped_by links for S-41, 30 of them on 2026-06-08 to 06-17]
- Impact: before/after hold: empty canvas → S-41 and its ring; mark the result line.

### S06 · Pasted values
- Voice: Now compare logins. From the File menu choose New: it closes what is open and brings back Start a graph. Start from Pasted values, and paste one value per line: S-41; S-72, the busy store login; and S-99. Commas, semicolons and tabs separate values too. Press Start from these. Both logins are drawn with their swaps. S-72 swapped far more SIMs, in an upgrade drive, and its customers kept their money. More swaps is not the lead; what followed them is.
- Chip: Pasted values · Start from these
- Action: File ▸ New ▸ Start from: Pasted values ▸ Entity Type: Telecom entity ▸ paste `S-41`, `S-72`, `S-99` on three lines ▸ Start from these ▸ wait. [check in UI: S-99 exists nowhere in the Dataset; ground truth: S-72 made 160 swaps]
- Impact: before/after hold; mark S-72's much larger ring beside S-41's.

### S07 · What found nothing is said
- Voice: Read the line above the canvas. It names the value that found nothing: S-99 is not in the Dataset. A value with no match is reported, never quietly dropped, so a typo cannot pass for no activity. One start draws at most twenty-five values; a longer paste or list draws its first twenty-five and reports the rest as more values not drawn.
- Chip: Result line
- Action: zoom on the result line (*… S-99 is not in mfs_screen_links_dataset. …*). [check in UI: the sentence's exact wording]
- Impact: mark *S-99 is not in …*.

### S08 · Entity List
- Voice: Start from Entity List draws the members of a list you keep: suspects, or a watch list. This Space has no list yet, and the form says so, and where to make one: Settings, Entity Lists, or paste the values instead. Episode E2 builds lists, and from then on a list is the quickest start of all.
- Chip: Entity List
- Action: File ▸ New ▸ Start from: Entity List ▸ hold on the message (*No Entity List yet. Create one in Settings ▸ Entity Lists, or paste values.*). [check in UI: no Entity List on a fresh Basic Telecom Space; if one exists, pick it and say what it holds]
- Impact: mark the message.

### S09 · The whole Dataset, and its warning
- Voice: Start from Whole Dataset draws every link at once. Choose it, and before you press anything, the form says what it would do: draw the whole of the Dataset, but only its busiest one thousand nodes. One entity or an Entity List is usually clearer. The whole Dataset is for a small table, or for a first look at its busiest corner. The busiest corner is rarely where the story is.
- Chip: Whole Dataset
- Action: Start from: Whole Dataset ▸ hold on the warning ▸ do not press *Draw the whole Dataset*. [check in UI: the warning shows (more distinct entities than the 1,000-node cap); if the Dataset is within the cap, the form instead says *Draws the whole of … (busiest 1,000 nodes).* and the voice says it would all fit]
- Impact: mark the warning.

### S10 · The button follows the choice
- Voice: Notice the button's name: it follows your choice. Draw, Start from these, Start from this list, or Draw the whole Dataset, so you always know what you are about to do. Whichever start you use, the box draws and never records: nothing you started here is kept until you save a view. Open, Explore Dataset and Examples are in the File menu, and Add data, under the box, brings in a file of your own.
- Chip: Draw · Start from these · Start from this list
- Action: switch Start from through its four choices, holding on the button each time ▸ point at the line *Open, Explore Dataset and Examples are in the File menu.* and the Add data card.
- Impact: mark the button's label at each choice.

### S11 · Try it
- Voice: Your turn. Start from One entity, A-088, one of the agents where the drained money was cashed out. Which wallets cashed out there? The answer is in the description. Next, in B2, preset queries and Build a question.
- Action: outro card: *Try it* and *Next: B2 · Preset queries and Build a question*. [check in UI: A-088's ring in `mfs_screen_links_dataset` holds the mule wallets named in the answer]
- Impact: none.

## B2_Preset-queries-and-Build-a-question

> **B2 · Preset queries and Build a question** · Basic · Banking and finance Space · the card compromise at one fuel
> pump · 5 min · user guide §5.1 (*Preset queries*, *Build a question*)

- **Objectives:** ask a Dataset a ready question and narrow it; tell busy from suspicious; build a question of your own
  from its parts; draw an answer; save a question for the Space.
- **Before:** a fresh unzip of `la-basic.zip`, the Banking and finance Space, Link Analysis on an empty canvas.
- **Covers (plan §5):** 5.2i Preset queries (Dataset, Question, its parameters, Ask, the Answer card and Close the
  answer, *Answer cut short*, Draw these), Build a question (Rank, By, Measure to total, From is, To is, Kinds of link,
  When by, From day, Before day, Measure range on, At least, At most, Keep groups with at least, How many rows, Ask,
  Close the answer, Draw these, Save as preset: Name, Description, Save preset), a saved question in the list.
- **Try it:** ask *Reached by many* with the kind `charged_back`. Which merchant is reached by the most disputed cards,
  and is it M-317? *Answer (confirm at recording): not M-317: the chargeback leader is a merchant with its own story
  (ground truth: M-221 refunds cards it never sold to; M-140 has a one-month chargeback spike).*
- **Check:** a merchant tops *Reached by many*. Is it a lead? *Answer: not by itself: a popular shop is reached by many.
  Narrow the question (here, to cards later defrauded) before reading the top row as a lead.*
- **Gotchas:** the Banking Space states no Industry pack, so the Question list is the core set (no pack questions ahead
  of it). Every answer in S03, S04, S07 and S08 is confirmed against the live table before recording. A saved preset
  stays in the install.

### S01 · When you have a question, not a suspect
- Voice: This is Inspecto LA training, episode B2: preset queries and Build a question. Sometimes you have no suspect yet, only a question: who receives from many, who sends the most, which accounts trade back and forth. Inspecto LA asks those questions of a whole Dataset for you, without writing a query, and draws the answer. We are in the Basic edition, in the Banking and finance Space, on a card compromise: sixty cards defrauded online, weeks apart. Where were they all copied?
- Action: intro card.
- Impact: none.

### S02 · The questions every Space asks
- Voice: On the empty canvas, open the Preset queries tab. Pick the Dataset: card_links_dataset, where each link joins a card to a merchant, a device or a network. The Question list holds the questions every Space can ask: Reached by many, Reaching many, Two-way pairs, Short loops, Newcomers, Biggest senders by amount, Top counterparts of one entity, and Two steps from one entity. An Industry pack adds its own ahead of them, and a question this Dataset cannot answer is not offered at all.
- Chip: Preset queries · Question
- Action: Start area ▸ Preset queries ▸ Dataset `card_links_dataset` ▸ open Question ▸ hold on the list. [check in UI: the eight core questions in this order]
- Impact: mark the Question list.

### S03 · Reached by many: busy is not suspicious
- Voice: Choose Reached by many: entities that many different others link to, such as a collection account, a mule, or a popular shop. Its few values each have a default: at least five distinct senders, any kind of link, fifty rows. Press Ask. The answer is a table, and the top merchant is reached by hundreds of cards. It is a supermarket. Busy is not suspicious: everyone shops there.
- Chip: Reached by many · Ask
- Action: Question: Reached by many ▸ hold on its description and the three values ▸ Ask ▸ hold on the Answer's first rows. [check in UI: M-052 (Harvest Fair Superstore, about 900 cards) heads the answer; reword if another merchant leads]
- Impact: mark the top row.

### S04 · Narrow it to the kind that matters
- Voice: Narrow the question. In Only these kinds of link, type visited_before_fraud: the link from a defrauded card to each merchant it paid in the forty-five days before the fraud. Ask again. Now one row is left: M-317, a fuel station, reached by sixty defrauded cards. The supermarket is gone, because none of its customers was defrauded.
- Chip: Only these kinds of link
- Action: Only these kinds of link ✎ `visited_before_fraud` ▸ Ask ▸ hold. [check in UI: M-317 first with 60 distinct senders; ground truth also has 40 background fraud cards, so other merchants appear lower]
- Impact: before/after hold of the table; mark M-317's row.

### S05 · Draw these
- Voice: Under the answer, Draw these starts a graph from the listed entities, the top twenty-five first, the way a paste does. Press it. The page says what it drew: one entity and its linked nodes, with M-317 at the centre of its defrauded cards. To keep the table and draw nothing, close the answer with its cross instead. Every question asked is recorded, an answer holds at most five hundred rows, and a longer one says it was cut short.
- Chip: Draw these · Close the answer
- Action: Draw these ▸ wait for the draw ▸ Close the answer (×).
- Impact: before/after hold: empty canvas → the graph round M-317; mark M-317.

### S06 · Questions about one entity
- Voice: Two questions start from one entity. Top counterparts of one entity says who it links to most, how often, and how much. Two steps from one entity says who is around it: its direct counterparts, then theirs, and through how many of them. Choose Two steps, type M-317, and ask. The first rows are its cards, one step away, each with its links. The merchants those cards used are two steps away, and the answer says it was cut short at fifty rows: raise the limit, or sort by Steps away, to read further.
- Chip: Two steps from one entity
- Action: File ▸ New ▸ Preset queries ▸ Question: Two steps from one entity ▸ The entity `M-317` ▸ Ask ▸ hold. [check in UI: no second merchant is reached through more than a handful of the 60 cards; if one is, the voice names it as a lead to check]
- Impact: mark the *Steps away* column.

### S07 · Build a question, from its parts
- Voice: A preset is a question someone wrote for you. Build a question lets you write your own over the same Dataset. Open its tab. Rank chooses whose rows you rank: Receivers, the To end, or Senders, the From end. By chooses how: Most links, Most distinct counterparts, or Largest total of a Measure. The rest narrows the rows first: one entity at either end, some kinds of link, a period, a range of amounts, a smallest group, and how many rows. Only columns this Dataset gives that role are offered.
- Chip: Build a question · Rank · By
- Action: Start area ▸ Build a question ▸ open Rank, then By, then hover each field: From is, To is, Kinds of link, From day, Before day, Measure range on, At least, At most, Keep groups with at least, How many rows.
- Impact: mark each field as the voice groups it.

### S08 · Ask your own question
- Voice: Build it. Rank Receivers, by Most distinct counterparts. Kinds of link: visited_before_fraud. From day: the second of March, which is included; Before day: the twenty-third, which is not. Keep groups with at least ten, and ten rows. Press Ask. M-317 stands alone at the top: in three weeks of March, sixty cards that were later defrauded all paid at one pump. Close the answer closes the table when you are done.
- Chip: Ask
- Action: Rank: Receivers (To) ▸ By: Most distinct counterparts ▸ Kinds of link `visited_before_fraud` ▸ From day `2026-03-02` ▸ Before day `2026-03-23` ▸ Keep groups with at least `10` ▸ How many rows `10` ▸ Ask. [check in UI: M-317 first with 60; the window 2026-03-02..22 is the ground-truth compromise window]
- Impact: mark M-317's row and its count.

### S09 · Save as preset
- Voice: Draw these works here too. And when a question is worth asking again, press Save as preset. Give it a Name: Merchants defrauded cards passed through. The Description can stay blank, and then it is the question in words. Press Save preset. Everyone in this Space can now pick it.
- Chip: Save as preset
- Action: Save as preset ▸ Name ✎ *Merchants defrauded cards passed through* ▸ Description blank (its placeholder shows the sentence) ▸ Save preset.
- Impact: mark the success note.

### S10 · Your question in the list
- Voice: Back on Preset queries, your question is in the Question list, marked saved here, beside the ones the product ships. It is offered over any Dataset that has the columns it needs, and shown only to those it is shared with. Pick it and press Ask: next month's compromise is one click away.
- Chip: Question · saved here
- Action: Preset queries ▸ open Question ▸ pick *Merchants defrauded cards passed through (saved here)* ▸ Ask. [check in UI: the suffix reads *(saved here)*]
- Impact: mark the saved question in the list.

### S11 · Try it
- Voice: Your turn. Ask Reached by many with the kind charged_back. Which merchant is reached by the most disputed cards, and is it the one you found here? The answer is in the description. Next, in B3, explore the Dataset, and the Graph model.
- Action: outro card: *Try it* and *Next: B3 · Explore the Dataset and the Graph model*.
- Impact: none.

## B3_Explore-the-Dataset-and-the-Graph-model

> **B3 · Explore the Dataset and the Graph model** · Basic · Utilities Space · the contractor nobody suspected · 5 min ·
> user guide §3.4 (*Discover*), §5.1 (*About*), §5.2, §10 (*Explore Dataset…*, *Examples*)

- **Objectives:** read what a Dataset holds before drawing it; tell busiest from suspicious; draw a table yourself with
  the Graph model and refresh it; open the Space's ready-made money view.
- **Before:** a fresh unzip of `la-basic.zip`, the Utilities Space, Link Analysis on an empty canvas.
- **Covers (plan §5):** 5.2i Explore the Dataset (About <Dataset>, Show what is in it, the summary, Entities, Kinds of
  link, Measures, Busiest and a row's summary with Draw it, Draw these, Hide); 5.2a File ▸ Explore Dataset…, File ▸
  Examples ▸ Follow the money; 5.4 ⚙ Settings ▸ Graph model (Industry pack line, masking badge, Graph built from, Dataset
  picker with reasons, From column, To column, Link kind column, Attribute columns, Add another mapping, Derive mapping,
  Run, Refresh); *Result cut short*.
- **Try it:** draw `service_order_dataset` with File ▸ Explore Dataset…, its link kind `order_type`. Which contractor
  closed the reconnection orders of the premises cut off for arrears? *Answer: C-22 (ground truth: 15 premises
  reconnected with no payment; the 10 look-alikes paid and were reconnected by other contractors).*
- **Check:** Explore the Dataset's busiest contractor: lead or not? *Answer: not by itself; busiest is where the work
  is. Here the busy contractors C-27 and C-21 are the false lead, and C-14 is nowhere near the top.*
- **Gotchas:** Utilities states no Industry pack: Graph model shows *Generic graph*. `field_visit_dataset` holds a year
  of visits: drawn whole from the Graph model it may exceed the canvas's 1,000 nodes and show *Result cut short*; the
  voice states either outcome. *Follow the money* picks the view whose name and description score highest on money
  words; on this Space that is expected to be `prepaid_token_fraud_vendor` (confirm).

### S01 · Know what a table holds
- Voice: This is Inspecto LA training, episode B3: explore the Dataset, and the Graph model. Before you start from a suspect, it helps to know what a Dataset holds: what kinds of link, over what period, and who is busiest. And when no saved view or question fits, you can say yourself how a table becomes a graph. We are in the Basic edition, in the Utilities Space, where twenty-two electricity meters started reading low.
- Action: intro card.
- Impact: none.

### S02 · About this Dataset
- Voice: On the empty canvas, Start a graph has a Dataset field. Pick field_visit_dataset: one row per visit of a contractor to a premise. Now open the fourth tab, Explore the Dataset. Its title follows the Dataset you picked: About field_visit_dataset. Press Show what is in it.
- Chip: Explore the Dataset · Show what is in it
- Action: Start a graph ▸ Dataset `field_visit_dataset` ▸ tab Explore the Dataset ▸ hold on *About field_visit_dataset* ▸ Show what is in it.
- Impact: mark the title; the panel fills.

### S03 · One panel, before any drawing
- Voice: One panel answers what this Dataset holds. About how many premises and contractors, and in which columns. The kinds of link with their counts, here the visit types: meter readings, inspections, fault repairs, safety checks, and a few with no type at all. Every Measure's total and median, or, as here, the reason there is none: no column of this Dataset is a Measure. A part the Dataset cannot answer says why, never a zero nobody counted. The answer is kept until a new file arrives, so a second look is instant.
- Chip: Entities · Kinds of link · Measures
- Action: scroll the panel: the summary line ▸ Entities ▸ Kinds of link ▸ Measures. [check in UI: the Measures line for a Dataset with no Measure column (*Not totalled: …*), and the visit types listed]
- Impact: mark each part as the voice names it.

### S04 · The busiest, and one entity's summary
- Voice: Then the Busiest: two tables, the entities that link to the most others, and those reached by the most. The contractors reached by the most premises are C-27 and C-21. Click a row, and that entity's summary opens: its links in and out, its distinct counterparts, the dates it was first and last seen, its activity per day, and who it links with most. Close it when you have read it.
- Chip: Busiest · Draw it
- Action: Busiest ▸ hold on both tables ▸ click the C-27 row ▸ hold on the summary ▸ close (do not press Draw it). [check in UI: C-27 and C-21 head the *reached by* table]
- Impact: mark the two rows; mark the summary's per-day chart.

### S05 · Draw these, and the lesson
- Voice: Draw these starts a graph from the busiest at once. Press it. The page says what it drew: twenty entities and their linked nodes. The busy contractors fill the screen, and that is the lesson of this tab: busiest is where the work is, not where the fraud is. The contractor in this story, C-14, is only fifth in the table, an ordinary busy contractor. Explore the Dataset shows you what is ordinary, so that you recognise what is not.
- Chip: Draw these
- Action: Draw these ▸ wait for the draw ▸ hold. [check in UI: C-14 is not among the busiest drawn]
- Impact: before/after hold: empty canvas → the busiest contractors and their premises.

### S06 · File, Explore Dataset
- Voice: Now draw a table yourself. From the File menu, choose Explore Dataset…. A list of the Space's Datasets opens, the tables that look like links first, each with one line saying why, and the others under Not link-shaped. Pick one and Inspecto draws it for you, reading the two ends of each link from its columns. The place that says how rows become a graph is Settings, Graph model, and that is where we look next.
- Chip: File · Explore Dataset… · Graph model
- Action: File ▸ Explore Dataset… ▸ hold on *Industry pack: Generic graph* and the masking badge ▸ open Dataset ▸ hold on the first entries and their reasons. [check in UI: the badge reads *Masking off* on Basic]
- Impact: mark the Industry pack line, the badge and one reason line.

### S07 · Which column is which
- Voice: Choose field_visit_dataset. Inspecto reads its columns and draws it at once. Now open Settings, Graph model to see how. Its first line shows the Space's Industry pack, here the Generic words. Graph built from is Entity/Link, from a Dataset, and the Dataset is field_visit_dataset. From column premise_id and To column contractor_id are the two ends of a visit. Link kind column visit_type gives each kind a colour and a filter. Attribute columns ride on each link and feed the timeline and the tools. Add another mapping would stack a second pair of columns into the same graph. Derive mapping drafts all of this with the assistant, if you may author configuration. Change either column if the link should run the other way.
- Chip: From column · To column · Link kind column
- Action: Dataset `field_visit_dataset` ▸ check From column `premise_id`, To column `contractor_id` ▸ Link kind column `visit_type` ▸ Attribute columns `visit_at` ▸ hover Add another mapping and Derive mapping. [check in UI: the two columns are prefilled]
- Impact: mark the two prefilled columns.

### S08 · Run
- Voice: Close Settings. The graph is drawn: a year of visits, premises round the contractors who visited them. When a limit cuts the result, a notice says so, Result cut short, never a silent gap; this one fits whole. If a table is too big, narrow it, or start from one entity instead.
- Chip: Run · Result cut short
- Action: Run ▸ wait ▸ open the Graph counts pill ▸ hold on the *Result cut short* notice if shown. [check in UI: whether the drawn graph is cut; if it is not, the voice says "this one fits whole, and the Graph counts card says how many entities and links"]
- Impact: mark the notice and the counts.

### S09 · Refresh, and keeping the model
- Voice: Later, when new visits have arrived, Settings, Graph model, Refresh runs the same model again over the data as it is then. Graph built from also offers Entity/Link over several Datasets, where the same entity in two tables becomes one node that lists both. And Save view as keeps the model with the view, so the next person never rebuilds it.
- Chip: Refresh · Graph built from
- Action: Settings ▸ Graph model ▸ point at Refresh ▸ open *Graph built from* to show *Entity/Link (several Datasets)* ▸ close without changing.
- Impact: mark Refresh and the several-Datasets choice.

### S10 · Examples, Follow the money
- Voice: Last, the File menu's Examples. Follow the money opens the Space's ready-made view about money, and draws it now. Here it opens the prepaid token view: one vending point, V-08, credits forty premises with far more electricity than their money buys, mostly at night. In a Space with no such view, the item is greyed, and says why.
- Chip: File · Examples · Follow the money
- Action: File ▸ Examples ▸ hover Follow the money (tooltip *Opens “…”*) ▸ click ▸ wait for the draw. [check in UI: the tooltip names `prepaid_token_fraud_vendor`]
- Impact: before/after hold; mark V-08.

### S11 · Try it
- Voice: Your turn. Use File, Explore Dataset… to draw service_order_dataset, with order_type as its kind of link. Which contractor closed the reconnection orders of the premises cut off for arrears? The answer is in the description. Next, in B4, find and filter.
- Action: outro card: *Try it* and *Next: B4 · Find and filter*.
- Impact: none.

## B4_Find-and-filter-search-node-types-time-timeline

> **B4 · Find and filter: search, node types, time, timeline** · Basic · Other Examples Space (plan: Telecom ·
> Wangiri) · structuring into one account · 6 min · user guide §5.3, §5.4, §6

- **Objectives:** find a node without changing the graph; narrow by node type and by time; play a period back; filter
  rows in two stages and know which stage counts right; recognise the threshold that hides structuring.
- **Before:** a fresh unzip of `la-basic.zip`, the Other Examples Space, Link Analysis, the saved view *Structuring into
  one account* open (File ▸ Open…), the dock collapsed.
- **Covers (plan §5):** 5.2a Search nodes, Filter node types, Filter links by time (Time column, cutoff, *Up to … UTC*),
  Clear search and filters; 5.2g Graph counts (on-screen line, tiles, *cut short*) and Limits, timeline playback (Play /
  Pause, Previous slice, Next slice, Slice, Window, Age fade, Reset, status line); 5.2h Rows ▸ Filter (Filter predicate,
  AND / OR, NOT, + Condition, + Group, Apply locally, Push to server, the two stage counts, Clear the predicate); the
  "≥ 5 000" trap; the D-TR6 claim.
- **Try it:** pick `BOOKED_AT`, set the window to *This slice*, and step through. When do the hub's deposits arrive, and
  when does the money leave it? *Answer: settled at recording from the status line (ground truth: the landed files
  cover 1-3 September 2026; the hub passes almost all of it on within hours).*
- **Check:** you filter a view to amounts ≥ 5 000 and the structuring pattern finds nothing. Why? *Answer: the filter
  removed exactly the small deposits structuring consists of; pattern and Measure tools read the whole Dataset for that
  reason.*
- **Gotchas:** **Space substituted.** The Wangiri story is the Other Examples Space's `telecom_fraud_network`, not the
  Telecom Space, and that view carries no time column (only `via`), so Filter links by time and playback cannot run on
  it. `mule_structuring` carries `BOOKED_AT` and `AMOUNT`, and the Space ships its threshold twin *Large transfers only*.
  The view draws one kind of node (accounts), so Filter node types is shown, not used. The telecom pack's words apply.

### S01 · From what was drawn to what matters
- Voice: This is Inspecto LA training, episode B4: find and filter. A graph on screen is rarely the one you need: it holds too much, or the wrong part. Finding and filtering take you from what was drawn to what matters, and one kind of filter can hide exactly what you are looking for. We are in the Basic edition, in the Other Examples Space, on the saved view Structuring into one account.
- Action: intro card.
- Impact: none.

### S02 · How much is on screen
- Voice: Every transfer is here, each with its date and amount: twelve accounts paying cash deposits just under a thousand into one hub account, among the ordinary traffic. Along the top of the canvas sit the tools for this episode: Search nodes, Filter node types, Filter links by time, and, once something is searched or filtered, a cross to clear it all. Beneath the graph, the table holds every link as a row.
- Chip: Graph counts · Limits
- Action: Graph counts pill ▸ hold on the line *N entities · M links on screen* and the tiles ▸ Limits ▸ hold (*Graph drawn: up to 1,000 nodes*, *On screen tools: up to 2,500 nodes*) ▸ Escape. [check in UI: the tiles' labels under the telecom pack]
- Impact: mark the first line, the tiles and the Limits list.

### S03 · Fast at any size; Search nodes
- Voice: Search and filter stay fast on millions of records, because the graph draws only what you ask for. When a limit does cut what you asked for, the card says cut short, and a notice says what to do: never a silent gap. Now the rail's Search nodes. Type SMURF. The matching accounts glow, and everything else dims. Search finds; it removes nothing.
- Chip: Search nodes
- Action: Search nodes ▸ type `SMURF` ▸ hold ▸ Show the table ▸ Nodes ▸ hold ▸ Hide the table.
- Impact: before/after hold: the twelve SMURF accounts glow; mark them.

### S04 · Filter node types, and Clear
- Voice: Filter node types takes whole kinds of node off the drawing: tick the kinds to keep. This graph draws one kind, accounts, so here there is nothing to narrow. On a graph of cards, merchants and devices, it is the quickest way to look at one kind. A search and a filter both tint their buttons, and Clear search and filters, the cross that appears beside them, resets them all in one press.
- Chip: Filter node types · Clear search and filters
- Action: Filter node types ▸ hold on its one entry ▸ close ▸ Clear search and filters (×). [check in UI: the node-type list for this view; if it lists more than one kind, untick one and say which]
- Impact: before/after hold: the glow from S03 clears.

### S05 · Filter links by time
- Voice: Filter links by time, the clock, needs a time on the links. Pick the Time column, BOOKED_AT: only date and time columns are offered. A slider appears, with a line reading Up to, a moment, in UTC. Drag it back, and every link after that moment leaves the canvas. Drag it to the end, and they return. Times are read in UTC, so a cutoff means the same moment on every screen.
- Chip: Filter links by time · Time column
- Action: Filter links by time ▸ Time column `BOOKED_AT` ▸ drag the Timeline cutoff to about the middle ▸ hold ▸ drag to the end.
- Impact: before/after hold: links after the cutoff leave; mark the *Up to … UTC* line.

### S06 · Play the period back
- Voice: Once a time column is picked, a playback bar appears above the canvas. It splits the period into twenty slices, and its status line says so before you start. Press Play. Each slice lights the links active in it, and the status line counts them. Previous slice and Next slice step by hand, and the Slice bar jumps anywhere. Playback only lights links: it never removes one.
- Chip: Play · Slice
- Action: hold on *Not started. 20 slices from … to … UTC.* ▸ Play ▸ let it run five slices ▸ Pause ▸ Next slice ▸ Previous slice ▸ drag Slice.
- Impact: mark the lit links and the status line *Slice n of 20: … N links active.*

### S07 · Window and Age fade
- Voice: Window chooses how much each step shows: This slice, Last day, Last 7 days, Last 30 days, or From the start, which builds the picture up as it goes. Choose From the start and play again: the picture builds up as the days pass. Age fade is on by default: it dims each link by its age, so the newest shine brightest. Reset ends the playback.
- Chip: Window · Age fade · Reset
- Action: Window: From the start ▸ Play ▸ hold on the hub filling up, then the relays lighting ▸ tick Age fade ▸ Reset. [check in UI: the hub's deposits come before the onward payments in the playback]
- Impact: mark MULE-HUB-01, then RELAY-01 and RELAY-02 as they light.

### S08 · Filter rows, stage one
- Voice: The second way to filter sits under the graph. Show the table, then press Filter: the Filter predicate opens. It is one group of conditions, joined by AND or by OR, and NOT turns a group around. Add a condition: AMOUNT, greater than or equal, five thousand. Press Apply locally. Stage one runs in your browser, at once, over the links already loaded, and the panel counts how many match, out of how many.
- Chip: Filter predicate · Apply locally
- Action: Show the table ▸ Filter ▸ + Condition ▸ Field `AMOUNT`, Operator `≥`, Value `5000` ▸ Apply locally ▸ hold on *Stage 1 · local (browser)* *m / n*.
- Impact: before/after hold: the canvas keeps only the large transfers; mark the stage-1 count.

### S09 · Stage two, and the trap
- Voice: Look at the canvas: every small deposit is gone, all twelve accounts, the whole structuring ring. Only the hub's wires to the relays, the relays' payments offshore, and a small shell group remain. Push to server sends the same condition to the Dataset itself, so it applies before the rows fold into links, and counts stay right on large data. Deposits kept just under the limit slip under any amount threshold. That is what structuring is for.
- Chip: Push to server
- Action: Push to server ▸ wait ▸ hold on *Stage 2 · pushdown (Dataset)* (*N links · complete*) ▸ hold on the canvas.
- Impact: before/after hold: the ring → hub, relays and OFFSHORE-77 only; mark where the SMURF accounts were.

### S10 · Why the tools read the whole Dataset
- Voice: This is why the pattern and Measure tools read the whole Dataset, not your filtered picture, and why this Space ships a view called Large transfers only: compare it with this one, and see what a threshold alone would miss. Clear the predicate with its cross, and push again to bring every row back.
- Chip: Clear the predicate
- Action: Clear the predicate (×) ▸ Push to server ▸ wait for the full ring.
- Impact: before/after hold: the ring returns.

### S11 · A group that finds the shape
- Voice: A filter can find the shape instead of hiding it. Group adds a nested group of conditions: with AMOUNT at least nine hundred, and AMOUNT less than one thousand, joined by AND, it would keep only the band just under the limit. A second group, joined by OR, would add another band beside it. Every condition lives inside a group, so the logic is always written out.
- Chip: + Group · AND · OR
- Action: + Group ▸ in it: + Condition `AMOUNT ≥ 900`, + Condition `AMOUNT < 1000`, operator AND ▸ Apply locally ▸ hold ▸ Clear the predicate. [check in UI: how many ordinary transfers fall in the 900-999 band; reword "very little else" to what the stage-1 count shows]
- Impact: before/after hold: the twelve SMURF links into MULE-HUB-01 stand out; mark the stage-1 count.

### S12 · Try it
- Voice: Your turn. Pick BOOKED_AT, set the window to This slice, and step through the slices. When do the hub's deposits arrive, and when does the money leave it? The answer is in the description. Next, in C1, moving around and selecting.
- Action: outro card: *Try it* and *Next: C1 · Moving around and selecting*.
- Impact: none.
