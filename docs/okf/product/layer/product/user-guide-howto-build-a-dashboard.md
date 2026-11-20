---
type: How-to
title: "Build a Dashboard"
description: "Save Widgets over a Dataset or saved Query in the Viz Library, arrange them on a Dashboard in the Dashboard Builder, share a link and put the Dashboard in a menu."
item: layer.product
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [inspecto-ui/src/app/modules/admin/studio, platform/inspecto-analytics]
---

# Build a Dashboard

## Goal

Put the numbers a business user needs on one page: a **Dashboard** of **Widgets**, each a chart or a figure over your
data.

## Before you start

- **Edition:** every edition. Link Analysis and Geo map Widgets on a Dashboard need Standard or higher.
- **Who:** a builder (`canAuthorWorkbench`).
- **What you need:** a Dataset, or a saved Query (**Platform → Studio → Query Library**), with the figures to show.

Concept: [Widget and Dashboard](user-guide-concept-widget-dashboard.md).

## Steps

1. **Make each Widget.** Open **Platform → Studio → Viz Library** and click **New widget**. In the *Widget Builder*
   pick the **Dataset** (or a **Saved query**), choose a chart from the **Visualization** buttons, and fill its
   **Fields** — what goes on each axis and how it is aggregated.
2. **Save widget**: a **Widget id** (letters, digits, `.`, `_`, `-`), a description, **Save**.
3. **Make the Dashboard.** Open **Platform → Studio → Dashboard Builder** and click **New dashboard**. Give the
   **Dashboard id**, then **Add widget** for each Widget. Drag tiles to reorder them, **Change width**, **Remove
   tile**. Optionally a description, an *as of* date, a date field with a default range, and the fields offered as
   quick filters.
4. **Save dashboard.** (A shortcut: in the Viz Library, a Widget's **Add to dashboard** adds it to an existing one.)
5. **Share** (while editing): **Share** opens a **Share link** with **Copy link** — a read-only, masked view for
   people without an account, if the server allows sharing.
6. **Put it in a menu** for business users: **Platform → Studio → Menus**, add a menu and add the Dashboard as a
   report ([Tutorial 4](user-guide-tutorial-dashboard.md) does this).

## Check it worked

The Dashboard holds its tiles — here the worked example's `o2c_overview`, with three:

```check
GET /spaces/{space}/components/dashboard/o2c_overview
as builder
expect status 200
expect data.content.tiles.length = 3
expect data.content.tiles[*].widgetId has o2c_unmatched_total
```

## What can go wrong

- *Add at least one widget.* — a Dashboard needs a tile before it saves.
- *A dashboard with this id already exists.* / *A widget with this id already exists.* — choose another id, or open
  the existing one.
- **Save widget** stays disabled until a Dataset and a Visualization are chosen.
- *Dashboard saving is disabled on this server (no write root configured).*
- *Sharing is disabled on this server. Set -Dbi.share.secret (≥ 16 chars) to enable public links.* — an operator turns
  sharing on.

## Next

- [Schedule a report](../../module/analytics/user-guide-howto-schedule-a-report.md)
- [Tutorial 4 — present it](user-guide-tutorial-dashboard.md)
