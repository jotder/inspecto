# Inspecto LA case videos v2 — twenty industry cases, re-specified to the training-series standard

> **Status 2026-11-19 — PROPOSED, nothing re-recorded.** Board row `LA-CASE-VIDEOS-2`. Operator request: recreate the
> specs for the twenty industry example / case videos with the quality of the training series, with a better voice and
> audio style, stronger case content and a better presentation style; update the scripts and the plan. This plan fixes the
> standard (§3–§5) and the order of work (§8); the twenty specs are written under it (§6) and a rendering pass follows
> operator sign-off on the open decisions (§9).
> Inputs: v1 scripts [`brochure-video-demo-case-scripts.md`](../okf/product/module/la-core/brochure-video-demo-case-scripts.md)
> (generated from `tools/la-demo-cases.mjs`, nineteen cases) and the hand-built money-mule ring video; the training series
> [`2026-11-16-la-training-series-plan.md`](2026-11-16-la-training-series-plan.md) (all 41 episodes rendered 2026-11-19; the
> method, the pipeline and the QA that worked are reused here).

## 1. Goal and scope

- **Twenty videos**, one per bundled case: the nineteen in `tools/la-demo-cases.mjs` plus the money-mule ring
  (`Banking_MuleRing`). Each is a *case film*: one planted story in one showcase Space, told from the stakes to the lead,
  recorded live on the shipped Standard build, signed in with the fake Demo Auth sign-in, in the bundled example Spaces (§4.5; the Basic-to-Standard step is shown once, in §4.4, not in every film).
- **Audience:** a prospect, a new analyst or a team lead who wants to see *what Inspecto LA finds and how a team would act
  on it* in their industry. Not a feature tour; the training series is the tour.
- **Done means:** (a) the twenty specs follow §5 and pass the spec gates in §7; (b) every figure, id and screen in a spec
  was checked against the Space's ground truth and a live screen (masked or not, as the analyst sees it); (c) after
  operator sign-off, each film is rendered at 4–5 minutes and passes the frame QA of §7; (d) v1 is marked superseded.

## 2. What v1 is, and what is weak about it

v1 (2026-11-15; nineteen films from one generator, one hand-built) is a plain full-screen live walk-through with the
Piper female voice (`tools/la-video-voice.mjs`), generic zoom and box marks, nothing else. Read against the training
series, the weaknesses are:

1. **One skeleton for every case.** All nineteen run the same sixteen scenes (problem, data, mapping, graph, sweep,
   narrow down, reveal, evidence, conclusion, Investigation, Working Set, step log, team, Dossier, Entity List,
   conclusion). The cases differ in a few sentences; the films feel like one film nineteen times and run long. The
   product tour (data, mapping, Investigation, team, Dossier) crowds out the case.
2. **The story is told as a brochure.** The problem is stated, never felt: no person, no decision, no moment where the
   analyst could be wrong. No decoy is ruled out in most films (the strongest ones, SIM swap with the busy store S-72
   and the device farm with the family phone, do it by accident). "The outcome is a lead, not a verdict" is repeated
   in every film as a disclaimer instead of being shown as a next action.
3. **Marks are generic.** The toolbox list and the Start-a-graph form look the same in every Space, so the marks point at
   the same places; node marks were hand-placed pixel boxes. The training series replaced them with computed impact
   marks (`impact` steps) and lower-third chips; v1 has neither.
4. **No presentation layer:** no intro or outro card, no edition chip, no lower-third control names, no burned-in
   captions or sidecar SRT, no chapter markers, no "case file" summary at the end.
5. **Audio is the raw voice.** One pace for the whole film, ids and figures read as the lexicon allows (digit by digit
   for ids, written-out figures), no pause design between beats, no mastering beyond the shared warm EQ, nothing under
   the title.
