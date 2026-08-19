# Plan — Delimited Grammar properties: 4-tab surface, typed columns table, CSV round-trip

**Status:** IN FLIGHT — grounded 2026-08-19; operator approved 2026-08-19 with the recommended
decisions (D1 (b) · D2 Auto · D3 synonyms ∪ names · D4 refuse · D5 keep 4 · D6 leave in place).
· **Opened:** 2026-08-19
**Parent item:** operator request 2026-08-19 (redesign of the delimited parse-step properties on the
pipeline canvas). · **Concept home on ship:**
[`../okf/frontend/features/grammar-config.md`](../okf/frontend/features/grammar-config.md) +
[`../okf/backend/config/parsing-options-reference.md`](../okf/backend/config/parsing-options-reference.md)

> **Vocabulary (binding, `docs/GLOSSARY.md:294-318`):** this surface authors a **Grammar** — the
> options telling a Parser how to read one format. ⛔ "parser config" / "parse options" never appear
> in UI copy. The operator-specified export filename `<pipeline>_parser.csv` is kept verbatim (a
> filename is not UI copy; noted in §8).

## 1. Objective (operator's ask, restated)

For the **delimited** parse step's properties on the pipeline canvas:

1. Reorganize the options into **4 tabs**: *Dialect / parsing* · *Types & columns* ·
   *Robustness / error handling* · *Files & metadata*.
2. *Types & columns* holds the **columns table populated from Test parse** (select columns, set a
   data type per column), plus a **"Data types: Auto"** mode that locks per-column type editing.
3. **Drop Name/Description** from the surface; **replace "Save as template…" with CSV
   export/import** of the whole property set, filename `<pipeline_name>_parser.csv`; importing the
   CSV repopulates the properties.
4. A **maximize** affordance (the configuration set is big).
5. Redesign the post-Test-parse **metadata definition table** to the column order: ① selection box
   ② column sequence ③ data type as an **icon only** (click pulls up the type list — no classic
   dropdown) ④ column name ⑤ **Synonym** (new editable field, unique).
6. *Files & metadata* carries **no glob/path options** — file selection is Collection's job
   (consignment Selector).

## 2. Grounding — the as-built facts this plan stands on

**Where the surface lives.** The modern delimited surface is the right-dock **drawer**
`PipelineParseDefinitionComponent` (`modules/admin/pipelines/pipeline-parse-definition.component.ts`,
hosted by `pipeline-editor.component.html:557-613`); `GrammarEditorDialog` remains only for a
dangling `use: grammar/<id>`, binary fixed-width, and the legacy generic `parser` type
(`pipeline-editor.component.ts:1525-1610`). Both embed the shared `<inspecto-grammar-editor>`
(`inspecto/grammar/`), which renders flat stacked sections today — no tabs. The onboarding Parsing
stage no longer exists (P6-e); the drawer + dialog are the only two adopters.

**What the engine can actually execute.** Production delimited ingest is DuckDB-native
(`DuckDbCsvIngester.buildReadSpec`, `inspecto-etl/.../DuckDbCsvIngester.java:208-251`) with a
univocity Java fallback (`engine: java`). The complete, closed key list the config parser reads
(`PipelineConfigParser.java:342-372` → `PipelineConfig.CsvSettings:102-109`):

`delimiter · has_header · skip_header_lines · skip_junk_lines · skip_tail_lines ·
skip_tail_columns · engine · date_formats · timestamp_formats · encoding · compression ·
strict_mode · null_strings · include_prefixes · include_regex · exclude_prefixes · exclude_regex ·
filter_target_column · where` (+ `frontend`)

Three hard-wired behaviors that the UI must present as facts, not knobs
(`DuckDbCsvIngester.java:208-251`): every column is read as **VARCHAR** (types are applied later by
the schema/mapping companion), **`ignore_errors=true` + `store_rejects=true`** always (rejected rows
are always ledgered, never silently dropped), `null_padding=false`, `auto_detect=false`.

**The silent-drop trap.** `mergeParsing` flattens `parsing.delimited.*` with **no key allow-list**
(`PipelineConfigParser.java:1181-1193`): an authored `quote:`/`escape:`/`comment:` is carried but
never read — silently dropped, not rejected. Worse, the grammar-component preview path
(`ComponentPreview.grammar()`, `inspecto-engine/.../ComponentPreview.java:84-122`) **does** honor
`quote`/`escape` and runs `auto_detect=true` — so one of the three preview routes can green-light
options production ingest cannot execute. §5 B1 closes this before any UI renders those fields.

**Doc drift found while grounding:** `docs/okf/backend/config/parsing-options-reference.md:186-195`
documents `delimited.strict:` but the engine only reads `strict_mode`
(`PipelineConfigParser.java:357`); the same doc's §5 claims unsupported knobs are "rejected at load"
— untrue for quote/escape/comment today. Fixed in B1.

**Columns table today.** `<inspecto-schema-fields-editor>` (`inspecto/schema/`), projected into the
drawer's `[grammarExtras]` as "Output schema": checkbox · Name · Source (read-only selector) · Type
(`mat-select` over `SCHEMA_TYPES = VARCHAR·DOUBLE·DATE·TIMESTAMP`, with `TYPE_META` icons + hints
already defined). Seeded from Test parse (`onPreviewed`,
`pipeline-parse-definition.component.ts:517-538`) with every column `VARCHAR`, `selector` = 0-based
position for delimited. Save is two-hop (`submitWithSchema` :961-1001): `POST /config/write` kind
`schema` (`raw.fields[{name,selector,type}]` + identity mapping rules) **then** apply the node with
`schema_file` set. Search / type-filter / sortable headers / paginator already built for 500-column
feeds. No reordering (none requested).

