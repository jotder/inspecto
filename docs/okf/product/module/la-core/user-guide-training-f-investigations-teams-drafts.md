---
type: Guide
title: Inspecto LA training videos — F · Investigations (Standard): narration scripts — Episodes F4-F6 — step log, teams and Drafts
description: Part 2 of 3 of Inspecto LA training videos — F · Investigations (Standard): narration scripts: F4_Step-log-undo-re-order-and-replay; F5_Teams-members-four-eyes-reviewers-and-outsiders; F6_Drafts-work-in-parallel
tags: [module, la-core, link-analysis, user-guide, training, video]
timestamp: 2026-11-16T00:00:00Z
---

# Inspecto LA training videos — F · Investigations (Standard): narration scripts — Episodes F4-F6 — step log, teams and Drafts

Part 2 of 3 of [Inspecto LA training videos — F · Investigations (Standard): narration scripts](user-guide-training-f-investigations.md) · previous: [Episodes F1-F3 — roles, expand, clean-up](user-guide-training-f-investigations-first-graph.md) · next: [Episodes F7-F9 — masked data, evidence and action](user-guide-training-f-investigations-evidence.md).

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
- Voice: From the File menu, Open, choose the Investigation Sanctions exposure two ownership hops down. A bank customer, CUS-00528, sits below a person whose name nearly matches an entry on a sanctions list, SL-0137. Open the Step log in the dock. The first row is the start from the list entry and the customer; the rows after it are the expand, a note, a time window and a snapshot marker. The expand brought in the matched person and the customer's company. Look closely, and something is missing: the company between them, the one that owns half of the customer's company, is not on the graph yet.
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
- Voice: The third group is Compare. Threshold keeps only the entities whose degree, links or events fall inside a band you set, a minimum, a maximum, or both. Use it to set aside the barely linked, or the busiest. Compare asks a different question: what changed between two windows of time? Set Window A to the years before 2025 and Window B to the last year, and Compare and seal records the difference as a step, so it is part of the evidence, not a remark. We leave both forms unsent here.
- Chip: More steps · Threshold · Compare
- Action: More steps ▸ Threshold (open: Measure, Minimum, Maximum, Apply threshold; do not apply) ▸ Compare ▸ Window A 2016-01-01 to 2025-01-01, Window B 2025-10-01 to 2026-10-01 ▸ Compare and seal. [check in UI: the field names in Window A and B, and what the result shows for these windows; reword the last sentence to it]
- Impact: mark the Threshold fields; then the Compare result and its log row.

### S09 · Replay and drift
- Voice: A log is only worth something if it still gives the same answer. Open the Settings gear, Investigation settings, Replay and drift. Press Replay. Replay matches: every step gives the same result. Now tick Compare with current data, and Replay again. Each expand is read again from today's data, and a table compares the rows sealed then with the rows now. Same means nothing moved. Changed would be drift: the data changed since, but the Working Set shown stays the sealed one. A reviewer can do all of this without changing anything.
- Chip: Replay and drift · Replay
- Action: Settings ▸ Investigation settings ▸ Replay and drift ▸ Replay ▸ hold ▸ tick Compare with current data ▸ Replay ▸ hold on the drift table. [check in UI: *Replay matches* and the closing sentence *Every sealed read still matches current data.*]
- Impact: mark *Replay matches*; then the drift table's Drift column.

### S10 · Re-order, as a fork
- Voice: Would the expand have found the same people if the time window had come first? Do not rewrite the record to find out. In the Step log, press Re-order steps. The box says it plainly: re-ordering creates a fork. The original Investigation stays exactly as it is, and a new Investigation is created with the steps in the new order, every expand reading the Dataset again, and it records this one as its parent. Move the time window up above the expand, press Create fork, and Open the parent takes you back.
- Chip: Re-order steps… · Create fork
- Action: Step log ▸ Re-order steps… ▸ *Move step 4 up* twice ▸ Create fork ▸ hold on the Lineage line ▸ Open the parent. [check in UI: the Lineage line *Fork of sanctions-exposure: its steps re-applied in the order …*]
- Impact: mark the alert *Re-ordering creates a fork*; then the Lineage line in the new Investigation.

### S11 · Try it
- Voice: So: every row is permanent, Undo is a step, Replay proves the log, and a new order is a fork. Your turn. In the same Investigation, Connect the list entry SL-0137 and the customer CUS-00528. What does the Connection column say, and why? The answer is in the description. Next, in F5, teams: members, four-eyes approval, reviewers and outsiders.
- Action: outro card: *Try it* and *Next: F5 · Teams*. [check in UI: the Try it answer reads *Not connected within 3 hops*]
- Impact: none.

## F5_Teams-members-four-eyes-reviewers-and-outsiders

> **F5 · Teams: members, four-eyes, reviewers and outsiders** · Standard · Utilities Space · the meter tampering · 7 min ·
> user guide §11.4, §11.5, §14

