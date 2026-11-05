# LA-COLUMN-ROLES-2 — design notes for the three parts that need a decision (2026-11-05)

`LA-COLUMN-ROLES-2` is the deliberate remainder of `LA-COLUMN-ROLES-1` (as built: `architecture.md` §6.15). Of its
four parts, the **Mapping-level override of `carried`** follows from §6.15 and is built without a decision (a Mapping's
own `columns[].carried`, set in the Mapping form, seeded from the base for a new Mapping and from the Mapping when
edited). The other three each had a real choice, put to the operator with a recommendation first.

**Operator decisions (2026-11-05): D-1 b, D-2 a, D-3 b.**
- **D-1 b:** count / sum / min / max per binding × Measure, plus the per-Entity distribution of each Measure's total
  (GROUP BY per Entity, sampled). The sample size and its cap are stated, and live in the existing limits config
  (`entity_profile.*`); a test proves the sample stays bounded on a large synthetic Dataset.
- **D-2 a:** the server sums the primary Measure beside `cnt` (Dataset SQL and the link index); the Display menu gains
  *Width: Count | <Measure>*, default Count.
- **D-3 b:** per-Dataset sections in Filter by role, which needs a filter per Dataset on the server's multi-Dataset
  read - built, with a test that one Dataset's filter does not narrow the others.
- If D-1 b or D-3 b turn out to need an Investigation, Postgres or an edition change: stop and ask.

## D-1 Entity *Type* profile: Measure aggregates

Today one Entity's profile has **Measures** (`EntityProfiles.measures`: per binding, each Measure of that Dataset over
the rows naming the Entity — count, sum, lowest, highest, unit). The Type profile (`typeProfile`, sections `bindings,
volume, shapes, time, lineage, links, relationships`) has none.

- **(a) A `measures` section, one row per binding × Measure** — rows holding a number, sum, lowest, highest, unit, over
  the rows where the bound column names an Entity (non-empty normalised key), inside the profile's window; one
  aggregate statement per bound Dataset (every Measure in it), under the profile's statement timeout and memory limit,
  cached with the other sections. ⟵ *recommended*: it is the one Entity's shape at the type level, cheap (one pass per
  Dataset, no GROUP BY per Entity), and never sums one Dataset's rows twice across bindings because each binding is
  its own row.
- (b) (a) plus the **per-Entity distribution** of each Measure's total (p50 / p90 / top of the per-Entity sums): what a
  typical wallet moves. Needs a GROUP BY over every Entity, the cost class of `relationships` (V4: the union read took
  112 s on a 39 M-row Dataset) — sampling by key hash as `relationships` does.
- (c) One pooled row per Measure name across bindings: simple to read, but a transfer naming two wallets counts twice.

## D-2 Edge width from the link mapping's primary Measure

Today the canvas weighs a link by its count (`edgeSql`: `COUNT(*) AS cnt`, folded on the client by the fold key). The
primary Measure is not on a link: a read carries no role column by default (§6.15 *Carriage*), because `attrCols` are
grouping keys.

- **(a) The server sums it**: when the read's Dataset has a primary Measure, `edgeSql` adds
  `SUM(TRY_CAST(m AS DOUBLE)) AS total` beside `cnt` (no grouping change, so links do not unfold), the link-index read
  sums the carried column the same way (`IndexedRead`; the index carries every Measure by default), the response's edge
  gains `total`, the client fold adds totals, and the Display menu gains **Width: Count | <Measure name>** (default
  Count, so nothing changes until asked). A link whose rows hold no number has total null and is drawn thinnest. ⟵
  *recommended*.
- (b) Width by the Measure always when there is one — changes every existing Dataset's picture without asking.
- (c) Client-only: carry the Measure and sum on the client — unfolds links (the reason carriage is off), so no.

## D-3 Category values for a read over several Datasets

Today the Filter by role panel is empty when the read spans several Datasets (`roleColumns` reads one
`loadedDataset()`); the filter it writes is one predicate tree pushed as one read's `filter`.

- **(a) Only the Categories every Dataset of the read shares by name**, values read per Dataset (one SQL per column per
  Dataset, at most 200 each) and merged; Measures and Times likewise only when shared by name. The one predicate tree
  then names columns every Dataset has, so pushing it to each Dataset's read stays valid. ⟵ *recommended*: no new
  filter model.
- (b) Per-Dataset sections in the panel, each writing a per-Dataset filter — needs a filter per Dataset on the
  multi-Dataset projection, which the server does not have (one `filter` per read today).
- (c) Leave it: multi-Dataset reads stay without the panel (close the part as won't-do).
