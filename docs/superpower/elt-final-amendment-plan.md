# ELT Final Amendment — one model, one vocabulary, one authoring surface

**Status: APPROVED v1.1 (2026-08-05) — all thirteen §9 decisions resolved. Phase 0 DONE; Phase 1
grounded, slice 1 of record set. v1.1 adds D-12 (Batch→Consignment = Phase 7, in-window, sequenced
last) and D-13 (per-Step `enabled:` pause with park/drain, Phase 4) plus the §2.7 Step-lifecycle
table.** *(Was DRAFT v0 earlier the same day; iterated through routing/§2.6, the Pipeline
Document/§5.1, the dedup boundary/§2.4, and schema-registry semantics/§3.4 before approval.)*

> **What this is.** The operator's 2026-08-05 directive: *"unify into one, on a single plan, for the
> final time — we have pretty much all functionalities."* Each unit activity becomes a uniform
> processor chained output→input; a Pipeline is the chain; Schema and Mapping become separate,
> reusable, tabular (CSV) files; custom processors arrive as plugins on demand. The end-user surface
> today is over-complicated (20 node kinds of which 8 round-trip, three file formats for one mental
> act, structure conflated with transformation) and this plan collapses it.
>
> **What this is NOT.** Not an engine rewrite. The crash-hardened core — DuckDB layer, the
> markers-LAST commit ordering in `BatchProcessor`, the acquisition framework (StabilityGate,
> ledger, B3/B4 staging + back-pressure), `ParserPlugin`, `ConsignmentProcessor`, the ComponentStore
> registry, the fail-closed control-plane gates, the served-AttributeSpec form contract — is kept
> verbatim. What is rewritten is the **model layer**: the config document model, the user-facing
> node taxonomy, the pipeline/enrichment/matrix trichotomy, and the vocabulary.
>
> **Relationship to in-flight work.** This plan is the umbrella for, and successor of scope in:
> - [`branch-aware-executor-plan.md`](branch-aware-executor-plan.md) — its §3 operator model
>   (*"Every unit is a job… fires the next task on another virtual thread… The execution model is
>   separate from the UI abstraction"*) is **this plan's execution model**, recorded 2026-08-01.
>   Stage A 1–3 shipped (dormant graph-execution machinery), Stage B CLOSED (B1–B5), and **Stage C
>   (per-file stage housekeeping) folds into §4.5 here — this plan is its sign-off vehicle** (§9 D-5).
> - [`consignment-elt-architecture.md`](consignment-elt-architecture.md) — vocabulary (§3),
>   processor blocks (§4), storage invariants (§5–6), summary semantics (§7), persistence (§11.3
>   built), `ProcessorContext` (§14 built). This plan operationalizes it for the end user.
> - The **already-MAJOR release**: four breaking reasons are banked (BACKLOG — D15 OIDC flag, D4
>   space-purge 409, three `@PublicApi` interface additions, `61dc8280` dedup fold). This
>   unification is the fifth and the release's headline. One MAJOR, one migration, one set of notes.

---

## 1. Vocabulary (binding — ships as GLOSSARY amendments in Phase 0)

Per GLOSSARY §0: one concept → one word; one word → one concept. The user-facing surface shrinks to
**nine nouns and seven verbs**.

### 1.1 Nouns (kept / sharpened)

| Term | Definition (final) | Today |
|---|---|---|
| **Pipeline** | A named, ordered **chain of Steps** with a trigger and guarantees. The only executable the user authors. | unchanged concept; absorbs Enrichment + Matrix |
| **Step** | One unit activity in a Pipeline: a processor with the uniform contract *Consignment in → Consignment out (+ rejects)*. Drawn from the seven built-in verbs (§1.2) or a plugin. | replaces the 20-id `BuiltinNodeType` *user* surface |
| **Consignment** | The transaction unit between Steps: a set of files → a set of rows, committed atomically. Id threads manifest, output registry, Signals. ⛔ *Batch* (user-facing). | already canonical (consignment doc §3) |
| **Grammar** | Authored options telling a *parse* Step how to read one format. | unchanged (shipped 2026-08-04) |
| **Schema** | **Structure only**: the ordered field list of a feed (name, type, selector, unit, description, classification). A flat table → a CSV file. | today conflated with mapping inside `*_schema.toon` |
| **Mapping** | **New component kind.** The field map: target ← source expression (+ kind). A flat table → a CSV file. Reusable across Schemas and Pipelines. | today buried as `mapping:` inside the schema file |
| **Guarantee** | A declared property the runtime honors regardless of chain shape: file dedup, backup, quarantine, markers, gap watch, retention. **Never a Step** — and record dedup is deliberately NOT here; it is the `dedup` Step (§2.4 boundary). | today a mix of config blocks, auto-added nodes, and buried flags |
| **Dataset** | Unchanged (GLOSSARY §6-B): the queryable registration of a Table. Every *sink* Step's Table may register one. | unchanged |
| **Pipeline Document** | A **generated**, human-readable specification of one Pipeline — source, Steps, the field-level Mapping + transformation tables, Guarantees, outputs, with worked sample rows — used for business verification, sign-off, and the change loop (§5.1). Never hand-authored; always a projection of the config, stamped with a config fingerprint. | new |

### 1.2 Verbs — the closed built-in Step set

| Verb | Does | Compiles onto (exists today) |
|---|---|---|
| `collect` | files from a Connection **or rows from a Table** — the entry Step; carries the trigger | acquisition / `CollectorConnector` SPI; table-entry rides the Signal bus (§4.2) |
| `parse` | Grammar → rows; hierarchical formats → segment rows (§2.8) | `ParserPlugin` SPI, two transparent engines |
| `map` | apply Schema + Mapping | `transform.map` + `TransformCompiler` registry |
| `dedup` | drop duplicate **records** by a declared business key + winner policy (`keep: first`, `order_by`); the duplicates leave as a counted, quarantined reject stream (§2.6) — inspectable and reportable, never silently discarded. ⚠ Record-grain, distinct from **file** dedup, which is a Guarantee (§2.4). v1 scope = within one Consignment (SQL); cross-Consignment window is §9 D-9 | `transform.dedup` (`QUALIFY ROW_NUMBER()`, design doc §3.4) |
| `transform` | filter / derive / reference-join (SQL-compiled, fusable) | `transform.filter/derive/select`, `RowShaper`; reference-join = the `EnrichmentConfig` join model |
| `summarize` | group-by + algebraically-composable measures → rollup | `SummaryWriter` (in-motion tier) / `MaterializeTask` (at-rest tier) |
| `sink` | land CSV/Parquet, partitioned; emits output manifest | `Output` / `sink.persistent` + `ConsignmentOutput` registry |

Plugins extend this set (§2.5). The nine `transform.*` ids, the three `sink.*` kinds, and the
CONTROL trio become **compile targets and engine internals** — still in `BuiltinNodeType`, no longer
in the palette or the docs a user reads first.

### 1.3 Retirements (⛔ ban-list additions)

| ⛔ Banned (user-facing) | → Use | Fate of the machinery |
|---|---|---|
| *Job* | **Pipeline** (table-entry) for data work; "job" survives only as engine-internal scheduling vocabulary | `JobService`, Signal bus, `consignment.process` kept verbatim as the compile target |
| *Enrichment* (file kind `*_enrich.toon`) | a **Pipeline** whose `collect` reads a Table | `EnrichmentEngine` retired after migration (§6); the join model lives on inside `transform` |
| *Matrix* (maintenance task `materialize`) | a **Pipeline** with a `summarize` Step | `MaterializeTask` becomes the at-rest compile target of `summarize` |
| ⛔ *Flow* / `*_flow.toon` | **Pipeline** | file kind deleted (already read-only since W5) |
| *Node* (user-facing) | **Step** | `PipelineNode` IR name unchanged internally |
| bare *Batch* (for the unit of work) | **Consignment** | `BatchProcessor` etc. keep the name as legacy internals (consignment doc §3) |

GLOSSARY §13 gains one rename-map row per line above (UI → model → backend), same discipline as the
⛔ Source → **Collector** flip of 2026-07-14.

---

## 2. The model

### 2.1 One authoring shape

```yaml
# config/pipelines/orders.toon
name: orders
active: true
trigger: { poll: 60s }                     # or cron / on: commit of <pipeline> / manual
steps:
  - collect: { connection: connections/sftp_prod, files: "*.csv" }
  - parse:   { grammar: grammars/delimited_pipe }
  - map:     { schema: schemas/orders_v1, mapping: mappings/orders_std }
  - dedup:   { key: [order_id], keep: first }
  - sink:    { table: orders, format: parquet, partition_by: [order_date] }
guarantees:
  file_dedup: fingerprint
  backup: true
  gap_watch: "ORDERS_{yyyyMMdd}"
```

Read top-to-bottom the way data flows. **Linear is the default and the whole beginner surface**;
each Step's output is implicitly the next Step's input — no edges to author. Branching exists but
is an advanced, explicit escape (§9 D-6), not a thing the 80% case ever sees.

The unification in two more examples — these replace `*_enrich.toon` and the `materialize` task:

```yaml
# was: orders_enrich.toon                    # was: maintenance task "materialize"
name: orders_enriched                        name: orders_daily
trigger: { on: commit of orders }            trigger: { on: commit of orders }
steps:                                       steps:
  - collect:   { table: orders }              - collect:   { table: orders }
  - transform: { join: references/customers,   - summarize: { group_by: [region, order_date],
                 on: cust_id }                                measures: ["count()", "sum(gross)"] }
  - sink:      { table: orders_enriched }     - sink:      { table: orders_daily }
```

One concept for the user. Three execution paths for the engine — unchanged (§4.2).

### 2.2 The Step contract (execution = the operator's recorded model)

Every Step, built-in or plugin:

- **I/O:** consumes a Consignment, emits a Consignment (+ `rejects`, routed to quarantine by the
  runtime, never by user wiring). The manifest **accretes** per Step (consignment doc §4) — v1 raw
  files, v2 decoded, … — so provenance and dry-run stay coherent.
- **Execution:** each Step run is a **job on a virtual thread that fires the next Step's task on
  completion** (branch-aware-executor-plan §3, verbatim operator intent). Pipeline-level progress
  and completeness are *derived* from Step-level facts, never separately tracked.
- **Grain:** declared `RECORD | FILE | BATCH`; the validator checks it, the palette shows it.
  Record-grain must compile to SQL; file-grain should be `GROUP BY source_file`, with per-file Java
  iteration opt-in and visibly discouraged (consignment doc §4).
- **Testability:** every Step supports `preview(sample)` through its production logic (dry-run,
  run-to-here — both shipped) — no divergent test path.

### 2.3 Two entries, one shape — the §3.8 line becomes invisible

`collect` is the only entry Step and comes in two sources:

| Entry | Trigger | Engine driver (unchanged) |
|---|---|---|
| `connection:` (files, in-motion) | `poll` / `cron` / file-arrival | the Stage-B acquire + ingest timers (`PipelineScheduler`), StabilityGate, ledger, B4 back-pressure |
| `table:` (rows at rest) | `on: commit of <pipeline>` / `cron` / manual | the Signal bus — `pipeline.commit` Signal → `JobService`, the shipped `consignment.process` shape |

The in-motion vs at-rest line (pipeline-graph-design §3.8) **stays binding — as an engine seam**.
The compiler routes each Pipeline to the right driver from its `collect` source; the user never
learns two scheduler vocabularies. The one hazard §3.8 exists for (cross-driver deletion) keeps its
fence: `restsOnDisk()` + the maintenance-window rules, untouched.

### 2.4 Guarantees, not Steps

File dedup (`file_dedup: path|fingerprint|marker`), `backup`, `quarantine` (always on; key only
tunes location/retention), `markers`, `gap_watch`, `retention`. Rationale is already doctrine:
*"Housekeeping is runtime-guaranteed, not designer-wired… If a designer can forget to wire the
status sink, they will"* (consignment doc §4). The lift already auto-adds the quarantine sink; this
plan makes that the rule for the whole family. `61dc8280` (fingerprint dedup folded into
acquisition, MAJOR reason #4) was the first step of exactly this fold — the plan finishes it.

**The dedup boundary (operator call, 2026-08-05).** Dedup splits by grain, and the two halves land
on opposite sides of the Step/Guarantee line:

| | File dedup | Record dedup |
|---|---|---|
| Question | "have I already collected/processed this **file**?" | "is this **record** a duplicate by business key?" |
| Semantics | pure housekeeping — no business meaning, no configuration judgment | business logic: key choice, winner policy, and the duplicates are *data someone must see* (billing/revenue assurance) |
| Where | **Guarantee** `file_dedup:` (path / fingerprint / marker) | **the `dedup` Step** (§1.2) — authored in the chain, duplicates → quarantine as a counted reject stream |
| Engine | acquisition-node dedup (`61dc8280`) + marker check (`transform.dedup.marker` becomes a compile-internal) | `transform.dedup` (`QUALIFY`) |

Naming honors one-word-one-concept: the Step owns the bare word `dedup`; the Guarantee key is
`file_dedup`.

**Per-file stage housekeeping (Stage C, folded in).** *"Full housekeeping for every file and its
stages"* is in the operator's recorded model, so it is a Guarantee deliverable, not a later phase:
a stage progression per file over the locked `Run ⊇ Batch ⊇ File` grain (StatusStore exists), plus
the per-edge `recordsIn/recordsOut/diverted` counters pipeline-graph-design §11.3 specifies. This
answers *"where is file X right now"* and makes the conservation invariant (§11.4 — imbalance =
silent data loss) checkable. **This paragraph is the Stage C sign-off** (§9 D-5).

### 2.5 Plugins — jobs on demand, as Steps

The plugin story is the shipped `ConsignmentProcessor` SPI + `ProcessorContext` (consignment doc
§14): a plugin declares `id()`, grain, and its config schema (`FieldSpec` — the same vocabulary
`grammarSchema()` and `AttributeSpec` already serve), reads its Consignment through
`ConsignmentReader`, emits through `SummaryEmitter`/sink surfaces. It appears in the palette and
the recipe (`- my_dedupe_scorer: {…}`) with **zero UI or control-plane change** — the self-describing
pattern `ParserPlugin` proved. Duplicate ids fail startup loudly (adopt the `Parsers.load` rule, not
the override-a-builtin rule — a plugin silently shadowing `sink` is not a feature).

### 2.6 One-to-many — filter and route, without giving up the linear read

*(§9 D-6, CONFIRMED 2026-08-05: fan-out is a normal shape, not an advanced escape.)*

Three principles, then the mechanism:

1. **Only `route` creates user-visible branches.** Every other divergence — filter `dropped`,
   validate `invalid`, parse `unmatched`, dedup `duplicate` — is a **reject stream**: routed to
   quarantine by the Guarantee, tagged with its reason, counted by the per-edge counters (§2.4).
   The user never wires a reject; they *tune* where rejects rest. This keeps the concept count at
   one branching construct while preserving the conservation invariant
   (pipeline-graph-design §11.4: `in = out + rejects`, or amplification factor under clone).
2. **The trunk stays linear; `route` ends it and opens named branches — each branch its own linear
   sub-chain.** Branches may nest another `route`, so a recipe is a **tree**. What a recipe
   deliberately cannot express is a **DAG** — fan-*in* (`transform.merge`, live join/union of two
   chains) is genuinely rare, fights the batch model (design doc §3.4), and stays a canvas-authored
   advanced shape.
3. **Same data to many destinations is not routing.** The shipped multi-destination `sink` format
   (`sinks:` list, 2026-08-02) covers "land the identical rows in N places" as one Step.
   `mode: clone` routing is only for branches that **diverge in processing** after the split.

```yaml
steps:
  - collect: { connection: connections/sftp_prod, files: "*.csv" }
  - parse:   { grammar: grammars/delimited_pipe }
  - map:     { schema: schemas/orders_v1, mapping: mappings/orders_std }
  - transform: { filter: "amount > 0 AND status <> 'TEST'" }   # dropped → quarantine, counted
  - route:
      mode: case                    # case = exclusive (default) | clone = multi-match
      branches:
        emea:
          when: "region IN ('DE','FR','UK')"
          steps:
            - sink: { table: orders_emea }
        apac:
          when: "region IN ('IN','SG','JP')"
          steps:
            - transform: { derive: { tax: "gross * 0.18" } }
            - sink: { table: orders_apac }
        other:
          default: true
          steps:
            - sink: { table: orders_other }
```

Semantics are inherited, not invented: `case`/`clone` modes, mandatory wired-or-defaulted branches,
fusion breaking (a `route` is a materialisation boundary; linear runs on either side still fuse to
one SQL pass), `(consignment, branch)` commit with source finalisation only when **all** leaf
branches commit, and clone amplification charged to the batch budget — all from
pipeline-graph-design §3.4/§3.5/§3.7. The compile target exists today (`transform.route` with named
relationships + per-branch edges); the recipe form is sugar over it, and the canvas renders the
compiled tree identically to any other topology.

### 2.7 Step lifecycle — scheduled / per-Consignment / invoked / paused (operator question, 2026-08-05)

| Lifecycle verb | Answer | Mechanism |
|---|---|---|
| **Scheduled** | **Entry Step only.** `collect` carries the trigger (`poll` / `cron` / `on: commit` / manual); every downstream Step is **data-driven**. Per-Step independent scheduling is deliberately rejected — it presumes inter-Step queues (the locked no-queue decision, design doc §3.5/§3.6) | trigger on the entry node; topological walk downstream |
| **Per Consignment** | **Yes — this IS the execution model.** Every Step runs once per Consignment; each Step-run is a job on a virtual thread firing the next; commit unit `(consignment, branch)`. Post-chain plugin processors (grain `BATCH`) fire per Consignment via the commit Signal | the §2.2 contract; `consignment.process` (shipped) |
| **Invoked** | Three forms: manual Pipeline trigger; per-Step **dry-run/preview through production logic** (test endpoints, run-to-here — shipped); **reprocess a Consignment** (`ReprocessCommand` — a new Run over the same Consignment). ⛔ No standalone production invoke of a mid-chain Step: its input is a Consignment, so the request has no defined input — dry-run is the honest form | `/pipelines/{id}/trigger`, `/components/{type}/{id}/test`, reprocess |
| **Paused** | Today: `active:` (whole Pipeline) + the Stage-B **half-pause** — acquire on / ingest off, backlog parks in the durable inbox under the B4 high-water gate. **Per-Step `enabled:` is now SCHEDULED (D-13, operator call 2026-08-05)** with **batch-honest semantics**: no queues ⇒ a disabled Step halts the chain at that boundary and Consignments **park durably** — pause is admission control, never RAM buffering | `active:`, split acquire/ingest timers; `enabled:` lands Phase 4 (below) |

**Per-Step pause as the testing dev-loop (D-13).** The operator's driver: *"if we can pause the
individual Pipeline Step, we are close to a NiFi processor — real help for testing."* Exactly right,
and the batch model can deliver it without queues, because **the durable park IS the queue, at
Consignment grain**: disable Step N+1 → real Consignments process through Step N and **park at the
boundary** → inspect the actual intermediate output → enable → parked Consignments **drain** through
the rest of the chain. That is NiFi-style incremental bring-up over *production data*, complementing
the scratch-only paths that already exist (dry-run, run-to-here). Three honest costs, all bounded:
1. **A pause boundary is a materialisation boundary** — linear Steps normally fuse into one SQL pass
   (§3.4 fusion), so pausing mid-fusion forces the intermediate to materialise (temp/scratch
   partition). Same rule as `route`; the fusion optimisation resumes when the pause lifts.
2. **A parked Consignment is NOT committed** — no source finalisation, no markers; the manifest
   accretes a `parked_at: <stepId>` state (crash-safe, same accretion discipline as the Decoder
   tier). Resume = drain from the manifest, a cousin of `ReprocessCommand`.
3. **Parking needs per-file/stage progression to be inspectable** — which is exactly Stage C
   (§2.4), so `enabled:` lands **with it in Phase 4**, not before.

### 2.8 Flattener — resolved by placement, not by a new node

Tree-shaped parses (ASN.1, XML) flatten **inside `parse`** onto segment Schemas — one input record →
N rows across N segment tables, exactly what `Asn1RecordIngester` does today. The recipe surface:
`parse: { grammar: grammars/vendor_cdr, segments: [schemas/cdr_voice, schemas/cdr_data] }`. The
flatten DSL (BACKLOG §4, XML still preview-only) lands *inside this key* when built — no new Step
kind, no user-visible "Flattener" concept.

---

## 3. Files & registry

### 3.1 Layout

```
spaces/<space>/config/
  pipelines/   orders.toon            # the chain — thin, ~15 lines
  schemas/     orders_v1.csv          # structure only — flat table
  mappings/    orders_std.csv         # field map only — flat table
  grammars/    delimited_pipe.toon    # nested options — stays TOON
  connections/ sftp_prod.toon         # nested + secrets — stays TOON
  references/  customers.toon         # lookup sources for transform joins
  registry/    …                      # datasets/dashboards/widgets/expectations unchanged
```

Rule: **flat tables are CSV; anything nested or secret-bearing is TOON.** CSV kinds are
Excel-editable — deliberate, for the operators who onboard vendor feeds.

### 3.2 The two CSV component kinds

```csv
# schemas/orders_v1.csv
field,type,selector,unit,description,classification
ORDER_ID,VARCHAR,0,,Order identifier,INTERNAL
ORDER_DATE,DATE,1,,Business date of the order,INTERNAL
QUANTITY,INTEGER,4,count,Units ordered,INTERNAL
```

```csv
# mappings/orders_std.csv
target,source,kind
ORDER_ID,ORDER_ID,direct
REGION,"UPPER(TRIM(REGION))",expr
GROSS,"ROUND(QUANTITY * UNIT_PRICE, 2)",expr
```

Columns mirror the existing `FieldSpec` / mapping-rule shapes 1:1 — the TOON inline tables were
already CSV-shaped; this promotes them to real files. `ConfigCodec` gains a CSV codec for exactly
these two kinds; `ConfigSafetyValidator` applies unchanged (and must be **loud** about Excel
mangling — BOM, smart quotes, encoding; R3).

### 3.3 Reuse semantics

`use:`-by-name resolution, edit-once-reload-referencers via the `referencedFiles()` mtime
fingerprint, "what references this?" safe-delete scan — all inherited verbatim from
pipeline-graph-design §4.1. No version pinning (D9 inherited; copy-as-new-name to pin). One Schema
serves N vendor feeds; one Mapping serves N Schemas that share structure. **CSV identity deviation:**
CSV has no in-file identity block, so for the two CSV kinds identity = filename (§9 D-3).

### 3.4 Schema registry semantics — evolution, type flow, and relations (without a registry service)

*(Operator question, 2026-08-05: can schema relations and field-type info flow through a
schema-registry-kind implementation, or a library?)*

**Verdict on libraries first.** An external registry service (Confluent-style) is **rejected**: it
adds a running server and a second source of truth, against the file-based/no-catalog, offline,
single-node doctrines (§7). Avro as a schema IDL is **rejected**: our data plane is Parquet +
DuckDB; a third type system would need lossy mapping both ways. What we **borrow** is the
registry's *vocabulary and guarantees* — subjects, versions, compatibility classes
(`BACKWARD`/`FORWARD`/`FULL`/`NONE`) — implemented over four seams that already exist:

1. **The store IS the registry.** `schemas/` + `mappings/` under the ComponentStore, identity per
   §3.3, git as the version history. No new infrastructure; "what references this schema" is the
   existing scan.
2. **Compatibility gate on edit (the registry's core value).** Saving a Schema CSV diffs old→new
   and classifies every change: *additive field / type widening* = backward-compatible → allowed;
   *rename / delete / type narrowing / selector move* = **breaking** → refused with a cell-level
   error unless the operator copies to a new name (or explicitly overrides). Enforced in
   `ConfigSafetyValidator`, same fail-closed pattern as every other gate. Default class is §9 D-10.
3. **Schema-per-data provenance.** Every Parquet file already carries its physical schema in the
   footer, and the consignment doc §5.6 already requires partition-affecting config **pinned in the
   manifest** — this extends the same rule: the manifest and the `ConsignmentOutput` row record the
   **schema fingerprint** that wrote each Consignment. Schema evolution history per partition falls
   out for free, and reprocessing/late readers know exactly which schema version produced which
   files — the registry's "data carries its schema id," without the id server.
4. **Type flow = compile-time inference through the chain.** Every core Step compiles to SQL, so
   the compiler can `DESCRIBE` the fused query at each Step boundary **without executing it** —
   deriving each Step's *output Schema* from the declared input Schema + Mapping + transforms.
   DuckDB is the type authority (the `FieldSpec` types are already its types); zero new
   dependencies. What the derived per-Step schemas power:
   - **validation at save** — a Mapping referencing a nonexistent field, a route predicate over a
     dropped column, a summarize over a non-numeric measure: all cell-level errors before anything runs;
   - **the Pipeline Document** (§5.1) — per-Step input→output schema tables, generated not authored;
   - **Dataset auto-registration** — the sink's derived schema becomes the Dataset's
     `columns{name,type,role}` block, replacing today's hand-authored column lists;
   - **plugin contract** — a plugin Step cannot be SQL-described, so the SPI declares its output
     schema (precedent: `Asn1RecordIngester` already lands on declared segment Schemas).

**Schema relations** are two different things, resolved differently:
- **Structural** (raw→canonical, parse segment fan-out): already derived by `MetadataGraphService`
  (`PRODUCES`/`HAS_COLUMN`/`FEEDS`/`COMPUTED_FROM`) — the lineage graph is the relation registry.
- **Semantic joins** (FK-ish: `orders.cust_id → customers.id`): **derive from usage first** — every
  `transform: {join: …, on: …}` and `summarize` group-by declares a relation operationally, and the
  catalog graph can project them (feeding link-analysis views and the Document's relation section).
  A hand-authored `relations` CSV component is possible later if business wants relations that no
  Pipeline exercises yet — deferred, §9 D-11.

---

## 4. Execution model

1. **Compile, don't interpret.** A recipe compiles through the existing lower path (recipe → IR →
   engine primitives). The dormant Stage-A machinery (graph vocabulary made executable) is the
   compile target; a linear recipe is strictly easier to lower than the free-form graph the lift
   already round-trips. Linear `map`/`transform` runs **fuse into one SQL pass** (§3.4 fusion rule
   inherited) — Step count is a logical unit, not N physical passes.
2. **Drivers unchanged.** File-entry pipelines: Stage-B acquire/ingest timers, `acquireGuard` /
   `runGuard` non-overlap, T15 adaptive admission, B4 high-water back-pressure. Table-entry
   pipelines: `pipeline.commit` Signal with `consignmentId` correlation → the `consignment.process`
   job shape. Two schedulers, one responsibility each — internal.
3. **Commit semantics inherited whole.** Markers-LAST crash ordering; source finalised only when
   all branches commit (`(consignment, branch)` unit, §3.7); idempotent sink writes; the
   `ConsignmentOutput` registry (§11.3, built) is the output ledger feeding compaction + lineage.
4. **Storage doctrine inherited whole.** Append-only Hive-style partitions, no catalog, no table
   format; compaction with the reprocessing horizon; summaries must be algebraically composable
   (count/sum/min/max + composable sketches — never raw AVG), partial aggregates are forced and
   merged at read (consignment doc §5–§7).
5. **Observability = the housekeeping Guarantee** (§2.4): per-file stage progression + per-edge
   counters + the conservation check, surfaced on the same G6 canvas as an overlay (the data plane
   of pipeline-graph-design §11).

---

## 5. Control plane & UI

- **API.** `/pipelines` CRUD unchanged. `GET /pipelines/node-types` → **`GET /pipelines/step-types`**:
  seven verbs + discovered plugins, each with served `AttributeSpec[]` — the config-key contract
  (attribute key = config key, `__` = nesting) is kept verbatim; specs must reach **all** seven verbs
  (today only 5 of 20 types have them). Component CRUD generalises to the CSV kinds. Dry-run,
  run-to-here, per-component `test` endpoints — kept.
- **UI.** The default editor becomes a **recipe editor**: an ordered list of Step cards; "add Step"
  offers seven verbs + plugins; each card's form is schema-form over served specs;
  `GrammarEditorDialog` stays the parse card's editor. The G6 canvas is **demoted to
  visualization** (topology + live overlay + provenance Sankey) and the advanced branching surface.
  Guarantees render as a fixed checklist panel, not draggable anything.
- **Onboarding** (W0–W5) already walks collect → parse → map → sink; it aligns 1:1 with the recipe
  and stops needing its own vocabulary.

### 5.1 The Pipeline Document — business verification, sign-off, and the change loop

**The business requirement (operator, 2026-08-05):** generate a pipeline document with the detailed
data mapping + transformation per relevant Step, so the solution can be **verified** by business
stakeholders before/after build, and remain **open to alter/change** — addressed from configuration
details, never written by hand.

**Why this plan makes it nearly free:** the document is a *projection of config*, and after Phase 1
the config's field-level heart **is already tabular** — `schemas/*.csv` and `mappings/*.csv` render
into the document's mapping tables verbatim. No parallel spec to drift.

- **Surface:** `GET /pipelines/{id}/document` → Markdown (printable/PDF-able; XLSX export per
  §9 D-8). UI: an "Export document" action on the Pipeline; regenerated on demand, never stored as
  truth.
- **Contents, per Step kind:**
  | Step | Document section |
  |---|---|
  | `collect` | source system, connection (secrets masked), file patterns, schedule/trigger, guarantees in force (file-dedup mode, backup, gap watch) |
  | `parse` | format + Grammar summary; segment fan-out table for hierarchical formats |
  | `map` | **the field table** — target, source field/expression, kind, type, unit, description, classification — a straight join of the Schema and Mapping CSVs |
  | `dedup` | business key, winner policy (`keep`, `order_by`), scope; duplicate counts and their quarantine location |
  | `transform` | filter predicates; derivation table (new field ← expression); reference joins (source, keys) |
  | `route` | branch table: name, condition, mode, destination chain |
  | `summarize` | group-by keys + measure definitions |
  | `sink` | table, format, partitioning, retention; registered Dataset |
- **Worked examples — the verification feature:** the document embeds **sample rows per Step**
  (input row → output row), produced by the shipped dry-run machinery (§7.2 endpoints run the
  *production* Step logic over a bounded sample). Business reviewers verify against real
  transformations, not prose.
- **Sign-off binding:** the document header carries the Pipeline name/version and a **config
  fingerprint** (hash over the recipe + every `use:`-referenced component — the same
  `referencedFiles()` set the mtime watch already computes). An approved document is verifiably
  tied to what runs; if config changes later, the fingerprint mismatch flags the approval as stale.
- **The alter/change round trip:** because the mapping tables *are* CSV components, a reviewer's
  edits (in Excel or the exported table) **import back** as a proposed Mapping change:
  upload → `ConfigSafetyValidator` + cell-level CSV validation (§3.2) → dry-run preview diff
  (old vs new output on the same sample) → apply. Change requests flow through the same one file
  the engine runs — review artifact and runtime artifact cannot diverge.
- **Grounding (all existing):** Schema columns already carry `description`/`unit`/`classification`
  (served today); semantic descriptions/grains exist in the meta layer (e.g.
  `events_meta.toon`); lineage comes from `MetadataGraphService`; dry-run + run-to-here shipped;
  `DescriptionProvider` SPI exists for prose enrichment.

---

## 6. Migration — what "final time" means

1. **One-shot converter** (`inspecto migrate-configs`): every `*_pipeline.toon` → `pipelines/*.toon`
  recipe; every `*_schema.toon` splits → `schemas/*.csv` + `mappings/*.csv`; every `*_enrich.toon` →
  a table-entry recipe; every `materialize` maintenance task → a `summarize` recipe. Originals to
  `archived-config/` untouched. Deterministic, dry-run-first, refuses on any lossy case.
2. **Parity gate:** the full suite (incl. the ~80 fixtures, plugin-ingester and multi-schema cases)
  green through the compiled-recipe path **before** any legacy path is removed — the same
  discipline as lift T1/T5a.
3. **Legacy read path:** kept behind a flag for one verification minor, then deleted (§9 D-2).
  `*_flow.toon` deleted at the MAJOR (already read-only).
4. **Release:** this is breaking reason #5; the MAJOR's release notes cover all five, per
  `release-workflow`.

---

## 7. What does NOT change (inherited locked decisions)

Append-only / no catalog / no Iceberg · no streaming runtime, no inter-node queues (except the
sanctioned spill-edge escalation already exercised by B4) · single-node ceiling accepted (NFR-8) ·
editions = build flavors, never branches · no component version pinning in v1 · ConfigSafetyValidator
fail-closed gates · the DuckDB native-access launch flag · markers-LAST · algebraic-only measures ·
sealing/completeness tier (consignment doc §8–9) unchanged and out of this plan's critical path.

---

## 8. Phases (each shippable; verify gate stated)

| Phase | Delivers | Verify gate |
|---|---|---|
| **0** | Vocabulary finalized: GLOSSARY §1.2/§1.3 amendments + §13 rows; this plan APPROVED | ✅ **DONE 2026-08-05** — §9 signed off; GLOSSARY amended (§3 Schema/Mapping + collision-resolution note, §5 Pipeline/Step/Transform/Enrichment/Guarantee/Pipeline Document, §6-A Job retirement note, §13 six new rename rows); vocabulary guard green (4 passes: 9 user-facing + 148 tree docs + 142 TOON + 1382 source files) |
| **1** | Mapping split out of Schema + CSV codec for the two kinds (independent, immediate reuse value) + the **schema compatibility save-gate** (§3.4.2) | round-trip tests; existing schema fixtures load via both shapes; breaking edit refused with cell-level error |
| **2** | Recipe format + compiler onto existing primitives; converter (read side); **per-Step type flow** (§3.4.4 — `DESCRIBE`-derived output schemas) + schema fingerprint pinned in manifest/`ConsignmentOutput` (§3.4.3) | full suite green through compiled path; converter round-trips all fixtures; derived sink schema matches actual Parquet footer on every fixture |
| **3** | Table-entry `collect` (+ `summarize` verb) — enrich/matrix unification onto the Signal bus | events/orders enrich + a materialize task run as recipes with identical outputs |
| **4** | Guarantees fold + Stage C per-file stage progression + per-edge counters + **per-Step `enabled:` with park/drain (D-13)** | conservation check runs on a fixture; "where is file X" answerable via API; a fixture Consignment parks at a disabled Step, is inspectable, and drains cleanly on re-enable |
| **5** | UI recipe editor (incl. the §2.6 route surface); canvas demoted; `step-types` endpoint; specs for all seven verbs; **Pipeline Document generator + mapping import loop (§5.1)** | GAUNTLET; axe-core gate; onboarding walkthrough over the recipe editor; document regenerates deterministically from fixtures and an edited mapping CSV round-trips through validate → preview → apply |
| **6** | Migration executed; legacy formats removed | suite green with legacy path deleted |
| **7** | **Batch→Consignment rename (D-12)** — the §13 "save for last" row, executed as the final pre-release phase; then the MAJOR cut | rename scoped by concept (grouping-sense `batch` untouched); read-aliases verified on persisted rows; full suite green; release notes cover all 5 breaking reasons + the rename |

Phases 1–3 are pure backend and de-risk everything (same shape as the graph design's own roadmap).
The UI slices are planned separately in [`elt-amendment-ui-plan.md`](elt-amendment-ui-plan.md)
(companion, v1.0) — S1–S3 there are unblocked before any backend phase lands, because the recipe
editor is a second projection of the existing `AuthoredPipeline` model.

#### Phase 1 GROUNDED 2026-08-05 — five findings that correct this plan's premises

1. **There is no `SchemaConfig` class and no schema-specific parser.** The schema `.toon` decodes to
   a raw `Map` (`ConfigCodec.toMap`) consumed structurally: `PipelineConfigParser.resolveSchemaRef`
   (`:624`; three branches at `:342/:371/:404`), `DataTransformer.materialize` reads
   `mapping.rules` as `List<Map<String,String>>` (`:60-115`), `PartitionDef.fromSchema` (`:62-94`),
   `TransformCompiler.dataColumn` (`:28-81`). The split therefore needs a **merge point** that keeps
   handing downstream one conflated map — not a model refactor.
2. **`ConfigCodec` is TOON-only with zero format abstraction** (61 lines, hardcoded JToon,
   `@PublicApi 4.0.0`); atomic writes live in `AtomicFiles`, and no secret masking exists on the
   schema path (none needed). The plan's "CSV codec" is therefore **not** a codec-registry job in
   slice 1 — a plain reader for the two flat shapes is the honest size.
3. **The compatibility-gate seam is `ConfigRoutes.writeConfig`/`patchConfig`** (`:114-179`, atomic
   write at `:165`), which already holds both the existing file and the draft. `ConfigSpecs.schema()`
   is UI-description-only, **`ConfigSpecs.schemaComponent()` does not exist**, and
   `ConfigSafetyValidator` deliberately skips `schema` — the BACKWARD diff (§3.4.2) is genuinely new
   logic inserted as ERROR findings in that route, not a wiring job.
4. **`schema` was deliberately removed from `ComponentStore.WRITABLE_TYPES`** (2026-07-31, W1:
   the id-addressed registry copy was never wired to execution; only path-addressed
   `processing.schema_file` executes) — while `ComponentRegistry.TYPE_BY_DIR` still lists
   `schemas/` read-side. **Sequencing call:** slice 1 keeps Mapping **path-addressed** (a sibling
   `<name>_mapping.csv` dual-read, injected into the decoded map when present — additive, zero
   fixture impact); promoting Schema/Mapping to id-addressed component kinds (`WRITABLE_TYPES` +
   `TYPE_BY_DIR` + `ComponentRoutes`) is a later slice and must resolve that latent
   read/write inconsistency deliberately.
5. **The "~80 fixtures" figure was wrong.** Reality: 7 real `*_schema.toon` (all with `mapping:`,
   3 with `partitions:`, none with `segments:`) + 18 `examples/**/schema.toon` + inline TOON
   literals across 28 Java test files. The Phase-2 parity gate counts THOSE, not 80 files.

**Slice 1 of record:** dual-read only — sibling `_mapping.csv` overrides `mapping.rules` at the
`resolveSchemaRef` merge point; plain CSV parse; `ConfigSpecs`/`ConfigSafetyValidator`/
`ComponentStore`/`ConfigRoutes` untouched until slices 2 (split-write + gate) and 3 (component kinds).
**✅ SHIPPED 2026-08-05** — `PipelineConfigParser.mergeSiblingMapping` wired at all three schema-decode
sites (segment/multi/single), before `Identifiers.validateSchema` so merged rules validate and count
toward `declaredColumns`; CSV header `targetColumn,sourceExpression,transformType` (any order, blank
kind = DIRECT, quoted cells carry commas); the CSV joins `referencedFiles` for hot-reload.
`MappingCsvDualReadTest` (6 tests); module suite green (222/0).

**Slices 2+3 SHIPPED 2026-08-05.** Slice 2 (split-write + BACKWARD gate): shared
`com.gamma.util.MappingCsv` (RFC4180 via the platform `Csv` reader; canonical header
`targetColumn,sourceExpression,transformType`, §3.2's `target,source,kind` accepted as read
aliases); `SchemaCompatibility.check` (inspecto-config) = the §3.4.2 BACKWARD diff → cell-level
findings (`raw.fields[NAME]`/`.type`/`.selector`); `ConfigRoutes` write/patch of a schema now
split-writes `mapping.rules` to the sibling `<name>_mapping.csv`, reads/serves the conflated view,
deletes the sibling with the TOON, and refuses breaking overwrites 422 unless
`compatibility: "none"` (D-10's explicit override). Slice 3 (component kinds): `mapping` =
first CSV-backed registry kind (`registry/mappings/<id>.csv`, filename = identity per D-3, no
sharing envelope by shape); `schema` re-added to `WRITABLE_TYPES` **with the W1 objection
resolved** — `processing.schema_file: schema/<id>` now executes the registry copy
(`resolveSchemaRef`, mirroring `grammar/<id>`), and `processing.mapping_file` (path or
`mapping/<id>`) supplies rules with precedence explicit > sibling > inline. Tests:
`MappingCsvTest`, `SchemaCompatibilityTest`, `MappingComponentTest`, `ControlApiSchemaSplitTest`
(+ the two W1 guard tests flipped to the new contract). Full reactor green.
**Phase 1 remaining:** the schema *structure* CSV shape (§3.2 first table) — schemas persist as
TOON for now; revisit with Phase 2's type flow.

---

## 9. Decisions of record (ALL RESOLVED 2026-08-05 — operator took the recommended option on each)

| # | Decision | Resolution (binding) |
|---|---|---|
| D-1 | **Step verb names** | `collect / parse / map / dedup / transform / summarize / sink` (dedup a verb by the 2026-08-05 operator call, §2.4). Considered and rejected: `load` for sink; a distinct `enrich` verb (D-4) |
| D-2 | **Legacy read path** at the MAJOR | Converter + **one flagged verification minor**, then the legacy readers are deleted. No permanent dual-format |
| D-3 | **CSV component identity** | **Filename = identity** for the two CSV kinds — a documented deviation from D9's in-file identity (CSV has no identity block) |
| D-4 | **Reference-join surface** | `transform: { join: references/x, on: k }` — one verb, fewer concepts. No `enrich` verb; the word stays banned user-facing (§1.3) |
| D-5 | **Stage C sign-off** | **SIGNED OFF — folded here** as §2.4 (housekeeping Guarantee) + Phase 4. `branch-aware-executor-plan.md` §5 Stage C closes by reference to this plan |
| D-6 | **Branching surface in recipes** | **§2.6 confirmed**: `route` with named nested branch chains — recipe = tree; rejects Guarantee-routed, never user-wired; fan-*in* stays canvas-only |
| D-7 | **`sink.materialized` / `sink.view`** | `materialized` = `summarize`'s internal compile target; `view` = Dataset-registration sugar. Both leave the user surface |
| D-8 | **Pipeline Document scope (§5.1)** | v1 = Markdown export + embedded dry-run examples + the CSV-mapping **import** loop. XLSX export = fast-follow |
| D-9 | **Cross-Consignment record dedup** | v1 `dedup` = within-Consignment (SQL `QUALIFY`, exists). The windowed keyed ledger (`scope: window(P4D)`) is a designed fast-follow — **never** faked with unbounded history |
| D-10 | **Schema compatibility class (§3.4)** | **`BACKWARD`** save-gate default: additive fields + type widening allowed in place; rename/delete/narrow/selector-move refused → copy-as-new-name or explicit override |
| D-11 | **Declared semantic relations** | **Derived from usage** (joins/group-bys project into the catalog graph — no authoring burden). A hand-authored `relations` CSV component is deferred until a business relation exists that no Pipeline exercises |
| D-12 | **Batch→Consignment rename** *(resolved 2026-08-05 — operator: "go")* | **Taken INSIDE the MAJOR window as Phase 7**, the final pre-release phase — the `AnnotationKinds` precedent (post-release the same rename costs a deprecation cycle). Scoped **by concept, not by string** (GLOSSARY §13 row): only the unit-of-work entity renames (`BatchEvent`/`BatchManifest`/`BatchProcessor`/…, `batch_id` where persisted); the generic grouping sense stays `batch` (`batch_max_files`, JDBC/telemetry batching). Read-aliases for persisted rows, never a hard cutover. **Sequenced LAST** — 517 files / 39 `@PublicApi` types is the largest blast radius, and every earlier phase touches `Batch*` files |
| D-13 | **Per-Step pause (`enabled:`)** *(resolved 2026-08-05 — operator: the NiFi-processor testing loop)* | **Scheduled, Phase 4**, with park-at-boundary semantics (§2.7): disable → Consignments park durably at the boundary → inspect real intermediates → enable → drain. Pause boundary = materialisation boundary; a parked Consignment is uncommitted (manifest `parked_at`); requires the Stage-C stage progression, hence Phase 4. Dry-run/run-to-here remain the scratch-only paths |

---

## 10. Risks

- **R1 Compile parity.** The recipe compiler must reproduce byte-equivalent behaviour for migrated
  configs. Mitigation: the fixture suite through both paths (Phase 2 gate) — the same bar the lift met.
- **R2 Vocabulary churn while other threads fly.** AGT-6a and the assistant tools speak today's
  vocabulary. Mitigation: Phase 0 lands GLOSSARY first; renames roll UI → model → backend per §13.
- **R3 CSV fragility.** Excel adds BOMs, smart quotes, re-encodes. Mitigation: validator refuses
  loudly with cell-level errors; canonical serializer rewrites on save.
- **R4 Canvas users.** The canvas remains (visualization + advanced); nothing is deleted from the
  read surface. The recipe editor is additive until Phase 6.
- **R5 Signal-path coupling.** Table-entry pipelines ride `pipeline.commit` — keep the
  `consignment.process` contract (`consignmentId` correlation) frozen; plugins already depend on it.
- **R6 Scope gravity.** This plan deliberately does not touch sealing/completeness (§8–9 of the
  consignment doc), Kafka, or Postgres multi-user. If an iteration pulls them in, that is a new plan.
- **R7 The document mistaken for the truth.** Business will treat a signed-off Pipeline Document as
  the contract while config evolves underneath. Mitigation: the config fingerprint in the header
  (§5.1) makes staleness detectable and cheap to surface ("approval predates current config");
  the document is regenerated on demand and never stored as an editable artifact.
