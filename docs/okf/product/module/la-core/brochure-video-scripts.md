# Inspecto LA short videos — scripts and agency briefs

> **Six concept videos and nine industry videos, filmed on the real product over synthetic data. Each one is an example that ships with Inspecto LA: it explains the problem, what the graph shows and why it took a graph, and turns raw records into an investigation outcome in a few steps that anyone can repeat on their own data. Each ends by saying that what it showed is in Basic, and what Standard and above add. For each video: a one-page brief for the agency, then a shot-by-shot script with voice-over; a 480p draft round on a Basic install comes before the agency's masters.**

*Audience: marketing, presales and the video agency. Product Map Item `module.la-core`. Draft of 2026-11-08, not
yet reviewed by product or legal. Every product claim below is taken from the
[Link Analysis user guide](user-guide-link-analysis.md); every story, id and figure in the industry videos is
taken from the showcase plan (`docs/superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md`) and,
for a built Space, its generator's ground truth file. The [whitepaper](brochure-inspecto-la-whitepaper.md) is the
reference for anything wider.*

## The series in one line

Every video is an **example**: raw records go in, an investigation outcome comes out, in a few steps anyone can
repeat. Every example is a showcase Space that **ships with Inspecto LA**, so the viewer can open it afterwards and
explore the same data. A viewer should finish knowing what problem the graph solved, and thinking *I could do that
with my own records*. Every story follows the same beats, and every script below is written to them:

