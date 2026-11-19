---
type: Guide
title: Inspecto LA training videos — F · Investigations (Standard): narration scripts — Episodes F7-F9 — masked data, evidence and action
description: Part 3 of 3 of Inspecto LA training videos — F · Investigations (Standard): narration scripts: F7_Masked-data-reveal-and-identity-resolution; F8_Evidence-coverage-Dossier-and-sealed-bundle; F9_From-finding-to-action
tags: [module, la-core, link-analysis, user-guide, training, video]
timestamp: 2026-11-16T00:00:00Z
item: module.la-core
audience: internal
status: current
verified: 2026-11-19 last-edit
code: la/inspecto-la-core
---

# Inspecto LA training videos — F · Investigations (Standard): narration scripts — Episodes F7-F9 — masked data, evidence and action

Part 3 of 3 of [Inspecto LA training videos — F · Investigations (Standard): narration scripts](user-guide-training-f-investigations.md) · previous: [Episodes F4-F6 — step log, teams and Drafts](user-guide-training-f-investigations-teams-drafts.md).

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
- Voice: The quickest way to merge is on the canvas: select two entities, and press Merge identities on the canvas toolbar. On a masked wallet it stays greyed, and the tooltip says why: a masked entity cannot be merged, only its alias is visible, and an identity needs the real value. Even after a reveal, the graph holds aliases. In a Space that masks, the merge is made in the Identities form, by someone who knows the real values.
- Chip: Merge identities…
- Action: Shift+click two wallet nodes ▸ hover Merge identities… on the canvas toolbar. [check in UI: the tooltip *A masked entity cannot be merged: only its alias is visible, and an identity needs the real value.*]
- Impact: mark the greyed button and its tooltip.

### S10 · Assert, then show it
- Voice: That form is Assert same entity. You choose the first identifier type, wallet, and give its value, then the second type and value, and a reason, such as same handset. Pressing Assert records that the two are one entity, and the group then holds every identifier that shares a member. Because an Investigation is open, the form then asks whether to show the merge there, and Add a resolve step seals it as a step. And it is reversible: retract the assertion, and the group splits. We do not press it here, so the seeded group stays as it is.
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
- Voice: Each Measure has a Watch button. Press Watch on Entities. The dialog proposes a name, and asks when to fire: greater than, at least, less than, at most a threshold. Keep greater than, and set the threshold to eighteen, one above today's seventeen, so the rule fires if the Working Set grows. Severity: Info, Warning, or Critical, which also opens an Incident when it fires. Warning is chosen. Press Create Alert Rule. The rule is armed, and a fired Alert discloses the Investigation, never the entities.
- Chip: Watch · Create Alert Rule
- Action: Watch on Entities ▸ Fire when the value is: greater than ▸ Threshold: the current value ▸ Severity: Warning ▸ Create Alert Rule ▸ hold ▸ Done. [check in UI: *Alert Rule armed* with *it would not fire now*, and the disclosure sentence]
- Impact: mark the severity hint *Also opens an Incident when it fires.*; then *Alert Rule armed*.

### S04 · Watching live data
- Voice: That rule watches the sealed Working Set. To watch the live data, use a value Measure. In the toolbox, Whole Dataset, under What looks unusual, open Value Measures. It reads the whole Dataset for a window of up to thirty-one days, not the graph on screen. The rule bound to this Investigation, mm-structuring, is one that watches the live data: cash-ins just under the five thousand limit, many of them, from many payers. A scheduled sweep reads the Dataset with the owner's access, counts only, and stops on record if that access changes. Only the Investigation's owner can enable it.
- Chip: Value Measures · Standing detection
- Action: toolbox ▸ Whole Dataset ▸ *What looks unusual?* ▸ Value Measures ▸ Bound Alert Rules ▸ Standing detection ▸ point at Watch this Measure. [check in UI: *mm-structuring — CRITICAL (value Measure over the live Dataset)* and *Status: Enabled.*]
- Impact: mark the bound rule, its status, and the Watch this Measure button.

