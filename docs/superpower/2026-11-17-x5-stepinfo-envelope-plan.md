# X5 — one Consignment across lanes, and the Step Receipt (the board's "StepInfo envelope")

Status: **DESIGN — nothing built. Decisions D1–D10 (§7) are open.** Opened 2026-11-17 for MoSCoW S4's last
item ([`plan-architecture-moscow.md`](../okf/product/layer/platform/plan-architecture-moscow.md) §8 S4: build X5
before the 4.0.0 tag, with a release-notes entry). Board row: [`BACKLOG.md`](../BACKLOG.md) §2 *X5 cross-lane
drill-down + StepInfo envelope*. Items: `module.engine`, `module.api`, `layer.product`. Every code fact below was
measured on `origin/master` `417757c35` (2026-11-17); `file:line` is relative to the repo root.

## 0. Verdict first

- **X5 is three things under one name**, and the documents that cite it do not agree on which (§1). This plan
  splits it into **X5-a** (the token vocabulary: collapse the reject relations, retire the outcome relations),
  **X5-b** (the Step Receipt: one bounded record per Step per Consignment, on every durable lane) and **X5-c** (the
  drill-down: one read and one screen that follow one Consignment across lanes).
- **Only X5-a needs the MAJOR.** It changes values on the wire (`GET /pipelines/node-types`, `GET /provenance`) and
  in persisted authored graphs; after 4.0.0 that costs a 5.0.0. X5-b and X5-c are additive (a new table, a new
  route, a new screen) except for the shape of the new records, which must be right before the tag because a Java
  record cannot grow without a breaking change.
- **Nothing X5 touches shipped in `v3.11.0`**, the newest release on `master`'s line (§4.1). Every "breaking"
  change here is breaking against trunk and its contract files, not against a released 3.x install.
- **Token-model step 4 already landed under another name** (Platform Services Stage 2: `ExecutionMode`,
  `StepExecutor`, `StepContext`), and step 1 is done. Steps 2 and 3 are half done. Step 5 (fan-in) is not X5's.
- **Recommendation (D1 = B):** before the tag build X5-a, X5-b on every durable lane, and the X5-c read route
  (lanes L1–L4, about 10 engineer-days, 6–7 calendar days with two lanes in parallel). The screen (L5) and the
  Step SPI additions (L6) are additive and can follow in 4.1. Do **not** attempt an explicit token object threaded
  through the executor, a token-resolving Step SPI, or fan-in before the tag (§6).

## 1. What X5 means — the board row and the token model are not the same thing

| Where | What it calls X5 |
|---|---|
| [`BACKLOG.md`](../BACKLOG.md) §2 row | "One-Consignment drill-down across lanes; ~1 KB pointer+schema+diagnostics envelope, failure routed by PORT. Phase-7 convergence." |
| [`spec-node-types.md`](../okf/product/module/engine/spec-node-types.md) § *The token model* | Step 3's `ConservationCheck` migration ("real work, tracked as X5"), and steps 4–5 ("need the runtime decision (tracked as X5)") |
| [`plan-api-stability.md`](../okf/product/module/api/plan-api-stability.md) § Release notes | Two deferred items: "the token *runtime* model (D2)" **and** "X5's cross-lane StepInfo envelope" |
| [`plan-control-api.md`](../okf/product/module/processor/plan-control-api.md) §5 | "~1 KB pointer + schema + diagnostics, deferred to the MAJOR" |
| Origin (history only): [`execution-residuals-plan.md`](../archived-documents/plans-archive/execution-residuals-plan.md) §X5 | The drill-down is the missing **cross-lane view** (X2, shipped 2026-09-02, is "its data half"). The envelope is "precisely the token model's concrete shape": an immutable per-hop record, additive accumulation, bounded diagnostics, "failure routed by PORT not exception" — borrowed from an external design wiki |
| History only: [`pipeline-spec.md`](../archived-documents/plans-archive/pipeline-spec.md) §11.5, §13 D2 | Adopt the token vocabulary now; the runtime model with Phase 7's major window |

Where they overlap and where they differ:

1. **The envelope is the token, made durable.** The origin says so in as many words. The token model's own text
   (`{ consignmentId, runId, attributes, dataRefs[…] }`) is the in-flight shape; the board's envelope adds what a
   *record of the hop* needs (counts per outlet, schema identity, diagnostics). Same concept, two moments.
2. **"Failure routed by PORT" contradicts token-model step 2 if read literally.** Step 2 moves `failure` off the
   graph and onto a Signal; a failure *port* would put it back as an edge. §3.4 reconciles them (D2).
3. **"X5 = steps 4–5" is stale for step 4.** The Step SPI opened in Platform Services Stage 2 (§2.1), on a row
   reader rather than on the `ConsignmentProcessor` shape the token model named. Step 5 (fan-in, authoring shape)
   is an authoring decision (D6 of the archived spec keeps fan-in canvas-only) and does not need a MAJOR.
4. **"Phase-7 convergence"** names D2's second half. The other half of Phase 7, the Batch→Consignment rename,
   shipped (`ff33246a`) and the `batch_id` trio closed it on 2026-11-17. The runtime already passes references, not
   rows (§2.1, step "runtime"), so what is left of "convergence" is vocabulary (X5-a) plus a durable record of each
   hop (X5-b).
5. **"Breaks two committed contracts"** (`spec-node-types.md`, citing `node-attributes` and `step-types`) is stale:
   the `step-types` table was retired 2026-09-24 (GLOSSARY *Step*), and
   `inspecto-ui/src/app/inspecto/contracts/node-attributes.contract.json` holds no relation name (measured: no
   `emits`/`accepts`, no `dropped`/`unmatched`/`on_commit`). The contracts that do carry relation names are the
   ones in §4.

**The precise statement this plan builds:**

- **X5-a — token vocabulary (token-model steps 2–3).** Built-in reject relations become `reject:<reason>`; the
  three outcome relations with no producer are retired; `ConservationCheck` reports the reason. Read aliases for
  every persisted old spelling.
- **X5-b — the Step Receipt.** One record of at most 1024 bytes per (Consignment, Step, attempt), produced by every
  lane that leaves something durable (ingest flat, ingest graph fork, at-rest job, park and drain): rows in, rows
  per outlet, a pointer to where the data rests, a schema fingerprint, the outcome and bounded diagnostics. A
  failing Step is named on its receipt.
