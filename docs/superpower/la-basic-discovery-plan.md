# LA-BASIC-DISCOVERY-1 — discovery, an entity summary and preset queries in Basic

> **PLAN 2026-11-03 — decisions Q-1…Q-5 accepted by the operator 2026-11-03 (the recommended options, §6; presets first).** Operator ask: "entity profiling and some kind of
> discovery in Basic also; the user should be able to query the entity relationship data; some preset queries would be
> nice." Product hub `docs/okf/product/module/la-core/`. Related: `la-data-arrival-plan.md` (the Data page, Mappings),
> `docs/EDITIONS.md` (D-LAE4: the Entity profile **pages** are Standard and up). Board rows: `LA-BASIC-DISCOVER-1`,
> `LA-ENTITY-SUMMARY-1`, `LA-PRESET-QUERIES-1`, `LA-QUERY-BUILDER-1`.

## 1. The goal in the analyst's words

Before and beside drawing a graph, in Basic:

1. **What is in this Dataset?** How many entities of each kind, which are busiest, which kinds of link and how many,
   the period covered, amount totals.
2. **Tell me about this one entity.** First and last seen, who it links with most (by links and by amount), its kinds of
   link, its activity over time - and *Draw it*.
3. **Ask the relationship data a question** without writing SQL: "who sent the most to X last week", "numbers sharing
   a device", answered as a table I can sort and **draw**.
4. **Start from good questions** my industry already asks: presets with a few parameters.

## 2. What exists (research 2026-11-03)

| Need | Built | Edition |
|---|---|---|
| Entity Type list, resolve, suggest | `EntityProfileRoutes` (`la/inspecto-la-api`) :48-54 | open (`geoLink`) |
| Full profile pages (type + entity) | `EntityPageRoutes` -> `EntityProfileRoutes.typeProfile / entityProfile` | **Standard+** (`entityPages`, D-LAE4) |
| Profile computation | `EntityProfiles` (package-private, no HTTP): sections bindings, volume, shapes, time, lineage, links, relationships; per-entity link stats through `LINK_SOURCES` = `IndexLinkStats` + `DatasetLinkStats`; masking via `maskedUnder(mode, type)` (masked keys leave as `masked:<16 hex>`, raw keys go through `/resolve`); every call audited | reusable |
| Graph reads | `POST /inv/projection`, `/projection/neighbors`, `/projection/multi`; `POST /inv/pattern/branching`, `/inv/pattern/temporal` (server side, index-aware) | open |
| Aggregate query | `POST /bi/query` `{dataset, measures[{agg, field}], groupBy[], filters[], orderBy, limit}` via `MeasureCompiler` (validated identifiers, typed parameters), masking `BiResultMasking`, row cap `ListLimitSettings` | gate **to confirm** (V1) |
| Dataset rows + column statistics | `GET /datasets/{id}/rows` (cardinality, rowCount), `GET /db/table` | open |
| Pattern packs | stage motifs (fan-out / fan-in, windows, thresholds) in Space templates `config/registry/pattern-packs/*.toon`; named by the industry pack's `pattern_packs[]` | open |
| Industry packs | `META-INF/inspecto/industry-packs/{telecom,mobile-money}/`: entity types, link kinds, terms, `profile{measure_hints, suggested_tools, …}` | every edition |
| Preset queries | **none** (BiTemplates are dashboard templates; saved queries exist at `/queries/{id}/run`) | - |

## 3. Proposed design

