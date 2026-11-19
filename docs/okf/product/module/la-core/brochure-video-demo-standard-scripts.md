---
type: Brochure
title: Inspecto LA Standard demos — from the Basic walk-through to a sealed Dossier
description: Draft scripts for two Telecom demo videos that continue the Basic walk-through into Standard features — sign-in, Investigations, four-eyes, Entity Lists — up to a sealed Dossier, with the recording setup.
item: module.la-core
audience: internal
status: current
verified: 2026-11-16 last-edit
code: [la/inspecto-la-core]
---

# Inspecto LA Standard demos — from the Basic walk-through to a sealed Dossier

> **Draft of 2026-11-16, not reviewed by product or legal.** Operator direction (2026-11-16): the case demos showed Basic only;
> extend them through the Standard features up to the Dossier export, two Telecom examples first, for review. Same style as
> [brochure-video-demo-scripts.md](brochure-video-demo-scripts.md): the real product full screen, recorded live; only zoom and
> ring/box marks as graphics; an explainer voice; **no record counts or Dataset sizes**; the outcome is a lead, never a verdict.
>
> Recorded on the **Preview demo build** (fake login: Demo Users), `C:\sandbox\la-preview-demo-20261116` on `:8081`, built from
> origin/master `9d07c5782`, with the 11 industry Spaces seeded by their `tools/seed-la-<use case>.mjs` (`--space la-telecom` for
> the two Telecom use cases). Preview carries everything Standard does; the scenes use only Standard features (EDITIONS.md:
> sign-in and roles, Investigations, the step log, members, four-eyes approval, Entity Lists, the Dossier and its sealed bundle).
> Render: `node tools/render-la-demo-video.mjs <name> --script docs/okf/product/module/la-core/brochure-video-demo-standard-scripts.md`
> (scenario `case_demo_std` of `tools/record-la-live.mjs`; its config adds the Demo User, the seeded Investigation and Entity List).
>
> Scene order: S00 sign in · S01–S05 the Basic intro (problem, data, mapping, graph, first sweep) · S10 open the Investigation ·
> S11 its Working Set · S12 the sealed step log and the annotations · S13 members, roles, four-eyes · S14 the Dossier: build,
> verify, download the sealed bundle · S15 the Entity List · S16 conclusion.

## Telecom_SimSwap_Standard

- Config: {"scenario":"case_demo_std","base":"http://127.0.0.1:8081","user":"Demo MFS Fraud Analyst","space":"la-telecom","view":"mfs_sim_swap_bursts","dataset":"mfs_screen_links_dataset","entity":"S-41","invTitle":"SIM-swap wallet takeover","listTitle":"Mule wallets to freeze"}

### S00 · Sign in
- Voice: This time we are on Inspecto LA Standard, the edition for a team. Everyone signs in, and what each person may see and do follows their role. This demo build offers ready-made users; in production, this is your company's single sign-on. Our analyst signs in as the fraud analyst for mobile financial services.

### S01 · The problem
- Voice: Customers say their mobile wallets were emptied. Every one of them had a SIM swap first. A swap is routine: a lost phone, a new handset. So who made these swaps, and why do the wallets they touched end up empty within two hours? The answer is spread across three files: the swap, the login, the payment. No single one shows it. The investigator starts in a Space, where the questions that matter are saved as views.

### S02 · The data
- Voice: First, the data. Every SIM event lands as a record: the number, the event, the time, the channel, and the staff login that did it. The swaps, the app logins and the wallet payments arrive as separate files. A Pipeline brings them in and keeps each Dataset up to date by itself. And this is not limited to a handful of records: the Datasets are indexed, so on the full history, millions of records, search and filter still answer in moments.

### S03 · The mapping
- Voice: The product has read the data and tells you what each column is. A Job links the pieces: a swap to the login that made it, a wallet to the handset it was used from, a wallet to the wallets it paid. Once you say which columns are the two ends of a link, a table of records becomes a network you can walk.
- Note: {"type":"zoom","f0":0.08,"f1":0.92,"x":1600,"y":700,"z":1.6}

