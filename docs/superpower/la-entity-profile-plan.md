# Link Analysis: Entity profile pages (what the data says about each Entity Type and each Entity)

**Status: PLAN 2026-10-15, nothing built. Decisions D-EP1..D-EP8 open (section 7).** Generic for every Space and every
industry pack: nothing here names a telecom column. The model for the content is the "What the data is" section of the
IMS showcase plan ([`la-ims-showcase-plan.md`](la-ims-showcase-plan.md) section 1): volumes, distinct values, value
shapes, sentinel values, time span, freshness, where the rows come from, how the identifiers relate, and what the
data does NOT contain. That section was written by hand with ad-hoc SQL; this plan makes the platform produce it, for
any **Entity Type**, from the Space's own Datasets.

When this ships: distil the as-built facts into `okf/frontend/features/link-analysis.md` and
`okf/backend/modules/link-analysis.md`, move open items to `BACKLOG.md`, and `git mv` this plan to
`archived-documents/plans-archive/`.

## 1. The problem

An analyst opening Link Analysis sees a graph of identifiers but cannot answer the questions an investigator asks
before trusting it:

- How many subscribers, devices, accounts are there at all, and in which Datasets?
- How complete are they (fill rate), and how many values are junk (blank, `-99`, `0`, a default device id)?
- What do the values look like (length, prefix, country code) and do they normalise cleanly?
- Which days are covered, how fresh is the newest row, what is still waiting for the index?
- Which Pipelines, Collectors and Files feed them?
- How do the identifiers relate (one device per subscriber, or one device shared by 15)?
- For one Entity: when was it first and last seen, on how many days, in which Datasets, how connected is it compared
  with its peers?

Today none of this has a home. The data-trust strip answers freshness and Files for **one Dataset inside one
Investigation**; nothing answers it per Entity Type or per Entity.

## 2. What exists today (surveyed 2026-10-15, reused, not rebuilt)

