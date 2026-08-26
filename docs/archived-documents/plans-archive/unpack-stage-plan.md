# The Unpack stage — pluggable decompression before the DuckDB engine

> Plan. Operator brief 2026-08-23: *"a pre-processor that takes the consignment before handing over to
> the duckdb engine, un-compresses with java plugins, transparently updates the consignment for each
> decompressed file on multiple threads (if safe), hands over to the current parsing engine, updates the
> consignment with bad files and marks the file unparsable, sends those errors and updates status — the
> end status must be against each source file."*
>
> **Operator refinement (same day):** handle these files **at the Collector level, keeping the EL less
> complex** — pluggable decompression, DuckDB-based parsing untouched; the Collector keeps track/logs
> **original and actual filename**; the consignment generator service generates Consignments **from the
> decompressed files**. And one added complexity: a filename may arrive with/without an extension, and
> carry **different extensions before and after decompression for the same file** — the duplicate check
> and reporting must handle this by **ignoring extensions** for such cases (§2.3).

## 1. Context — what exists, verified

| Fact | Where |
|---|---|
| Poll cycle builds a **mutable `List<File> candidates`** and only then freezes it into immutable `Batch`/`Batch.Member` records | `CollectorProcessor.java:105` → `:117` (`ConsignmentPlanner.plan`) |
| `Batch`, `Batch.Member`, `BatchManifest` are **immutable records**; `ManifestStore` offers whole-file `write`/`supersede` only — **no member append** | `Batch.java:15,23`, `BatchManifest.java:13`, `ManifestStore.java:21` |
| 🔴 **Quarantined members never reach the manifest** — `finalizeSource` builds `MemberEntry` from *survivors* only | `BatchProcessor.java:215-216` |
| Per-file status is a **bare String**, values scattered across ~6 files: `SUCCESS`, `QUARANTINED_UNREADABLE`, `QUARANTINED_MISMATCH`, `QUARANTINED_EMPTY` — no enum | `MemberAudit.java:12`, `CsvBatchStrategy.java:110,122,132` |
| Quarantine reasons are a separate, partly-overlapping vocabulary | `QuarantineManager.java:32-40` (`field_mismatch`/`unreadable`/`empty`/`corrupt_download`) |
| Parallelism is **batch-level**: virtual threads bounded by `Semaphore(processing.threads)`; members inside a batch run **sequentially on ONE shared `Connection`** | `CollectorProcessor.java:141-152`, `CsvBatchStrategy.java:68-69,99-153` |
| DuckDB needs a **real file path** — `read_csv('<path>')`; nothing streams a decoded `InputStream` into it | `DuckDbCsvIngester.java:244,292,385,591` |
| `Compression.java` decodes `.gz`/`.bz2`/`.zip`(first entry only) to an `InputStream`, consumed **only by the Java/univocity path** | `Compression.java:32-67` |
| `TarUtil.extractTar` materializes entries and **already has zip-slip defence** + an `.extracted.json` idempotency sentinel | `TarUtil.java:92-122` (guard `:107-109`), `:172-197` |
| **No** size cap, entry-count cap, or compression-ratio check exists anywhere | `Compression.java`, `TarUtil.java` — absent by omission |
| commons-compress **1.28.0** is unconditional on the engine classpath in every edition (covers tar/zip/`.Z`/xz) | root `pom.xml:139,232-233`; `inspecto-etl/pom.xml:78`, `inspecto-util/pom.xml:72`; `inspecto-engine/pom.xml:39,47,54` |
| SPI house pattern: interface + `ServiceLoader` + `META-INF/services/<fqcn>`, `id()`/`scheme()` matched in a linear scan, first match wins, fail-fast naming the built-in | `CollectorConnectors.java:29-33`; `ParserPlugin.java:1-90` |
| `dirs.temp` is the documented scratch home ("on the data volume, never the system /tmp") | `PipelineConfig.java:309` |
| Markers are keyed on the **poll-relative path** and written LAST in the crash-safe order | `MarkerManager.java:40-77`, `BatchProcessor.java:147-158` |
| Hierarchy is **`Run ⊇ Consignment ⊇ File`**, each with its own status | `GLOSSARY.md:383-387` |
| Planner order is `MTIME` (arrival) with an **absolute-path tie-break** | `ConsignmentPlanner.java:43,65-68` |

**Relationship to the existing wrap-SPI.** `StreamingFileIngester` + `RecordSink`
([`ingest-wrap-spi.md`](../okf/backend/engine/ingest-wrap-spi.md)) already handles *formats DuckDB
cannot read* by parsing records in Java. Unpack is a **different concern**: it produces *files*, not
records, and it is 1→N. It composes with the wrap-SPI rather than competing — an unpacked entry may
itself be ASN.1 and go on to a `StreamingFileIngester`. ⛔ Do not extend `StreamingFileIngester` to
mean "and it can also emit files".

## 2. Decisions

