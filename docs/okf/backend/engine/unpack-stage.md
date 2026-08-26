# Unpack stage — decompression at the Collector

> **As-built.** The Collector-level stage that expands compressed/archived inbox files into plain
> files *before* Consignments are planned, and the run-level ledger that reports what became of each
> Archive. Shipped `d6cd55b7` (2026-08-23); honesty fixes `fe6e1d7e` and the ledger 2026-08-26.
> Plan (archived, complete): [`archived-documents/plans-archive/unpack-stage-plan.md`](../../../archived-documents/plans-archive/unpack-stage-plan.md).
> Related: [`ingestion.md`](ingestion.md) · [`consignment-status-flow.md`](consignment-status-flow.md) ·
> [`ingest-wrap-spi.md`](ingest-wrap-spi.md) · [`parser-plugins.md`](parser-plugins.md)

## 1. Where it sits, and why that is the whole trick

`CollectorProcessor.run` expands the **mutable candidate list** at `:117`, immediately before
`ConsignmentPlanner.plan` freezes it. Every expanded file is therefore an ordinary Consignment member
*from birth* — planner sizing, batching, schema selection, both engine lanes, audit and finalize all
work unchanged, with **zero** modification to the Consignment model.

⛔ **Do not "update the Consignment" with expanded members.** `Batch`, `Batch.Member` and
`BatchManifest` are immutable records and `ManifestStore` has no merge; adding members mid-flight
means a mutation API on the crash-safe commit path, for no gain. Expanding one step earlier is free.

**Engine-aware:** a format the chosen lane decodes itself is left alone — but only for `STREAM`
kinds. ⚠ A `.zip` the Java lane "reads" is only ever its FIRST entry (`Compression.firstEntry`), so
an ARCHIVE is always expanded here or its remaining members vanish silently.

**Failure is fail-open into the existing path:** when an expansion fails, the ORIGINAL stays in the
candidate list, the engine fails to read it, and the normal machinery quarantines it as `unreadable`
with a per-file audit row. Quarantining at the Collector would bypass the status ledger.

## 2. Naming, and the two things that are DATA

Entries materialize as `<NNNNN>_<flattened-entry-name>`. The zero-padded index makes **path order ==
archive order**, which matters because every entry shares an mtime and `ConsignmentPlanner` orders by
mtime with an absolute-path tie-break. Flattening (separators → `_`) removes the zip-slip vector; the
resolved path is still asserted to stay under `workDir`.

🔴 **The `NNNNN_` prefix is workspace bookkeeping and must never reach data.** Lineage and
`output.filename_column` record the **ENTRY name** (`good.csv`, never `00001_good.csv`) — the grain
the operator ratified 2026-08-26. The mechanism: the name is captured **at register time**
(`UnpackOrigins.register(actual, original, lineageName)`, kind-aware in `UnpackStage`), not
reverse-engineered per consumer, and `ArchiveDecompressorPlugin.entryName` — the exact reverse of
`entryPath` — lives in the same class so the two halves of the format cannot drift.

⚠ **`srcIdToFile` is a FIVE-site concern, not three.** The three `srcIdToFile.put` sites
(`CsvBatchStrategy`, `NativeCsvStreamingEngine`, `UnionModeIngester`) are the obvious ones; the wrap
lane names its file at `DuckDbRecordSink` construction rather than per row, so
`GenerationModeIngester` and `UnionModeIngester`'s sink path are two more. Grepping `srcIdToFile`
alone leaves that lane leaking the temp name.

⛔ Do not "simplify" `filename_column` to the container name, and do not widen it to the composite
`archive!entry` — that bakes a key downstream consumers must parse into a data column.

## 3. A skipped entry is not silence

An encrypted or unsupported-method member has readable metadata but no readable bytes
(`!canReadEntryData`). It is skipped — and **reported**, via the `expand(source, workDir, limits,
skippedOut)` default SPI overload (default-method, so external plugins are unaffected).

Skips are recorded against the ORIGINAL (`UnpackOrigins.registerSkipped`) and drained **exactly
once** by `finalizeSource` (`takeSkipped` is atomic; the first of the archive's batches to finalize
wins) into `SKIPPED_UNREADABLE` manifest rows addressed `archive!entry`, with **srcId `-1`, no backup
and no marker** — nothing was ever planned and there are no bytes.

An **all**-unreadable archive still fails whole.

