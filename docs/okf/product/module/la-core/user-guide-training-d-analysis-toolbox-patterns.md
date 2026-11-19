---
type: Guide
title: Inspecto LA training videos — D · The analysis toolbox: narration scripts — Episodes D4-D6 — groups, the unusual and pattern match
description: Part 2 of 3 of Inspecto LA training videos — D · The analysis toolbox: narration scripts: D4_Groups-and-weak-points; D5_What-looks-unusual; D6_Pattern-match
tags: [module, la-core, link-analysis, user-guide, training, video]
timestamp: 2026-11-16T00:00:00Z
---

# Inspecto LA training videos — D · The analysis toolbox: narration scripts — Episodes D4-D6 — groups, the unusual and pattern match

Part 2 of 3 of [Inspecto LA training videos — D · The analysis toolbox: narration scripts](user-guide-training-d-analysis-toolbox.md) · previous: [Episodes D1-D3 — quick questions, connections, who matters](user-guide-training-d-analysis-toolbox-connections.md) · next: [Episodes D7-D8 — money, time and the whole Dataset](user-guide-training-d-analysis-toolbox-money-scope.md).

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
- Voice: Cohesive groups asks how tight a group is. Open it, and pick a Method. Dense core scores each node by how deeply it sits in a group where everyone has several links inside. Triangles counts the closed threes a node is part of. Cliques finds groups where everyone links to everyone. Press Find groups. Here every core is one deep: nothing here is a ring. That is a finding: access here was handed down a line, not passed round a circle.
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
- Voice: Under What looks unusual, open Similarity and prediction. Pick a Node: CL-0134, a passenger on six claims with six different drivers. Press Find similar nodes. The question is: who shares the most neighbours with this one? The table ranks nodes by the share of neighbours they have in common, from zero to one. At the top are the clinic CLI-04 and the lawyer LAW-02, then claimants who share its phones and addresses. Click a row, and the canvas selects and centres it.
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
- Voice: A score is a lead, never a verdict. The table is headed by CLI-01, the busy family doctor, at one hundred. It is well connected because many people visit a doctor, so it scores high for that reason alone. Compare it with the ring's clinic, CLI-04, which is referred to by one lawyer only. The score tells you where to look. The links, read one by one, tell you what you are looking at.
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
- Voice: Pick Pass-through intermediary. Its description: one account receives money and forwards it within forty-eight hours, check the middle account. Below it, the steps. A start node, then a step outgoing, then another step outgoing, carrying a chip: after the previous hop, within forty-eight hours. That chip is the time order, and it is shown so you can remove it. Press Find matches. The matches are listed under the button, one chain per row, and light up on the canvas.
- Chip: Pattern match · Pass-through intermediary · Find matches
- Action: Pattern ▸ *Pass-through intermediary* ▸ zoom on the steps and the *After the previous hop, within 48 hours* chip ▸ Find matches ▸ click two match rows. [check in UI: the matches listed, and whether a *Showing the first … matches* note appears]
- Impact: the matched links glow; mark the chip, then each clicked match.

### S04 · Build your own steps
- Voice: Now build one. Pick Custom pattern: a step with no link kind matches any link. Each step has a direction, outgoing, incoming or both ways, a link kind, and an Entity Type. Press Add step to chain another hop. In words: someone pays into an account that then pays on. Set each step's link kind to narrow the question, and press Find matches.
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
- Voice: Back on Structuring into one account. Sometimes you find one example first, and want every other one like it. Select a chain of entities on the canvas, and Use selection as pattern, greyed until something is selected, loads that chain as steps, with each step's direction, link kind and Entity Type taken from your example, and an after the previous hop chip. Only the order is kept, never the exact gaps. Then Find matches finds every other copy of the same route.
- Chip: Use selection as pattern
- Action: File ▸ Open… ▸ *Structuring into one account* ▸ Shift+click `MULE-HUB-02`, `CASHER-01`, `TILL-06` ▸ Use selection as pattern ▸ hold on the note *From your selection: …* ▸ Find matches. [check in UI: the derived steps and chips; the match count, expected eight]
- Impact: mark the loaded steps, then the eight matched routes.

### S09 · Save as pattern pack
- Voice: A pattern worth finding twice is worth saving. Press Save as pattern pack, give it a name, a category, and a description of what it looks for, and save. The line under the steps says it is saved, and everyone in this Space can now pick it. Saving a pack is a change to the Space, so it needs the Workbench author permission.
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