- **X5-c — the drill-down.** `GET /consignments/{consignmentId}` returns one Consignment's whole trail, and the
  Consignment detail screen follows it across lanes (ingest → the job runs that read it → their own Consignments).
- **Not X5:** fan-in (token step 5); an explicit token object replacing the scratch table names the executor passes
  today; a Step SPI that resolves data by reference instead of reading rows.

## 2. Current state (measured)

### 2.1 The token-model sequence

| Step ([`spec-node-types.md`](../okf/product/module/engine/spec-node-types.md)) | State | Evidence |
|---|---|---|
| 1 — say *token*, not rows on an edge | ✅ Done | `BuiltinNodeType.java:12-16` ("TOKEN vocabulary, not a record-… contract"); `PipelineRel.java:11-17` |
| 2 — move `success` / `failure` / `on_commit` / `gap` to Signals | ❌ Not done | All four constants live (`PipelineRel.java:60-68`) and are declared: `acquisition` emits `failure` and `gap` (`BuiltinNodeType.java:37`); the sinks emit `success` / `failure` / `on_commit` (`:262-281`); `enrichment` accepts `on_commit` (`:257`); `gap` is a CONTROL node fed by a lifted `gap` edge (`PipelineLift.java:69`, `BuiltinNodeType.java:286`). `success`, `failure` and `on_commit` have **no producer**; `on_commit` is only skipped (`PipelineExecutor.java:463,488`, `PipelineValidator.java:216,367`) |
| 3 — collapse the rejects into `reject:<reason>`; `ConservationCheck` reads the reason | ⚠ Half | `REJECT_PREFIX`, `reject()`, `isReject()` exist since S2-3 for contributed Steps (`PipelineRel.java:84-94`), and `ConservationCheck.relCounts` tags both spellings as diverted (`ConservationCheck.java:46-47,105`). The built-ins still emit `dropped` / `invalid` / `duplicate` (`RowShaper.java:437,441,526-533`) and the lift still draws `unmatched` (`PipelineLift.java:603`). `RelCount` has no reason field, and `relCounts` has **no production caller** (only tests). `imbalances` never looks at relation names (`ConservationCheck.java:74-82`), so the "real work" the token model warned about is about ten lines |
| 4 — open the Step SPI with `LOWERED` / `EXECUTED` | ✅ Landed differently | `ExecutionMode` (`ExecutionMode.java:17-18`), `PipelineNodeType.mode()`, `PipelineNodeTypes.register` refuses a pack's `LOWERED` type (`PipelineNodeTypes.java:77-79`), `StepExecutor` / `StepContext` (S2-3) run by `StepRunner` with a deadline and a watchdog (`StepRunner.java:75-134`). The shape is a **row reader plus per-relation writers**, modelled on `ProcessorContext` but with **no Consignment id and no token**: `StepContext` has `in()`, `emit(rel)`, `services()`, `signals()`, `dryRun()` (`StepContext.java:30-65`). A Step rejects rows by emitting to `reject:<reason>`, never by throwing (`StepExecutor.java:49-54`) |
| 5 — fan-in and the authoring shape | ❌ Not started | Not X5 (§6) |
| *Runtime: edges carry a reference, not rows* | ✅ Already true | The walk passes `produced: nodeId → rel → scratch table name` (`PipelineExecutor.java:212-213`); every shaped relation is a materialised table (`RowShaper.java:282-569`, `CREATE TABLE … AS`). Nothing pipes rows between Steps |

### 2.2 What each lane records about a Step today

| Lane | Per-Step record today | Where | Failure attribution |
|---|---|---|---|
| Ingest, flat | Two coarse rows, `parse|data` and `sink|data`, **SUCCESS only**, only with `-Dprovenance.backend` set | `ConsignmentIngestor.java:215,241-251` | Ledger row FAILED with an error string and `offendingFile`; no Step named |
| Ingest, graph fork | The **same two coarse rows** (shared tail at `ConsignmentIngestor.java:215`). The walk itself records nothing: it is given `ProvenanceCollector.NONE` | `ConsignmentGraphRunner.java:125` | As flat; branches already committed are merged onto the `/batches` row (`committedBranches`) |
| At-rest job | Every `(node, rel)` count, then `ConservationCheck` raises `PIPELINE_CONSERVATION_IMBALANCE`, only with `-Dprovenance.backend` set | `PipelineJobRunner.java:427-441,778-800` | Counts are persisted only **after** the walk returns (`:438-441`), so a failed run records **nothing**. The lane publishes a `ConsignmentEvent` only on SUCCESS (`:462`), so a failed job-lane Consignment has no `pipeline.batch.failed` Signal; the job run row says FAILED with a message, and the run store is off by default |
| Park | The manifest's `parkedAt` / `parkedTables` | `ConsignmentManifest.java:50-57`; served by `RunRoutes.withParkDetail` | n/a |
| Drain | The ordinary commit tail, no re-walk | `DrainCommand.java:81` | As flat |
| Scratch | `relations[]` in the response (`RelationCount`), nothing durable | `PipelineDryRun.java:41` | n/a |
| Live position (all) | `StepProgress` — in memory, cleared at the end | `PipelineExecutor.java:225`; flat phases `CsvIngestStrategy.java:141,227,233` | n/a |

A Step that throws anywhere in the walk fails the Consignment with an exception; nothing structured records which
Step it was. Only a contributed Step's `StepFailure` carries a code (`STEP_FAILED` / `STEP_TIMEOUT` /
`STEP_DISABLED`, `StepFailure.java:13-17`) and names the Step in its message.

### 2.3 Cross-lane links today

- **Job run → the Consignments it read** (X2): `inspecto_job_run_sources` (`DbJobRunStore.java:96-98`), served as
  `GET /jobs/runs/{runId}.derivedFrom[]` (`JobRoutes.java:340-370`) and the reverse
  `GET /runs/{name}/outputs.derivedRuns[]` (`RunRoutes.java:672`). ⚠ The run store is **off by default**
  (`jobs.backend=none`, [`spec-db-layer.md`](../okf/product/module/engine/spec-db-layer.md) store table), so both are absent on a default install.
- **Job run → its own Consignment**: not on the run row (nine scalars, `DbJobRunStore.java:88-91`). The job lane
  mints its Consignment id as `<job>-<millis>` unless one is pinned (`PipelineJobRunner.java:120-130`); a pinned
  id makes every run of that Job reuse one Consignment id. The only link is `consignment_outputs.run_id`
  (`DbConsignmentOutputStore.java:120-127`), the one store that is **on by default**.
