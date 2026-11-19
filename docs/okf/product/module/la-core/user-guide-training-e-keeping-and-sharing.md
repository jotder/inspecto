---
type: Guide
title: "Inspecto LA training videos — E · Keeping and sharing your work: narration scripts"
description: "The spoken script of the training episodes in group E (Keeping and sharing your work), scene by scene, with the action on screen and the change it causes. Format and status: the overview, user-guide-training-scripts.md."
resource: tools/render-la-demo-video.mjs
tags: [module, la-core, link-analysis, user-guide, training, video]
timestamp: 2026-11-16T00:00:00Z
item: module.la-core
audience: internal
status: current
verified: 2026-11-18 last-edit
code: la/inspecto-la-core
---

# Inspecto LA training videos — E · Keeping and sharing your work

> Narration scripts for group E of the Inspecto LA training series. How to read an episode, the voice and the
> renderer: [the overview](user-guide-training-scripts.md). Draft, not recorded, not reviewed by product; `[check in UI: …]` marks a detail to
> confirm on the live product before recording.

## Episodes in E

| Episode | Title | Edition | Scenes | Spoken words | UI checks open |
|---|---|---|---|---|---|
| [E1](#e1_saving-opening-and-exporting) | Saving, opening and exporting | Basic | 12 | 848 | 5 |
| [E2](#e2_entity-lists-and-identity-facts) | Entity Lists and Identity Facts | Standard | 11 | 805 | 6 |

## E1_Saving-opening-and-exporting

> **E1 · Saving, opening and exporting** · Basic · Utilities Space · the meter-tampering contractor · 7 min · user guide
> §10

- **Objectives:** use every item of the File menu; find a saved view with the Open dialog's search and filters; tell a
  saved view from a snapshot and say which is evidence; export a graph in the right format for the job.
- **Before:** a fresh unzip of `la-basic.zip`, the Utilities Space, Link Analysis open on an empty canvas (the *Start a
  graph* launchpad).
- **Covers (plan §5):** 5.2a the File menu (New, Open… and its dialog: Saved views, Search name or id, Dataset, Sort by,
  Changed, the preview, Open, Rename, Duplicate, Delete, Version history; Save view, Save view as (Name, Description, the
  *Not evidence* line), Save snapshot… (Title, Description, Frozen content, Attach to a Case (optional)), Import or export,
  Close, Delete…), Export (PNG, SVG, JSON, GraphML); the saved-view chip; *A view re-reads live data; a snapshot is
  frozen.* Vocabulary: **Snapshot** (first use).
- **Try it:** with nothing drawn, open the File menu. Which items are greyed, and what reason does each give? *Answer: Save
  view, Save view as and Save snapshot… say Draw a graph first.; Close and Delete… say what they need open first (expected:
  Open an Investigation or saved view first.).*
- **Check:** a saved view and a snapshot of the same graph: which one changes when new field visits land tomorrow? *Answer:
  the saved view, because it re-reads live data; the snapshot is frozen, with a fingerprint.*
- **Gotchas:** on Basic, *A new Case, from graph nodes* in Save snapshot… is off with its reason (Cases are Standard), and
  the Open dialog's Investigations tab is locked with one line. Comments, Tags and Share… show only where the Space offers
  them. Saving views needs the Workbench author permission on a signed-in edition; confirm on Basic that the items are on.

### S01 · Keeping what you found
- Voice: This is Inspecto LA training, episode E1: saving, opening and exporting. A graph you have built took decisions: where you started, what you expanded, what you left out. This episode is about keeping that work, finding it again, and handing it on. You will meet the File menu item by item, learn the difference between a saved view and a snapshot, and export a picture for a report. We are in the Basic edition, in the Utilities Space.
- Action: intro card.
- Impact: none.

### S02 · The File menu
- Voice: The File menu is the first icon of the toolbar. New, Open, Explore Dataset and Examples start or find work. Save view, Save view as and Save snapshot keep it. Import or export moves views between Spaces. Close and Delete end it. With nothing drawn, the saving items are greyed, and each says why under it, so a greyed item is never a mystery. Under the snapshot item, one line sums up this episode: a view re-reads live data; a snapshot is frozen.
- Chip: File
- Action: File ▸ hold on the open menu ▸ zoom on a greyed item's reason line and the line *A view re-reads live data; a snapshot is frozen.*. [check in UI: the greyed items and their reasons on an empty canvas]
- Impact: mark each group as the voice names it.

### S03 · The Open dialog
- Voice: Choose Open. One dialog, with a tab for saved views. In the Basic edition the Investigations tab says in one line what has it. Type in Search name or id, narrow by Dataset, sort by name or by last changed, and limit to views changed today, or in the last seven or thirty days. Click a view, and the preview on the right says its Dataset, when it last changed, and its Industry pack. Pick the meter tampering view, and press Open.
- Chip: File · Open… · Saved views
- Action: File ▸ Open… ▸ Search name or id `meter` ▸ Sort by: Last changed ▸ Changed: Any time ▸ click `meter_tampering_contractor` ▸ hold on the preview ▸ Open. [check in UI: the Investigations tab's locked line on Basic]
- Impact: mark the search box, the filters, then the preview.

### S04 · A view is a start, read again
- Voice: The view draws premises and the contractors who visited them or closed their orders. Search for the contractor C-14 to find it among the many nodes drawn. The orange notice says the result was cut short at the canvas limit, never a silent gap. What a saved view keeps is the recipe: the Dataset and mapping, the start, the layout, the display options, the filters and any Entity Lists it uses. Opening it tomorrow draws the recipe again, over the data as it is then. That is useful, and it is why a view is not evidence.
- Chip: Saved view · re-reads live data
- Action: Search nodes `C-14` ▸ wait for the canvas to centre. [check in UI: whether the view is cut short at the canvas limit, and that C-14 shows 36 premises]
- Impact: mark C-14 and its premises.

### S05 · Save view as
- Voice: Keep this as a new view. Choose Save view as. Give it a Name, contractor C-14, and a description that says why you kept it. Above the button, one line names the lists this graph uses, and one says plainly: not evidence, because a saved view re-reads live data. Press Save view. The view's name now sits beside the title as a chip. Click it any time for its details, rename it, or describe it.
- Chip: File · Save view as · Name
- Action: File ▸ Save view as ▸ Name `Contractor C-14` ▸ Description `C-14's premises: 22 under TX-007 and TX-019 read low after a visit` ▸ Save view ▸ point at the saved-view chip beside *Link Analysis*.
- Impact: mark the *Not evidence* line, then the chip.

### S06 · Save view
- Voice: Change something: pick another layout. Save view now saves over the open view, with no questions, because it already has a name. On an edition where people sign in, saving views needs the Workbench author permission, and without it the item is greyed and says so. A view someone shared with you to view only cannot be saved over; Save view as makes your own copy.
- Chip: File · Save view
- Action: Layout ▸ a layout that is not greyed at this size (Grid or Circular) ▸ File ▸ Save view ▸ hold on the confirmation. [check in UI: which layouts are offered at this graph's size, and the confirmation line after Save view]
- Impact: mark the confirmation.

### S07 · Save snapshot
- Voice: Now evidence. A snapshot is a frozen copy of what is on screen, sealed with a fingerprint, and it never changes. Choose Save snapshot. Give it a Title and a description. The dialog lists exactly what it will freeze: the subgraph, with its count of nodes and links, the filter, where it came from, the layout, and the fingerprint, computed on save. Attaching it to a new Case is off here, with its reason: Cases come with the Standard edition. Press Save snapshot.
- Chip: File · Save snapshot… · Fingerprint
- Action: File ▸ Save snapshot… ▸ Title `C-14 premises, as found` ▸ Description ▸ hold on *Frozen content* ▸ point at *A new Case, from graph nodes* (off, with its reason) ▸ Save snapshot ▸ hold on the line under the canvas.
- Impact: mark *Fingerprint*, then the confirmation *Snapshot “…” saved and sealed as evidence.*.

### S08 · View or snapshot?
- Voice: So which do you keep? A saved view when you want to come back and see the same question over today's data: it follows the data as new visits arrive. A snapshot when you need to show what you saw, as it was: anyone can check its fingerprint later. A view is a bookmark. A snapshot is a photograph with a seal on it.
- Chip: Saved view · Snapshot
- Action: side by side: the saved-view chip and the snapshot's confirmation line.
- Impact: mark both.

### S09 · Export
- Voice: To put the graph in a report or another tool, use Export, on the toolbar. Four formats, each for a job. PNG is a picture, ready for a slide. SVG is a drawing that stays sharp at any size, for print. JSON is the graph's data, nodes and links with their attributes. GraphML is the standard format other graph tools open. An export is a copy, not evidence: for that, there is the snapshot, and in Standard, the Dossier.
- Chip: Export · PNG · SVG · JSON · GraphML
- Action: Export ▸ PNG ▸ Export ▸ hover SVG, JSON, GraphML.
- Impact: mark each format as the voice names it.

### S10 · Managing views
- Voice: Back in File, Open. With a view picked, its actions sit in the dialog's bottom bar. Rename and Duplicate do what they say. Delete asks first. Version history lists every saved version with when it was saved. Where your Space offers them, Comments, Tags and Share sit beside them. And File, Import or export moves views between Spaces as a file: export one here, import it in another Space.
- Chip: Open… · Rename · Duplicate · Version history · Import or export
- Action: File ▸ Open… ▸ click `Contractor C-14` ▸ Version history ▸ close ▸ Duplicate ▸ close the dialog ▸ File ▸ Import or export ▸ hover its items. [check in UI: the Import or export menu's items on Basic]
- Impact: mark the bottom bar's buttons, then the Import or export menu.

### S11 · Close and Delete
- Voice: Close puts the canvas back to the start, and forgets the open view, so a reload does not reopen it. Delete names what it will delete, here the view Meter tampering contractor, and asks first. We will not confirm: a deletion is for a view you no longer need.
- Chip: File · Close · Delete…
- Action: File ▸ Close ▸ File ▸ Open… ▸ the duplicate ▸ Open ▸ File ▸ Delete… ▸ hold on the question ▸ confirm.
- Impact: mark the question naming the view.

### S12 · Try it
- Voice: Your turn. Close everything, so nothing is drawn, and open the File menu. Which items are greyed, and what reason does each give? The answer is in the description. Next, in E2, Entity Lists and Identity Facts: keeping sets of entities, and saying that two identifiers are one.
- Action: outro card: *Try it* and *Next: E2 · Entity Lists and Identity Facts*.
- Impact: none.

## E2_Entity-Lists-and-Identity-Facts

> **E2 · Entity Lists and Identity Facts** · Standard · Mobile Money Space · the device farm · 7 min · user guide §11.7,
> §12

- **Objectives:** create an Entity List with a purpose, entries, ranges and an expiry, and retire one; seed and exclude
  an Investigation by list; say what Register as Dataset exposes; read the Identity Facts chain and assert, find and
  retract an identity.
- **Before:** a fresh unzip of `la-standard.zip`, the Mobile Money Space, signed in as Demo Analyst. Seeded state:
  the lists *STRUCT wallets* (a `STRUCT-` prefix and HUB-01 on a 30-day expiry) and *Device farm wallets* (FARM-1…9),
  the Investigation *Mule hub and its chain*, and one Identity Fact (FARM-1 and FARM-2, *same handset DEV-FARM-01*), from
  `tools/seed-la-mm.mjs`.
- **Covers (plan §5):** 5.5 the Entity Lists page (Entity Lists and Identity Facts tabs; New list…: Title, Purpose, Entity
  Type, Reason, Create list; Edit entries…: Add / Remove, entries one per line (value, prefix, range, CIDR block),
  Expires, Reason; Retire; Register as Dataset); 5.4 Settings ▸ Entity Lists in an open Investigation (Exclude by list,
  Seed by list); Identity Facts (Chain verified, Head, Head hash, Resolved groups, Import identities); Settings ▸
  Identities (Assert same entity, Find group, Retract). Vocabulary: **Entity List** (first use).
- **Try it:** in Settings ▸ Identities, find the group of FARM-3. How many members does it have, and why? *Answer: one,
  itself: no assertion joins it to anything, so it resolves to itself (Joined by no assertion). Sharing a handset is a
  link, not an identity.*
- **Check:** what is the difference between an exclusion list and a Known good list? *Answer: an exclusion list leaves
  its members out of analyses and expands; a Known good list records that its members are legitimate, without hiding
  them.*
- **Gotchas:** *Exclude by list* and *Seed by list* appear only while an Investigation is open, so this episode records
  on Standard (the plan listed E2 as Basic; the page, New list…, Edit entries… and Retire are in Basic too). Identity
  Facts need the Manage Incidents and Cases permission even to read. *Register as Dataset* needs the permission to author
  Datasets; if Demo Analyst does not see it, S08 switches to Demo Admin. The household of three (W-1097, W-1105, W-1109,
  one national id) and the family phone (W-1232, W-1283, W-1331, W-1355, one handset) are the benign look-alikes, from
  `spaces/_templates/la-mobile-money/data`.

### S01 · Sets you keep
- Voice: This is Inspecto LA training, episode E2: Entity Lists and Identity Facts. Some things you know before you open a graph: these wallets are under watch, those agents are known good, that biller should be left out of every analysis. An Entity List is a named set of entities you keep, for one purpose, and every change to it is recorded with who, when and why. An Identity Fact says that two identifiers are one entity. We are in the Standard edition, in the Mobile Money Space, signed in as the analyst.
- Action: intro card.
- Impact: none.

### S02 · The Entity Lists page
- Voice: Entity Lists has its own page in the menu on the left, with two tabs: Entity Lists, and Identity Facts. Each list shows its title, its purpose, the Entity Type of its members, and how many it holds. This Space has two already: the structuring wallets, and the nine wallets of the device farm, to watch. A list can start a graph, or leave its members out of one.
- Chip: Entity Lists
- Action: the left menu ▸ Entity Lists ▸ hold on the two lists. [check in UI: the member counts shown]
- Impact: mark each list's line *purpose · Entity Type · members*.

### S03 · New list
- Voice: Make one. Press New list. Give it a Title: benign shared wallets. Purpose, in plain words: watch these, exclusion, known good, or block. Choose known good: these are legitimate, and we want to say so. Entity Type: wallet. And a Reason, because every list says why it exists. Press Create list. It appears, empty, with its own buttons.
- Chip: New list… · Purpose · Known good
- Action: New list… ▸ Title `Benign shared wallets` ▸ Purpose *Known good* ▸ Entity Type *Wallet* ▸ Reason `A household on one national id, and a family phone` ▸ Create list.
- Impact: mark the new list's line.

### S04 · Entries, ranges and expiry
- Voice: Press Edit entries. Choose Add, and type the entries, one per line. An entry can be a value, a prefix, a range written low to high, or a network block. Add the household's three wallets and the family's four. Expires stays blank: these are permanent. Give a reason, and save. The structuring list shows the other options: its entries are a prefix, every wallet starting STRUCT, and the hub HUB-01 held for thirty days, which then drops out by itself.
- Chip: Edit entries… · Add · Expires
- Action: Edit entries… ▸ Add ▸ entries `W-1097`, `W-1105`, `W-1109`, `W-1232`, `W-1283`, `W-1331`, `W-1355` ▸ Expires blank ▸ Reason `Benign look-alikes of the device farm` ▸ save ▸ *STRUCT wallets* ▸ Edit entries… ▸ hold on *Current range and expiring entries* ▸ Cancel. [check in UI: the save button's label in Edit entries]
- Impact: mark the member count rising to 7, then the prefix and the expiring entry.

### S05 · Retire
- Voice: Lists are never silently deleted. Retire takes one out of use, with a reason, and keeps its history, so a Dossier that once used it can still say what it held. A retired list is marked as such, and can no longer be edited, seed or exclude anything. On a governed list, every change also waits for a second approver. We keep ours: the next scene uses it.
- Chip: Retire
- Action: hover Retire on *Benign shared wallets*; do not press.
- Impact: mark the Retire button.

### S06 · Seed by list
- Voice: Lists work inside an Investigation. Open Link Analysis, and from the File menu, Open, the Investigation Mule hub and its chain. In Settings, under Entity Lists, each list now has two more buttons. Seed by list adds the list's members to the Working Set as one recorded step. Press it on the device farm wallets. The nine wallets join the Investigation, and the step log names the list they came from.
- Chip: Settings · Entity Lists · Seed by list
- Action: Link Analysis ▸ File ▸ Open… ▸ Investigations ▸ *Mule hub and its chain* ▸ Settings ▸ Entity Lists ▸ *Device farm wallets* ▸ Seed by list ▸ hold on the notice *Seeded … from “Device farm wallets”*. [check in UI: the Entity Lists section in the Settings dialog shows these buttons with the Investigation open]
- Impact: before/after hold: FARM-1…9 appear on the canvas; mark them.

### S07 · Exclude by list
- Voice: Exclude by list does the opposite. On the benign list, press it. It asks first, in a sentence: every entity of the Working Set, and every one a later expand would admit, whose key is on this list, as the list is now, is excluded. Confirm. If the household's wallets turn up in a later expand, they stay out, and the step log says which list kept them out. One list, applied the same way every time.
- Chip: Exclude by list
- Action: *Benign shared wallets* ▸ Exclude by list ▸ hold on the dialog *Exclude by Entity List* ▸ Exclude ▸ open the dock's Step log. [check in UI: the step's line in the Step log]
- Impact: mark the confirmation's sentence, then the new step row.

### S08 · Register as Dataset
- Voice: One more button exists: Register as Dataset. It turns a list into a Dataset that other tools can join to, such as a report. It appears only for people who may author Datasets, so it is not on this analyst's screen. Before it registers anything it warns: anyone who can read Datasets can read the list's raw keys, because masking does not apply to a Dataset. Read that warning before you press it.
- Chip: Register as Dataset
- Action: Register as Dataset on *Benign shared wallets* ▸ hold on the confirmation ▸ Cancel. [check in UI: which Demo User sees the button; switch to Demo Admin if the analyst does not]
- Impact: mark the warning's sentence.

### S09 · Identity Facts
- Voice: Now identities. On the Entity Lists page, open the Identity Facts tab. Every statement that two identifiers are one entity is a fact in a chain, and the server re-checks the whole chain on every read: Chain verified. Below it, the head: the number of the latest fact, its hash, and how many groups the facts resolve. Keep a copy of the head hash: if anyone cut facts off the end, it would no longer match. Import identities takes many pairs at once from a mapping Dataset.
- Chip: Identity Facts · Chain verified · Head hash
- Action: Entity Lists ▸ Identity Facts ▸ hold on *Chain verified* ▸ zoom on Head, Head hash, Resolved groups ▸ point at the Import identities form (Mapping Dataset, Column A, Column B, Type A (optional), Type B (optional), Reason).
- Impact: mark *Chain verified*, then the head hash.

### S10 · Find a group, and retract
- Voice: In Link Analysis, Settings, Identities, find the group of the wallet FARM-1. It holds FARM-1 and FARM-2, joined by one assertion: same handset. But a shared handset is a link, not proof that two wallets are one person; that is the very question the farm raises. Retract takes that assertion back: the group would split, and a retraction is a new fact, so the chain grows by one and still verifies. We leave ours as it is. Assert same entity works the same way: two identifiers, a reason, and nothing is written until you confirm.
- Chip: Settings · Identities · Find group · Retract
- Action: Settings ▸ Identities ▸ Identifier type *wallet* ▸ Identifier `FARM-1` ▸ Find group ▸ hold on *Assertions that joined it* ▸ Retract ▸ confirm ▸ Identity Facts tab ▸ the head number has gone up by one ▸ point at the *Assert same entity* form. [check in UI: the identifier type's label, and that Retract asks first]
- Impact: before/after hold: the group of two becomes one; mark the head's new number.

### S11 · Try it
- Voice: Lists for what you know, recorded with a reason; facts for who is who, in a chain that checks itself, and that you can always take back. Your turn. In Identities, find the group of FARM-3. How many members does it have, and why? The answer is in the description. Next, in F1, signing in, roles, and your first Investigation.
- Action: outro card: *Try it* and *Next: F1 · Signing in, roles and your first Investigation*.
- Impact: none.
