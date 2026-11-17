---
type: Guide
title: "Inspecto LA training videos — F · Investigations (Standard): narration scripts"
description: "The spoken script of the training episodes in group F (Investigations (Standard)), scene by scene, with the action on screen and the change it causes. Format and status: the overview, user-guide-training-scripts.md."
resource: tools/render-la-demo-video.mjs
tags: [module, la-core, link-analysis, user-guide, training, video]
timestamp: 2026-11-16T00:00:00Z
---

# Inspecto LA training videos — F · Investigations (Standard)

> Narration scripts for group F of the Inspecto LA training series. How to read an episode, the voice and the
> renderer: [the overview](user-guide-training-scripts.md). Draft, not recorded, not reviewed by product; `[check in UI: …]` marks a detail to
> confirm on the live product before recording.

## Episodes in F

| Episode | Title | Edition | Scenes | Spoken words | UI checks open |
|---|---|---|---|---|---|
| [F1](#f1_signing-in-roles-and-your-first-investigation) | Signing in, roles and your first Investigation | Standard | 11 | 819 | 6 |
| [F2](#f2_expand-with-intent) | Expand with intent: direction, kinds, time, strength, fan-out | Standard | 11 | 839 | 6 |
| [F3](#f3_recorded-clean-up-and-scope) | Recorded clean-up and scope | Standard | 12 | 896 | 6 |
| [F4](#f4_step-log-undo-re-order-and-replay) | The step log: replay, undo, re-order, more steps | Standard | 11 | 916 | 9 |
| [F5](#f5_teams-members-four-eyes-reviewers-and-outsiders) | Teams: members, four-eyes, reviewers and outsiders | Standard | 11 | 870 | 8 |
| [F6](#f6_drafts-work-in-parallel) | Drafts: work in parallel | Standard | 10 | 748 | 7 |
| [F7](#f7_masked-data-reveal-and-identity-resolution) | Masked data and audited reveal; resolving identities | Standard | 11 | 899 | 8 |
| [F8](#f8_evidence-coverage-dossier-and-sealed-bundle) | Evidence: coverage, Dossier and the sealed bundle | Standard | 11 | 838 | 5 |
| [F9](#f9_from-finding-to-action) | From finding to action: Alert Rules, Cases, widgets and sharing | Standard | 12 | 957 | 10 |

## F1_Signing-in-roles-and-your-first-Investigation

> **F1 · Signing in, roles and your first Investigation** · Standard · Other Examples Space · the Wangiri call-back
> number · 7 min · user guide §3, §11.1, §11.2, §14

- **Objectives:** sign in as a Demo User and say what each of the five may do; use the user menu; start an Investigation
  from one entity, work unsaved, and save it with a Name and a Purpose; read the Investigation tab's header and step bar.
  Vocabulary: **Step log** (first use).
- **Before:** a fresh unzip of `la-standard.zip`, served, the browser on the sign-in page. Other Examples Space.
- **Covers (plan §5):** 5.1 sign-in (*Local sign-in*, the five Demo Users), the user menu (Colour scheme: Match system,
  Light, Dark; Sign out), the Space switcher; 5.3 the Investigation tab, the New Investigation form (Start from, Dataset,
  Entity Type, value, Time column, Time zone, Degrees, Start), the unsaved card (*Unsaved*, Save…, Close, Steps, the step
  bar: Undo, Redo, *Unsaved steps · Ctrl+Z / Ctrl+Shift+Z*), the Save dialog (Name, Purpose, Save), the saved header
  (Name, purpose, *Data:*, the data strip, the Working Set line, How to investigate, Got it), Open the Step log.
- **Try it:** open the Investigation you saved, and open the Step log. How many steps does it hold, who took them, and
  what is each? *Answer: two: the start from 99979100001 and the one-degree expand drawn with it, both with your name;
  check the exact labels in the log.*
- **Check:** where do you type the Investigation's Name and Purpose? *Answer: in the Save dialog; the start form asks for
  neither, because you work unsaved first.*
- **Gotchas:** **the plan's Space (Telecom, SIM swap) cannot run this flow**: in every Space without an Industry pack that
  sets a neighbour limit, a start's ring keeps every neighbour, which is above the Space's four-eyes fan-out threshold
  (2,000), so the unsaved start is refused (*This start's ring needs a second person's approval, so it cannot run unsaved.
  …*). The Other Examples Space runs the Telecom pack (a 30-day window, 500 neighbours per entity), so the flow works
  there. Its pack words are *subscribers* and *calls*. The plan's row put Name and Purpose in the start form; they are in
  the Save dialog.

### S01 · Recorded work
- Voice: This is Inspecto LA training, episode F1: signing in, roles, and your first Investigation. Everything until now was exploring: nothing you did was kept unless you saved a view. An Investigation is different. Every step is recorded, in order, with who took it, so it can be replayed, reviewed and handed over. That needs to know who you are, and what you may do. We are in the Standard edition, in the Other Examples Space.
- Action: intro card.
- Impact: none.

### S02 · Signing in
- Voice: The sign-in page of a demonstration install lists Demo Users, with no password, under a warning that says it plainly: local sign-in, not secure, local only. A real install shows Sign in with single sign-on instead. There are five. Demo Admin, the platform administrator. Demo Analyst, the lead who does the work. Demo Manager, the second person who approves large expands. Demo Reviewer, who may read and check, but not change. And Demo Outsider, who is not a member at all.
- Chip: Sign in · Demo Users
- Action: the sign-in page ▸ zoom on *Local sign-in · Not secure, local only.* ▸ point at each of the five buttons with its title.
- Impact: mark each button as the voice names it.

### S03 · What the roles mean
- Voice: Roles decide what you see. The analyst may start an Investigation, expand, save, and run the server's tools. The manager may approve a large expand that someone else asked for, never their own. The reviewer opens the same Investigation read-only, with every change greyed and the reason under it. The outsider is told the Investigation is not available, the same answer whether it exists or not. The admin changes the Space's settings. When a control is greyed, hover it: it names what is missing. Episode F5 shows the team at work. Sign in as Demo Analyst.
- Chip: Demo Analyst
- Action: press *Demo Analyst* ▸ wait for the front page.
- Impact: mark the analyst's button as pressed.

### S04 · The user menu
- Voice: At the foot of the menu on the left, the person icon opens the user menu. It says who you are signed in as. Colour scheme follows your system, or fixes light or dark, remembered for you in this browser. Sign out ends the session. Above it, the Space switcher shows where you are: Other Examples. Open Link Analysis.
- Chip: User menu · Colour scheme · Sign out
- Action: the person icon ▸ hold on *Signed in as* ▸ point at Colour scheme (Match system, Light, Dark) and Sign out ▸ close ▸ point at the Space switcher ▸ Link Analysis. [check in UI: *Signed in as* shows for a Demo User session]
- Impact: mark the menu's three parts.

### S05 · The Investigation tab
- Voice: The Standard edition gives the toolbox a third tab: Investigation. With nothing open it is the New Investigation form, and its first line sets the rule: start, then expand; nothing is recorded until you save, and save asks for a name and a purpose. Start from: one entity. Dataset: the telecom links. The line under it says what data arrived and the period it covers.
- Chip: Investigation · New Investigation
- Action: the toolbox's Investigation tab ▸ hold on the form's first line ▸ Start from *One entity* ▸ Dataset `telecom_links_dataset` ▸ hold on the data line. [check in UI: the Dataset's name as the picker shows it]
- Impact: mark the first line, then the data line.

### S06 · Start from one number
- Voice: Entity Type: MSISDN, a phone number. Type the suspect number from four missed-call reports, 99979100001, in full: this Entity Type is masked, so there are no suggestions. Time column stays blank, which means the Dataset's own; it is what time windows and the coverage check will read. Time zone is for a column that has none. Degrees: one, the number and everything linked to it directly. Press Start.
- Chip: Entity Type · Time column · Degrees · Start
- Action: Entity Type *MSISDN* ▸ value `99979100001` ▸ pick the suggestion ▸ Time column blank ▸ Time zone as offered ▸ Degrees `1` ▸ Start. [check in UI: the Entity Type's label and the suggestion line]
- Impact: before/after hold: the empty canvas → the number and its ring.

### S07 · Unsaved
- Voice: The number appears with everything it touched in the last thirty days, the window this Industry pack reads: four hundred numbers it rang for a few seconds at most, a second SIM in the same handset, and a premium-rate number its calls are forwarded to. The Investigation tab now says Unsaved, beside Save and a close button. Nothing is on the server yet. Above the Expand section is the step bar: Undo and Redo, and a reminder that these are unsaved steps.
- Chip: Unsaved · step bar
- Action: zoom on the *Unsaved* marker, Save…, the close button ▸ zoom on the step bar *Unsaved steps · Ctrl+Z / Ctrl+Shift+Z*. [check in UI: the ring's size and that 99979100002 and 99891000001 are in it]
- Impact: mark 99979100002 and 99891000001 on the canvas, then the marker.

### S08 · Steps so far
- Voice: Open the dock's Steps tab. It lists what you have done, in order: the start, and the one degree drawn with it. You can expand, undo and redo here as much as you like; the canvas follows. Keep, hide, exclude and notes change a recorded Working Set, so the tab asks you to save first for those. Unsaved work lives in this browser tab only: close it, and it asks before anything is lost.
- Chip: Dock · Steps
- Action: the dock's Steps tab ▸ hold on the two lines ▸ point at the line *Keep, Hide, Exclude, Annotate … Save first.*. [check in UI: the two step lines' wording]
- Impact: mark the two steps.

### S09 · Save, with a purpose
- Voice: Press Save. The dialog says it will create the Investigation and record the steps taken so far, in order, with your name. Name and Purpose are filled from the start, the number and a review of its links. Make the purpose the real reason: missed-call bursts from one number, who called back, and where the calls went. The purpose is shown in the Dossier; it is your stated reason for looking. Press Save.
- Chip: Save… · Name · Purpose
- Action: Save… ▸ hold on the first line ▸ Name as filled ▸ Purpose `Missed-call bursts from one number: who called back, and where the calls went` ▸ Save. [check in UI: the pre-filled Name and Purpose]
- Impact: mark the dialog's first line, then the Purpose field.

### S10 · A saved Investigation
- Voice: The tab changes. At the top, the Name, the purpose, and the data in words: which Dataset, and how it links. Under them, the data strip, and one counts line: the Working Set, how many entities and links, and how far from the start. How to investigate lists five steps, until you press Got it. The step bar now says recorded steps. Press Open the Step log: the dock shows every step, who took it, and when. That ordered record is the step log.
- Chip: Working Set · Step log
- Action: zoom on the header ▸ the Working Set line ▸ How to investigate ▸ Got it ▸ Open the Step log.
- Impact: mark the Working Set line, then the Step log's rows with your name.

### S11 · Try it
- Voice: Sign in, start from one entity, work unsaved, and save with a reason. Your turn. Sign out, sign in as Demo Analyst again, and open the Investigation you saved from File, Open. Open its Step log. How many steps does it hold, who took them, and what is each? The answer is in the description. Next, in F2, expand with intent: direction, link kinds, time, strength and fan-out.
- Action: user menu ▸ Sign out ▸ outro card: *Try it* and *Next: F2 · Expand with intent*.
- Impact: none.

## F2_Expand-with-intent

> **F2 · Expand with intent: direction, kinds, time, strength, fan-out** · Standard · Other Examples Space · the Wangiri
> call-back number · 7 min · user guide §7.1, §7.2, §11.4

- **Objectives:** choose where an Expand starts and read its preview before it runs; narrow it by direction, link kind,
  time and strength; grow or shrink an Expand by its degrees; set the fan-out and busy limits, and recognise when an
  Expand would need a second person.
- **Before:** a fresh unzip of `la-standard.zip`, the Other Examples Space, signed in as Demo Analyst, the Investigation
  saved in F1 open (started from 99979100001, one degree, Telecom pack defaults), the toolbox on its **Investigation**
  tab at the Expand section.
- **Covers (plan §5):** 5.3 the Expand section: From (The selection, The outer ring (next degree), The start entities,
  The whole Working Set) and its line, Expand with its preview line, Expand filter (*N changed*, Defaults, Reset to
  defaults, *Every expand uses it…*), Direction (Both ways, Outgoing, Incoming, Reciprocal), Depth (Degrees, the
  *Degrees follows the last Expand* line, *Stops early…*), Link kinds, Time (Investigation window, All data, 7 days, 30
  days, 90 days, Custom), Strength (Minimum events per link, Minimum distinct days), Fan-out and busy (Hold busy entities,
  Busy limit, Most neighbours per entity, Fewest links a neighbour may have, Most links a neighbour may have, Row limit
  per degree, the four-eyes line), the per-degree result lines, Undo.
- **Try it:** select one of the numbers the suspect rang, set Direction to Reciprocal, and Expand. Who comes back, and
  why are they not part of the scheme? *Answer: a few numbers from its own calling circle, friends it calls and who call
  it; ordinary two-way relationships. The suspect never comes back: nobody calls it directly, the call-backs are
  forwarded to the premium number.*
- **Check:** you set Most neighbours per entity to 3,000 and the form shows a line in warning colour. What does it mean?
  *Answer: this Space asks a second person to approve an Expand that keeps more than 2,000 neighbours of each entity; the
  Expand would wait for approval at its first degree (F5).*
- **Gotchas:** in an Investigation, **G**, a double-click and the right-click menu all expand at once with this filter.
  Planted facts from `tools/gen-la-telecom-data.py`: the suspect rang 400 numbers for 0-3 s between days 48 and 58 of
  the 60; 40 called back and were forwarded to 99891000001; four IRSF lines pump 70-90 long night calls each into the
  premium range; five subscription-fraud lines share IRSF-01's identity document, and one shares a card with the first SIM
  box line; the second SIM 99979100002 rang 60 numbers between days 20 and 30, before the 30-day window; the taxi line
  99970000100 is called by 650 numbers and is held as busy (limit 500). The plan's Space was Telecom (dealer commission):
  see F1's Gotchas.

### S01 · Every expand is a decision
- Voice: This is Inspecto LA training, episode F2: expand with intent. In an Investigation, every expand is a recorded step, and a reviewer will ask why you took it. Why that direction? Why those links? Why that time? The Expand section answers each of those with one fold, and shows you what a step will add before you take it. We are in the Standard edition, in the Other Examples Space, in the Investigation from episode F1.
- Action: intro card.
- Impact: none.

### S02 · One form, every expand
- Voice: The Expand section has three parts. From says where the expand starts: the selection, the outer ring, the start entities, or the whole Working Set, one press each, with a line saying what that is. Then the Expand button, with a preview line under it that says what the step would add before anything changes. Then the Expand filter, in folds. Every expand uses it: this button, a double-click, the right-click menu, and the G key.
- Chip: Expand · From · Expand filter
- Action: the Investigation tab ▸ Expand section ▸ zoom on From's four choices and the line under them ▸ the preview line ▸ the folds and the line *Every expand uses it…*.
- Impact: mark each part as the voice names it.

### S03 · Link kinds and strength
- Voice: Select the premium-rate number 99891000001. From switches to the selection. Open Link kinds: one chip per kind, pressed means followed. Keep only voice calls. Set Direction to Incoming: who calls it? The preview counts everyone, including the forty victims' one-off call-backs. Now open Strength, and set the minimum events per link to three. The preview shrinks to the lines that call it again and again. Press Expand. Two numbers arrive, pumping long night calls into the premium range.
- Chip: Link kinds · Strength · Minimum events per link
- Action: click `99891000001` ▸ Link kinds: only `voice` ▸ Direction: Incoming ▸ hold on the preview ▸ Strength ▸ Minimum events per link `3` ▸ hold on the preview ▸ Expand. [check in UI: the two previews' counts, the link kind chip names, and that 99979200001 and 99979200002 arrive]
- Impact: before/after hold of the preview line; mark the two IRSF numbers.

### S04 · Reciprocal
- Voice: Direction has a fourth choice, only in an Investigation: Reciprocal, only pairs linked in both directions. Select the start number, and choose it. The preview says this step adds nothing new. Nobody the suspect rang ever rang it back directly; their call-backs were forwarded elsewhere. Mutual contact is how a relationship looks. A one-way burst is how a campaign looks. Set Direction back to Both ways.
- Chip: Direction · Reciprocal
- Action: click `99979100001` ▸ Direction: Reciprocal ▸ hold on the preview ▸ Direction: Both ways. [check in UI: the preview reads *This step adds nothing new*; reword if a reciprocal pair exists]
- Impact: mark the preview line.

### S05 · Degrees
- Voice: Select 99979200001, the first of the two pumping lines. Link kinds: shared identity, and shared payment. Open Depth, and set Degrees to two. Above two, a line warns that it stops early if a degree would expand from more than two thousand numbers. Press Expand two degrees. First, five lines registered on the same identity document. Then, from one of them, a SIM box line topped up with the same card. One identity, five lines, and a gateway.
- Chip: Depth · Degrees
- Action: click `99979200001` ▸ Link kinds: `shared_identity`, `shared_payment` ▸ Direction: Both ways ▸ Depth ▸ Degrees `2` ▸ Expand 2 degrees. [check in UI: 99979300001…05 at degree 1 and 99979400001 at degree 2; both links fall inside the 30-day window]
- Impact: before/after hold: mark the five subscription-fraud lines, then the SIM box line.

### S06 · Shrink an Expand, and grow it again
- Voice: While the newest step is an Expand, Degrees is that Expand's size, and a line says so. Lower it to one. The outermost degree leaves the canvas, and the record keeps that you took it back. Raise it to two again, and it grows back from the outer ring. The first degree always stays. This is how you try a wider step without losing your place.
- Chip: Degrees follows the last Expand
- Action: zoom on *Degrees follows the last Expand …* ▸ Degrees `1` ▸ wait ▸ Degrees `2` ▸ wait. [check in UI: the SIM box line leaves and returns; and how the Step log shows the lowered degree]
- Impact: before/after hold: 99979400001 leaves, then returns.

### S07 · Time
- Voice: This Investigation reads the last thirty days, counted back from the Dataset's latest event, never from today. Select the second SIM, 99979100002. Link kinds: voice. Direction: Outgoing. The preview says nothing new: in this window it rang no one. Open Time. The chips are: the Investigation window, all data, the last seven, thirty or ninety days, and a custom window for this Expand only. Choose all data. Now the preview counts sixty numbers it rang in late August. Press Expand. Same handset, same play, a month earlier.
- Chip: Time · Investigation window · All data
- Action: click `99979100002` ▸ Link kinds: only `voice` ▸ Direction: Outgoing ▸ hold on the preview ▸ Time ▸ All data ▸ hold on the preview ▸ Expand. [check in UI: the first preview finds nothing new or very few, the second about 60]
- Impact: before/after hold of the preview; mark the new ring of numbers.

### S08 · Fan-out and busy
- Voice: The last fold limits the size of a step. Hold busy entities is on: an entity with more than five hundred linked is shown, but not expanded through. Most neighbours per entity keeps the strongest few from each. Fewest and most links a neighbour may have screen out the lonely and the crowded. Row limit per degree stops a step at that many rows and marks it incomplete. Type three thousand neighbours per entity: a line warns that each degree would need a second person's approval. Clear it again.
- Chip: Fan-out and busy · Most neighbours per entity · Row limit per degree
- Action: Fan-out and busy ▸ zoom on *Hold busy entities (more than 500 linked)*, Busy limit, Most neighbours per entity, Fewest links a neighbour may have, Most links a neighbour may have, Row limit per degree ▸ Most neighbours per entity `3000` ▸ hold on *Each degree will need approval from a second person: …* ▸ clear the field.
- Impact: mark the warning line.

### S09 · A wide step, held at the hub
- Voice: Now a wide step. From: the outer ring. Link kinds: voice. Direction: both ways. The preview says how many numbers and calls it adds, and that one busy number will be held. Press Expand. Hundreds of numbers arrive, the circles the victims call every day, and the graph is laid out again. The taxi line, called by six hundred and fifty numbers, sits with a ring and a held badge. The line above the form says one busy entity is held, and how to go through it if you must.
- Chip: The outer ring (next degree) · held
- Action: From: The outer ring (next degree) ▸ Link kinds: only `voice` ▸ Direction: Both ways ▸ hold on the preview ▸ Expand ▸ zoom on 99970000100 and the line *1 busy entity is held. …*. [check in UI: the preview's numbers and that the taxi line is the one held]
- Impact: before/after hold: the wide ring arrives; mark the held taxi line.

### S10 · Undo, and reset
- Voice: That step answered nothing about the scheme. Press Control Z. One undo takes back the whole Expand, and the step log records the undo. Then look at the filter's title: it says how many groups you changed from the defaults, and each changed fold carries a dot. Reset to defaults puts them all back. The filter stays as you leave it while this Investigation is open, so check it before your next step.
- Chip: Undo · Ctrl+Z · Reset to defaults
- Action: Ctrl+Z ▸ hold on the result line ▸ zoom on *… changed* ▸ Reset to defaults ▸ hold on *Defaults*.
- Impact: before/after hold: the wide ring leaves; mark *Defaults*.

### S11 · Try it
- Voice: Where from, which way, which links, which days, how strong, how wide: six choices, each one you can defend. Your turn. Select one of the numbers the suspect rang, set Direction to Reciprocal, and Expand. Who comes back, and why are they not part of the scheme? The answer is in the description. Next, in F3, recorded clean-up and scope.
- Action: outro card: *Try it* and *Next: F3 · Recorded clean-up and scope*.
- Impact: none.

## F3_Recorded-clean-up-and-scope

> **F3 · Recorded clean-up and scope** · Standard · Utilities Space · the meter tampering · 7 min · user guide §7.4, §7.5,
> §11.2

- **Objectives:** clean a Working Set up in one press with a recorded reason; tell hide, keep and exclude apart; leave an
  entity out before any expand can reach it, and let an expand go through a busy one; hide what has no event in a time
  window; note an entity.
- **Before:** a fresh unzip of `la-standard.zip`, the Utilities Space, signed in as Demo Analyst, File ▸ Open… ▸
  Investigations ▸ *Meter tampering after one contractor's visits* (seeded by `tools/seed-la-utility.mjs`: started from
  C-14, TX-007 and TX-019; one expand of up to 40 neighbours each; a note, a threshold, a window, a resolve and a snapshot),
  the toolbox on its **Investigation** tab.
- **Covers (plan §5):** 5.3 the Clean up fold (Hide N dangling leaves, Hide N with fewer than N events and its Events
  stepper, the per-Entity-Type buttons, Keep paths between the 2 selected, Keep only the N selected, Exclude instead of
  hide, Outside a time window: 7 / 30 / 90 days, From, To, Find what is outside the window, Hide N with no link event in
  the window), Keep (K), Hide (H), Exclude… with a reason (X), Selected entity (Note, Annotate, the notes list), Busy
  entities (Expand anyway), Scope list (Entity ids, Include, Leave out, Only these); 5.6 K, H, X.
- **Try it:** on a fresh unzip, open this Investigation and press only the dangling-leaves clean-up. How many premises
  stay, and why one more than twenty-two? *Answer: 23: every premise C-14 visited under TX-007 or TX-019. Twenty-two of
  them read 40-70 % less within two weeks of the visit; PRM-0737 was visited too, and its reads did not drop.*
- **Check:** hide or exclude: which keeps an entity out of every later expand? *Answer: exclude. A hide only takes it off
  the view; an exclude takes it out of the Working Set with its links, and later expands do not admit it.*
- **Gotchas:** counts from `spaces/_templates/la-utility/data`: C-14 visited 36 premises, 23 of them under TX-007 or
  TX-019 (each feeds 25); so the seeded expand holds about 66 entities and 40 dangling leaves; the other contractors on the
  23 are C-22 (3), C-01, C-07, C-26 (2 each), C-16, C-21, C-24, C-27, C-29, C-30 (1 each); C-27 links to 157 premises,
  C-21 to 90. This Space sets no neighbour limit, so an Expand needs *Most neighbours per entity* set (S07), or it waits
  for a second person (F5). The seeded Investigation also holds a pending expand and a Draft: say nothing about them here
  (F4, F5, F6). S07 sets the Busy limit to 50 for one Expand, and says so.

### S01 · Clean-up you can defend
- Voice: This is Inspecto LA training, episode F3: recorded clean-up and scope. Every graph needs clearing: leaves that lead nowhere, entities that are not part of the story. In an Investigation, each of those choices is a step with a reason, because a reviewer will ask what you removed, and why. In this episode you will clean up in one press, keep what proves the point, exclude what does not, and decide in advance what no expand may bring in. We are in the Standard edition, in the Utilities Space.
- Action: intro card.
- Impact: none.

### S02 · The contractor and two transformers
- Voice: Open the Investigation Meter tampering after one contractor's visits. Twenty-two electricity meters under two transformers started reading low, while the transformers' supply stayed flat. The analyst started from the contractor C-14 and the two transformers, and expanded one degree. The counts line says what that gave: the three start entities, and every premise linked to any of them. Most of those are noise for this question.
- Chip: File · Open… · Investigations
- Action: File ▸ Open… ▸ Investigations ▸ *Meter tampering after one contractor's visits* ▸ Open ▸ zoom on the Working Set line. [check in UI: the Working Set line, expected about 66 entities]
- Impact: mark C-14, TX-007 and TX-019.

### S03 · Hide dangling leaves
- Voice: Open the Clean up fold. Each button does one thing to the whole Working Set, with the count on it, and is one recorded step with its reason. Start entities and kept entities are never taken away. The first hides dangling leaves: entities with one link. Here, those are premises C-14 visited elsewhere, and premises the transformers feed that C-14 never visited. Press it. What stays is every premise linked to both the contractor and a transformer: twenty-three.
- Chip: Clean up · Hide N dangling leaves
- Action: Clean up ▸ hold on the button's count ▸ press *Hide … dangling leaves* ▸ hold on the result line. [check in UI: the button reads *Hide 40 dangling leaves* and 23 premises stay]
- Impact: before/after hold: 40 premises leave; mark the 23 that stay.

### S04 · The other clean-ups
- Voice: The other buttons work the same way. Hide those with fewer than a number of events, set by the stepper beside it. One button per Entity Type, when there are several. With two entities selected, keep the paths between them; with some selected, keep only those. And the box above them all: exclude instead of hide. Hidden entities stay in the Working Set, out of view. Excluded ones leave it, with their links, and later expands do not bring them back.
- Chip: Clean up · Exclude instead of hide
- Action: point at *Hide … with fewer than … events* and its Events stepper, the per-type buttons, then select two entities to show *Keep paths between the 2 selected* and *Keep only the 2 selected* ▸ clear the selection ▸ point at *Exclude instead of hide*. [check in UI: whether per-type buttons show on this Working Set]
- Impact: mark each button as the voice names it.

### S05 · Keep, and a note
- Voice: Some entities prove the point. Select C-14 and the two transformers, and press K. Keep is one step, and from now on a clean-up or an exclusion can never take them away. Now click C-14 alone. The Selected entity section shows who it is, and the note recorded earlier, with its step number. Type a new note: three of these visits were joint with the contractor C-22. Press Annotate. A note changes nothing on the graph; it is part of the record.
- Chip: Keep · K · Selected entity · Annotate
- Action: Shift+click `C-14`, `TX-007`, `TX-019` ▸ K ▸ click `C-14` ▸ Selected entity ▸ hold on the notes list ▸ Note `Three of these visits were joint with C-22` ▸ Annotate.
- Impact: mark the keep step's result line, then the new note under Selected entity.

### S06 · Leave out, before it arrives
- Voice: Next we will look for the other contractors who visited these premises. One of them, C-27, visits more premises than anyone in the city: it simply does the most work. Decide now that it is not part of this question. Open More steps. In the Scope list, type C-27, and press Leave out. That is one exclude step, with the reason left out of the investigated set, and it works before the entity is even in the Working Set.
- Chip: More steps · Scope list · Leave out
- Action: More steps ▸ Scope list ▸ Entity ids `C-27` ▸ Leave out ▸ hold on the result line.
- Impact: mark the result line.

### S07 · Expand, and a busy entity
- Voice: Select the twenty-three premises. In the Expand filter, follow only the visited by links, and set Most neighbours per entity to twenty: without a limit, this Space would ask a second person to approve. For this lesson only, set the Busy limit to fifty. Press Expand. Nine contractors arrive, C-22 among them, and C-27 does not. One, C-21, links to ninety premises: it has a ring, a held badge, and a Busy entities fold of its own.
- Chip: Expand · Busy limit · Most neighbours per entity
- Action: right-click `C-14` ▸ Select neighbours ▸ Expand filter: Link kinds only `visited_by` ▸ Fan-out and busy ▸ Most neighbours per entity `20` ▸ Busy limit `50` ▸ Expand. [check in UI: nine contractors arrive, C-27 absent, C-21 held]
- Impact: before/after hold: mark the nine contractors and C-21's held badge.

### S08 · Busy entities, and Include
- Voice: Open Busy entities. Each is shown, but not expanded, with how many it links to against the limit, and Expand anyway, an override you confirm and that is recorded. There is a second way. In the Scope list, Include names entities every later expand goes through, busy or not, and Only these limits expands to the list. Each step records what it used. We do not need C-21's ninety premises, so leave it held.
- Chip: Busy entities · Expand anyway · Include
- Action: Busy entities ▸ zoom on C-21's row and Expand anyway ▸ More steps ▸ Scope list ▸ point at Include and, after it, *Only these (an expand admits nothing outside the list)*. [check in UI: C-21's badge reads *… 90 … (limit 50) …*]
- Impact: mark the busy row, then Include.

### S09 · Hide or exclude
- Voice: Select C-30, which visited one of these premises once, and press H. Hide takes it off the view, recorded, and it stays in the Working Set. Now select C-21 and press X. The Exclude dialog: from this Investigation only, one recorded step with your reason, or add to a list, so it stays out wherever that list is used. Recent reasons are one press away. Type: busy across the city, one visit to these premises. Press Exclude. C-21 leaves, with its links.
- Chip: Hide · H · Exclude… · X
- Action: click `C-30` ▸ H ▸ click `C-21` ▸ X ▸ *From this Investigation only* ▸ Reason `Busy across the city; one visit to these premises` ▸ Exclude.
- Impact: before/after hold twice: C-30 hides; C-21 leaves with its links.

### S10 · Outside a time window
- Voice: The last clean-up reads time. The tampering visits fell between December and June. In Outside a time window, set From the first of December and To the first of July. Press Find what is outside the window. It reads the Dataset now, and finds the entities none of whose links has an event in that window. The button below says how many. Press it: one hide, and its reason names the window. Kept entities stay, whatever the window says.
- Chip: Outside a time window · Find what is outside the window
- Action: Clean up ▸ Outside a time window ▸ From `2025-12-01T00:00Z` ▸ To `2026-07-01T00:00Z` ▸ Find what is outside the window ▸ press *Hide … with no link event in the window*. [check in UI: what the count is, and who leaves; if nothing, the voice says every remaining entity had a link in the window]
- Impact: mark the count, then the entities that leave.

### S11 · Read it back
- Voice: Open the Step log. Every choice of this episode is there, in order, with your name: the clean-up and its reason, the keep, the note, the left-out contractor, the expand, the hide, the exclude and its reason, and the window. Anyone who opens this Investigation can see what you removed, and why.
- Chip: Step log
- Action: Open the Step log ▸ scroll the new rows.
- Impact: mark each new row as the voice names it.

### S12 · Try it
- Voice: Hide, keep, exclude, leave out, include: each one a recorded step with a reason. Your turn. On a fresh unzip, open this Investigation and press only the dangling-leaves clean-up. How many premises stay, and why one more than twenty-two? The answer is in the description. Next, in F4, the step log: replay, undo, re-order, and more steps.
- Action: outro card: *Try it* and *Next: F4 · The step log*.
- Impact: none.

## F4_Step-log-undo-re-order-and-replay

> **F4 · The step log: replay, undo, re-order, more steps** · Standard · Banking and finance Space · the sanctioned owner ·
> 7 min · user guide §11.2, §11.3, §11.5

- **Objectives:** read a step log row by row; take the steps of the More steps fold (Connect, Time window, Snapshot marker,
  Threshold, Compare) and say what each records; take a step back with Undo and say why nothing is erased; check a log
  with Replay and drift; try another order as a fork.
- **Before:** a fresh unzip of `la-standard.zip`, the Banking and finance Space, signed in as Demo Analyst, the seeded
  Investigation *Sanctions exposure two ownership hops down* (`tools/seed-la-ownership.mjs`: Start from SL-0137 and
  CUS-00528, a bounded Expand, a note, a time window from 2016, a snapshot marker *showcase baseline*; the seed also leaves
  a held expand and a Draft, which this episode does not touch).
- **Covers (plan §5):** 5.2h Step log (Undo, Re-order steps…, the fork); 5.3 More steps (Connect start entities, Time
  window, Snapshot marker, Threshold, Compare), Replay and drift (Compare with current data, Replay), the Lineage line
  and Open the parent.
- **Try it:** in the same Investigation, Connect the list entry SL-0137 and the customer CUS-00528. What does the
  Connection column say, and why? *Answer: Not connected within 3 hops. The route has four links (list entry, matched
  person, the 50 % owner, the customer's company, the customer), and Connect searches three hops by default.*
- **Check:** does Undo delete a step from the log? *Answer: no. Undo is recorded as a step of its own; the step it takes
  back stays in the log, struck through, with "undone by step N".*
- **Gotchas:** Connect, Snapshot marker and Compare are steps that read or mark; only Time window and Threshold change
  what later steps admit. Re-order is off on a Draft and with fewer than two steps. Every step number below is the
  expected one on a fresh unzip; the Action lines say where to confirm it.

### S01 · The record of the work
- Voice: This is Inspecto LA training, episode F4: the step log. In episode F1 you met the step log: every step of an Investigation, in order, with who took it and why. In this episode you will read one closely, add steps from the More steps fold, take one back, check that the log still gives the same answer, and try the steps in another order without touching the original. We are in the Standard edition, in the Banking and finance Space, signed in as the analyst.
- Action: intro card.
- Impact: none.

### S02 · Five steps, in order
- Voice: From the File menu, Open, choose the Investigation Sanctions exposure two ownership hops down. A bank customer, CUS-00528, sits below a person whose name nearly matches an entry on a sanctions list, SL-0137. Open the Step log in the dock. Five rows: the start from the list entry and the customer, one expand, a note, a time window, and a snapshot marker. The expand brought in the matched person and the customer's company. Look closely, and something is missing: the company between them, the one that owns half of the customer's company, is not on the graph yet.
- Chip: File · Open… · Step log
- Action: File ▸ Open… ▸ Investigations ▸ *Sanctions exposure two ownership hops down* ▸ dock ▸ Step log. [check in UI: five rows named Start, Expand, Annotation, Time window, Snapshot; the canvas holds SL-0137, CUS-00528, PER-03904 and CO-04313, not CO-04431]
- Impact: mark the five rows; mark the gap between PER-03904 and CO-04313 on the canvas.

### S03 · What a row tells you
- Voice: Each row says the same things, in the same place. Its number, which never changes. The kind of step, in words. What it did, in a sentence the server wrote when it was recorded. A reason, when the step needed one. Who took it, and when. A step that read data also says how it was read. Nobody, not even an administrator, can edit a row afterwards. That is what makes the log a record you can defend.
- Chip: Step log · one row
- Action: zoom on row 2 (Expand): number, name, sentence, *by analyst*, time; then on row 3's note. [check in UI: whether row 2 shows a read line such as the link index note]
- Impact: mark each part of the row as the voice names it.

### S04 · More steps: connect two entities
- Voice: The Investigation tab has a fold called More steps, for the steps you take less often than expand and clean up. Open it. The first group is Connect. Connect start entities asks: how are these entities linked, within a few hops? Paste the matched person, PER-03904, and the customer, CUS-00528, and press Connect start entities. The answer is a table: one pair, connected in three hops, with an example route. Click the row, and the route lights up on the canvas. Connect is a recorded step: the log now says what was asked and what came back.
- Chip: More steps · Connect start entities
- Action: Investigation tab ▸ More steps ▸ Connect start entities ▸ paste `PER-03904, CUS-00528` ▸ Connect start entities ▸ click the row. [check in UI: *1 of 1 pairs connected within 3 hops*; Connection *3 hops · 1 route*; Example route PER-03904 → CO-04431 → CO-04313 → CUS-00528]
- Impact: mark the summary line and the route on the canvas; mark the new row in the Step log.

### S05 · Expand from the route
- Voice: The route runs through CO-04431, the company that is not on the graph. Under the table, press Expand from these nodes. The missing company arrives, and with it the chain reads end to end: the person matched to the list owns sixty percent of CO-04431, which owns half of the customer's company. The customer is two ownership hops below the match. Another row joins the log.
- Chip: Expand from these nodes
- Action: Expand from these nodes ▸ hold. [check in UI: the button reads *Expand from these nodes (4)*; that CO-04431 arrives; that the expand runs at once and is not held for approval]
- Impact: before/after hold: CO-04431 appears between PER-03904 and CO-04313; mark it and the new log row.

### S06 · Undo is a step too
- Voice: Suppose that expand was a mistake. In the Step log, press Undo. CO-04431 leaves the canvas, and look at the log: nothing was deleted. A new row says Undo, and the expand above it is struck through, with undone by step, and the number. Taking a step back is itself recorded, because a reviewer must see what you tried, not only what you kept. Redo, Control Shift Z, records the expand again. Press it, and the company is back.
- Chip: Undo · Ctrl+Z · Redo · Ctrl+Shift+Z
- Action: dock ▸ Step log ▸ Undo ▸ hold ▸ Ctrl+Shift+Z ▸ hold. [check in UI: the undone row reads *undone by step N*, struck through; Redo records a new row]
- Impact: before/after hold: CO-04431 leaves, then returns; mark the struck-through row and the Undo row.

### S07 · Time: a window and a marker
- Voice: The second group is Time. Time window sets the range of time that later expands read: from, to, a slot of the day, days of the week, and dates to leave out, such as holidays. This Investigation already has one, from 2016, the year of the first stake on the chain. Snapshot marker marks a place in the log, and changes nothing else. Give it a label, owner in, and press Mark this position. Later, a Dossier can be built at exactly this point.
- Chip: More steps · Time window · Snapshot marker
- Action: More steps ▸ Time window (open, point at From, To, Slot start, Days, Excluded dates (holidays), Timezone, All time (clear the window); do not apply) ▸ Snapshot marker ▸ Label (optional) `owner in` ▸ Mark this position.
- Impact: mark the window fields as named; then the new Snapshot row in the log; the canvas does not change.

### S08 · Compare: a threshold and two windows
- Voice: The third group is Compare. Threshold keeps only the entities whose degree, links or events fall inside a band you set, a minimum, a maximum, or both. Use it to set aside the barely linked, or the busiest. Compare asks a different question: what changed between two windows of time? Set Window A to the years before 2025 and Window B to the last year, and press Compare and seal. The difference is sealed as a step, so it is part of the evidence, not a remark.
- Chip: More steps · Threshold · Compare
- Action: More steps ▸ Threshold (open: Measure, Minimum, Maximum, Apply threshold; do not apply) ▸ Compare ▸ Window A 2016-01-01 to 2025-01-01, Window B 2025-10-01 to 2026-10-01 ▸ Compare and seal. [check in UI: the field names in Window A and B, and what the result shows for these windows; reword the last sentence to it]
- Impact: mark the Threshold fields; then the Compare result and its log row.

### S09 · Replay and drift
- Voice: A log is only worth something if it still gives the same answer. Open the Settings gear, Investigation settings, Replay and drift. Press Replay. Replay matches: every step gives the same result. Now tick Compare with current data, and Replay again. Each expand is read again from today's data, and a table compares the rows sealed then with the rows now. Same means nothing moved. Changed would be drift: the data changed since, but the Working Set shown stays the sealed one. A reviewer can do all of this without changing anything.
- Chip: Replay and drift · Replay
- Action: Settings ▸ Investigation settings ▸ Replay and drift ▸ Replay ▸ hold ▸ tick Compare with current data ▸ Replay ▸ hold on the drift table. [check in UI: *Replay matches* and the closing sentence *Every sealed read still matches current data.*]
- Impact: mark *Replay matches*; then the drift table's Drift column.

### S10 · Re-order, as a fork
- Voice: Would the expand have found the same people if the time window had come first? Do not rewrite the record to find out. In the Step log, press Re-order steps. The box says it plainly: re-ordering creates a fork. Move the time window up above the expand, and press Create fork. A new Investigation opens, its steps re-applied in the new order, every expand reading the Dataset again. The line at the top says whose fork it is, and that the parent is unchanged. Open the parent to go back.
- Chip: Re-order steps… · Create fork
- Action: Step log ▸ Re-order steps… ▸ *Move step 4 up* twice ▸ Create fork ▸ hold on the Lineage line ▸ Open the parent. [check in UI: the Lineage line *Fork of sanctions-exposure: its steps re-applied in the order …*]
- Impact: mark the alert *Re-ordering creates a fork*; then the Lineage line in the new Investigation.

### S11 · Try it
- Voice: So: every row is permanent, Undo is a step, Replay proves the log, and a new order is a fork. Your turn. In the same Investigation, Connect the list entry SL-0137 and the customer CUS-00528. What does the Connection column say, and why? The answer is in the description. Next, in F5, teams: members, four-eyes approval, reviewers and outsiders.
- Action: outro card: *Try it* and *Next: F5 · Teams*. [check in UI: the Try it answer reads *Not connected within 3 hops*]
- Impact: none.

## F5_Teams-members-four-eyes-reviewers-and-outsiders

> **F5 · Teams: members, four-eyes, reviewers and outsiders** · Standard · Telecom Space · the SIM-swap takeover · 7 min ·
> user guide §11.4, §11.5, §14

- **Objectives:** grant and revoke a member and say what each role may do; recognise an expand held for a second person
  and approve or deny it as that person; read an Investigation as a reviewer; say what an outsider sees and why.
- **Before:** a fresh unzip of `la-standard.zip`, the Telecom Space, the seeded Investigation *SIM-swap wallet takeover*
  (`tools/seed-la-mfs.mjs`: members analyst and admin as leads, reviewer and manager as reviewers; one unbounded expand by
  the analyst, held for a second person). Sign in as Demo Analyst first; the episode signs in as Demo Manager, Demo
  Reviewer and Demo Outsider in turn (user menu ▸ sign out, off camera between scenes).
- **Covers (plan §5):** 5.1 user menu, sign-in as each Demo User; 5.3 Members (Grant, Revoke, roles lead / analyst /
  reviewer), the held line, *N to approve*, Approve, Deny (Reason, Deny request), Oversight and approvals; Read-only
  banner; 5.5 Settings ▸ Investigation access.
- **Try it:** sign in as Demo Reviewer and open *SIM-swap wallet takeover*. Name two things you can still do, and the
  sentence that says why Expand is missing. *Answer: read it, Replay it, build and verify a Dossier; the Read-only banner
  says Changing an Investigation needs the Manage Incidents and Cases permission.*
- **Check:** who may approve a held expand? *Answer: someone other than the person who asked, holding Approve link
  expansions; where the Investigation has members, only a lead or a reviewer.*
- **Gotchas:** without sign-in (Basic) nobody can approve, so four-eyes is Standard and up. The Space's four-eyes limit
  is 2,000 neighbours of each entity (`four_eyes_fan_out_above` in Telecom's `link-analysis.toon`); the seeded request
  asks for every neighbour, so it is held. The Telecom Space also holds a second Investigation with its own held
  request: open the right one. Approving runs the expand for real: record S07 last of the manager's scenes.

### S01 · Investigations are team work
- Voice: This is Inspecto LA training, episode F5: teams. An Investigation is rarely one person's work. Someone leads it, someone checks it, and some steps are too big to take alone. In this episode you will see who is on an Investigation and what each role may do, add and remove a member, ask for a large expand that waits for a second person, approve it as that person, and see the same Investigation through the eyes of a reviewer and of someone who is not on it at all. We are in the Standard edition, in the Telecom Space.
- Action: intro card.
- Impact: none.

### S02 · Who is on it
- Voice: Signed in as the analyst, open the Investigation SIM-swap wallet takeover: one call-centre login, S-41, and the numbers it swapped. Open the Investigation actions menu and choose Members. Four people, each with a role. A lead changes the Investigation and decides who is on it. An analyst works on it. A reviewer reads it, and may decide approvals. The analyst opened it, so the analyst is its owner. The manager is here as a reviewer: that is the second pair of eyes.
- Chip: Investigation actions · Members…
- Action: File ▸ Open… ▸ Investigations ▸ *SIM-swap wallet takeover* ▸ ⋯ Investigation actions ▸ Members. [check in UI: the four rows analyst lead (owner, you), admin lead, reviewer reviewer, manager reviewer]
- Impact: mark each role badge as the voice names it.

### S03 · Grant and revoke
- Voice: A lead grants a role in one line. Type the user, outsider, choose the role reviewer, and press Grant. The list grows by one, and that person can now open the Investigation and read it. Now press Revoke beside them. The product asks first, and says what will happen: they lose access at once, and a lead can grant it again. Confirm. One more rule: an Investigation always keeps a lead, so the last lead cannot be removed.
- Chip: Members · Grant · Revoke
- Action: User `outsider` ▸ Role reviewer ▸ Grant ▸ hold ▸ Revoke beside outsider ▸ read the dialog ▸ Revoke.
- Impact: before/after hold: the outsider row appears, then leaves; mark the dialog sentence.

### S04 · A step too big to take alone
- Voice: Look at the Expand section of the Investigation tab. Under the expand already recorded, one line is waiting. The analyst asked to expand from every entity, with every neighbour. That is above this Space's four-eyes limit, so it did not run: it says waiting for approval, names the request, and says a second person must approve it. Before you press Expand, the form warns you the same way. Four-eyes is set per Space, as a limit, so that the large steps, the ones that pull in hundreds of people, are always seen by two.
- Chip: Expand · waiting for approval
- Action: Investigation tab ▸ Expand section ▸ zoom on the held line. [check in UI: the line reads *…: waiting for approval — this expand exceeds the Space's four-eyes limit (…), so it runs once a second person approves request …*]
- Impact: mark the held line and the request id.

### S05 · Who can open your Investigations
- Voice: One page answers who can open your work, across every Investigation you can open. Go to Settings, and under My settings choose Investigation access. Each Investigation has a card: your role on it, its members, and any live link that shows it outside. Where you lead, a Revoke button sits beside each member and each link. It is the place to check, before a holiday or a handover.
- Chip: Settings · Investigation access
- Action: rail ▸ Settings ▸ My settings ▸ Investigation access ▸ scroll to *SIM-swap wallet takeover*. [check in UI: the card shows *You: lead*, the four members and *Live embed links: None.*]
- Impact: mark the *You: lead* chip and the member rows.

### S06 · The approver's view
- Voice: Now sign out, and sign in as the manager. Open the same Investigation. Under the Dataset line, a badge says one to approve. The banner says what the manager may do here: read it and decide approvals; only a lead changes its steps. And where the analyst saw a waiting line, the manager sees the decision. It names who asks, and says in plain words what the expand would do: add every neighbour of each entity, more than the two thousand allowed without a second approval. Two buttons: Approve and Deny.
- Chip: 1 to approve · Approve · Deny
- Action: user menu ▸ sign out ▸ Demo Manager ▸ File ▸ Open… ▸ *SIM-swap wallet takeover* ▸ click *1 to approve*. [check in UI: the approver line's exact sentence, and that the banner reads *You review this Investigation: you can read it and decide approvals; only a lead changes its steps.*]
- Impact: mark the badge, the banner and the approver line.

### S07 · Approve, or deny with a reason
- Voice: Deny is a second step: an optional reason, which the requester sees on their line, then Deny request. Nothing is read, and the denial is recorded. Press Cancel; this time we approve. Press Approve. The product asks first: approving runs the expand now, as the analyst's step, and records you as the approver. Confirm. The expand runs, and the line now says what it admitted. In the step log, the new row carries both names.
- Chip: Deny · Reason (optional) · Approve this expand?
- Action: Deny ▸ point at Reason (optional) and Deny request ▸ Cancel ▸ Approve ▸ read the dialog *Approve this expand?* ▸ Approve ▸ hold ▸ dock ▸ Step log. [check in UI: the approved line *…: approved (request …) — N new entities admitted.*; name the count in the voice only if it reads well]
- Impact: before/after hold: the new entities join the canvas; mark the approved line and the new log row.

### S08 · The record of the decision
- Voice: Every decision stays on record. Open the Settings gear, Investigation settings, Oversight and approvals. The request is listed with who asked and when, the limit it crossed, and now: approved by the manager, and the step it ran as. Nobody can approve their own request. The server refuses it, whatever the screen offers.
- Chip: Investigation settings · Oversight and approvals
- Action: Settings ▸ Investigation settings ▸ Oversight and approvals ▸ hold on the request. [check in UI: *Approved by manager — ran as step N*]
- Impact: mark the request's status badge and its decision line.

### S09 · The reviewer's view
- Voice: Sign in as the reviewer. The Investigation opens, and everything can be read: the graph, the step log, the notes, the data strip. But a Read-only banner says why nothing can change: changing an Investigation needs the Manage Incidents and Cases permission. Undo and Redo are greyed, and their tooltip says why. There is no Expand form. What a reviewer can still do is check: Replay, re-read for drift, and build and verify the Dossier.
- Chip: Read-only
- Action: sign out ▸ Demo Reviewer ▸ open the Investigation ▸ hover Undo ▸ Settings ▸ Investigation settings ▸ Replay and drift ▸ Replay. [check in UI: Undo's tooltip wording for this reader]
- Impact: mark the Read-only banner, the greyed Undo and *Replay matches*.

### S10 · The outsider's view
- Voice: Last, the outsider: signed in, but not a member. In File, Open, the Investigation is not even listed. Open its link directly, and the answer is one sentence: this Investigation is not available: it does not exist, is not yours, or its Dataset is no longer shared with you. The answer is the same for all three on purpose. An outsider cannot even learn that it exists. To let them in, a lead grants them a role, as you saw at the start.
- Chip: Not available
- Action: sign out ▸ Demo Outsider ▸ File ▸ Open… (no such Investigation) ▸ open `/link-analysis?investigation=sim-swap-takeover` in the address bar. [check in UI: the sentence appears in the Investigation tab or as the page's alert]
- Impact: mark the sentence.

### S11 · Try it
- Voice: So: leads change, analysts work, reviewers read and decide, and outsiders are not told. Large expands wait for a second person, and every decision is recorded. Your turn. Sign in as the reviewer, and open SIM-swap wallet takeover. Name two things you can still do, and the sentence that tells you why Expand is missing. The answer is in the description. Next, in F6, Drafts: working on a side line in parallel.
- Action: outro card: *Try it* and *Next: F6 · Drafts*.
- Impact: none.

## F6_Drafts-work-in-parallel

> **F6 · Drafts: work in parallel** · Standard · Enterprise Space · the access creep · 5 min · user guide §11.5

- **Objectives:** say what a Draft is for; work on a Draft without changing the Investigation; check a Draft's conflicts,
  rebase it onto the main log and promote it; discard one, knowing it cannot be undone.
- **Before:** a fresh unzip of `la-standard.zip`, the Enterprise Space, signed in as Demo Analyst, the seeded Investigation
  *Access creep by one manager* (`tools/seed-la-insider.mjs`: start from EMP-0007, a bounded expand, a note, a threshold, a
  time window, a resolve step, a snapshot marker; and one open Draft by the analyst, *Draft: the vendor bank change*, with
  one note on EMP-0482).
- **Covers (plan §5):** 5.3 Investigation settings ▸ Drafts (Name this Draft, Fork a Draft, Work on this Draft, Back to the
  Investigation, Working Set, Conflicts, Rebase, Promote, Discard), the *Working on a Draft* banner, *main moved N steps*,
  the Draft limits; Replay and Re-order on a Draft.
- **Try it:** fork a Draft of your own named *cash out*, expand EMP-0482 by every kind of link on it, and look at the
  Draft's Working Set. Then discard it. What happened to the Investigation? *Answer: nothing. A Draft's steps go to the
  Draft only; discarding drops them, and the main log is as it was. (If the seeded Draft is still open, the product says
  you already have one: continue, promote or discard it first.)*
- **Check:** the main log moved on after you forked. What must you do before you promote? *Answer: Rebase the Draft onto
  the main log's head; Conflicts shows first which steps would carry over and which a rebase would drop.*
- **Gotchas:** one live Draft per person on an Investigation. This Space sets `drafts.max_open: 10`, `hibernate_after_minutes:
  60`, `expire_after_days: 14` (`link-analysis.toon`); the refusal sentence names the Space's own limit (10 here), as
  the server states it. A promote with a sensitive expand is held for four-eyes and applies nothing until approved.
  The Enterprise Space masks its *party* type; this Investigation uses the unmasked *insider_entity* type, so ids are readable.

### S01 · A side line, not a rewrite
- Voice: This is Inspecto LA training, episode F6: Drafts. The step log is permanent, and that is the point. But sometimes you want to try something first: follow a hunch, see where it leads, and only then decide whether it belongs in the record. That is a Draft: a side line forked from the main log, yours to work on, while the Investigation stays as it is. Several analysts can each have one, in parallel. We are in the Standard edition, in the Enterprise Space.
- Action: intro card.
- Impact: none.

### S02 · The access creep, and a hunch
- Voice: Signed in as the analyst, open the Investigation Access creep by one manager. An operations manager, EMP-0007, granted four of their own reports access to the payroll and treasury systems, with no ticket. One of those four, EMP-0482, later changed a supplier's bank account. That is a hunch worth following, but it is a different question from the one this Investigation was opened for. So the analyst has started a Draft for it.
- Chip: File · Open… · Investigations
- Action: File ▸ Open… ▸ Investigations ▸ *Access creep by one manager*. [check in UI: EMP-0007 and its reports on the canvas, EMP-0482 among them]
- Impact: mark EMP-0007 and EMP-0482.

### S03 · The Drafts fold
- Voice: Open the Settings gear, Investigation settings, Drafts. One Draft is listed. Whose it is, its name, the vendor bank change, its state, open, and two facts: it was forked at step seven, and it holds one step of its own, a note on EMP-0482. Press Working Set to see how big the Draft's own picture is. Press Conflicts: the main log has not moved since the fork, so a rebase would carry every step.
- Chip: Investigation settings · Drafts
- Action: Settings ▸ Investigation settings ▸ Drafts ▸ Working Set ▸ Conflicts. [check in UI: *forked at step 7 · 1 own step*; *No conflicts — a rebase would carry every step.*]
- Impact: mark the Draft row, then the Working Set line, then the conflicts line.

### S04 · Work on the Draft
- Voice: Press Work on this Draft, and close the settings. A banner now sits at the top of the Investigation tab: working on a Draft. Steps and Undo go to the Draft, not to the Investigation. Select EMP-0482, and in the Expand filter choose the one kind of link that matters here: who changed which supplier. Press Expand. The supplier VEN-0264 arrives, on the Draft only.
- Chip: Work on this Draft · Working on a Draft
- Action: Drafts ▸ Work on this Draft ▸ close Settings ▸ zoom on the banner ▸ click EMP-0482 ▸ Expand filter ▸ Link kinds: only `changed_vendor` ▸ Expand. [check in UI: the link kind's label in the filter; that VEN-0264 arrives]
- Impact: mark the banner; before/after hold: VEN-0264 joins the canvas.

### S05 · The Investigation did not move
- Voice: Now go back. In Drafts, press Back to the Investigation. The supplier is gone from the canvas, and the step log is exactly as it was. That is the promise of a Draft: you can explore freely, and nothing reaches the record until you decide it should. While you are on a Draft, two things are off on purpose. Re-order is not available, and Replay cannot compare a Draft with current data: both are about the record itself.
- Chip: Back to the Investigation
- Action: Settings ▸ Investigation settings ▸ Drafts ▸ Back to the Investigation ▸ close ▸ dock ▸ Step log.
- Impact: before/after hold: VEN-0264 leaves; mark the unchanged last row of the Step log.

### S06 · Meanwhile, the main log moves on
- Voice: In real work, the Investigation does not wait for you. Someone records another step on the main log. Here we do it ourselves: select EMP-0007 and press K, to keep the manager safe from any later exclusion. That is a step on the main log. Open Drafts again: the Draft now says main moved one step. It was forked from an older point of the record.
- Chip: Keep · K · main moved
- Action: click EMP-0007 ▸ K ▸ Settings ▸ Investigation settings ▸ Drafts. [check in UI: the badge reads *main moved 1 step*]
- Impact: mark the Keep row in the log; then the *main moved 1 step* badge.

### S07 · Conflicts, then Rebase
- Voice: Before a Draft joins the record, it has to sit on top of the latest step. Press Conflicts first. It says what a rebase would do: the step it was based on, where the main log is now, and how many of the Draft's steps would carry over. A Draft step that clashes with what the main log did since is listed as a conflict, with the reason, and a rebase drops it, after asking you. Here there is none. Press Rebase: rebased onto the latest step.
- Chip: Conflicts · Rebase
- Action: Conflicts ▸ hold ▸ Rebase ▸ hold on the notice. [check in UI: *Based on step 7; main is at step 8 (1 behind) · 2 steps would carry over.* and the notice *Rebased onto step 8.*]
- Impact: mark the conflicts report, then the notice.

### S08 · Promote
- Voice: Now decide. Press Promote. Promoted: the Draft's steps are now in the Investigation. The supplier appears on the main canvas, and the step log has the note and the expand, recorded with your name, after the keep. If a Draft contained a large expand, above the Space's four-eyes limit, the promote would not apply anything: it would wait for a second person, exactly like an expand on the main log.
- Chip: Promote
- Action: Promote ▸ hold ▸ close ▸ dock ▸ Step log. [check in UI: the notice *Promoted — the Draft’s steps are now in the Investigation.*; if the expand is held instead, reword the first two sentences to the held notice]
- Impact: before/after hold: VEN-0264 joins the main canvas; mark the promoted rows.

### S09 · Discard, and the limits
- Voice: A hunch that leads nowhere is discarded. Discard asks first, and means it: the Draft's steps are dropped, they are not promoted, and this cannot be undone. A few limits keep Drafts tidy. One live Draft per person on an Investigation. A Draft left alone goes to sleep, and after some days it expires; the panel warns you with expires soon. And a reviewer's role cannot change Drafts at all.
- Chip: Discard · Fork a Draft
- Action: point at Discard on a Draft row (do not confirm) ▸ point at Name this Draft (optional) and Fork a Draft. [check in UI: the Discard dialog's sentence, opened and cancelled]
- Impact: mark the dialog's last sentence, then the Fork a Draft button.

### S10 · Try it
- Voice: So: fork a Draft to try something, work on it, check its conflicts, rebase, and promote it or discard it. The record only ever gets what you decided to keep. Your turn. Fork a Draft of your own named cash out, expand EMP-0482 by every kind of link it has, and look at the Draft's Working Set. Then discard it. What happened to the Investigation? The answer is in the description. Next, in F7, masked data, audited reveal, and resolving identities.
- Action: outro card: *Try it* and *Next: F7 · Masked data and identities*.
- Impact: none.

## F7_Masked-data-reveal-and-identity-resolution

> **F7 · Masked data and audited reveal; resolving identities** · Standard · Mobile Money Space · the mule hub and the
> device farm · 7 min · user guide §4.1 (Masked ids), §6.2, §11.2 (Masked ids in an Investigation), §11.7, §14 (Masking)

- **Objectives:** read a masked graph and say what an alias still lets you do; reveal the real ids with a reason, and say
  who sees them and where it is recorded; define identity resolution; assert that two identifiers are one entity and
  show it in an Investigation; say why a masked entity cannot be merged on the canvas.
- **Before:** a fresh unzip of `la-standard.zip`, the Mobile Money Space, the seeded Investigation *Mule hub and its chain*
  (`tools/seed-la-mm.mjs`: start, bounded expand, RELAY-2 excluded, a note on HUB-01, a threshold, a time window, a resolve
  step, a snapshot marker; the Space's identity fact *wallet FARM-1 = wallet FARM-2, same handset DEV-FARM-01*). Sign in as
  Demo Analyst for S02–S04 and as Demo Admin from S05 (in this Space only the administrator may reveal, and is a lead of
  the Investigation).
- **Covers (plan §5):** 5.2d node detail ▸ Masked id, Reveal the masked entities on screen…; the canvas line *N entities
  masked* and Reveal…; the reveal dialog (Reason); Oversight and approvals ▸ Masked entities (the record of the reveal);
  5.2b Merge identities… and its refusal; 5.4 Settings ▸ Identities (Assert same entity, identity groups, Retract, Add a
  resolve step); the step log's Resolve identities.
- **Try it:** as Demo Admin, in Settings ▸ Identities, use Find group on the wallet FARM-2. How many identifiers are in its
  group, and why? *Answer: three after this episode (FARM-1, FARM-2 and FARM-3): FARM-1 = FARM-2 was asserted by the
  analyst, FARM-1 = FARM-3 in S10, and a group joins every assertion that shares a member. On a fresh unzip, without
  S10, it is two.*
- **Check:** who sees the real ids after a reveal? *Answer: only the person who revealed, on that screen, until they close
  the Investigation. Everyone else, and the Dossier, keep the aliases; each revealed entity is recorded with the reason.*
- **Gotchas:** this Space's wallets are masked because the wallet column is in a sensitive class (masking mode *typed*);
  the analyst role here holds no *See unmasked ids*, on purpose. Merge on the canvas refuses masked aliases, before and after a
  reveal, so S10 asserts with typed values. The FARM wallets are not in this Investigation's Working Set, so the resolve
  step of S10 changes no node on screen: say so, do not imply it. Identities appears in the Settings dialog only where the
  geo-link module is installed.

### S01 · Names you are not allowed to see
- Voice: This is Inspecto LA training, episode F7: masked data, and resolving identities. Some identifiers are personal data: a wallet, a phone number, an account. A Space can show them as aliases, so that an analyst can follow the money without seeing who it belongs to. When a name is truly needed, one person reveals it, with a reason, and it is recorded. In the second half, you will tell the product that two identifiers are one person, and see it in an Investigation. We are in the Standard edition, in the Mobile Money Space.
- Action: intro card.
- Impact: none.

### S02 · A masked graph still works
- Voice: Signed in as the analyst, open the Investigation Mule hub and its chain. Every wallet reads masked, then a code. Each code is an alias, and the same alias always stands for the same wallet, everywhere in this Investigation. So you can still group, count, search, start from and follow it. The hub still looks like a hub. Under the canvas, a line says how many entities are masked, beside a Reveal button. It is greyed, and its tooltip says why: needs See unmasked ids.
- Chip: N entities masked · Reveal…
- Action: File ▸ Open… ▸ Investigations ▸ *Mule hub and its chain* ▸ zoom on two node labels ▸ hover Reveal… under the canvas. [check in UI: that the wallet ids show as `masked:…` for Demo Analyst, and the tooltip *Needs See unmasked ids.*]
- Impact: mark two aliases; mark the greyed Reveal… and its tooltip.

### S03 · The node's details
- Voice: Select the hub, and click it again for its details. The first section is Masked id: it says this name is an alias, because this Space masks entities of this type, and why you cannot reveal it. Read on. Text and dates beside a masked id are masked with it, but counts and scores stay readable. The Summary still reads the hub's links, its counterparts by alias, and its activity per day. Masking hides who, never how much.
- Chip: Node details · Masked id
- Action: click the hub node ▸ click again ▸ the dialog ▸ Masked id section ▸ scroll to Summary. [check in UI: the Masked id sentence *… is an alias: this Space masks entities of this type.* and the reason line]
- Impact: mark the Masked id section; mark one masked detail and one readable count.

### S04 · Who may reveal
- Voice: Who may reveal is a decision made in two places. The role must hold See unmasked ids, and the person must lead the Investigation, because a reveal is recorded against an Investigation and its purpose. On a graph that is not saved as an Investigation, the button says to save it first. In this Space, the analyst works without the right, by design. The administrator holds it, and leads this Investigation too. Sign out, and sign in as the administrator.
- Chip: See unmasked ids
- Action: hold on the greyed Reveal… ▸ user menu ▸ sign out ▸ Demo Admin ▸ File ▸ Open… ▸ *Mule hub and its chain*.
- Impact: mark Reveal…, now enabled.

### S05 · Reveal, with a reason
- Voice: Press Reveal. The dialog says exactly what will happen: the real identifiers of the masked entities on screen are shown to you, and on this screen only; each one is recorded in the audit trail with your reason; everyone else, and the Dossier, keep the aliases. Type the reason: confirm the hub before the wallets are frozen. Press Reveal. A message confirms how many ids were revealed, each recorded with your reason. The labels change: HUB-01, the relays, the structuring wallet.
- Chip: Reveal the masked entities on screen
- Action: Reveal… ▸ read the dialog ▸ Reason `confirm the hub before the wallets are frozen` ▸ Reveal ▸ hold. [check in UI: the toast *Revealed N ids, each recorded with your reason.*]
- Impact: before/after hold: aliases turn into HUB-01, RELAY-1, STRUCT-01 and the others; mark the labels and the toast.

### S06 · On this screen, for you only
- Voice: Open the hub's details again. Masked id now shows the real id, and says it plainly: shown to you on this screen only, and recorded with your reason; everyone else still sees the alias. The step log reads the real ids too, for you. Close the Investigation, and the reveal is forgotten. Opening it again shows aliases until you reveal again, with a new reason.
- Chip: Masked id · Real id
- Action: click HUB-01 twice ▸ Masked id ▸ dock ▸ Step log (row 3 names RELAY-2) ▸ ⋯ ▸ Close ▸ File ▸ Open… ▸ the same Investigation. [check in UI: the step log rows read real ids after the reveal]
- Impact: mark *Real id: HUB-01*; then the aliases back on the reopened canvas.

### S07 · The record of a reveal
- Voice: Reveal again, with your reason, and open the Settings gear, Investigation settings, Oversight and approvals. Under Masked entities, each alias is listed with the value it stands for, marked reveal audited. One reveal shows them all, to you on this screen only, and each entity is recorded in the audit trail with your reason. That record is what an auditor reads later: who looked, at what, and why.
- Chip: Oversight and approvals · Masked entities
- Action: Reveal… ▸ Reason ▸ Reveal ▸ Settings ▸ Investigation settings ▸ Oversight and approvals ▸ Masked entities. [check in UI: each row reads alias → value *(reveal audited)*]
- Impact: mark two rows and the line *One reveal shows them all…*.

### S08 · Identity resolution
- Voice: Now the second problem. One person can hide behind several identifiers: two wallets, two phone numbers, opened with different identity papers. Identity resolution is declaring that two identifiers are one entity, with a reason, so the graph can show them as one. In this Investigation's step log, step seven is a Resolve identities step: from there on, the canvas shows the identities the Space knows. Open the Settings gear, Identities. One group is listed: two farm wallets, one handset, with the reason and who said so. Retract would take it back.
- Chip: Settings · Identities · Resolve identities
- Action: dock ▸ Step log ▸ row 7 ▸ Settings ▸ Identities ▸ the identity group. [check in UI: row 7 reads *Resolve identities*; the group shows 2 identifiers and the assertion *#1 … = … — same handset DEV-FARM-01 (showcase)*, by analyst]
- Impact: mark row 7; mark the group and its Retract button.

### S09 · Why the canvas will not merge these
- Voice: The quickest way to merge is on the canvas: select two entities, and press Merge identities on the canvas toolbar. Try it on two wallets here. It is greyed, and the tooltip says why: a masked entity cannot be merged, only its alias is visible, and an identity needs the real value. Even after a reveal, the graph holds aliases. In a Space that masks, the merge is made in the Identities form, by someone who knows the real values.
- Chip: Merge identities…
- Action: Shift+click two wallet nodes ▸ hover Merge identities… on the canvas toolbar. [check in UI: the tooltip *A masked entity cannot be merged: only its alias is visible, and an identity needs the real value.*]
- Impact: mark the greyed button and its tooltip.

### S10 · Assert, then show it
- Voice: In Identities, fill in Assert same entity. First identifier type, wallet, and the value FARM-1. Second identifier type, wallet, and FARM-3. The reason: same handset, DEV-FARM-01. Press Assert same entity. The group now holds three identifiers. Because an Investigation is open, the form asks whether to show this merge there. Press Add a resolve step. It is sealed as a step. These farm wallets are not on this graph, so nothing moves on screen now; on any later step that brings them in, the three draw as one. And it is reversible: retract the assertion, and the group splits.
- Chip: Assert same entity · Add a resolve step
- Action: Settings ▸ Identities ▸ First identifier type `wallet`, First identifier `FARM-1`, Second identifier type `wallet`, Second identifier `FARM-3`, Reason `same handset DEV-FARM-01` ▸ Assert same entity ▸ Add a resolve step ▸ dock ▸ Step log. [check in UI: the notice *Sealed a resolve step: the Investigation now shows the merged identities.* and a new *Resolve identities* row]
- Impact: mark the group (3 identifiers), the notice and the new log row.

### S11 · Try it
- Voice: So: an alias still lets you work; a reveal is one person, one screen, one reason, all recorded; and an identity is declared with a reason, shown by a resolve step, and can be taken back. Your turn. As the administrator, in Identities, use Find group on the wallet FARM-2. How many identifiers are in its group, and why? The answer is in the description. Next, in F9, from a finding to action: Alert Rules, Cases and sharing.
- Action: outro card: *Try it* and *Next: F9 · From finding to action*.
- Impact: none.

## F8_Evidence-coverage-Dossier-and-sealed-bundle

> **F8 · Evidence: coverage, Dossier and the sealed bundle** · Standard · Banking and finance Space · the compromised terminal ·
> 7 min · user guide §11.6, §11.9, §11.11, §15

- **Objectives:** say what data an Investigation stands on (the data strip, Data details, Coverage); build and verify a
  Dossier; catch an edited manifest and an edited sealed bundle.
- **Before:** a fresh unzip of `la-standard.zip`, the Banking and finance Space, signed in as Demo Analyst, the seeded Investigation
  *Compromised terminal* (start at M-317, bounded expand, note, threshold, time window, resolve, snapshot *showcase
  baseline*). In the recording folder, made off camera from real downloads of this Investigation: a manifest with one
  digit of one hash changed, and a sealed bundle with one character changed (editing on camera is error-prone).
- **Covers (plan §5):** 5.2h Coverage (Check coverage); data strip, Data details; 5.3 the Investigation actions menu, Build
  Dossier (At step, Include snapshots…), Log intact, ledger, manifest root, data state at export time, Download steps /
  method / HTML / manifest, Verify this Dossier, Verify an uploaded manifest…, Download Dossier (sealed), Verify a bundle….
- **Try it:** build a Dossier of *Compromised terminal* at step 3 and compare its summary with the latest one. What is
  different, and why? *Answer: it is built at an earlier step, so its ledger stops there and its counts are the Working
  Set as it was then; the later steps are not in it.*
- **Check:** a saved view, a snapshot and a Dossier: which are evidence? *Answer: the snapshot and the Dossier; a saved
  view re-reads live data.*
- **Gotchas:** Verify this Dossier is off until a Dossier is built, and says so. The data footer is outside the custody
  hash by design: say it, do not hide it. The Banking and finance Space masks nothing, so every id is readable here (F7 covers
  masking).

### S01 · A view is not evidence
- Voice: This is Inspecto LA training, episode F8: evidence. A graph on a screen is not evidence. A saved view re-reads live data, so tomorrow it may show something else. Evidence is something you can hand over, that anyone can check has not changed since. In this episode you will check what data an Investigation stands on, build a Dossier from it, verify it, catch an edited copy, and download a sealed bundle for someone outside the system. We are in the Standard edition, in the Banking and finance Space, signed in as the analyst.
- Action: intro card.
- Impact: none.

### S02 · The Investigation and its log
- Voice: From the File menu, Open, choose the Investigation Compromised terminal. It started from one merchant, the fuel station M-317, and reached the cards that paid at its pumps in March and were defrauded online weeks later. The step log shows how, in order: the start, a bounded expand, the analyst's note, a threshold, a time window, an identity step, and a sealed snapshot. Every step is recorded with who took it and why, and nothing in it can be quietly changed. That log is what the evidence is built from.
- Chip: File · Open… · Investigations
- Action: File ▸ Open… ▸ Investigations ▸ *Compromised terminal* ▸ open the dock's Step log. [check in UI: the step labels as the log shows them]
- Impact: mark the step log's seven rows.

### S03 · The data strip
- Voice: Above the Investigation is the data strip: a badge, and one plain sentence about the data it stands on, how current it is and how complete. The badge's colour comes with words: All clear, Check data, Data problem or Not known. Open Data details for what is behind it: when data last arrived, which days of the window are complete, one row per file with its outcome, and the state of the link index. Before you build evidence, you want to know what you built it on.
- Chip: Data strip · Data details
- Action: zoom on the strip ▸ Data details ▸ scroll the four parts (Fresh, Complete, Files, Index) ▸ close. [check in UI: the badge and sentence this Investigation shows]
- Impact: mark the badge and the sentence; mark each drawer part as the voice names it.

### S04 · Coverage: no activity, or no data?
- Voice: The Coverage tab in the dock asks a narrower question. Press Check coverage. It looks at every day of the Investigation's window and names the days with no rows at all. That matters more than it sounds. An empty result on a day with no data says nothing about what happened that day: the cards may have been used, and the records simply never arrived. Coverage is how you tell no activity from no data, and a Dossier that shows it is one a reviewer can trust.
- Chip: Coverage · Check coverage
- Action: dock ▸ Coverage ▸ Check coverage ▸ hold on the answer (*Every day has data* or *Days with no data*). [check in UI: which answer this Investigation gives; if days are missing, the voice adds one sentence naming how many]
- Impact: mark the answer.

### S05 · Build the Dossier
- Voice: Now the Dossier. Open the Investigation's actions menu and choose Build Dossier. At step can stay blank: blank means the latest step. Include snapshots lets you add sealed snapshots, and only those anchored to this Investigation are accepted. This Investigation has none to include, so leave it empty, and press Build Dossier. The summary appears: the Investigation, its Dataset, the step it was built at, and how many entities, links, excluded, hidden and kept.
- Chip: Build Dossier · At step
- Action: Investigation tab ▸ ⋯ actions ▸ Build Dossier ▸ At step blank ▸ Include snapshots… ▸ tick the snapshot ▸ Build Dossier. [check in UI: the snapshot's id as listed]
- Impact: mark the summary line.

### S06 · Log intact, the ledger and the manifest root
- Voice: The green box says Log intact: every recorded hash still agrees with the sealed log. Below it, the ledger lists each step, what was done, and how many entities the Working Set held after it. Then the manifest root: one fingerprint, a SHA-256 hash over every piece of the Dossier. One more part is labelled on purpose: the data state at export time, with the data-as-of day and the days with no rows. It is not part of the custody hash, because data moves on, and the evidence must not.
- Chip: Log intact · Manifest root
- Action: scroll the Dossier panel: Log intact ▸ Ledger ▸ Manifest root ▸ Data state at export time.
- Impact: mark *Log intact*, then the root, then *Not part of the custody hash*.

### S07 · Four downloads
- Voice: Four downloads sit under it. Download steps and Download method give plain statements: what was done, and how it was done. Download HTML is the readable Dossier, to read or print. Download manifest is the list of every piece with its hash. Masked ids stay masked in all of them, even inside a reason someone typed.
- Chip: Download steps · method · HTML · manifest
- Action: hover each of the four buttons; press Download manifest (kept for the Try it).
- Impact: mark each button as the voice names it.

### S08 · Verify this Dossier
- Voice: Press Verify this Dossier. The product checks the manifest against what is stored now. Verified: the manifest matches the store, with the same root. The detail lines say changed, none; missing, none; added, none. Nothing has moved since the Dossier was built.
- Chip: Verify this Dossier
- Action: Verify this Dossier ▸ hold on the result and its detail lines.
- Impact: mark *Verified* and the three *none* lines.

### S09 · Catch an edited manifest
- Voice: Now suppose someone edits a downloaded manifest, even by one character. Here is a copy where one digit of one hash was changed. Choose Verify an uploaded manifest, and pick it. NOT verified, in red, with the reason in words: the manifest's root does not match its own body, so the manifest itself was edited. A failed check is a result, not an error. It is the product doing its job.
- Chip: Verify an uploaded manifest…
- Action: Verify an uploaded manifest… ▸ pick the pre-made edited manifest ▸ hold. [check in UI: the reason lines and the Changed line for a one-digit edit]
- Impact: before/after hold on the result box; mark *NOT verified* and its reason.

### S10 · The sealed bundle
- Voice: Someone outside the system cannot ask the server, so a manifest is not enough for them. Download Dossier, sealed, makes one file: the Dossier, its references, and a seal over both. Choose Verify a bundle, and pick the file just downloaded: Bundle verified. The seal matches the file, and the store still agrees. Now a copy with one character changed after export: Bundle NOT verified. Seal intact: no, edited after export. Each problem is listed as it was found.
- Chip: Download Dossier (sealed) · Verify a bundle…
- Action: Download Dossier (sealed) ▸ Verify a bundle… ▸ pick the fresh bundle ▸ hold ▸ Verify a bundle… ▸ pick the pre-made edited bundle ▸ hold.
- Impact: two result states, each held 2 s; mark *Bundle verified*, then *Bundle NOT verified* and *no — edited after export*.

### S11 · Try it
- Voice: So: a view is not evidence; snapshots and Dossiers are. Check the data you stand on, build the Dossier, verify it, and hand over the sealed bundle. Your turn: on the Standard zip, build a Dossier of Compromised terminal at step three, and compare its summary with the latest one. What is different, and why? The answer is in the description. Next, in F9, from a finding to action: Alert Rules, Cases and sharing.
- Action: outro card: *Try it* and *Next: F9 · From finding to action*.
- Impact: none.

## F9_From-finding-to-action

> **F9 · From finding to action: Alert Rules, Cases, widgets and sharing** · Standard · Mobile Money Space · the
> structuring funnel · 7 min · user guide §11.8, §11.10, §12, §12.1

- **Objectives:** define Measure, Alert Rule, Incident and Case; watch a Measure with an Alert Rule and read whether it
  would fire; work a fired Alert from the Alerts page back to its Investigation; attach the analysis to a Case and find
  the Case beside a node; pin a Working Set to a Widget; share a read-only link; open Inspecto LA from another system's link.
- **Before:** a fresh unzip of `la-standard.zip`, the Mobile Money Space, the seeded Investigation *Mule hub and its chain*
  with its Case *Structuring through HUB-01* (linked), its template, and the bound Alert Rule *mm-structuring* (Critical,
  standing detection on) that has fired once (`tools/seed-la-mm.mjs`). Signed in as Demo Analyst for S02–S09; as Demo Admin
  for S10–S11 (sharing and revealing are the administrator's here). For S11, copy the hub's alias from the canvas first.
- **Covers (plan §5):** 5.3 Investigation settings ▸ Measures and templates (Measures strip, Watch, Save as template…, New
  Investigation from a template…), the Watch dialog (Alert Rule name, Fire when the value is, Threshold, Severity, Create
  Alert Rule), Value Measures ▸ Bound Alert Rules, Standing detection, Watch this Measure; 5.5 Alerts (Evaluate now, What
  happened, Acknowledge, Resolve, Open Investigation); 5.2b Attach to Case (Seal and attach); Case fold; 5.2d Case history;
  Pin to a Widget (Show, Frozen, Live, Save Widget); Share and embed (Create link, Revoke); the *Open in Inspecto LA* page
  (Open the Investigation, Open the Entity, Reveal the value).
- **Try it:** watch the Links Measure of *Mule hub and its chain*, set Fire when the value is to *at least* and the
  Threshold to the value the strip shows. Would it fire now? *Answer: yes: the dialog answers Alert Rule armed, it would
  fire now, because the value already meets "at least" itself.*
- **Check:** does an Alert Rule on an Investigation watch live data? *Answer: a Working Set Measure watches the sealed
  Working Set, not live data; a value Measure watches the live Dataset, and only once standing detection is enabled by the
  Investigation's owner.*
- **Gotchas:** Evaluate now needs the Run operations permission (Demo Manager), so the analyst does not see it. Masked
  wallets cannot become members of a new Case: attach to the existing one. Embed links are off unless the server runs with
  embedding enabled; then the panel says *Embed links are turned off. Ask an administrator.* Pin to a Widget is shown only
  where the host has a Widget library; the Widget is added to a dashboard in the Inspecto console, which this app does not
  show. Wallet ids are masked for the analyst (F7).

### S01 · A finding is not the end
- Voice: This is Inspecto LA training, episode F9: from a finding to action. Four words first. A Measure is a figure computed over a Dataset or a Working Set: how many entities, how much money in a band. An Alert Rule is a saved condition on a Measure that raises an Alert when it crosses a level. An Incident is the operational record an Alert can open. A Case groups Incidents and the work on them, with an owner. In this episode you will turn a finding into all four, and share it. We are in the Standard edition, in the Mobile Money Space.
- Action: intro card.
- Impact: none.

### S02 · Measures of a Working Set
- Voice: Signed in as the analyst, open Mule hub and its chain, the funnel of episode C4. Open the Settings gear, Investigation settings, Measures and templates. The strip counts this Investigation's Working Set at its latest step: entities, links, events, excluded, the furthest degree, identities. Below it, Save as template turns the method into a recipe for the next case, and New Investigation from a template starts one from it. This Investigation already has one: the mule chain, hub, relays and cash-out agent.
- Chip: Investigation settings · Measures and templates
- Action: File ▸ Open… ▸ *Mule hub and its chain* ▸ Settings ▸ Investigation settings ▸ Measures and templates ▸ point at Save as template… and New Investigation from a template…. [check in UI: the strip's labels and *At step 8.*]
- Impact: mark the strip, then the two template buttons.

### S03 · Watch a Measure
- Voice: Each Measure has a Watch button. Press Watch on Entities. The dialog proposes a name, and asks when to fire: greater than, at least, less than, at most a threshold. Set greater than, and a threshold just above today's value, so the rule fires if the Working Set grows. Severity: Info, Warning, or Critical, which also opens an Incident when it fires. Choose Warning, and press Create Alert Rule. Armed, and it says whether it would fire now, and what a fired Alert discloses: the Investigation, never the entities.
- Chip: Watch · Create Alert Rule
- Action: Watch on Entities ▸ Fire when the value is: greater than ▸ Threshold: the current value ▸ Severity: Warning ▸ Create Alert Rule ▸ hold ▸ Done. [check in UI: *Alert Rule armed* with *it would not fire now*, and the disclosure sentence]
- Impact: mark the severity hint *Also opens an Incident when it fires.*; then *Alert Rule armed*.

### S04 · Watching live data
- Voice: That rule watches the sealed Working Set. To watch the live data, use a value Measure. In the toolbox, Whole Dataset, under What looks unusual, open Value Measures. Bound to this Investigation is the rule that found this funnel: cash-ins just under the five thousand limit, many of them, from many payers. Its standing detection is enabled: a scheduled sweep reads the Dataset with the owner's access, counts only, and stops on record if that access changes. Only the Investigation's owner can enable it. Watch this Measure arms a new one the same way.
- Chip: Value Measures · Standing detection
- Action: toolbox ▸ Whole Dataset ▸ *What looks unusual?* ▸ Value Measures ▸ Bound Alert Rules ▸ Standing detection ▸ point at Watch this Measure. [check in UI: *mm-structuring — CRITICAL (value Measure over the live Dataset)* and *Status: Enabled.*]
- Impact: mark the bound rule, its status, and the Watch this Measure button.

### S05 · The Alerts page
- Voice: That rule has fired. In the rail, open Alerts. Each fired Alert is a row: the rule, its severity, when, and its state. What happened explains it. Acknowledge says you have seen it and are on it; Resolve closes it. Press Acknowledge: the state changes at once. Someone allowed to run operations also sees Evaluate now, to check every rule without waiting for the schedule. Below the Alerts are the Alert Rules themselves, including the one you just armed.
- Chip: Alerts · Acknowledge · Resolve
- Action: rail ▸ Alerts ▸ the *mm-structuring* row ▸ What happened ▸ close ▸ Acknowledge ▸ scroll to Alert Rules. [check in UI: the row's columns and that Evaluate now is absent for Demo Analyst]
- Impact: before/after hold: the state OPEN → ACKNOWLEDGED; mark the new rule under Alert Rules.

### S06 · Back to the Investigation
- Voice: A fired Alert names the Investigation, not the entities. To see who breached, press Open Investigation on its row. It opens here, where your access and the masking apply, so an Alert never shows more than you may see. When the work is done, come back and press Resolve.
- Chip: Open Investigation
- Action: Alerts ▸ the row ▸ Open Investigation ▸ hold ▸ back ▸ Resolve. [check in UI: the action's hint *Open Investigation mm-structuring-case — it lists the breaching entities*]
- Impact: mark the Investigation opening; then the state RESOLVED.

### S07 · Attach to a Case
- Voice: A finding someone else must act on goes to a Case. On the canvas toolbar, press Attach to Case. It seals what is on screen as a snapshot with a fingerprint, and attaches it to a Case, in one step. Give the snapshot a title, choose an existing Case, Structuring through HUB-01, and press Seal and attach. A new Case from the selected nodes is greyed here, and says why: these wallets are masked, and a Case needs real identifiers.
- Chip: Attach to Case · Seal and attach
- Action: canvas toolbar ▸ Attach to Case ▸ Snapshot title `funnel at step 8` ▸ An existing Case ▸ pick *Structuring through HUB-01* ▸ point at the greyed *A new Case, from the selected nodes* and its reason ▸ Seal and attach. [check in UI: the reason sentence, and the toast *Sealed “funnel at step 8” (…) and attached it to …*]
- Impact: mark the reason; then the toast.

### S08 · The Case, from both sides
- Voice: From the Investigation's side: Investigation settings, Case. It is linked to that Case, so the Case's owner and assignee can read this Investigation until the Case closes, and only you can change it. From the entity's side: open the hub's details and unfold Case history. Every Incident and Case that names this entity is listed, newest first, and Show filters all, open or closed.
- Chip: Case · Case history
- Action: Settings ▸ Investigation settings ▸ Case ▸ close ▸ click the hub twice ▸ Case history ▸ Show: open. [check in UI: *Linked to Case …* and the Case listed under the hub's alias]
- Impact: mark *Linked to Case*; then the Case history row.

### S09 · Pin to a Widget
- Voice: A manager may want this funnel on a dashboard. Investigation settings, Pin to a Widget. Name it, choose what to show, the entities, the links, or the exclusions, and choose Frozen, as it is now, or Live, which follows the Investigation. Press Save Widget. It goes to the Widget library, ready to add to a dashboard. Only people who can read the Investigation see its rows; anyone else sees that it is not available to them.
- Chip: Pin to a Widget · Frozen · Live
- Action: Settings ▸ Investigation settings ▸ Pin to a Widget ▸ Widget name `mm-funnel-entities` ▸ Show: Entities ▸ Frozen ▸ Save Widget. [check in UI: the fold is shown in this app; the notice *Widget saved*]
- Impact: mark the notice.

### S10 · Share a read-only link
- Voice: Someone without an account can be given a read-only, sealed view. Investigation settings, Share and embed. As the analyst, it says: only a lead with Investigation sharing can create embed links. The administrator has that right, so we switch. Press Create link: a label, what the link shows, its masking, which can be stricter than the Space and never looser, and a lifetime. Create link. The link is shown once: copy it now. Each link lists its status, expiry and views, and Revoke cuts it off at once.
- Chip: Share and embed · Create link · Revoke
- Action: Share and embed (as Demo Analyst, hold on the sentence) ▸ sign out ▸ Demo Admin ▸ the same Investigation ▸ Share and embed ▸ Create link ▸ Label `auditor` ▸ What the link shows: *Entities and links only* ▸ Lifetime (hours) `24` ▸ Create link ▸ Copy ▸ Done ▸ Revoke. [check in UI: whether embed links are on in this build; if the panel says *Embed links are turned off. Ask an administrator.*, the voice says so instead of the minting]
- Impact: mark *Copy now: it is not shown again.*; then the link row and its Revoke.

### S11 · A link from another system
- Voice: Last, the other direction: a case manager or a fraud product links straight into Inspecto LA. The link opens the Open in Inspecto LA page. It checks the link first, and never moves on by itself. It names the Investigation, and the entity by its alias, because a masked value never travels in an address. Choose Open the Investigation, or Open the Entity. Someone allowed to reveal also sees Reveal the value: it asks the server once, records it, and keeps the value on this page. A broken link gets one sentence saying what is wrong.
- Chip: Open in Inspecto LA
- Action: address bar `/open?investigation=mm-structuring-case&entity=wallet:<the hub's alias>` ▸ hold ▸ Reveal the value ▸ hold ▸ address bar `/open?entity=HUB-01` ▸ hold. [check in UI: the Entity line *wallet: masked:… masked, shown by its alias*; the broken link's sentence *The entity in this link is not in the form “<type>:<key>” …*]
- Impact: mark the page's three buttons; the revealed value; then the error box *This link cannot be opened*.

### S12 · Try it
- Voice: So: a Measure watched by an Alert Rule raises an Alert, the Alert leads back to the Investigation, and the evidence goes to a Case. Your turn. Watch the Links Measure, set at least, and a threshold equal to today's value. Would it fire now? The answer is in the description. Next, in G1, the Geo map.
- Action: outro card: *Try it* and *Next: G1 · The Geo map*.
- Impact: none.
