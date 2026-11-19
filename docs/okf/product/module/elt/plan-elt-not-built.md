---
type: Capability
title: Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan — Not built and open
description: Part 4 of 7 of Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan: Not built and open
status: current
tags: [acq, ing, pip, capability, acquisition, connections, collectors, connectors, ledger, ingestion, parsing, consignment, grammar, parser-plugin, quarantine, expectation, pipeline, authoring, step-catalog, execution, scheduler, jobs, plan]
timestamp: 2026-11-18T00:00:00Z
---

# Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan — Not built and open

Part 4 of 7 of [Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan](plan-elt.md) · previous: [Decisions — Pipeline execution onward](plan-elt-execution-decisions.md) · next: [Refused and superseded](plan-elt-refused-superseded.md).

## 5. Not built and open

⛔ **Pointers, never copies.** Each row names its board id; the board is the authority for status and
priority. A row with no id is flagged `UNTRACKED` and needs filing before it can be scheduled. The 2026-09-09 (Sprint 2) filing matched findings to
cross-cutting board rows by family (`CONSUMER-PAIRS-1`, `SPEC-STALEREF-1`, `SPEC-COUNTS-1`, `SPEC-NOPROOF-1`,
`SPEC-GLOSSARY-1`, `SPEC-GREENCELL-1`, `SPEC-PLANSTALE-1`, `SPEC-ORPHANPAGE-1`); ⚠ none of these ids is an open
board row today (re-checked 2026-11-15, `DOCS-AGENT-WRITTEN-REVIEW-1`) — history in
`docs/archived-documents/backlog-snapshot-2026-09-24.md` and
[`post-consolidation-sprints.md`](../../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.
Area-specific design findings stay here deliberately: the board holds what **crosses** areas, a plan holds
what belongs to **one**.

### ACQ.4 Acquisition — not built

#### Partial — the capability exists, the acquisition-node path is unproven

| Item | Board id | What remains |
|---|---|---|
| S3 / GCS object ingest | `SP-ACQ-06`, `SP-ACQ-08` | The connectors ship and are tested; there is **no proven end-to-end acquisition-node run**. ⚠ This is not a contradiction of `ACQ-4` — *a connector is not a Step* (§ACQ.5.7) |
| Azure ADLS Gen2 | `SP-ACQ-07` | Blob works; the Gen2 hierarchical namespace is unproven |
| Kafka consumer as a Collector | `SP-ACQ-09` | The per-scan drain ships, hardened to slice-level at-least-once. What keeps the cell 🟡: it is proven only against `MockConsumer` and a fake offset-tail connector; the real-broker test (`KafkaRealBrokerTest`, 2026-09-29) exists but SKIPS where no broker runs, which is every build so far. Consumer-group semantics stay out by design |
| Excel workbook fan-out | `SP-ACQ-05` | One sheet per file today; multi-sheet fan-out unbuilt |
| The stream lane's Incident hop | — (ASSURE-PUSH-INGEST-1) | Event → committed slice is measured (p95 1030 ms); alert evaluation after the commit has no harness, so D-P7 (p95 ≤ 30 s event → Incident) is not proven end to end |

#### Planned — declared, unstarted

`SP-ACQ-10` … `SP-ACQ-20` — eleven 🔲 rows: Pulsar, Kinesis, SQS, RabbitMQ, MQTT, Debezium CDC, Delta,
Iceberg, Snowflake/BigQuery readers, a REST poller, a webhook receiver and gRPC. ⚠ They are *declared*
so they are not re-proposed as new ideas; none is scheduled.

**Still future (declared, so it is not re-proposed as new):** a presigned-URL / STS credential mode for `s3`;
a Vault/KMS `SecretResolver` scope. The SPI makes each non-disruptive. (The remote-fetch bandwidth cap that
used to sit here was built 2026-11-17 — see below.)

#### Open elsewhere on the board

| Item | Board id | What remains |
|---|---|---|
| Vault / KMS secret provider | `BACKLOG.md` §6 "Decided 2026-09-06" note (`GAP-6` in older docs; no open row) | Deferred by the SEC-07 decision, not blocked: only when a client policy requires it |
| ~~"Listed-not-yet-fetched" gauge~~ | branch-aware residual **(e)** | **DROPPED (operator, 2026-10-06)** — recorded on the *Branch-aware executor residuals* row |
| ~~Remote-fetch bandwidth cap~~ | branch-aware residual **(f)** | **BUILT 2026-11-17 (MoSCoW C8)**: `-Dingest.maxBytesPerCycle` is applied before the fetch when every listed file carries `RemoteFile.size` ([spec](spec-acquisition-file-collection.md#the-poll-cycle-and-its-two-timers)); an unsized listing keeps the cap after landing. The file-count cap stays after landing |
| Multi-part archives; xz / zstd codecs; `.Z` round-trip test; a stale `META-INF/services` header | Unpack (11), `BACKLOG.md` §3 *Onboarding, Catalog, Parsing* | `depth: 1` is deliberate (§ACQ.5.8); *multi-part* (`.z01`, split RAR) is simply unbuilt; xz/zstd is a dependency sign-off, not a build |
| Unpack (5)(8)(10) — partial never fails; nested refused; crash mid-archive re-ingests committed Entries by design | `BACKLOG.md` §6 | LEAVE |
| ~~Missing connection not caught until first poll~~ | — (closed) | **Now caught at save**: a remote Collector whose `collector.connection` names no profile in the Space is an ERROR finding at `/config/write` (`ConfigRoutes.unknownConnectionFindings`); a Space import with a missing connection is a WARNING that writes the dependents switched off (2026-09-25) |
| Acquisition ledger defaults to `memory` | — (the *Completeness KPI hold* that carried it was lifted 2026-10-06) | 🔴 The default loses the ledger on restart, and with it the committed slice frontier. The completeness KPI no longer depends on it: `kpi.completeness` reads the consignment-output store and refuses unless that store is durable (`KpiCompletenessJob`) |

#### 🔴 UNTRACKED — no board row exists; file before scheduling

> Filed as a family 2026-09-09 (Sprint 2) with no cross-cutting rows: every item here is area-specific, and
> a design finding recorded in its owning plan is already filed — copying it onto the board would give it two
> homes. See [`post-consolidation-sprints.md`](../../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.

| Item | Where its design lives | Why it matters |
|---|---|---|
| Credentials & network profiles as **their own referenced resources** | `plans-archive/acquire-controller-service-design.md` §2.1 | The one untracked item with real design content and no home. Today a `tunnel` is **inline** on the Connection, so two Collectors through the same bastion duplicate it — and a rotation edits N places |
| Controller-service **VALID/INVALID lifecycle + run-gating** | same, §3 | Verified absent: validation gates *save* and *compile*, never *run*. A Collector whose Connection has gone bad still starts a run and fails inside it |
| `acquisition.list` / `acquisition.fetch` node split | same, §4 | Verified: `BuiltinNodeType.ACQUISITION` is the only acquisition node. ⚠ The SPI seam for the split already exists, so this is smaller than it reads |
| A "referencing components" read view | same | "What breaks if I change this Connection?" has no answer today |
| Presigned-URL / STS credentials for `s3` | `AwsSigV4`'s class javadoc states the scope reason in-code | Long-lived keys are the only option today |

### 5.1 Ingestion — tracked

| Item | Board id | What remains |
|---|---|---|
| A drop-in `plugins/` jar directory (the ASN.1 grammar as a stored `.asn` file SHIPPED 2026-09-23 — [ingest spec — parsing](spec-parsing.md)) | `BACKLOG.md` §3 *Parsing (Stage-1)* | a trust decision |
| **D13 parser field tiers** — the onboarding-observation session; `tier: 'required'` ships `required: false`. The pre-agreed analysis rule and the deliverable that closes it are in [grammar configuration](../config/user-guide-grammar-config.md); the kit is the parked appendix [§ Parked: parser field tiers interview kit](plan-elt-parser-field-tiers.md#parked-parser-field-tiers-interview-kit-d13) | `BACKLOG.md` §2 | externally gated |
| **Row 15** — delete the legacy flat read path (`-Dingest.lane` is the precondition) | `BACKLOG.md` §2 | release-gated |
| D-8 **XLSX export** (zero groundwork, no spreadsheet library) | `BACKLOG.md` §3 | |
| Column metadata editing on the Transform pane (Parse D2) | `BACKLOG.md` §3 (e) | built for chain Steps 2026-10-11; option (C) — the Step's declaration REPLACING the fail-closed rule — not built (`AUTHORING-REDESIGN-1` (e)) |
| `SP-PRS-09…17` planned parsers (key-value, YAML, ASN.1 PER/XER, EBCDIC, protobuf, Avro, PCAP, Grok, syslog) | `EDITIONS.md` board | no BACKLOG row |
| Delimited robustness knobs (a)(c) | archived board snapshot 2026-09-06 only | no live row |
| Re-delivery semantics of a daily feed (replace vs append of the partition, late-data effect on the Link Analysis Index) | `LA-DAILY-INGEST-1` T4 | until T4 lands, treat a re-delivered day as unverified; a date DROPPED by a correction still leaves the old file for that date (roadmap T4 b2) |
| Content refusal: the Collector's discovery logs and its acquisition ledger see the inbox name before the check runs | — | not covered by the refusal |
| Unpack items (xz/zstd, `.Z` round-trip, multi-part archives, `data_extensions[0]:`, stale services header) | [§ACQ.4](#acq4-acquisition--not-built) | |

### 5.2 Ingestion — `UNTRACKED`

| Item | Evidence | Why it matters |
|---|---|---|
| **`parquet` is a built-in frontend on no board** | `BuiltinParsers.IDS`; `FRONTENDS` | shipped, untested-as-a-row, invisible to `EDITIONS.md` and `step-catalog.md` |
| **The frontend count is stated six ways** (3 / 5 / 6 / 7 / 9 / 10) across five pages and the user guide | §2.1 | FOUR sets share one noun: **10**<!--count:parsing-frontend-tokens--> `parsing.frontend` tokens (eight formats once the two aliases fold), **6**<!--count:builtin-parsers--> DuckDB-native built-ins, **7**<!--count:parser-node-types--> `parser.*` node types, and three byte→row *mechanisms* (a prose taxonomy, not a count in code). The three derivable sets are marked and derived by `tools/check-doc-counts.mjs` (2026-09-09); the mechanism sense is written as *mechanisms*, never *frontends* |
| **`Segment` has no glossary entry** | `GLOSSARY.md` | the load-bearing plugin-ingest word is undefined in the binding vocabulary |
| **The "Batch" names survive in `duckdb.md`, `FEATURE_INVENTORY.md`** | `ConsignmentIngestStrategy`, `CsvIngestStrategy` | corrected in the seams page; the rest are a sweep |
| **No page owns Expectations as a subject** (`com.gamma.expectation`); the accounts are §2.1 here, `REQUIREMENTS.md`'s cell and the decision-rules condition-tree paragraph | *(gap)* | `inspecto/src/main/java/com/gamma/expectation` has no concept page |
| **The flat lane's deletion (Row 15) has no test because it is not done** | engine | |

### 5.3 Authoring — tracked and actionable

* **`AUTHORING-REDESIGN-1`** — the open letters. Macros as a function registry, demand-gated; column
  metadata option (C) (above); and a per-row "resolves to" line, for which no host resolves a sample against an
  attribute spec. *(The structured table over the SQL for filter editing shipped 2026-09-23 as (c); column
  metadata on chain Steps shipped 2026-10-11 as (e); "Sample resolves to" shipped on the Parse pane as (i).)*
* **The processor catalogue's partial rows** — each is a product decision, picked one at a time by name.
* **A host pane for the report builder** — no viable existing pane; a new surface, not an adoption.
* **AI drafting has no applicable component kind** — three kinds have no backend spec, and no low-risk
  slice survives; design first.
* **Two author-facing messages name things that do not exist** — one warns that only three Step kinds may
  run inside a branch when the code's own set also contains the transform-SQL kind.
* **Migrate Duplicate and the row export onto the bundle routes** — both still ride the client stream-bundle.
* **A Parse Apply over a stale `partitions[]` seed can clobber a Sink edit made meanwhile** (BACKLOG).
* **Single-slot inversion** — `processing.join`, `recordDedup`, `routeNode`, `summarizeNode` are last-one-wins,
  silently; inverting it is an operator call (BACKLOG §4).
* **An inline-config Step of other kinds cannot be tested by any existing backend surface** — the component
  `/test` routes resolve through `ComponentStore` and 404 on absence; do not "finish" this without a deliberate
  decision.
* **Comparing two runs side by side** — the run overlay is pick, not compare; a per-edge delta view is not built.

### 5.4 Authoring — `UNTRACKED`, ranked

1. 🔴 **An unknown node type is only a warning** (§2.2). The validator warns, the codec stores config as
   an unchecked map, and the compiler silently drops the node — so an invented vocabulary survives
   unnoticed. It should either be an error or be stated in the requirement.
2. 🔴 **`fields[]` is documented as inert in the config-key reference and as executable in the editor
   page** (§2.4). Code says executable; correcting the reference is the actual fix for the drift row.
3. 🔴 **Counts stated several ways** (§2.3) — node types, recipe entries, processors, transform functions.
4. 🔴 **Two current documents cited refusals that do not exist** — a second join and a second sink.
5. 🔴 **Eight current documents still name the deleted map type or the superseded simple grid as
   current**, including one index line stale in three ways at once. (Not re-checked after the 2026-11-18
   consolidation, which merged several of them.)
6. ⚠ **Whether a degraded palette should also fire the alert component** — the three host error arms are
   tested (2026-09-09, `pipeline-editor.component.spec.ts`; ⚠ `null`-vs-`[]` is the load-bearing distinction),
   but by this project's convention a degraded palette should alert, and nothing fires it today. A product
   decision, deliberately not encoded in a test.
7. ⚠ **`A6` was an orphan decision** — it appeared once in the current document set; §4.3 is now its second home.
8. ⚠ **Every executor line-number citation in the authoring documents is wrong, and so is its package
   path** — the class moved into an execution sub-package. **Cite the symbol, not the line.**
9. ⚠ **A deleted component is cited as the current mapping UI** in a concept page.
10. ⚠ **An identity page says "nothing open" four lines above citing a board row that does not exist** — the
    citation that caused a phantom row to be filed and then deleted unbuilt.
10a. ⚠ **A plan states a sibling document's size as 144 KB; it is 18 KB** — the figure predates a split the
    same list describes.
11. ⚠ **Two notes point at the wrong backlog section** for the authoring row.
12. ⚠ **One editor pane is undocumented anywhere in the frontend tier** — a guarantees checklist panel whose
    own docblock cites a plan that is not among its page's cross-links.
13. ⚠ **The requirement's own cell reads `SHIPPED` and "is not shipped" in one sentence** (§2.2).
14. ⚠ **`transform.merge` is executable code with no authoring route.** Correctly refused rather than
    half-exposed, but nothing on any board says the executor carries a node nobody can reach.

### 5.5 Execution's, not authoring's

`PIP-2` through `PIP-7` — the medallion lanes, incremental triggers, the scheduler and Jobs, async run
triggers, job templates and the maintenance library — belong to Pipeline execution ([§EXE.1](plan-elt-scope-requirements.md#exe1-pipeline-execution-pip-2--pip-7); what is not
built there is [§EXE.5](#exe5-pipeline-execution--not-built)), as do the **second execution home of an authored chain** and the requirement row
whose status hierarchy still uses a word the glossary retired in 2026-08.

### EXE.5 Pipeline execution — not built

#### EXE.5.1 Tracked, externally gated

* **Row 15 — the deletion half of the legacy flat read path.** Gate: a minor release on the trunk after the
  newest ancestor tag, shipping the converter and the lane flag. ⛔ Not closable by code, and ⛔ not to be
  started on momentum. 🔴 The earlier gate was **unfalsifiable by construction** — it named a tag on a
  retired line that can never be a trunk ancestor. Of its four steps, one is shipped, the parity gate through
  the compiled path is **met** (2026-09-16, §EXE.3), one shipped 2026-09-02, and the deletion awaits a release.
  ⚠ Its stated parity blocker is the phantom-class row in §EXE.2.3.
* **Cross-lane drill-down and the step-info envelope**, and **the batch-id rename trio** — both ride the
  next major tag. ⚠ The rename trio is **not enumerated in the drafted release notes**.
* **Distributed scheduler coordination** — ⚠ **no backlog row of its own**; it exists only as an Enterprise
  board cell and an unscoped gate.

#### EXE.5.2 Tracked and actionable

* ✅ ~~**Record-level replay from quarantine**~~ **SHIPPED 2026-09-28 (X4):** `POST /runs/{name}/replay-rejects`
  and `processing.reject_mode: eject | all_or_nothing` (§EXE.3).
* ✅ ~~**The retry deferrals**~~ **SHIPPED:** the per-pipeline `processing.retry {max_attempts, initial_backoff,
  max_backoff}` block (2026-09-25, `PipelineConfigParser`, absent keys inherit `-Dingest.retry.*`) and
  `POST /runs/{name}/retries/retry-now|cancel {file}` (`RunRoutes`; retry-now clears the backoff, keeps the
  attempt count).
* A pre-materialise cap to save fetch bandwidth —
  ⚠ the latter is **design-first** (checked 2026-09-16): no unit, config key or enforcement point exists
  anywhere. `IntakeGovernor` caps FILES per cycle after listing; a pre-materialise cap would have to cap BYTES
  or files *before* the remote fetch, which needs the connector to expose size before download and a
  decision on what happens to the remainder (deferred to the next cycle, or refused). Owed as a §EXE.1 input.
* **Branch-aware residuals** — ~~multi-schema with route~~ (segment-scoped lift shipped 2026-09-24); mid-branch
  transforms in the route verb; three node kinds still unimplemented anywhere; the destination-list
  follow-ups.
* ✅ ~~**Platform services stage two and three**~~ **SHIPPED 2026-09-28 → 2026-10-04** (S2-1…S2-3: node types
  declare an execution mode, the pack Step seam `StepExecutor` / `StepContext` with grants and watchdog; S3-1…S3-3:
  packs contribute Platform Services, provider pinning, disable/enable, read-only `DatasetAccess`); the
  job-side watchdog is `JobDeadline` (2026-10-06, §EXE.3). Design archived 2026-10-04.
* **Consignment addressing residuals** — a dead field that is always zero and never read; a task that must
  be configured or every full recompute leaves a complete extra copy on disk.
* **The consignment-ELT residual** — the batch table is **structurally singular**, one schema per row while
  a Consignment emits a row set per schema. An open decision, not a bug.
* **The completeness measure** — wiring, and a job type that must **refuse loudly** when the registry
  backend is off. ⚠ Two of its components have **no production caller today**. 🔴 Its hold is neither
  carrier- nor query-gated: its first action is *engineering*.
* **Surge admission** — open. *(The memory-cap default that shared this row shipped 2026-09-26, GAP-4, §EXE.2.4.)*

#### EXE.5.3 `UNTRACKED` — found writing this spec, no board row exists

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `SPEC-STALEREF-1`, `SPEC-GREENCELL-1`, `SPEC-GLOSSARY-1`, `SPEC-PLANSTALE-1`.
> ⚠ **None of these ids is an open board row today** (re-checked 2026-11-15, `DOCS-AGENT-WRITTEN-REVIEW-1`): `SPEC-STALEREF-1` closed 2026-09-09 when `tools/check-doc-citations.mjs` became a guard, `SPEC-NOPROOF-1` closed 2026-09-09 when all six Musts had tests, and the rest were closed, refuted or split into their own rows by the 2026-09-24 board consolidation. Their history: `docs/archived-documents/backlog-snapshot-2026-09-24.md` and `docs/archived-documents/plans-archive/post-consolidation-sprints.md`.
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`archived-documents/plans-archive/post-consolidation-sprints.md`](../../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.

Ranked. The first three are visible to an operator.

1. 🔴 **A release gate rests on a class that does not exist, with a claim that is false of the class that
   does** (§EXE.2.3). Fix the row's name and its claim before anyone reasons about Row 15's readiness again.
2. ✅ ~~**The served maintenance descriptor advertises four tasks Personal refuses and hides seven
   shipped ones**~~ **FIXED 2026-09-09.** `MaintenanceJob.BUILT_IN_TASKS` is now the one declaration of
   the switch's ids (20 then, 23 on 2026-11-15), `availableTasks()` adds whatever a provider on **this** classpath contributed,
   and the descriptor is built from that — so the form offers exactly what will not throw, per edition.
   Pinned by `MaintenanceTaskContractTest`, which **re-parses the switch's own `case` labels**; proven by
   mutation (removing one id failed three of its five tests, naming the id).
3. 🔴 **Replay is backed by an in-memory capped map, lost on restart**, while a board row marks it ✅ in all
   three editions and another spec misattributes its 404 to the wrong flag (§EXE.2.3). Either persist the
   lookup or caveat the row.
4. ✅ ~~**The 2 GB memory default is asserted in six more current locations**~~ — moot since 2026-09-26: a code
   default exists (GAP-4, `DuckDbUtil.defaultMemoryLimit`, §EXE.2.4), so the documents now owe *that* number,
   not "none".
5. 🔴 **"The only bound" is stale in three documents and in the class's own javadoc**, which says the
   default is unbounded directly above the constant that sets it to four (§EXE.2.5).
6. 🔴 **The status hierarchy is still stated with the retired word** in the register and three more current
   files — and the user guide **contradicts itself twelve lines apart**. One of these was recorded as
   "corrected" by an earlier spec and is still on disk.
7. 🔴 **Twenty-plus dead class citations** survive the 2026-08-31 rename, including **five in the active
   plan's "what actually runs" section** and two cited *by line number*.
8. ⚠ **"Lane" means four different partitions across five documents**, and the declared owner's own table
   **omits the post-sync lane** that another page calls one of two.
9. ⚠ **A job type id is misspelled in a concept page** — an underscore where the registered id has a dot.
   An operator copying it authors an unknown type.
10. ⚠ **An event name in a design page does not match the emitted constant** — the page uses the retired
    prefix, which survives only as a legacy alias, while a sibling page uses the canonical name and neither
    cross-references the other.
11. ⚠ **The retired pipeline word still names the concept in three execution documents**, including route
    templates and method names in prose.
12. ⚠ **The declared lane owner contradicts itself on the orphan gate thirty-four lines apart** — the
    second statement is the pre-2026-09-01 state that another page explicitly labels history.
13. ⚠ **The active plan says a new Step type cannot be added today**, contradicted by its own later row and
    by the boundary page; and it states the graph admission in its **pre-generalisation** form.
14. ⚠ **Two more self-contradictions**: a durable-store check recorded as both owed and discharged, and the
    seed count given as exactly one against a page that records the relaxation to one-or-more.
15. ⚠ **Record-level lineage is simultaneously refused and open** on one board — "won't do" in one spec and
    a live actionable row in another.
16. ⚠ **Refused-as-a-default versus planned-for-Enterprise** is the real position on distributed
    coordination, and **no document states it that way** — two say it is refused, three treat it as
    Enterprise scope.
17. ⚠ **No scheduler test class exists at all** for the ingest engine — recorded only in prose.
18. ⚠ **The retry's escape hatch is in no document**, and the descriptor's own count and the glossary's
    trigger entry are both wrong in ways nothing checks.

## Changes

- 2026-11-17: the remote-fetch bandwidth cap, first filed as `acquire.maxFilesPerCycle`, was built as `-Dingest.maxBytesPerCycle` (MoSCoW C8).
