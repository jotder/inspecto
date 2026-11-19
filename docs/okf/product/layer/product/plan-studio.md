---
type: Capability
area: BI · INV
title: Studio (BI + INV) — capability spec
description: The requirement-of-record and as-built specification for Studio — the Query Library, Widgets over the VizPlugin registry, Dashboards, KPIs & Reports with scheduled delivery, curated templates, public embed, and the two investigation studios (Link Analysis, Geo Map Analysis) — one file, eight sections, machine-verified pointers.
status: current
written: 2026-09-08
supersedes-rows: REQUIREMENTS §3.5 BI-1..BI-8 and §3.6 INV-1..INV-4 (this file corrects them, see §2)
item: layer.product
audience: internal
verified: 2026-11-19 last-edit
---

# Studio (`BI` + `INV`)

> **How to read this file.** §1–§2 are the *requirement of record*: where they disagree with
> `docs/okf/product/layer/platform/spec-requirements.md` §3.5/§3.6 or `docs/EDITIONS.md`, **this file wins** and the disagreement is
> stated in place. §3 is the as-built specification, §4 the dated decisions, §5 what is not built
> (tracked + `UNTRACKED`), §6 what was refused or superseded, §7 the pointers into code and docs, §8 how
> it is verified. Everything in backticks under §3 and §7 was checked against the tree on 2026-09-08.
>
> ⛔ **`BI` and `INV` are ONE capability.** `docs/GLOSSARY.md` §14 settles it: Link Analysis and Geo Map
> Analysis live *inside* **Studio** (§1-A), so `INV` was never a sibling of `BI`, and both
> "Investigation" and "presentation" are undefined words. Both ID ranges keep their numbers; one spec
> owns them.
>
> **Canonical vocabulary** (`GLOSSARY.md` §7, binding). **Type → Instance** throughout: a
> **Visualization Type** is the template, a **Widget** is the configured instance bound to a Dataset's
> **Result Set**, a **Dashboard** is a layout of Widgets. A BI aggregation is a **Measure** — ⛔ never
> the BI sense of *metric* <!-- vocab-allow: names the banned BI synonym in order to ban it -->, which
> is reserved for the operational counters `OPS` owns. A **Report** is an *operational* deliverable and
> is deliberately not a Dashboard. ⛔ Never call a Widget a "chart" in a model name.

## Parts

This page is split by topic into 3 parts; each part lists the sections it holds.

1. **[Purpose, requirements and specification](plan-studio-scope-specification.md)** — 1. Purpose & scope · 2. Requirements of record · 3. Specification
2. **[Specification — KPIs, Reports and scheduled delivery onward](plan-studio-kpis-reports.md)**
3. **[Decisions, not built, refused and verification](plan-studio-decisions-verification.md)** — 4. Decisions (dated one-liners) · 5. Not built · 6. Refused & superseded · 7. As-built pointers · 8. Verification