6. **Accuracy defects found since:** the *Fictitious Vendor* film named unmasked ids over a masked graph (training plan
   §6.4); cases whose ids are masked for the analyst were narrated as if readable. Counts and Dataset sizes must not be
   a selling point (operator rule, 2026-11-15); a few lines still lean on volume.
7. **State and order are not controlled:** each film assumes a fresh Space; the training series learned that recorded
   steps persist in the demo Investigations (the hide, keep and note steps of F3, the held expand consumed by F5), so a
   case film must record from a fresh unzip or reseed first.

## 3. Content standard — a case film, not a tour

**Arc (seven beats, 4–5 minutes, 9–11 scenes).** Every beat has one job; the case decides which scenes carry it.

| # | Beat | Job | Rough length |
|---|------|-----|--------------|
| 1 | **Cold open** | The stakes in one concrete moment, about twenty seconds: a person who has to decide, what goes wrong if they miss it. Intro card follows, not precedes. | 0:20 |
| 2 | **Why the usual checks miss it** | One sentence per check that says "clean": each record passes on its own; only the links show it. | 0:30 |
| 3 | **The data, in one breath** | What lands, what the columns are, how two ends become a link. One scene, not three; the mapping is shown as one zoom on the real Mappings area. | 0:30 |
| 4 | **The question, then the find** | The saved view, the first sweep, the **decoy** the sweep points at first and why it is set aside, then the reveal on the real node. | 1:30 |
| 5 | **Evidence you can defend** | Rows under the graph, the step log or the note: every line a record; what would change our mind. | 0:40 |
| 6 | **What the team does next** | The lead, named as a lead, with three concrete next actions (review a login, hold an agent, start an Alert Rule or Entity List), one shown on screen. | 0:40 |
| 7 | **Close** | The case-file card (lead, evidence, next step), the "Try it", the link to the training episode that teaches the control just used. | 0:20 |

**Content rules (bind every spec).**
1. **Stakes in role terms.** Who is the investigator, what do they own, what happens if they are late. No invented
   losses, no real names, no claim that is not in the Space's ground truth (`tools/gen-la-*-data.ground-truth.json`).
2. **Every film has a decoy and a "what would change our mind".** The decoy is a real entity the first sweep surfaces
   (busiest, loudest, largest) that the evidence clears; the second is one sentence on what fact would overturn the
   lead. Cases with no natural decoy use the planted near-miss of their generator.
3. **A lead, shown as action.** The outcome is a lead, never a verdict (operator, 2026-11-15); it is not repeated as a
   disclaimer. The film ends on what the team does with it.
4. **One Investigation tail, shared.** The Standard material (Investigation, step log, team, Dossier, Entity List) is
   one 60–75 second beat in the film that fits the case (the step log where evidence is the point, the Entity List
   where the next investigation starts from it). The full Standard story is §4.4, a shared 90-second module, linked
   from each film's end card, not rebuilt twenty times.
5. **Say what the screen shows.** Where the analyst sees masked ids, the voice says so and uses roles and counts
   ("the hub", "the twenty-fifth wallet"); where ids are unmasked it may read them. Check each case on the live
   Standard build as Demo Analyst before the spec is signed.
6. **Plain words and GLOSSARY terms** (Entity, Link, Dataset, Working Set, Investigation, Entity List, Alert Rule,
   Collector, Pipeline). The retired words stay retired: the ban list of `docs/GLOSSARY.md` §2–§6 binds the
   voice lines, the chips and the captions, and the vocabulary guard runs on the specs.
7. **No record counts or Dataset sizes as a selling point;** the "answers in moments on millions of records" claim is
   said once, in the shared closing words, as in v1.
8. **Industry vocabulary is defined once, in the cold open or beat 2** (SIM swap, MSISDN, KYC, three-way match,
   referral, chain of custody…), in one short sentence, never a glossary dump.

## 4. Voice, audio and presentation standard

### 4.1 Voice (narration)

