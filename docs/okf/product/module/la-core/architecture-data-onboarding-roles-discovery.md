---
type: Concept
title: Link Analysis — data onboarding — Column roles and discovery in Basic
description: Part 2 of 3 of Link Analysis — data onboarding: Column roles (LA-COLUMN-ROLES-1, as built 2026-11-04/05); Discovery in Basic: presets, Discover, entity summary, query builder (LA-BASIC-DISCOVERY-1, as built 2026-11-03/05)
tags: [module, link-analysis, onboarding, add-data, arrivals, dataset, architecture]
timestamp: 2026-11-12T00:00:00Z
---

# Link Analysis — data onboarding — Column roles and discovery in Basic

Part 2 of 3 of [Link Analysis — data onboarding](architecture-data-onboarding.md) · previous: [Add data, help and onboarding residuals](architecture-data-onboarding-add-data-help.md) · next: [Arrivals, arrival feeds and Dataset storage](architecture-data-onboarding-arrivals-storage.md).

## 6.15 Column roles (LA-COLUMN-ROLES-1, as built 2026-11-04/05)

A Dataset column has a **role**, stored once on the registry entry as `columns[].role`, one vocabulary with Studio:
`entity | temporal | measure | dimension | detail`, shown as **Entity · Time · Measure · Category · Detail**
(`docs/GLOSSARY.md` *Column Role*; operator decision B). A Measure may carry a `unit`; a Time, Measure or Category
column may say `carried: false`. A Mapping copies its base's columns with their roles and marks.

- **The rules** (`DatasetEntryRules`, run on every Dataset write): a role is one of the five or absent; an Entity column
  holds an Entity Type (`classification`); a `unit` is a Measure's only; a Measure, and the primary Measure, is a
  number column (`number`, BIGINT, DOUBLE, DECIMAL(p,s)...; a column without a type is not checked); `carried` is a
  boolean. An entry without roles still loads and reads back-compatibly: holds an Entity Type → Entity; the mapping's
  primary Time or `eventDate` → Time; its primary Measure → Measure; its kind → Category; on the Data page a
  date/timestamp column → Time; anything else → Detail.
- **A base's roles from its Mappings (operator 2026-11-05, display only).** On the Data page's Columns card, a base's
  column that states no role and that the base itself reads as a Detail shows the role the primaries of the Mappings
  built on it give it (primary Time → Time, primary Measure → Measure, kind → Category; `data-page.ts` `mappingRole`),
  with a note naming them (*From Mapping mm_wallet_links - not set on this Dataset*). Mappings that disagree leave it a
  Detail. Nothing is written: only a person's pick (or a Unit, which saves the Measure) stores a role. The server's
  readers (`DatasetEntryRules`, presets, profiles) do not derive it.
- **Primaries.** `defaultLinkMapping` names `primaryTime` (dates each link: the window, newcomers, playback's first
  choice, Reconciliation's `DatasetRelation.temporalColumn` when several Times exist) and `primaryMeasure` (presets'
  sums). `time` / `amount` are their old names, read for stored entries and in an Add data commit body; two different
  columns under both names are refused. A primary's column, when it states a role, has the matching role. Readers:
  `DatasetRelation.temporalColumn`, `PresetQueries.roles`, `EntityTypeBindings`, `OnboardImport`'s index build, the LA
  start path (`readLinkMapping`), Studio.
- **Add data** proposes a role per column (`entityTypes.columns[].role`, with evidence; `distinct` for a text column):
  Entity when it holds an Entity Type; the link's kind / time / measure take Category / Time / Measure; else a DATE or
  TIMESTAMP is a Time, a DOUBLE (or a BIGINT named as an amount) a Measure, a text column whose sampled values repeat
  under 200 distinct a Category (sure under 20), anything else a Detail (always sure). The commit writes the roles; a
  stated role that contradicts the Entity Type, the link's primaries or a Measure on text is 422. The Roles step shows a
  role row per column and offers each primary only among columns of its role; a Measure whose type is changed to text
  becomes a Detail.
- **Carriage (operator decision A, 2026-11-04).** The **link index** carries every Time, Measure and Category column
  (`DatasetEntryRules.carriedColumns`, column order, never a link end, at most 50 = the index build's own bound): Add
  data's first build passes them, and the Edge index tool's first-build form is prefilled with them. A **link read
  carries none by default**: a read's `attrCols` are grouping keys (server `edgeSql`, the UI fold key), so carrying a
  timestamp or an amount would unfold the links. Play's Time picker (the timeline menu and a picker on the playback
  strip) lists the dated link columns and the Dataset's Time columns, the primary first; picking one the links do not
  carry re-reads them carrying that one column, and only while the canvas is still the plain query's result
  (`plainGraph`: a seeded start, an Investigation, an expand, a Grow or a push is never replaced by the whole Dataset).
