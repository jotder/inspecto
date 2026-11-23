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
> (`mule_structuring`) · about 4 min

- Cast: the **till** where a ring's cash comes out (TILL-06); the **eight cash-out accounts** (CASHER-01…CASHER-08); the **second hub** that funds them (MULE-HUB-02). Decoys: the **first ring's hub** (MULE-HUB-01, in the same view), the **benefits payer** (SKIMMER-01), the five **other tills** (TILL-01…TILL-05). All masked for the analyst: said as roles.
- Stakes: the investigator is a cash-handling analyst at a bank with hundreds of tills. A ring is paid out in cash somewhere, and every till looks busy; each week it is not found, the cash is gone.
- Vocabulary: *structuring* (breaking a sum into amounts under a reporting limit), *cash-out* (the withdrawal that turns the money back into cash). Both said once in the cold open.
- Ground truth: `tools/gen-la-showcase-data.py` (the lists `TILLS`, `CASHERS`, `HUB2`, `SKIMMER`; the cash-out loop at "cash-out through TILL-06", line 83; `payer == HUB2 and payee in CASHERS`, line 192; pinned by `LaShowcaseGoldenTest`).
- Screen truth (checked live 2026-11-23, Standard, Demo Analyst): the saved view draws a large tangle, two small fans and a few loose pairs, "112 entities masked", labels off. Busiest nodes lists five by alias: the top one with 100 links (the first ring's hub), then two with 24 links each, then 18. Starting from the second card draws 1 entity and 8 linked accounts: eight accounts all paying into it (the till). Starting from the third card draws 1 entity and 8 linked accounts that it pays out to (the second hub), and **the eight aliases are the same eight**. The product's own words on this Space read *subscribers* and *calls* for the entities and links; the voice says accounts and transfers.
- Before: Standard, la-showcase, Demo Analyst, a fresh unzip; the saved view opens by its address. The ids are masked: the film never reads an alias aloud.
- Lead: *one till, eight accounts, one funder*. **What would change our mind:** if the eight accounts also cashed out at other tills in the same proportions, the till would be the neighbourhood's habit, not the ring's exit. **Next actions:** (1) send the till and the eight accounts to the branch-risk team for a visit, (2) keep the eight as an Entity List and put the hub on a thirty-day hold, (3) watch the till for new accounts.
- Voice style: cold open slow; lexicon exceptions: *till* (not "teller"), "second hub", "casher" said as "cash-out account".
- Try it / Check: on `la-standard.zip`, open *Structuring into one account*, press *Busiest nodes*, and start from the second name. *Why is the first hub not the lead of this film?* Answer: it is the previous story's ring and has no cash-out here; the lead is the till where this ring's money comes out.

### S01 · A Friday at the tills                                          ~25 s
- Voice: A branch manager reads a list of busy tills, and every one of them looks busy. [pause 0.5] Cash was broken into small deposits and passed through accounts, and then it had to turn back into cash somewhere. Every ring needs an exit. [pause 0.6] Which till is this one using?
- Action: cold open over the Space's home page.
- Impact: none.

### S02 · Why nothing fired                                             ~30 s
- Beat: steady
- Intro: The exit, not the entry | Other Examples Space
- Story: the first hub = last story's ring | the second hub = funds eight accounts | a till = where cash comes out
- Voice: Each deposit was under the reporting limit, and each withdrawal was an ordinary amount at an ordinary till. [pause 0.4] A check that reads one record at a time passes every one of them. The pattern only exists between the records: which accounts paid out, and where.
- Action: intro card, then the story card "Who is who"; the saved view opens behind it.
- Impact: the story card names the roles.

### S03 · The data, in one breath                                       ~28 s
- Voice: Every transfer lands as a record: who paid, who was paid, the amount and the time. Both ends are accounts, so every row is already a link. [pause 0.4] A cash-out is a link from an account to a till.
- Chip: Data
- Action: rail Data ▸ hold on the list of Datasets.
- Impact: none.

### S04 · The saved view                                                ~36 s
- Voice: The saved view keeps the accounts and the cash-outs. Each circle is an account or a till, each line a transfer or a withdrawal. [pause 0.4] The first ring from the earlier film is still in here. The ids on this screen are masked for the analyst, so I will say what each thing is, not what it is called.
- Chip: Saved views
- Action: open the saved view *Structuring into one account* ▸ hold on the bottom line *112 entities masked · Reveal…*.
- Impact: mark the masked-entities line.

### S05 · First sweep, and the decoy                                    ~42 s
- Voice: Who has the most links? One press on Busiest nodes. The first hub leads, as before, with a hundred. [pause 0.4] That is the previous story, so set it aside. But two others stand out with twenty-four links each: a till, and a second hub.
- Chip: Busiest nodes
- Action: Busiest nodes ▸ hold on the first three cards (100, 24, 24 links).
- Impact: grey "ruled out" mark on the first card; yellow on the second and third.

### S06 · Narrow to the till                                            ~38 s
- Story: off
- Voice: Start a new graph from the second name, the till. The graph draws it with eight accounts around it, and every one of them pays into it. [pause 0.5] That is every customer this till has: eight, and nobody else.
- Chip: Start from these
- Action: Busiest nodes ▸ the second card *Start from these* ▸ hold.
- Impact: before/after hold: the till with eight accounts appears.

### S07 · Where the money came from                                     ~36 s
- Voice: Now start from the third name, the second hub. It draws eight accounts too, and they are the same eight. [pause 0.5] The same hub funds them all. A busy till serves many people, a little each. This one serves one ring.
- Chip: Start from these
- Action: Busiest nodes (a fresh view) ▸ the third card *Start from these* ▸ hold on the eight.
- Impact: before/after hold: the second hub with the same eight accounts.

### S08 · Every line is a row                                           ~32 s
- Voice: Is this a guess? Open the table under the graph. Every line is a row: which account, which link, which account. Each one can be read, sorted and exported as a file. [pause 0.4] The graph shows the pattern, and the table backs it up.
- Chip: Rows
- Action: dock ▸ Rows ▸ hold.
- Impact: before/after hold: the dock opens.

### S09 · The lead, and what would change it                           ~38 s
- Voice: The till's eight accounts are all funded by one hub. [pause 0.6] That is a lead, not a verdict. It would weaken if those accounts also cashed out elsewhere in the same way, so the next step is a visit, not a charge.
- Action: hold on the graph.
- Impact: none.

### S10 · Monday morning                                                ~36 s
- Voice: Here is what the team does with it. Send the till and the eight accounts to the branch-risk team. Keep the eight as an Entity List, with the hub on a thirty-day hold. [pause 0.4] And watch the till for new accounts, because every graph in this Space marks that list from now on.
- Chip: Entity Lists
- Action: rail Entity Lists ▸ hold on the lists of this Space; create nothing.
- Impact: none.

### S11 · The case file                                                 ~28 s
- Casefile: Lead: one till and the ring behind it | Evidence: the till's eight accounts, all funded by one second hub, every link a row | Next: send the till and the eight accounts for a visit; keep the eight as an Entity List with the hub on hold; watch the till for new accounts | Try it: press Busiest nodes and start from the second name
- Voice: The case file. Lead: one till and the ring behind it. Evidence: eight accounts, all funded by one hub, every link a row. Next step: visit, hold, watch. [pause 0.6] Each deposit was legal; only the links show where the cash came out. The same steps run on millions of records and answer in moments. Try it: start from the second name in Busiest nodes.
- Action: case-file card (8 s) and the outro "Next: Make it defensible".
- Impact: none.

## Banking_SanctionedOwner

> **The better name match is the wrong one** · Standard · Banking and finance (`la-banking`) · saved view *Sanctioned owner
> path* (`sanctioned_owner_path`) and the Investigation *Sanctions exposure two ownership hops down* · about 5 min

- **Cast:** the **list entry** SL-0137; the **matched person** PER-03904; the **company** the person owns 60 % of, CO-04431; the
  **company behind the customer**, CO-04313; the **customer** CUS-00528. Decoys: the **large company** (CO-00859, 135 links, the
  busiest node of the view) and the **near namesake** (list entry SL-0289, match score 0.987, a better score than the real one,
  with no stake in any customer's chain). All ids are readable (no masking in this Space).
- **Stakes:** the investigator is a bank's sanctions-compliance analyst. Screening found nothing at the customer; if the match is
  real and missed, the bank is exposed through a customer it believes is clean.
- **Vocabulary:** *sanctions list* (names a bank may not deal with), *beneficial owner* (the person behind the stakes), *two hops*
  (two ownership links away). Said in S02.
- **Ground truth:** `tools/gen-la-ownership-data.ground-truth.json` ▸ `stories.sanctions_two_hops`: entities CO-04313, CO-04431,
  CUS-00528, PER-03904, SL-0137; `facts` listed_share_of_owner 60, owner_share 50, match_score 0.986, near_namesake_list_id
  SL-0289 with score 0.987. **Plan table corrected:** the decoy is a *better* score, not a near-match name. **Checked live
  2026-11-22 (fresh Standard unzip):** the saved view is cut short at the node cap (1000), Busiest nodes names CO-00859 (135 links),
  CO-01142 (56), CO-00240 (47); the Investigation opens with PER-03904, SL-0137, CUS-00528 and CO-04313 (a name-match link
  PER-03904 to SL-0137, a customer link CUS-00528 to CO-04313), Working Set 4 entities, 2 links; Connect start entities
  (PER-03904, CUS-00528) answers *3 hops · 1 route* through CO-04431; Expand from these nodes draws the chain; Rows lists
  CO-04431 owns CO-04313, CUS-00528 customer_is CO-04313, PER-03904 owns CO-04431, PER-03904 name_match SL-0137; the Data page
  shows the Dataset *ownership_dataset* (owner, owned, percent) with its own mapping; the Space already holds the Entity List
  *Parties on a sanctions path* (Party, 4 members).
- **Before:** Standard build, Banking Space from a fresh unzip, Demo Analyst. A single entity cannot be started unsaved here
  (the start's ring needs a second person), so the film reads the seeded Investigation and does its one state change, Connect
  and Expand, in S08.
- **Lead:** *customer CUS-00528 is two ownership hops below a possible match.* **What would change our mind:** the birth year or
  nationality in the match reasons differing from the list entry, or the 60 % stake having been sold before the customer was
  onboarded. **Next actions:** (1) escalate CUS-00528 for enhanced due diligence, (2) ask the customer for a certified ownership
  chain, (3) keep the parties as an Entity List so every later graph marks the chain.
- **Voice style:** ids spoken by the lexicon (PER, CO, CUS, SL as letters then digits); percentages as "sixty percent".
- **Try it / Check:** open the saved view, run Busiest nodes, and say why the largest company is not the lead. *Answer: its size is
  not a path to any listed person; the lead is the chain from the list entry, which the match reasons and the stakes show.*

### S01 · Cold open · ~25 s
- Voice: A bank screens every customer against the sanctions lists. This customer is clean. The company that owns it is clean too, and screening found nothing. [pause 0.6] But a name on a sanctions list is spelled almost like a person two layers up the ownership chain. Screening looks at the customer in front of it. Who looks two hops up?
- Action: cold open over the Banking Space's home page; the intro card opens the next scene.
- Impact: none.

### S02 · Why screening misses it · ~30 s
- Beat: steady
- Intro: The better name match is the wrong one | Banking and finance Space
- Story: a sanctions list = the names a bank may not deal with | a beneficial owner = the person behind the stakes | two hops = two ownership links away
- Voice: Screening compares names with the customer in front of it. The stake sits in another record, the owner in a third, and the list in a fourth. [pause 0.4] No single row says it. Only the chain does. A sanctions list names the people a bank may not deal with. A beneficial owner is the person behind the stakes. Two hops means two ownership links away.
- Action: story card "Who is who" over the home page.
- Impact: the story card names the three terms.

### S03 · The data, in one breath · ~30 s
- Voice: The ownership register lands as records: an owner, what is owned, and a percentage. Beside it sit the bank's customers and the sanctions list. [pause 0.4] A Job turns each register row into an owns link. Where a listed name is close to a person's, it adds a name-match link. The percentage rides on every owns link.
- Chip: Data · Mappings
- Action: rail Data ▸ the Dataset *ownership_dataset* ▸ hold on its Columns card and its Mappings line. `[checked live: owner_id and owned_id are Entity columns holding Party and Company, percent is a Measure, the mapping reads owner_id to owned_id, measure percent]`
- Impact: mark the Mappings line.

### S04 · The saved view, and its limit · ~35 s
- Voice: The saved view draws people, companies and customers, and who owns whom. It is large, and the product says so. [pause 0.4] The orange line reads: result cut short, node cap one thousand. That is honest. It is also the cue to stop looking at the whole picture and follow one chain.
- Chip: Saved views · Graph counts
- Action: open the saved view *Sanctioned owner path* ▸ hold on the banner. `[checked live: the banner reads Result cut short, the graph reached the node cap (1000)]`
- Impact: mark the banner.

### S05 · First sweep, and the big company · ~35 s
- Voice: Who has the most links? [pause 0.4] The busiest node is a company with a hundred and thirty-five stakes. It is large, and nothing more. No listed name leads to it. Size is not the question here. Set it aside.
- Chip: Busiest nodes
- Action: toolbox ▸ Busiest nodes ▸ hold on the first row. `[checked live: CO-00859 takes part in 135 links, then CO-01142 with 56 and CO-00240 with 47]`
- Impact: grey "ruled out" mark on the first row.

### S06 · The matched person · ~40 s
- Beat: reveal
- Story: off
- Voice: Now open the Investigation, Sanctions exposure two ownership hops down. It starts from two entities, the list entry and the customer. [pause 0.5] The match is already on the graph. One link joins a person to the list entry: two spellings of one name, the same birth year, the same nationality. That is the possible match. The Working Set holds four entities and two links, and the data runs to the end of September.
- Chip: File · Open… · Investigations
- Action: File ▸ Open… ▸ Investigations ▸ *Sanctions exposure two ownership hops down* ▸ Open ▸ hold on the canvas. `[checked live: the canvas holds PER-03904, SL-0137, CUS-00528 and CO-04313; the blue link joins PER-03904 to SL-0137]`
- Impact: mark the name-match link.

### S07 · The better name, and why it is not the one · ~40 s
- Voice: Here is the trap. A second list entry matches an even closer name, with a higher score. [pause 0.4] Follow it, and it ends. That person owns nothing in any customer's chain. A better score is not a better lead. The stake is what counts.
- Action: stay on the Investigation canvas while the voice runs. The namesake is not in this Working Set, so this scene is narration over the canvas; the page for it is not shown.
- Impact: none.

### S08 · Two hops to the customer · ~55 s
- Voice: The person owns a company that is not on the graph yet. So ask how the person and the customer connect. [pause 0.5] Connect start entities answers: three hops, one route. It reads: person, company, company, customer. The route runs through the missing company. Connect is a recorded step, so the log now says what was asked and what came back. Expand from these nodes, and the chain draws end to end. The person owns sixty percent of one company, which owns half of the customer's company. [pause 0.5] Two hops above a clean customer sits a possible match.
- Chip: More steps · Connect start entities · Expand from these nodes
- Action: More steps ▸ Connect start entities ▸ paste `PER-03904, CUS-00528` ▸ Connect start entities ▸ Expand from these nodes. This is the film's one recorded step. `[checked live: 1 of 1 pairs connected within 3 hops, route PER-03904 to CO-04431 to CO-04313 to CUS-00528, the button reads Expand from these nodes (4)]`
- Impact: before/after hold: CO-04431 appears between the person and the customer's company; mark it.

### S09 · Evidence you can show · ~35 s
- Voice: Every link is a row. Open the table under the graph. [pause 0.4] Who owns what, and the kind of link: owns, customer is, name match. The percentage is on each owns link. This is what you hand to the reviewer, and it comes straight from the register.
- Chip: Rows
- Action: dock ▸ Rows ▸ hold on the rows. `[checked live: CO-04431 owns CO-04313, CUS-00528 customer_is CO-04313, PER-03904 owns CO-04431, PER-03904 name_match SL-0137; the percentage is not a column of this table]`
- Impact: mark the rows.

### S10 · The lead, and what would change it · ~40 s
- Voice: One customer to escalate, never a verdict. [pause 0.5] It weakens if the birth year or the nationality differs in the match reasons. It weakens too if the sixty percent stake was sold before the customer joined. [pause 0.4] The register can show either.
- Action: hold on the drawn chain.
- Impact: none.

### S11 · Monday morning · ~35 s
- Voice: Escalate the customer for enhanced due diligence. Ask for a certified ownership chain. [pause 0.4] And keep the parties as an Entity List, so every later graph marks the chain. This Space already holds one, Parties on a sanctions path.
- Chip: Entity Lists
- Action: rail Entity Lists ▸ hold on the list *Parties on a sanctions path*. `[checked live: Party, 4 members]`
- Impact: mark the list row.

### S12 · Case file · ~25 s
- Casefile: Lead: customer CUS-00528, two hops below a possible match | Evidence: the register rows and the match reasons | Next: escalate for enhanced due diligence; ask for a certified ownership chain; keep the parties as an Entity List | Try it: open the saved view, run Busiest nodes, and say why the largest company is not the lead
- Voice: The case file. The lead: one customer, two ownership hops below a possible match. The evidence: the register rows and the match reasons. Next: escalate, ask, and keep watching. [pause 0.6] Your turn. Open the saved view, run Busiest nodes, and say why the largest company is not the lead.
- Action: case-file card and outro.
- Impact: none.

## Banking_CardCompromise

> **One fuel station, sixty cards** · Standard · Banking and finance (`la-banking`) · saved view *Common point of purchase*
> (`common_point_of_purchase`) · about 4½ min

- **Cast:** the **point of compromise** (merchant M-317, a fuel station); the **sixty defrauded cards** (CRD-…); the decoy
  **supermarket** M-052 (the lookalike: far more cards, none defrauded). All ids readable.
- **Stakes:** the investigator is a card-fraud analyst. Sixty cards were defrauded weeks apart at different shops; every week
  without the source, more cards are copied.
- **Vocabulary:** *common point of purchase* (the one place the defrauded cards had all been used before the fraud), *point of
  compromise*. Said in S01 and S02.
- **Ground truth:** `tools/gen-la-card-data.ground-truth.json` ▸ `stories.common_point_of_purchase` (hub M-317, lookalikes M-052)
  and ▸ `common_point_of_purchase` (`cards` 60 with `fraud_day`; `window` 2026-03-02 to 2026-03-22; `background_fraud_cards` 40
  cards defrauded with no link to M-317; `in_window_not_defrauded` 10 cards that paid at M-317 in the window and were not
  defrauded). **Plan table kept.** **Checked live 2026-11-22 (fresh Standard unzip):** the saved view draws with the caption
  *node labels off above 80 nodes*; Busiest nodes names M-317 (60 links, the most on the graph) and then three cards and a
  merchant with 4 links each; the Dataset *card_links_dataset* has link kinds used_at, refunded, used_device, from_ip, account_card,
  ships_to, charged_back and visited_before_fraud; the Entities page opens M-052 as a Merchant whose profile reads *Active days 365
  of 365*, Degree 928, *Among its type p100*; *Start from these* on M-317 draws the station with its cards around it; Rows lists
  CRD-100012 used_at M-317, CRD-100012 visited_before_fraud M-317, CRD-100017 visited_before_fraud M-317; the Space already holds
  the Entity List *Cards to reissue* (Card entity, 10 members).
- **Before:** Standard build, Banking Space from a fresh unzip, Demo Analyst. The film changes no state: it reads the saved view,
  the Entities page and the Entity Lists page.
- **Lead:** *the fuel station M-317 is where to look.* **What would change our mind:** if the sixty also share a second merchant,
  or if any card's fraud date fell before it paid at the station. **Next actions:** (1) send the terminal for inspection, (2)
  reissue the sixty cards and keep them as an Entity List, (3) watch the ten cards that also paid there and were not defrauded.
- **Voice style:** card ids spoken by the lexicon (CRD letters, then digits); dates as "the middle of March".
- **Try it / Check:** from M-317, expand once and count what its cards have in common. *Why is the supermarket not the lead?
  Answer: it served far more cards and none was defrauded; only the links to the frauds point at the station.*

### S01 · Sixty cards · ~25 s
- Voice: Sixty cards were defrauded online, weeks apart, at different shops. The victims share nothing, except that each card was copied somewhere, once, before the fraud. [pause 0.5] Where? The frauds themselves will not tell you.
- Action: cold open over the Banking Space's home page; the intro card opens the next scene.
- Impact: none.

### S02 · Why the frauds hide it · ~30 s
- Beat: steady
- Intro: One fuel station, sixty cards | Banking and finance Space
- Story: a common point of purchase = the one place the defrauded cards had all been used | the point of compromise = where the cards were copied | a defrauded card = a card with a confirmed fraud
- Voice: Look at the frauds and you see sixty shops on sixty different days. [pause 0.4] The place the cards share is earlier, and it is an ordinary purchase. The answer is in what came before. A common point of purchase is the one place all the defrauded cards had been used. The point of compromise is where they were copied.
- Action: story card "Who is who" over the home page.
- Impact: the story card names the three terms.

### S03 · The data, in one breath · ~30 s
- Voice: Every card authorisation lands as a record: the card, the merchant, the amount, the time, and the result. [pause 0.4] A Job turns the records into links between a card and the merchants it paid at. It adds a link from a card to a merchant where the card had visited before the fraud.
- Chip: Data · Columns
- Action: rail Data ▸ the Dataset *card_links_dataset* ▸ hold on its Columns card. `[checked live: src and dst are Entity columns holding Card entity, kind is a Category, amount a Measure, ts and event_date are Time, via a Detail; the description lists the link kinds]`
- Impact: mark the Columns card.

### S04 · The saved view · ~30 s
- Voice: The saved view keeps the merchants where defrauded cards had paid before the fraud. Each circle is a card or a merchant, each line a purchase. [pause 0.4] It is a lot of shapes, so the labels are off. Turn them on only where you need them.
- Chip: Saved views · Display options
- Action: open the saved view *Common point of purchase* ▸ hold on the line at the bottom. `[checked live: the line reads node labels off above 80 nodes: Display options turns them on]`
- Impact: mark the line.

### S05 · The busy merchant · ~40 s
- Voice: Who is the busiest merchant? A supermarket, M-052. [pause 0.4] Its profile says it all. Open its profile: first seen a year ago, last seen at the end of September, and active on every single day in between. Under Links, it sits at the very top of all merchants. [pause 0.4] A supermarket should look like that. If busy meant guilty, this would be the lead. [pause 0.5] It is not. Cards plenty, defrauded cards none. Set it aside.
- Chip: Entities · Open an Entity
- Action: rail Entities ▸ Entity Types ▸ Merchant ▸ Open an Entity ▸ `M-052` ▸ hold on Seen and Links. `[checked live: Active days 365 of 365, Degree 928, Among its type p100]`
- Impact: grey "ruled out" mark on the Seen card.

### S06 · The one with sixty · ~35 s
- Voice: Back in the saved view, ask Busiest nodes again. [pause 0.4] The first name is not the supermarket. It is one merchant, M-317, that takes part in sixty links. The names after it have four each. [pause 0.5] That is not a close race, and the supermarket never appears here: its cards were never defrauded, so they are not in this view.
- Chip: Busiest nodes
- Action: open the saved view ▸ Busiest nodes ▸ hold on the first row. `[checked live: M-317 takes part in 60 links, the most on this graph; the next rows are CRD-101227, CRD-104942 and M-832 with 4 links each]`
- Impact: computed mark on the first row, yellow.

### S07 · Start from the station · ~40 s
- Voice: Start from M-317. A fuel station. [pause 0.4] The notice reads: started from the busiest node, one entity and its linked nodes. Every spoke is a card that paid at its pumps.
- Chip: Start from these
- Action: Busiest nodes ▸ first row ▸ *Start from these*. `[checked live: the notice reads Started from M-317, the busiest node. Drew 1 entity and 120 linked nodes]`
- Impact: before/after hold: the single drawing of M-317 with its cards.

### S08 · Every line is a row · ~40 s
- Voice: Open the table. Each line is a row: the card, the kind of link, and the merchant. Each one can be read, sorted and quoted. [pause 0.4] Two kinds matter here. A card used at M-317, and a card that had visited M-317 before its fraud. [pause 0.5] That is the order a copying device gives you: first the pumps, then the fraud.
- Chip: Rows
- Action: dock ▸ Rows ▸ hold on the first rows. `[checked live: CRD-100012 used_at M-317, CRD-100012 visited_before_fraud M-317, CRD-100017 visited_before_fraud M-317]`
- Impact: mark the link kinds.

### S09 · The lead, and the honest caveat · ~40 s
- Voice: The lead is a terminal to inspect. [pause 0.5] One honest caveat: ten more cards paid there in those weeks and were not defrauded. The station is where to look, not proof. [pause 0.4] It would weaken if the sixty also share a second merchant, or if a fraud came before its card paid at the station.
- Action: hold on the rows while the voice runs.
- Impact: none.

### S10 · Monday morning · ~35 s
- Voice: Send the terminal for inspection. Reissue the sixty cards. [pause 0.4] And watch the ten that were exposed and are not defrauded yet. This Space already keeps a list for the reissue, Cards to reissue, so every graph marks them.
- Chip: Entity Lists
- Action: rail Entity Lists ▸ hold on *Cards to reissue*. `[checked live: Watch these, Card entity, 10 members]`
- Impact: mark the list row.

### S11 · Case file · ~25 s
- Casefile: Lead: one fuel station, M-317 | Evidence: sixty cards used there, each defrauded later | Next: send the terminal for inspection; reissue the cards and keep them as an Entity List; watch the ten cards that were exposed and not defrauded | Try it: from M-317, expand once and count what its cards have in common
- Voice: The case file. The lead: one fuel station. The evidence: its rows, in order. Next: inspect, reissue, watch. [pause 0.6] Your turn. From the station, expand once, and count what its cards have in common.
- Action: case-file card and outro.
- Impact: none.

## Banking_CircularTrade

> **The same six containers** · Standard · Banking and finance (`la-banking`) · saved view *Circular trade*
> (`circular_trade`) · about 4½ min

- **Cast:** the **loop** CO-00941 → CO-00388 → CO-00213; the **six containers** (KLNU…); the decoy **leasing-pool containers**
  (PWCU…: ten shippers each, never back to a shipper). All ids readable; both groups are drawn and labelled in the view.
- **Stakes:** the investigator is a trade-finance analyst. Every bill of lading is in order; if the goods never go anywhere, the
  bank financed paper.
- **Vocabulary:** *bill of lading* (the receipt for goods on a ship), *consignee* (who receives them), *leg* (one shipment). Said
  once, in S02.
- **Ground truth:** `tools/gen-la-trade-data.ground-truth.json` ▸ `stories.circular_trade`: `cycle` CO-00941, CO-00388, CO-00213;
  `containers` KLNU7238600, KLNU0722138, KLNU9605085, KLNU6490823, KLNU5682691, KLNU7946736; `legs` 6; `leg_values` from 450,681 to
  788,128; `first_ship_date` 2026-03-18, `last_ship_date` 2026-06-27; `leasing_boxes` and `lookalikes` PWCU0827459, PWCU9046170,
  PWCU3999100, PWCU2508727, PWCU0444452, PWCU6477031. **Plan table kept.** **Checked live 2026-11-23 (fresh Standard unzip):** the
  Dataset behind the Data page is *bill_of_lading_dataset* (one row per bill of lading; Entity columns trade_id, vessel,
  container, shipper, consignee; ports are Details; Time column bl_date; *Keep on each link* Yes; 0 Mappings); the saved view draws
  five hub-and-spoke clusters (the PWCU boxes) and one small ring at the bottom with the six KLNU boxes and CO-00941, CO-00388,
  CO-00213 labelled; Busiest nodes lists five boxes at 10 links each (PWCU0444452, PWCU0827459, PWCU2508727, PWCU3999100,
  PWCU6477031); Search nodes dims the rest and picks out CO-00941; the Rows table lists From, Link kind (traded_with, shipped_in)
  and To with a Count; it carries no date or value column; the time control (*Filter links by time*) cuts the graph's year into twenty
  slices (2025-10-02 to 2026-09-29) with a play button; the Entity Lists page already holds *Related-party traders* (Watch these,
  Trade entity).
- **Before:** Standard, la-banking, Demo Analyst; the saved view fits the screen with ids readable. The film changes no state.
- **Lead:** *three companies and six trades to review.* **What would change our mind:** if the containers' position reports show real
  voyages between the three companies, or the price rise matches a market move. **Next actions:** (1) ask trade finance to hold new
  advances to the three companies, (2) review the six trades and their invoices, (3) keep the three marked through the Entity List
  *Related-party traders*, which the Space already holds.
- **Voice style:** container ids spoken as four letters and digits; amounts "a little under half a million" to "nearly eight hundred
  thousand". The invoice values are in the trade documents and are narrated, not shown: the saved view's rows carry no value.
- **Try it / Check:** in the saved view find the boxes that appear with ten shippers each and say why they are not a loop. *Answer:
  a leasing pool lends boxes to many shippers and they never return to a shipper that sent them.*

### S01 · The goods keep coming back · ~25 s
- Voice: Every bill of lading is in order. The goods are real, the ships sail, the paperwork matches. [pause 0.5] So why do the same six containers keep coming back, round the same three companies?
- Action: cold open over the Banking Space's home page; the intro card opens the next scene.
- Impact: none.

### S02 · Why each document passes · ~30 s
- Beat: steady
- Intro: The same six containers | Banking and finance Space
- Story: a bill of lading = the receipt for goods on a ship | a consignee = the company that receives them | a leg = one shipment of one container
- Voice: Trade-finance teams check documents one at a time, and each one passes. A bill of lading is the receipt for goods on a ship. The consignee is who receives them, and one shipment of one container is a leg. [pause 0.4] Nobody checks whether the goods ever go anywhere. That is a question about connections.
- Action: story card "Who is who" over the home page.
- Impact: the story card names the three terms.

### S03 · The data, in one breath · ~30 s
- Voice: Every bill of lading lands as a record. It names the vessel, the container, the shipper, the consignee, two ports and the date. [pause 0.4] Shipper and consignee are companies, so each record becomes a link between two companies. The container rides along on that link.
- Chip: Data · Columns
- Action: rail Data ▸ the Dataset *bill_of_lading_dataset* ▸ hold on its Columns card. `[checked live: shipper, consignee, container, vessel and trade_id are Entity columns, bl_date is the Time, the ports are Details; Keep on each link is Yes]`
- Impact: mark the Columns card.

### S04 · The saved view · ~35 s
- Story: off
- Voice: The saved view draws the companies and the containers moving between them. Each shape is a company or a container, and each line is a shipment. [pause 0.4] The network falls into clusters. Most look like a hub with spokes. One small cluster, low on the screen, is different. Its lines run in a ring.
- Chip: Saved views
- Action: open the saved view *Circular trade* ▸ hold. `[checked live: five hub-and-spoke clusters and one small ring at the bottom, ids readable]`
- Impact: none.

### S05 · The busiest boxes, and the decoy · ~45 s
- Voice: Ask the first question: who is the busiest? Busiest nodes names five containers, each with ten links. [pause 0.4] Ten links means ten different shippers. These boxes belong to a leasing pool. A pool lends the same box to many shippers, and the box never goes back to one that sent it. That is ordinary. Look past them.
- Chip: Busiest nodes
- Action: toolbox ▸ Busiest nodes ▸ hold on the list. `[checked live: PWCU0444452, PWCU0827459, PWCU2508727, PWCU3999100 and PWCU6477031, 10 links each]`
- Impact: the hub-and-spoke clusters marked grey "ruled out: leasing pool".

### S06 · The loop · ~40 s
- Voice: Now look at the ring. Search for CO-00941. The graph dims everything else and picks out that company. [pause 0.4] It trades with just two others, CO-00388 and CO-00213. And it ships six containers. The same six, all the way round.
- Chip: Search nodes
- Action: toolbar Search ▸ type `CO-00941` ▸ Enter ▸ hold. `[checked live: the search dims the rest and highlights CO-00941 with its label]`
- Impact: the ring marked yellow.

### S07 · Round the loop twice · ~40 s
- Voice: The same six boxes went twice round the three companies, between March and June. Open the time control. It cuts the year into twenty steps. [pause 0.4] Play it, and the shipments appear in the order they happened.
- Chip: Filter links by time
- Action: toolbar clock ▸ Play ▸ hold. `[checked live: the control opens with a slice slider, a Play button and the line "20 slices from 2025-10-02 to 2026-09-29"; confirm that Play lights the ring's legs in turn]`
- Impact: before/after hold: the legs appear as the slices advance.

### S08 · Every leg is a row · ~35 s
- Voice: Open the table. Each line is a row: who ships, the kind of link, and to whom. The ring is in there. CO-00941 trades with CO-00388, and the containers go on from one company to the next. [pause 0.4] The invoices behind those legs rise from a little under half a million to nearly eight hundred thousand, on the same goods.
- Chip: Rows
- Action: dock ▸ Rows ▸ search the table for CO-00941 ▸ hold. `[checked live: columns From, Link kind (traded_with, shipped_in), To, Count; the rows carry no value: the invoice figures are narrated]`
- Impact: mark the first rows.

### S09 · The lead, and what would change it · ~40 s
- Voice: Three companies and six trades to look at. It is a lead. [pause 0.5] It weakens if vessel positions show a real voyage between them, or if the price rise follows the market. Both can be checked.
- Action: close the dock; hold on the ring while the voice runs.
- Impact: ring yellow, leasing pool grey.

### S10 · Monday morning · ~35 s
- Voice: Hold new advances to the three companies. Review the six trades. [pause 0.4] This Space already keeps the three as an Entity List, Related-party traders, so every graph marks them. A new investigation can start from it.
- Chip: Entity Lists
- Action: rail Entity Lists ▸ hold on *Related-party traders*. `[checked live: Watch these, Trade entity]`
- Impact: mark the list row.

### S11 · Case file · ~25 s
- Casefile: Lead: three companies, six trades | Evidence: the legs in the table, round the loop twice | Next: hold new advances to the three; review the six trades; keep the three marked through the Entity List | Try it: find the boxes with ten shippers each and say why they are not a loop
- Voice: The case file. The lead: three companies, six trades. The evidence: the legs, in order. Next: hold, review, watch. [pause 0.6] Your turn. Find the boxes with ten shippers each, and say why they are not a loop.
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
> ring* (`mule_layering_ring`) · about 4 min. The v1 film was recorded on Basic; the v2 film is on Standard and its ids are
> masked for the analyst.

- Cast: the **hub** (MULE-HUB-01); the **twelve feeder accounts** (SMURF-01…SMURF-12); the **two relays** (RELAY-01, RELAY-02); the **exit** (OFFSHORE-77). Decoys: the **tangled core** of ordinary customers, and the **busy but ordinary accounts** just behind the hub. All masked for the analyst: said as roles.
- Stakes: the investigator is an anti-money-laundering analyst. Nobody deposits more than a thousand, no alert fires, and over three days a small fortune leaves the country.
- Vocabulary: *layering* (moving money through accounts to hide where it came from), *mule* (an account used to pass money on). Said once.
- Ground truth: `tools/gen-la-showcase-data.py`: `SMURFS` 12, `HUB` MULE-HUB-01, `RELAYS` RELAY-01/02, `OFFSHORE` OFFSHORE-77; each feeder pays the hub in cash eight times just under a thousand (ninety-six deposits); pinned by `LaShowcaseGoldenTest` (MULE-HUB-01 ranks in the top three). **Plan table corrected:** nothing in the data is "a ring that looks like a family"; the decoy is the dense core of ordinary customers.
- Screen truth (checked live 2026-11-23, Standard, Demo Analyst): the saved view draws a dense tangle of coloured communities with a few small groups, "112 entities masked", labels off. Busiest nodes lists five by alias: the top one with 14 links (the hub), then 11, 10, 10 and 9. Starting from the top card draws 1 entity and 14 linked accounts: a wheel of twelve feeders pointing in with two dashed arrows going out (the relays). The product's own words on this Space read *subscribers* and *calls*; the voice says accounts and transfers. The reveal needs an Investigation led by the administrator, so it is narrated over the greyed Reveal… button, not performed.
- Before: Standard, la-showcase, Demo Analyst; fresh state; the saved view opens by its address.
- Lead: *one hub, twelve feeders, two relays and one exit to report*. **What would change our mind:** if the twelve feeders also receive from the hub (money flowing both ways would look like a payroll, not a funnel). **Next actions:** (1) file the route (feeders, hub, relays, exit) as a report, (2) hold the hub and the relays pending review, (3) keep the feeders as an Entity List.
- Voice style: no alias is read; amounts "just under a thousand"; "ninety-six deposits" once.
- Try it / Check: open the saved view and press *Busiest nodes*. *Why is the hub not just another busy account?* Answer: its links all point in from twelve accounts and out to two; busy accounts around it have links in both directions.

### S01 · An ordinary Tuesday                                         ~25 s
- Voice: Picture a bank on an ordinary Tuesday: millions of transfers, almost every one boring. That is what a laundering ring counts on. [pause 0.5] Nobody here ever deposits more than a thousand, no payment crosses a line, and no alert fires. And still, over three days, a small fortune leaves the country.
- Action: cold open over the Space's home page.
- Impact: none.

### S02 · How do you find the invisible                              ~25 s
- Beat: steady
- Intro: The funnel nobody deposited into | Other Examples Space
- Story: the hub = collects the deposits | a feeder = pays in just under a thousand | a relay = passes the money on
- Voice: You cannot find it one transaction at a time, because it was designed so that you could not. [pause 0.5] You stop looking at transactions and start looking at connections.
- Action: intro card, then the story card "Who is who"; the saved view opens behind it.
- Impact: the story card names the roles.

### S03 · The data, in one breath                                  ~26 s
- Voice: Every transfer lands as a record: who paid, who was paid, the channel, the amount and the time. Both ends are accounts, so every row is already a link.
- Chip: Data
- Action: rail Data ▸ hold on the list of Datasets.
- Impact: none.

### S04 · The tangle, and the shape that does not belong           ~36 s
- Voice: The saved view draws every account and every payment: a dense core of ordinary customers paying each other. Too much to read, and that is the point. [pause 0.5] Look away from the tangle. In a corner, one account in the middle, a ring around it, every line pointing in. A funnel. The ids are masked for the analyst, so I will say roles.
- Chip: Saved views
- Action: open the saved view *Money-mule layering ring* ▸ hold on the tangle, then *112 entities masked*.
- Impact: mark the masked-entities line.

### S05 · First sweep, and the busy accounts                      ~36 s
- Voice: Who has the most links? One press on Busiest nodes. The hub leads with fourteen, and just behind it a few ordinary accounts with ten or eleven: busy, but busy is not suspicious. [pause 0.4] What matters is what the hub's links look like.
- Chip: Busiest nodes
- Action: Busiest nodes ▸ hold on the first three cards (14, 11, 10 links).
- Impact: yellow mark on the first card; grey "ruled out" on the second and third.

### S06 · Narrow to the hub                                        ~32 s
- Story: off
- Voice: Start a new graph from the hub. Before it draws anything, the product tells you what it knows. [pause 0.4] One press, and it draws the hub with fourteen accounts around it.
- Chip: Start from these
- Action: Busiest nodes ▸ the first card *Start from these* ▸ hold.
- Impact: before/after hold: the wheel appears.

### S07 · Twelve accounts, eight deposits each                      ~42 s
- Voice: And there it is. Twelve accounts around the hub, each paying it in cash again and again, eight times apiece, always just under a thousand. That is why nothing fired. [pause 0.5] And look at the two dashed arrows going out. The hub is not keeping the money. It wires it on, to two relays.
- Chip: Start from these
- Action: hold on the wheel and the two dashed arrows.
- Impact: none.

### S08 · Every line is a row                                    ~32 s
- Voice: Is this a guess? Open the table under the graph. Every line is a row: from, link kind, to. The hub to one relay, a wire; the hub to the other, a wire. [pause 0.4] The graph shows the pattern, and the table backs it up.
- Chip: Rows
- Action: dock ▸ Rows ▸ hold.
- Impact: before/after hold: the dock opens.

### S09 · The route to the exit                                  ~36 s
- Voice: One last question: where does the money end up? In the data, the relays move it on by crypto, to one offshore account. [pause 0.5] The whole route is twelve small accounts, one hub, two relays and one exit. This graph shows the first half of it. The second half is one more Expand away.
- Action: hold on the graph.
- Impact: none.

### S10 · A name, with a reason                                  ~32 s
- Voice: When a name is truly needed, one person asks for it, with a reason, and it is recorded. [pause 0.4] The Reveal button under the canvas is greyed for this analyst, because the right to see unmasked ids belongs to the administrator, who must also lead the Investigation. Only that person sees the real ids, and only on that screen.
- Chip: Reveal…
- Action: hover the greyed Reveal… under the canvas (tooltip *Needs See unmasked ids*).
- Impact: mark the greyed Reveal….

### S11 · The lead, and what would change it                     ~36 s
- Voice: Ninety-six deposits, each just under a thousand, collected by one hub and passed on within hours. That is a lead, not a verdict. [pause 0.5] It weakens if the twelve also receive from the hub: money in both directions would look like a payroll, not a funnel. It does not.
- Action: hold on the graph.
- Impact: none.

### S12 · Monday morning                                         ~30 s
- Voice: File the route as a report, with the hub, the relays and the exit named. Hold the hub and the relays pending review. [pause 0.4] Keep the twelve feeders as an Entity List, so the next graph marks them.
- Chip: Entity Lists
- Action: rail Entity Lists ▸ hold on the lists of this Space; create nothing.
- Impact: none.

### S13 · The case file                                           ~28 s
- Casefile: Lead: one hub, twelve feeders, two relays, one exit | Evidence: ninety-six deposits just under a thousand, and the rows behind every link | Next: file the route as a report; hold the hub and the relays; keep the twelve feeders as an Entity List | Try it: press Busiest nodes and start from the first name
- Voice: The case file. Lead: one hub, twelve feeders, two relays, one exit. Evidence: ninety-six deposits just under a thousand, and the rows behind every link. Next step: file, hold, watch. [pause 0.6] No deposit was large and no payment crossed a line; only the links showed the funnel. The same steps run on millions of records and answer in moments. Try it: start from the first name in Busiest nodes.
- Action: case-file card (8 s) and the outro.
- Impact: none.
