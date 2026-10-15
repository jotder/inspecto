# Link Analysis: Entity profile pages (what the data says about each Entity Type and each Entity)

**Status: SHIPPED 2026-10-15 (P0-P3; P4 deferred to `LA-ENTITY-PROFILE-RESIDUALS-1`). As built in section 9, live pass in section 10; archived.** Generic for every Space and every
industry pack: nothing here names a telecom column. The model for the content is the "What the data is" section of the
IMS showcase plan ([`la-ims-showcase-plan.md`](../../superpower/la-ims-showcase-plan.md) section 1): volumes, distinct values, value
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

### 4.5 API contract (fixed 2026-10-15 for the parallel lanes)

All three routes are reads, Space-scoped (`/api/v1/spaces/{space}/...`), gated like the exploration reads
(`POST /inv/projection`), audited like them, and documented in `docs/api/openapi-v1.json`. Every section object carries
`assessed` (boolean) and `reason` (string or null); a section that could not be read says `assessed:false` with the
reason and never reports zero for something nobody measured. Numbers that are approximate say so (`approximate:true`).
Masked Entity Types never return a raw identifier: a key is the exploration alias (`masked:<16 hex>`).

**`GET /inv/entity-types`** - the Entity Types in force, cheap (reads the cache, never runs a profile query):

```json
{ "items": [ {
    "id": "msisdn", "label": "MSISDN", "normaliser": "e164", "masked": true, "classifications": ["MSISDN"],
    "bindings": [ { "dataset": "ims_links_dataset", "column": "a_msisdn", "role": "source" } ],
    "headline": { "distinct": 20546, "approximate": true, "rows": 19638, "dataAsOf": "2026-10-09T10:36:47Z",
                  "computedAt": "2026-10-15T09:00:00Z", "assessed": true, "reason": null } } ] }
```

`role` is `source` / `target` when the column is a link end of a Dataset's Link Analysis mapping (a saved view or an
index mapping names it), else `attribute`. `headline.assessed:false` with reason `not computed yet` until the type's
profile has been read once.

**`GET /inv/entity-types/{id}/profile?sections=bindings,volume,shapes,time,lineage,links,relationships`** (all sections
when `sections` is absent):

```json
{ "entityType": { "id": "...", "label": "...", "normaliser": "...", "masked": true, "classifications": [] },
  "computedAt": "...", "cached": true, "truncated": false,
  "bindings": [ { "dataset": "...", "column": "...", "role": "source", "timeColumn": "first_seen",
                  "rows": 0, "nonNull": 0, "fillRate": 0.0, "distinct": 0, "approximate": true,
                  "failNormaliser": 0, "assessed": true, "reason": null } ],
  "volume": { "distinct": 0, "approximate": true, "rows": 0,
              "sentinels": [ { "dataset": "...", "column": "...", "kind": "builtin", "value": "-99", "rows": 0, "share": 0.0 },
                             { "dataset": "...", "column": "...", "kind": "dominant", "value": null, "rows": 0, "share": 0.0 } ],
              "assessed": true, "reason": null },
  "shapes": { "length": [ { "length": 12, "rows": 0 } ], "prefix": [ { "prefix": "249", "rows": 0 } ], "prefixLength": 3,
              "charClass": { "digits": 0, "letters": 0, "mixed": 0 }, "sampled": false, "assessed": true, "reason": null },
  "time": { "earliest": "...", "latest": "...", "perDay": [ { "day": "2026-10-09", "rows": 0, "distinct": 0 } ],
            "missingDays": [], "dataAsOf": [ { "dataset": "...", "dataAsOf": "..." } ], "assessed": true, "reason": null },
  "lineage": [ { "dataset": "...", "pipelines": [], "collectors": [], "files": 0, "rows": 0, "latestIngestedAt": "...",
                 "waitingForIndex": 0, "problemFiles": 0, "assessed": true, "reason": null } ],
  "links": { "from": "index", "kinds": [ { "kind": "sms", "edges": 0 } ], "degree": { "p50": 1, "p90": 2, "p99": 9, "max": 46 },
             "hubThreshold": 500, "hubs": 0, "singleLink": 0, "assessed": true, "reason": null },
  "relationships": [ { "otherType": "imei", "dataset": "...", "oneToOneShare": 0.999, "maxPerEntity": 15,
                       "threshold": 6, "entitiesAtOrAboveThreshold": 1, "assessed": true, "reason": null } ],
  "masking": { "mode": "typed" } }
```

