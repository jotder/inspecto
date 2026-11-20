---
type: Spec
title: "Inspecto LA case videos v2 — Banking and finance: six case specs"
description: "The v2 spoken script, screen action and marks of the six banking case films (till structuring, sanctioned owner, card compromise, circular trade, crypto cash-out, money-mule ring), written to the case-video standard."
resource: tools/render-la-demo-video.mjs
tags: [module, la-core, link-analysis, video, case, banking, spec]
timestamp: 2026-11-19T00:00:00Z
item: module.la-core
audience: internal
status: current
verified: 2026-11-19 last-edit
code: la/inspecto-la-core
---

# Inspecto LA case videos v2 — Banking and finance

> **Recording environment (operator, 2026-11-20):** the Standard edition with the fake Demo Auth sign-in and the bundled example Spaces, from a fresh `la-standard.zip` unzip per lane; plan [§4.5](../../../../superpower/2026-11-19-la-case-videos-v2-plan.md).

> Six case specs under [the case-videos v2 plan](../../../../superpower/2026-11-19-la-case-videos-v2-plan.md) (standard in §3–§5,
> gates in §7). They replace the banking sections of the v1 scripts
> ([brochure-video-demo-case-scripts.md](brochure-video-demo-case-scripts.md) and
> [brochure-video-demo-scripts.md](brochure-video-demo-scripts.md)), which stay as the source of the stories. Draft, not
> recorded, not reviewed by product. `[check in UI: …]` marks a detail to confirm on the live Standard build before recording;
> `[pause 0.6]` is a silence of that many seconds. Format of each scene as in the training scripts: `Voice`, `Chip`, `Action`,
> `Impact`; the renderer reads `## <id>` and `### Sxx · title`.
>
> **Screen truth checked 2026-11-19 on Standard :8081 as Demo Analyst.** In **Banking and finance** the ids are readable:
> the four saved views draw company, container, card and account ids unmasked (labels are off above 80 nodes until Display
> options turns them on; *Sanctioned owner path* also shows *Result cut short … node cap (1000)*). In **Other Examples** the
> accounts and wallets are **masked for the analyst** (*112 entities masked · Reveal…* under the canvas of both *Structuring
> into one account* and *Money-mule layering ring*): the till film and the ring film say roles, not ids, and use the story
> card. The v1 films named ids in a Space that masks them for this user.

## Banking_TillStructuring

> **The exit, not the entry** · Standard · Other Examples (`la-showcase`) · saved view *Structuring into one account*
> (`mule_structuring`) · about 4½ min

