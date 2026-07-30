# Transform config reference (legacy `*_tx.json` format)

What `asn-transform`'s `LegacyTransformEngine` reads today. This documents the **legacy format**,
because that is what the ~23 production configs are written in and what the new engine reproduces
bit-for-bit. The new format designed in [REDESIGN.md](REDESIGN.md) §4.4 (`meta`/`framing`/`outputs`
sections, compiled expression AST, real `group`/`reduce`) is **not built yet** — see
[MIGRATION_STATUS.md](MIGRATION_STATUS.md).

## File handling

Not strict JSON. Before parsing, the loader (`TxConfig`, verbatim from legacy):

1. drops blank lines and lines starting with `--`,
2. collapses runs of whitespace to a single space,
3. **cuts everything from the first inline `--` to end of line.**

Step 3 applies inside string literals too. A `--` in a value is silently truncated. That is
legacy behaviour, reproduced on purpose; don't put `--` in a config value.

## Top-level shape

```jsonc
{
  "RecordTypeName": { ...section... },   // one per record type to flatten
  "SharedTypeName": { ...section... },   // referenced sub-record types
  "@simpleLookup": {                      // lookup tables, exposed to plugins
    "tableName": { "key": "value" }
  }
}
```

Keys not starting with `@` are record/type sections. Each top-level record-type key that matches a
decoded record's root name produces rows.

## Section keys

| Key | Meaning |
|---|---|
| `@keepSource` | keep the source field alongside a transformed one |
| `@transform` | `{ "OUT_FIELD": "funcName($arg, \"literal\")" }` — invoke a function (core or plugin) |
| `@derivedFields` | fields computed from already-populated row fields |
| `@autoJoin` | join a list sub-record into the parent row |
| `@rename` | output name for this field |
| `@prefix` / `@suffix` | affix the output name |
| `@useParentKeyAsPrefix` / `@useParentKeyAsSuffix` | affix with the parent key |
| `@group` / `@reduce` | **not implemented** — legacy only fed a static list nobody read |

## Flattening semantics

- A **map** child flattens into the shared parent row.
- A **list-of-maps** child produces one sub-row per element, **cartesian-joined** with the parent
  row. Several list children multiply. (This is why the corpus capture runs one JVM per case: the
  legacy engine could OOM on the product.)
- Field name collisions resolve last-write-wins, in config order.

## Function argument forms

| Form | Evaluates to |
|---|---|
| `"literal"` | the string itself |
| `$field` | the value of `field` in the current node, else in the accumulated row fields |
| `$$field` | indirect: the value of the field *named by* `$field`'s value |
| `@self` | the current node map |

Semantics that are load-bearing for parity:

- **A function that throws yields `null`** for the field — never a default, never an abort.
- **An unknown function name yields `null`**, silently.
- **A null `$param` drops the field entirely** rather than emitting an empty value.
- **A function returning a `Map` spreads** into multiple output fields (keys become names); a
  scalar sets the one named field.

## Functions

Core generics live in `asn-transform`'s `CoreFunctions` (currently `add`, `div` — ported as the
corpus needed them). Everything vendor-specific is a plugin: see [PLUGIN_GUIDE.md](PLUGIN_GUIDE.md)
and `asn-plugin-vendors` for the ~30 Ericsson CCN/OCC, Huawei MSC and operator-numbering functions.

Five names appear in production configs and are **intentionally unimplemented** —
`getStartEndTime`, `convertedClientDate`, `interOperatorIdentifiers`, `subscriptionId`,
`firstKey` — because legacy never implemented them either and the corpus rows depend on them
resolving to null.

## What the config does *not* specify

The `(grammar, root type, skipLines, file header length, record header length)` tuple that a file
needs in order to be framed and decoded **exists nowhere in `config/`**. It lived in deployment
wiring and had to be reconstructed per format; it is now captured in `GoldenCapture.CASES`. Any
new format migration starts by recovering that tuple — see [MIGRATION_STATUS.md](MIGRATION_STATUS.md).
