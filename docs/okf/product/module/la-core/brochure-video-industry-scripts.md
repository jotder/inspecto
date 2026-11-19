---
type: Brochure
title: Inspecto LA industry videos — story, data, map, investigation, conclusion
description: Draft shot scripts for the Link Analysis industry videos, each walking two bundled cases from the industry's problem through the data, the mapping and the investigation to a conclusion, with the telling rules and shot format.
item: module.la-core
audience: internal
status: current
verified: 2026-11-16 last-edit
code: [la/inspecto-la-core]
---

# Inspecto LA industry videos — story, data, map, investigation, conclusion

> **Draft of 2026-11-15, not reviewed by product or legal.** One video per industry, each walking two bundled cases through the same arc: the problem in the industry, the data that is available and ingested, how its key fields and relations are mapped, then the investigation step by step with the product's own tools until the conclusion. Replaces, for these videos, the per-case screenshot scripts (kept with their screenshots outside the repository); the common brief in [brochure-video-scripts.md](brochure-video-scripts.md) still binds (the outcome is a lead and never a verdict; own-data message; edition close).
> Rendered by `node tools/render-la-industry-video.mjs <script-name>` into `C:\sandbox\la-video-out\<script-name>.mp4`.

**Telling rules (operator, 2026-11-15).** Open with the industry's problem, not with a file. Say what data the industry holds and how it lands, then how its key fields and relations become links, then the investigation in the order an analyst does it. **Never state a record count or a Dataset size** (the example is deliberately small and must not read as the limit of the product); instead say, twice, that search and filter stay fast on millions of records because the graph draws only what is asked for. Crop the screenshots of the Data page to the *Columns* panel so the *Discover* row counts are not on screen. No "fraudster", "criminal", "caught" or "proven".

Shot format (read by the renderer): `### name`, then `- Still:` one still as `<folder>/<file>.png`, two joined by ` + ` for a split screen, or `none` for a text card; add ` @cols` to crop to the Columns panel; `- Style:` `blur` (blurred still under the title), `card` or empty; `- Text:` lower-third lines separated by ` · `; `- Voice:` the narration.

## Telecom_Structuring-DeviceFarm

### Cold open
- Still: mobile-money-structuring/01-raw-records.png
- Style: blur
- Text: Telecom · Mobile-money fraud
- Voice: A mobile-money operator moves money for millions of customers, every day. Almost all of it is ordinary. A few patterns are not, and no single transaction gives them away. Here are two of them, and how an investigator finds each one.

### The data that is available
- Still: mobile-money-structuring/01-raw-records.png
- Text: Wallet transactions, as they land
- Voice: It starts with the data the operator already holds. Every transaction lands as a record: an id, a sending wallet, a receiving wallet, a type, an amount and a time. Cash-ins at agents, cash-outs, transfers between wallets, merchant payments. Read one by one, each looks perfectly normal.

### Ingested and understood
- Still: mobile-money-structuring/02-metadata-dataset.png @cols
- Text: Ingested into a Dataset · every column understood
- Voice: A Pipeline ingests the records into a Dataset and keeps it up to date. The product reads the columns and says what each one is: the sending and receiving wallets are entities, the date is a time, the amount is a detail carried on every link. Beside it sit the customer identity records and the agent register.

### Key fields and relations
- Still: mobile-money-structuring/03-map-link-dataset.png @cols
- Text: Mapping · each record becomes a link between two wallets
- Voice: Then you say how the records relate, once. The sending wallet is one end of a link, the receiving wallet is the other, and the type of transaction is the kind of link. A Job adds two kinds of link that no transaction records: wallets that share a handset, and wallets that share an identity.

### Built for scale
- Still: none
- Style: card
- Text: Built for millions of records · Search and filter in moments
- Voice: None of this is limited to a handful of records. The Dataset is indexed for search and filter, so on the operator's full history, millions of records, the same questions come back in moments, and the graph only draws what you ask it to.

