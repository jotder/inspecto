# Column contract — Schema column changes and what they break (SE-3a)

**State:** built 2026-11-13 (SE-3a of the Space evolution plan, §4.3 steps 1 and 2: the shadow report, then a
warning on Schema save). It refuses nothing. The opt-in refusal with `?force=true` (D-SE4) is SE-3b.

## 1. What it adds, and what it reuses

There is no second column model. The link census (LC-5) already knows, per Space:

- the columns each Pipeline store and Dataset publishes (`ColumnSets`: a Schema's `mapping.fields`, plus the
  partition columns, a Dataset's `calculated` columns, …);
- every consumer's column links (Widget `controls`, Dataset `dateField` / `calculated[].expr` / `sql`,
  Expectation `column` / `refColumn`, Decision Rule `when.**.field`, Reconciliation `keyColumns` /
  `compareColumns`, Enrichment `transform` and Query `text` read through the DuckDB parse tree);
- for a RESOLVED column link, its origin — `schema:<file>` when the column comes from a Schema's
  `mapping.fields` (`LinkGraph.columnConsumers(schemaFile, column)`, the same read the
  `GET /config/schema/{name}/columns/{column}/impact` route uses).

SE-3a adds three things on top (`inspecto/.../service/ColumnContract.java`):

1. the Space lock records, per Schema, its published column set (name and type) and the consumers of each column;
2. a change between the recorded and the current set is classed, and each breaking change lists the consumers it
   breaks;
3. a Schema save through the config write funnel gets a WARNING finding per breaking change.

## 2. The published column set

A Schema's set is its `mapping.fields`, in declaration order. A field's type is its own `type`, else — for
`fn: keep` (or no `fn`) — the type of the `raw.fields` entry its `from` names (a `_structure.csv` sibling is
merged first, as the server stores split Schemas); else unknown (`""`). Names match case-insensitively (DuckDB
binds every consumer so); a case-only change is no change.

The set is **unknown** — the Schema is counted in `unknownSchemas` and never judged — when there is no
`mapping.fields` list, a field has no name, or the mapping is rule-built (`mapping.rules`, inline or in a sibling
`_mapping.csv`). This is the census' own rule for a rule-built mapping.

## 3. Change classes

| Class | Breaking | Detected when |
|---|---|---|
| `added` | no | a current column no recorded column matches |
| `type-widened` | no | a kept column's type changed along the BACKWARD lattice the Schema save gate already uses (`SchemaCompatibility.isWidening`: INTEGER → BIGINT / DOUBLE, BIGINT → DOUBLE, DATE → TIMESTAMP, anything → VARCHAR) |
| `removed` | yes | a recorded column no current column matches, and no rename pairs it |
| `renamed` | yes | the rename heuristic below |
| `type-narrowed` | yes | a kept column's type changed in any other way. Any type change that is not a known widening counts as narrowing |

A type unknown on either side never makes a type change.

**Rename heuristic.** A removed column and an added column at the **same position** in `mapping.fields` with
the **same type** (two unknown types count as the same) are one rename, from the old name to the new; each added
column pairs at most once. Limits, by design of a heuristic that reads only the two sets:

- a rename that also moves the column, or changes its type, reads as `removed` + `added` (still breaking);
- removing a column and adding an unrelated one of the same type in the same slot reads as a rename (still
  breaking, with the same consumers — only the label is wrong);
- inserting a column ahead of a renamed one shifts positions, so the rename reads as `removed` + `added`.

Every one of these errs to "breaking": the class may be wrong, the consumer list is not.

## 4. Consumers

