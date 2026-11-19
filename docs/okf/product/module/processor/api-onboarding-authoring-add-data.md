---
type: Seam
title: Onboarding authoring seams (draft lifecycle, previews, register pair) — Add data — /onboard/files
description: Part 2 of 3 of Onboarding authoring seams (draft lifecycle, previews, register pair): Add data: /onboard/files (file in → Dataset out; LA-ONBOARD-1 phase 2, 2026-10-22)
tags: [control-plane, onboarding, config, enrichment, pipeline, reference]
timestamp: 2026-07-16T00:00:00Z
---

# Onboarding authoring seams (draft lifecycle, previews, register pair) — Add data — /onboard/files

Part 2 of 3 of [Onboarding authoring seams (draft lifecycle, previews, register pair)](api-onboarding-authoring.md) · previous: [Drafts, previews, the register pair and reference production](api-onboarding-authoring-drafts-previews.md) · next: [Add data — the commit onward](api-onboarding-authoring-commit.md).

## Add data: `/onboard/files` (file in → Dataset out; `LA-ONBOARD-1` phase 2, 2026-10-22)

One server route family turns a person's file into a Dataset in two calls, on every edition (Basic included), in
the core module (`OnboardRoutes`, `OnboardDraft`, `EntityShapes`, `OnboardImport`; not under `/inv`). It replaces
the nine-call walkthrough (parse preview → suggest → two `/config/write` → `/runs` → copy → trigger →
`/components/dataset` → view). The console's guided Onboard Stream / Reference keeps working beside it.

| Route | Capability | What it does |
|---|---|---|
| `POST /onboard/files?name=&kind=stream\|reference[&delimiter=&header=&quote=&encoding=&decimal=&recordPath=]` | `canUploadData` | raw body, streamed to `<data root>/_onboard/<draftId>/`; answers the draft |
| `POST /onboard/files/{draftId}/redraft` | `canUploadData` | the column types and roles the person stated on screen: answers the same draft again around them (below); writes no config |
| `POST /onboard/files/{draftId}/commit` | `canUploadData` | one confirm: writes, registers, lands, runs once, registers the Dataset; 200 run summary, or 202 above `import.inline_max_bytes` |
| `GET /onboard/runs/{runId}` | `canUploadData` | follows a 202 run (`running` / `succeeded` / `failed`): the caller's own, or any with `canAdminister` |
| `POST /datasets/{id}/files?name=[&mode=replace\|append]` | `canUploadData` | **Add a file** to an imported Dataset and run it (replace is the default; append for a Stream only) |
| `POST /datasets/{id}/run` | `canUploadData` | **Run now**: runs the import Pipeline over what waits in its inbox; on a Postgres snapshot, copies the table again (below) |
| `PUT /datasets/{id}/columns/{column}` | `canAuthorWorkbench` | one column's Entity Type: `{entityType: id\|null}` or `{classification: X\|null}`; and/or its `role`, a measure's `unit`, and `carried` (`false` keeps a Time, Measure or Category column off the links and the index; LA-COLUMN-ROLES-1) |
| `GET` / `PUT /settings/data` | open / `canAdminister` | the Space's `data.self_import`: `{selfImport, selfImportDefault, selfImportInForce}` |

