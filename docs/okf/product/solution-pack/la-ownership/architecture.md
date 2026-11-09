---
type: Architecture
title: "Link Analysis ownership showcase — architecture"
description: "How the la-ownership template is built: seven feeds, seven Pipelines, one link Job with an engine-side fuzzy name match, four saved views, and how its stories are generated and pinned."
tags: [la-ownership, architecture]
timestamp: 2026-11-07T00:00:00Z
---

# Link Analysis ownership showcase — architecture

Template: `spaces/_templates/la-ownership/` (`minEdition: basic`, listed in `offerings/basic.toon`). Basic tier only:
no personas, roles, Alert Rules or seed script; the Advanced tier is open work.

## Data flow

```
data/inbox/<feed>/*.csv  --7 Collectors + Pipelines-->  data/<feed>/database (Parquet)  --Datasets <feed>_dataset
                                                                  |
                                       ownership_links Job (sql.template, on every pipeline.commit)
                                                                  v
                                  data/ownership_links (ONE link Dataset)  -->  4 saved views (entity-projection)
```

| Feed | Rows | Columns |
|---|---|---|
| `company` | 6 000 | company_id, name, jurisdiction, incorporated_at, reg_address |
| `person` | 5 000 | person_id, name, birth_year (VARCHAR), nationality |
| `ownership` | 8 807 | owner_id (PER- or CO-), owned_id, percent |
| `officer` | 8 085 | company_id, person_id, role |
| `customer` | 1 500 (+8 staged) | customer_id, company_id, onboarded_at |
| `payment` | 40 061 in 12 monthly files (+1 staged month) | payment_id, from_customer, to_company, amount, paid_at |
| `sanctions_list` | 400, invented | list_id, name, aliases (`|`-separated), birth_year, nationality, listed_at |

## The link Job

`config/jobs/ownership_links_job.toon` writes `ownership_links` (src, dst, kind, ts, amount, percent, score,
event_date, via), one row per pair and kind: `owns`, `officer_of`, `customer_is`, `paid` and `name_match`. `ts` is the
owned company's incorporation date (`owns`, `officer_of`), the onboarding date, the first payment, or the listing
date. Ids carry their type (`PER-`, `CO-`, `CUS-`, `SL-`); because an endpoint column holds ONE Entity Type, both
`src` and `dst` are classified `PARTY` and `config/link-analysis.toon` declares one unmasked Entity Type `party` (Basic
shows ids unmasked anyway). Without a claimed classification the preset queries (Reaching many, Short loops) are not
offered for the Dataset.

**Fuzzy name match.** The pipeline `similarity` step scores two columns of the same row, so it cannot match a register
against a list. The Job therefore calls the engine's DuckDB function `jaro_winkler_similarity` itself, the one that step
uses: names and every alias are lower-cased and cut to letters and single spaces, people and list entries are joined on
the same birth year and nationality, and a pair scoring at least the Job parameter `min_name_score` (0.90) becomes a
`name_match` with the best score and the listed name it matched. A blank birth year or nationality never matches.
The join on birth year and nationality keeps the comparison small (about one list entry per person) and is what keeps
the near-namesake out.

## Stories and how they are pinned

`tools/gen-la-ownership-data.py` (standard library, seed 20261107, byte-identical reruns) builds the background
register first (heavy-tailed ownership and directorships, acyclic company ownership, directorships capped at 12, 5 %
blank optional fields, 24 people registered twice under a one-vowel variant) and then plants four stories with a
look-alike each. It re-runs the name-match rule in Python and refuses to write if anything other than the planted pair
matches. It writes `tools/gen-la-ownership-data.ground-truth.json` (story → entities, look-alikes, facts), which is
not part of the data download.

| Check | What it pins |
|---|---|
| `tools/gen-la-ownership-data.test.mjs` | byte-identical reruns; plan scale; each story's facts read from the CSV files; no look-alike shares a record with its story |
| `OwnershipLinksJobGoldenTest` (`platform/inspecto-engine`) | the real Pipelines land every row with nothing rejected; the Job's link Dataset has the contract columns and five kinds; exactly one `name_match`; the 24 % chain and the 61 %; the only ownership cycle; every story one connected group; no look-alike linked to its story |

## References

[User guide](user-guide.md) · [Install](admin-guide-install.md) · plan: `docs/archived-documents/plans-archive/2026-11-07-la-showcase-police-insurance-utility-plan.md` spec 5