Sentinels (D-EP8): the built-in list `''`, `-99`, `0`, `NULL`, `null`, `unknown`, `n/a` is shown by value (they are not
identifiers); a **dominant** value (one value above the `entity_profile.sentinel_share` limit of a column) is shown
only as `kind:"dominant"` with `value:null` (aggregates only, D-EP3). `prefixLength` is the safe prefix the normaliser
declares (`e164`: 3, `digits`: 0 = no prefix histogram, `upper-trim`: 0, `default`: 0). Relationships are computed
for each other Entity Type bound in the SAME Dataset row.

**`GET /inv/entities/{type}/{key}/profile`** - one Entity; `key` is the raw value or the exploration alias, resolved
through the same masking as the exploration reads:

```json
{ "entity": { "type": "msisdn", "key": "masked:f01a9b8ca40c4eb1", "masked": true },
  "firstSeen": "...", "lastSeen": "...", "activeDays": 1, "coveredDays": 1,
  "perDay": [ { "day": "2026-10-09", "rows": 0 } ],
  "where": [ { "dataset": "...", "column": "...", "rows": 0 } ],
  "links": { "degree": 8, "kinds": [ { "kind": "co_target", "count": 7 } ], "percentile": 97, "from": "index", "assessed": true, "reason": null },
  "related": [ { "type": "imei", "count": 1, "keys": [ "masked:..." ] } ],
  "dataAsOf": [ { "dataset": "...", "dataAsOf": "..." } ],
  "assessed": true, "reason": null }
```

Entity List membership, identity group, Case history and Risk on the Entity page come from the existing routes.

**New limit items** (LA-CONFIG-LIMITS-1 catalogue, Space layer, server-gated): `entity_profile.timeout_s` (default 30),
`entity_profile.max_datasets` (20), `entity_profile.shape_sample_rows` (1 000 000), `entity_profile.top_n` (20),
`entity_profile.sentinel_share` (20, a percent), `entity_profile.timeline_days` (400),
`entity_profile.relationship_threshold` (6).

