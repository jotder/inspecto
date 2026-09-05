# Delete `transform.map` — the Record Transformer becomes the only projection slot

**Status:** in flight (2026-09-05). Operator decision: full deletion, overriding the 2026-09-05 handoff's
"deprecated, not deleted" guardrail. Drains `docs/BACKLOG.md` RECORD-TRANSFORMER-1 (a)+(b)+(f).

## Why this is safe to attempt now

Both spellings already compile through ONE seam — `DataTransformer.dataColumns` returns `[{name, expr}]`
whether it read `mapping.rules[]` (via `TransformCompiler`) or `mapping.fields[]` (via
`RecordTransform.compile`), and both execution lanes (`DataTransformer.selectFor` on ingest,
`RowShaper.columnsOf` at rest) read that list. Removing Map is therefore a *re-pointing* of ~16 backend
branches and ~8 UI sites at `transform.sql`, not a rewrite — and `MappingMigrator.verify` proves each
migrated file compiles to byte-identical SQL before it is written.

Measured 2026-09-05: `MappingMigrator --dry-run spaces` → `migrated=0 refused=4 untouched=109`. The four
refusals (`demo/orders`, `demo/payments`, `ucc/sites`, `_templates/orders-starter/orders`) all carry
`EXPR` rows; nothing stored uses `CONCAT_DT` / `FILENAME_DATE`.

## Phases (one commit each; `mvn -o test` on the touched reactor + UI `test:ci` before each commit)

### A — make every stored schema migratable, then migrate them
1. **Catalog gains two Dates functions** (Java `RecordTransform.SQL_FUNCTIONS` + TS `sql-functions.ts`,
   same position, contract JSON regenerated with `-Drecord.transform.write=true`):
   - `date.concat_parts` — `TRY_STRPTIME(CONCAT({source}, ' ', {time_column}), {format})`;
     params `time_column` (column), `format` (text, default `%Y-%m-%d %H:%M:%S`). The `CONCAT_DT`
     analogue. ⚠ Deliberate narrowing: legacy coalesced over every `csv.tsFormats()` entry and applied
     the date column's source zone; the catalog function takes ONE format and no zone (a Record
     Transformer row cannot see parser settings). Zero stored users, so nothing changes at rest.
   - `date.from_filename` — `TRY_STRPTIME(regexp_extract({source}, {pattern}, 1), {format})::DATE`;
     params `pattern` (text, default `([0-9]{8})`), `format` (text, default `%Y%m%d`). The
     `FILENAME_DATE` analogue; converter builds `pattern = prefix + "([0-9]{8})"`. The legacy
     "EVENT_DATE only" restriction is dropped — it was a guard on the rule type, not on the SQL.
2. **`RecordTransform.fromMappingRules`** converts all four rule types (no refusals left).
3. **`MappingMigrator`** emits the TOON **block-list** form when any row is non-DIRECT:
   ```
   fields[8]:
     - name: REGION
       from: ""
       fn: custom
       args:
         expression: "UPPER(TRIM(REGION))"
   ```
   Tabular rewrite stays for the all-DIRECT case (smallest diff). `verify` gates both.
4. Run the migrator for real on `spaces/`; re-run the demo pipelines; commit the 4 schemas with the code.

### B — backend: the lift always emits `transform.sql`; `rules[]` becomes a read-time conversion
- `PipelineConfigParser`: wherever `mapping.rules[]` (inline, `mapping_file`, sibling `_mapping.csv`)
  lands in `MapConfig`, convert to `fields[]` via `fromMappingRules` at load. Readers downstream see only
  `fields[]`. This honours "stays readable" without keeping an executor for it.
- `PipelineLift:301-328` → always `TRANSFORM_SQL`. `PipelineProjection:129` `map` verb → `transform.sql`.
- `PipelineEditable` (`isProjectionSlot`/legacy lowering), `RowShaper` (3 branches → the `project()`
  path), `PipelineDryRun:158,177`, `ConservationCheck:50`, `ConsignmentIngestStrategy:214,217,421`,
  `Identifiers:115-148` (validate `fields[].name`), `MappingRules` + `ComponentRoutes` (validate fields),
  `ConfigWriteRoutes.splitMapping`, `ConfigPreviewRoutes` `mappedColumns`, `MappingCsv` (CSV ⇄ fields).
- Delete `BuiltinNodeType.TRANSFORM_MAP`, `TransformCompiler` rule path, `DataTransformer` legacy
  branches (`dataColumns` fallback, `countCastFailures` rules branch, `coercedSourceColumn`).
- `ConfigSafetyValidator` / `ProcessorCatalog` wording.

### C — UI: the Load pane goes, the drawer hosts only the Transform pane
- Delete `pipeline-load-definition.component.ts` + spec; drop the `transform.map` branch in
  `pipeline-editor.component.html:736`, the header row at `.ts:2459`, imports/ViewChild.
- `pipeline-editable.ts:327-346` lift emits `transform.sql`; `:38,:556` and `pipeline-stages.ts:78,85`
  re-pointed; `pipeline-graph.ts:501,777` entries deleted.
- `mapping-rule.ts` STAYS (the Components mapping editor still uses it).
- Prune Map assertions in the five affected specs.

### D — docs
- OKF `catalog-vs-executors.md` + `schema-mapping-authoring.md` + `node-types.md`: Map removed, as-built.
- GLOSSARY touchpoint table; BACKLOG RECORD-TRANSFORMER-1 → (a)(b)(f) closed, (c)(d)(e) kept.
- This plan → `docs/archived-documents/plans-archive/`; `graphify update .`.

## Verification gates
- Phase A: `MappingMigrationTest` corpus test green; migrator dry-run reports `refused=0`; the three
  demo pipelines + `ucc/sites` re-run after migration produce identical row counts and `cast_failures`.
- Phase B/C: full `mvn -o clean test` (asn subtree excluded on this profile) + `npm run test:ci`,
  `lint:tokens`, `build`; then `package.ps1 -NoRuntime`, restart, and open `csv_example` + `orders` in the
  canvas — the projection node must render as a Record Transformer and open the Transform pane.