### S04 · The graph
- Voice: The saved view draws swaps in bursts: staff logins, the numbers they swapped, and where the money went afterwards. Most of it looks like the busy day of a telecom, with stores and call centres swapping SIMs all day.

### S05 · First sweep
- Voice: First sweep: the busiest login is S-72, a hundred and sixty swaps. The obvious suspect. But S-72 is a store in a SIM-upgrade drive, and its customers kept their money. Busy is not suspicious. The second name is the one to follow: a call-centre login, S-41.
- Note: {"type":"zoom","f0":0.2,"f1":0.95,"x":2274,"y":700,"z":1.7}
- Note: {"type":"box","f0":0.4,"f1":0.66,"x":2024,"y":406,"w":500,"h":210,"label":"S-72 - an upgrade drive"}
- Note: {"type":"box","f0":0.68,"f1":0.9,"x":2024,"y":636,"w":500,"h":184,"label":"S-41"}

### S10 · The Investigation
- Voice: In Basic, you would now start a graph from that login and explore. In Standard, the work lives in an Investigation, and the analyst has already opened one for this login. File, Open, Investigations: SIM-swap wallet takeover. Unlike an exploration, an Investigation is recorded: every step, who took it, and why.
- Note: {"type":"zoom","f0":0.45,"f1":0.98,"x":1100,"y":850,"z":1.5}

### S11 · The Working Set
- Voice: Here is its Working Set, ids on. The call-centre login S-41, marked as the start, and the first wallet it led to. Why so little? The next step, an expand to everything this busy login touched, is waiting for a second person's approval. Until the manager says yes, it does not run. And the case already has an owner, a purpose and a time window.

### S12 · The step log
- Voice: Open the step log. Every step is sealed, in order: the start from the login, each expand with its limits, every exclusion with its reason. Nothing can be quietly edited afterwards, and a replay of the log rebuilds exactly this picture. The annotations hold the analyst's notes on the entities that matter.

### S13 · The team
- Voice: Investigations are team work. The members: the fraud analyst who owns the case, a reviewer who can read it, the fraud operations manager, and the administrator. The manager is the second pair of eyes. That held expand from S-41 is waiting in the manager's queue: four-eyes, with a limit each Space sets for itself.

### S14 · The Dossier
- Voice: When it is time to hand over, build the Dossier. It packs the Investigation, its steps, its rows and its references, with a SHA-256 manifest over every piece. Verify it, and the product checks that nothing has changed since: the log is intact. Then download the sealed bundle: one file for a regulator, a court or another team, whose seal breaks if anyone edits it.

### S15 · The Entity List
- Voice: The wallets the money went to are kept as an Entity List, Mule wallets to freeze. Every graph in this Space can now mark them, and the next Investigation can start from them.

### S16 · The conclusion
- Voice: That is the step from Basic to Standard. Basic lets anyone explore the links and find the pattern. Standard turns the finding into an Investigation a team can share, approve and defend, and ends in a sealed Dossier anyone can verify. The outcome is still a lead, not a verdict, but now it is one you can hand over. The same steps run on millions of records. Inspecto LA.

## Telecom_UnbilledUsage_Standard

- Config: {"scenario":"case_demo_std","base":"http://127.0.0.1:8081","user":"Demo Revenue Assurance Analyst","space":"la-telecom","view":"unbilled_usage","dataset":"telco_ra_screen_links_dataset","entity":"DL-047","invTitle":"Unbilled usage at one dealer","listTitle":"Unbilled SIMs"}

### S00 · Sign in
- Voice: This time we are on Inspecto LA Standard, the edition for a team. Everyone signs in, and what each person may see and do follows their role. This demo build offers ready-made users; in production, this is your company's single sign-on. Our analyst signs in as the revenue assurance analyst.

