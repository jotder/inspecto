---
type: Guide
title: Inspecto LA training videos — D · The analysis toolbox: narration scripts — Episodes D7-D8 — money, time and the whole Dataset
description: Part 3 of 3 of Inspecto LA training videos — D · The analysis toolbox: narration scripts: D7_Money-and-time; D8_On-screen-vs-Whole-Dataset
tags: [module, la-core, link-analysis, user-guide, training, video]
timestamp: 2026-11-16T00:00:00Z
---

# Inspecto LA training videos — D · The analysis toolbox: narration scripts — Episodes D7-D8 — money, time and the whole Dataset

Part 3 of 3 of [Inspecto LA training videos — D · The analysis toolbox: narration scripts](user-guide-training-d-analysis-toolbox.md) · previous: [Episodes D4-D6 — groups, the unusual and pattern match](user-guide-training-d-analysis-toolbox-patterns.md).

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
- Voice: Open the saved view mm structuring band, then on the Whole Dataset tab open Value Measures: find entities by how money moves through them. It reads the whole Dataset in a window of at most thirty-one days, not the filtered picture on screen. The form starts with the Dataset and the sender, receiver and kind columns filled in. Choose the Measure, then the value column and the time column, and a window: a fixed From and To, or a rolling one. Until the form is valid, Run Measure stays off.
- Chip: Whole Dataset · Value Measures
- Action: the toolbox's Whole Dataset tab ▸ Value Measures ▸ hold on the form ▸ zoom on the reason line above Run Measure. [check in UI: the prefilled Dataset and columns without an Investigation open, and the reason line's words]
- Impact: mark the reason line and the disabled Run Measure.

### S03 · Structuring
- Voice: Pick the Measure: structuring, legs just under a limit. Value column amount, time column ts. Set the leg value from four thousand seven hundred and fifty, and below five thousand: the top of the band under the limit. Set the window, from the first of October to the fifteenth, not included, and press Run Measure. The answer first says which thresholds and which window it used. Then the table of entities that match. Every deposit was legal. Together they are a pattern.
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
- Voice: This is Inspecto LA training, episode D8: on screen against the whole Dataset. A screen can draw a thousand nodes. A Dataset can hold millions of records. Every tool in Inspecto LA has to be honest about which of the two it is looking at. In this episode you will see the two tabs of the toolbox, read the limits, follow a tool past the screen's limit to the server, and meet the link index. We are in the Standard edition, in the Other Examples Space, which holds the telecom link Dataset.
- Action: intro card.
- Impact: none.

### S02 · One tab, then two
- Voice: In the Basic edition the toolbox has one tab: On screen. Every tool there runs in your browser, over the graph you have drawn, and nothing else. Here, in the Standard edition, two more tabs appear. Whole Dataset runs tools on the server, over the whole Dataset or over an open Investigation's Working Set, whatever its size. The Investigation tab is for recorded work, the F episodes. Same questions, two places to answer them.
- Chip: Toolbox · On screen · Whole Dataset
- Action: inset from `la-basic.zip`: the toolbox shows only On screen ▸ cut to Standard, signed in as Demo Analyst: the three tabs.
- Impact: mark the one tab, then the three.

### S03 · Draw more than fits
- Voice: Draw something large. From the File menu, choose Explore Dataset, and pick the telecom link Dataset. The server returns its share and says the result was cut short: never a silent partial answer. The banner says the graph reached the node cap of a thousand, and to narrow the Dataset or add a filter. Graph drawn: up to a thousand nodes; the tools on the screen take up to two thousand five hundred, and the Whole Dataset tools far more. Each is a setting, and the pages that follow show the one in force.
- Chip: File · Explore Dataset… · Limits
- Action: File ▸ Explore Dataset… ▸ Dataset `telco_ra_links_dataset` ▸ From `src` ▸ To `dst` ▸ run ▸ wait for the draw ▸ Graph counts ▸ Limits. [check in UI: the run button's label in Graph model, the cut-short wording on the canvas, and the three lines' numbers]
- Impact: mark the cut-short line, then the three Limits lines.

### S04 · Reading the chips
- Voice: Every row on the On screen tab says how big a graph it takes. Centrality says up to five hundred nodes, because its go-between score is the heaviest to work out in a browser. Cohesive groups says a thousand. A row that the server can also run carries a chip, Also on Whole Dataset. And in a Space with an Industry pack, a row the pack recommends is chipped Suggested; this Space has none.
- Chip: Up to N nodes · Also on Whole Dataset
- Action: the On screen tab ▸ zoom on the Centrality row's *Up to … nodes* and *Also on Whole Dataset* chips ▸ the Cohesive groups row. [check in UI: the chips' numbers]
- Impact: mark each chip as the voice names it.