### Case one, the question
- Still: mobile-money-structuring/05-intention-saved-view.png
- Text: Case 1 · Cash-ins just under the limit
- Voice: First case. A cash-in is limited to five thousand, and ordinary customers stay far below it. The investigator saves a view that keeps only amounts of four thousand seven hundred and fifty and above, and asks one question: who is collecting the deposits that sit just under the limit?

### Case one, the graph
- Still: mobile-money-structuring/06-graph-whole-view.png
- Text: Filter, then draw
- Voice: The filter keeps the sliver under the limit and the graph draws what is left. Each circle is a wallet or an agent. Each line is a cash-in, a cash-out or a transfer, and thicker lines carry more of them. It already looks less like a market and more like a funnel, but it is still too much to read.

### Case one, first sweep
- Still: mobile-money-structuring/07-explore-busiest-nodes.png
- Text: Busiest nodes · HUB-01 · RELAY-3 · AG-18
- Voice: First sweep: who has the most links? A wallet called HUB-01 leads. A relay follows. Third is an agent, AG-18. These are leads, not answers.

### Case one, read the links
- Still: mobile-money-structuring/08-explore-links-table.png
- Text: Every line is a record you can read
- Voice: Nothing here is a guess. Every line in the graph is a row in this table: who, what kind of link, to whom.

### Case one, narrow down
- Still: mobile-money-structuring/09-focus-start-from-entity.png
- Text: Start a graph · HUB-01
- Voice: Now narrow down. Start a new graph from one entity, the hub, and ask the data for every link it has.

### Case one, the reveal
- Still: mobile-money-structuring/10-reveal-entity-graph.png
- Text: HUB-01 · STRUCT-01 to STRUCT-25 · RELAY-1 · RELAY-2
- Voice: Twenty-five wallets, STRUCT-01 to STRUCT-25. Each pays the hub exactly once, and the hub passes the money straight on to two relays.

### Case one, the conclusion
- Still: mobile-money-structuring/11-outcome-busiest-around-entity.png
- Text: One agent to review
- Voice: Back on the busiest nodes, the picture is complete. All twenty-five cash-ins came through agent AG-18 within thirty-six hours, every amount between four thousand seven hundred and fifty and five thousand. The outcome is a lead: one agent to review, with the wallets and the hub as the evidence.

### Case one, the evidence
- Still: mobile-money-structuring/12-evidence-links-table.png
- Text: The evidence, row by row
- Voice: And the evidence is not a picture. It is the links behind it, row by row, each one traceable to a record.

### Case two, the question
- Still: mobile-money-device-farm/05-intention-saved-view.png
- Text: Case 2 · Nine wallets, one handset
- Voice: Second case, a different question. Customers with different national ids, each wallet passing every identity check. On paper they have never met. Do any of these wallets share something the register does not show?

### Case two, the graph
- Still: mobile-money-device-farm/06-graph-whole-view.png
- Text: Shared handset and shared identity links
- Voice: This time the graph is drawn from the links the Job added: wallets joined by a shared handset, or by a shared identity. Small groups appear. A household of three on one identity. A family phone with four wallets. Both are ordinary, and they show up as exactly that.

### Case two, narrow down
- Still: mobile-money-device-farm/09-focus-start-from-entity.png
- Text: Start a graph · FARM-1
- Voice: One group is different. Start a graph from one of its wallets, FARM-1, and follow the handset.

### Case two, the reveal
- Still: mobile-money-device-farm/10-reveal-entity-graph.png
- Text: FARM-1 to FARM-9 · one handset · nine national ids
- Voice: Nine wallets, FARM-1 to FARM-9, all on one handset. Each is registered to a different national id. The handset links them, and the identity records never could.

### Case two, the conclusion
- Still: mobile-money-device-farm/11-outcome-busiest-around-entity.png
- Text: One group to review together
- Voice: The outcome is one group of nine wallets to review together, not nine separate customers. The wallet's ordinary payments sit beside the handset links, so the whole of the group's activity is in front of the investigator.

### Case two, the evidence
- Still: mobile-money-device-farm/12-evidence-links-table.png
- Text: The evidence, row by row
- Voice: Again, every link is a row that can be quoted and handed over.