**Per-column metadata model already exists** — additive, ETL-ignored, Catalog-read:
`fields[].{description,unit,classification}` (`SchemaProjection.java:9-41`, example
`spaces/default/config/csv_demo/csv_demo_schema.toon:5-10`). No synonym/alias field yet. This is the
natural home for ⑤ Synonym: one more additive column.

**Machinery to reuse:** CSV export `toCsv`/`downloadCsv` (`inspecto/data-table/core/csv.ts`); a
minimal RFC-4180 `parseCsv` living inside `editable-grid.component.ts:247` (to be extracted);
maximize chrome `[inspectoDialogResize]` (already on `GrammarEditorDialog`, `grammar-editor.dialog.html:1`);
type icons/hints `TYPE_META` (`schema-fields-editor.component.ts:17-22`); the `list` AttributeSpec
type (D7) for multi-value options; the offline mock `parsers.handler.ts` (never more lenient than
the server).

**Served schema is narrower than the truth**: `GET /parsers` advertises only 6 delimited fields
(`BuiltinParsers.java:39-49`) while the engine reads 19. The UI's own catalog
(`parsing-attributes.ts:28-71`) mirrors the served 6. Both are widened in this plan.

## 3. The reality matrix — requested option → what ships

Legend: **LIVE** = engine reads it today (UI work only) · **B1** = engine pass-through built in
slice B1 · **FACT** = hard-wired engine behavior, surfaced as information, never a knob ·
**REFUSED** = not rendered, with the reason.

### Tab 1 — Dialect / parsing
| Option | Engine key | Status |
|---|---|---|
| Delimiter | `delimiter` | LIVE |
| Quote char | `quote` | **B1** |
| Escape char | `escape` | **B1** |
| Comment char | `comment` | **B1** |
| First line is a header | `has_header` | LIVE |
| Skip leading lines | `skip_header_lines` | LIVE |
| Skip junk lines (adaptive) | `skip_junk_lines` | LIVE |
| Skip trailing lines | `skip_tail_lines` | LIVE |
| Skip trailing columns | `skip_tail_columns` | LIVE |
| Encoding | `encoding` | LIVE (select: utf-8 · utf-16 · latin-1) |
| Newline style | — | REFUSED — DuckDB auto-detects; engine passes nothing |
| Decimal separator | — | REFUSED — ingest reads VARCHAR; numeric casting is the mapping's job (D4) |

### Tab 2 — Types & columns
| Option | Home | Status |
|---|---|---|
| Columns table (①–⑤, §4.3) | schema companion `raw.fields[]` | LIVE (redesign U2) |
| Data types: Auto / Declared | `raw.types` marker + inferred snapshot | **B2 + B3** (§4.4) |
| Date formats | `date_formats` | LIVE (`list`) |
| Timestamp formats | `timestamp_formats` | LIVE (`list`) |
| Null strings | `null_strings` | LIVE (`list`) |
| `sample_size` / `all_varchar` / `auto_type_candidates` / `names` | — | REFUSED — ingest is all-VARCHAR by design; inference exists only in preview (B2); naming is the columns table's job |

### Tab 3 — Robustness / error handling
| Option | Engine key | Status |
|---|---|---|
| Strict mode (RFC-4180) | `strict_mode` | LIVE (⚠ not `strict` — doc drift, fixed B1) |
| Parse engine | `engine` | LIVE (select: duckdb · java — java = univocity fallback for messy files) |
| Include rows: prefixes / regex | `include_prefixes` · `include_regex` | LIVE |
| Exclude rows: prefixes / regex | `exclude_prefixes` · `exclude_regex` | LIVE |
| Filter target column | `filter_target_column` | LIVE |
| Row filter (SQL) | `where` | LIVE (`multiline`) |
| Rejected rows | — | **FACT** — info alert: "Unparseable rows are never dropped: they land in the rejects ledger" (+ the *N rejected* chip after Test parse) |
| `ignore_errors` / `store_rejects` / `rejects_*` / `null_padding` / `max_line_size` | — | REFUSED — hard-wired (`buildReadSpec`) or unread; no dead knobs |

