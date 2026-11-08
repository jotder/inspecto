# Inspecto LA short videos — scripts and agency briefs

> **Six concept videos and nine industry videos, filmed on the real product over synthetic data. Each one is an example: raw records become an investigation outcome in a few steps that anyone can repeat on their own data. For each video: a one-page brief for the agency, then a shot-by-shot script with voice-over.**

*Audience: marketing, presales and the video agency. Product Map Item `module.la-core`. Draft of 2026-11-08, not
yet reviewed by product or legal. Every product claim below is taken from the
[Link Analysis user guide](user-guide-link-analysis.md); every story, id and figure in the industry videos is
taken from the showcase plan (`docs/superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md`) and,
for a built Space, its generator's ground truth file. The [whitepaper](brochure-inspecto-la-whitepaper.md) is the
reference for anything wider.*

## The series in one line

Every video is an **example**: raw records go in, an investigation outcome comes out, in a few steps anyone can
repeat. A viewer should finish thinking *I could do that with my own records*. Every story follows the same five
beats, and every script below is written to them:

| Beat | What the viewer sees | What the voice does |
|---|---|---|
| **Raw** | The records as they really are: the actual file, unformatted. A corner tag *Example · synthetic data*. | Says in one short, blunt sentence what the records are, and why nobody can read the answer off them. |
| **Map** | The one screen where someone says what each column holds: *What does each column hold?* when onboarding a file, or the Dataset's *Entities* on the Data page. | Says it is done once, in plain words. |
| **Ask** | One analysis, its name on screen as the click happens. | Says the question, never the button. |
| **Outcome** | The finding, held, with its key ids and numbers on screen. | Says what it means and what the investigator does next: a lead to check, never a verdict. |
| **Your data** | *Your data · same steps* on screen, over the raw file turning into the graph. | Says the records were an example and the steps are the same on the viewer's own. |

## Contents

**Concept videos** — one idea each, cross-industry. Lengths are estimates.

| # | Title | Length (about) | Features shown | Film on | Data | Ready to film |
|---|---|---|---|---|---|---|
| 1 | The ring nobody saw | 50 s | Basic | Advanced install | `la-mobile-money` | Yes |
| 2 | Zero to graph | 65 s | Basic | Basic install | *Try the sample* | After the timing check |
| 4 | Retire the red string | 35 s | Basic | Advanced install | `la-mobile-money` | Yes |
| 5 | It stays current | 55 s | Basic, plus one Advanced shot | Basic install | `la-police` + its staged month | Yes (shot 7 later) |
| 6 | The analyst (dramatization) | 65 s | Standard | Standard or Advanced install | `la-mobile-money` | After casting |
| 7 | One graph for any data (introduces the industry videos) | 90 s | Basic | Basic install | Two prepared synthetic files, telecom and mobile money | After data preparation |

**Industry videos (video 3)** — one video per industry, nine in all, grouped as the showcase catalogue in the plan
is. Each holds every use case of its industry as a segment. `la-showcase` appears in two: its telecom rings under
Telecom, its banking stories under Banking and finance.

| # | Industry | Use cases (segments) | Length (about) | Film on | Ready to film |
|---|---|---|---|---|---|
| 3.1 | Telecom | Mobile money · MFS · Telco fraud · Revenue assurance | 75 s | Advanced | 2 of 4 segments (MFS, revenue assurance planned) |
| 3.2 | Banking and finance | First look · Financial crime · Ownership and sanctions · Card fraud · Trade finance · Crypto | 1 min 40 s | Advanced | 4 of 6 segments (trade, crypto planned) |
| 3.3 | Insurance | Claims fraud | 40 s | Advanced | Yes |
| 3.4 | Utilities | Electricity revenue protection | 40 s | Basic | Yes |
| 3.5 | Law enforcement | Criminal investigation | 40 s | Basic | Yes |
| 3.6 | Public sector | Procurement · Benefits and tax | 50 s | Basic | 1 of 2 segments (procurement planned) |
| 3.7 | Healthcare | Provider billing | 40 s | Basic | No: Space planned |
| 3.8 | Logistics | Cargo theft | 40 s | Basic | No: Space planned |
| 3.9 | Enterprise | Internal audit · Insider threat | 50 s | Advanced | 1 of 2 segments (insider planned) |

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

**The own-data message (binding).** Every video says once, near the end, that its records are an example and that the
viewer's own take the same steps. The standard line is *These records are examples. Drop in your own, say what each
column holds, and ask the same questions.* Concept video 7 shows those steps in full; the others point at them. Say
"same steps" only where it is true: product confirms, per story, that the outcome can be reached from the raw files
with *Add data* and the analysis shown. Where the Space also relies on something set up once (a Job that derives
links, a prepared saved view), that story's line says so: *…plus one Job, set up once.*

**Raw records (binding).**
- Show the real file the story was built from, unformatted, in a plain text viewer or the product's own preview, for
  about two seconds. Never a prettified mock-up, never a spreadsheet styled for the shot.
- The first raw beat of every video carries the corner tag *Example · synthetic data*.
- Explain the records in one blunt sentence: what one row is, and why the answer can't be read off them.
- The showcase files are raw but well-formed: they land with nothing set aside. Their mess is volume, opaque codes
  and benign noise (busy hubs, look-alikes). Broken rows (a number written two ways, a gap, a repeat) are shown in
  video 7, whose prepared files are planted with them. Never claim a showcase file is broken.

**The outcome (binding).** Every story ends on an outcome an investigator can act on: the lead, and the next step
(*one contractor to question, 22 premises to inspect*). It is a lead, never a verdict. No "fraudster", "criminal",
"caught" or "proven". The analyst finds it; the product points at it.

**Picture and voice (binding).** They complement each other; neither repeats the other.
- **Picture leads, voice follows.** The viewer sees the thing, then hears what it means, half a beat later.
- **The voice says what the picture can't:** what the records are, why they are hard, what the finding means, what
  happens next. It never describes what is plainly on screen ("here we see…") and never names the button being
  pressed; the on-screen label does that.
- **On-screen text is a label or a number,** a few words at most, never the sentence the voice is saying.
- **A figure or id the voice says is on screen at that moment.**
- **A reveal gets a beat of quiet** (the music accent) before the voice names it.
- **Muted test.** With the sound off, the captions carry the voice and the labels carry the steps: a muted viewer must
  still follow raw → ask → outcome. Check every cut both ways, muted and with sound.

**Filming rules (binding).**
- Film **only the synthetic showcase Spaces** named in this document, or (videos 5 and 7) a fresh Space made from the
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
deliberate pointer moves.

**Length and pace.** Every length in this document is an estimate, and the ≈ seconds in the scripts are a guide for
the edit, not a limit. Pace the voice for clarity (around 2.5 words a second reads well) and give the picture the time
it needs: each result stays on screen at least 1.5 s, each outcome at least 2 s. No video is cut to hit a number.

