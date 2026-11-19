---
type: Guide
title: Inspecto LA training videos — narration scripts
description: Overview of the Inspecto LA training-video narration scripts — how an episode script is read, the voice and the renderer, and the contents of all 41 episodes; the scripts themselves are one file per episode group (A-I).
resource: tools/render-la-demo-video.mjs
tags: [module, la-core, link-analysis, user-guide, training, video]
timestamp: 2026-11-16T00:00:00Z
---

# Inspecto LA training videos — narration scripts

> **Draft of 2026-11-16, written from the user guide, the UI source and the shipped Spaces' ground truth; not recorded,
> not reviewed by product.** The scripts are one file per episode group (A-I), listed under *Episode contents*; the pilots
> C4, D3 and F8 were written first and set the format. The series, its 41 episodes and its teaching rules
> are in the plan [`2026-11-16-la-training-series-plan.md`](../../../../superpower/2026-11-16-la-training-series-plan.md).
> Each episode complements a chapter of the [user guide](user-guide-la-core.md): the guide says what every control does; the
> episode shows why you would use it and what it changes on the graph.

**How to read an episode.** The `## <code>_<Title>` line names the video file. Under it, the episode card: edition, Space,
story, target length, what the viewer can do afterwards, the state to record from, the plan's coverage rows, *Try it*,
*Check* and *Gotchas*. Then one `### <scene> · <title>` per beat:

* `- Voice:` the narration, one line, read by the series voice (the local Piper voice, plan §6.2). It is what the renderer
  synthesises; ids are written as they appear on screen, and the voice tool reads them out (HUB-01 "hub zero one").
* `- Chip:` the lower-third label: the control's on-screen name and its key.
* `- Action:` what the cursor does, for the recorder. `[check in UI: …]` marks a detail the §6.3 walk of the plan has not
  confirmed yet; it is settled before recording and never appears in a Voice line.
* `- Impact:` what changes on screen, for the before/after hold and the yellow mark.

