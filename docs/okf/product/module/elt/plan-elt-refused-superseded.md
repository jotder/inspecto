---
type: Capability
title: Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan — Refused and superseded
description: Part 5 of 7 of Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan: Refused & superseded
status: current
tags: [acq, ing, pip, capability, acquisition, connections, collectors, connectors, ledger, ingestion, parsing, consignment, grammar, parser-plugin, quarantine, expectation, pipeline, authoring, step-catalog, execution, scheduler, jobs, plan]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
verified: 2026-11-20 last-edit
code: platform/inspecto-etl
---

# Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan — Refused and superseded

Part 5 of 7 of [Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan](plan-elt.md) · previous: [Not built and open](plan-elt-not-built.md) · next: [Verification and references](plan-elt-verification.md).

## 6. Refused & superseded

**This section exists because a refused idea with no recorded refusal gets re-proposed.** `BACKLOG.md` §6
does this for *work*; this does it for *design*. Each row states what was refused and the reason — the
reason is the load-bearing half. Unpack refusals (extending the ingester SPI to emit files, partial-archive
failure, nested archives, `archive!entry` in a data column, a per-entry Signal, the batch semaphore for Unpack)
are acquisition's ([§ACQ.5](#acq5-acquisition--refused--superseded), chiefly §ACQ.5.8).

### ACQ.5 Acquisition — refused & superseded

#### ACQ.5.1 An in-process NFS / SMB / CIFS client — REFUSED, and it is the security boundary