**Deliverables per video.** A 16:9 master; 9:16 and 1:1 cut-downs for social (re-framed on the graph, not
letterboxed); burned-in captions on every version, because most social plays start muted; a clean version
without captions; SRT files.

**Voice and audio.** One voice across the series. One music bed for the series, understated, with a single soft
accent on each reveal.

**End card (every video).** Inspecto LA logo · the line *See who is connected.* · *Try it on your own data.* (each
industry video adds its industry line) · `[CTA URL — to be supplied]`.

**Approvals.** Product signs off each script's claims before filming, and the final cut before release.

---

## 1 · The ring nobody saw (about 50 s)

**Brief**

| | |
|---|---|
| Objective | Show that links make visible what rows hide, in a few steps anyone can repeat. |
| Audience | Fraud and AML managers at banks and mobile-money operators. |
| Channel | LinkedIn, website hero, trade-show loop. |
| Message | A ring you can't see in the raw transfers is obvious as a graph, in a few clicks. Your own transfers work the same way. |
| Must show | The raw transfer file first; the Dataset's *Entities*; *Busiest nodes* and excluding the busy merchant; *Tight groups* finding the device farm; the outcome; the own-data line. |
| Outcome | Nine wallets on shared devices: one group to review, every link behind it on screen. |
| Must not | Claim the product "detects fraud on its own". The analyst finds it, and the product points at it. |
| Data | `la-mobile-money` (14 days of synthetic wallet transfers) on an Advanced install. The busy merchant is `MER-ELECTRIC` (a benign biller, the look-alike), and the planted ring is the nine-wallet device farm. Every feature shown is in Basic. |

**Script**

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 7 | The raw transfer file in a plain viewer, scrolling: wallet ids, amounts, timestamps, no formatting. | *Example · synthetic data* | Fourteen days of mobile-money transfers. One row each, and not one row says who works together. |
| 2 | 3 | The scroll stops on one ordinary row. | — | A ring is in here somewhere. |
| 3 | 4 | The Data page: the transfers Dataset open at *Entities*; the payer and payee columns hold the same Entity Type. | *Entities* | Say once what the columns hold: two wallets, one transfer. |
| 4 | 5 | *Start a graph* draws the Dataset; nodes settle into place. | *Start a graph* | Draw the rows as links… |
| 5 | 6 | One giant node dominates the centre. The right panel's *On screen* tab ▸ *Busiest nodes*: `MER-ELECTRIC` is first. | *Busiest nodes* | …and the first thing you meet is noise: a biller everyone pays. |
| 6 | 4 | Right-click `MER-ELECTRIC` ▸ *Exclude…*. The hub disappears and the graph loosens. | *Exclude* | Set it aside. |
| 7 | 6 | *Tight groups*: *Show on graph* on the first group. Nine wallets light up, tightly knotted; everything else dims. | *Tight groups* | Then ask who moves money only among themselves. |
| 8 | 6 | A beat of quiet on the nine, then slow push-in. | *9 wallets · shared devices* | Nine wallets on shared devices. One group to review, every link behind it on screen. |
| 9 | 7 | The raw file for a second, dissolving into the graph. | *Your data · same steps* | These records are examples. Drop in your own, say what each column holds, and ask the same questions. |
| 10 | 4 | End card. | *See who is connected.* · *Try it on your own data.* | Inspecto LA. |

---

## 2 · Zero to graph (about 65 s)

**Brief**

| | |
|---|---|
| Objective | Remove the fear that a graph tool is heavy to install and slow to learn. |
| Audience | Analysts and team leads at smaller teams; IT evaluators. |
| Channel | Website "Get started" page, YouTube, product launch email. |
| Message | Unzip, start, and your first graph and your first lead are a minute or so away. No cluster, no cloud, no consultants. |
| Must show | A real on-screen stopwatch running from the unzip to the drawn graph, with no cuts while it runs; then a first finding on the sample. |
| Outcome | The route from one small account to `OFFSHORE-77`, through a hub and a relay: the sample's planted story, found in one click. |
| Must not | Cut time out while the stopwatch runs, or state a time the stopwatch did not show. The title may add the measured time (*Zero to graph in 52 seconds*), never a target. |
| Data | *Try the sample* (`la-sample`) on a fresh Basic install with no Spaces. The sample graph has 112 entities and 224 links (measured on a Basic bundle on 2026-11-07). Its story: 12 smurf accounts structure cash into `MULE-HUB-01`, on through `RELAY-01`/`RELAY-02` to `OFFSHORE-77`. |
| Before filming | Time the run three times on the filming laptop with the final build; use the median wherever a time is said or shown. |

**Script**

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 4 | A laptop desktop, one zip file on it. A stopwatch appears in the corner at 00:00. | 00:00 | How long until you see your first link chart? |
| 2 | 8 | Stopwatch starts. Unzip the bundle; the folder opens. | — | Unzip it. |
| 3 | 8 | Run the start script; a console window shows the server starting. | — | Start it. One process, on your own machine. |
| 4 | 8 | The browser opens on *Getting started*. The pointer rests on *Try the sample*. | *Getting started* | The first page tells you what to do. |
| 5 | 10 | Click *Try the sample*. The graph draws: 112 entities, 224 links. The stopwatch stops on the drawn graph and stays in frame. | *Try the sample* · `[measured time]` | One click. No cluster, no cloud, no consultants. |
| 6 | 4 | The Data page: the Dataset the sample drew from, its columns as they came: account ids, amounts, dates. | *Example · synthetic data* | Underneath, plain transfer records. |
| 7 | 7 | Back on the graph: *Route from A to B* from a smurf account to `OFFSHORE-77`; the path lights through `MULE-HUB-01` and a relay. | *Route from A to B* | Ask where a small account's money ends up… |
| 8 | 5 | A beat of quiet on the route. | *Smurf → hub → relay → OFFSHORE-77* | …offshore, through one hub and a relay. That's your first lead. |
| 9 | 6 | Click the light bulb: *What can I do next?* opens with its suggestions. | *Your data · same steps* | The sample is an example. Next, add your own file; it shows you each step. |
| 10 | 4 | End card. | *See who is connected.* · *Try it on your own data.* | Inspecto LA. |

---

## 3 · Industry videos — one per industry (9 videos)

One video per industry, grouped as the showcase catalogue in the plan is. Each video holds **every use case of its
industry as a segment**. The agency builds one segment template and reuses it everywhere.

**Brief (shared)**