### Why a graph
- Still: mobile-money-device-farm/01-raw-records.png + mobile-money-device-farm/10-reveal-entity-graph.png
- Text:
- Voice: Each record was ordinary on its own. Only the links show twenty-five deposits meeting in one wallet, and nine identities meeting on one phone.

### Scale again
- Still: none
- Style: card
- Text: Millions of records · Answers in moments
- Voice: The same steps, a saved view, a filter, the busiest nodes, a graph from one entity, the table behind it, work on the operator's full history. Search and filter stay fast, because the graph only ever draws the part you ask for.

### Your data
- Still: mobile-money-structuring/10-reveal-entity-graph.png
- Text: Ships with Inspecto LA Basic · Your data, any domain
- Voice: Both cases ship with Inspecto LA, so you can work them yourself and look for the cases we did not show you. Then bring your own records, from any domain, say how they link once, and ask who is connected. Standard and above turn a finding into evidence a team can share and defend.

### End card
- Still: none
- Style: card
- Text: See who is connected. · For mobile-money fraud teams. · Try it on your own data.
- Voice: Inspecto LA.

## Banking_MuleRing-SanctionedOwner

### Cold open
- Still: mule-ring/01-raw-records.png
- Style: blur
- Text: Banking and finance · Financial crime
- Voice: A bank sees every transfer, every account and every owner it has on file. Two of its hardest questions are not about any one of them. Where does money go when it has been cut into small pieces on purpose? And who really stands behind a customer that passes every screening check? Here is how an investigator answers both.

### The data that is available
- Still: mule-ring/01-raw-records.png
- Text: Transfers, as they land
- Voice: The first data is the bank's own transfers. For each one: who paid, who was paid, the channel, the amount, the countries and the time. Cash deposits, wires, card payments, crypto. Each row looks ordinary.

### Ingested and understood
- Still: mule-ring/02-metadata-dataset.png @cols
- Text: Ingested into a Dataset · every column understood
- Voice: A Pipeline ingests the records into a Dataset and keeps it current. The product reads the columns and says what each one is: payer and payee are accounts, the booking time is a time, and the rest of each record travels with the link.

### Key fields and relations
- Still: mule-ring/03-map-link-dataset.png @cols
- Text: Mapping · payer to payee is already a link
- Voice: Here the payer column and the payee column both hold accounts, so each row is already a link from one account to another, and the channel names the kind of link. No extra step is needed.

### Built for scale
- Still: none
- Style: card
- Text: Built for millions of records · Search and filter in moments
- Voice: None of this is limited to a handful of records. The Dataset is indexed for search and filter, so across the bank's full transfer history, millions of records, the same questions come back in moments, and the graph only draws what you ask it to.

### Case one, the question
- Still: mule-ring/05-intention-saved-view.png
- Text: Case 1 · Cash broken into small pieces
- Voice: First case. Nobody deposited more than a thousand, so nobody was reported. And yet, in three days, a small fortune left the country. If cash is broken into pieces, the pieces must come back together somewhere. The investigator asks: where do the small deposits all end up?

### Case one, the graph
- Still: mule-ring/06-graph-whole-view.png
- Text: Accounts and the payments between them
- Voice: Each circle is an account. Each line is a kind of payment: a cash deposit, a wire, a card payment, crypto. Thicker lines carry more transfers. Too much to read at once.

### Case one, first sweep
- Still: mule-ring/07-explore-busiest-nodes.png
- Text: Busiest nodes · MULE-HUB-01
- Voice: First sweep: one account has more counterparties than any other, MULE-HUB-01. A few ordinary accounts are just behind it. Busy is not suspicious by itself, so the investigator looks at what the hub's links actually are.

### Case one, read the links
- Still: mule-ring/08-explore-links-table.png
- Text: Every line is a record you can read
- Voice: Nothing here is a guess. Every line in the graph is a row in this table: who, what kind of link, to whom.

### Case one, narrow down
- Still: mule-ring/09-focus-start-from-entity.png
- Text: Start a graph · MULE-HUB-01
- Voice: Now narrow down. Start a graph from the hub and ask the data for every link it has.

