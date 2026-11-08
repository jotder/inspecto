# Inspecto LA short videos — scripts and agency briefs

> **Six concept videos and nine industry videos, each filmed on the real product over synthetic data: a one-page brief for the agency, then a shot-by-shot script with voice-over.**

*Audience: marketing, presales and the video agency. Product Map Item `module.la-core`. Draft of 2026-11-08, not
yet reviewed by product or legal. Every product claim below is taken from the
[Link Analysis user guide](user-guide-link-analysis.md); every story, id and figure in the industry videos is
taken from the showcase plan (`docs/superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md`) and,
for a built Space, its generator's ground truth file. The [whitepaper](brochure-inspecto-la-whitepaper.md) is the
reference for anything wider.*

## Contents

**Concept videos** — one idea each, cross-industry.

| # | Title | Length | Features shown | Film on | Data | Ready to film |
|---|---|---|---|---|---|---|
| 1 | The ring nobody saw | 45 s | Basic | Advanced install | `la-mobile-money` | Yes |
| 2 | Zero to graph in 60 seconds | 60 s | Basic | Basic install | *Try the sample* | After the timing check |
| 4 | Retire the red string | 30 s | Basic | Advanced install | `la-mobile-money` | Yes |
| 5 | It stays current | 45 s | Basic, plus one Advanced shot | Basic install | `la-police` + its staged month | Yes (shot 7 later) |
| 6 | The analyst (dramatization) | 60 s | Standard | Standard or Advanced install | `la-mobile-money` | After casting |
| 7 | One graph for any data (introduces the industry videos) | 75 s | Basic | Basic install | Two prepared synthetic files, telecom and mobile money | After data preparation |

**Industry videos (video 3)** — one video per industry, nine in all, grouped as the showcase catalogue in the plan
is. Each holds every use case of its industry as a segment. `la-showcase` appears in two: its telecom rings under
Telecom, its banking stories under Banking and finance.

| # | Industry | Use cases (segments) | Length | Film on | Ready to film |
|---|---|---|---|---|---|
| 3.1 | Telecom | Mobile money · MFS · Telco fraud · Revenue assurance | 60 s | Advanced | 2 of 4 segments (MFS, revenue assurance planned) |
| 3.2 | Banking and finance | First look · Financial crime · Ownership and sanctions · Card fraud · Trade finance · Crypto | 85 s | Advanced | 4 of 6 segments (trade, crypto planned) |
| 3.3 | Insurance | Claims fraud | 30 s | Advanced | Yes |
| 3.4 | Utilities | Electricity revenue protection | 30 s | Basic | Yes |
| 3.5 | Law enforcement | Criminal investigation | 30 s | Basic | Yes |
| 3.6 | Public sector | Procurement · Benefits and tax | 35 s | Basic | No: both Spaces planned |
| 3.7 | Healthcare | Provider billing | 30 s | Basic | No: Space planned |
| 3.8 | Logistics | Cargo theft | 30 s | Basic | No: Space planned |
| 3.9 | Enterprise | Internal audit · Insider threat | 35 s | Advanced | 1 of 2 segments (insider planned) |

*Features shown* is the lowest edition that has every feature on screen. *Film on* is the install the video must be
captured on: `la-mobile-money`, `la-showcase` and `la-audit` are templates that need an Advanced install
(`minEdition: advanced`) today, even where every feature shown is in Basic. The plan's wave B gives those three a Basic
tier; when it lands, re-film or keep them on Advanced. `la-claims` got its Basic tier on 2026-11-08: its saved views
run on Basic with unmasked ids, while its Investigation, Reveal and Case segments still need an Advanced install. The planned Spaces are specified with a Basic tier first.

---

## Common brief (applies to every video)

**Product name.** *Inspecto LA* in voice-over and titles. "Link Analysis" is the name of the capability
inside the product, and may appear on screen.

**Tone.** Calm, precise and confident. The product's own voice is plain and honest: it says what it did and
what it set aside. The videos should sound the same. No thriller music, no hacker clichés (green code rain,
hooded figures), no "AI magic" language.

**Filming rules (binding).**
- Film **only the synthetic showcase Spaces** named in this document, or (video 7) a fresh Space made from the
  prepared synthetic files. Never film any other Space on the demo machine. Some hold real customer data.
- Before every capture session, set the browser's current Space explicitly to the Space being filmed, and check
  the Space name in the left rail in the first frame.
- Numbers and ids on screen must be the ones the product shows. Do not edit a count, a name or a timing in post.
  If a shot needs a different number, change the data and re-film it.
- On a Standard or Advanced install, identifiers are **masked** by default, so an id named in the voice-over
  (`AG-18`, `TILL-06`) can show as an alias. Film signed in as a user who can reveal identifiers, and check in the
  first frame that the ids on screen match the script. Basic shows them unmasked.
- Every story has a **benign look-alike** (named in each brief). It must never appear on screen as part of the
  story or as suspicious; that is the honest half of the demo.
- A shot showing a feature above Basic carries an on-screen edition label (*Inspecto LA Standard* or *Inspecto LA
  Advanced*) while it is visible. Geo Views are among them: they are inert on Basic. Untagged shots show features
  every edition has.