### 2.0 Collector-level placement — CONFIRMED by the operator (2026-08-23)

The refinement confirms §2.1's architecture in the operator's own words: the **Collector** owns
unpacking (`CollectorProcessor`'s collect step is exactly that layer), the EL stays untouched, and
"the consignment generator service generates consignments from the decompressed files" is precisely
`ConsignmentPlanner.plan` receiving the already-expanded candidate list. Nothing downstream of
`CollectorProcessor.java:117` changes. Two collector-owned additions the refinement makes explicit:

- **Original ↔ actual filename tracking is a Collector responsibility.** Every expansion (both
  `STREAM` and `ARCHIVE` kinds) logs `original=<poll-relative source> actual=<materialized path>`
  at the collect step — the "log the derived value beside its input" rule — and the same pair
  travels into the ledgers (§2.2's `origin` column; `MemberEntry.originalRelPath` as
  `archive-relpath!entry-name` per §4 step 8). The `unpack` ledger is the durable form of this log.
- **Filename identity across the compression boundary** — new, §2.3.

### 2.1 Expand *before* the Consignment exists — do not update it

The brief says "transparently update consignment for each decompressed file". **That is not
supported and should not be built:** `Batch` and `BatchManifest` are immutable records and
`ManifestStore` has no merge — adding members mid-flight means a new mutation API on the crash-safe
commit path, for no gain.

The same outcome is available for free one step earlier: **expand the `candidates` list at
`CollectorProcessor.java:105`, before `ConsignmentPlanner.plan` at `:117`.** Entries are then
ordinary members *from birth* — planner sizing, batching, schema selection, both engine lanes
(`CsvBatchStrategy` and `StreamingPluginBatchStrategy`), audit and finalize all work unchanged with
**zero** modification to the consignment model.

This is the whole reason the stage is cheap. Everything in §4 depends on it.

### 2.2 An Archive's verdict is a **Run**-level fact, not a Consignment member

The brief requires "the end status must be against each source file", and the source file the
operator dropped is the **Archive**. Two grounded obstacles:

1. **Failures are invisible in the manifest today** (`BatchProcessor.java:215-216`). Recording a
   status for an unparsable file at all requires extending `finalizeSource` to also write
   non-survivors — a real change on the crash-safe commit contract.
2. **An Archive can outlive one Consignment.** `archive.zip` with 500 entries against
   `batch.max_files: 100` plans **5 batches** — so the Archive's roll-up cannot be a `MemberEntry`
   in "the" manifest, because there are five.

⛔ **Refused: force all entries of one Archive into one Consignment.** It silently voids the
`max_files`/`max_bytes` guarantee that exists to bound memory and connection lifetime — one 50 000-entry
archive would plan one unbounded batch.

✅ **Chosen: an Archive row belongs to the Run.** A new `unpack` ledger (the fourth CSV ledger beside
status/batches/lineage) carries one row per Archive per Run:

```
run_id, archive_relpath, format, entries_found, entries_ingested, entries_failed,
entries_skipped, bytes_in, bytes_out, status, error, consignment_ids
```

⚠ **As built 2026-08-26 this list gained `entries_skipped`** — an entry the walk could not decode is
neither ingested nor quarantined, so without its own count `found - ingested - failed` silently
absorbs it. ⛔ **This block is prose, not the contract**: the columns are declared once in
`UnpackLedger.COLUMNS` and the header is joined from it — if the two ever disagree, the code is
right and this paragraph is stale.

This is honest to `Run ⊇ Consignment ⊇ File`: the expansion happens once per Run, before Consignments
are planned. Entries stay ordinary Files with per-Consignment status exactly as today, gaining one
additive `origin` column on the existing per-file status ledger that names their Archive.
⚠ Readers parse **by header name per file** (`Csv.readInto`), so *appending* `origin` cannot break old
ledger files — but the batches-ledger header has **five mirrors**
([`consignment-status-flow.md`](../okf/backend/engine/consignment-status-flow.md)); this new ledger
must not repeat that pattern — one declaration, read by name.

**Archive status vocabulary** — **SIGNED OFF by the operator 2026-08-26** (§6 Q1; `UNPACKED_PARTIAL`
widened from the original wording, which missed the skipped-unreadable entry):

| Status | Meaning |
|---|---|
| `UNPACKED` | every entry found was ingested |
| `UNPACKED_PARTIAL` | ≥1 entry ingested **and** ≥1 not ingested — quarantined **or** skipped-unreadable. Archive → backup, bad entries → quarantine. **The Consignment COMMITS** (§6 Q1b): the verdict is reporting, never a gate |
| `UNREADABLE` | unpack itself failed (corrupt/unsupported/cap breach), or no entry was readable — Archive → quarantine, reason `unreadable` |
| `EMPTY` | archive opened, zero entries found. ⚠ Distinct from `UNREADABLE` by operator decision, though both are one code path today — separating them means counting entries found, not merely failing |

### 2.3 Filename identity — the extension problem (operator-added complexity)

**The problem.** One logical file can present different names across its life:
`cdr_20260823.csv.gz` arrives, decompresses to `cdr_20260823.csv`; the same feed may later deliver
`cdr_20260823.Z` (stem only inside), or plain `cdr_20260823` with no extension at all. Today BOTH
duplicate mechanisms are extension-sensitive, so each spelling reads as a brand-new file:

- **Path markers** key on the poll-relative path verbatim (`MarkerManager.java:40-77`, checked in
  `CollectorProcessor.dedupLocal:384`).
- **Content dedup** keys the `AcquisitionLedger` by `(sourceId, relativePath)`
  (`AcquisitionLedger.java:22-26`, applied at `CollectorProcessor.java:409-446`) — the checksum
  detects *content* change, but the *lookup key* still carries the extension, so
  `cdr_20260823.csv.gz` and `cdr_20260823.csv` never even compare checksums.

**The design: a `logicalName`, derived in ONE place.**

```java
/** The extension-insensitive identity of a source file: the poll-relative path with every
 *  REGISTERED compression suffix stripped (iteratively: .csv.gz.Z → .csv), then at most ONE
 *  trailing data extension stripped (.csv/.tsv/.txt/.json/.jsonl/.xml/.ndjson). Directories kept. */
public static String logicalName(String relativePath)   // home: the unpack package, beside Decompressors
```

Rules, in order of what they protect:

1. **Strip iteratively, registered suffixes only.** The compression-suffix set comes from the
   discovered `DecompressorPlugin`s (never a hardcoded twin list — the one-concept-three-sites drift
   is a known failure mode here). `data.csv.gz` → `data.csv` → (data-ext strip) → `data`.
2. **At most one data-extension strip, from a small published allow-list.** ⛔ Never "strip
   everything after the first dot": feed names legitimately carry dots
   (`feed.2026.08.23.csv` must not collapse to `feed`). `ConfigSpecs` publishes the list
   (`processing.unpack.data_extensions`) so a deployment with `.dat` files can extend it.
3. **Directories stay in the key.** `a/x.csv` and `b/x.csv` remain distinct — extension-insensitive,
   never path-insensitive.
4. **Archive entries** get `logicalName(archiveRelPath) + "!" + logicalName(entryName)` — consistent
   with §4 step 8's addressable form.

**Where it applies:**

| Mechanism | Today's key | Becomes |
|---|---|---|
| Path marker (`MarkerManager`) | poll-relative path | `logicalName(path)` + marker files named by logical name (see migration) |
| Content ledger (`AcquisitionLedger`) | `(sourceId, relativePath)` | `(sourceId, logicalName)` — checksum semantics unchanged, so the same bytes under a new spelling now reads DUPLICATE and a re-delivery with different bytes reads CHANGED, exactly the existing `DuplicatePolicy` verdicts |
| Reporting (status ledger, `unpack` ledger, Run Detail ▸ Files) | filename verbatim | display BOTH: `filename` (actual) + `logical_name` column (additive, header-name parsed) — reports group/join on logical name, show actual names |

⚠ **Migration is the sharp edge.** Existing marker files and ledger rows are keyed by the OLD
spelling. Renaming the key silently forgets every already-processed file — the next poll would
re-ingest the whole backlog. So: on lookup, check the logical key **and fall back to the verbatim
key**; write only the logical key. A one-time miss upgrades the record. Pin this with a fixture that
has an old-style marker and asserts the file is NOT re-ingested.

⚠ **Collision honesty.** Two genuinely different feeds named `report.csv` and `report.json` in the
same directory collapse to one logical name under rule 2. This is the operator's stated intent
("ignoring extensions"), but it must be *visible*: when dedup drops a file whose logical key matched
a DIFFERENT verbatim spelling, the drop is logged and reported with both names
(`duplicate of <original spelling>`), never silent. If a deployment needs `report.csv` ≠
`report.json`, it empties `processing.unpack.data_extensions` — published config, not code.

## 3. The SPI

```java
public interface DecompressorPlugin {
    String id();                                    // "gzip", "zip", "tar", "compress-z"
    boolean supports(String fileName, byte[] magic); // extension AND magic bytes
    Kind kind();                                    // STREAM (1→1) or ARCHIVE (1→N)
    List<Path> expand(Path source, Path workDir, UnpackLimits limits) throws IOException;
}
```

Discovered by `ServiceLoader`, registered in `META-INF/services/com.gamma.etl.unpack.DecompressorPlugin`,
resolved by `Decompressors.forFile(...)` doing the linear first-match scan and failing fast with a
message naming the built-ins — the `CollectorConnectors.java:29-33` idiom verbatim.

**Why ServiceLoader here when `StreamingFileIngester` is deliberately a config FQCN.** The wrap-SPI
doc is explicit: config names *one* class, so discovery would be wrong there. Unpack is the opposite —
the format is discovered *from the file*, so the resolver genuinely needs the discovered set. State
this in the concept doc so it doesn't read as drift.

**`Kind` matters:** `STREAM` (1→1: `.gz`, `.bz2`, `.xz`, `.Z`, `.zst`) needs none of §2.2's ledger or
roll-up — one input, one output, existing per-file status is already correct. `ARCHIVE` (1→N: `.zip`,
`.tar`, `.tar.gz`, `.tgz`) is where all the new semantics live. **This split is the phasing.**

## 4. Phases

Each phase is independently shippable and independently verifiable.

> **Status 2026-08-23: the plan is BUILT end to end — Phases 1, 1b, 2, 3, 4 and the shipped halves
> of 5/6** (uncommitted; reactor **3547/0/0/5**, verified on a fresh log — see the trap note below).
> **Phase 6 (config):** `processing.unpack.{enabled,max_entries,max_entry_bytes,max_total_bytes,
> max_ratio,depth,threads}` as `PipelineConfig.Unpack` (absent block ⇒ `Unpack.defaults()`, absent
> KEY ⇒ the shipped cap — unlike `intake`, where an absent key inherits a `-D` global), validated in
> the record's constructor and published in `ConfigSpecs`. `depth != 1` is refused **by name** so the
> nested-archive non-feature reads as deliberate. **Phase 5 (threading):** archives expand on the
> stage's OWN bounded virtual-thread pool (never the batch semaphore — unpack runs *before* planning,
> so borrowing that permit would serialize it behind ingest), MDC propagated per worker, and the
> stage **decides before it expands** so `out` is reassembled in candidate order — thread scheduling
> cannot reorder what the planner sees (pinned: parallel output == sequential output, exactly).
> **Phase 4:** `finalizeSource` gained a `List<MemberAudit>` overload and now writes manifest
> `MemberEntry` rows for FAILED members beside the survivors — the operator's "end status against
> each source file". Crash-safe order is untouched (register → manifest → backup → markers LAST); a
> failed member gets a row and **no** backup/marker, because it went to quarantine and must not read
> as processed. The branch-aware graph path uses the no-audits overload and is unchanged.
> **Status 2026-08-26 — §6 is FULLY DRAINED and the ledger SHIPPED.** All four operator questions are
> answered (Q1 vocabulary + partial-commits · Q2 entry grain · Q3 resolved by building · Q4
> allow-list + published key + WARN on a dropped file), and with them: the run-level `unpack` ledger
> (`UnpackLedger`/`UnpackStatus`), the two honesty fixes (entry-name lineage, reported skips),
> `processing.unpack.data_extensions` published — completing step 17 — and step 19's docs. Pushed as
> `fe6e1d7e` → `6730cd66` → `e9750e20`; reactor **3603/0/0/5**, exit 0.
>
> **2026-08-26 pm: EVERY item in Phase 6 and step 4d is now SHIPPED — the plan has nothing left
> unbuilt.** The as-built lives in `okf/backend/engine/unpack-stage.md`; this file survives only
> for the historical Phase narrative and can now be archived.
> ⛔ **Archive this plan only once Phase 6 lands** — the as-built already lives in
> [`okf/backend/engine/unpack-stage.md`](../okf/backend/engine/unpack-stage.md), which is what to read
> for behaviour; this file survives only for its unbuilt Phase-6 detail.
> ⚠ **Verification trap worth keeping:** a verify agent reported this run as PASS at the PREVIOUS
> total (3542) off a stale log — the new tests showed their old counts (11/3 instead of 15/4). The
> totals matching the previous baseline *exactly* while new tests were added is the tell; re-run and
> count per test class, never trust the summed total alone.
>
> **Earlier the same day — Phases 1, 1b, 2 and 3** (reactor was 3542/0/0/5 at that point).
> Archive half (Phase 2 caps + Phase 3): zip / tar / tar.gz via `ArchiveDecompressorPlugin`, entries
> named `<NNNNN>_<flattened>` so **path order == archive order** (the planner's mtime tie-break has
> nothing else to go on), zip-slip flattened *and* asserted, `max_entries`/`max_entry_bytes`/
> `max_total_bytes`/`max_ratio` enforced during the walk with **every** written file deleted on a
> breach — including the in-progress one. `UnpackOrigins` now **refcounts** expansions and
> `finalizeSource` **defers an expanded original's backup+marker to its LAST member**: with
> `batch.max_files: 1` (the DEFAULT) an N-entry archive's members land in N batches, and marking the
> container early would strand every member still to come. Whoever consumes the last member disposes
> of the container — `finalizeSource` backs it up when that member succeeded, `QuarantineManager`
> quarantines it when it did not (else a wholly-bad archive would never leave the inbox and every
> poll would retry it). A bad *entry* is filed as `<archive>!<entry>` under the archive's own
> relative parent, and the container completes normally.
> ⚠ Three resolution/loading facts found by testing, each a silent-wrong-answer if missed:
> **(a)** `.gz` is a suffix of `.tar.gz` and both plugins match the same magic — resolution is
> **longest-matching-suffix**, not first-match, because ServiceLoader's order across jars is
> unspecified and expanding a tar.gz as a stream yields one undifferentiated blob;
> **(b)** ServiceLoader needs **public** classes with **public** no-arg constructors — package-private
> nested plugins load as a `ServiceConfigurationError`, i.e. every plugin disappears, not just yours;
> **(c)** a `.zip` on the Java lane was previously only ever its FIRST entry
> (`Compression.firstEntry`), so archives are expanded here on **both** lanes — the lane-reads-it-itself
> skip applies to STREAM kinds only.
>
> **Earlier the same day — Phases 1 + 1b + the STREAM half of 2** (reactor was 3530/0/0/5 at that
> point). Shipped: `com.gamma.etl.unpack` (`DecompressorPlugin` SPI + `Decompressors` resolver +
> gzip/bzip2/`.Z` built-ins — xz/zstd are deliberately absent, their codecs are not on the classpath
> and DuckDB reads `.zst` natively), `UnpackStage` wired at `CollectorProcessor.ingest` (engine-aware,
> fail-open), `UnpackOrigins` + hooks in `finalizeSource`/`QuarantineManager`/`MarkerManager` (every
> poll-relative side effect runs against the ORIGINAL; scratch cleaned last), `LogicalNames` +
> marker ALIAS + logical-first ledger lookup with verbatim fallback, stream caps in
> `StreamDecompressorPlugin` (entry/total/ratio, no-partial-on-breach), fail-closed
> `csv_settings.compression` validation + `ConfigSpecs` enumField. **Deviations from the letter of
> the plan:** the marker identity is an ALIAS marker beside the unchanged primary (never a renamed
> primary — the primary's atomic `createFile` is `FinalizeSourceConcurrencyTest`'s pinned race
> contract), and the alias is *written* only by compression-involved names (the operator's "for such
> cases") while *lookup* checks it for every file — so plain-only inboxes are byte-for-byte
> unchanged. Step 4d (`logical_name` ledger column) is NOT built. 🔴 Found in verification:
> commons-compress 1.28's bz2 decode needs commons-io ≥2.13 at runtime, and inspecto-engine's
> test-scoped `embedded-postgres` demoted it to 2.11.0 via nearest-wins — now pinned to 2.20.0 in
> root `dependencyManagement`; a codec that works in one module's tests can still be broken in the
> module that ships it.

### Phase 1 — SPI + STREAM formats (no new status semantics)
1. `DecompressorPlugin` + `Decompressors` resolver + `META-INF/services`, mirroring the house idiom.
   → *verify:* a unit test registers a toy plugin and asserts resolution by magic bytes, and a
   fail-fast message naming built-ins for an unknown format.
2. Built-ins wrapping commons-compress: gzip, bzip2, xz, `.Z` (`ZCompressorInputStream`), zstd.
   → *verify:* round-trip each fixture; assert byte-identical output vs the uncompressed original.
3. Wire into `CollectorProcessor.java:105` for `Kind.STREAM` only: decode into
   `dirs.temp/<runTs>/unpack/`, swap the candidate. Skip anything DuckDB already reads natively
   (`.gz`, `.zst`) unless `engine: java` — don't pay a decode to hand DuckDB what it decodes itself.
   → *verify:* a `.bz2` and a `.Z` CSV ingest end-to-end on the **native** engine (today they
   quarantine as unreadable); a `.gz` still takes the DuckDB-native route (assert no temp file).
4. **Fail-closed `compression` validation** — refuse `compression: zip|tar|Z` at config load with a
   message pointing at the unpack stage, instead of quarantining every file at run time
   (BACKLOG §4 row, 2026-08-23). Publish the accepted set in `ConfigSpecs`.
   → *verify:* a refusal test per bad value; `auto|gzip|zstd|none` still load.

Phase 1 alone closes the engine divergence (`.bz2`/`.zip` working on `engine: java` but not native)
and needs **no** consignment change at all.

### Phase 1b — logical filename identity (§2.3; required as soon as Phase 1 ships)
4b. `logicalName(...)` beside `Decompressors`, suffix set fed by the discovered plugins + the
    published `processing.unpack.data_extensions` allow-list.
    → *verify:* a table-driven test over the §2.3 rules — `data.csv.gz`→`data`,
    `feed.2026.08.23.csv`→`feed.2026.08.23`, `a/x.csv` ≠ `b/x.csv`, no-extension names unchanged.
4c. Marker + `AcquisitionLedger` lookups go logical-first with **verbatim fallback**; writes are
    logical-only. Duplicate drops whose match was under a different spelling are logged and
    reported with both names, never silent.
    → *verify:* the migration fixture (old-style marker ⇒ NOT re-ingested); `x.csv.gz` after
    `x.csv` reads DUPLICATE under checksum mode with identical bytes and CHANGED with different
    bytes; the drop line names both spellings.
4d. ~~Additive `logical_name` column…~~ **✅ SHIPPED 2026-08-26.** `BatchAuditWriter.FileRow` gained
    `logicalName` (two compat arities, so a pre-unpack or origin-only caller still constructs a row),
    the codec appends it QUOTED after `origin` (a poll-relative identity may carry a comma),
    `OperationalTables.FILES` carries it, and `BatchProcessor.logicalNameOf` computes it from
    `MemberAudit.originPath()` — captured at INGEST time, the same trap `origin` already documents,
    never resolved post-commit. ⚠ For an expanded entry this is the ARCHIVE's identity (shared by
    every entry of the delivery), not the entry's own name — Run Detail's Files tab needs no UI change
    (no explicit `columnDefs`, `autoColumns` surfaces it automatically, same as `origin`).

