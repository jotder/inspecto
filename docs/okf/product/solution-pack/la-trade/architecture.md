---
type: Architecture
title: "Link Analysis trade showcase — architecture"
description: "How the la-trade template is built: seven feeds, seven Pipelines, one link Job that checks every bill of lading against its vessel's daily position, four derived view Jobs, four saved views, and how its stories are generated and pinned."
tags: [la-trade, architecture]
timestamp: 2026-11-07T00:00:00Z
---

# Link Analysis trade showcase — architecture

Template: `spaces/_templates/la-trade/` (`minEdition: basic`, listed in `offerings/basic.toon`). Basic tier only:
no personas, roles, Alert Rules or seed script; the Advanced tier is open work.

## Data flow

```
data/inbox/<feed>/*.csv  --7 Collectors + Pipelines-->  data/<feed>/database (Parquet)  --Datasets <feed>_dataset
                                                                  |
                         trade_links Job (sql.template, on every pipeline.commit and every 5 minutes)
                                                                  v
                    data/trade_links (ONE link Dataset)  --job.dataset.produced-->  4 view Jobs
                                                                                       v
                                  data/trade_<view>_links (one per saved view)  -->  4 saved views (entity-projection)
```

| Feed | Rows landed | Columns |
|---|---|---|
| `company` | 2 500 | company_id, name, country, reg_address, incorporated_at, account_id |
| `director` | 3 514 | company_id, person_id, person_name |
| `trade` | 11 896 in 12 monthly files (+1 staged) | trade_id, instrument (LC, collection), applicant, beneficiary, hs_code (VARCHAR, keeps its leading zero), qty, unit_price, value, port_load, port_discharge, vessel, ship_date |
| `bill_of_lading` | 11 955 (+1 staged) | bl_id, trade_id, vessel, container, shipper, consignee, port_from, port_to, bl_date |
| `vessel_position` | 109 500 = 300 vessels × 365 days (+1 staged) | vessel, pos_date, port_or_area (a port, or `At sea - <area>`) |
| `price_reference` | 744 = 62 HS codes × 12 months (+1 staged) | hs_code, month (`YYYY-MM`), median_unit_price |
| `payment` | 28 993 (+1 staged) | payment_id, from_account, to_account, amount, paid_at, trade_ref |

## The link Job

`config/jobs/trade_links_job.toon` writes `trade_links` (src, dst, kind, ts, amount, event_date, via, flag), one row per
pair and kind, about 50 000 rows: `traded_with` (exporter → importer), `on_vessel` (shipper → vessel), `shipped_in`
(shipper → container), `directed_by` (company → person), `registered_at` (company → upper-cased address) and `paid`
(account owner → account owner, through `company.account_id`). Parameters: `price_ratio_alert` 3.0,
`min_relays` 2, `min_box_shippers` 6, `min_shared_address` 5. Because an endpoint column holds ONE Entity Type, `src`
and `dst` are both classified `TRADE_ENTITY` and `config/link-analysis.toon` declares one unmasked Entity Type
`trade_entity`, so the Basic preset queries are offered for the Dataset.

Every red flag is computed by the engine's own SQL; the generator precomputes none of them (it only re-checks them,
see below):

- **Vessel check (`vessel_elsewhere`).** Each B/L is LEFT JOINed to `vessel_position` on `vessel` and
  `pos_date = bl_date`; a B/L whose vessel was in another port or at sea is flagged. A B/L with no position row for its
  day is not flagged (no evidence). Checking each B/L against its own vessel is what lets a transhipment's two legs
  pass, where a check of the trade's named vessel on the ship date would flag them.
- **Price (`price_over_reference`).** `unit_price` divided by the reference median of the same HS code and ship month.
- **Boxes (`relayed`, `many_shippers`).** Per container, B/Ls ordered by `bl_date, bl_id`; a leg whose shipper is the
  previous leg's consignee is a relay (`lag` window).
- **Addresses (`shared_address`).** Companies per upper-cased, trimmed registered address.

**Saved views draw their whole Dataset**, and the canvas shows at most 1 000 nodes (`projection_node_cap`) from at most
4 000 rows (`traversal.rows_default`). Each view therefore reads its own Dataset, derived from `trade_links` by a
`sql.template` Job fired on `job.dataset.produced` (as `la-police` does): over-invoicing 23 rows, phantom shipment 90,
circular trade 102, shell importers 199.

**Two triggers on the link Job.** A `pipeline.commit` run can be skipped while the previous run is still in flight
(`JOB-SIGNAL-OVERLAP-SKIP-1`), which would leave the last landed file out of the link Dataset; the 5-minute cron catches
up.

**SQL guard gotcha.** The `sql.template` guard scans the statement lexically: the word `from` inside a string literal
(first draft: `' from ' || b.port_from`) is read as a FROM clause naming a file, and the Job is refused. The `via` text
avoids the word.

## Stories and how they are pinned

`tools/gen-la-trade-data.py` (standard library, seed 20261107, byte-identical reruns, about 5 seconds) builds the world
first: 25 invented ports in 24 real countries, 30 liner services (four fixed so the stories have a route), 10 vessels
per service sailing their loop day by day from July 2025, so every genuine B/L is dated on a day its vessel is in its
port of loading. It then builds the background (heavy-tailed trade relationships, 27 transhipments through a hub, boxes
reused at most 4 times and never back to a former consignee, 4 % blank addresses and collection vessels, 20 people
registered twice under a spelling variant) and plants four stories with a look-alike each. Company and person ids are
shuffled, trade and B/L ids follow the date, so a story is not visible in the numbering. Before writing, it re-runs
every red-flag rule on its own corpus and refuses to write if a flag fires anywhere but on the planted records. It writes
`tools/gen-la-trade-data.ground-truth.json` (story → entities, look-alikes, facts), which is not part of the data download.

| Check | What it pins |
|---|---|
| `tools/gen-la-trade-data.test.mjs` | byte-identical reruns; plan scale; each story's facts read from the CSV files, including the vessel check against `vessel_position` and the trade-level check that would wrongly flag the transhipments; no look-alike shares a record with its story |
| `TradeLinksJobGoldenTest` (`platform/inspecto-engine`) | the real Pipelines land every row with nothing rejected; the Job's link Dataset has the contract columns and six kinds; each flag on exactly the planted records; the derived Jobs fire on the link Job's signal; every story one connected group in the link Dataset and in its view; each view under 1 000 nodes and 4 000 rows and showing its look-alikes; no look-alike linked to its story. A look-alike made to trade with the story, and the position join shifted by one day, both turn it red |

## Deviations from the plan's spec 6

- **Trades are not nodes.** `shipped_in` and `on_vessel` run from the shipper (the exporter), not from the trade:
  trade nodes would need a seventh kind to reach any company and would push the link rows past 60 000. The trade and B/L
  ids are in `via` and in the feed Datasets.
- **62 HS codes, not 400**, so every code is a real one; the prices are invented.
- **Columns added:** `company.account_id` (to resolve `payment` accounts to companies), `trade.instrument`,
  `payment.payment_id` and `payment.trade_ref`. Date columns are named `ship_date`, `bl_date`, `pos_date`.
- **`flag` column** on the link Dataset, holding the red flags (a comma list on `traded_with`).

## References

[User guide](user-guide.md) · [Install](admin-guide-install.md) · plan: `docs/superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md` spec 6