- **Persona:** a calm senior analyst talking a colleague through a case, second person ("you"), present tense, no
  slogans. The training series' teacher voice explains controls; the case voice tells a story.
- **Engine:** unchanged, Piper `en_US-hfc_female` (D-TR1), so the twenty films and the 41 episodes sound like one
  product. The improvements below are in the text, the pacing and the mastering; a different voice is decision D-CV1
  (§9), not assumed.
- **Sentence craft:** average 12–16 words with variety, none over 26 (the first drafts of the specs average 9–10: the P1 review merges runs of short sentences before sign-off, because a voice that never lengthens a sentence reads choppy); one idea per sentence; a short sentence after every long one; no
  stacked "and … and …" chains; figures said once and rounded in speech ("just under five thousand"); the number is
  on screen. Questions are used sparingly (at most two per film) and always answered in the next sentence.
- **Pacing profile per beat** (Piper length scale and the silence after a sentence), set in the spec per scene and
  applied by `tools/la-video-voice.mjs`: cold open slower (length scale 1.04, 0.45 s between sentences); data and
  evidence steady (0.98, 0.30 s); the reveal beat slower on the last sentence (a 0.7 s hold before it); the close
  brisk (0.96). Defaults stay take D of the 2026-11-16 voice trial.
- **Pauses are authored:** a `[pause 0.6]` marker in the Voice line becomes silence; the scene's first sentence starts
  0.35 s after the cut; the last ends 0.5 s before the next cut. No dead air over 2 s on screen.
