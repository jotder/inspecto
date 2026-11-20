---
type: Brochure
title: Inspecto LA product demo videos — a plain, live walk-through with an explainer voice
description: Draft script for the Link Analysis product demo video — the real product recorded live with an explainer voice — giving the scene format and the narration and zoom or box notes of the bank mule-ring demo.
item: module.la-core
audience: internal
status: current
verified: 2026-11-20 code-reviewed
code: [la/inspecto-la-core]
---

# Inspecto LA product demo videos — a plain, live walk-through with an explainer voice

> **Draft of 2026-11-15, not reviewed by product or legal.** Operator direction (2026-11-15): no styled visuals. The picture is the **real product, full screen, recorded live**; the only graphics are a zoom or a mark on the node or subgraph being talked about. The voice does the explaining: short, vivid, conversational sentences in the manner of the reference explainer, a little faster than the earlier drafts, with the problem, the data, the mapping, the investigation and the conclusion told in that order. Same rules as [brochure-video-industry-scripts.md](brochure-video-industry-scripts.md): **no record counts or Dataset sizes** (say instead that search and filter answer in moments on millions of records), the outcome is a lead and never a verdict, no "fraudster", "criminal", "caught" or "proven".
> Built by `node tools/render-la-demo-video.mjs <name>`: the narration is synthesised first, its lengths pace a live recording of the product (`tools/record-la-live.mjs`, scenario `mule_demo`), and the final video overlays only the notes below. Output: `C:\sandbox\la-video-out\<name>.mp4` (1920 × 1080).

Scene format: `### <id> · <title>`, `- Voice:` the narration. Notes are optional, one JSON object per `- Note:` line, times in seconds from the start of the scene, coordinates in pixels of the 2560 × 1440 recording: `{"type":"zoom","t0":3,"t1":9,"x":1280,"y":720,"z":2}` (smooth push-in on that centre, then back out), `{"type":"ring","t0":3,"t1":9,"x":900,"y":500,"r":60,"label":"MULE-HUB-01"}`, `{"type":"box","t0":3,"t1":9,"x":800,"y":400,"w":300,"h":200,"label":"…"}`.

## Banking_MuleRing_Demo

### S01 · The problem
- Voice: Picture a bank on an ordinary Tuesday. Millions of transfers move through it, and almost every one of them is boring. That is exactly what a money-laundering ring is counting on. Here is the trick: nobody in this scheme ever deposits more than a thousand. No payment crosses a reporting line. No alert fires. And still, over three days, a small fortune leaves the country. So how do you find something that was designed to be invisible, one transaction at a time? You stop looking at transactions, and you start looking at connections. This is Inspecto LA. The investigator starts in a Space, where the questions that matter are saved as views.

### S02 · The data
- Voice: First, the data. Every transfer the bank books lands as a record: who paid, who was paid, the channel, cash deposit, wire, card or crypto, the amount, the countries, and the time. A Pipeline brings those records in and keeps this Dataset up to date by itself. Nothing to export, nothing to stitch together by hand. And this is not limited to a handful of records. The Dataset is indexed, so on the bank's full history, millions of records, search and filter still answer in moments.

### S03 · The mapping
- Voice: Now the part that matters: the columns. The product has read the file, and it tells you what each column is. The payer account and the payee account are both entities. They are accounts. The booking date is a time. Everything else rides along on the link. That is the whole mapping, and it is the key idea. Because both ends of every row are accounts, every row is already a link from one account to another. Two columns, and a table of transfers becomes a network you can walk.
- Note: {"type":"zoom","t0":3,"t1":26,"x":1600,"y":700,"z":1.6}
- Note: {"type":"box","t0":8,"t1":12.5,"x":790,"y":700,"w":480,"h":56,"label":"an Entity - an account"}
- Note: {"type":"box","t0":12.5,"t1":16.5,"x":1630,"y":700,"w":480,"h":56,"label":"an Entity - an account"}
- Note: {"type":"box","t0":16.5,"t1":21,"x":800,"y":808,"w":240,"h":50,"label":"a Time"}

### S04 · The graph
- Voice: Back in Link Analysis, the saved view draws itself. Every circle is an account. Every line is a payment, coloured by how the money moved. The product has also grouped the accounts that hang together, those coloured shapes. And the first thing you see is a dense, tangled core: ordinary customers paying each other. Too much to read, and that is the point. The skill is knowing where to look, so look away from the tangle.
- Note: {"type":"box","t0":12,"t1":23,"x":860,"y":440,"w":600,"h":620,"label":"ordinary customers paying each other"}

