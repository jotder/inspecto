---
type: How-to
title: "Watch a folder"
description: "Have files that land in a server folder picked up automatically — the Watch this folder switch of an Add data Dataset, or a Pipeline's Collector polling its inbox."
item: module.elt
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [platform/inspecto-etl, inspecto-ui/src/app/inspecto/add-data]
---

# Watch a folder

## Goal

Stop adding files by hand: let Inspecto pick up every file that lands in a folder on the server and add its rows to a
Dataset.

## Before you start

- **Edition:** every edition.
- **Who:** for an Add data Dataset, a person allowed to upload data (`canUploadData`); for a Pipeline, a builder
  (`canAuthorWorkbench`) who may also run it (`canOperateRuns`).
- **What you need:** a Dataset made with Add data ([Add a file](../../layer/product/user-guide-howto-add-a-file.md)),
  or a Pipeline; and whoever delivers the files must be able to write to a folder on the **server**.

Concept: [Connection and Collector](user-guide-concept-connection-collector.md).

## Steps

### A Dataset made with Add data

1. Open **Platform → Catalog → Datasets** and the Dataset's card. Its **Inbox** section shows the server folder and how
   many files are waiting.
2. Switch on **Watch this folder**. From now on, once a file in that folder has stopped changing, it is imported —
   checked every 30 seconds. **Import waiting files** imports what is there now.

### A Pipeline

1. Open **Platform → Workbench → Pipelines**, open the Pipeline and click **Edit**.
2. Double-click its Collector Step. Under **Collect from** choose **Local inbox**. Optionally set **Include patterns**,
   **Discovery** (**Poll**, or **Watch (filesystem events)**), a **Stability window** (how long a file must stay
   unchanged) and a **Ready marker** (a file whose arrival says the rest is complete). Click **Apply**.
3. **Save pipeline**, then **Activate**: *The collector starts picking up files on its next poll cycle.* The inbox is
   the Pipeline's `data/inbox/<pipeline>` folder unless its configuration says otherwise.

## Check it worked

On the Dataset's card the switch stays on and the waiting count drops to zero within a minute of a file landing; the
Dataset's arrivals list the file as coming from the system, not an upload. For a Pipeline,
**Platform → Workbench → Collectors** lists its Collector with the last poll time and poll count:

```check
GET /spaces/{space}/collectors
as builder
expect status 200
```

## What can go wrong

- *The last import of this folder failed: <reason>* on the card — open the reason; often a column type no longer
  fits.
- `'<id>' is already kept up to date on a schedule; stop that first to watch the folder instead` — the Dataset already
  has a scheduled feed.
- A Reference, or a Postgres snapshot, cannot be watched: a Reference is replaced with **Add a file**, a snapshot is
  refreshed with **Refresh now**.
- A Pipeline whose inbox lies inside its own output folders is refused at start-up.
- Files that are still being written are skipped until they stop changing — set a ready marker if the sender writes
  slowly.
- The Pipeline editor has no field for the inbox folder itself, and the Collectors page cannot create a Collector:
  change the folder in the Pipeline's configuration.

## Next

- [Connect SFTP or a database](../connectors/user-guide-howto-connect-sftp-or-a-database.md)
- [Handle quarantined rows](user-guide-howto-handle-quarantined-rows.md)