### Phase 2 — Safety limits (before any ARCHIVE support ships)
5. `UnpackLimits`: `max_entries`, `max_entry_bytes`, `max_total_bytes`, `max_ratio`, `depth`.
   Zip-slip: reuse `TarUtil.java:107-109`'s `normalize()`+`startsWith` check; additionally refuse
   absolute paths and any `..` segment. **Nesting depth defaults to 1** — a zip-of-zips is a bomb
   vector, so recursion is opt-in, never default.
   → *verify:* a zip-bomb fixture trips `max_ratio` and the Archive lands `UNREADABLE` with the cap
   named in `error`; a zip-slip fixture is refused and **nothing is written outside `workDir`**
   (assert on the filesystem, not on the exception).

### Phase 3 — ARCHIVE formats + the Archive verdict
6. zip / tar / tar.gz plugins (materializing, per §1: DuckDB needs paths).
7. Entry naming: `<archive-stem>/<NNNNN>_<entry-name>` under `dirs.temp/<runTs>/unpack/`. The
   zero-padded index makes **path order == archive order**, which matters because entries share an
   mtime and `ConsignmentPlanner.java:65-68` tie-breaks on absolute path — without it, member order
   is deterministic but arbitrary.
   → *verify:* a 3-entry archive plans in archive order under `Order.MTIME`.
