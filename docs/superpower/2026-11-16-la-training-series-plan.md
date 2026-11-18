# Inspecto LA user training series — plan

> **DECIDED 2026-11-16 — D-TR1…D-TR6 answered by the operator (§9); D-TR7 (episode list) taken as approved with the
> Advanced category added; the voice is a local neural engine (D-TR1)** (board row `LA-TRAINING-SERIES-1`). Operator request: *"a Inspecto LA user
> training video series, each video 5 to 7 minutes, that handholds new users through the product features and
> functionalities; describe each tool in the toolbars, tabs and configuration tabs including settings, and demonstrate how
> to use Inspecto LA; group the videos by category; the user guide helps but may not be complete; the intention of each
> tool is said in the voice, and the graph changes caused by each action are shown."*
>
> This plan was built from: the user guide [`okf/product/module/la-core/user-guide.md`](../okf/product/module/la-core/user-guide.md)
> (§1–§17), a control-by-control inventory of the la-app and link-analysis sources taken 2026-11-16 (summarised in §5), and
> the production pipeline that made the 20 case videos the same week (`tools/record-la-live.mjs`,
> `tools/render-la-demo-video.mjs`, scripts in `okf/product/module/la-core/brochure-video-demo-*.md`).

## 1. Goal, audience, done

- **Audience:** a new analyst who has never seen Inspecto LA (first ladder of episodes), then the team lead and the Space
  administrator (later categories). No graph-theory background assumed; every algorithm is introduced by the question it
  answers, not its name.
- **Goal:** after the series a new user can, unaided, start from data, build and read a graph, use every toolbox tool on
  purpose, record an Investigation a team can defend, hand over a Dossier, and configure a Space.
- **Done means:** (a) every user-facing control in the §5 inventory appears in at least one episode, said by its on-screen
  name, with its intent spoken and its effect shown (the coverage matrix in §5 is the checklist); (b) every episode is 5–7
  minutes, recorded live on the shipped product, with captions; (c) every episode passed script review and frame QA (§8).

## 2. Teaching rules (bind every episode)

1. **Intent → action → impact, every time.** For each control: the voice says *why you would use it* (one sentence), the
   cursor does it on the real screen, and the screen shows *what changed* — a short zoom on the changed region and, where the
   change is subtle, a before/after hold (freeze the "before" for ~1 s, play the action, hold the "after" with a yellow mark
   on what changed). Never just "click X".