## 4. The archive verdict (operator sign-off 2026-08-26)

`UnpackStatus` — the ARCHIVE's vocabulary. ⚠ Deliberately NOT the per-FILE status vocabulary: one
describes a container, the other a member. Do not merge them.

| Status | Meaning |
|---|---|
| `UNPACKED` | every entry found was ingested |
| `UNPACKED_PARTIAL` | ≥1 ingested **and** ≥1 not ingested — quarantined **or** skipped |
| `UNREADABLE` | expansion failed (corrupt / unsupported / cap breach), or entries existed and none was readable |
| `EMPTY` | archive opened, zero entries found |

🔴 **`UNPACKED_PARTIAL` COMMITS.** The verdict is **reporting, never a gate** — today's per-file
semantics are that a bad file never blocks its batch-mates, and failing whole would discard 499 good
ingests for one bad entry in a 500-entry archive. ⛔ Do not "fix" this by failing the Consignment.

🔴 **`EMPTY` and `UNREADABLE` are one code path** — both end in the same throw. They stay distinct
statuses by operator decision (an operator should hear "your zip is empty" apart from "your zip is
locked"), which is why `NoUsableEntriesException` carries `entriesFound()`: the count is the *only*
thing separating them, and it must never be recovered by string-matching the message.

## 5. The run-level unpack ledger

`<pipeline>_unpack_<runTimestamp>.csv` — the fourth ledger beside status/batches/lineage, one row per
**Archive** per **Run**. An Archive can outlive one Consignment (500 entries at `max_files: 100` plans
five batches), so its roll-up cannot be a `MemberEntry` in "the" manifest — there are five. The
expansion happens once per Run, so the Run is the honest home.

Columns: `run_id, archive_relpath, format, entries_found, entries_ingested, entries_failed,
entries_skipped, bytes_in, bytes_out, status, error, consignment_ids`.

⛔ **Declared ONCE** in `UnpackLedger.COLUMNS`; `HEADER` is `String.join`ed from it and the codec is
index-aligned to it, pinned by a test. This is a direct instruction from the plan: the **batches**
ledger restates its column list in FIVE places (writer header, row record, codec,
`OperationalTables.BATCHES`, a test literal), none generated from another — and because reads are by
header *name*, a stale mirror silently **hides** a column rather than erroring.

**Lifecycle:** accumulated in memory during the run, flushed once in `CollectorProcessor.run` **after
the batch futures join**. ⚠ Deliberately *not* written at the `UnpackOrigins.consume()` release
points, even though those are where an archive's last member lands: a batch that fails at COMMIT runs
neither the finalize nor the quarantine path, so the release never fires — for exactly the archives an
operator most needs a row for. `flush` is idempotent (it removes the run's rows).

🔴 **Keyed on the archive's normalized absolute PATH, never its basename.** `MemberAudit.origin` is a
display column and a bare filename; `in/east/data.zip` and `in/west/data.zip` share one. Keying the
roll-up on it would silently sum two archives into one row — which is why `MemberAudit` carries
`originPath` beside `origin`, captured in the same helper.

⚠ **Both origin fields must be captured at INGEST time**, not where the audit row is written:
`writeAudit` runs after `commit`, and commit's `UnpackStage.cleanup` consumes the mapping — a late
lookup reads blank for every expanded file.

**Only ARCHIVE kinds get a row.** A 1→1 stream expansion has no entries to roll up and its outcome is
fully described by its single file's own status row; a row here would double-report it.

**Read surface (2026-08-26):** `StatusStore.unpack(cfg)` — a `default` returning empty, so every
pre-existing `@PublicApi` implementer (incl. the anonymous test stores) keeps compiling — backed by
`FileStatusStore` (globs `<pipeline>_unpack_*.csv`) and `DbStatusStore` (projects into
`inspecto_status_unpack`, carried through sync/delete/rename/browse). `OperationalTables.UNPACK`
**references `UnpackLedger.COLUMNS`** rather than restating them, and `unpack` joined `STAGE1_NAMES`
so report-sql can query it.

### The per-file status ledger's `logical_name` column (2026-08-26)

Beside `origin` (§ above), the per-file `_status_*.csv` ledger carries `logical_name`: the SAME inbox
file's extension-insensitive **identity** (§6 below) — poll-relative, so unlike `origin`'s display
basename it IS a key. This is what a report groups on to unite a re-delivery with an earlier
compression spelling, rather than reading the alias hit out of the dedup log.

⚠ **For an expanded ARCHIVE this is the archive's identity, shared by every one of its entries** — one
delivery, one identity — never the entry's own name (that is what lineage records, through
`UnpackOrigins.lineageName`). ⚠ Computed from `MemberAudit.originPath()`, captured at INGEST time —
the identical trap `origin` already documents: a late lookup after `commit` reads blank. Appended
last (after `origin`) and QUOTED — a poll-relative identity may carry a comma.

## 6. Extension-insensitive identity, and why the collision is not a bug

One logical file may present as `cdr_20260823.csv.gz`, `cdr_20260823.Z`, `cdr_20260823.csv` or bare
`cdr_20260823`. `LogicalNames` derives one key for all of them: strip compression suffixes
**iteratively** (rule 1, from the *discovered* plugin suffixes — never a hand-mirrored twin list),
then strip **at most one** data extension (rule 2). ⛔ Never "everything after the first dot":
`feed.2026.08.23.csv` must key as `feed.2026.08.23`. Directories stay in the key — extension-
insensitive, never path-insensitive.

🔴 **The collision is the inescapable dual of the requirement.** Those four spellings meet *only* at
the fully-stripped tier — `cdr.csv.gz` → `cdr.csv` → `cdr`, and `cdr.Z` → `cdr`. That same strip is
why `report.csv` and `report.json` are also one logical file. ⛔ **Do not "fix" this with a two-tier
scheme** that matches on the compression-stripped form: it would break the operator's actual
requirement, which is the whole reason the mechanism exists. The remedy is the allow-list, not a
redesign.

`processing.unpack.data_extensions` (published, `FieldType.LIST`, default
`.csv .tsv .txt .json .jsonl .ndjson .xml`) is the escape hatch: narrow it, or author
`data_extensions[0]:` for an **empty** list to opt out entirely and key on verbatim names. Empty is
honoured as a CHOICE, never as "unset". Entries are normalised (bare / upper-case / padded → leading-
dot lower-case). ⚠ TOON's scalar-list form is **counted** (`data_extensions[2]: "a", "b"`), not
bracketed — a bracketed literal parses as one comma-split string, silently.

⚠ **ONE permitted mirror.** `ConfigSpecs` restates the default because `inspecto-config` sits *below*
`inspecto-etl` and cannot import `LogicalNames`; `LogicalNamesTest` pins them equal. A drifted
published default tells an operator the engine does something it does not. ⛔ Do not add a third.

**The two dedup lanes carry very different risk:**

| Lane | On an alias hit | Risk |
|---|---|---|
| Checksum / ledger | Finds a *candidate*; the hash still decides, so different bytes ⇒ `CHANGED` ⇒ reprocessed | Harmless |
| Marker (path) | `isAlreadyProcessed` returns true ⇒ the file is **dropped**, and nothing downstream can overrule it | 🔴 Real |

Which is why the marker-mode skip logs at **WARN** and names the remedy. Exposure is scoped: the
alias is only ever *written* for compression-involved names, so two plain files collide only after a
compressed spelling of that logical name has been processed.

## 7. Standing traps

- ⚠ **The per-file status vocabulary is still five bare literals** (`SUCCESS`,
  `QUARANTINED_EMPTY`, `QUARANTINED_MISMATCH`, `QUARANTINED_UNREADABLE`, `SKIPPED_UNREADABLE`) across
  ~6 files. The plan's step 14 predicted this drift and it happened anyway when the fifth was added.
- ⚠ `UnpackOrigins` entries are removed only by `consume()`. A batch that fails at COMMIT runs
  neither path, so its mappings leak — bounded in practice, unbounded in principle.
- ⚠ **Nested archives are refused by design** (`depth` must be 1, refused by name). A `.tar.gz`
  inside a `.zip` is extracted as an opaque member and then quarantines.
- ⚠ A crash mid-archive re-ingests committed members: the container is marked only after its LAST
  member, so a crash re-expands it whole. Relies on `OVERWRITE_OR_IGNORE` output idempotence.
- ⚠ **Two decompression vocabularies coexist** — this SPI and the legacy inline `Compression.java`
  (still used by the Java lane, `SchemaSelector`, `BoundaryScanner`, `FileChunker`). A format added
  to one is unknown to the other.
