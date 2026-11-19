---
type: Guide
title: Inspecto LA training videos — F · Investigations (Standard): narration scripts — Episodes F1-F3 — roles, expand, clean-up
description: Part 1 of 3 of Inspecto LA training videos — F · Investigations (Standard): narration scripts: Episodes in F; F1_Signing-in-roles-and-your-first-Investigation; F2_Expand-with-intent; F3_Recorded-clean-up-and-scope
tags: [module, la-core, link-analysis, user-guide, training, video]
timestamp: 2026-11-16T00:00:00Z
item: module.la-core
audience: internal
status: current
verified: 2026-11-19 last-edit
code: la/inspecto-la-core
---

# Inspecto LA training videos — F · Investigations (Standard): narration scripts — Episodes F1-F3 — roles, expand, clean-up

Part 1 of 3 of [Inspecto LA training videos — F · Investigations (Standard): narration scripts](user-guide-training-f-investigations.md) · next: [Episodes F4-F6 — step log, teams and Drafts](user-guide-training-f-investigations-teams-drafts.md).

## Episodes in F

| Episode | Title | Edition | Scenes | Spoken words | UI checks open |
|---|---|---|---|---|---|
| [F1](#f1_signing-in-roles-and-your-first-investigation) | Signing in, roles and your first Investigation | Standard | 11 | 819 | 6 |
| [F2](#f2_expand-with-intent) | Expand with intent: direction, kinds, time, strength, fan-out | Standard | 11 | 839 | 6 |
| [F3](#f3_recorded-clean-up-and-scope) | Recorded clean-up and scope | Standard | 12 | 896 | 6 |
| [F4](user-guide-training-f-investigations-teams-drafts.md#f4_step-log-undo-re-order-and-replay) | The step log: replay, undo, re-order, more steps | Standard | 11 | 916 | 9 |
| [F5](user-guide-training-f-investigations-teams-drafts.md#f5_teams-members-four-eyes-reviewers-and-outsiders) | Teams: members, four-eyes, reviewers and outsiders | Standard | 11 | 870 | 8 |
| [F6](user-guide-training-f-investigations-teams-drafts.md#f6_drafts-work-in-parallel) | Drafts: work in parallel | Standard | 10 | 748 | 7 |
| [F7](user-guide-training-f-investigations-evidence.md#f7_masked-data-reveal-and-identity-resolution) | Masked data and audited reveal; resolving identities | Standard | 11 | 899 | 8 |
| [F8](user-guide-training-f-investigations-evidence.md#f8_evidence-coverage-dossier-and-sealed-bundle) | Evidence: coverage, Dossier and the sealed bundle | Standard | 11 | 838 | 5 |
| [F9](user-guide-training-f-investigations-evidence.md#f9_from-finding-to-action) | From finding to action: Alert Rules, Cases, widgets and sharing | Standard | 12 | 957 | 10 |

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
- Voice: Open Link kinds: one chip per kind, pressed means followed. This pack has three: forwarding, shared device, and voice. Press forwarding and shared device to let them go, and keep only voice calls. Now open Strength, and set the minimum events per link to three. A pair of numbers that spoke once no longer counts; only the lines that call again and again do. Read the preview line under the Expand button after each change: it says what the step would add, before you press anything.
- Chip: Link kinds · Strength · Minimum events per link
- Action: click `99891000001` ▸ Link kinds: only `voice` ▸ Direction: Incoming ▸ hold on the preview ▸ Strength ▸ Minimum events per link `3` ▸ hold on the preview ▸ Expand. [check in UI: the two previews' counts, the link kind chip names, and that 99979200001 and 99979200002 arrive]
- Impact: before/after hold of the preview line; mark the two IRSF numbers.

### S04 · Reciprocal
- Voice: Direction has a fourth choice, only in a saved Investigation: Reciprocal, only pairs linked in both directions. Choose it, and read the preview: it counts only the numbers that were rung and also rang back. Mutual contact is how a relationship looks. A one-way burst, four hundred numbers rung for a few seconds, is how a campaign looks, and Reciprocal leaves it out. The filter title now says one group changed, and a dot marks the fold.
- Chip: Direction · Reciprocal
- Action: click `99979100001` ▸ Direction: Reciprocal ▸ hold on the preview ▸ Direction: Both ways. [check in UI: the preview reads *This step adds nothing new*; reword if a reciprocal pair exists]
- Impact: mark the preview line.

### S05 · Degrees
- Voice: Open Depth. Degrees says how many rings one Expand adds: one ring is the numbers a number touches, two rings is the numbers those touch. Press plus, and the preview counts two rings instead of one. Wider steps grow fast, so an Expand that would grow from too many numbers at once stops early, and says so, rather than flooding the canvas. For a real question, widen one degree at a time.
- Chip: Depth · Degrees
- Action: click `99979200001` ▸ Link kinds: `shared_identity`, `shared_payment` ▸ Direction: Both ways ▸ Depth ▸ Degrees `2` ▸ Expand 2 degrees. [check in UI: 99979300001…05 at degree 1 and 99979400001 at degree 2; both links fall inside the 30-day window]
- Impact: before/after hold: mark the five subscription-fraud lines, then the SIM box line.

### S06 · Shrink an Expand, and grow it again
- Voice: While the newest step is an Expand, Degrees is that Expand's size, and a line under it says so: Degrees follows the last Expand. Fewer removes its outermost degrees from the Working Set, and the record keeps that you took them back. More expands further, from the outer ring. The first degree always stays. This is how you try a wider step without losing your place.
- Chip: Degrees follows the last Expand
- Action: zoom on *Degrees follows the last Expand …* ▸ Degrees `1` ▸ wait ▸ Degrees `2` ▸ wait. [check in UI: the SIM box line leaves and returns; and how the Step log shows the lowered degree]
- Impact: before/after hold: 99979400001 leaves, then returns.

### S07 · Time
- Voice: This Investigation reads the last thirty days, counted back from the Dataset's latest event, never from today, and a line above the Expand button says which days the next Expand reads. Open Time. The chips are: the Investigation window, all data, the last seven, thirty or ninety days, and a custom window for this Expand only. Choose all data, and the next Expand reads the whole Dataset instead of the last month. Use it when the thing you chase happened before your window, such as a second SIM that rang its numbers a month earlier.
- Chip: Time · Investigation window · All data
- Action: click `99979100002` ▸ Link kinds: only `voice` ▸ Direction: Outgoing ▸ hold on the preview ▸ Time ▸ All data ▸ hold on the preview ▸ Expand. [check in UI: the first preview finds nothing new or very few, the second about 60]
- Impact: before/after hold of the preview; mark the new ring of numbers.

### S08 · Fan-out and busy
- Voice: The last fold limits the size of a step. Hold busy entities is on: an entity with more than five hundred linked is shown, but not expanded through. Most neighbours per entity keeps the strongest few from each. Fewest and most links a neighbour may have screen out the lonely and the crowded. Row limit per degree stops a step at that many rows and marks it incomplete. If you set Most neighbours per entity very high, a line warns that each degree would need a second person's approval; episode F5 shows that.
- Chip: Fan-out and busy · Most neighbours per entity · Row limit per degree
- Action: Fan-out and busy ▸ zoom on *Hold busy entities (more than 500 linked)*, Busy limit, Most neighbours per entity, Fewest links a neighbour may have, Most links a neighbour may have, Row limit per degree ▸ Most neighbours per entity `3000` ▸ hold on *Each degree will need approval from a second person: …* ▸ clear the field.
- Impact: mark the warning line.

### S09 · A wide step, held at the hub
- Voice: Now a wide step. Reset the filter to its defaults, and From is the outer ring. The preview says how many numbers and calls it adds, and that one busy number will be held, shown but not expanded through. Press Expand. Hundreds of numbers arrive, the circles the victims call every day, and the graph is laid out again. A line above the form says a busy entity is held, and how to go through it if you must.
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
- Voice: The other buttons work the same way. Hide those with fewer than a number of events, set by the stepper beside it. With one Entity Type in the Working Set, as here, there is no per-type button; with several, there is one for each. Select two entities and a button keeps the paths between them; select some, and one keeps only those. And the box above them all: exclude instead of hide. Hidden entities stay in the Working Set, out of view. Excluded ones leave it, with their links, and later expands do not bring them back.
- Chip: Clean up · Exclude instead of hide
- Action: point at *Hide … with fewer than … events* and its Events stepper, the per-type buttons, then select two entities to show *Keep paths between the 2 selected* and *Keep only the 2 selected* ▸ clear the selection ▸ point at *Exclude instead of hide*. [check in UI: whether per-type buttons show on this Working Set]
- Impact: mark each button as the voice names it.

### S05 · Keep, and a note
- Voice: Some entities prove the point. Click C-14 and press K. Keep is one step, and from now on a clean-up or an exclusion can never take it away. The Selected entity section shows who it is, and the note recorded earlier, with its step number. Type a new note: three of these visits were joint with the contractor C-22. Press Enter, then Annotate. A note changes nothing on the graph; it is part of the record.
- Chip: Keep · K · Selected entity · Annotate
- Action: Shift+click `C-14`, `TX-007`, `TX-019` ▸ K ▸ click `C-14` ▸ Selected entity ▸ hold on the notes list ▸ Note `Three of these visits were joint with C-22` ▸ Annotate.
- Impact: mark the keep step's result line, then the new note under Selected entity.

### S06 · Leave out, before it arrives
- Voice: Next we will look for the other contractors who visited these premises. One of them, C-27, visits more premises than anyone in the city: it simply does the most work. Decide now that it is not part of this question. Open More steps. In the Scope list, type C-27, and press Leave out. That is one exclude step, with the reason left out of the investigated set, and it works before the entity is even in the Working Set.
- Chip: More steps · Scope list · Leave out
- Action: More steps ▸ Scope list ▸ Entity ids `C-27` ▸ Leave out ▸ hold on the result line.
- Impact: mark the result line.

### S07 · Expand, and a busy entity
- Voice: Now look for the other contractors who visited these premises. In the Expand filter, start from the whole Working Set, and follow only the visited by links. Under Fan-out and busy, set Most neighbours per entity to twenty: without a limit, this Space would ask a second person to approve. For this lesson only, set the Busy limit to fifty. Press Expand. Contractors arrive; the busiest of them link to more premises than the limit, so they are held, with a badge and a Busy entities fold of their own.
- Chip: Expand · Busy limit · Most neighbours per entity
- Action: right-click `C-14` ▸ Select neighbours ▸ Expand filter: Link kinds only `visited_by` ▸ Fan-out and busy ▸ Most neighbours per entity `20` ▸ Busy limit `50` ▸ Expand. [check in UI: nine contractors arrive, C-27 absent, C-21 held]
- Impact: before/after hold: mark the nine contractors and C-21's held badge.

### S08 · Busy entities, and Include
- Voice: Open Busy entities. Each held entity is shown, but not expanded, with how many it links to against the limit, and Expand anyway, an override you confirm and that is recorded. There is a second way. In the Scope list, Include names entities every later expand goes through, busy or not, and Only these limits expands to the list. Each step records what it used. We do not need the held contractors' hundreds of premises, so leave them held.
- Chip: Busy entities · Expand anyway · Include
- Action: Busy entities ▸ zoom on C-21's row and Expand anyway ▸ More steps ▸ Scope list ▸ point at Include and, after it, *Only these (an expand admits nothing outside the list)*. [check in UI: C-21's badge reads *… 90 … (limit 50) …*]
- Impact: mark the busy row, then Include.

### S09 · Hide or exclude
- Voice: Two more decisions, on any entity you do not need. Select it and press H: Hide takes it off the view, recorded, and it stays in the Working Set. Select another and press X: the Exclude dialog offers from this Investigation only, one recorded step with your reason, or add to a list, so it stays out wherever that list is used. Recent reasons are one press away. An excluded entity leaves with its links.
- Chip: Hide · H · Exclude… · X
- Action: click `C-30` ▸ H ▸ click `C-21` ▸ X ▸ *From this Investigation only* ▸ Reason `Busy across the city; one visit to these premises` ▸ Exclude.
- Impact: before/after hold twice: C-30 hides; C-21 leaves with its links.

### S10 · Outside a time window
- Voice: The last clean-up reads time. The tampering visits fell between December and June. In Outside a time window, set From the first of December and To the first of July, or press seven, thirty or ninety days. Press Find what is outside the window. It reads the Dataset now, and finds the entities none of whose links has an event in that window. The button below says how many. Press it: one hide, and its reason names the window. Kept entities stay, whatever the window says.
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