| Beat | What the viewer sees | What the voice does |
|---|---|---|
| **Problem** | The world of the story for a moment: the raw records under a title, or the place the loss shows up. | Says in plain business words what is being lost or hidden, who loses, and why it is hard to see. Two or three sentences; no product yet. |
| **Raw** | The records as they really are: the actual file, unformatted. A corner tag *Example · synthetic data*. | Says in one short, blunt sentence what the records are, and why nobody can read the answer off them. |
| **Map** | The one screen where someone says what each column holds: *What does each column hold?* when onboarding a file, or the Dataset's *Entities* on the Data page. Where a Space's links come from a Job instead (no Entity Types set), the link Dataset that Job builds. | Says it is done once, in plain words. |
| **Graph** | The graph drawn, its legend open on the link kinds. | Says what the viewer is looking at: what each circle stands for and what each kind of line means. |
| **Ask** | One analysis, its name on screen as the click happens. | Says the question in plain words and what the analysis does to answer it (counts each circle's lines, finds the groups that link mostly among themselves, follows the shortest route). Never names the button. |
| **Outcome** | The finding, held, with its key ids and numbers on screen. | Says what it means and what the investigator does next: a lead to check, never a verdict. |
| **Why a graph** | The finding held, or the raw rows beside the graph. | Says what Link Analysis solved: why the rows could not show it, and which links did. |
| **Explore it** | The example Space in the app: its home page, the saved views under *My work*. | Says this example ships with Inspecto LA, and invites the viewer to open it, take its tour and try its exercises. |
| **Your data** | *Your data · same steps* on screen, over the raw file turning into the graph. | Says the records were an example and the steps are the same on the viewer's own. |
| **Edition** | *Everything shown: Inspecto LA Basic*, then a short card of what Standard and above add. | Says the viewer has just explored Basic, and in one sentence what Standard and above add. |

## Contents

**Concept videos** — one idea each, cross-industry. Lengths are estimates.

| # | Title | Length (about) | Features shown | Film on | Data | Ready to film |
|---|---|---|---|---|---|---|
| 1 | The ring nobody saw | 1 min 45 s | Basic | Advanced install | `la-mobile-money` | Yes |
| 2 | Zero to graph | 1 min 45 s | Basic | Basic install | *Try the sample* | After the timing check |
| 4 | Retire the red string | 40 s | Basic | Advanced install | `la-mobile-money` | Yes |
| 5 | It stays current | 1 min 30 s | Basic, plus one Advanced shot | Basic install | `la-police` + its staged month | Yes (shot 7 later) |
| 6 | The analyst (dramatization) | 65 s | Standard | Standard or Advanced install | `la-mobile-money` | After casting |
| 7 | One graph for any data (introduces the industry videos) | 100 s | Basic | Basic install | Two prepared synthetic files, telecom and mobile money | After data preparation |

**Industry videos (video 3)** — one video per industry, nine in all, grouped as the showcase catalogue in the plan
is. Each holds every use case of its industry as a segment. `la-showcase` appears in two: its telecom rings under
Telecom, its banking stories under Banking and finance.

| # | Industry | Use cases (segments) | Length (about) | Film on | Ready to film |
|---|---|---|---|---|---|
| 3.1 | Telecom | Mobile money · MFS · Telco fraud · Revenue assurance | 3 min 30 s | Advanced | 3 of 4 segments (MFS planned) |
| 3.2 | Banking and finance | First look · Financial crime · Ownership and sanctions · Card fraud · Trade finance · Crypto | 4 min 45 s | Advanced | 5 of 6 segments (crypto planned) |
| 3.3 | Insurance | Claims fraud | 2 min | Basic | Yes |
| 3.4 | Utilities | Electricity revenue protection | 2 min | Basic | Yes |
| 3.5 | Law enforcement | Criminal investigation | 2 min | Basic | Yes |
| 3.6 | Public sector | Procurement · Benefits and tax | 2 min 30 s | Basic | Yes |
| 3.7 | Healthcare | Provider billing | 2 min | Basic | Yes |
| 3.8 | Logistics | Cargo theft | 2 min | Basic | No: Space planned |
| 3.9 | Enterprise | Internal audit · Insider threat | 2 min 30 s | Advanced | 1 of 2 segments (insider planned) |

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

**Explain the story, not just the clicks (binding).** Tell each story so that a viewer who has never seen a link
chart can follow it and relate it to their own work:
- **The problem first,** in the audience's words: what is lost, who pays, why the usual reports miss it.
- **What is on screen:** what the circles and the kinds of line stand for, said once when the graph first appears.
- **What the analysis does,** in one plain sentence ("it counts each circle's lines", "it finds the groups that link
  mostly among themselves", "it follows the shortest route from one to the other").
- **What the finding means** and what the investigator does next.
- **Why it took a graph:** which link the rows could not show.

The voice carries more of the story than a teaser would: two to four sentences where the picture needs explaining,
and silence where the picture speaks (the reveal). The picture-and-voice rules below still hold: the voice explains
what the picture means; it never reads the screen aloud.

**It ships with the application (binding).** Every example is a synthetic showcase Space that ships with Inspecto LA:
its configuration and data come as two downloads beside the product, and its user guide opens with a five-minute tour
and one exercise per story, with the answers. Each video says so once, near the end, names the Space as the app shows
it, and shows its home page with the saved views under *My work*. The line is *This example ships with Inspecto LA:
open the <Space name> Space and explore it yourself.* Product confirms how a user adds a showcase from inside the
application before the line is final (see Open points).

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

**The edition close (binding).** Every video except 6 ends, after *Your data* and before the end card, by saying that
what the viewer just explored is in **Inspecto LA Basic**, then what Standard and above add:

| Beat | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| Basic | 3 | The video's last graph, smaller, with an edition badge. | *Everything shown: Inspecto LA Basic* | Everything you just explored is in Inspecto LA Basic. |
| More | 6 | The Standard-and-above card, its five lines appearing one at a time. | *More in Standard and above* | Standard and above turn a finding into evidence a team can share and defend. |

The card's five lines: *Investigations: every step recorded, replayable* · *A sealed Dossier to hand over* ·
*Sign-in, roles, four-eyes, audited reveal* · *Geo map, routes and co-location* · *Alert Rules and scheduled feeds*.
An industry video may lead with the line that matters most to its audience (Utilities and Law enforcement: the Geo
map). The full list is in the last section, *What these videos show, and what Standard and above add*. A video that
includes a shot above Basic (its edition label on screen) says *Everything else shown: Inspecto LA Basic*. Video 6
shows Standard features and carries their edition labels instead.

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

**Capture.** 1920 × 1080 screen capture at 60 fps, browser at 100 % zoom, the product's own theme (the LA application is dark only), a clean browser profile
with no bookmarks bar or extensions. Hide the mouse pointer except where a shot calls for a click. Slow,
deliberate pointer moves. This is for the masters; drafts are captured as *Draft round (480p)* says.

**Length and pace.** Every length in this document is an estimate, and the ≈ seconds in the scripts are a guide for
the edit, not a limit. Pace the voice for clarity (around 2.5 words a second reads well) and give the picture the time
it needs: each result stays on screen at least 1.5 s, each outcome at least 2 s. No video is cut to hit a number.
The explained stories run longer than a teaser: a single-story video is about two minutes, and an industry video
grows by about 45 s per story. For social, cut one story per post from Problem to Why a graph (about 45 s), with the
*Explore it* line and the end card.

**Deliverables per video.** A 16:9 master; 9:16 and 1:1 cut-downs for social (re-framed on the graph, not
letterboxed); burned-in captions on every version, because most social plays start muted; a clean version
without captions; SRT files.

**Voice and audio.** One voice across the series. One music bed for the series, understated, with a single soft
accent on each reveal.

**End card (every video).** Inspecto LA logo · the line *See who is connected.* · *Try it on your own data.* (each
industry video adds its industry line) · `[CTA URL — to be supplied]`.

**Approvals.** Product signs off each script's claims before filming, and the final cut before release.

---

## Draft round (480p)

Before the agency films the 1080p masters, each video is drafted in-house at 480p on a **Basic** install. A draft
proves the story, the order of the beats, the timing and the wording on the real product, and it is the reference the
agency films from. Drafts are for internal review only: never published, never sent to a prospect.

| | |
|---|---|
| Who | Presales or product, not the agency. |
| Install | One Basic install holding the showcase Spaces the round needs and, for video 7, the new Space made from its prepared files. Basic shows identifiers unmasked, so the ids read as the script says. Synthetic showcase Spaces only, as in the filming rules. |
| Resolution | 480p: **854 × 480** (16:9 SD, the same shape as the master), 30 fps, H.264 MP4. If a WVGA copy (800 × 480, 5:3) is needed, scale the 16:9 frame to 800 × 450 and letterbox it; never stretch. |
| Capture | A browser window of 1280 × 720 at 100 % zoom, scaled down to 854 × 480. A 1920 × 1080 capture shrinks 2.25 times and node labels blur; 1280 × 720 shrinks 1.5 times. Check that the screen still lays out as on a desktop, and push in on the reveal wherever an id must be read. At this width the Link Analysis toolbox starts folded into the right rail: open it on camera before the first analysis. Fold the legend and graph-count panels and dismiss the banner between beats, so the graph shows. |
| Voice and sound | A scratch voice-over (a team member reading the script, or the Windows offline voice) and burned-in captions; no music. The offline voice reads slowly, so a draft runs longer than its estimate (3.4: about 73 s against about 50 s). |
| Edition | Every draft ends with the edition close: everything it shows is in Basic. A shot above Basic (video 5 shot 7, the Utilities Geo View) is left out of the draft. |
| Review | Each draft is checked for the six beats, the muted test, and every id and number against the Space's ground truth. Product signs off its claims; the agency then receives it with the script. |
| Files | `la-video-<number>-draft-480p.mp4`, kept outside the repository (they are large binary files). |

**The Basic round, in order.** Simplest first, so the first draft also gives video 2 its timing check.

| Order | Video | Spaces (all with a Basic tier) | Notes |
|---|---|---|---|
| 1 | 2 · Zero to graph | `la-sample` | Its run is timed for the title as it is drafted. |
| 2 | 3.4 · Utilities | `la-utility` | Without the optional Geo View shot. Draft 1 (2026-11-08) followed the shorter script; the operator found it too compact to relate to the problem, which led to the explained beats. Re-record it first, as the pattern for the rest. |
| 3 | 3.5 · Law enforcement | `la-police` | |
| 4 | 3.3 · Insurance | `la-claims` | Basic tier since 2026-11-08; confirm *Communities* on the Basic install. |
| 5 | 3.7 · Healthcare | `la-health` | |
| 6 | 3.6 · Public sector | `la-procurement`, `la-benefits` | Both segments. |
| 7 | 3.2 · Banking and finance | `la-sample`, `la-ownership`, `la-card`, `la-trade` | Segments A, C, D and E. B needs `la-showcase` (an Advanced install today); F waits for its Space. |
| 8 | 7 · One graph for any data | A new Space from the two prepared files | After the files are generated and product's confirmations are in. |
| 9 | 5 · It stays current | `la-police` and its staged month; the video 7 Space for shot 8 | Without shot 7. |

**Drafted later.** Videos 1 and 4 and industry videos 3.1 and 3.9 use templates that need an Advanced install
today (`la-mobile-money`, `la-showcase`, `la-audit`); segment D of 3.1 (`la-telco-ra`) is Basic and can be drafted in
the Basic round on its own. They join the Basic round when the plan's wave B gives those templates a Basic tier, or
are drafted on an Advanced install, signed in as a user who can reveal identifiers. Video 6 shows Standard features
and is drafted after casting, on a Standard or Advanced install. A planned Space's segment is drafted when its Space
is built.

---

## 1 · The ring nobody saw (about 1 min 45 s)

**Brief**

| | |
|---|---|
| Objective | Show what links make visible that rows hide, and explain it, in a few steps anyone can repeat. |
| Audience | Fraud and AML managers at banks and mobile-money operators. |
| Channel | LinkedIn, website hero, trade-show loop. |
| Message | A ring you can't see in the raw transfers is obvious as a graph, in a few clicks. The example ships with Inspecto LA; your own transfers work the same way. |
| Must show | The problem; the raw transfer file; the link Dataset the `mm_links` Job builds; the graph explained; *Busiest nodes* and excluding the busy biller; *Tight groups* finding the device farm; the outcome; why it took a graph; the Space's home page; the own-data line. |
| Outcome | Nine wallets on shared devices: one group to review, every link behind it on screen. |
| Must not | Claim the product "detects fraud on its own". The analyst finds it, and the product points at it. |
| Data | *Link Analysis mobile money* (`la-mobile-money`, 14 days of synthetic wallet transactions) on an Advanced install, as a user who can reveal identifiers (ids are masked by default there). Its links come from the `mm_links` Job (`mm_links_dataset`: `p2p`, `cash_in`, `cash_out`, `merchant_pay`, `shared_device`, `shared_id`). The busy biller is `MER-ELECTRIC` (300 payers, one way: the look-alike, and the busiest node by degree), and the planted ring is the nine-wallet device farm. Every feature shown is in Basic. |
| Before filming | Try the ask live: if *Tight groups* after the exclusion does not put the nine wallets first, open the shipped saved view `mm_device_farm` instead and say so. |

**Script**

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 9 | Wallet transactions scrolling under the title. | *Example · synthetic data* | Fraud rings on mobile money spread their money across many wallets and many phones. Every transfer looks ordinary, so the ring stays invisible in a report. |
| 2 | 6 | `WALLET_TXN_20261006.csv` in a plain viewer, scrolling: wallet ids, amounts, timestamps. | — | Fourteen days of transfers. One row each, and not one row says who works together. |
| 3 | 8 | The Data page: `mm_links_dataset`, built by the `mm_links` Job. | *One link Dataset · built by one Job* | One Job, set up once, joins the transfers, the wallet register and the device logins into links: who paid whom, and who shares a phone or an identity. |
| 4 | 8 | *Start a graph* draws it; nodes settle; legend open. | *Start a graph* | Draw the links. Each circle is a wallet, an agent or a merchant; each line is a payment, a cash-in or out, or a shared device or identity. |
| 5 | 8 | One giant node dominates the centre. The right panel's *On screen* tab ▸ *Busiest nodes*: `MER-ELECTRIC` is first. | *Busiest nodes* | Busiest nodes counts each circle's lines. The first thing it finds is noise: an electricity biller that three hundred wallets pay. |
| 6 | 5 | Right-click `MER-ELECTRIC` ▸ *Exclude…*. The hub disappears and the graph loosens. | *Exclude* | Popular is not guilty. Set it aside. |
| 7 | 8 | *Tight groups*: *Show on graph* on the first group. Nine wallets light up, tightly knotted; everything else dims. | *Tight groups* | Now ask who belongs together. Tight groups finds the wallets that link mostly among themselves. |
| 8 | 9 | A beat of quiet on the nine, then slow push-in. | *9 wallets · shared devices* | Nine wallets on shared devices, moving money among themselves. One group to review, every link behind it on screen. |
| 9 | 7 | The raw file beside the nine. | — | No transfer in the file was unusual. The shared phones and the circle of payments only show as links. |
| 10 | 10 | The Space's home page: `mm_money_trail`, `mm_structuring_band` and `mm_device_farm` under *My work*. | *Ships with Inspecto LA · Link Analysis mobile money* | This example ships with Inspecto LA. Open it and explore it yourself: a structuring agent and a mule chain are planted in the same fortnight. |
| 11 | 7 | The raw file for a second, dissolving into the graph. | *Your data · same steps* | These records are examples. With your own, you set up the join once, then ask the same questions. |
| Close | 9 | The last graph, smaller, with an edition badge; then the Standard-and-above card, one line at a time. | *Everything shown: Inspecto LA Basic* · *More in Standard and above* | Everything you just explored is in Inspecto LA Basic. Standard and above turn a finding into evidence a team can share and defend. |
| 12 | 4 | End card. | *See who is connected.* · *Try it on your own data.* | Inspecto LA. |

---

## 2 · Zero to graph (about 1 min 45 s)

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
| 6 | 9 | The Data page: the Dataset the sample drew from, its columns as they came: account ids, amounts, dates. | *Example · synthetic data* | Underneath, plain transfer records: who paid whom, how much, and when. Somewhere in them, cash is being broken into small deposits so that no single one gets reported. |
| 7 | 7 | Back on the graph, the legend open. | — | On the graph, each circle is an account, and each line is a payment between two of them. |
| 8 | 8 | *Route from A to B* from a smurf account to `OFFSHORE-77`; the path lights through `MULE-HUB-01` and a relay. | *Route from A to B* | Ask where one small account's money ends up. Route from A to B follows the payments, hop by hop… |
| 9 | 6 | A beat of quiet on the route. | *Smurf → hub → relay → OFFSHORE-77* | …offshore, through one hub and a relay. That's your first lead. |
| 10 | 7 | The sample's rows beside the route. | — | Each payment on its own is small and ordinary. Only the chain of links shows where the money goes. |
| 11 | 9 | Click the light bulb: *What can I do next?* opens with its suggestions. | *Ships with Inspecto LA* · *Your data · same steps* | The sample ships with Inspecto LA, and it's yours to explore: a loop of shell accounts is hidden in it too. When you're ready, add your own file; it shows you each step. |
| Close | 9 | The last graph, smaller, with an edition badge; then the Standard-and-above card, one line at a time. | *Everything shown: Inspecto LA Basic* · *More in Standard and above* | Everything you just explored is in Inspecto LA Basic. Standard and above turn a finding into evidence a team can share and defend. |
| 12 | 4 | End card. | *See who is connected.* · *Try it on your own data.* | Inspecto LA. |

---

## 3 · Industry videos — one per industry (9 videos)

One video per industry, grouped as the showcase catalogue in the plan is. Each video holds **every use case of its
industry as a segment**, and each use case is a showcase Space that ships with Inspecto LA. The agency builds one
segment template and reuses it everywhere.

**Brief (shared)**

| | |
|---|---|
| Objective | One video per industry, so sales can send the one that fits the prospect: it explains each problem that industry has, shows the graph solving it, and points at the example the prospect can open themselves. |
| Introduced by | Concept video 7, *One graph for any data*: it shows how any domain's raw data becomes the same graph, and its last shot is the industry index that leads into these nine videos. On a website or playlist, video 7 plays first and each industry video links back to it. |
| Audience | Named per video below. |
| Channel | Sales email, LinkedIn targeted by industry, trade-show loop per stand; one-story cut-downs for social. |
| Message | Each problem, explained in your words; the graph that solves it, explained as it appears; an example you can open and explore, because it ships with Inspecto LA; and the same steps on your own records. |
| Shape | **Opening** (the industry and its problems in two sentences, over its raw records) → **Map**, once per video (how the records become links) → **one segment per use case** → **Explore it**, once (the Spaces that ship with the product) → **Your data** → **edition close** → end card with the industry line. The length follows the number of segments: about 45 s per segment. |
| Segment | Six beats, about 45 s in all: **Problem** (what is lost and why it is hard to see, in the audience's words), **Raw** (that use case's file as it landed, one blunt sentence), **Graph** (the graph drawn, legend open: what the circles and lines are), **Ask** (the one analysis named, and what it does in one plain sentence), **Outcome** (the finding held at least 2 s, its ids and numbers on screen; the finding and the next step), **Why a graph** (which link the rows could not show). |
| Modular | Segments are filmed separately. A video whose Spaces are all built is filmed now; a planned Space's segment is filmed when the Space is built and cut in. Until then, publish the video with its built segments only, or hold it (the owner decides per industry). |
| Film on | One install per video: an **Advanced** install if any of its segments needs one (`la-mobile-money`, `la-showcase`, `la-audit` need one today; `la-claims` only for its Investigation, Reveal and Case segments), otherwise Basic. On Advanced, film as a user who can reveal identifiers. |
| Must show | Each reveal exactly as the Space's ground truth file lists it: the same entities, nothing added. Each ask as the Space's user guide does it: try it on the live Space before filming. |
| Must not | Use real place, company or person names. Show a story's look-alike as suspicious. Present an outcome as a verdict. |
| Before filming | Check each reveal against the Space's ground truth file. Pick each Raw beat's file and rows: the file the story was built from, ideally rows that hold the story's ids. For a planned Space, re-read its segment against the Space as built: ids and figures can move during the build. |

**Segment template**

| Beat | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| Problem | 8 | The use case's world for a moment: its records under the use-case title. | [Use case] | [What is lost, who pays, why reports miss it] |
| Raw | 5 | The use case's records as they landed, unformatted. | — | [What the records are, bluntly] |
| Graph | 8 | The Space's graph or saved view drawn, legend open on the link kinds. | [Saved view or start] | [What the circles and lines are] |
| Ask | 7 | The analysis named in the segment. | [Analysis name] | [The question, and what the analysis does] |
| Outcome | 10 | The finding, held, after a beat of quiet. | [Key ids and numbers] | [The finding, and the next step] |
| Why a graph | 7 | The finding held, or the raw rows beside it. | — | [Which link the rows could not show] |

The opening, map, *Explore it* and close are the same in every multi-segment video:

| Beat | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| Opening | 8 | The raw files of every segment flash past, unformatted. | [Industry] · *Example · synthetic data* | [The industry and its problems, in two sentences] |
| Map | 8 | The Data page: one of the industry's Datasets open at *Entities*, or the link Dataset its Job builds. | *Entities* or *One link Dataset · built by one Job* | Each of these began as a raw export. You say once how the records link up; then you ask. |
| Explore it | 10 | Each Space's home page in turn, its saved views under *My work*. | *Ships with Inspecto LA* · [Space names] | Every example here ships with Inspecto LA. Open the Spaces and explore them yourself: each comes with a short tour, and an exercise for every story, with the answers. |
| Your data | 7 | One raw file dissolving into its graph. | *Your data · same steps* | These records are examples. Drop in your own, say what each column holds, and ask the same questions. |
| Edition close | 9 | The last graph, smaller, with an edition badge; then the Standard-and-above card, one line at a time. | *Everything shown: Inspecto LA Basic* · *More in Standard and above* | Everything you just explored is in Inspecto LA Basic. Standard and above turn a finding into evidence a team can share and defend. |
| End card | 4 | End card. | [Industry line] · *Try it on your own data.* | Inspecto LA. |

### 3.1 · Telecom (about 3 min 30 s)

| | |
|---|---|
| Audience | Fraud, risk and revenue-assurance teams at mobile operators and their mobile financial services (MFS) businesses. |
| Film on | Advanced install (`la-mobile-money`, `la-showcase`); film as a user who can reveal identifiers. |
| Ships as | *Link Analysis mobile money* (`la-mobile-money`, Advanced), *Link Analysis showcase* (`la-showcase`, Advanced), *Link Analysis telecom revenue assurance showcase* (`la-telco-ra`, Basic). `la-mfs` is planned. |
| Ready | Segments A, C and D now; B when `la-mfs` is built. |
| As built | All three Spaces build their links with a Job (`mm_links`, `telecom_links`, `telco_ra_links`), so the Map beat shows a Job-built link Dataset. *Busiest nodes* is not the ask in A: there the busiest node is the benign biller `MER-ELECTRIC`. |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | Mobile money | `la-mobile-money` (built) | Wallet transactions (`WALLET_TXN_20261006.csv`). | Agent `AG-18` cashes in for 25 wallets (`STRUCT-01`…`25`) within 36 hours, every amount between 4 750 and 5 000 (the limit is 5 000); each wallet pays `HUB-01`. | Saved view `mm_structuring_band`; *Value Measures* ▸ *structuring*, 4 750 to 5 000: one wallet, `HUB-01`, with 25 legs from 25 payers; the cash-in agent is `AG-18`. | An agent to review; the 25 wallets and the hub as the evidence. | `MER-ELECTRIC`, a biller 300 wallets pay, one way only: it tops degree and the default suspicion score, and is not the story. |
| B | MFS | `la-mfs` (planned) | SIM-swap events and wallet transfers. | SIM-swap wallet takeover: 30 SIMs swapped through one call-centre login, `S-41`, in 10 days; each wallet drained within 2 hours to 5 mule wallets. | Written when the Space is built, from its user guide. | A login to investigate; 5 mule wallets to trace. | A busy store doing many genuine SIM swaps for customers who keep their balances. |
| C | Telco fraud | `la-showcase` (built) | Call records (`VOICE_CDR_20260929.csv`). | One operation behind several typologies: the Wangiri number `99979100001` forwards to the premium number `99891000001`, which links to an IRSF ring, a subscription-fraud identity and, at degree 4, the SIM box. | Saved view `telecom_fraud_network`; seed `99979100001`; *Expand* one degree at a time to 4, busy numbers held. Degree 1 also draws its roughly 400 call-back victims (about 400 nodes, a star). | One operation, not four cases: the chain, link by link, for the report. | The taxi-dispatch number `99970000100` (650 callers): reached at degree 2 and held, never expanded. |
| D | Revenue assurance | `la-telco-ra` (built) | SIM activations and top-ups (`SUBSCRIBER_20260930.csv`, `TOPUP_202602.csv`). | Dealer `DL-112` (Copperfield Talk, Leeds) activates 400 SIMs on `DEV-00204`, `DEV-00205` and `DEV-00206` in six weeks from 2026-02-02; each is topped up once, with 5.00, at the dealer's own shop `POS-0008`, then never used again. | Saved view `telco_ra_commission_spikes`: two dealers with weeks of 50 or more commission-earning SIMs; follow each dealer's `topped_up` links: one fans out to ten shops, the other converges on `POS-0008`. | A dealer's commissions to audit. | `DL-163` (Yardley Handsets, Sheffield), 350 SIMs at the start of a university term: students top up by app or at ten shops and use the SIMs every month. |

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 8 | Opening: top-ups, call records and activations flash past, raw. | Telecom · *Example · synthetic data* | On a mobile network, money leaks out through laundering, scams and commissions. Each trick hides in a different system, and each team sees only its own. |
| 2 | 8 | Map: the Data page, one Space's link Dataset open, built by its Job. | *One link Dataset · built by one Job* | Each of these began as a raw export. A Job, set up once, joins each Space's files into one list of links. |
| A1 | 8 | Wallet transactions under the title. | *Mobile money* | Mobile money has a reporting limit. Launderers stay under it by splitting cash into many small deposits, often with an agent's help. |
| A2 | 5 | `WALLET_TXN_20261006.csv` as it landed. | — | A day of wallet transactions: two wallet ids, an amount, a time. |
| A3 | 8 | Saved view `mm_structuring_band`, legend open. | *mm_structuring_band* | This view keeps only the deposits just under the limit. Each circle is a wallet or an agent; each line is a cash-in or a payment. |
| A4 | 7 | Toolbox ▸ *Value Measures* ▸ *structuring*, 4 750 to 5 000. | *Value Measures · structuring* | Ask which wallet collects many deposits inside that band. The structuring measure counts them for every wallet. |
| A5 | 10 | A beat of quiet; `HUB-01`, its 25 payers and `AG-18`, held. | *AG-18 · 25 wallets · HUB-01* | Twenty-five wallets, every cash-in just under five thousand, all through one agent, AG-18, and all paying the same hub. An agent to review, with the wallets as evidence. |
| A6 | 7 | The raw rows beside the graph. | — | Each deposit is legal on its own. Only the links show twenty-five of them meeting in one place. |
| B | 45 | Segment B, filmed when `la-mfs` is built. | *MFS* | Written from its user guide when the Space ships. |
| C1 | 8 | Call records under the title. | *Telco fraud* | Fraud on a phone network is rarely one trick. A missed-call scam, a premium-rate number, a fake subscription and a SIM box can be one operation. |
| C2 | 5 | `VOICE_CDR_20260929.csv` as it landed. | — | Sixty days of call records: who called whom, when, and for how long. |
| C3 | 9 | Saved view `telecom_fraud_network`, seeded with `99979100001`; degree 1 draws a star of call-back victims. Legend open. | *telecom_fraud_network* | Start from one suspect number. Each circle is a number; each line is a call, a text, a forwarded call, or a device, identity or payment card two numbers share. |
| C4 | 8 | *Expand*, degree by degree to 4; the busy taxi-dispatch number is held. | *Expand · 4 degrees* | Widen out one degree at a time. Busy numbers, like a taxi dispatcher with six hundred and fifty callers, are held back, so the trail stays readable. |
| C5 | 10 | A beat of quiet; the chain from the suspect to the SIM box, held. | *99979100001 → SIM box · 4 degrees* | A forwarding to a premium number, a ring of international calls, a fake subscriber, and a shared payment card lead to the SIM box. One operation, not four cases. |
| C6 | 7 | The chain held. | — | Each team's report saw one trick. The links between the tricks were in the call records all along. |
| D1 | 8 | Activations under the title. | *Revenue assurance* | Operators pay dealers a commission for each new SIM that is topped up. A dealer can activate SIMs nobody will use, top each one up once, and collect. |
| D2 | 5 | `SUBSCRIBER_20260930.csv` and `TOPUP_202602.csv` as they landed. | — | SIM activations and top-ups: which dealer, which device, how much, at which shop. |
| D3 | 9 | Saved view `telco_ra_commission_spikes`, legend open. | *telco_ra_commission_spikes* | This view keeps the dealers with weeks of fifty or more commission-earning SIMs: there are two. Each circle is a dealer, a SIM, a device or a shop; each line is an activation or a top-up. |
| D4 | 8 | Follow each dealer's `topped_up` links; hover an `activated_by` link to read `topups:1`, `usage_months:1`. | *topped_up* | Ask where each dealer's SIMs were topped up. One dealer's fan out to ten shops near a university; the other's all converge on one shop. |
| D5 | 10 | A beat of quiet; `DL-112`, its three devices and `POS-0008`, held. | *DL-112 · 400 SIMs · POS-0008* | Four hundred SIMs from three devices, each topped up once with five, at the dealer's own shop, and then never used. A dealer's commissions to audit. |
| D6 | 7 | The two dealers side by side. | — | In the commission report both dealers look the same: a spike of new SIMs. The links show who paid for them. |
| 3 | 10 | Explore it: each Space's home page, its saved views under *My work*. | *Ships with Inspecto LA* · *Link Analysis mobile money* · *Link Analysis showcase* · *Link Analysis telecom revenue assurance showcase* | Every example here ships with Inspecto LA. Open them and explore: each comes with a short tour, and an exercise for every story, with the answers. |
| 4 | 7 | Your data. | *Your data · same steps* | These records are examples. With your own, you set up the join once, then ask the same questions. |
| Close | 9 | The last graph, smaller, with an edition badge; then the Standard-and-above card, one line at a time. | *Everything shown: Inspecto LA Basic* · *More in Standard and above* | Everything you just explored is in Inspecto LA Basic. Standard and above turn a finding into evidence a team can share and defend. |
| 5 | 4 | End card. | *For telecom fraud and revenue assurance.* · *Try it on your own data.* | Inspecto LA. |

### 3.2 · Banking and finance (about 4 min 45 s)

| | |
|---|---|
| Audience | Fraud, AML, KYC, sanctions and trade-finance compliance teams at banks, payment providers and virtual-asset service providers. |
| Film on | Advanced install (`la-showcase`); film as a user who can reveal identifiers. Without segment B, a Basic install. |
| Ships as | *Link Analysis sample (invented data)* (`la-sample`, every edition, opened in the app with *Try the sample*), *Link Analysis showcase* (`la-showcase`, Advanced), *Link Analysis ownership showcase* (`la-ownership`), *Link Analysis card fraud showcase* (`la-card`), *Link Analysis trade showcase* (`la-trade`), all three Basic. `la-crypto` is planned. |
| Ready | Segments A–E now; F when `la-crypto` is built. |
| As built | `TILL-06` takes **97 %** of the cash-out from 8 payers (the shipped data and `ControlApiLaShowcaseFiguresTest`; the old plan said 80 %). `la-sample` and the mule part of `la-showcase` mark their account columns as accounts; `la-ownership` and `la-trade` also set one Entity Type over a Job-built link Dataset; `la-card` builds its links with a Job only. The circular trade runs twice round between 18 March and 27 June 2026. |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | First look | `la-sample` (built) | Transfers between accounts (`data/samples/mule_transfers/TRANSFERS_20260901.csv`). | 12 smurf accounts structure cash into `MULE-HUB-01` (four deposits each just under 1 000), which passes it through `RELAY-01`/`RELAY-02` to `OFFSHORE-77`. | Saved view `mule_layering_ring`; *Pattern match* ▸ *Structuring (smurfing)*: `MULE-HUB-01`, 96 legs in the band from 12 payers. Then *Route from A to B* from `MULE-HUB-01` to `OFFSHORE-77`. | The route to report, hub and relay named. | None in the sample; imply no other account is suspect. |
| B | Financial crime tour | `la-showcase` (built) | Transfers and cash-outs (`TRANSFERS_20260901.csv`). | Eight cash-out accounts funded by `MULE-HUB-02` all cash out at agent till `TILL-06`: 97 % of its cash-out value from 8 payers. | *Value Measures* ▸ concentration, cash-out kind `cash_out`: `TILL-06`, 97 % from 8 payers. | A till to review. | The ordinary tills `TILL-01` to `TILL-05`, each with scattered payers. |
| C | Ownership and sanctions | `la-ownership` (built) | An ownership register and an (invented) sanctions list (`OWNERSHIP_20260930.csv`, `SANCTIONS_LIST_20260930.csv`). | List entry `SL-0137` *Corvin Tammask* (1971, CY) matches person `PER-03904` *Corvin Tamask* (score 0.986). `PER-03904` owns 60 % of `CO-04431`, which owns 50 % of `CO-04313`, the company of bank customer `CUS-00528`. | *Start a graph* ▸ *One entity*, Entity Type *Party*, `SL-0137`, *Draw*; open the `name_match` link (score, why); *Expand*, Degrees 3: six nodes. | A customer to escalate for review. | `PER-02226` *Anselm Pelivane* against listed `SL-0289` *Anselm Pellivane*: different birth year and nationality, so no match; his customer is clean. |
| D | Card fraud | `la-card` (built) | Card authorisations and chargebacks (`CARD_AUTH_202603.csv`, `CHARGEBACK_20260930.csv`). | 60 cards that paid at merchant `M-317` (Kestrel Fuel Parkway, Sheffield) between 2026-03-02 and 2026-03-22 suffered card-not-present fraud 1 to 6 weeks later. | Saved view `card_common_point` (each defrauded card linked to the merchants it paid before the fraud); *Busiest nodes*: `M-317` far ahead, linked to 60 cards. | The likely point of compromise to investigate. | `M-052` (Harvest Fair Superstore): about 940 cards, none defrauded; not in the view at all. |
| E | Trade finance | `la-trade` (built) | Bills of lading (`BILL_OF_LADING_202603.csv`). | Six containers (`KLNU7238600` and five more) go twice round `CO-00941` (UAE) → `CO-00388` (India) → `CO-00213` (Malaysia) → `CO-00941`, 18 March to 27 June 2026: the same 1 100 laptops, invoiced at 450 681 rising to 788 128. | Saved view `trade_circular_trade`: six boxes tied to the same three shippers stand apart; hover a box: `relayed`, its consignees and relays; then the `traded_with` links between the three companies. | Three companies and six trades to look at. | The leasing pool's boxes (`PWCU…`), each loaded by 10 different shippers, never back to a company that sent it. |
| F | Crypto | `la-crypto` (planned) | Exchange deposits and withdrawals. | Converging cash-out: 40 KYC accounts at 2 exchanges deposit from one cluster and cash out to 3 bank accounts. | Written when the Space is built, from its user guide. | Three bank accounts where the money goes next. | An over-the-counter desk whose many customers deposit their own funds. |

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 8 | Opening: transfers, a register, card payments and bills of lading flash past, raw. | Banking and finance · *Example · synthetic data* | Accounts, owners, cards and cargo. Money hides in the gaps between them, and the reports look at one at a time. |
| 2 | 8 | Map: the Data page, an account column marked as an account, and a Job-built link Dataset. | *Entities* · *One link Dataset* | Each of these began as a raw export. You say once how the records link up; then you ask. |
| A1 | 8 | Transfers under the title. | *First look* | Cash deposits over a limit get reported, so launderers split them across many accounts. That's called smurfing. |
| A2 | 5 | `TRANSFERS_20260901.csv` as it landed. | — | Three days of transfers: payer, payee, amount, time. |
| A3 | 7 | Saved view `mule_layering_ring`, legend open. | *mule_layering_ring* | Each circle is an account; each line is a payment between two of them. |
| A4 | 8 | *Pattern match* ▸ *Structuring (smurfing)*. | *Pattern match · Structuring* | Ask for the pattern itself: many small deposits, just under the limit, into one account. |
| A5 | 10 | `MULE-HUB-01` and its 12 payers; then *Route from A to B* to `OFFSHORE-77`, held. | *MULE-HUB-01 · 12 payers → OFFSHORE-77* | Twelve accounts feed one hub, ninety-six times. Follow the money, and it leaves through a relay to an offshore account. The route to report. |
| A6 | 7 | The rows beside the route. | — | Every deposit is under the limit. Only the links show where they all go. |
| B1 | 8 | Cash-out records under the title. | *Financial crime* | Mule rings need a way out. Somewhere, the money becomes cash, and that till rarely looks like the rest. |
| B2 | 5 | `TRANSFERS_20260901.csv` as it landed. | — | Transfers and cash-outs at agent tills, row after row. |
| B3 | 7 | The ring drawn, legend open. | — | Each circle is an account or a till; each line a payment or a cash-out. |
| B4 | 8 | Toolbox ▸ *Value Measures* ▸ concentration, cash-out kind `cash_out`. | *Value Measures · concentration* | Ask how concentrated each till's cash-out is: how much of it comes from how few payers. |
| B5 | 10 | `TILL-06` held. | *TILL-06 · 97 % · 8 payers* | One till takes ninety-seven percent of its cash-out from just eight payers. A till to review. |
| B6 | 7 | `TILL-06` beside an ordinary till. | — | The other tills serve many people, a little each. The links show the one that serves a ring. |
| C1 | 8 | The register under the title. | *Ownership and sanctions* | A bank must not serve a sanctioned person, even through layers of companies. The customer looks clean; the question is who stands behind it. |
| C2 | 5 | `OWNERSHIP_20260930.csv` and `SANCTIONS_LIST_20260930.csv` as they landed. | — | An ownership register and a sanctions list. The names are spelled differently. |
| C3 | 9 | *Start a graph*: Entity Type *Party*, `SL-0137`, *Draw*; open the `name_match` link: score 0.986, same birth year, same nationality. | *Start a graph · SL-0137* | Start from the list entry. Each circle is a person, a company or a customer; this line is a name match, with its score and its reasons. |
| C4 | 7 | *Expand*, Degrees 3. | *Expand · 3 degrees* | Widen out three degrees, following who owns whom. |
| C5 | 10 | Six nodes held: the person, 60 %, the company, 50 %, the customer `CUS-00528`. | *60 % · 50 % · CUS-00528* | The matched person owns sixty percent of a company that owns half of the customer. Two hops up, a sanctioned person. A customer to escalate. |
| C6 | 7 | The chain held. | — | No single register row says it. The ownership links, and a name match with its reasons, do. |
| D1 | 8 | Card payments under the title. | *Card fraud* | When card data is stolen at one shop, the fraud shows up weeks later, everywhere else. Finding the shop is the hard part. |
| D2 | 5 | `CARD_AUTH_202603.csv` and `CHARGEBACK_20260930.csv` as they landed. | — | Card payments, and the chargebacks filed weeks later. |
| D3 | 8 | Saved view `card_common_point`, legend open. | *card_common_point* | This view links each defrauded card to the shops it paid at before the fraud. Each circle is a card or a merchant. |
| D4 | 7 | *Busiest nodes*. | *Busiest nodes* | Ask which merchant most of them share. Busiest nodes counts each circle's lines. |
| D5 | 10 | `M-317` and its 60 cards, held. | *M-317 · 60 cards* | One fuel station, three weeks in March, then sixty frauds, one to six weeks later. The likely point of compromise. |
| D6 | 7 | The view held. | — | A busy supermarket served far more cards and none were defrauded, so it is not even in this view. Only the links to the frauds point at the pump. |
| E1 | 8 | Bills of lading under the title. | *Trade finance* | Trade can launder money: ship the same goods round in a circle, and raise the invoice every time. |
| E2 | 5 | `BILL_OF_LADING_202603.csv` as it landed. | — | Bills of lading: container, shipper, consignee, ports, date. |
| E3 | 8 | Saved view `trade_circular_trade`, legend open. | *trade_circular_trade* | Each circle is a company or a container; each line says who shipped which box to whom. |
| E4 | 8 | Hover a box: `relayed`, its consignees and relays; then the `traded_with` links between the three companies. | *trade_circular_trade* | Six boxes stand apart, tied to the same three companies. Ask where they went. |
| E5 | 10 | The loop held: UAE → India → Malaysia → UAE, twice. | *6 containers · twice round · 450 681 → 788 128* | The same six containers, the same laptops, twice round three companies in three months, invoiced higher every time. Three companies to look at. |
| E6 | 7 | The loop beside a leasing pool box. | — | Each bill of lading is ordinary. Only the links show the goods going nowhere while the invoices grow. |
| F | 45 | Segment F, filmed when `la-crypto` is built. | *Crypto* | Written from its user guide when the Space ships. |
| 3 | 10 | Explore it: each Space's home page. | *Ships with Inspecto LA* · [the five Space names] | Every example here ships with Inspecto LA; the sample opens with one click. Explore them yourself: each comes with a short tour, and an exercise for every story, with the answers. |
| 4 | 7 | Your data. | *Your data · same steps* | These records are examples. Drop in your own, say what each column holds, and ask the same questions. |
| Close | 9 | The last graph, smaller, with an edition badge; then the Standard-and-above card. | *Everything shown: Inspecto LA Basic* · *More in Standard and above* | Everything you just explored is in Inspecto LA Basic. Standard and above turn a finding into evidence a team can share and defend. |
| 5 | 4 | End card. | *For financial crime and compliance.* · *Try it on your own data.* | Inspecto LA. |

### 3.3 · Insurance (about 2 min)

| | |
|---|---|
| Audience | Special Investigations Units at insurers. |
| Film on | Basic install (`la-claims` has a Basic tier since 2026-11-08; ids show unmasked there). |
| Ships as | *Link Analysis claims showcase* (`la-claims`); saved views `claims_network`, `claims_shared_identity`, `claims_provider_money`, `claims_parts_suppliers`, `claims_premium_payers`. |
| Ready | Now. A second segment can follow from the shipped parts-supplier or ghost-broker stories (exercises in the user guide), about 45 s each. |
| As built | The ring has no user-guide exercise; its path is the showcase runbook's. The whole `claims_network` is a dust cloud, and the busy clinic `CLI-01` tops every centrality: start from `claims_shared_identity` and a seed. |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | Claims fraud | `la-claims` (built) | Claim parties and claims (`CLAIM_PARTY_20260930.csv`, `CLAIM_20260930.csv`). | 12 claimants on 9 claims share 4 phones and 3 addresses, with garage `GAR-03`, clinic `CLI-04` and lawyer `LAW-02`; `CL-0134` is a passenger on 6 claims with 6 drivers. | Saved view `claims_shared_identity`; seed `CL-0134`, *Expand* twice (about 23 entities); toolbox ▸ *Communities*: one community of the 12 claimants with `GAR-03`, `CLI-04` and `LAW-02`. | One ring of 12 claims to refer to the investigators. | A family of four (`CL-0103`, `CL-0217`, `CL-0218`, `CL-0249`) on one phone and address, in a community of its own; the busy GP clinic `CLI-01`. |

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 9 | The claims list under the title. | Insurance · *Example · synthetic data* | Organised claims fraud looks like bad luck spread across many people. Each claim is small and plausible; the ring only shows when you see what the claimants share. |
| 2 | 6 | `CLAIM_PARTY_20260930.csv` as it landed. | — | Claim parties: names, birth dates, phones, addresses. Read row by row, every claim looks like its own. |
| 3 | 8 | The Data page: `claims_links_dataset`, built by the `claims_links` Job. | *One link Dataset · built by one Job* | One Job, set up once, turns the claims into links: who claimed together, who shares a phone or an address, which garage, clinic and lawyer. |
| 4 | 9 | Saved view `claims_shared_identity`; seed `CL-0134`; *Expand* twice. Legend open. | *claims_shared_identity · CL-0134* | Start from one claimant and widen out twice. Each circle is a person, a phone, an address or a provider; each line is a claim, a shared phone or a shared address. |
| 5 | 8 | Toolbox ▸ *Communities*. | *Communities* | Ask which of them belong together. Communities finds the groups that link mostly among themselves. |
| 6 | 10 | A beat of quiet; the community held. | *12 claimants · 4 phones · 3 addresses* | Twelve claimants sharing four phones and three addresses, one garage, one clinic and one lawyer. One ring to refer to the investigators. |
| 7 | 8 | The family of four in its own small group, beside the ring. | — | A family sharing one phone forms a group too, but a small one, with its own garage. The links tell a household from a ring. |
| 8 | 10 | The Space's home page, its saved views under *My work*. | *Ships with Inspecto LA · Link Analysis claims showcase* | This example ships with Inspecto LA. Open the claims showcase and explore it yourself: a parts-supplier ring and a ghost broker are planted in the same claims. |
| 9 | 7 | Your data. | *Your data · same steps* | These records are examples. With your own, you set up the join once, then ask the same questions. |
| Close | 9 | The last graph, smaller, with an edition badge; then the Standard-and-above card. | *Everything shown: Inspecto LA Basic* · *More in Standard and above* | Everything you just explored is in Inspecto LA Basic. Standard and above turn a finding into evidence a team can share and defend. |
| 10 | 4 | End card. | *For Special Investigations Units.* · *Try it on your own data.* | Inspecto LA. |

### 3.4 · Utilities (about 2 min)

| | |
|---|---|
| Audience | Revenue-protection teams at electricity utilities. |
| Film on | Basic install. |
| Ships as | *Utilities* (`la-utilities`, the industry Space composed from the `la-utility` use case): five stories in one year of synthetic data, every Dataset mapped, saved views `meter_tampering_contractor`, `account_churn_by_bank_account` and `prepaid_token_fraud_vendor`. |
| Ready | Now. Draft 3 (480p, 2026-11-08) was recorded from this script on the *Utilities* Space. |
| Optional shot | The loss-by-transformer Geo View, `TX-007` and `TX-019` over 15 %: label it *Inspecto LA Standard* (Geo Views are inert on Basic) and film it on a Standard or higher install. |
| As filmed | Every Dataset says what its id columns hold (*Premise*, *Transformer*, *Contractor*, *Account*, *Bank account*, *Vending point*) and has its own Mapping, so the Map beat shows the real column screen. The links the story uses still come from the `utility_links` Job, so the own-data line names the join. Picking `utility_links_dataset` in the Start box sets *Entity Type* to *Utility entity*. The ask starts from the 22 low meters, not from the contractor-network saved view: there, *Busiest nodes* ranks the busy contractors `C-27` and `C-21` first, and volume alone is not the story. |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | Electricity revenue protection | `la-utility` (built) | Meter reads and a log of field visits. | 22 premises under transformers `TX-007` and `TX-019` drop 40–70 % within 2 weeks of a visit by contractor `C-14`; transformer supply stays flat, so the two transformers' loss rises to 17.9 % and 24.7 %. | *Start a graph* ▸ *Pasted values*: the 22 low meters, drawn with their links. *Busiest nodes*: `C-14` first with 22 links, then `TX-007` and `TX-019` with 11 each. *Show on graph* on `C-14`. | 22 premises to inspect, one contractor to question. | A holiday home whose use drops every winter, with no visit (`PRM-0773`, not among the 22 pasted). |

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 10 | A transformer's daily reads as they landed, steady line after line; the title over them. | Utilities · *Example · synthetic data* | Utilities lose money to meter tampering. A bypass makes the meter read low, the bill drops, and the energy is still used. To the billing system it looks like a customer using less. |
| 2 | 9 | `METER_READ_202602.csv` and `FIELD_VISIT_20260930.csv` side by side in a plain viewer, scrolling slowly. | — | Here is a year of meter reads, and the log of field visits. Twenty-two meters started reading low; the transformers feeding them didn't. |
| 3 | 11 | The Data page: open `field_visit_dataset`: `premise_id` holds *Premise*, `contractor_id` holds *Contractor*, and its Mapping reads premise → contractor, kind `visit_type`, when `visit_at`. | *What each column holds* | Say once what each column holds. Here the premise column holds premises, the contractor column holds contractors, and each visit links the two. Every file in the Space is mapped the same way. |
| 4 | 13 | *Start a graph* ▸ *Start from* ▸ *Pasted values*; paste the 22 premise ids; *Start from these*. The 22 premises draw with their links; the legend stays open on `supplied_by`, `account_at` and `visited_by`. | *Pasted values · 22 low meters* | Paste the twenty-two low meters and draw them. Each circle is a premise, a transformer, an account or a contractor. Each line is a supply, an account or a visit. |
| 5 | 9 | Fold the legend; open the toolbox from the right rail; *Busiest nodes*. The list: `C-14` 22 links, `TX-007` 11, `TX-019` 11. | *Busiest nodes* | Now ask what they have in common. Busiest nodes counts each circle's lines, so whatever the low meters share rises to the top. |
| 6 | 12 | A beat of quiet; *Show on graph* on `C-14`; a gentle push-in on it, the premises around it. | *C-14 · 22 links · TX-007 and TX-019* | One contractor, C-14, visited every one of them, and they all sit under two transformers. Twenty-two premises to inspect, one contractor to question. |
| 7 | 9 | The meter file beside the graph, `C-14` at its centre. | — | In the meter file each premise is a row on its own, and the visits live in another file. The links put them on one picture, and the contractor they share stands out. |
| 8 | 10 | The Space's home page: *My work* lists `meter_tampering_contractor`, `account_churn_by_bank_account` and `prepaid_token_fraud_vendor`. | *Ships with Inspecto LA · Utilities* | This example ships with Inspecto LA. Open the Utilities Space and explore it yourself: four more stories are planted in the same data, from illegal reconnections to prepaid token fraud. |
| 9 | 10 | The raw files dissolving into the graph. | *Your data · same questions* | These records are examples. With your own, you set up the join once, then ask the same questions. |
| Close | 11 | The last graph, smaller, with an edition badge; then the Standard-and-above card, one line at a time. | *Everything shown: Inspecto LA Basic* · *More in Standard and above* | Everything you just explored is in Inspecto LA Basic. Standard and above turn a finding into evidence a team can share and defend. |
| 10 | 4 | End card. | *For revenue protection.* · *Try it on your own data.* | Inspecto LA. |

The list under `C-14` also offers *Add to exclusion list* (the product's standard hint for any busy node). The voice-over
passes over it; product decides whether that hint should stay on a node the analyst is looking at.

### 3.5 · Law enforcement (about 2 min)

| | |
|---|---|
| Audience | Police intelligence analysts. |
| Film on | Basic install. |
| Ships as | *Link Analysis police showcase* (`la-police`); saved views `police_burglary_series`, `police_handset_network`, `police_witness_link` (and the Geo View `police_incident_map`, inert on Basic). |
| Ready | Now. |
| Alternative segment | The drug-supply handset network: one controller number per period, its handset changing every 3–5 weeks, 75–80 % of about 60 customers carried over (look-alike: a delivery driver's phone with many short calls and no carry-over). |
| As built | The series is 18 burglaries; the saved view's tour speaks of the 13 that a dealer's recorded serial ties to a scene. Say 18 for the series and 13 for the serial matches. Links come from the `police_links` Job. |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | Criminal investigation | `la-police` (built) | Incident reports, stolen property, dealer sales and number-plate reads (`INCIDENT_REPORT_20261031.csv`, `DEALER_SALE_20261031.csv`). | 18 burglaries in Leeds North and Leeds East, 2026-02-04 to 2026-04-27; 21 of 53 stolen items sold to dealers `DLR-04` and `DLR-09` by sellers `P-00476`, `P-04874` and `P-00785`, the serial recorded on 13. `P-00476` lives at `ADR-1377` with `P-05167`, keeper of the car `ZX 9117 X`, read by cameras near 11 of the 18 scenes. | Saved view `police_burglary_series`: burglaries point at three sellers, the sellers at two dealers. Find the seller with the most `item_match` links (`P-00476`); follow `lives_at`, then `keeper_of` and `seen_near`. | Three lines of enquiry: the dealers, the sellers, the car. | The black taxi `ZX 3465 D`, seen near 54 incidents across the year and the city, none in the series; not in the view, no link to the series. |

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 9 | Incident reports under the title. | Law enforcement · *Example · synthetic data* | A burglary series rarely leaves a suspect at the scene. But stolen goods have to be sold, and the people selling them have homes and cars. |
| 2 | 6 | `INCIDENT_REPORT_20261031.csv` and `DEALER_SALE_20261031.csv` as they landed. | — | Incident reports, stolen property with serial numbers, second-hand dealers' purchase logs, and number-plate camera reads. Different systems, different teams. |
| 3 | 8 | The Data page: `police_links_dataset`, built by the `police_links` Job. | *One link Dataset · built by one Job* | One Job, set up once, joins them into links: which item was stolen where, who sold it, who lives where, and which car was seen near which scene. |
| 4 | 9 | Saved view `police_burglary_series`, legend open. | *police_burglary_series* | Each circle is a burglary, an item, a person, a dealer, an address or a car. Burglaries point at three sellers; the sellers point at two dealers. |
| 5 | 9 | The seller with the most `item_match` links; follow `lives_at`, then `keeper_of` and `seen_near`. | *item_match · lives_at · seen_near* | Ask who sold the most matching items, and follow that seller home. Who lives there, and what do they drive? |
| 6 | 10 | A beat of quiet; the series held as one group, the car joining it. | *2 dealers · 3 sellers · 1 car · 11 of 18 scenes* | Two dealers, three sellers, and a car kept at the seller's address, seen near eleven of the eighteen scenes. Three lines of enquiry. |
| 7 | 8 | The group held. | — | A taxi was seen near more incidents than that car, all over the city, and none in this series. The links tell a busy car from a connected one. |
| 8 | 10 | The Space's home page, its saved views under *My work*. | *Ships with Inspecto LA · Link Analysis police showcase* | This example ships with Inspecto LA. Open the police showcase and explore it yourself: a drug-supply phone network and a suspicious witness are planted in the same data. |
| 9 | 7 | Your data. | *Your data · same steps* | These records are examples. With your own, you set up the join once, then ask the same questions. |
| Close | 9 | The last graph, smaller, with an edition badge; then the Standard-and-above card. | *Everything shown: Inspecto LA Basic* · *More in Standard and above* | Everything you just explored is in Inspecto LA Basic. Standard and above turn a finding into evidence a team can share and defend. |
| 10 | 4 | End card. | *For intelligence analysts.* · *Try it on your own data.* | Inspecto LA. |

### 3.6 · Public sector (about 2 min 30 s)

| | |
|---|---|
| Audience | Public-procurement integrity units, benefits-integrity and tax-compliance teams. |
| Film on | Basic install. |
| Ships as | *Link Analysis procurement fraud showcase* (`la-procurement`) and *Link Analysis benefits and tax fraud showcase* (`la-benefits`), both Basic. |
| Ready | Now: both Spaces are built. |
| Alternative segments | `la-procurement` also ships cover bids from one IP address, an official approving changes for a relative's firm, and a debarred firm reborn under its old directors; `la-benefits` ships payments to the deceased, undeclared work, and a tax-preparer refund ring. Each has its own saved view and look-alike in its user guide. |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | Procurement | `la-procurement` (built) | Tenders, bids and directors (`TENDER_202601.csv`, `BID_202601.csv`, `DIRECTOR_20260930.csv`). | `CO-0117` Aire Paving, `CO-0242` Thistle Roadworks, `CO-0388` Wren Highways and `CO-0561` Quill Highways are the only bidders on the 24 Highways North tenders, winning in turn; losers bid 5–12 % above the winner. `CO-0242` and `CO-0561` share a director, `P-00001`. | Saved view `procurement_bid_rotation` (the Road maintenance market): one block of tenders is bid on by the same four firms and nobody else; hover the `bid_on` links (losers 5–12 % above); read `won` in date order; the `directed_by` links show the shared director. | A bidding pattern to refer, with the shared director named. | `CO-0021`, `CO-0055` and `CO-0090` win about three in four Highways South tenders, but others bid against them, the lowest bid always wins, and they share no director with the four. |
| B | Benefits and tax | `la-benefits` (built) | Benefit claims and household declarations. | Address farm: `AD-03141` has 14 residents, all claimants under 5 surnames, each declaring a household of one; all 14 are paid into 3 accounts (`SYN-57707034`, `SYN-19642862`, `SYN-47766627`) that nobody else uses. | Saved view `benefits_shared_addresses` (every address with 8 or more claimants): three care homes drawn as stars, and `AD-03141`. Click `AD-03141` for its node detail; its 14 claims meet in the 3 accounts. Not *Busiest nodes*: it would rank the larger care homes first. | 14 claims and 3 accounts to review. | The three care homes in the same view (`AD-00412` Linden Court, 31 residents; `AD-02718` Harbour View, 24; `AD-05077` Orchard House, 27): one declaration each, every resident paid into their own account. They are on screen, so the voice-over names them as genuine. |

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 8 | Opening: tender awards and benefit claims flash past, raw. | Public sector · *Example · synthetic data* | Public money leaks two ways: contracts won by firms that agree not to compete, and benefits paid to claims that are not what they say. |
| 2 | 8 | Map: the Data page, a Job-built link Dataset. | *One link Dataset · built by one Job* | Each of these began as a raw export. A Job, set up once, joins each Space's files into one list of links. |
| A1 | 8 | Tenders under the title. | *Procurement* | When firms take turns to win, every tender looks competitive and the public still pays too much. |
| A2 | 5 | `TENDER_202601.csv` and `BID_202601.csv` as they landed. | — | Tenders, bids and amounts, and a register of company directors. |
| A3 | 9 | Saved view `procurement_bid_rotation`, legend open. | *procurement_bid_rotation* | This is the whole road-maintenance market. Each circle is a tender, a firm or a director; each line is a bid, a win or a directorship. |
| A4 | 8 | The closed block of tenders; hover a `bid_on` link; read `won` in date order. | *bid_on · won* | Ask who bids where. One block of tenders draws the same four firms and nobody else, and every losing bid is a little higher than the winner's. |
| A5 | 10 | A beat of quiet; the four firms and director `P-00001`, held. | *4 firms · 24 tenders · 1 director* | Four firms win twenty-four tenders in turn, and two of them share a director. A bidding pattern to refer. |
| A6 | 7 | The rotation beside the busy firms of the south. | — | Three other firms win even more often, but against real rivals. The links show who competes, and who only pretends to. |
| B1 | 8 | Claims under the title. | *Benefits and tax* | One address can hide many false claims: people who say they live alone, each paid separately. |
| B2 | 5 | Claims and household declarations as they landed. | — | Claims and household declarations: a name, an address, a bank account. |
| B3 | 8 | Saved view `benefits_shared_addresses`: four addresses; three draw as big stars. Legend open. | *benefits_shared_addresses* | This view keeps every address with eight or more claimants. Each circle is a person, a claim, an address or an account. Three are care homes, each resident paid into their own account. |
| B4 | 7 | Click `AD-03141`: its node detail. | *Node detail* | Ask what the fourth one is. |
| B5 | 10 | Its 14 claims meeting in 3 accounts, held. | *AD-03141 · 14 claimants · 3 accounts* | The fourth is a house: fourteen people, each living alone, paid into three accounts that nobody else uses. Claims to review. |
| B6 | 7 | The house beside a care home. | — | A care home and a farmed address both hold many claimants. Only the links to the money tell them apart. |
| 3 | 10 | Explore it: each Space's home page. | *Ships with Inspecto LA* · [the two Space names] | Both examples ship with Inspecto LA. Open them and explore: each comes with a short tour, and an exercise for every story, with the answers. |
| 4 | 7 | Your data. | *Your data · same steps* | These records are examples. With your own, you set up the join once, then ask the same questions. |
| Close | 9 | The last graph, smaller, with an edition badge; then the Standard-and-above card. | *Everything shown: Inspecto LA Basic* · *More in Standard and above* | Everything you just explored is in Inspecto LA Basic. Standard and above turn a finding into evidence a team can share and defend. |
| 5 | 4 | End card. | *For public-sector integrity.* · *Try it on your own data.* | Inspecto LA. |

### 3.7 · Healthcare (about 2 min)

| | |
|---|---|
| Audience | Program-integrity teams at health insurers and public payers. |
| Film on | Basic install. |
| Ships as | *Healthcare* (`la-healthcare`, the industry Space composed from the `la-health` use case, every Dataset mapped); saved views `referral_kickbacks`, `phantom_billing`, `pill_mill`, `upcoding_clinics`. |
| Ready | Now. |
| As built | The shipped story is a shared home, not a spouse: the centre's owner lives at one address with the owner of one referring practice. Concentration alone is not the finding; the shared home is. Only Brackenmoor's owner has that tie; say nothing of the second York practice beyond its referrals. |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | Provider billing | `la-health` (built) | Referrals, providers and owners (`REFERRAL_202604.csv`, `PROVIDER_20260930.csv`, `OWNER_20260930.csv`). | `PR-1426` Brackenmoor Surgery (68 of 79 referrals) and `PR-1392` Kilnworth Medical Practice (182 of 216), both in York, send their referrals to `PR-1170` Emberlea Imaging Centre. The centre's owner `OW-0434` lives at `AD-05150` with `OW-1270`, the owner of Brackenmoor Surgery. | Saved view `referral_kickbacks`: nine practices; seven point at the only hospital in their town, two at the imaging centre; follow `owned_by` from both ends of the link, then `lives_at`. | A conflict of interest to look into. | `PR-0828` Elder Grove Medical Practice sends 38 of 44 referrals to `PR-1129` Saltmere Bay Hospital, the only hospital in Scarborough, owned by a health trust; six more Scarborough practices do the same. |

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 9 | Referral counts under the title. | Healthcare · *Example · synthetic data* | When a doctor profits from where patients are sent, the referrals stop following need. Payers see the claims, not the owners behind them. |
| 2 | 6 | `REFERRAL_202604.csv` and `OWNER_20260930.csv` as they landed. | — | Referrals: which practice sent which patient where. And the enrolment file: who owns each provider, and where they live. |
| 3 | 8 | The Data page: `health_links_dataset`, built by the `health_links` Job. | *One link Dataset · built by one Job* | One Job, set up once, joins them into links: who referred to whom, who owns what, and who lives where. |
| 4 | 9 | Saved view `referral_kickbacks`, legend open. | *referral_kickbacks* | This view keeps every practice that sends most of its referrals to one place. Each circle is a practice, a provider, an owner or a home. |
| 5 | 9 | Seven practices pointing at the town hospital; two at the imaging centre; follow `owned_by`, then `lives_at`. | *owned_by · lives_at* | Seven practices send nearly everyone to the only hospital in town: that's geography. Two send theirs to a private imaging centre. Ask who owns each end. |
| 6 | 10 | A beat of quiet; the two owners meeting at `AD-05150`, held. | *Emberlea · Brackenmoor · one home* | The imaging centre's owner and the owner of one referring practice share a home. A conflict of interest to look into. |
| 7 | 8 | The town hospital's practices beside the imaging centre's. | — | Every practice here sends most referrals to one place. Only the ownership links show which of them has a reason to. |
| 8 | 10 | The Space's home page, its saved views under *My work*. | *Ships with Inspecto LA · Healthcare* | This example ships with Inspecto LA. Open the Healthcare Space and explore it yourself: phantom billing, a pill mill and upcoding clinics are planted in the same data. |
| 9 | 7 | Your data. | *Your data · same steps* | These records are examples. With your own, you set up the join once, then ask the same questions. |
| Close | 9 | The last graph, smaller, with an edition badge; then the Standard-and-above card. | *Everything shown: Inspecto LA Basic* · *More in Standard and above* | Everything you just explored is in Inspecto LA Basic. Standard and above turn a finding into evidence a team can share and defend. |
| 10 | 4 | End card. | *For program integrity.* · *Try it on your own data.* | Inspecto LA. |

### 3.8 · Logistics (about 2 min)

| | |
|---|---|
| Audience | Logistics security teams and cargo insurers. |
| Film on | Basic install. |
| Ready | When `la-logistics` is built: write its script from the Space's user guide then, with the same beats as 3.5. |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | Cargo theft | `la-logistics` (planned) | Trip logs, scan logs and loss reports. | Inside job: 12 losses share one driver pair, and one night-shift scanner is on 10 of them. | Written when the Space is built, from its user guide. | Three people to interview. | A high-volume driver on the busiest route with 2 losses in a year. |

Problem line to start from: *Cargo goes missing on different routes in different months, and each loss is filed on its
own. An inside job only shows when the losses are put side by side.*

### 3.9 · Enterprise (about 2 min 30 s)

| | |
|---|---|
| Audience | Internal audit, accounts-payable control, security, HR and internal investigations. All people and systems are invented. |
| Film on | Advanced install (`la-audit`); identifiers are masked there, so film as a user who can reveal them. |
| Ships as | *Link Analysis audit showcase* (`la-audit`, Advanced); saved views `ap_approval_graph`, `ap_vendor_identity`, `gl_intercompany`. `la-insider` is planned. |
| Ready | Segment A now; B when `la-insider` is built. |
| As built | The busy vendor `V-001` and the CFO `E-01` lead betweenness and are not findings: seed first, never start from a whole-graph ranking. |

| Seg | Use case | Space | Raw records | Story | Ask and reveal | Outcome | Look-alike |
|---|---|---|---|---|---|---|---|
| A | Internal audit | `la-audit` (built) | Vendor master and employee master (`VENDOR_MASTER_20260930.csv`, `EMPLOYEE_MASTER_20260930.csv`). | Vendor `V-117` shares bank account `BA-9031` and address `AD-9031` with employee `E-31`; its 14 invoices were all approved by `E-31`'s manager, `E-15`. | Saved view `ap_vendor_identity`; seed `V-117`, *Expand*: `E-31` joined by both `shares_account` and `shares_address`; *Connect seeds* between `V-117` and `E-31`; then `ap_approval_graph`: the only approver is `E-15`. | A conflict of interest to investigate. | The busy utilities vendor `V-001` and the CFO `E-01`: they lead betweenness and are not findings. |
| B | Insider threat | `la-insider` (planned) | Access grants and a change log. | Access creep: manager `M-7` grants payroll and treasury access to 4 reports with no ticket; one later edits a vendor's bank details. | Written when the Space is built, from its user guide. | An access review to start with one manager. | A role change with an HR record and a ticket. |

| # | ≈ s | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 8 | Opening: vendor records and an access log flash past, raw. | Enterprise · *Example · synthetic data* | Some of the costliest fraud comes from inside: a vendor that is really an employee, or access nobody approved. |
| 2 | 8 | Map: the Data page, `ap_links_dataset`, built by the `ap_links` Job. | *One link Dataset · built by one Job* | Each of these began as a raw export. A Job, set up once, joins vendors, staff, invoices and approvals into links. |
| A1 | 8 | Vendor records under the title. | *Internal audit* | A new vendor gets set up, invoices arrive, and they are approved. Nobody checks whose bank account the money goes to. |
| A2 | 5 | `VENDOR_MASTER_20260930.csv` and `EMPLOYEE_MASTER_20260930.csv` as they landed. | — | The vendor master and the employee master, kept in different systems. |
| A3 | 8 | Saved view `ap_vendor_identity`; seed `V-117`; *Expand*. Legend open. | *ap_vendor_identity · V-117* | Start from one vendor and widen out. Each circle is a vendor, an employee, a bank account or an address; each line says who shares what. |
| A4 | 8 | *Connect seeds* between `V-117` and `E-31`; then `ap_approval_graph`. | *Connect seeds* | Ask how the vendor connects to this employee, and who approved its invoices. |
| A5 | 10 | A beat of quiet; `V-117`, `E-31` and `E-15`, held. | *V-117 · E-31 · BA-9031 · AD-9031* | The same bank account and the same address as one of your own employees, and fourteen invoices, all approved by that employee's manager. A conflict to investigate. |
| A6 | 7 | The two masters side by side. | — | Neither file says it alone. The shared account and address only meet in the links. |
| B | 45 | Segment B, filmed when `la-insider` is built. | *Insider threat* | Written from its user guide when the Space ships. |
| 3 | 10 | Explore it: the Space's home page. | *Ships with Inspecto LA · Link Analysis audit showcase* | This example ships with Inspecto LA. Open the audit showcase and explore it yourself: split invoices, duplicate payments and intercompany money are planted in the same books. |
| 4 | 7 | Your data. | *Your data · same steps* | These records are examples. With your own, you set up the join once, then ask the same questions. |
| Close | 9 | The last graph, smaller, with an edition badge; then the Standard-and-above card. | *Everything shown: Inspecto LA Basic* · *More in Standard and above* | Everything you just explored is in Inspecto LA Basic. Standard and above turn a finding into evidence a team can share and defend. |
| 5 | 4 | End card. | *For internal audit and investigations.* · *Try it on your own data.* | Inspecto LA. |

---

## 4 · Retire the red string (about 40 s)

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
| 5 | 4 | Left fades out; the graph holds. | *Ships with Inspecto LA · explore it* · *Your data · same steps* | Bring your own records. |
| Close | 4 | The graph holds; the edition badge, then the Standard-and-above card. | *Everything shown: Inspecto LA Basic* · *More in Standard and above* | *(no voice-over)* |
| 6 | 4 | End card. | *Retire the red string.* · *Try it on your own data.* | Inspecto LA. |

---

## 5 · It stays current (about 1 min 30 s)

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
| 1 | 10 | The stolen-property saved view open on the burglary series, the legend open. | *Law enforcement* | A burglary series rarely ends with the last break-in: the stolen goods keep surfacing for months, at dealers, through sellers. On this graph each circle is a scene, an item, a seller or a dealer, and each line is where an item went. |
| 1b | 5 | Hold on the series. | — | Last month you linked the series and saved the view. |
| 2 | 6 | A file explorer: November's files are copied into the inbox folders; one opens in a plain viewer, raw rows. | *Example · synthetic data* | This month's records arrive as plain files: items, sellers, dates. |
| 3 | 6 | *Getting started*: *What changed in your data* shows the new files were brought in. | *What changed in your data* | Inspecto LA brings them in and tells you what changed. |
| 4 | 5 | *My work* ▸ open the saved view. The graph redraws over the new data. | *My work* | Open the same view… |
| 5 | 7 | A beat of quiet; the new serial match highlighted: a stolen item, sold by one of the series sellers. | *1 new match* | …and another stolen item turns up, sold by a seller you already know. |
| 6 | 7 | Click it: the node detail dialog. | — | Same question, fresh answer: one more item for the series. Nobody had to search November's records for it: the saved view asked again when it opened. |
| 7 | 4 | **Advanced, when built:** an Alert raised by the Alert Rule on new serial matches. | *Inspecto LA Advanced* | Or let an Alert Rule ask it for you. |
| 7b | 9 | The `la-police` home page: its saved views under *My work*. | *Ships with Inspecto LA* · Link Analysis police showcase | This example ships with Inspecto LA, November's files included. Open it, drop the new month in yourself, and watch the series grow. |
| 8 | 6 | The Data page: a Dataset's *Inbox*, *Watch this folder* switched on. | *Watch this folder* · *Your data · same steps* | Your own files work the same way: point it at the folder they land in. |
| Close | 9 | The last graph, smaller, with an edition badge; then the Standard-and-above card, one line at a time. | *Everything shown: Inspecto LA Basic* (with shot 7 in: *Everything else shown: Inspecto LA Basic*) · *More in Standard and above* | Everything you just explored is in Inspecto LA Basic. Standard and above turn a finding into evidence a team can share and defend. |
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

## 7 · One graph for any data (about 100 s)

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
| Close | 9 | The last graph, smaller, with an edition badge; then the Standard-and-above card, one line at a time. | *Everything shown: Inspecto LA Basic* · *More in Standard and above* | Everything you just explored is in Inspecto LA Basic. Standard and above turn a finding into evidence a team can share and defend. |
| 11 | 6 | **Industry index**: nine tiles (Telecom, Banking and finance, Insurance, Utilities, Law enforcement, Public sector, Healthcare, Logistics, Enterprise), each lighting in turn. | *Find your industry* · *Every example ships with Inspecto LA* | Each is an example that ships with Inspecto LA. Watch the one for your industry, open it and explore it, then try the same on your own records. |
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

## What these videos show, and what Standard and above add

[EDITIONS.md](../../../../EDITIONS.md) is the reference for editions; this is the summary that the scripts and the
edition close draw on.

**Basic: the features these videos explore.** Every feature below is in every edition, Basic included.

| Feature (every edition) | Explored in |
|---|---|
| *Try the sample*, *Getting started* and *What can I do next?* | 2 |
| *Add data* ▸ *Onboard a file*: detected columns, *A guess*, *What does each column hold?*, *Create and run* with what was kept and set aside | 7 |
| The *Entities* page: Entity Types and *Compare values as* | 7, and the Map beat of every video |
| The Data page: Datasets, what their columns hold, and Mappings | The Map beats; video 7's alternative cut |
| *Watch this folder* and *What changed in your data* | 5 |
| *Start a graph* (including *Pasted values*), saved views and *My work* | 1, 5, the industry videos (3.4 starts from pasted values) |
| *Busiest nodes* and *Tight groups* (graph algorithms in the browser) | 1, the industry videos |
| *Exclude…* | 1 |
| *Route from A to B* (find paths) | 2, 4, the industry videos |
| *Expand* (neighbours) | 3.7, 3.9 |
| *Pattern match* (patterns) | 3.2 |
| The node detail dialog | 3.6, 5 |
| Graph model ▸ *Entity/Link (several Datasets)* | 7 (product to confirm it is on Basic) |

**Standard and above add.** The Investigate rows and everything below them need the Investigations add-on, which
Standard and Advanced include by default; sign-in, roles and enforced masking come with the edition itself.

| Area | Standard and above |
|---|---|
| Data | *Keep this up to date* on a schedule (a watched folder or an SFTP feed); a retention policy; enrichment on the Dataset page; another database or a remote system, through a Pipeline. |
| Explore | The Geo map, routes and co-location; entity profile pages; Link analysis and Geo map widgets on dashboards. |
| Investigate | Investigations: a sealed history of every step, replay, compare, temporal findings. The Dossier and Dossier bundle, with verification. Templates and external references. Data trust: the data strip, Files, coverage and delivery Alerts. |
| Team | Sign-in, members and roles; four-eyes approval; enforced masking with audited reveal; drafts, so several analysts can work in parallel. |
| Share | Embed links (a read-only view of an Investigation); Case linking, and Case history beside a node. |
| Scale | Graph runs on the server, the link index and daily ingestion. |
| Operate | Alert Rules on Investigations and standing detection; the Assistant's proposed column mappings. |
| Integrate | Third-party integration (case managers, fraud products). |
| Advanced only | Investigations stored on PostgreSQL and shared by several servers; Access Policies (attribute-based, only ever narrowing what roles allow). |

---

## Open points before filming

- Supply the CTA URL for the end card.
- Draft round: name who records the 480p drafts and where the files are kept, outside the repository.
- Video 2: time the run on the final build and the filming laptop; use the median wherever a time is said or shown.
- **"Ships with Inspecto LA", from inside the app:** only `la-sample` is created in the app (*Try the sample*, or *New
  from sample…* in the Space switcher). Every other showcase arrives as its Space and data downloads beside the product.
  On a three-download Basic install (2026-11-08), *New from sample…* said *No sample is available on this server*.
  Product confirms how a user adds a showcase from inside the application (the template gallery or the switcher), and
  the *Explore it* line is worded to match, before filming.
- **Own-data line, per story:** product confirms that each story's outcome can be reached from its raw files with
  *Add data* and the analysis shown. Where a Space also relies on something set up once (a Job that derives links, a
  prepared saved view), that story's line names it (*…set up the join once*) instead of "same steps". Every showcase
  except `la-sample` builds its links with a Job, so the industry videos use the join wording.
- **Raw beats:** pick, per story, the landed file and the rows to show (ideally rows holding the story's ids), and
  check that the Data page shows what the Map beat's voice-over says for each Dataset filmed.
- **Try every ask on the live Space before filming,** not only on paper. In 3.4 the scripted *Busiest nodes* on the
  contractor-network view would have named the wrong contractor; the draft found it. The asks for 3.1–3.9 now follow
  each Space's user guide or showcase runbook; confirm on Basic that *Communities* (3.3) and *Value Measures* (3.1 A,
  3.2 B) behave as described, and that *Tight groups* finds the device farm in video 1 (else use `mm_device_farm`).
- Industry videos: check every reveal against the Space's ground truth file before filming. Segments on planned
  Spaces (3.1 B, 3.2 F, 3.8, 3.9 B) are filmed only after their Spaces are built, and scripted from their user guides
  then. Decide per industry whether to publish with built segments only or wait.
- Segment 3.2 A has no look-alike in the sample; confirm with its owner that nothing else on screen reads as suspect.
- Video 5: shot 7 waits for the `la-police` Advanced tier (its Alert Rule); shot 8 is filmed on the video 7 Space.
- Video 7: generate the two synthetic files with the planted mess (a number written two ways, gaps, a repeat, a
  second time format) and a shared handful of numbers, from a range no real subscriber can have; product confirms
  which number formats *Compare values as: Phone number* treats as equal, which rows are set aside, and that Graph
  model's *Entity/Link (several Datasets)* is on Basic. Film it after the industry videos it introduces, so shot 10
  can use their footage.
- Filming needs both a Basic install (videos 2, 5, 7, 3.3–3.8) and an Advanced install (videos 1, 4, 6, 3.1, 3.2,
  3.9); on the Advanced install, film as a user who can reveal identifiers.
- Product and legal sign-off on every script before the agency starts.
