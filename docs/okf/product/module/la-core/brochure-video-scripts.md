# Inspecto LA short videos — scripts and agency briefs

> **Six concept videos and an industry series of nineteen in nine industries, each filmed on the real product over synthetic data: a one-page brief for the agency, then a shot-by-shot script with voice-over.**

*Audience: marketing, presales and the video agency. Product Map Item `module.la-core`. Draft of 2026-11-08, not
yet reviewed by product or legal. Every product claim below is taken from the
[Link Analysis user guide](user-guide-link-analysis.md); every story, id and figure in the industry series is
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
| 7 | One graph for any data (introduces the industry series) | 75 s | Basic | Basic install | Two prepared synthetic files, telecom and mobile money | After data preparation |

**Industry series (video 3)** — one 30-second video per use case, nineteen in all, grouped by industry the same way
as the showcase catalogue in the plan. `la-showcase` gives two: its telecom rings under Telecom, its banking stories
under Banking and finance.

| # | Industry | Use case | Space | Film on | Ready to film |
|---|---|---|---|---|---|
| 3.1 | Telecom | Mobile money | `la-mobile-money` | Advanced | Yes |
| 3.2 | Telecom | MFS (SIM-swap takeover) | `la-mfs` | Basic (planned) | No: Space planned |
| 3.3 | Telecom | Telco fraud | `la-showcase` | Advanced | Yes |
| 3.4 | Telecom | Revenue assurance | `la-telco-ra` | Basic (planned) | No: Space planned |
| 3.5 | Banking and finance | First look | `la-sample` | Basic | Yes |
| 3.6 | Banking and finance | Financial crime tour | `la-showcase` | Advanced | Yes |
| 3.7 | Banking and finance | Beneficial ownership and sanctions | `la-ownership` | Basic | Yes |
| 3.8 | Banking and finance | Card fraud | `la-card` | Basic | Yes |
| 3.9 | Banking and finance | Trade finance | `la-trade` | Basic (planned) | No: Space planned |
| 3.10 | Banking and finance | Crypto | `la-crypto` | Basic (planned) | No: Space planned |
| 3.11 | Insurance | Claims fraud | `la-claims` | Advanced | Yes |
| 3.12 | Utilities | Electricity | `la-utility` | Basic | Yes |
| 3.13 | Law enforcement | Police | `la-police` | Basic | Yes |
| 3.14 | Public sector | Procurement | `la-procurement` | Basic (planned) | No: Space planned |
| 3.15 | Public sector | Benefits and tax | `la-benefits` | Basic (planned) | No: Space planned |
| 3.16 | Healthcare | Billing | `la-health` | Basic (planned) | No: Space planned |
| 3.17 | Logistics | Cargo theft | `la-logistics` | Basic (planned) | No: Space planned |
| 3.18 | Enterprise | Internal audit | `la-audit` | Advanced | Yes |
| 3.19 | Enterprise | Insider threat | `la-insider` | Basic (planned) | No: Space planned |

*Features shown* is the lowest edition that has every feature on screen. *Film on* is the install the video must be
captured on: `la-mobile-money`, `la-claims`, `la-showcase` and `la-audit` are templates that need an Advanced install
(`minEdition: advanced`) today, even where every feature shown is in Basic. The plan's wave B gives those four a Basic
tier; when it lands, re-film or keep them on Advanced. The planned Spaces are specified with a Basic tier first.

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

