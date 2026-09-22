---
type: Plan
title: postmed_xdr — building a post-mediated telecom xDR Pipeline from a local inbox, and what the build revealed
description: A from-scratch Pipeline over a local inbox of post-mediated telecom usage records (voice/SMS/data), driven end to end and verified against the written Parquet — plus the eight grounded findings the build produced about the Pipelines workbench and the ingest audit trail.
tags: [pipeline-graph, ingestion, telecom, pipelines-workbench, evaluation]
timestamp: 2026-09-22T00:00:00Z
---

# postmed_xdr — a post-mediated telecom xDR Pipeline, built and driven

Built 2026-09-22 in the worktree `sbx/builder-pipeline` off `master` `b34581f6`, so a peer could keep
working `master`. Everything below was **run**, not reasoned about; every number is copied from an
artefact on disk, and where a first reading turned out to be wrong it is struck rather than deleted.

## 1. What was built

| Artefact | Path | Role |
|---|---|---|
| Pipeline | `spaces/demo/config/postmed/postmed_xdr_pipeline.toon` | local inbox → delimited parse → mapping → partitioned Parquet |
| Schema | `spaces/demo/config/postmed/postmed_xdr_schema.toon` | 18 `raw.fields` + the 1:1 `mapping`, `partitionKey: EVENT_DATE` |
| Dataset | `spaces/demo/config/registry/datasets/postmed_xdr_dataset.toon` | catalogs the store as a Dataset (`physicalRef: postmed_xdr/database`) |
| Sample generator | `spaces/demo/data/samples/gen-postmed-xdr.py` | deterministic (seed `20260922`); `--seed-inbox` copies into the poll dir |
| Sample stream | `spaces/demo/data/samples/postmed_xdr/` | 3 clean interval files (400 rows each) + 1 `.psv.defect` file |

**The feed being modelled.** Post-mediation output, not raw switch CDRs: the mediation platform has
already decoded, normalised and rated the switch-native records into one uniform pipe-delimited layout
and emits one file per collection interval (`PMXDR_<yyyyMMdd>_<seq>.psv`). That distinction is what
makes this an easy pipeline — see §5.1. The 18 columns carry the identifiers (`IMSI`, `MSISDN`,
`CELL_ID`, `APN`), the timing (`START_AT`, `EVENT_DATE`, `DURATION_SEC`), the volumes
(`BYTES_UP`/`BYTES_DOWN`), the money (`RATED_AMOUNT`, `CURRENCY`) and the mediation verdict
(`MED_STATUS` ∈ `RATED` | `UNRATED` | `SUSPENSE`).

**All data is synthetic and obviously so** — PLMNs in the ITU test range `001xx`, MSISDNs in the
reserved `+99` range, currency `XTS` (the ISO "reserved for testing" code). No operator name,
number or capture appears, per the repo's data rule; `spaces/*/data/samples/**` is the sanctioned
committed exception and this does not extend it.

Two real-world shapes are deliberately in the data, because a Pipeline that has only seen clean rows
has not been tested: an `UNRATED` record carries **no** `RATED_AMOUNT` (empty), and voice/SMS records
carry no byte counts and the literal strings `NULL`/`N/A` where a column does not apply — which is
what `csv_settings.null_strings` exists for.

## 2. The config, and why each block is there

The file follows the shipped telecom analogue `spaces/demo/config/roaming/roaming_tap_pipeline.toon`
(same space, same `processing.csv_settings` spelling rather than `parsing.delimited`), and every block
it carries is on the accepted-block table in
[`pipeline-config-keys.md`](../okf/backend/pipeline-graph/pipeline-config-keys.md).

