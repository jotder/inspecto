---
type: How-to
title: "Add a file"
description: "Turn one CSV, TSV, Parquet, JSON or NDJSON file into a new Dataset with Add data — choose, check the columns, say what they hold, create and run — and add later files to it."
item: layer.product
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [inspecto-ui/src/app/inspecto/add-data, inspecto/src/main/java/com/gamma/control/OnboardRoutes.java]
---

# Add a file

## Goal

Get one file into a Space as a **Dataset** you can query, check and chart — without writing a Pipeline.

## Before you start

- **Edition:** every edition.
- **Who:** a person allowed to upload data (`canUploadData`), in a Space where adding data is turned on. It is on by
  default on a server without sign-in and off by default with sign-in; an administrator turns it on.
- **What you need:** a CSV or TSV, Parquet, JSON or NDJSON file.

Concept: [Dataset](user-guide-concept-dataset.md).

## Steps

1. Open **Add data**: on **Home**, **Getting started → Add data** while the Space has no Dataset yet; on
   **Platform → Catalog → Datasets** while it is empty, the **Add data** button; or drop the file on Home, the Catalog
   or the empty Datasets pane. The screen is titled *Onboard a file*.
2. **1. Choose the data** — say whether each row *links two things* (events between entities) or *describes one
   thing* (details about entities), choose **A file**, then **Choose a file** (or drop it on the box).
3. **2. Check the columns** — the columns and a few rows as read. Optionally **Keep only some rows**.
4. **3. What does each column hold?** — open it to confirm types and which column is the time of each row (a Dataset
   needs a time column for Reconciliation and real-time checks later).
5. **4. Create and run** — give the **Dataset name** and click **Create and run**. The screen says
   *<name> is ready*.

**Later files** for the same Dataset: **Platform → Catalog → Datasets**, open the Dataset's card, **Add a file**, then
**Run now**.

## Check it worked

The Dataset holds the file's rows (here a Dataset named `orders`). Its **Arrivals** list on the Dataset's page shows
the file as an upload.

```check
GET /spaces/{space}/datasets/orders/rows?limit=5
as builder
expect status 200
expect data.statistics.rowCount > 0
expect data.columns.length > 0
```

## What can go wrong

- *Adding data is not available here* — the Space has adding data turned off (`Add data is turned off in this Space;
  an administrator can turn it on`), or you lack the right to upload data.
- `nothing was imported from '<file>': … nothing was kept - check the column types and formats and try again` — a
  column's type or format does not fit; fix it in step 3. A failed run leaves nothing behind.
- The name is taken (409) or has characters other than letters, digits, spaces, `.`, `_` and `-`.
- The file is empty, or larger than the server allows (413).
- There is no menu entry for **Add data** once the Space has a Dataset: open it from an empty Datasets pane, by
  dropping a file on Home or the Catalog, or by its address `/add-data`.

## Next

- [Watch a folder](../../module/elt/user-guide-howto-watch-a-folder.md)
- [Add an Expectation](../../module/elt/user-guide-howto-add-an-expectation.md)
- [Tutorial 1 — your first Space and Dataset](../../module/config/user-guide-tutorial-first-space.md)
