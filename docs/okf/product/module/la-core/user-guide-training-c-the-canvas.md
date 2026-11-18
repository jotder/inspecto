---
type: Guide
title: "Inspecto LA training videos — C · Reading and shaping the canvas: narration scripts"
description: "The spoken script of the training episodes in group C (Reading and shaping the canvas), scene by scene, with the action on screen and the change it causes. Format and status: the overview, user-guide-training-scripts.md."
resource: tools/render-la-demo-video.mjs
tags: [module, la-core, link-analysis, user-guide, training, video]
timestamp: 2026-11-16T00:00:00Z
---

# Inspecto LA training videos — C · Reading and shaping the canvas

> Narration scripts for group C of the Inspecto LA training series. How to read an episode, the voice and the
> renderer: [the overview](user-guide-training-scripts.md). Draft, not recorded, not reviewed by product; `[check in UI: …]` marks a detail to
> confirm on the live product before recording.

## Episodes in C

| Episode | Title | Edition | Scenes | Spoken words | UI checks open |
|---|---|---|---|---|---|
| [C1](#c1_moving-around-and-selecting) | Moving around and selecting | Basic | 12 | 710 | 3 |
| [C2](#c2_layouts-legend-counts-and-minimap) | Layouts, legend, counts and minimap | Basic | 12 | 808 | 6 |
| [C3](#c3_making-the-graph-readable-display-options-and-display-rules) | Making the graph readable: Display options and Display rules | Basic | 12 | 802 | 6 |
| [C4](#c4_expand-and-collapse) | Expand and collapse, one degree at a time | Basic | 10 | 947 | 6 |
| [C5](#c5_cleaning-up-hide-remove-trim-exclude-lists) | Cleaning up: hide, remove, trim, exclude, lists | Basic | 12 | 699 | 4 |

## C1_Moving-around-and-selecting

> **C1 · Moving around and selecting** · Basic · Insurance Space · the claimant ring that shares phones · 5 min · user
> guide §6.1, §6.2

- **Objectives:** move and zoom by mouse and keyboard; read a node and a link without clicking; open a node's details;
  pick exactly what a tool will act on, by click, lasso and the selection tools; read a crowded corner with a lens.
- **Before:** a fresh unzip of `la-basic.zip`, the Insurance Space, Link Analysis, the saved view
  `claimant_ring_shared_identity` open, Canvas mode Pan.
- **Covers (plan §5):** 5.2b Canvas mode (Pan, Lasso, Fisheye, Link filter), Select all, Invert the selection, Select
  neighbours, Clear the selection, the selection count; 5.6 L, F, Esc, Ctrl+A, Shift+click, Shift+drag, arrows, + / −;
  the hover card; 5.2d the node detail dialog (ID, Links, Neighbours, Expansion, Summary, Actions ▸ Hide on canvas, Focus,
  Expand from here, Collapse branch, Close); Fit to screen.
- **Try it:** lasso the family of four, then press Select neighbours. Does the selection grow, and what does that say
  about the family? *Answer: it does not grow (or barely): the family's shared phone and address link only its own four,
  so they are a group of their own, not part of the ring (confirm at recording).*
- **Check:** in Pan mode, how do you draw a lasso without switching mode? *Answer: Shift and drag.*
- **Gotchas:** Insurance masks the Party type, but a Space that sets no masking mode masks nothing on Basic, so ids read
  in the clear here (confirm; F7 covers masking). Case history, Risk and the Entity page in the node dialog are Standard.
  Whether a plain click also selects the node as it opens its dialog is confirmed at recording (S05, S07).

### S01 · Choosing what a tool acts on
- Voice: This is Inspecto LA training, episode C1: moving around and selecting. Every tool in Inspecto LA acts on something: the node you picked, the group you drew a loop around, or everything on screen. Moving around a graph, and choosing exactly what a tool will act on, is the everyday skill behind all the others. We are in the Basic edition, in the Insurance Space, on a ring of claimants who share phones and addresses.
- Action: intro card.
- Impact: none.

### S02 · The claimant ring
- Voice: From the File menu, open the saved view claimant_ring_shared_identity. It keeps only the links that carry no money: two claimants sharing a phone, an address, or one accident. The ring, twelve claimants on nine claims, sits together in one dense group. A family of four, who share one phone and one address, forms a small group of its own.
- Chip: File · Open…
- Action: File ▸ Open… ▸ Saved views ▸ `claimant_ring_shared_identity` ▸ Open ▸ wait for the draw.
- Impact: mark the ring's group, then the family (CL-0103, CL-0217, CL-0218, CL-0249).

### S03 · Pan, zoom and the keyboard
- Voice: The Canvas mode decides what a drag on empty canvas does, one mode at a time. Pan is the start: drag to move the view, and scroll to zoom. With the graph focused, the arrow keys move it, and plus and minus zoom. When you are lost, Fit to screen brings everything back into view.
- Chip: Pan · arrows · + / −
- Action: hover Pan (*Pan: drag to pan, Shift+drag to lasso (Esc)*) ▸ drag the canvas ▸ scroll in ▸ click the canvas background ▸ arrow keys ▸ `+` and `−` ▸ drag one node ▸ Fit to screen.
- Impact: before/after hold of each move; mark the Pan tooltip.

### S04 · The hover card
- Voice: Rest the pointer on a node. A hover card names it, its Entity Type, and how many links it has on this graph. Hover a link instead, and the card names its kind and its two ends. The hover card answers what is this, without a click.
- Chip: Hover card
- Action: hover CL-0134 ▸ hold ▸ hover one `shares_phone` link ▸ hold. [check in UI: the card's lines under this view]
- Impact: mark both cards.

### S05 · A node's details
- Voice: Click a node for everything about it. Click one of the family, CL-0249. The dialog shows its id, its links in and out, and its neighbours: the other three of the family. Its Summary reads the node's links in the Dataset the graph was drawn from. Press Summarise to see them. Risk and Entity Lists have nothing for this pack, and the Actions are below.
- Chip: Node details · Summary
- Action: click CL-0134 ▸ hold on ID, Links, Neighbours, Expansion ▸ Summary ▸ *Summarise its links in claims_links_dataset* ▸ hold. [check in UI: the Summary's counterparts show the six claims (ground truth: passenger on 6 claims with 6 drivers)]
- Impact: mark the Links row and the Summary's counterparts.

### S06 · What the dialog can do
- Voice: At the foot of the dialog, Expand from here adds the node's neighbours, and Focus centres the canvas on the node. Under Actions, Hide on canvas takes it off this picture only. Case history and the Entity page come with the Standard edition.
- Chip: Focus · Expand from here · Hide on canvas
- Action: point at Focus, Expand from here, Collapse branch (if offered), Actions ▸ Hide on canvas ▸ press Focus.
- Impact: before/after hold: the canvas centres on CL-0134.

### S07 · Shift and click
- Voice: Now selection. A plain click opens the details, so you pick nodes by drawing a loop with Lasso. The count beside the selection tools says how many are picked, and the picked nodes are marked on the canvas.
- Chip: Lasso · selection count
- Action: Shift+click CL-0134 ▸ Shift+click two `shares_phone` neighbours ▸ Shift+click one again to drop it ▸ zoom on the count chip. [check in UI: whether a plain click also selects; if it does, the voice says "click picks one; Shift and click adds"]
- Impact: mark the count chip changing 1 → 3 → 2.

### S08 · Lasso
- Voice: To pick a whole group, draw around it. Press L for Lasso, and drag a loop around the family of four. Everything inside is selected, and the count appears. In Pan, Shift and drag does the same, so with a mouse you rarely need to switch; on a touchpad, Lasso is easier.
- Chip: Lasso · L
- Action: L ▸ drag a loop around the ring ▸ hold on the count ▸ Esc (back to Pan) ▸ Shift+drag a loop round the same group.
- Impact: before/after hold: the ring selected; mark the count.

### S09 · Build on a selection
- Voice: The selection tools build on what you picked. Select neighbours adds everything linked to the selection: one press, and everything linked to your pick is in. Invert the selection swaps picked and unpicked, so you can act on everything else. Select all, or Control A, picks everything. And Clear the selection, or Escape, empties it. Each changes only the selection; the graph stays as it is.
- Chip: Select neighbours · Invert the selection · Select all · Clear the selection
- Action: Select neighbours ▸ hold ▸ Invert the selection ▸ hold ▸ Select all ▸ hold ▸ Clear the selection.
- Impact: four before/after holds; mark the count each time.

### S10 · Fisheye
- Voice: Two more modes are lenses. Press F for Fisheye, and drag the lens over the ring's centre. The nodes under it spread apart and grow, so crowded labels can be read, while the rest of the graph stays put. Nothing moves for good: take the lens away, and the picture is as it was.
- Chip: Fisheye · F
- Action: F ▸ drag the lens across the ring's centre ▸ hold ▸ drag it off the graph.
- Impact: mark the magnified area.

### S11 · Link filter, and back to Pan
- Voice: Link filter is the other lens: only the links inside it are drawn, and the rest fade from that patch. Drag it between the ring and the family, and where one shared phone or address ties them, you see that link alone. Escape brings you back to Pan, from any mode.
- Chip: Link filter · Esc
- Action: Link filter ▸ drag the lens between the ring and the family ▸ hold ▸ Esc ▸ hold on Pan pressed.
- Impact: mark the links inside the lens.

### S12 · Try it
- Voice: Your turn. Lasso the family of four, then press Select neighbours. Does the selection grow, and what does that tell you about the family? The answer is in the description. Next, in C2, layouts, the legend, counts and the minimap.
- Action: outro card: *Try it* and *Next: C2 · Layouts, legend, counts and minimap*.
- Impact: none.

## C2_Layouts-legend-counts-and-minimap

> **C2 · Layouts, legend, counts and minimap** · Basic · Public sector Space · bid rotation in road maintenance · 6 min
> · user guide §5.4, §6 (*Layouts*, *Display options*)

- **Objectives:** pick a layout for the question you ask; read why a layout is greyed and how to make it available; read
  the legend, hide a kind of link and know what that does not change; read the Graph counts and Limits; use the minimap.
- **Before:** a fresh unzip of `la-basic.zip`, the Public sector Space, Link Analysis, the saved view `bid_rotation`
  open (layout Force), Legend and Graph counts minimised to pills.
- **Covers (plan §5):** 5.2f all 14 layouts (Layered, Grid, Force, Clustering force, Radial, Degree ordered, Circular,
  Information density, Force (spread), Mind map, Organisation chart, Radial tree, Dendrogram, Fishbone), the greyed
  reasons (*needs at most N nodes (this graph has M)*, *needs a tree-shaped graph*, Layered *too wide*), *Reduce the graph
  to use more layouts*; Fit to screen; 5.2g Legend (node kinds and counts, link-kind chips, *Hiding only changes the
  drawing…*, Ringed, Dotted outline, Next unexpanded, Only unexpanded, Minimise the legend), Graph counts (on-screen
  line, tiles, *cut short*, Limits, Minimise graph counts), minimap (Display options ▸ Minimap, Show the minimap / Hide
  the minimap).
- **Try it:** in the legend, hide every kind of link except `bid_on`, and choose Clustering force. How many firms bid on
  the northern block of tenders? *Answer: four: CO-0117, CO-0242, CO-0388 and CO-0561.*
- **Check:** you hide `won` in the legend and press Expand. Are win links followed? *Answer: yes. A legend chip changes
  the drawing only; Expand still follows hidden link kinds.*
- **Gotchas:** *Customise counts* is not reachable on the Link Analysis page (the Graph counts card is not given the
  option), so it is not shown; the plan's row is corrected here. Which layouts are greyed depends on the drawn node count
  (Radial ≤ 100, Clustering force ≤ 200, Information density ≤ 300 by default): every greyed/available claim is
  confirmed at recording. Ringed and Dotted rows show only when such nodes are drawn.

### S01 · One graph, many drawings
- Voice: This is Inspecto LA training, episode C2: layouts, the legend, counts and the minimap. The same links can be drawn in many ways, and each way answers a different question: what passes where, who sits in the middle, which group sticks together. In this episode you try every layout, learn when each helps and when the product says no, and read the legend, the counts and the minimap. We are in the Basic edition, in the Public sector Space, on bid rotation.
- Action: intro card.
- Impact: none.

### S02 · The road maintenance market
- Voice: Open the saved view bid_rotation. It holds one market, road maintenance: the bids, the wins, the directors of the road firms, and the officials who changed contracts. Somewhere in it, twenty-four tenders in the north are only ever bid on by four firms, which take turns to win. Fit to screen, in the toolbar, brings the whole graph into view, clear of the overlays. Use it whenever you are lost.
- Chip: Fit to screen
- Action: File ▸ Open… ▸ `bid_rotation` ▸ zoom in on a corner ▸ Fit to screen.
- Impact: before/after hold: zoomed corner → the whole graph.

### S03 · The Layout menu, and its honesty
- Voice: Open Layout. Fourteen layouts, the current one ticked. Some are greyed out, and each says why when you point at it: Radial needs at most a hundred nodes, and this graph has more; the tree layouts need a tree-shaped graph. When a layout is too big for the graph, the menu adds Reduce the graph to use more layouts. A layout that would freeze the screen is offered only when it can work.
- Chip: Layout
- Action: Layout ▸ hold on the list ▸ hover a greyed Radial (*Radial: needs at most 100 nodes (this graph has N)*) ▸ hover Mind map (*needs a tree-shaped graph*) ▸ point at *Reduce the graph to use more layouts*. [check in UI: the view's node count, and which layouts it greys; reword to the reasons shown]
- Impact: mark the two greyed reasons and the Reduce item.

### S04 · The force family
- Voice: The force layouts treat links as springs: linked nodes pull together, unlinked ones push apart, so groups show as clumps. Force is the start. Force, spread, keeps more even gaps, so a dense clump opens up. Clustering force pulls each group tighter still, but it is greyed here: it needs at most two hundred nodes, and this graph has five hundred and twelve. Choose Force, spread, and the clumps open up.
- Chip: Force · Force (spread) · Clustering force
- Action: Layout ▸ Force (spread) ▸ hold ▸ Layout ▸ Clustering force ▸ hold. [check in UI: Clustering force is available (≤ 200 nodes); if greyed, say so and stay on Force (spread)]
- Impact: before/after holds; mark the northern block.

### S05 · Layered, and too wide
- Voice: Layered sets nodes in rows by the direction of their links, so a chain of payments or approvals reads from top to bottom. On a wide, shallow graph it is greyed as too wide, because too many nodes would share one row and the picture would be a sliver. It is greyed here, and the menu says why when you point at it. A market of bids is wide; a chain of transfers is where Layered is the clearest layout there is.
- Chip: Layered
- Action: Layout ▸ hover or choose Layered. [check in UI: whether Layered is greyed *too wide (N nodes in one row, max 60)* on this view; if it runs, hold on it and the voice says "here it runs, and the tenders sit in one long row"]
- Impact: mark the reason, or the layered drawing.

### S06 · Order before shape
- Voice: Three layouts put order before shape. Grid sets every node in rows and columns, nothing overlapping, which is good for reading every name. Circular puts them all on one ring, so links cross the middle and dense pairs show as thick chords. Degree ordered puts the most-linked nodes in the centre and the least at the edge, so the busiest firms move inward.
- Chip: Grid · Circular · Degree ordered
- Action: Layout ▸ Grid ▸ hold ▸ Circular ▸ hold ▸ Degree ordered ▸ hold.
- Impact: three before/after holds; mark the centre of Degree ordered.

### S07 · Radial and Information density
- Voice: Radial puts one node in the middle and the others in rings around it, by how many steps away they are. Information density places nodes so that distance on screen follows distance in the graph. Both are for smaller graphs, a hundred and three hundred nodes by default, and say so. To use them here, reduce the graph first: Clean up, a filter, or Collapse.
- Chip: Radial · Information density
- Action: Layout ▸ hover Radial ▸ choose Information density if available, else hover it. [check in UI: availability at this node count]
- Impact: mark the reason or the drawing.

### S08 · The tree layouts
- Voice: The last five are for trees, where every node has one way back to a root: Mind map, Organisation chart, Radial tree, Dendrogram and Fishbone. Here they are all greyed, because this market is not a tree. To use one, cut the graph down to a tree first: select a director and its firms, and Clean up can keep only the selection. The Reduce the graph to use more layouts entry at the foot of the menu lists the ways.
- Chip: Organisation chart · Mind map
- Action: Search nodes `P-00001` ▸ Shift+click it ▸ Select neighbours ▸ Clean up ▸ Keep only selected ▸ Apply ▸ Layout ▸ Organisation chart ▸ hold ▸ Mind map ▸ hold ▸ Undo display change (Ctrl+Z) until the whole graph is back ▸ Layout ▸ Force. [check in UI: P-00001's neighbours on this view are CO-0242 and CO-0561 only, so the kept graph is a tree]
- Impact: before/after holds: three nodes as an organisation chart, then a mind map.

### S09 · The legend
- Voice: Open the Legend pill at the top left. It lists each kind of node with how many are on screen, and under them one chip per kind of link. Click won: the win links leave the drawing, and its chip is struck through. Hiding only changes the drawing; Expand still follows hidden kinds. Two more rows appear when they apply: Ringed, a busy entity shown but not expanded, and Dotted outline, not expanded yet, with Next unexpanded to step through them.
- Chip: Legend · link kinds
- Action: Legend pill ▸ hold on node kinds and counts ▸ click the `won` chip ▸ hold ▸ click it again ▸ zoom on *Hiding only changes the drawing. Expand still follows hidden link kinds.* ▸ point at the Dotted outline row and Next unexpanded if shown ▸ Minimise the legend. [check in UI: the node kinds listed for this view, and whether the Dotted outline row shows on a saved view]
- Impact: before/after hold: win links hidden; mark the struck-through chip and the note.

### S10 · Graph counts and Limits
- Voice: The legend you just opened is also the quickest count: it lists how many entities are on screen, here five hundred and twelve. The Graph counts card goes further, with links shown out of loaded and the node limits in force. When a limit cuts a result, the card says cut short, and a notice says what to do: never a silent gap.
- Chip: Graph counts · Limits
- Action: Graph counts pill ▸ hold on the first line and tiles ▸ Limits ▸ hold ▸ Esc ▸ Minimise graph counts.
- Impact: mark the on-screen line, the *Nodes* and *Links* tiles and the Limits list.

### S11 · The minimap
- Voice: On a big graph, the minimap keeps you oriented. Display options, Minimap, offers one on this canvas. Here it sits at the lower right of the canvas. The frame inside it is your view, so you can see where you are in the whole. Hide the minimap folds it away, clear of every label, and Show the minimap brings it back.
- Chip: Minimap · Show the minimap
- Action: Display options ▸ confirm Minimap is ticked ▸ close ▸ Show the minimap ▸ drag its frame ▸ Hide the minimap.
- Impact: mark the button; before/after hold as the view follows the frame.

### S12 · Try it
- Voice: Your turn. In the legend, hide every kind of link except bid_on, and choose Clustering force. How many firms bid on the northern block of tenders? The answer is in the description. Next, in C3, making the graph readable.
- Action: outro card: *Try it* and *Next: C3 · Display options and Display rules*.
- Impact: none.

## C3_Making-the-graph-readable-Display-options-and-Display-rules

> **C3 · Making the graph readable: Display options and Display rules** · Basic · Healthcare Space · referral
> kickbacks · 6 min · user guide §6 (*Display options*, *How nodes look by default*), §6.4

- **Objectives:** use every Display options control on purpose; take display changes back; colour nodes by a condition
  with Display rules, and know where node details come from.
- **Before:** a fresh unzip of `la-basic.zip`, the Healthcare Space, Link Analysis, the saved view `referral_kickbacks`
  open, Display options at their defaults.
- **Covers (plan §5):** 5.2e Node labels, Link labels, All link labels, Arrows and node labels on large graphs, Align
  while dragging, Highlight on hover, Legend, Graph counts, Level of detail, Minimap, Grid, Community outlines, Community
  shading, Link bundling, Group single-link neighbours, Node size, Node spacing, Link width, Node colours, Node shape,
  Link colours, Link pattern, Link size; Undo display change / Redo display change; 5.4 ⚙ Settings ▸ Display rules
  (Entity Type, Attribute, Comparison, Value, swatch, Add rule, the no-attributes message); ⚙ Settings ▸ Graph model ▸
  Entity/Link (several Datasets) for node details.
- **Try it:** on `referral_kickbacks`, make `lives_at` links dashed and thick, and turn on Community outlines. Which two
  owners share a home, and which practices are theirs? *Answer: OW-0434 (owner of PR-1170, Emberlea Imaging Centre) and
  OW-1270 (owner of PR-1426, Brackenmoor Surgery), at AD-05150.*
- **Check:** does Display options or a Display rule change what Expand or a tool reads? *Answer: no. Both change only
  the drawing; Undo display change takes them back.*
- **Gotchas:** a graph drawn from one link Dataset gives its nodes no details, so Display rules on the saved view says
  *The loaded nodes carry no attributes to build a rule on.*; S11 draws a two-Dataset graph (provider register +
  referrals) to give the nodes a `specialty`. That graph may exceed the 1,000-node canvas; if so, narrow the referral
  mapping with its own filter. Single-Dataset views draw every node as one kind, so the automatic colours land on link
  kinds; node colour and shape are shown on the one kind. Group single-link neighbours, Level of detail and Link width's
  Measure button depend on the drawn graph: confirm each at recording.

### S01 · Readable is explainable
- Voice: This is Inspecto LA training, episode C3: making the graph readable. A graph you cannot read is a graph you cannot explain to anyone else. Labels, lines, colours and groups are yours to set, and none of them changes the data. In this episode you go through Display options one control at a time, take back what you do not like, and colour nodes by a condition. We are in the Basic edition, in the Healthcare Space, where two practices send nearly all their referrals to one imaging centre.
- Action: intro card.
- Impact: none.

### S02 · The brush
- Voice: Open the saved view referral_kickbacks: every practice that sends sixty percent or more of its referrals to one provider, the owners of both ends, and any home two owners share. Most are practices in one seaside town, sending patients to its only hospital. Now open Display options, the brush in the toolbar. Its icon is tinted whenever a label switch differs from the start, so you can tell at a glance that a picture was adjusted.
- Chip: Display options
- Action: File ▸ Open… ▸ `referral_kickbacks` ▸ Display options (brush) ▸ hold on the panel.
- Impact: mark the panel's sections.

### S03 · Labels
- Voice: Labels first. Node labels show on their own up to eighty nodes, and the line under the graph says when they are off and where they come back. Link labels writes each link's kind on it. Above twenty links they show only on hover, unless you tick All link labels. On a very large graph, arrows and node labels show on hover only, unless you tick Arrows and node labels on large graphs, at the cost of slower drawing.
- Chip: Node labels · Link labels · All link labels
- Action: untick Node labels ▸ hold on the bottom line ▸ tick it ▸ tick Link labels ▸ tick All link labels ▸ hover *Arrows and node labels on large graphs* ▸ untick All link labels and Link labels.
- Impact: before/after holds; mark the brush turning tinted.

### S04 · Moving and overlays
- Voice: Align while dragging shows guide lines, so a node you move lines up with its neighbours. Highlight on hover lights a node and its links as you point. Under Overlays are the Legend and Graph counts cards; Level of detail, which drops labels above three hundred nodes so a big graph stays quick; the Minimap; and a Grid behind the graph.
- Chip: Align while dragging · Highlight on hover · Overlays
- Action: tick Align while dragging ▸ drag a node near another (guide lines) ▸ hover a node (highlight) ▸ tick Grid ▸ untick Grid ▸ hover Level of detail and Minimap.
- Impact: mark the guide lines and the highlight.

### S05 · Communities and bundles
- Voice: Community outlines draws a line around each group of nodes that link mostly among themselves, so the seaside town's practices sit inside one outline, and the city's elsewhere. Community shading fills those outlines, and needs them on. Link bundling draws links that run side by side as one bundle, so a busy area reads as roads, not a tangle.
- Chip: Community outlines · Community shading · Link bundling
- Action: Community outlines (on in this view) ▸ tick Community shading ▸ hold ▸ tick Link bundling ▸ hold ▸ untick both. [check in UI: the outlines group the town's practices with the hospital PR-1129]
- Impact: before/after holds; mark the town's outline.

### S06 · Group single-link neighbours
- Voice: Group single-link neighbours folds the nodes that link only to one hub into a single group beside it. The hospital's practices fold into one, and the panel says how many per hub: click a group to open it. The picture loses its crowd and keeps its meaning.
- Chip: Group single-link neighbours
- Action: tick Group single-link neighbours ▸ hold on *N+ per hub · click a group to open it* ▸ click the group by PR-1129 ▸ untick. [check in UI: a group forms at PR-1129 on this view]
- Impact: before/after hold: the practices around PR-1129 fold into one group node.

### S07 · Size, spacing and width
- Voice: Node size is Tiny, Small, Medium or Large, and it never follows the data, so a big node is never mistaken for an important one. Node spacing, from Compact to Widest, spreads the layout out, and the layout runs again. Link width draws each link by its count, or by the Measure the links carry, and the line under it says which.
- Chip: Node size · Node spacing · Link width
- Action: Node size Medium ▸ Node spacing Compact ▸ then Widest ▸ back to Wide ▸ Link width: Count, then the Measure button if enabled ▸ hold on the hint line. [check in UI: the Measure button's label (`amount`) and whether it is enabled on this view]
- Impact: before/after holds of each change.

### S08 · Colours, shapes and patterns
- Voice: Below are the colours. Every kind without a choice of yours gets an automatic colour and shape, and the most common kind stays neutral. Pick a colour for a kind of node, and a shape: circle, square, diamond, triangle or star. For links, a colour, a pattern, solid, dashed or dotted, and a size. Make owned_by dashed and lives_at thick: the shared home now reads at a glance. Your choices beat the automatic ones, and the legend shows them too.
- Chip: Node colours · Node shape · Link pattern · Link size
- Action: Node colours: one swatch on the node kind ▸ Node shape: Diamond ▸ Link pattern: `owned_by` Dashed ▸ Link size: `lives_at` L ▸ open the Legend pill. [check in UI: the link kinds listed (`referred_to`, `owned_by`, `lives_at`) and the node kind label]
- Impact: before/after hold; mark AD-05150 and its two `lives_at` links.

### S09 · Undo and Redo
- Voice: Every one of these is a display change. The toolbar's Undo display change, Control Z, takes them back one at a time, and Redo display change, Control Y, puts them back. None of them touches the data, or what a tool or an Expand will read. A saved view keeps your display choices, never the automatic look.
- Chip: Undo display change · Redo display change
- Action: Undo display change three times ▸ Redo display change once.
- Impact: before/after holds as each change leaves and returns.

### S10 · Display rules need details
- Voice: Display options colours a whole kind. Display rules colours nodes by a condition on their details. Open Settings, Display rules. On this graph it says the loaded nodes carry no attributes to build a rule on. A rule reads a node's details, and a graph drawn from one link table has none. Details come from a node Dataset in the Graph model, or from a file of details you add yourself.
- Chip: Settings · Display rules
- Action: Settings ▸ Display rules ▸ hold on *The loaded nodes carry no attributes to build a rule on.* [check in UI: the message on this view]
- Impact: mark the message.

### S11 · A rule on a graph with details
- Voice: So a graph needs details first. In Settings, Graph model, Graph built from offers Entity/Link over several Datasets: add a node mapping, such as the provider register with its id column and specialty as an attribute, and a link mapping for the referrals, then Run. Back in Display rules, a rule is a condition: a type, an attribute such as specialty, a comparison, a value such as Imaging, a colour swatch, and Add rule. Every imaging centre turns that colour. The first rule that matches wins, and rules follow Undo and the saved view.
- Chip: Display rules · Add rule
- Action: Settings ▸ Graph model ▸ Graph built from: Entity/Link (several Datasets) ▸ Add node mapping: Node Dataset `provider_dataset`, Id column `provider_id`, Attribute columns `specialty` ▸ Add link mapping: Link Dataset `referral_dataset`, `from_provider` → `to_provider` ▸ Run ▸ Settings ▸ Display rules: Entity Type *any type*, Attribute `specialty`, Comparison equals, Value `Imaging`, a red swatch ▸ Add rule ▸ close. [check in UI: the node count stays under 1,000 (else filter the link mapping to `to_provider` in PR-1170, PR-1129); the specialty value is spelled `Imaging`]
- Impact: before/after hold: the imaging centres turn red; mark PR-1170.

### S12 · Try it
- Voice: Your turn. On the referral view, make lives_at links dashed and thick, and turn on Community outlines. Which two owners share a home, and which practices are theirs? The answer is in the description. Next, in C4, expand and collapse, one degree at a time.
- Action: outro card: *Try it* and *Next: C4 · Expand and collapse*.
- Impact: none.

## C4_Expand-and-collapse

> **C4 · Expand and collapse, one degree at a time** · Basic · Mobile Money Space · the structuring funnel · 6 min ·
> user guide §7.1–§7.3

- **Objectives:** grow a graph on purpose from one entity; choose the direction of an expand and get back to the
  defaults; recognise a held busy entity and expand through it deliberately; fold a branch away and back.
- **Before:** a fresh unzip of `la-basic.zip`, the Mobile Money Space, Link Analysis open on an empty canvas (the *Start a
  graph* launchpad). Wait until the link Dataset `mm_links_dataset` has rows: a fresh install builds it on a schedule.
- **Covers (plan §5):** 5.2b Expand, Collapse/unfold, Held; 5.2c double-click; 5.2g dotted outline; 5.2i Start a graph
  (One entity); 5.6 G, C, Ctrl+Z.
- **Try it:** start from the agent AG-18, set Direction to Outgoing, and expand one degree at a time until the money
  leaves as cash. Which agent is it, and after how many expands? *Answer: AG-39, after five expands (the wallets, the
  hub, the relays, RELAY-3, the agent).*
- **Check:** what does a ring with a *held* badge mean? *Answer: a busy entity, with more linked entities than the busy
  limit: it is on the graph, but nothing is expanded through it unless you choose Expand anyway.*
- **Gotchas:** the Space's busy limit is 500 and no planted entity reaches it, so S07 lowers it to 20 for one Expand,
  and says so. While exploring, **G** opens the Expand form (in an Investigation it expands at once); the right-click
  *Expand, this once* options are Investigation-only and belong to F2.

### S01 · Grown, not drawn
- Voice: This is Inspecto LA training, episode C4: expand and collapse. A graph in Inspecto LA is grown, not drawn whole. You start from one thing you know, and you add what is linked to it, one step at a time, so that every node on the screen is there for a reason you chose. In this episode you will grow a graph from one wallet, decide which way an expand goes, see what happens when you reach a busy entity, and fold a branch away when you no longer need it. We are in the Basic edition, in the Mobile Money Space.
- Action: intro card; then the empty canvas with the Start a graph launchpad.
- Impact: none.

### S02 · Start from one wallet
- Voice: Start from one entity. In Start a graph, choose One entity, keep the link Dataset, and type the wallet HUB-01. Before you draw anything, the form tells you what it knows about it: how many links it has, and when it was first and last seen. Press Draw. HUB-01 appears in the middle, with everything linked to it directly: twenty-five wallets that each paid it once, and two relays that it paid onwards. A start always draws one ring: the wallet you started from, and its direct neighbours, nothing more. Everything further out, you add on purpose.
- Chip: Start a graph · One entity
- Action: Start from: One entity ▸ Dataset `mm_links_dataset` ▸ type `HUB-01` ▸ wait for the line under the value (links, first seen, last seen) ▸ Draw. (Basic's Start a graph has no Degrees stepper: the start draws one ring; the stepper is in the Investigation start form, F1.)
- Impact: before/after hold: empty canvas → 28 nodes. Marks: HUB-01; the ring of STRUCT-01…25; RELAY-1 and RELAY-2. Hold the start's result line above the canvas for 2 s.

### S03 · The dotted outline
- Voice: Look at the outlines. Every wallet around the hub has a dotted outline. Dotted means not expanded yet: nothing has followed its links, so there may be more behind it than you can see. The legend says so in words, so you never have to remember what a dotted line means. When you wonder where to look next, the dotted nodes are the unexplored edge of your graph.
- Chip: Legend · Dotted outline
- Action: zoom on one STRUCT wallet's outline; open the Legend pill if it is minimised; hover the row *Dotted outline: not expanded yet*. [check in UI: that the start entity draws a solid outline and the legend row shows on an exploring canvas]
- Impact: mark the legend row and two dotted outlines.

### S04 · Expand, with a direction
- Voice: Now expand. Click RELAY-1 to select it, and press G, or Expand on the canvas toolbar. Because we are exploring, not recording an Investigation, the Expand form opens over the canvas. From says where the expand starts: the one selected wallet. Under the button is the Expand filter, in folds, each saying on one line what is set. Open Direction and choose Outgoing: we want to know where this relay sends its money. The fold gets a dot, because it differs from the default. Press Expand. One new wallet appears, RELAY-3. Notice that nothing else moved. A small expand draws its new nodes outside what is already there, so the picture you were reading stays where it was. Back in the form, Reset to defaults puts Direction back to Both ways.
- Chip: Expand · G
- Action: click RELAY-1 ▸ G ▸ zoom on the *From* line and the preview line under Expand ▸ Direction fold ▸ Outgoing ▸ Expand ▸ reopen the form ▸ Reset to defaults. [check in UI: the preview line's wording on Basic, and whether the form keeps its filter between expands while exploring]
- Impact: before/after hold: RELAY-3 appears beyond RELAY-1; mark it; every earlier node in place.

### S05 · Double-click, and Undo
- Voice: Double-click does the same for one node, without the form. Double-click RELAY-3. Its neighbours fan out. The second relay was already on the screen, so only its link is new, and one new node arrives: the agent AG-39, where the money is taken out as cash. That is the whole chain, from the hub to the cash-out, built one degree at a time. Every step ends with a result line at the top of the canvas, with Undo beside it. A step you did not mean is one click, or Control Z, away.
- Chip: Double-click · expand one node
- Action: double-click RELAY-3 ▸ hold on the result line and its Undo button (do not press it).
- Impact: mark AG-39 and the new link from RELAY-2 to RELAY-3.

### S06 · Collapse and unfold
- Voice: A graph also needs room. Select HUB-01 and press C, or Collapse on the canvas toolbar. Collapse folds a node's branch, the neighbours that link to nothing else, into the node itself. The twenty-five wallets fold into the hub, and a badge says how many are inside. The relays stay, because they lead on to the rest of the chain. Double-click the hub to unfold them again. Collapse only changes what you see. Nothing is deleted, and even inside an Investigation nothing is recorded: it makes room on the screen, it is not a decision about the evidence.
- Chip: Collapse / unfold · C
- Action: click HUB-01 ▸ C ▸ hold ▸ double-click HUB-01. [check in UI: the badge reads +25]
- Impact: before/after hold: 25 wallets fold into a +25 badge on HUB-01; mark the badge; then the unfold.

### S07 · A busy entity is held
- Voice: Some entities are busy: a large biller, an agent that serves a whole town, a bank's own account. Expanding through one would flood the screen and bury the links that matter. Select one of the wallets that paid the hub, STRUCT-01, and press G. Open Fan-out and busy. Hold busy entities is ticked: an entity with more linked entities than the busy limit stays on the graph, but the product does not expand through it. This Space's busy limit is five hundred, and no wallet in this story comes near it. So for this lesson only, set the Busy limit to twenty, and press Expand. One node arrives: the agent AG-18, with a ring, a held badge, and a pill on the canvas that says one held.
- Chip: Fan-out and busy · Busy limit
- Action: click STRUCT-01 ▸ G ▸ Fan-out and busy fold ▸ Busy limit 20 ▸ Expand. [check in UI: that AG-18, 25 linked, is held at a limit of 20, and that HUB-01, already expanded, is not]
- Impact: mark AG-18's ring and held badge, and the *1 held* pill.

### S08 · Expand anyway, on purpose
- Voice: Open the held pill. The Held tray lists each busy entity with how many entities it links to, here twenty-five, and says why it was held. You have two choices. Hide held takes all of them off the graph in one step. Expand anyway goes through one of them, because you decided to. Press Expand anyway on AG-18. Its links fan out to every one of the twenty-five wallets that paid the hub. One agent cashed in for all of them, every amount just under the five thousand limit. That is the finding planted in this Space, and you reached it by choosing, one step at a time, where to look.
- Chip: Held · Expand anyway
- Action: click the *1 held* pill ▸ the tray *Held: busy … (1)* ▸ Expand anyway on AG-18. [check in UI: the Mobile Money pack's word for nodes in the tray title]
- Impact: before/after hold: 24 new links from AG-18 to STRUCT-02…25, marked yellow.

### S09 · Take it back, and what comes next
- Voice: Control Z takes back the last step: the twenty-four links go, and AG-18 is held again. On a graph you are only exploring, nothing you do is recorded, so try things freely. In an Investigation, every expand becomes a step in its log, with the same filter and more of it: time, strength and fan-out. That is episode F2.
- Chip: Undo · Ctrl+Z
- Action: Ctrl+Z ▸ hold.
- Impact: before/after hold: the 24 links leave; AG-18 keeps its ring.

### S10 · Try it
- Voice: Your turn. In the same Space, start from the agent AG-18. Set Direction to Outgoing, and expand one degree at a time, until the money leaves the system as cash. Which agent is it, and how many expands did it take? The answer is in the description. Next, in C5, you will clean a graph up: hide, remove, trim and exclude.
- Action: outro card: *Try it* and *Next: C5 · Cleaning up*. [check in UI: the Try it path ends at AG-39 after five outgoing expands]
- Impact: none.

## C5_Cleaning-up-hide-remove-trim-exclude-lists

> **C5 · Cleaning up: hide, remove, trim, exclude, lists** · Basic · Logistics Space · the inside job: one driver team and
> a night-shift scanner · 5 min · user guide §7.1 (*Remove*), §7.4, §7.5

- **Objectives:** reduce a graph to what matters with the five Clean up tools; choose between Hide, Remove and Exclude by
  how lasting each is; keep an entity out with an exclusion list; bring hidden nodes back.
- **Before:** a fresh unzip of `la-basic.zip`, the Logistics Space, Link Analysis, the saved view
  `driver_team_inside_job` open.
- **Covers (plan §5):** 5.2b Clean up the canvas (Keep paths between start entities with Longest path, Highlight shortest
  paths and Clear highlight, Trim dangling leaves, Keep only selected, Remove selected; the effect line, Apply, Cancel),
  Hide the selected nodes, Remove the selected nodes (Delete) with its change line and Undo, Exclude… (Hide from this
  graph, Add to a list…, List, New list name, Reason, Exclude), Hidden by lists (*N hidden on this graph (not
  recorded)*, Show them again, Create your first list), Reduce the graph (Clean up…, Filter…, Collapse the selection);
  Undo display change.
- **Try it:** select DRV-0892, DRV-0893 and EMP-0123, keep the paths between them of at most two links, and read the
  trips that remain. How many of the team's short loads did the scanner scan out? *Answer: ten of the twelve (ground
  truth: EMP-0123 at WH-06 scanned 10 of them out, between 00:50 and 03:30).*
- **Check:** Remove or Exclude: which keeps a node out of a later Expand? *Answer: Exclude with a list. Remove only takes
  it off this graph; an Expand that reaches it draws it again.*
- **Gotchas:** while exploring there are no recorded start entities, so Keep paths and Highlight shortest paths use the
  **selection** (two or more); the voice says so. On Basic, Hidden by lists shows the nodes hidden on this graph and
  *Show them again*; *Switch off* / *Switch on* a list are Investigation-only (F3), so the plan's row is narrowed here.
  Adding to an exclusion list needs the Manage Incidents and Cases permission when sign-in is on; confirm it is open on
  the Basic demo. Effect counts (*hides N of M entities*) are read from the panel at recording.

### S01 · Clearer, not just smaller
- Voice: This is Inspecto LA training, episode C5: cleaning up. A graph grows faster than it gets clearer. Cleaning up takes away what does not matter, so that what does is left on its own, and every way of taking something away says how lasting it is. We are in the Basic edition, in the Logistics Space, on an inside job: twelve loads that arrived short, one driver team, and a night-shift scanner.
- Action: intro card.
- Impact: none.

### S02 · The inside job
- Voice: Open the saved view driver_team_inside_job: every driver, co-driver and outbound scanner on two or more lost trips, with those trips and their loss reports. The team is here, DRV-0892 and DRV-0893, and so is the scanner, EMP-0123. So is the busiest driver on the busiest route, DRV-0894, with two thefts in over three hundred trips. Too much to read. Clean it up.
- Chip: File · Open…
- Action: File ▸ Open… ▸ `driver_team_inside_job` ▸ wait for the draw.
- Impact: mark DRV-0892, DRV-0893, EMP-0123, then DRV-0894.

### S03 · Highlight shortest paths
- Voice: The broom in the canvas toolbar is Clean up: five tools, and each says what it would do before it does it. Select the driver DRV-0892 and the scanner EMP-0123, and choose Highlight shortest paths. While you are exploring there are no recorded start entities, so your selection stands in for them. The trips that join the driver to the scanner light up, and nothing is removed. Clear highlight, or Escape, ends it.
- Chip: Clean up · Highlight shortest paths
- Action: Shift+click DRV-0892 and EMP-0123 ▸ Clean up (broom) ▸ Highlight shortest paths ▸ hold on *Lights N entities on the shortest paths; nothing is removed.* ▸ Highlight ▸ hold ▸ Clear highlight.
- Impact: before/after hold: the path glows; mark the effect line.

### S04 · Keep paths between them
- Voice: Now keep only what joins them. Add the co-driver, DRV-0893, to the selection, and choose Keep paths between start entities. Longest path, in links: two. The panel says what it would do before you agree: it hides most of the graph. Apply. What is left is the team, the scanner, and the trips that run between them.
- Chip: Keep paths between start entities · Longest path
- Action: Shift+click DRV-0893 ▸ Clean up ▸ Keep paths between start entities ▸ Longest path (links) `2` ▸ hold on *hides N of M entities* ▸ Apply ▸ hold. [check in UI: the effect line's numbers; the voice says "most of the graph" unless they say otherwise]
- Impact: before/after hold; mark the remaining trips.

### S05 · Trim, keep only, remove selected
- Voice: Three more tools. Trim dangling leaves hides the entities with a single link that lead nowhere, such as a loss report hanging off one trip; the selection and any held entity are spared. Keep only selected hides everything you did not select. Remove selected hides what you did. Each says how many it would hide, with Apply and Cancel, and a message afterwards says that Undo restores them.
- Chip: Trim dangling leaves · Keep only selected · Remove selected
- Action: Clean up ▸ Trim dangling leaves ▸ hold on the effect line ▸ Apply ▸ hold on the message *Hid N entities. Undo restores them.* ▸ open Clean up ▸ hover Keep only selected and Remove selected ▸ Cancel.
- Impact: before/after hold for Trim; mark the message.

### S06 · Undo the clean-ups
- Voice: While you explore, a clean-up is a display change. The toolbar's Undo display change, Control Z, puts back what the last one hid, one step at a time. Undo twice, and the whole view is back.
- Chip: Undo display change
- Action: Undo display change ▸ hold ▸ Undo display change ▸ hold. [check in UI: each Undo restores one clean-up]
- Impact: two before/after holds.

### S07 · Hide, and showing again
- Voice: To hide by hand, select and press Hide the selected nodes. Hide DRV-0894, the busy driver: it is ordinary, and it is in the way. Its count goes onto Hidden by lists, the last icon of the toolbar. Open it: one node hidden on this graph, not recorded, and Show them again brings it back.
- Chip: Hide the selected nodes · Hidden by lists
- Action: Shift+click DRV-0894 ▸ Hide the selected nodes ▸ zoom on the Hidden by lists count ▸ open Hidden by lists ▸ hold on the line ▸ Show them again ▸ hide it again.
- Impact: before/after holds; mark the count and the line.

### S08 · Remove
- Voice: Remove goes further. Select and press Delete, or the bin. The selected nodes leave the graph, and so does every neighbour left with no link at all. The result line says how many, that an Expand may bring them back, and offers Undo. Remove keeps nothing out for good: it only takes it off this graph.
- Chip: Remove the selected nodes · Delete
- Action: select one loss report (LR-…) ▸ Delete ▸ hold on *Removed N …; an Expand may bring them back.* and its Undo ▸ Undo.
- Impact: before/after hold; mark the change line and Undo.

### S09 · Exclude, two ways
- Voice: To keep something out on purpose, there is Exclude. Select DRV-0894 and press X. The dialog names what you exclude, and offers two ways. Hide from this graph: only on this screen, not recorded, and Undo brings it back. Or Add to a list: hidden here, and left out wherever the list is used.
- Chip: Exclude… · X
- Action: Show them again (Hidden by lists) ▸ Shift+click DRV-0894 ▸ X ▸ hold on *Exclude 1 …* and the two choices with their lines.
- Impact: mark the two choices and their hint lines.

### S10 · A list that keeps it out
- Voice: Choose Add to a list, and a new list: Busy drivers. Give a reason: two losses in over three hundred trips, ordinary for the route. Press Exclude. Now the busy driver stays out wherever this list is used, and the next graph you draw can start without it. Episode E2 shows lists in full.
- Chip: Add to a list… · New list name · Reason
- Action: Add to a list… ▸ List: new ▸ New list name *Busy drivers* ▸ Reason ✎ ▸ Exclude ▸ open Hidden by lists. [check in UI: the list choice's wording for a new list, and whether Hidden by lists then offers *Create your first list* or names the list]
- Impact: mark the node leaving and the Hidden by lists count.

### S11 · Reduce the graph
- Voice: One more way in. When a graph is too big for a layout or for Community outlines, those menus offer Reduce the graph: Clean up, Filter, or Collapse the selection. Each takes you straight to the tool that makes the graph small enough. Collapse, from episode C4, folds a branch into its node without hiding anything.
- Chip: Reduce the graph · Clean up… · Filter… · Collapse the selection
- Action: Undo display change until the full view is back ▸ Layout ▸ *Reduce the graph to use more layouts* ▸ hold on its three items ▸ Escape. [check in UI: the view is over the Radial cap so the item shows; if not, open it from Display options ▸ Community outlines' *Reduce the graph first.*]
- Impact: mark the three items.

### S12 · Try it
- Voice: Your turn. Select DRV-0892, DRV-0893 and EMP-0123, keep the paths between them of at most two links, and read the trips that remain. How many of the team's short loads did the scanner scan out? The answer is in the description. Next, in D1, quick questions: busiest, tight groups, and a route from A to B.
- Action: outro card: *Try it* and *Next: D1 · Quick questions*.
- Impact: none.
