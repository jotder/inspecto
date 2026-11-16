---
type: Guide
title: "Inspecto LA training videos — D · The analysis toolbox: narration scripts"
description: "The spoken script of the training episodes in group D (The analysis toolbox), scene by scene, with the action on screen and the change it causes. Format and status: the overview, user-guide-training-scripts.md."
resource: tools/render-la-demo-video.mjs
tags: [module, la-core, link-analysis, user-guide, training, video]
timestamp: 2026-11-16T00:00:00Z
---

# Inspecto LA training videos — D · The analysis toolbox

> Narration scripts for group D of the Inspecto LA training series. How to read an episode, the voice and the
> renderer: [the overview](user-guide-training-scripts.md). Draft, not recorded, not reviewed by product; `[check in UI: …]` marks a detail to
> confirm on the live product before recording.

## Episodes in D

| Episode | Title | Edition | Scenes | Spoken words | UI checks open |
|---|---|---|---|---|---|
| [D1](#d1_quick-questions-busiest-tight-groups-route) | Quick questions: busiest, tight groups, route A to B | Basic | 11 | 861 | 8 |
| [D2](#d2_how-are-they-connected) | How are they connected? | ? | 11 | 872 | 8 |
| [D3](#d3_who-matters-most) | Who matters most? | Basic | 11 | 976 | 9 |
| [D4](#d4_groups-and-weak-points) | Groups and weak points | Basic | 11 | 831 | 7 |
| [D5](#d5_what-looks-unusual) | What looks unusual? | Basic | 11 | 810 | 8 |
| [D6](#d6_pattern-match) | Pattern match | Basic | 11 | 841 | 8 |
| [D7](#d7_money-and-time) | Money and time: Value Measures, bursts and rhythms | ? | 11 | 832 | 7 |
| [D8](#d8_on-screen-vs-whole-dataset) | On screen vs Whole Dataset: limits and scale | Basic → Standard | 11 | 889 | 7 |

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
- Voice: All of that ran on the graph you can see. A saved view is a slice, and a route may leave it. Switch to the Whole Dataset tab, the server's side of the toolbox, which the Standard edition adds. Open Find paths. It searches the whole Dataset, not just the graph on screen, and adds any new node it reaches. Pick From: CO-00941. Leave To as any node, set Longest path to four hops, choose Outgoing, and press Find paths.
- Chip: Whole Dataset · Find paths
- Action: the toolbox's Whole Dataset tab ▸ How are they connected? ▸ Find paths ▸ From `CO-00941` ▸ To (optional): *Any node* ▸ Longest path (hops) `4` ▸ Outgoing ▸ Find paths.
- Impact: mark the Whole Dataset tab, then the button.

### S09 · Reading the server's answer
- Voice: The answer is one line: how many paths, the longest, and how deep it searched. Under it, a second line says where it was answered: from the link index, or from the Dataset itself, and why. If the server ran out of room it says the search stopped early, never a quiet nothing. Each path is a row; click one, and it is drawn on the canvas. This is how a route that leaves the saved view still gets found.
- Chip: Find paths · answer line
- Action: hold on the answer line and the line under it ▸ click the first path row. [check in UI: the path count, the depth line, and whether the source line says link index or Dataset]
- Impact: mark the answer line; new nodes from the click are marked yellow.

### S10 · Which tool for which question
- Voice: So: Shortest path, by fewest hops, for how close; by strongest ties, for the route the money prefers. All paths for how many separate ways they are joined. Find maximum flow for how much could pass and where it would be cut, and Strongest backbone for the skeleton of a crowded graph. Find paths, on the Whole Dataset tab, when the answer may lie outside what you drew. Same two names, five different questions.
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
- Voice: Now rank. Under Centrality, set Rank by to Degree, and press Rank nodes. Degree is the simplest question: who has the most links? The table lists the top twenty, highest first. The plate ZX 9117 X is near the top: it was seen near eleven of the scenes. Click its row, and the graph selects it and centres on it. Degree is quick and honest, but it counts every link the same, so a busy node always wins. This saved view leaves out a taxi seen near fifty-four other scenes. On a graph that held it, Degree would rank the taxi first, and it has nothing to do with this series.
- Chip: Centrality · Rank by · Degree
- Action: Centrality ▸ Rank by: Degree ▸ Rank nodes ▸ click the ZX 9117 X row. [check in UI: ZX 9117 X is in Degree's top three]
- Impact: the ranking table appears; mark the top three rows; the canvas centres on the plate (video ring).

### S06 · Betweenness: the go-between
- Voice: Betweenness asks a different question: who sits on the paths between the others? It finds the go-between, the node that, if you took it away, would leave parts of the network unable to reach each other. Rank by Betweenness, and watch the order change. The plate falls back: many of its links lead to a scene and stop there. What rises is the household that ties the two halves of the series together, the seller and the partner who keeps the car, at one address. That is the join the case turns on: the burglaries the stolen goods point to, and the scenes the car was seen near.
- Chip: Centrality · Betweenness
- Action: Rank by: Betweenness ▸ Rank nodes ▸ click the top row. [check in UI: ADR-1377, P-05167 or P-00476 lead the table and ZX 9117 X ranks lower than under Degree; reword to what the table shows]
- Impact: before/after hold of the table: the order changes; mark the rows that moved up.

### S07 · Closeness: the centre
- Voice: Closeness asks who can reach everyone else in the fewest steps. A high score is a node in the middle of its group, close to everything, which is not always the busiest one. Rank by Closeness, and click the top row. Use closeness when you want to know who would hear about something first, or where a message, or money, would pass soonest.
- Chip: Centrality · Closeness
- Action: Rank by: Closeness ▸ Rank nodes ▸ click the top row. [check in UI: how closeness scores the small one-off pieces of this graph; if one of them tops the table, the voice says that a tiny separate piece can score high because everything in it is close]
- Impact: mark the top row and the node.

### S08 · Eigenvector, PageRank and Katz: the company a node keeps
- Voice: The next three ask: who is linked to the important ones? Eigenvector scores a node high not for how many links it has, but for having links to nodes that themselves score high. PageRank is a close cousin that follows the direction of the links: a node scores high when important nodes point to it. Katz is the same idea with a little credit for every node, so a node on a small, separate piece of the graph is not scored as zero. Rank by each in turn. All three lift the heart of the series and push the edges down; Katz keeps the one-off sellers in the table, where eigenvector leaves them at nothing.
- Chip: Centrality · Eigenvector · PageRank · Katz
- Action: Rank by: Eigenvector ▸ Rank nodes ▸ PageRank ▸ Rank nodes ▸ Katz ▸ Rank nodes. [check in UI: eigenvector scores the one-off pieces at or near 0 and Katz above it]
- Impact: three table states, each held 2 s; mark the bottom rows under Eigenvector and under Katz.

### S09 · Hub and Authority: reading direction
- Voice: The last pair reads the direction of the links. Authority score asks who is pointed to by good pointers. Here, burglaries point to the seller of their stolen item, so the seller P-00476, pointed to by nine of them, scores as an authority. Hub score asks the reverse: who points to good authorities? The plate points to eleven scenes, and it scores as a hub. Two halves of one idea, and on this graph they find the seller and the car the case is built on.
- Chip: Centrality · Hub score (HITS) · Authority score (HITS)
- Action: Rank by: Authority score (HITS) ▸ Rank nodes ▸ Rank by: Hub score (HITS) ▸ Rank nodes. [check in UI: P-00476 tops Authority and ZX 9117 X tops Hub; reword to what the tables show]
- Impact: mark the top row of each table.

### S10 · So who matters most?
- Voice: So who matters most? It depends on the question. Degree finds the busiest node. Betweenness, the go-between. Closeness, the centre. Eigenvector, PageRank and Katz, the company a node keeps. Hub and Authority, which way the links run. No single ranking is the answer, and when two rankings disagree, that is often where the case is: here, the plate is the busiest, but the household is what joins the two halves of the series. Under any ranking, Start an Investigation from the top results queues them for a recorded Investigation, in the Standard edition and above.
- Chip: Start an Investigation from the top results
- Action: point at *Start an Investigation from the top results* and its Basic hint. [check in UI: the hint's wording on Basic]
- Impact: mark the hint.

### S11 · Try it
- Voice: Your turn. Rank by Degree, then by Betweenness, and write down the top three each time. Which node is in one top three and not the other, and what does that tell you about its role? The answer is in the description. Next, in D4, groups and weak points: communities, cut points and cycles.
- Action: outro card: *Try it* and *Next: D4 · Groups and weak points*.
- Impact: none.

## D4_Groups-and-weak-points

> **D4 · Groups and weak points** · Basic · Enterprise Space · the access creep · 7 min · user guide §8 (Communities,
> Connected components, Cut points, Cohesive groups, Cycles)

- **Objectives:** split a graph into groups two ways and into separate pieces; find the single entity or link that holds
  a part together; tell a dense group from a chain; find loops, and read "none found" as an answer.
- **Before:** a fresh unzip of `la-basic.zip`, the Enterprise Space, File ▸ Open… ▸ the saved view `access_creep`, the
  toolbox on its **On screen** tab. The saved view `mutual_approvals` is opened in S09.
- **Covers (plan §5):** 5.3 Communities (Label spread (fast), Louvain (finer), Detect communities, the community list),
  Connected components (Find connected components), Cut points (Find cut points, cut nodes, cut links), Cohesive groups
  (Method: Dense core (k-core), Triangles, Cliques; Find groups), Cycles (Find cycles, shortest first).
- **Try it:** on the access creep view, run Cut points and find the cut link that joins an employee to a vendor. Who is
  it, and what is on the far side? *Answer: EMP-0482 to VEN-0264: remove that link and the vendor is a piece of its own.
  EMP-0482 is one of the four reports given payroll and treasury, and the one who later changed VEN-0264's bank details.*
- **Check:** what is the difference between a community and a connected component? *Answer: a component is everything
  you can reach at all; a community is a group that links mostly among itself, so one component can hold several
  communities.*
- **Gotchas:** Communities colours the canvas and lists the groups; Connected components only lists them (a click on a
  row lights its members); Cut points lights the cut nodes and links; Dense core and Triangles fill a table, Cliques lights
  its members; Cycles lights the shortest loop. The access creep view is a hierarchy, close to a tree, so cohesive groups
  and cycles find nothing there by design; S09 opens `mutual_approvals` in the same Space for a real loop. On Basic the
  Space's masked Party type is shown in clear (Basic masks nothing unless a Space sets a masking mode); confirm before
  recording.

### S01 · Shape, not size
- Voice: This is Inspecto LA training, episode D4: groups and weak points. Rankings tell you who stands out. This episode asks about shape instead. Which entities form groups? Which parts are not joined at all? Where is the one link that, if it broke, would split a network in two? Are there tight rings, or loops that come back to where they started? We are in the Basic edition, in the Enterprise Space.
- Action: intro card.
- Impact: none.

### S02 · Access granted, and by whom
- Voice: Open the saved view access creep, from the File menu, Open. It shows every employee given access to a finance system long after they joined, linked to the person who approved it, and any change those employees later made to a vendor's bank details. A small graph. One manager, EMP-0007, has the most links: four. Four is not many. It depends who the four are.
- Chip: File · Open… · Saved views
- Action: File ▸ Open… ▸ Saved views ▸ `access_creep` ▸ Open. [check in UI: EMP-0007 has four links and the graph's size]
- Impact: mark EMP-0007 and its four links.

### S03 · Communities: who links mostly to each other
- Voice: Under Which groups and weak points exist, open Communities. It finds groups that link mostly among themselves. There are two ways. Label spread is fast: each node takes the group most of its neighbours have. Louvain is slower, and finds finer, tighter groups. Press Label spread, then Detect communities. The canvas colours each group, and the list says how many nodes each holds, largest first. Click the row with EMP-0007: one manager, four reports, and a vendor.
- Chip: Communities · Label spread (fast) · Louvain (finer)
- Action: Which groups and weak points exist? ▸ Communities ▸ Label spread (fast) ▸ Detect communities ▸ click the row holding EMP-0007 ▸ Louvain (finer) ▸ Detect communities. [check in UI: whether the two methods split this graph the same way; if Louvain splits finer, the voice adds one sentence saying where]
- Impact: before/after hold: the canvas colours by community; mark EMP-0007's group.

### S04 · Connected components: what is not joined at all
- Voice: A community is a group that sticks together. A component is simpler: everything you can reach from a node, by any route. Open Connected components and press Find connected components. Each row is a separate piece, with how many nodes it holds. Here, nearly every approver and the people they granted are a piece of their own. Click the row with EMP-1802: a payroll manager, and one employee who moved into payroll, granted the day after, with a ticket. Ordinary.
- Chip: Connected components · Find connected components
- Action: Connected components ▸ Find connected components ▸ click EMP-0007's component ▸ click EMP-1802's component. [check in UI: the component rows and that EMP-1802 and EMP-0062 form one]
- Impact: the list appears; each clicked component's members light up.

### S05 · Cut points: the single point of failure
- Voice: Now the weak points. Open Cut points. It finds nodes and links whose removal splits the graph. Press Find cut points. The cut nodes are listed by name, and the cut links light up. EMP-0007 is a cut point: take the manager away and the four reports fall apart, because nothing else joins them. On a graph shaped like a tree, almost every link is a cut link. On a dense graph, a cut point is rare, and that is when it matters most.
- Chip: Cut points · Find cut points
- Action: Cut points ▸ Find cut points ▸ hold on the *Cut nodes* line and the cut-link count. [check in UI: the cut nodes listed and the count of cut links]
- Impact: mark the cut nodes on the canvas and the line *Cut nodes:*.

### S06 · The link the case turns on
- Voice: One cut link is different from the rest. Click EMP-0482 in the cut nodes. Its link to the vendor VEN-0264 is a cut link: it is the only thing that joins that vendor to anyone in this picture. EMP-0482 is an operations analyst, one of the four the manager gave payroll and treasury, with no ticket, and the one who later changed that vendor's bank account. A weak point in a graph is often where a case breaks open.
- Chip: Cut points · cut link
- Action: click `EMP-0482` in the *Cut nodes* line ▸ zoom on the EMP-0482 to VEN-0264 link.
- Impact: mark the EMP-0482 to VEN-0264 link.

### S07 · Cohesive groups: dense, or just connected?
- Voice: Cohesive groups asks how tight a group is. Open it, and pick a Method. Dense core scores each node by how deeply it sits in a group where everyone has several links inside. Triangles counts the closed threes a node is part of. Cliques finds groups where everyone links to everyone. Press Find groups for each. Here every core is one deep, no node closes a triangle, and the answer says: no cliques of size three or more. That is a finding: access here was handed down a line, not passed round a ring.
- Chip: Cohesive groups · Dense core (k-core) · Triangles · Cliques
- Action: Cohesive groups ▸ Method: Dense core (k-core) ▸ Find groups ▸ Triangles ▸ Find groups ▸ Cliques ▸ Find groups. [check in UI: the k-core table scores 1 throughout, triangles 0, and Cliques reports *No cliques of size 3 or more.*]
- Impact: two table states and the Cliques message, each held 2 s; mark the message.

### S08 · Cycles: does anything come back?
- Voice: One more tool belongs here, though it sits under What looks unusual: it asks whether anything returns to where it started. Open Cycles: it finds loops, shortest first. Press Find cycles. No cycles in this graph. Approvals here run one way, from a manager down. A loop would mean someone approving their own approver, and that is a different story in the same Space.
- Chip: Cycles · Find cycles
- Action: What looks unusual? ▸ Cycles ▸ Find cycles. [check in UI: the note reads *No cycles in this graph.*]
- Impact: mark the note.

### S09 · A loop of two
- Voice: Open the saved view mutual approvals, and press Find cycles again. One loop lights up: EMP-0022 to EMP-0040, and back. Two account managers in different sales teams approve each other's expenses, forty times between them, every one just under the five hundred limit that needs a second approver, mostly at weekends. A loop of two is the shortest cycle there is, so it comes first. The team lead who approves a busy team, and is never approved back, makes no loop at all.
- Chip: Cycles · shortest first
- Action: File ▸ Open… ▸ `mutual_approvals` ▸ Cycles ▸ Find cycles ▸ hold on the first row. [check in UI: the first row reads EMP-0022 → EMP-0040 → EMP-0022 (2 hops); reword if the list starts at EMP-0040]
- Impact: before/after hold: the loop glows; mark both links.

### S10 · Which tool for which shape
- Voice: So: Communities for the groups that link mostly among themselves, and Connected components for the pieces that do not touch at all. Cut points for the one node or link that holds a part together. Cohesive groups for how dense a group really is: a core, a triangle, a clique. And Cycles for anything that comes back to its start. When a tool finds nothing, it says so in words, and that answer is evidence too.
- Chip: Which groups and weak points exist?
- Action: point at Communities, Connected components, Cut points and Cohesive groups under *Which groups and weak points exist?*, then Cycles under *What looks unusual?*.
- Impact: mark each row as the voice names it.

### S11 · Try it
- Voice: Your turn. Go back to the access creep view, run Cut points, and find the cut link that joins an employee to a vendor. Who is the employee, and what is on the far side of that link? The answer is in the description. Next, in D5, what looks unusual: look-alikes, possible duplicates and the suspicion score.
- Action: outro card: *Try it* and *Next: D5 · What looks unusual?*.
- Impact: none.

## D5_What-looks-unusual

> **D5 · What looks unusual?** · Basic · Insurance Space · the staged-accident ring · 7 min · user guide §8 (Similarity
> and prediction, Possible duplicates, Suspicion score, Propagated risk)

- **Objectives:** find the entities that look most like a known one; list the links likely missing; read Possible
  duplicates by their reasons before merging anything; rank by suspicion and treat the score as a lead.
- **Before:** a fresh unzip of `la-basic.zip`, the Insurance Space, File ▸ Open… ▸ the saved view `claims_network`, the
  toolbox on its **On screen** tab.
- **Covers (plan §5):** 5.3 Similarity and prediction (Node, Find similar nodes, the Similarity table, Predict links,
  Possible duplicates with its reasons and its caution line, Merge… named as Investigation-only), Suspicion score (Find
  suspicious nodes, the table, the top tenth highlighted, Risk model (optional)), Propagated risk (named, with where it
  runs).
- **Try it:** run Find similar nodes for one member of the family of four that shares a phone and an address. Who comes
  first, and does that make them a ring? *Answer: the other three family members, because they share the phone and the
  address; they are a household, not a ring: two claims in eighteen months, their own garage, no shared clinic or lawyer.*
- **Check:** why does Possible duplicates stop at twenty pairs? *Answer: when it was measured, the top twenty were all
  true duplicates; below them about one in four was two people sharing an address or a device, so the list stays short
  and each row says its reason.*
- **Gotchas:** Find similar nodes and Suspicion score fill a table; a row click selects and centres the node; Suspicion
  also highlights its top tenth on the canvas. **Propagated risk is not reachable on Basic** (it is a Whole Dataset row
  of Standard, it needs an open Investigation, and its *Use risk scores* needs an Industry pack that reads risk data,
  which only the Telecom pack ships): S10 names it and D8 opens its row. *Merge…* appears only inside an Investigation.
  The Insurance Space masks its Party type on Standard; on Basic the ids are readable (confirm before recording).

### S01 · Look-alikes and outliers
- Voice: This is Inspecto LA training, episode D5: what looks unusual? Fraud rarely looks unusual on its own. Each claim here is small and plausible. What gives it away is how the parties resemble each other, which links are missing, and which entities sit oddly in the network. In this episode you will find look-alikes, likely missing links, possible duplicates, and a score that ranks the oddest nodes, with its reasons. We are in the Basic edition, in the Insurance Space.
- Action: intro card.
- Impact: none.

### S02 · The claims network
- Voice: Open the saved view claims network, from the File menu, Open. Claimants are linked by the claims and accidents they share, by phone numbers and addresses, and to the garage, the clinic and the lawyer on each claim. Somewhere in here, twelve claimants on nine claims share four phones and three addresses, one garage, one clinic and one lawyer. A family of four that shares a phone, and a busy family doctor, are here too, and both are ordinary.
- Chip: File · Open… · Saved views
- Action: File ▸ Open… ▸ Saved views ▸ `claims_network` ▸ Open ▸ open the Legend pill. [check in UI: the graph is not cut short; if it is, the voice adds that the tools work on what is drawn]
- Impact: mark CL-0134, GAR-03, CLI-04 and LAW-02.

### S03 · Find similar nodes
- Voice: Under What looks unusual, open Similarity and prediction. Pick a Node: CL-0134, a passenger on six claims with six different drivers. Press Find similar nodes. The question is: who shares the most neighbours with this one? The table ranks nodes by the share of neighbours they have in common, from zero to one. At the top are claimants who share CL-0134's phones, addresses, garage, clinic and lawyer. Click a row, and the canvas selects and centres it.
- Chip: Similarity and prediction · Find similar nodes
- Action: What looks unusual? ▸ Similarity and prediction ▸ Node `CL-0134` ▸ Find similar nodes ▸ click the top row. [check in UI: the top rows are ring members; name the top one in the voice if it is stable]
- Impact: the table appears; mark the top three rows; the canvas centres on the clicked node.

### S04 · Predict links
- Voice: Similar entities often know each other, even when no record says so. Press Predict links. It lists pairs that share many neighbours but have no link between them, each with a score. Click a pair, and both are shown on the canvas. Read it as: these two are probably connected in a way the data has not recorded yet. In a ring, a predicted link between two claimants who never appear on the same claim is a question for the investigator.
- Chip: Predict links
- Action: Predict links ▸ click the first two rows in turn. [check in UI: the predicted pairs are ring members; reword if not]
- Impact: mark each clicked pair.

### S05 · Possible duplicates
- Voice: Now a sharper question: which two entities are probably the same one? Press Possible duplicates. It lists up to twenty pairs of the same Entity Type, not already linked, and a rare shared neighbour counts for more than a busy one. Each row says why: the kinds of neighbour the pair shares. Look for a claimant who is a ring member under a name one letter different, with the same phone. That is the pattern a duplicate identity leaves.
- Chip: Similarity and prediction · Possible duplicates
- Action: Possible duplicates ▸ hold on the caution line and the rows ▸ click the row that pairs the near-duplicate with its ring member. [check in UI: whether that pair is listed, its row's *shares …* reason, and the pair's ids for the voice]
- Impact: mark the caution line and the clicked pair on the canvas.

### S06 · Read the reason before you merge
- Voice: Read the line above the list: these are suggestions only, because two people at one address also share neighbours. The family of four shares a phone and an address, and they are four real people. That is why every row gives its reason. Inside an Investigation, in the Standard edition, each row also has Merge, which fills the identity form for you, and nothing is written until you confirm. Episode E2 shows that form.
- Chip: Possible duplicates · Merge… (Investigation)
- Action: click a family row if one is listed; point at where *Merge…* would be. [check in UI: whether a family pair appears in the twenty]
- Impact: mark the family pair, if listed.

### S07 · Suspicion score
- Voice: Open Suspicion score. It answers: whose links look most unusual? It combines how connected, how central and how clustered a node is into one score from nought to a hundred. Press Find suspicious nodes. The table lists the top twenty, highest first, and the canvas highlights the top tenth of the graph, so the oddest nodes stand out where they sit. Click a row to select and centre it.
- Chip: Suspicion score · Find suspicious nodes
- Action: What looks unusual? ▸ Suspicion score ▸ Find suspicious nodes ▸ click the top row. [check in UI: the top rows; expected among them CLI-04, LAW-02, GAR-03 or CL-0134]
- Impact: the table appears; the top tenth glows on the canvas; mark the top three rows.

### S08 · A score is a lead
- Voice: A score is a lead, never a verdict. Find the busy family doctor in the table. It is well connected, because many people visit a doctor, and it may score high for that reason alone. Compare it with the ring's clinic, which is referred to by one lawyer only. The score tells you where to look. The links, read one by one, tell you what you are looking at.
- Chip: Suspicion score · reading the table
- Action: point at the CLI-01 row and the CLI-04 row. [check in UI: where CLI-01 and CLI-04 rank; if CLI-01 is not in the twenty, the voice says the busy doctor does not make the list]
- Impact: mark both rows.

### S09 · Risk model, optional
- Voice: Under the button is Risk model, optional. Type the name of a risk model saved in your Space, and the table gains a Risk Score column, with the factors that make up each node's score one click away. Use it when your organisation already scores entities and you want both views side by side. This Space ships no model, so leave it blank.
- Chip: Risk model (optional)
- Action: point at the *Risk model (optional)* field and its placeholder; leave it blank.
- Impact: mark the field.

### S10 · Propagated risk
- Voice: One more tool answers a related question: who is close to the risky ones? Propagated risk spreads each scored node's risk to the nodes linked to it, a little less at every hop, and ranks the result with each score's contributors. It runs on the server, on the Whole Dataset tab of the Standard edition, over an open Investigation, and it needs risk scores the Space's Industry pack can read. Episode D8 opens it.
- Chip: Propagated risk (Standard)
- Action: none on Basic: an inset card naming *Whole Dataset ▸ What looks unusual? ▸ Propagated risk* in Standard.
- Impact: none.

### S11 · Try it
- Voice: Look-alikes, missing links, duplicates by their reasons, and a score that is a lead. Your turn. Run Find similar nodes for one member of the family of four that shares a phone and an address. Who comes first, and does that make them a ring? The answer is in the description. Next, in D6, pattern match: describe a shape, and find every place it happens.
- Action: outro card: *Try it* and *Next: D6 · Pattern match*. [check in UI: one family member's id for the outro card]
- Impact: none.

## D6_Pattern-match

> **D6 · Pattern match** · Basic · Other Examples Space · the structuring ring · 7 min · user guide §9

- **Objectives:** load a pattern pack and read what it looks for; build a chain of steps by direction, link kind and
  Entity Type; read and edit a branching pattern's stages; turn a selection into a pattern and save it for the Space.
- **Before:** a fresh unzip of `la-basic.zip`, the Other Examples Space, File ▸ Open… ▸ the saved view *Structuring into
  one account* (`mule_structuring`), **Filter links by time** ▸ Time column `BOOKED_AT` (as the view's description asks),
  the toolbox on its **On screen** tab. S07 opens the saved view *Large transfers only* (`mule_large_transfers`).
- **Covers (plan §5):** 5.3 Pattern match: the Pattern picker (the nine built-in packs, the Space's own packs, Custom
  pattern), the description line and its *Better served by* pointer, steps (Start node, → ← ↔, Link kind, Entity Type,
  Add step, remove a step, the *After the previous hop* chip), branching stages (Many send to one / One sends to many, At
  least … different counterparties, Value column, Value from, Value below, the removable chips), Steps / Conditions,
  Find matches, Use selection as pattern, Save as pattern pack; Search whole Dataset named, shown in D8.
- **Try it:** load Circular flow and read its description. Which tool does it point you to, and what does that tool find
  on this view? *Answer: Cycles; it finds the four shell companies' loop, SHELL-A → SHELL-B → SHELL-C → SHELL-D → SHELL-A,
  the only loop on this graph.*
- **Check:** why does a pattern run on the *Large transfers only* view say no link passes the band, instead of "no
  match"? *Answer: the view keeps only transfers of 5,000 or more, so the deposits under 1,000 the pattern needs were
  filtered away; the pattern names that, so an empty answer is never mistaken for an innocent one.*
- **Gotchas:** this Space speaks the Telecom pack, so the picker opens on the Space's own pack *Call-forwarding chain* and
  the built-in relay pack reads *Call-forwarding relay*. Matches light up on the canvas and list as rows. *Save as pattern
  pack* needs the Workbench author permission. *Search whole Dataset* appears only on the Whole Dataset tab of Standard,
  for a branching pattern on a graph that was cut short: D8 shows it.

### S01 · Describe a shape, find every copy
- Voice: This is Inspecto LA training, episode D6: pattern match. Some schemes have a shape you can describe before you find them. Money in from many small payers, then straight out to a few. A chain of accounts, each paying the next within hours. Pattern match lets you write that shape down once, and finds every place in the graph where it happens. In this episode you will load ready-made patterns, build your own, read a branching one, and save a pattern for your team. We are in the Basic edition, in the Other Examples Space.
- Action: intro card.
- Impact: none.

### S02 · The pattern packs
- Voice: Under What looks unusual, open Pattern match. Its first line says what it does: find chains of links, a start, then one hop per step. The Pattern picker holds the nine built-in packs: layering chain, pass-through intermediary, inbound collector, call-forwarding relay, circular flow, shared associates, structuring, layering network, and circular financing. Packs your Space has saved sit beside them. Each one, when picked, says in a line what it looks for, and some say a better tool exists.
- Chip: Pattern match · Pattern
- Action: What looks unusual? ▸ Pattern match ▸ open the Pattern picker ▸ scroll the list slowly ▸ close it. [check in UI: the picker's order and labels; the Space's packs *Call-forwarding chain*, *Shared top-up evidence* and *Subscription ring (shared identity)* sit with the nine]
- Impact: mark the nine built-in names as the voice reads them.

### S03 · A chain: pass-through intermediary
- Voice: Pick Pass-through intermediary. Its description: one account receives money and forwards it within forty-eight hours, check the middle account. Below it, the steps. A start node, then a step outgoing, then another step outgoing, carrying a chip: after the previous hop, within forty-eight hours. That chip is the time order, and it is shown so you can remove it. Press Find matches. The matches are listed, and light up on the canvas: depositors into the hub and out to a relay, the second hub through its accounts to the till.
- Chip: Pattern match · Pass-through intermediary · Find matches
- Action: Pattern ▸ *Pass-through intermediary* ▸ zoom on the steps and the *After the previous hop, within 48 hours* chip ▸ Find matches ▸ click two match rows. [check in UI: the matches listed, and whether a *Showing the first … matches* note appears]
- Impact: the matched links glow; mark the chip, then each clicked match.

### S04 · Build your own steps
- Voice: Now build one. Pick Custom pattern: a step with no link kind matches any link. Each step has a direction, outgoing, incoming or both ways, a link kind, and an Entity Type. Set the first step outgoing, by cash deposit. Press Add step, and set it outgoing, by wire. In words: someone pays cash into an account that then wires money on. Press Find matches. Only the depositors into MULE-HUB-01 and its two relays come back. The ordinary accounts never take cash and pay on.
- Chip: Custom pattern · Add step · Link kind
- Action: Pattern ▸ *Custom pattern* ▸ step 2: → Outgoing, Link kind `cash_deposit` ▸ Add step ▸ step 3: → Outgoing, Link kind `wire` ▸ Find matches. [check in UI: the link kind names offered and that every match runs through MULE-HUB-01]
- Impact: before/after hold: the ordinary traffic stays dark; the hub's chains glow.

### S05 · The same steps, as conditions
- Voice: Above a simple pattern like this one, a toggle offers Steps or Conditions. Press Conditions. The same search reads as plain conditions: a start Entity Type, then for each condition a direction, a link kind and a neighbour type, with the words spelled out. It runs the same search. The toggle is not offered for a pattern with a time order or with branches, because those only the step list can show.
- Chip: Steps · Conditions
- Action: the *Steps / Conditions* toggle ▸ Conditions ▸ hold on the sentence *This search in words* ▸ back to Steps. [check in UI: the toggle shows for the two-step custom pattern]
- Impact: mark the toggle and the sentence.

### S06 · A branching pattern: structuring
- Voice: Some shapes branch. Pick Structuring. Its stages read like the scheme: first, many send to one, at least five different counterparties, each amount from nine hundred to just below a thousand, within a day. Then one sends to many, after the previous stage. Then many send to one again. Every threshold is a field you can change, set to your own reporting limit. Press Find matches. Exactly one match: twelve accounts paying the hub, out through two relays, into one offshore account. Nothing in the ordinary traffic.
- Chip: Structuring (smurfing) · stages
- Action: Pattern ▸ *Structuring (smurfing)* ▸ zoom on stage 1 (At least `5`, Value column `AMOUNT`, Value from `900`, Value below `1000`) and the *After the previous stage* chips ▸ Find matches.
- Impact: before/after hold: one match glows; mark the twelve depositors, MULE-HUB-01, the relays and OFFSHORE-77.

### S07 · The amount-filter trap
- Voice: Now open the saved view Large transfers only. It keeps transfers of five thousand or more, the size a reviewer usually looks at. Run Structuring again. The answer is not no match. It says no link in this graph passes the band, and that a filter on the amount may have removed the links the pattern needs. A threshold alone hides exactly what structuring is built to hide. That is why patterns say what they could not check.
- Chip: Large transfers only · the refusal
- Action: File ▸ Open… ▸ *Large transfers only* ▸ Pattern match ▸ *Structuring (smurfing)* ▸ Find matches ▸ hold on the message. [check in UI: the message starts *No link in this graph passes …*; reword to its exact words]
- Impact: mark the message.

### S08 · Use selection as pattern
- Voice: Back on Structuring into one account. Sometimes you find one example first, and want every other one like it. Select three entities on the canvas: MULE-HUB-02, one account it pays, and TILL-06. Press Use selection as pattern. The chain loads as steps, with each step's direction, link kind and Entity Type taken from your example, and an after the previous hop chip. Only the order is kept, never the exact gaps. Press Find matches: the same route, once through each of the eight accounts.
- Chip: Use selection as pattern
- Action: File ▸ Open… ▸ *Structuring into one account* ▸ Shift+click `MULE-HUB-02`, `CASHER-01`, `TILL-06` ▸ Use selection as pattern ▸ hold on the note *From your selection: …* ▸ Find matches. [check in UI: the derived steps and chips; the match count, expected eight]
- Impact: mark the loaded steps, then the eight matched routes.

### S09 · Save as pattern pack
- Voice: A pattern worth finding twice is worth saving. Press Save as pattern pack, give it a name, cash-out through one till, a category, and a description of what it looks for. Save. The line under the steps says it is saved, and everyone in this Space can now pick it. Saving a pack is a change to the Space, so it needs the Workbench author permission.
- Chip: Save as pattern pack
- Action: Save as pattern pack ▸ Name `Cash-out through one till` ▸ Category ▸ Description `A hub pays several accounts, each of which cashes out at the same till.` ▸ save ▸ hold on the note ▸ reopen the Pattern picker. [check in UI: the button shows on Basic, and the dialog's save button label]
- Impact: mark the note *Saved as the pattern pack …* and the new name in the picker.

### S10 · Only what is on screen
- Voice: Everything here searched the graph on screen. If that graph was cut short, small links a pattern needs may never have been drawn. In the Standard edition, the Whole Dataset tab then offers Search whole Dataset for a branching pattern, which reads every row of the Dataset instead. That is episode D8.
- Chip: Search whole Dataset (Standard)
- Action: none on Basic: an inset card naming *Whole Dataset ▸ Pattern match ▸ Search whole Dataset* in Standard.
- Impact: none.

### S11 · Try it
- Voice: Packs, your own steps, branching stages, and a pattern from an example. Your turn. Load Circular flow and read its description. Which tool does it point you to, and what does that tool find on this view? The answer is in the description. Next, in D7, money and time: Value Measures, bursts and rhythms.
- Action: outro card: *Try it* and *Next: D7 · Money and time*. [check in UI: Circular flow says it is better served by the Cycles tool]
- Impact: none.

## D7_Money-and-time

> **D7 · Money and time: Value Measures, bursts and rhythms** · Standard (exploring, no Investigation open) · Mobile Money
> Space · the structuring hub · 7 min · user guide §8 (Value Measures, Burst and periodicity)

- **Objectives:** run a Value Measure over a window and read its thresholds, window and table; say which of the seven
  Measures answers which money question; scan the Dataset's event times for bursts and regular intervals and show them
  on the canvas.
- **Before:** a fresh unzip of `la-standard.zip`, the Mobile Money Space, signed in as Demo Analyst, no Investigation
  open, File ▸ Open… ▸ the saved view `mm_structuring_band`, **Filter links by time** ▸ Time column `ts`, the toolbox on
  its **Whole Dataset** tab. The landed data runs 2026-10-01 to 2026-10-14.
- **Covers (plan §5):** 5.3 Value Measures (Dataset, Measure: all seven, Fixed window From / To (not included), Or the
  last (rolling window), the per-Measure thresholds, Withdrawal link kinds, Intermediary list (optional), Run Measure and
  its disabled reason, Thresholds in use, Window read, the table, the line about watching a Measure), Burst and
  periodicity (Check each, Find: Bursts / Regular intervals, Burst window (seconds), Largest irregularity (0 to 1), Fewest
  events, Scan Dataset, the findings, Highlight all on canvas, Seal these findings and its reason).
- **Try it:** type `7d` in *Or the last*, clear From and To, and run Structuring. What does the Window read line say, and
  why is HUB-01 missing? *Answer: a rolling window counts back from now, in UTC, not from the data's last day; this demo's
  data ends on 2026-10-14, so the last seven days hold none of it. A fixed From and To reads the landed days.*
- **Check:** a view filtered to large amounts hides structuring; does a Value Measure care? *Answer: no. Value Measures
  read the whole Dataset in their window, not the filtered picture on screen.*
- **Gotchas:** Value Measures and Burst and periodicity read the server, so they are on the Whole Dataset tab, which Basic
  does not show: this episode records on Standard (the plan listed D7 as Basic). The Mobile Money pack relabels two
  Measures, *Time to cash-out* and *Cash-out concentration* (neutral: *Time to withdrawal*, *Withdrawal concentration*).
  Expected answers from `tools/gen-la-mm-data.py`: Structuring flags HUB-01 with 25 legs from 25 payers in the band 4,750
  to below 5,000; HUB-01 forwards 98 % within 10 minutes. *Watch this Measure* is F9.

### S01 · How much, and when
- Voice: This is Inspecto LA training, episode D7: money and time. A graph shows who is linked. It does not show how much moved, how fast, or whether it came in a rush or on a schedule. Two tools answer that, and both read the whole Dataset on the server, so they sit on the Whole Dataset tab of the Standard edition. In this episode you will run the seven Value Measures, and scan the event times for bursts and regular rhythms. We are in the Standard edition, in the Mobile Money Space, signed in as the analyst.
- Action: intro card.
- Impact: none.

### S02 · The Value Measures form
- Voice: On the Whole Dataset tab, open Value Measures: find entities by how money moves through them. It reads the whole Dataset in a window of at most thirty-one days, not the filtered picture on screen. Choose the Dataset, check that the value, time and kind columns are right, and pick a window: a fixed From and To, or a rolling one. Until the form is valid, Run Measure stays off and the line above it says what is missing.
- Chip: Whole Dataset · Value Measures
- Action: the toolbox's Whole Dataset tab ▸ Value Measures ▸ hold on the form ▸ zoom on the reason line above Run Measure. [check in UI: the prefilled Dataset and columns without an Investigation open, and the reason line's words]
- Impact: mark the reason line and the disabled Run Measure.

### S03 · Structuring
- Voice: Pick the Measure: structuring, legs just under a limit. Set From to the first of October and To, not included, to the fifteenth. Set the leg value from four thousand seven hundred and fifty, and below five thousand: the top of the band under the limit. Press Run Measure. The answer first says which thresholds and which window it used. Then the table: HUB-01, twenty-five legs in the band, from twenty-five different payers. Every deposit was legal. Together they are a pattern.
- Chip: Measure · Structuring (legs just under a limit) · Run Measure
- Action: Measure ▸ *Structuring (legs just under a limit)* ▸ From `2026-10-01` ▸ To (not included) `2026-10-15` ▸ Leg value from `4750` ▸ Leg value below `5000` ▸ Run Measure. [check in UI: the table's columns and that HUB-01 is the only row]
- Impact: mark *Thresholds in use*, *Window read*, then the HUB-01 row.

### S04 · Pass-through and velocity
- Voice: Switch the Measure to pass-through: accounts that forward nearly everything they receive. Its thresholds are a minimum inbound value and a minimum out-over-in ratio. Run it. HUB-01 is back: ninety-eight per cent of what came in went out. Now velocity: accounts that forward money within hours of receiving it, with a maximum number of hours. Run it. HUB-01 again, money out within ten minutes. One account, three Measures, the same answer from three sides.
- Chip: Pass-through · Velocity
- Action: Measure ▸ *Pass-through* ▸ Run Measure ▸ Measure ▸ *Velocity* ▸ Run Measure. [check in UI: whether the relays are flagged too; if so, the voice adds that they pass the money on as well]
- Impact: two table states, each held 2 s; mark HUB-01.

### S05 · Time to cash-out
- Voice: Money that is laundered has to leave as cash somewhere. Time to cash-out asks how fast received money reaches a cash-out, and the Withdrawal link kinds say which links count as one: here, cash out. Run it. The relay chain shows: money that entered the hub was taken out at the agent AG-39 soon after.
- Chip: Time to cash-out · Withdrawal link kinds
- Action: Measure ▸ *Time to cash-out* ▸ Withdrawal link kinds `cash_out` ▸ Run Measure. [check in UI: the rows flagged; expected RELAY-3 to AG-39; reword to the table]
- Impact: mark the flagged rows.

### S06 · Cash-out concentration
- Voice: Cash-out concentration turns the question round, to the agent: does one agent pay out a large share of all the cash, to only a few payers? Its thresholds are a minimum share, from nought to one, and a minimum number of payers. Intermediary list, optional, limits it to the agents on an Entity List you keep. Run it, and read which agent stands out, and from how many wallets.
- Chip: Cash-out concentration · Intermediary list (optional)
- Action: Measure ▸ *Cash-out concentration* ▸ point at Minimum share (0–1), Minimum payers and Intermediary list (optional) ▸ Run Measure. [check in UI: which agent is flagged; expected AG-39, with few payers]
- Impact: mark the flagged agent's row.

### S07 · Benefit transfer and value-weighted links
- Voice: Two more. Benefit transfer flags a benefit payment passed on to a single counterparty, with a minimum number of recipients. This Space has no benefit payments, and the table says no entity breaches these thresholds, an honest empty. Value-weighted links is not a test: it gives the total value and count per pair, a weighting to read, and it cannot be watched. To watch the others, an Alert Rule over a Measure needs an open Investigation. That is episode F9.
- Chip: Benefit transfer · Value-weighted links (read-only, cannot be watched)
- Action: Measure ▸ *Benefit transfer* ▸ Run Measure ▸ hold on *No entity breaches these thresholds* ▸ Measure ▸ *Value-weighted links (read-only, cannot be watched)* ▸ Run Measure ▸ hold on the line about watching.
- Impact: mark the empty-table line, then the top pair of the weighting table.

### S08 · Burst and periodicity
- Voice: Now time. Open Burst and periodicity. It scans event times across the whole Dataset, and needs the time column chosen in the toolbar's time filter; without one it says so. Check each: each link, or each entity with all its events together. Find: bursts. Set the burst window to a day, eighty-six thousand four hundred seconds, and the fewest events to ten. Press Scan Dataset. The findings are listed, strongest first, and an agent that cashed in for twenty-five wallets inside a day and a half stands out.
- Chip: Burst and periodicity · Bursts · Scan Dataset
- Action: Whole Dataset ▸ Burst and periodicity ▸ Check each: *Each entity* ▸ Find: *Bursts* ▸ Burst window (seconds) `86400` ▸ Fewest events `10` ▸ Scan Dataset. [check in UI: the findings; expected AG-18 and HUB-01 among them; reword to what the list shows]
- Impact: mark the summary line and the AG-18 finding.

### S09 · On the canvas
- Voice: Press Highlight all on canvas, and every finding that is drawn lights up. A finding whose entities are not on the canvas is greyed and says so, so you know when the answer lies outside your view. Click one finding to light only that one. A burst is a question: twenty-five cash-ins in thirty-six hours at one agent is not how an agent's day looks.
- Chip: Highlight all on canvas
- Action: Highlight all on canvas ▸ click the AG-18 finding.
- Impact: before/after hold: the findings glow; mark AG-18's links.

### S10 · Regular intervals, and sealing
- Voice: Switch Find to regular intervals: events that repeat on a schedule. Its setting is the largest irregularity, from nought to one, where nought is a perfect clock. Scan again. Rhythm can be innocent, a salary or a bill, or a scheme run by a script. Under the findings is Seal these findings. It is off here, and says why: open an Investigation first. Inside one, it records the findings in its log, and the Working Set does not change.
- Chip: Regular intervals · Seal these findings
- Action: Find: *Regular intervals* ▸ Largest irregularity (0 to 1) `0.2` ▸ Fewest events `4` ▸ Scan Dataset ▸ hover Seal these findings. [check in UI: what regular intervals finds here; if *Nothing matched*, the voice says the data holds no regular rhythm at this setting]
- Impact: mark the findings or the *Nothing matched* line, then the seal's reason.

### S11 · Try it
- Voice: Seven Measures for how money moves, and one scan for when. Your turn. In Value Measures, clear From and To, type seven d in the rolling window, and run Structuring. What does the Window read line say, and why is HUB-01 missing? The answer is in the description. Next, in D8, on screen against the whole Dataset: limits, and how Inspecto LA handles scale.
- Action: outro card: *Try it* and *Next: D8 · On screen vs Whole Dataset*.
- Impact: none.

## D8_On-screen-vs-Whole-Dataset

> **D8 · On screen vs Whole Dataset: limits and scale** · Basic → Standard · Telecom Space · the revenue-assurance data
> and the SIM-swap Investigation · 7 min · user guide §5.4, §8, §15

- **Objectives:** say where each toolbox tab runs and over what; read a row's size chip and the Limits card; follow a
  tool past the on-screen limit to the server; read a server answer, send a long one to the dock, and know what the link
  index is for.
- **Before:** S02's inset from a fresh `la-basic.zip`, Telecom Space. Then a fresh unzip of `la-standard.zip`, the
  Telecom Space, signed in as Demo Analyst, no Investigation open, an empty canvas.
- **Covers (plan §5):** 5.3 the tabs On screen / Whole Dataset, the *Up to N nodes* and *Instant up to N nodes* chips,
  *Also on Whole Dataset* / *Also on screen*, *Suggested*; 5.2g Graph counts ▸ Limits (Graph drawn, On screen tools,
  Whole Dataset tools); the over-limit line and the hand-off (Run on server, its reason without an Investigation); Search
  whole Dataset on a cut graph; the server rows (Degree centrality, Highlight on canvas, Open in dock), Propagated risk's
  fields; 5.2h the dock's Results tab; Link index (Full, Append, Compact, Build link index).
- **Try it:** in the SIM-swap Investigation, run the Whole Dataset row *Connected components*. How many parts does the
  Working Set fall into, and is S-41 in the largest? *Answer: settled at recording from the row's answer; expected: the
  login, the wallets it swapped and the mule wallets they paid sit in one part.*
- **Check:** a tool on the On screen tab says the graph is too big. What do you do? *Answer: use the same tool on the
  Whole Dataset tab, which runs on the server over an open Investigation's Working Set; with no Investigation open it
  says so and waits.*
- **Gotchas:** **index builds are refused on packaged installs** until the packager stamps the index Job's owner (plan
  §6.4): S10 is written, and is recorded only after that fix; until then it records the form and the voice stops before
  the build. Server rows run only over an open Investigation's Working Set (*Whole Dataset tools run on an open
  Investigation. Open or start one first.*), so S07 opens the seeded *SIM-swap wallet takeover*. The Telecom Space has no
  Industry pack: no *Suggested* chips, and Propagated risk says *No risk data for this Industry pack.* (only the Telecom
  pack, in the Other Examples Space, reads risk data). S05 changes a My browser limit and S11 puts it back.

### S01 · What fits, and what does not
- Voice: This is Inspecto LA training, episode D8: on screen against the whole Dataset. A screen can draw a thousand nodes. A Dataset can hold millions of records. Every tool in Inspecto LA has to be honest about which of the two it is looking at. In this episode you will see the two tabs of the toolbox, read the limits, follow a tool past the screen's limit to the server, and meet the link index. We start in the Basic edition, then move to Standard, in the Telecom Space.
- Action: intro card.
- Impact: none.

### S02 · One tab, then two
- Voice: In the Basic edition the toolbox has one tab: On screen. Every tool there runs in your browser, over the graph you have drawn, and nothing else. In the Standard edition, two more tabs appear. Whole Dataset runs tools on the server, over the whole Dataset or over an open Investigation's Working Set, whatever its size. The Investigation tab is for recorded work, the F episodes. Same questions, two places to answer them.
- Chip: Toolbox · On screen · Whole Dataset
- Action: inset from `la-basic.zip`: the toolbox shows only On screen ▸ cut to Standard, signed in as Demo Analyst: the three tabs.
- Impact: mark the one tab, then the three.

### S03 · Draw more than fits
- Voice: Draw something large. From the File menu, choose Explore Dataset, pick the revenue-assurance link Dataset, its from and to columns, and run it. The server returns its share and says the result was cut short: never a silent partial answer. Open the Graph counts card, and the Limits icon at its corner. Graph drawn: up to a thousand nodes. On screen tools: up to two thousand five hundred. Whole Dataset tools: far more. Each is a setting, and the card says the one in force.
- Chip: File · Explore Dataset… · Limits
- Action: File ▸ Explore Dataset… ▸ Dataset `telco_ra_links_dataset` ▸ From `src` ▸ To `dst` ▸ run ▸ wait for the draw ▸ Graph counts ▸ Limits. [check in UI: the run button's label in Graph model, the cut-short wording on the canvas, and the three lines' numbers]
- Impact: mark the cut-short line, then the three Limits lines.

### S04 · Reading the chips
- Voice: Every row on the On screen tab says how big a graph it takes. Centrality says up to five hundred nodes, because its go-between score is the heaviest to work out in a browser. Cohesive groups says a thousand. A row that the server can also run carries a chip, Also on Whole Dataset. And in a Space with an Industry pack, a row the pack recommends is chipped Suggested; this Space has none.
- Chip: Up to N nodes · Also on Whole Dataset
- Action: the On screen tab ▸ zoom on the Centrality row's *Up to … nodes* and *Also on Whole Dataset* chips ▸ the Cohesive groups row. [check in UI: the chips' numbers]
- Impact: mark each chip as the voice names it.

### S05 · Past the limit
- Voice: To see what happens at the limit, lower it, for this browser only. In Settings, under Link Analysis, the My browser section holds your own limits, never above the Space's. Set the analysis node limit to five hundred, and save. Back on the canvas, the On screen tab now says this graph has a thousand nodes, too big to answer on screen, use the Whole Dataset tab. That sentence is the hand-off.
- Chip: Settings · My browser · Analysis node cap
- Action: Settings ▸ Link Analysis ▸ My browser ▸ the analysis node limit `500` ▸ save ▸ back to Link Analysis ▸ hold on the On screen tab's line. [check in UI: the item's label (`analysis_node_cap`, *Analysis node cap* in the config spec) and the exact sentence]
- Impact: mark the line *… too big to answer on screen …*.

### S06 · The hand-off, and a cut graph
- Voice: Switch to the Whole Dataset tab. The tool that cannot run on screen is listed here with the same settings, and a Run on server button. Press it, and it waits with its reason: Whole Dataset tools run on an open Investigation. One more server tool needs no Investigation. On a graph that was cut short, a branching pattern offers Search whole Dataset, because the small links it needs may never have been drawn. Load layering network, and press it. The answer counts the matches in the whole Dataset, or says there are none.
- Chip: Run on server · Search whole Dataset
- Action: Whole Dataset tab ▸ the handed-over row ▸ Run on server ▸ hold on the reason ▸ Pattern match ▸ *Layering network (split and merge)* ▸ hold on *This graph was cut short …* ▸ Search whole Dataset. [check in UI: which rows hand over at a limit of 500; the pattern's answer line, *… in the whole Dataset* or *No matches in the whole Dataset*]
- Impact: mark the reason, then the answer line.

### S07 · Open an Investigation
- Voice: From the File menu, Open, choose the Investigation SIM-swap wallet takeover. Now the Whole Dataset tab has something to run on: the Working Set. Under each question, the server's rows are listed with a size of their own: instant up to a hundred thousand nodes, for example. Above that, a run is not refused. It is queued as a job, and the row shows it running. A row with a twin on screen is chipped Also on screen.
- Chip: File · Open… · Investigations
- Action: File ▸ Open… ▸ Investigations ▸ *SIM-swap wallet takeover* ▸ Open ▸ the Whole Dataset tab ▸ zoom on a row's *Instant up to … nodes* and *Also on screen* chips.
- Impact: mark the chips.

### S08 · A server answer, and the dock
- Voice: Open Degree centrality: rank nodes by how many links they have. Press Run on server. The answer comes back summarised in words, with the ranked lines under it. Highlight on canvas shows them where they sit. A long answer gets Open in dock: press it, and the dock's Results tab holds the whole table, with the headline above it, ready to sort and export. That is where long answers go, so the toolbox stays readable.
- Chip: Degree centrality · Run on server · Open in dock
- Action: Who matters most? ▸ *Degree centrality* ▸ Run on server ▸ Highlight on canvas ▸ Open in dock ▸ the dock's Results tab. [check in UI: the headline, that S-41 ranks near the top, and that the answer is long enough to offer Open in dock]
- Impact: before/after hold: the dock opens on Results; mark the headline and the table.

### S09 · Propagated risk
- Voice: Under What looks unusual, open Propagated risk: who is close to the risky ones? It spreads risk from scored nodes to the nodes linked to them, a share at each hop: Weight per hop. Risk origins limits which nodes pass risk on. Risk scores are read from the Industry pack's own data with Use risk scores. Here it is off, and says why: no risk data for this Industry pack. A tool that cannot run says so, once, in words.
- Chip: Propagated risk · Weight per hop · Risk origins
- Action: What looks unusual? ▸ *Propagated risk* ▸ point at Risk scores (*Use risk scores*, disabled), Risk origins, Weight per hop, Direction ▸ hold on *No risk data for this Industry pack.*.
- Impact: mark the reason.

### S10 · The link index
- Voice: Last, the link index. Searching a whole Dataset row by row gets slow as it grows. The link index is a ready-made map of who links to whom, built ahead of time, so search and filter stay fast on millions of records. Open Link index. It says whether serving is on for this Space. Build mode: Full rebuilds from the whole Dataset, Append adds the files that arrived since, Compact merges them. Every server read then says where it was answered: from the link index, or from the Dataset, and why.
- Chip: Link index · Full · Append · Compact
- Action: How are they connected? ▸ *Link index* ▸ hold on the serving line ▸ Build mode: Full ▸ Append ▸ Compact ▸ Build link index ▸ hold on the build's state. [check in UI: blocked on packaged installs (plan §6.4): record this scene after the packager fix; until then stop before Build link index]
- Impact: mark the serving line, then each mode as the voice names it.

### S11 · Try it
- Voice: The screen for what you can see, the server for everything else, and every limit said out loud. Before you go, set the My browser limit back with Reset to Space value. Your turn. In the SIM-swap Investigation, run the Whole Dataset row Connected components. How many parts does the Working Set fall into, and is the login S-41 in the largest? The answer is in the description. Next, in E1, keeping your work: saving, opening and exporting.
- Action: Settings ▸ Link Analysis ▸ My browser ▸ Reset to Space value ▸ outro card: *Try it* and *Next: E1 · Saving, opening and exporting*. [check in UI: the *Reset to Space value* label]
- Impact: none.