**P1 Discover** (per Dataset; the Data page's *Discover* tab and the Start box's *About this Dataset*):
`GET /inv/datasets/{id}/discover` (la-api, `geoLink` only) built from `EntityProfiles` blocks over THIS Dataset's
mapping (the Dataset's or a Mapping's `defaultLinkMapping` + column classifications): entities per Entity Type, top 10
busiest per role (From / To), link kinds with counts, time range, amount sum / median when an *Amount* is mapped. Masked
types answer aliases and counts only. Cached per Dataset + last arrival (the arrival ledger's newest consignment).

**P2 Entity summary** (the *lite* profile; Basic and up): `GET /inv/entities/{type}/{key}/summary?dataset=` (la-api,
`geoLink` only) = `EntityProfiles.entityProfile` restricted to: first / last seen, counterparts ranked by links and by
amount (top 20), link kinds, activity by day (last 90 days), Entity List membership. The full page (lineage,
relationships across Datasets, Case history, Investigations) stays Standard (D-LAE4 unchanged). UI: the node detail
dialog gets *Summary*; Discover's busiest rows open it.

**P3 Preset queries:** a pack carries `preset_queries[]` in `industry-pack.toon` (new key, parsed by `IndustryPack`):
`{id, label, description, roles: {from, to, kind?, time?, amount?}, kind: aggregate | temporal, params[{name, type, default}], …}`.
`aggregate` presets compile to a `MeasureCompiler` query over the Dataset's roles (never free SQL): e.g. *Shared devices*
(group by device, count distinct numbers >= n), *Fan-in* (group by To, count distinct From >= n in window),
*Top counterparts of X*. `temporal` presets run through the existing `POST /inv/pattern/temporal` (e.g. *cash-in then
cash-out within N minutes*). Routes: `GET /inv/presets?dataset=` (the active pack's presets whose roles the Dataset maps),
`POST /inv/presets/{id}/run {dataset, params}` -> `{columns, rows, truncated}` with masking and the Space row cap.
First set: Telecom - *numbers sharing a device*, *SIMs seen in many handsets*, *most-called numbers of X*; Mobile money -
*cash-in then cash-out within N minutes*, *many wallets paying one*, *top counterparts of a wallet*.

**P4 Query builder:** a form over the Dataset's roles (From is / To is / Kind of link is / When between / Amount over /
group by From or To / count or sum / top N) that builds the same `MeasureCompiler` query; results table with **Draw
these** (start a graph from the listed entities, the existing *Start from these* path) and **Save as preset** (a
Space-level preset, stored as a component, `preset-query/<id>`).

Every answer is read-only, masked as exploration is, capped by the Space limits, and audited like profile reads.

## 4. Phases

| Phase | Scope | Size |
|---|---|---|
| **P0 verify** | V1 `/bi/query` open on Basic (and its masking for a masked Entity Type); V2 `ValueMeasures` / `ColumnProfile` cover amount totals; V3 do pattern packs also run in the browser; V4 `EntityProfiles` cost on a 10^7-row Dataset (IMS) without the link index | 1 day |
| **P1 Discover** | route + cache + Data page tab + Start box *About this Dataset* | M |
| **P2 Entity summary** | route + node dialog *Summary* | M |
| **P3 Presets** | pack key + loader + 2 routes + 6 presets + UI picker with parameters + table + *Draw these* | L |
| **P4 Builder** | form + same compiler path + *Save as preset* | M |

## 5. Done means

On the Basic install, over the IMS and Mobile money Datasets: Discover shows counts and busiest entities in under 3 s
(cached under 0.5 s); an entity summary opens from a busiest row; *numbers sharing a device* and *cash-in then
cash-out within 30 minutes* return rows that **Draw these** turns into a graph; a masked Entity Type never shows a raw
key; tests per route (real HTTP, masking, limits) and UI specs.

## 6. Decisions (operator 2026-11-03: accepted as recommended - presets first)

- **Q-1** Discover and the entity summary are **Basic and up** (read-only explorer reading); the full profile pages
  stay Standard (D-LAE4 unchanged). *Recommended.*
- **Q-2** Presets are **declarative, never free SQL**: compiled through `MeasureCompiler` or the pattern routes over
  the Dataset's mapped roles. *Recommended* (masking and guards stay where they are).
- **Q-3** Presets ship **in the industry pack** (one set per pack), plus **Space-level presets** saved from the builder.
- **Q-4** Order: **P3 presets first** (the most visible value), then P1, P2, P4. Alternative: P1 first (cheapest).
- **Q-5** A preset needs its roles mapped: a Dataset that lacks a role (e.g. no device column) does not list that
  preset rather than showing it greyed - the Mapping page is where a role is added.