**End card (every video).** Inspecto LA logo · the line *See who is connected.* (the industry series adds its
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

## 3 · Industry series — by industry and use case (19 × 30 s)

One template, filmed once per use case, grouped by industry as in the showcase catalogue. The edit, the music and the voice-over shape stay the same, so the
agency builds it once and swaps the footage and the lines.

**Brief (shared)**

| | |
|---|---|
| Objective | One video per industry, so sales can send the one that fits the prospect. |
| Introduced by | Concept video 7, *One graph for any data*: it shows how any domain's data becomes the same graph, and its last shot is the industry index that leads into these videos. On a website or playlist, video 7 plays first and each industry video links back to it. |
| Audience | Named per video below. |
| Channel | Sales email, LinkedIn targeted by industry, trade-show loop per stand. |
| Message | Different industry, same move: the link you were missing is one click away. |
| Template | Hook (the industry's problem in one line) → the graph → one click → the reveal → end card with the industry line. |
| Must show | The story's reveal exactly as the Space's ground truth file lists it: the same entities, nothing added. |
| Must not | Use real place, company or person names. Show the story's look-alike as suspicious. |
| Before filming | Check each reveal shot against the Space's ground truth file. For a planned Space, re-read this script against the Space as built: ids and figures can move during the build. |

**Template**

| # | Time | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 0–5 | Industry establishing shot: the records on screen, never stock footage of people. | [Industry] | [Hook] |
| 2 | 5–11 | The graph of the Space (or its saved view), wide. | — | [What the data is] |
| 3 | 11–18 | One click: the analysis named in the script. | [Analysis name] | [What the click asks] |
| 4 | 18–25 | The reveal, held. | — | [What it shows] |
| 5 | 25–30 | End card with the industry line. | [Industry line] | Inspecto LA. |

Each video below gives its brief (audience, story, reveal, look-alike) and its five lines. *Voice-over: about 30
words each.*

### Telecom

#### 3.1 · Mobile money (`la-mobile-money`, built, Advanced install)

| | |
|---|---|
| Audience | AML teams at mobile-money operators. |
| Story | Agent-assisted structuring: 25 wallets top up through agent `AG-18`. |
| Reveal | After excluding `MER-ELECTRIC`, *Busiest nodes* puts `AG-18` at the centre of the 25 wallets. |
| Look-alike | `MER-ELECTRIC`, a biller everyone pays: shown only as the noise that is set aside. |

| # | Picture | On-screen text | Voice-over |
|---|---|---|---|
| 1 | Wallet top-ups, all just under the limit. | Mobile money | Twenty-five wallets. Every top-up just under the limit. |
| 2 | The graph; `MER-ELECTRIC` excluded. | *Exclude* | Draw the transfers. Set the biller aside. |
| 3 | *Busiest nodes*. | *Busiest nodes* | Ask what the wallets have in common. |
| 4 | `AG-18` and its 25 wallets. | — | One agent, AG-18, behind all of them. |
| 5 | End card. | *For anti-money-laundering.* | Inspecto LA. |

#### 3.2 · MFS — SIM-swap takeover (`la-mfs`, planned)

| | |
|---|---|
| Audience | Fraud and risk teams in a mobile operator's mobile financial services (MFS) business. |
| Story | SIM-swap wallet takeover: 30 subscribers have their SIM swapped through one call-centre login, `S-41`, in 10 days; each wallet is logged into from a new device and drained within 2 hours to 5 mule wallets, which cash out at 2 agents. |
| Reveal | *Busiest nodes* over the victims' SIM swaps puts `S-41` first; *Show on graph* adds the 5 mule wallets and the 2 cash-out agents. |
| Look-alike | A busy store that performs many genuine SIM swaps for customers who keep their balances. |
| Note | Not the `la-mobile-money` Space (wallet AML), and never a customer's MFS Space: everything on screen is invented. |

| # | Picture | On-screen text | Voice-over |
|---|---|---|---|
| 1 | Complaints: wallets emptied, thirty customers. | MFS | Thirty customers. Every wallet emptied within two hours. |
| 2 | SIM swaps, devices and transfers as a graph. | — | Draw the SIM swaps, the logins and the money. |
| 3 | *Busiest nodes*. | *Busiest nodes* | Ask what the victims share. |
| 4 | `S-41`, the 30 swaps, the 5 mule wallets. | — | One call-centre login swapped every SIM. Five mule wallets took the money. |
| 5 | End card. | *For mobile financial services.* | Inspecto LA. |

#### 3.3 · Telco fraud (`la-showcase`, built, Advanced install)

| | |
|---|---|
| Audience | Fraud teams at mobile operators. |
| Story | One planted ring per telecom typology (Wangiri, IRSF, SIM box, subscription fraud, mules); the video follows a suspect number to the SIM box. |
| Reveal | In the saved view `telecom_fraud_network`, *Route from A to B* from suspect `99979100001` to the SIM box: 4 hops. |
| Look-alike | — (none listed for this story; show no other number as suspect). |

| # | Picture | On-screen text | Voice-over |
|---|---|---|---|
| 1 | Call records from one suspect number. | Telco fraud | One suspect number. A SIM box hidden somewhere in the network. |
| 2 | The `telecom_fraud_network` saved view, wide. | — | Draw who calls whom. |
| 3 | *Route from A to B* to the SIM box. | *Route from A to B* | Ask for the route from the suspect to the box. |
| 4 | The route lit, hop by hop. | — | Four hops. Every link on the way, on screen. |
| 5 | End card. | *For telecom fraud teams.* | Inspecto LA. |

#### 3.4 · Telecom revenue assurance (`la-telco-ra`, planned)

| | |
|---|---|
| Audience | Revenue-assurance and fraud teams at mobile operators. |
| Story | Dealer commission fraud: a dealer activates 400 SIMs on 3 devices; each makes one top-up and goes dormant. |
| Reveal | *Busiest nodes* over activations: the dealer and the 3 devices behind the 400 SIMs. |
| Look-alike | A dealer near a university whose term-start spike stays active. |

| # | Picture | On-screen text | Voice-over |
|---|---|---|---|
| 1 | New subscribers, one top-up each, then nothing. | Telecom | Four hundred new subscribers. One top-up each. Then silence. |
| 2 | SIMs, dealers and devices as a graph. | — | Draw where each SIM was activated. |
| 3 | *Busiest nodes*. | *Busiest nodes* | Ask who sold them. |
| 4 | One dealer, three devices, 400 SIMs. | — | One dealer. Three devices. Paid a commission on every one. |
| 5 | End card. | *For revenue assurance.* | Inspecto LA. |

### Banking and finance

#### 3.5 · Banking — first look (`la-sample`, built, Basic)

| | |
|---|---|
| Audience | Anyone trying the product for the first time; companion to video 2. |
| Story | 12 smurf accounts structure cash into `MULE-HUB-01`, which passes it on through `RELAY-01` and `RELAY-02` to `OFFSHORE-77`. |
| Reveal | *Route from A to B* from one smurf account to `OFFSHORE-77`, the route lit through the hub and a relay. |
| Look-alike | — (the sample has none; do not imply other accounts are suspect). |

| # | Picture | On-screen text | Voice-over |
|---|---|---|---|
| 1 | Deposit rows, every amount just under the limit. | Banking | Twelve accounts. Every deposit just under the limit. |
| 2 | *Try the sample* graph, wide. | — | Draw who pays whom. |
| 3 | *Busiest nodes*: `MULE-HUB-01` first. | *Busiest nodes* | Ask who sits in the middle. |
| 4 | *Route from A to B* ▸ *Find route* to `OFFSHORE-77`. | *Route from A to B* | One hub, two relays, one offshore account. |
| 5 | End card. | *Try it on your own data.* | Inspecto LA. |

#### 3.6 · Banking — financial crime tour (`la-showcase`, built, Advanced install)

| | |
|---|---|
| Audience | Fraud teams at banks and mobile operators. |
| Story | Agent till `TILL-06` takes 80 % of the cash-out. |
| Reveal | *Busiest nodes* over the cash-out links: `TILL-06` first, its share in the result sentence. |
| Look-alike | — (none listed for this story; show no other till as suspect). |

| # | Picture | On-screen text | Voice-over |
|---|---|---|---|
| 1 | Cash-out records scrolling. | Banking and telecom | Hundreds of payments in. Where does the cash come out? |
| 2 | The cash-out graph, wide. | — | Draw payers and tills as links. |
| 3 | *Busiest nodes*. | *Busiest nodes* | Ask which till does the most. |
| 4 | `TILL-06` highlighted with its share. | — | One till. Eighty percent of the cash-out. |
| 5 | End card. | *For fraud and revenue assurance.* | Inspecto LA. |

#### 3.7 · Beneficial ownership and sanctions (`la-ownership`, built, Basic)

| | |
|---|---|
| Audience | KYC and sanctions compliance teams at banks. |
| Story | Sanctions two hops away: a customer's 50 % owner is 60 % owned by a person on the (invented) sanctions list. |
| Reveal | *Route from A to B* from the customer to the listed person: customer ← 50 % owner ← 60 % listed person. |
| Look-alike | A near-namesake of the listed person with a different birth year and nationality. It must not appear as a match. |

| # | Picture | On-screen text | Voice-over |
|---|---|---|---|
| 1 | The customer record, all checks green. | Ownership and sanctions | Your customer is clean. So is its owner. |
| 2 | Companies, people and stakes as a graph. | — | Draw who owns whom. |
| 3 | *Route from A to B* to the listed person. | *Route from A to B* | Follow the ownership up. |
| 4 | The route lit, the stakes on the links. | — | Two hops up: a person on the sanctions list. |
| 5 | End card. | *For KYC and sanctions.* | Inspecto LA. |

#### 3.8 · Card fraud (`la-card`, built, Basic)

| | |
|---|---|
| Audience | Card fraud operations at issuers and payment service providers. |
| Story | Common point of purchase: 60 cards defrauded 1–6 weeks after using one merchant, `M-317`, in a 3-week window. |
| Reveal | The common-point saved view (each defrauded card linked to the merchants it paid in the 45 days before): *Busiest nodes* puts `M-317` first, linked to all 60. |
| Look-alike | A busy supermarket used by many cards with no later fraud. |

| # | Picture | On-screen text | Voice-over |
|---|---|---|---|
| 1 | Sixty fraud reports, different cardholders. | Card fraud | Sixty cards. Sixty fraud claims. No connection. |
| 2 | The common-point saved view, wide. | — | Draw where each card was used before the fraud. |
| 3 | *Busiest nodes*. | *Busiest nodes* | Ask what they have in common. |
| 4 | `M-317` and the 60 cards. | — | One merchant, weeks before the fraud began. |
| 5 | End card. | *For card fraud teams.* | Inspecto LA. |

#### 3.9 · Trade finance (`la-trade`, planned)

| | |
|---|---|
| Audience | Trade-finance compliance at banks. |
| Story | Circular trade: the same 6 containers go A → B → C → A in 4 months, the declared value rising each leg. |
| Reveal | *Pattern match* for a cycle: the three companies and the 6 containers in a loop. |
| Look-alike | A container-leasing firm's boxes moving between many unrelated shippers. |

| # | Picture | On-screen text | Voice-over |
|---|---|---|---|
| 1 | Bills of lading for 6 containers. | Trade finance | Six containers. Four months. Three countries. |
| 2 | Companies, trades and containers as a graph. | — | Draw who shipped what to whom. |
| 3 | *Pattern match*. | *Pattern match* | Ask where the containers went. |
| 4 | The loop A → B → C → A, values rising. | — | Round in a circle, and worth more on every leg. |
| 5 | End card. | *For trade finance.* | Inspecto LA. |

#### 3.10 · Crypto (`la-crypto`, planned)

| | |
|---|---|
| Audience | Compliance at virtual-asset service providers, and investigators. All addresses are invented. |
| Story | Converging cash-out: 40 KYC accounts at 2 exchanges, with unrelated names, deposit from one cluster and cash out to 3 bank accounts. |
| Reveal | *Tight groups* over deposits and withdrawals: the one cluster in, the 40 accounts, the 3 bank accounts out. |
| Look-alike | An over-the-counter desk whose many customers deposit their own funds. |
| Note | The peel chain story (80 hops) is the more dramatic shot, but check the Space's path-depth limit before scripting it. |

| # | Picture | On-screen text | Voice-over |
|---|---|---|---|
| 1 | Forty customer profiles, unrelated names. | Crypto | Forty customers. Two exchanges. Forty different names. |
| 2 | Deposits and withdrawals as a graph. | — | Draw where the coins came from, and where the cash went. |
| 3 | *Tight groups*. | *Tight groups* | Ask what they share. |
| 4 | One cluster in, 3 bank accounts out. | — | One pool of funds in. Three bank accounts out. |
| 5 | End card. | *For crypto compliance.* | Inspecto LA. |

### Insurance

#### 3.11 · Insurance (`la-claims`, built, Advanced install)

| | |
|---|---|
| Audience | Special Investigations Units at insurers. |
| Story | 12 claimants sharing 4 phones, 3 addresses, one garage, one clinic and one lawyer. |
| Reveal | *Tight groups*: the 12 claimants and the 9 shared contacts and parties as one group. |
| Look-alike | — (none listed for this story; show no other claimant as suspect). |
| Later cut | When the planned additions land, a second cut on the parts-supplier ring (one supplier on invoices from 9 garages; parts never fitted). Its look-alike is a high-volume supplier whose parts are all fitted. |

| # | Picture | On-screen text | Voice-over |
|---|---|---|---|
| 1 | Claims list. | Insurance | Twelve claims. Twelve different claimants. Nothing in common. |
| 2 | The claims graph, wide. | — | Draw them as links: people, phones, addresses, garages. |
| 3 | *Tight groups*. | *Tight groups* | Ask which of them belong together. |
| 4 | The ring. | — | Four phones. Three addresses. One garage, one clinic, one lawyer. |
| 5 | End card. | *For Special Investigations Units.* | Inspecto LA. |

### Utilities

#### 3.12 · Electricity (`la-utility`, built, Basic)

| | |
|---|---|
| Audience | Revenue-protection teams at electricity utilities. |
| Story | Meter tampering: 22 premises under 2 metered transformers (`TX-007`, `TX-019`) drop 40–70 % within 2 weeks of a visit by contractor `C-14`, while transformer supply stays flat. |
| Reveal | The contractor-network saved view: `C-14` at the centre of the 22 premises. |
| Look-alike | A holiday home whose use drops every winter, with no visit. |
| Optional shot | The loss-by-transformer Geo View, with `TX-007` and `TX-019` over 15 %: label it *Inspecto LA Standard*, because Geo Views are inert on Basic. |

| # | Picture | On-screen text | Voice-over |
|---|---|---|---|
| 1 | Consumption table, the drops visible. | Electricity | Twenty-two meters started reading low. The transformers didn't. |
| 2 | The contractor-network saved view, wide. | — | Draw premises, visits and contractors as links. |
| 3 | *Busiest nodes*. | *Busiest nodes* | Ask who they have in common. |
| 4 | `C-14` and the 22 premises. | — | One contractor visited them all, two weeks before the drop. |
| 5 | End card. | *For revenue protection.* | Inspecto LA. |

### Law enforcement

#### 3.13 · Police (`la-police`, built, Basic)

| | |
|---|---|
| Audience | Police intelligence analysts. |
| Story | A burglary series: 18 burglaries in 3 months across 2 districts; 40 % of the stolen items resurface at 2 dealers through 3 sellers; a vehicle kept by one seller's partner is seen near 11 scenes. |
| Reveal | The stolen-property saved view: scenes, items, dealers and sellers as one connected piece, with the vehicle joining it. |
| Look-alike | A taxi seen near 54 scenes across the year and the city, none of them in the series. It must not appear in the reveal. |
| Alternative cut | The drug-supply handset network: one controller number per period, its handset changing every 3–5 weeks, with 75–80 % of about 60 customers carried over (look-alike: a delivery driver's phone with many short calls and no carry-over). |

| # | Picture | On-screen text | Voice-over |
|---|---|---|---|
| 1 | Incident reports, two districts. | Police | Eighteen burglaries. Two districts. No suspect. |
| 2 | The stolen-property saved view, wide. | — | Draw what was taken, and where it was sold. |
| 3 | *Tight groups*. | *Tight groups* | Ask what connects the scenes. |
| 4 | Two dealers, three sellers, one vehicle near eleven scenes. | — | Two dealers. Three sellers. One car seen near eleven of the scenes. |
| 5 | End card. | *For intelligence analysts.* | Inspecto LA. |

### Public sector

#### 3.14 · Procurement (`la-procurement`, planned)

| | |
|---|---|
| Audience | Public-procurement integrity units and internal audit. |
| Story | Bid rotation: 4 firms win 24 tenders in one category in turn; losing bids are 5–12 % higher; 2 of the firms share a director. |
| Reveal | *Tight groups* over bids and directors: the 4 firms, with the shared director between two of them. |
| Look-alike | 3 large firms that win often but bid independently. |

| # | Picture | On-screen text | Voice-over |
|---|---|---|---|
| 1 | Tender awards, four names repeating. | Procurement | Twenty-four tenders. Four winners. Taking turns. |
| 2 | Bidders, tenders and directors as a graph. | — | Draw who bid, who won, and who runs them. |
| 3 | *Tight groups*. | *Tight groups* | Ask who links the winners. |
| 4 | The 4 firms and the shared director. | — | Two of them share a director. |
| 5 | End card. | *For procurement integrity.* | Inspecto LA. |

#### 3.15 · Benefits and tax (`la-benefits`, planned)

| | |
|---|---|
| Audience | Government benefits-integrity and tax-compliance teams. |
| Story | Address farm: 14 claimants under 5 surnames at one address, each declaring a single-person household, paid into 3 accounts. |
| Reveal | *Busiest nodes* over addresses: the one address, its 14 claimants, and the 3 bank accounts they are paid into. |
| Look-alike | A care home with many genuine residents and their own accounts. |

| # | Picture | On-screen text | Voice-over |
|---|---|---|---|
| 1 | Claims, each "single-person household". | Benefits | Fourteen claimants. Each one says they live alone. |
| 2 | Claimants, addresses and bank accounts as a graph. | — | Draw where they live and where they're paid. |
| 3 | *Busiest nodes*. | *Busiest nodes* | Ask what they share. |
| 4 | One address, 14 claimants, 3 accounts. | — | One address. Three bank accounts. |
| 5 | End card. | *For benefits integrity.* | Inspecto LA. |

### Healthcare

#### 3.16 · Healthcare billing (`la-health`, planned)

| | |
|---|---|
| Audience | Program-integrity teams at health insurers and public payers. |
| Story | Referral kickbacks: 2 general practitioners send 85 % of their referrals to one imaging centre owned by one doctor's spouse. |
| Reveal | *Expand* from the imaging centre to its owner: the spouse, and the doctor beside them. |
| Look-alike | A practice that refers to the only hospital nearby. |

| # | Picture | On-screen text | Voice-over |
|---|---|---|---|
| 1 | Referral counts, one centre far ahead. | Healthcare | Two doctors. Most of their referrals go to one place. |
| 2 | Doctors, referrals, providers and owners as a graph. | — | Draw who refers to whom, and who owns what. |
| 3 | *Expand* from the imaging centre. | *Expand* | Ask who owns the imaging centre. |
| 4 | The owner: one doctor's spouse. | — | One doctor's spouse. |
| 5 | End card. | *For program integrity.* | Inspecto LA. |

### Logistics

#### 3.17 · Logistics (`la-logistics`, planned)

| | |
|---|---|
| Audience | Logistics security teams and cargo insurers. |
| Story | Inside job: 12 losses share one driver pair, and one night-shift scanner is on 10 of them. |
| Reveal | *Tight groups* over trips, drivers and scanners: the 12 losses joined by the 2 drivers and the scanner. |
| Look-alike | A high-volume driver on the busiest route with 2 losses in a year. |

| # | Picture | On-screen text | Voice-over |
|---|---|---|---|
| 1 | Loss reports, different routes and months. | Logistics | Twelve loads lost. Different routes. Different months. |
| 2 | Trips, drivers and scanners as a graph. | — | Draw who drove, and who scanned. |
| 3 | *Tight groups*. | *Tight groups* | Ask what the losses share. |
| 4 | The 2 drivers and the night-shift scanner. | — | The same two drivers. The same night-shift scanner. |
| 5 | End card. | *For cargo security.* | Inspecto LA. |

### Enterprise

#### 3.18 · Internal audit (`la-audit`, built, Advanced install)

| | |
|---|---|
| Audience | Internal audit and accounts-payable control teams. |
| Story | Vendor `V-117` shares a bank account and an address with employee `E-31`. |
| Reveal | *Expand* from `V-117`: the bank account and address, and `E-31` on the other side of both. |
| Look-alike | — (none listed for this story). Do not film the unlinked sixth vendor of the shared-account story as part of it. |

| # | Picture | On-screen text | Voice-over |
|---|---|---|---|
| 1 | Paid invoices from vendor `V-117`. | Internal audit | A new vendor. Invoices approved. Payments made. |
| 2 | Vendors, employees, bank accounts and addresses as a graph. | — | Draw who is paid, and where the money lands. |
| 3 | *Expand* from `V-117`. | *Expand* | Ask what this vendor is linked to. |
| 4 | `E-31` on the shared bank account and address. | — | The same bank account. The same address. One of your own employees. |
| 5 | End card. | *For internal audit.* | Inspecto LA. |

#### 3.19 · Insider threat (`la-insider`, planned)

| | |
|---|---|
| Audience | Security, HR and internal investigations. All people and systems are invented. |
| Story | Access creep: manager `M-7` grants payroll and treasury access to 4 reports outside their role, with no ticket; one of them later edits a vendor's bank details. |
| Reveal | *Route from A to B* from the vendor bank-detail edit back to `M-7`, through the grant with no ticket. |
| Look-alike | A role change with an HR record and a ticket. |

| # | Picture | On-screen text | Voice-over |
|---|---|---|---|
| 1 | Audit log: a vendor's bank details changed. | Insider threat | A vendor's bank details changed. By someone who shouldn't have access. |
| 2 | People, grants and systems as a graph. | — | Draw who has access, and who gave it. |
| 3 | *Route from A to B* to `M-7`. | *Route from A to B* | Ask where the access came from. |
| 4 | `M-7`, 4 grants, no ticket. | — | One manager. Four grants. No ticket. |
| 5 | End card. | *For internal investigations.* | Inspecto LA. |

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

The introduction to the industry series: whatever the industry, the data is mapped onto the product the same way,
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
| 8 | 58–66 | Three fast cuts from the industry series: *Busiest nodes*, *Tight groups*, *Route from A to B*, each on a different industry's graph. | — | And then the same tools work on any data. Who is busiest. Who belongs together. How A reaches B. |
| 9 | 66–72 | **Industry index**: nine tiles (Telecom, Banking and finance, Insurance, Utilities, Law enforcement, Public sector, Healthcare, Logistics, Enterprise), each lighting in turn. | *Find your industry* | See it on your industry's data. |
| 10 | 72–75 | End card, with the index still visible. | *See who is connected.* | Inspecto LA. |

*Voice-over: 150 words, a little under 2.5 words a second.*

**The industry index (shot 9).** On the web and in the playlist each tile links to its industry videos in the series
above. In the video itself the tiles are a still; no URLs on screen.

| Tile | Leads to |
|---|---|
| Telecom | 3.1 Mobile money · 3.2 MFS · 3.3 Telco fraud · 3.4 Revenue assurance |
| Banking and finance | 3.5 First look · 3.6 Financial crime tour · 3.7 Ownership and sanctions · 3.8 Card fraud · 3.9 Trade finance · 3.10 Crypto |
| Insurance | 3.11 Claims fraud |
| Utilities | 3.12 Electricity |
| Law enforcement | 3.13 Police |
| Public sector | 3.14 Procurement · 3.15 Benefits and tax |
| Healthcare | 3.16 Billing |
| Logistics | 3.17 Cargo theft |
| Enterprise | 3.18 Internal audit · 3.19 Insider threat |

**Alternative cut (optional, 20 s).** *One file, two questions*: on the Data page, a transfers Dataset gets a second
**Mapping** (*account → channel* beside *account → account*), and both appear in the Start box without copying the
data. For analysts who already have their data in; not part of the main cut.

---

## Open points before filming

- Supply the CTA URL for the end card.
- Video 2: time the run on the final build and the filming laptop; set the title from the median.
- Industry series: check every reveal against the Space's ground truth file before filming. Film 3.2, 3.4, 3.9,
  3.10, 3.14–3.17 and 3.19 only after their Spaces are built, and re-read each script against the Space as built.
- Videos 3.3, 3.5, 3.6, 3.11 and 3.18 have no look-alike listed in the showcase plan for the story filmed; confirm
  with the Space's owner that nothing else on screen reads as suspect.
- Video 5, shot 7: waits for the `la-police` Advanced tier (its Alert Rule).
- Video 7: generate the two synthetic files (calls and transfers with a planted shared handful of numbers);
  product confirms which number formats *Compare values as: Phone number* treats as equal, and that Graph model's
  *Entity/Link (several Datasets)* is on Basic. Film it after the industry series it introduces, so shot 8 can use
  their footage.
- Filming needs both a Basic install (videos 2, 5, 7, 3.5, 3.7, 3.8, 3.12, 3.13) and an Advanced install (videos 1,
  4, 6, 3.1, 3.3, 3.6, 3.11, 3.18); on the Advanced install, film as a user who can reveal identifiers.
- Product and legal sign-off on every script before the agency starts.