| | |
|---|---|
| Objective | One video per industry, so sales can send the one that fits the prospect and it shows every case that industry has. |
| Introduced by | Concept video 7, *One graph for any data*: it shows how any domain's raw data becomes the same graph, and its last shot is the industry index that leads into these nine videos. On a website or playlist, video 7 plays first and each industry video links back to it. |
| Audience | Named per video below. |
| Channel | Sales email, LinkedIn targeted by industry, trade-show loop per stand. |
| Message | Different cases, the same few steps: raw records, one question, a lead to act on. Your records work the same way. |
| Shape | **Opening** (the industry in one line, over its raw records) → **Map**, once per video (one of its Datasets at *Entities*) → **one segment per use case** → **Your data** → end card with the industry line. The length follows the number of segments. |
| Segment | Three beats, roughly 12–15 s in all: **Raw** (that use case's file as it landed, the use-case label on screen, one blunt sentence), **Ask** (the Space's graph or saved view and the one analysis named), **Outcome** (the finding held at least 2 s, its ids and numbers on screen; the voice says the finding and the next step). |
| Modular | Segments are filmed separately. A video whose Spaces are all built is filmed now; a planned Space's segment is filmed when the Space is built and cut in. Until then, publish the video with its built segments only, or hold it (the owner decides per industry). |
| Film on | One install per video: an **Advanced** install if any of its segments needs one (`la-mobile-money`, `la-claims`, `la-showcase`, `la-audit` need one today), otherwise Basic. On Advanced, film as a user who can reveal identifiers. |
| Must show | Each reveal exactly as the Space's ground truth file lists it: the same entities, nothing added. |
| Must not | Use real place, company or person names. Show a story's look-alike as suspicious. Present an outcome as a verdict. |
| Before filming | Check each reveal against the Space's ground truth file. Pick each Raw beat's file and rows: the file the story was built from, ideally rows that hold the story's ids. For a planned Space, re-read its segment against the Space as built: ids and figures can move during the build. |

**Segment template**

| Beat | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| Raw | 3 | The use case's records as they landed, unformatted. | [Use case] | [What the records are, bluntly] |
| Ask | 5 | The Space's graph or saved view; the analysis named in the segment. | [Analysis name] | [The question] |
| Outcome | 5 | The finding, held, after a beat of quiet. | [Key ids and numbers] | [The finding, and the next step] |

The opening, map and close are the same in every multi-segment video:

| Beat | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| Opening | 5 | The raw files of every segment flash past, unformatted. | [Industry] · *Example · synthetic data* | [The industry in one line] |
| Map | 5 | The Data page: one of the industry's Datasets open at *Entities*, each column and what it holds. | *Entities* | Each of these began as a raw export. You say once what each column holds; then you ask. |
| Your data | 7 | One raw file dissolving into its graph. | *Your data · same steps* | These records are examples. Drop in your own, say what each column holds, and ask the same questions. |
| End card | 4 | End card. | [Industry line] · *Try it on your own data.* | Inspecto LA. |

### 3.1 · Telecom (about 75 s)

| | |
|---|---|
| Audience | Fraud, risk and revenue-assurance teams at mobile operators and their mobile financial services (MFS) businesses. |
| Film on | Advanced install (`la-mobile-money`, `la-showcase`). |
| Ready | Segments A and C now; B and D when `la-mfs` and `la-telco-ra` are built. |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | Mobile money | `la-mobile-money` (built) | Wallet top-ups and transfers. | Agent-assisted structuring: 25 wallets top up through agent `AG-18`. | `MER-ELECTRIC` excluded, then *Busiest nodes*: `AG-18` at the centre of the 25. | An agent to review; the 25 wallets as the evidence. | `MER-ELECTRIC`, a biller everyone pays: shown only as the noise set aside. |
| B | MFS | `la-mfs` (planned) | SIM-swap events and wallet transfers. | SIM-swap wallet takeover: 30 SIMs swapped through one call-centre login, `S-41`, in 10 days; each wallet drained within 2 hours to 5 mule wallets. | *Busiest nodes* over the swaps: `S-41` first; *Show on graph* adds the mule wallets. | A login to investigate; 5 mule wallets to trace. | A busy store doing many genuine SIM swaps for customers who keep their balances. |
| C | Telco fraud | `la-showcase` (built) | Call records. | One planted ring per typology (Wangiri, IRSF, SIM box, subscription fraud, mules); the segment follows a suspect to the SIM box. | Saved view `telecom_fraud_network`, *Route from A to B* from `99979100001` to the SIM box: 4 hops. | The route, link by link, for the report. | — (none listed; show no other number as suspect). |
| D | Revenue assurance | `la-telco-ra` (planned) | SIM activations and top-ups. | Dealer commission fraud: a dealer activates 400 SIMs on 3 devices; each tops up once and goes dormant. | *Busiest nodes* over activations: the dealer and its 3 devices. | A dealer's commissions to audit. | A dealer near a university whose term-start spike stays active. |

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 5 | Opening: top-ups, swap logs, call records and activations flash past, raw. | Telecom · *Example · synthetic data* | Calls, wallets, SIMs and dealers. Four ways money leaks out of a network. |
| 2 | 5 | Map. | *Entities* | Each of these began as a raw export. You say once what each column holds; then you ask. |
| A1 | 3 | Wallet top-ups as they landed. | *Mobile money* | Top-ups, one row each, all just under the limit. |
| A2 | 5 | The `la-mobile-money` graph: `MER-ELECTRIC` excluded, then *Busiest nodes*. | *Busiest nodes* | Set aside the biller everyone pays, and ask who sits in the middle. |
| A3 | 5 | `AG-18` at the centre of the 25 wallets, held. | *AG-18 · 25 wallets* | One agent behind all twenty-five: an agent to review, the wallets as the evidence. |
| B1 | 3 | SIM-swap events and wallet transfers as they landed. | *MFS* | SIM swaps in one file, wallet transfers in another. |
| B2 | 5 | *Busiest nodes* over the swaps; *Show on graph* adds the mule wallets. | *Busiest nodes* | Ask who did the swaps behind the emptied wallets. |
| B3 | 5 | `S-41` first, its 30 swaps and the 5 mule wallets, held. | *S-41 · 30 SIMs · 5 mule wallets* | One call-centre login swapped every SIM; each wallet was drained within two hours. A login to investigate, five wallets to trace. |
| C1 | 3 | Call records as they landed. | *Telco fraud* | Call records. Every row looks like every other. |
| C2 | 5 | Saved view `telecom_fraud_network`; *Route from A to B* from `99979100001` to the SIM box. | *Route from A to B* | Ask how one suspect number reaches the SIM box. |
| C3 | 5 | The route held, link by link. | *4 hops* | Four hops, every link on screen: the route for the report. |
| D1 | 3 | SIM activations as they landed. | *Revenue assurance* | New activations, each one earning a dealer commission. |
| D2 | 5 | *Busiest nodes* over activations. | *Busiest nodes* | Ask who is behind the SIMs that top up once and go quiet. |
| D3 | 5 | The dealer and its 3 devices, held. | *1 dealer · 3 devices · 400 SIMs* | One dealer, three devices: commissions to audit. |
| 3 | 7 | Your data. | *Your data · same steps* | These records are examples. Drop in your own, say what each column holds, and ask the same questions. |
| 4 | 4 | End card. | *For telecom fraud and revenue assurance.* · *Try it on your own data.* | Inspecto LA. |