- **Signals of one Consignment**: `GET /signals?correlationId=` (`SignalRoutes.java:99-108`); the terminal Signal's
  payload carries status, counts, `error` and `offendingFile`, no Step (`PipelineConsignmentSignal.java:34-52`).
- **Which lane ran an ingest Consignment** is decided at `ConsignmentIngestStrategy.java:229-235` and recorded
  nowhere. Why a write stayed flat is computed by `flatReason` (`:299-321`) only for the `-Dingest.lane=graph`
  refusal.

### 2.4 Read routes and screens today

| Read | Shows | Code |
|---|---|---|
| `GET /runs/{name}/batches`, `/files`, `/lineage`, `/outputs`, `/errors`, `/files/stage` | One ingest pipeline's Consignments, member files, input→output lineage, registered outputs, rejected rows, a file's stages | `RunRoutes.java:69-94` |
| `GET /provenance?pipeline=&consignment=`, `/provenance/batches` | Per-`(node, rel)` counts of one Consignment, for the Sankey; 404 when the store is off | `JobRoutes.java:150-151,372-394` |
| `GET /jobs/runs/{runId}` | One job run plus `derivedFrom[]` | `JobRoutes.java:340-370` |
| Consignment detail dialog (`BatchDetailDialog`) | Summary, member files, lineage, outputs, derived runs, parked Steps, committed branches | `inspecto-ui/src/app/modules/admin/run-detail/batch-detail.dialog.ts:160-283` |
| Job run detail dialog | "derived from" list, linked only where the producer pipeline is known | `inspecto-ui/src/app/modules/admin/jobs/job-run-detail.dialog.ts:49-98` |
| Pipeline editor Sankey | Counts of a chosen past run | `inspecto-ui/src/app/modules/admin/pipelines/pipeline-editor.component.ts:1283-1303` |

There is **no single read for one Consignment**: the dialog stitches three list reads and filters client-side
(`batch-detail.dialog.ts:227-245`), and nothing joins an ingest Consignment to the job-lane Consignments its
readers produced.

### 2.5 Constraints the design must respect

1. **No per-Step durable write on the hot path.** `StepProgress.java:21-23` records why: a per-Step Signal is a
   durable write per Step per Consignment on the claim-holding thread and can fire Jobs mid-Consignment. Receipts
   are collected in memory during the walk and written **once per Consignment**, best effort, never throwing into
   the commit (the `ProvenanceStores.record` posture).
2. **Counts must be taken while the scratch relations are live** (`PipelineExecutor.java:116-121`). Every shaped
   relation is a table, so `count(*)` is a metadata read, not a re-run.
3. **Default-off stores make an empty answer ambiguous.** Provenance (`ServiceStores.java:93`, default `none`) and
   the job run store are off by default. A read must say "unknown", never return `[]` for "off" (the X2 rule,
   `JobRoutes.java:352-354`).
4. **Records freeze at the tag; interfaces do not.** A public record gains a component only by changing its
   canonical constructor (a breaking change); an interface gains a default method additively. Every new public
   record in this plan is final in shape at 4.0.0; every extension point is an interface with default methods.
5. **The flat lane executes no Steps.** It runs three phases (`parse`, `transform`, `sink`). Per-Step receipts on
   the flat lane would mean running it on the graph, which is S3/Row 15, found infeasible 2026-11-17.
6. **`UNIQUE` with a `NULL` column does not hold** (`NULL ≠ NULL`; the trap recorded at
   `ConsignmentGraphRunner.java:80-87`). Ingest-lane receipts have no run id, so the key column holds `''`, never
   `NULL`.
7. **Two Job triggers read Consignment events differently.** `on_pipeline` fires only on SUCCESS
   (`JobService.java:1129-1130`), but `mirrorPipelineCommit` turns **every** `ConsignmentEvent` into a
   `pipeline.commit` Signal (`JobService.java:1223-1226`). A new FAILED `ConsignmentEvent` on the job lane would
   therefore reach `on_signal: pipeline.commit` Jobs (D9).

## 3. The design

### 3.1 Name (D7)

The board's working name is "StepInfo envelope". **Envelope** already names three other things in the GLOSSARY
(the v1 response envelope `{data, metadata, diagnostics}`, the 13-field `Signal` record, a registry component's
sharing envelope), so it breaks one-word-one-concept. Proposed canonical name: **Step Receipt** — "the receipt a
Step leaves for one Consignment". Type/Instance: the *Step Receipt* shape is the type; each stored row is one
receipt. Added to [`GLOSSARY.md`](../GLOSSARY.md) in the lane that builds it (L1), with "StepInfo envelope" listed
as the retired working name. The token model's **outlet** is the canonical word for the board's "PORT"
(GLOSSARY *Stream*: "the model's word is the **reject outlet**").

### 3.2 Shape

One Step Receipt per (Consignment, Step, attempt). Java: `com.gamma.pipeline.exec.StepReceipt` record,
`@PublicApi(since = "4.0.0")`, with nested `Outlet`, `Pointer`, `Diagnostics` records.