2. **Say the control's exact on-screen name** (the inventory's label column) and, the first time, where it lives ("the brush
   icon in the top toolbar, Display options"). A lower-third chip shows the name and its shortcut key (`Expand · G`).
3. **One Space, one story per episode.** Each episode runs in one shipped showcase Space on one planted story, so the graph
   changes mean something (the planted funnel appears, the decoy is set aside). The Space is named in the intro card.
4. **Edition is always visible.** An edition chip in the corner (*Basic* / *Standard*); a Standard-only control is introduced
   as such. Basic episodes record on the Basic build, Standard episodes on the Standard (Preview demo) build.
5. **No record counts or Dataset sizes as a selling point** (the data is small and synthetic); say what scale changes (On
   screen vs Whole Dataset, caps, the link index). The claim "search and filter stay fast on millions of records" is kept
   (operator, D-TR6) and said in the same words as the case videos; no other performance figure is claimed.
6. **Plain words, GLOSSARY terms** (Entity, Link, Dataset, Working Set, Investigation, Entity List, Alert Rule, Measure,
   Collector, Pipeline). The ten *About* terms (§5.7) are introduced in A1 and reused verbatim; the rest of the guide's
   vocabulary (user guide §17) is defined the first time it is said: Space and Industry pack (A2), Entity Type (A4),
   Snapshot (E1), Entity List (E2), Step log (F1), Identity resolution (F7), Measure, Alert Rule, Incident and Case (F9).
7. **Every episode ends with "Try it":** one exercise on the shipped zips (`la-basic.zip` / `la-standard.zip`), and one
   check question with its answer in the description.
8. **Honest about limits:** where a tool has a cap or a locked state, show the lock and read its reason (the product's own
   disabled-reason text), once.

## 3. Categories and episodes

41 episodes in 9 categories (~4 h; category I added for Advanced, D-TR3; H3 added 2026-11-16 after the review against
the user guide, which found §5.3, §12.1, §14 and §16 untaught). The user guide's §18 (users of the old Studio) is left
out on purpose: the series is for new users. Waves (§9) order the work; the episode numbers are the viewing order.

| # | Episode | Ed. | Space · story | Covers (inventory §) |
|---|---|---|---|---|
| **A** | **Getting started** | | | |
| A1 | What Inspecto LA is, and a tour of the screen | B | Other Examples · mule ring | header, rail, About (10 terms), *What can I do next?*, toolbox tabs, dock, Settings gear, Full screen, Show/Hide toolbox, navigation collapse/resize (§5.1, §5.2a) |
| A2 | Spaces, the home page and saved views | B | Telecom | Space switcher, My work, saved views search/paging, *What changed in your data*, Getting started (sample), saved-view chip, details and version history (§5.1) |
| A3 | Your data: Datasets, columns and Mappings | B | Mobile Money | Data page, Find a Dataset, Rename or describe, Columns roles (Entity, Time, Measure, Category, Detail), Unit, Keep on each link, Mappings (New mapping), Discover (§5.5) |
| A4 | Entities and Entity Types | B | Banking | Entities page, Entity Types and Columns tabs, New Entity Type, Open an Entity, Settings ▸ Entity Types (§5.4, §5.5) |
| **B** | **Building a graph** | | | |
| B1 | Start a graph: one entity, a list, pasted values, the whole Dataset | B | Telecom · SIM swap | Start a graph (all *Start from* choices, Dataset, Entity Type, value box and suggestions, Degrees), Draw (§5.2i) |
| B2 | Preset queries and Build a question | B | Banking · card compromise | Preset queries, Build a question (every field), Draw these, Save as preset (§5.2i) |
| B3 | Explore the Dataset and the Graph model | B | Utilities | Explore the Dataset (Show what is in it, Busiest, Draw these), File ▸ Explore Dataset, Examples ▸ Follow the money, Settings ▸ Graph model (Run, Refresh, Industry pack) (§5.2a, §5.4) |
| B4 | Find and filter: search, node types, time, timeline | B | Telecom · Wangiri | Search nodes, Filter node types, Filter links by time (time column, cutoff), Clear search and filters, timeline playback (slice, play, window, age fade), Limits and graph counts; the Rows filter in two stages (Apply locally, Push to server, AND/OR groups) and the "≥ 5 000 hides structuring" trap (§5.2a, §5.2g, §5.2h; user guide §5.3) |
| **C** | **Reading and shaping the canvas** | | | |
| C1 | Moving around and selecting | B | Insurance · claimant ring | Canvas modes Pan/Lasso/Fisheye/Link filter (L, F, Esc), Select all/Invert/Neighbours/Clear, selection count, Shift+click, arrows and +/−, hover card, node detail dialog (§5.2b, §5.2d, §5.6) |
| C2 | Layouts, legend, counts and minimap | B | Public sector · bid rotation | all 14 layouts with caps and the over-cap message, Fit to screen, Legend (node/link kinds, hide a kind, ringed/dotted meanings), Graph counts (customise), minimap (§5.2f, §5.2g) |
| C3 | Making the graph readable: Display options and Display rules | B | Healthcare | every Display options item (labels, overlays, community outlines/shading, link bundling, grouping single-link neighbours, size/spacing/width, colours/shapes/patterns), Undo/Redo display change, Settings ▸ Display rules (§5.2e, §5.4) |
| C4 | Expand and collapse, one degree at a time | B | Mobile Money · structuring | Expand (G), double-click, right-click menu (Expand, Expand this once: outgoing/incoming/2 degrees/only kind), Collapse/unfold (C), Held busy entities, Expand anyway (§5.2b, §5.2c) |
| C5 | Cleaning up: hide, remove, trim, exclude, lists | B | Logistics | Clean up the canvas (keep paths, highlight shortest paths, trim dangling leaves, keep only selected, remove), Hide, Remove (Del), Reduce the graph menu, Exclude dialog, Hidden by lists (switch off/on, show again) (§5.2b, §5.3) |
| **D** | **The analysis toolbox** | | | |
| D1 | Quick questions: busiest, tight groups, route A to B | B | Banking · till structuring | Quick questions, result actions (Show on graph, Start from these, Add to a list, Add to exclusion list), Results in the dock (§5.3) |
| D2 | How are they connected? | B | Banking · circular trade | Shortest path (Fewest hops vs Strongest ties), All paths, Find paths (whole Dataset), `Flow and backbone` (max flow, strongest backbone) (§5.3 table) |
| D3 | Who matters most? | B | Law enforcement · burglary series | Explain node (hops), Centrality: Degree, Betweenness, Closeness, Eigenvector, Katz, PageRank, Hub/Authority (HITS) — each one's question in one sentence, same graph re-ranked (§5.3) |
| D4 | Groups and weak points | B | Enterprise · access creep | Communities (Label spread vs Louvain), Connected components, Cut points (nodes, links), Cohesive groups (k-core, triangles, cliques), Cycles (§5.3) |
| D5 | What looks unusual? | B | Insurance | Similarity (find similar, predict links), Possible duplicates and merge, Suspicion score (+ optional risk model), Propagated risk (§5.3) |
| D6 | Pattern match | B | Banking · mule ring | pattern packs (all nine), custom steps (direction, link kind, Entity Type), branching stages, Conditions view, Use selection as pattern, Save as pattern pack, Run on server (§5.3) |
| D7 | Money and time: Value Measures, bursts and rhythms | B | Mobile Money | the seven Value Measures, windows and thresholds, Run Measure; Burst and periodicity (bursts, regular intervals), Highlight all on canvas (§5.3) |
| D8 | On screen vs Whole Dataset: limits and scale | B→S | Telecom | the two toolbox tabs, *Up to N nodes* / *Instant up to N* chips, over-cap hand-off, server catalogue rows, Results dock, the link index (Standard) (§5.3) |
| **E** | **Keeping and sharing your work** | | | |
| E1 | Saving, opening and exporting | B | Utilities | File menu (New, Open… dialog and its filters, Save view, Save view as, Save snapshot, Import or export, Close, Delete), Export PNG/SVG/JSON/GraphML, saved view vs snapshot (§5.2a) |
| E2 | Entity Lists and Identity Facts | B | Mobile Money · device farm | Entity Lists page (New list, members, ranges, expiry, retire), Exclude by list, Seed by list, Register as Dataset, Identity Facts (assert, retract, import, Chain verified), Settings ▸ Identities (§5.4, §5.5) |
| **F** | **Investigations (Standard)** | | | |
| F1 | Signing in, roles and your first Investigation | S | Telecom · SIM swap | sign-in and the five Demo Users, user menu, Investigation tab, start form (Name, Purpose, Time column, Degrees), unsaved card, Save, step bar (§5.1, §5.3) |
| F2 | Expand with intent: direction, kinds, time, strength, fan-out | S | Telecom · dealer commission | the Expand section and every fold (Direction incl. Reciprocal, Degrees, Link kinds, Time window, Minimum events/days, Busy limit, Row limit), preview line, Reset to defaults (§5.3) |
| F3 | Recorded clean-up and scope | S | Utilities · meter tampering | Keep (K), Hide (H), Exclude with reason (X), Clean up fold (leaves, few events, keep paths), outside a time window, Scope (include/leave out), Selected entity notes, Busy entities (§5.3) |
| F4 | The step log: replay, undo, re-order, more steps | S | Banking · sanctioned owner | Step log, Undo the latest step, Re-order steps (fork), More steps (Connect, Time window, Snapshot marker, Threshold, Compare), Replay and drift (§5.2h, §5.3) |
| F5 | Teams: members, four-eyes, reviewers and outsiders | S | Telecom · SIM swap | Members (grant/revoke, roles), held request, Approve/Deny as manager, reviewer read-only, outsider refused, Investigation access setting (§5.3, §5.5) |
| F6 | Drafts: work in parallel | S | Enterprise | Drafts fold: fork, work, rebase, promote; Draft banner (walk in UI first, §6.3) |
| F7 | Masked data and audited reveal; resolving identities | S | Mobile Money | masking in the node detail, Reveal with reason, the reveal log, identity resolution step (Add a resolve step), merge identities (§5.2d, §5.4) |
| F8 | Evidence: coverage, Dossier and the sealed bundle | S | Banking · card compromise | Coverage (Check coverage), data trust strip, Build Dossier (at step, snapshots), downloads (steps, method, HTML, manifest), Verify, tamper check on an edited manifest, Download Dossier (sealed), Verify a bundle (§5.2h, §5.3) |
| F9 | From finding to action: Alert Rules, Cases, widgets and sharing | S | Mobile Money | Watch this Measure (Alert Rule, severity), standing detection, Alerts page (evaluate, acknowledge, resolve, open Investigation), Attach to Case, Case history, Measures and templates, Pin to a Widget, Share and embed; a link from another system: the *Open in Inspecto LA* page (Open the Investigation, Open the Entity, Reveal the value) (§5.3, §5.5; user guide §12.1) |
| **G** | **Geo and entity pages (Standard)** | | | |
| G1 | The Geo map | S | (Space with coordinates, §6.3) | Geo query form, points/flows, Heatmap, Filter to view, Layer manager, Measure/Radius/Polygon/Note tools, saved views, Export the map (§5.5) |
| G2 | Geo intelligence and pivoting between map and graph | S | (same) | Co-location, Frequent locations, Stay points, View as graph, pivot graph ↔ map (§5.5, user guide §6.3) |
| G3 | Entity profiles | S | Banking | Open the Entity page, profile (first/last seen, activity, measures, links, history), Investigate from a profile (§5.5) |
| **H** | **Administering a Space** | | | |
| H1 | Settings for a Space and for you | S | Other Examples | Settings page: My browser, Investigation access, Link Analysis (masking, Entity Types, index, embed), Analysis limits, Add data, Access (roles), Host resources, Getting started counters; Settings dialog ▸ Installed modules; who can do what, by role and by edition; every limit is a setting, in three sections (Analysis, Host resources, My browser) (§5.5; user guide §14, §15) |
| H2 | From your own file to a first finding (capstone) | B | new Space | Add data / Onboard a file, Upload a CSV, columns and roles, a Mapping, first graph, first Quick question, first saved view (§5.5, user guide §2–§3) |
| H3 | When the product says no: locked controls, Truncated and refusals | B→S | Telecom | a greyed control and its tooltip reason, *Truncated* and what to do, Layered greyed on a wide graph, `masked:…` ids, *Waiting for approval*, *Investigation is not available*, a pattern that finds nothing on a cut graph, 413 / 429 messages, sign-in and *No Space is attached* messages; views are not evidence (user guide §15, §16) |

| **I** | **Advanced edition** (D-TR3) | | | |
| I1 | Access Policies: narrowing what roles allow | A | Banking | Settings ▸ Access, an attribute-based Access Policy that can only narrow a role (e.g. a reviewer refused one Investigation), the refusal as the user sees it; sample `config/examples/access-policy-deny-persona.md` (walk in UI first, §6.3) |
| I2 | Investigations shared by several servers (PostgreSQL) | A | — (admin explainer) | what changes when Investigations live on PostgreSQL: one store, several servers, the same steps and Dossier; how an administrator switches it on. Needs an Advanced (non-demo) build with a PostgreSQL store to record (§6.4) |
| I3 | Scale on Advanced: graph runs on the server, the link index, daily ingestion | A | Telecom | Whole Dataset runs as server jobs, the link index (Full / Append / Compact), what daily ingestion adds; the "millions of records" claim (D-TR6) |

**Category J, worked cases** is the existing set of 20 case videos (`C:\sandbox\la-video-out\<Industry>_<Case>.mp4`,
shared as `la-videos.zip`); the series links to them from each category's last episode rather than re-making them.

## 4. Episode specification (the template every script fills)

```
### <id> · <title>                     edition · Space · story · target length
Objectives:     3 things the viewer can do after it
Before:         what state the product must be in (zip, Space, signed-in user, prior steps)
Controls:       the inventory rows it covers (ids from §5)
Beats:          6–9 beats, each = Intent (voice) · Action (cursor) · Impact (what changes, mark/zoom) · ~seconds
Try it:         one exercise on the shipped zips
Check:          one question + answer
Gotchas:        locked states, caps, things that differ on a recorded canvas
```

### 4.1 Worked example — C4 · Expand and collapse, one degree at a time (Basic · Mobile Money · structuring · 6 min)

Objectives: grow a graph deliberately from one entity; read the legend's expanded/unexpanded marks; set busy entities aside.
Before: `la-basic`, Mobile Money Space, empty canvas.

| Beat | Intent (voice) | Action | Impact shown |
|---|---|---|---|
| 1 | "A graph is grown, not drawn whole: start from one thing you know." | Start a graph ▸ One entity ▸ `HUB-01` ▸ Draw | one node + its links appear; zoom on the dotted outlines |
| 2 | "Dotted means *not expanded yet*: there is more behind it." | hover legend line *Dotted outline* | legend line marked |
| 3 | "Expand adds what is linked to the selection, one degree." | select a relay, press **G** | before/after hold: new ring of nodes marked yellow |
| 4 | "Double-click does the same for one node." | double-click a STRUCT wallet | its neighbours fan out |
| 5 | "Right-click when you want only one direction or one kind of link." | right-click ▸ Expand, this once ▸ Incoming | only incoming links added; arrows highlighted |
| 6 | "Busy entities would flood the graph, so they are held." | expand towards the biller | ringed node; *Held (1)* badge marked |
| 7 | "Expand anyway when you really need it — on purpose." | Held tray ▸ Expand anyway | the flood appears; then Ctrl+Z undoes it |
| 8 | "Collapse folds a branch back without losing it." | select, press **C** | branch folds into the node; count badge |
Try it: from `AG-18`, expand twice outgoing only and find the agent the money ends at. Check: what does a ringed node mean? (busy, shown but not expanded)

### 4.2 Worked example — D3 · Who matters most? (Basic · Law enforcement · burglary series · 7 min)

Beats: Explain node on `P-00476` (*what it links to, in words*) → Centrality ▸ Degree (*who has most links*) → Betweenness
(*who sits on the paths between others: the go-between*) → Closeness (*who reaches everyone fastest*) → Eigenvector and
PageRank (*who is linked to important ones*) → Katz → Hub and Authority (*who points to the important ones, and who they
point to*). Impact: the same graph re-ranked each time, the top three ringed, the ranking table in the dock; the
voice points out where the car `ZX 9117 X` ranks differently under Degree and Betweenness and why that matters.

### 4.3 Worked example — F8 · Evidence: coverage, Dossier and the sealed bundle (Standard · Banking · card compromise · 7 min)

Beats: open *Compromised terminal* → Coverage ▸ Check coverage (*which days have no rows at all*) → data trust strip →
Investigation actions ▸ Build Dossier ▸ At step blank ▸ Build → Verify this Dossier → *Log intact* (zoom) → Download
manifest → Verify an uploaded manifest with one digit changed → *NOT verified*, the changed item named → Download Dossier
(sealed) → Verify a bundle on an edited copy → *Bundle NOT verified*. Impact: each verification result held 2 s with a mark.
Before: a pre-made tampered manifest and bundle in the recording folder (hand-editing on camera is error-prone).

## 5. Coverage: the control inventory, by region

Taken 2026-11-16 from `inspecto-ui/projects/la-app/src/app` and `inspecto-ui/projects/link-analysis/src/link-analysis`
(file:line references in the inventory notes kept with this plan's working files). Each region lists its controls and the
episode(s) that cover it; the script review (§8) ticks every one.

| § | Region | Controls (abridged) | Episodes |
|---|---|---|---|
| 5.1 | App shell, rail, home | home link, App switcher, Areas (Link Analysis, Data, Geo, Entities, Entity Lists, Alerts, Settings), *What can I do next?* (its six states), Space switcher, user menu (scheme, sign out), rail resize; Getting started (Try the sample / my own data / my team's data); My work, Saved views (search, per page, paging) | A1, A2, F1 |
| 5.2a | Header and top toolbar | Link Analysis title, About, saved-view chip, File menu (New, Open…, Explore Dataset…, Examples, Save view, Save view as, Save snapshot…, Build Dossier…, Members…, Import or export, Close, Delete…), Search nodes, Filter node types, Layout, Filter links by time, Clear search and filters, Display options, Undo/Redo display change, Fit to screen, Export (PNG, JSON, SVG, GraphML), Show/Hide the table, Full screen, Settings, Show/Hide the toolbox | A1, B3, B4, C2, C3, E1 |
| 5.2b | Canvas tools row | Pan, Lasso, Fisheye, Link filter, Select all, Invert, Select neighbours, Clear, count chip, Expand, Collapse/unfold, Hide, Remove, Keep, Exclude…, Merge identities…, Attach to Case, Clean up the canvas (5 tools + Find paths server), Held, Hidden by lists, More canvas tools, Reduce the graph | C1, C4, C5, F3, F7, F9 |
| 5.2c | Node right-click menu | recorded: Expand anyway (busy), Expand, Expand this once (Outgoing, Incoming, 2 degrees, Only <kind>), Shrink, Keep, Hide, Select neighbours, Pick neighbours…, Exclude…, Collapse/unfold, Expand filter…; plain: Investigate this, Exclude…, Remove, Collapse/unfold, Expand…; double-click rules | C4, F2, F3 |
| 5.2d | Node detail dialog | Masked id + Reveal…, Summary, Risk, propagated risk, Details, Enrichment, Entity Lists, case history, Actions (hide, add to list), Open the Entity page | C1, F7, G3 |
| 5.2e | Display options | Node labels, Link labels, All link labels, Arrows on large graphs, Align while dragging, Highlight on hover, Legend, Graph counts, Level of detail, Minimap, Grid, Community outlines, Community shading, Link bundling, Group single-link neighbours, Node size, Node spacing, Link width, Node colours/shape, Link colours/pattern/size | C3 |
| 5.2f | Layouts | Layered, Grid, Force, Clustering force, Radial, Degree ordered, Circular, Information density, Force (spread), Mind map, Organisation chart, Radial tree, Dendrogram, Fishbone; caps and over-cap message | C2 |
| 5.2g | Legend, counts, status, timeline | node/link kinds, hide a kind, ringed/dotted, Next/Only unexpanded, Graph counts + Customise counts + Limits, render status, Result cut short, Refresh, minimap, change note; timeline playback (slices, play, window, age fade, Seal this window) | B4, C2 |
| 5.2h | Bottom dock | Rows (filter, predicate: apply locally / push to server / advanced SQL), Results, Steps, Step log (undo, re-order → fork), Working Set table, Coverage, Annotations, Excluded entities; resize, collapse | D1, F4, F8 |
| 5.2i | Start area (Basic) | Start a graph (One entity, Entity List, Pasted values, Whole Dataset, Graph on screen; Show values), Preset queries, Build a question (+ Save as preset), Explore the Dataset | B1, B2, B3 |
| 5.3 | Toolbox | tabs On screen / Whole Dataset / Investigation; chips (Suggested, Also on…, Up to N, Instant up to N); Quick questions; Shortest path, All paths, Find paths, Explain node, Centrality (8), Communities (2 methods), Connected components, Cycles, Cut points, Cohesive groups (3), Similarity and prediction (+ Possible duplicates), `Flow and backbone`, Suspicion score, Pattern match (9 packs, steps, branching, conditions), Value Measures (7) + Watch this Measure, Burst and periodicity, Link index (Full/Append/Compact), server rows (Neighbourhood, Ego network, Descendants, Free of cycles?, Propagated risk); Investigation tab: actions menu, start form, unsaved card, Expand section and folds, Clean up fold, Selected entity, Busy entities, Scope, More steps, Investigation settings folds (General, Case, Members, Share and embed, Drafts, Replay and drift, Pin to a Widget, Oversight and approvals, Measures and templates, Dossier, References), Dossier panel, Members panel, Exclude dialog, Hidden by lists panel | D1–D8, F1–F9 |
| 5.4 | Settings dialog (gear) | Graph model, Entity Types, Display rules, Entity Lists, Identities, Add data (Upload a CSV), Investigation settings, Installed modules | A4, B3, C3, E2, F7, H1, H2 |
| 5.5 | Rail pages | Data (Columns roles, Mappings, Discover), Add data / Onboard a file, Geo (toolbar, tools, layers, query form, Geo intelligence), Entities (types, columns, profiles), Entity Lists + Identity Facts, Alerts (evaluate, acknowledge, resolve, open Investigation), Settings page (8 drawers) | A3, A4, E2, F9, G1–G3, H1, H2 |
| 5.6 | Keyboard | G/E expand, K keep, H hide, X exclude, Del remove, C collapse, L lasso, F fisheye, Esc, Ctrl+A, arrows, +/−, Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y (recorded vs display undo), Shift+click | C1, C4, F3 (and a one-page shortcut card in A1's description) |
| 5.7 | About terms | Entity, Link, Dataset, Graph model, Expand, Saved view, Investigation, Working Set, Draft, Dossier | A1 (then reused) |

## 6. Production method

### 6.1 Pipeline (reuse, extend)

The case videos proved the method: narration first (its length paces the recording), a scripted live recording of the
real product in headless Chrome with a visible cursor, then a cut per scene with zoom and yellow marks only.
Extensions this series needs, in order of value:

1. **Declarative episode scripts.** Today a scenario is code in `tools/record-la-live.mjs`. Episodes need a step list in
   the script file (`click "Busiest nodes"`, `key G`, `select HUB-01`, `hover legend "Dotted outline"`, `wait graph`), so a
   writer can author an episode without touching the recorder. One small interpreter over the existing helpers.
2. **Impact marks computed, not hand-placed** (built 2026-11-17). The graph is a canvas, so node positions are not in the page; an `impact` step in an episode takes a screenshot before and after its action, `ffmpeg` subtracts them and the `bbox` filter finds the box that changed. The renderer draws that box and its label from the moment of the change, held 3 s; the author can limit the look to a `region` (such as the canvas) so side-panel changes are ignored. Hand-placed pixel marks do not scale to 40 episodes.
3. **Before/after hold** (freeze 1 s, act, hold) and a **lower-third chip** with the control name and key.
4. **Captions** burned in and as SRT, from the narration text (it already exists per scene).
5. **Chapter markers** per beat in the MP4 so a viewer can jump to a tool.
6. **Fresh state per episode:** record each episode from a fresh unzip of `la-basic.zip` / `la-standard.zip` (Standard
   episodes change state: approvals, Dossiers, reveals). The installs are relocatable (verified 2026-11-16: boot from a new
   folder in 15–21 s).

### 6.2 Voice and look (decisions §9)

**Voice (D-TR1, chosen 2026-11-16):** Piper (local, offline) with the English **hfc_female** voice, warm tone, a touch faster than normal (take D of the voice trial: length scale 0.98, noise 0.72 / 0.85, 0.30 s between sentences; then low-end +3 dB at 180 Hz, -1.5 dB at 3.5 kHz, -3 dB at 7.5 kHz, gentle compression, loudness -18 LUFS). Fixed in `tools/la-video-voice.mjs`, which the renderer uses by default; it also reads ids the same way everywhere (HUB-01 "hub zero one", SL-0137 "S L zero one three seven", phone numbers digit by digit, ZX 9117 X letter-digit-letter). Engine and model: C:sandboxla-voice (outside the repo, one-time download).

**Format (D-TR2):** MP4, 1920 × 1080, H.264 + AAC, burned-in captions plus a sidecar `.srt`, chapter markers; one file per
episode named `<category><n>_<Title>.mp4` (e.g. `C4_Expand-and-collapse.mp4`), delivered in one folder per category and
zipped like the case videos. No hosting.

**Branding and music (D-TR4, delegated):** the product's own dark theme carries the look. Intro card 4 s: dark canvas,
"Inspecto LA · Training", episode code and title, category colour bar, edition chip (Basic / Standard / Advanced); outro
3 s: "Next: <episode>" and "Try it: la-basic.zip / la-standard.zip". Category colours: A teal, B blue, C indigo, D violet,
E slate, F amber, G green, H grey, I red. Music: a quiet original ambient bed generated for this series (no licensed or
copyrighted track), only under the intro and outro cards, never under narration.

**Language (D-TR5):** English, voice and captions.

### 6.3 UI walk (P1d, 2026-11-17) — what was walked live on the Standard demo build, and what is still open

Walked by the declarative `episode` scenario (`record-la-live.mjs episode`, dump steps) on Preview as Demo Admin / Demo Analyst, Space *Other Examples*:

- **Settings page** (`/settings/<drawer>`): the nav lists *My browser, Investigation access* (My settings); *Link Analysis, Analysis limits, Add data,
  Access* (This Space); *Host resources, Getting started counters* (This server). **Link Analysis** drawer: *Masking* (Masking mode: inherit the default /
  typed / all / none, each with its one-line meaning; *Entity Types in force* listed with how each is compared and whether masked), *Propagated risk: per-hop
  weights*, *Classifications that may not be shared by link*, *Sites that may frame the viewer page*, *Save Link Analysis settings*, and a *category list*
  block (New category list, Create category list). **Analysis limits**: groups Canvas, Browser algorithms, Display, Investigation, Connect, Traversal,
  Neighbours, Snapshots, Files, Working set, Pattern, Measures, Comparison, Reference, Case history, Dossier, Identity, Upload, Import, Entity profile,
  Input, Index, Graph run, Drafts, Embed, each limit blank = shipped default. **Access**: the role list with *Show all capabilities*, *New role*, per-role
  *Edit role* / *Revert role … to its built-in default*. **Investigation access**: each Investigation with *You: lead* and its members and *Revoke*.
- **Investigation settings folds** (File ▸ Open ▸ an Investigation ▸ Investigation actions ▸ Settings…, a dialog; folds in order): *General* (Name,
  Purpose, Dataset, Link columns, Time column, Copied from, Industry pack, Delete…), *Case* (Linked to Case …, Unlink; who can read it), *Members*
  (list, Revoke, User + Role + Grant), *Share and embed* (embed links off unless the host enables them: "Embed links are turned off. Ask an
  administrator."; Create link), *Drafts* (Fork a Draft; per Draft: Work on this Draft, Working Set, Conflicts, Rebase, Promote, Discard),
  *Replay and drift* (Compare with current data, Replay), *Oversight and approvals* (pending Expand request with who and when and why it is held;
  *Masked entities*: Reveal all N…), *Measures and templates* (Entities, Links, Events, Excluded, Furthest degree, Identities, each with Watch; At step N;
  Save as template…; New Investigation from a template…), *Dossier*, *References* (Load references).
- **Geo** (P1b): the route is `/geo-map` (rail item *Geo*); the page opens on the query form (a form whose first field is labelled "Source" <!-- vocab-allow: the Geo page's own field label --> with the value Locations (from a Dataset), then Dataset, Latitude column,
  Longitude column, Optional columns, Run) with *Open saved views* (the Space's three saved maps: *Where the accounts are*, *The ring across Europe*,
  *Cash-out through one till*; each has Actions, Load view, Tags), *Import / export saved views*, *Save the current view*, *Export the map* (off until a map is drawn).
  **Other Examples is Geo-ready** (`mule_account_locations_dataset` with LAT / LON, plus `mule_ring_legs_dataset` and `mule_cashout_legs_dataset` for routes):
  G1 and G2 record there; no new data is needed.
- **Walked 2026-11-17, second pass:** *More steps* (Investigation tab fold) lists **Connect** (Connect start entities), **Time** (Time window, Snapshot
  marker), **Compare**, **Threshold**, then *Scope list (this Investigation)* (Entity ids, Include, Leave out, Add start entities…), *Open the Step log*,
  *Investigation settings…*. **Similarity and prediction** (On screen): a *Node* picker, buttons *Find similar nodes*, *Predict links*, *Possible
  duplicates*, hint "Pick a node first." (up to 1,000 nodes). **Suspicion score**: *Find suspicious nodes*, text "Combined score from how connected,
  central and clustered a node is.", an optional *Risk model* name field (up to 1,000 nodes). **Geo map** loaded from a saved view (*Open saved views* ▸
  pick ▸ *Load view <name>*): toolbar *Search points*, *Find place*, *Filter point types*, *Fit the map to the data*, *Switch to the density heatmap*,
  *Keep only the points in the current viewport*, *Toggle the data panel*, *Toggle the geo-intelligence toolbox*, *Investigation tools (measure, radius
  search, polygon, notes)*, *Layer manager*, *Edit the query*, *Open saved views*, *Import / export saved views*, *Save the current view*, *Export the
  map*, zoom and fullscreen.
- **Still to walk with their episodes** (they change state or need a loaded map): the Drafts sequence fork → work → rebase → promote (F6); Propagated risk
  parameters (D5); the contents of the geo-intelligence toolbox and the Layer manager (G1/G2); the Time window, Snapshot marker, Threshold and Compare
  forms as filled in (F4).

### 6.4 Known blockers found while building the case videos

- **Index builds were refused on packaged installs** (the packaged `la_index_build` Jobs carried no last editor; every run logged "names owner
  'admin' but its last editor '(none recorded)' does not hold canConfigureAccess"). **Fixed in P1a (2026-11-17):** `tools/package-la-showcase.mjs`
  now stamps `updatedBy` (`configToon`, tested) as the seed scaffold does. A reinstalled Space needs the new package; the existing zips and
  demo installs built before the fix still refuse the build.
- On Standard the empty canvas has no *Start a graph* launchpad (that is Basic); B-category episodes must record on Basic.
- Some Spaces' Investigations mask ids for the analyst; narration must say what the screen shows (masked) — the case video
  *Fictitious Vendor* named unmasked ids over a masked graph.

### 6.6 Wave 1 status (2026-11-18) — all twelve episodes rendered and pushed

A1, A2, A3, B1, B4, C1, C2, C4, D1, E1, F1 and F8 are rendered (`C:\sandbox\la-video-out\_wave1`, outside git), with their
episode specs in `tools/la-training/`. Recorder verbs added in Wave 1: `selectAll`, `kb` (key with modifiers), `drag`, and
`shift` on `at`. Voice was rewritten wherever the live screen differed: B4 (no Graph counts pill drawn; Filter rows stage
two), C1 (a click opens details, selection is by Lasso), C2 (512 nodes grey out five layouts), E1 (cut-short notice, no
exact counts), F1 (masked MSISDN has no suggestions). Open gaps: A1 colour-scheme demo; the A3 guide's References group;
A2 "Try the sample" greyed on Basic; the Graph counts pill is in the DOM but not drawn (a product defect to check).
Install state left behind: Scratch Space, "Card authorisations" rename, list "Tills to review", views "Contractor C-14",
a C-14 snapshot, and a saved F1 Investigation. Next: P4/P5 waves.

### 6.7 Wave 2 status (2026-11-18) — seven of eighteen rendered and pushed

Rendered (`C:sandboxla-video-out_wave2`, outside git): A4, B2, B3, C3, D2, D4, D5. The recorder runs several renders in parallel
when each uses its own `--port` (`render-la-demo-video.mjs --port 9433` and so on). Page loads must wait for the text
*Start from*, or the first frame can show *No Dataset in this Space has a link yet*. Voice was rewritten wherever the live screen
differed: A4 (Banking has no phone column; cards read Not assessed), B2 (Two steps answer cut short at 50 rows), B3 (picking a
Dataset in File ▸ Explore Dataset… draws it at once; Settings ▸ Graph model then shows how), C3 (Display rules need a graph
with details), D2 (the Whole Dataset tools run on an open Investigation, so the episode shows the tab and its Run on server
card), D4 (Triangles and Cliques not shown), D5 (top similar rows, CLI-01 tops the suspicion table).

Not delivered, with the reason (open work, in this order): **C5** — selecting the three team nodes needs a Lasso on a
crowded graph; *Keep paths between start entities* needs start entities and its Apply stays off for a plain selection, and the
later Hide / Remove / Exclude lassos miss after the layout moves. **D6** — Find matches needs a Time column (Filter links by
time ▸ BOOKED_AT, and the *Large transfers only* view has none, so the guide's refusal message does not appear); matches never
light up in the recording. **D7** — Value Measures starts blank (Dataset, columns and window are text pencils to fill) and Burst
and periodicity needs a Time column; Run Measure was never pressed. Still to do: E2, F2, F3, F4, F5, F7, F9 (all drive saved
lists, Investigations, four-eyes or permissions) — probe each screen first.
Install state left behind (Basic :8099): saved preset "Merchants defrauded cards passed through" on the Banking Space; the Phone
number type added in A4 was deleted again.

### 6.5 Pilot P2 status (2026-11-17) — C4, D3, F8 rendered, awaiting operator review

Recorded and rendered with `tools/render-la-demo-video.mjs <id> --episode tools/la-training/<id>.episode.json --script <guide>`
(episode specs in `tools/la-training/`; output `C:\sandbox\la-video-out\_pilot`, outside git). Recorder verbs added for it: `at`
(click or double-click a canvas position), `reveal` (scroll a text into view), `download` and `upload` (file capture and a
hand-fed file picker), `undo`.

- **D3** (6:01) and **F8** (5:38) match their narration. D3's S05–S10 voice was rewritten to the live tables (Betweenness
  leads with the seller P-00476, not the household; Authority is led by the burglaries); F8's S05 no longer says to tick a
  snapshot (this Investigation has none). F8's edited manifest and bundle are made off camera from real downloads.
- **C4** (5:57): all ten scenes match. The S09 Undo was never broken in the product: Ctrl+Z answers "Undone." right after
  *Expand anyway*; the earlier run lost it because the Expand anyway click was wrapped in an `impact` diff step. Clicking a
  canvas node opens its details dialog instead of selecting it, so S04/S07 expand from the form's scope (*nodes not expanded
  yet* / *whole graph*), not from a selected node.
- **Yellow marks:** the `impact` diff step cannot mark a changed node, because the layout re-fits on every expand and the
  diff covers the whole canvas (its region had also run past the page height). Use `{"mark":"<label>","box":[x,y,w,h]}`
  (CSS px, read from a probe screenshot) instead; C4 marks AG-39 (S05) and AG-18 held (S08) that way.
- **Open:** node positions on the canvas shift after each step, so a click or mark position is read from a probe screenshot taken
  at that exact step. Do not record two episodes at once: a parallel probe made a click step fail.

## 7. User-guide gaps and mismatches found (fix in the guide alongside)

| Guide says | UI shows | Fix |
|---|---|---|
| Tool rail "Filter edges by time" | "Filter links by time" | guide wording (GLOSSARY: *Link*) |
| "Graph layout" | "Layout" | guide |
| "Show as list", "Show the rows" | "Show the table" / "Hide the table" | guide |
| "Help", "Glossary" in the rail | "About Link Analysis"; glossary only when the Industry pack states terms ("Industry pack glossary") | guide |
| dock "Working Set table" | tab label to confirm in UI (inventory: Working Set table) | verify |
| Algorithms described by group | toolbox groups by four questions ("How are they connected?", "Who matters most?", "Which groups and weak points exist?", "What looks unusual?") | guide §8 to adopt the four questions |
| — | Link filter canvas mode, Group single-link neighbours, Level of detail, Information density layout, Radial tree / Dendrogram / Fishbone layouts, Customise counts, Seal this window, Re-order steps (fork), Save as pattern pack, Register as Dataset | not or barely in the guide: add as the episodes are scripted |

## 8. Review and QA per episode

1. **Script review** against the §5 rows it claims and the user guide (intent sentences checked against the tooltip text the
   product itself uses).
2. **Product review** (one domain reviewer per category) for correctness of the *why*.
3. **Frame QA:** automated frames at each beat end (the case-video `qa` grids), checked for: the action happened, the mark
   sits on the changed element, no error toast, no blank/white frame, no stale state.
4. **Length gate:** 5:00–7:00; an episode that runs long is split, not sped up.
5. **Re-record trigger:** a UI change that renames or moves a covered control flags the episodes listing it (keep the §5
   table in step; later, a check that diffs the inventory labels against the scripts).

## 9. Phases, effort and decisions

| Phase | Work | Output |
|---|---|---|
| P0 | Approve this plan: episode list, categories, waves; decisions D-TR1…D-TR7 | signed plan |
| P1 | Prerequisites: index-owner stamp in the packager (§6.4), a Geo-ready Space if none (§6.3), declarative script steps and computed impact marks (§6.1 items 1–2), the §6.3 UI walk | tooling + walked notes. **Status 2026-11-17: P1a (packager stamp), P1b (Geo: Other Examples), P1c steps (declarative `episode` scenario in `record-la-live.mjs`) and P1d (§6.3) done; the computed impact marks (§6.1 item 2) are built too (below); only the four state-changing walks listed in §6.3 remain, and they are done while scripting F4, F6, D5, G1** |
| P2 | Pilot: C4, D3, F8 scripted, recorded, reviewed | 3 episodes, method confirmed |
| P3 | Wave 1 — the new-user path: A1–A3, B1, B4, C1, C2, C4, D1, E1, F1, F8 (12) | core course |
| P4 | Wave 2 — depth: A4, B2, B3, C3, C5, D2–D7, E2, F2–F5, F7, F9 (19) | full analyst course |
| P5 | Wave 3 — admin and specialist: D8, F6, G1–G3, H1–H3 (8, minus the pilots already done) | admin course |
| P6 | Publish: playlist per category, captions, chapter markers, shortcut card, links to the case videos; guide fixes (§7) | released series |

Effort per episode once P1 is done: script 1–2 h, recording and render ~30–40 min machine time, review 30 min, fixes as found.

**Decisions (operator, 2026-11-16)**

- **D-TR1 voice:** a human-sounding female neural voice, from a **local neural engine** (operator, 2026-11-16): runs offline,
  no account, nothing leaves the machine (§6.2 option b). Engine and voice model are a one-time download, confirmed with the
  operator before it is fetched; the voice is picked from short samples of a few English female voices.
- **D-TR2 format:** MP4, no hosting (§6.2).
- **D-TR3 Advanced features:** included — category I (I1–I3). I2 needs an Advanced non-demo build with PostgreSQL to record.
- **D-TR4 branding and music:** delegated; as in §6.2.
- **D-TR5 language:** English.
- **D-TR6 performance claim:** keep "search and filter stay fast on millions of records" (§2 rule 5).
- **D-TR7 episode list:** taken as approved (40 episodes with category I; 41 with H3, added after the user-guide review);
  the pilot (P2) may still merge or split episodes.

**Narration scripts (operator, 2026-11-16):** a full spoken script per episode, the three pilots first (C4, D3, F8), then
the rest. One file per episode group (operator, 2026-11-16), `user-guide-training-<group>.md` in the la-core hub, listed
by the overview [`user-guide-training-scripts.md`](../okf/product/module/la-core/user-guide-training-scripts.md); in each,
one `## <episode>` section per video in the renderer's format (`### <scene> · <title>`, one `- Voice:` line per scene;
`- Action:` and `- Impact:` lines are for the recorder and the reviewer). Places the §6.3 walk has not confirmed are
marked `[check in UI: …]` in the Action line, never in the Voice line.

Wave 3 (P5) gains I1–I3.

## 10. Found while writing the scripts (2026-11-16)

All 41 scripts are drafted (about 33,800 spoken words, 267 `[check in UI]` marks to settle on the live product). Writing
them against the UI source and the shipped zips corrected the §3 rows below; each episode card states its own change.
The shipped zips hold 11 industry Spaces (display names: Banking and finance, Enterprise, Healthcare, Insurance, Law
enforcement, Logistics, Mobile Money, Public sector, Other Examples, Telecom, Utilities), not the `spaces/_templates`
names.

- **Edition:** Basic shows only the toolbox's **On screen** tab (the guide's §8 says Basic's Whole Dataset tab still runs
  Dataset reads; the code hides it), so Find paths (D2), Value Measures and Burst and periodicity (D7), and Seed / Exclude
  by list (E2) record on Standard; A4 is Standard (Entities and the profile pages are not on Basic); D5's Propagated risk
  needs an open Investigation and pack risk data (Telecom pack only).
- **Space changes:** A3 → Banking and finance (*Find a Dataset* shows only above 6 Datasets); B4 → Other Examples
  (`mule_structuring`, with a time column and a ≥ 5 000 twin view; Wangiri is there, not in Telecom); D1, D6, F1, F2 →
  Other Examples; G1 → Other Examples, G2 → Insurance; I3 → Other Examples (the only daily feed and index-build Job).
- **Rows that did not match the product:** B1 and C4 — Basic's *Start a graph* has no Degrees stepper (one ring; the
  stepper is the Investigation start form's); C2 — *Customise counts* is not reachable on the Link Analysis page; C5 —
  *Switch off / on* a list is Investigation-only (Basic: *Show them again*); A2 — *What changed in your data* never shows
  on the shipped Spaces (shown in H2), and Getting started needs an empty Space; C3 — single-Dataset views give nodes no
  details, so Display rules use a two-Dataset graph; C4 — the busy limit is 500 and nothing planted reaches it, so the
  episode lowers it to 20 for one Expand; D3 — Rank nodes fills a table and does not restyle the canvas; F1 — Name and
  Purpose are asked by Save, not the start form; F2 — the fourth direction is labelled *Reciprocal*; F7 — there is no
  reveal-log screen (Oversight and approvals ▸ Masked entities), and masked wallets cannot be merged; F9 — share and reveal
  need Demo Admin, *Evaluate now* needs Demo Manager, embed needs `la.embed.enabled`; G2 — no shipped Geo Dataset has a
  `caseId` / `incidentId` / `objectId`, so the map ↔ graph pivot cannot be recorded, and Stay points finds no tracks;
  H2 — the quick-start CSVs are `inspecto/src/test/resources/onboard/full-cycle/*.csv` and must ship with the series;
  H3 — the UI says *Result cut short*, not *Truncated*; I1 — the policy editor is console-only and Advanced signs in
  through the identity provider (a demo sign-in build is needed); I2 — blocked: the next release ships without
  PostgreSQL (operator, 2026-10-23); I3 — its features are Standard and up, and index builds wait on §6.4.
- **Defects found:** *fixed 2026-11-16* — the 429 text said *a burst of 20 requests, then one every 3 seconds*
  (`limit-refusal.ts`), right only for `/db/query`-class routes (the Link Analysis bucket is 30, then one every 2 s, and
  tunable); it now states no budget and names the wait from `Retry-After`. *Fixed 2026-11-16* — the Drafts refusal said
  *its limit of 50 open Drafts* whatever the Space's `drafts.max_open`; it now names the limit the server's 409 states
  (`link-analysis-drafts.component.ts`). *Open, a decision* — on Standard, a start from one entity in any
  Space whose Industry pack sets no neighbour limit is an unbounded Expand, always above `four_eyes_fan_out_above`, so the
  unsaved start is refused (`UNSAVED_FOUR_EYES_START`) — by design, but every new user meets it first.
- **User-guide wording to fix (§7):** §8 Whole Dataset on Basic; *Run query* → *Run* (Graph model); *Truncated* →
  *Result cut short*; *Both ways, mutual* → *Reciprocal*; the Telecom Space has no Industry pack set (Generic).
