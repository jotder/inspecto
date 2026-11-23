---
type: Reference
title: "Inspecto LA case videos v2 — specs: public sector, healthcare, logistics, enterprise"
description: "Six case-film specs (bid rotation, address farm, referral kickbacks, inside job, fictitious vendor, access creep) written to the v2 standard: a case film in ten scenes with a decoy, a lead shown as action, chips, impact marks and authored pauses."
resource: tools/la-demo-cases.mjs
tags: [module, la-core, link-analysis, video, case-video, spec]
timestamp: 2026-11-19T00:00:00Z
item: module.la-core
audience: internal
status: current
verified: 2026-11-19 last-edit
code: la/inspecto-la-core
---

# Inspecto LA case videos v2 — public sector, healthcare, logistics, enterprise

> **Recording environment (operator, 2026-11-20):** the Standard edition with the fake Demo Auth sign-in and the bundled example Spaces, from a fresh `la-standard.zip` unzip per lane; plan [§4.5](../../../../superpower/2026-11-19-la-case-videos-v2-plan.md).

> Six of the twenty case-film specs of [the v2 plan](../../../../superpower/2026-11-19-la-case-videos-v2-plan.md): the arc (§3),
> the voice, audio and picture standard (§4) and the spec format (§5) are defined there and are not repeated. They replace,
> case by case, the generated v1 scripts in [brochure-video-demo-case-scripts.md](brochure-video-demo-case-scripts.md).
> Format is the training guide's, so `tools/render-la-demo-video.mjs <id> --script <this file> --episode <spec>.episode.json` reads
> it unchanged. Draft, not recorded, not reviewed by product; `[check in UI: …]` marks a detail to confirm on the live product
> before recording. Every figure is cited to its generator and ground-truth key in the case's *Ground truth* line.

**Checked on the live Standard build as Demo Analyst, 2026-11-19** (saved view opened in each Space; reads only):

| Case | Space · saved view | What the analyst sees |
|------|--------------------|-----------------------|
| Bid rotation | la-public-sector · *Bid rotation* | ids readable; node labels show on hover above 1,000 links |
| Address farm | la-public-sector · *Address farm* | ids readable; node labels are off above 80 nodes (turn them on in Display options) |
| Referral kickbacks | la-healthcare · *Referral kickbacks* | ids readable (PR-, OW-, AD-) |
| Inside job | la-logistics-industry · *Driver team inside job* | ids readable (DRV-, EMP-, TR-, LR-) |
| Fictitious vendor | la-enterprise · *Ap vendor identity* | **masked: the canvas reads "291 entities masked · Reveal…"**; the v1 film named ids over this graph |
| Access creep | la-enterprise · *Access creep* | ids readable; the view takes 10–25 s to draw (wait for the nodes, not for a fixed time) |

**Before, for all six:** a fresh unzip of `la-standard.zip`, signed in as Demo Analyst (Demo Admin for the one reveal in the
vendor film), no Investigation open. The films use the **saved views**, not the seeded Investigations: the Enterprise Space's
Investigations hold steps from earlier recordings. Every dialog that would save something is opened and cancelled; nothing is
created, so a film can be recorded twice from the same install.

**Standard tail used here (one control per film, never a tour):** *New list…* on the Entity Lists page (bid rotation,
referral kickbacks, access creep), the *Attach to Case* dialog (address farm, fictitious vendor), the *Save…* dialog of the
Investigation tab (inside job). Each is shown, read aloud in one sentence and cancelled. The full Standard story is the plan's
shared module (§4.4).

## Episodes in this file