### S05 · Past the limit
- Voice: To see what happens at the limit, lower it, for this browser only. In Settings, under My settings, My browser holds your own limits, never above the Space's. Open Browser algorithms, set the Analysis node cap to five hundred, and keep it in this browser. Back on the canvas, draw the Dataset again, open a row, and the On screen tab now says this graph has a thousand nodes, too big to answer on screen, limit five hundred, use the Whole Dataset tab. That sentence is the hand-off.
- Chip: Settings · My browser · Analysis node cap
- Action: Settings ▸ Link Analysis ▸ My browser ▸ the analysis node limit `500` ▸ save ▸ back to Link Analysis ▸ hold on the On screen tab's line. [check in UI: the item's label (`analysis_node_cap`, *Analysis node cap* in the config spec) and the exact sentence]
- Impact: mark the line *… too big to answer on screen …*.

### S06 · The hand-off, and a cut graph
- Voice: Switch to the Whole Dataset tab. The tool that cannot run on screen is listed here with the same settings, and a Run on server button. With no Investigation open it waits, and says why: Whole Dataset tools run on an open Investigation. Open or start one first. A tool is never silently skipped; it tells you what it needs.
- Chip: Run on server · Search whole Dataset
- Action: Whole Dataset tab ▸ the handed-over row ▸ Run on server ▸ hold on the reason ▸ Pattern match ▸ *Layering network (split and merge)* ▸ hold on *This graph was cut short …* ▸ Search whole Dataset. [check in UI: which rows hand over at a limit of 500; the pattern's answer line, *… in the whole Dataset* or *No matches in the whole Dataset*]
- Impact: mark the reason, then the answer line.

### S07 · Open an Investigation
- Voice: From the File menu, Open, choose an Investigation: here, the mule hub with six feeder accounts. Now the Whole Dataset tab has something to run on: the Working Set. Under each question, the server's rows are listed with a size of their own: instant up to a hundred thousand nodes, for example. Above that, a run is not refused. It is queued as a job, and the row shows it running. A row with a twin on screen is chipped Also on screen.
- Chip: File · Open… · Investigations
- Action: File ▸ Open… ▸ Investigations ▸ *SIM-swap wallet takeover* ▸ Open ▸ the Whole Dataset tab ▸ zoom on a row's *Instant up to … nodes* and *Also on screen* chips.
- Impact: mark the chips.

### S08 · A server answer, and the dock
- Voice: Open Degree centrality: rank nodes by how many links they have. Press Run on server. The answer comes back summarised in words, with the ranked lines under it: here nine nodes were scored, and one hub leads with seven. Highlight on canvas shows them where they sit. A long answer gets Open in dock, and the dock's Results tab holds the whole table, ready to sort and export. That is where long answers go, so the toolbox stays readable.
- Chip: Degree centrality · Run on server · Open in dock
- Action: Who matters most? ▸ *Degree centrality* ▸ Run on server ▸ Highlight on canvas ▸ Open in dock ▸ the dock's Results tab. [check in UI: the headline, that S-41 ranks near the top, and that the answer is long enough to offer Open in dock]
- Impact: before/after hold: the dock opens on Results; mark the headline and the table.

### S09 · Propagated risk
- Voice: Under What looks unusual, Propagated risk asks who is close to the risky ones. It spreads risk from scored nodes to the nodes linked to them, a share at each hop: Weight per hop. Risk origins limits which nodes pass risk on. Risk scores are read from the Industry pack's own data. Where there is no such data, the tool says so, once, in words.
- Chip: Propagated risk · Weight per hop · Risk origins
- Action: What looks unusual? ▸ *Propagated risk* ▸ point at Risk scores (*Use risk scores*, disabled), Risk origins, Weight per hop, Direction ▸ hold on *No risk data for this Industry pack.*.
- Impact: mark the reason.

### S10 · The link index
- Voice: Last, the link index. Searching a whole Dataset row by row gets slow as it grows. The link index is a ready-made map of who links to whom, built ahead of time, so search and filter stay fast on millions of records. Open Link index on the Whole Dataset tab: it says whether serving is on for this Space, and offers a build mode: Full rebuilds from the whole Dataset, Append adds the files that arrived since, Compact merges them. Builds are refused on a packaged install, so we only read the form. Every server read then says where it was answered: from the link index, or from the Dataset, and why.
- Chip: Link index · Full · Append · Compact
- Action: How are they connected? ▸ *Link index* ▸ hold on the serving line ▸ Build mode: Full ▸ Append ▸ Compact ▸ Build link index ▸ hold on the build's state. [check in UI: blocked on packaged installs (plan §6.4): record this scene after the packager fix; until then stop before Build link index]
- Impact: mark the serving line, then each mode as the voice names it.

### S11 · Try it
- Voice: The screen for what you can see, the server for everything else, and every limit said out loud. Before you go, set the My browser limit back to the Space value; it only ever applied to this browser. Your turn. Open an Investigation, run the Whole Dataset row Connected components, and read how many parts the Working Set falls into. The answer is in the description. Next, in E1, keeping your work: saving, opening and exporting.
- Action: Settings ▸ Link Analysis ▸ My browser ▸ Reset to Space value ▸ outro card: *Try it* and *Next: E1 · Saving, opening and exporting*. [check in UI: the *Reset to Space value* label]
- Impact: none.