Renderer: `node tools/render-la-demo-video.mjs <code_Title> --script docs/okf/product/module/la-core/user-guide-training-<group>.md`
(for example `C4_Expand-and-collapse` with `user-guide-training-c-the-canvas.md`) reads the Voice lines (the episode scenarios themselves need the plan's declarative steps, §6.1, before they record).
At about 150 words a minute, a 6-minute episode carries about 800 spoken words, a 7-minute one about 950.

## Episode contents

One file per episode group; render an episode with `--script` set to its group file.

- **A · Getting started**: [user-guide-training-a-getting-started.md](user-guide-training-a-getting-started.md)
- **B · Building a graph**: [user-guide-training-b-building-a-graph.md](user-guide-training-b-building-a-graph.md)
- **C · Reading and shaping the canvas**: [user-guide-training-c-the-canvas.md](user-guide-training-c-the-canvas.md)
- **D · The analysis toolbox**: [user-guide-training-d-analysis-toolbox.md](user-guide-training-d-analysis-toolbox.md)
- **E · Keeping and sharing your work**: [user-guide-training-e-keeping-and-sharing.md](user-guide-training-e-keeping-and-sharing.md)
- **F · Investigations (Standard)**: [user-guide-training-f-investigations.md](user-guide-training-f-investigations.md)
- **G · Geo and entity pages (Standard)**: [user-guide-training-g-geo-and-entity-pages.md](user-guide-training-g-geo-and-entity-pages.md)
- **H · Administering a Space**: [user-guide-training-h-administering-a-space.md](user-guide-training-h-administering-a-space.md)
- **I · Advanced edition**: [user-guide-training-i-advanced-edition.md](user-guide-training-i-advanced-edition.md)

| Episode | Title | Edition | Scenes | Spoken words | UI checks open |
|---|---|---|---|---|---|
| [A1](user-guide-training-a-getting-started.md#a1_what-inspecto-la-is-and-a-tour-of-the-screen) | What Inspecto LA is, and a tour of the screen | Basic | 12 | 992 | 6 |
| [A2](user-guide-training-a-getting-started.md#a2_spaces-the-home-page-and-saved-views) | Spaces, the home page and saved views | Basic | 12 | 850 | 7 |
| [A3](user-guide-training-a-getting-started.md#a3_your-data-datasets-columns-and-mappings) | Your data: Datasets, columns and Mappings | Basic | 11 | 847 | 8 |
| [A4](user-guide-training-a-getting-started.md#a4_entities-and-entity-types) | Entities and Entity Types | ? | 11 | 815 | 6 |
| [B1](user-guide-training-b-building-a-graph.md#b1_start-a-graph) | Start a graph: one entity, a list, pasted values, the whole Dataset | Basic | 11 | 770 | 8 |
| [B2](user-guide-training-b-building-a-graph.md#b2_preset-queries-and-build-a-question) | Preset queries and Build a question | Basic | 11 | 725 | 6 |
| [B3](user-guide-training-b-building-a-graph.md#b3_explore-the-dataset-and-the-graph-model) | Explore the Dataset and the Graph model | Basic | 11 | 721 | 7 |
| [B4](user-guide-training-b-building-a-graph.md#b4_find-and-filter-search-node-types-time-timeline) | Find and filter: search, node types, time, timeline | Basic | 12 | 846 | 4 |
| [C1](user-guide-training-c-the-canvas.md#c1_moving-around-and-selecting) | Moving around and selecting | Basic | 12 | 710 | 3 |
| [C2](user-guide-training-c-the-canvas.md#c2_layouts-legend-counts-and-minimap) | Layouts, legend, counts and minimap | Basic | 12 | 808 | 6 |
| [C3](user-guide-training-c-the-canvas.md#c3_making-the-graph-readable-display-options-and-display-rules) | Making the graph readable: Display options and Display rules | Basic | 12 | 802 | 6 |
| [C4](user-guide-training-c-the-canvas.md#c4_expand-and-collapse) | Expand and collapse, one degree at a time | Basic | 10 | 947 | 6 |
| [C5](user-guide-training-c-the-canvas.md#c5_cleaning-up-hide-remove-trim-exclude-lists) | Cleaning up: hide, remove, trim, exclude, lists | Basic | 12 | 699 | 4 |
| [D1](user-guide-training-d-analysis-toolbox-connections.md#d1_quick-questions-busiest-tight-groups-route) | Quick questions: busiest, tight groups, route A to B | Basic | 11 | 861 | 8 |
| [D2](user-guide-training-d-analysis-toolbox-connections.md#d2_how-are-they-connected) | How are they connected? | ? | 11 | 872 | 8 |
| [D3](user-guide-training-d-analysis-toolbox-connections.md#d3_who-matters-most) | Who matters most? | Basic | 11 | 976 | 9 |
| [D4](user-guide-training-d-analysis-toolbox-patterns.md#d4_groups-and-weak-points) | Groups and weak points | Basic | 11 | 831 | 7 |
| [D5](user-guide-training-d-analysis-toolbox-patterns.md#d5_what-looks-unusual) | What looks unusual? | Basic | 11 | 810 | 8 |
| [D6](user-guide-training-d-analysis-toolbox-patterns.md#d6_pattern-match) | Pattern match | Basic | 11 | 841 | 8 |
| [D7](user-guide-training-d-analysis-toolbox-money-scope.md#d7_money-and-time) | Money and time: Value Measures, bursts and rhythms | ? | 11 | 832 | 7 |
| [D8](user-guide-training-d-analysis-toolbox-money-scope.md#d8_on-screen-vs-whole-dataset) | On screen vs Whole Dataset: limits and scale | Basic → Standard | 11 | 889 | 7 |
| [E1](user-guide-training-e-keeping-and-sharing.md#e1_saving-opening-and-exporting) | Saving, opening and exporting | Basic | 12 | 848 | 5 |
| [E2](user-guide-training-e-keeping-and-sharing.md#e2_entity-lists-and-identity-facts) | Entity Lists and Identity Facts | Standard | 11 | 805 | 6 |
| [F1](user-guide-training-f-investigations-first-graph.md#f1_signing-in-roles-and-your-first-investigation) | Signing in, roles and your first Investigation | Standard | 11 | 819 | 6 |
| [F2](user-guide-training-f-investigations-first-graph.md#f2_expand-with-intent) | Expand with intent: direction, kinds, time, strength, fan-out | Standard | 11 | 839 | 6 |
| [F3](user-guide-training-f-investigations-first-graph.md#f3_recorded-clean-up-and-scope) | Recorded clean-up and scope | Standard | 12 | 896 | 6 |
| [F4](user-guide-training-f-investigations-teams-drafts.md#f4_step-log-undo-re-order-and-replay) | The step log: replay, undo, re-order, more steps | Standard | 11 | 916 | 9 |
| [F5](user-guide-training-f-investigations-teams-drafts.md#f5_teams-members-four-eyes-reviewers-and-outsiders) | Teams: members, four-eyes, reviewers and outsiders | Standard | 11 | 870 | 8 |
| [F6](user-guide-training-f-investigations-teams-drafts.md#f6_drafts-work-in-parallel) | Drafts: work in parallel | Standard | 10 | 748 | 7 |
| [F7](user-guide-training-f-investigations-evidence.md#f7_masked-data-reveal-and-identity-resolution) | Masked data and audited reveal; resolving identities | Standard | 11 | 899 | 8 |
| [F8](user-guide-training-f-investigations-evidence.md#f8_evidence-coverage-dossier-and-sealed-bundle) | Evidence: coverage, Dossier and the sealed bundle | Standard | 11 | 838 | 5 |
| [F9](user-guide-training-f-investigations-evidence.md#f9_from-finding-to-action) | From finding to action: Alert Rules, Cases, widgets and sharing | Standard | 12 | 957 | 10 |
| [G1](user-guide-training-g-geo-and-entity-pages.md#g1_the-geo-map) | The Geo map | Standard | 11 | 804 | 6 |
| [G2](user-guide-training-g-geo-and-entity-pages.md#g2_geo-intelligence-and-map-to-graph) | Geo intelligence and pivoting between map and graph | Standard | 10 | 779 | 5 |
| [G3](user-guide-training-g-geo-and-entity-pages.md#g3_entity-profiles) | Entity profiles | Standard | 11 | 727 | 8 |
| [H1](user-guide-training-h-administering-a-space.md#h1_settings-for-a-space-and-for-you) | Settings for a Space and for you | Standard | 11 | 822 | 7 |
| [H2](user-guide-training-h-administering-a-space.md#h2_from-your-own-file-to-a-first-finding) | From your own file to a first finding (capstone) | Basic | 12 | 830 | 7 |
| [H3](user-guide-training-h-administering-a-space.md#h3_when-the-product-says-no) | When the product says no: locked controls, cut results and refusals | Basic → Standard | 12 | 859 | 4 |
| [I1](user-guide-training-i-advanced-edition.md#i1_access-policies) | Access Policies: narrowing what roles allow | Advanced | 9 | 661 | 6 |
| [I2](user-guide-training-i-advanced-edition.md#i2_investigations-on-postgresql) | Investigations shared by several servers (PostgreSQL) | Advanced | 9 | 646 | 2 |
| [I3](user-guide-training-i-advanced-edition.md#i3_scale-server-runs-link-index-daily-ingestion) | Scale: graph runs on the server, the link index, daily ingestion | ? | 11 | 826 | 7 |
