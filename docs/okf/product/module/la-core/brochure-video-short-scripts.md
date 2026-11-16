# Inspecto LA short vertical videos — collage style, real product screens

> **Draft of 2026-11-15, not reviewed by product or legal.** About 85 seconds, 9:16, for social and presales. Style taken from the operator's reference video (*How Graph Analytics Uncovers Hidden Fraud Rings*): a grainy noir paper-collage world, one prop per beat, a new picture every 5 to 10 seconds, and a short serif caption on a cream strip that follows the narration three words at a time. **Unlike the reference, every picture is a real Inspecto LA screen** (the shipped showcase Space, synthetic data) in a torn-paper print, with the camera panning across it to the ids named in the voice. Same telling rules as [brochure-video-industry-scripts.md](brochure-video-industry-scripts.md): the industry problem first, then the data, the mapping, the investigation step by step, the conclusion; **no record counts**; the scale message (millions of records, search and filter in moments); the outcome is a lead and never a verdict.
> Rendered by `node tools/render-la-short-video.mjs <name>` into `C:\sandbox\la-video-out\<name>.mp4`.

Shot format: `### name`; `- Still: <folder>/<file>.png @S,x0,y0,x1,y1` (a square window of S pixels of the 2560 × 1440 still, its top-left panning from x0,y0 to x1,y1) or `none` for a text card; `- Text:` card lines separated by ` · `; `- Voice:` the narration. Windows are chosen to keep the graph-count panels and saved-view descriptions (which state dataset sizes) out of frame.

## Banking_MuleRing_Short

### Hook
- Still: mule-ring/01-raw-records.png @1300,120,60,120,140
- Voice: Nobody deposited more than a thousand. Nobody was reported. And yet, in three days, a small fortune left the country.

### The data
- Still: mule-ring/01-raw-records.png @1100,110,40,470,40
- Voice: Every bank holds the clues in its transfers. Who paid, who was paid, the channel, the amount and the time.

### Ingest and map
- Still: mule-ring/02-metadata-dataset.png @860,780,118,1500,118
- Voice: The product ingests them, understands every column, and maps each row as a link from one account to another.

### Scale
- Still: none
- Text: Millions of records · Answers in moments
- Voice: And it is built for millions of records. Search and filter answer in moments, because the graph only draws what you ask for.

### The tangle
- Still: mule-ring/06-graph-whole-view.png @900,780,430,470,540
- Voice: Draw the accounts and it is a tangle. Each circle is an account, each line a payment. Far too much to read by eye.

### First sweep
- Still: mule-ring/07-explore-busiest-nodes.png @720,1840,340,1840,420
- Voice: So ask the first question. Who has the most links? One account stands out: MULE-HUB-01.

### Narrow down
- Still: mule-ring/09-focus-start-from-entity.png @1000,140,330,300,330
- Voice: Start a new graph from that one account, and ask the data for every link it has.

### The reveal
- Still: mule-ring/10-reveal-entity-graph.png @1120,390,200,440,230
- Voice: Twelve accounts, SMURF-01 to SMURF-12. Each pays the hub in cash, again and again, always just under a thousand.

### The conclusion
- Still: mule-ring/10-reveal-entity-graph.png @800,600,150,300,620
- Voice: The hub wires it straight on to two relays, which move it by crypto to one offshore account. That is the route to report.

### The evidence
- Still: mule-ring/12-evidence-links-table.png @880,150,560,150,560
- Voice: And every link is a row, traceable to a record. Evidence, not a guess.

### Close
- Still: none
- Text: Inspecto LA · See who is connected. · Try it on your own data.
- Voice: Search it. Filter it. Follow the links. Inspecto LA. Try it on your own data.

## Banking_MuleRing_Live

Same story and narration as `Banking_MuleRing_Short`, but the investigation beats are a **live recording** of the product (`tools/record-la-live.mjs mule`: headless Chrome drives the showcase install with a visible cursor and the DevTools screencast captures the screen) instead of panned stills. Format for those beats: `- Live: <take> <mark>[+s] <mark>[+s]` (a segment of the take, between two of its recorded marks, stretched at most 0.75x to 1.5x to the narration) and `- Window: S,x0,y0,x1,y1` (a square window of the 1920 × 1080 video, its top-left panning from x0,y0 to x1,y1). The Graph counts panel is hidden in the take because it states the size of the data.

### Hook
- Still: mule-ring/01-raw-records.png @1300,120,60,120,140
- Voice: Nobody deposited more than a thousand. Nobody was reported. And yet, in three days, a small fortune left the country.

### The data
- Still: mule-ring/01-raw-records.png @1100,110,40,470,40
- Voice: Every bank holds the clues in its transfers. Who paid, who was paid, the channel, the amount and the time.

### Ingest and map
- Still: mule-ring/02-metadata-dataset.png @860,780,118,1500,118
- Voice: The product ingests them, understands every column, and maps each row as a link from one account to another.

### Scale
- Still: none
- Text: Millions of records · Answers in moments
- Voice: And it is built for millions of records. Search and filter answer in moments, because the graph only draws what you ask for.

### The tangle
- Live: mule tangle sweep
- Window: 800,560,260,400,230
- Voice: Draw the accounts and it is a tangle. Each circle is an account, each line a payment. Far too much to read by eye.

### First sweep
- Live: mule sweep narrow-2.2
- Window: 700,1210,120,1210,200
- Voice: So ask the first question. Who has the most links? One account stands out: MULE-HUB-01.

### Narrow down
- Live: mule narrow-2.2 reveal+0.6
- Window: 700,1210,200,1210,260
- Voice: Start a new graph from that one account, and ask the data for every link it has.

### The reveal
- Live: mule reveal+0.6 conclusion
- Window: 820,390,110,410,130
- Voice: Twelve accounts, SMURF-01 to SMURF-12. Each pays the hub in cash, again and again, always just under a thousand.

### The conclusion
- Live: mule conclusion evidence
- Window: 760,440,300,520,300
- Voice: The hub wires it straight on to two relays, which move it by crypto to one offshore account. That is the route to report.

### The evidence
- Live: mule evidence end
- Window: 800,115,300,700,300
- Voice: And every link is a row, traceable to a record. Evidence, not a guess.

### Close
- Still: none
- Text: Inspecto LA · See who is connected. · Try it on your own data.
- Voice: Search it. Filter it. Follow the links. Inspecto LA. Try it on your own data.
