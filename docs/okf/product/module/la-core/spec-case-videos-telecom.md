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
  (STRUCT-01 to STRUCT-25), the relays (RELAY-1, RELAY-2). **Decoy:** the biggest receiver of all, a utility shop
  (MER-ELECTRIC): hundreds of payers, one way, ordinary amounts. The relay is second in the sweep but only passes money on.
- **Stakes:** the investigator is the operator's AML analyst, who owns the limit alerts. A cash-in limit that every check says
  is respected, and that is still beaten, is a limit nobody is enforcing.
- **Vocabulary:** *structuring* ("splitting one sum into amounts that each stay under a reporting limit"); *cash-in* ("a customer
  hands cash to an agent, who credits the wallet"). Defined in scenes S01 and S02.
- **Ground truth:** `tools/gen-la-mm-data.py` header, stories 1, 2 and 6 and 7: AG-18 cashes in for 25 wallets (STRUCT-01..25)
  inside 36 hours, each amount in the band 4 750 ≤ amount < 5 000 (`BAND_LO` = 0.95 × `TIER_LIMIT` 5 000); each wallet pays
  HUB-01 (the alert Measure fires on HUB-01, 25 legs, 25 payers); HUB-01 forwards 98 % within ten minutes to RELAY-1 and
  RELAY-2; the benign hub is MER-ELECTRIC (300 payers, one way, amounts under 4 400). No ground-truth JSON exists for this
  Space; the golden test is `LaMobileMoneyGoldenTest`. The plan's table said the relay is the busiest; the data says the
  hub leads and the relay follows, and the true decoy is the utility shop.
- **Screen truth:** saved view *Mm structuring band*; as Demo Analyst the wallets are `masked:…` ("31 entities masked",
  Reveal… greyed with *Needs See unmasked ids*). The reveal beat runs as Demo Admin on the Investigation *Mule hub and its
  chain* (as in F7). Quick question *Busiest nodes* names aliases.
- **Before:** Standard build, Mobile Money Space from a fresh unzip, Demo Analyst signed in, no Investigation open; the
  administrator scene reopens the seeded Investigation (the F7 reveal adds an audit entry, nothing else).
- **Lead:** one agent (AG-18) to review, the hub and its twenty-five wallets to hold. *What would change our mind:* if the
  twenty-five deposits came from twenty-five different agents over weeks, or from amounts all over the range, it would be a busy
  shop, not structuring. *Next actions:* review the agent's other cash-ins; hold the hub and its wallets; keep the wallets as the
  Entity List *STRUCT wallets* and watch the pattern with the existing Alert Rule `mm-structuring`.
- **Try it / Check:** *Try it:* open the saved view, press Busiest nodes, and start from the third name. *Check:* why is the utility
  shop not suspicious although it has the most payers? *Answer: its payers each pay once, in ordinary amounts, and nothing
  leaves; the pattern is many deposits under the limit meeting in one wallet.*

### S01 · Cold open · ~25 s
- Voice: Monday, nine in the morning. The cash-in limit on a basic wallet is five thousand, and not one deposit this month has crossed it. [pause 0.6] The compliance report is green. You are the analyst who has to say whether that is good news. [pause 0.5] It is not. It is what success looks like to someone who has read the rules.
- Chip: none
- Action: cold open over the Space's home page, the saved view *Mm structuring band* in My work; at 0:20 the intro card (4 s, teal bar, "Case file · The report is green · Mobile Money · Standard").
- Impact: none.

### S02 · Why every check stays green · ~30 s
- Voice: Every check looks at one deposit at a time. This deposit is under the limit, so it passes. The next one is under the limit, so it passes. [pause 0.5] Twenty-five wallets can each do that once, and no check ever sees the twenty-five. Splitting one sum into small amounts like this is called structuring. The pattern is not in any row. It is between them.
- Chip: none
- Action: story card "Who is who": *the hub wallet*, *the agent*, *the structuring wallets*, kept until the reveal; the saved view opens behind it.
- Impact: the story card names the three roles; nothing on the canvas is marked yet.

### S03 · The data in one breath · ~30 s
- Voice: Each wallet transaction is a record: a sending wallet, a receiving wallet, an amount and a time. [pause 0.4] A Job reads them into links between two wallets, and the amount and the time ride along on every link. That is the whole mapping. Say which two columns are the ends, and a table becomes a network you can walk.
- Chip: Data · Mappings
- Action: rail Data ▸ the transactions Dataset ▸ zoom on its Mappings area. [check in UI: the mapping's name and its From and To columns]
- Impact: mark the From and To columns of the mapping.

### S04 · A view that asks one question · ~40 s
- Voice: This saved view keeps only the cash-ins just under the limit: four thousand seven hundred and fifty up to five thousand. [pause 0.5] It asks one question: who is collecting them? Every wallet shows as an alias. This analyst works without seeing wallet ids, and the pattern does not need them. A funnel is a shape, not a name.
- Chip: Saved views · Reveal…
- Action: open the saved view *Mm structuring band*; hover the greyed Reveal… under the canvas. [check in UI: "31 entities masked" and the tooltip *Needs See unmasked ids*]
- Impact: mark the "entities masked" line and the greyed Reveal…; the funnel shape is the picture.

### S05 · First sweep, and the shop that is not a funnel · ~50 s
- Voice: First question: Busiest nodes. [pause 0.4] The top names are the hub wallet, a relay, and an agent. The relay only passes money on, so it is busy by design. Now clear the amount filter and ask again. The busiest receiver of all is a utility shop with hundreds of payers. Each pays once, in an ordinary amount, and nothing leaves. A popular shop is not a funnel. Set it aside.
- Chip: Busiest nodes
- Action: Busiest nodes on the saved view (the answer lists three rows: hub, relay, agent); then turn the band filter off (Display options or the saved view's filter) and press Busiest nodes again. [check in UI: how the filter is cleared and that the utility shop leads without it]
- Impact: grey "ruled out" marker on the utility shop; yellow on the hub; the relay marked "passes it on".

### S06 · The agent · ~45 s
- Voice: Look at the third name: the agent. Twenty-five links, exactly, and every one is a cash-in. [pause 0.5] Start a graph from the agent, and the funnel appears. Twenty-five wallets, each used once, all within thirty-six hours, all just under the limit. They pay one hub, and the hub passes it straight on.
- Chip: Start from these
- Action: Busiest nodes ▸ the agent's row ▸ Start from these ▸ hold on the drawn funnel ▸ labels on the hub. [check in UI: that Start from these draws the one-agent graph with the hub and relays]
- Impact: before/after hold: the funnel appears; mark the agent and the hub.

### S07 · Evidence you can defend · ~40 s
- Voice: Is this a guess? Open the table under the graph. Every line is a row: from, link kind, to, the amount and the time. [pause 0.5] Twenty-five deposits in the band, an hour or two apart. Nothing is estimated. The graph shows the shape, and the table lets a colleague check every line.
- Chip: Rows · Export
- Action: dock ▸ Rows ▸ sort by amount ▸ hold on the band values; point at Export. [check in UI: the dock tab name and the sort control]
- Impact: before/after hold: the dock opens; mark the amount column.

### S08 · Names, with a reason · ~40 s
- Voice: Now a name is needed, so the administrator reveals. [pause 0.5] Press Reveal, and the dialog says it plainly: the real ids, on this screen only, each one recorded with your reason. Type the reason: confirm the hub before the wallets are frozen. The labels change, and the audit trail knows who looked, and why.
- Chip: Reveal…
- Action: sign out ▸ Demo Admin ▸ File ▸ Open… ▸ Investigations ▸ *Mule hub and its chain* ▸ Reveal… ▸ Reason `confirm the hub before the wallets are frozen` ▸ Reveal (the F7 steps).
- Impact: before/after hold: aliases turn into HUB-01, RELAY-1 and the STRUCT ids; mark the confirmation message.

### S09 · What the team does next · ~45 s
- Voice: Here is the lead, and what happens to it. [pause 0.5] One agent to review. The hub and its twenty-five wallets to hold. Keep the wallets as an Entity List, and every graph in this Space marks them from now on. Then watch for the next twenty-five: an Alert Rule on this pattern fires the day it starts, not the month after.
- Chip: Entity Lists · Alert Rule
- Action: rail Entity Lists ▸ the list *STRUCT wallets* ▸ hold; then Settings ▸ Investigation settings ▸ Measures and templates ▸ the Alert Rule `mm-structuring` named on screen. [check in UI: the rule is listed as bound; do not create one]
- Impact: mark the list's row and the Alert Rule's name.

### S10 · The case file · ~25 s
- Voice: The case file. Lead: one agent, one hub, twenty-five wallets. Evidence: twenty-five deposits in the band, inside thirty-six hours, every one a row. Next step: review the agent, hold the wallets, keep the list. [pause 0.6] Each deposit was legal. Only the links show them meeting. The same steps run on millions of records and answer in moments. Try it: start from the third name in Busiest nodes. To make a case like this defensible, see the training episode on the step log.
- Chip: none
- Action: case-file card (8 s: Lead · Evidence · Next step · Try it), then the outro "Next: Make it defensible · training F4".
- Impact: none.

---

## Telecom_DeviceFarm

> **Nine people who never met** · Standard · Mobile Money Space (`la-mobile-money`) · one handset behind nine wallets · 4 min 30 s

- **Cast:** nine wallets (FARM-1 to FARM-9) on one handset (DEV-FARM-01). **Decoys:** a household of three wallets on one national
  id, and a family phone carried by four wallets. The placeholder values (UNKNOWN, blank) the Job leaves out are the third
  trap.
- **Stakes:** the investigator is in fraud operations; a sign-up bonus has paid out nine times to what the register says are nine
  people. The register checks each person once, so it cannot see that the people share a phone.
- **Vocabulary:** *KYC* ("the identity check an operator makes when it opens a wallet"); *handset* ("the physical phone,
  not the SIM or the number"). Defined in S01 and S02.
- **Ground truth:** `tools/gen-la-mm-data.py` header story 3 and lines 129-135 and 217-250: FARM-1..9 share DEV-FARM-01 and
  hold nine different national ids; all are tier T1; each takes three cash-ins at AG-05, pays MER-03 and MER-05 four times and
  cashes out twice at AG-30. Benign: a household of three on `NID-HOUSEHOLD-01`, a family phone `DEV-FAMILY-01` on four
  wallets; placeholders (UNKNOWN, blank, 000000000000000, PENDING) are held by 5-8 wallets each and excluded by the Job. The
  plan's table is confirmed; what separates the farm from the family phone is scale and sameness: nine wallets, all lowest tier,
  the same two agents and two merchants.
- **Screen truth:** saved view *Mm device farm*, 16 entities masked for the analyst (`masked:…` labels, labels on at this
  size); Reveal… greyed. The tail uses Settings ▸ Identities as Demo Admin (as F7 S08).
- **Before:** Standard build, Mobile Money Space from a fresh unzip, Demo Analyst; the tail scene signs in as Demo Admin.
- **Lead:** one group of nine wallets, to review together, not nine customers: for the fraud and identity teams, with the shared
  handset and the shared agents as evidence. *What would change our mind:* if the nine wallets had different handsets most days,
  or paid nine different merchants, the shared phone would be a coincidence. *Next actions:* freeze the bonus for the group;
  record that the wallets are one entity (an identity fact); watch new wallets that appear on the same handset.
- **Try it / Check:** *Try it:* in Settings ▸ Identities as Demo Admin, use Find group on one farm wallet. *Check:* why is the
  family phone not flagged? *Answer: four wallets on one phone is a family; the farm is nine wallets, all lowest tier, using the
  same agents and merchants.*

### S01 · Cold open · ~25 s
- Voice: Nine customers. Nine different national ids. Every one of them passed the identity check. [pause 0.6] You work in fraud operations, and a sign-up bonus has just paid out nine times. On paper, those nine people have never met.
- Chip: none
- Action: cold open over the saved view's thumbnail; at 0:20 the intro card ("Case file · Nine people who never met · Mobile Money · Standard").
- Impact: none.

### S02 · Why the register cannot see it · ~30 s
- Voice: The register holds people, one clean row each. The check made when a wallet is opened is called KYC, short for know your customer, and it checks the person. [pause 0.5] Nothing in it says which phone opened which wallet. The phone is the handset, and the connection between these nine only exists as a link.
- Chip: none
- Action: story card "Who is who": *a wallet*, *the shared handset*, *a family*; saved view opens behind it.
- Impact: the story card names the roles.

### S03 · Links the transactions do not hold · ~35 s
- Voice: Transactions say who paid whom. They do not say who shares a phone. [pause 0.4] A Job adds those links: two wallets on one handset, two wallets on one identity. Placeholder values such as unknown are left out on purpose. Without that, half the Space would look related to the other half.
- Chip: Data · Mappings
- Action: rail Data ▸ the wallets Dataset ▸ zoom on Mappings. [check in UI: the link kinds the Job adds, shared handset and shared identity, and the exclusions]
- Impact: mark the two added link kinds.

### S04 · Groups that look alike · ~45 s
- Voice: The saved view draws exactly those links. Small groups appear. A household of three wallets shares one national id. A family phone carries four wallets. [pause 0.5] Both are ordinary, and both are on screen. Sharing is not fraud. The question is which group is different, and how.
- Chip: Saved views · Legend
- Action: open the saved view *Mm device farm* ▸ Legend (the two link kinds) ▸ hold on the groups. [check in UI: 16 entities masked, the group sizes 3, 4 and 9]
- Impact: grey "ruled out" markers on the household and on the family phone, after S05's evidence; yellow on the nine.

### S05 · First sweep: a dead heat · ~40 s
- Voice: Ask Busiest nodes. [pause 0.4] There is no leader. Five wallets are tied at eight links each, and every one of them is linked to every other. A shop or an agent does not look like that. A shared device does.
- Chip: Busiest nodes · Tight groups
- Action: Busiest nodes ▸ hold on the tied rows; then Tight groups ▸ hold on the group of nine. [check in UI: five tied rows at eight links; Tight groups returns one group of nine]
- Impact: mark the tied rows; mark the group of nine.

### S06 · Same handset, same habits · ~50 s
- Voice: Start from one of them. Nine wallets on one handset, and nine different national ids. [pause 0.5] Now look at what they do. Each takes cash in from the same agent and pays the same two merchants. Each cashes out at the same second agent. A family phone is shared by people who live differently. These wallets live exactly alike, and all of them are at the lowest identity tier.
- Chip: Start from these · Expand · G
- Action: Busiest nodes ▸ a tied row ▸ Start from these ▸ Expand one degree (G) to bring in the agents and merchants ▸ hold. [check in UI: the shared agent AG-05, merchants MER-03 and MER-05, agent AG-30 appear around the nine, masked or readable]
- Impact: before/after hold: the shared agents and merchants appear around the nine; mark them.

### S07 · Evidence you can defend · ~35 s
- Voice: Open the table under the graph. Each link from one wallet to another has a row, with the shared handset behind it. [pause 0.5] Nine wallets, one device, nine identities, and the same few counterparties. A colleague can check every line.
- Chip: Rows
- Action: dock ▸ Rows ▸ filter to the shared-handset links. [check in UI: the link kind name in the Rows filter]
- Impact: before/after hold: the dock opens; mark the handset column.

### S08 · One entity, said once · ~40 s
- Voice: The nine wallets are one operation, so tell the product. As the administrator, open Settings, then Identities. [pause 0.5] An identity fact says that two identifiers are one entity, with a reason. Here, the farm wallets, one handset. The group shows who said so and why, and the assertion can be taken back.
- Chip: Settings · Identities
- Action: sign out ▸ Demo Admin ▸ open any Investigation ▸ Settings ▸ Identities ▸ hold on the group and its reason (as F7 S08). [check in UI: the seeded group reads two farm wallets and the assertion by analyst]
- Impact: mark the group, its reason and the Retract button (not pressed).

### S09 · What the team does next · ~40 s
- Voice: The lead: one group of nine wallets, to review together. Not nine customers. [pause 0.5] The fraud team freezes the bonus for the group. The identity team looks at who opened them. And a watch on that handset catches the tenth wallet before it is paid.
- Chip: Entity Lists
- Action: rail Entity Lists ▸ the list *Device farm wallets* ▸ hold. [check in UI: the list exists in this Space and holds the farm wallets]
- Impact: mark the list row.

### S10 · The case file · ~25 s
- Voice: The case file. Lead: one handset, nine wallets, nine identities. Evidence: the shared-handset links, and the same agents and merchants. Next step: freeze the bonus, record one entity, watch the handset. [pause 0.6] In the register each wallet is a clean row. The handset that joins them only exists as a link. The same steps run on millions of records and answer in moments. Try it: find the group of a farm wallet in Identities.
- Chip: none
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
- Chip: none
- Action: cold open over the Telecom Space's home page; at 0:20 the intro card ("Case file · Forty swaps, one login · Telecom · Standard").
- Impact: none.

### S02 · Three files, no single answer · ~30 s
- Voice: The swap is in one file, the staff login in another, the wallet payments in a third. [pause 0.5] Each file is clean on its own. A login is a staff member's account that can request a swap, and most swaps are honest. Only the links put a swap and an empty wallet on one line.
- Chip: none
- Action: story card "Who is who": *a login*, *a swapped number*, *a new handset*; the saved view opens behind it.
- Impact: the story card names the roles.

### S03 · Linking the pieces · ~30 s
- Voice: A Job reads the files and links the pieces. A swap links to the login that made it. A wallet links to the handset it was used from, and to the wallets it paid. [pause 0.4] The Dataset says which end is which, and the time puts them in order.
- Chip: Data · Mappings
- Action: rail Data ▸ the screening links Dataset ▸ zoom on Mappings. [check in UI: the Dataset name and the link kinds swap, handset and payment]
- Impact: mark the link kinds.

### S04 · The view of bursts · ~35 s
- Voice: The saved view draws swaps in bursts: logins, the numbers they swapped, and where the money went afterwards. [pause 0.5] Most of it looks like a busy day in a telecom, with stores and call centres swapping SIMs all day. It is too big to read, so ask a question.
- Chip: Saved views · Display options
- Action: open the saved view *Mfs sim swap bursts* ▸ hold on the two clusters. [check in UI: "node labels off above 80 nodes" and the two clusters]
- Impact: hold on the picture; no mark.

### S05 · First sweep, and the obvious suspect · ~50 s
- Voice: Busiest nodes. [pause 0.4] The busiest login has a hundred and sixty swaps. The obvious suspect. But it is a store in a SIM-upgrade drive, and its customers kept their money. Busy is not suspicious. Set it aside, and look at the second name: a call-centre login with forty swaps.
- Chip: Busiest nodes
- Action: Busiest nodes ▸ hold on S-72 (160) and S-41 (40) ▸ Add to exclusion list on S-72 is offered, point at it, do not press. [check in UI: the rows S-72, S-41, WL-07938, WL-07936]
- Impact: grey "ruled out" marker on S-72, yellow on S-41.

### S06 · Forty swaps, thirty in ten days · ~45 s
- Voice: Start from the second name. Forty swaps, and thirty of them fall inside ten days in June. [pause 0.5] Each of those thirty numbers was used from one of six new handsets within the hour. A customer who lost a phone does not do that. A person working down a list does.
- Chip: Start from these · Expand · G
- Action: Busiest nodes ▸ S-41 ▸ Start from these ▸ Expand one degree (G) ▸ hold on the swaps and the six handsets. [check in UI: the number of entities drawn and the handset labels]
- Impact: before/after hold: the swaps and the handsets appear around S-41; mark the six handsets.

### S07 · Where the money went · ~45 s
- Voice: Follow the money. Every wallet was drained within two hours, to the same five wallets, and cashed out at two agents. [pause 0.5] Open the table under the graph: each swap, and each payment out of the wallet that followed it, is a row. Thirty empty wallets, five places the money went.
- Chip: Rows · Expand · G
- Action: Expand one more degree from the victims ▸ dock ▸ Rows ▸ hold. [check in UI: the five mule wallets WL-07936..07940 and the two agents A-088, A-093]
- Impact: mark the five mules and the two agents; before/after hold for the dock.

### S08 · Make it defensible · ~70 s
- Voice: The lead has to survive a challenge, so it becomes an Investigation. Open SIM-swap wallet takeover. [pause 0.5] The step log lists every step in order, with who took it and why, and nobody can edit a row afterwards. The team page shows the manager as the second pair of eyes: a big expand waits for approval. And the Dossier packs it all with a seal, so anyone can verify that nothing changed.
- Chip: Step log · Members · Build Dossier
- Action: File ▸ Open… ▸ Investigations ▸ *SIM-swap wallet takeover* ▸ dock ▸ Step log ▸ Investigation actions ▸ Members… ▸ Settings ▸ Investigation settings ▸ Dossier ▸ Verify (as F4, F5 and F8; nothing approved or sealed here). [check in UI: the held request's line, "Dossier verified" wording]
- Impact: mark a step row, the held-request line and the verified line.

### S09 · What the team does next · ~40 s
- Voice: The lead: one login to investigate, five wallets and two agents to trace. [pause 0.5] The call centre suspends that login's swap right today. The five wallets are frozen. The two agents are asked for the cash-out records. The wallets are kept as an Entity List, so the next case starts from them.
- Chip: Entity Lists
- Action: rail Entity Lists ▸ the list *Mule wallets to freeze* ▸ hold. [check in UI: the list exists in this Space]
- Impact: mark the list row.

### S10 · The case file · ~25 s
- Voice: The case file. Lead: one login, five wallets, two agents. Evidence: thirty swaps in ten days, each followed by a drained wallet. Next step: suspend, freeze, trace. [pause 0.6] A swap alone was routine. A payment alone was routine. The links joined them. The same steps run on millions of records and answer in moments. Try it: start from the second name in Busiest nodes.
- Chip: none
- Action: case-file card (8 s) and the outro "Next: Teams and four-eyes · training F5".
- Impact: none.

---

## Telecom_WangiriNetwork

> **One operation, four masks** · Standard · Other Examples Space (`la-showcase`) · four fraud reports, one network · 5 min

- **Cast:** the suspect number (WANGIRI-A, 99979100001), a second SIM on its handset (WANGIRI-B, 99979100002), the premium-rate
  number its callbacks are forwarded to (PREMIUM-01, 99891000001), then IRSF-01, five numbers on one identity (SUBFRAUD-01..05),
  a SIM box (SIMBOX-01) and mules (MULE-01..). **Decoy:** the taxi-dispatch line (99970000100), called by 650 distinct
  subscribers.
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
  called by 650 distinct subscribers) is flagged and not expanded. **Corrections:** v1 said "four hundred numbers called it
  back" (it is 400 rung, 40 called back) and used "257 calls" for the taxi line (650 distinct callers). The plan's table angle
  ("four teams, four reports, one network") is confirmed and gains the decoy.
- **Screen truth:** saved view *Telecom fraud network*: 767 entities masked for the analyst and a banner *Result cut short:
  the server stopped reading at its row limit. Narrow the Dataset or add a filter.* The suspect number is typed as a start value
  (it comes from the report, as in F2) and is accepted while masked.
- **Before:** Standard build, Other Examples Space from a fresh unzip, Demo Analyst. The film starts and saves one Investigation
  (the F2 steps); a re-recording needs a fresh unzip.
- **Lead:** one operation to report, link by link, not four cases. *What would change our mind:* if the premium number's other
  callers had no link to the identity or the handset, the four reports would be four separate frauds. *Next actions:* block the
  premium number and the two SIMs; send the identity ring to subscription-fraud review; hand the SIM box and mules to the
  carrier team with the chain as the evidence.
- **Try it / Check:** *Try it:* start from the suspect number and expand one degree at a time with Expand · G, reading each new
  link kind. *Check:* why not start from the busiest number? *Answer: it is a taxi line that hundreds of people call; the
  number that receives calls it never made leads to the fraud.*

### S01 · Cold open · ~25 s
- Voice: Four reports land on four desks in one week. A missed-call scam. An international revenue-share ring. A false subscriber identity. A SIM box. [pause 0.6] Four teams close four tickets. You wonder whether it is one operation, wearing four masks.
- Chip: none
- Action: cold open over the home page; at 0:20 the intro card ("Case file · One operation, four masks · Other Examples · Standard").
- Impact: none.

### S02 · Each team sees one slice · ~35 s
- Voice: A network records every call it carries, and each team looks at its own slice. Wangiri, a Japanese word, is the missed call: the phone rings once so you call back a premium number. [pause 0.5] Each trick looks like a separate crime. The forwarding and the shared handset that join them were in the records all along.
- Chip: none
- Action: story card "Who is who": *a missed-call number*, *a premium number*, *a shared handset*; the saved view opens behind it.
- Impact: the story card names the roles.

### S03 · Calls into links · ~30 s
- Voice: Each call is a record: who rang, who was rung, when, for how long, and the SIM and handset behind it. [pause 0.4] A Job turns calls into links between numbers: who called whom, who forwarded to whom, and which SIMs share a handset. Numbers are the entities, and the call type names the kind of link.
- Chip: Data · Mappings
- Action: rail Data ▸ the telecom links Dataset ▸ zoom on Mappings. [check in UI: the Dataset name `telecom_links_dataset` and its link kinds]
- Impact: mark the link kinds voice, forwarding and shared device.

### S04 · A busy view, and a banner · ~40 s
- Voice: The saved view draws the suspicious corner of the network. It is large, and the server says why it looks cut: it stopped reading at its row limit. [pause 0.5] The product tells you instead of drawing a partial picture quietly. Every number is an alias for this analyst, so you will follow roles, not digits. Narrow it down.
- Chip: Saved views
- Action: open the saved view *Telecom fraud network* ▸ hold on the "Result cut short" banner and the "767 entities masked" line. [check in UI: the banner wording and the masked counter]
- Impact: mark the banner and the masked line.

### S05 · First sweep: a taxi line · ~40 s
- Voice: Busiest nodes. [pause 0.4] The top name is a taxi-dispatch line. Hundreds of people call a taxi, so it is busy for a good reason. Set it aside. Look instead for a number that receives calls it never asked for, and that forwards them on.
- Chip: Busiest nodes
- Action: Busiest nodes on the saved view ▸ hold on the first row. [check in UI: the first row is the taxi line, as an alias]
- Impact: grey "ruled out" marker on the taxi line.

### S06 · The missed-call number · ~50 s
- Voice: Start from the number the missed-call reports named. [pause 0.5] It rings four hundred phones for a second or two and hangs up. Forty people call back. Every callback is forwarded to one premium-rate number. And a second SIM, already on a blocklist, sits in the same handset, and ran the same trick earlier. That is how the money is made.
- Chip: Investigation · Start from · One entity
- Action: Investigation tab ▸ Start from One entity ▸ Dataset `telecom_links_dataset` ▸ Entity Type MSISDN ▸ `99979100001` ▸ Start ▸ Save… (the F2 steps) ▸ Expand to degree 1 ▸ hold. [check in UI: *Drew 1 entity*, the forward link to the premium number and the shared device link]
- Impact: before/after hold: the second SIM and the premium number appear; mark them.

### S07 · One degree at a time · ~50 s
- Voice: Now widen it one degree at a time, and watch what joins. [pause 0.5] The premium number receives voice calls pumped in by a revenue-share ring. That ring's id document belongs to five numbers on one synthetic identity. One of their payment cards pays for a SIM box. Another line passes to a chain of mules. Four reports, one chain.
- Chip: Expand · G · Step log
- Action: Expand one degree at a time (G) three or four times, with a pause after each ▸ Open the Step log ▸ hold on the rows. [check in UI: each degree adds the IRSF number, the five identity numbers, the SIM box and the mule chain, as the hub taxi line stays unexpanded]
- Impact: before/after hold at each degree; mark the new link kind each time.

### S08 · Evidence you can defend · ~35 s
- Voice: Is it a guess? The Step log lists each expand with its limits, in order, and the table holds every link as a row. [pause 0.5] Each call-back, and the forward that follows it, is a line a colleague can read. The chain is a result you can replay, not a drawing.
- Chip: Step log · Rows
- Action: dock ▸ Step log ▸ then Rows ▸ hold. [check in UI: the step names Start, Expand, Expand, Expand]
- Impact: mark the expand rows.

### S09 · What the team does next · ~40 s
- Voice: The lead: one operation, link by link, not four cases. [pause 0.5] Block the premium number and the two SIMs on the handset. Send the five identity numbers to subscription-fraud review. Hand the SIM box and the mules to the carrier team, with the chain attached.
- Chip: Entity Lists
- Action: rail Entity Lists ▸ the list *Telecom numbers under watch* ▸ hold. [check in UI: the list exists in this Space]
- Impact: mark the list row.

### S10 · The case file · ~25 s
- Voice: The case file. Lead: one operation behind four reports. Evidence: a chain of links from the missed-call number to the mules, each step a row. Next step: block, review, hand over. [pause 0.6] Each team saw one trick. The links joined them. The same steps run on millions of records and answer in moments. Try it: start from the suspect number and widen it yourself.
- Chip: none
- Action: case-file card (8 s) and the outro "Next: Expand with intent · training F2".
- Impact: none.

---

## Telecom_DealerCommission

> **Two record months** · Standard · Telecom Space (`la-telecom`) · commissions on SIMs nobody used · 4 min 45 s

- **Cast:** the dealer (DL-112), its single shop (POS-0008), three activation devices (DEV-00204 to DEV-00206), four hundred SIMs.
  **Decoy:** a campus dealer (DL-163) with a record month of its own: 350 SIMs topped up at ten shops and used every month.
- **Stakes:** the investigator audits dealer commissions, which are paid for each SIM activated. A record month is good news, so a
  report of activations cannot separate the dealer who earned it from the one who manufactured it.
- **Vocabulary:** *commission* ("a payment per SIM activated"); *top-up* ("credit added to a prepaid number"). Defined in S01.
- **Ground truth:** `tools/gen-la-telco-ra-data.ground-truth.json`, key `dealer_commission_fraud` and
  `stories.dealer_commission_fraud` (view `dealer_commission_fraud`): dealer DL-112; devices DEV-00204, DEV-00205, DEV-00206;
  shop `POS-0008`; 400 subscribers; weeks 2026-02-02 to 2026-03-09 (six weeks); lookalike dealer DL-163 with 350 subscribers
  and ten campus shops (`campus_pos`). From `gen-la-telco-ra-data.py` lines 234-260 and 358-359: each fraud SIM has exactly one
  top-up of 5.00 at POS-0008 within two days of activation and one usage month; each DL-163 SIM tops up at campus shops
  (amounts 10 or 20) with at least six top-ups and thirteen usage months. The plan's table said "the top-selling dealer vs the
  dealer whose SIMs are never used": the data says the decoy is the *other record-month dealer* (DL-163), not a quiet one.
- **Screen truth:** saved view *Dealer commission fraud*: two large hairballs, no masking counter (readable ids), labels on hover
  above 1 000 links; Busiest nodes names DL-112, POS-0008 and DL-163. The Investigations in this Space are *SIM-swap wallet takeover*
  and *Unbilled usage at one dealer* (another story), so the film does not open one.
- **Before:** Standard build, Telecom Space from a fresh unzip, Demo Analyst; the film saves one Investigation from DL-112 (the
  F2 steps) in S08; a re-recording needs a fresh unzip.
- **Lead:** one dealer's commissions to audit, with four hundred SIMs, three devices and one shop as the evidence. *What would
  change our mind:* if the SIMs were topped up at many shops and used every month, as DL-163's are, the spike would be
  honest demand. *Next actions:* hold the dealer's commission; send the three devices and the shop to the dealer-audit team;
  keep the SIMs as an Entity List and compare other dealers against the same shape.
- **Try it / Check:** *Try it:* press Busiest nodes, start from the dealer with the matching shop, and count the devices. *Check:*
  why is the second record-month dealer not suspect? *Answer: its SIMs top up at ten shops and are used every month; the
  suspect's SIMs top up once, at its own shop, and are never used again.*

### S01 · Cold open · ~25 s
- Voice: Two dealers had a record month. Dealers are paid commission for every SIM they activate, so a record month is a good month. [pause 0.6] One of them earned it. The other found a way to manufacture it. A report of activations cannot tell them apart.
- Chip: none
- Action: cold open over the Telecom Space home page; at 0:20 the intro card ("Case file · Two record months · Telecom · Standard").
- Impact: none.

### S02 · A spike looks the same · ~30 s
- Voice: In the commission report, both dealers are a spike of new SIMs. Each SIM has an activation, a dealer, a device and, for real customers, a top-up and some use. [pause 0.5] The report counts the activations. It does not ask who paid for them, or whether anyone used them. Those answers live in the links.
- Chip: none
- Action: story card "Who is who": *a dealer*, *a shop*, *a device*, *a SIM*; the saved view opens behind it.
- Impact: the story card names the roles.

### S03 · From subscribers to links · ~30 s
- Voice: Each subscriber is a record: the number, the SIM, the dealer who activated it, when, the plan, and the device. [pause 0.4] A Job reads each one into links: a SIM to its dealer, to its device, and to the shop where it was topped up. Numbers, dealers, devices and shops are all entities.
- Chip: Data · Mappings
- Action: rail Data ▸ the dealer Dataset ▸ zoom on Mappings. [check in UI: the Dataset name and the link kinds dealer, device and shop]
- Impact: mark the three link kinds.

### S04 · Two giants · ~35 s
- Voice: The saved view draws dealers, their SIMs, the devices and the shops that topped them up. Two dealers tower over it, each with hundreds of SIMs. [pause 0.5] It is too much to read, and both look alike. Ask a question.
- Chip: Saved views
- Action: open the saved view *Dealer commission fraud* ▸ hold on the two clusters. [check in UI: two clusters, labels on hover]
- Impact: no mark; the two clusters are the picture.

### S05 · First sweep: a dealer and a shop · ~45 s
- Voice: Busiest nodes. [pause 0.4] A dealer with four hundred links. And a shop with exactly four hundred too. The second dealer has three hundred and fifty, and no shop anywhere near that. A dealer and one shop with the same number is not how a market looks. It is how a loop looks.
- Chip: Busiest nodes
- Action: Busiest nodes ▸ hold on DL-112 (400), POS-0008 (400), DL-163 (350). [check in UI: the three rows]
- Impact: yellow on DL-112 and POS-0008; DL-163 marked for the next scene.

### S06 · The campus dealer · ~40 s
- Voice: Start from the second dealer first. Its SIMs are topped up at ten different shops, every month, and used every month. A student campus. Real people. [pause 0.5] That is what an earned record month looks like. Set it aside, and keep it as the yardstick.
- Chip: Start from these
- Action: Busiest nodes ▸ DL-163 ▸ Start from these ▸ hold on the ten shops. [check in UI: the ten campus shops around the dealer]
- Impact: grey "ruled out" marker on the dealer; mark the ten shops.

### S07 · Four hundred SIMs, one shop · ~50 s
- Voice: Now start from the dealer with the matching shop. Four hundred SIMs, every one activated by this dealer, from just three devices, in six weeks. [pause 0.5] Each SIM was topped up once, with five, at the dealer's own shop, and never used again. Nobody was meant to use these SIMs. They exist to be counted.
- Chip: Start from these · Rows
- Action: Busiest nodes ▸ DL-112 ▸ Start from these ▸ hold on the three devices and the shop ▸ dock ▸ Rows ▸ hold on a few rows. [check in UI: three devices, one shop, the top-up amount 5.00 in the rows]
- Impact: before/after hold: the three devices and the shop appear; mark them.

### S08 · Evidence, saved · ~45 s
- Voice: Every activation is a row: the SIM, the dealer, the device. [pause 0.5] To keep the work, save it as an Investigation. Start from the dealer, and save it with a purpose: commissions on SIMs that were never used. Now the steps are recorded, and an auditor can open them.
- Chip: Investigation · Save…
- Action: Investigation tab ▸ Start from One entity ▸ Dataset ▸ Entity Type ▸ `DL-112` ▸ Start ▸ Save… ▸ name and purpose ▸ Save (the F2 steps). [check in UI: the Dataset and entity type names for dealers]
- Impact: mark the saved-name header and the first step row.

### S09 · What the team does next · ~40 s
- Voice: The lead: one dealer's commissions to audit. [pause 0.5] Hold the payment. Send the three devices and the shop to the dealer-audit team. Keep the SIMs as an Entity List, and compare every other dealer against this shape: many SIMs, one shop, no use.
- Chip: Entity Lists · Add to a list
- Action: Busiest nodes or the table ▸ Add to a list ▸ choose an Entity List (or point at *Add to a list* without pressing). [check in UI: whether a suitable list exists in this Space; do not create one]
- Impact: mark Add to a list.

### S10 · The case file · ~25 s
- Voice: The case file. Lead: one dealer, one shop, three devices. Evidence: four hundred SIMs topped up once and never used, in six weeks. Next step: hold, audit, compare. [pause 0.6] In the commission report both dealers are a spike. The links show who paid for them. The same steps run on millions of records and answer in moments. Try it: start from the dealer with the matching shop and count the devices.
- Chip: none
- Action: case-file card (8 s) and the outro "Next: Recorded clean-up and scope · training F3".
- Impact: none.
