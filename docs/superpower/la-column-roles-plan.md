# Column roles: several times, measures and categories per Dataset (`LA-COLUMN-ROLES-1`)

> **PLAN 2026-11-04 — decisions Q-1…Q-6 OPEN (recommendations marked, §5).** Operator ask, on the Data page's
> Entities card: *"some of the important fields missing like date (would be more than one), amount, volume, type
> (would be multiple) which would be used for play / filter"*. Board row `LA-COLUMN-ROLES-1`.

## 1. Where we are (read on master `9cf6163be`, 2026-11-04)

A Dataset says two things about its columns today, and neither can hold "several dates, several measures, several
categories":

| What | Where | Shape |
|---|---|---|
| **What a column identifies** ("holds") | registry `columns[].classification` — an Entity Type id or nothing (the Data page's Entities card; `PresetQueries.roles` reads it through `claimed(k, types)`) | one Entity Type per column |
| **How a row becomes a link** | registry `defaultLinkMapping {from, to, kind?, time?, amount?, details?[]}` — `DatasetEntryRules.validate` (`inspecto/src/main/java/com/gamma/control/DatasetEntryRules.java`) | `kind`, `time`, `amount` ONE column each; `details` a list |

A Mapping (`origin: mapping`) carries the same `defaultLinkMapping`, so the same limits apply.

Who reads the link roles:
- **Add data** proposes `from / to / kind / time / amount` per column with evidence and confidence (`EntityShapes.link()`,
  keyword sets `KIND_WORDS` / `TIME_WORDS` / `AMOUNT_WORDS`), and every remaining column as `details`; the UI's Roles step
  shows one choice per role (`add-data.model.ts`, `add-data.component.ts`).
- **The time window** (Investigation filter `window`, server side) and **playback** (`timeline-playback.ts`, client
  side over the loaded links: it reads `e.data.attrs[col]`, so any date column delivered in a link's `attrs` could drive
  it — but nothing offers a second one).
- **Kind**: edge `data.kind` (style, legend) and the filter's `kinds` list (server side, `IndexReader` `kind IN (…)`).
- **Amount**: presets sum it — but see the defect below; the UI draws edge weight from the link COUNT, not the amount,
  and has no amount filter. No second measure (volume, duration, count, fee) exists anywhere.
- **Entity profiles** (`EntityTypeBindings.defaultMapping`) read from / to / kind / time; amount and details are dropped.

What already exists to build on:
- **The link index carries attribute columns.** `IndexMapping(src, dst, kind, time, zone, weight, attributeColumns)`;
  `IndexBuilder` writes `out` / `in` as `bucket, src, dst, kind, ts, w, a<N>…`, and `IndexReader` filters on them with a
  bound `filterSql`. Carrying more columns costs a new mapping hash (a rebuild), wider `out` / `in` tables (each
  attribute is stored twice), and a type rule per column. `pairs_back` and `loops` are unaffected.
- **Links can already carry columns to the UI** (`attrCols` / `timeCol` on the Investigation requests, `inv.service.ts`).

**Defect found while reading (fixed in P0):** `PresetQueries.roles()` validates the mapping's `amount` exists but never
assigns it — the preset answers sum the first column the Industry pack's `measure_hints` name, not the column the
Dataset or Mapping says is the amount. A Mapping chosen for a different amount gets the base's sums.

## 2. The target

1. **Every column has a role**, set once on the Dataset (the Data page; proposed by Add data):
   **Entity** (holds an Entity Type — today's "holds") · **Time** · **Measure** · **Category** · **Detail**.
   A Measure may say what it measures (money, duration, bytes, count) and its unit. There may be several of each.
2. **A link mapping picks its primaries** among those columns: the **time** that dates each link (the window, playback,
   newcomers) and the **measure** that weighs it (presets' sums, edge width). Every other Time, Measure and Category
   column rides on each link without being named again.
3. **Play** can step through any Time column of the link (the primary by default; a picker beside the play control).
4. **Filter** gains, beside today's kinds and window: a **range per Measure** and a **value list per Category**, and a
   window on any Time column. Reads from a published link index filter server side on the carried columns; reads from
   the Dataset filter in SQL.
5. **Entity profiles** sum and range every Measure, not only count links.

## 3. Design notes

- **One place for the meaning of a column.** The role belongs to the column (`columns[].role`, beside
  `classification`), not to each Mapping: today every Mapping would repeat "this is a date". A Mapping reads its base's
  roles and may override one (as it already overrides "holds").
- **Back-compatible read.** An entry without roles reads as today: `classification` set → Entity; the mapping's `time`
  → Time; `amount` → Measure; `kind` → Category; every other column → Detail. No migration is needed for the read; the
  Data page writes roles from then on. (Operator standing preference: breaking changes are free, but an absent field
  should still read sensibly.)
- **`defaultLinkMapping` keeps `from`, `to` and gains `primaryTime` / `primaryMeasure`**; `kind` stays as "the category
  that names the kind of link" (style, legend, the kinds filter). `time` / `amount` become aliases read for old entries.
- **Category means low cardinality.** A text column with thousands of distinct values is a Detail, not a filter list;
  Add data measures distinct counts in its sample (it already samples for confidence).
- **Index carriage is opt-out, not opt-in**: every Time / Measure / Category column becomes an index attribute, so a
  filter or a play column never needs a rebuild to appear. The cost is width; a Mapping may mark a column "not carried".
- **Filter UI follows the form idiom** (`spec-forms-state.md`): a Measure range is two pen-field rows, a Category is a
  picker with search (several values: the picker's multi-select — or `UI-PICKER-OPTIONS-1` if it needs groups).

## 4. Phases

| Phase | What | Size |
|---|---|---|
| **P0** | Fix the `amount` defect in `PresetQueries.roles()` (the mapping's amount, then the pack hint), with a test that fails today. Registry: `columns[].role` (+ measure `unit`) validated in `DatasetEntryRules`; the back-compatible read. Data page: the Entities card becomes **Columns** — role + holds (+ unit) per column | M |
| **P1** | Mappings: `primaryTime` / `primaryMeasure` picked among the role columns; Add data proposes roles per column (several times / measures / categories, distinct counts for categories) and the Roles step shows them | M |
| **P2** | Carry: every role column rides on links (`attrCols`) and in the link index (`attributeColumns`; one rebuild per index); Play picks its Time column | M |
| **P3** | Filter: a range per Measure, a value list per Category, a window on any Time column — server side for index reads, SQL for Dataset reads; Entity profiles sum and range every Measure | M |

## 5. Decisions (open)

- **Q-1 Where a role lives.** (a) on the column of the Dataset, a Mapping inherits and may override ⟵ *recommended*
  (one place for meaning; matches "holds"); (b) on each Mapping only.
- **Q-2 The role set.** (a) Entity · Time · Measure · Category · Detail ⟵ *recommended*; (b) also Geo (lat / lon,
  for the map) and Identifier (a non-entity key) now.
- **Q-3 Measures.** (a) one role, with an optional kind (money, duration, bytes, count) and unit ⟵ *recommended*;
  (b) fixed roles "amount" and "volume".
- **Q-4 Primaries.** (a) a mapping names one primary Time (window, playback default, newcomers) and one primary
  Measure (sums, weight) ⟵ *recommended*; (b) no primaries — every view asks which.
- **Q-5 Index carriage.** (a) every Time / Measure / Category column is carried; a Mapping may opt one out ⟵
  *recommended*; (b) carried only when a Mapping opts in.
- **Q-6 Categories.** (a) a Category is low cardinality (Add data proposes it under a distinct-count bound, say 200);
  a wider text column stays a Detail ⟵ *recommended*; (b) any text column may be a Category.

## 6. Not in scope

Changing what an Entity Type is or how entities merge; geo roles beyond what Q-2 decides; derived measures
(rate = amount / duration) — a calculated column already covers that; the Standard console's Catalog pages (they read the
same registry entry, so they gain the roles read-only).