**Index manifest additions** (additive, D-EP4): `kindEdges` (map kind to edges), `typeNodes` (map Entity Type to nodes,
when the mapping's columns are typed), `degree` (`p50`, `p90`, `p99`, `max`) and `timeSpan` (`min`, `max` of the
mapping's time column). `GET /inv/index` returns them per index. The Links section reads them; without a fresh index it
falls back to a Dataset query under the budget, else `assessed:false`.

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

## 7. Decisions (operator interview 2026-10-15)

| Id | Question | Decided |
|---|---|---|
| D-EP1 | Names | **Entities** page; **Entity Type Profile** (the Type-level object) and **Entity Profile** (one Entity), in the glossary |
| D-EP2 | On demand or a scheduled Job | **on demand + cache** keyed by the input-file fingerprint; the Job stays in P4 (deferred) |
| D-EP3 | Value examples on the type page | **none: aggregates only** |
| D-EP4 | Where link statistics come from | **index first** (new manifest fields), **Dataset fallback** under the budget, else `not assessed` |
| D-EP5 | What is one Entity across Datasets | **the normalised typed key**; identity groups shown on the Entity page, not merged into counts |
| D-EP6 | Where the pages live | **la-app** (nav entry beside Entity Lists) **and the full SPA**, through the shared library |
| D-EP7 | Does reading data as of need a write root | **no: a read gate only** |
| D-EP8 | Sentinels | **built-in list + dominance** (one value above a share of a column), per Space overridable |

Scope this round: **P0-P3**. Landing: the lanes are integrated on `master` locally after their tests and the guards
pass; the push waits for the operator.

## 8. Risks

- **Cost on large Datasets.** Distinct counts and per-day groups over 10^8 rows: approximate distinct, the cache, the
  limits budget, and the scheduled Job (P4) keep it off the request path.
- **A Dataset without a time column** has no timeline: the section says so instead of guessing.
- **A column bound to the wrong Entity Type** produces a confident wrong profile: the Where it appears section shows
  each binding so a wrong one is visible, and a binding whose values mostly fail the normaliser is flagged.
- **Masking leaks through shapes** if prefixes are too long: the normaliser-declared safe prefix length is the gate.

## 9. As built (2026-10-15, P0-P3)

Four parallel lanes against the section 4.5 contract, integrated on `ep-integ` and merged to `master`:

| Lane | Commits | What |
|---|---|---|
| A routes | `d7c4b1348` `f3dbb3b3c` `b3a585044` | `ColumnProfile` helper; `EntityTypeBindings`, `EntityProfiles`, `EntityProfileRoutes`, `EntityKeySql`, `LinkStatsSource`; the three GET routes plus `POST /inv/entities/{type}/resolve`; three `LINK_ENTITY_*` audit events; the seven `entity_profile.*` limits; openapi entries |
| B index stats | `18e1b68e7` `69e2d2b5e` `fa81c88f8` | `IndexManifest` gains `kindEdges`, `typeNodes`, `degree`, `timeSpan`; `GET /inv/index` serves them; `IndexLinkStats` is the index-first Links source |
| C P0 fixes | `dc12afd24` `9f5e0a4b7` | an Entity List match accepts the masked alias (node dialog passes the key it shows); `GET /datasets/{id}/freshness` is a read gate only (D-EP7) |
| D UI | `f1f486fea` `7ebd57658` | `projects/link-analysis/src/entities/*`: Entities list, Entity Type page, Entity page, lookup; la-app nav entry, full-SPA route, node dialog link |
| integration | `094560e9f` + the live-pass fixes | route counts (`AbsentModuleRoutesTest` 88, module doc 88/81), the `out` fixed name in `ImportLoaderInventoryTest`; see the live pass below |

Deviations from the contract, deliberate:

- **Raw keys never travel in a URL** (operator, during the build). A typed value is resolved by
  `POST /inv/entities/{type}/resolve {key}`; the Entity page URL carries only the returned key. For a masked type the
  GET is alias-only and answers **422** to a raw key. The contract's "raw value or alias" in the path is withdrawn.
- **No 403 path of its own:** the routes inherit the exploration read gate; a Dataset the caller cannot view is simply
  not a binding, and a section that needs one says `assessed:false` with the reason.
- **Extra fields** beyond section 4.5: `sentinelShare`, `truncated` and `sampleRows` per section, `masking.mode`.
- **View-backed Datasets are not cached** (no input-file fingerprint to key on); they are read on each request under
  the timeout.

## 10. Live pass (2026-10-15, IMS Space, Enterprise demo bundle with Demo Auth, index v37)

API, as `fm.analyst` (`ep_live` script): **34/35 + 6/6**. The one miss was by design: the raw-key GET on a masked type
answers 422 after the resolve decision; the resolve-then-alias flow passes 6/6.

- Entity Types: IMSI 21,314, IMEI 17,203, MSISDN 26,937 distinct (approx.), every one masked, data as of 9 Oct 2026.
- **The 15-SIM device appears as a count** (P2 done-when): IMEI to IMSI, most per Entity 15, 1 Entity at or above 6.
- MSISDN Links come **from the index**; the burst number resolves to an alias, degree 8, percentile 100, 8 related
  IMSIs. No raw identifier in any response body, page text or URL (regex scan for the data's identifier shapes).
- Cold profile read 2.2-5.4 s; the cached read about 200 ms.

Browser (la-app, Demo FM Analyst): Entities list, Entity Type page (all eight sections; Links `Not assessed` with its
reason, listed again under What is not here), lookup, Entity page on `/entities/msisdn/masked:...`.

Found and fixed in the pass:

- **Distinct above rows** (IMEI, 2026-10-08: 104 rows, 109 distinct): the approximate sketch overshoots on small
  counts. Every approximate distinct is now capped at its row count (`LEAST(...)`, `ColumnProfile` and
  `EntityProfiles`).
- **Enter in Open an Entity did nothing:** the pen field takes Enter to commit, so the form never submitted. The lookup
  opens on Enter in the editor; Enter on the closed row only starts editing (two new specs).

Open, moved to `BACKLOG.md`: dominance ignores values that fail the normaliser (`other_msisdn` is dominated by a
non-number value, yet MSISDN shows no sentinel); a one-to-one share of 99.95 % renders as 100 % next to a maximum of 15;
P4 (scheduled profile Job, drift Alert Rules, history).

## References

- IMS showcase plan section 1 (the model for the content): [`la-ims-showcase-plan.md`](../../superpower/la-ims-showcase-plan.md)
- Data trust as built: [`okf/frontend/features/link-analysis.md`](../../okf/frontend/features/link-analysis.md)
- Entity model design: [`archived-documents/plans-archive/link-analysis-entity-model-design.md`](link-analysis-entity-model-design.md)
- Configurable limits plan (shipped, archived): [`la-configurable-limits-plan.md`](la-configurable-limits-plan.md)