**Capture.** 1920 × 1080 screen capture at 60 fps, browser at 100 % zoom, light theme, a clean browser profile
with no bookmarks bar or extensions. Hide the mouse pointer except where a shot calls for a click. Slow,
deliberate pointer moves; let each result stay on screen for at least 1.5 s.

**Deliverables per video.** A 16:9 master; 9:16 and 1:1 cut-downs for social (re-framed on the graph, not
letterboxed); burned-in captions on every version, because most social plays start muted; a clean version
without captions; SRT files.

**Voice-over.** About 2.5 words a second. The word counts below fit the lengths given. One voice across the
series.

**Audio.** One music bed for the series, understated, with a single soft accent on each reveal shot.

**End card (every video).** Inspecto LA logo · the line *See who is connected.* (each industry video adds its
industry line) · `[CTA URL — to be supplied]`.

**Approvals.** Product signs off each script's claims before filming, and the final cut before release.

---

## 1 · The ring nobody saw (45 s)

**Brief**

| | |
|---|---|
| Objective | Show that links make visible what rows hide. |
| Audience | Fraud and AML managers at banks and mobile-money operators. |
| Channel | LinkedIn, website hero, trade-show loop. |
| Message | A ring that can't be seen in a table is obvious as a graph, in a few clicks. |
| Must show | The table first, then the graph; *Busiest nodes* and excluding the busy merchant; *Tight groups* finding the device farm. |
| Must not | Claim the product "detects fraud on its own". The analyst finds it, and the product points at it. |
| Data | `la-mobile-money` (14 days of synthetic wallet transfers) on an Advanced install. The busy merchant is `MER-ELECTRIC` (a benign biller, the look-alike), and the planted ring is the nine-wallet device farm. Every feature shown is in Basic. |

**Script**

| # | Time | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 0–5 | A table of wallet transfers scrolls slowly upward. Timestamps, wallet ids, amounts. | — | Fourteen days of mobile-money transfers. |
| 2 | 5–9 | The scroll stops. Zoom into one ordinary-looking row. | — | Somewhere in here, a ring is moving money. |
| 3 | 9–14 | Cut to Inspecto LA. *Start a graph* draws the Dataset; nodes settle into place. | — | Turn the rows into links… |
| 4 | 14–20 | One giant node dominates the centre. The right panel's *On screen* tab ▸ *Busiest nodes*: `MER-ELECTRIC` is first. | *Busiest nodes* | …and the first thing you see is noise: a biller everyone pays. |
| 5 | 20–25 | Right-click `MER-ELECTRIC` ▸ *Exclude…*. The hub disappears and the graph breathes out. | *Exclude* | Set it aside. |
| 6 | 25–33 | *Tight groups*: *Show on graph* on the first group. Nine wallets light up, tightly knotted. Everything else dims. | *Tight groups* | Now it stands out: nine wallets on shared devices, moving money among themselves. |
| 7 | 33–39 | Slow push-in on the nine-wallet cluster. | — | The table never showed you that. |
| 8 | 39–45 | End card. | *See who is connected.* | Rows hide relationships. Inspecto LA shows them. |

*Voice-over: 81 words.*

---

## 2 · Zero to graph in 60 seconds (60 s)

**Brief**

| | |
|---|---|
| Objective | Remove the fear that a graph tool is heavy to install and slow to learn. |
| Audience | Analysts and team leads at smaller teams; IT evaluators. |
| Channel | Website "Get started" page, YouTube, product launch email. |
| Message | Unzip, start, and you're looking at a graph in under a minute. No cluster, no cloud, no consultants. |
| Must show | A real on-screen stopwatch running the whole time, with no cuts while it runs. |
| Must not | Cut time out while the stopwatch runs. If the real run takes longer than 60 s, re-title the video with the real time. |
| Data | *Try the sample* (`la-sample`) on a fresh Basic install with no Spaces. The sample graph has 112 entities and 224 links (measured on a Basic bundle on 2026-11-07). |
| Before filming | Time the full run three times on the filming laptop with the final build; use the median in the title. |

**Script**

| # | Time | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 0–4 | A laptop desktop, one zip file on it. A stopwatch appears in the corner at 00:00. | 00:00 | How long does it take to see your first link chart? |
| 2 | 4–12 | Stopwatch starts. Unzip the bundle; the folder opens. | — | Unzip it. |
| 3 | 12–20 | Run the start script; a console window shows the server starting. | — | Start it. One process on your own machine. |
| 4 | 20–30 | The browser opens on *Getting started*. The pointer rests on *Try the sample*. | *Getting started* | The first page tells you what to do. |
| 5 | 30–42 | Click *Try the sample*. The graph draws: 112 entities, 224 links. | *Try the sample* | One click, and you're exploring. |
| 6 | 42–50 | Stopwatch stops. Hold on the graph with the time in frame. | `[measured time]` | No cluster. No cloud. No consultants. |
| 7 | 50–55 | Click the light bulb: *What can I do next?* opens with its suggestions. | *What can I do next?* | And when you're ready, it tells you the next step. |
| 8 | 55–60 | End card. | *See who is connected.* | Inspecto LA. |

