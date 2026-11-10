# Inspecto LA Basic — hands-on UI test report (2026-11-10)

**What was tested.** The Basic install at `http://localhost:8080` (web UI rebuilt from master `12d6abf5f`, 27
Spaces), driven in the built-in browser the way a first-time analyst would use it: clicking, typing and reading
the screens. No code was changed. Console errors and network calls were read where a screen misbehaved.

**Spaces exercised:** Audit (`la-audit`), Link Analysis sample (`la-sample`), Logistics cargo theft
(`la-logistics`), Claims (`la-claims`), Utility (`la-utility`), Banking and finance (`la-banking`).

**Sizes:** desktop 1440×900, small laptop 1280×720, phone 375×812 (mobile preset). **Colour schemes:** dark
only. The app is fixed to dark (`la-app/src/app/app.config.ts` `scheme: 'dark'`): it ignores a light
`prefers-color-scheme` and has no switch, so light mode could not be reached (see O-1).

**Areas covered:** home (My work), Link Analysis (start area tabs Start a graph / Preset queries / Build a question /
Explore the Dataset, Draw, Expand, node details, toolbox On screen with Busiest nodes and Communities, canvas tool
rail: search, Layout, Display options, Export menu, Fit, the second tool group, Legend, Graph counts, File menu: New,
Open (Saved views and Investigations tabs), Explore Dataset, Examples, Save view as, Delete, Attach to Case,
Settings dialog with all seven sections, About, Full screen, rail collapse toggle, toolbox Hide/Show toggle, bottom
Data dock), Data (Dataset cards, Discover), Add data, Entity Lists, Settings (all seven sections), What can I do
next?, the `?` shortcuts dialog, the Space switcher. There is no Geo map entry in the rail and no user menu in
Basic (no sign-in), so neither was tested.

**Test data left behind:** one Snapshot attached to a placeholder Case in `la-audit` (finding D-1). The test
saved view `ui test view` was created and deleted again. Node labels were switched on and back off.

**Not exercised:** uploading a file in Add data (needs a local file picker), Export downloads (PNG / JSON /
SVG / GraphML; downloads need separate approval), a real `?` key press (the browser tool could not send `?`;
the dialog was opened with a synthetic key event and works).

---

## Findings, by severity

Severity: **P1** blocks a user's task · **P2** misleads or breaks a main path · **P3** friction, layout or
wording. Each defect has a row in `docs/BACKLOG.md` (id in brackets).

### P2

**D-1 · Attach to Case in Basic attaches to invented Cases and reports success** (`LA-BASIC-PLACEHOLDER-CASE-1`)
- Where: Link Analysis, Audit, any graph ▸ second tool group ▸ *Attach to Case* (paper-clip icon).
- Expected: Basic has no Cases, so the button is disabled with a reason ("Needs Standard edition"), or the dialog
  says so and offers nothing to pick.
- Happened: the dialog says *"Creating a Case needs operational objects, which this bundle does not include
  (Standard edition and above)"* and *"Placeholder Cases — the ops module is not installed"*, yet lists
  `CASE-2026-0318 · Suspected layering network (placeholder Case)` and `CASE-2026-0322 · Burner rotation cluster`
  (fraud stories, in an Audit Space). Picking one and pressing *Seal and attach* calls `POST /inv/snapshots` and
  `POST /inv/snapshots/attach` (both 200) and toasts *"Sealed … (sha256:6f2…) and attached it to
  CASE-2026-0318"*. An analyst now believes the evidence sits on a real Case. Also: *Case is required.* shows as
  soon as the picker opens, and the selected radio is nearly invisible (D-12).

**D-2 · The sample Space is empty: its only example and saved view fail** (`LA-SAMPLE-EMPTY-DATASET-1`)
- Where: Space *Link Analysis sample (invented data)* (`la-sample`) ▸ Link Analysis ▸ File ▸ Examples ▸
  *Follow the money* (or the home card `mule_layering_ring`).
