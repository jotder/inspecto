# Inspecto LA short videos — scripts and agency briefs

> **Six short videos, each filmed on the real product over synthetic data: a one-page brief for the agency, then a shot-by-shot script with voice-over.**

*Audience: marketing, presales and the video agency. Product Map Item `module.la-core`. Draft of 2026-11-07, not
yet reviewed by product or legal. Every product claim below is taken from the
[Link Analysis user guide](user-guide-link-analysis.md); the [whitepaper](brochure-inspecto-la-whitepaper.md) is
the reference for anything wider.*

| # | Title | Length | Edition shown | Data | Ready to film |
|---|---|---|---|---|---|
| 1 | The ring nobody saw | 45 s | Basic | `la-mobile-money` | Yes |
| 2 | Zero to graph in 60 seconds | 60 s | Basic | *Try the sample* | Yes, after the timing check |
| 3a | Follow the claim (insurance) | 30 s | Basic | `la-claims` | Yes |
| 3b | Follow the caller (police) | 30 s | Basic | `la-police` | No: the Space is planned |
| 3c | Follow the meter (utility) | 30 s | Basic | `la-utility` | No: the Space is planned |
| 4 | Retire the red string | 30 s | Basic | `la-mobile-money` | Yes |
| 5 | It stays current | 45 s | Basic, plus one Standard shot | `la-mobile-money` + one prepared file | After data preparation |
| 6 | The analyst (dramatization) | 60 s | Standard | `la-mobile-money` | Yes, after casting |

---

## Common brief (applies to every video)

**Product name.** *Inspecto LA* in voice-over and titles. "Link Analysis" is the name of the capability
inside the product, and may appear on screen.

**Tone.** Calm, precise and confident. The product's own voice is plain and honest: it says what it did and
what it set aside. The videos should sound the same. No thriller music, no hacker clichés (green code rain,
hooded figures), no "AI magic" language.

**Filming rules (binding).**
- Film **only synthetic Spaces**: `la-mobile-money`, `la-claims`, *Try the sample*, and the police and utility
  showcases once they exist. Never film any other Space on the demo machine. Some hold real customer data.
- Before every capture session, set the browser's current Space explicitly to the Space being filmed, and check
  the Space name in the left rail in the first frame.
- Numbers on screen must be the numbers the product shows. Do not edit a count, a name or a timing in post.
  If a shot needs a different number, change the data and re-film it.
- A feature tagged **Standard** must carry the on-screen label *Inspecto LA Standard* while it is visible.
  Untagged shots are on Basic, the entry edition.

**Capture.** 1920 × 1080 screen capture at 60 fps, browser at 100 % zoom, light theme, a clean browser profile
with no bookmarks bar or extensions. Hide the mouse pointer except where a shot calls for a click. Slow,
deliberate pointer moves; let each result stay on screen for at least 1.5 s.

**Deliverables per video.** A 16:9 master; 9:16 and 1:1 cut-downs for social (re-framed on the graph, not
letterboxed); burned-in captions on every version, because most social plays start muted; a clean version
without captions; SRT files.

**Voice-over.** About 2.5 words a second. The word counts below fit the lengths given. One voice across the
series.

**Audio.** One music bed for the series, understated, with a single soft accent on each reveal shot.

**End card (every video).** Inspecto LA logo · the line *See who is connected.* · `[CTA URL — to be supplied]`.

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
| Data | `la-mobile-money` (14 days of synthetic wallet transfers). The busy merchant is `MER-ELECTRIC` (a benign biller), and the planted ring is the nine-wallet device farm. |

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
| Data | *Try the sample* on a fresh Basic install with no Spaces. The sample graph has 112 entities and 224 links (measured on a Basic bundle on 2026-11-07). |
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

## 3 · Follow the claim / the caller / the meter (3 × 30 s)

One template filmed three times, one industry each. The edit, the music and the voice-over structure stay the
same, so the agency builds it once.

**Brief (shared)**