*Voice-over: 66 words.*

---

## 3 · Industry videos — one per industry (9 videos)

One video per industry, grouped as the showcase catalogue in the plan is. Each video holds **every use case of its
industry as a segment**: a short opening, one segment per use case, and the end card. The agency builds one segment
template and reuses it everywhere.

**Brief (shared)**

| | |
|---|---|
| Objective | One video per industry, so sales can send the one that fits the prospect and it shows every case that industry has. |
| Introduced by | Concept video 7, *One graph for any data*: it shows how any domain's data becomes the same graph, and its last shot is the industry index that leads into these nine videos. On a website or playlist, video 7 plays first and each industry video links back to it. |
| Audience | Named per video below. |
| Channel | Sales email, LinkedIn targeted by industry, trade-show loop per stand. |
| Message | Different cases, same move: the link you were missing is one click away. |
| Shape | Opening (the industry in one line, 5 s) → one segment per use case (10–12 s each) → end card with the industry line (5 s). |
| Segment | The use-case label on screen; the Space's graph or saved view; one click (the analysis named); the reveal, held at least 2 s. Its voice-over is the hook and the finding in two short sentences. |
| Modular | Segments are filmed separately. A video whose Spaces are all built is filmed now; a planned Space's segment is filmed when the Space is built and cut in. Until then, publish the video with its built segments only, or hold it (the owner decides per industry). |
| Film on | One install per video: an **Advanced** install if any of its segments needs one (`la-mobile-money`, `la-showcase`, `la-audit` need one today; `la-claims` only for its Investigation, Reveal and Case segments), otherwise Basic. On Advanced, film as a user who can reveal identifiers. |
| Must show | Each reveal exactly as the Space's ground truth file lists it: the same entities, nothing added. |
| Must not | Use real place, company or person names. Show a story's look-alike as suspicious. |
| Before filming | Check each reveal against the Space's ground truth file. For a planned Space, re-read its segment against the Space as built: ids and figures can move during the build. |

**Segment template**

| Part | Picture | On-screen text | Voice-over |
|---|---|---|---|
| Label | The use case's records, one second. | [Use case] | [Hook] |
| Graph | The Space's graph or saved view, wide. | — | — |
| Click | The analysis named in the segment. | [Analysis name] | — |
| Reveal | The finding, held. | — | [Finding] |

### 3.1 · Telecom (60 s)

| | |
|---|---|
| Audience | Fraud, risk and revenue-assurance teams at mobile operators and their mobile financial services (MFS) businesses. |
| Film on | Advanced install (`la-mobile-money`, `la-showcase`). |
| Ready | Segments A and C now; B and D when `la-mfs` and `la-telco-ra` are built. |

| Seg | Use case | Space | Story | Reveal | Look-alike |
|---|---|---|---|---|---|
| A | Mobile money | `la-mobile-money` (built) | Agent-assisted structuring: 25 wallets top up through agent `AG-18`. | `MER-ELECTRIC` excluded, then *Busiest nodes*: `AG-18` at the centre of the 25. | `MER-ELECTRIC`, a biller everyone pays: shown only as the noise set aside. |
| B | MFS | `la-mfs` (planned) | SIM-swap wallet takeover: 30 SIMs swapped through one call-centre login, `S-41`, in 10 days; each wallet drained within 2 hours to 5 mule wallets. | *Busiest nodes* over the swaps: `S-41` first; *Show on graph* adds the mule wallets. | A busy store doing many genuine SIM swaps for customers who keep their balances. |
| C | Telco fraud | `la-showcase` (built) | One planted ring per typology (Wangiri, IRSF, SIM box, subscription fraud, mules); the segment follows a suspect to the SIM box. | Saved view `telecom_fraud_network`, *Route from A to B* from `99979100001` to the SIM box: 4 hops. | — (none listed; show no other number as suspect). |
| D | Revenue assurance | `la-telco-ra` (planned) | Dealer commission fraud: a dealer activates 400 SIMs on 3 devices; each tops up once and goes dormant. | *Busiest nodes* over activations: the dealer and its 3 devices. | A dealer near a university whose term-start spike stays active. |

| # | Time | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 0–5 | Call records and wallet transfers side by side. | Telecom | Calls, wallets, SIMs and dealers. Four ways money leaks out of a network. |
| 2 | 5–17 | Segment A. | *Mobile money* · *Busiest nodes* | Twenty-five wallets, every top-up just under the limit. Set the biller aside: one agent, AG-18, behind all of them. |
| 3 | 17–29 | Segment B. | *MFS* · *Busiest nodes* | Thirty wallets emptied within two hours. One call-centre login swapped every SIM. |
| 4 | 29–41 | Segment C. | *Telco fraud* · *Route from A to B* | One suspect number, a SIM box somewhere in the network. Four hops, every link on screen. |
| 5 | 41–53 | Segment D. | *Revenue assurance* · *Busiest nodes* | Four hundred new SIMs, one top-up each, then silence. One dealer, three devices. |
| 6 | 53–60 | End card. | *For telecom fraud and revenue assurance.* | Inspecto LA. |