### S05 · The Alerts page
- Voice: That rule has fired, many times. In the rail, open Alerts. Each fired Alert is a row: when, the severity, the Alert Rule, what it watches, and its value. On each row, two actions: What happened, which explains it when the intelligence module is installed, and Open Investigation. Here the module is not installed, so the dialog says so, once, in words. Below the Alerts are the Alert Rules themselves: Mm structuring, Critical, at least one.
- Chip: Alerts · Acknowledge · Resolve
- Action: rail ▸ Alerts ▸ the *mm-structuring* row ▸ What happened ▸ close ▸ Acknowledge ▸ scroll to Alert Rules. [check in UI: the row's columns and that Evaluate now is absent for Demo Analyst]
- Impact: before/after hold: the state OPEN → ACKNOWLEDGED; mark the new rule under Alert Rules.

### S06 · Back to the Investigation
- Voice: A fired Alert names the Investigation, not the entities. To see who breached, press Open Investigation on its row, the second action. It opens here, where your access and the masking apply, so an Alert never shows more than you may see.
- Chip: Open Investigation
- Action: Alerts ▸ the row ▸ Open Investigation ▸ hold ▸ back ▸ Resolve. [check in UI: the action's hint *Open Investigation mm-structuring-case — it lists the breaching entities*]
- Impact: mark the Investigation opening; then the state RESOLVED.

### S07 · Attach to a Case
- Voice: A finding someone else must act on goes to a Case. On the canvas toolbar, press Attach to Case. It seals what is on screen as a snapshot with a fingerprint, and attaches it to a Case, in one step. The dialog proposes a title, and offers an existing Case, or a new Case from the selected nodes. That one is greyed here, and says why: these wallets are masked, and a Case needs real identifiers, so you link the Investigation to the existing Case instead. We do not seal anything in this lesson.
- Chip: Attach to Case · Seal and attach
- Action: canvas toolbar ▸ Attach to Case ▸ Snapshot title `funnel at step 8` ▸ An existing Case ▸ pick *Structuring through HUB-01* ▸ point at the greyed *A new Case, from the selected nodes* and its reason ▸ Seal and attach. [check in UI: the reason sentence, and the toast *Sealed “funnel at step 8” (…) and attached it to …*]
- Impact: mark the reason; then the toast.

### S08 · The Case, from both sides
- Voice: From the Investigation's side: Investigation settings, Case. It is linked to that Case, so the Case's owner and assignee can read this Investigation, and only its leads change it. From the entity's side: open the hub's details and unfold Case history. Every Incident and Case that names this entity is listed, newest first, with how it got here: via this Investigation. Show filters all, open or closed.
- Chip: Case · Case history
- Action: Settings ▸ Investigation settings ▸ Case ▸ close ▸ click the hub twice ▸ Case history ▸ Show: open. [check in UI: *Linked to Case …* and the Case listed under the hub's alias]
- Impact: mark *Linked to Case*; then the Case history row.

### S09 · Pin to a Widget
- Voice: A manager may want this funnel on a dashboard. Where the host has a Widget library, Investigation settings offers Pin to a Widget: name it, choose what to show, entities, links or exclusions, Frozen as it is now or Live, which follows the Investigation, and Save Widget. This install has no Widget library, so the fold is not shown. The Widget is added to a dashboard in the Inspecto console, which this app does not show.
- Chip: Pin to a Widget · Frozen · Live
- Action: Settings ▸ Investigation settings ▸ Pin to a Widget ▸ Widget name `mm-funnel-entities` ▸ Show: Entities ▸ Frozen ▸ Save Widget. [check in UI: the fold is shown in this app; the notice *Widget saved*]
- Impact: mark the notice.

### S10 · Share a read-only link
- Voice: Someone without an account can be given a read-only, sealed view. Investigation settings, Share and embed. On this server embed links are turned off, and the panel says so in one sentence: ask an administrator. Where they are on, a lead with Investigation sharing creates a link with a label, what it shows, its masking, which can be stricter than the Space and never looser, and a lifetime. The link is shown once, so you copy it then, and Revoke cuts it off at once.
- Chip: Share and embed · Create link · Revoke
- Action: Share and embed (as Demo Analyst, hold on the sentence) ▸ sign out ▸ Demo Admin ▸ the same Investigation ▸ Share and embed ▸ Create link ▸ Label `auditor` ▸ What the link shows: *Entities and links only* ▸ Lifetime (hours) `24` ▸ Create link ▸ Copy ▸ Done ▸ Revoke. [check in UI: whether embed links are on in this build; if the panel says *Embed links are turned off. Ask an administrator.*, the voice says so instead of the minting]
- Impact: mark *Copy now: it is not shown again.*; then the link row and its Revoke.

### S11 · A link from another system
- Voice: Last, the other direction: a case manager or a fraud product links straight into Inspecto LA. The link opens the Open in Inspecto LA page. It checks the link first, and never moves on by itself. It names the Investigation, and the entity by its alias, because a masked value never travels in an address. A broken link, such as an entity without its type, gets one sentence saying what is wrong.
- Chip: Open in Inspecto LA
- Action: address bar `/open?investigation=mm-structuring-case&entity=wallet:<the hub's alias>` ▸ hold ▸ Reveal the value ▸ hold ▸ address bar `/open?entity=HUB-01` ▸ hold. [check in UI: the Entity line *wallet: masked:… masked, shown by its alias*; the broken link's sentence *The entity in this link is not in the form “<type>:<key>” …*]
- Impact: mark the page's three buttons; the revealed value; then the error box *This link cannot be opened*.

### S12 · Try it
- Voice: So: a Measure watched by an Alert Rule raises an Alert, the Alert leads back to the Investigation, and the evidence goes to a Case. Your turn. Watch the Links Measure, set at least, and a threshold equal to today's value. Would it fire now? The answer is in the description. Next, in G1, the Geo map.
- Action: outro card: *Try it* and *Next: G1 · The Geo map*.
- Impact: none.