| | |
|---|---|
| Objective | One video per industry, so sales can send the one that fits the prospect. |
| Audience | Special Investigations Units (insurance), police intelligence analysts, utility revenue-protection teams. |
| Channel | Sales email, LinkedIn targeted by industry, trade-show loop per stand. |
| Message | Different industry, same move: the link you were missing is one click away. |
| Template | Hook (the industry's problem in one line) → the graph → one click → the reveal → end card with the industry line. |
| Must not | Use real place, company or person names. Every name on screen comes from the synthetic data. |

**Template (fill per industry)**

| # | Time | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 0–5 | Industry establishing shot: documents or records on screen, never stock footage of people. | [Industry] | [Hook line] |
| 2 | 5–11 | The graph of the Space, wide. | — | [What the data is] |
| 3 | 11–18 | One click: the analysis named in the row below. | [Analysis name] | [What the click asks] |
| 4 | 18–25 | The reveal, held. | — | [What it shows] |
| 5 | 25–30 | End card with the industry line. | [Industry line] | Inspecto LA. |

### 3a · Insurance — Follow the claim (`la-claims`, ready)

| # | Voice-over | Picture detail |
|---|---|---|
| 1 | Twelve claims. Twelve different claimants. Nothing in common. | Claims list. |
| 2 | Draw them as links: people, phones, addresses, garages. | The claims graph, wide. |
| 3 | Ask which of them belong together. | *Tight groups*. |
| 4 | Four phones. Three addresses. One garage, one clinic, one lawyer. | The planted ring: 12 claimants sharing 4 phones, 3 addresses, one garage, clinic and lawyer. |
| 5 | Inspecto LA. | End card line: *For Special Investigations Units.* |

### 3b · Police — Follow the caller (`la-police`, planned)

| # | Voice-over | Picture detail |
|---|---|---|
| 1 | The handset changes every few weeks. The customers don't. | Call records. |
| 2 | Draw the calls as links. | The handset network over time (saved view). |
| 3 | Line up the numbers, period by period. | Time filter stepping through periods. |
| 4 | New handset, same sixty customers. One controller. | The controller handset rotation, with 70 % of customers carried over. |
| 5 | Inspecto LA. | End card line: *For intelligence analysts.* |

### 3c · Utility — Follow the meter (`la-utility`, planned)

| # | Voice-over | Picture detail |
|---|---|---|
| 1 | Twenty-two meters started reading low. The transformer didn't. | Consumption table. |
| 2 | Draw premises, visits and contractors as links. | Contractor C-14 network (saved view). |
| 3 | Ask who they have in common. | *Busiest nodes* on the selection. |
| 4 | One contractor visited them all, two weeks before the drop. | Contractor C-14 at the centre of the 22 premises; optionally the Geo view of loss by transformer area. |
| 5 | Inspecto LA. | End card line: *For revenue protection.* |

*Voice-over: about 30 words each.*

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
| Data | `la-mobile-money`. The route is the planted 98 % pass-through mule chain that ends at agent `AG-39`; take its first wallet from the Space's ground truth. |
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
| Audience | Fraud operations leads who run the same checks every week. |
| Channel | Website, sales follow-up email. |
| Message | Drop next month's file in a folder; your saved view shows what changed when you open it. |
| Must show | *Watch this folder*; *What changed in your data*; reopening a saved view. The Alert shot only with its edition label. |
| Must not | Say the graph redraws by itself while it is open. It re-reads the data when the view is opened. |
| Edition | Shots 1–6 Basic. Shot 7 (the Alert) **Standard**. Cut shot 7 for a Basic-only version. |
| Data preparation | A second `la-mobile-money` file in which a new wallet joins the planted ring. Prepare it from the generator, check it in a dry run, and keep it with the filming kit. **Product must confirm shot 7's Alert set-up before filming.** |

**Script**

| # | Time | Picture | On-screen text | Voice-over |
|---|---|---|---|---|
| 1 | 0–6 | The saved view *Mule ring* open on the ring. | *Mule ring* | Last week you found the ring and saved the view. |
| 2 | 6–12 | A file explorer: a new month's file is dropped into the watched folder. | *Watch this folder* | This week's transfers land in a folder. |
| 3 | 12–18 | *Getting started*: *What changed in your data* shows the new file was brought in. | *What changed in your data* | Inspecto LA picks them up and tells you what it brought in. |
| 4 | 18–25 | *My work* ▸ open *Mule ring*. The graph redraws over the new data. | *My work* | Open the same view… |
| 5 | 25–32 | A new wallet now sits inside the ring. Highlight it. | — | …and there's a wallet that wasn't there last week. |
| 6 | 32–37 | Click it: the node detail dialog. | — | Same question. Fresh answer. |
| 7 | 37–41 | **Standard:** an Alert in the Alerts list, opened from the Measure that watches the ring. | *Inspecto LA Standard* | Or let an Alert Rule ask it for you. |
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
| Edition | **Standard**: Investigations, the step log and the Dossier are not in Basic. |
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

## Open points before filming

- Supply the CTA URL for the end card.
- Video 2: time the run on the final build and the filming laptop; set the title from the median.
- Video 3b and 3c: film after `la-police` and `la-utility` are built (see the showcase plan in
  `docs/superpower/`). Check each reveal shot against the Space's ground truth file.
- Video 5: prepare the second data file; product confirms the Alert set-up for shot 7.
- Product and legal sign-off on every script before the agency starts.
