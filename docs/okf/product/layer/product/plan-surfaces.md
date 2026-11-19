---
type: Capability
area: UI
title: Surfaces & Lenses (UI) — capability spec
description: The requirement-of-record and as-built specification for the SPA shell — the three Lenses and how they differ from server capabilities, navigation and the Menu Builder, routing, the design system and its token gate, theming, accessibility, connectivity and error handling, settings, and what the server publishes for the shell to read.
status: current
written: 2026-09-08
supersedes-rows: REQUIREMENTS §3.15 UI-1..UI-8 (this file corrects them, see §2)
item: layer.product
audience: internal
verified: 2026-11-20 last-edit
---

# Surfaces & Lenses (`UI`)

> **How to read this file.** §1–§2 are the *requirement of record*: where they disagree with
> `docs/okf/product/layer/platform/spec-requirements.md` §3.15 or `docs/EDITIONS.md`, **this file wins** and the disagreement is stated
> in place. §3 is the as-built specification, §4 the dated decisions, §5 what is not built, §6 what was
> refused or superseded, §7 the pointers, §8 how it is verified.
>
> ⛔ **The area is *Surfaces & Lenses*, not "operator console UX".** `docs/GLOSSARY.md` §14 renamed it:
> *console* is not canonical, and a `ui/` directory would collide with `inspecto-ui/`. The rows are the
> **shell** — Lens switching, navigation, the design system, theming, accessibility, the responsive
> sweep. Individual panes belong to the capability whose data they show.

## Parts

This page is split by topic into 2 parts; each part lists the sections it holds.

1. **[Purpose, requirements and specification](plan-surfaces-scope-specification.md)** — 1. Purpose & scope · 2. Requirements of record · 3. Specification
2. **[Decisions, not built, refused and verification](plan-surfaces-decisions-verification.md)** — 4. Decisions (dated one-liners) · 5. Not built · 6. Refused & superseded · 7. As-built pointers · 8. Verification