### 3.2 · Banking and finance (about 1 min 40 s)

| | |
|---|---|
| Audience | Fraud, AML, KYC, sanctions and trade-finance compliance teams at banks, payment providers and virtual-asset service providers. |
| Film on | Advanced install (`la-showcase`). |
| Ready | Segments A–D now; E and F when `la-trade` and `la-crypto` are built. |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | First look | `la-sample` (built) | Transfers between accounts. | 12 smurf accounts structure cash into `MULE-HUB-01`, on through `RELAY-01`/`RELAY-02` to `OFFSHORE-77`. | *Route from A to B* from a smurf to `OFFSHORE-77`, through the hub and a relay. | The route to report, hub and relays named. | — (the sample has none; imply no other account is suspect). |
| B | Financial crime tour | `la-showcase` (built) | Payments and agent-till cash-outs. | Agent till `TILL-06` takes 80 % of the cash-out. | *Busiest nodes* over cash-out links: `TILL-06` first, its share in the sentence. | A till to review. | — (none listed; show no other till as suspect). |
| C | Ownership and sanctions | `la-ownership` (built) | A company register of ownership stakes, and an (invented) sanctions list. | A customer's 50 % owner is 60 % owned by a person on the sanctions list. | *Route from A to B* from the customer to the listed person, the stakes on the links. | A customer to escalate for review. | A near-namesake with a different birth year and nationality: never shown as a match. |
| D | Card fraud | `la-card` (built) | Card payments and fraud claims. | Common point of purchase: 60 cards defrauded 1–6 weeks after paying merchant `M-317`. | The common-point saved view, *Busiest nodes*: `M-317` first, linked to all 60. | The likely point of compromise to investigate. | A busy supermarket used by many cards with no later fraud. |
| E | Trade finance | `la-trade` (planned) | Bills of lading. | Circular trade: the same 6 containers go A → B → C → A in 4 months, the value rising each leg. | *Pattern match* for a cycle: the three companies and the 6 containers in a loop. | Three companies to look at. | A container-leasing firm's boxes moving between many unrelated shippers. |
| F | Crypto | `la-crypto` (planned) | Exchange deposits and withdrawals. | Converging cash-out: 40 KYC accounts at 2 exchanges deposit from one cluster and cash out to 3 bank accounts. | *Tight groups*: one cluster in, the 40 accounts, 3 bank accounts out. | Three bank accounts where the money goes next. | An over-the-counter desk whose many customers deposit their own funds. |

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 5 | Opening: transfers, a register, card payments, bills of lading and exchange records flash past, raw. | Banking and finance · *Example · synthetic data* | Accounts, owners, cards, cargo and coins. Six places money hides. |
| 2 | 5 | Map. | *Entities* | Each of these began as a raw export. You say once what each column holds; then you ask. |
| A1 | 3 | Transfers as they landed. | *First look* | Small deposits from twelve accounts, each one under the limit. |
| A2 | 5 | *Route from A to B* from a smurf to `OFFSHORE-77`. | *Route from A to B* | Ask where the money ends up. |
| A3 | 5 | The route held. | *MULE-HUB-01 → relay → OFFSHORE-77* | Through one hub and a relay to one offshore account: the route to report. |
| B1 | 3 | Cash-out records as they landed. | *Financial crime* | Cash-outs at agent tills, row after row. |
| B2 | 5 | *Busiest nodes* over cash-out links. | *Busiest nodes* | Ask where the money leaves. |
| B3 | 5 | `TILL-06` first, held. | *TILL-06 · 80 % of cash-out* | One till takes eighty percent of it: a till to review. |
| C1 | 3 | The ownership register as it landed. | *Ownership and sanctions* | A company register: who owns what, and how much. |
| C2 | 5 | *Route from A to B* from the customer to the listed person. | *Route from A to B* | Ask whether the customer leads to anyone on the sanctions list. |
| C3 | 5 | The route held, the stakes on its links. | *50 % · 60 % · listed* | The customer is clean, and so is its owner. Two hops up, a listed person: a customer to escalate. |
| D1 | 3 | Card payments and fraud claims as they landed. | *Card fraud* | Sixty fraud claims, and months of card payments. |
| D2 | 5 | The common-point saved view; *Busiest nodes*. | *Busiest nodes* | Ask where those cards had been before. |
| D3 | 5 | `M-317` linked to all 60, held. | *M-317 · 60 cards* | One merchant, one to six weeks before each fraud: the likely point of compromise. |
| E1 | 3 | Bills of lading as they landed. | *Trade finance* | Bills of lading: container, shipper, buyer, value. |
| E2 | 5 | *Pattern match* for a cycle. | *Pattern match* | Ask for goods that come back to where they started. |
| E3 | 5 | The loop held. | *6 containers · A → B → C → A* | Round in a circle in four months, worth more on every leg: three companies to look at. |
| F1 | 3 | Exchange deposits and withdrawals as they landed. | *Crypto* | Deposits at two exchanges, under forty different names. |
| F2 | 5 | *Tight groups*. | *Tight groups* | Ask which of these accounts move together. |
| F3 | 5 | One cluster in, the 40 accounts, 3 bank accounts out, held. | *1 cluster · 40 accounts · 3 bank accounts* | One pool of funds in, three bank accounts out: where to follow the money next. |
| 3 | 7 | Your data. | *Your data · same steps* | These records are examples. Drop in your own, say what each column holds, and ask the same questions. |
| 4 | 4 | End card. | *For financial crime and compliance.* · *Try it on your own data.* | Inspecto LA. |

### 3.3 · Insurance (about 40 s)

| | |
|---|---|
| Audience | Special Investigations Units at insurers. |
| Film on | Advanced install (`la-claims`). |
| Ready | Now. When the planned `la-claims` additions land, a second segment (parts-supplier ring: one supplier on invoices from 9 garages, parts never fitted; look-alike a high-volume supplier whose parts are all fitted) adds about 13 s. |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | Claims fraud | `la-claims` (built) | A claims export: each claim with its people, phones, addresses and providers. | 12 claimants sharing 4 phones, 3 addresses, one garage, one clinic and one lawyer. | *Tight groups*: the 12 claimants and what they share, as one group. | One ring of 12 claims to refer to the investigators. | — (none listed; show no other claimant as suspect). |

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 5 | The claims export as it landed, scrolling. | Insurance · *Example · synthetic data* | A claims export. Read row by row, every claim looks like its own. |
| 2 | 5 | The Data page: the claims Dataset at *Entities*; the phone, address and garage columns each hold an Entity Type. | *Entities* | Say once what the columns hold: people, phones, addresses, garages. |
| 3 | 5 | The claims graph, wide; *Tight groups*. | *Tight groups* | Ask which claims belong together. |
| 4 | 8 | A beat of quiet; the ring held. | *12 claimants · 4 phones · 3 addresses* | Twelve claimants sharing four phones, three addresses, one garage, one clinic and one lawyer. One ring to refer. |
| 5 | 7 | Your data. | *Your data · same steps* | These records are examples. Drop in your own, say what each column holds, and ask the same questions. |
| 6 | 4 | End card. | *For Special Investigations Units.* · *Try it on your own data.* | Inspecto LA. |