### S01 · The problem
- Voice: A telecom operator bills every SIM that uses its network. Or it should. Some subscribers make calls and use data every month, and no bill is ever raised. Revenue leaks, quietly, month after month. Where are those SIMs coming from? A billing report shows the gap. It cannot show who is behind it. The investigator starts in a Space, where the questions that matter are saved as views.

### S02 · The data
- Voice: First, the data. Every subscriber lands as a record: the number, the SIM, the dealer who activated it, when, the plan and the activation device. Beside it, the monthly network usage and the billing records. A Pipeline brings them in and keeps each Dataset up to date by itself. And this is not limited to a handful of records: the Datasets are indexed, so on the full history, millions of records, search and filter still answer in moments.

### S03 · The mapping
- Voice: The product has read the data and tells you what each column is. A Job links each subscriber to the dealer that activated it and the device it was activated on, and counts the months its usage reached no bill. Once you say which columns are the two ends of a link, a table of records becomes a network you can walk.
- Note: {"type":"zoom","f0":0.08,"f1":0.92,"x":1600,"y":700,"z":1.6}

### S04 · The graph
- Voice: The saved view keeps every subscriber whose usage reached no bill in two or more months, with the dealer and the device behind it. A few ordinary subscribers are simply billed a month late. The question: is there one source behind the unbilled SIMs?

### S05 · First sweep
- Voice: First sweep: Busiest nodes. At the top, a device, DEV-00086, and a dealer, DL-047, each tied to three hundred unbilled SIMs. Then DL-000, with forty: the network's own test team, whose SIMs are never billed by design and are on the known list. The dealer and its one device are the ones to follow.
- Note: {"type":"zoom","f0":0.2,"f1":0.95,"x":2274,"y":700,"z":1.7}
- Note: {"type":"box","f0":0.35,"f1":0.6,"x":2024,"y":406,"w":500,"h":210,"label":"device DEV-00086"}

### S10 · The Investigation
- Voice: In Basic, you would now start a graph from that dealer and explore. In Standard, the work lives in an Investigation, and the revenue assurance analyst has already opened one. File, Open, Investigations: Unbilled usage at one dealer. Unlike an exploration, an Investigation is recorded: every step, who took it, and why.
- Note: {"type":"zoom","f0":0.45,"f1":0.98,"x":1100,"y":850,"z":1.5}

### S11 · The Working Set
- Voice: Its Working Set starts from the dealer, DL-047, expanded to the subscribers it activated. Three hundred SIMs, activated on one device on one day, used every month since and never billed. Beside them, the dealer's ordinary customers, billed as they should be.

### S12 · The step log
- Voice: Open the step log. Every step is sealed, in order: the start from the dealer, the bounded expand to its subscribers, the threshold and the time window. Nothing can be quietly edited afterwards, and a replay of the log rebuilds exactly this picture. The annotation on the dealer says what was found, in the analyst's words.

### S13 · The team
- Voice: Investigations are team work. Members have roles: a lead who works the case, reviewers who can read it, a manager who approves. Some steps are too big to take alone. An unbounded expand from a dealer this busy waits for a second person to approve it. That is the four-eyes rule, and each Space sets its own limit.

### S14 · The Dossier
- Voice: When it is time to hand over, build the Dossier. It packs the Investigation, its steps, its rows and its references, with a SHA-256 manifest over every piece. Verify it, and the product checks that nothing has changed since: the log is intact. Then download the sealed bundle: one file for finance, audit or a regulator, whose seal breaks if anyone edits it.

### S15 · The Entity List
- Voice: The unbilled SIMs are kept as an Entity List, Unbilled SIMs, so the billing team can act on them, and every graph in this Space marks them from now on.

### S16 · The conclusion
- Voice: That is the step from Basic to Standard. Basic lets anyone explore the links and find the pattern. Standard turns the finding into an Investigation a team can share, approve and defend, and ends in a sealed Dossier anyone can verify. Here, the outcome is revenue to recover, with the evidence already packed. The same steps run on millions of records. Inspecto LA.
