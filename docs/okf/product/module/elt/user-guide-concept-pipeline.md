---
type: Concept
title: Pipeline and Steps
description: What a Pipeline is — a chain of Steps that reads incoming data and writes clean Datasets — what a Step and a Run are, and how a Pipeline differs from a Job.
item: module.elt
audience: external
---

# Pipeline and Steps

## What it is

A **Pipeline** is a named chain of **Steps** that turns incoming data into clean, queryable
**[Datasets](../../layer/product/user-guide-concept-dataset.md)**. Each Step does one thing to the records and
hands them to the next.

```
  collect ─▶ parse ─▶ dedup ─▶ transform ─▶ summarize ─▶ sink
  fetch the  read the  drop     shape,       add up      write the
  files      format    repeats  join, enrich             Dataset
                                    │
                         records that fail go to quarantine,
                         with the reason, instead of being lost
```

The Step kinds you work with:

- **collect** — take what a [Collector](user-guide-concept-connection-collector.md) fetched.
- **parse** — read a file format: delimited text (CSV), fixed-width or pattern-matched text, JSON, Excel, Parquet,
  and, for telecom data, ASN.1.
- **dedup** — drop records already seen.
- **transform** — filter rows, compute new columns, join or look up other Datasets.
- **summarize** — aggregate records, for example into daily totals.
- **sink** — write the result.

A Pipeline can also branch, sending different records down different Steps.

## Why it exists

Raw files are messy: different formats, repeated records, wrong values. A Pipeline writes down, once, how to read and
clean them, so every file is treated the same way every time, and every record that is set aside is kept with its
reason.

## When it runs, and what a Run is

A Pipeline runs on its **trigger**: on a schedule, when an event arrives, after another Pipeline finishes, or by hand
with **Run now**. Each execution is a **Run**. A Run lists the files it processed and the outcome of each:
committed, quarantined or failed.

## How it relates to its neighbours

- A **[Collector](user-guide-concept-connection-collector.md)** brings files in; the Pipeline reads them.
- The Pipeline writes **[Datasets](../../layer/product/user-guide-concept-dataset.md)**.
- **[Expectations](user-guide-concept-expectation.md)** check the data it writes.
- A **Decision Rule** routes records inside a Pipeline, for example sending one event type to its own Dataset. See
  [Alert Rule, Expectation and Decision Rule](../../addon/incidents/user-guide-concept-alert-rule.md).
- A **Job** is not a Step. A Job works on data already stored — an enrichment, a report, a maintenance task — and
  can be set to run after a Pipeline finishes.

## What you do with it in the console

- **Pipelines** (in **Platform → Workbench**): see each Pipeline's state and last run, **Run now**, open its history,
  and open the **editor**. In the editor you place Steps on a canvas, connect them, configure each one, and **Test**
  a single Step against sample data.
- **Runs**: see every Run of every Pipeline and Job, and drill into which files succeeded, were quarantined or
  failed.
- **Operations → Processing Status**: one row per Pipeline, with committed and quarantined counts side by side.

## Which edition has it

Pipelines are in every edition, and Basic runs any Pipeline you bring. In the Basic editor, the Steps that compute,
join, enrich or summarise are locked; filtering rows and removing duplicates stay available. A Pipeline step that
masks personal data is Advanced only, and the ASN.1 and telecom decoders come with the Telecom industry pack in
Advanced. See [The editions on one page](../../../../EDITIONS.md).

## Read next

- [Connection and Collector](user-guide-concept-connection-collector.md)
- [Expectation](user-guide-concept-expectation.md)
- [Dataset](../../layer/product/user-guide-concept-dataset.md)