| Need | Already there | Gap |
|---|---|---|
| Entity Types | `EntityTypes` (`platform/inspecto-entity-store`): id, label, normaliser, `masked`, the column `classification` values it claims; `GET /settings/link-analysis` returns them | no counts, no bindings, no route of its own |
| Type to column binding | a Dataset column's `classification` maps to exactly one Entity Type (each classification claimed once) | not resolved anywhere as "where does this type appear" |
| Data as of | `GET /datasets/{id}/freshness` (`dataAsOf` from the Dataset's `eventDate:`), cached per input-file stamp in `DatasetMeasureProbe` | per Dataset only; needs a write root |
| Files, rows per day, ingest time, index state | `GET /inv/datasets/{id}/files` (`summary`, `days[]`, per-File index state, `assessed:false` without evidence) | per Dataset only |
| Coverage (missing days, per Collector) | `GET /inv/investigations/{id}/coverage` | per Investigation and needs a bounded window |
| Column statistics | `POST /inv/schema/overlap-profile` computes `rows, distinct, nulls` per column (approximate distinct) | private `ColumnStats` record, pairwise-oriented, no time, no shapes |
| Lineage | `GET /lineage?store=` (upstream Pipeline / Consignment / input File / rows), `/catalog/graph` | keyed by store, not Dataset |
| Link index | `IndexManifest`: version, `builtAt`, mapping, edge and node table rows, input files; `GET /inv/index` | no per-kind counts, no degree distribution, no time span, no per-type node counts |
| One Entity | `POST /inv/projection/neighbors` (one hop), `/entity-lists/{id}/match`, `/inv/entity-identities/group`, `POST /inv/case-history`, node dialog (Risk, Enrichment, Case history) | no first/last seen, no activity timeline, no standalone page |
| UI pieces | data strip drawer (`link-analysis-data-strip.component.ts`, `data-trust.ts` with `assessed` wording), `inspecto-stat-tile`, `kpi-trend`, heatmap plugin, `inspecto-data-table`, pen rows and collapsible two-column groups (shipped 2026-10-15) | no entity route, no histogram component |

Two defects this plan depends on fixing first:

- **Entity List membership never matches a masked node** (IMS showcase plan, on-screen rehearsal finding 1): the list
  aliases a value differently from the exploration alias. An Entity page under `typed` masking would show "on no
  list" for a listed Entity.
- **The freshness route refuses without a write root** (`WriteGates.requireWriteRoot`): a read-only deployment would
  show no "data as of". Decide whether a read needs it (D-EP7).

## 3. The two pages

### 3.1 Entities (one card per Entity Type) and the Entity Type page

The list page shows one card per Entity Type in force: label, distinct Entities (approximate), Datasets it appears in,
data as of, a freshness badge. Opening a card shows the **Entity Type page**, sections as collapsible groups (the
shipped pattern), each a two-column grid of stat tiles and small tables:

| Section | Content | Computed from |
|---|---|---|
| **Identity** | label, id, normaliser, masked or not, the classifications it claims | Entity Type config |
| **Where it appears** | every Dataset column bound to the type (role: link end, attribute, time-only), rows, fill rate, distinct per column | Dataset registry + profile query |
| **Lineage** | per Dataset: Pipelines, Collectors, File count, latest ingest, rows; Files waiting for the index; rejected rows reported by the Pipelines (the `file_problem` signal) | `/inv/datasets/{id}/files`, `/lineage`, batch ledger |
| **Volume and quality** | distinct Entities across all bindings (approximate) and per binding; fill rate; values that fail the normaliser; **sentinel values** found (blank, `-99`, `0`, `NULL`, `unknown`, any single value above a share threshold) with their share; duplicate Files (same content, different name) | profile query |
| **Value shapes** | length histogram, leading-character histogram (first N characters, N set per normaliser, e.g. a dialling prefix for `e164`), digits vs letters; under masking: shapes only, never values | profile query |
| **Time** | earliest and latest event, data as of, rows and distinct Entities per day (calendar heatmap), missing days, corrected days | profile query + `/freshness` + Files `days[]` |
| **Links** | link kinds the type takes part in with counts, degree distribution (p50, p90, p99, max), number of hubs above the hub threshold (counts only), share of Entities with exactly one link | link index (extended, section 4.2) or Dataset query |
| **Relationships** | for each other Entity Type sharing a row: the cardinality pattern ("1 device per subscriber for 99.9 %, 6 devices carry 6 or more subscribers"), the worst offenders as counts | profile query over co-bound columns |
| **What is not here** | Entity Types claimed by no column, bindings with no time column, Datasets with no freshness column, reads not assessed | derived; says `not assessed`, never "none" |

### 3.2 The Entity page (one Entity)

Reached from the node dialog ("Open the Entity page"), from an Entity List row, or by typing a value. Shows the
Entity's key (an alias under masking, with the audited Reveal for a caller who may), then:

| Section | Content |
|---|---|
| **Seen** | first seen, last seen, active days out of the covered days, rows per day sparkline |
| **Where** | the Datasets and columns it appears in, rows in each |
| **Links** | degree, link kinds with counts, and where that degree sits in its type's distribution ("p97") |
| **Related identifiers** | other Entity Types in the same rows (its devices, its accounts) with counts, under masking as aliases |
| **Lists and groups** | Entity List membership (after the masked-alias fix), identity group, Case history, Risk (reused from the node dialog) |
| **Freshness** | data as of for each Dataset it appears in, so "last seen" is read against the data, not the clock |

"Investigate" seeds an Investigation from the page, as the starter card does today.

## 4. Backend

### 4.1 Profile service and routes

- `GET /inv/entity-types` - the types in force, each with its bindings and a cached headline (distinct, rows, data as
  of, computed-at). Cheap: it reads the cache and never runs a query on the request path.
- `GET /inv/entity-types/{id}/profile?sections=` - the Entity Type profile (section 3.1). Computed per binding with
  one DuckDB pass per Dataset (`approx_count_distinct`, `count`, `count(col)`, `min/max(time)`, `length`, prefix,
  `group by day`), merged across bindings. Cached by the Dataset's input-file fingerprint, the same key
  `DatasetMeasureProbe` already uses for data as of, so a new File invalidates it and nothing else does.
- `GET /inv/entities/{type}/{key}/profile` - the Entity page (section 3.2). `key` is a raw value or a masked alias,
  resolved through the same masking the exploration reads use; one filtered query per binding.
- The private `ColumnStats` of the overlap profile becomes a shared column-profile helper (one implementation, two
  callers).
- Every section carries `assessed:true|false` and `reason`, as the data-trust routes do.

### 4.2 Link index additions (additive manifest fields)

At build time the index already scans every edge, so it records, for free: edges per link kind, node count per Entity
Type (when the mapping is typed), degree quantiles (p50/p90/p99/max) and the event time span. `GET /inv/index`
returns them. When no fresh index exists, the Links section falls back to a Dataset query under the limits budget, or
says `not assessed`.

### 4.3 Scheduled profile (optional, D-EP2)

A `la.entity.profile` Job type that refreshes the profiles after each committed Consignment (`on_signal:
pipeline.commit`, as `la.index.build` does) and writes them to a profile Dataset. The profile Dataset then feeds:
Alert Rules on drift (distinct count jumps, a new sentinel value, fill rate drops), a Dashboard, and history ("how
did this type grow over 30 days").

### 4.4 Limits (LA-CONFIG-LIMITS-1 catalogue)

New limit items, server-gated like the rest: profile query timeout, Datasets per type profiled, rows sampled for value
shapes, top-N size for histograms, sentinel share threshold, per-day timeline length. Reads past a limit return a
partial section marked `truncated`.

## 5. Privacy and governance

- **Aggregates only on the Entity Type page**: counts, shares, shapes and quantiles; never a raw identifier.
- **Value examples**: off by default. When enabled (D-EP3) they appear as masked aliases and only for values seen at
  least k times (k-anonymity floor, a limit item).
- **Prefix histograms** expose at most the leading characters a normaliser declares safe (a country code, not a
  subscriber prefix).
- **Hubs** are counted, never named, on the type page; the Entity page names an Entity only through the caller's own
  read rights, and Reveal stays per Entity and audited.
- Every profile read is audited like an exploration read (`LINK_*` events).

## 6. Delivery phases

| Phase | Scope | Done when |
|---|---|---|
| **P0** | Decisions D-EP1..D-EP8; fix the Entity List masked-alias match; glossary entries | the decisions are recorded here and in `GLOSSARY.md` |
| **P1** | Type-to-binding resolver; column-profile helper; `GET /inv/entity-types` and `/profile` (Identity, Where it appears, Volume and quality, Time, Lineage); Entities list + Entity Type page in la-app | the IMS Space's types render the section-1 facts of the IMS plan without SQL by hand; real-HTTP tests per route, masking and `assessed` cases |
| **P2** | Value shapes, sentinel detection, Relationships (co-bound cardinality); index manifest additions; Links section | the 15-SIM device of the IMS data appears as a Relationships outlier, as a count |
| **P3** | Entity page + node dialog link + Entity List link | an analyst goes graph node to Entity page to Investigation and back |
| **P4** (optional) | `la.entity.profile` Job, profile Dataset, drift Alert Rules, 30-day history | an Alert fires on a fill-rate drop in a test Space |

Each phase: unit tests per route and component, the openapi entry for each new route, axe on each page, a live pass
on a seeded Space (the IMS Space and `la-showcase`) recorded in this plan.

## 7. Decisions to take (operator)

| Id | Question | Recommended |
|---|---|---|
| D-EP1 | Names: the page and the computed object | "Entities" page; **Entity Type Profile** (the Type-level object) and **Entity Profile** (one Entity), added to the glossary |
| D-EP2 | On-demand cached profile, or a scheduled Job snapshot as well | on-demand + cache in P1; the Job in P4 only if drift alerts are wanted |
| D-EP3 | Value examples on the type page | off; aliases with a k floor if turned on |
| D-EP4 | Link statistics from the index only, or also from Datasets | index first, Dataset fallback under budget |
| D-EP5 | Which identifiers count as one Entity across Datasets | the normalised typed key (`<type>:<value>`); identity groups shown, not merged, in P1 |
| D-EP6 | Where the pages live | la-app (nav entry beside Entity Lists) and the full SPA through the shared library |
| D-EP7 | Does a read of data as of need a write root | no: a read gate only, so a read-only host shows freshness |
| D-EP8 | Sentinel list | a built-in list (blank, `-99`, `0`, `NULL`, `unknown`, `n/a`) plus "any one value above X % of a column", per Space overridable |

## 8. Risks

- **Cost on large Datasets.** Distinct counts and per-day groups over 10^8 rows: approximate distinct, the cache, the
  limits budget, and the scheduled Job (P4) keep it off the request path.
- **A Dataset without a time column** has no timeline: the section says so instead of guessing.
- **A column bound to the wrong Entity Type** produces a confident wrong profile: the Where it appears section shows
  each binding so a wrong one is visible, and a binding whose values mostly fail the normaliser is flagged.
- **Masking leaks through shapes** if prefixes are too long: the normaliser-declared safe prefix length is the gate.

## References

- IMS showcase plan section 1 (the model for the content): [`la-ims-showcase-plan.md`](la-ims-showcase-plan.md)
- Data trust as built: [`okf/frontend/features/link-analysis.md`](../okf/frontend/features/link-analysis.md)
- Entity model design: [`archived-documents/plans-archive/link-analysis-entity-model-design.md`](../archived-documents/plans-archive/link-analysis-entity-model-design.md)
- Configurable limits plan (shipped, archived): [`la-configurable-limits-plan.md`](../archived-documents/plans-archive/la-configurable-limits-plan.md)