### S05 · The funnel
- Voice: There. Down in the corner, a shape that does not belong. One account in the middle, a ring of accounts around it, every line pointing in. That is a funnel. Ordinary customers do not pay each other like that. But a hunch is not evidence, so let's question it the way an investigator would, with questions the product can answer in a click.
- Note: {"type":"ring","t0":1.5,"t1":17,"x":704,"y":1060,"r":150,"label":"a funnel"}
- Note: {"type":"zoom","t0":2,"t1":17,"x":704,"y":1060,"z":2.3}

### S06 · First sweep
- Voice: Question one: who has the most links? One click on Busiest nodes, and the list appears. MULE-HUB-01 has more counterparties than any other account. Just behind it, a few ordinary accounts, busy, but busy is not suspicious by itself. What matters is what the hub's links look like. So we follow that one.
- Note: {"type":"zoom","t0":4,"t1":19.5,"x":2274,"y":700,"z":1.7}
- Note: {"type":"box","t0":7,"t1":13,"x":2024,"y":406,"w":500,"h":210,"label":"MULE-HUB-01"}
- Note: {"type":"box","t0":13.5,"t1":17.5,"x":2024,"y":636,"w":500,"h":184,"label":"busy - but ordinary"}

### S07 · Narrow down
- Voice: Now narrow down. Instead of the whole network, start a new graph from one thing. Pick the dataset, type the account, MULE-HUB-01, and before you draw anything the product tells you what it knows about it: how many links, and when it was first and last seen. One click on Draw.
- Note: {"type":"zoom","t0":8,"t1":16,"x":830,"y":700,"z":1.7}
- Note: {"type":"box","t0":10,"t1":15,"x":190,"y":716,"w":1280,"h":80,"label":"links - first seen - last seen"}

### S08 · The reveal
- Voice: And there it is. Turn on node labels so every id is readable. MULE-HUB-01 in the middle, and around it, twelve accounts, SMURF-01 to SMURF-12. Each one pays the hub in cash, again and again, eight times apiece, always just under a thousand. That is why nothing ever fired: every single deposit was small enough to hide. And look at the dashed arrows going out. The hub is not keeping the money. It wires it straight on, to two relays, RELAY-01 and RELAY-02.
- Note: {"type":"ring","t0":5.5,"t1":9.5,"x":1066,"y":760,"r":100,"label":"MULE-HUB-01"}
- Note: {"type":"ring","t0":9.5,"t1":16,"x":1066,"y":760,"r":580,"label":"12 SMURF accounts"}
- Note: {"type":"ring","t0":23.5,"t1":28,"x":994,"y":224,"r":75,"label":"RELAY-02"}
- Note: {"type":"ring","t0":25.5,"t1":30.5,"x":596,"y":1038,"r":75,"label":"RELAY-01"}

### S09 · The evidence
- Voice: Is this a guess? No. Open the table under the graph, and every line is a row: from, link kind, to. The hub to RELAY-01, a wire. The hub to RELAY-02, a wire. Each row is a record that can be read, quoted, and handed to a colleague. The graph is how you see the pattern. The table is how you back it up.
- Note: {"type":"zoom","t0":5,"t1":20,"x":1000,"y":1150,"z":1.25}
- Note: {"type":"box","t0":6,"t1":20,"x":150,"y":1230,"w":1450,"h":170,"label":"every line is a row"}

### S10 · The route
- Voice: One last question: where does the money end up? Go back to the full view and search for OFFSHORE. One box, and the graph lights up exactly one node, OFFSHORE-77, at the far end of the same funnel. The relays move the money on by crypto, to one offshore account. The whole route is now in front of you: twelve small accounts, one hub, two relays, one exit.
- Note: {"type":"box","t0":8.2,"t1":11.5,"x":780,"y":164,"w":440,"h":76,"label":"search the account"}
- Note: {"type":"zoom","t0":11,"t1":21,"x":1000,"y":1200,"z":1.4}
- Note: {"type":"box","t0":11,"t1":21,"x":150,"y":1236,"w":1450,"h":110,"label":"RELAY to OFFSHORE-77 - crypto"}

### S11 · The conclusion
- Voice: So what did we find? Ninety-six deposits, each just under a thousand, collected by one hub and passed on within hours. The outcome is a lead, not a verdict: a route to report, with the hub and the relays named, and every link behind it traceable to a record. No single transaction gave it away. The connections did. The same steps, a saved view, a search, the busiest nodes, a graph from one account, the table behind it, run on millions of records and answer in moments. Everything you just saw ships in Inspecto LA Basic, with Spaces like this one to explore. Bring your own records, from any domain, say once how they link, and ask who is connected. Inspecto LA.
- Note: {"type":"box","t0":2,"t1":18,"x":150,"y":1236,"w":1450,"h":110,"label":"the exit - OFFSHORE-77"}
