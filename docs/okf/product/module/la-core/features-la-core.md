---
type: Feature
title: Link Analysis — features
description: What an analyst can do with Link Analysis (LA), as a short presales capability list with the editions it ships in; each line links to the user guide or the architecture section that explains it.
tags: [module, la-core, link-analysis, features, presales]
timestamp: 2026-11-15T00:00:00Z
---

# Link Analysis — features

- [Availability](#availability) · [What an analyst can do](#what-an-analyst-can-do) · [Governance and trust](#governance-and-trust) · [More](#more)

Link Analysis lets an analyst explore who is connected to whom in platform Datasets (calls, transfers, devices,
accounts), record that exploration as an **Investigation**, run graph algorithms over it, and hand the result on as a
sealed dossier, a Case link or a standing detection.

## Availability

Every edition from Basic (`linkAnalysis` add-on in `offerings/basic.toon`: `la-storage`, `la-core`, `la-api`,
`geo-link`); Advanced adds `la-store-pg` (Postgres Investigations). Basic is the **explorer**: it withholds the
Investigation surface, the Geo map and Entity profile pages (see `docs/EDITIONS.md` and
[architecture §8](architecture-la-core.md#8-editions-and-packaging)). Investigations need Standard or above and the
Investigations add-on (`INS-AO-LA-INVESTIGATIONS`, held by default).

## What an analyst can do

- **Get started without help** - a Getting started page, a sample Space, Add data for a file, and help on every screen
  ([user guide §3](user-guide-la-core.md#3-signing-in-getting-started-and-add-data)).
- **Start a graph from one entity, a list, a paste or a whole Dataset**
  ([§5.1](user-guide-la-core.md#51-start-a-graph)).
- **Grow the graph step by step** with a preview of what the next step adds, busy entities held in a tray, and Exclude
  to a list ([§7](user-guide-la-core.md#7-expand-and-clean-up-the-graph); [architecture §6.9](architecture-http-and-ui.md)).
- **Find paths and connect seeds** - how A reaches B, and how a set of seeds connect within N hops
  ([architecture §3.1](architecture-la-core.md#31-investigation-op-log)).
- **Run graph algorithms** in the browser or on the server: centrality, communities, cycles, propagated risk and more
  (29 server algorithms; [§8](user-guide-la-core.md#8-the-analysis-catalogue); [architecture §5](architecture-la-core.md#5-graph-run-graph--la-core--la-storage)).
- **Match patterns** - fan-in / fan-out rings, bursts and regular series ([§9](user-guide-la-core.md#9-pattern-matching)).
- **Read an Entity's profile** across every Dataset that holds it (Standard and above;
  [architecture §6.3](architecture-http-and-ui.md)).
- **Record an Investigation** as a replayable, hash-sealed log with Drafts, members and undo / redo
  ([§11](user-guide-la-core.md#11-investigations); [architecture §3](architecture-la-core.md#3-domain-core-la-core)).
- **Hand the result on** - a sealed Dossier and bundle, a Case link, a time-limited public embed, a case hand-off to
  another product ([§11.9](user-guide-la-core.md#119-evidence-snapshot-dossier-sealed-bundle),
  [§11.10](user-guide-la-core.md#1110-widgets-sharing-and-embed); [architecture §6.2, §6.5](architecture-http-and-ui.md)).
- **Watch for it again** - Working Set measures and a standing detection that fires an Alert
  ([§11.8](user-guide-la-core.md#118-measures-and-alert-rules); [architecture §7](architecture-la-core.md#7-host-registration-jobs-and-live-detection)).
- **Speak the industry's words** - a Domain Profile picks the Telecom or Mobile Money pack's vocabulary
  ([architecture §3.6](architecture-la-core.md#36-domain-profile-and-industry-packs-la-editions-1-phase-4-as-built-2026-10-17-settles-ldp-d7)).
- **Upload a CSV** into a Collector that accepts uploads ([architecture §6.6](architecture-http-and-ui.md)).

## Governance and trust

- **Masking** - raw identifiers never leave in an answer; reveal needs a capability and is audited
  ([architecture §3.4](architecture-la-core.md#34-masking)).
- **Four-eyes** - a large expand waits for a second person ([§11.4](user-guide-la-core.md#114-large-expands-need-a-second-person-four-eyes)).
- **Category exclusion lists** applied to every new Investigation, restricted lists kept hidden
  ([architecture §6.8](architecture-http-and-ui.md)).
- **Honest limits** - every bound is a setting and a cut answer says so ([§15](user-guide-la-core.md#15-limits-and-honest-behaviour);
  [architecture §9](architecture-la-core.md#9-concurrency-and-resource-bounds-summary)).
- **Scale** - a versioned Parquet link index, proven to 10^8 edges ([architecture §4](architecture-la-core.md#4-link-index-la-storage)).

## More

[User guide](user-guide-la-core.md) · [API](api-la-core.md) · [Architecture](architecture-la-core.md) (module table in §2).