- **Objectives:** grant and revoke a member and say what each role may do; recognise an expand held for a second person
  and approve or deny it as that person; read an Investigation as a reviewer; say what an outsider sees and why.
- **Before:** a fresh unzip of `la-standard.zip`, the Utilities Space, the seeded Investigation *Meter tampering after one contractor's visits*
  (`tools/seed-la-utility.mjs`: members analyst and admin as leads, reviewer and manager as reviewers; one unbounded expand by
  the analyst, held for a second person). Sign in as Demo Analyst first; the episode signs in as Demo Manager, Demo
  Reviewer and Demo Outsider in turn (user menu ▸ sign out, off camera between scenes).
- **Covers (plan §5):** 5.1 user menu, sign-in as each Demo User; 5.3 Members (Grant, Revoke, roles lead / analyst /
  reviewer), the held line, *N to approve*, Approve, Deny (Reason, Deny request), Oversight and approvals; Read-only
  banner; 5.5 Settings ▸ Investigation access.
- **Try it:** sign in as Demo Reviewer and open *Meter tampering after one contractor's visits*. Name two things you can still do, and the
  sentence that says why Expand is missing. *Answer: read it, Replay it, build and verify a Dossier; the Read-only banner
  says Changing an Investigation needs the Manage Incidents and Cases permission.*
- **Check:** who may approve a held expand? *Answer: someone other than the person who asked, holding Approve link
  expansions; where the Investigation has members, only a lead or a reviewer.*
- **Gotchas:** without sign-in (Basic) nobody can approve, so four-eyes is Standard and up. The Space's four-eyes limit
  is 2,000 neighbours of each entity (`four_eyes_fan_out_above` in Utilities's `link-analysis.toon`); the seeded request
  asks for every neighbour, so it is held. The Utilities Space also holds a second Investigation with its own held
  request: open the right one. Approving runs the expand for real: record S07 last of the manager's scenes.

### S01 · Investigations are team work
- Voice: This is Inspecto LA training, episode F5: teams. An Investigation is rarely one person's work. Someone leads it, someone checks it, and some steps are too big to take alone. In this episode you will see who is on an Investigation and what each role may do, add and remove a member, ask for a large expand that waits for a second person, approve it as that person, and see the same Investigation through the eyes of a reviewer and of someone who is not on it at all. We are in the Standard edition, in the Utilities Space.
- Action: intro card.
- Impact: none.

### S02 · Who is on it
- Voice: Signed in as the analyst, open the Investigation Meter tampering after one contractor's visits: two transformers, one contractor, and the premises between them. Open the Investigation actions menu and choose Members. Four people, each with a role. A lead changes the Investigation and decides who is on it. An analyst works on it. A reviewer reads it, and may decide approvals. The analyst opened it, so the analyst is its owner. The manager is here as a reviewer: that is the second pair of eyes.
- Chip: Investigation actions · Members…
- Action: File ▸ Open… ▸ Investigations ▸ *Meter tampering after one contractor's visits* ▸ ⋯ Investigation actions ▸ Members. [check in UI: the four rows analyst lead (owner, you), admin lead, reviewer reviewer, manager reviewer]
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
- Action: rail ▸ Settings ▸ My settings ▸ Investigation access ▸ scroll to *Meter tampering after one contractor's visits*. [check in UI: the card shows *You: lead*, the four members and *Live embed links: None.*]
- Impact: mark the *You: lead* chip and the member rows.

### S06 · The approver's view
- Voice: Now sign out, and sign in as the manager. Open the same Investigation. Under the Dataset line, a badge says one to approve. The banner says what the manager may do here: read it and decide approvals; only a lead changes its steps. And where the analyst saw a waiting line, the manager sees the decision. It names who asks, and says in plain words what the expand would do: add every neighbour of each entity, more than the two thousand allowed without a second approval. Two buttons: Approve and Deny.
- Chip: 1 to approve · Approve · Deny
- Action: user menu ▸ sign out ▸ Demo Manager ▸ File ▸ Open… ▸ *Meter tampering after one contractor's visits* ▸ click *1 to approve*. [check in UI: the approver line's exact sentence, and that the banner reads *You review this Investigation: you can read it and decide approvals; only a lead changes its steps.*]
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
- Voice: Last, the outsider: signed in, but admitted to no Space. The home page says so in one sentence: you have no access to any Space; ask your administrator. No Space is listed, and a link into this one answers as if it did not exist, so an outsider cannot even learn that the Investigation exists. To let them in, an administrator gives them a role this Space defines; a lead then grants them a role in the Investigation, as you saw at the start.
- Chip: No access
- Action: sign out ▸ Demo Outsider ▸ hold on the home page ▸ open `/link-analysis?investigation=tampering-case` in the address bar. [check in UI: the home page shows *You have no access to any Space*]
- Impact: mark the sentence.