- **Filter by role** (`role-filters.ts`, `link-analysis-role-filter.component.ts`): a range per Measure, a value list per
  Category (its distinct values read with one SQL per column, at most 200, as toggle chips with a find box past 12; read
  once per Dataset, a late answer for a Dataset no longer drawn is dropped), a window on any Time column. Applying writes
  the conditions into the one predicate tree - taking out only the conditions the panel wrote last time (JSON-equal), so
  the analyst's own and a loaded saved view's conditions stay even on a role column; an OR or negated root becomes one
  group ANDed with them - and pushes it as the read's `filter`, which the server renders over the Dataset in SQL or,
  when the read is served from the link index, over its attribute columns (`IndexedRead.renderFilter`) - so no column
  needs carrying for it. A Category value holding a comma cannot travel in an `in` list and is left out.
- **Entity profile measures** (`EntityProfiles.measures`): for each binding of the Entity Type, each Measure of that
  Dataset over the rows naming the Entity in that column - rows holding a number, sum, lowest, highest, the unit - inside
  the profile's window on the binding's time column; a value that is no number is not counted (`TRY_CAST`; sums rounded
  to 6 places through BigDecimal, a non-finite one is null). Inside a window a binding with no time column adds none.
  The bound column and any column holding an Entity Type are never a measure here - and `DatasetEntryRules` refuses a
  Measure that holds an Entity Type ("an identifier, not a measure"), because its sums would surface raw ids the
  profile masks; the Data page drops an unclaimed Entity Type when a column becomes a Measure. The Entity page shows
  them as **Measures** (absent when there are none).