- Cast: the **till** where a ring's cash comes out (TILL-06); the **eight cash-out accounts** (CASHER-01…CASHER-08); the **second hub** that funds them (MULE-HUB-02). Decoys: the **first ring's hub** (MULE-HUB-01, in the same view), the **benefits payer** (SKIMMER-01), the five **other tills** (TILL-01…TILL-05). All masked for the analyst: said as roles.
- Stakes: the investigator is a cash-handling analyst at a bank with hundreds of tills. A ring is paid out in cash somewhere, and every till looks busy; each week it is not found, the cash is gone.
- Vocabulary: *structuring* (breaking a sum into amounts under a reporting limit), *cash-out* (the withdrawal that turns the money back into cash). Both said once in the cold open.
- Ground truth: `tools/gen-la-showcase-data.py` (the lists `TILLS`, `CASHERS`, `HUB2`, `SKIMMER`; the cash-out loop at "cash-out through TILL-06", line 83; `payer == HUB2 and payee in CASHERS`, line 192; pinned by `LaShowcaseGoldenTest`); v1 figures: twenty-four links on the till and on the second hub, three cash-outs per account, ninety-seven percent of the till's cash to those eight accounts `[check in UI: the share, from the Rows of the till's links]`. **Plan table corrected:** the v1 story and the generator are about the *exit* (one till that serves a ring), not many tills under a limit; the decoys are the first hub, the benefits payer and the other tills.
- Before: Standard, la-showcase, Demo Analyst, a fresh state; the saved view opens from File ▸ Open… ▸ Saved views. The ids are masked: the film never reads an alias aloud.
- Lead: *one till, eight accounts, one funder*. **What would change our mind:** if the eight accounts also cashed out at other tills in the same proportions, the till would be the neighbourhood's habit, not the ring's exit. **Next actions:** (1) send the till and the eight accounts to the branch-risk team for a visit, (2) keep the eight as an Entity List and put the hub on a thirty-day hold, (3) watch the till with an Alert Rule on new accounts that cash out there.
- Voice style: pace as plan §4.1; cold open slow; lexicon exceptions: *till* (not "teller"), "second hub", "casher" said as "cash-out account".
- Try it / Check: on `la-standard.zip`, open *Structuring into one account*, press *Busiest nodes*, and find the till. *Why is the first hub not the lead of this film?* Answer: it is the previous story's ring and has no cash-out here; the lead is the till where this ring's money comes out.

### S01 · A Friday at the tills                                          ~25 s
- Voice: A branch manager reads a list of busy tills, and every one of them looks busy. [pause 0.5] Cash was broken into small deposits, passed through accounts, and then it had to turn back into cash somewhere. Every ring needs an exit. [pause 0.6] Which till is this one using?
- Chip: none (cold open on a plain card, then the intro card)
- Action: cold open: still of the canvas under a slow push-in; intro card *Banking · Till structuring · Standard*.
- Impact: none.

### S02 · Why nothing fired                                             ~30 s
- Voice: Each deposit was under the reporting limit. Each withdrawal was an ordinary amount at an ordinary till. [pause 0.4] A check that reads one record at a time passes every one of them. The pattern only exists between the records: which accounts paid out, and where.
- Chip: none
- Action: slow zoom on the toolbox quick questions while the voice runs.
- Impact: none.

### S03 · The data, in one breath                                       ~30 s
- Voice: Every transfer lands as a record: who paid, who was paid, the channel, the amount, the time. Both ends are accounts, so every row is already a link. [pause 0.4] A cash-out is a link from an account to a till.
- Chip: Mappings · Dataset
- Action: Data page ▸ the transfers Dataset ▸ zoom on the Mappings area. `[check in UI: the Mappings line names the payer, payee and time columns]`
- Impact: mark the two entity columns and the time column.

### S04 · The saved view                                                ~40 s
- Voice: The saved view keeps the accounts and the cash-outs. Each circle is an account or a till, each line a transfer or a withdrawal. [pause 0.4] The first ring from the earlier film is still in here. Look below it. The ids on this screen are masked for the analyst, so I will say what each thing is, not what it is called.
- Chip: Saved views · File ▸ Open…
- Action: File ▸ Open… ▸ Saved views ▸ *Structuring into one account* ▸ hold on the bottom line *112 entities masked · Reveal…* `[check in UI: the count]`
- Impact: mark the masked-entities line; the story card lists "the first hub", "the second hub", "a till".

### S05 · First sweep, and the decoy                                    ~45 s
- Voice: Who has the most links? One press on Busiest nodes. The first hub leads, as before, and a benefits payer is high on the list. [pause 0.4] That payer is exactly what it looks like: one payer, many ordinary payees. Set it aside. [pause 0.5] But two others stand out with twenty-four links each. A second hub, and a till.
- Chip: Busiest nodes
- Action: toolbox ▸ Busiest nodes ▸ click the rows in turn: the first hub, the benefits payer, the second hub, the till. `[check in UI: the order and that the benefits payer is in the list]`
- Impact: computed mark on the clicked node; the benefits payer marked grey "ruled out".

### S06 · Narrow to the till                                            ~40 s
- Voice: Start a new graph from the till. Before it draws anything, the product says what it knows: how many links, and when it was first and last seen. [pause 0.4] Turn on the labels. Eight accounts, and three cash-outs each. That is every customer this till has.
- Chip: Search · Display options
- Action: toolbar ▸ Search ▸ the till ▸ Focus; Display options ▸ node labels on. `[check in UI: Focus on a masked alias works from the search result]`
- Impact: computed mark on the eight accounts; zoom on the till.

### S07 · Where the money came from                                     ~35 s
- Voice: Now follow the eight back. All eight were funded by the same second hub. [pause 0.5] A busy till serves many people, a little each. This one serves one ring.
- Chip: Expand · G
- Action: select the eight accounts ▸ Expand (one degree, incoming) ▸ the second hub appears. `[check in UI: Expand filter Direction Incoming]`
- Impact: before/after hold: the second hub joins; mark it.

### S08 · Every line is a row                                           ~35 s
- Voice: Is this a guess? Open the table under the graph. Every line is a row: which account, how much, which day. Each one can be read, sorted, and exported. [pause 0.4] The graph shows the pattern. The table backs it up.
- Chip: Rows · Results
- Action: dock ▸ Rows ▸ sort by the till's links.
- Impact: mark "every line is a row" over the table.

### S09 · The lead, and what would change it                           ~40 s
- Voice: Ninety-seven percent of the cash this till paid out went to eight accounts, all funded by one hub. [pause 0.6] That is a lead, not a verdict. It would weaken if those accounts also cashed out elsewhere in the same way. [pause 0.4] So the next step is a visit, not a charge.
- Chip: none
- Action: hold on the till, the eight accounts and the second hub; the three bullets of the lead appear as a lower third.
- Impact: lead marked yellow; decoys grey.

### S10 · Monday morning                                                ~40 s
- Voice: Here is what the team does with it. Send the till and the eight accounts to the branch-risk team. Keep the eight as an Entity List, with the hub on a thirty-day hold. [pause 0.4] And watch the till for new accounts. Every graph in this Space marks that list from now on.
- Chip: Entity List · New list…
- Action: Entity Lists page ▸ New list… ▸ the eight accounts. `[check in UI: lists of masked ids may need the Demo Admin sign-in; if so, show the list already made and say so]`
- Impact: mark the new list's name.

### S11 · Case file                                                    ~15 s
- Voice: Lead: one till and the ring behind it. Evidence: the rows of the till's cash-outs. Next: visit, hold, watch. [pause 0.6] To make a case defensible, see the Standard module. Inspecto LA.
- Chip: none
- Action: case-file card (8 s) and the outro, *Try it* and the link to the shared Standard module.
- Impact: none.

## Banking_SanctionedOwner

> **The better name match is the wrong one** · Standard · Banking and finance (`la-banking`) · saved view *Sanctioned owner
> path* (`sanctioned_owner_path`) · about 5 min

- Cast: the **list entry** SL-0137; the **matched person** PER-03904; the **company** the person owns 60 % of, CO-04431; the **company behind the customer**, CO-04313; the **customer** CUS-00528. Decoys: the **large holding company** (135 stakes, v1); the **near namesake** (list entry SL-0289, match score 0.987, a better score than the real one). All ids readable.
- Stakes: the investigator is a bank's sanctions-compliance analyst. Screening found nothing at the customer; if the match is real and missed, the bank is exposed through a customer it believes is clean.
- Vocabulary: *sanctions list* (names a bank may not deal with), *beneficial owner* (the person behind the stakes), *two hops* (two ownership links away). Said in the cold open.
- Ground truth: `tools/gen-la-ownership-data.ground-truth.json` ▸ `stories.sanctions_two_hops`: entities CO-04313, CO-04431, CUS-00528, PER-03904, SL-0137; `facts` listed_share_of_owner 60, owner_share 50, match_score 0.986, near_namesake_list_id SL-0289 with score 0.987; `lookalikes` CO-04242, CUS-00642, PER-02226. **Plan table corrected:** the decoy is not "a near-match name"; it is a *better* near-match (0.987 against 0.986) with no stake in any customer's chain, which is the stronger story. The person behind SL-0289 is `PER-02226 [check in UI: that id is the namesake]`.
- Before: Standard, la-banking, Demo Analyst, fresh state; the saved view is cut short at the node cap, so the film starts the focus from the entity, not from the whole view.
- Lead: *customer CUS-00528 is two ownership hops below a possible match*. **What would change our mind:** the birth year or nationality in the match reasons differing from the list entry, or the 60 % stake having been sold before the customer was onboarded. **Next actions:** (1) escalate CUS-00528 for enhanced due diligence, (2) ask the customer for a certified ownership chain, (3) keep the five ids as an Entity List and watch new stakes in that chain with an Alert Rule.
- Voice style: ids spoken by the lexicon (PER, CO, CUS, SL as letters then digits); percentages spoken as "sixty percent".
- Try it / Check: open the saved view, run Busiest nodes, and say why the largest company is not the lead. Answer: its size is not a path to any listed person; the lead is the chain from SL-0137, which the match reasons and the stakes show.

### S01 · The clean customer                                            ~25 s
- Voice: The customer is clean. The company that owns it is clean. Screening found nothing. [pause 0.5] But a name on a sanctions list is spelled almost like someone two layers up the ownership chain. A screening check looks at the customer. Who looks two hops up?
- Chip: none
- Action: cold open on a still of the saved view; intro card.
- Impact: none.

### S02 · Why screening misses it                                      ~30 s
- Voice: Screening compares names to the customer in front of it. The stake is in another record, the owner in a third, the list in a fourth. [pause 0.4] No single row says it. The chain does.
- Chip: none
- Action: zoom slowly on the toolbox while the voice runs.
- Impact: none.

### S03 · The data, in one breath                                      ~30 s
- Voice: The ownership register lands as records: an owner, what is owned, the percentage. Beside it, the bank's customers and a sanctions list. [pause 0.4] A Job turns each register row into an owns link, and adds a name-match link where a listed name is close to a person's.
- Chip: Mappings
- Action: Data page ▸ the ownership links Dataset ▸ Mappings. `[check in UI]`
- Impact: mark the owner, owned and percentage columns.

### S04 · The saved view, and its limit                                ~40 s
- Voice: The saved view draws people, companies and customers, and who owns whom. It is large: the product says so, and cuts the drawing at its node limit. [pause 0.4] That is honest, and it is the cue to stop looking at the whole and start from one entity.
- Chip: Saved views · Graph counts
- Action: File ▸ Open… ▸ Saved views ▸ *Sanctioned owner path* ▸ hold on the banner *Result cut short … node cap (1000)*.
- Impact: mark the banner.

### S05 · First sweep, and the big company                              ~35 s
- Voice: Who has the most links? The busiest node is a company with a hundred and thirty-five stakes. Large, and nothing more. No listed name leads to it. [pause 0.5] Size is not the question here. Set it aside.
- Chip: Busiest nodes
- Action: toolbox ▸ Busiest nodes ▸ click the first row. `[check in UI: it is a company with 135 stakes; Busiest nodes on a cut graph]`
- Impact: computed mark on the company, then grey "ruled out".

### S06 · Start from the match                                         ~40 s
- Voice: Start from the person the list entry matches, PER-03904. [pause 0.4] Two links. One is a name match to list entry SL-0137: two spellings of one name, the same birth year, the same nationality. The other is a stake in a company, CO-04431.
- Chip: Search · Focus
- Action: Search ▸ PER-03904 ▸ Focus; labels on.
- Impact: computed mark on the two links; the match link opened to show its reasons. `[check in UI: the match reasons panel]`

### S07 · The better name, and why it is not the one                    ~40 s
- Voice: Here is the trap. A second list entry, SL-0289, matches an even closer name, with a higher score. [pause 0.4] Follow it, and it ends: that person owns nothing in any customer's chain. A better score is not a better lead. The stake is what counts.
- Chip: Search
- Action: Search ▸ the namesake (PER-02226 `[check in UI]`) ▸ Focus ▸ hold on the dead end.
- Impact: namesake marked grey "ruled out"; the real chain yellow.

### S08 · Two hops to the customer                                     ~45 s
- Voice: Back to the match. That person owns sixty percent of CO-04431, which owns half of CO-04313, the company behind customer CUS-00528. [pause 0.5] Two hops above a clean customer sits a possible match.
- Chip: Expand · G
- Action: Expand from PER-03904 two degrees along owns links only. `[check in UI: Expand filter Link kinds owns; Degrees 2]`
- Impact: before/after hold: the chain appears; mark each hop.

### S09 · Evidence you can show                                        ~35 s
- Voice: The stake and the name match are both rows, and the match shows its reasons. Open the table under the graph: owner, owned, percentage. [pause 0.4] This is what you hand to the reviewer, and it is all in the register.
- Chip: Rows
- Action: dock ▸ Rows ▸ select the three links of the chain.
- Impact: mark the percentage cells.

### S10 · The lead, and what would change it                           ~40 s
- Voice: One customer to escalate, never a verdict. [pause 0.5] It weakens if the birth year or the nationality differs in the match reasons, or if the sixty percent was sold before the customer joined. [pause 0.4] The register can show either.
- Chip: none
- Action: lower third with the lead and the two checks.
- Impact: lead yellow, decoys grey.

### S11 · Monday morning                                               ~35 s
- Voice: Escalate the customer for enhanced due diligence. Ask for a certified ownership chain. [pause 0.4] And keep the five ids as an Entity List, so every later graph marks the chain, and a new stake in it raises an Alert Rule.
- Chip: Entity List
- Action: New list… `Parties on a sanctions path` with the five ids ▸ done.
- Impact: mark the list name.

### S12 · Case file                                                   ~15 s
- Voice: Lead: customer CUS-00528, two hops below a possible match. Evidence: three register rows and the match reasons. Next: escalate, ask, watch. Inspecto LA.
- Chip: none
- Action: case-file card and outro.
- Impact: none.

## Banking_CardCompromise

> **One fuel station, sixty cards** · Standard · Banking and finance (`la-banking`) · saved view *Common point of purchase*
> (`common_point_of_purchase`) · about 4½ min

- Cast: the **point of compromise** (merchant M-317, a fuel station); the **sixty defrauded cards** (CRD-…); the decoy **supermarket** M-052 (the lookalike: many more cards, none defrauded). Readable ids.
- Stakes: the investigator is a card-fraud analyst. Sixty cards were defrauded weeks apart at different shops; every week without the source, more cards are copied.
- Vocabulary: *common point of purchase* (the one place the defrauded cards had all been used before the fraud), *point of compromise*. Said in the cold open.
- Ground truth: `tools/gen-la-card-data.ground-truth.json` ▸ `stories.common_point_of_purchase` (hub M-317, lookalikes M-052; `entities` 61) and ▸ `common_point_of_purchase` (`cards` 60 with `fraud_day`; `window` 2026-03-02 to 2026-03-22; `background_fraud_cards` 40 cards defrauded with no link to M-317; `in_window_not_defrauded` 10 cards that paid at M-317 in the window and were not defrauded). Fraud days run 2026-03-15 to 2026-04-24. **Plan table kept:** the decoy is the busiest merchant (M-052), and the film adds the honest caveat of the ten cards.
- Before: Standard, la-banking, Demo Analyst; the saved view is above 80 nodes, so labels are off until Display options turns them on.
- Lead: *the fuel station M-317 is where to look*. **What would change our mind:** if the sixty also share a second merchant, or if any card's fraud date fell before it paid at the station. **Next actions:** (1) send the terminal for inspection, (2) reissue the sixty cards and keep them as the Entity List *Cards to reissue*, (3) watch the ten cards that also paid there and were not defrauded.
- Voice style: card ids spoken as "card one oh oh oh one two"; dates as "the middle of March".
- Try it / Check: from M-317, expand once and count what its cards have in common. *Why is the supermarket not the lead?* Answer: it served far more cards and none was defrauded; only the links to the frauds point at the station.

### S01 · Sixty cards                                                    ~25 s
- Voice: Sixty cards were defrauded online, weeks apart, at different shops. The victims share nothing except that each card was copied somewhere, once, before the fraud. [pause 0.5] Where? The frauds themselves will not tell you.
- Chip: none
- Action: cold open and intro card.
- Impact: none.

### S02 · Why the frauds hide it                                       ~30 s
- Voice: Look at the frauds and you see sixty shops, sixty days. [pause 0.4] The place the cards share is earlier, and it is an ordinary purchase. The answer is in what came before.
- Chip: none
- Action: zoom while the voice runs.
- Impact: none.

### S03 · The data, in one breath                                      ~30 s
- Voice: Every card authorisation lands as a record: the card, the merchant, the amount, the time, the result. A Job links each card to the merchants it paid at, and to the frauds reported on it.
- Chip: Mappings
- Action: Data page ▸ the card links Dataset ▸ Mappings. `[check in UI]`
- Impact: mark card and merchant columns.

### S04 · The saved view                                               ~35 s
- Voice: The saved view keeps the merchants where defrauded cards had paid before the fraud. Each circle is a card or a merchant, each line a purchase. [pause 0.4] It is a lot of shapes, so the labels are off. Turn them on only where you need them.
- Chip: Saved views · Display options
- Action: File ▸ Open… ▸ Saved views ▸ *Common point of purchase* ▸ hold on the line *node labels off above 80 nodes*.
- Impact: mark the line.

### S05 · The busy merchant                                           ~40 s
- Voice: Who has the most links? The busiest merchant is a supermarket, M-052. [pause 0.4] It served far more cards than anyone. If busy meant guilty, this would be the lead. Open its details: cards, plenty. Defrauded cards, none.
- Chip: Busiest nodes
- Action: Open an Entity ▸ M-052 ▸ read Links and Lists. `[check in UI: Open an Entity route, as in G3]`
- Impact: M-052 marked grey "ruled out".

### S06 · The one with sixty                                           ~35 s
- Voice: Now sort by links to defrauded cards. One merchant, M-317, is linked to sixty. The next name on the list has four. [pause 0.5] That is not a close race.
- Chip: Busiest nodes
- Action: toolbox ▸ Busiest nodes ▸ read the first rows of the view. `[check in UI: M-317 first with sixty links, the next with four]`
- Impact: computed mark on M-317 yellow.

### S07 · Start from the station                                      ~40 s
- Voice: Start from M-317. A fuel station. Every line here is a card that paid at its pumps.
- Chip: Search · Focus
- Action: Search ▸ M-317 ▸ Focus; labels on.
- Impact: computed mark on the sixty; zoom.

### S08 · Every line is a row                                          ~35 s
- Voice: Open the table. Each purchase is a row: the card, the amount, the day. Sixty cards used there in three weeks of March. Each one defrauded one to six weeks later. [pause 0.5] That is the order a copying device gives you.
- Chip: Rows
- Action: dock ▸ Rows ▸ sort by date.
- Impact: mark the date column.

### S09 · The lead, and the honest caveat                              ~40 s
- Voice: The lead is a terminal to inspect. [pause 0.5] One honest caveat: ten more cards also paid there in those weeks and were not defrauded. The station is where to look, not proof. It would weaken if the sixty also share a second merchant.
- Chip: none
- Action: lower third with the lead and the caveat.
- Impact: M-317 yellow, the ten marked blue "not defrauded".

### S10 · Monday morning                                              ~35 s
- Voice: Send the terminal for inspection. Reissue the sixty cards, and keep them as an Entity List so every graph marks them. [pause 0.4] And watch the ten that were exposed and are not defrauded yet.
- Chip: Entity List
- Action: New list… `Cards to reissue`. `[check in UI]`
- Impact: mark the list name.

### S11 · Case file                                                   ~15 s
- Voice: Lead: one fuel station. Evidence: its rows, in order. Next: inspect, reissue, watch. Inspecto LA.
- Chip: none
- Action: case-file card and outro.
- Impact: none.

## Banking_CircularTrade

> **The same six containers** · Standard · Banking and finance (`la-banking`) · saved view *Circular trade*
> (`circular_trade`) · about 4½ min

- Cast: the **loop** CO-00941 → CO-00388 → CO-00213; the **six containers** (KLNU…); the decoy **leasing-pool containers** (PWCU…: ten shippers each, never back to a shipper). Readable ids; both are drawn and labelled in the view.
- Stakes: the investigator is a trade-finance analyst. Every bill of lading is in order; if the goods never go anywhere, the bank financed paper.
- Vocabulary: *bill of lading* (the receipt for goods on a ship), *consignee* (who receives them), *leg* (one shipment). Said once.
- Ground truth: `tools/gen-la-trade-data.ground-truth.json` ▸ `stories.circular_trade`: `cycle` CO-00941, CO-00388, CO-00213; `containers` KLNU7238600, KLNU0722138, KLNU9605085, KLNU6490823, KLNU5682691, KLNU7946736; `legs` 6, `leg_values` from 450,681 to 788,128 (rising, so each leg is invoiced higher), `first_ship_date` 2026-03-18, `last_ship_date` 2026-06-27; `leasing_boxes` PWCU0827459 … PWCU6477031 (the decoys, in `lookalikes`). **Plan table corrected:** the decoy is the leasing-pool boxes (the busiest nodes in the view), not a supplier loop.
- Before: Standard, la-banking, Demo Analyst; the saved view fits the screen with ids readable.
- Lead: *three companies and six trades to review*. **What would change our mind:** if the containers' position reports show real voyages between the three companies, or the price rise matches a market move. **Next actions:** (1) ask trade finance to hold new advances to the three companies, (2) review the six trades and their invoices, (3) keep the three as the Entity List *Related-party traders*.
- Voice style: container ids spoken as four letters and digits ("kay el en you"); amounts "a little under half a million" to "nearly eight hundred thousand".
- Try it / Check: in the saved view find the four containers with ten shippers each and say why they are not a loop. Answer: a leasing pool lends boxes to many shippers and they never return to a shipper that sent them.

### S01 · The goods keep coming back                                    ~25 s
- Voice: Every bill of lading is in order. The goods are real, the ships sail, the paperwork matches. [pause 0.5] So why do the same six containers keep coming back?
- Chip: none
- Action: cold open and intro card.
- Impact: none.

### S02 · Why each document passes                                    ~30 s
- Voice: Trade-finance teams check documents one at a time, and each one is fine. Nobody checks whether the goods are actually going anywhere. [pause 0.4] That is a question about connections.
- Chip: none
- Action: zoom while the voice runs.
- Impact: none.

### S03 · The data, in one breath                                     ~30 s
- Voice: Every bill of lading lands as a record: the vessel, the container, the shipper, the consignee, the ports and the date. A Job links shippers and consignees through the containers they handled.
- Chip: Mappings
- Action: Data page ▸ the trade links Dataset ▸ Mappings. `[check in UI]`
- Impact: mark the shipper, consignee and container columns.

### S04 · The saved view                                              ~35 s
- Voice: The saved view draws companies and the containers moving between them. One question: does any container come back to a company that sent it?
- Chip: Saved views
- Action: File ▸ Open… ▸ Saved views ▸ *Circular trade* ▸ hold. `[check in UI: labels visible on this view]`
- Impact: none.

### S05 · The busiest boxes, and the decoy                            ~45 s
- Voice: Busiest nodes: containers with ten shippers each. Suspicious? [pause 0.4] They belong to a leasing pool, many shippers, and never back to a company that sent them. Ordinary. Look past them.
- Chip: Busiest nodes
- Action: toolbox ▸ Busiest nodes ▸ click a PWCU row. `[check in UI: the PWCU boxes lead]`
- Impact: the PWCU boxes marked grey "leasing pool, ruled out".

### S06 · The loop                                                    ~40 s
- Voice: Start from one company in the loop, CO-00941. It trades with just two companies, CO-00388 and CO-00213. And it ships six containers. The same six. [pause 0.5] Round all three.
- Chip: Search · Focus
- Action: Search ▸ CO-00941 ▸ Focus ▸ expand one degree.
- Impact: computed mark on the three companies and the six boxes.

### S07 · Round the loop twice                                        ~40 s
- Voice: The same six boxes went twice round three companies, between March and June. [pause 0.4] Follow one container leg by leg. Each time it changes hands, the invoice is higher.
- Chip: Time window
- Action: Time ▸ the window March to June; select one KLNU box. `[check in UI: Time window control on the toolbar]`
- Impact: before/after hold: the six legs appear in order.

### S08 · Every leg is a row                                         ~35 s
- Voice: Open the table. Each leg is a row: shipper, container, consignee, date, value. The values climb from a little under half a million to nearly eight hundred thousand, on the same goods.
- Chip: Rows
- Action: dock ▸ Rows ▸ sort by date.
- Impact: mark the value column.

### S09 · The lead, and what would change it                        ~40 s
- Voice: Three companies and six trades to look at. It is a lead. [pause 0.5] It weakens if vessel positions show a real voyage between them, or if the price rise follows the market. Both can be checked.
- Chip: none
- Action: lower third with the lead and the two checks.
- Impact: loop yellow, leasing pool grey.

### S10 · Monday morning                                             ~35 s
- Voice: Hold new advances to the three companies. Review the six trades. [pause 0.4] Keep the three as an Entity List, so every later graph marks them.
- Chip: Entity List
- Action: New list… `Related-party traders` with the three. `[check in UI]`
- Impact: mark the list name.

### S11 · Case file                                                  ~15 s
- Voice: Lead: three companies, six trades. Evidence: the legs, in order. Next: hold, review, watch. Inspecto LA.
- Chip: none
- Action: case-file card and outro.
- Impact: none.

## Banking_CryptoCashOut

> **Forty names, one hand** · Standard · Banking and finance (`la-banking`) · saved view *Converging cash out*
> (`converging_cash_out`) · about 4½ min

- Cast: the **cluster** CL-01125 (thirty deposit addresses); the **forty exchange accounts** (XA-03119…XA-03158) in forty names; the **three bank accounts** they cash out to (BK-49804167, BK-77665088, BK-95284540). Decoy: the **payment-desk cluster** CL-01126 (sixty accounts, fifty-four banks). Readable ids; the saved view is large and unlabelled until focused.
- Stakes: the investigator works on virtual-asset flows at a bank. Forty small accounts show nothing large; the stakes are in the one hand behind them.
- Vocabulary: *cluster* (addresses controlled by one owner, as grouped by the analytics), *off-ramp* (where crypto turns into bank money). Said once.
- Ground truth: `tools/gen-la-crypto-data.ground-truth.json` ▸ `converging_cash_out`: `cluster` CL-01125, `accounts` 40, `addresses` 30, `banks` three, `exchanges` Fernhill Coin Exchange 22 and Pebblebrook Exchange 18, `persons` 40, `lookalike_cluster` CL-01126 with `lookalike_accounts` 60 and `lookalike_banks` 54; `thresholds` funded_accounts 10, hop_hours 24. **Plan table kept and sharpened:** many accounts, one off-ramp, with a payment desk that looks the same from the chain as the decoy.
- Before: Standard, la-banking, Demo Analyst; the view is dense, so the film opens the cluster by search.
- Lead: *one controller behind forty accounts, and three bank accounts to follow*. **What would change our mind:** the forty accounts turning out to be customers of one licensed desk (the lookalike pattern: many banks, not three). **Next actions:** (1) file the cluster and the three bank accounts with the financial-crime unit, (2) ask both exchanges for the account-opening records, (3) keep the forty as an Entity List and an Alert Rule on new accounts funded from the cluster.
- Voice style: addresses are never read; the voice says "thirty addresses".
- Try it / Check: search CL-01126 and compare it with CL-01125. *What differs?* Answer: it pays sixty accounts that cash out to fifty-four different banks; CL-01125 pays forty that converge on three.

### S01 · Forty small accounts                                        ~25 s
- Voice: Forty exchange accounts. Forty different names. Not one large balance. [pause 0.5] Yet the money behind them arrives from one place and leaves to a handful of bank accounts. Whose hand is behind the forty?
- Chip: none
- Action: cold open and intro card.
- Impact: none.

### S02 · Why small hides it                                        ~30 s
- Voice: Each account passes its checks. Each deposit is small. [pause 0.4] A check that reads one account cannot see that thirty addresses belong to one owner, or that forty accounts empty into three.
- Chip: none
- Action: zoom while the voice runs.
- Impact: none.

### S03 · The data, in one breath                                  ~30 s
- Voice: Every exchange account lands as a record: the account, the exchange, the verified person, the deposit address. A Job links accounts to the addresses that funded them, the clusters those belong to, and the bank accounts they cash out to.
- Chip: Mappings
- Action: Data page ▸ the screening links Dataset ▸ Mappings. `[check in UI]`
- Impact: mark the account, address, cluster columns.

### S04 · The saved view                                           ~35 s
- Voice: The saved view draws the cash-out side of the chain: clusters of addresses, the exchange accounts they paid, and the bank accounts at the end. It is dense. We will not read it all. We will ask it questions.
- Chip: Saved views
- Action: File ▸ Open… ▸ Saved views ▸ *Converging cash out*.
- Impact: none.

### S05 · First sweep, and the desk                              ~45 s
- Voice: Busiest nodes puts one cluster first, CL-01125, with thirty links. A cluster that size is one owner's wallet. [pause 0.4] But hold on. A payment desk paying its customers looks the same from the chain. So check a second cluster, CL-01126, before deciding.
- Chip: Busiest nodes
- Action: toolbox ▸ Busiest nodes ▸ rows ▸ Search ▸ CL-01126 ▸ Focus. `[check in UI: CL-01126 appears in the view and its fan-out]`
- Impact: CL-01126 marked grey "a desk".

### S06 · The desk fans out; the cluster converges              ~40 s
- Voice: The desk pays sixty accounts, and they cash out to fifty-four different banks. A desk's customers go everywhere. [pause 0.5] Now the first cluster. Forty accounts, and every one of them cashes out to one of three bank accounts.
- Chip: Expand · G
- Action: select CL-01125 ▸ Expand (two degrees, outgoing); then the same for CL-01126.
- Impact: before/after hold: CL-01125 closes on three bank nodes; mark them.

### S07 · Thirty ways of sending                                    ~35 s
- Voice: Thirty addresses, all behind CL-01125. Thirty ways of sending. One sender.
- Chip: Display options
- Action: Display options ▸ node labels on; zoom on the cluster.
- Impact: computed mark on the thirty addresses.

### S08 · Every payment is a row                                  ~35 s
- Voice: Open the table. Every payment from the cluster to an exchange account is a row. Twenty-two went to one exchange, eighteen to the other. Each exchange can confirm its own.
- Chip: Rows
- Action: dock ▸ Rows ▸ sort by exchange.
- Impact: mark the exchange column.

### S09 · The lead, and what would change it                      ~40 s
- Voice: One sender paid forty accounts opened in forty names, and each cashes out to one of three bank accounts. That is a lead: one controller. [pause 0.5] It weakens if the forty turn out to be customers of one licensed desk. The desk pattern, many banks, is the opposite.
- Chip: none
- Action: lower third with the lead and the check.
- Impact: lead yellow, desk grey.

### S10 · Monday morning                                          ~35 s
- Voice: File the cluster and the three bank accounts with the financial-crime unit. Ask both exchanges for the account-opening records. [pause 0.4] Keep the forty as an Entity List, and raise an Alert Rule on new accounts funded from the cluster.
- Chip: Entity List · Alert Rule
- Action: New list… `Peel-chain exchange accounts` is the v1 name; use `Converging cash-out accounts` `[check in UI: list names are free text]`.
- Impact: mark the list name.

### S11 · Case file                                              ~15 s
- Voice: Lead: one controller, forty accounts, three banks. Evidence: the payments, by exchange. Next: file, ask, watch. Inspecto LA.
- Chip: none
- Action: case-file card and outro.
- Impact: none.

## Banking_MuleRing

> **The funnel that nobody deposited into** · Standard · Other Examples (`la-showcase`) · saved view *Money-mule layering
> ring* (`mule_layering_ring`) · about 5 min. The v1 film was recorded on Basic; the v2 film is on Standard and its ids are
> masked for the analyst.

- Cast: the **hub** (MULE-HUB-01); the **twelve feeder accounts** (SMURF-01…SMURF-12); the **two relays** (RELAY-01, RELAY-02); the **exit** (OFFSHORE-77). Decoys: the **tangled core** of ordinary customers, and the **busy but ordinary accounts** just behind the hub. All masked for the analyst: said as roles; the film shows a reveal, as the administrator.
- Stakes: the investigator is an anti-money-laundering analyst. Nobody deposits more than a thousand, no alert fires, and over three days a small fortune leaves the country.
- Vocabulary: *layering* (moving money through accounts to hide where it came from), *mule* (an account used to pass money on). Said once.
- Ground truth: `tools/gen-la-showcase-data.py`: `SMURFS` 12, `HUB` MULE-HUB-01, `RELAYS` RELAY-01/02, `OFFSHORE` OFFSHORE-77; each feeder pays the hub in cash eight times just under a thousand (v1: ninety-six deposits); pinned by `LaShowcaseGoldenTest` (MULE-HUB-01 ranks in the top three). **Plan table corrected:** nothing in the data is "a ring that looks like a family"; the decoy is the dense core of ordinary customers and the busy ordinary accounts next to the hub in the first sweep.
- Before: Standard, la-showcase, Demo Analyst for S01–S09, Demo Admin for S10 `[check in UI: the administrator may reveal in this Space, as in F7]`; fresh state; the saved view shows *112 entities masked · Reveal…*.
- Lead: *one hub, twelve feeders, two relays and one exit to report*. **What would change our mind:** if the twelve feeders also receive from the hub (money flowing both ways would look like a payroll, not a funnel). **Next actions:** (1) file the route (feeders, hub, relays, exit) as a report, (2) hold the hub and the relays pending review, (3) keep the feeders as an Entity List.
- Voice style: no alias is read; amounts "just under a thousand"; "ninety-six deposits" once.
- Try it / Check: open the saved view and press *Busiest nodes*. *Why is the hub not just another busy account?* Answer: its links all point in from twelve accounts and out to two; busy accounts around it have links in both directions.

### S01 · An ordinary Tuesday                                         ~25 s
- Voice: Picture a bank on an ordinary Tuesday. Millions of transfers, almost every one boring. That is what a laundering ring counts on. [pause 0.5] Nobody here ever deposits more than a thousand. No payment crosses a line. No alert fires. And still, over three days, a small fortune leaves the country.
- Chip: none
- Action: cold open and intro card.
- Impact: none.

### S02 · How do you find the invisible                              ~25 s
- Voice: You cannot find it one transaction at a time; it was designed so that you could not. [pause 0.5] You stop looking at transactions and start looking at connections.
- Chip: none
- Action: zoom on the toolbox quick questions.
- Impact: none.

### S03 · The data, in one breath                                  ~30 s
- Voice: Every transfer lands as a record: who paid, who was paid, the channel, the amount, the time. Both ends are accounts, so every row is already a link.
- Chip: Mappings
- Action: Data page ▸ the transfers Dataset ▸ Mappings. `[check in UI]`
- Impact: mark the two account columns and the time.

### S04 · The tangle, and the shape that does not belong           ~40 s
- Voice: The saved view draws every account and every payment. A dense core of ordinary customers paying each other. Too much to read, and that is the point. [pause 0.5] Look away from the tangle. In the corner, one account in the middle, a ring around it, every line pointing in. A funnel. The ids are masked for the analyst, so I will say roles.
- Chip: Saved views
- Action: File ▸ Open… ▸ Saved views ▸ *Money-mule layering ring* ▸ hold on *112 entities masked*. `[check in UI: the funnel at the lower left of the view]`
- Impact: mark the tangle grey; ring on the funnel; mark the masked-entities line.

### S05 · First sweep, and the busy accounts                      ~40 s
- Voice: Who has the most links? One press on Busiest nodes. The hub leads, and just behind it a few ordinary accounts, busy, but busy is not suspicious. What matters is what the hub's links look like.
- Chip: Busiest nodes
- Action: toolbox ▸ Busiest nodes ▸ click the first row and the next two.
- Impact: hub marked yellow; the busy ordinary accounts grey.

### S06 · Narrow to the hub                                        ~35 s
- Voice: Start a new graph from the hub. Before it draws anything, the product tells you what it knows: how many links, and when first and last seen. [pause 0.4] One press on Draw.
- Chip: Search · Focus
- Action: Search ▸ the hub (alias from the Busiest nodes row) ▸ Focus. `[check in UI: Focus from a Busiest nodes row]`
- Impact: before/after hold.

### S07 · Twelve accounts, eight deposits each                      ~45 s
- Voice: And there it is. Twelve accounts around the hub. Each pays it in cash again and again, eight times apiece, always just under a thousand. That is why nothing fired. [pause 0.5] And look at the dashed arrows going out. The hub is not keeping the money. It wires it on, to two relays.
- Chip: Display options
- Action: Display options ▸ node labels on (aliases); zoom out to the ring.
- Impact: computed mark on the twelve; ring on the two relays.

### S08 · Every line is a row                                    ~35 s
- Voice: Is this a guess? Open the table under the graph. Every line is a row: from, link kind, to. The hub to one relay, a wire. The hub to the other, a wire. [pause 0.4] The graph shows the pattern. The table backs it up.
- Chip: Rows
- Action: dock ▸ Rows.
- Impact: mark "every line is a row".

### S09 · The route to the exit                                  ~40 s
- Voice: One last question: where does the money end up? Search for OFFSHORE. One box, and one node lights up, at the far end of the same funnel. The relays move the money on by crypto, to one offshore account. [pause 0.5] The whole route is in front of you: twelve small accounts, one hub, two relays, one exit.
- Chip: Search
- Action: Search ▸ OFFSHORE ▸ the node lights. `[check in UI: the exit is searchable under its alias]`
- Impact: computed mark on the exit.

### S10 · A name, with a reason                                  ~35 s
- Voice: When a name is truly needed, one person asks for it, with a reason, and it is recorded. [pause 0.4] The administrator reveals the ids on this screen: the hub, the relays, the exit. Only that person sees them, only on this screen.
- Chip: Reveal…
- Action: sign out ▸ Demo Admin ▸ open the view ▸ Reveal… ▸ Reason `confirm the hub before the accounts are held` ▸ Reveal. `[check in UI: the reveal is allowed in la-showcase for Demo Admin]`
- Impact: before/after hold: aliases become MULE-HUB-01, RELAY-01, RELAY-02; mark the toast.

### S11 · The lead, and what would change it                     ~40 s
- Voice: Ninety-six deposits, each just under a thousand, collected by one hub and passed on within hours. That is a lead, not a verdict. [pause 0.5] It weakens if the twelve also receive from the hub: money in both directions would look like a payroll, not a funnel. It does not.
- Chip: none
- Action: lower third with the lead and the check.
- Impact: lead yellow.

### S12 · Monday morning                                         ~35 s
- Voice: File the route as a report, with the hub, the relays and the exit named. Hold the hub and the relays pending review. [pause 0.4] Keep the twelve feeders as an Entity List, so the next graph marks them.
- Chip: Entity List
- Action: New list… `SMURF accounts` with the twelve. `[check in UI]`
- Impact: mark the list name.

### S13 · Case file                                              ~15 s
- Voice: Lead: one hub, twelve feeders, two relays, one exit. Evidence: the rows behind every link. Next: report, hold, watch. Inspecto LA.
- Chip: none
- Action: case-file card and outro.
- Impact: none.