**Gate order** for the Add data routes: capability 401/403 → no write root 503 → `data.self_import` off 403 ("Add
data is turned off in this Space; an administrator can turn it on", `OnboardRoutes.SELF_IMPORT_OFF`) → the route's
own checks. `data.self_import` lives in `data.toon` (a reserved config path: no import may write it); unset = on
with no sign-in, off with sign-in (`Authenticators.active()`); `/bootstrap` carries the value in force as
`selfImport`. Rate class: the standard bucket (`OnboardRoutes.isRateLimited`).

### The draft (the contract shared with `ONBOARDING-FIRST-RUN-1`)

```
{draftId, kind, file:{name, bytes, rows},
 format:{type, delimiter?, header?, quote?,                     // delimiter/header/quote for CSV / TSV only
         decimalMark?,                                          // every text format (not Parquet)
         recordPath?, flatten?,                                 // JSON / NDJSON
         fileDate:{value, format, name, evidence, confidence}},
 columns:[{name, header, type:{value, format, evidence, confidence}, sample:[up to 3]}],
 rows:[up to 20 preview rows],
 entityTypes:{columns:[{column, entityType:{value, classification?, label?, evidence, confidence},
                        role:{value, evidence, confidence}, distinct? (text columns)}],
              primary, link:{from, to, kind, primaryTime, primaryMeasure, details} (stream) | identifying (reference)},
 limits:{maxBytes, maxRows, maxImports, imports, inlineMaxBytes, excel},
 findings:[{entry, column?, cause, fix:{label, redraft? | columns? | newDataset?}}],   // C2, the fix catalogue (below)
 matches:[{column, dataset, datasetColumn, share, keys, found, by: name|entityType, entityType?, entityTypeLabel?, datasetKind?}]}  // C6a
```

* **Shared columns (`matches`, `OnboardMatches`; `ONBOARDING-FIRST-RUN-1` C6a, 2026-11-05).** Which Datasets already in
  the Space share a column with the file: the same name (any case), or a file column proposed as the Entity Type a
  Dataset column holds - its `classification` resolved to the Space's Entity Type (one that claims it, else one whose id
  it is; `OnboardMatches.typeOf`), and answered as that type's **id** (`entityType`, what the commit accepts) and name
  (`entityTypeLabel`). `share` = `found / keys`: of that Dataset column's distinct non-blank keys over its first
  1,000,000 rows (read over its trusted relation, `DatasetRelation`), the part among the file column's sampled values
  (the first 1,000 rows). Best first, at most 10; at most 20 column pairs are counted per draft, over at most 50 Datasets.
  A Dataset read outside the data root (a shared snapshot, a lakehouse table) is left out. Keys are compared as trimmed text, not through
  the Entity Type's normaliser: `+15550000001` in the file does not match `15550000001` stored as a number (seen live,
  2026-11-05; a later refinement, not built). **Counts only, no value leaves;**
  only Datasets the caller may read (`ComponentAccess.canView`), and never a column masked for the caller
  (`BiResultMasking.hidesFromCaller`: a count of matching keys would tell which keys exist). Best effort: a Dataset that
  cannot be read is left out, never an error. Not written to `draft.json` (the commit does not read it). Add data uses
  it for *Use as a lookup* (`layer/product/user-guide-onboarding.md`). Tests: `ControlApiOnboardMatchesTest` (by name with
  the share, partial, none; by Entity Type under another name; none for a viewer over a masked column, the count for a
  revealer; a lookup committed with the match's Entity Type id).

* **Every proposed choice is `{value, evidence, confidence}`**. OF-2 (operator 2026-10-25): `high` when every sampled
  value agrees and the sample has at least **200** non-blank values (`OnboardDraft.MIN_SAMPLE`; it was 50 until
  `ONBOARDING-FIRST-RUN-1` C1, 2026-11-05), `low` otherwise. A choice the user stated (`?delimiter=`, `?decimal=`,
  `?recordPath=` on a re-draft) is `high` by definition. The sample is the first 1,000 rows. A column's date or time
  format rides its `type` choice (`format`, the strptime pattern every sampled value parsed with: DATE through
  `SchemaSuggest`, TIMESTAMP from a fixed candidate list).
* **A column that is almost all one type (`LA-FULL-CYCLE-TEST-1` phase 3, 2026-11-15; `OnboardDraft.almost`).** The
  type vote needs every sampled value to agree, so one `not-a-date` made a column of 460 timestamps *Text* at `high`
  confidence, and the link then had no time. Now a text column whose non-blank sampled values are **at least nine in
  ten, and at least ten,** dates with a time (one of the TIMESTAMP candidate formats), dates (`DATE_FORMATS`) or
  numbers is proposed as TIMESTAMP, DATE, DOUBLE or BIGINT at `low` confidence, with the format the fitting values are
  written in and evidence that names a value that does not fit: `460 of 461 sampled values are dates with a time, but
  1 is not (e.g. 'not-a-date'); it will be left blank`. **Why nine in ten, not the four in five that marks a number
  column a guess:** this proposal blanks every value that does not fit, so it may cost at most one value in ten;
  between four in five and nine in ten a number column stays text (`…, so it is kept as text`, `low`) and a date
  column is offered through the fix catalogue's `date_format` entry. **Why ten:** below ten agreeing values one stray
  value is already a tenth of the column. **A whole-number column is proposed this way only when its name says an
  amount** (`EntityShapes.namedAsAmount`: amount, total, fee, …): digits with a few letters among them are more often
  identifiers, and a number type would blank them. Two formats that read the values equally well (day first or month
  first) are never guessed between: the column stays text. A column where **every** value is a date with a time in a
  candidate format a plain cast does not read (`21/09/2026 03:58:24`) is TIMESTAMP with that format too. The roles
  that follow see the column: the link's `primaryTime` and `primaryMeasure` are proposed, and a measure's evidence
  says `460 of 461 sampled values are numbers`, never "all". The values that do not fit are **left blank by the import,
  their rows kept, and counted** in the run summary's `blanked` (below). Tests:
  `ControlApiOnboardFullCycleTest` on the committed fixture `inspecto/src/test/resources/onboard/full-cycle/`
  (`tools/gen-la-import-fixture.mjs`).
* **Reasons in plain words (finding 10; `Words`).** One helper agrees in number (`1 is not`, `3 are not`; `1 distinct
  value`), and a reason never carries an engine type or an Entity Type id: `all 240 sampled values are decimal
  numbers` (it was `parse as double`), `booked_at holds dates with a time`, `the name 'phone' says Phone number` (the
  Entity Type's label, also answered as `entityType.label`). A date format is shown by a value (`like '2026-09-01
  00:05:00'`), not by its strptime pattern; the pattern stays in `type.format`.
* **A whole number is only text with no decimal point** (`DECIMAL-TEXT-NOT-BIGINT-1`, 2026-11-06). The type vote
  (`SchemaSuggest`) gives BIGINT only to digits with no `.` and no exponent: `47.00`, `370.00` are DOUBLE (the text
  says decimal even when every fraction is zero), so a later `12.25` is kept, not cut to 12. A column whose sample is
  whole numbers but whose file has a fraction further down (`OnboardDraft.fractions`, one scan of the whole file) is
  DOUBLE too, `high`, evidence `the 1000 sampled values are whole numbers, but the file also has '99.95', so a decimal
  number`. A later file into an existing BIGINT column with such a value is refused (la-core `architecture-data-onboarding.md` §6.17).
* **The decimal mark (`format.decimalMark`, C1; `DecimalMark`).** `.` (`1,234.50`) or `,` (`1.234,50`); digit groups
  may be split by the other mark or a space. A column decides when every sampled value reads with one mark and not the
  other (`12.5` → `.`; `12,5`, `1.234,50` → `,`); a whole part with a leading zero (`000 345 678`, `012.000.789`) is
  an identifier, never a number (known limit: a space-grouped identifier with no leading zero, such as a SIREN `512 100 554`, is still proposed as a number - drop it or set its type at commit); `1.234` reads either way and decides nothing (then `.` is assumed,
  `low`). Columns that disagree keep `.`, `low`, both named. No number with a mark: `high`, "changes nothing". A column
  whose numbers need the mark (a `,` mark, or grouping) is typed DOUBLE (never BIGINT: a later `7.500,25` keeps its
  fraction) and carries it in
  the draft's `plan` (`decimal`); the commit writes that raw field as VARCHAR and maps it with `fn: custom`,
  `TRY_CAST(REPLACE(…) AS DOUBLE)` (the engine's cast has no decimal-mark option; a `custom` field is outside the
  engine's cast-failure audit, so the import result counts this one expression itself: `blanked`, below). Not on
  Parquet (`?decimal=` 422).
* **JSON records (`format.recordPath`, `format.flatten`, C1; `OnboardJson`).** A `.json` file starting with `{` that
  holds ONE document is walked with Jackson's streaming parser; its records are the first list of objects in document
  order (a list inside a record is a field). `recordPath` is that list's dotted path (`response.transfers`; `$` for a
  top-level array or NDJSON), `high` when it is the document's only list of records and holds ≥ 200 records; the
  commit writes it as the Pipeline's `json.records_path` (`format: auto`). One object with no list: one line is NDJSON,
  more is 422. Nested objects are spread into columns by dotted selectors (`payer.account` → column `payer_account`;
  `flatten` lists the spread paths); a list stays one column of JSON text; a path that is an object in some records and
  a value in others stays whole (JSON text), so no value is lost. A key holding a `.` is escaped (`r\.s`), the
  engine's one notation. A nested document over 16 MB is 422 "save it as NDJSON" (DuckDB's `maximum_object_size`
  default; raising it is a deliberate deferral - its buffer cost was not measured). Add a file judges a later JSON file
  by these dotted selectors, read at the import's own `json.records_path` (a chosen `?recordPath=` included); an import
  written before C1 that reads a nested object whole by its root key still takes a file holding that object. Only `.` is
  escaped and a `"` is not, exactly as the engine's `selectorSegments` / `jsonPath` read them, so a key holding `"`
  fails the draft (422) rather than the commit.
* **A date in the file name (`format.fileDate`, C1).** `%Y-%m-%d` / `%Y_%m_%d` / `%Y.%m.%d` and `%Y-%m` / `%Y_%m` are
  `high`; a bare `%Y%m%d` or `%Y%m` digit run and day-first `%d-%m-%Y` are `low`; the date must be real, the year
  1990-2099. `value` is ISO (`2026-10-31` or `2026-10`), `name` the file name without it (`sales_2026-10.csv` →
  `sales`; UC-2); a commit with no `name` takes it (`OnboardImport.proposedStem`). No date: `value: null`, `high`.
* Reading uses a sealed in-memory DuckDB allowed only the draft directory. Delimited text: `sniff_csv` (it spells
  "no quote found" as `(empty)`, and fails on mixed line endings; then the delimiter is guessed from field counts
  and every format choice is `low`), field-count evidence from a quote-aware split of the first lines, and types by
  `SchemaSuggest` voting. Parquet: the file's declared types ("the file declares …"). JSON (array) and NDJSON:
  `read_json`. Excel (`.xlsx`): only when the DuckDB `excel` extension loads from the bundle (the staged
  `-Dduckdb.extension.dir`, else DuckDB's local cache; this route never runs an `INSTALL`); otherwise 422 "save
  '<file>' as CSV". `.xls`, zip archives and other types are 422 naming the formats that are read.
* **Value shapes** (`EntityShapes`): IMEI (15 digits with a valid Luhn check digit), IBAN (mod-97; maps to the type
  claiming `IBAN`, else `ACCOUNT`), e-mail (no seeded type claims it, so the column stays a detail and the evidence
  says so), `+` and 8 to 15 digits (MSISDN; bare 8 to 15 digits is a weak, `low` guess). Names: a token equal to an
  Entity Type id or classification, plus hint words (account/acct/iban, msisdn/phone/caller/called/a_number, imei,
  imsi, wallet, agent/till, cell). Only VARCHAR and BIGINT columns can hold identifiers. **From/to:** two columns
  of one Entity Type whose names say direction (from/to, payer/payee, sender/receiver, caller/called, debit/credit,
  src/dst) is `high`; the same type without direction words, or two different types, is `low`. Kind: a text column
  named type/kind/channel/… with at most 50 distinct sampled values. Primary Time (`primaryTime`): the TIMESTAMP
  (else DATE) column. Primary Measure (`primaryMeasure`): a numeric column named amount/value/sum/…. Details: the rest.
  **Roles (LA-COLUMN-ROLES-1 P1):** every `entityTypes.columns[]` row also carries `role` (a choice:
  `entity | temporal | measure | dimension | detail`) and, for a text column, `distinct` (its distinct sampled
  values). Entity when it holds an Entity Type; the link's kind / time / measure take Category / Time / Measure;
  else a DATE or TIMESTAMP is a Time, a DOUBLE (or a BIGINT named as an amount) a Measure, a text column whose values
  repeat under 200 distinct a Category (`high` under 20), anything else a Detail (always `high`). Primary: the Entity Type most columns
  hold (a tie is `low`). Reference: the identifying column is the Entity Type column whose sampled values are all
  distinct (an `id` or `key` name wins).
* Limits come from the configurable-limits catalogue (`LaLimit`, Space layer, `PUT /settings/link-analysis/limits`):
  `import.max_bytes` 500 MiB (streamed; a declared length is refused before reading), `import.max_rows` 10 M
  (counted at draft), `import.max_datasets` 20 (at commit), `import.inline_max_bytes` 64 MiB. Measured 2026-10-22
  on a laptop, 7-column CSV, no index: 65 MB / 1 M rows took 1.5 s to draft and 4.5 s to commit; 264 MB / 4 M rows
  took 3.2 s and 20 s. A 500 MB file would hold a request for about 40 s plus the index build, hence the 202
  threshold.
* Drafts expire after 24 hours (pruned on the next upload); a signed-in caller sees only their own (404 otherwise).

### The fix catalogue (`OnboardFixes`; `ONBOARDING-FIRST-RUN-1` C2, 2026-11-05)

"Problems in plain words" for both apps, server side, every edition. Seven entries (`OnboardFixes.ENTRIES`); a matching
draft finding, commit refusal or set-aside/rejected run carries `{entry, column?, cause, fix}`. A fix only edits what the
user is about to confirm - `redraft:{param: value}` (add the same file again to `POST /onboard/files` with these
parameters), `columns:[{name, type, format}]` (send them in the commit body) or `newDataset: true` (add the file as its
own Dataset) - and the user re-runs it; nothing is applied silently.

| Entry | Where it is found | Cause (example) | Fix |
|---|---|---|---|
| `decimal_mark` | draft: a text column whose values (≥ 4 in 5) are numbers that need the OTHER mark | "'amount' has numbers like '1.001,50' in 240 of 240 sampled rows: this file uses ',' as the decimal mark" | `redraft {decimal}` |
| `date_format` | draft: a text column whose values (≥ 4 in 5) are dates in one of seven formats; when another format reads exactly as many (`03/04/2026`: day or month first) the cause names it and the fix carries it under `alternatives` - the user chooses | "239 of 240 sampled values of 'ordered' are dates written %d/%m/%Y; 1 is not a real date (e.g. '31/02/2026') and would stay empty" | `columns [{name, type: DATE, format}]` (+ `alternatives`) |
| `delimiter` | draft: lines split into one column, or unevenly, and another of `, ; tab \|` splits every sampled line alike | "every sampled line is one column on ','; every line splits into 3 columns on ';'" | `redraft {delimiter}` |
| `header_row` | draft: no header line, yet the first line is distinct non-number names over a number column; or a header whose names over number columns are numbers | "the first line (meter, reading, zone) holds names, not values like the rows below" | `redraft {header}` |
| `encoding` | draft refusal (422) of a file that is not UTF-8; a commit whose run rejected rows or set the file aside, the whole draft file decoded | "'customers.csv' is not UTF-8 text; it reads as Latin-1 (Windows Western)", or, for a file starting with a UTF-16 byte-order mark (Excel's "Unicode Text"), "… is UTF-16 text" | `redraft {encoding: latin-1 \| utf-16}` |
| `quote` | draft: lines split unevenly under the quote, and no quote (else `"` or `'`) makes them split alike | "only 120 of 240 sampled lines split alike when '\"' quotes a field; with no character every line splits into 3 columns" | `redraft {quote}` |
| `missing_column` | Add a file refusal (422) and Run now's refused arrivals: a column the Dataset reads is not in the file | "'later.csv' has no column 'amount', which 'sales' reads" | `newDataset` |

* **Answers.** Draft: `findings` (stored in `draft.json` too). A refusal that matches is the usual error with
  `error.details.findings` (`OnboardRoutes.fixable` answers `OnboardFixes.Refusal`): the non-UTF-8 draft, Add a file's
  missing column. A commit whose run rejected rows or set the file aside explains it from the draft's file
  (`Job.explain` → `OnboardDraft.diagnose`): `findings` in the 200 summary (and on each `quarantined[]` entry), or in
  `error.details.findings` of the 422 "nothing was imported"; a followed (202) commit refused so keeps them on its run
  (`GET /onboard/runs/{id}` → `findings`). Run now's refused arrivals carry `findings` per file.
* **Encoding.** `?encoding=utf-8|latin-1|utf-16` (delimited text only; DuckDB's `read_csv` names; Java reads UTF-16 by
  its byte-order mark). The draft reads the lines and
  `sniff_csv` / `read_csv` with it and proposes `format.encoding`; the commit writes `csv_settings.encoding`,
  which the engine passes to `read_csv`; Add a file reads a later file with the import's encoding. A redraft fix does not
  apply to a later file, so Add a file answers a non-UTF-8 file as before (plain 422).
* **Measured (2026-11-05):** a stray `"` inside values does not set the file aside - the engine imports every row - so
  the quote entry is a draft finding only; bytes that stop being UTF-8 after the sampled lines make the run reject those
  rows (200, `rowsRejected`), which is where the run-time encoding finding comes from.
* Tests: `ControlApiOnboardFixesTest` - one test per entry (named after it; a reflection check fails when an entry has
  none) that produces the problem, applies the fix exactly as the finding states it, and re-runs to an imported Dataset;
  seven mutants (no decimal finding, no run diagnosis, no missing-column finding, no quote search, no date tie, no UTF-16
  mark, no findings on a followed run) each killed.
* **Not covered (seen 2026-11-05):** a ragged CSV that sniffs as one column split on `|`, and a pivot header
  (`country,2022,2023`) sniffed as no header, get no finding; a quoted multi-line field is a 422 from DuckDB's parallel
  reader with `null_padding` (outside the catalogue).

### The redraft: `POST /onboard/files/{draftId}/redraft` (`LA-FULL-CYCLE-TEST-1` phase 3, 2026-11-15)

The reasons of a draft are written once, on the server. A type changed on screen used to leave them standing ("the
values are text", "no column holds dates or times") and the screen had to be corrected in three places per column. Now
a stated type or role is one call: body `{columns:[{name, type?, role?}]}` - **every** type and role the person has
stated, the whole set each time (`[]` states nothing) - and the answer is the same draft, same `draftId`, proposed
again (`OnboardRoutes.redraft`):

* The file is read again with the format choices the draft was made with (`draft.json` keeps them as `overrides`), so
  nothing but what follows from the stated choices changes; the draft's uploader and age stay.
* A stated **type** (`VARCHAR|BIGINT|DOUBLE|DATE|TIMESTAMP|BOOLEAN`) is `high` by definition. Its evidence says how many
  sampled values it reads, tested as the import will read them (a number or a yes / no by a plain cast, a date by the
  one candidate format that reads the most values): `you chose Date and time: 460 of 461 sampled values are dates with
  a time, but 1 is not (e.g. 'not-a-date'); it will be left blank`, or `…, but none of the 461 sampled values is a
  date with a time: every value would be left blank`. A date type carries that format in `type.format` and in the
  draft's `plan`, so the commit reads the values (see the commit, below, for why that matters).
* A stated **role** (`temporal|measure|dimension|detail`; `entity` is 422 - a column is an Entity by the Entity Type it
  holds) is `high` (`you chose Detail for channel`), and the column then holds no Entity Type.
* Everything that depends on them is worked out again (`EntityShapes.propose` with the stated roles): each column's
  role, and the link's kind, `primaryTime` and `primaryMeasure` among the columns that may still hold them - a column
  stated a Detail is no longer the link's kind; one stated a Measure can be what the link is weighed by even when its
  name says no amount; a record type's usual roles never replace a stated one.
* The stored draft is replaced, so a commit that states nothing starts from what was last answered. No config is written.

Gates: capability 401/403 → no write root 503 → `data.self_import` off 403 → unknown, expired or another person's
draft (or a draft whose file is gone) 404 → 422 `columns` is not a list, an entry names no draft column, or states a
type or role outside the lists. Rate class: the standard bucket. The screen (`AddDataComponent`) sends it on every
Type or Role pick, keeps what the person changed and takes every other proposal from the answer (`adoptRedraft` in
`add-data.model.ts`); a failed call leaves the person's choice on screen and the commit states it. Tests:
`ControlApiOnboardFullCycleTest` (`aStatedTypeOrRoleIsProposedAgainByTheServerInOneCall`, `redraftIsGatedLikeTheDraft`),
`EntityShapesTest` (stated roles).