- **Entity Type profile measures** (`LA-COLUMN-ROLES-2` D-1 b, 2026-11-05; `EntityProfiles.typeMeasures`, section
  `measures`): one row per binding x Measure (the same column rule, `measureColumns`) over the rows where the bound
  column names an Entity (non-empty normalised key) - rows, sum, lowest, highest, unit - in ONE aggregate statement per
  bound Dataset (each binding is its own row, so no row is summed twice), plus `distribution`: p50 / p90 / max of the
  per-Entity totals (`quantile_disc`) and the Entities measured, one GROUP BY per binding. That read groups at most
  `entity_profile.measure_sample_entities` Entities (Space limit, default 100,000, 1..10,000,000): above it a key-hash
  sample, `hash(k) % every = 0` with `every = ceil(approx distinct x 1.1 / cap)` (the headroom keeps the hash spread under
  the cap), the cap as a hard `LIMIT`; the answer says `sampled`, `every`, `sampleEntities`, `approximate`, and
  `truncated` when the LIMIT may have cut it. Optional `from` / `to` on `GET /inv/entity-types/{type}/profile` scope this
  section only (on each binding's time column; a binding with none is not assessed inside a window), cached per window.
  A failed or timed-out per-Entity read is `distribution: {assessed:false, retryable}` beside the row's sums and keeps
  the section out of the cache. The Entity Type page shows it as **Measures**. Measured on 200,000 synthetic Entities
  with a cap of 1,000: 1 in 232, under 1,000 grouped, the LIMIT not reached (`ControlApiEntityProfileScaleTest`).
- **Roles from the record type (`ENTITY-TYPE-RECORD-ROLES-1`, 2026-11-05).** A record type names its usual Time,
  Measure and Category columns by hints (entity-store `spec-entity-store.md` *A record type's usual roles*; the one rule is
  `EntityShapes.hintedRole` / UI `hintedRole` in `column-roles.ts`, kept alike). **Add data:** after the record type is
  proposed, `EntityShapes.recordTypeRoles` gives each column a hint names its role - never one holding an Entity Type
  or chosen as the link's kind, time or measure - sure when the record type is, and keeps the role proposed without it
  as `base`. Picking another record type on the screen re-proposes (`roleUnder`) every role still as proposed: back to
  `base`, then the new record type's hint; a role the person picked stays. **Data page:** under *Each row is*, the
  roles the record type proposes for columns that state none or Detail and are none of the link mapping's
  (`recordTypeProposals`; a role the column already reads as is not repeated), saved together by *Use these roles*;
  a role the person set is never proposed over. The Holds picker marks *Shape matches* per column from
  `GET /inv/datasets/{id}/shapes` (read once per page per Dataset).
- **A Mapping's own carriage (`LA-COLUMN-ROLES-2`, 2026-11-05).** The Mapping form has an *On each link* row per Time,
  Measure and Category column of its base (as the page reads the base's roles; never the Mapping's From or To),
  seeded from the base for a new Mapping and from the Mapping when editing it (`MappingForm.notCarried`,
  `carriedChoices`). The Mapping's entry writes its OWN `carried: false` marks, so its link index carries what it says,
  whatever its base says; a kept-off column whose base states no role gets the role the page shows (a date is a Time).
- **Link width (`LA-COLUMN-ROLES-2` D-2 a, 2026-11-05).** A link read may name `measureCol` (`/inv/projection`,
  `/neighbors`; an edge mapping's `measureColumn` on `/multi`): the server SUMs it per folded link as `total`
  (`TRY_CAST ... AS DOUBLE`; a link with no number has null) beside `cnt`, never as a grouping key - in the flat SQL
  (`edgeSql`) and, for a neighbours read, from the link index when it carries the column (`IndexReader.fold`'s
  `totalCol`; else flat, `column_not_indexed`). The canvas draws a link's width from `data.weight`
  (`link-width.ts` `weighLinks`): by its count (default, *Width: Count*) or by its total relative to the largest on
  the canvas (null or not positive = thinnest); the client folds add totals (10 + null = 10). The Display menu offers
  the Measure only when every read Dataset names the same primary Measure, and not on an Investigation canvas (it
  draws its Working Set by count); over a graph grown beyond the query it can be picked only once the links carry
  their totals (else *Run the query again first*); the choice is kept in a saved view (`linkWidth: 'measure'`).
  Not yet weighed: the dashboard link-view widget and the embed view.
- **Filter by role over several Datasets** (`LA-COLUMN-ROLES-2` D-3 b, 2026-11-05; `role-filters.ts`
  `withDatasetRoleFilters`, the panel's `sections`). Over a read of several Datasets (`entity-projection-multi`) the
  panel draws one section per edge mapping's Dataset, in mapping order, titled by its name, from that Dataset's own
  `columns[].role` (a Dataset with no Measure, Category or Time column gets none); each section is a group named by its
  heading and its controls are named `<Dataset>: <field>`, so the same column in two sections reads apart. Category
  values are read per Dataset (one SQL per column, at most 200), once per Dataset while it is drawn; a late answer for a
  Dataset no longer drawn is dropped. Applying writes each section's conditions as the own `filter` of every edge
  mapping over that Dataset on `POST /inv/projection/multi` (which narrows only that mapping,
  `ControlApiMultiDatasetFilterTest`) and re-runs the read as a push does (merged, stranded nodes marked). Only the
  conditions the panel wrote into the run on screen are taken out (JSON-equal, per Dataset; recognised by the mapping
  `filter` objects the push sent, which a Refresh or a Measure re-read keeps, so they never pile up - while from a new
  run's or a loaded view's mappings nothing is taken out; loading a view or Clear in the filter also empties the
  panel); the analyst's own mapping filter and the top-level `filter` stay. A section left empty sends no filter for its Dataset. Node mappings are not narrowed. A single-Dataset read is
  unchanged.

## 6.16 Discovery in Basic: presets, Discover, entity summary, query builder (LA-BASIC-DISCOVERY-1, as built 2026-11-03/05)

The operator's ask: profiling and discovery in Basic, querying the relationship data without SQL, preset queries. Four
read-only surfaces, all **Basic and up** (feature `geoLink`, Q-1), all over ONE Dataset's mapped roles, masked like
exploration, capped by the Space's limits and audited. The full Entity profile pages stay Standard (D-LAE4 unchanged).
Rows closed: `LA-PRESET-QUERIES-1`, `LA-BASIC-DISCOVER-1`, `LA-ENTITY-SUMMARY-1`, `LA-QUERY-BUILDER-1`; plan
[`la-basic-discovery-plan.md`](../../../../archived-documents/plans-archive/la-basic-discovery-plan.md).

| Route | Class | What |
|---|---|---|
| `GET /inv/presets?dataset=` | `PresetRoutes` | the pack's preset questions the Dataset can answer, its roles, the builder's role columns (`columns {time, measure}`) and the Space presets saved from the builder (`saved`) |
| `POST /inv/presets/{id}/run` | `PresetRoutes` + `PresetQueries` | one preset; read-shaped, EXPENSIVE, `LINK_PRESET_RUN` |
| `GET /inv/datasets/{id}/discover` | `DiscoverRoutes` + `DatasetDiscovery` | what is in a Dataset; EXPENSIVE, `LINK_DATASET_DISCOVERED` |
| `GET /inv/datasets/{id}/shapes` | `DiscoverRoutes` | the Entity Types whose shape each column's first 200 rows fit, marks only (the Data page's *Shape matches*; `ENTITY-TYPE-RECORD-ROLES-1`); cheap, not audited (no value leaves) |
| `GET /inv/entities/{type}/{key}/summary?dataset=` | `EntitySummaryRoutes` + `EntitySummary` | one entity over one Dataset; EXPENSIVE, `LINK_ENTITY_SUMMARIZED` |
| `POST /inv/datasets/{id}/query` | `QueryRoutes` + `QueryBuilder` | a builder question (or a saved preset's); read-shaped, EXPENSIVE, `LINK_QUERY_RUN` |

**Never free SQL (Q-2).** la-api may not depend on the engine (LA separation D-1), so `MeasureCompiler` is out of reach:
each surface renders its own fixed statement over the Dataset's real, quoted columns with bound values. The builder's
`QueryBuilder.parse` accepts exactly `by, rank, sumColumn, from, to, kinds, timeColumn, after, before, measureColumn,
measureMin, measureMax, min, top` and checks each column's ROLE (Time = `columns[].role: temporal` or the primary Time;
Measure = `role: measure` without a classification, else the mapping's primary Measure). A saved preset is parsed again
each time it is listed or run, so a stored query is never trusted as written. The window is half-open (`after`
included, `before` not), a Measure range inclusive.

**Roles.** `PresetQueries.roles` gives From, To, Kind and the primaries (`defaultLinkMapping.primaryTime` /
`primaryMeasure`, or their old names `time` / `amount`; else the classified columns, `*_kind`, the event date, the pack's
`measure_hints`). Without a mapping, From / To are (`PresetQueries.ends`, `LA-PRESET-ROLES-1` fixed 2026-11-05) the
Dataset's record type's parties (its `from_hints` / `to_hints` among the classified columns), else the first two
columns holding the SAME Entity Type, else the first two - so a subscriber's number and SIM are no longer read as a
link when the call's two parties are classified too. `DatasetDiscovery.measures` and `QueryBuilder.columns` add every Measure and Time column
(LA-COLUMN-ROLES-1). A role the Dataset lacks: the preset or saved question is not offered (Q-5); a Discover section is
`assessed:false` with why; a summary part is null.

**Presets (P3).** Code keyed by Industry pack id (`PresetQueries.forPack`; mobile money and telecom put one question
ahead of the core set), not `industry-pack.toon` entries: a preset's compile rule is code either way. Shapes: the
aggregate (group by one end; counterparts, links, total; a HAVING on the first measure), graph statements over the links
joined to themselves (`graphSql`: Two-way pairs, Short loops, Newcomers, Two steps), and the `timeToCashOut` Value
Measure. Reached by many, Reaching many and Newcomers read the link index's `nodes` table, Two-way pairs and Short loops
its `pairs_back` / `loops` (`LA-INDEX-PAIRS-LOOPS-1`), through `IndexedRead.select` (the Space's index switch and the
staleness refusal apply); each answer carries `source`. Counting from the index differs (each side apart, per kind of
link) and says so. **Space presets (Q-3)** are registry components `preset-query/<id>` `{label, description, query}`
(`ComponentStore`, read through the `DatasetProvider.presetQueries` seam), listed only to callers who may view them
(`ComponentAccess.canView`), a raw From / To in a stored query echoed as its alias.

**Discover (P1).** V4 measured the `EntityProfiles` union read (one Entity Type across every bound Dataset) at **112 s**
on IMS (39.4 M links, 12.35 M distinct numbers), so Discover never uses it. It reads THIS Dataset in cheap statements:
one pass for rows, the period on the primary Time and each Measure's count, sum and `approx_quantile(0.5)`; one
`approx_count_distinct` per Entity Type over its columns, keys normalised as the profiles normalise (`EntityKeySql`;
2.6 s on IMS, 0.15 % off the exact 18.5 s count); one `GROUP BY` on the kind (at most 50); and the 10 busiest per end
through the presets' path (`PresetRoutes.fromIndex` with Reaching / Reached by many, else the aggregate over the Dataset
under the statement timeout; a timeout is `assessed:false`, "a link index answers it quickly"). Cold Discover on IMS is
about 6 s (over the plan's 3 s); cached it is instant. **Cache:** per Space, Dataset entry, roles, limits, held types and
masking mode; stamped with the Dataset's `files:` input fingerprint AND the link-index versions currently published for
it, so an arrival or a new index recomputes. Entity ids are cached raw and masked on the way out. A failed or timed-out
read is answered but never kept (`retryable`; an unmeasured row count carries `rowsReason`). A Dataset without a
`files:` fingerprint (a view) is never cached. `busiest.fromType` / `toType` name the Entity Type a busiest row's
summary asks for.

**Entity summary (P2).** One statement per entity: the links naming it at either end (keys compared normalised under
the type's normaliser), grouped once by `GROUPING SETS ((), (o), (k), (d))` - headline, per counterpart, per kind, per
day - each set cut by a window rank (top 20 by links and top 20 by the primary Measure, 50 kinds, the 90 days before its
last seen). V4: a one-key read of IMS costs 3-4 s, so four statements would not do. A self-link or an empty other end
counts as a link but is no counterpart. **Key rule** as the Entity profile's: for a type masked under the Space's mode
the URL takes only the alias (a raw key is refused 422, never read; the UI resolves a raw key through
`POST .../resolve` in a body first); when the queried type is masked EVERY returned id is an alias
(`ExplorationMasking.forced`), whatever its columns are classified as - the first review found raw counterparts over
unclassified columns, the entity's own key among them through a self-link.

**V3 (pattern packs in the browser):** yes, over the loaded graph only (`matchPattern`, `matchBranchingPattern`); the
whole-Dataset run is `POST /inv/pattern/branching`. Discover therefore runs no pack.

**UI.** `link-analysis-presets` (under Basic's Start box) holds the pack questions, the saved ones (*... (saved here)*)
and `link-analysis-query-builder` (shown when the Dataset maps From and To; *Save as preset* writes the component).
`link-analysis-dataset-discover` sits on the la-app Data page (one card per Dataset; Draw is offered from Explore) and
under the Start box as *About <Dataset>*, following the Start box's pick; it drops its answer when its Dataset changes.
A busiest row opens `EntitySummaryDialog`; the node dialog's on-demand *Summary* reads the Dataset the graph was drawn
from (`summaryDataset`). Everything is read on demand: a large Dataset takes seconds.

**Gotchas.** DuckDB refuses the zone id `Z`: pass `ZoneId.of("UTC")`. The graph's own *Find something interesting*
already owns `discover.ts` / `link-analysis-discover.component.ts` - the Dataset panel is `dataset-discover`. A
component that imports `DataTableComponent` brings `MatDialogModule` into its injector, so a host spec mocking
`MatDialog` needs `TestBed.overrideProvider`. Section landmarks repeated per Dataset need unique names (axe
`landmark-unique`).

**Deliberately not done.** Bridges, Shared counterparts and Bursts presets; a Category value-list filter in the builder
beyond the kind of link (`LA-COLUMN-ROLES-2`); drawing from the Data page (it points to Explore); a live drive on the
Basic install (the plan's §5 check over IMS and Mobile money) waits for a redeploy.

**The Data page's layout and a Dataset's name (`LA-DATA-MENU-1`, `LA-DATA-DETAILS-1`, operator 2026-11-10).** The la-app
Data page is laid out like Settings: a menu of Datasets grouped by kind (Streams, References) on the left, the picked one
on the right (`?dataset=<id>` keeps it across a reload; the first listed shows when none is picked); nothing folds. A
filter box over the menu (shown past six Datasets) keeps those whose name, id or description hold every word typed.
The open Dataset's header shows its name, its id (copyable), its description and facts (kind, origin, columns,
Mappings, Time column); *Rename or describe* opens `DatasetDetailsDialog`, which re-reads the component and writes it
back with only `title` and `description` changed, against its `contentHash` (a concurrent change is refused with the
stale-write message, never overwritten). Opening a Mapping form also brings its base into view.
**Gotcha:** the shown name is stored as **`title`**, never `name` - `ComponentRoutes` stamps `name` with the id on every
write, so a renamed `name` is silently put back. The id never changes (Mappings, saved views and Pipelines refer to it).
The shared `DatasetsService.fromContent` reads `title` first, so every Dataset picker in the app shows the new name, and
a Studio save carries `title` through untouched.