8. `MemberEntry.originalRelPath` for an entry uses the JAR-style `archive-relpath!entry-name`, so an
   entry remains addressable and traceable with no schema change.
9. **Mark the Archive, not the entries.** Markers key on poll-relative path
   (`MarkerManager.java:40-77`); entries have no poll-relative existence, and the Archive's marker is
   already exactly the "don't reprocess this" record.
   → *verify:* a second poll cycle re-ingests nothing after an archive completes.
10. ⚠ **Never write into `dirs.poll`.** Entries there would be rediscovered by the next cycle's walk
    (`CollectorProcessor.java:277-307`) as unmarked candidates — an ingest loop. `dirs.temp` only,
    resolved against the safety-roots union (`-Dassist.safety.roots` ∪ `DiscoveredRoots`), **never
    jailed against `configDir`**.
    → *verify:* an assertion that no unpacked path resolves under `dirs.poll`.
11. Temp cleanup after finalize; Archive → backup on success (it is the real input), → quarantine
    `unreadable` on unpack failure.

### Phase 4 — Status against each source file
12. Extend `finalizeSource` to record **non-survivors** as `MemberEntry` with their
    `QUARANTINED_*` status — the change §2.2 identified. Keep the crash-safe write order
    (`BatchProcessor.java:147-158`) untouched: manifest before backup, markers last.
    → *verify:* a batch with one good and one unreadable file yields **two** manifest entries;
    re-run the crash-order test to prove ordering is unchanged.