- **Pronunciation lexicon** (extends `speakable()` in `tools/la-video-voice.mjs`, one tested table): ids by their
  family (HUB-01 "hub oh one"; TX-007 "tee ex oh oh seven"; MSISDN "em ess eye ess dee en"; IMSI, SIM, KYC, AP, IBAN as
  letters; DEV-… as "device …"), amounts ("4 750" is "four thousand seven hundred and fifty", "5 000" is "five
  thousand", a range "four seven fifty to five thousand"), times ("36 hours" is "thirty-six hours"), percentages, and a
  per-industry exceptions list in the spec header. A lexicon test lists every id in the twenty specs and its
  spoken form.
- **No filler:** no "now", "so", "as you can see", "let's" openers beyond one per film; no "simply", "just click".

### 4.2 Audio (mix and master)

- **Master:** mono voice, 44.1 kHz → stereo AAC 192 kbps; integrated loudness **-16 LUFS** (the web and phone norm;
  the training series ships at -18 LUFS, so the case films are slightly louder because they are shown without a
  teacher's context), true peak -1.5 dB, a de-esser band at 6–8 kHz, the warm EQ of D-TR1 kept, a gentle high-pass at
  80 Hz.
- **Ambient bed:** one quiet original ambient track per industry family (five: telecom, finance, public and law
  enforcement, health and insurance, enterprise and logistics and utilities), generated for the series (no licensed or
  copyrighted music), **-30 LUFS under the intro and outro and the case-file card only**, ducked 12 dB the instant
  narration starts; never under the investigation scenes.
- **UI sound:** an optional very soft tick (-34 LUFS) on each computed impact mark so the eye finds the change; a
  render switch `--no-ui-sound`; off in the files delivered for accessibility reviews.
- **Captions:** burned in (white, semi-transparent bar, 40 characters a line, two lines at most) plus a sidecar `.srt`
  from the same text; English only (D-TR5).
- **Checks:** a script measures LUFS and true peak per film and fails outside ±1 LU; a second pass flags any silence
  over 2.5 s that is not a card; one listening pass by a person per film (§7).

### 4.3 Presentation (the picture)

- **Format:** MP4 1920 × 1080, H.264 + AAC, chapter markers per beat, sidecar SRT; one file per case named
  `<Industry>_<Case>_v2.mp4`, one folder per industry, zipped like v1. No hosting.
- **Intro card (4 s, after the cold open):** dark canvas, the industry's accent colour bar, "Inspecto LA · Case file",
  the case title, the Space name, the *Standard* edition chip. Industry accents: Telecom teal, Banking blue, Insurance
  violet, Utilities amber, Law enforcement slate, Public sector indigo, Healthcare green, Logistics orange, Enterprise
  grey.
- **Lower-third chips** on every control the film uses, with its on-screen name and key (`Busiest nodes`,
  `Expand · G`), 2.5 s, from the training series' chip renderer.
- **Computed impact marks** (the `impact` step: screenshot before and after, `bbox` of the change) on the node or panel
  the voice names; before/after hold for any change smaller than a node. Hand-placed Note boxes stay only where the
  graph is a canvas and the change cannot be computed (rare, listed in the spec).
- **Story card** (new, first ten seconds after the intro): a small corner card, "Who is who", that names the roles of
  the two or three entities the film is about (*the hub wallet*, *a relay*, *the agent*), kept on screen until the
  reveal and replaced by the marks. It carries the story when ids are masked.
- **Decoy marker:** the decoy entity is marked in grey with "ruled out" when the evidence clears it, the lead in
  yellow, so the eye learns the difference.
- **Case-file card (end, 8 s):** *Lead* (one line), *Evidence* (the rows or the log step, by name), *Next step* (three
  bullets), *Try it*. Same layout in all twenty.
- **Motion:** cursor eased with a 0.25 s approach, zoom eased in and out over 0.6 s, crossfade 0.3 s between scenes,
  no whip pans; the on-screen action never starts before the voice names its intent.

### 4.4 The shared Standard module (written once)

*"Make it defensible" (90 s, Standard):* the same four moves on whichever case the viewer just saw: the Investigation
and its Working Set; the step log with who and why; the team (four-eyes, reviewer); the Dossier verified. Recorded
once on the case that carries it best (Telecom SIM swap), linked from the end card of the other nineteen. Each
film keeps one 60–75 s tail beat only where its story needs it (§3 rule 4).

### 4.5 Recording environment (operator, 2026-11-20): Standard, the fake sign-in, the bundled examples

- **Build:** the Standard edition as shipped in `la-standard.zip` (the Preview demo build), loopback only, started from a
  **fresh unzip per lane** (the installs are relocatable and boot in 15–21 s), each lane in its own folder and on its own
  port. No Basic install is used for the case films; the Basic start of a graph is the training series' episode B1.
- **Sign-in:** the **Demo Auth** sign-in, the fake one: local, no password, five Demo Users (Admin, Analyst, Manager,
  Reviewer, Outsider), a sign-in sends only a `demo:<id>` code and never a credential, and it works on loopback only
  (`tools/check-demo-auth-isolation.mjs` keeps it out of release builds). The recorder's `signin` verb picks the user on
  the sign-in page; a user is left through the User menu, from an open Investigation page. No real identity provider, no
  real credential and no secret appears in a spec, a log or a film.
- **Examples:** the bundled industry showcase Spaces with their synthetic data, saved views and seeded Investigations.
  Space per film: Mobile Money (rows 1, 2), Telecom (3, 5), Other Examples (4, 6, 11), Banking and finance (7–10),
  Insurance (12), Utilities (13), Law enforcement (14), Public sector (15, 16), Healthcare (17), Logistics (18),
  Enterprise (19, 20); the Space ids are in the §6 table.
- **Who signs in:** Demo Analyst for every film. Demo Admin only for the reveal and identity beats (Telecom_Structuring,
  the tail of Telecom_DeviceFarm, Banking_MuleRing, Enterprise_FictitiousVendor), where the Space lets only the
  administrator reveal. Demo Manager only in the shared module of §4.4, where it approves nothing. Reviewer and
  Outsider are not used in the case films (training episodes F5 and H3 cover them).
- **What the viewer sees of the sign-in:** the first frame of a film is after sign-in (a cut); the sign-in screen
  appears only in the shared module and, as a one-second cut, where a film changes user.
- **State rules** (the training series' lesson: recorded steps persist): the films use the saved views and the seeded
  Investigations read-only; a film that must change state (an Entity List, an Alert Rule, a Dossier) does it in its last
  scene, on a fresh unzip, and the next film does not reuse that unzip; one lane per Space at a time; a film that is
  re-recorded starts from a fresh unzip again.

## 5. The case spec (what every one of the twenty files holds)

Specs use the training-guide format, so the renderer and the `episode` recorder read them unchanged
(`tools/render-la-demo-video.mjs <id> --script <file> --episode tools/la-demo/<id>.episode.json`). One file per
industry group under `docs/okf/product/module/la-core/` (spec files, one `## <id>` per case, ids `<Industry>_<Case>`
as in v1).

```
## <Industry>_<Case>
> **<Case title>** · Standard · <Space> · <story> · <target length>
- Cast:        the two or three entities the story turns on (id or role, masked for the analyst or not), and the decoy
- Stakes:      who the investigator is and what is at risk (role terms, no invented losses)
- Vocabulary:  industry terms defined once, with the sentence that defines them
- Ground truth: the planted figures and ids, with the generator file and key they come from
- Before:      Space, saved view, signed-in user, fresh-state requirement
- Lead:        the one-line lead; What would change our mind; three next actions
- Voice style: pace overrides and lexicon exceptions for this film
- Try it / Check: one exercise on the shipped zip, one question and its answer

### S01 · <scene title>                                           ~seconds
- Voice:   narration, with [pause 0.6] markers where wanted
- Chip:    control name · key (lower third)
- Action:  what the cursor does on the real screen, with [check in UI: …] for anything to confirm
- Impact:  what changes on screen and what is marked (computed impact, or a listed hand mark)
```

Scene list is per case (9–11 scenes) and follows the arc of §3; no case is forced into sixteen.

## 6. The twenty cases

Each row is one spec file section; the lane owner writes it (§8). *Angle* is the v2 change in content, not a new case.

| # | Id | Industry · Space (view · start entity) | Angle as specced (decoy first; what the sweep gets wrong) |
|---|----|----------------------------------------|-----------------------------------------------|
| 1 | Telecom_Structuring | la-mobile-money · `mm_structuring_band` · HUB-01 | the report is green; the decoy is the utility shop (MER-ELECTRIC), not the relay; one admin-reveal beat |
| 2 | Telecom_DeviceFarm | la-mobile-money · `mm_device_farm` · FARM-1 | nine wallets, nine ids, one handset; decoys are the household of three and the family phone of four |
| 3 | Telecom_SimSwap | la-telecom · `mfs_sim_swap_bursts` · S-41 | the busy store S-72 (an upgrade drive) vs the quiet call-centre login S-41; also carries the shared module of §4.4 |
| 4 | Telecom_WangiriNetwork | la-showcase · `telecom_fraud_network` · 99979100001 | one chain behind four reports; the taxi line is the decoy; figures corrected to the data (400 numbers rung, 40 called back; 650 distinct callers on the taxi line) |
| 5 | Telecom_DealerCommission | la-telecom · `dealer_commission_fraud` · DL-112 | the decoy is the other record-month dealer, DL-163 |
| 6 | Banking_TillStructuring | la-showcase · `mule_structuring` · TILL-06 | the exit, not the entry; decoys are the first hub, the benefits payer and the other tills; ids masked for the analyst (roles, not ids) |
| 7 | Banking_SanctionedOwner | la-banking · `sanctioned_owner_path` · PER-03904 | the namesake SL-0289 scores 0.987, above the real match at 0.986, yet holds no stake in any customer's chain (reuses the F4 story) |
| 8 | Banking_CardCompromise | la-banking · `common_point_of_purchase` · M-317 | the busy supermarket M-052 vs the merchant the stolen cards share; ten cards that paid at M-317 were not defrauded, so the lead carries a caveat |
| 9 | Banking_CircularTrade | la-banking · `circular_trade` · CO-00941 | the leasing-pool boxes (PWCU) are the decoy loop; the loop that returns to its origin is the find |
| 10 | Banking_CryptoCashOut | la-banking · `converging_cash_out` · CL-01125 | decoy cluster CL-01126 (60 accounts, 54 banks) vs CL-01125 (40 accounts, 3 banks) |
| 11 | Banking_MuleRing | la-showcase · hand-built mule ring (v1 `Banking_MuleRing_Demo`) | decoys are the tangled core and busy ordinary accounts; Standard, with a Demo Admin reveal; ids masked for the analyst |
| 12 | Insurance_ClaimantRing | la-insurance · `claimant_ring_shared_identity` · CL-0134 | one repeat passenger plus a shared garage, clinic and lawyer; decoys are the family of four and the busy clinic CLI-01; claimant ids masked |
| 13 | Utilities_MeterTampering | la-utilities · `meter_tampering_contractor` · C-14 | the busiest contractor C-27 is set aside; C-14's visits come before the drops; PRM-0737 (visited, unaffected) is the near-miss (reuses F3) |
| 14 | LawEnforcement_BurglarySeries | la-law-enforcement · `burglary_series` · P-00476 | the seller P-00476 and the car ZX 9117 X; the decoy is the taxi ZX 3465 D; P-00476 sells at DLR-04 only (v1 said both dealers) |
| 15 | PublicSector_BidRotation | la-public-sector · `bid_rotation` · P-00001 | four firms alone on 24 tenders win in turn, two share a director; the decoy is the three firms that win most of Highways South against real rivals |
| 16 | PublicSector_AddressFarm | la-public-sector · `address_farm` · AD-03141 | 14 single-person claims, 5 surnames, 3 accounts at one address; the decoy is the three care homes |
| 17 | Healthcare_ReferralKickbacks | la-healthcare · `referral_kickbacks` · PR-1170 | the imaging centre PR-1170 whose owner shares a home with a referring practice's owner; the decoy is the only local hospital |
| 18 | Logistics_InsideJob | la-logistics-industry · `driver_team_inside_job` · DRV-0892 | 12 short loads, one driver pair, one night-shift scanner; the decoy is the driver on the busiest route |
| 19 | Enterprise_FictitiousVendor | la-enterprise · `ap_vendor_identity` · V-117 | the lead is masked, so roles until Demo Admin reveals V-117, BA-9031 and E-31 (fixes the v1 masked-id defect); the decoy is the utilities vendor with the most payments |
| 20 | Enterprise_AccessCreep | la-enterprise · `access_creep` · EMP-0007 | EMP-0007 granted payroll and treasury to four reports, one of whom changed VEN-0264's bank details; the decoy is the payroll officer EMP-0062, whose access came with a role change (the "no ticket" claim is v1 text: confirm in the Rows tab; the view takes 10-25 s to draw) |

The angles are the result of the four spec lanes (2026-11-19), checked against each Space's ground truth and one live screen; where the data corrected the first hypothesis of this plan (rows 1, 5, 6, 7, 9, 11, 12, 14, 15, 17, 18, 19, 20) the table now holds the corrected angle. The specs and their sources:

- [`spec-case-videos-telecom.md`](../okf/product/module/la-core/spec-case-videos-telecom.md) — rows 1–5, ten scenes each.
- [`spec-case-videos-banking.md`](../okf/product/module/la-core/spec-case-videos-banking.md) — rows 6–11, 69 scenes.
- [`spec-case-videos-insurance-utilities-law.md`](../okf/product/module/la-core/spec-case-videos-insurance-utilities-law.md) — rows 12–14, the shared "Make it defensible" module (§4.4) and the lexicon seed of §4.1.
- [`spec-case-videos-public-health-logistics-enterprise.md`](../okf/product/module/la-core/spec-case-videos-public-health-logistics-enterprise.md) — rows 15–20, ten scenes each.

## 7. Gates (spec, then render)

**Spec gates (per case, before sign-off):** (1) *Ground truth:* every id and figure cited from the generator's ground-truth
file, key named; (2) *Screen truth:* the start entity, the saved view and the decoy located on the live Standard build as
Demo Analyst, masked or readable recorded in the spec; (3) *Arc:* beats 1–7 all present, a decoy, a "what would change
our mind", three next actions; (4) *Words:* the sentence-length and filler rules of §4.1, the GLOSSARY terms, no record
count as a selling point; (5) *Lexicon:* every id and figure in the Voice lines spoken correctly by the lexicon test;
(6) *Chips:* every control named exactly as on screen.

**Render gates (per film):** frame QA by contact sheet at 1 frame per 12 s and by reading the cut at each scene change;
LUFS ±1 LU of -16 and true peak under -1.5 dB; no silence over 2.5 s outside cards; captions and SRT identical to the
Voice lines; chapter markers present; one listening pass by a person; vocabulary guard
(`node tools/check-vocabulary.mjs`) and the docs guards clean.

## 8. Phases and order of work

| Phase | Work | Output |
|-------|------|--------|
| P0 | This plan and the standard (§3–§5); the open decisions (§9) | this file |
| P1 | **Twenty specs**, four lanes in parallel, each written against the Space's ground truth and a live screen check | five spec files, one per industry group, in the la-core hub (Telecom 5, Banking 6, Insurance + Utilities + Law enforcement 3, Public sector + Healthcare + Logistics + Enterprise 6) |
| P2 (done 2026-11-22) | Voice and audio upgrades in `tools/la-video-voice.mjs` and the renderer: lexicon table and test, pace profile and `[pause]` markers, mastering to -16 LUFS, ambient bed and ducking, UI tick, `--no-ui-sound` | tested tool changes; three trial takes of one case for the operator (D-CV1, D-CV2) |
| P3 (done 2026-11-22) | Renderer presentation: intro and case-file cards, story card, decoy marker, chips, captions and SRT, chapters, industry accents (shares P3 of the training plan §6.1) | `render-la-demo-video.mjs` flags; one pilot film |
| P4 | Episode JSON per case (`tools/la-demo/<id>.episode.json`) written from each spec, probed on the live Space; a fresh `la-standard.zip` unzip per lane with the Demo Auth sign-in (§4.5), reseed after any state change; recording order noted | twenty episode specs |
| P5 | Render, QA, package | twenty films, zips per industry, status table in this plan |
| P6 | Mark v1 superseded: its generated script gets a banner, `tools/la-demo-cases.mjs` kept as the data for the shared closing words and the figures until retired | clean docs |

Sequencing: P0 and P1 now; P2 and P3 can start as soon as D-CV1…D-CV3 are answered and run beside P1; the pilot
(Telecom_SimSwap, which also carries the shared module of §4.4) is rendered first and reviewed by the operator before P4
fans out. Lanes in P4 and P5 follow the training series' lane flow: probe, spec, render, contact-sheet, commit; one lane
per push; never record two lanes on the same Space at once (the training series' state-pollution lesson).

## 9. Decisions for the operator (all answered 2026-11-20: D-CV1…D-CV6 "all recommended", D-CV7 the environment)

- **D-CV1 voice (decided 2026-11-20, the recommendation):** keep Piper `hfc_female` for continuity with the training series *(recommended)*, or choose a second
  voice for case films (a trial of two candidates costs one lane).
- **D-CV2 loudness and music (decided 2026-11-20, the recommendation):** -16 LUFS with the ambient bed under cards only *(recommended)*, or no music, or -18 LUFS
  like the training series.
- **D-CV3 length (decided 2026-11-20, the recommendation):** 4–5 minutes per film with a shared 90-second Standard module *(recommended)*, or keep the v1 length
  and tail in every film.
- **D-CV4 supersede (decided 2026-11-20, the recommendation):** v1 files stay in the folder as `_v1` until v2 is signed off *(recommended)*, or are deleted when
  each v2 renders.
- **D-CV5 captions and SRT (decided 2026-11-20, the recommendation):** burned in plus sidecar *(recommended)*, or sidecar only.
- **D-CV6 UI tick sound (decided 2026-11-20, the recommendation):** included, switchable *(recommended)*, or none.
- **D-CV7 environment (answered by the operator, 2026-11-20):** record on Standard with the fake Demo Auth sign-in and the bundled example Spaces (§4.5).

## 10. Status log

- **2026-11-19:** plan written; the standard and the case table fixed; P1 (twenty specs) started in four lanes.
- **2026-11-19 (later):** P1 first drafts of all twenty specs written (four files, §6), the table corrected to the data. Open before sign-off: a review pass for sentence rhythm (average 9–10 words, target 12–16), the `[check in UI]` marks (Insurance start-from step, whether the Law enforcement taxi is drawn, v1 link counts, the Access creep "no ticket" claim), `tools/check-vocabulary.mjs` on the new files once tracked, and decisions D-CV1…D-CV6.
- **2026-11-20:** the operator confirmed the recording environment: Standard, fake Demo Auth sign-in, the bundled examples (new §4.5, D-CV7). Spec headers point to it.
- **2026-11-20 (later):** the operator answered "all recommended" for D-CV1…D-CV6 and said start: three lanes began, VOICE (P2: lexicon and its test, pace profile and `[pause]`, -16 LUFS mastering, ambient bed with ducking, UI tick), RENDER (P3: story card, decoy marker, case-file card, intro card, SRT and chapters for case films), PILOT (Telecom_SimSwap on a fresh Standard unzip with Demo Auth: episode JSON and probes). Review of the spec drafts for sentence rhythm and the `[check in UI]` marks runs beside them.
- **2026-11-22 — P2, P3 and the pilot done.** P2 (voice and audio) is on master: `tools/la-video-voice.mjs` (`speakableV2` lexicon, per-scene pace, `[pause N]`, `sayV2`) and `tools/la-video-audio.mjs` (-16 LUFS master, ambient bed with ducking, soft tick, loudness and silence gate), 16 tests including every id and figure form of the spec lexicon; three voice trial takes in `C:\sandbox\la-video-out\_voice-trial\`. P3 (presentation) is on master (`a45ba5a21`): `tools/la-video-present.mjs` with the spec lines `Intro`, `Story`, `Casefile`, the grey decoy mark, burned-in captions, `.srt` and chapters behind `--case-film`. The audio stage is wired into the renderer (`055985930`): `--case-film` narrates through `sayV2`, masters the film, lays the bed under the cards, ticks the marks (`--no-ui-sound` turns them off) and fails the render if the gate fails. **Pilot `Telecom_SimSwap`** is re-specified and re-recorded on a fresh Standard unzip with the fake Demo Auth sign-in: `C:\sandbox\la-video-out\_cv2\Telecom_SimSwap.mp4` (4:34, ten chapters, sidecar `.srt`; gate -16 LUFS, true peak -4.5 dB, no silence over 2.5 s; checked from contact sheets and a few frames, not played); the held expand, the Dossier build and the agents A-088 and A-093 are narrated rather than shown. **Blockers for the other nineteen (P4):** each film needs its `.episode.json` probed on a fresh Standard unzip; recording latency overruns the narration by 5–25 s on a first render, so waits are trimmed per scene from the log's "ran … over" lines; the spec drafts still need the sentence-rhythm review and their `[check in UI]` marks; one lane per Space at a time; stop the lane's Standard server when its film is done.