### 3.2 · Banking and finance (85 s)

| | |
|---|---|
| Audience | Fraud, AML, KYC, sanctions and trade-finance compliance teams at banks, payment providers and virtual-asset service providers. |
| Film on | Advanced install (`la-showcase`). |
| Ready | Segments A–D now; E and F when `la-trade` and `la-crypto` are built. |

| Seg | Use case | Space | Story | Reveal | Look-alike |
|---|---|---|---|---|---|
| A | First look | `la-sample` (built) | 12 smurf accounts structure cash into `MULE-HUB-01`, on through `RELAY-01`/`RELAY-02` to `OFFSHORE-77`. | *Route from A to B* from a smurf to `OFFSHORE-77`, through the hub and a relay. | — (the sample has none; imply no other account is suspect). |
| B | Financial crime tour | `la-showcase` (built) | Agent till `TILL-06` takes 80 % of the cash-out. | *Busiest nodes* over cash-out links: `TILL-06` first, its share in the sentence. | — (none listed; show no other till as suspect). |
| C | Ownership and sanctions | `la-ownership` (built) | A customer's 50 % owner is 60 % owned by a person on the (invented) sanctions list. | *Route from A to B* from the customer to the listed person, the stakes on the links. | A near-namesake with a different birth year and nationality: never shown as a match. |
| D | Card fraud | `la-card` (built) | Common point of purchase: 60 cards defrauded 1–6 weeks after paying merchant `M-317`. | The common-point saved view, *Busiest nodes*: `M-317` first, linked to all 60. | A busy supermarket used by many cards with no later fraud. |
| E | Trade finance | `la-trade` (planned) | Circular trade: the same 6 containers go A → B → C → A in 4 months, the value rising each leg. | *Pattern match* for a cycle: the three companies and the 6 containers in a loop. | A container-leasing firm's boxes moving between many unrelated shippers. |
| F | Crypto | `la-crypto` (planned) | Converging cash-out: 40 KYC accounts at 2 exchanges deposit from one cluster and cash out to 3 bank accounts. | *Tight groups*: one cluster in, the 40 accounts, 3 bank accounts out. | An over-the-counter desk whose many customers deposit their own funds. |

| # | Time | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 0–5 | Statements, registers and bills of lading. | Banking and finance | Accounts, owners, cards, cargo and coins. Six places money hides. |
| 2 | 5–17 | Segment A. | *First look* · *Route from A to B* | Twelve accounts, every deposit under the limit. One hub, two relays, one offshore account. |
| 3 | 17–29 | Segment B. | *Financial crime* · *Busiest nodes* | Hundreds of payments in. One till takes eighty percent of the cash-out. |
| 4 | 29–41 | Segment C. | *Ownership and sanctions* · *Route from A to B* | The customer is clean, and so is its owner. Two hops up: a person on the sanctions list. |
| 5 | 41–53 | Segment D. | *Card fraud* · *Busiest nodes* | Sixty cards, sixty fraud claims, no connection. One merchant, weeks before the fraud began. |
| 6 | 53–65 | Segment E. | *Trade finance* · *Pattern match* | Six containers, four months, three countries. Round in a circle, worth more on every leg. |
| 7 | 65–77 | Segment F. | *Crypto* · *Tight groups* | Forty customers, forty different names. One pool of funds in, three bank accounts out. |
| 8 | 77–85 | End card. | *For financial crime and compliance.* | Inspecto LA. |

### 3.3 · Insurance (30 s)

| | |
|---|---|
| Audience | Special Investigations Units at insurers. |
| Film on | Advanced install (`la-claims`). |
| Ready | Now. When the planned `la-claims` additions land, a second segment (parts-supplier ring: one supplier on invoices from 9 garages, parts never fitted; look-alike a high-volume supplier whose parts are all fitted) makes it 42 s. |

| Seg | Use case | Space | Story | Reveal | Look-alike |
|---|---|---|---|---|---|
| A | Claims fraud | `la-claims` (built) | 12 claimants sharing 4 phones, 3 addresses, one garage, one clinic and one lawyer. | *Tight groups*: the 12 claimants and what they share, as one group. | — (none listed; show no other claimant as suspect). |

| # | Time | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 0–5 | Claims list. | Insurance | Twelve claims. Twelve different claimants. Nothing in common. |
| 2 | 5–11 | The claims graph, wide. | — | Draw them as links: people, phones, addresses, garages. |
| 3 | 11–18 | *Tight groups*. | *Tight groups* | Ask which of them belong together. |
| 4 | 18–25 | The ring. | — | Four phones. Three addresses. One garage, one clinic, one lawyer. |
| 5 | 25–30 | End card. | *For Special Investigations Units.* | Inspecto LA. |

### 3.4 · Utilities (30 s)

| | |
|---|---|
| Audience | Revenue-protection teams at electricity utilities. |
| Film on | Basic install. |
| Ready | Now. |
| Optional shot | The loss-by-transformer Geo View, `TX-007` and `TX-019` over 15 %: label it *Inspecto LA Standard* (Geo Views are inert on Basic) and film it on a Standard or higher install. |