13. ~~The `unpack` ledger + the additive `origin` column on the per-file status ledger (§2.2).~~
    **✅ BOTH SHIPPED** — `origin` 2026-08-23; the ledger 2026-08-26 once §6 Q1 was answered.
    `UnpackLedger` + `UnpackStatus`, written to `<pipeline>_unpack_<ts>.csv`, accumulated across the
    run and flushed in `CollectorProcessor.run` after the batch futures join. ⛔ Columns declared
    ONCE (`UnpackLedger.COLUMNS`) — the anti-mirror rule this section demanded, pinned by a test.
    ~~Open: no READ surface yet~~ **✅ READ SURFACE SHIPPED 2026-08-26** — `StatusStore.unpack`
    (default-empty), both store impls, and `OperationalTables.UNPACK` = a REFERENCE to
    `UnpackLedger.COLUMNS` (never a restated list); `unpack` joined `STAGE1_NAMES`.
14. ~~⚠ STILL OPEN~~ **✅ SHIPPED 2026-08-26** — `com.gamma.etl.MemberStatus`, the member (per-file)
    vocabulary in one declaration; the constant name IS the wire form (`name()` at every write site),
    kept apart from `UnpackStatus` exactly as below. The compiler found a SIXTH bearer file the
    grep-scoped estimate missed (`PipelineTestRun`) — the point of retyping over aliasing. Original:
    promote the per-file status strings to a **single enum**. There are now **FIVE** bare literals across ~6 files, not four:
    `SUCCESS`, `QUARANTINED_EMPTY`, `QUARANTINED_MISMATCH`, `QUARANTINED_UNREADABLE` and
    `SKIPPED_UNREADABLE` (added 2026-08-26 by the open-item (4) fix — this section predicted exactly
    that drift and it happened anyway). ⚠ The new `UnpackStatus` enum is the ARCHIVE vocabulary and
    is deliberately NOT this: one describes a container, the other a member. Do not merge them.
    → *verify:* one declaration, all call sites referencing it, no literal `"QUARANTINED_` left.