### 3.4 · Utilities (about 40 s)

| | |
|---|---|
| Audience | Revenue-protection teams at electricity utilities. |
| Film on | Basic install. |
| Ready | Now. |
| Optional shot | The loss-by-transformer Geo View, `TX-007` and `TX-019` over 15 %: label it *Inspecto LA Standard* (Geo Views are inert on Basic) and film it on a Standard or higher install. |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | Electricity revenue protection | `la-utility` (built) | Meter reads and a log of field visits. | 22 premises under transformers `TX-007` and `TX-019` drop 40–70 % within 2 weeks of a visit by contractor `C-14`; transformer supply stays flat. | Contractor-network saved view, *Busiest nodes*: `C-14` at the centre of the 22. | 22 premises to inspect, one contractor to question. | A holiday home whose use drops every winter, with no visit. |

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 6 | Meter reads as they landed, the field-visit log beside them. | Utilities · *Example · synthetic data* | Meter reads and a log of field visits. Twenty-two meters started reading low; the transformers didn't. |
| 2 | 5 | The Data page: a Dataset at *Entities*. | *Entities* | Say once what the columns hold: premises, visits, contractors. |
| 3 | 5 | The contractor-network saved view, wide; *Busiest nodes*. | *Busiest nodes* | Ask what the low meters have in common. |
| 4 | 7 | A beat of quiet; `C-14` and the 22 premises held. | *C-14 · 22 premises* | One contractor visited them all, two weeks before each drop. Twenty-two premises to inspect, one contractor to question. |
| 5 | 7 | Your data. | *Your data · same steps* | These records are examples. Drop in your own, say what each column holds, and ask the same questions. |
| 6 | 4 | End card. | *For revenue protection.* · *Try it on your own data.* | Inspecto LA. |

### 3.5 · Law enforcement (about 40 s)