- Expected: the sample draws the money-mule ring the home card describes.
- Happened: red banner *"Could not draw the graph. Dataset 'mule_transfers_dataset' has no data yet: nothing has
  been ingested into its store (mule_transfers/database). Run the Pipeline that fills it, or check the Dataset's
  physicalRef."* `GET /inv/presets`, `POST /inv/projection`, `/shapes` and `/discover` all answer 409. The Data
  page lists the Dataset as *"Kept up to date by a Pipeline"* without saying it is empty, and nothing in the LA
  app can run a Pipeline. The cause (template ships no data vs. this install's Pipeline never ran) was not
  verified; to a user, the product's own sample is broken. The banner's wording (*store*, *physicalRef*) is
  developer text.

**D-3 · Opening the Data dock makes the page taller than the window** (`LA-DOCK-PAGE-GROWS-1`)
- Where: Link Analysis with a graph ▸ bottom bar (*Rows / Results*) ▸ open.
- Expected: the dock takes space from the canvas; the page never grows (angular-ui §5.2 *Bounded panels*).
- Happened: at 1440×900 the document becomes 1025 px tall (the dock box runs 74 px below the window, the page
  scrolls 125 px) and only one table row is visible. At 1280×720 the document is 926 px: the Links / Nodes toggle
  and the search box are visible, every table row is below the window. Same at the pane's own 1411×880 (926 px).

**D-4 · Basic-edition 503s are shown as red errors or with the wrong reason** (`LA-BASIC-503-EXPLAINED-1`)
- File ▸ Open ▸ *Investigations* tab (`GET /inv/investigations` → 503): a red error box listing *"Investigations,
  Drafts, Dossier, templates, members, embed links, data trust, Case history, Graph Run, index builds and
  Alert Rules on Investigations … this build (Basic) is the Link Analysis explorer"*, under live Owner / Show / Dataset
  filters for a list that cannot exist. Switching back to *Saved views* keeps the red box on that tab too.
- Settings dialog ▸ *Add data* (`GET /inv/uploads` → 503): *"Uploads are not available here - the link-analysis
  module or a write root is missing."* The reason is wrong (Basic by design) and contradicts the Add data page,
  which works in the same Space.
- Expected: one calm, neutral note per place ("Investigations are part of Standard and above"), not the error
  style, and the right reason.

### P3

**D-5 · Radial layout squashes the graph into a wedge** (`LA-RADIAL-LAYOUT-SQUASH-1`)
- Where: Audit, the *Reached by many* answer drawn (27 nodes, two components) ▸ Layout ▸ *Radial*.
- Happened: the 24 neighbours of `V-001` are drawn in a narrow fan about 40° wide, overlapping one another; the
  other component flattens into near-horizontal lines. The layout also stays chosen for the next graph (the
  V-117 graph drew with its nodes pinned to the canvas edges). Force, the default, looks right.

**D-6 · The phone layout scrolls sideways** (`LA-PHONE-OVERFLOW-1`)
- Link Analysis with a graph at 375 px: the canvas tool bar (`nav` "Link Analysis tools") runs from x 138 to 503;
  it wraps over the page title and Full screen / About sit on top of tool icons; the document is 507 px wide.
- Add data at 375 px: the *Read from another database or a remote system* button is 469 px wide.
- Also on the phone: the Space switcher shows only an icon (which Space am I in?), and the rail collapse and
  toolbox toggles are absent from the header.

**D-7 · The Space switcher overflows the rail, and its list is hard to read** (`LA-SPACE-SWITCHER-1`)
- The switcher button grows with the Space name and is not clipped: *Link Analysis sample (invented data)* makes
  it 378 px wide in a 195 px rail, so it covers page content (the Mappings heading on Data, the Add data card on
  Link Analysis, the dock's Links toggle). *Banking and finance* and *Logistics cargo theft* overflow too.
- In the list (29 entries): three names still carry the old prefix (*Link Analysis mobile money*, *Link Analysis
  sample (invented data)*, *Link Analysis showcase*); near-twins cannot be told apart (*Logistics* / *Logistics
  cargo theft*, *Utilities* / *Utility*, *Telecom* / *Telecom revenue assurance*, *Law enforcement* / *Police*,
  *Insurance* / *Claims*); the order is not alphabetical (*Provider billing fraud* sits between *Enterprise* and
  *Healthcare*); a two-line name (*Mobile financial services fraud*) is squeezed into a 48 px item.
- Switching Space with a graph open toasts *"Closed: the analysis was closed when you switched Space. It stays in
  Space la-claims"* (a Space id, not its name, and a lowercase sentence start).

**D-8 · Controls clipped or overflowing their panels** (`LA-CLIPPED-CONTROLS-1`)
- *Search nodes* pop-up: the 288 px field sits in a 278 px panel, so the panel gets a horizontal scroll bar and
  the clear icon is cut.
- Two-choice toggles in the toolbox cut their labels: *Strongest ties* (Shortest path), *Label spread (fast)*
  (217 px of text in 159 px) and *Louvain (finer)* (153 in 112) in Communities.
- File ▸ *Save view as* panel: *Start an Investigation from t…* is cut and the panel scrolls sideways.
- Display options menu shows both scroll bars at 1440×900.
- The canvas hover card is a white box in a serif font on the dark theme.

**D-9 · The canvas tool bar moves under the pointer** (`LA-TOOLBAR-SHIFT-1`)
- After a node search a clear (×) button is inserted into the tool bar and every tool to its right shifts by
  one slot; clearing the search shifts them back. A click aimed at *Fit to screen* opened *Export* instead.

**D-10 · Overlays and fit hide the graph** (`LA-OVERLAYS-HIDE-GRAPH-1`)
- Claims ▸ saved view `claims_network`: the expanded Legend and Graph counts panels cover the top third of the
  canvas, where the graph is drawn. After *Expand* on the V-117 graph, fit placed a node under the Graph counts
  chip and others on the canvas edge (half cut). In Banking ▸ `circular_trade` the bottom component is cut by the
  canvas edge.
- Node labels are off by default (Display options ▸ *Node labels* unchecked), so a first graph is a set of
  unnamed circles until the user finds that setting; it works when switched on.

**D-11 · The Open dialog hides its main action** (`LA-OPEN-DIALOG-ACTIONS-1`)
- File ▸ Open ▸ pick a saved view: *Open*, *Rename*, *Duplicate*, *Delete*, *Version history* sit at y 821 in an
  880 px window, below the fold of the dialog's scroll area (the mouse wheel did not scroll it in this test;
  only scrolling the element into view reached them). The dialog also changes width between its two tabs.
  *Last changed 2026-11-09 06:27 UTC* is in UTC while every other time is local (`DATES-ONE-ZONE-1`).

**D-12 · Selected radio buttons are nearly invisible in dark** (`LA-DARK-RADIO-CONTRAST-1`)
- Add data (*Events between entities*, *A file*) and Attach to Case (*An existing Case*): the selected radio is a
  dark dot on a dark ring; it reads as unselected.

**D-13 · Settings ▸ Link Analysis flashes a red error while loading** (`LA-SETTINGS-PROFILE-FLASH-1`)
- On first open, *"Domain Profile unavailable: The server did not answer with the Domain Profile."* shows in red
  with *Reading the Link Analysis settings…*; `GET …/domain-profile` then answers 200 and the page fills in.

**D-14 · Wording and vocabulary breaks** (`LA-UI-TEXT-PASS-2`) — against `docs/GLOSSARY.md` and angular-ui §5.2:
- Keyboard shortcuts dialog: *"Grow the graph (opens the Grow panel)"*, *"Undo the last Grow or Exclude"* —
  the LA word is Expand (the canvas itself says *Expand… (G)*).
- Saved-view descriptions shipped in the showcase Spaces tell users to *"Seed the repeat passenger (see the
  runbook)"* (Start entity; no runbook in the product), *"Run Cliques and Triangle count"*, *"Run HITS or
  PageRank"*, *"Run Articulation points and Bridges"* — names the toolbox does not show (it says Cohesive groups,
  Centrality, Cut points). The Data page shows the same template text (*"Link Analysis audit showcase - ONE
  link Dataset … built by the ap_links Job"*).
- Developer text on screen: Settings ▸ Link Analysis *"-Dla.embed.enabled=true"*, *"upper-trim normaliser;
  claims PARTY"*; Installed modules *"is not on the class path"*; Settings ▸ Access shows the raw capability id
  `canOpenInboundInvestigation` among plain-language chips, *"Seed default"*, and the word *capabilities*; the
  Explore panel *"(in src, dst)"*, *"by event_date"*, *"Answered from the link index (version 10)"*; Build a
  question *"2026-10-01, or an instant ending in Z"*; Expand *"(not recorded)"*; node details *"No risk data for
  this industry pack"*; Entity Lists *IDENTITY FACT LOG* (*"0 fact(s)"*, *"note the head hash"*).
- Breaks of §5.2: `" - "` used as a dash (preset help *"link to - a collector, a mule"*, Add data *"Standard and
  above - this build (Basic)"*, uploads note, masking list); *"(s)"* plural; *"1 000"* not *"1,000"*; straight
  quotes in toasts (*Saved the view "ui test view".*); lowercase *space* (*"switch space"* aria label and tooltip,
  *"in this space"* on Settings ▸ Link Analysis); a sentence starting lowercase (home: *"it uses. a saved view
  re-reads live data"*); Settings section *THIS STUDIO* (Studio is not an LA word); *"Link analysis"* vs *"Link
  Analysis"* in Installed modules.
- *"a collector"* (the preset help) uses the glossary word Collector for a money collector — one word, one concept.
- Add data page is titled *Onboard a file* while the rail, buttons and Settings call it *Add data*.

---

## Smaller observations (no BACKLOG row)

- **O-1 · Light scheme unreachable.** The app is fixed to dark; a light OS setting is ignored and there is no
  switch. If light is meant to be supported in la-app, this is a defect; if dark-only is a decision, record it in
  the la-core hub.
- **O-2 · Full screen gave no feedback** in the embedded browser (`document.fullscreenElement` stayed null). May be
  the test browser; re-check in a normal Chrome.
- **O-3 · Expand that adds nothing says nothing.** *Expand from here* on `V-001` (all neighbours already drawn)
  left counts at 27 / 29 with no message.
- **O-4 · Deleting the open saved view also closes the graph** and shows no confirmation toast.
- **O-5 · Preset *Ask* in Utility took 4–7 s** with only a greyed button; no *Asking…* text.
- **O-6 · The start-area tabs disappear once a graph is drawn**; File ▸ New brings them back. A first-time user
  may not find the way back to *Preset queries*.
- **O-7 · The page title stays *Link Analysis*** with a saved view open; nothing on screen names the view.
- **O-8 · The node details dialog shows the Entity Type as *entity*** (the Logistics legend also lists *entity*),
  while the start area calls the same things *Party*, *Driver*, *Premise*.
- **O-9 · Console:** *"Unable to preventDefault inside passive event listener invocation"* on canvas wheel
  zoom (every Space).
- **O-10 · The `?` keyboard-shortcuts dialog has no button** anywhere in la-app; the header *?* opens About.

## What worked well

- **The bounded start area holds.** Start a graph, Preset queries (answer card with *Draw these*), Build a
  question (answer *cut short* notice, own scroll) and Explore the Dataset never grew the page at 1440×900 or
  1280×720.
- **Drawing and expanding are clear:** the entity box suggests matches with link counts and dates
  (*"30 links, first seen 10 Jan 2026"*), the Expand panel previews *"about 2 nodes and 3 links"*, and the result
  toast offers *Undo*.
- **Disabled controls say why** in most places: *Select nodes to remove*, *Mind map: needs a tree-shaped graph*,
  the greyed Examples entry in Audit, *Draw a graph first.*
- **Saved views** save, list, preview and delete cleanly; the delete confirmation names the view and says copies
  stay in its version history.
- **The toolbox** quick questions (*Busiest nodes*) and Communities (6 groups on Claims) answer in plain words.
- **Rail collapse and toolbox Show/Hide** toggles in the header work and redraw the canvas.
- **About Link Analysis** is a short, readable glossary of the screen's terms.
- **Space switching** clears the old graph and says so; every showcase Space opened its saved views and drew
  them (Audit, Logistics cargo theft, Claims, Utility, Banking and finance).
- **Settings** is well organised (My browser / This Space / This server) and Getting started counters are a nice
  touch.