15. Error reporting: the existing `errors/<base>_errors.csv` + quarantine tree already carry
    row-level evidence and need no change. An Archive-level failure reports through the new ledger's
    `error` column and the existing terminal `BatchEvent`/Signal.
    ⛔ **No per-entry Signal.** A Signal is a durable ledger write that can trigger `on_signal` jobs;
    emitting one per entry while the run claim is held is the re-entrancy class
    `consignment-status-flow.md` already forbids.

### Phase 5 — Threading
16. Unpack is **pure I/O with no DuckDB connection**, so it parallelizes safely — but not for free:
    - Parallelism unit = **one Archive** (never entries within one; a single archive stream is
      sequential by construction for tar/gz).
    - Its own bounded pool with a dedicated knob (`processing.unpack.threads`, default = min(4,
      `processing.threads`)). ⛔ Do not reuse the batch `Semaphore` — unpack runs *before* planning,
      so nothing is competing yet, and borrowing that permit would serialize unpack behind ingest.
    - **MDC must propagate** (`CollectorProcessor.java:148,153`) or log/space routing breaks.
    - ⚠ `ConcurrencyControlsTest` already warns when `processing.threads × duckdb_threads`
      oversubscribes cores; unpack threads must join that accounting, not dodge it.
    → *verify:* extend `ConcurrencyControlsTest` for the new knob; a multi-archive fixture unpacks
    concurrently and produces byte-identical output to the sequential run.

