---
type: Plan
title: module.exchange - board row detail
description: The narrative behind module.exchange's long rows on the board (docs/BACKLOG.md), moved here verbatim so each board row stays a short summary.
tags: [module, exchange, plan, backlog]
timestamp: 2026-11-19T00:00:00Z
item: module.exchange
audience: internal
status: plan
verified: 2026-11-19 last-edit
code: features/inspecto-exchange
---

# module.exchange - board row detail

Each section below is one row of [the board](../../../../BACKLOG.md), headed by its id or name. The board row keeps
the row's priority, a one-line summary of what is open, its state and its tag, and stays the only list of open
work; this page keeps the narrative the row carried, verbatim as it stood on the board on 2026-11-19
(`INFO-RESTRUCTURE-1` W3). Close or narrow the board row first, then trim its section here.

## Open rows

### Bundle / Exchange

- **Bundle / Exchange — "load as draft" residuals** — ✅ **every slice SHIPPED 2026-09-25** (all eight calls answered that day): **Import as draft…** beside **Import…** in the Dashboard, Widget, Dataset, authored Pipeline, Link Analysis view and Geo map view editors — prerequisites write-through imported first, the target held unsaved in memory under a shared banner, Save through the editor's own route (`If-Match` + a diff for an existing id), advisory integrity findings from `POST /bundle/preview` re-checked just before Save. As-built: `okf/product/module/processor/spec-metadata-bundle.md`; design archived. Re-ranked P2 → P3. ✅ 2026-10-04: `/bundle/preview` and `/bundle/import` now judge an LA/Geo view's `query.projection.datasetId`, so those banners show real findings. Left: ~~Pipeline drafts still read "not checked"~~ ✅ 2026-10-04 (operator: the preview runs full validation — `/bundle/preview` runs the graph Save's Validate + save gate on a `pipeline` item, writing nothing; the banner shows the findings); ~~LA/Geo Save runs no re-check (`LaTransfer` has no preview seam)~~ ✅ 2026-10-06 (operator, 2026-10-06: add the re-check — `LaTransfer.recheck`, wired to `BundleTransferService.draftIntegrity`; LA and Geo *Save draft* re-run the preview over the content about to be written and show the findings in the banner and toast); a browser pass over the six editors. **2026-10-18 (re-verified, decided under operator delegation):** kept — the browser pass is the only item left; it needs no decision and no outside trigger, just a live session over a built bundle, so it stays open rather than retiring. → `okf/product/module/processor/spec-metadata-bundle.md`