A breaking change lists the consumers that read the column **as the Schema published it**: the RESOLVED census
column links whose origin is that Schema file. Columns that reach a consumer through
`processing.map.fields` (which outrank the Schema's mapping) are the Pipeline's, not the Schema's, and are not
listed — the census' own attribution.

**Not analysed.** A column link the parser could not attribute (an unqualified name over a join, a relation
whose columns are unknown) is NOT_ANALYSED, never broken. Each breaking change lists, under `notAnalysed`, those
whose value is the changed column; the section's `notAnalysed` lists every NOT_ANALYSED column link of the Space
(a SQL text the parser did not read at all carries the reason as its value), so the reader sees what was not
checked.

## 5. In the Space lock and the integrity report

The lock gains one additive field (format stays 1; a lock without it is a baseline):

```
"columns": [ {"path": "orders/orders_schema.toon",
              "columns": [ {"name": "ORDER_ID", "type": "VARCHAR"}, … ],
              "consumers": { "gross": [ {"fromKind": "widget", "fromName": "…", "from": "…", "key": "…", "value": "GROSS"} ] },
              "changes": [ {"change": "renamed", "column": "GROSS", "to": "GROSS_AMOUNT", "typeBefore": "", "typeAfter": "",
                            "breaking": true, "at": "…", "outside": true, "consumers": [ … ]} ] } ]
```

On each computation (spec-space-lock §6), per Schema with a known set:

- **no previous entry** (a new Schema, or a previous lock without `columns`): a baseline, no changes;
- **the same set as recorded**: the recorded `changes` are carried forward; a consumer that no longer holds the
  same column link (it was fixed or removed) drops out;
- **a different set**: `changes` = the classes from the recorded set to the current one; a breaking change's
  consumers are the recorded consumers of the old column that still hold the same link. `outside` is true when
  the same computation marked the Schema file as changed outside the server. These replace the earlier changes.

So a Schema changed by hand while the server was stopped, or with a text editor while it ran, is reported on the
next computation, with what it breaks.

`GET /spaces/{id}/integrity` (`ready`) gains:

| Field | Meaning |
|---|---|
| `columnChanges.status` | `ready`, or `disabled` under the kill switch |
| `baseline` | `lock` when the previous lock held column sets, else `none` |
| `schemas`, `unknownSchemas` | Schemas whose set is known / unknown |
| `total`, `breaking` | changes reported / of them breaking |
| `items` | at most 200: `schema` (path) plus the change fields above; a breaking item adds `notAnalysed` |
| `notAnalysed` | `{total, items}`: every NOT_ANALYSED column link |

## 6. The warning on Schema save

`POST /config/write` (overwriting a Schema) and `POST /config/patch` (on a Schema) compare the stored set with
the saved one, after every existing gate (the BACKWARD `raw.fields` gate is unchanged and still refuses its own
cases) and before the maker-checker hold. Each breaking change adds a WARNING finding to `findings`:

- code `WARN_COLUMN_BREAKS`, field path `mapping.fields[<column>]`;
- message: the change, then `breaks N consumer(s): <kind> '<name>' (<key>), …` (or "no consumer in this Space
  reads it"), then the count of not-analysed SQL references.

The save is written as before (D-SE4 step 1: warn only). There is no draft exemption: a Schema has no
`active` flag, and the warning never skips a save. The consumers come from the Space as stored before the write.
A Schema saved with a rule-built mapping, or first written (no stored version), gets no warning.

## 7. Kill switch

`-Dcolumn.contract=off` (default `report`; any other value is `report`, so a typo never silences more than
asked). Off: the lock records no `columns`, the report answers `columnChanges: {status: disabled}`, and saves get
no warning. Turning it back on starts from a baseline, never a flood of changes. `SpaceCorpusEquivalenceTest`
loads every shipped Space with the switch off and on and proves the load identical. SE-3b will add a `refuse`
value.

## 8. Deliberately not covered

- **Refusal** of any kind (SE-3b: stopped with the list, `?force=true` goes ahead).
- **A deleted Schema**: its consumers lose their set and become UNCHECKED in the census; the change report covers
  only Schemas that still exist.
- **History of changes**: a Schema changed twice before anyone reads the report shows only the latest diff.
- **Columns from `processing.map.fields`, Job- or Enrichment-written stores, Dataset declared `columns`**: not a
  Schema's, so not here (the census still judges their links).
- **Pipeline-side raw-field reads** (`custom` expressions, `steps[].filter.where`): not census links (LC-5 open).
- **UI.**

## References

- Plan: `docs/superpower/2026-11-11-space-evolution-plan.md` §4.3, D-SE4.
- Space lock: [spec-space-lock.md](spec-space-lock.md).
- Census: `platform/inspecto-engine/src/main/java/com/gamma/pipeline/ColumnSets.java`, `LinkGraph.java`.
- Code: `inspecto/src/main/java/com/gamma/service/ColumnContract.java`, `SpaceLock.java`,
  `inspecto/src/main/java/com/gamma/control/ConfigWriteRoutes.java`.
- Tests: `ColumnContractTest`, `ControlApiColumnContractSaveTest`, `ControlApiSpaceIntegrityTest`.