### Phase 6 — Surfaces
17. ~~`ConfigSpecs` publication for every `processing.unpack.*` key.~~ **✅ COMPLETE 2026-08-26** — the
    last unpublished key, `data_extensions`, shipped with §6 Q4.
18. ~~UI: unpack settings…~~ **✅ SHIPPED 2026-08-26.** ⚠ Deviation from the letter of this step: the
    settings are **NOT** on the shared `<inspecto-collector-config>` — that component authors the
    `collector:` block and is rendered whole by Onboarding's Collection stage, so folding borrowed
    `processing:` keys in would give that stage fields it would write where nothing reads them. They
    render as their own **Unpack** group in the collector pane, exactly the precedent marker dedup
    set. Run Detail's Files tab needed no change: it has no explicit `columnDefs`, so `autoColumns`
    already surfaces the `origin` the ledger has carried since 2026-08-23 (the polished
    "From archive" treatment stays on Processing Status ▸ Problem files). The mock is not more
    lenient — it serves the SAME regenerated `node-attributes.contract.json` the server is
    byte-compared against.
19. ~~Docs: new OKF concept + GLOSSARY entries.~~ **✅ DONE 2026-08-26** — `okf/backend/engine/unpack-stage.md`
    written and indexed; **Archive**, **Entry** and **Unpack** are in GLOSSARY §5 with an
    Entry-≠-Member row in §11's *Resolved collisions*. **The code sweep landed 2026-08-26**
    (BACKLOG §4 unpack item 13): `com.gamma.etl.unpack` says Entry everywhere an archive's inner
    file is meant, incl. the operator-facing WARN; `Batch.Member`/`MemberAudit`/`MemberEntry`
    untouched, and the three genuinely-Consignment-member sentences kept.

## 5. Deliberately out of scope

- **The existing Tar CLI stays.** `MainApp copy-tars`/`extract` (`TarUtil`/`TarArranger`/
  `TarInboxPreparer`) is a *different workflow* — pre-arranging an inbox into date buckets before any
  pipeline runs. This stage does not replace it; it removes the *requirement* to use it.
- Recursive/nested archives beyond `depth: 1` (opt-in only).
- Encrypted archives (needs a secret-reference design; `${ENV:…}` only, never a literal).
- Multi-part/split archives (`.z01`, `.part1.rar`) — arrival-completeness is a Collector question.

## 6. Open — needs the operator

