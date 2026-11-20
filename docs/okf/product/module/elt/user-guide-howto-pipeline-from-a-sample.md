---
type: How-to
title: "Build a Pipeline from a sample file"
description: "Create a Pipeline in the Pipeline editor, let a sample file propose its Schema, check and save it, try it with a dry run and activate it."
item: module.elt
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [platform/inspecto-etl, inspecto-ui/src/app/modules/admin/pipelines]
---

# Build a Pipeline from a sample file

## Goal

Build a **Pipeline** for a file format you receive regularly, with a **Schema** derived from a real sample instead of
typed by hand.

## Before you start

- **Edition:** every edition. Steps that compute, join or enrich are locked on Basic; reading and typing the file is
  not.
- **Who:** a builder (`canAuthorWorkbench`).
- **What you need:** a sample file of the format — a few hundred rows are enough. The sample is read in your
  browser; it is not stored on the server.

Concept: [Pipeline and Steps](user-guide-concept-pipeline.md).

## Steps

1. Open **Platform → Workbench → Pipelines**. The editor opens read-only: click **Edit**.
2. Click **+** (*New pipeline*), enter the **New pipeline name**, choose the **Parse format** — Delimited (CSV-like),
   Fixed-width, JSON, Text (regex), Excel or ASN.1 — and click **Create**. The Pipeline is saved inactive.
3. Double-click the **Parse** Step. In its **Sample** panel, upload the sample file (or paste its text and click
   **Use pasted sample**), then click **Parse sample**.
4. Under *How values are understood*, click **Detect column types** and review the table of columns that come out:
   names, types and formats.
5. Click **Apply** — the Schema is written — then **Save pipeline** in the toolbar.
6. Try it: **Validate**, then **Dry-run** and **Run dry-run** with a few sample rows. A dry run writes nothing.
7. When it is right, **Activate**: *The collector starts picking up files on its next poll cycle.* Files dropped in its
   inbox now land in its Dataset ([Watch a folder](user-guide-howto-watch-a-folder.md)).

Alternatively, **Platform → Catalog → Onboard Stream** walks through the same stages (Collect, Parse, Schema, Enrich,
Publish) and opens the editor with a draft.

## Check it worked

The Pipeline is listed, and its graph has a parse Step — here the worked example's `orders` Pipeline (shipped with
the template; a Pipeline you build reads the same way, its parse Step typed by its format, such as
`parser.delimited`):

```check
GET /spaces/{space}/pipelines
as builder
expect status 200
expect data[*].name has orders
```

```check
GET /spaces/{space}/pipelines/orders/graph/raw
as builder
expect status 200
expect data.nodes[*].type has parser
```

## What can go wrong

- *Choose the format this pipeline reads* — pick a **Parse format** before **Create**.
- *A pipeline called that already exists — pick another name.*
- **Apply** refuses to overwrite a saved Schema that has columns this sample no longer produces — choose **Replace the
  output schema** if that is what you mean.
- *This sample no longer matches the saved schema…* — **Add N new** columns, or **Re-derive from this sample**.
- *Fix the errors below before activating.* — **Activate** is refused while the Pipeline has errors.
- **Validate** checks in the browser; the server's own checks run on **Save pipeline** and can still refuse it.

## Next

- [Add an Expectation](user-guide-howto-add-an-expectation.md)
- [Change a Pipeline safely](user-guide-howto-change-a-pipeline-safely.md)