| Id | Industry · Space | Lead in one line |
|----|------------------|------------------|
| [PublicSector_BidRotation](#publicsector_bidrotation) | Public sector · la-public-sector | four firms bid alone on twenty-four northern tenders and win in turn; two share a director |
| [PublicSector_AddressFarm](#publicsector_addressfarm) | Public sector · la-public-sector | one address, fourteen single-person claims under five surnames, paid into three accounts |
| [Healthcare_ReferralKickbacks](#healthcare_referralkickbacks) | Healthcare · la-healthcare | an imaging centre fed by two practices, one of whose owners lives with the centre's owner |
| [Logistics_InsideJob](#logistics_insidejob) | Logistics · la-logistics-industry | twelve short loads, one driver pair, and one night-shift scanner |
| [Enterprise_FictitiousVendor](#enterprise_fictitiousvendor) | Enterprise · la-enterprise | a vendor that shares a bank account and an address with an employee |
| [Enterprise_AccessCreep](#enterprise_accesscreep) | Enterprise · la-enterprise | one manager gave four reports payroll and treasury, and one of them changed a vendor's bank details |

## PublicSector_BidRotation

> **Bid rotation: four firms, one order** · Standard · Public sector (la-public-sector) · the rotating winners of a highways
> unit · 4:30 · plan §6 row 15

- **Cast:** the four rotating firms (CO-0117, CO-0242, CO-0388, CO-0561) and the director shared by two of them (P-00001). **Decoy:** the three firms that win most of Highways South (CO-0021, CO-0055, CO-0090) against real rivals. Ids readable.
- **Stakes:** a procurement integrity officer in a roads authority. Every tender had several bidders, so by the book each was competitive. A later review will ask why nobody asked.
- **Vocabulary:** *tender*, a call for bids on one contract. *Cover bid*, a bid made to lose. *Rotation*, bidders taking turns to win. Each said once in S02.
- **Ground truth:** `tools/gen-la-procurement-data.ground-truth.json` · `bid_rotation` (`firms`, `unit` Highways North, `tenders_in_order` 24, `winners_in_order`, `shared_by`, `shared_director` P-00001, `lookalike_firms`, `lookalike_unit` Highways South, `lookalike_win_share` 0.744); the losers bid 5–12 % above the winner, `gen-la-procurement-data.py` line 13 and the story-1 block.
- **Screen truth** (checked live on a fresh Standard unzip, Demo Analyst, 2026-11-22): the saved view *Bid rotation* draws 512 nodes and 1,291 links, so labels show only on hover; Busiest nodes answers CO-0021 (147 links), CO-0055 (132), CO-0090 (124), CO-0242 (36) and CO-0388 (34). *Start from these* on CO-0242 draws one entity and thirty linked nodes: the tenders it bid on or won, and four directors. A double-click on P-00001 brings in CO-0561 and the address AD-00001. The Rows table lists the link kinds `directed_by`, `bid_on` and `won` (CO-0242: 36 rows). Ids are readable, nothing is masked.
- **Before:** fresh unzip of `la-standard.zip` with the fake Demo Auth sign-in, Public sector Space, Demo Analyst; nothing is saved or changed.
- **Lead:** four firms are the only bidders on twenty-four Highways North tenders and win in a fixed order, the losers always between five and twelve percent above the winner; two of them share a director. **What would change our mind:** one real third bidder with a different price on any of the twenty-four, or a director who left before the first bid. **Next actions:** refer the twenty-four tenders for audit; ask for the document authors and addresses of the four bids; keep the four firms and the director as an Entity List.
- **Voice style:** cold open 1.04; the reveal scene (S06) holds 0.7 s before "Two rivals, one director." Lexicon: CO-0242 "c o zero two four two", P-00001 "p zero zero zero zero one"; say *tender* as "tender", not "T".
- **Try it:** on the shipped zip, open the saved view *Bid rotation* and run Busiest nodes. Which names lead, and are they the rotation? *Answer: the leaders are the three firms that win most of Highways South; they face real rivals. The rotation is further down, at the firms with thirty-odd links.*
- **Check:** why is the firm that wins most often not the lead? *Answer: winning often against real rivals is competition; the lead is firms that never face one and take turns.*

### S01 · Four firms, twenty-four tenders · ~25 s
- Beat: cold
- Voice: You are a procurement integrity officer for a roads authority. [pause 0.4] Every tender on your desk had several bidders, the lowest price won, and the file says it was competitive. [pause 0.6] A reviewer reads one tender at a time and finds nothing wrong. This film is about what shows up when you read them together.
- Action: cold open on the home page of the Public sector Space, saved views in view.
- Impact: none.

### S02 · Why each tender looks fine · ~32 s
- Beat: steady
- Intro: Bid rotation | Public sector Space
- Story: the winners = the firms that take the contract in turn | the losers = firms that bid to lose | the shared director = one person who directs two of the rivals
- Voice: A tender with four bids passes the competition check, the winner is the lowest bidder so it passes the price check, and the paperwork is complete so it passes the audit. [pause 0.5] A cover bid is a bid made to lose, placed so the tender looks contested. Rotation means the bidders take turns to win. Neither shows in one tender, and both show in the links between tenders.
- Action: story card "Who is who" over the home page.
- Impact: the story card names the three roles.

### S03 · The data in one breath · ~30 s
- Voice: Every bid lands as a record: the tender, the bidder, the price and the time. [pause 0.3] A Job reads those records and links each bidder to the tenders it bid on, and each firm to its directors. [pause 0.4] Firms, tenders and people are the entities, and every bid is a link between a firm and a tender.
- Chip: Data · Mappings
- Action: rail ▸ Data ▸ `procurement_links_dataset` ▸ hold on its Columns card.
- Impact: mark the src and dst columns once.

### S04 · The question · ~28 s
- Voice: Open the saved view Bid rotation. [pause 0.4] It draws the bidders, the tenders and who directs which firm, and it is dense, as a real tender book is. The question is simple: who really competes, and who only appears to? We need the product to point.
- Chip: Saved views
- Action: open the saved view *Bid rotation* ▸ hold on the drawn graph (labels appear on hover only above one thousand links).
- Impact: none.

### S05 · The sweep points at the wrong firms · ~42 s
- Voice: Ask the first question: who has the most links? Press Busiest nodes. [pause 0.5] Three firms tower over the list, with well over a hundred links each. They win most of the southern tenders, so a reviewer would check them first. [pause 0.4] But others bid against them, the prices are far apart, and they win because they are good. Set them aside.
- Chip: Busiest nodes
- Action: toolbox ▸ Quick questions ▸ Busiest nodes ▸ hold on the ranked list (CO-0021 147, CO-0055 132, CO-0090 124, then CO-0242 36, CO-0388 34).
- Impact: grey *ruled out* mark over the three leaders (the decoy marker).

### S06 · The pattern is further down · ~42 s
- Beat: reveal
- Story: off
- Voice: Look lower in the list. [pause 0.4] The next firms have far fewer links, thirty-six and thirty-four. Start from one of them, and the graph shows the tenders it bid on, and the people who direct it. [pause 0.5] Double-click one of those directors, P-00001, and a second firm appears. [pause 0.7] Two rivals, one director.
- Chip: Start from these · Expand
- Action: Busiest nodes ▸ the CO-0242 row ▸ Start from these ▸ close the banner ▸ double-click P-00001 ▸ CO-0561 appears.
- Impact: marks on P-00001 and on CO-0561 in yellow, the lead.

### S07 · The evidence you can quote · ~42 s
- Voice: Is this a guess? [pause 0.4] Open the Rows tab under the graph. Every line is a row: a firm, a link kind and a tender, so you can see who directs the firm, who bid, and who won. [pause 0.4] Across the twenty-four northern tenders, four firms are the only bidders and they win in turn, in the same order. The losers always sit five to twelve percent above the winner, never identical. Each row can be quoted, sorted and exported.
- Chip: Rows
- Action: dock ▸ Rows ▸ hold on the `directed_by`, `bid_on` and `won` rows of CO-0242.
- Impact: mark the first rows.

### S08 · What would change our mind · ~22 s
- Voice: Be fair to the firms. [pause 0.3] One real third bidder with a different price, on any of the twenty-four tenders, would weaken this. So would a director who had left before the first bid. Neither is in the data, and these are the two facts to check first.
- Action: the Rows tab stays open.
- Impact: none.

### S09 · What the team does next · ~40 s
- Voice: This is a lead, so the work starts here. [pause 0.4] First, refer the twenty-four tenders for audit. Second, ask for the document authors and addresses of the four bids. Third, keep the four firms and the director together as an Entity List, so the next tender they appear on is marked. [pause 0.3] That is the Entity Lists page, and New list opens the form.
- Chip: Entity Lists · New list…
- Action: rail ▸ Entity Lists ▸ hold ▸ New list… ▸ the form opens ▸ Cancel (nothing is created).
- Impact: none.

### S10 · The case file · ~24 s
- Beat: close
- Casefile: Lead: four firms, one order, one shared director | Evidence: the rows of twenty-four tenders, four bidders only | Next: refer the tenders for audit; ask for the bid authors and addresses; keep the firms as a list | Try it: run Busiest nodes and find the rotation below the leaders
- Voice: The lead: four firms, one order, one shared director. [pause 0.4] The evidence: the rows of twenty-four tenders. The next step: audit, authors and addresses, and an Entity List. [pause 0.6] Each tender was clean on its own, and only the links showed the rotation. Try it: run Busiest nodes, and find the rotation below the leaders.
- Action: case-file card (8 s: Lead · Evidence · Next step · Try it), then the outro.
- Impact: none.

## PublicSector_AddressFarm

> **Address farm: the fourth address** · Standard · Public sector (la-public-sector) · benefit claims at one address · 4:15 · plan §6 row 16

- **Cast:** the address AD-03141 and its fourteen claimants (five surnames, three bank accounts). **Decoy:** three care homes (AD-00412, AD-05077, AD-02718) with many residents, each paid into their own account. Ids readable.
- **Stakes:** a benefits fraud officer who can visit a handful of addresses a month. Four addresses hold far too many claimants for a home; three are legitimate.
- **Vocabulary:** *claimant*, a person with an open benefit claim. *Household of one*, a claim that declares a single-person home. Said once in S02.
- **Ground truth:** `tools/gen-la-benefits-data.ground-truth.json` · `address_farm` (`address` AD-03141, `claimants` 14, `surnames` Dunbar, Kettering, Oddie, Varga, Zamora, `accounts` three, `care_homes` AD-00412 31, AD-02718 24, AD-05077 27); `gen-la-benefits-data.py` line 13 and the address-farm block (five surnames in a 4-3-3-2-2 split, each a single-person household, three accounts).
- **Screen truth** (checked live on a fresh Standard unzip, Demo Analyst, 2026-11-22): the saved view *Address farm* draws 320 nodes and 366 links, so node labels are off until Display options turns them on; Busiest nodes answers AD-00412 (31 links), AD-05077 (27), AD-02718 (24), AD-03141 (14) and SYN-19642862 (5). *Start from these* on AD-03141 draws one entity and fourteen linked nodes, the claimants CIT-00953 to CIT-09614, every link `lives_at`. The Rows table lists those fourteen rows (From, Link kind, To). The surnames, the single-person declaration and the three bank accounts are **not on this graph**: they come from the ground truth, so the voice states them as what the records say and the screen shows the fourteen people and the address. Attach to Case opens with *An existing Case* and *A new Case, from the selected nodes* both available (ids readable), sealing the fifteen nodes on screen. Nothing is masked.
- **Before:** fresh unzip of `la-standard.zip` with the fake Demo Auth sign-in, Public sector Space, Demo Analyst; nothing is saved or created (the Attach to Case dialog is cancelled).
- **Lead:** fourteen single-person claims at one address, five surnames, paid into three accounts nobody else uses. **What would change our mind:** the three accounts belonging to one registered representative who is paid on behalf of many lawful claimants, or the fourteen being lodgers with a landlord's account. **Next actions:** verify the address in person; hold the three accounts for review; bring the fourteen claims together as a Case.
- **Voice style:** the reveal scene (S06) holds 0.7 s before "Fourteen single-person homes." Lexicon: AD-03141 "a d zero three one four one"; *claimant* as a word.
- **Try it:** open *Address farm* and press Busiest nodes. How do you tell a care home from the farm? *Answer: by the money. A care home's residents are each paid into their own account; the farm's fourteen share three.*
- **Check:** why is the largest address not the lead? *Answer: many people at one address is normal for a care home; only the shared accounts and the single-person declarations are unusual.*

### S01 · The address with too many people · ~25 s
- Beat: cold
- Voice: You work in a benefits fraud team, and you cannot visit every address. [pause 0.4] Four addresses hold far more claimants than any home should. Three are care homes, which is legitimate, and the fourth is a house. [pause 0.6] You need to know which one is wrong before you spend a visit.
- Action: cold open on the Space's home page.
- Impact: none.

### S02 · Why each claim looks fine · ~30 s
- Beat: steady
- Intro: Address farm | Public sector Space
- Story: a care home = an address where many people live, each paid separately | the house = an address that should hold one household | a claimant = a person with an open benefit claim
- Voice: Each claim, read alone, is ordinary: a person, an address, a benefit, a bank account. [pause 0.4] A household of one is a claim that declares a single-person home, and it passes the checks because nothing in it contradicts the rest. [pause 0.5] The oddity only shows when you count who else declares the same address, and whose account the money goes to.
- Action: story card "Who is who" over the home page.
- Impact: the story card names the three roles.

### S03 · The data in one breath · ~30 s
- Voice: Every claim lands as a record: the claim, the person, the benefit, the dates, the amount and the bank account paid into. [pause 0.3] A Job links each claimant to the address they declare and to the account they are paid into. People, addresses and accounts are the entities, and the links are the claims.
- Chip: Data · Mappings
- Action: rail ▸ Data ▸ `benefits_screen_links_dataset` ▸ hold on its Columns card.
- Impact: mark the src and dst columns once.

### S04 · The question · ~26 s
- Voice: Open the saved view Address farm. [pause 0.4] It draws addresses with the people who live at them, and the question is which of these is a home and which is a front door for many claims. Labels are off at this size, so a few hubs stand out, each with a crowd around it.
- Chip: Saved views
- Action: open the saved view *Address farm* ▸ hold on the drawn graph (the caption says node labels are off above 80 nodes).
- Impact: none.

### S05 · The big three are homes · ~40 s
- Voice: Press Busiest nodes. [pause 0.4] Three addresses lead, with thirty-one links, twenty-seven and twenty-four. Look at the money. In these three, each resident is paid into their own account. These are care homes, and they are genuine. [pause 0.6] The fourth address has fewer people, fourteen, and something else they share.
- Chip: Busiest nodes
- Action: toolbox ▸ Quick questions ▸ Busiest nodes ▸ hold on the ranked list (AD-00412 31, AD-05077 27, AD-02718 24, then AD-03141 14).
- Impact: grey *ruled out* mark over the three care homes (the decoy marker).

### S06 · The fourth address · ~44 s
- Beat: reveal
- Story: off
- Voice: Start from the fourth address, AD-03141. [pause 0.5] The graph redraws around it, and the banner says what it drew: one entity and fourteen linked nodes. Fourteen claimants, all living at one address. [pause 0.7] The records say five surnames, every one of them a household of one, and only three bank accounts between them. Fourteen single-person homes at one address.
- Chip: Start from these
- Action: Busiest nodes ▸ the AD-03141 row ▸ Start from these ▸ hold on the banner and the drawn graph.
- Impact: before/after hold: the address and its fourteen claimants appear; mark AD-03141 in yellow.

### S07 · The evidence you can quote · ~38 s
- Voice: Open the Rows tab under the graph. Every line is a row: a claimant, the link kind and the address, so fourteen claims at one address. [pause 0.4] The benefit records behind them carry the surnames and the three accounts, and they are the reason this address is the lead. Each row can be sorted, filtered and exported. [pause 0.3] The links tell a care home from a farmed address, and the money separates them.
- Chip: Rows
- Action: dock ▸ Rows ▸ hold on the fourteen `lives_at` rows.
- Impact: mark the rows.

### S08 · What would change our mind · ~22 s
- Voice: Be careful with what this shows. [pause 0.3] If the three accounts belong to one registered representative, paid on behalf of many lawful claimants, the picture changes. So it does if the fourteen turn out to be lodgers whose landlord is paid. Those are the two checks to make first.
- Action: the Rows tab stays open.
- Impact: none.

### S09 · What the team does next · ~40 s
- Voice: This is a lead, so here is the work. [pause 0.4] First, verify the address in person. Second, hold the three accounts for review. Third, bring the fourteen claims together as a Case. [pause 0.3] On the canvas toolbar, Attach to Case seals what is on screen with a fingerprint. A new Case from the selected nodes is open to you here, because these ids are readable. We read it, and cancel.
- Chip: Attach to Case
- Action: canvas toolbar ▸ Attach to Case ▸ hold on *An existing Case* and *A new Case, from the selected nodes* ▸ Cancel (nothing is created).
- Impact: mark the *A new Case, from the selected nodes* option.

### S10 · The case file · ~22 s
- Beat: close
- Casefile: Lead: fourteen single-person claims at one address, five surnames, three accounts | Evidence: the fourteen rows at AD-03141, and the records behind them | Next: verify the address in person; hold the three accounts; open a Case | Try it: compare a care home with the fourth address
- Voice: The lead: fourteen single-person claims, five surnames, three accounts. [pause 0.4] The evidence: the rows under the graph and the records behind them. The next step: verify, hold, and open a Case. [pause 0.6] Try it yourself: open Address farm, and compare a care home with the fourth address.
- Action: case-file card (8 s: Lead · Evidence · Next step · Try it), then the outro.
- Impact: none.

## Healthcare_ReferralKickbacks

> **Referral kickbacks: an imaging centre and two owners who share a home** · Standard · Healthcare (la-healthcare) · referrals and ownership · 4:10 · plan §6 row 17

- **Cast:** the imaging centre PR-1170, its owner OW-0434, two referring practices (PR-1426, PR-1392), the owner OW-1270 who shares a home (AD-05150) with OW-0434. **Decoy:** the only local hospital (PR-1129, owner OW-0263), the busiest receiver. Ids readable.
- **Stakes:** a health-system integrity analyst. In a small town, sending most referrals to one place can be geography. The question is whether any of it has a reason.
- **Vocabulary:** *referral*, one provider sending a patient to another. *Ownership tie*, who owns a practice. Said once in S02.
- **Ground truth:** `tools/gen-la-health-data.ground-truth.json` · `referral_kickbacks` (`imaging_centre` PR-1170, `spouse` OW-0434, `practice_owner` OW-1270, `second_practice_owner` OW-1286, `practices` [PR-1426, PR-1392], `home` AD-05150, `lookalike_hospital` PR-1129, `lookalike_hospital_owner` OW-0263).
- **Screen truth** (checked live on a fresh Standard unzip, Demo Analyst, 2026-11-22): the saved view *Referral kickbacks* is small and fully readable (23 nodes, 22 links, PR-, OW- and AD- labels). Busiest nodes answers PR-1129 (8 links, with an offer to add it to an exclusion list), PR-1170 (3), AD-05150 (2), OW-0434 (2) and OW-1270 (2). On the canvas PR-1170 links to PR-1392 and PR-1426 (referrals), to its owner OW-0434, who lives at AD-05150, and OW-1270 also lives at AD-05150. Nothing is masked.
- **Before:** fresh unzip of `la-standard.zip` with the fake Demo Auth sign-in, Healthcare Space, Demo Analyst; nothing is saved or created (the New list form is cancelled).
- **Lead:** two city practices send their patients to an imaging centre whose owner shares a home with the owner of one of them. **What would change our mind:** the owners declared the tie and the referrals are documented as clinically driven, or the centre is the only one with that equipment. **Next actions:** open a conflict-of-interest review of the two practices; ask for the referral reasons on their patients; keep the centre and its owners together as an Entity List.
- **Voice style:** the reveal scene (S06) holds 0.7 s before "They share a home." Lexicon: PR-1170 "p r one one seven zero", OW-0434 "o w zero four three four".
- **Try it:** open *Referral kickbacks*, press Busiest nodes and compare the top name with the imaging centre. *Answer: the busiest name is the only hospital in the seaside town, which is geography; the imaging centre has few links and an ownership tie.*
- **Check:** why is the busiest receiver not the lead? *Answer: a town with one hospital sends it most referrals; only an ownership tie gives a reason to choose one place over another.*

### S01 · Referrals that all go one way · ~24 s
- Beat: cold
- Voice: You are an integrity analyst for a health system. [pause 0.4] Referrals from city practices keep landing at one imaging centre. In a small town, that can be geography. [pause 0.6] Or it can be a reason. Referral data alone cannot say which, but ownership can.
- Action: cold open on the Space's home page.
- Impact: none.

### S02 · Why referrals alone are not enough · ~30 s
- Beat: steady
- Intro: Referral kickbacks | Healthcare Space
- Story: the local hospital = the only hospital in the town | the imaging centre = a private centre that practices refer to | the owners = the people who own the practices
- Voice: A referral is one provider sending a patient to another. Counting them tells you who is busy, never why. [pause 0.4] Ownership says who owns each practice, and where the owners live. [pause 0.4] The two sit in different records, and only when they are joined does a pattern either hold up or fall apart.
- Action: story card "Who is who" over the home page.
- Impact: the story card names the three roles.

### S03 · The data in one breath · ~30 s
- Voice: Every referral lands as a record: the sending provider, the receiving provider and the date. [pause 0.3] A Job links providers to each other by referral, and to their owners and the owners' addresses by ownership. Providers, owners and addresses are the entities.
- Chip: Data · Mappings
- Action: rail ▸ Data ▸ `health_screen_links_dataset` ▸ hold on its Columns card.
- Impact: mark the src and dst columns once.

### S04 · The question · ~26 s
- Voice: Open the saved view Referral kickbacks. [pause 0.4] It draws the practices, where they refer, and who owns what. The question is whether any referral pattern coincides with an ownership tie. It is a small graph, and every id reads in full.
- Chip: Saved views
- Action: open the saved view *Referral kickbacks* ▸ hold.
- Impact: none.

### S05 · The busiest name is geography · ~40 s
- Voice: Press Busiest nodes. [pause 0.4] The busiest provider, PR-1129, has eight links. It is the only hospital in a seaside town, owned by a health trust. [pause 0.6] That is geography, because people go where the hospital is. Set it aside. The second name has only three links, and it is a private imaging centre.
- Chip: Busiest nodes
- Action: toolbox ▸ Quick questions ▸ Busiest nodes ▸ hold on the answers (PR-1129 8 links, PR-1170 3).
- Impact: grey *ruled out* mark on PR-1129 in the graph.

### S06 · The centre and its owner · ~44 s
- Beat: reveal
- Story: off
- Voice: Look at the imaging centre, PR-1170. [pause 0.5] Two practices refer their patients here, PR-1426 and PR-1392, and the centre has one owner, OW-0434. Follow the owner's home, AD-05150. [pause 0.7] They share a home with OW-1270, who owns one of the referring practices.
- Action: hold on the graph; marks on PR-1170, OW-0434, AD-05150 and OW-1270 in turn.
- Impact: marks on OW-0434, AD-05150 and OW-1270 in yellow.

### S07 · The evidence you can quote · ~38 s
- Voice: Open the Rows tab under the graph. Each line is a row: a provider, a link kind and a provider, owner or home. [pause 0.4] The referrals to the centre, its ownership and the shared home are all there, and each row can be read, sorted and exported to the reviewer. [pause 0.3] The tie is in the data, not in a theory.
- Chip: Rows
- Action: dock ▸ Rows ▸ hold.
- Impact: mark the first rows.

### S08 · What would change our mind · ~22 s
- Voice: Check the other side. [pause 0.3] If the owners declared the tie and the referrals are documented as clinically driven, this is a disclosed interest. If the centre has equipment nobody else has, the referrals are explained. Both are one request away.
- Action: the Rows tab stays open.
- Impact: none.

### S09 · What the team does next · ~38 s
- Voice: This is a lead, so here is the work. [pause 0.4] First, open a conflict-of-interest review of the two practices. Second, ask for the referral reasons on their patients. Third, keep the centre and its owners together. [pause 0.3] That is an Entity List, and New list opens the form.
- Chip: Entity Lists · New list…
- Action: rail ▸ Entity Lists ▸ New list… ▸ the form opens ▸ Cancel (nothing is created).
- Impact: none.

### S10 · The case file · ~22 s
- Beat: close
- Casefile: Lead: an imaging centre fed by two practices, two owners who share a home | Evidence: the referral and ownership rows | Next: a conflict-of-interest review; the referral reasons; an Entity List | Try it: compare the busiest name with the centre
- Voice: The lead: an imaging centre fed by two practices, and two owners who share a home. [pause 0.4] The evidence: the referral and ownership rows. The next step: a review, the referral reasons, and an Entity List. [pause 0.6] Try it yourself: compare the busiest name with the centre.
- Action: case-file card (8 s: Lead · Evidence · Next step · Try it), then the outro.
- Impact: none.

## Logistics_InsideJob

> **Inside job: twelve short loads** · Standard · Logistics (la-logistics-industry) · a driver pair and one scanner · 4:00 · plan §6 row 18

- **Cast:** the driver DRV-0892 and co-driver DRV-0893 (carrier CAR-052), the warehouse scanner EMP-0123 (WH-06). **Decoy:** the driver DRV-0894 on the busiest route (Southampton to Birmingham), who lost two loads too and shares nothing with them. Ids readable.
- **Stakes:** a loss-prevention manager. Loads arrive short on different routes in different months; cargo theft is common, so it reads as bad luck.
- **Vocabulary:** *short load*, a delivery that arrives with goods missing. *Scanner*, the warehouse person who scans a load onto a truck. Said once in S02.
- **Ground truth:** `tools/gen-la-logistics-data.ground-truth.json` · `inside_job` (`driver` DRV-0892, `co_driver` DRV-0893, `carrier` CAR-052, `losses` twelve, `trips` twelve, `night_trips` ten, `scanner` EMP-0123, `scanner_warehouse` WH-06, `lookalike_driver` DRV-0894, `lookalike_carrier` CAR-103, `lookalike_trips` two, `busiest_route` Southampton, Birmingham); `gen-la-logistics-data.py` lines 13 and 436–444 (ten of the twelve left Nottingham at night, scanned out by the same night-shift scanner).
- **Screen truth** (checked live on a fresh Standard unzip, Demo Analyst, 2026-11-22): the saved view *Driver team inside job* draws 79 nodes and 88 links, labels readable at the size shown; Busiest nodes answers DRV-0892 (12 links), DRV-0893 (12), EMP-0123 (10), TR-02297 (4) and TR-03007 (4). The lookalike driver DRV-0894 is on the canvas with few links. Nothing is masked. The night-shift timing (ten of twelve) is a ground-truth fact the view does not show, so the voice states it as what the records say.
- **Before:** fresh unzip of `la-standard.zip` with the fake Demo Auth sign-in, Logistics Space, Demo Analyst; nothing is saved or created.
- **Lead:** twelve short loads, one driver pair on all of them, and one night-shift scanner on ten. **What would change our mind:** a rota showing the scanner worked other shifts on those nights, or a second pair on any of the twelve trips. **Next actions:** interview the three people; pull the warehouse badge records for the ten nights; compare WH-06 night scans against the outbound loads.
- **Voice style:** cold open 1.04; the reveal scene (S06) holds 0.7 s before "Ten of the twelve." Lexicon: DRV-0892 "driver zero eight nine two"; EMP-0123 "employee zero one two three"; do not read trip or loss ids aloud.
- **Try it:** open *Driver team inside job* and press Busiest nodes. Who is on top besides the drivers? *Answer: a warehouse scanner, EMP-0123, who is not a driver at all.*
- **Check:** why is the busiest driver on the busiest route not the lead? *Answer: busy routes lose loads by bad luck; the lead is the same pair, and the same scanner, on every short load.*

### S01 · Loads that arrive short · ~24 s
- Beat: cold
- Voice: You are a loss-prevention manager. [pause 0.4] Loads keep arriving short, on different routes, in different months, for different customers. Each customer reports to a different depot, and nobody puts the reports side by side. [pause 0.6] Cargo theft is common, so it looks like bad luck. This film asks whether it is.
- Action: cold open on the Space's home page.
- Impact: none.

### S02 · Why bad luck is the default · ~30 s
- Beat: steady
- Intro: Inside job | Logistics Space
- Story: a short load = a delivery that arrives with goods missing | the driver pair = two drivers who share a truck | the scanner = the warehouse person who scans a load onto a truck
- Voice: A short load is a delivery that arrives with goods missing. Each report is one trip, one depot and one customer, so each looks like bad luck. [pause 0.5] A scanner is the warehouse person who scans a load onto a truck. Nobody asks who drove, and who scanned, across all twelve.
- Action: story card "Who is who" over the home page.
- Impact: the story card names the three roles.

### S03 · The data in one breath · ~28 s
- Voice: Every trip lands as a record with its driver, carrier, route and time. [pause 0.3] Warehouse scans record who loaded which truck, and loss reports say which trips lost goods. A Job links drivers, scanners and trips, and the loss reports mark the trips that arrived short.
- Chip: Data · Mappings
- Action: rail ▸ Data ▸ `logistics_screen_links_dataset` ▸ hold on its Columns card.
- Impact: mark the src and dst columns once.

### S04 · The question · ~26 s
- Voice: Open the saved view Driver team inside job. [pause 0.4] It draws the trips that lost goods with the people behind them. It is busy, and every label reads. The question is whether the same names keep coming back.
- Chip: Saved views
- Action: open the saved view *Driver team inside job* ▸ hold.
- Impact: none.

### S05 · The busiest route is bad luck · ~38 s
- Voice: Press Busiest nodes. [pause 0.4] Two drivers lead, with twelve links each, and then someone who is not a driver at all: a warehouse employee. [pause 0.5] One more driver deserves a thought, because he drives the busiest route and lost two loads too. But that route loses loads to bad luck, and he shares nothing with the others. Set him aside.
- Chip: Busiest nodes
- Action: toolbox ▸ Quick questions ▸ Busiest nodes ▸ hold on the answers (DRV-0892 12, DRV-0893 12, EMP-0123 10).
- Impact: grey *ruled out* mark on the lookalike driver DRV-0894 on the graph.

### S06 · The pair and the scanner · ~44 s
- Beat: reveal
- Story: off
- Voice: Look at the top three. [pause 0.5] The same two drivers are on all twelve short loads, and the warehouse employee, EMP-0123, is on ten of them. [pause 0.7] Ten of the twelve left the depot at night, scanned out by that same person.
- Action: hold on the graph; marks on the driver pair and EMP-0123 in yellow.
- Impact: yellow mark on the cluster of DRV-0892, DRV-0893 and EMP-0123.

### S07 · The evidence you can quote · ~36 s
- Voice: Open the Rows tab under the graph. Each line is a row: a driver, a link kind and a trip or a scanner. [pause 0.4] The twelve trips, the pair, and the scanner are all there, and each row can be read, sorted and exported. [pause 0.3] A loss-prevention manager can quote every line.
- Chip: Rows
- Action: dock ▸ Rows ▸ hold.
- Impact: mark the first rows.

### S08 · What would change our mind · ~22 s
- Voice: Be fair to the three. [pause 0.3] A rota showing the scanner worked other shifts on those nights would weaken this. So would a second pair on any of the twelve trips. Both are one request away.
- Action: the Rows tab stays open.
- Impact: none.

### S09 · What the team does next · ~36 s
- Voice: This is a lead, so here is the work. [pause 0.4] Interview the three people. Pull the warehouse badge records for the ten nights. Compare that warehouse's night scans against the outbound loads. [pause 0.3] Keep the three together as an Entity List, and New list opens the form.
- Chip: Entity Lists · New list…
- Action: rail ▸ Entity Lists ▸ New list… ▸ the form opens ▸ Cancel (nothing is created).
- Impact: none.

### S10 · The case file · ~22 s
- Beat: close
- Casefile: Lead: one driver pair on all twelve short loads, one scanner on ten | Evidence: the twelve trips and the people behind them | Next: interview the three; pull the badge records; compare the night scans | Try it: find who tops Busiest nodes besides the drivers
- Voice: The lead: one driver pair on all twelve short loads, and one scanner on ten. [pause 0.4] The evidence: the trips and the people behind them. The next step: interview, badge records, night scans. [pause 0.6] Try it yourself: find who tops Busiest nodes besides the drivers.
- Action: case-file card (8 s: Lead · Evidence · Next step · Try it), then the outro.
- Impact: none.

## Enterprise_FictitiousVendor

> **Fictitious vendor: fourteen clean invoices** · Standard · Enterprise (la-enterprise) · a vendor that shares an employee's account · 4:00 · plan §6 row 19

- **Cast (roles, because the graph is masked):** the vendor behind the fourteen invoices, the employee who shares its bank account and address, and that employee's manager. Revealed by Demo Admin in S06: V-117, BA-9031, E-31, E-15. **Decoy:** the biggest vendor, a utilities company (V-001), with the most payments of all. The analyst reads aliases: *291 entities masked*.
- **Stakes:** an internal auditor. Fourteen invoices, all approved and paid, nothing late or duplicated. An audit that checks each invoice against its approval finds nothing.
- **Vocabulary:** *vendor master*, the list of suppliers with their bank accounts and addresses. *Approver*, the person who signs an invoice off. Said once in S02.
- **Ground truth:** `tools/gen-la-audit-data.py` (no JSON file: the story is in the header comment, line 13, and constants): `FICTITIOUS` V-117, `E31` E-31, `E31_MANAGER` E-15, `vendor_account[FICTITIOUS] = emp_account[E31] = "BA-9031"` (line 86), fourteen invoices approved by E-15 (line 196), `UTILITIES` V-001 "Meridian Utilities Ltd" (lines 47, 101). Invoice amounts are not stated. **Plan table corrected:** the table said "the vendor with the largest invoices"; the decoy is the vendor with the *most payments*, a utilities company. **Fixes the v1 defect:** v1 read ids over this masked graph; v2 uses roles until the administrator reveals.
- **Screen truth** (checked live on a fresh Standard unzip, Demo Analyst, 2026-11-22): the saved view *Ap vendor identity* draws 291 nodes and 1,787 links and says *291 entities masked* with the greyed *Reveal…*; Busiest nodes answers masked aliases (100, 100, 45, 30 and 30 links), so every name is an alias. The reveal beat needs Demo Admin and writes an audit record, so this film records the analyst side only and the reveal is **narrated** (the real reveal is training episode F7). The fourteen invoices, the shared account and the approver E-15 are ground truth the masked view does not show.
- **Before:** a vendor that shares a bank account and an address with an employee, with fourteen invoices all approved by that employee's manager. **What would change our mind:** the shared account turning out to be a payment bureau used by many vendors, or the address being a shared office. **Next actions:** stop payments to the vendor pending verification; review every invoice the manager approved for it; search the vendor master for other vendors sharing an employee's account.
- **Voice style:** cold open 1.04. Before S06, say roles only; after the reveal, read ids with the lexicon (V-117 "v one one seven", BA-9031 "b a nine zero three one"). The reveal scene holds 0.7 s before "The same account."
- **Try it:** open *Ap vendor identity* as the analyst and press Busiest nodes. What does the canvas say about the names, and who can reveal them? *Answer: it says the entities are masked, with a Reveal button; revealing needs See unmasked ids, which the administrator holds.*
- **Check:** why is the largest vendor not the lead? *Answer: a utilities company with many regular payments is normal; the lead is a small vendor whose account and address match an employee's.*

### S01 · Fourteen invoices, no flaws  ~25 s
- Voice: You are an internal auditor. [pause 0.4] Fourteen invoices from one vendor. All approved, all paid. Nothing late, nothing duplicated. [pause 0.6] An audit that checks each invoice against its approval finds nothing wrong. So the question is not about the invoices. It is about the vendor.
- Chip: none
- Action: cold open on the Space's home page; intro card (*Fictitious vendor*, *Enterprise*, *Standard*).
- Impact: none.

### S02 · Why the audit finds nothing  ~30 s
- Voice: The vendor master is the list of suppliers, with their bank accounts and addresses. [pause 0.3] An approver is the person who signs an invoice off. Each invoice passes, because each has an approver. [pause 0.5] The vendor master and the employee file are separate lists. Neither says that a supplier and a member of staff are the same account. Only a link can.
- Chip: none
- Action: home page; the story card names *the vendor*, *the employee* and *the manager*.
- Impact: none.

### S03 · The data in one breath  ~30 s
- Voice: Every vendor lands in the master file as a record: the name, the bank account, the address and the phone. [pause 0.3] A Job links vendors to the accounts, addresses and phones they register, and links employees to theirs. Vendors, employees, accounts and addresses are the entities. Anything two of them share becomes a link.
- Chip: Mappings · Data
- Action: rail ▸ Data ▸ the identity links Dataset ▸ zoom on the Mappings area. [check in UI: the Mappings area of `ap_identity_links_dataset`]
- Impact: mark the From and To columns.

### S04 · The question, and a masked graph  ~30 s
- Voice: Open the saved view Ap vendor identity. [pause 0.4] The canvas says two hundred and ninety-one entities are masked. [pause 0.4] You are reading aliases, not names. That is by design: vendors and staff are personal data, and this analyst works without seeing them. The shape is still readable. Most of it is pairs: a vendor, and its own account.
- Chip: File · Open… · Saved views · Reveal…
- Action: File ▸ Open… ▸ Saved views ▸ *Ap vendor identity* ▸ hold on the line *291 entities masked* and the greyed *Reveal…*. [check in UI: the masked line and the tooltip *Needs See unmasked ids*]
- Impact: mark the masked line and *Reveal…*; before/after hold: the graph appears.

### S05 · The biggest vendor is a decoy  ~40 s
- Voice: Press Busiest nodes. [pause 0.4] The busiest vendor has the most payments of all. It is a utilities company: large, regular, real. [pause 0.5] An audit that starts from the biggest numbers ends right here. Set it aside. Look instead for anything that is joined to something it should not be.
- Chip: Busiest nodes
- Action: toolbox ▸ Quick questions ▸ Busiest nodes ▸ hold; click the top alias. [check in UI: the top alias is the utilities vendor]
- Impact: mark the top vendor grey with *ruled out*.

### S06 · The vendor that touches staff  ~50 s
- Voice: Look for the small cluster, a vendor joined to an employee. [pause 0.4] You can see its shape without the names: one vendor, one bank account, and one employee joined to that account. [pause 0.7] The same account. To name them, an administrator presses Reveal and gives a reason, and the reveal is recorded. That step is training episode F7, so here we stay with the aliases.
- Chip: Reveal… · Reason
- Action: hold on the graph with the masked line; marks on the cluster; the Reveal… button is greyed for this analyst and is not pressed.
- Impact: mark the masked line and the greyed *Reveal…*.

### S07 · The evidence you can quote  ~40 s
- Voice: Open the Rows tab. Each line is a row: a link and its two ends, shown as aliases. [pause 0.4] The records behind them hold the fourteen invoices, each approved by the same manager, and the two shared details, the account and the address. Each row can be exported, and a reveal would sit in the audit trail with its reason.
- Chip: Rows
- Action: dock ▸ Rows ▸ sort by approver ▸ hold on the fourteen.
- Impact: mark the approver column; the two shared-detail rows.

### S08 · What would change our mind  ~20 s
- Voice: Check the innocent explanations. [pause 0.3] If the shared account is a payment bureau used by many vendors, this changes. So does an address that is a shared office. The vendor master will show whether other vendors use the same account.
- Chip: none
- Action: the Rows tab stays open; the story card shows *what would change our mind*.
- Impact: none.

### S09 · What the team does next  ~40 s
- Voice: This is a lead, so here is the work. [pause 0.4] First, stop payments to the vendor until it is verified. Second, review every invoice the manager approved for it. Third, search the vendor master for any other vendor that shares an employee's account. [pause 0.3] To hand the finding to a Case, press Attach to Case. A new Case from the selected nodes is greyed here, because these ids are masked for you. Read the reason, and cancel.
- Chip: Attach to Case
- Action: back as Demo Analyst ▸ select the cluster ▸ canvas toolbar ▸ Attach to Case ▸ read the greyed *A new Case, from the selected nodes* and its reason ▸ Cancel (nothing is created). [check in UI: the reason sentence for masked wallets or ids]
- Impact: mark the reason sentence.

### S10 · The case file  ~20 s
- Voice: The lead: a vendor that shares an employee's account and address. [pause 0.4] The evidence: fourteen invoices approved by one manager, and two shared details. The next step: stop, review, and search. [pause 0.6] To try it yourself, open Ap vendor identity and read what a masked graph still tells you.
- Chip: none
- Action: the case-file card; outro card.
- Impact: none.

## Enterprise_AccessCreep

> **Access creep: who could, and who let them** · Standard · Enterprise (la-enterprise) · four grants and one bank-detail change · 4:00 · plan §6 row 20

- **Cast:** the Operations Manager EMP-0007, their four reports (EMP-0482, EMP-0636, EMP-1059, EMP-1415) and the vendor VEN-0264 whose bank details one of them changed. **Decoy:** the Payroll Officer EMP-0062, whose payroll access came with a role change on 2026-03-02, a manager (EMP-1802) and a ticket (REQ-163776). Ids readable.
- **Stakes:** an access-review analyst. A vendor's bank details were changed once, quietly. That is one edit among thousands of users. The question is who could make it, and who allowed that.
- **Vocabulary:** *grant*, one person giving another access to a system. *Ticket*, the approval a grant should carry. Said once in S02.
- **Ground truth:** `tools/gen-la-insider-data.ground-truth.json` · `access_creep` (`manager` EMP-0007 "Operations Manager", `reports` four, `systems` PAYROLL and TREASURY, `grants` eight, 2026-01-12 to 2026-02-10, `vendor_editor` EMP-0482, `vendor` VEN-0264, `vendor_changed_at` 2026-05-14 10:42:17, `lookalike_employee` EMP-0062, `lookalike_role_since` 2026-03-02, `lookalike_manager` EMP-1802, `lookalike_ticket` REQ-163776).
- **Screen truth** (checked live on a fresh Standard unzip, Demo Analyst, 2026-11-22): the saved view *Access creep* draws 26 nodes and 18 links after a 10–25 s wait for the nodes; Busiest nodes answers EMP-0007 (4 links), EMP-2057 (3), EMP-0482 (2), EMP-0556 (2) and EMP-0991 (2). *Start from these* on EMP-0007 draws the four reports; the Rows table has four rows, each `granted_by` EMP-0007 (EMP-0482, EMP-0636, EMP-1059, EMP-1415). The eight grants, the systems payroll and treasury, the empty ticket column and the vendor change are **not on this graph or in its rows**: they are ground truth, so the voice states them as what the access records say, and the "no ticket" claim is v1 and ground-truth text that this screen does not show. The decoy EMP-0062 is not among the busiest names, so it is spoken, not marked. Nothing is masked.
- **Before:** fresh unzip of `la-standard.zip` with the fake Demo Auth sign-in, Enterprise Space, Demo Analyst; the saved view is used, not the seeded Investigations; nothing is saved or created.
- **Lead:** one manager gave four of their own reports payroll and treasury access, none of whom works in finance, and one of the four then changed a vendor's bank details. **What would change our mind:** tickets for the four grants that the log does not hold, or a documented cover arrangement for finance. **Next actions:** start an access review with the one manager; stop and check the payment to the vendor; keep the four reports and the vendor together as an Entity List.
- **Voice style:** cold open 1.04; the reveal scene (S06) holds 0.7 s before "None of the four works in finance." Lexicon: EMP-0007 "employee zero zero zero seven"; VEN-0264 "vendor zero two six four"; speak the systems as "payroll" and "treasury".
- **Try it:** open *Access creep* and press Busiest nodes. How many links does the top name have, and why is that few? *Answer: four; it is a small graph, so four links to one manager is what stands out, not volume.*
- **Check:** why is the payroll officer not the lead? *Answer: a role change with a ticket looks the same in the access log, but it has a manager and an approval; the four grants have neither.*

### S01 · One quiet edit · ~24 s
- Beat: cold
- Voice: You are an access-review analyst. [pause 0.4] A vendor's bank details were changed once, quietly, and in a system with thousands of users that is one edit among many. [pause 0.6] You do not need to know who made it. You need to know who could, and who let them.
- Action: cold open on the Space's home page.
- Impact: none.

### S02 · Why the change looks valid · ~30 s
- Beat: steady
- Intro: Access creep | Enterprise Space
- Story: the manager = the person who gave the access | the four reports = the people who received it | the vendor = the supplier whose bank details changed
- Voice: A grant is one person giving another access to a system, and a ticket is the approval a grant should carry. [pause 0.4] The log shows the bank-detail change was made by someone with access, and that is true. [pause 0.5] It shows each grant on its own line, but not that one manager gave the same two systems to four people in turn.
- Action: story card "Who is who" over the home page.
- Impact: the story card names the three roles.

### S03 · The data in one breath · ~28 s
- Voice: Every access grant lands as a record: the employee, the system, who granted it and when. [pause 0.3] A Job links employees to the systems they use, and each grant to the person who made it. Employees and systems are the entities, and the time of the grant orders them.
- Chip: Data · Mappings
- Action: rail ▸ Data ▸ `insider_screen_links_dataset` ▸ hold on its Columns card.
- Impact: mark the src and dst columns once.

### S04 · The question · ~24 s
- Voice: Open the saved view Access creep. [pause 0.4] It draws who granted access to whom, and the nodes take a moment to appear. It is a small graph, and most of it is what you would expect: managers granting access to their own teams.
- Chip: Saved views
- Action: open the saved view *Access creep* ▸ wait for the nodes (10–25 s) ▸ hold.
- Impact: none.

### S05 · A payroll officer is the decoy · ~36 s
- Voice: Press Busiest nodes. [pause 0.4] One name on top, with four links, and another approver with three. Four is not many, and it depends who the four are. [pause 0.5] First, set the obvious one aside: a payroll officer with payroll access. Their role changed in March, with a manager and a ticket, which is legitimate.
- Chip: Busiest nodes
- Action: toolbox ▸ Quick questions ▸ Busiest nodes ▸ hold on the answers (EMP-0007 4 links, EMP-2057 3).
- Impact: none (the decoy EMP-0062 is spoken, not drawn).

### S06 · The manager and the four · ~42 s
- Beat: reveal
- Story: off
- Voice: Start from the top name, EMP-0007. [pause 0.5] The graph redraws around an operations manager and four people who report to them. The access records say these four were given payroll and treasury between January and February. [pause 0.7] None of the four works in finance. And one of them, EMP-0482, is the person who changed the vendor's bank details.
- Chip: Start from these
- Action: Busiest nodes ▸ the EMP-0007 row ▸ Start from these ▸ hold on the banner and the graph.
- Impact: before/after hold: the manager and the four reports appear; mark EMP-0007.

### S07 · The evidence you can quote · ~34 s
- Voice: Open the Rows tab under the graph. Each line is a row: an employee, a link kind and the person who granted it. [pause 0.4] Four rows, four reports, one manager, all granted by the same person. The grant records behind them carry the systems, the dates and the empty ticket column. Every row can be sorted, read in order and exported.
- Chip: Rows
- Action: dock ▸ Rows ▸ hold on the four `granted_by` rows.
- Impact: mark the four rows.

### S08 · What would change our mind · ~22 s
- Voice: Be fair to the manager. [pause 0.3] If tickets exist that the log does not hold, or the manager was covering for finance by arrangement, the picture softens. Both are quick to check, and both belong in the first conversation.
- Action: the Rows tab stays open.
- Impact: none.

### S09 · What the team does next · ~36 s
- Voice: This is a lead, so here is the work. [pause 0.4] First, start an access review with the one manager. Second, stop and check the payment to the vendor. Third, keep the four reports and the vendor together, so any further change to either is marked. [pause 0.3] That is an Entity List, and New list opens the form.
- Chip: Entity Lists · New list…
- Action: rail ▸ Entity Lists ▸ New list… ▸ the form opens ▸ Cancel (nothing is created).
- Impact: none.

### S10 · The case file · ~22 s
- Beat: close
- Casefile: Lead: one manager, four grants, and a changed bank account | Evidence: the four grant rows and the records behind them | Next: an access review; a payment check; an Entity List | Try it: compare the manager with the payroll officer
- Voice: The lead: one manager, four grants, and a changed bank account. [pause 0.4] The evidence: the grant rows and the records behind them. The next step: a review, a payment check, and an Entity List. [pause 0.6] Try it yourself: open Access creep, and compare the manager with the payroll officer.
- Action: case-file card (8 s: Lead · Evidence · Next step · Try it), then the outro.
- Impact: none.
