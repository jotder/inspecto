---
type: Concept
title: Step catalog — every Step kind, delivered and pending — Binning to sink — data, privacy and routing Steps
description: Part 3 of 5 of Step catalog — every Step kind, delivered and pending: binning → transform.data.binning — Histogram & quantile binner; correlation → transform.stats.correlation — Pearson correlation & linear regression; dedup → transform.dedup — Record deduplicator; join → transform.join — Reference join; sql → transform.sql — the Record Transformer; summarize → transform.summarize — Group-by …
tags: [pipeline-graph, steps, catalog, processors, palette, examples, roadmap]
timestamp: 2026-09-06T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-19 last-edit
code: platform/inspecto-etl
---

# Step catalog — every Step kind, delivered and pending — Binning to sink — data, privacy and routing Steps

Part 3 of 5 of [Step catalog — every Step kind, delivered and pending](spec-step-catalog.md) · previous: [Forecast to outlier — analytics Steps](spec-step-catalog-analytics-steps.md) · next: [Webhook and Excel sinks, and the non-chain processors](spec-step-catalog-outbound-sinks.md).

## `binning` → `transform.data.binning` — Histogram & quantile binner

*Added 2026-10-17 (ANALYTICS-CORE-1 S3, [analytics-kernel § Distribution Steps](../analytics/spec-analytics-distribution-time-series.md#distribution-steps)).*

**Function.** Every row gains a **bin number** (0 … bins − 1) and a **label** (`[lower, upper)`) for a number column —
amount bands, age bands, deciles.

**Behaviour.** The SQL is the Analytics Kernel's (`Binning`), a JVM and a DuckDB form held equal by a parity test.
- **`equal_width`** (default) — `bins` bins of equal width over the group's minimum … maximum, or over a stated `min`
  / `max`. Bin `i` is `[lo + i·w, lo + (i+1)·w)`; **the last bin is closed**, so the maximum is in it. A value equal to
  an edge opens the next bin. All values equal: bin 0. **Out of range** (a stated range only): a value below `min` or
  above `max` gets **no bin** (empty number and label) — never clamped into the end bins.
- **`quantile`** (equal frequency) — the edges are the group's quantiles `Q(i / bins)` (`quantile_mode` `continuous`,
  the default, or `discrete`); the bin is the number of interior edges at or below the value, so **ties always share a
  bin** (the higher one when a value equals an edge) and heavy ties can leave a bin empty. Deterministic — not `ntile`,
  which splits ties at random.
- **Per group** with `group_by` (a NULL key is a group of its own). An empty value gets no bin. The label prints the
  edges as DuckDB prints a `DOUBLE` (`[250.0, 500.0)`), `]` for the last bin.
- The column must be a number; `bins` 1–1,000. `min` / `max` serve equal width only, `quantile_mode` quantile only —
  the other method's keys are checked but have no effect. Every row is kept. Runs **at rest**; not admitted mid-branch.
- **Histogram:** no separate Step — `binning` followed by `summarize` (`group_by: [bin, bin_label]`, `measures:
  [count]`) is the histogram, and `ColumnProfiler.histogram` (the profiler's one-column histogram) computes the **same
  equal-width bin** (one shared expression); that profiler form also lists empty bins.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `column` | string | required | — | **Number column.** The number binned. |
| `bins` | number | required | — | **Bins.** How many, 1–1,000, numbered from 0. |
| `method` | select | optional | `equal_width` | **Method.** `equal_width` · `quantile`. |
| `group_by` | list | optional | — | **Groups.** Bin each group over its own range or quantiles. |
| `min` | number | advanced | — | **Range from.** Equal width only; below it, no bin. |
| `max` | number | advanced | — | **Range to.** Equal width only; above it, no bin. |
| `quantile_mode` | select | advanced | `continuous` | **Quantiles.** Quantile only: `continuous` · `discrete`. |
| `bin_column` | string | advanced | `bin` | **Bin column.** Receives the bin number. |
| `label_column` | string | advanced | `bin_label` | **Label column.** Receives `[lower, upper)`. |

Refused at save, at compile and at run with the same words (`DistributionStep.parseBinning`): an unknown key, no
`bins` or one outside 1–1,000, an unknown method or quantile mode, `min` not below `max`, equal output names, a
non-identifier column, and (against the declared schema) a column it lacks, an added column that collides, a text
column.

**Example.** `examples/07-steps/outliers` — after the outlier flags, `bins: 4, min: 0, max: 1000` puts each payment in
a 250-wide band; `P14` 1250.00 is above the range and gets none.

**Release bar (2026-10-17): met.** (1) this section · (2) `RowShaperDistributionTest`,
`PipelineJobRunnerTest#runsAFlatConfigsOutlierAndBinningStepsAndWritesFlagsAndBins` · (3)
`NodeConfigNameContractTest#distributionAttributesSurviveTheSave`, `PipelineEditableTest`; the dry run walks it through
the same `RowShaper` arm · (4) palette `addable` · (5) Base, every edition · (6) `StepConfigSaveFindingsTest` · (7)
`examples/07-steps/outliers` (above).

## `correlation` → `transform.stats.correlation` — Pearson correlation & linear regression

*Added 2026-10-17 (ANALYTICS-CORE-1 S3, [analytics-kernel § Distribution Steps](../analytics/spec-analytics-distribution-time-series.md#distribution-steps)).*
Catalog `transform.stats.correlation` is **DELIVERED**: Pearson (default) and Spearman (`method: spearman`, 2026-10-18).

**Function.** How strongly two number columns move together, and the straight line that fits them — per group, for
every pair of the listed columns. **Summarising**: the output is one row per group per pair, not the input rows.

**Behaviour.** DuckDB's `corr`, `regr_slope`, `regr_intercept`, `regr_r2`, `regr_count` (`CorrelationSql`), whose JVM
definitions are `Stats.correlation` / `Stats.linearRegression`.
- **Pairs:** for `columns: [A, B, C]` the pairs are (A, B), (A, C), (B, C), in that order; in each the **later column
  is regressed on the earlier** (`B = intercept + slope · A`).
- **Output columns:** the `group_by` columns (their own names and types), `x_column`, `y_column` (the pair's names),
  `n` (pairs with **both** values present), `correlation` (Pearson's r), `slope`, `intercept`, `r2`. Rows ordered by
  the group values (empty key last), then the pairs as listed.
- **No number is empty, never NaN:** a constant side (one pair included) has no correlation; a constant x has no line;
  a constant y with a varying x has slope 0 and `r2` 1 (a flat line fits it exactly — DuckDB's rule). No complete pair:
  `n` 0 and every figure empty.
- ⚠ DuckDB's streaming covariance loses digits when the values sit on a large common offset (measured: slope off by
  about 5e-12 relative at 1e6, 1.7e-9 at 1e9, 5e-6 at 1e12); subtract a reference first for such data.
- The columns must be numbers; 2–50 columns (up to 1,225 pairs); a group column may not also be correlated nor take an
  output column's name. Runs **at rest**; not admitted mid-branch.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `columns` | list | required | — | **Number columns.** Every pair is correlated. |
| `group_by` | list | optional | — | **Groups.** One row per group per pair. |
| `method` | select | optional | `pearson` | **Method.** `pearson` (on the values) or `spearman` (on the within-group average ranks; ties share their average rank). Spearman's `slope` / `intercept` / `r2` describe the line through the ranks. |

Refused at save, at compile and at run with the same words (`DistributionStep.parseCorrelation`): an unknown key, fewer
than 2 or more than 50 columns, a column named twice, a group column that is also correlated or named like an output
column, and (against the declared schema) a column it lacks or a text column.

**Example.** `examples/07-steps/correlation` — 10 orders, `columns: [PRICE, QUANTITY, DISCOUNT]` by `REGION`: 6 rows;
NORTH price → quantity r = −0.9995, slope −2.4265.

**Release bar (2026-10-17, Spearman added 2026-10-18): met.** (1) this section · (2)
`RowShaperDistributionTest`, `PipelineJobRunnerTest#runsAFlatConfigsCorrelationStepAndWritesOneRowPerPair` · (3)
`NodeConfigNameContractTest#distributionAttributesSurviveTheSave`, `PipelineEditableTest` · (4) palette `addable`
(delivered, with a node type) · (5) Base, every edition · (6) `StepConfigSaveFindingsTest` · (7)
`examples/07-steps/correlation`, run with `serve-example --demo --check-jobs` (job SUCCESS, 6 rows as its `probes.txt`
states).

## `dedup` → `transform.dedup` — Record deduplicator

**Function.** Record-grain distinct by business key — one row per `keys[]` value. Not file dedup:
that is the Collector's `duplicate:` policy / marker `duplicate_check`, a Guarantee that rides the
Collector.

**Behaviour.** `ROW_NUMBER() OVER (PARTITION BY keys ORDER BY order_by)`: the first row per key is
kept as `data`, the rest form a counted `duplicate` relation. With `scope: window(P4D)` the winners are
also claimed in the durable dedup ledger so a key admitted by an earlier Consignment inside the window
is suppressed too (D-9, at rest only); a window without `order_by` refuses, at save and at run, because
a ledger must never record a non-deterministic winner. Mid-branch only the consignment scope arms.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `keys` | list | required | — | **Dedup keys.** Rows sharing these column values are duplicates; the first (per "Order by") is kept. |
| `order_by` | string | optional | — | **Order by.** Which duplicate wins — SQL ordering over the typed columns; blank = input order. REQUIRED once Scope declares a window: a durable ledger must never record a non-deterministic winner. |
| `scope` | string | advanced | — | **Scope.** How far back a key is a duplicate: blank/consignment = within this Consignment only; window(<ISO-8601 period>), e.g. window(P4D), also suppresses keys admitted by earlier Consignments inside that window via the durable dedup ledger (runs at rest). |

**Example.** `examples/07-steps/dedup` (12 → 9 rows, newest version of each order) ·
`spaces/default/config/dedup_step` · `examples/07-steps/dedup-window` (`scope: window(P4D)`, two drops: 8 rows, then 1 — the three re-sends
suppressed by the ledger). ⚠ The window is a **tumbling** bucket anchored on the epoch
(`DedupScope.Window.startFor`), keyed on the `order_by` column's date — a re-send is suppressed only when it
falls in the same bucket as the original, not whenever it is within the period of it.

## `join` → `transform.join` — Reference join

**Function.** Enrich rows from a **Reference Dataset** by key.

**Behaviour.** Always a `LEFT JOIN … USING(on)`: an unmatched key keeps its row with NULL reference
columns, never a silent drop; a non-key column shared by both sides fails loudly rather than writing an
ambiguous result. `reference` is `reference/<pipeline>` (a versioned reference store read through
`ReferenceReader`, current/as-of like the Stage-2 enrichment) or a plain CSV/Parquet path. The recipe's
plural `references/<pipeline>` is the same by-name binding everywhere — one test,
`com.gamma.etl.ReferenceBinding`, shared by `PipelineConfigParser`, `ReferenceReader.parse`,
`RecipeCompiler` and `RecipeConverter`. ⚠ Until 2026-09-24 only the parser accepted the plural, so a
hand-written flat config saying `references/x` loaded as a name and then failed at run time as a missing
FILE (`No files found that match the pattern "references/x"`); compiled recipes were unaffected. The
reference is resolved once per run into a scratch view. **At rest only**: the ingest walk carries no
reference resolver, so a mid-branch join refuses with the remedy *run it at rest via output_store:*.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `reference` | autocomplete | required | — | **Reference.** The registered Reference component joined onto the row set. |
| `on` | list | required | — | **Join keys.** Column(s) equated between the rows and the Reference. |

**Example.** `examples/07-steps/join` (8 rows, 6 enriched, WEST deliberately unmatched) ·
`spaces/default/config/join_step` over `spaces/default/data/ref/region_dim.csv`.

## `sql` → `transform.sql` — the Record Transformer

**Function.** One author `SELECT` over the typed input relation, addressed by the fixed alias
`input`; the engine rewrites it to the real relation at execution. This is also the **projection slot**
after every parser (the schema's `mapping.fields[]` compiles to it) and the Record Transformer of the
editor: `transform.record` in the processor catalog, folded 2026-09-04 from expression, cast and
trim processors — one grid, one compiler.

**Two nodes of this kind (TRANSFORMER-TWO-NODES-1, 2026-11-15).** "Record Transformer" names the Step kind,
never an instance. The projection slot (id `map` / `map_<key>`, saved as `processing.map.fields[]`, Stage 1)
is the instance `PipelineLift` names **"Shape &lt;table&gt;"**, or **"Shape records"** when no table is known.
A Record Transformer added from the palette is a chain Step (`steps: - sql:`) that runs after load, at rest
(`PipelineLift.stageTwo`, from a `type: pipeline` Job), writing the top-level `output_store:`. A chain with no
`output_store:` is refused at save, one finding per Step: `ERR_CHAIN_WITHOUT_OUTPUT_STORE` on an active
Pipeline (422), `WARN_CHAIN_WITHOUT_OUTPUT_STORE` on an inactive draft; a chain with a store that no
enabled `type: pipeline` Job runs saves with `WARN_CHAIN_WITHOUT_PIPELINE_JOB`
([config safety](../config/spec-config-safety.md), "A chain with no output store").

**Behaviour.** A node carrying `fields[]` / `columns` / `rules` is a **projection** and compiles
through `RecordTransform` (the same `[{name, expr}]` seam the browser's grid renders); a hand-written
`sql` string takes the opaque path: `SqlGuard.check` refuses anything but a read-only SELECT (no
DDL/DML, one statement), and saving one yields the `SQL_STEP_UNAUDITED` warning (a WARNING never blocks
a save). Types are derived without a run by `POST /components/transform/describe` (`TypeFlow.describe`,
execution-free DESCRIBE); only a DuckDB binder 422 blocks Apply. In the editor the Step is two peer
views — a **Fields** grid over the function catalog and the **SQL** — with the bounded reconciler
(projections only, catalog templates matched in reverse and re-compiled to prove the round-trip, the
rest kept verbatim as custom rows; a `WHERE`/`JOIN` disables the Fields tab with the reason)
([ETL user guide](user-guide-elt.md) §8.2).

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `sql` | multiline | required | — | **SQL.** One SELECT statement over the typed input relation, addressed by the fixed alias 'input' (FROM input) — the engine rewrites it to the real relation at execution. No DDL/DML, no multiple statements. |

An authored `fields[]` (`{id, name, from, fn, args}` over `sql-functions.ts`) may ride beside `sql`;
the engine reads only `sql`.

**Example.** `examples/07-steps/sql` (8 → 7 rows, NET and SIZE_BAND derived) ·
`spaces/default/config/sql_step`; the projection form is every `*_schema.toon` `mapping.fields[]`.

## `summarize` → `transform.summarize` — Group-by summarizer

**Function.** A GROUP BY rollup with algebraically composable measures.

**Behaviour.** Measures speak the **one measure grammar** (`measure-grammar.contract.json`) that the
BI query and a materialize Job also use: `count` | `agg(field)` with `agg` ∈ count · countDistinct ·
sum · avg · min · max · median · stddev · variance · p05 · p25 · p75 · p90 · p95 · p99; result columns are the stable `agg_field` ids (`count`, `sum_gross`,
`max_unit_price`). `group_by` is optional — omit it for one grand-total row. Compiled by
`MeasureCompiler`, so a summarize node, a materialize Job and a dashboard query cannot drift.
Arms mid-branch.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `group_by` | list | required | — | **Group by.** Grouping columns of the rollup. |
| `measures` | list | required | — | **Measures.** Aggregate expressions computed per group. |

**Example.** `examples/07-steps/summarize` (8 rows → 4 REGION groups) ·
`spaces/default/config/summarize_step`.

## `profile` → `transform.profile` — Per-column profiler

**Function.** Per-column row/null/distinct counts and min/max over the inbound data.

**Behaviour.** One output row per profiled column — `column_name`, `row_count`, `null_count`,
`distinct_count`, `min_value`, `max_value` (the two bounds cast to VARCHAR so one result table can
hold every column type). `columns` is optional: leave it empty and every inbound column is profiled,
which is why an empty list is WRITTEN rather than dropped on save — dropping it would lower back a
narrower document than was authored. Naming a column the inbound data does not have is refused, not
silently skipped. Like `summarize`, it executes **at rest** over the landed store, so an active
pipeline carrying it must declare `output_store:` (`stage-two-blocks-require-output-store`).

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `columns` | list | optional | — | **Columns.** Columns to profile. Leave blank to profile every inbound column. |

**Example.** `examples/07-steps/profile` (8 rows → 3 statistics rows: REGION, QUANTITY, GROSS; added
2026-09-24 — until then the profile Step was the one Step kind with no example).

## `running` → `transform.running` — Running calculations & moving windows

*Added 2026-10-06 (catalog `transform.analytics.running`; the operator lifted the Step Processor hold and
named this one first (operator, 2026-10-06)).*

**Function.** A rolling calculation per key: `count`, `sum`, `avg`, `min` or `max` of a column over the
rows of the same partition that lie inside a window ending at the current row, ordered by a time column.
Every inbound row is kept and gains one column per measure. Velocity, moving average and rolling total
are this step.

**Behaviour.** It compiles to ONE DuckDB window query:
`SELECT *, fn(col) OVER w AS "as" … FROM input WINDOW w AS (PARTITION BY … ORDER BY "order_by" <frame>)`.

- **Duration window** (`window: 60m`, units `s` · `m` · `h` · `d`): `RANGE BETWEEN INTERVAL n <unit>
  PRECEDING AND CURRENT ROW`. Both ends are **inclusive**, so a row exactly 60 minutes earlier counts.
  `order_by` must be a `TIMESTAMP`, `TIMESTAMP WITH TIME ZONE` or `DATE` column. Any other type is refused.
- **Row window** (`window: 5 rows`, or a bare integer `5`): `ROWS BETWEEN 4 PRECEDING AND CURRENT ROW`,
  meaning the current row and the 4 before it. Rows with equal `order_by` values have no defined order between
  them, so a row window over ties is not deterministic. Use a duration window, or order by a unique column.
- A row whose `order_by` is NULL is framed only with the other NULL rows of its partition.
- `count` with no `column` is `count(*)`. `count(col)` skips NULLs, as do the other four functions.
- **State is per run, never across runs.** The window sees only the rows this one run reads. A key's
  activity in an earlier batch is invisible, so a burst that spans two batches is under-counted. ⚠ For
  velocity across batches, keep using the SQL-over-sink approach: a `sql.template` Job over the landed
  Dataset, as the payment pack's `pf_instrument_velocity` does.

**Safety.** Nothing the author writes is spliced into SQL as text. Every column name (`partition_by`,
`order_by`, `measures[].column`, `measures[].as`) must match `[A-Za-z_][A-Za-z0-9_]*` and is
double-quoted. `fn` is one of five fixed words. The window size is parsed to a positive integer and
re-printed, and the unit maps to a fixed DuckDB keyword. The run refuses a column the inbound data lacks,
and an `as` that collides with an inbound column or a sibling measure. The same checks run at save, in
`ConfigRoutes.stepConfigFindings` (ERROR when active, WARNING on a draft).

**Configuration.** `partition_by`, `order_by` and `window` are form attributes. `measures` is a list of
maps (`{fn, column, as}`), and the shared `list` widget authors only strings. So `measures` has no
attribute spec, exactly like `transform.route`'s `branches`. It is authored in the flat file or the recipe,
and the node form's additional-config editor carries it through a save unchanged.

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `partition_by` | list | optional | — | **Partition by.** The key columns. Each key gets its own window. Leave it blank to treat the whole batch as one key. |
| `order_by` | string | required | — | **Order by (time column).** The column the window slides along. A duration window needs a TIMESTAMP or DATE column. |
| `window` | string | required | — | **Window.** A duration (`60m`, `24h`, `30s`, `7d`) or a row count (`5 rows`). |
| `measures` | list of `{fn, column, as}` | required | — | `fn` ∈ count · sum · avg · min · max. `column` is optional for `count` only. `as` names the output column. |

It runs **at rest**, like every `steps:` kind, so an active pipeline carrying it needs `output_store:`.
It does not arm mid-branch (`RouteArming.BRANCH_STEP_KINDS`).

**Example.** `examples/07-steps/running`: card-payment attempts get a 60-minute attempt count and amount
sum per `INSTRUMENT_TOKEN`, a single-batch version of the payment pack's instrument velocity.

## `hash` → `transform.hash` — Salted one-way hasher

*Added 2026-10-06 (catalog `quality.crypto.hash`, operator 2026-10-06).*

**Design (decided before build).**
- **Namespace.** The node type is `transform.hash` with `steps:` kind `hash`, and the catalog id stays
  `quality.crypto.hash`. `NodeTypeStepKinds.isKnown` only recognises `transform.<kind>`; that is the trap the
  profile Step hit.
- **Flat home.** It is `steps:` only (`FlatHome.STEP`) and has no legacy `processing.*` block. Like `lookup` and
  `sql`, a chain carrying it is never "legacy-shaped".
- **Key.** The key is the Space's own mask key, `EvidenceMasker.forSpace(SpaceConfigRoot.current())`. It is 32
  random bytes in `<config root>.secrets/.risk-score-mask.key`, created on first use. The token is
  `masked:<16 hex>` of an HMAC-SHA-256 (64 bits), the SAME token Link Analysis and audit masking give that
  value. A hashed column therefore joins with itself across runs and with those surfaces. This key was chosen
  over `FailureText`'s `value-fingerprint.salt` because that salt is resolved from a `PipelineConfig`'s dirs,
  which a `RowShaper` node does not hold. It also sits beside config rather than in the Space's secrets
  (operator, 2026-10-06).
- **The key never enters SQL.** DuckDB 1.5 has no HMAC, and inlining the key as a literal would put it in
  query text, profiles and error messages. Instead each column's DISTINCT values are hashed in Java, written to
  a scratch `TEMP` map table, and LEFT JOINed back. The map is DROPPED in a `finally`: left behind, it would be a
  reversal table. ⚠ Memory cost: one Java string per distinct value per column per batch. That is acceptable
  at batch grain and would not be at a whole-history rebuild.
- **Fail closed.** A run with no Space config root is refused, never hashed unkeyed. An unknown column is
  refused, because hashing four of five named columns ships the fifth in the clear. A `salt:` / `key:` key is
  refused at compile and at save. Every refusal names the column and never a value.

**Behaviour.** Each named column is replaced in place by its token (the column becomes VARCHAR), or with
`keep_original: true` the column stays and `<col>_hash` is added beside it. NULL stays NULL. Row counts never
change. The same value gives the same token within a Space and a different token in another Space.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `columns` | list | required | — | **Columns to hash.** Each named column is replaced by a salted one-way token (masked:<16 hex>), the same within this Space so it can still be joined on. |
| `keep_original` | boolean | optional | — | **Keep the original column.** When on, the original column stays and the token is added as <column>_hash. Off (the default) replaces the value in place. |

**Refused at save** (`ConfigRoutes.stepConfigFindings`): no `columns`, any key other than the two above (a
`salt` by name), or a column the declared schema does not carry.

**Example.** `inspecto/examples/07-steps/hash` hashes `ORDER_ID` (8 rows in, 8 rows out).
**Tests.** `RowShaperHashTest` checks determinism, the Space-key equality, cross-Space difference and NULL. Its
leak probes check that no raw value or key bytes appear in any table left behind, and that no value appears in
refusal text. `StepConfigSaveFindingsTest`, `RecipeCompilerTest` and
`NodeConfigNameContractTest.hashAttributesReachTheEngine` cover the save gate, compile and round trip.

## `mask` → `transform.mask` — PII masking (🔒 Enterprise)

*Added 2026-10-06 (catalog `quality.pii.mask`, board SEC-08, operator 2026-10-06).*

**Design (decided before build).**
- **Choosing columns.** Columns are chosen BY CLASSIFICATION, BY NAME, or both (a union).
  - By classification: `classifications: [PII, MSISDN]` with `dataset: <registry id>`. A column's class is the
    Dataset's own `columns[].classification` merged with `EvidenceMasker.lineageClassification`. That covers
    sibling Datasets over the same store and the pipeline schemas behind it, the resolver `publish.postgres` and
    evidence masking already share.
  - Several classes on one column resolve **strictest-wins** (`ColumnClassification.stricter`). The SELECTED set
    is the masked predicate, so a selected class outranks a stricter unselected one. Example: a column that is
    PII on one Dataset and MSISDN on its sibling is masked by a PII-only mask.
  - Untraceable lineage (`UNKNOWN_LINEAGE`) is refused rather than guessed.
- **Modes.**
  - `full` writes `****`.
  - `partial` writes stars plus the last `keep_last` characters (default 4). A value no longer than `keep_last`
    is masked whole.
  - `hash` delegates to `transform.hash`'s Space-keyed token.
  - NULL stays NULL.
  - ⛔ There is **no reversible tokenization vault** (operator 2026-10-06), and a `vault:` key is refused by name.
- **Edition (SEC-08).** `EditionFeatures.PII_MASK` (`quality.pii.mask`) is declared only by `inspecto-policy`
  (`PolicyEditionFeatures`), the module only the Enterprise and Preview flavours bundle. It follows the G9 pattern:
  - The save gate (`pipelineRefusals(draft)`, route-branch steps included) refuses with `ERR_EDITION_FEATURE`.
  - So does the pipeline load (`pipelineRefusals(cfg)`).
  - `RowShaper.mask` itself calls `EditionFeatures.require` before reading a value.
- **One rule list.** `MaskSpec.problems` is shared by `RecipeCompiler` and the save gate, so the shape rules
  cannot drift.
- **Fail loud.** These are refused, each naming columns or classes and never a value:
  - an unknown named column;
  - an unknown Dataset;
  - no Space bound while classifications are set;
  - a mask that resolves to NO inbound column.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `classifications` | list | optional | — | **Mask classifications.** Mask every column the Dataset classifies as one of these (MSISDN, IMSI, ACCOUNT, PII). A column with several classes is judged by its strictest. |
| `dataset` | string | optional | — | **Classified Dataset.** The registry Dataset whose column classifications (and their lineage) choose the columns. Required with classifications. |
| `columns` | list | optional | — | **Mask columns.** Columns masked by name, in addition to any chosen by classification. |
| `mode` | select | optional | `full` | **Mode.** `full` / `partial` / `hash`. |
| `keep_last` | number | optional | 4 | **Keep last N characters.** Partial mode only. |

**Example.** `inspecto/examples/07-steps/mask` (Enterprise build). `write/registry/datasets/orders.toon`
classifies `ORDER_ID` as ACCOUNT, and the run masks it to `**01`…`**08` (8 rows in, 8 rows out). On 2026-10-06
`serve-example --demo` was run with `inspecto-policy` on the classpath. `ShippedExamplesPassTheSaveGateTest` pins
that a Personal build refuses exactly `steps[0].mask`.

**Tests.**
- `RowShaperMaskTest`:
  - by classification;
  - by lineage;
  - strictest-wins with the selected set;
  - partial and keep_last;
  - hash delegation;
  - leak probes over every table left and every refusal;
  - the run-time edition refusal.
- `EditionFeaturesTest`: chain and branch refusals; Professional features do not lift the refusal.
- `PolicyEditionFeaturesTest`: the Enterprise module declares it.
- `ProfessionalSinkFeaturesAcceptedTest`: a Professional module does not declare it.
- `StepConfigSaveFindingsTest`, `RecipeCompilerTest` and `NodeConfigNameContractTest.maskAttributesReachTheEngine`.

Three mutations each turn the suite red: dropping the run-time gate, dropping the branch walk, and using the plain
restrictiveness order instead of the selected-set predicate.

## `route` → `transform.route` — Router

**Function.** Content-based routing into operator-defined branches, each writing its own destination.

**Behaviour.** `mode: case` labels each row by the first matching branch `where:` (first match wins)
and sends the rest to `default:`; `mode: clone` lets a row leave on every matching branch. Row
conservation holds: in = Σ branches. Branches pair with `sinks[]` destinations **by `database`** — the
join key — so every armed branch needs a `key` and a `database` matching exactly one `sinks[]` entry,
exactly one branch is the default, and a duplicated database refuses (rows would land nowhere).
A branch may chain its own `steps[]` (filter · consignment-scope dedup · summarize) before its sink.
Route lives on the **ingest lane** (the branch-aware executor gives each branch key its own sink), so
the at-rest lift refuses a route step; switching a branch's sink off *parks* its rows for a later
*drain* ([step-park-drain](spec-pipeline-execution.md)). Multi-schema pipelines cannot arm a route.

**Configuration.** The contract exposes `mode`; keys, predicates and destinations are the branch rows:

```
sinks[2]{database,format}:
  <dir-a>,CSV
  <dir-b>,CSV
route:
  mode: case
  default: <key>
  branches[2]{key,where,database}:
    <key-a>,"<predicate>",<dir-a>
    <key-b>,"<predicate>",<dir-b>
```

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `mode` | select (case · clone) | required | `case` | **Route mode.** Branch keys, predicates and destinations are edited on the branch rows in the Recipe view. |

**Example.** `examples/07-steps/route` (domestic / other, `CENTRAL` caught by the default) ·
`spaces/default/config/route_step`.

## `sink` → `sink.persistent` — the resting store

**Function.** Writes the batch to a resting store: Parquet (snappy / zstd / gzip) or CSV files,
Hive-partitioned `year=/month=/day=` by the schema's `partitionKey` (a non-date key lands in the
default partition), or a DuckLake catalog (`output.ducklake`, PostgreSQL sidecar, Professional+).

**Behaviour.** The store name (`mapping.canonicalName`) is the join key everything downstream reads —
Jobs, enrichments, Datasets. `filename_column` stamps each row with its source file (lineage also
lives in the per-file / batch / lineage ledgers regardless). `priority` is a **share** weight when
execution slots are contended, never precedence; the `intake.*` caps are this pipeline's admission
override of the fleet-wide `-Dingest.maxFilesPerCycle`. A pipeline has one primary sink
(`dirs.database` + `output:`) or several through `sinks[]` — several are constructible and liftable,
but execute only when a `route:` pairs them.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `database` | string | required | — | **Database directory.** Directory where committed batches land. The pipeline's primary sink must set this; a quarantine sink writes unmatched files to 'dir' instead. |
| `format` | select (CSV · PARQUET) | required | `PARQUET` | **Output format.** Stage-1 output file format; absent = Parquet (the engine default). A CSV store is text: it reads back VARCHAR whatever the schema declares. |
| `compression` | string | optional | — | **Compression.** Codec for the output (e.g. snappy / zstd / gzip); blank = format default. |
| `filename_column` | identifier | advanced | — | **Source filename column.** Adds a column of this name carrying each row’s source file. New pipelines default to file_name; blank = no column (lineage stays in the ledger only). |
| `priority` | number | advanced | — | **Priority.** Share weight (1-3) for this pipeline's consignments when execution slots are contended: 3 gets ~3x the throughput share of 1. Shares, never precedence — a priority-1 pipeline always keeps making progress. Blank = 1. |
| `pool` | string | advanced | — | **Execution pool.** Named execution pool this pipeline's consignments are admitted in. Pools are defined server-side (scheduler settings); admission only — a pool never widens the thread or memory caps. A name the server does not define runs in 'default'. Blank = default. |
| `intake.max_files_per_cycle` | number | advanced | — | **Intake cap (files/cycle).** This pipeline's admission cap, overriding the -Dingest.maxFilesPerCycle global (default 500, operator 2026-10-06); 0 = explicitly unbounded (exempts this pipeline from a fleet-wide cap). Blank = inherit the global. |
| `intake.min_files_per_cycle` | number | advanced | — | **Intake cap floor.** Floor the adaptive controller may halve this pipeline's cap down to. Blank = inherit -Dingest.minFilesPerCycle. |
| `intake.adaptive` | boolean | advanced | — | **Adaptive intake control.** Whether cycle overrun adjusts this pipeline's cap; off pins it at the stated cap. Blank = inherit -Dingest.backpressure.adaptive. |
| `ducklake.enabled` | boolean | advanced | — | **Register in DuckLake.** After each committed batch, register the written Parquet files in a DuckLake catalog. Blank = off. Needs catalog URL, data path and table. |
| `ducklake.catalog_url` | string | advanced | — | **Catalog URL.** The DuckLake catalog backend, attached as ducklake:<this>. |
| `ducklake.data_path` | string | advanced | — | **Data path.** The catalog's DATA_PATH. A relative path resolves under the Space directory; an object-store URI is kept as written. |
| `ducklake.schema` | string | advanced | — | **Schema.** Catalog schema the table is created in. Blank = main. |
| `ducklake.table` | string | advanced | — | **Table.** Catalog table the files are inserted into; a batch that lands in a table sub-directory uses that name instead. |

The `ducklake.*` keys (the "DuckLake catalog" group, added 2026-09-23) lower to `output.ducklake:`, or to
the node's own `sinks[]` entry. An entry without its own block inherits `output.ducklake`
(`PipelineConfig.resolveSinks`), and `DuckLakeRegistrar` registers each sink's files in that sink's lake.
`ConfigSafetyValidator` refuses an enabled block that lacks `catalog_url`, `data_path` or `table` (and
path-jails `data_path`). The `ducklake.*` leaves are not declared in `ConfigSpecs.pipeline()`: `output` is
accepted whole. On one node a failed registration is logged and the batch succeeds. With
`-Dinspecto.topology=partitioned`, a failed or missing registration fails the batch. The rules are in
`DuckLakeRegistrar.requireRegistrationConfigured` and `onRegistrationFailure`.

**Example.** `examples/07-steps/sink` (CSV + `SOURCE_FILE`) · `04-output/csv-output` ·
`spaces/default/config/sink_step` · `_reference/ducklake-sink` (`output.ducklake`, needs a PostgreSQL
catalog — shape-correct, not runnable offline).

---
