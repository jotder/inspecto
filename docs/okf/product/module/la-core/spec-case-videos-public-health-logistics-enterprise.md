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
- **Ground truth:** `tools/gen-la-procurement-data.ground-truth.json` · `bid_rotation` (`firms`, `unit` Highways North, `tenders_in_order` 24, `winners_in_order`, `shared_by`, `shared_director` P-00001, `lookalike_firms`, `lookalike_unit` Highways South, `lookalike_win_share` 0.744); the losers bid 5–12 % above the winner, `gen-la-procurement-data.py` line 13 and the story-1 block (`add_bid(… base * rng.uniform(1.05, 1.12))`). **Plan table corrected:** the table said "the lowest bidder every time"; the data says the decoy is the three *most frequent winners*, who win a large share against real rivals, and the lead is the order of winners.
- **Lead:** four firms are the only bidders on twenty-four Highways North tenders and win in a fixed order, the losers always between five and twelve percent above the winner; two of them share a director. **What would change our mind:** one real third bidder with a different price on any of the twenty-four, or a director who left before the first bid. **Next actions:** refer the twenty-four tenders for audit; keep the four firms and the director as an Entity List so the next tender is marked; ask for the document authors and the addresses of the four bids.
- **Voice style:** cold open 1.04; the reveal scene (S06) holds 0.7 s before "Two rivals, one director." Lexicon: CO-0242 "c o zero two four two", P-00001 "p zero zero zero zero one"; say *tender* as "tender", not "T".
- **Try it:** on the shipped zip, open the saved view *Bid rotation* and run Busiest nodes. Which names lead, and are they the rotation? *Answer: the leaders are the three firms that win most of Highways South; they face real rivals. The rotation is further down, at the firms with thirty-odd links.*
- **Check:** why is the firm that wins most often not the lead? *Answer: winning often against real rivals is competition; the lead is firms that never face one and take turns.*

