---
type: Concept
title: Sample profiler — one sniff and one type answer
description: The engine's sample profiler (com.gamma.parse.SampleProfiler) — the one dialect sniff and the one type vote that Add data, the parse preview and schema suggest all give, the operator's type rulings of 2026-11-19 (dates in words, the T timestamp, TIME, the decimal mark, almost-all numbers stay text) and the golden tests that pin them. Section P.3.6 of the parsing spec, kept on its own page.
resource: platform/inspecto-engine/src/main/java/com/gamma/parse/SampleProfiler.java
tags: [parsing, sniff, types, sample-profiler, add-data, schema-suggest, decimal-mark]
timestamp: 2026-11-19T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-20 code-reviewed
code: platform/inspecto-etl
---

# P.3.6 The sample profiler — one sniff, one type answer (`BASIC-ELT-PROFILE-1` BE-3)

Part of §P.3.6 of the [parsing specification](spec-parsing.md); the Grammar keys it reads (`quote: ""`,
`decimal_separator`) are in that page's key table.

**One sniff in the product (`BASIC-ELT-PROFILE-1` BE-3 slice 1, 2026-11-19).** The engine's sample profiler,
`com.gamma.parse.SampleProfiler.dialect(conn, file, encoding)` (`inspecto-engine`), runs `sniff_csv` with no authored
options and returns DuckDB's answer under the grammar option names (`delimiter`, `quote`, `escape`, `comment`,
`has_header`, `skip_header_lines`, `date_format`, `timestamp_format`), unshaped: DuckDB's `(empty)` ("none found") is
passed through, and an unsettled dialect (mixed line endings) throws. Both readers call it and keep their own shaping:
the parse preview (`ComponentPreview.parsing`, the `resolved` line) shows the map and shows nothing when the sniff
fails; Add data (`OnboardDraft`) passes its chosen encoding, falls back to its own delimiter guess (with `"`) when the
sniff throws, and adds its evidence and confidence. `DialectSniffGoldenTest` pins both readers' answers.

**Add data follows the sniff (BE-3 slice 2; operator ruling 2026-11-19, "follow the sniff").** Slice 1 left three
differences; they are now one rule — Add data takes the sniff's answer as it is: a quote not found (`(empty)`) is **no
quote** (it was `"`), so a stray quote such as `1,"x` is text in its value and the file imports (it was a 422); and the
lines the sniff skips before the header are skipped (`format.skipLines`, present only when the sniff skips lines), so
the real header is read (the first line was). The person's own `?delimiter=`, `?quote=` and `?header=` still win over
the sniff. The Pipeline Add data writes reads the file the same way: `skip_header_lines` when the draft skipped lines,
and `csv_settings.quote` as the draft read it - except that no quote (`quote: ""`, see the key table in [spec-parsing.md](spec-parsing.md)) is written
only when the file holds a `"`: a quote-free file reads alike under the engine's default `"`, which keeps a later file's
quoted fields whole (`ControlApiDecimalColumnTest` adds a later file with `"1,22"` values to an import made from a
quote-free file). A later file (Add a file, the inbox) is judged against the lines the import skips: when its own sniff
skips a different number of lines before the header it is refused (422, "it has N lines before the header line and
'<id>' skips M") — the run would otherwise lose its first row or read its header as a row.
`ControlApiOnboardDialectTest` imports each case. A no-quote import made from a file with a stray
quote reads a later file's quoted fields as text; the quote entry of the fix catalogue offers `"` when a draft's lines
do not split alike. ⛔ The
grammar editor cannot author "no quote" (clearing the field removes the key, which means `"`).

**One type inference (BE-3 slice 2; operator rulings 2026-11-19).** `SampleProfiler.types(conn, relation, limit,
decimalMark)` is the one entry for column types, and its **vote is the one answer** all three screens give: the parse
preview's `columnTypes` (`ComponentPreview.parsing`, voted over the very rows it parsed, all text — which are also what
schema suggest receives), schema suggest (`SchemaSuggest.infer`) and Add data (`OnboardDraft.typed`; a file that
declares its types, Parquet, keeps its declared type, now including `TIME`). The vote, over each column's first `limit`
values as text, takes the first rule every non-blank value meets:

| # | Values | Answer |
|---|---|---|
| 1 | numbers written with the decimal mark that a plain cast cannot read (`3,50`, `1.234,50`, `1,234.50`) | `DOUBLE` carrying the mark (`ColumnType.decimal`). Under a `,` mark a column with a value the mark does not read (`3.5`, which the import would read as `35`) skips rule 2: no number |
| 2 | whole numbers (never text with `.`/`e`, even `47.00`), then any number | `BIGINT`, then `DOUBLE` |
| 3 | a bare `TIMESTAMP` cast | `DATE` (all midnight) with its `DATE_FORMATS` entry, else `TIMESTAMP` with the first `TIMESTAMP_FORMATS` entry every value parses with — `2025-03-22T09:30:00` is `TIMESTAMP %Y-%m-%dT%H:%M:%S` (ruling 1) |
| 4 | one `DATE_FORMATS` entry (ambiguity refused), then one `TIMESTAMP_FORMATS` entry a bare cast cannot read | `DATE` with it (`March 22, 2025` is `DATE %B %d, %Y`, ruling 1), then `TIMESTAMP` with it |
| 5 | a `TIME` cast, then a `BOOLEAN` cast | `TIME` (`09:30:00`, ruling 2), then `BOOLEAN` |
| 6 | **almost all** (≥ 9 in 10 and ≥ 10 values, `SampleProfiler.mostly`) dates with a time, then dates, in one format | that type and format; `fits`/`of`/`odd` say how many fit and name one that does not, which the import leaves blank (finding 3) |
| — | anything else, **including a column that is almost all numbers** | `VARCHAR` (ruling 4: a number is proposed only when every value survives) |

**A later file's new column (`BASIC-ELT-PROFILE-1` B5, 2026-11-20).** A column an import keeps from later files is
typed from this same answer, file by file, and settled across the files of the run by the engine's drift service
(`SchemaDrift.settle`, see [spec-pipeline-execution-run-steps.md](spec-pipeline-execution-run-steps.md)): so a time of
day kept from a later file is `TIME`, as it is in a first file. The Dataset entry calls a `TIME` column `date`, the
temporal type a Dataset read gives it (`ResultSetDescriptor.columnType`).

**The decimal mark (ruling 3).** The preview and suggest read the Grammar's `decimal_separator` (the preview from its
draft's `csv_settings`, suggest from the body's `decimalSeparator`, which the Pipelines editor sends from the Grammar on
screen); Add data detects the mark (`DecimalMark.choose`, C1) and passes it. So `3,50` is a number in all three once
the Grammar says `,`; without the key the preview and suggest say text — as the engine would read it. Add data's own
layers stay its own because only it can see them: the person's stated types (finding 4), a fraction beyond the sample
(DECIMAL-TEXT-NOT-BIGINT-1), and the words and confidence of each proposal. `TypeInferenceGoldenTest` pins one answer
per case (every former `disagreement…` test is now an agreement test).

**What the rulings changed (2026-11-19).** Before, the preview gave DuckDB's own `read_csv` auto-detect type, schema
suggest the vote, and Add data the vote plus its layers; they differed on dates in words (text in the preview), times
of day (text in suggest and Add data, and Basic had no `TIME`), decimal-comma numbers (numbers only in Add data) and
almost-all-number columns (numbers only in Add data, the odd values blanked). The full-cycle transfers fixture
(461 rows, one `abc` amount, one `not-a-date` booking time) now reads `amount` as **text** everywhere — one value would
not survive a number, and its numbers carry no decimal comma, so rule 3 does not apply — and `booked_at` as
`TIMESTAMP %Y-%m-%dT%H:%M:%S` everywhere (rule 6: 460 of 461 values fit; the odd one is left blank).

## Open rows this concept owns

- **`CONVERT-TYPE-DECIMAL-MARK-1`**: *Change the type* (`convert.type`) casts with a plain `TRY_CAST` and ignores the decimal mark.
- **`ADD-DATA-DECIMAL-SEPARATOR-1`**: Add data writes a decimal-comma column as a per-column `EXPR` mapping
  (`OnboardImport.Plan.pipeline`, through `DecimalMark.sql`), not the Grammar's `decimal_separator`.
- **`GRAMMAR-NO-QUOTE-1`**: the Grammar editor's *Text quote* takes one character, so `quote: ""` (no quote) cannot be authored there.