| Seg | Use case | Space | Story | Reveal | Look-alike |
|---|---|---|---|---|---|
| A | Electricity revenue protection | `la-utility` (built) | 22 premises under transformers `TX-007` and `TX-019` drop 40–70 % within 2 weeks of a visit by contractor `C-14`; transformer supply stays flat. | Contractor-network saved view, *Busiest nodes*: `C-14` at the centre of the 22. | A holiday home whose use drops every winter, with no visit. |

| # | Time | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 0–5 | Consumption table, the drops visible. | Utilities | Twenty-two meters started reading low. The transformers didn't. |
| 2 | 5–11 | The contractor-network saved view, wide. | — | Draw premises, visits and contractors as links. |
| 3 | 11–18 | *Busiest nodes*. | *Busiest nodes* | Ask who they have in common. |
| 4 | 18–25 | `C-14` and the 22 premises. | — | One contractor visited them all, two weeks before the drop. |
| 5 | 25–30 | End card. | *For revenue protection.* | Inspecto LA. |

### 3.5 · Law enforcement (30 s)

| | |
|---|---|
| Audience | Police intelligence analysts. |
| Film on | Basic install. |
| Ready | Now. |
| Alternative segment | The drug-supply handset network: one controller number per period, its handset changing every 3–5 weeks, 75–80 % of about 60 customers carried over (look-alike: a delivery driver's phone with many short calls and no carry-over). |

| Seg | Use case | Space | Story | Reveal | Look-alike |
|---|---|---|---|---|---|
| A | Criminal investigation | `la-police` (built) | Burglary series: 18 burglaries in 3 months across 2 districts; 40 % of the items resurface at 2 dealers through 3 sellers; a vehicle kept by one seller's partner is seen near 11 scenes. | Stolen-property saved view, *Tight groups*: scenes, items, dealers and sellers as one piece, the vehicle joining it. | A taxi seen near 54 scenes across the year and the city, none in the series. |

| # | Time | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 0–5 | Incident reports, two districts. | Law enforcement | Eighteen burglaries. Two districts. No suspect. |
| 2 | 5–11 | The stolen-property saved view, wide. | — | Draw what was taken, and where it was sold. |
| 3 | 11–18 | *Tight groups*. | *Tight groups* | Ask what connects the scenes. |
| 4 | 18–25 | Two dealers, three sellers, one vehicle. | — | Two dealers. Three sellers. One car seen near eleven of the scenes. |
| 5 | 25–30 | End card. | *For intelligence analysts.* | Inspecto LA. |

### 3.6 · Public sector (35 s)

| | |
|---|---|
| Audience | Public-procurement integrity units, benefits-integrity and tax-compliance teams. |
| Film on | Basic install. |
| Ready | When `la-procurement` and `la-benefits` are built. |

| Seg | Use case | Space | Story | Reveal | Look-alike |
|---|---|---|---|---|---|
| A | Procurement | `la-procurement` (planned) | Bid rotation: 4 firms win 24 tenders in one category in turn; 2 of them share a director. | *Tight groups* over bids and directors: the 4 firms and the shared director. | 3 large firms that win often but bid independently. |
| B | Benefits and tax | `la-benefits` (planned) | Address farm: 14 claimants under 5 surnames at one address, each "living alone", paid into 3 accounts. | *Busiest nodes* over addresses: the one address, its 14 claimants, the 3 accounts. | A care home with many genuine residents and their own accounts. |

| # | Time | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 0–5 | Tender awards and benefit claims. | Public sector | Public money, two ways to take it. |
| 2 | 5–17 | Segment A. | *Procurement* · *Tight groups* | Twenty-four tenders, four winners taking turns. Two of them share a director. |
| 3 | 17–29 | Segment B. | *Benefits* · *Busiest nodes* | Fourteen claimants, each one living alone. One address, three bank accounts. |
| 4 | 29–35 | End card. | *For public-sector integrity.* | Inspecto LA. |

### 3.7 · Healthcare (30 s)

| | |
|---|---|
| Audience | Program-integrity teams at health insurers and public payers. |
| Film on | Basic install. |
| Ready | When `la-health` is built. |

| Seg | Use case | Space | Story | Reveal | Look-alike |
|---|---|---|---|---|---|
| A | Provider billing | `la-health` (planned) | Referral kickbacks: 2 general practitioners send 85 % of their referrals to one imaging centre owned by one doctor's spouse. | *Expand* from the imaging centre to its owner: the spouse, and the doctor beside them. | A practice that refers to the only hospital nearby. |

| # | Time | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 0–5 | Referral counts, one centre far ahead. | Healthcare | Two doctors. Most of their referrals go to one place. |
| 2 | 5–11 | Doctors, referrals, providers and owners as a graph. | — | Draw who refers to whom, and who owns what. |
| 3 | 11–18 | *Expand* from the imaging centre. | *Expand* | Ask who owns the imaging centre. |
| 4 | 18–25 | The owner. | — | One doctor's spouse. |
| 5 | 25–30 | End card. | *For program integrity.* | Inspecto LA. |

### 3.8 · Logistics (30 s)

| | |
|---|---|
| Audience | Logistics security teams and cargo insurers. |
| Film on | Basic install. |
| Ready | When `la-logistics` is built. |

| Seg | Use case | Space | Story | Reveal | Look-alike |
|---|---|---|---|---|---|
| A | Cargo theft | `la-logistics` (planned) | Inside job: 12 losses share one driver pair, and one night-shift scanner is on 10 of them. | *Tight groups*: the 12 losses joined by the 2 drivers and the scanner. | A high-volume driver on the busiest route with 2 losses in a year. |

| # | Time | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 0–5 | Loss reports, different routes and months. | Logistics | Twelve loads lost. Different routes. Different months. |
| 2 | 5–11 | Trips, drivers and scanners as a graph. | — | Draw who drove, and who scanned. |
| 3 | 11–18 | *Tight groups*. | *Tight groups* | Ask what the losses share. |
| 4 | 18–25 | The 2 drivers and the scanner. | — | The same two drivers. The same night-shift scanner. |
| 5 | 25–30 | End card. | *For cargo security.* | Inspecto LA. |

### 3.9 · Enterprise (35 s)

| | |
|---|---|
| Audience | Internal audit, accounts-payable control, security, HR and internal investigations. All people and systems are invented. |
| Film on | Advanced install (`la-audit`). |
| Ready | Segment A now; B when `la-insider` is built. |

| Seg | Use case | Space | Story | Reveal | Look-alike |
|---|---|---|---|---|---|
| A | Internal audit | `la-audit` (built) | Vendor `V-117` shares a bank account and an address with employee `E-31`. | *Expand* from `V-117`: the account and address, `E-31` behind both. | — (none listed; do not film the unlinked sixth vendor of the shared-account story as part of it). |
| B | Insider threat | `la-insider` (planned) | Access creep: manager `M-7` grants payroll and treasury access to 4 reports with no ticket; one later edits a vendor's bank details. | *Route from A to B* from the bank-detail edit back to `M-7`, through the grant with no ticket. | A role change with an HR record and a ticket. |

| # | Time | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 0–5 | An approved invoice and an access log. | Enterprise | The threat inside the company. |
| 2 | 5–17 | Segment A. | *Internal audit* · *Expand* | A new vendor, invoices approved. Same bank account, same address as one of your own employees. |
| 3 | 17–29 | Segment B. | *Insider threat* · *Route from A to B* | A vendor's bank details changed. One manager, four grants, no ticket. |
| 4 | 29–35 | End card. | *For internal audit and investigations.* | Inspecto LA. |

---

## 4 · Retire the red string (30 s)

**Brief**

| | |
|---|---|
| Objective | The most shareable cut: one image that explains the product with no words needed. |
| Audience | Broad: anyone who has investigated anything. |
| Channel | Social (9:16 first), conference screens. |
| Message | The corkboard is how investigators used to think. Inspecto LA is how they think now. |
| Must show | Split screen. Left: a real, physical corkboard with printouts, sticky notes and red string, shot practically (not CGI). Right: the same people as a graph. |
| Data | `la-mobile-money` on an Advanced install. The route is the planted 98 % pass-through mule chain that ends at agent `AG-39`; take its first wallet from the Space's ground truth. Every feature shown is in Basic. |
| Casting | Hands only, no faces. |

**Script**

| # | Time | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 0–5 | Left only: hands pin a printout to a corkboard and wind red string between two photos. | — | *(no voice-over; the sound of a pin going in)* |
| 2 | 5–10 | Split screen opens. Right: the same wallets as a graph. | — | You've seen this board before. |
| 3 | 10–18 | Left: hands hunt for the next string. Right: *Route from A to B* ▸ *Find route*. | *Route from A to B* | The route from the first wallet to the cash-out, |
| 4 | 18–24 | Right: the chain to `AG-39` lights up, link by link. Left: the hands stop. | — | found in one click. |
| 5 | 24–30 | Left fades out. End card on the right. | *Retire the red string.* | Inspecto LA. |

*Voice-over: 23 words.*

---

## 5 · It stays current (45 s)

**Brief**

| | |
|---|---|
| Objective | Show that a view is a question about the data, not a frozen picture. |
| Audience | Analysts who run the same checks every week or month. |
| Channel | Website, sales follow-up email. |
| Message | Next month's files land in a folder; your saved view shows what changed when you open it. |
| Must show | Files landing in the watched folder; *What changed in your data*; reopening a saved view. The Alert shot only with its edition label. |
| Must not | Say the graph redraws by itself while it is open. It re-reads the data when the view is opened. |
| Data | `la-police` on a Basic install. It lands 12 months, 2025-11-01 to 2026-10-31; its staged month (November 2026, in `data/staged/<feed>`) adds one more serial match by a seller in the burglary series. The `police_links` Job folds new files in every 5 minutes: cut the wait in the edit, never fake the result. |
| Edition | Shots 1–6 Basic. Shot 7 (the Alert) **Advanced**: the police Advanced tier specifies an Alert Rule on new serial matches but is not built yet. Cut shot 7 until it is, and for a Basic-only version. |

**Script**

| # | Time | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 0–6 | The stolen-property saved view open on the burglary series. | — | Last month you linked the series and saved the view. |
| 2 | 6–12 | A file explorer: November's files are copied into the inbox folders. | — | This month's records land in a folder. |
| 3 | 12–18 | *Getting started*: *What changed in your data* shows the new files were brought in. | *What changed in your data* | Inspecto LA picks them up and tells you what it brought in. |
| 4 | 18–25 | *My work* ▸ open the saved view. The graph redraws over the new data. | *My work* | Open the same view… |
| 5 | 25–32 | The new serial match: a stolen item, sold by one of the series sellers. Highlight it. | — | …and another stolen item turns up, sold by the same seller. |
| 6 | 32–37 | Click it: the node detail dialog. | — | Same question. Fresh answer. |
| 7 | 37–41 | **Advanced, when built:** an Alert raised by the Alert Rule on new serial matches. | *Inspecto LA Advanced* | Or let an Alert Rule ask it for you. |
| 8 | 41–45 | End card. | *See who is connected.* | Inspecto LA. |

*Voice-over: 75 words.*

---

## 6 · The analyst — dramatization (60 s)

**Brief**

| | |
|---|---|
| Objective | Give the product a human face for LinkedIn and booth screens. |
| Audience | Heads of financial crime; the analysts who will use it. |
| Channel | LinkedIn, booth loop, top of a webinar. |
| Message | Investigators need to find the link *and* defend it. Inspecto LA records the steps and hands them over as evidence. |
| Edition | **Standard**: Investigations, the step log and the Dossier are not in Basic. Film on an Advanced install (`la-mobile-money` needs one). |
| Casting | One actor, mid-career analyst, plain office clothes. The actor plays a role: no real person, company or customer is named or implied. |
| Required label | *Dramatization. Product footage is real; all data is synthetic.* on screen for at least 3 s at the start. |
| Must not | Present the actor as a customer, or quote a customer without signed approval. |

**Script**

| # | Time | Picture | On-screen text | Voice-over / dialogue |
|---|---|---|---|---|
| 1 | 0–4 | Black, then the label. | *Dramatization. Product footage is real; all data is synthetic.* | — |
| 2 | 4–12 | Actor at a desk, to camera. | — | **Analyst:** Finding the link is the easy part. Proving it later — that's the job. |
| 3 | 12–22 | B-roll: *Expand* on a wallet; the busy merchant is excluded. | — | **Analyst:** I start from one account and widen out. The noise goes on a list, so it stays out. |
| 4 | 22–32 | B-roll: *Start an Investigation*; the Investigation tab's step log fills in. | *Inspecto LA Standard* | **Analyst:** When it matters, I open an Investigation. Every step I take is recorded. |
| 5 | 32–42 | B-roll: a held large expand waiting for a second person's approval. | *Four-eyes* | **Analyst:** The big steps need a second pair of eyes. That's not slowing me down — that's protecting the case. |
| 6 | 42–52 | B-roll: the Dossier is built and downloaded. | *Dossier* | **Analyst:** And when it's done, I hand over a sealed Dossier. Anyone can replay what I did. |
| 7 | 52–60 | Actor, to camera, then end card. | *See who is connected.* | **Analyst:** It finds the link. And it lets me stand behind it. |

*Dialogue: 106 words.*

---

## 7 · One graph for any data (75 s)

The introduction to the industry videos: whatever the industry, the data is mapped onto the product the same way,
and the same tools then work on it. It ends on an index of the nine industries, each leading to its videos.

**Brief**

| | |
|---|---|
| Objective | Answer the buyer's first question, "will it work on *our* data?", and route them to their industry's video. |
| Audience | Every prospect; the opening video of the industry playlist, the website's "How it works", the top of every sales deck demo. |
| Channel | Website, YouTube playlist opener, sales demos. |
| Message | Any records that say who dealt with whom become one graph. You say what each column holds; the product does the rest. Records from different systems meet on the entities they share. |
| Must show | *Add data* ▸ *Onboard a file* end to end: *Events between entities*, the detected columns with one **A guess** confirmed, *What does each column hold?* (an Entity Type, **From** and **To**), *Create and run* and its result (kept and set aside). Then a second file from another domain, the **Entities** page showing one Entity Type held by both Datasets, and **Graph model ▸ Entity/Link (several Datasets)** drawing them as one graph. |
| Must not | Say the product maps data "automatically" or "with AI". It detects and proposes, marks its guesses, and the analyst confirms. Do not show *Derive mapping* (it needs configuration-authoring rights and is not the point). |
| Data | A fresh Basic install, a new Space, and two **prepared synthetic files**: `calls.csv` (telecom call records: caller, callee, start time, duration) and `transfers.csv` (mobile-money transfers: sender number, receiver number, time, amount). The numbers are invented; a planted handful appear in both files, so that a number that calls the suspect also receives the money. |
| Data preparation | Generate both files with a script kept beside the showcase generators. If the two files write numbers differently, the shared Entity Type's *Compare values as: Phone number* is what makes them meet: product confirms which formats it treats as equal before the files are made. Film a dry run first. |
| Edition | Basic. Product confirms that *Entity/Link (several Datasets)* in Graph model is on Basic before filming. |

**Script**

| # | Time | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 0–7 | Quick montage of raw records from five industries, each a few rows on screen: call records, wallet transfers, insurance claims, meter reads, a company register. | — | Every industry keeps its records its own way. Calls. Transfers. Claims. Meter reads. |
| 2 | 7–13 | *Getting started* ▸ *Add data*. *Onboard a file*: choose *Events between entities*, drop `calls.csv`. | *Add data* | Inspecto LA asks every one of them the same question: who is connected to whom? |
| 3 | 13–22 | *Check the columns*: the detected types with their reasons; one column marked **A guess**; press *Confirm*. | *A guess* | It reads the file, says what it found, and marks what is only a guess, for you to confirm. |
| 4 | 22–32 | *What does each column hold?*: `caller` and `callee` hold *Phone number*; links go **From** `caller` **To** `callee`; the start time is the time. | *What does each column hold?* | You say what each column holds. These are phone numbers, and a call goes from one to the other. |
| 5 | 32–39 | *Create and run*. The result: rows read and kept, rows set aside and why. | *Create and run* | It tells you what it kept, what it set aside, and why. |
| 6 | 39–48 | *Add another file*: `transfers.csv`, a different system. Its sender and receiver columns hold the same *Phone number* type. The **Entities** page: the *Phone number* card lists both Datasets. | *Entities* | Now a file from a different business: mobile money. Its numbers are the same kind of thing… |
| 7 | 48–58 | **Graph model ▸ Entity/Link (several Datasets)**: both Datasets; *Run query*. Calls and transfers draw as one graph; click a shared number: one node, listing both Datasets. | *One node, two Datasets* | …so a number is one node, whichever system it came from. Here, the number that calls the suspect is also the one that gets the money. |
| 8 | 58–66 | Three fast cuts from the industry videos: *Busiest nodes*, *Tight groups*, *Route from A to B*, each on a different industry's graph. | — | And then the same tools work on any data. Who is busiest. Who belongs together. How A reaches B. |
| 9 | 66–72 | **Industry index**: nine tiles (Telecom, Banking and finance, Insurance, Utilities, Law enforcement, Public sector, Healthcare, Logistics, Enterprise), each lighting in turn. | *Find your industry* | See it on your industry's data. |
| 10 | 72–75 | End card, with the index still visible. | *See who is connected.* | Inspecto LA. |

*Voice-over: 150 words, a little under 2.5 words a second.*

**The industry index (shot 9).** On the web and in the playlist each tile links to its industry video above. In the
video itself the tiles are a still; no URLs on screen.

| Tile | Leads to | Its use cases |
|---|---|---|
| Telecom | 3.1 | Mobile money · MFS · Telco fraud · Revenue assurance |
| Banking and finance | 3.2 | First look · Financial crime · Ownership and sanctions · Card fraud · Trade finance · Crypto |
| Insurance | 3.3 | Claims fraud |
| Utilities | 3.4 | Electricity revenue protection |
| Law enforcement | 3.5 | Criminal investigation |
| Public sector | 3.6 | Procurement · Benefits and tax |
| Healthcare | 3.7 | Provider billing |
| Logistics | 3.8 | Cargo theft |
| Enterprise | 3.9 | Internal audit · Insider threat |

**Alternative cut (optional, 20 s).** *One file, two questions*: on the Data page, a transfers Dataset gets a second
**Mapping** (*account → channel* beside *account → account*), and both appear in the Start box without copying the
data. For analysts who already have their data in; not part of the main cut.

---

## Open points before filming

- Supply the CTA URL for the end card.
- Video 2: time the run on the final build and the filming laptop; set the title from the median.
- Industry videos: check every reveal against the Space's ground truth file before filming. Segments on planned
  Spaces (3.1 B and D, 3.2 E and F, 3.6, 3.7, 3.8, 3.9 B) are filmed only after their Spaces are built, and
  re-read against the Space as built. Decide per industry whether to publish with built segments only or wait.
- Segments 3.1 C, 3.2 A and B, 3.3 and 3.9 A have no look-alike listed in the showcase plan for the story filmed;
  confirm with the Space's owner that nothing else on screen reads as suspect.
- Video 5, shot 7: waits for the `la-police` Advanced tier (its Alert Rule).
- Video 7: generate the two synthetic files (calls and transfers with a planted shared handful of numbers);
  product confirms which number formats *Compare values as: Phone number* treats as equal, and that Graph model's
  *Entity/Link (several Datasets)* is on Basic. Film it after the industry videos it introduces, so shot 8 can use
  their footage.
- Filming needs both a Basic install (videos 2, 5, 7, 3.4–3.8) and an Advanced install (videos 1, 4, 6, 3.1, 3.2,
  3.3, 3.9); on the Advanced install, film as a user who can reveal identifiers.
- Product and legal sign-off on every script before the agency starts.