### S11 · Try it
- Voice: So: leads change, analysts work, reviewers read and decide, and outsiders are not told. Large expands wait for a second person, and every decision is recorded. Your turn. Sign in as the reviewer, and open Meter tampering after one contractor's visits. Name two things you can still do, and the sentence that tells you why Expand is missing. The answer is in the description. Next, in F6, Drafts: working on a side line in parallel.
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
- Voice: Press Work on this Draft, and close the settings. A banner now sits at the top of the Investigation tab: working on a Draft. Steps and Undo go to the Draft, not to the Investigation. Select EMP-0482, and the Selected entity section shows its note, recorded on the Draft. An expand from here would go to the Draft only, and a large one would wait for a second person, as on the main log. We do not expand in this episode, so the Draft keeps its single step.
- Chip: Work on this Draft · Working on a Draft
- Action: Drafts ▸ Work on this Draft ▸ close Settings ▸ zoom on the banner ▸ click EMP-0482 ▸ Expand filter ▸ Link kinds: only `changed_vendor` ▸ Expand. [check in UI: the link kind's label in the filter; that VEN-0264 arrives]
- Impact: mark the banner; before/after hold: VEN-0264 joins the canvas.

### S05 · The Investigation did not move
- Voice: Now go back. In Drafts, press Back to the Investigation. The banner goes, and the canvas and the step log are exactly as they were. That is the promise of a Draft: you can explore freely, and nothing reaches the record until you decide it should. While you are on a Draft, two things are off on purpose. Re-order is not available, and Replay cannot compare a Draft with current data: both are about the record itself.
- Chip: Back to the Investigation
- Action: Settings ▸ Investigation settings ▸ Drafts ▸ Back to the Investigation ▸ close ▸ dock ▸ Step log.
- Impact: before/after hold: VEN-0264 leaves; mark the unchanged last row of the Step log.

### S06 · Meanwhile, the main log moves on
- Voice: In real work, the Investigation does not wait for you. Someone records another step on the main log. Here we do it ourselves: select another of the reports, EMP-2278, and press K, to keep it safe from any later exclusion. That is a step on the main log. Open Drafts again: the Draft now says main moved one step. It was forked from an older point of the record.
- Chip: Keep · K · main moved
- Action: click EMP-0007 ▸ K ▸ Settings ▸ Investigation settings ▸ Drafts. [check in UI: the badge reads *main moved 1 step*]
- Impact: mark the Keep row in the log; then the *main moved 1 step* badge.

### S07 · Conflicts, then Rebase
- Voice: Before a Draft joins the record, it has to sit on top of the latest step. Press Conflicts first. It says what a rebase would do: the step it was based on, where the main log is now, and how many of the Draft's steps would carry over. A Draft step that clashes with what the main log did since is listed as a conflict, with the reason, and a rebase drops it, after asking you. Here there is none. Press Rebase: rebased onto the latest step.
- Chip: Conflicts · Rebase
- Action: Conflicts ▸ hold ▸ Rebase ▸ hold on the notice. [check in UI: *Based on step 7; main is at step 8 (1 behind) · 2 steps would carry over.* and the notice *Rebased onto step 8.*]
- Impact: mark the conflicts report, then the notice.

### S08 · Promote
- Voice: Now decide. Press Promote. Promoted: the Draft's steps are now in the Investigation. The note on EMP-0482 is in the step log, recorded with your name, after the keep. If a Draft contained a large expand, above the Space's four-eyes limit, the promote would not apply anything: it would wait for a second person, exactly like an expand on the main log.
- Chip: Promote
- Action: Promote ▸ hold ▸ close ▸ dock ▸ Step log. [check in UI: the notice *Promoted — the Draft’s steps are now in the Investigation.*; if the expand is held instead, reword the first two sentences to the held notice]
- Impact: before/after hold: VEN-0264 joins the main canvas; mark the promoted rows.

### S09 · Discard, and the limits
- Voice: A hunch that leads nowhere is discarded. To see how, fork a new Draft: type a name, cash out, and press Fork a Draft. Now press Discard. It asks first, and means it: the Draft's steps are dropped, they are not promoted, and this cannot be undone. Confirm, and the Draft is gone, and the Investigation never knew it. A few limits keep Drafts tidy. One live Draft per person on an Investigation. A Draft left alone goes to sleep, and after some days it expires. And a reviewer's role cannot change Drafts at all.
- Chip: Discard · Fork a Draft
- Action: point at Discard on a Draft row (do not confirm) ▸ point at Name this Draft (optional) and Fork a Draft. [check in UI: the Discard dialog's sentence, opened and cancelled]
- Impact: mark the dialog's last sentence, then the Fork a Draft button.

### S10 · Try it
- Voice: So: fork a Draft to try something, work on it, check its conflicts, rebase, and promote it or discard it. The record only ever gets what you decided to keep. Your turn. Fork a Draft of your own named cash out, expand EMP-0482 by every kind of link it has, and look at the Draft's Working Set. Then discard it. What happened to the Investigation? The answer is in the description. Next, in F7, masked data, audited reveal, and resolving identities.
- Action: outro card: *Try it* and *Next: F7 · Masked data and identities*.
- Impact: none.
