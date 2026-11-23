---
type: Reference
title: Inspecto LA case videos v2 - Telecom specs (five cases)
description: The v2 specs of the five Telecom case films (mobile-money structuring, device farm, SIM swap, Wangiri network, dealer commission): case-first arc, decoy, lead as action, voice and chip lines checked against the Space ground truth and the live Standard screens.
resource: tools/la-demo-cases.mjs
tags: [module, la-core, link-analysis, video, case-videos, spec]
timestamp: 2026-11-19T00:00:00Z
item: module.la-core
audience: internal
status: current
verified: 2026-11-19 last-edit
code: la/inspecto-la-core
---

# Inspecto LA case videos v2 - Telecom (five specs)

> **Recording environment (operator, 2026-11-20):** the Standard edition with the fake Demo Auth sign-in and the bundled example Spaces, from a fresh `la-standard.zip` unzip per lane; plan [§4.5](../../../../superpower/2026-11-19-la-case-videos-v2-plan.md).

**Status 2026-11-19: specs, nothing re-recorded.** These five films re-tell the Telecom cases of
[`brochure-video-demo-case-scripts.md`](brochure-video-demo-case-scripts.md) (v1, generated from `tools/la-demo-cases.mjs`)
under the standard of [`2026-11-19-la-case-videos-v2-plan.md`](../../../../superpower/2026-11-19-la-case-videos-v2-plan.md):
a case film, not a tour (§3 arc of seven beats, 4-5 minutes, one decoy, one "what would change our mind", a lead shown as action),
the voice, audio and presentation rules of §4, and the spec format of §5 (it is the training-guide format, so the renderer and the
`episode` recorder read it). Every id and figure is cited from the Space's generator ground truth; every screen statement was
checked on the Standard build as Demo Analyst on 2026-11-19. `[pause 0.6]` is authored silence; `[check in UI: …]` marks a
control or label to confirm when the episode JSON is probed (plan phase P4). Scene titles carry their target seconds.

