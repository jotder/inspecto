---
type: How-to
title: "Explore links between entities"
description: "Start a Link Analysis graph from one entity, a list or a whole Dataset, expand it degree by degree, leave out what you do not need, and save the view."
item: module.la-core
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [la/inspecto-la-core, inspecto-ui/src/app/modules/admin/studio]
---

# Explore links between entities

## Goal

See who is connected to whom — accounts that share a device, callers who ring the same numbers — as a graph you can
expand, and keep the view.

## Before you start

- **Edition:** every edition has Link Analysis: draw, expand, find paths and neighbours, save views, Entity Lists.
  Investigations, Drafts, the Dossier, the Geo map and entity pages need Standard or higher.
- **Who:** anyone who may see the Dataset; saving a view needs `canAuthorWorkbench`.
- **What you need:** a Dataset whose rows link two things (an *events between entities* Dataset — for example made
  with [Add a file](../../layer/product/user-guide-howto-add-a-file.md)).

Concept: [Link Analysis and Investigations](user-guide-concept-link-analysis.md).

## Steps

1. Open **Platform → Studio → Link Analysis**.
2. In the start form (*Start a graph* — on the **Investigation** tab on Standard and up, on the start tab on Basic)
   choose **Start from**: one entity, an Entity List, pasted values or a whole Dataset.
3. Pick the **Dataset** and the **Entity Type**, and type the first letters of the value — the suggestions show how
   busy each match is.
4. Click **Start** (on Basic the button reads **Draw**). Very busy entities are held back rather than drawn, so the
   graph stays readable.
5. **Expand** the graph one or more degrees from what is selected. Right-click a node and **Exclude…** to leave it out
   of further expansion.
6. Keep it: **File → Save view as**, give a **Name** and a description, **Save view**. **File → Open…** brings it back.
   A saved view reads the data again when opened — it is not frozen evidence; on Standard and up an Investigation keeps
   evidence.

## Check it worked

The Dataset you start from can be read by Link Analysis (here the worked example's `payments`):

```check
GET /spaces/{space}/inv/datasets/payments/discover
as builder
expect status 200
```

## What can go wrong

- The Dataset is not offered, or answers 404 — you may not see it, or it has no columns linking two entities.
- A typed value longer than the Space allows is refused (422).
- **Save view as** is disabled until a graph is drawn.
- In a Space that masks data, entities show as aliases.
- On Basic, Investigations and the Geo map are not offered.

## Next

- [Screen names against an Entity List](../screening/user-guide-howto-screen-names.md)
- [Open and work a Case](../case-management/user-guide-howto-open-and-work-a-case.md)