- `dirs` — all nine leaves, on the `<space>/data/<name>/<role>` convention. Five are spec-declared;
  `errors` / `quarantine` / `markers` / `log_dir` are **engine-read and authoring-invisible** (that
  page's census caveat). They matter here: §4 shows the reject report landing in `dirs.errors`.
- `processing.file_pattern: "glob:**/*.psv"` + `collector.include[1]: "glob:**/PMXDR_*.psv"` — the
  interval files only. The pattern deliberately does **not** match `*.psv.defect`, which is how the
  defect file stays out of the happy path until invited in.
- `processing.duplicate_check` — marker-file, path-keyed, 30-day retention. ⚠ This is **file-grain**
  dedup, not record-grain; §4 finding F3 is the consequence.
- `processing.csv_settings` — `delimiter: "|"`, `has_header: true`, `comment: "#"` (the mediation
  header comment), `null_strings[2]: "NULL", "N/A"`, and both `date_formats`/`timestamp_formats`.
  Omitting the last two is what the validator warns about — see §5.4.
- `output: PARQUET/snappy`, `partitionKey: EVENT_DATE` in the schema → `year=/month=/day=` dirs.

Field types use the full vocabulary `SchemaFieldTypes` honours: `BIGINT` for byte counters (**not**
`DOUBLE` — lossy above 2⁵³, which is exactly where long numeric identifiers live), `INTEGER` for
duration, `DOUBLE` for money, `TIMESTAMP`/`DATE` for the two time columns.

## 3. Run 1 — the happy path

`java --enable-native-access=ALL-UNNAMED -Dassist.safety.roots=<space> -jar inspecto.jar <config>`
(the native-access flag and `assist.safety.roots` are both mandatory; the CLI does no space discovery,
so a `schema_file:` dies without the roots).

⚠ **Exit 0 and a three-line log are not evidence** — the log ends at *"Planned 3 batch(es)"* and says
nothing about the outcome. The verdict came from the artefacts:

```
consignment            status   member  rejected  in    out   files  bytes   cast_failures
default_51a82d8fd50f   SUCCESS  1       0         400   400   1      29537   0
default_8af597243dc3   SUCCESS  1       0         400   400   1      29714   0
default_591c46a7dd62   SUCCESS  1       0         400   400   1      29612   0
```

**1200 in, 1200 out, 0 rejected, 0 cast failures**, three Parquet files under
`database/year=2026/month=09/day=0{1,2,3}/`, three `.processed` markers, inputs moved to `backup/`,
and a lineage CSV mapping each input file to its output file and partition.

### 3.1 The check that mattered: did the declared types survive?

A declared type landing as `VARCHAR` is a known silent loss in this codebase's history, so the Parquet
schema was read back rather than trusted:

```
START_AT TIMESTAMP · EVENT_DATE DATE · DURATION_SEC INTEGER
BYTES_UP BIGINT · BYTES_DOWN BIGINT · RATED_AMOUNT DOUBLE · (12 × VARCHAR)
+ partition columns: year BIGINT, month VARCHAR, day VARCHAR
```

All 18 declared types were honoured. ⚠ Note the partition columns are **not** consistently typed —
`year` is `BIGINT` while `month`/`day` are `VARCHAR`; harmless for `year=/month=/day=` pruning, but a
predicate written against `month` needs a string literal.

### 3.2 …and is the data semantically right?

| Check | Result | Reading |
|---|---|---|
| `APN IS NULL` | 843 | = SMS (291) + VOICE (552) — the literal `NULL` was nulled |
| `OTHER_PARTY IS NULL` | 357 | = DATA rows — the literal `N/A` was nulled |
| `RATED_AMOUNT IS NULL` | 87 | = `MED_STATUS='UNRATED'` count, **exactly**; `RATED` and `SUSPENSE` have 0 unpriced |
| `BYTES_UP IS NULL` | 843 | = voice + SMS — empty field → NULL, not 0 |
| partition vs `EVENT_DATE` | 0 mismatched | `year‖month‖day` agrees with the column on every row |
| `START_AT` | `2026-09-01 00:04:41` → `2026-09-03 23:59:42`, 72 distinct hours | parsed, not zeroed |

Revenue reconciles by record type (VOICE 2893.34 · DATA 135.82 · SMS 12.23 XTS) and byte totals appear
only for `DATA`. The null semantics are the substance of this check: a post-mediated feed that turns an
unrated record's empty price into `0.00` would silently understate the suspense queue.

## 4. Run 2 — the defective interval file, and four findings

The `.psv.defect` file (20 data rows) was copied in as `PMXDR_20260904_001.psv`. It carries four
real-world faults: an unparseable `DURATION_SEC` (`not-a-number`), an impossible `EVENT_DATE`
(`2026-13-45`), one exact duplicate record, and one row truncated mid-write to 9 of 18 fields.

The run exited 0 and reported `SUCCESS`:

```
default_41fb475b1ced  SUCCESS  member=1  rejected=0  in=19  out=19  cast_failures=2
WARN DataTransformer - 2 value(s) failed their declared type coercion and were stored as NULL
     (the rows were KEPT): EVENT_DATE=1, DURATION_SEC=1
```

**F1 — cast failures are honestly audited (good).** The two bad values became `NULL`, the rows were
kept, the log names the columns, and `cast_failures=2` reaches the batch audit. Verified in the store:
the `MED-20260904-001` rows are 19 with exactly 1 NULL `EVENT_DATE` and 1 NULL `DURATION_SEC`. A cast
that nulls values *without* the failure audit counting them is the hole this closes, and it is closed.

**F2 — the batch audit loses the dropped row.** The file holds **20** data rows; the truncated row was
dropped and written to `dirs.errors/PMXDR_20260904_001_errors.csv` as `MISSING COLUMNS`. But the batch
row says `total_input_rows=19` and **`rejected_count=0`**. So a file that lost a record reports zero
rejects, and the input count silently excludes it — the only place the loss is visible is the errors
CSV, which nothing in the audit row points at. An operator reconciling mediation output against the
store by these counters would see 19 = 19 and conclude nothing was lost.

**F3 — record-grain duplicates pass through.** `REC_SEQ = 20260904-000011` is present twice in the
store. This is correct behaviour for the config as written (`processing.duplicate_check` is file-grain,
path/marker-keyed) but it is a live trap for a mediation feed, where a re-emitted interval file is the
normal failure mode. Record-grain dedup needs an explicit dedup Step, which in turn needs top-level
`output_store:` to arm. Worth stating in the ingestion docs beside `duplicate_check`, because the key's
name reads as if it covered this.

**F4 — the errors report is one row per missing column.** The single truncated line produced **nine**
rows (`c9`…`c17`), each repeating the whole raw line. A file with many short rows produces an errors
CSV that is mostly duplicated payload.

## 5. Driving the Pipelines workbench over it

Served the control plane from the worktree on `127.0.0.1:8099` (`-Dcontrol.bind=127.0.0.1`, Personal
edition = no authenticator, so binding to every interface would have exposed the config-write routes;
a peer already held `:8080`). UI served from the built `dist` via `-Dui.dir`.

**W1 — the flat config lifts into a correct graph.** Opening the hand-authored Pipeline rendered
`acq → parse → Record Transformer → postmed_xdr` with the collector drawer showing the real authored
values (include pattern, `Poll` discovery, duplicate detection), and the Record Transformer pane
deriving **all 18 fields** with `18 fields out · 0 changed · 0 calculated` and a peer Fields|SQL view.
Nothing about the file had to be graph-native for the workbench to understand it.

**W2 — Validate is clean and the messages are good.** Zero errors, zero warnings; four info findings,
all *"<node>: not yet tested."* The scaffold-level warnings are genuinely actionable — a Pipeline with
no `date_formats` is told *"TRY_STRPTIME will return NULL for any DATE column"*, which names the
consequence rather than the rule.

**W3 — "not yet tested" ignores real run history.** All four nodes read *not yet tested* while the
Pipeline had three completed CLI runs, four batches and 1219 rows in its own status store. The
workbench's confidence signal is per-node dry-run state only; it does not reconcile with
`/provenance`. On a Pipeline that demonstrably works, the builder still shows an untested graph.

**W4 — Dry-run seeds *after* the parse, so parse config is untestable in the builder.** The panel
states it plainly (*"a bounded sample through the transform→sink subgraph"*) and it worked — 2 pasted
rows produced `map → data=2 → sink → postmed_xdr → 2 row(s)`. But the delimiter, header, comment
character and `null_strings` — the only genuinely risky decisions in a post-mediated delimited feed —
are upstream of the seed and cannot be exercised. Worse, the sample rows must be **hand-typed as a
JSON array** while 1200 real rows sit in the Pipeline's own inbox and store; there is no "seed from my
inbox / from the last batch".

**W5 — adding a Step from the palette drops an orphan.** With `Record Transformer` selected, clicking
*Add Row filter* created `transform_filter_1` **disconnected**, below the chain. Validate caught it
(*"transform_filter_1: has no input connection."*) — it fails safe — but inserting one Step mid-chain
is then three manual graph operations (connect upstream, connect downstream, delete the old edge) with
no drop-onto-an-edge affordance. The typed config pane itself is good: the filter offered a
`Row predicate (after parsing)` field and accepted `MED_STATUS <> 'UNRATED'`.

**W6 — `GET …/graph` is not a valid `PUT …/graph` body; `graph/raw` is.** Taking the graph exactly as
the server served it on `/graph` and PUTting it straight back is refused **422** with
`NO_PERSISTENT_SINK` and `PARSER_NO_SCHEMA` — because `/graph` is a *display projection* and carries no
node `config`. The editor knows this and loads `/graph/raw` (confirmed on the wire). The refusal
reported `written: false` and the file was **byte-identical** afterwards (md5 unchanged), so it fails
closed. Still: one URL whose read shape is not its write shape is a trap for any other client.

**W7 — the round-trip is semantically lossless but reformats the file.** `GET /graph/raw` → `PUT
/graph` returned `written: true, findings: []` and **kept every key**, including the four undeclared
`dirs` leaves and `description` — the passthrough contract holds. 🔴 **A first reading of the diff
called this data corruption and that was wrong**: the writer emits unquoted scalars
(`delimiter: "|"` → `delimiter: |`, `comment: "#"` → `comment: #`), which *looks* like the classic
comment-character loss, but the TOON reader parses them back identically — re-read through
`/graph/raw` the values are still `"|"`, `"#"` and `["NULL","N/A"]`, and a re-run over the saved config
produced the same 400/400 rows with correct nulls and 21 columns. What is genuinely lost is
**formatting**: blank lines, column alignment, quoting, key order within `collector`, and the trailing
newline. For a team that keeps Pipeline configs in git, every workbench save is therefore a noisy
whole-file diff. The committed file here was restored to house formatting after the test.

**W8 — the created scaffold is good, but lands in the wrong place.** *New pipeline* → name +
format-picker (six formats, each with a one-line plain description, and inline
*"Choose the format this pipeline reads"* validation) → `Create` wrote a sensible scaffold:
`active: false`, `id` stamped, all nine `dirs` leaves derived from the name,
`processing.duplicate_check` on, `parsing.frontend: delimited` from the chosen format. It validated to
`clean: false` with the two useful format warnings from W2. **But it was written to
`spaces/demo/config/<name>_pipeline.toon` — flat at the space config root**, while all eight existing
Pipelines in that space live in a per-Pipeline directory (`config/postmed/`, `config/orders/`,
`config/roaming/`…). UI-created and hand-authored Pipelines therefore end up in two different layouts,
and a `<name>_schema.toon` sibling would land at the root too.

**W9 — the read-only lens still offers 36 enabled "Add …" buttons.** In the `View` lens the palette
renders every add control with `disabled: false`. Clicking one does **not** mutate the graph (the
handler guard holds — checked: no dirty flag, no node), so this is presentation, not integrity: a
keyboard or screen-reader user gets no disabled cue for 36 controls that do nothing.

⚠ **Two things first read as defects and were not.** `Ctrl+A` failing to select inside the sample-rows
editor reproduced in a *plain palette search box* too, so it was the automation's key dispatch, not the
app. And a "dead" *New pipeline* button was a viewport-emulation coordinate mismatch — with emulation
cleared the click registered and the form opened. Both were only settled by running a control; neither
belongs on a findings list.

### 5.1 What the palette says about telecom coverage

The palette is the served `ProcessorCatalog` taxonomy — the product's own roadmap, rendered with
undelivered entries visible but inactive. Counted from
`inspecto-engine/src/main/java/com/gamma/pipeline/ProcessorCatalog.java`: **119 processors —
36 DELIVERED, 16 PARTIAL, 67 PLANNED** across eight families (the UI's `X/Y` chips are
`DELIVERED+PARTIAL` over the family total, which matched: collectors `9/20`, parsers `7/17`).

For this feed that split is the whole story: **post-mediated ingestion sits entirely on the delivered
path** (local directory Collector + delimited parser + mapping + persistent sink), which is why the
Pipeline worked first run. The telecom-specific processors are the planned ones —
`transform.telecom.rating`, `transform.telecom.roaming` (TAP3/CIBER surcharge),
`transform.telecom.simbox`, and `parser.asn1.per` — while `parser.asn1.ber`, the decoder you would
need for *pre*-mediation switch CDRs, is delivered. Full analysis:
[`pipelines-workbench-moscow.md`](pipelines-workbench-moscow.md).

## 6. Reproducing this

```bash
python spaces/demo/data/samples/gen-postmed-xdr.py --seed-inbox
java --enable-native-access=ALL-UNNAMED \
     "-Dassist.safety.roots=<abs path to spaces/demo>" \
     -jar inspecto/target/inspecto-processor-*-shaded.jar \
     spaces/demo/config/postmed/postmed_xdr_pipeline.toon
```

Then read the verdict from `spaces/demo/data/postmed_xdr/status/*_batches_*.csv`, **not** from the exit
code. To exercise §4, copy `postmed_xdr/PMXDR_20260904_001.psv.defect` into the inbox as `.psv`.

## 7. What is left

Nothing is owed for the Pipeline itself — it is built, run and verified. The open items are the
findings, and they are **not yet filed as BACKLOG rows**: `BACKLOG.md` is a shared file and a peer is
working `master`, so filing them by hand here risks sweeping their edits (the staging hazard this repo
has already been bitten by). They should be filed as rows in the next change that legitimately touches
that file:

| # | Finding | Shape |
|---|---|---|
| F2 | batch audit reports `rejected_count=0` and an input count excluding a dropped row | bug — audit correctness |
| F4 | errors CSV emits one row per missing column | quality |
| F3 | `duplicate_check` is file-grain; record-grain duplicates pass | docs |
| W3 | node "not yet tested" ignores `/provenance` run history | product |
| W4 | dry-run cannot seed from the inbox, and cannot exercise parse config | product — the biggest builder gap |
| W5 | palette add drops an orphan; no insert-on-edge | product |
| W6 | `/graph` read shape ≠ `/graph` write shape | API |
| W7 | workbench save reformats the whole file (semantically lossless) | quality |
| W8 | created Pipeline lands flat at the space config root, not a per-Pipeline directory | consistency |
| W9 | read-only lens renders 36 enabled add controls | a11y |

## References

- [`pipeline-config-keys.md`](../okf/backend/pipeline-graph/pipeline-config-keys.md) — the accepted-block census this config conforms to
- [`pipelines-workbench-moscow.md`](pipelines-workbench-moscow.md) — the MoSCoW analysis this build fed
- `spaces/demo/config/roaming/roaming_tap_pipeline.toon` — the shipped telecom analogue this follows
