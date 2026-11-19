---
type: Capability
title: Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan — Where the mechanism lives, and decisions
description: Part 2 of 7 of Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan: Where the as-built mechanism lives; Decisions
status: current
tags: [acq, ing, pip, capability, acquisition, connections, collectors, connectors, ledger, ingestion, parsing, consignment, grammar, parser-plugin, quarantine, expectation, pipeline, authoring, step-catalog, execution, scheduler, jobs, plan]
timestamp: 2026-11-18T00:00:00Z
---

# Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan — Where the mechanism lives, and decisions

Part 2 of 7 of [Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan](plan-elt.md) · previous: [Purpose, scope and requirements of record](plan-elt-scope-requirements.md) · next: [Decisions — Pipeline execution onward](plan-elt-execution-decisions.md).

## 3. Where the as-built mechanism lives

The specification sections of the two former capability files were trimmed on 2026-11-18. Each shipped fact
now lives once, in its owner:

| Mechanism | Owner |
|---|---|
| Consignment planning, `collector.consignment`, the run budget (`sources.max × processing.threads × duckdb_threads`) | [ingest spec — Consignments and ingest](spec-ingest.md) |
| `ConsignmentIngestor`, the two strategies, union vs generation mode (`large_file_bytes`, `flush_records`), staged write and reveal, the crash-safe commit order, unkeyed → flat store (E1) | [ingest spec](spec-ingest.md) |
| `route:` on the poll path and `-Dingest.lane` | [ingest spec](spec-ingest.md) · [Pipeline spec — execution](spec-pipeline-execution.md) |
| The `parsing:` block, the frontends and their engines, `ParserPlugin` / `Parsers`, ASN.1, the XML bridge, every parsing key and its refusals | [ingest spec — parsing](spec-parsing.md) |
| The wrap-SPI (`StreamingFileIngester`, `RecordSink`, `DuckDbRecordSink`, the Appender), segments, the two `partitions[]` readers | [ingest spec](spec-ingest.md) |
| Unpack — placement, caps, verdict, ledger, identity | [acquisition spec Part 3](spec-acquisition-unpack.md) |
| Casting, cast-failure measurement (`-1` = not measured), quarantine reasons, reject rows, the ledgers, in-memory live progress | [architecture](architecture-elt.md) · [ingest spec](spec-parsing.md) |
| The source time zone (`SourceZones`), the Appender, threads | [engine — DuckDB](../engine/spec-duckdb.md) · [ingest spec — parsing](spec-parsing.md) |
| Per-field date format and epoch timestamps; content refusal (`processing.refusal`); the `postmed_xdr` reference build; the daily-feed template, export contract and delivery checks; the Parse pane and field tiers | [user guide](user-guide-elt.md) |
| The authoring model (derived graph, three flags, five registration points, the Pipeline Document and its workbook), the transform surface, the editor, identity and lifecycle | [user guide](user-guide-elt.md) · [Step catalog](spec-step-catalog.md) |
| The round trip (`toMap` / `lower`), lift and `stageTwo`, the validation ladder, the node-type model | [Pipeline spec — model](spec-pipeline-model.md) |
| Identity, rename, save-as-template, config history | [Pipeline identity](../processor/spec-pipeline-identity.md) |

### EXE.3 Pipeline execution — specification, where it now lives

The as-built specification that stood here (§EXE.3.1–§EXE.3.11) moved on 2026-11-18 (`DOCS-ELT-CONSOLIDATE-1`) into
the spec files. A "§EXE.3.x" reference elsewhere in this file resolves through this table.

