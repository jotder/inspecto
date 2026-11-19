---
type: Guide
title: Inspecto LA training videos — D · The analysis toolbox: narration scripts — Episodes D1-D3 — quick questions, connections, who matters
description: Part 1 of 3 of Inspecto LA training videos — D · The analysis toolbox: narration scripts: Episodes in D; D1_Quick-questions-busiest-tight-groups-route; D2_How-are-they-connected; D3_Who-matters-most
tags: [module, la-core, link-analysis, user-guide, training, video]
timestamp: 2026-11-16T00:00:00Z
item: module.la-core
audience: internal
status: current
verified: 2026-11-20 last-edit
code: la/inspecto-la-core
---

# Inspecto LA training videos — D · The analysis toolbox: narration scripts — Episodes D1-D3 — quick questions, connections, who matters

Part 1 of 3 of [Inspecto LA training videos — D · The analysis toolbox: narration scripts](user-guide-training-d-analysis-toolbox.md) · next: [Episodes D4-D6 — groups, the unusual and pattern match](user-guide-training-d-analysis-toolbox-patterns.md).

## Episodes in D

| Episode | Title | Edition | Scenes | Spoken words | UI checks open |
|---|---|---|---|---|---|
| [D1](#d1_quick-questions-busiest-tight-groups-route) | Quick questions: busiest, tight groups, route A to B | Basic | 11 | 861 | 8 |
| [D2](#d2_how-are-they-connected) | How are they connected? | ? | 11 | 872 | 8 |
| [D3](#d3_who-matters-most) | Who matters most? | Basic | 11 | 976 | 9 |
| [D4](user-guide-training-d-analysis-toolbox-patterns.md#d4_groups-and-weak-points) | Groups and weak points | Basic | 11 | 831 | 7 |
| [D5](user-guide-training-d-analysis-toolbox-patterns.md#d5_what-looks-unusual) | What looks unusual? | Basic | 11 | 810 | 8 |
| [D6](user-guide-training-d-analysis-toolbox-patterns.md#d6_pattern-match) | Pattern match | Basic | 11 | 841 | 8 |
| [D7](user-guide-training-d-analysis-toolbox-money-scope.md#d7_money-and-time) | Money and time: Value Measures, bursts and rhythms | ? | 11 | 832 | 7 |
| [D8](user-guide-training-d-analysis-toolbox-money-scope.md#d8_on-screen-vs-whole-dataset) | On screen vs Whole Dataset: limits and scale | Basic → Standard | 11 | 889 | 7 |

## D1_Quick-questions-busiest-tight-groups-route

> **D1 · Quick questions: busiest, tight groups, route A to B** · Basic · Other Examples Space · the till structuring ·
> 6 min · user guide §8 (Quick questions)

- **Objectives:** ask the three Quick questions of a graph and read each answer as a sentence; act on an answer with
  Show on graph, Start from these and Add to a list; treat the exclusion hint as a suggestion you judge, not an order.
- **Before:** a fresh unzip of `la-basic.zip`, the Other Examples Space, File ▸ Open… ▸ the saved view *Structuring into
  one account* (`mule_structuring`), the toolbox on its **On screen** tab, and **Filter links by time** ▸ Time column
  `BOOKED_AT` (the view's own description asks for it; the group sentences then say how many days they span).
- **Covers (plan §5):** 5.3 Quick questions (Busiest nodes, Tight groups, Route from A to B: From, To, Find route), the
  result actions (Show on graph, Start from these, Add to a list, the *Add to exclusion list* hint); 5.2h the dock's
  Results tab (its empty line on Basic; it fills in D8).
- **Try it:** ask Route from A to B from GOV-BENEFITS to SKIMMER-01. How many links, and through whom? *Answer: two
  links, through one of the six BENEFICIARY accounts: the benefits payer pays a beneficiary, who passes part of it on to
  the skimmer.*
- **Check:** the busiest node gets the hint *Add it to an exclusion list?*. Should you always say yes? *Answer: no. The
  hint fires when one entity is far busier than the next, which usually means a shared service; here it is the planted
  hub, the lead itself.*
- **Gotchas:** the Other Examples Space speaks the Telecom Industry pack's words outside an Investigation, so the reading
  sentences say *calls* for these bank transfers; the voice says *links*. Every transfer is its own link on this view, so
  the counts are transfers (MULE-HUB-01 100, MULE-HUB-02 and TILL-06 24 each, GOV-BENEFITS and SKIMMER-01 18 each, from
  `tools/gen-la-showcase-data.py`). The plan's Space was Banking; the till story lives only in Other Examples
  (the case video *Banking_TillStructuring*).

### S01 · Three questions before any tool
- Voice: This is Inspecto LA training, episode D1: quick questions. Before you learn a single algorithm, there are three questions anyone can ask of a graph. Who is busiest? Which entities stick together? And how does one entity reach another? Inspecto LA answers each in a plain sentence, and every answer comes with what you can do next. In this episode you will ask all three, act on the answers, and learn when to ignore a helpful suggestion. We are in the Basic edition, in the Other Examples Space.
- Action: intro card.
- Impact: none.

### S02 · A bank, and a ring hiding in it
- Voice: Open the saved view called Structuring into one account, from the File menu, Open. Each node is a bank account or a cash till, and every transfer stays on the graph as its own link, with its date and its amount. Most of it is ordinary traffic between ordinary accounts. Somewhere in it, a second ring is turning money back into cash. Every ring needs an exit, and every till looks busy. The question is which one.
- Chip: File · Open… · Saved views
- Action: File ▸ Open… ▸ Saved views ▸ *Structuring into one account* ▸ Open ▸ wait for the draw. [check in UI: the view's title as the Open dialog lists it]
- Impact: mark the dense group round MULE-HUB-01, then the scattered ordinary accounts.

### S03 · Where the Quick questions live
- Voice: The Quick questions sit at the top of the toolbox, on the On screen tab, above every other tool. Three buttons: Busiest nodes, Tight groups, and Route from A to B. They are asked of what is on screen, nothing else. Before any graph is drawn, the panel says so in one line, so you never wonder why a button did nothing. Here the graph is drawn, so press Busiest nodes.
- Chip: Toolbox · On screen · Quick questions
- Action: zoom on the *Quick questions* heading and its three buttons ▸ Busiest nodes.
- Impact: mark the three buttons.

### S04 · Busiest nodes
- Voice: The answer is five rows, busiest first, each one a sentence. MULE-HUB-01 takes part in a hundred links, the most on this graph. Then two names with twenty-four each: a second hub, MULE-HUB-02, and a till, TILL-06. Then a benefits payer and a skimmer, with eighteen each. Busiest nodes counts links on the graph you can see. It is the quickest way to find what holds a picture together, and the quickest way to be misled.
- Chip: Busiest nodes
- Action: hold on the result's heading and its five rows. [check in UI: the five rows and their counts; the Telecom pack's words make the sentences say *calls*, the voice keeps *links*]
- Impact: mark each row as the voice names it; video rings on MULE-HUB-01, MULE-HUB-02 and TILL-06.

### S05 · A suggestion, not an order
- Voice: Under the top row is a question: MULE-HUB-01 has a hundred links, add it to an exclusion list? The product asks this when one entity is far busier than the next, at least twice as busy. Most of the time that is a shared service: an electricity company, a payroll account, a call centre. Leaving it out lets the real links show. But read before you press. This is the hub the deposits pour into. Excluding it would hide your lead. Leave it.
- Chip: Add to exclusion list
- Action: zoom on the hint line and its *Add to exclusion list* button; do not press it. [check in UI: the hint's exact wording]
- Impact: mark the hint; do not mark the button as pressed.

### S06 · Show on graph
- Voice: Every answer row offers the same three actions. The first is Show on graph. On the TILL-06 row, press it. The till and its links light up, and the canvas centres on it. Twenty-four cash-outs, all at one till. Is that a lot? The other tills serve many customers, a little each. Showing the answer where it sits is how you decide whether a number is strange or just big.
- Chip: Show on graph
- Action: the TILL-06 row ▸ Show on graph.
- Impact: before/after hold: the canvas centres on TILL-06; its links glow; mark them.

### S07 · Tight groups
- Voice: Now the second question. Press Tight groups. It finds groups of three or more that link mostly among themselves, largest first, and reads each in a sentence: how many accounts, how many links among them, within how many days, and a few of their names. The hub with its twelve depositors and two relays is one group. The second hub, eight accounts and TILL-06 are another. The benefits payer and its payees, and four shell companies, make two more. Press Show on graph on the group with the till.
- Chip: Tight groups
- Action: Tight groups ▸ hold on the rows ▸ Show on graph on the row naming TILL-06. [check in UI: the group order and sizes; Louvain may also split the ordinary accounts into groups that rank among the five]
- Impact: mark each planted group's row; the till group glows on the canvas.

### S08 · Route from A to B
- Voice: The third question joins two entities. Press Route from A to B, and pick From: MULE-HUB-02, and To: TILL-06. Until both are picked, Find route stays off and says why. Press it. MULE-HUB-02 reaches TILL-06 in two links, through one account. The route follows the direction of the links. When there is no route that way, it looks in either direction and says so, so a reversed link never passes for a path.
- Chip: Route from A to B · Find route
- Action: Route from A to B ▸ From `MULE-HUB-02` ▸ To `TILL-06` ▸ Find route. [check in UI: the sentence and which CASHER account it names]
- Impact: mark the reading sentence; the two-link route glows after Show on graph.

### S09 · Start from these
- Voice: The second action is Start from these. Go back to Busiest nodes, and on the TILL-06 row press it. It draws a new graph from that row, the till and one ring around it. Here it is: TILL-06, and eight accounts, CASHER-01 to CASHER-08, three cash-outs each. That is every customer this till has, and the same second hub feeds them all. We are only exploring, so nothing is recorded; the earlier graph is one File, Open away.
- Chip: Start from these
- Action: Busiest nodes ▸ the TILL-06 row ▸ Start from these ▸ wait for the draw. [check in UI: the ring drawn is TILL-06 and CASHER-01…08]
- Impact: before/after hold: the big graph → TILL-06 and eight accounts; mark the eight.

### S10 · Add to a list, and the Results tab
- Voice: The third action keeps what you found. On the till's row, press Add to a list. The dialog asks for a purpose in plain words: watch these, exclusion, known good, or block, then a list and a reason, which is recorded. Choose Watch these, and a new list for tills to review. Episode E2 is about lists. One more place to know: the dock's Results tab. On Basic it holds one line, because long answers from the server land there, in the Standard edition. That is episode D8.
- Chip: Add to a list · Dock · Results
- Action: the TILL-06 row ▸ Add to a list ▸ purpose *Watch these* ▸ new list `Tills to review` ▸ reason `One till serves only one ring` ▸ confirm ▸ the dock's Results tab ▸ hold on its line. [check in UI: the Add to a list dialog's fields on Basic, and that the list is written without a sign-in]
- Impact: mark the confirmation message, then the Results tab's line.

### S11 · Try it
- Voice: Three questions, three actions: Show on graph, Start from these, Add to a list, and one suggestion you judge for yourself. Your turn. Draw the saved view again, and ask Route from A to B, from GOV-BENEFITS to SKIMMER-01. How many links, and through whom? The answer is in the description. Next, in D2, how are they connected: paths, all of them, and the flow and backbone tool.
- Action: outro card: *Try it* and *Next: D2 · How are they connected?*. [check in UI: the route is two links through one BENEFICIARY account]
- Impact: none.

## D2_How-are-they-connected

> **D2 · How are they connected?** · Standard (exploring, no Investigation open) · Banking and finance Space · the
> circular trade · 7 min · user guide §8 (Shortest path, All paths, `Flow and backbone`, Find paths)

- **Objectives:** find the shortest route between two entities by hops and by strength; list every route; say how much
  could pass between two entities and where it would be cut; search the whole Dataset for paths the screen does not hold.
- **Before:** a fresh unzip of `la-standard.zip`, the Banking and finance Space, signed in as Demo Analyst, no
  Investigation open, File ▸ Open… ▸ the saved view `circular_trade`, the toolbox on its **On screen** tab.
- **Covers (plan §5):** 5.3 Shortest path (From, To, Fewest hops, Strongest ties, Find shortest path, the hop list), All
  paths (Find all paths, up to 10, parallel links counted once), `Flow and backbone` (Find maximum flow, the minimum cut,
  Strongest backbone), Find paths on the Whole Dataset tab (From, To (optional), Longest path (hops), Outgoing / Both ways,
  the answer line, where it was answered).
- **Try it:** with Fewest hops, find the shortest path from CO-00388 to CO-00941, then open All paths between the same
  two. Why does the shortest path alone undersell how they are related? *Answer: the shortest path is one hop, their
  direct trade; All paths also shows the route through CO-00213. Each of the three companies trades directly with the
  other two (two trades per pair): a closed loop.*
- **Check:** what does the minimum cut of a maximum flow tell you? *Answer: the fewest links that, removed, would stop
  anything passing between the two entities: the choke points of that route.*
- **Gotchas:** Find paths is a server read on the **Whole Dataset** tab, which Basic does not show, so this episode
  records on Standard; the three On screen tools are the same in Basic, and the voice says so. Two link kinds are on this
  view: `shipped_in` (a company to a container, weighted by bills of lading) and `traded_with` (a company to a company,
  weighted by the trade value). The plan listed D2 as Basic.

### S01 · One question, four tools
- Voice: This is Inspecto LA training, episode D2: how are they connected? Two names come up in a case. Are they linked, how closely, through whom, and how much could move between them? The toolbox answers each of those with a different tool, and all of them sit under one heading. In this episode you will find the shortest route, every route, the most that could flow, and the paths the screen does not even hold. We are in the Standard edition, in the Banking and finance Space, signed in as the analyst, and only exploring.
- Action: intro card.
- Impact: none.

### S02 · Six boxes that keep coming back
- Voice: Open the saved view called circular trade, from the File menu, Open. Each node is a company or a shipping container. A company links to the boxes it shipped, and a company links to the companies it traded with, with the trade's value on the link. Somewhere here, three companies ship the same six containers round to each other, and the invoice grows on every leg. Nearby, a leasing pool's boxes are carried for ten shippers each, and that is ordinary.
- Chip: File · Open… · Saved views
- Action: File ▸ Open… ▸ Saved views ▸ `circular_trade` ▸ Open ▸ open the Legend pill. [check in UI: the link kinds `shipped_in` and `traded_with` as the legend names them]
- Impact: mark CO-00941, CO-00388 and CO-00213, then a leasing-pool box with its ten shippers.

### S03 · Shortest path, by hops
- Voice: In the toolbox, under How are they connected, open Shortest path. Pick From: CO-00941, and To: CO-00213. Leave Fewest hops pressed, and press Find shortest path. The answer is a numbered list of the hops, in order, and the route glows on the canvas. Click any hop to centre on it. Fewest hops answers the plainest question: how few steps apart are these two? Everything in this episode except the last tool works the same in the Basic edition.
- Chip: Shortest path · Fewest hops
- Action: How are they connected? ▸ Shortest path ▸ From `CO-00941` ▸ To `CO-00213` ▸ Fewest hops ▸ Find shortest path ▸ click the second hop. [check in UI: the hop list; whether the route is the direct trade link or runs through CO-00388; reword to what the list shows]
- Impact: mark the numbered hops and the glowing route.

### S04 · Strongest ties
- Voice: Short is not the same as strong. Press Strongest ties, and run it again. Now the route prefers links with the highest weight, here the largest trade values and the most bills of lading, even if that takes an extra step. Use it when a thin, one-off link should not count as much as a relationship that carries real money. If the two answers differ, the difference is worth a look: one route is quick, the other is where the money goes.
- Chip: Shortest path · Strongest ties
- Action: Strongest ties ▸ Find shortest path. [check in UI: whether the strongest route differs from the fewest-hops route; if not, the voice says both routes agree here]
- Impact: before/after hold of the hop list; mark the hops that changed.

### S05 · Every route, up to ten
- Voice: One route can hide the others. Open All paths, pick From: CO-00941 and To: CO-00388, and press Find all paths. It lists every route between them, up to ten. Two links between the same pair count as one route, and the row says how many links it stands for. Here the two companies are joined directly, through the third company, and through each of the six containers they both shipped. Many separate routes between two parties are not chance. They are a relationship.
- Chip: All paths · Find all paths
- Action: All paths ▸ From `CO-00941` ▸ To `CO-00388` ▸ Find all paths ▸ click two rows in turn. [check in UI: the routes listed, how many, and the parallel-link counts]
- Impact: the union of the routes glows; mark each row as it is clicked.

### S06 · The flow and backbone tool: how much could move
- Voice: The next tool asks a different question: not whether money could move from one company to another, but how much. Open the flow and backbone tool, pick From: CO-00941 and To: CO-00213, and press Find maximum flow. Each link can carry as much as its weight. The answer says the maximum that could pass, and highlights the minimum cut: the fewest links that, if they were gone, would stop everything getting through. Those are the choke points of the scheme.
- Chip: `Flow and backbone` · Find maximum flow
- Action: How are they connected? ▸ `Flow and backbone` ▸ From `CO-00941` ▸ To `CO-00213` ▸ Find maximum flow. [check in UI: the result line *Maximum flow: … links in the minimum cut are highlighted* and which links it marks]
- Impact: mark the result line; mark the cut links on the canvas.

### S07 · Strongest backbone
- Voice: Beside it, Strongest backbone needs no start or end. It keeps the strongest links that still join each part of the graph, and drops every weaker link that only repeats a connection. The line says how many links connect how many nodes. Use it on a crowded graph to see its skeleton: what holds it together when the noise is taken away. Here the backbone runs through the three companies' trades, and the leasing pool hangs off it on thin links.
- Chip: Strongest backbone
- Action: Strongest backbone ▸ hold on the line and the canvas. [check in UI: the line *Backbone: … links connect … nodes* and that the three companies' `traded_with` links are on the backbone]
- Impact: mark the result line; the backbone links glow.

### S08 · Beyond the screen: Find paths
- Voice: All of that ran on the graph you can see. A saved view is a slice, and a route may leave it. Switch to the Whole Dataset tab, the server's side of the toolbox, which the Standard edition adds. It lists the same questions, and more: Shortest path, Neighbourhood, Ego network, Descendants, Strongest path, Strongest backbone, and All paths. Each says how big a graph it answers instantly, up to a hundred thousand nodes, far more than the screen holds.
- Chip: Whole Dataset · Run on server
- Action: the toolbox's Whole Dataset tab ▸ How are they connected? ▸ Find paths ▸ From `CO-00941` ▸ To (optional): *Any node* ▸ Longest path (hops) `4` ▸ Outgoing ▸ Find paths.
- Impact: mark the Whole Dataset tab, then the button.

### S09 · Reading the server's answer
- Voice: Open Shortest path here. Beside From and To is a Direction: outgoing, incoming or both ways, and Run on server. These tools search the whole Dataset, not just the graph on screen, and add any new node they reach. They run over an open Investigation, so the button waits, and the card says so: open or start one first. Episode F1 starts an Investigation, and the Whole Dataset tools come into their own there.
- Chip: Find paths · answer line
- Action: hold on the answer line and the line under it ▸ click the first path row. [check in UI: the path count, the depth line, and whether the source line says link index or Dataset]
- Impact: mark the answer line; new nodes from the click are marked yellow.

### S10 · Which tool for which question
- Voice: So: Shortest path, by fewest hops, for how close; by strongest ties, for the route the money prefers. All paths for how many separate ways they are joined. Find maximum flow for how much could pass and where it would be cut, and Strongest backbone for the skeleton of a crowded graph. And the Whole Dataset tab, over an Investigation, when the answer may lie outside what you drew. Same two names, five different questions.
- Chip: How are they connected?
- Action: collapse the tools and point at each row in turn on the On screen tab, then Find paths on the Whole Dataset tab.
- Impact: mark each row as the voice names it.

### S11 · Try it
- Voice: Your turn. On the On screen tab, with Fewest hops, find the shortest path from CO-00388 to CO-00941. Then open All paths between the same two. Why does the shortest path alone undersell how they are related? The answer is in the description. Next, in D3, who matters most: one question that turns out to be several.
- Action: outro card: *Try it* and *Next: D3 · Who matters most?*. [check in UI: Fewest hops gives the one-hop direct trade, and All paths lists the route through CO-00213]
- Impact: none.

## D3_Who-matters-most

> **D3 · Who matters most?** · Basic · Law enforcement Space · the burglary series · 7 min · user guide §8

- **Objectives:** read a node in words with Explain node; rank a graph by each centrality and say the question each
  answers; read a disagreement between two rankings as a lead.
- **Before:** a fresh unzip of `la-basic.zip`, the Law enforcement Space, the saved view *burglary_series* open, the toolbox on its
  **On screen** tab.
- **Covers (plan §5):** 5.3 the four question groups, *Up to N nodes* chip, Explain node, Centrality (Rank by: Degree,
  Betweenness, Closeness, Eigenvector, Katz, PageRank, Hub score (HITS), Authority score (HITS)), Rank nodes, the ranking
  table, a ranking row selects and centres; *Start an Investigation from the top results* (its Basic hint).
- **Try it:** rank by Degree, then by Betweenness, and write down the top three each time. Which node is in one top three
  and not the other, and what does that say about its role? *Answer: settled at recording from the two tables (expected:
  the plate ZX 9117 X ranks high on Degree, the household that joins the sellers to the plate's keeper on Betweenness).*
- **Check:** which centrality would you use to find a go-between? *Answer: Betweenness.*
- **Gotchas:** **Rank nodes** fills a table (the top 20); it does not restyle the canvas. The yellow rings on the top
  three are the video's marks, and the voice says that a click on a row selects and centres the node. Every rank named
  in a Voice line is confirmed against the live table before recording (`[check in UI]` on its Action line).

### S01 · Several questions in one
- Voice: This is Inspecto LA training, episode D3: who matters most? It sounds like one question. It is several. Who has the most links? Who sits between the others? Who can reach everyone fastest? Who is linked to the important ones? Inspecto LA answers each with a different score, and on the same graph each one can name a different node. Knowing which question you are asking is the skill this episode teaches. We are in the Basic edition, in the Law enforcement Space.
- Action: intro card.
- Impact: none.

### S02 · The burglary series
- Voice: Open the saved view of the burglary series, from the File menu, Open. Each node is an incident, a person, a dealer, an address or a vehicle plate. A burglary links to the person who later sold an item with its stolen serial number. Sellers link to the dealers they sold to, people to the address they live at, a keeper to the plate they keep, and a plate to the scenes it was seen near. Thirteen burglaries in Leeds, from February to April, meet in one group. The other matches are one-off sellers, off to the side.
- Chip: File · Open… · Saved views
- Action: File ▸ Open… ▸ saved view *burglary_series* ▸ wait for the draw ▸ open the Legend pill. [check in UI: the saved view's title in the Open dialog, and the link-kind names in the legend]
- Impact: mark the big group, then the one-off sellers at the side.

### S03 · Where the tools are
- Voice: The toolbox on the right is ordered by the questions you might ask: how are they connected, who matters most, which groups and weak points exist, and what looks unusual. Each tool says in one line what it does, and how big a graph it takes on screen. The On screen tab works on the graph you can see, and this one is well within its limit. Open Who matters most.
- Chip: Toolbox · On screen
- Action: the toolbox's On screen tab ▸ point at the four group titles ▸ open *Who matters most?* ▸ zoom on a row's *Up to N nodes* chip.
- Impact: mark the group title and the chip.

### S04 · Explain node: words before numbers
- Voice: Start with one person, before any ranking. Explain node describes a node in plain words: what it links to, in which direction, and by which kind of link. Pick P-00476 and press Explain node. The answer is a sentence, not a score. Nine burglaries link in to this person, through stolen serials; out from them go links to the dealer DLR-04, where the items were sold, and to the address where they live. A ranking will try to say with one number how much this person matters. Explain node tells you why.
- Chip: Explain node
- Action: Explain node ▸ Node `P-00476` ▸ Explain node ▸ hold on the sentence. [check in UI: the sentence's exact wording, and what the Hops field changes in the browser answer]
- Impact: mark the sentence; mark P-00476 on the canvas.

### S05 · Degree: who has the most links
- Voice: Now rank. Under Centrality, set Rank by to Degree, and press Rank nodes. Degree is the simplest question: who has the most links? The table lists the top twenty, highest first. The plate ZX 9117 X comes first, with fifteen links, and the seller P-00476 second, with twelve. Click a row, and the graph selects that node and centres on it. Degree is quick and honest, but it counts every link the same, so a busy node always wins.
- Chip: Centrality · Rank by · Degree
- Action: Centrality ▸ Rank by: Degree ▸ Rank nodes ▸ click the ZX 9117 X row. [check in UI: ZX 9117 X is in Degree's top three]
- Impact: the ranking table appears; mark the top three rows; the canvas centres on the plate (video ring).

### S06 · Betweenness: the go-between
- Voice: Betweenness asks a different question: who sits on the paths between the others? It finds the go-between, the node that, if you took it away, would leave parts of the network unable to reach each other. Rank by Betweenness, and watch the order change. The seller P-00476 now comes first, ahead of the plate, and the dealer DLR-04 moves up to third. The seller is the join the case turns on: the burglaries point to them through the stolen goods, and they lead on to the dealers and the address.
- Chip: Centrality · Betweenness
- Action: Rank by: Betweenness ▸ Rank nodes ▸ click the top row. [check in UI: ADR-1377, P-05167 or P-00476 lead the table and ZX 9117 X ranks lower than under Degree; reword to what the table shows]
- Impact: before/after hold of the table: the order changes; mark the rows that moved up.

### S07 · Closeness: the centre
- Voice: Closeness asks who can reach everyone else in the fewest steps. A high score is a node in the middle of its group, close to everything. Rank by Closeness, and click the top row. Here the seller P-00476 is first, with the dealer DLR-04 next, and the plate drops to ninth. Use closeness when you want to know who would hear about something first, or where a message, or money, would pass soonest.
- Chip: Centrality · Closeness
- Action: Rank by: Closeness ▸ Rank nodes ▸ click the top row. [check in UI: how closeness scores the small one-off pieces of this graph; if one of them tops the table, the voice says that a tiny separate piece can score high because everything in it is close]
- Impact: mark the top row and the node.

### S08 · Eigenvector, PageRank and Katz: the company a node keeps
- Voice: The next three ask: who is linked to the important ones? Eigenvector scores a node high not for how many links it has, but for having links to nodes that themselves score high. PageRank is a close cousin that follows the direction of the links: a node scores high when important nodes point to it. Katz is the same idea with a little credit for every node. Rank by each in turn. Eigenvector and Katz put the plate first and the seller second. PageRank puts the seller first, then the dealers DLR-04 and DLR-09: the places the stolen goods end up.
- Chip: Centrality · Eigenvector · PageRank · Katz
- Action: Rank by: Eigenvector ▸ Rank nodes ▸ PageRank ▸ Rank nodes ▸ Katz ▸ Rank nodes. [check in UI: eigenvector scores the one-off pieces at or near 0 and Katz above it]
- Impact: three table states, each held 2 s; mark the bottom rows under Eigenvector and under Katz.

### S09 · Hub and Authority: reading direction
- Voice: The last pair reads the direction of the links. Authority score asks who is pointed to by good pointers. Hub score asks the reverse: who points to good authorities? Rank by Authority score. The top of the table is a long run of burglaries, all scored the same: they are what the plate points to. Now Hub score. The plate comes first, alone, with a score of one. Two halves of one idea, reading which way the links run.
- Chip: Centrality · Hub score (HITS) · Authority score (HITS)
- Action: Rank by: Authority score (HITS) ▸ Rank nodes ▸ Rank by: Hub score (HITS) ▸ Rank nodes. [check in UI: P-00476 tops Authority and ZX 9117 X tops Hub; reword to what the tables show]
- Impact: mark the top row of each table.

### S10 · So who matters most?
- Voice: So who matters most? It depends on the question. Degree finds the busiest node. Betweenness, the go-between. Closeness, the centre. Eigenvector, PageRank and Katz, the company a node keeps. Hub and Authority, which way the links run. No single ranking is the answer, and when two rankings disagree, that is often where the case is: here, the plate is the busiest, but the seller P-00476 is the one who joins the two halves of the series. Under any ranking, Start an Investigation from the top results queues them for a recorded Investigation, in the Standard edition and above.
- Chip: Start an Investigation from the top results
- Action: point at *Start an Investigation from the top results* and its Basic hint. [check in UI: the hint's wording on Basic]
- Impact: mark the hint.

### S11 · Try it
- Voice: Your turn. Rank by Degree, then by Betweenness, and write down the top three each time. Which node is in one top three and not the other, and what does that tell you about its role? The answer is in the description. Next, in D4, groups and weak points: communities, cut points and cycles.
- Action: outro card: *Try it* and *Next: D4 · Groups and weak points*.
- Impact: none.