### Case one, the reveal
- Still: mule-ring/10-reveal-entity-graph.png
- Text: SMURF-01 to SMURF-12 · MULE-HUB-01 · RELAY-01 · RELAY-02
- Voice: Twelve accounts, SMURF-01 to SMURF-12. Each pays the hub in cash, eight times apiece. And the hub wires the money straight on to two relays.

### Case one, the conclusion
- Still: mule-ring/11-outcome-busiest-around-entity.png
- Text: A route to report
- Voice: Ninety-six deposits, each just under a thousand. The hub keeps almost nothing. The relays move it on by crypto to one offshore account, OFFSHORE-77. The outcome is a lead: a route to report, with the hub and the relays named.

### Case one, the evidence
- Still: mule-ring/12-evidence-links-table.png
- Text: The evidence, row by row
- Voice: And the evidence is not a picture. It is the links behind it, row by row, each one traceable to a record.

### Case two, more data and a new map
- Still: sanctioned-owner/03-map-link-dataset.png @cols
- Text: Case 2 · Two hops above a clean customer · Ownership links and name matches
- Voice: Second case, with different data: the ownership register, which says who owns how much of what, next to the bank's customers and a sanctions list. A Job turns each row into an owns link, and adds a name-match link wherever a listed name is close to a person's, with the same birth year and nationality.

### Case two, the question
- Still: sanctioned-owner/05-intention-saved-view.png
- Text: Does a listed person reach a customer through a chain of stakes?
- Voice: The customer passes screening. So does the company that owns it. The investigator asks: starting from a list entry with a name match, does a chain of stakes lead down to a customer of the bank?

### Case two, the graph
- Still: sanctioned-owner/06-graph-whole-view.png
- Text: People, companies, customers · who owns whom
- Voice: The view draws people, companies and customers, and who owns whom. A register drawn as a map.

### Case two, first sweep
- Still: sanctioned-owner/07-explore-busiest-nodes.png
- Text: Busiest nodes · CO-00859
- Voice: The busiest node is a large holding company, CO-00859. Large, and nothing more: no listed name leads to it. Size is not the question here, so the investigator sets it aside.

### Case two, narrow down
- Still: sanctioned-owner/09-focus-start-from-entity.png
- Text: Start a graph · PER-03904
- Voice: Start instead from the person the list entry matches, PER-03904, and ask for every link.

### Case two, the reveal
- Still: sanctioned-owner/10-reveal-entity-graph.png
- Text: SL-0137 ≈ PER-03904 · owns CO-04431
- Voice: One link is a name match to list entry SL-0137: two spellings of one name, the same birth year, the same nationality. The other link is a stake in a company, CO-04431.

### Case two, the conclusion
- Still: sanctioned-owner/11-outcome-busiest-around-entity.png
- Text: 60 % of CO-04431 · 50 % of CO-04313 · CUS-00528
- Voice: That person owns sixty percent of CO-04431, which owns half of CO-04313, the company behind customer CUS-00528. Two hops above a clean customer sits a possible match. The outcome is a customer to escalate for review, never a verdict.

### Case two, the evidence
- Still: sanctioned-owner/12-evidence-links-table.png
- Text: The evidence, row by row
- Voice: Again, every link is a row that can be quoted, and the name match shows its reasons.

### Scale again
- Still: none
- Style: card
- Text: Millions of records · Answers in moments
- Voice: The same steps, a saved view, the busiest nodes, a graph from one entity, the table behind it, work across the bank's full history and its whole ownership register. Search and filter stay fast, because the graph only ever draws the part you ask for.

### Your data
- Still: mule-ring/10-reveal-entity-graph.png
- Text: Ships with Inspecto LA Basic · Your data, any domain
- Voice: Both cases ship with Inspecto LA, so you can work them yourself and look for the cases we did not show you. Then bring your own records, from any domain, say how they link once, and ask who is connected. Standard and above turn a finding into evidence a team can share and defend.

### End card
- Still: none
- Style: card
- Text: See who is connected. · For financial crime and compliance. · Try it on your own data.
- Voice: Inspecto LA.