| | |
|---|---|
| Audience | Police intelligence analysts. |
| Film on | Basic install. |
| Ready | Now. |
| Alternative segment | The drug-supply handset network: one controller number per period, its handset changing every 3–5 weeks, 75–80 % of about 60 customers carried over (look-alike: a delivery driver's phone with many short calls and no carry-over). |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | Criminal investigation | `la-police` (built) | Incident reports, stolen-property records, dealer purchase logs and vehicle sightings. | Burglary series: 18 burglaries in 3 months across 2 districts; 40 % of the items resurface at 2 dealers through 3 sellers; a vehicle kept by one seller's partner is seen near 11 scenes. | Stolen-property saved view, *Tight groups*: scenes, items, dealers and sellers as one piece, the vehicle joining it. | Three lines of enquiry: the dealers, the sellers, the vehicle. | A taxi seen near 54 scenes across the year and the city, none in the series. |

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 6 | Incident reports and property records as they landed. | Law enforcement · *Example · synthetic data* | Incident reports, property records, dealer logs. Eighteen burglaries, two districts, no suspect. |
| 2 | 5 | The Data page: a Dataset at *Entities*. | *Entities* | Say once what the columns hold: items, people, places, vehicles. |
| 3 | 5 | The stolen-property saved view, wide; *Tight groups*. | *Tight groups* | Ask what connects the scenes. |
| 4 | 7 | A beat of quiet; two dealers, three sellers, one vehicle held. | *2 dealers · 3 sellers · 1 vehicle* | Two dealers, three sellers, and one car seen near eleven of the scenes. Three lines of enquiry. |
| 5 | 7 | Your data. | *Your data · same steps* | These records are examples. Drop in your own, say what each column holds, and ask the same questions. |
| 6 | 4 | End card. | *For intelligence analysts.* · *Try it on your own data.* | Inspecto LA. |

### 3.6 · Public sector (about 50 s)

| | |
|---|---|
| Audience | Public-procurement integrity units, benefits-integrity and tax-compliance teams. |
| Film on | Basic install. |
| Ready | Segment B now; A when `la-procurement` is built. |
| Alternative segments | `la-benefits` holds three more built stories, each with its own saved view and look-alike: payments to the deceased, undeclared work, and a tax-preparer refund ring (exercises 2–4 of its [user guide](../../solution-pack/la-benefits/user-guide.md)). |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | Procurement | `la-procurement` (planned) | Tender awards and a company register. | Bid rotation: 4 firms win 24 tenders in one category in turn; 2 of them share a director. | *Tight groups* over bids and directors: the 4 firms and the shared director. | A bidding pattern to refer, with the shared director named. | 3 large firms that win often but bid independently. |
| B | Benefits and tax | `la-benefits` (built) | Benefit claims and household declarations. | Address farm: `AD-03141` has 14 residents, all claimants under 5 surnames, each declaring a household of one; all 14 are paid into 3 accounts (`SYN-57707034`, `SYN-19642862`, `SYN-47766627`) that nobody else uses. | Saved view `benefits_shared_addresses` (every address with 8 or more claimants): three care homes drawn as stars, and `AD-03141`. Click `AD-03141` for its node detail; its 14 claims meet in the 3 accounts. Not *Busiest nodes*: it would rank the larger care homes first. | 14 claims and 3 accounts to review. | The three care homes in the same view (`AD-00412` Linden Court, 31 residents; `AD-02718` Harbour View, 24; `AD-05077` Orchard House, 27): one declaration each, every resident paid into their own account. They are on screen, so the voice-over names them as genuine. |

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 5 | Opening: tender awards and benefit claims flash past, raw. | Public sector · *Example · synthetic data* | Public money, two ways to take it. |
| 2 | 5 | Map. | *Entities* | Each of these began as a raw export. You say once what each column holds; then you ask. |
| A1 | 3 | Tender awards and the company register as they landed. | *Procurement* | Tender awards, and a register of company directors. |
| A2 | 5 | *Tight groups* over bids and directors. | *Tight groups* | Ask who wins together. |
| A3 | 5 | The 4 firms and the shared director, held. | *4 firms · 24 tenders · 1 director* | Four firms taking turns to win, two of them under one director: a pattern to refer. |
| B1 | 3 | Benefit claims and household declarations as they landed. | *Benefits and tax* | Claims and household declarations: a name, an address, a bank account. |
| B2 | 6 | Saved view `benefits_shared_addresses`: four addresses; three draw as big stars. | *Saved view* | Ask which addresses hold eight or more claimants. Three are care homes, each resident paid into their own account. |
| B3 | 6 | Click `AD-03141`: its node detail; then its 14 claims meeting in 3 accounts, held. | *AD-03141 · 14 claimants · 3 accounts* | The fourth is a house: fourteen people, each living alone, paid into three accounts. Claims to review. |
| 3 | 7 | Your data. | *Your data · same steps* | These records are examples. Drop in your own, say what each column holds, and ask the same questions. |
| 4 | 4 | End card. | *For public-sector integrity.* · *Try it on your own data.* | Inspecto LA. |

### 3.7 · Healthcare (about 40 s)

| | |
|---|---|
| Audience | Program-integrity teams at health insurers and public payers. |
| Film on | Basic install. |
| Ready | When `la-health` is built. |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | Provider billing | `la-health` (planned) | Referral records and a register of provider owners. | Referral kickbacks: 2 general practitioners send 85 % of their referrals to one imaging centre owned by one doctor's spouse. | *Expand* from the imaging centre to its owner: the spouse, and the doctor beside them. | A conflict of interest to look into. | A practice that refers to the only hospital nearby. |

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 6 | Referral records as they landed. | Healthcare · *Example · synthetic data* | Referral records: which doctor sent which patient where. Two doctors send most of theirs to one place. |
| 2 | 5 | The Data page: a Dataset at *Entities*. | *Entities* | Say once what the columns hold: doctors, providers, owners. |
| 3 | 5 | Doctors, referrals, providers and owners as a graph; *Expand* from the imaging centre. | *Expand* | Ask who owns the imaging centre. |
| 4 | 6 | A beat of quiet; the owner held, beside the doctor. | *Owner: a referring doctor's spouse* | The spouse of one of the doctors: a conflict of interest to look into. |
| 5 | 7 | Your data. | *Your data · same steps* | These records are examples. Drop in your own, say what each column holds, and ask the same questions. |
| 6 | 4 | End card. | *For program integrity.* · *Try it on your own data.* | Inspecto LA. |

### 3.8 · Logistics (about 40 s)

| | |
|---|---|
| Audience | Logistics security teams and cargo insurers. |
| Film on | Basic install. |
| Ready | When `la-logistics` is built. |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | Cargo theft | `la-logistics` (planned) | Trip logs, scan logs and loss reports. | Inside job: 12 losses share one driver pair, and one night-shift scanner is on 10 of them. | *Tight groups*: the 12 losses joined by the 2 drivers and the scanner. | Three people to interview. | A high-volume driver on the busiest route with 2 losses in a year. |

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 6 | Trip logs and loss reports as they landed. | Logistics · *Example · synthetic data* | Trip logs, scan logs, and twelve lost loads on different routes in different months. |
| 2 | 5 | The Data page: a Dataset at *Entities*. | *Entities* | Say once what the columns hold: loads, drivers, scanners. |
| 3 | 5 | Trips, drivers and scanners as a graph; *Tight groups*. | *Tight groups* | Ask what the losses share. |
| 4 | 6 | A beat of quiet; the 2 drivers and the scanner held. | *12 losses · 2 drivers · 1 scanner* | The same two drivers, and the same night-shift scanner on ten of them: three people to interview. |
| 5 | 7 | Your data. | *Your data · same steps* | These records are examples. Drop in your own, say what each column holds, and ask the same questions. |
| 6 | 4 | End card. | *For cargo security.* · *Try it on your own data.* | Inspecto LA. |

### 3.9 · Enterprise (about 50 s)

| | |
|---|---|
| Audience | Internal audit, accounts-payable control, security, HR and internal investigations. All people and systems are invented. |
| Film on | Advanced install (`la-audit`). |
| Ready | Segment A now; B when `la-insider` is built. |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | Internal audit | `la-audit` (built) | Vendor master data and staff records. | Vendor `V-117` shares a bank account and an address with employee `E-31`. | *Expand* from `V-117`: the account and address, `E-31` behind both. | A conflict of interest to investigate. | — (none listed; do not film the unlinked sixth vendor of the shared-account story as part of it). |
| B | Insider threat | `la-insider` (planned) | Access grants and a change log. | Access creep: manager `M-7` grants payroll and treasury access to 4 reports with no ticket; one later edits a vendor's bank details. | *Route from A to B* from the bank-detail edit back to `M-7`, through the grant with no ticket. | An access review to start with one manager. | A role change with an HR record and a ticket. |

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 5 | Opening: vendor records and an access log flash past, raw. | Enterprise · *Example · synthetic data* | The threat inside the company. |
| 2 | 5 | Map. | *Entities* | Each of these began as a raw export. You say once what each column holds; then you ask. |
| A1 | 3 | Vendor records and staff records as they landed. | *Internal audit* | Vendor records and staff records, kept in different systems. |
| A2 | 5 | *Expand* from `V-117`. | *Expand* | Ask what the new vendor is connected to. |
| A3 | 5 | The account and address, `E-31` behind both, held. | *V-117 · E-31* | The same bank account and address as one of your own employees: a conflict to investigate. |
| B1 | 3 | Access grants and the change log as they landed. | *Insider threat* | Access grants, and a log of changes. |
| B2 | 5 | *Route from A to B* from the bank-detail edit back to `M-7`. | *Route from A to B* | Ask how the person who changed a vendor's bank details got the access. |
| B3 | 5 | The route held, through the grant with no ticket. | *M-7 · 4 grants · no ticket* | One manager, four grants, no ticket: an access review to start. |
| 3 | 7 | Your data. | *Your data · same steps* | These records are examples. Drop in your own, say what each column holds, and ask the same questions. |
| 4 | 4 | End card. | *For internal audit and investigations.* · *Try it on your own data.* | Inspecto LA. |

---

## 4 · Retire the red string (about 35 s)

**Brief**

| | |
|---|---|
| Objective | The most shareable cut: one image that explains the product with almost no words. |
| Audience | Broad: anyone who has investigated anything. |
| Channel | Social (9:16 first), conference screens. |
| Message | The corkboard is how investigators used to think. Inspecto LA is how they think now, on their own records. |
| Must show | Split screen. Left: a real, physical corkboard with printouts, sticky notes and red string, shot practically (not CGI); the printouts are real rows of the Space's raw transfer file. Right: the same wallets as a graph. |
| Outcome | The cash-out agent at the end of the chain. |
| Data | `la-mobile-money` on an Advanced install. The route is the planted 98 % pass-through mule chain that ends at agent `AG-39`; take its first wallet from the Space's ground truth. Every feature shown is in Basic. |
| Casting | Hands only, no faces. |

**Script**

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 5 | Left only: hands pin a printout of raw transfer rows to a corkboard and wind red string between two of them. | *Example · synthetic data* | *(no voice-over; the sound of a pin going in)* |
| 2 | 5 | Split screen opens. Right: the same wallets as a graph. | — | You've seen this board before. |
| 3 | 8 | Left: hands hunt for the next string. Right: *Route from A to B* ▸ *Find route*. | *Route from A to B* | From the first wallet to whoever cashed out… |
| 4 | 6 | Right: the chain to `AG-39` lights up, link by link. Left: the hands stop. | *AG-39* | …one click. That agent is your next call. |
| 5 | 4 | Left fades out; the graph holds. | *Your data · same steps* | Bring your own records. |
| 6 | 4 | End card. | *Retire the red string.* · *Try it on your own data.* | Inspecto LA. |

---

## 5 · It stays current (about 55 s)

**Brief**

| | |
|---|---|
| Objective | Show that a view is a question about the data, not a frozen picture. |
| Audience | Analysts who run the same checks every week or month. |
| Channel | Website, sales follow-up email. |
| Message | Next month's files land in a folder; your saved view shows what changed when you open it. Your own files work the same way. |
| Must show | Files landing in the watched folder and one opened raw; *What changed in your data*; reopening a saved view; *Watch this folder* on a Dataset. The Alert shot only with its edition label. |
| Outcome | One more stolen item tied to a seller already in the series. |
| Must not | Say the graph redraws by itself while it is open. It re-reads the data when the view is opened. |
| Data | `la-police` on a Basic install. It lands 12 months, 2025-11-01 to 2026-10-31; its staged month (November 2026, in `data/staged/<feed>`) adds one more serial match by a seller in the burglary series. The `police_links` Job folds new files in every 5 minutes: cut the wait in the edit, never fake the result. Shot 8 is filmed on a Dataset added with *Add data* (the video 7 Space's calls Dataset): `la-police`'s Datasets are kept by Pipelines and have no inbox switch. |
| Edition | Shots 1–6 and 8 Basic. Shot 7 (the Alert) **Advanced**: the police Advanced tier specifies an Alert Rule on new serial matches but is not built yet. Cut shot 7 until it is, and for a Basic-only version. |

**Script**

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 6 | The stolen-property saved view open on the burglary series. | — | Last month you linked the series and saved the view. |
| 2 | 6 | A file explorer: November's files are copied into the inbox folders; one opens in a plain viewer, raw rows. | *Example · synthetic data* | This month's records arrive as plain files: items, sellers, dates. |
| 3 | 6 | *Getting started*: *What changed in your data* shows the new files were brought in. | *What changed in your data* | Inspecto LA brings them in and tells you what changed. |
| 4 | 5 | *My work* ▸ open the saved view. The graph redraws over the new data. | *My work* | Open the same view… |
| 5 | 7 | A beat of quiet; the new serial match highlighted: a stolen item, sold by one of the series sellers. | *1 new match* | …and another stolen item turns up, sold by a seller you already know. |
| 6 | 5 | Click it: the node detail dialog. | — | Same question, fresh answer: one more item for the series. |
| 7 | 4 | **Advanced, when built:** an Alert raised by the Alert Rule on new serial matches. | *Inspecto LA Advanced* | Or let an Alert Rule ask it for you. |
| 8 | 6 | The Data page: a Dataset's *Inbox*, *Watch this folder* switched on. | *Watch this folder* · *Your data · same steps* | Your own files work the same way: point it at the folder they land in. |
| 9 | 4 | End card. | *See who is connected.* · *Try it on your own data.* | Inspecto LA. |

---

## 6 · The analyst — dramatization (about 65 s)

**Brief**

| | |
|---|---|
| Objective | Give the product a human face for LinkedIn and booth screens. |
| Audience | Heads of financial crime; the analysts who will use it. |
| Channel | LinkedIn, booth loop, top of a webinar. |
| Message | Investigators need to find the link *and* defend it. Inspecto LA records the steps and hands them over as evidence. |
| Outcome | A sealed Dossier that anyone can replay. |
| Edition | **Standard**: Investigations, the step log and the Dossier are not in Basic. Film on an Advanced install (`la-mobile-money` needs one). |
| Casting | One actor, mid-career analyst, plain office clothes. The actor plays a role: no real person, company or customer is named or implied. |
| Required label | *Dramatization. Product footage is real; all data is synthetic.* on screen for at least 3 s at the start. |
| Must not | Present the actor as a customer, or quote a customer without signed approval. |

The B-roll shows the step; the analyst's line says why it matters. The analyst never narrates the clicks.

**Script**

| # | ≈ s | Picture | On-screen text | Voice-over / dialogue |
|---|---|---|---|---|
| 1 | 4 | Black, then the label. | *Dramatization. Product footage is real; all data is synthetic.* | — |
| 2 | 8 | Actor at a desk, to camera. | — | **Analyst:** Finding the link is the easy part. Proving it later — that's the job. |
| 3 | 5 | B-roll: a raw transfer file on the analyst's screen, scrolling. | — | **Analyst:** It always starts as a wall of transactions. |
| 4 | 10 | B-roll: *Expand* on a wallet; the busy merchant is excluded. | — | **Analyst:** I start from one account and widen out. The noise goes on a list, so it stays out. |
| 5 | 10 | B-roll: *Start an Investigation*; the Investigation tab's step log fills in. | *Inspecto LA Standard* | **Analyst:** When it matters, I open an Investigation. Every step I take is recorded. |
| 6 | 10 | B-roll: a held large expand waiting for a second person's approval. | *Four-eyes* | **Analyst:** The big steps need a second pair of eyes. That's not slowing me down — that's protecting the case. |
| 7 | 10 | B-roll: the Dossier is built and downloaded. | *Dossier* | **Analyst:** And when it's done, I hand over a sealed Dossier. Anyone can replay what I did. |
| 8 | 8 | Actor, to camera, then end card. | *See who is connected.* · *Try it on your own data.* | **Analyst:** It finds the link. And it lets me stand behind it. |

---

## 7 · One graph for any data (about 90 s)

The introduction to the industry videos, and the one that shows the own-data steps in full: whatever the industry, a
messy raw export is mapped onto the product the same way, and the same questions then work on it. It ends on an index
of the nine industries, each leading to its video.

**Brief**

| | |
|---|---|
| Objective | Answer the buyer's first question, "will it work on *our* data?", and route them to their industry's video. |
| Audience | Every prospect; the opening video of the industry playlist, the website's "How it works", the top of every sales deck demo. |
| Channel | Website, YouTube playlist opener, sales demos. |
| Message | Any records that say who dealt with whom become one graph, mess and all. You say what each column holds; the product does the rest. Records from different systems meet on the entities they share. |
| Must show | The raw `calls.csv`, with its mess visible; *Add data* ▸ *Onboard a file* end to end: *Events between entities*, the detected columns with one **A guess** confirmed, *What does each column hold?* (an Entity Type, **From** and **To**), *Create and run* and its result (kept, and set aside with the reason). Then a second file from another domain, the **Entities** page showing one Entity Type held by both Datasets, and **Graph model ▸ Entity/Link (several Datasets)** drawing them as one graph. |
| Outcome | The number that calls the suspect is the one that receives the money: one lead, across two systems. |
| Must not | Say the product maps data "automatically" or "with AI". It detects and proposes, marks its guesses, and the analyst confirms. Do not show *Derive mapping* (it needs configuration-authoring rights and is not the point). |
| Data | A fresh Basic install, a new Space, and two **prepared synthetic files**: `calls.csv` (telecom call records: caller, callee, start time, duration) and `transfers.csv` (mobile-money transfers: sender number, receiver number, time, amount). The numbers are invented, from a range no real subscriber can have (the showcase uses `999…`); a planted handful appear in both files, so that a number that calls the suspect also receives the money. |
| Data preparation | Generate both files with a script kept beside the showcase generators. Plant real-world mess in them: the same number written with and without its country code or with spaces, a few rows with a blank callee or receiver, one repeated row, a time in a second format. Product confirms which of these the shared Entity Type's *Compare values as: Phone number* treats as equal and which rows *Create and run* sets aside, before the files are made; film what the product reports. Film a dry run first. |
| Edition | Basic. Product confirms that *Entity/Link (several Datasets)* in Graph model is on Basic before filming. |

**Script**

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 7 | Quick montage of raw records from five industries, each a few rows on screen: call records, wallet transfers, insurance claims, meter reads, a company register. | *Example · synthetic data* | Every industry keeps its records its own way. Calls. Transfers. Claims. Meter reads. |
| 2 | 6 | `calls.csv` in a plain text viewer: the same number written two ways, a blank callee, a repeated row, each marked by a soft highlight added in the edit. | *calls.csv* | And real exports are messy: the same number written two ways, a gap, a repeat. |
| 3 | 6 | *Getting started* ▸ *Add data*. *Onboard a file*: choose *Events between entities*, drop `calls.csv`. | *Add data* | Inspecto LA asks every one of them the same question: who is connected to whom? |
| 4 | 8 | *Check the columns*: the detected types with their reasons; one column marked **A guess**; press *Confirm*. | *A guess* | It says what it found, and marks what is only a guess, for you to confirm. |
| 5 | 9 | *What does each column hold?*: `caller` and `callee` hold *Phone number*; links go **From** `caller` **To** `callee`; the start time is the time. | *What does each column hold?* | You say what the columns hold: phone numbers, and a call goes from one to the other. |
| 6 | 8 | *Create and run*. The result: rows read and kept, rows set aside and why, as the product reports them. | *Create and run* | It keeps what it can use, sets aside what it can't, and tells you why. |
| 7 | 9 | *Add another file*: `transfers.csv`, a different system. Its sender and receiver columns hold the same *Phone number* type. The **Entities** page: the *Phone number* card lists both Datasets. | *Entities* | Now a file from a different business: mobile money. Its numbers are the same kind of thing… |
| 8 | 10 | **Graph model ▸ Entity/Link (several Datasets)**: both Datasets; *Run query*. Calls and transfers draw as one graph; click a shared number: one node, listing both Datasets. | *One node, two Datasets* | …so a number is one node, whichever system it came from and however it was written. |
| 9 | 6 | A beat of quiet on the shared number: a call link to the suspect, a transfer link from the suspect's side. | *Calls the suspect · receives the money* | The number that calls the suspect is the one that gets the money. That's the lead. |
| 10 | 8 | Three fast cuts from the industry videos: *Busiest nodes*, *Tight groups*, *Route from A to B*, each on a different industry's graph. | — | And the same questions work on any data. Who is busiest. Who belongs together. How A reaches B. |
| 11 | 6 | **Industry index**: nine tiles (Telecom, Banking and finance, Insurance, Utilities, Law enforcement, Public sector, Healthcare, Logistics, Enterprise), each lighting in turn. | *Find your industry* | Each is an example. See one on your industry's records, then try it on yours. |
| 12 | 4 | End card, with the index still visible. | *See who is connected.* · *Try it on your own data.* | Inspecto LA. |

**The industry index (shot 11).** On the web and in the playlist each tile links to its industry video above. In the
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

**Alternative cut (optional, about 20 s).** *One file, two questions*: on the Data page, a transfers Dataset gets a
second **Mapping** (*account → channel* beside *account → account*), and both appear in the Start box without copying
the data. For analysts who already have their data in; not part of the main cut.

---

## Open points before filming

- Supply the CTA URL for the end card.
- Video 2: time the run on the final build and the filming laptop; use the median wherever a time is said or shown.
- **Own-data line, per story:** product confirms that each story's outcome can be reached from its raw files with
  *Add data* and the analysis shown. Where a Space also relies on something set up once (a Job that derives links, a
  prepared saved view), that story's line names it (*…plus one Job, set up once*) instead of "same steps".
- **Raw beats:** pick, per story, the landed file and the rows to show (ideally rows holding the story's ids), and
  check that the Data page's *Entities* shows what the Map beat's voice-over says for each Dataset filmed.
- Industry videos: check every reveal against the Space's ground truth file before filming. Segments on planned
  Spaces (3.1 B and D, 3.2 E and F, 3.6 A, 3.7, 3.8, 3.9 B) are filmed only after their Spaces are built, and
  re-read against the Space as built. Decide per industry whether to publish with built segments only or wait.
- Segments 3.1 C, 3.2 A and B, 3.3 and 3.9 A have no look-alike listed in the showcase plan for the story filmed;
  confirm with the Space's owner that nothing else on screen reads as suspect.
- Video 5: shot 7 waits for the `la-police` Advanced tier (its Alert Rule); shot 8 is filmed on the video 7 Space.
- Video 7: generate the two synthetic files with the planted mess (a number written two ways, gaps, a repeat, a
  second time format) and a shared handful of numbers, from a range no real subscriber can have; product confirms
  which number formats *Compare values as: Phone number* treats as equal, which rows are set aside, and that Graph
  model's *Entity/Link (several Datasets)* is on Basic. Film it after the industry videos it introduces, so shot 10
  can use their footage.
- Filming needs both a Basic install (videos 2, 5, 7, 3.4–3.8) and an Advanced install (videos 1, 4, 6, 3.1, 3.2,
  3.3, 3.9); on the Advanced install, film as a user who can reveal identifiers.
- Product and legal sign-off on every script before the agency starts.