### Tab 4 — Files & metadata
| Option | Home | Status |
|---|---|---|
| Input compression | `compression` | LIVE (select: auto · gzip · zstd · none — DuckDB decompresses inline at read) |
| Column metadata: Description · Unit · Classification | `raw.fields[].{description,unit,classification}` | LIVE model, first UI (D1) |
| Column Synonym (also col ⑤ in tab 2's table) | `raw.fields[].synonym` | **B3** (additive) |
| File selection / globs / lists of paths | — | REFUSED per operator — Collection owns it (`ConsignmentSelector`); info alert links there |
| `union_by_name` · `buffer_size` | — | REFUSED — absent from the ingest path (grepped `inspecto-etl` + `inspecto-engine`: no hits; `parsing-options-reference.md:132-136`) |
| `filename` column · `hive_partitioning`/`hive_types` · `parallel` | — | **RE-HOMED, not read options** (§10) — partitioned output and parallel batch execution already ship on the sink/executor side; source-filename lineage in output rows = new slice **B4** |

## 4. UX design

### 4.1 Where the tabs live

The tab shell goes **inside `<inspecto-grammar-editor>`**, so both adopters (drawer + dialog) get it
from one implementation:

- `AttributeSpec` gains optional `group?: string`; `parsingAttributesFor('delimited')` assigns
  `dialect | types | robustness | files`. **A spec set with ≥2 distinct groups renders a
  `mat-tab-group`, one `<inspecto-schema-form>` per group; ungrouped sets render exactly as today**
  — json/fixedwidth/text_regex/served plugins are byte-identical, zero regression surface.
- Tab content is **eagerly rendered and stays mounted** (default MatTab behavior, no
  `matTabContent` templates) — the R9 rule: ViewChilds and form state must survive tab switches.
- The editor aggregates its schema-forms: `value()` merges the group maps (keys share one flat
  namespace), `validate()` = every form valid + `markAllAsTouched` on the failing tab, `isDirty()` =
  any, `markPristine()` loops. The existing spec-swap re-seed rule applies **per group** (the
  documented trap: reassigning `specs` rebuilds controls from defaults).
- Two **named projection slots** let hosts own write-path content without the editor growing one:
  `<ng-content select="[tabTypes]">` (tab 2 — the drawer projects the columns table here) and
  `<ng-content select="[tabFiles]">` (tab 4 — the column-metadata grid, D1). The dialog projects
  nothing and simply shows the tabs' option forms — its "Draft Schema…" onward button stays.
- **Tab badges (the "smart" layer):** each tab label carries a count chip of values set away from
  default, and a warn dot when the tab holds an invalid control — the operator sees where
  configuration lives without hunting. Counts derive from the live form vs. spec defaults.

Layout (drawer, maximized):

```
┌ Parse — Delimited Grammar ──────────────────────────────── ⤢ ✕ ┐
│ [Sample panel: choose file · paste · 256KB cap]   [Test parse] │
│ ┌ Dialect/parsing (3) │ Types & columns ⚠ │ Robustness │ Files ┐│
│ │  …active tab's schema-form / columns table…                  ││
│ └───────────────────────────────────────────────────────────── ┘│
│ Parsed · delimited · 12 columns · 200 rows · 3 rejected         │
│ [rows preview — <inspecto-data-table tier="pro">]                │
│                        [Import CSV] [Export CSV]  [Cancel][Save]│
└─────────────────────────────────────────────────────────────────┘
```

Sample + Test parse stay **above** the tabs and the parsed preview **below** them — both visible
from every tab, so the dialect-tweak → re-test loop never leaves tab 1.

**Auto re-parse:** after the first successful Test parse, any grammar value change re-runs the
parse debounced (~600 ms) for **built-in frontends only** (plugins/asn1 keep the explicit button —
server round-trips, and asn1's preview legitimately throws offline). A quiet spinner on the Parsed
chip row; failures reuse the existing error alert without stealing focus.

### 4.2 Tab contents

Exactly the §3 LIVE/B1 rows, as `AttributeSpec`s (tiers preserved: frequent knobs `required`-tier
visible, the rest `optional`/`advanced` inside each tab):

- **Dialect:** delimiter (required-tier), quote, escape, comment (single-char pattern), has_header,
  skip_header_lines, skip_junk_lines, skip_tail_lines, skip_tail_columns, encoding (select).
- **Types & columns:** the Auto/Declared toggle + columns table (§4.3–4.4); date_formats,
  timestamp_formats, null_strings as `type: 'list'` chips (⚠ list errors render explicitly via
  `listError()` + `role="alert"` — never `<mat-error>`).
- **Robustness:** strict_mode, engine (select), include_prefixes (`list`), include_regex,
  exclude_prefixes (`list`), exclude_regex, filter_target_column, where (`multiline`), + the
  rejects FACT alert (`<inspecto-alert variant="info">`).
- **Files & metadata:** compression (select), the column-metadata grid (D1), + an info alert:
  "Which files are read is configured on this pipeline's Collection step."

### 4.3 The columns table (metadata definition table), redesigned

`<inspecto-schema-fields-editor>` reordered and extended — same FormArray, same seeding, same
paging/sort/search/drift:

| # | Column | Behavior |
|---|---|---|
| ① | ▢ include | per-row checkbox + tri-state master over the filtered set (kept) |
| ② | `#` sequence | the field's `selector` verbatim (0-based physical position for delimited), read-only, tooltip "0-based position in the record"; default sort |
| ③ | Type — **icon only** | `mat-icon-button` showing the `TYPE_META` icon (`aria-label="Column type: DOUBLE — change"`, tooltip = type name) → `mat-menu` listing the current `SCHEMA_TYPES` with icon + name + plain-words hint per row. No `mat-select`. In Auto mode: disabled, tooltip "Inferred — switch Data types to Declared to override" |
| ④ | Name | editable, identifier-validated (kept) |
| ⑤ | **Synonym** | new optional input, same identifier rule as Name; **unique** across all synonyms ∪ all names (D3), inline duplicate error |

`Source` stays as a sixth column **only for name-based frontends** (json/text_regex, where
selector ≠ position); for delimited/fixedwidth ② already *is* the source. The type list stays the
current `SCHEMA_TYPES` (the operator asked for "the list currently on the dropdown"); widening it is
out of scope.

### 4.4 Data types: Auto / Declared

A `mat-button-toggle` at the head of tab 2.

- **Auto:** Test parse returns per-column **inferred types** (B2 — a DuckDB `auto_detect` sniff of
  the same sample; the machinery already exists in `ComponentPreview.grammar()`). The table shows
  them as read-only icons. **On save, the inferred snapshot is written into the schema fields** —
  deterministic downstream, and the authored file records what the sniffer said (this is the honest
  answer to the known trap where a declared INTEGER lands as VARCHAR: in Auto, declared = inferred
  by construction). Saving in Auto with no successful parse this session is blocked with "Run Test
  parse first" (seeding already requires it in practice).
- **Declared:** icons editable via the menu; if a parse ran, an "Apply suggested types" chip offers
  the inferred set as a one-click, non-destructive starting point.
- **As-built deviation (2026-08-19):** the "Run Test parse first" save-block was NOT implemented —
  with no derived columns nothing is written at all (a parser may be defined before its schema, the
  BUILDER-1a dead-end rule the drawer already pins in a test), and once columns exist they always
  came from a parse. The one residue — an old server serving no `columnTypes` — saves `types: auto`
  over VARCHAR, recording everything the sniff could say.
- The mode persists as `raw.types: auto|declared` on the schema companion (additive, ETL-ignored,
  B3), so reopening restores it. Existing schemas without the marker load as Declared.

### 4.5 CSV export / import (replaces "Save as template…")

**Filename:** `<pipelineName>_parser.csv` (operator-specified). **Format:** tidy long-form
RFC-4180, one property per row — order-independent, Excel-editable, diff-friendly:

```csv
section,key,attr,value
meta,format,,delimited
meta,version,,1
meta,pipeline,,orders_daily
option,delimiter,,"|"
option,has_header,,true
option,null_strings,,"NULL,N/A"
column,0,include,true
column,0,name,customer_id
column,0,type,BIGINT
column,0,synonym,cust_no
column,0,description,Customer account number
```

`option` keys are the **engine key names** (§3), `column` keys are the selector. Export writes all
set options + every column with all its attributes.

**Import semantics:** refuse outright on `meta,format` mismatch with the active frontend; apply
known option keys and **list unknown ones** in a warning `<inspecto-alert>` (imported anyway into
the block? No — unknown keys are *not* applied: the engine would silently drop them, §2's trap);
columns **replace the table wholesale**, behind `InspectoConfirmService` when the current state is
dirty. A round-trip spec (export → import → deep-equal) pins the format.

**Mechanics:** export via existing `toCsv`/`downloadCsv`; import via `parseCsv` **extracted** from
`editable-grid.component.ts:247` into `inspecto/data-table/core/csv.ts` (editable-grid +
`mapping-editor.dialog.ts:418` re-import from there — a pure move, both consumers' specs stay
green). Two icon-buttons (upload/download, aria-labelled) sit where "Save as template…" sits today,
on both adopters.

**Removals and their consequences (deliberate, per operator):**
- The drawer's Name + Description inputs (`pipeline-parse-definition.component.ts` template
  L150-157) go. U4 first verifies what they bind (step name/description) and confirms the canvas
  rename path still covers it before deleting.
- "Save as template…" goes from the drawer (L159-180 + host wiring at
  `pipeline-editor.component.ts:1715`) and from the dialog (incl. its whole *name step*, which
  simplifies its `guardDirtyClose`). **Consequence:** grammar *templates* can no longer be created
  from the parse surfaces; the "Start from a template" select **stays** (untouched — existing
  templates and any Components-admin create path keep working), and the CSV file becomes the
  portable template going forward.

### 4.6 Maximize

- The **dialog** already has it (`inspectoDialogResize` + toggle, `grammar-editor.dialog.html:1-18`).
- The **drawer** gets the equivalent: a maximize icon-button in `<inspecto-definition-drawer>`'s
  header toggling a `maximized` signal; the host binds width to `maximized() ? '100%' :
  h.width()px` over the canvas. The split handle **stays mounted** (`[class.hidden]`, never `@if` —
  the documented `#h="inspectoSplit"` trap), and the canvas host already has a `ResizeObserver`.

## 5. Backend slices

**B1 — quote/escape/comment pass-through + served-schema truth** (`feat(etl)` + `feat(control)`)
- `CsvSettings` + 3 fields; `PipelineConfigParser:342-372` reads `quote`/`escape`/`comment`
  (validate single-char, fail-closed `IllegalArgumentException` at load).
- `DuckDbCsvIngester.readOptions:706-724` threads `quote=`/`escape=`/`comment=` when set; the
  univocity fallback (`CsvIngester`) gets the same three so the two engines cannot diverge.
- Reconcile `ComponentPreview.grammar()`'s divergent semantics (it already honors quote/escape,
  `auto_detect=true`): after B1 the production path honors them too; `auto_detect` stays a
  preview-only affair. Resolve the "deliberate or stale" question by blame during the slice.
- Widen the served delimited `grammarSchema` (`BuiltinParsers.java:39-49`) to the full live key set
  (§3), and mirror the mock `CATALOG` (`parsers.handler.ts:37-66`). ⚠ Check the byte-compared
  `*.contract.json` fixtures (`NodeAttributesContractTest`-family) — if the parser catalog is
  pinned there, update fixture + source in the same commit or the **Java** build breaks.
- Fix the doc drift: `parsing-options-reference.md` `strict:`→`strict_mode`, §5's false "rejected
  at load" claim, and flip quote/escape/comment `[NATIVE]`→`[LIVE]`.
- **Verify:** `UnifiedParsingBlockTest` rows for the new keys; an ingester test parsing a quoted
  sample with embedded delimiters + an escaped quote, on **both** engines; full
  `mvn -o clean test -Pedition-enterprise` (one build per tree).

**B2 — preview returns inferred column types** (`feat(control)`)
- `ComponentPreview.parsing` runs a second, `auto_detect=true` sniff over the same sample and the
  response gains additive `columnTypes: [{name, type}]` on **both** UI-used routes
  (`POST /parsers/{id}/preview`, `ParserRoutes.java:57-100`; `POST /config/preview/parsing`,
  `ConfigRoutes.java:611-635`). Old clients ignore the key.
- Mock parity: `parsers.handler.ts` `table()` adds `columnTypes` from a small deterministic
  inferrer (int/float/date/timestamp/bool/else VARCHAR) — same key, same casing, **never more
  lenient**; asn1 unchanged (still throws).
- **Verify:** route test asserting inferred BIGINT/DATE/VARCHAR on a typed sample; a handler spec
  pinning the mock's response keys against the route's.

**B3 — `synonym` + `types` marker as additive schema metadata** (`feat(engine)`)
- Falsify-first: a test writes `raw.fields[].synonym` + `raw.types` through `POST /config/write`
  kind `schema` and reads them back verbatim (expected: the codec already round-trips unknown keys;
  if not, that's the slice).
- `SchemaProjection.Column` gains `synonym` (Catalog-facing, same additive pattern as
  description/unit/classification, `SchemaProjection.java:20-41`). No ETL change by design.
- **Verify:** round-trip test + a `SchemaProjection` read test; reactor green.

**B4 — `filename_column`: source-file lineage as an output-row column** (`feat(etl)`)
- Today the per-row tag `__src_id` (stamped `CsvBatchStrategy.java:140-145`) is **always excluded**
  from written files (`PartitionWriter.java:76-83`) and translated to filenames only in the external
  lineage ledger (`LineageCollector.java:44-76`). B4 makes it a column on request: when the output
  block declares `filename_column: <name>`, `PartitionWriter.write` translates instead of excluding
  (`srcIdToFile` map → VARCHAR column). Both lanes get it for free — the graph-lane sink delegates
  to the same writer (`PartitionSinkWriter.java:41-158`).
- Config home: `output.filename_column` / `sinks[].filename_column`. Collision with an existing
  data column fails at load. The sink node's served FieldSpec gains the row, so the sink pane
  renders the field with **zero UI work** (server-published attribute-spec contract).
- **As-built deviation (2026-08-19):** the column ships on the **ingest lane** (`BatchIngestStrategy`,
  all sinks) and the **wrap lane** (`DuckDbRecordSink`, plugin ingesters) — the two lanes whose rows
  carry a real `__src_id`. The graph lane (`PartitionSinkWriter`) was NOT wired: `PipelineJobRunner`
  executes over data **at rest**, where rows have no source files, so a filename column there could
  only be invented. `PartitionWriter` fails hard when `filename_column` is declared over a relation
  with no `__src_id` — a lineage column that silently wrote NULLs would look like it worked. The
  "zero UI work" claim also under-counted: `NodeAttributes.OUTPUT` has a hand-mirrored TS table
  (`output-attributes.ts`) + the byte-compared contract JSON + `PipelineEditable` lift/lower (Java
  AND mock TS) — five touchpoints, all updated together.
- **Verify:** writer tests — partitioned + unpartitioned × parquet + csv: column present with
  correct per-member values on a multi-file batch, absent when unset, load-fails on collision;
  lineage ledger unchanged; reactor green.

## 6. UI slices

**U1 — tab shell + the widened, grouped delimited spec set** (`feat(ui)`)
- `AttributeSpec.group`; grammar-editor tab rendering, aggregation, per-group re-seed, badges
  (§4.1); `parsingAttributesFor('delimited')` regrouped + widened to every §3 LIVE/B1 key with the
  right types (`list` / `select` / `multiline` / single-char patterns). Ungrouped formats render
  exactly as before.
- **Verify:** editor specs — tabs for delimited, flat for json; `value()` merge; cross-tab
  `validate()` focuses the failing tab; badge counts; spec-swap keeps live values; axe on the
  tabbed editor. Suite exit code 0, `lint:tokens`, 3× tsc, AOT build.

**U2 — columns table redesign** (`feat(ui)`)
- Reorder to ①–⑤ (§4.3); icon-button + `mat-menu` type picker reusing `TYPE_META`; `synonym`
  control + cross-row uniqueness validator; `Source` column conditional on name-based frontends;
  `autoTypes` input disabling the menu.
- **Verify:** specs — order, menu keyboard operation, aria-labels (axe), duplicate-synonym error
  **rendered** (assert the `role="alert"`/`mat-error` element, not the message string), master
  checkbox over filtered set still correct.

**U3 — Auto/Declared wiring** (`feat(ui)`)
- Toggle; consume `columnTypes` (Auto seeds read-only icons; Declared gets "Apply suggested
  types"); save semantics incl. the Auto-without-parse guard; `raw.types` persistence + restore.
- **Verify:** `onPreviewed` seeding with/without `columnTypes` (backward-compatible when absent);
  save payload carries the snapshot; mode restored on reopen; mock-driven preview flow spec.

**U4 — CSV round-trip + removals** (`feat(ui)`)
- Extract `parseCsv` → `data-table/core/csv.ts` (re-point editable-grid + mapping-editor);
  export/import per §4.5 on both adopters; remove Name/Description (verify binding first),
  "Save as template…" everywhere + the dialog's name step.
- **Verify:** round-trip deep-equal spec; unknown-key warning; format-mismatch refusal;
  confirm-on-dirty; editable-grid + mapping-editor suites still green; grep confirms no orphaned
  `GrammarTemplateDialog` wiring remains (remove if orphaned — our own mess).

**U5 — drawer maximize** (`feat(ui)`)
- §4.6. **Verify:** toggle spec (width binding, aria-label, handle stays mounted), axe.

**U6 — docs + gallery + graph** (`docs`)
- `okf/frontend/features/grammar-config.md` as-built update (also fix its stale "Onboarding Parsing
  stage is a host" note); `parsing-options-reference.md` already synced in B1; `/design` gallery
  gains the tabbed grammar editor + icon-type-menu pattern; the `angular-ui` skill if a shared
  pattern changed; `graphify update .`; INDEX/BACKLOG bookkeeping; archive this plan.

## 7. Sequencing & global verify

```
B1 → U1 → B2 → U3          (U1 renders quote/escape only after B1 lands)
      U2 ──────┘            (U2 independent after U1)
B3 ∥ B4 ∥ U4 → U5 → U6
```

Each slice = one commit per `release-workflow` (`feat:` → master). Global gate before calling the
plan done: **GAUNTLET** (reactor `mvn -o clean test -Pedition-enterprise` vs. the 3459/0/0/5-family
baseline + UI `lint:tokens` / `test:ci` **exit code** / AOT build / 3× tsc) **plus a builder-driven
preview pass**: open a pipeline → parse step → walk all 4 tabs → Test parse → toggle Auto → export
CSV → reimport → Save → reopen and verify persistence by reading the mock store
(`MOCK_STORE_KEY`), never by "the drawer closed". (Precedent: preview catches the wiring defects the
2600-green suite cannot.)

## 8. Explicitly out of scope / refused

- `union_by_name`, `buffer_size`, glob/path lists: not in the ingest READ path; file selection is
  Collection's (consignment Selector).
- `filename` injection, `hive_partitioning`/`hive_types`, `parallel` looked absent from the read
  side and were initially refused here — the 2026-08-19 follow-up re-homed them (§10): partitioned
  output and parallel batches already ship; the filename column is slice B4.
- `decimal_separator`, wider `SCHEMA_TYPES`, drag-reordering of columns, `max_line_size`,
  `null_padding`, rejects tuning: not requested / engine-fixed.
- The dialog's known sample-thread gap for the generic `parser` type (BACKLOG L637-640) — adjacent,
  untouched here.
- Filename says `_parser` (operator-specified) while the surface says Grammar — accepted tension,
  recorded here so nobody "fixes" it into a rename ticket.

## 9. Decisions for the operator

| # | Question | Recommendation |
|---|---|---|
| **D1** | Tab 4 content beyond `compression`: (a) thin + Collection pointer only · (b) + the column-metadata grid (description/unit/classification — model exists, first UI) | **(b)** — real, additive, Catalog-read. (The former option (c) — engine pass-through for filename/hive/parallel — dissolved 2026-08-19: §10) |
| **D2** | Default types mode for new parse steps | **Auto** (snapshot-on-save keeps downstream deterministic) |
| **D3** | Synonym uniqueness domain | **synonyms ∪ column names** (prevents ambiguous lookups) |
| **D4** | `decimal_separator` | **Refuse at Dialect** — casting is the mapping's concern; revisit there if ever |

## 10. Adjacent asks re-homed (grounded 2026-08-19)

The operator's follow-up — "filename injection, hive_partitioning/hive_types are required on
**output** parquet/csv; parallel is also required, non-sequential execution most of the cases" —
lands on the **sink and executor**, not on the delimited Grammar. Grounding found two of the three
already ship:

- **Hive-partitioned output SHIPS today.** `PartitionWriter.java:56-163` writes every batch via
  `COPY … TO <staging> (FORMAT …, PARTITION_BY (…), OVERWRITE_OR_IGNORE 1)` + an atomic two-step
  reveal (`:172-188`). Where to define it: graph lane — the sink node's `partitions[]`
  (`SinkPartitions.java:26-105`: bare column or `{column, source}`; `PartitionSinkWriter.java:41-158`
  delegates, else writes unpartitioned); ingest lane — `partitionKey:` / typed `partitions[]`
  (`PartitionDef.java:70-103`, live example `csv_demo_schema.toon:1`). Partition **values are
  duplicated into the file rows by design**, so read-back needs no `hive_types` synthesis — Dataset
  reads deliberately keep `hive_partitioning=false` (BACKLOG:1729-1731, recorded product decision).
  Read-back globs are depth-agnostic (`SqlViews.java:45-121`), so partition dirs don't disturb the
  Selector / `retire_superseded` contract (`consignment-addressing.md` §3–4).
  ⚠ **Part II correction (2026-08-19):** the ingest lane cannot yet write *unpartitioned* — an
  undeclared key degrades to a `year=1900/month=01/day=01/` sentinel bucket
  (`DataTransformer.java:114-124`); true optional partitioning is slice **E1** (§13).
- **Parallel batch execution SHIPS, two layers deep.** Per pipeline: every planned batch runs on a
  virtual-thread executor gated by `Semaphore(processing.threads)` (default 4), each batch in its
  **own temp DuckDB file** — N independent writers, no shared-writer contention
  (`CollectorProcessor.java:117-181`, `BatchIngestStrategy.openTempDb`), DuckDB worker threads
  auto-divided (`processing.duckdb_threads: 0` → cores ÷ threads, `DuckDbUtil.java:227-232`).
  Across pipelines: `MultiCollectorProcessor.java:99-200`, `Semaphore(sources.max)`. The one
  sequential stretch left is **inside a single batch's node graph** — `PipelineExecutor.java:28-37`
  documents the topological walk as a deliberate first cut; parallel branches there belong to the
  gated branch-aware-executor work (ELT amendment plan), not to this one.
- **Source-filename lineage in output rows is the one real gap → slice B4 (§5).**

⚠ Two live, already-tracked caveats for anyone leaning harder on partitioned stores:
`PartitionWriter.reveal()` is atomic per **file**, not per generation (BACKLOG JAVA-1 :911-926), and
an unconfigured `retire_superseded` leaves a permanent extra copy per full recompute
(BACKLOG:2122-2128).

---

# Part II — DuckDB-centric execution core (operator direction, 2026-08-19)

## 11. The direction, restated

DuckDB **is** the execution core: it does the loading and writes the output, hive-partitioned when
a key is declared, **plain files when not** ("smaller data that don't need partitions or don't have
a key"). Formats DuckDB can't read natively **wrap** the core — Java parses records and feeds them
in, or stages intermediate CSV. If concurrent writing can't be made safe, batch-level
single-threading is acceptable, since DuckDB parallelizes internally across all cores. Simplify the
execution engine where it makes sense.

## 12. Answers, grounded

**Is the DuckDB writer safe (concurrently)? Yes — by construction, today.** DuckDB itself allows
one writer per database file, and the engine never violates that: each parallel batch opens its
**own temp DuckDB file** (`BatchIngestStrategy.openTempDb`), and the durable output is plain
parquet/CSV via `COPY` to staging + atomic reveal — not a shared DuckDB database. Every shared
finalization store is a single JDBC connection with every mutator `synchronized`, documented as
such: the batches ledger (`BatchAuditWriter.java:20-21,114` — "each batch's rows are written
contiguously even when multiple batches finish concurrently"), `DbConsignmentOutputStore.java:23-24`,
`DbAcquisitionLedger.java:24`, `DbFileStageStore.java:23`. Manifests and markers are per-batch-unique
files that fail fast on collision (`ManifestStore.java:21-24`, `MarkerManager.java:53-59` via
`Files.createFile`), and concurrent batches process disjoint file sets by construction
(`ConsignmentPlanner.plan`). **So single-threading is not forced** — and where an operator wants it
anyway, `processing.threads: 1` already grants DuckDB all cores (`DuckDbUtil.effectiveWorkerThreads`,
`DuckDbUtil.java:227-232`: concurrency ≤ 1 → DuckDB default = all cores). What's missing is not
safety but **proof**: no test exercises concurrent finalization — slice **E4** pins it (falsify the
guard; an inherited "safe" claim that was never pinned is a known trap here). One documented manual
seam remains: total pressure ≈ `sources.max × threads × duckdb_threads`, sized by hand
(`MultiCollectorProcessor.java:31,35`); a global ceiling is under separate investigation (the
pending "thread division under nested concurrency" task).

**The wrap for non-DuckDB formats already exists — canonize it, don't rebuild it.** The SPI pair is
`StreamingFileIngester` (`ingest(File, RecordSink, srcId, cfg)`) + `RecordSink`
(`define/emit/reject/junk`, both `inspecto-etl/.../etl/`); the sole sink implementation
`DuckDbRecordSink` buffers 10 000 rows and flushes through the **JDBC `DuckDBAppender`**
(`DuckDbRecordSink.java:49-294`) — never staged CSV, never row INSERTs — then runs the *identical*
downstream (`DataTransformer.materialize` → `PartitionWriter.write` → `LineageCollector`) as native
CSV. Two drive modes exist: `GenerationModeIngester` (huge single files, bounded generation flushes)
and `UnionModeIngester` (many small files, union then one pass). `Asn1RecordIngester.java:79-151`
is the shipped reference implementation of exactly the operator's "feed java parsed records into
it". **Decision embedded here:** the Appender path is the canonical wrap; "generate intermediate
CSV" is demoted to a fallback for *out-of-process* producers only (the in-process path is already
built, faster, and avoids a second disk write).

**What "simplify the execution engine" honestly finds.** The two lanes are **not** redundant
engines: they split by *entry kind* — files being ingested (`CollectorProcessor` →
`BatchProcessor` → `DataTransformer`/`PartitionWriter`) vs data already at rest
(`PipelineJobRunner` → `PipelineExecutor` → `PartitionSinkWriter`; "ingest is pipeline-exclusive",
`PipelineJobRunner.java:56`) — and the mapping/type-cast compiler is already shared
(`RowShaper.java:364` calls `DataTransformer.dataColumns`). What **is** duplicated and collapsible:
(a) **two writers** — `PartitionWriter` (always-partitioned) vs `PartitionSinkWriter`
(partitioned-or-single-file) → E1 merges to one writer owning both modes; (b) **two partition
grammars** — schema `partitions[]`/`PartitionDef` vs sink `partitions[]`/`SinkPartitions` → E2
unifies the parser. ⛔ **Explicitly out of scope:** merging the lanes, wiring `BatchGraphRunner`
(zero production call sites), or anything touching the `writeAndTrace` tail (decision-rule routing,
record dedup, reference-version stamping, fan-out, `EventTimeBounds`) — that is the ELT amendment
plan's gated "output parity" territory (`elt-final-amendment-plan.md:1074-1101`), blocked on its own
operator decision, and this plan must not re-litigate it.

**Optional partitioning is a real gap on the ingest lane.** `PartitionWriter.write` unconditionally
emits `PARTITION_BY (…)` (`PartitionWriter.java:130-131`), and
`BatchIngestStrategy.partitionColumns` defaults to `(year, month, day)` whenever the schema declares
nothing (`BatchIngestStrategy.java:72-75`) — with no date source, `DataTransformer.selectFor` emits
a literal `'1900','01','01'` sentinel (`DataTransformer.java:114-124`), so every row of an unkeyed
pipeline lands in one degenerate `year=1900/month=01/day=01/` bucket. The graph lane already writes
true single-file output when the sink declares no partitions (`PartitionSinkWriter.java:107-149`,
`okf/backend/engine/output-sinks.md:19-22`). E1 makes "no key declared → flat store" the ingest
lane's behavior too, retiring the sentinel for new writes.

## 13. Slices E1–E4

**E1 — One writer, partitioning optional on both lanes** (`feat(etl)`)
- Merge `PartitionSinkWriter`'s unpartitioned mode down into `PartitionWriter`: one class owning
  partitioned `COPY … PARTITION_BY` and unpartitioned single-file `COPY`, plus staging + atomic
  reveal for both; `PartitionSinkWriter` becomes a thin delegate for both modes.
- Ingest lane: `partitionColumns()` stops defaulting to `(year,month,day)` when the schema declares
  no partition source → unpartitioned flat store; the `1900` sentinel is retired **for new writes**.
  Declared keys behave exactly as today. `__event_time` emission unchanged (already NULL-safe).
- Migration is read-compatible by construction: read-back globs are depth-agnostic
  (`SqlViews.java:45-121`) and the Selector subtracts by catalog status, not layout — existing
  sentinel directories stay readable beside new flat files.
- **Verify:** writer tests partitioned + unpartitioned × parquet + csv on BOTH lanes; an unkeyed
  ingest pipeline produces flat files and reads back; maintenance ops (`retire_superseded`,
  compaction) run against a flat and a mixed (sentinel + flat) store in tests; reactor green.

**E2 — One partition grammar** (`refactor(etl)`)
- Single shared parser for `partitions[]` (bare column or `{column, source[, type]}`), used by both
  homes — schema (ingest lane) and sink node (graph lane); legacy `partitionKey:` still read.
  Docs collapse to one section. No semantic change — a parse-layer unification only.
- **Verify:** both existing config corpora (`spaces/**`) parse identically before/after (golden
  comparison); `UnifiedParsingBlockTest`-style rows; reactor green.

**E3 — Canonize the wrap-SPI** (`docs` + one test)
- A new OKF concept (`okf/backend/engine/ingest-wrap-spi.md`): `StreamingFileIngester` +
  `RecordSink` → `DuckDbRecordSink` (Appender) → Generation/Union drive modes, ASN.1 as the
  reference implementation; intermediate CSV recorded as out-of-process fallback only. No code
  change (the reflective `ingesterClass` FQCN instantiation is correct for config-chosen
  implementations — not a ServiceLoader candidate).
- Plus **the core contract test**: a toy `StreamingFileIngester` fixture drives records end-to-end
  into a partitioned and an unpartitioned store, asserting rows, partitions, lineage ledger.
- **Verify:** the contract test; `graphify update .`; INDEX.

**E4 — Finalization concurrency pin** (`test(engine)`)
- A stress test: N batches finalize concurrently through `BatchProcessor.finalizeSource` + the four
  synchronized stores; assert contiguous per-batch ledger blocks, no lost output registrations, no
  marker/manifest collisions. This converts §12's structural safety argument into a pinned one.
- **Verify:** the test is red if any store's `synchronized` is removed (mutation check on one), then
  green; reactor green.

Sequencing: E1 → E2; E3 ∥ E4 anytime. Part II is independent of Part I's UI slices; B-slices and
E-slices may interleave. Same global gate (§7 GAUNTLET).

## 14. Part II decisions

| # | Question | Recommendation |
|---|---|---|
| **D5** | Default `processing.threads` (today 4, each batch getting cores ÷ 4 DuckDB threads) | **Keep 4** — concurrency is safe (§12) and union-mode many-small-file loads regress at 1; per-pipeline `threads: 1` remains the single-big-file tuning (DuckDB then takes all cores). Revisit only if the nested-concurrency investigation finds real oversubscription |
| **D6** | Should E1 also retire the sentinel for **existing** unkeyed pipelines' *old* data (rewrite), or leave old sentinel dirs in place | **Leave in place** — read-back is layout-agnostic; a rewrite buys nothing and risks a recompute-copy explosion (`retire_superseded` caveat, §10) |