> Each question now records **what the shipped code does while it is unanswered**, so the default is
> visible rather than implied. The full open-items list, with every workaround and its restriction,
> is the two `BACKLOG.md` §4 rows added 2026-08-23 ("Unpack stage — open items", "Delimited
> error-handling knobs — open items"). The two honesty gaps that were pulled forward are **both
> FIXED 2026-08-26** (they were defects on any Q1/Q2 answer, so they did not wait):
>
> - ~~🔴 **Lineage records the internal member name.**~~ **FIXED** — lineage/`filename_column` now
>   records the ENTRY name, stored at register time (`UnpackOrigins.register(..., lineageName)`;
>   `ArchiveDecompressorPlugin.entryName` reverses `entryPath` in the same class). Q2's defect half
>   is gone; entry grain is in force pending ratification.
> - ~~🔴 **An unreadable archive member is silently skipped**~~ **FIXED (honesty half)** — skips are
>   reported via `expand(..., skippedOut)` and drained once by `finalizeSource` into
>   `SKIPPED_UNREADABLE` manifest rows (`archive!entry`, srcId -1, no backup/marker). An
>   all-unreadable archive still fails whole; the archive-LEVEL verdict stays Q1's.

1. ~~**Archive status vocabulary**~~ **✅ ANSWERED by the operator 2026-08-26.**
   **(a) The four statuses stand, with `UNPACKED_PARTIAL` WIDENED.** Its §2.2 definition said "≥1
   entry ingested, ≥1 **quarantined**" — which no longer covers every case: a skipped unreadable
   entry (encrypted / unsupported method, fixed 2026-08-26) is never quarantined, because there are
   no readable bytes to move. An archive with one encrypted member and four good ones matched
   neither `UNPACKED` nor `UNPACKED_PARTIAL` as written. Binding definition:

   | Status | Meaning |
   |---|---|
   | `UNPACKED` | every entry found was ingested |
   | `UNPACKED_PARTIAL` | ≥1 entry ingested **and** ≥1 entry not ingested — quarantined **or** skipped-unreadable |
   | `UNREADABLE` | unpack itself failed (corrupt / unsupported / cap breach), or no entry was readable |
   | `EMPTY` | archive opened, zero entries found |

   ⚠ `EMPTY` and `UNREADABLE` are ONE code path today — both throw `no readable entries` and the
   original flows on to quarantine as `unreadable`. Keeping them distinct (operator's call: an
   operator should be told "your zip is empty" vs "your zip is locked") means the expansion must
   count entries found, not merely fail.

   **(b) On `UNPACKED_PARTIAL` the Consignment COMMITS** — today's shipped behaviour and today's
   per-file semantics (a bad file never blocks its batch-mates) are ratified, not changed. Failing
   whole would discard 499 good ingests for one bad entry in a 500-entry archive, and a re-drive
   would re-ingest everything. The verdict is REPORTING, never a gate.
2. ~~**Lineage grain.**~~ **✅ ANSWERED by the operator 2026-08-26: the ENTRY name, as shipped.**
   A row from `bundle.zip`'s `good.csv` records `good.csv` — finest grain, and the Archive link
   lives in the ledger (and in the manifest's `archive!entry` address). Archive-name was considered
   and refused as a deliberate information loss. No code change: the fix of 2026-08-26 already put
   the entry grain in force, and this ratifies it. ⛔ Do not "simplify" `filename_column` to the
   container name, and do not widen it to the composite `archive!entry` — that would bake a key
   downstream consumers must parse into a data column.
3. ~~**Phase 4 appetite.**~~ **RESOLVED by building it** (2026-08-23): failures are recorded in the
   manifest, crash-safe order untouched, branch-aware path unchanged. What remains of the
   "end status against each source file" ask is the run-level ledger under Q1.
4. ~~**The data-extension allow-list default**~~ **✅ ANSWERED by the operator 2026-08-26.**
   **(a) The seven stand:** `.csv .tsv .txt .json .jsonl .ndjson .xml`. **(b) The key is PUBLISHED** —
   `processing.unpack.data_extensions` (`FieldType.LIST`), so a deployment may narrow it or set it
   empty (`data_extensions[0]:`) to opt out of extension-insensitive identity entirely. **(c) The
   marker-mode alias hit now logs at WARN**, not INFO — in that lane the skip DROPS a file and
   nothing downstream can overrule it, unlike the checksum lane where the hash still decides.

   🔴 **The collision is INHERENT, and this is the thing to understand before touching it.** Rule 2's
   single data-extension strip is exactly what makes `cdr_20260823.csv.gz`, `cdr_20260823.Z` and bare
   `cdr_20260823` one logical file — they meet ONLY at the fully-stripped tier. `report.csv` and
   `report.json` colliding is the *same strip*. ⛔ A "safer" two-tier scheme matching on the
   compression-stripped form would break the operator's own requirement; the escape hatch is the
   list, not a redesign. Both halves are pinned in `LogicalNamesTest`.

   ⚠ Exposure is still scoped: the alias is only ever WRITTEN for compression-involved names, so two
   plain files collide only once a compressed spelling of that logical name has been processed.
