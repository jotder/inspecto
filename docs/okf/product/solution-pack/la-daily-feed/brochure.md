# Link Analysis daily feed — brochure

> **A ready-made, monitored daily feed for Link Analysis — one file per day in, an up-to-date link index out.**

*Audience: customers, presales and sales. Product Map Item `solution-pack.la-daily-feed` · status **ga**. Prices are not published here.*

## Who it is for

Teams putting a daily telecom (or similar) record feed behind Link Analysis, from a file drop or a nightly database export.

## The problems it solves

- A day is missing, half-delivered or doubled and the graph is silently wrong.
- The link index falls behind the files that have landed.

## What is included

- A Pipeline with a typed schema, partitioned by event date.
- A poll Collector matching one file per day, with duplicate markers and quarantine for refused files.
- A Dataset.
- A freshness Alert Rule, an index-lag Alert Rule and a daily row-count Expectation.
- Jobs that append each landed day to the Link Analysis index and run the row-count check after every file.

## How it fits with the Editions

Ships with every Edition from [Basic](../../edition/basic/brochure.md) up. The link index and daily ingestion are Link Analysis features of [Standard](../../edition/standard/brochure.md) and above.

## How it works

Rename the feed, columns and file pattern to match your feed and keep the partition on the event-date column. Each day's file is collected, validated, partitioned and indexed; the three monitors tell you when a day is late, short or not yet indexed.

## Next step

Bring a week of your daily extract and we will stand the feed up with you.

## Read more

- Ingestion contract: [ETL ingestion plan](../../module/etl/plan-ingestion.md)
- The template manifest is `spaces/_templates/la-daily-feed/template.toon`.
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md) · [enterprise whitepaper](../../layer/product/brochure-enterprise-whitepaper.md) · [competitive landscape](../../layer/product/brochure-competitive-landscape.md)