### S01 · Four firms, twenty-four tenders  ~25 s
- Voice: You are a procurement integrity officer for a roads authority. [pause 0.4] Every tender on your desk had several bidders. The lowest price won, and the file says it was competitive. [pause 0.6] A reviewer reads one tender at a time, and finds nothing wrong. This film is about what happens when you read them together.
- Chip: none (cold open on the Space's home page)
- Action: cold open on the home page of the Public sector Space, saved views in view; then the intro card (*Bid rotation*, *Public sector*, *Standard*).
- Impact: none.

### S02 · Why each tender looks fine  ~30 s
- Voice: A tender with four bids passes the competition check. [pause 0.3] The winner is the lowest bidder, so it passes the price check. The paperwork is complete, so it passes the audit. Each tender is clean on its own. [pause 0.5] A cover bid is a bid made to lose, placed so the tender looks contested. Rotation means the bidders take turns to win. Neither shows in one tender. Both show in the links between tenders and bidders.
- Chip: none
- Action: still on the home page; the story card *Who is who* appears: *the winners*, *the losers*, *the shared director*.
- Impact: none (the story card is the picture).

### S03 · The data in one breath  ~30 s
- Voice: Every bid lands as a record: the tender, the bidder, the price, the time, and the address it came from. [pause 0.3] A Job reads those records and links each bidder to the tenders it bid on, and each firm to its directors. [pause 0.4] Firms, tenders and people are the entities. Every bid is a link between a firm and a tender.
- Chip: Mappings · Data
- Action: rail ▸ Data ▸ the procurement links Dataset ▸ zoom on the Mappings area (From, To, Link kind). [check in UI: the Mappings area of `procurement_links_dataset`]
- Impact: mark the From and To columns of the mapping.

### S04 · The question  ~25 s
- Voice: Open the saved view Bid rotation. [pause 0.4] It draws the bidders, the tenders and who directs which firm. The question is simple. Who really competes, and who only appears to? [pause 0.5] The picture is dense, as a real tender book is. We need the product to point.
- Chip: File · Open… · Saved views
- Action: File ▸ Open… ▸ Saved views ▸ *Bid rotation* ▸ hold on the drawn graph; Display options ▸ node labels on. [check in UI: the canvas draws a dense graph; labels are on hover above 1,000 links]
- Impact: before/after hold: the graph appears; mark the dense centre.

### S05 · The sweep points at the wrong firms  ~40 s
- Voice: Ask the first question: who has the most links? Press Busiest nodes. [pause 0.5] Three firms tower over the list. They win most of the southern tenders. Check them, because that is what a reviewer would do. [pause 0.4] Others bid against them. They skip tenders. The lowest bid wins, and the price gaps are wide. These three win often because they are good. Set them aside.
- Chip: Busiest nodes
- Action: toolbox ▸ Quick questions ▸ Busiest nodes ▸ hold on the ranked list; click the top name ▸ the three lookalike firms shown. [check in UI: the three leaders are CO-0021, CO-0055 and CO-0090]
- Impact: mark the three leaders grey with *ruled out* (the decoy marker).

### S06 · The pattern is further down  ~40 s
- Voice: Look lower in the list. [pause 0.4] The next firms have far fewer links, thirty-six and thirty-four. Open one of them and look at what it shares. Select the person P-00001, and focus on them. [pause 0.7] This one person directs two companies, and both are among the four that bid on every northern tender. Two rivals, one director.
- Chip: Node details · Focus
- Action: click `P-00001` twice ▸ Node details ▸ Focus; then select the two directed firms. [check in UI: P-00001 is linked to CO-0242 and CO-0561]
- Impact: mark P-00001 and its two firms in yellow, the lead.

### S07 · The evidence you can quote  ~40 s
- Voice: Is this a guess? [pause 0.4] Open the Rows tab under the graph. Every line is a row: a bid, a firm, a tender. Four firms are the only bidders on twenty-four northern tenders. [pause 0.4] They win in turn, in the same order, and the losers always sit five to twelve percent above the winner. Never identical. Each row can be quoted, sorted and exported.
- Chip: Rows
- Action: dock ▸ Rows ▸ sort by tender ▸ hold on the pattern of winners.
- Impact: mark the winning column; the four firms by colour.

### S08 · What would change our mind  ~20 s
- Voice: Be fair to the firms. [pause 0.3] A single real third bidder, with a different price, on any of the twenty-four tenders would weaken this. So would a director who had left before the first bid. Neither is in the data. These are the two facts to check first.
- Chip: none
- Action: the Rows tab stays open; the story card shows *what would change our mind*.
- Impact: none.

### S09 · What the team does next  ~40 s
- Voice: This is a lead, so the work starts here. [pause 0.4] First, refer the twenty-four tenders for audit. Second, ask for the document authors and addresses of the four bids. Third, keep the four firms and the director together, so the next tender they appear on is marked. [pause 0.3] That is an Entity List. On the Entity Lists page, press New list.
- Chip: Entity Lists · New list…
- Action: rail ▸ Entity Lists ▸ New list… ▸ the form (Title, Purpose, Entity Type) ▸ Cancel (nothing is created). [check in UI: the form fields]
- Impact: mark the form's Title and Purpose.

### S10 · The case file  ~20 s
- Voice: The lead: four firms, one order, one shared director. [pause 0.4] The evidence: the rows of twenty-four tenders. The next step: audit, authors and addresses, and an Entity List. [pause 0.6] To make it defensible, see the Make it defensible module. To try it yourself, open Bid rotation on the shipped zip.
- Chip: none
- Action: the case-file card (*Lead*, *Evidence*, *Next step*, *Try it*); outro card.
- Impact: none.

## PublicSector_AddressFarm

> **Address farm: the fourth address** · Standard · Public sector (la-public-sector) · benefit claims at one address · 4:20 · plan §6 row 16

- **Cast:** the address AD-03141 and its fourteen claimants (five surnames, three bank accounts). **Decoy:** three care homes (AD-00412, AD-05077, AD-02718) with many residents, each paid into their own account. Ids readable.
- **Stakes:** a benefits fraud officer who can visit a handful of addresses a month. Four addresses hold far too many claimants for a home; three are legitimate.
- **Vocabulary:** *claimant*, a person with an open benefit claim. *Household of one*, a claim that declares a single-person home. Said once in S02.
- **Ground truth:** `tools/gen-la-benefits-data.ground-truth.json` · `address_farm` (`address` AD-03141, `claimants` 14, `surnames` Dunbar, Kettering, Oddie, Varga, Zamora, `accounts` three, `care_homes` AD-00412 31, AD-02718 24, AD-05077 27); `gen-la-benefits-data.py` line 13 and the address-farm block (five surnames in a 4-3-3-2-2 split, each a single-person household, three accounts nobody else uses). Plan table confirmed (decoy: legitimate homes).
- **Lead:** fourteen single-person claims at one address, five surnames, paid into three accounts nobody else uses. **What would change our mind:** the three accounts belonging to one registered representative who is paid on behalf of many lawful claimants, or the fourteen being lodgers with a landlord's account. **Next actions:** verify the address in person; hold the three accounts for review; mark the fourteen claims so a new claim at this address is flagged.
- **Voice style:** the reveal scene (S06) holds 0.7 s before "Fourteen single-person homes." Lexicon: AD-03141 "a d zero three one four one"; *claimant* as a word.
- **Try it:** open *Address farm* and press Busiest nodes. How do you tell a care home from the farm? *Answer: by the money. A care home's residents are each paid into their own account; the farm's fourteen share three.*
- **Check:** why is the largest address not the lead? *Answer: many people at one address is normal for a care home; only the shared accounts and the single-person declarations are unusual.*

### S01 · The address with too many people  ~25 s
- Voice: You work in a benefits fraud team, and you cannot visit every address. [pause 0.4] Four addresses hold far more claimants than any home should. Three are care homes, which is legitimate. [pause 0.6] The fourth is a house. You need to find out which one is wrong before you spend a visit.
- Chip: none
- Action: cold open on the Space's home page; intro card (*Address farm*, *Public sector*, *Standard*).
- Impact: none.

### S02 · Why each claim looks fine  ~30 s
- Voice: Each claim, read alone, is ordinary. A person, an address, a benefit, a bank account. [pause 0.4] A household of one is a claim that declares a single-person home. It passes the checks because nothing in it contradicts the rest. [pause 0.5] The oddity is only visible when you count who else declares the same address, and whose account the money goes to.
- Chip: none
- Action: home page; the story card names *the care home* and *the house*.
- Impact: none.

### S03 · The data in one breath  ~30 s
- Voice: Every claim lands as a record: the claim, the person, the benefit, the dates, the amount and the bank account paid into. [pause 0.3] A Job links each claimant to the address they declare and to the account they are paid into. People, addresses and accounts are the entities, and the links are the claims.
- Chip: Mappings · Data
- Action: rail ▸ Data ▸ the benefits links Dataset ▸ zoom on the Mappings area. [check in UI: the Mappings area of `benefits_screen_links_dataset`]
- Impact: mark the From and To columns.

### S04 · The question  ~25 s
- Voice: Open the saved view Address farm. [pause 0.4] It draws addresses with many claimants. The question is which of these is a home, and which is a front door for many claims. [pause 0.4] Four hubs stand out, each with a crowd around it.
- Chip: File · Open… · Saved views
- Action: File ▸ Open… ▸ Saved views ▸ *Address farm*; Display options ▸ node labels on (off above 80 nodes). [check in UI: four orange hubs]
- Impact: before/after hold: the graph appears; mark the four hubs.

### S05 · The big three are homes  ~40 s
- Voice: Press Busiest nodes. [pause 0.4] Three addresses lead, with thirty-one residents, twenty-seven and twenty-four. Look at the money. Each resident is paid into their own account. These are care homes, and they are genuine. [pause 0.6] The fourth address is smaller, and stranger. It has fewer people, and something else it shares.
- Chip: Busiest nodes
- Action: toolbox ▸ Quick questions ▸ Busiest nodes ▸ hold on the ranked list; click the top three. [check in UI: the three leaders are AD-00412, AD-05077 and AD-02718]
- Impact: mark the three care homes grey with *ruled out*.

### S06 · The fourth address  ~45 s
- Voice: Select the fourth address, AD-03141, and focus on it. [pause 0.7] Fourteen residents. Five surnames. [pause 0.4] And every one of them declares a household of one. Fourteen single-person homes, at one address. A care home has residents with their own accounts. This has fourteen people and a handful of accounts.
- Chip: Node details · Focus
- Action: click `AD-03141` twice ▸ Node details ▸ Focus ▸ hold on the fourteen claimants and the accounts. [check in UI: fourteen claimants, three accounts]
- Impact: mark AD-03141 and the fourteen in yellow; the three accounts outlined.

### S07 · The evidence you can quote  ~40 s
- Voice: Open the Rows tab. Every line is a row: a claimant, the address, the account. [pause 0.4] Fourteen claims, five surnames, and just three bank accounts that nobody else uses. Each row can be sorted by surname or by account, and exported. [pause 0.3] The links tell a care home from a farmed address. The money is what separates them.
- Chip: Rows
- Action: dock ▸ Rows ▸ sort by account ▸ hold.
- Impact: mark the account column; group the rows by colour.

### S08 · What would change our mind  ~20 s
- Voice: Be careful with what this shows. [pause 0.3] If the three accounts belong to one registered representative, paid on behalf of many lawful claimants, the picture changes. So it would if the fourteen turn out to be lodgers whose landlord is paid. Those are the two checks to make first.
- Chip: none
- Action: the Rows tab stays open; the story card shows *what would change our mind*.
- Impact: none.

### S09 · What the team does next  ~40 s
- Voice: This is a lead, so here is the work. [pause 0.4] First, verify the address in person. Second, hold the three accounts for review. Third, bring the fourteen claims together as a Case. [pause 0.3] Select the claimants, and press Attach to Case. A new Case from the selected nodes is open to you here, because these ids are readable. Read it, and cancel.
- Chip: Attach to Case
- Action: select the fourteen claimants (Select neighbours of AD-03141) ▸ canvas toolbar ▸ Attach to Case ▸ read *A new Case, from the selected nodes* ▸ Cancel (nothing is created). [check in UI: the new-Case option is enabled for readable ids]
- Impact: mark the *A new Case, from the selected nodes* option.

### S10 · The case file  ~20 s
- Voice: The lead: fourteen single-person claims, five surnames, three accounts. [pause 0.4] The evidence: the rows under the graph. The next step: verify, hold, and open a Case. [pause 0.6] To try it yourself, open Address farm and compare a care home with the fourth address.
- Chip: none
- Action: the case-file card; outro card.
- Impact: none.

## Healthcare_ReferralKickbacks

> **Referral kickbacks: nine practices, one imaging centre** · Standard · Healthcare (la-healthcare) · referrals and ownership · 4:30 · plan §6 row 17

- **Cast:** the imaging centre PR-1170, its owner OW-0434, two referring practices (PR-1426, PR-1392), the owner OW-1270 who shares a home (AD-05150) with OW-0434. **Decoy:** the only local hospital (PR-1129, owner OW-0263), the busiest receiver. Ids readable.
- **Stakes:** a health-system integrity analyst. In a small town, sending most referrals to one place can be geography. The question is whether any of it has a reason.
- **Vocabulary:** *referral*, one provider sending a patient to another. *Ownership tie*, who owns a practice. Said once in S02.
- **Ground truth:** `tools/gen-la-health-data.ground-truth.json` · `referral_kickbacks` (`imaging_centre` PR-1170, `spouse` OW-0434, `practice_owner` OW-1270, `second_practice_owner` OW-1286, `practices` [PR-1426, PR-1392], `home` AD-05150, `lookalike_hospital` PR-1129, `lookalike_hospital_owner` OW-0263, `scarborough_practices` seven). Nine practices = the seven seaside practices plus the two city practices. **Plan table corrected:** the table said "the specialist everyone refers to vs a loop paid back"; the data says the decoy is the only local hospital (geography) and the lead is an *ownership tie by a shared home*.
- **Lead:** two city practices send their patients to an imaging centre whose owner shares a home with the owner of one of them. **What would change our mind:** the owners declared the tie and the referrals are documented as clinically driven, or the centre is the only one with that equipment. **Next actions:** open a conflict-of-interest review of the two practices; ask for the referral reasons on the two practices' patients; keep the centre and the three owners together as an Entity List.
- **Voice style:** the reveal scene (S06) holds 0.7 s before "They share a home." Lexicon: PR-1170 "p r one one seven zero", OW-0434 "o w zero four three four".
- **Try it:** open *Referral kickbacks*, press Busiest nodes and compare the top name with the imaging centre. *Answer: the busiest name is the only hospital in the seaside town, which is geography; the imaging centre has few links and an ownership tie.*
- **Check:** why is the busiest receiver not the lead? *Answer: a town with one hospital sends it most referrals; only an ownership tie gives a reason to choose one place over another.*

### S01 · Nine practices, one destination  ~25 s
- Voice: You are an integrity analyst for a health system. [pause 0.4] Nine practices send nearly all their referrals to one place. In a small town, that can be geography. [pause 0.6] Or it can be a reason. Referral data alone cannot say which. Ownership can.
- Chip: none
- Action: cold open on the Space's home page; intro card (*Referral kickbacks*, *Healthcare*, *Standard*).
- Impact: none.

### S02 · Why referrals alone are not enough  ~30 s
- Voice: A referral is one provider sending a patient to another. Counting them tells you who is busy, never why. [pause 0.4] Ownership says who owns each practice, and where the owners live. [pause 0.4] Referrals and ownership sit in different records. Only when they are joined does a pattern either hold up, or fall apart.
- Chip: none
- Action: home page; the story card names *the local hospital*, *the imaging centre*, *the owners*.
- Impact: none.

### S03 · The data in one breath  ~30 s
- Voice: Every referral lands as a record: the sending provider, the receiving provider, the patient and the date. [pause 0.3] A Job links providers to each other by referral, and to their owners and owners' addresses by ownership. Providers, owners and addresses are the entities.
- Chip: Mappings · Data
- Action: rail ▸ Data ▸ the health links Dataset ▸ zoom on the Mappings area. [check in UI: the Mappings area of `health_screen_links_dataset`]
- Impact: mark the From and To columns.

### S04 · The question  ~25 s
- Voice: Open the saved view Referral kickbacks. [pause 0.4] It draws the practices, where they refer, and who owns what. The question is whether any referral pattern coincides with an ownership tie. [pause 0.4] A small, readable graph, with owners and homes on it.
- Chip: File · Open… · Saved views
- Action: File ▸ Open… ▸ Saved views ▸ *Referral kickbacks* ▸ hold. [check in UI: a small graph with PR-, OW- and AD- labels]
- Impact: before/after hold: the graph appears; mark the owners.

### S05 · The busiest name is geography  ~40 s
- Voice: Press Busiest nodes. [pause 0.4] The busiest provider receives from seven practices. It is the only hospital in a seaside town, owned by a health trust. [pause 0.6] That is geography. People go where the hospital is. Set it aside. The second name has only three links, and it is a private imaging centre.
- Chip: Busiest nodes
- Action: toolbox ▸ Quick questions ▸ Busiest nodes ▸ hold; click the top name. [check in UI: the top name is PR-1129 and its owner OW-0263]
- Impact: mark PR-1129 grey with *ruled out*.

### S06 · The centre and its owner  ~45 s
- Voice: Select the imaging centre, PR-1170, and focus on it. [pause 0.5] Two city practices send their patients here: PR-1426 and PR-1392. And the centre has one owner, OW-0434. [pause 0.4] Follow the owner's home. [pause 0.7] They share a home with OW-1270, who owns one of the two referring practices.
- Chip: Node details · Focus
- Action: click `PR-1170` twice ▸ Focus ▸ follow OW-0434 to `AD-05150` and back to `OW-1270`. [check in UI: OW-0434 and OW-1270 both link to AD-05150]
- Impact: mark OW-0434, AD-05150 and OW-1270 in yellow.

### S07 · The evidence you can quote  ~40 s
- Voice: Open the Rows tab. Each line is a row: a referral, an owner, a home. [pause 0.4] The referrals from the two practices to the centre, the ownership of the centre, and the shared home are all there. Each row can be read, sorted and exported to the reviewer. [pause 0.3] The tie is in the data, not in a theory.
- Chip: Rows
- Action: dock ▸ Rows ▸ sort by link kind ▸ hold on the referral and ownership rows.
- Impact: mark the three owner rows.

### S08 · What would change our mind  ~20 s
- Voice: Check the other side. [pause 0.3] If the owners declared the tie and the referrals are documented as clinically driven, this is a disclosed interest. If the centre has equipment nobody else has, the referrals are explained. Both are one request away.
- Chip: none
- Action: the Rows tab stays open; the story card shows *what would change our mind*.
- Impact: none.

### S09 · What the team does next  ~40 s
- Voice: This is a lead, so here is the work. [pause 0.4] First, open a conflict-of-interest review of the two practices. Second, ask for the referral reasons on their patients. Third, keep the centre and its three owners together. [pause 0.3] That is an Entity List. On the Entity Lists page, press New list.
- Chip: Entity Lists · New list…
- Action: rail ▸ Entity Lists ▸ New list… ▸ the form ▸ Cancel (nothing is created). [check in UI: the form fields]
- Impact: mark the form's Title and Purpose.

### S10 · The case file  ~20 s
- Voice: The lead: an imaging centre fed by two practices, one owner who shares a home. [pause 0.4] The evidence: the referral and ownership rows. The next step: a review, the referral reasons, and an Entity List. [pause 0.6] To try it yourself, open Referral kickbacks and compare the busiest name with the centre.
- Chip: none
- Action: the case-file card; outro card.
- Impact: none.

## Logistics_InsideJob

> **Inside job: twelve short loads** · Standard · Logistics (la-logistics-industry) · a driver pair and one scanner · 4:30 · plan §6 row 18

- **Cast:** the driver DRV-0892 and co-driver DRV-0893 (carrier CAR-052), the warehouse scanner EMP-0123 (WH-06). **Decoy:** the driver DRV-0894 on the busiest route (Southampton to Birmingham), who lost two loads too and shares nothing with them. Ids readable.
- **Stakes:** a loss-prevention manager. Loads arrive short on different routes in different months; cargo theft is common, so it reads as bad luck.
- **Vocabulary:** *short load*, a delivery that arrives with goods missing. *Scanner*, the warehouse person who scans a load onto a truck. Said once in S02.
- **Ground truth:** `tools/gen-la-logistics-data.ground-truth.json` · `inside_job` (`driver` DRV-0892, `co_driver` DRV-0893, `carrier` CAR-052, `losses` twelve, `trips` twelve, `night_trips` ten, `scanner` EMP-0123, `scanner_warehouse` WH-06, `lookalike_driver` DRV-0894, `lookalike_carrier` CAR-103, `lookalike_trips` two, `busiest_route` Southampton, Birmingham); `gen-la-logistics-data.py` lines 13 and 436–444 (ten of the twelve left Nottingham at night, scanned out by the same night-shift scanner). **Plan table corrected:** the table said "the driver with most incidents"; the data says the decoy is the driver on the busiest route, not the one with most incidents.
- **Lead:** twelve short loads, one driver pair on all of them, and one night-shift scanner on ten. **What would change our mind:** a rota showing the scanner worked other shifts on those nights, or a second pair on any of the twelve trips. **Next actions:** interview the three people; pull the warehouse badge records for the ten nights; compare WH-06 night scans against the outbound loads.
- **Voice style:** cold open 1.04; the reveal scene (S06) holds 0.7 s before "Ten of the twelve." Lexicon: DRV-0892 "driver zero eight nine two"; EMP-0123 "employee zero one two three"; do not read trip or loss ids aloud.
- **Try it:** open *Driver team inside job* and press Busiest nodes. Who is on top besides the drivers? *Answer: a warehouse scanner, EMP-0123, who is not a driver at all.*
- **Check:** why is the busiest driver on the busiest route not the lead? *Answer: busy routes lose loads by bad luck; the lead is the same pair, and the same scanner, on every short load.*

### S01 · Loads that arrive short  ~25 s
- Voice: You are a loss-prevention manager. [pause 0.4] Loads keep arriving short. Different routes, different months, different customers. Each customer reports to a different depot, and nobody puts the reports side by side. [pause 0.6] Cargo theft is common, so it looks like bad luck. This film asks whether it is.
- Chip: none
- Action: cold open on the Space's home page; intro card (*Driver team inside job*, *Logistics*, *Standard*).
- Impact: none.

### S02 · Why each loss looks like bad luck  ~30 s
- Voice: A short load is a delivery that arrives with goods missing. [pause 0.3] Each one is a small claim against a carrier. Each is explained: a long stop, a lay-by, an unlucky night. A scanner is the warehouse person who scans a load onto a truck. [pause 0.5] Alone, none of these points to a person. Together, they might point to a team.
- Chip: none
- Action: home page; the story card names *the driver pair* and *the scanner*.
- Impact: none.

### S03 · The data in one breath  ~30 s
- Voice: Every trip lands as a record: the shipment, the driver, the co-driver, the vehicle and the times. [pause 0.3] A Job links drivers and vehicles to trips, trips to the loads that arrived short, and scanners to the loads they scanned. People, vehicles and loads are the entities.
- Chip: Mappings · Data
- Action: rail ▸ Data ▸ the logistics links Dataset ▸ zoom on the Mappings area. [check in UI: the Mappings area of `logistics_screen_links_dataset`]
- Impact: mark the From and To columns.

### S04 · The question  ~25 s
- Voice: Open the saved view Driver team inside job. [pause 0.4] It draws the short loads, the trips that carried them and the people on those trips. The question is whether one person, or one pair, is on more than their share.
- Chip: File · Open… · Saved views
- Action: File ▸ Open… ▸ Saved views ▸ *Driver team inside job* ▸ hold. [check in UI: trips, loads and drivers readable]
- Impact: before/after hold: the graph appears; mark the dense centre.

### S05 · The busiest route is a decoy  ~40 s
- Voice: Press Busiest nodes. [pause 0.4] Two drivers lead with twelve links each, the same twelve. And a third name, which is not a driver at all: a warehouse scanner. [pause 0.5] First, set the obvious one aside. The driver on the busiest route lost two loads too. But those two share nothing with the others. Bad luck on a busy road.
- Chip: Busiest nodes
- Action: toolbox ▸ Quick questions ▸ Busiest nodes ▸ hold; click DRV-0894. [check in UI: DRV-0894 has two losses and no shared people]
- Impact: mark DRV-0894 grey with *ruled out*.

### S06 · The pair and the scanner  ~45 s
- Voice: Select the driver DRV-0892, and focus on them. [pause 0.5] Twelve trips. Twelve loads that arrived short. The same driver drove every one, with the same co-driver beside them. [pause 0.7] Now look at who scanned the loads on. Ten of the twelve were scanned out by the same person, on the night shift.
- Chip: Node details · Focus
- Action: click `DRV-0892` twice ▸ Focus ▸ follow `DRV-0893` and `EMP-0123`. [check in UI: EMP-0123 is linked to ten of the twelve trips]
- Impact: mark DRV-0892, DRV-0893 and EMP-0123 in yellow.

### S07 · The evidence you can quote  ~40 s
- Voice: Open the Rows tab. Each line is a row: a trip, the driver, the co-driver, the load. [pause 0.4] The same two drivers, twelve times. Ten of those trips also carry the same scanner. Sort by shift, and the ten gather at night. [pause 0.3] A pattern like this can be quoted, exported, and put in front of a manager.
- Chip: Rows
- Action: dock ▸ Rows ▸ sort by driver ▸ hold on the twelve.
- Impact: mark the driver columns; the ten night trips by colour.

### S08 · What would change our mind  ~20 s
- Voice: Be fair to the three. [pause 0.3] If the scanner's rota puts them on another shift for those nights, the tie weakens. So does a second pair on any of the twelve trips. The rota and the trip crew lists are the first two things to ask for.
- Chip: none
- Action: the Rows tab stays open; the story card shows *what would change our mind*.
- Impact: none.

### S09 · What the team does next  ~40 s
- Voice: This is a lead, so here is the work. [pause 0.4] First, interview the three people. Second, pull the warehouse badge records for the ten nights. Third, compare the night scans at that warehouse with the loads that left. [pause 0.3] To keep this view for the team, press Save on the Investigation tab. Read what it asks for, and cancel.
- Chip: Investigation · Save…
- Action: toolbox ▸ Investigation tab ▸ Save… ▸ read the name and purpose fields ▸ Cancel (nothing is saved). [check in UI: the Save dialog]
- Impact: mark the Name and Purpose fields.

### S10 · The case file  ~20 s
- Voice: The lead: twelve short loads, one driver pair, one scanner. [pause 0.4] The evidence: the trip rows, with the ten at night. The next step: three interviews, badge records, and the night scans. [pause 0.6] To try it yourself, open Driver team inside job and find the third name.
- Chip: none
- Action: the case-file card; outro card.
- Impact: none.

## Enterprise_FictitiousVendor

> **Fictitious vendor: fourteen clean invoices** · Standard · Enterprise (la-enterprise) · a vendor that shares an employee's account · 4:45 · plan §6 row 19

- **Cast (roles, because the graph is masked):** the vendor behind the fourteen invoices, the employee who shares its bank account and address, and that employee's manager. Revealed by Demo Admin in S06: V-117, BA-9031, E-31, E-15. **Decoy:** the biggest vendor, a utilities company (V-001), with the most payments of all. The analyst reads aliases: *291 entities masked*.
- **Stakes:** an internal auditor. Fourteen invoices, all approved and paid, nothing late or duplicated. An audit that checks each invoice against its approval finds nothing.
- **Vocabulary:** *vendor master*, the list of suppliers with their bank accounts and addresses. *Approver*, the person who signs an invoice off. Said once in S02.
- **Ground truth:** `tools/gen-la-audit-data.py` (no JSON file: the story is in the header comment, line 13, and constants): `FICTITIOUS` V-117, `E31` E-31, `E31_MANAGER` E-15, `vendor_account[FICTITIOUS] = emp_account[E31] = "BA-9031"` (line 86), fourteen invoices approved by E-15 (line 196), `UTILITIES` V-001 "Meridian Utilities Ltd" (lines 47, 101). Invoice amounts are not stated. **Plan table corrected:** the table said "the vendor with the largest invoices"; the decoy is the vendor with the *most payments*, a utilities company. **Fixes the v1 defect:** v1 read ids over this masked graph; v2 uses roles until the administrator reveals.
- **Lead:** a vendor that shares a bank account and an address with an employee, with fourteen invoices all approved by that employee's manager. **What would change our mind:** the shared account turning out to be a payment bureau used by many vendors, or the address being a shared office. **Next actions:** stop payments to the vendor pending verification; review every invoice the manager approved for it; search the vendor master for other vendors sharing an employee's account.
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
- Voice: Look for the small cluster, a vendor joined to an employee. [pause 0.4] You can see its shape without the names. One vendor, one bank account, and one employee joined to that account twice: the same account, and the same address. [pause 0.7] To name them, you need the administrator. Signed in as Demo Admin, press Reveal, and give a reason. The ids are shown to this person, on this screen only, and the reveal is recorded.
- Chip: Reveal… · Reason
- Action: select the small cluster (vendor, account, employee) ▸ [as Demo Admin, off camera sign-in] Reveal… ▸ Reason `confirm the vendor before payments are stopped` ▸ Reveal ▸ hold on V-117, BA-9031, E-31. [check in UI: the reveal dialog and the toast; the ids shown]
- Impact: before/after hold: aliases turn into V-117, BA-9031 and E-31; mark the shared account.

### S07 · The evidence you can quote  ~40 s
- Voice: Open the Rows tab. Each line is a row: a payment, a link, a shared detail. [pause 0.4] Fourteen invoices paid to the vendor. Every one approved by the same manager, E-15. And two shared details: the account and the address. [pause 0.3] Each row can be exported. The reveal itself is in the audit trail, with the reason you gave.
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

> **Access creep: who could, and who let them** · Standard · Enterprise (la-enterprise) · four grants and one bank-detail change · 4:30 · plan §6 row 20

- **Cast:** the Operations Manager EMP-0007, their four reports (EMP-0482, EMP-0636, EMP-1059, EMP-1415) and the vendor VEN-0264 whose bank details one of them changed. **Decoy:** the Payroll Officer EMP-0062, whose payroll access came with a role change on 2026-03-02, a manager (EMP-1802) and a ticket (REQ-163776). Ids readable.
- **Stakes:** an access-review analyst. A vendor's bank details were changed once, quietly. That is one edit among thousands of users. The question is who could make it, and who allowed that.
- **Vocabulary:** *grant*, one person giving another access to a system. *Ticket*, the approval a grant should carry. Said once in S02.
- **Ground truth:** `tools/gen-la-insider-data.ground-truth.json` · `access_creep` (`manager` EMP-0007 "Operations Manager", `reports` four, `systems` PAYROLL and TREASURY, `grants` eight, 2026-01-12 to 2026-02-10, `vendor_editor` EMP-0482, `vendor` VEN-0264, `vendor_changed_at` 2026-05-14 10:42:17, `lookalike_employee` EMP-0062, `lookalike_role_since` 2026-03-02, `lookalike_manager` EMP-1802, `lookalike_ticket` REQ-163776). v1 states that none of the four had a ticket; confirm in the Rows tab before recording. **Plan table corrected:** the table said "the employee with the most access"; the decoy is a payroll officer whose access came with a role change and a ticket.
- **Lead:** one manager gave four of their own reports payroll and treasury access, none of whom works in finance and none with a ticket, and one of the four then changed a vendor's bank details. **What would change our mind:** tickets for the four grants that the log does not hold, or a documented cover arrangement for finance. **Next actions:** start an access review with the one manager; stop and check the payment to the vendor; keep the four reports and the vendor together as an Entity List.
- **Voice style:** cold open 1.04; the reveal scene (S06) holds 0.7 s before "None of the four works in finance." Lexicon: EMP-0007 "employee zero zero zero seven"; VEN-0264 "vendor zero two six four"; speak the systems as "payroll" and "treasury".
- **Try it:** open *Access creep* and press Busiest nodes. How many links does the top name have, and why is that few? *Answer: four; it is a small graph, so four links to one manager is what stands out, not volume.*
- **Check:** why is the payroll officer not the lead? *Answer: a role change with a ticket looks the same in the access log, but it has a manager and an approval; the four grants have neither.*

### S01 · One quiet edit  ~25 s
- Voice: You are an access-review analyst. [pause 0.4] A vendor's bank details were changed once, quietly. In a system with thousands of users, that is one edit. [pause 0.6] You do not need to know who made it. You need to know who could, and who let them. An audit of the change finds a valid login.
- Chip: none
- Action: cold open on the Space's home page; intro card (*Access creep*, *Enterprise*, *Standard*).
- Impact: none.

### S02 · Why the change looks valid  ~30 s
- Voice: A grant is one person giving another access to a system. A ticket is the approval a grant should carry. [pause 0.4] The log shows the bank-detail change was made by someone with access. That is true. [pause 0.5] The access log shows each grant on its own line. It does not show that one manager gave the same two systems to four people, one after another.
- Chip: none
- Action: home page; the story card names *the manager*, *the four reports* and *the vendor*.
- Impact: none.

### S03 · The data in one breath  ~30 s
- Voice: Every access grant lands as a record: the employee, the system, who granted it, when, and the ticket behind it. [pause 0.3] A Job links employees to the systems they use, and each grant to the person who made it. Employees and systems are the entities, and the time of the grant orders them.
- Chip: Mappings · Data
- Action: rail ▸ Data ▸ the insider links Dataset ▸ zoom on the Mappings area. [check in UI: the Mappings area of `insider_screen_links_dataset`]
- Impact: mark the From and To columns.

### S04 · The question  ~25 s
- Voice: Open the saved view Access creep. [pause 0.4] It draws grants: who gave what access to whom. Wait for it to draw; it takes a moment. [pause 0.4] A small graph, and most of it is what you would expect: managers granting access to their own teams.
- Chip: File · Open… · Saved views
- Action: File ▸ Open… ▸ Saved views ▸ *Access creep* ▸ wait for the nodes (10–25 s). [check in UI: the nodes appear after a skeleton; no fixed wait]
- Impact: before/after hold: the skeleton, then the graph; mark the hub at the left.

### S05 · A payroll officer is the decoy  ~40 s
- Voice: Press Busiest nodes. [pause 0.4] One name on top, with four links. Another approver follows with three. Four is not many. It depends who the four are. [pause 0.5] First, set the obvious one aside. A payroll officer has payroll access. But the role changed in March, with a manager and a ticket. Legitimate. Set it aside.
- Chip: Busiest nodes
- Action: toolbox ▸ Quick questions ▸ Busiest nodes ▸ hold; click EMP-0062. [check in UI: EMP-0062 with manager EMP-1802]
- Impact: mark EMP-0062 grey with *ruled out*.

### S06 · The manager and the four  ~45 s
- Voice: Select the top name, EMP-0007, and focus on them. [pause 0.5] An operations manager. Four of their own team were given payroll and treasury, between January and February. [pause 0.7] None of the four works in finance. None had a ticket. And one of them, EMP-0482, is the person who changed the bank details of the vendor.
- Chip: Node details · Focus
- Action: click `EMP-0007` twice ▸ Focus ▸ follow the four reports and `VEN-0264`. [check in UI: EMP-0482 links to VEN-0264]
- Impact: mark EMP-0007, the four reports and VEN-0264 in yellow.

### S07 · The evidence you can quote  ~40 s
- Voice: Open the Rows tab. Each line is a row: who granted what, to whom, and when. [pause 0.4] Eight grants, two systems, four people, over four weeks. The ticket column is empty. [pause 0.3] And the vendor change, a line of its own, a few months later. Every row can be sorted by date, read in order, and exported.
- Chip: Rows
- Action: dock ▸ Rows ▸ sort by time ▸ hold on the eight grants and the vendor change.
- Impact: mark the ticket column; the vendor row.

### S08 · What would change our mind  ~20 s
- Voice: Be fair to the manager. [pause 0.3] If tickets exist that the log does not hold, or the manager was covering for finance by arrangement, the picture softens. Both are quick to check, and both belong in the first conversation.
- Chip: none
- Action: the Rows tab stays open; the story card shows *what would change our mind*.
- Impact: none.

### S09 · What the team does next  ~40 s
- Voice: This is a lead, so here is the work. [pause 0.4] First, start an access review with the one manager. Second, stop and check the payment to the vendor. Third, keep the four reports and the vendor together, so any further change to either is marked. [pause 0.3] That is an Entity List. On the Entity Lists page, press New list.
- Chip: Entity Lists · New list…
- Action: rail ▸ Entity Lists ▸ New list… ▸ the form ▸ Cancel (nothing is created). [check in UI: the form fields]
- Impact: mark the form's Title and Purpose.

### S10 · The case file  ~20 s
- Voice: The lead: one manager, four grants, and a changed bank account. [pause 0.4] The evidence: the grant rows and the vendor change. The next step: a review, a payment check, and an Entity List. [pause 0.6] To try it yourself, open Access creep and compare the manager with the payroll officer.
- Chip: none
- Action: the case-file card; outro card.
- Impact: none.