`ACQ-4` asked for "network-share (NFS/SMB) connectors on the connector SPI". **There is no share
connector and there will not be one.** The path validator **rejects UNC outright**
(`PathJail` refuses a value that "is a UNC/network path", the rule `ConfigSafetyValidator` documents as
"reject UNC/network paths"; `DataRef` bans the `\` separator that `\\host\share` needs). That rejection is not a gap someone forgot to close: it is the boundary that
keeps an authored config from reaching an arbitrary host on the network.

**What serves the need instead:** mount the share at the OS level and point the built-in `local`
connector at it ([spec](spec-acquisition-connectors-control.md#network-shares--the-declined-design-recorded-once)). The editions matrix's
`SP-ACQ-01` (`spec-editions.md`) is titled "**Local / NFS** directory watcher" — `LocalFileSystemConnector`, the
default collector — and reads `✅✅✅`, so NFS **is** served; what is refused is an in-process protocol client.

✅ **The two tiers now agree.** The old root `ROADMAP.md` §3.2 kept the half open, while `REQUIREMENTS.md`
closed it as the mounted-share pattern. Its successor, [`plan-roadmap.md`](../../layer/platform/plan-roadmap.md)
§3.2, records the half as "Not delivered, and not pending — REFUSED by decision" (framing corrected
2026-09-09). **The refusal wins, because the code enforces it.**

#### ACQ.5.2 A standalone `inspecto-connect` repo with a cross-repo `@PublicApi` — REVERSED IN FACT

Decided 2026-06-24 (`plans-archive/brainstorm-tingly-storm.md`): build connections as their own
repository behind a stable cross-repo `@PublicApi`. **Verified against the tree: no such repo or module
exists.** What happened instead is `inspecto-acquire` (the SPI) plus the optional `inspecto-connectors`
(the implementations) — which delivers the same dependency isolation *without* the cross-repo
versioning cost the plan itself had flagged as its main risk.

The half that was **kept** is worth naming, because it is why the reversal was cheap: the UI-first
contract freeze. The connection contract was frozen from the consumer's side first, so moving the
implementation was a packaging decision rather than an interface renegotiation.

⚠ Residue: `@PublicApi(since = "4.0.0")` marks eight `inspecto-acquire` SPI types (`CollectorConnector`,
`CollectorConnectorFactory`, `ExportConnector`, `ExportConnectorFactory`, `DiscoveryContext`, `RemoteFile`,
`PostAction`, `AcquisitionException`). It is **not** a stale marker: trunk is `4.0.0-SNAPSHOT`, and per the
[API stability policy](../api/plan-api-stability.md) `since = "4.0.0"` means "will become public API in 4.0.0"
(the deleted `4.x` branch never shipped), so these types may still change before that release.

#### ACQ.5.3 NiFi's FlowFile session/queue runtime — REFUSED

Adopt NiFi's *authoring contracts*, not its *runtime*. A live inter-node queue would make the pipeline a
stateful cluster; the flow-graph decision is recorded as locked. The consequence to keep in mind when
reading any NiFi-shaped design doc: **"predecessors ran" means materialized output on disk, not a queue
handoff.**

#### ACQ.5.4 Enriching `POST /components/connection/{id}/test` — SUPERSEDED

The controller-service design proposed extending that route. The UI's frozen
`/connections/{id}/probe | explore | sample` triad won, and the design predates the contract. Not a
rejection on merit — a document that aged past its subject.

#### ACQ.5.5 A WRITE probe against a customer system — REFUSED

`WRITE` is deliberately `skipped` for `db`, `s3`, `gcs`, `azure` and `kafka`. A workbench must never
create a scratch object or publish a probe record into someone's production system. Only `local`
performs write-and-delete, where the blast radius is a temp file we own.

#### ACQ.5.6 Edition-gating the connector sidecar — REFUSED

~32 MB, accepted. Remote acquisition is core capability, not a paid tier; gating it would make Basic
a demo rather than a product (2026-09-07).

#### ACQ.5.7 "A connector is not a Step" — the standing answer to an apparent contradiction

`SP-ACQ-06` / `SP-ACQ-08` read 🟡 "no proven end-to-end acquisition-node run" while `ACQ-4` reads
FULLY CLOSED. Both are true and neither is stale: a **Connection kind** existing (tested connector,
real credentials, real listing) is a different claim from an **acquisition node** having been proven end
to end in a pipeline run. It is recorded here so the pair is not "reconciled" by flattening one of
them.

#### ACQ.5.8 Other standing refusals, with their reasons

| Refused | Reason |
|---|---|
| A second Kafka lane for urgent topics | Urgency is a *parameter* on one node, not a second lane |
| The stream consumer as a long-running Job (STREAM-CONSUMER-1, 2026-09-24) | `JobService` models finite, non-overlapping Runs: a permanent Run holds one of the global in-flight slots forever, every later fire records `SKIPPED`, and a Job has no path to the pipeline's inbox, run guard or acquisition ledger, so it would duplicate the scheduler's wiring. A Run that never ends also makes Run history and duration meaningless |
| A Kafka slice named after its reached range, with the frontier derived from the name | The commit is source-agnostic and the ledger key is not in the name; a DB-export watermark (a timestamp with `:`) cannot live in a portable file name. One durable record serves both connectors |
| A watermark for the `dataset` connector | Snapshots are timestamp-named and marker dedup already gives re-ingest-on-refresh; do not build one on the assumption it is missing |
| Nested archives, and partial-archive failure | `depth: 1` is deliberate; a nested archive is an unbounded expansion in a jailed path |
| Extending the ingester SPI to emit files; "updating" a Consignment with expanded Entries | Unpack produces *files*, the wrap-SPI produces *records*; the manifest is immutable and `ManifestStore` has no merge; forcing an Archive's Entries into one Consignment would void the `max_files` / `max_bytes` memory bound |
| A two-tier logical-name scheme to stop the `report.csv` / `report.json` collision | It would break the requirement the mechanism exists for; the allow-list is the remedy |
| Crash-mid-archive exactly-once | Relies on `OVERWRITE_OR_IGNORE`; true exactly-once would need a per-entry ledger, which costs more than the failure |
| A per-entry Signal in Unpack; reusing the batch semaphore for Unpack | Unpack runs before planning, so the Consignment semaphore cannot bound it |
| Fetch-lane fairness | Stays FIFO until a real fetch-lane wait is observed |
| Vault / KMS **now** | Client policy, not code — the SEC-07 seam is already in place for when it lands |
| `PathJail` for the watermark store | A deliberate LEAVE: the store is engine-owned, not author-supplied, so the jail would guard nothing |
| `/metrics/acquisition` as an edition-gated cell | Core and ungated — an operator on any edition must see whether data is arriving |
| Bounds-based window pruning | Measured 1.3–2.6× faster for 29–88× fewer rows — the win did not survive contact with real cardinality |
| Merging the manual-run `runGuard` with the background `acquireGuard` | It would serialise ingest behind fetch again; the overlap it allows is benign |

⚠ **Do not carry forward** `BACKLOG.md` §6's "the TOON `source:` block stays". It is refuted:
`PipelineConfigParser` reads only `collector` (`parseCollector`), and no committed TOON carries a top-level
`source:`. Only `NodeCategory.SOURCE` survives, and that is an enum constant, not authored config.

### 6.1 Ingestion

| Refused | Why |
|---|---|
| **One partition grammar (E2); staged CSV from inside a plugin** (2026-08-19) | Folding either `partitions[]` reader into the other imports a hard failure into the degrade lane or softens the fail-closed one. The Appender is built and faster; never demote to staging CSV in-process |
| **Config that would load looking honoured and then fail** — refused at load | `format: array` + `ignore_errors`; `records_path [*]`; `compression: zip | tar | Z` in config (only `auto | gzip | zstd | none`); a plugin with no `segments`; `TIMESTAMPTZ` without a zone; `%z` formats; a `root_type` without a grammar. Each is a caller error, not a silent fallback |
| **Knobs nothing has needed** | ASN.1 trailer length and `lengthOffset` / `lengthSize` / endianness (no corpus file uses them); `decimal_separator` at the dialect (ingest reads VARCHAR); newline style, `sample_size`, `all_varchar`, `auto_type_candidates`, `names`, `union_by_name`, `buffer_size`, file globs in the grammar (auto-detected, all-VARCHAR by design, or the Collector's); `read_xlsx columns` (does not exist — refused, not faked); a `timezone_column` editor (~418 zone names per row) |
| **Per-step Signals and periodic progress persistence** (consignment chain) | A Signal is a durable write on the claim-holding thread and triggers `on_signal` jobs while the claim is held (the re-entrancy class `module/engine/spec-engine-gotchas.md` forbids); periodic persistence is stale by construction and leaves crash-orphaned rows. Live gauges stay in memory; the ledgers answer the post-crash question |
| **Overriding a built-in parser id** | The built-ins' preview is the ingest engine, so an override lets preview diverge from production |
| **A breaking engine slice removing `use: grammar/` (D3)** | It costs nothing, is pinned, and a hand-authored file may still carry it; the resolution was to stop *authoring* the binding, not to build a write route that removes it |
| **A selector naming a container or a repeated sibling** | It yields NULL — "a lie dressed as a decoded value" |
| **A partial decode on an ASN.1 / XML parse error** | the whole file fails (`QUARANTINED_UNREADABLE`), never a prefix |
| **The purge seam for content refusal (`PURGED_REFUSED`)** (2026-09-30) | data content could trigger it, it applied to every Pipeline, and it deleted irreversibly; replaced by `restricted_quarantine` |
| **A rename-and-redeliver correction of a daily file** | a correction under a NEW name doubles every row of the date — the feed owner's error, not fixed in the platform, detected by the delivery check |

**Superseded designs — what replaced them:**

| Superseded | By | Where recorded |
|---|---|---|
| Whole-file `FileIngester` (since 1.3.0) | `StreamingFileIngester` (3.11.0 — a deliberate within-major exception) | `api-stability.md` |
| `processing.csv_settings` / `processing.ingester`; the standalone `*.grammar.toon` file | the unified `parsing:` block (2026-07-07); Grammars inline on the Step; the `grammar` kind as a template | [ingest spec — parsing](spec-parsing.md) |
| `processing.batch.*` | `collector.consignment.*` (2026-09-02) | `GLOSSARY.md` §13 |
| `Batch` / `BatchProcessor` / `BatchIngestStrategy` / `CsvBatchStrategy` | `Consignment` / `ConsignmentIngestor` / `ConsignmentIngestStrategy` / `CsvIngestStrategy` (2026-08-31) | `GLOSSARY.md` §13 |
| Record dedup in Stage-1 | the at-rest `dedup` Step (2026-08-11) | [architecture](architecture-elt.md) |
| The `year=1900` sentinel bucket | a flat unpartitioned store (E1) | [ingest spec](spec-parsing.md) |
| `use: grammar/<id>` as a live binding (2026-08-04) | Grammar Templates as copies (2026-08-15); the form read-only | `GLOSSARY.md` §5 |
| "P3d retires `GrammarEditorDialog` entirely" | binary fixed-width and the unmappable generic `parser` keep it (operator, 2026-08-16); the dangling case moved to the drawer 2026-09-06 | [grammar configuration](../config/user-guide-grammar-config.md) |
| The 4-tab delimited surface (U1–U5); `<inspecto-property-rows>`; `GrammarTemplateDialog`; the 9-type hardcoded `parser-types.ts`; the mock `/components/grammar/preview`; the Onboarding Parsing stage | the sectioned pane, one schema-form per section, Grammar CSV, the served `ParserPlugin` contract, the editor as the guided surface | [grammar configuration](../config/user-guide-grammar-config.md), [ingest spec — parsing](spec-parsing.md) |
| XML preview-only; `xml.` grammar root | `XmlRecordIngester`; keys under `ingester_config` (2026-08-30) | [ingest spec — parsing](spec-parsing.md) |
| The node-count engagement predicate for the graph lane | branch count (`dataFedSinkCount`) — "refuted by its own falsification test" | [ingest spec](spec-ingest.md) |
| "An absolute refusal of `steps:` was the original design and was WRONG" | `steps[]` runs at rest via a `type: pipeline` job | [architecture](architecture-elt.md) |
| `DATA_RULES` / `TransformCompiler` as the mapping lane | the Record Transformer (2026-09-05) | `configuration.md`; [Pipeline spec — model](spec-pipeline-execution.md) |
| `generation_threshold_bytes`; "nested archives"; LDIF `[PROPOSED]`; "XML today" preview-only; "nine format types" and `Run ⊇ Batch ⊇ File` | corrected with the ING capability spec, 2026-09-08 | those files |
| The 4.1 account of the parsing grammar (`*.grammar.toon`, `csv_settings`) | superseded 2026-07-07; flagged, a rewrite owed | [ingest spec — parsing](spec-parsing.md) |

### 6.2 Authoring

| Item | Verdict | Why |
|---|---|---|
| **Sub-Pipeline / embedded-Job nesting** | ⛔ **REFUSED by design** 2026-08-02 | In-motion and at-rest are a binding line; they compose as producer and consumer over a shared store |
| **The closed type vocabulary** | **SUPERSEDED** 2026-08-09 (D0-B) | Totality, not closure, was the real guarantee — and it survives an open registry — which is now live, so the set is open in fact too |
| **The simple five-verb grid, and its editing lock** | **SUPERSEDED + DELETED** 2026-09-04, the day it was reviewed live | A raw SQL box is not an authoring surface for a non-technical user; a verb enum is not a mapping model either. Replaced by a typed function catalogue |
| **The SQL-only middle state** | ⚠ **NOT CURRENT** — the surface flipped twice on 2026-09-04 | Recorded because a reader landing on the middle state would build the wrong thing |
| **The map type** | ⛔ **DELETED** 2026-09-05 | Replaced by the Record Transformer; map is a slot identified by id grammar, not a type |
| **Three catalog labels plus type coercion** | **FOLDED** into one row 2026-09-04 | Three labels over one grid. The schema *contract* could not fold — the declared column and target type are the cast-failure audit's denominator |
| **Deleting the legacy rule read path** | ⛔ **REFUSED** 2026-09-05 | It stays readable **permanently**; migration is by tool, never by breaking stored schemas |
| **An authored reference on any kind but two** | ⛔ **REFUSED** by name | Every other kind carries its settings inline. The rule is one-way and deliberate: a picker requires a home |
| **A map-list attribute spec for branch config** | ⛔ **REFUTED** | A specced key is form-owned and would destroy the derived pair |
| **The merge and join input picker** | ⛔ **REFUSED on grounding** 2026-09-07 | There are no input keys to write; edges stay authored on the canvas |
| **A SQL parser in the client for reference detection** | ⛔ **REFUSED** | It would be a second implementation of the dialect the engine owns |
| **Folding the transform family into one SQL node** | ⛔ **ASSESSED AND REFUSED** 2026-08-29 | Filter, validate, dedup and route each *split* rather than project; and folding split, merge or summarize into enrichment would move operators after commit and across pipelines — a change of execution moment, not a rename |
| **Deleting the dedup-marker type** | ⛔ **REFUTED** 2026-08-29 | It is already in its correct terminal state: known, lowerable, unauthorable, unspecced. Marking it deprecated would be actively harmful. Method note: read a type's *consumers*, not the comment nearest it |
| **Graph-shaped authoring writes** | **RETIRED** | Grandfathered readable, runnable and deletable; never newly written |
| **A single drawer over schema, mapping and table** | ⛔ **REFUSED** 2026-08-16 | It would span three Step types and break the one-in, one-out contract every drawer holds |
| **A plural collector** | ⛔ **REFUSED PERMANENTLY** 2026-08-11 | Refuted by grounding: every stateful acquisition subsystem is keyed on one durable id |
| **Fan-in beyond the canvas** | ⛔ **DEFERRED** (D6) | Expressibility is not a reason to overturn a standing decision speculatively |
| **A validation-gate plan for schema authoring** | **SUPERSEDED the day it was drafted** 2026-09-03 | Judged too complex; the operator chose the parse-pane redesign and the SQL-first transform instead. Nothing was built from it |
| **Scalar user functions outside the gated letter** | ⛔ **REFUSED** | Measured: the driver exposes no scalar-function API, so "user function" can only mean a per-connection macro |
| **Keying anything on the verb** | ⛔ **BANNED** | One verb appears twice; only the type is unique. And a single polymorphic entry with a discriminator cannot work, because lowering dispatches on type, never on config content |
| **The step-scaffold generator for a new type** | ⛔ **GATED** | A classpath type gets no hot deploy, no isolated loader and no watchdog |
| **The offline mock backend** | ⛔ **DELETED** 2026-08-31 | The editor now needs a real control plane |
| **A per-Pipeline config directory `config/<id>/`** | ⛔ **REFUSED** (D3, reversed 2026-09-23) | it would add a third layout; a bare `schema_file` resolves beside its own config |
| **Renaming `GET …/graph`** | ⛔ **REFUSED** (D9, 2026-09-22) | a link buys the discoverability; a rename breaks every client |
| **A `renamed` drift category** | ⛔ **cannot exist** | from draft + sample, a rename is indistinguishable from remove + add |
| **An AST → SQL write path on the Filter Step** | ⛔ **REFUSED, for good** (Q2) | the round trip drops comments and re-spells everything |

### EXE.6 Pipeline execution — refused & superseded

| Item | Verdict | Why |
|---|---|---|
| **An `ingest` job type** | ⛔ **REFUSED** 2026-06-17 | A Job is strictly downstream over data at rest; ingestion is the Pipeline's sole responsibility |
| **Sub-Pipeline / embedded-Job nesting** | ⛔ **REFUSED by design** 2026-08-02 | In-motion versus at-rest is a binding line; they compose as producer and consumer over a shared store |
| **A global priority-ordered queue** | ⛔ **REFUSED — do not re-propose** | Strict ordering *is* the starvation trap, and it reorders Consignments within a pipeline, breaking arrival order |
| **Nested semaphores per tier** | ⛔ **REFUSED** | No global view for the fairness decision, and cross-tier ordering is a deadlock hazard |
| **A separate consignment-generation service** | ⛔ **REFUSED** | Planning stays in the poll cycle; only *admission to execution* is shared |
| **Retiring the ingest run budget** | ⛔ **REFUSED — the plan claimed it, the code refuted it** | It bounds a different *phase*, not the same fact twice |
| **A cap computed from memory ÷ the live concurrency** | ⛔ **REJECTED** (the GAP-4 default divides by a fixed 4) | The divisor is routinely unknown and the ingest path has its own limiter, so any formula is wrong *in exactly the overcommit case it was meant to prevent* |
| **A temporary-directory-size default** | ⛔ **NONE, permanently** | None is defensible without knowing the volume |
| **Capping preview and dry-run connections** | ⛔ **REFUSED, and must stay so** | They run over bounded samples |
| **Read-path connection reuse** | ⛔ **NOT the lever** | Measured: a warm open is 24 ms; no contradicting measurement exists |
| **Route at rest** | ⛔ **REFUSED at two sites** | Route's home is the ingest lane. ⚠ Both refusals used to be *negatives that never told the author route works at all*; both now name its home, and **no test pinned either string** |
| **Decision rules together with route** | ⛔ **REFUSED at runtime** | A rule that actually routed rows keeps the pipeline flat; the admission cannot see rules statically |
| **Multi-schema together with route** | ✅ **ARMS since 2026-09-24** | Each per-schema write walks its own schema's slice (`PipelineLift.scope`); ONE shared `route:` whose predicates must bind in every schema, else arming refuses — [spec-ingest.md](spec-ingest.md) § *Multi-schema route* |
| **A node between projection and write on either ingest lane** | ⛔ **REFUSED on both** | Carrying it means executing it at rest, which is second-stage work; **batch independence is the dividing rule** |
| **Cross-branch all-or-nothing commit** | ⛔ **OUT OF SCOPE deliberately** | One healthy branch stays committed when a sibling fails, and the failed branch retries independently |
| **A versioned reference store per branch** | ⛔ **REFUSED** | One version history across branches is ill-defined |
| **Per-step signals for live position** | ⛔ **REFUSED** | A signal is a durable ledger write inside the run claim — and because signals trigger jobs, a mid-batch signal invites triggered work to fire *while the claim is held* |
| **Periodic persistence of progress** | ⛔ **REFUSED** | Stale by construction, recurring writes for a value that expires in seconds, crash-orphaned rows to clean — and the post-crash question is already answered by the ledgers |
| **Folding intrinsic and inherited file status into one enum** | ⛔ **REFUSED** | A committed file inside a failed Consignment is real and common; folding loses *which files made it* |
| **A rollup cache** | ⛔ **deliberately ABSENT** | Nothing depends on it, read-time aggregation has not been shown too slow, and building it adds the one mutable-looking thing in the system **ahead of evidence** |
| **An on-commit job trigger** | 🔴 **DOES NOT EXIST** | Commit-fired jobs ride the signal bus. ⛔ Do not cite the graph-structure refusal as what keeps job work at rest |
| **Growing the event enum for new facts** | ⛔ **BANNED — emit a signal** | And **per-file facts belong in a ledger row**: a signal per file floods the bus on a large Consignment |
| **A third-party scheduler library** | ⛔ **REJECTED** | The config file is the durable schedule |
| **Inbox lag or pending depth as throttle inputs** | ⛔ **BANNED** | Positive feedback; they stay observability surfaces |
| **Kafka as a second execution model** | ⛔ **REFUSED, never re-opened** | Urgency is a parameter on one node. ⛔ Do not re-file it; a Kafka *collector* is a different, open question |
| **Timeout or cancellation on either engine** | ⛔ **NONE — a recorded gap** | Cancelling a handle un-arms only future fires |
| **Sealing** | **DROPPED, not deferred** 2026-08-30 | The partition-state machinery bought nothing the completeness measure does not |
| **Per-record lineage** | ⛔ **REFUSED / won't do now** | Per-batch ancestry is the accepted grain. ⚠ See §EXE.5.3 item 15 — one board says both |
| **Distributed-by-default** | ⛔ **REFUSED as a default** | ⚠ But planned as Enterprise scope, unscoped — and no document states the distinction (§EXE.5.3 item 16) |
| **A partial archive failing its Consignment** | ⛔ **REFUSED** | The partial verdict is **reporting, never a gate**; it is a Run-level fact, never a manifest member row |
| **Nested archives** | ⛔ **REFUSED** | Depth is pinned at one |
| **Crash-mid-archive re-ingest** | **BY DESIGN** | Relies on idempotent overwrite; revisit only with a measured cost. ⚠ The bounded retry does not cover it — it records a *returned* failure, and a crash returns nothing |
| **Member-level transient classification** | ⛔ **stays as it is** | The catch sites collapse every read failure into a permanent quarantine across four strategies; nothing classifies transient from fatal (§EXE.3.7) |
| **Multi-user relational deployment** | ⛔ **PARKED** 2026-09-06 | Until a multi-operator install exists |
| **Mail delivery reporting success with no channel** | **DELIBERATE** | ⛔ Do not "fix" without an operator call — but know that a scheduled mail job **reads green while delivering nothing** |
| **A built-in with an unsatisfiable grant** | **ACCEPTED** asymmetry | Only the pack and classpath paths refuse; recorded deliberately |
| **The step-scaffold generator for a new kind** | ✅ **BUILT** for pack-hosted Steps — `node tools/scaffold.mjs new step` (isolated classloader, `StepContext`, watchdog; platform-services S2-3) | The original gate stands for a *classpath* node type: it gets no hot deploy, no isolated loader and no watchdog |
| **Third-party lowerable Step kinds** | ⛔ **CLOSED** | Until a fragment guard exists |
| **Reusing the flow-job sink writer for ingest destinations** | ⛔ **NEVER** | It is shaped for one root, with no lineage, and self-registers |