**Screen truth found while writing (changes v1):**
- **Mobile Money (Structuring, DeviceFarm) is masked for the analyst:** the saved views show `masked:…` aliases ("31 entities
  masked", "16 entities masked"). v1 read the ids aloud. The v2 voice says "the hub", "the agent", "the nine wallets", and the
  story card carries the roles; a Demo Admin reveal beat (as in training episode F7) shows the real ids once.
- **Wangiri (Other Examples) is masked and the saved view opens with a "Result cut short" banner** ("the server stopped reading at
  its row limit; narrow the Dataset or add a filter"): the voice reads the banner and narrows with a start entity.
- **Telecom (SIM swap, dealer commission) is readable:** no masked-entities counter; labels are off above 80 nodes (the
  sweeps name the ids).
- **Investigations in the Telecom Space:** *SIM-swap wallet takeover* and *Unbilled usage at one dealer* (a neighbouring
  story, not the dealer-commission one). The dealer-commission film therefore does not open it; it starts and saves its own.
- **Two v1 figures were wrong:** Wangiri "four hundred numbers called it back" (the data: 400 were rung for 0-3 seconds,
  40 called back, and the callback is forwarded) and the Wangiri decoy "257 calls" (the taxi line is called by 650 distinct
  subscribers). Both are corrected below.

**Shared lines (not repeated in each case).** *Closing words, said in every film's last scene:* "The same steps run on millions of
records and answer in moments." *Edition chip:* Standard. *Industry accent:* Telecom teal. *Voice defaults:* Piper hfc_female
take D (length scale 0.98, 0.30 s between sentences), cold open 1.04 and 0.45 s, close 0.96. *Case-file card:* Lead · Evidence ·
Next step · Try it, 8 s. *Lexicon (all five films):* MSISDN "em ess eye ess dee en", SIM "sim", IMEI "eye em ee eye", IMSI "eye em
ess eye", KYC "kay why see", ids by family ("HUB-01" "hub oh one", "AG-18" "ay gee eighteen", "DL-112" "dee el one one two",
"POS-0008" "pee oh ess oh oh oh eight", "S-41" "ess forty-one"), amounts as words ("4 750" "four thousand seven hundred and
fifty").

---

## Telecom_Structuring

> **The report is green** · Standard · Mobile Money Space (`la-mobile-money`) · cash-ins just under the limit · 4 min 45 s

- **Cast:** the hub wallet (HUB-01), the agent who handled the cash-ins (AG-18), the twenty-five structuring wallets
  (STRUCT-01 to STRUCT-25), the relays (RELAY-1, RELAY-2); all masked for the analyst, so the film names roles.
  **Decoy on screen:** the second name of Busiest nodes, a relay with three linked wallets that only passes money on.
  **Decoy in the data, narrated:** the biggest receiver of all, a utility shop (MER-ELECTRIC): hundreds of payers, one
  way, ordinary amounts; it is not on this saved view.
- **Stakes:** the investigator is the operator's AML analyst, who owns the limit alerts. A cash-in limit that every check says
  is respected, and that is still beaten, is a limit nobody is enforcing.
- **Vocabulary:** *structuring* ("splitting one sum into amounts that each stay under a reporting limit"); *cash-in* ("a customer
  hands cash to an agent, who credits the wallet"). Defined in scenes S01 and S02.
- **Ground truth:** `tools/gen-la-mm-data.py` header, stories 1, 2 and 6 and 7: AG-18 cashes in for 25 wallets (STRUCT-01..25)
  inside 36 hours, each amount in the band 4 750 ≤ amount < 5 000 (`BAND_LO` = 0.95 × `TIER_LIMIT` 5 000); each wallet pays
  HUB-01 (the alert Measure fires on HUB-01, 25 legs, 25 payers); HUB-01 forwards 98 % within ten minutes to RELAY-1 and
  RELAY-2; the benign hub is MER-ELECTRIC (300 payers, one way, amounts under 4 400). No ground-truth JSON exists for this
  Space; the golden test is `LaMobileMoneyGoldenTest`.
- **Screen truth (checked live 2026-11-22, Standard, Demo Analyst):** saved view *Mm structuring band* draws a diamond (the
  agent, a column of 25 wallets, the hub and two relays), labels off, "31 entities masked" with Reveal… greyed. Busiest nodes
  lists five wallets by alias: 42 transfers (starting from it draws 1 entity and 27 linked wallets: the hub), 34 (3 linked
  wallets: a relay), 25 (25 linked wallets: the agent), 20, 17. The Data page lists four streams; `mm_links_dataset` has `src` and
  `dst` as Entity (Wallet), `kind` and `amount` as Detail, `ts` and `event_date` as Time. The dock Rows tab lists From, Link kind,
  To and Count (Cash-in, 1). Search nodes on the saved view finds nothing for the shop: it is outside the band.
- **Before:** Standard build, Mobile Money Space from a fresh unzip, Demo Analyst signed in; the administrator scene reopens the
  seeded Investigation *Mule hub and its chain* (the F7 reveal adds an audit entry, nothing else).
- **Lead:** one agent (AG-18) to review, the hub and its twenty-five wallets to hold. *What would change our mind:* if the
  twenty-five deposits came from twenty-five different agents over weeks, or from amounts all over the range, it would be a busy
  shop, not structuring. *Next actions:* review the agent's other cash-ins; hold the hub and its wallets; keep the wallets as the
  Entity List *STRUCT wallets* and watch the pattern with the existing Alert Rule `mm-structuring`.
- **Try it / Check:** *Try it:* open the saved view, press Busiest nodes, and start from the third name. *Check:* why is the second
  name not suspicious although it is busy? *Answer: it only passes money on; the pattern is many deposits under the limit meeting in
  one wallet, and the agent who made them.*

### S01 · Cold open · ~26 s
- Voice: Monday, nine in the morning. The cash-in limit on a basic wallet is five thousand, and not one deposit this month has crossed it, so the compliance report is green. [pause 0.6] You are the analyst who has to say whether that is good news. It is not: it is what success looks like to someone who has read the rules.
- Action: cold open over the Space's home page, the saved view *Mm structuring band* in My work.
- Impact: none.

### S02 · Why every check stays green · ~30 s
- Beat: steady
- Intro: The report is green | Mobile Money Space
- Story: the agent = who handled the cash-ins | the structuring wallets = twenty-five, each used once | the hub = where they all pay
- Voice: Every check looks at one deposit at a time. This deposit is under the limit, so it passes, and so does the next one. [pause 0.5] Twenty-five wallets can each do that once, and no check ever sees the twenty-five. Splitting one sum into amounts that stay under a limit is called structuring, and the pattern is not in any row: it is between them.
- Action: intro card, then the story card "Who is who"; the saved view opens behind it.
- Impact: the story card names the three roles; nothing on the canvas is marked yet.

### S03 · The data in one breath · ~30 s
- Voice: Each wallet transaction is a record: a sending wallet, a receiving wallet, a kind, an amount and a time. [pause 0.4] A Job reads them into links, and the Data page shows how. The sending and receiving wallets are the two ends, and the time puts every link in order. Tell the product which two columns are the ends, and a table becomes a network you can walk.
- Chip: Data · Columns
- Action: rail Data ▸ the Dataset *mm_links_dataset* ▸ hold on its Columns card (src and dst hold a Wallet, ts is a Time).
- Impact: mark the src and dst columns.

### S04 · A view that asks one question · ~38 s
- Voice: This saved view keeps only the cash-ins just under the limit, four thousand seven hundred and fifty up to five thousand, and it asks one question: who is collecting them? [pause 0.5] Every wallet shows as an alias, and the line under the canvas says thirty-one entities are masked. This analyst works without seeing wallet ids, and the pattern does not need them, because a funnel is a shape, not a name.
- Chip: Saved views · Reveal…
- Action: open the saved view *Mm structuring band*; hover the greyed Reveal… under the canvas (tooltip *Needs See unmasked ids*).
- Impact: mark the "entities masked" line and the greyed Reveal….

### S05 · First sweep, and the name to set aside · ~45 s
- Voice: First question: Busiest nodes. [pause 0.4] Three names lead. The top one has twenty-seven wallets around it. The second has only three: it passes money on, so it is busy by design, and it is set aside. The third has exactly twenty-five links, and every one is a cash-in. [pause 0.4] Elsewhere in the data a utility shop has more payers, but each pays once, in an ordinary amount, and nothing leaves. A popular shop is not a funnel.
- Chip: Busiest nodes
- Action: Busiest nodes ▸ hold on the three top cards (42, 34, 25 transfers).
- Impact: yellow mark on the top card (the hub), grey "ruled out: passes it on" on the second, yellow on the third (the agent).

### S06 · The agent, and the funnel · ~42 s
- Beat: reveal
- Story: off
- Voice: Start from the third name, the agent. The graph draws it with twenty-five wallets around it, each used once, and every link is a cash-in. [pause 0.4] Now expand one degree. The wallets all pay one hub, and the hub passes the money straight on to two relays. [pause 0.5] That is the funnel: twenty-five deposits under the limit, meeting in one wallet.
- Chip: Start from these · Expand · G
- Action: Busiest nodes ▸ the third card *Start from these* ▸ hold ▸ G ▸ Expand ▸ hold on the funnel.
- Impact: before/after hold: the hub and the two relays appear.

### S07 · Evidence you can defend · ~38 s
- Voice: Is this a guess? Open the table under the graph. Every line is a row: from, link kind, to, and how many times. [pause 0.5] The first lines are the agent's twenty-five cash-ins, one to each wallet, and further down the same wallets pay one hub. Nothing is estimated. The graph shows the shape, and the table lets a colleague check every line and export it as a file.
- Chip: Rows · Export
- Action: dock ▸ Rows ▸ hold on the rows; point at the download icon.
- Impact: before/after hold: the dock opens; mark the Link kind column.

### S08 · Names, with a reason · ~40 s
- Voice: Now a name is needed, so the administrator signs in and opens the same case. [pause 0.5] Press Reveal, and the dialog says it plainly: the real ids, on this screen only, each one recorded with your reason. Type the reason: confirm the hub before the wallets are frozen. The labels change to the real ids, and the audit trail knows who looked, and why. [pause 0.4] Everyone else keeps the aliases.
- Chip: Reveal…
- Action: User menu ▸ Sign out ▸ Demo Admin ▸ File ▸ Open… ▸ Investigations ▸ *Mule hub and its chain* ▸ Reveal… ▸ Reason `confirm the hub before the wallets are frozen` ▸ Reveal (the F7 steps; the dock must be closed, or Reveal… sits higher).
- Impact: before/after hold: aliases turn into HUB-01, RELAY-1 and the STRUCT ids; mark the confirmation message.

### S09 · What the team does next · ~42 s
- Voice: Here is the lead, and what happens to it. [pause 0.5] One agent to review, and the hub with its twenty-five wallets to hold. The wallets are kept as an Entity List, so every graph in this Space marks them from now on. Then watch for the next twenty-five: the Alert Rule on this pattern fires the day it starts, not the month after.
- Chip: Entity Lists · Alerts
- Action: rail Entity Lists ▸ the list *STRUCT wallets* ▸ hold; rail Alerts ▸ hold on the Alert Rule `mm-structuring` in the rules table; create nothing.
- Impact: mark the list's row and the Alert Rule's name.

### S10 · The case file · ~28 s
- Casefile: Lead: one agent, one hub, twenty-five wallets | Evidence: twenty-five deposits in the band, inside thirty-six hours, every one a row | Next: review the agent; hold the hub and its wallets; keep the Entity List and watch it with the Alert Rule | Try it: start from the third name in Busiest nodes
- Voice: The case file. Lead: one agent, one hub, twenty-five wallets. Evidence: twenty-five deposits in the band, inside thirty-six hours, every one a row. Next step: review the agent, hold the wallets, keep the list. [pause 0.6] Each deposit was legal; only the links show them meeting. The same steps run on millions of records and answer in moments. Try it: start from the third name in Busiest nodes.
- Action: case-file card (8 s: Lead · Evidence · Next step · Try it), then the outro "Next: Make it defensible · training F4".
- Impact: none.

---

## Telecom_DeviceFarm

> **Nine people who never met** · Standard · Mobile Money Space (`la-mobile-money`) · one handset behind nine wallets · 4 min 30 s

- **Cast:** nine wallets (FARM-1 to FARM-9) on one handset (DEV-FARM-01), all masked for the analyst, so the film names roles.
  **Decoys on screen:** a household of three wallets on one national id, and a family phone carried by four wallets; the
  placeholder values (UNKNOWN, blank) the Job leaves out are the third trap, narrated.
- **Stakes:** the investigator is in fraud operations; a sign-up bonus has paid out nine times to what the register says are nine
  people. The register checks each person once, so it cannot see that the people share a phone.
- **Vocabulary:** *KYC* ("the identity check an operator makes when it opens a wallet"); *handset* ("the physical phone,
  not the SIM or the number"). Defined in S01 and S02.
- **Ground truth:** `tools/gen-la-mm-data.py` header story 3 and lines 129-135 and 217-250: FARM-1..9 share DEV-FARM-01 and
  hold nine different national ids; all are tier T1; each takes three cash-ins at AG-05, pays MER-03 and MER-05 four times and
  cashes out twice at AG-30. Benign: a household of three on `NID-HOUSEHOLD-01`, a family phone `DEV-FAMILY-01` on four
  wallets; placeholders are held by 5-8 wallets each and excluded by the Job.
- **Screen truth (checked live 2026-11-22, Standard, Demo Analyst):** saved view *Mm device farm*: 16 entities masked, labels on,
  Reveal… greyed; the Legend lists the link kinds *Shared device* and *Shared identity*. Tight groups returns three groups: 9
  wallets with 36 transfers among them, 4 wallets with 6, 3 wallets with 3. Starting from a wallet of the nine draws 1 entity
  and its linked wallets; the Expand dialog offers "The nodes not expanded yet" and adds about 38 wallets and 279 transfers. The
  Entity Lists page shows *STRUCT wallets* (26 members) and *Device farm wallets* (9 members). Settings ▸ Identities as Demo Admin
  shows one group of 2 identifiers: `#7 … = … — same handset DEV-FARM-01 (showcase)`, asserted by analyst, with Retract.
- **Before:** Standard build, Mobile Money Space from a fresh unzip, Demo Analyst; the tail scene signs in as Demo Admin.
- **Lead:** one group of nine wallets, to review together, not nine customers: for the fraud and identity teams, with the shared
  handset and the shared agents as evidence. *What would change our mind:* if the nine wallets had different handsets most days,
  or paid nine different merchants, the shared phone would be a coincidence. *Next actions:* freeze the bonus for the group;
  record that the wallets are one entity (an identity fact); watch new wallets that appear on the same handset.
- **Try it / Check:** *Try it:* in Settings ▸ Identities as Demo Admin, use Find group on one farm wallet. *Check:* why is the
  family phone not flagged? *Answer: four wallets on one phone is a family; the farm is nine wallets, all lowest tier, using the
  same agents and merchants.*

### S01 · Cold open · ~25 s
- Voice: Nine customers, nine different national ids, and every one passed the identity check. [pause 0.6] You work in fraud operations, and a sign-up bonus has just paid out nine times. On paper, those nine people have never met.
- Action: cold open over the Space's home page, the saved view *Mm device farm* in My work.
- Impact: none.

### S02 · Why the register cannot see it · ~30 s
- Beat: steady
- Intro: Nine people who never met | Mobile Money Space
- Story: a wallet = one customer's account | the shared handset = the phone behind several wallets | a family = people who share a phone and a home
- Voice: The register holds people, one clean row each. The check made when a wallet is opened is called KYC, short for know your customer, and it checks the person. [pause 0.5] Nothing in it says which phone opened which wallet. The phone is the handset, and the connection between these nine only exists as a link.
- Action: intro card, then the story card "Who is who"; the saved view opens behind it.
- Impact: the story card names the roles.

### S03 · Links the transactions do not hold · ~32 s
- Voice: Transactions say who paid whom, but not who shares a phone. [pause 0.4] A Job adds those links: two wallets on one handset, and two wallets on one identity. Placeholder values such as unknown are left out on purpose, because without that, half the Space would look related to the other half. The Data page shows the Dataset they all land in.
- Chip: Data · Columns
- Action: rail Data ▸ the Dataset *mm_links_dataset* ▸ hold on its description and Columns card.
- Impact: mark the Dataset's description line.

### S04 · Groups that look alike · ~42 s
- Voice: The saved view draws exactly those links. The legend names two kinds, a shared device and a shared identity. [pause 0.5] Small groups appear: a household of three wallets, a family phone with four, and one larger tangle. The first two are ordinary, and both are on screen. Sharing is not fraud, so the question is which group is different, and how.
- Chip: Saved views · Legend
- Action: open the saved view *Mm device farm* ▸ Legend ▸ hold on the groups.
- Impact: none yet; the groups are marked in S05.

### S05 · First sweep: a tight group · ~40 s
- Voice: Ask Tight groups. [pause 0.4] The product finds three groups of wallets that link mostly among themselves. The smallest is three wallets, the household. The next is four, the family phone. [pause 0.4] The largest is nine wallets with thirty-six transfers among them: every wallet is tied to every other. A shop or an agent does not look like that. A shared device does.
- Chip: Tight groups
- Action: Tight groups ▸ hold on the three cards (9, 4 and 3 wallets).
- Impact: yellow mark on the group of nine; grey "ruled out" marks on the four and the three.

### S06 · Same handset, same habits · ~45 s
- Beat: reveal
- Story: off
- Voice: Start from one wallet of the nine. The graph draws it with the wallets that share its handset. [pause 0.5] Now expand one degree, and the agents and merchants appear around the group. Each wallet takes cash in from the same agent, pays the same two merchants, and cashes out at a second agent. A family phone is shared by people who live differently. These wallets live exactly alike, and all of them sit at the lowest identity tier.
- Chip: Start from these · Expand · G
- Action: Tight groups ▸ the first card *Start from these* ▸ G ▸ Expand ▸ hold.
- Impact: before/after hold: the agents and merchants appear around the nine.

### S07 · Evidence you can defend · ~32 s
- Voice: Open the table under the graph. Each link has a row: from, link kind, to, and how many. [pause 0.5] Nine wallets, one device, nine identities, and the same few counterparties. A colleague can check every line, and export it as a file.
- Chip: Rows
- Action: dock ▸ Rows ▸ hold on the rows.
- Impact: before/after hold: the dock opens.

### S08 · One entity, said once · ~40 s
- Voice: The nine wallets are one operation, so tell the product. As the administrator, open Settings, then Identities. [pause 0.5] An identity fact says that two identifiers are one entity, with a reason. Here, two farm wallets on one handset. The group shows who said so and why, and the assertion can be taken back with Retract. We do not press it here.
- Chip: Settings · Identities
- Action: User menu ▸ Sign out ▸ Demo Admin ▸ File ▸ Open… ▸ *Mule hub and its chain* ▸ Settings ▸ Identities ▸ hold on the group and its reason (as F7).
- Impact: mark the group, its reason and the Retract button (not pressed).

### S09 · What the team does next · ~38 s
- Voice: The lead: one group of nine wallets, to review together, not nine customers. [pause 0.5] The fraud team freezes the bonus for the group. The identity team looks at who opened them. And a watch on that handset catches the tenth wallet before it is paid. The nine are already kept as an Entity List, so the next case starts from them.
- Chip: Entity Lists
- Action: rail Entity Lists ▸ hold on *Device farm wallets* (9 members).
- Impact: mark the list row.

### S10 · The case file · ~28 s
- Casefile: Lead: one handset, nine wallets, nine identities | Evidence: the shared-handset links, and the same agents and merchants | Next: freeze the bonus for the group; record one entity; watch the handset | Try it: find the group of a farm wallet in Identities
- Voice: The case file. Lead: one handset, nine wallets, nine identities. Evidence: the shared-handset links, and the same agents and merchants. Next step: freeze the bonus, record one entity, watch the handset. [pause 0.6] In the register each wallet is a clean row. The handset that joins them only exists as a link. The same steps run on millions of records and answer in moments. Try it: find the group of a farm wallet in Identities.
- Action: case-file card (8 s) and the outro "Next: Masked data and resolving identities · training F7".
- Impact: none.

---

## Telecom_SimSwap

> **Forty swaps, one login** · Standard · Telecom Space (`la-telecom`) · SIM swaps, then emptied wallets · 5 min (carries the shared Standard module, §4.4 of the plan)

- **Cast:** the call-centre login (S-41), thirty swapped wallets, six new handsets (HS-08913 to HS-08918), five mule wallets
  (WL-07936 to WL-07940), two cash-out agents (A-088, A-093). **Decoy:** the store login S-72, the busiest of all, in a SIM-upgrade
  drive; its customers kept their money.
- **Stakes:** the investigator works in the operator's fraud team. Customers say their wallets were emptied, and every one had
  a SIM swap first. A swap is a routine request, so the question is whose swaps these are.
- **Vocabulary:** *SIM swap* ("moving a number to a new SIM, normally after a lost phone"); *login* ("a staff member's account
  that can request a swap"). Defined in S01 and S02.
- **Ground truth:** `tools/gen-la-mfs-data.ground-truth.json`, key `sim_swap_takeover` and `stories.sim_swap_takeover`
  (hub S-41, lookalike S-72, view `mfs_sim_swap_bursts`): login S-41; thirty victims drained within two hours; attacker devices
  HS-08913 to HS-08918; cash-out agents A-088 and A-093; mules WL-07936 to WL-07940; days 2026-06-08 and 2026-06-17;
  lookalike login S-72 with 160 customers (key `lookalike_customers`); ten genuine swaps by other logins; threshold
  `swaps_in_10_days` 15. The plan's table is confirmed.
- **Screen truth:** saved view *Mfs sim swap bursts*: a large cluster picture, labels off above 80 nodes. Busiest nodes names
  S-72 (160 links), S-41 (40), WL-07938 (19), WL-07936 (16), readable (no masking in this Space). The Investigation *SIM-swap
  wallet takeover* exists (members, a held expand, as in F5).
- **Before:** Standard build, Telecom Space from a fresh unzip (the Investigation and its held request untouched), Demo Analyst; the
  shared module scene signs in as Demo Manager and approves nothing.
- **Lead:** one login to investigate, five wallets and two agents to trace. *What would change our mind:* if the thirty
  wallets were emptied to thirty different places, or the swaps were spread over months, it would be ordinary customer service.
  *Next actions:* suspend the login's swap right; freeze the five mule wallets; ask the two agents for the cash-outs.
- **Try it / Check:** *Try it:* press Busiest nodes in the saved view and start from the second name. *Check:* why is the busiest
  login not the suspect? *Answer: it is a store in an upgrade drive, and its customers kept their money; busy is not suspicious.*

### S01 · Cold open · ~25 s
- Voice: A customer calls. Her wallet is empty. Two hours earlier, her number was moved to a new SIM, and she did not ask for it. [pause 0.6] A SIM swap is a routine request, so who made this one? By Friday there are thirty customers with the same story.
- Action: cold open over the Telecom Space's home page; the intro card opens the next scene ("Case file · Forty swaps, one login · Telecom Space · Standard").
- Impact: none.

### S02 · Three files, no single answer · ~30 s
- Beat: steady
- Intro: Forty swaps, one login | Telecom Space
- Story: a login = a staff account that can request a SIM swap | a swapped number = a customer's number moved to a new SIM | a new handset = the phone the number was moved to
- Voice: The swap is in one file, the staff login in another, the wallet payments in a third. [pause 0.5] Each file is clean on its own. A login is a staff member's account that can request a swap, and most swaps are honest. Only the links put a swap and an empty wallet on one line.
- Action: story card "Who is who": *a login*, *a swapped number*, *a new handset*; the saved view opens behind it.
- Impact: the story card names the roles.

### S03 · Linking the pieces · ~30 s
- Voice: A Job reads the three files and links the pieces into one Dataset. Each row has two ends, the source and the destination, and both are telecom entities. [pause 0.4] A kind says what the link is: a swap, a handset, a payment. An amount and a time ride along, so the links fall in order.
- Chip: Data · Mappings
- Action: rail Data ▸ the Dataset *mfs_screen_links_dataset* ▸ hold on its Columns card. `[checked live: src and dst are Entity (Telecom entity), kind is a Category, amount a Measure, ts and event_date are Time, via and screen are Details]`
- Impact: mark the link kinds.

### S04 · The view of bursts · ~35 s
- Voice: The saved view draws swaps in bursts: logins, the numbers they swapped, and where the money went afterwards. [pause 0.5] Two pictures sit side by side. One is a small tangle. The other is a huge wheel, a single login with a hundred and sixty spokes. It is too big to read, so ask a question.
- Chip: Saved views · Display options
- Action: open the saved view *Mfs sim swap bursts* ▸ hold on the small cluster and the large wheel. `[checked live: the caption reads "node labels off above 80 nodes: Display options turns them on"]`
- Impact: hold on the picture; no mark.

### S05 · First sweep, and the obvious suspect · ~50 s
- Voice: Busiest nodes. [pause 0.4] The busiest login has a hundred and sixty swaps. The obvious suspect. But it is a store in a SIM-upgrade drive, and its customers kept their money. Busy is not suspicious. Set it aside, and look at the second name: a call-centre login with forty swaps.
- Chip: Busiest nodes
- Action: Busiest nodes ▸ hold on S-72 (160 links) and S-41 (40 links) ▸ point at *Add to exclusion list* on S-72, do not press. `[checked live: the rows are S-72 160, S-41 40, WL-07938 19, WL-07936 16]`
- Impact: grey "ruled out" marker on S-72, yellow on S-41.

### S06 · Forty swaps, thirty in ten days · ~45 s
- Beat: reveal
- Story: off
- Voice: Start from the second name. The graph draws S-41 with forty numbers around it, and each one was swapped by this login. [pause 0.5] Now expand one degree. The six new handsets appear, HS-08913 to HS-08918, and the wallets that were used from them. A customer who lost a phone does not do that. A person working down a list does. [pause 0.4] Six new handsets carry thirty numbers in ten days.
- Chip: Start from these · Expand · G
- Action: Busiest nodes ▸ S-41 *Start from these* ▸ close the notice ▸ G ▸ *The whole graph* ▸ Expand ▸ hold on the swaps and the handsets. `[checked live: the first view draws 1 entity and 40 linked nodes; the first expand adds 11 nodes and 52 links, the six handsets HS-08913..HS-08918 among them; the Expand panel says the Dataset has no link index yet]`
- Impact: before/after hold: the swaps and the handsets appear around S-41; mark the six handsets.

### S07 · Where the money went · ~45 s
- Voice: Follow the money. Expand once more from the whole graph. Every wallet was drained within two hours, into the same five wallets, WL-07936 to WL-07940, and cashed out at two agents. [pause 0.5] Open the table under the graph. Each swap and each payment out is a row: from, link kind, to. Thirty empty wallets, five places the money went.
- Chip: Rows · Expand · G
- Action: G ▸ *The whole graph* ▸ Expand ▸ dock ▸ Rows ▸ hold on the rows. `[checked live: the Rows tab lists from, link kind, to and count, the first rows WL-00300, WL-00302, WL-00423 swapped_by S-41; the second expand adds two nodes and 45 links; the agents A-088 and A-093 are narrated, confirm them in the Nodes tab]`
- Impact: mark the five mules and the two agents; before/after hold for the dock.

### S08 · Make it defensible · ~70 s
- Voice: The lead has to survive a challenge, so it becomes an Investigation. Open SIM-swap wallet takeover. [pause 0.5] The step log lists every step in order, with who took it and why, and nobody can edit a row afterwards. Replay runs the whole log again and says whether every step still gives the same result. One note records what the analyst found: thirty SIMs swapped through this login in ten days. [pause 0.5] The members page shows who is on the case. The reviewer and the manager are the second pair of eyes: a big expand waits for their approval, so the largest steps are always seen by two people. Reviewers read, and only a lead changes the steps. [pause 0.5] And the Dossier packs the Investigation, its steps and its rows, with a seal over every piece, so anyone can verify that nothing changed. We only look here. Nothing is approved or sealed in this film.
- Chip: Step log · Members · Build Dossier
- Action: File ▸ Open… ▸ Investigations ▸ *SIM-swap wallet takeover* ▸ Step log ▸ Investigation actions ▸ Members… ▸ Settings ▸ Investigation settings ▸ hold on the Dossier fold, do not build. `[checked live: the step log rows Start, Annotation 3 ("30 SIMs swapped through this login between 2026-06-08 and 2026-06-17"), Members: analyst lead owner, reviewer, manager, admin lead]`
- Impact: mark a step row, the held-request line and the verified line.

### S09 · What the team does next · ~40 s
- Voice: The lead: one login to investigate, five wallets and two agents to trace. [pause 0.5] The call centre suspends that login's swap right today. The five wallets are frozen. The two agents are asked for the cash-out records. The wallets are kept in an Entity List, so the next case starts from them.
- Chip: Entity Lists
- Action: rail Entity Lists ▸ hold on the lists of this Space. `[checked live: the Space's Entity Lists are Mule wallets to freeze and Unbilled SIMs, as offered in the node dialog; open the page to read the list rows]`
- Impact: mark the list row.

### S10 · The case file · ~25 s
- Casefile: Lead: one login, five wallets, two agents | Evidence: thirty swaps in ten days, each followed by a drained wallet | Next: suspend the login's swap right; freeze the five mule wallets; ask the two agents for the cash-out records | Try it: press Busiest nodes and start from the second name
- Voice: The case file. Lead: one login, five wallets, two agents. Evidence: thirty swaps in ten days, each followed by a drained wallet. Next step: suspend, freeze, trace. [pause 0.6] A swap alone was routine. A payment alone was routine. The links joined them. The same steps run on millions of records and answer in moments. Try it: start from the second name in Busiest nodes.
- Action: case-file card (8 s) and the outro "Next: Teams and four-eyes · training F5".
- Impact: none.

---

## Telecom_WangiriNetwork

> **One operation, four masks** · Standard · Other Examples Space (`la-showcase`) · four fraud reports, one network · 4 min 45 s

- **Cast:** the suspect number (WANGIRI-A, 99979100001), a second SIM on its handset (WANGIRI-B, 99979100002), the premium-rate
  number its callbacks are forwarded to (PREMIUM-01, 99891000001), then IRSF-01, five numbers on one identity (SUBFRAUD-01..05),
  a SIM box (SIMBOX-01) and mules (MULE-01..); all masked for the analyst, so the film names roles. **Decoy:** the taxi-dispatch
  line, called by 650 distinct subscribers; the product holds it back as a *busy* subscriber when the graph is expanded.
- **Stakes:** four teams hold four reports (a missed-call scam, an international revenue-share ring, a false subscriber identity,
  a SIM box). Each team can close its own ticket; nobody sees that the same operation sits behind all four.
- **Vocabulary:** *Wangiri* ("the phone rings once and stops so the victim calls back to a premium-rate number"); *revenue-share
  fraud* ("calls pumped into a premium number to collect its share"); *SIM box* ("a box of SIMs that turns international calls into
  local ones"). Defined once each, at the scene where the chain reaches it.
- **Ground truth:** `tools/gen-la-telecom-data.py` header and lines 50-60 and 183-192: WANGIRI-A rings 400 victims for 0-3
  seconds, one way; 40 call back, and the callback is forwarded to PREMIUM-01; WANGIRI-B (already on the blocklist) ran the
  same play earlier on 60 victims; both SIMs share one handset. Degree ladder from WANGIRI-A: d1 WANGIRI-B (shared device) and
  PREMIUM-01 (forwarding); d2 IRSF-01 (voice calls to PREMIUM-01); d3 SUBFRAUD-01..05 (shared identity, IRSF-01's id document);
  d4 SIMBOX-01 (shared payment card) and MULE-01 (SIM history); d5 the other mules. The HUB supernode (a taxi-dispatch line
  called by 650 distinct subscribers) is flagged and not expanded. **Corrections:** v1 said "four hundred numbers called it back"
  (it is 400 rung, 40 called back) and used "257 calls" for the taxi line (650 distinct callers).
- **Screen truth (checked live 2026-11-23, Standard, Demo Analyst):** saved view *Telecom fraud network*: 767 entities masked,
  a banner *Result cut short*, and Busiest nodes names aliases. Starting from the typed number (Investigation tab ▸ Start from One
  entity ▸ Dataset `telecom_links_dataset` ▸ Entity Type MSISDN ▸ the value ▸ Start; the first Start can take over fifteen
  seconds on a cold server) draws *1 entity and 402 linked subscribers* ("403 entities masked"). Expand (G) from "the nodes
  not expanded yet" takes the first 25 of the 402 (about 41 subscribers and 270 calls) and the next press adds 41 more
  subscribers; the Expand dialog reports *1 busy subscriber will be held*, and the canvas lists it with *Expand anyway* and
  *Hide held (1)*. The dock Steps tab reads *Start from 1 entity · Expand 1 degree from 1 entity · Expand 1 degree from the outer
  ring*, and says *Nothing is recorded yet. Save asks for a Name and a Purpose*. The film does not save. The full chain to the
  identity ring, the SIM box and the mules is narrated from the generator, not reached on screen.
- **Before:** Standard build, Other Examples Space from a fresh unzip, Demo Analyst; nothing is saved.
- **Lead:** one operation to report, link by link, not four cases. *What would change our mind:* if the premium number's other
  callers had no link to the identity or the handset, the four reports would be four separate frauds. *Next actions:* block the
  premium number and the two SIMs; send the identity ring to subscription-fraud review; hand the SIM box and mules to the
  carrier team with the chain as the evidence.
- **Try it / Check:** *Try it:* start from the suspect number and expand one degree at a time with Expand · G, reading what each
  press adds. *Check:* why not start from the busiest number? *Answer: it is a taxi line that hundreds of people call, so the
  product holds it back; the number that rings people for a second and hangs up leads to the fraud.*

### S01 · Cold open · ~25 s
- Voice: Four reports land on four desks in one week: a missed-call scam, an international revenue-share ring, a false subscriber identity, and a SIM box. [pause 0.6] Four teams close four tickets. You wonder whether it is one operation, wearing four masks.
- Action: cold open over the home page.
- Impact: none.

### S02 · Each team sees one slice · ~32 s
- Beat: steady
- Intro: One operation, four masks | Other Examples Space
- Story: a missed-call number = rings once and hangs up | a premium number = where the callbacks end | a shared handset = one phone behind two SIMs
- Voice: A network records every call it carries, and each team looks at its own slice. Wangiri, a Japanese word, is the missed call: the phone rings once so you call back a premium number. [pause 0.5] Each trick looks like a separate crime, but the forwarding and the shared handset that join them were in the records all along.
- Action: intro card, then the story card "Who is who"; the saved view opens behind it.
- Impact: the story card names the roles.

### S03 · Calls into links · ~28 s
- Voice: Each call is a record: who rang, who was rung, when, for how long, and the SIM and handset behind it. [pause 0.4] A Job turns calls into links between numbers: who called whom, who forwarded to whom, and which SIMs share a handset. Numbers are the entities, and the call type names the kind of link.
- Chip: Data
- Action: rail Data ▸ hold on the list of Datasets.
- Impact: none.

### S04 · A busy view, and a banner · ~38 s
- Voice: The saved view draws the suspicious corner of the network. It is large, and the server says why it looks cut: it stopped reading at its row limit. [pause 0.5] The product tells you instead of drawing a partial picture quietly. Every number is an alias for this analyst, so you follow roles, not digits. Narrow it down.
- Chip: Saved views
- Action: open the saved view *Telecom fraud network* ▸ hold on the banner and the "entities masked" line.
- Impact: mark the masked line.

### S05 · First sweep: a taxi line · ~36 s
- Voice: Busiest nodes. [pause 0.4] The top name is a taxi-dispatch line. Hundreds of people call a taxi, so it is busy for a good reason. Set it aside, and look instead for a number that rings people it never meant to talk to, and that forwards what comes back.
- Chip: Busiest nodes
- Action: Busiest nodes on the saved view ▸ hold on the first rows.
- Impact: grey "ruled out" marker on the first row (the taxi line).

### S06 · The missed-call number · ~48 s
- Voice: Start from the number the missed-call reports named. Type it in the Investigation tab, and press Start. [pause 0.5] The graph draws that one number with four hundred and two subscribers around it. It rang them all for a second or two and hung up. Forty people called back, and every callback was forwarded to one premium-rate number. And a second SIM, already on a blocklist, sits in the same handset and ran the same trick earlier. That is how the money is made.
- Chip: Investigation · Start from · One entity
- Action: Investigation tab ▸ Start from One entity ▸ Dataset `telecom_links_dataset` ▸ Entity Type MSISDN ▸ the number ▸ Start ▸ hold on the wheel of 402.
- Impact: before/after hold: the wheel of subscribers appears around the start number.

### S07 · One degree at a time · ~48 s
- Voice: Now widen it one degree at a time, and watch what joins. Press Expand, G. [pause 0.5] The product takes the outer ring, adds more subscribers, and holds back one busy number instead of pulling in its whole city: the taxi line, again. Press it a second time. In the full data, the premium number receives voice calls pumped in by a revenue-share ring, whose id document belongs to five numbers on one synthetic identity. One of their payment cards pays for a SIM box. Four reports, one chain.
- Chip: Expand · G
- Action: G ▸ Expand ▸ hold ▸ G ▸ Expand ▸ hold on the held number and *Expand anyway*; do not press it.
- Impact: before/after hold at each press; mark the held number.

### S08 · Evidence you can defend · ~35 s
- Voice: Is it a guess? The dock lists each step in order: the start, and each expand with its limits. [pause 0.5] The table beside it holds every call as a row a colleague can read. Nothing is saved yet. Save asks for a name and a purpose, and then the steps are recorded for an auditor, so a chain is a result you can replay, not a drawing.
- Chip: Steps
- Action: dock ▸ Steps ▸ hold on the three rows and the line *Nothing is recorded yet*.
- Impact: mark the Steps rows.

### S09 · What the team does next · ~38 s
- Voice: The lead: one operation to report, link by link, not four cases. [pause 0.5] The premium number and the two SIMs are blocked. The identity ring goes to subscription-fraud review. The SIM box and the mules go to the carrier team, with the chain as the evidence. And the same walk is repeated on every other number that rings people for a second.
- Action: hold on the graph.
- Impact: none.

### S10 · The case file · ~28 s
- Casefile: Lead: one operation behind four reports | Evidence: four hundred rung, forty called back, every callback forwarded to one premium number, one handset behind two SIMs | Next: block the premium number and both SIMs; send the identity ring to subscription-fraud review; hand the SIM box and mules to the carrier team | Try it: start from the suspect number and expand one degree at a time
- Voice: The case file. Lead: one operation behind four reports. Evidence: four hundred rung, forty called back, every callback forwarded to one premium number. Next step: block the number and the two SIMs, send the identity ring to review, hand the SIM box and mules to the carrier team. [pause 0.6] Four teams saw four slices; the links showed one operation. The same steps run on millions of records and answer in moments. Try it: start from the suspect number.
- Action: case-file card (8 s) and the outro "Next: Expand with intent · training F2".
- Impact: none.

---

## Telecom_DealerCommission

> **Two record months** · Standard · Telecom Space (`la-telecom`) · commissions on SIMs nobody used · 4 min 30 s

- **Cast:** the dealer (DL-112), its single shop (POS-0008), activation devices, four hundred SIMs; readable ids.
  **Decoy:** a campus dealer (DL-163) with a record month of its own: 350 SIMs topped up at ten shops and used every month.
- **Stakes:** the investigator audits dealer commissions, which are paid for each SIM activated. A record month is good news, so a
  report of activations cannot separate the dealer who earned it from the one who manufactured it.
- **Vocabulary:** *commission* ("a payment per SIM activated"); *top-up* ("credit added to a prepaid number"). Defined in S01 and S02.
- **Ground truth:** `tools/gen-la-telco-ra-data.ground-truth.json`, key `dealer_commission_fraud` and
  `stories.dealer_commission_fraud` (view `dealer_commission_fraud`): dealer DL-112; shop `POS-0008`; 400 subscribers; weeks
  2026-02-02 to 2026-03-09 (six weeks); lookalike dealer DL-163 with 350 subscribers and ten campus shops (`campus_pos`). From
  `gen-la-telco-ra-data.py` lines 234-260 and 358-359: each fraud SIM has exactly one top-up of 5.00 at POS-0008 within two days
  of activation and one usage month; each DL-163 SIM tops up at campus shops (amounts 10 or 20) with at least six top-ups and
  thirteen usage months. The plan's table said "the top-selling dealer vs the dealer whose SIMs are never used": the data says
  the decoy is the *other record-month dealer* (DL-163).
- **Screen truth (checked live 2026-11-22, Standard, Demo Analyst):** saved view *Dealer commission fraud*: two large clusters
  (about 400 and 350 SIMs around a dealer each), no masking line, labels off ("node labels off above 80 nodes"). Busiest nodes
  lists DL-112 (400 links, the most), POS-0008 (400), DL-163 (350), then two devices with 188 and 162 links. Start from these on a
  dealer draws 1 entity and its linked SIMs in one star (400 for DL-112, 350 for DL-163), with the link kind `activated_by` in the
  Rows table. The view reads `telco_ra_screen_links_dataset`. The Data page lists 20 streams, the first `agent_commission_dataset`.
  This film does not open or save an Investigation.
- **Before:** Standard build, Telecom Space from a fresh unzip, Demo Analyst; nothing is saved.
- **Lead:** one dealer's commissions to audit, with four hundred SIMs and one shop as the evidence. *What would change our mind:*
  if the SIMs were topped up at many shops and used every month, as DL-163's are, the spike would be honest demand. *Next actions:*
  hold the dealer's commission; send the shop and its devices to the dealer-audit team; compare other dealers against the same shape.
- **Try it / Check:** *Try it:* press Busiest nodes, start from the dealer with the matching shop, and open the Rows table. *Check:*
  why is the second record-month dealer not suspect? *Answer: its SIMs top up at ten shops and are used every month; the suspect's SIMs
  top up once, at its own shop, and are never used again.*

### S01 · Cold open · ~25 s
- Voice: Two dealers had a record month. Dealers are paid commission for every SIM they activate, so a record month is a good month. [pause 0.6] One of them earned it. The other found a way to manufacture it, and a report of activations cannot tell them apart.
- Action: cold open over the Telecom Space home page.
- Impact: none.

### S02 · A spike looks the same · ~30 s
- Beat: steady
- Intro: Two record months | Telecom Space
- Story: a dealer = paid commission per activated SIM | a shop = where a SIM is topped up | a device = the phone that activates SIMs
- Voice: In the commission report, both dealers are a spike of new SIMs. Each SIM has an activation, a dealer and a device, and for a real customer a top-up and some use. [pause 0.5] The report counts the activations. It does not ask who paid for them, or whether anyone used them, and those answers live in the links.
- Action: intro card, then the story card "Who is who"; the saved view opens behind it.
- Impact: the story card names the roles.

### S03 · From subscribers to links · ~30 s
- Voice: Each subscriber is a record: the number, the SIM, the dealer who activated it, when, the plan and the device. [pause 0.4] A Job reads each one into links, from a SIM to its dealer, its device and the shop where it was topped up. Numbers, dealers, devices and shops are all entities, and the Data page lists every Dataset they land in.
- Chip: Data
- Action: rail Data ▸ hold on the list of Datasets (Search finds the one the view reads, `telco_ra_screen_links_dataset`).
- Impact: mark the first Dataset card.

### S04 · Two giants · ~35 s
- Voice: The saved view draws dealers and their SIMs. Two dealers tower over it, each with hundreds of SIMs around it. [pause 0.5] It is too much to read, and both look alike. Ask a question.
- Chip: Saved views
- Action: open the saved view *Dealer commission fraud* ▸ hold on the two clusters.
- Impact: none; the two clusters are the picture.

### S05 · First sweep: a dealer and a shop · ~42 s
- Voice: Busiest nodes. [pause 0.4] A dealer with four hundred links, and a shop with exactly four hundred too. The second dealer has three hundred and fifty, and no shop anywhere near that. [pause 0.4] A dealer and one shop with the same number is not how a market looks. It is how a loop looks.
- Chip: Busiest nodes
- Action: Busiest nodes ▸ hold on DL-112 (400), POS-0008 (400), DL-163 (350).
- Impact: yellow on DL-112 and POS-0008; grey "ruled out" on DL-163.

### S06 · The campus dealer · ~38 s
- Voice: Start from the second dealer first. The graph draws its three hundred and fifty SIMs in one wheel. [pause 0.5] Its SIMs are topped up at ten different shops, every month, and used every month. A student campus. Real people. That is what an earned record month looks like, so set it aside and keep it as the yardstick.
- Chip: Start from these
- Action: Busiest nodes ▸ DL-163 ▸ Start from these ▸ hold on the wheel.
- Impact: before/after hold: the wheel appears; grey "ruled out" on the dealer.

### S07 · Four hundred SIMs, one shop · ~42 s
- Voice: Now start from the dealer with the matching shop. Four hundred SIMs, every one activated by this dealer, in six weeks. [pause 0.5] Open the table under the graph. Every line is a SIM, activated by the dealer. Each was topped up once, with five, at the dealer's own shop, and never used again. Nobody was meant to use these SIMs. They exist to be counted.
- Chip: Start from these · Rows
- Action: Busiest nodes ▸ DL-112 ▸ Start from these ▸ hold ▸ dock ▸ Rows ▸ hold on the rows (`activated_by`).
- Impact: before/after hold: the wheel of 400 appears; mark the Link kind column.

### S08 · Evidence you can keep · ~38 s
- Voice: Every activation is a row: the SIM, the link kind, the dealer. [pause 0.5] To keep the work, you would save it as an Investigation. The Investigation tab already holds the dealer as the first step, and asks for a purpose: commissions on SIMs that were never used. Then every step is recorded, and an auditor can open it. We leave it unsaved here.
- Chip: Investigation
- Action: toolbox ▸ Investigation tab ▸ hold on Name, Purpose and the queued start entity `DL-112`; do not save.
- Impact: mark the Purpose field and the queued entity.

### S09 · What the team does next · ~38 s
- Voice: The lead: one dealer, four hundred SIMs and one shop. [pause 0.5] The audit team holds the dealer's commission today. The shop and its devices go to the dealer-audit team. And the same shape, many activations, one top-up, no use, is checked for every other dealer.
- Chip: Busiest nodes
- Action: hold on the Busiest nodes answer.
- Impact: mark DL-112 and POS-0008.

### S10 · The case file · ~28 s
- Casefile: Lead: one dealer, one shop, four hundred SIMs | Evidence: every SIM activated by the dealer, topped up once at its own shop, never used again | Next: hold the commission; audit the shop and its devices; compare every other dealer against the same shape | Try it: press Busiest nodes and start from the dealer with the matching shop
- Voice: The case file. Lead: one dealer, one shop, four hundred SIMs. Evidence: every SIM activated by the dealer, topped up once at its own shop, never used again. Next step: hold the commission, audit the shop, compare the other dealers. [pause 0.6] A record month looked the same for both dealers; only the links told them apart. The same steps run on millions of records and answer in moments. Try it: start from the dealer with the matching shop.
- Action: case-file card (8 s) and the outro "Next: Evidence, Dossier and the sealed bundle · training F8".
- Impact: none.

---