| Field | Type / bound | Meaning |
|---|---|---|
| `v` | int, `1` | Shape version, so a later reader can tell |
| `consignmentId` | ≤ 128 chars | The Consignment |
| `pipeline` | ≤ 128 chars | Pipeline id (the authored id on the job lane) |
| `lane` | `ingest-flat` · `ingest-graph` · `job` · `drain` | Which lane wrote it ([execution lanes](../okf/product/module/engine/spec-execution-lanes.md)) |
| `runId` | ≤ 128 chars, `''` on ingest lanes | The job run (an attempt); `''`, never `null` (§2.5 item 6) |
| `seq` | int | Order within (Consignment, run, lane): the walk's topological index, or the phase index on the flat lane |
| `step` / `kind` | ≤ 128 chars each | Node id and node type (`transform.filter`); on the flat lane the phase (`parse`, `transform`, `sink:<destination>`) and `phase` |
| `outcome` | `OK` · `FAILED` · `SKIPPED` · `PARKED` · `NOT_REACHED` | `SKIPPED` = a disabled Step bypassed; `NOT_REACHED` = downstream of a failure |
| `in` | long | Rows entering (the sum over inbound outlets; the seed's own count for a seed) |
| `outlets` | ≤ 16 × `{outlet, rows}` | Rows leaving per outlet (§3.3) |
| `ref` | `Pointer` or `null` | Where the data rests *durably*: `outputs` (table + file count; the files are the registry's rows for this Consignment), `park` (the park table's path relative to the data root), `rejects` (the reject sidecar), `store` (store + partition). Scratch relations are not durable and get no pointer |
| `schema` | `{columns, fp}` | Column count and a 12-hex `CanonicalHash` of the `data` outlet's `(name, type)` list. Two adjacent receipts with different `fp` mark where the shape changed. Full columns stay in the Catalog |
| `diag` | `{code, msg ≤ 240 chars, counts ≤ 8 × name→long}` | `code` from a closed set: `STEP_FAILED` · `STEP_TIMEOUT` · `STEP_DISABLED` (the `StepFailure` codes) · `SQL_ERROR` · `IO_ERROR` · `CONSERVATION_LOSS` · `CONSERVATION_AMPLIFICATION` · `LANE_FLAT` (with the `flatReason` text) · `null` |
| `startedAt`, `ms` | ISO-8601, long | When and how long |
| `simulated` | boolean | A dry run's receipt (the `ProvenanceRow.simulated` rule) |
| `truncated` | boolean | Something was cut to fit |

**Size bound.** The JSON form is at most **1024 bytes UTF-8**, enforced when the record is built, not by
convention. Cut in this order until it fits: `diag.msg` to 240 chars; `outlets` beyond 16 folded into one
`route:*` entry carrying their sum; `diag.counts` beyond 8 dropped; `ref` path kept to its last 128 chars. Each cut
sets `truncated`. Identity fields are bounded by the id validators that already guard them. Example (a filter Step
on the graph fork, about 420 bytes):

```json
{"v":1,"consignmentId":"20261117T101500_cdr_voice_0007","pipeline":"cdr_voice","lane":"ingest-graph",
 "runId":"","seq":4,"step":"flt_valid","kind":"transform.filter","outcome":"OK","in":120000,
 "outlets":[{"outlet":"data","rows":119412},{"outlet":"reject:dropped","rows":588}],"ref":null,
 "schema":{"columns":31,"fp":"9c1e4f0a2b7d"},"diag":{"code":null,"msg":null,"counts":{}},
 "startedAt":"2026-11-17T10:15:02.114Z","ms":41,"simulated":false,"truncated":false}
```

**Immutable and additive.** A receipt is written once and never updated. A Consignment's trail is the ordered set
of its receipts; a drain appends `drain` receipts to the parked Consignment's trail; a re-run of a pinned job-lane
Consignment appends under its own `runId`.

### 3.3 Outlets

The vocabulary is the token model's three kinds, with one spelling change (D4): **`data`** (the token continues;
the token model wrote `main`), **`reject:<reason>`** (rows a Step did not pass on; reasons `dropped`, `invalid`,
`duplicate`, `unmatched`, or a contributed Step's own), **`route:<key>`** (content demux). `PipelineRel.canonical(rel)`
maps every legacy spelling (`dropped` → `reject:dropped`, …) and is the identity otherwise, so receipts are
canonical from the first build **whether or not X5-a lands** — that is what makes L1 and L2 independent.

### 3.4 "Failure routed by PORT" — what it means here (D2)

Read literally, it asks for a `failure` outlet a Step could send a failed token down, with the walk continuing into
an error-handling Step. That contradicts token-model step 2 (failure is a Signal, not an edge) and the
per-Consignment commit (a Consignment commits or fails whole; only route branches commit separately, through
`BranchCommitLog`). The reconciled reading, recommended:

- **Rows a Step will not pass on go to an outlet**: `reject:<reason>`. Already the contributed-Step contract
  (`StepExecutor.java:49-54`); X5-a makes the built-ins speak it too.
- **A Step that fails is recorded at the Step**: the walk catches the throw once, writes that Step's receipt with
  `outcome: FAILED` and the code, marks every Step it did not reach `NOT_REACHED`, flushes the receipts, and
  rethrows. The Consignment still fails exactly as today.
- **The failure leaves the graph as a Signal**: `pipeline.batch.failed` gains `failedStep` and `code` in its payload
  (additive keys, `PipelineConsignmentSignal.java:44-52`). On the job lane this Signal is new (D9).

### 3.5 Where receipts are produced, per lane

| Lane | Producer and hook | Steps named | Pointers | On failure |
|---|---|---|---|---|
| Ingest, flat | `ConsignmentIngestor` at its one status-final point (`:215`, which today calls `recordProvenance` for SUCCESS only) — for **every** status | Three phases from counts the lane already holds (`IngestOutcome`): `parse` (`data` = input rows, `reject:unmatched` = sidecar rows; cast failures in `diag.counts`), `transform` (`route:<rule>` for rows a Decision Rule routed, to be grounded in L3), one `sink:<destination>` per `sinks[]` entry. `diag.code = LANE_FLAT` with the `flatReason` text on `parse` | `rejects` on `parse`, `outputs` on each sink | The phase that threw: FAILED; later phases NOT_REACHED |
| Ingest, graph fork | `ConsignmentGraphRunner.run` passes a real walk observer instead of `NONE` (`:125`); receipts flushed at the same `:215` point | The `parse` phase (shared with flat), then one receipt per walked Step below the seed, including each sink branch | `outputs` per sink branch; `rejects` on `parse` | FAILED at the Step that threw; branches already committed stay listed (`committedBranches`) |
| At-rest job | `PipelineJobRunner` wraps the collector it already builds (`:427-430`) and flushes in a `finally`, success or failure | One per walked Step, the `source_store` seeds included; a `ConservationCheck` imbalance is written onto that Step's `diag` as well as raised as today | `outputs` per sink (registry rows with this `run_id`) | FAILED at the Step; the job run row as today; `pipeline.batch.failed` per D9 |
| Park | The walk reaching a disabled sink with a live inbound relation (`PipelineExecutor.java:232-235`) | That sink: `PARKED` | `park` (the park table) | n/a |
| Drain | `DrainCommand` at its commit | One `drain` receipt per parked sink it commits | `outputs` | As flat |
| Scratch | Not persisted. *Could, later:* the same shape in the run-to-here response | — | — | — |

One observer interface replaces the bare 3-argument `ProvenanceCollector` as the walk's hook:
`PipelineExecutor.WalkObserver` with default methods (`counts`, `stepStarted`, `stepEnded(outcome, diag)`,
`parked`), so it can grow in 4.x without a MAJOR. `ProvenanceCollector` stays and is adapted onto it, so the
existing provenance rows and the conservation check are unchanged. A contributed Step's own diagnostics (L6) enter
through a default method on `StepContext` (`note(code, count)`), capped by §3.2.

### 3.6 Where receipts are stored (D3) and how long (D10)

Recommended: **a table beside `consignment_outputs`**, in the default-on Consignment registry DB
(`consignment.outputs.backend=duckdb`), written through the same `ConnectionSource` (DuckDB is single-writer per
file):

```sql
CREATE TABLE IF NOT EXISTS consignment_steps (
  consignment_id VARCHAR, run_id VARCHAR,           -- run_id '' on ingest lanes, never NULL
  pipeline VARCHAR, lane VARCHAR, seq INTEGER,
  step VARCHAR, kind VARCHAR, outcome VARCHAR, rows_in BIGINT,
  receipt VARCHAR,                                  -- the ≤ 1024-byte JSON
  recorded_at VARCHAR, simulated BOOLEAN,
  UNIQUE (consignment_id, run_id, lane, seq));
CREATE INDEX IF NOT EXISTS consignment_steps_by_consignment ON consignment_steps (consignment_id);
```

Why there and not in the provenance DB: the provenance DB is off by default and its default is tied to the OPS-5
live soak of the *conservation alerts* ([`tests-provenance-conservation.md`](../okf/product/layer/platform/tests-provenance-conservation.md)
step 4). Receipts do not raise alerts, and on the flat lane their counts are already in hand. Putting them in the
default-on registry gives a drill-down that works on a default install while the provenance rows, the Sankey and
the conservation alert stay exactly as they are. Switch: `-Dconsignment.steps=false`. Retention: rows older than
`-Dconsignment.steps.retentionDays` (default 30) are deleted by the registry's `db_maintenance` pass. Volume: about
5 Steps × 1 KB per Consignment, before DuckDB compression; 5 000 Consignments a day is about 25 MB a day
uncompressed, which is why retention is part of the design and not a follow-up.

### 3.7 Where receipts are served (D8)

`GET /api/v1/consignments/{consignmentId}` — one Consignment across every lane. A new route family under the
canonical noun; `/runs/{name}/…` is keyed by an ingest pipeline config, which a job-lane Consignment does not have.

```
{ "consignmentId", "pipeline", "status",                  // ledger row, or the job run's status
  "lanes": ["ingest-graph", "drain"],
  "members": [ … ],                                        // manifest members (ingest lanes)
  "steps": [ StepReceipt … ],                              // ordered by run, lane, seq; ABSENT when switched off
  "outputs": [ … ], "outputsTotal": n,                     // consignment_outputs rows, first 200
  "upstream":   [ {consignmentId, pipeline, tableName} ],  // what its job run read (X2)
  "downstream": [ {runId, job, status, consignmentId} ],   // runs that read it (X2), each with its OWN Consignment id
  "parked": { "at": [...], "tables": {...} },
  "signals": [ {type, at, severity, message} ],            // correlationId = this id, newest 50
  "known": { "steps": bool, "runs": bool, "outputs": bool } }
```

- **404** when no store knows the id (ledger, registry, receipts, run sources). ⛔ Never an empty document.
- `known.*` says which stores could answer; a `false` means "this install cannot say", and the screen says so (the
  X2 rule).
- `downstream[].consignmentId` comes from the job lane's receipts (`runId` + `consignmentId`), else from
  `consignment_outputs.run_id`. That closes the gap of §2.3 (no run → own Consignment link).
- Gates: an open read under Space admission, like `/runs/{name}/batches`; `signals[]` goes through the same read
  masking as `GET /signals`. Real-HTTP test class per the `endpoint` skill.
- `docs/api/openapi-v1.json`: new `ConsignmentTrail` and `StepReceipt` schemas.

### 3.8 The drill-down screen — what one Consignment's view shows

The existing Consignment detail dialog (`BatchDetailDialog`) gains a **Steps** section and **Upstream /
Downstream** navigation, fed by the one read above instead of three list reads. It opens from Run detail's
Consignment list (as today), from Job run detail's "derived from" links, from the pipeline editor's run picker, and
from an Alert whose correlation id is a Consignment (`PIPELINE_CONSERVATION_IMBALANCE`). Built per the
`angular-ui` skill (status-badge, empty-state, no hard-coded colour, axe).

| Lane | What the Steps section shows |
|---|---|
| Ingest, flat | Three phase rows (parse → transform → each sink) with rows in and out, rejected rows with a link to the rejected-rows dialog, and a plain line: "Written without the Pipeline graph: *reason*" |
| Ingest, graph fork | The parse row, then one row per Step with its outlets (kept, rejected by reason, each route), a marker where the column set changes, each sink branch with *committed* / *not committed* |
| At-rest job | One row per Step from the seeds to the sinks, a conservation finding on the Step it concerns, and **Upstream**: the ingest Consignments the run read, each opening its own trail |
| Park / drain | *Parked here* on each disabled sink with its park table; after the drain, the drain's rows under it and the status moving from PARKED to its end |
| Any failure | The failed Step highlighted with its code and message; the Steps after it greyed as *not reached* |
| Downstream (all lanes) | Each job run that read this Consignment, with its status, opening **that run's own Consignment** in the same dialog with a way back — one Consignment, followed across lanes |

When `known.steps` is false: "Step detail is switched off on this install" — not an empty list.

### 3.9 X5-a — the token vocabulary changes

| Change | Where | Read alias |
|---|---|---|
| `PipelineRel.DROPPED` / `INVALID` / `DUPLICATE` / `UNMATCHED` keep their **names**; their **values** become `reject:dropped` … `reject:unmatched`. New `PipelineRel.reason(rel)` and `PipelineRel.canonical(rel)` | `PipelineRel.java:64,72-76`; producers `RowShaper.java:437,441,526-533`, `PipelineLift.java:603`; the seven main files that use the constants keep compiling | — |
| `success`, `failure`, `on_commit` retired (no producer) | `PipelineRel.java:60-68`; `BuiltinNodeType.java:37,257-281`; skips at `PipelineExecutor.java:463,488`, `PipelineValidator.java:216,367`, `PipelineDryRun.java:316` | An authored edge carrying one is dropped on load with a validation warning naming it (`PipelineCodec.java:84,105`); it never ran, so nothing changes at run time |
| `gap` stays an edge to its CONTROL node in 4.0.0 (D5 = b) | `PipelineLift.java:69`, `BuiltinNodeType.java:286` | — (the token-model doc is amended to say so) |
| Authored graphs | `PipelineCodec.java:39,84,105` | Decode maps through `canonical`; the next save writes the new spelling. No file is rewritten in place |
| `inspecto_pipeline_provenance.rel` | `DbProvenanceStore.query` | The read maps legacy values in SQL (`CASE rel WHEN 'dropped' THEN 'reject:dropped' …`); no stored row is rewritten |
| `ConservationCheck` | `ConservationCheck.java:43,46-47,97-108` | `REJECT_RELS` deleted (`isReject` covers it); `RelCount` gains `reason` (the record's last chance to grow) |
| UI | `pipeline-graph.ts:733,1205`, `pipeline-editor.component.ts:2810`, `run-to-here.dialog.ts:446` (all under `inspecto-ui/src/app/modules/admin/pipelines/`) | Same lane; the palette reads `emits` from the wire |
| Scratch table names | `RowShaper.table` sanitises the relation into the name (`RowShaper.java:1585-1587`), as it already does for `route:<key>` | — |

## 4. Breaking surface

### 4.1 Against the last release: none

Verified 2026-11-17 with `git ls-tree -r --name-only v3.11.0`: none of `PipelineRel`, `ConservationCheck`,
`ProvenanceRow`, `DbProvenanceStore`, `PipelineExecutor`, `StepContext`, `StepExecutor`, `StepFailure`,
`PipelineNodeType`, `BuiltinNodeType` exists in `v3.11.0`, and `git grep` finds no `/provenance` or
`/pipelines/node-types` route and no edge-relation vocabulary there. Under
[`plan-api-stability.md`](../okf/product/module/api/plan-api-stability.md) § *Release baseline* these may change
freely before 4.0.0 ships. The changes below are breaking only against trunk, its contract files and data written
by trunk builds.

### 4.2 Against trunk

| # | Surface | Kind | Change | Migration / alias |
|---|---|---|---|---|
| 1 | `GET /pipelines/node-types` `emits` / `accepts` (`PipelineProjection.java:42-43`) | Wire | Reject values become `reject:*`; `success` / `failure` / `on_commit` gone | None on the wire; the SPA reads it live |
| 2 | `GET /provenance` `rel` | Wire | New rows `reject:*`; old rows mapped on read | SQL `CASE` alias; no rewrite |
| 3 | Authored graph JSON edges (`PipelineProjection.java:240`) and the dry-run `relations[].rel` (`PipelineDryRun.java:41`) | Wire | The lifted parse→quarantine edge and dry-run rejects read `reject:*` | UI changed in the same lane |
| 4 | `pipeline.batch.failed` payload | Wire (Signal) | Adds `failedStep`, `code` | Additive keys |
| 5 | `GET /consignments/{consignmentId}` | Wire | New | Additive |
| 6 | Authored graph files (`PipelineCodec`) | Persisted | Old spellings read; new spelling on next save; outcome edges dropped with a warning | Read alias at decode |
| 7 | `inspecto_pipeline_provenance` rows | Persisted | None rewritten | Read alias |
| 8 | `consignment_steps` table | Persisted | New, created on open | Additive |
| 9 | `-Dconsignment.steps`, `-Dconsignment.steps.retentionDays` | Config | New `-D` keys | Defaults: on, 30 days |
| 10 | Authored TOON config | Config | **None** — no `*_pipeline.toon` key holds a relation (lift-derived); `processing.disabled_steps` unaffected | — |
| 11 | `PipelineRel` (`public-api.txt` lines `com.gamma.pipeline.PipelineRel`) | `@PublicApi` | Three constants removed; four values changed; `reason`, `canonical` added | `node tools/check-public-api.mjs --update` in the lane |
| 12 | `ConservationCheck.RelCount` | `@PublicApi` record | Gains `reason` | Same |
| 13 | `PipelineExecutor` | `@PublicApi` | `WalkObserver` interface and the overload that takes it | Same; the six existing overloads stay |
| 14 | `StepReceipt` (+ `Outlet`, `Pointer`, `Diagnostics`) | `@PublicApi` | New | Same |
| 15 | `DbConsignmentOutputStore` | `@PublicApi` | `recordSteps`, `steps(consignmentId)` | Same |
| 16 | `StepContext` (L6) | `@PublicApi` interface | `consignmentId()`, `note(code, count)` as default methods | Additive; may wait for 4.1 |
| 17 | `node-attributes.contract.json`, `processor-catalog.contract.json` | Committed contract | **Unchanged** under D5 = b (no relation names in either; the `gap` node stays) | — |

### 4.3 Release-note entry (draft — paste into `plan-api-stability.md` § Release notes when the lanes land)

> **Breaking — one word for rejected rows, and a record of every Step (X5, MoSCoW S4)**
>
> A Step that does not pass rows on now says why in one way: the **reject outlet** `reject:<reason>`.
> - **Reject relations are `reject:dropped`, `reject:invalid`, `reject:duplicate` and `reject:unmatched`**, replacing
>   `dropped`, `invalid`, `duplicate` and `unmatched` in `GET /pipelines/node-types` (`emits`), `GET /provenance`
>   (`rel`), a Pipeline graph's edges and a dry run's `relations[]`. Saved Pipeline graphs and stored provenance rows
>   are read under both names; a graph is saved with the new names the next time it is saved. ⚠ A client or BI
>   query that matches the old values must change. The `PipelineRel` constants keep their names.
> - **The `success`, `failure` and `on_commit` relations are removed.** Nothing produced them. An edge that names
>   one is dropped when its graph loads, with a warning naming it. A Consignment's outcome is the
>   `pipeline.batch.committed`, `pipeline.batch.failed` or `pipeline.batch.parked` Signal.
> - **New: a Step Receipt for every Step a Consignment passes through**, on the ingest lanes, the at-rest job lane,
>   and park and drain: rows in, rows per outlet, where the data rests, a schema fingerprint and, when it failed,
>   the Step and its code. At most 1 KB each, kept 30 days (`-Dconsignment.steps.retentionDays`), on by default
>   (`-Dconsignment.steps=false` switches it off).
> - **New: `GET /consignments/{consignmentId}`** returns one Consignment across lanes — status, member files, its
>   Step Receipts, outputs, the job runs that read it and the Consignments those runs produced, and its Signals —
>   and the Consignment detail screen follows it.
> - `pipeline.batch.failed` carries `failedStep` and `code`. *(If D9 = c:)* the at-rest job lane now raises
>   `pipeline.batch.failed` when a run fails; it raised nothing before.

## 5. Build plan — lanes that build and push on their own

Each lane starts from a fresh `origin/master` worktree, runs `node tools/affected-modules.mjs` and the targeted
tests it names, gets one `verification` agent PASS, and pushes alone. The full gate is not run per lane. Docs:
each lane updates the engine hub ([`spec-node-types.md`](../okf/product/module/engine/spec-node-types.md),
[`spec-execution-lanes.md`](../okf/product/module/engine/spec-execution-lanes.md),
[`spec-db-layer.md`](../okf/product/module/engine/spec-db-layer.md)) in the same commit.

| Lane | Scope | Depends on | Tests | Falsification (must turn red) | Size |
|---|---|---|---|---|---|
| **L0** measure | Ingest wall-clock on the demo corpus with receipts on vs off (graph fork counts + one batched insert); decide D3 | — | A benchmark in the style of `BridgeSpikeBenchmark`, not a gate | — | 0.5 d |
| **L1** receipt core + job lane | `StepReceipt` + size cap; `PipelineRel.canonical`; `WalkObserver`; `consignment_steps` table, write and read; job lane flushes in a `finally` with FAILED / NOT_REACHED; conservation onto `diag`; GLOSSARY entry | — | `StepReceiptTest` (worst case: a 10 KB message and 500 route outlets stays ≤ 1024 bytes, `truncated`); `PipelineJobRunnerTest`: a filter Step's receipt reads `data` N + `reject:dropped` M with `in` N+M; a contributed Step that throws leaves receipts for every upstream Step, its own FAILED with `STEP_FAILED`, the rest NOT_REACHED; an injected imbalance appears on the Step's `diag`; `DbConsignmentOutputStoreTest` round trip, `run_id` `''` uniqueness | Remove the `finally` flush → the failure test finds no receipts. Raise the cap → the size test fails | 2.5 d |
| **L2** token vocabulary (X5-a) | §3.9: values, retirements, `PipelineCodec` alias, provenance read alias, `RelCount.reason`, UI four sites, `public-api.txt`, token-model doc amended (D4, D5) | — (L1's `canonical` is added here too if L2 lands first) | A legacy authored graph fixture (`unmatched`, `dropped`, `on_commit`, `success` edges) loads, validates with a warning, runs, saves with new names; `GET /provenance` over a row written as `dropped` answers `reject:dropped`; `PipelineProjectionTest` pins `emits`; `ConservationCheckTest` reasons; vitest for the four UI sites | Delete the decode alias → the legacy fixture fails validation. Delete the SQL alias → the old row reads `dropped` | 2.5 d |
| **L3** ingest lanes, park, drain | Flat phase receipts from `IngestOutcome` for every status; graph fork observer at `ConsignmentGraphRunner.java:125`; PARKED and `drain` receipts; ground the Decision Rule `route:` counts | L1 | Parity: one input through `-Dingest.lane=flat` and through the graph fork gives equal `sink` rows on both trails (extend `FlatVsGraphLaneParityTest`); a Step throwing on the graph fork leaves its FAILED receipt; a parked Consignment's trail shows PARKED + park pointer, and after `POST /runs/{name}/drain` the `drain` receipts | Put `NONE` back at `ConsignmentGraphRunner.java:125` → the graph-fork test finds only `parse`. Record SUCCESS only → the failure test is red | 3 d |
| **L4** the read route | `GET /consignments/{consignmentId}` (§3.7), OpenAPI schemas, masking | L1 (L3 for ingest content) | `ControlApiConsignmentTrailTest`, real HTTP: 404 unknown id; receipts switched off → `steps` absent and `known.steps=false`, never `[]`; ingest → job hop: `downstream[0].consignmentId` read again shows `upstream` = the first id; a masked Signal message stays masked | Return `[]` when off → red. Drop the `run_id` join → the hop test is red | 2 d |
| **L5** the screen | §3.8 in `BatchDetailDialog` and its entry points; one read instead of three | L4 | vitest: steps render outlets and the failed Step; upstream and downstream navigate and come back; "switched off" text; axe | Render `[]` for an absent `steps` → the off-state spec is red | 2.5 d |
| **L6** Step SPI notes | `StepContext.consignmentId()` and `note(code, count)` (default methods) onto the receipt; `tools/templates/step` updated | L1 | `StepRunnerTest`: a Step's note lands on its own receipt, capped at 8 | Drop the cap → a Step writing 50 notes overflows the receipt test | 1 d |

**Totals.** All lanes: about 14 engineer-days. D1 = B (L1–L4 plus the release note): about 10, or 6–7 calendar
days as two chains in parallel (L2 alone; L1 → L3 and L4). L5 and L6 follow in 4.1, additive.

**Order.** L2 and L1 can start the same day. L3 and L4 after L1. L0 before L3 merges if D3 is (a) or (c) with a
cost ceiling. The release note is added by the last lane before the tag, the BACKLOG row narrowed by each lane and
closed by the last; on ship, this plan's as-built facts go to the engine hub and the plan is archived.

## 6. Infeasible, or not worth it, before 4.0.0

- **An explicit token object through the executor** (edges carrying `{consignmentId, runId, attributes, dataRefs}`
  instead of scratch table names). The walk already passes references (§2.1), so it changes no behaviour an
  operator can see; it is an internal refactor that can land in 4.x if it is ever wanted.
- **A Step SPI that resolves data by reference** instead of the row reader. The row reader is the shipped pack
  contract (S2-3), and a by-reference Step would hand third-party code SQL over the engine's relations, which
  `ExecutionMode` refuses to packs on purpose (`ExecutionMode.java:9-12`, archived R2). Not before a SQL-fragment
  guard exists.
- **Fan-in and the authoring shape** (token step 5). An authoring decision, not a MAJOR item.
- **Per-Step receipts on the flat lane.** The flat lane runs no Steps (§2.5 item 5); only the S3 deletion would
  change that, and S3 was found infeasible on 2026-11-17.
- **A `failure` outlet that continues the walk** (the literal D2 reading). It contradicts D2 and the commit model.
- **Turning on the conservation alert by default.** Needs the OPS-5 live soak, which is external.
- **Moving `gap` to a Signal.** Possible (the `SEQUENCE_GAP` event exists), but it deletes a node type, changes the
  canvas, the processor catalog and the node-attributes contract, for no operator gain. D5 recommends keeping it.

## 7. Decisions for the operator

**Taken 2026-11-17, as recommended**, under the operator's standing instruction for this MoSCoW run ("take
decisions reasonably"): D1 = B · D2 = b · D3 = c · D4 = b · D5 = b · D6 = a · D7 = b · D8 = a · D9 = c · D10 = a.
Also decided: §8's `batchId` components of `ProvenanceRow` and `ConsignmentEvent` are renamed to `consignmentId`
before the tag (now, not 5.0.0), in a lane of their own after L2. Each is reversible until the 4.0.0 tag; the
operator may overturn any of them, and a lane not yet pushed is then re-scoped.

1. **D1 — How much of X5 lands before the 4.0.0 tag.**
   (A) all lanes L0–L6, about 14 engineer-days; (B) the MAJOR-gated vocabulary plus the receipts on every durable
   lane and the read route — L1–L4 and the release note, about 10 engineer-days — with the screen and the Step SPI
   notes in 4.1; (C) the vocabulary and the receipt's shape only (L2 + L1), about 5 days, everything else in 4.x.
   **Recommendation: B.** It freezes every shape correctly at 4.0.0 and gives the drill-down as an API at the tag;
   the screen is additive and can iterate.
2. **D2 — What "failure routed by PORT" means.**
   (a) literally: a `failure` outlet a Step routes a failed token down, the walk continuing; (b) reconciled: rows
   go to `reject:<reason>` outlets, a failed Step is recorded on its receipt and named in `pipeline.batch.failed`,
   and the Consignment still fails whole. **Recommendation: b** — (a) contradicts token-model step 2 and the
   per-Consignment commit.
3. **D3 — Where receipts are stored, and whether they are on by default.**
   (a) the provenance DB, flipping `-Dprovenance.backend` to `duckdb` by default; (b) the provenance DB, default
   off as today; (c) a `consignment_steps` table beside `consignment_outputs` in the default-on registry DB, on by
   default, provenance and the conservation alert unchanged. **Recommendation: c** — a drill-down that is empty on
   a default install is not one, and (a) ties receipts to OPS-5's unfinished live soak.
4. **D4 — The spelling of the main outlet.**
   (a) rename `data` to `main` everywhere, as the token model wrote it; (b) keep `data` and amend the token model so
   the main outlet is spelled `data`. **Recommendation: b** — `data` is the default when an edge omits `rel`, is in
   every saved graph, and renaming it changes every edge for no operator gain.
5. **D5 — Token-model step 2 (the outcome relations).**
   (a) retire `success`, `failure`, `on_commit` **and** move `gap` to the `SEQUENCE_GAP` Signal, deleting the `gap`
   node; (b) retire the three with no producer, keep `gap` as an edge to its node; (c) change nothing.
   **Recommendation: b.** After the tag, (a) costs a 5.0.0 — accepted.
6. **D6 — Token-model step 3 (the reject relations).**
   (a) change the four values to `reject:<reason>` in 4.0.0, with read aliases; (b) keep the old spellings in
   4.0.0 (receipts translate them) and collapse in 5.0.0. **Recommendation: a** — it is the one part of X5 that
   genuinely needs this MAJOR.
7. **D7 — The name of the record.**
   (a) "StepInfo envelope" as on the board; (b) **Step Receipt**, added to the GLOSSARY; (c) "Step Record".
   **Recommendation: b** — "envelope" already names three things in the GLOSSARY and "record" names a data row.
8. **D8 — The drill-down route.**
   (a) `GET /consignments/{consignmentId}`, a new family under the canonical noun; (b)
   `GET /runs/{name}/consignments/{consignmentId}`, which needs an ingest pipeline name that a job-lane Consignment
   does not have; (c) widen the `/runs/{name}/batches` rows. **Recommendation: a.**
9. **D9 — A failed run on the at-rest job lane.**
   (a) publish a FAILED `ConsignmentEvent` (fires `pipeline.batch.failed`, and through `mirrorPipelineCommit` a
   WARN `pipeline.commit` Signal that `on_signal: pipeline.commit` Jobs see); (b) only the receipt, no new Signal;
   (c) emit `pipeline.batch.failed` alone, not a `ConsignmentEvent`. **Recommendation: c** — the failure becomes
   visible without changing which Jobs fire on `pipeline.commit`.
10. **D10 — How long receipts are kept.**
    (a) 30 days by default, pruned by `db_maintenance`, `-Dconsignment.steps.retentionDays` to change; (b) as long
    as the Consignment's registry rows; (c) never pruned. **Recommendation: a.**

## 8. Adjacent findings (not X5; noted, not changed)

- `ProvenanceRow` and `ConsignmentEvent` still name their Consignment component `batchId`
  (`ProvenanceRow.java:29`; `public-api.txt`, `com.gamma.etl.ConsignmentEvent`). Both are public records first
  released in 4.0.0, so after the tag the name is fixed until 5.0.0. Worth a decision of its own before the tag.
- The GLOSSARY *Step* entry says plugin Steps use the `ConsignmentProcessor` SPI; the pack Step SPI that runs in a
  Pipeline is `StepExecutor` (S2-3). `ConsignmentProcessor` is the post-sync Job SPI.
- [`spec-node-types.md`](../okf/product/module/engine/spec-node-types.md) says steps 1–3 "are documentation and
  vocabulary"; step 3 changes wire and persisted values (§4.2 rows 1–3, 6–7), which is why it needs this MAJOR.

## References

- Board: [`BACKLOG.md`](../BACKLOG.md) §2 *X5*; MoSCoW: [`plan-architecture-moscow.md`](../okf/product/layer/platform/plan-architecture-moscow.md) §8 S4
- Design of record: [`spec-node-types.md`](../okf/product/module/engine/spec-node-types.md) § *The token model*;
  lanes: [`spec-execution-lanes.md`](../okf/product/module/engine/spec-execution-lanes.md);
  park and drain: [`spec-step-park-drain.md`](../okf/product/module/engine/spec-step-park-drain.md);
  stores: [`spec-db-layer.md`](../okf/product/module/engine/spec-db-layer.md)
- API policy and release notes: [`plan-api-stability.md`](../okf/product/module/api/plan-api-stability.md)
- Control plane deferrals: [`plan-control-api.md`](../okf/product/module/processor/plan-control-api.md) §5
- Platform Services (the Step SPI as built): [`spec-platform-services.md`](../okf/product/module/processor/spec-platform-services.md)
- History only: [`execution-residuals-plan.md`](../archived-documents/plans-archive/execution-residuals-plan.md) §X2, §X5;
  [`pipeline-spec.md`](../archived-documents/plans-archive/pipeline-spec.md) §11, §13 D2
