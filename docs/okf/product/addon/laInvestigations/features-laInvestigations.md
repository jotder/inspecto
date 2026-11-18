---
type: Feature
title: "Link Analysis Investigations — features"
description: "The Investigations add-on lifts Link Analysis from the Explorer level to recorded, evidence-grade Investigations."
tags: [laInvestigations, linkAnalysis, features, presales]
timestamp: 2026-10-22T00:00:00Z
---

# Link Analysis Investigations — features

**Product Map Item:** [`addon.laInvestigations`](../../../../../product-map/addon/laInvestigations.toon) · **Status:** ga ·
**Availability:** Billable add-on `INS-AO-LA-INVESTIGATIONS`, **included by default on Standard and above**
(`offerings/standard.toon`, inherited by Advanced and Preview); never on Basic. **Enforcement: `runtime`** — the running
server checks that the installation holds it (`LA-ONBOARD-1` OB-5).

## What it is

Link Analysis runs at one of two **levels**. The **Explorer** level (every edition) is the graph, Grow, the browser
algorithms, Entity Lists and saved views. The **Investigations** level adds the Investigation surface: the sealed op log,
replay and compare, Drafts, the Dossier and its bundle, templates, members, embed links, data trust, Case history, Graph
Run on the server, link index builds and Alert Rules on Investigations. The level decides the screens only; who the user
is (sign-in, masking, roles) stays the edition's business.

## How it is enforced

- Held by default wherever the edition includes Investigations. An installation removes it with the JVM setting
  `-Daddons.laInvestigations=false` (for example through `INSPECTO_JAVA_OPTS`). A signed entitlement will replace this
  setting; the one place that reads it is `EditionFeatures.holdsInvestigationsAddon()`.
- Without it the Investigation routes answer `503 CAPABILITY_UNAVAILABLE` naming the add-on, `/bootstrap` reports
  `laLevel: "explorer"` with a `featureReasons.investigations` line, and the Link Analysis screens show Investigation
  actions locked with that line. Masking stays on for a sign-in installation either way.

## Related hubs

[la-core](../../module/la-core/index.md) · [Link Analysis add-on](../linkAnalysis/index.md) · [ops](../../module/ops/index.md)