| Was | Now |
|---|---|
| §EXE.3.1 Two engines | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Two engines and their triggers* |
| §EXE.3.2 Two trigger models (incl. the `dataset.write`-on-completion ordering and the `on: dataset` self-loop guard) | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Two trigger models*; the entry-node trigger grammar in [architecture-elt.md](architecture-elt.md), the Job trigger fields in [../processor/spec-jobs.md](../processor/spec-jobs.md) |
| §EXE.3.3 Two concurrency mechanisms (the Run bound, the ingest budget, the four-layer broker, stride scheduling) | [spec-consignments.md](spec-consignments.md) |
| §EXE.3.4 The ingest lane fork | [spec-consignments.md](spec-consignments.md) (admission, the `-Dingest.lane` flag, decision rules, scratch dir, the engagement predicate, lane parity); the parity gate's status, the per-segment admission and the reason-for-flat list in [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Lane-fork admission notes* |
| §EXE.3.5 The at-rest runner and the two lifts | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *The at-rest runner, and the two lifts* |
| §EXE.3.6 Run identity, ledgers and the async contract | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Run identity, ledgers and the async contract*; intrinsic vs inherited file status in [architecture-elt.md](architecture-elt.md) |
| §EXE.3.7 Caps and the bounded retry | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Caps and the bounded retry*; the memory limit in [../engine/spec-duckdb.md](../engine/spec-duckdb.md) |
| §EXE.3.8 The post-sync derived lane | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Post-sync step chains*; the Job-side decisions (registry-not-glob reads, declared composability, a summary-write failure fails the Run) in [../processor/spec-jobs.md](../processor/spec-jobs.md) |
| §EXE.3.9 Maintenance and retention | [../processor/spec-jobs.md](../processor/spec-jobs.md) (the maintenance library, contributed tasks, retention, the purge and legal hold); compaction (quiet-window, crash-journal safe, query-transparent; a compacted-away batch refuses reprocess) in [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Post-sync step chains* |
| §EXE.3.10 Failure, park and recovery | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Failure and recovery, per lane* and *Per-Step `enabled` — park and drain* |
| §EXE.3.11 Whole-pipeline dry run (`PIPELINE-DRYRUN-1`) | [spec-pipeline-execution.md](spec-pipeline-execution.md) — *Whole-pipeline dry run* |

### EXE.7 Pipeline execution — as-built pointers, where they now live

The as-built pointer table (concern → symbol → known gap) moved to
[spec-pipeline-execution.md](spec-pipeline-execution.md) — *Code pointers*. ⚠ **Cite the symbol, not the line** —
and in this area, **check the symbol still exists** (§EXE.5.3 item 7).

## 4. Decisions

Dated, one line each, with the reason — because the reason is what stops a decision being re-litigated.
Only decisions that still bind are listed; where one reversed an earlier one, both appear. Unpack decisions
are acquisition's ([§ACQ.3](#acq3-acquisition--decisions), *Poll cycle, consignments, dedup*).

### ACQ.3 Acquisition — decisions

#### Vocabulary

| Date | Decision | Why |
|---|---|---|
| 2026-07-14 | *Source* → **Collector** (and not *Poller*) for the acquisition entity | "Source" collided with the data-origin sense; "Poller" would have excluded push inputs. Rollout is DONE end-to-end — routes `/sources`→`/collectors`, the TOON block `source:`→`collector:`. Residual by design: the lineage attribute key, `Event.source()`, and `NodeCategory.SOURCE`. `GLOSSARY.md` §13 | <!-- vocab-allow: records the Source->Collector decision -->
| 2026-07-14 | *Data Source* → **Stream** + **Reference** | Two different natures of origin were wearing one name. Acquisition config is unaffected: it stays Connection + Collector | <!-- vocab-allow: records the Data Source->Stream/Reference decision -->

#### Architecture & packaging

| Date | Decision | Why |
|---|---|---|
| 2026-08-11 | `PipelineConfig.collector` is **singular, permanently** | Every stateful subsystem — ledger, watermark, dedup, gap detection — is keyed on one collector id. A list would force a ledger re-key, which is a migration, not a feature |
| 2026-09-07 | The connector sidecar is **NOT edition-gated** | Remote acquisition is core, not a paid tier. `inspecto-connectors.jar` rides every bundle; the ~32 MB was accepted deliberately (CONNECTORS-BUNDLE-1) |
| 2026-09-06 | **SEC-07** — `${FILE}` / `${KEYSTORE}` are Standard + Advanced; the SPI core stays in `inspecto-acquire` | Keeps the lean core free of a secrets client while letting Basic refuse the scheme **by name** rather than failing obscurely. Vault deferred to policy, not code |
| 2026-06-24 → reversed | Connections as a standalone `inspecto-connect` repo with a cross-repo `@PublicApi` | See §ACQ.5.2 — reversed in fact, and this plan is where that reversal is recorded |

#### Connectivity, probe & secrets

| Date | Decision | Why |
|---|---|---|
| 2026-07-08 | Every remote connector is **SDK-free** — SigV4 for `s3`, SharedKey for `azure`, and (2026-07-22) a native JSON API + RS256 service-account JWT for `gcs` | A small SBOM is a FedRAMP asset; three cloud SDKs would have dwarfed the whole artifact |
| 2026-07-08 | `kafka` uses `assign()`+`seek()` with **no consumer group** | The consumed frontier rides the acquisition ledger watermark instead, so replay and crash-recovery reuse machinery that already exists and is already tested |
| 2026-09-24 | The stream consumer loop **stays in the Collector scan**; broker consumer groups stay out (STREAM-CONSUMER-1 Q2, Q4) | No latency target was named, so a continuous lane buys nothing yet; one offset store and fewer ACLs outweigh broker-visible lag |
| 2026-09-29 | The continuous `trigger: stream` lane is **built** (option B), reversing the 2026-09-24 deferral; push ingest is a DATA write, not a config write (ASSURE-PUSH-INGEST-1) | D-P7 named the latency target the deferral waited for; a push is data arriving at a Stream, so the write-root and approval gates (config) do not apply — it is gated as an operation, capped, rate-limited and audited |
| 2026-09-24 | A remote slice's frontier must **survive a restart**, for `kafka` AND `db` (STREAM-CONSUMER-1 Q3) | A restart must never re-ingest; built as a durable per-slice record, not a name-derived frontier |
| 2026-08-13 | HTTP `CONNECT` proxy dial-through **shipped, reversing an earlier fail-closed rejection** | The rejection assumed a JDK socket could tunnel transparently. It cannot, so the tunnel had to be explicit — the earlier "no" was based on a wrong premise, not a policy |
| 2026-09-06 | JDBC dial-through is **PostgreSQL only**, fail-closed elsewhere | Each driver's socket-factory hook differs; a generic claim would have been untestable |
| 2026-07-18 | The connection **probe is graded** (`REACHABILITY` → `AUTHENTICATE` → `READ` → `WRITE` → `LIST`) and WRITE is `skipped` for every remote scheme | A workbench must never write a scratch object into a customer system. Only `local` does write-and-delete. See §ACQ.5.5 |
| 2026-09-17 | Connection test and probe need **`canOnboardConnections`**; explore and sample stay ungated reads | Test dials from the server's network position and probe reads through the saved credential |

#### Poll cycle, consignments, dedup

| Date | Decision | Why |
|---|---|---|
| 2026-08-12 | Consignment ordering defaults to **`mtime`** (arrival), not filename | Filename order is a guess about someone else's naming; arrival order is a fact the filesystem already knows |
| 2026-09-02 | The batch knobs live at **`collector.consignment.{max_files,max_bytes,order}`** | The planner runs before any sink exists, so the knobs cannot hang off the sink (CONSIGNMENT-HOME-1) |
| 2026-09-01 | Dedup keys are **SHA-256 hashed** in the ledger | A ledger is an operational artifact that gets exported and read; raw paths leak customer structure |
| 2026-08-05 | `file_dedup` is a **Guarantee** (file grain); `dedup` is a **Step** (record grain) | One word was doing two jobs at two grains — the split is why either can be reasoned about |
| 2026-08-23 (operator, `d6cd55b7`) | **Unpack sits at the Collector, before planning**; the Consignment model is unchanged | An Entry is a File from birth; "update the Consignment" needs a merge the immutable manifest has no seam for |
| 2026-08-26 (operator) | Four Unpack verdicts; **`UNPACKED_PARTIAL` commits**; `EMPTY` ≠ `UNREADABLE` by `entriesFound()` | A bad Entry must not discard 499 good ones; an operator must hear "empty" apart from "locked" |
| 2026-08-26 → ratified 2026-09-06 | Lineage records the **Entry name**, never `archive!entry` nor the index prefix; the verdict reports at **archive level** with **entry-grain** detail, one ledger row per Archive per Run | An operator asks "did that archive land?" and then "which member failed?" — one answer cannot serve both; a data column must not carry a key consumers parse |
| 2026-08-28 | One decompression vocabulary (`Compression.INLINE_SUFFIXES`), **one-way** drift guard | The SPI is deliberately wider; reverse containment would forbid `.Z` / `.tar` |
| — | Unpack `depth` **= 1**; nested archives refused by name. Identity is extension-insensitive and the collision is **not a bug** | Recursion needs a bound nobody asked for; the allow-list is the remedy, a two-tier scheme would break the actual requirement |
| 2026-10-06 (operator) | `gap_detection` gains `file_template` + `seq_scope`; a date-only template means one expected file per day | The completeness KPI and the delivery check need a second token and a per-day expectation the `sequence` grammar cannot express |
| 2026-11-17 | The per-cycle **byte cap also bounds the fetch** when every listed file has a size (MoSCoW C8); the file-count cap stays after landing | Moving the adaptive, on-by-default file cap would change every remote Collector's default |

🔴 **One gap in the decision record, stated rather than papered over: there is no dated decision anywhere
in the current tier for the stability gate** — the mechanism that decides a remote file has stopped
growing. It is the single most consequential piece of acquisition (get it wrong and you ingest a
half-written file), it is fully built and documented as *mechanism* in
[spec Phase B](spec-acquisition-file-collection.md#phase-b--the-readiness--stability-gate), and *why* its thresholds are what they are is
recorded nowhere. ⛔ Do not invent one. It needs an operator to state the intent, and until then the defaults
are as-built facts with no recorded rationale.

### 4.1 The lane and the commit

| Date | Decision | Why |
|---|---|---|
| 2026-06-28 | **Three frontends, one DuckDB backend** | every format converges on mapping + transform + partition + lineage |
| 3.10.0 → 3.11.0 | `StreamingFileIngester` + `RecordSink` are the plugin SPI; whole-file `FileIngester` **removed** | one contract; the framework picks union vs generation by size |
| — | **Markers and fingerprints go last**; a FAILED Consignment leaves no record | retry is implicit and safe |
| 2026-08-11 | `processing.dedup` **leaves Stage-1**; refused at `prepare()`; the Step runs at rest | dedup over a landed store is decidable; mid-stream is not |
| 2026-08-12 (operator) | Consignment `order` defaults **`mtime`**; `name` opt-in; else refused | arrival order is what a feed means; stamps lie only sometimes |
| 2026-08-19 (E1) | An unkeyed segment writes a **flat store**; the `year=1900` sentinel retired | a sentinel partition is a lie about the data |
| 2026-08-19 (E2) | The two `partitions[]` readers **stay separate** — ingest fail-closed, sink degrades | a wrong layout at ingest corrupts the store; a good sink write must not fail over an advisory |
| 2026-08-19 (E3) | The Appender path is **the** wrap; staged CSV is for out-of-process producers only | built, faster, no second disk write |
| 2026-08-26 → 2026-09-06 | `route:` executes on the poll path; `mode: clone` **arms** (reversing the first refusal) | the graph lane admits non-route pipelines; committed branches must be visible |
| 2026-09-01 | Orphan `output_store` audit **default-on** (`-Djobs.orphan.audit=false` to kill) | a landed store nobody reads is a silent loss |
| 2026-09-02 (CONSIGNMENT-HOME-1) | `processing.batch.*` → **`collector.consignment.*`**, dual-read, healed on save | the planner runs in the poll cycle before any sink exists |
| 2026-09-06 | A plugin pipeline can **plan** a Consignment (audit label `schema`) | a `null` label had thrown for every plugin pipeline |
| 2026-09-07 | **Intrinsic and inherited status never collapse** into one enum | a `COMMITTED` file in a `FAILED` Consignment is exactly what reprocessing asks about |
| 2026-09-30 | **Content refusal is opt-in per Pipeline** (`processing.refusal: restricted_quarantine`), moving a refused file whole to a restricted store; it replaced the purge seam (`PURGED_REFUSED`) | data content could trigger the purge seam, it applied to every Pipeline, and it deleted irreversibly |
| 2026-10-04 (operator) | **Restricted-file retention is mandatory**: `processing.refusal_retention_days` defaults to 7, must be 1..30 | PCI: no stored card number |
| 2026-10-06 (operator) | A daily feed's backfill is the same door as a daily load, then ONE full index build; a Job run that finds a build in flight WAITS and re-plans | no per-day index builds to merge; the daily trigger need not be paused |
| 2026-10-14 (owner call) | A corrected same-name day lands only under `collector.duplicate {mode: checksum, on_change: reprocess}`; the default path markers skip it | no new key; the fingerprint ledger already carries the semantics |

### 4.2 Parsing and plugins

| Date | Decision | Why |
|---|---|---|
| 2026-07-07 | One **`parsing:`** block; `csv_settings` / `processing.ingester` are aliases and `parsing:` wins | one home per concept |
| 2026-07-31 | **ASN.1 served**: grammar optional for preview, required for ingest; hex-first; INTEGER 42 is never `"*"` | a structural dump is honest; a faked decode is not |
| 2026-07-31 | `frontend: asn1` **synthesises** `parsing.plugin` and refuses a co-present one | one config key must win |
| 2026-07-31 | JSON `records_path` dotted; `format: array` + `ignore_errors` **refused at load**; `[*]` refused | each would load looking honoured and fail every Consignment, or return zero rows silently |
| 2026-08-01 | The `asn-parser` reactor split resolved; corpus tests **opt-in and data-gated** — *skipped is the expected state* | the corpus is customer data (DATA-GOV-1) |
| 2026-08-16 (operator) | Binary fixed-width **keeps `GrammarEditorDialog`** | its geometry is `ingester_config`; the slice table would govern nothing |
| 2026-08-19 (X1–X3) | XLSX shipped on the `excel` extension with a **three-layer fail-closed load**; `read_xlsx columns` **refused, not faked** | the parameter does not exist on 1.5.2.1 |
| 2026-08-28 | `text_regex.record_split` verified live — **LDIF block records are live** | blank-line splitting is the whole LDIF need |
| 2026-08-30 | `XmlRecordIngester`: **preview and ingest share one walker**; a selector names a leaf that occurs once; XML keys under `ingester_config` | a second walker would resolve authored selectors to NULL silently |
| — | **A built-in parser id cannot be overridden**; a duplicate id fails startup | the built-ins' preview *is* the ingest engine |
| 2026-09-04/06 | `strict_mode` is a **three-way select** (engine default on / on / off), not `default: true` | a placeholder writes nothing; a default must equal the engine's |
| 2026-09-25 | A field's own `raw.fields[].format` **replaces**, never joins, the Pipeline's date formats for that field | a shared list trying other patterns first is how `%d/%m/%Y` silently reads as `%m/%d/%Y` |
| 2026-11-15 (`MAPPING-EPOCH-TIMESTAMP-1`) | `epoch` / `epoch_ms` are reserved `format` values on a `TIMESTAMP` field | an epoch count lands as UTC with no `sql` step |
| 2026-11-17 (DT-12) | The ASN.1 frontend (`inspecto-telecom-asn1`) is **Advanced only** | edition placement of the telecom decoder |

### 4.3 Authoring

| Date | Decision | Who / why |
|---|---|---|
| 2026-08-02 | **Sub-Pipeline nesting ruled out by design.** In-motion and at-rest are a binding line, so an at-rest operator cannot be an in-motion node; they compose as producer and consumer over a shared store | product |
| 2026-08-04 (user) | Store "both": inline by default, extractable to a `grammar` component; *Grammar* canonical, "parser config" banned | one word, one store |
| 2026-08-09 | **The closed vocabulary is superseded (D0-B).** The real guarantee is compiler *totality*, which survives an open Step-kind registry where every registered kind declares that it lowers or executes; a kind with neither is refused fail-closed at arming | engineering |
| 2026-08-11 | **A single collector, permanently** — the list design was refuted by grounding | engineering |
| 2026-08-11 | **Multiplicity resolved by an ordered chain**, and the "too many of a kind" refusals deleted with it — a count is not what should constrain a pipeline | engineering |
| 2026-08-13 | **Pipeline-level settings are their own route**, not the graph save, because the editable model deliberately never models non-node keys | engineering |
| 2026-08-14 | **An authored reference has a home for two node kinds only**; on any other kind it is refused by name. The bug this fixed: the picker was keyed on category rather than type, so lowering dropped every other reference **in silence** while the save returned success | engineering |
| 2026-08-15 | **A load-time synthesis makes a binding derived**, and derived bindings are dropped silently — refusing them would make every binary-decoder pipeline unsaveable. The general rule: whenever a load-time synthesis invents something the lift can present, check what the save path will then think the author wrote | engineering |
| 2026-08-15 (operator) | ⛔ **A Grammar Template is a copy source, never a binding** — reverses the `use: grammar/<id>` contract of 2026-08-04; the bound form stays readable but is never authored, and opening such a Step migrates it to an inline copy on save | every other *Template* in the glossary is a copy source; a live binding made an edit reach Steps nobody opened |
| 2026-08-16 (operator) | ⛔ **No single drawer over schema, mapping and table** — it would span three Step types and break the one-node-in, one-node-out contract every drawer holds | operator |
| 2026-08-17 | **A new pipeline carries a stable id from birth**, stamped by one scaffold behind both create surfaces so neither can drift; the id is immutable once set | engineering |
| 2026-08-17 | ⛔ **The id is not opaque or minted** — it names the file, the commit log, the ledger and the catalogue entry | engineering |
| 2026-08-17 | ⛔ **The parser's fallback derivation is deliberately not narrowed** — narrowing silently re-keys every id-less pipeline on disk. Migrating the legacy path is a data migration, not an edit | engineering |
| 2026-08-17 (**A6**) | **Export lives in one always-visible home, un-gated** — a business-persona operator handing a config to support is the point | operator |
| 2026-08-19 | **Grammar CSV** round-trip replaces "Save as template…"; `GrammarTemplateDialog` deleted | a file is the portable form |
| 2026-08-29 | ⛔ **`authorable` and `lowerable` stay two flags** — collapsing them forces a choice between offering a node nothing should create and refusing to save a graph that legitimately still carries one | engineering |
| 2026-08-29 | **Providers layer last so an edition may override a built-in** without forking core | engineering |
| 2026-08-31 (**D2**) | **A Step passes a token, not data** — adopt the vocabulary now, the runtime model later. *"This is not a new direction. It is what the engine already does, and what the type system contradicts."* Overturned only by evidence some Step needs rows in flight — none found | operator |
| 2026-08-31 (**D1**) | **Finish the approved amendment, then extend — ⛔ do NOT replace it.** The meta-decision that legitimised every amendment phase. Overturned only by *a requirement the `trigger` / `steps` / `guarantees` shape provably cannot express* | operator |
| 2026-08-31 | **The `data` edge is a fiction** — it expresses a reference, not a flow | engineering |
| 2026-08-31 (**D3**) | **A new pipeline asks for the parser format** — a parser is always format-specific, and defaulting guesses for the author | engineering |
| 2026-08-31 (**D6**) | **Fan-in stays canvas-only for now** — a token model makes fan-in expressible, and that is not a reason to overturn a standing decision speculatively | engineering |
| 2026-08-31 (**D7**) | **One surface serves both the chain and the post-sync lane** — building two leaves the second one second-class. The visual design is open; the constraint is what is recorded | engineering |
| 2026-08-31 (**D9**) | **"Related to a pipeline"** means what it owns, what names it, and what an import needs — ⛔ connections excluded, because a bundle that moved them would move a deployment's identity between spaces | operator |
| 2026-08-31 (**D10**) | **Contract test first, single declaration later** — declaring keys one at a time never stops the drift growing. *"It fails today; that is the point."* | engineering |
| 2026-09-01 | **Layer split**: the editor page owns the editor, the graph bundle owns the backend model, and the active plan stays a plan | operator |
| 2026-09-01 | **`description` is declared, parsed, projected and display-only**; blank clears the key rather than persisting an empty string | engineering |
| 2026-09-02 | **The palette renders the served processor taxonomy** — the product board rendered in-product, with every processor visible and non-addable ones inactive with a reason. The engine's executable vocabulary stays separate | operator |
| 2026-09-02 | **Mid-branch sub-chains ship**; a join, a windowed dedup and a nested route refuse by name at save | engineering |
| 2026-09-03 → 2026-09-04 | 🔴 **The simple five-verb grid and its "a hand edit locks the Step" rule were superseded and deleted the day the operator reviewed them live** | operator |
| 2026-09-04 | **Transform goes SQL-first** — one author projection over the typed upstream relation, never a split | operator |
| 2026-09-04 (same day, second flip) | 🔴 **The grid returns, with a typed function catalogue replacing the verb enum** — *"mapping with functions and parameters is not realistic"*. ⚠ **The SQL-only middle state is not current** | operator |
| 2026-09-04 (operator, D1–D4) | The **sectioned Parse pane**; plain-language labels; mockup rows with no engine key **not built**; grounded defaults vs help-only | a UI knob must name an engine key |
| 2026-09-04 | **The Record Transformer** — one Step that sanitizes, casts, renames and computes, one row per output field over a typed catalogue, generating the projection it saves. Folded from three catalog labels plus type coercion; the schema *contract* could not fold, because the declared column and target type are the cast-failure audit's denominator | engineering |
| 2026-09-04, verified 2026-09-06 (**D8**) | **Parse does not drop columns — Transform owns exclusion.** Excluding in Parse edits the schema, which the compatibility gate can refuse to undo; excluding in Transform is a clause, reversible for free | engineering |
| 2026-09-04 (**D9**, **D10**) | Both field tables are built for a wide feed; Transform opens on *Changed*, not *All* | real feeds carry hundreds of fields |
| 2026-09-05 | 🔴 **The map type is deleted; map is a slot, not a step** — the id grammar is the discriminator, never the type. A stored legacy rule block still loads, permanently | engineering |
| 2026-09-05 | **Fields and SQL are peer views of one Step**, and the earlier "never parse SQL back into rows" rule (D4) is superseded by a bounded reconciler | engineering |
| 2026-09-05 | **`fields` must be lowerable because it is executable** — a key that becomes executable without joining the allow-list is silently dropped on save | engineering |
| 2026-09-06 (PARSE-HOME-1) | **A Parse Step is three things and the drawer is its one home** — a grammar, the schemas it emits, and its other properties, including a dangling binding | three surfaces for one Step confused every author |
| 2026-09-06 | **Paths derive from the slug id too** — one identity for id, file and directories | engineering |
| 2026-09-06 | **Enrichment stays first-class; the retirement narrowed to nothing** after grounding found the one committed fixture does not migrate. ⛔ Not a pending rename — do not re-file it as one | operator |
| 2026-09-07 | **The workbench field list is capped at fifty rendered rows** with the filter always visible — 🔴 the cap applies to what is *rendered*, never to what is *searched* | operator |
| 2026-09-07 | ⛔ **The merge and join input picker is refused** — there are no input keys to write; a picker would have written a key nothing reads. Edges stay authored on the canvas | engineering |
| 2026-09-07 | ⛔ **Reference detection may not use a SQL parser** — that would be a second implementation of the dialect the engine owns. 🔴 And it cannot use a word boundary, because an underscore is a word character | engineering |
| 2026-09-22, 🔴 **REVERSED 2026-09-23** | ⛔ **A created Pipeline stays FLAT at the space config root; `config/<id>/` is REFUSED (D3).** It was signed on the premise that *every shipped Pipeline lives in a per-Pipeline directory* — counted on disk, that is false (`config/orders/` holds THREE Pipelines; directory names are DOMAIN words: `config/postmed/` → `postmed_xdr`, `config/mule/` → `mule_transfers`). `subdir:` gives an author a domain home. 🔴 **The lesson is the premise: "every shipped X does Y" is a claim to COUNT before a decision rests on it.** | operator (reversed on the operator's behalf) |
| 2026-09-22 (**D9**) | **`GET …/graph` is NOT renamed; it says what it is instead.** It gains `links.roundTrip: …/graph/raw` plus `readOnlyProjection: true` (`WB-18`). 🔴 PUTting a `/graph` body back is refused 422 on 26 of 26 shipped Pipelines; a rename would have broken every client for the same gain. Row `GRAPH-READ-SHAPE-NOT-WRITE-SHAPE-1` | operator |
| 2026-09-22 (**D6**) | Node test state may **not** read operate-lane provenance into `tested`; a distinct *ran* badge is the remedy (`WB-11`) | a signal wrong for every Pipeline in production is one readers learn to ignore |
| 2026-09-22 (**D8**) | The orphan-drop decline is **amended**: with a node selected, an added Step is wired after it (`WB-14`) | the Recipe view's insert-between it rested on was deleted |
| 2026-09-23 (operator, `PIPELINE-LOAD-FAILURE-INVISIBLE-1`) | **A Pipeline that does not load is a BROKEN ENTRY in the Open dialog, never a missing one** | a broken file must be openable to be repaired |
| 2026-09-24 | Retention of the server-side config history is **a per-Space setting**, default 50 | the same cap as undo/redo, changeable by the operator |
| 2026-10-11 (operator, option B) | A Step column's sensitive classification is merged into masking **additively only**; it never clears `UNKNOWN_LINEAGE` | an omission must never unmask a column |
